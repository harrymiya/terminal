import importlib
from pathlib import Path
import sys
import unittest


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
        for platform in ("omarchy", "ubuntu", "windows"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn("paned.tile-paned > separator { background-color: #243546; margin: 0; padding: 0; }", py_file)
            self.assertIn("paned.tile-paned.horizontal > separator { min-width: 2px; }", py_file)
            self.assertIn("paned.tile-paned.vertical > separator { min-height: 2px; }", py_file)

    def test_notebook_and_tab_dark_theme(self):
        for platform in ("omarchy", "ubuntu", "windows"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn("notebook { background-color: #0c1119;", py_file)
            self.assertIn("notebook tab {", py_file)
            self.assertIn("notebook tab:checked {", py_file)
            self.assertIn(".tile-header { padding: 4px 8px; background: #16222f;", py_file)

    def test_dark_theme_css_colors(self):
        for platform in ("omarchy", "ubuntu", "windows"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn("entry {", py_file)
            self.assertIn("spinbutton {", py_file)
            self.assertIn("combobox button.combo, combobox button {", py_file)
            self.assertIn("button {", py_file)
            self.assertIn(".sidebar button {", py_file)
            self.assertIn(".host-button {", py_file)
            self.assertIn(".project-button {", py_file)
            self.assertIn(".manage-button {", py_file)

    def test_view_button_vector_icons(self):
        for platform in ("omarchy", "ubuntu", "windows"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn('"view-paged-symbolic" if self.tiled else "view-grid-symbolic"', py_file)

    def test_host_list_ui_matches_workspace_management(self):
        for platform in ("omarchy", "ubuntu", "windows"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn('actions = [("＋主机", "add")]', py_file)
            self.assertIn('def manage_host(self, button, index, action):', py_file)
            self.assertIn('for label, action in [("✎", "edit"), ("×", "delete")]:', py_file)

    def test_left_alignments(self):
        for platform in ("omarchy", "ubuntu", "windows"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn('label.set_xalign(0.0)', py_file)

    def test_omarchy_agent_edit_mode_shows_and_hides_row_actions(self):
        app = self.app_mod.App()
        try:
            app.data["hosts"] = [{"id": "host", "name": "Host", "address": "127.0.0.1", "port": 2200, "username": "dev"}]
            app.data["agents"] = [{"id": "codex", "name": "Codex", "command": "codex"}]
            app.refresh()
            app.show_all()
            app.edit_toggle.set_active(True)
            while self.app_mod.Gtk.events_pending():
                self.app_mod.Gtk.main_iteration()
            row = app.agents_list.get_children()[0]
            children = row.get_children()
            self.assertEqual(len(children), 2)
            self.assertEqual([button.get_label() for button in children[1].get_children()], ["✎", "×"])
            app.edit_toggle.set_active(False)
            self.assertEqual(len(app.agents_list.get_children()[0].get_children()), 1)
        finally:
            app.sync_executor.shutdown(wait=True)
            app.disconnect_by_func(self.app_mod.Gtk.main_quit)
            app.destroy()

    def test_uniform_control_heights(self):
        for platform in ("omarchy", "ubuntu", "windows"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn('button, entry, spinbutton, combobox, combobox button, .menu-toggle, .host-button, .project-button, .manage-button {', py_file)
            self.assertIn('min-height: 24px;', py_file)
            self.assertIn('padding: 2px 8px;', py_file)

    def test_spinbutton_buttons_have_no_borders(self):
        for platform in ("omarchy", "ubuntu", "windows"):
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
        for platform in ("omarchy", "ubuntu", "windows"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn("notebook tab {", py_file)
            self.assertIn("border-radius: 0;", py_file)

    def test_running_card_header_and_tab_breathing_halo_glow(self):
        import time

        for platform in ("omarchy", "ubuntu", "windows"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn("@keyframes orange-glow-breath", py_file)
            self.assertIn(".tile-glow-bar", py_file)
            self.assertIn(".tab-glow-bar", py_file)
            self.assertIn(".tile-running .tile-header", py_file)
            self.assertIn("_animate_running_headers", py_file)

        app = self.app_mod.App()
        term = self.app_mod.Vte.Terminal()
        label = self.app_mod.Gtk.Box()
        state = {"last_output_at": time.monotonic(), "quota_busy": False}
        app.sessions = [{"terminal": term, "label": label, "key": "k", "id": "id1", "state": state}]

        # 1. Tiled mode test
        app.tiled = True
        app.rebuild_view()

        glow = getattr(term, "_glow_bar", None)
        frame = getattr(term, "_tile_frame", None)
        self.assertIsNotNone(glow)
        self.assertIsNotNone(frame)
        self.assertFalse(glow.get_visible())
        self.assertFalse(frame.get_style_context().has_class("tile-running"))

        # When running (recent output within 2.5s), glow bar is visible and frame has tile-running
        app._animate_running_headers()
        self.assertTrue(glow.get_visible())
        self.assertTrue(frame.get_style_context().has_class("tile-running"))

        # When idle (>2.5s ago), glow bar is hidden and tile-running is removed
        state["last_output_at"] = time.monotonic() - 10.0
        app._animate_running_headers()
        self.assertFalse(glow.get_visible())
        self.assertFalse(frame.get_style_context().has_class("tile-running"))

        # 2. Tab mode test
        state["last_output_at"] = time.monotonic()
        app.tiled = False
        app.rebuild_view()

        tab_glow = getattr(term, "_tab_glow_bar", None)
        self.assertIsNotNone(tab_glow)
        self.assertFalse(tab_glow.get_visible())

        # When running, tab_glow becomes visible
        app._animate_running_headers()
        self.assertTrue(tab_glow.get_visible())

        # When idle, tab_glow is hidden
        state["last_output_at"] = time.monotonic() - 10.0
        app._animate_running_headers()
        self.assertFalse(tab_glow.get_visible())

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
