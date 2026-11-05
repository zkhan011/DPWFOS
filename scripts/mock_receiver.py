"""Loopback-only receiver with idempotent effects. Synthetic development data only."""
import json, threading
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
seen = {}
lock = threading.Lock()
class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size <= 0 or size > 262144:
                self.send_error(413); return
            body = json.loads(self.rfile.read(size))
            msg_id = body["msgId"]
            identity = (self.path, msg_id)
            with lock:
                previous = seen.get(identity)
                conflict = previous is not None and previous != body
                if not conflict: seen[identity] = body
            status = 409 if conflict else 200
            data = json.dumps({"msgId":msg_id,"code":"CONFLICT" if conflict else "0","message":"conflict" if conflict else "success","data":{}}).encode()
            self.send_response(status); self.send_header("Content-Type","application/json")
            self.send_header("Content-Length",str(len(data))); self.end_headers(); self.wfile.write(data)
        except (ValueError, KeyError, TypeError): self.send_error(400)
    def log_message(self, *_): pass
if __name__ == "__main__":
    print("Synthetic receiver: http://127.0.0.1:8090", flush=True)
    ThreadingHTTPServer(("127.0.0.1",8090),Handler).serve_forever()
