#!/usr/bin/env dotnet
// 把 10 个单卷试卷包汇总成一个「超格10套卷」包。
//
// App 的规则是「一个工作表 = 一份试卷」，所以汇总 = 把 10 份卷写成同一个 试卷.xlsx 里的
// 10 张工作表（表名即卷名），图片全放进同一个 images/。
//
// 坑：单卷包里的图都叫 `images/q076.png`、`images/q076_A.png`，10 套之间会撞名。所以
// 汇总时统一加卷前缀 `s01_`、`s02_`…，并把单元格里的引用一起改掉。
//
// 产物：qbank_out/超格行测套卷/超格10套卷.zip（试卷.xlsx + images/），导入后是一个标题、
// 下面 10 份卷。
//
// 用法：
//   dotnet run tools/qbank/merge_exam_packages.cs
//   dotnet run tools/qbank/merge_exam_packages.cs -- --src qbank_out/超格行测套卷 \
//       --out qbank_out/超格行测套卷/超格10套卷.zip

#:package ClosedXML@0.105.1
#:property PublishAot=false

using System.Globalization;
using System.IO.Compression;
using System.Runtime.CompilerServices;
using System.Text.RegularExpressions;
using ClosedXML.Excel;

Console.OutputEncoding = System.Text.Encoding.UTF8;

var ROOT = Path.GetFullPath(Path.Combine(ThisDir(), "..", ".."));
var SRC = Path.Combine(ROOT, "qbank_out", "超格行测套卷");
var OUT = Path.Combine(SRC, "超格10套卷.zip");

var src = Opt(args, "--src", SRC)!;
var outPath = Opt(args, "--out", OUT)!;
_ = Opt(args, "--prefix", "超格10套卷");   // 原脚本解析了 --prefix 但未使用，这里保持一致

var imgRef = new Regex(@"images/([^)\s""']+)");

if (!Directory.Exists(src))
{
    Console.Error.WriteLine($"没找到单卷包：{src}");
    return 2;
}
var zipNames = Directory.GetFiles(src)
    .Select(Path.GetFileName)
    .Where(n => n != null && Regex.IsMatch(n, @"\A行测套卷\d+\.zip\z"))
    .Select(n => n!)
    .OrderBy(PaperNo)
    .ToList();
if (zipNames.Count == 0)
{
    Console.Error.WriteLine($"没找到单卷包：{src}");
    return 2;
}
Console.WriteLine($"汇总 {zipNames.Count} 个单卷包：{string.Join(", ", zipNames)}");

using var wb = new XLWorkbook();
foreach (var sh in wb.Worksheets.ToList()) sh.Delete();   // 去掉默认工作表，等价于 remove(active)

var images = new Dictionary<string, byte[]>(StringComparer.Ordinal);   // 包内新路径 -> bytes
var summary = new List<(string Sheet, int Rows, int Refs)>();
List<object?>? headerRef = null;

foreach (var zname in zipNames)
{
    int no = PaperNo(zname);
    string tag = "s" + no.ToString("D2", CultureInfo.InvariantCulture);
    string sheetName = "行测套卷" + no.ToString(CultureInfo.InvariantCulture);
    using var z = ZipFile.OpenRead(Path.Combine(src, zname));
    var names = new HashSet<string>(z.Entries.Select(e => e.FullName), StringComparer.Ordinal);

    var xlsxIn = ReadEntry(z, "试卷.xlsx");
    if (xlsxIn.Length == 0)
    {
        Console.Error.WriteLine($"包内缺 试卷.xlsx：{zname}");
        return 2;
    }
    using var wbIn = new XLWorkbook(new MemoryStream(xlsxIn));
    var wsIn = wbIn.Worksheet(1);
    int maxColIn = wsIn.LastColumnUsed()?.ColumnNumber() ?? 0;
    var header = new List<object?>();
    for (int c = 1; c <= maxColIn; c++) header.Add(GetCellValue(wsIn.Cell(1, c)));
    if (headerRef == null) headerRef = header;
    else if (!HeadersEqual(header, headerRef))
        Console.WriteLine($"  注意：{zname} 的表头与第一份不一致，按自己的表头写");

    var ws = wb.AddWorksheet(sheetName);
    int rows = 0, refs = 0;
    var missing = new List<string>();
    int maxRowIn = wsIn.LastRowUsed()?.RowNumber() ?? 0;
    for (int r = 1; r <= maxRowIn; r++)
    {
        var outRow = new List<object?>();
        for (int c = 1; c <= maxColIn; c++)
        {
            object? v = GetCellValue(wsIn.Cell(r, c));
            if (v is string sv && sv.Contains("images/"))
            {
                v = imgRef.Replace(sv, m => FixRef(m, tag, names, z, images, missing));
                refs += imgRef.Matches(sv).Count;
            }
            outRow.Add(v);
        }
        for (int c = 1; c <= outRow.Count; c++) SetCellValue(ws.Cell(r, c), outRow[c - 1]);
        rows++;
    }
    if (missing.Count > 0)
        Console.WriteLine($"  警告：{zname} 引用里缺图 {PyListRepr(missing.Take(5).ToList())}");
    summary.Add((sheetName, rows - 1, refs));
}

using var xlsxBuf = new MemoryStream();
wb.SaveAs(xlsxBuf);
var xlsxBytes = xlsxBuf.ToArray();

