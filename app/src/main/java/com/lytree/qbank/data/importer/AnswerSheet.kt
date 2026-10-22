package com.lytree.qbank.data.importer

import com.lytree.qbank.data.model.Question
import com.lytree.qbank.engine.Judge
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * 答案补充。
 *
 * 真实场景是「试卷先到手、答案后到手」：导入题目时往往整卷没有答案，
 * 做完之后再拿到一份答案文件（常见是一张「题号 + 答案」的表，或原题库的答案版）。
 * 这里把答案文件解析成 [AnswerEntry] 列表，再和卷内题目做匹配。
 */
data class AnswerEntry(
    /** 题号（1 起）。答案表里通常有，纯题库导出时可能没有 */
    val no: Int? = null,
    /** 题干。用于按题干匹配，比题号更抗「题序被调整」 */
    val stem: String = "",
    val answer: String = "",
    val analysis: String = ""
)

/** 解析后的答案文件 */
data class AnswerSheet(
    val entries: List<AnswerEntry>,
    val issues: List<ImportIssue>,
    val format: String
) {
    val errorCount get() = issues.count { it.level == ImportIssue.Level.ERROR }
    val warnCount get() = issues.count { it.level == ImportIssue.Level.WARN }
}

/**
 * 答案文件解析器。
 *
 * 支持四种格式：
 * - **CSV / Excel**：`题号,答案` 两列；也支持 `题号,答案,解析` 与带「题干」列的完整答案表。
 *   表头能识别就按表头走，识别不到就按位置约定（第一列题号、第二列答案）。
 * - **JSON**：`[{"no":1,"answer":"A"}]`、`{"1":"A","2":"B"}`、带题干的对象数组都认。
 * - **TXT**：`1.A` / `1、A` / `第1题 A` 逐行；一行多条（`1.A 2.B`）也认；
 *   如果整份是带 `答案：` 前缀的题库文本，则复用题库解析器。
 */
object AnswerSheetImporter {

