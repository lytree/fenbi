package com.lytree.qbank

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
 */
@RunWith(AndroidJUnit4::class)
class ProgressMergeTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = QBankDb(ctx)

    /** 用负数试卷 id 隔离测试数据，不会碰到真实的卷子 */
    private val paperId = -990_101L

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
}
