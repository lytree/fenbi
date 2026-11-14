#:property PublishAot=false
#:package SixLabors.ImageSharp@3.1.5
#:package SixLabors.ImageSharp.Drawing@2.1.4

// 圆点阵结构化重绘（v3）：实心 / 空心 / 双圈 三种圆态，正交排列（六角走兜底），外框原样保留。
//
//   dotnet run tools/qbank/RedrawDots2.cs -- --list tmp/figtype/dots_q.txt --out tmp/build/dots2
//
// 关键三步：
//   1. 用「菱形白缝」连通块定节距与原点（按面积剔除空心圆的白盘），比拿外框除格数稳；
//   2. 稀疏搜 (r, 线宽, 亚像素平移)，取全图差异最小的一组；
//   3. 每格再独立试 4 种圆态，取局部差异最小者 —— 防止个别格子判错被全图均值掩盖。
// 验收：全图 diff < 0.085 且没有任何一格局部 diff > 0.16 才输出。

using System.Text;
using System.Text.Json;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.Drawing.Processing;
using SixLabors.ImageSharp.PixelFormats;
using SixLabors.ImageSharp.Processing;
using Draw = SixLabors.ImageSharp.Drawing;

string listFile = "tmp/figtype/dots_q.txt";
string outRoot = "tmp/build/dots2";
bool saveAll = args.Contains("--all");
for (int i = 0; i < args.Length; i++)
{
    if (args[i] == "--list" && i + 1 < args.Length) listFile = args[i + 1];
    if (args[i] == "--out" && i + 1 < args.Length) outRoot = args[i + 1];
}
Directory.CreateDirectory(outRoot);

var qs = File.ReadAllLines(listFile)
    .Select(l => l.Trim().Replace("\r", ""))
    .Where(l => l.Contains(':'))
    .Select(l => (vol: int.Parse(l.Split(':')[0]), no: int.Parse(l.Split(':')[1])))
    .ToList();

var results = new List<Res>();
foreach (var (vol, no) in qs)
{
    string dir = Path.Combine("qbank_out", "超格行测套卷", $"行测套卷{vol}", "images");
    var files = Directory.GetFiles(dir, $"q{no:D3}*.png").OrderBy(f => f).ToList();
    string od = Path.Combine(outRoot, $"行测套卷{vol}");
    Directory.CreateDirectory(od);
    foreach (var f in files)
    {
        var r = Fit(f);
        r.Vol = vol; r.No = no; r.Name = Path.GetFileName(f);
        if (saveAll) r.Ok = true;
        if (r.Ok)
        {
            r.Out = Path.Combine(od, r.Name);
            Save(r, r.Out);
        }
        results.Add(r);
        string kinds = string.Join(" ", new[] { 1, 2, 3 }.Select(k => $"{k}:{r.Cells.Count(c => c.Kind == k)}"));
        Console.WriteLine($"{vol}-{no} {r.Name} {r.W}x{r.H} {r.Cols}x{r.Rows} r={r.R:F1} s={r.Stroke:F2} diff={r.Diff:F4} maxCell={r.MaxCellDiff:F2} fix={r.Corrected} {kinds} {(r.Ok ? "OK" : "REJ")}");
    }
}

File.WriteAllText(Path.Combine(outRoot, "report.json"),
    JsonSerializer.Serialize(results, new JsonSerializerOptions { WriteIndented = false, IncludeFields = true }), new UTF8Encoding(false));
Console.WriteLine($"accepted {results.Count(r => r.Ok)}/{results.Count}");

