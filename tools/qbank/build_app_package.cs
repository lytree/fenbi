#!/usr/bin/env dotnet
// 把「图推700题」的 Anki 导入包（anki_import.tsv + images/）转换成当前 App
// 能直接导入的「试卷包」，并按套拆成 35 份试卷：
//
//     图推700题试卷包/
//     ├── 试卷.xlsx          # 35 张工作表 = 35 份试卷（图推第01套 … 图推第35套）
//     └── images/            # 每题 5 张：题干图 + A/B/C/D 四张选项图（共 3500 张）
//
// 再压成 图推700题试卷包.zip，可直接在 App 导入页选入；导入预览页顶部会出现
// 35 个卷名胶囊，想建哪几套就勾哪几套。
//
// 背景：原始数据是 Anki 的四列 TSV（`<img src>` + 答案字母 + 题号标签 + 标签列），
// 一张图就是一道完整题（题干图形和 A–D 四个选项都画在同一张图里），没有文字选项。
// 这里用 `split_options.cs` 的 split_question（见下方内联注释）把整图拆成
// 「题干区 + 4 张选项图」：题干图裁掉底部选项后写进「图片」列；四个选项图分别写进
// 「选项A..D」列（`![选项](images/xxx_A.png)`，App 的选项单元格支持图片）。
//
// 当前 App 的规则是：
//
// * 一个工作表 = 一份试卷（docs/02-试卷格式规范.md）——所以「按套拆卷」就是把
//   每套题写成一张工作表，而不是把 700 题平铺在一张表里；
// * 题干必填且不渲染 Markdown —— 题图走「图片」列（导入时转成 material 里的图片
//   并前置渲染，出现在题干上方），题干只放「第几套·第几题」的定位文字；
// * 选项列里的 `![选项](相对路径)` 由导入器统一改写为解压后的绝对路径，四个选项
//   各自显示自己的图形，不再出现「见题图」。
//
// 用法：
//   dotnet run tools/qbank/build_app_package.cs                                  # 默认参数跑
//   dotnet run tools/qbank/build_app_package.cs -- --zip <原始 Anki 导入包.zip>   # 从中取源
//   dotnet run tools/qbank/build_app_package.cs -- --emit-dir                      # 同时把解开的 试卷.xlsx + images/ 落到包目录
//   dotnet run tools/qbank/build_app_package.cs -- --per-set                       # 另外再拆 35 个单卷包
//
// 注：原 Python 脚本里 `import split_options as so` / `so.split_question(src)`
// 依赖同目录脚本；file-based app 是单文件，不能引用另一个 .cs，因此下面把
// split_options.cs 的 split_question 及其全部依赖函数内联翻译（来源见「内联」注释块）。

#:package SkiaSharp@2.88.9
#:property PublishAot=false

using System.Globalization;
using System.IO.Compression;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using SkiaSharp;

Console.OutputEncoding = System.Text.Encoding.UTF8;

try
{
    string here = ThisDir();
    string root = Path.GetFullPath(Path.Combine(here, "..", ".."));
    string outRoot = Path.Combine(root, "qbank_out");
    string pkgName = "图推700题试卷包";
    G.Here = here;
    G.PkgName = pkgName;
    G.PkgDir = Path.Combine(outRoot, pkgName);
    G.PkgZip = Path.Combine(outRoot, pkgName + ".zip");
    G.SplitDir = Path.Combine(outRoot, pkgName + "-分卷");

    string? zipPath = null;
    string tsvPath = Path.Combine(outRoot, "anki_import.tsv");
    string imgDir = Path.Combine(outRoot, "images");
    bool perSet = false, emitDir = false;
    for (int i = 0; i < args.Length; i++)
    {
        switch (args[i])
        {
            case "--zip" when i + 1 < args.Length: zipPath = args[++i]; break;
            case "--tsv" when i + 1 < args.Length: tsvPath = args[++i]; break;
            case "--images" when i + 1 < args.Length: imgDir = args[++i]; break;
            case "--per-set": perSet = true; break;
            case "--emit-dir": emitDir = true; break;
        }
    }

    string? tmp = null;
    if (zipPath != null)
    {
        (tmp, tsvPath, imgDir) = ExtractPackage(zipPath);
        Console.WriteLine($"从压缩包取源：{zipPath}");
    }
    if (!File.Exists(tsvPath))
        throw new Exception($"找不到 TSV：{tsvPath}（先跑 OCR/切片流程，或 --zip 指定原始包）");
    if (!Directory.Exists(imgDir))
        throw new Exception($"找不到题图目录：{imgDir}");

    var records = ParseTsv(tsvPath);
    var (sets, problems) = GroupSets(records);
    if (problems.Count > 0)
    {
        Console.WriteLine("警告：");
        foreach (var p in problems) Console.WriteLine("   " + p);
    }
    var missing = CheckImages(records, imgDir);
    if (missing.Count > 0)
        throw new Exception($"缺 {missing.Count} 张题图（例：{PyRepr(missing.Take(5).ToArray())}），先补齐再打包");

    var sheets = sets.Select(recs => (name: SheetName(recs[0].Set), rows: RowsOfSet(recs))).ToList();
    var names = sheets.Select(s => s.name).ToList();
    if (names.Distinct().Count() != names.Count)
        throw new Exception("卷名重复：" + PyRepr(names.ToArray()));
    if (names.Any(n => n.Length > 31))
        throw new Exception("工作表名超过 31 字符");

    var (pkgDir, pkgZip) = WritePackage(sheets, records, imgDir, emitDir);

    Console.WriteLine($"题图：{records.Count} 张");
    var qcounts = sheets.Select(s => s.rows.Count - 1).Distinct().OrderBy(v => v).ToArray();
    string tail = sheets.Select(s => s.rows.Count).Distinct().Count() == 1
        ? "—" : "（题量不一致，注意）";
    Console.WriteLine($"试卷：{sheets.Count} 套（每套 {PyRepr(qcounts)} 题）{tail}");
    Console.WriteLine("答案分布：" + string.Join(" ",
        "ABCD".Select(k => $"{k}={records.Count(r => r.Answer == k.ToString())}")));
    Console.WriteLine($"表名：{names[0]} … {names[^1]}");
    if (pkgDir != null) Console.WriteLine($"包目录：{pkgDir}");
    Console.WriteLine($"导入包：{pkgZip}（" +
        (new FileInfo(pkgZip).Length / 1048576.0).ToString("0.0", CultureInfo.InvariantCulture) + " MB）");

    if (perSet)
    {
        var made = WriteSplitPackages(sets, imgDir);
        Console.WriteLine($"单卷小包：{made.Count} 个 -> {G.SplitDir}");
    }
    if (tmp != null)
    {
        try { Directory.Delete(tmp, true); } catch { }
    }
}
catch (Exception ex)
{
    Console.Error.WriteLine(ex.Message);
    return 2;
}
return 0;

// ---------------- helpers ----------------

static string ThisDir([CallerFilePath] string p = "") => Path.GetDirectoryName(p)!;

static string BaseName(string name)
{
    int i = name.LastIndexOf('/');
    return i >= 0 ? name[(i + 1)..] : name;
}

static string ColLetter(int idx0)
{
    string s = "";
    int n = idx0 + 1;
    while (n > 0)
    {
        n--;
        int r = n % 26;
        n /= 26;
        s = (char)(65 + r) + s;
    }
    return s;
}

// xml.sax.saxutils.escape：只转义 & < >
static string Esc(string s) =>
    s.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;");

static string SheetName(int setNo) => $"{G.SHEET_PREFIX}{setNo:D2}套";

static string OptBase(string image) =>
    image.ToLower().EndsWith(".png") ? image[..^4] : image;

static string[] OptionImageRefs(string image)
{
    string b = OptBase(image);
    return "ABCD".Select(k => $"images/{b}_{k}.png").ToArray();
}

