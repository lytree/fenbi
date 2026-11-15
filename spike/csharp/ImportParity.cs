#:project ./src/QBank.Core/QBank.Core.csproj

using QBank.Core;

// 实验：把 QBank 的导入 + 判题链路整体移植到 C#，用仓库里的两份真实试卷验证结果与 Kotlin 版一致。
//
// 移植对照（Kotlin 源文件 → 本文件）：
//   data/model/Question.kt        → QType / Option / Question
//   data/importer/Normalizer.kt   → FieldAliases / Normalizer
//   data/importer/ColumnSniffer.kt→ ColumnSniffer
//   data/importer/CsvImporter.kt  → ParseRows（只取表格解析部分，编码/分隔符探测不涉及）
//   data/importer/Importer.kt     → ParseWorkbook / dedup
//   engine/Judge.kt               → Judge
//
// 未移植（本次不需要，见文末报告）：PaperSplitter 的「试卷」列拆卷、StructureCheck、XlsxReader 的稀疏行补齐。

using System.Text;
using System.Text.RegularExpressions;

// 回归基线：逻辑已下沉到 src/QBank.Core，这里只跑「真实试卷 → 解析 → 判题」的对照，数字必须每次一致。
// 用法：dotnet run ImportParity.cs [仓库根目录]
var root = args.Length > 0 ? args[0] : @"F:\Code\Android\fenbi";
var cases = new (string File, string ExpectedName, int ExpectSheets, int ExpectQuestions)[]
{
    ("超格老于言语-进阶易错600题（导入模板）.xlsx", "超格老于言语", 17, 600),
    ("疯狂刷2500题-言语理解与表达600题（题目+答案解析）.xlsx", "疯狂刷2500题", 15, 600),
};

Console.WriteLine("== 一、真实试卷解析对照 ==");
var allPassed = true;
foreach (var c in cases)
{
    var path = Path.Combine(root, c.File);
    var issues = new List<ImportIssue>();
    var papers = WorkbookImporter.ParseWorkbook(path, Path.GetFileNameWithoutExtension(c.File), issues);

    var questions = papers.SelectMany(p => p.Questions).ToList();
    var noAnswer = questions.Count(q => string.IsNullOrWhiteSpace(q.Answer));
    var badOptions = questions.Count(q => q.Options.Count != 4);
    var emptyStem = questions.Count(q => string.IsNullOrWhiteSpace(q.Stem));
    var dup = papers.Sum(p => p.DuplicateSkipped);
    var dist = questions.GroupBy(q => q.Answer).OrderBy(g => g.Key)
        .Select(g => $"{g.Key}={g.Count()}").ToList();

    var ok = papers.Count == c.ExpectSheets && questions.Count == c.ExpectQuestions
             && noAnswer == 0 && badOptions == 0 && emptyStem == 0;
    allPassed &= ok;

    Console.WriteLine($"\n[{c.ExpectedName}] {(ok ? "PASS" : "FAIL")}");
    Console.WriteLine($"  试卷(工作表)数 : {papers.Count,5}  期望 {c.ExpectSheets}");
    Console.WriteLine($"  题目总数       : {questions.Count,5}  期望 {c.ExpectQuestions}");
    Console.WriteLine($"  缺答案 / 选项≠4 / 空题干 : {noAnswer} / {badOptions} / {emptyStem}");
    Console.WriteLine($"  卷内去重跳过   : {dup}");
    Console.WriteLine($"  答案分布       : {string.Join(" ", dist)}");
    Console.WriteLine($"  题型分布       : {string.Join(" ", questions.GroupBy(q => q.Type.Label()).Select(g => $"{g.Key}={g.Count()}"))}");
    Console.WriteLine($"  校验信息       : {issues.Count(i => i.Level == Level.Error)} 错误 / {issues.Count(i => i.Level == Level.Warn)} 警告 / {issues.Count(i => i.Level == Level.Info)} 提示");
    foreach (var m in papers) Console.WriteLine($"    · {m.Name,-24} {m.Questions.Count,4} 题");
}

Console.WriteLine("\n== 二、判题对拍（真实题目：喂标准答案应全对，喂空应全空） ==");
var judgeCases = new List<(string Name, Question Q, string User, bool ExpectCorrect, double ExpectEarned, bool ExpectBlank, bool ExpectPending)>();
foreach (var c in cases)
{
    var path = Path.Combine(root, c.File);
    var qs = WorkbookImporter.ParseWorkbook(path, c.File, new List<ImportIssue>()).SelectMany(p => p.Questions).ToList();
    foreach (var q in qs)
    {
        judgeCases.Add(($"{c.ExpectedName}/标准答案", q, q.Answer, true, q.Score, false, false));
        judgeCases.Add(($"{c.ExpectedName}/空白", q, "", false, 0, true, false));
        var wrongKey = new[] { "A", "B", "C", "D", "E" }.First(k => !q.AnswerKeys().Contains(k));
        judgeCases.Add(($"{c.ExpectedName}/错答", q, wrongKey, false, 0, false, false));
    }
}

