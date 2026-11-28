import os
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

# Pop core so tests can import cleanly
sys.modules.pop("core", None)
for p in ("ubuntu", "omarchy"):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / p))

from ubuntu.core import filter_operation_logs as ubuntu_filter_logs
from omarchy.core import filter_operation_logs as omarchy_filter_logs


class HostSwitchingTest(unittest.TestCase):
    def test_filter_operation_logs_by_host(self):
        logs = [
            {"id": "1", "account": "alice", "created_at": 100, "host_id": "h1", "host_name": "Host1"},
            {"id": "2", "account": "alice", "created_at": 200, "host_id": "h2", "host_name": "Host2"},
            {"id": "3", "account": "alice", "created_at": 300, "host_id": "h1", "host_name": "Host1"},
            {"id": "4", "account": "bob", "created_at": 400, "host_id": "h1", "host_name": "Host1"},
        ]
        for filter_fn in (ubuntu_filter_logs, omarchy_filter_logs):
            # Filter by account "alice" and host "h1"
            res = filter_fn(logs, account="alice", host={"id": "h1", "name": "Host1"})
            self.assertEqual(len(res), 2)
            self.assertEqual([x["id"] for x in res], ["3", "1"])  # sorted by created_at desc

            # Filter by account "alice" and host "h2"
            res_h2 = filter_fn(logs, account="alice", host={"id": "h2", "name": "Host2"})
            self.assertEqual(len(res_h2), 1)
            self.assertEqual(res_h2[0]["id"], "2")

            # Filter with no host (all hosts for alice)
            res_all = filter_fn(logs, account="alice", host=None)
            self.assertEqual(len(res_all), 3)

    def test_session_matches_host_recreated_id_same_host(self):
        import ast
        source = (Path(__file__).resolve().parents[1] / "ubuntu" / "remote-dev-terminal.py").read_text()
        tree = ast.parse(source)
        cls = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "App")
        method = next(node for node in cls.body if isinstance(node, ast.FunctionDef) and node.name == "session_matches_host")
        isolated = ast.Module(body=[ast.ClassDef(name="Probe", bases=[], keywords=[], body=[method], decorator_list=[])], type_ignores=[])
        namespace = {}
        exec(compile(ast.fix_missing_locations(isolated), "<session-match>", "exec"), namespace)
        app = namespace["Probe"]()
        old_host = {"id": "deleted-id", "name": "xk", "username": "agent", "address": "114.55.232.229"}
        replacement = {"id": "new-id", "name": "xk", "username": "agent", "address": "114.55.232.229"}
        self.assertTrue(app.session_matches_host({"host": old_host}, replacement))

    def test_session_matches_host_logic(self):
        host1 = {"id": "h1", "name": "Host1", "username": "user1", "address": "192.168.1.10"}
        host2 = {"id": "h2", "name": "Host2", "username": "user2", "address": "192.168.1.20"}

        session_h1 = {"id": "s1", "host": host1}
        session_h2 = {"id": "s2", "host": host2}
        session_h1_copy = {"id": "s3", "host": {"id": "h1", "name": "Host1"}}

        # Test with dummy class implementing session_matches_host
        class DummyApp:
            def session_matches_host(self, session, host):
                if not session or not host:
                    return False
                shost = session.get("host")
                if not shost or not isinstance(shost, dict):
                    return False
                if shost is host:
                    return True
                sid = str(shost.get("id", "")).strip()
                hid = str(host.get("id", "")).strip()
                if sid and hid and sid == hid:
                    return True
                sync_id = str(shost.get("sync_id", "")).strip()
                h_sync_id = str(host.get("sync_id", "")).strip()
                if sync_id and h_sync_id and sync_id == h_sync_id:
                    return True
                s_name = str(shost.get("name", "")).strip()
                h_name = str(host.get("name", "")).strip()
                if s_name and h_name and s_name == h_name:
                    return True
                s_addr = str(shost.get("address") or shost.get("lan_address") or shost.get("external_address") or "").strip()
                h_addr = str(host.get("address") or host.get("lan_address") or host.get("external_address") or "").strip()
                s_user = str(shost.get("username", "")).strip()
                h_user = str(host.get("username", "")).strip()
                if s_addr and h_addr and s_addr == h_addr and s_user == h_user:
                    return True
                return False

        app = DummyApp()
        self.assertTrue(app.session_matches_host(session_h1, host1))
        self.assertFalse(app.session_matches_host(session_h1, host2))
        self.assertTrue(app.session_matches_host(session_h2, host2))
        self.assertFalse(app.session_matches_host(session_h2, host1))
        self.assertTrue(app.session_matches_host(session_h1_copy, host1))

    def test_gtk_host_switch_isolation(self):
        import gi
        gi.require_version("Gtk", "3.0")
        from gi.repository import Gtk
        if not Gtk.init_check([])[0]:
            raise unittest.SkipTest("GTK display not available")

        for platform in ("ubuntu", "omarchy"):
            sys.modules.pop("core", None)
            sys.modules.pop("remote-dev-terminal", None)
            mod_path = str(Path(__file__).resolve().parents[1] / platform)
            if mod_path not in sys.path:
                sys.path.insert(0, mod_path)
            rdt = __import__("remote-dev-terminal")

            # Check that App class has session_matches_host
            self.assertTrue(hasattr(rdt.App, "session_matches_host"))

            # Test host_agents returns host's specific agents
            host_a = {"id": "ha", "name": "HostA", "agents": [{"name": "AgentA", "command": "cmd_a"}]}
            host_b = {"id": "hb", "name": "HostB", "agents": [{"name": "AgentB", "command": "cmd_b"}]}
            dummy_app = MagicMock()
            dummy_app.data = {"agents": [{"name": "DefaultAgent", "command": "default"}]}
            agents_a = rdt.App.host_agents(dummy_app, host_a)
            agents_b = rdt.App.host_agents(dummy_app, host_b)
            self.assertEqual(agents_a[0]["name"], "AgentA")
            self.assertEqual(agents_b[0]["name"], "AgentB")

    def test_full_app_host_switching_flow(self):
        import gi
        gi.require_version("Gtk", "3.0")
        gi.require_version("Vte", "2.91")
        from gi.repository import Gtk, Vte
        if not Gtk.init_check([])[0]:
            raise unittest.SkipTest("GTK display not available")

        for platform in ("ubuntu", "omarchy"):
            sys.modules.pop("core", None)
            sys.modules.pop("remote-dev-terminal", None)
            mod_path = str(Path(__file__).resolve().parents[1] / platform)
            if mod_path not in sys.path:
                sys.path.insert(0, mod_path)
            rdt = __import__("remote-dev-terminal")

            test_config = {
                "tiled": False,
                "hosts": [
                    {
                        "id": "h1",
                        "name": "Host1",
                        "username": "user1",
                        "address": "10.0.0.1",
                        "port": 22,
                        "selected_agent": 1,
                        "agents": [
                            {"name": "Codex-H1", "command": "codex"},
                            {"name": "OpenCode-H1", "command": "opencode"},
                        ],
                        "directories": [{"name": "proj1", "path": "/home/user1/proj1"}],
                    },
                    {
                        "id": "h2",
                        "name": "Host2",
                        "username": "user2",
                        "address": "10.0.0.2",
                        "port": 22,
                        "selected_agent": 0,
                        "agents": [
                            {"name": "Antigravity-H2", "command": "agy"},
                            {"name": "Shell-H2", "command": "bash"},
                        ],
                        "directories": [{"name": "proj2", "path": "/home/user2/proj2"}],
                    },
                ],
                "agents": [{"name": "DefaultAgent", "command": "bash"}],
                "operation_logs": [
                    {"id": "l1", "host_id": "h1", "host_name": "Host1", "created_at": 100},
                    {"id": "l2", "host_id": "h2", "host_name": "Host2", "created_at": 200},
                ],
            }

            with patch.object(rdt, "load_config", return_value=test_config), \
                 patch.object(rdt, "save_config", return_value=None), \
                 patch.dict(os.environ, {"RDT_MCP_DISABLED": "1"}):
                app = rdt.App()
                app.scale_layout = lambda *_: None

                # Verify initial active host is host 0 ("Host1")
                self.assertEqual(app.current_host()["name"], "Host1")
                # Host 1 has selected_agent = 1 ("OpenCode-H1")
                self.assertEqual(app.agents.get_active(), 1)

                # Create dummy sessions for Host 1 and Host 2
                term1 = Vte.Terminal()
                btn1 = Gtk.Button(label="min1")
                sess1 = {
                    "id": "s1",
                    "host": test_config["hosts"][0],
                    "terminal": term1,
                    "label": Gtk.Box(),
                    "state": {"connected": False},
                    "minimized_button": None,
                    "floating_window": None,
                    "item": {"directory_name": "proj1", "directory_path": "/home/user1/proj1", "display_name": "OpenCode · proj1"},
                    "agent": test_config["hosts"][0]["agents"][1],
                }

                term2 = Vte.Terminal()
                btn2 = Gtk.Button(label="min2")
                sess2 = {
                    "id": "s2",
                    "host": test_config["hosts"][1],
                    "terminal": term2,
                    "label": Gtk.Box(),
                    "state": {"connected": False},
                    "minimized_button": btn2,
                    "floating_window": None,
                    "item": {"directory_name": "proj2", "directory_path": "/home/user2/proj2", "display_name": "Antigravity · proj2"},
                    "agent": test_config["hosts"][1]["agents"][0],
                }

                app.sessions = [sess1, sess2]
                app.minimized_buttons.pack_start(btn2, False, False, 0)

                # Rebuild view on Host 1
                app.rebuild_view()

                # On Host 1:
                # 1. tabs should only contain term1, not term2
                self.assertEqual(app.tabs.get_n_pages(), 1)
                self.assertEqual(app.tabs.get_nth_page(0), term1)

                # 2. minimized scroller for Host 1 should NOT show btn2 (which belongs to Host 2)
                self.assertFalse(btn2.get_visible())

                # 3. count_label should show 1 session for Host 1
                self.assertEqual(app.count_label.get_text(), "1 / 8")

                # Switch host to Host 2
                app.hosts.set_active(1)

                # Verify developer switched to Host 2's developers and selected_agent (0 = "Antigravity-H2")
                self.assertEqual(app.current_host()["name"], "Host2")
                self.assertEqual(app.agents.get_active(), 0)
                active_agent_name = app.agents.get_model()[app.agents.get_active()][0]
                self.assertEqual(active_agent_name, "Antigravity-H2")

                # On Host 2:
                # 1. sess2 is minimized, so tabs should have 0 docked pages
                self.assertEqual(app.tabs.get_n_pages(), 0)

                # 2. btn2 belongs to Host 2, so it should now be visible!
                self.assertTrue(btn2.get_visible())

                # 3. count_label should show 1 session for Host 2
                self.assertEqual(app.count_label.get_text(), "1 / 8")

                # Test floating window visibility toggling when switching hosts
                floating_mock = MagicMock()
                sess1["floating_window"] = floating_mock
                app.rebuild_view()
                # On Host 2, sess1 is on Host 1, so floating window should be hidden
                floating_mock.set_visible.assert_called_with(False)

                # Switch back to Host 1
                app.hosts.set_active(0)
                self.assertEqual(app.current_host()["name"], "Host1")
                floating_mock.set_visible.assert_called_with(True)

                # Test restore_from_operation_log: restoring log from Host 2 should auto-switch active host
                entry_h2 = {"session_id": "s2", "host_id": "h2", "host_name": "Host2"}
                app.restore_from_operation_log(entry_h2)
                self.assertEqual(app.current_host()["name"], "Host2")

                app.destroy()


