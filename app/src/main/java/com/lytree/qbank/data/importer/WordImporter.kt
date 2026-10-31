package com.lytree.qbank.data.importer

import android.content.Context
import com.lytree.qbank.data.model.Word
import com.lytree.qbank.data.model.WordCategory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** 词库支持的文档格式 */
enum class WordFormat(val ext: String, val label: String, val mime: String) {
    CSV("csv", "CSV", "text/csv"),
    XLSX("xlsx", "Excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    DOCX("docx", "Word 文档", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    TXT("txt", "TXT", "text/plain"),
    JSON("json", "JSON", "application/json");

    companion object {
        fun of(name: String): WordFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.ext == ext }
        }

        /** 老版 .doc 是二进制格式，读不了，单独提示别让用户白等 */
        fun isLegacyDoc(name: String): Boolean = name.substringAfterLast('.', "").equals("doc", true)

        fun detect(fileName: String, bytes: ByteArray): WordFormat {
            of(fileName)?.let { return it }
            val head = bytes.take(4)
            if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
            ) {
                // zip 壳：里面是 word/document.xml 就是 docx，否则按 xlsx 试
                return if (DocxReader.looksLikeDocx(bytes)) DOCX else XLSX
            }
            val text = CsvImporter.decode(bytes).trimStart()
            if (text.startsWith("{") || text.startsWith("[")) return JSON
            if (text.contains(',') && text.lineSequence().take(1).any { it.contains("词条") || it.contains("成语") }) {
                return CSV
            }
            return TXT
        }
    }
}

/** 词库表头对应的规范字段 */
object WordField {
    const val WORD = "word"
    const val PINYIN = "pinyin"
    const val CATEGORY = "category"
    const val TAG = "tag"
    const val MEANING = "meaning"
    const val EXAMPLE = "example"
    const val SOURCE = "source"
}

/**
 * 词库表头别名。飞书文档导出的表格、手抄的 Excel、Word 里粘的表，
 * 表头叫法都不一样，这里按「归一化后完全相等」匹配，不做模糊包含，避免串列。
 */
object WordAliases {

    private val ALIASES: Map<String, Set<String>> = mapOf(
        WordField.WORD to setOf(
            "词条", "词", "词语", "词汇", "成语", "实词", "字词", "词目", "条目",
            "词语名称", "成语名称", "字词积累", "词语积累",
            "word", "text", "entry", "term", "name", "title",
            // 老词表常把「成语」本身当表头
            "成语积累"
        ),
        WordField.PINYIN to setOf(
            "拼音", "注音", "读音", "全拼", "拼音首字母", "音序",
            "pinyin", "py", "pronunciation", "spell"
        ),
        WordField.CATEGORY to setOf(
            "分类", "类别", "词类", "类型", "大类",
            "category", "type", "class", "kind"
        ),
        WordField.TAG to setOf(
            "细分", "小类", "子类", "词性", "标签", "属性", "感情色彩", "结构",
            "subcategory", "tag", "partofspeech", "pos", "label"
        ),
        WordField.MEANING to setOf(
            "释义", "解释", "意思", "含义", "词义", "意思解释", "注释", "注解", "说明",
            "meaning", "definition", "def", "explain", "explanation", "note", "desc"
        ),
        WordField.EXAMPLE to setOf(
            "例句", "示例", "例子", "用法", "造句", "例句示范", "示例句子",
            "example", "sentence", "usage", "sample"
        ),
        WordField.SOURCE to setOf(
            "出处", "来源", "引用", "典出", "源自",
            "source", "origin", "from", "reference"
        )
    )

    private val NORM: Map<String, Set<String>> =
        ALIASES.entries.associate { (k, v) -> k to v.map { FieldAliases.normHeader(it) }.toSet() }

    /** 表头 → 规范字段；认不出返回 null */
    fun resolve(header: String): String? {
        val h = FieldAliases.normHeader(header)
        if (h.isEmpty()) return null
        for ((field, aliases) in NORM) if (aliases.contains(h)) return field
        return null
    }
}

/** 一行原始数据（表头已映射成字段） */
private class WordRow(val rowNo: Int, val values: Map<String, String>) {
    fun get(field: String): String = values[field]?.trim() ?: ""
}

/** 词库导入产物 */
data class WordOutcome(
    val format: String,
    /** 文件里的数据行数（不含表头） */
    val total: Int,
    /** 已按「首字母 → 全拼 → 词条」排好序、去过重 */
    val words: List<Word>,
    val issues: List<ImportIssue>,
    /** 没有「分类」列、靠字数推测出来的条数 */
    val guessed: Int = 0
) {
    val errorCount get() = issues.count { it.level == ImportIssue.Level.ERROR }
    val warnCount get() = issues.count { it.level == ImportIssue.Level.WARN }
    val idiomCount get() = words.count { it.categoryEnum == WordCategory.IDIOM }
    val contentCount get() = words.count { it.categoryEnum == WordCategory.WORD }
}

