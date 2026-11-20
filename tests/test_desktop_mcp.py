import importlib.util
import io
import json
from pathlib import Path
import sys
import threading
import time
import unittest
from unittest.mock import patch
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def load_module(folder, filename, mod_name):
    folder_path = str(ROOT / folder)
    if folder_path not in sys.path:
        sys.path.insert(0, folder_path)
    try:
        spec = importlib.util.spec_from_file_location(mod_name, ROOT / folder / filename)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod
    finally:
        if sys.path and sys.path[0] == folder_path:
            sys.path.pop(0)


omarchy_mcp = load_module("omarchy", "mcp_server.py", "omarchy_mcp_test")
ubuntu_mcp = load_module("ubuntu", "mcp_server.py", "ubuntu_mcp_test")


def load_gtk_app(folder):
    folder_path = str(ROOT / folder)
    sys.path.insert(0, folder_path)
    # Ensure each platform imports its own core/mcp_server/version cleanly.
    for cached in ("core", "mcp_server", "version"):
        sys.modules.pop(cached, None)
    try:
        spec = importlib.util.spec_from_file_location(f"{folder}_app_mcp_test", ROOT / folder / "remote-dev-terminal.py")
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod
    except (ImportError, ValueError):
        return None
    finally:
        if sys.path and sys.path[0] == folder_path:
            sys.path.pop(0)


class MCPProtocolUnitTest(unittest.TestCase):
    def test_omarchy_and_ubuntu_mcp_tools_match(self):
        self.assertEqual(
            [t["name"] for t in omarchy_mcp.TOOLS],
            [t["name"] for t in ubuntu_mcp.TOOLS],
        )
        for tool in omarchy_mcp.TOOLS:
            self.assertIn("name", tool)
            self.assertIn("description", tool)
            self.assertIn("inputSchema", tool)

    def test_jsonrpc_lifecycle_and_stdio_bridge(self):
        init_res = omarchy_mcp.handle_jsonrpc_request(
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
            lambda *_: {},
            version="1.2.3",
        )
        self.assertEqual(init_res["result"]["protocolVersion"], omarchy_mcp.MCP_PROTOCOL_VERSION)
        self.assertEqual(init_res["result"]["serverInfo"]["version"], "1.2.3")

        notif_res = omarchy_mcp.handle_jsonrpc_request(
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            lambda *_: {},
        )
        self.assertIsNone(notif_res)

        list_res = omarchy_mcp.handle_jsonrpc_request(
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
            lambda *_: {},
        )
        self.assertGreaterEqual(len(list_res["result"]["tools"]), 25)

        err_res = omarchy_mcp.handle_jsonrpc_request(
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "boom", "arguments": {}}},
            lambda *_: (_ for _ in ()).throw(ValueError("bad arg")),
        )
        self.assertTrue(err_res["result"]["isError"])
        self.assertIn("bad arg", err_res["result"]["content"][0]["text"])

        # Test stdio bridge parsing & forwarding
        stdin = io.StringIO(
            json.dumps({"jsonrpc": "2.0", "id": 10, "method": "ping"}) + "\n"
            + json.dumps({"jsonrpc": "2.0", "id": 11, "method": "tools/call", "params": {"name": "get_app_state", "arguments": {}}}) + "\n"
        )
        stdout = io.StringIO()
        fake_http_resp = {"jsonrpc": "2.0", "id": 1, "result": {"content": [{"type": "text", "text": "{\"ok\": true}"}], "isError": False}}
        with patch.object(sys, "stdin", stdin), patch.object(sys, "stdout", stdout), \
             patch.object(omarchy_mcp, "_post_json", return_value=fake_http_resp):
            omarchy_mcp.run_stdio_bridge()
        lines = [json.loads(line) for line in stdout.getvalue().strip().splitlines()]
        self.assertEqual(lines[0]["id"], 10)
        self.assertEqual(lines[0]["result"], {})
        self.assertEqual(lines[1]["id"], 11)
        self.assertFalse(lines[1]["result"]["isError"])
        self.assertIn('"ok": true', lines[1]["result"]["content"][0]["text"])


