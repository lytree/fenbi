package com.lytree.qbank.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
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
 *
 * 试卷常常是先导入、答案后补，所以这里也是补答案的主入口：
 * 导入答案后会按记录里的作答「重判」，不需要重做一遍。
 */
class ReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORD_ID = "record_id"
    }

    private lateinit var binding: ActivityReportBinding
    private lateinit var db: QBankDb
    private var recordId = 0L

    /** 补答案：选文件 → 匹配 → 写库 → 重判本记录 */
    private val answerPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAnswers(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)
        binding.titleBar.setTitle("成绩报告")

        recordId = intent.getLongExtra(EXTRA_RECORD_ID, 0L)
        val record = db.getRecord(recordId)
        if (record == null) {
            finish(); return
        }

        binding.titleBar.addTextAction(getString(R.string.action_import_answer)) { launchAnswerPicker() }

        val pending = record.pendingCount
        binding.tvPendingHint.visibility = if (pending > 0) View.VISIBLE else View.GONE
        if (pending > 0) {
            binding.tvPendingHint.text = getString(R.string.pending_answer_report_hint, pending)
        }

        binding.tvScore.text = String.format(Locale.CHINA, "%.1f", record.score)
        binding.tvFull.text = when {
            // 整卷都没有答案时「满分 0 分」读起来像算错了，换成如实描述
            pending > 0 && record.fullScore <= 0.0 -> "暂无可判分的题目 · $pending 题待补答案"
            pending > 0 -> getString(R.string.pending_answer_full, trim(record.fullScore), pending)
            else -> "满分 ${trim(record.fullScore)} 分"
        }
        val rate = if (record.answeredCount > 0) record.correctCount * 100 / record.answeredCount else 0
        binding.tvCorrectRate.text = metric("正确率", "$rate%")
        binding.tvRight.text = metric("答对", "${record.correctCount}")
        // 答错只统计「答了但没对」，未作答不算答错；待补答案的题本来就不判分
        val wrong = (record.answeredCount - record.correctCount - record.partialCount).coerceAtLeast(0)
        binding.tvWrong.text = metric("答错", "$wrong")
        binding.tvTime.text = metric("用时", RecordAdapter.fmtDuration(record.durationSec))

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = ReportAdapter(record.results)
    }

    private fun launchAnswerPicker() {
        try {
            answerPicker.launch(AnswerImportUi.MIME)
        } catch (_: Exception) {
            answerPicker.launch(arrayOf("*/*"))
        }
    }

    private fun importAnswers(uri: Uri) {
        val record = db.getRecord(recordId) ?: return
        // 优先按试卷当前题目匹配（答案可能刚被别的入口改过），试卷没了才退回记录里的快照
        val questions = db.getPaper(record.paperId)?.questions
            ?: record.results.map { it.question }
        AnswerImportUi.handle(this, db, questions, uri) {
            // 答案已更新 → 按这条记录里的作答重判，然后整页重建数据显示对比结果
            db.rejudgeRecord(recordId)
            recreate()
        }
    }

    /** 指标：小号灰标签 + 大号加粗数值（同一 TextView 内两级字号） */
    private fun metric(label: String, value: String): android.text.SpannableString {
        val raw = "$label\n$value"
        return android.text.SpannableString(raw).apply {
            val meta = ContextCompat.getColor(this@ReportActivity, R.color.fb_text_meta)
            val title = ContextCompat.getColor(this@ReportActivity, R.color.fb_text_title)
            setSpan(android.text.style.ForegroundColorSpan(meta), 0, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.RelativeSizeSpan(1.55f), label.length + 1, raw.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), label.length + 1, raw.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(title), label.length + 1, raw.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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
            "pending" -> ctx.getString(R.string.pending_answer) to R.color.partial
            else -> "错误" to R.color.wrong
        }
        holder.binding.tvStatus.text = label
        holder.binding.tvStatus.setTextColor(ContextCompat.getColor(ctx, color))

        holder.binding.tvMine.text = "你的答案：${r.userAnswer.ifBlank { "未作答" }}" +
            if (r.pending) "" else "　得分 ${fmt(r.earned)}/${fmt(r.full)}"
        holder.binding.tvCorrect.text =
            if (r.pending) ctx.getString(R.string.pending_answer_empty)
            else "正确答案：${r.question.displayAnswer()}"

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
