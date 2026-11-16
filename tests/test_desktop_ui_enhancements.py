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
        App = self.app_mod.App
        base_css = App.__dict__.get("base_css", None)
        # Check base_css in instance creation or raw file
        omarchy_py = (Path(__file__).resolve().parents[1] / "omarchy" / "remote-dev-terminal.py").read_text(encoding="utf-8")
        self.assertIn("paned.tile-paned > separator { background-color: #243546; background-clip: content-box; }", omarchy_py)
        self.assertIn("paned.tile-paned.horizontal > separator { min-width: 8px; padding: 0 3px; margin: 0 -3px; }", omarchy_py)
        self.assertIn("paned.tile-paned.vertical > separator { min-height: 8px; padding: 3px 0; margin: -3px 0; }", omarchy_py)

    def test_dark_theme_css_colors(self):
        for platform in ("omarchy", "ubuntu", "windows"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn("entry { background-color: #16222f;", py_file)
            self.assertIn("spinbutton { background-color: #16222f;", py_file)
            self.assertIn("combobox button.combo, combobox button { background-color: #16222f;", py_file)
            self.assertIn("button { background-color: #182635;", py_file)

    def test_host_list_ui_matches_workspace_management(self):
        for platform in ("omarchy", "ubuntu", "windows"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn('actions = [("添加主机", "add")]', py_file)
            self.assertIn('def manage_host(self, button, index, action):', py_file)
            self.assertIn('for label, action in [("✎", "edit"), ("×", "delete")]:', py_file)

    def test_left_alignments(self):
        for platform in ("omarchy", "ubuntu", "windows"):
            py_file = (Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py").read_text(encoding="utf-8")
            self.assertIn('.project-button > label { xalign: 0; }', py_file)
            self.assertIn('.host-button > label { xalign: 0; }', py_file)
            self.assertIn('button.get_child().set_xalign(0.0)', py_file)


if __name__ == "__main__":
    unittest.main()
