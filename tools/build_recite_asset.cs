#!/usr/bin/env dotnet
// 把「超格·【早自习】2027政治理论背诵手册礼包 / 核心知识带背」抓取结果
// 整理成 App 的考点背诵资产
// =================================================================
// 读取 course_content/ 下按课程分文件的 .txt（形如 `N. 内容`，记忆关键词在词两侧
// 用 2 个以上空格包住），转换成 ReciteRepository 需要的
// `{id, cat, text, answers[], note, source}` 结构，并合并进
// app/src/main/assets/recite_politics.json（保留原有精编卡片，不覆盖）。
//
// 用法：
//     dotnet run tools/build_recite_asset.cs -- \
//         --src tmp/recite_early/course_content \
//         --asset app/src/main/assets/recite_politics.json
//
// 转换规则：
//   · 每张卡片 = txt 里以空行分隔的一段，首行带 “N. ”；卡片内部还有一层 “N.”
//   · 记忆关键词 = 被 2+ 个空格（含全角空格）夹住的一段 → 换成 `____`，原文进 answers
//   · 空行/空段丢弃；text 中 `____` 数量必须与 answers 数量一致

#:property PublishAot=false

using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

Console.OutputEncoding = System.Text.Encoding.UTF8;

// 课程顺序（与超格「核心知识带背」20 节一致）
string[] LESSONS =
{
    "政治理论考情介绍+新思想导论",
    "新思想第一、二节",
    "新思想第三、四、五节",
    "新思想第六节",
    "新思想第七、八节",
    "新思想第九、十节",
    "新思想第十一、十二节",
    "新思想第十三、十四、十五节",
    "新思想第十六、十七节",
    "时政理论-谋划“十五五”1",
    "时政理论-谋划“十五五”2",
    "时政理论-谋划“十五五”3",
    "时政理论-系列讲话、指示1",
    "时政理论-系列讲话、指示2",
    "时政理论-系列讲话、指示3+重要会议",
    "时政理论-重要文件1",
    "时政理论-重要文件2",
    "时政理论-重要文件3",
    "时政理论-重要文件4",
    "时政理论-重要文件5",
};

// 分组（弹层里作为二级标题展示）：(分组名, 该组分类简称列表)
// 分类名用简称，来源信息由分组标题承载，避免「超格·新思想新思想第一、二节」这类重复。
var LESSON_GROUPS = new List<(string Group, List<string> Names)>
{
    ("超格 · 新思想", new List<string>
    {
        "考情介绍 + 新思想导论",
        "第一、二节",
        "第三、四、五节",
        "第六节",
        "第七、八节",
        "第九、十节",
        "第十一、十二节",
        "第十三、十四、十五节",
        "第十六、十七节",
    }),
    ("超格 · 时政理论", new List<string>
    {
        "谋划“十五五”1",
        "谋划“十五五”2",
        "谋划“十五五”3",
        "系列讲话、指示1",
        "系列讲话、指示2",
        "系列讲话、指示3+重要会议",
        "重要文件1",
        "重要文件2",
        "重要文件3",
        "重要文件4",
        "重要文件5",
    }),
};

// 非超格来源（原有精编考点）归入这个分组
const string DEFAULT_GROUP = "精编考点";

// ---------- 参数（对应 argparse） ----------
string src = "tmp/recite_early/course_content";
string assetPath = "app/src/main/assets/recite_politics.json";
bool replace = false;
bool refreshCg = false;
string prefix = "";
for (int i = 0; i < args.Length; i++)
{
    string a = args[i];
    if (a == "--src" && i + 1 < args.Length) src = args[++i];
    else if (a.StartsWith("--src=", StringComparison.Ordinal)) src = a["--src=".Length..];
    else if (a == "--asset" && i + 1 < args.Length) assetPath = args[++i];
    else if (a.StartsWith("--asset=", StringComparison.Ordinal)) assetPath = a["--asset=".Length..];
    else if (a == "--prefix" && i + 1 < args.Length) prefix = args[++i];
    else if (a.StartsWith("--prefix=", StringComparison.Ordinal)) prefix = a["--prefix=".Length..];
    else if (a == "--replace") replace = true;
    else if (a == "--refresh-cg") refreshCg = true;
    else
    {
        Console.Error.WriteLine($"无法识别的参数：{a}");
        return 2;
    }
}

if (!Directory.Exists(src))
{
    Console.Error.WriteLine($"src 目录不存在: {src}");
    return 2;
}
if (!File.Exists(assetPath))
{
    Console.Error.WriteLine($"找不到资产文件：{assetPath}");
    return 2;
}

