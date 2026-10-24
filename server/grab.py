#!/usr/bin/env python3
"""Retry the site's 智能直达 (locating the button fresh each round) until a code is caught."""
import json, re, time, urllib.request, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
tok = json.load(open(os.path.join(HERE, "tokens.json")))["admin"]

def call(tool, args, timeout=40):
    req = urllib.request.Request("http://127.0.0.1:8390/call",
                                 data=json.dumps({"tool": tool, "args": args}).encode(),
                                 headers={"Content-Type": "application/json", "X-Token": tok})
    return json.load(urllib.request.urlopen(req, timeout=timeout))

def tree_text(nodes=120):
    r = call("a11y.tree", {"maxNodes": nodes})
    return r.get("result", {}).get("tree", "")

def find_btn(tree, needle):
    for line in tree.split("\n"):
        if needle in line:
            m = re.search(r"bounds=\[(\d+),(\d+),(\d+),(\d+)\]", line)
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                if y2 > y1:  # visible (not collapsed off-screen)
                    return (x1 + x2) // 2, (y1 + y2) // 2
    return None

for attempt in range(1, 9):
    call("a11y.tap", {"text": "好的"})
    time.sleep(0.5)
    t = tree_text()
    pos = find_btn(t, "onekeyBtn") or find_btn(t, "智能直达")
    if not pos:
        # scroll down a bit and try to find it
        call("android.input", {"kind": "swipe", "x1": 720, "y1": 1800, "x2": 720, "y2": 2400, "ms": 300})
        time.sleep(0.8)
        t = tree_text()
        pos = find_btn(t, "onekeyBtn") or find_btn(t, "智能直达")
    if not pos:
        print(f"attempt {attempt}: button not found")
        continue
    call("a11y.tap", {"x": pos[0], "y": pos[1]})
    time.sleep(2.8)
    t = tree_text(40)
    if "想要打开外部应用" in t:
        print(f"attempt {attempt}: GOT CODE - external app dialog shown")
        sys.exit(0)
    elif "暂无可用码" in t:
        print(f"attempt {attempt}: pool empty, retrying")
        continue
    elif "手慢" in t:
        print(f"attempt {attempt}: sniped, retrying")
        continue
    else:
        print(f"attempt {attempt}: unexpected state")
        print(t[:400])
        sys.exit(2)
print("exhausted attempts")
sys.exit(1)
