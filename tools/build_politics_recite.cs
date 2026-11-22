#!/usr/bin/env dotnet
// 把《2027 政治理论背诵手册 · 重点背诵（汇总版）》的 OCR 结果整理成背诵资产。
// =================================================================
// 输入：tmp/ocr/bm_a.json + tmp/ocr/bm_b.json（tools/ocr_pdf.cs 的产物，坐标 220dpi）
// 输出：
//   · app/src/main/assets/recite_library.json   政治大纲页读的统一库（只放本册条目）
//     —— 政治卡片库 recite_politics.json 已退场（政治页改读统一库后没人再读它），
//        别再生成，免得又冒出一份和统一库打架的内容
//   · tmp/politics/cards-preview.md             人工核对用的纯文本清单
//
// 规则：
//   · 页眉页脚 / 页码 / 单字符碎片丢弃；「超格学员专用」这类水印按关键字过滤
//   · 每节从 1 重新编号，`N.` 开头的行起一张新卡，缩进更靠左的续行接回上一张
//   · 【对比】【记忆方法】、①②③ 列举另起一行，保留原样
//   · 挖空：中文引号里的短语 + 四位年份，最多 3 个（取最长的），其余按原文照排
//   · 标签 = 节对应的内容标签（沿用 App 里那套词表）+ 来源标签「重点背诵」
//
// 用法：
//   dotnet run tools/build_politics_recite.cs -- --src tmp/ocr/bm_a.json --src tmp/ocr/bm_b.json
//   dotnet run tools/build_politics_recite.cs -- --check      # 只打印统计，不写资产

using System.Globalization;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

Console.OutputEncoding = Encoding.UTF8;

static string RepoRoot([System.Runtime.CompilerServices.CallerFilePath] string self = "") =>
    Path.GetFullPath(Path.Combine(Path.GetDirectoryName(self)!, ".."));

var repo = RepoRoot();
var argsList = args.ToList();
string Arg(string name, string fallback)
{
    var i = argsList.IndexOf(name);
    return i >= 0 && i + 1 < argsList.Count ? argsList[i + 1] : fallback;
}
var srcs = new List<string>();
for (var i = 0; i < argsList.Count; i++)
    if (argsList[i] == "--src" && i + 1 < argsList.Count) srcs.Add(argsList[i + 1]);
if (srcs.Count == 0)
    srcs = Directory.GetFiles(Path.Combine(repo, "tmp/ocr"), "bm_*.json").OrderBy(p => p).ToList();

var checkOnly = argsList.Contains("--check");
var libraryPath = Path.GetFullPath(Arg("--library", Path.Combine(repo, "app/src/main/assets/recite_library.json")));
var previewPath = Path.GetFullPath(Arg("--preview", Path.Combine(repo, "tmp/politics/cards-preview.md")));

// ---------------------------------------------------------------- 内容标签（与 App 现有词表一致）

var TAG_DEFS = new (string Id, string Name)[]
{
    ("xinsixiang", "新思想"),
    ("dangjian", "党的建设"),
    ("jingji", "经济"),
    ("jinrong", "金融"),
    ("gaige", "改革开放"),
    ("fazhi", "法治"),
    ("minzhu", "民主政治"),
    ("wenhua", "文化"),
    ("jiaoyu", "教育科技"),
    ("minsheng", "民生"),
    ("sannong", "三农"),
    ("shengtai", "生态文明"),
    ("anquan", "安全国防"),
    ("waijiao", "外交"),
    ("dangshi", "党史"),
    ("guihua", "规划与会议"),
    ("jianghua", "系列讲话"),
    ("huiyi", "重要会议"),
    ("wenjian", "重要文件"),
    ("jichu", "基础理论"),
    ("minzu", "民族宗教"),
};

