using System.Text;
using System.Text.RegularExpressions;
using ClosedXML.Excel;

namespace QBank.Core;

// 导入：工作簿解析 / 表头别名 / 列嗅探 / 规范化
// 对应 Kotlin 的 data/importer/{Importer,Normalizer,ColumnSniffer,CsvImporter}.kt

public static class WorkbookImporter
{

public static List<PaperDraft> ParseWorkbook(string path, string fallbackName, List<ImportIssue> fileIssues)
{
    var sheets = new List<(string Name, List<List<string>> Rows)>();
    using (var wb = new XLWorkbook(path))
    {
        foreach (var ws in wb.Worksheets)
        {
            var used = ws.RangeUsed();
            var rows = new List<List<string>>();
            if (used != null)
            {
                var cols = used.ColumnCount();
                foreach (var row in used.Rows())
                {
                    var cells = new List<string>(cols);
                    for (var c = 1; c <= cols; c++) cells.Add(row.Cell(c).GetFormattedString().Trim());
                    rows.Add(cells);
                }
            }
            sheets.Add((ws.Name, rows));
        }
    }
    if (sheets.Count == 0)
    {
        fileIssues.Add(new ImportIssue(0, Level.Error, "Excel 中没有可读数据"));
        return new List<PaperDraft>();
    }

    var parsed = sheets.Select(s =>
    {
        var sheetIssues = new List<ImportIssue>();
        var raws = RowParser.ParseRows(s.Rows, sheetIssues, "XLSX");
        return (s.Name, Raws: raws, Issues: sheetIssues);
    }).ToList();

    var withQuestions = parsed.Where(p => p.Raws.Count > 0).ToList();
    var multiSheet = parsed.Count > 1;

    if (withQuestions.Count == 0)
    {
        foreach (var s in parsed)
        {
            var first = s.Issues.FirstOrDefault(i => i.Level == Level.Error) ?? s.Issues.FirstOrDefault();
            fileIssues.Add(new ImportIssue(0, Level.Error,
                (multiSheet ? $"工作表「{s.Name}」" : "") + (first?.Message ?? "没有可导入的题目")));
        }
        return new List<PaperDraft>();
    }

    foreach (var s in parsed.Where(p => p.Raws.Count == 0))
    {
        var first = s.Issues.FirstOrDefault(i => i.Level == Level.Error);
        fileIssues.Add(new ImportIssue(0, Level.Info,
            $"工作表「{s.Name}」没有可导入的题目，已忽略" + (first != null ? $"（{first.Message}）" : "")));
    }

    foreach (var s in withQuestions)
        foreach (var i in s.Issues)
            fileIssues.Add(multiSheet ? i with { Message = $"「{s.Name}」{i.Message}" } : i);

    var drafts = new List<PaperDraft>();
    foreach (var s in withQuestions)
    {
        var issues = new List<ImportIssue>();
        var questions = new List<Question>();
        foreach (var raw in s.Raws)
        {
            var q = Normalizer.ToQuestion(raw, issues);
            if (q != null) questions.Add(q);
        }
        var (unique, dup) = Dedup(questions);
        if (dup > 0) issues.Add(new ImportIssue(0, Level.Info, $"{dup} 题内容完全重复，已自动跳过"));
        if (unique.Count > 0) drafts.Add(new PaperDraft(s.Name, unique, s.Raws.Count, dup, issues));
    }

    foreach (var d in drafts.Where(d => d.Questions.Count == 0))
    {
        var first = d.Issues.FirstOrDefault(i => i.Level == Level.Error);
        fileIssues.Add(new ImportIssue(0, Level.Info, $"「{d.Name}」没有可导入的题目，已跳过" + (first != null ? $"：{first.Message}" : "")));
    }
    return drafts.Where(d => d.Questions.Count > 0).ToList();
}

static (List<Question>, int) Dedup(List<Question> list)
{
    if (list.Count < 2) return (list, 0);
    var seen = new HashSet<string>(StringComparer.Ordinal);
    var output = new List<Question>(list.Count);
    var dup = 0;
    foreach (var q in list)
    {
        if (seen.Add(Fingerprint(q))) output.Add(q);
        else dup++;
    }
    return (output, dup);
}

static string Fingerprint(Question q)
{
    static string Norm(string s) => new(s.Where(c => !char.IsWhiteSpace(c)).ToArray());
    var sb = new StringBuilder();
    sb.Append(Norm(q.Stem)).Append('\u0001').Append(Norm(q.Material)).Append('\u0001');
    foreach (var o in q.Options) sb.Append(o.Key).Append('=').Append(Norm(o.Text)).Append('\u0002');
    return sb.ToString();
}

}

