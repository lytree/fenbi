package com.lytree.qbank.data.importer

import com.lytree.qbank.data.model.Option
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.data.model.QuestionType

/** 规范字段名 */
object Field {
    const val TYPE = "type"
    const val STEM = "stem"
    const val MATERIAL = "material"
    const val OPTIONS = "options"
    const val ANSWER = "answer"
    const val ANALYSIS = "analysis"
    const val KNOWLEDGE = "knowledge"
    const val DIFFICULTY = "difficulty"
    const val REGION = "region"
    const val SUBJECT = "subject"
    const val SOURCE = "source"
    const val YEAR = "year"
    const val SCORE = "score"
    const val KEYWORDS = "keywords"
}

/**
 * 表头别名表。中文题库导出常见叫法五花八门，这里尽量兼容，
 * 并且做归一化（去空格/下划线/连字符/全角）后再比对。
 */
object FieldAliases {

    private val ALIASES: Map<String, Set<String>> = mapOf(
        Field.TYPE to setOf(
            "题型", "类型", "题目类型", "type", "questiontype", "qtype", "category"
        ),
        Field.STEM to setOf(
            "题干", "题目", "试题", "问题", "题目内容", "stem", "question", "title",
            "content", "questioncontent", "problem", "q"
        ),
        Field.MATERIAL to setOf(
            "材料", "材料内容", "背景", "背景材料", "资料", "material", "passage", "context", "stem2"
        ),
        Field.OPTIONS to setOf(
            "选项", "选项列表", "options", "choices", "optionlist"
        ),
        Field.ANSWER to setOf(
            "答案", "正确答案", "标准答案", "参考答案", "answer", "correctanswer", "key",
            "rightanswer", "ans"
        ),
        Field.ANALYSIS to setOf(
            "解析", "答案解析", "试题解析", "说明", "备注", "analysis", "explanation",
            "explain", "remark", "note", "solution"
        ),
        Field.KNOWLEDGE to setOf(
            "知识点", "考点", "知识点标签", "标签", "knowledge", "point", "tag", "knowledgepoint"
        ),
        Field.DIFFICULTY to setOf(
            "难度", "难度等级", "difficulty", "level", "hard", "stars"
        ),
        Field.REGION to setOf(
            "地区", "省份", "城市", "地域", "region", "area", "province"
        ),
        Field.SUBJECT to setOf(
            "科目", "学科", "分类", "模块", "subject", "category", "module", "section"
        ),
        Field.SOURCE to setOf(
            "来源", "出处", "试卷来源", "source", "from", "origin"
        ),
        Field.YEAR to setOf(
            "年份", "年度", "year", "date", "yr"
        ),
        Field.SCORE to setOf(
            "分值", "分", "分数", "每题分值", "score", "points", "value", "mark"
        ),
        Field.KEYWORDS to setOf(
            "得分点", "关键词", "采分点", "keywords", "keypoints", "scoringpoints"
        )
    )

    /** 单选选项表头：A / 选项A / option_a / optionA */
    private val OPTION_ALIASES = mapOf(
        "A" to setOf("A", "选项A", "A选项", "optiona", "a选项", "A.", "选A", "答案A"),
        "B" to setOf("B", "选项B", "B选项", "optionb", "b选项", "B.", "选B", "答案B"),
        "C" to setOf("C", "选项C", "C选项", "optionc", "c选项", "C.", "选C", "答案C"),
        "D" to setOf("D", "选项D", "D选项", "optiond", "d选项", "D.", "选D", "答案D"),
        "E" to setOf("E", "选项E", "E选项", "optione", "e选项", "E.", "选E", "答案E"),
        "F" to setOf("F", "选项F", "F选项", "optionf", "f选项", "F.", "选F", "答案F"),
        "G" to setOf("G", "选项G", "G选项", "optiong", "g选项", "G.", "选G", "答案G"),
        "H" to setOf("H", "选项H", "H选项", "optionh", "h选项", "H.", "选H", "答案H")
    )

