#!/usr/bin/env dotnet
// 两份「思维导图」PDF 的 OCR 结果 → App 的导图资产
// =================================================================
// 输入（tools/ocr_pdf.cs 的产物，坐标基准 220dpi）：
//   · 【汇总版】思维导图  → tmp/ocr/mm_*.json   骨架：章 → 节 → 知识梳理树
//   · 政治理论思维导图大盘点 → tmp/ocr/dp_*.json  补充：汇总版里没有的板块
// 输出：
//   · app/src/main/assets/recite_mindmap.json   导图页读的资产（sections → topics → nodes）
//   · tmp/mindmap/tree-preview.md               人工核对用的缩进树
//
// 还原规则：
//   · 一页 = 一个主题。汇总版每页都有【知识梳理】（导图）/【本节带背】（填空自测）两块，
//     只取【知识梳理】；只有【本节带背】的页跳过
//   · 按 x 聚成列：列越靠右层级越深；父节点 = 上一列里 y 最近的那个（同一列内按 y 单调分配）
//   · 单字符碎片（「○」「-」「?」「6」）和带（）的填空行一律丢弃
//   · 大盘点页若内容已被汇总版覆盖（节点文本重合过半）就不重复收录
//
// 用法：
//   dotnet run tools/build_mindmap_pdf.cs
//   dotnet run tools/build_mindmap_pdf.cs -- --check

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
var checkOnly = argsList.Contains("--check");
var assetPath = Path.GetFullPath(Arg("--out", Path.Combine(repo, "app/src/main/assets/recite_mindmap.json")));
var previewPath = Path.GetFullPath(Arg("--preview", Path.Combine(repo, "tmp/mindmap/tree-preview.md")));

var mmPages = LoadPages(Directory.GetFiles(Path.Combine(repo, "tmp/ocr"), "mm_*.json").OrderBy(p => p).ToList());
var dpPages = LoadPages(Directory.GetFiles(Path.Combine(repo, "tmp/ocr"), "dp_*.json").OrderBy(p => p).ToList());
if (mmPages.Count == 0 && dpPages.Count == 0)
{
    Console.Error.WriteLine("tmp/ocr 下没有 mm_*.json / dp_*.json，先跑 tools/ocr_pdf.cs");
    return 2;
}

const string X = "汇总版";
const string D = "大盘点";

var chapters = new List<string>();          // 按出现顺序
var chapterOfPage = new Dictionary<int, string>();
var topics = new List<Topic>();

// ---------------------------------------------------------------- 汇总版：章 / 节 / 知识梳理

foreach (var (pg, lines) in mmPages)
{
    var rows = MergeRows(lines);
    if (rows.Count == 0) continue;

    var headRows = rows.Where(r => r.Y is > 255 and < 470).ToList();
    var headText = string.Join(" ", headRows.Select(r => r.Text));

    // 章：出现在结构页 / 章首页，形如「第一章习近平新时代中国特色社会主义思想」
    var mc = Regex.Match(headText, @"第([一二三四五六七八九十]+)章\s*([\u4e00-\u9fa5“”·、]{0,40})");
    if (mc.Success)
    {
        var name = "第" + mc.Groups[1].Value + "章 " + Clean(mc.Groups[2].Value);
        if (!chapters.Contains(name)) chapters.Add(name);
        chapterOfPage[pg] = name;
    }

    // 封面 / 目录 / 考情 / 全书结构页都没有知识梳理块，直接跳过
    var (label, title) = SectionTitle(headRows);
    if (title.Length == 0) continue;

    var block = KnowledgeBlock(rows);
    if (block.Count < 3) continue;

    var roots = BuildTree(block);
    if (roots.Count == 0) continue;

    topics.Add(new Topic
    {
        Id = $"m{pg:00}",
        Chapter = CurChapter(pg),
        Label = label,
        Name = title,
        Book = X,
        Page = pg,
        Roots = roots,
    });
}

// ---------------------------------------------------------------- 大盘点：用已校准的树形结构

