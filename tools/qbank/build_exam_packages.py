#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""超格行测套卷（10 套）-> App 可直接导入的「试卷包」，图推等选项是图的题把选项拆成四张图。

产物：qbank_out/超格行测套卷/行测套卷N.zip，每个 zip = 试卷.xlsx + images/
（App 导入页选 zip 即可，一套一卷。）

「选项是图」的题（选项文字写的是「（见图A）」那种）现在这样处理：

    材料  = 只剩题干图形（选项那一带切掉后剩下的部分）
    选项A..D = `![A](images/q076_A.png)`，一个选项一张图

App 的选项渲染本来就认 `![说明](路径)`（MaterialRenderer.splitOption），点图即选该项，
所以选项是真图、能点、能判分，不再是一行「见图A」。

拆分办法（不依赖 OCR，纯图像处理）：
1. 把该题分到的原始位图按 PDF 坐标拼回一张画布（k=1 时直接用原图；k=4 时四个位图
   本身就是四个选项，按阅读顺序当 A-D）；
2. 优先找「四格带边框的选项条」——横框线用最长连续墨迹判定，竖分隔线切出四格，
   直接按格子裁（最常见版式，最准）；
3. 认不到格子就找四个「字母大小、四周留白」的孤立墨块，按行优先排成 A/B/C/D，
   再按字母朝图形一侧逐像素扫出该选项的图形（选项在上/下/左/右四种排法都能处理）；
4. 都没有就按列白缝硬切；
5. 四种方案里取「框住墨迹最多、彼此不重叠、不带卷面字母」的那一组；都不合格就保持
   旧样（整块图进材料 + 「见图X」文字），绝不硬凑。

跑法（走 uv）：
    uv run --with pillow --with numpy --with scipy --with openpyxl         python tools/qbank/build_exam_packages.py
    ... --only 行测套卷1            # 只做一套
    ... --probe 行测套卷1:76        # 单题调试，打印拆分细节
    ... --out <目录>                # 换输出目录（默认 qbank_out/超格行测套卷）
    ... --build-dir <tmp/build>     # raw/ img/ img_index.json 所在目录
