#!/usr/bin/env dotnet
// 超格行测套卷（10 套）-> App 可直接导入的「试卷包」，图推等选项是图的题把选项拆成四张图。
//
// 产物：qbank_out/超格行测套卷/行测套卷N.zip，每个 zip = 试卷.xlsx + images/
// （App 导入页选 zip 即可，一套一卷。）
//
// 「选项是图」的题（选项文字写的是「（见图A）」那种）现在这样处理：
//
//     材料  = 只剩题干图形（选项那一带切掉后剩下的部分）
//     选项A..D = `![A](images/q076_A.png)`，一个选项一张图
//
// App 的选项渲染本来就认 `![说明](路径)`（MaterialRenderer.splitOption），点图即选该项，
// 所以选项是真图、能点、能判分，不再是一行「见图A」。
//
// 拆分办法（不依赖 OCR，纯图像处理）：
// 1. 把该题分到的原始位图按 PDF 坐标拼回一张画布（k=1 时直接用原图；k=4 时四个位图
//    本身就是四个选项，按阅读顺序当 A-D）；
// 2. 优先找「四格带边框的选项条」——横框线用最长连续墨迹判定，竖分隔线切出四格，
//    直接按格子裁（最常见版式，最准）；
// 3. 认不到格子就找四个「字母大小、四周留白」的孤立墨块，按行优先排成 A/B/C/D，
//    再按字母朝图形一侧逐像素扫出该选项的图形（选项在上/下/左/右四种排法都能处理）；
// 4. 都没有就按列白缝硬切；
// 5. 四种方案里取「框住墨迹最多、彼此不重叠、不带卷面字母」的那一组；都不合格就保持
//    旧样（整块图进材料 + 「见图X」文字），绝不硬凑。
//
// 用法：
//   dotnet run tools/qbank/build_exam_packages.cs                              # 默认参数跑
//   dotnet run tools/qbank/build_exam_packages.cs -- --only 行测套卷1            # 只做一套
//   dotnet run tools/qbank/build_exam_packages.cs -- --probe 行测套卷1:76        # 单题调试，打印拆分细节
//   dotnet run tools/qbank/build_exam_packages.cs -- --out <目录>                # 换输出目录（默认 qbank_out/超格行测套卷）
//   dotnet run tools/qbank/build_exam_packages.cs -- --build-dir <tmp/build>     # raw/ img/ img_index.json 所在目录

#:package SkiaSharp@2.88.9
#:package ClosedXML@0.105.1
#:property PublishAot=false

using System.Globalization;
using System.IO.Compression;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using ClosedXML.Excel;
using SkiaSharp;

Console.OutputEncoding = System.Text.Encoding.UTF8;

try
{
    string here = ThisDir();
    string root = Path.GetFullPath(Path.Combine(here, "..", ".."));
    G.Here = here;
    G.Root = root;
    G.Build = Path.Combine(root, "tmp", "build");
    G.Raw = Path.Combine(G.Build, "raw");
    G.ImgDir = Path.Combine(G.Build, "img");
    G.Out = Path.Combine(root, "qbank_out", "超格行测套卷");

    string? only = null, probePaper = null, buildDir = null, outArg = null;
    int? probeNo = null;
    for (int i = 0; i < args.Length; i++)
    {
        switch (args[i])
        {
            case "--only" when i + 1 < args.Length: only = args[++i]; break;
            case "--probe" when i + 1 < args.Length:
                {
                    var pv = args[++i].Split(':');
                    probePaper = pv[0];
                    probeNo = int.Parse(pv[1]);
                }
                break;
            case "--build-dir" when i + 1 < args.Length: buildDir = args[++i]; break;
            case "--out" when i + 1 < args.Length: outArg = args[++i]; break;
        }
    }
    // 原 main() 里 probe 有一段「先置 None 再重算」的重复赋值，净效果不变，这里合并。
    // --probe 会强制只处理该卷。
    if (probePaper != null) only = probePaper;

    if (buildDir != null)
    {
        G.Build = Path.GetFullPath(buildDir);
        G.Raw = Path.Combine(G.Build, "raw");
        G.ImgDir = Path.Combine(G.Build, "img");
    }
    if (outArg != null) G.Out = Path.GetFullPath(outArg);
    Directory.CreateDirectory(G.Out);

    string idxPath = Path.Combine(G.Build, "img_index.json");
    string ansPath = Path.Combine(G.Build, "answers_raw.json");
    if (!File.Exists(idxPath)) { Console.Error.WriteLine($"找不到 {idxPath}"); return 2; }
    if (!File.Exists(ansPath)) { Console.Error.WriteLine($"找不到 {ansPath}"); return 2; }
    G.Idx = LoadIdx(idxPath);
    G.AnswersRaw = LoadAnswersRaw(ansPath);
    G.OptionRects = LoadOptionRects(Path.Combine(G.Here, "option_rects.json"));

    var summary = new Dictionary<string, object?>();
    var papers = G.Idx.Keys.OrderBy(p => PaperNo(p)).ToList();
    if (only != null) papers = papers.Where(p => p == only).ToList();

    foreach (var paper in papers)
    {
        var qs = LoadQs(paper);
        var (perQ, skipped) = PairSlots(qs, paper);
        int totalMain = qs.Any(q => q.No <= 130)
            ? qs.Where(q => q.No <= 130).Max(q => q.No)
            : qs.Max(q => q.No);
        var ans = LoadAnswers(paper, qs.Any(q => q.No > 130) ? 130 : totalMain);

        string pdir = Path.Combine(G.Out, paper);
        if (Directory.Exists(pdir)) Directory.Delete(pdir, true);
        Directory.CreateDirectory(Path.Combine(pdir, "images"));

        // 材料里「既有图又有 md 表」的，把表删掉，避免 App 里重复展示
        var dropped = new List<int>();
        foreach (var q in qs)
        {
            if (q.Own)
            {
                string mat = q.Material ?? "";
                if (G.ImgRe.IsMatch(mat) && mat.Contains('|'))
                {
                    var (mat2, d0) = DropMdTable(mat);
                    if (d0) { q.Material = mat2; dropped.Add(q.No); }
                }
            }
        }

        var outQs = new List<Dictionary<string, object?>>();
        var optSplit = new List<int>();
        var optFail = new List<Dictionary<string, object?>>();
        var optInfo = new List<Dictionary<string, object?>>();

        foreach (var q in qs)
        {
            string mat = q.Material ?? "";
            var opts = new Dictionary<string, string>();
            foreach (var kv in q.Options) opts[kv.Key] = kv.Value.Trim();
            bool allImgOpts = opts.Count > 0 && opts.Values.All(v => v.Contains("见图"));
            var items = perQ.TryGetValue(q.No, out var lst) ? lst : new List<ImgItem>();

            if (allImgOpts && items.Count > 0)
            {
                string stemName = $"q{q.No:D3}";
                var (stemPath, optImgs, info) = SplitQuestion(
                    paper, items, q.No, Path.Combine(pdir, "images"), stemName);
                if (probeNo != null && probeNo == q.No)
                    Console.WriteLine("PROBE " + JsonSerializer.Serialize(info,
                        new JsonSerializerOptions { Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }));
                if (optImgs != null)
                {
                    mat = G.ImgRe.Replace(mat, "").Trim();
                    if (stemPath != null)
                        mat = (mat.TrimEnd() + $"\n\n![题干图形](images/{stemName}.png)").Trim();
                    opts = new Dictionary<string, string>();
                    foreach (char k in "ABCD") opts[k.ToString()] = $"![{k}](images/{stemName}_{k}.png)";
                    optSplit.Add(q.No);
                    var info2 = new Dictionary<string, object?>(info) { ["no"] = q.No };
                    optInfo.Add(info2);
                }
                else
                {
                    optFail.Add(new Dictionary<string, object?> { ["no"] = q.No, ["info"] = info });
                    var files = items.Select(it => it.File).ToList();
                    if (files.Count == 1)
                    {
                        string src = Path.Combine(G.ImgDir, paper, files[0]);
                        string dst = Path.Combine(pdir, "images", $"{stemName}.png");
                        File.Copy(src, dst, true);
                        mat = G.ImgRe.Replace(mat, "").Trim();
                        mat = (mat.TrimEnd() + $"\n\n![题目配图](images/{stemName}.png)").Trim();
                    }
                    else
                    {
                        var missing = new List<string>();
                        foreach (var it in items)
                        {
                            string dst = Path.Combine(pdir, "images", it.File);
                            if (!File.Exists(dst)) File.Copy(Path.Combine(G.ImgDir, paper, it.File), dst, true);
                            missing.Add($"![配图](images/{it.File})");
                        }
                        mat = G.ImgRe.Replace(mat, "").Trim();
                        mat = (mat.TrimEnd() + "\n\n" + string.Join("\n\n", missing)).Trim();
                    }
                }
            }
            else if (G.ImgRe.IsMatch(mat) && items.Count > 0)
            {
                var refs = new List<string>();
                foreach (var it in items)
                {
                    string dst = Path.Combine(pdir, "images", it.File);
                    File.Copy(Path.Combine(G.ImgDir, paper, it.File), dst, true);
                    refs.Add($"![配图](images/{it.File})");
                }
                mat = (G.ImgRe.Replace(mat, "").Trim() + "\n\n" + string.Join("\n\n", refs)).Trim();
            }

            mat = Regex.Replace(mat, @"\n{3,}", "\n\n").Trim();
            string ansStr = ans.TryGetValue(q.No, out var av) ? av : "";
            var ansLetters = Regex.Matches(ansStr.ToUpper(), "[A-H]")
                                  .Cast<Match>().Select(mm => mm.Value).ToList();
            string qtype = new HashSet<string>(ansLetters).Count > 1 ? "多选" : "单选";
            outQs.Add(new Dictionary<string, object?>
            {
                ["no"] = q.No,
                ["type"] = qtype,
                ["subject"] = q.Section,
                ["stem"] = Regex.Replace(q.Stem ?? "", @"[ \t]+", " ").Trim(),
                ["options"] = opts,
                ["answer"] = ansStr,
                ["material"] = mat,
                ["score"] = 1,
                ["source"] = $"2027超格公考套卷实战班·{paper}",
                ["region"] = "全国",
                ["year"] = 2027,
            });
        }

        // ---- 写 试卷.xlsx ----
        var optKeys = new HashSet<string> { "A", "B", "C", "D" };
        foreach (var it in outQs)
            if (it["options"] is Dictionary<string, string> od)
                foreach (var k in od.Keys) optKeys.Add(k);
        var keys = optKeys.OrderBy(k => k, StringComparer.Ordinal).ToList();
        var header = new List<string> { "题型", "题干", "材料", "图片" };
        foreach (var k in keys) header.Add("选项" + k);
        header.AddRange(new[] { "答案", "解析", "知识点", "难度", "地区", "科目", "来源", "年份", "分值" });

        var rows = new List<List<object?>>();
        foreach (var it in outQs)
        {
            var od = (Dictionary<string, string>)it["options"]!;
            var row = new List<object?>
            {
                (string)it["type"]!, (string)it["stem"]!, (string)it["material"]!, ""
            };
            foreach (var k in keys) row.Add(od.TryGetValue(k, out var v) ? v : "");
            row.Add(it["answer"]); row.Add(""); row.Add(""); row.Add("");
            row.Add(it["region"]); row.Add(it["subject"]); row.Add(it["source"]);
            row.Add(it["year"]); row.Add(it["score"]);
            rows.Add(row);
        }
        string xlsxPath = Path.Combine(pdir, "试卷.xlsx");
        WriteXlsx(xlsxPath, header, rows);

        string zpath = Path.Combine(G.Out, paper + ".zip");
        if (File.Exists(zpath)) File.Delete(zpath);
        using (var z = ZipFile.Open(zpath, ZipArchiveMode.Create))
        {
            z.CreateEntryFromFile(xlsxPath, "试卷.xlsx", CompressionLevel.Optimal);
            foreach (var fn in Directory.GetFiles(Path.Combine(pdir, "images"))
                                        .Select(Path.GetFileName)
                                        .OrderBy(f => f, StringComparer.Ordinal))
                z.CreateEntryFromFile(Path.Combine(pdir, "images", fn!), "images/" + fn,
                                      CompressionLevel.Optimal);
        }

        var noAns = outQs.Where(it => string.IsNullOrEmpty((string?)it["answer"]))
                         .Select(it => (int)it["no"]!).ToList();
        int imgCount = Directory.GetFiles(Path.Combine(pdir, "images")).Length;
        double zipMb = new FileInfo(zpath).Length / 1e6;
        summary[paper] = new Dictionary<string, object?>
        {
            ["questions"] = outQs.Count,
            ["opt_split"] = optSplit.Cast<object?>().ToArray(),
            ["opt_fail"] = optFail.Cast<object?>().ToArray(),
            ["opt_info"] = optInfo.Cast<object?>().ToArray(),
            ["imgs"] = imgCount,
            ["no_answer"] = noAns.Cast<object?>().ToArray(),
            ["skipped_imgs"] = skipped.Cast<object?>().ToArray(),
            ["table_dropped"] = dropped.Cast<object?>().ToArray(),
            ["zip_mb"] = Math.Round(zipMb, 2),
        };
        Console.WriteLine($"{paper}: {outQs.Count}题 选项拆分{optSplit.Count} 失败{optFail.Count} " +
                          $"图片{imgCount} 跳过位图{skipped.Count} zip={zipMb.ToString("0.00", CultureInfo.InvariantCulture)}MB");
        foreach (var f in optFail)
            Console.WriteLine($"    拆不动 第{(int)f["no"]!}题 {PyRepr(f["info"])}");
    }

    var jopts = new JsonSerializerOptions
    {
        WriteIndented = true,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };
    // json.dump(..., indent=1) 对应 .NET 9+ 的 IndentSize，反射设置以兼容更多 SDK
    typeof(JsonSerializerOptions).GetProperty("IndentSize")?.SetValue(jopts, 1);
    File.WriteAllText(Path.Combine(G.Out, "build_summary.json"),
                      JsonSerializer.Serialize(summary, jopts));
    Console.WriteLine("产物：" + G.Out);
}
catch (Exception ex)
{
    Console.Error.WriteLine(ex.Message);
    return 2;
}
return 0;

