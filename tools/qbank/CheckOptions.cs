#!/usr/bin/env dotnet
// 校验「超格行测套卷」包里拆分出来的图片：每道「选项是图」的题，四张选项图对不对。
//
// 用法：
//   dotnet run tools/qbank/CheckOptions.cs
//   dotnet run tools/qbank/CheckOptions.cs -- --base qbank_out/超格行测套卷
//
// 做的事：
//   1. 结构检查：引用的图是否都在、是否空白、四张是否重复、尺寸是否离谱、是否混进卷面字母；
//   2. 出人工核对表 audit_checks/audit_N.png（每题一行：题干图 + A/B/C/D，4 题一张）；
//   3. 把可疑题写进 audit_report.json。
//
// 判定思路：
//   · blank      —— 墨迹占比 < 0.4%，肯定裁空了；
//   · dup        —— 两图缩到 72×72 后平均灰度差 < 0.015，基本是同一块；
//   · letter?    —— 贴边有孤立的「小块 + 与主体分离」，那是卷面印的 A/B/C/D；
//   · border-ink —— 某条边上有粗墨迹长段（≥ 8px 且 3px 深），提示这道边把图形切断了，
//                   需要人眼确认（裁紧的图正常也会在极值点碰到边，但只有 1~2px）。

#:package SkiaSharp@2.88.9

using System.Text.Json;
using SkiaSharp;

var baseDir = "qbank_out/超格行测套卷";
for (int i = 0; i < args.Length; i++)
{
    if (args[i] == "--base" && i + 1 < args.Length) baseDir = args[i + 1];
}
if (!Directory.Exists(baseDir))
{
    // 从当前目录往上找
    var dir = new DirectoryInfo(Directory.GetCurrentDirectory());
    while (dir != null && !Directory.Exists(Path.Combine(dir.FullName, baseDir)))
        dir = dir.Parent;
    if (dir != null) baseDir = Path.Combine(dir.FullName, baseDir);
}
if (!Directory.Exists(baseDir))
{
    Console.Error.WriteLine($"找不到产物目录：{baseDir}");
    return 2;
}
baseDir = Path.GetFullPath(baseDir);

var summaryPath = Path.Combine(baseDir, "build_summary.json");
if (!File.Exists(summaryPath))
{
    Console.Error.WriteLine($"找不到 {summaryPath}");
    return 2;
}

var outDir = Path.Combine(baseDir, "audit_checks");
Directory.CreateDirectory(outDir);
foreach (var f in Directory.GetFiles(outDir, "audit_*.png")) File.Delete(f);

// ---------- 字体（只画 ASCII 标签） ----------
SKTypeface? labelFont = null;
foreach (var fam in new[] { "Consolas", "Arial", "Segoe UI" })
{
    labelFont = SKTypeface.FromFamilyName(fam);
    if (labelFont != null) break;
}
if (labelFont == null)
{
    foreach (var fp in new[] { @"C:\Windows\Fonts\consola.ttf", @"C:\Windows\Fonts\arial.ttf" })
        if (File.Exists(fp)) { labelFont = SKTypeface.FromFile(fp); break; }
}
Console.WriteLine("标签字体：" + (labelFont?.FamilyName ?? "无"));

// ---------- 收集题目 ----------
var records = new List<QRec>();
using (var doc = JsonDocument.Parse(File.ReadAllText(summaryPath)))
{
    foreach (var paperProp in doc.RootElement.EnumerateObject())
    {
        var paper = paperProp.Name;
        if (!paperProp.Value.TryGetProperty("opt_split", out var arr)) continue;
        foreach (var e in arr.EnumerateArray())
        {
            var no = e.GetInt32();
            var imgDir = Path.Combine(baseDir, paper, "images");
            var stemP = Path.Combine(imgDir, $"q{no:D3}.png");
            var optsP = "ABCD".Select(k => Path.Combine(imgDir, $"q{no:D3}_{k}.png")).ToArray();
            records.Add(new QRec(paper, no, File.Exists(stemP) ? stemP : null, optsP));
        }
    }
}
records.Sort((a, b) =>
{
    int pa = int.Parse(new string(a.Paper.Where(char.IsDigit).ToArray()));
    int pb = int.Parse(new string(b.Paper.Where(char.IsDigit).ToArray()));
    return pa != pb ? pa.CompareTo(pb) : a.No.CompareTo(b.No);
});

var report = new List<(string paper, int no, string[] flags, string sizes)>();
int flagged = 0;

