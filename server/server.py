#!/usr/bin/env python3
"""Muse 壳工具端点：手机长轮询取命令，沙盒侧 /call 同步调用。
仅绑 127.0.0.1:8390，对外经 Cloudflare 隧道 phone.ruisnow.de。
令牌两把：phone（/poll、/result）与 admin（/call、/status），存 tokens.json(600)。
"""
import json
import os
import queue
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(HERE, "tokens.json")) as f:
    TOKENS = json.load(f)

cmd_q = queue.Queue()
pending = {}          # id -> {"event": Event, "result": dict}
pending_lock = threading.Lock()
state = {"device": None, "last_seen": 0.0, "last_tool": None}


def wait_result(cid, timeout):
    with pending_lock:
        slot = pending.get(cid)
    if slot is None:
        return None
    slot["event"].wait(timeout)
    with pending_lock:
        pending.pop(cid, None)
    return slot.get("result")


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, obj=None):
        body = b"" if obj is None else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b"{}"
        try:
            return json.loads(raw.decode() or "{}")
        except Exception:
            return {}

    def _auth(self, kind):
        return self.headers.get("X-Token") == TOKENS.get(kind)

    def do_GET(self):
        if self.path.startswith("/poll"):
            if not self._auth("phone"):
                return self._send(401, {"error": "bad_token"})
            state["device"] = self.headers.get("X-Device")
            state["last_seen"] = time.time()
            try:
                cmd = cmd_q.get(timeout=25)
                return self._send(200, cmd)
            except queue.Empty:
                return self._send(204)
        if self.path.startswith("/status"):
            if not self._auth("admin"):
                return self._send(401, {"error": "bad_token"})
            return self._send(200, {
                "device": state["device"],
                "last_seen_ago_sec": (time.time() - state["last_seen"]) if state["last_seen"] else None,
                "queued": cmd_q.qsize(),
                "last_tool": state["last_tool"],
            })
        return self._send(404, {"error": "not_found"})

    def do_POST(self):
        if self.path.startswith("/result"):
            if not self._auth("phone"):
                return self._send(401, {"error": "bad_token"})
            state["last_seen"] = time.time()
            data = self._body()
            cid = data.get("id")
            with pending_lock:
                slot = pending.get(cid)
                if slot is not None:
                    slot["result"] = data
                    slot["event"].set()
            return self._send(200, {"ok": True})
        if self.path.startswith("/call"):
            if not self._auth("admin"):
                return self._send(401, {"error": "bad_token"})
            data = self._body()
            tool = data.get("tool")
            if not tool:
                return self._send(400, {"error": "tool_required"})
            timeout = min(float(data.get("timeout", 60)), 900)
            cid = uuid.uuid4().hex[:12]
            with pending_lock:
                pending[cid] = {"event": threading.Event(), "result": None}
            state["last_tool"] = tool
            cmd_q.put({"id": cid, "tool": tool, "args": data.get("args") or {}})
            res = wait_result(cid, timeout)
            if res is None:
                return self._send(504, {"ok": False, "error": "phone_timeout_or_offline"})
            return self._send(200, res)
        return self._send(404, {"error": "not_found"})


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", 8390), H)
    print("muse-shell bridge on 127.0.0.1:8390", flush=True)
    srv.serve_forever()