// ---------------- helpers ----------------

static string ThisDir([CallerFilePath] string p = "") => Path.GetDirectoryName(p)!;

static int PaperNo(string p) => int.Parse(Regex.Match(p, @"(\d+)").Groups[1].Value);

static Dictionary<string, Dictionary<string, List<ImgItem>>> LoadIdx(string path)
{
    var res = new Dictionary<string, Dictionary<string, List<ImgItem>>>();
    using var doc = JsonDocument.Parse(File.ReadAllText(path));
    foreach (var p in doc.RootElement.EnumerateObject())
    {
        var d = new Dictionary<string, List<ImgItem>>();
        foreach (var pg in p.Value.EnumerateObject())
        {
            var lst = new List<ImgItem>();
            foreach (var e in pg.Value.EnumerateArray())
                lst.Add(new ImgItem
                {
                    File = e.GetProperty("file").GetString() ?? "",
                    Bbox = e.GetProperty("bbox").EnumerateArray().Select(v => v.GetDouble()).ToArray(),
                });
            d[pg.Name] = lst;
        }
        res[p.Name] = d;
    }
    return res;
}

static Dictionary<string, Dictionary<string, List<string>>> LoadAnswersRaw(string path)
{
    var res = new Dictionary<string, Dictionary<string, List<string>>>();
    using var doc = JsonDocument.Parse(File.ReadAllText(path));
    foreach (var p in doc.RootElement.EnumerateObject())
    {
        var d = new Dictionary<string, List<string>>();
        foreach (var sec in p.Value.EnumerateObject())
        {
            var lst = new List<string>();
            foreach (var e in sec.Value.EnumerateArray()) lst.Add(e.GetString() ?? "");
            d[sec.Name] = lst;
        }
        res[p.Name] = d;
    }
    return res;
}

static Dictionary<string, OptRectCfg> LoadOptionRects(string path)
{
    var res = new Dictionary<string, OptRectCfg>();
    if (!File.Exists(path)) return res;
    using var doc = JsonDocument.Parse(File.ReadAllText(path));
    foreach (var p in doc.RootElement.EnumerateObject())
    {
        if (p.Name.StartsWith("_")) continue;
        var c = new OptRectCfg();
        if (p.Value.TryGetProperty("method", out var m)) c.Method = m.GetString();
        if (p.Value.TryGetProperty("mode", out var mo)) c.Mode = mo.GetString();
        if (p.Value.TryGetProperty("no_stem", out var ns)) c.NoStem = ns.GetBoolean();
        if (p.Value.TryGetProperty("rect", out var r))
            c.Rect = r.EnumerateArray().Select(v => v.GetDouble()).ToArray();
        if (p.Value.TryGetProperty("boxes", out var bx) && bx.ValueKind == JsonValueKind.Object)
        {
            c.Boxes = new Dictionary<string, double[]>();
            foreach (var kv in bx.EnumerateObject())
                c.Boxes[kv.Name] = kv.Value.EnumerateArray().Select(v => v.GetDouble()).ToArray();
        }
        res[p.Name] = c;
    }
    return res;
}

static List<Q> LoadQs(string paper)
{
    var qs = new List<Q>();
    string? sec = null;
    string dir = Path.Combine(G.Raw, paper);
    if (!Directory.Exists(dir)) return qs;
    foreach (var f in Directory.GetFiles(dir, "p*.json").OrderBy(f => f, StringComparer.Ordinal))
    {
        var m = Regex.Match(Path.GetFileName(f), @"p(\d+)\.json");
        if (!m.Success) continue;
        int page = int.Parse(m.Groups[1].Value);
        using var doc = JsonDocument.Parse(File.ReadAllText(f));
        var d = doc.RootElement;
        if (d.TryGetProperty("section", out var se) && se.ValueKind == JsonValueKind.String
            && !string.IsNullOrEmpty(se.GetString()))
            sec = se.GetString();
        if (!d.TryGetProperty("questions", out var qarr)) continue;
        foreach (var qe in qarr.EnumerateArray())
        {
            var q = new Q { Page = page, Section = sec ?? "" };
            if (qe.TryGetProperty("no", out var no)) q.No = no.GetInt32();
            if (qe.TryGetProperty("stem", out var st) && st.ValueKind == JsonValueKind.String)
                q.Stem = st.GetString() ?? "";
            if (qe.TryGetProperty("material", out var mat) && mat.ValueKind == JsonValueKind.String)
                q.Material = mat.GetString();
            if (qe.TryGetProperty("options", out var op) && op.ValueKind == JsonValueKind.Object)
                foreach (var kv in op.EnumerateObject())
                    q.Options[kv.Name] = kv.Value.ValueKind == JsonValueKind.String
                        ? kv.Value.GetString() ?? "" : kv.Value.ToString();
            if (qe.TryGetProperty("material_same", out var ms)) q.MaterialSame = ms.ValueKind == JsonValueKind.True;
            q.Own = qe.TryGetProperty("material", out var mm) && mm.ValueKind == JsonValueKind.String
                    && !string.IsNullOrEmpty(mm.GetString());
            qs.Add(q);
        }
    }
    qs.Sort((a, b) => a.No.CompareTo(b.No));
    // 题干里的 ![](IMG) 挪进材料
    foreach (var q in qs)
    {
        string st = q.Stem ?? "";
        if (G.ImgRe.IsMatch(st))
        {
            int n = G.ImgRe.Matches(st).Count;
            q.Stem = G.ImgRe.Replace(st, "").Trim();
            string ph = string.Concat(Enumerable.Repeat("![](IMG)", n));
            if (q.Own) q.Material = (q.Material ?? "").TrimEnd() + "\n\n" + ph;
            else { q.Material = ph; q.Own = true; }
        }
    }
    string? last = null;
    foreach (var q in qs)
    {
        if (q.Own) last = q.Material;
        else if (q.MaterialSame) q.Material = last;
        else last = null;
    }
    return qs;
}

static Dictionary<int, string> LoadAnswers(string paper, int totalMain)
{
    var m = new Dictionary<int, string>();
    if (!G.AnswersRaw.TryGetValue(paper, out var secs)) return m;
    foreach (var kv in secs)
    {
        int off = kv.Key == "附加题" ? totalMain : 0;
        foreach (var r in kv.Value)
        {
            var parts = r.Split(new[] { ':' }, 2);
            var ab = parts[0].Split('-');
            int s = int.Parse(ab[0]), e = int.Parse(ab[1]);
            string letters = parts[1].Trim();
            for (int i = 0; i < letters.Length; i++) m[s + i + off] = letters[i].ToString();
        }
    }
    return m;
}

static (Dictionary<int, List<ImgItem>>, List<string>) PairSlots(List<Q> qs, string paper)
{
    var slots = new List<(int q, int page, int k, int i)>();
    foreach (var q in qs)
    {
        if (!q.Own) continue;
        int k = G.ImgRe.Matches(q.Material ?? "").Count;
        for (int i = 0; i < k; i++) slots.Add((q.No, q.Page, k, i));
    }
    var imgs = new List<ImgItem>();
    if (G.Idx.TryGetValue(paper, out var pages))
        foreach (var pg in pages.OrderBy(kv => int.Parse(kv.Key)))
            foreach (var it in pg.Value)
                imgs.Add(new ImgItem { File = it.File, Bbox = it.Bbox, Page = int.Parse(pg.Key) });

    int m = slots.Count, n = imgs.Count;
    const double INF = 1e9;
    var dp = new double[m + 1][];
    var bk = new char[m + 1][];
    for (int i = 0; i <= m; i++)
    {
        dp[i] = new double[n + 1]; bk[i] = new char[n + 1];
        for (int j = 0; j <= n; j++) dp[i][j] = INF;
    }
    dp[0][0] = 0;
    for (int i = 0; i <= m; i++)
        for (int j = 0; j <= n; j++)
        {
            if (dp[i][j] >= INF) continue;
            if (i < m && j < n)
            {
                double c = dp[i][j] + Math.Abs(slots[i].page - imgs[j].Page);
                if (c < dp[i + 1][j + 1]) { dp[i + 1][j + 1] = c; bk[i + 1][j + 1] = 'M'; }
            }
            if (j < n)
            {
                double c = dp[i][j] + 1.6;
                if (c < dp[i][j + 1]) { dp[i][j + 1] = c; bk[i][j + 1] = 'S'; }
            }
        }
    int ii = m, jj = n;
    var pair = new Dictionary<int, int>();
    while (ii > 0 || jj > 0)
    {
        char b = (ii >= 0 && ii <= m && jj >= 0 && jj <= n) ? bk[ii][jj] : '\0';
        if (b == 'M') { pair[ii - 1] = jj - 1; ii--; jj--; }
        else if (b == 'S') jj--;
        else ii--;
    }
    var perQ = new Dictionary<int, List<ImgItem>>();
    for (int si = 0; si < slots.Count; si++)
        if (pair.TryGetValue(si, out int ji))
        {
            var src = imgs[ji];
            var item = new ImgItem
            {
                File = src.File, Bbox = src.Bbox, Page = src.Page,
                K = slots[si].k, I = slots[si].i,
            };
            if (!perQ.TryGetValue(slots[si].q, out var l2))
            { l2 = new List<ImgItem>(); perQ[slots[si].q] = l2; }
            l2.Add(item);
        }
    var used = new HashSet<int>(pair.Values);
    var skipped = new List<string>();
    for (int t = 0; t < n; t++) if (!used.Contains(t)) skipped.Add(imgs[t].File);
    return (perQ, skipped);
}

// PIL convert('L') 的加权（0.299/0.587/0.114），避免 SkiaSharp 默认灰度权重差异
static L8Image LoadL8(string path)
{
    using var rgb = RgbImage.Load(path);
    var img = new L8Image(rgb.Width, rgb.Height);
    for (int y = 0; y < rgb.Height; y++)
        for (int x = 0; x < rgb.Width; x++)
        {
            var p = rgb[x, y];
            img[x, y] = new L8((byte)(0.299 * p.R + 0.587 * p.G + 0.114 * p.B + 0.5));
        }
    return img;
}

static L8Image ItemImage(string paper, ImgItem it) =>
    LoadL8(Path.Combine(G.ImgDir, paper, it.File));

