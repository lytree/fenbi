#!/usr/bin/env dotnet
// 从 mozillazg/pinyin-data 的 pinyin.txt 生成 App 内置拼音表 assets/pinyin.txt。
//
// 用法：
//   dotnet run tools/GenPinyin.cs                          # 读 ./pinyin_full.txt，写 app/src/main/assets/pinyin.txt
//   dotnet run tools/GenPinyin.cs -- <源 pinyin.txt> <输出.txt>
//
// 源文件格式（pinyin-data 原样）：
//   U+554A: ā  # 啊
//   U+5973: nǚ # 女
//
// 输出格式：一行「字 拼音」，无声调、ü 记作 v，只取每个字的第一个读音（多音字按最常见读音）。
// 只收 BMP（U+FFFF 以内）：App 侧按 Char 建表，超出的字本来也索引不到。
//
// 数据源：https://github.com/mozillazg/pinyin-data （v0.15.0，2.7 万字）
// 这张表是词库导入算拼音/首字母的唯一依据，换源重跑脚本即可，别手改资产。

using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

Console.OutputEncoding = Encoding.UTF8;

var src = args.Length > 0 ? args[0] : "pinyin_full.txt";
var dst = args.Length > 1 ? args[1] : Path.Combine("app", "src", "main", "assets", "pinyin.txt");

if (!File.Exists(src))
{
    Console.Error.WriteLine($"找不到源文件：{src}");
    Console.Error.WriteLine("用法: dotnet run tools/GenPinyin.cs -- <源 pinyin.txt> <输出.txt>");
    return 2;
}

var rows = new List<(int Code, char Ch, string Pinyin)>();

foreach (var raw in File.ReadLines(src))
{
    var line = raw.Trim();
    if (line.Length == 0 || line[0] == '#') continue;
    var colon = line.IndexOf(':');
    if (colon < 0) continue;

    var addr = line[..colon].Trim().Replace("U+", "", StringComparison.OrdinalIgnoreCase);
    if (addr.StartsWith("0x", StringComparison.OrdinalIgnoreCase)) addr = addr[2..];
    if (!int.TryParse(addr, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var code)) continue;
    if (code > 0xFFFF) continue;

    // 「ā  # 啊」→ 取 # 之前、逗号之前的第一段
    var rest = line[(colon + 1)..];
    var hash = rest.IndexOf('#');
    if (hash >= 0) rest = rest[..hash];
    var py = StripTone(rest.Split(',')[0].Trim());
    if (py.Length == 0) continue;

    rows.Add((code, (char)code, py));
}

rows.Sort((a, b) => a.Code.CompareTo(b.Code));

var sb = new StringBuilder(rows.Count * 10);
sb.Append("# 汉字拼音表（单字 → 常用读音，无声调，ü 记作 v）\n");
sb.Append("# 数据源 https://github.com/mozillazg/pinyin-data pinyin.txt v0.15.0\n");
sb.Append("# 由 tools/GenPinyin.cs 生成，勿手改\n");
sb.Append('\n');
foreach (var (_, ch, py) in rows) sb.Append(ch).Append(' ').Append(py).Append('\n');

File.WriteAllText(dst, sb.ToString(), new UTF8Encoding(false));
Console.WriteLine($"chars {rows.Count}");
Console.WriteLine($"已写入 {dst}");
return 0;

/// <summary>
/// 去声调、ü 记作 v、只留 a-z。
/// 声调在 Unicode 里是组合用变音符号，先 NFD 拆开再按组合类过滤即可，
/// 不必为 ā á ǎ à 这些字符逐个建表。
/// </summary>
static string StripTone(string s)
{
    const string UmlautV = "üǖǘǚǜÜǕǗǙǛ";
    var sb = new StringBuilder(s.Length);
    foreach (var c in s) sb.Append(UmlautV.IndexOf(c) >= 0 ? 'v' : c);

    var decomposed = sb.ToString().Normalize(NormalizationForm.FormD);
    var plain = new StringBuilder(decomposed.Length);
    foreach (var c in decomposed)
    {
        var cat = CharUnicodeInfo.GetUnicodeCategory(c);
        if (cat is not (UnicodeCategory.NonSpacingMark
            or UnicodeCategory.SpacingCombiningMark
            or UnicodeCategory.EnclosingMark))
        {
            plain.Append(c);
        }
    }

    var result = new StringBuilder(plain.Length);
    foreach (var c in plain.ToString().ToLowerInvariant())
    {
        if (c is >= 'a' and <= 'z') result.Append(c);
    }
    return result.ToString();
}