var pairsByTopic = new Dictionary<string, List<(string L, string R)>>(StringComparer.Ordinal);
var pairsPath = Path.Combine(repo, "tools/mindmap/pairs.json");
if (File.Exists(pairsPath))
{
    var doc = JsonNode.Parse(File.ReadAllText(pairsPath, Encoding.UTF8));
    if (doc?["pages"] is JsonObject pagesObj)
        foreach (var kv in pagesObj)
        {
            if (kv.Value is not JsonArray arr) continue;
            var list = new List<(string L, string R)>();
            foreach (var it in arr)
            {
                var l = (it?["left"]?.GetValue<string>() ?? "").Trim();
                var r = (it?["right"]?.GetValue<string>() ?? "").Trim();
                if (l.Length > 0 && r.Length > 0) list.Add((l, r));
            }
            if (list.Count > 0) pairsByTopic[kv.Key] = list;
        }
}

var corpus = string.Join("\n", topics.SelectMany(t => AllTexts(t.Roots)));
var corpusGrams = Grams(corpus);
var treePath = Path.Combine(repo, "政治理论思维导图大盘点-树形结构.json");
if (File.Exists(treePath))
{
    var tree = JsonNode.Parse(File.ReadAllText(treePath, Encoding.UTF8))!;
    foreach (var secNode in tree["children"]?.AsArray() ?? new JsonArray())
    {
        var secName = Clean(secNode?["name"]?.GetValue<string>() ?? "");
        foreach (var tNode in secNode?["children"]?.AsArray() ?? new JsonArray())
        {
            var rawName = tNode?["name"]?.GetValue<string>() ?? "";
            var pg = tNode?["page"]?.GetValue<int>() ?? 0;
            var roots = new List<Node>();
            foreach (var kid in tNode?["children"]?.AsArray() ?? new JsonArray())
            {
                var built = BuildNode(kid);
                if (built != null) roots.Add(built);
            }
            if (roots.Count == 0) continue;

            var pairs = pairsByTopic.GetValueOrDefault($"mm{pg - 2:00}") ?? new List<(string L, string R)>();
            // 4-gram 覆盖率过半 = 这块内容汇总版已经讲过了，不再重复收录（连线配对是人工校准的，留着）
            var grams = Grams(string.Join("\n", AllTexts(roots)));
            if (pairs.Count == 0 && grams.Count > 0
                && grams.Count(g => corpusGrams.Contains(g)) * 1.0 / grams.Count >= 0.5) continue;

            // 「书页 04 · 五中全会3-4 · 党性与从严治吏」→ 去掉书页前缀
            var name = Regex.Replace(rawName, @"^书页\s*\d+\s*[·・]\s*", "");
            topics.Add(new Topic
            {
                Id = $"d{pg:00}",
                Chapter = secName,
                Label = $"书页 {Math.Max(0, pg - 2):00}",
                Name = Clean(name),
                Book = D,
                Page = pg,
                Roots = roots,
                Pairs = pairs,
            });
        }
    }
}

// 章节顺序：先按书里出现的章，再补上只在某个来源里出现的板块
foreach (var c in topics.Select(t => t.Chapter).Where(c => c.Length > 0).Distinct())
    if (!chapters.Contains(c)) chapters.Add(c);
if (chapters.Count == 0) chapters.Add("政治理论");

var sections = chapters.Select((c, i) => (Id: $"s{i + 1:00}", Name: c, Topics: topics.Where(t => t.Chapter == c).ToList())).ToList();
sections = sections.Where(s => s.Topics.Count > 0).ToList();

Console.WriteLine($"主题 {topics.Count}（汇总版 {topics.Count(t => t.Book == X)} / 大盘点 {topics.Count(t => t.Book == D)}）");
Console.WriteLine($"节点 {topics.Sum(t => CountNodes(t.Roots))}");
foreach (var s in sections)
    Console.WriteLine($"  {s.Name}: {s.Topics.Count} 个主题 / {s.Topics.Sum(t => CountNodes(t.Roots))} 个节点");
if (topics.Count == 0)
{
    Console.Error.WriteLine("没还原出任何主题，先看 OCR 结果");
    return 2;
}

Directory.CreateDirectory(Path.GetDirectoryName(previewPath)!);
File.WriteAllText(previewPath, Preview(sections), new UTF8Encoding(false));