class DesktopMCPIntegrationTest(unittest.TestCase):
    def _post_mcp(self, url, method, params=None, req_id=1):
        payload = {"jsonrpc": "2.0", "id": req_id, "method": method, "params": params or {}}
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=5) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def _call_tool(self, url, name, arguments=None):
        res = self._post_mcp(url, "tools/call", {"name": name, "arguments": arguments or {}})
        result = res["result"]
        if result.get("isError"):
            self.fail(f"Tool {name} failed: {result['content'][0]['text']}")
        return json.loads(result["content"][0]["text"])

    def _run_platform_e2e(self, folder):
        app_module = load_gtk_app(folder)
        if app_module is None or not app_module.Gtk.init_check([])[0]:
            self.skipTest(f"GTK display unavailable for {folder}")

        Gtk = app_module.Gtk
        GLib = app_module.GLib

        # Run Gtk.main() in a background thread? No, Gtk must run on the thread that owns it;
        # we run the test assertions in a worker thread while Gtk.main() runs on the main thread!
        config = {"hosts": [], "agents": [{"name": "Shell", "command": "bash"}]}
        with patch.dict("os.environ", {"RDT_MCP_PORT": "0"}), \
             patch.object(app_module, "load_config", return_value=config), \
             patch.object(app_module, "save_config"), \
             patch.object(app_module, "ssh_address", side_effect=lambda h, force=False: h.get("lan_address") or h.get("external_address") or ""), \
             patch.object(app_module.App, "refresh_all_shared"):
            app = app_module.App()
            app.show_all()
            url = f"http://127.0.0.1:{app.mcp_server.port}/mcp"
            errors = []

            def test_worker():
                try:
                    # 1. Health endpoint
                    health_url = f"http://127.0.0.1:{app.mcp_server.port}/health"
                    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(health_url, timeout=5) as resp:
                        health = json.loads(resp.read().decode("utf-8"))
                    self.assertTrue(health["ok"])
                    self.assertEqual(health["platform"], folder)

                    # 2. Add host, agent, workspace and verify live UI updates
                    added = self._call_tool(url, "add_host", {
                        "name": "DevBox",
                        "username": "dev",
                        "lan_address": "10.0.0.8",
                        "lan_port": 22,
                    })
                    self.assertEqual(added["host"]["name"], "DevBox")
                    self.assertEqual(app.hosts.get_active(), 0)

                    self._call_tool(url, "add_agent", {"name": "Codex", "command": "codex"})
                    self._call_tool(url, "add_workspace", {"name": "repo", "path": "/home/dev/repo"})

                    state = self._call_tool(url, "get_app_state")
                    self.assertEqual(len(state["hosts"]), 1)
                    self.assertEqual(state["hosts"][0]["directories"][0]["path"], "/home/dev/repo")

                    # 3. UI controls update live widgets
                    self._call_tool(url, "set_view_mode", {"tiled": False})
                    self.assertFalse(app.tiled)
                    self._call_tool(url, "set_view_mode", {"tiled": True})
                    self.assertTrue(app.tiled)

                    self._call_tool(url, "set_tile_layout", {"layout": "columns"})
                    self.assertEqual(app.tile_layout, "columns")

                    self._call_tool(url, "toggle_sidebar", {"visible": False, "edit_mode": True})
                    self.assertFalse(app.sidebar_revealer.get_reveal_child())
                    self.assertTrue(app.edit_mode)

                    self._call_tool(url, "set_ui_scale", {"scale": 120})
                    self.assertEqual(app.scale, 120)

                    self._call_tool(url, "update_settings", {"auto_enter": True, "auto_enter_delay": 7000})
                    self.assertTrue(app.auto_enter.get_active())
                    self.assertEqual(app.delay.get_value_as_int(), 7000)

                    # 4. Open terminal (mock spawn_async so no real SSH runs), send/read VTE text, close
                    with patch.object(app_module.Vte.Terminal, "spawn_async"):
                        opened = self._call_tool(url, "open_terminal", {"workspace_name": "repo", "agent_name": "Codex"})
                    sid = opened["session"]["session_id"]
                    self.assertEqual(len(app.sessions), 1)

                    # Feed output into VTE on main thread and read it back via MCP
                    def feed_output():
                        app.sessions[0]["terminal"].feed(b"ready> hello from agent\r\n")
                        return False
                    done_feed = threading.Event()
                    GLib.idle_add(lambda: (feed_output(), done_feed.set(), False)[2])
                    done_feed.wait(5)

                    screen = {"text": ""}
                    for _ in range(20):
                        screen = self._call_tool(url, "read_terminal_screen", {"session_id": sid})
                        if "hello from agent" in screen.get("text", ""):
                            break
                        time.sleep(0.05)
                    self.assertIn("hello from agent", screen["text"])

                    with patch.object(app.sessions[0]["terminal"], "feed_child") as mock_feed:
                        self._call_tool(url, "send_terminal_text", {"session_id": sid, "text": "ls -la", "submit": True})
                        self.assertEqual(mock_feed.call_args_list[0][0][0], b"ls -la")
                        self.assertEqual(mock_feed.call_args_list[1][0][0], b"\r")

                        self._call_tool(url, "send_terminal_keys", {"session_id": sid, "keys": ["Ctrl+C", "Enter"]})
                        self.assertEqual(mock_feed.call_args_list[2][0][0], b"\x03\r")

                    closed = self._call_tool(url, "close_terminal", {"session_id": sid})
                    self.assertEqual(closed["remaining_sessions"], 0)
                    self.assertEqual(len(app.sessions), 0)

                    # 5. Cleanup host
                    self._call_tool(url, "delete_host", {"host_name": "DevBox"})
                    self.assertEqual(len(app.data["hosts"]), 0)
                except Exception as exc:
                    errors.append(exc)
                finally:
                    GLib.idle_add(lambda: (app.destroy(), False)[1])

            t = threading.Thread(target=test_worker, daemon=True)
            t.start()
            Gtk.main()
            t.join(timeout=10)
            if errors:
                raise errors[0]

    def test_omarchy_mcp_end_to_end(self):
        self._run_platform_e2e("omarchy")

    def test_ubuntu_mcp_end_to_end(self):
        self._run_platform_e2e("ubuntu")


if __name__ == "__main__":
    unittest.main()
