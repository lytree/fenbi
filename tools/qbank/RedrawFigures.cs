#!/usr/bin/env dotnet
// 图推题图片「自绘」：能结构化的按几何重画，其余做清理式重绘；统一 1:1 + 8px 留白。
//
//   dotnet run tools/qbank/RedrawFigures.cs
//   dotnet run tools/qbank/RedrawFigures.cs -- --paper 行测套卷1 --sheet
//
// 策略（逐张尝试，按「与原图的像素差异」择优）：
//   1. dots  圆点阵：行列穷举 + 每格判实心/空心，按圆心半径重画；
//   2. grid  网格填色：找等距横竖线，逐格判断填色，重画线 + 填充；
//   3. bitmap 其余：二值化 + 去灰 + 8px 留白（保持原样，只是干净）
//
// 产出 tmp/build/redraw/<paper>/<name>.png + report.json（含每张的处理方式与差异率）

#:property PublishAot=false
#:package SixLabors.ImageSharp@3.1.5
#:package SixLabors.ImageSharp.Drawing@2.1.4

using System.Text.Json;
using SixLabors.Fonts;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.Drawing.Processing;
using SixLabors.ImageSharp.PixelFormats;
using SixLabors.ImageSharp.Processing;
using Draw = SixLabors.ImageSharp.Drawing;

const int Pad = 8;

var baseDir = "qbank_out/超格行测套卷";
string? onlyPaper = null;
bool sheet = false;
for (int i = 0; i < args.Length; i++)
{
    if (args[i] == "--base" && i + 1 < args.Length) baseDir = args[++i];
    else if (args[i] == "--paper" && i + 1 < args.Length) onlyPaper = args[++i];
    else if (args[i] == "--sheet") sheet = true;
}
baseDir = Path.GetFullPath(baseDir);
var root = Directory.GetParent(baseDir)!.Parent!.FullName;
var outRoot = Path.Combine(root, "tmp", "build", "redraw");
Directory.CreateDirectory(outRoot);

var summary = JsonDocument.Parse(File.ReadAllText(Path.Combine(baseDir, "build_summary.json")));
var jobs = new List<(string paper, int no, string slot, string path)>();
foreach (var pp in summary.RootElement.EnumerateObject())
{
    var paper = pp.Name;
    if (onlyPaper != null && paper != onlyPaper) continue;
    if (!pp.Value.TryGetProperty("opt_split", out var arr)) continue;
    var imgDir = Path.Combine(baseDir, paper, "images");
    foreach (var e in arr.EnumerateArray())
    {
        int no = e.GetInt32();
        string stem = Path.Combine(imgDir, $"q{no:D3}.png");
        if (File.Exists(stem)) jobs.Add((paper, no, "stem", stem));
        foreach (var k in "ABCD")
        {
            var p = Path.Combine(imgDir, $"q{no:D3}_{k}.png");
            if (File.Exists(p)) jobs.Add((paper, no, k.ToString(), p));
        }
    }
}
Console.WriteLine($"待处理 {jobs.Count} 张");

if (args.Contains("--selftest"))
{
    var probe = jobs[0];
    using var pimg = Image.Load<L8>(probe.path);
    var pink = Ink(pimg);
    Console.WriteLine($"自检 {probe.path} {pimg.Width}x{pimg.Height} ink={pink.Count(v => v) / (double)pink.Length:P2}");
    Console.WriteLine($"  自身差异（应≈0）: {Diff(pink, pimg, 1):P2}");
    using var white = new Image<L8>(pimg.Width, pimg.Height, new L8(255));
    Console.WriteLine($"  与全白差异（应≈墨占比）: {Diff(pink, white, 1):P2}");
    return 0;
}

var report = new List<string>();
var stat = new Dictionary<string, int>();
var rows = new List<(string paper, int no, string slot, string src, string dst, string kind)>();

foreach (var job in jobs)
{
    var dir = Path.Combine(outRoot, job.paper);
    Directory.CreateDirectory(dir);
    var name = Path.GetFileName(job.path);
    var dst = Path.Combine(dir, name);

    using var src = Image.Load<L8>(job.path);
    var ink = Ink(src);
    var (kind, outImg, diff) = TryRender(ink, src.Width, src.Height);

    outImg.SaveAsPng(dst);
    outImg.Dispose();

    stat[kind] = stat.GetValueOrDefault(kind) + 1;
    report.Add($"{{\"key\":\"{job.paper}:{job.no}:{job.slot}\",\"kind\":\"{kind}\",\"diff\":{diff:F3}}}");
    rows.Add((job.paper, job.no, job.slot, job.path, dst, kind));
}