if (checkOnly)
{
    Console.WriteLine("--check：未写入资产");
    return 0;
}

WriteAsset(sections);
Console.WriteLine($"写入 {Path.GetRelativePath(repo, assetPath)}");
Console.WriteLine($"预览 {Path.GetRelativePath(repo, previewPath)}");
return 0;

// ---------------------------------------------------------------- 结构还原

/// <summary>中文数字转阿拉伯数字（第一节 / 第十七节），认不出返回 0。</summary>
static int CnNumber(string cn)
{
    const string DIGITS = "零一二三四五六七八九";
    int total = 0, cur = 0;
    foreach (var ch in cn)
    {
        int i = DIGITS.IndexOf(ch);
        if (i >= 0) cur = i;
        else if (ch is '十')
        {
            total += (cur == 0 ? 1 : cur) * 10;
            cur = 0;
        }
        else return 0;
    }
    return total + cur;
}

/// <summary>题名：页头里除章名 / 页眉之外的「第X节 …」「导论」。</summary>
(string Label, string Name) SectionTitle(List<Row> headRows)
{
    foreach (var r in headRows)
    {
        var t = r.Text;
        if (t.Contains("超格学员专用", StringComparison.Ordinal)) continue;
        if (Regex.IsMatch(t, @"^第[一二三四五六七八九十]+章")) continue;
        if (t.Contains("政治理论考点梳理", StringComparison.Ordinal) || t.Contains("政治理论考情分析", StringComparison.Ordinal)) continue;
        var m = Regex.Match(t, @"(第[一二三四五六七八九十]+节)\s*([\u4e00-\u9fa5“”·、（）()]{2,40})");
        if (m.Success)
        {
            // 列表卡片的徽标取 label 里的数字（「书页 02」→ 02）；「第一节」没数字会显示成「·」
            int no = CnNumber(m.Groups[1].Value[1..^1]);
            return (no > 0 ? $"节 {no:00}" : m.Groups[1].Value, Clean(m.Groups[2].Value));
        }
        if (t.StartsWith("导论", StringComparison.Ordinal)) return ("导论", "导论");
    }
    return ("", "");
}

/// <summary>【知识梳理】到【本节带背】之间的行；没有知识梳理标记时返回空。</summary>
static List<Row> KnowledgeBlock(List<Row> rows)
{
    int start = rows.FindIndex(r => r.Text.Contains("知识梳理", StringComparison.Ordinal));
    if (start < 0) return new List<Row>();
    int end = rows.FindIndex(start + 1, r => r.Text.Contains("本节带背", StringComparison.Ordinal));
    if (end < 0) end = rows.Count;
    return rows.Skip(start + 1).Take(end - start - 1)
               .Where(r => r.Y is > 255 and < 2350 && !IsJunk(r.Text))
               .ToList();
}

/// <summary>按 x 聚列 → 定层级 → 找父节点。</summary>
static List<Node> BuildTree(List<Row> rows)
{
    if (rows.Count == 0) return new List<Node>();
    var sorted = rows.OrderBy(r => r.X).ThenBy(r => r.Y).ToList();

    var columns = new List<List<Row>>();
    foreach (var r in sorted)
    {
        if (columns.Count == 0 || r.X - columns[^1][^1].X > 70) columns.Add(new List<Row> { r });
        else columns[^1].Add(r);
    }
    if (columns.Count == 0) return new List<Node>();

    // 根列：最左边一列；同列多个节点时各自成树
    var roots = columns[0].OrderBy(r => r.Y).Select(r => new Node(r.Text) { Y = r.Y }).ToList();
    var nodesOfLevel = new List<List<Node>> { roots };

    for (int ci = 1; ci < columns.Count; ci++)
    {
        var parents = nodesOfLevel[ci - 1];
        var cur = new List<Node>();
        int lastParent = 0;
        foreach (var r in columns[ci].OrderBy(r => r.Y))
        {
            // y 单调：父节点下标只能往后走，避免子节点挂到上面的兄弟枝
            int best = lastParent;
            double bestDy = double.MaxValue;
            for (int pi = lastParent; pi < parents.Count; pi++)
            {
                double dy = Math.Abs(parents[pi].Y - r.Y);
                if (dy < bestDy) { bestDy = dy; best = pi; }
            }
            lastParent = best;
            var node = new Node(r.Text) { Y = r.Y };
            parents[best].Children.Add(node);
            cur.Add(node);
        }
        nodesOfLevel.Add(cur);
    }
    return roots;
}

