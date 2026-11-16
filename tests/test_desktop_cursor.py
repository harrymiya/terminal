import importlib
from pathlib import Path
import sys
import unittest


class DesktopCursorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        omarchy_dir = str(Path(__file__).resolve().parents[1] / "omarchy")
        if omarchy_dir not in sys.path:
            sys.path.insert(0, omarchy_dir)
        cls.app_mod = importlib.import_module("remote-dev-terminal")
        if not cls.app_mod.Gtk.init_check([])[0]:
            raise unittest.SkipTest("GTK display not available in headless environment (run under xvfb)")

    def test_apply_hand_cursor_on_button(self):
        Gtk = self.app_mod.Gtk
        btn = Gtk.Button(label="Click")
        self.app_mod.App.apply_hand_cursor(btn)
        win = Gtk.Window()
        win.add(btn)
        win.show_all()
        ew = btn.get_event_window()
        self.assertIsNotNone(ew)
        self.assertIsNotNone(ew.get_cursor())
        win.destroy()

    def test_apply_hand_cursor_on_toggle_and_check(self):
        Gtk = self.app_mod.Gtk
        box = Gtk.Box()
        tog = Gtk.ToggleButton(label="Toggle")
        chk = Gtk.CheckButton(label="Check")
        box.pack_start(tog, False, False, 0)
        box.pack_start(chk, False, False, 0)
        self.app_mod.App.apply_hand_cursor(tog)
        self.app_mod.App.apply_hand_cursor(chk)
        win = Gtk.Window()
        win.add(box)
        win.show_all()
        self.assertIsNotNone(tog.get_event_window().get_cursor())
        self.assertIsNotNone(chk.get_event_window().get_cursor())
        win.destroy()

    def test_apply_hand_cursor_on_combobox(self):
        Gtk = self.app_mod.Gtk
        combo = Gtk.ComboBoxText()
        combo.append_text("Item 1")
        self.app_mod.App.apply_hand_cursor(combo)
        win = Gtk.Window()
        win.add(combo)
        win.show_all()
        alloc = combo.get_allocation()
        parent_win = combo.get_window()
        found = False
        for cw in parent_win.get_children():
            geom = cw.get_geometry()
            if (abs(geom.x - alloc.x) <= 2 and abs(geom.y - alloc.y) <= 2 and
                    abs(geom.width - alloc.width) <= 2 and abs(geom.height - alloc.height) <= 2):
                self.assertIsNotNone(cw.get_cursor())
                found = True
                break
        self.assertTrue(found, "ComboBox child event window cursor not set")
        win.destroy()

    def test_scale_layout_recursively_applies_hand_cursor(self):
        Gtk = self.app_mod.Gtk
        box = Gtk.Box()
        btn = Gtk.Button(label="Inside Box")
        box.pack_start(btn, False, False, 0)
        win = Gtk.Window()
        win.add(box)

        # Mock dummy app with scale_layout
        class DummyApp:
            scale = 100
            apply_hand_cursor = staticmethod(self.app_mod.App.apply_hand_cursor)
            scale_layout = self.app_mod.App.scale_layout

        app = DummyApp()
        app.scale_layout(win)
        win.show_all()
        self.assertIsNotNone(btn.get_event_window().get_cursor())
        win.destroy()


if __name__ == "__main__":
    unittest.main()
