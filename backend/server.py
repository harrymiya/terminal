#!/usr/bin/env python3
"""Small authenticated, persistent registry of remote terminal sessions."""
import argparse
import hmac
import hashlib
import json
import os
import re
import secrets
import sqlite3
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

MAX_BODY = 65536
FIELDS = ("id", "directory_path", "directory_name", "agent_name", "agent_command", "display_name")
WORKSPACE_FIELDS = ("id", "name", "path", "icon")
AGENT_FIELDS = ("id", "name", "command", "icon")


def workspace_revision(payload):
    return hashlib.sha256((payload if payload is not None else "").encode("utf-8")).hexdigest()


def merge_workspaces(base, current, incoming):
    base_by_id = {item["id"]: item for item in base}
    current_by_id = {item["id"]: item for item in current}
    incoming_by_id = {item["id"]: item for item in incoming}
    merged = {}
    for item_id in base_by_id.keys() | current_by_id.keys() | incoming_by_id.keys():
        before, remote, local = base_by_id.get(item_id), current_by_id.get(item_id), incoming_by_id.get(item_id)
        if local == before:
            value = remote
        elif remote == before or local == remote:
            value = local
        elif remote is None:
            value = local
        else:
            value = local
        if value is not None:
            merged[item_id] = value
    base_order = [item["id"] for item in base if item["id"] in merged]
    remote_order = [item["id"] for item in current if item["id"] in merged]
    local_order = [item["id"] for item in incoming if item["id"] in merged]
    if remote_order == base_order:
        order = local_order
    elif local_order == base_order:
        order = remote_order
    else:
        order = remote_order + [item_id for item_id in local_order if item_id not in remote_order]
    order += [item_id for item_id in merged if item_id not in order]
    return [merged[item_id] for item_id in order]


merge_agents = merge_workspaces


