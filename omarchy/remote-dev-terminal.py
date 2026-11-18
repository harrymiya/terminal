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
from gi.repository import Gtk, Vte, GLib, Gdk, GdkPixbuf, Pango
from core import load_config, save_config, ssh_argv, auto_enter_signature, AutoEnterController, quota_agent, quota_remote_command, quota_from_codex, quota_from_agy, format_quota, upload_image, MAX_IMAGE_BYTES, sync_request, sync_data_request, sync_host_key, legacy_sync_keys, ssh_address, ssh_port, ssh_host_fingerprint, sync_auth_request, save_sync_password, load_sync_password, clear_sync_password, render_battery_pixbuf, save_host_password, load_host_password, clear_host_password, ssh_auth_environment
from version import VERSION
from mcp_server import EmbeddedMCPServer


def make_quota_indicator():
    """Draw a compact battery using GTK widgets and readable percentage text."""
    box = Gtk.Box(spacing=3)
    battery = Gtk.Box(spacing=0)
    battery.set_valign(Gtk.Align.CENTER)
    level = Gtk.LevelBar.new_for_interval(0, 100)
    level.set_size_request(30, 12)
    level.set_valign(Gtk.Align.CENTER)
    level.get_style_context().add_class("quota-battery")
    cap = Gtk.Box()
    cap.set_size_request(3, 6)
    cap.set_valign(Gtk.Align.CENTER)
    cap.get_style_context().add_class("quota-battery-cap")
    battery.pack_start(level, False, False, 0)
    battery.pack_start(cap, False, False, 0)
    value = Gtk.Label(label="--")
    value.get_style_context().add_class("quota-value")
    box.pack_start(battery, False, False, 0)
    box.pack_start(value, False, False, 0)

    state = {"pct": None, "show_percent": True}

    def update(pct):
        pct = None if pct is None else max(0, min(100, pct))
        state["pct"] = pct
        level.set_value(pct if pct is not None else 0)
        value.set_text("--" if pct is None else f"{round(pct)}{'%' if state['show_percent'] else ''}")
        context = level.get_style_context()
        for name in ("quota-unknown", "quota-low", "quota-warning", "quota-ok"):
            context.remove_class(name)
        context.add_class("quota-unknown" if pct is None else "quota-low" if pct < 5 else "quota-warning" if pct < 20 else "quota-ok")
    def set_show_percent(show):
        state["show_percent"] = show
        update(state["pct"])

    box.set_show_percent = set_show_percent
    update(None)
    return box, update


