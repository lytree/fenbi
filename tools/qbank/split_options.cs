#!/usr/bin/env dotnet
// 把「图推700题」整图（题干图形 + A/B/C/D 四选项画在一张图）拆成：
//    stem  : 题干区（文字 + 题干图形，已裁掉底部选项）
//    opt_A..opt_D : 四个选项图形（各裁成只含图形本身的紧致框）
//
// 用法：
//   dotnet run tools/qbank/split_options.cs -- qbank_out/images/s01_q01.png
//
// 方法（纯 PIL + numpy，不依赖 OCR / 外部连通域库）：
// 1. 块归约二值化（3×3 块内任一墨点即视为墨），保住细笔画。
// 2. 低分辨率连通域定位「图形」，过滤+合并碎片成 figure 级框。
// 3. 题干/选项分隔：从底部向上找第一条「整行全白且 >=15px」的横向空白带，
//    其下方即选项图形簇；若找不到则用底部 45% 兜底。
// 4. 选项簇内用「整列/整行是否完全无墨」判定 1×4 还是 2×2，切出 4 格；
//    若图形相连无间隙，按区域宽高比兜底（宽>高 => 1×4，否则 2×2）。
// 5. 每格再按墨点做紧致裁切，只留图形本身（去掉字母标签与边框外留白）。
// 6. 题干 = 分隔带以上全部内容（含题干文字与图形）。
// 任何情况都保证产出 4 张选项图，绝不回退到「见题图」。
//
// 说明：上面的 6 步是原 docstring 的算法说明；实现按原 Python 脚本逐函数翻译，
// docstring 里提到但主流程已不再调用的 _block_bin / _fig_boxes 等仍原样保留。

#:package SkiaSharp@2.88.9
#:property PublishAot=false

#pragma warning disable CS8321   // 保留原脚本中定义但主流程未调用的辅助函数

using System;
using System.Collections.Generic;
using System.Linq;
using SkiaSharp;

Console.OutputEncoding = System.Text.Encoding.UTF8;

var p = args.Length > 0 ? args[0] : "qbank_out/images/s01_q01.png";
if (!File.Exists(p))
{
    Console.Error.WriteLine($"找不到图片：{p}");
    return 2;
}
try
{
    var r = SplitQuestion(p);
    Console.WriteLine($"{p} {r.grid} {r.method} size ({r.size[0]}, {r.size[1]})");
    Console.WriteLine("  stem " + FmtBox(r.stem_box));
    for (int i = 0; i < r.options.Count; i++)
        Console.WriteLine("  opt " + "ABCD"[i] + " " + FmtBox(r.options[i]));
}
catch (Exception ex)
{
    Console.Error.WriteLine(ex.Message);
    return 2;
}
return 0;

// ---------------- helpers ----------------

// numpy 二维数组映射到一维 byte[] + 矩形视图：Slice 对应 bin[y0:y1, x0:x1]，
// RowSums / ColSums 对应 sum(axis=1) / sum(axis=0)，Copy 对应 .copy()。
static Bin2D Binarize(string path)
{
    using var bmp = SKBitmap.Decode(path);
    int w = bmp.Width, h = bmp.Height;
    var b = new Bin2D(w, h);
    // PIL 的 convert("L")：L = (R*19595 + G*38470 + B*7471) >> 16
    // SkiaSharp 的 GetPixel 返回与底层 ColorType 无关的 SKColor(R,G,B,A)，
    // 仍按原手写亮度公式计算，不用 SkiaSharp 内置色彩转换，贴合 PIL 阈值行为。
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
        {
            var px = bmp.GetPixel(x, y);
            int lum = (px.Red * 19595 + px.Green * 38470 + px.Blue * 7471) >> 16;
            b[x, y] = (byte)(lum < C.BIN_THRESH ? 1 : 0);
        }
    return b;
}

// 块归约二值化：块内任一墨点即视为墨。
static Bin2D BlockBin(Bin2D binFull, int block)
{
    int h = binFull.H, w = binFull.W;
    int hh = h / block, ww = w / block;
    if (hh == 0 || ww == 0) return binFull;
    var res = new Bin2D(ww, hh);
    for (int by = 0; by < hh; by++)
        for (int bx = 0; bx < ww; bx++)
        {
            bool on = false;
            for (int dy = 0; dy < block && !on; dy++)
                for (int dx = 0; dx < block; dx++)
                    if (binFull[bx * block + dx, by * block + dy] != 0) { on = true; break; }
            res[bx, by] = (byte)(on ? 1 : 0);
        }
    return res;
}

// 一维 bool 数组里连续 False 的区段，长度 >= minlen。
static List<int[]> BlankRuns(bool[] arr, int minlen)
{
    var runs = new List<int[]>();
    int s = -1;
    for (int i = 0; i < arr.Length; i++)
    {
        if (!arr[i]) { if (s < 0) s = i; }
        else { if (s >= 0) { if (i - s >= minlen) runs.Add(new[] { s, i }); s = -1; } }
    }
    if (s >= 0 && arr.Length - s >= minlen) runs.Add(new[] { s, arr.Length });
    return runs;
}

