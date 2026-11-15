#!/usr/bin/env dotnet
#:property PublishAot=false

// 把《政治理论思维导图大盘点》的树形结构合成 App 的导图资产。
//
// 输入：
//   政治理论思维导图大盘点-树形结构.json   # tools/build_mindmap_tree.cs 从 OCR 还原出的树
//                                        # （板块 → 主题 → 中心主题 → 分支 → 要点，带 x/y 坐标）
//   tools/mindmap/pairs.json             # 人工校准的内涵连线（新树把连线题错还原成了父子，正确配对单独存）
// 输出：
//   app/src/main/assets/recite_mindmap.json
//
// 为什么要转一道：源树带坐标、层级 7 层、主题名挂着「书页 04 ·」前缀，
// 这些是 OCR 溯源用的；资产只留渲染要的（板块 / 主题 / 树 / 配对），
// 坐标留在源文件里，将来真要做图形化导图再从树形结构 json 生成。
//
// 用法：
//   dotnet run tools/build_mindmap_asset.cs
//   dotnet run tools/build_mindmap_asset.cs -- --check      # 只校验，不写文件
//   dotnet run tools/build_mindmap_asset.cs -- --updated 2026-11-15

using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

static string RepoRoot([System.Runtime.CompilerServices.CallerFilePath] string self = "") =>
    Path.GetFullPath(Path.Combine(Path.GetDirectoryName(self)!, ".."));

var repo = RepoRoot();
var treePath = Path.Combine(repo, "政治理论思维导图大盘点-树形结构.json");
var pairsPath = Path.Combine(repo, "tools", "mindmap", "pairs.json");
var assetPath = Path.Combine(repo, "app", "src", "main", "assets", "recite_mindmap.json");

var checkOnly = args.Contains("--check");
var updatedArg = ArgValue("--updated");
var today = updatedArg ?? DateTime.Now.ToString("yyyy-MM-dd");

var tree = JsonNode.Parse(File.ReadAllText(treePath, Encoding.UTF8))
    ?? throw new InvalidOperationException($"树形结构 json 解析失败：{treePath}");
var pairsDoc = JsonNode.Parse(File.ReadAllText(pairsPath, Encoding.UTF8));
var pairsByTopic = new Dictionary<string, JsonArray>(StringComparer.Ordinal);
if (pairsDoc?["pages"] is JsonObject pagesObj)
{
    foreach (var kv in pagesObj)
        if (kv.Value is JsonArray arr && arr.Count > 0)
            pairsByTopic[kv.Key] = arr;
}

var sectionsJson = tree["children"] as JsonArray
    ?? throw new InvalidOperationException("树形结构 json 缺少顶层 children");

// 「书页 04 · 习近平党建思想」→ 书页号 4 + 干净标题
var topicName = new Regex(@"^书页\s*(\d+)\s*[·・]\s*(.+)$");

var sections = new List<(string Id, string Name, List<Topic> Topics)>();
var sourceNodes = 0;
var usedPairs = new HashSet<string>(StringComparer.Ordinal);

for (var i = 0; i < sectionsJson.Count; i++)
{
    var secNode = sectionsJson[i] ?? throw new InvalidOperationException($"sections[{i}] 为空");
    var secName = (secNode["name"]?.GetValue<string>() ?? "").Trim();
    if (secName.Length == 0) throw new InvalidOperationException($"sections[{i}] 没有 name");

    var topics = new List<Topic>();
    if (secNode["children"] is JsonArray topicArr)
    {
        foreach (var tNode in topicArr)
        {
            if (tNode is null) continue;
            var raw = (tNode["name"]?.GetValue<string>() ?? "").Trim();
            var pdfPage = tNode["page"]?.GetValue<int>() ?? 0;

            var label = "";
            var title = raw;
            var bookPage = pdfPage > 2 ? pdfPage - 2 : pdfPage;
            var m = topicName.Match(raw);
            if (m.Success)
            {
                bookPage = int.Parse(m.Groups[1].Value);
                title = m.Groups[2].Value.Trim();
                label = $"书页 {bookPage:00}";
            }
            else if (bookPage > 0)
            {
                label = $"书页 {bookPage:00}";
            }

            var roots = new List<Node>();
            if (tNode["children"] is JsonArray kidArr)
            {
                foreach (var kid in kidArr)
                {
                    if (kid is null) continue;
                    var built = BuildNode(kid);
                    if (built is not null) roots.Add(built);
                }
            }
            if (roots.Count == 0)
                throw new InvalidOperationException($"主题「{raw}」没有任何节点");

            var id = $"mm{bookPage:00}";
            var pairs = new List<(string L, string R)>();
            if (pairsByTopic.TryGetValue(id, out var pArr))
            {
                usedPairs.Add(id);
                foreach (var p in pArr)
                {
                    if (p is null) continue;
                    var l = (p["left"]?.GetValue<string>() ?? "").Trim();
                    var r = (p["right"]?.GetValue<string>() ?? "").Trim();
                    if (l.Length > 0 && r.Length > 0) pairs.Add((l, r));
                }
            }

            sourceNodes += CountNodes(roots);
            topics.Add(new Topic(id, pdfPage, label, title, roots, pairs));
        }
    }
    if (topics.Count == 0) throw new InvalidOperationException($"板块「{secName}」没有主题");
    sections.Add(($"s{i + 1:00}", secName, topics));
}