Res Fit(string path)
{
    var res = new Res { SrcPath = path };
    using var img = Image.Load<L8>(path);
    int w = img.Width, h = img.Height;
    res.W = w; res.H = h;
    var ink = new bool[w * h];
    img.ProcessPixelRows(a =>
    {
        for (int y = 0; y < h; y++)
        {
            var row = a.GetRowSpan(y);
            for (int x = 0; x < w; x++) ink[y * w + x] = row[x].PackedValue < 160;
        }
    });

    // 贴边外框（整行/整列几乎全墨的带）—— 只从边缘往里数，圆阵满墨行不会被误判
    int ft = 0, fb = 0, fl = 0, fr = 0;
    while (ft < h / 6 && RowInk(ink, w, ft) > 0.80) ft++;
    while (fb < h / 6 && RowInk(ink, w, h - 1 - fb) > 0.80) fb++;
    while (fl < w / 6 && ColInk(ink, w, h, fl) > 0.80) fl++;
    while (fr < w / 6 && ColInk(ink, w, h, w - 1 - fr) > 0.80) fr++;
    res.Frame = new[] { fl, ft, fr, fb };

    var work = (bool[])ink.Clone();
    for (int y = 0; y < ft; y++) for (int x = 0; x < w; x++) work[y * w + x] = false;
    for (int y = h - fb; y < h; y++) for (int x = 0; x < w; x++) work[y * w + x] = false;
    for (int x = 0; x < fl; x++) for (int y = 0; y < h; y++) work[y * w + x] = false;
    for (int x = w - fr; x < w; x++) for (int y = 0; y < h; y++) work[y * w + x] = false;

    int x0 = w, y0 = h, x1 = -1, y1 = -1;
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
            if (work[y * w + x]) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    if (x1 < 0) return res;

    var cands = new List<Lat>();
    var gap = GapLattice(work, w, h);
    if (gap != null) cands.Add(gap);

    // 兜底：按外框穷举行列数（含六角）
    for (int cols = 3; cols <= 9; cols++)
        for (int rows = 3; rows <= 9; rows++)
            foreach (bool hex in new[] { false, true })
            {
                int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
                double ddx = bw / (double)cols;
                double ddy = hex ? bh / (rows - 0.5) : bh / (double)rows;
                if (!hex && Math.Abs(ddx - ddy) / Math.Max(ddx, ddy) > 0.22) continue;
                cands.Add(new Lat { Ox = x0 + ddx / 2, Oy = y0 + ddy / 2, Dx = ddx, Dy = ddy, Cols = cols, Rows = rows, Hex = hex });
            }

    // 候选裁剪：白缝法命中就只用它；否则按「粗渲染差异」只留前 3 组，避免组合爆炸
    var picked = new List<Lat>();
    if (gap != null) picked.Add(gap);
    else
    {
        var scored = new List<(double d, Lat l)>();
        foreach (var c in cands)
        {
            double rr0 = Math.Min(c.Dx, c.Dy) * 0.53;
            var cl0 = ClassifyAt(work, w, h, c, rr0);
            if (cl0.Count < 4) continue;
            using var im0 = Render(w, h, cl0, rr0, 0.27);
            scored.Add((Diff(work, im0, res.Frame, w, h), c));
        }
        picked = scored.OrderBy(x => x.d).Take(3).Select(x => x.l).ToList();
    }
    if (picked.Count == 0) return res;

    double best = 1.0;
    Lat bestLat = null!; List<Cell> bestCells = null!; double bestR = 0, bestS = 0;
    foreach (var c in picked)
        foreach (double ox in new[] { -1.0, 0.0, 1.0 })
            foreach (double oy in new[] { -1.0, 0.0, 1.0 })
                foreach (double rMul in new[] { 0.500, 0.520, 0.540, 0.560 })
                    foreach (double sMul in new[] { 0.22, 0.27, 0.32 })
                    {
                        var lat = new Lat { Ox = c.Ox + ox, Oy = c.Oy + oy, Dx = c.Dx, Dy = c.Dy, Cols = c.Cols, Rows = c.Rows, Hex = c.Hex };
                        double rr = Math.Min(lat.Dx, lat.Dy) * rMul;
                        var cells = ClassifyAt(work, w, h, lat, rr);
                        if (cells.Count < 4) continue;
                        using var cand = Render(w, h, cells, rr, sMul);
                        double dv = Diff(work, cand, res.Frame, w, h);
                        if (dv < best) { best = dv; bestLat = lat; bestCells = cells; bestR = rr; bestS = sMul; }
                    }

    if (bestLat == null) return res;

    // 逐格纠错：每格单独试 4 种圆态，取局部差异最小者
    int corrected = 0;
    var fixedCells = new List<Cell>();
    foreach (var cell in bestCells)
    {
        int bestKind = cell.Kind; double bestLocal = 9;
        foreach (int k in new[] { 2, 1, 3, 0 })
        {
            double d = LocalDiff(work, w, h, cell.Cx, cell.Cy, bestR, bestS, k);
            if (d < bestLocal) { bestLocal = d; bestKind = k; }
        }
        if (bestKind != cell.Kind) corrected++;
        if (bestKind > 0) fixedCells.Add(new Cell { Cx = cell.Cx, Cy = cell.Cy, Kind = bestKind });
    }
    bestCells = fixedCells;

    using var final = Render(w, h, bestCells, bestR, bestS);
    res.Diff = Diff(work, final, res.Frame, w, h);
    res.MaxCellDiff = 0;
    foreach (var c in bestCells)
    {
        double d = LocalDiff(work, w, h, c.Cx, c.Cy, bestR, bestS, c.Kind);
        if (d > res.MaxCellDiff) res.MaxCellDiff = d;
    }
    res.Cols = bestLat.Cols; res.Rows = bestLat.Rows; res.Hex = bestLat.Hex;
    res.R = bestR; res.Stroke = bestS; res.Cells = bestCells; res.Lat = bestLat; res.Corrected = corrected;
    res.Ok = res.Diff < 0.085 && res.MaxCellDiff < 0.16;
    return res;
}

