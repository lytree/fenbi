#!/usr/bin/env dotnet
// 图推题图片类型普查：判断每张图属于「圆点阵 / 网格方块 / 柱状 / 折线 / 其他」。
//
// 用法：
//   dotnet run tools/qbank/ProbeFigures.cs
//   dotnet run tools/qbank/ProbeFigures.cs -- --paper 行测套卷1
//
// 产出 tmp/build/figure_probe.json + 控制台汇总。

#:property PublishAot=false
#:package SixLabors.ImageSharp@3.1.5

using System.Text.Json;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;

var baseDir = "qbank_out/超格行测套卷";
string? onlyPaper = null;
for (int i = 0; i < args.Length; i++)
{
    if (args[i] == "--base" && i + 1 < args.Length) baseDir = args[++i];
    if (args[i] == "--paper" && i + 1 < args.Length) onlyPaper = args[++i];
}
baseDir = Path.GetFullPath(baseDir);
var root = Directory.GetParent(baseDir)!.Parent!.FullName;
var outDir = Path.Combine(root, "tmp", "build");
Directory.CreateDirectory(outDir);

var summary = JsonDocument.Parse(File.ReadAllText(Path.Combine(baseDir, "build_summary.json")));

var items = new List<(string paper, int no, string kind, string path)>();
foreach (var pp in summary.RootElement.EnumerateObject())
{
    var paper = pp.Name;
    if (onlyPaper != null && paper != onlyPaper) continue;
    if (!pp.Value.TryGetProperty("opt_split", out var arr)) continue;
    var imgDir = Path.Combine(baseDir, paper, "images");
    foreach (var e in arr.EnumerateArray())
    {
        var no = e.GetInt32();
        var stem = Path.Combine(imgDir, $"q{no:D3}.png");
        if (File.Exists(stem)) items.Add((paper, no, "stem", stem));
        foreach (var k in "ABCD")
        {
            var p = Path.Combine(imgDir, $"q{no:D3}_{k}.png");
            if (File.Exists(p)) items.Add((paper, no, k.ToString(), p));
        }
    }
}
Console.WriteLine($"待普查 {items.Count} 张图");

var results = new List<(string key, string kind, string detail)>();
var stat = new Dictionary<string, int>();

foreach (var it in items)
{
    var info = Analyze(it.path);
    var kind = Classify(info);
    stat[kind] = stat.GetValueOrDefault(kind) + 1;
    results.Add(($"{it.paper}:{it.no}:{it.kind}", kind, info));
}
Console.WriteLine();
foreach (var kv in stat.OrderByDescending(k => k.Value))
    Console.WriteLine($"  {kv.Key,-14} {kv.Value}");

var json = "[\n" + string.Join(",\n", results.Select(r =>
    $"  {{\"key\":\"{r.key}\",\"kind\":\"{r.kind}\",\"detail\":\"{r.detail}\"}}")) + "\n]";
File.WriteAllText(Path.Combine(outDir, "figure_probe.json"), json);
Console.WriteLine($"\n明细 -> {Path.Combine(outDir, "figure_probe.json")}");
return 0;

// ---------------- 分析 ----------------

