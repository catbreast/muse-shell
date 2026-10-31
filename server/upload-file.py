#!/usr/bin/env python3
"""通用校验分块上传：把沙盒文件送到手机容器指定路径（逐块 sha 校验）。"""
import base64, hashlib, json, subprocess, sys

def call(args, timeout=60):
    r = subprocess.run(["python3", "/home/hatch/workspace/muse-android-shell/server/phone.py",
                        "call", "linux.exec", json.dumps(args), "--timeout", str(timeout)],
                       capture_output=True, text=True)
    out = json.loads(r.stdout)
    if not out.get("ok"):
        raise RuntimeError(r.stdout[-300:])
    return out["result"]

def upload(local, remote):
    data = open(local, "rb").read()
    sha = hashlib.sha256(data).hexdigest()
    b64 = base64.b64encode(data).decode()
    tag = hashlib.md5(remote.encode()).hexdigest()[:8]
    parts = [b64[i:i + 3000] for i in range(0, len(b64), 3000)]
    call({"cmd": f"rm -f /tmp/up{tag}.part.*"})
    for idx, part in enumerate(parts):
        want = hashlib.sha256(part.encode()).hexdigest()
        for _ in range(3):
            call({"cmd": f"printf %s '{part}' > /tmp/up{tag}.part.{idx:03d}"})
            res = call({"cmd": f"sha256sum /tmp/up{tag}.part.{idx:03d} | cut -d' ' -f1"})
            if res["stdout"].strip() == want:
                break
        else:
            raise RuntimeError(f"chunk {idx} failed for {remote}")
    res = call({"cmd": f"cat /tmp/up{tag}.part.* | base64 -d > /tmp/up{tag}.bin && cp /tmp/up{tag}.bin '{remote}' && sha256sum '{remote}'"})
    ok = sha in res["stdout"]
    print(("OK  " if ok else "BAD "), local, "->", remote, f"({len(data)}B, {len(parts)} chunks)")
    if not ok:
        raise RuntimeError("final sha mismatch: " + remote)

if __name__ == "__main__":
    upload(sys.argv[1], sys.argv[2])
