#!/usr/bin/env python3
"""Push enter-ubuntu.sh to the phone via phone.py shell.

Robust version: stage to a temp file, verify byte count and sha256 at every
step, and only move into place once the staged copy matches the local digest.
The live script is never truncated by a failed transfer.
"""
import base64, hashlib, json, subprocess, sys

SRC = "/home/hatch/workspace/muse-android-shell/server/enter-ubuntu.sh"
DST = "/data/local/muse-linux/enter-ubuntu.sh"
TMP = DST + ".tmp"
B64 = DST + ".b64"
PHONE = "/home/hatch/workspace/muse-android-shell/server/phone.py"

data = open(SRC, "rb").read()
digest = hashlib.sha256(data).hexdigest()
b64 = base64.b64encode(data).decode()

def call(cmd):
    payload = json.dumps({"cmd": cmd})
    out = subprocess.run(
        ["python3", PHONE, "call", "shell", payload],
        capture_output=True, text=True, timeout=120,
    )
    r = json.loads(out.stdout)
    if not r.get("ok"):
        raise SystemExit(f"transport failed: {out.stdout} {out.stderr}")
    res = r["result"]
    if res.get("exit") not in (0, None):
        raise SystemExit(f"remote cmd failed (exit={res.get('exit')}): {cmd}\n{res}")
    return res.get("stdout", "")

call(f"rm -f {B64} {TMP} {B64}.part*")
CHUNK = 3000
parts = []
for idx, i in enumerate(range(0, len(b64), CHUNK)):
    part = f"{B64}.part{idx}"
    parts.append(part)
    # Overwrite per part file: a duplicated delivery rewrites the same bytes
    # instead of appending a second copy (the relay can re-deliver a call).
    call(f"printf %s '{b64[i:i + CHUNK]}' > {part}")
call(f"cat {' '.join(parts)} > {B64} && rm -f {B64}.part*")
n = int(call(f"wc -c < {B64}").strip())
if n != len(b64):
    raise SystemExit(f"b64 size mismatch: remote {n} != local {len(b64)}; live file untouched")
call(f"base64 -d {B64} > {TMP}")
remote = call(f"sha256sum {TMP}").strip().split()[0]
if remote != digest:
    raise SystemExit(f"sha mismatch: remote {remote} != local {digest}; live file untouched")
call(f"chmod 755 {TMP} && mv {TMP} {DST} && rm -f {B64}")
print("pushed OK, sha256:", digest)
