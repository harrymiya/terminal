"""Persistent configuration and remote session command construction."""
import json
import hashlib
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
        return {"hosts": [], "agents": [dict(a) for a in DEFAULT_AGENTS], "sync_url": "", "sync_token": ""}
    with path.open(encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict) or not isinstance(data.get("hosts"), list) or not isinstance(data.get("agents"), list):
        raise ValueError("配置文件格式无效")
    data.setdefault("sync_url", "")
    data.setdefault("sync_token", "")
    for host in data["hosts"]:
        data["sync_url"] = data.get("sync_url") or host.get("sync_url", "")
        data["sync_token"] = data.get("sync_token") or host.get("sync_token", "")
        host.pop("sync_url", None); host.pop("sync_token", None)
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



def sync_request(config, method, session_id=None, item=None):
    return sync_data_request(config, method, "/v1/sessions" + ("/" + session_id if session_id else ""), item)


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
    with urllib.request.build_opener(NoRedirect).open(req, timeout=5) as response:
        return json.load(response)


def sync_host_key(host):
    value = f"{host.get('username', '')}@{host.get('address', '').lower()}:{host.get('port', 22)}"
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


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