// 8 邻域（3×3）连通域标注：手写并查集，返回标签数组与标签上界。
static (int[] Lab, int N) LabelCC(Bin2D binSmall)
{
    int h = binSmall.H, w = binSmall.W;
    var lab = new int[w * h];
    var parent = new int[w * h + 1];
    int nxt = 1;
    bool any = false;
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
        {
            if (binSmall[x, y] == 0) continue;
            any = true;
            var nb = new List<int>();
            if (x > 0 && binSmall[x - 1, y] != 0) nb.Add(lab[y * w + (x - 1)]);
            if (y > 0 && binSmall[x, y - 1] != 0) nb.Add(lab[(y - 1) * w + x]);
            if (nb.Count == 0)
            {
                lab[y * w + x] = nxt;
                parent[nxt] = nxt;
                nxt++;
            }
            else
            {
                int m = nb.Min();
                lab[y * w + x] = m;
                foreach (int v in nb) UnionRoots(parent, m, v);
            }
        }
    if (!any) return (lab, 0);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
        {
            int i = y * w + x;
            if (lab[i] != 0) lab[i] = FindRoot(parent, lab[i]);
        }
    return (lab, nxt - 1);
}

static int FindRoot(int[] parent, int x)
{
    int root = x;
    while (parent[root] != root) root = parent[root];
    return root;
}

static void UnionRoots(int[] parent, int a, int b)
{
    int ra = FindRoot(parent, a), rb = FindRoot(parent, b);
    if (ra != rb) parent[ra] = rb;
}

// 每个连通域的包围盒，乘回 block 还原到原图坐标。
static List<int[]> FigBoxes(Bin2D binSmall, int block)
{
    var boxes = new List<int[]>();
    if (binSmall.Sum() == 0) return boxes;
    var (lab, n) = LabelCC(binSmall);
    int w = binSmall.W;
    for (int cid = 1; cid <= n; cid++)
    {
        int minX = int.MaxValue, minY = int.MaxValue, maxX = -1, maxY = -1;
        for (int i = 0; i < lab.Length; i++)
            if (lab[i] == cid)
            {
                int x = i % w, y = i / w;
                if (x < minX) minX = x; if (x > maxX) maxX = x;
                if (y < minY) minY = y; if (y > maxY) maxY = y;
            }
        if (maxX < 0) continue;
        boxes.Add(new[] { minX * block, minY * block, (maxX + 1) * block, (maxY + 1) * block });
    }
    return boxes;
}

// 相互重叠（外扩 pad）的框合并。
static List<int[]> MergeBoxes(List<int[]> src, int pad)
{
    var boxes = src.Select(b => new[] { b[0], b[1], b[2], b[3] }).ToList();
    bool changed = true;
    while (changed)
    {
        changed = false;
        for (int i = 0; i < boxes.Count; i++)
        {
            for (int j = i + 1; j < boxes.Count; j++)
            {
                var a = boxes[i]; var b = boxes[j];
                if (a[0] - pad < b[2] && b[0] - pad < a[2] &&
                    a[1] - pad < b[3] && b[1] - pad < a[3])
                {
                    boxes[i] = new[] { Math.Min(a[0], b[0]), Math.Min(a[1], b[1]),
                                       Math.Max(a[2], b[2]), Math.Max(a[3], b[3]) };
                    boxes.RemoveAt(j);
                    changed = true;
                    break;
                }
            }
        }
    }
    return boxes;
}

// 紧致裁切：把框收到子区域内墨点的真实外接框。
static int[] Tighten(Bin2D binFull, int[] box)
{
    int x0 = box[0], y0 = box[1];
    int x1 = Math.Min(box[2], binFull.W), y1 = Math.Min(box[3], binFull.H);
    if (x1 <= x0 || y1 <= y0) return box;
    var sub = binFull.Slice(x0, y0, x1, y1);
    if (sub.Sum() == 0) return box;
    var bd = sub.Bounds()!;
    return new[] { x0 + bd[0], y0 + bd[1], x0 + bd[2] + 1, y0 + bd[3] + 1 };
}

// 选项区域内用「内部整行基本无墨」判定 1 行 / 2 行 / 4 行。
static int RowCount(Bin2D binOpt)
{
    int h = binOpt.H, w = binOpt.W;
    var rowInk = binOpt.RowSums();
    var rowEmpty = new bool[h];
    for (int i = 0; i < h; i++) rowEmpty[i] = rowInk[i] < 0.06 * w;
    var sep = BlankRuns(rowEmpty, 3);
    int interior = 0;
    foreach (var r in sep) if (r[0] > 1 && r[1] < h - 1) interior++;
    int nrow = interior + 1;
    if (nrow != 1 && nrow != 2 && nrow != 4) nrow = 1;
    return nrow;
}

// 一维投影里「有墨」的连续区段，间隔 < minGap 的合并。
static List<int[]> OnGroups(int[] profile, int thresh, int minGap, int length)
{
    var on = new bool[profile.Length];
    for (int i = 0; i < profile.Length; i++) on[i] = profile[i] >= thresh;
    var runs = new List<int[]>();
    int s = -1;
    for (int i = 0; i < on.Length; i++)
    {
        if (on[i]) { if (s < 0) s = i; }
        else { if (s >= 0) { runs.Add(new[] { s, i }); s = -1; } }
    }
    if (s >= 0) runs.Add(new[] { s, length });
    var merged = new List<int[]>();
    foreach (var r in runs)
    {
        if (merged.Count > 0 && r[0] - merged[^1][1] < minGap) merged[^1][1] = r[1];
        else merged.Add(new[] { r[0], r[1] });
    }
    return merged;
}