class App(Gtk.Window):
    def __init__(self):
        super().__init__(title="Remote Dev Terminal")
        self.set_default_size(1200, 760)
        self.set_size_request(700, 450)
        self.sessions = []
        self.shared_by_host = {}
        self.sync_pending = set()
        self.workspace_versions = {}
        self.workspace_dirty = set()
        self.dirty_host_profiles = set()
        self.host_profile_sync_pending = False
        self.refresh_sessions_after_host_profiles = False
        self.workspace_sync_errors = set()
        self.sync_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="session-sync")
        try:
            self.data = load_config()
        except (OSError, ValueError) as exc:
            self.message("无法读取配置", str(exc))
            raise SystemExit(1)
        self.data.setdefault("auto_sync_enabled", False)
        self.data.setdefault("sync_interval_seconds", 5)
        self.dirty_host_profiles = set(self.data.get("dirty_host_profiles", []))
        self.tiled = self.data.get("tiled", True)
        self.scale = self.data.get("ui_scale", 100)
        if not isinstance(self.scale, int) or isinstance(self.scale, bool) or not 50 <= self.scale <= 200:
            self.scale = 100
        self.css = Gtk.CssProvider()
        self.base_css = '''
            * { font-size: 13px; }
            window { background: #0c1119; color: #e8eef6; }
            button, entry, spinbutton, combobox, combobox button, .menu-toggle, .host-button, .project-button, .manage-button {
                min-height: 24px;
                padding: 2px 8px;
            }
            button {
                background-image: none;
                background-color: #16222f;
                color: #d0e1ed;
                border: 1px solid #2b3e51;
                border-radius: 6px;
                padding: 2px 8px;
                box-shadow: none;
                min-height: 24px;
            }
            button:hover { background-image: none; background-color: #213548; border-color: #86dbb8; color: #ffffff; box-shadow: none; }
            button:active { background-image: none; background-color: #203e36; border-color: #86dbb8; color: #d8fff0; box-shadow: none; }
            entry {
                background-color: #16222f;
                color: #e8eef6;
                border: 1px solid #2b3e51;
                border-radius: 6px;
                padding: 2px 8px;
                min-height: 24px;
            }
            entry:focus { border-color: #86dbb8; }
            spinbutton {
                background-color: #16222f;
                color: #e8eef6;
                border: 1px solid #2b3e51;
                border-radius: 6px;
                padding: 0;
                min-height: 24px;
            }
            spinbutton entry {
                background-color: transparent;
                border: none;
                box-shadow: none;
                color: #e8eef6;
                min-height: 24px;
                padding: 2px 6px;
            }
            spinbutton button,
            spinbutton.horizontal button,
            spinbutton.horizontal > button,
            spinbutton button:hover,
            spinbutton button:active,
            spinbutton button:focus,
            spinbutton.horizontal > button:hover,
            spinbutton.horizontal > button:active {
                background-image: none;
                background-color: transparent;
                border: none;
                border-width: 0;
                border-style: none;
                border-color: transparent;
                box-shadow: none;
                color: #a6bacb;
                border-radius: 4px;
                padding: 0 4px;
                margin: 0;
                min-height: 11px;
            }
            spinbutton button:hover,
            spinbutton.horizontal button:hover,
            spinbutton.horizontal > button:hover {
                background-color: #213548;
                color: #86dbb8;
                border: none;
                border-width: 0;
                box-shadow: none;
            }
            spinbutton button:active,
            spinbutton.horizontal button:active,
            spinbutton.horizontal > button:active {
                background-color: #203e36;
                color: #d8fff0;
                border: none;
                border-width: 0;
                box-shadow: none;
            }
            combobox { min-height: 24px; padding: 0; }
            combobox button.combo, combobox button {
                background-image: none;
                background-color: #16222f;
                color: #d6e3ee;
                border: 1px solid #2b3e51;
                border-radius: 6px;
                padding: 2px 8px;
                box-shadow: none;
                min-height: 24px;
            }
            combobox button.combo:hover, combobox button:hover { background-image: none; background-color: #213548; border-color: #86dbb8; color: #ffffff; }
            combobox menu, combobox window.popup { background-color: #141f2b; color: #e8eef6; border: 1px solid #2b3e51; }
            combobox menu menuitem:hover { background-color: #203e36; color: #bdf5dd; }
            checkbutton { min-height: 24px; padding: 2px 0; }
            checkbutton check { background-image: none; background-color: #16222f; border: 1px solid #2b3e51; border-radius: 4px; box-shadow: none; min-width: 16px; min-height: 16px; }
            checkbutton check:checked { background-image: none; background-color: #203e36; border-color: #86dbb8; color: #86dbb8; }
            levelbar.quota-battery trough { background-image: none; background-color: #101823; border: 1px solid #8fa2b4; border-radius: 3px; padding: 1px; min-height: 8px; }
            levelbar.quota-battery block { border: none; border-radius: 1px; min-height: 6px; }
            levelbar.quota-battery block.empty { background-color: transparent; }
            levelbar.quota-battery.quota-ok block.filled { background-color: #86dbb8; }
            levelbar.quota-battery.quota-warning block.filled { background-color: #ff9e20; }
            levelbar.quota-battery.quota-low block.filled { background-color: #ff4d4d; }
            .quota-battery-cap { background-color: #8fa2b4; border-radius: 1px; }
            .quota-value { color: #e8eef6; font-size: 12px; font-weight: bold; }
            .quota-value:backdrop { color: #e8eef6; }
            notebook { background-color: #0c1119; border: none; }
            notebook > header { background-color: #101823; border-bottom: 1px solid #243546; min-height: 25px; padding: 0 4px; }
            notebook tab {
                background-image: none;
                background-color: #16222f;
                color: #8fa2b4;
                border: 1px solid #243546;
                border-bottom: none;
                border-radius: 0;
                padding: 0 12px;
                margin: 0 2px;
                box-shadow: none;
                min-height: 25px;
            }
            notebook tab:hover { background-image: none; background-color: #213548; color: #ffffff; }
            notebook tab:checked {
                background-image: none;
                background-color: #203e36;
                color: #86dbb8;
                border-color: #243546;
                box-shadow: none;
            }
            notebook tab:checked label { font-weight: bold; color: #86dbb8; }
            notebook tab:focus,
            notebook tab button:focus {
                outline-style: none;
                outline-width: 0;
                outline-color: transparent;
                box-shadow: none;
            }
            notebook tab button {
                background-image: none;
                background-color: transparent;
                border: none;
                box-shadow: none;
                min-height: 0;
                padding: 1px 3px;
                color: #8fa2b4;
            }
            notebook tab button:hover { background-image: none; background-color: rgba(255, 255, 255, 0.15); color: #ffffff; }
            .sidebar { background: #131b26; border-right: 1px solid #243546; }
            .sidebar button {
                background-image: none;
                background-color: #16222f;
                color: #d0e1ed;
                border: 1px solid #2b3e51;
                border-radius: 6px;
                box-shadow: none;
                min-height: 24px;
                padding: 2px 8px;
            }
            .sidebar button:hover { background-image: none; background-color: #213548; border-color: #86dbb8; color: #ffffff; box-shadow: none; }
            .sidebar button:active { background-image: none; background-color: #203e36; border-color: #86dbb8; color: #d8fff0; box-shadow: none; }
            .brand { font-size: 19px; font-weight: bold; color: #a2e8cc; }
            .subtitle { color: #8fa2b4; font-size: 11px; }
            .section { color: #9bb0c3; font-weight: bold; margin-top: 12px; }
            .toolbar { background: #101823; border-bottom: 1px solid #243546; padding: 6px 12px; }
            .menu-toggle {
                background-image: none;
                background-color: #16222f;
                color: #d0e1ed;
                border: 1px solid #2b3e51;
                border-radius: 6px;
                padding: 2px 10px;
                box-shadow: none;
                min-height: 24px;
            }
            .menu-toggle:hover { background-color: #213548; border-color: #86dbb8; color: #ffffff; }
            .menu-toggle:checked { background-color: #203e36; border-color: #86dbb8; color: #d8fff0; }
            .menu-toggle:checked:hover { background-color: #274b42; }
            .menu-toggle:focus { outline-color: #86dbb8; }
            .workspace-title { font-weight: bold; font-size: 15px; }
            .primary { background: #86dbb8; color: #101c1a; font-weight: bold; border: 0; }
            .tile-grid { background: #263647; }
            dialog treeview.user-table { background-color: #101823; color: #e8eef6; }
            dialog treeview.user-table:selected { background-color: #203e36; color: #d8fff0; }
            dialog treeview.user-table header button {
                background-image: none; background-color: #16222f; color: #9bb0c3;
                border: none; border-bottom: 1px solid #2b3e51; border-radius: 0;
                padding: 7px 10px; box-shadow: none; font-weight: bold;
            }
            .tile { background: #101823; }
            .tile-header { padding: 0 8px; background: #16222f; border-bottom: 1px solid #243546; min-height: 25px; }
            .running-outline { margin: 3px; }
            .tile-header button { background-image: none; background-color: transparent; border: none; box-shadow: none; min-height: 0; padding: 1px 3px; color: #8fa2b4; }
            .tile-header button:hover { background-image: none; background-color: rgba(255, 255, 255, 0.15); color: #ffffff; }
            .tile-focused .tile-header { background: #203e36; }
            paned.sidebar-paned > separator { background-color: #243546; min-width: 6px; }
            paned.sidebar-paned > separator:hover { background-color: #86dbb8; }
            paned.tile-paned > separator { background-color: #243546; margin: 0; padding: 0; }
            paned.tile-paned.horizontal > separator { min-width: 2px; }
            paned.tile-paned.vertical > separator { min-height: 2px; }
            paned.tile-paned > separator:hover, paned.tile-paned > separator:active { background-color: #86dbb8; }
            .empty { color: #8fa2b4; font-size: 15px; }
            .host-button {
                background-image: none;
                background-color: #16222f;
                border: 1px solid #2b3e51;
                border-radius: 6px;
                padding: 2px 8px;
                color: #d6e3ee;
                box-shadow: none;
                min-height: 24px;
            }
            .host-button:hover, .host-button.agent-selected { background-image: none; background-color: #213548; border-color: #86dbb8; color: #ffffff; }
            .host-selected { background-image: none; background-color: #203c34; border-color: #86dbb8; color: #d8fff0; font-weight: bold; }
            .project-button {
                background-image: none;
                background-color: #16222f;
                border: 1px solid #2b3e51;
                border-radius: 6px;
                padding: 2px 8px;
                color: #e2edf7;
                box-shadow: none;
                min-height: 24px;
            }
            .project-button:hover { background-image: none; background-color: #213548; border-color: #86dbb8; color: #ffffff; }
            .manage-button {
                background-image: none;
                background-color: #16222f;
                color: #b3c7d6;
                border: 1px solid #2b3e51;
                border-radius: 5px;
                padding: 2px 6px;
                font-weight: bold;
                box-shadow: none;
                min-height: 24px;
            }
            .manage-button:hover { background-image: none; background-color: #213548; border-color: #86dbb8; color: #ffffff; }
        '''
        self.apply_scale_css()
        Gtk.StyleContext.add_provider_for_screen(Gdk.Screen.get_default(), self.css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        root = Gtk.Paned(orientation=Gtk.Orientation.HORIZONTAL)
        root.get_style_context().add_class("sidebar-paned")
        self.sidebar_paned = root
        self.sidebar_width_timer = None
        self._edit_sidebar_width = None
        self._restoring_sidebar_width = False
        saved_width = self.data.get("sidebar_width")
        self.sidebar_width = saved_width if isinstance(saved_width, int) and 200 <= saved_width <= 2000 else self.sidebar_target_width() + 32
        self.add(root)
        self.sidebar_revealer = Gtk.Revealer(reveal_child=True)
        self.sidebar_revealer.set_transition_type(Gtk.RevealerTransitionType.SLIDE_RIGHT)
        root.pack1(self.sidebar_revealer, resize=False, shrink=True)
        sidebar_frame = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        sidebar_frame.get_style_context().add_class("sidebar")
        self.sidebar_revealer.add(sidebar_frame)
        sidebar_scroll = Gtk.ScrolledWindow()
        sidebar_scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        sidebar_frame.pack_start(sidebar_scroll, True, True, 0)
        sidebar = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin=16)
        self.sidebar = sidebar
        sidebar.set_size_request(self.sidebar_target_width(), -1)
        sidebar_scroll.add(sidebar)
        brand_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        brand = Gtk.Label(label="REMOTE DEV", xalign=0)
        brand.get_style_context().add_class("brand")
        brand_row.pack_start(brand, True, True, 0)
        self.edit_mode = False
        self.edit_actions = []
        self.edit_toggle = Gtk.ToggleButton(label="编辑")
        self.edit_toggle.set_tooltip_text("进入编辑模式，管理主机、Agent 和工作区")
        self.edit_toggle.connect("toggled", self.toggle_sidebar_edit)
        brand_row.pack_start(self.edit_toggle, False, False, 0)
        refresh_button = Gtk.Button()
        refresh_button.set_image(Gtk.Image.new_from_icon_name("view-refresh-symbolic", Gtk.IconSize.BUTTON))
        refresh_button.set_tooltip_text("立即同步主机、Agent、工作区和共享会话")
        refresh_button.connect("clicked", lambda *_: self.refresh_all_shared())
        brand_row.pack_start(refresh_button, False, False, 0)
        settings_button = Gtk.Button()
        settings_button.set_image(Gtk.Image.new_from_icon_name("emblem-system-symbolic", Gtk.IconSize.BUTTON))
        settings_button.set_tooltip_text("系统设置")
        settings_button.connect("clicked", self.show_settings)
        brand_row.pack_start(settings_button, False, False, 0)
        sidebar.pack_start(brand_row, False, False, 0)
        self.sidebar_title_min_width = brand_row.get_preferred_size()[0].width + sidebar.get_margin_start() + sidebar.get_margin_end()
        sidebar.set_size_request(max(self.sidebar_target_width(), self.sidebar_title_min_width), -1)
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
            items_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=5)
            setattr(self, attr + "_list", items_box)
            sidebar.pack_start(items_box, False, False, 0)
            buttons = Gtk.Box(spacing=4)
            if attr == "hosts":
                actions = [("＋主机", "add")]
            elif attr == "directories":
                actions = [("＋工作区", "add")]
            else:
                actions = [("＋Agent", "add")]
            for label, action in actions:
                button = Gtk.Button(label=label)
                button.get_child().set_single_line_mode(True)
                button.connect("clicked", self.manage, attr, action)
                buttons.pack_start(button, True, True, 0)
            buttons.show_all()
            buttons.set_no_show_all(True)
            buttons.hide()
            self.edit_actions.append(buttons)
            sidebar.pack_start(buttons, False, False, 0)
            if attr == "hosts":
                self.sync_status = Gtk.Label(xalign=0)
                self.sync_status.set_line_wrap(True)
                self.sync_status.get_style_context().add_class("subtitle")
                self.sync_status.set_tooltip_text("会话、Agent、工作区和主机状态按系统设置周期同步")
                sidebar.pack_start(self.sync_status, False, False, 0)
        self.hosts.connect("changed", self.host_changed)
        self.agents.connect("changed", self.agent_changed)
        self.auto_enter = Gtk.CheckButton(label="自动回车")
        self.auto_enter.set_active(self.data.get("auto_enter", False))
        self.auto_enter.connect("toggled", self.save_settings)
        self.delay = Gtk.SpinButton.new_with_range(1, 30, 1)
        self.delay.set_value(self.data.get("auto_enter_delay", 3))
        workspace = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        root.pack2(workspace, resize=True, shrink=False)
        root.set_position(self.sidebar_width)
        root.connect("notify::position", self.sidebar_width_changed)
        toolbar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        toolbar.get_style_context().add_class("toolbar")
        workspace.pack_start(toolbar, False, False, 0)
        menu = Gtk.ToggleButton()
        self.menu_toggle = menu
        menu.get_style_context().add_class("menu-toggle")
        menu.set_tooltip_text("显示或隐藏侧边栏")
        menu_content = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=7)
        menu_content.pack_start(Gtk.Image.new_from_icon_name("open-menu-symbolic", Gtk.IconSize.MENU), False, False, 0)
        menu_content.pack_start(Gtk.Label(label="菜单"), False, False, 0)
        menu.add(menu_content)
        menu.set_active(True)
        menu.connect("toggled", self.toggle_sidebar)
        toolbar.pack_start(menu, False, False, 0)
        title = Gtk.Label(label="工作区", xalign=0)
        title.get_style_context().add_class("workspace-title")
        toolbar.pack_start(title, True, True, 0)
        self.count_label = Gtk.Label(label="0 / 8")
        self.count_label.get_style_context().add_class("subtitle")
        toolbar.pack_start(self.count_label, False, False, 0)
        self.tile_layout = self.data.get("tile_layout", "grid")
        if self.tile_layout not in ("grid", "columns", "rows"):
            self.tile_layout = "grid"
        self.view_button = Gtk.ToggleButton()
        view_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        self.view_icon = Gtk.Image.new_from_icon_name(
            "view-paged-symbolic" if self.tiled else "view-grid-symbolic",
            Gtk.IconSize.MENU,
        )
        self.view_label = Gtk.Label(label="标签模式" if self.tiled else "平铺模式")
        view_box.pack_start(self.view_icon, False, False, 0)
        view_box.pack_start(self.view_label, False, False, 0)
        self.view_button.add(view_box)
        self.view_button.get_style_context().add_class("menu-toggle")
        self.view_button.set_tooltip_text("切换平铺视图与标签视图")
        self.view_button.set_active(self.tiled)
        self.view_button.connect("toggled", self.change_view)
        toolbar.pack_start(self.view_button, False, False, 0)
        layout_labels = {"grid": "⊞  切换布局 · 网格", "columns": "⬓  切换布局 · 左右", "rows": "⬒  切换布局 · 上下"}
        self.layout_button = Gtk.Button(label=layout_labels.get(self.tile_layout, "⊞  切换布局 · 网格"))
        self.layout_button.get_style_context().add_class("menu-toggle")
        self.layout_button.set_tooltip_text("点击切换平铺分栏布局（网格 / 左右分栏 / 上下分栏）")
        self.layout_button.connect("clicked", self.cycle_tile_layout)
        self.layout_button.set_no_show_all(True)
        if self.tiled:
            self.layout_button.show()
        else:
            self.layout_button.hide()
        toolbar.pack_start(self.layout_button, False, False, 0)
        zoom = Gtk.SpinButton.new_with_range(50, 200, 1)
        self.zoom_spin = zoom
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
        self.grid = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.grid.get_style_context().add_class("tile-grid")
        self.grid.set_hexpand(True)
        self.grid.set_vexpand(True)
        self.grid.connect("size-allocate", self._on_grid_allocate)
        self.views.add_named(self.grid, "grid")
        self.empty = Gtk.Label(label="选择左侧主机和 Agent，然后点击工作区直达终端。")
        self.empty.get_style_context().add_class("empty")
        self.views.add_named(self.empty, "empty")
        self.views.set_visible_child_name("empty")
        self.refresh()
        self.refresh_all_shared()
        self.sync_poll_source = None
        self.configure_sync_polling()
        GLib.timeout_add(16, self._animate_running_headers)
        self.scale_layout(self)
        self.mcp_server = None
        if os.environ.get("RDT_MCP_DISABLED") != "1" and self.data.get("mcp_enabled", True):
            self.mcp_server = EmbeddedMCPServer(self, {
                "platform": "omarchy",
                "version": VERSION,
                "max_image_bytes": MAX_IMAGE_BYTES,
                "askpass_path": Path(__file__).with_name("askpass.py"),
                "ssh_address": ssh_address,
                "ssh_port": ssh_port,
                "ssh_auth_environment": ssh_auth_environment,
                "save_host_password": save_host_password,
                "clear_host_password": clear_host_password,
                "upload_image": upload_image,
            })
            self.mcp_server.start()
        def on_destroy(*_):
            if self.mcp_server is not None:
                self.mcp_server.stop()
        self.connect("destroy", on_destroy)
        self.connect("destroy", Gtk.main_quit)

    def change_view(self, button):
        self.tiled = button.get_active()
        self.data["tiled"] = self.tiled
        self.view_icon.set_from_icon_name(
            "view-paged-symbolic" if self.tiled else "view-grid-symbolic",
            Gtk.IconSize.MENU
        )
        self.view_label.set_text("标签模式" if self.tiled else "平铺模式")
        if self.tiled:
            self.layout_button.show()
        else:
            self.layout_button.hide()
        self.persist()
        self.rebuild_view()

    def cycle_tile_layout(self, _button):
        layouts = ["grid", "columns", "rows"]
        idx = (layouts.index(self.tile_layout) + 1) % len(layouts)
        self.tile_layout = layouts[idx]
        self.data["tile_layout"] = self.tile_layout
        layout_labels = {"grid": "⊞  切换布局 · 网格", "columns": "⬓  切换布局 · 左右", "rows": "⬒  切换布局 · 上下"}
        self.layout_button.set_label(layout_labels.get(self.tile_layout, "⊞  切换布局 · 网格"))
        self.persist()
        self.rebuild_view()

    def toggle_sidebar(self, button):
        if button.get_active():
            self.sidebar_revealer.show()
            self.sidebar_revealer.set_reveal_child(True)
            self.sidebar_paned.set_position(self.sidebar_width)
        else:
            self.sidebar_revealer.set_reveal_child(False)
            self.sidebar_revealer.hide()
            self.sidebar_paned.set_position(0)

    def sidebar_width_changed(self, paned, _property):
        if self._restoring_sidebar_width or not self.sidebar_revealer.get_visible() or not paned.get_mapped():
            return
        width = paned.get_position()
        minimum = max(200, paned.get_property("min-position"))
        if width < minimum:
            paned.set_position(minimum)
            return
        self.sidebar_width = width
        if self.sidebar_width_timer:
            GLib.source_remove(self.sidebar_width_timer)
        def save_width():
            self.sidebar_width_timer = None
            self.data["sidebar_width"] = self.sidebar_width
            self.persist()
            return False
        self.sidebar_width_timer = GLib.timeout_add(500, save_width)

    def sidebar_target_width(self):
        if self.scale >= 100:
            return round(250 * self.scale / 100)
        # Prevent sidebar buttons and selectors from being squashed or clipped.
        return max(210, round(250 * self.scale / 100))

    def terminal_font_scale(self):
        if self.scale >= 100:
            return self.scale / 100.0
        # When UI is shrunk (< 100%), protect terminal text legibility:
        # Monospace terminal characters must stay clear, crisp, and readable.
        # Damped scaling from 1.0 down to a safe floor (0.88) at 50% scale.
        factor = self.scale / 100.0
        return max(0.88, 1.0 - (1.0 - factor) * 0.24)

    def apply_scale_css(self):
        factor = self.scale / 100.0
        def replace_decl(match):
            prop = match.group(1).strip()
            val_str = match.group(2)
            if factor >= 1.0:
                return f"{prop}:{re.sub(r'(\d+)px', lambda m: f'{round(int(m.group(1)) * factor)}px', val_str)}"

            # When scaling down (< 100%): protect text legibility and widget usability.
            if prop == "font-size":
                def scale_font(m):
                    orig = int(m.group(1))
                    font_factor = 1.0 - (1.0 - factor) * 0.35
                    floor = 13 if orig >= 15 else (12 if orig >= 13 else 11)
                    return f"{max(floor, round(orig * font_factor))}px"
                return f"{prop}:{re.sub(r'(\d+)px', scale_font, val_str)}"
            elif prop == "min-height":
                orig = int(re.search(r'\d+', val_str).group(0)) if re.search(r'\d+', val_str) else 24
                floor = 22 if orig >= 20 else 2
                return f"{prop}:{re.sub(r'(\d+)px', lambda m: f'{max(floor, round(int(m.group(1)) * factor))}px', val_str)}"
            elif prop == "min-width":
                orig = int(re.search(r'\d+', val_str).group(0)) if re.search(r'\d+', val_str) else 24
                floor = 22 if orig >= 20 else 2
                return f"{prop}:{re.sub(r'(\d+)px', lambda m: f'{max(floor, round(int(m.group(1)) * factor))}px', val_str)}"
            elif "padding" in prop:
                return f"{prop}:{re.sub(r'(\d+)px', lambda m: f'{max(1, round(int(m.group(1)) * factor)) if int(m.group(1)) else 0}px', val_str)}"
            elif "margin" in prop:
                return f"{prop}:{re.sub(r'(-?\d+)px', lambda m: f'{round(int(m.group(1)) * factor)}px', val_str)}"
            elif "border-radius" in prop:
                return f"{prop}:{re.sub(r'(\d+)px', lambda m: f'{max(3, round(int(m.group(1)) * factor))}px', val_str)}"
            else:
                return f"{prop}:{re.sub(r'(\d+)px', lambda m: f'{max(1, round(int(m.group(1)) * factor)) if int(m.group(1)) else 0}px', val_str)}"

        css = re.sub(r"([\w-]+)\s*:\s*([^;]+)(?=[;}])", replace_decl, self.base_css)
        # VTE applies its own font scale, so keep its CSS font size unscaled.
        self.css.load_from_data((css + "\nterminal { font-size: 13px; }").encode("utf-8"))

    @staticmethod
    def apply_hand_cursor(widget):
        """Configure clickable widgets to display a pointer (hand) cursor on hover."""
        if not isinstance(widget, Gtk.Widget):
            return widget

        def set_cursor_for_window(gdk_win, display):
            try:
                cursor = Gdk.Cursor.new_from_name(display, "pointer")
                if not cursor:
                    cursor = Gdk.Cursor.new(Gdk.CursorType.HAND2)
                gdk_win.set_cursor(cursor)
            except Exception:
                pass

        def on_realize(w):
            try:
                display = w.get_display() or Gdk.Display.get_default()
                if not display:
                    return

                # Gtk.Button (including ToggleButton and CheckButton)
                ew = getattr(w, "get_event_window", lambda: None)()
                if ew:
                    set_cursor_for_window(ew, display)
                    return

                # EventBox or widgets that manage their own GdkWindow
                if getattr(w, "get_has_window", lambda: False)():
                    win = w.get_window()
                    if win:
                        set_cursor_for_window(win, display)
                    return

                # Gtk.ComboBox / ComboBoxText
                if isinstance(w, Gtk.ComboBox):
                    parent_win = w.get_window()
                    if parent_win:
                        alloc = w.get_allocation()
                        for cw in parent_win.get_children():
                            geom = cw.get_geometry()
                            if (abs(geom.x - alloc.x) <= 2 and abs(geom.y - alloc.y) <= 2 and
                                    abs(geom.width - alloc.width) <= 2 and abs(geom.height - alloc.height) <= 2):
                                set_cursor_for_window(cw, display)
                                return

                # Gtk.SpinButton (up and down stepper arrows)
                if isinstance(w, Gtk.SpinButton):
                    win = w.get_window()
                    if win:
                        alloc = w.get_allocation()
                        for cw in win.get_children():
                            geom = cw.get_geometry()
                            if geom.x > alloc.width // 3:
                                set_cursor_for_window(cw, display)

                # Gtk.Notebook (tab header area)
                if isinstance(w, Gtk.Notebook):
                    win = w.get_window()
                    if win:
                        for cw in win.get_children():
                            set_cursor_for_window(cw, display)

            except Exception:
                pass

        is_clickable = isinstance(widget, (Gtk.Button, Gtk.ComboBox, Gtk.SpinButton, Gtk.Notebook, Gtk.EventBox))
        if is_clickable:
            if getattr(widget, "_hand_cursor_connected", False):
                if widget.get_realized():
                    on_realize(widget)
                return widget
            widget._hand_cursor_connected = True
            if widget.get_realized():
                on_realize(widget)
            else:
                widget.connect("realize", on_realize)
            if isinstance(widget, (Gtk.ComboBox, Gtk.SpinButton, Gtk.Notebook)):
                widget.connect("map", lambda w: on_realize(w))
                widget.connect("size-allocate", lambda w, _: on_realize(w) if w.get_realized() else None)

        return widget

    def scale_layout(self, widget):
        """Scale application-defined spacing and ensure clickable widgets show pointer cursor."""
        self.apply_hand_cursor(widget)
        properties = ["margin-start", "margin-end", "margin-top", "margin-bottom"]
        if isinstance(widget, Gtk.Box):
            properties.append("spacing")
        if isinstance(widget, Gtk.Grid) and widget is not getattr(self, "grid", None):
            properties.extend(("row-spacing", "column-spacing"))
        if not hasattr(widget, "_scale_baseline"):
            widget._scale_baseline = {name: widget.get_property(name) for name in properties}
        factor = self.scale / 100.0
        for name, value in widget._scale_baseline.items():
            if factor >= 1.0:
                widget.set_property(name, round(value * factor))
            else:
                min_val = 1 if value > 0 and "spacing" in name else 0
                widget.set_property(name, max(min_val, round(value * factor)))
        if isinstance(widget, Gtk.Container):
            for child in widget.get_children():
                self.scale_layout(child)

    def change_scale(self, spin):
        self.scale = spin.get_value_as_int()
        self.data["ui_scale"] = self.scale
        self.apply_scale_css()
        self.sidebar.set_size_request(max(self.sidebar_target_width(), getattr(self, "sidebar_title_min_width", 0)), -1)
        self.scale_layout(self)
        term_scale = self.terminal_font_scale()
        for session in self.sessions:
            session["terminal"].set_font_scale(term_scale)
        self.persist()

    def _on_grid_allocate(self, widget, alloc):
        if not getattr(widget, "_proportions_applied", False) and alloc.width > 100 and alloc.height > 100:
            widget._proportions_applied = True
            children = widget.get_children()
            if children and isinstance(children[0], Gtk.Paned):
                self.set_initial_positions(children[0], alloc.width, alloc.height)

    def build_split(self, widgets, orientation):
        if not widgets:
            return None
        if len(widgets) == 1:
            return widgets[0]
        mid = len(widgets) // 2
        left_list = widgets[:mid]
        right_list = widgets[mid:]
        paned = Gtk.Paned(orientation=orientation)
        paned.get_style_context().add_class("tile-paned")
        paned._split_orientation = orientation
        paned._left_weight = len(left_list)
        paned._right_weight = len(right_list)
        paned._left_child = self.build_split(left_list, orientation)
        paned._right_child = self.build_split(right_list, orientation)
        paned.pack1(paned._left_child, resize=True, shrink=True)
        paned.pack2(paned._right_child, resize=True, shrink=True)
        return paned

    def set_initial_positions(self, widget, w, h):
        if not isinstance(widget, Gtk.Paned):
            return
        total = w if widget._split_orientation == Gtk.Orientation.HORIZONTAL else h
        ratio = widget._left_weight / (widget._left_weight + widget._right_weight)
        pos = max(1, round(total * ratio))
        widget.set_position(pos)
        if widget._split_orientation == Gtk.Orientation.HORIZONTAL:
            self.set_initial_positions(widget._left_child, pos, h)
            self.set_initial_positions(widget._right_child, total - pos, h)
        else:
            self.set_initial_positions(widget._left_child, w, pos)
            self.set_initial_positions(widget._right_child, w, total - pos)

    def build_tile_layout(self, frames):
        n = len(frames)
        if n == 0:
            return None
        if n == 1:
            return frames[0]

        if self.tile_layout == "columns":
            rows = [frames]
        elif self.tile_layout == "rows":
            rows = [[f] for f in frames]
        else:  # "grid"
            if n == 2:
                rows = [frames]
            elif n in (3, 4):
                rows = [frames[:2], frames[2:]]
            elif n in (5, 6):
                rows = [frames[:3], frames[3:]]
            else:
                rows = [frames[:4], frames[4:]]

        row_splits = [self.build_split(r, Gtk.Orientation.HORIZONTAL) for r in rows]
        root = self.build_split(row_splits, Gtk.Orientation.VERTICAL)
        alloc = self.grid.get_allocation()
        total_w = alloc.width if alloc.width > 100 else 1200
        total_h = alloc.height if alloc.height > 100 else 760
        self.set_initial_positions(root, total_w, total_h)
        return root

    def _animate_running_headers(self):
        now = time.monotonic()
        for session in self.sessions:
            terminal = session.get("terminal")
            outline = getattr(terminal, "_running_outline", None)
            state = session.get("state")
            is_active = False
            if state:
                is_active = (now - state.get("last_output_at", 0.0) < 2.5) or state.get("quota_busy", False)
            if outline:
                if is_active and not outline.get_visible():
                    outline.show()
                elif not is_active and outline.get_visible():
                    outline.hide()
                if is_active:
                    outline.queue_draw()
        return True

    def rebuild_view(self, selected=None):
        if selected is None and not self.tiled:
            selected = self.tabs.get_nth_page(self.tabs.get_current_page())
        # Detach the reusable header before removing its notebook page: GTK
        # otherwise destroys the tab label and all of its child widgets.
        for session in self.sessions:
            label = session["label"]
            if label.get_parent():
                label.get_parent().remove(label)
        while self.tabs.get_n_pages():
            self.tabs.remove_page(0)
        for session in self.sessions:
            terminal = session["terminal"]
            if terminal.get_parent():
                terminal.get_parent().remove(terminal)
        for child in self.grid.get_children():
            self.grid.remove(child)
        for session in self.sessions:
            for indicator in session.get("quota_indicators", ()):
                indicator.set_show_percent(not self.tiled)
            session["terminal"]._tile_frame = None
            session["terminal"]._running_outline = None
        self.count_label.set_text(f"{len(self.sessions)} / 8")
        if not self.sessions:
            self.views.set_visible_child_name("empty")
            return
        if self.tiled:
            self.layout_button.show()
            self.layout_button.set_sensitive(len(self.sessions) >= 2)
            self.grid._proportions_applied = False
            frames = []
            for session in self.sessions:
                frame = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
                frame.get_style_context().add_class("tile")
                session["terminal"]._tile_frame = frame
                if session["terminal"].has_focus():
                    frame.get_style_context().add_class("tile-focused")
                frame.set_hexpand(True)
                frame.set_vexpand(True)
                header_container = Gtk.EventBox()
                header_container.get_style_context().add_class("tile-header")
                header_container.set_size_request(-1, 25)
                header = Gtk.Overlay()
                header_container.add(header)
                header_bar = Gtk.Box()
                header_bar.pack_start(session["label"], True, True, 0)
                header.add(header_bar)
                outline = Gtk.DrawingArea()
                outline.set_has_window(False)
                outline.set_can_focus(False)
                outline.set_no_show_all(True)
                outline.get_style_context().add_class("running-outline")
                outline.set_halign(Gtk.Align.FILL)
                outline.set_valign(Gtk.Align.FILL)
                outline.hide()
                outline.connect("draw", self.draw_running_outline)
                header.add_overlay(outline)
                header.set_overlay_pass_through(outline, True)
                session["terminal"]._running_outline = outline
                frame.pack_start(header_container, False, False, 0)
                frame.pack_start(session["terminal"], True, True, 0)
                frames.append(frame)
            tile_root = self.build_tile_layout(frames)
            if tile_root:
                self.grid.pack_start(tile_root, True, True, 0)
            self.grid.show_all()
            self.views.set_visible_child_name("grid")
        else:
            self.layout_button.hide()
            for session in self.sessions:
                tab_box = Gtk.Overlay()
                tab_box.set_size_request(-1, 25)
                tab_bar = Gtk.Box()
                tab_bar.pack_start(session["label"], True, True, 0)
                tab_box.add(tab_bar)
                outline = Gtk.DrawingArea()
                outline.set_has_window(False)
                outline.set_can_focus(False)
                outline.set_no_show_all(True)
                outline.get_style_context().add_class("running-outline")
                outline.set_halign(Gtk.Align.FILL)
                outline.set_valign(Gtk.Align.FILL)
                outline.hide()
                outline.connect("draw", self.draw_running_outline)
                tab_box.add_overlay(outline)
                tab_box.set_overlay_pass_through(outline, True)
                session["terminal"]._running_outline = outline
                tab_box.add_events(Gdk.EventMask.BUTTON_PRESS_MASK)
                tab_box.connect("button-press-event", self.activate_tab_from_label, session["terminal"])
                tab_box.show_all()
                self.tabs.append_page(session["terminal"], tab_box)
            self.tabs.show_all()
            self.views.set_visible_child_name("tabs")
            if selected:
                index = self.tabs.page_num(selected)
                if index >= 0:
                    self.tabs.set_current_page(index)

    def draw_running_outline(self, widget, cr):
        allocation = widget.get_allocation()
        width, height = allocation.width, allocation.height
        if width > 2 and height > 2:
            cr.set_source_rgb(0.12, 0.65, 1.0)
            cr.set_line_width(1.0)
            cr.set_dash([4.0, 4.0], -(time.monotonic() * 16.0 % 8.0))
            cr.rectangle(0.5, 0.5, width - 1.0, height - 1.0)
            cr.stroke()
        return False

    def activate_tab_from_label(self, _widget, event, terminal):
        if event.button == 1:
            page = self.tabs.page_num(terminal)
            if page >= 0:
                self.tabs.set_current_page(page)
        return False

    def save_settings(self, *_):
        self.data["auto_enter"] = self.auto_enter.get_active()
        self.data["auto_enter_delay"] = self.delay.get_value_as_int()
        self.persist()

    def manage_sync_users(self, parent=None):
        if not self.data.get("sync_is_admin"):
            return Gtk.Box() if parent is not None else None
        dialog = parent or Gtk.Dialog(title="用户维护", transient_for=self, modal=True)
        if parent is None:
            dialog.add_buttons("关闭", Gtk.ResponseType.CLOSE)
            dialog.set_default_size(760, 520)
            box = dialog.get_content_area()
        else:
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin=8)
            box.pack_start(Gtk.Label(label="用户维护", xalign=0), False, False, 0)
        rows_model = Gtk.ListStore(str, str, str)
        users_by_id = {}
        rows = Gtk.TreeView(model=rows_model)
        rows.set_headers_visible(True)
        rows.set_grid_lines(Gtk.TreeViewGridLines.HORIZONTAL)
        rows.set_enable_search(False)
        rows.get_selection().set_mode(Gtk.SelectionMode.SINGLE)
        rows.get_style_context().add_class("user-table")
        for title, column_index, expand in (("账号", 1, True), ("角色", 2, False)):
            renderer = Gtk.CellRendererText()
            renderer.set_padding(10, 7)
            column = Gtk.TreeViewColumn(title, renderer, text=column_index)
            column.set_expand(expand)
            column.set_clickable(False)
            rows.append_column(column)
        rows.set_hexpand(True)
        rows.set_vexpand(True)
        scroll = Gtk.ScrolledWindow()
        scroll.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.AUTOMATIC)
        scroll.set_min_content_height(190 if parent is not None else 240)
        scroll.add(rows)
        box.pack_start(scroll, True, True, 8)
        status = Gtk.Label(xalign=0)
        box.pack_start(status, False, False, 8)
        actions = Gtk.Box(spacing=8)
        add_button, edit_button, delete_button = (Gtk.Button(label=s) for s in ("添加用户", "编辑所选", "删除所选"))
        for button in (add_button, edit_button, delete_button): actions.pack_start(button, True, True, 0)
        box.pack_start(actions, False, False, 8)
        def selected_user():
            model, iterator = rows.get_selection().get_selected()
            return users_by_id.get(model.get_value(iterator, 0)) if iterator is not None else None
        def reload_users():
            rows_model.clear()
            users_by_id.clear()
            try:
                users = sync_data_request(self.data, "GET", "/v1/admin/users").get("users", [])
                for user in users:
                    user_id = str(user["id"])
                    users_by_id[user_id] = user
                    rows_model.append((user_id, user["username"], "管理员" if user.get("is_admin") else "普通用户"))
                rows.show_all()
                status.set_text("")
            except Exception as exc:
                status.set_text(str(exc))
        def edit_user(_button, user=None):
            selected = None if _button is add_button else user if user is not None else selected_user()
            if user is None and selected is None:
                status.set_text("请先选择一个用户。")
                return
            form = Gtk.Dialog(title="添加用户" if selected is None else "编辑用户", transient_for=dialog, modal=True)
            form.add_buttons("取消", Gtk.ResponseType.CANCEL, "保存", Gtk.ResponseType.OK)
            parent_width, parent_height = self.get_size()
            form_width = max(320, min(560, parent_width - 64))
            form_max_height = max(180, min(440, parent_height - 180))
            form_scroll = Gtk.ScrolledWindow()
            form_scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
            form_scroll.set_min_content_width(form_width - 32)
            form_scroll.set_max_content_height(form_max_height)
            form_scroll.set_propagate_natural_height(True)
            grid = Gtk.Grid(column_spacing=10, row_spacing=8, margin=16)
            grid.set_hexpand(True)
            form_scroll.add(grid)
            form.get_content_area().add(form_scroll)
            form.set_default_size(form_width, -1)
            form.set_resizable(True)
            name_entry = Gtk.Entry(text=selected.get("username", "") if selected else "")
            name_entry.set_hexpand(True)
            pass_entry = Gtk.Entry()
            pass_entry.set_hexpand(True)
            pass_entry.set_visibility(False)
            pass_entry.set_placeholder_text("留空保留当前密码" if selected else "至少 8 位")
            admin_check = Gtk.CheckButton(label="管理员")
            admin_check.set_active(bool(selected and selected.get("is_admin")))
            grid.attach(Gtk.Label(label="账号", xalign=0), 0, 0, 1, 1); grid.attach(name_entry, 1, 0, 1, 1)
            grid.attach(Gtk.Label(label="密码", xalign=0), 0, 1, 1, 1); grid.attach(pass_entry, 1, 1, 1, 1)
            grid.attach(admin_check, 1, 2, 1, 1)
            form.show_all()
            if form.run() == Gtk.ResponseType.OK:
                payload = {"username": name_entry.get_text().strip(), "password": pass_entry.get_text(), "is_admin": admin_check.get_active()}
                try:
                    if selected:
                        sync_data_request(self.data, "PUT", "/v1/admin/users/" + str(selected["id"]), payload)
                        if selected["username"] == self.data.get("sync_username"):
                            old_username = selected["username"]
                            remembered_password = load_sync_password(old_username) if self.data.get("sync_remember", True) else ""
                            password_to_remember = payload["password"] or remembered_password
                            self.data["sync_username"] = payload["username"]
                            self.persist()
                            if old_username != payload["username"] or payload["password"] or not self.data.get("sync_remember", True):
                                clear_sync_password(old_username)
                                if self.data.get("sync_remember", True) and password_to_remember:
                                    save_sync_password(payload["username"], password_to_remember)
                                self.persist()
                    else:
                        sync_data_request(self.data, "POST", "/v1/admin/users", payload)
                    reload_users()
                except Exception as exc:
                    status.set_text(str(exc))
            form.destroy()
        def delete_user(_button):
            selected = selected_user()
            if not selected:
                status.set_text("请先选择一个用户。")
                return
            confirm = Gtk.MessageDialog(transient_for=dialog, modal=True, message_type=Gtk.MessageType.QUESTION,
                buttons=Gtk.ButtonsType.YES_NO, text="删除账号「%s」？" % selected["username"])
            answer = confirm.run(); confirm.destroy()
            if answer == Gtk.ResponseType.YES:
                try:
                    sync_data_request(self.data, "DELETE", "/v1/admin/users/" + str(selected["id"]))
                    reload_users()
                except Exception as exc:
                    status.set_text(str(exc))
        add_button.connect("clicked", edit_user, None)
        edit_button.connect("clicked", edit_user)
        delete_button.connect("clicked", delete_user)
        reload_users()
        if parent is None:
            self.scale_layout(dialog)
            dialog.show_all()
            dialog.run()
            dialog.destroy()
        else:
            box.show_all()
        return box
    def show_settings(self, *_):
        dialog = Gtk.Dialog(title="系统设置", transient_for=self, modal=True)
        dialog.set_default_size(820, 760)
        dialog.add_buttons("取消", Gtk.ResponseType.CANCEL, "保存", Gtk.ResponseType.OK)
        grid = Gtk.Grid(column_spacing=10, row_spacing=9, margin=16)
        dialog.get_content_area().add(grid)
        url_entry = Gtk.Entry(text=self.data.get("sync_url", ""))
        grid.attach(Gtk.Label(label="同步服务地址", xalign=0), 0, 0, 1, 1)
        grid.attach(url_entry, 1, 0, 1, 1)
        grid.attach(Gtk.Label(label="同步账号", xalign=0), 0, 1, 1, 1)
        grid.attach(Gtk.Label(label=self.data.get("sync_username", "未登录"), xalign=0), 1, 1, 1, 1)
        auto_sync = Gtk.CheckButton(label="启用跨设备自动同步（本机设置）")
        auto_sync.set_active(bool(self.data.get("auto_sync_enabled", False)))
        grid.attach(auto_sync, 0, 2, 2, 1)
        sync_interval = Gtk.SpinButton.new_with_range(5, 300, 5)
        sync_interval.set_value(min(max(int(self.data.get("sync_interval_seconds", 5)), 5), 300))
        sync_interval_row = Gtk.Box(spacing=6)
        sync_interval_row.pack_start(Gtk.Label(label="自动同步周期"), False, False, 0)
        sync_interval_row.pack_start(sync_interval, False, False, 0)
        sync_interval_row.pack_start(Gtk.Label(label="秒（5–300）"), False, False, 0)
        grid.attach(sync_interval_row, 0, 3, 2, 1)
        grid.attach(self.auto_enter, 0, 4, 2, 1)
        delay_row = Gtk.Box(spacing=6)
        delay_row.pack_start(Gtk.Label(label="自动回车延迟"), False, False, 0)
        delay_row.pack_start(self.delay, False, False, 0)
        delay_row.pack_start(Gtk.Label(label="秒"), False, False, 0)
        grid.attach(delay_row, 0, 5, 2, 1)
        key_path = Path.home() / ".ssh" / "id_ed25519.pub"
        key_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        key_label = Gtk.Label(label=key_path.read_text(encoding="utf-8").strip() if key_path.exists() else "尚未生成本地 SSH 密钥", xalign=0)
        key_label.set_line_wrap(True)
        key_label.set_line_wrap_mode(Pango.WrapMode.WORD_CHAR)
        key_label.set_max_width_chars(40)
        key_box.pack_start(key_label, False, False, 0)
        key_buttons = Gtk.Box(spacing=6)
        generate = Gtk.Button(label="生成/读取公钥")
        copy = Gtk.Button(label="复制公钥")
        def read_key(*_):
            if not key_path.exists():
                key_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                result = subprocess.run(["ssh-keygen", "-t", "ed25519", "-N", "", "-f", str(key_path.with_suffix("")), "-C", "remote-dev-terminal"], capture_output=True, text=True)
                if result.returncode:
                    self.message("生成 SSH 密钥失败", result.stderr.strip() or "ssh-keygen 执行失败")
                    return
            key_label.set_text(key_path.read_text(encoding="utf-8").strip())
        def copy_key(*_):
            if not key_path.exists():
                read_key()
            if key_path.exists():
                Gtk.Clipboard.get(Gdk.SELECTION_CLIPBOARD).set_text(key_path.read_text(encoding="utf-8").strip(), -1)
                key_label.set_text("公钥已复制到剪贴板")
        generate.connect("clicked", read_key)
        copy.connect("clicked", copy_key)
        key_buttons.pack_start(generate, False, False, 0)
        key_buttons.pack_start(copy, False, False, 0)
        key_box.pack_start(key_buttons, False, False, 0)
        grid.attach(Gtk.Label(label="本机 SSH 公钥", xalign=0), 0, 6, 2, 1)
        grid.attach(key_box, 0, 7, 2, 1)
        if self.data.get("sync_is_admin"):
            grid.attach(self.manage_sync_users(dialog), 0, 8, 2, 2)
        self.scale_layout(dialog)
        dialog.show_all()
        if dialog.run() == Gtk.ResponseType.OK:
            new_url = url_entry.get_text().strip()
            if self.data.get("sync_url") != new_url: self.data["sync_token"] = ""
            self.data["sync_url"] = new_url
            self.data["auto_sync_enabled"] = auto_sync.get_active()
            self.data["sync_interval_seconds"] = sync_interval.get_value_as_int()
            self.save_settings()
            self.configure_sync_polling()
            self.refresh_all_shared()
        delay_row.remove(self.delay)
        grid.remove(self.auto_enter)
        dialog.destroy()

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
        self.update_sync_status()

    def host_changed(self, *_):
        if not self.refreshing:
            self.refresh_host_content()
            self.update_sync_status()

    def agent_changed(self, *_):
        host = self.current_host()
        if not self.refreshing and host and self.agents.get_active() >= 0:
            if host.get("selected_agent") != self.agents.get_active():
                host["selected_agent"] = self.agents.get_active()
                self.persist()
                self.refresh_agent_list()
                self.push_host_state(host)
                self.push_host_profile(host)

    def host_agents(self, host):
        return self.data["agents"]

    def refresh_host_content(self):
        host = self.current_host()
        selected = self.hosts.get_active()
        for child in self.hosts_list.get_children():
            self.hosts_list.remove(child)
        host_radio_group = None
        for index, item in enumerate(self.data["hosts"]):
            row = Gtk.Box(spacing=4)
            row.set_hexpand(True)
            active_address = ssh_address(item)
            active_port = item.get("_active_port", item.get("port", 0))
            has_lan = bool(item.get("lan_address"))
            has_ext = bool(item.get("external_address"))
            is_single = not has_lan or not has_ext or item.get("lan_address") == item.get("external_address")
            if is_single:
                route_desc = f"{active_address}:{active_port}" if active_address else (item.get("lan_address") or item.get("external_address") or item.get("address", ""))
                route = active_address or "在线"
            else:
                route = "内网" if active_address and active_address == item.get("lan_address") else "外网" if active_address else "未连接"
                route_desc = f"{route} {active_address or item.get('lan_address') or item.get('external_address') or item.get('address', '')}"
            button = Gtk.RadioButton.new_with_label_from_widget(host_radio_group, item["name"] + "  ·  " + route_desc)
            button.set_mode(False)
            if host_radio_group is None:
                host_radio_group = button
            button.set_active(index == selected)
            button.set_tooltip_text(f"{item.get('username', '')}@{active_address or item.get('lan_address') or item.get('external_address') or item.get('address', '')}:{active_port}\n当前状态：{route}\n点击切换主机")
            button.get_style_context().add_class("host-button")
            self.fit_sidebar_label(button)
            button.set_hexpand(True)
            if index == selected:
                button.get_style_context().add_class("host-selected")
            button.connect("toggled", lambda radio, i=index: self.hosts.set_active(i) if radio.get_active() else None)
            row.pack_start(button, True, True, 0)
            if self.edit_mode:
                actions = Gtk.Box(spacing=4)
                actions.set_halign(Gtk.Align.END)
                for label, action in [("✎", "edit"), ("×", "delete")]:
                    edit = Gtk.Button(label=label)
                    edit.set_tooltip_text("编辑主机" if action == "edit" else "删除主机")
                    edit.get_style_context().add_class("manage-button")
                    edit.connect("clicked", self.manage_host, index, action)
                    actions.pack_start(edit, False, False, 0)
                row.pack_start(actions, False, False, 0)
            self.hosts_list.pack_start(row, False, False, 0)
            self.scale_layout(row)
        if not self.data["hosts"]:
            hint = Gtk.Label(label="请点击顶部“编辑”后添加主机", xalign=0)
            hint.get_style_context().add_class("subtitle")
            self.hosts_list.pack_start(hint, False, False, 0)
        self.hosts_list.show_all()
        agents = self.host_agents(host) if host else []
        target_names = [agent["name"] for agent in agents]
        current_model = self.agents.get_model()
        current_names = [row[0] for row in current_model] if current_model else []
        target_active = min(max(host.get("selected_agent", 0), 0), len(agents) - 1) if agents else -1
        if current_names != target_names:
            self.refreshing = True
            self.agents.remove_all()
            for agent in agents:
                self.agents.append_text(agent["name"])
            if target_active >= 0:
                self.agents.set_active(target_active)
            self.refreshing = False
        elif self.agents.get_active() != target_active and target_active >= 0:
            self.refreshing = True
            self.agents.set_active(target_active)
            self.refreshing = False
        self.refresh_agent_list()
        self.refresh_directories()

    def refresh_agent_list(self):
        host = self.current_host()
        agents = self.host_agents(host) if host else []
        active_index = self.agents.get_active()
        children = self.agents_list.get_children()
        if len(children) == len(agents):
            matched = True
            for child, item in zip(children, agents):
                row_children = child.get_children() if isinstance(child, Gtk.Box) else []
                btn = row_children[0] if row_children else None
                has_manage_actions = len(row_children) > 1
                if (not isinstance(btn, Gtk.Button)
                        or btn.get_label() != item.get("name", "")
                        or has_manage_actions != self.edit_mode):
                    matched = False
                    break
            if matched:
                for index, child in enumerate(children):
                    btn = child.get_children()[0] if isinstance(child, Gtk.Box) and child.get_children() else None
                    if btn:
                        ctx = btn.get_style_context()
                        if index == active_index:
                            if not ctx.has_class("agent-selected"):
                                ctx.add_class("agent-selected")
                        else:
                            if ctx.has_class("agent-selected"):
                                ctx.remove_class("agent-selected")
                return
        for child in self.agents_list.get_children():
            self.agents_list.remove(child)
        for index, item in enumerate(agents):
            row = Gtk.Box(spacing=4)
            row.set_hexpand(True)
            button = Gtk.Button(label=item["name"])
            button.set_tooltip_text(item.get("command", "") + "\n点击选择 Agent")
            button.get_style_context().add_class("host-button")
            self.fit_sidebar_label(button)
            button.set_hexpand(True)
            if index == active_index:
                button.get_style_context().add_class("agent-selected")
            button.connect("clicked", lambda _button, i=index: self.agents.set_active(i))
            row.pack_start(button, True, True, 0)
            if self.edit_mode:
                actions = Gtk.Box(spacing=4)
                actions.set_halign(Gtk.Align.END)
                for label, action in [("✎", "edit"), ("×", "delete")]:
                    edit = Gtk.Button(label=label)
                    edit.set_tooltip_text("编辑 Agent" if action == "edit" else "删除 Agent")
                    edit.get_style_context().add_class("manage-button")
                    edit.connect("clicked", self.manage_agent, index, action)
                    actions.pack_start(edit, False, False, 0)
                row.pack_start(actions, False, False, 0)
            self.agents_list.pack_start(row, False, False, 0)
            self.scale_layout(row)
        if not agents:
            hint = Gtk.Label(label="请先选择主机，再点击顶部“编辑”添加 Agent", xalign=0)
            hint.get_style_context().add_class("subtitle")
            self.agents_list.pack_start(hint, False, False, 0)
        self.agents_list.show_all()

    def manage_agent(self, button, index, action):
        self.agents.set_active(index)
        self.manage(button, "agents", action)

    @staticmethod
    def fit_sidebar_label(button):
        label = button.get_child()
        if isinstance(label, Gtk.Label):
            label.set_xalign(0.0)
            label.set_single_line_mode(True)
            label.set_ellipsize(Pango.EllipsizeMode.END)
            label.set_width_chars(1)
            label.set_max_width_chars(14)

    def toggle_sidebar_edit(self, button):
        restore_width = None
        if button.get_active():
            if self.sidebar_width_timer:
                GLib.source_remove(self.sidebar_width_timer)
                self.sidebar_width_timer = None
            self._edit_sidebar_width = self.sidebar_paned.get_position()
        else:
            restore_width = self._edit_sidebar_width
            self._edit_sidebar_width = None
        self.edit_mode = button.get_active()
        button.set_label("完成" if self.edit_mode else "编辑")
        button.set_tooltip_text("退出编辑模式" if self.edit_mode else "进入编辑模式，管理主机、Agent 和工作区")
        for actions in self.edit_actions:
            actions.set_visible(self.edit_mode)
        self.refresh_host_content()
        if restore_width is not None:
            GLib.idle_add(self.restore_sidebar_width, restore_width)

    def restore_sidebar_width(self, width):
        if self.sidebar_width_timer:
            GLib.source_remove(self.sidebar_width_timer)
            self.sidebar_width_timer = None
        self._restoring_sidebar_width = True
        self.sidebar_paned.set_position(width)
        self.sidebar_width = width
        self.data["sidebar_width"] = width
        self.persist()
        GLib.timeout_add(700, self.finish_sidebar_width_restore)
        return False

    def finish_sidebar_width_restore(self):
        self._restoring_sidebar_width = False
        return False

    def manage_host(self, button, index, action):
        self.hosts.set_active(index)
        self.manage(button, "hosts", action)

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
            row.set_hexpand(True)
            button = Gtk.Button(label="▸  " + item["name"])
            self.fit_sidebar_label(button)
            button.set_hexpand(True)
            button.set_tooltip_text(item["path"] + "\n点击使用当前 Agent 打开")
            button.get_style_context().add_class("project-button")
            button.connect("clicked", self.open_directory, index)
            row.pack_start(button, True, True, 0)
            if self.edit_mode:
                actions = Gtk.Box(spacing=4)
                actions.set_halign(Gtk.Align.END)
                for symbol, offset, tooltip in (("↑", -1, "上移工作区"), ("↓", 1, "下移工作区")):
                    move = Gtk.Button(label=symbol)
                    move.set_tooltip_text(tooltip)
                    move.set_sensitive(0 <= index + offset < len(directories))
                    move.get_style_context().add_class("manage-button")
                    move.connect("clicked", self.move_directory, index, offset)
                    actions.pack_start(move, False, False, 0)
                for label, action in [("✎", "edit"), ("×", "delete")]:
                    edit = Gtk.Button(label=label)
                    edit.set_tooltip_text("编辑工作区" if action == "edit" else "删除工作区")
                    edit.get_style_context().add_class("manage-button")
                    edit.connect("clicked", self.manage_directory, index, action)
                    actions.pack_start(edit, False, False, 0)
                row.pack_start(actions, False, False, 0)
            self.directories_list.pack_start(row, False, False, 0)
            self.scale_layout(row)
        if not directories:
            hint = Gtk.Label(label="请先选择主机，再点击顶部“编辑”添加工作区", xalign=0)
            hint.get_style_context().add_class("subtitle")
            self.directories_list.pack_start(hint, False, False, 0)
        self.directories_list.show_all()

    def move_directory(self, _button, index, offset):
        host = self.current_host()
        directories = host.get("directories", []) if host else []
        target = index + offset
        if not (0 <= index < len(directories) and 0 <= target < len(directories)):
            return
        directories[index], directories[target] = directories[target], directories[index]
        self.persist()
        self.refresh_directories()
        self.directories.set_active(target)
        self.push_workspace_list(host)

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
                if kind == "hosts" and items[index].get("credential_id"):
                    try:
                        clear_host_password(items[index]["credential_id"])
                    except RuntimeError as exc:
                        self.message("删除密码失败", str(exc))
                        return
                if kind == "hosts": self.delete_host_profile(items[index].get("id", ""))
                items.pop(index)
                if kind == "agents":
                    host["selected_agent"] = min(index, max(0, len(items) - 1))
                self.persist()
                self.refresh()
                if kind == "directories":
                    self.push_workspace_list(host)
                elif kind == "agents":
                    self.push_agent_list()
                if kind == "hosts":
                    self.refresh_all_shared()
            return
        old = dict(items[index]) if action == "edit" else {}
        if kind == "hosts":
            if not old.get("lan_address") and not old.get("external_address") and old.get("address"):
                old["lan_address"] = old.get("address", "")
            old["lan_port"] = old.get("lan_port") or old.get("port", "")
            old["external_port"] = old.get("external_port") or old.get("port", "")
        fields = {"hosts": [("name", "名称"), ("lan_address", "内网地址"), ("lan_port", "内网端口（必填）"), ("external_address", "外网地址"), ("external_port", "外网端口（必填）"), ("username", "用户名")], "directories": [("name", "名称"), ("path", "远端绝对路径")], "agents": [("name", "名称"), ("command", "启动命令")]}[kind]
        dialog = Gtk.Dialog(title="编辑" if action == "edit" else "添加", transient_for=self, modal=True)
        dialog.add_buttons("取消", Gtk.ResponseType.CANCEL, "保存", Gtk.ResponseType.OK)
        parent_width, parent_height = self.get_size()
        form_width = max(320, min(560, parent_width - 64))
        form_max_height = max(180, min(440, parent_height - 180))
        form_scroll = Gtk.ScrolledWindow()
        form_scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        form_scroll.set_min_content_width(form_width - 32)
        form_scroll.set_max_content_height(form_max_height)
        form_scroll.set_propagate_natural_height(True)
        grid = Gtk.Grid(column_spacing=12, row_spacing=8, margin=16)
        grid.set_hexpand(True)
        form_scroll.add(grid)
        dialog.get_content_area().add(form_scroll)
        dialog.set_default_size(form_width, -1)
        dialog.set_resizable(True)
        entries = {}
        for row, (key, label) in enumerate(fields):
            grid.attach(Gtk.Label(label=label, xalign=0), 0, row, 1, 1)
            entry = Gtk.Entry(text=str(old.get(key, "")))
            if key in ("lan_port", "external_port"):
                entry.set_placeholder_text("端口号")
            entry.set_hexpand(True)
            grid.attach(entry, 1, row, 1, 1)
            entries[key] = entry
        password_entry = None
        clear_password = None
        if kind == "hosts":
            hint = Gtk.Label(label="优先连接内网地址；无法连接时自动切换外网地址。主机配置会按账号同步。", xalign=0, wrap=True)
            grid.attach(hint, 1, len(fields), 1, 1)
            row = len(fields) + 1
            grid.attach(Gtk.Label(label="SSH 密码", xalign=0), 0, row, 1, 1)
            password_entry = Gtk.Entry(text=load_host_password(old.get("credential_id")))
            password_entry.set_hexpand(True)
            password_entry.set_visibility(False)
            password_entry.set_placeholder_text("留空保留已保存密码" if old.get("credential_id") else "可留空，使用密钥或连接时输入")
            grid.attach(password_entry, 1, row, 1, 1)
            if old.get("credential_id"):
                clear_password = Gtk.CheckButton(label="删除已保存的密码")
                grid.attach(clear_password, 1, row + 1, 1, 1)
        if kind == "directories":
            browse = Gtk.Button(label="浏览远端目录…")
            browse.connect("clicked", lambda *_: self.browse_directories(host, entries["path"], dialog))
            grid.attach(browse, 1, len(fields), 1, 1)
        self.scale_layout(dialog)
        dialog.show_all()
        while dialog.run() == Gtk.ResponseType.OK:
            values = {key: entry.get_text().strip() for key, entry in entries.items()}
            try:
                required_fields = {"hosts": ("name", "username"), "directories": ("name", "path"), "agents": ("name", "command")}[kind]
                if any(not values.get(key) for key in required_fields) or (kind == "hosts" and not (values.get("lan_address") or values.get("external_address"))):
                    raise ValueError("请填写所有必填字段")
                if kind == "hosts":
                    primary = values.get("lan_address") or values.get("external_address")
                    parsed_ports = {}
                    for address_key, port_key in (("lan_address", "lan_port"), ("external_address", "external_port")):
                        if values.get(address_key):
                            try:
                                port = int(values[port_key])
                            except (TypeError, ValueError):
                                raise ValueError("每个已填写的主机地址都必须配置端口")
                            if not 1 <= port <= 65535:
                                raise ValueError("主机端口必须在 1 到 65535 之间")
                            parsed_ports[port_key] = port
                    if not parsed_ports:
                        raise ValueError("请至少配置一个有效的主机地址和端口")
                    values["lan_port"] = parsed_ports.get("lan_port", next(iter(parsed_ports.values())))
                    values["external_port"] = parsed_ports.get("external_port", next(iter(parsed_ports.values())))
                    values["port"] = values["lan_port"] if values.get("lan_address") else values["external_port"]
                    values["address"] = primary or ""
                    if any(v.startswith("-") for v in (values["lan_address"], values["external_address"])):
                        raise ValueError("主机地址无效")
                    values["id"] = old.get("id") or uuid.uuid4().hex
                    values["legacy_address"] = old.get("legacy_address", old.get("address", ""))
                    values["sync_id"] = old.get("sync_id", "")
                    values["directories"] = old.get("directories", [])
                    values["agents"] = old.get("agents", [dict(agent) for agent in self.data["agents"]])
                    values["selected_agent"] = old.get("selected_agent", 0)
                    if old.get("_sync_fingerprint"):
                        values["_sync_fingerprint"] = old["_sync_fingerprint"]
                    credential_id = old.get("credential_id")
                    password = password_entry.get_text()
                    if clear_password and clear_password.get_active():
                        if password:
                            raise ValueError("请输入新密码或选择删除密码，不能同时操作")
                        clear_host_password(credential_id)
                        credential_id = None
                    elif password:
                        credential_id = credential_id or uuid.uuid4().hex
                        save_host_password(credential_id, password)
                    if credential_id:
                        values["credential_id"] = credential_id
                if kind == "directories" and not values["path"].startswith("/"):
                    raise ValueError("请填写远端绝对路径")
            except (ValueError, RuntimeError) as exc:
                self.message("保存失败", str(exc))
                continue
            if action == "edit":
                items[index] = values
            else:
                items.append(values)
                if kind == "agents":
                    host["selected_agent"] = len(items) - 1
            self.persist()
            self.refresh()
            if kind == "directories":
                self.push_workspace_list(host)
            elif kind == "agents":
                self.push_agent_list()
            if kind == "hosts":
                target_host = items[index] if action == "edit" else items[-1]
                target_host.pop("_resolved", None)
                ssh_address(target_host, force=True)
                self.push_host_profile(target_host)
                self.push_host_state(target_host)
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
                address = ssh_address(host)
                argv = ["ssh", "-o", "ConnectTimeout=20", "-p", str(ssh_port(host)), "-l", host["username"], "--", address, command]
                env = ssh_auth_environment(host, Path(__file__).with_name("askpass.py"))
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
                        eb = Gtk.EventBox()
                        eb.add(Gtk.Label(label=name, xalign=0, margin=max(3, round(6 * self.scale / 100))))
                        self.apply_hand_cursor(eb)
                        item.add(eb)
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
        sync_id = str(host.get("sync_id", "")).strip()
        if sync_id:
            return ("manual", sync_id)
        return host.get("id") or (host.get("address"), host.get("port"), host.get("username"))

    def canonical_session_host(self, host):
        if not host:
            return host
        current = next((item for item in self.data.get("hosts", []) if item.get("id") == host.get("id")), None)
        if current:
            return current
        aliases = set(legacy_sync_keys(host))
        matches = [item for item in self.data.get("hosts", []) if aliases.intersection(legacy_sync_keys(item))]
        return matches[0] if len(matches) == 1 else host

    def update_sync_status(self):
        host = self.current_host()
        if not self.data.get("auto_sync_enabled", False):
            text = "自动同步：已关闭（本机设置）"
        elif not host or not self.data.get("sync_url"):
            text = "同步：未配置服务地址"
        else:
            result = self.shared_by_host.get(self.shared_host_key(host), "正在同步…")
            detail = f"{len(result)} 个共享会话" if isinstance(result, list) else str(result)
            text = f"自动同步 · {detail}"
        self.sync_status.set_text(text)

    def refresh_selected_shared(self, _button):
        if not self.data.get("auto_sync_enabled", False):
            self.message("自动同步已关闭", "请先在系统设置中启用本机的跨设备自动同步。")
            return
        host = self.current_host()
        if not host or not self.data.get("sync_url"):
            self.message("共享会话", "请先在系统设置中填写同步服务地址并登录账号。")
            return
        self.shared_by_host[self.shared_host_key(host)] = "正在同步…"
        self.update_sync_status()
        self.refresh_host_shared(host)

    def refresh_host_shared(self, host):
        if not self.data.get("sync_url") or not self.data.get("auto_sync_enabled", False):
            return
        host = self.canonical_session_host(host)
        key = self.shared_host_key(host)
        fingerprint = ssh_host_fingerprint(host)
        if fingerprint: host["_sync_fingerprint"] = fingerprint
        snapshot = dict(host)
        request_token = self.data.get("sync_token")
        pending_key = (key, request_token)
        if pending_key in self.sync_pending:
            return
        pending_mutations = self.pending_session_sync_for(host)
        if key in self.workspace_dirty:
            self.push_workspace_list(host)
        workspace_version = self.workspace_versions.get(key, 0)
        self.sync_pending.add(pending_key)
        pushed_mutations = []
        def worker():
            try:
                sessions = sync_request(self.data, "GET", host=snapshot)["sessions"]
                for mutation in pending_mutations:
                    try:
                        sync_request(self.data, mutation["method"], mutation["session_id"], mutation.get("item"), host=snapshot)
                        pushed_mutations.append(mutation)
                    except Exception:
                        pass
                workspaces = sync_data_request(self.data, "GET", "/v1/workspaces/" + sync_host_key(snapshot))
                agents = sync_data_request(self.data, "GET", "/v1/agents/account")
                profile = sync_data_request(self.data, "GET", "/v1/hosts/" + sync_host_key(snapshot) + "/state")
                result = (sessions, workspaces, agents, profile)
            except Exception as exc:
                if getattr(exc, "code", None) == 401:
                    GLib.idle_add(self.expire_sync_token, request_token)
                result = "同步失败：" + (str(exc) or type(exc).__name__)
            def finish():
                self.sync_pending.discard(pending_key)
                self.clear_session_sync_mutations(pushed_mutations)
                if any(self.shared_host_key(h) == key for h in self.data["hosts"]):
                    if isinstance(result, tuple):
                        sessions, remote_workspaces, remote_agents, profile = result
                        local_host = next((h for h in self.data["hosts"] if self.shared_host_key(h) == key), None)
                        config_changed = False
                        if (local_host is not None and key not in self.workspace_dirty
                                and workspace_version == self.workspace_versions.get(key, 0)):
                            revisions = self.data.setdefault("workspace_sync_revisions", {})
                            identity = self.data.get("sync_username", "") + ":" + sync_host_key(snapshot)
                            local_revision = revisions.get(identity, 0)
                            remote_revision = remote_workspaces.get("revision", 0)
                            if remote_workspaces.get("configured") and local_revision < remote_revision:
                                local_host["directories"] = remote_workspaces["workspaces"]
                                revisions[identity] = remote_revision
                                config_changed = True
                            elif remote_workspaces.get("configured") and local_host.get("directories", []) != remote_workspaces["workspaces"]:
                                local_host["directories"] = remote_workspaces["workspaces"]
                                revisions[identity] = remote_revision
                                config_changed = True
                            elif not remote_workspaces.get("configured") and local_host.get("directories"):
                                self.push_workspace_list(local_host)
                            elif remote_workspaces.get("configured"):
                                revisions[identity] = remote_revision
                        agent_revisions = self.data.setdefault("agent_sync_revisions", {})
                        identity = self.data.get("sync_username", "") + ":account"
                        local_agent_revision = agent_revisions.get(identity, 0)
                        remote_agent_revision = remote_agents.get("revision", 0)
                        if remote_agents.get("configured") and local_agent_revision < remote_agent_revision:
                            self.data["agents"] = remote_agents["agents"]
                            agent_revisions[identity] = remote_agent_revision
                            config_changed = True
                        elif remote_agents.get("configured") and self.data.get("agents", []) != remote_agents["agents"]:
                            self.data["agents"] = remote_agents["agents"]
                            agent_revisions[identity] = remote_agent_revision
                            config_changed = True
                        elif not remote_agents.get("configured") and self.data.get("agents"):
                            self.push_agent_list(local_host)
                        elif remote_agents.get("configured"):
                            agent_revisions[identity] = remote_agent_revision
                        local_profile = profile.get("state", {})
                        if profile.get("configured") and local_host is not None:
                            if local_profile.get("name") and local_host.get("name") != local_profile["name"]:
                                local_host["name"] = local_profile["name"]; config_changed = True
                            if local_profile.get("icon") and local_host.get("icon") != local_profile["icon"]:
                                local_host["icon"] = local_profile["icon"]; config_changed = True
                            command = local_profile.get("selected_agent_command")
                            if command:
                                index = next((i for i, agent in enumerate(self.data.get("agents", [])) if agent.get("command") == command), -1)
                                if index >= 0 and local_host.get("selected_agent", 0) != index:
                                    local_host["selected_agent"] = index; config_changed = True
                        elif local_host is not None:
                            self.push_host_state(local_host)
                        if config_changed:
                            self.persist()
                            self.refresh()
                    else:
                        sessions = None
                        self.shared_by_host[key] = result
                    if isinstance(sessions, list):
                        overlay = pending_mutations + pushed_mutations + self.pending_session_sync_for(host)
                        sessions = self.merge_session_sync_items(sessions, overlay)
                        remote_ids = {item.get("id") for item in sessions}
                        self.shared_by_host[key] = sessions
                        for session in list(self.sessions):
                            session_host = self.canonical_session_host(session.get("host", {}))
                            if self.shared_host_key(session_host) == key:
                                ws = session.get("key", (None, None, None, None, None))[3:5]
                                matching_item = next((item for item in sessions if (item.get("directory_path"), item.get("agent_command")) == ws), None)
                                if matching_item and matching_item.get("id"):
                                    session["id"] = matching_item["id"]
                                elif session.get("id") not in remote_ids and time.monotonic() - session.get("started_at", 0) > 15:
                                    state = session.get("state")
                                    if isinstance(state, dict):
                                        state["closed_by_sync"] = True
                                    session.get("close_button").clicked()
                        for item in sessions:
                            if len(self.sessions) >= 8:
                                break
                            target_key = (snapshot.get("id"), snapshot.get("port"), snapshot.get("username"), item.get("directory_path"), item.get("agent_command"))
                            already_active = any(
                                session.get("id") == item.get("id")
                                or session.get("key", ())[:5] == target_key
                                or (
                                    self.shared_host_key(self.canonical_session_host(session.get("host", {}))) == key
                                    and session.get("key", (None, None, None, None, None))[3:5] == (item.get("directory_path"), item.get("agent_command"))
                                )
                                for session in self.sessions
                            )
                            if not already_active:
                                self.open_terminal(None, snapshot, item, activate=False)
                    self.update_sync_status()
                return False
            GLib.idle_add(finish)
        self.sync_executor.submit(worker)

    def push_workspace_list(self, host):
        if not self.data.get("sync_url"): return
        key = self.shared_host_key(host)
        version = self.workspace_versions.get(key, 0) + 1
        self.workspace_versions[key] = version
        self.workspace_dirty.add(key)
        items = []
        for directory in host.get("directories", []):
            path = directory.get("path", "")
            if path:
                items.append({"id": directory.get("id") or __import__("hashlib").sha256(path.encode("utf-8")).hexdigest()[:32],
                              "name": directory.get("name", path.rsplit("/", 1)[-1]), "path": path, "icon": directory.get("icon", "📁")})
        path = "/v1/workspaces/" + sync_host_key(host)
        identity = self.data.get("sync_username", "") + ":" + sync_host_key(host)
        base_revision = self.data.setdefault("workspace_sync_revisions", {}).get(identity, 0)
        def worker():
            revision = base_revision
            response = None
            error = False
            for attempt in range(3):
                try:
                    response = sync_data_request(self.data, "PUT", path, {"workspaces": items, "base_revision": revision})
                    break
                except Exception as exc:
                    if getattr(exc, "code", None) != 409 or attempt == 2:
                        error = True
                        break
                    try:
                        conflict = json.loads(exc.read().decode("utf-8"))
                        revision = int(conflict["revision"])
                    except (AttributeError, KeyError, TypeError, ValueError, UnicodeDecodeError, json.JSONDecodeError):
                        error = True
                        break
            def finish():
                if self.workspace_versions.get(key) == version:
                    if error:
                        if key not in self.workspace_sync_errors:
                            self.workspace_sync_errors.add(key)
                            self.message("工作区同步失败", "本地顺序已保存，将自动重试同步。")
                    else:
                        self.workspace_dirty.discard(key)
                        self.data.setdefault("workspace_sync_revisions", {})[identity] = response.get("revision", base_revision)
                        local_host = next((h for h in self.data["hosts"] if self.shared_host_key(h) == key), None)
                        merged = response.get("workspaces", items)
                        if local_host is not None and local_host.get("directories", []) != merged:
                            local_host["directories"] = merged
                            self.persist(); self.refresh()
                        else:
                            self.persist()
                        self.workspace_sync_errors.discard(key)
                    self.update_sync_status()
                return False
            GLib.idle_add(finish)
        self.sync_executor.submit(worker)

    def push_agent_list(self, host=None):
        if not self.data.get("sync_url"):
            return
        items = [{"id": agent.get("id") or __import__("hashlib").sha256(agent.get("command", "").encode()).hexdigest()[:32],
                  "name": agent.get("name", "Agent"), "command": agent.get("command", "bash -l"), "icon": agent.get("icon", "✳")}
                 for agent in self.data.get("agents", [])]
        host = host or self.current_host()
        if not host: return
        identity = self.data.get("sync_username", "") + ":account"
        base_revision = self.data.setdefault("agent_sync_revisions", {}).get(identity, 0)
        def worker():
            revision = base_revision
            response = None
            for attempt in range(3):
                try:
                    response = sync_data_request(self.data, "PUT", "/v1/agents/account", {"agents": items, "base_revision": revision})
                    break
                except Exception as exc:
                    if getattr(exc, "code", None) != 409 or attempt == 2:
                        break
                    try:
                        conflict = json.loads(exc.read().decode("utf-8"))
                        revision = int(conflict["revision"])
                    except (AttributeError, KeyError, TypeError, ValueError, UnicodeDecodeError, json.JSONDecodeError):
                        break
            if response is not None:
                def finish():
                    self.data.setdefault("agent_sync_revisions", {})[identity] = response.get("revision", revision)
                    if self.data.get("agents", []) != response.get("agents", []):
                        self.data["agents"] = response.get("agents", []); self.persist(); self.refresh()
                    else: self.persist()
                    return False
                GLib.idle_add(finish)
        self.sync_executor.submit(worker)

    def push_host_state(self, host):
        if not host or not self.data.get("sync_url"): return
        agents = self.host_agents(host)
        index = min(max(host.get("selected_agent", 0), 0), max(0, len(agents) - 1))
        state = {"name": host.get("name", "主机"), "icon": host.get("icon", "⌘")}
        if agents: state["selected_agent_command"] = agents[index].get("command", "")
        path = "/v1/hosts/" + sync_host_key(host) + "/state"
        def worker():
            try:
                for _ in range(3):
                    current = sync_data_request(self.data, "GET", path)
                    merged = dict(current.get("state", {})); merged.update(state)
                    try:
                        sync_data_request(self.data, "PUT", path, {"state": merged, "base_revision": current.get("revision", 0)})
                        break
                    except Exception as exc:
                        if "409" not in str(exc): raise
            except Exception:
                pass
        self.sync_executor.submit(worker)

    def poll_shared(self):
        if not self.data.get("auto_sync_enabled", False):
            return True
        self.refresh_all_shared(prompt_for_login=False)
        return True

    def configure_sync_polling(self):
        if self.sync_poll_source:
            GLib.source_remove(self.sync_poll_source)
            self.sync_poll_source = None
        if self.data.get("auto_sync_enabled", False):
            interval = min(max(int(self.data.get("sync_interval_seconds", 5)), 5), 300)
            self.sync_poll_source = GLib.timeout_add_seconds(interval, self.poll_shared)

    def host_profile_payload(self, host):
        agents = self.host_agents(host)
        selected = min(max(int(host.get("selected_agent", 0)), 0), max(0, len(agents) - 1))
        shared_port = host.get("port")
        lan_port = host.get("lan_port") or shared_port or (host.get("external_port") if not host.get("lan_address") else None)
        external_port = host.get("external_port") or shared_port or (host.get("lan_port") if not host.get("external_address", host.get("address")) else None)
        try:
            lan_port, external_port = int(lan_port), int(external_port)
        except (TypeError, ValueError):
            raise ValueError("主机缺少有效端口，编辑主机并填写端口后才能同步")
        if any(not 1 <= port <= 65535 for port in (lan_port, external_port)):
            raise ValueError("主机端口必须在 1 到 65535 之间")
        try:
            primary_port = int(host.get("port"))
        except (TypeError, ValueError):
            primary_port = lan_port if host.get("lan_address") else external_port
        if not 1 <= primary_port <= 65535:
            primary_port = lan_port if host.get("lan_address") else external_port
        return {"id": str(host.get("id") or uuid.uuid4().hex), "name": host.get("name", "主机"),
                "lan_address": host.get("lan_address", ""), "external_address": host.get("external_address", host.get("address", "")),
                "lan_port": lan_port, "external_port": external_port, "port": primary_port, "username": host.get("username", ""), "icon": host.get("icon", "⌘"),
                "selected_agent_command": agents[selected].get("command", "") if agents else "",
                "legacy_keys": legacy_sync_keys(host)}

    def push_host_profile(self, host):
        if not self.data.get("sync_url") or not self.data.get("sync_token"): return
        try:
            payload = self.host_profile_payload(host)
        except ValueError:
            return
        host_id = payload["id"]
        self.dirty_host_profiles.add(host_id)
        self.persist_dirty_host_profiles()
        def worker():
            try:
                response = sync_data_request(self.data, "PUT", "/v1/hosts/" + host_id, payload)
                canonical_id = response.get("host", {}).get("id", host_id)
                self.dirty_host_profiles.discard(host_id)
                GLib.idle_add(self.persist_dirty_host_profiles)
                if canonical_id != host_id:
                    GLib.idle_add(self.remap_host_id, host_id, canonical_id)
            except Exception: pass
        self.sync_executor.submit(worker)

    def persist_dirty_host_profiles(self):
        self.data["dirty_host_profiles"] = sorted(self.dirty_host_profiles)
        self.persist()
        return False

    def remap_host_id(self, old_id, canonical_id):
        if not old_id or not canonical_id or old_id == canonical_id:
            return False
        old_host = next((host for host in self.data.get("hosts", []) if host.get("id") == old_id), None)
        canonical_host = next((host for host in self.data.get("hosts", []) if host.get("id") == canonical_id), None)
        if old_host is None:
            return False
        if canonical_host is None:
            old_host["id"] = canonical_id
            canonical_host = old_host
        else:
            self.data["hosts"] = [host for host in self.data["hosts"] if host.get("id") != old_id]
        for session in self.sessions:
            if session.get("key", (None,))[0] == old_id:
                session["key"] = (canonical_id, *session["key"][1:])
                session["host"] = canonical_host
        if old_id in self.dirty_host_profiles:
            self.dirty_host_profiles.discard(old_id)
            self.dirty_host_profiles.add(canonical_id)
        for entry in self.data.get("pending_session_sync", []):
            if entry.get("host_id") == str(old_id):
                entry["host_id"] = str(canonical_id)
        self.persist()
        self.refresh()
        return False

    def delete_host_profile(self, host_id):
        if not self.data.get("sync_url") or not self.data.get("sync_token"): return
        self.dirty_host_profiles.discard(host_id)
        self.sync_executor.submit(lambda: sync_data_request(self.data, "DELETE", "/v1/hosts/" + str(host_id)))

    def sync_host_profiles(self):
        if not self.data.get("sync_url") or not self.data.get("sync_token") or self.host_profile_sync_pending: return
        self.host_profile_sync_pending = True
        local_hosts = [dict(host) for host in self.data["hosts"]]
        config = dict(self.data)
        def worker():
            remapped_ids = {}
            try:
                remote = sync_data_request(config, "GET", "/v1/hosts").get("hosts", [])
                active = [item for item in remote if not item.get("deleted")]
                deleted = [item for item in remote if item.get("deleted")]
                matched = set(); result = []
                for host in local_hosts:
                    aliases = set(legacy_sync_keys(host))
                    alias_matches = [item for item in remote if aliases.intersection(item.get("legacy_keys", []))]
                    tombstone = next((item for item in deleted if item.get("id") == host.get("id")), None)
                    if tombstone:
                        canonical_id = tombstone.get("canonical_id")
                        if canonical_id and any(item.get("id") == canonical_id for item in active):
                            matched.add(canonical_id)
                            remapped_ids[host.get("id")] = canonical_id
                            result.append(dict(host, id=canonical_id))
                        else:
                            matched.add(tombstone.get("id"))
                        continue
                    if len(alias_matches) == 1 and alias_matches[0].get("deleted"):
                        matched.add(alias_matches[0].get("id")); continue
                    candidates = [item for item in active if aliases.intersection(item.get("legacy_keys", []))]
                    profile = next((item for item in active if item.get("id") == host.get("id")), None)
                    if profile is None and len(candidates) == 1:
                        profile = candidates[0]
                    if profile:
                        matched.add(profile.get("id"))
                        remapped_ids[host.get("id")] = profile.get("id")
                        if host.get("id") in self.dirty_host_profiles:
                            canonical_host = dict(host, id=profile.get("id"))
                            try:
                                payload = self.host_profile_payload(canonical_host)
                                sync_data_request(config, "PUT", "/v1/hosts/" + payload["id"], payload)
                                self.dirty_host_profiles.discard(host.get("id"))
                            except Exception: pass
                            result.append(canonical_host)
                            continue
                        merged = dict(host)
                        old_route = tuple(host.get(key) for key in ("lan_address", "lan_port", "external_address", "external_port", "address", "port"))
                        merged.update({key: profile.get(key, merged.get(key, "")) for key in ("id", "name", "lan_address", "external_address", "lan_port", "external_port", "port", "username", "icon")})
                        merged["address"] = merged.get("external_address") or merged.get("lan_address") or ""
                        new_route = tuple(merged.get(key) for key in ("lan_address", "lan_port", "external_address", "external_port", "address", "port"))
                        if old_route != new_route:
                            for key in ("_resolved", "_active_address", "_active_port"):
                                merged.pop(key, None)
                        if profile.get("selected_agent_command"):
                            selected = next((i for i, item in enumerate(self.data.get("agents", [])) if item.get("command") == profile["selected_agent_command"]), None)
                            if selected is not None and host.get("selected_agent") is None:
                                merged["selected_agent"] = selected
                        result.append(merged)
                    else:
                        try:
                            payload = self.host_profile_payload(host)
                        except ValueError:
                            result.append(host)
                            continue
                        response = sync_data_request(config, "PUT", "/v1/hosts/" + payload["id"], payload)
                        canonical_id = response.get("host", {}).get("id", payload["id"])
                        matched.add(canonical_id)
                        remapped_ids[host.get("id")] = canonical_id
                        result.append(dict(host, id=canonical_id))
                for profile in active:
                    if profile.get("id") in matched: continue
                    result.append({"id": profile.get("id"), "name": profile.get("name", "主机"), "lan_address": profile.get("lan_address", ""),
                                   "external_address": profile.get("external_address", ""), "address": profile.get("external_address") or profile.get("lan_address", ""),
                                   "port": profile["port"], "lan_port": profile.get("lan_port", profile["port"]), "external_port": profile.get("external_port", profile["port"]), "username": profile.get("username", ""), "icon": profile.get("icon", "⌘"),
                                   "selected_agent": next((i for i, item in enumerate(self.data.get("agents", [])) if item.get("command") == profile.get("selected_agent_command")), 0),
                                   "directories": [], "agents": [dict(item) for item in self.data.get("agents", [])]})
                unique = []
                seen_ids = set()
                for host in result:
                    if host.get("id") in seen_ids: continue
                    seen_ids.add(host.get("id"))
                    unique.append(host)
                result = unique
            except Exception as exc:
                if getattr(exc, "code", None) == 401:
                    GLib.idle_add(self.expire_sync_token, config.get("sync_token"))
                result = None
            def finish():
                self.host_profile_sync_pending = False
                if result is not None:
                    current_hosts = self.data["hosts"]
                    current_by_id = {host.get("id"): host for host in current_hosts}
                    snapshot_ids = {host.get("id") for host in local_hosts}
                    # The profile request used an older snapshot; retain directory edits made while it was running.
                    reconciled = []
                    for synced in result:
                        source_id = next((old_id for old_id, new_id in remapped_ids.items() if new_id == synced.get("id")), synced.get("id"))
                        latest = current_by_id.get(synced.get("id")) or current_by_id.get(source_id)
                        merged = dict(synced)
                        if latest is not None:
                            merged["directories"] = latest.get("directories", merged.get("directories", []))
                        reconciled.append(merged)
                    reconciled_ids = {host.get("id") for host in reconciled}
                    reconciled.extend(dict(host) for host in current_hosts
                                      if host.get("id") not in snapshot_ids and host.get("id") not in reconciled_ids)
                    self.data["dirty_host_profiles"] = sorted(self.dirty_host_profiles)
                    if reconciled != current_hosts:
                        self.data["hosts"] = reconciled
                        self.refresh()
                    self.persist()
                    current_by_id = {host.get("id"): host for host in self.data["hosts"]}
                    for session in self.sessions:
                        old_id = session.get("key", (None,))[0]
                        canonical_id = remapped_ids.get(old_id, old_id)
                        if canonical_id in current_by_id:
                            if canonical_id != old_id:
                                session["key"] = (canonical_id, *session["key"][1:])
                            session["host"] = current_by_id[canonical_id]
                    if remapped_ids:
                        for entry in self.data.get("pending_session_sync", []):
                            old_h = entry.get("host_id")
                            if old_h in remapped_ids:
                                entry["host_id"] = remapped_ids[old_h]
                    if self.refresh_sessions_after_host_profiles:
                        self.refresh_sessions_after_host_profiles = False
                        self.shared_by_host = {}
                        for host in self.data["hosts"]:
                            key = self.shared_host_key(host)
                            self.shared_by_host[key] = "正在同步…"
                            self.refresh_host_shared(host)
                        self.update_sync_status()
                elif self.refresh_sessions_after_host_profiles:
                    self.refresh_sessions_after_host_profiles = False
                    for host in self.data["hosts"]:
                        self.shared_by_host[self.shared_host_key(host)] = "主机身份同步失败"
                    self.update_sync_status()
                return False
            GLib.idle_add(finish)
        self.sync_executor.submit(worker)

    def ensure_sync_login(self):
        if not self.data.get("sync_url"):
            return False
        if self.data.get("sync_token"):
            try:
                account = sync_data_request(self.data, "GET", "/v1/auth/me")
                self.data["sync_username"] = account.get("username", "")
                self.data["sync_is_admin"] = account.get("is_admin", False)
                return True
            except Exception as exc:
                if getattr(exc, "code", None) != 401:
                    return False
                self.data["sync_token"] = ""
        dialog = Gtk.Dialog(title="同步账号登录", transient_for=self, modal=True)
        dialog.add_button("取消", Gtk.ResponseType.CANCEL)
        dialog.add_button("登录", Gtk.ResponseType.OK)
        grid = Gtk.Grid(column_spacing=10, row_spacing=8, margin=16)
        dialog.get_content_area().add(grid)
        username = Gtk.Entry(text=self.data.get("sync_username", ""))
        password = Gtk.Entry()
        password.set_visibility(False)
        password.set_text(load_sync_password(username.get_text()))
        remember = Gtk.CheckButton(label="保持账号密码")
        remember.set_active(bool(self.data.get("sync_remember", True)))
        status = Gtk.Label(xalign=0)
        status.set_line_wrap(True)
        grid.attach(Gtk.Label(label="账号", xalign=0), 0, 0, 1, 1)
        grid.attach(username, 1, 0, 1, 1)
        grid.attach(Gtk.Label(label="密码", xalign=0), 0, 1, 1, 1)
        grid.attach(password, 1, 1, 1, 1)
        grid.attach(remember, 1, 2, 1, 1)
        grid.attach(status, 0, 3, 2, 1)
        dialog.show_all()
        while True:
            response = dialog.run()
            if response != Gtk.ResponseType.OK:
                dialog.destroy()
                return False
            account_name, account_password = username.get_text().strip(), password.get_text()
            try:
                result = sync_auth_request(self.data, "login", account_name, account_password)
                self.data["sync_username"] = result["username"]
                self.data["sync_token"] = result["access_token"]
                self.data["sync_is_admin"] = result.get("is_admin", False)
                self.data["sync_remember"] = remember.get_active()
                if remember.get_active():
                    save_sync_password(account_name, account_password)
                else:
                    clear_sync_password(account_name)
                self.persist()
                dialog.destroy()
                return True
            except Exception as exc:
                status.set_text(str(exc))

    def expire_sync_token(self, expired_token):
        if expired_token and self.data.get("sync_token") == expired_token:
            self.data["sync_token"] = ""
            self.persist()
            for host in self.data["hosts"]:
                self.shared_by_host[self.shared_host_key(host)] = "同步登录已失效，请手动重新登录"
            self.update_sync_status()
        return False

    def refresh_all_shared(self, prompt_for_login=True):
        self.shared_by_host = {}
        if not self.data.get("auto_sync_enabled", False):
            self.refresh_sessions_after_host_profiles = False
            self.update_sync_status()
            return
        if self.data.get("sync_url"):
            if prompt_for_login:
                if not self.ensure_sync_login(): return
            elif not self.data.get("sync_token"):
                for host in self.data["hosts"]:
                    self.shared_by_host[self.shared_host_key(host)] = "请手动重新登录同步账号"
                self.update_sync_status()
                return
        if not self.data.get("sync_url"):
            self.update_sync_status()
            return
        self.refresh_sessions_after_host_profiles = True
        self.sync_host_profiles()
        for host in self.data["hosts"]:
            self.shared_by_host[self.shared_host_key(host)] = "正在同步…"
        self.update_sync_status()

    def queue_session_sync(self, host, method, session_id, item=None):
        if not host or method not in ("PUT", "DELETE") or not session_id:
            return
        pending = self.data.setdefault("pending_session_sync", [])
        entry = {
            "mutation_id": uuid.uuid4().hex,
            "account": str(self.data.get("sync_username", "")).strip().casefold(),
            "host_id": str(host.get("id", "")),
            "username": str(host.get("username", "")),
            "session_id": str(session_id).replace("-", ""),
            "method": method,
            "item": dict(item) if item is not None else None,
        }
        pending[:] = [old for old in pending if not (
            old.get("host_id") == entry["host_id"] and old.get("username") == entry["username"]
            and old.get("session_id") == entry["session_id"]
            and old.get("account", "").casefold() == entry["account"]
        )]
        pending.append(entry)
        self.persist()

    def pending_session_sync_for(self, host):
        account = str(self.data.get("sync_username", "")).strip().casefold()
        return [dict(item) for item in self.data.get("pending_session_sync", [])
                if item.get("host_id") == str(host.get("id", ""))
                and item.get("username") == str(host.get("username", ""))
                and item.get("account", "").casefold() in ("", account)]

    @staticmethod
    def merge_session_sync_items(items, mutations):
        merged = {item.get("id"): item for item in items if item.get("id")}
        for mutation in mutations:
            session_id = mutation.get("session_id")
            if mutation.get("method") == "DELETE":
                merged.pop(session_id, None)
            elif mutation.get("method") == "PUT" and mutation.get("item"):
                merged[session_id] = mutation["item"]
        by_workspace = {}
        for item in merged.values():
            ws_key = (item.get("directory_path"), item.get("agent_command"))
            if ws_key[0] is not None and ws_key[1] is not None:
                by_workspace.pop(ws_key, None)
                by_workspace[ws_key] = item
            else:
                by_workspace[("id", item.get("id"))] = item
        return list(by_workspace.values())

    def clear_session_sync_mutations(self, mutations):
        mutation_ids = {item.get("mutation_id") for item in mutations}
        pending = self.data.get("pending_session_sync", [])
        updated = [item for item in pending if item.get("mutation_id") not in mutation_ids]
        if len(updated) != len(pending):
            self.data["pending_session_sync"] = updated
            self.persist()
        return False

    def sync_async(self, host, method, session_id, item=None):
        self.queue_session_sync(self.canonical_session_host(host), method, session_id, item)

    def show_shared_sessions(self, _button):
        if not self.data.get("auto_sync_enabled", False):
            self.message("自动同步已关闭", "请先在系统设置中启用本机的跨设备自动同步。")
            return
        host = self.current_host()
        if not host or not self.data.get("sync_url"):
            self.message("共享会话", "请先在系统设置中填写同步服务地址并登录账号。")
            return
        pending_mutations = self.pending_session_sync_for(host)
        def worker():
            try:
                items = sync_request(self.data, "GET", host=host)["sessions"]
                pushed_mutations = []
                for mutation in pending_mutations:
                    try:
                        sync_request(self.data, mutation["method"], mutation["session_id"], mutation.get("item"), host=host)
                        pushed_mutations.append(mutation)
                    except Exception:
                        pass
                items = self.merge_session_sync_items(items, pending_mutations + self.pending_session_sync_for(host))
                if pushed_mutations:
                    GLib.idle_add(self.clear_session_sync_mutations, pushed_mutations)
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
                self.scale_layout(dialog)
                dialog.show_all()
                dialog.run()
                dialog.destroy()
                return False
            GLib.idle_add(show)
        self.sync_executor.submit(worker)

    def open_terminal(self, _button, shared_host=None, shared_item=None, activate=True):
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
        key = (host.get("id"), host["port"], host["username"], directory["path"], agent["command"])
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
            return False
        # Wait until VTE has finished updating its selection before copying it.
        terminal.connect("selection-changed", lambda widget: GLib.idle_add(copy_selection, widget))
        def update_tile_focus(widget, _event, focused):
            frame = getattr(widget, "_tile_frame", None)
            if frame:
                context = frame.get_style_context()
                (context.add_class if focused else context.remove_class)("tile-focused")
            return False
        terminal.connect("focus-in-event", update_tile_focus, True)
        terminal.connect("focus-out-event", update_tile_focus, False)
        terminal.set_font_scale(self.terminal_font_scale())
        terminal.set_size(24, 8)
        terminal.set_hexpand(True)
        terminal.set_vexpand(True)
        terminal.set_scrollback_lines(10000)
        label = Gtk.Box(spacing=5)
        label.pack_start(Gtk.Label(label=directory["name"] + " · " + agent["name"]), True, True, 0)
        kind, quota_executable = quota_agent(agent["command"])
        if kind == "codex":
            # Codex handles Page Up/Down, while tmux may capture the wheel and
            # enter an empty copy-mode history instead of scrolling Codex.
            smooth_scroll = [0.0]
            def scroll_codex(widget, event):
                if event.state & Gtk.accelerator_get_default_mod_mask():
                    return False
                if event.direction == Gdk.ScrollDirection.UP:
                    delta = -1.0
                elif event.direction == Gdk.ScrollDirection.DOWN:
                    delta = 1.0
                elif event.direction == Gdk.ScrollDirection.SMOOTH:
                    valid, _dx, delta = event.get_scroll_deltas()
                    if not valid or delta == 0:
                        return False
                else:
                    return False
                smooth_scroll[0] += delta
                while abs(smooth_scroll[0]) >= 1:
                    up = smooth_scroll[0] < 0
                    widget.feed_child(b"\x1b[5~" if up else b"\x1b[6~")
                    smooth_scroll[0] += 1 if up else -1
                return True
            terminal.add_events(Gdk.EventMask.SCROLL_MASK | Gdk.EventMask.SMOOTH_SCROLL_MASK)
            terminal.connect("scroll-event", scroll_codex)
        quota_box = Gtk.Box(spacing=3)
        hourly_img, update_hourly = make_quota_indicator()
        sep = Gtk.Label(label="·")
        sep.get_style_context().add_class("subtitle")
        weekly_img, update_weekly = make_quota_indicator()
        days_label = Gtk.Label(label="")
        days_label.get_style_context().add_class("subtitle")
        quota_box.pack_start(hourly_img, False, False, 0)
        quota_box.pack_start(sep, False, False, 0)
        quota_box.pack_start(weekly_img, False, False, 0)
        quota_box.pack_start(days_label, False, False, 0)
        if kind:
            quota_box.show_all()
            days_label.hide()
        quota_box.set_no_show_all(True)
        label.pack_start(quota_box, False, False, 0)

        def update_quota_display(result):
            if not kind:
                return
            hourly_pct = None
            weekly_pct = None
            days_val = None
            for item in result:
                if isinstance(item, (list, tuple)) and len(item) >= 2:
                    limit, pct = item[0], item[1]
                    if limit == 300:
                        hourly_pct = pct
                    elif limit == 10080:
                        weekly_pct = pct
                        if len(item) > 2 and item[2]:
                            days_val = item[2]
            update_hourly(hourly_pct)
            hourly_img.set_tooltip_text(f"小时余量: {hourly_pct}%" if hourly_pct is not None else "小时余量: --")
            update_weekly(weekly_pct)
            weekly_img.set_tooltip_text(f"周余量: {weekly_pct}%" if weekly_pct is not None else "周余量: --")
            if days_val:
                days_label.set_text(days_val)
                days_label.show()
            else:
                days_label.set_text("")
                days_label.hide()
            quota_box.show()
        image_button = Gtk.Button.new_from_icon_name("image-x-generic-symbolic", Gtk.IconSize.MENU)
        image_button.set_relief(Gtk.ReliefStyle.NONE)
        image_button.set_tooltip_text("发送图片给此终端的 Agent")
        label.pack_start(image_button, False, False, 0)
        close = Gtk.Button.new_from_icon_name("window-close-symbolic", Gtk.IconSize.MENU)
        close.set_relief(Gtk.ReliefStyle.NONE)
        label.pack_start(close, False, False, 0)
        state = {
            "closed": False,
            "connected": False,
            "generation": 0,
            "retry": 0,
            "retry_source": None,
            "watchdog_timer": None,
            "quota_busy": False,
            "connected_at": 0,
            "last_output_at": 0.0
        }
        image_button.connect("clicked", lambda *_: self.send_image_dialog(host, socket_path, terminal, state))
        def close_tab(*_):
            state["closed"] = True
            if not state.get("closed_by_sync"):
                self.sync_async(host, "DELETE", session_id)
            if state["watchdog_timer"]:
                GLib.source_remove(state["watchdog_timer"])
                state["watchdog_timer"] = None
            if state["retry_source"]:
                GLib.source_remove(state["retry_source"])
                state["retry_source"] = None
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
            address = ssh_address(host)
            subprocess.Popen(["ssh", "-S", socket_path, "-O", "exit", "-p", str(ssh_port(host)), "-l", host["username"], "--", address], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            shutil.rmtree(socket_dir, ignore_errors=True)
        close.connect("clicked", close_tab)
        self.sessions.append({"terminal": terminal, "label": label, "key": key, "id": session_id, "host": host, "state": state,
                              "item": item, "socket_path": socket_path, "close_button": close, "started_at": time.monotonic(),
                              "quota_indicators": (hourly_img, weekly_img)})
        self.rebuild_view(selected=terminal if activate else None)
        def spawned(_terminal, _pid, error, *_):
            if error:
                self.message("无法启动 SSH", str(error))
        reconnect = Gtk.Button(label="重连")
        reconnect.set_no_show_all(True)
        label.pack_start(reconnect, False, False, 0)
        label.reorder_child(close, -1)
        self.sessions[-1]["reconnect_button"] = reconnect
        self.scale_layout(label)
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
                address = ssh_address(host)
                base = ["ssh", "-S", socket_path, "-o", "BatchMode=yes", "-o", "ConnectTimeout=5", "-p", str(ssh_port(host)), "-l", host["username"], "--", address]
                try:
                    if kind == "agy":
                        remote = quota_remote_command(directory["path"], quota_executable, '-p "/usage" --output-format json')
                        output = subprocess.check_output(base + [remote], stdin=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15).decode("utf-8", errors="replace").strip()
                        if output and not output.startswith("{") and "{" in output:
                            output = output[output.find("{"):]
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
                        update_quota_display(result)
                    return False
                GLib.idle_add(finish)
            threading.Thread(target=worker, daemon=True).start()
            return True
        GLib.timeout_add_seconds(60, read_quota)
        auto_confirm = AutoEnterController()

        def visible_signature():
            try:
                # Only inspect the visible screen, not old prompts in scrollback.
                if hasattr(terminal, "get_text_format"):
                    text = terminal.get_text_format(Vte.Format.TEXT)
                else:
                    _, row = terminal.get_cursor_position()
                    text, _ = terminal.get_text_range_format(
                        Vte.Format.TEXT, max(0, row - terminal.get_row_count() + 1), 0,
                        row, terminal.get_column_count() - 1)
                return auto_enter_signature(text or "")
            except (AttributeError, TypeError, ValueError):
                return None

        def check_prompt():
            enabled = state["connected"] and self.auto_enter.get_active() and not state["closed"]
            signature = visible_signature() if enabled else None
            if auto_confirm.observe(signature, time.monotonic(), self.delay.get_value_as_int(), enabled):
                # A changed selection or prompt must start a fresh countdown.
                if visible_signature() == signature:
                    terminal.feed_child(b"\r")
                else:
                    auto_confirm.reset()

        def changed(*_):
            state["last_output_at"] = time.monotonic()
            check_prompt()

        terminal.connect("contents-changed", changed)

        def session_watchdog():
            if state["closed"]:
                return False
            check_prompt()
            return True

        state["watchdog_timer"] = GLib.timeout_add(250, session_watchdog)

        def key_pressed(widget, event):
            modifiers = event.state & Gtk.accelerator_get_default_mod_mask()
            if (kind == "agy" and modifiers == Gdk.ModifierType.CONTROL_MASK
                    and event.keyval in (Gdk.KEY_v, Gdk.KEY_V)):
                auto_confirm.cancel(visible_signature())
                widget.paste_clipboard()
                return True
            if modifiers == (Gdk.ModifierType.CONTROL_MASK | Gdk.ModifierType.SHIFT_MASK):
                if event.keyval in (Gdk.KEY_c, Gdk.KEY_C):
                    copy_selection(widget)
                    return True
                if event.keyval in (Gdk.KEY_v, Gdk.KEY_V):
                    auto_confirm.cancel(visible_signature())
                    widget.paste_clipboard()
                    return True
            # 单纯按下修饰键（Shift、Ctrl、Alt等）不取消自动回车
            if event.keyval not in (
                Gdk.KEY_Shift_L, Gdk.KEY_Shift_R,
                Gdk.KEY_Control_L, Gdk.KEY_Control_R,
                Gdk.KEY_Alt_L, Gdk.KEY_Alt_R,
                Gdk.KEY_Super_L, Gdk.KEY_Super_R,
                Gdk.KEY_Caps_Lock,
            ):
                auto_confirm.cancel(visible_signature())
            return False
        terminal.connect("key-press-event", key_pressed)
        def start(*_):
            if state["closed"]:
                return False
            if state["retry_source"]:
                GLib.source_remove(state["retry_source"])
                state["retry_source"] = None
            auto_confirm.reset()
            state["last_output_at"] = time.monotonic()
            reconnect.hide()
            state["generation"] += 1
            auth_env = ssh_auth_environment(host, Path(__file__).with_name("askpass.py")) if host.get("credential_id") else None
            envv = [f"{key}={value}" for key, value in auth_env.items()] if auth_env else None
            terminal.spawn_async(Vte.PtyFlags.DEFAULT, None, argv, envv, GLib.SpawnFlags.SEARCH_PATH, None, None, -1, None, spawned, None)
            return False
        reconnect.connect("clicked", start)
        def child_exited(_terminal, status):
            state["connected"] = False
            update_quota_display([])
            auto_confirm.reset()
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
                state["last_output_at"] = time.monotonic()
                read_quota()
                changed()
            return True
        GLib.timeout_add_seconds(1, check_connection)
        start()


if __name__ == "__main__":
    App().show_all()
    Gtk.main()
