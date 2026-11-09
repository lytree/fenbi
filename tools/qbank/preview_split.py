#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把拆分结果画回原图做人工复核：题干框（蓝）、选项框（红，标 A~D）。

    uv run --with numpy --with pillow python tools/qbank/preview_split.py s01_q01 s05_q03
    # 输出 qbank_out/debug/preview/<题号>.png
"""

import os
import sys

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools", "qbank"))
import split_options as so  # noqa: E402

IMG = os.path.join(ROOT, "qbank_out", "images")
OUT = os.path.join(ROOT, "qbank_out", "debug", "preview")


def annotate(q, scale=1.0):
    p = os.path.join(IMG, q + ".png")
    im = Image.open(p).convert("RGB")
    if scale != 1.0:
        im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
    r = so.split_question(p)
    d = ImageDraw.Draw(im)
    sb = [int(v * scale) for v in r["stem_box"]]
    d.rectangle(sb, outline=(0, 90, 220), width=3)
    d.text((sb[0] + 4, max(0, sb[1] - 12)), "STEM", fill=(0, 90, 220))
    for k, b in zip("ABCD", r["options"]):
        bb = [int(v * scale) for v in b]
        d.rectangle(bb, outline=(220, 30, 30), width=3)
        d.text((bb[0] + 2, max(0, bb[1] - 11)), k, fill=(220, 30, 30))
    return im, r


def main(qs, scale=1.0, per_sheet=2):
    os.makedirs(OUT, exist_ok=True)
    tiles = []
    for q in qs:
        im, r = annotate(q, scale)
        canvas = Image.new("RGB", (im.width, im.height + 16), (255, 255, 255))
        canvas.paste(im, (0, 16))
        ImageDraw.Draw(canvas).text(
            (4, 2), "%s  %s  %s" % (q, r["grid"], r["method"]), fill=(0, 0, 0))
        tiles.append(canvas)
        print("%-9s %-5s %-14s stem=%s" % (q, r["grid"], r["method"], r["stem_box"]))
    # 按 per_sheet 张一组拼长条
    sheets = []
    for i in range(0, len(tiles), per_sheet):
        group = tiles[i:i + per_sheet]
        W = max(t.width for t in group)
        H = sum(t.height + 10 for t in group)
        s = Image.new("RGB", (W, H), (255, 255, 255))
        y = 0
        for t in group:
            s.paste(t, (0, y))
            y += t.height + 10
        sp = os.path.join(OUT, "_sheet%d.png" % (i // per_sheet + 1))
        s.save(sp)
        sheets.append(sp)
    print("输出：", *sheets, sep="\n  ")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    sc = 1.0
    for a in sys.argv[1:]:
        if a.startswith("--scale="):
            sc = float(a.split("=")[1])
    main(args or ["s01_q01", "s01_q02"], sc)
