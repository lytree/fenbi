#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Build a structured 700-question bank from the graphic-reasoning PDF (v3).

Robust segmentation (fixes v1 offset + v2 banner/overlap issues):
* Each question has exactly one embedded figure (or rarely two -> merged).
* Anchor blocks on FIGURE TOPS (reliable; title banners have no figure).
* For each figure, bracket it with the whitespace gap just ABOVE it (between
  the previous question's options and this question's number) and the gap just
  BELOW it (between this question's options and the next figure). This captures
  the FULL question (number + figure + options) and avoids the systematic
  one-question offset of naive figure-top banding.
* Over-split questions (two figures) are merged by collapsing the smallest
  inter-figure gaps until exactly 20 per set remain.

Outputs (into qbank_out/):
  images/s{ss}_q{nn}.png
  questions.json   {set,qnum,image,answer,page,top,bottom}
  questions.csv
"""
import os, csv, json
import fitz
import numpy as np
from PIL import Image

SRC = r'G:/Share/【图推】2027刘义恒图形推理700题/讲义/图形推理700题讲义.pdf'
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))   # tools/qbank -> 仓库根目录
OUT = os.path.join(ROOT, 'qbank_out')           # 生成产物目录（.gitignore 已忽略）
IMG_DIR = os.path.join(OUT, 'images')
DPI = 150
SCALE = DPI / 72.0
PAGE_BOTTOM_CLIP = 40   # drop page-number strip

DIVIDERS = [7,13,21,28,35,42,49,56,63,70,78,86,93,101,108,115,122,130,138,146,
            154,162,170,178,185,193,201,209,216,223,231,238,245,253,261]

RAW_KEY = [
 "DCABA CACCD CBBCB CDDBA","ADACD CCDDC ADABC BBABC","DACAB CABDD DCCCA DCDDC",
 "DACAD DBBDA ACBBB BACCA","CABCB ABBAB CABCA BBBAC","ADADC CADAD DBCAB CCACA",
 "ABACD CACCA ADCDB BACBD","DDABD ABDCD BBBDC DBCAD","AAABC CCDAA CDABA ACBDB",
 "ABBAD BABCC BAADC DDBDB","DAADA DDACC CDCCC ABCCA","ABADB DDBAD BDBDB DAADB",
 "BADDC BDABB DDBAB CBDAD","DDAAB DBACB ADACB CDABB","DCDAA DCDAD DABAA CCABB",
 "BBADC ACABB ACCDD CCBDB","CDACB DAACA ABABD DDDBB","DBACC BBCDD ACBCB BCDBA",
 "DCDDB CBCBC ABACA BCCDB","CDACD CBCCB CDCBD DBCDC","DCABD ADCBA CCACC BCBBA",
 "ABDCD DADCC BDCAC CCDCD","ADAAA BDADB DDBAD BBAAD","BACDD BABCA BDADC CBADB",
 "BACBC CACCA ACDBA BDDBD","AACAC CDDCD DBDBA CBDBD","CDADA CCBDB ACBCA BABBB",
 "CCCAB ACCDB CACCA AABDB","DCDAB ABDCC DDBCD ACAAA","BAABC CAADC DBDBB BCDCD",
 "CDBCD BADCC ADADA DAAAB","ACACD BADDB CBACA BDBCA","DBACD ACACB CAACC ACDAD",
 "BCCCD DAAAD DBACB DCDDB","BACAA BADAC ABDAC BDDBD",
]
ANSWER_KEY = [s.replace(' ', '') for s in RAW_KEY]
assert all(len(a) == 20 for a in ANSWER_KEY), "answer key length error"
assert len(ANSWER_KEY) == 35

doc = fitz.open(SRC)

def page_arr(pno):
    pg = doc[pno-1]
    pix = pg.get_pixmap(dpi=DPI)
    return np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width, pix.n)

def fig_tops(pno):
    pg = doc[pno-1]
    if len(pg.get_images(full=True)) == 0:
        return []
    return sorted(set((pg.rect.height - r.y1) * SCALE
                      for im in pg.get_images(full=True)
                      for r in pg.get_image_rects(im[0])))

def deepest_gap_below(arr, lo, hi, margin=18):
    """Whitespace gap in (lo, hi) that separates two questions, returned as its
    centre. We exclude a margin around both ends (figure tops often have a 1px
    low-ink row that would otherwise look like a gap) and pick the surviving
    gap whose end is closest to hi (the inter-question gap)."""
    H, W, _ = arr.shape
    g = arr[:, :, :3].mean(axis=2)
    dark = (g < 210).sum(axis=1)
    thr = int(0.02 * W)
    a = int(lo) + margin
    b = int(hi) - margin
    if b <= a:
        return int((int(lo) + int(hi)) // 2)
    runs = []; s = None
    for y in range(a, b):
        if dark[y] < thr and s is None: s = y
        elif dark[y] >= thr and s is not None: runs.append((s, y)); s = None
    if s is not None: runs.append((s, b))
    if not runs:
        return int((a + b) // 2)
    runs.sort(key=lambda r: r[1])   # gap closest to hi last
    best = runs[-1]
    return int((best[0] + best[1]) // 2)

# ---- collect figures + bracket gaps per set, in reading order ----
set_figs = {}
for s in range(35):
    a = DIVIDERS[s]
    b = DIVIDERS[s+1] - 1 if s < 34 else 267
    figs = []
    for pno in range(a, b+1):
        ft = fig_tops(pno)
        if not ft:
            continue
        arr = page_arr(pno)
        H = arr.shape[0]
        for idx, t in enumerate(ft):
            prev_same = ft[idx-1] if idx > 0 else None
            ga = deepest_gap_below(arr, prev_same if prev_same is not None else 0, t)
            if idx < len(ft) - 1:
                gb = deepest_gap_below(arr, t, ft[idx+1])
            else:
                gb = deepest_gap_below(arr, t, H - PAGE_BOTTOM_CLIP)
            figs.append((pno, int(round(t)), int(ga), int(gb)))
    set_figs[s] = figs

# ---- merge over-split questions until exactly 20 per set ----
def merge_to_20(figs):
    while len(figs) > 20:
        best_i, best_gap = 0, 1e9
        for i in range(len(figs) - 1):
            gap = figs[i+1][1] - figs[i][1]
            if gap < best_gap:
                best_gap, best_i = gap, i
        i = best_i
        # merge i and i+1: span from fig_i's top-gap to fig_{i+1}'s bottom-gap
        merged = (figs[i][0], figs[i][1], figs[i][2], figs[i+1][3])
        figs = figs[:i] + [merged] + figs[i+2:]
    return figs

records = []
for s in range(35):
    figs = merge_to_20(set_figs[s])
    assert len(figs) == 20, f"set {s+1}: {len(figs)}"
    rendered = {}
    for (pno, _, _, _) in figs:
        if pno not in rendered:
            rendered[pno] = page_arr(pno)
    for qnum, (pno, ftop, ga, gb) in enumerate(figs, start=1):
        arr = rendered[pno]
        H = arr.shape[0]
        top = max(0, ga)
        bottom = min(max(gb, top + 120), H)
        crop = arr[top:bottom, :, :]
        im = Image.fromarray(crop[:, :, :3])
        fname = f"s{s+1:02d}_q{qnum:02d}.png"
        im.save(os.path.join(IMG_DIR, fname))
        records.append({
            "set": s+1, "qnum": qnum, "image": fname,
            "answer": ANSWER_KEY[s][qnum-1],
            "page": pno, "top": top, "bottom": bottom,
        })

print("total records:", len(records))
with open(os.path.join(OUT, 'questions.json'), 'w', encoding='utf-8') as f:
    json.dump(records, f, ensure_ascii=False, indent=2)
with open(os.path.join(OUT, 'questions.csv'), 'w', encoding='utf-8', newline='') as f:
    w = csv.DictWriter(f, fieldnames=["set","qnum","image","answer","page","top","bottom"])
    w.writeheader()
    for r in records:
        w.writerow(r)
print("done")
