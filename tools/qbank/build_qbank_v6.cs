#!/usr/bin/env dotnet
// 从图形推理 PDF 构建结构化 700 题库（v6）。
//
// 用法：
//   dotnet run tools/qbank/build_qbank_v6.cs
//   （file-based app 的参数统一写在 -- 之后；本脚本无需参数）
//
// 为什么是 v6：PDF 没有文本层，但每道题以印刷题号（"1." ... "20."）开头，
//   题号固定在左边距（150dpi 下 x 约 176-221），与题干同一行。对左边距窄条
//   （x 172-240）以 400dpi 用数字白名单做 OCR，取每个 token 的前导数字作为题号；
//   35 组里有 33 组恰好得到顺序的 20 个号，是裁图完美的 1:1 锚点。
//
// 裁图：top = 题号行 y - PAD_TOP
//       bottom = 下一题题号行 y - PAD_TOP（同页）
//                或本页最后一行内容（跨页）
//
// 产物：images/s{ss}_q{nn}.png、questions.json、questions.csv
//
// 转换说明：
//   · pymupdf 渲染改用 PDFium（Docnet.Core）：GetImage() 返回 BGRA，手写转成 RGB
//     byte[]，按亮度均值手写 dk2/dark 投影，裁图用 SkiaSharp 存 PNG；
//   · numpy/PIL 改为手写数组运算 + SkiaSharp，不调用 Python；
//   · 题号锚点仍来自外部 ocr_margin.json（由 OCR 步骤生成，本脚本不直接跑 OCR；
//     工具链整体的 OCR 改用 PaddleOCRSharp，见 tools/ocr_zhengzhi.cs）。
//   · 裁图坐标公式 PAD_TOP=12 / PAD_BOT=10、图片命名 s{ss}_q{nn}.png、
//     questions.json/csv 字段、DIVIDERS / ANSWER_KEY / PATCH 均与原脚本一致。

#:package Docnet.Core@2.6.0
#:package SkiaSharp@2.88.9
#:property PublishAot=false

using System.Linq;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.RegularExpressions;
using Docnet.Core;
using Docnet.Core.Models;
using Docnet.Core.Readers;
using SkiaSharp;

Console.OutputEncoding = System.Text.Encoding.UTF8;

const string SRC = @"G:/Share/【图推】2027刘义恒图形推理700题/讲义/图形推理700题讲义.pdf";
const int DPI = 150;
const int PAD_TOP = 12;          // 题号行上方保留的空白
const int PAD_BOT = 10;          // 裁图下方保留的空白

string ROOT = Path.GetFullPath(Path.Combine(ThisDir(), "..", ".."));
string OUT = Path.Combine(ROOT, "qbank_out");
string IMG_DIR = Path.Combine(OUT, "images");
string OCR_JSON = Path.Combine(OUT, "ocr_margin.json");

int[] DIVIDERS = {
    7, 13, 21, 28, 35, 42, 49, 56, 63, 70, 78, 86, 93, 101, 108, 115, 122, 130, 138, 146,
    154, 162, 170, 178, 185, 193, 201, 209, 216, 223, 231, 238, 245, 253, 261
};

