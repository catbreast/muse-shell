#!/usr/bin/env python3
"""收尾：把 agent 运行名从 ragent5.py 改回 agent.py 并干净重启。
毒命令早已跑完落账，改名不再有自杀风险；仍走脱离式脚本执行。"""
import base64, json, subprocess, sys, time

SCRIPT = r"""#!/bin/bash
cd /home/hatch/workspace/muse-android-shell/server/agent || exit 1
cp agent.py agent.py.bak-v5
sed -i 's/ragent5\.py/agent.py/g' agent-run.sh
for p in $(ps aux | grep -E 'agent-run\.sh|python3 ragent5\.py' | grep -v grep | awk '{print $2}'); do kill $p 2>/dev/null; done
sleep 2
rm -f ragent5.py
setsid nohup bash agent-run.sh >> agent.log 2>&1 &
echo restore-done $(date)
"""

def call(tool, args, timeout=45):
    r = subprocess.run(["python3", "/home/hatch/workspace/muse-android-shell/server/phone.py",
                        "call", tool, json.dumps(args), "--timeout", str(timeout)],
                       capture_output=True, text=True)
    return json.loads(r.stdout)

b64 = base64.b64encode(SCRIPT.encode()).decode()
for attempt in range(5):
    out = call("linux.exec", {"cmd": f"printf %s '{b64}' | base64 -d > /tmp/fixup2.sh && chmod +x /tmp/fixup2.sh && echo WROTE"})
    if out.get("ok"):
        break
    time.sleep(3)
else:
    sys.exit(1)
for attempt in range(5):
    out = call("linux.exec", {"cmd": "setsid nohup bash /tmp/fixup2.sh > /tmp/fixup2.log 2>&1 & echo LAUNCHED"})
    if out.get("ok"):
        print("restore launched")
        break
    time.sleep(3)
else:
    sys.exit(1)