    fun parse(fileName: String, bytes: ByteArray): AnswerSheet {
        val format = Importer.detect(fileName, bytes)
        val issues = ArrayList<ImportIssue>()
        val entries = when (format) {
            ImportFormat.JSON -> parseJson(bytes, issues)
            ImportFormat.CSV -> {
                val text = CsvImporter.decode(bytes)
                parseTable(CsvImporter.parseTable(text, CsvImporter.detectDelimiter(text)), issues)
            }
            ImportFormat.XLSX -> try {
                parseTable(XlsxReader.read(ByteArrayInputStream(bytes)).rows, issues)
            } catch (e: Exception) {
                issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "Excel 解析失败：${e.message}"))
                emptyList()
            }
            ImportFormat.TXT -> parseTxt(bytes, issues)
            ImportFormat.ZIP -> {
                issues.add(
                    ImportIssue(0, ImportIssue.Level.ERROR, "答案文件不支持压缩包，请单独选择 txt / csv / xlsx")
                )
                emptyList()
            }
        }
        if (entries.isEmpty()) {
            issues.add(
                ImportIssue(0, ImportIssue.Level.ERROR, "没有从文件里解析出任何答案，请检查格式")
            )
        }
        return AnswerSheet(entries, issues, format.label)
    }

    // ---------------- CSV / Excel ----------------

    private fun parseTable(rows: List<List<String>>, issues: MutableList<ImportIssue>): List<AnswerEntry> {
        if (rows.isEmpty()) return emptyList()

        var ansCol = -1
        var noCol = -1
        var stemCol = -1
        var anaCol = -1
        rows.first().forEachIndexed { i, cell ->
            when (FieldAliases.resolve(cell)) {
                Field.ANSWER -> if (ansCol < 0) ansCol = i
                Field.NO -> if (noCol < 0) noCol = i
                Field.STEM -> if (stemCol < 0) stemCol = i
                Field.ANALYSIS -> if (anaCol < 0) anaCol = i
                else -> Unit
            }
        }

        val hasAnswerHeader = ansCol >= 0
        if (!hasAnswerHeader) {
            issues.add(
                ImportIssue(
                    1, ImportIssue.Level.WARN,
                    "未识别到「答案」列，已按「第一列题号、第二列答案」解析"
                )
            )
        }

        val out = ArrayList<AnswerEntry>()
        for (r in (if (hasAnswerHeader) 1 else 0) until rows.size) {
            val row = rows[r]
            if (row.all { it.isBlank() }) continue
            val rowNo = r + 1

            val entry = if (hasAnswerHeader) {
                AnswerEntry(
                    no = row.getOrNull(noCol)?.trim()?.toIntOrNull(),
                    stem = row.getOrNull(stemCol)?.trim().orEmpty(),
                    answer = clean(row.getOrNull(ansCol)),
                    analysis = row.getOrNull(anaCol)?.trim().orEmpty()
                )
            } else if (row.size >= 2) {
                AnswerEntry(
                    no = row[0].trim().toIntOrNull(),
                    answer = clean(row[1]),
                    analysis = row.getOrNull(2)?.trim().orEmpty()
                )
            } else {
                AnswerEntry(answer = clean(row[0]))
            }

            if (entry.answer.isBlank()) {
                issues.add(ImportIssue(rowNo, ImportIssue.Level.WARN, "第 $rowNo 行答案为空，已跳过"))
                continue
            }
            out.add(entry)
        }
        return out
    }

    // ---------------- JSON ----------------

    private fun parseJson(bytes: ByteArray, issues: MutableList<ImportIssue>): List<AnswerEntry> {
        val text = CsvImporter.decode(bytes).trim()
        val root = try {
            JSONTokenerCompat.parse(text)
        } catch (e: Exception) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "JSON 解析失败：${e.message}"))
            return emptyList()
        }

        val out = ArrayList<AnswerEntry>()
        when (root) {
            is JSONArray -> for (i in 0 until root.length()) {
                (root.opt(i) as? JSONObject)?.let { out.add(entryFromJson(it)) }
            }

            is JSONObject -> {
                val arr = listOf("answers", "answerList", "data", "list", "items", "答案")
                    .firstNotNullOfOrNull { k -> root.opt(k) as? JSONArray }
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        (arr.opt(i) as? JSONObject)?.let { out.add(entryFromJson(it)) }
                    }
                } else {
                    // {"1":"A","2":"B"} —— key 就是题号
                    var mapped = 0
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = root.opt(k)
                        val no = k.trim().toIntOrNull()
                        if (no != null && (v is String || v is Number)) {
                            out.add(AnswerEntry(no = no, answer = clean(v.toString())))
                            mapped++
                        }
                    }
                    if (mapped == 0) out.add(entryFromJson(root))
                }
            }
        }
        return out.filter { it.answer.isNotBlank() }
    }

    private fun entryFromJson(o: JSONObject): AnswerEntry {
        var no: Int? = null
        var stem = ""
        var answer = ""
        var analysis = ""
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.opt(k)
            if (v is JSONArray || v is JSONObject || v == null) continue
            val s = v.toString().trim()
            when (FieldAliases.resolve(k)) {
                Field.NO -> if (no == null) no = s.toIntOrNull()
                Field.STEM -> if (stem.isEmpty()) stem = s
                Field.ANSWER -> if (answer.isEmpty()) answer = clean(s)
                Field.ANALYSIS -> if (analysis.isEmpty()) analysis = s
                else -> Unit
            }
        }
        return AnswerEntry(no, stem, answer, analysis)
    }

    // ---------------- TXT ----------------

    /** `1.A` / `1、A` / `第1题 A` / `(1) A` */
    private val LINE_RE = Regex("""^\s*[(（]?\s*(?:第)?\s*(\d{1,4})\s*(?:题)?\s*[.、．,，:：)）\-—]?\s*(.+?)\s*$""")

    /** 一行多条：`1.A 2.B 3.C` */
    private val INLINE_RE = Regex(
        """(?:^|[\s,，;；])(\d{1,4})\s*[.、．:：)）]?\s*([A-Ha-h]{1,6}|对|错|正确|错误|√|×|TRUE|FALSE|T|F)(?=\s|$|[.、．,，;；])"""
    )

    private fun parseTxt(bytes: ByteArray, issues: MutableList<ImportIssue>): List<AnswerEntry> {
        // 先按「完整题库」试一次：带 `答案：` 前缀的文本走这条路最准
        val structured = TxtImporter.parse(ByteArrayInputStream(bytes), ArrayList())
        val answered = structured.questions.filter { it.get(Field.ANSWER).isNotBlank() }
        if (answered.isNotEmpty() && answered.size * 2 >= structured.questions.size) {
            return answered.map {
                AnswerEntry(
                    no = it.rowNo,
                    stem = it.get(Field.STEM),
                    answer = clean(it.get(Field.ANSWER)),
                    analysis = it.get(Field.ANALYSIS)
                )
            }
        }

        val text = CsvImporter.decode(bytes).replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')

        // 整份文本像「题库」时（长题干行多），只接受严格答案，避免把题干当答案
        val looksLikeBank = lines.count { it.trim().length > 30 && QUESTION_HEAD.containsMatchIn(it.trim()) } >= 2

        val out = ArrayList<AnswerEntry>()
        lines.forEachIndexed { idx, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed

            val m = LINE_RE.matchEntire(line)
            if (m != null) {
                val no = m.groupValues[1].toIntOrNull()
                val ans = clean(m.groupValues[2])
                if (looksLikeAnswer(ans) || (!looksLikeBank && acceptLoose(ans))) {
                    out.add(AnswerEntry(no = no, answer = ans))
                    return@forEachIndexed
                }
            }

            // 一行多条
            var hits = 0
            INLINE_RE.findAll(line).forEach { mm ->
                out.add(AnswerEntry(no = mm.groupValues[1].toIntOrNull(), answer = mm.groupValues[2]))
                hits++
            }
            if (hits == 0) {
                issues.add(
                    ImportIssue(idx + 1, ImportIssue.Level.WARN, "无法识别这一行：${line.take(20)}")
                )
            }
        }
        return out
    }

    private val QUESTION_HEAD = Regex("""^\s*[(（]?\s*\d{1,4}\s*[.、．)）]""")

    /** 答案形态：A / A,C / BCD / 对 / 正确 / T … */
    private fun looksLikeAnswer(s: String): Boolean {
        val t = s.trim().uppercase()
        if (t.isEmpty() || t.length > 30) return false
        if (t.matches(Regex("^[A-H]((\\s*[,，、/]\\s*|\\s+)?[A-H])*$"))) return true
        return t in setOf("对", "错", "正确", "错误", "√", "×", "T", "F", "Y", "N", "TRUE", "FALSE")
    }

    /** 宽松形态：填空题答案这种短词也收，但排除带问号/句号的题干片段 */
    private fun acceptLoose(s: String): Boolean =
        s.isNotEmpty() && s.length <= 20 && !s.contains('？') && !s.contains('?') && !s.contains('。')

    /** 去掉「答案：」前缀与首尾空白 */
    internal fun clean(s: String?): String =
        (s ?: "").trim()
            .replace(Regex("^(答案|正确答案|参考答案|标准答案)\\s*[:：]\\s*"), "")
            .trim()
}

