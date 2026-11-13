#!/usr/bin/env dotnet
// 把每道题的「选项裁剪框」画回原卷面，旁边配裁好的四张图 —— 一眼看出框有没有偏、有没有漏。
//
// 用法：
//   dotnet run tools/qbank/OverlayBoxes.cs
//   dotnet run tools/qbank/OverlayBoxes.cs -- --only 行测套卷9
//
// 依赖 build_summary.json 里的 items（该题分到的位图）与 boxes（裁剪框，画布坐标）。
// 画布按 build_exam_packages.cs 的 build_canvas 同样规则重拼：等比倍率 S、
// 最少留白 PAD=8，多块位图按 PDF 坐标对齐。
//
// 产出 audit_checks/overlay_N.png：每题一行，左侧原卷面（红框=A/B/C/D），右侧四张裁图。

#:package SkiaSharp@2.88.9

using System.Text.Json;
using SkiaSharp;

const int Pad = 8;

var baseDir = "qbank_out/超格行测套卷";
var imgRoot = "tmp/build/img";
string? only = null;
for (int i = 0; i < args.Length; i++)
{
    switch (args[i])
    {
        case "--base" when i + 1 < args.Length: baseDir = args[++i]; break;
        case "--img" when i + 1 < args.Length: imgRoot = args[++i]; break;
        case "--only" when i + 1 < args.Length: only = args[++i]; break;
    }
}
baseDir = Path.GetFullPath(baseDir);
imgRoot = Path.GetFullPath(imgRoot);
var outDir = Path.Combine(baseDir, "audit_checks");
Directory.CreateDirectory(outDir);

SKTypeface? font = null;
foreach (var fam in new[] { "Consolas", "Arial" })
{
    font = SKTypeface.FromFamilyName(fam);
    if (font != null) break;
}

var summary = JsonDocument.Parse(File.ReadAllText(Path.Combine(baseDir, "build_summary.json")));

var items = new List<(string paper, int no, string stem, string[] opts, List<(string file, double[] bbox)> parts, Dictionary<string, int[]> boxes)>();
foreach (var pp in summary.RootElement.EnumerateObject())
{
    var paper = pp.Name;
    if (only != null && paper != only) continue;
    if (!pp.Value.TryGetProperty("opt_info", out var oi)) continue;
    foreach (var q in oi.EnumerateArray())
    {
        if (!q.TryGetProperty("boxes", out var bx) || !q.TryGetProperty("items", out var its)) continue;
        var no = q.GetProperty("no").GetInt32();
        var parts = its.EnumerateArray().Select(e => (
            e.GetProperty("file").GetString()!,
            e.GetProperty("bbox").EnumerateArray().Select(v => v.GetDouble()).ToArray())).ToList();
        var boxes = new Dictionary<string, int[]>();
        foreach (var b in bx.EnumerateObject())
            boxes[b.Name] = b.Value.EnumerateArray().Select(v => v.GetInt32()).ToArray();
        var imgDir = Path.Combine(baseDir, paper, "images");
        var stem = Path.Combine(imgDir, $"q{no:D3}.png");
        var opts = "ABCD".Select(k => Path.Combine(imgDir, $"q{no:D3}_{k}.png")).ToArray();
        items.Add((paper, no, stem, opts, parts, boxes));
    }
}
items.Sort((a, b) =>
{
    int pa = int.Parse(new string(a.paper.Where(char.IsDigit).ToArray()));
    int pb = int.Parse(new string(b.paper.Where(char.IsDigit).ToArray()));
    return pa != pb ? pa.CompareTo(pb) : a.no.CompareTo(b.no);
});
Console.WriteLine($"题目 {items.Count} 道");

const int RowH = 265, PerSheet = 4, LabelW = 96;
int sheetIdx = 0;
var flagged = new List<string>();