// 节名关键字 -> 主标签。命中第一条即用；都没命中时落到章默认标签。
var SECTION_TAGS = new (string Key, string Tag)[]
{
    ("一国两制", "anquan"),
    ("国防", "anquan"),
    ("国家安全", "anquan"),
    ("生态文明", "shengtai"),
    ("文化强国", "wenhua"),
    ("依法治国", "fazhi"),
    ("人民民主", "minzhu"),
    ("民生", "minsheng"),
    ("教育", "jiaoyu"),
    ("科技", "jiaoyu"),
    ("高质量发展", "jingji"),
    ("改革开放", "gaige"),
    ("从严治党", "dangjian"),
    ("党的全面领导", "dangjian"),
    ("大国外交", "waijiao"),
    ("人类命运共同体", "waijiao"),
    ("十五五", "guihua"),
    ("系列讲话", "jianghua"),
    ("重要会议", "huiyi"),
    ("重要文件", "wenjian"),
    ("马克思主义哲学", "jichu"),
    ("政治经济学", "jichu"),
    ("科学社会主义", "jichu"),
};

var chapterTag = new Dictionary<string, string>
{
    ["第一章"] = "xinsixiang",
    ["第二章"] = "guihua",
    ["第三章"] = "jichu",
};

const string SOURCE_TAG = "beisong";
const string SOURCE_NAME = "重点背诵";

// ---------------------------------------------------------------- 读 OCR

var pages = new SortedDictionary<int, List<Line>>();
foreach (var src in srcs)
{
    if (!File.Exists(src)) { Console.Error.WriteLine($"找不到 OCR 文件：{src}"); return 2; }
    using var doc = JsonDocument.Parse(File.ReadAllText(src, Encoding.UTF8));
    foreach (var prop in doc.RootElement.EnumerateObject())
    {
        var pg = int.Parse(prop.Name, CultureInfo.InvariantCulture);
        if (pages.ContainsKey(pg)) continue;
        var list = new List<Line>();
        foreach (var e in prop.Value.EnumerateArray())
            list.Add(new Line(
                e.GetProperty("y").GetDouble(),
                e.GetProperty("x").GetDouble(),
                e.GetProperty("x2").GetDouble(),
                e.GetProperty("text").GetString() ?? ""));
        pages[pg] = list;
    }
}
Console.WriteLine($"页数 {pages.Count}（{pages.Keys.Min()}..{pages.Keys.Max()}）");

// ---------------------------------------------------------------- 逐页解析

var cards = new List<Card>();
var sections = new List<Section>();
var chapterTitles = new Dictionary<string, string>(StringComparer.Ordinal);
Section? curSection = null;
string curChapter = "";
var curNo = 0;
Card? curCard = null;

foreach (var (pg, lines) in pages)
{
    var rows = MergeRows(lines);
    var prevWasChapter = false;

    foreach (var r in rows)
    {
        if (r.Y < 258 || r.Y > 2330) continue;
        var t = r.Text.Trim();
        if (t.Length == 0 || IsJunk(t)) continue;

        // 章头：`第一章` 单独一行时，下一行才是章名
        var mc = Regex.Match(t, @"^第([一二三四五六七八九十]+)章");
        if (mc.Success && r.Y < 480)
        {
            curChapter = "第" + mc.Groups[1].Value + "章";
            var rest = CleanTitle(t[mc.Length..]);
            if (rest.Length > 0) chapterTitles[curChapter] = rest;
            else prevWasChapter = true;
            continue;
        }
        if (prevWasChapter && r.Y < 480 && t.Length <= 30)
        {
            chapterTitles[curChapter] = CleanTitle(t);
            prevWasChapter = false;
            continue;
        }
        prevWasChapter = false;

        // 节头
        if (r.Y < 490 && (Regex.IsMatch(t, @"^第[一二三四五六七八九十]+节") || t.StartsWith("导论", StringComparison.Ordinal)))
        {
            var (label, name) = SplitSection(t);
            curSection = GetSection(pg, curChapter, label, name);
            curNo = 0;
            curCard = null;
            continue;
        }

        // 顶部的「十、重要指示」这类小标题只做分段，不能并进上一条正文
        if (r.Y < 490 && Regex.IsMatch(t, @"^[一二三四五六七八九十]+、"))
        {
            curCard = null;
            continue;
        }

        var m = Regex.Match(t, @"^(\d{1,3})\s*[.．、,，]\s*(.*)$");
        if (m.Success && r.X >= 150 && r.X <= 310)
        {
            var n = int.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture);
            // 编号偶有 OCR 跳号：允许往后多认 3 个，别让整节并成一张卡
            if (curNo == 0 || n == 1 || (n > curNo && n <= curNo + 3))
            {
                curNo = n;
                curSection ??= GetSection(pg, curChapter, "", "未分节");
                curCard = new Card
                {
                    No = curNo,
                    Section = curSection,
                    Page = pg,
                    Lines = new List<string> { m.Groups[2].Value.Trim() },
                };
                cards.Add(curCard);
                continue;
            }
        }

        if (curCard == null) continue;
        var prev = curCard.Lines[^1];
        // 【对比】【记忆方法】/（判断）/ 圈号列举：另起一行，读起来才分得清
        bool breakLine = Regex.IsMatch(t, @"^[【（(①②③④⑤⑥⑦⑧]");
        if (breakLine) curCard.Lines.Add(t);
        else curCard.Lines[^1] = prev + t;
    }
}

