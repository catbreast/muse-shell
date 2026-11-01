#!/usr/bin/env python3
"""沙盒侧调用手机工具的命令行客户端。
用法：
  phone.py status
  phone.py call <tool> ['<args-json>'] [--timeout N]
  phone.py batch '<calls-json>' [--timeout N]
例：phone.py call files.list '{"path":"/sdcard/MuseShell"}'
    phone.py batch '[{"tool":"ping","args":{}},{"tool":"ping","args":{}}]'
"""
import http.client
import json
import os
import socket
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(HERE, "tokens.json")) as f:
    ADMIN = json.load(f)["admin"]
# 2026-11-02 设备定向：默认钉一加 13（ec751bad）；GT7 用 PHONE_DEVICE=2182b319。
# 中继按 device 只投目标设备的连接；设 PHONE_DEVICE=none 则不带目标（旧行为）。
DEVICE = os.environ.get("PHONE_DEVICE", "ec751bad")
if DEVICE.lower() in ("", "none"):
    DEVICE = None
# 2026-10-22 迁移：中继已搬到 Cloudflare 边缘（Worker + Durable Object），
# 经 https://phone.ruisnow.de 走。BASE 可用环境变量 PHONE_BASE 覆盖。
BASE = os.environ.get("PHONE_BASE", "https://phone.ruisnow.de")
UA = "muse-phone.py/2.0"

# 2026-11-02 BWG 优选（用户拍板）：沙盒直连 CF 落点迈阿密（/status 约 1.2s），
# 改走常驻 SSH 隧道 沙盒→BWG（127.0.0.1:11080 SOCKS5）→CF 落 LAX（约 0.3s）。
# 每次进程启动探活一次：隧道不在位、或中途传输失败 → 自动回落直连（旧行为）。
# PHONE_NO_BWG=1 可禁用。隧道保活：~/workspace/relay-bwg-tunnel/tunnel.sh。
_BWG_SOCKS = ("127.0.0.1", 11080)
_bwg_mode = {"via": None}  # None=未探活 / "bwg"=走隧道 / "direct"=直连


def _recvn(s, n):
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise OSError("socks5: unexpected eof")
        buf += chunk
    return buf


def _socks5_connect(host, port, timeout):
    s = socket.create_connection(_BWG_SOCKS, timeout=timeout)
    try:
        s.sendall(b"\x05\x01\x00")
        if _recvn(s, 2) != b"\x05\x00":
            raise OSError("socks5 greeting rejected")
        hb = host.encode()
        s.sendall(b"\x05\x01\x00\x03" + bytes([len(hb)]) + hb + port.to_bytes(2, "big"))
        hdr = _recvn(s, 4)
        if hdr[1] != 0:
            raise OSError(f"socks5 connect rep={hdr[1]}")
        atyp = hdr[3]
        if atyp == 1:
            _recvn(s, 4)
        elif atyp == 3:
            _recvn(s, _recvn(s, 1)[0])
        elif atyp == 4:
            _recvn(s, 16)
        _recvn(s, 2)
        return s
    except Exception:
        s.close()
        raise


class _BwgHTTPS(http.client.HTTPSConnection):
    def connect(self):
        raw = _socks5_connect(self.host, self.port, self.timeout or 60)
        ctx = self._context or ssl.create_default_context()
        self.sock = ctx.wrap_socket(raw, server_hostname=self.host)


class _BwgHandler(urllib.request.HTTPSHandler):
    def https_open(self, request):
        return self.do_open(_BwgHTTPS, request)


def _build_opener():
    if os.environ.get("PHONE_NO_BWG"):
        _bwg_mode["via"] = "direct"
        return urllib.request.build_opener()
    # 全程探活：不只探 SOCKS 端口，要真经隧道连一次目标（隧道在位但 BWG 侧
    # 解析/连出失败时，端口探活是假绿——2026-11-02 实测踩过）。
    try:
        u = urllib.parse.urlsplit(BASE)
        s = _socks5_connect(u.hostname, u.port or 443, 3)
        s.close()
        _bwg_mode["via"] = "bwg"
        # 必须禁掉 ProxyHandler：沙盒环境带 https_proxy（平台出口代理），默认
        # opener 会先把请求改写给它，SOCKS 目标就会变成代理主机名（BWG 侧
        # 解析失败）或在 TLS 里塞 CONNECT（流式请求挂死）——2026-11-02 实测。
        return urllib.request.build_opener(urllib.request.ProxyHandler({}), _BwgHandler())
    except Exception:
        _bwg_mode["via"] = "direct"
        return urllib.request.build_opener()


_OPENER = None


def _open(r, data=None, timeout=60):
    """隧道优先、失败回落直连的 urlopen 替代（req/call_stream 共用）。"""
    global _OPENER
    if _OPENER is None:
        _OPENER = _build_opener()
    try:
        return _OPENER.open(r, data=data, timeout=timeout)
    except Exception:
        if _bwg_mode["via"] == "bwg":
            # 隧道中途失效：切直连再抛回，调用方既有重试会走直连。
            _OPENER = urllib.request.build_opener()
            _bwg_mode["via"] = "direct"
        raise


def req(method, path, body=None, timeout=60, retries=2):
    """带断连重试的请求。/call 幂等（客户端 id 去重），/wait 只读，重试都安全。"""
    last = None
    for attempt in range(retries + 1):
        r = urllib.request.Request(BASE + path, method=method)
        r.add_header("X-Token", ADMIN)
        r.add_header("User-Agent", UA)
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            r.add_header("Content-Type", "application/json")
        try:
            with _open(r, data=data, timeout=timeout) as resp:
                raw = resp.read().decode()
                return resp.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as e:
            raw = e.read().decode()
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, {}
        except Exception as e:  # RemoteDisconnected / URLError 等传输层错误
            last = e
            if attempt < retries:
                time.sleep(2)
    raise last