static List<Record> ParseTsv(string path)
{
    var records = new List<Record>();
    var bad = new List<(int lineno, string why)>();
    string text = File.ReadAllText(path, Encoding.UTF8);   // utf-8-sig：去掉 BOM
    if (text.Length > 0 && text[0] == '\uFEFF') text = text[1..];
    var lines = text.Split('\n');
    for (int li = 0; li < lines.Length; li++)
    {
        int lineno = li + 1;
        string line = lines[li].TrimEnd('\r', '\n');
        if (string.IsNullOrWhiteSpace(line) || line.StartsWith("#")) continue;
        var cells = line.Split('\t');
        if (cells.Length < 2) { bad.Add((lineno, "列数不足")); continue; }
        var src = G.IMG_SRC.Match(cells[0]);
        string image = src.Success ? BaseName(src.Groups[1].Value).Trim() : "";
        string answer = cells[1].Trim().ToUpper();
        string label = cells.Length > 2 ? cells[2].Trim() : "";

        int setNo, qnum;
        var m = G.QNAME.Match(label);
        if (m.Success)
        {
            setNo = int.Parse(m.Groups[1].Value);
            qnum = int.Parse(m.Groups[2].Value);
        }
        else
        {
            var m2 = G.FNAME.Match(image);
            if (!m2.Success) { bad.Add((lineno, $"认不出套号/题号：{label}")); continue; }
            setNo = int.Parse(m2.Groups[1].Value);
            qnum = int.Parse(m2.Groups[2].Value);
        }
        if (image.Length == 0) { bad.Add((lineno, "行内没有 <img src>")); continue; }
        if (answer.Length != 1 || !G.VALID_ANSWERS.Contains(answer[0]))
        {
            bad.Add((lineno, $"答案不是 A-D：{PyRepr(answer)}"));
            answer = "";
        }
        records.Add(new Record(setNo, qnum, image, answer));
    }
    records = records.OrderBy(r => r.Set).ThenBy(r => r.Qnum).ToList();
    if (bad.Count > 0)
    {
        Console.WriteLine($"跳过 {bad.Count} 行（不影响其余题目）：");
        foreach (var b in bad.Take(10)) Console.WriteLine($"    第 {b.lineno} 行：{b.why}");
    }
    if (records.Count == 0) throw new Exception($"TSV 里一条题都没解析出来：{path}");
    return records;
}

static (List<List<Record>>, List<string>) GroupSets(List<Record> records)
{
    var sets = new Dictionary<int, List<Record>>();
    foreach (var r in records)
    {
        if (!sets.TryGetValue(r.Set, out var l)) { l = new List<Record>(); sets[r.Set] = l; }
        l.Add(r);
    }
    var problems = new List<string>();
    foreach (int setNo in sets.Keys.OrderBy(k => k))
    {
        var nums = sets[setNo].Select(r => r.Qnum).ToList();
        var expected = Enumerable.Range(1, nums.Count).ToList();
        if (!nums.SequenceEqual(expected))
            problems.Add($"第 {setNo:D2} 套题号不连续：{PyRepr(nums.Take(30).ToArray())}");
    }
    return (sets.Keys.OrderBy(k => k).Select(k => sets[k]).ToList(), problems);
}