static (L8Image, List<Placed>, double) BuildCanvas(string paper, List<ImgItem> items)
{
    if (items.Count == 1)
        return (ItemImage(paper, items[0]),
                new List<Placed> { new Placed { Px = 0, Py = 0, It = items[0], S = 1.0 } }, 1.0);
    var scales = new List<double>();
    foreach (var it in items)
    {
        using var im = ItemImage(paper, it);
        double bw = Math.Max(1e-6, it.Bbox[2] - it.Bbox[0]);
        scales.Add(im.Width / bw);
    }
    double S = Math.Max(1.0, Median(scales));
    double x0 = items.Min(it => it.Bbox[0]), y0 = items.Min(it => it.Bbox[1]);
    int W = Math.Max(1, (int)((items.Max(it => it.Bbox[2]) - x0) * S) + 2 * G.PAD);
    int H = Math.Max(1, (int)((items.Max(it => it.Bbox[3]) - y0) * S) + 2 * G.PAD);
    var canvas = new L8Image(W, H, 255);
    var placed = new List<Placed>();
    foreach (var it in items)
    {
        using var im = ItemImage(paper, it);
        int tw = Math.Max(1, (int)((it.Bbox[2] - it.Bbox[0]) * S));
        int th = Math.Max(1, (int)((it.Bbox[3] - it.Bbox[1]) * S));
        int px = (int)((it.Bbox[0] - x0) * S) + G.PAD;
        int py = (int)((it.Bbox[1] - y0) * S) + G.PAD;
        using var piece = im.Resize(tw, th);
        Paste(canvas, piece, px, py);
        placed.Add(new Placed { Px = px, Py = py, It = it, S = S });
    }
    return (canvas, placed, S);
}

static void Paste(L8Image dst, L8Image src, int px, int py)
{
    for (int y = 0; y < src.Height; y++)
    {
        int dy = py + y;
        if (dy < 0 || dy >= dst.Height) continue;
        for (int x = 0; x < src.Width; x++)
        {
            int dx = px + x;
            if (dx < 0 || dx >= dst.Width) continue;
            dst[dx, dy] = src[x, y];
        }
    }
}

static L8Image CropL8(L8Image src, int x0, int y0, int x1, int y1, bool padBlack = false)
{
    int w = Math.Max(0, x1 - x0), h = Math.Max(0, y1 - y0);
    var img = new L8Image(w, h, padBlack ? (byte)0 : (byte)255);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
        {
            int sx = x0 + x, sy = y0 + y;
            if (sx >= 0 && sy >= 0 && sx < src.Width && sy < src.Height)
                img[x, y] = src[sx, sy];
        }
    return img;
}

static Bin ToBin(L8Image img, int thresh)
{
    var b = new Bin(img.Height, img.Width);
    for (int y = 0; y < img.Height; y++)
        for (int x = 0; x < img.Width; x++)
            b[y, x] = img[x, y].PackedValue < thresh;
    return b;
}

static double Median(List<double> v)
{
    if (v.Count == 0) return 0;
    var a = v.OrderBy(x => x).ToArray();
    int n = a.Length;
    return n % 2 == 1 ? a[n / 2] : (a[n / 2 - 1] + a[n / 2]) / 2.0;
}

static double Mean(double[] v) => v.Length == 0 ? 0 : v.Average();

static double Std(double[] v)
{
    if (v.Length == 0) return 0;
    double m = v.Average(), s = 0;
    foreach (var x in v) s += (x - m) * (x - m);
    return Math.Sqrt(s / v.Length);
}

static IEnumerable<T[]> Combinations<T>(IList<T> pool, int k)
{
    int n = pool.Count;
    if (k > n || k <= 0) yield break;
    var idx = new int[k];
    for (int i = 0; i < k; i++) idx[i] = i;
    while (true)
    {
        var res = new T[k];
        for (int i = 0; i < k; i++) res[i] = pool[idx[i]];
        yield return res;
        int p = k - 1;
        while (p >= 0 && idx[p] == n - k + p) p--;
        if (p < 0) break;
        idx[p]++;
        for (int i = p + 1; i < k; i++) idx[i] = idx[i - 1] + 1;
    }
}

// 手写 8 邻域（或 4 邻域）BFS 连通域，替代 scipy.ndimage.label / find_objects
static CcResult Cc(Bin m, bool conn8)
{
    int H = m.H, W = m.W, N = m.D.Length;
    var labels = new int[N];
    var boxes = new List<int[]> { new int[4] };
    var areas = new List<int> { 0 };
    int[] dy, dx;
    if (conn8) { dy = new[] { -1, -1, -1, 0, 0, 1, 1, 1 }; dx = new[] { -1, 0, 1, -1, 1, -1, 0, 1 }; }
    else { dy = new[] { -1, 0, 0, 1 }; dx = new[] { 0, -1, 1, 0 }; }
    var stack = new Stack<int>();
    int cur = 0;
    for (int i = 0; i < N; i++)
    {
        if (!m.D[i] || labels[i] != 0) continue;
        cur++;
        stack.Clear(); stack.Push(i); labels[i] = cur;
        int x0 = W, y0 = H, x1 = 0, y1 = 0, area = 0;
        while (stack.Count > 0)
        {
            int p = stack.Pop(), px = p % W, py = p / W;
            area++;
            if (px < x0) x0 = px; if (px > x1) x1 = px;
            if (py < y0) y0 = py; if (py > y1) y1 = py;
            for (int k = 0; k < dy.Length; k++)
            {
                int nx = px + dx[k], ny = py + dy[k];
                if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                int qi = ny * W + nx;
                if (m.D[qi] && labels[qi] == 0) { labels[qi] = cur; stack.Push(qi); }
            }
        }
        boxes.Add(new[] { y0, y1, x0, x1 });
        areas.Add(area);
    }
    return new CcResult(labels, cur, boxes.ToArray(), areas.ToArray());
}

static List<List<int>> Groups(List<int> vals, int gap)
{
    var g = new List<List<int>>();
    foreach (int v in vals)
    {
        if (g.Count > 0 && v - g[^1][^1] <= gap) g[^1].Add(v);
        else g.Add(new List<int> { v });
    }
    return g;
}

static int MaxRun(bool[] arr)
{
    int best = 0, cur = 0;
    foreach (var v in arr) { cur = v ? cur + 1 : 0; if (cur > best) best = cur; }
    return best;
}

static int MaxRunRow(Bin m, int y, int x0, int x1)
{
    int best = 0, cur = 0;
    for (int x = Math.Max(0, x0); x < Math.Min(m.W, x1); x++)
    { cur = m[y, x] ? cur + 1 : 0; if (cur > best) best = cur; }
    return best;
}

static int MaxRunCol(Bin m, int x, int y0, int y1)
{
    int best = 0, cur = 0;
    for (int y = Math.Max(0, y0); y < Math.Min(m.H, y1); y++)
    { cur = m[y, x] ? cur + 1 : 0; if (cur > best) best = cur; }
    return best;
}

static double RowMean(Bin m, int y, int x0, int x1)
{
    int cnt = 0, tot = 0;
    for (int x = Math.Max(0, x0); x < Math.Min(m.W, x1); x++) { tot++; if (m[y, x]) cnt++; }
    return tot == 0 ? 0 : cnt / (double)tot;
}

static double ColMean(Bin m, int x, int y0, int y1)
{
    int cnt = 0, tot = 0;
    for (int y = Math.Max(0, y0); y < Math.Min(m.H, y1); y++) { tot++; if (m[y, x]) cnt++; }
    return tot == 0 ? 0 : cnt / (double)tot;
}

static List<Cand> LetterCandidates(Bin mask)
{
    int H = mask.H, W = mask.W;
    var cands = new List<Cand>();
    var cc = Cc(mask, true);                    // ndimage.label(mask, ones(3,3)) = 8 邻域
    for (int i = 1; i <= cc.N; i++)
    {
        var bb = cc.Boxes[i];                   // [y0,y1,x0,x1]
        int y0 = bb[0], y1 = bb[1], x0 = bb[2], x1 = bb[3];
        int h = y1 - y0, w = x1 - x0;
        if (!(0.015 * H <= h && h <= 0.16 * H)) continue;
        if (!(0.008 * W <= w && w <= 0.11 * W)) continue;
        if (!(0.35 <= w / (double)h && w / (double)h <= 2.0)) continue;
        int area = cc.Areas[i];
        if (area > 0.004 * H * W) continue;
        double fill = area / (double)(w * h);
        if (!(0.12 <= fill && fill <= 0.70)) continue;
        int d = Math.Max(3, (int)(0.7 * h));
        int ry0 = Math.Max(0, y0 - d), ry1 = Math.Min(H, y1 + d);
        int rx0 = Math.Max(0, x0 - d), rx1 = Math.Min(W, x1 + d);
        int region = mask.RegionSum(ry0, ry1, rx0, rx1);
        int inner = mask.RegionSum(y0, y1, x0, x1);
        double ring = (region - inner) / (double)Math.Max(1, (ry1 - ry0) * (rx1 - rx0));
        cands.Add(new Cand
        {
            Y0 = y0, Y1 = y1, X0 = x0, X1 = x1, H = h, W = w,
            Fill = fill, Ring = ring, Cx = (x0 + x1) / 2.0, Cy = (y0 + y1) / 2.0,
        });
    }
    return cands;
}

static (List<Cand>?, string) PickFourLetters(List<Cand> cands, int H, int W)
{
    if (cands.Count < 4) return (null, $"cand<{cands.Count}");
    var pool = new List<Cand>(cands);
    if (pool.Count > 9)
    {
        var bestGrp = new List<Cand>();
        foreach (var c in pool)
        {
            var grp = pool.Where(d => Math.Abs(d.H - c.H) <= 0.12 * c.H).ToList();
            if (grp.Count > bestGrp.Count) bestGrp = grp;
        }
        pool = bestGrp;
    }
    if (pool.Count > 16) pool = pool.OrderBy(d => d.Ring).Take(16).ToList();

    List<Cand>? bestOrder = null;
    double? bestScore = null;
    foreach (var combo in Combinations(pool, 4))
    {
        var rows = new List<List<Cand>>();
        foreach (var c in combo.OrderBy(d => d.Cy))
        {
            bool added = false;
            foreach (var r in rows)
                if (Math.Abs(c.Cy - r[0].Cy) <= 0.45 * c.H) { r.Add(c); added = true; break; }
            if (!added) rows.Add(new List<Cand> { c });
        }
        var lens = rows.Select(r => r.Count).OrderBy(v => v).ToArray();
        if (!(lens.SequenceEqual(new[] { 4 }) || lens.SequenceEqual(new[] { 2, 2 }))) continue;
        rows = rows.OrderBy(r => r.Min(c => c.Cy)).ToList();
        var order = new List<Cand>();
        foreach (var r in rows) order.AddRange(r.OrderBy(d => d.Cx));
        var hs = order.Select(c => (double)c.H).ToArray();
        if (hs.Max() - hs.Min() > 0.35 * hs.Max()) continue;
        var pitchs = new List<double>();
        foreach (var r in rows)
        {
            var rr = r.OrderBy(d => d.Cx).ToList();
            for (int i = 0; i < rr.Count - 1; i++) pitchs.Add(rr[i + 1].Cx - rr[i].Cx);
        }
        if (pitchs.Count == 0) continue;
        double pitch = pitchs.Average();
        if (pitch < 0.06 * W) continue;
        double cv = pitchs.Count > 1 ? Std(pitchs.ToArray()) / pitch : 0.0;
        if (cv > 0.22) continue;
        int extra = 0;
        double meanHs = hs.Average();
        foreach (var r in rows)
        {
            double lo = r.Min(c => c.Cy) - 0.5 * meanHs;
            double hi = r.Max(c => c.Cy) + 0.5 * meanHs;
            foreach (var c in cands)
                if (lo <= c.Cy && c.Cy <= hi && !r.Contains(c)) extra++;
        }
        double score = cv
            + 0.5 * (Std(hs) / hs.Average())
            + 0.6 * (hs.Average() / H)
            + 2.0 * (order.Average(c => c.Ring))
            + 0.05 * extra
            + 1.2 * Math.Max(0.0, (0.12 * W - pitch) / (0.12 * W))
            - 0.15 * 4;
        if (bestScore == null || score < bestScore) { bestScore = score; bestOrder = order; }
    }
    if (bestOrder == null) return (null, $"no-grid(候选{pool.Count})");
    return (bestOrder, "ok");
}

static List<(int, int)> InkRuns1D(bool[] profile)
{
    var runs = new List<(int, int)>();
    int i = 0, n = profile.Length;
    while (i < n)
    {
        if (profile[i])
        {
            int j = i;
            while (j < n && profile[j]) j++;
            runs.Add((i, j));
            i = j;
        }
        else i++;
    }
    return runs;
}

static bool[] ColAnyInRows(Bin m, int x0, int x1, int y0, int y1)
{
    var r = new bool[Math.Max(0, y1 - y0)];
    for (int y = y0; y < y1; y++)
    {
        if (y < 0 || y >= m.H) continue;
        bool any = false;
        for (int x = Math.Max(0, x0); x < Math.Min(m.W, x1); x++)
            if (m[y, x]) { any = true; break; }
        r[y - y0] = any;
    }
    return r;
}