// 菱形白缝栅格：相切圆之间的白缝是最小的一类白块，其质心阵列节距恰等于圆阵节距
Lat? GapLattice(bool[] ink, int w, int h)
{
    var seen = new bool[w * h];
    var xs = new List<double>(); var ys = new List<double>(); var areas = new List<int>();
    var stack = new Stack<int>();
    for (int i = 0; i < ink.Length; i++)
    {
        if (ink[i] || seen[i]) continue;
        stack.Clear(); stack.Push(i); seen[i] = true;
        int n = 0; double sx = 0, sy = 0; bool touch = false;
        while (stack.Count > 0)
        {
            int p = stack.Pop();
            int px = p % w, py = p / w;
            n++; sx += px; sy += py;
            if (px < 2 || py < 2 || px > w - 3 || py > h - 3) touch = true;
            if (n > 4000) { touch = true; break; }
            if (px > 0) { int q = p - 1; if (!ink[q] && !seen[q]) { seen[q] = true; stack.Push(q); } }
            if (px < w - 1) { int q = p + 1; if (!ink[q] && !seen[q]) { seen[q] = true; stack.Push(q); } }
            if (py > 0) { int q = p - w; if (!ink[q] && !seen[q]) { seen[q] = true; stack.Push(q); } }
            if (py < h - 1) { int q = p + w; if (!ink[q] && !seen[q]) { seen[q] = true; stack.Push(q); } }
        }
        if (touch || n < 12) continue;
        areas.Add(n); xs.Add(sx / n); ys.Add(sy / n);
    }
    if (xs.Count < 8) return null;

    var sa = areas.OrderBy(a => a).ToList();
    double a10 = sa[sa.Count / 10];
    double lim = Math.Max(30, a10 * 2.5);
    var fx = new List<double>(); var fy = new List<double>();
    for (int i = 0; i < areas.Count; i++) if (areas[i] <= lim) { fx.Add(xs[i]); fy.Add(ys[i]); }
    if (fx.Count < 4) return null;

    var ux = Cluster(fx, 3.0);
    var uy = Cluster(fy, 3.0);
    if (ux.Count < 3 || uy.Count < 3) return null;
    double dx = MedianDiff(ux), dy = MedianDiff(uy);
    if (dx < 8 || dy < 8) return null;
    if (Math.Abs(dx - dy) / Math.Max(dx, dy) > 0.12) return null;

    return new Lat
    {
        Ox = ux[0] - dx / 2, Oy = uy[0] - dy / 2, Dx = dx, Dy = dy,
        Cols = (int)Math.Round((ux[^1] - ux[0]) / dx) + 2,
        Rows = (int)Math.Round((uy[^1] - uy[0]) / dy) + 2
    };
}