// ============================ 表头别名 ============================

public static class Field
{
    public const string Type = "type", No = "no", Stem = "stem", Material = "material", Images = "images",
        Options = "options", Answer = "answer", Analysis = "analysis", Knowledge = "knowledge",
        Difficulty = "difficulty", Region = "region", Subject = "subject", Source = "source",
        Year = "year", Score = "score", Keywords = "keywords", Paper = "paper";
}

public static class FieldAliases
{
    static readonly Dictionary<string, string[]> Aliases = new()
    {
        [Field.Type] = new[] { "题型", "类型", "题目类型", "type", "questiontype", "qtype", "category" },
        [Field.No] = new[] { "题号", "题序", "序号", "顺序", "编号", "题目编号", "第几题", "no", "number", "num", "index", "idx", "seq", "questionno", "qno" },
        [Field.Stem] = new[] { "题干", "题目", "试题", "问题", "题目内容", "stem", "question", "title", "content", "questioncontent", "problem", "q" },
        [Field.Material] = new[] { "材料", "材料内容", "背景", "背景材料", "资料", "material", "passage", "context", "stem2" },
        [Field.Images] = new[] { "图片", "图片地址", "配图", "插图", "题图", "图", "image", "images", "img", "pic", "picture", "figure", "photo" },
        [Field.Options] = new[] { "选项", "选项列表", "options", "choices", "optionlist" },
        [Field.Answer] = new[] { "答案", "正确答案", "标准答案", "参考答案", "answer", "correctanswer", "key", "rightanswer", "ans" },
        [Field.Analysis] = new[] { "解析", "答案解析", "试题解析", "说明", "备注", "analysis", "explanation", "explain", "remark", "note", "solution" },
        [Field.Knowledge] = new[] { "知识点", "考点", "知识点标签", "标签", "knowledge", "point", "tag", "knowledgepoint" },
        [Field.Difficulty] = new[] { "难度", "难度等级", "difficulty", "level", "hard", "stars" },
        [Field.Region] = new[] { "地区", "省份", "城市", "地域", "region", "area", "province" },
        [Field.Subject] = new[] { "科目", "学科", "分类", "模块", "subject", "category", "module", "section" },
        [Field.Source] = new[] { "来源", "出处", "试卷来源", "source", "from", "origin" },
        [Field.Year] = new[] { "年份", "年度", "year", "date", "yr" },
        [Field.Score] = new[] { "分值", "分", "分数", "每题分值", "score", "points", "value", "mark" },
        [Field.Keywords] = new[] { "得分点", "关键词", "采分点", "keywords", "keypoints", "scoringpoints" },
        [Field.Paper] = new[] { "试卷", "试卷名", "试卷名称", "卷名", "卷子", "套卷", "套题", "试题卷", "试卷编号", "试卷序号", "卷号", "paper", "papername", "paperno", "examname", "testname", "exam", "testpaper" },
    };

    static readonly Dictionary<string, string[]> OptionAliases = new()
    {
        ["A"] = new[] { "A", "选项A", "A选项", "optiona", "a选项", "A.", "选A", "答案A" },
        ["B"] = new[] { "B", "选项B", "B选项", "optionb", "b选项", "B.", "选B", "答案B" },
        ["C"] = new[] { "C", "选项C", "C选项", "optionc", "c选项", "C.", "选C", "答案C" },
        ["D"] = new[] { "D", "选项D", "D选项", "optiond", "d选项", "D.", "选D", "答案D" },
        ["E"] = new[] { "E", "选项E", "E选项", "optione", "e选项", "E.", "选E", "答案E" },
        ["F"] = new[] { "F", "选项F", "F选项", "optionf", "f选项", "F.", "选F", "答案F" },
        ["G"] = new[] { "G", "选项G", "G选项", "optiong", "g选项", "G.", "选G", "答案G" },
        ["H"] = new[] { "H", "选项H", "H选项", "optionh", "h选项", "H.", "选H", "答案H" },
    };