// 把分组数调整到恰好 n：多了合并间隔最近的两组，少了拆分最宽的一组。
static List<int[]>? FitGroups(List<int[]> src, int n)
{
    var groups = src.Select(g => new[] { g[0], g[1] }).ToList();
    if (groups.Count == 0) return null;
    while (groups.Count > n)
    {
        int bi = 0, bgap = int.MaxValue;
        for (int i = 0; i < groups.Count - 1; i++)
        {
            int gap = groups[i + 1][0] - groups[i][1];
            if (gap < bgap) { bgap = gap; bi = i; }
        }
        groups[bi][1] = groups[bi + 1][1];
        groups.RemoveAt(bi + 1);
    }
    while (groups.Count < n)
    {
        int i = 0, best = int.MinValue;
        for (int k = 0; k < groups.Count; k++)
        {
            int wd = groups[k][1] - groups[k][0];
            if (wd > best) { best = wd; i = k; }
        }
        int a = groups[i][0], b = groups[i][1];
        int m = (a + b) / 2;
        if (m <= a || m >= b) return null;
        groups[i] = new[] { a, m };
        groups.Insert(i + 1, new[] { m, b });
    }
    return groups;
}

// 收紧选项框，并丢掉贴在外边缘的那条表格框线。
static int[] TightenOpt(Bin2D binFull, int[] box)
{
    int x0 = box[0], y0 = box[1];
    int x1 = Math.Min(box[2], binFull.W), y1 = Math.Min(box[3], binFull.H);
    if (x1 <= x0 || y1 <= y0) return box;
    var sub = binFull.Slice(x0, y0, x1, y1);
    if (sub.Sum() == 0) return box;
    var bd = sub.Bounds()!;
    int t = bd[1], b = bd[3], l = bd[0], r = bd[2];
    int wdt = r - l + 1;
    bool FullRow(int yy)
    {
        int s = 0; for (int xx = l; xx <= r; xx++) s += sub[xx, yy];
        return s >= 0.9 * wdt;
    }
    while (t < b && b - t > 8 && FullRow(t)) t++;
    while (b > t && b - t > 8 && FullRow(b)) b--;
    int hgt = b - t + 1;
    bool FullCol(int xx)
    {
        int s = 0; for (int yy = t; yy <= b; yy++) s += sub[xx, yy];
        return s >= 0.9 * hgt;
    }
    while (l < r && r - l > 8 && FullCol(l)) l++;
    while (r > l && r - l > 8 && FullCol(r)) r--;
    return new[] { x0 + l, y0 + t, x0 + r + 1, y0 + b + 1 };
}

// 在选项区域内按实际墨迹分组切出 4 格，按 (行,列) 阅读顺序返回。
static Built? BuildCells(Bin2D region, int top, int w, int h, string? force = null)
{
    int rh = region.H, rw = region.W;
    if (rh < 8 || rw < 8) return null;
    var colprof = region.ColSums();
    var cg = OnGroups(colprof, Math.Max(1, rh / 300), Math.Max(10, rw / 100), rw);
    if (cg.Count == 0) return null;
    cg = cg.Where(g => (g[1] - g[0]) >= 10).ToList();
    if (cg.Count == 0) return null;
    var rowprof = region.RowSums();
    var rg = OnGroups(rowprof, 1, 6, rh);
    if (rg.Count > 0) rg = rg.Where(g => (g[1] - g[0]) >= 6).ToList();
    int nr = rg.Count > 0 ? rg.Count : 1;
    bool is2x2;
    if (force == "1x4") is2x2 = false;
    else if (force == "2x2") is2x2 = true;
    else is2x2 = (nr >= 2 && cg.Count <= 2);
    if (!is2x2)
    {
        var cols = FitGroups(cg, 4);
        if (cols == null) return null;
        int y0 = rg.Count > 0 ? rg[0][0] : 0;
        int y1 = rg.Count > 0 ? rg[^1][1] : rh;
        var cells = new List<int[]>();
        for (int j = 0; j < 4; j++) cells.Add(new[] { cols[j][0], top + y0, cols[j][1], top + y1 });
        return new Built(1, 4, cells);
    }
    else
    {
        var rows = FitGroups(rg, 2);
        var cols = FitGroups(cg, 2);
        if (rows == null || cols == null) return null;
        var cells = new List<int[]>();
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++)
                cells.Add(new[] { cols[j][0], top + rows[i][0], cols[j][1], top + rows[i][1] });
        return new Built(2, 2, cells);
    }
}

// 横向「内容带」：有墨的行连成带，带之间用空白隔开（间隙 < gap 的合并）。
static List<int[]> InkBands(Bin2D binFull, int w, int gap = 6)
{
    int h = binFull.H;
    var rowInk = binFull.RowSums();
    int thr = Math.Max(2, (int)(0.004 * w));
    var on = new bool[h];
    for (int i = 0; i < h; i++) on[i] = rowInk[i] >= thr;
    var bands = new List<int[]>();
    int s = -1;
    for (int i = 0; i < h; i++)
    {
        if (on[i]) { if (s < 0) s = i; }
        else { if (s >= 0) { bands.Add(new[] { s, i }); s = -1; } }
    }
    if (s >= 0) bands.Add(new[] { s, h });
    var merged = new List<int[]>();
    foreach (var b in bands)
    {
        if (merged.Count > 0 && b[0] - merged[^1][1] < gap) merged[^1][1] = b[1];
        else merged.Add(new[] { b[0], b[1] });
    }
    return merged;
}

// 丢掉「表格横线 / 边框」：很扁（<=6px）且近乎贯穿全宽的带。
static List<int[]> DropRules(List<int[]> bands, Bin2D binFull, int w)
{
    var outp = new List<int[]>();
    foreach (var be in bands)
    {
        int s = be[0], e = be[1];
        if (e - s <= 6)
        {
            var ca = binFull.Slice(0, s, w, e).ColAny();
            int span = 0; foreach (bool v in ca) if (v) span++;
            if (span >= 0.85 * w) continue;
        }
        outp.Add(new[] { s, e });
    }
    return outp;
}

