package com.lytree.qbank

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.AnswerResult
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.data.model.QuestionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 交卷的原子性：`finishSubmission` 一个事务内完成「记录转正 + 逐题作答 + 清进度」。
 *
 * 覆盖三条口径：
 * 1. 正常交卷：记录 id 不变、状态 done、成绩与整卷作答落库、未交卷进度被清掉；
 * 2. 重复交卷：第二次直接返回成功，不改写已有成绩、不追加作答行、不产生第二条记录；
 * 3. 事务中途失败：整体回滚——记录还是进行中、进度与作答都还在；失败后可以原样重试成功。
 */
@RunWith(AndroidJUnit4::class)
class SubmissionAtomicityTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = QBankDb(ctx)

    /** 用负数试卷 id 隔离测试数据；整卷 2 题，答案 A / B */
    private val paperId = -990_301L

    private val failTrigger = "test_fail_answers"

    @After
    fun cleanup() {
        db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $failTrigger")
        val ids = ArrayList<Long>()
        db.readableDatabase.rawQuery(
            "SELECT id FROM records WHERE paper_id = ?",
            arrayOf(paperId.toString())
        ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        db.writableDatabase.beginTransaction()
        try {
            for (id in ids) {
                db.writableDatabase.delete("record_answers", "record_id = ?", arrayOf(id.toString()))
                db.writableDatabase.delete("records", "id = ?", arrayOf(id.toString()))
            }
            db.writableDatabase.delete("exam_progress", "paper_id = ?", arrayOf(paperId.toString()))
            db.writableDatabase.setTransactionSuccessful()
        } finally {
            db.writableDatabase.endTransaction()
        }
    }

    @Test
    fun submission_commits_record_and_clears_progress() {
        val draft = db.ensureDraftRecord(paperId, "exam", 1_000L)
        db.saveProgress(paperId, mapOf(1L to "A", 2L to "B"), setOf(1L), 45)

        val rid = db.finishSubmission(paperId, draft, doneRecord(), results())

        assertEquals("草稿转正不换记录 id", draft, rid)
        val rec = db.getRecord(rid)!!
        assertEquals("交卷后记录是已完成", QBankDb.STATUS_DONE, rec.status)
        assertEquals("整卷 2 题都参与判分", 2, rec.totalCount)
        assertEquals(2, rec.answeredCount)
        assertEquals(2.0, rec.score, 0.001)
        assertEquals("整卷每题都要落库", 2, answerRows(rid))
        assertNull("未交卷进度要随事务一起清掉", db.getProgress(paperId))
        assertEquals("一条草稿只转成一条记录", 1, recordRows())
    }

    @Test
    fun repeated_submission_keeps_single_done_record() {
        val draft = db.ensureDraftRecord(paperId, "exam", 1_000L)
        db.saveProgress(paperId, mapOf(1L to "A", 2L to "B"), setOf(1L), 45)
        val first = db.finishSubmission(paperId, draft, doneRecord(), results())

        // 重复点击 / 页面重建后再交一次：记录已是 done，直接按成功返回
        val second = db.finishSubmission(paperId, first, doneRecord(score = 99.0), emptyList())

        assertEquals("幂等返回同一条记录", first, second)
        val rec = db.getRecord(first)!!
        assertEquals("既有成绩不能被第二次交卷改写", 2.0, rec.score, 0.001)
        assertEquals("不追加、也不清空已落库的作答", 2, answerRows(first))
        assertEquals("不会出现第二条 done 记录", 1, recordRows())
        assertNull(db.getProgress(paperId))
    }

    @Test
    fun failed_submission_rolls_back_and_can_retry() {
        val draft = db.ensureDraftRecord(paperId, "exam", 1_000L)
        db.saveProgress(paperId, mapOf(1L to "A", 2L to "B"), setOf(1L), 45)

        // 注入失败：往 record_answers 插第一条时直接 ABORT，模拟事务中途出错
        db.writableDatabase.execSQL(
            "CREATE TRIGGER $failTrigger BEFORE INSERT ON record_answers " +
                "WHEN NEW.record_id = $draft BEGIN SELECT RAISE(ABORT, 'injected failure'); END"
        )
        var error: Exception? = null
        try {
            db.finishSubmission(paperId, draft, doneRecord(), results())
        } catch (e: Exception) {
            error = e
        } finally {
            db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $failTrigger")
        }

        assertNotNull("注入的失败要能被抛出，让界面给出反馈", error)
        assertEquals("记录必须退回进行中", QBankDb.STATUS_ONGOING, db.getRecord(draft)!!.status)
        assertEquals("失败时不应留下任何作答行", 0, answerRows(draft))
        assertEquals("也不能多插一条记录", 1, recordRows())
        val kept = db.getProgress(paperId)!!
        assertEquals("作答进度原样保留", "A", kept.answers[1L])
        assertEquals("B", kept.answers[2L])
        assertEquals("标记也还在", setOf(1L), kept.marked)

        // 去掉故障后重试：同一条草稿能正常交卷
        val retried = db.finishSubmission(paperId, draft, doneRecord(), results())
        assertEquals(draft, retried)
        assertEquals(QBankDb.STATUS_DONE, db.getRecord(draft)!!.status)
        assertEquals(2, answerRows(draft))
        assertNull("重试成功后进度才被清掉", db.getProgress(paperId))
    }

    private fun doneRecord(score: Double = 2.0) = ExamRecord(
        paperId = paperId,
        name = "交卷原子性测试",
        mode = "手动交卷",
        startedAt = 1_000L,
        submittedAt = System.currentTimeMillis(),
        durationSec = 45,
        totalCount = 2,
        answeredCount = 2,
        correctCount = 2,
        partialCount = 0,
        score = score,
        fullScore = 2.0,
        marked = setOf(1L),
        status = QBankDb.STATUS_DONE
    )

    private fun results() = listOf(
        AnswerResult(
            Question(id = 1L, type = QuestionType.SINGLE, answer = "A", score = 1.0),
            "A", 1.0, 1.0, true, false
        ),
        AnswerResult(
            Question(id = 2L, type = QuestionType.SINGLE, answer = "B", score = 1.0),
            "B", 1.0, 1.0, true, false
        )
    )

    private fun answerRows(recordId: Long): Int =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM record_answers WHERE record_id = ?",
            arrayOf(recordId.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun recordRows(): Int =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM records WHERE paper_id = ?",
            arrayOf(paperId.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}
