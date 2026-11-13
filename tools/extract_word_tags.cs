#!/usr/bin/env dotnet
// 从两册《高频成语+高频实词800词》docx 抽「分组标签 + 释义」，给内置词库补「细分」。
//
// 源文档结构（两册一致）：
//   H1 高频成语篇 / 高频实词篇
//     4 列分类表：分类（【第N组】组名）| 细分 | 个数 | 词（顿号分隔）  ← 标签主来源
//     H2 成语解释 / 实词解释 下的 2 列小表：细分 | 词               ← 标签补充
//     H2 成语解释 / 实词解释 → H3 ✎词条 + 释义段 + 【例】段          ← 释义来源
//     H2 真题示例｜表格里是题目，**不参与**标签
//
// 输出：
//   · tools/word_tags.json —— {词条: ["组名·细分", ...]}
//   · --write-csv 直接把标签写进词的「细分」列，并把词库里没有的词补成新行
//
// 用法：
//   dotnet run tools/extract_word_tags.cs -- --upper "…上册.docx" --lower "…下册.docx" --write-csv
//   dotnet run tools/extract_word_tags.cs -- --upper "…上册.docx" --lower "…下册.docx" \
//       --csv app/src/main/assets/builtin/words.csv --out tools/word_tags.json

#:property PublishAot=false

using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;
using System.Xml.Linq;

Console.OutputEncoding = System.Text.Encoding.UTF8;

var upper = Opt(args, "--upper");
var lower = Opt(args, "--lower");
var csvPath = Opt(args, "--csv", "app/src/main/assets/builtin/words.csv")!;
var outPath = Opt(args, "--out", "tools/word_tags.json")!;
var writeCsv = Flag(args, "--write-csv");

if (upper == null || lower == null)
{
    Console.Error.WriteLine("用法: dotnet run tools/extract_word_tags.cs -- --upper <上册.docx> --lower <下册.docx> [--csv ...] [--out ...] [--write-csv]");
    return 2;
}
if (!File.Exists(upper) || !File.Exists(lower))
{
    Console.Error.WriteLine($"找不到 docx：{upper} / {lower}");
    return 2;
}

var tags = new Dictionary<string, List<string>>(StringComparer.Ordinal);
var entries = new Dictionary<string, Entry>(StringComparer.Ordinal);
var stats = new Stats();
ParseDocx(upper, tags, entries, stats);
ParseDocx(lower, tags, entries, stats);

// 同一批词既在分类表（组·细分）又在解释小表（只有细分）出现时，
// 保留「组·细分」这条，去掉会重复的那条裸细分。
foreach (var w in tags.Keys.ToList())
{
    var lst = tags[w];
    var tails = new HashSet<string>(StringComparer.Ordinal);
    foreach (var l in lst)
        if (l.Contains('·')) tails.Add(l[(l.IndexOf('·') + 1)..]);
    tags[w] = lst.Where(l => l.Contains('·') || !tails.Contains(l)).ToList();
}

File.WriteAllText(outPath, BuildTagsJson(tags), new UTF8Encoding(false));

Console.WriteLine($"文档标签：{tags.Count} 个词（分类表 {stats.Tagged} 条 / 小表补充 {stats.Supp} 条）");
Console.WriteLine($"文档释义：{entries.Values.Count(e => !string.IsNullOrEmpty(e.Meaning))} 个词有条目释义");

if (!File.Exists(csvPath)) return 0;

var allRows = CsvReadAll(File.ReadAllText(csvPath, Encoding.UTF8));
var fields = allRows[0];
var rows = new List<Dictionary<string, string>>();
foreach (var r in allRows.Skip(1))
{
    var d = new Dictionary<string, string>(StringComparer.Ordinal);
    for (int i = 0; i < fields.Length; i++) d[fields[i]] = i < r.Length ? r[i] : "";
    rows.Add(d);
}

int hit = 0, changed = 0;
foreach (var r in rows)
{
    if (!tags.TryGetValue(r.GetValueOrDefault("词条", ""), out var t) || t.Count == 0) continue;
    hit++;
    var newTag = string.Join("·", t);
    if (r.GetValueOrDefault("细分", "").Trim() != newTag)
    {
        r["细分"] = newTag;
        changed++;
    }
}

