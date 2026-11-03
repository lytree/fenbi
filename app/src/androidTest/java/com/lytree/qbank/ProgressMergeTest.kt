package com.lytree.qbank

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lytree.qbank.data.db.QBankDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 未交卷进度的存储口径：答题页与分屏页共用同一份进度。
 *
 * 分屏页只显示一组题，保存时必须与已有进度合并，
 * 否则会把答题页做的其它题清掉；已用时长则只增不减。
 *
 * 另外钉住进度串的两种格式：新写入用 US 分隔（值里的 `;` 不再截断），
 * 升级前落库的 `id,value;` 旧串仍要能读出来、并在下次保存时平滑转成新格式。
 */
@RunWith(AndroidJUnit4::class)
class ProgressMergeTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = QBankDb(ctx)

    /** 用负数试卷 id 隔离测试数据，不会碰到真实的卷子 */
    private val paperId = -990_101L

    private val sep = QBankDb.PROGRESS_UNIT_SEP

    @After
    fun cleanup() {
        db.writableDatabase.delete("exam_progress", "paper_id = ?", arrayOf(paperId.toString()))
    }

    @Test
    fun merge_keeps_answers_from_other_pages() {
        // 答题页先存：第 1、2 题作答
        db.saveProgress(paperId, mapOf(1L to "A", 2L to "B"), setOf(1L), 30)
        // 分屏页只显示第 2 题：改答案，并带上自己的标记与时长
        val merged = db.saveProgress(paperId, mapOf(2L to "C"), setOf(2L), 45, merge = true)

        assertEquals("分屏页没有的题不能被冲掉", "A", merged[1L])
        assertEquals("同一题以分屏页的最新作答为准", "C", merged[2L])

        val stored = db.getProgress(paperId)!!
        assertEquals("A", stored.answers[1L])
        assertEquals("C", stored.answers[2L])
        assertTrue("标记取并集", stored.marked.containsAll(setOf(1L, 2L)))
        assertEquals("时长取较大值", 45, stored.elapsedSec)
    }

    @Test
    fun merge_keeps_longer_elapsed() {
        db.saveProgress(paperId, mapOf(1L to "A"), emptySet(), 120)
        db.saveProgress(paperId, emptyMap(), emptySet(), 30, merge = true)
        assertEquals("已用时长单调递增，不能被较短的会话改小", 120, db.getProgress(paperId)!!.elapsedSec)
    }

    @Test
    fun plain_save_replaces_progress() {
        db.saveProgress(paperId, mapOf(1L to "A", 2L to "B"), emptySet(), 10)
        db.saveProgress(paperId, mapOf(1L to "C"), emptySet(), 20)
        assertEquals("答题页整卷覆盖写保持原语义", mapOf(1L to "C"), db.getProgress(paperId)!!.answers)
    }

    @Test
    fun semicolon_and_newline_in_answer_survive_roundtrip() {
        val text = "要点一;要点二\n补充说明，含逗号"
        db.saveProgress(paperId, mapOf(7L to text, 8L to ""), setOf(7L), 12)

        val stored = db.getProgress(paperId)!!
        assertEquals("值里的分号、换行、逗号都要原样读回", text, stored.answers[7L])
        assertEquals("空作答也要保留这一条", "", stored.answers[8L])
        assertTrue("库里应写成 US 开头的新格式", rawAnswers().startsWith(sep))
    }

    @Test
    fun legacy_progress_string_is_readable() {
        // 模拟升级前落库的旧串：分号分隔条目，键与值用第一个逗号切开
        db.writableDatabase.insertWithOnConflict(
            "exam_progress", null,
            ContentValues().apply {
                put("paper_id", paperId)
                put("answers", "1,A;2,B,C")
                put("marked", "1")
                put("elapsed_sec", 60)
                put("updated_at", 0L)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )

        val stored = db.getProgress(paperId)!!
        assertEquals("A", stored.answers[1L])
        assertEquals("旧格式里值中的逗号本就保留", "B,C", stored.answers[2L])
        assertEquals(setOf(1L), stored.marked)
        assertEquals(60, stored.elapsedSec)
    }

    @Test
    fun merge_on_legacy_row_reads_old_answers_and_rewrites_as_new_format() {
        db.writableDatabase.insertWithOnConflict(
            "exam_progress", null,
            ContentValues().apply {
                put("paper_id", paperId)
                put("answers", "1,A;2,B,C")
                put("marked", "")
                put("elapsed_sec", 30)
                put("updated_at", 0L)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )

        // 分屏页只改了第 2 题：合并时旧串要先读出来，再按新格式写回
        val merged = db.saveProgress(paperId, mapOf(2L to "D"), emptySet(), 40, merge = true)
        assertEquals("旧串里的其它作答不能丢", "A", merged[1L])
        assertEquals("同一题以本次为准", "D", merged[2L])

        val stored = db.getProgress(paperId)!!
        assertEquals("A", stored.answers[1L])
        assertEquals("D", stored.answers[2L])
        assertEquals("时长取较大值", 40, stored.elapsedSec)
        assertTrue("合并保存后已转成新格式", rawAnswers().startsWith(sep))
    }

    private fun rawAnswers(): String {
        db.readableDatabase.rawQuery(
            "SELECT answers FROM exam_progress WHERE paper_id = ?",
            arrayOf(paperId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getString(0) ?: "" else "" }
    }
}
