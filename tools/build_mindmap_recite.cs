#!/usr/bin/env dotnet
// 把《政治理论思维导图大盘点》OCR 结果灌进 App 的背诵资产
// =================================================================
// 输入：tmp/mindmap/ocr_all.json（RapidOCR 逐页识别，带坐标）
// 输出：
//   · tmp/mindmap/政治理论思维导图大盘点-OCR.md   整理后的识别文本（便于人工核对）
//   · app/src/main/assets/recite_politics.json    追加一批 mmXX 分类 / 卡片
//
// 用法：
//     dotnet run tools/build_mindmap_recite.cs
//     dotnet run tools/build_mindmap_recite.cs -- --dry-run        # 只出 md + 统计，不改资产
//     dotnet run tools/build_mindmap_recite.cs -- --asset <path> --src <path>
//
// 规则：
//   · 过滤页眉页脚/水印；合并折行与竖排单字；按视觉间距分块，每 6 行切一张卡
//   · 每张卡最多挖 2 个中文引号短语作 ____（answers 按顺序对应），其余为纯知识点卡
//   · 只动 mm 开头的分类与卡片，重跑幂等；原有 cg / 精编考点顺序完全不变
//   · 新分类、新卡片一律追加到数组末尾，避免打乱用户已存的背诵进度

#:property PublishAot=false

using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

Console.OutputEncoding = Encoding.UTF8;

const string GROUP = "思维导图大盘点";
const string SOURCE_NAME = "政治理论思维导图大盘点";

// PDF 页 -> 书页主题（分类名）。书页号 = PDF 页 - 2（PDF 前 3 页无内容）。
var TOPICS = new Dictionary<int, string>
{
    [4] = "五中全会3-4 · 党性与从严治吏",
    [5] = "五中全会5-6 · 组织建设与反腐",
    [6] = "习近平党建思想",
    [7] = "党的建设1 · 政治/思想/制度",
    [8] = "党的建设2 · 组织/作风/纪律/反腐",
    [9] = "党的建设3-4 · 自我革命",
    [10] = "党的建设5 · 正确政绩观",
    [11] = "两个结合 · 新思想科学体系",
    [12] = "社会主义现代化强国",
    [13] = "中国式现代化 · 四个时期",
    [14] = "改革开放与对外开放",
    [15] = "高质量发展",
    [16] = "中国特色金融发展之路",
    [17] = "现代金融体系",
    [18] = "实体经济与现代化产业体系",
    [19] = "扩大内需",
    [20] = "全过程人民民主",
    [21] = "全面依法治国",
    [22] = "教育强国 · 立德树人",
    [23] = "基础研究",
    [24] = "科学技术 · 奖励大会",
    [25] = "人工智能",
    [26] = "文化自信 · 文化发展道路",
    [27] = "民族宗教",
    [28] = "收入分配",
    [29] = "人口高质量发展 · 社会建设",
    [30] = "三农 · 乡村振兴",
    [31] = "三农 · 县乡村治理",
    [32] = "城市工作",
    [33] = "总体国家安全观",
    [34] = "平安中国 · 公共安全",
    [35] = "强军建设",
    [36] = "生态文明建设 · 四大转变",
    [37] = "全球治理倡议 · 大国关系",
    [38] = "建党105周年 · 伟大成就",
    [39] = "闽宁协作 · 义乌经验 · 六尺巷",
};

string src = "tmp/mindmap/ocr_all.json";
string assetPath = "app/src/main/assets/recite_politics.json";
string mdPath = "政治理论思维导图大盘点-识别文本.md";
bool dryRun = false;
for (int i = 0; i < args.Length; i++)
{
    string a = args[i];
    if (a == "--src" && i + 1 < args.Length) src = args[++i];
    else if (a == "--asset" && i + 1 < args.Length) assetPath = args[++i];
    else if (a == "--md" && i + 1 < args.Length) mdPath = args[++i];
    else if (a == "--dry-run") dryRun = true;
    else { Console.Error.WriteLine($"无法识别的参数：{a}"); return 2; }
}

if (!File.Exists(src)) { Console.Error.WriteLine($"找不到 OCR 结果：{src}"); return 2; }
if (!dryRun && !File.Exists(assetPath)) { Console.Error.WriteLine($"找不到资产文件：{assetPath}"); return 2; }

