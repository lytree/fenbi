package com.lytree.qbank.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.AnswerResult
import com.lytree.qbank.databinding.ActivityReportBinding
import com.lytree.qbank.databinding.ItemReportQuestionBinding
import com.lytree.qbank.ui.record.RecordAdapter
import java.util.Locale

/**
 * 成绩报告：得分 / 正确率 / 用时 + 逐题解析。
 */
class ReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORD_ID = "record_id"
    }

    private lateinit var binding: ActivityReportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val id = intent.getLongExtra(EXTRA_RECORD_ID, 0L)
        val record = QBankDb.get(this).getRecord(id)
        if (record == null) {
            finish(); return
        }

        binding.tvScore.text = String.format(Locale.CHINA, "%.1f", record.score)
        binding.tvFull.text = "满分 ${trim(record.fullScore)} 分"
        val rate = if (record.answeredCount > 0) record.correctCount * 100 / record.answeredCount else 0
        binding.tvCorrectRate.text = "正确率\n$rate%"
        binding.tvRight.text = "答对\n${record.correctCount}"
        binding.tvWrong.text = "答错\n${record.totalCount - record.answeredCount - record.partialCount}"
        binding.tvTime.text = "用时\n${RecordAdapter.fmtDuration(record.durationSec)}"

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = ReportAdapter(record.results)
    }

    private fun trim(v: Double) =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.CHINA, "%.1f", v)
}

class ReportAdapter(private val items: List<AnswerResult>) :
    RecyclerView.Adapter<ReportAdapter.VH>() {

    class VH(val binding: ItemReportQuestionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemReportQuestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val ctx = holder.itemView.context
        holder.binding.tvIndex.text = "第 ${position + 1} 题 · ${r.question.type.label}"
        holder.binding.tvStem.text = r.question.stem

        val (label, color) = when (r.status) {
            "right" -> "正确" to R.color.right
            "partial" -> "部分得分" to R.color.partial
            "blank" -> "未作答" to R.color.blank
            else -> "错误" to R.color.wrong
        }
        holder.binding.tvStatus.text = label
        holder.binding.tvStatus.setTextColor(ContextCompat.getColor(ctx, color))

        holder.binding.tvMine.text = "你的答案：${r.userAnswer.ifBlank { "未作答" }}" +
            "　得分 ${fmt(r.earned)}/${fmt(r.full)}"
        holder.binding.tvCorrect.text = "正确答案：${r.question.displayAnswer()}"

        if (r.question.analysis.isNotBlank()) {
            holder.binding.tvAnalysis.visibility = android.view.View.VISIBLE
            holder.binding.tvAnalysis.text = "解析：${r.question.analysis}"
        } else {
            holder.binding.tvAnalysis.visibility = android.view.View.GONE
        }
    }

    private fun fmt(v: Double) =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.CHINA, "%.1f", v)
}