static string SheetXml(List<List<string>> rows)
{
    var sb = new StringBuilder();
    sb.Append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    sb.Append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
    sb.Append("<sheetData>");
    for (int ri = 0; ri < rows.Count; ri++)
    {
        var cells = new StringBuilder();
        for (int ci = 0; ci < rows[ri].Count; ci++)
        {
            string val = rows[ri][ci];
            string refer = ColLetter(ci) + (ri + 1);
            if (val.Length == 0)
                cells.Append($"<c r=\"{refer}\"/>");
            else
                cells.Append($"<c r=\"{refer}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">{Esc(val)}</t></is></c>");
        }
        sb.Append($"<row r=\"{ri + 1}\">{cells}</row>");
    }
    sb.Append("</sheetData>");
    sb.Append("</worksheet>");
    return sb.ToString();
}

// 纯手工拼 OOXML（和原脚本一样，不用 openpyxl / ClosedXML）
static List<(string name, string data)> BuildXlsx(List<(string name, List<List<string>> rows)> sheets)
{
    int n = sheets.Count;
    string overrides = string.Concat(Enumerable.Range(0, n).Select(i =>
        "<Override PartName=\"/xl/worksheets/sheet" + (i + 1) + ".xml\" " +
        "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"));
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
    string sheetTags = string.Concat(sheets.Select((s, i) =>
        $"<sheet name=\"{Esc(s.name)}\" sheetId=\"{i + 1}\" r:id=\"rId{i + 1}\"/>"));
    string workbook =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
        + "<sheets>" + sheetTags + "</sheets></workbook>";
    string wbRels =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + string.Concat(Enumerable.Range(0, n).Select(i =>
            "<Relationship Id=\"rId" + (i + 1) + "\" "
            + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" "
            + "Target=\"worksheets/sheet" + (i + 1) + ".xml\"/>"))
        + "</Relationships>";
    var files = new List<(string name, string data)>
    {
        ("[Content_Types].xml", contentTypes),
        ("_rels/.rels", rels),
        ("xl/workbook.xml", workbook),
        ("xl/_rels/workbook.xml.rels", wbRels),
    };
    for (int i = 0; i < sheets.Count; i++)
        files.Add(("xl/worksheets/sheet" + (i + 1) + ".xml", SheetXml(sheets[i].rows)));
    return files;
}

static List<List<string>> RowsOfSet(List<Record> records)
{
    var rows = new List<List<string>> { new List<string>(G.HEADERS) };
    int setNo = records[0].Set;
    for (int i = 0; i < records.Count; i++)
    {
        var q = records[i];
        string stem = $"{SheetName(setNo)}·第{q.Qnum}题";
        var opts = OptionImageRefs(q.Image);
        rows.Add(new List<string>
        {
            G.TYPE,                                  // 题型
            G.SUBJECT,                               // 科目（决定卷内大类）
            (i + 1).ToString(),                      // 题号（卷内连续）
            stem,                                    // 题干（纯文本定位）
            "images/" + q.Image,                     // 图片（题干区）
            "![选项](" + opts[0] + ")",              // 选项A（图）
            "![选项](" + opts[1] + ")",              // 选项B（图）
            "![选项](" + opts[2] + ")",              // 选项C（图）
            "![选项](" + opts[3] + ")",              // 选项D（图）
            q.Answer,                                // 答案
            G.SOURCE,                                // 来源
        });
    }
    return rows;
}

static (string tmp, string tsv, string imgDir) ExtractPackage(string zipPath)
{
    string tmp = Path.Combine(Path.GetTempPath(), "tuitui_src_" + Path.GetRandomFileName());
    Directory.CreateDirectory(tmp);
    using (var z = ZipFile.OpenRead(zipPath))
    {
        foreach (var info in z.Entries)
        {
            if (info.FullName.EndsWith("/")) continue;      // is_dir()
            string name = info.FullName.Replace('\\', '/');
            string lower = name.ToLower();
            string target;
            if (lower.EndsWith(".tsv") || lower.EndsWith(".txt"))
                target = Path.Combine(tmp, BaseName(name));
            else if (("/" + name).Contains("/images/") || lower.EndsWith(".png"))
            {
                string baseN = BaseName(name);
                target = Path.Combine(tmp, "images", baseN);
                if (!baseN.ToLower().EndsWith(".png")) continue;
            }
            else continue;
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            using var src = info.Open();
            using var dst = File.Create(target);
            src.CopyTo(dst);
        }
    }
    string tsv = Path.Combine(tmp, "anki_import.tsv");
    if (!File.Exists(tsv))
    {
        var cands = Directory.GetFiles(tmp).Where(f => f.ToLower().EndsWith(".tsv"))
                             .Select(Path.GetFileName).ToList();
        if (cands.Count == 0) throw new Exception($"压缩包里没找到 tsv：{zipPath}");
        tsv = Path.Combine(tmp, cands[0]!);
    }
    return (tmp, tsv, Path.Combine(tmp, "images"));
}

static List<string> CheckImages(List<Record> records, string imgDir) =>
    records.Where(r => !File.Exists(Path.Combine(imgDir, r.Image)))
           .Select(r => r.Image).ToList();

static L8Image ToL8(RgbImage rgb)
{
    var img = new L8Image(rgb.Width, rgb.Height);
    for (int y = 0; y < rgb.Height; y++)
        for (int x = 0; x < rgb.Width; x++)
        {
            var p = rgb[x, y];
            img[x, y] = new L8((byte)(0.299 * p.R + 0.587 * p.G + 0.114 * p.B + 0.5));
        }
    return img;
}

// 存 PNG：黑白扫描件转灰度存（体积减半），真彩色保持 RGB
static byte[] PngBytes(RgbImage im)
{
    long total = (long)im.Width * im.Height;
    long colored = 0;
    for (int y = 0; y < im.Height; y++)
        for (int x = 0; x < im.Width; x++)
        {
            var p = im[x, y];
            int mx = Math.Max(p.R, Math.Max(p.G, p.B));
            int mn = Math.Min(p.R, Math.Min(p.G, p.B));
            if (mx - mn > 24) colored++;
        }
    bool toGray = total > 0 && colored / (double)total < 0.01;
    using var ms = new MemoryStream();
    if (toGray) { using var l8 = ToL8(im); l8.SaveAsPng(ms); }
    else im.SaveAsPng(ms);
    return ms.ToArray();
}

static RgbImage CropRgb(RgbImage src, int x0, int y0, int x1, int y1)
{
    int w = Math.Max(0, x1 - x0), h = Math.Max(0, y1 - y0);
    var img = new RgbImage(w, h, 255, 255, 255);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
        {
            int sx = x0 + x, sy = y0 + y;
            if (sx >= 0 && sy >= 0 && sx < src.Width && sy < src.Height)
                img[x, y] = src[sx, sy];
        }
    return img;
}

static byte[] OptPng(RgbImage im, int[] box)
{
    using var crop = CropRgb(im, box[0], box[1], box[2], box[3]);
    if (crop.Height > 0 && crop.Height < G.OPT_TRIGGER_H)
    {
        int factor = Math.Min(G.OPT_UPSCALE_MAX,
            Math.Max(2, (int)Math.Ceiling(G.OPT_MIN_H / (double)crop.Height)));
        // SkiaSharp：高质量重采样到目标尺寸（对应原 KnownResamplers.Lanczos3）
        using var resized = crop.Resize(crop.Width * factor, crop.Height * factor);
        return PngBytes(resized);
    }
    return PngBytes(crop);
}

static byte[] XlsxBytes(List<(string name, List<List<string>> rows)> sheets)
{
    using var ms = new MemoryStream();
    using (var z = new ZipArchive(ms, ZipArchiveMode.Create, true))
    {
        foreach (var (name, data) in BuildXlsx(sheets))
        {
            var e = z.CreateEntry(name, CompressionLevel.Optimal);
            using var w = new StreamWriter(e.Open(), new UTF8Encoding(false));
            w.Write(data);
        }
    }
    return ms.ToArray();
}

static Dictionary<string, ManualRect> ManualRects()
{
    var res = new Dictionary<string, ManualRect>();
    string path = Path.Combine(G.Here, "manual_rects.json");
    if (!File.Exists(path)) return res;
    using var doc = JsonDocument.Parse(File.ReadAllText(path));
    foreach (var p in doc.RootElement.EnumerateObject())
    {
        if (p.Name.StartsWith("_")) continue;
        var mr = new ManualRect
        {
            StemBox = p.Value.GetProperty("stem_box").EnumerateArray()
                       .Select(v => v.GetInt32()).ToArray(),
        };
        if (p.Value.TryGetProperty("whiteout", out var wo))
            mr.Whiteout = wo.EnumerateArray()
                .Select(a => a.EnumerateArray().Select(v => v.GetInt32()).ToArray()).ToList();
        mr.Options = p.Value.GetProperty("options").EnumerateArray()
            .Select(a => a.EnumerateArray().Select(v => v.GetInt32()).ToArray()).ToList();
        res[p.Name] = mr;
    }
    return res;
}

static (Dictionary<string, byte[]>, bool) QuestionImages(
    string src, string image, Dictionary<string, ManualRect> manual)
{
    string baseN = OptBase(image);
    var items = new Dictionary<string, byte[]>();
    string key = Path.GetFileNameWithoutExtension(image);   // os.path.splitext(image)[0]
    if (manual.TryGetValue(key, out var ov))
    {
        using var im = RgbImage.Load(src);
        var sb = ov.StemBox;
        using var stem = CropRgb(im, sb[0], sb[1], sb[2], sb[3]);
        // 把选项列从题干图里涂白：题目和选项彻底分开，且题干不漏内容
        foreach (var wx in ov.Whiteout)
            for (int y = wx[1] - sb[1]; y <= wx[3] - sb[1] && y < stem.Height; y++)
                for (int x = wx[0] - sb[0]; x <= wx[2] - sb[0] && x < stem.Width; x++)
                    if (y >= 0 && x >= 0) stem[x, y] = new Rgb24(255, 255, 255);
        items[image] = PngBytes(stem);
        for (int i = 0; i < 4; i++)
            items[$"{baseN}_{"ABCD"[i]}.png"] = OptPng(im, ov.Options[i]);
        return (items, true);
    }
    try
    {
        var res = SplitQuestion(src);
        using var im = RgbImage.Load(src);
        var sb = res.StemBox;
        using var stem = CropRgb(im, sb[0], sb[1], sb[2], sb[3]);
        items[image] = PngBytes(stem);
        for (int i = 0; i < 4; i++)
            items[$"{baseN}_{"ABCD"[i]}.png"] = OptPng(im, res.Options[i]);
        return (items, true);
    }
    catch (Exception e)
    {
        Console.WriteLine($"  拆分失败、整图兜底：{image}（{e.Message}）");
        using var im = RgbImage.Load(src);
        int w = im.Width, h = im.Height;
        items[image] = PngBytes(im);
        int top = (int)(h * 0.55);
        int cw = w / 4;
        for (int i = 0; i < 4; i++)
        {
            using var crop = CropRgb(im, i * cw, top, (i + 1) * cw, h);
            items[$"{baseN}_{"ABCD"[i]}.png"] = PngBytes(crop);
        }
        return (items, false);
    }
}

static (string?, string) WritePackage(
    List<(string name, List<List<string>> rows)> sheets, List<Record> records,
    string imgDir, bool emitDir)
{
    if (File.Exists(G.PkgZip)) File.Delete(G.PkgZip);
    string outImages = Path.Combine(G.PkgDir, "images");
    if (emitDir) Directory.CreateDirectory(outImages);

    byte[] xlsxBytes = XlsxBytes(sheets);
    if (emitDir) File.WriteAllBytes(Path.Combine(G.PkgDir, "试卷.xlsx"), xlsxBytes);

    Console.WriteLine($"拆分题干/选项图（{records.Count} 题）…");
    var manual = ManualRects();
    int ok = 0;
    using (var z = ZipFile.Open(G.PkgZip, ZipArchiveMode.Create))
    {
        var xe = z.CreateEntry(G.PkgName + "/试卷.xlsx", CompressionLevel.Optimal);
        using (var xw = xe.Open()) xw.Write(xlsxBytes, 0, xlsxBytes.Length);
        foreach (var r in records)
        {
            string src = Path.Combine(imgDir, r.Image);
            var (items, good) = QuestionImages(src, r.Image, manual);
            foreach (var kv in items)
            {
                var e = z.CreateEntry(G.PkgName + "/images/" + kv.Key, CompressionLevel.NoCompression);
                using var w = e.Open();
                w.Write(kv.Value, 0, kv.Value.Length);
                if (emitDir) File.WriteAllBytes(Path.Combine(outImages, kv.Key), kv.Value);
            }
            if (good) ok++;
        }
    }
    Console.WriteLine($"  拆分完成：{ok} / {records.Count} 题");
    return (emitDir ? G.PkgDir : null, G.PkgZip);
}

static List<string> WriteSplitPackages(List<List<Record>> sets, string imgDir)
{
    Directory.CreateDirectory(G.SplitDir);
    var manual = ManualRects();
    var made = new List<string>();
    foreach (var recs in sets)
    {
        string name = SheetName(recs[0].Set);
        string zpath = Path.Combine(G.SplitDir, name + ".zip");
        if (File.Exists(zpath)) File.Delete(zpath);
        using (var z = ZipFile.Open(zpath, ZipArchiveMode.Create))
        {
            byte[] xb = XlsxBytes(new List<(string, List<List<string>>)> { (name, RowsOfSet(recs)) });
            var xe = z.CreateEntry(name + "/试卷.xlsx", CompressionLevel.Optimal);
            using (var xw = xe.Open()) xw.Write(xb, 0, xb.Length);
            foreach (var r in recs)
            {
                var (items, _) = QuestionImages(Path.Combine(imgDir, r.Image), r.Image, manual);
                foreach (var kv in items)
                {
                    var e = z.CreateEntry(name + "/images/" + kv.Key, CompressionLevel.NoCompression);
                    using var w = e.Open();
                    w.Write(kv.Value, 0, kv.Value.Length);
                }
            }
        }
        made.Add(zpath);
    }
    return made;
}

// Python 列表/字典的近似 repr（控制台文案用）
static string PyRepr(object? v)
{
    if (v == null) return "None";
    if (v is string s) return "'" + s.Replace("\\", "\\\\").Replace("'", "\\'") + "'";
    if (v is char c) return "'" + c + "'";
    if (v is bool b) return b ? "True" : "False";
    if (v is int i) return i.ToString(CultureInfo.InvariantCulture);
    if (v is double d) return d.ToString("R", CultureInfo.InvariantCulture);
    if (v is System.Collections.IDictionary dict)
    {
        var parts = new List<string>();
        foreach (System.Collections.DictionaryEntry e in dict)
            parts.Add(PyRepr(e.Key) + ": " + PyRepr(e.Value));
        return "{" + string.Join(", ", parts) + "}";
    }
    if (v is System.Collections.IEnumerable en)
    {
        var parts = new List<string>();
        foreach (var e in en) parts.Add(PyRepr(e));
        return "[" + string.Join(", ", parts) + "]";
    }
    return v.ToString() ?? "";
}

// ============================================================================
// 内联自 tools/qbank/split_options.cs —— split_question 及其全部依赖函数的忠实翻译。
// 原脚本里 _block_bin / _label / _fig_boxes / _merge / _row_count / _is_label_row /
// _grid_cells / _order_cells / crop_images 只被定义、不被 split_question 调用，
// 依任务要求（只内联 split_question 及其依赖函数）未内联。
// 常量 BIN_THRESH=140 用于 _binarize；BLOCK/BAND_MIN/AREA_MIN/MARGIN/MERGE_PAD
// 仅被上述未内联函数使用，故保留为常量注释说明。
// numpy 的二维 bool 数组用手写 Bin（row-major bool[]）替代，轴投影手写。
// ============================================================================

static Bin Binarize(RgbImage im)
{
    using var l8 = ToL8(im);
    var b = new Bin(l8.Height, l8.Width);
    for (int y = 0; y < l8.Height; y++)
        for (int x = 0; x < l8.Width; x++)
            b[y, x] = l8[x, y].PackedValue < 140;      // BIN_THRESH
    return b;
}

// _blank_runs：找 any_arr 里连续 False（空白）的段，长度 >= minlen
static List<(int, int)> BlankRuns(bool[] anyArr, int minlen)
{
    var runs = new List<(int, int)>();
    int s = -1;
    for (int i = 0; i < anyArr.Length; i++)
    {
        if (!anyArr[i]) { if (s < 0) s = i; }
        else { if (s >= 0) { if (i - s >= minlen) runs.Add((s, i)); s = -1; } }
    }
    if (s >= 0 && anyArr.Length - s >= minlen) runs.Add((s, anyArr.Length));
    return runs;
}

// _on_groups：一维投影里「有墨」的连续区段，间隔 < min_gap 的合并
static List<int[]> OnGroups(int[] profile, double thresh, int minGap, int length)
{
    var on = profile.Select(v => v >= thresh).ToArray();
    var runs = new List<int[]>();
    int s = -1;
    for (int i = 0; i < on.Length; i++)
    {
        if (on[i]) { if (s < 0) s = i; }
        else { if (s >= 0) { runs.Add(new[] { s, i }); s = -1; } }
    }
    if (s >= 0) runs.Add(new[] { s, length });
    var merged = new List<int[]>();
    foreach (var r in runs)
    {
        if (merged.Count > 0 && r[0] - merged[^1][1] < minGap)
            merged[^1][1] = r[1];
        else merged.Add(new[] { r[0], r[1] });
    }
    return merged;
}

// _fit_groups：把分组数调整到恰好 n（多了合并最近两组，少了拆最宽一组）
static List<int[]>? FitGroups(List<int[]> groups, int n)
{
    var g = groups.Select(x => new[] { x[0], x[1] }).ToList();
    if (g.Count == 0) return null;
    while (g.Count > n)
    {
        int bi = 0, bg = int.MaxValue;
        for (int i = 0; i < g.Count - 1; i++)
        {
            int gap = g[i + 1][0] - g[i][1];
            if (gap < bg) { bg = gap; bi = i; }
        }
        g[bi][1] = g[bi + 1][1];
        g.RemoveAt(bi + 1);
    }
    while (g.Count < n)
    {
        int wi = 0, wmax = int.MinValue;
        for (int i = 0; i < g.Count; i++)
        {
            int wd = g[i][1] - g[i][0];
            if (wd > wmax) { wmax = wd; wi = i; }
        }
        int a = g[wi][0], b = g[wi][1];
        int m = (a + b) / 2;
        if (m <= a || m >= b) return null;
        g[wi] = new[] { a, m };
        g.Insert(wi + 1, new[] { m, b });
    }
    return g;
}

// _tighten：把框收紧到墨迹包围盒
static int[] Tighten(Bin binFull, int[] box)
{
    int x0 = box[0], y0 = box[1];
    int x1 = Math.Min(box[2], binFull.W), y1 = Math.Min(box[3], binFull.H);
    if (x1 <= x0 || y1 <= y0) return box;
    var sub = binFull.Sub(y0, y1, x0, x1);
    if (sub.Sum() == 0) return box;
    var bb = sub.BBox()!;
    return new[] { x0 + bb[0], y0 + bb[1], x0 + bb[2], y0 + bb[3] };
}

// _ink_bands：横向「内容带」，有墨的行连成带，间隙 < gap 的合并
static List<(int, int)> InkBands(Bin binFull, int w, int gap = 6)
{
    int h = binFull.H;
    var rowInk = binFull.RowSum();
    double thr = Math.Max(2, (int)(0.004 * w));
    var on = rowInk.Select(v => v >= thr).ToArray();
    var bands = new List<(int, int)>();
    int s = -1;
    for (int i = 0; i < h; i++)
    {
        if (on[i]) { if (s < 0) s = i; }
        else { if (s >= 0) { bands.Add((s, i)); s = -1; } }
    }
    if (s >= 0) bands.Add((s, h));
    var merged = new List<(int, int)>();
    foreach (var b in bands)
    {
        if (merged.Count > 0 && b.Item1 - merged[^1].Item2 < gap)
            merged[^1] = (merged[^1].Item1, b.Item2);
        else merged.Add(b);
    }
    return merged;
}

// _drop_rules：丢掉「很扁(<=6px)且近乎贯穿全宽」的表格横线 / 边框带
static List<(int, int)> DropRules(List<(int, int)> bands, Bin binFull, int w)
{
    var outp = new List<(int, int)>();
    foreach (var (s, e) in bands)
    {
        if (e - s <= 6)
        {
            int span = binFull.Sub(s, e, 0, binFull.W).ColAny().Count(v => v);
            if (span >= 0.85 * w) continue;
        }
        outp.Add((s, e));
    }
    return outp;
}

// _run_mask：mask 里连续 True 的区段 [(s,e)]，长度落在 [minlen, maxlen]
static List<(int, int)> RunMask(bool[] mask, int minlen = 0, int maxlen = int.MaxValue)
{
    var outp = new List<(int, int)>();
    int s = -1;
    for (int i = 0; i < mask.Length; i++)
    {
        if (mask[i]) { if (s < 0) s = i; }
        else
        {
            if (s >= 0)
            {
                if (minlen <= i - s && i - s <= maxlen) outp.Add((s, i));
                s = -1;
            }
        }
    }
    if (s >= 0 && minlen <= mask.Length - s && mask.Length - s <= maxlen)
        outp.Add((s, mask.Length));
    return outp;
}

// _strip_rules：把表格框线（细且贯穿整块高/宽的横竖线）抹掉再统计投影
static Bin StripRules(Bin block)
{
    var b = block.Clone();
    int rh = b.H, rw = b.W;
    if (rh < 6 || rw < 6) return b;
    var colInk = b.ColSum();
    foreach (var (s, e) in RunMask(colInk.Select(v => v >= 0.88 * rh).ToArray(), 0, 6))
        for (int y = 0; y < rh; y++)
            for (int x = s; x < e && x < rw; x++) b[y, x] = false;
    var rowInk = b.RowSum();
    foreach (var (s, e) in RunMask(rowInk.Select(v => v >= 0.90 * rw).ToArray(), 0, 6))
        for (int y = s; y < e && y < rh; y++)
            for (int x = 0; x < rw; x++) b[y, x] = false;
    return b;
}

// _v_rule_positions：细(<=6px)且几乎贯穿整块高的列，返回每段的中心
static int[] VRulePositions(Bin block, double ratio = 0.85)
{
    int rh = block.H;
    if (rh < 36) return Array.Empty<int>();
    var colInk = block.ColSum();
    var runs = RunMask(colInk.Select(v => v >= ratio * rh).ToArray(), 0, 6);
    return runs.Select(r => (r.Item1 + r.Item2) / 2).ToArray();
}

// _split_at_rules：把跨越「长横线」的内容带在这些线处切开（去掉线本身）
static List<(int, int)> SplitAtRules(List<(int, int)> bands, Bin binFull, int minH = 8)
{
    var outp = new List<(int, int)>();
    foreach (var (s, e) in bands)
    {
        var sub = binFull.Sub(s, e, 0, binFull.W);
        var colAny = sub.ColAny();
        var cols = Enumerable.Range(0, colAny.Length).Where(i => colAny[i]).ToArray();
        if (cols.Length == 0) { outp.Add((s, e)); continue; }
        int x0 = cols.Min(), x1 = cols.Max() + 1;
        int span = x1 - x0;
        if (span < 40) { outp.Add((s, e)); continue; }
        var cuts = new List<int>();
        for (int y = 0; y < sub.H; y++)
        {
            int rowInk = sub.RegionSum(y, y + 1, x0, x1);
            if (rowInk < 0.6 * span) continue;
            int best = 0, c = 0;
            for (int x = x0; x < x1; x++)
            { c = sub[y, x] ? c + 1 : 0; if (c > best) best = c; }
            if (best >= 0.85 * span) cuts.Add(y);
        }
        if (cuts.Count == 0) { outp.Add((s, e)); continue; }
        int cur = s;
        foreach (int y in cuts)
        {
            int cut = s + y;
            if (cut - cur >= minH) outp.Add((cur, cut));
            cur = cut + 1;
        }
        if (e - cur >= minH) outp.Add((cur, e));
    }
    return outp;
}

// _col_groups：一行内的列分组（文字行用更大的合并间隙）
static List<int[]> ColGroups(Bin rowblock, int w, bool textLike)
{
    int rh = rowblock.H;
    int gap = textLike ? 42 : Math.Max(12, w / 90);
    var cg = OnGroups(rowblock.ColSum(), Math.Max(1, rh / 300), gap, w);
    return cg.Where(g => g[1] - g[0] >= 8).ToList();
}

// _is_label_band：整条带是不是「A B C D」这种单字母标签行
static bool IsLabelBand(Bin block, int w)
{
    int rh = block.H;
    if (!(6 <= rh && rh <= 26)) return false;
    var cg = ColGroups(block, w, true);
    if (cg.Count != 4) return false;
    if (cg.Max(g => g[1] - g[0]) > 24) return false;
    var inks = cg.Select(g => block.RegionSum(0, rh, g[0], g[1])).ToArray();
    return inks.Min() >= 3;
}

// _rule_span：在 x 列上找最长的一段连续墨迹（= 单元格高度）
static (int, int)? RuleSpan(Bin block, int x)
{
    if (x < 0 || x >= block.W) return null;
    int cx0 = Math.Max(0, x - 1), cx1 = Math.Min(block.W, x + 2);
    var col = new bool[block.H];
    for (int y = 0; y < block.H; y++)
    {
        bool any = false;
        for (int xx = cx0; xx < cx1; xx++) if (block[y, xx]) { any = true; break; }
        col[y] = any;
    }
    (int, int)? best = null;
    int s = -1;
    for (int i = 0; i < col.Length; i++)
    {
        if (col[i]) { if (s < 0) s = i; }
        else
        {
            if (s >= 0)
            {
                if (best == null || i - s > best.Value.Item2 - best.Value.Item1)
                    best = (s, i);
                s = -1;
            }
        }
    }
    if (s >= 0 && (best == null || col.Length - s > best.Value.Item2 - best.Value.Item1))
        best = (s, col.Length);
    return best;
}

// _cell_y：用框线自身的跨度确定单元格上下边界
static (int, int)? CellY(Bin block, int[] vr, int[] reference)
{
    var spans = new List<(int, int)>();
    foreach (int x in vr)
    {
        var sp = RuleSpan(block, x);
        if (sp != null) spans.Add(sp.Value);
    }
    if (spans.Count == 0) return null;
    int y0 = spans.Min(s => s.Item1), y1 = spans.Max(s => s.Item2);
    if (y1 - y0 < 10) return null;
    if (!(y0 - 6 <= reference[0] && reference[1] <= y1 + 6)) return null;
    int pad = (int)(0.35 * (reference[1] - reference[0]));
    return (Math.Max(y0, reference[0] - pad), Math.Min(y1, reference[1] + pad));
}

// _partition：把一块区域按实际墨迹切成 4 个格子
static (int, int, List<int[]>)? Partition(Bin block)
{
    var b = StripRules(block);
    int rh = b.H, rw = b.W;
    if (rh < 8 || rw < 8) return null;
    var rg = OnGroups(b.RowSum(), 1, 5, rh).Where(g => g[1] - g[0] >= 5).ToList();
    if (rg.Count == 0) return null;
    var inks = rg.Select(g => b.RegionSum(g[0], g[1], 0, rw)).ToArray();
    int argmax = 0;
    for (int i = 1; i < inks.Length; i++) if (inks[i] > inks[argmax]) argmax = i;
    var main = rg[argmax];

    var vr = VRulePositions(block, 0.85).ToList();
    int[] reference = main;
    if (!(vr.Count >= 3 && vr.Count <= 5))
        foreach (var g in rg.OrderByDescending(g => g[1] - g[0]))
        {
            var v = VRulePositions(block.Sub(g[0], g[1], 0, rw), 0.85).ToList();
            if (v.Count >= 3 && v.Count <= 5) { vr = v; reference = g; break; }
        }
    if (vr.Count == 5)
    {
        var span = CellY(block, vr.ToArray(), reference) ?? (main[0], main[1]);
        var cells = Enumerable.Range(0, 4)
            .Select(j => new[] { vr[j] + 3, span.Item1, vr[j + 1] - 3, span.Item2 }).ToList();
        if (cells.All(c => c[2] - c[0] >= 12)) return (1, 4, cells);
    }
    if (vr.Count == 2)
    {
        var span = CellY(block, vr.ToArray(), main) ?? (main[0], main[1]);
        int width = vr[1] - vr[0];
        if (width >= 160)
        {
            double step = width / 4.0;
            var cells = Enumerable.Range(0, 4)
                .Select(j => new[]
                { (int)(vr[0] + j * step) + 2, span.Item1,
                  (int)(vr[0] + (j + 1) * step) - 2, span.Item2 }).ToList();
            if (cells.All(c => c[2] - c[0] >= 12)) return (1, 4, cells);
        }
    }
    if (vr.Count == 3 && rg.Count >= 2)
    {
        var two = rg.OrderByDescending(g => b.RegionSum(g[0], g[1], 0, rw))
                    .Take(2).OrderBy(g => g[0]).ToList();
        var cells = new List<int[]>();
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++)
                cells.Add(new[] { vr[j] + 3, two[i][0], vr[j + 1] - 3, two[i][1] });
        if (cells.All(c => c[2] - c[0] >= 12)) return (2, 2, cells);
    }

    int nr = rg.Count;
    bool textLike = rg.Max(g => g[1] - g[0]) <= 34;
    var perRow = rg.Select(g => ColGroups(b.Sub(g[0], g[1], 0, rw), rw, textLike)).ToList();
    if (nr == 1)
    {
        var cg = perRow[0];
        if (!(cg.Count >= 3 && cg.Count <= 6)) return null;
        var cols = FitGroups(cg, 4);
        if (cols == null) return null;
        int y0 = rg[0][0], y1 = rg[0][1];
        return (1, 4, Enumerable.Range(0, 4)
            .Select(j => new[] { cols[j][0], y0, cols[j][1], y1 }).ToList());
    }
    if (nr == 2)
    {
        var g0 = perRow[0];
        var g1 = perRow[1];
        if (!(g0.Count >= 2 && g0.Count <= 3 && g1.Count >= 2 && g1.Count <= 3)) return null;
        var c0 = FitGroups(g0, 2);
        var c1 = FitGroups(g1, 2);
        if (c0 == null || c1 == null) return null;
        var cells = new List<int[]>();
        for (int j = 0; j < 2; j++) cells.Add(new[] { c0[j][0], rg[0][0], c0[j][1], rg[0][1] });
        for (int j = 0; j < 2; j++) cells.Add(new[] { c1[j][0], rg[1][0], c1[j][1], rg[1][1] });
        return (2, 2, cells);
    }
    if (nr == 4)
    {
        if (!perRow.All(g => g.Count >= 1 && g.Count <= 2)) return null;
        var widths = new List<int[]>();
        foreach (var g in perRow)
        {
            var gg = FitGroups(g, 1);
            if (gg == null) return null;
            widths.Add(gg[0]);
        }
        return (4, 1, Enumerable.Range(0, 4)
            .Select(i => new[] { widths[i][0], rg[i][0], widths[i][1], rg[i][1] }).ToList());
    }
    return null;
}

