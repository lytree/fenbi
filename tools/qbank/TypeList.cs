#:property PublishAot=false
#:package SixLabors.ImageSharp@3.1.5

// 生成图推题图形类型清单（md + csv）：合并人工分类、重绘状态、选项图像指标
using System.Text;
using System.Text.Json;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;

string root = args.Length > 0 ? args[0] : @"qbank_out\超格行测套卷";
string typeFile = args.Length > 1 ? args[1] : @"tools\qbank\fig_types.json";
string outMd = args.Length > 2 ? args[2] : Path.Combine(root, "图推题图形类型清单.md");
string outCsv = args.Length > 3 ? args[3] : Path.Combine(root, "图推题图形类型清单.csv");

var types = new List<Item>();
using (var doc = JsonDocument.Parse(File.ReadAllText(typeFile, Encoding.UTF8)))
{
    foreach (var e in doc.RootElement.GetProperty("items").EnumerateArray())
    {
        types.Add(new Item
        {
            Vol = e.GetProperty("vol").GetInt32(),
            No = e.GetProperty("no").GetInt32(),
            Type = e.GetProperty("type").GetString() ?? "",
            Sub = e.GetProperty("sub").GetString() ?? "",
            Grade = e.GetProperty("grade").GetString() ?? "",
            Note = e.GetProperty("note").GetString() ?? "",
        });
    }
}

// 重绘状态：key = 卷:题号:part
var kinds = new Dictionary<string, string>();
string repFile = @"tmp\build\redraw\report.json";
if (File.Exists(repFile))
{
    using var doc = JsonDocument.Parse(File.ReadAllText(repFile, Encoding.UTF8));
    foreach (var e in doc.RootElement.EnumerateArray())
        kinds[e.GetProperty("key").GetString()!] = e.GetProperty("kind").GetString()!;
}

// v3 点阵重绘结果（逐格验收通过的那批）
string dots2File = "tmp/build/dots2/report.json";
if (File.Exists(dots2File))
{
    using var d2 = JsonDocument.Parse(File.ReadAllText(dots2File, Encoding.UTF8));
    foreach (var e in d2.RootElement.EnumerateArray())
    {
        if (!(e.GetProperty("Ok").GetBoolean())) continue;
        string volName = $"行测套卷{e.GetProperty("Vol").GetInt32()}";
        kinds[$"{volName}:{e.GetProperty("No").GetInt32()}:{Part(e.GetProperty("Name").GetString()!)}"] = "dots2";
    }
}

foreach (var it in types)
{
    string volName = $"行测套卷{it.Vol}";
    string dir = Path.Combine(root, volName, "images");
    var parts = new List<string> { "stem", "A", "B", "C", "D" };
    var kindSet = new Dictionary<string, int>();
    var sizes = new List<string>();
    double inkSum = 0; int inkN = 0;
    foreach (var p in parts)
    {
        string key = $"{volName}:{it.No}:{p}";
        string k = kinds.TryGetValue(key, out var kk) ? kk : "-";
        kindSet[k] = kindSet.GetValueOrDefault(k) + 1;
        string f = p == "stem" ? Path.Combine(dir, $"q{it.No:D3}.png") : Path.Combine(dir, $"q{it.No:D3}_{p}.png");
        if (!File.Exists(f)) continue;
        using var img = Image.Load<Rgba32>(f);
        if (p != "stem") sizes.Add($"{img.Width}x{img.Height}");
        double ink = 0;
        for (int y = 0; y < img.Height; y++)
            for (int x = 0; x < img.Width; x++)
            {
                var px = img[x, y];
                double lum = (px.R * 0.299 + px.G * 0.587 + px.B * 0.114) / 255.0;
                if (lum < 0.55 && px.A > 128) ink++;
            }
        inkSum += ink / Math.Max(1, img.Width * img.Height); inkN++;
    }
    it.KindText = string.Join("/", kindSet.OrderByDescending(k => k.Value).Select(k => k.Key));
    it.KindRaw = kindSet.OrderByDescending(k => k.Value).First().Key;
    it.Drawn = kindSet.Where(k => k.Key is "dots" or "grid" or "dots2").Sum(k => k.Value);
    it.SizeText = sizes.Count > 0 ? sizes[0] : "-";
    it.SizeSpread = Spread(sizes);
    it.Ink = inkN > 0 ? inkSum / inkN : 0;

    // 计算结构化完成度：题干是否也是结构化
    it.StemStructured = kinds.TryGetValue($"{volName}:{it.No}:stem", out var sk) && (sk == "dots" || sk == "grid");
}

