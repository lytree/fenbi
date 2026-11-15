#!/usr/bin/env dotnet
// 把《政治理论思维导图大盘点》的 OCR 结果（带坐标）还原成树形思维导图
// =================================================================
// 输入：tmp/mindmap/ocr_all.json（RapidOCR 逐页识别，每框 x0/y0/x1/t）
// 输出：
//   · 政治理论思维导图大盘点-树形结构.md   缩进树 + 每页 Mermaid mindmap
//   · 政治理论思维导图大盘点-树形结构.json 完整树（name/children/page/x/y）
//
// 用法：
//     dotnet run tools/build_mindmap_tree.cs
//     dotnet run tools/build_mindmap_tree.cs -- --src <ocr.json> --md <out.md> --json <out.json>
//
// 还原规则（参考 pdf-to-feishu-mindmap 的 x0 方向规则，针对本书是从左向右展开的树）：
//   · 过滤页眉页脚/水印；合并折行与竖排单字；按 (y 分带, x0) 排成阅读序列
//   · x0 越小层级越浅；用栈找「最近的、x0 明显更小的节点」作父节点
//   · 每页挂一个虚拟根（书页主题），全书再挂一个总根
//   · 连线题/表格页同样按此规则落成兄弟节点，不强行猜语义

#:property PublishAot=false

using System.Globalization;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

Console.OutputEncoding = Encoding.UTF8;

const string BOOK = "政治理论思维导图大盘点";

// PDF 页 -> 书页主题（与背诵资产里的分类名一致）
var TOPICS = new Dictionary<int, string>
{
    [4] = "五中全会3-4 · 党性与从严治吏", [5] = "五中全会5-6 · 组织建设与反腐",
    [6] = "习近平党建思想", [7] = "党的建设1 · 政治/思想/制度",
    [8] = "党的建设2 · 组织/作风/纪律/反腐", [9] = "党的建设3-4 · 自我革命",
    [10] = "党的建设5 · 正确政绩观", [11] = "两个结合 · 新思想科学体系",
    [12] = "社会主义现代化强国", [13] = "中国式现代化 · 四个时期",
    [14] = "改革开放与对外开放", [15] = "高质量发展",
    [16] = "中国特色金融发展之路", [17] = "现代金融体系",
    [18] = "实体经济与现代化产业体系", [19] = "扩大内需",
    [20] = "全过程人民民主", [21] = "全面依法治国",
    [22] = "教育强国 · 立德树人", [23] = "基础研究",
    [24] = "科学技术 · 奖励大会", [25] = "人工智能",
    [26] = "文化自信 · 文化发展道路", [27] = "民族宗教",
    [28] = "收入分配", [29] = "人口高质量发展 · 社会建设",
    [30] = "三农 · 乡村振兴", [31] = "三农 · 县乡村治理",
    [32] = "城市工作", [33] = "总体国家安全观",
    [34] = "平安中国 · 公共安全", [35] = "强军建设",
    [36] = "生态文明建设 · 四大转变", [37] = "全球治理倡议 · 大国关系",
    [38] = "建党105周年 · 伟大成就", [39] = "闽宁协作 · 义乌经验 · 六尺巷",
};

// 全书按内容分板块（PDF 页区间），作为根节点与书页之间的一层语义结构
var SECTIONS = new (int From, int To, string Name)[]
{
    (4, 10, "党的建设与全面从严治党"),
    (11, 13, "新思想与中国式现代化"),
    (14, 19, "经济高质量发展与金融"),
    (20, 21, "民主与法治"),
    (22, 27, "教育科技与文化"),
    (28, 32, "民生与社会建设"),
    (33, 35, "国家安全与国防"),
    (36, 36, "生态文明建设"),
    (37, 37, "外交与国际关系"),
    (38, 39, "党史成就与地方经验"),
};

string src = "tmp/mindmap/ocr_all.json";
string mdPath = "政治理论思维导图大盘点-树形结构.md";
string jsonPath = "政治理论思维导图大盘点-树形结构.json";
for (int i = 0; i < args.Length; i++)
{
    string a = args[i];
    if (a == "--src" && i + 1 < args.Length) src = args[++i];
    else if (a == "--md" && i + 1 < args.Length) mdPath = args[++i];
    else if (a == "--json" && i + 1 < args.Length) jsonPath = args[++i];
    else { Console.Error.WriteLine($"无法识别的参数：{a}"); return 2; }
}
if (!File.Exists(src)) { Console.Error.WriteLine($"找不到 OCR 结果：{src}"); return 2; }