var asset = (Dictionary<string, object?>)JParse(File.ReadAllText(assetPath, Encoding.UTF8))!;

var existingCats = AsList(Get(asset, "categories"));
if (refreshCg)
{
    var drop = new HashSet<string>();
    foreach (var c in existingCats)
    {
        string id = PyStr(((Dictionary<string, object?>)c!)["id"]);
        if (id.StartsWith("cg", StringComparison.Ordinal)) drop.Add(id);
    }
    existingCats = existingCats
        .Where(c => !drop.Contains(PyStr(((Dictionary<string, object?>)c!)["id"])))
        .ToList();
    var cards0 = AsList(Get(asset, "cards"));
    asset["cards"] = cards0.Where(c =>
    {
        var d = (Dictionary<string, object?>)c!;
        return !d.TryGetValue("cat", out var cat) || !drop.Contains(PyStr(cat));
    }).ToList();
    Console.WriteLine($"[refresh-cg] 移除旧分类 {drop.Count} 个");
}

var curCards = AsList(Get(asset, "cards"));
var existingCards = replace ? new List<object?>() : new List<object?>(curCards);
var usedIds = new HashSet<string>();
foreach (var c in curCards) usedIds.Add(PyStr(((Dictionary<string, object?>)c!)["id"]));

// 老数据缺 group 字段时补默认分组，保证弹层能正常分组
foreach (var c in existingCats)
{
    var d = (Dictionary<string, object?>)c!;
    if (!d.ContainsKey("group")) d["group"] = DEFAULT_GROUP;   // setdefault
    if (IsFalsy(d["group"])) d["group"] = DEFAULT_GROUP;       // or DEFAULT_GROUP
}

var lessonMeta = new List<(string Group, string Short)>();
foreach (var (g, names) in LESSON_GROUPS)
    foreach (var n in names) lessonMeta.Add((g, n));
if (lessonMeta.Count != LESSONS.Length)
{
    Console.Error.WriteLine($"LESSON_GROUPS 与 LESSONS 数量不一致: {lessonMeta.Count} vs {LESSONS.Length}");
    return 2;
}

var existingCatIds = new HashSet<string>();
foreach (var c in existingCats) existingCatIds.Add(PyStr(((Dictionary<string, object?>)c!)["id"]));

var newCats = new List<object?>();
var newCards = new List<object?>();
var report = new List<(string Lesson, int N, int Zero)>();
int totalBlankCards = 0;

for (int idx = 1; idx <= LESSONS.Length; idx++)
{
    string lesson = LESSONS[idx - 1];
    var (group, shortName) = lessonMeta[idx - 1];
    string path = Path.Combine(src, lesson + ".txt");
    if (!File.Exists(path))
    {
        Console.WriteLine($"[warn] 缺少文件: {path}");
        continue;
    }
    string catId = $"cg{idx:D2}";
    while (existingCatIds.Contains(catId)) catId += "x";
    newCats.Add(new Dictionary<string, object?>
    {
        ["id"] = catId,
        ["name"] = prefix + shortName,
        ["group"] = group,
    });

    var cards = SplitCards(File.ReadAllText(path, Encoding.UTF8));
    int zero = 0;
    for (int seq = 1; seq <= cards.Count; seq++)
    {
        var (text, answers) = ConvertCard(cards[seq - 1]);
        if (text.Length == 0) continue;
        if (CountOccurrences(text, "____") != answers.Count)
        {
            Console.WriteLine($"[warn] {lesson} #{seq} 空位数与答案数不一致，跳过");
            continue;
        }
        if (answers.Count == 0) { zero++; totalBlankCards++; }
        string cid = $"{catId}-{seq:D3}";
        while (usedIds.Contains(cid)) cid += "x";
        usedIds.Add(cid);
        newCards.Add(new Dictionary<string, object?>
        {
            ["id"] = cid,
            ["cat"] = catId,
            ["text"] = text,
            ["answers"] = answers.Select(a => (object?)a).ToList(),
            ["note"] = "",
            ["source"] = "超格 · " + lesson,
        });
    }
    report.Add((lesson, cards.Count, zero));
}

var allCats = new List<object?>(newCats);
allCats.AddRange(existingCats);
var allCards = new List<object?>(newCards);
allCards.AddRange(existingCards);
asset["categories"] = allCats;
asset["cards"] = allCards;
asset["updated"] = DateTime.Today.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
asset["title"] = "2027 政治理论考点背诵（含超格背诵手册）";
asset["intro"] =
    "含两部分：① 精编政治理论考点；② 超格《【早自习】2027政治理论背诵手册礼包》"
    + $"「核心知识带背」{LESSONS.Length} 节全套考点。弹层按「超格 · 新思想」「超格 · 时政理论」"
    + $"「精编考点」分组，共 {allCats.Count} 个分类 / {allCards.Count} 张卡片。";

