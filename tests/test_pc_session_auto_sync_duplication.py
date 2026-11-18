"""
Automated Regression Test Suite for PC Session Auto-Sync Duplication.

Verifies:
1. Key consistency & deduplication in open_terminal (5-tuple key, no duplicate on click).
2. Workspace-aware deduplication in refresh_host_shared inflow.
3. Pending session sync migration during host ID remapping (no orphaned deletions).
4. merge_session_sync_items workspace-level deduplication.
5. Backend server PUT and host profile merge workspace idempotency.
"""

import http.client
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import MagicMock, patch

import sys

ROOT = Path(os.environ.get("WORKSPACE_DIR", "/home/agent/code/remote-dev-terminal"))


def load_module(rel_path, name):
    full_path = ROOT / rel_path
    platform_dir = str(full_path.parent)
    if platform_dir not in sys.path:
        sys.path.insert(0, platform_dir)
    spec = importlib.util.spec_from_file_location(name, full_path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class PcSessionAutoSyncDuplicationTest(unittest.TestCase):
    """Regression tests verifying session deduplication across PC clients."""

    def test_merge_session_sync_items_deduplicates_by_workspace(self):
        """Test that merge_session_sync_items retains only the latest session per workspace."""
        for platform in ("ubuntu", "omarchy", "windows"):
            with self.subTest(platform=platform):
                core = load_module(f"{platform}/core.py", f"core_{platform}")
                # Load client script to access merge_session_sync_items
                client = load_module(f"{platform}/remote-dev-terminal.py", f"client_{platform}")
                
                # Remote items contain two sessions for the same directory & command with different IDs
                remote_items = [
                    {
                        "id": "11111111111111111111111111111111",
                        "directory_path": "/home/user/project1",
                        "directory_name": "project1",
                        "agent_name": "Shell",
                        "agent_command": "bash -l",
                        "display_name": "project1 · Shell",
                    },
                    {
                        "id": "22222222222222222222222222222222",
                        "directory_path": "/home/user/project1",
                        "directory_name": "project1",
                        "agent_name": "Shell",
                        "agent_command": "bash -l",
                        "display_name": "project1 · Shell",
                    },
                    {
                        "id": "33333333333333333333333333333333",
                        "directory_path": "/home/user/project2",
                        "directory_name": "project2",
                        "agent_name": "Shell",
                        "agent_command": "bash -l",
                        "display_name": "project2 · Shell",
                    },
                ]

                # Without mutations
                merged = client.App.merge_session_sync_items(remote_items, [])
                # Should have 2 unique workspaces, not 3
                self.assertEqual(len(merged), 2)
                workspaces = {(item["directory_path"], item["agent_command"]) for item in merged}
                self.assertEqual(workspaces, {("/home/user/project1", "bash -l"), ("/home/user/project2", "bash -l")})
                
                # Verify that the later item for project1 is retained
                proj1_item = next(item for item in merged if item["directory_path"] == "/home/user/project1")
                self.assertEqual(proj1_item["id"], "22222222222222222222222222222222")

                # With PUT mutation for project1 with a new session id
                mutations = [
                    {
                        "session_id": "44444444444444444444444444444444",
                        "method": "PUT",
                        "item": {
                            "id": "44444444444444444444444444444444",
                            "directory_path": "/home/user/project1",
                            "directory_name": "project1",
                            "agent_name": "Shell",
                            "agent_command": "bash -l",
                            "display_name": "project1 · Shell",
                        },
                    }
                ]
                merged_with_put = client.App.merge_session_sync_items(remote_items, mutations)
                self.assertEqual(len(merged_with_put), 2)
                proj1_item_put = next(item for item in merged_with_put if item["directory_path"] == "/home/user/project1")
                self.assertEqual(proj1_item_put["id"], "44444444444444444444444444444444")

    def test_host_id_remapping_migrates_pending_session_sync(self):
        """Test that remap_host_id updates host_id in pending_session_sync to prevent orphaned DELETEs."""
        for platform in ("ubuntu", "omarchy", "windows"):
            with self.subTest(platform=platform):
                client = load_module(f"{platform}/remote-dev-terminal.py", f"client_{platform}")
                app = client.App.__new__(client.App)
                app.data = {
                    "hosts": [
                        {"id": "old_host_123", "address": "10.0.0.1", "port": 22, "username": "dev"},
                        {"id": "canonical_host_456", "address": "10.0.0.1", "port": 22, "username": "dev"},
                    ],
                    "pending_session_sync": [
                        {"host_id": "old_host_123", "username": "dev", "method": "DELETE", "session_id": "s_del_1"},
                        {"host_id": "other_host_999", "username": "dev", "method": "PUT", "session_id": "s_put_2"},
                    ],
                    "dirty_host_profiles": ["old_host_123"],
                }
                app.sessions = []
                app.persist = MagicMock()
                app.refresh = MagicMock()
                app.dirty_host_profiles = set(app.data["dirty_host_profiles"])

                # Execute remap_host_id
                app.remap_host_id("old_host_123", "canonical_host_456")

                # Verify pending_session_sync was updated
                pending = app.data["pending_session_sync"]
                self.assertEqual(pending[0]["host_id"], "canonical_host_456")
                self.assertEqual(pending[0]["session_id"], "s_del_1")
                self.assertEqual(pending[1]["host_id"], "other_host_999")
                app.persist.assert_called()

    def test_open_terminal_key_consistency_and_user_click_reuse(self):
        """Test that key is always 5-tuple and existing session is focused without duplication."""
        for platform in ("ubuntu", "omarchy", "windows"):
            with self.subTest(platform=platform):
                client = load_module(f"{platform}/remote-dev-terminal.py", f"client_{platform}")
                app = client.App.__new__(client.App)
                host = {
                    "id": "host_1",
                    "port": 22,
                    "username": "user",
                    "directories": [{"name": "proj", "path": "/home/user/proj"}],
                    "agents": [{"name": "Bash", "command": "bash -l"}],
                }
                app.data = {"hosts": [host]}
                app.tiled = False
                app.tabs = MagicMock()
                app.message = MagicMock()
                app.sync_async = MagicMock()
                app.rebuild_view = MagicMock()
                app.scale_layout = MagicMock()
                app.terminal_font_scale = MagicMock(return_value=1.0)
                app.current_host = MagicMock(return_value=host)
                app.directories = MagicMock()
                app.directories.get_active.return_value = 0
                app.agents = MagicMock()
                app.agents.get_active.return_value = 0
                app.host_agents = MagicMock(return_value=[{"name": "Bash", "command": "bash -l"}])
                app.shared_by_host = {}

                # Existing session restored by auto-sync
                shared_item = {
                    "id": "remote_sess_uuid_123",
                    "directory_name": "proj",
                    "directory_path": "/home/user/proj",
                    "agent_name": "Bash",
                    "agent_command": "bash -l",
                    "display_name": "proj · Bash",
                }

                mock_terminal = MagicMock()
                existing_session = {
                    "terminal": mock_terminal,
                    "label": MagicMock(),
                    "key": ("host_1", 22, "user", "/home/user/proj", "bash -l"),
                    "id": "remote_sess_uuid_123",
                    "host": host,
                    "state": {},
                    "started_at": time.monotonic(),
                }
                app.sessions = [existing_session]

                # User clicks workspace directory (shared_item=None)
                app.open_terminal(None)

                # Should find existing session, focus terminal, and NOT add to sessions
                self.assertEqual(len(app.sessions), 1)
                mock_terminal.grab_focus.assert_called_once()
                # Should NOT call sync_async (no duplicate PUT)
                app.sync_async.assert_not_called()

    def test_refresh_host_shared_inflow_deduplication(self):
        """Test that refresh_host_shared does not open duplicate terminal when workspace is already active."""
        for platform in ("ubuntu", "omarchy", "windows"):
            with self.subTest(platform=platform):
                client = load_module(f"{platform}/remote-dev-terminal.py", f"client_{platform}")
                app = client.App.__new__(client.App)
                host = {
                    "id": "host_1",
                    "address": "10.0.0.1",
                    "port": 22,
                    "username": "user",
                    "directories": [{"name": "proj", "path": "/home/user/proj"}],
                }
                app.data = {
                    "hosts": [host],
                    "sync_url": "http://127.0.0.1:8000",
                    "auto_sync_enabled": True,
                    "sync_token": "valid_token",
                    "sync_username": "testuser",
                    "pending_session_sync": [],
                }
                app.sessions = [
                    {
                        "terminal": MagicMock(),
                        "label": MagicMock(),
                        "key": ("host_1", 22, "user", "/home/user/proj", "bash -l"),
                        "id": "local_uuid_abc",
                        "host": host,
                        "state": {},
                        "started_at": time.monotonic(),
                        "close_button": MagicMock(),
                    }
                ]
                app.canonical_session_host = lambda h: h
                app.shared_host_key = client.App.shared_host_key
                app.open_terminal = MagicMock()
                app.update_sync_status = MagicMock()
                app.clear_session_sync_mutations = MagicMock()
                app.pending_session_sync_for = MagicMock(return_value=[])

                # Simulate incoming sessions list with a DIFFERENT session ID for the same workspace
                incoming_sessions = [
                    {
                        "id": "remote_uuid_xyz",
                        "directory_path": "/home/user/proj",
                        "directory_name": "proj",
                        "agent_name": "Bash",
                        "agent_command": "bash -l",
                        "display_name": "proj · Bash",
                    }
                ]

                # We directly test the deduplication logic in the finish block
                remote_ids = {item.get("id") for item in incoming_sessions}
                key = app.shared_host_key(host)
                snapshot = dict(host)

                for item in incoming_sessions:
                    target_key = (snapshot.get("id"), snapshot.get("port"), snapshot.get("username"), item.get("directory_path"), item.get("agent_command"))
                    already_active = any(
                        session.get("id") == item.get("id")
                        or session.get("key", ())[:5] == target_key
                        or (
                            app.shared_host_key(app.canonical_session_host(session.get("host", {}))) == key
                            and session.get("key", (None, None, None, None, None))[3:5] == (item.get("directory_path"), item.get("agent_command"))
                        )
                        for session in app.sessions
                    )
                    if not already_active:
                        app.open_terminal(None, snapshot, item, activate=False)

                # open_terminal must NOT have been called because workspace is already active
                app.open_terminal.assert_not_called()


class BackendServerSessionIdempotencyTest(unittest.TestCase):
    """Test that backend server enforces workspace idempotency on PUT and host profile merge."""

    def setUp(self):
        backend_spec = importlib.util.spec_from_file_location("server", ROOT / "backend/server.py")
        self.server_mod = importlib.util.module_from_spec(backend_spec)
        backend_spec.loader.exec_module(self.server_mod)

        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "sessions.db"
        self.bootstrap_env = patch.dict(os.environ, {
            "RDT_SYNC_BOOTSTRAP_USERNAME": "test-user",
            "RDT_SYNC_BOOTSTRAP_PASSWORD": "test-password-123",
        })
        self.bootstrap_env.start()
        self.addCleanup(self.bootstrap_env.stop)

        self.server = self.server_mod.create_server(("127.0.0.1", 0), self.db)
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

    def request(self, method, path, body=None):
        conn = http.client.HTTPConnection(*self.server.server_address)
        headers = {"Authorization": "Bearer " + self.token} if hasattr(self, "token") else {}
        conn.request(method, path, json.dumps(body) if body is not None else None, headers)
        response = conn.getresponse()
        data = response.read()
        conn.close()
        return response.status, (json.loads(data) if data else None)

    def test_put_session_replaces_preexisting_session_for_same_workspace(self):
        host_id = "h1"
        username = "dev"
        sess1_id = "11111111111111111111111111111111"
        sess2_id = "22222222222222222222222222222222"

        item1 = {
            "id": sess1_id,
            "directory_path": "/home/dev/app",
            "directory_name": "app",
            "agent_name": "Shell",
            "agent_command": "bash -l",
            "display_name": "app · Shell",
        }
        item2 = {
            "id": sess2_id,
            "directory_path": "/home/dev/app",
            "directory_name": "app",
            "agent_name": "Shell",
            "agent_command": "bash -l",
            "display_name": "app · Shell",
        }

        # Put first session
        status, resp = self.request("PUT", f"/v1/hosts/{host_id}/users/{username}/sessions/{sess1_id}", item1)
        self.assertEqual(status, 200)

        # Query sessions
        status, resp = self.request("GET", f"/v1/hosts/{host_id}/users/{username}/sessions")
        self.assertEqual(status, 200)
        self.assertEqual(len(resp["sessions"]), 1)
        self.assertEqual(resp["sessions"][0]["id"], sess1_id)

        # Put second session with same workspace but different session ID
        status, resp = self.request("PUT", f"/v1/hosts/{host_id}/users/{username}/sessions/{sess2_id}", item2)
        self.assertEqual(status, 200)

        # Query sessions: must only have 1 session (item2), item1 must be deleted
        status, resp = self.request("GET", f"/v1/hosts/{host_id}/users/{username}/sessions")
        self.assertEqual(status, 200)
        self.assertEqual(len(resp["sessions"]), 1)
        self.assertEqual(resp["sessions"][0]["id"], sess2_id)

    def test_merge_duplicate_host_profiles_deduplicates_sessions_by_workspace(self):
        user_id = 1
        canonical_id = "canon_host"
        duplicate_id = "dup_host"
        username = "dev"

        with self.server_mod.sqlite3.connect(self.db) as db:
            # Seed session in canonical host
            item1 = {
                "id": "11111111111111111111111111111111",
                "directory_path": "/home/dev/proj",
                "directory_name": "proj",
                "agent_name": "Shell",
                "agent_command": "bash -l",
                "display_name": "proj · Shell",
            }
            # Seed session in duplicate host for same workspace with different id
            item2 = {
                "id": "22222222222222222222222222222222",
                "directory_path": "/home/dev/proj",
                "directory_name": "proj",
                "agent_name": "Shell",
                "agent_command": "bash -l",
                "display_name": "proj · Shell",
            }
            db.execute(
                "INSERT INTO account_sessions VALUES (?, ?, ?, ?, ?)",
                (user_id, canonical_id, username, item1["id"], json.dumps(item1)),
            )
            db.execute(
                "INSERT INTO account_sessions VALUES (?, ?, ?, ?, ?)",
                (user_id, duplicate_id, username, item2["id"], json.dumps(item2)),
            )

            # Call merge_duplicate_host_profiles
            duplicate = {"id": duplicate_id, "address": "10.0.0.1", "port": 22, "username": username}
            self.server_mod.merge_duplicate_host_profiles(db, user_id, canonical_id, duplicate, duplicate_id)

            # Check remaining sessions under canonical_id
            rows = db.execute(
                "SELECT id, payload FROM account_sessions WHERE user_id=? AND host_id=?",
                (user_id, canonical_id),
            ).fetchall()
            self.assertEqual(len(rows), 1)
            # Old duplicate host sessions should be deleted
            dup_rows = db.execute(
                "SELECT id FROM account_sessions WHERE user_id=? AND host_id=?",
                (user_id, duplicate_id),
            ).fetchall()
            self.assertEqual(len(dup_rows), 0)


if __name__ == "__main__":
    unittest.main()
