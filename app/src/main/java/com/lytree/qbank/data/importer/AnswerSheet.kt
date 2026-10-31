package com.lytree.qbank.data.importer

import com.lytree.qbank.data.model.Question
import com.lytree.qbank.engine.Judge
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

    /**
     * 整份文件只有解析没有答案 → 按「解析文件」处理：只更新解析，不动答案。
     * 混合文件（答案 + 解析两列都有）不算解析文件，走答案导入并顺带写入解析。
     */
    val analysisOnly: Boolean
        get() = entries.isNotEmpty() && entries.all { it.answer.isBlank() }
}

/**
 * 答案文件解析器。
 *
 * 统一只认 **Excel（.xlsx）** 一种文件：
 * - `题号,答案` 两列；也支持 `题号,答案,解析` 与带「题干」列的完整答案表；
 * - 表头能识别就按表头走，识别不到就按位置约定（第一列题号、第二列答案）。
 *
 * 手动粘贴的答案文本（逐题补录页）不走文件解析，见 [parsePasted]。
 */
object AnswerSheetImporter {

    fun parse(fileName: String, bytes: ByteArray): AnswerSheet {
        val issues = ArrayList<ImportIssue>()
        val entries = try {
            parseTable(XlsxReader.read(ByteArrayInputStream(bytes)).rows, issues)
        } catch (e: Exception) {
            issues.add(
                ImportIssue(0, ImportIssue.Level.ERROR, "只支持 Excel（.xlsx）文件：${e.message}")
            )
            emptyList()
        }
        if (entries.isEmpty() && issues.none { it.level == ImportIssue.Level.ERROR }) {
            issues.add(
                ImportIssue(0, ImportIssue.Level.ERROR, "没有从文件里解析出任何答案或解析，请检查表头（题号 / 答案 / 解析）")
            )
        }
        return AnswerSheet(entries, issues, "Excel")
    }

    /**
     * 解析**手动粘贴**的一段答案文本（不落文件、不做格式探测）。
     *
     * 只认「答案」本身，不做题库结构判断，所以比 [parse] 宽松：
     * - `1.A`、`第1题 A`、`(1) A` 逐行；
     * - 一行多条 `1.A 2.B 3.对`；
     * - 整段只有答案没有题号（`A B C D` / 一行一个字母）→ 不给题号，交给调用方按题序填。
     */
    fun parsePasted(raw: String): List<AnswerEntry> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<AnswerEntry>()

        for (line in text.split('\n')) {
            val s = line.trim()
            if (s.isEmpty()) continue

            val m = LINE_RE.matchEntire(s)
            if (m != null) {
                val ans = clean(m.groupValues[2])
                // 「1.A 2.B」这种一行多条：LINE_RE 会把后半截一起吃进来，交给下面的 INLINE_RE 拆
                val hasMore = MORE_NO_RE.containsMatchIn(ans)
                if (ans.isNotEmpty() && !hasMore) {
                    out.add(AnswerEntry(no = m.groupValues[1].toIntOrNull(), answer = ans))
                    continue
                }
            }

            var hits = 0
            INLINE_RE.findAll(s).forEach { mm ->
                out.add(AnswerEntry(no = mm.groupValues[1].toIntOrNull(), answer = mm.groupValues[2]))
                hits++
            }
            if (hits > 0) continue

            // 没有题号：按分隔符拆成纯答案序列
            s.split(' ', '　', '\t', ',', '，', '、', ';', '；', '/')
                .map { clean(it) }
                .filter { it.isNotEmpty() && it.length <= 20 }
                .forEach { out.add(AnswerEntry(answer = it)) }
        }
        return out
    }

    /** `1.A` / `1、A` / `第1题 A` / `(1) A`（粘贴文本用） */
    private val LINE_RE = Regex("""^\s*[(（]?\s*(?:第)?\s*(\d{1,4})\s*(?:题)?\s*[.、．,，:：)）\-—]?\s*(.+?)\s*$""")

    /** 一行多条：`1.A 2.B 3.C`（粘贴文本用） */
    private val INLINE_RE = Regex(
        """(?:^|[\s　,，;；、])(\d{1,4})\s*[.、．:：)）]?\s*([A-Ha-h]{1,6}|对|错|正确|错误|√|×|TRUE|FALSE|T|F)(?=\s|$|[.、．,，;；])"""
    )

    /** 一段文本里还夹着「题号 + 答案」的另一条（说明这一行不止一题） */
    private val MORE_NO_RE = Regex("""(?:^|[\s　,，;；、])\d{1,4}\s*[.、．:：)）]\s*\S""")

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
        val hasAnyHeader = hasAnswerHeader || anaCol >= 0 || noCol >= 0 || stemCol >= 0
        if (!hasAnyHeader) {
            issues.add(
                ImportIssue(
                    1, ImportIssue.Level.WARN,
                    "未识别到表头，已按「第一列题号、第二列答案」解析"
                )
            )
        } else if (!hasAnswerHeader && anaCol >= 0) {
            // 只有「题号 + 解析」两列的解析文件：答案列整个缺失是它的正常形态
            issues.add(
                ImportIssue(
                    1, ImportIssue.Level.WARN,
                    "未识别到「答案」列，已按解析文件导入（只更新解析，不改答案）"
                )
            )
        } else if (!hasAnswerHeader) {
            issues.add(
                ImportIssue(
                    1, ImportIssue.Level.WARN,
                    "未识别到「答案」列，已按「第一列题号、第二列答案」解析"
                )
            )
        }

        val out = ArrayList<AnswerEntry>()
        for (r in (if (hasAnyHeader) 1 else 0) until rows.size) {
            val row = rows[r]
            if (row.all { it.isBlank() }) continue
            val rowNo = r + 1

            val entry = if (hasAnyHeader) {
                AnswerEntry(
                    no = row.getOrNull(noCol)?.trim()?.toIntOrNull(),
                    stem = row.getOrNull(stemCol)?.trim().orEmpty(),
                    answer = if (ansCol >= 0) clean(row.getOrNull(ansCol)) else "",
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

            if (entry.answer.isBlank() && entry.analysis.isBlank()) {
                issues.add(ImportIssue(rowNo, ImportIssue.Level.WARN, "第 $rowNo 行答案与解析都为空，已跳过"))
                continue
            }
            out.add(entry)
        }
        return out
    }

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

    /**
     * @param what 匹配提示里的说法（「答案」/「解析」），随导入模式切换
     */
    fun match(questions: List<Question>, sheet: AnswerSheet, what: String = "答案"): Result {
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
                    notes.add("第 $no 题的$what 重复出现，已保留先匹配到的一条")
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
            notes.add("本卷还有 ${questions.size - used.size} 题没有匹配到$what")
        }
        return Result(hits.sortedBy { it.index }, unmatched, notes)
    }

    private fun hit(q: Question, index: Int, e: AnswerEntry) = Hit(
        index = index,
        question = q,
        // 解析文件的条目没有答案：保持空串，写入时只更新解析
        answer = if (e.answer.isBlank()) "" else Normalizer.normalizeAnswer(e.answer, q.type, q.options),
        analysis = e.analysis
    )

    /** 题干归一化：去空白/标点/全半角差异，便于跨文件比对 */
    private fun normStem(s: String): String = Judge.normalizeText(s)
}