using var jd = JsonDocument.Parse(File.ReadAllText(src, Encoding.UTF8));
var pages = new List<(int Page, List<Box> Boxes)>();
foreach (var p in jd.RootElement.EnumerateArray())
{
    int page = p.GetProperty("page").GetInt32();
    var boxes = new List<Box>();
    foreach (var it in p.GetProperty("items").EnumerateArray())
        boxes.Add(new Box(
            it.GetProperty("y0").GetDouble(), it.GetProperty("x0").GetDouble(),
            it.GetProperty("x1").GetDouble(), it.GetProperty("t").GetString() ?? ""));
    pages.Add((page, boxes));
}

var newCats = new List<object?>();
var newCards = new List<object?>();
var md = new StringBuilder();
md.AppendLine($"# {SOURCE_NAME} · OCR 识别文本\n");
md.AppendLine("> 来源：`C:/Users/hiyan/Downloads/政治理论思维导图大盘点-11324586.pdf`（39 页扫描/矢量图片型）  ");
md.AppendLine("> 识别：RapidOCR 逐页中文识别，按视觉行合并折行，按间距分块；PDF 前 3 页内容流损坏，各渲染器均为空白，未产生文本。  ");
md.AppendLine("> 下方为未挖空的原始识别文本；导入 App 时会把引号短语替换为 ____ 作为背诵填空。\n");

int totalBlanks = 0;
var perPage = new List<string>();
foreach (var (page, boxes) in pages)
{
    if (boxes.Count == 0) continue;
    if (!TOPICS.TryGetValue(page, out var topic)) topic = $"书页 {page - 2:D2}";
    string catId = $"mm{page:D2}";
    newCats.Add(new Dictionary<string, object?>
    {
        ["id"] = catId,
        ["name"] = topic,
        ["group"] = GROUP,
    });

    var texts = BuildCards(boxes);
    md.AppendLine($"## 书页 {page - 2:D2} · {topic}（PDF 第 {page} 页）\n");
    for (int i = 0; i < texts.Count; i++)
    {
        string raw = texts[i];
        var (text, answers) = BlankOut(raw);
        totalBlanks += answers.Count;
        newCards.Add(new Dictionary<string, object?>
        {
            ["id"] = $"{catId}-{i + 1:D3}",
            ["cat"] = catId,
            ["text"] = text,
            ["answers"] = answers.Select(a => (object?)a).ToList(),
            ["note"] = "",
            ["source"] = $"{SOURCE_NAME} · 书页 {page - 2:D2}（PDF p{page}）",
        });
        md.AppendLine($"### {i + 1}\n");
        foreach (var ln in raw.Split('\n')) md.AppendLine(ln + "  ");
        md.AppendLine();
    }
    perPage.Add($"  书页 {page - 2:D2} {topic}: {texts.Count} 张");
}

File.WriteAllText(mdPath, md.ToString(), new UTF8Encoding(false));
Console.WriteLine($"识别文本已写入：{mdPath}");

// ---------------- 资产合并 ----------------
if (!dryRun)
{
    var asset = (Dictionary<string, object?>)JParse(File.ReadAllText(assetPath, Encoding.UTF8))!;
    var cats = AsList(Get(asset, "categories"))
        .Where(c => !PyStr(((Dictionary<string, object?>)c!)["id"]).StartsWith("mm", StringComparison.Ordinal))
        .ToList();
    var cards = AsList(Get(asset, "cards"))
        .Where(c => !PyStr(((Dictionary<string, object?>)c!)["cat"]).StartsWith("mm", StringComparison.Ordinal))
        .ToList();
    cats.AddRange(newCats);
    cards.AddRange(newCards);
    asset["categories"] = cats;
    asset["cards"] = cards;
    asset["title"] = "2027 政治理论考点背诵（超格手册 + 思维导图大盘点）";
    asset["updated"] = DateTime.Today.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
    asset["intro"] =
        "含三部分：① 精编政治理论考点；② 超格《【早自习】2027政治理论背诵手册礼包》「核心知识带背」20 节全套考点；"
        + "③《政治理论思维导图大盘点》书页 02–37 的 36 个主题。弹层按「超格 · 新思想」「超格 · 时政理论」"
        + $"「精编考点」「思维导图大盘点」分组，共 {cats.Count} 个分类 / {cards.Count} 张卡片"
        + $"（其中导图 {newCards.Count} 张 / {totalBlanks} 处填空）。";
    File.WriteAllText(assetPath, JDump(asset, 2), new UTF8Encoding(false));
    Console.WriteLine($"资产已更新：{assetPath}");
}