// ---------------------------------------------------------------- 归一化 + 挖空

foreach (var c in cards)
{
    var text = Polish(string.Join("\n", c.Lines)).Trim();
    text = Regex.Replace(text, @"\n{2,}", "\n");
    var (withBlanks, answers) = Blank(text);
    c.Text = withBlanks;
    c.Answers = answers;
}

// ---------------------------------------------------------------- 输出

var kept = cards.Where(c => c.Text.Length >= 8).ToList();
var dropped = cards.Count - kept.Count;

var tagCounts = new Dictionary<string, int>(StringComparer.Ordinal);
foreach (var c in kept)
    foreach (var t in c.Section.Tags)
        tagCounts[t] = tagCounts.GetValueOrDefault(t) + 1;

Console.WriteLine($"卡片 {kept.Count} 张（丢弃过短 {dropped}），节 {sections.Count} 个，章 {sections.Select(s => s.Chapter).Distinct().Count()} 个");
foreach (var g in kept.GroupBy(c => c.Section.Chapter))
    Console.WriteLine($"  {g.Key}: {g.Count()} 张 / {g.Select(c => c.Section.Label).Distinct().Count()} 节");
foreach (var t in TAG_DEFS)
    Console.WriteLine($"  标签 {t.Name,-5} {tagCounts.GetValueOrDefault(t.Id),5}");

Directory.CreateDirectory(Path.GetDirectoryName(previewPath)!);
File.WriteAllText(previewPath, BuildPreview(kept), new UTF8Encoding(false));

if (checkOnly)
{
    Console.WriteLine("--check：未写入资产");
    return 0;
}

WriteLibrary(kept, sections, tagCounts);

Console.WriteLine($"写入 {Path.GetRelativePath(repo, libraryPath)}");
Console.WriteLine($"预览 {Path.GetRelativePath(repo, previewPath)}");
return 0;

// ---------------------------------------------------------------- 结构

Section GetSection(int pg, string chapter, string label, string name)
{
    var exist = sections.FirstOrDefault(s => s.Label == label && s.Name == name);
    if (exist != null) return exist;
    var tags = new List<string>();
    foreach (var (key, tag) in SECTION_TAGS)
        if (name.Contains(key, StringComparison.Ordinal)) { tags.Add(tag); break; }
    if (tags.Count == 0) tags.Add(chapterTag.GetValueOrDefault(chapter, "xinsixiang"));
    var sec = new Section
    {
        Id = $"j{sections.Count + 1:00}",
        Chapter = chapter.Length == 0 ? "未分章" : chapter,
        Label = label,
        Name = name,
        Tags = tags,
        Page = pg,
    };
    sections.Add(sec);
    return sec;
}

static (string Label, string Name) SplitSection(string t)
{
    var m = Regex.Match(t, @"^(第[一二三四五六七八九十]+节)\s*(.*)$");
    if (m.Success) return (m.Groups[1].Value, CleanTitle(m.Groups[2].Value));
    if (t.StartsWith("导论", StringComparison.Ordinal)) return ("导论", CleanTitle(t[2..]));
    return ("", CleanTitle(t));
}

