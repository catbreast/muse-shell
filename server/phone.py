#!/usr/bin/env python3
"""沙盒侧调用手机工具的命令行客户端。
用法：
  phone.py status
  phone.py call <tool> ['<args-json>'] [--timeout N]
  phone.py batch '<calls-json>' [--timeout N]
例：phone.py call files.list '{"path":"/sdcard/MuseShell"}'
    phone.py batch '[{"tool":"ping","args":{}},{"tool":"ping","args":{}}]'
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(HERE, "tokens.json")) as f:
    ADMIN = json.load(f)["admin"]
# 2026-10-22 迁移：中继已搬到 Cloudflare 边缘（Worker + Durable Object），
# 经 https://phone.ruisnow.de 走。BASE 可用环境变量 PHONE_BASE 覆盖。
BASE = os.environ.get("PHONE_BASE", "https://phone.ruisnow.de")
UA = "muse-phone.py/2.0"


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
            with urllib.request.urlopen(r, data=data, timeout=timeout) as resp:
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
    code, res = req("POST", "/batch", {"calls": items, "wait": 50}, timeout=65)
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
    data = json.dumps({"id": cid, "tool": tool, "args": args}).encode()
    r.add_header("Content-Type", "application/json")
    try:
        resp = urllib.request.urlopen(r, data=data, timeout=70)
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
                                {"id": cid, "tool": tool, "args": args, "wait": 50},
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
