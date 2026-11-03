package com.lytree.qbank

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.ui.ExamActivity
import com.lytree.qbank.ui.InkOverlay
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 笔迹「预取 + 节流落库」的针对性回归。
 *
 * 覆盖四条：
 * 1. 多笔累积后在 flush 时一次写出，内容完整；
 * 2. 合并写不改变笔迹顺序与每一笔的点序；
 * 3. 撤销后的结果（含撤销到空删行）落库正确；
 * 4. 答题页 onPause 会把节流中的最后一笔 flush 出去。
 */
@RunWith(AndroidJUnit4::class)
class InkPersistenceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = QBankDb(ctx)

    /** 负数 id 隔离测试数据，不碰真实卷子 */
    private val paperId = -990_601L
    private val questionId = -990_611L
    private val targetQid = -990_612L

    @Before
    fun setUp() {
        cleanUp()
    }

    @After
    fun tearDown() {
        cleanUp()
    }

    // ---------------- 多笔合并 ----------------

    @Test
    fun accumulated_strokes_are_written_once_and_content_is_complete() {
        val draft = db.ensureDraftRecord(paperId, "exam", 1_000L)
        val overlay = newOverlay(draft)

        overlay.drawStroke(100f to 100f, 200f to 200f)
        overlay.drawStroke(300f to 300f, 400f to 400f, 500f to 500f)

        // 节流窗口内：还没落库（这就是「合并成一次写」的前提）
        assertNull("抬笔后要等合并窗口，不能一笔一写", db.getInk(draft, QBankDb.INK_KIND_QUESTION, targetQid))

        overlay.flush()
        val raw = db.getInk(draft, QBankDb.INK_KIND_QUESTION, targetQid)
        assertTrue("flush 后应有一次完整落库", raw != null && raw.isNotEmpty())

        val strokes = strokesOf(raw!!)
        assertEquals("两笔都要在", 2, strokes.size)
        assertEquals("第一笔点序完整", 2, strokes[0].size)
        assertEquals("第二笔点序完整", 3, strokes[1].size)
        assertEquals(100f, strokes[0][0][0], 0.01f)
        assertEquals(500f, strokes[1][2][0], 0.01f)
        assertEquals("同一目标只留一行", 1, inkRows(draft, targetQid))
    }

    @Test
    fun merged_write_keeps_stroke_order_and_point_order() {
        val draft = db.ensureDraftRecord(paperId, "exam", 1_000L)
        val overlay = newOverlay(draft)

        overlay.drawStroke(10f to 10f, 20f to 20f)
        overlay.drawStroke(30f to 30f, 40f to 40f, 50f to 50f)
        overlay.drawStroke(60f to 60f)
        overlay.flush()

        val strokes = strokesOf(db.getInk(draft, QBankDb.INK_KIND_QUESTION, targetQid)!!)
        assertEquals("三笔按先后顺序保存", 3, strokes.size)
        // 每笔的起点就是「第几笔」的指纹，顺序错了立刻能看出来
        assertEquals(10f, strokes[0][0][0], 0.01f)
        assertEquals(30f, strokes[1][0][0], 0.01f)
        assertEquals(60f, strokes[2][0][0], 0.01f)
        // 每笔内部点序不能倒
        assertEquals(20f, strokes[0][1][0], 0.01f)
        assertEquals(40f, strokes[1][1][0], 0.01f)
        assertEquals(50f, strokes[1][2][0], 0.01f)
        assertEquals("单点那一笔也要保留", 1, strokes[2].size)
    }

    @Test
    fun undo_then_flush_persists_without_the_undone_stroke() {
        val draft = db.ensureDraftRecord(paperId, "exam", 1_000L)
        val overlay = newOverlay(draft)

        overlay.drawStroke(10f to 10f, 20f to 20f)
        overlay.drawStroke(30f to 30f, 40f to 40f)
        // 撤销最后一笔，再撤销到空：两次都要按撤销后的结果落库
        overlay.undo()
        overlay.flush()
        val afterFirstUndo = strokesOf(db.getInk(draft, QBankDb.INK_KIND_QUESTION, targetQid)!!)
        assertEquals("撤销后只剩一笔", 1, afterFirstUndo.size)
        assertEquals("留下的是没被撤销的那一笔", 10f, afterFirstUndo[0][0][0], 0.01f)

        overlay.undo()
        overlay.flush()
        assertNull("撤销到空要删行，不留空串", db.getInk(draft, QBankDb.INK_KIND_QUESTION, targetQid))
        assertEquals(0, inkRows(draft, targetQid))
    }

    // ---------------- onPause 触发 flush ----------------

    @Test
    fun on_pause_flushes_pending_strokes() {
        insertPaper()

        val scenario = ActivityScenario.launch<ExamActivity>(
            Intent(ctx, ExamActivity::class.java).putExtra(ExamActivity.EXTRA_PAPER_ID, paperId)
        )
        try {
            val recordId = waitForRecordId(scenario)
            assertTrue("答题页进页面就该有草稿记录", recordId > 0L)

            scenario.onActivity { activity ->
                val overlay = activity.findViewById<InkOverlay>(R.id.inkOverlay)
                overlay.writeMode = true
                overlay.drawStroke(120f to 120f, 220f to 220f)
            }

            // 还没到节流窗口，库里应该还是空的——接下来的写入只能由 onPause 的 flush 完成
            assertNull(
                "落笔后不应立刻写库（节流窗口内）",
                db.getInk(recordId, QBankDb.INK_KIND_QUESTION, targetQid)
            )

            scenario.moveToState(Lifecycle.State.CREATED)

            val found = db.listInkForRecord(recordId)
            assertTrue("onPause 必须把没落库的笔迹 flush 出去", found.isNotEmpty())
            val strokes = strokesOf(found.first().strokes)
            assertEquals("flush 出来的笔迹内容正确", 1, strokes.size)
            assertEquals(2, strokes[0].size)
        } finally {
            scenario.close()
        }
    }

    // ---------------- 工具 ----------------

    /** 不挂 Activity 的裸 View 也能跑绘制：layout 出尺寸，锚点铺满整层 */
    private fun newOverlay(recordId: Long): InkOverlay =
        InkOverlay(ctx).apply {
            layout(0, 0, 1080, 1920)
            anchorSource = {
                listOf(InkOverlay.Anchor(targetQid, 0f, 0f, 1080f, 1920f))
            }
            this.recordId = recordId
            writeMode = true
        }

    /** 按真实触摸序列落一笔：DOWN + 若干 MOVE + UP */
    private fun InkOverlay.drawStroke(vararg points: Pair<Float, Float>) {
        val downAt = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, downAt, downAt, points[0])
        for (i in 1 until points.size) {
            dispatch(MotionEvent.ACTION_MOVE, downAt, downAt + i * 10L, points[i])
        }
        dispatch(MotionEvent.ACTION_UP, downAt, downAt + points.size * 10L, points.last())
    }

    private fun InkOverlay.dispatch(action: Int, downAt: Long, at: Long, p: Pair<Float, Float>) {
        val e = MotionEvent.obtain(downAt, at, action, p.first, p.second, 0)
        try {
            onTouchEvent(e)
        } finally {
            e.recycle()
        }
    }

    private fun strokesOf(raw: String): List<List<FloatArray>> {
        val arr = JSONObject(raw).getJSONArray("s")
        return (0 until arr.length()).map { i ->
            val pts = arr.getJSONObject(i).getJSONArray("p")
            (0 until pts.length()).map { j ->
                val pa = pts.getJSONArray(j)
                floatArrayOf(
                    pa.getDouble(0).toFloat(),
                    pa.getDouble(1).toFloat(),
                    pa.getDouble(2).toFloat()
                )
            }
        }
    }

    private fun inkRows(recordId: Long, targetId: Long): Int =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM record_inks WHERE record_id = ? AND kind = ? AND target_id = ?",
            arrayOf(recordId.toString(), QBankDb.INK_KIND_QUESTION, targetId.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** 轮询等主线程把异步初始化做完（进卷是后台读库，RESUMED 可能先到） */
    private fun waitForRecordId(scenario: ActivityScenario<ExamActivity>): Long {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var rid = 0L
            scenario.onActivity { activity ->
                rid = activity.findViewById<InkOverlay>(R.id.inkOverlay).recordId
            }
            if (rid > 0L) return rid
            Thread.sleep(50L)
        }
        return 0L
    }

    private fun insertPaper() {
        val w = db.writableDatabase
        w.execSQL(
            """INSERT INTO papers(id, name, description, created_at, duration_min, mode)
               VALUES($paperId, '笔迹落库测试卷', '', 1700000000000, 30, 'exam')"""
        )
        w.execSQL(
            "INSERT INTO questions(id, type, stem, answer) VALUES($questionId, 'single', '题干', 'A')"
        )
        w.execSQL(
            "INSERT INTO paper_questions(paper_id, question_id, position) VALUES($paperId, $questionId, 0)"
        )
    }

    private fun cleanUp() {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (table in arrayOf("record_inks", "record_answers", "record_question_times")) {
                w.execSQL(
                    "DELETE FROM $table WHERE record_id IN (SELECT id FROM records WHERE paper_id = ?)",
                    arrayOf<Any>(paperId)
                )
            }
            w.delete("records", "paper_id = ?", arrayOf(paperId.toString()))
            w.execSQL("DELETE FROM exam_progress WHERE paper_id = ?", arrayOf<Any>(paperId))
            w.delete("paper_questions", "paper_id = ?", arrayOf(paperId.toString()))
            w.delete("papers", "id = ?", arrayOf(paperId.toString()))
            w.delete("questions", "id = ?", arrayOf(questionId.toString()))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }
}
