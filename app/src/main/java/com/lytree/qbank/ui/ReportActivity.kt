package com.lytree.qbank.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.AnswerResult
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.databinding.ActivityReportBinding
import com.lytree.qbank.databinding.ItemCardNumBinding
import com.lytree.qbank.ui.record.RecordAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 成绩报告：得分 / 正确率 / 用时 + 全部题目对错一览 + 解析入口。
 *
 * 这里不再直接铺「逐题解析」那一长串卡片——一屏先给整卷的答题对错（答对绿、答错红），
 * 想深看再选：点某个题号 = 单题解析弹层（可上下翻），「错题解析」只翻没答对的题，
 * 「全部解析」= 整页顺序过一遍。
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
        // 题干 / 答案 / 判定一次读完再装配报告；每题用时留给解析页自己读
        lifecycleScope.launch {
            val record = withContext(Dispatchers.IO) { db.getRecord(recordId) }
            if (isFinishing || isDestroyed) return@launch
            if (record == null) {
                finish(); return@launch
            }
            renderReport(record)
        }
    }

    private fun renderReport(record: ExamRecord) {
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
        setupOverview(record)

        // 错题解析：只在本次「答了但没全对」的题里翻，口径与上面「答错」统计、错题重练一致；
        // 未作答与待补答案不算错题，想看得去点对应的题号
        binding.btnStepAnalysis.setOnClickListener {
            val wrongIdx = record.results.indices.filter { i ->
                val r = record.results[i]
                !r.pending && r.userAnswer.isNotBlank() && !r.correct
            }
            if (wrongIdx.isEmpty()) {
                Toast.makeText(this, R.string.report_retry_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openReview(record, wrongIdx, 0)
        }
        // 全部解析：整页按题序看，适合考完复盘
        binding.btnAllAnalysis.setOnClickListener {
            startActivity(
                Intent(this, AnalysisActivity::class.java).apply {
                    putExtra(AnalysisActivity.EXTRA_RECORD_ID, record.id)
                }
            )
        }
    }

    /**
     * 全部题目：答题卡式网格，一格一题。
     * 答对绿、答错红、未作答灰、部分得分橙、待补答案浅橙，整卷错在哪一眼就能扫出来。
     */
    private fun setupOverview(record: ExamRecord) {
        // 本次标记过的题在格子上带一个橙点，标题里先给总数，免得逐个找
        binding.tvOverviewTitle.text = if (record.marked.isEmpty()) {
            getString(R.string.report_overview_title)
        } else {
            getString(R.string.report_overview_title_marked, record.marked.size)
        }
        setupLegend(record)

        // 格子 48dp（40dp 圆 + 两侧 4dp），按屏宽扣掉页面左右 16dp 与卡片 16dp 内边距后算列数
        val cellDp = (resources.getDimension(R.dimen.fb_card_num_size)
                + resources.getDimension(R.dimen.fb_card_num_pad) * 2) / resources.displayMetrics.density
        val padDp = (resources.getDimension(R.dimen.fb_page_pad) * 2
                + resources.getDimension(R.dimen.fb_card_pad) * 2) / resources.displayMetrics.density
        val usableDp = resources.configuration.screenWidthDp - padDp
        val columns = (usableDp / cellDp).toInt().coerceIn(5, 12)

        binding.grid.layoutManager = GridLayoutManager(this, columns)
        binding.grid.itemAnimator = null
        binding.grid.adapter = ReportGridAdapter(record.results, record.marked) { position ->
            // 点题号 = 进解析页，从这道题开始按原卷顺序往后翻
            openReview(record, record.results.indices.toList(), position)
        }
    }

    /**
     * 进解析页。版式与答题页完全相同（[QuestionReviewActivity]），
     * [numbers] 是要翻的题（原卷下标）：点题号给整卷，错题解析只给错题。
     */
    private fun openReview(record: ExamRecord, numbers: List<Int>, start: Int) {
        startActivity(
            Intent(this, QuestionReviewActivity::class.java).apply {
                putExtra(QuestionReviewActivity.EXTRA_RECORD_ID, record.id)
                putExtra(QuestionReviewActivity.EXTRA_NUMBERS, numbers.toIntArray())
                putExtra(QuestionReviewActivity.EXTRA_START, start)
            }
        )
    }

    /** 图例：只列本卷实际出现的状态，全列 5 个反而要占两行、还都是没意义的灰点 */
    private fun setupLegend(record: ExamRecord) {
        val box = binding.legendBox
        box.removeAllViews()
        val statuses = record.results.map { it.status }.toSet()
        val entries = ArrayList<Pair<String, Int>>()
        if ("right" in statuses) entries += getString(R.string.report_legend_right) to R.color.right
        if ("wrong" in statuses) entries += getString(R.string.report_legend_wrong) to R.color.wrong
        if ("partial" in statuses) entries += getString(R.string.report_legend_partial) to R.color.partial
        if ("blank" in statuses) entries += getString(R.string.report_legend_blank) to R.color.blank
        if ("pending" in statuses) entries += getString(R.string.report_legend_pending) to R.color.fb_warning
        if (entries.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE

        val rowGap = (6 * resources.displayMetrics.density).toInt()
        // 每行最多 3 个，最后一行补空位，列与列之间才能对齐
        entries.chunked(3).forEachIndexed { rowIndex, row ->
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { (label, color) -> rowView.addView(legendItem(label, color)) }
            repeat(3 - row.size) {
                rowView.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            }
            box.addView(
                rowView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (rowIndex > 0) topMargin = rowGap }
            )
        }
    }

    /** 图例一项：8dp 圆点 + 11sp 说明，占 1/3 行宽 */
    private fun legendItem(label: String, colorRes: Int): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val dot = View(context).apply {
                background = ContextCompat.getDrawable(context, R.drawable.bg_num_blank)
                ViewCompat.setBackgroundTintList(
                    this,
                    ColorStateList.valueOf(ContextCompat.getColor(context, colorRes))
                )
            }
            addView(dot, LinearLayout.LayoutParams((8 * density).toInt(), (8 * density).toInt()))
            addView(
                TextView(context).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.fb_text_tag))
                    setTextColor(ContextCompat.getColor(context, R.color.fb_text_meta))
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (5 * density).toInt() }
            )
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
                    TypedValue.COMPLEX_UNIT_PX,
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
                Toast.makeText(this, "生成错题卷失败", Toast.LENGTH_SHORT).show()
                return@addTextAction
            }
            startActivity(
                Intent(this, ExamActivity::class.java).apply {
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
            Intent(this, AnswerEntryActivity::class.java).apply {
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
        // 读记录 + 读卷都是库操作：整段放后台，回到主线程再更新界面
        lifecycleScope.launch {
            val questions = withContext(Dispatchers.IO) {
                val record = db.getRecord(recordId) ?: return@withContext null
                // 优先按试卷当前题目匹配（答案可能刚被别的入口改过），试卷没了才退回记录里的快照
                db.getPaper(record.paperId)?.questions ?: record.results.map { it.question }
            } ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            AnswerImportUi.handle(this@ReportActivity, db, questions, uri) {
                // 答案已更新 → 按这条记录里的作答重判，然后整页重建数据显示对比结果
                db.rejudgeRecord(recordId)
                recreate()
            }
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
 * 全部题目的题号网格（答题卡样式）。
 *
 * 颜色口径就一条：**答对绿、答错红**；未作答灰、部分得分橙、待补答案浅橙。
 * 圆点用底色填充（不走实心 drawable），新状态加进来只要在 [fillRes] / [textRes] 里补一行。
 */
class ReportGridAdapter(
    private val items: List<AnswerResult>,
    private val marked: Set<Long> = emptySet(),
    private val onClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<ReportGridAdapter.VH>() {

    class VH(val binding: ItemCardNumBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCardNumBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = holder.binding
        val ctx = b.root.context
        val r = items[position]
        val status = r.status

        b.tvNum.text = (position + 1).toString()
        ViewCompat.setBackgroundTintList(
            b.tvNum,
            ColorStateList.valueOf(ContextCompat.getColor(ctx, fillRes(status)))
        )
        b.tvNum.setTextColor(ContextCompat.getColor(ctx, textRes(status)))
        b.vMark.visibility = if (r.question.id in marked) View.VISIBLE else View.GONE
        b.vFav.visibility = View.GONE

        // 无障碍：读屏念出来是「第 3 题 错误」，而不是光一个数字
        b.root.contentDescription = "第 ${position + 1} 题 ${ReportAdapter.statusStyle(ctx, r).first}"
        b.root.setOnClickListener { onClick?.invoke(position) }
    }

    /** 格子底色：对=绿 / 错=红 / 部分得分=橙 / 未作答=浅灰 / 待补答案=浅橙 */
    private fun fillRes(status: String) = when (status) {
        "right" -> R.color.right
        "wrong" -> R.color.wrong
        "partial" -> R.color.partial
        "blank" -> R.color.fb_card_num_blank_bg
        else -> R.color.fb_warning_bg
    }

    /** 填充色重的那几种用白字，浅底用深字 */
    private fun textRes(status: String) = when (status) {
        "right", "wrong", "partial" -> android.R.color.white
        "blank" -> R.color.fb_card_num_blank_text
        else -> R.color.fb_warning
    }
}
