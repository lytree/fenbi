#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Build a structured question bank (700 questions) from the graphic-reasoning PDF.

Segmentation
------------
* Each question = embedded figure(s) + rasterized number/stem/options.
* Segment every content page into bands at embedded-figure tops using the
  split7 rule that was verified to yield complete single questions:
      band_i top    = 0            if i==0 else tops[i]
      band_i bottom = page_height  if i==k-1 else tops[i+1]
  (tops = sorted embedded-figure top pixel rows). Empirically each such band
  captures one full question (number+stem+figure(s)+options).
* Total embedded figures across the 35 sets = 714; each set must have exactly
  20 questions, so we merge the (figures_in_set - 20) smallest inter-figure
  gaps per set (multi-figure questions that were over-split).
* Each question is aligned with the answer key (35 sets x 20 letters),
  transcribed directly from the PDF 参考答案 pages.

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

DIVIDERS = [7,13,21,28,35,42,49,56,63,70,78,86,93,101,108,115,122,130,138,146,
            154,162,170,178,185,193,201,209,216,223,231,238,245,253,261]

RAW_KEY = [
 "DCABA CACCD CBBCB CDDBA",  # (一)
 "ADACD CCDDC ADABC BBABC",  # (二)
 "DACAB CABDD DCCCA DCDDC",  # (三)
 "DACAD DBBDA ACBBB BACCA",  # (四)
 "CABCB ABBAB CABCA BBBAC",  # (五)
 "ADADC CADAD DBCAB CCACA",  # (六)
 "ABACD CACCA ADCDB BACBD",  # (七)
 "DDABD ABDCD BBBDC DBCAD",  # (八)
 "AAABC CCDAA CDABA ACBDB",  # (九)
 "ABBAD BABCC BAADC DDBDB",  # (十)
 "DAADA DDACC CDCCC ABCCA",  # (十一)
 "ABADB DDBAD BDBDB DAADB",  # (十二)
 "BADDC BDABB DDBAB CBDAD",  # (十三)
 "DDAAB DBACB ADACB CDABB",  # (十四)
 "DCDAA DCDAD DABAA CCABB",  # (十五)
 "BBADC ACABB ACCDD CCBDB",  # (十六)
 "CDACB DAACA ABABD DDDBB",  # (十七)
 "DBACC BBCDD ACBCB BCDBA",  # (十八)
 "DCDDB CBCBC ABACA BCCDB",  # (十九)
 "CDACD CBCCB CDCBD DBCDC",  # (二十)
 "DCABD ADCBA CCACC BCBBA",  # (二十一)
 "ABDCD DADCC BDCAC CCDCD",  # (二十二)
 "ADAAA BDADB DDBAD BBAAD",  # (二十三)
 "BACDD BABCA BDADC CBADB",  # (二十四)
 "BACBC CACCA ACDBA BDDBD",  # (二十五)
 "AACAC CDDCD DBDBA CBDBD",  # (二十六)
 "CDADA CCBDB ACBCA BABBB",  # (二十七)
 "CCCAB ACCDB CACCA AABDB",  # (二十八)
 "DCDAB ABDCC DDBCD ACAAA",  # (二十九)
 "BAABC CAADC DBDBB BCDCD",  # (三十)
 "CDBCD BADCC ADADA DAAAB",  # (三十一)
 "ACACD BADDB CBACA BDBCA",  # (三十二)
 "DBACD ACACB CAACC ACDAD",  # (三十三)
 "BCCCD DAAAD DBACB DCDDB",  # (三十四)
 "BACAA BADAC ABDAC BDDBD",  # (三十五)
]
ANSWER_KEY = [s.replace(' ', '') for s in RAW_KEY]
for i, a in enumerate(ANSWER_KEY):
    assert len(a) == 20, f"set {i+1} answer length {len(a)}: {a}"
assert len(ANSWER_KEY) == 35

doc = fitz.open(SRC)
print("PDF pages:", doc.page_count)

def page_figure_tops(pno):
    pg = doc[pno-1]
    if len(pg.get_images(full=True)) == 0:
        return None
    tops = [(pg.rect.height - r.y1) * SCALE for im in pg.get_images(full=True) for r in pg.get_image_rects(im[0])]
    return sorted(set(tops))

bounds = []
for s in range(35):
    a = DIVIDERS[s]
    b = DIVIDERS[s+1]-1 if s < 34 else 267
    bounds.append((a, b))

set_pages = {}
for s, (a, b) in enumerate(bounds):
    pages = []
    for pno in range(a, b+1):
        tops = page_figure_tops(pno)
        if tops is None:
            continue
        pages.append((pno, tops))
    set_pages[s] = pages

def build_set_bands(s):
    pages = set_pages[s]
    page_segs = {}
    for pno, tops in pages:
        H = int(doc[pno-1].rect.height * SCALE)
        k = len(tops)
        segs = []
        for i in range(k):
            top = 0 if i == 0 else tops[i]
            bot = H if i == k-1 else tops[i+1]
            segs.append([top, bot])
        page_segs[pno] = segs
    # candidate merge gaps = consecutive figure-top differences within a page
    gaps = []
    for pno, tops in pages:
        for i in range(len(tops)-1):
            gaps.append((pno, i, tops[i+1]-tops[i]))
    gaps.sort(key=lambda x: x[2])
    merges = sum(len(page_segs[p]) for p in page_segs) - 20
    assert merges >= 0, f"set {s+1}: negative merges {merges}"
    for _ in range(merges):
        while True:
            pno, idx, _ = gaps.pop(0)
            segs = page_segs[pno]
            if idx >= len(segs)-1:
                continue
            segs[idx][1] = segs[idx+1][1]
            del segs[idx+1]
            break
    questions = []
    for pno, _ in pages:
        for seg in page_segs[pno]:
            questions.append((pno, seg[0], seg[1]))
    return questions

os.makedirs(IMG_DIR, exist_ok=True)
# Note: image filenames are deterministic (s{ss}_q{nn}.png) so re-running
# overwrites cleanly; we intentionally do NOT bulk-delete to avoid the
# safe-delete guard.

records = []
for s in range(35):
    questions = build_set_bands(s)
    assert len(questions) == 20, f"set {s+1} -> {len(questions)} questions"
    rendered = {}
    for pno, top, bot in questions:
        if pno not in rendered:
            pix = doc[pno-1].get_pixmap(dpi=DPI)
            rendered[pno] = np.frombuffer(pix.samples, dtype=np.uint8).reshape(
                pix.height, pix.width, pix.n)
    for qnum, (pno, top, bot) in enumerate(questions, start=1):
        img = rendered[pno]
        t = max(0, int(round(top))); b = min(img.shape[0], int(round(bot)))
        crop = img[t:b, :, :]
        im = Image.fromarray(crop[:, :, :3])
        fname = f"s{s+1:02d}_q{qnum:02d}.png"
        im.save(os.path.join(IMG_DIR, fname))
        records.append({
            "set": s+1, "qnum": qnum, "image": fname,
            "answer": ANSWER_KEY[s][qnum-1],
            "page": pno, "top": t, "bottom": b,
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