def batch_call(calls, timeout=120):
    """一次下发多条指令（一个往返入队），等齐后按入参顺序返回结果列表。
    每条自动分配客户端 id：传输重试时服务端按 id 幂等去重，不重复执行。"""
    items = [{"id": uuid.uuid4().hex[:12], "tool": c["tool"], "args": c.get("args", {})}
             for c in calls]
    ids = [c["id"] for c in items]
    got = {}
    deadline = time.time() + timeout
    code, res = req("POST", "/batch", {"calls": items, "wait": 50, "device": DEVICE}, timeout=65)
    while True:
        for k, v in (res.get("results") or {}).items():
            got[k] = v
        pending = [i for i in ids if i not in got]
        if not pending or code not in (200, 202) or time.time() >= deadline:
            break
        code, res = req("POST", "/batchwait", {"ids": pending, "wait": 50}, timeout=65)
    return [got.get(i, {"ok": False, "error": "phone_timeout_or_offline"}) for i in ids]


class StreamFail(Exception):
    pass


def call_stream(cid, tool, args, deadline):
    """快车道：POST /callstream，结果以 NDJSON 流回（chunk 帧拼起来即
    结果 JSON 原文）。中继不支持/执行者不会分块时，中继直接按 /call
    语义返回普通 JSON——按 Content-Type 分辨。流中断抛 StreamFail，
    调用方用同一 id 回退慢车道 /wait 取落盘副本（中继尾帧落账保证）。"""
    r = urllib.request.Request(BASE + "/callstream", method="POST")
    r.add_header("X-Token", ADMIN)
    r.add_header("User-Agent", UA)
    data = json.dumps({"id": cid, "tool": tool, "args": args, "device": DEVICE}).encode()
    r.add_header("Content-Type", "application/json")
    try:
        resp = _open(r, data=data, timeout=70)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            raise StreamFail(f"http_{e.code}")
    with resp:
        ctype = resp.headers.get("Content-Type", "")
        if "ndjson" not in ctype:
            raw = resp.read().decode()
            try:
                return resp.status, (json.loads(raw) if raw else {})
            except Exception:
                raise StreamFail("bad_json_response")
        parts = []
        while True:
            if time.time() > deadline:
                raise StreamFail("deadline")
            line = resp.readline()
            if not line:
                raise StreamFail("eof_without_end")
            line = line.strip()
            if not line:
                continue
            try:
                frame = json.loads(line)
            except Exception:
                raise StreamFail("bad_frame")
            t = frame.get("t")
            if t == "chunk":
                parts.append(frame.get("d", ""))
            elif t == "reset":
                parts = []
            elif t == "end":
                try:
                    return 200, json.loads("".join(parts))
                except Exception:
                    raise StreamFail("assembly_parse_fail")
            elif t == "error":
                raise StreamFail(frame.get("error", "stream_error"))
            # begin / ping 帧：忽略


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    if sys.argv[1] == "status":
        _, res = req("GET", "/status")
        print(json.dumps(res, ensure_ascii=False, indent=1))
        return 0
    if sys.argv[1] == "call":
        tool = sys.argv[2]
        args = json.loads(sys.argv[3]) if len(sys.argv) > 3 and not sys.argv[3].startswith("--") else {}
        timeout = 60
        if "--timeout" in sys.argv:
            timeout = float(sys.argv[sys.argv.index("--timeout") + 1])
        deadline = time.time() + timeout
        cid = uuid.uuid4().hex[:12]
        try:
            code, res = None, None
            if not os.environ.get("PHONE_NOSTREAM"):
                try:
                    code, res = call_stream(cid, tool, args, deadline)
                except Exception as e:
                    if os.environ.get("PHONE_DEBUG"):
                        print(f"[stream-fail: {e}]", file=sys.stderr)
                    # 流可能是在命令已带 stream 入队后才断的：先纯取回
                    # /wait（不重新入队），取不到才走 /call 慢车道。
                    try:
                        wcode, wres = req("POST", "/wait", {"id": cid, "wait": 5}, timeout=15)
                        if wcode == 200:
                            code, res = wcode, wres
                    except Exception:
                        pass
            if code is None:
                code, res = req("POST", "/call",
                                {"id": cid, "tool": tool, "args": args, "wait": 50, "device": DEVICE},
                                timeout=65)
            while code == 202 and res.get("pending") and time.time() < deadline:
                code, res = req("POST", "/wait", {"id": res["id"], "wait": 50}, timeout=65)
            if code == 202:
                res = {"ok": False, "error": "phone_timeout_or_offline"}
        except Exception as e:
            res = {"ok": False, "error": f"transport: {e}"}
        print(json.dumps(res, ensure_ascii=False, indent=1))
        return 0 if res.get("ok") else 1
    if sys.argv[1] == "batch":
        calls = json.loads(sys.argv[2])
        timeout = 120
        if "--timeout" in sys.argv:
            timeout = float(sys.argv[sys.argv.index("--timeout") + 1])
        try:
            results = batch_call(calls, timeout=timeout)
        except Exception as e:
            results = [{"ok": False, "error": f"transport: {e}"}]
        print(json.dumps(results, ensure_ascii=False, indent=1))
        return 0 if all(r.get("ok") for r in results) else 1
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
