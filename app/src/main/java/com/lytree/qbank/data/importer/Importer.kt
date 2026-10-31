package com.lytree.qbank.data.importer

import com.lytree.qbank.data.model.Option
import com.lytree.qbank.data.model.Question
import java.io.ByteArrayInputStream
import java.io.File

/** 支持的题库文件格式：统一只认 Excel；图片等媒体资源放 zip 包里随 Excel 一起导入 */
enum class ImportFormat(val ext: String, val label: String, val mime: String) {
    XLSX("xlsx", "Excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),

    /** 题库包：zip 里放 题库.xlsx + images/ 等媒体文件 */
    ZIP("zip", "题库包", "application/zip");

    companion object {
        fun of(name: String): ImportFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.ext == ext }
        }
    }
}

/**
 * 一份待建卷的解析结果。
 *
 * 一个文件可能拆出多份（见 [PaperSplitter]），所以「导入」的单位从「文件」变成了「卷」：
 * 每卷有自己的卷名、题目、去重计数与校验问题。
 */
data class PaperDraft(
    val name: String,
    val questions: List<Question>,
    /** 这一卷对应文件里的数据行数（含被跳过的） */
    val totalRows: Int,
    /** 本卷内判定为重复而跳过的题数 */
    val duplicateSkipped: Int,
    val issues: List<ImportIssue>,
    /** 卷名从哪来的：`「试卷」列` / `工作表「Sheet2」`；空串表示整文件一卷 */
    val origin: String = ""
)

/** 整份文件的解析结果：一份或多份试卷 + 文件级问题 */
data class MultiPaperOutcome(
    val format: String,
    val papers: List<PaperDraft>,
    /** 文件级问题：解析失败、多工作表说明、包内择优等，不属于某一卷 */
    val issues: List<ImportIssue>
) {
    val questionCount get() = papers.sumOf { it.questions.size }
    val totalRows get() = papers.sumOf { it.totalRows }
    val duplicateSkipped get() = papers.sumOf { it.duplicateSkipped }
    val errorCount get() = issues.count { it.level == ImportIssue.Level.ERROR }
    val warnCount get() = issues.count { it.level == ImportIssue.Level.WARN }
    val multi get() = papers.size > 1
}

/**
 * 导入调度：格式探测 + 分发 + 拆卷 + 规范化 + 校验。
 *
 * 三条贯穿全程的原则（借自 Anki 导入器的实用做法）：
 * 1. **按结果取舍，不按文件名取舍**：题库包里有几份 Excel 就都试一遍，谁真能解析出题用谁；
 * 2. **先报数再入库**：去重、跳过、警告都在预览页一次说清，别让用户点完「导入」才发现少了一半；
 * 3. **宽容解析**：表头别名、图片路径写法、答案写法都尽量认，认不出来才报错。
 *
 * 关于「一个文件几份卷」：拆卷规则见 [PaperSplitter]（「试卷」列 / 多工作表），
 * 拆不开就老老实实当一卷——把两套题并成一卷，比少拆一卷严重得多。
 */
object Importer {

