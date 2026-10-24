#!/usr/bin/env python3
"""Fetch the current vscreen frame and save it as a JPEG for inspection."""
import json, sys, urllib.request, base64, os

HERE = os.path.dirname(os.path.abspath(__file__))
tok = json.load(open(os.path.join(HERE, "tokens.json")))
out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/vscreen.jpg"
req = urllib.request.Request("http://127.0.0.1:8390/call",
                             data=json.dumps({"tool": "vscreen.see", "args": {}}).encode(),
                             headers={"Content-Type": "application/json", "X-Token": tok["admin"]})
r = json.load(urllib.request.urlopen(req, timeout=60))
res = r.get("result", {})
print("ok:", res.get("ok"), "changed:", res.get("screenChanged"), "err:", r.get("error"))
if res.get("jpeg_base64"):
    open(out, "wb").write(base64.b64decode(res["jpeg_base64"]))
    print("saved", out)