// _tighten_opt：收紧选项框，并丢掉贴在外边缘的那条表格框线
static int[] TightenOpt(Bin binFull, int[] box)
{
    int x0 = box[0], y0 = box[1];
    int x1 = Math.Min(box[2], binFull.W), y1 = Math.Min(box[3], binFull.H);
    if (x1 <= x0 || y1 <= y0) return box;
    var sub = binFull.Sub(y0, y1, x0, x1);
    if (sub.Sum() == 0) return box;
    var bb = sub.BBox()!;
    int t = bb[1], bot = bb[3] - 1, l = bb[0], r = bb[2] - 1;
    int wdt = r - l + 1;
    bool FullRow(int y) => sub.RegionSum(y, y + 1, l, r + 1) >= 0.9 * wdt;
    while (t < bot && bot - t > 8 && FullRow(t)) t++;
    while (bot > t && bot - t > 8 && FullRow(bot)) bot--;
    int hgt = bot - t + 1;
    bool FullCol(int x) => sub.RegionSum(t, bot + 1, x, x + 1) >= 0.9 * hgt;
    while (l < r && r - l > 8 && FullCol(l)) l++;
    while (r > l && r - l > 8 && FullCol(r)) r--;
    return new[] { x0 + l, y0 + t, x0 + r + 1, y0 + bot + 1 };
}