string CurChapter(int pg)
{
    // 用「本页或之前最近一次出现的章」兜住跨页小节
    if (chapterOfPage.TryGetValue(pg, out var c)) return c;
    for (int p = pg; p >= 0; p--)
        if (chapterOfPage.TryGetValue(p, out var prev)) return prev;
    return chapters.Count > 0 ? chapters[0] : "";
}

// ---------------------------------------------------------------- 行处理

static List<Row> MergeRows(List<Line> lines)
{
    var sorted = lines.Where(l => l.Text.Trim().Length > 0 && !IsJunk(l.Text))
                      .OrderBy(l => l.Y).ThenBy(l => l.X).ToList();
    var rows = new List<Row>();
    foreach (var l in sorted)
    {
        // 同一行被 OCR 切成几段才合并；mindmap 里同一 y 的不同列是不同层的节点，不能并
        if (rows.Count > 0 && Math.Abs(rows[^1].Y - l.Y) <= 10 && l.X - rows[^1].X2 < 50)
        {
            var last = rows[^1];
            rows[^1] = new Row(last.Y, last.X, l.X2, last.Text + l.Text);
        }
        else
        {
            rows.Add(new Row(l.Y, l.X, l.X2, l.Text.Trim()));
        }
    }
    return rows;
}

/// <summary>碎片、页码、水印、填空行。</summary>
static bool IsJunk(string raw)
{
    var t = raw.Trim();
    if (t.Length == 0) return true;
    string[] NOISE = { "超格学员专用", "仅供超格", "抖音", "追梦人" };
    foreach (var n in NOISE) if (t.Contains(n, StringComparison.Ordinal)) return true;
    if (Regex.IsMatch(t, @"^\d{1,3}$")) return true;
    if (t.Length <= 2 && Regex.IsMatch(t, @"^[\p{P}\p{S}\dA-Za-z②③④⑤⑥⑦⑧]+$")) return true;
    // 填空自测行：（）/（ 出现在正文里
    if (t.Contains("（）", StringComparison.Ordinal)) return true;
    if (Regex.IsMatch(t, @"[（(]\s*[）)]")) return true;
    return false;
}

static string Clean(string t)
{
    t = Regex.Replace(t ?? "", @"[\s\u3000]+", "");
    t = Regex.Replace(t, @"^[•·\-—–_|、,，.。:：*★☆]+", "");
    t = Regex.Replace(t, @"[•·\-—–_|、,，.。:：…]+$", "");
    return t.Trim();
}

static IEnumerable<string> AllTexts(List<Node> nodes)
{
    foreach (var n in nodes)
    {
        var key = Regex.Replace(n.Text, @"[\s\p{P}\p{S}]", "");
        if (key.Length > 5) yield return key;
        foreach (var t in AllTexts(n.Children)) yield return t;
    }
}

/// <summary>4-gram 集合：用来判断两段内容是不是在讲同一件事。</summary>
static HashSet<string> Grams(string text)
{
    var set = new HashSet<string>(StringComparer.Ordinal);
    var t = Regex.Replace(text ?? "", @"[\s\p{P}\p{S}]", "");
    for (int i = 0; i + 4 <= t.Length; i++) set.Add(t.Substring(i, 4));
    return set;
}

static int CountNodes(List<Node> nodes) => nodes.Sum(n => 1 + CountNodes(n.Children));

static Node? BuildNode(JsonNode? raw)
{
    var text = (raw?["name"]?.GetValue<string>() ?? "").Trim();
    if (text.Length == 0) return null;
    var node = new Node(text) { Y = raw?["y"]?.GetValue<double>() ?? 0 };
    foreach (var kid in raw?["children"]?.AsArray() ?? new JsonArray())
    {
        var built = BuildNode(kid);
        if (built != null) node.Children.Add(built);
    }
    return node;
}

