package com.lytree.qbank

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ExamRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 手写标注跟随作答记录保存的回归测试。
 *
 * 覆盖四条产品口径：
 * 1. 进卷先落草稿记录，同一张卷 / 同一个来源反复进入拿到的是同一条草稿，草稿不进记录列表；
 * 2. 笔迹挂在本轮记录上：换一轮练习（另一条记录）看不到上一轮的圈画；
 * 3. 同一记录同一目标只保留最新一份（后写覆盖前写，不涨行数），撤销到空即删行；
 * 4. 交卷转正后记录 id 不变、笔迹还在；重新开始 / 放弃时连带笔迹一起清掉；
 *    题目（question）与材料（material）分开存，互不覆盖。
 */
@RunWith(AndroidJUnit4::class)
class RecordInkTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = QBankDb(ctx)

    /** 用负数试卷 id 隔离测试数据，不会碰到真实的卷子 */
    private val paperId = -990_001L
    private val otherPaperId = -990_002L
    private val source = "exam"

    @After
    fun cleanup() {
        val ids = ArrayList<Long>()
        db.readableDatabase.rawQuery(
            "SELECT id FROM records WHERE paper_id IN (?, ?)",
            arrayOf(paperId.toString(), otherPaperId.toString())
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
    fun draft_record_is_reused_and_hidden_from_list() {
        val first = db.ensureDraftRecord(paperId, source, 1_000L)
        assertTrue("草稿应有 id", first > 0L)

        val again = db.ensureDraftRecord(paperId, source, 2_000L)
        assertEquals("同一张卷同一来源复用同一条草稿", first, again)

        assertTrue("草稿不进入记录列表", db.listRecords().none { it.id == first })

        // 另一个来源（分屏页）是另一条草稿
        val split = db.ensureDraftRecord(paperId, "split", 3_000L)
        assertNotEquals("答题页与分屏页的草稿分开", first, split)
    }

    @Test
    fun ink_follows_record_and_keeps_only_latest() {
        val draft = db.ensureDraftRecord(paperId, source, 1_000L)

        db.setInk(draft, QBankDb.INK_KIND_QUESTION, 101L, "v1")
        assertEquals("v1", db.getInk(draft, QBankDb.INK_KIND_QUESTION, 101L))

        db.setInk(draft, QBankDb.INK_KIND_QUESTION, 101L, "v2")
        assertEquals("后写覆盖前写，只留最新一份", "v2", db.getInk(draft, QBankDb.INK_KIND_QUESTION, 101L))
        assertEquals("同一记录同一目标只有一行", 1, inkRows(draft, QBankDb.INK_KIND_QUESTION, 101L))

        // 材料与题目分开存，互不覆盖
        db.setInk(draft, QBankDb.INK_KIND_MATERIAL, 101L, "material")
        assertEquals("题目笔迹不受材料笔迹影响", "v2", db.getInk(draft, QBankDb.INK_KIND_QUESTION, 101L))
        assertEquals("material", db.getInk(draft, QBankDb.INK_KIND_MATERIAL, 101L))

        // 换一轮练习是另一条记录，圈画不串
        val nextRound = db.ensureDraftRecord(otherPaperId, source, 4_000L)
        assertNull("新一轮看不到上一轮的圈画", db.getInk(nextRound, QBankDb.INK_KIND_QUESTION, 101L))
        assertNull("新一轮看不到上一轮的材料圈画", db.getInk(nextRound, QBankDb.INK_KIND_MATERIAL, 101L))
    }

    @Test
    fun undo_to_empty_deletes_row() {
        val draft = db.ensureDraftRecord(paperId, source, 1_000L)
        db.setInk(draft, QBankDb.INK_KIND_QUESTION, 202L, "only")
        assertEquals(1, inkRows(draft, QBankDb.INK_KIND_QUESTION, 202L))

        db.deleteInk(draft, QBankDb.INK_KIND_QUESTION, 202L)
        assertNull("撤销到空不再留空行", db.getInk(draft, QBankDb.INK_KIND_QUESTION, 202L))
        assertEquals(0, inkRows(draft, QBankDb.INK_KIND_QUESTION, 202L))
    }

    @Test
    fun finalize_keeps_record_and_ink() {
        val draft = db.ensureDraftRecord(paperId, source, 1_000L)
        db.setInk(draft, QBankDb.INK_KIND_QUESTION, 303L, "kept")
        db.setInk(draft, QBankDb.INK_KIND_MATERIAL, 304L, "kept-material")

        val now = System.currentTimeMillis()
        val rid = db.finalizeRecord(
            draft,
            ExamRecord(
                paperId = paperId, name = "标注测试", mode = "手动交卷",
                startedAt = 1_000L, submittedAt = now, durationSec = 1,
                totalCount = 1, answeredCount = 1, correctCount = 1,
                partialCount = 0, score = 1.0, fullScore = 1.0
            ),
            emptyList()
        )
        assertEquals("交卷不换记录 id", draft, rid)
        assertTrue("交卷后进入记录列表", db.listRecords().any { it.id == rid })
        assertEquals("题目笔迹随记录保留", "kept", db.getInk(rid, QBankDb.INK_KIND_QUESTION, 303L))
        assertEquals("材料笔迹随记录保留", "kept-material", db.getInk(rid, QBankDb.INK_KIND_MATERIAL, 304L))

        // 交卷后再进卷是全新一轮：拿到的是新草稿，不是刚交的记录
        val fresh = db.ensureDraftRecord(paperId, source, now + 1)
        assertNotEquals("交卷后另起草稿", rid, fresh)
        assertNull("新一轮没有上一轮的笔迹", db.getInk(fresh, QBankDb.INK_KIND_QUESTION, 303L))

        // 重新开始 / 放弃分屏：草稿连同笔迹一起清掉
        db.setInk(fresh, QBankDb.INK_KIND_QUESTION, 303L, "discard-me")
        db.discardDraftRecords(paperId, source)
        assertFalse("草稿记录被丢弃", db.listRecords().any { it.id == fresh })
        assertNull("草稿笔迹一并清掉", db.getInk(fresh, QBankDb.INK_KIND_QUESTION, 303L))
        assertEquals("已交卷记录的笔迹不受影响", "kept", db.getInk(rid, QBankDb.INK_KIND_QUESTION, 303L))
        assertTrue("仍然能取回新的草稿", db.ensureDraftRecord(paperId, source, now + 2) > 0L)
    }

    private fun inkRows(recordId: Long, kind: String, targetId: Long): Int {
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM record_inks WHERE record_id = ? AND kind = ? AND target_id = ?",
            arrayOf(recordId.toString(), kind, targetId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }
}