using var jd = JsonDocument.Parse(File.ReadAllText(src, Encoding.UTF8));
var root = new Node { Name = BOOK };
var sections = SECTIONS.Select(s => new Node { Name = s.Name }).ToList();
var stats = new List<string>();
int totalNodes = 0;

foreach (var p in jd.RootElement.EnumerateArray())
{
    int page = p.GetProperty("page").GetInt32();
    var boxes = new List<Box>();
    foreach (var it in p.GetProperty("items").EnumerateArray())
        boxes.Add(new Box(
            it.GetProperty("y0").GetDouble(), it.GetProperty("x0").GetDouble(),
            it.GetProperty("x1").GetDouble(), it.GetProperty("t").GetString() ?? ""));
    if (boxes.Count == 0) continue;

    string topic = TOPICS.TryGetValue(page, out var t) ? t : $"书页 {page - 2:D2}";
    var pageNode = new Node { Name = $"书页 {page - 2:D2} · {topic}", Page = page };
    var lines = BuildLines(boxes);

    // 左侧短标签（x0 < 360）是导图分支名；表格页的标签垂直居中，
    // 因此用相邻标签 y 的中点把整页切段，段内文字都挂到该标签下。
    var anchorIdx = new HashSet<int>();
    for (int i = 0; i < lines.Count; i++)
        if (lines[i].X0 < 360 && lines[i].T.Length <= 20 && !Regex.IsMatch(lines[i].T, @"^\d"))
            anchorIdx.Add(i);
    var anchors = anchorIdx.OrderBy(i => i).Select(i => lines[i]).ToList();

    if (anchors.Count >= 2)
    {
        var boundaries = new List<double>();
        for (int i = 0; i < anchors.Count - 1; i++)
            boundaries.Add((anchors[i].Y0 + anchors[i + 1].Y0) / 2.0);
        var stacks = anchors.Select(a => new List<Node>
        {
            new Node { Name = a.T, Page = page, X = a.X0, Y = a.Y0 }
        }).ToList();
        foreach (var st in stacks) { pageNode.Children.Add(st[0]); totalNodes++; }

        for (int i = 0; i < lines.Count; i++)
        {
            if (anchorIdx.Contains(i)) continue;
            var b = lines[i];
            int seg = 0;
            while (seg < boundaries.Count && b.Y0 >= boundaries[seg]) seg++;
            var st = stacks[seg];
            // 段内再用 x0 栈找父节点；段根恒在栈底
            while (st.Count > 1 && st[^1].X >= b.X0 - 30) st.RemoveAt(st.Count - 1);
            var node = new Node { Name = b.T, Page = page, X = b.X0, Y = b.Y0 };
            st[^1].Children.Add(node);
            st.Add(node);
            totalNodes++;
        }
    }
    else
    {
        // 没足够标签时退回纯 x0 栈：最近的更浅节点作父
        var stack = new List<Node>();
        foreach (var b in lines)
        {
            while (stack.Count > 0 && stack[^1].X >= b.X0 - 30) stack.RemoveAt(stack.Count - 1);
            var node = new Node { Name = b.T, Page = page, X = b.X0, Y = b.Y0 };
            if (stack.Count == 0) pageNode.Children.Add(node);
            else stack[^1].Children.Add(node);
            stack.Add(node);
            totalNodes++;
        }
    }

    int si = Array.FindIndex(SECTIONS, s => page >= s.From && page <= s.To);
    if (si >= 0) sections[si].Children.Add(pageNode);
    else root.Children.Add(pageNode);
    stats.Add($"  书页 {page - 2:D2} {topic}: {CountNodes(pageNode)} 节点 / 深 {Depth(pageNode)} 层");
}
root.Children.AddRange(sections.Where(s => s.Children.Count > 0));