static bool[] RowAnyInCols(Bin m, int y0, int y1, int x0, int x1)
{
    var r = new bool[Math.Max(0, x1 - x0)];
    for (int x = x0; x < x1; x++)
    {
        if (x < 0 || x >= m.W) continue;
        bool any = false;
        for (int y = Math.Max(0, y0); y < Math.Min(m.H, y1); y++)
            if (m[y, x]) { any = true; break; }
        r[x - x0] = any;
    }
    return r;
}

// 在子图里算墨迹包围盒，先剔掉贯穿整幅的细框线（实心黑块不能剔）
static int[]? ContentBBox(Bin sub, double lineFrac = 0.92, int maxLine = 3)
{
    if (!sub.Any()) return null;
    int h = sub.H, w = sub.W;
    var m = sub.Clone();

    void EraseThin(int[] idx, bool horizontal)
    {
        if (idx.Length == 0) return;
        var runs = new List<List<int>>();
        var run = new List<int> { idx[0] };
        foreach (var v in idx.Skip(1))
        {
            if (v - run[^1] <= 1) run.Add(v);
            else { runs.Add(run); run = new List<int> { v }; }
        }
        runs.Add(run);
        foreach (var rr in runs)
        {
            if (rr.Count > maxLine) continue;         // 厚的是实心黑块，不是框线
            foreach (var y in rr)
            {
                if (horizontal) { if (y >= 0 && y < h) m.SetRow(y, false); }
                else { if (y >= 0 && y < w) m.SetCol(y, false); }
            }
        }
    }
    var rowMean = sub.RowMean();
    EraseThin(Enumerable.Range(0, h).Where(i => rowMean[i] >= lineFrac).ToArray(), true);
    var colMean = sub.ColMean();
    EraseThin(Enumerable.Range(0, w).Where(i => colMean[i] >= lineFrac).ToArray(), false);
    var src = m.Any() ? m : sub;
    return src.BBox();
}

static string ProbeDirs(Bin mask, List<Cand> letters, double pitch)
{
    int H = mask.H, W = mask.W;
    var total = new Dictionary<string, int> { ["up"] = 0, ["down"] = 0, ["left"] = 0, ["right"] = 0 };
    foreach (var letter in letters)
    {
        int lh = letter.H;
        foreach (var d in new[] { "up", "down", "left", "right" })
        {
            int x0, x1, y0, y1;
            if (d == "up")
            {
                x0 = (int)Math.Max(0, letter.Cx - 0.45 * pitch); x1 = (int)Math.Min(W, letter.Cx + 0.45 * pitch);
                y0 = (int)Math.Max(0, letter.Y0 - 1.8 * pitch); y1 = (int)Math.Max(0, letter.Y0 - 0.15 * lh);
            }
            else if (d == "down")
            {
                x0 = (int)Math.Max(0, letter.Cx - 0.45 * pitch); x1 = (int)Math.Min(W, letter.Cx + 0.45 * pitch);
                y0 = (int)Math.Min(H, letter.Y1 + 0.15 * lh); y1 = (int)Math.Min(H, letter.Y1 + 1.8 * pitch);
            }
            else if (d == "left")
            {
                y0 = (int)Math.Max(0, letter.Cy - 0.45 * pitch); y1 = (int)Math.Min(H, letter.Cy + 0.45 * pitch);
                x0 = (int)Math.Max(0, letter.X0 - 1.8 * pitch); x1 = (int)Math.Max(0, letter.X0 - 0.15 * lh);
            }
            else
            {
                y0 = (int)Math.Max(0, letter.Cy - 0.45 * pitch); y1 = (int)Math.Min(H, letter.Cy + 0.45 * pitch);
                x0 = (int)Math.Min(W, letter.X1 + 0.15 * lh); x1 = (int)Math.Min(W, letter.X1 + 1.8 * pitch);
            }
            if (y1 > y0 && x1 > x0) total[d] += mask.RegionSum(y0, y1, x0, x1);
        }
    }
    string best = "up";
    int bv = total["up"];
    foreach (var d in new[] { "down", "left", "right" })
        if (total[d] > bv) { bv = total[d]; best = d; }
    return best;
}

static int[]? CropOption(Bin mask, Cand letter, double pitch, int gapTh, int thinTh, string direction)
{
    int H = mask.H, W = mask.W;
    double half = pitch / 2.0;
    if (direction != "up" && direction != "down" && direction != "left" && direction != "right")
        direction = "up";
    bool vertical = direction == "up" || direction == "down";
    int x0 = 0, x1 = 0, y0 = 0, y1 = 0;
    bool[] prof;
    if (vertical)
    {
        x0 = (int)Math.Max(0, letter.Cx - half); x1 = (int)Math.Min(W, letter.Cx + half);
        prof = ColAnyInRows(mask, x0, x1, 0, H);
    }
    else
    {
        y0 = (int)Math.Max(0, letter.Cy - half); y1 = (int)Math.Min(H, letter.Cy + half);
        prof = RowAnyInCols(mask, y0, y1, 0, W);
    }
    var runs = InkRuns1D(prof);
    if (runs.Count == 0) return null;
    if (direction == "up") runs = runs.Where(r => r.Item2 <= letter.Y0).ToList();
    else if (direction == "down") runs = runs.Where(r => r.Item1 >= letter.Y1).ToList();
    else if (direction == "left") runs = runs.Where(r => r.Item2 <= letter.X0).ToList();
    else runs = runs.Where(r => r.Item1 >= letter.X1).ToList();
    if (runs.Count == 0) return null;
    bool forward = direction == "up" || direction == "left";
    var seq = forward
        ? runs.OrderByDescending(r => r.Item2).ToList()
        : runs.OrderBy(r => r.Item1).ToList();
    (int, int)? start = null;
    foreach (var r in seq)
        if (r.Item2 - r.Item1 >= thinTh) { start = r; break; }
    if (start == null) return null;
    int lo = start.Value.Item1, hi = start.Value.Item2;
    var cur = start.Value;
    while (true)
    {
        (int, int)? nxt = null;
        int? gap = null;
        if (forward)
        {
            var cand = runs.Where(r => r.Item2 <= cur.Item1).ToList();
            if (cand.Count > 0) nxt = cand.OrderByDescending(r => r.Item2).First();
            if (nxt != null) gap = cur.Item1 - nxt.Value.Item2;
        }
        else
        {
            var cand = runs.Where(r => r.Item1 >= cur.Item2).ToList();
            if (cand.Count > 0) nxt = cand.OrderBy(r => r.Item1).First();
            if (nxt != null) gap = nxt.Value.Item1 - cur.Item2;
        }
        if (nxt == null || gap == null || gap >= gapTh) break;
        lo = Math.Min(lo, nxt.Value.Item1); hi = Math.Max(hi, nxt.Value.Item2);
        cur = nxt.Value;
    }
    Bin sub;
    if (vertical) { y0 = lo; y1 = hi; sub = mask.Sub(y0, y1, x0, x1); }
    else { x0 = lo; x1 = hi; sub = mask.Sub(y0, y1, x0, x1); }
    if (sub.D.Length == 0 || !sub.Any()) return null;
    var bb = ContentBBox(sub);
    if (bb == null) return null;
    var box = new[] { x0 + bb[0], y0 + bb[1], x0 + bb[2], y0 + bb[3] };
    if (box[2] - box[0] < 6 || box[3] - box[1] < 6) return null;
    return box;
}

static L8Image? BuildStem(L8Image canvas, Dictionary<string, int[]> boxes,
                            List<Cand> letters, double pitch, int[]? band)
{
    int W = canvas.Width, H = canvas.Height;
    int ux0 = boxes.Values.Min(b => b[0]), ux1 = boxes.Values.Max(b => b[2]);
    int uy0 = boxes.Values.Min(b => b[1]), uy1 = boxes.Values.Max(b => b[3]);
    int ly1 = letters.Count > 0 ? letters.Max(c => c.Y1) : uy1;
    var mask = ToBin(canvas, 128);
    int bandY0, bandY1;
    if (band != null)
    {
        bandY0 = Math.Max(0, band[0] - 4); bandY1 = Math.Min(H, band[1] + 6);
    }
    else
    {
        var longRows = new List<int>();
        for (int y = 0; y < H; y++) if (RowMean(mask, y, 0, W) > 0.5) longRows.Add(y);
        var topC = longRows.Where(y => y <= uy0 - 2 && uy0 - y <= 0.6 * pitch).ToList();
        var botC = longRows.Where(y => uy1 + 2 <= y && y <= ly1).ToList();
        bandY0 = Math.Max(0, topC.Count > 0 ? topC.Max() - 4 : uy0 - 14);
        bandY1 = Math.Min(H, Math.Max(uy1 + 10, Math.Max(ly1 + 8, botC.Count > 0 ? botC.Min() + 5 : 0)));
    }
    var outImg = new L8Image(W, H, 255);
    if (bandY0 > 0) Paste(outImg, CropL8(canvas, 0, 0, W, bandY0, true), 0, 0);
    foreach (var (cx0, cx1) in new[] { (0, Math.Max(0, ux0 - 12)), (Math.Min(W, ux1 + 12), W) })
    {
        if (cx1 - cx0 < 12) continue;
        int by0 = Math.Max(0, Math.Min(bandY0, H));
        int by1 = Math.Max(by0, Math.Min(bandY1, H));
        if (by1 <= by0) continue;
        using var part = CropL8(canvas, cx0, by0, cx1, by1);
        var pm = ToBin(part, 128);
        var rowsOver = Enumerable.Range(0, pm.H).Where(y => RowMean(pm, y, 0, pm.W) > 0.6).ToArray();
        var colsOver = Enumerable.Range(0, pm.W).Where(x => ColMean(pm, x, 0, pm.H) > 0.6).ToArray();
        var pm2 = pm.Clone();
        foreach (int y in rowsOver) pm2.SetRow(y, false);
        foreach (int x in colsOver) pm2.SetCol(x, false);
        if (pm2.Sum() < Math.Max(150.0, 0.002 * pm2.D.Length)) continue;
        using var arr = part.Clone();
        foreach (int y in rowsOver)
            for (int xx = 0; xx < arr.Width; xx++) arr[xx, y] = new L8(255);
        foreach (int x in colsOver)
            for (int yy = 0; yy < arr.Height; yy++) arr[x, yy] = new L8(255);
        Paste(outImg, arr, cx0, by0);
    }
    return CropTight(outImg, 6, 250);
}

static L8Image CropTight(L8Image img, int pad = 4, int thresh = 250)
{
    var m = ToBin(img, thresh);
    var bb = m.BBox();
    if (bb == null) return img;
    int x0 = Math.Max(0, bb[0] - pad), y0 = Math.Max(0, bb[1] - pad);
    int x1 = Math.Min(img.Width, bb[2] + pad), y1 = Math.Min(img.Height, bb[3] + pad);
    return CropL8(img, x0, y0, x1, y1);
}

static int CountBelow(L8Image img, int thresh)
{
    int n = 0;
    for (int y = 0; y < img.Height; y++)
        for (int x = 0; x < img.Width; x++)
            if (img[x, y].PackedValue < thresh) n++;
    return n;
}

