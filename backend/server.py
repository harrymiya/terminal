#!/usr/bin/env python3
"""Small authenticated, persistent registry of remote terminal sessions."""
import argparse
from datetime import datetime, timezone
import hmac
import hashlib
import json
import os
import re
import secrets
import sqlite3
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qsl, urlencode, urlsplit
from urllib.request import ProxyHandler, Request, build_opener

MAX_BODY = 65536
MAX_MEMORY_BODY = 2 * 1024 * 1024
FIELDS = ("id", "directory_path", "directory_name", "agent_name", "agent_command", "display_name")
OPTIONAL_SESSION_FIELDS = ("title_source",)
WORKSPACE_FIELDS = ("id", "name", "path", "icon")
AGENT_FIELDS = ("id", "name", "command", "icon")
MEMORY_CATEGORIES = {"profile", "preference", "project", "decision", "goal", "environment", "general"}
MEM0_DEFAULT_URL = "http://192.168.20.45:8888"


def call_mem0(method, path, payload=None):
    """Call the machine-local Mem0 service without exposing its key to clients or logs."""
    base_url = os.environ.get("RDT_MEM0_URL", MEM0_DEFAULT_URL).rstrip("/")
    api_key = os.environ.get("MEM0_API_KEY", "").strip()
    if not api_key:
        key_file = os.environ.get("MEM0_API_KEY_FILE", "/home/agent/.config/mem0/api-key")
        try:
            api_key = Path(key_file).read_text(encoding="utf-8").strip()
        except OSError:
            api_key = ""
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    headers = {"Accept": "application/json", "Connection": "close"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if api_key:
        headers["X-API-Key"] = api_key
    request = Request(base_url + path, data=body, headers=headers, method=method)
    try:
        opener = build_opener(ProxyHandler({}))
        with opener.open(request, timeout=45) as response:
            raw = response.read(1_048_577)
    except HTTPError as error:
        raise RuntimeError(f"Mem0 service returned HTTP {error.code}") from None
    except (URLError, TimeoutError, OSError):
        raise RuntimeError("Mem0 service is unavailable") from None
    if len(raw) > 1_048_576:
        raise RuntimeError("Mem0 response is too large")
    try:
        result = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise RuntimeError("Mem0 returned an invalid response") from None
    if not isinstance(result, dict):
        raise RuntimeError("Mem0 returned an invalid response")
    return result


def memory_category(text):
    value = str(text).lower()
    if any(word in value for word in ("喜欢", "偏好", "习惯", "prefer", "favorite")):
        return "preference"
    if any(word in value for word in ("项目", "工作区", "代码库", "仓库", "project", "repository")):
        return "project"
    if any(word in value for word in ("决定", "约定", "规则", "decision", "agreed")):
        return "decision"
    if any(word in value for word in ("目标", "计划", "想要", "goal", "plan")):
        return "goal"
    if any(word in value for word in ("环境", "设备", "主机", "系统", "environment", "device")):
        return "environment"
    if any(word in value for word in ("我叫", "我是", "我的", "家人", "生日", "name is", "my family")):
        return "profile"
    return "general"


def memory_timestamp(value, fallback):
    if isinstance(value, (int, float)):
        return int(value)
    text = str(value or "").strip()
    try:
        return int(text)
    except ValueError:
        try:
            parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            return int(parsed.timestamp() * 1000)
        except ValueError:
            return fallback


def normalize_memory_result(result, now_ms=None):
    if not isinstance(result, dict):
        return None
    content = str(result.get("memory") or result.get("text") or "").strip()[:2000]
    if not content:
        return None
    metadata = result.get("metadata") if isinstance(result.get("metadata"), dict) else {}
    category = str(metadata.get("category") or memory_category(content))
    if category not in MEMORY_CATEGORIES:
        category = memory_category(content)
    try:
        importance = max(1, min(5, int(metadata.get("importance", 3))))
    except (TypeError, ValueError):
        importance = 3
    now = int(now_ms or time.time() * 1000)
    memory_id = str(result.get("id") or hashlib.sha256(content.casefold().encode()).hexdigest())[:128]
    return {
        "id": memory_id,
        "content": content,
        "category": category,
        "importance": importance,
        "created_at": memory_timestamp(result.get("created_at"), now),
        "updated_at": memory_timestamp(result.get("updated_at"), now),
        "last_recalled_at": 0,
        "recall_count": 0,
        "source": "mem0",
    }


def upsert_short_memory(db, user_id, item):
    db.execute(
        "INSERT INTO jarvis_short_memories(user_id,id,content,category,importance,created_at,updated_at,last_recalled_at,recall_count,source) "
        "VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,id) DO UPDATE SET "
        "content=CASE WHEN excluded.updated_at >= jarvis_short_memories.updated_at THEN excluded.content ELSE jarvis_short_memories.content END, "
        "category=CASE WHEN excluded.updated_at >= jarvis_short_memories.updated_at THEN excluded.category ELSE jarvis_short_memories.category END, "
        "importance=MAX(jarvis_short_memories.importance,excluded.importance), "
        "created_at=MIN(jarvis_short_memories.created_at,excluded.created_at), updated_at=MAX(jarvis_short_memories.updated_at,excluded.updated_at), "
        "last_recalled_at=MAX(jarvis_short_memories.last_recalled_at,excluded.last_recalled_at), "
        "recall_count=MAX(jarvis_short_memories.recall_count,excluded.recall_count), source=excluded.source",
        (user_id, item["id"], item["content"], item["category"], item["importance"], item["created_at"], item["updated_at"], item["last_recalled_at"], item["recall_count"], item["source"]),
    )


def host_endpoint_keys(host):
    pairs = set()
    for address_key, port_key in (("lan_address", "lan_port"), ("external_address", "external_port")):
        address = str(host.get(address_key, "")).strip().casefold()
        if address:
            raw_port = host.get(port_key, host.get("port"))
            if raw_port is None:
                continue
            try:
                port = int(raw_port)
            except (TypeError, ValueError):
                continue
            if 1 <= port <= 65535:
                pairs.add((address, port))
    if not pairs and host.get("address") and host.get("port") is not None:
        try:
            port = int(host["port"])
        except (TypeError, ValueError):
            port = 0
        if 1 <= port <= 65535:
            pairs.add((str(host["address"]).strip().casefold(), port))
    return pairs


def merge_duplicate_host_profiles(db, user_id, *args):
    rows = db.execute("SELECT host_id, payload FROM account_hosts WHERE user_id=? AND deleted=0 ORDER BY rowid", (user_id,)).fetchall()
    groups = {}
    for host_id, payload in rows:
        try:
            host = json.loads(payload)
            endpoints = tuple(sorted(host_endpoint_keys(host)))
            username = str(host.get("username", "")).casefold()
        except (AttributeError, TypeError, ValueError, json.JSONDecodeError):
            continue
        if endpoints and username:
            groups.setdefault((username, endpoints), []).append((host_id, host))
    for profiles in groups.values():
        if len(profiles) < 2:
            continue
        canonical_id, _ = profiles[0]
        canonical = dict(profiles[-1][1])
        canonical["id"] = canonical_id
        legacy_keys = []
        for _, profile in profiles:
            for key in profile.get("legacy_keys", []):
                if isinstance(key, str) and key not in legacy_keys:
                    legacy_keys.append(key)
        canonical["legacy_keys"] = legacy_keys[:16]
        db.execute("UPDATE account_hosts SET payload=? WHERE user_id=? AND host_id=?", (json.dumps(canonical, ensure_ascii=False), user_id, canonical_id))
        for duplicate_id, duplicate in profiles[1:]:
            old_workspace = db.execute("SELECT payload FROM account_workspaces WHERE user_id=? AND host_id=?", (user_id, duplicate_id)).fetchone()
            current_workspace = db.execute("SELECT payload FROM account_workspaces WHERE user_id=? AND host_id=?", (user_id, canonical_id)).fetchone()
            if old_workspace:
                items = {}
                for item in (json.loads(current_workspace[0]) if current_workspace else []) + json.loads(old_workspace[0]):
                    items[item.get("id") or item.get("path")] = item
                merged = json.dumps(list(items.values()), ensure_ascii=False)
                db.execute("INSERT INTO account_workspaces VALUES (?, ?, ?) ON CONFLICT(user_id, host_id) DO UPDATE SET payload=excluded.payload", (user_id, canonical_id, merged))
                for revision, history_payload in db.execute("SELECT revision, payload FROM workspace_history WHERE user_id=? AND host_id=?", (user_id, duplicate_id)).fetchall():
                    db.execute("INSERT OR IGNORE INTO workspace_history VALUES (?, ?, ?, ?)", (user_id, canonical_id, revision, history_payload))
                next_revision = db.execute("SELECT COALESCE(MAX(revision), 0) + 1 FROM workspace_history WHERE user_id=? AND host_id=?", (user_id, canonical_id)).fetchone()[0]
                db.execute("INSERT OR IGNORE INTO workspace_history VALUES (?, ?, ?, ?)", (user_id, canonical_id, next_revision, merged))
            old_state = db.execute("SELECT revision, payload FROM account_host_state WHERE user_id=? AND host_id=?", (user_id, duplicate_id)).fetchone()
            if old_state:
                current_state = db.execute("SELECT revision FROM account_host_state WHERE user_id=? AND host_id=?", (user_id, canonical_id)).fetchone()
                if current_state is None:
                    db.execute("INSERT INTO account_host_state VALUES (?, ?, ?, ?)", (user_id, canonical_id, old_state[0], old_state[1]))
                elif old_state[0] > current_state[0]:
                    db.execute("UPDATE account_host_state SET revision=?, payload=? WHERE user_id=? AND host_id=?", (old_state[0], old_state[1], user_id, canonical_id))
            db.execute("INSERT OR IGNORE INTO account_sessions(user_id, host_id, username, id, payload) SELECT user_id, ?, username, id, payload FROM account_sessions WHERE user_id=? AND host_id=?", (canonical_id, user_id, duplicate_id))
            db.execute("DELETE FROM account_sessions WHERE user_id=? AND host_id=?", (user_id, duplicate_id))
            deduplicate_sessions_by_workspace(db, user_id, canonical_id)
            old_agents = db.execute("SELECT payload FROM account_agents WHERE user_id=? AND host_id=?", (user_id, duplicate_id)).fetchone()
            current_agents = db.execute("SELECT payload FROM account_agents WHERE user_id=? AND host_id=?", (user_id, canonical_id)).fetchone()
            if old_agents and not current_agents:
                db.execute("INSERT INTO account_agents VALUES (?, ?, ?)", (user_id, canonical_id, old_agents[0]))
            for revision, agent_payload in db.execute("SELECT revision, payload FROM agent_history WHERE user_id=? AND host_id=?", (user_id, duplicate_id)).fetchall():
                db.execute("INSERT OR IGNORE INTO agent_history VALUES (?, ?, ?, ?)", (user_id, canonical_id, revision, agent_payload))
            duplicate["canonical_id"] = canonical_id
            db.execute("UPDATE account_hosts SET payload=?, deleted=1 WHERE user_id=? AND host_id=?", (json.dumps(duplicate, ensure_ascii=False), user_id, duplicate_id))


def deduplicate_sessions_by_workspace(db, user_id, host_id):
    rows = db.execute("SELECT rowid, payload FROM account_sessions WHERE user_id=? AND host_id=? ORDER BY rowid DESC", (user_id, host_id)).fetchall()
    seen = set()
    for rowid, payload_str in rows:
        try:
            payload = json.loads(payload_str)
            d_path = payload.get("directory_path")
            a_cmd = payload.get("agent_command")
            if d_path is not None and a_cmd is not None:
                ws_key = (d_path, a_cmd)
                if ws_key in seen:
                    db.execute("DELETE FROM account_sessions WHERE rowid=?", (rowid,))
                else:
                    seen.add(ws_key)
        except Exception:
            pass


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
    memory_consolidation_lock = threading.Lock()
    memory_consolidation_users = set()
    memory_consolidation_force_users = set()
    with sqlite3.connect(database) as db:
        db.execute("CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS host_sessions (host_id TEXT NOT NULL, username TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(host_id, username, id))")
        db.execute("CREATE TABLE IF NOT EXISTS host_state (host_id TEXT PRIMARY KEY, revision INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS workspaces (host_id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS agents (host_id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, username TEXT UNIQUE NOT NULL, salt BLOB NOT NULL, password_hash BLOB NOT NULL, must_change INTEGER NOT NULL DEFAULT 0, is_admin INTEGER NOT NULL DEFAULT 0, theme TEXT NOT NULL DEFAULT '')")
        try:
            db.execute("ALTER TABLE users ADD COLUMN must_change INTEGER NOT NULL DEFAULT 0")
        except sqlite3.OperationalError:
            pass
        try:
            db.execute("ALTER TABLE users ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0")
        except sqlite3.OperationalError:
            pass
        try:
            db.execute("ALTER TABLE users ADD COLUMN theme TEXT NOT NULL DEFAULT ''")
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
        db.execute("CREATE TABLE IF NOT EXISTS account_operation_logs (user_id INTEGER NOT NULL, id TEXT NOT NULL, created_at INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(user_id, id))")
        db.execute("CREATE TABLE IF NOT EXISTS jarvis_short_memories (user_id INTEGER NOT NULL, id TEXT NOT NULL, content TEXT NOT NULL, category TEXT NOT NULL, importance INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, last_recalled_at INTEGER NOT NULL DEFAULT 0, recall_count INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL DEFAULT 'jarvis', PRIMARY KEY(user_id, id))")
        db.execute("CREATE INDEX IF NOT EXISTS idx_jarvis_short_memories_user_rank ON jarvis_short_memories(user_id, importance DESC, updated_at DESC)")
        db.execute("CREATE TABLE IF NOT EXISTS jarvis_memory_turns (user_id INTEGER NOT NULL, id TEXT NOT NULL, created_at INTEGER NOT NULL, user_text TEXT NOT NULL, assistant_text TEXT NOT NULL, PRIMARY KEY(user_id, id))")
        db.execute("CREATE INDEX IF NOT EXISTS idx_jarvis_memory_turns_user_time ON jarvis_memory_turns(user_id, created_at ASC)")
        db.execute("CREATE TABLE IF NOT EXISTS jarvis_memory_recalls (user_id INTEGER NOT NULL, memory_id TEXT NOT NULL, recall_count INTEGER NOT NULL DEFAULT 0, last_recalled_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(user_id, memory_id))")
        db.execute("CREATE TABLE IF NOT EXISTS jarvis_memory_namespaces (user_id INTEGER PRIMARY KEY, namespace TEXT NOT NULL UNIQUE)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_operation_logs_user_time ON account_operation_logs(user_id, created_at DESC)")
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

    def memory_items_for(user_id, limit=512):
        with sqlite3.connect(database) as db:
            rows = db.execute(
                "SELECT id, content, category, importance, created_at, updated_at, last_recalled_at, recall_count, source "
                "FROM jarvis_short_memories WHERE user_id=? ORDER BY importance DESC, updated_at DESC LIMIT ?",
                (user_id, max(1, min(512, int(limit)))),
            ).fetchall()
        keys = ("id", "content", "category", "importance", "created_at", "updated_at", "last_recalled_at", "recall_count", "source")
        return [dict(zip(keys, row)) for row in rows]

    def memory_namespace(user_id):
        with sqlite3.connect(database) as db:
            namespace = "rdt-" + uuid.uuid4().hex
            db.execute("INSERT OR IGNORE INTO jarvis_memory_namespaces(user_id,namespace) VALUES(?,?)", (user_id, namespace))
            return db.execute("SELECT namespace FROM jarvis_memory_namespaces WHERE user_id=?", (user_id,)).fetchone()[0]

    def consolidate_memory_turns(user_id, force=False):
        now = int(time.time() * 1000)
        with sqlite3.connect(database) as db:
            rows = db.execute(
                "SELECT id, created_at, user_text, assistant_text FROM jarvis_memory_turns WHERE user_id=? ORDER BY created_at ASC LIMIT 20",
                (user_id,),
            ).fetchall()
        if not rows:
            return {"consolidated": False, "memories": []}
        should_run = force or len(rows) >= 5 or (len(rows) >= 2 and now - int(rows[0][1]) >= 24 * 60 * 60 * 1000)
        if not should_run:
            return {"consolidated": False, "memories": []}
        messages = []
        for _, _, user_text, assistant_text in rows:
            messages.append({"role": "user", "content": user_text})
            if assistant_text:
                messages.append({"role": "assistant", "content": assistant_text})
        response = call_mem0("POST", "/v1/memories", {
            "messages": messages,
            "infer": True,
            "user_id": memory_namespace(user_id),
            "agent_id": "jarvis",
            "metadata": {"source": "jarvis_voice", "tier": "long", "category": "general"},
        })
        raw_results = response.get("results", [])
        memories = []
        with sqlite3.connect(database) as db:
            for result in raw_results if isinstance(raw_results, list) else []:
                if not isinstance(result, dict):
                    continue
                memory_id = str(result.get("id") or "")[:128]
                event = str(result.get("event") or "").upper()
                if memory_id and event == "DELETE":
                    db.execute("DELETE FROM jarvis_short_memories WHERE user_id=? AND id=?", (user_id, memory_id))
                    continue
                item = normalize_memory_result(result)
                if item is not None:
                    upsert_short_memory(db, user_id, item)
                    memories.append(item)
            db.executemany("DELETE FROM jarvis_memory_turns WHERE user_id=? AND id=?", [(user_id, row[0]) for row in rows])
            db.execute(
                "DELETE FROM jarvis_short_memories WHERE user_id=? AND id NOT IN "
                "(SELECT id FROM jarvis_short_memories WHERE user_id=? ORDER BY importance DESC, updated_at DESC LIMIT 512)",
                (user_id, user_id),
            )
        return {"consolidated": True, "memories": memories}

    def schedule_memory_consolidation(user_id, force=False):
        with memory_consolidation_lock:
            if user_id in memory_consolidation_users:
                if force:
                    memory_consolidation_force_users.add(user_id)
                return
            memory_consolidation_users.add(user_id)

        def worker():
            should_force = force
            while True:
                try:
                    consolidate_memory_turns(user_id, force=should_force)
                except Exception:
                    # Keep candidates for retry on a later turn; never log conversation content or service keys.
                    pass
                with memory_consolidation_lock:
                    should_force = user_id in memory_consolidation_force_users
                    memory_consolidation_force_users.discard(user_id)
                    if should_force:
                        continue
                    memory_consolidation_users.discard(user_id)
                    return

        threading.Thread(target=worker, name="jarvis-memory-consolidation", daemon=True).start()

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
                account = db.execute("SELECT is_admin, theme FROM users WHERE id = ?", (user_id,)).fetchone()
            access_token, expiry = self.issue_token(user_id)
            return self.reply(200, {"username": username, "access_token": access_token, "expires_at": expiry,
                                   "must_change": bool(must_change) if not register else False,
                                   "is_admin": bool(account and account[0]),
                                   "theme": (account[1] or "") if account else ""})

        def do_GET(self):
            if urlsplit(self.path).path == "/v1/hosts":
                if not self.authorized(): return
                with sqlite3.connect(database) as db:
                    db.execute("BEGIN IMMEDIATE")
                    merge_duplicate_host_profiles(db, self.user_id)
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
                    row = db.execute("SELECT username, must_change, is_admin, theme FROM users WHERE id = ?", (self.user_id,)).fetchone()
                return self.reply(200, {"username": row[0] if row else "legacy", "must_change": bool(row and row[1]), "is_admin": bool(row and row[2]), "theme": (row[3] or "") if row else ""})
            if urlsplit(self.path).path == "/v1/preferences":
                if not self.authorized(): return
                with sqlite3.connect(database) as db:
                    row = db.execute("SELECT theme FROM users WHERE id = ?", (self.user_id,)).fetchone()
                return self.reply(200, {"theme": (row[0] or "") if row else ""})
            if not self.authorized():
                return
            path = urlsplit(self.path).path
            parts = path.strip("/").split("/")
            if path == "/v1/jarvis/short-memory":
                return self.reply(200, {"items": memory_items_for(self.user_id)})
            if path == "/v1/jarvis/long-memory":
                try:
                    raw_limit = dict(parse_qsl(urlsplit(self.path).query)).get("limit", "100")
                    limit = max(1, min(500, int(raw_limit)))
                    namespace = memory_namespace(self.user_id)
                    result = call_mem0("GET", "/v1/memories?" + urlencode({"user_id": namespace, "agent_id": "jarvis", "limit": limit}))
                    memories = result.get("results", []) if isinstance(result.get("results"), list) else []
                    ids = [str(item.get("id", ""))[:128] for item in memories if isinstance(item, dict) and item.get("id")]
                    recall_map = {}
                    if ids:
                        with sqlite3.connect(database) as db:
                            marks = ",".join("?" for _ in ids)
                            recall_map = {row[0]: {"recall_count": row[1], "last_recalled_at": row[2]} for row in db.execute(
                                f"SELECT memory_id, recall_count, last_recalled_at FROM jarvis_memory_recalls WHERE user_id=? AND memory_id IN ({marks})",
                                (self.user_id, *ids),
                            )}
                    for item in memories:
                        if not isinstance(item, dict):
                            continue
                        item.update(recall_map.get(str(item.get("id", "")), {"recall_count": 0, "last_recalled_at": 0}))
                        metadata = item.get("metadata") if isinstance(item.get("metadata"), dict) else {}
                        metadata.setdefault("category", memory_category(item.get("memory") or item.get("text") or ""))
                        item["metadata"] = metadata
                    return self.reply(200, {"results": memories})
                except (ValueError, RuntimeError):
                    return self.reply(502, {"error": "Mem0 长期记忆服务暂不可用"})
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
            if path == "/v1/operation-logs":
                try:
                    raw_limit = dict(parse_qsl(urlsplit(self.path).query)).get("limit", "200")
                    limit = max(1, min(500, int(raw_limit)))
                except (TypeError, ValueError):
                    limit = 200
                with sqlite3.connect(database) as db:
                    rows = db.execute(
                        "SELECT payload FROM account_operation_logs WHERE user_id = ? ORDER BY created_at DESC, rowid DESC LIMIT ?",
                        (self.user_id, limit),
                    ).fetchall()
                return self.reply(200, {"logs": [json.loads(r[0]) for r in rows]})
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
                    if not isinstance(password, str) or not 6 <= len(password) <= 1024: raise ValueError("密码至少 6 个字符")
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
            if path.startswith("/v1/jarvis/"):
                if not self.authorized(): return
                try:
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length < 0 or length > MAX_MEMORY_BODY:
                        raise ValueError()
                    request_data = json.loads(self.rfile.read(length))
                    if not isinstance(request_data, dict):
                        raise ValueError()
                except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
                    return self.reply(400, {"error": "invalid Jarvis memory request"})

                if path == "/v1/jarvis/short-memory/sync":
                    items = request_data.get("items", [])
                    if not isinstance(items, list) or len(items) > 512:
                        return self.reply(400, {"error": "invalid short memory batch"})
                    normalized = []
                    now = int(time.time() * 1000)
                    for raw in items:
                        if not isinstance(raw, dict):
                            return self.reply(400, {"error": "invalid short memory item"})
                        memory_id = str(raw.get("id", "")).strip()[:128]
                        content = str(raw.get("content", "")).strip()[:2000]
                        category = str(raw.get("category", "general"))
                        try:
                            importance = max(1, min(5, int(raw.get("importance", 3))))
                            created_at = max(1, int(raw.get("created_at", now)))
                            updated_at = max(created_at, int(raw.get("updated_at", now)))
                            last_recalled_at = max(0, int(raw.get("last_recalled_at", 0)))
                            recall_count = max(0, min(1_000_000, int(raw.get("recall_count", 0))))
                        except (TypeError, ValueError):
                            return self.reply(400, {"error": "invalid short memory metadata"})
                        if not memory_id or not content or category not in MEMORY_CATEGORIES:
                            return self.reply(400, {"error": "invalid short memory item"})
                        normalized.append({
                            "id": memory_id, "content": content, "category": category,
                            "importance": importance, "created_at": created_at, "updated_at": updated_at,
                            "last_recalled_at": last_recalled_at, "recall_count": recall_count,
                            "source": str(raw.get("source", "jarvis"))[:64] or "jarvis",
                        })
                    with sqlite3.connect(database) as db:
                        for item in normalized:
                            upsert_short_memory(db, self.user_id, item)
                        db.execute(
                            "DELETE FROM jarvis_short_memories WHERE user_id=? AND id NOT IN "
                            "(SELECT id FROM jarvis_short_memories WHERE user_id=? ORDER BY importance DESC, updated_at DESC LIMIT 512)",
                            (self.user_id, self.user_id),
                        )
                    return self.reply(200, {"items": memory_items_for(self.user_id, 512)})

                if path == "/v1/jarvis/memory/turns":
                    turns = request_data.get("turns", [])
                    if not isinstance(turns, list) or len(turns) > 20:
                        return self.reply(400, {"error": "invalid conversation batch"})
                    now = int(time.time() * 1000)
                    normalized_turns = []
                    for turn in turns:
                        if not isinstance(turn, dict):
                            return self.reply(400, {"error": "invalid conversation item"})
                        turn_id = str(turn.get("id", "")).strip()[:64]
                        user_text = str(turn.get("user_text", "")).strip()[:2000]
                        assistant_text = str(turn.get("assistant_text", "")).strip()[:2000]
                        try:
                            created_at = max(1, int(turn.get("created_at", now)))
                        except (TypeError, ValueError):
                            return self.reply(400, {"error": "invalid conversation timestamp"})
                        if not turn_id or not user_text:
                            return self.reply(400, {"error": "invalid conversation item"})
                        normalized_turns.append((self.user_id, turn_id, created_at, user_text, assistant_text))
                    with sqlite3.connect(database) as db:
                        db.executemany(
                            "INSERT OR IGNORE INTO jarvis_memory_turns(user_id,id,created_at,user_text,assistant_text) VALUES(?,?,?,?,?)",
                            normalized_turns,
                        )
                        db.execute(
                            "DELETE FROM jarvis_memory_turns WHERE user_id=? AND created_at < ?",
                            (self.user_id, now - 30 * 24 * 60 * 60 * 1000),
                        )
                    schedule_memory_consolidation(self.user_id, force=bool(request_data.get("force", False)))
                    return self.reply(202, {"queued": True})

                if path == "/v1/jarvis/long-memory/search":
                    query = str(request_data.get("query", "")).strip()[:1000]
                    try:
                        limit = max(1, min(10, int(request_data.get("limit", 5))))
                    except (TypeError, ValueError):
                        return self.reply(400, {"error": "invalid memory search limit"})
                    if not query:
                        return self.reply(400, {"error": "query is required"})
                    try:
                        namespace = memory_namespace(self.user_id)
                        result = call_mem0("POST", "/v1/memories/search", {
                            "query": query, "user_id": namespace, "agent_id": "jarvis", "limit": limit,
                        })
                    except RuntimeError:
                        return self.reply(502, {"error": "Mem0 长期记忆服务暂不可用"})
                    memories = result.get("results", []) if isinstance(result.get("results"), list) else []
                    now = int(time.time() * 1000)
                    response_items = []
                    with sqlite3.connect(database) as db:
                        for item in memories:
                            if not isinstance(item, dict):
                                continue
                            memory_id = str(item.get("id", ""))[:128]
                            if memory_id:
                                db.execute(
                                    "INSERT INTO jarvis_memory_recalls(user_id,memory_id,recall_count,last_recalled_at) VALUES(?,?,1,?) "
                                    "ON CONFLICT(user_id,memory_id) DO UPDATE SET recall_count=recall_count+1,last_recalled_at=excluded.last_recalled_at",
                                    (self.user_id, memory_id, now),
                                )
                                recall = db.execute("SELECT recall_count FROM jarvis_memory_recalls WHERE user_id=? AND memory_id=?", (self.user_id, memory_id)).fetchone()
                                item["recall_count"] = recall[0] if recall else 1
                                item["last_recalled_at"] = now
                            metadata = item.get("metadata") if isinstance(item.get("metadata"), dict) else {}
                            metadata.setdefault("category", memory_category(item.get("memory") or item.get("text") or ""))
                            item["metadata"] = metadata
                            response_items.append(item)
                    return self.reply(200, {"results": response_items})

                return self.reply(404, {"error": "unknown Jarvis memory endpoint"})
            if path == "/v1/operation-logs":
                if not self.authorized(): return
                try:
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length < 0 or length > MAX_BODY * 16:
                        raise ValueError()
                    data = json.loads(self.rfile.read(length))
                    raw_logs = data.get("logs") if isinstance(data, dict) and isinstance(data.get("logs"), list) else ([data] if isinstance(data, dict) else None)
                    if raw_logs is None:
                        raise ValueError()
                    normalized = []
                    for entry in raw_logs:
                        if not isinstance(entry, dict):
                            raise ValueError()
                        log_id = str(entry.get("id", "")).strip()
                        if not log_id or len(log_id) > 64:
                            raise ValueError()
                        created_at = int(entry.get("created_at", 0))
                        if created_at <= 0:
                            created_at = int(time.time() * 1000)
                        updated_at = int(entry.get("updated_at", created_at))
                        if updated_at <= 0:
                            updated_at = created_at
                        clean = {
                            "id": log_id,
                            "created_at": created_at,
                            "updated_at": updated_at,
                            "host_id": str(entry.get("host_id", ""))[:256],
                            "host_name": str(entry.get("host_name", ""))[:256],
                            "ssh_username": str(entry.get("ssh_username", ""))[:256],
                            "directory_id": str(entry.get("directory_id", ""))[:256],
                            "directory_name": str(entry.get("directory_name", ""))[:256],
                            "directory_path": str(entry.get("directory_path", ""))[:4096],
                            "agent_id": str(entry.get("agent_id", ""))[:256],
                            "agent_name": str(entry.get("agent_name", ""))[:256],
                            "agent_command": str(entry.get("agent_command", ""))[:4096],
                            "agent_icon": str(entry.get("agent_icon", ""))[:128],
                            "session_id": str(entry.get("session_id", ""))[:256],
                            "session_name": str(entry.get("session_name", ""))[:512],
                            "title_source": entry.get("title_source") if isinstance(entry.get("title_source"), str) and entry.get("title_source") in {"default", "agent", "manual"} else "default",
                            "client": str(entry.get("client", ""))[:64],
                            "action": str(entry.get("action", "open"))[:64] or "open",
                        }
                        if not clean["host_id"] or not clean["directory_path"] or not clean["agent_command"] or not clean["session_id"]:
                            raise ValueError()
                        normalized.append(clean)
                except (ValueError, TypeError, UnicodeDecodeError, json.JSONDecodeError):
                    return self.reply(400, {"error": "invalid operation log"})
                with sqlite3.connect(database) as db:
                    db.execute("BEGIN IMMEDIATE")
                    for item in normalized:
                        existing = db.execute(
                            "SELECT payload FROM account_operation_logs WHERE user_id = ? AND id = ?",
                            (self.user_id, item["id"]),
                        ).fetchone()
                        if existing:
                            try:
                                old = json.loads(existing[0])
                                if int(old.get("updated_at", old.get("created_at", 0))) > item["updated_at"]:
                                    continue
                            except (TypeError, ValueError, json.JSONDecodeError):
                                pass
                        db.execute(
                            "INSERT INTO account_operation_logs(user_id, id, created_at, payload) VALUES (?, ?, ?, ?) "
                            "ON CONFLICT(user_id, id) DO UPDATE SET created_at=excluded.created_at, payload=excluded.payload",
                            (self.user_id, item["id"], item["created_at"], json.dumps(item, ensure_ascii=False)),
                        )
                    db.execute(
                        "DELETE FROM account_operation_logs WHERE user_id = ? AND id NOT IN (SELECT id FROM account_operation_logs WHERE user_id = ? ORDER BY created_at DESC, rowid DESC LIMIT 500)",
                        (self.user_id, self.user_id),
                    )
                    rows = db.execute(
                        "SELECT payload FROM account_operation_logs WHERE user_id = ? ORDER BY created_at DESC, rowid DESC LIMIT 200",
                        (self.user_id,),
                    ).fetchall()
                return self.reply(200, {"logs": [json.loads(r[0]) for r in rows]})
            return self.reply(404, {"error": "not found"})

        def do_PUT(self):
            if not self.authorized():
                return
            path = urlsplit(self.path).path
            if path == "/v1/preferences":
                try:
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length < 0 or length > 4096: raise ValueError()
                    data = json.loads(self.rfile.read(length))
                    theme = str(data.get("theme", "")).strip()
                    if not re.fullmatch(r"[a-z0-9_-]{0,64}", theme):
                        raise ValueError("invalid theme")
                except (ValueError, AttributeError, UnicodeDecodeError, json.JSONDecodeError):
                    return self.reply(400, {"error": "invalid preferences"})
                with sqlite3.connect(database) as db:
                    db.execute("UPDATE users SET theme = ? WHERE id = ?", (theme, self.user_id))
                return self.reply(200, {"theme": theme})
            if path.startswith("/v1/hosts/") and path.count("/") == 3:
                host_id = path.removeprefix("/v1/hosts/")
                try:
                    length = int(self.headers.get("Content-Length", "-1"))
                    if not host_id or len(host_id) > 64 or length < 0 or length > MAX_BODY: raise ValueError()
                    host = json.loads(self.rfile.read(length))
                    fields = ("id", "name", "lan_address", "external_address", "username", "icon", "selected_agent_command")
                    if not isinstance(host, dict) or host.get("id") != host_id: raise ValueError()
                    if any(not isinstance(host.get(key, ""), str) or len(host.get(key, "")) > 4096 for key in fields): raise ValueError()
                    port = host.get("port")
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
                    requested_host_id = host_id
                    duplicate_host_ids = []
                    existing = db.execute("SELECT deleted, payload FROM account_hosts WHERE user_id=? AND host_id=?", (self.user_id, host_id)).fetchone()
                    if existing and existing[0]:
                        try:
                            canonical_id = json.loads(existing[1]).get("canonical_id")
                        except (AttributeError, TypeError, json.JSONDecodeError):
                            canonical_id = None
                        canonical_exists = db.execute("SELECT 1 FROM account_hosts WHERE user_id=? AND host_id=? AND deleted=0", (self.user_id, canonical_id)).fetchone() if canonical_id else None
                        if canonical_exists:
                            host_id = canonical_id
                            duplicate_host_ids = [requested_host_id]
                            host["id"] = host_id
                            payload = json.dumps(host, ensure_ascii=False)
                    if existing is None:
                        incoming_aliases = set(host["legacy_keys"])
                        incoming_endpoints = host_endpoint_keys(host)
                        candidates = []
                        for candidate_id, candidate_payload in db.execute("SELECT host_id, payload FROM account_hosts WHERE user_id=? AND deleted=0 ORDER BY rowid", (self.user_id,)):
                            candidate = json.loads(candidate_payload)
                            if candidate.get("username", "").casefold() != host.get("username", "").casefold():
                                continue
                            candidates.append((candidate_id, candidate, host_endpoint_keys(candidate)))
                        exact_routes = [item for item in candidates if incoming_endpoints and incoming_endpoints == item[2]]
                        alias_matches = [item for item in candidates if incoming_aliases.intersection(item[1].get("legacy_keys", []))]
                        alias_signatures = {tuple(sorted(item[2])) for item in alias_matches}
                        match = exact_routes[0] if exact_routes else alias_matches[0] if len(alias_matches) == 1 or (alias_matches and len(alias_signatures) == 1) else None
                        if match:
                            host_id = match[0]
                            equivalent = exact_routes if exact_routes else [item for item in alias_matches if item[2] == match[2]]
                            duplicate_host_ids = [item[0] for item in equivalent if item[0] != host_id]
                            host["legacy_keys"] = list(dict.fromkeys(host["legacy_keys"] + [key for item in equivalent for key in item[1].get("legacy_keys", [])]))[:16]
                            host["id"] = host_id
                            payload = json.dumps(host, ensure_ascii=False)
                    db.execute("INSERT INTO account_hosts(user_id, host_id, payload, deleted) VALUES (?, ?, ?, 0) ON CONFLICT(user_id, host_id) DO UPDATE SET payload=excluded.payload, deleted=0", (self.user_id, host_id, payload))
                    legacy_ids = set(host["legacy_keys"])
                    if requested_host_id != host_id: legacy_ids.add(requested_host_id)
                    legacy_ids.update(duplicate_host_ids)
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
                        db.execute("DELETE FROM account_sessions WHERE user_id=? AND host_id=?", (self.user_id, old_id))
                    deduplicate_sessions_by_workspace(db, self.user_id, host_id)
                    for duplicate_id in duplicate_host_ids:
                        row = db.execute("SELECT payload FROM account_hosts WHERE user_id=? AND host_id=?", (self.user_id, duplicate_id)).fetchone()
                        if row:
                            duplicate_profile = json.loads(row[0])
                            duplicate_profile["canonical_id"] = host_id
                            db.execute("UPDATE account_hosts SET payload=?, deleted=1 WHERE user_id=? AND host_id=?", (json.dumps(duplicate_profile, ensure_ascii=False), self.user_id, duplicate_id))
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
                    if password and (not isinstance(password, str) or not 6 <= len(password) <= 1024): raise ValueError("密码至少 6 个字符")
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
                    if (not isinstance(item, dict) or not set(FIELDS).issubset(item)
                            or set(item) - set(FIELDS) - set(OPTIONAL_SESSION_FIELDS)
                            or item["id"] != session_id):
                        raise ValueError()
                    if any(not isinstance(item[field], str) or not item[field] or len(item[field]) > 4096 for field in FIELDS):
                        raise ValueError()
                    title_source = item.get("title_source", "default")
                    if not isinstance(title_source, str) or title_source not in {"default", "agent", "manual"}:
                        raise ValueError()
                    item["title_source"] = title_source
                except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
                    return self.reply(400, {"error": "invalid session"})
                with sqlite3.connect(database) as db:
                    rows = db.execute("SELECT id, payload FROM account_sessions WHERE user_id=? AND host_id=?", (self.user_id, host_id)).fetchall()
                    target_dir = item.get("directory_path")
                    target_cmd = item.get("agent_command")
                    for row_id, payload_str in rows:
                        if row_id == session_id:
                            continue
                        try:
                            parsed = json.loads(payload_str)
                            if parsed.get("directory_path") == target_dir and parsed.get("agent_command") == target_cmd:
                                db.execute("DELETE FROM account_sessions WHERE user_id=? AND host_id=? AND id=?", (self.user_id, host_id, row_id))
                        except Exception:
                            pass
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
                if (not isinstance(item, dict) or not set(FIELDS).issubset(item)
                        or set(item) - set(FIELDS) - set(OPTIONAL_SESSION_FIELDS)
                        or item["id"] != session_id):
                    raise ValueError()
                if any(not isinstance(item[field], str) or not item[field] or len(item[field]) > 4096 for field in FIELDS):
                    raise ValueError()
                title_source = item.get("title_source", "default")
                if not isinstance(title_source, str) or title_source not in {"default", "agent", "manual"}:
                    raise ValueError()
                item["title_source"] = title_source
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
                    for table in ("auth_tokens", "account_sessions", "account_workspaces", "workspace_history", "account_agents", "agent_history", "account_host_state", "account_hosts", "account_operation_logs", "jarvis_short_memories", "jarvis_memory_turns", "jarvis_memory_recalls", "jarvis_memory_namespaces"):
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
                    alias_host_ids = set()
                    canonical_profile = db.execute("SELECT payload FROM account_hosts WHERE user_id=? AND host_id=?", (self.user_id, host_id)).fetchone()
                    if canonical_profile:
                        try:
                            alias_host_ids.update(json.loads(canonical_profile[0]).get("legacy_keys", []))
                        except (TypeError, json.JSONDecodeError):
                            pass
                    for alias_id, payload in db.execute("SELECT host_id, payload FROM account_hosts WHERE user_id=? AND deleted=1 AND host_id!=?", (self.user_id, host_id)):
                        try:
                            alias = json.loads(payload)
                        except (TypeError, json.JSONDecodeError):
                            continue
                        if alias.get("canonical_id") == host_id:
                            alias_host_ids.add(alias_id)
                    for target_host_id in [host_id, *alias_host_ids]:
                        db.execute("DELETE FROM account_sessions WHERE user_id = ? AND host_id = ? AND username = ? AND id = ?", (self.user_id, target_host_id, username, session_id))
                return self.reply(200, {"deleted": session_id})
            session_id = path.removeprefix("/v1/sessions/")
            if not path.startswith("/v1/sessions/") or len(session_id) != 32 or any(c not in "0123456789abcdef" for c in session_id):
                return self.reply(404, {"error": "invalid session id"})
            with sqlite3.connect(database) as db:
                db.execute("DELETE FROM account_sessions WHERE user_id = ? AND host_id = 'legacy' AND username = 'legacy' AND id = ?", (self.user_id, session_id))
            self.reply(200, {"deleted": session_id})

    server = ThreadingHTTPServer(address, Handler)
    server.memory_maintenance_stop = threading.Event()

    def memory_maintenance():
        try:
            interval = max(60, min(24 * 60 * 60, int(os.environ.get("RDT_MEMORY_MAINTENANCE_SECONDS", "3600"))))
        except ValueError:
            interval = 3600
        while not server.memory_maintenance_stop.is_set():
            try:
                with sqlite3.connect(database) as db:
                    user_ids = [row[0] for row in db.execute("SELECT DISTINCT user_id FROM jarvis_memory_turns")]
                for user_id in user_ids:
                    schedule_memory_consolidation(user_id)
            except Exception:
                pass
            server.memory_maintenance_stop.wait(interval)

    threading.Thread(target=memory_maintenance, name="jarvis-memory-maintenance", daemon=True).start()
    return server


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
        server.memory_maintenance_stop.set()
        server.server_close()


if __name__ == "__main__":
    main()
