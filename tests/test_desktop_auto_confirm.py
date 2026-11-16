import importlib
from pathlib import Path
import sys
import unittest
import gi
gi.require_version("Gtk", "3.0")
from gi.repository import Gtk, GLib


class DesktopAutoConfirmTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        omarchy_dir = str(Path(__file__).resolve().parents[1] / "omarchy")
        if omarchy_dir not in sys.path:
            sys.path.insert(0, omarchy_dir)
        cls.core_mod = importlib.import_module("core")
        cls.app_mod = importlib.import_module("remote-dev-terminal")

    def test_auto_enter_signature_patterns(self):
        sig = self.core_mod.auto_enter_signature
        # Standard yes/no prompts with colon or question marks
        self.assertIsNotNone(sig("Do you want to proceed? [Y/n]"))
        self.assertIsNotNone(sig("Do you want to proceed? [Y/n]: "))
        self.assertIsNotNone(sig("Continue installation? (Y/n)? "))
        self.assertIsNotNone(sig("Apply changes? [Yes/no]"))
        self.assertIsNotNone(sig("Press enter to continue"))
        self.assertIsNotNone(sig("按回车继续"))
        self.assertIsNotNone(sig("按 Enter 确认"))

        # Interactive arrow selections
        text_approve = "Would you like to run this command?\n❯ Yes, approve"
        self.assertIsNotNone(sig(text_approve))

        # Rejection or non-prompt
        self.assertIsNone(sig("Just standard output from a build\nDone in 2.5s"))
        self.assertIsNone(sig("Would you like to run this command?\n❯ No, cancel"))

    def test_multi_session_independent_secondary_confirmation(self):
        # Verify that secondary check and secondary confirmation work as expected,
        # and two sessions run independently without interfering with each other.

        class MockSession:
            def __init__(self, name):
                self.name = name
                self.feed_calls = []
                self.current_screen_text = ""
                self.auto_enter_active = True
                self.delay = 1
                self.state = {
                    "closed": False,
                    "connected": True,
                    "timer": None,
                    "secondary_timer": None,
                    "watchdog_timer": None,
                    "signature": None,
                    "confirm_attempts": 0,
                    "handled_signature": None,
                }

            def cancel_confirm_timers(self):
                if self.state["timer"]:
                    GLib.source_remove(self.state["timer"])
                    self.state["timer"] = None
                if self.state["secondary_timer"]:
                    GLib.source_remove(self.state["secondary_timer"])
                    self.state["secondary_timer"] = None

            def visible_signature(self):
                return self.current_screen_text if self.current_screen_text else None

            def schedule_secondary_check(self, target_sig):
                if self.state["closed"] or not self.state["connected"]:
                    return
                if self.state["secondary_timer"]:
                    GLib.source_remove(self.state["secondary_timer"])
                    self.state["secondary_timer"] = None

                def secondary_check():
                    self.state["secondary_timer"] = None
                    if self.state["closed"] or not self.state["connected"] or not self.auto_enter_active:
                        return False
                    latest_sig = self.visible_signature()
                    if not latest_sig:
                        self.state["signature"] = None
                        self.state["confirm_attempts"] = 0
                        self.state["handled_signature"] = None
                        return False
                    if latest_sig != target_sig:
                        self.state["signature"] = None
                        self.state["confirm_attempts"] = 0
                        self.check_prompt()
                        return False
                    if self.state["confirm_attempts"] < 3:
                        self.state["confirm_attempts"] += 1
                        self.feed_calls.append(b"\r")
                        self.state["secondary_timer"] = GLib.timeout_add(40, secondary_check)
                    else:
                        self.state["handled_signature"] = target_sig
                    return False

                self.state["secondary_timer"] = GLib.timeout_add(40, secondary_check)

            def do_primary_confirm(self, sig):
                self.state["timer"] = None
                if self.state["closed"] or not self.state["connected"] or not self.auto_enter_active:
                    return False
                current_sig = self.visible_signature()
                if current_sig != sig:
                    if current_sig:
                        self.check_prompt()
                    return False
                self.state["confirm_attempts"] = 1
                self.feed_calls.append(b"\r")
                self.schedule_secondary_check(sig)
                return False

            def check_prompt(self):
                if not self.state["connected"] or not self.auto_enter_active or self.state["closed"]:
                    return
                sig = self.visible_signature()
                if not sig:
                    if self.state["signature"]:
                        self.cancel_confirm_timers()
                        self.state["signature"] = None
                        self.state["confirm_attempts"] = 0
                        self.state["handled_signature"] = None
                    return
                if sig == self.state["handled_signature"]:
                    return
                if sig != self.state["signature"]:
                    self.cancel_confirm_timers()
                    self.state["signature"] = sig
                    self.state["confirm_attempts"] = 0
                    self.state["handled_signature"] = None
                    self.state["timer"] = GLib.timeout_add(30, lambda s=sig: self.do_primary_confirm(s))
                elif self.state["timer"] is None and self.state["secondary_timer"] is None and self.state["confirm_attempts"] == 0:
                    self.state["timer"] = GLib.timeout_add(30, lambda s=sig: self.do_primary_confirm(s))

        session1 = MockSession("s1")
        session2 = MockSession("s2")

        # Session 1 has a prompt that gets stuck (e.g. first CR didn't advance it)
        session1.current_screen_text = "Prompt 1 [Y/n]"
        session1.check_prompt()

        # Session 2 has a prompt that advances/clears after first CR
        session2.current_screen_text = "Prompt 2 [Y/n]"
        session2.check_prompt()

        def step_clear_s2():
            # Session 2 prompt cleared after primary confirm
            session2.current_screen_text = ""
            return False

        GLib.timeout_add(45, step_clear_s2)

        loop = GLib.MainLoop()
        GLib.timeout_add(250, loop.quit)
        loop.run()

        # Session 1 was stuck on the same prompt, so secondary confirm was issued
        self.assertGreaterEqual(len(session1.feed_calls), 2, "Session 1 should have performed secondary confirm")
        self.assertEqual(session1.feed_calls[0], b"\r")
        self.assertEqual(session1.feed_calls[1], b"\r")

        # Session 2 cleared immediately after primary confirm, so it only received 1 CR
        self.assertEqual(len(session2.feed_calls), 1, "Session 2 should only confirm once when prompt cleared")
        self.assertEqual(session2.feed_calls[0], b"\r")
        self.assertIsNone(session2.state["signature"])


if __name__ == "__main__":
    unittest.main()
