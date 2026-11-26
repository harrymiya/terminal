"""Persistent configuration and remote session command construction."""
import json
import hashlib
import base64
import os
from pathlib import Path
import shlex
import tempfile
import time
from datetime import datetime, timezone
import uuid
import re
import subprocess
import select
import socket
import urllib.request
from urllib.parse import quote

DEFAULT_AGENTS = [
    {"name": "Codex", "command": "codex"},
    {"name": "OpenCode", "command": "opencode"},
    {"name": "Antigravity", "command": "agy"},
    {"name": "Shell", "command": "bash -l", "icon": "brand:shell"},
]

MAX_IMAGE_BYTES = 20 * 1024 * 1024
MAX_FILE_BYTES = 20 * 1024 * 1024


def format_file_size(size):
    if size >= 1024 * 1024:
        return f"{size / (1024 * 1024):.1f} MB"
    return f"{max(1, round(size / 1024))} KB"


def image_upload_command():
    """Write stdin atomically to a private remote file and return its absolute path."""
    name = uuid.uuid4().hex + ".png"
    return ("umask 077; dir=\"$HOME/.remote-dev-terminal/images\"; "
            "mkdir -p -- \"$dir\" || exit 1; "
            f'tmp="$dir/.{name}.tmp"; target="$dir/{name}"; '
            "trap 'rm -f -- \"$tmp\"' EXIT HUP INT TERM; "
            "cat > \"$tmp\" && mv -- \"$tmp\" \"$target\" && printf '\\0%s\\0' \"$target\"")


def upload_image(host, control_path, image):
    if not image or len(image) > MAX_IMAGE_BYTES:
        raise ValueError("图片为空或超过 20 MB")
    address = ssh_address(host)
    argv = ["ssh", "-S", control_path, "-o", "ControlMaster=no", "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=5", "-p", str(ssh_port(host)), "-l", host["username"],
            "--", address, image_upload_command()]
    result = subprocess.run(argv, input=image, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip() or "图片上传失败")
    parts = result.stdout.split(b"\0")
    try:
        path = parts[-2].decode("utf-8") if len(parts) >= 3 and parts[-1] == b"" else ""
    except UnicodeDecodeError:
        path = ""
    if not path.startswith("/") or "\n" in path or "\r" in path:
        raise RuntimeError("远端未返回有效的图片路径")
    return path


def file_upload_command(extension=""):
    """Write stdin atomically to a private remote file in ~/.remote-dev-terminal/files and return its absolute path."""
    name = uuid.uuid4().hex + extension
    return ("umask 077; dir=\"$HOME/.remote-dev-terminal/files\"; "
            "mkdir -p -- \"$dir\" || exit 1; "
            f'tmp="$dir/.{name}.tmp"; target="$dir/{name}"; '
            "trap 'rm -f -- \"$tmp\"' EXIT HUP INT TERM; "
            "cat > \"$tmp\" && mv -- \"$tmp\" \"$target\" && printf '\\0%s\\0' \"$target\"")


def upload_file(host, control_path, file_bytes, filename=""):
    if not file_bytes or len(file_bytes) > MAX_FILE_BYTES:
        raise ValueError("文件为空或超过 20 MB")
    ext = os.path.splitext(filename)[1].lower() if filename else ""
    if not (2 <= len(ext) <= 16 and ext[1:].isalnum()):
        ext = ""
    address = ssh_address(host)
    argv = ["ssh", "-S", control_path, "-o", "ControlMaster=no", "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=5", "-p", str(ssh_port(host)), "-l", host["username"],
            "--", address, file_upload_command(ext)]
    result = subprocess.run(argv, input=file_bytes, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip() or "文件上传失败")
    parts = result.stdout.split(b"\0")
    try:
        path = parts[-2].decode("utf-8") if len(parts) >= 3 and parts[-1] == b"" else ""
    except UnicodeDecodeError:
        path = ""
    if not path.startswith("/") or "\n" in path or "\r" in path:
        raise RuntimeError("远端未返回有效的文件路径")
    return path


def config_path():
    return Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "remote-dev-terminal" / "config.json"


def load_config(path=None):
    path = path or config_path()
    if not path.exists():
        return {"hosts": [], "agents": [dict(a) for a in DEFAULT_AGENTS], "sync_url": "", "sync_token": "", "offline_mode": False}
    with path.open(encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict) or not isinstance(data.get("hosts"), list) or not isinstance(data.get("agents"), list):
        raise ValueError("配置文件格式无效")
    data.setdefault("sync_url", "")
    data.setdefault("sync_token", "")
    data.setdefault("offline_mode", False)
    migrated = False
    for host in data["hosts"]:
        if not host.get("id"):
            host["id"] = uuid.uuid4().hex
            migrated = True
        if "legacy_address" not in host: host["legacy_address"] = host.get("address", "")
        if "external_address" not in host:
            host["external_address"] = host.get("address", "")
            migrated = True
        if "lan_address" not in host:
            host["lan_address"] = ""
            migrated = True
        if not data["sync_url"] and host.get("sync_url"):
            data["sync_url"] = host["sync_url"]
        if not data["sync_token"] and host.get("sync_token"):
            data["sync_token"] = host["sync_token"]
        host.pop("sync_url", None)
        host.pop("sync_token", None)
    if migrated: save_config(data, path)
    return data


def save_config(data, path=None):
    path = path or config_path()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".config-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            persisted = dict(data)
            persisted["hosts"] = [{key: value for key, value in host.items() if key not in ("_active_address", "_active_port")} for host in data.get("hosts", [])]
            json.dump(persisted, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)



