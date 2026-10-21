package com.lytree.qbank.data.importer

import java.io.InputStream
import java.nio.charset.Charset

/**
 * CSV / TSV 解析。特点：
 * - 自动识别分隔符（, ; Tab |）
 * - 自动识别编码（UTF-8 / UTF-8-BOM / GBK）
 * - 支持带引号字段、字段内换行、"" 转义
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

        if (!fieldByCol.containsValue(Field.STEM)) {
            issues.add(
                ImportIssue(1, ImportIssue.Level.ERROR, "未找到「题干/题目/stem」列，无法识别表头")
            )
            return ImportResult(emptyList(), issues, "CSV", table.size)
        }

        val raws = ArrayList<RawQuestion>()
        for (r in 1 until table.size) {
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
                    JsonImporter.parseInlineOptions(raw, row[col])
                }
            }
            raws.add(raw)
        }
        return ImportResult(raws, issues, "CSV", table.size - 1)
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
}
