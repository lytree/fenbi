package com.lytree.qbank

import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.ui.MainActivity
import com.lytree.qbank.ui.record.RecordAdapter
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 练习记录分页的界面联动测试。
 *
 * 数据层分页有 [RecordPagingTest] 兜底，这一条管的是「页面确实一页一页地取」：
 * 首屏只装一页，滑到底之后适配器条数要变多（`loadMore()` 真的被滚动回调拉起来了）。
 */
@RunWith(AndroidJUnit4::class)
class RecordPagingUiTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val db = QBankDb(ctx)

    /** 用负数试卷 id 隔离测试数据，不会碰到真实的卷子 */
    private val paperId = -992_002L

    @Before
    fun seed() {
        val now = System.currentTimeMillis()
        repeat(RecordAdapter.PAGE_SIZE * 2 + 5) { i ->
            db.insertRecord(
                ExamRecord(
                    paperId = paperId, name = "分页 UI $i", mode = "手动交卷",
                    startedAt = now - 60_000, submittedAt = now + i, durationSec = 1,
                    totalCount = 1, answeredCount = 1, correctCount = 1,
                    partialCount = 0, score = 1.0, fullScore = 1.0
                ),
                emptyList()
            )
        }
    }

    @After
    fun cleanup() {
        db.writableDatabase.delete("records", "paper_id = ?", arrayOf(paperId.toString()))
    }

    @Test
    fun records_list_loads_one_page_then_more_on_scroll() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                act.findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_records
            }

            val firstPage = waitForCount(scenario) { it >= RecordAdapter.PAGE_SIZE }
            // 首屏只装一页（+1 是尾部状态行），不会把几十条一次读进来
            assertTrue(
                "首屏应只装一页，实际装了 $firstPage 条",
                firstPage <= RecordAdapter.PAGE_SIZE + 1
            )

            // 滑到底：滚动回调应该把下一页取回来
            scenario.onActivity { act ->
                act.findViewById<RecyclerView>(R.id.list)
                    .smoothScrollToPosition(firstPage - 1)
            }
            val afterScroll = waitForCount(scenario) { it > firstPage }

            assertTrue(
                "滑到底之后应补上下一页：$firstPage → $afterScroll",
                afterScroll > firstPage
            )
        }
    }

    /** 轮询适配器条数直到满足条件（或超时），返回最后看到的条数 */
    private fun waitForCount(scenario: ActivityScenario<MainActivity>, ok: (Int) -> Boolean): Int {
        val deadline = System.currentTimeMillis() + 5_000
        var last = 0
        while (System.currentTimeMillis() < deadline) {
            inst.waitForIdleSync()
            Thread.sleep(100)
            scenario.onActivity { act ->
                last = act.findViewById<RecyclerView>(R.id.list)?.adapter?.itemCount ?: 0
            }
            if (ok(last)) return last
        }
        return last
    }
}
