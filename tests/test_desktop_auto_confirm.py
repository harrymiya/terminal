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

    def test_long_confirmation_requests_are_detected(self):
        long_diff = "\n".join(f"  + line {i}" for i in range(60))
        long_footer = "\n".join(f"  option/hint line {i}" for i in range(25))
        cases = (
            f"Would you like to run this command?\n{long_diff}\n❯ 1. Yes, proceed\n  2. No",
            f"Do you want to make this edit?\n❯ 1. Yes\n  2. Yes, allow all\n{long_footer}",
            f"{long_diff}\n❯ 1. Yes, proceed\n  2. No",
            f"Continue with deployment? [Y/n]\n{long_footer[:200]}",
        )
        for platform in ('omarchy', 'ubuntu', 'windows'):
            with self.subTest(platform=platform):
                detect = load_core(platform).auto_enter_signature
                for text in cases:
                    self.assertIsNotNone(detect(text), text[:80])

    def test_codex_approval_uses_the_visible_one_time_choice(self):
        body = "\n".join(f"$ command argument {i}" for i in range(65))
        menu = (
            "\n› 1. Yes, grant these\n"
            "     permissions for this turn (y)\n"
            "  2. Yes, grant these permissions for this session\n"
            "  3. No, continue without permissions\n"
            "Press enter\n"
            "to confirm or esc to cancel\n"
            "[rdt_1234] 0:codex*"
        )
        for platform in ('omarchy', 'ubuntu', 'windows'):
            with self.subTest(platform=platform):
                detect = load_core(platform).auto_enter_signature
                prompt = body + menu
                signature = detect(prompt, codex=True)
                self.assertIsNotNone(signature)
                self.assertEqual(detect("• Working (12s)\n" + prompt, codex=True), signature)
                self.assertNotEqual(detect(prompt.replace("argument 64", "argument 99"), codex=True), signature)
                for selection in (
                    menu.replace("› 1.", "  1.").replace("  2.", "› 2."),
                    menu.replace("› 1.", "  1.").replace("  3.", "› 3."),
                    menu.replace("› 1.", "  1."),
                    "\n› 1. Yes\n  2. No\nPress enter to confirm or esc to cancel",
                    "\n› 1. Yes, provide the requested info\n  2. No\nPress enter to confirm or esc to cancel",
                ):
                    self.assertIsNone(detect(body + selection, codex=True))

    def test_codex_sessions_still_handle_ordinary_confirmations(self):
        prior_menu = (
            "Would you like to run the following command?\n"
            "  1. Yes, proceed\n"
            "› 2. No\n"
            "Press enter to confirm or esc to cancel\n"
        )
        for platform in ('omarchy', 'ubuntu', 'windows'):
            with self.subTest(platform=platform):
                detect = load_core(platform).auto_enter_signature
                for text in (
                    "Press Enter to confirm",
                    "Press enter to continue",
                    "按回车继续",
                    "Do you trust the contents of this directory?\n› 1. Yes, I trust this folder\n  2. No\nPress enter to continue",
                    "Permission required\n● 1. Allow once\n○ 2. No",
                    "Would you like to run this command?\n❯ 1. Allow once\n  2. No\nPress Enter to confirm",
                    prior_menu + "Continue? [Y/n]",
                ):
                    self.assertIsNotNone(detect(text, codex=True), text)
                for text in (
                    prior_menu + "Continue? [y/N]",
                    "Continue? [y/n]",
                    "Would you like to run the following command?\n  1. Yes, proceed\n› 2. Yes, and don't ask again\nPress enter to confirm",
                ):
                    self.assertIsNone(detect(text, codex=True), text)

    def test_auto_enter_delay_accepts_tenths_and_clamps_to_one_tenth(self):
        for platform in ('omarchy', 'ubuntu', 'windows'):
            with self.subTest(platform=platform):
                controller = load_core(platform).AutoEnterController()
                for delay in (0.1, 0.01):
                    controller.reset()
                    self.assertFalse(controller.observe('approval', 10, delay))
                    self.assertFalse(controller.observe('approval', 10.099, delay))
                    self.assertTrue(controller.observe('approval', 10.1, delay))
                    self.assertFalse(controller.observe('approval', 10.2, delay))


if __name__ == '__main__':
    unittest.main()
