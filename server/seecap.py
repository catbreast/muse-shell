import json, sys, urllib.request, base64, os
HERE = os.path.dirname(os.path.abspath(__file__))
tok = json.load(open(os.path.join(HERE, "tokens.json")))
out = sys.argv[1]
req = urllib.request.Request("http://127.0.0.1:8390/call",
    data=json.dumps({"tool": "a11y.see", "args": {"maxWidth": 1080}}).encode(),
    headers={"Content-Type": "application/json", "X-Token": tok["admin"]})
r = json.load(urllib.request.urlopen(req, timeout=60))
res = r.get("result", {})
print("ok:", res.get("ok"), "err:", r.get("error"))
if res.get("jpeg_base64"):
    open(out, "wb").write(base64.b64decode(res["jpeg_base64"]))
    print("saved", out)
