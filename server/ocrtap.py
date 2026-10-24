#!/usr/bin/env python3
"""OCR 补树（学 ARTEMIS 的 fused hierarchy）：控件树里没字的 H5/自绘页面，
拿截图 OCR 认字建索引，按字点击。必须用 OCR venv 的 python 运行：
  ./ocr-venv/bin/python ocrtap.py index [--vscreen]
  ./ocr-venv/bin/python ocrtap.py tap <文字> [--vscreen] [--idx N] [--dry]
  ./ocr-venv/bin/python ocrtap.py file <图片路径>
索引/点击坐标都换算到目标屏的真实像素，主屏点击带 pageChanged 回执验收。"""
import base64
import difflib
import io
import json
import sys

sys.path.insert(0, ".")
from phone import req as phone_req
from PIL import Image
from rapidocr_onnxruntime import RapidOCR

ENGINE = RapidOCR()


def call(tool, args, timeout=60):
    res = phone_req("POST", "/call", {"tool": tool, "args": args, "timeout": timeout},
                    timeout=timeout + 15)
    if not res.get("ok"):
        raise RuntimeError(f"{tool} failed: {res.get('error')}")
    return res["result"]


def grab(vscreen):
    """Return (jpeg_bytes, real_w, real_h)."""
    if vscreen:
        r = call("vscreen.see", {}, 30)
        img = base64.b64decode(r["jpeg_base64"])
        im = Image.open(io.BytesIO(img))
        return img, im.width, im.height
    r = call("a11y.see", {"maxWidth": 1080}, 30)
    img = base64.b64decode(r["jpeg_base64"])
    nodes = call("a11y.nodes", {"maxNodes": 5}, 20)
    root = nodes["nodes"][0]
    return img, root["w"], root["h"]


def ocr_index(img_bytes):
    import numpy as np
    im = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    result, _ = ENGINE(np.array(im))
    items = []
    for i, (box, text, score) in enumerate(result or []):
        xs = [p[0] for p in box]
        ys = [p[1] for p in box]
        items.append({
            "index": i,
            "text": text,
            "score": round(float(score), 3),
            "cx_img": sum(xs) / len(xs),
            "cy_img": sum(ys) / len(ys),
            "box": [min(xs), min(ys), max(xs), max(ys)],
        })
    return im.width, im.height, items


def norm(s):
    return "".join(str(s).split()).lower()


def find(items, query):
    q = norm(query)
    hits = []
    for it in items:
        t = norm(it["text"])
        if q in t or t in q and len(t) >= 2:
            hits.append((1.0, it))
        else:
            sim = difflib.SequenceMatcher(None, q, t).ratio()
            if sim >= 0.6:
                hits.append((sim, it))
    hits.sort(key=lambda x: (-x[0], -x[1]["score"]))
    return hits


def main():
    argv = sys.argv[1:]
    vscreen = "--vscreen" in argv
    argv = [a for a in argv if a != "--vscreen"]
    if not argv:
        print(__doc__)
        return 2
    cmd = argv[0]

    if cmd == "file":
        img = open(argv[1], "rb").read()
        w, h, items = ocr_index(img)
        print(f"image {w}x{h}, {len(items)} blocks")
        for it in items:
            print(f"[{it['index']}] {it['text']!r} score={it['score']} "
                  f"center_img=({it['cx_img']:.0f},{it['cy_img']:.0f})")
        return 0

    img, real_w, real_h = grab(vscreen)
    w, h, items = ocr_index(img)
    sx, sy = real_w / w, real_h / h
    for it in items:
        it["cx"] = it["cx_img"] * sx
        it["cy"] = it["cy_img"] * sy

    if cmd == "index":
        print(f"screen {real_w}x{real_h} (img {w}x{h}), {len(items)} blocks")
        for it in items:
            print(f"[{it['index']}] {it['text']!r} score={it['score']} "
                  f"tap=({it['cx']:.0f},{it['cy']:.0f})")
        return 0

    if cmd == "tap":
        query = argv[1]
        idx = 0
        dry = "--dry" in argv
        if "--idx" in argv:
            idx = int(argv[argv.index("--idx") + 1])
        hits = find(items, query)
        if not hits:
            print(f"NOT FOUND: {query!r} (screen has {len(items)} OCR blocks)")
            return 1
        for j, (sim, it) in enumerate(hits[:6]):
            mark = " <==" if j == idx else ""
            print(f"cand[{j}] sim={sim:.2f} {it['text']!r} score={it['score']} "
                  f"tap=({it['cx']:.0f},{it['cy']:.0f}){mark}")
        _, it = hits[idx]
        if dry:
            print("dry run, not tapping")
            return 0
        x, y = int(round(it["cx"])), int(round(it["cy"]))
        if vscreen:
            r = call("vscreen.tap", {"x": x, "y": y}, 30)
        else:
            r = call("a11y.tap", {"x": x, "y": y}, 40)
        print("tap result:", json.dumps(r, ensure_ascii=False))
        return 0

    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
