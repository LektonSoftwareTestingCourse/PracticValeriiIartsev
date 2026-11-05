"""Test-only HTTP fault injection. Unmatched requests reach the real service."""

import http.client
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

TARGETS = {"cms": "card-management", "auth": "authorization"}
RULES = []
LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def read_body(self):
        if self.headers.get("Transfer-Encoding", "").lower() != "chunked":
            return self.rfile.read(int(self.headers.get("Content-Length", "0")))
        chunks = []
        while True:
            size = int(self.rfile.readline().split(b";", 1)[0].strip(), 16)
            if size == 0:
                while self.rfile.readline().strip():
                    pass
                return b"".join(chunks)
            chunks.append(self.rfile.read(size))
            self.rfile.read(2)

    def handle_request(self):
        body = self.read_body()
        if self.path == "/__rules":
            with LOCK:
                if self.command == "POST":
                    RULES.append(json.loads(body))
                elif self.command == "DELETE":
                    RULES.clear()
            return self.reply(200, b'{"status":"ok"}', "application/json")
        if self.path == "/health":
            return self.reply(200, b'{"status":"ok"}', "application/json")
        parts = self.path.split("/", 2)
        if len(parts) < 3 or parts[1] not in TARGETS:
            return self.reply(404, b"{}", "application/json")
        service, path = parts[1], "/" + parts[2]
        with LOCK:
            rule = next((r.copy() for r in RULES
                         if r["service"] == service and r["method"] == self.command
                         and r["path"] == urlsplit(path).path), None)
        if rule:
            return self.reply(rule["status"], json.dumps(rule.get("body", {})).encode(), "application/json")
        try:
            conn = http.client.HTTPConnection(TARGETS[service], 8080, timeout=10)
            headers = {k: v for k, v in self.headers.items()
                       if k.lower() not in ("host", "connection", "transfer-encoding", "content-length")}
            conn.request(self.command, path, body=body, headers=headers)
            response = conn.getresponse()
            self.reply(response.status, response.read(), response.getheader("Content-Type", "application/json"))
            conn.close()
        except (OSError, http.client.HTTPException):
            self.reply(503, b'{"error":"TEST_UPSTREAM_UNAVAILABLE"}', "application/json")

    def reply(self, status, body, content_type):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = do_PATCH = do_DELETE = handle_request

    def log_message(self, *_):
        pass


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