static string Preview(List<(string Id, string Name, List<Topic> Topics)> secs)
{
    var sb = new StringBuilder();
    sb.AppendLine("# 导图还原预览");
    foreach (var s in secs)
    {
        sb.AppendLine();
        sb.AppendLine($"## {s.Name}");
        foreach (var t in s.Topics)
        {
            sb.AppendLine();
            sb.AppendLine($"### {t.Name}  （{t.Book} 第 {t.Page} 页）");
            foreach (var r in t.Roots) Dump(sb, r, 1);
        }
    }
    return sb.ToString();

    static void Dump(StringBuilder sb, Node n, int depth)
    {
        sb.AppendLine(new string(' ', depth * 2) + "- " + n.Text);
        foreach (var c in n.Children) Dump(sb, c, depth + 1);
    }
}

void WriteAsset(List<(string Id, string Name, List<Topic> Topics)> secs)
{
    Directory.CreateDirectory(Path.GetDirectoryName(assetPath)!);
    using var fs = File.Create(assetPath);
    using var w = new Utf8JsonWriter(fs, new JsonWriterOptions
    {
        Indented = true,
        NewLine = "\n",
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    });
    w.WriteStartObject();
    w.WriteNumber("version", 2);   // 与 MindMapRepository.VERSION 对齐，改结构才动
    w.WriteString("title", "政治理论思维导图");
    w.WriteString("updated", DateTime.Now.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture));
    w.WriteString("intro",
        $"按《【汇总版】思维导图》的章/节结构还原：{secs.Count} 个板块 {topics.Count} 个主题 "
        + $"{topics.Sum(t => CountNodes(t.Roots))} 个节点；每节取【知识梳理】那一页的导图，"
        + "填空自测页不进导图（那是背诵条目）。整句保留、不挖空，适合通读和朗读。");

    w.WriteStartArray("sections");
    foreach (var s in secs)
    {
        w.WriteStartObject();
        w.WriteString("id", s.Id);
        w.WriteString("name", s.Name);
        w.WriteStartArray("topics");
        foreach (var t in s.Topics)
        {
            w.WriteStartObject();
            w.WriteString("id", t.Id);
            w.WriteNumber("page", t.Page);
            w.WriteString("label", t.Label);
            w.WriteString("name", t.Name);
            w.WriteStartArray("nodes");
            foreach (var n in t.Roots) WriteNode(w, n);
            w.WriteEndArray();
            if (t.Pairs.Count > 0)
            {
                w.WriteStartArray("pairs");
                foreach (var (l, r) in t.Pairs)
                {
                    w.WriteStartObject();
                    w.WriteString("left", l);
                    w.WriteString("right", r);
                    w.WriteEndObject();
                }
                w.WriteEndArray();
            }
            w.WriteEndObject();
        }
        w.WriteEndArray();
        w.WriteEndObject();
    }
    w.WriteEndArray();
    w.WriteEndObject();
}

static void WriteNode(Utf8JsonWriter w, Node node)
{
    w.WriteStartObject();
    w.WriteString("text", node.Text);
    if (node.Children.Count > 0)
    {
        w.WriteStartArray("children");
        foreach (var c in node.Children) WriteNode(w, c);
        w.WriteEndArray();
    }
    w.WriteEndObject();
}

static SortedDictionary<int, List<Line>> LoadPages(List<string> files)
{
    var pages = new SortedDictionary<int, List<Line>>();
    foreach (var f in files)
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(f, Encoding.UTF8));
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
    return pages;
}

// ---------------------------------------------------------------- 模型

internal sealed record Line(double Y, double X, double X2, string Text);
internal sealed record Row(double Y, double X, double X2, string Text);

internal sealed class Node
{
    public Node(string text) { Text = text; }
    public string Text { get; }
    public double Y { get; set; }
    public List<Node> Children { get; } = new();
}

internal sealed class Topic
{
    public string Id { get; init; } = "";
    public string Chapter { get; init; } = "";
    public string Label { get; init; } = "";
    public string Name { get; init; } = "";
    public string Book { get; init; } = "";
    public int Page { get; init; }
    public List<Node> Roots { get; init; } = new();
    public List<(string L, string R)> Pairs { get; init; } = new();
}
