import importlib.util
from pathlib import Path
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]


def load_core(folder):
    spec = importlib.util.spec_from_file_location(f"{folder}_core_quota_test", ROOT / folder / "core.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class DesktopQuotaTest(unittest.TestCase):
    def test_agy_and_agy2_quota_agent_and_reset_days(self):
        future_weekly = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 3 * 86400 + 3600))
        future_5h = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 7200))
        sample = {
            "command": {
                "data": {
                    "groups": [
                        {
                            "buckets": [
                                {"id": "gemini-5h", "remaining_fraction": 0.75, "reset_time": future_5h},
                                {"id": "gemini-weekly", "remaining_fraction": 0.4, "reset_time": future_weekly},
                            ]
                        }
                    ]
                }
            }
        }
        for folder in ("omarchy", "ubuntu", "windows"):
            core = load_core(folder)
            self.assertEqual(core.quota_agent("agy"), ("agy", "agy"), folder)
            self.assertEqual(core.quota_agent("agy2"), ("agy", "agy2"), folder)
            self.assertEqual(core.quota_agent("/opt/bin/agy2 --resume"), ("agy", "/opt/bin/agy2"), folder)
            parsed = dict((m, (pct, days)) for m, pct, days in core.quota_from_agy(sample))
            self.assertEqual(parsed[300], (75, "<1d"), folder)
            self.assertEqual(parsed[10080], (40, "3d"), folder)


if __name__ == "__main__":
    unittest.main()
