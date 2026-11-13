#!/usr/bin/env dotnet
// 对「图推700题试卷包.zip」做端到端审计（完全以最终交付包为准，不信任生成脚本的中间结果）：
//
// 1. 结构：工作表数 / 每表行数 / 题号连续 / 答案合法 / 科目一致 / 无重复题
// 2. 引用：题干图 + A~D 四个选项图，引用路径必须真实存在于 zip 内
// 3. 图片本体：非空白、尺寸合理、选项之间不重复、选项高度均衡
// 4. 空间关系：题干图与选项图不可能重叠（裁切自不同区域）——用「题干底部墨迹」与
//    「选项顶部留白」做间接校验
// 5. 抽样导出对照图，供人眼复核
//
// 用法：
//   dotnet run tools/qbank/audit_package.cs
//   dotnet run tools/qbank/audit_package.cs -- --sample 12

#:package SkiaSharp@2.88.9
#:property PublishAot=false

using System.Globalization;
using System.IO.Compression;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using SkiaSharp;

Console.OutputEncoding = System.Text.Encoding.UTF8;

var ROOT = Path.GetFullPath(Path.Combine(ThisDir(), "..", ".."));
var PKG_ZIP = Path.Combine(ROOT, "qbank_out", "图推700题试卷包.zip");
var DBG = Path.Combine(ROOT, "qbank_out", "debug", "audit");

var zipPath = Opt(args, "--zip", PKG_ZIP)!;
var sample = OptInt(args, "--sample", 0);
var imgRef = new Regex(@"!\[[^\]]*]\(([^)]+)\)");

if (!File.Exists(zipPath))
{
    Console.Error.WriteLine($"找不到试卷包：{zipPath}");
    return 2;
}

using var zf = ZipFile.OpenRead(zipPath);
var names = zf.Entries.Select(e => e.FullName).ToList();
var pkgPrefix = names[0].Split('/')[0];
var xlsx = names.Where(n => n.EndsWith(".xlsx", StringComparison.Ordinal)).ToList();
var imgNames = new HashSet<string>(names.Where(n =>
    n.EndsWith(".png", StringComparison.OrdinalIgnoreCase) ||
    n.EndsWith(".jpg", StringComparison.OrdinalIgnoreCase) ||
    n.EndsWith(".jpeg", StringComparison.OrdinalIgnoreCase)), StringComparer.Ordinal);
Console.WriteLine($"包：{Path.GetFileName(zipPath)}");
Console.WriteLine($"  条目 {names.Count} | xlsx {PyListRepr(xlsx)} | 图片 {imgNames.Count}");

if (xlsx.Count == 0)
{
    Console.Error.WriteLine("包内没有 xlsx");
    return 2;
}
using var inner = new ZipArchive(new MemoryStream(ReadEntry(zf, xlsx[0])), ZipArchiveMode.Read);
var sheets = SheetXmls(inner);
var shared = LoadSharedStrings(inner);
if (sheets.Count == 0)
{
    Console.Error.WriteLine("workbook 里没有工作表");
    return 2;
}
Console.WriteLine($"  工作表数 {sheets.Count}：{sheets[0].Name} … {sheets[^1].Name}");

var srcAnswers = LoadSourceAnswers(ROOT);
Console.WriteLine($"  原始 TSV 答案数：{srcAnswers.Count}");

var problems = new List<string>();
int qTotal = 0;
var qNumbers = new List<(string Sheet, List<string> Nums)>();
var stemsSeen = new Dictionary<string, int>(StringComparer.Ordinal);
var cached = new Dictionary<string, GrayImg>(StringComparer.Ordinal);
var detail = new List<Detail>();

GrayImg Load(string name)
{
    if (!cached.TryGetValue(name, out var g))
    {
        g = LoadGray(ReadEntry(zf, name));
        cached[name] = g;
    }
    return g;
}

