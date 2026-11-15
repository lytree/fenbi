using System.Text;

namespace QBank.Core;

// 判题：对应 Kotlin 的 engine/Judge.kt（纯函数，无副作用）

// ============================ 判题 ============================

public static class Judge
{
    public static bool HasStandard(Question q) =>
        !string.IsNullOrWhiteSpace(q.Answer) || (q.Type == QType.Subjective && !string.IsNullOrWhiteSpace(q.Keywords));

    public static AnswerResult Judge1(Question q, string userAnswer, Policy? policy = null)
    {
        policy ??= Policy.Default;
        var full = q.Score;
        var ua = userAnswer.Trim();
        if (!HasStandard(q)) return new AnswerResult(q, ua, 0, full, false, false, Pending: true);

        var correctKeys = q.AnswerKeys();
        var userKeys = ua.Split(new[] { ',', ' ', '，', '、', ';', '；', '|' }, StringSplitOptions.RemoveEmptyEntries)
            .Select(s => s.Trim().ToUpperInvariant()).Where(s => s.Length > 0)
            .ToHashSet(StringComparer.Ordinal);

        switch (q.Type)
        {
            case QType.Single:
            {
                var ok = userKeys.Count > 0 && userKeys.SetEquals(correctKeys);
                return new AnswerResult(q, ua, ok ? full : 0, full, ok, false);
            }
            case QType.Multi:
            {
                var ok = userKeys.Count > 0 && userKeys.SetEquals(correctKeys);
                var partial = !ok && policy.MultiPartial && userKeys.Count > 0 && correctKeys.IsSupersetOf(userKeys);
                var earned = ok ? full : partial ? full * ((double)userKeys.Count / correctKeys.Count) : 0;
                return new AnswerResult(q, ua, earned, full, ok, partial);
            }
            case QType.Uncertain:
            {
                var ok = userKeys.Count > 0 && userKeys.SetEquals(correctKeys);
                var miss = userKeys.Count > 0 && correctKeys.IsSupersetOf(userKeys);
                var wrong = userKeys.Any(k => !correctKeys.Contains(k));
                var partial = !ok && !wrong && miss && policy.UncertainPartial;
                var earned = ok ? full : partial ? full * policy.UncertainPartialRatio : 0;
                return new AnswerResult(q, ua, earned, full, ok, partial);
            }
            case QType.TrueFalse:
            {
                var ok = userKeys.Count > 0 && NormalizeJudge(userKeys, policy).SetEquals(NormalizeJudge(correctKeys, policy));
                return new AnswerResult(q, ua, ok ? full : 0, full, ok, false);
            }
            case QType.Fill:
            {
                var std = q.Answer.Split(new[] { '|', '；', ';', '\n' }).Select(s => s.Trim()).Where(s => s.Length > 0).ToList();
                var mine = ua.Split(new[] { '|', '；', ';', '\n' }).Select(s => s.Trim()).Where(s => s.Length > 0).ToList();
                if (std.Count == 0) return new AnswerResult(q, ua, 0, full, false, false);
                var hit = 0;
                for (var i = 0; i < std.Count; i++)
                {
                    var m = i < mine.Count ? mine[i] : (mine.Count > 0 ? mine[0] : null);
                    if (m != null && NormalizeText(m) == NormalizeText(std[i])) hit++;
                }
                var allHit = hit == std.Count;
                var partial = !allHit && policy.FillPartial && hit > 0;
                var earned = allHit ? full : partial ? full * ((double)hit / std.Count) : 0;
                return new AnswerResult(q, ua, earned, full, allHit, partial);
            }
            default:
            {
                var kws = q.Keywords.Split(new[] { ';', '；', ',', '，', '|' }).Select(s => s.Trim()).Where(s => s.Length > 0).ToList();
                var text = NormalizeText(ua);
                var hit = kws.Count(k => text.Contains(NormalizeText(k), StringComparison.Ordinal));
                var allHit = kws.Count > 0 && hit == kws.Count;
                var partial = !allHit && policy.SubjectivePartial && hit > 0;
                var earned = kws.Count == 0 ? 0 : allHit ? full : partial ? full * ((double)hit / kws.Count) : 0;
                return new AnswerResult(q, ua, earned, full, allHit, partial);
            }
        }
    }

    static HashSet<string> NormalizeJudge(HashSet<string> keys, Policy policy)
    {
        if (!policy.JudgeLenient) return keys;
        return keys.Select(k => k switch
        {
            "T" or "TRUE" or "Y" or "YES" or "正确" or "对" or "√" or "A" or "1" => "T",
            "F" or "FALSE" or "N" or "NO" or "错误" or "错" or "×" or "B" or "0" => "F",
            _ => k,
        }).ToHashSet(StringComparer.Ordinal);
    }

    public static string NormalizeText(string s)
    {
        var sb = new StringBuilder(s.Length);
        foreach (var ch in s.ToLowerInvariant())
        {
            if (ch >= 0xFF01 && ch <= 0xFF5E) sb.Append((char)(ch - 0xFEE0));
            else if (ch == '　') { }
            else if (char.IsWhiteSpace(ch)) { }
            else if ("，。！？；：、,.!?;:()（）【】[]<>《》\"'“”‘’~-_—".Contains(ch)) { }
            else sb.Append(ch);
        }
        return sb.ToString();
    }
}