// ---------------- Markdown ----------------
var md = new StringBuilder();
md.AppendLine($"# {BOOK} · 树形结构\n");
md.AppendLine("> 由 OCR 文本框坐标还原：`x0` 越小层级越浅，按阅读顺序用栈找最近的更浅节点作父节点。  ");
md.AppendLine("> 每个叶子都是 PDF 里的一条原文（已合并折行、修正明显错字、去掉页眉页脚）。  ");
md.AppendLine("> 连线题、表格页按视觉左右关系落成兄弟/子节点，语义配对请以原图为准。  ");
md.AppendLine($"> 全书 {root.Children.Count} 个板块 / {root.Children.Sum(s => s.Children.Count)} 页主题 / {totalNodes} 个文本节点；PDF 前 3 页内容流损坏，无文本。\n");
md.AppendLine("## 目录\n");
foreach (var sec in root.Children)
{
    md.AppendLine($"- **{sec.Name}**");
    foreach (var pg in sec.Children) md.AppendLine($"  - {pg.Name}");
}
md.AppendLine();

foreach (var sec in root.Children)
foreach (var pg in sec.Children)
{
    int page = pg.Page ?? 0;
    md.AppendLine($"## {pg.Name}（PDF 第 {page} 页）\n");
    md.AppendLine($"> 板块：{sec.Name}\n");
    foreach (var child in pg.Children) WriteTree(md, child, 0);
    md.AppendLine();
    md.AppendLine("### Mermaid 视图\n");
    md.AppendLine("```mermaid");
    md.AppendLine("mindmap");
    md.AppendLine($"  root(({MermaidText(pg.Name)}))");
    foreach (var child in pg.Children) WriteMermaid(md, child, 2, 0);
    md.AppendLine("```\n");
}
File.WriteAllText(mdPath, md.ToString(), new UTF8Encoding(false));

// ---------------- JSON ----------------
var opts = new JsonSerializerOptions
{
    WriteIndented = true,
    Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
};
File.WriteAllText(jsonPath, JsonSerializer.Serialize(root, opts), new UTF8Encoding(false));

Console.WriteLine($"Markdown: {mdPath}");
Console.WriteLine($"JSON    : {jsonPath}");
Console.WriteLine($"板块 {root.Children.Count} 个 / 页主题 {root.Children.Sum(s => s.Children.Count)} 个 / 文本节点 {totalNodes} 个 / 总节点 {CountNodes(root)} 个");
foreach (var s in stats) Console.WriteLine(s);
return 0;

// ---------------- helpers ----------------

/// <summary>过滤、修正、合并折行与竖排单字，再按 (y 分带, x0) 排成阅读序列。</summary>
static List<Box> BuildLines(List<Box> boxes0)
{
    var boxes = boxes0.Where(b => !IsNoise(b))
        .Select(b => b with { T = Fix(b.T) })
        .Where(b => b.T.Length > 0)
        .OrderBy(b => b.Y0).ThenBy(b => b.X0)
        .ToList();
    var merged = new List<Box>();
    foreach (var b in boxes)
    {
        if (merged.Count > 0 && IsSingleCjk(b.T) && IsSingleCjk(merged[^1].T)
            && Math.Abs(b.X0 - merged[^1].X0) < 30 && b.Y0 - merged[^1].Y0 is > 0 and < 65)
        {
            var last = merged[^1];
            merged[^1] = last with { T = last.T + b.T, X1 = Math.Max(last.X1, b.X1) };
        }
        else merged.Add(b);
    }
    var lines = new List<Box>();
    foreach (var b in merged)
    {
        // 折行合并：同列、纵向紧邻、上一行未收句；中间插入别的分栏框也要能找回去
        int target = -1;
        for (int k = lines.Count - 1; k >= 0 && b.Y0 - lines[k].Y0 < 60; k--)
        {
            var prev = lines[k];
            double dy = b.Y0 - prev.Y0;
            bool wrapped = prev.X1 > 1250
                || (prev.X1 - prev.X0 > 200 && !Regex.IsMatch(prev.T, @"[。！？；：]$"));
            if (prev.T.Length > 8 && wrapped && Math.Abs(b.X0 - prev.X0) < 45
                && dy is > 0 and < 48 && b.T.Length <= 48)
            { target = k; break; }
        }
        if (target >= 0)
        {
            lines[target] = lines[target] with { T = lines[target].T + b.T, X1 = b.X1 };
            continue;
        }
        lines.Add(b);
    }

    // 同一视觉行（y 差 < 22px）先聚成一行，行内按 x0 从左到右，
    // 避免左右分栏时右列文字被排到左列节点前面、父子颠倒。
    var rows = new List<List<Box>>();
    foreach (var b in lines.OrderBy(b => b.Y0).ThenBy(b => b.X0))
    {
        if (rows.Count > 0 && b.Y0 - rows[^1][0].Y0 < 35) rows[^1].Add(b);
        else rows.Add(new List<Box> { b });
    }
    var ordered = new List<Box>();
    foreach (var row in rows)
    {
        row.Sort((a, b) => a.X0.CompareTo(b.X0));
        ordered.AddRange(row);
    }
    return ordered;
}