static List<double> Cluster(List<double> v, double tol)
{
    var s = v.OrderBy(x => x).ToList();
    var o = new List<double>();
    var cur = new List<double> { s[0] };
    for (int i = 1; i < s.Count; i++)
    {
        if (s[i] - cur[^1] <= tol) cur.Add(s[i]);
        else { o.Add(cur.Average()); cur = new List<double> { s[i] }; }
    }
    o.Add(cur.Average());
    return o;
}

static double MedianDiff(List<double> v)
{
    var d = new List<double>();
    for (int i = 1; i < v.Count; i++) d.Add(v[i] - v[i - 1]);
    if (d.Count == 0) return 0;
    d.Sort();
    return d[d.Count / 2];
}

List<Cell> ClassifyAt(bool[] ink, int w, int h, Lat l, double r)
{
    var list = new List<Cell>();
    for (int j = 0; j < l.Rows; j++)
        for (int i = 0; i < l.Cols; i++)
        {
            double cx = l.Ox + i * l.Dx + (l.Hex && j % 2 == 1 ? l.Dx / 2 : 0);
            double cy = l.Oy + j * l.Dy;
            if (cx + r < 0 || cy + r < 0 || cx - r > w || cy - r > h) continue;
            int kind = GuessKind(ink, w, h, cx, cy, r);
            if (kind > 0) list.Add(new Cell { Cx = cx, Cy = cy, Kind = kind });
        }
    return list;
}

int GuessKind(bool[] ink, int w, int h, double cx, double cy, double r)
{
    double core = DiskInk(ink, w, h, cx, cy, r * 0.36);
    double mid = DiskInk(ink, w, h, cx, cy, r * 0.16);
    double ring = RingInk(ink, w, h, cx, cy, r * 0.72, r * 0.94);
    double ringIn = RingInk(ink, w, h, cx, cy, r * 0.28, r * 0.44);
    if (core > 0.62) return 2;
    if (mid > 0.50 && ringIn > 0.22 && ring > 0.22) return 3;
    if (core < 0.24 && ring > 0.26) return 1;
    return 0;
}

// 单格局部差异：只渲染这一格，与同位置原图比
double LocalDiff(bool[] ink, int w, int h, double cx, double cy, double r, double sMul, int kind)
{
    int box = (int)Math.Ceiling(r) + 2;
    int xa = (int)Math.Round(cx) - box, ya = (int)Math.Round(cy) - box;
    int side = box * 2 + 1;
    int diff = 0, tot = 0;
    // 只比本圆盘内的像素：盒子四角是邻居的圆弧，算进来会误导判态
    double rr2 = (r * 0.96) * (r * 0.96);
    bool InDisk(int xx, int yy) => (xx - box) * (xx - box) + (yy - box) * (yy - box) <= rr2;
    if (kind == 0)
    {
        for (int yy = 0; yy < side; yy++)
        {
            int gy = ya + yy; if (gy < 0 || gy >= h) continue;
            for (int xx = 0; xx < side; xx++)
            {
                int gx = xa + xx; if (gx < 0 || gx >= w) continue;
                if (!InDisk(xx, yy)) continue;
                if (ink[gy * w + gx]) diff++;
                tot++;
            }
        }
        return tot == 0 ? 1 : diff / (double)tot;
    }
    using var tmp = new Image<L8>(side, side, new L8(255));
    var one = new List<Cell> { new Cell { Cx = box, Cy = box, Kind = kind } };
    using (var dr = Render(side, side, one, r, sMul))
        tmp.Mutate(c => c.DrawImage(dr, 1f));
    tmp.ProcessPixelRows(a =>
    {
        for (int yy = 0; yy < side; yy++)
        {
            int gy = ya + yy; if (gy < 0 || gy >= h) continue;
            var row = a.GetRowSpan(yy);
            for (int xx = 0; xx < side; xx++)
            {
                int gx = xa + xx; if (gx < 0 || gx >= w) continue;
                if (!InDisk(xx, yy)) continue;
                if ((row[xx].PackedValue < 160) != ink[gy * w + gx]) diff++;
                tot++;
            }
        }
    });
    return tot == 0 ? 1 : diff / (double)tot;
}

