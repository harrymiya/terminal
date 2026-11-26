import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec_omarchy = importlib.util.spec_from_file_location("desktop_core_omarchy", Path(__file__).resolve().parents[1] / "omarchy" / "core.py")
core_omarchy = importlib.util.module_from_spec(spec_omarchy)
spec_omarchy.loader.exec_module(core_omarchy)

spec_ubuntu = importlib.util.spec_from_file_location("desktop_core_ubuntu", Path(__file__).resolve().parents[1] / "ubuntu" / "core.py")
core_ubuntu = importlib.util.module_from_spec(spec_ubuntu)
spec_ubuntu.loader.exec_module(core_ubuntu)


class ImageUploadTest(unittest.TestCase):
    def test_upload_writes_exact_bytes_and_returns_private_unique_path(self):
        for core in (core_omarchy, core_ubuntu):
            with self.subTest(core=core.__name__):
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
        for core in (core_omarchy, core_ubuntu):
            with self.subTest(core=core.__name__):
                host = {"port": 22, "username": "dev", "address": "example"}
                with patch.object(core.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"", b"failed")):
                    with self.assertRaisesRegex(RuntimeError, "failed"):
                        core.upload_image(host, "socket", b"image")
                with patch.object(core.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b"not a path", b"")):
                    with self.assertRaises(RuntimeError):
                        core.upload_image(host, "socket", b"image")
                with self.assertRaises(ValueError):
                    core.upload_image(host, "socket", b"")


class FileUploadTest(unittest.TestCase):
    def test_file_upload_writes_exact_bytes_and_preserves_extension(self):
        for core in (core_omarchy, core_ubuntu):
            with self.subTest(core=core.__name__):
                with tempfile.TemporaryDirectory(prefix="remote home ") as home:
                    calls = []
                    host = {"port": 22, "username": "dev", "address": "example"}
                    payload = b"hello remote file world"
                    real_run = subprocess.run
                    def fake_ssh(argv, **kwargs):
                        calls.append(argv)
                        return real_run(["/bin/sh", "-c", argv[-1]], input=kwargs["input"],
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                        env={**os.environ, "HOME": home}, timeout=5)
                    with patch.object(core.subprocess, "run", side_effect=fake_ssh):
                        first = core.upload_file(host, "/tmp/socket", payload, "test.txt")
                        second = core.upload_file(host, "/tmp/socket", payload, "another.TXT")
                    self.assertNotEqual(first, second)
                    self.assertTrue(first.endswith(".txt"))
                    self.assertTrue(second.endswith(".txt"))
                    self.assertIn("/.remote-dev-terminal/files/", first)
                    self.assertEqual(Path(first).read_bytes(), payload)
                    self.assertEqual(Path(first).stat().st_mode & 0o777, 0o600)
                    self.assertIn("ControlMaster=no", calls[0])
                    self.assertIn("BatchMode=yes", calls[0])

    def test_file_upload_sanitizes_extension_and_handles_no_extension(self):
        for core in (core_omarchy, core_ubuntu):
            with self.subTest(core=core.__name__):
                with tempfile.TemporaryDirectory(prefix="remote home ") as home:
                    host = {"port": 22, "username": "dev", "address": "example"}
                    payload = b"payload"
                    real_run = subprocess.run
                    def fake_ssh(argv, **kwargs):
                        return real_run(["/bin/sh", "-c", argv[-1]], input=kwargs["input"],
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                        env={**os.environ, "HOME": home}, timeout=5)
                    with patch.object(core.subprocess, "run", side_effect=fake_ssh):
                        first = core.upload_file(host, "/tmp/socket", payload, "noext")
                        second = core.upload_file(host, "/tmp/socket", payload, "../../bad/path.toolongextensionhere123")
                    self.assertNotIn(".", Path(first).name)
                    self.assertNotIn(".", Path(second).name)

    def test_file_upload_validation_and_failures(self):
        for core in (core_omarchy, core_ubuntu):
            with self.subTest(core=core.__name__):
                host = {"port": 22, "username": "dev", "address": "example"}
                with patch.object(core.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"", b"failed")):
                    with self.assertRaisesRegex(RuntimeError, "failed"):
                        core.upload_file(host, "socket", b"content", "file.txt")
                with self.assertRaises(ValueError):
                    core.upload_file(host, "socket", b"", "file.txt")
                with self.assertRaises(ValueError):
                    core.upload_file(host, "socket", b"x" * (20 * 1024 * 1024 + 1), "file.txt")

    def test_format_file_size(self):
        for core in (core_omarchy, core_ubuntu):
            with self.subTest(core=core.__name__):
                self.assertEqual(core.format_file_size(500), "1 KB")
                self.assertEqual(core.format_file_size(2048), "2 KB")
                self.assertEqual(core.format_file_size(1024 * 1024), "1.0 MB")
                self.assertEqual(core.format_file_size(int(2.5 * 1024 * 1024)), "2.5 MB")


