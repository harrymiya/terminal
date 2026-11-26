import http.client
import importlib.util
import json
import os
import shlex
import sqlite3
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("sync_server", Path(__file__).resolve().parents[1] / "backend/server.py")
sync_server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync_server)


class SyncServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "sessions.db"
        self.bootstrap_env = patch.dict(os.environ, {
            "RDT_SYNC_BOOTSTRAP_USERNAME": "test-user",
            "RDT_SYNC_BOOTSTRAP_PASSWORD": "test-password-123",
        })
        self.bootstrap_env.start()
        self.addCleanup(self.bootstrap_env.stop)
        self.token = ""
        self.server = sync_server.create_server(("127.0.0.1", 0), self.db)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        status, login = self.request("POST", "/v1/auth/login", {"username": "test-user", "password": "test-password-123"})
        self.assertEqual(status, 200)
        self.token = login["access_token"]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def request(self, method, path, body=None, token=None):
        if token is None:
            token = self.token
        conn = http.client.HTTPConnection(*self.server.server_address)
        headers = {"Authorization": "Bearer " + token} if token else {}
        conn.request(method, path, json.dumps(body) if body is not None else None, headers)
        response = conn.getresponse()
        result = response.status, json.loads(response.read())
        conn.close()
        return result

    def test_authenticated_persistent_session_lifecycle(self):
        session_id = "a" * 32
        path = "/v1/sessions/" + session_id
        item = {"id": session_id, "directory_path": "/work", "directory_name": "work", "agent_name": "Shell", "agent_command": "bash -l", "display_name": "work", "title_source": "default"}
        self.assertEqual(self.request("GET", "/v1/sessions", token="wrong")[0], 401)
        self.assertEqual(self.request("PUT", path, item)[0], 200)
        self.assertEqual(self.request("GET", "/v1/sessions")[1]["sessions"], [item])
        with sync_server.sqlite3.connect(self.db) as db:
            self.assertEqual(json.loads(db.execute("SELECT payload FROM account_sessions WHERE host_id='legacy' AND username='legacy'").fetchone()[0]), item)
        self.assertEqual(self.request("DELETE", path)[0], 200)
        self.assertEqual(self.request("GET", "/v1/sessions")[1]["sessions"], [])

    def test_rejects_extra_fields_and_invalid_ids(self):
        self.assertEqual(self.request("PUT", "/v1/sessions/invalid", {})[0], 404)
        self.assertEqual(self.request("PUT", "/v1/sessions/" + "a" * 32, {"password": "secret"})[0], 400)

    def test_desktop_clients_use_same_protocol_and_restore_id(self):
        item = {"id": "b" * 32, "directory_path": "/work", "directory_name": "work", "agent_name": "Shell", "agent_command": "bash -l", "display_name": "work"}
        host = {"sync_url": "http://%s:%d" % self.server.server_address, "sync_token": self.token, "address": "127.0.0.1", "port": 22, "username": "user"}
        for name in ("ubuntu", "omarchy"):
            spec = importlib.util.spec_from_file_location(name + "_core", Path(__file__).resolve().parents[1] / name / "core.py")
            core = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(core)
            core.sync_request(host, "PUT", item["id"], item)
            self.assertEqual(core.sync_request(host, "GET")["sessions"], [{**item, "title_source": "default"}])
            command, session_id = core.ssh_argv(host, "/work", "bash -l", session_id=item["id"], restore_only=True)
            self.assertEqual(session_id, item["id"])
            self.assertIn("rdt_" + item["id"][:18], command[-1])
            self.assertNotIn("new-session", command[-1])
            core.sync_request(host, "DELETE", item["id"])

    def test_session_add_and_delete_converge_between_clients(self):
        item = {"id": "d" * 32, "directory_path": "/work", "directory_name": "work", "agent_name": "Shell", "agent_command": "bash -l", "display_name": "work"}
        base = {"sync_url": "http://%s:%d" % self.server.server_address, "sync_token": self.token, "id": "shared-host", "address": "127.0.0.1", "port": 2200, "username": "dev"}
        creator, receiver = dict(base), dict(base)
        spec = importlib.util.spec_from_file_location("ubuntu_session_sync_core", Path(__file__).resolve().parents[1] / "ubuntu/core.py")
        core = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(core)

        core.sync_request(creator, "PUT", item["id"], item, host=creator)
        self.assertEqual(core.sync_request(receiver, "GET", host=receiver)["sessions"], [{**item, "title_source": "default"}])
        core.sync_request(receiver, "DELETE", item["id"], host=receiver)
        self.assertEqual(core.sync_request(creator, "GET", host=creator)["sessions"], [])

    def test_host_profiles_require_ports_and_merge_duplicate_ids(self):
        def profile(host_id, name="dev", port=2202):
            return {
                "id": host_id,
                "name": name,
                "lan_address": "127.0.0.1",
                "external_address": "",
                "port": port,
                "lan_port": port,
                "external_port": port,
                "username": "dev",
                "icon": "⌘",
                "selected_agent_command": "bash -l",
                "legacy_keys": [],
            }

        incomplete = profile("incomplete")
        del incomplete["port"]
        del incomplete["lan_port"]
        del incomplete["external_port"]
        self.assertEqual(self.request("PUT", "/v1/hosts/incomplete", incomplete)[0], 400)

        canonical_id, duplicate_id = "canonical-host", "duplicate-host"
        canonical = profile(canonical_id)
        self.assertEqual(self.request("PUT", f"/v1/hosts/{canonical_id}", canonical)[0], 200)
        duplicate = profile(duplicate_id, "latest name")
        with sync_server.sqlite3.connect(self.db) as db:
            db.execute("INSERT INTO account_hosts(user_id, host_id, payload, deleted) VALUES (1, ?, ?, 0)", (duplicate_id, json.dumps(duplicate)))
            session_id = "c" * 32
            item = {"id": session_id, "directory_path": "/work", "directory_name": "work", "agent_name": "Shell", "agent_command": "bash -l", "display_name": "work"}
            db.execute("INSERT INTO account_sessions VALUES (1, ?, 'dev', ?, ?)", (duplicate_id, session_id, json.dumps(item)))

        hosts = self.request("GET", "/v1/hosts")[1]["hosts"]
        active = [host for host in hosts if not host.get("deleted")]
        deleted = [host for host in hosts if host.get("deleted")]
        self.assertEqual([host["id"] for host in active], [canonical_id])
        self.assertEqual(next(host for host in deleted if host["id"] == duplicate_id)["canonical_id"], canonical_id)
        with sync_server.sqlite3.connect(self.db) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM account_sessions WHERE host_id=?", (canonical_id,)).fetchone()[0], 1)

        stale_update = self.request("PUT", f"/v1/hosts/{duplicate_id}", profile(duplicate_id, "updated", 2203))[1]
        self.assertEqual(stale_update["host"]["id"], canonical_id)
        self.assertEqual(stale_update["host"]["port"], 2203)
        host_update = profile(canonical_id, "updated", 2204)
        self.assertEqual(self.request("PUT", f"/v1/hosts/{canonical_id}", host_update)[1]["host"]["id"], canonical_id)
        active = [host for host in self.request("GET", "/v1/hosts")[1]["hosts"] if not host.get("deleted")]
        self.assertEqual([(host["id"], host["port"]) for host in active], [(canonical_id, 2204)])

    def test_operation_logs_sync(self):
        log1 = {
            "id": "1" * 32,
            "created_at": 1732410000000,
            "host_id": "host-1",
            "host_name": "Dev Server",
            "ssh_username": "dev",
            "directory_id": "dir-1",
            "directory_name": "remote-dev",
            "directory_path": "/home/dev/remote-dev",
            "agent_id": "agent-1",
            "agent_name": "Claude Code",
            "agent_command": "claude",
            "session_id": "a" * 32,
            "session_name": "remote-dev · Claude Code",
            "client": "android",
            "action": "open",
        }
        log2 = {**log1, "id": "2" * 32, "created_at": 1732410005000, "client": "ubuntu"}
        status, body = self.request("POST", "/v1/operation-logs", {"logs": [log1, log2]})
        self.assertEqual(status, 200)
        self.assertEqual([item["id"] for item in body["logs"]], [log2["id"], log1["id"]])

        # Duplicate POST is idempotent
        status, body = self.request("POST", "/v1/operation-logs", {"logs": [log1]})
        self.assertEqual(status, 200)
        self.assertEqual(len(body["logs"]), 2)

        renamed_log = {**log1, "updated_at": log1["created_at"] + 1000, "session_name": "Fix Android title sync", "title_source": "agent"}
        status, body = self.request("POST", "/v1/operation-logs", {"logs": [renamed_log]})
        self.assertEqual(status, 200)
        synced_log = next(item for item in body["logs"] if item["id"] == log1["id"])
        self.assertEqual(synced_log["session_name"], "Fix Android title sync")
        self.assertEqual(synced_log["title_source"], "agent")
        stale_log = {**log1, "session_name": "stale title"}
        status, body = self.request("POST", "/v1/operation-logs", {"logs": [stale_log]})
        self.assertEqual(status, 200)
        synced_log = next(item for item in body["logs"] if item["id"] == log1["id"])
        self.assertEqual(synced_log["session_name"], "Fix Android title sync")

        status, body = self.request("GET", "/v1/operation-logs?limit=1")
        self.assertEqual(status, 200)
        self.assertEqual([item["id"] for item in body["logs"]], [log2["id"]])

        # Operation logs are strictly isolated per authenticated user
        status, created_user = self.request("POST", "/v1/admin/users", {"username": "bob_logs", "password": "bob-password-1"})
        self.assertEqual(status, 201)
        status, bob_login = self.request("POST", "/v1/auth/login", {"username": "bob_logs", "password": "bob-password-1"}, token=None)
        self.assertEqual(status, 200)
        bob_token = bob_login["access_token"]
        status, bob_logs = self.request("GET", "/v1/operation-logs", token=bob_token)
        self.assertEqual(status, 200)
        self.assertEqual(bob_logs["logs"], [])

        # Client-side helper isolates local logs per account
        import sys
        from pathlib import Path
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ubuntu"))
        import core as ubuntu_core
        alice_log = {**log1, "account": "alice"}
        bob_log = {**log2, "account": "bob_logs"}
        self.assertEqual([x["id"] for x in ubuntu_core.filter_operation_logs([alice_log, bob_log], "alice")], [alice_log["id"]])
        self.assertEqual([x["id"] for x in ubuntu_core.filter_operation_logs([alice_log, bob_log], "bob_logs")], [bob_log["id"]])
        merged = ubuntu_core.merge_operation_logs([alice_log], [log2], account="bob_logs")
        self.assertEqual([x["id"] for x in ubuntu_core.filter_operation_logs(merged, "alice")], [alice_log["id"]])
        self.assertEqual([x["id"] for x in ubuntu_core.filter_operation_logs(merged, "bob_logs")], [log2["id"]])

    def test_theme_preference_sync_per_user(self):
        status, bob_user = self.request("POST", "/v1/admin/users", {"username": "bob_theme", "password": "bob-password-1"})
        self.assertEqual(status, 201)
        status, bob_login = self.request("POST", "/v1/auth/login", {"username": "bob_theme", "password": "bob-password-1"}, token=None)
        self.assertEqual(status, 200)
        bob_token = bob_login["access_token"]

        # Alice sets her theme to light
        status, body = self.request("PUT", "/v1/preferences", {"theme": "light"})
        self.assertEqual(status, 200)
        self.assertEqual(body["theme"], "light")
        # Bob sets his theme to nord
        status, body = self.request("PUT", "/v1/preferences", {"theme": "nord"}, token=bob_token)
        self.assertEqual(status, 200)
        self.assertEqual(body["theme"], "nord")

        # Each user restores their own theme via /v1/preferences, /v1/auth/me, and /v1/auth/login
        self.assertEqual(self.request("GET", "/v1/preferences")[1]["theme"], "light")
        self.assertEqual(self.request("GET", "/v1/auth/me")[1]["theme"], "light")
        self.assertEqual(self.request("GET", "/v1/preferences", token=bob_token)[1]["theme"], "nord")
        status, relogin = self.request("POST", "/v1/auth/login", {"username": "bob_theme", "password": "bob-password-1"}, token=None)
        self.assertEqual(status, 200)
        self.assertEqual(relogin["theme"], "nord")

    def test_wrap_agent_command_codex_and_agy(self):
        import sys
        from pathlib import Path
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ubuntu"))
        import core as ubuntu_core
        omarchy_spec = importlib.util.spec_from_file_location("omarchy_core", Path(__file__).resolve().parents[1] / "omarchy/core.py")
        omarchy_core = importlib.util.module_from_spec(omarchy_spec)
        omarchy_spec.loader.exec_module(omarchy_core)

        wrapped_codex = ubuntu_core.wrap_agent_command("a" * 32, "codex --dangerously-bypass-approvals-and-sandbox")
        self.assertIn("codex resume \"$_rdt_id\" --dangerously-bypass-approvals-and-sandbox", wrapped_codex)
        self.assertIn("rollout-*.jsonl", wrapped_codex)

        wrapped_agy = ubuntu_core.wrap_agent_command("b" * 32, "agy")
        self.assertIn("agy --conversation \"$_rdt_id\"", wrapped_agy)
        self.assertIn("*.db", wrapped_agy)

        self.assertEqual(ubuntu_core.wrap_agent_command("c" * 32, "bash -l"), "bash -l")
        self.assertEqual(ubuntu_core.wrap_agent_command("d" * 32, "codex resume 1234"), "codex resume 1234")

        wrapped_omarchy_codex = omarchy_core.wrap_agent_command("e" * 32, "codex")
        wrapped_omarchy_agy = omarchy_core.wrap_agent_command("f" * 32, "agy")
        self.assertIn("codex resume", wrapped_omarchy_codex)
        self.assertIn("agy --conversation", wrapped_omarchy_agy)
        for wrapped in (wrapped_omarchy_codex, wrapped_omarchy_agy):
            result = subprocess.run(["bash", "-n"], input=wrapped, text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
        with tempfile.TemporaryDirectory() as temp_dir:
            session_id = "e" * 32
            session_store = Path(temp_dir) / ".remote-dev-terminal/agent-sessions"
            session_store.mkdir(parents=True)
            (session_store / session_id).write_text(session_id, encoding="utf-8")
            codex_home = Path(temp_dir) / "codex"
            codex_home.mkdir()
            with sqlite3.connect(codex_home / "state_5.sqlite") as db:
                db.execute("CREATE TABLE threads (id TEXT PRIMARY KEY, title TEXT, cwd TEXT, updated_at INTEGER)")
                db.execute("INSERT INTO threads VALUES (?, ?, ?, ?)", (session_id, "Stale SQLite title", "/work", 1))
                legacy_thread_id = "f" * 32
                db.execute("INSERT INTO threads VALUES (?, ?, ?, ?)", (legacy_thread_id, "Legacy title", "/work", 2))
            (codex_home / "session_index.jsonl").write_text(
                json.dumps({"id": session_id, "thread_name": "Old Codex title", "updated_at": "1"}) + "\n"
                + json.dumps({"id": legacy_thread_id, "thread_name": "Recovered Codex title", "updated_at": "2"}) + "\n"
                + json.dumps({"id": session_id, "thread_name": "Codex title from index", "updated_at": "3"}) + "\n",
                encoding="utf-8")
            codex_env = {**os.environ, "CODEX_HOME": str(codex_home)}
            codex_env["HOME"] = temp_dir
            codex_command = omarchy_core.agent_session_title_command(session_id, "codex", "/work")
            codex_remote = f'"${{SHELL:-/bin/bash}}" -l -c {shlex.quote(codex_command)}'
            result = subprocess.run(["bash", "-c", codex_remote],
                                    env=codex_env, text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "RDT_TITLE:Codex title from index")

            fake_bin = Path(temp_dir) / "bin"
            fake_bin.mkdir()
            fake_tmux = fake_bin / "tmux"
            fake_tmux.write_text(
                "#!/bin/sh\n"
                "case \"$5\" in\n"
                "  '#{pane_current_command}') printf 'codex\\n' ;;\n"
                "  '#{pane_current_path}') printf '%s\\n' \"$RDT_TEST_PANE_PATH\" ;;\n"
                "esac\n", encoding="utf-8")
            fake_tmux.chmod(0o700)
            legacy_session_id = "d" * 32
            legacy_command = omarchy_core.agent_session_title_command(legacy_session_id, "codex", "/work")
            legacy_remote = f'"${{SHELL:-/bin/bash}}" -l -c {shlex.quote(legacy_command)}'
            legacy_env = {**codex_env, "PATH": str(fake_bin) + os.pathsep + os.environ.get("PATH", ""),
                          "RDT_TEST_PANE_PATH": "/work"}
            result = subprocess.run(["bash", "-c", legacy_remote],
                                    env=legacy_env, text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "RDT_TITLE:Recovered Codex title")
            self.assertEqual((session_store / legacy_session_id).read_text(encoding="utf-8"), legacy_thread_id)

            agy_home = Path(temp_dir) / "agy"
            agy_home.mkdir()
            with sqlite3.connect(agy_home / "conversation_summaries.db") as db:
                db.execute("CREATE TABLE conversation_summaries (conversation_id TEXT PRIMARY KEY, title TEXT, workspace_uris TEXT, last_modified_time INTEGER)")
                db.execute("INSERT INTO conversation_summaries VALUES (?, ?, ?, ?)",
                           (session_id, "AGY title from DB", json.dumps(["file:///work"]), 1))
                legacy_agy_id = "a" * 32
                db.execute("INSERT INTO conversation_summaries VALUES (?, ?, ?, ?)",
                           (legacy_agy_id, "Recovered AGY title", json.dumps(["file:///work"]), 2))
            agy_env = {**os.environ, "AGY_HOME": str(agy_home)}
            agy_env["HOME"] = temp_dir
            agy_command = omarchy_core.agent_session_title_command(session_id, "agy", "/work")
            agy_remote = f'"${{SHELL:-/bin/bash}}" -l -c {shlex.quote(agy_command)}'
            result = subprocess.run(["bash", "-c", agy_remote],
                                    env=agy_env, text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "RDT_TITLE:AGY title from DB")

            legacy_agy_session_id = "c" * 32
            legacy_agy_command = omarchy_core.agent_session_title_command(legacy_agy_session_id, "agy", "/work")
            legacy_agy_remote = f'"${{SHELL:-/bin/bash}}" -l -c {shlex.quote(legacy_agy_command)}'
            legacy_agy_env = {**agy_env, "PATH": str(fake_bin) + os.pathsep + os.environ.get("PATH", ""),
                              "RDT_TEST_PANE_PATH": "/work"}
            fake_tmux.write_text(
                "#!/bin/sh\n"
                "case \"$5\" in\n"
                "  '#{pane_current_command}') printf 'agy\\n' ;;\n"
                "  '#{pane_current_path}') printf '%s\\n' \"$RDT_TEST_PANE_PATH\" ;;\n"
                "esac\n", encoding="utf-8")
            result = subprocess.run(["bash", "-c", legacy_agy_remote],
                                    env=legacy_agy_env, text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "RDT_TITLE:Recovered AGY title")
            self.assertEqual((session_store / legacy_agy_session_id).read_text(encoding="utf-8"), legacy_agy_id)


if __name__ == "__main__":
    unittest.main()
