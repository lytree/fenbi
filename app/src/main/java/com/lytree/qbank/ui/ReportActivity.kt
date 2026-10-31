package com.lytree.qbank.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.AnswerResult
import com.lytree.qbank.data.model.ExamRecord
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

    /** 补答案 / 补解析：选文件 → 匹配 → 写库 → 重判本记录 */
    private val answerPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAnswers(uri)
    }

    /** 补录答案：逐题手填（不用答案文件），回来后重建页面显示新成绩 */
    private val fillAnswerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手机锁竖屏 / 平板跟随旋转（方向锁是 Activity 级的，答题页锁了这里也要跟着锁）
        ScreenAdapt.applyOrientation(this)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)
        binding.titleBar.setTitle("成绩报告")

        recordId = intent.getLongExtra(EXTRA_RECORD_ID, 0L)
        val record = db.getRecord(recordId)
        if (record == null) {
            finish(); return
        }

        // 补答案入口：标题栏只留一个「补答案」，点开再选「逐题补录 / 导入文件」——
        // 文字动作挤在一行会压住标题，而缺答案的卷子两个入口都得给。
        binding.titleBar.addTextAction(getString(R.string.action_answer_fix)) {
            pickAnswerEntry(record)
        }

        val pending = record.pendingCount
        binding.tvPendingHint.visibility = if (pending > 0) View.VISIBLE else View.GONE
        if (pending > 0) {
            binding.tvPendingHint.text = getString(R.string.pending_answer_report_hint, pending)
            // 提示条本身就是入口：点一下直接去逐题补录
            binding.tvPendingHint.setOnClickListener { openAnswerEntry(record) }
        } else {
            binding.tvPendingHint.setOnClickListener(null)
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

        setupSubjectStats(record)
        setupRetryWrong(record)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = ReportAdapter(record.results, record.marked)

        // 本次标记过的题在逐题解析里带「已标记」标签，标题里先给个总数，免得逐页翻找
        binding.tvAnalysisTitle.text = if (record.marked.isEmpty()) {
            getString(R.string.report_analysis_title)
        } else {
            getString(R.string.report_analysis_title_marked, record.marked.size)
        }
    }

    /**
     * 分模块正确率（对齐参考应用练习报告）：按题目的「科目 → 知识点」聚合，
     * 已判分的题才进统计，定位薄弱模块。
     */
    private fun setupSubjectStats(record: ExamRecord) {
        data class Stat(var total: Int = 0, var right: Int = 0)

        val groups = LinkedHashMap<String, Stat>()
        for (r in record.results) {
            if (r.pending || r.userAnswer.isBlank()) continue
            val label = r.question.subject.ifBlank { r.question.knowledge.ifBlank { "未分类" } }
            val s = groups.getOrPut(label) { Stat() }
            s.total++
            if (r.correct) s.right++
        }
        if (groups.size <= 1) {
            // 只有一个模块（或全卷没判分）时单独一行正确率没有信息量，不显示
            binding.subjectStatsCard.visibility = View.GONE
            return
        }
        binding.subjectStatsCard.visibility = View.VISIBLE
        val box = binding.subjectStatsBox
        box.removeAllViews()
        val padV = (10 * resources.displayMetrics.density).toInt()
        groups.forEach { (label, s) ->
            val pct = if (s.total > 0) s.right * 100 / s.total else 0
            val tv = TextView(this).apply {
                text = getString(R.string.report_subject_row, label, s.right, s.total, pct)
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(R.dimen.fb_text_body_size)
                )
                setTextColor(ContextCompat.getColor(context, R.color.fb_text_secondary))
                setPadding(4, padV, 4, padV)
            }
            box.addView(tv)
            if (s != groups.values.last()) {
                box.addView(View(this).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.density * 0.5f).toInt().coerceAtLeast(1)
                    )
                    setBackgroundColor(ContextCompat.getColor(context, R.color.fb_divider))
                })
            }
        }
    }

    /** 错题重练：把本次答错 / 部分得分的题按原顺序生成一份新卷，直接开练（对齐参考应用「错题重练」闭环） */
    private fun setupRetryWrong(record: ExamRecord) {
        val wrongIds = record.results
            .filter { !it.pending && it.userAnswer.isNotBlank() && !it.correct }
            .map { it.question.id }
        if (wrongIds.isEmpty()) return
        binding.titleBar.addTextAction(getString(R.string.report_retry_wrong)) {
            val name = "${record.name}·错题重练"
            val newPaperId = db.createPaperFromQuestions(name, wrongIds)
            if (newPaperId == 0L) {
                android.widget.Toast.makeText(this, "生成错题卷失败", android.widget.Toast.LENGTH_SHORT).show()
                return@addTextAction
            }
            startActivity(
                android.content.Intent(this, ExamActivity::class.java).apply {
                    putExtra(ExamActivity.EXTRA_PAPER_ID, newPaperId)
                    putExtra(ExamActivity.EXTRA_PAPER_NAME, name)
                }
            )
        }
    }

    /**
     * 补答案 / 补解析只有两种方式，不用先想清楚手里的文件是答案还是解析：
     * - **逐题补录**：不用准备文件，进补录页把答案填进去（最省事）；
     * - **导入文件**：选一个 Excel，App 自动识别是答案（`题号,答案`）还是解析
     *   （`题号,解析`——只更新解析、不动答案），确认弹窗里会写明。
     */
    private fun pickAnswerEntry(record: ExamRecord) {
        FbDialog.list(
            context = this,
            title = getString(R.string.action_answer_fix),
            items = arrayOf(
                getString(R.string.answer_fix_manual),
                getString(R.string.answer_fix_file)
            ),
            onPick = { which ->
                if (which == 0) openAnswerEntry(record) else launchAnswerPicker()
            }
        )
    }

    /** 逐题补录答案页：保存后本页会按记录里的作答重判并重建 */
    private fun openAnswerEntry(record: ExamRecord) {
        fillAnswerLauncher.launch(
            android.content.Intent(this, AnswerEntryActivity::class.java).apply {
                putExtra(AnswerEntryActivity.EXTRA_PAPER_ID, record.paperId)
                putExtra(AnswerEntryActivity.EXTRA_RECORD_ID, record.id)
            }
        )
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

/**
 * 逐题解析列表。
 *
 * [marked] 是本次作答被标记（细线星）的题 id —— 交卷时随记录落库，
 * 所以回看旧记录也能看出当时标了哪几题，而不只是当轮答题卡上的点。
 */
class ReportAdapter(
    private val items: List<AnswerResult>,
    private val marked: Set<Long> = emptySet()
) :
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
        holder.binding.tvMark.visibility =
            if (r.question.id in marked) android.view.View.VISIBLE else android.view.View.GONE

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