def save_host_password(credential_id, password):
    """Store an SSH password in the user's Secret Service keyring."""
    try:
        result = subprocess.run(
            ["secret-tool", "store", "--label=Remote Dev Terminal SSH", "application", "remote-dev-terminal", "host", credential_id],
            input=password, text=True, capture_output=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError("无法访问系统密钥环，请安装 secret-tool 并确保密钥环已解锁") from exc
    if result.returncode:
        raise RuntimeError("无法保存 SSH 密码到系统密钥环：" + (result.stderr.strip() or "密钥环不可用"))


def load_host_password(credential_id):
    if not credential_id: return ""
    try:
        result = subprocess.run(["secret-tool", "lookup", "application", "remote-dev-terminal", "host", credential_id], capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired): return ""
    return result.stdout.rstrip("\n") if result.returncode == 0 else ""


def clear_host_password(credential_id):
    try:
        result = subprocess.run(
            ["secret-tool", "clear", "application", "remote-dev-terminal", "host", credential_id],
            capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError("无法访问系统密钥环") from exc
    if result.returncode:
        raise RuntimeError("无法删除密钥环中的 SSH 密码：" + (result.stderr.strip() or "密钥环不可用"))


def ssh_auth_environment(host, askpass_path):
    env = os.environ.copy()
    env.update(SSH_ASKPASS=str(askpass_path), SSH_ASKPASS_REQUIRE="force")
    if host.get("credential_id"):
        env["RDT_SSH_CREDENTIAL_ID"] = host["credential_id"]
    else:
        env.pop("RDT_SSH_CREDENTIAL_ID", None)
    return env


def sync_data_request(config, method, path, item=None):
    url = config.get("sync_url", "").rstrip("/")
    if not url.startswith(("http://", "https://")):
        raise ValueError("请先配置同步服务地址")
    url += path
    body = json.dumps(item, ensure_ascii=False).encode() if item is not None else None
    req = urllib.request.Request(url, data=body, method=method, headers={
        "Authorization": "Bearer " + config.get("sync_token", ""), "Content-Type": "application/json"})
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    with urllib.request.build_opener(NoRedirect, urllib.request.ProxyHandler({})).open(req, timeout=5) as response:
        return json.load(response)


def operation_log_account(config_or_entry):
    if not isinstance(config_or_entry, dict):
        return ""
    if "sync_username" in config_or_entry:
        return str(config_or_entry.get("sync_username", "")).strip().casefold()
    return str(config_or_entry.get("account", "")).strip().casefold()


def filter_operation_logs(logs, account="", limit=200):
    target = str(account or "").strip().casefold()
    filtered = [
        item for item in (logs or [])
        if isinstance(item, dict) and item.get("id")
        and str(item.get("account", "")).strip().casefold() == target
    ]
    return sorted(filtered, key=lambda x: int(x.get("created_at", 0)), reverse=True)[:limit]


def merge_operation_logs(local_logs, remote_logs, limit=200, account=None):
    merged = {}
    target = str(account).strip().casefold() if account is not None else None
    for item in (local_logs or []):
        if isinstance(item, dict) and item.get("id"):
            merged[str(item["id"])] = dict(item)
    for item in (remote_logs or []):
        if isinstance(item, dict) and item.get("id"):
            entry = dict(item)
            if target is not None:
                entry["account"] = target
            log_id = str(entry["id"])
            current = merged.get(log_id)
            current_updated = int(current.get("updated_at", current.get("created_at", 0))) if current else -1
            entry_updated = int(entry.get("updated_at", entry.get("created_at", 0)))
            if current is None or entry_updated >= current_updated:
                merged[log_id] = entry
    return sorted(merged.values(), key=lambda x: int(x.get("created_at", 0)), reverse=True)[:limit]


def apply_active_session_titles_to_operation_logs(logs, active_titles, account=None, now=None):
    target_account = str(account).strip().casefold() if account is not None else None
    titles = {}
    for active in active_titles or []:
        if not isinstance(active, dict):
            continue
        session_id = str(active.get("session_id", "")).replace("-", "").casefold()
        session_name = str(active.get("session_name", "")).strip()
        title_source = active.get("title_source", "default")
        if session_id and session_name and title_source in ("agent", "manual"):
            titles[session_id] = (session_name, title_source)

    result = []
    changed = []
    current_time = int(now if now is not None else time.time() * 1000)
    for item in logs or []:
        if not isinstance(item, dict):
            continue
        entry = dict(item)
        session_id = str(entry.get("session_id", "")).replace("-", "").casefold()
        active_title = titles.get(session_id)
        if active_title and (target_account is None or operation_log_account(entry) == target_account):
            session_name, title_source = active_title
            old_source = entry.get("title_source", "default")
            # An agent title must never replace a name the user edited manually.
            if not (title_source == "agent" and old_source == "manual") and (
                    entry.get("session_name") != session_name or old_source != title_source):
                previous_updated = int(entry.get("updated_at", entry.get("created_at", 0)))
                entry["session_name"] = session_name
                entry["title_source"] = title_source
                entry["updated_at"] = max(current_time, previous_updated + 1)
                changed.append(entry)
        result.append(entry)
    return result, changed


def sync_operation_logs(config, local_logs=None, limit=200):
    account = operation_log_account(config)
    all_local = list(local_logs or [])
    scoped_local = filter_operation_logs(all_local, account, limit=limit)
    if scoped_local:
        res = sync_data_request(config, "POST", "/v1/operation-logs", {"logs": scoped_local[:50]})
    else:
        res = sync_data_request(config, "GET", f"/v1/operation-logs?limit={limit}")
    return merge_operation_logs(all_local, res.get("logs", []), limit=max(limit, 500), account=account)


def sync_request(config, method, session_id=None, item=None, host=None):
    if host:
        path = "/v1/hosts/" + sync_host_key(host) + "/users/" + quote(host.get("username", ""), safe="") + "/sessions"
        return sync_data_request(config, method, path + ("/" + session_id if session_id else ""), item)
    return sync_data_request(config, method, "/v1/sessions" + ("/" + session_id if session_id else ""), item)


def sync_host_key(host):
    if host.get("id"): return str(host["id"])
    sync_id = str(host.get("sync_id", "")).strip()
    if sync_id: return hashlib.sha256(("manual:" + sync_id).encode("utf-8")).hexdigest()
    fingerprint = host.get("_sync_fingerprint", "")
    if fingerprint: return hashlib.sha256(fingerprint.encode("utf-8")).hexdigest()
    return hashlib.sha256(host.get("address", "").lower().encode("utf-8")).hexdigest()


def legacy_sync_keys(host):
    values = []
    sync_id = str(host.get("sync_id", "")).strip()
    if sync_id: values.append(hashlib.sha256(("manual:" + sync_id).encode("utf-8")).hexdigest())
    fingerprint = host.get("_sync_fingerprint", "")
    if fingerprint: values.append(hashlib.sha256(fingerprint.encode("utf-8")).hexdigest())
    for address in (host.get("legacy_address", ""), host.get("address", ""), host.get("lan_address", ""), host.get("external_address", "")):
        if address: values.append(hashlib.sha256(address.lower().encode("utf-8")).hexdigest())
    return list(dict.fromkeys(values))


def ssh_address(host, force=False):
    if not force and host.get("_resolved") and host.get("_active_address") is not None:
        return host.get("_active_address", "")
    configured_port = host.get("port")
    candidates = [(host.get("lan_address", ""), host.get("lan_port") or configured_port), (host.get("external_address", ""), host.get("external_port") or configured_port)]
    if not any(address for address, _port in candidates) and host.get("address"):
        candidates.append((host["address"], configured_port))
    normalized = []
    for address, raw_port in candidates:
        if not address or not str(address).strip():
            continue
        try:
            port = int(raw_port)
        except (TypeError, ValueError):
            continue
        if 1 <= port <= 65535:
            normalized.append((str(address).strip(), port))
    candidates = list(dict.fromkeys(normalized))
    for address, port in candidates:
        try:
            with socket.create_connection((address, port), timeout=1.2):
                host["_active_address"], host["_active_port"] = address, port
                host["_resolved"] = True
                return address
        except (OSError, ValueError):
            continue
    if candidates:
        host["_active_address"], host["_active_port"] = candidates[0]
        host["_resolved"] = True
        return candidates[0][0]
    try:
        fallback_port = int(configured_port)
    except (TypeError, ValueError):
        fallback_port = 0
    host["_active_address"], host["_active_port"] = "", fallback_port
    host["_resolved"] = True
    return ""


def ssh_port(host):
    if not host.get("_active_address"):
        ssh_address(host)
    try:
        port = int(host.get("_active_port", host.get("port", 0)))
    except (TypeError, ValueError):
        return 0
    return port if 1 <= port <= 65535 else 0


def ssh_host_fingerprint(host):
    known_hosts = Path.home() / ".ssh" / "known_hosts"
    if not known_hosts.exists(): return ""
    keys = []
    configured_port = host.get("port")
    candidates = [(host.get("lan_address", ""), host.get("lan_port") or configured_port), (host.get("external_address", ""), host.get("external_port") or configured_port)]
    if not any(address for address, _port in candidates) and host.get("address"):
        candidates.append((host["address"], configured_port))
    normalized = []
    for address, raw_port in candidates:
        try:
            port = int(raw_port)
        except (TypeError, ValueError):
            continue
        if address and 1 <= port <= 65535:
            normalized.append(f"[{address}]:{port}")
    for lookup in dict.fromkeys(normalized):
        args = ["ssh-keygen", "-F", lookup, "-f", str(known_hosts)]
        try: result = subprocess.run(args, capture_output=True, timeout=5)
        except (OSError, subprocess.TimeoutExpired): continue
        for line in result.stdout.decode("utf-8", errors="ignore").splitlines():
            fields = line.split()
            if len(fields) >= 3 and not fields[0].startswith("#"):
                try: keys.append(base64.b64decode(fields[2]))
                except Exception: pass
    return "SHA256:" + base64.b64encode(hashlib.sha256(sorted(keys)[0]).digest()).decode("ascii").rstrip("=") if keys else ""


def sync_auth_request(config, action, username, password):
    url = config.get("sync_url", "").rstrip("/") + "/v1/auth/" + action
    body = json.dumps({"username": username, "password": password}).encode()
    req = urllib.request.Request(url, data=body, method="POST", headers={"Content-Type": "application/json"})
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl): return None
    with urllib.request.build_opener(NoRedirect, urllib.request.ProxyHandler({})).open(req, timeout=10) as response:
        return json.load(response)


def save_sync_password(username, password):
    identifier = hashlib.sha256(username.lower().encode()).hexdigest()
    try:
        result = subprocess.run(["secret-tool", "store", "--label=Remote Dev Terminal Account", "application", "remote-dev-terminal", "sync-user", identifier],
                                input=password, text=True, capture_output=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError("无法保存登录密码到系统密钥环，请安装 secret-tool 并解锁密钥环") from exc
    if result.returncode:
        raise RuntimeError("无法保存登录密码：" + (result.stderr.strip() or "密钥环不可用"))


def load_sync_password(username):
    if not username: return ""
    identifier = hashlib.sha256(username.lower().encode()).hexdigest()
    try:
        result = subprocess.run(["secret-tool", "lookup", "application", "remote-dev-terminal", "sync-user", identifier],
                                capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        return ""
    return result.stdout if result.returncode == 0 else ""


def clear_sync_password(username):
    if not username:
        return
    identifier = hashlib.sha256(username.lower().encode()).hexdigest()
    try:
        subprocess.run(["secret-tool", "clear", "application", "remote-dev-terminal", "sync-user", identifier],
                       capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        pass


def _agent_session_id_recovery_script():
    return r'''import glob, json, os, re, sys
kind, rdt_id, workspace = sys.argv[1:4]
workspace = os.path.realpath(workspace) if workspace else ""
ids = []
if kind == "codex":
    homes = [os.environ.get("CODEX_HOME", ""), os.path.expanduser("~/.codex")]
    homes.extend(glob.glob(os.path.expanduser("~/.codex-*")))
    for home in dict.fromkeys(os.path.realpath(os.path.expanduser(p)) for p in homes if p):
        path = os.path.join(home, "session_index.jsonl")
        try:
            with open(path, encoding="utf-8") as handle:
                for line in handle:
                    try: row = json.loads(line)
                    except (ValueError, TypeError): continue
                    sid = str(row.get("id", "")).replace("-", "").lower()
                    cwd = row.get("cwd") or row.get("project_root") or ""
                    if re.fullmatch(r"[0-9a-f]{32}", sid) and workspace and cwd and os.path.realpath(cwd) == workspace:
                        ids.append((str(row.get("updated_at", "")), sid))
        except OSError: pass
else:
    roots = [os.environ.get("AGY_HOME", ""), os.path.expanduser("~/.gemini/antigravity-cli")]
    roots.extend(glob.glob(os.path.expanduser("~/.gemini/antigravity*")))
    for root in dict.fromkeys(os.path.realpath(os.path.expanduser(p)) for p in roots if p):
        path = os.path.join(root, "conversation_summaries.db")
        try:
            import sqlite3
            db = sqlite3.connect("file:" + path + "?mode=ro", uri=True, timeout=.2)
            cols = {row[1] for row in db.execute("PRAGMA table_info(conversation_summaries)")}
            idcol = next((c for c in ("conversation_id", "id") if c in cols), None)
            wcol = next((c for c in ("workspace_uris", "workspace_uri", "workspace") if c in cols), None)
            tcol = next((c for c in ("last_modified_time", "updated_at", "modified_at") if c in cols), None)
            if idcol and wcol:
                query = "SELECT " + idcol + "," + wcol + ("," + tcol if tcol else "") + " FROM conversation_summaries"
                for row in db.execute(query):
                    raw = row[1]
                    try: raw = json.loads(raw)
                    except (ValueError, TypeError): pass
                    vals = raw if isinstance(raw, list) else [raw]
                    paths = [v.get("uri", v.get("path", "")) if isinstance(v, dict) else v for v in vals]
                    if workspace and any(os.path.realpath(str(v).replace("file://", "")) == workspace for v in paths):
                        sid = str(row[0]).replace("-", "").lower()
                        if re.fullmatch(r"[0-9a-f]{32}", sid): ids.append((str(row[2] if tcol else ""), sid))
            db.close()
        except Exception: pass
if ids: print(sorted(ids)[-1][1], end="")'''


def wrap_agent_command(session_id, agent):
    text = (agent or "").strip()
    if not text:
        return agent
    try:
        tokens = shlex.split(text)
    except ValueError:
        return agent
    if not tokens:
        return agent
    cmd_bin = os.path.basename(tokens[0])
    rest = tokens[1:]
    codex_subcommands = {
        "resume", "exec", "e", "login", "logout", "mcp", "plugin", "app-server",
        "remote-control", "completion", "update", "doctor", "sandbox", "debug",
        "apply", "a", "archive", "delete", "unarchive", "fork", "cloud", "agents",
    }
    agy_subcommands = {
        "acp", "agent", "agents", "changelog", "help", "install",
        "mcp", "mic-serve", "models", "plugin", "plugins",
    }
    if cmd_bin == "codex" and (not rest or rest[0] not in codex_subcommands):
        resume_cmd = " ".join([shlex.quote(tokens[0]), "resume", '"$_rdt_id"', *[shlex.quote(t) for t in rest]])
        search_roots = '"${CODEX_HOME:-$HOME/.codex}/sessions"'
        file_pattern = "'rollout-*.jsonl'"
        strip_suffix = ".jsonl"
        title_kind = "codex"
    elif cmd_bin in ("agy", "antigravity") and (not rest or (rest[0] not in agy_subcommands and not any(t in ("-c", "--continue", "--conversation") or t.startswith("--conversation=") for t in rest))):
        resume_cmd = " ".join([shlex.quote(tokens[0]), "--conversation", '"$_rdt_id"', *[shlex.quote(t) for t in rest]])
        search_roots = '"${AGY_HOME:-$HOME/.gemini/antigravity-cli}/conversations" "$HOME/.agy-homes"'
        file_pattern = "'*.db'"
        strip_suffix = ".db"
        title_kind = "agy"
    else:
        return agent
    sid = str(session_id).replace("-", "").lower()
    recovery_script = base64.b64encode(_agent_session_id_recovery_script().encode("utf-8")).decode("ascii")
    recovery_command = "import base64;exec(compile(base64.b64decode('" + recovery_script + "'),'<rdt-id-recovery>','exec'))"
    recovery_argv = "python3 -c " + shlex.quote(recovery_command) + " " + shlex.quote(title_kind) + " " + shlex.quote(sid) + " \"$PWD\""
    return (
        f'_rdt_dir="$HOME/.remote-dev-terminal/agent-sessions"; '
        f'_rdt_f="$_rdt_dir/{sid}"; '
        f'mkdir -p "$_rdt_dir"; '
        f'if [ -s "$_rdt_f" ]; then '
        f'_rdt_id=$(cat "$_rdt_f"); '
        f'{resume_cmd}; '
        f'else '
        f'_rdt_mark="$_rdt_dir/.mark_{sid}"; : > "$_rdt_mark"; '
        f'_rdt_id=$({recovery_argv} 2>/dev/null); '
        f'if [ -n "$_rdt_id" ]; then printf "%s" "$_rdt_id" > "$_rdt_f"; '
        f'{resume_cmd}; '
        f'else '
        f'(_i=0; while [ $_i -lt 120 ]; do sleep 1; '
        f'_rdt_n=$(find {search_roots} -maxdepth 4 -type f -name {file_pattern} -newer "$_rdt_mark" 2>/dev/null | head -n 1); '
        f'if [ -n "$_rdt_n" ]; then '
        f'_rdt_id=$(basename "$_rdt_n" {strip_suffix} | grep -oE "[0-9a-f]{{8}}-[0-9a-f]{{4}}-[0-9a-f]{{4}}-[0-9a-f]{{4}}-[0-9a-f]{{12}}" | tail -n 1); '
        f'if [ -n "$_rdt_id" ]; then printf "%s" "$_rdt_id" > "$_rdt_f"; break; fi; '
        f'fi; _i=$((_i + 1)); done; rm -f "$_rdt_mark") >/dev/null 2>&1 & '
        f'{text}; fi; fi'
    )


def agent_session_title_command(session_id, kind, workspace=""):
    if kind not in ("codex", "agy"):
        raise ValueError("unsupported agent title source")
    sid = re.sub(r"[^0-9a-f]", "", str(session_id).replace("-", "").lower())
    if not sid:
        raise ValueError("invalid session ID")
    script = r'''import glob, os, sqlite3, sys
import json, re, subprocess
from urllib.parse import quote, unquote, urlparse
kind, rdt_session_id, requested_workspace = sys.argv[1:4]
session_file = os.path.join(os.path.expanduser("~"), ".remote-dev-terminal", "agent-sessions", rdt_session_id)
try:
    wanted = open(session_file, encoding="utf-8").read().strip().replace("-", "").lower()
except OSError:
    wanted = ""
def clean(value):
    return " ".join(str(value or "").replace("\x00", " ").split())[:512]
def normalized_path(value):
    return os.path.normcase(os.path.realpath(os.path.expanduser(str(value or "")))) if value else ""
def sort_timestamp(value):
    try:
        return (1, float(value))
    except (TypeError, ValueError):
        return (0, str(value or ""))
def codex_homes():
    roots = [os.environ.get("CODEX_HOME", ""), os.path.expanduser("~/.codex")]
    roots.extend(glob.glob(os.path.expanduser("~/.codex-*")))
    unique = []
    for root in roots:
        if root:
            root = os.path.realpath(os.path.expanduser(root))
            if root not in unique:
                unique.append(root)
    return unique
def codex_db_paths():
    roots = codex_homes() + [os.environ.get("CODEX_SQLITE_HOME", "")]
    paths = []
    for root in roots:
        if root:
            paths.extend(glob.glob(os.path.join(root, "state_*.sqlite")))
            paths.extend(glob.glob(os.path.join(root, "sqlite", "state_*.sqlite")))
            paths.append(os.path.join(root, "state.sqlite"))
    return sorted(set(paths), key=lambda path: os.path.getmtime(path) if os.path.isfile(path) else 0, reverse=True)
def codex_title(thread_id):
    wanted_id = str(thread_id).replace("-", "").lower()
    for home in codex_homes():
        path = os.path.join(home, "session_index.jsonl")
        try:
            with open(path, "rb") as handle:
                handle.seek(0, os.SEEK_END)
                position, pending = handle.tell(), b""
                while position:
                    size = min(position, 8192)
                    position -= size
                    handle.seek(position)
                    pending = handle.read(size) + pending
                    lines = pending.split(b"\n")
                    pending = lines.pop(0)
                    for line in reversed(lines):
                        try:
                            entry = json.loads(line)
                        except (UnicodeDecodeError, json.JSONDecodeError):
                            continue
                        if isinstance(entry, dict) and str(entry.get("id", "")).replace("-", "").lower() == wanted_id:
                            title = clean(entry.get("thread_name"))
                            if title:
                                return title
                if pending:
                    try:
                        entry = json.loads(pending)
                    except (UnicodeDecodeError, json.JSONDecodeError):
                        entry = {}
                    if isinstance(entry, dict) and str(entry.get("id", "")).replace("-", "").lower() == wanted_id:
                        title = clean(entry.get("thread_name"))
                        if title:
                            return title
        except OSError:
            pass
    return ""
def active_legacy_agent():
    target = "rdt_" + rdt_session_id[:18]
    try:
        command = subprocess.run(["tmux", "display-message", "-p", "-t", target, "#{pane_current_command}"],
                                 capture_output=True, text=True, timeout=2, check=False).stdout.strip().lower()
        pane_path = subprocess.run(["tmux", "display-message", "-p", "-t", target, "#{pane_current_path}"],
                                   capture_output=True, text=True, timeout=2, check=False).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return False
    base = os.path.basename(command)
    if kind == "codex":
        expected = base in ("codex", "codex2", "codx") or base.startswith("codex-")
    else:
        expected = base in ("agy", "agy2", "antigravity")
    return expected and (not requested_workspace or not pane_path or normalized_path(pane_path) == normalized_path(requested_workspace))
def codex_workspace_candidates():
    workspace = normalized_path(requested_workspace)
    if not workspace:
        return []
    candidates = []
    for path in codex_db_paths():
        if not os.path.isfile(path):
            continue
        try:
            uri = "file:" + quote(os.path.abspath(path), safe="/") + "?mode=ro"
            db = sqlite3.connect(uri, uri=True, timeout=0.2)
            db.execute("PRAGMA busy_timeout=200")
            columns = {row[1] for row in db.execute("PRAGMA table_info(threads)")}
            id_col = next((name for name in ("id", "thread_id") if name in columns), None)
            cwd_col = "cwd" if "cwd" in columns else None
            updated_col = next((name for name in ("updated_at_ms", "updated_at", "recency_at_ms", "created_at_ms", "created_at") if name in columns), None)
            if id_col and cwd_col:
                query = "SELECT " + id_col + ", " + cwd_col + (", " + updated_col if updated_col else "") + " FROM threads"
                for row in db.execute(query):
                    if normalized_path(row[1]) == workspace and row[0]:
                        updated = row[2] if updated_col else 0
                        candidates.append((sort_timestamp(updated), str(row[0]).replace("-", "").lower()))
            db.close()
        except Exception:
            pass
    return [thread_id for _updated, thread_id in sorted(candidates, key=lambda row: row[0], reverse=True)]
def agy_db_paths():
    roots = [os.environ.get("AGY_HOME", ""), os.path.expanduser("~/.gemini/antigravity-cli"),
             os.path.expanduser("~/.gemini/antigravity"), os.path.expanduser("~/.gemini/antigravity-ide")]
    roots.extend(glob.glob(os.path.expanduser("~/.gemini/antigravity*")))
    paths = []
    for root in roots:
        if root:
            path = os.path.join(os.path.realpath(os.path.expanduser(root)), "conversation_summaries.db")
            if path not in paths:
                paths.append(path)
    return paths
def agy_workspace_matches(value):
    wanted_path = normalized_path(requested_workspace)
    if not wanted_path:
        return False
    try:
        parsed_value = json.loads(value) if isinstance(value, str) else value
    except (TypeError, json.JSONDecodeError):
        parsed_value = value
    values = parsed_value if isinstance(parsed_value, list) else [parsed_value]
    for item in values:
        if isinstance(item, dict):
            item = item.get("uri") or item.get("path") or item.get("workspace_uri") or ""
        if not isinstance(item, str):
            continue
        parsed = urlparse(item)
        path = unquote(parsed.path) if parsed.scheme == "file" else unquote(item)
        if normalized_path(path) == wanted_path:
            return True
    return False
def agy_workspace_candidates():
    candidates = []
    for path in agy_db_paths():
        if not os.path.isfile(path):
            continue
        try:
            uri = "file:" + quote(os.path.abspath(path), safe="/") + "?mode=ro"
            db = sqlite3.connect(uri, uri=True, timeout=0.2)
            db.execute("PRAGMA busy_timeout=200")
            columns = {row[1] for row in db.execute("PRAGMA table_info(conversation_summaries)")}
            id_col = next((name for name in ("conversation_id", "id") if name in columns), None)
            workspace_col = next((name for name in ("workspace_uris", "workspace_uri", "workspace") if name in columns), None)
            updated_col = next((name for name in ("last_modified_time", "updated_at", "modified_at") if name in columns), None)
            if id_col and workspace_col:
                select = [id_col, workspace_col] + ([updated_col] if updated_col else [])
                for row in db.execute("SELECT " + ", ".join(select) + " FROM conversation_summaries"):
                    if agy_workspace_matches(row[1]) and row[0]:
                        updated = row[2] if updated_col else 0
                        candidates.append((sort_timestamp(updated), str(row[0]).replace("-", "").lower()))
            db.close()
        except Exception:
            pass
    return [conversation_id for _updated, conversation_id in sorted(candidates, key=lambda row: row[0], reverse=True)]
def recover_legacy_session_id():
    pending_capture = os.path.join(os.path.dirname(session_file), ".mark_" + rdt_session_id)
    if os.path.exists(pending_capture):
        return ""
    if not active_legacy_agent():
        return ""
    candidates = codex_workspace_candidates() if kind == "codex" else agy_workspace_candidates()
    for candidate in candidates:
        if re.fullmatch(r"[0-9a-f]{32,36}", candidate):
            return candidate
    return ""
if not wanted:
    wanted = recover_legacy_session_id()
    if wanted:
        try:
            os.makedirs(os.path.dirname(session_file), mode=0o700, exist_ok=True)
            temp_file = session_file + ".tmp"
            with open(temp_file, "w", encoding="utf-8") as handle:
                handle.write(wanted)
            os.replace(temp_file, session_file)
        except OSError:
            pass
if not wanted:
    print("RDT_ERROR:session-id-not-found", end="")
    raise SystemExit(0)
if kind == "codex":
    title = codex_title(wanted)
    if title:
        print("RDT_TITLE:" + title, end="")
    else:
        print("RDT_ERROR:codex-title-not-found", end="")
else:
    found_title = False
    for path in agy_db_paths():
        if not os.path.isfile(path):
            continue
        try:
            uri = "file:" + quote(os.path.abspath(path), safe="/") + "?mode=ro"
            db = sqlite3.connect(uri, uri=True, timeout=0.2)
            db.execute("PRAGMA busy_timeout=200")
            columns = {row[1] for row in db.execute("PRAGMA table_info(conversation_summaries)")}
            id_col = next((name for name in ("conversation_id", "id") if name in columns), None)
            title_col = next((name for name in ("title", "conversation_title") if name in columns), None)
            if id_col and title_col:
                query = "SELECT " + title_col + " FROM conversation_summaries WHERE lower(replace(" + id_col + ", '-', '')) = lower(replace(?, '-', '')) LIMIT 1"
                row = db.execute(query, (wanted,)).fetchone()
                db.close()
                title = clean(row[0]) if row else ""
                if title:
                    print("RDT_TITLE:" + title, end="")
                    found_title = True
                    break
            else:
                db.close()
        except Exception:
            pass
    if not found_title:
        print("RDT_ERROR:agy-title-not-found", end="")'''
    encoded = base64.b64encode(script.encode("utf-8")).decode("ascii")
    arguments = base64.b64encode(json.dumps([kind, sid, str(workspace or "")], ensure_ascii=False).encode("utf-8")).decode("ascii")
    python = (f'import base64,json,sys;sys.argv[1:]=json.loads(base64.b64decode("{arguments}"));'
              f'exec(compile(base64.b64decode("{encoded}"),"<rdt-title-query>","exec"))')
    return "python3 -c " + shlex.quote(python)


def ssh_argv(host, directory, agent, session_id=None, control_path=None, restore_only=False):
    session_id = session_id or uuid.uuid4().hex
    if not session_id or any(c not in "0123456789abcdef" for c in session_id):
        raise ValueError("无效的会话 ID")
    name = "rdt_" + session_id[:18]
    directory = shlex.quote(directory)
    command = shlex.quote(wrap_agent_command(session_id, agent))
    # tmux receives one shell command; the user's configured command runs in a login shell.
    inner = f'"${{SHELL:-/bin/bash}}" -l -i -c {command}; code=$?; printf "\\n[命令已退出，状态 %s；Shell 仍然可用]\\n" "$code"; exec "${{SHELL:-/bin/bash}}" -l -i'
    remote = (f"cd -- {directory} && if command -v tmux >/dev/null 2>&1; then "
              f"(tmux has-session -t {name} 2>/dev/null || tmux new-session -d -s {name} -c {directory} {shlex.quote(inner)}) && "
              f"tmux set-option -t {name} mouse on && tmux attach-session -t {name}; "
              f"else printf '\\n[提示] 未安装 tmux，断线后无法恢复会话\\n'; exec \"${{SHELL:-/bin/bash}}\" -l -i -c {command}; fi")
    if restore_only:
        remote = (f"cd -- {directory} && if command -v tmux >/dev/null 2>&1 && tmux has-session -t {name} 2>/dev/null; then "
                  f"tmux set-option -t {name} mouse on && tmux attach-session -t {name}; else printf '\\n[会话已不存在，无法恢复]\\n'; exit 1; fi")
    options = ["-o", "ControlMaster=auto", "-o", "ControlPersist=60", "-o", "ControlPath=" + control_path] if control_path else []
    address = ssh_address(host)
    port = ssh_port(host)
    if not address or not port:
        raise ValueError("请先在主机设置中填写有效的 SSH 地址和端口")
    return ["ssh", "-tt", "-o", "ServerAliveInterval=20", "-o", "ServerAliveCountMax=6", *options, "-p", str(port), "-l", host["username"], "--", address, remote], session_id


def close_remote_session_command(session_id):
    if not session_id or any(c not in "0123456789abcdef" for c in session_id):
        raise ValueError("无效的会话 ID")
    name = "rdt_" + session_id[:18]
    return (
        f"if command -v tmux >/dev/null 2>&1 && tmux has-session -t {name} 2>/dev/null; then "
        f"for p in $(tmux list-panes -t {name} -F '#{{pane_pid}}' 2>/dev/null); do "
        f"pkill -TERM -P \"$p\" 2>/dev/null || true; kill -TERM \"$p\" 2>/dev/null || true; "
        f"done; "
        f"tmux kill-session -t {name} 2>/dev/null || true; "
        f"fi"
    )


def should_close_missing_shared_session(session, remote_ids, now=None):
    """Only auto-close a shared session after it is absent and locally disconnected."""
    if session.get("id") in remote_ids:
        return False
    current_time = time.monotonic() if now is None else now
    if current_time - session.get("started_at", 0) <= 15:
        return False
    state = session.get("state")
    if isinstance(state, dict) and (state.get("connected") or state.get("retry_source")):
        return False
    reconnect_button = session.get("reconnect_button")
    if reconnect_button is not None:
        try:
            if reconnect_button.get_visible():
                return False
        except (AttributeError, RuntimeError):
            pass
    return True


def _normalize_signature(sig):
    if not sig:
        return ""
    # 去除动态时钟 (HH:MM:SS 或 HH:MM)
    sig = re.sub(r"\b\d{1,2}:\d{2}(?::\d{2})?\b", "", sig)
    # 去除耗时指示 (例如 (2.5s) 或 (12s))
    sig = re.sub(r"\(\d+(?:\.\d+)?s\)", "", sig)
    # 去除旋转动效字符
    sig = re.sub(r"[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]", "", sig)
    return sig.strip()


def auto_enter_signature(text):
    """Identify a currently visible, explicitly affirmative Enter action."""
    if not text:
        return None
    lines = []
    for raw in text.splitlines():
        line = raw.strip().strip("│┃║| ").strip()
        if not line or re.match(r"^\[(?:rdt_[0-9a-fA-F]+|\d+)\]\s+\d+:\S+", line):
            continue
        lines.append(line)
    if not lines:
        return None

    return _classic_auto_enter_signature(lines) or _wrapped_approval_signature(lines)


def _classic_auto_enter_signature(lines):
    # Only a capital Y indicates that Enter selects yes. A lowercase y/N or
    # ambiguous y/n must not be silently answered with Enter.
    enter_prompt = re.compile(r"(?i)(?:press\s+(?:enter|return)\s+to\s+(?:continue|confirm|approve)|按\s*(?:回车|enter)\s*(?:继续|确认|批准|同意))[:\s?.!]*$")
    yes_default = re.compile(r"(?:\[Y/(?:n|no)\]|\(Y/(?:n|no)\)|\[Yes/no\]|\(Yes/no\))[:\s?.]*$")
    option = re.compile(r"^\s*(?:[›❯>➜→*●◉]|\[[xX*]\]|\([xX*]\))\s*(?:\d+[.)]\s*)?(.*)$")
    explicit_cursor = re.compile(r"^\s*(?:[›❯●◉]|\[[xX*]\]|\([xX*]\))")
    affirmative = re.compile(r"(?i)^(?:yes\b|allow\b|approve\b|proceed\b|continue\b|always\b|允许|批准|同意|继续|确认|总是)")
    request = re.compile(r"(?i)(?:approv(?:e|al)|allow|would you like|do you want|proceed|continue|permission|confirm|execute|apply|install|run|resume|recover|restore|授权|批准|是否允许|需要确认|是否继续|确认执行|\?|？)")
    # Recent selected option wins, including a selected rejection.
    for index in range(len(lines) - 1, max(-1, len(lines) - 45), -1):
        match = option.match(lines[index])
        if not match:
            continue
        if not affirmative.match(match.group(1)):
            return None
        for question_index in range(index - 1, max(-1, index - 150), -1):
            question = lines[question_index]
            if request.search(question):
                start = max(0, question_index - 4)
                return _normalize_signature("\n".join(lines[start:index + 1]))
        if explicit_cursor.match(lines[index]):
            start = max(0, index - 4)
            return _normalize_signature("\n".join(lines[start:index + 1]))
        return None
    for index in range(len(lines) - 1, max(-1, len(lines) - 20), -1):
        if yes_default.search(lines[index]) or enter_prompt.search(lines[index]):
            start = max(0, index - 4)
            return _normalize_signature("\n".join(lines[start:index + 1]))

    return None


_WRAPPED_APPROVAL_FOOTER = re.compile(
    r"press\s+(?:enter|return)\s+to\s+confirm\s+or\s+(?:esc|escape)\s+to\s+cancel[.!]?", re.I,
)
_APPROVAL_CURSOR = re.compile(r"^(?:[›❯➜●◉]|>\s*\d+[.)])")
_APPROVAL_CHOICE = re.compile(r"^(?:[›❯>➜●◉]\s*)?\d+[.)]\s*(.*)$")
_APPROVE_ONCE = re.compile(
    r"(?:yes,\s*(?:proceed|just this once|grant these permissions for this turn|"
    r"grant for this turn with strict auto review)|allow once)\s*(?:\([a-z]\))?", re.I,
)


def _wrapped_approval_signature(lines):
    """Recover a wrapped approval choice when the classic prompt detector found none."""
    lines = [line for line in lines if not all(char in "─━═╭╮╰╯┌┐└┘+ " for char in line)]
    footer = next((i for i in range(len(lines) - 1, max(-1, len(lines) - 5), -1)
                   if _WRAPPED_APPROVAL_FOOTER.fullmatch(" ".join(lines[i:]))), None)
    if footer is None:
        return None
    selected = next((i for i in range(footer - 1, -1, -1) if _APPROVAL_CURSOR.match(lines[i])), None)
    if selected is None:
        return None
    choice = _APPROVAL_CHOICE.fullmatch(lines[selected])
    if choice is None:
        return None
    parts = [choice.group(1)]
    for line in lines[selected + 1:min(footer, selected + 6)]:
        if _APPROVAL_CHOICE.match(line):
            break
        parts.append(line)
    label = " ".join(" ".join(parts).split())
    if len(parts) == 1 or not _APPROVE_ONCE.fullmatch(label):
        return None
    return _normalize_signature("\n".join(lines[max(0, selected - 2):selected] + [label]))


def confirmation_pending(text):
    """Recognize a visible confirmation even when its current choice is negative."""
    lines = []
    for raw in text.splitlines()[-45:]:
        line = raw.strip().strip("│┃║| ")
        if line and not all(char in "─━═╭╮╰╯┌┐└┘+ " for char in line) and not re.match(
                r"^\[(?:rdt_[0-9a-fA-F]+|\d+)\]\s+\d+:\S+", line):
            lines.append(line)
    if not lines:
        return False
    if re.search(r"[\[(](?:y|yes)/(?:n|no)[\])]\s*[:?？.!]*$", lines[-1], re.I):
        return True
    for index in range(max(0, len(lines) - 4), len(lines)):
        footer = " ".join(lines[index:])
        if _WRAPPED_APPROVAL_FOOTER.fullmatch(footer) or re.fullmatch(
                r"press\s+(?:enter|return)\s+to\s+(?:confirm|approve)[.!]?", footer, re.I):
            return True
    selected = next((index for index in range(len(lines) - 1, -1, -1)
                     if _APPROVAL_CURSOR.match(lines[index]) and _APPROVAL_CHOICE.fullmatch(lines[index])), None)
    return selected is not None and any(
        re.search(r"would you like to (?:run|make|grant)|do you want to (?:approve|proceed)|needs your approval|需要确认|是否允许",
                  line, re.I)
        for line in lines[:selected]
    )


class AutoEnterController:
    """Track one visible prompt per terminal and send at most one Enter."""
    def __init__(self):
        self.reset()

    def reset(self):
        self.signature = None
        self.detected_at = 0.0
        self.handled = False

    def cancel(self, signature=None):
        # Manual input cancels the currently displayed prompt until it changes.
        self.signature = signature or self.signature
        self.handled = True

    def observe(self, signature, now, delay, enabled=True):
        if not enabled or not signature:
            self.reset()
            return False
        if signature != self.signature:
            self.signature = signature
            self.detected_at = now
            self.handled = False
            return False
        if not self.handled and now - self.detected_at >= max(0.05, delay):
            self.handled = True
            return True
        return False


def quota_agent(command):
    """Identify supported quota providers and retain the configured executable."""
    try:
        words = shlex.split(command)
    except ValueError:
        return None, None
    if not words:
        return None, None
    executable = words[0]
    name = os.path.basename(executable)
    if name in ("codex", "codex2", "codx"):
        return "codex", executable
    if name in ("agy", "agy2"):
        return "agy", executable
    return None, None


def quota_remote_command(directory, executable, arguments):
    command = f"{shlex.quote(executable)} {arguments}"
    cd = f"cd -- {shlex.quote(directory)} && " if directory else ""
    return cd + '"${SHELL:-/bin/bash}" -l -i -c ' + shlex.quote(command)


def quota_from_codex(message):
    limits = message.get("result", {}).get("rateLimits", {})
    now = time.time()
    result = []
    for name in ("primary", "secondary"):
        w = limits.get(name)
        if isinstance(w, dict) and w.get("windowDurationMins") and w.get("usedPercent") is not None:
            mins = int(w["windowDurationMins"])
            percent = max(0, min(100, 100 - int(w["usedPercent"])))
            resets_at = w.get("resetsAt")
            days_str = None
            if resets_at is not None:
                try:
                    delta = float(resets_at) - now
                    if delta >= 86400:
                        days_str = f"{int(delta // 86400)}d"
                    elif delta > 0:
                        days_str = "<1d"
                except (ValueError, TypeError):
                    pass
            result.append((mins, percent, days_str))
    return result


def quota_from_agy(message):
    result = {}
    for group in message.get("command", {}).get("data", {}).get("groups", []):
        is_gemini_models = (group.get("name") or "").strip().casefold() == "gemini models"
        for bucket in group.get("buckets", []):
            name = bucket.get("id", "").lower()
            if "gemini" not in name:
                continue
            minutes = 10080 if "weekly" in name or "7d" in name else 300 if "5h" in name or "session" in name else None
            if minutes == 10080 and not is_gemini_models:
                continue
            value = bucket.get("remaining_fraction")
            if minutes and value is not None:
                percent = max(0, min(100, int(float(value) * 100)))
                reset_time_str = bucket.get("reset_time")
                days_str = None
                if reset_time_str:
                    try:
                        clean_ts = reset_time_str.replace("Z", "+00:00")
                        dt = datetime.fromisoformat(clean_ts)
                        delta = (dt - datetime.now(timezone.utc)).total_seconds()
                        if delta >= 86400:
                            days_str = f"{int(delta // 86400)}d"
                        elif delta > 0:
                            days_str = "<1d"
                    except Exception:
                        pass
                result[minutes] = (percent, days_str)
    return [(mins, p, d) for mins, (p, d) in sorted(result.items())]



def read_agent_quota(host, command, directory=None, control_path=None):
    """Read the selected remote account's limits over SSH without using its terminal input."""
    kind, executable = quota_agent(command)
    address, port = ssh_address(host), ssh_port(host)
    if not kind or not address or not port:
        return []
    batch = "no" if host.get("credential_id") else "yes"
    control = ["-S", control_path] if control_path else []
    base = ["ssh", *control, "-o", f"BatchMode={batch}", "-o", "StrictHostKeyChecking=yes",
            "-o", "NumberOfPasswordPrompts=1", "-o", "ConnectTimeout=5",
            "-p", str(port), "-l", host["username"], "--", address]
    env = ssh_auth_environment(host, Path(__file__).with_name("askpass.py"))
    env["RDT_SSH_NONINTERACTIVE"] = "1"
    if kind == "agy":
        remote = quota_remote_command(directory, executable, '-p "/usage" --output-format json')
        output = subprocess.check_output(base + [remote], stdin=subprocess.DEVNULL,
                                         stderr=subprocess.DEVNULL, env=env, timeout=30)
        return quota_from_agy(json.loads(output[output.index(b"{"):]))

    remote = quota_remote_command(directory, executable, "app-server --listen stdio://")
    with subprocess.Popen(base + [remote], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL, env=env, bufsize=0) as proc:
        try:
            proc.stdin.write(b'{"id":1,"method":"initialize","params":{"clientInfo":{"name":"remote_dev_terminal","version":"1.0"}}}\n')
            deadline = time.monotonic() + 15
            pending = b""
            while time.monotonic() < deadline and len(pending) < 1_000_000:
                ready, _, _ = select.select([proc.stdout], [], [], max(0, deadline - time.monotonic()))
                if not ready:
                    break
                chunk = os.read(proc.stdout.fileno(), 4096)
                if not chunk:
                    break
                pending += chunk
                while b"\n" in pending:
                    line, pending = pending.split(b"\n", 1)
                    try:
                        message = json.loads(line)
                    except ValueError:
                        continue
                    if not isinstance(message, dict):
                        continue
                    if message.get("id") == 1:
                        if "error" in message:
                            return []
                        proc.stdin.write(b'{"method":"initialized","params":{}}\n'
                                         b'{"id":2,"method":"account/rateLimits/read","params":{}}\n')
                    elif message.get("id") == 2:
                        return quota_from_codex(message)
        finally:
            proc.kill()
    return []


def quota_key(host, command):
    executable = quota_agent(command)[1]
    if executable is None:
        return None
    endpoints = tuple(host.get(field) for field in
                      ("address", "port", "lan_address", "lan_port", "external_address", "external_port"))
    return (host.get("id"), host.get("username"), endpoints, executable)


def hourly_quota(quota):
    return min((window for window in quota if 0 < window[0] < 1440), default=(300, None))


def format_quota(quota):
    """Show both requested windows with reset days for weekly quota if available."""
    values = {}
    days_map = {}
    for item in quota:
        if isinstance(item, (list, tuple)) and len(item) >= 2:
            mins, percent = item[0], item[1]
            values[mins] = percent
            if len(item) >= 3 and item[2] is not None:
                days_map[mins] = item[2]

    def value(minutes):
        return f"{values[minutes]}%" if minutes in values else "--"

    weekly = value(10080)
    reset_days = days_map.get(10080)
    if weekly != "--" and reset_days:
        weekly_part = f"{weekly} {reset_days}"
    else:
        weekly_part = weekly

    return f"{value(300)} · {weekly_part}"


def render_battery_pixbuf(pct, width=34, height=13):
    """Render a mobile-style battery icon pixbuf with inverted text over fill."""
    import cairo
    import gi
    gi.require_version("GdkPixbuf", "2.0")
    from gi.repository import GdkPixbuf

    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, width, height)
    cr = cairo.Context(surface)
    cr.set_operator(cairo.OPERATOR_CLEAR)
    cr.paint()
    cr.set_operator(cairo.OPERATOR_OVER)

    body_x = 0.5
    body_y = 0.5
    body_w = width - 4.5
    body_h = height - 1.0
    rad = 2.0

    def round_rect(x, y, rw, rh, r):
        cr.new_sub_path()
        cr.arc(x + rw - r, y + r, r, -1.5707963, 0)
        cr.arc(x + rw - r, y + rh - r, r, 0, 1.5707963)
        cr.arc(x + r, y + rh - r, r, 1.5707963, 3.1415926)
        cr.arc(x + r, y + r, r, 3.1415926, 4.7123889)
        cr.close_path()

    if pct is None:
        border_color = (0.35, 0.45, 0.55, 0.8)
        fill_color = None
    elif pct < 5:
        border_color = (1.0, 0.30, 0.30, 0.9)
        fill_color = (1.0, 0.30, 0.30)  # <5% Red
    elif pct < 20:
        border_color = (1.0, 0.60, 0.05, 0.9)
        fill_color = (1.0, 0.60, 0.05)  # <20% Orange
    else:
        border_color = (0.525, 0.859, 0.722, 0.9)
        fill_color = (0.525, 0.859, 0.722)  # >=20% Green/Teal

    # Battery outline
    cr.set_source_rgba(*border_color)
    cr.set_line_width(1.0)
    round_rect(body_x, body_y, body_w, body_h, rad)
    cr.stroke()

    # Battery cap
    cap_x = body_w + 1.0
    cap_y = body_y + body_h * 0.3
    cap_w = 2.0
    cap_h = body_h * 0.4
    round_rect(cap_x, cap_y, cap_w, cap_h, 0.8)
    cr.fill()

    # Inner cavity
    inner_x = body_x + 1.5
    inner_y = body_y + 1.5
    inner_w = body_w - 3.0
    inner_h = body_h - 3.0

    cr.set_source_rgba(0.08, 0.12, 0.17, 0.85)
    round_rect(inner_x, inner_y, inner_w, inner_h, 1.2)
    cr.fill()

    fill_w = 0.0
    if fill_color is not None and pct > 0:
        fill_w = max(1.5, min(inner_w, inner_w * (pct / 100.0)))
        cr.set_source_rgb(*fill_color)
        round_rect(inner_x, inner_y, fill_w, inner_h, 1.2)
        cr.fill()

    text = "--" if pct is None else str(round(pct))
    cr.select_font_face("Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    font_size = 8.5 if len(text) <= 2 else 7.0
    cr.set_font_size(font_size)
    extents = cr.text_extents(text)
    tx = inner_x + (inner_w - extents.width) / 2.0 - extents.x_bearing
    ty = inner_y + (inner_h - extents.height) / 2.0 - extents.y_bearing

    text_dark = (0.05, 0.10, 0.12)
    text_light = (0.90, 0.94, 0.98)

    # Inverted color rendering:
    # 1. Clip over filled part (dark text)
    if fill_w > 0:
        cr.save()
        cr.rectangle(inner_x, inner_y, fill_w, inner_h)
        cr.clip()
        cr.set_source_rgb(*text_dark)
        cr.move_to(tx, ty)
        cr.show_text(text)
        cr.restore()

    # 2. Clip over unfilled part (light text)
    if fill_w < inner_w:
        cr.save()
        cr.rectangle(inner_x + fill_w, inner_y, inner_w - fill_w, inner_h)
        cr.clip()
        cr.set_source_rgb(*text_light)
        cr.move_to(tx, ty)
        cr.show_text(text)
        cr.restore()

    data = surface.get_data()
    return GdkPixbuf.Pixbuf.new_from_data(
        bytes(data),
        GdkPixbuf.Colorspace.RGB,
        True,
        8,
        width,
        height,
        surface.get_stride()
    )
