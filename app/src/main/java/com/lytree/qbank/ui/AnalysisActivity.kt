package com.lytree.qbank.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.databinding.ActivityAnalysisBinding
import com.lytree.qbank.ui.paper.PaperDetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全部解析：成绩报告页不再直接铺一长串解析，想整体过一遍时进这里。
 *
 * 每道题的题干 / 我的答案 / 正确答案 / 解析按题序排开，点卡片仍然可以跳到试卷里的原题
 * （材料、配图、表格只有试卷详情渲染得全）。
 */
class AnalysisActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORD_ID = "record_id"
    }

    private lateinit var binding: ActivityAnalysisBinding
    private lateinit var db: QBankDb
    private var recordId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenAdapt.applyOrientation(this)
        binding = ActivityAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)
        binding.titleBar.setTitle(getString(R.string.report_all_analysis_title))

        recordId = intent.getLongExtra(EXTRA_RECORD_ID, 0L)
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

    private fun render(record: ExamRecord, questionTimes: Map<Long, Int>) {
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.itemAnimator = null
        binding.list.adapter = ReportAdapter(
            record.results,
            record.marked,
            questionTimes
        ) { position -> openInPaper(record, position) }
    }

    /** 点某道题 → 回试卷里定位到原题（材料 / 选项 / 图片都在那边才是完整的） */
    private fun openInPaper(record: ExamRecord, position: Int) {
        val qid = record.results.getOrNull(position)?.question?.id ?: return
        if (record.paperId <= 0L) {
            Toast.makeText(this, R.string.report_paper_missing, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, PaperDetailActivity::class.java).apply {
                putExtra(PaperDetailActivity.EXTRA_PAPER_ID, record.paperId)
                putExtra(PaperDetailActivity.EXTRA_PAPER_NAME, record.displayTitle)
                putExtra(PaperDetailActivity.EXTRA_FOCUS_QUESTION_ID, qid)
                putExtra(PaperDetailActivity.EXTRA_RECORD_ID, record.id)
            }
        )
    }
}
