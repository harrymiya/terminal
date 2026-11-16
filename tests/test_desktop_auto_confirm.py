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
        self.assertIsNotNone(sig("Do you want to proceed? [y/N]: "))
        self.assertIsNotNone(sig("Continue installation? (y/n)? "))
        self.assertIsNotNone(sig("Apply changes? [Yes/no]"))
        self.assertIsNotNone(sig("Press enter to continue"))
        self.assertIsNotNone(sig("按回车继续"))
        self.assertIsNotNone(sig("按 Enter 确认"))

        # Tmux status bar at bottom should not break prompt detection
        tmux_screen = "Do you want to proceed? [Y/n]: \n[rdt_7fa83d7d99994cec85] 0:bash* \"remote\" 18:05:30"
        self.assertIsNotNone(sig(tmux_screen))

        # Interactive arrow/bullet selections
        text_approve = "Would you like to run this command?\n❯ Yes, approve"
        self.assertIsNotNone(sig(text_approve))
        text_bullet = "Would you like to run this command?\n● 1. Yes (approve)\n○ 2. No"
        self.assertIsNotNone(sig(text_bullet))

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
                    "prompt_detected_at": 0.0,
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
                    if self.state["confirm_attempts"] < 3:
                        self.state["confirm_attempts"] += 1
                        self.feed_calls.append(b"\r")
                        self.state["secondary_timer"] = GLib.timeout_add(40, secondary_check)
                    else:
                        self.state["handled_signature"] = latest_sig
                    return False

                self.state["secondary_timer"] = GLib.timeout_add(40, secondary_check)

            def do_primary_confirm(self, sig):
                self.state["timer"] = None
                if self.state["closed"] or not self.state["connected"] or not self.auto_enter_active:
                    return False
                current_sig = self.visible_signature()
                if not current_sig:
                    self.state["signature"] = None
                    self.state["confirm_attempts"] = 0
                    self.state["handled_signature"] = None
                    return False
                self.state["signature"] = current_sig
                self.state["confirm_attempts"] = 1
                self.feed_calls.append(b"\r")
                self.schedule_secondary_check(current_sig)
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
                if self.state["signature"] is None:
                    self.state["signature"] = sig
                    self.state["confirm_attempts"] = 0
                    self.state["handled_signature"] = None
                    self.state["timer"] = GLib.timeout_add(30, lambda s=sig: self.do_primary_confirm(s))
                else:
                    if self.state["secondary_timer"] is not None:
                        return
                    if self.state["timer"] is None and self.state["confirm_attempts"] == 0:
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

    def test_watchdog_and_contents_changed_do_not_starve_timer(self):
        # Verify that frequent contents-changed or watchdog checks (e.g. clock ticking or spinners)
        # do not indefinitely postpone the confirmation deadline.
        import time

        class ActiveSession:
            def __init__(self):
                self.feed_calls = []
                self.current_screen_text = "Proceed? [y/N]"
                self.auto_enter_active = True
                self.delay_sec = 0.05
                self.state = {
                    "closed": False,
                    "connected": True,
                    "timer": None,
                    "secondary_timer": None,
                    "signature": None,
                    "prompt_detected_at": 0.0,
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

            def do_primary_confirm(self, sig):
                self.state["timer"] = None
                if self.state["closed"] or not self.state["connected"] or not self.auto_enter_active:
                    return False
                cur = self.current_screen_text
                if not cur:
                    return False
                self.state["confirm_attempts"] = 1
                self.feed_calls.append(b"\r")
                return False

            def check_prompt(self):
                if not self.state["connected"] or not self.auto_enter_active or self.state["closed"]:
                    return
                sig = self.current_screen_text
                if not sig:
                    return
                now = time.monotonic()
                if self.state["signature"] is None:
                    self.state["signature"] = sig
                    self.state["prompt_detected_at"] = now
                    self.state["timer"] = GLib.timeout_add(
                        int(self.delay_sec * 1000), lambda s=sig: self.do_primary_confirm(s)
                    )
                else:
                    if self.state["timer"] is not None:
                        if now - self.state.get("prompt_detected_at", now) >= self.delay_sec:
                            self.cancel_confirm_timers()
                            self.do_primary_confirm(sig)
                        return
                    if self.state["confirm_attempts"] == 0:
                        elapsed = now - self.state.get("prompt_detected_at", now)
                        if elapsed >= self.delay_sec:
                            self.do_primary_confirm(sig)
                        else:
                            remain = max(1, int((self.delay_sec - elapsed) * 1000))
                            self.state["timer"] = GLib.timeout_add(
                                remain, lambda s=sig: self.do_primary_confirm(s)
                            )

        session = ActiveSession()
        session.check_prompt()

        # Simulate frequent watchdog & contents-changed calls every 10ms with slightly changing text
        def tick(count=[0]):
            count[0] += 1
            session.current_screen_text = f"Proceed? [y/N] (elapsed {count[0]}0ms)"
            session.check_prompt()
            return count[0] < 8

        GLib.timeout_add(10, tick)

        loop = GLib.MainLoop()
        GLib.timeout_add(150, loop.quit)
        loop.run()

        self.assertGreaterEqual(
            len(session.feed_calls), 1, "Timer should fire despite frequent check_prompt updates"
        )
        self.assertEqual(session.feed_calls[0], b"\r")


if __name__ == "__main__":
    unittest.main()