class DialogUploadTest(unittest.TestCase):
    def test_dialog_has_file_upload_button_and_handles_upload(self):
        try:
            import gi
            gi.require_version("Gtk", "3.0")
            from gi.repository import Gtk, GLib
            if not Gtk.init_check([])[0]:
                self.skipTest("GTK display unavailable")
        except (ImportError, ValueError):
            self.skipTest("GTK unavailable")

        import sys, time
        from unittest.mock import MagicMock

        for platform in ("ubuntu", "omarchy"):
            with self.subTest(platform=platform):
                module_path = Path(__file__).resolve().parents[1] / platform / "remote-dev-terminal.py"
                spec_mod = importlib.util.spec_from_file_location(f"desktop_{platform}", module_path)
                app_module = importlib.util.module_from_spec(spec_mod)
                old_path = list(sys.path)
                sys.modules.pop("core", None)
                try:
                    sys.path.insert(0, str(module_path.parent))
                    spec_mod.loader.exec_module(app_module)
                finally:
                    sys.path = old_path
                    sys.modules.pop("core", None)

                state = {"connected": True, "closed": False, "generation": 1}
                terminal = MagicMock()
                host = {"username": "testuser", "address": "127.0.0.1", "port": 22}
                window = Gtk.Window()
                window.scale_layout = lambda d: None

                with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as f:
                    f.write(b"file content")
                    f.flush()
                    temp_file_path = f.name

                try:
                    dialog_holder = []
                    real_dialog_init = Gtk.Dialog.__init__
                    def capture_init(this, *args, **kwargs):
                        real_dialog_init(this, *args, **kwargs)
                        dialog_holder.append(this)

                    with patch.object(Gtk.Dialog, "__init__", capture_init):
                        app_module.App.send_image_dialog(window, host, "/tmp/test.sock", terminal, state)

                    self.assertEqual(len(dialog_holder), 1)
                    dlg = dialog_holder[0]
                    self.assertEqual(dlg.get_title(), "发送图片或文件给 Agent")

                    buttons = []
                    def find_buttons(widget):
                        if isinstance(widget, Gtk.Button):
                            buttons.append(widget)
                        if isinstance(widget, Gtk.Container):
                            for child in widget.get_children():
                                find_buttons(child)
                    find_buttons(dlg.get_content_area())

                    labels = [b.get_label() for b in buttons if b.get_label()]
                    self.assertIn("选择本地图片…", labels)
                    self.assertIn("选择文件…", labels)
                    self.assertIn("粘贴图片", labels)

                    file_btn = next(b for b in buttons if b.get_label() == "选择文件…")
                    with patch.object(Gtk.FileChooserDialog, "run", return_value=Gtk.ResponseType.OK), \
                         patch.object(Gtk.FileChooserDialog, "get_filename", return_value=temp_file_path):
                        file_btn.clicked()

                    self_ok = dlg.get_widget_for_response(Gtk.ResponseType.OK)
                    self.assertTrue(self_ok.get_sensitive())

                    with patch.object(app_module, "upload_file", return_value="/remote/files/target.txt") as mock_upload:
                        dlg.response(Gtk.ResponseType.OK)
                        for _ in range(50):
                            while GLib.main_context_default().iteration(False):
                                pass
                            time.sleep(0.01)

                        mock_upload.assert_called_once_with(host, "/tmp/test.sock", b"file content", os.path.basename(temp_file_path))
                        terminal.paste_text.assert_called_once()
                        sent = terminal.paste_text.call_args[0][0]
                        self.assertIn("请查看文件", sent)
                        self.assertIn(os.path.basename(temp_file_path), sent)
                        self.assertIn("/remote/files/target.txt", sent)
                        terminal.feed_child.assert_called_once_with(b"\r")
                finally:
                    os.unlink(temp_file_path)


if __name__ == "__main__":
    unittest.main()
