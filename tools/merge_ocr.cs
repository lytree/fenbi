#!/usr/bin/env dotnet
// 把多套参数的 OCR 结果合并成一份：按页取并集，坐标归一化到 220dpi 基准。
//
// - 220dpi（ocr_a..e）：敏感参数，负责行全
// - 300dpi（ocr_part0）：补漏
// - 260dpi（ocr_f..i）：第三次交叉验证，补零星漏行
// 同一行多套结果按「文本聚类投票 + 取最长」合并。
//
// 用法：dotnet run tools/merge_ocr.cs

#:property PublishAot=false

using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

Console.OutputEncoding = System.Text.Encoding.UTF8;

const string BASE = "F:/Code/Android/fenbi/tmp/zhengzhi";
const string OUT = BASE + "/ocr_merged.json";

var DPI_BY_NAME = new Dictionary<string, int> { ["ocr_part0.json"] = 300 };
foreach (char c in "abcde") DPI_BY_NAME["ocr_" + c + ".json"] = 220;
foreach (char c in "fghi") DPI_BY_NAME["ocr_" + c + ".json"] = 260;

const int ROW_TOL = 14;   // 220dpi 下同一行的 y 容差（行距约 65）
const double SIM = 0.72;  // 同一行不同来源文本的相似度阈值

if (!Directory.Exists(BASE))
{
    Console.Error.WriteLine($"找不到 OCR 目录：{BASE}");
    return 2;
}

var perPage = Load(BASE, DPI_BY_NAME);
var outDict = new Dictionary<string, object?>();

foreach (var (pg, lines0) in perPage)
{
    var lines = lines0
        .OrderBy(d => AsDouble(d["y"]))
        .ThenBy(d => AsDouble(d["x"]))
        .ToList();
    var clusters = new List<List<Dictionary<string, object?>>>();
    foreach (var ln in lines)
    {
        List<Dictionary<string, object?>>? hit = null;
        foreach (var c in clusters)
        {
            if (SameLine(c[0], ln)) { hit = c; break; }
        }
        if (hit == null) clusters.Add(new List<Dictionary<string, object?>> { ln });
        else hit.Add(ln);
    }
    var rows = clusters.Select(MergeCluster).ToList();
    rows = rows.OrderBy(d => AsDouble(d["y"])).ThenBy(d => AsDouble(d["x"])).ToList();

    var outRows = new List<object?>();
    foreach (var r in rows)
    {
        outRows.Add(new Dictionary<string, object?>
        {
            ["y"] = new JNum(Round1(AsDouble(r["y"]))),
            ["x"] = new JNum(Round1(AsDouble(r["x"]))),
            ["x2"] = new JNum(Round1(AsDouble(r["x2"]))),
            ["text"] = r["text"],
            ["score"] = r["score"],
            ["src"] = r["src"],
            ["votes"] = r.TryGetValue("votes", out var v) ? v : new JNum("1"),
        });
    }
    outDict[pg] = outRows;
}

// 人工视觉核对补录的漏检行（e 原样 append，不经过上面的 round / 字段重建）
string fixPath = BASE + "/ocr_fixups.json";
int nFix = 0;
if (File.Exists(fixPath))
{
    var fixups = (Dictionary<string, object?>)JParse(File.ReadAllText(fixPath, Encoding.UTF8))!;
    foreach (var kv in fixups)
    {
        string pg = kv.Key;
        if (pg.StartsWith("_", StringComparison.Ordinal)) continue;
        if (!outDict.TryGetValue(pg, out var val) || val is not List<object?> rows2)
        {
            rows2 = new List<object?>();
            outDict[pg] = rows2;
        }
        foreach (var e0 in AsList(kv.Value))
        {
            var e = (Dictionary<string, object?>)e0!;
            bool dup = rows2.Any(r =>
                Math.Abs(AsDouble(((Dictionary<string, object?>)r!)["y"]) - AsDouble(e["y"])) < 14);
            if (dup) continue;
            rows2.Add(e);
            nFix++;
        }
        outDict[pg] = rows2
            .OrderBy(r => AsDouble(((Dictionary<string, object?>)r!)["y"]))
            .ThenBy(r => AsDouble(((Dictionary<string, object?>)r!)["x"]))
            .ToList();
    }
}

File.WriteAllText(OUT, JDump(outDict, 1), new UTF8Encoding(false));
Console.WriteLine($"pages {outDict.Count} lines {outDict.Values.Sum(v => ((List<object?>)v!).Count)}");
int single = outDict.Values.Sum(v => ((List<object?>)v!).Count(r =>
{
    var d = (Dictionary<string, object?>)r!;
    return d.TryGetValue("votes", out var vt) && AsDouble(vt) == 1;
}));
Console.WriteLine($"仅一套参数命中的行: {single} | 补录行: {nFix}");
return 0;