class UbuntuDeveloperSyncAndIconTest(unittest.TestCase):
    def test_account_agent_sync_clears_stale_host_agents_and_updates_session_icons_and_names(self):
        import json
        import tempfile
        try:
            import gi
            gi.require_version("Gtk", "3.0")
            gi.require_version("Vte", "2.91")
            from gi.repository import Gtk
        except (ImportError, ValueError):
            self.skipTest("GTK/VTE not available")

        if not Gtk.init_check([])[0]:
            self.skipTest("GTK display not available")

        with tempfile.TemporaryDirectory() as tmpdir:
            config_dir = Path(tmpdir) / ".config" / "remote-dev-terminal"
            config_dir.mkdir(parents=True)
            config_path = config_dir / "config.json"
            stale_agent = {"id": "a1", "name": "Codex", "command": "codex", "icon": "✳"}
            test_config = {
                "hosts": [
                    {
                        "id": "h1",
                        "name": "Host1",
                        "address": "127.0.0.1",
                        "port": 22,
                        "username": "user1",
                        "directories": [{"name": "proj1", "path": "/home/user1/proj1"}],
                        "agents": [dict(stale_agent), {"id": "a2", "name": "Shell", "command": "bash -l", "icon": "✳"}],
                        "selected_agent": 0,
                    }
                ],
                "agents": [dict(stale_agent)],
                "sync_url": "http://127.0.0.1:18080",
                "sync_token": "tok",
                "auto_sync_enabled": True,
            }
            config_path.write_text(json.dumps(test_config), encoding="utf-8")

            with patch.dict(os.environ, {"HOME": tmpdir, "XDG_CONFIG_HOME": str(Path(tmpdir) / ".config")}):
                sys.modules.pop("core", None)
                sys.modules.pop("remote-dev-terminal", None)
                ubuntu_dir = str(Path(__file__).resolve().parents[1] / "ubuntu")
                if ubuntu_dir in sys.path:
                    sys.path.remove(ubuntu_dir)
                sys.path.insert(0, ubuntu_dir)
                mod = __import__("remote-dev-terminal")
                app = mod.App()
                try:
                    app.persist = lambda: None

                    term = mod.Vte.Terminal()
                    title_icon = Gtk.Box()
                    session_title = Gtk.Label(label="Codex · proj1")
                    close_btn = Gtk.Button()
                    sess = {
                        "id": "s1",
                        "terminal": term,
                        "host": app.data["hosts"][0],
                        "key": ("h1", 22, "user1", "/home/user1/proj1", "codex"),
                        "state": {},
                        "agent": {"name": "Codex", "command": "codex"},
                        "item": {
                            "id": "s1",
                            "directory_name": "proj1",
                            "directory_path": "/home/user1/proj1",
                            "agent_name": "Codex",
                            "agent_command": "codex",
                            "display_name": "Codex · proj1",
                            "title_source": "default",
                        },
                        "title_icon": title_icon,
                        "session_title": session_title,
                        "title_source": "default",
                        "close_button": close_btn,
                        "label": Gtk.Box(),
                        "float_button": Gtk.Button(),
                    }
                    app.sessions = [sess]
                    app.minimize_session(sess)
                    self.assertEqual(len(sess["minimized_button"].get_child().get_children()), 2)

                    synced_agents = [
                        {"id": "a1", "name": "Codex 极速版", "command": "codex", "icon": "brand:codex"},
                        {"id": "a2", "name": "我的 Shell", "command": "bash -l", "icon": "brand:shell"},
                    ]
                    app.replace_agents(synced_agents)
                    app.refresh()

                    self.assertNotIn("agents", app.data["hosts"][0])
                    self.assertEqual(app.host_agents(app.data["hosts"][0]), synced_agents)
                    self.assertEqual(sess["agent"]["name"], "Codex 极速版")
                    self.assertEqual(sess["item"]["agent_name"], "Codex 极速版")
                    self.assertEqual(sess["item"]["agent_icon"], "brand:codex")
                    self.assertEqual(sess["session_title"].get_text(), "Codex 极速版 · proj1")
                    self.assertEqual(len(title_icon.get_children()), 1)
                    self.assertIsInstance(title_icon.get_children()[0], Gtk.Image)
                    # Minimized instance card should now include the brand icon between dot and label.
                    self.assertEqual(len(sess["minimized_button"].get_child().get_children()), 3)
                    self.assertIsInstance(sess["minimized_icon"].get_children()[0], Gtk.Image)

                    # Docked ungrouped instance card in sidebar should display the developer icon.
                    app.remove_minimized_button(sess)
                    app.tiled = False
                    app.rebuild_instances_sidebar([sess])
                    rows = app.instances_list.get_children()
                    self.assertEqual(len(rows), 1)
                    btn_children = rows[0].get_children()[0].get_child().get_children()
                    self.assertIsInstance(btn_children[0], Gtk.Image)
                    self.assertEqual(btn_children[1].get_text(), "Codex 极速版 · proj1")

                    # Reordering developers pushes via push_agent_list, not push_host_profile.
                    with patch.object(app, "push_agent_list") as push_agents, patch.object(app, "push_host_profile") as push_profile:
                        app.move_agent(None, 1, -1)
                        push_agents.assert_called_once()
                        push_profile.assert_not_called()
                        self.assertEqual([a["command"] for a in app.data["agents"]], ["bash -l", "codex"])
                finally:
                    app.sessions = []
                    app.sync_executor.shutdown(wait=True)
                    app.destroy()


if __name__ == "__main__":
    unittest.main()
