package com.lytree.qbank.data.importer

/**
 * Anki 纯文本（TSV / `.txt`）版式。
 *
 * Anki 的「导出 → 笔记 → 纯文本」会在文件头写几行 `#` 指令，其余一行一张卡片：
 *
 * ```
 * #separator:tab
 * #html:true
 * #tags column:4
 * <img src="s01_q01.png">	D	第1套 第1题	图推700题
 * ```
 *
 * 和普通 CSV / TSV 的关键差别是**没有表头**，列的含义由笔记类型的字段顺序决定。这里按最常见的
 * Basic 版式映射：
 *
 * | 列 | 映射 |
 * |---|---|
 * | 第 1 列（正面） | 题干；里面的 `<img>` 转成 Markdown 图片并挪进「材料」（题干只做纯文本渲染） |
 * | 第 2 列（背面） | 答案：字母 → 单选 / 多选；`对/错/√/×/T/F` → 判断；其它原样交给规范化 |
 * | 第 3 列起 | 备注类字段 → 解析；正面只有图时，第一段备注顶上来当题干（如「第1套 第1题」） |
 * | `#tags column:N` 指定的列 | 标签 → 知识点 |
 *
 * 两处自动兜底：
 * 1. **没有选项列**（Anki 卡片本来就没有）时，按答案字母生成 A–D 占位选项，文字用零宽空格——
 *    图推这类题的四个选项就画在整题图里，答题时只该看到字母圆标；
 * 2. 占位选项的个数取**全文件答案字母的最大值**（最大是 D 就 4 个，出现 E 就 5 个）。
 */
object AnkiImporter {

    /** 没有文字的占位选项：图推题的 A/B/C/D 在图里，界面上只留字母圆标 */
    const val PLACEHOLDER = "\u200B"

    /** 题干为空时的兜底文案（正面只有一张图、也没有备注） */
    private const val EMPTY_STEM = "（见图）"

    /** 认得出来才按 Anki 版式走，避免把普通 CSV 里 `#` 开头的注释当指令 */
    private val KNOWN_DIRECTIVES = listOf(
        "#separator:", "#html:", "#columns:", "#tags column:", "#notetype:", "#deck:"
    )

