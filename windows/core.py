"""Persistent configuration and remote session command construction."""
import json
import os
from pathlib import Path
import shlex
import tempfile
import time
from datetime import datetime, timezone
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
              f"tmux set-option -t {name} mouse on && tmux attach-session -t {name}; "
              f"else printf '\\n[提示] 未安装 tmux，断线后无法恢复会话\\n'; exec \"${{SHELL:-/bin/bash}}\" -l -i -c {command}; fi")
    if restore_only:
        remote = (f"cd -- {directory} && if command -v tmux >/dev/null 2>&1 && tmux has-session -t {name} 2>/dev/null; then "
                  f"tmux attach-session -t {name}; else printf '\\n[会话已不存在，无法恢复]\\n'; exit 1; fi")
    options = ["-o", "ControlMaster=auto", "-o", "ControlPersist=60", "-o", "ControlPath=" + control_path] if control_path else []
    return ["ssh", "-tt", "-o", "ServerAliveInterval=20", "-o", "ServerAliveCountMax=6", *options, "-p", str(host["port"]), "-l", host["username"], "--", host["address"], remote], session_id


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
    if not text:
        return None
    raw_lines = [line.strip() for line in text.splitlines()]
    # 过滤掉末尾的空行以及 tmux 状态栏（例如: [rdt_7fa83d7d99994cec85] 0:bash* ...）
    lines = []
    for line in raw_lines:
        if not line:
            continue
        if re.match(r"^\[(?:rdt_[0-9a-fA-F]+|\d+)\]\s+\d+:\S+", line):
            continue
        lines.append(line)
    if not lines:
        return None

    # 1. 检查末尾附近的快捷确认/回车提示（大小写不敏感，如 [y/n], [y/N], (y/n), press enter to continue 等）
    prompt_re = re.compile(
        r"(?i)(?:\[(?:y/n|yes/no|y/N|Y/n)\]|\((?:y/n|yes/no|y/N|Y/n)\)|press\s+(?:enter|return)\s+to\s+(?:continue|confirm|approve)|按\s*(?:回车|enter)\s*(?:继续|确认|批准|同意))[:\s?]*$"
    )
    for idx in range(len(lines) - 1, max(-1, len(lines) - 6), -1):
        if prompt_re.search(lines[idx]):
            context = lines[max(0, idx - 1):idx + 1]
            return _normalize_signature("\n".join(context))

    # 2. 检查多选项式交互菜单（如 Codex / Agy CLI 的光标或标记选单）
    option_re = re.compile(
        r"(?i)^\s*(?:[›❯>➜→*]|●|◉|\[[x*]\]|\([x*]\))\s*(?:\d+[.)]\s*)?(?:yes\b|allow\b|approve\b|proceed\b|continue\b|always\b|允许|批准|同意|继续|确认|总是)"
    )
    request_re = re.compile(
        r"(?i)(approve (this|the)? ?(command|action)?|allow (this|the)? ?(command|action)?|would you like to|do you want to|run this command\?|continue\? ?\[|\[(y/n|yes/no)\]|\((y/n|yes/no)\)|press\s+(enter|return)\s+to\s+(continue|confirm|approve)|按\s*(回车|enter)\s*(继续|确认|批准)|permission required|needs your approval|批准|是否允许|需要确认|是否继续)"
    )

    for i in range(len(lines) - 1, -1, -1):
        if option_re.match(lines[i]):
            for line in reversed(lines[max(0, i - 20):i]):
                if request_re.search(line):
                    return _normalize_signature(line + "\n" + lines[i])
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
    now = time.time()
    for group in message.get("command", {}).get("data", {}).get("groups", []):
        for bucket in group.get("buckets", []):
            name = bucket.get("id", "").lower()
            if "gemini" not in name:
                continue
            minutes = 10080 if "weekly" in name or "7d" in name else 300 if "5h" in name or "session" in name else None
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
