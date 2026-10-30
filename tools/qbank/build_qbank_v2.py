#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Build a structured 700-question bank from the graphic-reasoning PDF (v2).

Segmentation strategy (fixes the systematic 1-question offset of v1):
* Each question has exactly one embedded figure (or, rarely, two that belong
  to the same question -> over-split, merged below).
* The reliable anchor for a question block is its QUESTION NUMBER, which sits
  at the top-left of every block. We locate each figure's associated number by
  scanning the left column (x < 0.25*W) for ink and taking the group that is
  just above (or contains) the figure's top.
* A question block = [number_top, next_question_number_top] on the same page,
  or [number_top, page_bottom-margin] for the last block on a page.
* Sets with >20 figures (rare two-figure questions) are merged by collapsing
  the smallest inter-figure gaps until exactly 20 questions remain.

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
BOTTOM_MARGIN = 45   # drop page-number strip at very bottom

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

def left_groups(arr, xfrac=0.25, merge=100, minh=8):
    H, W, _ = arr.shape
    x1 = int(xfrac * W)
    g = arr[:, :x1, :3].mean(axis=2)
    dark = (g < 200).sum(axis=1)
    runs = []; s = None
    for y in range(H):
        if dark[y] > 0 and s is None: s = y
        elif dark[y] == 0 and s is not None: runs.append((s, y)); s = None
    if s is not None: runs.append((s, H))
    groups = []
    for (a, b) in runs:
        if groups and a - groups[-1][1] <= merge:
            groups[-1] = (groups[-1][0], b)
        else:
            groups.append((a, b))
    return [(a, b) for (a, b) in groups if b - a >= minh]

def number_top_for_figure(arr, t):
    """Return the y of the question number associated with figure top t."""
    groups = left_groups(arr)
    # group that contains t (figure's left part bleeds into left column)
    for (a, b) in groups:
        if a <= t <= b:
            return a
    above = [g for g in groups if g[1] <= t]
    if above:
        return min(above, key=lambda g: t - g[1])[0]
    below = [g for g in groups if g[0] >= t]
    if below:
        return min(below, key=lambda g: g[0] - t)[0]
    return int(t)

def last_content_bottom(arr, start_y):
    """Bottom of the last inked row at/below start_y (options bottom)."""
    H = arr.shape[0]
    g = arr[:, :, :3].mean(axis=2)
    dark = (g < 210).sum(axis=1)
    last = start_y
    for y in range(int(start_y), H - 40):   # skip page-number strip
        if dark[y] > 0.02 * arr.shape[1]:
            last = y
    return min(last + 15, H - 30)

def gap_before(arr, ya, yb):
    """Find the whitespace gap between question ya and the next question yb,
    choosing the gap whose end is closest to yb (the inter-question gap)."""
    H, W, _ = arr.shape
    g = arr[:, :, :3].mean(axis=2)
    dark = (g < 210).sum(axis=1)
    thr = int(0.02 * W)
    runs = []; s = None
    for y in range(int(ya), int(yb)):
        if dark[y] < thr and s is None: s = y
        elif dark[y] >= thr and s is not None: runs.append((s, y)); s = None
    if s is not None: runs.append((s, int(yb)))
    if not runs:
        return int((ya + yb) // 2)
    runs.sort(key=lambda r: r[1])          # gap closest to yb last
    best = runs[-1]
    return int((best[0] + best[1]) // 2)

# ---- collect figures + number-tops per set, in reading order ----
set_figs = {}   # s -> list of (pno, fig_top, number_top)
for s in range(35):
    a = DIVIDERS[s]
    b = DIVIDERS[s+1] - 1 if s < 34 else 267
    figs = []
    for pno in range(a, b+1):
        ft = fig_tops(pno)
        if not ft:
            continue
        arr = page_arr(pno)
        for t in ft:
            figs.append((pno, int(round(t)), int(number_top_for_figure(arr, t))))
    set_figs[s] = figs

# ---- merge over-split questions until exactly 20 per set ----
def merge_to_20(figs):
    while len(figs) > 20:
        # smallest gap between consecutive figure tops (reading order)
        best_i, best_gap = 0, 1e9
        for i in range(len(figs) - 1):
            gap = figs[i+1][1] - figs[i][1]
            if gap < best_gap:
                best_gap, best_i = gap, i
        # keep figs[i] (its number_top), drop figs[i+1]
        figs = figs[:best_i] + figs[best_i+1:]
    return figs

records = []
for s in range(35):
    figs = merge_to_20(set_figs[s])
    assert len(figs) == 20, f"set {s+1}: {len(figs)}"
    # render needed pages once
    rendered = {}
    for (pno, _, _) in figs:
        if pno not in rendered:
            rendered[pno] = page_arr(pno)
    for qnum, (pno, ftop, ntop) in enumerate(figs, start=1):
        arr = rendered[pno]
        H = arr.shape[0]
        top = max(0, int(round(ntop)))
        # bottom = gap before next question on same page, else dynamic content bottom
        nxt = None
        for (p2, f2, n2) in figs:
            if p2 == pno and n2 > ntop + 5:
                nxt = n2; break
        if nxt is not None:
            bottom = gap_before(arr, ntop, nxt)
        else:
            bottom = int(last_content_bottom(arr, ftop))
        bottom = int(min(max(bottom, top + 120), H))
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