    static readonly Dictionary<string, HashSet<string>> NormAliases =
        Aliases.ToDictionary(kv => kv.Key, kv => kv.Value.Select(NormHeader).ToHashSet(StringComparer.Ordinal));
    static readonly Dictionary<string, HashSet<string>> NormOptions =
        OptionAliases.ToDictionary(kv => kv.Key, kv => kv.Value.Select(NormHeader).ToHashSet(StringComparer.Ordinal));

    public static string NormHeader(string h)
    {
        var sb = new StringBuilder(h.Length);
        foreach (var ch in h)
        {
            if (ch >= 0xFF01 && ch <= 0xFF5E) sb.Append((char)(ch - 0xFEE0));
            else if (char.IsWhiteSpace(ch) || ch is '_' or '-' or '*' or ':' or '：' or '(' or ')' or '（' or '）') { }
            else sb.Append(char.ToLowerInvariant(ch));
        }
        return sb.ToString();
    }

    public static string? Resolve(string header)
    {
        var h = NormHeader(header);
        if (h.Length == 0) return null;
        foreach (var (field, set) in NormAliases) if (set.Contains(h)) return field;
        return null;
    }

    public static string? ResolveOption(string header)
    {
        var h = NormHeader(header);
        if (h.Length == 0) return null;
        foreach (var (key, set) in NormOptions) if (set.Contains(h)) return key;
        var m = Regex.Match(h, "^(?:选项|option)?([1-8])$");
        if (m.Success) return ((char)('A' + int.Parse(m.Groups[1].Value) - 1)).ToString();
        return null;
    }
}

// ============================ 无表头列嗅探 ============================

public static class ColumnSniffer
{
    const int SampleRows = 80;
    const double OptionMaxAvg = 60;

    public record Layout(int DataStart, Dictionary<int, string> Fields, Dictionary<int, string> Options)
    {
        public string Describe()
        {
            var parts = new List<string>();
            foreach (var (c, f) in Fields.OrderBy(kv => kv.Key))
                parts.Add($"第 {c + 1} 列 → " + (f == Field.Stem ? "题干" : f == Field.Answer ? "答案" : f));
            foreach (var (c, k) in Options.OrderBy(kv => kv.Key))
                parts.Add($"第 {c + 1} 列 → 选项 {k}");
            return string.Join("，", parts);
        }
    }

    public static Layout? Detect(List<List<string>> table)
    {
        if (table.Count < 3) return null;
        foreach (var start in CandidateStarts(table))
        {
            var rows = table.Skip(start).Take(SampleRows).ToList();
            if (rows.Count >= 2)
            {
                var l = LayoutOf(rows, start);
                if (l != null) return l;
            }
        }
        return null;
    }

    static IEnumerable<int> CandidateStarts(List<List<string>> table)
    {
        var first = table[0];
        var looksHeader = first.All(v => string.IsNullOrWhiteSpace(v) || v.Trim().Length <= 6);
        return looksHeader ? new[] { 1, 0 } : new[] { 0, 1 };
    }

