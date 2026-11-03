package com.lytree.qbank

import android.content.Intent
import android.os.CountDownTimer
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.ui.ExamActivity
import com.lytree.qbank.ui.InkOverlay
import com.lytree.qbank.ui.QuestionTimer
import com.lytree.qbank.ui.SplitExamActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 交卷失败回滚后的计时恢复。
 *
 * 覆盖：
 * 1. 限时卷交卷失败 → 倒计时按**剩余时间**重挂（不回到满时长、失败窗口不算作答）→ 再次交卷成功；
 * 2. 连续两次失败 → 不叠加计时器、剩余时间不越走越少；
 * 3. 分屏页交卷失败 → 正计时续上、每题计时续上，再次交卷成功。
 */
@RunWith(AndroidJUnit4::class)
class SubmitTimingRecoveryTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = QBankDb(ctx)

    /** 用真实的正数 id：每题计时按「QuestionTimer 只给正 id 计时」的口径才能验证 */
    private val paperId = 9_900_701L
    private val splitPaperId = 9_900_702L
    private val questionId = 9_900_711L
    private val splitQuestionId = 9_900_712L

    private val failTrigger = "test_fail_submit_timing"

    @Before
    fun setUp() {
        cleanUp()
    }

    @After
    fun tearDown() {
        cleanUp()
    }

    // ---------------- 答题页 ----------------

    @Test
    fun failed_submit_restores_remaining_time_and_retry_succeeds() {
        insertPaper(paperId, questionId, durationMin = 30)
        val draft = db.ensureDraftRecord(paperId, "exam", 1_000L)
        installFailTrigger(draft)

        val scenario = launchExam()
        try {
            assertEquals("答题页沿用已有草稿", draft, waitForRecordId(scenario))
            val before = waitForPositiveTimer(scenario)

            scenario.onActivity { act -> invokeSubmit(act) }
            // 失败立刻回滚：界面不能停在「已交卷」，倒计时要重新走起来
            var submitted = true
            var timer: CountDownTimer? = null
            var after = 0
            scenario.onActivity { act ->
                submitted = field(act, "submitted") as Boolean
                timer = field(act, "timer") as CountDownTimer?
                after = remainingSeconds(act)
            }
            assertFalse("失败后必须回到可重试状态", submitted)
            assertTrue("失败后倒计时必须重新挂上", timer != null)
            assertTrue("剩余时间不能回到满时长", after in 1 until 30 * 60)
            assertTrue("交卷失败这段窗口不算作答（剩余时间不能明显变短）", after >= before - 3)

            // 倒计时确实在走（到点自动交卷的判定也一起恢复了）
            Thread.sleep(1_200L)
            val afterTick = readRemaining(scenario)
            assertTrue("恢复后倒计时必须继续递减", afterTick < after)

            // 每题计时：stop() 之后要重新拨回当前题，继续累加
            Thread.sleep(1_200L)
            var questionSec = 0
            scenario.onActivity { act ->
                questionSec = (field(act, "questionTimer") as QuestionTimer).currentSeconds()
            }
            assertTrue("失败后每题计时要继续累加", questionSec >= 1)

            // 去掉故障重试：同一条草稿正常交卷
            db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $failTrigger")
            scenario.onActivity { act -> invokeSubmit(act) }
            assertEquals("重试后记录完成", QBankDb.STATUS_DONE, db.getRecord(draft)!!.status)
            assertNull("重试成功后进度才被清掉", db.getProgress(paperId))
        } finally {
            db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $failTrigger")
            scenario.close()
        }
    }

    @Test
    fun two_failed_submits_do_not_stack_or_drain_the_timer() {
        insertPaper(paperId, questionId, durationMin = 30)
        val draft = db.ensureDraftRecord(paperId, "exam", 1_000L)
        installFailTrigger(draft)

        val scenario = launchExam()
        try {
            assertEquals(draft, waitForRecordId(scenario))
            val before = waitForPositiveTimer(scenario)

            scenario.onActivity { act -> invokeSubmit(act) }
            val afterFirst = readRemaining(scenario)
            scenario.onActivity { act -> invokeSubmit(act) }
            val afterSecond = readRemaining(scenario)

            // 两次失败各摘掉自己的失败窗口：不回到满时长，也不叠成两个计时器把时间走掉
            assertTrue("第一次失败后剩余时间不能回满", afterFirst in 1 until 30 * 60)
            assertTrue("第一次失败不能多扣时间", afterFirst >= before - 3)
            assertTrue("第二次失败后剩余时间不能回满", afterSecond in 1 until 30 * 60)
            assertTrue("第二次失败不能多扣时间", afterSecond >= afterFirst - 3)

            db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $failTrigger")
            scenario.onActivity { act -> invokeSubmit(act) }
            assertEquals(QBankDb.STATUS_DONE, db.getRecord(draft)!!.status)
        } finally {
            db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $failTrigger")
            scenario.close()
        }
    }

    // ---------------- 分屏页 ----------------

    @Test
    fun split_failed_submit_resumes_timers_and_retry_succeeds() {
        insertPaper(splitPaperId, splitQuestionId, durationMin = 30)
        val draft = db.ensureDraftRecord(splitPaperId, "split", 1_000L)
        installFailTrigger(draft)

        val scenario = ActivityScenario.launch<SplitExamActivity>(
            Intent(ctx, SplitExamActivity::class.java)
                .putExtra(SplitExamActivity.EXTRA_PAPER_ID, splitPaperId)
        )
        try {
            assertEquals("分屏页沿用已有草稿", draft, waitForSplitRecordId(scenario))
            var startedAtBefore = 0L
            scenario.onActivity { act ->
                startedAtBefore = field(act, "startedAt") as Long
                invokeSplitSubmit(act)
            }

            var submitted = true
            var startedAtAfter = 0L
            scenario.onActivity { act ->
                submitted = field(act, "submitted") as Boolean
                startedAtAfter = field(act, "startedAt") as Long
            }
            assertFalse("分屏页失败后必须回到可重试状态", submitted)
            // 正计时按 startedAt 起算：失败窗口被往后挪掉，计时不会把失败这段算作答
            assertTrue("失败时 startedAt 应往后挪（摘掉失败窗口）", startedAtAfter >= startedAtBefore)

            Thread.sleep(1_200L)
            var questionSec = 0
            scenario.onActivity { act ->
                questionSec = (field(act, "questionTimer") as QuestionTimer).currentSeconds()
            }
            assertTrue("分屏页失败后每题计时继续累加", questionSec >= 1)

            db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $failTrigger")
            scenario.onActivity { act -> invokeSplitSubmit(act) }
            assertEquals(QBankDb.STATUS_DONE, db.getRecord(draft)!!.status)
            assertNull(db.getProgress(splitPaperId))
        } finally {
            db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $failTrigger")
            scenario.close()
        }
    }

    // ---------------- 工具 ----------------

    private fun launchExam(): ActivityScenario<ExamActivity> =
        ActivityScenario.launch<ExamActivity>(
            Intent(ctx, ExamActivity::class.java).putExtra(ExamActivity.EXTRA_PAPER_ID, paperId)
        )

    private fun waitForRecordId(scenario: ActivityScenario<ExamActivity>): Long {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var rid = 0L
            scenario.onActivity { act ->
                rid = act.findViewById<InkOverlay>(R.id.inkOverlay).recordId
            }
            if (rid > 0L) return rid
            Thread.sleep(50L)
        }
        return 0L
    }

    private fun waitForSplitRecordId(scenario: ActivityScenario<SplitExamActivity>): Long {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var rid = 0L
            scenario.onActivity { act ->
                rid = act.findViewById<InkOverlay>(R.id.inkOverlay).recordId
            }
            if (rid > 0L) return rid
            Thread.sleep(50L)
        }
        return 0L
    }

    /** 等第一秒的 onTick 把倒计时文本刷出来，再读一个正的剩余秒数 */
    private fun waitForPositiveTimer(scenario: ActivityScenario<ExamActivity>): Int {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val sec = readRemaining(scenario)
            if (sec > 0) return sec
            Thread.sleep(50L)
        }
        return 0
    }

    private fun readRemaining(scenario: ActivityScenario<ExamActivity>): Int {
        var sec = 0
        scenario.onActivity { act -> sec = remainingSeconds(act) }
        return sec
    }

    /** 顶栏倒计时文本（mm:ss）换算成秒 */
    private fun remainingSeconds(activity: ExamActivity): Int {
        val text = activity.findViewById<TextView>(R.id.tvTimer).text.toString()
        val parts = text.split(":")
        if (parts.size != 2) return 0
        return (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
    }

    private fun invokeSubmit(activity: ExamActivity) {
        val m = ExamActivity::class.java.getDeclaredMethod("submit", Boolean::class.javaPrimitiveType)
        m.isAccessible = true
        m.invoke(activity, false)
    }

    private fun invokeSplitSubmit(activity: SplitExamActivity) {
        val m = SplitExamActivity::class.java.getDeclaredMethod("submit")
        m.isAccessible = true
        m.invoke(activity)
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    /** 注入失败：往 record_answers 插第一条时 ABORT，模拟交卷事务中途出错 */
    private fun installFailTrigger(recordId: Long) {
        db.writableDatabase.execSQL(
            "CREATE TRIGGER $failTrigger BEFORE INSERT ON record_answers " +
                "WHEN NEW.record_id = $recordId BEGIN SELECT RAISE(ABORT, 'injected failure'); END"
        )
    }

    private fun insertPaper(paperId: Long, questionId: Long, durationMin: Int) {
        val w = db.writableDatabase
        w.execSQL(
            """INSERT INTO papers(id, name, description, created_at, duration_min, mode)
               VALUES($paperId, '交卷计时恢复测试卷', '', 1700000000000, $durationMin, 'exam')"""
        )
        w.execSQL("INSERT INTO questions(id, type, stem, answer) VALUES($questionId, 'single', '题干', 'A')")
        w.execSQL("INSERT INTO paper_questions(paper_id, question_id, position) VALUES($paperId, $questionId, 0)")
    }

    private fun cleanUp() {
        db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $failTrigger")
        val ids = ArrayList<Long>()
        db.readableDatabase.rawQuery(
            "SELECT id FROM records WHERE paper_id IN (?, ?)",
            arrayOf(paperId.toString(), splitPaperId.toString())
        ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (id in ids) {
                for (table in arrayOf("record_inks", "record_answers", "record_question_times")) {
                    w.delete(table, "record_id = ?", arrayOf(id.toString()))
                }
                w.delete("records", "id = ?", arrayOf(id.toString()))
            }
            for (pid in longArrayOf(paperId, splitPaperId)) {
                w.delete("exam_progress", "paper_id = ?", arrayOf(pid.toString()))
                w.delete("paper_questions", "paper_id = ?", arrayOf(pid.toString()))
                w.delete("papers", "id = ?", arrayOf(pid.toString()))
            }
            w.delete("questions", "id = ?", arrayOf(questionId.toString()))
            w.delete("questions", "id = ?", arrayOf(splitQuestionId.toString()))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }
}