// ---------------- helpers ----------------

/// <summary>norm：去掉空白、点、顿号、冒号、逗号（含全角）。</summary>
static string Norm(string t) => Regex.Replace(t, @"[\s.．、:：,，]", "");

/// <summary>
/// 逐文件读取并按 220dpi 基准缩放坐标；文件名不在 DPI 表里的跳过。
/// sorted(glob(...)) 用序数排序对齐 Python 的 Unicode 码点排序。
/// </summary>
static Dictionary<string, List<Dictionary<string, object?>>> Load(
    string baseDir, Dictionary<string, int> dpiByName)
{
    var perPage = new Dictionary<string, List<Dictionary<string, object?>>>();
    var paths = Directory.GetFiles(baseDir, "ocr_*.json")
                         .OrderBy(p => p, StringComparer.Ordinal)
                         .ToList();
    foreach (var path in paths)
    {
        string name = Path.GetFileName(path);   // re.split(r"[\\/]", path)[-1]
        if (!dpiByName.TryGetValue(name, out int dpi) || dpi == 0) continue;
        double scale = 220.0 / dpi;
        var doc = (Dictionary<string, object?>)JParse(File.ReadAllText(path, Encoding.UTF8))!;
        foreach (var kv in doc)
        {
            if (!perPage.TryGetValue(kv.Key, out var bucket))
            {
                bucket = new List<Dictionary<string, object?>>();
                perPage[kv.Key] = bucket;
            }
            foreach (var ln0 in AsList(kv.Value))
            {
                var ln = (Dictionary<string, object?>)ln0!;
                bucket.Add(new Dictionary<string, object?>
                {
                    ["y"] = AsDouble(ln["y"]) * scale,
                    ["x"] = AsDouble(ln["x"]) * scale,
                    ["x2"] = AsDouble(ln["x2"]) * scale,
                    ["text"] = ln["text"],
                    ["score"] = ln["score"],
                    ["src"] = new JNum(dpi.ToString(CultureInfo.InvariantCulture)),
                });
            }
        }
    }
    return perPage;
}

/// <summary>t[:1] in 圈号；空串在 Python 里 "" in s 恒为真，C# Contains("") 亦然。</summary>
static bool IsCircleLike(string t)
{
    const string CIRCLE = "①②③④⑤⑥⑦⑧";
    return CIRCLE.Contains(t.Length == 0 ? "" : t[..1]);
}

static bool IsLetterOpt(string t) => Regex.IsMatch(t, @"^[A-D]\s*[.．、]");

static bool IsOptLike(string t) => IsLetterOpt(t) || IsCircleLike(t);

static bool SameLine(Dictionary<string, object?> a, Dictionary<string, object?> b)
{
    if (Math.Abs(AsDouble(a["y"]) - AsDouble(b["y"])) > ROW_TOL) return false;
    double lo = Math.Max(AsDouble(a["x"]), AsDouble(b["x"]));
    double hi = Math.Min(AsDouble(a["x2"]), AsDouble(b["x2"]));
    if (hi - lo > 0) return true;
    // 同一行被切成左右两段：间隙不大且不是「横排选项」那种并排结构
    double gap = -hi;   // 原脚本即为 gap = -hi（疑似笔误），忠实保留未擅改
    if (gap < 220 && !(IsLetterOpt(PyStr(a["text"])) && IsLetterOpt(PyStr(b["text"])))) return true;
    return false;
}

/// <summary>拼接同一行被切开的左右两段，去掉重叠部分。</summary>
static string JoinText(string left, string right)
{
    string a = left.TrimEnd();
    string b = right.TrimStart();
    string na = Norm(a), nb = Norm(b);
    if (nb.Length > 0 && na.Contains(nb)) return a;   // 右段已在左段里（重复识别）
    int best = 0;
    for (int k = Math.Min(a.Length, b.Length); k > 0; k--)
    {
        if (a.Substring(a.Length - k) == b.Substring(0, k)) { best = k; break; }
    }
    if (best > 0) return a + b.Substring(best);
    if ((a.Length > 0 && "，。；：、！？”》".Contains(a[^1])) || a.Length == 0) return a + b;
    if (b.Length <= 20) return a + "——" + b;
    return a + b;
}

/// <summary>{**r, "y": y, "votes": r["n"]}。</summary>
static Dictionary<string, object?> WithYVotes(Dictionary<string, object?> r, double y, object? votes)
{
    var d = new Dictionary<string, object?>(r) { ["y"] = y, ["votes"] = votes };
    return d;
}