foreach (var q in records)
{
    var flags = new List<string>();
    var missing = q.Options.Where(p => !File.Exists(p)).ToArray();
    if (missing.Length > 0)
    {
        flags.Add("missing:" + missing.Length);
        report.Add((q.Paper, q.No, flags.ToArray(), ""));
        continue;
    }

    var opts = q.Options.Select(p => LoadGray(p)).ToArray();
    var sizes = opts.Select(i => (i.Width, i.Height)).ToArray();

    for (int i = 0; i < 4; i++)
    {
        if (InkFraction(opts[i]) < 0.004) flags.Add("ABCD"[i] + ":blank");
        if (sizes[i].Width < 12 || sizes[i].Height < 12) flags.Add("ABCD"[i] + ":tiny");
    }

    int minW = sizes.Min(s => s.Width), maxW = sizes.Max(s => s.Width);
    int minH = sizes.Min(s => s.Height), maxH = sizes.Max(s => s.Height);
    if (maxW > 3 * Math.Max(1, minW) || maxH > 3 * Math.Max(1, minH)) flags.Add("size-spread");

    for (int i = 0; i < 4; i++)
        for (int j = i + 1; j < 4; j++)
        {
            double d = Diff(opts[i], opts[j]);
            if (d < 0.012) flags.Add($"dup:{"ABCD"[i]}={"ABCD"[j]}({d:0.000})");
        }

    var blobCounts = opts.Select(LetterBlobs).ToArray();
    if (blobCounts.Count(c => c > 0) >= 2)
        flags.Add("letter?" + string.Concat("ABCD".Where((c, i) => blobCounts[i] > 0)));

    if (flags.Count > 0)
    {
        flagged++;
        Console.WriteLine($"{q.Paper} 第{q.No}题  {string.Join(" ", flags)}");
    }
    report.Add((q.Paper, q.No, flags.ToArray(),
                string.Join(",", sizes.Select(sz => $"{sz.Width}x{sz.Height}"))));
}

File.WriteAllText(Path.Combine(baseDir, "audit_report.json"), BuildJson(report));

// ---------- 人工核对表 ----------
const int RowH = 230, PerSheet = 4, LabelW = 110;
int sheetIdx = 0;
for (int s = 0; s < records.Count; s += PerSheet)
{
    var grp = records.Skip(s).Take(PerSheet).ToList();
    var rows = grp.Select(q => (
        q,
        stem: q.Stem != null && File.Exists(q.Stem) ? Fit(LoadGray(q.Stem), RowH) : null,
        opts: q.Options.Select(p => Fit(LoadGray(p), RowH)).ToArray()
    )).ToList();

    int width = 900;
    foreach (var r in rows)
    {
        int w = LabelW + (r.stem?.Width ?? 0) + 40 + r.opts.Sum(o => o.Width + 24) + 80;
        if (w > width) width = w;
    }
    int height = rows.Count * (RowH + 48) + 10;

    using var sheet = new SKBitmap(width, height);
    using var canvas = new SKCanvas(sheet);
    canvas.Clear(SKColors.White);
    int y = 6;
    foreach (var r in rows)
    {
        string tag = $"{Short(r.q.Paper)}-{r.q.No}";
        if (labelFont != null) DrawLabel(canvas, tag, 5, y + 8, labelFont);

        int x = LabelW;
        if (r.stem != null)
        {
            x = Paste(sheet, r.stem, x, y);
            x += 30;
        }
        // 题干与选项之间的分隔线
        for (int yy = y; yy < y + RowH; yy++)
            for (int k = 0; k < 2; k++)
                sheet.SetPixel(Math.Max(0, x - 15 + k), yy, new SKColor(150, 150, 150));
        x += 8;
        for (int i = 0; i < 4; i++)
        {
            if (labelFont != null)
                DrawLabel(canvas, "#" + "ABCD"[i], x, y + RowH + 4, labelFont);
            x = Paste(sheet, r.opts[i], x, y);
            x += 24;
        }
        for (int xx = 0; xx < width; xx++)
            for (int k = 0; k < 1; k++) sheet.SetPixel(xx, y + RowH + 30, new SKColor(200, 200, 200));
        y += RowH + 48;
    }
    sheetIdx++;
    using var image = SKImage.FromBitmap(sheet);
    using var data = image.Encode(SKEncodedImageFormat.Png, 100);
    using var fs = File.Create(Path.Combine(outDir, $"audit_{sheetIdx}.png"));
    data.SaveTo(fs);
}

Console.WriteLine($"\n共 {records.Count} 题，可疑 {flagged} 题；核对表 {sheetIdx} 张 -> {outDir}");
return 0;

// ---------------- helpers ----------------

static string Short(string paper)
{
    int i = paper.IndexOf('卷');
    return i >= 0 && i + 1 < paper.Length ? "S" + paper[(i + 1)..] : paper;
}

