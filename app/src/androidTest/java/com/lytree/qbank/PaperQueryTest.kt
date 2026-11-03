package com.lytree.qbank

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lytree.qbank.data.db.QBankDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `getPaper(id)` 改成按 id 直查后，结果必须与旧的「读列表再取那一条」完全一致：
 * 字段值、题量、缺答案计数，以及题目顺序（按 paper_questions.position，而不是插入顺序）。
 */
@RunWith(AndroidJUnit4::class)
class PaperQueryTest {

    private companion object {
        const val PAPER_ID = -990_401L
        const val SET_ID = -990_410L
        val QUESTION_IDS = longArrayOf(-990_401L, -990_402L, -990_403L)
    }

    private lateinit var db: QBankDb

    @Before
    fun setUp() {
        db = QBankDb(InstrumentationRegistry.getInstrumentation().targetContext)
        cleanUp()
        insertFixtures()
    }

    @After
    fun tearDown() {
        cleanUp()
    }

    private fun cleanUp() {
        val w = db.writableDatabase
        w.delete("paper_questions", "paper_id = ?", arrayOf(PAPER_ID.toString()))
        w.delete("papers", "id = ?", arrayOf(PAPER_ID.toString()))
        for (qid in QUESTION_IDS) w.delete("questions", "id = ?", arrayOf(qid.toString()))
    }

    private fun insertFixtures() {
        val w = db.writableDatabase
        w.execSQL(
            """INSERT INTO papers(id, name, description, created_at, duration_min, mode, set_id)
               VALUES($PAPER_ID, '一致性校验卷', '导入自 test.xlsx', 1700000000000, 30, 'exam', $SET_ID)"""
        )
        // 三题：一题没有标准答案（缺答案计数应为 1）
        w.execSQL("INSERT INTO questions(id, type, stem, answer) VALUES(${QUESTION_IDS[0]}, 'single', '第一题', 'A')")
        w.execSQL("INSERT INTO questions(id, type, stem, answer) VALUES(${QUESTION_IDS[1]}, 'single', '第二题', '')")
        w.execSQL("INSERT INTO questions(id, type, stem, answer) VALUES(${QUESTION_IDS[2]}, 'single', '第三题', 'B')")
        // position 与插入顺序故意不同：卷面顺序由 position 决定
        w.execSQL("INSERT INTO paper_questions(paper_id, question_id, position) VALUES($PAPER_ID, ${QUESTION_IDS[0]}, 1)")
        w.execSQL("INSERT INTO paper_questions(paper_id, question_id, position) VALUES($PAPER_ID, ${QUESTION_IDS[1]}, 0)")
        w.execSQL("INSERT INTO paper_questions(paper_id, question_id, position) VALUES($PAPER_ID, ${QUESTION_IDS[2]}, 2)")
    }

    @Test
    fun get_paper_by_id_matches_list_row_and_keeps_question_order() {
        val listed = db.listPapers().first { it.id == PAPER_ID }
        val byId = db.getPaper(PAPER_ID)
        assertNotNull(byId)
        byId!!

        // 字段逐项一致
        assertEquals(listed.id, byId.id)
        assertEquals(listed.name, byId.name)
        assertEquals(listed.description, byId.description)
        assertEquals(listed.createdAt, byId.createdAt)
        assertEquals(listed.durationMin, byId.durationMin)
        assertEquals(listed.mode, byId.mode)
        assertEquals(listed.setId, byId.setId)
        assertEquals(listed.questionCount, byId.questionCount)
        assertEquals(listed.missingAnswers, byId.missingAnswers)

        // 题量与顺序：按 position 排（第二题在 position 0，应该排第一）
        assertEquals(3, byId.questions.size)
        assertEquals(
            listOf(QUESTION_IDS[1], QUESTION_IDS[0], QUESTION_IDS[2]),
            byId.questions.map { it.id }
        )
        assertEquals("第二题", byId.questions[0].stem)
        assertEquals(1, byId.missingAnswers)
    }

    @Test
    fun get_paper_returns_null_for_unknown_id() {
        assertNull(db.getPaper(-990_499L))
    }
}
