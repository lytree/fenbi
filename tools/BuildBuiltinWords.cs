#!/usr/bin/env dotnet
// 把《成语与实词汇总（合并版）》docx 转成 App 内置词库 CSV。
//
// 用法：
//   dotnet run tools/BuildBuiltinWords.cs -- <源文件.docx> <输出.csv>
//   dotnet run tools/BuildBuiltinWords.cs -- "C:/Users/me/Downloads/成语与实词汇总（合并版）.docx" app/src/main/assets/builtin/words.csv
//
// 源文档结构（必须一致，否则脚本会报错而不是瞎猜）：
//   子表一 · 成语汇总
//       H1 成语汇总
//           H3 A/B/C…（首字母分节）
//               条目段落：「词条：释义〔分类标签〕」
//       H2 成语归类表
//           表格：成语分类 | 子类 | 个数 | 成语（顿号分隔）
//   子表二 · 实词汇总
//       一、汇总概览      表格：来源 | 结构 | 条数（跳过）
//       二、高频实词辨析  表格：组 | 词语 | 释义 | 下册分组
//       三、高频三字词    表格：词语 | 释义 | 下册分组
//       四、400 组        表格：拼音 | 对比词组 | 释义 | 下册分组
//       五、下册独有      表格：词语 | 下册分组
//
// 输出列与 App 的 WordImporter 表头别名对齐：词条,拼音,分类,细分,释义,出处
// 拼音留空——App 首次启动导入时会用内置拼音表逐字算，避免这里算错多音字。
//
// 零依赖：docx 就是个 zip，用 System.IO.Compression 取 word/document.xml，
// 再用 System.Xml.Linq 按 OOXML 的 w: 命名空间取节点，不需要 Word/OpenXML SDK。

using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;
using System.Xml.Linq;

Console.OutputEncoding = Encoding.UTF8;

if (args.Length < 2)
{
    Console.Error.WriteLine("用法: dotnet run tools/BuildBuiltinWords.cs -- <源文件.docx> <输出.csv>");
    return 2;
}

var src = args[0];
var dst = args[1];
var body = Docx.LoadBody(src);

var entries = new List<Entry>();
var curH1 = "";
var idiomSection = false;
var idiomCount = 0;
var tables = new List<(string Ctx, List<List<string>> Rows)>();

foreach (var child in body.Elements())
{
    if (child.Name == W.Ns + "p")
    {
        var text = Docx.ParaText(child);
        var level = Docx.HeadingLevel(child);
        if (level > 0)
        {
            curH1 = text;
            if (text == "成语汇总") idiomSection = true;
            else if (text is "成语归类表" or "实词汇总（以 800 词为准 · 下册分组）") idiomSection = false;
            continue;
        }
        if (!idiomSection || text.Length == 0) continue;
        if (!text.Contains('：') && !text.Contains(':')) continue;
        var sep = text.Contains('：') ? '：' : ':';
        var at = text.IndexOf(sep);
        var word = Words.Clean(text[..at]);
        if (!Words.Accept(word)) continue;
        var (meaning, rawTag) = Words.SplitTag(text[(at + 1)..]);
        entries.Add(new Entry(word, "成语", Words.CleanTag(rawTag), meaning, "内置·成语汇总"));
        idiomCount++;
    }
    else if (child.Name == W.Ns + "tbl")
    {
        tables.Add((curH1, Docx.TableRows(child)));
    }
}

