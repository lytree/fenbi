#!/usr/bin/env dotnet
// 拆分质量审计：机器初筛 + 生成人工核对表。
//
// 用法：
//   dotnet run tools/qbank/audit_options.cs
//
// 机器初筛（能可靠判定的几类）：
//   · blank      选项图几乎是空白
//   · dup        A/B/C/D 之间有两张几乎一模一样（裁到同一块图形）
//   · size-spread 四个选项尺寸差异过大
//   · letter?    选项图贴边有孤立小块（卷面印的 A/B/C/D 混进来了）
//
// 产出：
//   qbank_out/超格行测套卷/audit_report.json
//   qbank_out/超格行测套卷/audit_checks/audit_N.png（每题一行：题干 + ABCD，4 题一张）

#:package SkiaSharp@2.88.9
#:property PublishAot=false

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using SkiaSharp;

Console.OutputEncoding = System.Text.Encoding.UTF8;

string ROOT = Path.GetFullPath(Path.Combine(ThisDir(), "..", ".."));
string BASE = Path.Combine(ROOT, "qbank_out", "超格行测套卷");
string OUT = Path.Combine(BASE, "audit_checks");

var summaryPath = Path.Combine(BASE, "build_summary.json");
if (!File.Exists(summaryPath))
{
    Console.Error.WriteLine($"找不到 {summaryPath}");
    return 2;
}

Directory.CreateDirectory(OUT);

// 读取 build_summary.json：顶层每个 paper 的 opt_split 是题号数组。
var papers = new List<(string Paper, int[] Nos)>();
using (var doc = JsonDocument.Parse(File.ReadAllText(summaryPath)))
{
    foreach (var pp in doc.RootElement.EnumerateObject())
    {
        var nos = pp.Value.GetProperty("opt_split").EnumerateArray()
                    .Select(e => e.GetInt32()).ToArray();
        papers.Add((pp.Name, nos));
    }
}
papers = papers.OrderBy(p => int.Parse(new string(p.Paper.Where(char.IsDigit).ToArray()))).ToList();

var report = new List<(string key, Rec rec)>();
var rows = new List<(string paper, int no, SKBitmap? stem, SKBitmap[] opts)>();

