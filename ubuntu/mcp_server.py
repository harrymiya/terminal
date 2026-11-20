#!/usr/bin/env python3
"""Embedded MCP (Model Context Protocol) server and stdio bridge for Remote Dev Terminal.

Runs inside the live GTK desktop app (omarchy / ubuntu) on 127.0.0.1 so any
connected AI agent operates directly on the real UI and the user sees every
change in real time. Also runnable directly as a stdio MCP bridge.
"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

DEFAULT_MCP_PORT = 17333
MCP_PROTOCOL_VERSION = "2024-11-05"

KEY_MAP = {
    "enter": b"\r",
    "return": b"\r",
    "tab": b"\t",
    "shift+tab": b"\x1b[Z",
    "escape": b"\x1b",
    "esc": b"\x1b",
    "backspace": b"\x7f",
    "delete": b"\x1b[3~",
    "space": b" ",
    "up": b"\x1b[A",
    "down": b"\x1b[B",
    "right": b"\x1b[C",
    "left": b"\x1b[D",
    "home": b"\x1b[H",
    "end": b"\x1b[F",
    "pageup": b"\x1b[5~",
    "page_up": b"\x1b[5~",
    "pagedown": b"\x1b[6~",
    "page_down": b"\x1b[6~",
}
for _i, _ch in enumerate("abcdefghijklmnopqrstuvwxyz", start=1):
    KEY_MAP[f"ctrl+{_ch}"] = bytes([_i])

TOOLS = [
    {
        "name": "get_app_state",
        "description": "获取桌面端当前完整状态：窗口布局、缩放、侧边栏、自动回车、同步配置、主机列表、当前选中的主机/Agent/工作区，以及所有已打开终端会话。",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "select_host",
        "description": "在左侧栏切换选中的主机（界面会同步高亮并刷新对应的 Agent 和工作区列表）。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host_id": {"type": "string", "description": "主机 ID"},
                "host_index": {"type": "integer", "description": "主机索引（从 0 开始）"},
                "host_name": {"type": "string", "description": "主机名称"},
            },
        },
    },
    {
        "name": "add_host",
        "description": "添加新的 SSH 主机（支持内网/外网地址与端口、可选保存密码到系统密钥环），界面立即刷新。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "主机名称"},
                "username": {"type": "string", "description": "SSH 用户名"},
                "lan_address": {"type": "string", "description": "内网地址（与 external_address 至少填一个）"},
                "lan_port": {"type": "integer", "description": "内网端口（填写 lan_address 时必填，1-65535）"},
                "external_address": {"type": "string", "description": "外网地址"},
                "external_port": {"type": "integer", "description": "外网端口（填写 external_address 时必填，1-65535）"},
                "password": {"type": "string", "description": "可选 SSH 密码（存入系统密钥环）"},
                "sync_id": {"type": "string", "description": "可选多端同步标识"},
            },
            "required": ["name", "username"],
        },
    },
    {
        "name": "edit_host",
        "description": "编辑已有主机的配置或密码，界面立即刷新。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host_id": {"type": "string"},
                "host_index": {"type": "integer"},
                "host_name": {"type": "string"},
                "name": {"type": "string"},
                "username": {"type": "string"},
                "lan_address": {"type": "string"},
                "lan_port": {"type": "integer"},
                "external_address": {"type": "string"},
                "external_port": {"type": "integer"},
                "password": {"type": "string"},
                "clear_password": {"type": "boolean"},
                "sync_id": {"type": "string"},
            },
        },
    },
    {
        "name": "delete_host",
        "description": "删除指定主机及其保存的密码，界面立即刷新。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host_id": {"type": "string"},
                "host_index": {"type": "integer"},
                "host_name": {"type": "string"},
            },
        },
    },
    {
        "name": "select_agent",
        "description": "切换当前主机的 Agent（如 Codex、OpenCode、Antigravity、Shell 等），界面高亮同步更新。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host_id": {"type": "string"},
                "host_index": {"type": "integer"},
                "host_name": {"type": "string"},
                "agent_index": {"type": "integer", "description": "Agent 索引（从 0 开始）"},
                "agent_name": {"type": "string", "description": "Agent 名称"},
            },
        },
    },
    {
        "name": "add_agent",
        "description": "为指定主机（默认当前主机）添加新 Agent 命令预设。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Agent 名称"},
                "command": {"type": "string", "description": "启动命令"},
                "host_id": {"type": "string"},
                "host_index": {"type": "integer"},
                "host_name": {"type": "string"},
            },
            "required": ["name", "command"],
        },
    },
    {
        "name": "edit_agent",
        "description": "编辑指定主机的 Agent 名称或启动命令。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "agent_index": {"type": "integer"},
                "agent_name": {"type": "string"},
                "name": {"type": "string"},
                "command": {"type": "string"},
                "host_id": {"type": "string"},
                "host_index": {"type": "integer"},
                "host_name": {"type": "string"},
            },
        },
    },
    {
        "name": "delete_agent",
        "description": "删除指定主机的 Agent。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "agent_index": {"type": "integer"},
                "agent_name": {"type": "string"},
                "host_id": {"type": "string"},
                "host_index": {"type": "integer"},
                "host_name": {"type": "string"},
            },
        },
    },
    {
        "name": "add_workspace",
        "description": "为指定主机（默认当前主机）添加工作区目录（必须为远端绝对路径）。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "工作区显示名称"},
                "path": {"type": "string", "description": "远端绝对路径，例如 /home/dev/project"},
                "host_id": {"type": "string"},
                "host_index": {"type": "integer"},
                "host_name": {"type": "string"},
            },
            "required": ["name", "path"],
        },
    },
    {
        "name": "edit_workspace",
        "description": "编辑指定主机的工作区名称或路径。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "workspace_index": {"type": "integer"},
                "workspace_name": {"type": "string"},
                "workspace_path": {"type": "string"},
                "name": {"type": "string"},
                "path": {"type": "string"},
                "host_id": {"type": "string"},
                "host_index": {"type": "integer"},
                "host_name": {"type": "string"},
            },
        },
    },
    {
        "name": "delete_workspace",
        "description": "删除指定主机的工作区。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "workspace_index": {"type": "integer"},
                "workspace_name": {"type": "string"},
                "workspace_path": {"type": "string"},
                "host_id": {"type": "string"},
                "host_index": {"type": "integer"},
                "host_name": {"type": "string"},
            },
        },
    },
    {
        "name": "move_workspace",
        "description": "调整工作区排序（上移 offset=-1，下移 offset=1），界面同步更新。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "workspace_index": {"type": "integer"},
                "workspace_name": {"type": "string"},
                "workspace_path": {"type": "string"},
                "offset": {"type": "integer", "description": "-1 上移，1 下移"},
                "host_id": {"type": "string"},
                "host_index": {"type": "integer"},
                "host_name": {"type": "string"},
            },
            "required": ["offset"],
        },
    },
    {
        "name": "browse_remote_directory",
        "description": "通过 SSH 浏览远端主机的子目录列表（等同于界面中的「浏览远端目录」）。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "要浏览的远端路径，默认 ~ 或 /"},
                "host_id": {"type": "string"},
                "host_index": {"type": "integer"},
                "host_name": {"type": "string"},
            },
        },
    },
    {
        "name": "open_terminal",
        "description": "在界面中打开（或切换到已打开的）终端标签/平铺卡片，发起真实 SSH + tmux 会话。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host_id": {"type": "string"},
                "host_index": {"type": "integer"},
                "host_name": {"type": "string"},
                "workspace_index": {"type": "integer"},
                "workspace_name": {"type": "string"},
                "workspace_path": {"type": "string"},
                "agent_index": {"type": "integer"},
                "agent_name": {"type": "string"},
                "activate": {"type": "boolean", "description": "是否立即聚焦/切换到该终端，默认 true"},
            },
        },
    },
    {
        "name": "focus_terminal",
        "description": "聚焦指定终端（标签模式下切到对应标签，平铺模式下聚焦对应卡片）。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "session_id": {"type": "string"},
                "session_index": {"type": "integer"},
            },
        },
    },
    {
        "name": "close_terminal",
        "description": "关闭指定终端标签/卡片并断开对应本地 SSH 控制连接（等同于点击终端关闭按钮）。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "session_id": {"type": "string"},
                "session_index": {"type": "integer"},
            },
        },
    },
    {
        "name": "reconnect_terminal",
        "description": "重新连接已断开的终端（等同于点击终端上的「重连」按钮）。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "session_id": {"type": "string"},
                "session_index": {"type": "integer"},
            },
        },
    },
    {
        "name": "send_terminal_text",
        "description": "向指定终端输入文本并可选按回车提交，界面终端会实时显示输入和输出。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "session_id": {"type": "string"},
                "session_index": {"type": "integer"},
                "text": {"type": "string", "description": "要发送的文本"},
                "submit": {"type": "boolean", "description": "是否在末尾发送回车，默认 true"},
                "use_paste": {"type": "boolean", "description": "是否使用终端粘贴方式输入（多行文本自动使用粘贴）"},
                "focus": {"type": "boolean", "description": "是否聚焦该终端，默认 true"},
            },
            "required": ["text"],
        },
    },
    {
        "name": "send_terminal_keys",
        "description": "向指定终端发送按键或组合键，如 Enter, Tab, Escape, Ctrl+C, Ctrl+D, Up, Down, PageUp, PageDown 等。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "session_id": {"type": "string"},
                "session_index": {"type": "integer"},
                "keys": {
                    "oneOf": [
                        {"type": "string"},
                        {"type": "array", "items": {"type": "string"}},
                    ],
                    "description": "单个按键名或按键序列",
                },
                "focus": {"type": "boolean", "description": "是否聚焦该终端，默认 true"},
            },
            "required": ["keys"],
        },
    },
    {
        "name": "read_terminal_screen",
        "description": "读取指定终端当前可见屏幕内容（或包含回滚历史），以及连接状态和光标位置。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "session_id": {"type": "string"},
                "session_index": {"type": "integer"},
                "include_scrollback": {"type": "boolean", "description": "是否包含回滚缓冲区，默认 false（仅当前可见屏幕）"},
                "max_lines": {"type": "integer", "description": "最多返回末尾多少行，默认 200"},
            },
        },
    },
    {
        "name": "send_image_to_terminal",
        "description": "读取本地图片，转换为 PNG（<=20MB）并上传到远端 ~/.remote-dev-terminal/images/，再将路径发送到指定终端并回车提交。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "session_id": {"type": "string"},
                "session_index": {"type": "integer"},
                "image_path": {"type": "string", "description": "本地图片绝对路径"},
                "prompt_prefix": {"type": "string", "description": "路径前缀提示文本，默认「请查看这张图片：」"},
            },
            "required": ["image_path"],
        },
    },
    {
        "name": "set_view_mode",
        "description": "切换主工作区视图模式：平铺模式（tiled=true）或标签模式（tiled=false）。",
        "inputSchema": {
            "type": "object",
            "properties": {"tiled": {"type": "boolean"}},
            "required": ["tiled"],
        },
    },
    {
        "name": "set_tile_layout",
        "description": "切换平铺模式的分栏布局：grid（网格）、columns（左右分栏）、rows（上下分栏）。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "layout": {"type": "string", "enum": ["grid", "columns", "rows"]},
            },
            "required": ["layout"],
        },
    },
    {
        "name": "toggle_sidebar",
        "description": "展开或收起左侧边栏，并可调整侧边栏宽度或切换编辑模式。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "visible": {"type": "boolean", "description": "是否显示侧边栏"},
                "width": {"type": "integer", "description": "可选侧边栏宽度（像素）"},
                "edit_mode": {"type": "boolean", "description": "是否开启左侧编辑模式"},
            },
        },
    },
    {
        "name": "set_ui_scale",
        "description": "调整界面缩放比例（50–200%），界面实时缩放并保存。",
        "inputSchema": {
            "type": "object",
            "properties": {"scale": {"type": "integer", "minimum": 50, "maximum": 200}},
            "required": ["scale"],
        },
    },
    {
        "name": "update_settings",
        "description": "更新系统设置（自动回车开关与延迟、自动同步开关与周期、同步服务地址）并保存。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "auto_enter": {"type": "boolean"},
                "auto_enter_delay": {"type": "integer", "minimum": 1, "maximum": 60000},
                "auto_sync_enabled": {"type": "boolean"},
                "sync_interval_seconds": {"type": "integer", "minimum": 5, "maximum": 300},
                "sync_url": {"type": "string"},
            },
        },
    },
    {
        "name": "trigger_sync",
        "description": "立即触发一次主机、Agent、工作区和共享会话同步。",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "get_ssh_public_key",
        "description": "读取本机 ~/.ssh/id_ed25519.pub 公钥（可选在不存在时自动生成）。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "generate_if_missing": {"type": "boolean", "description": "不存在时是否自动生成 ed25519 密钥，默认 false"},
            },
        },
    },
]


def discovery_file_path():
    runtime_dir = os.environ.get("XDG_RUNTIME_DIR") or tempfile.gettempdir()
    return os.path.join(runtime_dir, "remote-dev-terminal-mcp.json")


def _sanitize_host(host, index):
    return {
        "index": index,
        "id": host.get("id", ""),
        "name": host.get("name", ""),
        "username": host.get("username", ""),
        "lan_address": host.get("lan_address", ""),
        "lan_port": host.get("lan_port"),
        "external_address": host.get("external_address", ""),
        "external_port": host.get("external_port"),
        "port": host.get("port"),
        "sync_id": host.get("sync_id", ""),
        "has_credential": bool(host.get("credential_id")),
        "selected_agent": host.get("selected_agent", 0),
        "directories": [
            {"index": i, "name": d.get("name", ""), "path": d.get("path", "")}
            for i, d in enumerate(host.get("directories", []))
        ],
        "agents": [
            {"index": i, "name": a.get("name", ""), "command": a.get("command", "")}
            for i, a in enumerate(host.get("agents", []))
        ],
    }


def _resolve_host(app, args):
    hosts = app.data.get("hosts", [])
    if not hosts:
        raise ValueError("当前没有配置任何主机，请先 add_host")
    if "host_id" in args and args["host_id"]:
        for idx, h in enumerate(hosts):
            if h.get("id") == args["host_id"]:
                return idx, h
        raise ValueError(f"未找到 host_id={args['host_id']}")
    if "host_index" in args and args["host_index"] is not None:
        idx = int(args["host_index"])
        if 0 <= idx < len(hosts):
            return idx, hosts[idx]
        raise ValueError(f"host_index 超出范围: {idx} (共 {len(hosts)} 台主机)")
    if "host_name" in args and args["host_name"]:
        for idx, h in enumerate(hosts):
            if h.get("name") == args["host_name"]:
                return idx, h
        raise ValueError(f"未找到名为 {args['host_name']!r} 的主机")
    idx = app.hosts.get_active()
    if idx < 0 or idx >= len(hosts):
        raise ValueError("当前未选中任何主机，请先 select_host 或传入 host_id/host_index/host_name")
    return idx, hosts[idx]


def _resolve_workspace(host, args):
    dirs = host.get("directories", [])
    if not dirs:
        raise ValueError(f"主机「{host.get('name', '')}」没有配置任何工作区")
    if "workspace_index" in args and args["workspace_index"] is not None:
        idx = int(args["workspace_index"])
        if 0 <= idx < len(dirs):
            return idx, dirs[idx]
        raise ValueError(f"workspace_index 超出范围: {idx}")
    if "workspace_path" in args and args["workspace_path"]:
        for idx, d in enumerate(dirs):
            if d.get("path") == args["workspace_path"]:
                return idx, d
        raise ValueError(f"未找到路径为 {args['workspace_path']!r} 的工作区")
    if "workspace_name" in args and args["workspace_name"]:
        for idx, d in enumerate(dirs):
            if d.get("name") == args["workspace_name"]:
                return idx, d
        raise ValueError(f"未找到名为 {args['workspace_name']!r} 的工作区")
    raise ValueError("请指定 workspace_index、workspace_name 或 workspace_path")


def _resolve_agent(app, host, args):
    agents = app.host_agents(host)
    if not agents:
        raise ValueError(f"主机「{host.get('name', '')}」没有配置任何 Agent")
    if "agent_index" in args and args["agent_index"] is not None:
        idx = int(args["agent_index"])
        if 0 <= idx < len(agents):
            return idx, agents[idx]
        raise ValueError(f"agent_index 超出范围: {idx}")
    if "agent_name" in args and args["agent_name"]:
        for idx, a in enumerate(agents):
            if a.get("name") == args["agent_name"]:
                return idx, a
        raise ValueError(f"未找到名为 {args['agent_name']!r} 的 Agent")
    idx = app.agents.get_active()
    if 0 <= idx < len(agents):
        return idx, agents[idx]
    return 0, agents[0]


def _resolve_session(app, args):
    sessions = app.sessions
    if not sessions:
        raise ValueError("当前没有已打开的终端会话，请先调用 open_terminal")
    if "session_id" in args and args["session_id"]:
        for idx, s in enumerate(sessions):
            if s.get("id") == args["session_id"]:
                return idx, s
        raise ValueError(f"未找到 session_id={args['session_id']}")
    if "session_index" in args and args["session_index"] is not None:
        idx = int(args["session_index"])
        if 0 <= idx < len(sessions):
            return idx, sessions[idx]
        raise ValueError(f"session_index 超出范围: {idx} (共 {len(sessions)} 个会话)")
    for idx, s in enumerate(sessions):
        if s["terminal"].has_focus():
            return idx, s
    if not app.tiled:
        page = app.tabs.get_current_page()
        if page >= 0:
            widget = app.tabs.get_nth_page(page)
            for idx, s in enumerate(sessions):
                if s["terminal"] is widget:
                    return idx, s
    if len(sessions) == 1:
        return 0, sessions[0]
    summary = ", ".join(f"[{i}] {s.get('id')} ({s.get('item', {}).get('display_name', '')})" for i, s in enumerate(sessions))
    raise ValueError(f"存在多个终端且无法确定目标，请传入 session_id 或 session_index。当前会话: {summary}")


def _focus_session(app, session):
    terminal = session["terminal"]
    if not app.tiled:
        page = app.tabs.page_num(terminal)
        if page >= 0:
            app.tabs.set_current_page(page)
    terminal.grab_focus()


def _serialize_session(app, idx, s):
    item = s.get("item", {})
    host = s.get("host", {})
    state = s.get("state", {})
    terminal = s["terminal"]
    last_out = state.get("last_output_at", 0.0)
    is_running = bool(state.get("connected") and not state.get("closed") and last_out and (time.monotonic() - last_out) < 3.0)
    tab_page = None
    if not app.tiled:
        p = app.tabs.page_num(terminal)
        tab_page = p if p >= 0 else None
    return {
        "index": idx,
        "session_id": s.get("id", ""),
        "display_name": item.get("display_name", ""),
        "host_id": host.get("id", ""),
        "host_name": host.get("name", ""),
        "directory_name": item.get("directory_name", ""),
        "directory_path": item.get("directory_path", ""),
        "agent_name": item.get("agent_name", ""),
        "agent_command": item.get("agent_command", ""),
        "connected": bool(state.get("connected", False)),
        "closed": bool(state.get("closed", False)),
        "is_focused": bool(terminal.has_focus()),
        "is_running": is_running,
        "tab_page": tab_page,
    }


def _read_vte_text(terminal, include_scrollback=False, max_lines=200):
    Vte = sys.modules.get("gi.repository.Vte")
    if Vte is None:
        import gi
        gi.require_version("Vte", "2.91")
        from gi.repository import Vte
    col, row = terminal.get_cursor_position()
    rows = max(1, terminal.get_row_count())
    cols = max(1, terminal.get_column_count())
    if not include_scrollback and hasattr(terminal, "get_text_format"):
        text = terminal.get_text_format(Vte.Format.TEXT) or ""
    else:
        start_row = 0 if include_scrollback else max(0, row - rows + 1)
        end_row = max(start_row, row)
        result = terminal.get_text_range_format(Vte.Format.TEXT, start_row, 0, end_row, cols - 1)
        text = result[0] if isinstance(result, tuple) else (result or "")
    lines = text.splitlines()
    while lines and not lines[-1]:
        lines.pop()
    if max_lines and len(lines) > max_lines:
        lines = lines[-max_lines:]
    return {
        "text": "\n".join(lines),
        "cursor": {"column": int(col), "row": int(row)},
        "size": {"rows": int(rows), "columns": int(cols)},
    }


def _parse_keys(keys):
    if isinstance(keys, str):
        keys = [keys]
    if not isinstance(keys, list) or not keys:
        raise ValueError("keys 必须是非空字符串或字符串数组")
    payload = bytearray()
    for key in keys:
        k = str(key).strip()
        lower = k.lower()
        if lower in KEY_MAP:
            payload.extend(KEY_MAP[lower])
        elif len(k) == 1:
            payload.extend(k.encode("utf-8"))
        else:
            raise ValueError(f"不支持的按键: {key!r}")
    return bytes(payload)


def _validate_and_apply_host(app, deps, args, old=None):
    old = dict(old) if old else {}
    values = {
        "name": str(args.get("name", old.get("name", ""))).strip(),
        "username": str(args.get("username", old.get("username", ""))).strip(),
        "lan_address": str(args.get("lan_address", old.get("lan_address", ""))).strip(),
        "lan_port": args.get("lan_port", old.get("lan_port") or old.get("port", "")),
        "external_address": str(args.get("external_address", old.get("external_address", ""))).strip(),
        "external_port": args.get("external_port", old.get("external_port") or old.get("port", "")),
    }
    if not values["name"] or not values["username"] or not (values["lan_address"] or values["external_address"]):
        raise ValueError("请填写 name、username 以及至少一个地址（lan_address 或 external_address）")
    parsed_ports = {}
    for addr_key, port_key in (("lan_address", "lan_port"), ("external_address", "external_port")):
        if values[addr_key]:
            try:
                port = int(values[port_key])
            except (TypeError, ValueError):
                raise ValueError(f"填写了 {addr_key} 时必须提供有效整数 {port_key}")
            if not 1 <= port <= 65535:
                raise ValueError("主机端口必须在 1 到 65535 之间")
            parsed_ports[port_key] = port
    if any(v.startswith("-") for v in (values["lan_address"], values["external_address"])):
        raise ValueError("主机地址不能以 '-' 开头")
    values["lan_port"] = parsed_ports.get("lan_port", next(iter(parsed_ports.values())))
    values["external_port"] = parsed_ports.get("external_port", next(iter(parsed_ports.values())))
    values["port"] = values["lan_port"] if values["lan_address"] else values["external_port"]
    values["address"] = values["lan_address"] or values["external_address"]
    values["id"] = old.get("id") or uuid.uuid4().hex
    values["legacy_address"] = old.get("legacy_address", old.get("address", ""))
    values["sync_id"] = str(args.get("sync_id", old.get("sync_id", ""))).strip()
    values["directories"] = old.get("directories", [])
    values["agents"] = old.get("agents", [dict(a) for a in app.data.get("agents", [])])
    values["selected_agent"] = old.get("selected_agent", 0)
    if old.get("_sync_fingerprint"):
        values["_sync_fingerprint"] = old["_sync_fingerprint"]
    credential_id = old.get("credential_id")
    password = args.get("password")
    if args.get("clear_password"):
        if password:
            raise ValueError("password 与 clear_password 不能同时指定")
        if credential_id:
            deps["clear_host_password"](credential_id)
            credential_id = None
    elif password:
        credential_id = credential_id or uuid.uuid4().hex
        deps["save_host_password"](credential_id, password)
    if credential_id:
        values["credential_id"] = credential_id
    return values


def execute_app_tool_sync(app, name, args, deps):
    """Execute a synchronous UI/state tool on the GTK main thread."""
    args = args or {}

    if name == "get_app_state":
        hosts = app.data.get("hosts", [])
        return {
            "platform": deps["platform"],
            "version": deps["version"],
            "window_size": list(app.get_size()),
            "tiled": bool(app.tiled),
            "tile_layout": app.tile_layout,
            "ui_scale": app.scale,
            "sidebar_visible": bool(app.sidebar_revealer.get_reveal_child()),
            "sidebar_width": int(app.sidebar_paned.get_position()),
            "edit_mode": bool(app.edit_mode),
            "auto_enter": bool(app.auto_enter.get_active()),
            "auto_enter_delay": int(app.delay.get_value_as_int()),
            "auto_sync_enabled": bool(app.data.get("auto_sync_enabled", False)),
            "sync_interval_seconds": int(app.data.get("sync_interval_seconds", 5)),
            "sync_url": app.data.get("sync_url", ""),
            "sync_username": app.data.get("sync_username", ""),
            "current_host_index": app.hosts.get_active(),
            "current_agent_index": app.agents.get_active(),
            "current_directory_index": app.directories.get_active(),
            "hosts": [_sanitize_host(h, i) for i, h in enumerate(hosts)],
            "sessions": [_serialize_session(app, i, s) for i, s in enumerate(app.sessions)],
        }

    if name == "select_host":
        idx, host = _resolve_host(app, args)
        app.hosts.set_active(idx)
        return {"selected_host_index": idx, "host": _sanitize_host(host, idx)}

    if name == "add_host":
        host = _validate_and_apply_host(app, deps, args, old=None)
        app.data.setdefault("hosts", []).append(host)
        idx = len(app.data["hosts"]) - 1
        app.persist()
        app.refresh()
        host.pop("_resolved", None)
        deps["ssh_address"](host, force=True)
        app.push_host_profile(host)
        app.push_host_state(host)
        app.refresh_all_shared(prompt_for_login=False)
        app.hosts.set_active(idx)
        return {"host": _sanitize_host(host, idx)}

    if name == "edit_host":
        idx, old = _resolve_host(app, args)
        updated = _validate_and_apply_host(app, deps, args, old=old)
        app.data["hosts"][idx] = updated
        app.persist()
        app.refresh()
        updated.pop("_resolved", None)
        deps["ssh_address"](updated, force=True)
        app.push_host_profile(updated)
        app.push_host_state(updated)
        app.refresh_all_shared(prompt_for_login=False)
        return {"host": _sanitize_host(updated, idx)}

    if name == "delete_host":
        idx, host = _resolve_host(app, args)
        if host.get("credential_id"):
            deps["clear_host_password"](host["credential_id"])
        app.delete_host_profile(host.get("id", ""))
        app.data["hosts"].pop(idx)
        app.persist()
        app.refresh()
        app.refresh_all_shared(prompt_for_login=False)
        return {"deleted_host_id": host.get("id", ""), "deleted_host_name": host.get("name", "")}

    if name == "select_agent":
        h_idx, host = _resolve_host(app, args)
        if app.hosts.get_active() != h_idx:
            app.hosts.set_active(h_idx)
        a_idx, agent = _resolve_agent(app, host, args)
        app.agents.set_active(a_idx)
        return {"host_index": h_idx, "agent_index": a_idx, "agent": agent}

    if name == "add_agent":
        h_idx, host = _resolve_host(app, args)
        agent_name = str(args.get("name", "")).strip()
        command = str(args.get("command", "")).strip()
        if not agent_name or not command:
            raise ValueError("name 和 command 不能为空")
        agents = app.host_agents(host)
        agent = {"name": agent_name, "command": command}
        agents.append(agent)
        host["selected_agent"] = len(agents) - 1
        app.persist()
        app.refresh()
        app.push_agent_list(host)
        return {"host_index": h_idx, "agent_index": host["selected_agent"], "agent": agent}

    if name == "edit_agent":
        h_idx, host = _resolve_host(app, args)
        agents = app.host_agents(host)
        a_idx, old = _resolve_agent(app, host, args)
        updated = {
            "name": str(args.get("name", old.get("name", ""))).strip(),
            "command": str(args.get("command", old.get("command", ""))).strip(),
        }
        if not updated["name"] or not updated["command"]:
            raise ValueError("name 和 command 不能为空")
        agents[a_idx] = updated
        app.persist()
        app.refresh()
        app.push_agent_list(host)
        return {"host_index": h_idx, "agent_index": a_idx, "agent": updated}

    if name == "delete_agent":
        h_idx, host = _resolve_host(app, args)
        agents = app.host_agents(host)
        a_idx, old = _resolve_agent(app, host, args)
        agents.pop(a_idx)
        host["selected_agent"] = min(a_idx, max(0, len(agents) - 1))
        app.persist()
        app.refresh()
        app.push_agent_list(host)
        return {"host_index": h_idx, "deleted_agent": old}

    if name == "add_workspace":
        h_idx, host = _resolve_host(app, args)
        ws_name = str(args.get("name", "")).strip()
        ws_path = str(args.get("path", "")).strip()
        if not ws_name or not ws_path.startswith("/"):
            raise ValueError("name 不能为空且 path 必须是远端绝对路径（以 / 开头）")
        ws = {"name": ws_name, "path": ws_path}
        host.setdefault("directories", []).append(ws)
        app.persist()
        app.refresh()
        app.push_workspace_list(host)
        return {"host_index": h_idx, "workspace_index": len(host["directories"]) - 1, "workspace": ws}

    if name == "edit_workspace":
        h_idx, host = _resolve_host(app, args)
        w_idx, old = _resolve_workspace(host, args)
        updated = {
            "name": str(args.get("name", old.get("name", ""))).strip(),
            "path": str(args.get("path", old.get("path", ""))).strip(),
        }
        if not updated["name"] or not updated["path"].startswith("/"):
            raise ValueError("name 不能为空且 path 必须是远端绝对路径（以 / 开头）")
        host["directories"][w_idx] = updated
        app.persist()
        app.refresh()
        app.push_workspace_list(host)
        return {"host_index": h_idx, "workspace_index": w_idx, "workspace": updated}

    if name == "delete_workspace":
        h_idx, host = _resolve_host(app, args)
        w_idx, old = _resolve_workspace(host, args)
        host["directories"].pop(w_idx)
        app.persist()
        app.refresh()
        app.push_workspace_list(host)
        return {"host_index": h_idx, "deleted_workspace": old}

    if name == "move_workspace":
        h_idx, host = _resolve_host(app, args)
        if app.hosts.get_active() != h_idx:
            app.hosts.set_active(h_idx)
        w_idx, _ = _resolve_workspace(host, args)
        offset = int(args["offset"])
        app.move_directory(None, w_idx, offset)
        return {"host_index": h_idx, "directories": host.get("directories", [])}

    if name == "open_terminal":
        h_idx, host = _resolve_host(app, args)
        if app.hosts.get_active() != h_idx:
            app.hosts.set_active(h_idx)
        if any(k in args and args[k] is not None for k in ("agent_index", "agent_name")):
            a_idx, _ = _resolve_agent(app, host, args)
            app.agents.set_active(a_idx)
        if any(k in args and args[k] is not None for k in ("workspace_index", "workspace_name", "workspace_path")):
            w_idx, _ = _resolve_workspace(host, args)
            app.directories.set_active(w_idx)
        elif app.directories.get_active() < 0 and host.get("directories"):
            app.directories.set_active(0)
        if app.directories.get_active() < 0 or app.agents.get_active() < 0:
            raise ValueError("请确保当前主机已配置至少一个工作区和 Agent")
        activate = bool(args.get("activate", True))
        before_ids = {s["id"] for s in app.sessions}
        app.open_terminal(None, activate=activate)
        # Find the newly opened or existing matching session
        directory = host["directories"][app.directories.get_active()]
        agent = app.host_agents(host)[app.agents.get_active()]
        target = next((s for s in app.sessions if s["id"] not in before_ids), None)
        if target is None:
            key = (host.get("id"), host["port"], host["username"], directory["path"], agent["command"])
            target = next((s for s in app.sessions if s["key"] == key), None)
        if target is None:
            raise RuntimeError("打开终端失败（可能已达 8 个终端上限）")
        idx = app.sessions.index(target)
        return {"session": _serialize_session(app, idx, target)}

    if name == "focus_terminal":
        idx, session = _resolve_session(app, args)
        _focus_session(app, session)
        return {"session": _serialize_session(app, idx, session)}

    if name == "close_terminal":
        idx, session = _resolve_session(app, args)
        sid = session.get("id", "")
        session["close_button"].clicked()
        return {"closed_session_id": sid, "closed_index": idx, "remaining_sessions": len(app.sessions)}

    if name == "reconnect_terminal":
        idx, session = _resolve_session(app, args)
        btn = session.get("reconnect_button")
        if btn is None:
            raise RuntimeError("该终端不支持重连操作")
        btn.clicked()
        return {"reconnecting_session_id": session.get("id", ""), "session_index": idx}

    if name == "send_terminal_text":
        idx, session = _resolve_session(app, args)
        terminal = session["terminal"]
        text = str(args["text"])
        submit = bool(args.get("submit", True))
        use_paste = bool(args.get("use_paste", "\n" in text))
        if args.get("focus", True):
            _focus_session(app, session)
        if text:
            if use_paste:
                terminal.paste_text(text)
            else:
                terminal.feed_child(text.encode("utf-8"))
        if submit:
            terminal.feed_child(b"\r")
        return {"sent": True, "session_id": session.get("id", ""), "bytes": len(text.encode("utf-8")), "submitted": submit}

    if name == "send_terminal_keys":
        idx, session = _resolve_session(app, args)
        terminal = session["terminal"]
        payload = _parse_keys(args["keys"])
        if args.get("focus", True):
            _focus_session(app, session)
        terminal.feed_child(payload)
        return {"sent": True, "session_id": session.get("id", ""), "bytes": len(payload)}

    if name == "read_terminal_screen":
        idx, session = _resolve_session(app, args)
        screen = _read_vte_text(
            session["terminal"],
            include_scrollback=bool(args.get("include_scrollback", False)),
            max_lines=int(args.get("max_lines", 200)),
        )
        screen["session"] = _serialize_session(app, idx, session)
        return screen

    if name == "set_view_mode":
        tiled = bool(args["tiled"])
        app.view_button.set_active(tiled)
        return {"tiled": bool(app.tiled)}

    if name == "set_tile_layout":
        layout = str(args["layout"])
        if layout not in ("grid", "columns", "rows"):
            raise ValueError("layout 必须是 grid、columns 或 rows")
        app.tile_layout = layout
        layout_labels = {"grid": "⊞  切换布局 · 网格", "columns": "⬓  切换布局 · 左右", "rows": "⬒  切换布局 · 上下"}
        app.layout_button.set_label(layout_labels[layout])
        app.data["tile_layout"] = layout
        app.persist()
        if app.tiled and app.sessions:
            app.rebuild_view()
        return {"tile_layout": app.tile_layout}

    if name == "toggle_sidebar":
        if "visible" in args and args["visible"] is not None:
            app.menu_toggle.set_active(bool(args["visible"]))
        if "width" in args and args["width"] is not None:
            w = max(200, min(2000, int(args["width"])))
            app.sidebar_paned.set_position(w)
        if "edit_mode" in args and args["edit_mode"] is not None:
            app.edit_toggle.set_active(bool(args["edit_mode"]))
        return {
            "sidebar_visible": bool(app.sidebar_revealer.get_reveal_child()),
            "sidebar_width": int(app.sidebar_paned.get_position()),
            "edit_mode": bool(app.edit_mode),
        }

    if name == "set_ui_scale":
        scale = int(args["scale"])
        if not 50 <= scale <= 200:
            raise ValueError("scale 必须在 50 到 200 之间")
        app.zoom_spin.set_value(scale)
        return {"ui_scale": app.scale}

    if name == "update_settings":
        if "auto_enter" in args and args["auto_enter"] is not None:
            app.auto_enter.set_active(bool(args["auto_enter"]))
        if "auto_enter_delay" in args and args["auto_enter_delay"] is not None:
            raw_d = int(args["auto_enter_delay"])
            d = raw_d * 1000 if raw_d <= 30 else raw_d
            d = max(50, min(60000, d))
            app.delay.set_value(d)
        if "auto_sync_enabled" in args and args["auto_sync_enabled"] is not None:
            app.data["auto_sync_enabled"] = bool(args["auto_sync_enabled"])
        if "sync_interval_seconds" in args and args["sync_interval_seconds"] is not None:
            app.data["sync_interval_seconds"] = max(5, min(300, int(args["sync_interval_seconds"])))
        if "sync_url" in args and args["sync_url"] is not None:
            new_url = str(args["sync_url"]).strip()
            if app.data.get("sync_url") != new_url:
                app.data["sync_token"] = ""
            app.data["sync_url"] = new_url
        app.save_settings()
        app.configure_sync_polling()
        return {
            "auto_enter": bool(app.auto_enter.get_active()),
            "auto_enter_delay": int(app.delay.get_value_as_int()),
            "auto_sync_enabled": bool(app.data.get("auto_sync_enabled", False)),
            "sync_interval_seconds": int(app.data.get("sync_interval_seconds", 5)),
            "sync_url": app.data.get("sync_url", ""),
        }

    if name == "trigger_sync":
        app.refresh_all_shared(prompt_for_login=False)
        return {"triggered": True}

    if name == "get_ssh_public_key":
        import subprocess
        key_path = Path.home() / ".ssh" / "id_ed25519.pub"
        if not key_path.exists() and args.get("generate_if_missing"):
            key_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            res = subprocess.run(
                ["ssh-keygen", "-t", "ed25519", "-N", "", "-f", str(key_path.with_suffix("")), "-C", "remote-dev-terminal"],
                capture_output=True, text=True,
            )
            if res.returncode:
                raise RuntimeError(res.stderr.strip() or "ssh-keygen 执行失败")
        return {
            "exists": key_path.exists(),
            "path": str(key_path),
            "public_key": key_path.read_text(encoding="utf-8").strip() if key_path.exists() else "",
        }

    raise ValueError(f"未知工具: {name}")


def list_remote_directories(host, requested, askpass_path, ssh_address_fn, ssh_port_fn, ssh_auth_env_fn):
    import shlex
    import subprocess
    command = "cd -- " + (shlex.quote(requested) if requested else '"$HOME"') + " && printf '%s\\0' \"$PWD\" && find -L . -mindepth 1 -maxdepth 1 -type d -printf '%f\\0'"
    address = ssh_address_fn(host)
    argv = ["ssh", "-o", "ConnectTimeout=20", "-p", str(ssh_port_fn(host)), "-l", host["username"], "--", address, command]
    env = ssh_auth_env_fn(host, askpass_path)
    proc = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=60, start_new_session=True)
    if proc.returncode:
        raise RuntimeError(proc.stderr.decode(errors="replace").strip() or "SSH 连接失败")
    parts = proc.stdout.decode("utf-8", errors="replace").split("\0")
    if not parts[0].startswith("/"):
        raise RuntimeError("远端返回的目录无效")
    return parts[0], sorted((x for x in parts[1:] if x), key=str.casefold)


def execute_app_tool(app, name, args, deps, run_on_main):
    """Execute any MCP tool, running SSH/image I/O off the GTK main thread."""
    args = args or {}

    if name == "browse_remote_directory":
        def prep():
            _, host = _resolve_host(app, args)
            return dict(host)
        host = run_on_main(prep)
        requested = str(args.get("path", "")).strip()
        loaded, entries = list_remote_directories(
            host, requested, deps["askpass_path"],
            deps["ssh_address"], deps["ssh_port"], deps["ssh_auth_environment"],
        )
        return {"path": loaded, "directories": entries}

    if name == "send_image_to_terminal":
        image_path = str(args.get("image_path", "")).strip()
        if not image_path or not os.path.isfile(image_path):
            raise ValueError(f"本地图片不存在: {image_path}")
        if os.path.getsize(image_path) > deps["max_image_bytes"]:
            raise ValueError("图片文件超过 20 MB")

        def prep():
            import gi
            gi.require_version("GdkPixbuf", "2.0")
            from gi.repository import GdkPixbuf
            idx, session = _resolve_session(app, args)
            state = session["state"]
            if state.get("closed") or not state.get("connected"):
                raise RuntimeError("终端尚未连接或已关闭，请等待连接成功后再发送图片")
            info = GdkPixbuf.Pixbuf.get_file_info(image_path)
            if not info or info[0] is None or info[1] * info[2] > 40_000_000:
                raise ValueError("图片格式无效或尺寸超过 4000 万像素")
            pixbuf = GdkPixbuf.Pixbuf.new_from_file(image_path)
            ok, data = pixbuf.save_to_bufferv("png", [], [])
            if not ok or not data or len(data) > deps["max_image_bytes"]:
                raise ValueError("转换后的 PNG 图片为空或超过 20 MB")
            return idx, session["id"], session["host"], session.get("socket_path", ""), state["generation"], bytes(data)

        idx, sid, host, socket_path, generation, png_bytes = run_on_main(prep)
        if not socket_path:
            raise RuntimeError("该终端缺少 SSH 控制通道，无法上传图片")
        remote_path = deps["upload_image"](host, socket_path, png_bytes)
        prefix = str(args.get("prompt_prefix", "请查看这张图片："))

        def finish():
            _, session = _resolve_session(app, {"session_id": sid})
            state = session["state"]
            if state.get("closed") or not state.get("connected") or state.get("generation") != generation:
                raise RuntimeError(f"图片已上传到 {remote_path}，但终端连接状态已变化")
            _focus_session(app, session)
            session["terminal"].paste_text(prefix + remote_path)
            session["terminal"].feed_child(b"\r")
            return {"session_id": sid, "remote_path": remote_path, "sent": True}

        return run_on_main(finish)

    return run_on_main(lambda: execute_app_tool_sync(app, name, args, deps))


def handle_jsonrpc_request(req, tool_executor, version="1.0.0"):
    if not isinstance(req, dict):
        return {"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Invalid Request"}}
    req_id = req.get("id")
    method = req.get("method", "")
    params = req.get("params") or {}

    if method == "notifications/initialized" or req_id is None and method.startswith("notifications/"):
        return None
    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "protocolVersion": MCP_PROTOCOL_VERSION,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "remote-dev-terminal-mcp", "version": version},
            },
        }
    if method == "ping":
        return {"jsonrpc": "2.0", "id": req_id, "result": {}}
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": req_id, "result": {"tools": TOOLS}}
    if method == "tools/call":
        tool_name = params.get("name", "")
        arguments = params.get("arguments") or {}
        try:
            result = tool_executor(tool_name, arguments)
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False, indent=2)}],
                    "isError": False,
                },
            }
        except Exception as exc:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": str(exc)}],
                    "isError": True,
                },
            }
    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32601, "message": f"Method not found: {method}"}}


class EmbeddedMCPServer:
    """Runs an HTTP MCP endpoint inside the live GTK App process."""

    def __init__(self, app, deps, port=None):
        self.app = app
        self.deps = deps
        requested_port = port if port is not None else int(os.environ.get("RDT_MCP_PORT") or app.data.get("mcp_port") or DEFAULT_MCP_PORT)
        self.port = requested_port
        self.httpd = None
        self.thread = None
        self._discovery_written = False

    def run_on_gtk_main(self, fn, timeout=30.0):
        from gi.repository import GLib
        if threading.current_thread() is threading.main_thread():
            return fn()
        done = threading.Event()
        box = {}

        def callback():
            try:
                box["result"] = fn()
            except Exception as exc:
                box["error"] = exc
            finally:
                done.set()
            return False

        GLib.idle_add(callback)
        if not done.wait(timeout):
            raise TimeoutError("等待桌面界面响应超时")
        if "error" in box:
            raise box["error"]
        return box.get("result")

    def start(self):
        parent = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                return

            def _send_json(self, status, payload):
                body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                if self.path in ("/health", "/"):
                    self._send_json(200, {
                        "ok": True,
                        "app": "remote-dev-terminal",
                        "platform": parent.deps["platform"],
                        "version": parent.deps["version"],
                        "port": parent.port,
                        "url": f"http://127.0.0.1:{parent.port}/mcp",
                    })
                    return
                self._send_json(404, {"error": "Not found"})

            def do_POST(self):
                if self.path != "/mcp":
                    self._send_json(404, {"error": "Not found"})
                    return
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length)
                try:
                    payload = json.loads(raw.decode("utf-8"))
                except Exception:
                    self._send_json(400, {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}})
                    return

                def exec_tool(name, arguments):
                    return execute_app_tool(parent.app, name, arguments, parent.deps, parent.run_on_gtk_main)

                if isinstance(payload, list):
                    responses = []
                    for item in payload:
                        res = handle_jsonrpc_request(item, exec_tool, version=parent.deps["version"])
                        if res is not None:
                            responses.append(res)
                    if not responses:
                        self.send_response(202)
                        self.end_headers()
                        return
                    self._send_json(200, responses)
                    return

                res = handle_jsonrpc_request(payload, exec_tool, version=parent.deps["version"])
                if res is None:
                    self.send_response(202)
                    self.end_headers()
                    return
                self._send_json(200, res)

        class ReusableHTTPServer(ThreadingHTTPServer):
            allow_reuse_address = True
            daemon_threads = True

        try:
            self.httpd = ReusableHTTPServer(("127.0.0.1", self.port), Handler)
        except OSError:
            self.httpd = ReusableHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True, name="rdt-mcp-http")
        self.thread.start()
        self._write_discovery()
        return self.port

    def _write_discovery(self):
        path = discovery_file_path()
        try:
            data = {
                "pid": os.getpid(),
                "port": self.port,
                "url": f"http://127.0.0.1:{self.port}/mcp",
                "platform": self.deps["platform"],
                "version": self.deps["version"],
            }
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(data, fh, ensure_ascii=False)
            self._discovery_written = True
        except OSError:
            pass

    def stop(self):
        if self.httpd is not None:
            try:
                self.httpd.shutdown()
                self.httpd.server_close()
            except OSError:
                pass
            self.httpd = None
        if self._discovery_written:
            try:
                path = discovery_file_path()
                if os.path.exists(path):
                    info = json.loads(Path(path).read_text(encoding="utf-8"))
                    if info.get("pid") == os.getpid():
                        os.unlink(path)
            except (OSError, ValueError):
                pass
            self._discovery_written = False


def resolve_mcp_url():
    if os.environ.get("RDT_MCP_URL"):
        return os.environ["RDT_MCP_URL"].strip()
    path = discovery_file_path()
    if os.path.exists(path):
        try:
            info = json.loads(Path(path).read_text(encoding="utf-8"))
            if info.get("url"):
                return info["url"]
        except (OSError, ValueError):
            pass
    port = os.environ.get("RDT_MCP_PORT") or DEFAULT_MCP_PORT
    return f"http://127.0.0.1:{port}/mcp"


def _post_json(url, payload, timeout=60.0):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST", headers={"Content-Type": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=timeout) as resp:
        body = resp.read()
        return json.loads(body.decode("utf-8")) if body else None


def run_stdio_bridge():
    """Stdio MCP server that forwards tool calls to the running desktop app."""
    def forward_tool(name, arguments):
        url = resolve_mcp_url()
        req = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": name, "arguments": arguments}}
        try:
            resp = _post_json(url, req)
        except urllib.error.URLError as exc:
            raise RuntimeError(
                f"无法连接到运行中的 Remote Dev Terminal 桌面端 ({url}): {exc}。"
                "请先启动 Remote Dev Terminal 桌面应用，再重试。"
            ) from exc
        if not isinstance(resp, dict):
            raise RuntimeError("桌面端 MCP 返回了无效响应")
        if "error" in resp:
            raise RuntimeError(resp["error"].get("message", "未知 MCP 错误"))
        result = resp.get("result") or {}
        if result.get("isError"):
            texts = [c.get("text", "") for c in result.get("content", []) if c.get("type") == "text"]
            raise RuntimeError("\n".join(texts) or "工具执行失败")
        texts = [c.get("text", "") for c in result.get("content", []) if c.get("type") == "text"]
        if len(texts) == 1:
            try:
                return json.loads(texts[0])
            except ValueError:
                return {"text": texts[0]}
        return {"content": result.get("content", [])}

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            resp = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}}
        else:
            resp = handle_jsonrpc_request(req, forward_tool)
        if resp is not None:
            sys.stdout.write(json.dumps(resp, ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    if "--url" in sys.argv:
        print(resolve_mcp_url())
    elif "--check" in sys.argv:
        url = resolve_mcp_url().removesuffix("/mcp") + "/health"
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(url, timeout=3) as resp:
            print(resp.read().decode("utf-8"))
    else:
        run_stdio_bridge()
