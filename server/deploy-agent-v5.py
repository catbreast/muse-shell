#!/usr/bin/env python3
"""分块（带逐块校验）把新版 agent.py 送进容器并替换重启。"""
import base64, hashlib, json, subprocess, sys

SRC = "/home/hatch/workspace/muse-android-shell/server/agent/agent.py"
DST_DIR = "/home/hatch/workspace/muse-android-shell/server/agent"
data = open(SRC, "rb").read()
sha = hashlib.sha256(data).hexdigest()
b64 = base64.b64encode(data).decode()
print("local sha256:", sha, "b64 len:", len(b64))

def call(tool, args, timeout=60):
    r = subprocess.run(["python3", "/home/hatch/workspace/muse-android-shell/server/phone.py",
                        "call", tool, json.dumps(args), "--timeout", str(timeout)],
                       capture_output=True, text=True)
    out = json.loads(r.stdout)
    if not out.get("ok"):
        raise RuntimeError(f"{tool} failed: {r.stdout[-300:]}")
    return out["result"]

CH = 3000
parts = [b64[i:i + CH] for i in range(0, len(b64), CH)]
call("linux.exec", {"cmd": "rm -f /tmp/agent.part.* /tmp/agent.b64"})
for idx, part in enumerate(parts):
    want = hashlib.sha256(part.encode()).hexdigest()
    for attempt in range(3):
        call("linux.exec", {"cmd": f"printf %s '{part}' > /tmp/agent.part.{idx:02d}"})
        res = call("linux.exec", {"cmd": f"sha256sum /tmp/agent.part.{idx:02d} | cut -d' ' -f1"})
        if res["stdout"].strip() == want:
            break
    else:
        print(f"chunk {idx} failed after retries — abort")
        sys.exit(1)
    print(f"chunk {idx + 1}/{len(parts)} ok")
res = call("linux.exec", {"cmd": "cat /tmp/agent.part.* | base64 -d > /tmp/agent.py.new && sha256sum /tmp/agent.py.new"})
print("remote:", res["stdout"].strip())
if sha not in res["stdout"]:
    print("SHA MISMATCH after assembly — aborting")
    sys.exit(1)
res = call("linux.exec", {"cmd":
    f"cp {DST_DIR}/agent.py {DST_DIR}/agent.py.bak-v4 && "
    f"cp /tmp/agent.py.new {DST_DIR}/agent.py && "
    "AGENT_PID=$(ps aux | grep '[a]gent.py' | awk '{print $2}' | head -1); "
    "echo killing $AGENT_PID; kill $AGENT_PID; sleep 6; "
    f"tail -3 {DST_DIR}/agent.log; ps aux | grep '[a]gent.py' | head -2"})
print(res["stdout"])
print("DEPLOY DONE")