static string Analyze(string path)
{
    using var img = Image.Load<L8>(path);
    int w = img.Width, h = img.Height;
    var ink = new bool[w * h];
    int gray = 0;
    img.ProcessPixelRows(a =>
    {
        for (int y = 0; y < h; y++)
        {
            var row = a.GetRowSpan(y);
            for (int x = 0; x < w; x++)
            {
                byte v = row[x].PackedValue;
                if (v > 60 && v < 200) gray++;
                ink[y * w + x] = v < 160;
            }
        }
    });
    int inkCount = 0;
    for (int i = 0; i < ink.Length; i++) if (ink[i]) inkCount++;

    // 去掉贯穿的长直线（外框、坐标轴），剩下的才算「图形块」
    var body = (bool[])ink.Clone();
    int hLines = 0, vLines = 0;
    for (int y = 0; y < h; y++)
    {
        int run = MaxRun(ink, y * w, 1, w);
        if (run >= 0.55 * w) { hLines++; for (int x = 0; x < w; x++) body[y * w + x] = false; }
    }
    for (int x = 0; x < w; x++)
    {
        int run = MaxRun(ink, x, w, h);
        if (run >= 0.55 * h) { vLines++; for (int y = 0; y < h; y++) body[y * w + x] = false; }
    }

    var comps = Components(body, w, h).Where(c => c.area >= 25).ToList();
    // 圆形块：近似正方形 + 填充率像圆（实心 0.78、环 0.35~0.55）
    var round = comps.Where(c =>
    {
        double r = Math.Abs(c.w - c.h) / (double)Math.Max(c.w, c.h);
        double fill = c.area / (double)(c.w * c.h);
        return r <= 0.30 && c.h >= 10 && c.h <= 0.45 * h && fill >= 0.25 && fill <= 0.92;
    }).ToList();

    double medH = round.Count == 0 ? 0 : round.Select(c => c.h).OrderBy(v => v).ToArray()[round.Count / 2];
    int uniform = round.Count(c => Math.Abs(c.h - medH) <= 0.22 * medH);

    // 网格间距一致性：圆心的 x/y 去重后，相邻间距的变异系数
    double pitchCv = 1.0;
    if (round.Count >= 6)
    {
        var xs = round.Select(c => c.x + c.w / 2.0).OrderBy(v => v).ToList();
        var ys = round.Select(c => c.y + c.h / 2.0).OrderBy(v => v).ToList();
        pitchCv = Math.Min(Cv(Dedup(xs, 0.4 * medH)), Cv(Dedup(ys, 0.4 * medH)));
    }

    return $"w={w},h={h},ink={(double)inkCount / (w * h):F3},gray={(double)gray / (w * h):F3}," +
           $"body={comps.Count},round={round.Count},medH={medH:F0},uniform={uniform}," +
           $"pitchCv={pitchCv:F2},hl={hLines},vl={vLines}";
}

static double Cv(List<double> centers)
{
    if (centers.Count < 3) return 1.0;
    var gaps = new List<double>();
    for (int i = 1; i < centers.Count; i++)
    {
        double g = centers[i] - centers[i - 1];
        if (g > 2) gaps.Add(g);
    }
    if (gaps.Count < 2) return 1.0;
    double mean = gaps.Average();
    double sd = Math.Sqrt(gaps.Select(g => (g - mean) * (g - mean)).Average());
    return sd / mean;
}

static List<double> Dedup(List<double> v, double tol)
{
    var res = new List<double>();
    foreach (var x in v)
        if (res.Count == 0 || x - res[^1] > tol) res.Add(x);
        else res[^1] = (res[^1] + x) / 2;
    return res;
}

static string Classify(string detail)
{
    var d = detail.Split(',').Select(s => s.Split('=')).Where(p => p.Length == 2)
                  .ToDictionary(p => p[0], p => p[1]);
    int body = int.Parse(d["body"]);
    int round = int.Parse(d["round"]);
    int uniform = int.Parse(d["uniform"]);
    double pitchCv = double.Parse(d["pitchCv"]);
    int hl = int.Parse(d["hl"]), vl = int.Parse(d["vl"]);
    double ink = double.Parse(d["ink"]);

    if (round >= 6 && uniform >= 0.6 * round && pitchCv <= 0.35) return "dots";
    if (hl >= 2 && vl >= 2) return "gridframe";
    if (body <= 2 && ink < 0.06) return "line";
    return "other";
}

static int MaxRun(bool[] m, int start, int step, int n)
{
    int best = 0, cur = 0;
    for (int i = 0; i < n; i++)
    {
        cur = m[start + i * step] ? cur + 1 : 0;
        if (cur > best) best = cur;
    }
    return best;
}

static List<(int area, int x, int y, int w, int h)> Components(bool[] m, int w, int h)
{
    var seen = new bool[m.Length];
    var res = new List<(int, int, int, int, int)>();
    var stack = new Stack<int>();
    for (int i = 0; i < m.Length; i++)
    {
        if (!m[i] || seen[i]) continue;
        seen[i] = true;
        stack.Clear();
        stack.Push(i);
        int area = 0, x0 = int.MaxValue, y0 = int.MaxValue, x1 = -1, y1 = -1;
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
                    if (m[qi] && !seen[qi]) { seen[qi] = true; stack.Push(qi); }
                }
        }
        res.Add((area, x0, y0, x1 - x0 + 1, y1 - y0 + 1));
    }
    return res;
}
