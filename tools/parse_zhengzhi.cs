#!/usr/bin/env dotnet
// 把 OCR 行还原成 200 道选择题的结构化数据。
//
// 输入：tmp/zhengzhi/ocr_merged.json（坐标已归一化到 220dpi）
// 输出：tmp/zhengzhi/questions.json + 控制台校验报告
//
// 用法：
//   dotnet run tools/parse_zhengzhi.cs                        # 读默认 ocr_merged.json
//   dotnet run tools/parse_zhengzhi.cs -- <输入 ocr_merged.json>

#:property PublishAot=false

using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

Console.OutputEncoding = System.Text.Encoding.UTF8;

string SRC = args.Length > 0 ? args[0] : "F:/Code/Android/fenbi/tmp/zhengzhi/ocr_merged.json";
const string OUT = "F:/Code/Android/fenbi/tmp/zhengzhi/questions.json";
const string CIRCLE = "①②③④⑤⑥⑦⑧";

const string ANSWER_RAW = @"
1~5:BBCCC 6~10:CACBA 11~15:ADBAA 16~20:DCBCD
21~25:CADDB 26~30:CBCBC 31~35:CCDCD 36~40:DCADD
41~45:CDBBA 46~50:ACCCD 51~55:CCCDD 56~60:BABBB
61~65:CBDCC 66~70:CDDAC 71~75:ADDDC 76~80:ACACB
81~85:BBCBD 86~90:CDDCB 91~95:DABCD 96~100:BBCAA
101~105:BBDCB 106~110:CCDAA 111~115:DDBBD 116~120:CBCDB
121~125:DBCCD 126~130:BCDDA 131~135:DCDDD 136~140:DABAC
141~145:CACDC 146~150:ADCCD 151~155:DCCBC 156~160:CBBBB
161~165:ABACA 166~170:ADBCB 171~175:BBCBC 176~180:DACAD
181~185:DBBAB 186~190:DBDAB 191~195:BCCCD 196~200:BDDAD
";

if (!File.Exists(SRC))
{
    Console.Error.WriteLine($"找不到输入文件：{SRC}");
    return 2;
}
var data = (Dictionary<string, object?>)JParse(File.ReadAllText(SRC, Encoding.UTF8))!;
var pages = data.Keys.OrderBy(k => int.Parse(k, CultureInfo.InvariantCulture)).ToList();

var questions = new List<Dictionary<string, object?>>();
Dictionary<string, object?>? cur = null;
string? lastField = null;
int expected = 1;
var problems = new List<string>();

foreach (var pg in pages)
{
    foreach (var ln0 in AsList(data[pg]))
    {
        var ln = (Dictionary<string, object?>)ln0!;
        string text = Clean(PyStr(ln["text"]));
        if (text.Length == 0 || IsNoise(text, AsDouble(ln["y"]))) continue;

        var m = Regex.Match(text, @"^(\d{1,3})\s*[.．、,，]\s*");
        // 220dpi 基准下：题目行缩进约 262，续行顶格约 201
        bool isQstart = m.Success
            && int.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture) == expected
            && (cur == null || AsDouble(ln["x"]) > 230);

        if (isQstart)
        {
            if (cur != null) questions.Add(cur);
            cur = new Dictionary<string, object?>
            {
                ["no"] = expected,
                ["stem"] = text.Substring(m.Index + m.Length),
                ["options"] = new Dictionary<string, object?>(),
                ["page"] = int.Parse(pg, CultureInfo.InvariantCulture),
            };
            lastField = "stem";
            expected++;
            continue;
        }
        if (cur == null) continue;

        if (Regex.IsMatch(text, @"^[A-D]\s*[.．、]\s*"))
        {
            foreach (var part in SplitOptions(text))
            {
                var nv = NormOption(part);
                if (nv == null) continue;
                var (k, v) = nv.Value;
                ((Dictionary<string, object?>)cur["options"]!)[k] = v;
                lastField = k;
            }
            continue;
        }

        // 圈号陈述行；OCR 偶尔把 ⑤ 识别成 "5"，靠「已有圈号陈述」兜住
        string stem = PyStr(cur["stem"]);
        bool hasCircle = stem.Split('\n').Any(s => s.Length > 0 && CIRCLE.Contains(s[0]));
        var mDigit = Regex.Match(text, @"^([1-8])(?=[\u4e00-\u9fa5])");
        if (CIRCLE.Contains(text[0]))
        {
            cur["stem"] = stem + "\n" + FixCircle(text);
            lastField = "stem";
            continue;
        }
        if (hasCircle && mDigit.Success)
        {
            string t = CIRCLE[int.Parse(mDigit.Groups[1].Value, CultureInfo.InvariantCulture) - 1].ToString()
                       + text.Substring(1);
            cur["stem"] = stem + "\n" + FixCircle(t);
            lastField = "stem";
            continue;
        }

        var opts = (Dictionary<string, object?>)cur["options"]!;
        if (lastField != null && "ABCD".Contains(lastField) && opts.ContainsKey(lastField))
            opts[lastField] = PyStr(opts[lastField]) + text;
        else
            cur["stem"] = stem + text;
    }
}
if (cur != null) questions.Add(cur);