/// <summary>
/// 横排陈述 / 多段拼接分支的返回：x 取首段、x2 取末段、score 取最大、votes 取最小。
/// </summary>
static Dictionary<string, object?> JoinedRep(
    List<Dictionary<string, object?>> reps, string text, double y)
{
    double maxScore = double.NegativeInfinity;
    object? scoreVal = null;
    foreach (var r in reps)
    {
        double s = AsDouble(r["score"]);
        if (s > maxScore) { maxScore = s; scoreVal = r["score"]; }
    }
    double minN = double.PositiveInfinity;
    object? nVal = null;
    foreach (var r in reps)
    {
        double n = AsDouble(r["n"]);
        if (n < minN) { minN = n; nVal = r["n"]; }
    }
    return new Dictionary<string, object?>
    {
        ["x"] = reps[0]["x"],
        ["x2"] = reps[reps.Count - 1]["x2"],
        ["text"] = text,
        ["y"] = y,
        ["score"] = scoreVal,
        ["src"] = reps[0]["src"],
        ["votes"] = nVal,
    };
}

/// <summary>多套结果聚成一组；同一行被切成几段时按 x 顺序拼接。</summary>
static Dictionary<string, object?> MergeCluster(List<Dictionary<string, object?>> cluster)
{
    var groups = new List<(string Norm, List<Dictionary<string, object?>> Items)>();
    foreach (var it in cluster)
    {
        string t = Norm(PyStr(it["text"]));
        bool found = false;
        for (int gi = 0; gi < groups.Count; gi++)
        {
            var g = groups[gi];
            if (SeqRatio(t, g.Norm) >= SIM || g.Norm.Contains(t) || t.Contains(g.Norm))
            {
                g.Items.Add(it);
                groups[gi] = (t.Length > g.Norm.Length ? t : g.Norm, g.Items);
                found = true;
                break;
            }
        }
        if (!found) groups.Add((t, new List<Dictionary<string, object?>> { it }));
    }

    // 每组挑一个代表
    var reps = new List<Dictionary<string, object?>>();
    foreach (var (_, items) in groups)
    {
        // best = max(items, key=(len(norm(text)), score))，平局保留第一个
        var best = items[0];
        double bestLen = Norm(PyStr(best["text"])).Length;
        double bestScore = AsDouble(best["score"]);
        for (int k = 1; k < items.Count; k++)
        {
            var it = items[k];
            double l = Norm(PyStr(it["text"])).Length;
            double s = AsDouble(it["score"]);
            if (l > bestLen || (l == bestLen && s > bestScore))
            {
                best = it; bestLen = l; bestScore = s;
            }
        }
        // score = max(i["score"] for i in items)
        double maxScore = double.NegativeInfinity;
        object? scoreVal = null;
        foreach (var it in items)
        {
            double s = AsDouble(it["score"]);
            if (s > maxScore) { maxScore = s; scoreVal = it["score"]; }
        }
        reps.Add(new Dictionary<string, object?>
        {
            ["x"] = best["x"],
            ["x2"] = best["x2"],
            ["text"] = best["text"],
            ["score"] = scoreVal,
            ["src"] = best["src"],
            ["n"] = items.Count,
        });
    }
    reps = reps.OrderBy(r => AsDouble(r["x"])).ToList();
    var ys = cluster.Select(i => AsDouble(i["y"])).OrderBy(v => v).ToList();
    double y = ys[cluster.Count / 2];

    if (reps.Count == 1)
        return WithYVotes(reps[0], y, reps[0]["n"]);

    int optlike = reps.Count(r => IsOptLike(PyStr(r["text"])));
    int letters = reps.Count(r => IsLetterOpt(PyStr(r["text"])));
    if (letters >= 2)   // 横排选项 A/B/C/D，各段独立
    {
        // r = max(reps, key=(n, len(norm(text))))，平局保留第一个
        var r = reps[0];
        double rn = AsDouble(r["n"]), rl = Norm(PyStr(r["text"])).Length;
        for (int k = 1; k < reps.Count; k++)
        {
            var rr = reps[k];
            double n2 = AsDouble(rr["n"]), l2 = Norm(PyStr(rr["text"])).Length;
            if (n2 > rn || (n2 == rn && l2 > rl)) { r = rr; rn = n2; rl = l2; }
        }
        return WithYVotes(r, y, r["n"]);
    }
    if (optlike >= 2)   // 横排陈述 ①②③④，按 x 顺序拼成一行
    {
        string text = string.Join("　", reps.Select(r => PyStr(r["text"])));
        return JoinedRep(reps, text, y);
    }
    string joined = PyStr(reps[0]["text"]);
    for (int k = 1; k < reps.Count; k++) joined = JoinText(joined, PyStr(reps[k]["text"]));
    return JoinedRep(reps, joined, y);
}