for (int s = 0; s < items.Count; s += PerSheet)
{
    var grp = items.Skip(s).Take(PerSheet).ToList();
    var rows = new List<(string tag, SKBitmap canvas, SKBitmap[] crops)>();
    foreach (var q in grp)
    {
        SKBitmap? cv;
        try { cv = BuildCanvas(imgRoot, q.paper, q.parts); }
        catch (Exception ex) { Console.WriteLine($"{q.paper} {q.no} 重拼失败：{ex.Message}"); continue; }

        var rgb = NewWhite(cv.Width, cv.Height);
        using (var rc = new SKCanvas(rgb))
        {
            rc.DrawBitmap(cv, 0, 0);
            foreach (var (k, b) in q.boxes)
            {
                DrawBox(rc, b, SKColors.Red, 2f);
                if (font != null)
                    DrawLabel(rc, k, b[0] + 2, Math.Max(0, b[1] - 18), font, SKColors.Red, 15);
            }
            // 框外 3px 内还有墨迹 -> 十有八九把图形切断了
            var leftover = FindLeftover(cv, q.boxes, 3);
            if (leftover.Count > 0)
            {
                foreach (var b in leftover)
                    DrawBox(rc, b, SKColors.Blue, 2f);
                flagged.Add($"{q.paper} 第{q.no}题 框外残留 {leftover.Count} 块");
            }
        }
        cv.Dispose();

        var crops = q.opts.Select(p => File.Exists(p) ? LoadGray(p) : NewWhite(8, 8)).ToArray();
        rows.Add(($"{Short(q.paper)}-{q.no}", rgb, crops));
    }
    if (rows.Count == 0) continue;

    // 画布按高 RowH 缩放，右边贴四张裁图（高 RowH/2）
    var scaled = rows.Select(r =>
    {
        double k = Math.Min(1.0, RowH / (double)r.canvas.Height);
        var cv2 = r.canvas.Resize(new SKSizeI((int)(r.canvas.Width * k), (int)(r.canvas.Height * k)), SKFilterQuality.High);
        r.canvas.Dispose();
        var cps = r.crops.Select(p => Fit(p, RowH / 2)).ToArray();
        return (r.tag, cv2, cps, k);
    }).ToList();

    int width = scaled.Max(r => LabelW + r.cv2.Width + 30 + r.cps.Sum(c => c.Width + 14) + 40);
    using var sheet = NewWhite(Math.Max(width, 600), scaled.Count * (RowH + 40) + 10);
    using var canvas = new SKCanvas(sheet);
    int y = 5;
    foreach (var (tag, cv2, cps, _) in scaled)
    {
        if (font != null) DrawLabel(canvas, tag, 4, y + 4, font, SKColors.Black, 15);
        int x = LabelW;
        canvas.DrawBitmap(cv2, x, y);
        x += cv2.Width + 30;
        foreach (var (cp, i) in cps.Select((v, i) => (v, i)))
        {
            canvas.DrawBitmap(cp, x, y);
            if (font != null) DrawLabel(canvas, "ABCD"[i].ToString(), x, y + cp.Height + 2, font, SKColors.Black, 15);
            x += cp.Width + 14;
            cp.Dispose();
        }
        cv2.Dispose();
        using (var p = new SKPaint { Color = SKColors.Gray, Style = SKPaintStyle.Stroke, StrokeWidth = 1 })
            canvas.DrawLine(0, y + RowH + 20, sheet.Width, y + RowH + 20, p);
        y += RowH + 40;
    }
    sheetIdx++;
    using var image = SKImage.FromBitmap(sheet);
    using var data = image.Encode(SKEncodedImageFormat.Png, 100);
    using var fs = File.Create(Path.Combine(outDir, $"overlay_{sheetIdx}.png"));
    data.SaveTo(fs);
}
Console.WriteLine($"叠加图 {sheetIdx} 张 -> {outDir}");
Console.WriteLine("框外残留（蓝框标记）" + flagged.Count + " 题：");
foreach (var f in flagged) Console.WriteLine("  " + f);
return 0;

// ---------------- helpers ----------------

static string Short(string paper)
{
    int i = paper.IndexOf('卷');
    return i >= 0 && i + 1 < paper.Length ? "S" + paper[(i + 1)..] : paper;
}

static SKBitmap NewWhite(int w, int h)
{
    var b = new SKBitmap(w, h);
    using (var c = new SKCanvas(b)) c.Clear(SKColors.White);
    return b;
}

// 灰度位图统一用 Bgra8888 表示：R=G=B=灰度值、A=255（等价原 Image<L8> 单通道）。
// 灰度仍用原 PIL 权重公式 (R*19595+G*38470+B*7471)>>16，不改用 SkiaSharp 内置色彩转换。
static SKBitmap ToGray(SKBitmap src)
{
    var g = new SKBitmap(src.Width, src.Height);
    for (int y = 0; y < src.Height; y++)
        for (int x = 0; x < src.Width; x++)
        {
            var c = src.GetPixel(x, y);
            byte v = (byte)((c.Red * 19595 + c.Green * 38470 + c.Blue * 7471) >> 16);
            g.SetPixel(x, y, new SKColor(v, v, v, 255));
        }
    return g;
}

static SKBitmap LoadGray(string path)
{
    using var src = SKBitmap.Decode(path);
    return ToGray(src);
}

// SkiaSharp 的 DrawText 以基线定位，原按左上角 PointF 定位，
// 这里加一个字号偏移，保持视觉位置接近。
static void DrawLabel(SKCanvas canvas, string text, float x, float y, SKTypeface tf, SKColor color, float size)
{
    using var paint = new SKPaint { Color = color, TextSize = size, IsAntialias = true, Typeface = tf };
    canvas.DrawText(text, x, y + size, paint);
}

static void DrawBox(SKCanvas canvas, int[] b, SKColor color, float width)
{
    using var paint = new SKPaint { Color = color, Style = SKPaintStyle.Stroke, StrokeWidth = width };
    canvas.DrawRect(new SKRect(b[0], b[1], b[2], b[3]), paint);
}