var sb = new StringBuilder();
int total = types.Count;
int aCount = types.Count(t => t.Grade == "A"), bCount = types.Count(t => t.Grade == "B"), cCount = types.Count(t => t.Grade == "C");
int structImgs = types.Sum(t => t.Drawn);
int aStructImgs = types.Where(t => t.Grade == "A").Sum(t => t.Drawn);
int aFull = types.Count(t => t.Grade == "A" && t.Drawn >= 5);
int aPart = types.Count(t => t.Grade == "A" && t.Drawn >= 1 && t.Drawn < 5);
sb.AppendLine("# 超格行测 10 套卷 · 图推题图形类型清单");
sb.AppendLine();
sb.AppendLine($"> 数据来源：`{root}` 全部 `q{ "NNN" }_A~D.png` 选项图 + `tmp/build/redraw/report.json` 重绘记录。");
sb.AppendLine($"> 分类方式：逐题视觉核对（`tmp/figtype/sheet_01~13.png` 对照拼图）后固化为 `tools/qbank/fig_types.json`，可复算。");
sb.AppendLine();
sb.AppendLine("## 一、总览");
sb.AppendLine();
sb.AppendLine("| 项 | 数量 | 说明 |");
sb.AppendLine("|---|---|---|");
sb.AppendLine($"| 图推题总数 | {total} | 78 = 10 套卷中含图形选项的全部题目 |");
sb.AppendLine($"| A 级（可参数化自绘） | {aCount} | 点阵/方格/饼图/柱状/折线等规则结构，能按格心、角度、柱高直接生成 |");
sb.AppendLine($"| B 级（几何已知需给参） | {bCount} | 形状简单但顶点/角度需逐题标定，或含文字标注 |");
sb.AppendLine($"| C 级（不建议自绘） | {cCount} | 立体堆积/立体展开/立体轮廓/文字流程，保留清理后的位图 |");
int optDone = types.Count(t => t.Grade == "A" && t.Drawn >= 4);
sb.AppendLine($"| A 级已结构化图数 | {aStructImgs} | A 级题目里已由规则重绘的图片数（题干多为一行小图，通常仍是位图） |");
sb.AppendLine($"| A 级选项全结构化（4/4） | {optDone} | 四个选项全部自绘的题数 |");
sb.AppendLine($"| A 级部分完成 | {aPart} | 只完成了部分选项的题数 |");
sb.AppendLine();
int c2 = kinds.Values.Count(v => v == "dots2");
int cd = kinds.Values.Count(v => v == "dots");
int cg = kinds.Values.Count(v => v == "grid");
int cb = kinds.Values.Count(v => v == "bitmap");
sb.AppendLine($"当前重绘覆盖（按单图计）：自绘点阵 {c2} 张、dots {cd} 张、grid {cg} 张、位图清理 {cb} 张。");
sb.AppendLine();
sb.AppendLine("## 二、按图形类型汇总");
sb.AppendLine();
sb.AppendLine("| 图形类型 | 题数 | 等级分布 | 已结构化图数 | 涉及题号 |");
sb.AppendLine("|---|---|---|---|---|");
foreach (var g in types.GroupBy(t => t.Type).OrderByDescending(g => g.Count()))
{
    string gradeDist = string.Join(" ", g.GroupBy(t => t.Grade).OrderBy(t => t.Key).Select(x => $"{x.Key}{x.Count()}"));
    int done = g.Sum(t => t.Drawn);
    string list = string.Join("、", g.Select(t => $"{t.Vol}-{t.No}"));
    sb.AppendLine($"| {g.Key} | {g.Count()} | {gradeDist} | {(g.Any(t => t.Grade == "A") ? done.ToString() : "—")} | {list} |");
}
sb.AppendLine();
sb.AppendLine("## 三、逐卷明细");
sb.AppendLine();
sb.AppendLine("`重绘` 列格式为 `dots·grid·bitmap` 计数（5 张图中各类数量）；`尺寸` 取 A 选项尺寸；`离散` 为 4 张选项图长宽比的极差百分比。");
foreach (var vol in types.GroupBy(t => t.Vol).OrderBy(g => g.Key))
{
    sb.AppendLine();
    sb.AppendLine($"### 行测套卷 {vol.Key}（{vol.Count()} 题）");
    sb.AppendLine();
    sb.AppendLine("| 题号 | 图形类型 | 细分 | 等级 | 重绘 | 尺寸 | 离散 | 备注 |");
    sb.AppendLine("|---|---|---|---|---|---|---|---|");
    foreach (var t in vol.OrderBy(t => t.No))
    {
        sb.AppendLine($"| {t.No} | {t.Type} | {t.Sub} | {t.Grade} | `{t.KindText}` | {t.SizeText} | {t.SizeSpread:F0}% | {t.Note} |");
    }
}