if (File.Exists(outPath)) File.Delete(outPath);
using (var ofs = new FileStream(outPath, FileMode.Create, FileAccess.Write))
using (var oz = new ZipArchive(ofs, ZipArchiveMode.Create))
{
    var xe = oz.CreateEntry("试卷.xlsx");
    using (var xs = xe.Open()) xs.Write(xlsxBytes, 0, xlsxBytes.Length);
    foreach (var kv in images.OrderBy(kv => kv.Key, StringComparer.Ordinal))
    {
        var ie = oz.CreateEntry(kv.Key);
        using (var ins = ie.Open()) ins.Write(kv.Value, 0, kv.Value.Length);
    }
}

Console.WriteLine($"工作表：{string.Join(", ", summary.Select(s => s.Sheet))}");
Console.WriteLine($"题目合计：{summary.Sum(s => s.Rows)} 行，图片引用 {summary.Sum(s => s.Refs)} 处，图片文件 {images.Count} 张");
Console.WriteLine($"汇总包：{outPath}（{(new FileInfo(outPath).Length / 1048576.0).ToString("F1", CultureInfo.InvariantCulture)} MB）");

// 回读校验：每张表的图片引用都能在 zip 里找到。
using (var rz = ZipFile.OpenRead(outPath))
{
    var names2 = new HashSet<string>(rz.Entries.Select(e => e.FullName), StringComparer.Ordinal);
    var xlsx2 = ReadEntry(rz, "试卷.xlsx");
    using var wb2 = new XLWorkbook(new MemoryStream(xlsx2));
    var bad = new List<(string Sheet, string M)>();
    foreach (var ws2 in wb2.Worksheets)
    {
        int maxRow2 = ws2.LastRowUsed()?.RowNumber() ?? 0;
        int maxCol2 = ws2.LastColumnUsed()?.ColumnNumber() ?? 0;
        for (int r = 1; r <= maxRow2; r++)
            for (int c = 1; c <= maxCol2; c++)
            {
                var val = GetCellValue(ws2.Cell(r, c));
                if (val is string sval)
                    foreach (Match m in imgRef.Matches(sval))
                        if (!names2.Contains("images/" + m.Groups[1].Value))
                            bad.Add((ws2.Name, m.Groups[1].Value));
            }
    }
    Console.WriteLine($"校验：工作表 {wb2.Worksheets.Count} 张，缺图引用 {bad.Count} 处");
    if (bad.Count > 0)
        Console.WriteLine("   " + PyTupleListRepr(bad.Take(5).ToList()));
}
return 0;

// ---------------- helpers ----------------

static string ThisDir([CallerFilePath] string p = "") => Path.GetDirectoryName(p)!;

static string? Opt(string[] a, string name, string? def = null)
{
    for (int i = 0; i < a.Length; i++)
    {
        if (a[i] == name && i + 1 < a.Length) return a[i + 1];
        if (a[i].StartsWith(name + "=", StringComparison.Ordinal)) return a[i][(name.Length + 1)..];
    }
    return def;
}

static int PaperNo(string name)
{
    var m = Regex.Match(name, @"(\d+)");
    return m.Success ? int.Parse(m.Groups[1].Value) : 0;
}

static byte[] ReadEntry(ZipArchive z, string name)
{
    var e = z.GetEntry(name);
    if (e == null) return Array.Empty<byte>();
    using var s = e.Open();
    using var ms = new MemoryStream();
    s.CopyTo(ms);
    return ms.ToArray();
}

// IMG_REF.sub(fix, v)：把 images/q076.png 改写成 images/s01_q076.png，顺带收集图片字节 / 缺图。
static string FixRef(Match m, string tag, HashSet<string> names, ZipArchive z,
                     Dictionary<string, byte[]> images, List<string> missing)
{
    var g = m.Groups[1].Value;
    var newRef = "images/" + tag + "_" + g;
    var old = "images/" + g;
    if (!names.Contains(old)) missing.Add(g);
    else if (!images.ContainsKey(newRef)) images[newRef] = ReadEntry(z, old);
    return newRef;
}

static object? GetCellValue(IXLCell c)
{
    var v = c.Value;
    if (v.IsBlank) return null;
    if (v.IsBoolean) return v.GetBoolean();
    if (v.IsNumber) return v.GetNumber();
    if (v.IsDateTime) return v.GetDateTime();
    if (v.IsTimeSpan) return v.GetTimeSpan();
    if (v.IsText) return v.GetText();
    return v.ToString();
}

static void SetCellValue(IXLCell cell, object? v)
{
    if (v == null) return;
    cell.Value = XLCellValue.FromObject(v, CultureInfo.InvariantCulture);
}

static bool HeadersEqual(List<object?> a, List<object?> b)
{
    if (a.Count != b.Count) return false;
    for (int i = 0; i < a.Count; i++)
        if (!object.Equals(a[i], b[i])) return false;
    return true;
}

static string PyStrRepr(string s) =>
    "'" + s.Replace("\\", "\\\\").Replace("'", "\\'")
           .Replace("\n", "\\n").Replace("\r", "\\r").Replace("\t", "\\t") + "'";

static string PyListRepr(List<string> items) => "[" + string.Join(", ", items.Select(PyStrRepr)) + "]";

static string PyTupleListRepr(List<(string Sheet, string M)> items) =>
    "[" + string.Join(", ", items.Select(t => "(" + PyStrRepr(t.Sheet) + ", " + PyStrRepr(t.M) + ")")) + "]";
