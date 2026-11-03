#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把「图推700题」整图（题干图形 + A/B/C/D 四选项画在一张图）拆成：
    stem  : 题干区（文字 + 题干图形，已裁掉底部选项）
    opt_A..opt_D : 四个选项图形（各裁成只含图形本身的紧致框）

方法（纯 PIL + numpy，不依赖 OCR / 外部连通域库）：
1. 块归约二值化（3×3 块内任一墨点即视为墨），保住细笔画。
2. 低分辨率连通域定位「图形」，过滤+合并碎片成 figure 级框。
3. 题干/选项分隔：从底部向上找第一条「整行全白且 >=15px」的横向空白带，
   其下方即选项图形簇；若找不到则用底部 45% 兜底。
4. 选项簇内用「整列/整行是否完全无墨」判定 1×4 还是 2×2，切出 4 格；
   若图形相连无间隙，按区域宽高比兜底（宽>高 => 1×4，否则 2×2）。
5. 每格再按墨点做紧致裁切，只留图形本身（去掉字母标签与边框外留白）。
6. 题干 = 分隔带以上全部内容（含题干文字与图形）。
任何情况都保证产出 4 张选项图，绝不回退到「见题图」。
"""

import os
import numpy as np
from PIL import Image

BIN_THRESH = 140
BLOCK = 3
BAND_MIN = 15           # 候选分隔带最小高度
AREA_MIN = 200          # figure 最小面积（原图像素）
MARGIN = 6
MERGE_PAD = 8


def _binarize(im):
    a = np.asarray(im.convert("L"))
    return (a < BIN_THRESH).astype(np.uint8)


def _block_bin(bin_full, block):
    h, w = bin_full.shape
    hh, ww = h // block, w // block
    if hh == 0 or ww == 0:
        return bin_full
    trimmed = bin_full[:hh * block, :ww * block]
    view = trimmed.reshape(hh, block, ww, block)
    return view.any(axis=(1, 3)).astype(np.uint8)


def _blank_runs(any_arr, minlen):
    runs = []
    s = -1
    for i, v in enumerate(any_arr):
        if not v:
            if s < 0:
                s = i
        else:
            if s >= 0:
                if i - s >= minlen:
                    runs.append((s, i))
                s = -1
    if s >= 0 and len(any_arr) - s >= minlen:
        runs.append((s, len(any_arr)))
    return runs


def _label(bin_small):
    h, w = bin_small.shape
    ys, xs = np.where(bin_small)
    if xs.size == 0:
        return np.zeros((h, w), dtype=np.int32), 0
    lab = np.zeros((h, w), dtype=np.int32)
    parent = {}

    def find(x):
        root = x
        while parent.get(root, root) != root:
            root = parent[root]
        return root

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[ra] = rb

    nxt = 1
    for y, x in zip(ys.tolist(), xs.tolist()):
        nb = []
        if x > 0 and bin_small[y, x - 1]:
            nb.append(lab[y, x - 1])
        if y > 0 and bin_small[y - 1, x]:
            nb.append(lab[y - 1, x])
        if not nb:
            lab[y, x] = nxt
            parent[nxt] = nxt
            nxt += 1
        else:
            m = min(nb)
            lab[y, x] = m
            for v in nb:
                union(m, v)
    for y, x in zip(ys.tolist(), xs.tolist()):
        if lab[y, x]:
            lab[y, x] = find(lab[y, x])
    return lab, nxt - 1


def _fig_boxes(bin_small, block):
    h, w = bin_small.shape
    if bin_small.sum() == 0:
        return []
    lab, n = _label(bin_small)
    boxes = []
    for cid in range(1, n + 1):
        ys, xs = np.where(lab == cid)
        if xs.size == 0:
            continue
        boxes.append([int(xs.min()) * block, int(ys.min()) * block,
                      (int(xs.max()) + 1) * block, (int(ys.max()) + 1) * block])
    return boxes


def _merge(boxes, pad):
    boxes = [list(b[:4]) for b in boxes]
    changed = True
    while changed:
        changed = False
        for i in range(len(boxes)):
            for j in range(i + 1, len(boxes)):
                a, b = boxes[i], boxes[j]
                if (a[0] - pad < b[2] and b[0] - pad < a[2] and
                        a[1] - pad < b[3] and b[1] - pad < a[3]):
                    boxes[i] = [min(a[0], b[0]), min(a[1], b[1]),
                                max(a[2], b[2]), max(a[3], b[3])]
                    del boxes[j]
                    changed = True
                    break
    return boxes


def _tighten(bin_full, box):
    x0, y0, x1, y1 = box
    x1 = min(x1, bin_full.shape[1])
    y1 = min(y1, bin_full.shape[0])
    if x1 <= x0 or y1 <= y0:
        return box
    sub = bin_full[y0:y1, x0:x1]
    if sub.sum() == 0:
        return box
    ys, xs = np.where(sub)
    return [x0 + int(xs.min()), y0 + int(ys.min()),
            x0 + int(xs.max()) + 1, y0 + int(ys.max()) + 1]


def _row_count(bin_opt):
    """选项区域内：用『内部、且整行基本无墨』的横向空白带判定是 1 行 / 2 行 /
    4 行（忽略贴上下边缘的边距）。返回 nrow ∈ {1,2,4}。"""
    h, w = bin_opt.shape
    row_ink = bin_opt.sum(axis=1)
    row_empty = row_ink < (0.06 * w)      # 整行墨量占比 < 6% 视为空行（比纯全白更稳）
    sep = _blank_runs(row_empty, 3)
    interior = [(s, e) for s, e in sep if s > 1 and e < h - 1]   # 排除贴边边距
    nrow = len(interior) + 1
    if nrow not in (1, 2, 4):
        nrow = 1
    return nrow


def split_question(img_path):
    im = Image.open(img_path).convert("RGB")
    w, h = im.size
    bin_full = _binarize(im)
    row_any = bin_full.any(axis=1)
    small = _block_bin(bin_full, BLOCK)
    figs = _fig_boxes(small, BLOCK)
    figs = _merge(figs, MERGE_PAD)

    # 过滤 figure 级框
    good = []
    for b in figs:
        bw, bh = b[2] - b[0], b[3] - b[1]
        if bw > 0.95 * w or bh < 10 or bw < 10:
            continue
        area = int(bin_full[b[1]:b[3], b[0]:b[2]].sum())
        if area < AREA_MIN:
            continue
        good.append(b)
    good = _merge(good, MERGE_PAD)

    # —— 题干/选项分界：对每条候选分界（整行全白 >=8px，位于中下部），在其下方
    #    区域做 4 格均分并验证「每格都有足量墨」；能干净分出 4 个选项格的才采用。
    #    这样 1×4 只认题干/选项那条；2×2 会自动排除掉「A/B 与 C/D 之间」的内部那条。 ——
    cand = [(s, e) for s, e in _blank_runs(row_any, 8)
            if s >= 0.30 * h and e <= 0.97 * h]
    chosen = None          # (opt_top, min_score, nrow, ncol, cells)
    for s, e in sorted(cand, key=lambda x: -x[0]):    # 从最靠下的分界试起
        top = e
        region = bin_full[top:, 0:w]
        if region.shape[0] < 0.18 * h:
            continue
        nrow = _row_count(region)
        ncol = 4 // nrow
        cells = _grid_cells(region, nrow, ncol, w, top, h)
        if len(cells) < 4:
            continue
        areas = [int(bin_full[c[1]:c[3], c[0]:c[2]].sum()) for c in cells[:4]]
        if min(areas) < 400:
            continue
        # 优先「分界最靠下（带最少题干）+ 每格墨量最足」
        score = (top, -min(areas))
        if chosen is None or score > chosen[:2]:
            chosen = (top, -min(areas), nrow, ncol, cells[:4])

    method = "band"
    if chosen is not None:
        opt_top, _, nrow, ncol, ordered = chosen
        stem_bottom = max(0, opt_top)
    else:
        # 兜底：题干/选项无明显整行空白时，取底部区域（选项基本都在底部）
        ink_bottom = h - 1
        for y in range(h - 1, -1, -1):
            if row_any[y]:
                ink_bottom = y
                break
        opt_top = min(int(0.55 * h), ink_bottom)
        region = bin_full[opt_top:, 0:w]
        nrow = _row_count(region)
        ncol = 4 // nrow
        cells = _grid_cells(region, nrow, ncol, w, opt_top, h)
        areas = [int(bin_full[c[1]:c[3], c[0]:c[2]].sum()) for c in cells[:4]]
        if len(areas) < 4 or min(areas) < 400:
            nrow, ncol = 1, 4
            cells = _grid_cells(region, nrow, ncol, w, opt_top, h)
        ordered = cells
        stem_bottom = max(0, opt_top)
        method = "fallback_region"

    options = [_tighten(bin_full, [c[0], c[1], c[2], c[3]]) for c in ordered[:4]]

    # 题干：分界以上
    if stem_bottom < 0.12 * h:
        stem_bottom = int(0.12 * h)
    stem_box = _tighten(bin_full, [0, 0, w, stem_bottom])
    if stem_box[3] - stem_box[1] < 20:
        stem_box = [0, 0, w, max(20, int(0.12 * h))]

    grid = "%dx%d" % (nrow, ncol)
    return {"stem_box": stem_box, "options": options, "grid": grid,
            "method": method, "size": (w, h)}


def _grid_cells(bin_opt, nrow, ncol, w, sy0, sy1):
    """按 nrow×ncol 把选项区域切成网格，返回每个 cell 的原图坐标框。"""
    hh = sy1 - sy0
    # 列边界：用行投影把宽度均分到 ncol（更稳，避开边框误切）
    col_edges = [int(round(w * i / ncol)) for i in range(ncol + 1)]
    row_edges = [sy0 + int(round(hh * i / nrow)) for i in range(nrow + 1)]
    cells = []
    for r in range(nrow):
        for c in range(ncol):
            cells.append([col_edges[c], row_edges[r], col_edges[c + 1], row_edges[r + 1]])
    return cells


def _order_cells(cells):
    # 按 (行, 列) 排序
    def center(b):
        return ((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
    # 先按 y 中心分排
    cs = sorted(cells, key=lambda b: center(b)[1])
    rows = []
    cur = [cs[0]]
    for b in cs[1:]:
        if abs(center(b)[1] - center(cur[0])[1]) < 60:
            cur.append(b)
        else:
            rows.append(cur)
            cur = [b]
    rows.append(cur)
    ordered = []
    for r in rows:
        r.sort(key=lambda b: center(b)[0])
        ordered.extend(r)
    return ordered


def crop_images(img_path, result, out_dir, stem_name, opt_names):
    im = Image.open(img_path).convert("RGB")
    paths = []
    sx0, sy0, sx1, sy1 = result["stem_box"]
    stem = im.crop((sx0, sy0, sx1, sy1))
    sp = os.path.join(out_dir, stem_name)
    stem.save(sp)
    paths.append(sp)
    for b, nm in zip(result["options"], opt_names):
        opt = im.crop((b[0], b[1], b[2], b[3]))
        p = os.path.join(out_dir, nm)
        opt.save(p)
        paths.append(p)
    return paths


if __name__ == "__main__":
    import sys
    p = sys.argv[1] if len(sys.argv) > 1 else r"qbank_out/images/s01_q01.png"
    r = split_question(p)
    print(p, r["grid"], r["method"], "size", r["size"])
    print("  stem", r["stem_box"])
    for i, b in enumerate(r["options"]):
        print("  opt", chr(65 + i), b)
