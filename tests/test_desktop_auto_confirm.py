import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


def load_core(platform):
    spec = importlib.util.spec_from_file_location('auto_enter_' + platform, ROOT / platform / 'core.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DesktopAutoConfirmTest(unittest.TestCase):
    def test_prompt_detection_and_negative_selections(self):
        for platform in ('omarchy', 'ubuntu', 'windows'):
            with self.subTest(platform=platform):
                detect = load_core(platform).auto_enter_signature
                for text in (
                    'Do you want to proceed? [Y/n]',
                    'Continue? [Yes/no]:',
                    'Press enter to continue',
                    '按回车继续',
                    '│ Would you like to run this command? │\n│ ❯ 1. Yes, proceed │\n│   2. No │',
                    'Permission required\n● 1. Allow once\n○ 2. No',
                ):
                    self.assertIsNotNone(detect(text), text)
                for text in (
                    'Continue? [y/N]', 'Continue? [y/n]',
                    'Would you like to run this command?\n❯ No, cancel\nPress enter to confirm',
                    'build finished',
                    'Would you like to run this command?\n❯ No\n  Yes',
                ):
                    self.assertIsNone(detect(text), text)

    def test_each_prompt_is_delayed_once_and_manual_action_cancels(self):
        for platform in ('omarchy', 'ubuntu', 'windows'):
            with self.subTest(platform=platform):
                controller = load_core(platform).AutoEnterController()
                self.assertFalse(controller.observe('first', 0, 3))
                self.assertFalse(controller.observe('first', 2.99, 3))
                self.assertTrue(controller.observe('first', 3, 3))
                self.assertFalse(controller.observe('first', 10, 3))
                self.assertFalse(controller.observe('second', 10, 3))
                self.assertFalse(controller.observe('second', 12.9, 3))
                self.assertTrue(controller.observe('second', 13, 3))
                self.assertFalse(controller.observe(None, 14, 3))
                self.assertFalse(controller.observe('first', 15, 3))
                controller.cancel('first')
                self.assertFalse(controller.observe('first', 20, 3))
                self.assertFalse(controller.observe('first', 21, 3, enabled=False))
                self.assertFalse(controller.observe('first', 22, 3))
                self.assertTrue(controller.observe('first', 25, 3))

    def test_prompt_change_restarts_countdown_and_sessions_are_independent(self):
        for platform in ('omarchy', 'ubuntu', 'windows'):
            core = load_core(platform)
            a, b = core.AutoEnterController(), core.AutoEnterController()
            self.assertFalse(a.observe('A', 0, 3))
            self.assertFalse(b.observe('B', 1, 3))
            self.assertFalse(a.observe('C', 2.9, 3))
            self.assertFalse(a.observe('C', 3, 3))
            self.assertTrue(b.observe('B', 4, 3))
            self.assertTrue(a.observe('C', 5.9, 3))

    def test_vte_visible_screen_is_detected(self):
        try:
            import gi
            gi.require_version('Gtk', '3.0')
            gi.require_version('Vte', '2.91')
            from gi.repository import Gtk, Vte, GLib
        except (ImportError, ValueError):
            self.skipTest('GTK/VTE not installed')
        if not Gtk.init_check([])[0]:
            self.skipTest('GTK display not available')
        window = Gtk.Window()
        terminal = Vte.Terminal()
        window.add(terminal)
        window.show_all()
        terminal.feed('│ Would you like to run this command? │\r\n│ ❯ 1. Yes, proceed │\r\n│ 2. No │'.encode())
        loop = GLib.MainLoop()
        GLib.timeout_add(100, lambda: (loop.quit(), False)[1])
        loop.run()
        screen = terminal.get_text_format(Vte.Format.TEXT)
        for platform in ('omarchy', 'ubuntu', 'windows'):
            self.assertIsNotNone(load_core(platform).auto_enter_signature(screen))
        window.destroy()


if __name__ == '__main__':
    unittest.main()
