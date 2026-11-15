#!/usr/bin/env dotnet
// 通用 PDF 逐页 OCR（PaddleOCRSharp + PDFium 渲染），按页增量写盘，可断点续跑。
//
// 用法：
//   dotnet run tools/ocr_pdf.cs -- --pdf <文件> --out tmp/ocr/xxx.json --start 0 --end 38
//   dotnet run tools/ocr_pdf.cs -- --pdf <文件> --out tmp/ocr/xxx.json --pages 0,5,9
//   dotnet run tools/ocr_pdf.cs -- --merge tmp/ocr            # 合并目录下 xxx_p*.json
//
// 参数：
//   --pdf       必需，PDF 绝对路径
//   --out       必需，输出 json（每页写一次，已存在的页跳过）
//   --start/--end  页区间（0 基，含 end）
//   --pages     指定页（0 基，逗号分隔）
//   --dpi       渲染 dpi，默认 220
//   --limit     OCR max_side_len，默认 2000
//   --model-dir PaddleOCR 模型目录，默认 tools/qbank/models
//
// 输出格式：{ "页号(0基)": [ {y,x,x2,text,score}, ... ] }，行按 y、x 排序。
//
// 运行前准备（模型不入库，tools/qbank/models/ 已进 .gitignore）：
//   · PaddleOCRSharp 6.2 只认新格式模型（inference.json + inference.pdiparams），
//     .pdmodel 老格式会报「no longer support from v6.1.0」：
//       curl -O https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv5_mobile_det_infer.tar
//       curl -O https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv5_mobile_rec_infer.tar
//       curl -O https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-LCNet_x1_0_textline_ori_infer.tar
//     解压后分别放到 tools/qbank/models/{det,rec,cls}；识别字典从 rec 的 inference.yml
//     里 PostProcess.character_dict 导出成 tools/qbank/models/ppocr_keys.txt。
//   · 社区版 PaddleOCR.dll 与 paddle_inference 等 native 库不在 runtimes/ 下，
//     要把 paddle.runtime.win_x64/build/win_x64/*.dll 与 PaddleOCRSharp/build/ytLib/PaddleOCR.dll
//     放到同一个目录，再把该目录加进 PATH，否则报 DllNotFoundException。
//   · 单页文字框超过 100 个（密排导图页）社区版会抛异常，脚本会自动按横向条带切分重试。

#:package Docnet.Core@2.6.0
#:package PaddleOCRSharp@6.2.0
#:package SkiaSharp@2.88.9
#:property PublishAot=false

using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using Docnet.Core;
using Docnet.Core.Models;
using PaddleOCRSharp;
using SkiaSharp;

Console.OutputEncoding = System.Text.Encoding.UTF8;

static string RepoRoot([System.Runtime.CompilerServices.CallerFilePath] string self = "") =>
    Path.GetFullPath(Path.Combine(Path.GetDirectoryName(self)!, ".."));

string? pdf = null, outPath = null, modelDir = Path.Combine(RepoRoot(), "tools/qbank/models");
int? start = null, end = null;
var pages = new List<int>();
int dpi = 220, limit = 2000;
string? mergeDir = null;

for (int i = 0; i < args.Length; i++)
{
    string a = args[i];
    string? Nxt() => i + 1 < args.Length ? args[++i] : null;
    switch (a)
    {
        case "--pdf": pdf = Nxt(); break;
        case "--out": outPath = Nxt(); break;
        case "--start": start = int.Parse(Nxt()!, CultureInfo.InvariantCulture); break;
        case "--end": end = int.Parse(Nxt()!, CultureInfo.InvariantCulture); break;
        case "--dpi": dpi = int.Parse(Nxt()!, CultureInfo.InvariantCulture); break;
        case "--limit": limit = int.Parse(Nxt()!, CultureInfo.InvariantCulture); break;
        case "--model-dir": modelDir = Nxt()!; break;
        case "--merge": mergeDir = Nxt(); break;
        case "--pages":
            foreach (var s in (Nxt() ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries))
                pages.Add(int.Parse(s.Trim(), CultureInfo.InvariantCulture));
            break;
        default:
            Console.Error.WriteLine($"无法识别的参数：{a}");
            return 2;
    }
}