// ---- 成语归类表：给成语补细分（文档里没有〔标签〕的） ----
var idiomTag = new Dictionary<string, string>(StringComparer.Ordinal);
foreach (var (_, rows) in tables)
{
    if (!Shell.HeadIs(rows, 4, "成语分类", "子类", "个数", "成语")) continue;
    var group = "";
    foreach (var r in rows.Skip(1))
    {
        if (r.Count < 4) continue;
        var rawGroup = r[0].Trim();
        if (rawGroup.Length > 0)
        {
            var stripped = Regex.Replace(rawGroup, "^【[^】]*】", "").Trim();
            group = stripped.Length > 0 ? stripped : rawGroup;
        }
        var sub = r[1].Trim();
        foreach (var piece in Regex.Split(r[3], "[、,，/]"))
        {
            var w = Words.Clean(piece);
            if (!Words.Accept(w)) continue;
            if (!idiomTag.ContainsKey(w))
            {
                var joined = string.Join("·", new[] { Words.CleanTag(group), Words.CleanTag(sub) }
                    .Where(x => x.Length > 0));
                idiomTag[w] = joined;
            }
        }
    }
}

foreach (var e in entries)
{
    if (e.Tag.Length == 0 && idiomTag.TryGetValue(e.Text, out var t)) e.Tag = t;
}

// ---- 实词四张表 ----
var realCount = 0;
foreach (var (_, rows) in tables)
{
    if (rows.Count == 0) continue;
    var bodyRows = rows.Skip(1).Where(r => r.Any(x => x.Trim().Length > 0)).ToList();

    if (Shell.HeadIs(rows, 4, "组", "词语", "释义", "下册分组"))
    {
        foreach (var r in bodyRows)
        {
            if (r.Count < 3) continue;
            var w = Words.Clean(r[1]);
            if (!Words.Accept(w)) continue;
            entries.Add(new Entry(w, "实词", Words.CleanTag(r.Count > 3 ? r[3] : ""), r[2].Trim(), "内置·实词辨析"));
            realCount++;
        }
    }
    else if (Shell.HeadIs(rows, 3, "词语", "释义", "下册分组"))
    {
        foreach (var r in bodyRows)
        {
            var w = r.Count > 0 ? Words.Clean(r[0]) : "";
            if (!Words.Accept(w)) continue;
            entries.Add(new Entry(w, "实词", "三字词", r.Count > 1 ? r[1].Trim() : "", "内置·高频三字词"));
            realCount++;
        }
    }
    else if (Shell.HeadIs(rows, 4, "拼音", "对比词组", "释义", "下册分组"))
    {
        foreach (var r in bodyRows)
        {
            if (r.Count < 3) continue;
            var pair = Regex.Split(r[1], "[/／]")
                .Select(Words.Clean)
                .Where(Words.Accept)
                .ToList();
            if (pair.Count == 0) continue;
            var gl = r[2].Trim();
            var group = Words.CleanTag(r.Count > 3 ? r[3] : "");
            foreach (var w in pair)
            {
                var others = string.Join("、", pair.Where(x => x != w));
                var meaning = others.Length > 0 ? $"与「{others}」辨析：{gl}" : gl;
                var tag = group.Length > 0 ? "对比辨析·" + group : "对比辨析";
                entries.Add(new Entry(w, "实词", tag, meaning, "内置·实词对比"));
                realCount++;
            }
        }
    }
    else if (Shell.HeadIs(rows, 2, "词语", "下册分组"))
    {
        foreach (var r in bodyRows)
        {
            var w = r.Count > 0 ? Words.Clean(r[0]) : "";
            if (!Words.Accept(w)) continue;
            entries.Add(new Entry(w, "实词", Words.CleanTag(r.Count > 1 ? r[1] : ""), "", "内置·下册独有"));
            realCount++;
        }
    }
}

// ---- 去重：同词条合并释义 / 标签，成语优先 ----
var merged = new List<Entry>();
var index = new Dictionary<string, int>(StringComparer.Ordinal);
foreach (var e in entries)
{
    if (!index.TryGetValue(e.Text, out var i))
    {
        index[e.Text] = merged.Count;
        merged.Add(e);
        continue;
    }
    var old = merged[i];
    if (e.Category == "成语" && old.Category != "成语") old.Category = "成语";
    if (e.Meaning.Length > old.Meaning.Length) old.Meaning = e.Meaning;
    if (e.Tag.Length > 0) old.Tag = Words.MergeTag(old.Tag, e.Tag);
}