/// <summary>节名在 PDF 里常被装饰符号和 OCR 碎片污染（「•推动高质量发展」「坚持以人民为中心cY」）。</summary>
static string CleanTitle(string t)
{
    t = Regex.Replace(t, @"[\s\u3000]+", "");
    t = Regex.Replace(t, @"^[•·\-—–_|、,，.。:：*★☆邨昇郎郞々]+", "");
    t = Regex.Replace(t, @"[•·\-—–_|、,，.。:：…cC]+$", "");
    t = Regex.Replace(t, @"[A-Za-z]{1,2}$", "");
    return Polish(t);
}

/// <summary>同一 y 上的若干框按 x 拼成一行（「第一章」和章名分成两个框的情况）。</summary>
static List<Row> MergeRows(List<Line> lines)
{
    var sorted = lines.Where(l => l.Text.Trim().Length > 0 && !IsJunk(l.Text))
                      .OrderBy(l => l.Y).ThenBy(l => l.X).ToList();
    var rows = new List<Row>();
    foreach (var l in sorted)
    {
        if (rows.Count > 0 && Math.Abs(rows[^1].Y - l.Y) <= 12)
        {
            var last = rows[^1];
            rows[^1] = new Row(last.Y, last.X, l.X2, JoinOverlap(last.Text, l.Text));
        }
        else
        {
            rows.Add(new Row(l.Y, l.X, l.X2, l.Text.Trim()));
        }
    }
    return rows;
}

/// <summary>
/// 同一行的两个识别框拼接：OCR 常把「第八节发」和「发展全过程人民民主」切成两块，
/// 直接相加会得到「第八节发发展…」。两端有重叠时只取一次。
/// </summary>
static string JoinOverlap(string a, string b)
{
    if (a.EndsWith(b, StringComparison.Ordinal)) return a;
    if (b.StartsWith(a, StringComparison.Ordinal)) return b;
    int max = Math.Min(a.Length, b.Length);
    for (int k = max; k > 0; k--)
        if (a.EndsWith(b[..k], StringComparison.Ordinal)) return a + b[k..];
    return a + b;
}

/// <summary>水印、页码、单字符碎片。</summary>
static bool IsJunk(string t)
{
    string[] NOISE = { "超格学员专用", "仅供超格", "抖音", "追梦人" };
    foreach (var n in NOISE) if (t.Contains(n, StringComparison.Ordinal)) return true;
    if (Regex.IsMatch(t, @"^\d{1,3}$")) return true;
    if (t.Length <= 2 && Regex.IsMatch(t, @"^[\p{P}\p{S}\dA-Za-z]+$")) return true;
    return false;
}

