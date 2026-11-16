import importlib
from pathlib import Path
import re
import sys
import unittest


class DesktopScalingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        omarchy_dir = str(Path(__file__).resolve().parents[1] / "omarchy")
        if omarchy_dir not in sys.path:
            sys.path.insert(0, omarchy_dir)
        cls.app_mod = importlib.import_module("remote-dev-terminal")

    def make_dummy_app(self, scale=100):
        app_mod = self.app_mod

        class DummyApp:
            sidebar_target_width = app_mod.App.sidebar_target_width
            terminal_font_scale = app_mod.App.terminal_font_scale
            apply_scale_css = app_mod.App.apply_scale_css

            def __init__(self, scale):
                self.scale = scale
                self.css = app_mod.Gtk.CssProvider()
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
                    .workspace-title { font-weight: bold; font-size: 15px; }
                    .tile-header { padding: 5px 8px; background: #1b2735; }
                    .empty { color: #91a5b8; font-size: 15px; }
                '''

        return DummyApp(scale)

    def test_terminal_font_scale_protected_when_shrunk(self):
        app = self.make_dummy_app(50)
        self.assertGreaterEqual(app.terminal_font_scale(), 0.85)
        self.assertLessEqual(app.terminal_font_scale(), 0.90)

        app100 = self.make_dummy_app(100)
        self.assertEqual(app100.terminal_font_scale(), 1.0)

        app150 = self.make_dummy_app(150)
        self.assertEqual(app150.terminal_font_scale(), 1.5)

    def test_sidebar_width_protected_when_shrunk(self):
        app50 = self.make_dummy_app(50)
        self.assertGreaterEqual(app50.sidebar_target_width(), 200)

        app100 = self.make_dummy_app(100)
        self.assertEqual(app100.sidebar_target_width(), 250)

        app200 = self.make_dummy_app(200)
        self.assertEqual(app200.sidebar_target_width(), 500)

    def test_css_font_sizes_never_illegible(self):
        # Test CSS generated across scale ranges from 50 to 90
        for scale in [50, 60, 70, 80, 90]:
            app = self.make_dummy_app(scale)
            # Capture the CSS string passed to load_from_data
            loaded = []
            app.css.load_from_data = lambda data: loaded.append(data.decode("utf-8"))
            app.apply_scale_css()
            self.assertTrue(loaded, f"CSS not loaded for scale {scale}")
            css_text = loaded[0]

            for line in css_text.splitlines():
                if "font-size:" in line:
                    match = re.search(r"font-size:\s*(\d+)px", line)
                    if match:
                        size = int(match.group(1))
                        # No font in the UI should drop below 11px
                        self.assertGreaterEqual(
                            size, 11,
                            f"Font size {size}px in '{line.strip()}' at scale {scale}% is too small"
                        )
                if "min-height:" in line:
                    match = re.search(r"min-height:\s*(\d+)px", line)
                    if match:
                        height = int(match.group(1))
                        # Button min-height should allow text to display without clipping
                        self.assertGreaterEqual(
                            height, 22,
                            f"min-height {height}px in '{line.strip()}' at scale {scale}% is too small"
                        )


if __name__ == "__main__":
    unittest.main()
