#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Build a structured 700-question bank from the graphic-reasoning PDF (v4).

Segmentation strategy (figure-anchored, correct top->bottom coordinates,
banner-safe):
* Each question has exactly ONE embedded figure (1-per-question counting is
  reliable: every set yields 20 figures).  The figure's TOP is taken in
  FROM-TOP pixel coordinates (y=0 at the top of the rendered page), which is
  the correct reading order (v2 erroneously measured from the bottom, which
  paired the bottom-most figure with the banner box on banner pages).
* For each figure we find its QUESTION NUMBER by scanning the left column for
  the ink group just above (or containing) the figure top.  The question block
  is [number_top, next_number_top] on the same page, or [number_top, page
  content bottom] for the last block on a page.
* BANNER pages (e.g. "挑战图推700题（三十五）") carry a wide centered title band
  in the top ~25%.  We detect that band and (a) exclude any left-column ink
  that sits inside the banner region and (b) drop any figure whose top lies
  inside the banner region, so the banner never becomes a fake question 1.
* Sets that yield more than 20 figures (a banner deco image, or a rare
  two-figure question) are reconciled by collapsing the smallest inter-figure
  gaps until exactly 20 remain.

Outputs (into qbank_out/):
  images/s{ss}_q{nn}.png
  questions.json   {set,qnum,image,answer,page,top,bottom}
  questions.csv
"""
import os, csv, json, sys
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
DRY = (len(sys.argv) > 1 and sys.argv[1] == 'dry')

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
assert all(len(a) == 20 for a in ANSWER_KEY)
assert len(ANSWER_KEY) == 35

doc = fitz.open(SRC)

def page_arr(pno):
    pg = doc[pno-1]
    pix = pg.get_pixmap(dpi=DPI)
    return np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width, pix.n)

def fig_tops_fromtop(pno):
    """Embedded-image TOP y-coordinates measured FROM THE TOP of the page
    (correct reading order). Returns a sorted list of unique values."""
    pg = doc[pno-1]
    if len(pg.get_images(full=True)) == 0:
        return []
    return sorted(set((r.y0) * SCALE
                      for im in pg.get_images(full=True)
                      for r in pg.get_image_rects(im[0])))

def left_groups(arr, xfrac=0.25, merge=100, minh=8, ymin=0):
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
    return [(a, b) for (a, b) in groups if b - a >= minh and a >= ymin]

def detect_banner(arr):
    """Return the bottom-y (from top) of a centered title band in the top 25%,
    else 0.  Title band = a row whose dark span is wide (>0.55W) and centered."""
    H, W, _ = arr.shape
    g = arr[:, :, :3].mean(axis=2)
    dark = g < 200
    limit = int(0.25 * H)
    band_rows = []
    for y in range(limit):
        xs = np.where(dark[y])[0]
        if len(xs) == 0:
            continue
        xmin, xmax = int(xs.min()), int(xs.max())
        if (xmax - xmin) > 0.55 * W and xmin < 0.25 * W and xmax > 0.75 * W:
            band_rows.append(y)
    if not band_rows:
        return 0
    return int(max(band_rows)) + 8

def number_top_for_figure(arr, t, ymin=0):
    """Return the y of the question number associated with figure top t."""
    groups = left_groups(arr, ymin=ymin)
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
    H = arr.shape[0]
    g = arr[:, :, :3].mean(axis=2)
    dark = (g < 210).sum(axis=1)
    last = start_y
    for y in range(int(start_y), H - 40):
        if dark[y] > 0.02 * arr.shape[1]:
            last = y
    return min(last + 15, H - 30)

# ---- collect figures (+ their number tops) per set, reading order ----
set_figs = {}   # s -> list of (pno, ftop, ntop)
for s in range(35):
    a = DIVIDERS[s]
    b = DIVIDERS[s+1] - 1 if s < 34 else 267
    figs = []
    for pno in range(a, b+1):
        ft = fig_tops_fromtop(pno)
        if not ft:
            continue
        arr = page_arr(pno)
        banner_bot = detect_banner(arr)
        for t in ft:
            if banner_bot and t < banner_bot:
                continue   # skip a banner decoration figure if any
            ntop = number_top_for_figure(arr, t, ymin=banner_bot)
            figs.append((pno, int(round(t)), int(round(ntop))))
    set_figs[s] = figs

def reconcile(figs):
    while len(figs) > 20:
        best_i, best_gap = 0, 1e9
        for i in range(len(figs) - 1):
            gap = figs[i+1][1] - figs[i][1]
            if gap < best_gap:
                best_gap, best_i = gap, i
        figs = figs[:best_i] + figs[best_i+1:]
    return figs

# ---- dry-run report ----
if DRY:
    print("=== DRY RUN: figure counts per set ===")
    tot = 0
    for s in range(35):
        gs = set_figs[s]
        tot += len(gs)
        bypage = {}
        for (pno, ft, nt) in gs:
            bypage.setdefault(pno, []).append(ft)
        detail = ", ".join(f"p{p}:[{','.join(str(t) for t in sorted(v))}]" for p, v in sorted(bypage.items()))
        flag = "" if len(gs) == 20 else "  <<< NOT 20"
        print(f" set {s+1:2d}: {len(gs):2d} figs | {detail}{flag}")
    print(f"TOTAL: {tot} (expected 700)")
    sys.exit(0)

# ---- build records ----
records = []
rendered = {}
for s in range(35):
    figs = reconcile(set_figs[s])
    assert len(figs) == 20, f"set {s+1}: {len(figs)}"
    for (pno, _, _) in figs:
        if pno not in rendered:
            rendered[pno] = page_arr(pno)
    for qnum, (pno, ftop, ntop) in enumerate(figs, start=1):
        arr = rendered[pno]
        H = arr.shape[0]
        top = max(0, int(round(ntop)))
        nxt = None
        for (p2, f2, n2) in figs:
            if p2 == pno and n2 > ntop + 5:
                nxt = n2; break
        if nxt is not None:
            bottom = int(nxt)
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