// 擦掉「孤零零的贯穿长直线」：原脚本里定义但未被调用，忠实保留
static L8Image DropOrphanLines(L8Image img, double minFrac = 0.5, int gap = 14, double keepFrac = 0.02)
{
    var arr = ToBin(img, 128);
    int H = arr.H, W = arr.W;
    var outp = arr.Clone();

    bool EmptyNear((int s0, int s1) seg, string axis, int pos)
    {
        int a, b;
        if (axis == "row")
        {
            int ay0 = Math.Max(0, pos - gap), ay1 = Math.Max(0, pos - 3);
            int by0 = Math.Min(H, pos + 4), by1 = Math.Min(H, pos + gap);
            a = arr.RegionSum(ay0, ay1, seg.s0, seg.s1);
            b = arr.RegionSum(by0, by1, seg.s0, seg.s1);
            int na = Math.Max(0, ay1 - ay0) * Math.Max(0, seg.s1 - seg.s0);
            int nb = Math.Max(0, by1 - by0) * Math.Max(0, seg.s1 - seg.s0);
            return (a + b) < keepFrac * Math.Max(1, na + nb);
        }
        else
        {
            int ax0 = Math.Max(0, pos - gap), ax1 = Math.Max(0, pos - 3);
            int bx0 = Math.Min(W, pos + 4), bx1 = Math.Min(W, pos + gap);
            a = arr.RegionSum(seg.s0, seg.s1, ax0, ax1);
            b = arr.RegionSum(seg.s0, seg.s1, bx0, bx1);
            int na = Math.Max(0, seg.s1 - seg.s0) * Math.Max(0, ax1 - ax0);
            int nb = Math.Max(0, seg.s1 - seg.s0) * Math.Max(0, bx1 - bx0);
            return (a + b) < keepFrac * Math.Max(1, na + nb);
        }
    }

    for (int y = 0; y < H; y++)
        if (RowMean(arr, y, 0, W) > minFrac)
        {
            int xmin = W, xmax = -1;
            for (int x = 0; x < W; x++) if (arr[y, x]) { if (x < xmin) xmin = x; if (x > xmax) xmax = x; }
            if (xmax >= xmin && EmptyNear((xmin, xmax + 1), "row", y))
                for (int x = xmin; x <= xmax; x++) outp[y, x] = false;
        }
    for (int x = 0; x < W; x++)
        if (ColMean(arr, x, 0, H) > minFrac)
        {
            int ymin = H, ymax = -1;
            for (int y = 0; y < H; y++) if (arr[y, x]) { if (y < ymin) ymin = y; if (y > ymax) ymax = y; }
            if (ymax >= ymin && EmptyNear((ymin, ymax + 1), "col", x))
                for (int y = ymin; y <= ymax; y++) outp[y, x] = false;
        }
    if (!outp.Any()) return img;
    var res = new L8Image(W, H);
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++) res[x, y] = new L8(outp[y, x] ? (byte)0 : (byte)255);
    return res;
}

// 原脚本里此函数定义了两次（完全相同），这里合并为一份；未被调用，忠实保留
static L8Image TrimEdgeLines(L8Image img, double frac = 0.93)
{
    var a = ToBin(img, 128);
    int h = a.H, w = a.W;
    int x0 = 0, x1 = w, y0 = 0, y1 = h;
    while (x0 < x1 - 4 && ColMean(a, x0, 0, h) > frac) x0++;
    while (x1 - 4 > x0 && ColMean(a, x1 - 1, 0, h) > frac) x1--;
    while (y0 < y1 - 4 && RowMean(a, y0, 0, w) > frac) y0++;
    while (y1 - 4 > y0 && RowMean(a, y1 - 1, 0, w) > frac) y1--;
    if (x0 == 0 && y0 == 0 && x1 == w && y1 == h) return img;
    return CropL8(img, x0, y0, x1, y1);
}

static L8Image StripFrameBorder(L8Image img, double frac = 0.90, int gap = 2)
{
    var a = ToBin(img, 160);
    int h = a.H, w = a.W;
    int y0 = 0, y1 = h, x0 = 0, x1 = w;

    bool AnyInRect(int ry0, int ry1, int rx0, int rx1) =>
        a.RegionSum(Math.Max(0, ry0), Math.Min(h, ry1), Math.Max(0, rx0), Math.Min(w, rx1)) > 0;

    while (y1 - y0 > 8 && RowMean(a, y0, x0, x1) > frac)
    {
        int rest = y1 - y0 - 1;
        if (rest <= gap || AnyInRect(y0 + 1, y0 + 1 + gap, x0, x1)) break;
        y0++;
    }
    while (y1 - y0 > 8 && RowMean(a, y1 - 1, x0, x1) > frac)
    {
        int rest = y1 - 1 - y0;
        if (rest <= gap || AnyInRect(y1 - 1 - gap, y1 - 1, x0, x1)) break;
        y1--;
    }
    while (x1 - x0 > 8 && ColMean(a, x0, y0, y1) > frac)
    {
        int rest = x1 - x0 - 1;
        if (rest <= gap || AnyInRect(y0, y1, x0 + 1, x0 + 1 + gap)) break;
        x0++;
    }
    while (x1 - x0 > 8 && ColMean(a, x1 - 1, y0, y1) > frac)
    {
        int rest = x1 - 1 - x0;
        if (rest <= gap || AnyInRect(y0, y1, x1 - 1 - gap, x1 - 1)) break;
        x1--;
    }
    if (x0 == 0 && y0 == 0 && x1 == w && y1 == h) return img;
    return CropL8(img, x0, y0, x1, y1);
}

static (string, bool) DropMdTable(string text)
{
    var lines = text.Split('\n');
    var outp = new List<string>();
    int i = 0;
    bool dropped = false;
    while (i < lines.Length)
    {
        string ln = lines[i];
        string nxt = i + 1 < lines.Length ? lines[i + 1] : "";
        if (ln.Contains('|') && Regex.IsMatch(nxt, @"^[\s|:\-]+$") && nxt.Contains('-'))
        {
            int j = i + 1;
            while (j < lines.Length && lines[j].Contains('|')) j++;
            i = j; dropped = true; continue;
        }
        outp.Add(ln); i++;
    }
    return (string.Join("\n", outp), dropped);
}

static void WriteXlsx(string path, List<string> header, List<List<object?>> rows)
{
    using var wb = new XLWorkbook();
    var ws = wb.AddWorksheet("试卷");
    for (int c = 0; c < header.Count; c++) SetCell(ws.Cell(1, c + 1), header[c]);
    for (int r = 0; r < rows.Count; r++)
        for (int c = 0; c < rows[r].Count; c++) SetCell(ws.Cell(r + 2, c + 1), rows[r][c]);
    wb.SaveAs(path);
}

static void SetCell(IXLCell cell, object? v)
{
    if (v == null) cell.Value = "";
    else if (v is string s) cell.Value = s;
    else if (v is int i) cell.Value = i;
    else if (v is double d) cell.Value = d;
    else if (v is bool b) cell.Value = b;
    else cell.Value = v.ToString() ?? "";
}

