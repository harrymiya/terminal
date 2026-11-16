import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("desktop_core", Path(__file__).resolve().parents[1] / "omarchy" / "core.py")
core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(core)


class ImageUploadTest(unittest.TestCase):
    def test_upload_writes_exact_bytes_and_returns_private_unique_path(self):
        with tempfile.TemporaryDirectory(prefix="remote home ") as home:
            calls = []
            host = {"port": 22, "username": "dev", "address": "example"}
            payload = b"\x89PNG\r\n\x1a\n\x00test"
            real_run = subprocess.run
            def fake_ssh(argv, **kwargs):
                calls.append(argv)
                return real_run(["/bin/sh", "-c", argv[-1]], input=kwargs["input"],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                env={**os.environ, "HOME": home}, timeout=5)
            with patch.object(core.subprocess, "run", side_effect=fake_ssh):
                first = core.upload_image(host, "/tmp/socket", payload)
                second = core.upload_image(host, "/tmp/socket", payload)
            self.assertNotEqual(first, second)
            self.assertEqual(Path(first).read_bytes(), payload)
            self.assertEqual(Path(second).read_bytes(), payload)
            self.assertEqual(Path(first).stat().st_mode & 0o777, 0o600)
            self.assertIn("ControlMaster=no", calls[0])
            self.assertIn("BatchMode=yes", calls[0])

    def test_failure_and_invalid_result_do_not_return_a_path(self):
        host = {"port": 22, "username": "dev", "address": "example"}
        with patch.object(core.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"", b"failed")):
            with self.assertRaisesRegex(RuntimeError, "failed"):
                core.upload_image(host, "socket", b"image")
        with patch.object(core.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b"not a path", b"")):
            with self.assertRaises(RuntimeError):
                core.upload_image(host, "socket", b"image")
        with self.assertRaises(ValueError):
            core.upload_image(host, "socket", b"")


if __name__ == "__main__":
    unittest.main()