sb.AppendLine();
sb.AppendLine("## 四、下一步建议（按收益排序）");
sb.AppendLine();
var todoA = types.Where(t => t.Grade == "A" && t.Drawn < 4).ToList();
sb.AppendLine($"1. **先补 A 级剩余 {todoA.Count} 题**：这些题结构完全规则，重绘后可与原卷 1:1 对齐且不会失真。重点类型是圆点阵（{types.Count(t => t.Type == "圆点阵")} 题）、饼图（{types.Count(t => t.Type == "饼图")} 题）、方格拼块（{types.Count(t => t.Type == "方格拼块")} 题）。");
sb.AppendLine($"2. **A 级里三张特殊结构要单独写生成器**：六角蜂窝点阵（5-79）、45° 三角拼块（7-63、8-64）、网格叠圆点（10-61）。");
sb.AppendLine($"3. **B 级 {bCount} 题按需推进**：含标注文字的 4 题（4-129 饼图百分比、7-67 与 10-66 视图名、8-119 坐标刻度）建议保留位图、仅清理边缘；其余多边形/线条类可逐题标顶点后矢量化。");
sb.AppendLine($"4. **C 级 {cCount} 题建议收手**：立体堆积（8 题）、立体投影（7 题）、立体展开（3 题）、立体轮廓（3 题）用位图更稳，只需保证裁切留白一致。");
sb.AppendLine("5. **字母残留集中清理**：6-77、6-82、7-65、7-66、9-65 的卷面 A/B/C 字母混进了选项图，建议按已知坐标涂白，不要用模糊擦除。");
sb.AppendLine();
sb.AppendLine("## 五、遗留风险");
sb.AppendLine();
sb.AppendLine("- `图推` 分类基于视觉核对，细分描述（如「双圈标记」「灰面」）为人工判读，若后续脚本按类型批处理，请以 `tools/qbank/fig_types.json` 为准并随时修订。");
var loose = types.Where(t => t.SizeSpread > 15).OrderBy(t => t.Vol).ThenBy(t => t.No).ToList();
sb.AppendLine($"- 裁切松紧不一致（尺寸离散 > 15%）共 {loose.Count} 题：{string.Join("、", loose.Select(t => $"{t.Vol}-{t.No}({t.SizeSpread:F0}%)"))}。导入 App 后并排显示会有大小跳动，建议优先统一留白。");
sb.AppendLine("- 5-76 的 C/D 在原卷即为空白网格，非拆分缺陷。");

File.WriteAllText(outMd, sb.ToString(), new UTF8Encoding(false));

var csv = new StringBuilder();
csv.AppendLine("卷,题号,图形类型,细分,等级,重绘分布,主类型,已结构化图数,A选项尺寸,尺寸离散%,平均墨量,备注");
foreach (var t in types.OrderBy(t => t.Vol).ThenBy(t => t.No))
    csv.AppendLine($"{t.Vol},{t.No},{Esc(t.Type)},{Esc(t.Sub)},{t.Grade},{Esc(t.KindText)},{t.KindRaw},{t.Drawn},{t.SizeText},{t.SizeSpread:F0},{t.Ink:F4},{Esc(t.Note)}");
File.WriteAllText(outCsv, csv.ToString(), new UTF8Encoding(false));

Console.WriteLine($"md  -> {outMd}");
Console.WriteLine($"csv -> {outCsv}");
Console.WriteLine($"total={total} A={aCount} B={bCount} C={cCount} structImgs={structImgs} aStruct={aStructImgs} aFull={aFull} aPart={aPart} todoA={todoA.Count}");

static double Spread(List<string> sizes)
{
    var ratios = new List<double>();
    foreach (var s in sizes)
    {
        var p = s.Split('x');
        if (p.Length == 2 && double.TryParse(p[1], out var h) && h > 0)
            ratios.Add(double.Parse(p[0]) / h);
    }
    if (ratios.Count < 2) return 0;
    return (ratios.Max() - ratios.Min()) / ratios.Max() * 100;
}

static string Part(string file)
{
    var n = Path.GetFileNameWithoutExtension(file);
    int u = n.IndexOf('_');
    return u < 0 ? "stem" : n[(u + 1)..];
}

static string Esc(string s) => s.Contains(',') || s.Contains('"') ? "\"" + s.Replace("\"", "\"\"") + "\"" : s;

class Item
{
    public int Vol; public int No;
    public string Type = "", Sub = "", Grade = "", Note = "";
    public string KindText = "-", KindRaw = "-", SizeText = "-";
    public int Drawn; public bool StemStructured;
    public double SizeSpread, Ink;
}