// Python `%s` 打印 dict/list 的近似表示（仅用于控制台调试行）
static string PyRepr(object? v)
{
    if (v == null) return "None";
    if (v is bool b) return b ? "True" : "False";
    if (v is string s)
        return "'" + s.Replace("\\", "\\\\").Replace("'", "\\'")
                       .Replace("\n", "\\n").Replace("\r", "\\r").Replace("\t", "\\t") + "'";
    if (v is int i) return i.ToString(CultureInfo.InvariantCulture);
    if (v is long l) return l.ToString(CultureInfo.InvariantCulture);
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

// 方案一：四格带边框的选项条（横框线用最长连续墨迹，竖线切四格）
static (Dictionary<string, int[]>, int, int)? SplitBoxedRow(Bin mask)
{
    int H = mask.H, W = mask.W;
    var raw = new List<int>();
    for (int y = 0; y < H; y++) if (MaxRunRow(mask, y, 0, W) > 0.55 * W) raw.Add(y);
    if (raw.Count < 2) return null;
    var ys = Groups(raw, 3).Select(g => (int)g.Average()).ToList();

    (double score, Dictionary<string, int[]> boxes, int t, int b)? best = null;
    for (int i1 = 0; i1 < ys.Count; i1++)
    {
        int t = ys[i1];
        for (int j = i1 + 1; j < ys.Count; j++)
        {
            int b = ys[j];
            int hgt = b - t;
            if (hgt < 0.06 * H || hgt > 0.55 * H) continue;
            var cols = new List<int>();
            for (int x = 0; x < W; x++)
                if (MaxRunCol(mask, x, t + 2, b - 1) > 0.85 * hgt) cols.Add(x);
            if (cols.Count < 5) continue;
            var vg = Groups(cols, 3);
            var cells = new List<(int, int)>();
            for (int k = 0; k < vg.Count - 1; k++)
            {
                int a = vg[k][^1] + 2, b2 = vg[k + 1][0] - 1;
                if (b2 - a > 0.06 * W) cells.Add((a, b2));
            }
            if (cells.Count != 4) continue;
            var ws = cells.Select(c => c.Item2 - c.Item1).ToArray();
            if (ws.Max() > 1.5 * ws.Min()) continue;
            var boxes = new Dictionary<string, int[]>();
            bool ok = true;
            for (int z = 0; z < 4; z++)
            {
                var (a, b2) = cells[z];
                var bb = ContentBBox(mask.Sub(t + 2, b - 1, a, b2));
                if (bb == null) { ok = false; break; }
                int bw = bb[2] - bb[0], bh = bb[3] - bb[1];
                if (bw < 0.03 * W || bh < 0.04 * H) { ok = false; break; }
                boxes["ABCD"[z].ToString()] = new[]
                { a + bb[0], t + 2 + bb[1], a + bb[2], t + 2 + bb[3] };
            }
            if (!ok) continue;
            double[] wsd = ws.Select(v => (double)v).ToArray();
            double score = Std(wsd) / Mean(wsd) - 0.6 * (t / (double)H);
            if (best == null || score < best.Value.score)
                best = (score, boxes, t, b);
        }
    }
    if (best == null) return null;
    return (best.Value.boxes, best.Value.t, best.Value.b);
}

static Bin StripLetters(Bin sub)
{
    var prof = sub.RowAny();
    var bands = new List<(int, int)>();
    int s0 = -1;
    for (int i = 0; i < prof.Length; i++)
    {
        if (prof[i] && s0 < 0) s0 = i;
        else if (!prof[i] && s0 >= 0) { bands.Add((s0, i)); s0 = -1; }
    }
    if (s0 >= 0) bands.Add((s0, prof.Length));
    if (bands.Count < 2) return sub;
    var inks = bands.Select(bd => sub.RegionSum(bd.Item1, bd.Item2, 0, sub.W)).ToArray();
    int keep = inks.Max();
    int lo = 0, hi = bands.Count - 1;
    while (lo < hi && inks[lo] < 0.22 * keep && bands[lo + 1].Item1 - bands[lo].Item2 >= 5) lo++;
    while (hi > lo && inks[hi] < 0.22 * keep && bands[hi].Item1 - bands[hi - 1].Item2 >= 5) hi--;
    return sub.Sub(bands[lo].Item1, bands[hi].Item2, 0, sub.W);
}

static int[]? CutCell(Bin mask, int regX0, int regY0, int[] cell, bool fallback)
{
    int cx0 = cell[0], cy0 = cell[1], cx1 = cell[2], cy1 = cell[3];
    var s2 = mask.Sub(regY0 + cy0, regY0 + cy1, regX0 + cx0, regX0 + cx1);
    if (s2.Any()) s2 = StripLetters(s2);
    var bb = s2.Any() ? ContentBBox(s2) : null;
    if (bb == null)
    {
        if (!fallback) return null;
        bb = new[] { 0, 0, cx1 - cx0, cy1 - cy0 };
    }
    var res = new[]
    { regX0 + cx0 + bb[0], regY0 + cy0 + bb[1], regX0 + cx0 + bb[2], regY0 + cy0 + bb[3] };
    if (res[2] - res[0] < 10 || res[3] - res[1] < 10)
        if (!fallback) return null;
    return res;
}

static int BestCut(double[] prof, int total, double expect, double win)
{
    int lo = Math.Max(2, (int)(expect - win));
    int hi = Math.Min(total - 2, (int)(expect + win));
    if (hi <= lo) return (int)expect;
    int best = lo;
    double bv = prof[lo];
    for (int i = lo + 1; i < hi; i++)
        if (prof[i] < bv) { bv = prof[i]; best = i; }
    return best;
}

// 人工标定的「选项区外框」切四个选项（mode = 1x4 / 2x2）
static (Dictionary<string, int[]>, int[])? SplitRegionOptions(
    Bin mask, double[] rect01, string mode, bool exact = false)
{
    int H = mask.H, W = mask.W;
    int x0 = (int)Math.Round(rect01[0] * W, MidpointRounding.ToEven);
    int x1 = (int)Math.Round(rect01[2] * W, MidpointRounding.ToEven);
    int y0 = (int)Math.Round(rect01[1] * H, MidpointRounding.ToEven);
    int y1 = (int)Math.Round(rect01[3] * H, MidpointRounding.ToEven);
    x0 = Math.Max(0, Math.Min(x0, W - 4)); x1 = Math.Max(0, Math.Min(x1, W));
    y0 = Math.Max(0, Math.Min(y0, H - 4)); y1 = Math.Max(0, Math.Min(y1, H));
    if (x1 - x0 < 40 || y1 - y0 < 40) return null;
    var sub = mask.Sub(y0, y1, x0, x1);
    int h = sub.H, w = sub.W;
    var vprof = sub.ColSum().Select(v => (double)v).ToArray();

    List<int[]> cells;
    if (mode == "1x4")
    {
        double win = 0.14 * w;
        var xs = new List<int> { 0 };
        foreach (int i in new[] { 1, 2, 3 }) xs.Add(BestCut(vprof, w, w * i / 4.0, win));
        xs.Add(w);
        var uniq = xs.Distinct().OrderBy(v => v).ToList();
        if (uniq.Count == 5)
            cells = Enumerable.Range(0, 4).Select(i => new[] { uniq[i], 0, uniq[i + 1], h }).ToList();
        else
        {
            var e = Enumerable.Range(0, 5).Select(i => (int)(w * i / 4.0)).ToArray();
            cells = Enumerable.Range(0, 4).Select(i => new[] { e[i], 0, e[i + 1], h }).ToList();
        }
    }
    else if (mode == "2x2")
    {
        int mx = w / 2, my = h / 2;
        cells = new List<int[]>
        {
            new[] { 0, 0, mx, my }, new[] { mx, 0, w, my },
            new[] { 0, my, mx, h }, new[] { mx, my, w, h },
        };
    }
    else return null;
    if (cells.Count == 0) return null;
    var boxes = new List<int[]?>();
    foreach (var c in cells) boxes.Add(CutCell(mask, x0, y0, c, mode == "2x2"));
    if (boxes.Any(b => b == null)) return null;
    var res = new Dictionary<string, int[]>();
    for (int i = 0; i < 4; i++) res["ABCD"[i].ToString()] = boxes[i]!;
    return (res, new[] { y0, y1 });
}

// 从卷面渲染图（200dpi）直接裁四个选项（method=page）
static Dictionary<string, string>? SplitPageOptions(string paper, int page, double[]? rect01,
    string? mode, string outDir, string stemName, Dictionary<string, double[]>? boxes01)
{
    string path = Path.Combine(Path.GetDirectoryName(G.Build)!, "ocr_pages", paper,
                               $"p{page:D2}.png");
    if (!File.Exists(path)) return null;
    using var im = LoadL8(path);
    int W = im.Width, H = im.Height;
    var boxes = new Dictionary<string, double[]>();
    if (boxes01 != null)
    {
        foreach (var kv in boxes01)
            boxes[kv.Key] = new[]
            { kv.Value[0] * W, kv.Value[1] * H, kv.Value[2] * W, kv.Value[3] * H };
    }
    else
    {
        if (rect01 == null || mode == null) return null;
        var got = SplitRegionOptions(ToBin(im, 128), rect01, mode, true);
        if (got == null) return null;
        foreach (var kv in got.Value.Item1)
            boxes[kv.Key] = kv.Value.Select(v => (double)v).ToArray();
    }
    var opts = new Dictionary<string, string>();
    foreach (char letter in "ABCD")
    {
        if (!boxes.TryGetValue(letter.ToString(), out var b)) return null;
        using var crop = CropL8(im, (int)b[0], (int)b[1], (int)b[2], (int)b[3]);
        if (crop.Width < 10 || crop.Height < 6) return null;
        string p = Path.Combine(outDir, $"{stemName}_{letter}.png");
        using var ct = CropTight(crop);
        ct.SaveAsPng(p);
        opts[letter.ToString()] = p;
    }
    return opts;
}

static List<int> VLines(Bin mask, int t, int b, double frac = 0.80)
{
    var band = mask.Sub(t + 2, b - 1, 0, mask.W);
    if (band.D.Length == 0) return new List<int>();
    var cols = new List<int>();
    for (int x = 0; x < band.W; x++)
        if (MaxRunCol(band, x, 0, band.H) > frac * band.H) cols.Add(x);
    return Groups(cols, 3).Select(g => (int)g.Average()).ToList();
}

static List<int[]> CellsFrom(List<int> xs, int y0, int y1, int W)
{
    var outp = new List<int[]>();
    for (int i = 0; i < xs.Count - 1; i++)
    {
        int a = xs[i] + 2, b = xs[i + 1] - 1;
        if (b - a > 0.05 * W) outp.Add(new[] { a, y0, b, y1 });
    }
    return outp;
}

static int[]? CellContent(Bin mask, int[] cell)
{
    int x0 = cell[0], y0 = cell[1], x1 = cell[2], y1 = cell[3];
    var sub = mask.Sub(y0, y1, x0, x1).Clone();
    if (!sub.Any()) return null;
    int h = sub.H, w = sub.W;
    var ylist = new List<int>();
    for (int i = 0; i < Math.Min(3, h); i++) ylist.Add(i);
    for (int i = Math.Max(0, h - 3); i < h; i++) ylist.Add(i);
    foreach (int y in ylist)
        if (y >= 0 && y < h && RowMean(sub, y, 0, w) > 0.6) sub.SetRow(y, false);
    var xlist = new List<int>();
    for (int i = 0; i < Math.Min(3, w); i++) xlist.Add(i);
    for (int i = Math.Max(0, w - 3); i < w; i++) xlist.Add(i);
    foreach (int x in xlist)
        if (x >= 0 && x < w && ColMean(sub, x, 0, h) > 0.6) sub.SetCol(x, false);
    if (!sub.Any()) return null;
    var cc = Cc(sub, true);                        // ndimage.label(sub, ones(3,3)) = 8 邻域
    if (cc.N > 1)
    {
        var blobs = new List<(int area, int y0, int y1, int x0, int x1)>();
        for (int i = 1; i <= cc.N; i++)
        {
            var bb = cc.Boxes[i];
            blobs.Add((cc.Areas[i], bb[0], bb[1], bb[2], bb[3]));
        }
        int big = blobs.Max(bl => bl.area);
        foreach (var bl in blobs)
        {
            int bh = bl.y1 - bl.y0, bw = bl.x1 - bl.x0;
            if (bl.area >= 0.35 * big || bh > 0.5 * h || bw > 0.5 * w) continue;
            if (bh < 6 || bw < 4) continue;
            bool corner = (bl.y0 < 0.30 * h || bl.y1 > 0.70 * h)
                       && (bl.x0 < 0.30 * w || bl.x1 > 0.70 * w);
            if (corner)
                for (int yy = Math.Max(0, bl.y0 - 1); yy < Math.Min(h, bl.y1 + 1); yy++)
                    for (int xx = Math.Max(0, bl.x0 - 1); xx < Math.Min(w, bl.x1 + 1); xx++)
                        sub[yy, xx] = false;
        }
    }
    if (!sub.Any()) return null;
    var bb2 = sub.BBox();
    if (bb2 == null) return null;
    var res = new[] { x0 + bb2[0], y0 + bb2[1], x0 + bb2[2], y0 + bb2[3] };
    if (res[2] - res[0] < 10 || res[3] - res[1] < 10) return null;
    return res;
}

// 按「表格边框」切：原脚本里定义但未被调用，忠实保留
static (Dictionary<string, int[]>, int, int)? SplitFrameCells(Bin mask)
{
    int H = mask.H, W = mask.W;
    if (H < 40 || W < 80) return null;
    var hl = new List<int>();
    for (int y = 0; y < H; y++) if (MaxRunRow(mask, y, 0, W) > 0.42 * W) hl.Add(y);
    if (hl.Count < 2) return null;
    var ys = Groups(hl, 3).Select(g => (int)g.Average()).ToList();

    (double score, List<int[]> quad, int t, int b)? best = null;
    for (int i = 0; i < ys.Count; i++)
    {
        int t = ys[i];
        for (int j = i + 1; j < ys.Count; j++)
        {
            int b = ys[j], hgt = b - t;
            if (hgt < 0.10 * H || hgt > 0.70 * H) continue;
            var xs = VLines(mask, t, b);
            if (xs.Count < 4) continue;
            var cells = CellsFrom(xs, t + 2, b - 1, W);
            if (cells.Count < 4) continue;
            for (int k = 0; k < cells.Count - 3; k++)
            {
                var quad = cells.GetRange(k, 4);
                var ws = quad.Select(c => c[2] - c[0]).ToArray();
                if (ws.Max() > 1.15 * ws.Min()) continue;
                var inbox = quad.Select(c => (double)mask.RegionSum(c[1], c[3], c[0], c[2])).ToArray();
                if (inbox.Min() < 0.004 * W * H) continue;
                if (quad.Max(c => c[3] - c[1]) != quad.Min(c => c[3] - c[1])) continue;
                double[] wsd = ws.Select(v => (double)v).ToArray();
                double score = Std(wsd) / Mean(wsd)
                    - 0.30 * (quad.Sum(c => c[0]) / 4.0) / W
                    - 0.10 * (inbox.Min() / Math.Max(1.0, inbox.Max()));
                if (best == null || score < best.Value.score)
                    best = (score, quad, t, b);
            }
        }
    }
    if (best == null) return null;
    var quad0 = best.Value.quad;
    int t0 = best.Value.t, b0 = best.Value.b;
    var xs0 = VLines(mask, t0, b0);
    var mid = new List<int>();
    for (int y = t0 + 8; y < b0 - 8; y++)
        if (MaxRunRow(mask, y, 0, W) > 0.30 * W) mid.Add(y);
    var boxes = new Dictionary<string, int[]>();
    if (mid.Count > 0)
    {
        int my = (int)mid.Average();
        var topCells = CellsFrom(xs0, t0 + 2, my - 1, W);
        var botCells = CellsFrom(xs0, my + 2, b0 - 1, W);
        var quadT = topCells.Where(c => c[2] - c[0] > 0.05 * W).ToList();
        var quadB = botCells.Where(c => c[2] - c[0] > 0.05 * W).ToList();
        if (quadT.Count == 2 && quadB.Count == 2)
        {
            var cells = quadT.Concat(quadB).ToList();
            boxes = new Dictionary<string, int[]>();
            bool ok = true;
            for (int z = 0; z < 4; z++)
            {
                var bb = CellContent(mask, cells[z]);
                if (bb == null) { ok = false; break; }
                boxes["ABCD"[z].ToString()] = bb;
            }
            if (ok && boxes.Count == 4) return (boxes, t0, b0);
        }
    }
    boxes = new Dictionary<string, int[]>();
    for (int z = 0; z < 4; z++)
    {
        var bb = CellContent(mask, quad0[z]);
        if (bb == null) return null;
        boxes["ABCD"[z].ToString()] = bb;
    }
    return (boxes, t0, b0);
}

// 原脚本里此函数定义了两次（完全相同），这里合并为一份；未被调用，忠实保留
static List<int[]> EdgeLetterBoxes(Bin mask, int boxH, int boxW)
{
    if (!mask.Any()) return new List<int[]>();
    var cc = Cc(mask, true);                      // ndimage.label(mask, ones(3,3)) = 8 邻域
    if (cc.N < 2) return new List<int[]>();
    var comps = new List<(int area, int y0, int y1, int x0, int x1)>();
    for (int i = 1; i <= cc.N; i++)
    {
        var bb = cc.Boxes[i];
        comps.Add((cc.Areas[i], bb[0], bb[1], bb[2], bb[3]));
    }
    int main = comps.Max(c => c.area);
    int H = mask.H;
    var outp = new List<int[]>();
    foreach (var c in comps)
    {
        int h = c.y1 - c.y0, w = c.x1 - c.x0;
        if (c.area > 0.18 * main) continue;
        if (!(0.03 * boxH <= h && h <= 0.24 * boxH)) continue;
        if (w > 0.25 * boxW) continue;
        if (!(0.25 <= w / (double)h && w / (double)h <= 2.4)) continue;
        if ((H - c.y1) <= 0.16 * boxH || c.y0 <= 0.10 * boxH)
            outp.Add(new[] { c.y0, c.y1, c.x0, c.x1 });
    }
    return outp;
}

static Bin StripEdgeLetters(Bin mask, int boxH, int boxW)
{
    if (!mask.Any()) return mask;
    var cc = Cc(mask, true);                      // ndimage.label(mask, ones(3,3)) = 8 邻域
    if (cc.N < 2) return mask;
    var comps = new List<(int area, int y0, int y1, int x0, int x1)>();
    for (int i = 1; i <= cc.N; i++)
    {
        var bb = cc.Boxes[i];
        comps.Add((cc.Areas[i], bb[0], bb[1], bb[2], bb[3]));
    }
    int main = comps.Max(c => c.area);
    var outp = mask.Clone();
    int H = mask.H;
    foreach (var c in comps)
    {
        int h = c.y1 - c.y0, w = c.x1 - c.x0;
        if (c.area > 0.18 * main) continue;
        if (!(0.04 * boxH <= h && h <= 0.22 * boxH)) continue;
        if (w > 0.22 * boxW) continue;
        if (!(0.3 <= w / (double)h && w / (double)h <= 2.2)) continue;
        bool near = (H - c.y1) <= 0.14 * boxH || c.y0 <= 0.10 * boxH;
        if (near)
            for (int y = c.y0; y < c.y1; y++)
                for (int x = c.x0; x < c.x1; x++) outp[y, x] = false;
    }
    return outp;
}

// 方形膨胀，替代 scipy.ndimage.binary_dilation(mask, ones((d,d)))
static Bin Dilate(Bin m, int d)
{
    int H = m.H, W = m.W;
    var res = new Bin(H, W);
    int lo = -(d / 2), hi = lo + d - 1;
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++)
        {
            if (!m[y, x]) continue;
            for (int dy = lo; dy <= hi; dy++)
                for (int dx = lo; dx <= hi; dx++)
                {
                    int ny = y + dy, nx = x + dx;
                    if (ny < 0 || ny >= H || nx < 0 || nx >= W) continue;
                    res[ny, nx] = true;
                }
        }
    return res;
}