// mask 里连续 True 的区段，长度落在 [minlen, maxlen]。
static List<int[]> RunMask(bool[] mask, int minlen = 0, int maxlen = 1_000_000_000)
{
    var outp = new List<int[]>();
    int s = -1;
    for (int i = 0; i < mask.Length; i++)
    {
        if (mask[i]) { if (s < 0) s = i; }
        else { if (s >= 0) { if (minlen <= i - s && i - s <= maxlen) outp.Add(new[] { s, i }); s = -1; } }
    }
    if (s >= 0 && minlen <= mask.Length - s && mask.Length - s <= maxlen)
        outp.Add(new[] { s, mask.Length });
    return outp;
}

// 把区域内的表格框线（细且贯穿整块高/宽的横竖线）抹掉再统计投影。
static Bin2D StripRules(Bin2D block)
{
    var b = block.Copy();
    int rh = b.H, rw = b.W;
    if (rh < 6 || rw < 6) return b;
    var colInk = b.ColSums();
    var cm = new bool[rw];
    for (int x = 0; x < rw; x++) cm[x] = colInk[x] >= 0.88 * rh;
    foreach (var se in RunMask(cm, maxlen: 6))
        for (int y = 0; y < rh; y++)
            for (int x = se[0]; x < se[1]; x++) b[x, y] = 0;
    var rowInk = b.RowSums();
    var rm = new bool[rh];
    for (int y = 0; y < rh; y++) rm[y] = rowInk[y] >= 0.90 * rw;
    foreach (var se in RunMask(rm, maxlen: 6))
        for (int y = se[0]; y < se[1]; y++)
            for (int x = 0; x < rw; x++) b[x, y] = 0;
    return b;
}

// 竖直框线位置：细（<=6px）且几乎贯穿整块高的列。
static List<int> VRulePositions(Bin2D block, double ratio = 0.85)
{
    int rh = block.H;
    if (rh < 36) return new List<int>();
    var colInk = block.ColSums();
    var m = new bool[block.W];
    for (int x = 0; x < block.W; x++) m[x] = colInk[x] >= ratio * rh;
    return RunMask(m, maxlen: 6).Select(se => (se[0] + se[1]) / 2).ToList();
}

// 把跨越「长横线」的内容带在这些线处切开（去掉线本身）。
static List<int[]> SplitAtRules(List<int[]> bands, Bin2D binFull, int minH = 8)
{
    var outp = new List<int[]>();
    foreach (var be in bands)
    {
        int s = be[0], e = be[1];
        var sub = binFull.Slice(0, s, binFull.W, e);
        var ca = sub.ColAny();
        int x0 = -1, x1 = 0;
        for (int x = 0; x < ca.Length; x++) if (ca[x]) { if (x0 < 0) x0 = x; x1 = x + 1; }
        if (x0 < 0) { outp.Add(new[] { s, e }); continue; }
        int span = x1 - x0;
        if (span < 40) { outp.Add(new[] { s, e }); continue; }
        var sub2 = sub.Slice(x0, 0, x1, sub.H);
        var rowInk = sub2.RowSums();
        var cuts = new List<int>();
        for (int y = 0; y < rowInk.Length; y++)
        {
            if (!(rowInk[y] >= 0.6 * span)) continue;
            int best = 0, c = 0;
            for (int x = 0; x < span; x++) { c = sub2[x, y] != 0 ? c + 1 : 0; if (c > best) best = c; }
            if (best >= 0.85 * span) cuts.Add(y);
        }
        if (cuts.Count == 0) { outp.Add(new[] { s, e }); continue; }
        int cur = s;
        foreach (int yy in cuts)
        {
            int cut = s + yy;
            if (cut - cur >= minH) outp.Add(new[] { cur, cut });
            cur = cut + 1;
        }
        if (e - cur >= minH) outp.Add(new[] { cur, e });
    }
    return outp;
}

// 整条带是不是「A B C D」这种单字母标签行：4 个又窄又矮的小块。
static bool IsLabelBand(Bin2D block, int w)
{
    int rh = block.H;
    if (!(6 <= rh && rh <= 26)) return false;
    var cg = ColGroups(block, w, true);
    if (cg.Count != 4) return false;
    foreach (var g in cg) if (g[1] - g[0] > 24) return false;
    int minInk = int.MaxValue;
    foreach (var g in cg)
    {
        int ink = block.Slice(g[0], 0, g[1], block.H).Sum();
        if (ink < minInk) minInk = ink;
    }
    return minInk >= 3;
}

// 一行内的列分组。文字行用更大的合并间隙。
static List<int[]> ColGroups(Bin2D rowblock, int w, bool textLike)
{
    int rh = rowblock.H;
    int gap = textLike ? 42 : Math.Max(12, w / 90);
    var cg = OnGroups(rowblock.ColSums(), Math.Max(1, rh / 300), gap, w);
    return cg.Where(g => (g[1] - g[0]) >= 8).ToList();
}

// 在 x 列上找最长的一段连续墨迹，作为这条框线的上下跨度。
static int[]? RuleSpan(Bin2D block, int x)
{
    if (x < 0 || x >= block.W) return null;
    int cx0 = Math.Max(0, x - 1), cx1 = Math.Min(block.W, x + 2);
    var col = block.Slice(cx0, 0, cx1, block.H).RowAny();
    int[]? best = null;
    int s = -1;
    for (int i = 0; i < col.Length; i++)
    {
        if (col[i]) { if (s < 0) s = i; }
        else { if (s >= 0) { if (best == null || i - s > best[1] - best[0]) best = new[] { s, i }; s = -1; } }
    }
    if (s >= 0 && (best == null || col.Length - s > best[1] - best[0])) best = new[] { s, col.Length };
    return best;
}

