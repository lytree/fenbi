package com.lytree.qbank.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.databinding.ActivityAnalysisBinding
import com.lytree.qbank.ui.paper.PaperDetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 逐题解析（独立一页）。
 *
 * 成绩报告只留入口，看题搬到这里：报告页上已经有成绩、模块正确率、补答案这些，
 * 再压一长串题卡会让人一直往下滚；单开一页也能自己切档，回报告不丢位置。
 *
 * 顶部「错题解析 / 全部解析」两档，看题是一屏一题、左右滑（[ViewPager2]），底部给「3 / 9」页码；
 * 每页自己竖向滚，长解析不用在同一屏里挤。两档用的是同一套题卡（题号 / 状态 / 题干 /
 * 我的答案 / 正确答案 / 解析，连页码样式都一样），区别只在 [TAB_WRONG] 把做对的题筛掉。
 *
 * 报告页的两个入口（错题解析 / 全部解析）带 [EXTRA_TAB] 指定初始档位，点题号再带
 * [EXTRA_START_NO] 直接翻到那一题——两档落地看到的是同一套页面，只有数据集不同。
 */
class AnalysisActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORD_ID = "record_id"

        /** 进来时停在哪一档：[TAB_WRONG] / [TAB_ALL] */
        const val EXTRA_TAB = "tab"

        /** 进来时直接翻到这一题（原卷题号下标，-1 表示从本档第一题开始） */
        const val EXTRA_START_NO = "start_no"

        const val TAB_WRONG = 0
        const val TAB_ALL = 1
    }

    private lateinit var binding: ActivityAnalysisBinding
    private lateinit var db: QBankDb
    private var recordId = 0L

    private lateinit var record: ExamRecord
    private var questionTimes: Map<Long, Int> = emptyMap()
    private var analysisTab = TAB_WRONG
    private var allRows: List<AnalysisPagerAdapter.Row> = emptyList()
    private var wrongRows: List<AnalysisPagerAdapter.Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenAdapt.applyOrientation(this)
        binding = ActivityAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)
        binding.titleBar.setTitle(getString(R.string.report_analysis_title))

        recordId = intent.getLongExtra(EXTRA_RECORD_ID, 0L)

        // 一屏一题：左右滑翻页，页码跟着变
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updatePageCounter()
        })

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val record = db.getRecord(recordId) ?: return@withContext null
                record to db.getQuestionTimes(record.id)
            }
            if (isFinishing || isDestroyed) return@launch
            if (loaded == null) {
                finish(); return@launch
            }
            render(loaded.first, loaded.second)
        }
    }

    private fun render(record: ExamRecord, times: Map<Long, Int>) {
        this.record = record
        this.questionTimes = times

        // 本次标记过的题在卡片上带「已标记」，标题里先给个总数，免得逐页翻找
        if (record.marked.isNotEmpty()) {
            binding.titleBar.setTitle(getString(R.string.report_analysis_title_marked, record.marked.size))
        }

        // 先按原卷顺序给每题编号，再各自过滤一次（题号跟着原卷走，不按列表位置算）
        allRows = record.results.mapIndexed { i, r -> AnalysisPagerAdapter.Row(i, r) }
        // 错题口径与报告页「答错」统计一致：答了但完全没对。部分得分 / 未作答 / 待补答案只在全部解析里
        wrongRows = allRows.filter { row ->
            val r = row.result
            !r.pending && r.userAnswer.isNotBlank() && !r.correct && !r.partial
        }

        binding.btnWrongTab.text = getString(R.string.report_tab_wrong, wrongRows.size)
        binding.btnAllTab.text = getString(R.string.report_tab_all, allRows.size)
        binding.btnWrongTab.setOnClickListener { switchTab(TAB_WRONG) }
        binding.btnAllTab.setOnClickListener { switchTab(TAB_ALL) }

        // 报告页两个入口各自带档位进来；点题号还带题号，落地就直接翻到那一题
        val wanted = intent.getIntExtra(EXTRA_TAB, TAB_WRONG)
        var tab = if (wanted == TAB_ALL) TAB_ALL else TAB_WRONG
        // 要的那一档是空的（一道没错却点了错题解析）就退回有内容的那档，别让人对着空页面
        if (tab == TAB_WRONG && wrongRows.isEmpty() && allRows.isNotEmpty()) tab = TAB_ALL
        if (tab == TAB_ALL && allRows.isEmpty()) tab = TAB_WRONG
        switchTab(tab, intent.getIntExtra(EXTRA_START_NO, -1))
    }

    /**
     * 切档位：两档共用同一套题卡与同一套翻页（一屏一题 + 底部页码），
     * 只是 [TAB_WRONG] 少掉做对的题。[startNo] >= 0 时翻到那一题，否则从本档第一题开始。
     */
    private fun switchTab(tab: Int, startNo: Int = -1) {
        val rows = if (tab == TAB_WRONG) wrongRows else allRows
        // 已经在这一档就不重建（连点同一档不该闪一下）
        if (analysisTab == tab && binding.pager.adapter != null) {
            jumpTo(rows, startNo)
            return
        }
        analysisTab = tab
        styleSegment(binding.btnWrongTab, tab == TAB_WRONG)
        styleSegment(binding.btnAllTab, tab == TAB_ALL)

        binding.pager.adapter = AnalysisPagerAdapter(rows, record.marked, questionTimes) { no ->
            openInPaper(record, no)
        }
        jumpTo(rows, startNo)

        binding.pager.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        binding.tvPageCounter.visibility = binding.pager.visibility
        binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (rows.isEmpty()) {
            // 待补答案的题不判分，也不进错题档——这时候提示「全对」会误导，如实说清楚
            binding.tvEmpty.text = when {
                record.pendingCount > 0 -> getString(R.string.pending_answer_hint)
                tab == TAB_WRONG -> getString(R.string.report_retry_empty)
                else -> getString(R.string.report_analysis_empty)
            }
        }
        updatePageCounter()
    }

    /** 翻到原卷题号为 [startNo] 的那一题；找不到（比如筛掉了）就停在第一题 */
    private fun jumpTo(rows: List<AnalysisPagerAdapter.Row>, startNo: Int) {
        val pos = if (startNo >= 0) rows.indexOfFirst { it.no == startNo } else -1
        binding.pager.setCurrentItem(if (pos >= 0) pos else 0, false)
        updatePageCounter()
    }

    /** 页码：滑到第几题 / 共几题（空档位不显示） */
    private fun updatePageCounter() {
        val count = binding.pager.adapter?.itemCount ?: 0
        binding.tvPageCounter.text = if (count == 0) "" else "${binding.pager.currentItem + 1} / $count"
    }

    /**
     * 点某道题 → 跳到它所在的试卷，定位到这道题并默认展开：
     * 题干 / 选项 / 材料 / 标准答案 / 解析一次看全，顺带带上这次的作答与对错。
     */
    private fun openInPaper(record: ExamRecord, resultIndex: Int) {
        val qid = record.results.getOrNull(resultIndex)?.question?.id ?: return
        if (record.paperId <= 0L) {
            android.widget.Toast.makeText(this, R.string.report_paper_missing, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            android.content.Intent(this, PaperDetailActivity::class.java).apply {
                putExtra(PaperDetailActivity.EXTRA_PAPER_ID, record.paperId)
                putExtra(PaperDetailActivity.EXTRA_PAPER_NAME, record.displayTitle)
                putExtra(PaperDetailActivity.EXTRA_FOCUS_QUESTION_ID, qid)
                putExtra(PaperDetailActivity.EXTRA_RECORD_ID, record.id)
            }
        )
    }

    /** 分段胶囊：选中白底深字加粗，未选中透明底灰字（与答题页「更多」面板同一套观感） */
    private fun styleSegment(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_fb_segment_pill else R.drawable.bg_fb_segment_pill_off
        )
        view.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.fb_segment_text_sel else R.color.fb_segment_text
            )
        )
        view.setTypeface(
            null,
            if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
    }
}