static List<int[]> BlocksAt(Bin mask, int d, int H, int W)
{
    var dil = Dilate(mask, d);
    var cc = Cc(dil, false);                     // ndimage.label(dil) 默认 4 邻域
    int[] minX = new int[cc.N + 1], maxX = new int[cc.N + 1];
    int[] minY = new int[cc.N + 1], maxY = new int[cc.N + 1];
    int[] area = new int[cc.N + 1];
    for (int i = 0; i <= cc.N; i++)
    { minX[i] = W; maxX[i] = -1; minY[i] = H; maxY[i] = -1; }
    for (int p = 0; p < cc.Labels.Length; p++)
    {
        int l = cc.Labels[p];
        if (l == 0 || !mask.D[p]) continue;
        int x = p % W, y = p / W;
        if (x < minX[l]) minX[l] = x;
        if (x > maxX[l]) maxX[l] = x;
        if (y < minY[l]) minY[l] = y;
        if (y > maxY[l]) maxY[l] = y;
        area[l]++;
    }
    var outp = new List<int[]>();
    for (int i = 1; i <= cc.N; i++)
    {
        if (area[i] == 0) continue;
        int x0 = minX[i], y0 = minY[i], x1 = maxX[i] + 1, y1 = maxY[i] + 1;
        if (area[i] < 0.0008 * H * W || x1 - x0 < 10 || y1 - y0 < 10) continue;
        outp.Add(new[] { x0, y0, x1, y1 });
    }
    return outp;
}

static double? ScoreBlocks(Bin mask, List<int[]> boxes)
{
    int H = mask.H, W = mask.W;
    var ws = boxes.Select(b => (double)(b[2] - b[0])).ToArray();
    var hs = boxes.Select(b => (double)(b[3] - b[1])).ToArray();
    if (ws.Max() > 2.4 * ws.Min() || hs.Max() > 2.4 * hs.Min()) return null;
    var cys = boxes.Select(b => (b[1] + b[3]) / 2.0).ToArray();
    var cxs = boxes.Select(b => (b[0] + b[2]) / 2.0).ToArray();
    var rows = new List<(double key, List<int> idxs)>();
    for (int i = 0; i < cys.Length; i++)
    {
        double cyi = cys[i];
        int found = -1;
        double bestGap = double.MaxValue;
        for (int k = 0; k < rows.Count; k++)
        {
            double g = Math.Abs(rows[k].key - cyi);
            if (g < bestGap) { bestGap = g; found = k; }
        }
        if (found >= 0 && bestGap < 0.5 * hs.Max()) rows[found].idxs.Add(i);
        else rows.Add((cyi, new List<int> { i }));
    }
    var layout = rows.Select(r => r.idxs.Count).OrderBy(v => v).ToArray();
    if (!(layout.SequenceEqual(new[] { 4 }) || layout.SequenceEqual(new[] { 2, 2 }))) return null;
    double pitchCv = 0;
    foreach (var r in rows)
    {
        if (r.idxs.Count < 2) continue;
        var xs = r.idxs.Select(i => cxs[i]).OrderBy(v => v).ToArray();
        var gaps = new List<double>();
        for (int k = 0; k < xs.Length - 1; k++) gaps.Add(xs[k + 1] - xs[k]);
        if (gaps.Count > 1 && gaps.Average() > 0) pitchCv += Std(gaps.ToArray()) / gaps.Average();
    }
    double cv = Std(ws) / Mean(ws) + Std(hs) / Mean(hs);
    var ink = boxes.Select(b => (double)mask.RegionSum(b[1], b[3], b[0], b[2])).ToArray();
    double ratio = ink.Min() / Math.Max(1.0, ink.Max());
    double cx = cys.Length > 0 ? Mean(cxs) / W : 0;
    double cyMean = cys.Length > 0 ? Mean(cys) / H : 0;
    return cv + 0.5 * pitchCv - 0.25 * (cx + cyMean) - 0.3 * ratio;
}

// 不合格回退之一：形态学膨胀把同一选项里几笔/几块粘一起，取面积尺寸最一致的四块
static Dictionary<string, int[]>? SplitBlocksOptions(Bin mask)
{
    int H = mask.H, W = mask.W;
    int baseD = Math.Max(4, (int)(0.012 * Math.Max(H, W)));
    double? bestScore = null;
    List<int[]>? best = null;
    foreach (int mult in new[] { 1, 2, 3, 4, 6 })
    {
        var bl = BlocksAt(mask, baseD * mult, H, W);
        if (bl.Count < 4) continue;
        if (bl.Count > 14)
            bl = bl.OrderByDescending(b => (b[2] - b[0]) * (b[3] - b[1])).Take(14).ToList();
        foreach (var combo in Combinations(bl, 4))
        {
            var sc = ScoreBlocks(mask, combo.ToList());
            if (sc != null && (bestScore == null || sc < bestScore))
            { bestScore = sc; best = combo.ToList(); }
        }
    }
    if (best == null) return null;
    var rows = new List<List<int[]>>();
    foreach (var b in best.OrderBy(b => (b[1] + b[3]) / 2.0))
    {
        double cy = (b[1] + b[3]) / 2.0;
        bool added = false;
        foreach (var r in rows)
            if (Math.Abs(cy - (r[0][1] + r[0][3]) / 2.0) < 0.5 * Math.Max(1, b[3] - b[1]))
            { r.Add(b); added = true; break; }
        if (!added) rows.Add(new List<int[]> { b });
    }
    var ordered = new List<int[]>();
    foreach (var r in rows.OrderBy(r => (r[0][1] + r[0][3]) / 2.0))
        ordered.AddRange(r.OrderBy(b => b[0]));
    var res = new Dictionary<string, int[]>();
    for (int i = 0; i < 4 && i < ordered.Count; i++) res["ABCD"[i].ToString()] = ordered[i];
    return res;
}

// 方案三：按「竖向墨迹段」切四列（选项横排、列间有空隙）
static Dictionary<string, int[]>? SplitByColumns(Bin mask)
{
    int H = mask.H, W = mask.W;
    var rows = mask.RowAny();
    var bands = new List<(int, int)>();
    int y = 0;
    while (y < H)
    {
        if (rows[y])
        {
            int y0 = y;
            while (y < H && rows[y]) y++;
            bands.Add((y0, y));
        }
        else y++;
    }
    if (bands.Count < 2) return null;
    int yEnd = (bands[^1].Item2 - bands[^1].Item1) < 0.22 * H ? bands[^1].Item1 : H;
    var sub = mask.Sub(0, yEnd, 0, W);
    var colink = sub.ColAny();
    var segs = new List<(int, int)>();
    int x = 0;
    while (x < W)
    {
        if (colink[x])
        {
            int x0 = x;
            while (x < W && colink[x]) x++;
            if (x - x0 >= 8) segs.Add((x0, x));
        }
        else x++;
    }
    var merged = new List<(int, int)>();
    foreach (var (a, b) in segs)
    {
        if (merged.Count > 0 && a - merged[^1].Item2 < 0.05 * W)
            merged[^1] = (merged[^1].Item1, b);
        else merged.Add((a, b));
    }
    segs = merged;
    if (segs.Count < 4) return null;

    (double score, List<(int, int)> quad)? best = null;
    for (int i = 0; i < segs.Count - 3; i++)
    {
        var quad = segs.GetRange(i, 4);
        var ws = quad.Select(q => q.Item2 - q.Item1).ToArray();
        if (ws.Min() < 0.06 * W) continue;
        if (ws.Max() > 1.35 * ws.Min()) continue;
        var gaps = new double[3];
        for (int k = 0; k < 3; k++) gaps[k] = quad[k + 1].Item1 - quad[k].Item2;
        double[] wsd = ws.Select(v => (double)v).ToArray();
        double cvW = Std(wsd) / Mean(wsd);
        double meanG = Mean(gaps);
        double cvG = meanG > 0 ? Std(gaps) / meanG : 1.0;
        if (cvG > 0.35) continue;
        double score = cvW + 0.8 * cvG - 0.35 * (quad[0].Item1 / (double)W);
        if (best == null || score < best.Value.score) best = (score, quad);
    }
    if (best == null) return null;
    var boxes = new Dictionary<string, int[]>();
    for (int z = 0; z < 4; z++)
    {
        var (qx0, qx1) = best.Value.quad[z];
        int c0 = Math.Max(0, qx0 - 2), c1 = Math.Min(W, qx1 + 2);
        var bb = ContentBBox(mask.Sub(0, yEnd, c0, c1));
        if (bb == null) return null;
        boxes["ABCD"[z].ToString()] = new[] { c0 + bb[0], bb[1], c0 + bb[2], bb[3] };
    }
    return boxes;
}

static bool BoxesOk(Dictionary<string, int[]> bx)
{
    if (bx.Count != 4) return false;
    var vals = bx.Values.ToList();
    for (int i = 0; i < 4; i++)
        for (int j = i + 1; j < 4; j++)
        {
            int ox = Math.Min(vals[i][2], vals[j][2]) - Math.Max(vals[i][0], vals[j][0]);
            int oy = Math.Min(vals[i][3], vals[j][3]) - Math.Max(vals[i][1], vals[j][1]);
            if (ox > 0 && oy > 0)
            {
                int area = ox * oy;
                int small = Math.Min((vals[i][2] - vals[i][0]) * (vals[i][3] - vals[i][1]),
                                     (vals[j][2] - vals[j][0]) * (vals[j][3] - vals[j][1]));
                if (area > 0.12 * Math.Max(1, small)) return false;
            }
        }
    var ws = vals.Select(b => b[2] - b[0]).ToArray();
    var hs = vals.Select(b => b[3] - b[1]).ToArray();
    if (ws.Min() < 8 || hs.Min() < 8) return false;
    if (ws.Max() > 3.2 * ws.Min() || hs.Max() > 3.2 * hs.Min()) return false;
    return true;
}

static double Coverage(Bin mask, Dictionary<string, int[]> bx)
{
    double tot = mask.Sum(); if (tot == 0) tot = 1.0;
    double inside = 0;
    foreach (var b in bx.Values) inside += mask.RegionSum(b[1], b[3], b[0], b[2]);
    return inside / tot;
}

static bool HitLetter(Dictionary<string, int[]> bx, List<Cand>? letters)
{
    if (letters == null) return false;
    foreach (var b in bx.Values)
        foreach (var c in letters)
        {
            int ox = Math.Min(b[2], c.X1) - Math.Max(b[0], c.X0);
            int oy = Math.Min(b[3], c.Y1) - Math.Max(b[1], c.Y0);
            if (ox > 2 && oy > 2) return true;
        }
    return false;
}

