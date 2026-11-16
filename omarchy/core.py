"""Persistent configuration and remote session command construction."""
import json
import os
from pathlib import Path
import shlex
import tempfile
import uuid
import re
import subprocess
import urllib.request

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
    argv = ["ssh", "-S", control_path, "-o", "ControlMaster=no", "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=5", "-p", str(host["port"]), "-l", host["username"],
            "--", host["address"], image_upload_command()]
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
        return {"hosts": [], "agents": [dict(a) for a in DEFAULT_AGENTS]}
    with path.open(encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict) or not isinstance(data.get("hosts"), list) or not isinstance(data.get("agents"), list):
        raise ValueError("配置文件格式无效")
    return data


def save_config(data, path=None):
    path = path or config_path()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".config-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)



def sync_request(host, method, session_id=None, item=None):
    url = host.get("sync_url", "").rstrip("/")
    if not url.startswith(("http://", "https://")):
        raise ValueError("请先配置同步服务地址")
    url += "/v1/sessions" + ("/" + session_id if session_id else "")
    body = json.dumps(item, ensure_ascii=False).encode() if item is not None else None
    req = urllib.request.Request(url, data=body, method=method, headers={
        "Authorization": "Bearer " + host.get("sync_token", ""), "Content-Type": "application/json"})
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    with urllib.request.build_opener(NoRedirect).open(req, timeout=5) as response:
        return json.load(response)


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
              f"tmux set-option -t {name} mouse off && tmux attach-session -t {name}; "
              f"else printf '\\n[提示] 未安装 tmux，断线后无法恢复会话\\n'; exec \"${{SHELL:-/bin/bash}}\" -l -i -c {command}; fi")
    if restore_only:
        remote = (f"cd -- {directory} && if command -v tmux >/dev/null 2>&1 && tmux has-session -t {name} 2>/dev/null; then "
                  f"tmux attach-session -t {name}; else printf '\\n[会话已不存在，无法恢复]\\n'; exit 1; fi")
    options = ["-o", "ControlMaster=auto", "-o", "ControlPersist=60", "-o", "ControlPath=" + control_path] if control_path else []
    return ["ssh", "-tt", "-o", "ServerAliveInterval=20", "-o", "ServerAliveCountMax=6", *options, "-p", str(host["port"]), "-l", host["username"], "--", host["address"], remote], session_id


def auto_enter_signature(text):
    text = text.rstrip()
    if re.search(r"(?:\[Y/n\]|\(Y/n\)|\[Yes/no\]|(?i:press\s+(?:enter|return)\s+to\s+(?:continue|confirm|approve)))\s*$", text):
        return "\n".join([line for line in text.splitlines() if line.strip()][-2:])
    lines = text.splitlines()
    for i in range(len(lines) - 1, -1, -1):
        if re.match(r"\s*[›❯>➜]", lines[i]):
            if not re.match(r"(?i)^\s*[›❯>➜]\s*(?:\d+[.)]\s*)?(?:yes\b|allow\b|approve\b|proceed\b|continue\b|允许|批准|同意|继续|确认)", lines[i]):
                return None
            request = re.compile(r"(?i)(approve (this|the)? ?(command|action)?|allow (this|the)? ?(command|action)?|would you like to|do you want to|run this command\?|continue\? ?\[|\[(y/n|yes/no)\]|\((y/n|yes/no)\)|press\s+(enter|return)\s+to\s+(continue|confirm|approve)|permission required|needs your approval|批准|是否允许|需要确认|是否继续)")
            for line in reversed(lines[max(0, i-20):i]):
                if request.search(line):
                    return line.strip() + "\n" + lines[i].strip()
            return None
    return None


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
    if name in ("codex", "codex2"):
        return "codex", executable
    if name == "agy":
        return "agy", executable
    return None, None


def quota_remote_command(directory, executable, arguments):
    command = f"{shlex.quote(executable)} {arguments}"
    return f'cd -- {shlex.quote(directory)} && "${{SHELL:-/bin/bash}}" -l -i -c {shlex.quote(command)}'


def quota_from_codex(message):
    limits = message.get("result", {}).get("rateLimits", {})
    return [(int(w["windowDurationMins"]), max(0, min(100, 100 - int(w["usedPercent"]))))
            for name in ("primary", "secondary") if isinstance((w := limits.get(name)), dict)
            and w.get("windowDurationMins") and w.get("usedPercent") is not None]


def quota_from_agy(message):
    result = {}
    for group in message.get("command", {}).get("data", {}).get("groups", []):
        for bucket in group.get("buckets", []):
            name = bucket.get("id", "").lower()
            if "gemini" not in name:
                continue
            minutes = 10080 if "weekly" in name or "7d" in name else 300 if "5h" in name or "session" in name else None
            value = bucket.get("remaining_fraction")
            if minutes and value is not None:
                result[minutes] = max(0, min(100, int(float(value) * 100)))
    return sorted(result.items())


def format_quota(quota):
    """Show both requested windows even when only one is available."""
    values = dict(quota)
    def value(minutes):
        return f"{values[minutes]}%" if minutes in values else "--"
    return f"{value(300)} · {value(10080)}"