// _looks_like_options
static bool LooksLikeOptions(List<int[]> opts, Bin binFull, int w, int h)
{
    if (opts.Count != 4) return false;
    var inks = new List<int>();
    var ws = new List<int>();
    var hs = new List<int>();
    foreach (var b in opts)
    {
        int x0 = b[0], y0 = b[1], x1 = b[2], y1 = b[3];
        if (x1 <= x0 || y1 <= y0) return false;
        inks.Add(binFull.RegionSum(y0, y1, x0, x1));
        ws.Add(x1 - x0);
        hs.Add(y1 - y0);
    }
    if (inks.Min() < 40 || ws.Min() < 8 || hs.Min() < 6) return false;
    if (inks.Max() > 0 && inks.Min() / (double)inks.Max() < 0.15) return false;
    if (hs.Max() > 0 && hs.Min() / (double)hs.Max() < 0.25) return false;
    if (opts.Min(b => b[1]) < 0.30 * h) return false;
    return true;
}

// _find_option_block：自下而上拼「选项块」并打分选最优
static (List<int[]>, int, int, int)? FindOptionBlock(Bin binFull, int w, int h)
{
    var bands = DropRules(InkBands(binFull, w), binFull, w);
    bands = SplitAtRules(bands, binFull);
    // 尾部「字母标签行」先剔掉
    while (bands.Count > 0)
    {
        var (s, e) = bands[^1];
        if (IsLabelBand(binFull.Sub(s, e, 0, w), w)) { bands.RemoveAt(bands.Count - 1); continue; }
        break;
    }
    (double bal, int negK, List<int[]> opts, int nrow, int ncol, int top)? best = null;
    for (int k = 1; k <= bands.Count; k++)
    {
        var block = bands.GetRange(bands.Count - k, k);
        int top = block[0].Item1, bot = block[^1].Item2;
        if (bot - top > 0.88 * h) break;
        var built = Partition(binFull.Sub(top, bot, 0, w));
        if (built == null) continue;
        var (nrow, ncol, cells) = built.Value;
        var opts = cells.Select(c => TightenOpt(binFull,
            new[] { c[0], top + c[1], c[2], top + c[3] })).ToList();
        if (!LooksLikeOptions(opts, binFull, w, h)) continue;
        var inks = opts.Select(b => binFull.RegionSum(b[1], b[3], b[0], b[2])).ToArray();
        double bal = inks.Max() > 0 ? inks.Min() / (double)inks.Max() : 0;
        double bal3 = Math.Round(bal, 3, MidpointRounding.ToEven);
        int negK = -k;
        if (best == null || bal3 > best.Value.bal ||
            (bal3 == best.Value.bal && negK > best.Value.negK))
            best = (bal3, negK, opts, nrow, ncol, top);
    }
    if (best == null) return null;
    return (best.Value.opts, best.Value.nrow, best.Value.ncol, best.Value.top);
}