string[] RAW_KEY = {
    "DCABA CACCD CBBCB CDDBA", "ADACD CCDDC ADABC BBABC", "DACAB CABDD DCCCA DCDDC",
    "DACAD DBBDA ACBBB BACCA", "CABCB ABBAB CABCA BBBAC", "ADADC CADAD DBCAB CCACA",
    "ABACD CACCA ADCDB BACBD", "DDABD ABDCD BBBDC DBCAD", "AAABC CCDAA CDABA ACBDB",
    "ABBAD BABCC BAADC DDBDB", "DAADA DDACC CDCCC ABCCA", "ABADB DDBAD BDBDB DAADB",
    "BADDC BDABB DDBAB CBDAD", "DDAAB DBACB ADACB CDABB", "DCDAA DCDAD DABAA CCABB",
    "BBADC ACABB ACCDD CCBDB", "CDACB DAACA ABABD DDDBB", "DBACC BBCDD ACBCB BCDBA",
    "DCDDB CBCBC ABACA BCCDB", "CDACD CBCCB CDCBD DBCDC", "DCABD ADCBA CCACC BCBBA",
    "ABDCD DADCC BDCAC CCDCD", "ADAAA BDADB DDBAD BBAAD", "BACDD BABCA BDADC CBADB",
    "BACBC CACCA ACDBA BDDBD", "AACAC CDDCD DBDBA CBDBD", "CDADA CCBDB ACBCA BABBB",
    "CCCAB ACCDB CACCA AABDB", "DCDAB ABDCC DDBCD ACAAA", "BAABC CAADC DBDBB BCDCD",
    "CDBCD BADCC ADADA DAAAB", "ACACD BADDB CBACA BDBCA", "DBACD ACACB CAACC ACDAD",
    "BCCCD DAAAD DBACB DCDDB", "BACAA BADAC ABDAC BDDBD",
};
string[] ANSWER_KEY = RAW_KEY.Select(s => s.Replace(" ", "")).ToArray();

// OCR 少数题号行识别失败；坐标经人工核对。
Dictionary<(int s, int v), (int page, int y)> PATCH = new()
{
    [(21, 4)] = (156, 212),
    [(21, 5)] = (156, 533),
    [(21, 6)] = (156, 906),
    [(34, 3)] = (254, 1041),
};

if (ANSWER_KEY.Length != 35 || ANSWER_KEY.Any(a => a.Length != 20))
{
    Console.Error.WriteLine("ANSWER_KEY 长度校验失败（应为 35 行，每行 20 个答案）");
    return 2;
}
if (!File.Exists(OCR_JSON)) { Console.Error.WriteLine($"找不到 OCR 锚点文件：{OCR_JSON}"); return 2; }
if (!File.Exists(SRC)) { Console.Error.WriteLine($"找不到 PDF：{SRC}"); return 2; }

try
{
    var ocr = LoadOcr(OCR_JSON);
    Directory.CreateDirectory(IMG_DIR);
    using var doc = DocLib.Instance.GetDocReader(SRC, new PageDimensions(DPI / 72.0));
    var cache = new Dictionary<int, PageData>();

    var records = new List<Rec>();
    var problems = new List<Problem>();
    for (int s = 1; s <= 35; s++)
    {
        var got = BuildAnchors(ocr, s, DIVIDERS, PATCH);
        var anchors = new List<(int v, int pno, int y)>();
        for (int v = 1; v <= 20; v++)
        {
            var (page, y) = got[v];
            anchors.Add((v, page, y));
        }
        anchors.Sort((x, z) =>
        {
            int c = x.pno.CompareTo(z.pno);
            if (c != 0) return c;
            c = x.y.CompareTo(z.y);
            return c != 0 ? c : x.v.CompareTo(z.v);
        });

        for (int i = 0; i < anchors.Count; i++)
        {
            var (v, pno, y) = anchors[i];
            var arr = PageArr(doc, cache, pno);
            int H = arr.H;
            int top = Math.Max(0, y - PAD_TOP);
            int bottom;
            if (i + 1 < anchors.Count && anchors[i + 1].pno == pno)
                bottom = anchors[i + 1].y - PAD_TOP - PAD_BOT;
            else
                bottom = ContentBottom(arr, y + 40) + PAD_BOT;
            bottom = Math.Min(Math.Max(bottom, top + 120), H);

            string fname = $"s{s:D2}_q{v:D2}.png";
            SaveCrop(arr, top, bottom, Path.Combine(IMG_DIR, fname));
            int h = bottom - top;
            if (h < 200 || h > 800)
                problems.Add(new Problem(fname, pno, top, bottom, h));
            records.Add(new Rec(s, v, fname, ANSWER_KEY[s - 1][v - 1].ToString(),
                                pno, top, bottom));
        }
    }

    File.WriteAllText(Path.Combine(OUT, "questions.json"),
        DumpRecords(records), new System.Text.UTF8Encoding(false));
    File.WriteAllText(Path.Combine(OUT, "questions.csv"),
        DumpCsv(records), new System.Text.UTF8Encoding(false));

    Console.WriteLine($"total records: {records.Count}");
    Console.WriteLine($"suspicious heights: {problems.Count}");
    foreach (var p in problems)
        Console.WriteLine($"    ('{p.Fname}', {p.Pno}, {p.Top}, {p.Bottom}, {p.H})");
}
catch (Exception ex)
{
    Console.Error.WriteLine(ex.Message);
    return 2;
}
return 0;