File.WriteAllText(Path.Combine(outRoot, "report.json"), "[\n" + string.Join(",\n", report.Select(r => "  " + r)) + "\n]");
Console.WriteLine();
foreach (var kv in stat.OrderByDescending(k => k.Value)) Console.WriteLine($"  {kv.Key,-8} {kv.Value}");
File.WriteAllText(Path.Combine(outRoot, "stat.txt"),
    string.Join("\n", stat.OrderByDescending(k => k.Value).Select(kv => $"{kv.Key}\t{kv.Value}")));
Console.WriteLine($"-> {outRoot}");

if (sheet) MakeSheet(rows, Path.Combine(root, "tmp", "build", "montage"), outRoot);
return 0;

// ================= 判别与渲染 =================

static (string kind, Image<L8> img, double diff) TryRender(bool[] ink, int w, int h)
{
    var dots = TryDots(ink, w, h, out double dDiff);
    if (dots != null && dDiff < 0.16) return ("dots", dots, dDiff);

    var grid = TryGrid(ink, w, h, out double gDiff);
    if (grid != null && gDiff < 0.18) return ("grid", grid, gDiff);

    var clean = Clean(ink, w, h);
    return ("bitmap", clean, 0.0);
}

// ---------- 圆点阵 ----------

static Image<L8>? TryDots(bool[] ink, int w, int h, out double bestDiff)
{
    bestDiff = 1.0;
    var body = (bool[])ink.Clone();
    for (int y = 0; y < h; y++) if (MaxRun(body, y * w, 1, w) >= 0.9 * w) for (int x = 0; x < w; x++) body[y * w + x] = false;
    for (int x = 0; x < w; x++) if (MaxRun(body, x, w, h) >= 0.9 * h) for (int y = 0; y < h; y++) body[y * w + x] = false;

    int x0 = w, y0 = h, x1 = -1, y1 = -1;
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
            if (body[y * w + x]) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    if (x1 < 0) return null;
    int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
    if (bw < 40 || bh < 40) return null;

    Image<L8>? best = null;
    var bestCells = new List<(double cx, double cy, int kind)>();
    double bestR = 1;
    for (int cols = 4; cols <= 10; cols++)
        for (int rows = 4; rows <= 10; rows++)
        {
            double dx = bw / (double)cols, dy = bh / (double)rows;
            if (Math.Abs(dx - dy) / Math.Max(dx, dy) > 0.22) continue;
            double r = 0.5 * Math.Min(dx, dy);
            var cells = new List<(double cx, double cy, int kind)>();
            int strong = 0, total = cols * rows;
            // 点阵的独有特征：四个圆交界处是白的；方格填色图那里是黑线交点
            int cornerWhite = 0, cornerN = 0;
            for (int j = 0; j < rows; j++)
                for (int i = 0; i < cols; i++)
                {
                    double cx = x0 + (i + 0.5) * dx, cy = y0 + (j + 0.5) * dy;
                    double core = Disk(ink, w, h, cx, cy, r * 0.45);
                    double ring = Ring(ink, w, h, cx, cy, r * 0.78, r * 1.02);
                    // 格心必须「非黑即白」：落在中间说明网格没对准，或者根本不是点阵
                    if (core > 0.92 || core < 0.08) strong++;
                    int kind = core > 0.75 ? 2 : (core < 0.12 && ring > 0.20 ? 1 : 0);
                    if (kind > 0) cells.Add((cx, cy, kind));
                }
            for (int j = 0; j < rows; j++)
                for (int i = 0; i < cols; i++)
                {
                    double gx = x0 + i * dx, gy = y0 + j * dy;
                    if (gx < 3 || gy < 3 || gx > w - 4 || gy > h - 4) continue;
                    cornerN++;
                    if (Disk(ink, w, h, gx, gy, 2.2) < 0.15) cornerWhite++;
                }
            if (cornerN > 0 && cornerWhite / (double)cornerN < 0.70) continue;

            // 硬判据：绝大多数格心清晰 + 格子别太空 + 至少 4×4
            if (strong < 0.85 * total) continue;
            if (cells.Count < 0.45 * total) continue;

            var cand = RenderDots(w, h, cells, r + 0.6);
            double dv = Diff(ink, cand, 1);
            if (dv < bestDiff) { best?.Dispose(); best = cand; bestDiff = dv; bestCells = cells; bestR = r; }
            else cand.Dispose();
        }
    if (best != null && bestDiff < 0.35)
    {
        best.Dispose();
        return RenderDots(w, h, bestCells, bestR, Pad);
    }
    best?.Dispose();
    return null;
}

