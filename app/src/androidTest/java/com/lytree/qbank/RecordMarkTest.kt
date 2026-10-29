package com.lytree.qbank

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ExamRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 标记（细线星）跟随做题记录留档的回归测试。
 *
 * 产品口径：
 * 1. 交卷时把本次的 marked 写进 `records.marked`，记录列表与报告都能读回；
 * 2. 补答案重判只改分数，不动标记；
 * 3. 没标记的记录保持空集，不会因为补列（v8）读出错值。
 */
@RunWith(AndroidJUnit4::class)
class RecordMarkTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = QBankDb(ctx)

    /** 用负数试卷 id 隔离测试数据，不会碰到真实的卷子 */
    private val paperId = -991_001L

    @After
    fun cleanup() {
        val ids = ArrayList<Long>()
        db.readableDatabase.rawQuery(
            "SELECT id FROM records WHERE paper_id = ?", arrayOf(paperId.toString())
        ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        db.writableDatabase.beginTransaction()
        try {
            for (id in ids) {
                db.writableDatabase.delete("record_inks", "record_id = ?", arrayOf(id.toString()))
                db.writableDatabase.delete("record_answers", "record_id = ?", arrayOf(id.toString()))
                db.writableDatabase.delete("records", "id = ?", arrayOf(id.toString()))
            }
            db.writableDatabase.setTransactionSuccessful()
        } finally {
            db.writableDatabase.endTransaction()
        }
    }

    @Test
    fun marks_are_saved_with_record() {
        val draft = db.ensureDraftRecord(paperId, "exam", 1_000L)
        val now = System.currentTimeMillis()

        val rid = db.finalizeRecord(
            draft,
            ExamRecord(
                paperId = paperId, name = "标记留档", mode = "手动交卷",
                startedAt = 1_000L, submittedAt = now, durationSec = 1,
                totalCount = 3, answeredCount = 3, correctCount = 2,
                partialCount = 0, score = 2.0, fullScore = 3.0,
                marked = setOf(11L, 22L)
            ),
            emptyList()
        )

        assertEquals("交卷不换记录 id", draft, rid)
        assertEquals("报告页能读回本次标记", setOf(11L, 22L), db.getRecord(rid)!!.marked)
        assertEquals(
            "记录列表能读回本次标记",
            setOf(11L, 22L),
            db.listRecords().first { it.id == rid }.marked
        )
    }

    @Test
    fun rejudge_keeps_marks() {
        val now = System.currentTimeMillis()
        val rid = db.insertRecord(
            ExamRecord(
                paperId = paperId, name = "重判", mode = "手动交卷",
                startedAt = now - 1_000, submittedAt = now, durationSec = 1,
                totalCount = 1, answeredCount = 1, correctCount = 0,
                score = 0.0, fullScore = 1.0, marked = setOf(33L)
            ),
            emptyList()
        )

        val after = db.rejudgeRecord(rid)!!
        assertEquals("补答案重判不动标记", setOf(33L), after.marked)
    }

    @Test
    fun record_without_marks_stays_empty() {
        val now = System.currentTimeMillis()
        val rid = db.insertRecord(
            ExamRecord(
                paperId = paperId, name = "无标记", mode = "手动交卷",
                startedAt = now - 1_000, submittedAt = now, durationSec = 1,
                totalCount = 1, answeredCount = 1, correctCount = 1,
                score = 1.0, fullScore = 1.0
            ),
            emptyList()
        )

        assertTrue("没标记就是空集", db.getRecord(rid)!!.marked.isEmpty())
    }
}