var have = new HashSet<string>(rows.Select(r => r.GetValueOrDefault("词条", "")), StringComparer.Ordinal);
var missing = tags.Keys.Where(w => !have.Contains(w)).ToList();
var added = new List<Dictionary<string, string>>();
foreach (var w in missing.OrderBy(x => x, StringComparer.Ordinal))
{
    entries.TryGetValue(w, out var e);
    var cat = (e != null && !string.IsNullOrEmpty(e.Category)) ? e.Category : (w.Length == 4 ? "成语" : "实词");
    added.Add(new Dictionary<string, string>(StringComparer.Ordinal)
    {
        ["词条"] = w,
        ["拼音"] = "",
        ["分类"] = cat,
        ["细分"] = string.Join("·", tags[w]),
        ["释义"] = e?.Meaning ?? "",
        ["出处"] = "内置·四海 800 词",
    });
}

Console.WriteLine($"词库 {rows.Count} 条；命中标签 {hit} 条（改了 {changed} 条）；文档里有、词库没有 {missing.Count} 个");

if (writeCsv)
{
    var sb = new StringBuilder();
    sb.Append(CsvLine(fields));
    foreach (var r in rows) sb.Append(CsvLine(fields.Select(f => r.GetValueOrDefault(f, "")).ToArray()));
    foreach (var r in added) sb.Append(CsvLine(fields.Select(f => r.GetValueOrDefault(f, "")).ToArray()));
    File.WriteAllText(csvPath, sb.ToString(), new UTF8Encoding(false));
    Console.WriteLine($"已写回 {csvPath}：{rows.Count + added.Count} 条（新增 {added.Count} 条）");
}
return 0;

// ---------------- helpers ----------------

static string? Opt(string[] a, string name, string? def = null)
{
    for (int i = 0; i < a.Length; i++)
    {
        if (a[i] == name && i + 1 < a.Length) return a[i + 1];
        if (a[i].StartsWith(name + "=", StringComparison.Ordinal)) return a[i][(name.Length + 1)..];
    }
    return def;
}

static bool Flag(string[] a, string name) => Array.IndexOf(a, name) >= 0;

