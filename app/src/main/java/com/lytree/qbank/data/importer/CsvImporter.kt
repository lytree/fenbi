package com.lytree.qbank.data.importer

import java.io.InputStream
import java.nio.charset.Charset

/**
 * CSV / TSV 解析。特点：
 * - 自动识别分隔符（, ; Tab |）
 * - 自动识别编码（UTF-8 / UTF-8-BOM / GBK）
 * - 支持带引号字段、字段内换行、"" 转义
 *
 * 现在不再作为独立导入格式对外开放——它是 **Excel 导入的内部引擎**
 * （XlsxReader 读出单元格后，转成表格文本走这里），以及包内候选的表格式解析器。
 */
object CsvImporter {

    private val DELIMITERS = charArrayOf(',', '\t', ';', '|')

    fun parse(input: InputStream, issues: MutableList<ImportIssue>): ImportResult {
        val bytes = input.readBytes()
        val text = decode(bytes)

        val delimiter = detectDelimiter(text)

        val table = parseTable(text, delimiter)
        if (table.isEmpty()) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "CSV 内容为空或无法解析"))
            return ImportResult(emptyList(), issues, "CSV", 0)
        }
        return parseRows(table, issues, "CSV")
    }

    /**
     * 已经是二维表的解析（Excel 各工作表、包内候选都走这里）。
     *
     * 单独拆出来是因为 Excel 不该先序列化成 CSV 文本再解析：那样单元格里的逗号、换行要来回转义，
     * 多工作表也没地方放。表格已经是表了，直接按行列走。
     *
     * @param format 结果里回填的格式名（"XLSX" / "CSV"），只影响提示文案
     */
    fun parseRows(
        table: List<List<String>>,
        issues: MutableList<ImportIssue>,
        format: String = "CSV"
    ): ImportResult {
        if (table.isEmpty()) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "文件内容为空或无法解析"))
            return ImportResult(emptyList(), issues, format, 0)
        }

        val header = table.first()

        // 建立 列索引 → 规范字段名 / 选项 key 的映射
        val fieldByCol = HashMap<Int, String>()
        val optionByCol = HashMap<Int, String>()
        header.forEachIndexed { i, h ->
            if (optionByCol.containsKey(i)) return@forEachIndexed
            val opt = FieldAliases.resolveOption(h)
            if (opt != null) {
                optionByCol[i] = opt
                return@forEachIndexed
            }
            val f = FieldAliases.resolve(h)
            if (f != null && !fieldByCol.containsValue(f)) fieldByCol[i] = f
        }

        // 表头认不出来时按内容形状兜底：没有表头的导出（爬虫、PDF 转表、手工整理）也能进
        var dataStart = 1
        if (!fieldByCol.containsValue(Field.STEM)) {
            val layout = ColumnSniffer.detect(table)
            if (layout == null) {
                issues.add(
                    ImportIssue(1, ImportIssue.Level.ERROR, "未找到「题干/题目/stem」列，无法识别表头")
                )
                return ImportResult(emptyList(), issues, format, table.size)
            }
            fieldByCol.clear()
            fieldByCol.putAll(layout.fields)
            optionByCol.clear()
            optionByCol.putAll(layout.options)
            dataStart = layout.dataStart
            issues.add(
                ImportIssue(
                    1, ImportIssue.Level.WARN,
                    "没有表头，已按内容推断列含义：${layout.describe()}。请核对下面的预览，不对就补上表头再导入"
                )
            )
        }

        val raws = ArrayList<RawQuestion>()
        for (r in dataStart until table.size) {
            val row = table[r]
            if (row.all { it.isBlank() }) continue
            val rowNo = r + 1
            val raw = RawQuestion(rowNo)
            for ((col, field) in fieldByCol) {
                if (col < row.size) raw.put(field, row[col])
            }
            for ((col, key) in optionByCol) {
                if (col < row.size) raw.options[key] = row[col]
            }
            // 名为"选项"的整列 → 尝试按内联格式拆
            fieldByCol.forEach { (col, field) ->
                if (field == Field.OPTIONS && col < row.size && row[col].isNotBlank()) {
                    parseInlineOptions(raw, row[col])
                }
            }
            raws.add(raw)
        }
        return ImportResult(raws, issues, format, table.size - dataStart)
    }

    /** 编码识别：优先 UTF-8 BOM，其次严格 UTF-8 校验，失败回落 GBK */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return try {
            val cs = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            cs.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            try {
                String(bytes, Charset.forName("GBK"))
            } catch (_: Exception) {
                String(bytes, Charsets.UTF_8)
            }
        }
    }

    /** 分隔符探测：取前若干行里出现次数最多且不含引号的候选 */
    fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().take(20).joinToString("\n")
        var best = ','
        var bestCount = -1
        for (d in DELIMITERS) {
            val count = countOutsideQuotes(sample, d)
            if (count > bestCount) { bestCount = count; best = d }
        }
        return best
    }

    private fun countOutsideQuotes(s: String, d: Char): Int {
        var inQuote = false
        var n = 0
        for (c in s) {
            when {
                c == '"' -> inQuote = !inQuote
                c == d && !inQuote -> n++
            }
        }
        return n
    }

    /** 解析成二维表 */
    fun parseTable(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                inQuote -> {
                    if (c == '"') {
                        if (i + 1 < n && text[i + 1] == '"') { sb.append('"'); i++ }
                        else inQuote = false
                    } else sb.append(c)
                }
                c == '"' -> inQuote = true
                c == delimiter -> { row.add(sb.toString()); sb.setLength(0) }
                c == '\r' -> Unit
                c == '\n' -> {
                    row.add(sb.toString()); sb.setLength(0)
                    rows.add(row); row = ArrayList()
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) {
            row.add(sb.toString())
            rows.add(row)
        }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    /** "A. 文本" -> ("A", "文本")；"A、文本" 同理；无前缀则返回 null */
    private fun parseInlineOption(s: String): Pair<String, String>? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val m = Regex("^([A-Ha-h])\\s*[.、．:：)）]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(t)
        if (m != null) return m.groupValues[1].uppercase() to m.groupValues[2].trim()
        return null
    }

    /** 解析一整块选项文本，支持换行 / 竖线 / 分号分隔 */
    private fun parseInlineOptions(raw: RawQuestion, text: String) {
        val parts = text.split('\n', '|', '；', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        for (p in parts) {
            val parsed = parseInlineOption(p)
            if (parsed != null) {
                raw.options[parsed.first] = parsed.second
            } else {
                // 无前缀：按出现顺序分配字母
                val idx = raw.options.size
                if (idx < 8) raw.options[('A'.code + idx).toChar().toString()] = p
            }
        }
    }
}