static SKBitmap Fit(SKBitmap src, int maxH)
{
    if (src.Height <= maxH) return src;
    double k = maxH / (double)src.Height;
    int w = Math.Max(1, (int)Math.Round(src.Width * k));
    var c = src.Resize(new SKSizeI(w, maxH), SKFilterQuality.High);
    src.Dispose();
    return c;
}

// 框外 tol 像素内、且不属于任何框的墨迹块（形状被切断的痕迹）
static List<int[]> FindLeftover(SKBitmap canvas, Dictionary<string, int[]> boxes, int tol)
{
    int w = canvas.Width, h = canvas.Height;
    var ink = new bool[w * h];
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) ink[y * w + x] = canvas.GetPixel(x, y).Red < 160;
    // 框内（含 1px 容差）算已覆盖
    var inside = new bool[w * h];
    foreach (var b in boxes.Values)
    {
        int x0 = Math.Max(0, b[0] - 1), y0 = Math.Max(0, b[1] - 1);
        int x1 = Math.Min(w, b[2] + 1), y1 = Math.Min(h, b[3] + 1);
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) inside[y * w + x] = true;
    }
    // 贴着框边（tol 内）才算切断；离得远的（比如印的字母）不算
    var nearBox = new bool[w * h];
    foreach (var b in boxes.Values)
    {
        int x0 = Math.Max(0, b[0] - tol), y0 = Math.Max(0, b[1] - tol);
        int x1 = Math.Min(w, b[2] + tol), y1 = Math.Min(h, b[3] + tol);
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) nearBox[y * w + x] = true;
    }
    int bandTop = boxes.Values.Min(b => b[1]);
    int bandBottom = boxes.Values.Max(b => b[3]);
    int bandH = Math.Max(1, bandBottom - bandTop);
    int bandW = Math.Max(1, boxes.Values.Max(b => b[2]) - boxes.Values.Min(b => b[0]));

    var seen = new bool[w * h];
    var res = new List<int[]>();
    var stack = new Stack<int>();
    for (int i = 0; i < ink.Length; i++)
    {
        if (!ink[i] || inside[i] || seen[i]) continue;
        seen[i] = true;
        stack.Clear();
        stack.Push(i);
        int area = 0, x0b = w, y0b = h, x1b = 0, y1b = 0;
        bool touches = false;
        while (stack.Count > 0)
        {
            int p = stack.Pop(), px = p % w, py = p / w;
            area++;
            if (nearBox[p]) touches = true;
            if (px < x0b) x0b = px; if (px > x1b) x1b = px;
            if (py < y0b) y0b = py; if (py > y1b) y1b = py;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++)
                {
                    int nx = px + dx, ny = py + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int qi = ny * w + nx;
                    if (ink[qi] && !inside[qi] && !seen[qi]) { seen[qi] = true; stack.Push(qi); }
                }
        }
        bool belowBand = y0b >= bandBottom - 1;
        bool aboveBand = y1b <= bandTop + 1;
        bool letterSized = (y1b - y0b) <= 0.25 * bandH && (x1b - x0b) <= 0.30 * bandW;
        if (area >= 20 && touches && !((belowBand || aboveBand) && letterSized))
            res.Add(new[] { x0b, y0b, x1b + 1, y1b + 1 });
    }
    return res;
}

// 与 build_exam_packages.cs 的 build_canvas 同规则重拼
static SKBitmap BuildCanvas(string imgRoot, string paper, List<(string file, double[] bbox)> parts)
{
    var imgs = parts.Select(p => LoadGray(Path.Combine(imgRoot, paper, p.file))).ToList();
    if (parts.Count == 1) return imgs[0];

    var scales = parts.Select((p, i) => imgs[i].Width / Math.Max(1e-6, p.bbox[2] - p.bbox[0])).OrderBy(v => v).ToArray();
    double S = Math.Max(1.0, scales[scales.Length / 2]);
    double x0 = parts.Min(p => p.bbox[0]), y0 = parts.Min(p => p.bbox[1]);
    int W = (int)((parts.Max(p => p.bbox[2]) - x0) * S) + 2 * Pad;
    int H = (int)((parts.Max(p => p.bbox[3]) - y0) * S) + 2 * Pad;
    var canvas = NewWhite(W, H);
    using (var cc = new SKCanvas(canvas))
    {
        for (int i = 0; i < parts.Count; i++)
        {
            var p = parts[i];
            int tw = Math.Max(1, (int)((p.bbox[2] - p.bbox[0]) * S));
            int th = Math.Max(1, (int)((p.bbox[3] - p.bbox[1]) * S));
            int px = (int)((p.bbox[0] - x0) * S) + Pad;
            int py = (int)((p.bbox[1] - y0) * S) + Pad;
            var piece = imgs[i].Resize(new SKSizeI(tw, th), SKFilterQuality.High);
            cc.DrawBitmap(piece, px, py);
            piece.Dispose();
            imgs[i].Dispose();
        }
    }
    return canvas;
}
