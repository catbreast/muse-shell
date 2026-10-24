#!/usr/bin/env python3
"""Fetch a sliced screenshot (vscreen.see / a11y.see with part protocol, shell 0.6.6+)
and reassemble the JPEG locally.
Usage: see_parts.py <vscreen|a11y> <out.jpg> [maxBytes]
Each part is retried a few times; parts are ~3.4KB so they survive the relay."""
import base64
import json
import subprocess
import sys
import time

HERE = __file__.rsplit("/", 1)[0]


def call(tool, args, timeout=50):
    for attempt in range(4):
        p = subprocess.run(
            ["python3", f"{HERE}/phone.py", "call", tool, json.dumps(args),
             "--timeout", str(timeout)],
            capture_output=True, text=True)
        try:
            d = json.loads(p.stdout)
        except Exception:
            d = {}
        if d.get("ok"):
            return d["result"]
        time.sleep(1.5)
    raise RuntimeError(f"{tool} failed: {p.stdout[:200]}")


def main():
    kind = sys.argv[1]
    out = sys.argv[2]
    max_bytes = int(sys.argv[3]) if len(sys.argv) > 3 else 24000
    tool = "vscreen.see" if kind == "vscreen" else "a11y.see"
    first = call(tool, {"part": 0, "maxBytes": max_bytes})
    parts = first["parts"]
    chunks = [first["data"]]
    meta = {k: first.get(k) for k in ("imgWidth", "imgHeight", "scale",
                                      "screenWidth", "screenHeight")}
    for i in range(1, parts):
        r = call(tool, {"part": i})
        assert r["part"] == i, r
        chunks.append(r["data"])
    raw = base64.b64decode("".join(chunks))
    open(out, "wb").write(raw)
    print(json.dumps({"saved": out, "bytes": len(raw), "parts": parts, **meta}))


if __name__ == "__main__":
    main()