// _build_cells：在选项区域内按实际墨迹分组切出 4 格
static (int, int, List<int[]>)? BuildCells(Bin region, int top, int w, int h, string? force)
{
    int rh = region.H, rw = region.W;
    if (rh < 8 || rw < 8) return null;
    var cg = OnGroups(region.ColSum(), Math.Max(1, rh / 300),
                      Math.Max(10, rw / 100), rw)
             .Where(g => g[1] - g[0] >= 10).ToList();
    if (cg.Count == 0) return null;
    var rg = OnGroups(region.RowSum(), 1, 6, rh);
    if (rg.Count > 0) rg = rg.Where(g => g[1] - g[0] >= 6).ToList();
    int nr = rg.Count > 0 ? rg.Count : 1;
    bool is2x2;
    if (force == "1x4") is2x2 = false;
    else if (force == "2x2") is2x2 = true;
    else is2x2 = nr >= 2 && cg.Count <= 2;
    if (!is2x2)
    {
        var cols = FitGroups(cg, 4);
        if (cols == null) return null;
        int y0 = rg.Count > 0 ? rg[0][0] : 0;
        int y1 = rg.Count > 0 ? rg[^1][1] : rh;
        return (1, 4, Enumerable.Range(0, 4)
            .Select(j => new[] { cols[j][0], top + y0, cols[j][1], top + y1 }).ToList());
    }
    else
    {
        var rows = FitGroups(rg, 2);
        var cols = FitGroups(cg, 2);
        if (rows == null || cols == null) return null;
        var cells = new List<int[]>();
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++)
                cells.Add(new[] { cols[j][0], top + rows[i][0], cols[j][1], top + rows[i][1] });
        return (2, 2, cells);
    }
}

// split_question 主入口
static SplitResult SplitQuestion(string imgPath)
{
    using var im = RgbImage.Load(imgPath);
    int w = im.Width, h = im.Height;
    var binFull = Binarize(im);
    var found = FindOptionBlock(binFull, w, h);
    if (found != null)
    {
        var (options, nrow, ncol, _) = found.Value;
        int optTop = options.Min(b => b[1]);
        int stemBottom = Math.Max(0, optTop - 2);
        if (stemBottom < 0.10 * h) stemBottom = (int)(0.10 * h);
        var stemBox = Tighten(binFull, new[] { 0, 0, w, stemBottom });
        if (stemBox[3] - stemBox[1] < 20)
            stemBox = new[] { 0, 0, w, Math.Max(20, (int)(0.10 * h)) };
        if (stemBox[3] > optTop) stemBox[3] = optTop;
        var areas = options.Select(b => binFull.RegionSum(b[1], b[3], b[0], b[2])).ToArray();
        int mx = areas.Length > 0 ? areas.Max() : 0;
        bool lowConf = mx == 0 || (areas.Length > 0 && areas.Min() / (double)mx < 0.30);
        return new SplitResult
        {
            StemBox = stemBox, Options = options, Grid = $"{nrow}x{ncol}",
            Method = "block", W = w, H = h, LowConf = lowConf,
        };
    }
    // 原 _split_legacy(img_path) 会重新打开文件；此处复用已算好的 binFull，行为一致
    return SplitLegacyCore(binFull, w, h);
}