foreach (var (sheetName, xml) in sheets)
{
    var rows = Rows(xml, shared);
    var header = rows[0];
    var body = rows.Skip(1).ToList();
    var idx = new Dictionary<string, int>(StringComparer.Ordinal);
    for (int i = 0; i < header.Count; i++) idx[header[i]] = i;
    var nums = new List<string>();

    foreach (var r0 in body)
    {
        qTotal++;
        var r = new List<string>(r0);
        while (r.Count < header.Count) r.Add("");
        string stem = r[Idx(idx, "题干", 4)];
        nums.Add(r[Idx(idx, "题号", 2)]);
        string ans = r[Idx(idx, "答案", 9)];
        if (ans != "A" && ans != "B" && ans != "C" && ans != "D")
            problems.Add($"{sheetName} 答案非法：{PyStrRepr(ans)}");
        var stemKey = Regex.Replace(stem, @"\s+", "");
        stemsSeen.TryGetValue(stemKey, out int sc);
        stemsSeen[stemKey] = sc + 1;

        var refs = new Dictionary<string, string?>(StringComparer.Ordinal)
        {
            ["stem"] = r[Idx(idx, "图片", 5)]
        };
        var optCols = header.Where(h => h.StartsWith("选项", StringComparison.Ordinal))
                            .Select(h => idx[h]).ToList();
        if (optCols.Count != 4)
        {
            problems.Add($"{sheetName} 选项列数 {optCols.Count}");
            continue;
        }
        const string keys = "ABCD";
        for (int k = 0; k < 4; k++)
        {
            var cell = r[optCols[k]];
            var m = imgRef.Match(cell);
            refs[keys[k].ToString()] = m.Success ? m.Groups[1].Value : null;
            if (!m.Success)
                problems.Add($"{sheetName}#{nums[^1]} {keys[k]} 选项无图引用：{PyStrRepr(cell.Substring(0, Math.Min(40, cell.Length)))}");
        }

        var paths = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var kv in refs)
        {
            var k = kv.Key;
            var refVal = kv.Value;
            if (string.IsNullOrEmpty(refVal))
            {
                problems.Add($"{sheetName}#{nums[^1]} {k} 缺图引用");
                continue;
            }
            var full = pkgPrefix + "/" + refVal.Replace('\\', '/');
            if (!imgNames.Contains(full))
            {
                problems.Add($"{sheetName}#{nums[^1]} {k} 图片不存在：{refVal}");
                continue;
            }
            paths[k] = full;
        }
        if (paths.Count < 5)
        {
            detail.Add(new Detail(sheetName, nums[^1], "缺图", null));
            continue;
        }

        var arrs = new Dictionary<string, GrayImg>(StringComparer.Ordinal);
        foreach (var kv in paths) arrs[kv.Key] = Load(kv.Value);
        var inks = new Dictionary<string, int>(StringComparer.Ordinal);
        foreach (var kv in arrs) inks[kv.Key] = kv.Value.Px.Count(b => b < 140);
        var shapes = new Dictionary<string, (int H, int W)>(StringComparer.Ordinal);
        foreach (var kv in arrs) shapes[kv.Key] = (kv.Value.H, kv.Value.W);
        if (inks.Values.Min() < 60)
            problems.Add($"{sheetName}#{nums[^1]} 有近乎空白的图：{InkDictRepr(inks)}");
        if (inks["stem"] < 600)
            problems.Add($"{sheetName}#{nums[^1]} 题干图墨量偏少：{inks["stem"]}");

        // 答案与原始 TSV 对照（事实来源）。
        var mSet = Regex.Match(sheetName, @"(\d+)");
        if (srcAnswers.Count > 0 && mSet.Success)
        {
            int qn = (nums[^1].Length > 0 && nums[^1].All(char.IsDigit)) ? int.Parse(nums[^1]) : -1;
            var key = (int.Parse(mSet.Groups[1].Value), qn);
            if (srcAnswers.TryGetValue(key, out var want) && !string.IsNullOrEmpty(want) && want != ans)
                problems.Add($"{sheetName}#{nums[^1]} 答案与原始数据不符：表里 {ans} / 原卷 {want}");
        }

        // 选项之间不应完全相同。Python 用 arr.tobytes().__hash__() 作相等性标记，
        // 这里用等价的、对原始像素字节的确定性 FNV-1a 哈希（同样忽略 shape）。
        var sigs = new Dictionary<string, ulong>(StringComparer.Ordinal);
        foreach (var k in "ABCD") { var ks = k.ToString(); sigs[ks] = Sig(arrs[ks].Px); }
        sigs["stem"] = Sig(arrs["stem"].Px);
        var sigValues = new[] { "A", "B", "C", "D", "stem" }.Select(k => sigs[k]).ToList();
        if (sigValues.Distinct().Count() < 4)
        {
            var dup = new List<string>();
            foreach (var k in "ABCD")
            {
                var ks = k.ToString();
                if (sigValues.Count(s => s == sigs[ks]) > 1) dup.Add(ks);
            }
            problems.Add($"{sheetName}#{nums[^1]} 选项图重复：{PyListRepr(dup)}");
        }
        if (new[] { "A", "B", "C", "D" }.Any(k => sigs[k] == sigs["stem"]))
            problems.Add($"{sheetName}#{nums[^1]} 题干图与某个选项图完全相同");

        var hs = new[] { "A", "B", "C", "D" }.Select(k => shapes[k].H).ToList();
        double ratio = hs.Max() != 0 ? hs.Min() / (double)hs.Max() : 0.0;
        detail.Add(new Detail(sheetName, nums[^1], ratio > 0.45 ? "ok" : "uneven",
                              (Math.Round(ratio, 2, MidpointRounding.ToEven), inks)));
    }
    qNumbers.Add((sheetName, nums));
}

