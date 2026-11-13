#!/usr/bin/env dotnet
// 把 tools/extra_tags.json 里的语义分组标签补进内置词库。
//
// `extract_word_tags.cs` 只认识四海 800 词文档里出现过的 801 个词；
// 词库里另外六百多条成语来自《成语与实词汇总（合并版）》，源文档没有分组，
// 所以那批词的「细分」一直是空的。这个脚本把人工按释义逐个归类的
// `tools/extra_tags.json` 写进 CSV 的空「细分」列。
//
// 规则：
//   · 只填空标签，**不覆盖**已有标签（文档来的分组优先级更高）
//   · 词库里没有的词条只报告、不新增（没有释义、没有分类，凭空加行没有意义）
//   · UTF-8 无 BOM + LF，和仓库 .gitattributes 的 eol=lf 对齐
//
// 用法：
//   dotnet run tools/apply_extra_tags.cs                # 干跑，只打印统计
//   dotnet run tools/apply_extra_tags.cs -- --write    # 真正写回 CSV

#:property PublishAot=false

using System.Text;
using System.Text.Json;

Console.OutputEncoding = System.Text.Encoding.UTF8;

// ---------- 参数（对应 argparse） ----------
string csvPath = "app/src/main/assets/builtin/words.csv";
string tagsPath = "tools/extra_tags.json";
bool write = false;
for (int i = 0; i < args.Length; i++)
{
    string a = args[i];
    if (a == "--csv" && i + 1 < args.Length) csvPath = args[++i];
    else if (a.StartsWith("--csv=", StringComparison.Ordinal)) csvPath = a["--csv=".Length..];
    else if (a == "--tags" && i + 1 < args.Length) tagsPath = args[++i];
    else if (a.StartsWith("--tags=", StringComparison.Ordinal)) tagsPath = a["--tags=".Length..];
    else if (a == "--write") write = true;
    else
    {
        Console.Error.WriteLine($"无法识别的参数：{a}");
        return 2;
    }
}

if (!File.Exists(tagsPath))
{
    Console.Error.WriteLine($"找不到标签表：{tagsPath}");
    return 2;
}
if (!File.Exists(csvPath))
{
    Console.Error.WriteLine($"找不到词库 CSV：{csvPath}");
    return 2;
}

// tags：词 -> 细分标签，保持 JSON 里的键顺序
var tags = new Dictionary<string, string?>();
using (var doc = JsonDocument.Parse(File.ReadAllText(tagsPath, Encoding.UTF8)))
{
    foreach (var p in doc.RootElement.EnumerateObject())
    {
        tags[p.Name] = p.Value.ValueKind switch
        {
            JsonValueKind.String => p.Value.GetString(),
            JsonValueKind.Null => null,
            _ => p.Value.ToString(),
        };
    }
}

string csvText = File.ReadAllText(csvPath, Encoding.UTF8);
var table = ParseCsv(csvText);
// DictReader 跳过空行，取第一个非空行作为表头
int head = -1;
for (int i = 0; i < table.Count; i++)
{
    if (table[i].Count > 0) { head = i; break; }
}
if (head < 0)
{
    Console.Error.WriteLine($"词库 CSV 为空：{csvPath}");
    return 2;
}
var fields = table[head];
var rows = new List<Dictionary<string, string>>();
for (int r = head + 1; r < table.Count; r++)
{
    if (table[r].Count == 0) continue;   // 空行
    var row = new Dictionary<string, string>();
    for (int c = 0; c < fields.Count; c++)
        row[fields[c]] = c < table[r].Count ? table[r][c] : "";
    rows.Add(row);
}

var have = new HashSet<string>();
foreach (var r in rows) have.Add(r["词条"]);
var missing = new List<string>();
foreach (var w in tags.Keys) if (!have.Contains(w)) missing.Add(w);

int filled = 0, skipped = 0;
foreach (var r in rows)
{
    if (!tags.TryGetValue(r["词条"], out var t) || string.IsNullOrEmpty(t)) continue;
    if (r["细分"].Trim().Length > 0) skipped++;
    else { r["细分"] = t; filled++; }
}