foreach (var e in merged) e.Tag = Words.MergeTag(e.Tag);

// 行尾写死 \n：仓库 .gitattributes 是 eol=lf，本地留 \r\n 会让「我编的包」和「CI 编的包」内容不一致
var sb = new StringBuilder();
Csv.AppendRow(sb, "词条", "拼音", "分类", "细分", "释义", "出处");
foreach (var e in merged) Csv.AppendRow(sb, e.Text, "", e.Category, e.Tag, e.Meaning, e.Source);
File.WriteAllText(dst, sb.ToString(), new UTF8Encoding(false));

var idioms = merged.Count(e => e.Category == "成语");
Console.WriteLine($"成语段落 {idiomCount} 条，实词行 {realCount} 条，去重后合计 {merged.Count} 条（成语 {idioms} / 实词 {merged.Count - idioms}）");
Console.WriteLine($"已写入 {dst}");
return 0;

// ---------------- 以下是被顶层语句用到的类型 ----------------

internal static class W
{
    public static readonly XNamespace Ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
}

internal static class Docx
{
    public static XElement LoadBody(string path)
    {
        // 手动开 FileStream 而不是 ZipFile.OpenRead：源文档经常还开在 Word 里，
        // OpenRead 默认 FileShare.Read，会被 Word 的写锁挡住；这里放宽到 ReadWrite 只读不写。
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        using var zip = new ZipArchive(stream, ZipArchiveMode.Read);
        var entry = zip.GetEntry("word/document.xml")
                    ?? throw new InvalidOperationException("docx 里没有 word/document.xml");
        using var entryStream = entry.Open();
        var doc = XDocument.Load(entryStream);
        return doc.Root?.Element(W.Ns + "body")
               ?? throw new InvalidOperationException("document.xml 里没有 body");
    }

    /// <summary>段落文字：把段内所有 w:t 拼起来（超链接、域里的文字也算）。</summary>
    public static string ParaText(XElement p) =>
        string.Concat(p.Descendants(W.Ns + "t").Select(t => t.Value)).Trim();

    public static string ParaStyle(XElement p) =>
        p.Element(W.Ns + "pPr")?.Element(W.Ns + "pStyle")?.Attribute(W.Ns + "val")?.Value ?? "";

    /// <summary>返回 1..9，非标题返回 0。中文 Word 的标题样式多为 heading 1 / 1 / 标题 1。</summary>
    public static int HeadingLevel(XElement p)
    {
        var style = ParaStyle(p);
        if (style.Length == 0) return 0;
        var m = Regex.Match(style, @"(\d)$");
        if (!m.Success) return 0;
        var low = style.ToLowerInvariant();
        if (low.Contains("heading") || low.StartsWith("标题", StringComparison.Ordinal) || style is "1" or "2" or "3")
            return m.Groups[1].Value[0] - '0';
        return 0;
    }

    public static List<List<string>> TableRows(XElement tbl) =>
        tbl.Elements(W.Ns + "tr").Select(tr => tr.Elements(W.Ns + "tc")
            .Select(tc => string.Concat(tc.Elements(W.Ns + "p").Select(ParaText)).Trim())
            .ToList()).ToList();
}

internal static class Words
{
    public const int MaxLen = 16;

    private static readonly Regex Cjk = new(@"[\u4e00-\u9fff]", RegexOptions.Compiled);
    private static readonly Regex BadChars = new(@"[\s，。；：、,;:]", RegexOptions.Compiled);
    private static readonly Regex Ordinal = new(@"^[0-9０-９]+\s*[.、．)）]\s*", RegexOptions.Compiled);
    private static readonly Regex Bullet = new(@"^[·•●▪◆\-—*]\s*", RegexOptions.Compiled);
    private static readonly Regex Bracket = new(@"[〔【\[]([^〕】\]]+)[〕】\]]\s*$", RegexOptions.Compiled);
    private static readonly Regex GroupPrefix = new(@"^第\s*[0-9零〇一二三四五六七八九十百千两]{1,4}\s*组[\s:：、,，\-—]*", RegexOptions.Compiled);
    private static readonly Regex TagSplit = new(@"[·；;]", RegexOptions.Compiled);