// 拆分一道「选项是图」的题：k=4 直接按位图、其余走 boxed / letters / columns /
// manual / page / rect / blocks(rect) / grid 各方案，取覆盖最多且互不重叠的一组。
static (string?, Dictionary<string, string>?, Dictionary<string, object?>)
SplitQuestion(string paper, List<ImgItem> items, int no, string imgOutDir, string stemName)
{
    var (canvas, placed, scale) = BuildCanvas(paper, items);
    var mask = ToBin(canvas, 128);
    var info = new Dictionary<string, object?>
    {
        ["k"] = items.Count,
        ["canvas"] = new object?[] { canvas.Width, canvas.Height },
        ["items"] = items.Select(it => (object?)new Dictionary<string, object?>
        {
            ["file"] = it.File,
            ["bbox"] = it.Bbox.Select(v => (object?)v).ToArray(),
        }).ToList(),
    };

    // k=4：四个独立位图 = 四个选项，按阅读顺序当 A-D
    if (items.Count == 4)
    {
        var optsK4 = new Dictionary<string, string>();
        for (int idx = 0; idx < 4; idx++)
        {
            var pl = placed[idx];
            using var im = ItemImage(paper, pl.It);
            string path = Path.Combine(imgOutDir, $"{stemName}_{"ABCD"[idx]}.png");
            using var ct = CropTight(im);
            ct.SaveAsPng(path);
            optsK4["ABCD"[idx].ToString()] = path;
        }
        info["mode"] = "k4-positional";
        var bx4 = new Dictionary<string, object?>();
        for (int idx = 0; idx < 4; idx++)
        {
            var pl = placed[idx];
            bx4["ABCD"[idx].ToString()] = new object?[]
            {
                pl.Px, pl.Py,
                pl.Px + Math.Max(1, (int)((pl.It.Bbox[2] - pl.It.Bbox[0]) * pl.S)),
                pl.Py + Math.Max(1, (int)((pl.It.Bbox[3] - pl.It.Bbox[1]) * pl.S)),
            };
        }
        info["boxes"] = bx4;
        canvas.Dispose();
        return (null, optsK4, info);
    }

    // 2~3 块位图拼起来的题：跳过「字母 / 四格条 / 白缝」，只走最后的网格兜底
    bool multiBitmap = items.Count != 1;
    var cands = multiBitmap ? new List<Cand>() : LetterCandidates(mask);
    List<Cand>? letters;
    string why;
    if (cands.Count > 0) (letters, why) = PickFourLetters(cands, mask.H, mask.W);
    else { letters = null; why = "multi-bitmap"; }
    info["letters"] = cands.Count;

    int H = mask.H, W = mask.W;
    var plans = new List<(string name, Dictionary<string, int[]> boxes, int[]? band)>();

    var boxed = multiBitmap ? null : SplitBoxedRow(mask);
    if (boxed != null)
        plans.Add(("boxed", boxed.Value.Item1, new[] { boxed.Value.Item2, boxed.Value.Item3 }));

    if (letters != null && letters.Count > 0)
    {
        var rows = new List<List<Cand>>();
        foreach (var c in letters.OrderBy(c => c.Cy))
        {
            bool added = false;
            foreach (var r in rows)
                if (Math.Abs(c.Cy - r[0].Cy) <= 0.45 * c.H) { r.Add(c); added = true; break; }
            if (!added) rows.Add(new List<Cand> { c });
        }
        rows = rows.OrderBy(r => r.Average(c => c.Cy)).ToList();
        info["rows"] = rows.Select(r => r.Count).ToArray();
        var row0 = rows[0].OrderBy(c => c.Cx).ToList();
        double pitch = 0;
        for (int i = 0; i < row0.Count - 1; i++) pitch += row0[i + 1].Cx - row0[i].Cx;
        pitch /= Math.Max(1, row0.Count - 1);
        double avgH = letters.Average(c => c.H);
        int gapTh = (int)Math.Max(6, 0.5 * avgH);
        int thinTh = (int)Math.Max(4, 0.25 * avgH);
        info["pitch"] = Math.Round(pitch, 1, MidpointRounding.ToEven);
        string gdir = ProbeDirs(mask, letters, pitch);
        foreach (var (tag, useGlobal) in new[] { ("letters-mixed", false), ("letters-global", true) })
        {
            var bx = new Dictionary<string, int[]>();
            var used = new List<string>();
            for (int i = 0; i < 4; i++)
            {
                string d = useGlobal ? gdir : ProbeDirs(mask, new List<Cand> { letters[i] }, pitch);
                used.Add(d);
                var box = CropOption(mask, letters[i], pitch, gapTh, thinTh, d);
                if (box != null) bx["ABCD"[i].ToString()] = box;
            }
            if (BoxesOk(bx)) { plans.Add((tag, bx, null)); info["dirs"] = used; }
        }
    }

    if (!multiBitmap)
    {
        var alt = SplitByColumns(mask);
        if (alt != null && BoxesOk(alt)) plans.Add(("columns", alt, null));
    }

    bool twoByTwo = letters != null
        && info.TryGetValue("rows", out var rv) && rv is int[] ra
        && ra.SequenceEqual(new[] { 2, 2 });
    var cfg = G.OptionRects.TryGetValue($"{paper}:{no}", out var c0) ? c0 : null;

    // 人工标定优先：这几题自动切不可靠，框是逐题量出来的
    (string name, Dictionary<string, int[]> boxes, int[]? band)? chosen = null;
    if (cfg != null && cfg.Boxes != null)
    {
        int W0 = canvas.Width, H0 = canvas.Height;
        var bx = new Dictionary<string, int[]>();
        foreach (var kv in cfg.Boxes)
            bx[kv.Key] = new[]
            {
                (int)Math.Round(kv.Value[0] * W0, MidpointRounding.ToEven),
                (int)Math.Round(kv.Value[1] * H0, MidpointRounding.ToEven),
                (int)Math.Round(kv.Value[2] * W0, MidpointRounding.ToEven),
                (int)Math.Round(kv.Value[3] * H0, MidpointRounding.ToEven),
            };
        if (BoxesOk(bx)) { chosen = ("manual", bx, null); info["via"] = "manual"; }
    }

    var valid = plans.Where(p => BoxesOk(p.boxes)
        && !(p.name.StartsWith("letters") && letters != null && HitLetter(p.boxes, letters))
        && !(p.name.StartsWith("letters") && twoByTwo)).ToList();
    if (chosen == null && valid.Count > 0)
    {
        var bestPlan = valid
            .Select(p => (p, s: Coverage(mask, p.boxes) + (p.name == "boxed" ? 0.02 : 0)))
            .OrderByDescending(t => t.s).First().p;
        chosen = (bestPlan.name, bestPlan.boxes, bestPlan.band);
    }
    info["cover"] = valid.ToDictionary(
        p => p.name, p => (object?)Math.Round(Coverage(mask, p.boxes), 3, MidpointRounding.ToEven));

    if (chosen == null)
    {
        if (cfg != null && cfg.Method == "page")
        {
            var pageOpts = SplitPageOptions(paper, items[0].Page, cfg.Rect, cfg.Mode,
                                            imgOutDir, stemName, cfg.Boxes);
            if (pageOpts != null)
            {
                info["mode"] = "ok"; info["via"] = "page";
                canvas.Dispose();
                return (null, pageOpts, info);
            }
        }
        if (cfg != null && cfg.Method == "blocks")
        {
            var bx = SplitBlocksOptions(mask);
            if (bx != null && BoxesOk(bx)) { chosen = ("rect", bx, null); info["via"] = "rect-blocks"; }
        }
        else if (cfg != null && cfg.Rect != null)
        {
            var reg = SplitRegionOptions(mask, cfg.Rect, cfg.Mode ?? "");
            if (reg != null && BoxesOk(reg.Value.Item1))
            { chosen = ("rect", reg.Value.Item1, reg.Value.Item2); info["via"] = "rect"; }
        }
    }
    if (chosen == null)
    {
        var grid = SplitBlocksOptions(mask);
        if (grid != null && BoxesOk(grid)) { chosen = ("grid", grid, null); info["via"] = "grid"; }
        else
        {
            info["mode"] = "fail:" + (letters == null ? why : "no-valid-plan");
            info["plans"] = plans.Select(p => (object?)new List<object?> { p.name, p.boxes.Count }).ToList();
            canvas.Dispose();
            return (null, null, info);
        }
    }
    // 原脚本 info['via'], boxes, band = chosen 会用方案名覆盖之前设的 via
    info["via"] = chosen.Value.name;
    var boxes = chosen.Value.boxes;
    var band = chosen.Value.band;
    info["boxes"] = boxes.ToDictionary(kv => kv.Key,
        kv => (object?)kv.Value.Select(v => (object?)v).ToArray());
    var allLetters = letters != null ? new List<Cand>(letters) : new List<Cand>();
    letters = chosen.Value.name.StartsWith("letters") ? letters : new List<Cand>();
    double pitch2 = Mean(boxes.Values.Select(b => (double)(b[2] - b[0])).ToArray());
    if (pitch2 == 0) pitch2 = 1.0;

    var opts = new Dictionary<string, string>();
    foreach (char letter in "ABCD")
    {
        var b = boxes[letter.ToString()];
        using var im = CropL8(canvas, b[0], b[1], b[2], b[3]);
        // 只涂白「落在本框里的卷面字母」，不做二值化
        foreach (var c in allLetters)
        {
            int ox0 = Math.Max(b[0], c.X0 - 2) - b[0];
            int oy0 = Math.Max(b[1], c.Y0 - 2) - b[1];
            int ox1 = Math.Min(b[2], c.X1 + 2) - b[0];
            int oy1 = Math.Min(b[3], c.Y1 + 2) - b[1];
            if (ox1 - ox0 >= 2 && oy1 - oy0 >= 2)
                for (int yy = oy0; yy <= oy1 && yy < im.Height; yy++)
                    for (int xx = ox0; xx <= ox1 && xx < im.Width; xx++)
                        if (yy >= 0 && xx >= 0) im[xx, yy] = new L8(255);
        }
        string path = Path.Combine(imgOutDir, $"{stemName}_{letter}.png");
        using var ct = CropTight(im);
        using var sf = StripFrameBorder(ct);
        sf.SaveAsPng(path);
        opts[letter.ToString()] = path;
    }

    var cfg2 = G.OptionRects.TryGetValue($"{paper}:{no}", out var c2) ? c2 : null;
    L8Image? stem = null;
    if (cfg2 == null || !cfg2.NoStem)
        stem = BuildStem(canvas, boxes, letters ?? new List<Cand>(), pitch2, band);
    string? stemPath = null;
    if (stem == null || CountBelow(stem, 200) < 120) stemPath = null;
    else
    {
        stemPath = Path.Combine(imgOutDir, $"{stemName}.png");
        stem.SaveAsPng(stemPath);
    }
    stem?.Dispose();
    info["mode"] = "ok";
    canvas.Dispose();
    return (stemPath, opts, info);
}

// ---------------- 类型声明 ----------------

static class G
{
    public const int PAD = 8;
    public static string Here = "", Root = "", Build = "", Raw = "", ImgDir = "", Out = "";
    public static Dictionary<string, Dictionary<string, List<ImgItem>>> Idx = new();
    public static Dictionary<string, Dictionary<string, List<string>>> AnswersRaw = new();
    public static Dictionary<string, OptRectCfg> OptionRects = new();
    public static readonly Regex ImgRe = new(@"!\[[^\]]*\]\(\s*IMG\s*\)", RegexOptions.Compiled);
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
    public double[] RowMean()
    {
        var r = new double[H];
        for (int y = 0; y < H; y++)
        {
            int n = 0;
            for (int x = 0; x < W; x++) if (D[y * W + x]) n++;
            r[y] = W == 0 ? 0 : n / (double)W;
        }
        return r;
    }
    public double[] ColMean()
    {
        var r = new double[W];
        for (int x = 0; x < W; x++)
        {
            int n = 0;
            for (int y = 0; y < H; y++) if (D[y * W + x]) n++;
            r[x] = H == 0 ? 0 : n / (double)H;
        }
        return r;
    }
    public void SetRow(int y, bool v) { for (int x = 0; x < W; x++) D[y * W + x] = v; }
    public void SetCol(int x, bool v) { for (int y = 0; y < H; y++) D[y * W + x] = v; }
    public int RegionSum(int y0, int y1, int x0, int x1)
    {
        y0 = Math.Max(0, y0); x0 = Math.Max(0, x0);
        y1 = Math.Min(H, y1); x1 = Math.Min(W, x1);
        int n = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) if (D[y * W + x]) n++;
        return n;
    }
    // np.where(m) 的包围盒 [x0,y0,x1,y1]，空则返回 null
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

sealed class ImgItem
{
    public string File = "";
    public double[] Bbox = new double[4];
    public int Page, K, I;
}

sealed class Placed
{
    public int Px, Py;
    public ImgItem It = null!;
    public double S;
}

sealed class Cand
{
    public int Y0, Y1, X0, X1, H, W;
    public double Fill, Ring, Cx, Cy;
}

sealed class Q
{
    public int No;
    public string Stem = "";
    public string? Material;
    public Dictionary<string, string> Options = new();
    public bool MaterialSame;
    public int Page;
    public string Section = "";
    public bool Own;
}

sealed class OptRectCfg
{
    public string? Method, Mode;
    public Dictionary<string, double[]>? Boxes;
    public double[]? Rect;
    public bool NoStem;
}

sealed class CcResult
{
    public int[] Labels;
    public int N;
    public int[][] Boxes;   // [label] -> [y0,y1,x0,x1]
    public int[] Areas;
    public CcResult(int[] labels, int n, int[][] boxes, int[] areas)
    { Labels = labels; N = n; Boxes = boxes; Areas = areas; }
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