// ---------------- 自检 ----------------
var catIds = new HashSet<string>();
foreach (var c in newCats) catIds.Add(PyStr(((Dictionary<string, object?>)c!)["id"]));
var ids = new HashSet<string>();
int bad = 0;
foreach (var c in newCards)
{
    var d = (Dictionary<string, object?>)c!;
    string id = PyStr(d["id"]), cat = PyStr(d["cat"]), text = PyStr(d["text"]);
    var answers = AsList(d["answers"]);
    if (!ids.Add(id) || !catIds.Contains(cat) || CountOccurrences(text, "____") != answers.Count)
    {
        Console.Error.WriteLine($"[校验失败] {id}: cat={cat}, 空位={CountOccurrences(text, "____")}, 答案={answers.Count}");
        bad++;
    }
}
Console.WriteLine($"新增分类 {newCats.Count} 个 / 卡片 {newCards.Count} 张 / 填空 {totalBlanks} 处；校验失败 {bad} 张。");
foreach (var line in perPage) Console.WriteLine(line);
if (!dryRun)
{
    Console.WriteLine("--- 卡片预览 ---");
    for (int i = 0; i < Math.Min(2, newCards.Count); i++)
    {
        var d = (Dictionary<string, object?>)newCards[i]!;
        Console.WriteLine($"[{PyStr(d["id"])}] {PyStr(d["text"]).Replace("\n", " / ")[..Math.Min(150, PyStr(d["text"]).Length)]}");
    }
}
return bad > 0 ? 1 : 0;

// ---------------- helpers ----------------

/// <summary>过滤页眉页脚/水印，做错字修正，再合并竖排单字与折行，最后分块切卡。</summary>
static List<string> BuildCards(List<Box> boxes0)
{
    var boxes = boxes0.Where(b => !IsNoise(b))
        .Select(b => b with { T = Fix(b.T) })
        .Where(b => b.T.Length > 0)
        .OrderBy(b => b.Y0).ThenBy(b => b.X0)
        .ToList();
    if (boxes.Count == 0) return new List<string>();

    // 竖排单字（思维导图的「金」「融」「强」这类）合成一个词
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

    // 折行合并：上一行顶到右边界、下一行左缩进对齐且纵向紧邻
    var lines = new List<Box>();
    foreach (var b in merged)
    {
        if (lines.Count > 0)
        {
            var prev = lines[^1];
            if (prev.X1 > 1250 && prev.T.Length > 10 && Math.Abs(b.X0 - prev.X0) < 45
                && b.Y0 - prev.Y0 is > 0 and < 50)
            {
                lines[^1] = prev with { T = prev.T + b.T, X1 = b.X1 };
                continue;
            }
        }
        lines.Add(b);
    }
    lines = lines.OrderBy(b => b.Y0 / 12).ThenBy(b => b.X0).ToList();

    // 按纵向大空隙分块
    var blocks = new List<List<Box>>();
    foreach (var b in lines)
    {
        if (blocks.Count == 0 || b.Y0 - blocks[^1][^1].Y0 > 110) blocks.Add(new List<Box>());
        blocks[^1].Add(b);
    }

    // 每块按最多 6 行切卡；过短的尾部并入上一张
    var result = new List<string>();
    foreach (var block in blocks)
    {
        for (int i = 0; i < block.Count; i += 6)
        {
            var chunk = block.Skip(i).Take(6).Select(b => b.T).ToList();
            // 合并后可能重新拼出竖排单字误识（「收」+「人」=「收人」），再修一次
            string text = Fix(string.Join("\n", chunk).Trim());
            if (text.Length == 0) continue;
            if (result.Count > 0 && (chunk.Count == 1 && text.Length < 12 || text.Length < 8))
                result[^1] = result[^1] + "\n" + text;
            else result.Add(text);
        }
    }
    return result;
}

static bool IsNoise(Box b)
{
    string t = b.T.Trim();
    if (t.Length == 0) return true;
    if (b.Y0 < 230) return true;                             // 页眉 / 水印
    if (b.Y0 > 2180 && t.Length <= 4) return true;            // 页脚页码
    if (t.Contains("超格教育") || t.Contains("抖音") || t.Contains("听预测课")
        || t.Contains("思维导图大盘点")) return true;
    if (t.Length <= 3 && t.All(char.IsDigit)) return true;
    if (Regex.IsMatch(t, @"^[0-9A-Za-z丨/／\|]+$") && t.Length <= 4) return true;
    return false;
}

static bool IsSingleCjk(string t) => t.Length == 1 && t[0] >= 0x4e00 && t[0] <= 0x9fff;