// tools/qbank -> 仓库根目录。
static string ThisDir([CallerFilePath] string p = "") => Path.GetDirectoryName(p)!;

// OCR = {int(k): v for k, v in json.load(ocr_margin.json).items()}
static Dictionary<int, List<MarginToken>> LoadOcr(string path)
{
    var d = new Dictionary<int, List<MarginToken>>();
    using var jd = JsonDocument.Parse(File.ReadAllText(path, System.Text.Encoding.UTF8));
    foreach (var prop in jd.RootElement.EnumerateObject())
    {
        int pno = int.Parse(prop.Name);
        var list = new List<MarginToken>();
        foreach (var e in prop.Value.EnumerateArray())
            list.Add(new MarginToken(
                e.GetProperty("text").GetString() ?? "",
                e.GetProperty("y0").GetDouble()));
        d[pno] = list;
    }
    return d;
}

// [(y, value)]，按 y 升序（同 y 时按 value，等价 Python 元组排序）。
static List<(int y, int v)> PageNumbers(Dictionary<int, List<MarginToken>> ocr, int pno)
{
    var result = new List<(int y, int v)>();
    if (!ocr.TryGetValue(pno, out var tokens)) return result;
    foreach (var t in tokens)
    {
        var m = Regex.Match(t.Text, @"^(\d{1,2})\.");
        if (!m.Success) continue;
        int v = int.Parse(m.Groups[1].Value);
        if (1 <= v && v <= 20)
            result.Add(((int)t.Y0, v));
    }
    result.Sort((a, b) =>
    {
        int c = a.y.CompareTo(b.y);
        return c != 0 ? c : a.v.CompareTo(b.v);
    });
    return result;
}

// {value: (page, y)}，严格 1..20 单调阅读顺序，缺失用 PATCH 补，仍缺则抛错。
static Dictionary<int, (int page, int y)> BuildAnchors(
    Dictionary<int, List<MarginToken>> ocr, int s, int[] dividers,
    Dictionary<(int s, int v), (int page, int y)> patch)
{
    int a = dividers[s - 1];
    int b = s < 35 ? dividers[s] - 1 : 267;
    var seq = new List<(int pno, int y, int v)>();
    for (int pno = a; pno <= b; pno++)
        foreach (var (y, v) in PageNumbers(ocr, pno))
            seq.Add((pno, y, v));
    seq.Sort((x, z) =>
    {
        int c = x.pno.CompareTo(z.pno);
        if (c != 0) return c;
        c = x.y.CompareTo(z.y);
        return c != 0 ? c : x.v.CompareTo(z.v);
    });

    var got = new Dictionary<int, (int page, int y)>();
    int last = -1;
    foreach (var (pno, y, v) in seq)
    {
        if (v <= last || got.ContainsKey(v)) continue;
        got[v] = (pno, y);
        last = v;
    }
    for (int v = 1; v <= 20; v++)
        if (!got.ContainsKey(v) && patch.TryGetValue((s, v), out var pv))
            got[v] = pv;

    var missing = new List<int>();
    for (int v = 1; v <= 20; v++)
        if (!got.ContainsKey(v)) missing.Add(v);
    if (missing.Count > 0)
        throw new Exception($"set {s}: missing numbers [{string.Join(", ", missing)}]");
    return got;
}