var ans = ParseAnswers(ANSWER_RAW);
foreach (var q in questions)
{
    q["answer"] = ans.TryGetValue((int)q["no"]!, out var a) ? a : "";
    q["stem"] = Polish(PyStr(q["stem"]));
    var opts = (Dictionary<string, object?>)q["options"]!;
    var polished = new Dictionary<string, object?>();
    foreach (var kv in opts) polished[kv.Key] = Polish(PyStr(kv.Value));
    q["options"] = polished;
}

var nos = questions.Select(q => (int)q["no"]!).ToList();
if (nos.Count != 200 || !nos.Select((n, i) => n == i + 1).All(x => x))
{
    var missing = Enumerable.Range(1, 200).Except(nos).OrderBy(x => x).Take(40).ToList();
    problems.Add($"题号序列异常：共 {nos.Count} 题，缺 {PyIntListRepr(missing)}");
}
foreach (var q in questions)
{
    int no = (int)q["no"]!;
    var opts = (Dictionary<string, object?>)q["options"]!;
    var keys = opts.Keys.OrderBy(k => k, StringComparer.Ordinal).ToList();
    if (!keys.SequenceEqual(new[] { "A", "B", "C", "D" }))
        problems.Add($"第 {no} 题选项不全：{PyStrListRepr(keys)}（p{(int)q["page"]!}）");
    if (PyStr(q["answer"]).Length == 0)
        problems.Add($"第 {no} 题缺答案");
    string stem = PyStr(q["stem"]);
    if (stem.Length < 10)
        problems.Add($"第 {no} 题题干过短：{PyReprStr(stem)}");
    int nCircle = Regex.Matches(stem, @"[①②③④⑤⑥⑦⑧]").Count;
    if (nCircle > 0 && nCircle < 3)
        problems.Add($"第 {no} 题陈述只有 {nCircle} 条（疑似漏行）");
    foreach (var kv in opts)
    {
        if (PyStr(kv.Value).Length < 1)
            problems.Add($"第 {no} 题选项 {kv.Key} 为空");
    }
}

File.WriteAllText(OUT, JDump(questions.Select(q => (object?)q).ToList(), 1), new UTF8Encoding(false));
int totalChars = questions.Sum(q =>
    PyStr(q["stem"]).Length + ((Dictionary<string, object?>)q["options"]!).Values.Sum(v => PyStr(v).Length));
Console.WriteLine($"题数: {questions.Count} 总字数: {totalChars}");
Console.WriteLine($"问题数: {problems.Count}");
foreach (var p in problems) Console.WriteLine($"  ! {p}");
return 0;

// ---------------- helpers ----------------

/// <summary>答案表：把 1~5:BBCCC 这类段落展开成题号 -> 字母。</summary>
static Dictionary<int, string> ParseAnswers(string raw)
{
    var ans = new Dictionary<int, string>();
    foreach (Match m in Regex.Matches(raw, @"(\d+)\s*[~～\-]\s*\d+\s*[:：]\s*([A-D]+)"))
    {
        int lo = int.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture);
        string letters = m.Groups[2].Value;
        for (int k = 0; k < letters.Length; k++)
            ans[lo + k] = letters[k].ToString();
    }
    return ans;
}

/// <summary>NBSP 转普通空格，去掉空格 / tab，去首尾空白。</summary>
static string Clean(string text)
{
    string t = text.Replace("\u00a0", " ");
    t = Regex.Replace(t, @"[ \t]+", "");
    return t.Trim();
}

