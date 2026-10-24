#!/usr/bin/env python3
"""沙盒侧调用手机工具的命令行客户端。
用法：
  phone.py status
  phone.py call <tool> ['<args-json>'] [--timeout N]
例：phone.py call files.list '{"path":"/sdcard/MuseShell"}'
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
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
