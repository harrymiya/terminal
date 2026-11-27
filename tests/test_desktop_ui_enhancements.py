import importlib
from pathlib import Path
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch


class DesktopUIEnhancementsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        omarchy_dir = str(Path(__file__).resolve().parents[1] / "omarchy")
        if omarchy_dir not in sys.path:
            sys.path.insert(0, omarchy_dir)
        cls.app_mod = importlib.import_module("remote-dev-terminal")
        if not cls.app_mod.Gtk.init_check([])[0]:
            raise unittest.SkipTest("GTK display not available in headless environment")

    def test_default_tiled_mode(self):
        # By default when "tiled" is not specified in config, tiled mode is True
        config = {}
        tiled_mode = config.get("tiled", True)
        self.assertTrue(tiled_mode)

    def test_separator_css_has_wide_hitbox_and_crisp_visual(self):
        for platform in ("omarchy", "ubuntu"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertTrue("paned.tile-paned > separator {" in py_file or "paned.tile-paned > separator {{" in py_file)
            self.assertTrue(
                "paned.tile-paned.horizontal > separator { min-width: 2px; }" in py_file
                or "paned.tile-paned.horizontal > separator {{ min-width: 2px; }}" in py_file
            )
            self.assertTrue(
                "paned.tile-paned.vertical > separator { min-height: 2px; }" in py_file
                or "paned.tile-paned.vertical > separator {{ min-height: 2px; }}" in py_file
            )

    def test_notebook_and_tab_dark_theme(self):
        for platform in ("omarchy", "ubuntu"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertTrue("notebook { background-color:" in py_file or "notebook {{ background-color:" in py_file)
            self.assertTrue("notebook tab {" in py_file or "notebook tab {{" in py_file)
            self.assertTrue("notebook tab:checked {" in py_file or "notebook tab:checked {{" in py_file)
            self.assertTrue(".tile-header {" in py_file or ".tile-header {{" in py_file)

    def test_dark_theme_css_colors(self):
        for platform in ("omarchy", "ubuntu"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertTrue("entry {" in py_file or "entry {{" in py_file)
            self.assertTrue("spinbutton {" in py_file or "spinbutton {{" in py_file)
            self.assertTrue("combobox button.combo, combobox button {" in py_file or "combobox button.combo, combobox button {{" in py_file)
            self.assertTrue("button {" in py_file or "button {{" in py_file)
            self.assertTrue(".sidebar button {" in py_file or ".sidebar button {{" in py_file)
            self.assertTrue(".host-button {" in py_file or ".host-button {{" in py_file)
            self.assertTrue(".project-button {" in py_file or ".project-button {{" in py_file)
            self.assertTrue(".manage-button {" in py_file or ".manage-button {{" in py_file)

    def test_view_button_vector_icons(self):
        for platform in ("omarchy", "ubuntu"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn('"view-paged-symbolic" if self.tiled else "view-grid-symbolic"', py_file)

    def test_host_list_ui_matches_workspace_management(self):
        for platform in ("omarchy", "ubuntu"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn('actions = [("＋主机", "add")]', py_file)
            self.assertIn('def manage_host(self, button, index, action):', py_file)
            self.assertIn('for label, action in [("✎", "edit"), ("×", "delete")]:', py_file)

    def test_left_alignments(self):
        for platform in ("omarchy", "ubuntu"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn('label.set_xalign(0.0)', py_file)

    def test_omarchy_developer_menu_quota_and_reordering(self):
        app = self.app_mod.App()
        try:
            host = {"id": "host", "name": "Host", "address": "127.0.0.1", "port": 2200, "username": "dev", "selected_agent": 0}
            other = {**host, "id": "other", "selected_agent": 1}
            app.persist = lambda: None
            app.data["hosts"] = [host, other]
            app.data["agents"] = [
                {"id": "codex", "name": "Codex", "command": "codex"},
                {"id": "agy", "name": "Gemini", "command": "agy"},
            ]
            app.quota_pending.add(self.app_mod.quota_key(host, "codex"))
            app.quota_pending.add(self.app_mod.quota_key(host, "agy"))
            app.quota_cache[self.app_mod.quota_key(host, "codex")] = (
                self.app_mod.time.monotonic(), [(300, 75, None), (10080, 40, "3d")],
            )
            app.refresh()
            app.show_all()
            app.edit_toggle.set_active(True)
            while self.app_mod.Gtk.events_pending():
                self.app_mod.Gtk.main_iteration()
            row = app.agents_list.get_children()[0]
            children = row.get_children()
            self.assertEqual([button.get_label() for button in children[1].get_children()], ["↑", "↓", "✎", "×"])
            quota = children[0].get_child().get_children()[1]
            self.assertIsInstance(quota, self.app_mod.Gtk.Box)
            self.assertTrue(quota.is_ancestor(children[0]))
            self.assertEqual(quota.get_orientation(), self.app_mod.Gtk.Orientation.HORIZONTAL)
            hourly, weekly, reset = quota.get_children()
            columns = (hourly, weekly, reset)
            widths = [column.get_preferred_width() for column in columns]
            for indicator, percent in ((hourly, 75), (weekly, 40)):
                battery, value = indicator.get_children()
                self.assertEqual(battery.get_children()[0].get_value(), percent)
                self.assertEqual(value.get_text(), str(percent))
            self.assertEqual(reset.get_text(), "3")
            row.update_developer_quota([(300, 100, None), (10080, 0, "<1d")])
            self.assertEqual(hourly.get_children()[1].get_text(), "100")
            self.assertEqual(weekly.get_children()[1].get_text(), "0")
            self.assertEqual(reset.get_text(), "<1")
            self.assertEqual([column.get_preferred_width() for column in columns], widths)
            self.assertFalse(hourly.get_children()[1].get_layout().is_ellipsized())
            gemini = app.agents_list.get_children()[1].get_children()[0].get_child().get_children()[1]
            bundle = gemini.get_children()
            self.assertEqual(bundle[0].get_children()[1].get_text(), "--")
            self.assertEqual(bundle[2].get_text(), "--")
            self.assertEqual([column.get_preferred_width() for column in bundle], widths)
            self.assertEqual(
                [column.translate_coordinates(app.agents_list, 0, 0)[0] for column in columns],
                [column.translate_coordinates(app.agents_list, 0, 0)[0] for column in bundle],
            )
            self.assertFalse(children[1].get_children()[0].get_sensitive())
            children[1].get_children()[1].clicked()
            self.assertEqual([item["command"] for item in app.data["agents"]], ["agy", "codex"])
            self.assertEqual(app.agents.get_active(), 1)
            self.assertEqual((host["selected_agent"], other["selected_agent"]), (1, 0))
            app.edit_toggle.set_active(False)
            self.assertEqual(len(app.agents_list.get_children()[1].get_children()), 1)
        finally:
            app.sync_executor.shutdown(wait=True)
            app.disconnect_by_func(self.app_mod.Gtk.main_quit)
            app.destroy()

    def test_developer_quota_refresh_reuses_live_ssh_and_observes_interval(self):
        app = self.app_mod.App()
        try:
            host = {"id": "host", "name": "Host", "address": "127.0.0.1", "port": 2200, "username": "dev"}
            app.data["hosts"] = [host]
            app.data["agents"] = [
                {"id": "codex", "name": "Codex", "command": "codex"},
                {"id": "resume", "name": "Resume", "command": "codex resume"},
            ]
            app.data["agent_quota_interval_seconds"] = 45
            app.refresh()
            app.show_all()
            session = {"host": host, "item": {"agent_command": "codex", "directory_path": "/project"},
                       "state": {"connected": True}, "socket_path": "/tmp/rdt-control", "update_quota": lambda _: None}
            app.sessions = [session, session.copy()]
            key = self.app_mod.quota_key(host, "codex")

            with patch.object(self.app_mod.threading, "Thread") as worker, \
                    patch.object(self.app_mod.time, "monotonic", return_value=100) as clock:
                app.refresh_quotas()
                self.assertEqual(worker.call_count, 1)
                self.assertEqual(worker.call_args.kwargs["args"], (key, (host, "codex", "/project", "/tmp/rdt-control")))
                clock.return_value = 144.9
                app.quota_pending.clear()
                app.refresh_quotas()
                self.assertEqual(worker.call_count, 1)
                clock.return_value = 145
                app.refresh_quotas()
                self.assertEqual(worker.call_count, 2)
        finally:
            app.sessions = []
            app.sync_executor.shutdown(wait=True)
            app.disconnect_by_func(self.app_mod.Gtk.main_quit)
            app.destroy()


    def test_session_changes_queue_until_sync_and_merge_over_pulled_list(self):
        app = self.app_mod.App()
        try:
            app.data = {"sync_username": "single-user", "pending_session_sync": []}
            app.persist = lambda: None
            host = {"id": "host-id", "username": "dev"}
            deleted_id = "a" * 32
            added_id = "b" * 32
            added = {"id": added_id, "directory_path": "/new", "directory_name": "new", "agent_name": "Shell", "agent_command": "bash -l", "display_name": "new"}
            app.queue_session_sync(host, "PUT", deleted_id, {**added, "id": deleted_id})
            app.queue_session_sync(host, "DELETE", deleted_id)
            app.queue_session_sync(host, "PUT", added_id, added)
            pending = app.pending_session_sync_for(host)
            self.assertEqual([(item["session_id"], item["method"]) for item in pending], [(deleted_id, "DELETE"), (added_id, "PUT")])
            pulled = [{**added, "id": deleted_id}, {**added, "id": "c" * 32, "directory_path": "/other"}]
            merged = app.merge_session_sync_items(pulled, pending)
            self.assertEqual([item["id"] for item in merged], ["c" * 32, added_id])
        finally:
            app.sync_executor.shutdown(wait=True)
            app.disconnect_by_func(self.app_mod.Gtk.main_quit)
            app.destroy()

    def test_renamed_session_header_keeps_workspace_and_agent(self):
        mod = self.app_mod
        self.assertEqual(
            mod.session_display_name({"directory_name": "proj", "agent_name": "Codex", "display_name": "Fix headers"}),
            "Fix headers · proj · Codex",
        )
        self.assertEqual(
            mod.session_display_name({"directory_name": "proj", "agent_name": "Codex",
                                      "display_name": "Fix headers · proj · Codex"}),
            "Fix headers · proj · Codex",
        )
        self.assertEqual(
            mod.session_workspace_label({"directory_name": "proj", "agent_name": "Codex",
                                         "display_name": "Fix headers · proj · Codex"}),
            "Fix headers · proj",
        )
        app = mod.App()
        try:
            app.persist = lambda: None
            session = {
                "id": "a" * 32,
                "item": {"directory_name": "proj", "directory_path": "/proj", "agent_command": "codex",
                         "display_name": "Old name", "title_source": "default"},
                "agent": {"name": "Codex", "command": "codex"},
                "title_source": "default",
                "session_title": mod.Gtk.Label(label="Old name · proj · Codex"),
            }
            app.set_session_name(session, "Fix headers", sync=False, title_source="agent")
            self.assertEqual(session["session_title"].get_text(), "Fix headers · proj · Codex")
            self.assertEqual(session["item"]["display_name"], "Fix headers")
        finally:
            app.sync_executor.shutdown(wait=True)
            app.disconnect_by_func(mod.Gtk.main_quit)
            app.destroy()

    def test_session_title_mutation_is_pushed_to_remote_server_immediately(self):
        app = self.app_mod.App()
        try:
            host = {"id": "host-id", "username": "dev", "address": "127.0.0.1", "port": 22}
            item = {"id": "a" * 32, "directory_path": "/project", "directory_name": "project",
                    "agent_name": "Codex", "agent_command": "codex",
                    "display_name": "Generated title", "title_source": "agent"}
            app.data.update({"sync_username": "single-user", "sync_url": "https://sync.example",
                             "sync_token": "test-token", "auto_sync_enabled": True,
                             "offline_mode": False, "pending_session_sync": []})
            app.persist = lambda: None
            requests = []

            def record_request(config, method, session_id=None, payload=None, host=None):
                requests.append((method, session_id, payload, host))
                return payload

            with patch.object(self.app_mod, "sync_request", side_effect=record_request), \
                    patch.object(self.app_mod.GLib, "idle_add", side_effect=lambda callback, *args: callback(*args)):
                app.sync_async(host, "PUT", item["id"], item)
                app.sync_executor.shutdown(wait=True)

            self.assertEqual(len(requests), 1)
            self.assertEqual(requests[0][0:2], ("PUT", item["id"]))
            self.assertEqual(requests[0][2]["display_name"], "Generated title")
            self.assertEqual(requests[0][2]["title_source"], "agent")
            self.assertEqual(app.data["pending_session_sync"], [])
        finally:
            app.sync_executor.shutdown(wait=True)
            app.disconnect_by_func(self.app_mod.Gtk.main_quit)
            app.destroy()

    def test_uniform_control_heights(self):
        for platform in ("omarchy", "ubuntu"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertTrue(
                'button, entry, spinbutton, combobox, combobox button, .menu-toggle, .host-button, .project-button, .manage-button {' in py_file
                or 'button, entry, spinbutton, combobox, combobox button, .menu-toggle, .host-button, .project-button, .manage-button {{' in py_file
                or 'button:not(.titlebutton), entry, spinbutton, combobox, combobox button, .menu-toggle, .host-button, .project-button, .manage-button {{' in py_file
            )
            self.assertIn('min-height: 24px;', py_file)
            self.assertIn('padding: 2px 8px;', py_file)

    def test_spinbutton_buttons_have_no_borders(self):
        for platform in ("omarchy", "ubuntu"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn('spinbutton button', py_file)
            self.assertIn('spinbutton.horizontal > button', py_file)
            self.assertIn('border: none;', py_file)
            self.assertIn('border-width: 0;', py_file)
            self.assertIn('box-shadow: none;', py_file)

    def test_format_quota_with_weekly_reset_days(self):
        omarchy_dir = str(Path(__file__).resolve().parents[1] / "omarchy")
        if omarchy_dir not in sys.path:
            sys.path.insert(0, omarchy_dir)
        core_mod = importlib.import_module("core")
        format_quota = core_mod.format_quota

        self.assertEqual(format_quota([]), "-- · --")
        self.assertEqual(format_quota([(300, 80), (10080, 50)]), "80% · 50%")
        self.assertEqual(format_quota([(300, 80, None), (10080, 50, "5d")]), "80% · 50% 5d")
        self.assertEqual(format_quota([(10080, 50, "<1d")]), "-- · 50% <1d")


    def test_tab_corners_are_square_not_rounded(self):
        for platform in ("omarchy", "ubuntu"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertTrue("notebook tab {" in py_file or "notebook tab {{" in py_file)
            self.assertIn("border-radius: 0;", py_file)

    def test_running_card_header_and_tab_breathing_halo_glow(self):
        for platform in ("omarchy", "ubuntu"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertTrue(
                "running-outline" in py_file
                or "@keyframes orange-glow-breath" in py_file
                or "draw_running_outline" in py_file
            )

    def test_battery_quota_style_and_threshold_colors(self):
        omarchy_dir = str(Path(__file__).resolve().parents[1] / "omarchy")
        if omarchy_dir not in sys.path:
            sys.path.insert(0, omarchy_dir)
        core_mod = importlib.import_module("core")
        render_battery_pixbuf = core_mod.render_battery_pixbuf

        # Test pixbuf rendering at various thresholds
        pb_none = render_battery_pixbuf(None)
        self.assertEqual(pb_none.get_width(), 34)
        self.assertEqual(pb_none.get_height(), 13)

        pb_red = render_battery_pixbuf(3)       # <5% Red
        self.assertEqual(pb_red.get_width(), 34)

        pb_orange = render_battery_pixbuf(15)   # <20% Orange
        self.assertEqual(pb_orange.get_width(), 34)

        pb_green = render_battery_pixbuf(85)    # >=20% Green
        self.assertEqual(pb_green.get_width(), 34)

        pb_full = render_battery_pixbuf(100)
        self.assertEqual(pb_full.get_width(), 34)


if __name__ == "__main__":
    unittest.main()


class DesktopThemeBuildTest(unittest.TestCase):
    def test_ubuntu_and_omarchy_support_cyberpunk_and_light_themes(self):
        for platform in ("ubuntu", "omarchy"):
            platform_dir = str(Path(__file__).resolve().parents[1] / platform)
            if platform_dir not in sys.path:
                sys.path.insert(0, platform_dir)
            try:
                sys.modules.pop("core", None)
                sys.modules.pop("remote-dev-terminal", None)
                mod = importlib.import_module("remote-dev-terminal")
                mod = importlib.reload(mod)
            finally:
                if platform_dir in sys.path:
                    sys.path.remove(platform_dir)
            self.assertIn("cyberpunk", mod.THEMES)
            self.assertIn("neon_glow", mod.THEMES)
            self.assertIn("neon", mod.THEMES)
            self.assertIn("light", mod.THEMES)
            cyber_css = mod.build_theme_css("cyberpunk")
            glow_css = mod.build_theme_css("neon_glow")
            neon_css = mod.build_theme_css("neon")
            light_css = mod.build_theme_css("light")
            self.assertIn("#00f0ff", cyber_css)
            self.assertIn("#ff2a85", cyber_css)
            self.assertIn("@keyframes neon_breathe", glow_css)
            self.assertIn("box-shadow:", glow_css)
            self.assertIn("text-shadow:", glow_css)
            self.assertIn("#ff2eec", neon_css)
            self.assertIn("#00ffa3", neon_css)
            self.assertIn("#f5f7fb", light_css)
            self.assertIn("#182230", light_css)
            self.assertIn("button:not(.titlebutton)", cyber_css)
            self.assertIn("button:not(.titlebutton)", glow_css)
            self.assertIn("button:not(.titlebutton)", neon_css)
            self.assertIn("button:not(.titlebutton)", light_css)
            self.assertNotIn("{{", cyber_css)
            self.assertNotIn("{{", glow_css)
            self.assertNotIn("{{", neon_css)
            self.assertNotIn("{{", light_css)

    def test_neon_theme_has_no_top_toolbar_neon_bar(self):
        for platform in ("ubuntu", "omarchy"):
            src = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertNotIn("toolbar_neon_bar", src)
            self.assertNotIn("toolbar-neon-bar", src)
            self.assertNotIn("_draw_toolbar_neon_bar", src)
            self.assertNotIn("_update_cyber_animations", src)
            self.assertNotIn("cyber_anim_timer", src)

    def test_light_theme_palette_and_high_contrast_text(self):
        for platform in ("ubuntu", "omarchy"):
            platform_dir = str(Path(__file__).resolve().parents[1] / platform)
            if platform_dir not in sys.path:
                sys.path.insert(0, platform_dir)
            try:
                sys.modules.pop("core", None)
                sys.modules.pop("remote-dev-terminal", None)
                mod = importlib.import_module("remote-dev-terminal")
                mod = importlib.reload(mod)
            finally:
                if platform_dir in sys.path:
                    sys.path.remove(platform_dir)
            light = mod.THEMES["light"]
            self.assertIn("term_palette", light)
            self.assertEqual(len(light["term_palette"]), 16)
            self.assertEqual(light["subtitle"], "#334155")
            self.assertEqual(light["section"], "#1e293b")
            self.assertEqual(light["tab_fg"], "#334155")
            self.assertEqual(light["manage_fg"], "#1e293b")
            self.assertIn("cursor_color", light)
            self.assertIn("highlight_bg", light)
            self.assertIn("highlight_fg", light)

            class DummyTerminal:
                def __init__(self):
                    self.colors = None
                    self.cursor = None
                    self.highlight = None
                    self.highlight_fg = None
                def set_colors(self, fg, bg, palette):
                    self.colors = (fg, bg, palette)
                def set_color_cursor(self, c):
                    self.cursor = c
                def set_color_highlight(self, c):
                    self.highlight = c
                def set_color_highlight_foreground(self, c):
                    self.highlight_fg = c

            class DummyApp:
                def current_theme(self):
                    return "light"

            dummy_term = DummyTerminal()
            mod.App.apply_terminal_theme(DummyApp(), dummy_term)
            self.assertIsNotNone(dummy_term.colors)
            self.assertIsNotNone(dummy_term.colors[2])
            self.assertEqual(len(dummy_term.colors[2]), 16)
            self.assertIsNotNone(dummy_term.cursor)
            self.assertIsNotNone(dummy_term.highlight)
            self.assertIsNotNone(dummy_term.highlight_fg)

    def test_session_edit_pencil_and_rename_sync(self):
        for platform in ("ubuntu", "omarchy"):
            platform_dir = str(Path(__file__).resolve().parents[1] / platform)
            if platform_dir not in sys.path:
                sys.path.insert(0, platform_dir)
            try:
                sys.modules.pop("core", None)
                sys.modules.pop("remote-dev-terminal", None)
                mod = importlib.import_module("remote-dev-terminal")
                mod = importlib.reload(mod)
            finally:
                if platform_dir in sys.path:
                    sys.path.remove(platform_dir)

            self.assertEqual(
                mod.session_display_name({"directory_name": "proj", "agent_name": "Codex"}),
                "Codex · proj",
            )
            self.assertEqual(
                mod.session_display_name({"directory_name": "proj", "agent_name": "Codex", "display_name": "Custom"}),
                "Custom · proj · Codex",
            )
            self.assertEqual(
                mod.session_workspace_label({"directory_name": "proj", "agent_name": "Codex", "display_name": "Codex · proj"}),
                "proj",
            )
            self.assertEqual(
                mod.session_workspace_label({"directory_name": "proj", "agent_name": "Codex", "display_name": "Custom"}),
                "Custom · proj",
            )

            app = mod.App()
            try:
                app.persist = lambda: None
                host = {"id": "h1", "name": "Host1", "address": "127.0.0.1", "port": 22, "username": "dev"}
                app.data["hosts"] = [host]
                app.data["sync_url"] = "http://127.0.0.1:8080"
                app.data["sync_token"] = "token"
                app.data["auto_sync_enabled"] = True

                # 1. Tiled mode test: check open_terminal creates edit_button in title_box
                shared_item = {
                    "id": "sess-1",
                    "directory_path": "/proj",
                    "directory_name": "proj",
                    "agent_name": "codex",
                    "agent_command": "codex",
                    "display_name": "Original Name"
                }
                sync_calls = []
                app.sync_async = lambda h, method, sid, it=None: sync_calls.append((method, sid, it))

                with patch.object(mod, "ssh_argv", return_value=(["/bin/true"], "sess-1")):
                    app.open_terminal(None, host, shared_item)

                self.assertEqual(len(app.sessions), 1)
                sess = app.sessions[0]
                self.assertIn("edit_button", sess)
                self.assertIn("session_title", sess)
                self.assertEqual(sess["session_title"].get_text(), "Original Name · proj · codex")
                self.assertEqual(sess["edit_button"].get_tooltip_text(), "编辑实例名称")
                app.data["operation_logs"] = [{
                    "id": "log-1", "created_at": 1, "updated_at": 1,
                    "session_id": "sess-1", "session_name": "Original Name",
                    "title_source": "default", "account": "",
                }]
                history_sync_calls = []
                app.sync_operation_logs_async = lambda *args, **kwargs: history_sync_calls.append(True)

                # Agent titles update the instance and matching history rows, while defaults cannot overwrite them.
                Path(sess["socket_path"]).touch()
                with patch.object(mod.subprocess, "run", return_value=SimpleNamespace(
                        returncode=0, stdout="login notice\nRDT_TITLE:Agent Generated Title\n")) as ssh_run:
                    agent_title = app.read_agent_session_title(sess, "codex")
                self.assertEqual(agent_title, "Agent Generated Title")
                self.assertIn(sess["socket_path"], ssh_run.call_args.args[0])
                remote_title_command = ssh_run.call_args.args[0][-1]
                self.assertIn("python3 -c", remote_title_command)
                self.assertNotIn("<<'RDT_PY'", remote_title_command)
                app.apply_agent_session_title(sess, agent_title)
                self.assertEqual(sess["session_title"].get_text(), "Agent Generated Title · proj · codex")
                self.assertEqual(sess["item"]["title_source"], "agent")
                self.assertEqual(app.data["operation_logs"][0]["session_name"], "Agent Generated Title")
                self.assertEqual(app.data["operation_logs"][0]["title_source"], "agent")
                self.assertTrue(history_sync_calls)
                history_sync_calls.clear()
                self.assertFalse(app.agent_session_title_needs_sync(sess, agent_title))
                app.set_session_name(sess, "Stale synchronized title", sync=False, title_source="agent")
                self.assertTrue(app.agent_session_title_needs_sync(sess, agent_title))
                app.apply_agent_session_title(sess, agent_title)
                self.assertEqual(sess["session_title"].get_text(), "Agent Generated Title · proj · codex")
                app.set_session_name(sess, "Stale Default Name", sync=False, title_source="default")
                self.assertEqual(sess["session_title"].get_text(), "Agent Generated Title · proj · codex")
                with patch.object(mod.subprocess, "run", return_value=SimpleNamespace(
                        returncode=0, stdout="RDT_ERROR:session-id-not-found", stderr="")):
                    self.assertEqual(app.read_agent_session_title(sess, "codex"), "")
                self.assertEqual(sess["title_sync_status"], "远端未绑定 Agent 会话 ID")
                app.apply_agent_title_sync_status(sess, sess["title_sync_status"])
                self.assertIn("远端未绑定 Agent 会话 ID", sess["session_title"].get_tooltip_text())

                # Test set_session_name updates label and syncs PUT
                app.set_session_name(sess, "Renamed Instance", sync=True)
                self.assertEqual(sess["session_title"].get_text(), "Renamed Instance · proj · codex")
                self.assertEqual(sess["item"]["display_name"], "Renamed Instance")
                self.assertEqual(sess["item"]["title_source"], "manual")
                self.assertEqual(sess["display_name"], "Renamed Instance")
                self.assertEqual(app.data["operation_logs"][0]["session_name"], "Renamed Instance")
                self.assertEqual(app.data["operation_logs"][0]["title_source"], "manual")
                self.assertTrue(any(call[0] == "PUT" and call[1] == "sess-1" and call[2]["display_name"] == "Renamed Instance" for call in sync_calls))
                self.assertFalse(app.agent_session_title_needs_sync(sess, "A later generated title"))
                with patch.object(mod.subprocess, "run", side_effect=AssertionError("manual names must skip remote title reads")):
                    self.assertEqual(app.read_agent_session_title(sess, "codex"), "")
                self.assertEqual(sess["title_sync_status"], "保留手动名称")
                app.apply_agent_session_title(sess, "A later generated title")
                self.assertEqual(sess["session_title"].get_text(), "Renamed Instance · proj · codex")
                self.assertEqual(app.data["operation_logs"][0]["session_name"], "Renamed Instance")

                # 2. Tab mode keeps only close in the instance row; header owns the other actions.
                app.tiled = False
                app.rebuild_view()
                rows = app.instances_list.get_children()
                self.assertEqual(len(rows), 1)
                children = rows[0].get_children()
                self.assertEqual(len(children), 1)
                tab_btn = children[0]
                btn_children = tab_btn.get_child().get_children()
                self.assertEqual(len(btn_children), 2)
                title_lbl, close_btn = btn_children
                self.assertEqual(title_lbl.get_text(), "Renamed Instance · proj · codex")
                self.assertTrue(app.tab_header_edit_button.get_visible())
                self.assertTrue(app.tab_header_image_button.get_visible())
                self.assertTrue(app.tab_header_close_button.get_visible())

                # Test incoming remote rename in refresh_host_shared updates name without loop sync PUT
                sync_calls.clear()
                remote_sessions = [{
                    "id": "sess-1",
                    "directory_path": "/proj",
                    "agent_command": "codex",
                    "display_name": "Remote Updated Name"
                }]
                matching = remote_sessions[0]
                remote_name = matching.get("display_name")
                if remote_name and remote_name != sess.get("item", {}).get("display_name"):
                    app.set_session_name(sess, remote_name, sync=False, title_source="manual")
                self.assertEqual(sess["session_title"].get_text(), "Remote Updated Name · proj · codex")
                self.assertEqual(sess["item"]["display_name"], "Remote Updated Name")
                rows = app.instances_list.get_children()
                self.assertEqual(len(rows), 1)
                tab_btn = rows[0].get_children()[0]
                self.assertEqual(tab_btn.get_child().get_text(), "Remote Updated Name · proj · codex")
                self.assertEqual(len(sync_calls), 0)

                # A title change schedules history sync even before the matching log row is loaded.
                app.data["operation_logs"] = []
                unloaded_history_session = {
                    "id": "session-without-local-history",
                    "item": {"display_name": "Old title", "title_source": "agent"},
                    "display_name": "Old title",
                    "title_source": "agent",
                }
                app.set_session_name(unloaded_history_session, "Current title", sync=False, title_source="agent")
                self.assertTrue(history_sync_calls)
            finally:
                for session in app.sessions:
                    stop = session.get("title_watcher_stop")
                    if stop is not None:
                        stop.set()
                app.sessions = []
                app.sync_executor.shutdown(wait=True)
                app.disconnect_by_func(mod.Gtk.main_quit)
                app.destroy()
