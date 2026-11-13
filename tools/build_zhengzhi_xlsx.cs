#!/usr/bin/env dotnet
// 把解析结果写成 App 能直接导入的 xlsx（一张表 = 一份试卷）。
//
// 两种产物：
//   1) 整卷：200 题一张表
//   2) 分组：每 20 题一张表，共 10 份卷
//
// 用法：
//   dotnet run tools/build_zhengzhi_xlsx.cs                 # 整卷
//   dotnet run tools/build_zhengzhi_xlsx.cs -- --group 20  # 每 20 题一表

#:property PublishAot=false

using System.Globalization;
using System.IO.Compression;
using System.Text;
using System.Text.Json;

Console.OutputEncoding = System.Text.Encoding.UTF8;

const string SRC = "F:/Code/Android/fenbi/tmp/zhengzhi/questions.json";
const string OUT_DIR = "F:/Code/Android/fenbi/qbank_out";

const string SUBJECT = "政治理论";
const string TYPE = "单选";
const string SOURCE = "超格《2027政治理论背诵手册》考前200题";
string[] HEADERS = { "题型", "科目", "题号", "题干", "选项A", "选项B", "选项C", "选项D", "答案", "来源" };

int group = 0;
int gi = Array.IndexOf(args, "--group");
if (gi >= 0) group = int.Parse(args[gi + 1], CultureInfo.InvariantCulture);

if (!File.Exists(SRC))
{
    Console.Error.WriteLine($"找不到题目文件：{SRC}");
    return 2;
}
var qs = (List<object?>)JParse(File.ReadAllText(SRC, Encoding.UTF8))!;

if (group == 0)
{
    string name = "政治理论考前200题";
    string path = Path.Combine(OUT_DIR, name + ".xlsx");
    var sheets = new List<(string Name, List<List<object?>> Rows)>
    {
        (name, RowsFor(qs, SOURCE, HEADERS, TYPE, SUBJECT)),
    };
    WriteXlsx(path, sheets);
    Console.WriteLine($"整卷 {path} {qs.Count} 题");
    return 0;
}

string name2 = $"政治理论考前200题（{group}题一组）";
string path2 = Path.Combine(OUT_DIR, name2 + ".xlsx");
var sheets2 = new List<(string Name, List<List<object?>> Rows)>();
for (int i = 0; i < qs.Count; i += group)
{
    var chunk = qs.GetRange(i, Math.Min(group, qs.Count - i));
    string firstNo = IntFmt(((Dictionary<string, object?>)chunk[0]!)["no"]);
    string lastNo = IntFmt(((Dictionary<string, object?>)chunk[chunk.Count - 1]!)["no"]);
    string rng = $"{firstNo}-{lastNo}题";
    string title = $"第{i / group + 1:D2}组（{rng}）";
    sheets2.Add((title, RowsFor(chunk, SOURCE + " " + rng, HEADERS, TYPE, SUBJECT)));
}
WriteXlsx(path2, sheets2);
Console.WriteLine($"分组 {path2} {sheets2.Count} 张表 / {qs.Count} 题");
foreach (var (t, r) in sheets2)
    Console.WriteLine($"   {t} {r.Count - 1} 题");
return 0;

// ---------------- helpers ----------------

/// <summary>rows_for：表头 + 每题一行（题型/科目/题号/题干/A~D/答案/来源）。</summary>
static List<List<object?>> RowsFor(List<object?> qs, string label, string[] headers, string type, string subject)
{
    var rows = new List<List<object?>> { headers.Select(h => (object?)h).ToList() };
    foreach (var q0 in qs)
    {
        var q = (Dictionary<string, object?>)q0!;
        var opts = (Dictionary<string, object?>)q["options"]!;
        rows.Add(new List<object?>
        {
            type, subject, q["no"], q["stem"],
            opts["A"], opts["B"], opts["C"], opts["D"],
            q["answer"], label,
        });
    }
    return rows;
}

/// <summary>xml.sax.saxutils.escape 默认只转义 &amp; &lt; &gt;，不动引号。</summary>
static string Esc(string s) =>
    s.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;");

/// <summary>0-based 列号转 Excel 列字母（A..Z, AA..）。</summary>
static string ColLetter(int idx0)
{
    string s = "";
    int n = idx0 + 1;
    while (n > 0)
    {
        int m = n - 1;
        n = m / 26;
        int r = m % 26;
        s = (char)(65 + r) + s;
    }
    return s;
}

/// <summary>val == "" 或 val is None 时输出空单元格。</summary>
static bool IsEmptyCell(object? v) => v is null || (v is string s && s.Length == 0);

