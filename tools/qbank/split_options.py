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


def _on_groups(profile, thresh, min_gap, length):
    """把一维投影里「有墨」的连续区段找出来，间隔 < min_gap 的合并。"""
    on = profile >= thresh
    runs = []
    s = -1
    for i, v in enumerate(on):
        if v:
            if s < 0:
                s = i
        else:
            if s >= 0:
                runs.append([s, i])
                s = -1
    if s >= 0:
        runs.append([s, length])
    merged = []
    for r in runs:
        if merged and r[0] - merged[-1][1] < min_gap:
            merged[-1][1] = r[1]
        else:
            merged.append(r)
    return merged


def _fit_groups(groups, n):
    """把分组数调整到恰好 n：多了就合并间隔最近的两组，少了就拆分最宽的一组。"""
    groups = [list(g) for g in groups]
    if not groups:
        return None
    while len(groups) > n:
        gaps = [(groups[i + 1][0] - groups[i][1], i) for i in range(len(groups) - 1)]
        _, i = min(gaps)
        groups[i][1] = groups[i + 1][1]
        del groups[i + 1]
    while len(groups) < n:
        i = max(range(len(groups)), key=lambda k: groups[k][1] - groups[k][0])
        a, b = groups[i]
        m = (a + b) // 2
        if m <= a or m >= b:
            return None
        groups[i] = [a, m]
        groups.insert(i + 1, [m, b])
    return groups


def _tighten_opt(bin_full, box):
    """收紧选项框，并丢掉贴在外边缘的那条表格框线（只裁边缘，不碰图形内部笔画）。"""
    x0, y0, x1, y1 = box
    x1 = min(x1, bin_full.shape[1])
    y1 = min(y1, bin_full.shape[0])
    if x1 <= x0 or y1 <= y0:
        return box
    sub = bin_full[y0:y1, x0:x1]
    if sub.sum() == 0:
        return box
    ys, xs = np.where(sub)
    t, b = int(ys.min()), int(ys.max())
    l, r = int(xs.min()), int(xs.max())
    wdt = r - l + 1

    def full_row(y):
        return int(sub[y, l:r + 1].sum()) >= 0.9 * wdt

    while t < b and b - t > 8 and full_row(t):
        t += 1
    while b > t and b - t > 8 and full_row(b):
        b -= 1
    hgt = b - t + 1

    def full_col(x):
        return int(sub[t:b + 1, x].sum()) >= 0.9 * hgt

    while l < r and r - l > 8 and full_col(l):
        l += 1
    while r > l and r - l > 8 and full_col(r):
        r -= 1
    return [x0 + l, y0 + t, x0 + r + 1, y0 + b + 1]


