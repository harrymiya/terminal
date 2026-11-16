import http.client
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest

spec = importlib.util.spec_from_file_location("sync_server", Path(__file__).resolve().parents[1] / "backend/server.py")
sync_server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync_server)


class SyncServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "sessions.db"
        self.server = sync_server.create_server(("127.0.0.1", 0), self.db, "test-token")
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def request(self, method, path, body=None, token="test-token"):
        conn = http.client.HTTPConnection(*self.server.server_address)
        headers = {"Authorization": "Bearer " + token}
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
            self.assertEqual(json.loads(db.execute("SELECT payload FROM sessions").fetchone()[0]), item)
        self.assertEqual(self.request("DELETE", path)[0], 200)
        self.assertEqual(self.request("GET", "/v1/sessions")[1]["sessions"], [])

    def test_rejects_extra_fields_and_invalid_ids(self):
        self.assertEqual(self.request("PUT", "/v1/sessions/invalid", {})[0], 404)
        self.assertEqual(self.request("PUT", "/v1/sessions/" + "a" * 32, {"password": "secret"})[0], 400)

    def test_desktop_clients_use_same_protocol_and_restore_id(self):
        item = {"id": "b" * 32, "directory_path": "/work", "directory_name": "work", "agent_name": "Shell", "agent_command": "bash -l", "display_name": "work"}
        host = {"sync_url": "http://%s:%d" % self.server.server_address, "sync_token": "test-token", "address": "example.test", "port": 22, "username": "user"}
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


if __name__ == "__main__":
    unittest.main()