    private static readonly char[] QuoteChars = "“”\"'‘’《》「」".ToCharArray();

    /// <summary>去掉序号、项目符号、首尾引号；只保留能当词条的内容。</summary>
    public static string Clean(string raw)
    {
        var s = raw.Trim().Trim('　', ' ');
        s = Ordinal.Replace(s, "");
        s = Bullet.Replace(s, "");
        s = s.Trim(QuoteChars);
        return s.Trim();
    }

    public static bool Accept(string s) =>
        s.Length > 0 && s.Length <= MaxLen && Cjk.IsMatch(s) && !BadChars.IsMatch(s);

    /// <summary>「释义〔分类标签〕」→ (释义, 标签)；没有标签时标签为空。</summary>
    public static (string Meaning, string Tag) SplitTag(string text)
    {
        foreach (Match m in Bracket.Matches(text))
        {
            if (m.Index + m.Length == text.Length)
                return (text[..m.Index].Trim(), m.Groups[1].Value.Trim());
        }
        return (text.Trim(), "");
    }

    /// <summary>源表用「—」占位表示没有分组，不要把它当标签。</summary>
    public static string CleanTag(string? t)
    {
        var s = (t ?? "").Trim();
        return s is "—" or "-" or "——" or "/" or "无" ? "" : s;
    }

    /// <summary>去掉标签开头的「第 N 组」编号；整段只有编号时丢掉。</summary>
    public static string StripGroup(string? t) => GroupPrefix.Replace((t ?? "").Trim(), "").Trim();

    /// <summary>
    /// 合并细分标签：按 ·/；拆段、去空、去「第 N 组」、去重（保序）再拼回去。
    /// 同一个词在正文段落和归类表里都会出现，直接拼接会拼出「动作·对比辨析·动作」这种自我重复，
    /// App 的标签筛选就会多出无意义的项。
    /// </summary>
    public static string MergeTag(params string[] tags)
    {
        var parts = new List<string>();
        foreach (var t in tags)
        {
            foreach (var piece in TagSplit.Split(t ?? ""))
            {
                var p = StripGroup(piece);
                if (p.Length > 0 && !parts.Contains(p)) parts.Add(p);
            }
        }
        return string.Join("·", parts);
    }
}

internal static class Shell
{
    /// <summary>表头前 n 列是否等于期望值（列不够就不算命中）。</summary>
    public static bool HeadIs(List<List<string>> rows, int n, params string[] expected)
    {
        if (rows.Count == 0 || rows[0].Count < n) return false;
        for (var i = 0; i < n; i++)
        {
            if (!string.Equals(rows[0][i], expected[i], StringComparison.Ordinal)) return false;
        }
        return true;
    }
}

internal static class Csv
{
    /// <summary>按 Python csv 的 QUOTE_MINIMAL 规则拼一行：只有含分隔符、引号或换行时才加引号。</summary>
    public static void AppendRow(StringBuilder sb, params string[] fields)
    {
        for (var i = 0; i < fields.Length; i++)
        {
            if (i > 0) sb.Append(',');
            sb.Append(Quote(fields[i] ?? ""));
        }
        sb.Append('\n');
    }

    private static string Quote(string f)
    {
        var need = f.IndexOfAny(new[] { ',', '"', '\r', '\n' }) >= 0;
        return need ? "\"" + f.Replace("\"", "\"\"") + "\"" : f;
    }
}

internal sealed class Entry(string text, string category, string tag, string meaning, string source)
{
    public string Text { get; } = text;
    public string Category { get; set; } = category;
    public string Tag { get; set; } = tag;
    public string Meaning { get; set; } = meaning;
    public string Source { get; } = source;
}
