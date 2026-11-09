#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""拆分质量审计：机器初筛 + 生成人工核对表。

机器初筛（能可靠判定的几类）：
  · blank      选项图几乎是空白
  · dup        A/B/C/D 之间有两张几乎一模一样（裁到同一块图形）
  · size-spread 四个选项尺寸差异过大
  · letter?    选项图贴边有孤立小块（卷面印的 A/B/C/D 混进来了）

产出：
  qbank_out/超格行测套卷/audit_report.json
  qbank_out/超格行测套卷/audit_checks/audit_N.png（每题一行：题干 + ABCD，4 题一张）
"""
import json
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BASE = os.path.join(ROOT, 'qbank_out', '超格行测套卷')
OUT = os.path.join(BASE, 'audit_checks')


def ink(img):
    return np.asarray(img.convert('L')) < 160


def similar(a, b):
    x = np.asarray(a.convert('L').resize((72, 72), Image.LANCZOS), dtype=np.float32)
    y = np.asarray(b.convert('L').resize((72, 72), Image.LANCZOS), dtype=np.float32)
    return float(np.abs(x - y).mean() / 255.0)


def edge_blobs(m):
    """贴边孤立小块计数（疑似卷面字母）。"""
    from scipy import ndimage
    h, w = m.shape
    lab, n = ndimage.label(m, np.ones((3, 3), bool))
    if n < 2:
        return 0
    info = []
    for i, sl in enumerate(ndimage.find_objects(lab), 1):
        if sl is None:
            continue
        ys, xs = sl
        info.append((int((lab[sl] == i).sum()), ys.start, ys.stop, xs.start, xs.stop))
    big = max(a for a, *_ in info)
    cnt = 0
    for a, y0, y1, x0, x1 in info:
        if a > 0.30 * big or a < 10:
            continue
        near = (y1 >= h - 3) or (y0 <= 2) or (x1 >= w - 3) or (x0 <= 2)
        if near and (y1 - y0) <= 0.40 * h and (x1 - x0) <= 0.40 * w:
            cnt += 1
    return cnt


def main():
    os.makedirs(OUT, exist_ok=True)
    summary = json.load(open(os.path.join(BASE, 'build_summary.json'), encoding='utf-8'))
    papers = sorted(summary, key=lambda x: int(''.join(c for c in x if c.isdigit())))

    report, rows = {}, []
    for paper in papers:
        d = os.path.join(BASE, paper, 'images')
        for no in summary[paper]['opt_split']:
            sp = os.path.join(d, 'q%03d.png' % no)
            op = {k: os.path.join(d, 'q%03d_%s.png' % (no, k)) for k in 'ABCD'}
            rec = {'paper': paper, 'no': no, 'flags': []}
            if not all(os.path.exists(p) for p in op.values()):
                rec['flags'].append('missing-file')
                report['%s:%d' % (paper, no)] = rec
                continue
            stem = Image.open(sp).convert('L') if os.path.exists(sp) else None
            opts = {k: Image.open(p).convert('L') for k, p in op.items()}
            sz = {k: v.size for k, v in opts.items()}
            rec['sizes'] = {k: list(v) for k, v in sz.items()}
            rec['stem'] = list(stem.size) if stem else None

            for k, im in opts.items():
                if float(ink(im).mean()) < 0.004:
                    rec['flags'].append('%s:blank' % k)
            ws = [sz[k][0] for k in 'ABCD']
            hs = [sz[k][1] for k in 'ABCD']
            if min(ws) < 12 or min(hs) < 12:
                rec['flags'].append('tiny')
            if max(ws) > 3.0 * max(1, min(ws)) or max(hs) > 3.0 * max(1, min(hs)):
                rec['flags'].append('size-spread')
            for i, a in enumerate('ABCD'):
                for b in 'ABCD'[i + 1:]:
                    dv = similar(opts[a], opts[b])
                    if dv < 0.015:
                        rec['flags'].append('dup:%s=%s(%.3f)' % (a, b, dv))
            lb = {k: edge_blobs(ink(v)) for k, v in opts.items()}
            rec['edge_blobs'] = lb
            if sum(lb.values()) >= 2:
                rec['flags'].append('letter?%s' % '/'.join(k for k, v in lb.items() if v))
            report['%s:%d' % (paper, no)] = rec
            rows.append((paper, no, stem, opts))

    json.dump(report, open(os.path.join(BASE, 'audit_report.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)

    H, PER = 230, 4
    try:
        font = ImageFont.load_default(size=18)
    except Exception:
        font = None
    for si in range(0, len(rows), PER):
        grp = rows[si:si + PER]
        fit = lambda im: None if im is None else im.resize(
            (max(1, int(im.width * min(1.0, H / float(im.height)))),
             max(1, int(im.height * min(1.0, H / float(im.height))))), Image.LANCZOS)
        items = [(p, n, fit(s), [fit(o) for o in [opts[k] for k in 'ABCD']]) for p, n, s, opts in grp]
        W = max(110 + (s.width if s else 0) + 30 + sum(o.width + 24 for o in ops if o) + 70
                for _, _, s, ops in items)
        sheet = Image.new('L', (W, len(items) * (H + 48) + 8), 255)
        d = ImageDraw.Draw(sheet)
        y = 6
        for paper, no, s, ops in items:
            d.text((5, y + 8), '%s-%d' % (paper.replace('行测套卷', 'S'), no), fill=0, font=font)
            x = 110
            if s:
                sheet.paste(s, (x, y))
                x += s.width + 30
            d.line([x - 15, y, x - 15, y + H], fill=170)
            x += 6
            for k, o in zip('ABCD', ops):
                d.text((x, y + H + 4), k, fill=0, font=font)
                if o:
                    sheet.paste(o, (x, y))
                x += (o.width if o else 40) + 24
            d.line([0, y + H + 30, W, y + H + 30], fill=200)
            y += H + 48
        sheet.save(os.path.join(OUT, 'audit_%d.png' % (si // PER + 1)))
    print('核对表 %d 张，题 %d' % ((len(rows) + PER - 1) // PER, len(rows)))

    flagged = {k: v['flags'] for k, v in report.items() if v['flags']}
    print('=== 机器初筛可疑 %d 题' % len(flagged))
    for k, f in flagged.items():
        print(' ', k, f)


if __name__ == '__main__':
    main()
