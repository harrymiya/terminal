#!/usr/bin/env python3
"""Small authenticated, persistent registry of remote terminal sessions."""
import argparse
import hmac
import json
import os
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

MAX_BODY = 65536
FIELDS = ("id", "directory_path", "directory_name", "agent_name", "agent_command", "display_name")
WORKSPACE_FIELDS = ("id", "name", "path", "icon")
AGENT_FIELDS = ("id", "name", "command", "icon")


def create_server(address, database, token):
    if not token:
        raise ValueError("RDT_SYNC_TOKEN must be set")
    database = str(database)
    with sqlite3.connect(database) as db:
        db.execute("CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS workspaces (host_id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS agents (host_id TEXT PRIMARY KEY, payload TEXT NOT NULL)")

    class Handler(BaseHTTPRequestHandler):
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
            supplied = self.headers.get("Authorization", "")
            if not hmac.compare_digest(supplied.encode(), ("Bearer " + token).encode()):
                self.reply(401, {"error": "unauthorized"})
                return False
            return True

        def do_GET(self):
            if not self.authorized():
                return
            path = urlsplit(self.path).path
            if path.startswith("/v1/agents/"):
                host_id = path.removeprefix("/v1/agents/")
                if not host_id or len(host_id) > 256:
                    return self.reply(404, {"error": "invalid host id"})
                with sqlite3.connect(database) as db:
                    row = db.execute("SELECT payload FROM agents WHERE host_id = ?", (host_id,)).fetchone()
                return self.reply(200, {"agents": json.loads(row[0]) if row else [], "configured": row is not None})
            if path.startswith("/v1/workspaces/"):
                host_id = path.removeprefix("/v1/workspaces/")
                if not host_id or len(host_id) > 256:
                    return self.reply(404, {"error": "invalid host id"})
                with sqlite3.connect(database) as db:
                    row = db.execute("SELECT payload FROM workspaces WHERE host_id = ?", (host_id,)).fetchone()
                return self.reply(200, {"workspaces": json.loads(row[0]) if row else [], "configured": row is not None})
            if path != "/v1/sessions":
                return self.reply(404, {"error": "not found"})
            with sqlite3.connect(database) as db:
                sessions = [json.loads(row[0]) for row in db.execute("SELECT payload FROM sessions ORDER BY rowid")]
            self.reply(200, {"sessions": sessions})

        def do_PUT(self):
            if not self.authorized():
                return
            path = urlsplit(self.path).path
            if path.startswith("/v1/agents/"):
                host_id = path.removeprefix("/v1/agents/")
                if not host_id or len(host_id) > 256:
                    return self.reply(404, {"error": "invalid host id"})
                try:
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length < 0 or length > MAX_BODY:
                        raise ValueError()
                    items = json.loads(self.rfile.read(length)).get("agents")
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
                with sqlite3.connect(database) as db:
                    db.execute("INSERT INTO agents VALUES (?, ?) ON CONFLICT(host_id) DO UPDATE SET payload=excluded.payload", (host_id, json.dumps(items, ensure_ascii=False)))
                return self.reply(200, {"agents": items, "configured": True})
            if path.startswith("/v1/workspaces/"):
                host_id = path.removeprefix("/v1/workspaces/")
                if not host_id or len(host_id) > 256:
                    return self.reply(404, {"error": "invalid host id"})
                try:
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length < 0 or length > MAX_BODY:
                        raise ValueError()
                    items = json.loads(self.rfile.read(length)).get("workspaces")
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
                with sqlite3.connect(database) as db:
                    db.execute("INSERT INTO workspaces VALUES (?, ?) ON CONFLICT(host_id) DO UPDATE SET payload=excluded.payload", (host_id, json.dumps(items, ensure_ascii=False)))
                return self.reply(200, {"workspaces": items, "configured": True})
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
                db.execute("INSERT INTO sessions VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload", (session_id, json.dumps(item, ensure_ascii=False)))
            self.reply(200, item)

        def do_DELETE(self):
            if not self.authorized():
                return
            path = urlsplit(self.path).path
            session_id = path.removeprefix("/v1/sessions/")
            if not path.startswith("/v1/sessions/") or len(session_id) != 32 or any(c not in "0123456789abcdef" for c in session_id):
                return self.reply(404, {"error": "invalid session id"})
            with sqlite3.connect(database) as db:
                db.execute("DELETE FROM sessions WHERE id = ?", (session_id,))
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
    server = create_server((args.host, args.port), database, os.environ.get("RDT_SYNC_TOKEN", ""))
    os.chmod(database, 0o600)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