// 用框线自身的跨度确定单元格上下边界。
static int[]? CellY(Bin2D block, List<int> rules, int[] refer)
{
    var spans = new List<int[]>();
    foreach (int x in rules) { var sp = RuleSpan(block, x); if (sp != null) spans.Add(sp); }
    if (spans.Count == 0) return null;
    int y0 = spans.Min(sp => sp[0]);
    int y1 = spans.Max(sp => sp[1]);
    if (y1 - y0 < 10) return null;
    if (!(y0 - 6 <= refer[0] && refer[1] <= y1 + 6)) return null;
    int pad = (int)(0.35 * (refer[1] - refer[0]));
    return new[] { Math.Max(y0, refer[0] - pad), Math.Min(y1, refer[1] + pad) };
}

// 把一块区域按实际墨迹切成 4 个格子，按阅读顺序返回。
static Built? Partition(Bin2D block)
{
    var b = StripRules(block);
    int rh = b.H, rw = b.W;
    if (rh < 8 || rw < 8) return null;
    var rg = OnGroups(b.RowSums(), 1, 5, rh);
    rg = rg.Where(g => (g[1] - g[0]) >= 5).ToList();
    if (rg.Count == 0) return null;
    var inks = new List<int>();
    foreach (var g in rg) inks.Add(b.Slice(0, g[0], rw, g[1]).Sum());
    int argmax = 0;
    for (int i = 1; i < inks.Count; i++) if (inks[i] > inks[argmax]) argmax = i;
    var main = rg[argmax];
    var vr = VRulePositions(block);
    var refer = main;
    if (!(3 <= vr.Count && vr.Count <= 5))
    {
        foreach (var g in rg.OrderByDescending(g => g[1] - g[0]).ToList())
        {
            var v = VRulePositions(block.Slice(0, g[0], block.W, g[1]));
            if (3 <= v.Count && v.Count <= 5) { vr = v; refer = g; break; }
        }
    }
    if (vr.Count == 5)
    {
        var span = CellY(block, vr, refer) ?? main;
        var cells = new List<int[]>();
        for (int j = 0; j < 4; j++) cells.Add(new[] { vr[j] + 3, span[0], vr[j + 1] - 3, span[1] });
        if (cells.All(c => c[2] - c[0] >= 12)) return new Built(1, 4, cells);
    }
    if (vr.Count == 2)
    {
        var span = CellY(block, vr, main) ?? main;
        int width = vr[1] - vr[0];
        if (width >= 160)
        {
            double step = width / 4.0;
            var cells = new List<int[]>();
            for (int j = 0; j < 4; j++)
                cells.Add(new[] { (int)(vr[0] + j * step) + 2, span[0],
                                  (int)(vr[0] + (j + 1) * step) - 2, span[1] });
            if (cells.All(c => c[2] - c[0] >= 12)) return new Built(1, 4, cells);
        }
    }
    if (vr.Count == 3 && rg.Count >= 2)
    {
        var two = rg.OrderByDescending(g => b.Slice(0, g[0], rw, g[1]).Sum()).Take(2)
                    .OrderBy(g => g[0]).ToList();
        var cells = new List<int[]>();
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++)
                cells.Add(new[] { vr[j] + 3, two[i][0], vr[j + 1] - 3, two[i][1] });
        if (cells.All(c => c[2] - c[0] >= 12)) return new Built(2, 2, cells);
    }
    int nr = rg.Count;
    bool textLike = rg.Max(g => g[1] - g[0]) <= 34;
    var perRow = rg.Select(g => ColGroups(b.Slice(0, g[0], rw, g[1]), rw, textLike)).ToList();
    if (nr == 1)
    {
        var cg = perRow[0];
        if (!(3 <= cg.Count && cg.Count <= 6)) return null;
        var cols = FitGroups(cg, 4);
        if (cols == null) return null;
        int y0 = rg[0][0], y1 = rg[0][1];
        var cells = new List<int[]>();
        for (int j = 0; j < 4; j++) cells.Add(new[] { cols[j][0], y0, cols[j][1], y1 });
        return new Built(1, 4, cells);
    }
    if (nr == 2)
    {
        var g0 = perRow[0]; var g1 = perRow[1];
        if (!(2 <= g0.Count && g0.Count <= 3 && 2 <= g1.Count && g1.Count <= 3)) return null;
        var c0 = FitGroups(g0, 2); var c1 = FitGroups(g1, 2);
        if (c0 == null || c1 == null) return null;
        var cells = new List<int[]>();
        for (int j = 0; j < 2; j++) cells.Add(new[] { c0[j][0], rg[0][0], c0[j][1], rg[0][1] });
        for (int j = 0; j < 2; j++) cells.Add(new[] { c1[j][0], rg[1][0], c1[j][1], rg[1][1] });
        return new Built(2, 2, cells);
    }
    if (nr == 4)
    {
        if (!perRow.All(g => 1 <= g.Count && g.Count <= 2)) return null;
        var widths = new List<int[]>();
        foreach (var g in perRow) widths.Add(FitGroups(g, 1)![0]);
        var cells = new List<int[]>();
        for (int i = 0; i < 4; i++) cells.Add(new[] { widths[i][0], rg[i][0], widths[i][1], rg[i][1] });
        return new Built(4, 1, cells);
    }
    return null;
}