if (mergeDir != null)
{
    var files = Directory.GetFiles(mergeDir, "*_p*.json").OrderBy(p => p, StringComparer.Ordinal).ToList();
    var merged = new SortedDictionary<int, List<Line>>();
    foreach (var f in files)
    {
        using var jd = JsonDocument.Parse(File.ReadAllText(f, System.Text.Encoding.UTF8));
        foreach (var prop in jd.RootElement.EnumerateObject())
            merged[int.Parse(prop.Name, CultureInfo.InvariantCulture)] = ReadLines(prop.Value);
    }
    Console.WriteLine($"merged {merged.Count} pages from {files.Count} files");
    return 0;
}

if (pdf == null || outPath == null) { Console.Error.WriteLine("缺少 --pdf / --out"); return 2; }
if (!File.Exists(pdf)) { Console.Error.WriteLine($"找不到 PDF：{pdf}"); return 2; }

var config = new OCRModelConfig(
    Path.Combine(modelDir, "det"),
    Path.Combine(modelDir, "cls"),
    Path.Combine(modelDir, "rec"),
    Path.Combine(modelDir, "ppocr_keys.txt"));
if (!Directory.Exists(config.det_infer) || !Directory.Exists(config.rec_infer))
{
    Console.Error.WriteLine($"缺少 PaddleOCR 模型：{modelDir}（det/rec/cls + ppocr_keys.txt）");
    return 2;
}

Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(outPath))!);

var data = new SortedDictionary<int, List<Line>>();
if (File.Exists(outPath))
{
    using var jd = JsonDocument.Parse(File.ReadAllText(outPath, System.Text.Encoding.UTF8));
    foreach (var prop in jd.RootElement.EnumerateObject())
        data[int.Parse(prop.Name, CultureInfo.InvariantCulture)] = ReadLines(prop.Value);
}

var engine = new PaddleOCREngine(config, new OCRParameter { max_side_len = limit });
using var doc = DocLib.Instance.GetDocReader(pdf, new PageDimensions(dpi / 72.0));

int total = doc.GetPageCount();
int from = start ?? 0;
int to = end ?? total - 1;
var todo = pages.Count > 0
    ? pages.Where(p => p >= 0 && p < total).ToList()
    : Enumerable.Range(from, Math.Max(0, Math.Min(to, total - 1) - from + 1)).ToList();

Console.WriteLine($"pdf {Path.GetFileName(pdf)} pages {total}，本次处理 {todo.Count} 页");
foreach (int i in todo)
{
    if (data.TryGetValue(i, out var cached) && cached.Count > 0) continue;

    byte[] png;
    using (var page = doc.GetPageReader(i))
    {
        int w = page.GetPageWidth(), h = page.GetPageHeight();
        png = ToPng(page.GetImage(), w, h);
    }

    var lines = Detect(engine, png);
    lines.Sort((a, b) => a.Y != b.Y ? a.Y.CompareTo(b.Y) : a.X.CompareTo(b.X));
    data[i] = lines;
    File.WriteAllText(outPath, DumpJson(data), new System.Text.UTF8Encoding(false));
    Console.WriteLine($"page {i} -> {lines.Count}");
}
engine.Dispose();
Console.WriteLine($"done {outPath} pages {data.Count}");
return 0;

/// <summary>
/// 一页识别。社区版限制单次最多 100 个文字框，密排的导图页会直接抛异常，
/// 这类页按横向条带切开分别识别，再把 y 偏移回整页坐标（带与带之间留 80px 重叠，
/// 落进重叠区的重复行按文本去重）。
/// </summary>
static List<Line> Detect(PaddleOCREngine engine, byte[] png)
{
    try
    {
        return Extract(engine.DetectText(png));
    }
    catch (Exception)
    {
        var outLines = new List<Line>();
        using var bmp = SKBitmap.Decode(png);
        if (bmp == null) throw;
        int w = bmp.Width, h = bmp.Height;
        foreach (int bands in new[] { 2, 3, 4 })
        {
            outLines.Clear();
            int step = h / bands;
            const int overlap = 80;
            try
            {
                for (int b = 0; b < bands; b++)
                {
                    int y0 = Math.Max(0, b * step - (b == 0 ? 0 : overlap));
                    int y1 = Math.Min(h, (b + 1) * step + overlap);
                    using var crop = new SKBitmap(w, y1 - y0, SKColorType.Bgra8888, SKAlphaType.Premul);
                    using (var canvas = new SKCanvas(crop))
                        canvas.DrawBitmap(bmp, new SKRect(0, y0, w, y1), new SKRect(0, 0, w, y1 - y0));
                    using var img = SKImage.FromBitmap(crop);
                    using var data = img.Encode(SKEncodedImageFormat.Png, 100);
                    foreach (var l in Extract(engine.DetectText(data.ToArray())))
                        outLines.Add(l with { Y = l.Y + y0 });
                }
                break;
            }
            catch (Exception)
            {
                if (bands == 4) throw;
            }
        }
        // 叠区里同一行可能被两条带各识别一次
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var dedup = new List<Line>();
        foreach (var l in outLines.OrderBy(l => l.Y).ThenBy(l => l.X))
            if (seen.Add($"{Math.Round(l.Y / 6)}|{l.Text}")) dedup.Add(l);
        return dedup;
    }
}