/**
 * 答案与题目的匹配。
 *
 * 优先级：**题干全等 > 题号 > 顺序**。
 * 题干优先是因为答案文件本身常常就是原题库的答案版，此时题序可能被重排；
 * 题号优先则适合「只有一张题号答案表」的常见场景。
 */
object AnswerMatcher {

    data class Hit(
        val index: Int,
        val question: Question,
        /** 已按该题题型归一化过的答案，可直接入库 */
        val answer: String,
        val analysis: String
    )

    data class Result(
        val hits: List<Hit>,
        val unmatched: List<AnswerEntry>,
        /** 给人看的提示（未匹配数量、重复题号等） */
        val notes: List<String>
    )

    fun match(questions: List<Question>, sheet: AnswerSheet): Result {
        val used = HashSet<Int>()
        val hits = ArrayList<Hit>()
        val notes = ArrayList<String>()
        val unmatched = ArrayList<AnswerEntry>()

        // 题干索引：归一化后全等
        val stemMap = HashMap<String, MutableList<Int>>()
        questions.forEachIndexed { i, q ->
            val key = normStem(q.stem)
            if (key.length >= 8) stemMap.getOrPut(key) { ArrayList() }.add(i)
        }

        // 第一轮：题干
        val byNumber = ArrayList<AnswerEntry>()
        for (e in sheet.entries) {
            val key = normStem(e.stem)
            val idx = if (key.length >= 8) stemMap[key]?.firstOrNull { it !in used } else null
            if (idx != null) {
                hits.add(hit(questions[idx], idx, e))
                used.add(idx)
            } else {
                byNumber.add(e)
            }
        }

        // 第二轮：题号
        val byOrder = ArrayList<AnswerEntry>()
        for (e in byNumber) {
            val no = e.no
            if (no != null && no in 1..questions.size) {
                val idx = no - 1
                if (idx !in used) {
                    hits.add(hit(questions[idx], idx, e))
                    used.add(idx)
                } else {
                    notes.add("第 $no 题的答案重复出现，已保留先匹配到的一条")
                }
            } else {
                byOrder.add(e)
            }
        }

        // 第三轮：既没题号也对不上题干（纯答案列表）→ 按顺序填给还没匹配的题
        if (byOrder.isNotEmpty()) {
            val free = questions.indices.filter { it !in used }
            if (byOrder.size > free.size) {
                notes.add("答案条数（${byOrder.size}）多于待补题目（${free.size}），多出的部分已忽略")
            }
            byOrder.forEachIndexed { i, e ->
                val idx = free.getOrNull(i)
                if (idx == null) {
                    unmatched.add(e)
                } else {
                    hits.add(hit(questions[idx], idx, e))
                    used.add(idx)
                }
            }
        }

        if (used.size < questions.size) {
            notes.add("本卷还有 ${questions.size - used.size} 题没有匹配到答案")
        }
        return Result(hits.sortedBy { it.index }, unmatched, notes)
    }

    private fun hit(q: Question, index: Int, e: AnswerEntry) = Hit(
        index = index,
        question = q,
        answer = Normalizer.normalizeAnswer(e.answer, q.type, q.options),
        analysis = e.analysis
    )

    /** 题干归一化：去空白/标点/全半角差异，便于跨文件比对 */
    private fun normStem(s: String): String = Judge.normalizeText(s)
}