// 四个格子都只是「A/B/C/D 这种单字母标签」——不是选项本身。
static bool IsLabelRow(List<int[]> opts)
{
    bool ok = true;
    foreach (var b in opts)
    {
        int bw = b[2] - b[0], bh = b[3] - b[1];
        if (bw > 30 || bh > 26) { ok = false; break; }
    }
    return ok;
}

static bool LooksLikeOptions(List<int[]> opts, Bin2D binFull, int w, int h)
{
    if (opts.Count != 4) return false;
    var inks = new List<int>(); var ws = new List<int>(); var hs = new List<int>();
    foreach (var b in opts)
    {
        int x0 = b[0], y0 = b[1], x1 = b[2], y1 = b[3];
        if (x1 <= x0 || y1 <= y0) return false;
        inks.Add(binFull.Slice(x0, y0, x1, y1).Sum());
        ws.Add(x1 - x0); hs.Add(y1 - y0);
    }
    if (inks.Min() < 40 || ws.Min() < 8 || hs.Min() < 6) return false;
    if (inks.Max() > 0 && inks.Min() / (double)inks.Max() < 0.15) return false;
    if (hs.Max() > 0 && hs.Min() / (double)hs.Max() < 0.25) return false;
    if (opts.Min(b => b[1]) < 0.30 * h) return false;
    return true;
}

// 自下而上拼「选项块」并打分选最优。
static Found? FindOptionBlock(Bin2D binFull, int w, int h)
{
    var bands = DropRules(InkBands(binFull, w), binFull, w);
    bands = SplitAtRules(bands, binFull);
    while (bands.Count > 0)
    {
        var last = bands[^1];
        if (IsLabelBand(binFull.Slice(0, last[0], w, last[1]), w)) { bands.RemoveAt(bands.Count - 1); continue; }
        break;
    }
    List<int[]>? bestOpts = null;
    int bestNrow = 0, bestNcol = 0, bestTop = 0, bestK = 0;
    double bestBal = 0;
    for (int k = 1; k <= bands.Count; k++)
    {
        var block = bands.GetRange(bands.Count - k, k);
        int top = block[0][0], bot = block[^1][1];
        if (bot - top > 0.88 * h) break;
        var built = Partition(binFull.Slice(0, top, w, bot));
        if (built == null) continue;
        var opts = new List<int[]>();
        foreach (var c in built.Cells)
            opts.Add(TightenOpt(binFull, new[] { c[0], top + c[1], c[2], top + c[3] }));
        if (!LooksLikeOptions(opts, binFull, w, h)) continue;
        var inks = opts.Select(b => binFull.Slice(b[0], b[1], b[2], b[3]).Sum()).ToList();
        int mx = inks.Max();
        double bal = mx > 0 ? inks.Min() / (double)mx : 0.0;
        double balR = Math.Round(bal, 3);
        if (bestOpts == null || balR > bestBal || (balR == bestBal && k < bestK))
        {
            bestOpts = opts; bestNrow = built.Nrow; bestNcol = built.Ncol; bestTop = top;
            bestBal = balR; bestK = k;
        }
    }
    return bestOpts == null ? null : new Found(bestOpts, bestNrow, bestNcol, bestTop);
}

static SplitResult SplitQuestion(string path)
{
    var binFull = Binarize(path);
    int w = binFull.W, h = binFull.H;
    var found = FindOptionBlock(binFull, w, h);
    if (found != null)
    {
        var options = found.Opts;
        int nrow = found.Nrow, ncol = found.Ncol;
        int optTop = options.Min(b => b[1]);
        int stemBottom = Math.Max(0, optTop - 2);
        if (stemBottom < 0.10 * h) stemBottom = (int)(0.10 * h);
        var stemBox = Tighten(binFull, new[] { 0, 0, w, stemBottom });
        if (stemBox[3] - stemBox[1] < 20)
            stemBox = new[] { 0, 0, w, Math.Max(20, (int)(0.10 * h)) };
        if (stemBox[3] > optTop) stemBox[3] = optTop;
        var areas = options.Select(b => binFull.Slice(b[0], b[1], b[2], b[3]).Sum()).ToList();
        int mx = areas.Count > 0 ? areas.Max() : 0;
        bool lowConf = mx == 0 || (areas.Min() / (double)mx) < 0.30;
        return new SplitResult(stemBox, options, $"{nrow}x{ncol}", "block", w, h, lowConf);
    }
    return SplitLegacy(path);
}

