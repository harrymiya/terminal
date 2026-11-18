import http.client
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
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
        item = {"id": session_id, "directory_path": "/work", "directory_name": "work", "agent_name": "Shell", "agent_command": "bash -l", "display_name": "work"}
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
        for name in ("ubuntu", "windows", "omarchy"):
            spec = importlib.util.spec_from_file_location(name + "_core", Path(__file__).resolve().parents[1] / name / "core.py")
            core = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(core)
            core.sync_request(host, "PUT", item["id"], item)
            self.assertEqual(core.sync_request(host, "GET")["sessions"], [item])
            command, session_id = core.ssh_argv(host, "/work", "bash -l", session_id=item["id"], restore_only=True)
            self.assertEqual(session_id, item["id"])
            self.assertIn("rdt_" + item["id"][:18], command[-1])
            self.assertNotIn("new-session", command[-1])
            core.sync_request(host, "DELETE", item["id"])

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


if __name__ == "__main__":
    unittest.main()
