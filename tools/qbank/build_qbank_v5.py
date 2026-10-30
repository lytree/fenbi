#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Build a structured 700-question bank from the graphic-reasoning PDF (v5, hybrid).

Strategy:
* Each question has exactly ONE embedded figure graphic -> reliable 1-per-question
  anchor. Figure TOPs are taken in FROM-TOP pixel coords (correct reading order).
* Each question also has a left-column question NUMBER. We use the numbers to
  recover the rare questions whose figure is rendered as vector art (no embedded
  image). Combined anchors = figures (with their number) + numbers without a
  nearby figure.
* BANNER pages: a real banner has a top-left "超格学员专用" box AND a wide centered
  title band. Pure-title pages (no content below) are skipped; mixed pages keep
  their questions but ignore banner-region ink.
* Reconcile each set to exactly 20 question anchors (merge closest pairs if >20;
  the smallest gap is usually two figures of one question; split tallest block
  if <20). Then crop [number_top, next_number_top] (or page content bottom).

Outputs: images/s{ss}_q{nn}.png, questions.json, questions.csv
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

def analyze_page(arr):
    """Return (skip_page, banner_bot)."""
    H, W, _ = arr.shape
    g = arr[:, :, :3].mean(axis=2); dark = g < 200
    tl = dark[:int(0.18*H), :int(0.15*W)].sum() > 0
    limit = int(0.25*H); band = 0
    for y in range(limit):
        xs = np.where(dark[y])[0]
        if len(xs) == 0: continue
        if (xs.max()-xs.min()) > 0.55*W and xs.min() < 0.25*W and xs.max() > 0.75*W:
            band = y
    if tl and band > 0:
        bottom_rows = np.where((dark[int(0.45*H):]).sum(axis=1) > 0.02*W)[0]
        bottom_ink = len(bottom_rows) / (0.55*H)
        if bottom_ink < 0.02:
            return (True, 0)          # pure title page -> skip
        return (False, band + 10)     # mixed -> ignore banner region
    return (False, 0)

def left_groups(arr, xfrac, merge, thr=240, minh=6, ymin=0):
    H, W, _ = arr.shape; x1 = int(xfrac*W)
    g = arr[:, :x1, :3].mean(axis=2); dark = (g < thr).sum(axis=1)
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

def fig_tops_fromtop(pno):
    pg = doc[pno-1]
    if len(pg.get_images(full=True)) == 0:
        return []
    return sorted(set(int(r.y0*SCALE) for im in pg.get_images(full=True)
                     for r in pg.get_image_rects(im[0])))

def last_content_bottom(arr, start_y):
    H = arr.shape[0]
    g = arr[:, :, :3].mean(axis=2)
    dark = (g < 210).sum(axis=1)
    last = start_y
    for y in range(int(start_y), H - 40):
        if dark[y] > 0.02 * arr.shape[1]:
            last = y
    return min(last + 15, H - 30)

# ---- per-set candidate collection ----
set_cands = {}   # s -> list of (pno, qtop, ftop_or_None)
for s in range(35):
    a = DIVIDERS[s]; b = DIVIDERS[s+1]-1 if s < 34 else 267
    figs = []; nums = []
    for pno in range(a, b+1):
        arr = page_arr(pno)
        skip, bb = analyze_page(arr)
        if skip:
            continue
        for t in fig_tops_fromtop(pno):
            if bb and t < bb:
                continue
            figs.append((pno, t))
        for (ta, tb) in left_groups(arr, 0.30, 40, ymin=bb):
            nums.append((pno, ta))
    # associate: for each figure, the number whose top is within [ftop-350, ftop+60]
    used_num = set()
    cands = []
    for (pno, ft) in figs:
        best = None; bestd = 1e9
        for i, (pn2, nt) in enumerate(nums):
            if pn2 != pno:
                continue
            if nt < ft - 350 or nt > ft + 60:
                continue
            d = ft - nt
            if d < bestd:
                bestd, best = d, i
        if best is not None:
            used_num.add(best)
            cands.append((pno, nums[best][1], ft))
        else:
            cands.append((pno, ft - 50, ft))   # estimate number top
    # numbers without a figure -> vector-figure questions
    for i, (pno, nt) in enumerate(nums):
        if i in used_num:
            continue
        cands.append((pno, nt, None))
    set_cands[s] = cands

def reconcile(cands):
    # cands: (pno, qtop, ftop) ; sort by (pno, qtop)
    cands = sorted(set_cands_item_dedup(cands), key=lambda x: (x[0], x[1]))
    while len(cands) > 20:
        best_i, best_gap = 0, 1e9
        for i in range(len(cands)-1):
            if cands[i][0] == cands[i+1][0]:
                gap = cands[i+1][1] - cands[i][1]
            else:
                gap = 1e8   # different page: don't merge across pages
            if gap < best_gap:
                best_gap, best_i = gap, i
        # merge: keep first, drop second (assume two figs of one question)
        cands = cands[:best_i] + cands[best_i+1:]
    # if <20, split tallest block (largest gap between consecutive same-page)
    # add synthetic anchors by halving the tallest consecutive same-page gap
    guard = 0
    while len(cands) < 20 and guard < 50:
        guard += 1
        # find largest same-page gap
        best_i, best_gap = -1, -1
        for i in range(len(cands)-1):
            if cands[i][0] == cands[i+1][0]:
                gap = cands[i+1][1] - cands[i][1]
                if gap > best_gap:
                    best_gap, best_i = gap, i
        if best_i < 0 or best_gap < 200:
            break
        # insert midpoint
        p = cands[best_i][0]
        mid = (cands[best_i][1] + cands[best_i+1][1]) // 2
        cands.insert(best_i+1, (p, mid, None))
    return cands

def set_cands_item_dedup(cands):
    # dedupe candidates within 60px on same page
    out = []
    for c in sorted(cands, key=lambda x: (x[0], x[1])):
        if out and c[0] == out[-1][0] and abs(c[1] - out[-1][1]) < 60:
            continue
        out.append(c)
    return out

# ---- dry run ----
if DRY:
    print("=== DRY RUN: hybrid candidate counts ===")
    tot = 0
    for s in range(35):
        raw = set_cands_item_dedup(set_cands[s])
        c = reconcile(set_cands[s])
        tot += len(c)
        flag = "" if len(raw) == 20 else "   <<< RAW!=20"
        print(f" set {s+1:2d}: raw={len(raw):3d} -> {len(c)}{flag}")
    print("TOTAL:", tot)
    sys.exit(0)

# ---- build ----
records = []
rendered = {}
for s in range(35):
    cands = reconcile(set_cands[s])
    assert len(cands) == 20, f"set {s+1}: {len(cands)}"
    for (pno, _, _) in cands:
        if pno not in rendered:
            rendered[pno] = page_arr(pno)
    for qnum, (pno, qtop, ftop) in enumerate(cands, start=1):
        arr = rendered[pno]
        H = arr.shape[0]
        top = max(0, int(round(qtop)))
        nxt = None
        for (p2, t2, f2) in cands:
            if p2 == pno and t2 > qtop + 5:
                nxt = t2; break
        if nxt is not None:
            bottom = int(nxt)
        elif ftop is not None:
            bottom = int(last_content_bottom(arr, ftop))
        else:
            bottom = int(last_content_bottom(arr, top + 50))
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
