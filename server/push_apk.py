#!/usr/bin/env python3
"""把 APK 以 base64 分块经 files.write 推到手机，再用 shell 解码并 pm install。
用法: push_apk.py <apk路径> [远程文件名]"""
import base64
import sys

sys.path.insert(0, ".")
from phone import req

apk = sys.argv[1]
name = sys.argv[2] if len(sys.argv) > 2 else "MuseShell.apk"
remote_dir = "/sdcard/MuseShell"
b64_path = remote_dir + "/" + name + ".b64"
apk_path = remote_dir + "/" + name

data = base64.b64encode(open(apk, "rb").read()).decode()
CH = 24000
chunks = [data[i:i + CH] for i in range(0, len(data), CH)]
print(f"b64 {len(data)} chars, {len(chunks)} chunks")
for i, c in enumerate(chunks):
    args = {"path": b64_path, "text": c}
    if i > 0:
        args["append"] = True
    res = req("POST", "/call", {"tool": "files.write", "args": args, "timeout": 60}, timeout=75)
    if not res.get("ok"):
        print("write chunk", i, "FAILED", res)
        sys.exit(1)
    print("chunk", i + 1, "/", len(chunks), "ok")

tmp_apk = "/data/local/tmp/" + name
cmd = (f"base64 -d {b64_path} > {apk_path} && cp {apk_path} {tmp_apk} "
       f"&& chmod 644 {tmp_apk} && pm install -r {tmp_apk}")
res = req("POST", "/call", {"tool": "shell", "args": {"cmd": cmd, "timeout": 120}, "timeout": 150},
          timeout=170)
print("install:", res)