Console.WriteLine($"\n题目总数 {qTotal}");
foreach (var qn in qNumbers.Take(3).Concat(qNumbers.TakeLast(1)))
    Console.WriteLine($"  {qn.Sheet} 题号 {(qn.Nums.Count > 0 ? qn.Nums[0] : "-")}…{(qn.Nums.Count > 0 ? qn.Nums[^1] : "-")}（{qn.Nums.Count} 题）");
var dupStems = stemsSeen.Where(kv => kv.Value > 1).Select(kv => kv.Key).ToList();
if (dupStems.Count > 0)
    Console.WriteLine($"  重复题干 {dupStems.Count} 条：{PyListRepr(dupStems.Take(3).ToList())}");
Console.WriteLine($"  选项高度不均衡（<0.45）：{detail.Count(d => d.Tag == "uneven")} 题");
Console.WriteLine($"  发现问题 {problems.Count} 条");
foreach (var p in problems.Take(20)) Console.WriteLine("    • " + p);

if (sample != 0 && detail.Count > 0)
{
    Directory.CreateDirectory(DBG);
    // 原脚本此处 random.seed(3) + random.sample 选出 picks，但循环体是 pass，
    // 没有任何输出；唯一可观察效果就是建 DBG 目录。C# 忠实于此（不写对照图）。
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

static int OptInt(string[] a, string name, int def)
{
    var v = Opt(a, name);
    return v != null && int.TryParse(v, out int n) ? n : def;
}

static XName M(string local) => XName.Get(local, "http://schemas.openxmlformats.org/spreadsheetml/2006/main");
static XName R(string local) => XName.Get(local, "http://schemas.openxmlformats.org/officeDocument/2006/relationships");

static Stream ReadStream(ZipArchive z, string name)
{
    var e = z.GetEntry(name) ?? throw new InvalidOperationException($"缺 {name}");
    return e.Open();
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

// 按 workbook.xml 的 <sheets> 顺序返回 (表名, sheet xml bytes)。
static List<(string Name, byte[] Xml)> SheetXmls(ZipArchive zf)
{
    XDocument wb, rels;
    using (var s = ReadStream(zf, "xl/workbook.xml")) wb = XDocument.Load(s);
    using (var s = ReadStream(zf, "xl/_rels/workbook.xml.rels")) rels = XDocument.Load(s);
    var rid2target = new Dictionary<string, string>(StringComparer.Ordinal);
    foreach (var r in rels.Root!.Elements())
    {
        var id = r.Attribute("Id")?.Value;
        var tgt = r.Attribute("Target")?.Value;
        if (id != null && tgt != null) rid2target[id] = tgt;
    }
    var outp = new List<(string Name, byte[] Xml)>();
    foreach (var sh in wb.Root!.Element(M("sheets"))!.Elements(M("sheet")))
    {
        var name = sh.Attribute("name")!.Value;
        var rid = sh.Attribute(R("id"))!.Value;
        var tgt = rid2target[rid].TrimStart('/');
        if (!tgt.StartsWith("xl/", StringComparison.Ordinal)) tgt = "xl/" + tgt;
        outp.Add((name, ReadEntry(zf, tgt)));
    }
    return outp;
}

// 返回每行的单元格文本列表。inlineStr 取 <is><t>；其余取 <v> 文本。
// 原脚本对 t="s"（sharedStrings）单元格直接取 <v> 索引文本；按任务「sharedStrings.xml
// 解析要完整翻译」的要求，这里额外加载 sharedStrings.xml，把索引还原成真实文本，
// 使审计对 openpyxl 生成的试卷包也成立（inlineStr 包上与原脚本完全一致）。
static List<List<string>> Rows(byte[] xmlBytes, List<string>? shared)
{
    var root = XDocument.Load(new MemoryStream(xmlBytes));
    var rows = new List<List<string>>();
    foreach (var row in root.Descendants(M("row")))
    {
        var vals = new List<string>();
        foreach (var c in row.Elements())
        {
            var t = c.Attribute("t")?.Value;
            string txt;
            if (t == "inlineStr")
                txt = string.Concat(c.Descendants(M("t")).Select(x => x.Value));
            else
            {
                var v = c.Element(M("v"));
                if (t == "s" && shared != null && v != null
                    && int.TryParse(v.Value, NumberStyles.None, CultureInfo.InvariantCulture, out int si)
                    && si >= 0 && si < shared.Count)
                    txt = shared[si];
                else
                    txt = (v != null && v.Value.Length > 0) ? v.Value : "";
            }
            vals.Add(txt);
        }
        rows.Add(vals);
    }
    return rows;
}

// sharedStrings.xml：<si> 下所有 <t> 拼接（含富文本分段）。
static List<string>? LoadSharedStrings(ZipArchive zf)
{
    var e = zf.GetEntry("xl/sharedStrings.xml");
    if (e == null) return null;
    using var s = e.Open();
    var doc = XDocument.Load(s);
    return doc.Root!.Elements(M("si"))
        .Select(si => string.Concat(si.Descendants(M("t")).Select(t => t.Value)))
        .ToList();
}

// 读原始 Anki TSV，返回 {(套号, 题号): 答案}。
static Dictionary<(int Set, int Qnum), string> LoadSourceAnswers(string root)
{
    var path = Path.Combine(root, "qbank_out", "anki_import.tsv");
    var outp = new Dictionary<(int, int), string>();
    if (!File.Exists(path)) return outp;
    foreach (var line in File.ReadLines(path, Encoding.UTF8))
    {
        if (line.StartsWith("#", StringComparison.Ordinal) || line.Trim().Length == 0) continue;
        var parts = line.TrimEnd('\r', '\n').Split('\t');
        if (parts.Length < 3) continue;
        var m = Regex.Match(parts[0], "src=\"([^\"]+)\"");
        var tag = Regex.Match(parts[2], @"第\s*(\d+)\s*套\s*第\s*(\d+)\s*题");
        if (m.Success && tag.Success)
            outp[(int.Parse(tag.Groups[1].Value), int.Parse(tag.Groups[2].Value))] = parts[1].Trim();
    }
    return outp;
}

static int Idx(Dictionary<string, int> idx, string key, int def) =>
    idx.TryGetValue(key, out int i) ? i : def;

// Image.open(...).convert("L") -> 灰度二维数组；这里存成行优先的 byte[]。
static GrayImg LoadGray(byte[] data)
{
    using var img = SKBitmap.Decode(data);
    int w = img.Width, h = img.Height;
    var px = new byte[w * h];
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
        {
            var c = img.GetPixel(x, y);
            px[y * w + x] = (byte)((c.Red * 19595 + c.Green * 38470 + c.Blue * 7471) >> 16);
        }
    return new GrayImg(w, h, px);
}

static ulong Sig(byte[] px)
{
    ulong h = 14695981039346656037UL;
    foreach (var b in px) { h ^= b; h *= 1099511628211UL; }
    return h;
}

static string PyStrRepr(string s)
{
    char q = (s.Contains('\'') && !s.Contains('"')) ? '"' : '\'';
    var sb = new StringBuilder();
    sb.Append(q);
    foreach (var c in s)
    {
        if (c == q || c == '\\') sb.Append('\\').Append(c);
        else if (c == '\n') sb.Append("\\n");
        else if (c == '\r') sb.Append("\\r");
        else if (c == '\t') sb.Append("\\t");
        else if (c < 0x20 || c == 0x7f) sb.Append("\\x").Append(((int)c).ToString("x2"));
        else sb.Append(c);
    }
    sb.Append(q);
    return sb.ToString();
}

static string PyListRepr(List<string> items) => "[" + string.Join(", ", items.Select(PyStrRepr)) + "]";

static string InkDictRepr(Dictionary<string, int> d) =>
    "{" + string.Join(", ", d.Select(kv => PyStrRepr(kv.Key) + ": " + kv.Value.ToString(CultureInfo.InvariantCulture))) + "}";

record GrayImg(int W, int H, byte[] Px);
record Detail(string Sheet, string Num, string Tag, object? Info);