/// <summary>常见 OCR 错字修正 + 压缩多余空格。</summary>
static string Fix(string t)
{
    foreach (var (a, b) in Fixes.Pairs) t = t.Replace(a, b);
    return Regex.Replace(t, @"[ \u3000]{2,}", " ").Trim();
}

/// <summary>把最多 2 个中文引号短语换成 ____，答案按出现顺序入 answers。</summary>
static (string Text, List<string> Answers) BlankOut(string text)
{
    var answers = new List<string>();
    string outp = Regex.Replace(text, "[“\"]([^”\"]{2,12})[”\"]", m =>
    {
        if (answers.Count >= 2) return m.Value;
        answers.Add(m.Groups[1].Value);
        return "____";
    });
    return (outp, answers);
}

static int CountOccurrences(string s, string sub)
{
    int n = 0, i = 0;
    while ((i = s.IndexOf(sub, i, StringComparison.Ordinal)) >= 0) { n++; i += sub.Length; }
    return n;
}

static object? Get(Dictionary<string, object?> d, string key) =>
    d.TryGetValue(key, out var v) ? v : null;

static List<object?> AsList(object? v) => v as List<object?> ?? new List<object?>();

static string PyStr(object? v) => v switch
{
    null => "",
    string s => s,
    JNum n => n.Raw,
    bool b => b ? "True" : "False",
    _ => v.ToString() ?? "",
};

// ---- 极简 JSON：读用 JsonDocument，写用手工序列化（保持原资产 indent=2 / 中文不转义） ----

static object? JParse(string text)
{
    using var doc = JsonDocument.Parse(text);
    return FromElement(doc.RootElement);
}

static object? FromElement(JsonElement e)
{
    switch (e.ValueKind)
    {
        case JsonValueKind.Object:
            var d = new Dictionary<string, object?>();
            foreach (var p in e.EnumerateObject()) d[p.Name] = FromElement(p.Value);
            return d;
        case JsonValueKind.Array:
            var l = new List<object?>();
            foreach (var x in e.EnumerateArray()) l.Add(FromElement(x));
            return l;
        case JsonValueKind.String: return e.GetString();
        case JsonValueKind.Number: return new JNum(e.GetRawText());
        case JsonValueKind.True: return true;
        case JsonValueKind.False: return false;
        default: return null;
    }
}

static string JDump(object? v, int indent)
{
    var sb = new StringBuilder();
    Dump(sb, v, indent, 0);
    return sb.ToString();
}

static void Dump(StringBuilder sb, object? v, int indent, int level)
{
    switch (v)
    {
        case null: sb.Append("null"); return;
        case bool b: sb.Append(b ? "true" : "false"); return;
        case string s: JStr(sb, s); return;
        case JNum n: sb.Append(n.Raw); return;
        case int i: sb.Append(i.ToString(CultureInfo.InvariantCulture)); return;
        case List<object?> arr:
            if (arr.Count == 0) { sb.Append("[]"); return; }
            sb.Append("[\n");
            for (int k = 0; k < arr.Count; k++)
            {
                sb.Append(' ', indent * (level + 1));
                Dump(sb, arr[k], indent, level + 1);
                if (k < arr.Count - 1) sb.Append(',');
                sb.Append('\n');
            }
            sb.Append(' ', indent * level).Append(']');
            return;
        case Dictionary<string, object?> obj:
            if (obj.Count == 0) { sb.Append("{}"); return; }
            sb.Append("{\n");
            int j = 0;
            foreach (var kv in obj)
            {
                sb.Append(' ', indent * (level + 1));
                JStr(sb, kv.Key);
                sb.Append(": ");
                Dump(sb, kv.Value, indent, level + 1);
                if (j < obj.Count - 1) sb.Append(',');
                sb.Append('\n');
                j++;
            }
            sb.Append(' ', indent * level).Append('}');
            return;
        default: JStr(sb, v.ToString() ?? ""); return;
    }
}

static void JStr(StringBuilder sb, string s)
{
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
                if (c < 0x20) sb.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));
                else sb.Append(c);
                break;
        }
    }
    sb.Append('"');
}

static class Fixes
{
    public static readonly (string, string)[] Pairs =
    {
        ("融人", "融入"), ("自已", "自己"), ("深人", "深入"), ("收人", "收入"),
        ("切人", "切入"), ("进人", "进入"), ("加人", "加入"),
        ("惩前后", "惩前毖后"), ("一一", "——"), ("柱干", "骨干"),
    };
}

sealed class JNum
{
    public string Raw { get; }
    public JNum(string raw) { Raw = raw; }
}

sealed record Box(double Y0, double X0, double X1, string T);