    static Layout? LayoutOf(List<List<string>> rows, int start)
    {
        var width = rows.Max(r => r.Count);
        if (width < 2) return null;

        var cols = Enumerable.Range(0, width)
            .Select(c => rows.Select(r => c < r.Count ? r[c].Trim() : "").ToList())
            .ToList();

        var solid = Enumerable.Range(0, width)
            .Where(c => cols[c].Count(v => v.Length > 0) * 100 >= rows.Count * 60)
            .ToList();
        if (solid.Count == 0) return null;

        int? answerCol = null;
        foreach (var c in solid)
        {
            var vals = cols[c].Where(v => v.Length > 0).ToList();
            if (vals.Count >= 2 && vals.All(v => v.Length == 1 && char.ToUpperInvariant(v[0]) is >= 'A' and <= 'H'))
            {
                answerCol = c;
                break;
            }
        }

        int? stemCol = null;
        var bestAvg = -1.0;
        foreach (var c in solid.Where(c => c != answerCol))
        {
            var vals = cols[c].Where(v => v.Length > 0).ToList();
            if (!vals.Any(v => v.Length > 4) || vals.All(v => v.Length == 1)) continue;
            var avg = vals.Average(v => (double)v.Length);
            if (avg > bestAvg) { bestAvg = avg; stemCol = c; }
        }
        if (stemCol is null) return null;

        var options = new Dictionary<int, string>();
        var optionEnd = answerCol is int ac && ac > stemCol.Value ? ac : width;
        var key = 'A';
        for (var c = stemCol.Value + 1; c < optionEnd; c++)
        {
            var vals = cols[c].Where(v => v.Length > 0).ToList();
            var avg = vals.Count == 0 ? 0.0 : vals.Average(v => (double)v.Length);
            if (vals.Count < 2 || avg > OptionMaxAvg || cols[c].Distinct().Count() < 2) break;
            options[c] = key.ToString();
            key++;
            if (options.Count >= 8) break;
        }

        if (answerCol is null && options.Count == 0) return null;

        var fields = new Dictionary<int, string> { [stemCol.Value] = Field.Stem };
        if (answerCol is int a2) fields[a2] = Field.Answer;
        return new Layout(start, fields, options);
    }
}

// ============================ 表格 → RawQuestion ============================

public static class RowParser
{
    static readonly char[] Splits = { '\n', '|', '；', ';' };

    public static List<RawQuestion> ParseRows(List<List<string>> table, List<ImportIssue> issues, string format)
    {
        if (table.Count == 0)
        {
            issues.Add(new ImportIssue(0, Level.Error, "文件内容为空或无法解析"));
            return new List<RawQuestion>();
        }

        var header = table[0];
        var fieldByCol = new Dictionary<int, string>();
        var optionByCol = new Dictionary<int, string>();

        for (var i = 0; i < header.Count; i++)
        {
            var opt = FieldAliases.ResolveOption(header[i]);
            if (opt != null) { optionByCol[i] = opt; continue; }
            var f = FieldAliases.Resolve(header[i]);
            if (f != null && !fieldByCol.ContainsValue(f)) fieldByCol[i] = f;
        }

        var dataStart = 1;
        if (!fieldByCol.ContainsValue(Field.Stem))
        {
            var layout = ColumnSniffer.Detect(table);
            if (layout == null)
            {
                issues.Add(new ImportIssue(1, Level.Error, "未找到「题干/题目/stem」列，无法识别表头"));
                return new List<RawQuestion>();
            }
            fieldByCol = new Dictionary<int, string>(layout.Fields);
            optionByCol = new Dictionary<int, string>(layout.Options);
            dataStart = layout.DataStart;
            issues.Add(new ImportIssue(1, Level.Warn,
                $"没有表头，已按内容推断列含义：{layout.Describe()}。请核对下面的预览，不对就补上表头再导入"));
        }

        var raws = new List<RawQuestion>();
        for (var r = dataStart; r < table.Count; r++)
        {
            var row = table[r];
            if (row.All(string.IsNullOrWhiteSpace)) continue;
            var raw = new RawQuestion(r + 1);
            foreach (var (col, field) in fieldByCol)
                if (col < row.Count) raw.Put(field, row[col]);
            foreach (var (col, k) in optionByCol)
                if (col < row.Count) raw.SetOption(k, row[col]);

            foreach (var (col, field) in fieldByCol)
                if (field == Field.Options && col < row.Count && !string.IsNullOrWhiteSpace(row[col]))
                    ParseInlineOptions(raw, row[col]);

            raws.Add(raw);
        }
        return raws;
    }

    static readonly Regex InlineOption = new(@"^([A-Ha-h])\s*[.、．:：)）]\s*(.*)$", RegexOptions.Singleline);