/// <summary>页眉页脚 / 页码 / 水印词，用 \A..\z 模拟 re.fullmatch。</summary>
static bool IsNoise(string text, double y)
{
    if (y < 250 || y > 2100) return true;
    if (Regex.IsMatch(text, @"\A(?:\d{1,4})\z")) return true;
    string[] NOISE = { "抖音", "超格教育", "追梦人", "听预测课" };
    foreach (var k in NOISE) if (text.Contains(k)) return true;
    return false;
}

/// <summary>圈号旁的阿拉伯数字还原成圈号（固定宽度 lookbehind / lookahead）。</summary>
static string FixCircle(string text)
{
    const string CIRCLE = "①②③④⑤⑥⑦⑧";
    return Regex.Replace(text, @"(?<=[①②③④⑤⑥⑦⑧])[1-8]|[1-8](?=[①②③④⑤⑥⑦⑧])",
        m => CIRCLE[int.Parse(m.Value, CultureInfo.InvariantCulture) - 1].ToString());
}

/// <summary>OCR 常见成对错误：破折号被识别成「一一」，个别形近字。</summary>
static string Polish(string t)
{
    t = t.Replace("一一", "——").Replace("一—", "——").Replace("—一", "——");
    t = Regex.Replace(t, @"—{3,}", "——");
    t = Regex.Replace(t, @"(.{4,40})——\1$", "$1");   // 末尾整段重复
    var FIXES = new (string A, string B)[]
    {
        ("美其名日", "美其名曰"), ("名日", "名曰"), ("会唔", "会晤"), ("会晒", "会晤"),
        ("福社", "福祉"), ("路身", "跻身"), ("深人", "深入"),
        ("融人", "融入"), ("注人", "注入"), ("加人", "加入"), ("投人", "投入"),
        ("人市", "入市"), ("进人常态", "进入常态"),
    };
    foreach (var (a, b) in FIXES) t = t.Replace(a, b);
    return t;
}

/// <summary>按 A./B./C./D. 的位置把一行切成多个选项段。</summary>
static List<string> SplitOptions(string line)
{
    var idx = new List<int>();
    foreach (Match m in Regex.Matches(line, @"[A-D]\s*[.．、]")) idx.Add(m.Index);
    if (idx.Count == 0) return new List<string> { line };
    var parts = new List<string>();
    for (int k = 0; k < idx.Count; k++)
    {
        int s = idx[k];
        int e = k + 1 < idx.Count ? idx[k + 1] : line.Length;
        parts.Add(line.Substring(s, e - s).Trim());
    }
    return parts;
}

/// <summary>解析单段选项；组合选项里的阿拉伯数字还原成圈号。</summary>
static (string K, string V)? NormOption(string part)
{
    var m = Regex.Match(part, @"^([A-D])\s*[.．、]\s*(.*)$");
    if (!m.Success) return null;
    const string CIRCLE = "①②③④⑤⑥⑦⑧";
    string v = FixCircle(m.Groups[2].Value.Trim());
    if (Regex.IsMatch(v, @"\A(?:[①②③④⑤⑥⑦⑧1-8]{2,6})\z"))
    {
        var sb = new StringBuilder();
        foreach (var c in v)
            sb.Append("12345678".Contains(c)
                ? CIRCLE[int.Parse(c.ToString(), CultureInfo.InvariantCulture) - 1]
                : c);
        v = sb.ToString();
    }
    return (m.Groups[1].Value, v);
}

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

/// <summary>Python 3 字符串 repr（%r 用）。</summary>
static string PyReprStr(string s)
{
    char quote = s.Contains('\'') && !s.Contains('"') ? '"' : '\'';
    var sb = new StringBuilder();
    sb.Append(quote);
    foreach (var c in s)
    {
        if (c == quote || c == '\\') { sb.Append('\\'); sb.Append(c); }
        else if (c == '\n') sb.Append("\\n");
        else if (c == '\r') sb.Append("\\r");
        else if (c == '\t') sb.Append("\\t");
        else if (c < 0x20 || c == 0x7f) sb.Append("\\x").Append(((int)c).ToString("x2", CultureInfo.InvariantCulture));
        else sb.Append(c);
    }
    sb.Append(quote);
    return sb.ToString();
}

static string PyStrListRepr(List<string> items) =>
    "[" + string.Join(", ", items.Select(PyReprStr)) + "]";

static string PyIntListRepr(List<int> items) =>
    "[" + string.Join(", ", items.Select(i => i.ToString(CultureInfo.InvariantCulture))) + "]";

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