def _build_cells(region, top, w, h, force=None):
    """在选项区域内，按实际墨迹分组切出 4 格，按 (行,列) 阅读顺序返回。
    返回 (nrow, ncol, cells) 或 None（无法切成 4 格时）。

    关键：先按行投影分组，**丢掉过细的行组**（题干残条/边距碎片，否则 1×4 会
    被误判成 2×2）；再只在保留的行范围内统计列分组，丢掉过细的列组。
    """
    rh, rw = region.shape
    if rh < 8 or rw < 8:
        return None

    # 列分组：阈值低、只丢极窄噪声列（文字选项的笔画很细，不能丢）
    colprof = region.sum(axis=0)
    cg = _on_groups(colprof, max(1, rh // 300), max(10, rw // 100), rw)
    if not cg:
        return None
    cg = [g for g in cg if (g[1] - g[0]) >= 10]      # 极窄列当噪声
    if not cg:
        return None

    # 行分组：**只有行间真空白（墨量<=1）且空隙>=6px 才分行**；
    # 文字选项行很细（~15px）也要保留，图形之间若只是「低墨」并非空白则不拆行。
    rowprof = region.sum(axis=1)
    rg = _on_groups(rowprof, 1, 6, rh)
    if rg:
        rg = [g for g in rg if (g[1] - g[0]) >= 6]
    nr = len(rg) if rg else 1

    # 2×2 的判据：确实有 2 行，且列分组只有 2 个（图形 2×2 或「分类题」2 列×2 行文字）。
    # 若列分组 >=3，说明是一排 4 个、那 2 行是误拆——按 1×4 处理。
    if force == "1x4":
        is_2x2 = False
    elif force == "2x2":
        is_2x2 = True
    else:
        is_2x2 = (nr >= 2 and len(cg) <= 2)

    if not is_2x2:
        # 1×4
        cols = _fit_groups(cg, 4)
        if not cols:
            return None
        y0 = rg[0][0] if rg else 0
        y1 = rg[-1][1] if rg else rh
        cells = [[cols[j][0], top + y0, cols[j][1], top + y1] for j in range(4)]
        return 1, 4, cells
    else:
        # 2×2
        rows = _fit_groups(rg, 2)
        cols = _fit_groups(cg, 2)
        if not rows or not cols:
            return None
        cells = [[cols[j][0], top + rows[i][0], cols[j][1], top + rows[i][1]]
                 for i in range(2) for j in range(2)]
        return 2, 2, cells


def _ink_bands(bin_full, w, gap=6):
    """横向「内容带」：有墨的行连成带，带之间用空白隔开（间隙 < gap 的合并）。

    阈值取 0.4% 宽度——A/B/C/D 这种小字号标签行每行只有几个墨点，
    阈值放高会把它切成一堆 1~2px 的碎片，认不出是「标签行」。
    """
    h = bin_full.shape[0]
    row_ink = bin_full.sum(axis=1)
    on = row_ink >= max(2, int(0.004 * w))
    bands, s = [], -1
    for i, v in enumerate(on):
        if v:
            if s < 0:
                s = i
        else:
            if s >= 0:
                bands.append([s, i])
                s = -1
    if s >= 0:
        bands.append([s, h])
    merged = []
    for b in bands:
        if merged and b[0] - merged[-1][1] < gap:
            merged[-1][1] = b[1]
        else:
            merged.append(b)
    return [(a, b) for a, b in merged]


def _drop_rules(bands, bin_full, w):
    """丢掉「表格横线 / 边框」：很扁（<=6px）且近乎贯穿全宽的带。"""
    out = []
    for s, e in bands:
        if (e - s) <= 6:
            span = int(bin_full[s:e].any(axis=0).sum())
            if span >= 0.85 * w:
                continue
        out.append((s, e))
    return out


def _run_mask(mask, minlen=0, maxlen=10 ** 9):
    """返回 mask 里连续 True 的区段 [(s,e)]，长度落在 [minlen, maxlen]。"""
    out, s = [], -1
    for i, v in enumerate(mask):
        if v:
            if s < 0:
                s = i
        else:
            if s >= 0:
                if minlen <= i - s <= maxlen:
                    out.append((s, i))
                s = -1
    if s >= 0 and minlen <= len(mask) - s <= maxlen:
        out.append((s, len(mask)))
    return out


def _strip_rules(block):
    """把区域内的表格框线（细且贯穿整块高/宽的横竖线）抹掉再统计投影。

    题干表格的框线会把 4 个相邻的选项图形“连成一整块”，导致列分组只剩 1 组。
    """
    b = block.copy()
    rh, rw = b.shape
    if rh < 6 or rw < 6:
        return b
    col_ink = b.sum(axis=0)
    for s, e in _run_mask(col_ink >= 0.88 * rh, maxlen=6):
        b[:, s:e] = 0
    row_ink = b.sum(axis=1)
    for s, e in _run_mask(row_ink >= 0.90 * rw, maxlen=6):
        b[s:e, :] = 0
    return b


def _v_rule_positions(block, ratio=0.85):
    """竖直框线位置：细（<=6px）且**几乎贯穿整块高**的列。

    图形推理卷里大量题目把选项排在**表格**里，表格竖线恰好就是选项边界；
    表格里一行 4 个格 => 5 条竖线。阈值取 0.85 是关键：图形自己的竖直笔画
    只有 0.5~0.8 块高，达不到，不会误判成框线。
    """
    rh = block.shape[0]
    if rh < 36:
        return []
    col_ink = block.sum(axis=0)
    runs = _run_mask(col_ink >= ratio * rh, maxlen=6)
    return [(s + e) // 2 for s, e in runs]


def _split_at_rules(bands, bin_full, min_h=8):
    """把跨越「长横线」的内容带在这些线处切开（去掉线本身）。

    跨度按**本条带自己**的内容宽度算：表格的分隔线只贯穿表格，
    而图片里还可能有比表格更宽的文字行，用整图宽度会漏判。
    """
    out = []
    for s, e in bands:
        sub = bin_full[s:e, :]
        cols = np.where(sub.any(axis=0))[0]
        if cols.size == 0:
            out.append((s, e))
            continue
        x0, x1 = int(cols.min()), int(cols.max()) + 1
        span = x1 - x0
        if span < 40:
            out.append((s, e))
            continue
        row_ink = sub[:, x0:x1].sum(axis=1)
        cuts = []
        for y in np.where(row_ink >= 0.6 * span)[0]:
            row = sub[y, x0:x1]
            best = c = 0
            for v in row:
                c = c + 1 if v else 0
                if c > best:
                    best = c
            if best >= 0.85 * span:
                cuts.append(int(y))
        if not cuts:
            out.append((s, e))
            continue
        cur = s
        for y in cuts:
            cut = s + y
            if cut - cur >= min_h:
                out.append((cur, cut))
            cur = cut + 1
        if e - cur >= min_h:
            out.append((cur, e))
    return out


def _is_label_band(block, w):
    """整条带是不是「A B C D」这种单字母标签行：4 个又窄又矮的小块。"""
    rh = block.shape[0]
    if not (6 <= rh <= 26):
        return False
    cg = _col_groups(block, w, True)
    if len(cg) != 4:
        return False
    ws = [b - a for a, b in cg]
    if max(ws) > 24:
        return False
    inks = [int(block[:, a:b].sum()) for a, b in cg]
    return min(inks) >= 3


def _col_groups(rowblock, w, text_like):
    """一行内的列分组。文字行用更大的合并间隙（选项之间的间距远大于逗号间距）。"""
    rh = rowblock.shape[0]
    gap = 42 if text_like else max(12, w // 90)
    cg = _on_groups(rowblock.sum(axis=0), max(1, rh // 300), gap, w)
    return [g for g in cg if (g[1] - g[0]) >= 8]


def _rule_span(block, x):
    """在 x 列上找最长的一段连续墨迹，作为这条框线的上下跨度（= 单元格高度）。"""
    if x < 0 or x >= block.shape[1]:
        return None
    col = block[:, max(0, x - 1):x + 2].any(axis=1)
    best, s = None, -1
    for i, v in enumerate(col):
        if v:
            if s < 0:
                s = i
        else:
            if s >= 0:
                if best is None or i - s > best[1] - best[0]:
                    best = (s, i)
                s = -1
    if s >= 0 and (best is None or len(col) - s > best[1] - best[0]):
        best = (s, len(col))
    return best


def _cell_y(block, rules, ref):
    """用框线自身的跨度确定单元格上下边界（避免内容被拆成两条带时切掉上下）。"""
    spans = [sp for sp in (_rule_span(block, x) for x in rules) if sp]
    if not spans:
        return None
    y0 = min(s for s, e in spans)
    y1 = max(e for s, e in spans)
    if y1 - y0 < 10:
        return None
    if not (y0 - 6 <= ref[0] and ref[1] <= y1 + 6):   # 参考行应在跨度内
        return None
    # 框线可能连着相邻行，限制外扩幅度，避免把别的行也框进来
    pad = int(0.35 * (ref[1] - ref[0]))
    return max(y0, ref[0] - pad), min(y1, ref[1] + pad)


def _partition(block):
    """把一块区域按实际墨迹切成 4 个格子，按阅读顺序返回 (nrow, ncol, cells)。

    先取「墨量最多的那一行」当选项行：这样即使块里混进了 A/B/C/D 字母标签行，
    也只会切真正的内容行。

    切列的优先级：
      1. 表格竖框线恰好 5 条 → 直接就是 4 格（最准）；
      2. 只有左右两条框线 → 在这两条之间等分 4 格（内部格线被图形压住时）；
      3. 3 条框线 + 2 行 → 2×2；
      4. 没有框线 → 按列分组数收敛（1 行 3~6 组、2 行各 2 组、4 行各 1 组）。
    """
    b = _strip_rules(block)
    rh, rw = b.shape
    if rh < 8 or rw < 8:
        return None

    rg = _on_groups(b.sum(axis=1), 1, 5, rh)
    rg = [g for g in rg if (g[1] - g[0]) >= 5]
    if not rg:
        return None
    inks = [int(b[s:e].sum()) for s, e in rg]
    main = rg[int(np.argmax(inks))]

    # 竖框线：先整块找；找不到（框线只贯穿某一格、被字母标签行稀释掉）就逐行找，
    # 取第一个能给出 3~5 条线的区域。ref 记下这个区域，供 _cell_y 定位单元格高度。
    vr, ref = _v_rule_positions(block), main
    if not (3 <= len(vr) <= 5):
        for g in sorted(rg, key=lambda x: -(x[1] - x[0])):
            v = _v_rule_positions(block[g[0]:g[1], :])
            if 3 <= len(v) <= 5:
                vr, ref = v, g
                break
    if len(vr) == 5:
        span = _cell_y(block, vr, ref) or main
        cells = [[vr[j] + 3, span[0], vr[j + 1] - 3, span[1]] for j in range(4)]
        if all(c[2] - c[0] >= 12 for c in cells):
            return 1, 4, cells
    if len(vr) == 2:
        span = _cell_y(block, vr, main) or main
        width = vr[1] - vr[0]
        if width >= 160:
            step = width / 4.0
            cells = [[int(vr[0] + j * step) + 2, span[0],
                      int(vr[0] + (j + 1) * step) - 2, span[1]] for j in range(4)]
            if all(c[2] - c[0] >= 12 for c in cells):
                return 1, 4, cells
    if len(vr) == 3 and len(rg) >= 2:
        two = sorted(rg, key=lambda g: -int(b[g[0]:g[1]].sum()))[:2]
        two.sort(key=lambda g: g[0])
        cells = [[vr[j] + 3, two[i][0], vr[j + 1] - 3, two[i][1]]
                 for i in range(2) for j in range(2)]
        if all(c[2] - c[0] >= 12 for c in cells):
            return 2, 2, cells

    nr = len(rg)
    text_like = max(e - s for s, e in rg) <= 34
    per_row = [_col_groups(b[s:e, :], rw, text_like) for s, e in rg]

    if nr == 1:
        cg = per_row[0]
        if not (3 <= len(cg) <= 6):
            return None
        cols = _fit_groups(cg, 4)
        if not cols:
            return None
        y0, y1 = rg[0]
        return 1, 4, [[cols[j][0], y0, cols[j][1], y1] for j in range(4)]

    if nr == 2:
        g0, g1 = per_row[0], per_row[1]
        if not (2 <= len(g0) <= 3 and 2 <= len(g1) <= 3):
            return None
        c0, c1 = _fit_groups(g0, 2), _fit_groups(g1, 2)
        if not c0 or not c1:
            return None
        cells = []
        for j in range(2):
            cells.append([c0[j][0], rg[0][0], c0[j][1], rg[0][1]])
        for j in range(2):
            cells.append([c1[j][0], rg[1][0], c1[j][1], rg[1][1]])
        return 2, 2, cells

    if nr == 4:
        if not all(1 <= len(g) <= 2 for g in per_row):
            return None
        widths = []
        for g in per_row:
            gg = _fit_groups(g, 1)
            widths.append(gg[0])
        return 4, 1, [[widths[i][0], rg[i][0], widths[i][1], rg[i][1]]
                      for i in range(4)]
    return None


def _is_label_row(opts):
    """四个格子都只是「A/B/C/D 这种单字母标签」——不是选项本身。"""
    ok = True
    for b in opts:
        bw, bh = b[2] - b[0], b[3] - b[1]
        if bw > 30 or bh > 26:
            ok = False
            break
    return ok


def _looks_like_options(opts, bin_full, w, h):
    if len(opts) != 4:
        return False
    inks, ws, hs = [], [], []
    for b in opts:
        x0, y0, x1, y1 = b
        if x1 <= x0 or y1 <= y0:
            return False
        inks.append(int(bin_full[y0:y1, x0:x1].sum()))
        ws.append(x1 - x0)
        hs.append(y1 - y0)
    if min(inks) < 40 or min(ws) < 8 or min(hs) < 6:
        return False
    if max(inks) > 0 and min(inks) / max(inks) < 0.15:
        return False
    if max(hs) > 0 and min(hs) / max(hs) < 0.25:
        return False
    if min(b[1] for b in opts) < 0.30 * h:      # 选项必须在画面下半部
        return False
    return True


def _find_option_block(bin_full, w, h):
    """自下而上拼「选项块」并打分选最优。

    同时处理：
      * 一排 4 个图形选项（最后一条带直接就是）；
      * 2×2 图形选项 / 2 列×2 行文字选项（拼两条带）；
      * 计数题「A. 1 B. 2 C. 3 D. 4」这种底部一行矮文字选项；
      * A/B/C/D 字母写在图形**下方**的表格题（字母行当标签剔掉）；
      * 表格横线（很扁且贯穿全宽的带，先丢掉）。
    """
    bands = _drop_rules(_ink_bands(bin_full, w), bin_full, w)
    bands = _split_at_rules(bands, bin_full)

    # 尾部「字母标签行」先剔掉：只有 4 个单字母大小的小块
    while bands:
        s, e = bands[-1]
        if _is_label_band(bin_full[s:e, :], w):
            bands.pop()
            continue
        break

    best = None
    for k in range(1, len(bands) + 1):
        block = bands[-k:]
        top, bot = block[0][0], block[-1][1]
        if bot - top > 0.88 * h:
            break
        built = _partition(bin_full[top:bot, :])
        if built is None:
            continue
        nrow, ncol, cells = built
        opts = [_tighten_opt(bin_full, [c[0], top + c[1], c[2], top + c[3]])
                for c in cells]
        if not _looks_like_options(opts, bin_full, w, h):
            continue
        inks = [int(bin_full[b[1]:b[3], b[0]:b[2]].sum()) for b in opts]
        bal = min(inks) / max(inks) if max(inks) else 0
        score = (round(bal, 3), -k)          # 先看均衡度，同分选「带更少」的块
        if best is None or score > best[0]:
            best = (score, opts, nrow, ncol, top)
    return None if best is None else best[1:]


def split_question(img_path):
    im = Image.open(img_path).convert("RGB")
    w, h = im.size
    bin_full = _binarize(im)

    found = _find_option_block(bin_full, w, h)
    if found is not None:
        options, nrow, ncol, _block_top = found
        # 题干止于「四个选项格的真实上沿」，而不是选项块的顶部：
        # 块里可能还混着上面的内容（表格上边线、文字行），用块顶会把题干压成一条
        opt_top = min(b[1] for b in options)
        stem_bottom = max(0, opt_top - 2)
        if stem_bottom < 0.10 * h:
            stem_bottom = int(0.10 * h)
        stem_box = _tighten(bin_full, [0, 0, w, stem_bottom])
        if stem_box[3] - stem_box[1] < 20:
            stem_box = [0, 0, w, max(20, int(0.10 * h))]
        if stem_box[3] > opt_top:               # 题干不得越过选项上沿
            stem_box[3] = opt_top
        areas = [int(bin_full[b[1]:b[3], b[0]:b[2]].sum()) for b in options]
        mx = max(areas) if areas else 0
        return {"stem_box": stem_box, "options": options,
                "grid": "%dx%d" % (nrow, ncol), "method": "block",
                "size": (w, h),
                "low_conf": bool(mx == 0 or min(areas) / mx < 0.30)}

    return _split_legacy(img_path)


def _split_legacy(img_path):
    im = Image.open(img_path).convert("RGB")
    w, h = im.size
    bin_full = _binarize(im)
    row_any = bin_full.any(axis=1)

    # —— 题干/选项分界：对每条候选分界（整行全白 >=8px，位于中下部），在其下方
    #    区域按「实际墨迹列/行分组」切成 4 格并验证每格都有足量墨；能干净分出 4 格的才采用。
    #    这样 1×4 只认题干/选项那条；2×2 会自动排除掉「A/B 与 C/D 之间」的内部那条。 ——
    # 候选分界：用「整行基本无墨」（<5% 宽）而不是「整行全白」，
    # 这样题干图形与选项之间只要有明显空隙就能找到，文字选项题也不至于把图形并进选项。
    row_ink = bin_full.sum(axis=1)
    has_ink = row_ink >= (0.05 * w)
    cand = [(s, e) for s, e in _blank_runs(has_ink, 6)
            if s >= 0.30 * h and e <= 0.97 * h]
    chosen = None          # (opt_top, -min_area, nrow, ncol, cells)
    for s, e in sorted(cand, key=lambda x: -x[0]):    # 从最靠下的分界试起
        top = e
        region = bin_full[top:, 0:w]
        if region.shape[0] < 0.18 * h:
            continue
        built = _build_cells(region, top, w, h)
        if built is None:
            continue
        nrow, ncol, cells = built
        areas = [int(bin_full[c[1]:c[3], c[0]:c[2]].sum()) for c in cells]
        if min(areas) < 150:          # 文字选项每格墨量本来就少，阈值放低
            continue
        # 优先「分界最靠下（带最少题干）+ 每格墨量最足」
        score = (top, -min(areas))
        if chosen is None or score > chosen[:2]:
            chosen = (top, -min(areas), nrow, ncol, cells)

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
        built = _build_cells(region, opt_top, w, h)
        if built is None:
            nrow, ncol = 1, 4
            cw = w // 4
            ordered = [[i * cw, opt_top, (i + 1) * cw, h] for i in range(4)]
        else:
            nrow, ncol, ordered = built
        stem_bottom = max(0, opt_top)
        method = "fallback_region"

    options = [_tighten(bin_full, [c[0], c[1], c[2], c[3]]) for c in ordered[:4]]

    def _areas(opts):
        return [int(bin_full[b[1]:b[3], b[0]:b[2]].sum()) for b in opts]

    def _balance(opts):
        a = _areas(opts)
        mx = max(a) if a else 0
        return (min(a) / mx) if mx > 0 else 0.0

    # 后处理：判成 2×2 但四格高度差太大（1×4 被误切成两半）→ 按 1×4 重切
    if nrow == 2:
        hs = [b[3] - b[1] for b in options]
        if min(hs) < 0.55 * max(hs):
            built = _build_cells(bin_full[opt_top:, 0:w], opt_top, w, h, force="1x4")
            if built is not None:
                nrow, ncol, ordered = built
                options = [_tighten(bin_full, [c[0], c[1], c[2], c[3]])
                           for c in ordered[:4]]

    # 均衡度重试：四格严重不均时，试试另一种版式，取更均衡的
    # （分类题的「2 列 × 2 行文字选项」常被当成 1×4 切成碎片，反之亦然）
    if _balance(options) < 0.30:
        alt = "2x2" if ncol == 4 else "1x4"
        built = _build_cells(bin_full[opt_top:, 0:w], opt_top, w, h, force=alt)
        if built is not None:
            n2r, n2c, cells2 = built
            opts2 = [_tighten(bin_full, [c[0], c[1], c[2], c[3]]) for c in cells2]
            if _balance(opts2) > _balance(options):
                nrow, ncol, options = n2r, n2c, opts2

    # 标记低置信（供日志参考），但**不再整图当题干**：
    # 题干一律裁到选项区以上，保证「题目」和「选项」是分开的独立图片。
    areas = _areas(options)
    low_conf = (min(areas) < 150) or (max(areas) > 0 and min(areas) / max(areas) < 0.2)

    if stem_bottom < 0.12 * h:
        stem_bottom = int(0.12 * h)
    stem_box = _tighten(bin_full, [0, 0, w, stem_bottom])
    if stem_box[3] - stem_box[1] < 20:
        stem_box = [0, 0, w, max(20, int(0.12 * h))]

    grid = "%dx%d" % (nrow, ncol)
    return {"stem_box": stem_box, "options": options, "grid": grid,
            "method": method, "size": (w, h), "low_conf": low_conf}


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