static bool IsNoise(Box b)
{
    string t = b.T.Trim();
    if (t.Length == 0) return true;
    if (b.Y0 < 230 || (b.Y0 > 2180 && t.Length <= 4)) return true;
    if (t.Contains("超格教育") || t.Contains("抖音") || t.Contains("听预测课")
        || t.Contains("思维导图大盘点")) return true;
    if (t.Length <= 3 && t.All(char.IsDigit)) return true;
    if (Regex.IsMatch(t, @"^[0-9A-Za-z丨/／\|]+$") && t.Length <= 4) return true;
    return false;
}

static bool IsSingleCjk(string t) => t.Length == 1 && t[0] >= 0x4e00 && t[0] <= 0x9fff;

static string Fix(string t)
{
    foreach (var (a, b) in Fixes.Pairs) t = t.Replace(a, b);
    return Regex.Replace(t, @"[ \u3000]{2,}", " ").Trim();
}

static void WriteTree(StringBuilder sb, Node n, int depth)
{
    sb.Append(' ', depth * 2).Append("- ").Append(n.Name.Replace("\n", " ")).Append('\n');
    foreach (var c in n.Children) WriteTree(sb, c, depth + 1);
}

/// <summary>Mermaid 视图：限深、限宽、清洗易破坏解析的半角符号。</summary>
static void WriteMermaid(StringBuilder sb, Node n, int depth, int level)
{
    if (level > 3 || depth > 9) return;
    sb.Append(' ', depth * 2).Append(MermaidText(n.Name)).Append('\n');
    int shown = 0;
    foreach (var c in n.Children)
    {
        if (shown++ >= 5) break;
        WriteMermaid(sb, c, depth + 1, level + 1);
    }
}

static string MermaidText(string s)
{
    s = Regex.Replace(s.Replace("\n", " ").Replace("\r", ""), @"\s{2,}", " ").Trim();
    s = s.Replace("(", "（").Replace(")", "）").Replace(":", "：").Replace(";", "；")
        .Replace("\"", "「").Replace("'", "「").Replace("[", "【").Replace("]", "】")
        .Replace("{", "（").Replace("}", "）").Replace("#", "№").Replace("|", "｜")
        .Replace("“", "「").Replace("”", "」").Replace("·", "・");
    if (s.Length > 34) s = s[..34] + "…";
    return s;
}

static int CountNodes(Node n) => 1 + n.Children.Sum(CountNodes);
static int Depth(Node n) => n.Children.Count == 0 ? 1 : 1 + n.Children.Max(Depth);

static class Fixes
{
    public static readonly (string, string)[] Pairs =
    {
        ("融人", "融入"), ("自已", "自己"), ("深人", "深入"), ("收人", "收入"),
        ("切人", "切入"), ("进人", "进入"), ("加人", "加入"),
        ("惩前后", "惩前毖后"), ("一一", "——"), ("柱干", "骨干"),
    };
}

sealed class Node
{
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("page")] public int? Page { get; set; }
    [JsonPropertyName("x")] public double? X { get; set; }
    [JsonPropertyName("y")] public double? Y { get; set; }
    [JsonPropertyName("children")] public List<Node> Children { get; set; } = new();
}

sealed record Box(double Y0, double X0, double X1, string T);