    /** 归一化表头：去空白、下划线、连字符、括号、冒号，全角转半角，小写 */
    fun normHeader(h: String): String {
        val sb = StringBuilder()
        for (ch in h) {
            when {
                ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar())
                ch.isWhitespace() || ch == '_' || ch == '-' || ch == '*' || ch == ':' ||
                        ch == '：' || ch == '(' || ch == ')' || ch == '（' || ch == '）' -> Unit
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /** 规范字段名 -> 别名集合（已归一化） */
    private val NORM_ALIASES: Map<String, Set<String>> =
        ALIASES.entries.associate { (k, v) -> k to v.map { normHeader(it) }.toSet() }

    private val NORM_OPTIONS: Map<String, Set<String>> =
        OPTION_ALIASES.entries.associate { (k, v) -> k to v.map { normHeader(it) }.toSet() }

    /** 把一个表头映射到规范字段名；识别不出返回 null */
    fun resolve(header: String): String? {
        val h = normHeader(header)
        if (h.isEmpty()) return null
        for ((field, aliases) in NORM_ALIASES) {
            if (aliases.contains(h)) return field
        }
        return null
    }

    /** 把一个表头映射到选项 key（A..H）；不是选项列返回 null */
    fun resolveOption(header: String): String? {
        val h = normHeader(header)
        if (h.isEmpty()) return null
        for ((key, aliases) in NORM_OPTIONS) {
            if (aliases.contains(h)) return key
        }
        // 形如 "选项1" / "option1" / "1" 的序号式选项列
        val m = Regex("^(?:选项|option)?([1-8])$").find(h)
        if (m != null) return ('A'.code + (m.groupValues[1].toInt() - 1)).toChar().toString()
        return null
    }
}

/** 解析过程中的单条问题 */
class RawQuestion(
    val rowNo: Int,
    val values: MutableMap<String, String> = LinkedHashMap(),
    val options: LinkedHashMap<String, String> = LinkedHashMap()
) {
    fun get(field: String): String = values[field]?.trim() ?: ""
    fun put(field: String, v: String?) {
        if (!v.isNullOrBlank()) values[field] = v.trim()
    }
}

/** 导入问题 */
data class ImportIssue(
    val rowNo: Int,
    val level: Level,
    val message: String
) {
    enum class Level { ERROR, WARN, INFO }
}

/** 导入结果 */
data class ImportResult(
    val questions: List<RawQuestion>,
    val issues: List<ImportIssue>,
    val format: String,
    val totalRows: Int
) {
    val errorCount get() = issues.count { it.level == ImportIssue.Level.ERROR }
    val warnCount get() = issues.count { it.level == ImportIssue.Level.WARN }
    val ok get() = questions.isNotEmpty()
}

/**
 * 把 [RawQuestion] 规范化成 [Question]，并给出逐条校验结果。
 */
object Normalizer {

    fun toQuestion(raw: RawQuestion, issues: MutableList<ImportIssue>): Question? {
        val stem = raw.get(Field.STEM)
        if (stem.isEmpty()) {
            issues.add(ImportIssue(raw.rowNo, ImportIssue.Level.ERROR, "题干为空，已跳过"))
            return null
        }

        // 题型
        val typeRaw = raw.get(Field.TYPE)
        val type = QuestionType.parse(typeRaw)
        if (typeRaw.isNotEmpty() && type == null) {
            issues.add(
                ImportIssue(raw.rowNo, ImportIssue.Level.WARN, "无法识别的题型「$typeRaw」，按单选题处理")
            )
        }

        // 选项
        val options = ArrayList<Option>()
        raw.options.forEach { (k, v) ->
            val text = v.trim()
            if (text.isNotEmpty()) options.add(Option(k.uppercase(), text))
        }

        var effectiveType = type ?: QuestionType.SINGLE
        if (options.isEmpty() && effectiveType.hasOptions) {
            // 没识别到选项列，但有多答案特征 → 升级为多选
            val ans = raw.get(Field.ANSWER)
            if (ans.split(',', '，', '、', ' ').count { it.trim().isNotEmpty() } > 1) {
                effectiveType = QuestionType.MULTI
                issues.add(
                    ImportIssue(raw.rowNo, ImportIssue.Level.WARN, "未识别到选项列，已按多选题处理")
                )
            } else {
                effectiveType = QuestionType.FILL
                issues.add(
                    ImportIssue(raw.rowNo, ImportIssue.Level.WARN, "未识别到选项列，已按填空题处理")
                )
            }
        }
        // 当前答题流程只支持选择题：填空/主观题若带选项则降级为单选题，否则跳过
        if (!effectiveType.answerable) {
            if (options.isNotEmpty()) {
                effectiveType = QuestionType.SINGLE
                issues.add(
                    ImportIssue(raw.rowNo, ImportIssue.Level.WARN, "当前版本仅支持选择题，已按单选题处理")
                )
            } else {
                issues.add(
                    ImportIssue(raw.rowNo, ImportIssue.Level.ERROR, "当前版本仅支持选择题，该题已跳过")
                )
                return null
            }
        }
        if (options.isNotEmpty() && effectiveType == QuestionType.TRUE_FALSE) {
            // 判断题用 A/B 表示「正确/错误」时，保持原样即可，判题时会归一化
            if (options.any { it.key == "A" || it.key == "B" } &&
                !options.any { it.key == "T" || it.key == "F" }
            ) {
                issues.add(
                    ImportIssue(raw.rowNo, ImportIssue.Level.INFO, "判断题选项沿用 A/B，实际按「正确/错误」判题")
                )
            }
        }

        // 答案
        val answerRaw = raw.get(Field.ANSWER)
        if (answerRaw.isEmpty()) {
            issues.add(ImportIssue(raw.rowNo, ImportIssue.Level.ERROR, "缺少答案，已跳过"))
            return null
        }
        val answer = normalizeAnswer(answerRaw, effectiveType, options)

        // 客观题答案必须在选项范围内
        if (effectiveType.objective && effectiveType.hasOptions && options.isNotEmpty()) {
            val keys = answer.split(',', ' ', '，', '、', ';', '；')
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
            val known = options.map { it.key.uppercase() }.toSet()
            val bad = keys.filter { it !in known && it !in setOf("T", "F", "对", "错", "正确", "错误") }
            if (bad.isNotEmpty()) {
                issues.add(
                    ImportIssue(raw.rowNo, ImportIssue.Level.WARN, "答案 ${bad.joinToString(",")} 不在选项范围内")
                )
            }
            if (effectiveType == QuestionType.SINGLE && keys.size > 1) {
                issues.add(
                    ImportIssue(raw.rowNo, ImportIssue.Level.WARN, "单选题给出多个答案，已按多选判题")
                )
            }
        }

        val difficulty = parseDifficulty(raw.get(Field.DIFFICULTY))
        val score = raw.get(Field.SCORE).toDoubleOrNull() ?: defaultScore(effectiveType)

        return Question(
            type = effectiveType,
            stem = stem,
            material = raw.get(Field.MATERIAL),
            options = options,
            answer = answer,
            analysis = raw.get(Field.ANALYSIS),
            knowledge = raw.get(Field.KNOWLEDGE),
            difficulty = difficulty,
            region = raw.get(Field.REGION),
            subject = raw.get(Field.SUBJECT),
            source = raw.get(Field.SOURCE),
            year = raw.get(Field.YEAR),
            score = score,
            keywords = raw.get(Field.KEYWORDS)
        )
    }

    private fun defaultScore(t: QuestionType): Double = when (t) {
        QuestionType.SINGLE, QuestionType.TRUE_FALSE -> 1.0
        QuestionType.MULTI, QuestionType.UNCERTAIN -> 2.0
        QuestionType.FILL -> 1.0
        QuestionType.SUBJECTIVE -> 10.0
    }

    private fun parseDifficulty(s: String): Int {
        if (s.isBlank()) return 0
        s.trim().toIntOrNull()?.let { return it.coerceIn(0, 3) }
        return when (s.trim()) {
            "易", "简单", "容易", "低", "基础" -> 1
            "中", "中等", "一般" -> 2
            "难", "困难", "高", "较难" -> 3
            else -> 0
        }
    }

    /** 答案归一化：去掉"答案:"前缀、统一分隔符、判断题特殊值 */
    fun normalizeAnswer(raw: String, type: QuestionType, options: List<Option>): String {
        var s = raw.trim()
        s = s.replace(Regex("^(答案|正确答案|参考答案|标准答案)\\s*[:：]\\s*"), "")

        val known = options.map { it.key.uppercase() }.toSet()

        // 提取答案键：允许 "A" / "A.文本" / "A、文本" 三种写法
        fun extractKeys(text: String): List<String> {
            val out = LinkedHashSet<String>()
            text.split(',', '，', '、', ';', '；', ' ', '|', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { token ->
                    val up = token.uppercase()
                    when {
                        known.contains(up) -> out.add(up)
                        else -> {
                            val m = Regex("^([A-Ha-h])\\s*[.、．:：)）]\\s*.*$").find(token)
                            if (m != null && known.contains(m.groupValues[1].uppercase())) {
                                out.add(m.groupValues[1].uppercase())
                            } else if (known.isEmpty()) {
                                out.add(up)
                            }
                        }
                    }
                }
            return out.toList()
        }

        return when (type) {
            QuestionType.MULTI, QuestionType.UNCERTAIN ->
                extractKeys(s).distinct().sorted().joinToString(",")

            QuestionType.SINGLE -> {
                val keys = extractKeys(s)
                if (keys.isNotEmpty()) keys.first() else s.uppercase()
            }

            QuestionType.TRUE_FALSE -> {
                val u = s.uppercase()
                when {
                    u in setOf("T", "TRUE", "Y", "YES", "对", "正确", "√", "A") -> "T"
                    u in setOf("F", "FALSE", "N", "NO", "错", "错误", "×", "B") -> "F"
                    else -> s
                }
            }

            QuestionType.FILL -> s.split('|', '；', ';', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("|")

            QuestionType.SUBJECTIVE -> s
        }
    }
}
