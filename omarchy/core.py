"""Persistent configuration and remote session command construction."""
import json
import os
from pathlib import Path
import shlex
import tempfile
import uuid
import re

DEFAULT_AGENTS = [
    {"name": "Codex", "command": "codex"},
    {"name": "OpenCode", "command": "opencode"},
    {"name": "Antigravity", "command": "agy"},
    {"name": "Shell", "command": "bash -l"},
]


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


def ssh_argv(host, directory, agent, session_id=None, control_path=None):
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
              f"tmux set-option -t {name} mouse on && tmux attach-session -d -t {name}; "
              f"else printf '\\n[提示] 未安装 tmux，断线后无法恢复会话\\n'; exec \"${{SHELL:-/bin/bash}}\" -l -i -c {command}; fi")
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
