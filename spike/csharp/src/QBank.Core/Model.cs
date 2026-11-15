namespace QBank.Core;

// 模型：对应 Kotlin 的 data/model/Question.kt


public enum QType { Single, Multi, Uncertain, TrueFalse, Fill, Subjective }

public static class QTypeExt
{
    public static string Label(this QType t) => t switch
    {
        QType.Single => "单选题", QType.Multi => "多选题", QType.Uncertain => "不定项",
        QType.TrueFalse => "判断题", QType.Fill => "填空题", _ => "主观题",
    };
    public static bool HasOptions(this QType t) =>
        t is QType.Single or QType.Multi or QType.Uncertain or QType.TrueFalse;
    public static bool Objective(this QType t) => t != QType.Subjective;
    public static bool Answerable(this QType t) => t.HasOptions();

    public static QType? Parse(string? raw)
    {
        if (raw is null) return null;
        var s = raw.Trim().ToLowerInvariant().Replace(" ", "");
        if (s.Length == 0) return null;
        return s switch
        {
            "single" or "单选" or "单选题" or "选择" or "选择题" or "singlechoice" or "1" => QType.Single,
            "multi" or "多选" or "多选题" or "多项选择" or "multiple" or "multiplechoice" or "2" => QType.Multi,
            "uncertain" or "不定项" or "不定项选择题" or "不定项选择" or "partial" or "3" => QType.Uncertain,
            "judge" or "truefalse" or "判断" or "判断题" or "tf" or "bool" or "boolean" or "4" => QType.TrueFalse,
            "fill" or "填空" or "填空题" or "blank" or "cloze" or "5" => QType.Fill,
            "subjective" or "主观" or "主观题" or "简答" or "申论" or "材料分析" or "论述" or "essay" or "6" => QType.Subjective,
            _ => null,
        };
    }
}

public record Option(string Key, string Text);

public record Question
{
    public long Id { get; init; }
    public QType Type { get; init; } = QType.Single;
    public string Stem { get; init; } = "";
    public string Material { get; init; } = "";
    public List<Option> Options { get; init; } = new();
    public string Answer { get; init; } = "";
    public string Analysis { get; init; } = "";
    public string Knowledge { get; init; } = "";
    public int Difficulty { get; init; }
    public string Region { get; init; } = "";
    public string Subject { get; init; } = "";
    public string Source { get; init; } = "";
    public string Year { get; init; } = "";
    public double Score { get; init; } = 1.0;
    public string Keywords { get; init; } = "";

    public HashSet<string> AnswerKeys() =>
        Answer.Split(new[] { ',', ' ', '，', '、', ';', '；', '|' }, StringSplitOptions.RemoveEmptyEntries)
            .Select(s => s.Trim().ToUpperInvariant())
            .Where(s => s.Length > 0)
            .ToHashSet(StringComparer.Ordinal);
}

public class RawQuestion(int rowNo)
{
    public int RowNo { get; } = rowNo;
    public Dictionary<string, string> Values { get; } = new(StringComparer.Ordinal);
    public List<KeyValuePair<string, string>> Options { get; } = new();

    public string Get(string field) => Values.TryGetValue(field, out var v) ? v.Trim() : "";

    public void Put(string field, string? v)
    {
        if (!string.IsNullOrWhiteSpace(v)) Values[field] = v.Trim();
    }

    public void SetOption(string key, string value)
    {
        var i = Options.FindIndex(p => p.Key == key);
        if (i >= 0) Options[i] = new(key, value);
        else Options.Add(new(key, value));
    }
}

public enum Level { Error, Warn, Info }

public record ImportIssue(int RowNo, Level Level, string Message);

public record PaperDraft(string Name, List<Question> Questions, int TotalRows, int DuplicateSkipped, List<ImportIssue> Issues, string Origin = "");

public record AnswerResult(Question Q, string UserAnswer, double Earned, double Full, bool Correct, bool Partial, bool Pending = false);

public record Policy(bool MultiPartial = false, bool UncertainPartial = true, double UncertainPartialRatio = 0.5,
    bool FillPartial = true, bool SubjectivePartial = true, bool JudgeLenient = true)
{
    public static readonly Policy Default = new();
}

