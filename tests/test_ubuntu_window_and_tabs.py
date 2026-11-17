import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

UBUNTU = Path(__file__).resolve().parents[1] / 'ubuntu'
sys.path.insert(0, str(UBUNTU))
try:
    spec = importlib.util.spec_from_file_location('ubuntu_desktop_regression', UBUNTU / 'remote-dev-terminal.py')
    app_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(app_module)
except (ImportError, ValueError):
    app_module = None


@unittest.skipIf(app_module is None, 'GTK unavailable')
class UbuntuWindowAndTabsTest(unittest.TestCase):
    def setUp(self):
        if not app_module.Gtk.init_check([])[0]:
            self.skipTest('GTK display unavailable')
        with patch.object(app_module, 'load_config', return_value={'hosts': [], 'agents': []}):
            self.app = app_module.App()

    def tearDown(self):
        self.app.disconnect_by_func(app_module.Gtk.main_quit)
        self.app.destroy()

    def test_backdrop_keeps_text_readable(self):
        Gtk = app_module.Gtk
        self.app.show_all()
        def labels(widget):
            if isinstance(widget, Gtk.Label):
                yield widget
            if isinstance(widget, Gtk.Container):
                for child in widget.get_children():
                    yield from labels(child)
        by_text = {label.get_text(): label for label in labels(self.app)}
        for text in ('REMOTE DEV', '添加主机', '刷新同步'):
            color = by_text[text].get_style_context().get_color(Gtk.StateFlags.BACKDROP)
            self.assertGreater(min(color.red, color.green, color.blue), 0.45, text)

    def test_repeated_mode_switch_preserves_header_and_quota(self):
        Gtk = app_module.Gtk
        terminal = app_module.Vte.Terminal()
        header = Gtk.Box()
        name = Gtk.Label(label='project · Codex')
        quota = Gtk.Label(label='余量 50%')
        header.pack_start(name, True, True, 0)
        header.pack_start(quota, False, False, 0)
        self.app.sessions = [{'terminal': terminal, 'label': header, 'key': 'k', 'id': 'id', 'state': {}}]
        self.app.show_all()
        for tiled in (True, False, True, False, True, False):
            self.app.tiled = tiled
            self.app.rebuild_view()
            self.assertEqual(header.get_children(), [name, quota])
            self.assertEqual(quota.get_text(), '余量 50%')
            if not tiled:
                self.assertEqual(self.app.tabs.get_n_pages(), 1)
                self.assertIsNotNone(self.app.tabs.get_tab_label(terminal))
                while Gtk.events_pending():
                    Gtk.main_iteration()
                self.assertTrue(name.get_mapped(), 'tab name must actually be displayed')
                self.assertTrue(quota.get_mapped(), 'tab quota must actually be displayed')

    def test_battery_indicator_shows_readable_text_and_levels(self):
        Gtk = app_module.Gtk
        indicator, update = app_module.make_quota_indicator()
        battery, text = indicator.get_children()
        level = battery.get_children()[0]
        for pct, expected, style in ((None, '--', 'quota-unknown'), (3, '3%', 'quota-low'), (15, '15%', 'quota-warning'), (85, '85%', 'quota-ok')):
            update(pct)
            self.assertEqual(text.get_text(), expected)
            self.assertEqual(level.get_value(), pct or 0)
            self.assertTrue(level.get_style_context().has_class(style))
        self.assertIsInstance(level, Gtk.LevelBar)


if __name__ == '__main__':
    unittest.main()
