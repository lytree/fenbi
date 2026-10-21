package com.lytree.qbank.data.importer

import java.io.ByteArrayInputStream
import java.io.InputStream

/** 支持的题库文件格式 */
enum class ImportFormat(val ext: String, val label: String, val mime: String) {
    JSON("json", "JSON", "application/json"),
    CSV("csv", "CSV", "text/csv"),
    XLSX("xlsx", "Excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    TXT("txt", "TXT", "text/plain");

    companion object {
        fun of(name: String): ImportFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.ext == ext }
        }
    }
}

/**
 * 导入调度：格式探测 + 分发 + 规范化 + 校验。
 */
object Importer {

    /** 根据文件名与内容探测格式 */
    fun detect(fileName: String, bytes: ByteArray): ImportFormat {
        ImportFormat.of(fileName)?.let { return it }

        val head = bytes.take(4)
        // PK\x03\x04 → zip（xlsx）
        if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        ) {
            return ImportFormat.XLSX
        }
        val text = String(bytes, Charsets.UTF_8).trimStart()
        if (text.startsWith("{") || text.startsWith("[")) return ImportFormat.JSON
        // 出现列分隔且有中文表头特征 → CSV
        if (text.contains(',') && Regex("(?m)^.*(题干|题目|答案).*$").containsMatchIn(text)) {
            return ImportFormat.CSV
        }
        return ImportFormat.TXT
    }

    fun parse(format: ImportFormat, bytes: ByteArray): ImportResult {
        val issues = ArrayList<ImportIssue>()
        val raw = when (format) {
            ImportFormat.JSON -> JsonImporter.parse(ByteArrayInputStream(bytes), issues)
            ImportFormat.CSV -> CsvImporter.parse(ByteArrayInputStream(bytes), issues)
            ImportFormat.XLSX -> parseXlsx(bytes, issues)
            ImportFormat.TXT -> TxtImporter.parse(ByteArrayInputStream(bytes), issues)
        }
        return raw
    }

    private fun parseXlsx(bytes: ByteArray, issues: MutableList<ImportIssue>): ImportResult {
        return try {
            val sheet = XlsxReader.read(ByteArrayInputStream(bytes))
            if (sheet.rows.isEmpty()) {
                issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "Excel 中没有可读数据"))
                ImportResult(emptyList(), issues, "XLSX", 0)
            } else {
                val csvText = sheet.rows.joinToString("\n") { row ->
                    row.joinToString(",") { cell ->
                        if (cell.contains(',') || cell.contains('"') || cell.contains('\n')) {
                            "\"" + cell.replace("\"", "\"\"") + "\""
                        } else cell
                    }
                }
                val r = CsvImporter.parse(ByteArrayInputStream(csvText.toByteArray(Charsets.UTF_8)), issues)
                r.copy(format = "XLSX")
            }
        } catch (e: Exception) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "Excel 解析失败：${e.message}"))
            ImportResult(emptyList(), issues, "XLSX", 0)
        }
    }

    /**
     * 完整导入流程：解析 → 规范化 → 校验。
     * @param selectedIndices 只保留规范化后列表中的这些下标（null 表示全部）
     */
    fun build(
        format: ImportFormat,
        bytes: ByteArray,
        selectedIndices: Set<Int>? = null
    ): ImportOutcome {
        val parsed = parse(format, bytes)
        val issues = ArrayList(parsed.issues)
        val all = ArrayList<com.lytree.qbank.data.model.Question>()

        parsed.questions.forEach { raw ->
            Normalizer.toQuestion(raw, issues)?.let { all.add(it) }
        }
        val chosen = if (selectedIndices == null) all
        else all.filterIndexed { i, _ -> i in selectedIndices }

        return ImportOutcome(
            format = format.label,
            total = parsed.totalRows,
            imported = chosen.size,
            questions = chosen,
            issues = issues
        )
    }
}

/** 导入最终产物 */
data class ImportOutcome(
    val format: String,
    val total: Int,
    val imported: Int,
    val questions: List<com.lytree.qbank.data.model.Question>,
    val issues: List<ImportIssue>
) {
    val errorCount get() = issues.count { it.level == ImportIssue.Level.ERROR }
    val warnCount get() = issues.count { it.level == ImportIssue.Level.WARN }
}
