package com.lytree.qbank

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lytree.qbank.data.db.AnswerUpdate
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.importer.AnswerMatcher
import com.lytree.qbank.data.importer.AnswerSheetImporter
import com.lytree.qbank.data.importer.ImportFormat
import com.lytree.qbank.data.importer.Importer
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.engine.Judge
import com.lytree.qbank.engine.Policy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「试卷没答案 → 做完 → 补答案 → 对比」全链路测试。
 *
 * 覆盖三件事：
 * 1. 缺答案的题库不再被拒绝，按「待补答案」入库；
 * 2. 答案文件（题号表 / 题干表）能与题目正确匹配，并归一化成可判分的形式；
 * 3. 补完答案后重判历史记录，得分与正确率按已有作答重算。
 */
@RunWith(AndroidJUnit4::class)
class AnswerBackfillTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = QBankDb(ctx)

    /** 一份没有答案的三题试卷：单选 / 多选 / 判断 */
    private val noAnswerJson = """
    [
      {"type":"单选","stem":"下列说法正确的是","options":{"A":"甲","B":"乙","C":"丙","D":"丁"}},
      {"type":"多选","stem":"下列属于行政处罚的有","options":{"A":"警告","B":"罚款","C":"拘役","D":"没收"}},
      {"type":"判断","stem":"行政复议不收费","options":{"A":"正确","B":"错误"}}
    ]
    """.trimIndent()

    private var bankId = 0L
    private var recordId = 0L

    @After
    fun cleanup() {
        if (recordId > 0) {
            db.writableDatabase.delete("record_answers", "record_id = ?", arrayOf(recordId.toString()))
            db.writableDatabase.delete("records", "id = ?", arrayOf(recordId.toString()))
        }
        if (bankId > 0) db.deleteBank(bankId)
    }

    @Test
    fun paper_without_answers_is_importable() {
        val r = Importer.build(ImportFormat.JSON, noAnswerJson.toByteArray())
        assertEquals("三题都应导入", 3, r.imported)
        assertEquals("缺答案只算警告，不算错误", 0, r.errorCount)
        assertTrue("答案应为空", r.questions.all { it.answer.isBlank() })
        assertTrue("缺答案不应影响判题依据的判断", r.questions.none { Judge.hasStandard(it) })
    }

    @Test
    fun answer_txt_matches_by_number() {
        val questions = Importer.build(ImportFormat.JSON, noAnswerJson.toByteArray()).questions
        val sheet = AnswerSheetImporter.parse("答案.txt", "1.B\n2.A,C\n3.对\n".toByteArray())
        assertEquals("应解析出三条答案", 3, sheet.entries.size)

        val m = AnswerMatcher.match(questions, sheet)
        assertEquals("三题都应匹配上", 3, m.hits.size)
        assertEquals("单选题答案按题号命中", "B", m.hits[0].answer)
        assertEquals("多选答案归一化为 A,C", "A,C", m.hits[1].answer)
        assertEquals("判断题「对」归一化为 T", "T", m.hits[2].answer)
    }

    @Test
    fun answer_csv_matches_by_stem_when_order_differs() {
        val questions = Importer.build(ImportFormat.JSON, noAnswerJson.toByteArray()).questions
        // 顺序与卷内相反，且不带题号：靠题干匹配
        val csv = """
            题干,答案,解析
            行政复议不收费,正确,行政复议法明确规定不收费
            下列属于行政处罚的有,"A,B,D",
            下列说法正确的是,B,乙项符合题意
        """.trimIndent().replace("\n", "\r\n")

        val sheet = AnswerSheetImporter.parse("答案.csv", csv.toByteArray())
        assertEquals("应解析出三条答案", 3, sheet.entries.size)

        val m = AnswerMatcher.match(questions, sheet)
        assertEquals("按题干应全部匹配", 3, m.hits.size)
        assertEquals("第 1 题取到自己的答案", "B", m.hits[0].answer)
        assertEquals("第 2 题取到自己的答案", "A,B,D", m.hits[1].answer)
        assertEquals("第 3 题取到自己的答案", "T", m.hits[2].answer)
        assertEquals("解析随答案一起补进来", "乙项符合题意", m.hits[0].analysis)
    }

    @Test
    fun backfill_then_rejudge_uses_existing_answers() {
        val questions = Importer.build(ImportFormat.JSON, noAnswerJson.toByteArray()).questions
        val name = "回归测试卷-${System.currentTimeMillis()}"
        val imported = db.importAsPaper(name, "", "", name, 0, questions)
        bankId = imported.bankId

        val paperQuestions = db.getPaper(imported.paperId)!!.questions
        assertEquals(3, paperQuestions.size)
        assertEquals("新导入的卷子应全是待补答案", 3, db.countMissingAnswers(imported.paperId))

        // 模拟作答：单选 B、多选 A,C、判断 B
        val userAnswers = listOf("B", "A,C", "B")
        val policy = Policy.DEFAULT
        val before = paperQuestions.mapIndexed { i, q -> Judge.judge(q, userAnswers[i], policy) }
        assertTrue("无答案时全部待补", before.all { it.pending })

        val summaryBefore = Judge.summarize(before)
        assertEquals(0.0, summaryBefore.score, 0.0001)
        assertEquals("待补的题不计入满分", 0.0, summaryBefore.fullScore, 0.0001)
        assertEquals(3, summaryBefore.pending)

        val now = System.currentTimeMillis()
        recordId = db.insertRecord(
            ExamRecord(
                paperId = imported.paperId, name = "回归测试", mode = "手动交卷",
                startedAt = now - 60_000, submittedAt = now, durationSec = 60,
                totalCount = summaryBefore.total, answeredCount = summaryBefore.answered,
                correctCount = summaryBefore.correct, partialCount = summaryBefore.partial,
                score = summaryBefore.score, fullScore = summaryBefore.fullScore
            ),
            before
        )

        // 补答案
        val written = db.updateAnswers(
            listOf(
                AnswerUpdate(paperQuestions[0].id, "B"),
                AnswerUpdate(paperQuestions[1].id, "A,B,D"),
                AnswerUpdate(paperQuestions[2].id, "T", "行政复议不收费")
            )
        )
        assertEquals(3, written)
        assertEquals(0, db.countMissingAnswers(imported.paperId))

        // 重判：单选对、多选错（漏选默认不给分）、判断错
        val after = db.rejudgeRecord(recordId)!!
        assertEquals("待补答案清零", 0, after.pendingCount)
        assertEquals("答对 1 题", 1, after.correctCount)
        assertEquals("满分 4 分", 4.0, after.fullScore, 0.0001)
        assertEquals("单选 1 分", 1.0, after.score, 0.0001)

        val pendingFree = after.results.none { it.pending }
        assertTrue("重判后不应再有待补状态", pendingFree)
        assertFalse("原作答应保留", after.results.all { it.userAnswer.isBlank() })
    }
}
