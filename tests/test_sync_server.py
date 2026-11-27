import http.client
import importlib.util
import json
import os
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
        self.server.memory_maintenance_stop.set()
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

    def test_jarvis_short_and_long_memory_sync_and_async_consolidation(self):
        mem0_called = threading.Event()
        namespaces = []

        def fake_mem0(method, path, payload=None):
            namespaces.append(payload.get("user_id"))
            if path == "/v1/memories/search":
                return {"results": [{"id": "long-1", "memory": "Jarvis project uses SQLite", "metadata": {"category": "project"}}]}
            if path == "/v1/memories":
                mem0_called.set()
                return {"results": [{"id": "long-2", "memory": "User prefers concise voice replies", "metadata": {"category": "preference", "importance": 4}}]}
            raise AssertionError(f"Unexpected Mem0 call: {method} {path}")

        with patch.object(sync_server, "call_mem0", side_effect=fake_mem0):
            status, synced = self.request("POST", "/v1/jarvis/short-memory/sync", {"items": [{
                "id": "short-1", "content": "Jarvis uses SQLite on this phone", "category": "project",
                "importance": 5, "created_at": 1, "updated_at": 1,
            }]})
            self.assertEqual(status, 200)
            self.assertEqual(synced["items"][0]["id"], "short-1")
            self.assertEqual(self.request("GET", "/v1/jarvis/short-memory")[1]["items"][0]["content"], "Jarvis uses SQLite on this phone")

            status, long_results = self.request("POST", "/v1/jarvis/long-memory/search", {"query": "Jarvis project"})
            self.assertEqual(status, 200)
            self.assertEqual(long_results["results"][0]["id"], "long-1")

            status, queued = self.request("POST", "/v1/jarvis/memory/turns", {"force": True, "turns": [{
                "id": "turn-1", "user_text": "Remember I prefer concise replies", "assistant_text": "I'll remember that."
            }]})
            self.assertEqual(status, 202)
            self.assertTrue(queued["queued"])
            self.assertTrue(mem0_called.wait(3))
            for _ in range(30):
                items = self.request("GET", "/v1/jarvis/short-memory")[1]["items"]
                if any(item["id"] == "long-2" for item in items):
                    break
                threading.Event().wait(0.1)
            self.assertTrue(any(item["id"] == "long-2" for item in items))
            self.assertEqual(len(set(namespaces)), 1)

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

        newer_local = {**alice_log, "updated_at": 30, "session_name": "Current title", "title_source": "agent"}
        older_remote = {**alice_log, "updated_at": 20, "session_name": "Stale title", "title_source": "default"}
        self.assertEqual(
            ubuntu_core.merge_operation_logs([newer_local], [older_remote])[0]["session_name"],
            "Current title",
        )
        synced, changed = ubuntu_core.apply_active_session_titles_to_operation_logs(
            [older_remote],
            [{"session_id": "-".join(("a" * 8, "a" * 8, "a" * 8, "a" * 8)),
              "session_name": "Generated title", "title_source": "agent"}],
            account="alice",
            now=40,
        )
        self.assertEqual(synced[0]["session_name"], "Generated title")
        self.assertEqual(synced[0]["title_source"], "agent")
        self.assertEqual(synced[0]["updated_at"], 40)
        self.assertEqual(len(changed), 1)

        manually_named = {**older_remote, "session_name": "Manual title", "title_source": "manual"}
        unchanged, changed = ubuntu_core.apply_active_session_titles_to_operation_logs(
            [manually_named],
            [{"session_id": "a" * 32, "session_name": "Generated title", "title_source": "agent"}],
            account="alice",
            now=50,
        )
        self.assertEqual(unchanged[0]["session_name"], "Manual title")
        self.assertEqual(changed, [])
        renamed, changed = ubuntu_core.apply_active_session_titles_to_operation_logs(
            [manually_named],
            [{"session_id": "a" * 32, "session_name": "Edited again", "title_source": "manual"}],
            account="alice",
            now=60,
        )
        self.assertEqual(renamed[0]["session_name"], "Edited again")
        self.assertEqual(renamed[0]["title_source"], "manual")
        self.assertEqual(len(changed), 1)

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
        import urllib.error
        import sys
        from pathlib import Path
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ubuntu"))
        import core as ubuntu_core
        omarchy_spec = importlib.util.spec_from_file_location("omarchy_core", Path(__file__).resolve().parents[1] / "omarchy/core.py")
        omarchy_core = importlib.util.module_from_spec(omarchy_spec)
        omarchy_spec.loader.exec_module(omarchy_core)

        title_item = {"id": "1" * 32, "directory_path": "/work", "directory_name": "work",
                      "agent_name": "Codex", "agent_command": "codex",
                      "display_name": "Generated title", "title_source": "agent"}
        old_server_error = urllib.error.HTTPError("https://sync.example", 400, "invalid session", {}, None)
        with patch.object(omarchy_core, "sync_data_request", side_effect=[old_server_error, title_item]) as request:
            self.assertEqual(omarchy_core.sync_request({}, "PUT", title_item["id"], title_item), title_item)
        self.assertEqual(request.call_count, 2)
        self.assertNotIn("title_source", request.call_args.args[3])

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
            recovery_session_id = "9" * 32
            recovery_workspace = Path(temp_dir) / "workspace"
            recovery_workspace.mkdir()
            recovery_home = Path(temp_dir) / "recovery-codex"
            recovery_home.mkdir()
            (recovery_home / "session_index.jsonl").write_text(
                json.dumps({"id": legacy_thread_id, "thread_name": "Existing workspace title", "cwd": str(recovery_workspace), "updated_at": "5"}) + "\n",
                encoding="utf-8")
            recovery_command = omarchy_core.wrap_agent_command(recovery_session_id, "codex")
            capture_bin = Path(temp_dir) / "capture-bin"
            capture_bin.mkdir()
            fake_codex = capture_bin / "codex"
            fake_codex.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" > \"$RDT_CAPTURE\"\n", encoding="utf-8")
            fake_codex.chmod(0o700)
            recovery_store = Path(temp_dir) / ".remote-dev-terminal/agent-sessions"
            recovery_store.mkdir(parents=True, exist_ok=True)
            recovery_env = {**os.environ, "CODEX_HOME": str(recovery_home), "HOME": temp_dir,
                            "PATH": str(capture_bin) + os.pathsep + os.environ.get("PATH", ""),
                            "RDT_CAPTURE": str(Path(temp_dir) / "capture.txt")}
            result = subprocess.run(["bash", "-c", recovery_command], cwd=recovery_workspace, env=recovery_env,
                                    text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("resume " + legacy_thread_id, (Path(temp_dir) / "capture.txt").read_text(encoding="utf-8"))
            self.assertEqual((recovery_store / recovery_session_id).read_text(encoding="utf-8"), legacy_thread_id)
            (codex_home / "session_index.jsonl").write_text(
                json.dumps({"id": session_id, "thread_name": "Old Codex title", "updated_at": "1"}) + "\n"
                + json.dumps({"id": legacy_thread_id, "thread_name": "Recovered Codex title", "updated_at": "2"}) + "\n"
                + json.dumps({"id": session_id, "thread_name": "Codex title from index", "updated_at": "3"}) + "\n",
                encoding="utf-8")
            codex_env = {**os.environ, "CODEX_HOME": str(codex_home)}
            codex_env["HOME"] = temp_dir
            codex_command = omarchy_core.agent_session_title_command(session_id, "codex", "/work")
            self.assertNotIn("<<'RDT_PY'", codex_command)
            result = subprocess.run(["bash", "-c", codex_command],
                                    env=codex_env, text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "RDT_TITLE:Codex title from index")

            codex_default_home = Path(temp_dir) / ".codex"
            codex_default_home.mkdir()
            (codex_default_home / "session_index.jsonl").write_text(
                json.dumps({"id": session_id, "thread_name": "Codex title from default home"}) + "\n",
                encoding="utf-8")
            codex_alternate_home = Path(temp_dir) / ".codex-account2"
            codex_alternate_home.mkdir()
            alternate_codex_env = {**codex_env, "CODEX_HOME": str(codex_alternate_home)}
            result = subprocess.run(["bash", "-c", codex_command],
                                    env=alternate_codex_env, text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "RDT_TITLE:Codex title from default home")

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
            legacy_env = {**codex_env, "PATH": str(fake_bin) + os.pathsep + os.environ.get("PATH", ""),
                          "RDT_TEST_PANE_PATH": "/work"}
            result = subprocess.run(["bash", "-c", legacy_command],
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
            result = subprocess.run(["bash", "-c", agy_command],
                                    env=agy_env, text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "RDT_TITLE:AGY title from DB")

            agy_default_home = Path(temp_dir) / ".gemini/antigravity-cli"
            agy_default_home.mkdir(parents=True)
            with sqlite3.connect(agy_default_home / "conversation_summaries.db") as db:
                db.execute("CREATE TABLE conversation_summaries (conversation_id TEXT PRIMARY KEY, title TEXT)")
                db.execute("INSERT INTO conversation_summaries VALUES (?, ?)", (session_id, "AGY title from default home"))
            agy_alternate_home = Path(temp_dir) / ".gemini/antigravity-cli-account2"
            agy_alternate_home.mkdir()
            alternate_agy_env = {**agy_env, "AGY_HOME": str(agy_alternate_home)}
            result = subprocess.run(["bash", "-c", agy_command],
                                    env=alternate_agy_env, text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "RDT_TITLE:AGY title from default home")

            agy_account_id = "7" * 32
            (session_store / agy_account_id).write_text(agy_account_id, encoding="utf-8")
            agy_account_home = Path(temp_dir) / ".agy-homes/account-b/.gemini/antigravity-cli"
            agy_account_home.mkdir(parents=True)
            with sqlite3.connect(agy_account_home / "conversation_summaries.db") as db:
                db.execute("CREATE TABLE conversation_summaries (conversation_id TEXT PRIMARY KEY, title TEXT)")
                db.execute("INSERT INTO conversation_summaries VALUES (?, ?)",
                           (agy_account_id, "AGY title from alternate account"))
            account_agy_env = {**agy_env, "AGY_HOME": str(Path(temp_dir) / "missing-agy-home")}
            account_agy_command = omarchy_core.agent_session_title_command(agy_account_id, "agy", "/work")
            result = subprocess.run(["bash", "-c", account_agy_command],
                                    env=account_agy_env, text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "RDT_TITLE:AGY title from alternate account")

            legacy_agy_session_id = "c" * 32
            legacy_agy_command = omarchy_core.agent_session_title_command(legacy_agy_session_id, "agy", "/work")
            legacy_agy_env = {**agy_env, "PATH": str(fake_bin) + os.pathsep + os.environ.get("PATH", ""),
                              "RDT_TEST_PANE_PATH": "/work"}
            fake_tmux.write_text(
                "#!/bin/sh\n"
                "case \"$5\" in\n"
                "  '#{pane_current_command}') printf 'agy.real\\n' ;;\n"
                "  '#{pane_current_path}') printf '%s\\n' \"$RDT_TEST_PANE_PATH\" ;;\n"
                "esac\n", encoding="utf-8")
            result = subprocess.run(["bash", "-c", legacy_agy_command],
                                    env=legacy_agy_env, text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "RDT_TITLE:Recovered AGY title")
            self.assertEqual((session_store / legacy_agy_session_id).read_text(encoding="utf-8"), legacy_agy_id)


if __name__ == "__main__":
    unittest.main()
