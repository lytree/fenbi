package com.lytree.qbank.data.importer

/**
 * 无表头 CSV 的列嗅探。
 *
 * 现实里很多导出（自写爬虫、PDF 转表格、老师手工整理）根本没有表头，第一行就是题目。
 * 以前遇到这种文件直接报「未找到题干列」——用户只看到失败，却没法自救。
 *
 * 这里的做法是**按内容形状认列**，只认最有把握的两件事：
 *
 * | 形状 | 判断 |
 * |---|---|
 * | 答案列 | 整列非空值都是单个字母 A–H（`A` / `B` / `D`） |
 * | 题干列 | 非空、平均字数最长，且不是整列单字母 |
 * | 选项列 | 题干右侧的连续列，取值互不相同、长度适中（内联选项列不参与，它会被当成解析） |
 *
 * 认不出来就返回 null，让调用方照旧报错——**宁可报错，也不猜着导错**。
 */
object ColumnSniffer {

    /** 只看前若干行，够判断形状又不会被超大文件拖住 */
    private const val SAMPLE_ROWS = 80

    /** 选项正文的平均长度上限：超过这个数多半是解析/材料列 */
    private const val OPTION_MAX_AVG = 60

    data class Layout(
        /** 数据从第几行开始（0 起）：有可能是被当成数据的自制表头行 */
        val dataStart: Int,
        val fields: Map<Int, String>,
        val options: Map<Int, String>
    ) {
        /** 给用户看的推断说明：`第 2 列 → 题干，第 5 列 → 答案` */
        fun describe(): String {
            val parts = ArrayList<String>()
            fields.entries.sortedBy { it.key }.forEach { (c, f) ->
                parts.add("第 ${c + 1} 列 → " + label(f))
            }
            options.entries.sortedBy { it.key }.forEach { (c, k) ->
                parts.add("第 ${c + 1} 列 → 选项 $k")
            }
            return parts.joinToString("，")
        }

        private fun label(f: String): String = when (f) {
            Field.STEM -> "题干"
            Field.ANSWER -> "答案"
            else -> f
        }
    }

    fun detect(table: List<List<String>>): Layout? {
        if (table.size < 3) return null
        for (start in candidateStarts(table)) {
            val rows = table.drop(start).take(SAMPLE_ROWS)
            if (rows.size >= 2) layout(rows, start)?.let { return it }
        }
        return null
    }

    /**
     * 第一行是否自制表头：每格都很短（≤6 字）就先当表头跳过，否则整表都是数据。
     * 两种起点都试一遍，先试更可能的那个。
     */
    private fun candidateStarts(table: List<List<String>>): List<Int> {
        val first = table.first()
        val looksHeader = first.all { it.isBlank() || it.trim().length <= 6 }
        return if (looksHeader) listOf(1, 0) else listOf(0, 1)
    }

    private fun layout(rows: List<List<String>>, start: Int): Layout? {
        val width = rows.maxOf { it.size }
        if (width < 2) return null

        val cols = (0 until width).map { c -> rows.map { it.getOrElse(c) { "" }.trim() } }
        // 只认「大部分行都有值」的列：稀疏列多半是备注/补丁数据
        val solid = (0 until width).filter { c ->
            cols[c].count { it.isNotEmpty() } * 100 >= rows.size * 60
        }
        if (solid.isEmpty()) return null

        // 答案列：整列都是单个 A–H 字母
        val answerCol = solid.firstOrNull { c ->
            val vals = cols[c].filter { it.isNotEmpty() }
            vals.size >= 2 && vals.all { it.length == 1 && it[0].uppercaseChar() in 'A'..'H' }
        }

        // 题干列：平均字数最长，且不是整列单字母
        val stemCol = solid
            .filter { it != answerCol }
            .filter { c ->
                val vals = cols[c].filter { it.isNotEmpty() }
                vals.any { it.length > 4 } && !vals.all { it.length == 1 }
            }
            .maxByOrNull { c ->
                cols[c].filter { it.isNotEmpty() }.map { it.length }.average()
            } ?: return null

        // 选项列：题干右侧、答案列之前的连续列；长度适中的正文才算
        val options = LinkedHashMap<Int, String>()
        val optionEnd = if (answerCol != null && answerCol > stemCol) answerCol else width
        var key = 'A'
        for (c in (stemCol + 1) until optionEnd) {
            val vals = cols[c].filter { it.isNotEmpty() }
            val avg = if (vals.isEmpty()) 0.0 else vals.map { it.length }.average()
            if (vals.size < 2 || avg > OPTION_MAX_AVG || cols[c].distinct().size < 2) break
            options[c] = key.toString()
            key++
            if (options.size >= 8) break
        }

        // 只有「题干 + 答案」或「题干 + 选项」这种能自证的结构才采纳
        if (answerCol == null && options.isEmpty()) return null

        val fields = LinkedHashMap<Int, String>()
        fields[stemCol] = Field.STEM
        answerCol?.let { fields[it] = Field.ANSWER }

        return Layout(start, fields, options)
    }
}