static Image<L8> RenderDots(int w, int h, List<(double cx, double cy, int kind)> cells, double r, int pad = 0)
{
    var img = new Image<L8>(w + 2 * pad, h + 2 * pad, new L8(255));
    cells = cells.Select(c => (c.cx + pad, c.cy + pad, c.kind)).ToList();
    int stroke = Math.Max(2, (int)Math.Round(r * 0.34));
    var filled = new Draw.PathCollection(cells.Where(c => c.kind == 2)
        .Select(c => (Draw.IPath)new Draw.EllipsePolygon((float)c.cx, (float)c.cy, (float)r)).ToList());
    var hollow = new Draw.PathCollection(cells.Where(c => c.kind == 1)
        .Select(c => (Draw.IPath)new Draw.EllipsePolygon((float)c.cx, (float)c.cy, (float)r)).ToList());
    if (filled.Any()) img.Mutate(c => c.Fill(new SolidBrush(Color.Black), filled));
    if (hollow.Any()) img.Mutate(c => c.Draw(new SolidPen(Color.Black, stroke), hollow));
    return img;
}

// ---------- 网格填色 ----------

static Image<L8>? TryGrid(bool[] ink, int w, int h, out double bestDiff)
{
    bestDiff = 1.0;
    var hl = new List<int>();
    var vl = new List<int>();
    for (int y = 0; y < h; y++) if (MaxRun(ink, y * w, 1, w) >= 0.7 * w) hl.Add(y);
    for (int x = 0; x < w; x++) if (MaxRun(ink, x, w, h) >= 0.7 * h) vl.Add(x);
    var hy = Group(hl);
    var vx = Group(vl);
    if (hy.Count < 3 || vx.Count < 3 || hy.Count > 12 || vx.Count > 12) return null;

    var img = new Image<L8>(w, h, new L8(255));
    int stroke = Math.Max(1, (int)Math.Round(hy.Zip(hy.Skip(1), (a, b) => b - a).DefaultIfEmpty(20).Min() * 0.03));
    var pens = new SolidPen(Color.Black, Math.Max(1, stroke));

    // 逐格判断填色（检查格心 60% 区域的墨量）
    int filled = 0;
    for (int j = 0; j + 1 < hy.Count; j++)
        for (int i = 0; i + 1 < vx.Count; i++)
        {
            int xa = vx[i] + 2, xb = vx[i + 1] - 2, ya = hy[j] + 2, yb = hy[j + 1] - 2;
            if (xb <= xa || yb <= ya) continue;
            double v = CellInk(ink, w, xa, ya, xb, yb);
            if (v > 0.55)
            {
                filled++;
                int a = xa, b2 = xb, c = ya, d = yb;
                img.Mutate(x => x.Fill(new SolidBrush(Color.Black),
                    new Draw.RectangularPolygon(a, c, b2 - a, d - c)));
            }
        }
    if (filled == 0) { img.Dispose(); return null; }

    var pb = new Draw.PathBuilder();
    foreach (var y in hy) pb.AddSegment(new Draw.LinearLineSegment(new PointF(0, y), new PointF(w - 1, y)));
    foreach (var x in vx) pb.AddSegment(new Draw.LinearLineSegment(new PointF(x, 0), new PointF(x, h - 1)));
    var grid = pb.Build();
    img.Mutate(c => c.Draw(pens, grid));

    bestDiff = Diff(ink, img, 2);
    if (bestDiff >= 0.18) { img.Dispose(); return null; }
    return img;
}

static double CellInk(bool[] m, int w, int xa, int ya, int xb, int yb)
{
    int on = 0, tot = 0;
    for (int y = ya; y <= yb; y++)
        for (int x = xa; x <= xb; x++) { tot++; if (m[y * w + x]) on++; }
    return tot == 0 ? 0 : on / (double)tot;
}

// ---------- 清理式重绘 ----------

static Image<L8> Clean(bool[] ink, int w, int h)
{
    var img = new Image<L8>(w, h, new L8(255));
    var pts = new List<(int x, int y)>();
    int xa = w, ya = h, xb = -1, yb = -1;
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
            if (ink[y * w + x]) { if (x < xa) xa = x; if (x > xb) xb = x; if (y < ya) ya = y; if (y > yb) yb = y; }
    if (xb < 0) return img;
    // 直接按像素重画（3x3 邻域平滑一次，去掉孤立噪点）
    var smooth = (bool[])ink.Clone();
    for (int y = 1; y < h - 1; y++)
        for (int x = 1; x < w - 1; x++)
        {
            if (!ink[y * w + x]) continue;
            int nb = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) if (ink[(y + dy) * w + x + dx]) nb++;
            if (nb <= 2) smooth[y * w + x] = false;
        }
    var outImg = new Image<L8>(xb - xa + 1 + 2 * Pad, yb - ya + 1 + 2 * Pad, new L8(255));
    for (int y = ya; y <= yb; y++)
        for (int x = xa; x <= xb; x++)
            if (smooth[y * w + x]) outImg[x - xa + Pad, y - ya + Pad] = new L8(0);
    img.Dispose();
    return outImg;
}

// ================= 工具 =================

