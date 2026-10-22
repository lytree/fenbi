package com.lytree.qbank.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 题型。对齐主流刷题 App 的题型体系：
 * 单选 / 多选 / 不定项 / 判断 / 填空 / 主观（申论、材料分析）。
 *
 * 注：当前版本答题页只完整支持**选择题**（单选 / 多选 / 不定项 / 判断）。
 * 填空与主观题在数据层保留（导入、组卷、判题口径不变），
 * 但不作为正式答题流程的题型，等后续迭代再接。
 */
enum class QuestionType(val code: String, val label: String, val hasOptions: Boolean, val objective: Boolean) {
    SINGLE("single", "单选题", true, true),
    MULTI("multi", "多选题", true, true),
    UNCERTAIN("uncertain", "不定项", true, true),
    TRUE_FALSE("judge", "判断题", true, true),
    FILL("fill", "填空题", false, true),
    SUBJECTIVE("subjective", "主观题", false, false);

    /** 是否为当前答题流程支持的题型（仅选择题） */
    val answerable: Boolean
        get() = hasOptions

    companion object {
        /** 宽松解析：兼容中英文/别名/数字。 */
        fun parse(raw: String?): QuestionType? {
            val s = raw?.trim()?.lowercase()?.replace(" ", "") ?: return null
            if (s.isEmpty()) return null
            return when {
                s in setOf("single", "单选", "单选题", "选择", "选择题", "singlechoice", "1") -> SINGLE
                s in setOf("multi", "多选", "多选题", "多项选择", "multiple", "multiplechoice", "2") -> MULTI
                s in setOf("uncertain", "不定项", "不定项选择题", "不定项选择", "partial", "3") -> UNCERTAIN
                s in setOf("judge", "truefalse", "判断", "判断题", "tf", "bool", "boolean", "4") -> TRUE_FALSE
                s in setOf("fill", "填空", "填空题", "blank", "cloze", "5") -> FILL
                s in setOf("subjective", "主观", "主观题", "简答", "申论", "材料分析", "论述", "essay", "6") -> SUBJECTIVE
                else -> null
            }
        }
    }
}

/** 选项。key 形如 "A" / "B"，也允许 "①" 之类的非字母 key。 */
data class Option(val key: String, val text: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("k", key)
        put("t", text)
    }

    companion object {
        fun fromJson(o: JSONObject): Option = Option(
            o.optString("k", o.optString("key")),
            o.optString("t", o.optString("text", o.optString("value")))
        )
    }
}

/**
 * 题目。字段命名刻意贴近刷题类 App 的通用模型，方便与各类题库文件做映射。
 */
data class Question(
    val id: Long = 0L,
    val bankId: Long = 0L,
    val type: QuestionType = QuestionType.SINGLE,
    val stem: String = "",
    val material: String = "",
    val options: List<Option> = emptyList(),
    /** 客观题：标准答案，多选用 "," 或 " " 分隔；主观题：参考答案 */
    val answer: String = "",
    val analysis: String = "",
    val knowledge: String = "",
    /** 1=易 2=中 3=难，0=未标注 */
    val difficulty: Int = 0,
    val region: String = "",
    val subject: String = "",
    val source: String = "",
    val year: String = "",
    val score: Double = 1.0,
    /** 主观题得分关键词，";" 分隔 */
    val keywords: String = ""
) {
    /** 该题是否为"有标准答案键"的客观题 */
    fun answerKeys(): Set<String> =
        answer.split(',', ' ', '，', '、', ';', '；', '|')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun optionsJson(): String {
        val arr = JSONArray()
        options.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    /** 面向用户的答案文案（判断题把 T/F 显示成 正确/错误） */
    fun displayAnswer(): String = when (type) {
        QuestionType.TRUE_FALSE -> when (answer.trim().uppercase()) {
            "T" -> "正确"
            "F" -> "错误"
            else -> answer
        }
        else -> answer
    }

    companion object {
        fun optionsFromJson(s: String): List<Option> {
            if (s.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(s)
                (0 until arr.length()).map { Option.fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

/** 题库 */
data class Bank(
    val id: Long = 0L,
    val name: String = "",
    val description: String = "",
    val subject: String = "",
    val createdAt: Long = 0L,
    val questionCount: Int = 0
)

/** 试卷 */
data class Paper(
    val id: Long = 0L,
    val name: String = "",
    val bankId: Long = 0L,
    val createdAt: Long = 0L,
    val totalScore: Double = 0.0,
    val durationMin: Int = 0,
    val mode: String = "",
    /** 列表查询时只带题量，不加载整卷题目 */
    val questionCount: Int = 0,
    val questions: List<Question> = emptyList()
)

/** 单题作答结果 */
data class AnswerResult(
    val question: Question,
    val userAnswer: String,
    val earned: Double,
    val full: Double,
    val correct: Boolean,
    val partial: Boolean
) {
    /** 状态：right 全对 / partial 部分得分 / wrong 错误 / blank 未答 */
    val status: String
        get() = when {
            userAnswer.isBlank() -> "blank"
            correct -> "right"
            partial -> "partial"
            else -> "wrong"
        }
}

/** 考试记录 */
data class ExamRecord(
    val id: Long = 0L,
    val paperId: Long = 0L,
    val name: String = "",
    val mode: String = "",
    val startedAt: Long = 0L,
    val submittedAt: Long = 0L,
    val durationSec: Int = 0,
    val totalCount: Int = 0,
    val answeredCount: Int = 0,
    val correctCount: Int = 0,
    val partialCount: Int = 0,
    val score: Double = 0.0,
    val fullScore: Double = 0.0,
    val correctRate: Double = 0.0,
    val results: List<AnswerResult> = emptyList()
)
