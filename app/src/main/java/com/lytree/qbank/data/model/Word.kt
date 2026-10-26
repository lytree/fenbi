package com.lytree.qbank.data.model

/**
 * 词条分类。
 *
 * 词库不再只收成语：四字成语和普通词语（实词）同表存放，靠这个字段区分。
 * 细分（名词 / 动词 / 褒义 …）不在这里定死，走 [Word.tag] 自由文本，
 * 因为不同来源的词表口径差异太大，硬编码枚举反而会丢信息。
 */
enum class WordCategory(val code: String, val label: String) {
    IDIOM("idiom", "成语"),
    WORD("word", "实词"),
    OTHER("other", "其他");

    companion object {
        /** 从存量数据里的 code 还原（未知一律归「其他」，不丢词条） */
        fun of(code: String?): WordCategory =
            entries.firstOrNull { it.code == code?.trim() } ?: OTHER

        /**
         * 从表格里的「分类」列解析。识别不出的返回 null，
         * 由调用方回落到字数推测——不要在这里猜，猜的地方要能标记「推测」。
         */
        fun parse(raw: String?): WordCategory? {
            val s = raw?.trim()?.lowercase()?.replace(" ", "") ?: return null
            if (s.isEmpty()) return null
            return when {
                s in setOf("成语", "四字成语", "成语词", "成语类", "idiom", "chengyu") -> IDIOM
                s in setOf("实词", "词语", "词", "普通词", "词语积累", "word", "contentword", "实词类") -> WORD
                s.contains("成语") -> IDIOM
                s.contains("实词") || s.contains("词语") -> WORD
                else -> null
            }
        }
    }
}

/**
 * 词条。与 [Question] 完全平行的一套数据，不进题目表、不参与判分。
 *
 * [initial] 是入库时就算好的拼音首字母（A-Z，取不到为 `#`），
 * 列表排序和 A-Z 分组都直接用它，不必每次翻页再算一遍。
 */
data class Word(
    val id: Long = 0L,
    /** 词条本体，如「画蛇添足」「踌躇」 */
    val text: String = "",
    /** 无声调全拼，多字之间空格分隔，如 `hua she tian zu` */
    val pinyin: String = "",
    /** 拼音首字母，大写 A-Z；取不到为 `#` */
    val initial: String = "#",
    /** 分类 code，见 [WordCategory] */
    val category: String = WordCategory.WORD.code,
    /** 细分标签（名词 / 动词 / 褒义 …），可为空 */
    val tag: String = "",
    val meaning: String = "",
    val example: String = "",
    val source: String = "",
    val createdAt: Long = 0L
) {
    val categoryEnum: WordCategory get() = WordCategory.of(category)

    val categoryLabel: String get() = categoryEnum.label

    /** 详情里「拼音 · 分类 · 细分」那一行，空的项直接省掉 */
    fun metaLine(): String = buildList {
        if (pinyin.isNotBlank()) add(pinyin)
        add(categoryLabel)
        if (tag.isNotBlank()) add(tag)
    }.joinToString(" · ")

    /** 列表排序口径：首字母 → 全拼 → 词条本身 */
    fun sortKey(): String = initial + "|" + pinyin.replace(" ", "").lowercase() + "|" + text
}
