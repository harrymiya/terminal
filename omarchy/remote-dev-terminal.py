#!/usr/bin/env python3
import sys
import os
import json
import shlex
import subprocess
import tempfile
import threading
import uuid
import select
import time
import shutil
import re
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import gi
gi.require_version("Gtk", "3.0")
gi.require_version("Vte", "2.91")
gi.require_version("GdkPixbuf", "2.0")
from gi.repository import Gtk, Vte, GLib, Gdk, GdkPixbuf
from core import load_config, save_config, ssh_argv, auto_enter_signature, quota_agent, quota_remote_command, quota_from_codex, quota_from_agy, format_quota, upload_image, MAX_IMAGE_BYTES, sync_request
from version import VERSION


class App(Gtk.Window):
    def __init__(self):
        super().__init__(title="Remote Dev Terminal")
        self.set_default_size(1200, 760)
        self.set_size_request(700, 450)
        self.sessions = []
        self.shared_by_host = {}
        self.sync_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="session-sync")
        self.tiled = False
        try:
            self.data = load_config()
        except (OSError, ValueError) as exc:
            self.message("无法读取配置", str(exc))
            raise SystemExit(1)
        self.scale = self.data.get("ui_scale", 100)
        if not isinstance(self.scale, int) or isinstance(self.scale, bool) or not 50 <= self.scale <= 200:
            self.scale = 100
        self.css = Gtk.CssProvider()
        self.base_css = '''
            * { font-size: 13px; }
            window { background: #0c1119; color: #e8eef6; }
            button, entry, spinbutton, combobox { min-height: 24px; padding: 4px 6px; }
            notebook tab { padding: 4px 6px; }
            .sidebar { background: #151e2a; border-right: 1px solid #293748; }
            .brand { font-size: 19px; font-weight: bold; color: #a2e8cc; }
            .subtitle { color: #9aabbc; font-size: 11px; }
            .section { color: #a8b9ca; font-weight: bold; margin-top: 12px; }
            .toolbar { background: #111a25; border-bottom: 1px solid #293748; padding: 8px 12px; }
            .menu-toggle { background-image: none; background-color: #1b2a37; color: #c9d9e4; border: 1px solid #344b59; border-radius: 8px; padding: 6px 11px; box-shadow: none; }
            .menu-toggle:hover { background-color: #263e46; border-color: #6dbda3; color: #edfff7; }
            .menu-toggle:checked { background-color: #25443d; border-color: #70c8a8; color: #c5f6e1; }
            .menu-toggle:checked:hover { background-color: #30554a; }
            .menu-toggle:focus { outline-color: #86dbb8; }
            .workspace-title { font-weight: bold; font-size: 15px; }
            .primary { background: #86dbb8; color: #101c1a; font-weight: bold; border: 0; }
            .tile-grid { background: #344558; }
            .tile { background: #111a25; }
            .tile-header { padding: 5px 8px; background: #1b2735; }
            .tile-focused { box-shadow: inset 0 0 0 2px #86dbb8; }
            .tile-focused .tile-header { background: #2b5146; }
            .empty { color: #91a5b8; font-size: 15px; }
            .host-button { background: #1d2a39; border: 1px solid #34485b; padding: 8px; }
            .host-selected { background: #29483f; border-color: #86dbb8; color: #d8fff0; }
            .project-button { background: #202e3d; border: 1px solid #34485b; padding: 10px; }
            .project-button:hover { background: #2b483f; border-color: #86dbb8; }
            .manage-button { padding: 4px 7px; }
        '''
        self.apply_scale_css()
        Gtk.StyleContext.add_provider_for_screen(Gdk.Screen.get_default(), self.css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        root = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        self.add(root)
        self.sidebar_revealer = Gtk.Revealer(reveal_child=True)
        self.sidebar_revealer.set_transition_type(Gtk.RevealerTransitionType.SLIDE_RIGHT)
        root.pack_start(self.sidebar_revealer, False, False, 0)
        sidebar_frame = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        sidebar_frame.get_style_context().add_class("sidebar")
        self.sidebar_revealer.add(sidebar_frame)
        sidebar_scroll = Gtk.ScrolledWindow()
        sidebar_scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        sidebar_frame.pack_start(sidebar_scroll, True, True, 0)
        sidebar = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin=16)
        self.sidebar = sidebar
        sidebar.set_size_request(round(250 * self.scale / 100), -1)
        sidebar_scroll.add(sidebar)
        brand = Gtk.Label(label="REMOTE DEV", xalign=0)
        brand.get_style_context().add_class("brand")
        sidebar.pack_start(brand, False, False, 0)
        subtitle = Gtk.Label(label=f"远程开发工作台 · v{VERSION}", xalign=0)
        subtitle.get_style_context().add_class("subtitle")
        sidebar.pack_start(subtitle, False, False, 0)
        self.refreshing = False
        for title, attr in [("主机", "hosts"), ("当前 Agent", "agents"), ("工作区 · 点击直达", "directories")]:
            heading = Gtk.Label(label=title, xalign=0)
            heading.get_style_context().add_class("section")
            sidebar.pack_start(heading, False, False, 0)
            combo = Gtk.ComboBoxText()
            setattr(self, attr, combo)
            if attr == "agents":
                sidebar.pack_start(combo, False, False, 0)
            else:
                items_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=5)
                setattr(self, attr + "_list", items_box)
                sidebar.pack_start(items_box, False, False, 0)
            buttons = Gtk.Box(spacing=4)
            actions = [("添加工作区", "add")] if attr == "directories" else [("添加", "add"), ("编辑", "edit"), ("删除", "delete")]
            for label, action in actions:
                button = Gtk.Button(label=label)
                button.connect("clicked", self.manage, attr, action)
                buttons.pack_start(button, True, True, 0)
            sidebar.pack_start(buttons, False, False, 0)
        self.hosts.connect("changed", self.host_changed)
        self.agents.connect("changed", self.agent_changed)
        settings = Gtk.Box(spacing=5)
        self.auto_enter = Gtk.CheckButton(label="自动回车")
        self.auto_enter.set_active(self.data.get("auto_enter", False))
        self.auto_enter.connect("toggled", self.save_settings)
        settings.pack_start(self.auto_enter, False, False, 0)
        self.delay = Gtk.SpinButton.new_with_range(1, 30, 1)
        self.delay.set_value(self.data.get("auto_enter_delay", 3))
        self.delay.connect("value-changed", self.save_settings)
        settings.pack_start(self.delay, False, False, 0)
        settings.pack_start(Gtk.Label(label="秒"), False, False, 0)
        sidebar.pack_start(settings, False, False, 0)
        shared_title = Gtk.Label(label="共享会话", xalign=0)
        shared_title.get_style_context().add_class("section")
        sidebar.pack_start(shared_title, False, False, 0)
        self.shared_list = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=5)
        sidebar.pack_start(self.shared_list, False, False, 0)
        hint = Gtk.Label(label="SSH 密码和密钥由系统 SSH 处理。\n关闭标签会断开连接；tmux 会话保留在远端。", xalign=0)
        hint.set_line_wrap(True)
        hint.get_style_context().add_class("subtitle")
        sidebar.pack_start(hint, False, False, 0)
        workspace = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        root.pack_start(workspace, True, True, 0)
        toolbar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        toolbar.get_style_context().add_class("toolbar")
        workspace.pack_start(toolbar, False, False, 0)
        menu = Gtk.ToggleButton()
        menu.get_style_context().add_class("menu-toggle")
        menu.set_tooltip_text("显示或隐藏侧边栏")
        menu_content = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=7)
        menu_content.pack_start(Gtk.Image.new_from_icon_name("open-menu-symbolic", Gtk.IconSize.MENU), False, False, 0)
        menu_content.pack_start(Gtk.Label(label="菜单"), False, False, 0)
        menu.add(menu_content)
        menu.set_active(True)
        menu.connect("toggled", lambda button: self.sidebar_revealer.set_reveal_child(button.get_active()))
        toolbar.pack_start(menu, False, False, 0)
        title = Gtk.Label(label="工作区", xalign=0)
        title.get_style_context().add_class("workspace-title")
        toolbar.pack_start(title, True, True, 0)
        shared = Gtk.Button(label="共享会话")
        shared.connect("clicked", self.show_shared_sessions)
        toolbar.pack_start(shared, False, False, 0)
        self.count_label = Gtk.Label(label="0 / 8")
        self.count_label.get_style_context().add_class("subtitle")
        toolbar.pack_start(self.count_label, False, False, 0)
        self.view_button = Gtk.ToggleButton(label="▦  平铺模式")
        self.view_button.get_style_context().add_class("menu-toggle")
        self.view_button.set_tooltip_text("切换标签和平铺视图")
        self.view_button.connect("toggled", self.change_view)
        toolbar.pack_start(self.view_button, False, False, 0)
        zoom = Gtk.SpinButton.new_with_range(50, 200, 1)
        zoom.set_numeric(True)
        zoom.set_value(self.scale)
        zoom.set_tooltip_text("界面缩放")
        zoom.connect("value-changed", self.change_scale)
        toolbar.pack_start(zoom, False, False, 0)
        toolbar.pack_start(Gtk.Label(label="%"), False, False, 0)
        self.views = Gtk.Stack()
        self.views.set_transition_type(Gtk.StackTransitionType.CROSSFADE)
        workspace.pack_start(self.views, True, True, 0)
        self.tabs = Gtk.Notebook(scrollable=True)
        self.views.add_named(self.tabs, "tabs")
        self.grid = Gtk.Grid(column_spacing=1, row_spacing=1, column_homogeneous=True, row_homogeneous=True)
        self.grid.get_style_context().add_class("tile-grid")
        self.grid.set_hexpand(True)
        self.grid.set_vexpand(True)
        self.views.add_named(self.grid, "grid")
        self.empty = Gtk.Label(label="选择左侧主机和 Agent，然后点击工作区直达终端。")
        self.empty.get_style_context().add_class("empty")
        self.views.add_named(self.empty, "empty")
        self.views.set_visible_child_name("empty")
        self.refresh()
        self.refresh_all_shared()
        self.scale_layout(self)
        self.connect("destroy", Gtk.main_quit)

    def change_view(self, button):
        self.tiled = button.get_active()
        button.set_label("▤  标签模式" if self.tiled else "▦  平铺模式")
        self.rebuild_view()

    def apply_scale_css(self):
        factor = self.scale / 100
        css = re.sub(r"(\d+)px", lambda match: f"{max(1, round(int(match.group(1)) * factor)) if int(match.group(1)) else 0}px", self.base_css)
        # VTE applies its own font scale, so keep its CSS font size unscaled.
        self.css.load_from_data((css + "\nterminal { font-size: 13px; }").encode("utf-8"))

    def scale_layout(self, widget):
        """Scale application-defined spacing from its original, unscaled values."""
        properties = ["margin-start", "margin-end", "margin-top", "margin-bottom"]
        if isinstance(widget, Gtk.Box):
            properties.append("spacing")
        if isinstance(widget, Gtk.Grid) and widget is not getattr(self, "grid", None):
            properties.extend(("row-spacing", "column-spacing"))
        if not hasattr(widget, "_scale_baseline"):
            widget._scale_baseline = {name: widget.get_property(name) for name in properties}
        for name, value in widget._scale_baseline.items():
            widget.set_property(name, round(value * self.scale / 100))
        if isinstance(widget, Gtk.Container):
            for child in widget.get_children():
                self.scale_layout(child)

    def change_scale(self, spin):
        self.scale = spin.get_value_as_int()
        self.data["ui_scale"] = self.scale
        self.apply_scale_css()
        factor = self.scale / 100
        self.sidebar.set_size_request(round(250 * factor), -1)
        self.scale_layout(self)
        for session in self.sessions:
            session["terminal"].set_font_scale(factor)
        self.persist()

    def rebuild_view(self, selected=None):
        if selected is None and not self.tiled:
            selected = self.tabs.get_nth_page(self.tabs.get_current_page())
        # Keep each terminal alive while moving it between containers.
        while self.tabs.get_n_pages():
            self.tabs.remove_page(0)
        for session in self.sessions:
            for widget in (session["terminal"], session["label"]):
                parent = widget.get_parent()
                if parent:
                    parent.remove(widget)
        for child in self.grid.get_children():
            self.grid.remove(child)
        for session in self.sessions:
            session["terminal"]._tile_frame = None
        self.count_label.set_text(f"{len(self.sessions)} / 8")
        if not self.sessions:
            self.views.set_visible_child_name("empty")
            return
        if self.tiled:
            columns = len(self.sessions) if len(self.sessions) <= 4 else (len(self.sessions) + 1) // 2
            for index, session in enumerate(self.sessions):
                frame = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
                frame.get_style_context().add_class("tile")
                session["terminal"]._tile_frame = frame
                if session["terminal"].has_focus():
                    frame.get_style_context().add_class("tile-focused")
                frame.set_hexpand(True)
                frame.set_vexpand(True)
                header = Gtk.Box()
                header.get_style_context().add_class("tile-header")
                header.pack_start(session["label"], True, True, 0)
                frame.pack_start(header, False, False, 0)
                frame.pack_start(session["terminal"], True, True, 0)
                self.grid.attach(frame, index % columns, index // columns, 1, 1)
            self.grid.show_all()
            self.views.set_visible_child_name("grid")
        else:
            for session in self.sessions:
                # Notebook tab labels are not shown by show_all() on the notebook.
                session["label"].show_all()
                self.tabs.append_page(session["terminal"], session["label"])
            self.tabs.show_all()
            self.views.set_visible_child_name("tabs")
            if selected:
                index = self.tabs.page_num(selected)
                if index >= 0:
                    self.tabs.set_current_page(index)

    def save_settings(self, *_):
        self.data["auto_enter"] = self.auto_enter.get_active()
        self.data["auto_enter_delay"] = self.delay.get_value_as_int()
        self.persist()

    def message(self, title, detail):
        dialog = Gtk.MessageDialog(transient_for=self, modal=True, message_type=Gtk.MessageType.ERROR, buttons=Gtk.ButtonsType.CLOSE, text=title)
        dialog.format_secondary_text(detail)
        dialog.run()
        dialog.destroy()

    def refresh(self):
        selected = self.hosts.get_active()
        self.refreshing = True
        self.hosts.remove_all()
        for host in self.data["hosts"]:
            self.hosts.append_text(host["name"])
        if self.data["hosts"]:
            self.hosts.set_active(min(max(selected, 0), len(self.data["hosts"]) - 1))
        self.refreshing = False
        self.refresh_host_content()

    def host_changed(self, *_):
        if not self.refreshing:
            self.refresh_host_content()

    def agent_changed(self, *_):
        host = self.current_host()
        if not self.refreshing and host and self.agents.get_active() >= 0:
            host["selected_agent"] = self.agents.get_active()
            self.persist()

    def host_agents(self, host):
        return host.setdefault("agents", [dict(agent) for agent in self.data["agents"]])

    def refresh_host_content(self):
        host = self.current_host()
        selected = self.hosts.get_active()
        for child in self.hosts_list.get_children():
            self.hosts_list.remove(child)
        for index, item in enumerate(self.data["hosts"]):
            button = Gtk.Button(label=item["name"] + "  ·  " + item["address"])
            button.get_style_context().add_class("host-button")
            if index == selected:
                button.get_style_context().add_class("host-selected")
            button.connect("clicked", lambda _button, i=index: self.hosts.set_active(i))
            self.hosts_list.pack_start(button, False, False, 0)
        self.hosts_list.show_all()
        self.refreshing = True
        self.agents.remove_all()
        agents = self.host_agents(host) if host else []
        for agent in agents:
            self.agents.append_text(agent["name"])
        if agents:
            self.agents.set_active(min(max(host.get("selected_agent", 0), 0), len(agents) - 1))
        self.refreshing = False
        self.refresh_directories()

    def current_host(self):
        index = self.hosts.get_active()
        return self.data["hosts"][index] if index >= 0 else None

    def refresh_directories(self):
        self.directories.remove_all()
        for child in self.directories_list.get_children():
            self.directories_list.remove(child)
        host = self.current_host()
        directories = host.get("directories", []) if host else []
        for index, item in enumerate(directories):
            self.directories.append_text(item["name"])
            row = Gtk.Box(spacing=4)
            button = Gtk.Button(label="▸  " + item["name"])
            button.set_tooltip_text(item["path"] + "\n点击使用当前 Agent 打开")
            button.get_style_context().add_class("project-button")
            button.connect("clicked", self.open_directory, index)
            row.pack_start(button, True, True, 0)
            for label, action in [("✎", "edit"), ("×", "delete")]:
                edit = Gtk.Button(label=label)
                edit.set_tooltip_text("编辑工作区" if action == "edit" else "删除工作区")
                edit.get_style_context().add_class("manage-button")
                edit.connect("clicked", self.manage_directory, index, action)
                row.pack_start(edit, False, False, 0)
            self.directories_list.pack_start(row, False, False, 0)
            self.scale_layout(row)
        if not directories:
            hint = Gtk.Label(label="请先选择主机并添加工作区", xalign=0)
            hint.get_style_context().add_class("subtitle")
            self.directories_list.pack_start(hint, False, False, 0)
        self.directories_list.show_all()

    def open_directory(self, _button, index):
        self.directories.set_active(index)
        self.open_terminal(None)

    def manage_directory(self, button, index, action):
        self.directories.set_active(index)
        self.manage(button, "directories", action)

    def persist(self):
        try:
            save_config(self.data)
        except OSError as exc:
            self.message("保存失败", str(exc))

    def manage(self, _button, kind, action):
        host = self.current_host()
        if kind in ("directories", "agents") and not host:
            self.message("请先选择主机", "请先添加并选择主机。")
            return
        items = host.setdefault("directories", []) if kind == "directories" else self.host_agents(host) if kind == "agents" else self.data[kind]
        index = getattr(self, kind).get_active()
        if action != "add" and index < 0:
            return
        if action == "delete":
            dialog = Gtk.MessageDialog(transient_for=self, modal=True, message_type=Gtk.MessageType.QUESTION, buttons=Gtk.ButtonsType.YES_NO, text="删除「%s」？" % items[index]["name"])
            result = dialog.run()
            dialog.destroy()
            if result == Gtk.ResponseType.YES:
                items.pop(index)
                if kind == "agents":
                    host["selected_agent"] = min(index, max(0, len(items) - 1))
                self.persist()
                self.refresh()
                if kind == "hosts":
                    self.refresh_all_shared()
            return
        fields = {"hosts": [("name", "名称"), ("address", "地址"), ("port", "端口"), ("username", "用户名"), ("sync_url", "同步服务地址（可选）"), ("sync_token", "同步令牌（可选）")], "directories": [("name", "名称"), ("path", "远端绝对路径")], "agents": [("name", "名称"), ("command", "启动命令")]}[kind]
        old = items[index] if action == "edit" else {"port": 22}
        dialog = Gtk.Dialog(title="编辑" if action == "edit" else "添加", transient_for=self, modal=True)
        dialog.add_buttons("取消", Gtk.ResponseType.CANCEL, "保存", Gtk.ResponseType.OK)
        grid = Gtk.Grid(column_spacing=12, row_spacing=8, margin=16)
        dialog.get_content_area().add(grid)
        entries = {}
        for row, (key, label) in enumerate(fields):
            grid.attach(Gtk.Label(label=label, xalign=0), 0, row, 1, 1)
            entry = Gtk.Entry(text=str(old.get(key, "")))
            if key == "sync_token":
                entry.set_visibility(False)
            grid.attach(entry, 1, row, 1, 1)
            entries[key] = entry
        if kind == "directories":
            browse = Gtk.Button(label="浏览远端目录…")
            browse.connect("clicked", lambda *_: self.browse_directories(host, entries["path"], dialog))
            grid.attach(browse, 1, len(fields), 1, 1)
        self.scale_layout(dialog)
        dialog.show_all()
        while dialog.run() == Gtk.ResponseType.OK:
            values = {key: entry.get_text().strip() for key, entry in entries.items()}
            try:
                if not all(value for key, value in values.items() if key not in ("sync_url", "sync_token")):
                    raise ValueError("所有字段均为必填项")
                if kind == "hosts":
                    values["port"] = int(values["port"])
                    if not 1 <= values["port"] <= 65535 or values["address"].startswith("-"):
                        raise ValueError("主机地址或端口无效")
                    values["directories"] = old.get("directories", [])
                    values["agents"] = old.get("agents", [dict(agent) for agent in self.data["agents"]])
                    values["selected_agent"] = old.get("selected_agent", 0)
                if kind == "directories" and not values["path"].startswith("/"):
                    raise ValueError("请填写远端绝对路径")
            except ValueError as exc:
                self.message("输入无效", str(exc))
                continue
            if action == "edit":
                items[index] = values
            else:
                items.append(values)
                if kind == "agents":
                    host["selected_agent"] = len(items) - 1
            self.persist()
            self.refresh()
            if kind == "hosts":
                self.refresh_all_shared()
            if kind == "hosts" and action == "add":
                self.hosts.set_active(len(items) - 1)
            break
        dialog.destroy()

    def browse_directories(self, host, target, parent):
        dialog = Gtk.Dialog(title="浏览远端目录", transient_for=parent, modal=True)
        dialog.add_buttons("取消", Gtk.ResponseType.CANCEL, "选择当前目录", Gtk.ResponseType.OK)
        box = dialog.get_content_area()
        box.set_spacing(6)
        box.set_border_width(10)
        path = Gtk.Entry(text=target.get_text())
        box.add(path)
        row = Gtk.Box(spacing=5)
        up = Gtk.Button(label="上一级")
        load = Gtk.Button(label="打开路径")
        row.pack_start(up, False, False, 0)
        row.pack_start(load, False, False, 0)
        box.add(row)
        status = Gtk.Label(label="", xalign=0)
        box.add(status)
        listing = Gtk.ListBox()
        scroll = Gtk.ScrolledWindow()
        scroll.set_min_content_height(300)
        scroll.set_min_content_width(450)
        scroll.add(listing)
        box.add(scroll)
        state = {"generation": 0, "alive": True, "loaded": None}
        def fetch(requested):
            state["generation"] += 1
            generation = state["generation"]
            state["loaded"] = None
            status.set_text("正在连接并读取目录…")
            dialog.set_response_sensitive(Gtk.ResponseType.OK, False)
            def worker():
                # The first NUL-delimited item is canonical cwd, followed by directory names.
                command = "cd -- " + (shlex.quote(requested) if requested else '"$HOME"') + " && printf '%s\\0' \"$PWD\" && find -L . -mindepth 1 -maxdepth 1 -type d -printf '%f\\0'"
                argv = ["ssh", "-o", "ConnectTimeout=20", "-p", str(host["port"]), "-l", host["username"], "--", host["address"], command]
                env = os.environ.copy()
                env.update(SSH_ASKPASS=str(Path(__file__).with_name("askpass.py")), SSH_ASKPASS_REQUIRE="force")
                try:
                    proc = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=60, start_new_session=True)
                    if proc.returncode:
                        raise RuntimeError(proc.stderr.decode(errors="replace").strip() or "SSH 连接失败")
                    parts = proc.stdout.decode("utf-8", errors="replace").split("\0")
                    if not parts[0].startswith("/"):
                        raise RuntimeError("远端返回的目录无效")
                    result, error = (parts[0], sorted((x for x in parts[1:] if x), key=str.casefold)), None
                except (OSError, subprocess.TimeoutExpired, RuntimeError) as exc:
                    result, error = None, str(exc)
                def finish():
                    if not state["alive"] or generation != state["generation"]:
                        return False
                    if error:
                        status.set_text(error)
                        return False
                    current, names = result
                    state["loaded"] = current
                    path.set_text(current)
                    for child in listing.get_children():
                        listing.remove(child)
                    for name in names:
                        item = Gtk.ListBoxRow()
                        item.add(Gtk.Label(label=name, xalign=0, margin=round(6 * self.scale / 100)))
                        item.directory_name = name
                        listing.add(item)
                    listing.show_all()
                    status.set_text("%d 个目录" % len(names))
                    dialog.set_response_sensitive(Gtk.ResponseType.OK, True)
                    return False
                GLib.idle_add(finish)
            threading.Thread(target=worker, daemon=True).start()
        load.connect("clicked", lambda *_: fetch(path.get_text().strip()))
        path.connect("activate", lambda *_: fetch(path.get_text().strip()))
        up.connect("clicked", lambda *_: fetch(os.path.dirname(path.get_text().rstrip("/")) or "/"))
        listing.connect("row-activated", lambda _list, row: fetch(state["loaded"].rstrip("/") + "/" + row.directory_name))
        self.scale_layout(dialog)
        dialog.show_all()
        fetch(path.get_text().strip())
        if dialog.run() == Gtk.ResponseType.OK and state["loaded"]:
            target.set_text(state["loaded"])
        state["alive"] = False
        dialog.destroy()

    def send_image_dialog(self, host, socket_path, terminal, state):
        if not state["connected"] or state["closed"]:
            self.message("无法发送图片", "请先等待该终端连接成功。")
            return
        dialog = Gtk.Dialog(title="发送图片给 Agent", transient_for=self, modal=True)
        dialog.add_buttons("取消", Gtk.ResponseType.CANCEL, "上传并发送", Gtk.ResponseType.OK)
        box = dialog.get_content_area()
        box.set_spacing(10)
        box.set_border_width(16)
        box.add(Gtk.Label(label="选择图片或粘贴剪贴板中的图片（Ctrl+V）。", xalign=0))
        box.add(Gtk.Label(label="路径会发送到此终端并提交，请确认 Agent 正在等待输入且输入框为空。", xalign=0, wrap=True))
        preview = Gtk.Image()
        box.add(preview)
        status = Gtk.Label(label="尚未选择图片", xalign=0, wrap=True)
        box.add(status)
        buttons = Gtk.Box(spacing=8)
        choose = Gtk.Button(label="选择本地图片…")
        paste = Gtk.Button(label="粘贴图片")
        buttons.pack_start(choose, False, False, 0)
        buttons.pack_start(paste, False, False, 0)
        box.add(buttons)
        selected = {"image": None, "alive": True, "busy": False}
        dialog.set_response_sensitive(Gtk.ResponseType.OK, False)

        def set_image(pixbuf, description):
            if pixbuf is None:
                selected["image"] = None
                preview.clear()
                dialog.set_response_sensitive(Gtk.ResponseType.OK, False)
                status.set_text("没有找到可用的图片。")
                return
            try:
                width, height = pixbuf.get_width(), pixbuf.get_height()
                if width * height > 40_000_000:
                    raise ValueError("图片尺寸过大（最多 4000 万像素）")
                ok, data = pixbuf.save_to_bufferv("png", [], [])
                if not ok or not data or len(data) > MAX_IMAGE_BYTES:
                    raise ValueError("转换后的图片为空或超过 20 MB")
                selected["image"] = data
                factor = min(1.0, 360 / width, 200 / height)
                preview.set_from_pixbuf(pixbuf.scale_simple(max(1, int(width * factor)), max(1, int(height * factor)), GdkPixbuf.InterpType.BILINEAR))
                status.set_text(f"{description} · {width} × {height}")
                dialog.set_response_sensitive(Gtk.ResponseType.OK, True)
            except (GLib.Error, ValueError) as exc:
                selected["image"] = None
                dialog.set_response_sensitive(Gtk.ResponseType.OK, False)
                status.set_text(str(exc))

        def choose_image(*_):
            chooser = Gtk.FileChooserDialog(title="选择图片", transient_for=dialog, action=Gtk.FileChooserAction.OPEN)
            chooser.add_buttons("取消", Gtk.ResponseType.CANCEL, "选择", Gtk.ResponseType.OK)
            image_filter = Gtk.FileFilter()
            image_filter.set_name("图片")
            image_filter.add_pixbuf_formats()
            chooser.add_filter(image_filter)
            if chooser.run() == Gtk.ResponseType.OK:
                path = chooser.get_filename()
                try:
                    if os.path.getsize(path) > MAX_IMAGE_BYTES:
                        raise ValueError("文件超过 20 MB")
                    info = GdkPixbuf.Pixbuf.get_file_info(path)
                    if not info or info[0] is None or info[1] * info[2] > 40_000_000:
                        raise ValueError("图片格式无效或尺寸过大")
                    set_image(GdkPixbuf.Pixbuf.new_from_file(path), os.path.basename(path))
                except (OSError, GLib.Error, ValueError) as exc:
                    selected["image"] = None
                    dialog.set_response_sensitive(Gtk.ResponseType.OK, False)
                    status.set_text(str(exc))
            chooser.destroy()

        def paste_image(*_):
            set_image(Gtk.Clipboard.get(Gdk.SELECTION_CLIPBOARD).wait_for_image(), "剪贴板图片")

        def on_key(_dialog, event):
            if event.state & Gdk.ModifierType.CONTROL_MASK and event.keyval in (Gdk.KEY_v, Gdk.KEY_V):
                if not selected["busy"]:
                    paste_image()
                return True
            return False

        def on_response(_dialog, response):
            if response != Gtk.ResponseType.OK:
                selected["alive"] = False
                dialog.destroy()
                return
            if selected["busy"] or selected["image"] is None:
                return
            if state["closed"] or not state["connected"]:
                status.set_text("终端已断开，请重新连接后再发送。")
                return
            selected["busy"] = True
            image, generation = selected["image"], state["generation"]
            dialog.set_response_sensitive(Gtk.ResponseType.OK, False)
            choose.set_sensitive(False)
            paste.set_sensitive(False)
            status.set_text("正在上传图片…")
            def worker():
                try:
                    path, error = upload_image(host, socket_path, image), None
                except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
                    path, error = None, str(exc)
                def finish():
                    if not selected["alive"]:
                        return False
                    selected["busy"] = False
                    choose.set_sensitive(True)
                    paste.set_sensitive(True)
                    dialog.set_response_sensitive(Gtk.ResponseType.OK, True)
                    if error:
                        status.set_text("上传失败：" + error)
                    elif state["closed"] or not state["connected"] or state["generation"] != generation:
                        status.set_text("图片已上传，但终端连接已变化，未发送路径：" + path)
                    else:
                        terminal.paste_text("请查看这张图片：" + path)
                        terminal.feed_child(b"\r")
                        selected["alive"] = False
                        dialog.destroy()
                        terminal.grab_focus()
                    return False
                GLib.idle_add(finish)
            threading.Thread(target=worker, daemon=True).start()

        choose.connect("clicked", choose_image)
        paste.connect("clicked", paste_image)
        dialog.connect("key-press-event", on_key)
        dialog.connect("response", on_response)
        dialog.connect("destroy", lambda *_: selected.update(alive=False))
        self.scale_layout(dialog)
        dialog.show_all()

    @staticmethod
    def shared_host_key(host):
        return (host.get("address"), host.get("port"), host.get("username"), host.get("sync_url"))

    def render_shared(self):
        for child in self.shared_list.get_children():
            self.shared_list.remove(child)
        for host in self.data["hosts"]:
            entry = self.shared_by_host.get(self.shared_host_key(host))
            if not entry:
                continue
            if isinstance(entry, str):
                label = Gtk.Label(label=host["name"] + " · " + entry, xalign=0)
                label.set_line_wrap(True)
                self.shared_list.pack_start(label, False, False, 0)
                continue
            for item in entry:
                button = Gtk.Button(label=host["name"] + " · " + item["display_name"])
                button.connect("clicked", lambda _button, h=host, i=item: self.open_terminal(None, h, i))
                self.shared_list.pack_start(button, False, False, 0)
        self.shared_list.show_all()

    def refresh_host_shared(self, host):
        if not host.get("sync_url"):
            return
        key = self.shared_host_key(host)
        snapshot = dict(host)
        def worker():
            try:
                result = sync_request(snapshot, "GET")["sessions"]
            except Exception:
                result = "同步失败，请检查地址和令牌"
            def finish():
                if any(self.shared_host_key(h) == key and h.get("sync_token") == snapshot.get("sync_token") for h in self.data["hosts"]):
                    self.shared_by_host[key] = result
                    self.render_shared()
                return False
            GLib.idle_add(finish)
        self.sync_executor.submit(worker)

    def refresh_all_shared(self):
        self.shared_by_host = {}
        for host in self.data["hosts"]:
            if host.get("sync_url"):
                self.shared_by_host[self.shared_host_key(host)] = "正在同步…"
                self.refresh_host_shared(host)
        self.render_shared()

    def sync_async(self, host, method, session_id, item=None):
        if not host.get("sync_url"):
            return
        def worker():
            try:
                sync_request(host, method, session_id, item)
                GLib.idle_add(self.refresh_host_shared, host)
            except Exception as exc:
                GLib.idle_add(self.message, "会话同步失败", str(exc))
        self.sync_executor.submit(worker)

    def show_shared_sessions(self, _button):
        host = self.current_host()
        if not host or not host.get("sync_url"):
            self.message("共享会话", "请先在主机设置中填写同步服务地址和令牌。")
            return
        def worker():
            try:
                items = sync_request(host, "GET")["sessions"]
            except Exception as exc:
                GLib.idle_add(self.message, "读取共享会话失败", str(exc))
                return
            def show():
                dialog = Gtk.Dialog(title="共享会话", transient_for=self, modal=True)
                dialog.add_button("关闭", Gtk.ResponseType.CLOSE)
                box = dialog.get_content_area()
                if not items:
                    box.add(Gtk.Label(label="暂无共享会话"))
                for item in items:
                    button = Gtk.Button(label=item["display_name"] + " · " + item["directory_name"])
                    def selected(_button, selected_item=item):
                        dialog.destroy()
                        self.open_terminal(None, host, selected_item)
                    button.connect("clicked", selected)
                    box.add(button)
                dialog.show_all()
                dialog.run()
                dialog.destroy()
                return False
            GLib.idle_add(show)
        self.sync_executor.submit(worker)

    def open_terminal(self, _button, shared_host=None, shared_item=None):
        host = shared_host or self.current_host()
        di, ai = self.directories.get_active(), self.agents.get_active()
        if not host or (shared_item is None and (di < 0 or ai < 0)):
            self.message("无法打开终端", "请选择主机、项目目录和命令预设。")
            return
        if shared_item:
            directory = {"name": shared_item["directory_name"], "path": shared_item["directory_path"]}
            agent = {"name": shared_item["agent_name"], "command": shared_item["agent_command"]}
        else:
            directory, agent = host["directories"][di], self.host_agents(host)[ai]
        key = (host["address"], host["port"], host["username"], directory["path"], agent["command"])
        if shared_item:
            key += (shared_item["id"],)
        existing = next((item for item in self.sessions if item["key"] == key or (shared_item and item["id"] == shared_item["id"])), None)
        if existing:
            terminal = existing["terminal"]
            if not self.tiled:
                self.tabs.set_current_page(self.tabs.page_num(terminal))
            terminal.grab_focus()
            return
        if len(self.sessions) >= 8:
            self.message("终端已达上限", "最多同时打开 8 个终端，请先关闭一个终端。")
            return
        socket_dir = tempfile.mkdtemp(prefix="rdt-")
        socket_path = os.path.join(socket_dir, "ssh")
        session_id = shared_item["id"] if shared_item else uuid.uuid4().hex
        argv, _ = ssh_argv(host, directory["path"], agent["command"], session_id=session_id, control_path=socket_path, restore_only=shared_item is not None)
        item = {"id": session_id, "directory_path": directory["path"], "directory_name": directory["name"],
                "agent_name": agent["name"], "agent_command": agent["command"], "display_name": directory["name"] + " · " + agent["name"]}
        if not shared_item:
            self.sync_async(host, "PUT", session_id, item)
        terminal = Vte.Terminal()
        def copy_selection(widget):
            if widget.get_has_selection():
                widget.copy_clipboard_format(Vte.Format.TEXT)
        terminal.connect("selection-changed", copy_selection)
        def update_tile_focus(widget, _event, focused):
            frame = getattr(widget, "_tile_frame", None)
            if frame:
                context = frame.get_style_context()
                (context.add_class if focused else context.remove_class)("tile-focused")
            return False
        terminal.connect("focus-in-event", update_tile_focus, True)
        terminal.connect("focus-out-event", update_tile_focus, False)
        terminal.set_font_scale(self.scale / 100)
        terminal.set_size(24, 8)
        terminal.set_hexpand(True)
        terminal.set_vexpand(True)
        terminal.set_scrollback_lines(10000)
        label = Gtk.Box(spacing=5)
        self.scale_layout(label)
        label.pack_start(Gtk.Label(label=directory["name"] + " · " + agent["name"]), True, True, 0)
        kind, quota_executable = quota_agent(agent["command"])
        quota = Gtk.Label(label=format_quota([]) if kind else "")
        label.pack_start(quota, False, False, 0)
        image_button = Gtk.Button.new_from_icon_name("image-x-generic-symbolic", Gtk.IconSize.MENU)
        image_button.set_relief(Gtk.ReliefStyle.NONE)
        image_button.set_tooltip_text("发送图片给此终端的 Agent")
        label.pack_start(image_button, False, False, 0)
        close = Gtk.Button.new_from_icon_name("window-close-symbolic", Gtk.IconSize.MENU)
        close.set_relief(Gtk.ReliefStyle.NONE)
        label.pack_start(close, False, False, 0)
        state = {"closed": False, "connected": False, "generation": 0, "retry": 0, "retry_source": None, "timer": None, "signature": None, "handled": None, "quota_busy": False, "connected_at": 0}
        image_button.connect("clicked", lambda *_: self.send_image_dialog(host, socket_path, terminal, state))
        def close_tab(*_):
            state["closed"] = True
            self.sync_async(host, "DELETE", session_id)
            if state["timer"]:
                GLib.source_remove(state["timer"])
            if state["retry_source"]:
                GLib.source_remove(state["retry_source"])
            if terminal.get_parent():
                parent = terminal.get_parent()
                if parent is self.tabs:
                    self.tabs.remove_page(self.tabs.page_num(terminal))
                else:
                    parent.remove(terminal)
            label_parent = label.get_parent()
            if label_parent:
                label_parent.remove(label)
            self.sessions[:] = [item for item in self.sessions if item["terminal"] is not terminal]
            self.rebuild_view()
            # Stop the multiplex master as well as the visible SSH client.
            subprocess.Popen(["ssh", "-S", socket_path, "-O", "exit", "-p", str(host["port"]), "-l", host["username"], "--", host["address"]], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            shutil.rmtree(socket_dir, ignore_errors=True)
        close.connect("clicked", close_tab)
        self.sessions.append({"terminal": terminal, "label": label, "key": key, "id": session_id})
        self.rebuild_view(selected=terminal)
        def spawned(_terminal, _pid, error, *_):
            if error:
                self.message("无法启动 SSH", str(error))
        reconnect = Gtk.Button(label="重连")
        reconnect.set_no_show_all(True)
        label.pack_start(reconnect, False, False, 0)
        label.reorder_child(close, -1)
        def read_quota():
            if state["closed"]:
                return False
            if state["quota_busy"] or not state["connected"]:
                return True
            if not kind:
                return False
            state["quota_busy"] = True
            def worker():
                result = []
                base = ["ssh", "-S", socket_path, "-o", "BatchMode=yes", "-o", "ConnectTimeout=5", "-p", str(host["port"]), "-l", host["username"], "--", host["address"]]
                try:
                    if kind == "agy":
                        output = subprocess.check_output(base + ["bash -lc 'agy -p \"/usage\" --output-format json'"], stdin=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
                        result = quota_from_agy(json.loads(output))
                    else:
                        remote = quota_remote_command(directory["path"], quota_executable, "app-server --listen stdio://")
                        proc = subprocess.Popen(base + [remote], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
                        try:
                            proc.stdin.write(b'{"id":1,"method":"initialize","params":{"clientInfo":{"name":"remote_dev_terminal","version":"1.0"}}}\n')
                            deadline = time.monotonic() + 15
                            pending = b""
                            while time.monotonic() < deadline and len(pending) < 1000000:
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
                                        msg = json.loads(line)
                                    except ValueError:
                                        continue
                                    if msg.get("id") == 1 and "error" not in msg:
                                        proc.stdin.write(b'{"method":"initialized","params":{}}\n{"id":2,"method":"account/rateLimits/read","params":{}}\n')
                                    elif msg.get("id") == 2:
                                        result = quota_from_codex(msg)
                                        break
                                if result:
                                    break
                        finally:
                            proc.kill()
                            proc.communicate()
                except (OSError, ValueError, TypeError, subprocess.SubprocessError):
                    pass
                def finish():
                    state["quota_busy"] = False
                    if not state["closed"] and state["connected"]:
                        quota.set_text(format_quota(result))
                    return False
                GLib.idle_add(finish)
            threading.Thread(target=worker, daemon=True).start()
            return True
        GLib.timeout_add_seconds(60, read_quota)
        def visible_signature():
            try:
                _, row = terminal.get_cursor_position()
                text, _ = terminal.get_text_range_format(Vte.Format.TEXT, max(0, row - 99), 0, row, terminal.get_column_count() - 1)
                return auto_enter_signature(text or "")
            except (TypeError, ValueError):
                return None
        def changed(*_):
            if not state["connected"] or not self.auto_enter.get_active():
                return
            signature = visible_signature()
            if signature != state["signature"]:
                if state["timer"]:
                    GLib.source_remove(state["timer"])
                    state["timer"] = None
                state["signature"] = signature
                if signature and signature != state["handled"]:
                    def confirm():
                        state["timer"] = None
                        if not state["closed"] and state["connected"] and self.auto_enter.get_active() and visible_signature() == signature:
                            state["handled"] = signature
                            terminal.feed_child(b"\r")
                        return False
                    state["timer"] = GLib.timeout_add_seconds(self.delay.get_value_as_int(), confirm)
                elif not signature:
                    state["handled"] = None
        terminal.connect("contents-changed", changed)
        def key_pressed(*_):
            if state["timer"]:
                GLib.source_remove(state["timer"])
                state["timer"] = None
            state["handled"] = state["signature"]
            return False
        terminal.connect("key-press-event", key_pressed)
        def start(*_):
            if state["closed"]:
                return False
            if state["retry_source"]:
                GLib.source_remove(state["retry_source"])
                state["retry_source"] = None
            reconnect.hide()
            state["generation"] += 1
            terminal.spawn_async(Vte.PtyFlags.DEFAULT, None, argv, None, GLib.SpawnFlags.SEARCH_PATH, None, None, -1, None, spawned, None)
            return False
        reconnect.connect("clicked", start)
        def child_exited(_terminal, status):
            state["connected"] = False
            quota.set_text(format_quota([]) if kind else "")
            if state["timer"]:
                GLib.source_remove(state["timer"])
                state["timer"] = None
            state["signature"] = None
            if state["closed"]:
                return
            code = os.waitstatus_to_exitcode(status)
            if code == 255:
                if state["connected_at"] and time.monotonic() - state["connected_at"] >= 30:
                    state["retry"] = 0
                state["connected_at"] = 0
                state["retry"] += 1
                seconds = min(15, 2 ** min(state["retry"] - 1, 4))
                reconnect.set_label(f"{seconds} 秒后重连")
                reconnect.show()
                def retry():
                    state["retry_source"] = None
                    return start()
                state["retry_source"] = GLib.timeout_add_seconds(seconds, retry)
            else:
                reconnect.set_label("重连")
                reconnect.show()
        terminal.connect("child-exited", child_exited)
        def check_connection():
            if state["closed"]:
                return False
            if not state["connected"] and os.path.exists(socket_path):
                state["connected"] = True
                state["connected_at"] = time.monotonic()
                read_quota()
                changed()
            return True
        GLib.timeout_add_seconds(1, check_connection)
        start()


if __name__ == "__main__":
    App().show_all()
    Gtk.main()