// 渲染并缓存页：doc[pno-1] @150dpi，BGRA -> RGB，并预计算 g<215 的 dk2/逐行 dark。
static PageData PageArr(IDocReader doc, Dictionary<int, PageData> cache, int pno)
{
    if (!cache.TryGetValue(pno, out var pd))
    {
        using var page = doc.GetPageReader(pno - 1);
        int W = page.GetPageWidth(), H = page.GetPageHeight();
        byte[] bgra = page.GetImage();
        var rgb = new byte[H * W * 3];
        var dk2 = new byte[H * W];
        var dark = new int[H];
        for (int y = 0; y < H; y++)
        {
            int count = 0;
            int off4 = y * W * 4;
            int off3 = y * W * 3;
            for (int x = 0; x < W; x++)
            {
                int i = off4 + x * 4;
                byte r = bgra[i + 2], g = bgra[i + 1], b = bgra[i];
                int j = off3 + x * 3;
                rgb[j] = r; rgb[j + 1] = g; rgb[j + 2] = b;
                int idx = y * W + x;
                // mean(R,G,B) < 215  <=>  R+G+B < 645
                if (r + g + b < 645) { dk2[idx] = 1; count++; }
            }
            dark[y] = count;
        }
        pd = new PageData(W, H, rgb, dk2, dark);
        cache[pno] = pd;
    }
    return pd;
}

// 题目底部：跳过页脚，遇到超过 gap 的竖向空白即停，再去掉居中的孤立页码小块。
static int ContentBottom(PageData a, double yFrom, int gap = 160)
{
    int H = a.H, W = a.W;
    int thr = Math.Max(3, (int)(0.004 * W));
    int limit = (int)(H * 0.94);
    int yf = (int)yFrom;

    var ink = new List<int>();
    for (int y = yf; y < limit; y++)
        if (a.Dark[y] > thr) ink.Add(y);
    if (ink.Count == 0)
        return Math.Min(yf + 200, limit);

    int last = ink[0];
    for (int i = 1; i < ink.Count; i++)
    {
        if (ink[i] - last > gap) break;
        last = ink[i];
    }

    for (int iter = 0; iter < 3; iter++)
    {
        var rows = new List<int>();
        for (int y = yf; y <= last; y++)
            if (a.Dark[y] > thr) rows.Add(y);
        if (rows.Count == 0) break;

        var lines = new List<(int y0, int y1)> { (rows[0], rows[0]) };
        for (int i = 1; i < rows.Count; i++)
        {
            int y = rows[i];
            var cur = lines[lines.Count - 1];
            if (y - cur.y1 <= 6) lines[lines.Count - 1] = (cur.y0, y);
            else lines.Add((y, y));
        }
        if (lines.Count < 2) break;

        int y0 = lines[lines.Count - 1].y0;
        int y1 = lines[lines.Count - 1].y1;
        int prevEnd = lines[lines.Count - 2].y1;
        if (y0 - prevEnd < 24) break;           // 与上文相连，属于题目本身

        int minX = W, maxX = -1;
        for (int x = 0; x < W; x++)
        {
            bool any = false;
            for (int y = y0; y <= y1; y++)
                if (a.Dk2[y * W + x] != 0) { any = true; break; }
            if (any) { if (x < minX) minX = x; if (x > maxX) maxX = x; }
        }
        if (maxX < 0) break;
        double wfrac = (maxX - minX) / (double)W;
        double cfrac = (maxX + minX) / 2.0 / W;
        if (wfrac < 0.18 && 0.33 < cfrac && cfrac < 0.67)
            last = prevEnd;                      // 丢掉这个居中孤立小块
        else
            break;
    }
    return last;
}