File.WriteAllText(assetPath, JDump(asset, 2), new UTF8Encoding(false));

Console.WriteLine(Environment.NewLine + $"新增分类 {newCats.Count} 个，新增卡片 {newCards.Count} 张；"
                  + $"资产合计 {allCats.Count} 分类 / {allCards.Count} 卡片。");
Console.WriteLine($"其中无填空卡片 {totalBlankCards} 张。" + Environment.NewLine);
foreach (var (lesson, n, zero) in report)
{
    string flag = zero > 0 ? $" (无填空 {zero})" : "";
    Console.WriteLine($"  {lesson}: {n} 张{flag}");
}
return 0;

// ---------------- helpers ----------------

/// <summary>txt 按空行分段：先统一换行、去首尾空白，再按正则拆段并丢弃空段。</summary>
static List<string> SplitCards(string txt)
{
    txt = txt.Replace("\r\n", "\n").Trim();
    var parts = Regex.Split(txt, @"\n\s*\n");
    var res = new List<string>();
    foreach (var c in parts)
    {
        string s = c.Trim();
        if (s.Length > 0) res.Add(s);
    }
    return res;
}

/// <summary>
/// 被 2+ 个空格（含全角空格）夹住的一段换成 ____，原文进 answers。
/// 整行只剩一个空位时说明是缩进列表项而非填空，撤销假答案并还原原文。
/// </summary>
static string ConvertLine(string line, List<string> answers)
{
    var parts = Regex.Split(line, @"[ \u3000]{2,}");
    var buf = new StringBuilder();
    for (int i = 0; i < parts.Length; i++)
    {
        if (i % 2 == 0) buf.Append(parts[i]);
        else
        {
            string term = parts[i].Trim();
            if (term.Length > 0) { buf.Append("____"); answers.Add(term); }
        }
    }
    string outp = buf.ToString();
    if (outp.Trim() == "____")
    {
        if (answers.Count > 0) answers.RemoveAt(answers.Count - 1);
        return line.Trim();
    }
    return outp;
}

/// <summary>去掉外层编号与卡片自身编号，再逐行转换并拼成 text。</summary>
static (string Text, List<string> Answers) ConvertCard(string raw)
{
    string s = new Regex(@"^\d+\.").Replace(raw, "", 1);
    s = new Regex(@"^\s").Replace(s, "", 1);
    s = new Regex(@"^\d+\.").Replace(s, "", 1);
    var answers = new List<string>();
    var lines = new List<string>();
    foreach (var ln in s.Split('\n')) lines.Add(ConvertLine(ln, answers).Trim());
    string text = string.Join("\n", lines.Where(l => l.Length > 0)).Trim();
    return (text, answers);
}

/// <summary>Python str.count：统计非重叠子串出现次数。</summary>
static int CountOccurrences(string s, string sub)
{
    int n = 0, i = 0;
    while ((i = s.IndexOf(sub, i, StringComparison.Ordinal)) >= 0)
    {
        n++;
        i += sub.Length;
    }
    return n;
}

static object? Get(Dictionary<string, object?> d, string key) =>
    d.TryGetValue(key, out var v) ? v : null;

static List<object?> AsList(object? v) => v as List<object?> ?? new List<object?>();

static string PyStr(object? v) => v switch
{
    null => "None",
    string s => s,
    JNum n => n.Raw,
    bool b => b ? "True" : "False",
    _ => v.ToString() ?? "",
};

static bool IsFalsy(object? v) => v switch
{
    null => true,
    string s => s.Length == 0,
    bool b => !b,
    JNum n => n.D == 0,
    _ => false,
};

// ---- 极简 JSON：读用 JsonDocument，写用手工序列化（对齐 json.dump 的 indent / ensure_ascii=False） ----

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
        case double d: sb.Append(d.ToString("R", CultureInfo.InvariantCulture)); return;
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
            sb.Append(' ', indent * level);
            sb.Append(']');
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
            sb.Append(' ', indent * level);
            sb.Append('}');
            return;
        default: JStr(sb, v.ToString() ?? ""); return;
    }
}

/// <summary>Python json.dumps(ensure_ascii=False) 的字符串转义。</summary>
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

/// <summary>保留数字原始文本，避免写回时把 123.0 变成 123 这类格式漂移。</summary>
sealed class JNum
{
    public string Raw { get; }
    public JNum(string raw) { Raw = raw; }
    public double D => double.Parse(Raw, CultureInfo.InvariantCulture);
}
