#!/usr/bin/env dotnet
// 对《2027 政治理论背诵手册》第三部分「考前 200 题」做 OCR（分片）。
//
// 用法：
//   dotnet run tools/ocr_zhengzhi.cs -- --start 189 --end 202 --out tmp/zhengzhi/ocr_a.json
//   dotnet run tools/ocr_zhengzhi.cs -- --merge
//
// 每片写自己的文件，互不干扰；跑完用 --merge 合并成 ocr_lines.json。
//
// 转换说明：
//   · PDF 渲染改用 PDFium（Docnet.Core）：GetImage() 返回 BGRA 字节，
//     交给 OCR 前用 SkiaSharp 编码成 PNG（对应 PaddleOCRSharp 的 byte[] 重载）；
//   · RapidOCR 改用 PaddleOCRSharp：OCRModelConfig + OCRParameter + PaddleOCREngine，
//     DetectText(byte[]) 取 OCRResult.TextBlocks（BoxPoints/Text/Score），全程不调用 Python；
//   · --limit（原 rapidocr 的 det_limit_side_len）映射到 OCRParameter.max_side_len；
//   · 输出仍是 {页码: [{y, x, x2, text, score}, ...]}，ensure_ascii=False、indent=1。
//
// 模型准备（PaddleOCRSharp 包内不含推理模型，需自备 PaddleOCR 官方模型）：
//   --model-dir 默认 tools/qbank/models（相对仓库根目录），目录结构：
//     tools/qbank/models/
//       det/             文字检测推理模型（inference.pdmodel / inference.pdiparams）
//       cls/             方向分类推理模型
//       rec/             文字识别推理模型
//       ppocr_keys.txt   识别字典（keys 文件）
//   获取方式：从 PaddleOCR 官方模型库下载 PP-OCR 系列 det/cls/rec 推理模型，
//   解压到对应子目录，并把字典文件放到 ppocr_keys.txt。
//   缺少 det/cls/rec 任一目录或 ppocr_keys.txt 时脚本报错并返回 2。

#:package Docnet.Core@2.6.0
#:package PaddleOCRSharp@6.2.0
#:package SkiaSharp@2.88.9
#:property PublishAot=false

using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using Docnet.Core;
using Docnet.Core.Models;
using Docnet.Core.Readers;
using PaddleOCRSharp;
using SkiaSharp;

Console.OutputEncoding = System.Text.Encoding.UTF8;

const string PDF = @"C:/Users/hiyan/Downloads/Documents/2027年政治理论背诵手册_2026102210434284.pdf";

try
{
    int? start = null, end = null;
    string outPath = "";
    int dpi = 220;
    int limit = 1800;
    bool merge = false;
    string modelDir = "tools/qbank/models";

    for (int i = 0; i < args.Length; i++)
    {
        switch (args[i])
        {
            case "--start" when i + 1 < args.Length: start = int.Parse(args[++i]); break;
            case "--end" when i + 1 < args.Length: end = int.Parse(args[++i]); break;
            case "--out" when i + 1 < args.Length: outPath = args[++i]; break;
            case "--dpi" when i + 1 < args.Length: dpi = int.Parse(args[++i]); break;
            case "--limit" when i + 1 < args.Length: limit = int.Parse(args[++i]); break;
            case "--model-dir" when i + 1 < args.Length: modelDir = args[++i]; break;
            case "--merge": merge = true; break;
        }
    }

    if (merge)
    {
        Merge("tmp/zhengzhi/ocr_*.json", "tmp/zhengzhi/ocr_lines.json");
        return 0;
    }

    if (start == null || end == null) { Console.Error.WriteLine("缺少 --start / --end"); return 2; }

    var config = new OCRModelConfig(
        Path.Combine(modelDir, "det"),
        Path.Combine(modelDir, "cls"),
        Path.Combine(modelDir, "rec"),
        Path.Combine(modelDir, "ppocr_keys.txt"));
    foreach (var d in new[] { config.det_infer, config.cls_infer, config.rec_infer })
    {
        if (!Directory.Exists(d))
        {
            Console.Error.WriteLine($"缺少 PaddleOCR 模型目录：{d}");
            Console.Error.WriteLine("请按文件头说明准备 det/cls/rec 与 ppocr_keys.txt，或用 --model-dir 指定。");
            return 2;
        }
    }
    if (!File.Exists(config.keys))
    {
        Console.Error.WriteLine($"缺少 PaddleOCR 字典文件：{config.keys}");
        Console.Error.WriteLine("请按文件头说明准备 det/cls/rec 与 ppocr_keys.txt，或用 --model-dir 指定。");
        return 2;
    }
    if (!File.Exists(PDF)) { Console.Error.WriteLine($"找不到 PDF：{PDF}"); return 2; }

    Run(config, start.Value, end.Value, outPath, dpi, limit, PDF);
    return 0;
}
catch (Exception ex)
{
    Console.Error.WriteLine(ex.Message);
    return 2;
}