Image<L8> Render(int w, int h, List<Cell> cells, double r, double sMul)
{
    var img = new Image<L8>(w, h, new L8(255));
    int stroke = Math.Max(1, (int)Math.Round(r * sMul));
    var filled = new Draw.PathCollection(cells.Where(c => c.Kind == 2)
        .Select(c => (Draw.IPath)new Draw.EllipsePolygon((float)c.Cx, (float)c.Cy, (float)r)).ToList());
    var hollow = new Draw.PathCollection(cells.Where(c => c.Kind == 1)
        .Select(c => (Draw.IPath)new Draw.EllipsePolygon((float)c.Cx, (float)c.Cy, (float)r)).ToList());
    var dblOuter = new Draw.PathCollection(cells.Where(c => c.Kind == 3)
        .Select(c => (Draw.IPath)new Draw.EllipsePolygon((float)c.Cx, (float)c.Cy, (float)r)).ToList());
    var dblInner = new Draw.PathCollection(cells.Where(c => c.Kind == 3)
        .Select(c => (Draw.IPath)new Draw.EllipsePolygon((float)c.Cx, (float)c.Cy, (float)(r * 0.34))).ToList());
    if (filled.Any()) img.Mutate(c => c.Fill(new SolidBrush(Color.Black), filled));
    if (hollow.Any()) img.Mutate(c => c.Draw(new SolidPen(Color.Black, stroke), hollow));
    if (dblOuter.Any()) img.Mutate(c => c.Draw(new SolidPen(Color.Black, stroke), dblOuter));
    if (dblInner.Any()) img.Mutate(c => c.Draw(new SolidPen(Color.Black, Math.Max(1, stroke - 1)), dblInner));
    return img;
}

void Save(Res r, string dst)
{
    using var src = Image.Load<L8>(r.SrcPath!);
    using var outImg = new Image<L8>(r.W, r.H, new L8(255));
    using (var drawn = Render(r.W, r.H, r.Cells, r.R, r.Stroke))
        outImg.Mutate(c => c.DrawImage(drawn, 1f));
    var f = r.Frame;
    if (f[0] + f[1] + f[2] + f[3] > 0)
        outImg.ProcessPixelRows(src, (o, s) =>
        {
            for (int y = 0; y < r.H; y++)
            {
                var os = o.GetRowSpan(y); var ss = s.GetRowSpan(y);
                for (int x = 0; x < r.W; x++)
                    if (x < f[0] || x >= r.W - f[2] || y < f[1] || y >= r.H - f[3]) os[x] = ss[x];
            }
        });
    outImg.SaveAsPng(dst);
}

double Diff(bool[] ink, Image<L8> cand, int[] frame, int w, int h)
{
    int diff = 0, total = 0;
    cand.ProcessPixelRows(rows =>
    {
        for (int y = 0; y < h; y++)
        {
            var row = rows.GetRowSpan(y);
            for (int x = 0; x < w; x++)
            {
                if (x < frame[0] || x >= w - frame[2] || y < frame[1] || y >= h - frame[3]) continue;
                if ((row[x].PackedValue < 160) != ink[y * w + x]) diff++;
                total++;
            }
        }
    });
    return total == 0 ? 1 : diff / (double)total;
}

static double RowInk(bool[] m, int w, int y)
{
    int on = 0;
    for (int x = 0; x < w; x++) if (m[y * w + x]) on++;
    return on / (double)w;
}

static double ColInk(bool[] m, int w, int h, int x)
{
    int on = 0;
    for (int y = 0; y < h; y++) if (m[y * w + x]) on++;
    return on / (double)h;
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

class Cell { public double Cx, Cy; public int Kind; }
class Lat { public double Ox, Oy, Dx, Dy; public int Cols, Rows; public bool Hex; }
class Res
{
    public int Vol, No; public string Name = "", Out = "";
    public int W, H, Cols, Rows; public bool Hex, Ok;
    public double R, Diff, MaxCellDiff, Stroke = 0.30;
    public int Corrected;
    public int[] Frame = new int[4];
    public Lat? Lat;
    public List<Cell> Cells = new();
    public string? SrcPath;
}
