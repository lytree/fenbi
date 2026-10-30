#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Build the structured 700-question bank from the graphic-reasoning PDF (v6).

Why v6: the PDF has no text layer, but each question starts with a printed
number ("1." ... "20.") sitting at a fixed left margin (x ~ 176-221 at 150dpi)
on the SAME line as the stem. We OCR a narrow vertical band of the left margin
(x 172-240) at 400dpi with a digit whitelist; the leading digits of each token
give the question number. That yields, for 33 of 35 sets, exactly the 20 numbers
in order, which is a perfect 1:1 anchor for cropping.

Cropping:  top = number_line_y - PAD_TOP
           bottom = next question's number_line_y - PAD_TOP   (same page)
                 or last content row on the page (page break)

Outputs: images/s{ss}_q{nn}.png, questions.json, questions.csv
"""
import os, csv, json, re
import pymupdf
import numpy as np
from PIL import Image

SRC = r'G:/Share/【图推】2027刘义恒图形推理700题/讲义/图形推理700题讲义.pdf'
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))   # tools/qbank -> 仓库根目录
OUT = os.path.join(ROOT, 'qbank_out')           # 生成产物目录（.gitignore 已忽略）
IMG_DIR = os.path.join(OUT, 'images')
OCR_JSON = os.path.join(OUT, 'ocr_margin.json')
DPI = 150
PAD_TOP = 12          # blank margin kept above the number line
PAD_BOT = 10          # blank margin kept below the crop

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
assert all(len(a) == 20 for a in ANSWER_KEY) and len(ANSWER_KEY) == 35

# OCR failed on these few number lines; positions verified visually.
PATCH = {
    (21, 4): (156, 212), (21, 5): (156, 533), (21, 6): (156, 906),
    (34, 3): (254, 1041),
}

doc = pymupdf.open(SRC)
OCR = {int(k): v for k, v in json.load(open(OCR_JSON, encoding='utf-8')).items()}
_cache = {}

def page_arr(pno):
    if pno not in _cache:
        pg = doc[pno - 1]
        pix = pg.get_pixmap(dpi=DPI)
        _cache[pno] = np.frombuffer(pix.samples, dtype=np.uint8).reshape(
            pix.height, pix.width, pix.n)[:, :, :3].copy()
    return _cache[pno]

def page_numbers(pno):
    """[(y, value)] of question-number lines on this page, sorted by y."""
    out = []
    for t in OCR.get(pno, []):
        m = re.match(r'^(\d{1,2})\.', t['text'])
        if not m:
            continue
        v = int(m.group(1))
        if 1 <= v <= 20:
            out.append((int(t['y0']), v))
    out.sort()
    return out

def build_anchors(s):
    """Return {value: (page, y)} for a set, or raise."""
    a = DIVIDERS[s - 1]
    b = DIVIDERS[s] - 1 if s < 35 else 267
    seq = []
    for pno in range(a, b + 1):
        for (y, v) in page_numbers(pno):
            seq.append((pno, y, v))
    seq.sort()
    got = {}
    last = -1
    for (pno, y, v) in seq:
        if v <= last or v in got:
            continue          # enforce strict 1..20 monotonic reading order
        got[v] = (pno, y)
        last = v
    for v in range(1, 21):
        if v not in got and (s, v) in PATCH:
            got[v] = PATCH[(s, v)]
    missing = [v for v in range(1, 21) if v not in got]
    if missing:
        raise RuntimeError(f"set {s}: missing numbers {missing}")
    return got

def content_bottom(pno, y_from, gap=160):
    """Bottom of the question that starts at y_from on this page.

    Blanks the page footer and stops at the first vertical blank run longer than
    `gap`, so leftover page space (and the footer logo) is not swallowed.
    Then drops a trailing printed page number (a narrow, centred, isolated blob).
    """
    a = page_arr(pno)
    H, W, _ = a.shape
    g = a[:, :, :3].mean(axis=2)
    dk2 = g < 215
    dark = dk2.sum(axis=1)
    thr = max(3, int(0.004 * W))
    limit = int(H * 0.94)         # skip footer band
    ink = [y for y in range(int(y_from), limit) if dark[y] > thr]
    if not ink:
        return min(int(y_from) + 200, limit)
    last = ink[0]
    for y in ink[1:]:
        if y - last > gap:
            break
        last = y
    # trim trailing isolated narrow/centred blob(s) = printed page numbers
    for _ in range(3):
        rows = [y for y in range(int(y_from), last + 1) if dark[y] > thr]
        if not rows:
            break
        # group into text lines
        lines = [[rows[0], rows[0]]]
        for y in rows[1:]:
            if y - lines[-1][1] <= 6:
                lines[-1][1] = y
            else:
                lines.append([y, y])
        if len(lines) < 2:
            break
        y0, y1 = lines[-1]
        if y0 - lines[-2][1] < 24:          # not isolated -> part of question
            break
        band = dk2[y0:y1 + 1]
        cols = np.where(band.any(axis=0))[0]
        if len(cols) == 0:
            break
        wfrac = (cols.max() - cols.min()) / W
        cfrac = (cols.max() + cols.min()) / 2 / W
        if wfrac < 0.18 and 0.33 < cfrac < 0.67:
            last = lines[-2][1]             # drop this blob
        else:
            break
    return last

def main():
    os.makedirs(IMG_DIR, exist_ok=True)
    records = []
    problems = []
    for s in range(1, 36):
        got = build_anchors(s)
        anchors = [(v, got[v][0], got[v][1]) for v in range(1, 21)]
        anchors.sort(key=lambda x: (x[1], x[2]))
        for i, (v, pno, y) in enumerate(anchors):
            arr = page_arr(pno)
            H = arr.shape[0]
            top = max(0, y - PAD_TOP)
            if i + 1 < len(anchors) and anchors[i + 1][1] == pno:
                bottom = anchors[i + 1][2] - PAD_TOP - PAD_BOT
            else:
                bottom = content_bottom(pno, y + 40) + PAD_BOT
            bottom = min(max(bottom, top + 120), H)
            crop = arr[top:bottom]
            fname = f"s{s:02d}_q{v:02d}.png"
            Image.fromarray(crop).save(os.path.join(IMG_DIR, fname))
            h = bottom - top
            if h < 200 or h > 800:
                problems.append((fname, pno, top, bottom, h))
            records.append({
                "set": s, "qnum": v, "image": fname,
                "answer": ANSWER_KEY[s - 1][v - 1],
                "page": pno, "top": int(top), "bottom": int(bottom),
            })
    with open(os.path.join(OUT, 'questions.json'), 'w', encoding='utf-8') as f:
        json.dump(records, f, ensure_ascii=False, indent=2)
    with open(os.path.join(OUT, 'questions.csv'), 'w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=["set","qnum","image","answer","page","top","bottom"])
        w.writeheader()
        w.writerows(records)
    print("total records:", len(records))
    print("suspicious heights:", len(problems))
    for p in problems:
        print("   ", p)

if __name__ == '__main__':
    main()
