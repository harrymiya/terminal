import importlib.util
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]


def load_modules(folder):
    core_spec = importlib.util.spec_from_file_location("core", ROOT / folder / "core.py")
    core = importlib.util.module_from_spec(core_spec)
    core_spec.loader.exec_module(core)
    with mock.patch.dict(sys.modules, {"core": core}):
        spec = importlib.util.spec_from_file_location(f"{folder}_host_metrics", ROOT / folder / "host_metrics.py")
        metrics = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(metrics)
    return metrics


class HostMetricsTest(unittest.TestCase):
    def test_parse_rejects_invalid_or_missing_measurements(self):
        for folder in ("omarchy", "ubuntu", "windows"):
            metrics = load_modules(folder)
            self.assertEqual(metrics.parse_metrics("login banner\nRDT_METRICS 19.5 81.25\n"), (19.5, 81.25))
            for output in ("", "RDT_METRICS 10", "RDT_METRICS nan 20", "RDT_METRICS 101 10", "RDT_METRICS -1 10"):
                self.assertIsNone(metrics.parse_metrics(output), (folder, output))

    def test_linux_sampler_produces_interval_measurements(self):
        metrics = load_modules("omarchy")
        if not Path("/proc/meminfo").exists():
            self.skipTest("Linux /proc required")
        output = subprocess.check_output(["sh", "-c", metrics.REMOTE_COMMAND], text=True, timeout=5)
        result = metrics.parse_metrics(output)
        self.assertIsNotNone(result, output)
        self.assertTrue(all(0 <= value <= 100 for value in result))

    def test_ssh_is_noninteractive_and_checks_host_key(self):
        for folder in ("omarchy", "ubuntu", "windows"):
            metrics = load_modules(folder)
            host = {"id": "1", "username": "user", "_resolved": True, "_active_address": "example", "_active_port": 22}
            with mock.patch.object(metrics.subprocess, "check_output", return_value="RDT_METRICS 25 90\n") as run:
                self.assertEqual(metrics.read_host_metrics(host), (25.0, 90.0))
            argv = run.call_args.args[0]
            self.assertIn("StrictHostKeyChecking=yes", argv)
            self.assertIn("BatchMode=yes", argv)
            self.assertEqual(run.call_args.kwargs["env"]["RDT_SSH_NONINTERACTIVE"], "1")
            self.assertEqual(run.call_args.kwargs["timeout"], 12)


if __name__ == "__main__":
    unittest.main()