    static void ParseInlineOptions(RawQuestion raw, string text)
    {
        foreach (var p in text.Split(Splits).Select(s => s.Trim()).Where(s => s.Length > 0))
        {
            var m = InlineOption.Match(p);
            if (m.Success) raw.SetOption(m.Groups[1].Value.ToUpperInvariant(), m.Groups[2].Value.Trim());
            else if (raw.Options.Count < 8) raw.SetOption(((char)('A' + raw.Options.Count)).ToString(), p);
        }
    }
}

// ============================ 规范化 ============================

public static class Normalizer
{
    public static Question? ToQuestion(RawQuestion raw, List<ImportIssue> issues)
    {
        var stem = raw.Get(Field.Stem);
        if (stem.Length == 0)
        {
            issues.Add(new ImportIssue(raw.RowNo, Level.Error, "题干为空，已跳过"));
            return null;
        }

        var typeRaw = raw.Get(Field.Type);
        var type = QTypeExt.Parse(typeRaw);
        if (typeRaw.Length > 0 && type == null)
            issues.Add(new ImportIssue(raw.RowNo, Level.Warn, $"无法识别的题型「{typeRaw}」，按单选题处理"));

        var options = new List<Option>();
        foreach (var (k, v) in raw.Options)
        {
            var text = v.Trim();
            if (text.Length > 0) options.Add(new Option(k.ToUpperInvariant(), text));
        }

        var effective = type ?? QType.Single;

        if (options.Count == 0 && effective.HasOptions())
        {
            var ans = raw.Get(Field.Answer);
            if (ans.Length == 0)
            {
                issues.Add(new ImportIssue(raw.RowNo, Level.Error, "未识别到选项列，无法作答，已跳过"));
                return null;
            }
            if (ans.Split(',', '，', '、', ' ').Count(t => t.Trim().Length > 0) > 1)
            {
                effective = QType.Multi;
                issues.Add(new ImportIssue(raw.RowNo, Level.Warn, "未识别到选项列，已按多选题处理"));
            }
            else
            {
                effective = QType.Fill;
                issues.Add(new ImportIssue(raw.RowNo, Level.Warn, "未识别到选项列，已按填空题处理"));
            }
        }

        if (!effective.Answerable())
        {
            if (options.Count > 0)
            {
                effective = QType.Single;
                issues.Add(new ImportIssue(raw.RowNo, Level.Warn, "当前版本仅支持选择题，已按单选题处理"));
            }
            else
            {
                issues.Add(new ImportIssue(raw.RowNo, Level.Error, "当前版本仅支持选择题，该题已跳过"));
                return null;
            }
        }

        if (options.Count > 0 && effective == QType.TrueFalse
            && options.Any(o => o.Key is "A" or "B") && !options.Any(o => o.Key is "T" or "F"))
            issues.Add(new ImportIssue(raw.RowNo, Level.Info, "判断题选项沿用 A/B，实际按「正确/错误」判题"));

        var answerRaw = raw.Get(Field.Answer);
        if (answerRaw.Length == 0)
            issues.Add(new ImportIssue(raw.RowNo, Level.Warn, "缺少答案，已按「待补答案」导入"));
        var answer = answerRaw.Length == 0 ? "" : NormalizeAnswer(answerRaw, effective, options);

        if (answer.Length > 0 && effective.Objective() && effective.HasOptions() && options.Count > 0)
        {
            var keys = answer.Split(',', ' ', '，', '、', ';', '；')
                .Select(s => s.Trim().ToUpperInvariant()).Where(s => s.Length > 0).ToList();
            var known = options.Select(o => o.Key.ToUpperInvariant()).ToHashSet(StringComparer.Ordinal);
            var bad = keys.Where(k => !known.Contains(k) && k is not ("T" or "F" or "对" or "错" or "正确" or "错误")).ToList();
            if (bad.Count > 0)
                issues.Add(new ImportIssue(raw.RowNo, Level.Warn, $"答案 {string.Join(",", bad)} 不在选项范围内"));
            if (effective == QType.Single && keys.Count > 1)
                issues.Add(new ImportIssue(raw.RowNo, Level.Warn, "单选题给出多个答案，已按多选判题"));
        }

        return new Question
        {
            Type = effective,
            Stem = stem,
            Material = MergeImages(raw.Get(Field.Material), raw.Get(Field.Images)),
            Options = options,
            Answer = answer,
            Analysis = raw.Get(Field.Analysis),
            Knowledge = raw.Get(Field.Knowledge),
            Difficulty = ParseDifficulty(raw.Get(Field.Difficulty)),
            Region = raw.Get(Field.Region),
            Subject = raw.Get(Field.Subject),
            Source = raw.Get(Field.Source),
            Year = raw.Get(Field.Year),
            Score = double.TryParse(raw.Get(Field.Score), out var sc) ? sc : DefaultScore(effective),
            Keywords = raw.Get(Field.Keywords),
        };
    }