/// <summary>OCR 常见成对错误 + 全角空格清理。</summary>
static string Polish(string t)
{
    t = t.Replace("\u00a0", " ").Replace("\u3000", " ").Trim();
    t = Regex.Replace(t, @"[ \t]{2,}", " ");
    t = t.Replace("一一", "——").Replace("一—", "——").Replace("—一", "——");
    var FIXES = new (string A, string B)[]
    {
        ("士个明确", "十个明确"), ("士四个坚持", "十四个坚持"), ("士三个方面成就", "十三个方面成就"),
        ("美其名日", "美其名曰"), ("名日", "名曰"), ("深人", "深入"), ("融人", "融入"),
        ("注人", "注入"), ("加人", "加入"), ("投人", "投入"), ("人市", "入市"),
        ("福社", "福祉"), ("路身", "跻身"), ("会唔", "会晤"),
        ("強大", "强大"), ("区別", "区别"), ("部份", "部分"),
        // 数字「十」被识成「土」：只收「第几届 / 十几大 / 十五五」这类不会与「土地」混淆的搭配
        ("土一届", "十一届"), ("土四大", "十四大"), ("土五大", "十五大"),
        ("土六大", "十六大"), ("土七大", "十七大"), ("土八大", "十八大"),
        ("土九大", "十九大"), ("土五五", "十五五"),
        ("士四大", "十四大"), ("士五大", "十五大"), ("士六大", "十六大"),
        ("士七大", "十七大"), ("士八大", "十八大"), ("士九大", "十九大"),
        ("士一届", "十一届"), ("士二届", "十二届"), ("士三届", "十三届"),
        ("士四届", "十四届"), ("士五届", "十五届"), ("士六届", "十六届"),
        ("士七届", "十七届"), ("士八届", "十八届"), ("士九届", "十九届"),
        ("土二年", "十二年"),
        ("上四个坚持", "十四个坚持"), ("上三个方面成就", "十三个方面成就"),
        ("一而就", "一蹴而就"), ("真抓实十", "真抓实干"), ("于事和干净", "干事和干净"),
        ("票赋", "禀赋"), ("十部队伍", "干部队伍"), ("暮然回首", "蓦然回首"),
        ("并行不悸", "并行不悖"), ("白年老店", "百年老店"),
    };
    foreach (var (a, b) in FIXES) t = t.Replace(a, b, StringComparison.Ordinal);
    // 引号错配：开后单引号收成双引号 / 开后双引号收成单引号
    t = Regex.Replace(t, @"‘([^’”]{2,20})”", "‘$1’");
    t = Regex.Replace(t, @"“([^“”‘]{1,20})’", "“$1”");
    t = Regex.Replace(t, @"“([^“”]{2,30})'", "“$1”");
    // 中文正文里的半角标点收成全角
    t = Regex.Replace(t, @"(?<=[\u4e00-\u9fff])[;:](?=[\u4e00-\u9fff])", m => m.Value == ";" ? "；" : "：");
    // 行尾被识别成一串点号的省略号
    t = Regex.Replace(t, @"[.·]{2,}\s*$", "……");
    // 拼接两行时可能出现的叠标点（「成就。” + “结合」）
    t = Regex.Replace(t, @"([，。；：、])\1+", "$1");
    // 红字/下划线块被识别成的尾巴碎片
    t = Regex.Replace(t, @"支美$", "");
    t = Regex.Replace(t, @"，生(?=[，。]|$)", "");
    // 跨页断行把开引号留在上一页
    t = Regex.Replace(t, @"^十五五”", "“十五五”");
    t = Regex.Replace(t, @"共同富裕十五五”", "共同富裕“十五五”");
    t = Regex.Replace(t, @"^一国两制”", "“一国两制”");
    t = Regex.Replace(t, @"坚持“大稳定、小调整$", "坚持“大稳定、小调整”");
    t = Regex.Replace(t, @"。，", "。");
    return t;
}

/// <summary>把中文引号短语和四位年份挖成 ____，最多 3 个（取最长的）。</summary>
static (string Text, List<string> Answers) Blank(string text)
{
    var spans = new List<(int Start, int Len, string Answer)>();
    foreach (Match m in Regex.Matches(text, @"[“「]([^”」]{2,14})[”」]"))
        spans.Add((m.Index, m.Length, m.Value));
    // 「‘三个进一步解放’」这类嵌套引号（外层是双引号时不会与上面重叠）
    foreach (Match m in Regex.Matches(text, @"‘([^’]{2,14})’"))
        spans.Add((m.Index, m.Length, m.Value));
    foreach (Match m in Regex.Matches(text, @"(?<!\d)(\d{4})(?=年)"))
        spans.Add((m.Index, m.Length, m.Value));

    // 重叠时保留更长的那个；再按长度取前 3
    var picked = new List<(int Start, int Len, string Answer)>();
    foreach (var s in spans.OrderByDescending(s => s.Len).ThenBy(s => s.Start))
    {
        if (picked.Any(p => s.Start < p.Start + p.Len && p.Start < s.Start + s.Len)) continue;
        picked.Add(s);
    }
    picked = picked.OrderByDescending(p => p.Len).Take(3).OrderBy(p => p.Start).ToList();
    if (picked.Count == 0) return (text, new List<string>());

    var sb = new StringBuilder();
    var answers = new List<string>();
    int pos = 0;
    foreach (var p in picked)
    {
        sb.Append(text, pos, p.Start - pos);
        sb.Append("____");
        answers.Add(p.Answer);
        pos = p.Start + p.Len;
    }
    sb.Append(text, pos, text.Length - pos);
    return (sb.ToString(), answers);
}

// ---------------------------------------------------------------- 写资产