static SplitResult SplitLegacy(string path)
{
    var binFull = Binarize(path);
    int w = binFull.W, h = binFull.H;
    var rowAny = binFull.RowAny();
    var rowInk = binFull.RowSums();
    var hasInk = new bool[h];
    for (int y = 0; y < h; y++) hasInk[y] = rowInk[y] >= 0.05 * w;
    var cand = BlankRuns(hasInk, 6)
        .Where(r => r[0] >= 0.30 * h && r[1] <= 0.97 * h).ToList();
    int? chTop = null, chNegMin = null;
    int chNrow = 0, chNcol = 0;
    List<int[]>? chCells = null;
    foreach (var ce in cand.OrderByDescending(r => r[0]))
    {
        int top = ce[1];
        var region = binFull.Slice(0, top, w, h);
        if (region.H < 0.18 * h) continue;
        var built = BuildCells(region, top, w, h);
        if (built == null) continue;
        var areas = built.Cells.Select(c => binFull.Slice(c[0], c[1], c[2], c[3]).Sum()).ToList();
        int minA = areas.Min();
        if (minA < 150) continue;
        if (chTop == null || top > chTop.Value ||
            (top == chTop.Value && -minA > chNegMin!.Value))
        {
            chTop = top; chNegMin = -minA;
            chNrow = built.Nrow; chNcol = built.Ncol; chCells = built.Cells;
        }
    }
    string method = "band";
    int optTop, nrow, ncol, stemBottom;
    List<int[]> ordered;
    if (chTop != null)
    {
        optTop = chTop.Value;
        nrow = chNrow; ncol = chNcol; ordered = chCells!;
        stemBottom = Math.Max(0, optTop);
    }
    else
    {
        int inkBottom = h - 1;
        for (int y = h - 1; y >= 0; y--) if (rowAny[y]) { inkBottom = y; break; }
        optTop = Math.Min((int)(0.55 * h), inkBottom);
        var built = BuildCells(binFull.Slice(0, optTop, w, h), optTop, w, h);
        if (built == null)
        {
            nrow = 1; ncol = 4;
            int cw = w / 4;
            ordered = new List<int[]>();
            for (int i = 0; i < 4; i++) ordered.Add(new[] { i * cw, optTop, (i + 1) * cw, h });
        }
        else { nrow = built.Nrow; ncol = built.Ncol; ordered = built.Cells; }
        stemBottom = Math.Max(0, optTop);
        method = "fallback_region";
    }
    var options = ordered.Take(4)
        .Select(c => Tighten(binFull, new[] { c[0], c[1], c[2], c[3] })).ToList();

    List<int> AreaOf(List<int[]> opts) =>
        opts.Select(b => binFull.Slice(b[0], b[1], b[2], b[3]).Sum()).ToList();
    double Balance(List<int[]> opts)
    {
        var a = AreaOf(opts);
        int mx = a.Count > 0 ? a.Max() : 0;
        return mx > 0 ? a.Min() / (double)mx : 0.0;
    }

    // 判成 2×2 但四格高度差太大（1×4 被误切成两半）-> 按 1×4 重切
    if (nrow == 2)
    {
        var hs = options.Select(b => b[3] - b[1]).ToList();
        if (hs.Min() < 0.55 * hs.Max())
        {
            var built = BuildCells(binFull.Slice(0, optTop, w, h), optTop, w, h, force: "1x4");
            if (built != null)
            {
                nrow = built.Nrow; ncol = built.Ncol; ordered = built.Cells;
                options = ordered.Take(4)
                    .Select(c => Tighten(binFull, new[] { c[0], c[1], c[2], c[3] })).ToList();
            }
        }
    }
    // 均衡度重试：四格严重不均时，试试另一种版式，取更均衡的
    if (Balance(options) < 0.30)
    {
        string alt = ncol == 4 ? "2x2" : "1x4";
        var built = BuildCells(binFull.Slice(0, optTop, w, h), optTop, w, h, force: alt);
        if (built != null)
        {
            int n2r = built.Nrow, n2c = built.Ncol;
            var opts2 = built.Cells
                .Select(c => Tighten(binFull, new[] { c[0], c[1], c[2], c[3] })).ToList();
            if (Balance(opts2) > Balance(options)) { nrow = n2r; ncol = n2c; options = opts2; }
        }
    }
    var areas2 = AreaOf(options);
    bool lowConf = (areas2.Min() < 150) ||
                   (areas2.Max() > 0 && areas2.Min() / (double)areas2.Max() < 0.2);
    if (stemBottom < 0.12 * h) stemBottom = (int)(0.12 * h);
    var stemBox = Tighten(binFull, new[] { 0, 0, w, stemBottom });
    if (stemBox[3] - stemBox[1] < 20)
        stemBox = new[] { 0, 0, w, Math.Max(20, (int)(0.12 * h)) };
    return new SplitResult(stemBox, options, $"{nrow}x{ncol}", method, w, h, lowConf);
}

// 按 nrow×ncol 把选项区域切成网格，返回每个 cell 的原图坐标框。
static List<int[]> GridCells(Bin2D binOpt, int nrow, int ncol, int w, int sy0, int sy1)
{
    int hh = sy1 - sy0;
    var colEdges = new int[ncol + 1];
    for (int i = 0; i <= ncol; i++) colEdges[i] = (int)Math.Round((double)w * i / ncol);
    var rowEdges = new int[nrow + 1];
    for (int i = 0; i <= nrow; i++) rowEdges[i] = sy0 + (int)Math.Round((double)hh * i / nrow);
    var cells = new List<int[]>();
    for (int r = 0; r < nrow; r++)
        for (int c = 0; c < ncol; c++)
            cells.Add(new[] { colEdges[c], rowEdges[r], colEdges[c + 1], rowEdges[r + 1] });
    return cells;
}

// 按 (行, 列) 排序。
static List<int[]> OrderCells(List<int[]> cells)
{
    double Cy(int[] b) => (b[1] + b[3]) / 2.0;
    double Cx(int[] b) => (b[0] + b[2]) / 2.0;
    var cs = cells.OrderBy(b => Cy(b)).ToList();
    var rows = new List<List<int[]>>();
    var cur = new List<int[]> { cs[0] };
    foreach (var b in cs.Skip(1))
    {
        if (Math.Abs(Cy(b) - Cy(cur[0])) < 60) cur.Add(b);
        else { rows.Add(cur); cur = new List<int[]> { b }; }
    }
    rows.Add(cur);
    var ordered = new List<int[]>();
    foreach (var r in rows) ordered.AddRange(r.OrderBy(b => Cx(b)));
    return ordered;
}

// 把 SKBitmap 编码为 PNG 写到文件（对应原 SaveAsPng）。
static void SavePng(SKBitmap bmp, string path)
{
    using var img = SKImage.FromBitmap(bmp);
    using var data = img.Encode(SKEncodedImageFormat.Png, 100);
    File.WriteAllBytes(path, data.ToArray());
}