try
{
    foreach (var (paper, nos) in papers)
    {
        string d = Path.Combine(BASE, paper, "images");
        foreach (int no in nos)
        {
            var rec = new Rec(paper, no);
            string key = $"{paper}:{no}";
            var op = "ABCD".Select(k => Path.Combine(d, $"q{no:D3}_{k}.png")).ToArray();
            if (op.Any(p => !File.Exists(p)))
            {
                rec.flags.Add("missing-file");
                report.Add((key, rec));
                continue;
            }
            string sp = Path.Combine(d, $"q{no:D3}.png");
            SKBitmap? stem = File.Exists(sp) ? LoadL8(sp) : null;
            var opts = op.Select(LoadL8).ToArray();
            var sz = opts.Select(o => (w: o.Width, h: o.Height)).ToArray();
            rec.hasDetails = true;
            rec.sizes = new Dictionary<string, int[]>();
            rec.stem = stem != null ? new[] { stem.Width, stem.Height } : null;
            for (int i = 0; i < 4; i++)
                rec.sizes["ABCD"[i].ToString()] = new[] { sz[i].w, sz[i].h };

            for (int i = 0; i < 4; i++)
                if (InkFraction(Ink(opts[i])) < 0.004)
                    rec.flags.Add("ABCD"[i] + ":blank");
            int[] ws = sz.Select(s => s.w).ToArray();
            int[] hs = sz.Select(s => s.h).ToArray();
            if (ws.Min() < 12 || hs.Min() < 12) rec.flags.Add("tiny");
            if (ws.Max() > 3.0 * Math.Max(1, ws.Min()) || hs.Max() > 3.0 * Math.Max(1, hs.Min()))
                rec.flags.Add("size-spread");
            for (int i = 0; i < 4; i++)
                for (int j = i + 1; j < 4; j++)
                {
                    double dv = Similar(opts[i], opts[j]);
                    if (dv < 0.015)
                        rec.flags.Add($"dup:{"ABCD"[i]}={"ABCD"[j]}({dv.ToString("0.000", CultureInfo.InvariantCulture)})");
                }
            var lb = new int[4];
            rec.edge_blobs = new Dictionary<string, int>();
            for (int i = 0; i < 4; i++)
            {
                lb[i] = EdgeBlobs(Ink(opts[i]), opts[i].Width, opts[i].Height);
                rec.edge_blobs["ABCD"[i].ToString()] = lb[i];
            }
            if (lb.Sum() >= 2)
                rec.flags.Add("letter?" + string.Join("/", "ABCD".Where((c, i) => lb[i] > 0)));
            report.Add((key, rec));
            rows.Add((paper, no, stem, opts));
        }
    }

    File.WriteAllText(Path.Combine(BASE, "audit_report.json"), BuildReportJson(report),
                      new UTF8Encoding(false));

    // ---------- 人工核对表 ----------
    const int H = 230, PER = 4;
    SKTypeface? font = null;
    foreach (var fam in new[] { "Consolas", "Arial", "Segoe UI" })
    {
        font = SKTypeface.FromFamilyName(fam);
        if (font != null) break;
    }
    if (font == null)
    {
        foreach (var fp in new[] { @"C:\Windows\Fonts\consola.ttf", @"C:\Windows\Fonts\arial.ttf" })
            if (File.Exists(fp)) { font = SKTypeface.FromFile(fp); break; }
    }

    for (int si = 0; si < rows.Count; si += PER)
    {
        var grp = rows.GetRange(si, Math.Min(PER, rows.Count - si));
        var items = grp.Select(r => (
            r.paper, r.no,
            s: FitL8(r.stem, H),
            ops: r.opts.Select(o => FitL8(o, H)).ToArray()
        )).ToList();
        int W = items.Max(it => 110 + (it.s?.Width ?? 0) + 30 +
                                 it.ops.Where(o => o != null).Sum(o => o!.Width + 24) + 70);
        using var sheet = NewWhite(W, items.Count * (H + 48) + 8);
        using var canvas = new SKCanvas(sheet);
        int y = 6;
        foreach (var it in items)
        {
            if (font != null)
                DrawLabel(canvas,
                    $"{it.paper.Replace("行测套卷", "S")}-{it.no}", 5, y + 8, font);
            int x = 110;
            if (it.s != null)
            {
                PasteL8(sheet, it.s, x, y);
                x += it.s.Width + 30;
            }
            DrawVLine(sheet, x - 15, y, y + H, 170);
            x += 6;
            for (int i = 0; i < 4; i++)
            {
                var o = it.ops[i];
                if (font != null)
                    DrawLabel(canvas, "ABCD"[i].ToString(), x, y + H + 4, font);
                if (o != null) PasteL8(sheet, o, x, y);
                x += (o?.Width ?? 40) + 24;
            }
            DrawHLine(sheet, y + H + 30, 0, W, 200);
            y += H + 48;
        }
        using var image = SKImage.FromBitmap(sheet);
        using var data = image.Encode(SKEncodedImageFormat.Png, 100);
        using var fs = File.Create(Path.Combine(OUT, $"audit_{si / PER + 1}.png"));
        data.SaveTo(fs);
        foreach (var it in items)
        {
            it.s?.Dispose();
            foreach (var o in it.ops) o?.Dispose();
        }
    }
    Console.WriteLine($"核对表 {(rows.Count + PER - 1) / PER} 张，题 {rows.Count}");

    var flagged = report.Where(kv => kv.rec.flags.Count > 0).ToList();
    Console.WriteLine($"=== 机器初筛可疑 {flagged.Count} 题");
    foreach (var kv in flagged)
        Console.WriteLine("  " + kv.key + " " + FmtStrList(kv.rec.flags));
}
catch (Exception ex)
{
    Console.Error.WriteLine(ex.Message);
    return 2;
}
return 0;

// ---------------- helpers ----------------

static string ThisDir([CallerFilePath] string p = "") => Path.GetDirectoryName(p)!;

static SKBitmap NewWhite(int w, int h)
{
    var b = new SKBitmap(w, h);
    using (var c = new SKCanvas(b)) c.Clear(SKColors.White);
    return b;
}

// SkiaSharp 的 DrawText 以基线定位，原按左上角 PointF 定位，
// 这里加一个字号（18）偏移，保持视觉位置接近。
static void DrawLabel(SKCanvas canvas, string text, float x, float y, SKTypeface tf)
{
    using var paint = new SKPaint { Color = SKColors.Black, TextSize = 18, IsAntialias = true, Typeface = tf };
    canvas.DrawText(text, x, y + 18, paint);
}

// PIL 的 convert("L")：L = (R*19595 + G*38470 + B*7471) >> 16
static SKBitmap LoadL8(string path)
{
    using var rgb = SKBitmap.Decode(path);
    var g = new SKBitmap(rgb.Width, rgb.Height);
    for (int y = 0; y < rgb.Height; y++)
        for (int x = 0; x < rgb.Width; x++)
        {
            var px = rgb.GetPixel(x, y);
            byte v = (byte)((px.Red * 19595 + px.Green * 38470 + px.Blue * 7471) >> 16);
            g.SetPixel(x, y, new SKColor(v, v, v, 255));
        }
    return g;
}

// ink(img)：灰度 < 160。
static bool[] Ink(SKBitmap img)
{
    int w = img.Width, h = img.Height;
    var m = new bool[w * h];
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) m[y * w + x] = img.GetPixel(x, y).Red < 160;
    return m;
}

static double InkFraction(bool[] m)
{
    int ink = 0; foreach (bool v in m) if (v) ink++;
    return ink / (double)m.Length;
}