static SplitResult SplitLegacyCore(Bin binFull, int w, int h)
{
    var rowAny = binFull.RowAny();
    var rowInk = binFull.RowSum();
    var hasInk = rowInk.Select(v => v >= 0.05 * w).ToArray();
    var cand = BlankRuns(hasInk, 6)
        .Where(t => t.Item1 >= 0.30 * h && t.Item2 <= 0.97 * h).ToList();

    (int top, int negMinArea, int nrow, int ncol, List<int[]> ordered)? chosen = null;
    foreach (var (s, e) in cand.OrderByDescending(t => t.Item1))
    {
        int top = e;
        var region = binFull.Sub(top, h, 0, w);
        if (region.H < 0.18 * h) continue;
        var built = BuildCells(region, top, w, h, null);
        if (built == null) continue;
        var (nrow, ncol, cells) = built.Value;
        var cellAreas = cells.Select(c => binFull.RegionSum(c[1], c[3], c[0], c[2])).ToArray();
        if (cellAreas.Min() < 150) continue;
        int negMin = -cellAreas.Min();
        if (chosen == null || top > chosen.Value.top ||
            (top == chosen.Value.top && negMin > chosen.Value.negMinArea))
            chosen = (top, negMin, nrow, ncol, cells);
    }

    string method = "band";
    int optTop, nrowF, ncolF, stemBottom;
    List<int[]> ordered;
    if (chosen != null)
    {
        optTop = chosen.Value.top;
        nrowF = chosen.Value.nrow; ncolF = chosen.Value.ncol; ordered = chosen.Value.ordered;
        stemBottom = Math.Max(0, optTop);
    }
    else
    {
        int inkBottom = h - 1;
        for (int y = h - 1; y >= 0; y--) if (rowAny[y]) { inkBottom = y; break; }
        optTop = Math.Min((int)(0.55 * h), inkBottom);
        var region = binFull.Sub(optTop, h, 0, w);
        var built = BuildCells(region, optTop, w, h, null);
        if (built == null)
        {
            nrowF = 1; ncolF = 4;
            int cw = w / 4;
            ordered = Enumerable.Range(0, 4)
                .Select(i => new[] { i * cw, optTop, (i + 1) * cw, h }).ToList();
        }
        else { nrowF = built.Value.Item1; ncolF = built.Value.Item2; ordered = built.Value.Item3; }
        stemBottom = Math.Max(0, optTop);
        method = "fallback_region";
    }

    var options = ordered.Take(4)
        .Select(c => Tighten(binFull, new[] { c[0], c[1], c[2], c[3] })).ToList();
    int[] AreasOf(List<int[]> opts) =>
        opts.Select(b => binFull.RegionSum(b[1], b[3], b[0], b[2])).ToArray();
    double Balance(List<int[]> opts)
    {
        var a = AreasOf(opts);
        int mx = a.Length > 0 ? a.Max() : 0;
        return mx > 0 && a.Length > 0 ? a.Min() / (double)mx : 0.0;
    }

    // 判成 2×2 但四格高度差太大（1×4 被误切成两半）→ 按 1×4 重切
    if (nrowF == 2)
    {
        var hs = options.Select(b => b[3] - b[1]).ToArray();
        if (hs.Min() < 0.55 * hs.Max())
        {
            var built = BuildCells(binFull.Sub(optTop, h, 0, w), optTop, w, h, "1x4");
            if (built != null)
            {
                nrowF = built.Value.Item1; ncolF = built.Value.Item2; ordered = built.Value.Item3;
                options = ordered.Take(4)
                    .Select(c => Tighten(binFull, new[] { c[0], c[1], c[2], c[3] })).ToList();
            }
        }
    }
    // 均衡度重试：四格严重不均时，试试另一种版式，取更均衡的
    if (Balance(options) < 0.30)
    {
        string alt = ncolF == 4 ? "2x2" : "1x4";
        var built = BuildCells(binFull.Sub(optTop, h, 0, w), optTop, w, h, alt);
        if (built != null)
        {
            var (n2r, n2c, cells2) = built.Value;
            var opts2 = cells2.Select(c => Tighten(binFull,
                new[] { c[0], c[1], c[2], c[3] })).ToList();
            if (Balance(opts2) > Balance(options))
            { nrowF = n2r; ncolF = n2c; options = opts2; }
        }
    }

    var areas = AreasOf(options);
    bool lowConf = areas.Min() < 150 ||
                   (areas.Max() > 0 && areas.Min() / (double)areas.Max() < 0.2);
    if (stemBottom < 0.12 * h) stemBottom = (int)(0.12 * h);
    var stemBox = Tighten(binFull, new[] { 0, 0, w, stemBottom });
    if (stemBox[3] - stemBox[1] < 20)
        stemBox = new[] { 0, 0, w, Math.Max(20, (int)(0.12 * h)) };
    return new SplitResult
    {
        StemBox = stemBox, Options = options, Grid = $"{nrowF}x{ncolF}",
        Method = method, W = w, H = h, LowConf = lowConf,
    };
}

// ---------------- 类型声明 ----------------

static class G
{
    public const string SHEET_PREFIX = "图推第";
    public const string SUBJECT = "行测·图形推理";
    public const string TYPE = "单选";
    public const string SOURCE = "图推700题";
    public const string OPTION_TEXT = "见题图";        // 原脚本定义但未使用

    public static readonly string[] HEADERS =
    { "题型", "科目", "题号", "题干", "图片", "选项A", "选项B", "选项C", "选项D", "答案", "来源" };
    public static readonly string[] OPTION_KEYS = { "A", "B", "C", "D" };  // 原脚本定义但未使用

    public const int OPT_MIN_H = 48, OPT_TRIGGER_H = 36, OPT_UPSCALE_MAX = 4;

    public static readonly HashSet<char> VALID_ANSWERS = new("ABCD");
    public static readonly Regex IMG_SRC =
        new(@"<img[^>]*src=[""']([^""']+)[""']", RegexOptions.IgnoreCase | RegexOptions.Compiled);
    public static readonly Regex QNAME =
        new(@"第\s*(\d+)\s*套\s*第\s*(\d+)\s*题", RegexOptions.Compiled);
    public static readonly Regex FNAME =
        new(@"^s(\d+)_q(\d+)\.", RegexOptions.IgnoreCase | RegexOptions.Compiled);

    public static string Here = "", PkgName = "", PkgDir = "", PkgZip = "", SplitDir = "";
}

// 二维布尔掩膜（row-major），替代 numpy 的 bool 二维数组
sealed class Bin
{
    public int H, W;
    public bool[] D;
    public Bin(int h, int w) { H = h; W = w; D = new bool[h * w]; }
    public bool this[int y, int x]
    {
        get => D[y * W + x];
        set => D[y * W + x] = value;
    }
    public bool Any() { foreach (var v in D) if (v) return true; return false; }
    public int Sum() { int n = 0; foreach (var v in D) if (v) n++; return n; }
    public Bin Clone() { var b = new Bin(H, W); Array.Copy(D, b.D, D.Length); return b; }
    public Bin Sub(int y0, int y1, int x0, int x1)
    {
        y0 = Math.Max(0, y0); x0 = Math.Max(0, x0);
        y1 = Math.Min(H, y1); x1 = Math.Min(W, x1);
        int hh = Math.Max(0, y1 - y0), ww = Math.Max(0, x1 - x0);
        var b = new Bin(hh, ww);
        for (int y = 0; y < hh; y++)
            for (int x = 0; x < ww; x++) b[y, x] = D[(y0 + y) * W + (x0 + x)];
        return b;
    }
    public bool[] RowAny()
    {
        var r = new bool[H];
        for (int y = 0; y < H; y++)
        {
            bool any = false;
            for (int x = 0; x < W; x++) if (D[y * W + x]) { any = true; break; }
            r[y] = any;
        }
        return r;
    }
    public bool[] ColAny()
    {
        var r = new bool[W];
        for (int x = 0; x < W; x++)
        {
            bool any = false;
            for (int y = 0; y < H; y++) if (D[y * W + x]) { any = true; break; }
            r[x] = any;
        }
        return r;
    }
    public int[] RowSum()
    {
        var r = new int[H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) if (D[y * W + x]) r[y]++;
        return r;
    }
    public int[] ColSum()
    {
        var r = new int[W];
        for (int x = 0; x < W; x++)
            for (int y = 0; y < H; y++) if (D[y * W + x]) r[x]++;
        return r;
    }
    public int RegionSum(int y0, int y1, int x0, int x1)
    {
        y0 = Math.Max(0, y0); x0 = Math.Max(0, x0);
        y1 = Math.Min(H, y1); x1 = Math.Min(W, x1);
        int n = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) if (D[y * W + x]) n++;
        return n;
    }
    // np.where(sub) 的包围盒 [x0,y0,x1,y1]，空则返回 null
    public int[]? BBox()
    {
        int x0 = W, y0 = H, x1 = 0, y1 = 0;
        bool any = false;
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                if (D[y * W + x])
                {
                    any = true;
                    if (x < x0) x0 = x; if (x > x1) x1 = x;
                    if (y < y0) y0 = y; if (y > y1) y1 = y;
                }
        return any ? new[] { x0, y0, x1 + 1, y1 + 1 } : null;
    }
}

sealed record Record(int Set, int Qnum, string Image, string Answer);

sealed class ManualRect
{
    public int[] StemBox = new int[4];
    public List<int[]> Whiteout = new();
    public List<int[]> Options = new();
}