    /** 根据文件名与内容探测格式：zip 壳里认得出题库包就是 ZIP，其余一律按 Excel 走解析（失败时报「只支持 Excel」） */
    fun detect(fileName: String, bytes: ByteArray): ImportFormat {
        val head = bytes.take(4)
        // PK\x03\x04 → zip：里面是题库包（有 Excel 数据文件）还是普通 Excel
        if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        ) {
            return if (QbankPackage.looksLikePackage(bytes)) ImportFormat.ZIP else ImportFormat.XLSX
        }
        return ImportFormat.XLSX
    }

    /**
     * 完整导入流程：解析 → 拆卷 → 规范化 → 去重 → 校验。
     *
     * @param fileName 原始文件名：整文件只有一卷时，卷名默认用它（去掉扩展名）
     * @param pkgDir 题库包解压到哪里（普通格式不需要）
     */
    fun buildPapers(
        format: ImportFormat,
        bytes: ByteArray,
        fileName: String = "",
        pkgDir: File? = null
    ): MultiPaperOutcome {
        val baseName = fileName.substringBeforeLast('.').trim()
        return when (format) {
            ImportFormat.XLSX -> {
                val issues = ArrayList<ImportIssue>()
                val papers = try {
                    papersOfSheets(bytes, baseName, null, issues)
                } catch (e: Exception) {
                    issues.add(
                        ImportIssue(
                            0, ImportIssue.Level.ERROR,
                            "只支持 Excel（.xlsx）文件：${e.message ?: e.javaClass.simpleName}"
                        )
                    )
                    emptyList()
                }
                val out = MultiPaperOutcome("XLSX", papers, issues)
                out.withMultiNote()
            }
            ImportFormat.ZIP -> buildPackage(bytes, baseName, pkgDir)
        }
    }

    /**
     * 单卷便捷入口（老调用方与单元测试用）：多卷文件会按卷顺序摊平返回。
     *
     * 新代码请直接走 [buildPapers]，否则多卷文件里只有第一行的卷名能用上。
     *
     * @param selectedIndices 只保留摊平后列表中的这些下标（null 表示全部）；多卷时不建议用
     */
    fun build(
        format: ImportFormat,
        bytes: ByteArray,
        selectedIndices: Set<Int>? = null,
        fileName: String = "",
        pkgDir: File? = null
    ): ImportOutcome {
        val multi = buildPapers(format, bytes, fileName, pkgDir)
        val all = multi.papers.flatMap { it.questions }
        val chosen = if (selectedIndices == null) all else all.filterIndexed { i, _ -> i in selectedIndices }
        return ImportOutcome(
            format = multi.format,
            total = multi.totalRows,
            imported = chosen.size,
            questions = chosen,
            issues = multi.issues + multi.papers.flatMap { it.issues },
            suggestedName = multi.papers.firstOrNull()?.name,
            duplicateSkipped = multi.duplicateSkipped,
            papers = multi.papers
        )
    }

    /** 拆出多卷时补一条文件级说明：让「为什么变成 N 份试卷」有据可查 */
    private fun MultiPaperOutcome.withMultiNote(): MultiPaperOutcome {
        if (papers.size <= 1) return this
        val how = papers.map { it.origin }.filter { it.isNotEmpty() }.distinct().joinToString("、")
        val note = ImportIssue(
            0, ImportIssue.Level.INFO,
            "检测到 ${papers.size} 份试卷" + (if (how.isNotEmpty()) "（按$how 拆分）" else "") +
                "，共 $questionCount 题；导入后会各建一份试卷"
        )
        return copy(issues = issues + note)
    }

    /** 一张工作表解析出来的东西 */
    private data class SheetData(val name: String, val result: ImportResult)

    /**
     * 解析 Excel 的所有工作表 → 拆卷 → 逐卷规范化。
     *
     * 工作表级的错误处理有一条分寸：**只要有任意一张表出了题，其余表的错误就降级成提示**。
     * 「说明」「答题卡」这类表在很多题库里都存在，它们解析不出题是正常的，
     * 报成红色错误只会让用户以为自己导错了。
     */
    private fun papersOfSheets(
        bytes: ByteArray,
        fallbackName: String,
        assets: Map<String, String>?,
        fileIssues: MutableList<ImportIssue>
    ): List<PaperDraft> {
        val sheets = XlsxReader.readAll(ByteArrayInputStream(bytes))
        if (sheets.isEmpty()) {
            fileIssues.add(ImportIssue(0, ImportIssue.Level.ERROR, "Excel 中没有可读数据"))
            return emptyList()
        }

        val parsed = sheets.map { s ->
            val sheetIssues = ArrayList<ImportIssue>()
            SheetData(s.name, CsvImporter.parseRows(s.rows, sheetIssues, "XLSX"))
        }

        val withQuestions = parsed.filter { it.result.questions.isNotEmpty() }
        val multiSheet = parsed.size > 1

        if (withQuestions.isEmpty()) {
            // 一张表都没出题：每张表给一条硬错误，否则用户只看到「0 题」不知道哪儿错了
            parsed.forEach { s ->
                val first = s.result.issues.firstOrNull { it.level == ImportIssue.Level.ERROR }
                    ?: s.result.issues.firstOrNull()
                fileIssues.add(
                    ImportIssue(
                        0, ImportIssue.Level.ERROR,
                        (if (multiSheet) "工作表「${s.name}」" else "") +
                            (first?.message ?: "没有可导入的题目")
                    )
                )
            }
            return emptyList()
        }

        parsed.filter { it.result.questions.isEmpty() }.forEach { s ->
            val first = s.result.issues.firstOrNull { it.level == ImportIssue.Level.ERROR }
            fileIssues.add(
                ImportIssue(
                    0, ImportIssue.Level.INFO,
                    "工作表「${s.name}」没有可导入的题目，已忽略" +
                        (first?.let { "（${it.message}）" } ?: "")
                )
            )
        }

        // 出题的表：表头推断、整行跳过之类的提示按表归属上报（多表时标上表名，否则对不上号）
        withQuestions.forEach { s ->
            s.result.issues.forEach { i ->
                fileIssues.add(
                    if (!multiSheet) i else i.copy(message = "「${s.name}」${i.message}")
                )
            }
        }

        return papersOf(withQuestions, fallbackName, assets, fileIssues)
    }

    /**
     * 把各表的行拆成卷，再逐卷规范化、去重、体检。
     *
     * 去重按**卷内**做：同一道题出现在两份卷子里是正常的（真题会重复用），不能跨卷吞掉。
     */
    private fun papersOf(
        sheets: List<SheetData>,
        fallbackName: String,
        assets: Map<String, String>?,
        fileIssues: MutableList<ImportIssue>
    ): List<PaperDraft> {
        val blocks = PaperSplitter.split(
            sheets.map { PaperSplitter.SheetQuestions(it.name, it.result.questions) },
            fallbackName
        )

        val drafts = ArrayList<PaperDraft>(blocks.size)
        blocks.forEach { block ->
            val issues = ArrayList<ImportIssue>()
            val questions = ArrayList<Question>(block.raws.size)
            block.raws.forEach { raw ->
                Normalizer.toQuestion(raw, issues)?.let { q ->
                    questions.add(if (assets.isNullOrEmpty()) q else rewriteAssets(q, assets))
                }
            }
            val (unique, duplicated) = dedup(questions)
            if (duplicated > 0) {
                issues.add(
                    ImportIssue(0, ImportIssue.Level.INFO, "$duplicated 题内容完全重复，已自动跳过")
                )
            }
            // 卷面结构体检：按卷各查一遍（三级结构由 subject/material 的连续重复派生）
            issues.addAll(StructureCheck.check(unique))
            drafts.add(
                PaperDraft(
                    name = block.name,
                    questions = unique,
                    totalRows = block.raws.size,
                    duplicateSkipped = duplicated,
                    issues = issues,
                    origin = block.origin
                )
            )
        }

        // 一题都没有的卷不建：把原因留成提示，而不是建一份空卷让用户自己发现
        val usable = drafts.filter { it.questions.isNotEmpty() }
        drafts.filter { it.questions.isEmpty() }.forEach { d ->
            val first = d.issues.firstOrNull { it.level == ImportIssue.Level.ERROR }
            fileIssues.add(
                ImportIssue(
                    0,
                    if (usable.isEmpty()) ImportIssue.Level.ERROR else ImportIssue.Level.INFO,
                    "「${d.name}」没有可导入的题目，已跳过" + (first?.let { "：${it.message}" } ?: "")
                )
            )
        }
        return usable
    }

    /**
     * 题库包导入：解压 → **逐个候选试解析** → 选真正能出题的那份 → 图片路径换成本机绝对路径。
     *
     * 换路径在入库前做完，题目存的就是最终地址，之后翻题不必再算包内位置。
     */
    private fun buildPackage(
        bytes: ByteArray,
        fallbackName: String,
        pkgDir: File?
    ): MultiPaperOutcome {
        val issues = ArrayList<ImportIssue>()
        if (pkgDir == null) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "题库包缺少解压目录，无法导入"))
            return MultiPaperOutcome("题库包", emptyList(), issues)
        }
        val pack = try {
            QbankPackage.open(bytes, pkgDir)
        } catch (e: Exception) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "题库包解压失败：${e.message}"))
            return MultiPaperOutcome("题库包", emptyList(), issues)
        }

        // 包名优先当卷名：包里那份 Excel 只有一卷时，用户认的是压缩包的名字，不是「Sheet1」
        val name = pack.name.takeIf { it.isNotBlank() } ?: fallbackName
        val attempts = pack.candidates.map { tryParse(it, pack.assets, name) }
        // 并列时保留靠前的（命名与扩展名优先级更高）：只在严格更多时才换
        var bestIndex = 0
        attempts.forEachIndexed { i, a ->
            if (a.count > attempts[bestIndex].count) bestIndex = i
        }
        val best = attempts[bestIndex]

        issues.addAll(best.issues)
        if (pack.candidates.size > 1) issues.addAll(reportChoice(attempts, bestIndex))

        val out = MultiPaperOutcome("题库包 · ${best.format.label}", best.papers, issues)
        return out.withMultiNote()
    }

    /** 单个候选的试解析结果 */
    private data class Attempt(
        val candidate: QbankPackage.Candidate,
        val format: ImportFormat,
        val papers: List<PaperDraft>,
        val issues: List<ImportIssue>
    ) {
        val count: Int get() = papers.sumOf { it.questions.size }
    }

    /**
     * 试解析一个候选文件：解析 + 拆卷 + 规范化 + 换图片路径。
     *
     * 失败不抛异常——包里有几份数据文件，一份坏不该拖垮整个导入，交给调用方择优。
     */
    private fun tryParse(
        candidate: QbankPackage.Candidate,
        assets: Map<String, String>,
        fallbackName: String
    ): Attempt {
        val format = detect(candidate.path, candidate.bytes)
        if (format == ImportFormat.ZIP) {
            return Attempt(
                candidate, format, emptyList(),
                listOf(ImportIssue(0, ImportIssue.Level.ERROR, "题库包不能嵌套压缩包"))
            )
        }
        return try {
            val issues = ArrayList<ImportIssue>()
            val papers = papersOfSheets(candidate.bytes, fallbackName, assets, issues)
            Attempt(candidate, format, papers, issues)
        } catch (e: Exception) {
            Attempt(
                candidate, format, emptyList(),
                listOf(ImportIssue(0, ImportIssue.Level.ERROR, "解析失败：${e.message}"))
            )
        }
    }

    /**
     * 多候选时的说明：选了哪份、为什么，其余各能出多少题。
     *
     * 这件事必须让用户看见——包里有几份数据文件本身就是数据质量问题的信号。
     */
    private fun reportChoice(attempts: List<Attempt>, bestIndex: Int): List<ImportIssue> {
        val others = attempts.filterIndexed { i, _ -> i != bestIndex }
        val best = attempts[bestIndex]
        if (best.count == 0) {
            // 全军覆没：每份都给出第一条硬错误，否则用户只知道「0 题」不知道哪儿错了
            return attempts.map { a ->
                val first = a.issues.firstOrNull { it.level == ImportIssue.Level.ERROR }
                ImportIssue(
                    0, ImportIssue.Level.ERROR,
                    "「${a.candidate.path}」没能解析出题目：${first?.message ?: "内容为空或格式不支持"}"
                )
            }
        }
        val usable = others.filter { it.count > 0 }
        val level = if (usable.isEmpty()) ImportIssue.Level.INFO else ImportIssue.Level.WARN
        val tail = others.joinToString("") { a ->
            "；已忽略「${a.candidate.path}」（${a.count} 题）"
        }
        return listOf(
            ImportIssue(
                0, level,
                "包内有 ${attempts.size} 个数据文件，已选用「${best.candidate.path}」（${best.count} 题）" + tail
            )
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

/** 导入最终产物（单卷语义的汇总；多卷细节在 [papers] 里） */
data class ImportOutcome(
    val format: String,
    val total: Int,
    val imported: Int,
    val questions: List<Question>,
    val issues: List<ImportIssue>,
    // 题库包自带的题库名（普通格式为空），导入时作为默认试卷名
    val suggestedName: String? = null,
    // 批次内判定为重复而跳过的题数
    val duplicateSkipped: Int = 0,
    // 拆出来的各卷（单卷文件里只有一个元素）
    val papers: List<PaperDraft> = emptyList()
) {
    val errorCount get() = issues.count { it.level == ImportIssue.Level.ERROR }
    val warnCount get() = issues.count { it.level == ImportIssue.Level.WARN }
}