// python-docx 的 Document(path) 读 word/document.xml；walk() 只遍历 <w:body> 的直接子结点，
// 逐段/逐表处理。w:pStyle 的 styleId（Heading1 / 标题1 / 1 …）在这里直接判成标题级别，
// 等价于 style.name == "Heading 1/2/3"。
static void ParseDocx(string path, Dictionary<string, List<string>> tags,
                      Dictionary<string, Entry> entries, Stats stats)
{
    using var za = ZipFile.OpenRead(path);
    var docEntry = za.GetEntry("word/document.xml");
    if (docEntry == null) return;
    XDocument doc;
    using (var s = docEntry.Open()) doc = XDocument.Load(s);
    var body = doc.Root?.Element(Wn("body"));
    if (body == null) return;

    string section = "", subHead = "", curWord = "";
    var buf = new List<string>();

    void Flush()
    {
        if (curWord.Length == 0) return;
        string meaning = "";
        foreach (var line in buf)
        {
            if (line.StartsWith("【例】", StringComparison.Ordinal)) continue;
            if (line.Length > 0) { meaning = line; break; }
        }
        var cat = section.Contains("成语") ? "成语" : (section.Contains("实词") ? "实词" : "");
        entries.TryGetValue(curWord, out var old);
        if (old == null || (string.IsNullOrEmpty(old.Meaning) && meaning.Length > 0))
        {
            entries[curWord] = new Entry
            {
                Category = cat.Length > 0 ? cat : (old?.Category ?? ""),
                Meaning = meaning.Length > 0 ? meaning : (old?.Meaning ?? "")
            };
        }
    }

    foreach (var child in body.Elements())
    {
        if (child.Name == Wn("p"))
        {
            int level = HeadingLevel(PStyle(child));
            var text = ParaText(child).Replace('\u00a0', ' ').Trim();
            if (level == 1) { Flush(); curWord = ""; buf = new List<string>(); section = text; }
            else if (level == 2) { Flush(); curWord = ""; buf = new List<string>(); subHead = text; }
            else if (level == 3) { Flush(); buf = new List<string>(); curWord = text.TrimStart('\u270E').Trim(); }
            else if (curWord.Length > 0 && text.Length > 0) buf.Add(text);
            continue;
        }
        if (child.Name == Wn("tbl"))
        {
            var rows = child.Elements(Wn("tr")).ToList();
            if (rows.Count == 0) continue;
            int cols = ColumnCount(child);
            if (cols >= 4)
            {
                string group = "";
                foreach (var tr in rows)
                {
                    var cells = tr.Elements(Wn("tc"))
                        .Select(tc => CellText(tc).Replace("\n", " ").Replace('\u00a0', ' ').Trim())
                        .ToList();
                    if (cells.Count < 4) continue;
                    if (!Regex.IsMatch(cells[2], @"\A\d{1,3}\z")) continue;
                    var g = CleanGroup(cells[0]);
                    if (g.Length > 0) group = g;
                    var sub = CleanSub(cells[1]);
                    var label = (group.Length > 0 && group == sub)
                        ? group
                        : string.Join("·", new[] { group, sub }.Where(x => x.Length > 0));
                    foreach (var w in SplitWords(cells[3]))
                        if (AddTag(tags, w, label)) stats.Tagged++;
                }
            }
            else if (cols == 2 && subHead.Contains("解释"))
            {
                foreach (var tr in rows)
                {
                    var cells = tr.Elements(Wn("tc"))
                        .Select(tc => CellText(tc).Replace("\n", " ").Replace('\u00a0', ' ').Trim())
                        .ToList();
                    if (cells.Count != 2) continue;
                    var sub = CleanSub(cells[0]);
                    foreach (var w in SplitWords(cells[1]))
                        if (AddTag(tags, w, sub)) stats.Supp++;
                }
            }
        }
    }
    Flush();
}

static XName Wn(string local) => XName.Get(local, "http://schemas.openxmlformats.org/wordprocessingml/2006/main");

static string? PStyle(XElement p) =>
    p.Element(Wn("pPr"))?.Element(Wn("pStyle"))?.Attribute(Wn("val"))?.Value;

static int HeadingLevel(string? styleId)
{
    if (string.IsNullOrEmpty(styleId)) return 0;
    var s = styleId.Replace(" ", "").ToLowerInvariant();
    bool isHeading = s.Contains("heading") || s.Contains("标题") || Regex.IsMatch(s, @"\A\d+\z");
    if (!isHeading) return 0;
    var m = Regex.Match(s, @"(\d+)\z");
    if (m.Success && int.TryParse(m.Groups[1].Value, out int n) && n >= 1 && n <= 3) return n;
    return 0;
}

// Paragraph.text / cell.text：按文档顺序拼 w:t，w:tab -> \t，w:br/w:cr -> \n。
static string ParaText(XElement p)
{
    var sb = new StringBuilder();
    foreach (var e in p.Descendants())
    {
        if (e.Name == Wn("t")) sb.Append(e.Value);
        else if (e.Name == Wn("tab")) sb.Append('\t');
        else if (e.Name == Wn("br") || e.Name == Wn("cr")) sb.Append('\n');
    }
    return sb.ToString();
}

static string CellText(XElement tc) => string.Join("\n", tc.Elements(Wn("p")).Select(ParaText));

static int ColumnCount(XElement tbl)
{
    var grid = tbl.Element(Wn("tblGrid"));
    int n = grid?.Elements(Wn("gridCol")).Count() ?? 0;
    if (n == 0)
    {
        var firstRow = tbl.Elements(Wn("tr")).FirstOrDefault();
        if (firstRow != null) n = firstRow.Elements(Wn("tc")).Count();
    }
    return n;
}

static string CleanGroup(string raw)
{
    var s = Regex.Replace(raw, @"^【\s*第?\s*[0-9零〇一二三四五六七八九十百千两]{1,4}\s*组\s*】\s*", "");
    s = Regex.Replace(s, @"\s+", "");
    return s.Trim(' ', '\u3000', '\u00B7', ':', '\uFF1A');
}