// ---------------------------------------------------------------- 校验

var topicTotal = sections.Sum(s => s.Topics.Count);
var dupIds = sections.SelectMany(s => s.Topics).GroupBy(t => t.Id).Where(g => g.Count() > 1)
    .Select(g => g.Key).ToList();
if (dupIds.Count > 0)
    throw new InvalidOperationException($"topic id 重复（书页号撞了）：{string.Join(", ", dupIds)}");

var missingPairs = pairsByTopic.Keys.Where(k => !usedPairs.Contains(k)).ToList();
foreach (var k in missingPairs)
    Console.WriteLine($"⚠ pairs.json 里的 {k} 没找到对应主题，已忽略");

Console.WriteLine($"板块 {sections.Count}");
Console.WriteLine($"主题 {topicTotal}");
Console.WriteLine($"节点 {sourceNodes}");
Console.WriteLine($"配对 {sections.Sum(s => s.Topics.Sum(t => t.Pairs.Count))} 组（{usedPairs.Count} 个主题）");

if (sections.Count == 0 || topicTotal == 0 || sourceNodes == 0)
    throw new InvalidOperationException("生成结果为空，先看树形结构 json 是不是选错了");
if (checkOnly)
{
    Console.WriteLine("--check：未写入资产");
    return;
}

// ---------------------------------------------------------------- 写资产

var tmpPath = assetPath + ".tmp";
using (var stream = File.Create(tmpPath))
using (var w = new Utf8JsonWriter(stream, new JsonWriterOptions
       {
           Indented = true,
           // 固定 LF：Windows 上默认是 CRLF，资产进 git 会有整文件换行的噪音
           NewLine = "\n",
           // 中文不转义，资产要能直接 diff / 肉眼看
           Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
       }))
{
    w.WriteStartObject();
    w.WriteNumber("version", 2);
    w.WriteString("title", "政治理论思维导图大盘点");
    w.WriteString("updated", today);
    w.WriteString(
        "intro",
        $"{sections.Count} 个板块 {topicTotal} 个主题，按原书导图的连线还原成树：中心主题 → 分支 → 要点，"
        + "整句保留、不挖空，适合通读和朗读；书页上的「内涵连线」另做成配对自测。"
    );

    w.WriteStartArray("sections");
    foreach (var (id, name, topics) in sections)
    {
        w.WriteStartObject();
        w.WriteString("id", id);
        w.WriteString("name", name);
        w.WriteStartArray("topics");
        foreach (var t in topics)
        {
            w.WriteStartObject();
            w.WriteString("id", t.Id);
            if (t.Page > 0) w.WriteNumber("page", t.Page);
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
File.Move(tmpPath, assetPath, overwrite: true);

var sizeKb = new FileInfo(assetPath).Length / 1024.0;
Console.WriteLine($"写入 {Path.GetRelativePath(repo, assetPath)}（{sizeKb:F0} KB，updated={today}）");

// ---------------------------------------------------------------- 小工具

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

static Node? BuildNode(JsonNode raw)
{
    var text = (raw["name"]?.GetValue<string>() ?? "").Trim();
    if (text.Length == 0) return null;
    var kids = new List<Node>();
    if (raw["children"] is JsonArray arr)
        foreach (var k in arr)
        {
            if (k is null) continue;
            var built = BuildNode(k);
            if (built is not null) kids.Add(built);
        }
    return new Node(text, kids);
}

static int CountNodes(List<Node> nodes) => nodes.Sum(n => 1 + CountNodes(n.Children));

string? ArgValue(string key)
{
    for (var i = 0; i < args.Length - 1; i++)
        if (args[i] == key) return args[i + 1];
    return null;
}

internal sealed record Node(string Text, List<Node> Children);

internal sealed record Topic(
    string Id,
    int Page,
    string Label,
    string Name,
    List<Node> Roots,
    List<(string L, string R)> Pairs
);