// 两图缩到 72×72 后的平均灰度差 / 255。
static double Similar(SKBitmap a, SKBitmap b)
{
    using var ra = a.Resize(new SKSizeI(72, 72), SKFilterQuality.High);
    using var rb = b.Resize(new SKSizeI(72, 72), SKFilterQuality.High);
    double s = 0;
    for (int y = 0; y < 72; y++)
        for (int x = 0; x < 72; x++)
            s += Math.Abs(ra.GetPixel(x, y).Red - rb.GetPixel(x, y).Red);
    return s / (72.0 * 72.0) / 255.0;
}

// 8 邻域（3×3）连通域标注 + 每个连通域的包围盒；scipy.ndimage.label + find_objects。
static int EdgeBlobs(bool[] m, int w, int h)
{
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
        int area = 0, x0 = w, y0 = h, x1 = 0, y1 = 0;   // x1/y1 为 exclusive
        while (stack.Count > 0)
        {
            int p = stack.Pop(), px = p % w, py = p / w;
            area++;
            if (px < x0) x0 = px;
            if (px + 1 > x1) x1 = px + 1;
            if (py < y0) y0 = py;
            if (py + 1 > y1) y1 = py + 1;
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

// fit：等比缩到不超过 H 高（不放大）。
static SKBitmap? FitL8(SKBitmap? im, int H)
{
    if (im == null) return null;
    double k = Math.Min(1.0, H / (double)im.Height);
    int nw = Math.Max(1, (int)(im.Width * k));
    int nh = Math.Max(1, (int)(im.Height * k));
    return im.Resize(new SKSizeI(nw, nh), SKFilterQuality.High);
}

static void PasteL8(SKBitmap sheet, SKBitmap src, int x, int y)
{
    for (int yy = 0; yy < src.Height && y + yy < sheet.Height; yy++)
        for (int xx = 0; xx < src.Width && x + xx < sheet.Width; xx++)
            sheet.SetPixel(x + xx, y + yy, src.GetPixel(xx, yy));
}

static void SetL8(SKBitmap img, int x, int y, byte v)
{
    if (x >= 0 && y >= 0 && x < img.Width && y < img.Height)
        img.SetPixel(x, y, new SKColor(v, v, v, 255));
}

static void DrawVLine(SKBitmap img, int x, int y0, int y1, byte v)
{
    for (int y = y0; y <= y1; y++) SetL8(img, x, y, v);
}

static void DrawHLine(SKBitmap img, int y, int x0, int x1, byte v)
{
    for (int x = x0; x <= x1; x++) SetL8(img, x, y, v);
}

// json.dump(report, ensure_ascii=False, indent=1)
static string BuildReportJson(List<(string key, Rec rec)> report)
{
    using var ms = new MemoryStream();
    using (var w = new Utf8JsonWriter(ms, new JsonWriterOptions
    {
        Indented = true,
        IndentSize = 1,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping
    }))
    {
        w.WriteStartObject();
        foreach (var (key, rec) in report)
        {
            w.WritePropertyName(key);
            w.WriteStartObject();
            w.WriteString("paper", rec.paper);
            w.WriteNumber("no", rec.no);
            w.WritePropertyName("flags");
            w.WriteStartArray();
            foreach (var f in rec.flags) w.WriteStringValue(f);
            w.WriteEndArray();
            if (rec.hasDetails)
            {
                w.WritePropertyName("sizes");
                w.WriteStartObject();
                foreach (char k in "ABCD")
                {
                    var v = rec.sizes![k.ToString()];
                    w.WritePropertyName(k.ToString());
                    w.WriteStartArray();
                    w.WriteNumberValue(v[0]);
                    w.WriteNumberValue(v[1]);
                    w.WriteEndArray();
                }
                w.WriteEndObject();
                w.WritePropertyName("stem");
                if (rec.stem == null) w.WriteNullValue();
                else
                {
                    w.WriteStartArray();
                    foreach (var v in rec.stem) w.WriteNumberValue(v);
                    w.WriteEndArray();
                }
                w.WritePropertyName("edge_blobs");
                w.WriteStartObject();
                foreach (char k in "ABCD")
                    w.WriteNumber(k.ToString(), rec.edge_blobs![k.ToString()]);
                w.WriteEndObject();
            }
            w.WriteEndObject();
        }
        w.WriteEndObject();
    }
    return Encoding.UTF8.GetString(ms.ToArray());
}

// Python str(list)：元素用单引号。
static string FmtStrList(List<string> xs) =>
    "[" + string.Join(", ", xs.Select(s => "'" + s + "'")) + "]";

// ---------------- types ----------------

sealed class Rec
{
    public string paper;
    public int no;
    public List<string> flags = new();
    public bool hasDetails;
    public Dictionary<string, int[]>? sizes;
    public int[]? stem;
    public Dictionary<string, int>? edge_blobs;
    public Rec(string paper, int no) { this.paper = paper; this.no = no; }
}