static string CleanSub(string raw)
{
    var s = Regex.Replace(raw, @"\s+", "");
    return s.Trim(' ', '\u3000', '\u00B7', ':', '\uFF1A');
}

static List<string> SplitWords(string raw)
{
    var outp = new List<string>();
    foreach (var part in Regex.Split(raw, "[、，,]"))
    {
        var w = part.Trim().Trim('\u201C', '\u201D', '"', '\'');
        if (w.Length == 0 || w == "个数" || w == "成语" || w == "词") continue;
        if (!Regex.IsMatch(w, "[\u4e00-\u9fff]") || w.Length > 12) continue;
        outp.Add(w);
    }
    return outp;
}

static bool AddTag(Dictionary<string, List<string>> tags, string word, string label)
{
    if (word.Length == 0 || label.Length == 0) return false;
    if (!tags.TryGetValue(word, out var lst)) { lst = new List<string>(); tags[word] = lst; }
    if (lst.Contains(label)) return false;
    lst.Add(label);
    return true;
}

// json.dump(..., ensure_ascii=False, indent=1)：键按码点排序，逐层 1 空格缩进，无尾随换行。
static string BuildTagsJson(Dictionary<string, List<string>> tags)
{
    var sb = new StringBuilder();
    sb.Append("{\n");
    var keys = tags.Keys.OrderBy(k => k, StringComparer.Ordinal).ToList();
    for (int i = 0; i < keys.Count; i++)
    {
        sb.Append(' ').Append(JsonStr(keys[i])).Append(": [\n");
        var lst = tags[keys[i]];
        for (int j = 0; j < lst.Count; j++)
        {
            sb.Append("  ").Append(JsonStr(lst[j]));
            if (j < lst.Count - 1) sb.Append(',');
            sb.Append('\n');
        }
        sb.Append(" ]");
        if (i < keys.Count - 1) sb.Append(',');
        sb.Append('\n');
    }
    sb.Append('}');
    return sb.ToString();
}

static string JsonStr(string s)
{
    var sb = new StringBuilder();
    sb.Append('"');
    foreach (var c in s)
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

// csv 模块默认 dialect（QUOTE_MINIMAL，lineterminator="\n"）的读/写。
static List<string[]> CsvReadAll(string text)
{
    var rows = new List<string[]>();
    var field = new StringBuilder();
    var row = new List<string>();
    bool inQuotes = false;
    int i = 0;
    while (i < text.Length)
    {
        char c = text[i];
        if (inQuotes)
        {
            if (c == '"')
            {
                if (i + 1 < text.Length && text[i + 1] == '"') { field.Append('"'); i += 2; continue; }
                inQuotes = false; i++; continue;
            }
            field.Append(c); i++; continue;
        }
        if (c == '"') { inQuotes = true; i++; continue; }
        if (c == ',') { row.Add(field.ToString()); field.Clear(); i++; continue; }
        if (c == '\r' || c == '\n')
        {
            if (row.Count == 0 && field.Length == 0) { if (c == '\r' && i + 1 < text.Length && text[i + 1] == '\n') i += 2; else i++; continue; }
            row.Add(field.ToString()); field.Clear();
            rows.Add(row.ToArray()); row.Clear();
            if (c == '\r' && i + 1 < text.Length && text[i + 1] == '\n') i += 2; else i++;
            continue;
        }
        field.Append(c); i++;
    }
    if (field.Length > 0 || row.Count > 0) { row.Add(field.ToString()); rows.Add(row.ToArray()); }
    return rows;
}

static string CsvQuote(string s)
{
    if (s.IndexOfAny(new[] { ',', '"', '\r', '\n' }) < 0) return s;
    return "\"" + s.Replace("\"", "\"\"") + "\"";
}

static string CsvLine(string[] cells) => string.Join(",", cells.Select(CsvQuote)) + "\n";

sealed class Entry { public string Category = ""; public string Meaning = ""; }
sealed class Stats { public int Tagged; public int Supp; }
