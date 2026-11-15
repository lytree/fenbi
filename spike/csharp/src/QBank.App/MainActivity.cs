using Android.App;
using Android.OS;
using Android.Views;
using Android.Widget;
using QBank.Core;

namespace QBank.App;

/// <summary>
/// 最小闭环验收：在真机/模拟器上跑一遍「Assets 里的真实试卷 → QBank.Core 解析 → 判分」，
/// 把结果直接打在屏幕上。这一段通过了，就说明 C# 版不只是能编译，而是能在 Android 上跑通业务逻辑。
/// </summary>
[Activity(Label = "QBank C# 实验", MainLauncher = true, Theme = "@android:style/Theme.Material.Light")]
public class MainActivity : Activity
{
    static readonly (string Asset, int Papers, int Questions)[] Cases =
    {
        ("超格老于言语-进阶易错600题（导入模板）.xlsx", 17, 600),
        ("疯狂刷2500题-言语理解与表达600题（题目+答案解析）.xlsx", 15, 600),
    };

    TextView _log = null!;
    bool _allPassed = true;

    protected override void OnCreate(Bundle? savedInstanceState)
    {
        base.OnCreate(savedInstanceState);

        var root = new LinearLayout(this) { Orientation = Orientation.Vertical };
        root.SetPadding(Dp(18), Dp(36), Dp(18), Dp(18));
        root.SetBackgroundColor(Android.Graphics.Color.White);

        var title = new TextView(this);
        title.Text = "QBank · C# 实验（Core on Android）";
        title.SetTextColor(Android.Graphics.Color.Black);
        title.SetTextSize(Android.Util.ComplexUnitType.Sp, 16f);
        root.AddView(title);

        _log = new TextView(this);
        _log.SetTextColor(Android.Graphics.Color.ParseColor("#1A1A1A"));
        _log.SetTextSize(Android.Util.ComplexUnitType.Sp, 12f);
        _log.SetPadding(0, Dp(14), 0, 0);
        root.AddView(_log);

        var scroll = new ScrollView(this);
        scroll.AddView(root);
        SetContentView(scroll);

        Task.Run(Run);
    }

    void Run()
    {
        try
        {
            Log($"运行时：{System.Runtime.InteropServices.RuntimeInformation.FrameworkDescription}");
            var total = System.Diagnostics.Stopwatch.StartNew();

            foreach (var (asset, expectPapers, expectQuestions) in Cases)
            {
                var sw = System.Diagnostics.Stopwatch.StartNew();
                var path = CopyAsset(asset);
                var issues = new List<ImportIssue>();
                var papers = WorkbookImporter.ParseWorkbook(path, Path.GetFileNameWithoutExtension(asset), issues);
                var questions = papers.SelectMany(p => p.Questions).ToList();
                var parsed = sw.ElapsedMilliseconds;

                sw.Restart();
                var correct = questions.Count(q => Judge.Judge1(q, q.Answer).Correct);
                var judged = sw.ElapsedMilliseconds;

                var ok = papers.Count == expectPapers && questions.Count == expectQuestions && correct == questions.Count;
                _allPassed &= ok;

                Log($"{(ok ? "PASS" : "FAIL")}  {asset[..6]}…  {papers.Count} 卷 / {questions.Count} 题");
                Log($"      判分 {correct}/{questions.Count} · 解析 {parsed}ms · 判分 {judged}ms"
                    + (issues.Count > 0 ? $" · 信息 {issues.Count} 条" : ""));
            }

            Log($"总耗时 {total.ElapsedMilliseconds} ms");
            Log(_allPassed ? "→ 结论：Core 在 Android 上跑通" : "→ 结论：有失败项");
        }
        catch (Exception ex)
        {
            Log("EX " + ex.GetType().Name + ": " + ex.Message);
            Log(ex.StackTrace ?? "");
        }
    }

    string CopyAsset(string name)
    {
        var dst = Path.Combine(CacheDir!.AbsolutePath, name);
        if (File.Exists(dst) && new FileInfo(dst).Length > 0) return dst;
        using var input = Assets!.Open(name);
        using var output = File.Create(dst);
        input.CopyTo(output);
        return dst;
    }

    void Log(string line) => RunOnUiThread(() =>
    {
        _log.Text = string.IsNullOrEmpty(_log.Text) ? line : _log.Text + "\n" + line;
    });

    int Dp(int v) => (int)(v * Resources!.DisplayMetrics!.Density);
}