    static double DefaultScore(QType t) => t switch
    {
        QType.Single or QType.TrueFalse => 1.0,
        QType.Multi or QType.Uncertain => 2.0,
        QType.Fill => 1.0,
        _ => 10.0,
    };

    public static string MergeImages(string material, string images)
    {
        var refs = images.Split(new[] { '\n', '|', '；', ';' }).Select(s => s.Trim()).Where(s => s.Length > 0).ToList();
        if (refs.Count == 0) return material;
        var md = refs.Select((s, i) =>
            s.StartsWith("![") || s.StartsWith("<img") || s.StartsWith("[")
                ? s
                : $"![题图{(refs.Count > 1 ? (i + 1).ToString() : "")}]({s})").ToList();
        md.Add(material);
        return string.Join("\n\n", md.Where(s => !string.IsNullOrWhiteSpace(s)));
    }

    static int ParseDifficulty(string s)
    {
        if (string.IsNullOrWhiteSpace(s)) return 0;
        if (int.TryParse(s.Trim(), out var n)) return Math.Clamp(n, 0, 3);
        return s.Trim() switch
        {
            "易" or "简单" or "容易" or "低" or "基础" => 1,
            "中" or "中等" or "一般" => 2,
            "难" or "困难" or "高" or "较难" => 3,
            _ => 0,
        };
    }

    static readonly Regex AnswerPrefix = new("^(答案|正确答案|参考答案|标准答案)\\s*[:：]\\s*");
    static readonly Regex KeyWithText = new(@"^([A-Ha-h])\s*[.、．:：)）]\s*.*$");

    public static string NormalizeAnswer(string raw, QType type, List<Option> options)
    {
        var s = AnswerPrefix.Replace(raw.Trim(), "");
        var known = options.Select(o => o.Key.ToUpperInvariant()).ToHashSet(StringComparer.Ordinal);

        List<string> ExtractKeys(string text)
        {
            var seen = new HashSet<string>(StringComparer.Ordinal);
            var order = new List<string>();
            foreach (var token in text.Split(new[] { ',', '，', '、', ';', '；', ' ', '|', '\n' }))
            {
                var t = token.Trim();
                if (t.Length == 0) continue;
                var up = t.ToUpperInvariant();
                if (known.Contains(up)) { if (seen.Add(up)) order.Add(up); continue; }
                var m = KeyWithText.Match(t);
                if (m.Success && known.Contains(m.Groups[1].Value.ToUpperInvariant()))
                {
                    var k = m.Groups[1].Value.ToUpperInvariant();
                    if (seen.Add(k)) order.Add(k);
                }
                else if (known.Count == 0 && seen.Add(up)) order.Add(up);
            }
            return order;
        }

        switch (type)
        {
            case QType.Multi:
            case QType.Uncertain:
                return string.Join(",", ExtractKeys(s).Distinct().OrderBy(x => x, StringComparer.Ordinal));
            case QType.Single:
                var keys = ExtractKeys(s);
                return keys.Count > 0 ? keys[0] : s.ToUpperInvariant();
            case QType.TrueFalse:
                return s.ToUpperInvariant() switch
                {
                    "T" or "TRUE" or "Y" or "YES" or "对" or "正确" or "√" or "A" => "T",
                    "F" or "FALSE" or "N" or "NO" or "错" or "错误" or "×" or "B" => "F",
                    _ => s,
                };
            case QType.Fill:
                return string.Join("|", s.Split(new[] { '|', '；', ';', '\n' }).Select(x => x.Trim()).Where(x => x.Length > 0));
            default:
                return s;
        }
    }
}