// 分片识别：逐页渲染、OCR、写回 out 文件（每页写一次，支持断点续跑）。
static void Run(OCRModelConfig config, int start, int end, string outPath,
                int dpi, int limit, string pdf)
{
    var param = new OCRParameter { max_side_len = limit };
    var engine = new PaddleOCREngine(config, param);

    var data = new Dictionary<string, List<Line>>();
    if (File.Exists(outPath))
    {
        using var jd = JsonDocument.Parse(File.ReadAllText(outPath, System.Text.Encoding.UTF8));
        foreach (var prop in jd.RootElement.EnumerateObject())
            data[prop.Name] = ParseLines(prop.Value);
    }

    using var doc = DocLib.Instance.GetDocReader(pdf, new PageDimensions(dpi / 72.0));

    for (int i = start; i <= end; i++)
    {
        string key = i.ToString(CultureInfo.InvariantCulture);
        if (data.TryGetValue(key, out var existing) && existing.Count > 0) continue;

        byte[] png;
        using (var page = doc.GetPageReader(i))
        {
            int w = page.GetPageWidth(), h = page.GetPageHeight();
            png = ToPng(page.GetImage(), w, h);
        }

        var result = engine.DetectText(png);
        var lines = new List<Line>();
        foreach (var tb in result.TextBlocks ?? new List<TextBlock>())
        {
            string text = (tb.Text ?? "").Trim();
            if (text.Length == 0) continue;
            var pts = tb.BoxPoints;
            if (pts == null || pts.Count == 0) continue;
            double minX = pts.Min(p => p.X), maxX = pts.Max(p => p.X);
            double minY = pts.Min(p => p.Y), maxY = pts.Max(p => p.Y);
            lines.Add(new Line(
                Math.Round(minY, 1),
                Math.Round(minX, 1),
                Math.Round(maxX, 1),
                text,
                Math.Round((double)tb.Score, 3)));
        }
        lines.Sort((a, b) =>
        {
            int c = a.Y.CompareTo(b.Y);
            return c != 0 ? c : a.X.CompareTo(b.X);
        });
        data[key] = lines;
        Console.WriteLine($"page {i} -> {lines.Count}");
        File.WriteAllText(outPath, DumpJson(data), new System.Text.UTF8Encoding(false));
    }
    Console.WriteLine($"done {outPath} {data.Count}");
    engine.Dispose();
}

// 合并分片：按文件名升序读取 ocr_*.json，后出现的页覆盖先出现的页。
static void Merge(string pattern, string outPath)
{
    string dir = Path.GetDirectoryName(pattern) ?? ".";
    string mask = Path.GetFileName(pattern);
    var files = Directory.Exists(dir)
        ? Directory.GetFiles(dir, mask).OrderBy(p => p, StringComparer.Ordinal).ToList()
        : new List<string>();

    var merged = new Dictionary<string, List<Line>>();
    foreach (var p in files)
    {
        using var jd = JsonDocument.Parse(File.ReadAllText(p, System.Text.Encoding.UTF8));
        foreach (var prop in jd.RootElement.EnumerateObject())
            merged[prop.Name] = ParseLines(prop.Value);
    }
    File.WriteAllText(outPath, DumpJson(merged), new System.Text.UTF8Encoding(false));
    Console.WriteLine($"merged {merged.Count} pages -> {outPath}");
}

static List<Line> ParseLines(JsonElement arr)
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

// 复刻 Python json.dump(ensure_ascii=False, indent=1) 的字节格式。
static string DumpJson(Dictionary<string, List<Line>> data)
{
    if (data.Count == 0) return "{}";
    var sb = new System.Text.StringBuilder();
    sb.Append("{\n");
    int ki = 0;
    foreach (var kv in data)
    {
        sb.Append(' ').Append(Esc(kv.Key)).Append(": ");
        if (kv.Value.Count == 0)
        {
            sb.Append("[]");
        }
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

// Python repr(float)：整数值也带 ".0"。
static string Num(double v)
{
    string s = v.ToString("R", CultureInfo.InvariantCulture);
    if (!s.Contains('.') && !s.Contains('e') && !s.Contains('E')) s += ".0";
    return s;
}

// JSON 字符串转义：只处理引号、反斜杠和控制字符，非 ASCII 原样输出。
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

// BGRA（Docnet.Core GetImage）编码成 PNG 字节，供 DetectText(byte[]) 使用。
static byte[] ToPng(byte[] bgra, int w, int h)
{
    // Docnet.Core 输出 BGRA，与 Windows 上 SKColorType.Bgra8888 天然对齐。
    using var bmp = new SKBitmap(w, h, SKColorType.Bgra8888, SKAlphaType.Premul);
    Marshal.Copy(bgra, 0, bmp.GetPixels(), bgra.Length);
    using var image = SKImage.FromBitmap(bmp);
    using var data = image.Encode(SKEncodedImageFormat.Png, 100);
    return data.ToArray();
}

record Line(double Y, double X, double X2, string Text, double Score);