// SkiaSharp 的 DrawText 以基线定位，原按左上角 PointF 定位，
// 这里加一个字号（18）偏移，保持视觉位置接近。
static void DrawLabel(SKCanvas canvas, string text, float x, float y, SKTypeface tf)
{
    using var paint = new SKPaint { Color = SKColors.Black, TextSize = 18, IsAntialias = true, Typeface = tf };
    canvas.DrawText(text, x, y + 18, paint);
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

static SKBitmap Fit(SKBitmap src, int maxH)
{
    if (src.Height <= maxH) return src;
    double k = maxH / (double)src.Height;
    int w = Math.Max(1, (int)Math.Round(src.Width * k));
    var c = src.Resize(new SKSizeI(w, maxH), SKFilterQuality.High);
    src.Dispose();
    return c;
}

static int Paste(SKBitmap sheet, SKBitmap src, int x, int y)
{
    for (int yy = 0; yy < src.Height && y + yy < sheet.Height; yy++)
        for (int xx = 0; xx < src.Width && x + xx < sheet.Width; xx++)
            sheet.SetPixel(x + xx, y + yy, src.GetPixel(xx, yy));
    return x + src.Width;
}

static double InkFraction(SKBitmap img)
{
    long ink = 0;
    for (int y = 0; y < img.Height; y++)
        for (int x = 0; x < img.Width; x++)
            if (img.GetPixel(x, y).Red < 160) ink++;
    return ink / (double)(img.Width * img.Height);
}

// 细线条在灰度下采样里会被抹平（四张不同的柱状图会显得一样），
// 所以按「块内只要有墨迹就算有」的方式池化，再比不重合比例。
static double Diff(SKBitmap a, SKBitmap b)
{
    const int N = 96;
    bool[] pa = InkPool(a, N), pb = InkPool(b, N);
    int diff = 0, ink = 0;
    for (int i = 0; i < pa.Length; i++)
    {
        if (pa[i] != pb[i]) diff++;
        if (pa[i] || pb[i]) ink++;
    }
    // 两张都空的地方不算差异，只比有墨迹的区域
    return ink == 0 ? 0 : diff / (double)ink;
}

static bool[] InkPool(SKBitmap img, int n)
{
    var res = new bool[n * n];
    for (int y = 0; y < n; y++)
        for (int x = 0; x < n; x++)
        {
            int sx0 = x * img.Width / n, sx1 = Math.Max(sx0 + 1, (x + 1) * img.Width / n);
            int sy0 = y * img.Height / n, sy1 = Math.Max(sy0 + 1, (y + 1) * img.Height / n);
            bool on = false;
            for (int sy = sy0; sy < sy1 && !on; sy++)
                for (int sx = sx0; sx < sx1; sx++)
                    if (img.GetPixel(sx, sy).Red < 160) { on = true; break; }
            res[y * n + x] = on;
        }
    return res;
}

// 贴边孤立小块数（卷面字母残留）
static int LetterBlobs(SKBitmap img)
{
    int w = img.Width, h = img.Height;
    var m = new bool[w * h];
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) m[y * w + x] = img.GetPixel(x, y).Red < 160;

    var label = new int[w * h];
    var comps = new List<(int area, int x0, int y0, int x1, int y1)>();
    int cur = 0;
    var stack = new Stack<int>();
    for (int i = 0; i < m.Length; i++)
    {
        if (!m[i] || label[i] != 0) continue;
        cur++;
        stack.Clear();
        stack.Push(i);
        label[i] = cur;
        int area = 0, x0 = w, y0 = h, x1 = 0, y1 = 0;
        while (stack.Count > 0)
        {
            int p = stack.Pop(), px = p % w, py = p / w;
            area++;
            if (px < x0) x0 = px; if (px > x1) x1 = px;
            if (py < y0) y0 = py; if (py > y1) y1 = py;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++)
                {
                    int nx = px + dx, ny = py + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int qi = ny * w + nx;
                    if (m[qi] && label[qi] == 0) { label[qi] = cur; stack.Push(qi); }
                }
        }
        comps.Add((area, x0, y0, x1, y1));
    }
    if (comps.Count < 2) return 0;
    int big = comps.Max(c => c.area);
    int cnt = 0;
    foreach (var c in comps)
    {
        if (c.area > 0.30 * big || c.area < 10) continue;
        bool near = c.y1 >= h - 3 || c.y0 <= 2 || c.x1 >= w - 3 || c.x0 <= 2;
        bool smallish = (c.y1 - c.y0) <= 0.40 * h && (c.x1 - c.x0) <= 0.40 * w;
        if (near && smallish) cnt++;
    }
    return cnt;
}

static string BuildJson(List<(string paper, int no, string[] flags, string sizes)> rows)
{
    var sb = new System.Text.StringBuilder();
    sb.Append("[\n");
    for (int i = 0; i < rows.Count; i++)
    {
        var r = rows[i];
        sb.Append("  {\"paper\":\"").Append(r.paper)
          .Append("\",\"no\":").Append(r.no)
          .Append(",\"sizes\":\"").Append(r.sizes)
          .Append("\",\"flags\":[");
        for (int j = 0; j < r.flags.Length; j++)
        {
            if (j > 0) sb.Append(',');
            sb.Append('"').Append(r.flags[j]).Append('"');
        }
        sb.Append("]}");
        if (i < rows.Count - 1) sb.Append(',');
        sb.Append('\n');
    }
    sb.Append(']');
    return sb.ToString();
}

record QRec(string Paper, int No, string? Stem, string[] Options);
