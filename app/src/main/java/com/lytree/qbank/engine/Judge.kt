package com.lytree.qbank.engine

import com.lytree.qbank.data.model.AnswerResult
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.data.model.QuestionType

/**
 * 判题计分规则。不同机构口径不同，这里把差异点全部收敛到 [Policy]，
 * 用户可在设置里切换，题库本身不绑定规则。
 */
data class Policy(
    /** 多选题：少选是否给部分分（参考应用口径为"多选、少选、错选均不得分"） */
    val multiPartial: Boolean = false,
    /** 不定项：少选是否给部分分（国考常见口径为"少选每个正确项得 0.5 分"） */
    val uncertainPartial: Boolean = true,
    /** 不定项少选时的单空得分率 */
    val uncertainPartialRatio: Double = 0.5,
    /** 填空题：漏填部分是否给部分分 */
    val fillPartial: Boolean = true,
    /** 主观题：关键词命中是否给部分分 */
    val subjectivePartial: Boolean = true,
    /** 判断题是否允许"对/错/√/×"多种写法 */
    val judgeLenient: Boolean = true
) {
    companion object {
        val DEFAULT = Policy()
    }
}

/**
 * 判题引擎。所有客观题走"答案键集合比对"，主观题走"关键词命中率"。
 */
object Judge {

    /**
     * 是否具备判分依据。
     * 客观题看标准答案；主观题允许只给「得分点」而不给整段参考答案。
     */
    fun hasStandard(q: Question): Boolean =
        q.answer.isNotBlank() ||
                (q.type == QuestionType.SUBJECTIVE && q.keywords.isNotBlank())


    /** 对主观题而言，标准答案即参考答案 */
    fun judge(q: Question, userAnswer: String, policy: Policy = Policy.DEFAULT): AnswerResult {
        val full = q.score
        val ua = userAnswer.trim()
        // 还没有标准答案（试卷先导入、答案后补）→ 不判分，标记为待补
        if (!hasStandard(q)) {
            return AnswerResult(q, ua, 0.0, full, false, false, pending = true)
        }
        val correctKeys = q.answerKeys()
        val userKeys = ua.split(',', ' ', '，', '、', ';', '；', '|')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        return when (q.type) {
            QuestionType.SINGLE -> {
                val ok = userKeys.isNotEmpty() && userKeys == correctKeys
                AnswerResult(q, ua, if (ok) full else 0.0, full, ok, false)
            }

            QuestionType.MULTI -> {
                val ok = userKeys.isNotEmpty() && userKeys == correctKeys
                val partial = !ok && policy.multiPartial && userKeys.isNotEmpty() &&
                        correctKeys.containsAll(userKeys)
                val earned = when {
                    ok -> full
                    partial -> full * (userKeys.size.toDouble() / correctKeys.size)
                    else -> 0.0
                }
                AnswerResult(q, ua, earned, full, ok, partial)
            }

            QuestionType.UNCERTAIN -> {
                val ok = userKeys.isNotEmpty() && userKeys == correctKeys
                val miss = userKeys.isNotEmpty() && correctKeys.containsAll(userKeys)
                val wrong = userKeys.any { it !in correctKeys }
                val partial = !ok && !wrong && miss && policy.uncertainPartial
                val earned = when {
                    ok -> full
                    partial -> full * policy.uncertainPartialRatio
                    else -> 0.0
                }
                AnswerResult(q, ua, earned, full, ok, partial)
            }

            QuestionType.TRUE_FALSE -> {
                val ok = normalizeJudge(userKeys, policy) == normalizeJudge(correctKeys, policy) &&
                        userKeys.isNotEmpty()
                AnswerResult(q, ua, if (ok) full else 0.0, full, ok, false)
            }

            QuestionType.FILL -> {
                val std = q.answer.split('|', '；', ';', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val mine = ua.split('|', '；', ';', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (std.isEmpty()) {
                    AnswerResult(q, ua, 0.0, full, false, false)
                } else {
                    var hit = 0
                    std.forEachIndexed { i, s ->
                        val m = mine.getOrNull(i) ?: mine.getOrNull(0)
                        if (m != null && normalizeText(m) == normalizeText(s)) hit++
                    }
                    val allHit = hit == std.size
                    val partial = !allHit && policy.fillPartial && hit > 0
                    val earned = when {
                        allHit -> full
                        partial -> full * (hit.toDouble() / std.size)
                        else -> 0.0
                    }
                    AnswerResult(q, ua, earned, full, allHit, partial)
                }
            }

            QuestionType.SUBJECTIVE -> {
                val kws = q.keywords.split(';', '；', ',', '，', '|')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val text = normalizeText(ua)
                val hit = kws.count { text.contains(normalizeText(it)) }
                val allHit = kws.isNotEmpty() && hit == kws.size
                val partial = !allHit && policy.subjectivePartial && hit > 0
                val earned = when {
                    kws.isEmpty() -> 0.0
                    allHit -> full
                    partial -> full * (hit.toDouble() / kws.size)
                    else -> 0.0
                }
                AnswerResult(q, ua, earned, full, allHit, partial)
            }
        }
    }

    /** 判断题答案归一化：T/正确/对/√/Y/YES/TRUE -> T；F/错误/错/×/N/NO/FALSE -> F */
    private fun normalizeJudge(keys: Set<String>, policy: Policy): Set<String> {
        if (!policy.judgeLenient) return keys
        return keys.map { k ->
            when (k) {
                "T", "TRUE", "Y", "YES", "正确", "对", "√", "A", "1" -> "T"
                "F", "FALSE", "N", "NO", "错误", "错", "×", "B", "0" -> "F"
                else -> k
            }
        }.toSet()
    }

    /** 文本归一化：去空白、全角转半角、忽略常见标点与大小写 */
    fun normalizeText(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            when {
                ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar()) // 全角→半角
                ch == '　' -> Unit
                ch.isWhitespace() -> Unit
                ch in "，。！？；：、,.!?;:()（）【】[]<>《》\"'“”‘’~-_—" -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 汇总统计 */
    data class Summary(
        val total: Int,
        val answered: Int,
        val correct: Int,
        val partial: Int,
        val wrong: Int,
        val blank: Int,
        /** 缺少标准答案、未参与判分的题数 */
        val pending: Int,
        val score: Double,
        val fullScore: Double
    ) {
        /** 正确率：只统计已作答题，答对 / 已作答 */
        val correctRate: Double
            get() = if (answered == 0) 0.0 else correct.toDouble() / answered
        /** 得分率：总得分 / 总分（待补答案的题不计入总分） */
        val scoreRate: Double
            get() = if (fullScore <= 0) 0.0 else score / fullScore
    }

    fun summarize(results: List<AnswerResult>): Summary {
        val pending = results.count { it.pending }
        // 待补答案的题既不进分子也不进分母，否则「满分」会被还没拿到的答案撑大
        val scored = results.filter { !it.pending }
        val answered = scored.count { it.userAnswer.isNotBlank() }
        val correct = scored.count { it.correct }
        val partial = scored.count { it.partial && !it.correct }
        val blank = scored.count { it.userAnswer.isBlank() }
        val score = scored.sumOf { it.earned }
        val full = scored.sumOf { it.full }
        return Summary(
            total = results.size,
            answered = answered,
            correct = correct,
            partial = partial,
            wrong = scored.size - answered - partial,
            blank = blank,
            pending = pending,
            score = score,
            fullScore = full
        )
    }
}