/// <summary>worksheet XML：全部单元格用 inlineStr，空值写自闭合 &lt;c/&gt;。</summary>
static string SheetXml(List<List<object?>> rows)
{
    var sb = new StringBuilder();
    sb.Append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    sb.Append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
    sb.Append("<sheetData>");
    for (int ri = 1; ri <= rows.Count; ri++)
    {
        var row = rows[ri - 1];
        var cells = new StringBuilder();
        for (int ci = 0; ci < row.Count; ci++)
        {
            string cellRef = ColLetter(ci) + ri.ToString(CultureInfo.InvariantCulture);
            if (IsEmptyCell(row[ci]))
            {
                cells.Append("<c r=\"").Append(cellRef).Append("\"/>");
            }
            else
            {
                cells.Append("<c r=\"").Append(cellRef)
                     .Append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                     .Append(Esc(PyStr(row[ci])))
                     .Append("</t></is></c>");
            }
        }
        sb.Append("<row r=\"").Append(ri.ToString(CultureInfo.InvariantCulture)).Append("\">")
          .Append(cells).Append("</row>");
    }
    sb.Append("</sheetData></worksheet>");
    return sb.ToString();
}

/// <summary>
/// 手写 OOXML 包：ZipArchive 对应 zipfile.ZipFile(..., "w", ZIP_DEFLATED)，
/// 各 XML 部件的内容与字段顺序与 Python 版逐字一致（UTF-8 无 BOM）。
/// </summary>
static void WriteXlsx(string path, List<(string Name, List<List<object?>> Rows)> sheets)
{
    int n = sheets.Count;
    var overrides = new StringBuilder();
    for (int i = 0; i < n; i++)
    {
        overrides.Append("<Override PartName=\"/xl/worksheets/sheet")
                 .Append((i + 1).ToString(CultureInfo.InvariantCulture))
                 .Append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
    }
    string contentTypes =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        + "<Override PartName=\"/xl/workbook.xml\" "
        + "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
        + overrides + "</Types>";
    string rels =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" "
        + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" "
        + "Target=\"xl/workbook.xml\"/></Relationships>";
    var sheetTags = new StringBuilder();
    for (int i = 0; i < n; i++)
    {
        sheetTags.Append("<sheet name=\"").Append(Esc(sheets[i].Name))
                 .Append("\" sheetId=\"").Append((i + 1).ToString(CultureInfo.InvariantCulture))
                 .Append("\" r:id=\"rId").Append((i + 1).ToString(CultureInfo.InvariantCulture))
                 .Append("\"/>");
    }
    string workbook =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
        + "<sheets>" + sheetTags + "</sheets></workbook>";
    var wbRels = new StringBuilder(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
    for (int i = 0; i < n; i++)
    {
        wbRels.Append("<Relationship Id=\"rId").Append((i + 1).ToString(CultureInfo.InvariantCulture))
              .Append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" ")
              .Append("Target=\"worksheets/sheet").Append((i + 1).ToString(CultureInfo.InvariantCulture))
              .Append(".xml\"/>");
    }
    wbRels.Append("</Relationships>");

    Directory.CreateDirectory(Path.GetDirectoryName(path)!);
    using (var fs = new FileStream(path, FileMode.Create, FileAccess.Write))
    using (var zip = new ZipArchive(fs, ZipArchiveMode.Create))
    {
        AddEntry(zip, "[Content_Types].xml", contentTypes);
        AddEntry(zip, "_rels/.rels", rels);
        AddEntry(zip, "xl/workbook.xml", workbook);
        AddEntry(zip, "xl/_rels/workbook.xml.rels", wbRels.ToString());
        for (int i = 0; i < n; i++)
            AddEntry(zip, "xl/worksheets/sheet" + (i + 1).ToString(CultureInfo.InvariantCulture) + ".xml",
                     SheetXml(sheets[i].Rows));
    }
}

static void AddEntry(ZipArchive zip, string name, string content)
{
    var entry = zip.CreateEntry(name, CompressionLevel.Optimal);
    using var w = new StreamWriter(entry.Open(), new UTF8Encoding(false));
    w.Write(content);
}

/// <summary>Python 的 %d：按整数输出（float 会向零截断）。</summary>
static string IntFmt(object? v) =>
    ((long)AsDouble(v)).ToString(CultureInfo.InvariantCulture);

static string PyStr(object? v) => v switch
{
    null => "None",
    string s => s,
    JNum n => n.Raw,
    bool b => b ? "True" : "False",
    _ => v.ToString() ?? "",
};

static double AsDouble(object? v) => v switch
{
    JNum n => n.D,
    double d => d,
    int i => i,
    _ => double.Parse(PyStr(v), CultureInfo.InvariantCulture),
};

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

sealed class JNum
{
    public string Raw { get; }
    public JNum(string raw) { Raw = raw; }
    public double D => double.Parse(Raw, CultureInfo.InvariantCulture);
}
