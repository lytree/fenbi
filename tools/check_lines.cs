#!/usr/bin/env dotnet
// 用像素投影估计每页文本行数，和 OCR 行数比对，找出可疑漏检页。
//
// 用法：
//   dotnet run tools/check_lines.cs
//   （file-based app 的参数统一写在 -- 之后；本脚本无需参数）
//
// 转换说明：
//   · pymupdf 渲染改用 PDFium（Docnet.Core）：GetDocReader/GetPageReader 拿页，
//     GetImage() 返回 BGRA 字节，按亮度公式手写转灰度，不使用 System.Drawing；
//   · 整个工具链的 OCR 改用 PaddleOCRSharp（见 tools/ocr_zhengzhi.cs），本脚本
//     只读取 ocr_merged.json 里的 OCR 行，numpy 的逐行求和改为手写数组运算；
//   · 正文区比例（top=250/2304、bottom=2100/2304）、dark<160、thresh=width*0.004
//     以及 OCR 行 y 过滤区间 [250, 2100]、聚类容差 14 均与原脚本一致。

#:package Docnet.Core@2.6.0
#:property PublishAot=false

using System.Text.Json;
using Docnet.Core;
using Docnet.Core.Models;
using Docnet.Core.Readers;

Console.OutputEncoding = System.Text.Encoding.UTF8;

const string PDF = @"C:/Users/hiyan/Downloads/Documents/2027年政治理论背诵手册_2026102210434284.pdf";
const string OCR = @"F:/Code/Android/fenbi/tmp/zhengzhi/ocr_merged.json";
const int DPI = 110;

if (!File.Exists(OCR)) { Console.Error.WriteLine($"找不到 OCR 结果文件：{OCR}"); return 2; }
if (!File.Exists(PDF)) { Console.Error.WriteLine($"找不到 PDF：{PDF}"); return 2; }

try
{
    var ocr = LoadOcr(OCR);
    using var doc = DocLib.Instance.GetDocReader(PDF, new PageDimensions(DPI / 72.0));

    var rows = new List<(int pg, int c, int e, int d)>();
    foreach (var (pg, ys) in ocr)
    {
        // OCR 行按 y 聚类（同一行左右分栏算一行），容差 14
        int clusters = 1;
        for (int i = 1; i < ys.Count; i++)
            if (ys[i] - ys[i - 1] > 14) clusters++;

        int est;
        using (var page = doc.GetPageReader(pg)) est = InkRows(page);

        rows.Add((pg, clusters, est, clusters - est));
    }

    Console.WriteLine("page  ocr  ink  diff");
    foreach (var (pg, c, e, d) in rows)
    {
        string flag = Math.Abs(d) >= 2 ? "  <<<" : "";
        Console.WriteLine($"{pg,4} {c,4} {e,4} {d,4}{flag}");
    }
    var bad = rows.Where(r => Math.Abs(r.d) >= 2).Select(r => r.pg).ToList();
    Console.WriteLine();
    Console.WriteLine("可疑页: [" + string.Join(", ", bad) + "]");
}
catch (Exception ex)
{
    Console.Error.WriteLine(ex.Message);
    return 2;
}
return 0;

// 读取 OCR JSON：{页码字符串: [{y, x, x2, text, score}, ...]}，按页码数值排序，
// 每页取 250 <= y <= 2100 的 round(y) 去重升序（等价于 Python 的 sorted(set(...))）。
static List<(int pg, List<int> ys)> LoadOcr(string path)
{
    var list = new List<(int pg, List<int> ys)>();
    using var jd = JsonDocument.Parse(File.ReadAllText(path, System.Text.Encoding.UTF8));
    foreach (var prop in jd.RootElement.EnumerateObject())
    {
        int pg = int.Parse(prop.Name);
        var set = new SortedSet<int>();
        foreach (var ln in prop.Value.EnumerateArray())
        {
            double y = ln.GetProperty("y").GetDouble();
            if (250 <= y && y <= 2100)
                set.Add((int)Math.Round(y, MidpointRounding.ToEven));
        }
        list.Add((pg, set.ToList()));
    }
    list.Sort((a, b) => a.pg.CompareTo(b.pg));
    return list;
}

// 像素投影估计文本行数：BGRA 手写灰度（Y=0.299R+0.587G+0.114B），
// 只统计正文区 [height*250/2304, height*2100/2304)，逐行统计暗像素数，
// 连续墨迹行合并成一个文本行。
static int InkRows(IPageReader page)
{
    int width = page.GetPageWidth();
    int height = page.GetPageHeight();
    byte[] bgra = page.GetImage();

    int top = (int)(height * 250.0 / 2304);
    int bottom = (int)(height * 2100.0 / 2304);
    int thresh = Math.Max(3, (int)(width * 0.004));

    int count = 0;
    bool prev = false;
    for (int y = top; y < bottom; y++)
    {
        int dark = 0;
        int rowOff = y * width * 4;
        for (int x = 0; x < width; x++)
        {
            int i = rowOff + x * 4;
            int b = bgra[i], g = bgra[i + 1], r = bgra[i + 2];
            int lum = (int)(0.299 * r + 0.587 * g + 0.114 * b);
            if (lum < 160) dark++;
        }
        bool f = dark > thresh;
        if (f && !prev) count++;
        prev = f;
    }
    return count;
}
