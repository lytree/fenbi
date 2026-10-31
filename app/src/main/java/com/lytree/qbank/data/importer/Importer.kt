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

    /** 制表符分隔：Anki 导出的纯文本走这里（`.tsv` 或带 `#separator:` 头的 `.txt`） */
    TSV("tsv", "TSV / Anki", "text/tab-separated-values"),

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
 *
 * 三条贯穿全程的原则（借自 Anki 导入器的实用做法）：
 * 1. **按结果取舍，不按文件名取舍**：题库包里有几份数据文件就都试一遍，谁真能解析出题用谁；
 * 2. **先报数再入库**：去重、跳过、警告都在预览页一次说清，别让用户点完「导入」才发现少了一半；
 * 3. **宽容解析**：编码、分隔符、表头别名、图片路径写法、答案写法都尽量认，认不出来才报错。
 */
object Importer {

    /** 根据文件名与内容探测格式 */
    fun detect(fileName: String, bytes: ByteArray): ImportFormat {
        val byExt = ImportFormat.of(fileName)

        val head = bytes.take(4)
        // PK\x03\x04 → zip：里面是题库包（有数据文件）还是 Excel（有 xl/workbook.xml）
        if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        ) {
            return if (QbankPackage.looksLikePackage(bytes)) ImportFormat.ZIP else ImportFormat.XLSX
        }
        val text = headText(bytes).trimStart('\uFEFF', ' ', '\t', '\n', '\r')
        // Anki 导出可能是 .tsv 也可能是老版本的 .txt，靠文件头的 # 指令认
        if (AnkiImporter.looksLikeAnki(text)) return ImportFormat.TSV
        // json / csv / xlsx / tsv 这类明确扩展名直接信；`.txt` 最含糊，让内容说了算
        if (byExt != null && byExt != ImportFormat.TXT) return byExt
        if (text.startsWith("{") || text.startsWith("[")) return ImportFormat.JSON
        // 出现列分隔且有中文表头特征 → CSV
        if (text.contains(',') && Regex("(?m)^.*(题干|题目|答案).*$").containsMatchIn(text)) {
            return ImportFormat.CSV
        }
        return ImportFormat.TXT
    }

    /**
     * 取文件头做格式嗅探。
     *
     * 只读前 64 KB —— 格式特征都在开头，没必要为探测把整份文件解一遍。
     * 截断可能切在多字节字符中间，所以先严格按 UTF-8 试，失败就回退最多 3 个字节再试，
     * 全不行才按 GBK 解（Windows 导出的老文件多是 GBK）。
     */
    private fun headText(bytes: ByteArray): String {
        val n = minOf(bytes.size, 64 * 1024)
        if (n == 0) return ""
        val head = bytes.copyOf(n)
        for (cut in 0..3) {
            val len = n - cut
            if (len <= 0) break
            try {
                val cs = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                return cs.decode(java.nio.ByteBuffer.wrap(head, 0, len)).toString()
            } catch (_: Exception) {
                // 可能是末尾截断，缩一个字节再试
            }
        }
        return runCatching { String(head, java.nio.charset.Charset.forName("GBK")) }
            .getOrDefault(String(head, Charsets.UTF_8))
    }

    fun parse(format: ImportFormat, bytes: ByteArray): ImportResult {
        val issues = ArrayList<ImportIssue>()
        val raw = when (format) {
            ImportFormat.JSON -> JsonImporter.parse(ByteArrayInputStream(bytes), issues)
            ImportFormat.CSV, ImportFormat.TSV -> CsvImporter.parse(ByteArrayInputStream(bytes), issues)
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
     * 完整导入流程：解析 → 规范化 → 去重 → 校验。
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
        return finish(format.label, parsed.totalRows, all, issues, selectedIndices, null)
    }

    /**
     * 题库包导入：解压 → **逐个候选试解析** → 选真正能出题的那份 → 图片路径换成本机绝对路径。
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

        val attempts = pack.candidates.map { tryParse(it, pack.assets) }
        // 并列时保留靠前的（命名与扩展名优先级更高）：只在严格更多时才换
        var bestIndex = 0
        attempts.forEachIndexed { i, a ->
            if (a.questions.size > attempts[bestIndex].questions.size) bestIndex = i
        }
        val best = attempts[bestIndex]

        issues.addAll(best.issues)
        if (pack.candidates.size > 1) issues.addAll(reportChoice(attempts, bestIndex))

        return finish(
            formatLabel = "题库包 · " + best.format.label,
            totalRows = best.total,
            questions = best.questions,
            issues = issues,
            selectedIndices = selectedIndices,
            suggestedName = pack.name.takeIf { it.isNotBlank() }
        )    }

    /** 单个候选的试解析结果 */
    private data class Attempt(
        val candidate: QbankPackage.Candidate,
        val format: ImportFormat,
        val total: Int,
        val questions: List<Question>,
        val issues: List<ImportIssue>
    )

    /**
     * 试解析一个候选文件：解析 + 规范化 + 换图片路径。
     *
     * 失败不抛异常——包里有几份数据文件，一份坏不该拖垮整个导入，交给调用方择优。
     */
    private fun tryParse(
        candidate: QbankPackage.Candidate,
        assets: Map<String, String>
    ): Attempt {
        val format = detect(candidate.path, candidate.bytes)
        if (format == ImportFormat.ZIP) {
            return Attempt(
                candidate, format, 0, emptyList(),
                listOf(ImportIssue(0, ImportIssue.Level.ERROR, "题库包不能嵌套压缩包"))
            )
        }
        val parsed = try {
            parse(format, candidate.bytes)
        } catch (e: Exception) {
            return Attempt(
                candidate, format, 0, emptyList(),
                listOf(ImportIssue(0, ImportIssue.Level.ERROR, "解析失败：${e.message}"))
            )
        }
        val issues = ArrayList(parsed.issues)
        val questions = ArrayList<Question>()
        parsed.questions.forEach { raw ->
            Normalizer.toQuestion(raw, issues)?.let { questions.add(rewriteAssets(it, assets)) }
        }
        return Attempt(candidate, format, parsed.totalRows, questions, issues)
    }

    /**
     * 多候选时的说明：选了哪份、为什么，其余各能出多少题。
     *
     * 这件事必须让用户看见——包里有几份数据文件本身就是数据质量问题的信号。
     */
    private fun reportChoice(attempts: List<Attempt>, bestIndex: Int): List<ImportIssue> {
        val others = attempts.filterIndexed { i, _ -> i != bestIndex }
        val best = attempts[bestIndex]
        if (best.questions.isEmpty()) {
            // 全军覆没：每份都给出第一条硬错误，否则用户只知道「0 题」不知道哪儿错了
            return attempts.map { a ->
                val first = a.issues.firstOrNull { it.level == ImportIssue.Level.ERROR }
                ImportIssue(
                    0, ImportIssue.Level.ERROR,
                    "「${a.candidate.path}」没能解析出题目：${first?.message ?: "内容为空或格式不支持"}"
                )
            }
        }
        val usable = others.filter { it.questions.isNotEmpty() }
        val level = if (usable.isEmpty()) ImportIssue.Level.INFO else ImportIssue.Level.WARN
        val tail = others.joinToString("") { a ->
            "；已忽略「${a.candidate.path}」（${a.questions.size} 题）"
        }
        return listOf(
            ImportIssue(
                0, level,
                "包内有 ${attempts.size} 个数据文件，已选用「${best.candidate.path}」（${best.questions.size} 题）" + tail
            )
        )
    }

    /**
     * 统一收尾：批次内去重 → 按勾选过滤 → 卷面体检。
     *
     * 去重是 Anki 导入器最实用的一条经验：同一份题重复导入、或导出时把同一张卡片复制了几份，
     * 都不该变成卷子里的重复题目。用户会先在预览页看到「重复跳过 N」，而不是入库后自己发现。
     */
    private fun finish(
        formatLabel: String,
        totalRows: Int,
        questions: List<Question>,
        issues: MutableList<ImportIssue>,
        selectedIndices: Set<Int>?,
        suggestedName: String?
    ): ImportOutcome {
        val (unique, duplicated) = dedup(questions)
        if (duplicated > 0) {
            issues.add(
                ImportIssue(0, ImportIssue.Level.INFO, "$duplicated 题内容完全重复，已自动跳过")
            )
        }

        val chosen = if (selectedIndices == null) unique
        else unique.filterIndexed { i, _ -> i in selectedIndices }

        // 卷面结构体检：只体检真正会入库的题（三级结构由 subject/material 的连续重复派生）
        issues.addAll(StructureCheck.check(chosen))

        return ImportOutcome(
            format = formatLabel,
            total = totalRows,
            imported = chosen.size,
            questions = chosen,
            issues = issues,
            suggestedName = suggestedName,
            duplicateSkipped = duplicated
        )
    }

    /**
     * 按「题干 + 材料 + 选项」的归一化指纹去重，保留第一次出现的。
     *
     * 空白差异不算差异（全角空格、换行、多余空格在题库里非常常见）。
     */
    private fun dedup(list: List<Question>): Pair<List<Question>, Int> {
        if (list.size < 2) return list to 0
        val seen = HashSet<String>(list.size)
        val out = ArrayList<Question>(list.size)
        var dup = 0
        list.forEach { q ->
            if (seen.add(fingerprint(q))) out.add(q) else dup++
        }
        return out to dup
    }

    private fun fingerprint(q: Question): String {
        fun norm(s: String) = s.filterNot { it.isWhitespace() }
        return buildString {
            append(norm(q.stem))
            append('\u0001')
            append(norm(q.material))
            append('\u0001')
            q.options.forEach { append(it.key).append('=').append(norm(it.text)).append('\u0002') }
        }
    }

    /**
     * 包内相对路径 → 解压后的绝对路径；长路径先替换，避免前缀互相吃掉。
     *
     * 除了整段相对路径，还兼容**只写文件名**的引用（Anki 卡片里的 `src="s01_q01.png"` 就是这样）：
     * 按 basename 兜底匹配，大小写不敏感，同名文件有两份以上时不猜，保持原样。
     */
    private fun rewriteAssets(q: Question, assets: Map<String, String>): Question {
        if (assets.isEmpty()) return q
        val pairs = assets.entries.sortedByDescending { it.key.length }

        // basename → 绝对路径；同一个文件名出现多次说明有歧义，直接不参与兜底
        val byBase = HashMap<String, String>()
        val ambiguous = HashSet<String>()
        assets.forEach { (rel, abs) ->
            val base = rel.substringAfterLast('/')
            if (byBase.put(base, abs) != null) ambiguous.add(base)
        }
        ambiguous.forEach { byBase.remove(it) }
        // 小写索引：压缩包里的文件名大小写和卡片里写的经常对不上
        val byBaseLower = HashMap<String, String>()
        val lowerAmbiguous = HashSet<String>()
        byBase.forEach { (base, abs) ->
            if (byBaseLower.put(base.lowercase(), abs) != null) lowerAmbiguous.add(base.lowercase())
        }
        lowerAmbiguous.forEach { byBaseLower.remove(it) }

        fun fix(text: String): String {
            if (text.isEmpty()) return text
            var out = text
            pairs.forEach { (rel, abs) ->
                if (out.contains(rel)) out = out.replace("./" + rel, abs).replace(rel, abs)
            }
            if (byBase.isEmpty() || !out.contains('.')) return out
            // 只认 markdown / html 里「括号或引号里的一串文件名」，且路径里不带分隔符
            return BARE_FILE_REF.replace(out) { m ->
                val raw = m.groupValues[1]
                // 中文文件名经 Anki / 网页导出常被百分号转义，先还原再找
                val decoded = decodePercent(raw)
                byBase[decoded] ?: byBase[raw] ?: byBaseLower[decoded.lowercase()] ?: m.value
            }
        }

        return q.copy(
            stem = fix(q.stem),
            material = fix(q.material),
            analysis = fix(q.analysis),
            options = q.options.map { Option(it.key, fix(it.text)) }
        )
    }

    /** 括号 / 引号里只有文件名的引用：[("'](xxx.png)[)"'] */
    private val BARE_FILE_REF = Regex("""(?<=[("'])([^("'/\s]+\.[A-Za-z0-9]{2,5})(?=[)"'])""")

    /**
     * 只解 `%XX`，不用 URLDecoder：后者会把文件名里的 `+` 变成空格，反而毁掉正常文件名。
     */
    private fun decodePercent(s: String): String {
        if (!s.contains('%')) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 3 <= s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            val b = c.toString().toByteArray(Charsets.UTF_8)
            out.write(b, 0, b.size)
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
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
    val suggestedName: String? = null,
    // 批次内判定为重复而跳过的题数
    val duplicateSkipped: Int = 0
) {
    val errorCount get() = issues.count { it.level == ImportIssue.Level.ERROR }
    val warnCount get() = issues.count { it.level == ImportIssue.Level.WARN }
}