    private val HTML_IMAGE = Regex(
        """<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE
    )
    private val HTML_BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val HTML_BLOCK_END = Regex(
        """</\s*(p|div|li|tr|h[1-6]|blockquote|section)\s*>""", RegexOption.IGNORE_CASE
    )
    private val HTML_TAG = Regex("""<[^>]+>""")
    private val MD_IMAGE = Regex("""!\[[^\]]*]\(\s*([^)\s]+)\s*\)""")
    private val BLANK_LINES = Regex("""\n{3,}""")

    /** 答案列的常见前缀：`答案：A`、`正确答案是 B`、`故选 C`、`Answer: D` */
    private val ANSWER_PREFIX = Regex(
        """^(?:正确答案|参考答案|标准答案|正确选项|答案|故选|应选|选|answer|ans|correct)\s*[是为]?\s*[:：]?\s*""",
        RegexOption.IGNORE_CASE
    )

    /** 答案字母后面的量词：`A项`、`B选项`、`C正确` */
    private val KEY_SUFFIX = Regex("""(?:选项|项|正确|符合|不符)$""")

    /** 文件头信息 */
    data class Head(
        /** 头部指令占掉的行数，数据从这一行开始 */
        val lines: Int,
        val separator: Char,
        val html: Boolean,
        /** 标签列下标（0 起），-1 表示没声明 */
        val tagsColumn: Int
    )

    /** 一张卡片拆出来的四段 */
    private data class Card(
        val rowNo: Int,
        val front: String,
        val back: String,
        val extra: String,
        val tags: String
    )

    /** 文件头是不是 Anki 指令——只看前 8 行，够用且不会误判末尾内容 */
    fun looksLikeAnki(text: String): Boolean =
        text.lineSequence().take(8).any { line ->
            val t = line.trimStart()
            t.startsWith("#") && KNOWN_DIRECTIVES.any { t.lowercase().startsWith(it) }
        }

    /**
     * 解析 Anki 文件。不是 Anki 版式（没有 `#` 指令）时返回 null，由调用方走普通 CSV 逻辑。
     */
    fun parse(text: String, issues: MutableList<ImportIssue>): ImportResult? {
        val head = readHead(text) ?: return null
        val lines = text.split('\n')
            .map { it.trimEnd('\r') }
            .drop(head.lines)
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "Anki 文件里没有卡片（只有 # 指令行）"))
            return ImportResult(emptyList(), issues, "Anki", 0)
        }

        val cards = lines.mapIndexed { i, line -> toCard(i + head.lines + 1, line, head) }

        // 占位选项该生成几个：取全文件答案字母的最大值，出现 E 就说明有 5 个选项
        var optionCount = 0
        cards.forEach { c ->
            val keys = answerKeys(c.back)
            if (keys.isNotEmpty()) {
                optionCount = maxOf(optionCount, keys.maxOf { it[0] - 'A' } + 1)
            }
        }
        if (optionCount == 1) optionCount = 2
        optionCount = optionCount.coerceIn(0, 8)

        val raws = ArrayList<RawQuestion>()
        var synthesized = 0
        var judge = 0
        cards.forEach { c ->
            val raw = RawQuestion(c.rowNo)

            // 题干里的图挪进材料：题干是纯文本控件，Markdown 图片在那里只会显示成一串字
            val images = MD_IMAGE.findAll(c.front).map { it.groupValues[1] }.toList()
            var stem = stripImages(c.front)
            val extra = c.extra.trim()
            var extraAsStem = false
            if (stem.isEmpty()) {
                // 正面只有图：优先拿备注当题干（图推包的「第1套 第1题」就靠这个定位）
                val first = extra.lineSequence().firstOrNull { it.isNotBlank() }
                stem = first ?: EMPTY_STEM
                extraAsStem = first != null
            }
            raw.put(Field.STEM, stem)
            // 一行一张图，材料渲染器会把每张图铺满材料宽度、点开能看大图
            if (images.isNotEmpty()) raw.put(Field.MATERIAL, images.joinToString("\n") { "![]($it)" })

            val keys = answerKeys(c.back)
            when {
                keys.isNotEmpty() && optionCount > 0 -> {
                    for (i in 0 until optionCount) raw.options[('A' + i).toString()] = PLACEHOLDER
                    raw.put(Field.TYPE, if (keys.size > 1) "多选" else "单选")
                    raw.put(Field.ANSWER, keys.joinToString(","))
                    synthesized++
                }
                // 背面是「对/错/√/×」→ 判断题，选项沿用 A/B 表示正确/错误
                isJudge(c.back) -> {
                    raw.options["A"] = "正确"
                    raw.options["B"] = "错误"
                    raw.put(Field.TYPE, "判断")
                    raw.put(Field.ANSWER, if (isTrue(c.back)) "A" else "B")
                    judge++
                }
                // 背面空着、但这批卡片是选择题 → 先按「待补答案」导入
                c.back.isBlank() && optionCount > 0 -> {
                    for (i in 0 until optionCount) raw.options[('A' + i).toString()] = PLACEHOLDER
                    raw.put(Field.TYPE, "单选")
                    synthesized++
                }
                // 问答型卡片（背面是一段话）：原样交给规范化，由它给出「仅支持选择题」的提示
                else -> c.back.takeIf { it.isNotBlank() }?.let { raw.put(Field.ANSWER, it) }
            }

            if (!extraAsStem) raw.put(Field.ANALYSIS, extra)
            raw.put(Field.KNOWLEDGE, c.tags)
            raws.add(raw)
        }

        issues.add(
            ImportIssue(
                0,
                ImportIssue.Level.INFO,
                "已按 Anki 版式解析：第 1 列 → 题干，第 2 列 → 答案" +
                    if (head.tagsColumn >= 0) "，第 ${head.tagsColumn + 1} 列 → 标签" else ""
            )
        )
        if (synthesized > 0) {
            issues.add(
                ImportIssue(
                    0,
                    ImportIssue.Level.INFO,
                    "Anki 卡片没有选项列，已按答案字母为 $synthesized 题生成 A–${
                        ('A' + (optionCount - 1))
                    } 占位选项（选项内容本该在题图里）"
                )
            )
        }
        if (judge > 0) {
            issues.add(
                ImportIssue(0, ImportIssue.Level.INFO, "$judge 题背面是「对/错」，已按判断题导入")
            )
        }
        return ImportResult(raws, issues, "Anki", lines.size)
    }

    // ---------------- 头部与列 ----------------

    private fun readHead(text: String): Head? {
        val lines = text.split('\n')
        var separator = '\t'
        var html = false
        var tags = -1
        var n = 0
        var known = false
        while (n < lines.size) {
            val line = lines[n].trimEnd('\r').trim()
            if (!line.startsWith("#")) break
            val low = line.lowercase()
            when {
                low.startsWith("#separator:") -> {
                    known = true
                    separator = when (val v = low.removePrefix("#separator:").trim()) {
                        "comma" -> ','
                        "semicolon" -> ';'
                        "pipe" -> '|'
                        "tab", "" -> '\t'
                        else -> v.firstOrNull() ?: '\t'
                    }
                }
                low.startsWith("#html:") -> {
                    known = true
                    html = low.removePrefix("#html:").trim() != "false"
                }
                low.startsWith("#tags column:") -> {
                    known = true
                    tags = (low.removePrefix("#tags column:").trim().toIntOrNull() ?: 0) - 1
                }
                // 列名对列位映射没有意义（Anki 写的是字段名），认下来但不参与映射
                low.startsWith("#columns:") ||
                    low.startsWith("#notetype:") ||
                    low.startsWith("#deck:") -> known = true
            }
            n++
        }
        return if (known) Head(n, separator, html, tags) else null
    }

    private fun toCard(rowNo: Int, line: String, head: Head): Card {
        val cells = line.split(head.separator)
        val front = toMarkdown(cells.getOrElse(0) { "" }, head.html)
        val back = toMarkdown(cells.getOrElse(1) { "" }, head.html)
        // 第 3 列起是备注类字段，标签列要排除在外
        val extra = cells.withIndex()
            .filter { it.index >= 2 && it.index != head.tagsColumn }
            .joinToString("\n") { toMarkdown(it.value, head.html) }
            .trim()
        val tags = if (head.tagsColumn >= 0) {
            cells.getOrElse(head.tagsColumn) { "" }.trim()
        } else ""
        return Card(rowNo, front, back, extra, tags)
    }

    // ---------------- HTML → Markdown ----------------

    /**
     * 卡片字段是 HTML（`#html:true`）时，只做三件对答题有用的事：
     * 图片留下来、换行留下来、其余标签和实体清掉。样式表 / class 之类一律丢弃。
     */
    fun toMarkdown(raw: String, html: Boolean): String {
        if (raw.isEmpty()) return ""
        // 图片先转成 Markdown，免得被后面的「清标签」吃掉
        var s = HTML_IMAGE.replace(raw) { "![](${it.groupValues[1].trim()})" }
        if (html) {
            s = HTML_BR.replace(s, "\n")
            s = HTML_BLOCK_END.replace(s, "\n")
            s = HTML_TAG.replace(s, "")
            s = decodeEntities(s)
        }
        s = s.split('\n').joinToString("\n") { it.trimEnd() }
        return BLANK_LINES.replace(s, "\n\n").trim()
    }

    private fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        var out = s
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&") // 放最后：避免 &amp;lt; 被二次解码
        out = Regex("""&#(\d{1,6});""").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }
                ?: m.value
        }
        out = Regex("""&#[xX]([0-9a-fA-F]{1,6});""").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }
                ?: m.value
        }
        return out
    }

    /** 只留下图片引用（一行一张），其余排版信息丢掉 */
    private fun stripImages(markdown: String): String =
        MD_IMAGE.replace(markdown, "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    // ---------------- 答案 ----------------

    /**
     * 背面是纯字母时取出去重排序的选项键；否则返回空表。
     *
     * 背面被人工写过之后什么样子都有，这些都要认：
     * `A`、`A,C`、`ABD`、`答案：A`、`正确答案是 B`、`（C）`、`D.`、`选 A`、`A 项`。
     * 认不出来就返回空表，交给「判断题 / 问答型」分支，绝不硬猜。
     */
    fun answerKeys(answer: String): List<String> {
        val t = stripAnswerDecor(answer)
        if (t.isEmpty()) return emptyList()
        val parts = t.split(',', '，', '、', ';', '；', '/', ' ', '\n')
            .map { cleanKey(it) }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val keys = LinkedHashSet<String>()
        for (p in parts) {
            when {
                p.length == 1 && p[0] in 'A'..'H' -> keys.add(p)
                p.length in 2..8 && p.all { it in 'A'..'H' } -> p.forEach { keys.add(it.toString()) }
                else -> return emptyList()
            }
        }
        return keys.sorted()
    }

    /** 剥掉「答案：」「正确答案是」「故选」这类前缀与句末标点 */
    private fun stripAnswerDecor(raw: String): String {
        var s = ANSWER_PREFIX.replace(raw.trim().uppercase(), "")
        s = s.trim().trim('。', '！', '!', '.', '；', ';', ' ')
        return s
    }

    /** 剥掉单个字母周围的括号、序号点与量词：`（A）` / `A.` / `A项` / `A选项` */
    private fun cleanKey(raw: String): String {
        var s = raw.trim().trim(
            '(', ')', '（', '）', '[', ']', '【', '】', '<', '>',
            '。', '.', '、', ',', '：', ':', '-', '—', ' '
        )
        s = KEY_SUFFIX.replace(s, "")
        return s.trim()
    }

    private fun isJudge(answer: String): Boolean =
        answer.trim().uppercase() in setOf(
            "对", "错", "正确", "错误", "√", "×", "✓", "✗", "T", "F", "TRUE", "FALSE", "YES", "NO", "是", "否"
        )

    private fun isTrue(answer: String): Boolean =
        answer.trim().uppercase() in setOf("对", "正确", "√", "✓", "T", "TRUE", "YES", "是")
}
