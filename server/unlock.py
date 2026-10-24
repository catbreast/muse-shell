#!/usr/bin/env python3
"""远程解锁 v2：调用壳内 lock.unlock 工具。

PIN 只存在手机 App 的私有设置里（用户在控制台录入），本脚本和通道里
不出现任何数字。App 侧：未锁定时直接返回 already、不发任何输入；
锁定时内部注入数字键事件完成解锁，只试一次。
用法: python3 unlock.py
"""
import json
import sys
import time
import uuid

sys.path.insert(0, "/home/hatch/workspace/muse-android-shell/server")
from phone import req


def call(tool, args, timeout=90):
    deadline = time.time() + timeout
    cid = uuid.uuid4().hex[:12]
    code, res = req("POST", "/call", {"id": cid, "tool": tool, "args": args, "wait": 50}, timeout=65)
    while code == 202 and res.get("pending") and time.time() < deadline:
        code, res = req("POST", "/wait", {"id": res["id"], "wait": 50}, timeout=65)
    if code == 202:
        return {"ok": False, "error": "phone_timeout"}
    return res


if __name__ == "__main__":
    r = call("lock.unlock", {}, timeout=120)
    print(json.dumps(r, ensure_ascii=False))
    sys.exit(0 if r.get("ok") else 1)