int blankIdiom = rows.Count(r => r["分类"] == "成语" && r["细分"].Trim().Length == 0);
int blankWord = rows.Count(r => r["分类"] != "成语" && r["细分"].Trim().Length == 0);

Console.WriteLine($"标签表 {tags.Count} 条；本次填充 {filled} 条，已有标签跳过 {skipped} 条");
Console.WriteLine($"词库里没有的映射 {missing.Count} 个：{PyListRepr(missing.Take(10).ToList())}");
Console.WriteLine($"剩余无标签：成语 {blankIdiom} 条 / 实词 {blankWord} 条");

if (write && filled > 0)
{
    WriteCsv(csvPath, fields, rows);
    Console.WriteLine($"已写回 {csvPath}");
}
return 0;

// ---------------- helpers ----------------

/// <summary>
/// 手写 CSV 解析，等价 csv.reader 的 excel 方言：逗号分隔、双引号包裹、
/// 字段内双引号用 "" 转义、引号内可含逗号与换行；空行返回空行（由调用方跳过）。
/// </summary>
static List<List<string>> ParseCsv(string text)
{
    var rows = new List<List<string>>();
    var row = new List<string>();
    var field = new StringBuilder();
    bool inQuotes = false;
    bool lineHadContent = false;
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
        if (c == '"') { inQuotes = true; lineHadContent = true; i++; continue; }
        if (c == ',') { row.Add(field.ToString()); field.Clear(); lineHadContent = true; i++; continue; }
        if (c == '\r' || c == '\n')
        {
            if (lineHadContent || field.Length > 0 || row.Count > 0)
            {
                row.Add(field.ToString());
                rows.Add(row);
            }
            else rows.Add(new List<string>());
            row = new List<string>();
            field.Clear();
            lineHadContent = false;
            inQuotes = false;
            if (c == '\r' && i + 1 < text.Length && text[i + 1] == '\n') i += 2;
            else i++;
            continue;
        }
        field.Append(c);
        lineHadContent = true;
        i++;
    }
    if (lineHadContent || field.Length > 0 || row.Count > 0)
    {
        row.Add(field.ToString());
        rows.Add(row);
    }
    return rows;
}

/// <summary>QUOTE_MINIMAL：仅当字段含逗号、双引号、CR 或 LF 时才加引号。</summary>
static string QuoteField(string s)
{
    if (s.IndexOfAny(new[] { ',', '"', '\r', '\n' }) < 0) return s;
    return "\"" + s.Replace("\"", "\"\"") + "\"";
}

/// <summary>
/// 等价 csv.DictWriter（fieldnames=fields, lineterminator="\n"）：
/// 先写表头，再按表头顺序写每行；UTF-8 无 BOM。
/// </summary>
static void WriteCsv(string path, List<string> fields, List<Dictionary<string, string>> rows)
{
    var sb = new StringBuilder();
    void AppendRow(List<string> vals)
    {
        for (int i = 0; i < vals.Count; i++)
        {
            if (i > 0) sb.Append(',');
            sb.Append(QuoteField(vals[i]));
        }
        sb.Append('\n');
    }
    AppendRow(fields);
    foreach (var r in rows)
        AppendRow(fields.Select(f => r.TryGetValue(f, out var v) ? v : "").ToList());
    File.WriteAllText(path, sb.ToString(), new UTF8Encoding(false));
}

/// <summary>Python 3 的字符串 repr（优先单引号，含单引号不含双引号时改用双引号）。</summary>
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
        else if (c < 0x20 || c == 0x7f) sb.Append("\\x").Append(((int)c).ToString("x2"));
        else sb.Append(c);
    }
    sb.Append(quote);
    return sb.ToString();
}

/// <summary>Python list 的 str/repr：['词1', '词2']。</summary>
static string PyListRepr(List<string> items) =>
    "[" + string.Join(", ", items.Select(PyReprStr)) + "]";
