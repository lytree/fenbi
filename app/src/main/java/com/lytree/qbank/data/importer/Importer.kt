package com.lytree.qbank.data.importer

import com.lytree.qbank.data.model.Option
import com.lytree.qbank.data.model.Question
import java.io.ByteArrayInputStream
import java.io.File

/** 支持的题库文件格式 */
enum class ImportFormat(val ext: String, val label: String, val mime: String) {
    JSON("json", "JSON", "application/json"),
    CSV("csv", "CSV", "text/csv"),
    XLSX("xlsx", "Excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    TXT("txt", "TXT", "text/plain"),

    /** 题库包：zip 里放主数据 + images 素材目录 */
    ZIP("zip", "题库包", "application/zip");

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
        // PK\x03\x04 → zip：里面是题库包（有 qbank.json）还是 Excel（有 xl/workbook.xml）
        if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        ) {
            return if (QbankPackage.looksLikePackage(bytes)) ImportFormat.ZIP else ImportFormat.XLSX
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
            ImportFormat.ZIP -> {
                // 题库包必须先解压（见 build），走到这里说明调用方漏传了解压目录
                issues.add(
                    ImportIssue(0, ImportIssue.Level.ERROR, "题库包需要先解压，请重新选择文件导入")
                )
                ImportResult(emptyList(), issues, "ZIP", 0)
            }
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
        selectedIndices: Set<Int>? = null,
        // 题库包解压到哪里（普通格式不需要）
        pkgDir: File? = null
    ): ImportOutcome {
        if (format == ImportFormat.ZIP) return buildPackage(bytes, selectedIndices, pkgDir)

        val parsed = parse(format, bytes)
        val issues = ArrayList(parsed.issues)
        val all = ArrayList<Question>()

        parsed.questions.forEach { raw ->
            Normalizer.toQuestion(raw, issues)?.let { all.add(it) }
        }
        val chosen = if (selectedIndices == null) all
        else all.filterIndexed { i, _ -> i in selectedIndices }

        // 卷面结构体检：只体检真正会入库的题（三级结构由 subject/material 的连续重复派生）
        issues.addAll(StructureCheck.check(chosen))

        return ImportOutcome(
            format = format.label,
            total = parsed.totalRows,
            imported = chosen.size,
            questions = chosen,
            issues = issues
        )
    }

    /**
     * 题库包导入：解压 → 解析主数据 → 材料里的相对图片路径换成本机绝对路径。
     *
     * 换路径在入库前做完，题目存的就是最终地址，之后翻题不必再算包内位置。
     */
    private fun buildPackage(
        bytes: ByteArray,
        selectedIndices: Set<Int>?,
        pkgDir: File?
    ): ImportOutcome {
        val issues = ArrayList<ImportIssue>()
        if (pkgDir == null) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "题库包缺少解压目录，无法导入"))
            return ImportOutcome("题库包", 0, 0, emptyList(), issues)
        }
        val pack = try {
            QbankPackage.open(bytes, pkgDir)
        } catch (e: Exception) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "题库包解压失败：${e.message}"))
            return ImportOutcome("题库包", 0, 0, emptyList(), issues)
        }

        val inner = ImportFormat.of(pack.mainPath) ?: ImportFormat.JSON
        if (inner == ImportFormat.ZIP) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "题库包不能嵌套压缩包"))
            return ImportOutcome("题库包", 0, 0, emptyList(), issues)
        }
        val parsed = parse(inner, pack.mainBytes)
        issues.addAll(parsed.issues)

        val all = ArrayList<Question>()
        parsed.questions.forEach { raw ->
            Normalizer.toQuestion(raw, issues)?.let { all.add(rewriteAssets(it, pack.assets)) }
        }
        val chosen = if (selectedIndices == null) all
        else all.filterIndexed { i, _ -> i in selectedIndices }

        issues.addAll(StructureCheck.check(chosen))

        return ImportOutcome(
            format = "题库包 · " + inner.label,
            total = parsed.totalRows,
            imported = chosen.size,
            questions = chosen,
            issues = issues,
            suggestedName = pack.name.takeIf { it.isNotBlank() }
        )
    }

    /** 包内相对路径 → 解压后的绝对路径；长路径先替换，避免前缀互相吃掉 */
    private fun rewriteAssets(q: Question, assets: Map<String, String>): Question {
        if (assets.isEmpty()) return q
        val pairs = assets.entries.sortedByDescending { it.key.length }

        fun fix(text: String): String {
            if (text.isEmpty()) return text
            var out = text
            pairs.forEach { (rel, abs) ->
                if (out.contains(rel)) out = out.replace("./" + rel, abs).replace(rel, abs)
            }
            return out
        }

        return q.copy(
            stem = fix(q.stem),
            material = fix(q.material),
            analysis = fix(q.analysis),
            options = q.options.map { Option(it.key, fix(it.text)) }
        )
    }
}

/** 导入最终产物 */
data class ImportOutcome(
    val format: String,
    val total: Int,
    val imported: Int,
    val questions: List<Question>,
    val issues: List<ImportIssue>,
    // 题库包自带的题库名（普通格式为空），导入时作为默认试卷名
    val suggestedName: String? = null
) {
    val errorCount get() = issues.count { it.level == ImportIssue.Level.ERROR }
    val warnCount get() = issues.count { it.level == ImportIssue.Level.WARN }
}