"""
import argparse
import collections
import glob
import json
import os
import re
import shutil
import zipfile

import numpy as np
from PIL import Image
import openpyxl

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))          # tools/qbank -> 仓库根
BUILD = os.path.join(ROOT, 'tmp', 'build')             # 分页 JSON / 位图 / 图片索引所在
RAW = os.path.join(BUILD, 'raw')
IMG_DIR = os.path.join(BUILD, 'img')
OUT = os.path.join(ROOT, 'qbank_out', '超格行测套卷')

IMG_RE = re.compile(r'!\[[^\]]*\]\(\s*IMG\s*\)')
BASE_HEAD = ['题型', '题干', '材料', '图片', '答案', '解析', '知识点', '难度',
             '地区', '科目', '来源', '年份', '分值']

PAD = 8              # 裁图四周留白（画布像素）


# ------------------------------------------------------------------ 基础读入

def paper_no(p):
    return int(re.search(r'(\d+)', p).group(1))


def load_answers(paper, total_main):
    m = {}
    for sec, rngs in answers_raw[paper].items():
        off = total_main if sec == '附加题' else 0
        for r in rngs:
            a, b = r.split(':')
            s, e = map(int, a.split('-'))
            letters = b.strip()
            assert len(letters) == e - s + 1, (paper, r)
            for i, ch in enumerate(letters):
                m[s + i + off] = ch
    return m


def load_qs(paper):
    """raw/<卷>/pNN.json -> 全卷题目列表（题号排序，材料组解析好）。"""
    qs, sec = [], None
    for f in sorted(glob.glob(os.path.join(RAW, paper, 'p*.json'))):
        page = int(re.search(r'p(\d+)\.json', f).group(1))
        d = json.load(open(f, encoding='utf-8'))
        sec = d.get('section') or sec
        for q in d.get('questions', []):
            q = dict(q)
            q['_page'] = page
            q['_section'] = sec
            q['_own'] = ('material' in q and bool(q['material']))
            qs.append(q)
    qs.sort(key=lambda x: x['no'])
    # 题干里的 ![](IMG) 挪进材料
    for q in qs:
        st = q.get('stem') or ''
        if IMG_RE.search(st):
            n = len(IMG_RE.findall(st))
            q['stem'] = IMG_RE.sub('', st).strip()
            if q.get('_own'):
                q['material'] = (q.get('material') or '').rstrip() + '\n\n' + '![](IMG)' * n
            else:
                q['material'] = '![](IMG)' * n
                q['_own'] = True
    last = None
    for q in qs:
        if q.get('_own'):
            last = q.get('material')
        elif q.get('material_same'):
            q['material'] = last
        else:
            last = None
    return qs


def pair_slots(qs, paper):
    """把材料里的 ![](IMG) 占位与页面位图按页码就近配对（同 build_xlsx_packages.py）。"""
    slots = []
    for q in qs:
        if not q.get('_own'):
            continue
        k = len(IMG_RE.findall(q.get('material') or ''))
        for i in range(k):
            slots.append({'q': q['no'], 'page': q['_page'], 'k': k, 'i': i})
    imgs = []
    for pg in sorted(idx[paper], key=lambda s: int(s)):
        for it in idx[paper][pg]:
            imgs.append({'page': int(pg), 'file': it['file'], 'bbox': it['bbox']})
    m, n = len(slots), len(imgs)
    INF = 10 ** 9
    dp = [[INF] * (n + 1) for _ in range(m + 1)]
    bk = [[None] * (n + 1) for _ in range(m + 1)]
    dp[0][0] = 0
    for i in range(m + 1):
        for j in range(n + 1):
            if dp[i][j] >= INF:
                continue
            if i < m and j < n:
                c = dp[i][j] + abs(slots[i]['page'] - imgs[j]['page'])
                if c < dp[i + 1][j + 1]:
                    dp[i + 1][j + 1] = c
                    bk[i + 1][j + 1] = 'M'
            if j < n:
                c = dp[i][j] + 1.6
                if c < dp[i][j + 1]:
                    dp[i][j + 1] = c
                    bk[i][j + 1] = 'S'
    i, j, pair = m, n, {}
    while i > 0 or j > 0:
        b = bk[i][j]
        if b == 'M':
            pair[i - 1] = j - 1
            i -= 1
            j -= 1
        elif b == 'S':
            j -= 1
        else:
            i -= 1
    per_q = collections.defaultdict(list)
    for si, slot in enumerate(slots):
        if si in pair:
            per_q[slot['q']].append(dict(imgs[pair[si]], k=slot['k'], i=slot['i']))
    skipped = [imgs[jj]['file'] for jj in range(n) if jj not in set(pair.values())]
    return per_q, skipped


# ------------------------------------------------------------------ 画布

def item_image(paper, item):
    return Image.open(os.path.join(IMG_DIR, paper, item['file'])).convert('L')


def build_canvas(paper, items):
    """按 PDF 坐标把该题的位图拼回一张画布，返回 (L 模式图, 每块在图上的位置)。"""
    if len(items) == 1:
        return item_image(paper, items[0]), [(0, 0, items[0])]
    # 用「原始像素 / PDF 点」估一个不缩小倍率，避免糊
    scales = []
    for it in items:
        im = item_image(paper, it)
        bw = max(1e-6, it['bbox'][2] - it['bbox'][0])
        scales.append(im.width / bw)
    S = max(1.0, float(np.median(scales)))
    x0 = min(it['bbox'][0] for it in items)
    y0 = min(it['bbox'][1] for it in items)
    W = int((max(it['bbox'][2] for it in items) - x0) * S) + 2 * PAD
    H = int((max(it['bbox'][3] for it in items) - y0) * S) + 2 * PAD
    canvas = Image.new('L', (W, H), 255)
    placed = []
    for it in items:
        im = item_image(paper, it)
        tw = max(1, int((it['bbox'][2] - it['bbox'][0]) * S))
        th = max(1, int((it['bbox'][3] - it['bbox'][1]) * S))
        px = int((it['bbox'][0] - x0) * S) + PAD
        py = int((it['bbox'][1] - y0) * S) + PAD
        canvas.paste(im.resize((tw, th), Image.LANCZOS), (px, py))
        placed.append((px, py, it))
    return canvas, placed


# ------------------------------------------------------------------ 找字母

def letter_candidates(mask):
    from scipy import ndimage
    lbl, n = ndimage.label(mask, structure=np.ones((3, 3), dtype=int))
    H, W = mask.shape
    cands = []
    for i, sl in enumerate(ndimage.find_objects(lbl), start=1):
        if sl is None:
            continue
        ys, xs = sl
        h = ys.stop - ys.start
        w = xs.stop - xs.start
        if not (0.015 * H <= h <= 0.16 * H):
            continue
        if not (0.008 * W <= w <= 0.11 * W):
            continue
        if not (0.35 <= w / h <= 2.0):
            continue
        area = int((lbl[sl] == i).sum())
        if area > 0.004 * H * W:
            continue
        fill = area / float(w * h)
        if not (0.12 <= fill <= 0.70):
            continue
        y0, y1, x0, x1 = ys.start, ys.stop, xs.start, xs.stop
        # 字母四周是空白：算一圈「邻域墨迹占比」，图形里的小方块/圆点这一圈会很脏
        d = max(3, int(0.7 * h))
        ry0, ry1 = max(0, y0 - d), min(H, y1 + d)
        rx0, rx1 = max(0, x0 - d), min(W, x1 + d)
        ring = mask[ry0:ry1, rx0:rx1].copy()
        ring[y0 - ry0:y1 - ry0, x0 - rx0:x1 - rx0] = False
        ring_ratio = float(ring.sum()) / max(1, ring.size)
        cands.append(dict(y0=y0, y1=y1, x0=x0, x1=x1,
                          h=h, w=w, fill=fill, ring=ring_ratio,
                          cx=(x0 + x1) / 2.0, cy=(y0 + y1) / 2.0))
    return cands, lbl


def pick_four_letters(cands, H, W):
    """从候选墨块里挑出排成「1×4」或「2×2」的四个字母，返回按 A,B,C,D 排好的列表。"""
    import itertools
    if len(cands) < 4:
        return None, 'cand<%d' % len(cands)
    pool = cands
    if len(pool) > 9:                                  # 太多了，先按高度聚类取最大簇
        best = []
        for c in pool:
            grp = [d for d in pool if abs(d['h'] - c['h']) <= 0.12 * c['h']]
            if len(grp) > len(best):
                best = grp
        pool = best
    if len(pool) > 16:                                 # 再砍一刀，组合数别炸
        pool = sorted(pool, key=lambda d: d['ring'])[:16]
    best, best_score = None, None
    for combo in itertools.combinations(pool, 4):
        rows = []
        for c in sorted(combo, key=lambda d: d['cy']):
            for r in rows:
                if abs(c['cy'] - r[0]['cy']) <= 0.45 * c['h']:
                    r.append(c)
                    break
            else:
                rows.append([c])
        if sorted(len(r) for r in rows) not in ([4], [2, 2]):
            continue
        rows.sort(key=lambda r: min(c['cy'] for c in r))
        order = []
        for r in rows:
            order.extend(sorted(r, key=lambda d: d['cx']))
        hs = [c['h'] for c in order]
        if max(hs) - min(hs) > 0.35 * max(hs):
            continue
        pitchs = []
        for r in rows:
            r = sorted(r, key=lambda d: d['cx'])
            pitchs += [r[i + 1]['cx'] - r[i]['cx'] for i in range(len(r) - 1)]
        if not pitchs:
            continue
        pitch = float(np.mean(pitchs))
        if pitch < 0.06 * W:
            continue
        cv = float(np.std(pitchs) / pitch) if len(pitchs) > 1 else 0.0
        if cv > 0.22:
            continue
        # 同一行里还有别的候选（字母那一行通常干干净净），罚分
        extra = 0
        for r in rows:
            lo = min(c['cy'] for c in r) - 0.5 * float(np.mean(hs))
            hi = max(c['cy'] for c in r) + 0.5 * float(np.mean(hs))
            for c in cands:
                if lo <= c['cy'] <= hi and c not in r:
                    extra += 1
        score = (cv
                 + 0.5 * float(np.std(hs) / np.mean(hs))
                 + 0.6 * (float(np.mean(hs)) / H)
                 + 2.0 * float(np.mean([c['ring'] for c in order]))
                 + 0.05 * extra
                 + 1.2 * max(0.0, (0.12 * W - pitch) / (0.12 * W))
                 - 0.15 * len(combo))
        if best_score is None or score < best_score:
            best, best_score = order, score
    if best is None:
        return None, 'no-grid(候选%d)' % len(pool)
    return best, 'ok'


# ------------------------------------------------------------------ 裁选项

def ink_runs_1d(profile):
    """布尔序列 -> [(start, stop)) 的墨迹段。"""
    runs, i, n = [], 0, len(profile)
    while i < n:
        if profile[i]:
            j = i
            while j < n and profile[j]:
                j += 1
            runs.append((i, j))
            i = j
        else:
            i += 1
    return runs


def content_bbox(sub, line_frac=0.92):
    """在子图里算墨迹包围盒，先剔掉贯穿整幅的框线（选项常画在带边框的格子里）。"""
    if not sub.any():
        return None
    h, w = sub.shape
    rows = sub.mean(axis=1)
    cols = sub.mean(axis=0)
    m = sub.copy()
    for y in np.where(rows >= line_frac)[0]:
        m[y, :] = False
    for x in np.where(cols >= line_frac)[0]:
        m[:, x] = False
    if not m.any():
        m = sub
    ys, xs = np.where(m)
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1


def probe_dirs(mask, letters, pitch):
    """四个字母四个方向各探一块墨迹，合计最多的那一侧就是图形所在的一侧。

    同一道题的四个选项总是排在同一侧（上/下/左/右），按整组合计比逐个字母猜稳。
    """
    H, W = mask.shape
    total = {'up': 0, 'down': 0, 'left': 0, 'right': 0}
    for letter in letters:
        lh = letter['h']
        for d in total:
            if d == 'up':
                x0, x1 = int(max(0, letter['cx'] - 0.45 * pitch)), int(min(W, letter['cx'] + 0.45 * pitch))
                y0, y1 = int(max(0, letter['y0'] - 1.8 * pitch)), int(max(0, letter['y0'] - 0.15 * lh))
            elif d == 'down':
                x0, x1 = int(max(0, letter['cx'] - 0.45 * pitch)), int(min(W, letter['cx'] + 0.45 * pitch))
                y0, y1 = int(min(H, letter['y1'] + 0.15 * lh)), int(min(H, letter['y1'] + 1.8 * pitch))
            elif d == 'left':
                y0, y1 = int(max(0, letter['cy'] - 0.45 * pitch)), int(min(H, letter['cy'] + 0.45 * pitch))
                x0, x1 = int(max(0, letter['x0'] - 1.8 * pitch)), int(max(0, letter['x0'] - 0.15 * lh))
            else:
                y0, y1 = int(max(0, letter['cy'] - 0.45 * pitch)), int(min(H, letter['cy'] + 0.45 * pitch))
                x0, x1 = int(min(W, letter['x1'] + 0.15 * lh)), int(min(W, letter['x1'] + 1.8 * pitch))
            if y1 > y0 and x1 > x0:
                total[d] += int(mask[y0:y1, x0:x1].sum())
    return max(total, key=total.get)


def crop_option(mask, letter, pitch, gap_th, thin_th, direction):
    """返回该字母对应选项的 (x0,y0,x1,y1) 或 None。"""
    H, W = mask.shape
    half = pitch / 2.0
    if direction in ('up', 'down', 'left', 'right'):
        pass
    else:
        direction = 'up'

    vertical = direction in ('up', 'down')
    if vertical:
        x0 = int(max(0, letter['cx'] - half))
        x1 = int(min(W, letter['cx'] + half))
        prof = mask[:, x0:x1].any(axis=1)
    else:
        y0 = int(max(0, letter['cy'] - half))
        y1 = int(min(H, letter['cy'] + half))
        prof = mask[y0:y1, :].any(axis=0)

    runs = ink_runs_1d(prof)
    if not runs:
        return None
    # 先去掉字母自己那一段，免得把字母当选项
    if direction == 'up':
        runs = [r for r in runs if r[1] <= letter['y0']]
    elif direction == 'down':
        runs = [r for r in runs if r[0] >= letter['y1']]
    elif direction == 'left':
        runs = [r for r in runs if r[1] <= letter['x0']]
    else:
        runs = [r for r in runs if r[0] >= letter['x1']]
    if not runs:
        return None
    forward = direction in ('up', 'left')
    # 由近到远：向上/向左时 y（或 x）大的先看
    seq = sorted(runs, key=(lambda r: -r[1]) if forward else (lambda r: r[0]))
    start = None
    for r in seq:
        thick = r[1] - r[0]
        if thick >= thin_th:                  # 跳过 1~4px 的框线
            start = r
            break
    if start is None:
        return None
    lo, hi = start
    cur = start
    while True:
        nxt = None
        if forward:
            cand = [r for r in runs if r[1] <= cur[0]]
            nxt = max(cand, key=lambda r: r[1]) if cand else None
            gap = (cur[0] - nxt[1]) if nxt else None
        else:
            cand = [r for r in runs if r[0] >= cur[1]]
            nxt = min(cand, key=lambda r: r[0]) if cand else None
            gap = (nxt[0] - cur[1]) if nxt else None
        if nxt is None or gap is None or gap >= gap_th:
            break
        lo, hi = min(lo, nxt[0]), max(hi, nxt[1])
        cur = nxt

    if vertical:
        y0, y1 = lo, hi
        sub = mask[y0:y1, x0:x1]
    else:
        x0, x1 = lo, hi
        sub = mask[y0:y1, x0:x1]
    if sub.size == 0 or not sub.any():
        return None
    bb = content_bbox(sub)
    if bb is None:
        return None
    box = (x0 + bb[0], y0 + bb[1], x0 + bb[2], y0 + bb[3])
    if box[2] - box[0] < 6 or box[3] - box[1] < 6:
        return None
    return box


def build_stem(canvas, boxes, letters, pitch, band=None):
    """题干图形：选项那一带以外的部分拼回来。没内容返回 None。"""
    W, H = canvas.size
    ux0 = min(b[0] for b in boxes.values())
    ux1 = max(b[2] for b in boxes.values())
    uy0 = min(b[1] for b in boxes.values())
    uy1 = max(b[3] for b in boxes.values())
    ly1 = max([c['y1'] for c in letters], default=uy1)

    mask = np.asarray(canvas) < 128
    if band:
        band_y0 = max(0, band[0] - 4)
        band_y1 = min(H, band[1] + 6)
    else:
        long_rows = np.where(mask.mean(axis=1) > 0.5)[0]
        top_c = [y for y in long_rows if y <= uy0 - 2 and uy0 - y <= 0.6 * pitch]
        bot_c = [y for y in long_rows if uy1 + 2 <= y <= ly1]
        band_y0 = max(0, (max(top_c) - 4) if top_c else uy0 - 14)
        band_y1 = min(H, max(uy1 + 10, ly1 + 8, (min(bot_c) + 5) if bot_c else 0))

    out = Image.new('L', (W, H), 255)
    if band_y0 > 0:
        out.paste(canvas.crop((0, 0, W, band_y0)), (0, 0))
    for x0, x1 in ((0, max(0, ux0 - 12)), (min(W, ux1 + 12), W)):
        if x1 - x0 < 12:
            continue
        part = canvas.crop((x0, band_y0, x1, band_y1))
        pm = np.asarray(part) < 128
        pm2 = pm.copy()
        for y in np.where(pm.mean(axis=1) > 0.6)[0]:
            pm2[y, :] = False
        for x in np.where(pm.mean(axis=0) > 0.6)[0]:
            pm2[:, x] = False
        if pm2.sum() < max(150, 0.002 * pm2.size):
            continue                      # 只剩格子边框，当它没有
        arr = np.asarray(part).copy()
        for y in np.where(pm.mean(axis=1) > 0.6)[0]:
            arr[y, :] = 255
        for x in np.where(pm.mean(axis=0) > 0.6)[0]:
            arr[:, x] = 255
        out.paste(Image.fromarray(arr), (x0, band_y0))
    return crop_tight(out, pad=6)


def drop_orphan_lines(img, min_frac=0.5, gap=14, keep_frac=0.02):
    """擦掉「孤零零的贯穿长直线」：选项格子边框常在题干图形之外剩一两条。
    判断标准——线的上下（左右）十几像素内基本没有别的墨迹，就当它是空框线。"""
    arr = np.asarray(img) < 128
    H, W = arr.shape
    out = arr.copy()

    def empty_near(seg, axis, pos):
        s0, s1 = seg
        if axis == 'row':
            a = arr[max(0, pos - gap):max(0, pos - 3), s0:s1]
            b = arr[min(H, pos + 4):min(H, pos + gap), s0:s1]
        else:
            a = arr[s0:s1, max(0, pos - gap):max(0, pos - 3)]
            b = arr[s0:s1, min(W, pos + 4):min(W, pos + gap)]
        n = max(1, (a.shape[0] * a.shape[1]) + (b.shape[0] * b.shape[1]))
        return (int(a.sum()) + int(b.sum())) < keep_frac * n

    for y in np.where(arr.mean(axis=1) > min_frac)[0]:
        xs = np.where(arr[y])[0]
        if len(xs) and empty_near((int(xs.min()), int(xs.max()) + 1), 'row', int(y)):
            out[y, xs.min():xs.max() + 1] = False
    for x in np.where(arr.mean(axis=0) > min_frac)[0]:
        ys = np.where(arr[:, x])[0]
        if len(ys) and empty_near((int(ys.min()), int(ys.max()) + 1), 'col', int(x)):
            out[ys.min():ys.max() + 1, x] = False
    if not out.any():
        return img
    return Image.fromarray(np.where(out, 0, 255).astype(np.uint8))


def crop_tight(img, pad=4, thresh=250):
    arr = np.asarray(img)
    m = arr < thresh
    if not m.any():
        return img
    ys, xs = np.where(m)
    x0 = max(0, int(xs.min()) - pad)
    y0 = max(0, int(ys.min()) - pad)
    x1 = min(img.width, int(xs.max()) + 1 + pad)
    y1 = min(img.height, int(ys.max()) + 1 + pad)
    return img.crop((x0, y0, x1, y1))


def _max_run(line):
    """一维布尔数组里最长连续 True 的长度。"""
    if not line.any():
        return 0
    white = np.flatnonzero(~line)
    if white.size == 0:
        return int(len(line))
    gaps = np.diff(np.concatenate(([-1], white, [len(line)]))) - 1
    return int(gaps.max())


def trim_edge_lines(img, frac=0.93):
    """再削掉紧贴边上的残余边框线（细线占满整列/整行）。"""
    a = np.asarray(img) < 128
    h, w = a.shape
    x0, x1, y0, y1 = 0, w, 0, h
    while x0 < x1 - 4 and a[:, x0].mean() > frac:
        x0 += 1
    while x1 - 4 > x0 and a[:, x1 - 1].mean() > frac:
        x1 -= 1
    while y0 < y1 - 4 and a[y0, :].mean() > frac:
        y0 += 1
    while y1 - 4 > y0 and a[y1 - 1, :].mean() > frac:
        y1 -= 1
    if (x0, y0, x1, y1) == (0, 0, w, h):
        return img
    return img.crop((x0, y0, x1, y1))


def trim_edge_lines(img, frac=0.93):
    """再削掉紧贴边上的残余边框线（细线占满整列/整行）。"""
    a = np.asarray(img) < 128
    h, w = a.shape
    x0, x1, y0, y1 = 0, w, 0, h
    while x0 < x1 - 4 and a[:, x0].mean() > frac:
        x0 += 1
    while x1 - 4 > x0 and a[:, x1 - 1].mean() > frac:
        x1 -= 1
    while y0 < y1 - 4 and a[y0, :].mean() > frac:
        y0 += 1
    while y1 - 4 > y0 and a[y1 - 1, :].mean() > frac:
        y1 -= 1
    if (x0, y0, x1, y1) == (0, 0, w, h):
        return img
    return img.crop((x0, y0, x1, y1))


def split_boxed_row(mask):
    """按「四格带边框的选项条」拆：找出整片边框里被竖线分成四格的那一条。

    图形推理卷面最常见的版式就是「选项四格一行、外面套一个框」，直接按格子切最准。
    框线用「最长连续墨迹」判，别用整行墨迹占比——图形里密集的点阵会被误判成框线。
    返回 ({letter: box}, strip_top, strip_bottom) 或 None。
    """
    H, W = mask.shape
    raw = [y for y in range(H) if _max_run(mask[y]) > 0.55 * W]
    if len(raw) < 2:
        return None
    groups = []
    for y in raw:
        if groups and y - groups[-1][-1] <= 3:
            groups[-1].append(y)
        else:
            groups.append([y])
    ys = [int(np.mean(g)) for g in groups]

    best = None
    for i1, t in enumerate(ys):
        for b in ys[i1 + 1:]:
            hgt = b - t
            if hgt < 0.06 * H or hgt > 0.55 * H:
                continue
            cols = []
            for x in range(W):
                if _max_run(mask[t + 2:b - 1, x]) > 0.85 * hgt:
                    cols.append(x)
            if len(cols) < 5:
                continue
            vg = []
            for x in cols:
                if vg and x - vg[-1][-1] <= 3:
                    vg[-1].append(x)
                else:
                    vg.append([x])
            xs = [int(np.mean(g)) for g in vg]
            # 格子内区域：从这条竖线的右沿到下一条竖线的左沿，把分隔线整条排掉
            spans = [(vg[k][-1] + 2, vg[k + 1][0] - 1) for k in range(len(vg) - 1)]
            cells = [(a, b2) for a, b2 in spans if b2 - a > 0.06 * W]
            if len(cells) != 4:
                continue
            ws = [b2 - a for a, b2 in cells]
            if max(ws) > 1.5 * min(ws):
                continue
            boxes, ok = {}, True
            for letter, (a, b2) in zip('ABCD', cells):
                bb = content_bbox(mask[t + 2:b - 1, a:b2])
                if bb is None:
                    ok = False
                    break
                bw, bh = bb[2] - bb[0], bb[3] - bb[1]
                if bw < 0.03 * W or bh < 0.04 * H:      # 空格子/只剩零星几笔，不是选项
                    ok = False
                    break
                boxes[letter] = (a + bb[0], t + 2 + bb[1], a + bb[2], t + 2 + bb[3])
            if not ok:
                continue
            score = float(np.std(ws) / np.mean(ws)) - 0.6 * (t / float(H))
            if best is None or score < best[0]:
                best = (score, boxes, t, b)
    if best is None:
        return None
    return best[1], best[2], best[3]


def split_by_columns(mask):
    """兜底拆法：卷面字母太小/糊掉时，按列白缝把选项区切成 4 列。

    只对「选项横排、列之间有空隙」的版式有效（条形图、折线图那种）。
    返回 {'A': box, ...} 或 None。
    """
    H, W = mask.shape
    rows = mask.any(axis=1)
    bands, y = [], 0
    while y < H:
        if rows[y]:
            y0 = y
            while y < H and rows[y]:
                y += 1
            bands.append((y0, y))
        else:
            y += 1
    if len(bands) < 2:
        return None
    y_end = bands[-1][0] if (bands[-1][1] - bands[-1][0]) < 0.22 * H else H
    sub = mask[:y_end]
    colink = sub.any(axis=0)
    # 找贯穿的白缝（内部）
    gaps, x = [], 0
    while x < W:
        if not colink[x]:
            x0 = x
            while x < W and not colink[x]:
                x += 1
            if x0 > 0 and x < W:
                gaps.append((x0, x))
        else:
            x += 1
    if len(gaps) < 3:
        return None
    import itertools
    best = None
    for pick in itertools.combinations(gaps, 3):
        edges = [0] + [(a + b) // 2 for a, b in pick] + [W]
        cells = [(edges[i], edges[i + 1]) for i in range(4)]
        ws = [c[1] - c[0] for c in cells]
        if min(ws) < 0.12 * W:
            continue
        score = float(np.std(ws) / np.mean(ws))
        if best is None or score < best[0]:
            best = (score, cells)
    if best is None:
        return None
    cells = best[1]
    boxes = {}
    for letter, (x0, x1) in zip('ABCD', cells):
        c0, c1 = max(0, x0 - 2), min(W, x1 + 2)
        sub2 = mask[:y_end, c0:c1]
        bb = content_bbox(sub2)
        if bb is None:
            return None
        boxes[letter] = (c0 + bb[0], bb[1], c0 + bb[2], bb[3])
    return boxes


def split_question(paper, items, no, img_out_dir, stem_name):
    """拆分一道「选项是图」的题。

    返回 (stem_path|None, {letter: path}|None, info)
    """
    canvas, placed = build_canvas(paper, items)
    arr = np.asarray(canvas.convert('L'))
    mask = arr < 128
    info = {'k': len(items), 'canvas': list(canvas.size)}

    letters, why = None, ''
    if len(items) == 4:
        # 四个独立位图 = 四个选项（卷面 2×2 排布，字母在 PDF 里是文本、不在位图内）：按阅读顺序当 A-D
        opts = {}
        # 直接按位图裁（resize 前的原图更清晰）
        for letter, (px, py, it) in zip('ABCD', placed):
            bw = int((it['bbox'][2] - it['bbox'][0]) * 1)
            im = item_image(paper, it)
            path = os.path.join(img_out_dir, '%s_%s.png' % (stem_name, letter))
            trim_edge_lines(crop_tight(im)).save(path, optimize=True)
            opts[letter] = path
        info['mode'] = 'k4-positional'
        return None, opts, info

    if len(items) not in (1, 4):
        # 2~3 块位图拼起来的题：卷面本身被切成了几段，按字母切会把一个选项切成两半，不做拆分
        info['mode'] = 'fail:k=%d 多块位图' % len(items)
        return None, None, info

    if len(items) not in (1, 4):
        # 2~3 块位图拼起来的题：卷面本身被切成了几段，按字母切会把一个选项切成两半，不做拆分
        info['mode'] = 'fail:k=%d 多块位图' % len(items)
        return None, None, info

    cands, lbl = letter_candidates(mask)
    letters, why = pick_four_letters(cands, mask.shape[0], mask.shape[1])
    info['letters'] = len(cands)

    H, W = mask.shape

    def boxes_ok(bx):
        if len(bx) != 4:
            return False
        vals = list(bx.values())
        for i in range(4):
            for j in range(i + 1, 4):
                ox = min(vals[i][2], vals[j][2]) - max(vals[i][0], vals[j][0])
                oy = min(vals[i][3], vals[j][3]) - max(vals[i][1], vals[j][1])
                if ox > 0 and oy > 0:          # 两个选项框明显重叠 -> 切错了
                    area = ox * oy
                    small = min((vals[i][2] - vals[i][0]) * (vals[i][3] - vals[i][1]),
                                (vals[j][2] - vals[j][0]) * (vals[j][3] - vals[j][1]))
                    if area > 0.12 * max(1, small):
                        return False
        vals = list(bx.values())
        for i in range(4):
            for j in range(i + 1, 4):
                ox = min(vals[i][2], vals[j][2]) - max(vals[i][0], vals[j][0])
                oy = min(vals[i][3], vals[j][3]) - max(vals[i][1], vals[j][1])
                if ox > 0 and oy > 0:          # 两个选项框明显重叠 -> 切错了
                    area = ox * oy
                    small = min((vals[i][2] - vals[i][0]) * (vals[i][3] - vals[i][1]),
                                (vals[j][2] - vals[j][0]) * (vals[j][3] - vals[j][1]))
                    if area > 0.12 * max(1, small):
                        return False
        ws = [b[2] - b[0] for b in bx.values()]
        hs = [b[3] - b[1] for b in bx.values()]
        if min(ws) < 8 or min(hs) < 8:
            return False
        if max(ws) > 3.2 * min(ws) or max(hs) > 3.2 * min(hs):
            return False
        return True

    plans = []            # (方案名, boxes, band)
    boxed = split_boxed_row(mask)
    if boxed:
        plans.append(('boxed', boxed[0], (boxed[1], boxed[2])))

    if letters:
        rows = []
        for c in sorted(letters, key=lambda d: d['cy']):
            for r in rows:
                if abs(c['cy'] - r[0]['cy']) <= 0.45 * c['h']:
                    r.append(c)
                    break
            else:
                rows.append([c])
        rows.sort(key=lambda r: float(np.mean([c['cy'] for c in r])))
        info['rows'] = [len(r) for r in rows]
        row0 = sorted(rows[0], key=lambda d: d['cx'])
        pitch = float(np.mean([row0[i + 1]['cx'] - row0[i]['cx'] for i in range(len(row0) - 1)]))
        gap_th = max(6, int(0.5 * float(np.mean([c['h'] for c in letters]))))
        thin_th = max(4, int(0.25 * float(np.mean([c['h'] for c in letters]))))
        info['pitch'] = round(pitch, 1)

        # 先逐个字母自己判方向（2×2 版式常用），再试整组统一方向
        gdir = probe_dirs(mask, letters, pitch)
        for tag, use_global in (('letters-mixed', False), ('letters-global', True)):
            bx, used = {}, []
            for letter, c in zip('ABCD', letters):
                d = gdir if use_global else probe_dirs(mask, [c], pitch)
                used.append(d)
                box = crop_option(mask, c, pitch, gap_th, thin_th, d)
                if box:
                    bx[letter] = box
            if boxes_ok(bx):
                plans.append((tag, bx, None))
                info['dirs'] = used

    alt = split_by_columns(mask)
    if alt and boxes_ok(alt):
        plans.append(('columns', alt, None))

    def coverage(bx):
        tot = float(mask.sum()) or 1.0
        inside = 0.0
        for b in bx.values():
            inside += float(mask[b[1]:b[3], b[0]:b[2]].sum())
        return inside / tot

    def hit_letter(bx):
        # 裁出来的选项里如果带着卷面印的字母，说明切偏了（字母该在图形外面）
        for b in bx.values():
            for c in letters or []:
                ox = min(b[2], c['x1']) - max(b[0], c['x0'])
                oy = min(b[3], c['y1']) - max(b[1], c['y0'])
                if ox > 2 and oy > 2:
                    return True
        return False

    two_by_two = bool(letters) and info.get('rows') == [2, 2]
    valid = [p for p in plans if boxes_ok(p[1])
             and not (p[0].startswith('letters') and letters and hit_letter(p[1]))
             and not (p[0].startswith('letters') and two_by_two)]
    chosen = max(valid, key=lambda p: coverage(p[1]) + (0.02 if p[0] == 'boxed' else 0)) if valid else None
    info['cover'] = {p[0]: round(coverage(p[1]), 3) for p in valid}
    if chosen is None:
        info['mode'] = 'fail:' + (why if not letters else 'no-valid-plan')
        info['plans'] = [(p[0], len(p[1])) for p in plans]
        return None, None, info
    info['via'], boxes, band = chosen
    letters = letters if info['via'].startswith('letters') else []
    pitch = float(np.mean([b[2] - b[0] for b in boxes.values()])) or 1.0

    opts = {}
    for letter in 'ABCD':
        b = boxes[letter]
        im = canvas.crop(b)
        path = os.path.join(img_out_dir, '%s_%s.png' % (stem_name, letter))
        trim_edge_lines(crop_tight(im)).save(path, optimize=True)
        opts[letter] = path

    # 题干图形 = 画布上「选项那一带」以外的部分：
    #   · 带子上方的整幅（题目序列等）
    #   · 带子左右两侧，且剥掉格子边框后确实还有内容（立体图常与选项并排）
    # 直接按区域切出来再拼，比在整幅图上涂白干净——不会剩下空框线。
    stem = build_stem(canvas, boxes, letters, pitch, band)
    if stem is None or np.asarray(stem).astype(np.int16).__lt__(200).sum() < 120:
        stem_path = None
    else:
        stem_path = os.path.join(img_out_dir, '%s.png' % stem_name)
        stem.save(stem_path, optimize=True)
    info['mode'] = 'ok'
    return stem_path, opts, info


# ------------------------------------------------------------------ 主流程

idx = json.load(open(os.path.join(BUILD, 'img_index.json'), encoding='utf-8'))
answers_raw = json.load(open(os.path.join(BUILD, 'answers_raw.json'), encoding='utf-8'))


def drop_md_table(text):
    lines = text.split('\n')
    out, i, dropped = [], 0, False
    while i < len(lines):
        ln = lines[i]
        nxt = lines[i + 1] if i + 1 < len(lines) else ''
        if '|' in ln and re.fullmatch(r'[\s|:\-]+', nxt or '') and '-' in (nxt or ''):
            j = i + 1
            while j < len(lines) and '|' in lines[j]:
                j += 1
            i = j
            dropped = True
            continue
        out.append(ln)
        i += 1
    return '\n'.join(out), dropped


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--only', help='只处理某一套（卷名）')
    ap.add_argument('--probe', help='调试单题：卷名:题号，打印拆分细节')
    ap.add_argument('--build-dir', help='raw/ img/ img_index.json 所在目录')
    ap.add_argument('--out', help='产物目录')
    args = ap.parse_args()

    global BUILD, RAW, IMG_DIR, OUT, idx, answers_raw
    if args.build_dir:
        BUILD = os.path.abspath(args.build_dir)
        RAW = os.path.join(BUILD, 'raw')
        IMG_DIR = os.path.join(BUILD, 'img')
        idx = json.load(open(os.path.join(BUILD, 'img_index.json'), encoding='utf-8'))
        answers_raw = json.load(open(os.path.join(BUILD, 'answers_raw.json'), encoding='utf-8'))
    if args.out:
        OUT = os.path.abspath(args.out)
    os.makedirs(OUT, exist_ok=True)
    summary = {}
    probe = None
    if args.probe:
        pn, qno = args.probe.split(':')
        probe = (pn, int(qno))
        args.only = pn
    probe = None
    if args.probe:
        pn, qno = args.probe.split(':')
        probe = (pn, int(qno))
        args.only = pn
    papers = sorted(idx.keys(), key=paper_no)
    if args.only:
        papers = [p for p in papers if p == args.only]

    for paper in papers:
        qs = load_qs(paper)
        per_q, skipped = pair_slots(qs, paper)
        total_main = max([q['no'] for q in qs if q['no'] <= 130] or [max(q['no'] for q in qs)])
        ans = load_answers(paper, 130 if any(q['no'] > 130 for q in qs) else total_main)

        pdir = os.path.join(OUT, paper)
        if os.path.isdir(pdir):
            shutil.rmtree(pdir)
        os.makedirs(os.path.join(pdir, 'images'), exist_ok=True)

        # 材料里「既有图又有 md 表」的，把表删掉，避免 App 里重复展示
        dropped = []
        for q in qs:
            if q.get('_own'):
                mat = q.get('material') or ''
                if IMG_RE.search(mat) and '|' in mat:
                    mat2, d0 = drop_md_table(mat)
                    if d0:
                        q['material'] = mat2
                        dropped.append(q['no'])

        out_qs, opt_split, opt_fail, opt_info = [], [], [], []
        for q in qs:
            mat = q.get('material') or ''
            opts = {k: str(v).strip() for k, v in (q.get('options') or {}).items()}
            all_img_opts = bool(opts) and all('见图' in v for v in opts.values())
            stem_img = None
            items = per_q.get(q['no'], [])

            if all_img_opts and items:
                stem_name = 'q%03d' % q['no']
                stem_path, opt_imgs, info = split_question(
                    paper, items, q['no'], os.path.join(pdir, 'images'), stem_name)
                if probe and probe[1] == q['no']:
                    print('PROBE', json.dumps(info, ensure_ascii=False))
                if probe and probe[1] == q['no']:
                    print('PROBE', json.dumps(info, ensure_ascii=False))
                if opt_imgs:
                    mat = IMG_RE.sub('', mat).strip()
                    if stem_path:
                        mat = (mat.rstrip() + '\n\n![题干图形](images/%s.png)' % stem_name).strip()
                    opts = {k: '![%s](images/%s_%s.png)' % (k, stem_name, k) for k in 'ABCD'}
                    opt_split.append(q['no'])
                    opt_info.append(dict(info, no=q['no']))
                else:
                    opt_fail.append({'no': q['no'], 'info': info})
                    # 拆不动：维持旧样（整块图进材料 + 「见图X」文字）
                    files = [it['file'] for it in items]
                    if len(files) == 1:
                        src = os.path.join(IMG_DIR, paper, files[0])
                        dst = os.path.join(pdir, 'images', '%s.png' % stem_name)
                        shutil.copyfile(src, dst)
                        mat = IMG_RE.sub('', mat).strip()
                        mat = (mat.rstrip() + '\n\n![题目配图](images/%s.png)' % stem_name).strip()
                    else:
                        missing = []
                        for it in items:
                            dst = os.path.join(pdir, 'images', it['file'])
                            if not os.path.exists(dst):
                                shutil.copyfile(os.path.join(IMG_DIR, paper, it['file']), dst)
                            missing.append('![配图](images/%s)' % it['file'])
                        mat = IMG_RE.sub('', mat).strip()
                        mat = (mat.rstrip() + '\n\n' + '\n\n'.join(missing)).strip()
            elif IMG_RE.search(mat) and items:
                # 普通配图题：沿用旧口径，整块图进材料
                files = [it['file'] for it in items]
                refs = []
                for it in items:
                    dst = os.path.join(pdir, 'images', it['file'])
                    shutil.copyfile(os.path.join(IMG_DIR, paper, it['file']), dst)
                    refs.append('![配图](images/%s)' % it['file'])
                mat = (IMG_RE.sub('', mat).strip() + '\n\n' + '\n\n'.join(refs)).strip()

            mat = re.sub(r'\n{3,}', '\n\n', mat).strip()
            ans_letters = re.findall(r'[A-Ha-h]', str(ans.get(q['no'], '')).upper())
            qtype = '多选' if len(set(ans_letters)) > 1 else '单选'
            item = {
                'no': q['no'],
                'type': qtype,
                'subject': q.get('_section') or '',
                'stem': re.sub(r'[ \t]+', ' ', q.get('stem') or '').strip(),
                'options': opts,
                'answer': ans.get(q['no'], ''),
                'material': mat,
                'score': 1,
                'source': '2027超格公考套卷实战班·%s' % paper,
                'region': '全国',
                'year': 2027,
            }
            out_qs.append(item)

        # ---- 写 试卷.xlsx ----
        opt_keys = sorted({k for q in out_qs for k in q['options']} | set('ABCD'))
        header = ['题型', '题干', '材料', '图片'] + ['选项%s' % k for k in opt_keys] + BASE_HEAD[4:]
        wb = openpyxl.Workbook()
        ws = wb.active
        ws.title = '试卷'
        ws.append(header)
        for q in out_qs:
            ws.append(
                [q['type'], q['stem'], q['material'], '']
                + [q['options'].get(k, '') for k in opt_keys]
                + [q['answer'], '', '', '', q['region'], q['subject'], q['source'], q['year'], q['score']]
            )
        xlsx_path = os.path.join(pdir, '试卷.xlsx')
        wb.save(xlsx_path)

        zpath = os.path.join(OUT, '%s.zip' % paper)
        if os.path.exists(zpath):
            os.remove(zpath)
        with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED) as z:
            z.write(xlsx_path, '试卷.xlsx')
            for fn in sorted(os.listdir(os.path.join(pdir, 'images'))):
                z.write(os.path.join(pdir, 'images', fn), 'images/%s' % fn)

        no_ans = [q['no'] for q in out_qs if not q['answer']]
        imgs = sorted(os.listdir(os.path.join(pdir, 'images')))
        summary[paper] = {
            'questions': len(out_qs),
            'opt_split': opt_split,
            'opt_fail': opt_fail,
            'opt_info': opt_info,
            'opt_info': opt_info,
            'imgs': len(imgs),
            'no_answer': no_ans,
            'skipped_imgs': skipped,
            'table_dropped': dropped,
            'zip_mb': round(os.path.getsize(zpath) / 1e6, 2),
        }
        print('%s: %d题 选项拆分%d 失败%d 图片%d 跳过位图%d zip=%.2fMB'
              % (paper, len(out_qs), len(opt_split), len(opt_fail), len(imgs),
                 len(skipped), os.path.getsize(zpath) / 1e6))
        for f in opt_fail:
            print('    拆不动 第%d题 %s' % (f['no'], f['info']))

    json.dump(summary, open(os.path.join(OUT, 'build_summary.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('产物：', OUT)


if __name__ == '__main__':
    main()