// arr[top:bottom] 存成 PNG（RGB 三通道）。
static void SaveCrop(PageData a, int top, int bottom, string path)
{
    int h = bottom - top;
    byte[] crop = new byte[h * a.W * 3];
    Array.Copy(a.Rgb, top * a.W * 3, crop, 0, crop.Length);
    // Rgb24 三通道补 X 成 Rgb888x（4 字节/像素）：SkiaSharp 无 3 字节颜色类型。
    var buf = new byte[h * a.W * 4];
    for (int i = 0, j = 0; i < crop.Length; i += 3, j += 4)
    {
        buf[j] = crop[i];         // R
        buf[j + 1] = crop[i + 1]; // G
        buf[j + 2] = crop[i + 2]; // B
        buf[j + 3] = 255;         // X
    }
    using var bmp = new SKBitmap(a.W, h, SKColorType.Rgb888x, SKAlphaType.Opaque);
    Marshal.Copy(buf, 0, bmp.GetPixels(), buf.Length);
    using var image = SKImage.FromBitmap(bmp);
    using var data = image.Encode(SKEncodedImageFormat.Png, 100);
    using var fs = File.Create(path);
    data.SaveTo(fs);
}

// 复刻 json.dump(records, ensure_ascii=False, indent=2)。
static string DumpRecords(List<Rec> records)
{
    if (records.Count == 0) return "[]";
    var sb = new System.Text.StringBuilder();
    sb.Append("[\n");
    for (int i = 0; i < records.Count; i++)
    {
        var r = records[i];
        sb.Append("  {\n");
        sb.Append("    \"set\": ").Append(r.Set).Append(",\n");
        sb.Append("    \"qnum\": ").Append(r.Qnum).Append(",\n");
        sb.Append("    \"image\": ").Append(JStr(r.Image)).Append(",\n");
        sb.Append("    \"answer\": ").Append(JStr(r.Answer)).Append(",\n");
        sb.Append("    \"page\": ").Append(r.Page).Append(",\n");
        sb.Append("    \"top\": ").Append(r.Top).Append(",\n");
        sb.Append("    \"bottom\": ").Append(r.Bottom).Append('\n');
        sb.Append("  }");
        if (i < records.Count - 1) sb.Append(',');
        sb.Append('\n');
    }
    sb.Append(']');
    return sb.ToString();
}

// 复刻 csv.DictWriter（excel 方言：逗号分隔、\r\n 行尾，本脚本字段无需引号）。
static string DumpCsv(List<Rec> records)
{
    var sb = new System.Text.StringBuilder();
    sb.Append("set,qnum,image,answer,page,top,bottom\r\n");
    foreach (var r in records)
        sb.Append(r.Set).Append(',').Append(r.Qnum).Append(',')
          .Append(r.Image).Append(',').Append(r.Answer).Append(',')
          .Append(r.Page).Append(',').Append(r.Top).Append(',')
          .Append(r.Bottom).Append("\r\n");
    return sb.ToString();
}

// JSON 字符串转义：只处理引号、反斜杠和控制字符，非 ASCII 原样输出。
static string JStr(string s)
{
    var sb = new System.Text.StringBuilder();
    sb.Append('"');
    foreach (char c in s)
    {
        switch (c)
        {
            case '"': sb.Append("\\\""); break;
            case '\\': sb.Append("\\\\"); break;
            case '\b': sb.Append("\\b"); break;
            case '\f': sb.Append("\\f"); break;
            case '\n': sb.Append("\\n"); break;
            case '\r': sb.Append("\\r"); break;
            case '\t': sb.Append("\\t"); break;
            default:
                if (c < 0x20) sb.Append("\\u").Append(((int)c).ToString("x4"));
                else sb.Append(c);
                break;
        }
    }
    sb.Append('"');
    return sb.ToString();
}

record MarginToken(string Text, double Y0);
record PageData(int W, int H, byte[] Rgb, byte[] Dk2, int[] Dark);
record Rec(int Set, int Qnum, string Image, string Answer, int Page, int Top, int Bottom);
record Problem(string Fname, int Pno, int Top, int Bottom, int H);
