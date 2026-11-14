#!/usr/bin/env dotnet
#:property PublishAot=false
// 把 tmp/build/redraw 下的重绘图写回各套的 images（覆盖单卷 zip），原图先备份。
//
//   dotnet run tools/qbank/ApplyRedraw.cs            # 写回 zip（原图备份到 bak/<paper>/）
//   dotnet run tools/qbank/ApplyRedraw.cs -- --dry   # 只报告会改哪些
//
// 之后跑 `uv run --with openpyxl python tools/qbank/merge_exam_packages.py` 重新汇总。

using System.IO.Compression;

var baseDir = Path.GetFullPath(Path.Combine("qbank_out", "超格行测套卷"));
var root = Directory.GetParent(baseDir)!.Parent!.FullName;
var redrawRoot = Path.Combine(root, "tmp", "build", "redraw");
bool dry = args.Contains("--dry");

if (!Directory.Exists(redrawRoot))
{
    Console.Error.WriteLine($"找不到重绘目录：{redrawRoot}");
    return 2;
}

var zips = Directory.GetFiles(baseDir, "行测套卷*.zip").OrderBy(p => p).ToArray();
int totalReplaced = 0;
foreach (var zip in zips)
{
    var paper = Path.GetFileNameWithoutExtension(zip);
    var paperRedraw = Path.Combine(redrawRoot, paper);
    if (!Directory.Exists(paperRedraw)) { Console.WriteLine($"{paper}: 无重绘结果，跳过"); continue; }

    var replaced = new List<string>();
    var bakDir = Path.Combine(baseDir, "bak", paper);
    Directory.CreateDirectory(bakDir);

    // 先备份原 zip 里的图片
    using (var zin = ZipFile.OpenRead(zip))
        foreach (var e in zin.Entries)
            if (e.FullName.StartsWith("images/") && e.Name.EndsWith(".png"))
            {
                var dst = Path.Combine(bakDir, e.Name);
                if (!File.Exists(dst)) e.ExtractToFile(dst);
            }

    if (dry)
    {
        Console.WriteLine($"{paper}: 将替换 {Directory.GetFiles(paperRedraw, "*.png").Length} 张");
        continue;
    }

    var tmp = zip + ".tmp";
    using (var zin = ZipFile.OpenRead(zip))
    using (var zout = ZipFile.Open(tmp, ZipArchiveMode.Create))
    {
        foreach (var e in zin.Entries)
        {
            var ne = zout.CreateEntry(e.FullName, CompressionLevel.Optimal);
            using var os = ne.Open();
            if (e.FullName.StartsWith("images/") && e.Name.EndsWith(".png"))
            {
                var rp = Path.Combine(paperRedraw, e.Name);
                if (File.Exists(rp))
                {
                    using var rs = File.OpenRead(rp);
                    rs.CopyTo(os);
                    replaced.Add(e.Name);
                    continue;
                }
            }
            using var es = e.Open();
            es.CopyTo(os);
        }
    }
    File.Delete(zip);
    File.Move(tmp, zip);
    totalReplaced += replaced.Count;
    Console.WriteLine($"{paper}: 替换 {replaced.Count} 张（原图备份 -> bak/{paper}）");
}

Console.WriteLine($"\n合计替换 {totalReplaced} 张。下一步：重新汇总——");
Console.WriteLine("  uv run --with openpyxl python tools/qbank/merge_exam_packages.py");
return 0;