static bool[] Ink(Image<L8> img)
{
    var m = new bool[img.Width * img.Height];
    img.ProcessPixelRows(a =>
    {
        for (int y = 0; y < a.Height; y++)
        {
            var row = a.GetRowSpan(y);
            for (int x = 0; x < a.Width; x++) m[y * a.Width + x] = row[x].PackedValue < 160;
        }
    });
    return m;
}

static int MaxRun(bool[] m, int start, int step, int n)
{
    int best = 0, cur = 0;
    for (int i = 0; i < n; i++) { cur = m[start + i * step] ? cur + 1 : 0; if (cur > best) best = cur; }
    return best;
}

static List<int> Group(List<int> idx)
{
    var res = new List<int>();
    foreach (var v in idx)
        if (res.Count == 0 || v - res[^1] > 3) res.Add(v);
    return res;
}

static double Disk(bool[] m, int w, int h, double cx, double cy, double r)
{
    int xa = Math.Max(0, (int)(cx - r)), xb = Math.Min(w - 1, (int)(cx + r));
    int ya = Math.Max(0, (int)(cy - r)), yb = Math.Min(h - 1, (int)(cy + r));
    int on = 0, tot = 0;
    for (int y = ya; y <= yb; y++)
        for (int x = xa; x <= xb; x++)
        {
            double ax = x - cx, ay = y - cy;
            if (ax * ax + ay * ay > r * r) continue;
            tot++; if (m[y * w + x]) on++;
        }
    return tot == 0 ? 0 : on / (double)tot;
}

static double Ring(bool[] m, int w, int h, double cx, double cy, double r0, double r1)
{
    int xa = Math.Max(0, (int)(cx - r1)), xb = Math.Min(w - 1, (int)(cx + r1));
    int ya = Math.Max(0, (int)(cy - r1)), yb = Math.Min(h - 1, (int)(cy + r1));
    int on = 0, tot = 0;
    for (int y = ya; y <= yb; y++)
        for (int x = xa; x <= xb; x++)
        {
            double ax = x - cx, ay = y - cy;
            double dd = ax * ax + ay * ay;
            if (dd < r0 * r0 || dd > r1 * r1) continue;
            tot++; if (m[y * w + x]) on++;
        }
    return tot == 0 ? 0 : on / (double)tot;
}

// 逐像素比对（分块会把细线条的差异抹平，导致线条图被误判成点阵）
static double Diff(bool[] a, Image<L8> b, int block)
{
    int bw = b.Width, bh = b.Height;
    if (a.Length != bw * bh) return 1;
    var bm = Ink(b);
    int diff = 0;
    for (int i = 0; i < a.Length; i++) if (a[i] != bm[i]) diff++;
    return diff / (double)a.Length;
}

static void MakeSheet(List<(string paper, int no, string slot, string src, string dst, string kind)> rows,
                      string montageDir, string outRoot)
{
    Directory.CreateDirectory(montageDir);
    Font? font = null;
    foreach (var fam in new[] { "Consolas", "Arial" })
        if (SystemFonts.TryGet(fam, out var f)) { font = f.CreateFont(15); break; }

    const int H = 150, Per = 8;
    for (int s = 0; s < rows.Count; s += Per)
    {
        var grp = rows.Skip(s).Take(Per).ToList();
        var items = grp.Select(r =>
        {
            var a = Image.Load<L8>(r.src);
            var b = Image.Load<L8>(r.dst);
            double k = Math.Min(1.0, H / (double)Math.Max(a.Height, b.Height));
            var a2 = a.Clone(c => c.Resize((int)(a.Width * k), (int)(a.Height * k)));
            var b2 = b.Clone(c => c.Resize((int)(b.Width * k), (int)(b.Height * k)));
            a.Dispose(); b.Dispose();
            return (r, a2, b2);
        }).ToList();

        int W = items.Sum(t => t.a2.Width + t.b2.Width + 26) + 30;
        using var sheetImg = new Image<Rgba32>(Math.Max(W, 600), (H + 40) * items.Count + 8, new Rgba32(255, 255, 255));
        int y = 4;
        foreach (var (r, a2, b2) in items)
        {
            int x = 6;
            sheetImg.Mutate(c => c.DrawImage(a2, new Point(x, y), 1f));
            x += a2.Width + 10;
            sheetImg.Mutate(c => c.DrawImage(b2, new Point(x, y), 1f));
            if (font != null)
                sheetImg.Mutate(c => c.DrawText($"{r.paper[^2..]}-{r.no}{r.slot} [{r.kind}]", font, Color.Black,
                    new PointF(6, y + H + 2)));
            a2.Dispose(); b2.Dispose();
            y += H + 40;
        }
        sheetImg.SaveAsPng(Path.Combine(montageDir, $"rd_{s / Per + 1}.png"));
    }
}
