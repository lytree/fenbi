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
 * 标签 + 词条数：标签筛选弹窗的数据单元。[name] 为空表示「未标注标签」这一类。
 */
data class WordTagCount(val name: String, val count: Int)

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

    companion object {

        /** 标签分隔符：一个词条可能同时属于多个细分（「对比辨析·动作」） */
        const val TAG_SEP = '·'

        /**
         * 拆段用的分隔符。来源表除了 ·，偶尔还用「；」把两个细分配在一起
         * （「技艺精湛；近似成语」），一并当成段与段之间的分隔。
         * 输出统一用 [TAG_SEP] 拼接，库里不会出现分号。
         */
        private val SEG_SPLIT = Regex("[·；;]")

        /**
         * 规范化细分标签：按 · 拆段 → 去空白 → 去空段 → 去重（保序）→ 重新拼接。
         *
         * 同一个词在多张表里出现时，旧版生成脚本会把标签重复追加，
         * 拼出「第十一组 …·对比辨析·第十一组 …」这种自我重复。入库前统一过一遍，
         * 标签筛选列表和词条详情才会干净。
         */
        fun normalizeTag(raw: String?): String =
            splitTags(raw).filter { it.isNotEmpty() }.joinToString(TAG_SEP.toString())

        /** 来源词表里的分组前缀：「第一组 动作」「第十一组：需要积累的其他词语」 */
        private val GROUP_PREFIX =
            Regex("^第\\s*[0-9零〇一二三四五六七八九十百千两]{1,4}\\s*组[\\s:：、,，\\-—]*")

        /**
         * 去掉标签开头的「第 N 组」编号。
         *
         * 词表按「第一组 / 第二组 …」编排，分组编号对使用者没有意义，
         * 留着会让标签列表里出现一堆「第七组 动作」「第十组 事情不顺利」，
         * 既长又难筛。只留后面的描述；整段只有编号时（「第三组」）直接丢弃。
         */
        fun stripGroup(raw: String?): String {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return ""
            return GROUP_PREFIX.replace(s, "").trim()
        }

        /**
         * 拆成单个标签段（去重保序）。没有标签时返回只含空串的列表——
         * 空串代表「未标注标签」，筛选时是一个独立选项，不能直接丢掉。
         */
        fun splitTags(raw: String?): List<String> {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return listOf("")
            val parts = LinkedHashSet<String>()
            s.split(SEG_SPLIT).forEach { part ->
                val t = stripGroup(part)
                if (t.isNotEmpty()) parts.add(t)
            }
            return if (parts.isEmpty()) listOf("") else parts.toList()
        }
    }
}