void WriteLibrary(List<Card> items, List<Section> secs, Dictionary<string, int> counts)
{
    Directory.CreateDirectory(Path.GetDirectoryName(libraryPath)!);
    using var fs = File.Create(libraryPath);
    using var w = new Utf8JsonWriter(fs, new JsonWriterOptions
    {
        Indented = true,
        NewLine = "\n",
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    });
    w.WriteStartObject();
    w.WriteNumber("version", 2);
    w.WriteString("title", "政治理论");
    w.WriteString("updated", DateTime.Now.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture));
    w.WriteString("intro",
        $"{items.Count} 条背诵条目，全部来自《2027 政治理论背诵手册 · 重点背诵（汇总版）》"
        + $"（{secs.Select(s => s.Chapter).Distinct().Count()} 章 {secs.Count} 节）。"
        + "按内容标签分组、按节分段，一条一屏背；挖空是原文里的引号短语和年份。");

    w.WriteStartArray("tags");
    foreach (var (id, name) in TAG_DEFS)
    {
        if (counts.GetValueOrDefault(id) == 0) continue;   // 空标签不进大纲，免得点开一片空白
        w.WriteStartObject();
        w.WriteString("id", id);
        w.WriteString("name", name);
        w.WriteString("kind", "topic");
        w.WriteNumber("count", counts.GetValueOrDefault(id));
        w.WriteEndObject();
    }
    w.WriteStartObject();
    w.WriteString("id", SOURCE_TAG);
    w.WriteString("name", SOURCE_NAME);
    w.WriteString("kind", "source");
    w.WriteNumber("count", items.Count);
    w.WriteEndObject();
    w.WriteEndArray();

    w.WriteStartArray("items");
    var seq = new Dictionary<string, int>(StringComparer.Ordinal);
    foreach (var c in items)
    {
        var key = c.Section.Id;
        var n = seq.GetValueOrDefault(key) + 1;
        seq[key] = n;
        w.WriteStartObject();
        w.WriteString("id", $"{key}-{n:000}");
        w.WriteString("kind", "card");
        w.WriteString("text", c.Text);
        if (c.Answers.Count > 0)
        {
            w.WriteStartArray("answers");
            foreach (var a in c.Answers) w.WriteStringValue(a);
            w.WriteEndArray();
        }
        w.WriteString("source", c.Section.FullName);
        w.WriteStartArray("tags");
        foreach (var t in c.Section.Tags) w.WriteStringValue(t);
        w.WriteStringValue(SOURCE_TAG);
        w.WriteEndArray();
        w.WriteEndObject();
    }
    w.WriteEndArray();
    w.WriteEndObject();
}

string curChapterTitleOf(Section s) => chapterTitles.GetValueOrDefault(s.Chapter, "");

static string BuildPreview(List<Card> items)
{
    var sb = new StringBuilder();
    sb.AppendLine("# 重点背诵（汇总版）OCR 抽取预览");
    sb.AppendLine();
    string? lastSec = null;
    foreach (var c in items)
    {
        if (c.Section.FullName != lastSec)
        {
            sb.AppendLine();
            sb.AppendLine($"## {c.Section.Chapter} {c.Section.Label} {c.Section.Name}".Trim());
            sb.AppendLine();
            lastSec = c.Section.FullName;
        }
        sb.AppendLine($"{c.No}. {c.Text.Replace("\n", " ⏎ ")}");
        if (c.Answers.Count > 0) sb.AppendLine($"   → {string.Join(" / ", c.Answers)}");
    }
    return sb.ToString();
}

// ---------------------------------------------------------------- 模型

internal sealed record Line(double Y, double X, double X2, string Text);
internal sealed record Row(double Y, double X, double X2, string Text);

internal sealed class Card
{
    public int No { get; init; }
    public int Page { get; init; }
    public required Section Section { get; init; }
    public List<string> Lines { get; init; } = new();
    public string Text { get; set; } = "";
    public List<string> Answers { get; set; } = new();
}

internal sealed class Section
{
    public string Id { get; init; } = "";
    public string Chapter { get; init; } = "";
    public string Label { get; init; } = "";
    public string Name { get; init; } = "";
    public List<string> Tags { get; init; } = new();
    public int Page { get; init; }
    public string FullName => (Label + " " + Name).Trim();
}
