#!/usr/bin/env dotnet
// 实验：圆点阵题的结构化重绘 + 像素比对验收。
//
//   dotnet run tools/qbank/RedrawDots.cs -- <原图.png> [输出.png] [--dump]
//
// 做法：
//   1. 距离变换估圆半径 r（相切排列时连通域会连成一片，投影也没有周期峰）；
//   2. 用墨迹外框与 r 反推行列数，得到规则格点；
//   3. 每个格点判断「实心 / 空心(白底黑边) / 空位」；
//   4. 用绘图原语重画，再与原图逐像素比对 —— 差异率低才说明拟合成立。

#:property PublishAot=false
#:package SixLabors.ImageSharp@3.1.5
#:package SixLabors.ImageSharp.Drawing@2.1.4

using SixLabors.ImageSharp;
using SixLabors.ImageSharp.Drawing.Processing;
using SixLabors.ImageSharp.PixelFormats;
using SixLabors.ImageSharp.Processing;
using Draw = SixLabors.ImageSharp.Drawing;

var src = args[0];
var dst = args.Length > 1 && !args[1].StartsWith("--") ? args[1] : Path.ChangeExtension(src, ".redraw.png");
bool dump = args.Contains("--dump");

using var img = Image.Load<L8>(src);
int w = img.Width, h = img.Height;
var ink = new bool[w * h];
img.ProcessPixelRows(a =>
{
    for (int y = 0; y < h; y++)
    {
        var row = a.GetRowSpan(y);
        for (int x = 0; x < w; x++) ink[y * w + x] = row[x].PackedValue < 160;
    }
});

// 去掉贴边长直线（外框）
for (int y = 0; y < h; y++) if (MaxRun(ink, y * w, 1, w) >= 0.9 * w) for (int x = 0; x < w; x++) ink[y * w + x] = false;
for (int x = 0; x < w; x++) if (MaxRun(ink, x, w, h) >= 0.9 * h) for (int y = 0; y < h; y++) ink[y * w + x] = false;

int x0 = w, y0 = h, x1 = -1, y1 = -1;
for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++)
        if (ink[y * w + x]) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
if (x1 < 0) { Console.WriteLine("空图"); return 1; }
int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
var dist = Distance(ink, w, h);

// 直接穷举行列数：每个组合按格点重画一次，取「与原图差异最小」的那组。
// 差异率是硬指标，比任何几何启发式都可靠；差太多说明不是点阵。
int bestCols = 0, bestRows = 0;
double bestDiff = 1.0, r = 0, dx = 0, dy = 0;
List<(double cx, double cy, int kind)> bestCells = new();

for (int cols = 3; cols <= 10; cols++)
    for (int rows = 3; rows <= 10; rows++)
    {
        double ddx = bw / (double)cols, ddy = bh / (double)rows;
        if (Math.Abs(ddx - ddy) / Math.Max(ddx, ddy) > 0.20) continue;
        double rr = 0.5 * Math.Min(ddx, ddy) + 1.0;
        var cells = new List<(double cx, double cy, int kind)>();
        for (int j = 0; j < rows; j++)
            for (int i = 0; i < cols; i++)
            {
                double cx = x0 + (i + 0.5) * ddx, cy = y0 + (j + 0.5) * ddy;
                double core = DiskInk(ink, w, h, cx, cy, rr * 0.45);
                double ring = RingInk(ink, w, h, cx, cy, rr * 0.78, rr * 1.00);
                int kind = core > 0.55 ? 2 : (core < 0.20 && ring > 0.18 ? 1 : 0);
                if (kind > 0) cells.Add((cx, cy, kind));
            }
        if (cells.Count < 4) continue;

        using var cand = Render(w, h, cells, rr);
        double dv = Diff(ink, cand);
        if (dv < bestDiff)
        {
            bestDiff = dv; bestCols = cols; bestRows = rows; bestCells = cells;
            r = rr; dx = ddx; dy = ddy;
        }
    }