static List<Line> Extract(OCRResult result)
{
    var lines = new List<Line>();
    foreach (var tb in result.TextBlocks ?? new List<TextBlock>())
    {
        string text = (tb.Text ?? "").Trim();
        if (text.Length == 0) continue;
        var pts = tb.BoxPoints;
        if (pts == null || pts.Count == 0) continue;
        lines.Add(new Line(
            Math.Round((double)pts.Min(p => p.Y), 1),
            Math.Round((double)pts.Min(p => p.X), 1),
            Math.Round((double)pts.Max(p => p.X), 1),
            text,
            Math.Round((double)tb.Score, 3)));
    }
    return lines;
}

static List<Line> ReadLines(JsonElement arr)
{
    var list = new List<Line>();
    foreach (var e in arr.EnumerateArray())
        list.Add(new Line(
            e.GetProperty("y").GetDouble(),
            e.GetProperty("x").GetDouble(),
            e.GetProperty("x2").GetDouble(),
            e.GetProperty("text").GetString() ?? "",
            e.GetProperty("score").GetDouble()));
    return list;
}

static string DumpJson(SortedDictionary<int, List<Line>> data)
{
    var sb = new System.Text.StringBuilder();
    sb.Append("{\n");
    int ki = 0;
    foreach (var kv in data)
    {
        sb.Append(" \"").Append(kv.Key.ToString(CultureInfo.InvariantCulture)).Append("\": ");
        if (kv.Value.Count == 0) sb.Append("[]");
        else
        {
            sb.Append("[\n");
            for (int j = 0; j < kv.Value.Count; j++)
            {
                var l = kv.Value[j];
                sb.Append("  {\n");
                sb.Append("   \"y\": ").Append(Num(l.Y)).Append(",\n");
                sb.Append("   \"x\": ").Append(Num(l.X)).Append(",\n");
                sb.Append("   \"x2\": ").Append(Num(l.X2)).Append(",\n");
                sb.Append("   \"text\": ").Append(Esc(l.Text)).Append(",\n");
                sb.Append("   \"score\": ").Append(Num(l.Score)).Append('\n');
                sb.Append("  }");
                if (j < kv.Value.Count - 1) sb.Append(',');
                sb.Append('\n');
            }
            sb.Append(" ]");
        }
        if (ki < data.Count - 1) sb.Append(',');
        sb.Append('\n');
        ki++;
    }
    sb.Append('}');
    return sb.ToString();
}

static string Num(double v)
{
    string s = v.ToString("R", CultureInfo.InvariantCulture);
    if (!s.Contains('.') && !s.Contains('e') && !s.Contains('E')) s += ".0";
    return s;
}

static string Esc(string s)
{
    var sb = new System.Text.StringBuilder();
    sb.Append('"');
    foreach (char c in s)
    {
        switch (c)
        {
            case '"': sb.Append("\\\""); break;
            case '\\': sb.Append("\\\\"); break;
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

static byte[] ToPng(byte[] bgra, int w, int h)
{
    using var bmp = new SKBitmap(w, h, SKColorType.Bgra8888, SKAlphaType.Premul);
    Marshal.Copy(bgra, 0, bmp.GetPixels(), bgra.Length);
    using var image = SKImage.FromBitmap(bmp);
    using var data = image.Encode(SKEncodedImageFormat.Png, 100);
    return data.ToArray();
}

record Line(double Y, double X, double X2, string Text, double Score);
