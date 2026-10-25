import sys, time, json
from rapidocr_onnxruntime import RapidOCR
t = time.time()
eng = RapidOCR()
res, _ = eng(sys.argv[1])
out = []
for box, txt, sc in (res or []):
    xs = [p[0] for p in box]; ys = [p[1] for p in box]
    out.append([txt, int(sum(xs)/len(xs)), int(sum(ys)/len(ys))])
print(json.dumps({"n": len(out), "sec": round(time.time()-t, 1), "items": out}, ensure_ascii=False))