// 按 result 里的框裁出题干图与四张选项图并保存，返回产出路径。
static List<string> CropImages(string imgPath, SplitResult result, string outDir,
                               string stemName, string[] optNames)
{
    using var im = SKBitmap.Decode(imgPath);
    var paths = new List<string>();
    var sb = result.stem_box;
    using (var stem = CropRgb(im, sb[0], sb[1], sb[2], sb[3]))
    {
        var sp = Path.Combine(outDir, stemName);
        SavePng(stem, sp);
        paths.Add(sp);
    }
    int n = Math.Min(result.options.Count, optNames.Length);
    for (int i = 0; i < n; i++)
    {
        var b = result.options[i];
        using var opt = CropRgb(im, b[0], b[1], b[2], b[3]);
        var pp = Path.Combine(outDir, optNames[i]);
        SavePng(opt, pp);
        paths.Add(pp);
    }
    return paths;
}

static SKBitmap CropRgb(SKBitmap src, int x0, int y0, int x1, int y1)
{
    int w = Math.Max(1, x1 - x0), h = Math.Max(1, y1 - y0);
    var dst = new SKBitmap(w, h, SKColorType.Bgra8888, SKAlphaType.Premul);
    var black = new SKColor(0, 0, 0);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
        {
            int sx = x0 + x, sy = y0 + y;
            // 越界位置保持不透明黑，与原 new Image<Rgb24> 的零初始化一致。
            dst.SetPixel(x, y, sx >= 0 && sy >= 0 && sx < src.Width && sy < src.Height
                                    ? src.GetPixel(sx, sy) : black);
        }
    return dst;
}

static string FmtBox(int[] b) => "[" + string.Join(", ", b) + "]";

// ---------------- types ----------------

sealed class Bin2D
{
    public readonly byte[] A;
    public readonly int Stride;
    public readonly int X0, Y0;
    public readonly int W, H;

    public Bin2D(int w, int h)
    {
        A = new byte[w * h];
        Stride = w; X0 = 0; Y0 = 0; W = w; H = h;
    }
    private Bin2D(byte[] a, int stride, int x0, int y0, int w, int h)
    { A = a; Stride = stride; X0 = x0; Y0 = y0; W = w; H = h; }

    public byte this[int x, int y]
    {
        get => A[(Y0 + y) * Stride + (X0 + x)];
        set => A[(Y0 + y) * Stride + (X0 + x)] = value;
    }
    public Bin2D Slice(int x0, int y0, int x1, int y1)
        => new Bin2D(A, Stride, X0 + x0, Y0 + y0, x1 - x0, y1 - y0);
    public Bin2D Copy()
    {
        var c = new Bin2D(W, H);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) c[x, y] = this[x, y];
        return c;
    }
    public int Sum()
    {
        int s = 0;
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) s += this[x, y];
        return s;
    }
    public int[] RowSums()
    {
        var r = new int[H];
        for (int y = 0; y < H; y++)
        {
            int s = 0;
            for (int x = 0; x < W; x++) s += this[x, y];
            r[y] = s;
        }
        return r;
    }
    public int[] ColSums()
    {
        var c = new int[W];
        for (int x = 0; x < W; x++)
        {
            int s = 0;
            for (int y = 0; y < H; y++) s += this[x, y];
            c[x] = s;
        }
        return c;
    }
    public bool[] RowAny()
    {
        var b = new bool[H];
        for (int y = 0; y < H; y++)
        {
            bool on = false;
            for (int x = 0; x < W; x++) if (this[x, y] != 0) { on = true; break; }
            b[y] = on;
        }
        return b;
    }
    public bool[] ColAny()
    {
        var b = new bool[W];
        for (int x = 0; x < W; x++)
        {
            bool on = false;
            for (int y = 0; y < H; y++) if (this[x, y] != 0) { on = true; break; }
            b[x] = on;
        }
        return b;
    }
    // 返回 [minX, minY, maxX, maxY]（含端点），无墨返回 null。
    public int[]? Bounds()
    {
        int minX = W, minY = H, maxX = -1, maxY = -1;
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                if (this[x, y] != 0)
                {
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                }
        return maxX < 0 ? null : new[] { minX, minY, maxX, maxY };
    }
}

sealed class Built
{
    public readonly int Nrow, Ncol;
    public readonly List<int[]> Cells;
    public Built(int nrow, int ncol, List<int[]> cells) { Nrow = nrow; Ncol = ncol; Cells = cells; }
}

sealed class Found
{
    public readonly List<int[]> Opts;
    public readonly int Nrow, Ncol, Top;
    public Found(List<int[]> opts, int nrow, int ncol, int top)
    { Opts = opts; Nrow = nrow; Ncol = ncol; Top = top; }
}

// 对应 split_question 返回的 dict：stem_box / options / grid / method / size / low_conf
sealed class SplitResult
{
    public int[] stem_box;
    public List<int[]> options;
    public string grid;
    public string method;
    public int[] size;
    public bool low_conf;
    public SplitResult(int[] stemBox, List<int[]> options, string grid, string method,
                       int w, int h, bool lowConf)
    { stem_box = stemBox; this.options = options; this.grid = grid; this.method = method; size = new[] { w, h }; low_conf = lowConf; }
}

static class C
{
    public const int BIN_THRESH = 140;
    public const int BLOCK = 3;
    public const int BAND_MIN = 15;     // 候选分隔带最小高度
    public const int AREA_MIN = 200;    // figure 最小面积（原图像素）
    public const int MARGIN = 6;
    public const int MERGE_PAD = 8;
}
