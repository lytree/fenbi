#:property PublishAot=false
#:package SixLabors.ImageSharp@3.1.5
#:package SixLabors.ImageSharp.Drawing@2.1.4

// 生成图推题对照拼图：每张 sheet 6 道题，每行 [题号][题干][A][B][C][D]
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;
using SixLabors.ImageSharp.Processing;
using SixLabors.ImageSharp.Formats.Png;
using SixLabors.Fonts;
using Draw = SixLabors.ImageSharp.Drawing.Processing;
using SixLabors.ImageSharp.Drawing.Processing;

string root = args.Length > 0 ? args[0] : @"qbank_out\超格行测套卷";
string outDir = args.Length > 1 ? args[1] : @"tmp\figtype";
int perSheet = args.Length > 2 ? int.Parse(args[2]) : 6;
Directory.CreateDirectory(outDir);

int cell = 200, pad = 6, labelW = 96, headH = 34;
FontFamily fam;
if (!SystemFonts.TryGet("Consolas", out fam) && !SystemFonts.TryGet("Arial", out fam))
    fam = SystemFonts.Families.First();
Font font = fam.CreateFont(16);

var items = new List<(string vol, int no, string dir, string[] files)>();
for (int v = 1; v <= 10; v++)
{
    string vol = $"行测套卷{v}";
    string imgDir = Path.Combine(root, vol, "images");
    if (!Directory.Exists(imgDir)) continue;
    var nums = Directory.GetFiles(imgDir, "q*_A.png")
        .Select(f => Path.GetFileName(f))
        .Select(n => int.Parse(n.Substring(1, 3)))
        .OrderBy(n => n).ToList();
    foreach (int n in nums)
    {
        string stem = Path.Combine(imgDir, $"q{n:D3}.png");
        items.Add((vol, n, imgDir, new[] {
            File.Exists(stem) ? stem : "",
            Path.Combine(imgDir, $"q{n:D3}_A.png"),
            Path.Combine(imgDir, $"q{n:D3}_B.png"),
            Path.Combine(imgDir, $"q{n:D3}_C.png"),
            Path.Combine(imgDir, $"q{n:D3}_D.png"),
        }));
    }
}
Console.WriteLine($"questions={items.Count}");

int sheets = (items.Count + perSheet - 1) / perSheet;
for (int s = 0; s < sheets; s++)
{
    var batch = items.Skip(s * perSheet).Take(perSheet).ToList();
    int rows = batch.Count;
    int W = labelW + 5 * (cell + pad) + pad;
    int H = headH + rows * (cell + pad) + pad;
    using var canvas = new Image<Rgba32>(W, H, new Rgba32(255, 255, 255));
    canvas.Mutate(c => c.Fill(Color.White));

    string[] heads = { "题号", "题干", "A", "B", "C", "D" };
    for (int i = 0; i < heads.Length; i++)
    {
        float x = i == 0 ? 6 : labelW + (i - 1) * (cell + pad) + 6;
        canvas.Mutate(c => c.DrawText(new RichTextOptions(font) { Origin = new PointF(x, 8) }, heads[i], Color.ParseHex("888888")));
    }
    canvas.Mutate(c => c.DrawLine(Color.ParseHex("DDDDDD"), 2, new PointF(0, headH - 3), new PointF(W, headH - 3)));

    for (int r = 0; r < rows; r++)
    {
        var it = batch[r];
        int y0 = headH + r * (cell + pad);
        string tag = $"{it.vol.Replace("行测套卷", "")}-{it.no}";
        canvas.Mutate(c => c.DrawText(new RichTextOptions(font) { Origin = new PointF(6, y0 + 6) }, tag, Color.ParseHex("333333")));

        for (int k = 0; k < it.files.Length; k++)
        {
            int x0 = labelW + k * (cell + pad);
            // 边框
            canvas.Mutate(c => c.Draw(Color.ParseHex("E8E8E8"), 1f,
                new RectangleF(x0, y0, cell, cell)));
            string f = it.files[k];
            if (f == "" || !File.Exists(f)) continue;
            using var src = Image.Load<Rgba32>(f);
            float sc = Math.Min((float)(cell - 8) / src.Width, (float)(cell - 8) / src.Height);
            int tw = Math.Max(1, (int)(src.Width * sc)), th = Math.Max(1, (int)(src.Height * sc));
            src.Mutate(c => c.Resize(tw, th));
            int ox = x0 + (cell - tw) / 2, oy = y0 + (cell - th) / 2;
            canvas.Mutate(c => c.DrawImage(src, new Point(ox, oy), 1f));
        }
    }
    string path = Path.Combine(outDir, $"sheet_{s + 1:D2}.png");
    canvas.Save(path, new PngEncoder());
    Console.WriteLine($"{path} -> {string.Join(", ", batch.Select(b => $"{b.vol.Replace("行测套卷","")}-{b.no}"))}");
}
Console.WriteLine("done");