var jPassed = 0;
var jFailed = 0;
foreach (var (name, q, user, expCorrect, expEarned, expBlank, expPending) in judgeCases)
{
    var r = Judge.Judge1(q, user);
    var ok = r.Correct == expCorrect && Math.Abs(r.Earned - expEarned) < 1e-9
             && (string.IsNullOrWhiteSpace(r.UserAnswer)) == expBlank && r.Pending == expPending;
    if (ok) jPassed++;
    else
    {
        jFailed++;
        if (jFailed <= 5)
            Console.WriteLine($"  FAIL {name}: correct={r.Correct} earned={r.Earned} 期望 {expCorrect}/{expEarned}「{q.Stem[..Math.Min(20, q.Stem.Length)]}」");
    }
}
Console.WriteLine($"  通过 {jPassed} / {judgeCases.Count}，失败 {jFailed}");
allPassed &= jFailed == 0;

Console.WriteLine("\n== 三、判题规则单测（真实数据只有单选，其余题型用构造用例） ==");
var ruleTests = new (string Name, Question Q, string User, double Expect, Policy? P)[]
{
    ("多选·全对",        Q(QType.Multi, "A,C", 2),               "A,C",   2, null),
    ("多选·少选默认不给分", Q(QType.Multi, "A,C", 2),             "A",     0, null),
    ("多选·少选开部分分",  Q(QType.Multi, "A,C", 2),               "A",     1, new Policy(MultiPartial: true)),
    ("不定项·少选半分",    Q(QType.Uncertain, "A,C", 2),           "C",     1, null),
    ("不定项·多选错项",    Q(QType.Uncertain, "A,C", 2),           "AB",    0, null),
    ("判断·A当正确",       Q(QType.TrueFalse, "T", 1),             "A",     1, null),
    ("判断·×当错误",       Q(QType.TrueFalse, "F", 1),             "×",     1, null),
    ("填空·全对",         Q(QType.Fill, "北京|上海", 2),          "北京|上海", 2, null),
    ("填空·部分给分",      Q(QType.Fill, "北京|上海", 2),          "北京",   1, null),
    ("主观·关键词全中",    Q(QType.Subjective, "", 10, keywords: "市场;政府"), "既要发挥市场作用，也要政府调控", 10, null),
    ("主观·关键词半中",    Q(QType.Subjective, "", 10, keywords: "市场;政府"), "只谈市场", 5, null),
    ("待补答案不判分",     Q(QType.Single, "", 1, opts: true),     "A",     0, null),
};
var rPassed = 0;
foreach (var (name, q, user, expect, pol) in ruleTests)
{
    var r = Judge.Judge1(q, user, pol);
    var ok = Math.Abs(r.Earned - expect) < 1e-9;
    if (ok) rPassed++;
    else Console.WriteLine($"  FAIL {name}: earned={r.Earned} 期望 {expect}");
    if (name == "待补答案不判分" && !r.Pending) { rPassed -= 1; Console.WriteLine("  FAIL 待补答案未标 pending"); }
}
Console.WriteLine($"  通过 {rPassed} / {ruleTests.Length}");
allPassed &= rPassed == ruleTests.Length;

Console.WriteLine("\n== 四、表头别名与无表头嗅探 ==");
var aliasTests = new (string Header, string? Expect)[]
{
    ("题干", Field.Stem), ("question", Field.Stem), ("题目内容", Field.Stem),
    ("选 项 A", "opt:A"), ("option_b", "opt:B"), ("选项3", "opt:C"),
    ("分 值", Field.Score), ("知识点标签", Field.Knowledge), ("得分点", Field.Keywords),
    ("卷名", Field.Paper), ("题库", null),
};
var aPassed = 0;
foreach (var (h, exp) in aliasTests)
{
    var got = FieldAliases.ResolveOption(h) is string k ? "opt:" + k : FieldAliases.Resolve(h);
    if (got == exp) aPassed++;
    else Console.WriteLine($"  FAIL 「{h}」→ {got ?? "null"} 期望 {exp ?? "null"}");
}
Console.WriteLine($"  通过 {aPassed} / {aliasTests.Length}");
allPassed &= aPassed == aliasTests.Length;

var sniff = ColumnSniffer.Detect(new List<List<string>>
{
    new() { "1", "下列词语中加点字的读音全都正确的一项是", "刚愎自用", "殚精竭虑", "病入膏肓", "一蹴而就", "C" },
    new() { "2", "文段意在说明的是以下哪一项内容呢", "淋漓尽致", "栩栩如生", "惟妙惟肖", "跃然纸上", "B" },
    new() { "3", "填入划横线部分最恰当的一项是", "熙熙攘攘", "川流不息", "络绎不绝", "车水马龙", "A" },
});
var sniffOk = sniff != null && sniff.Fields.Count == 2 && sniff.Options.Count == 4 && sniff.DataStart == 0;
Console.WriteLine($"  无表头嗅探：{(sniffOk ? "PASS" : "FAIL")}（{sniff?.Describe() ?? "null"}）");
allPassed &= sniffOk;

Console.WriteLine();
Console.WriteLine(allPassed ? "== 结论：全部通过 ==" : "== 结论：存在失败项 ==");
return allPassed ? 0 : 1;

static Question Q(QType type, string answer, double score, bool multiPartial = false, string keywords = "", bool opts = false) =>
    new()
    {
        Type = type, Answer = answer, Score = score, Keywords = keywords,
        Stem = "构造用例",
        Options = opts || type.HasOptions()
            ? new List<Option> { new("A", "甲"), new("B", "乙"), new("C", "丙"), new("D", "丁") }
            : new List<Option>(),
    };