/// <summary>
/// 等价 difflib.SequenceMatcher(None, a, b).ratio()。
/// 用 Ratcliff / Obershelp「递归最长连续匹配块」求匹配总数 M，ratio = 2*M/(len(a)+len(b))；
/// 不是简单的 LCS（后者允许跨块组合，M 会偏大）。这里按 difflib 的
/// find_longest_match（j2len 动态规划 + 前后扩展）与递归划分区间实现，
/// 并保留 autojunk（len(b)>=200 时剔除高频字符）与非 junk 扩展。
/// 序列元素按 UTF-16 char 计：对 BMP 文本（中文 / 圈号 / 全角标点）与 Python 逐字符一致。
/// </summary>
static double SeqRatio(string a, string b)
{
    int la = a.Length, lb = b.Length;
    if (la + lb == 0) return 1.0;

    var b2j = new Dictionary<char, List<int>>();
    for (int j = 0; j < lb; j++)
    {
        if (!b2j.TryGetValue(b[j], out var lst)) { lst = new List<int>(); b2j[b[j]] = lst; }
        lst.Add(j);
    }
    // autojunk：仅当 len(b)>=200 生效，出现次数 > len(b)/100+1 的字符不作为匹配锚点
    if (lb >= 200)
    {
        int ntest = lb / 100 + 1;
        foreach (var key in b2j.Keys.ToList())
            if (b2j[key].Count > ntest) b2j.Remove(key);
    }

    long total = 0;
    var stack = new Stack<(int Alo, int Ahi, int Blo, int Bhi)>();
    stack.Push((0, la, 0, lb));
    while (stack.Count > 0)
    {
        var (alo, ahi, blo, bhi) = stack.Pop();
        var (i, j, k) = FindLongestMatch(a, b, b2j, alo, ahi, blo, bhi);
        if (k > 0)
        {
            total += k;
            if (alo < i && blo < j) stack.Push((alo, i, blo, j));
            if (i + k < ahi && j + k < bhi) stack.Push((i + k, ahi, j + k, bhi));
        }
    }
    return 2.0 * total / (la + lb);
}

static (int I, int J, int K) FindLongestMatch(
    string a, string b, Dictionary<char, List<int>> b2j, int alo, int ahi, int blo, int bhi)
{
    int besti = alo, bestj = blo, bestsize = 0;
    var j2len = new Dictionary<int, int>();
    for (int i = alo; i < ahi; i++)
    {
        var newj2len = new Dictionary<int, int>();
        if (b2j.TryGetValue(a[i], out var js))
        {
            foreach (int j in js)
            {
                if (j < blo) continue;
                if (j >= bhi) break;
                int k = (j2len.TryGetValue(j - 1, out var prev) ? prev : 0) + 1;
                newj2len[j] = k;
                if (k > bestsize) { besti = i - k + 1; bestj = j - k + 1; bestsize = k; }
            }
        }
        j2len = newj2len;
    }
    // 非 junk 扩展（把匹配块向前后扩到最长）
    while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1])
    {
        besti--; bestj--; bestsize++;
    }
    while (besti + bestsize < ahi && bestj + bestsize < bhi
           && a[besti + bestsize] == b[bestj + bestsize])
    {
        bestsize++;
    }
    return (besti, bestj, bestsize);
}

/// <summary>Python round(x, 1)：banker's rounding，json 里保留一位小数（如 123.0）。</summary>
static string Round1(double x) =>
    Math.Round(x, 1, MidpointRounding.ToEven).ToString("0.0", CultureInfo.InvariantCulture);

static double AsDouble(object? v) => v switch
{
    JNum n => n.D,
    double d => d,
    int i => i,
    long l => l,
    _ => double.Parse(PyStr(v), CultureInfo.InvariantCulture),
};

static string PyStr(object? v) => v switch
{
    null => "None",
    string s => s,
    JNum n => n.Raw,
    bool b => b ? "True" : "False",
    _ => v.ToString() ?? "",
};

static List<object?> AsList(object? v) => v as List<object?> ?? new List<object?>();

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

sealed class JNum
{
    public string Raw { get; }
    public JNum(string raw) { Raw = raw; }
    public double D => double.Parse(Raw, CultureInfo.InvariantCulture);
}
