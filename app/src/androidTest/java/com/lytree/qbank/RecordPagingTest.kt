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
 * 练习记录分页的回归测试。
 *
 * 产品口径：
 * 1. 一页一页取出来的顺序与全量一致（按 id 倒序），不重不漏；
 * 2. 超过总数往下取给空页——列表据此收尾，才不会一直转圈；
 * 3. 不在第一页的记录也能按 id 直接读到（报告页 / 继续答题不该为此读全表）。
 */
@RunWith(AndroidJUnit4::class)
class RecordPagingTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = QBankDb(ctx)

    /** 用负数试卷 id 隔离测试数据，不会碰到真实的卷子 */
    private val paperId = -992_001L

    @After
    fun cleanup() {
        db.writableDatabase.delete("records", "paper_id = ?", arrayOf(paperId.toString()))
    }

    /** 造 [count] 条记录，返回新建的 id（早 → 晚） */
    private fun seed(count: Int): List<Long> {
        val now = System.currentTimeMillis()
        return (1..count).map { i ->
            db.insertRecord(
                ExamRecord(
                    paperId = paperId, name = "分页 $i", mode = "手动交卷",
                    startedAt = now - 60_000, submittedAt = now + i, durationSec = 1,
                    totalCount = 1, answeredCount = 1, correctCount = 1,
                    partialCount = 0, score = 1.0, fullScore = 1.0
                ),
                emptyList()
            )
        }
    }

    @Test
    fun pages_join_into_the_same_list_as_full_read() {
        seed(45)

        val all = db.listRecords().map { it.id }
        assertEquals("总数与全量读到的条数一致", all.size, db.countRecords())

        // 按 20 条一页往下翻，拼起来必须和全量一模一样（不重、不漏、顺序相同）
        val paged = ArrayList<Long>()
        var offset = 0
        while (true) {
            val page = db.listRecords(20, offset).map { it.id }
            if (page.isEmpty()) break
            paged.addAll(page)
            offset += page.size
        }
        assertEquals("分页拼起来 == 全量", all, paged)
        assertTrue("造的数据要跨页才测得出来", paged.size >= 45)
    }

    @Test
    fun offset_past_the_end_returns_empty_page() {
        seed(5)
        val total = db.countRecords()

        // 列表就是靠这个空页收尾的，不能返回第一页导致无限加载
        assertTrue("超过总数应给空页", db.listRecords(20, total).isEmpty())
        assertTrue("远超过总数也是空页", db.listRecords(20, total + 500).isEmpty())
        // 刚好落在最后一条上仍取得到
        assertTrue("最后一条不能被吞掉", db.listRecords(1, total - 1).isNotEmpty())
    }

    @Test
    fun record_by_id_works_outside_first_page() {
        seed(45)

        val all = db.listRecords()
        assertTrue("总数要跨过一页才测得出来", all.size > 20)

        // 最老的一条肯定不在第一页：报告页 / 继续答题要能直接按 id 拿到它，而不是先读全表
        val oldest = all.last()
        assertEquals("按 id 取回的就是全量里的同一条", oldest, db.recordById(oldest.id))
        assertEquals("最新一条同样能按 id 读回", all.first(), db.recordById(all.first().id))
    }
}