def create_server(address, database, token=None):
    database = str(database)
    with sqlite3.connect(database) as db:
        db.execute("CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS host_sessions (host_id TEXT NOT NULL, username TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(host_id, username, id))")
        db.execute("CREATE TABLE IF NOT EXISTS host_state (host_id TEXT PRIMARY KEY, revision INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS workspaces (host_id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS agents (host_id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, username TEXT UNIQUE NOT NULL, salt BLOB NOT NULL, password_hash BLOB NOT NULL, must_change INTEGER NOT NULL DEFAULT 0, is_admin INTEGER NOT NULL DEFAULT 0)")
        try:
            db.execute("ALTER TABLE users ADD COLUMN must_change INTEGER NOT NULL DEFAULT 0")
        except sqlite3.OperationalError:
            pass
        try:
            db.execute("ALTER TABLE users ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0")
        except sqlite3.OperationalError:
            pass
        db.execute("CREATE TABLE IF NOT EXISTS auth_tokens (token_hash BLOB PRIMARY KEY, user_id INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS account_sessions (user_id INTEGER NOT NULL, host_id TEXT NOT NULL, username TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(user_id, host_id, username, id))")
        db.execute("CREATE TABLE IF NOT EXISTS account_workspaces (user_id INTEGER NOT NULL, host_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(user_id, host_id))")
        db.execute("CREATE TABLE IF NOT EXISTS workspace_history (user_id INTEGER NOT NULL, host_id TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(user_id, host_id, revision))")
        db.execute("CREATE TABLE IF NOT EXISTS account_agents (user_id INTEGER NOT NULL, host_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(user_id, host_id))")
        db.execute("CREATE TABLE IF NOT EXISTS agent_history (user_id INTEGER NOT NULL, host_id TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(user_id, host_id, revision))")
        db.execute("CREATE TABLE IF NOT EXISTS account_host_state (user_id INTEGER NOT NULL, host_id TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(user_id, host_id))")
        db.execute("CREATE TABLE IF NOT EXISTS account_hosts (user_id INTEGER NOT NULL, host_id TEXT NOT NULL, payload TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(user_id, host_id))")
        db.execute("UPDATE users SET must_change = 0 WHERE must_change != 0")
        if db.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 0:
            username = os.environ.get("RDT_SYNC_BOOTSTRAP_USERNAME", "lirui").strip().lower()
            password = os.environ.get("RDT_SYNC_BOOTSTRAP_PASSWORD", "123123")
            if not re.fullmatch(r"[a-z0-9_.-]{3,64}", username):
                raise ValueError("RDT_SYNC_BOOTSTRAP_USERNAME must be 3–64 lowercase letters, digits, dots, underscores, or hyphens")
            if not isinstance(password, str) or not 6 <= len(password) <= 1024:
                raise ValueError("RDT_SYNC_BOOTSTRAP_PASSWORD must be 6–1024 characters")
            salt = secrets.token_bytes(16)
            password_hash = hashlib.scrypt(password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32)
            db.execute("INSERT INTO users(username, salt, password_hash, must_change, is_admin) VALUES (?, ?, ?, 0, 1)", (username, salt, password_hash))

    class Handler(BaseHTTPRequestHandler):
        def issue_token(self, user_id):
            raw = secrets.token_urlsafe(36)
            digest = hashlib.sha256(raw.encode()).digest()
            expiry = int(time.time()) + 30 * 24 * 60 * 60
            with sqlite3.connect(database) as db:
                db.execute("INSERT INTO auth_tokens VALUES (?, ?, ?)", (digest, user_id, expiry))
            return raw, expiry

        def authenticate(self):
            supplied = self.headers.get("Authorization", "")
            bearer = supplied[7:] if supplied.startswith("Bearer ") else ""
            if not bearer:
                return False
            digest = hashlib.sha256(bearer.encode()).digest()
            with sqlite3.connect(database) as db:
                row = db.execute("SELECT user_id, expires_at FROM auth_tokens WHERE token_hash = ?", (digest,)).fetchone()
                if row and row[1] >= int(time.time()):
                    self.user_id = row[0]
                    account = db.execute("SELECT must_change FROM users WHERE id = ?", (self.user_id,)).fetchone()
                    self.must_change = bool(account and account[0])
                    return True
                if row:
                    db.execute("DELETE FROM auth_tokens WHERE token_hash = ?", (digest,))
            return False

        def log_message(self, format, *args):
            # Do not log request headers or credentials.
            super().log_message(format, *args)

        def reply(self, status, payload):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def authorized(self):
            if not self.authenticate():
                self.reply(401, {"error": "unauthorized"})
                return False
            return True

        def admin_authorized(self):
            if not self.authorized(): return False
            with sqlite3.connect(database) as db:
                row = db.execute("SELECT is_admin FROM users WHERE id = ?", (self.user_id,)).fetchone()
            if not row or not row[0]:
                self.reply(403, {"error": "administrator required"})
                return False
            return True

        def auth_request(self, register=False):
            try:
                length = int(self.headers.get("Content-Length", "-1"))
                if length < 0 or length > 8192:
                    raise ValueError()
                data = json.loads(self.rfile.read(length))
                username, password = data.get("username", "").strip().lower(), data.get("password", "")
                if not isinstance(username, str) or not re.fullmatch(r"[a-z0-9_.-]{3,64}", username):
                    raise ValueError("用户名需为 3–64 位字母、数字、点、下划线或连字符")
                min_password = 8 if register else 6
                if not isinstance(password, str) or not min_password <= len(password) <= 1024:
                    raise ValueError(f"密码长度需为 {min_password}–1024 个字符")
            except (ValueError, AttributeError, UnicodeDecodeError, json.JSONDecodeError) as exc:
                return self.reply(400, {"error": str(exc) or "invalid credentials"})
            with sqlite3.connect(database) as db:
                row = db.execute("SELECT id, salt, password_hash, must_change FROM users WHERE username = ?", (username,)).fetchone()
                if register:
                    if row:
                        return self.reply(409, {"error": "username already exists"})
                    salt = secrets.token_bytes(16)
                    password_hash = hashlib.scrypt(password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32)
                    user_id = db.execute("INSERT INTO users(username, salt, password_hash, must_change) VALUES (?, ?, ?, 0)", (username, salt, password_hash)).lastrowid
                else:
                    if not row:
                        return self.reply(401, {"error": "invalid username or password"})
                    user_id, salt, password_hash, must_change = row
                    candidate = hashlib.scrypt(password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32)
                    if not hmac.compare_digest(candidate, password_hash):
                        return self.reply(401, {"error": "invalid username or password"})
            with sqlite3.connect(database) as db:
                account = db.execute("SELECT is_admin FROM users WHERE id = ?", (user_id,)).fetchone()
            access_token, expiry = self.issue_token(user_id)
            return self.reply(200, {"username": username, "access_token": access_token, "expires_at": expiry,
                                   "must_change": bool(must_change) if not register else False,
                                   "is_admin": bool(account and account[0])})

        def do_GET(self):
            if urlsplit(self.path).path == "/v1/hosts":
                if not self.authorized(): return
                with sqlite3.connect(database) as db:
                    rows = db.execute("SELECT payload, deleted FROM account_hosts WHERE user_id = ? ORDER BY rowid", (self.user_id,)).fetchall()
                return self.reply(200, {"hosts": [{**json.loads(payload), "deleted": bool(deleted)} for payload, deleted in rows]})
            if urlsplit(self.path).path == "/v1/admin/users":
                if not self.admin_authorized(): return
                with sqlite3.connect(database) as db:
                    rows = db.execute("SELECT id, username, is_admin, must_change FROM users ORDER BY username").fetchall()
                return self.reply(200, {"users": [{"id": r[0], "username": r[1], "is_admin": bool(r[2]), "must_change": bool(r[3])} for r in rows]})
            if urlsplit(self.path).path == "/v1/auth/me":
                if not self.authorized(): return
                with sqlite3.connect(database) as db:
                    row = db.execute("SELECT username, must_change, is_admin FROM users WHERE id = ?", (self.user_id,)).fetchone()
                return self.reply(200, {"username": row[0] if row else "legacy", "must_change": bool(row and row[1]), "is_admin": bool(row and row[2])})
            if not self.authorized():
                return
            path = urlsplit(self.path).path
            parts = path.strip("/").split("/")
            if len(parts) == 4 and parts[:2] == ["v1", "hosts"] and parts[3] == "state":
                host_id = parts[2]
                if not host_id or len(host_id) > 256:
                    return self.reply(404, {"error": "invalid host id"})
                with sqlite3.connect(database) as db:
                    row = db.execute("SELECT revision, payload FROM account_host_state WHERE user_id = ? AND host_id = ?", (self.user_id, host_id)).fetchone()
                return self.reply(200, {"state": json.loads(row[1]) if row else {}, "revision": row[0] if row else 0, "configured": row is not None})
            if len(parts) == 6 and parts[:2] == ["v1", "hosts"] and parts[3] == "users" and parts[5] == "sessions":
                host_id, username = parts[2], __import__("urllib.parse", fromlist=["unquote"]).unquote(parts[4])
                if not host_id or len(host_id) > 256 or not username or len(username) > 256:
                    return self.reply(404, {"error": "invalid host or user"})
                with sqlite3.connect(database) as db:
                    rows = db.execute("SELECT payload FROM account_sessions WHERE user_id = ? AND host_id = ? AND username = ? ORDER BY rowid", (self.user_id, host_id, username)).fetchall()
                return self.reply(200, {"sessions": [json.loads(row[0]) for row in rows]})
            if path.startswith("/v1/agents/"):
                host_id = path.removeprefix("/v1/agents/")
                if not host_id or len(host_id) > 256:
                    return self.reply(404, {"error": "invalid host id"})
                with sqlite3.connect(database) as db:
                    row = db.execute("SELECT payload FROM account_agents WHERE user_id = ? AND host_id = ?", (self.user_id, host_id)).fetchone()
                    payload = row[0] if row else json.dumps([], ensure_ascii=False)
                    revision = db.execute("SELECT MAX(revision) FROM agent_history WHERE user_id = ? AND host_id = ?", (self.user_id, host_id)).fetchone()[0]
                    if revision is None:
                        db.execute("INSERT OR IGNORE INTO agent_history VALUES (?, ?, 0, ?)", (self.user_id, host_id, json.dumps([], ensure_ascii=False)))
                        if row:
                            db.execute("INSERT OR IGNORE INTO agent_history VALUES (?, ?, 1, ?)", (self.user_id, host_id, payload))
                            revision = 1
                        else:
                            revision = 0
                return self.reply(200, {"agents": json.loads(payload), "configured": row is not None, "revision": revision})
            if path.startswith("/v1/workspaces/"):
                host_id = path.removeprefix("/v1/workspaces/")
                if not host_id or len(host_id) > 256:
                    return self.reply(404, {"error": "invalid host id"})
                with sqlite3.connect(database) as db:
                    row = db.execute("SELECT payload FROM account_workspaces WHERE user_id = ? AND host_id = ?", (self.user_id, host_id)).fetchone()
                    current_payload = row[0] if row else json.dumps([], ensure_ascii=False)
                    history = db.execute("SELECT MAX(revision) FROM workspace_history WHERE user_id = ? AND host_id = ?", (self.user_id, host_id)).fetchone()[0]
                    if history is None:
                        db.execute("INSERT OR IGNORE INTO workspace_history VALUES (?, ?, 0, ?)", (self.user_id, host_id, json.dumps([], ensure_ascii=False)))
                        if row:
                            db.execute("INSERT OR IGNORE INTO workspace_history VALUES (?, ?, 1, ?)", (self.user_id, host_id, current_payload))
                            history = 1
                        else:
                            history = 0
                    configured = row is not None
                return self.reply(200, {"workspaces": json.loads(current_payload), "configured": configured,
                                        "revision": history, "etag": workspace_revision(current_payload)})
            if path != "/v1/sessions":
                return self.reply(404, {"error": "not found"})
            with sqlite3.connect(database) as db:
                sessions = [json.loads(row[0]) for row in db.execute("SELECT payload FROM account_sessions WHERE user_id = ? AND host_id = 'legacy' AND username = 'legacy' ORDER BY rowid", (self.user_id,))]
            self.reply(200, {"sessions": sessions})

        def do_POST(self):
            path = urlsplit(self.path).path
            if path == "/v1/admin/users":
                if not self.admin_authorized(): return
                try:
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length < 0 or length > 8192: raise ValueError()
                    data = json.loads(self.rfile.read(length))
                    username, password = data.get("username", "").strip().lower(), data.get("password", "")
                    is_admin = bool(data.get("is_admin", False))
                    if not isinstance(username, str) or not re.fullmatch(r"[a-z0-9_.-]{3,64}", username): raise ValueError("用户名需为 3–64 位字母、数字、点、下划线或连字符")
                    if not isinstance(password, str) or not 8 <= len(password) <= 1024: raise ValueError("密码至少 8 个字符")
                except (ValueError, AttributeError, UnicodeDecodeError, json.JSONDecodeError) as exc:
                    return self.reply(400, {"error": str(exc) or "invalid user"})
                salt = secrets.token_bytes(16)
                password_hash = hashlib.scrypt(password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32)
                try:
                    with sqlite3.connect(database) as db:
                        cursor = db.execute("INSERT INTO users(username, salt, password_hash, must_change, is_admin) VALUES (?, ?, ?, 0, ?)", (username, salt, password_hash, int(is_admin)))
                except sqlite3.IntegrityError:
                    return self.reply(409, {"error": "username already exists"})
                return self.reply(201, {"id": cursor.lastrowid, "username": username, "is_admin": is_admin, "must_change": False})
            if path == "/v1/auth/register":
                if not self.admin_authorized(): return
                return self.auth_request(register=True)
            if path == "/v1/auth/login":
                return self.auth_request(register=False)
            if path == "/v1/auth/password":
                if not self.authenticate(): return self.reply(401, {"error": "unauthorized"})
                try:
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length < 0 or length > 8192: raise ValueError()
                    new_password = json.loads(self.rfile.read(length)).get("new_password", "")
                    if not isinstance(new_password, str) or not 8 <= len(new_password) <= 1024:
                        raise ValueError("新密码至少 8 个字符")
                except (ValueError, AttributeError, UnicodeDecodeError, json.JSONDecodeError) as exc:
                    return self.reply(400, {"error": str(exc) or "invalid password"})
                salt = secrets.token_bytes(16)
                password_hash = hashlib.scrypt(new_password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32)
                with sqlite3.connect(database) as db:
                    db.execute("UPDATE users SET salt = ?, password_hash = ?, must_change = 0 WHERE id = ?", (salt, password_hash, self.user_id))
                self.must_change = False
                return self.reply(200, {"password_changed": True})
            if path == "/v1/auth/logout":
                if not self.authorized(): return
                bearer = self.headers.get("Authorization", "")[7:]
                if bearer:
                    with sqlite3.connect(database) as db:
                        db.execute("DELETE FROM auth_tokens WHERE token_hash = ?", (hashlib.sha256(bearer.encode()).digest(),))
                return self.reply(200, {"logged_out": True})
            return self.reply(404, {"error": "not found"})

        def do_PUT(self):
            if not self.authorized():
                return
            path = urlsplit(self.path).path
            if path.startswith("/v1/hosts/") and path.count("/") == 3:
                host_id = path.removeprefix("/v1/hosts/")
                try:
                    length = int(self.headers.get("Content-Length", "-1"))
                    if not host_id or len(host_id) > 64 or length < 0 or length > MAX_BODY: raise ValueError()
                    host = json.loads(self.rfile.read(length))
                    fields = ("id", "name", "lan_address", "external_address", "username", "icon", "selected_agent_command")
                    if not isinstance(host, dict) or host.get("id") != host_id: raise ValueError()
                    if any(not isinstance(host.get(key, ""), str) or len(host.get(key, "")) > 4096 for key in fields): raise ValueError()
                    port = host.get("port", 22)
                    lan_port = host.get("lan_port", port)
                    external_port = host.get("external_port", port)
                    aliases = host.get("legacy_keys", [])
                    if any(not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= 65535 for value in (port, lan_port, external_port)): raise ValueError()
                    if not isinstance(aliases, list) or len(aliases) > 16 or any(not isinstance(key, str) or len(key) > 256 for key in aliases): raise ValueError()
                    host = {key: host.get(key, "") for key in fields} | {"port": port, "lan_port": lan_port, "external_port": external_port, "legacy_keys": list(dict.fromkeys(aliases))}
                    host["id"] = host_id
                    payload = json.dumps(host, ensure_ascii=False)
                except (ValueError, AttributeError, UnicodeDecodeError, json.JSONDecodeError):
                    return self.reply(400, {"error": "invalid host profile"})
                with sqlite3.connect(database) as db:
                    db.execute("BEGIN IMMEDIATE")
                    db.execute("INSERT INTO account_hosts(user_id, host_id, payload, deleted) VALUES (?, ?, ?, 0) ON CONFLICT(user_id, host_id) DO UPDATE SET payload=excluded.payload, deleted=0", (self.user_id, host_id, payload))
                    legacy_ids = set(host["legacy_keys"])
                    matched_names = []
                    for old_id, state_payload in db.execute("SELECT host_id, payload FROM account_host_state WHERE user_id=?", (self.user_id,)):
                        try: old_name = json.loads(state_payload).get("name", "")
                        except (AttributeError, json.JSONDecodeError): old_name = ""
                        if old_id in legacy_ids and old_name.casefold() == host["name"].casefold(): matched_names.append(old_id)
                    other_named_profiles = [row[0] for row in db.execute("SELECT host_id, payload FROM account_hosts WHERE user_id=? AND host_id!=? AND deleted=0", (self.user_id, host_id))
                                            if json.loads(row[1]).get("name", "").casefold() == host["name"].casefold()]
                    if matched_names and not other_named_profiles:
                        for old_id, state_payload in db.execute("SELECT host_id, payload FROM account_host_state WHERE user_id=?", (self.user_id,)):
                            try: old_name = json.loads(state_payload).get("name", "")
                            except (AttributeError, json.JSONDecodeError): old_name = ""
                            if old_name.casefold() == host["name"].casefold(): legacy_ids.add(old_id)
                    for old_id in legacy_ids:
                        if old_id == host_id: continue
                        old = db.execute("SELECT payload FROM account_workspaces WHERE user_id=? AND host_id=?", (self.user_id, old_id)).fetchone()
                        current = db.execute("SELECT payload FROM account_workspaces WHERE user_id=? AND host_id=?", (self.user_id, host_id)).fetchone()
                        if old:
                            items = {item.get("id") or item.get("path"): item for item in json.loads(old[0]) + (json.loads(current[0]) if current else [])}
                            merged = json.dumps(list(items.values()), ensure_ascii=False)
                            db.execute("INSERT INTO account_workspaces VALUES (?, ?, ?) ON CONFLICT(user_id, host_id) DO UPDATE SET payload=excluded.payload", (self.user_id, host_id, merged))
                            revision = db.execute("SELECT COALESCE(MAX(revision), 0) + 1 FROM workspace_history WHERE user_id=? AND host_id=?", (self.user_id, host_id)).fetchone()[0]
                            db.execute("INSERT OR IGNORE INTO workspace_history VALUES (?, ?, ?, ?)", (self.user_id, host_id, revision, merged))
                        old_state = db.execute("SELECT revision, payload FROM account_host_state WHERE user_id=? AND host_id=?", (self.user_id, old_id)).fetchone()
                        if old_state: db.execute("INSERT OR IGNORE INTO account_host_state VALUES (?, ?, ?, ?)", (self.user_id, host_id, old_state[0], old_state[1]))
                        db.execute("INSERT OR IGNORE INTO account_sessions(user_id, host_id, username, id, payload) SELECT user_id, ?, username, id, payload FROM account_sessions WHERE user_id=? AND host_id=?", (host_id, self.user_id, old_id))
                return self.reply(200, {"host": host, "configured": True})
            if path.startswith("/v1/admin/users/"):
                if not self.admin_authorized(): return
                try:
                    user_id = int(path.rsplit("/", 1)[1])
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length < 0 or length > 8192: raise ValueError()
                    data = json.loads(self.rfile.read(length))
                    username = data.get("username", "").strip().lower()
                    password = data.get("password", "")
                    is_admin = bool(data.get("is_admin", False))
                    if user_id < 1 or not re.fullmatch(r"[a-z0-9_.-]{3,64}", username): raise ValueError("用户信息无效")
                    if password and (not isinstance(password, str) or not 8 <= len(password) <= 1024): raise ValueError("密码至少 8 个字符")
                except (ValueError, AttributeError, UnicodeDecodeError, json.JSONDecodeError) as exc:
                    return self.reply(400, {"error": str(exc) or "invalid user"})
                try:
                    with sqlite3.connect(database) as db:
                        current = db.execute("SELECT is_admin FROM users WHERE id = ?", (user_id,)).fetchone()
                        if not current: return self.reply(404, {"error": "user not found"})
                        if user_id == self.user_id and not is_admin: return self.reply(400, {"error": "cannot remove your own administrator access"})
                        admin_count = db.execute("SELECT COUNT(*) FROM users WHERE is_admin = 1").fetchone()[0]
                        if current[0] and not is_admin and admin_count <= 1: return self.reply(400, {"error": "at least one administrator is required"})
                        if password:
                            salt = secrets.token_bytes(16)
                            password_hash = hashlib.scrypt(password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32)
                            db.execute("UPDATE users SET username = ?, salt = ?, password_hash = ?, must_change = 0, is_admin = ? WHERE id = ?", (username, salt, password_hash, int(is_admin), user_id))
                            active_token = self.headers.get("Authorization", "")[7:]
                            if user_id == self.user_id and active_token:
                                active_hash = hashlib.sha256(active_token.encode()).digest()
                                db.execute("DELETE FROM auth_tokens WHERE user_id = ? AND token_hash != ?", (user_id, active_hash))
                            else:
                                db.execute("DELETE FROM auth_tokens WHERE user_id = ?", (user_id,))
                        else:
                            db.execute("UPDATE users SET username = ?, is_admin = ? WHERE id = ?", (username, int(is_admin), user_id))
                except sqlite3.IntegrityError:
                    return self.reply(409, {"error": "username already exists"})
                return self.reply(200, {"id": user_id, "username": username, "is_admin": is_admin})
            parts = path.strip("/").split("/")
            if len(parts) == 4 and parts[:2] == ["v1", "hosts"] and parts[3] == "state":
                host_id = parts[2]
                if not host_id or len(host_id) > 256:
                    return self.reply(404, {"error": "invalid host id"})
                try:
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length < 0 or length > MAX_BODY:
                        raise ValueError()
                    request = json.loads(self.rfile.read(length))
                    state, base_revision = request.get("state"), request.get("base_revision")
                    if not isinstance(state, dict) or not isinstance(base_revision, int) or base_revision < 0:
                        raise ValueError()
                    payload = json.dumps(state, ensure_ascii=False)
                except (ValueError, AttributeError, UnicodeDecodeError, json.JSONDecodeError):
                    return self.reply(400, {"error": "invalid host state"})
                with sqlite3.connect(database) as db:
                    db.execute("BEGIN IMMEDIATE")
                    row = db.execute("SELECT revision FROM account_host_state WHERE user_id = ? AND host_id = ?", (self.user_id, host_id)).fetchone()
                    revision = row[0] if row else 0
                    if base_revision != revision:
                        return self.reply(409, {"error": "host state conflict", "revision": revision})
                    revision += 1
                    db.execute("INSERT INTO account_host_state VALUES (?, ?, ?, ?) ON CONFLICT(user_id, host_id) DO UPDATE SET revision=excluded.revision, payload=excluded.payload", (self.user_id, host_id, revision, payload))
                return self.reply(200, {"state": state, "revision": revision, "configured": True})
            if len(parts) == 7 and parts[:2] == ["v1", "hosts"] and parts[3] == "users" and parts[5] == "sessions":
                host_id, username, session_id = parts[2], __import__("urllib.parse", fromlist=["unquote"]).unquote(parts[4]), parts[6]
                if not host_id or len(host_id) > 256 or not username or len(username) > 256 or len(session_id) != 32 or any(c not in "0123456789abcdef" for c in session_id):
                    return self.reply(404, {"error": "invalid host session"})
                try:
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length < 0 or length > MAX_BODY:
                        raise ValueError()
                    item = json.loads(self.rfile.read(length))
                    if not isinstance(item, dict) or set(item) != set(FIELDS) or item["id"] != session_id:
                        raise ValueError()
                    if any(not isinstance(item[field], str) or not item[field] or len(item[field]) > 4096 for field in FIELDS):
                        raise ValueError()
                except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
                    return self.reply(400, {"error": "invalid session"})
                with sqlite3.connect(database) as db:
                    db.execute("INSERT INTO account_sessions VALUES (?, ?, ?, ?, ?) ON CONFLICT(user_id, host_id, username, id) DO UPDATE SET payload=excluded.payload", (self.user_id, host_id, username, session_id, json.dumps(item, ensure_ascii=False)))
                return self.reply(200, item)
            if path.startswith("/v1/agents/"):
                host_id = path.removeprefix("/v1/agents/")
                if not host_id or len(host_id) > 256:
                    return self.reply(404, {"error": "invalid host id"})
                try:
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length < 0 or length > MAX_BODY:
                        raise ValueError()
                    request = json.loads(self.rfile.read(length))
                    items = request.get("agents")
                    base_revision = request.get("base_revision")
                    if base_revision is not None and (not isinstance(base_revision, int) or isinstance(base_revision, bool) or base_revision < 0):
                        raise ValueError()
                    if not isinstance(items, list) or len(items) > 1000:
                        raise ValueError()
                    seen = set()
                    for item in items:
                        if not isinstance(item, dict) or set(item) != set(AGENT_FIELDS):
                            raise ValueError()
                        if any(not isinstance(item[field], str) or not item[field] or len(item[field]) > 4096 for field in AGENT_FIELDS):
                            raise ValueError()
                        if item["id"] in seen:
                            raise ValueError()
                        seen.add(item["id"])
                except (ValueError, AttributeError, UnicodeDecodeError, json.JSONDecodeError):
                    return self.reply(400, {"error": "invalid agents"})
                items = [{**item, "id": hashlib.sha256(item["command"].encode("utf-8")).hexdigest()[:32]} for item in items]
                with sqlite3.connect(database) as db:
                    db.execute("BEGIN IMMEDIATE")
                    row = db.execute("SELECT payload FROM account_agents WHERE user_id = ? AND host_id = ?", (self.user_id, host_id)).fetchone()
                    current = json.loads(row[0]) if row else []
                    current_revision = db.execute("SELECT MAX(revision) FROM agent_history WHERE user_id = ? AND host_id = ?", (self.user_id, host_id)).fetchone()[0]
                    if current_revision is None:
                        db.execute("INSERT OR IGNORE INTO agent_history VALUES (?, ?, 0, ?)", (self.user_id, host_id, json.dumps([], ensure_ascii=False)))
                        if row:
                            db.execute("INSERT OR IGNORE INTO agent_history VALUES (?, ?, 1, ?)", (self.user_id, host_id, row[0]))
                            current_revision = 1
                        else:
                            current_revision = 0
                    if base_revision is None: base_revision = current_revision
                    base_row = db.execute("SELECT payload FROM agent_history WHERE user_id = ? AND host_id = ? AND revision = ?", (self.user_id, host_id, base_revision)).fetchone()
                    if base_row is None: return self.reply(409, {"error": "agent revision expired", "revision": current_revision})
                    merged = merge_agents(json.loads(base_row[0]), current, items)
                    payload = json.dumps(merged, ensure_ascii=False)
                    new_revision = current_revision + 1
                    db.execute("INSERT INTO account_agents VALUES (?, ?, ?) ON CONFLICT(user_id, host_id) DO UPDATE SET payload=excluded.payload", (self.user_id, host_id, payload))
                    db.execute("INSERT INTO agent_history VALUES (?, ?, ?, ?)", (self.user_id, host_id, new_revision, payload))
                return self.reply(200, {"agents": merged, "configured": True, "revision": new_revision})
            if path.startswith("/v1/workspaces/"):
                host_id = path.removeprefix("/v1/workspaces/")
                if not host_id or len(host_id) > 256:
                    return self.reply(404, {"error": "invalid host id"})
                try:
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length < 0 or length > MAX_BODY:
                        raise ValueError()
                    request = json.loads(self.rfile.read(length))
                    items = request.get("workspaces")
                    base_revision = request.get("base_revision")
                    if base_revision is not None and (not isinstance(base_revision, int) or isinstance(base_revision, bool) or base_revision < 0):
                        raise ValueError()
                    if not isinstance(items, list) or len(items) > 1000:
                        raise ValueError()
                    seen = set()
                    for item in items:
                        if not isinstance(item, dict) or set(item) != set(WORKSPACE_FIELDS):
                            raise ValueError()
                        if any(not isinstance(item[field], str) or not item[field] or len(item[field]) > 4096 for field in WORKSPACE_FIELDS):
                            raise ValueError()
                        if item["id"] in seen:
                            raise ValueError()
                        seen.add(item["id"])
                except (ValueError, AttributeError, UnicodeDecodeError, json.JSONDecodeError):
                    return self.reply(400, {"error": "invalid workspaces"})
                items = [{**item, "id": hashlib.sha256(item["path"].encode("utf-8")).hexdigest()[:32]} for item in items]
                if len({item["id"] for item in items}) != len(items):
                    return self.reply(400, {"error": "duplicate workspace path"})
                with sqlite3.connect(database) as db:
                    db.execute("BEGIN IMMEDIATE")
                    row = db.execute("SELECT payload FROM account_workspaces WHERE user_id = ? AND host_id = ?", (self.user_id, host_id)).fetchone()
                    current = json.loads(row[0]) if row else []
                    current_revision = db.execute("SELECT MAX(revision) FROM workspace_history WHERE user_id = ? AND host_id = ?", (self.user_id, host_id)).fetchone()[0]
                    if current_revision is None:
                        db.execute("INSERT OR IGNORE INTO workspace_history VALUES (?, ?, 0, ?)", (self.user_id, host_id, json.dumps([], ensure_ascii=False)))
                        if row:
                            db.execute("INSERT OR IGNORE INTO workspace_history VALUES (?, ?, 1, ?)", (self.user_id, host_id, row[0]))
                            current_revision = 1
                        else:
                            current_revision = 0
                    if base_revision is None:
                        base_revision = current_revision
                    base_row = db.execute("SELECT payload FROM workspace_history WHERE user_id = ? AND host_id = ? AND revision = ?", (self.user_id, host_id, base_revision)).fetchone()
                    if base_row is None:
                        return self.reply(409, {"error": "workspace revision expired", "revision": current_revision})
                    merged = merge_workspaces(json.loads(base_row[0]), current, items)
                    payload = json.dumps(merged, ensure_ascii=False)
                    new_revision = current_revision + 1
                    db.execute("INSERT INTO account_workspaces VALUES (?, ?, ?) ON CONFLICT(user_id, host_id) DO UPDATE SET payload=excluded.payload", (self.user_id, host_id, payload))
                    db.execute("INSERT INTO workspace_history VALUES (?, ?, ?, ?)", (self.user_id, host_id, new_revision, payload))
                return self.reply(200, {"workspaces": merged, "configured": True, "revision": new_revision, "etag": workspace_revision(payload)})
            session_id = path.removeprefix("/v1/sessions/")
            if not path.startswith("/v1/sessions/") or len(session_id) != 32 or any(c not in "0123456789abcdef" for c in session_id):
                return self.reply(404, {"error": "invalid session id"})
            try:
                length = int(self.headers.get("Content-Length", "-1"))
                if length < 0 or length > MAX_BODY:
                    raise ValueError()
                item = json.loads(self.rfile.read(length))
                if not isinstance(item, dict) or set(item) != set(FIELDS) or item["id"] != session_id:
                    raise ValueError()
                if any(not isinstance(item[field], str) or not item[field] or len(item[field]) > 4096 for field in FIELDS):
                    raise ValueError()
            except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
                return self.reply(400, {"error": "invalid session"})
            with sqlite3.connect(database) as db:
                db.execute("INSERT INTO account_sessions VALUES (?, ?, ?, ?, ?) ON CONFLICT(user_id, host_id, username, id) DO UPDATE SET payload=excluded.payload", (self.user_id, "legacy", "legacy", session_id, json.dumps(item, ensure_ascii=False)))
            self.reply(200, item)

        def do_DELETE(self):
            path = urlsplit(self.path).path
            if path.startswith("/v1/admin/users/"):
                if not self.admin_authorized(): return
                try: user_id = int(path.rsplit("/", 1)[1])
                except ValueError: return self.reply(404, {"error": "user not found"})
                if user_id == self.user_id: return self.reply(400, {"error": "cannot delete current account"})
                with sqlite3.connect(database) as db:
                    row = db.execute("SELECT is_admin FROM users WHERE id = ?", (user_id,)).fetchone()
                    if not row: return self.reply(404, {"error": "user not found"})
                    if row[0] and db.execute("SELECT COUNT(*) FROM users WHERE is_admin = 1").fetchone()[0] <= 1:
                        return self.reply(400, {"error": "at least one administrator is required"})
                    for table in ("auth_tokens", "account_sessions", "account_workspaces", "workspace_history", "account_agents", "agent_history", "account_host_state", "account_hosts"):
                        db.execute(f"DELETE FROM {table} WHERE user_id = ?", (user_id,))
                    db.execute("DELETE FROM users WHERE id = ?", (user_id,))
                return self.reply(200, {"deleted": user_id})
            if not self.authorized(): return
            if path.startswith("/v1/hosts/") and path.count("/") == 3:
                host_id = path.removeprefix("/v1/hosts/")
                with sqlite3.connect(database) as db:
                    db.execute("UPDATE account_hosts SET deleted=1 WHERE user_id=? AND host_id=?", (self.user_id, host_id))
                return self.reply(200, {"deleted": True})
            parts = path.strip("/").split("/")
            if len(parts) == 7 and parts[:2] == ["v1", "hosts"] and parts[3] == "users" and parts[5] == "sessions":
                host_id, username, session_id = parts[2], __import__("urllib.parse", fromlist=["unquote"]).unquote(parts[4]), parts[6]
                if not host_id or len(host_id) > 256 or not username or len(username) > 256 or len(session_id) != 32 or any(c not in "0123456789abcdef" for c in session_id):
                    return self.reply(404, {"error": "invalid host session"})
                with sqlite3.connect(database) as db:
                    db.execute("DELETE FROM account_sessions WHERE user_id = ? AND host_id = ? AND username = ? AND id = ?", (self.user_id, host_id, username, session_id))
                return self.reply(200, {"deleted": session_id})
            session_id = path.removeprefix("/v1/sessions/")
            if not path.startswith("/v1/sessions/") or len(session_id) != 32 or any(c not in "0123456789abcdef" for c in session_id):
                return self.reply(404, {"error": "invalid session id"})
            with sqlite3.connect(database) as db:
                db.execute("DELETE FROM account_sessions WHERE user_id = ? AND host_id = 'legacy' AND username = 'legacy' AND id = ?", (self.user_id, session_id))
            self.reply(200, {"deleted": session_id})

    return ThreadingHTTPServer(address, Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--database", default="~/.local/share/remote-dev-terminal/sessions.db")
    args = parser.parse_args()
    database = Path(args.database).expanduser()
    database.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    server = create_server((args.host, args.port), database)
    os.chmod(database, 0o600)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