if (bestCols == 0) { Console.WriteLine($"{Path.GetFileName(src)} 拟合失败"); return 2; }
if (dump)
{
    Console.WriteLine($"bbox {bw}x{bh} -> {bestCols}x{bestRows} dx={dx:F1} dy={dy:F1} r={r:F1} diff={bestDiff:P2}");
    for (int j = 0; j < bestRows; j++)
    {
        var line = new System.Text.StringBuilder("  ");
        for (int i = 0; i < bestCols; i++)
        {
            var hit = bestCells.FirstOrDefault(c => Math.Abs(c.cy - (y0 + (j + 0.5) * dy)) < dy * 0.4
                                                 && Math.Abs(c.cx - (x0 + (i + 0.5) * dx)) < dx * 0.4);
            line.Append(hit.kind == 2 ? "● " : hit.kind == 1 ? "○ " : ". ");
        }
        Console.WriteLine(line.ToString());
    }
}

var cellsOut = bestCells;
using var outImg = Render(w, h, cellsOut, r);
Console.WriteLine($"{Path.GetFileName(src)} {w}x{h} 圆 {cellsOut.Count}(实心 {cellsOut.Count(c => c.kind == 2)}/空心 {cellsOut.Count(c => c.kind == 1)}) 差异 {bestDiff:P2}");
outImg.SaveAsPng(dst);
return bestDiff < 0.06 ? 0 : 3;

static Image<L8> Render(int w, int h, List<(double cx, double cy, int kind)> cells, double r)
{
    var img = new Image<L8>(w, h, new L8(255));
    int stroke = Math.Max(1, (int)Math.Round(r * 0.34));
    var filled = new Draw.PathCollection(cells.Where(c => c.kind == 2)
        .Select(c => (Draw.IPath)new Draw.EllipsePolygon((float)c.cx, (float)c.cy, (float)r)).ToList());
    var hollow = new Draw.PathCollection(cells.Where(c => c.kind == 1)
        .Select(c => (Draw.IPath)new Draw.EllipsePolygon((float)c.cx, (float)c.cy, (float)r)).ToList());
    if (filled.Any()) img.Mutate(c => c.Fill(new SolidBrush(Color.Black), filled));
    if (hollow.Any()) img.Mutate(c => c.Draw(new SolidPen(Color.Black, stroke), hollow));
    return img;
}

static int MaxRun(bool[] m, int start, int step, int n)
{
    int best = 0, cur = 0;
    for (int i = 0; i < n; i++) { cur = m[start + i * step] ? cur + 1 : 0; if (cur > best) best = cur; }
    return best;
}

// 两遍扫描的倒角距离变换（近似欧氏，够用来估半径）
static float[] Distance(bool[] m, int w, int h)
{
    var d = new float[w * h];
    float inf = 1e9f;
    for (int i = 0; i < d.Length; i++) d[i] = m[i] ? inf : 0;
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
        {
            int i = y * w + x;
            if (d[i] == 0) continue;
            float v = d[i];
            if (x > 0) v = Math.Min(v, d[i - 1] + 1);
            if (y > 0) v = Math.Min(v, d[i - w] + 1);
            if (x > 0 && y > 0) v = Math.Min(v, d[i - w - 1] + 1.414f);
            if (x + 1 < w && y > 0) v = Math.Min(v, d[i - w + 1] + 1.414f);
            d[i] = v;
        }
    for (int y = h - 1; y >= 0; y--)
        for (int x = w - 1; x >= 0; x--)
        {
            int i = y * w + x;
            if (d[i] == 0) continue;
            float v = d[i];
            if (x + 1 < w) v = Math.Min(v, d[i + 1] + 1);
            if (y + 1 < h) v = Math.Min(v, d[i + w] + 1);
            if (x + 1 < w && y + 1 < h) v = Math.Min(v, d[i + w + 1] + 1.414f);
            if (x > 0 && y + 1 < h) v = Math.Min(v, d[i + w - 1] + 1.414f);
            d[i] = v;
        }
    return d;
}

static double DiskInk(bool[] m, int w, int h, double cx, double cy, double r)
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

static double RingInk(bool[] m, int w, int h, double cx, double cy, double r0, double r1)
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

static double Diff(bool[] a, Image<L8> b)
{
    int diff = 0, total = 0;
    b.ProcessPixelRows(rows =>
    {
        for (int y = 0; y < b.Height; y++)
        {
            var row = rows.GetRowSpan(y);
            for (int x = 0; x < b.Width; x++)
            {
                if ((row[x].PackedValue < 160) != a[y * b.Width + x]) diff++;
                total++;
            }
        }
    });
    return total == 0 ? 1 : diff / (double)total;
}
