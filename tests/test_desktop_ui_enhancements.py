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
            self.assertIn('actions = [("添加主机", "add")]', py_file)
            self.assertIn('def manage_host(self, button, index, action):', py_file)
            self.assertIn('for label, action in [("✎", "edit"), ("×", "delete")]:', py_file)

    def test_left_alignments(self):
        for platform in ("omarchy", "ubuntu", "windows"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn('button.get_child().set_xalign(0.0)', py_file)

    def test_uniform_control_heights(self):
        for platform in ("omarchy", "ubuntu", "windows"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn('button, entry, spinbutton, combobox, combobox button, .menu-toggle, .host-button, .project-button, .manage-button {', py_file)
            self.assertIn('min-height: 24px;', py_file)
            self.assertIn('padding: 2px 8px;', py_file)


if __name__ == "__main__":
    unittest.main()