sealed class SplitResult
{
    public int[] StemBox = new int[4];
    public List<int[]> Options = new();
    public string Grid = "", Method = "";
    public int W, H;
    public bool LowConf;
}

// ============================================================================
// SkiaSharp 图像适配层：SKBitmap 只负责「解码 / Resize 重采样 / PNG 编码」，
// 逐像素运算仍在手写 byte[]（row-major）上进行，阈值与 0.299/0.587/0.114
// 灰度权重全部保留，不使用 SkiaSharp 内置色彩/灰度转换（避免改变阈值判定）。
// ============================================================================
static class ImgHelpers
{
    // SKBitmap -> 紧密 RGB byte[]（每像素 3 字节）。按 ColorType 判断通道顺序，
    // 不做任何内置灰度/色彩转换；RowBytes 可能含行对齐，故按 stride 逐行拷贝。
    public static byte[] BitmapToRgb(SKBitmap bmp, out int w, out int h)
    {
        w = bmp.Width; h = bmp.Height;
        var rgb = new byte[w * h * 3];
        int bpp = bmp.BytesPerPixel, stride = bmp.RowBytes;
        var ct = bmp.ColorType;
        IntPtr basePtr = bmp.GetPixels();
        var row = new byte[stride];
        for (int y = 0; y < h; y++)
        {
            Marshal.Copy(IntPtr.Add(basePtr, y * stride), row, 0, stride);
            for (int x = 0; x < w; x++)
            {
                int si = x * bpp, di = (y * w + x) * 3;
                byte r, g, b;
                if (ct == SKColorType.Gray8) { r = g = b = row[si]; }
                else if (ct == SKColorType.Rgba8888) { r = row[si]; g = row[si + 1]; b = row[si + 2]; }
                else { r = row[si + 2]; g = row[si + 1]; b = row[si]; }   // Bgra8888 及其它 N32
                rgb[di] = r; rgb[di + 1] = g; rgb[di + 2] = b;
            }
        }
        return rgb;
    }

    // 紧密 RGB byte[] -> Bgra8888 的 SKBitmap（SkiaSharp 无 3 字节 Rgb888）。
    public static SKBitmap RgbToBitmap(byte[] rgb, int w, int h)
    {
        var bmp = new SKBitmap(w, h, SKColorType.Bgra8888, SKAlphaType.Opaque);
        int stride = bmp.RowBytes;
        var buf = new byte[stride * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
            {
                int di = y * stride + x * 4, si = (y * w + x) * 3;
                buf[di] = rgb[si + 2]; buf[di + 1] = rgb[si + 1];
                buf[di + 2] = rgb[si]; buf[di + 3] = 255;
            }
        Marshal.Copy(buf, 0, bmp.GetPixels(), buf.Length);
        return bmp;
    }

    // 灰度 byte[] -> Gray8 的 SKBitmap（对应原 Image<L8>，编码成单通道灰度 PNG）。
    public static SKBitmap GrayToBitmap(byte[] gray, int w, int h)
    {
        var bmp = new SKBitmap(w, h, SKColorType.Gray8, SKAlphaType.Opaque);
        int stride = bmp.RowBytes;
        var buf = new byte[stride * h];
        for (int y = 0; y < h; y++)
            Array.Copy(gray, y * w, buf, y * stride, w);
        Marshal.Copy(buf, 0, bmp.GetPixels(), buf.Length);
        return bmp;
    }

    // SKBitmap -> PNG 字节（等价原 SaveAsPng；optimize 由编码器内部处理，像素一致）。
    public static byte[] EncodePng(SKBitmap bmp)
    {
        using var img = SKImage.FromBitmap(bmp);
        using var data = img.Encode(SKEncodedImageFormat.Png, 100);
        return data.ToArray();
    }
}

// 三通道像素，替代原 Rgb24 像素类型（保留 R/G/B 字段名）
readonly struct Rgb24
{
    public readonly byte R, G, B;
    public Rgb24(byte r, byte g, byte b) { R = r; G = g; B = b; }
}

// 单通道灰度像素，替代 L8（保留 PackedValue 字段名）
readonly struct L8
{
    public readonly byte PackedValue;
    public L8(byte v) { PackedValue = v; }
}

// 替代 Image<Rgb24>：内部紧密 RGB byte[]，row-major
sealed class RgbImage : IDisposable
{
    public int Width, Height;
    public byte[] D;
    public RgbImage(byte[] d, int w, int h) { D = d; Width = w; Height = h; }
    public RgbImage(int w, int h, byte r, byte g, byte b)
    {
        Width = w; Height = h; D = new byte[w * h * 3];
        for (int i = 0; i < w * h; i++) { D[i * 3] = r; D[i * 3 + 1] = g; D[i * 3 + 2] = b; }
    }
    public Rgb24 this[int x, int y]
    {
        get { int i = (y * Width + x) * 3; return new Rgb24(D[i], D[i + 1], D[i + 2]); }
        set { int i = (y * Width + x) * 3; D[i] = value.R; D[i + 1] = value.G; D[i + 2] = value.B; }
    }
    public static RgbImage Load(string path)
    {
        using var bmp = SKBitmap.Decode(path);
        if (bmp == null) throw new Exception($"无法解码图片：{path}");
        var rgb = ImgHelpers.BitmapToRgb(bmp, out int w, out int h);
        return new RgbImage(rgb, w, h);
    }
    // 原 Clone(c => c.Resize(w,h,Lanczos3))：改用 SKFilterQuality.High 高质量重采样
    public RgbImage Resize(int w, int h)
    {
        using var bmp = ImgHelpers.RgbToBitmap(D, Width, Height);
        using var rs = bmp.Resize(new SKSizeI(w, h), SKFilterQuality.High);
        if (rs == null) throw new Exception("SkiaSharp Resize 返回 null");
        var rgb = ImgHelpers.BitmapToRgb(rs, out int rw, out int rh);
        return new RgbImage(rgb, rw, rh);
    }
    public void SaveAsPng(Stream s)
    {
        using var bmp = ImgHelpers.RgbToBitmap(D, Width, Height);
        var bytes = ImgHelpers.EncodePng(bmp);
        s.Write(bytes, 0, bytes.Length);
    }
    public void Dispose() { }
}

// 替代 Image<L8>：内部紧密灰度 byte[]（每像素 1 字节），row-major
sealed class L8Image : IDisposable
{
    public int Width, Height;
    public byte[] D;
    public L8Image(byte[] d, int w, int h) { D = d; Width = w; Height = h; }
    public L8Image(int w, int h, byte fill)
    {
        Width = w; Height = h; D = new byte[w * h];
        for (int i = 0; i < D.Length; i++) D[i] = fill;
    }
    public L8Image(int w, int h) : this(w, h, (byte)0) { }   // 同 Image<L8>：默认全 0，随后被逐像素覆盖
    public L8 this[int x, int y]
    {
        get => new L8(D[y * Width + x]);
        set => D[y * Width + x] = value.PackedValue;
    }
    public L8Image Clone() => new L8Image((byte[])D.Clone(), Width, Height);
    // 原 Clone(c => c.Resize(tw,th,Lanczos3))：Gray8 位图上做单通道高质量重采样
    public L8Image Resize(int w, int h)
    {
        using var bmp = ImgHelpers.GrayToBitmap(D, Width, Height);
        using var rs = bmp.Resize(new SKSizeI(w, h), SKFilterQuality.High);
        if (rs == null) throw new Exception("SkiaSharp Resize 返回 null");
        int rw = rs.Width, rh = rs.Height, stride = rs.RowBytes;
        var gray = new byte[rw * rh];           // Gray8 Resize 结果仍是 Gray8，每像素 1 字节
        var row = new byte[stride];
        IntPtr basePtr = rs.GetPixels();
        for (int y = 0; y < rh; y++)
        {
            Marshal.Copy(IntPtr.Add(basePtr, y * stride), row, 0, stride);
            Array.Copy(row, 0, gray, y * rw, rw);
        }
        return new L8Image(gray, rw, rh);
    }
    public void SaveAsPng(Stream s)
    {
        using var bmp = ImgHelpers.GrayToBitmap(D, Width, Height);
        var bytes = ImgHelpers.EncodePng(bmp);
        s.Write(bytes, 0, bytes.Length);
    }
    public void SaveAsPng(string path)
    {
        using var bmp = ImgHelpers.GrayToBitmap(D, Width, Height);
        File.WriteAllBytes(path, ImgHelpers.EncodePng(bmp));
    }
    public void Dispose() { }
}



