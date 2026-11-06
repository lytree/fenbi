"""对《2027 政治理论背诵手册》第三部分「考前 200 题」做 OCR（分片）。

用法：
  uv run --no-project --with rapidocr_onnxruntime --with pymupdf --with numpy \
      python tools/ocr_zhengzhi.py --start 189 --end 202 --out tmp/zhengzhi/ocr_a.json

每片写自己的文件，互不干扰；跑完用 --merge 合并成 ocr_lines.json。
"""
import argparse
import glob
import json
import os

import numpy as np
import pymupdf
from rapidocr_onnxruntime import RapidOCR

PDF = r"C:/Users/hiyan/Downloads/Documents/2027年政治理论背诵手册_2026102210434284.pdf"


def run(start, end, out, dpi, limit):
    ocr = RapidOCR(det_limit_side_len=limit)
    doc = pymupdf.open(PDF)
    data = {}
    if os.path.exists(out):
        with open(out, encoding="utf-8") as f:
            data = json.load(f)
    for i in range(start, end + 1):
        if data.get(str(i)):
            continue
        pm = doc[i].get_pixmap(dpi=dpi)
        img = np.frombuffer(pm.samples, dtype=np.uint8).reshape(pm.height, pm.width, pm.n)
        res, _ = ocr(img)
        lines = []
        for box, text, score in res or []:
            text = text.strip()
            if not text:
                continue
            xs = [p[0] for p in box]
            ys = [p[1] for p in box]
            lines.append({
                "y": round(min(ys), 1),
                "x": round(min(xs), 1),
                "x2": round(max(xs), 1),
                "text": text,
                "score": round(float(score), 3),
            })
        lines.sort(key=lambda d: (d["y"], d["x"]))
        data[str(i)] = lines
        print(f"page {i} -> {len(lines)}", flush=True)
        with open(out, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
    print("done", out, len(data))


def merge(pattern, out):
    merged = {}
    for p in sorted(glob.glob(pattern)):
        with open(p, encoding="utf-8") as f:
            merged.update(json.load(f))
    with open(out, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False, indent=1)
    print("merged", len(merged), "pages ->", out)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", type=int)
    ap.add_argument("--end", type=int)
    ap.add_argument("--out", default="")
    ap.add_argument("--dpi", type=int, default=220)
    ap.add_argument("--limit", type=int, default=1800)
    ap.add_argument("--merge", action="store_true")
    a = ap.parse_args()
    if a.merge:
        merge("tmp/zhengzhi/ocr_*.json", "tmp/zhengzhi/ocr_lines.json")
    else:
        run(a.start, a.end, a.out, a.dpi, a.limit)