/**
 * 词库导入：文档 → 词条列表。
 *
 * 与试卷导入的关键差别（也是这次改动的目的）：
 * * **不再只收成语**——四字成语和普通实词一起进，靠「分类」列区分；
 * * 没有分类列时按字数推测（4 字记成语，其余记实词），并在汇总里说明推测了多少条；
 * * 只丢真正没法用的行（空、没有汉字、长到像整句），**不再按字数一刀切丢掉非成语**；
 * * 排好序再交出去：首字母 → 全拼 → 词条，入库和预览都是同一个顺序。
 */
object WordImporter {

    /** 单个词条最长字数，超过基本就是一整句了（成语最长 8 字，实词更短） */
    private const val MAX_WORD_LEN = 16

    fun build(
        context: Context,
        format: WordFormat,
        bytes: ByteArray,
        selectedIndices: Set<Int>? = null
    ): WordOutcome {
        PinyinTable.ensure(context)
        val issues = ArrayList<ImportIssue>()

        val rows = extractRows(format, bytes, issues)
        val all = ArrayList<Word>()
        var guessed = 0
        val seen = HashMap<String, Int>()   // 词条 → 首次出现的行号
        var duplicates = 0

        rows.forEach { row ->
            val parsed = toWord(row, issues) ?: return@forEach
            if (parsed.guessed) guessed++
            val word = parsed.word
            if (seen.containsKey(word.text)) {
                duplicates++
                return@forEach
            }
            seen[word.text] = row.rowNo
            all.add(word)
        }

        if (duplicates > 0) {
            issues.add(
                ImportIssue(0, ImportIssue.Level.INFO, "有 $duplicates 条重复词条，已按首次出现的保留")
            )
        }
        if (guessed > 0) {
            issues.add(
                ImportIssue(
                    0, ImportIssue.Level.INFO,
                    "有 $guessed 条没有「分类」列可读，已按字数推测：4 字记「成语」，其余记「实词」"
                )
            )
        }

        val chosen = if (selectedIndices == null) all
        else all.filterIndexed { i, _ -> i in selectedIndices }

        val sorted = chosen.sortedWith(
            compareBy({ it.initial }, { PinyinTable.sortKey(it.pinyin) }, { it.text })
        )

        if (sorted.isEmpty()) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "没有解析出任何词条，请检查文档里的表格或表头"))
        }

        return WordOutcome(
            format = format.label,
            total = rows.size,
            words = sorted,
            issues = issues,
            guessed = guessed
        )
    }

    // ---------------- 取行 ----------------

    private fun extractRows(
        format: WordFormat,
        bytes: ByteArray,
        issues: MutableList<ImportIssue>
    ): List<WordRow> = when (format) {
        WordFormat.JSON -> parseJson(bytes, issues)
        WordFormat.XLSX -> tableRows(runCatching {
            XlsxReader.read(ByteArrayInputStream(bytes)).rows
        }.getOrElse {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "Excel 解析失败：${it.message}"))
            emptyList()
        }, issues)

        WordFormat.DOCX -> docxRows(bytes, issues)
        WordFormat.CSV -> tableRows(csvTable(bytes), issues)
        WordFormat.TXT -> textRows(bytes, issues)
    }

    private fun csvTable(bytes: ByteArray): List<List<String>> {
        val text = CsvImporter.decode(bytes)
        return CsvImporter.parseTable(text, CsvImporter.detectDelimiter(text))
    }

    /** Word 文档：优先取「表头能认出词条列」的那张表；没有表格就按段落解析 */
    private fun docxRows(bytes: ByteArray, issues: MutableList<ImportIssue>): List<WordRow> {
        val doc = DocxReader.read(ByteArrayInputStream(bytes))
        if (doc.isEmpty) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "文档里没有读到内容，确认是 .docx 格式"))
            return emptyList()
        }

        val table = doc.tables.firstOrNull { t ->
            val head = t.firstOrNull() ?: return@firstOrNull false
            head.any { WordAliases.resolve(it) == WordField.WORD }
        } ?: doc.tables.maxByOrNull { it.size }

        if (table != null) {
            if (doc.tables.size > 1) {
                issues.add(
                    ImportIssue(0, ImportIssue.Level.INFO, "文档里有 ${doc.tables.size} 张表，已取词条最多的那张")
                )
            }
            return tableRows(table, issues)
        }

        issues.add(
            ImportIssue(0, ImportIssue.Level.WARN, "文档里没有表格，已按「一段一个词条」解析")
        )
        return paragraphRows(doc.paragraphs)
    }

    /** 无表格文档 / TXT：一行一个词条，Tab 或连续空格分列（词条｜释义｜…） */
    private fun paragraphRows(paragraphs: List<String>): List<WordRow> {
        val rows = ArrayList<WordRow>()
        paragraphs.forEachIndexed { i, para ->
            val cells = splitLoose(para)
            if (cells.isEmpty()) return@forEachIndexed
            // 没有任何分列的整行：短的是词条，长的是标题 / 说明 / 例句，跳过
            if (cells.size == 1 && (cells[0].length > 8 || cells[0].any { it in "。！？；" })) {
                return@forEachIndexed
            }
            val values = HashMap<String, String>()
            values[WordField.WORD] = cells[0]
            // 第二列：像拼音就记拼音，否则按释义处理
            if (cells.size >= 2) {
                if (looksLikePinyin(cells[1])) values[WordField.PINYIN] = cells[1]
                else values[WordField.MEANING] = cells[1]
            }
            if (cells.size >= 3) values[WordField.MEANING] = cells[2]
            if (cells.size >= 4) values[WordField.EXAMPLE] = cells[3]
            rows.add(WordRow(i + 1, values))
        }
        return rows
    }

    /**
     * TXT：默认「一行一个词条」。但很多人会把 CSV 的内容存成 .txt，
     * 所以首行带逗号且能认出「词条 / 词语 / 成语」表头时，改走表格解析。
     */
    private fun textRows(bytes: ByteArray, issues: MutableList<ImportIssue>): List<WordRow> {
        val text = CsvImporter.decode(bytes)
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val looksTabular = (firstLine.contains(',') || firstLine.contains('\t') || firstLine.contains(';')) &&
                listOf("词条", "词语", "成语", "实词", "词汇").any { firstLine.contains(it) }
        if (looksTabular) {
            issues.add(ImportIssue(0, ImportIssue.Level.INFO, "首行像表头，已按表格（CSV）解析"))
            return tableRows(CsvImporter.parseTable(text, CsvImporter.detectDelimiter(text)), issues)
        }
        return paragraphRows(text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList())
    }

    /** Tab / 「|」/ 连续两个以上空格 都当列分隔 */
    private fun splitLoose(line: String): List<String> {
        val sep = when {
            line.contains('\t') -> Regex("\\t")
            line.contains('|') -> Regex("\\s*\\|\\s*")
            Regex("[ ]{2,}|　+").containsMatchIn(line) -> Regex("[ ]{2,}|　+")
            else -> null
        } ?: return listOf(line.trim()).filter { it.isNotEmpty() }
        return line.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 二维表 → 行；表头认不出词条列时按列序兜底 */
    private fun tableRows(table: List<List<String>>, issues: MutableList<ImportIssue>): List<WordRow> {
        if (table.isEmpty()) return emptyList()
        val header = table.first()
        val fieldByCol = HashMap<Int, String>()
        header.forEachIndexed { i, h ->
            val f = WordAliases.resolve(h) ?: return@forEachIndexed
            if (!fieldByCol.containsValue(f)) fieldByCol[i] = f
        }

        val hasHeader = fieldByCol.containsValue(WordField.WORD)
        val start = if (hasHeader) 1 else 0
        if (!hasHeader) {
            val firstRows = table.take(6).map { it.getOrNull(0).orEmpty() }
            val wordish = firstRows.count { PinyinTable.hasHan(it) }
            issues.add(
                ImportIssue(
                    0, ImportIssue.Level.WARN,
                    if (wordish >= firstRows.size / 2 && firstRows.isNotEmpty())
                        "没认出表头，已按列序解析：第 1 列词条、第 2 列拼音/释义，其余依次为释义、例句"
                    else
                        "没认出「词条 / 词语 / 成语」表头，请检查第一行是不是表头"
                )
            )
        }

        val rows = ArrayList<WordRow>()
        for (r in start until table.size) {
            val row = table[r]
            if (row.all { it.isBlank() }) continue
            val values = HashMap<String, String>()
            if (hasHeader) {
                fieldByCol.forEach { (col, field) ->
                    row.getOrNull(col)?.takeIf { it.isNotBlank() }?.let { values[field] = it }
                }
            } else {
                row.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { values[WordField.WORD] = it }
                row.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
                    if (looksLikePinyin(it)) values[WordField.PINYIN] = it
                    else values[WordField.MEANING] = it
                }
                row.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { values[WordField.MEANING] = it }
                row.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { values[WordField.EXAMPLE] = it }
                row.getOrNull(4)?.takeIf { it.isNotBlank() }?.let { values[WordField.SOURCE] = it }
            }
            if (values.isEmpty()) continue
            rows.add(WordRow(r + 1, values))
        }
        return rows
    }

    private fun parseJson(bytes: ByteArray, issues: MutableList<ImportIssue>): List<WordRow> {
        val text = CsvImporter.decode(bytes).trim()
        val array: JSONArray = try {
            when {
                text.startsWith("[") -> JSONArray(text)
                else -> {
                    val obj = JSONObject(text)
                    obj.optJSONArray("words")
                        ?: obj.optJSONArray("词条")
                        ?: obj.optJSONArray("list")
                        ?: throw IllegalArgumentException("JSON 里没有 words / list 数组")
                }
            }
        } catch (e: Exception) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "JSON 解析失败：${e.message}"))
            return emptyList()
        }

        val rows = ArrayList<WordRow>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val values = HashMap<String, String>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val field = WordAliases.resolve(k) ?: continue
                val v = o.optString(k, "").trim()
                if (v.isNotEmpty() && !values.containsKey(field)) values[field] = v
            }
            if (values.isNotEmpty()) rows.add(WordRow(i + 1, values))
        }
        if (rows.isEmpty()) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "JSON 里没有可识别的词条字段（词条 / 词语 / word）"))
        }
        return rows
    }

    // ---------------- 逐行规范化 ----------------

    /** 规范化结果：词条 + 这条的分类是不是推测出来的 */
    private class ParsedWord(val word: Word, val guessed: Boolean)

    private fun toWord(row: WordRow, issues: MutableList<ImportIssue>): ParsedWord? {
        var text = row.get(WordField.WORD)
            .trim()
            .trim('"', '\'', '“', '”', '「', '」')
            .replace(Regex("[\\s\\u00A0]+"), "")
        text = stripLeadingIndex(text)

        if (text.isEmpty()) {
            issues.add(ImportIssue(row.rowNo, ImportIssue.Level.WARN, "词条为空，已跳过"))
            return null
        }
        if (!PinyinTable.hasHan(text)) {
            issues.add(
                ImportIssue(row.rowNo, ImportIssue.Level.WARN, "「$text」不含汉字，不是成语或实词，已跳过")
            )
            return null
        }
        if (text.length > MAX_WORD_LEN) {
            issues.add(
                ImportIssue(
                    row.rowNo, ImportIssue.Level.WARN,
                    "「${text.take(8)}…」有 ${text.length} 个字，像是整句，已跳过"
                )
            )
            return null
        }

        // 分类：显式列优先，没有才推测（推测的条数最后汇总，不在每行刷屏）
        val rawCategory = row.get(WordField.CATEGORY)
        val explicit = WordCategory.parse(rawCategory)
        if (rawCategory.isNotEmpty() && explicit == null) {
            issues.add(
                ImportIssue(
                    row.rowNo, ImportIssue.Level.INFO,
                    "分类「$rawCategory」无法识别，已按字数推测"
                )
            )
        }
        val guessed = explicit == null
        val category = explicit ?: if (text.length == 4) WordCategory.IDIOM else WordCategory.WORD

        // 拼音：表里给了就用表里的（去声调），没给就按内置字表逐字拼
        val rawPinyin = row.get(WordField.PINYIN)
        var pinyin = if (rawPinyin.isNotEmpty()) PinyinTable.normalizePinyin(rawPinyin) else ""
        if (pinyin.isEmpty()) pinyin = PinyinTable.fullPinyin(text)
        val initial = PinyinTable.initialOf(pinyin)
        if (pinyin.isEmpty()) {
            issues.add(
                ImportIssue(row.rowNo, ImportIssue.Level.WARN, "「$text」取不到拼音，已归到 # 组")
            )
        }

        return ParsedWord(
            Word(
                text = text,
                pinyin = pinyin,
                initial = initial,
                category = category.code,
                tag = row.get(WordField.TAG),
                meaning = row.get(WordField.MEANING),
                example = row.get(WordField.EXAMPLE),
                source = row.get(WordField.SOURCE),
                createdAt = System.currentTimeMillis()
            ),
            guessed
        )
    }

    /** 「1. 画蛇添足」「2、踌躇」「· 徜徉」这类前缀去掉 */
    private fun stripLeadingIndex(s: String): String =
        s.replace(Regex("^[0-9０-９]{1,3}\\s*[.、)）]\\s*"), "")
            .replace(Regex("^[•·\\-—]\\s*"), "")
            .trim()

    /** 第二列像不像拼音（纯字母 + 空格，2~40 字符） */
    private fun looksLikePinyin(s: String): Boolean {
        val t = s.trim()
        if (t.length !in 2..40) return false
        return t.all { it in 'a'..'z' || it in 'A'..'Z' || it == ' ' || it == '\'' } && t.any { it in 'a'..'z' || it in 'A'..'Z' }
    }
}
