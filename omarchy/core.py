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
    {"name": "Shell", "command": "bash -l"},
]

MAX_IMAGE_BYTES = 20 * 1024 * 1024


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


def config_path():
    return Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "remote-dev-terminal" / "config.json"


def load_config(path=None):
    path = path or config_path()
    if not path.exists():
        return {"hosts": [], "agents": [dict(a) for a in DEFAULT_AGENTS], "sync_url": "", "sync_token": ""}
    with path.open(encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict) or not isinstance(data.get("hosts"), list) or not isinstance(data.get("agents"), list):
        raise ValueError("配置文件格式无效")
    data.setdefault("sync_url", "")
    data.setdefault("sync_token", "")
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


def ssh_argv(host, directory, agent, session_id=None, control_path=None, restore_only=False):
    session_id = session_id or uuid.uuid4().hex
    if not session_id or any(c not in "0123456789abcdef" for c in session_id):
        raise ValueError("无效的会话 ID")
    name = "rdt_" + session_id[:18]
    directory = shlex.quote(directory)
    command = shlex.quote(agent)
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



_CODEX_FOOTER = re.compile(r"^press\s+(?:enter|return)\s+to\s+confirm\s+or\s+(?:esc|escape)\s+to\s+cancel\b", re.I)
_CODEX_TITLE = re.compile(
    r"^(?:would you like to (?:run|send input|grant|make)|"
    r"do you want to (?:approve network access|make)|.+ needs your approval\.)", re.I
)
_EXPLICIT_CURSOR = re.compile(r"^(?:[›❯●◉]|> |\[[xX*]\]|\([xX*]\))")
_NUMBERED_OPTION = re.compile(r"^(?:[›❯>]\s*)?\d+[.)]\s*")
_CODEX_ONCE = re.compile(
    r"yes(?:,\s*(?:proceed|just this once|grant these permissions for this turn|"
    r"grant for this turn with strict auto review))?\s*(?:\([^)]*\)|\[[^]]*\])?", re.I
)
_CODEX_OTHER_CHOICE = re.compile(
    r"don['’]t ask again|(?:for|in) this session|for this conversation|in the future|provide the requested info|^(?:always|no|deny|cancel)\b",
    re.I,
)


def _codex_approval_signature(lines, option):
    footer = next(
        (i for i in range(len(lines) - 1, max(-1, len(lines) - 7), -1)
         if _CODEX_FOOTER.match(" ".join(lines[i:i + 3]))),
        None,
    )
    end = len(lines) if footer is None else footer
    selected = next((i for i in range(end - 1, max(-1, end - 46), -1)
                     if _EXPLICIT_CURSOR.match(lines[i])), None)
    title = None if selected is None else next(
        (i for i in range(selected - 1, -1, -1) if _CODEX_TITLE.match(lines[i])), None,
    )
    # A recognized Codex menu owns the decision, including a rejected selection.
    # Other terminal prompts continue through the ordinary detector.
    if footer is None and title is None:
        return False, None
    if selected is None:
        return True, None
    parts = [option.match(lines[selected]).group(1)]
    for line in lines[selected + 1:min(end, selected + 4)]:
        if _NUMBERED_OPTION.match(line):
            break
        parts.append(line)
    label = " ".join(" ".join(parts).split())
    approve_once = _CODEX_ONCE.fullmatch(label)
    if footer is None and not approve_once and not _CODEX_OTHER_CHOICE.search(label):
        return False, None
    if not approve_once or (title is None and "," not in label):
        return True, None

    start = max(title if title is not None else 0, selected - 2)
    heading = [lines[title]] if title is not None and title < start else []
    return True, "\n".join(heading + lines[start:selected] + [label])


def auto_enter_signature(text, codex=False):
    """Identify a currently visible, explicitly affirmative Enter action."""
    if not text:
        return None
    lines = []
    for raw in text.splitlines():
        line = raw.strip().strip("│┃║| ").strip()
        if not line or all(char in "─━═╭╮╰╯┌┐└┘+ " for char in line) or re.match(r"^\[(?:rdt_[0-9a-fA-F]+|\d+)\]\s+\d+:\S+", line):
            continue
        lines.append(line)
    if not lines:
        return None

    # Only a capital Y indicates that Enter selects yes. A lowercase y/N or
    # ambiguous y/n must not be silently answered with Enter.
    enter_prompt = re.compile(r"(?i)(?:press\s+(?:enter|return)\s+to\s+(?:continue|confirm|approve)|按\s*(?:回车|enter)\s*(?:继续|确认|批准|同意))[:\s?.!]*$")
    yes_default = re.compile(r"(?:\[Y/(?:n|no)\]|\(Y/(?:n|no)\)|\[Yes/no\]|\(Yes/no\)|\[Y\]/n)[:\s?？.>›❯!]*$")
    yes_no = re.compile(yes_default.pattern, re.I)
    option = re.compile(r"^\s*(?:[›❯>➜→*●◉]|\[[xX*]\]|\([xX*]\))\s*(?:\d+[.)]\s*)?(.*)$")
    explicit_cursor = re.compile(r"^\s*(?:[›❯●◉]|\[[xX*]\]|\([xX*]\))")
    affirmative = re.compile(r"(?i)^(?:yes\b|allow\b|approve\b|proceed\b|continue\b|always\b|允许|批准|同意|继续|确认|总是)")
    request = re.compile(r"(?i)(?:approv(?:e|al)|allow|would you like|do you want|proceed|continue|permission|confirm|execute|apply|install|run|resume|recover|restore|授权|批准|是否允许|需要确认|是否继续|确认执行|\?|？)")
    if yes_no.search(lines[-1]):
        return _normalize_signature("\n".join(lines[-5:])) if yes_default.search(lines[-1]) else None
    if codex:
        recognized, signature = _codex_approval_signature(lines, option)
        if recognized:
            return signature

    # Recent selected option wins, including a selected rejection.
    for index in range(len(lines) - 1, max(-1, len(lines) - 45), -1):
        if index >= len(lines) - 20 and yes_no.search(lines[index]):
            return _normalize_signature("\n".join(lines[max(0, index - 4):index + 1])) if yes_default.search(lines[index]) else None
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
        if enter_prompt.search(lines[index]):
            start = max(0, index - 4)
            return _normalize_signature("\n".join(lines[start:index + 1]))

    return None


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
        if not self.handled and now >= self.detected_at + max(0.1, delay):
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
