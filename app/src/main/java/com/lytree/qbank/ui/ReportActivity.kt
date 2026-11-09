package com.lytree.qbank.ui

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import com.lytree.qbank.databinding.ItemReportNumberBinding
import com.lytree.qbank.ui.record.RecordAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 成绩报告：得分 / 正确率 / 用时 + 模块正确率 + 逐题解析入口。
 *
 * 试卷常常是先导入、答案后补，所以这里也是补答案的主入口：
 * 导入答案后会按记录里的作答「重判」，不需要重做一遍。
 * 逐题解析不在这页展开——点入口去 [AnalysisActivity] 单独看。
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
        // 每题用时只在解析页用，这里只读记录本身
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
        setupAnalysisEntry(record)
    }

    /**
     * 逐题解析的两个入口 + 「全部题目」题号卡。
     *
     * 解析本身不压在本页：报告上已经有成绩、模块正确率、补答案、错题重练这些，再往下挂一长串题卡
     * 只会让页面越滚越长。这里只负责「怎么进去」——点题号直接翻到那一题，底部两个按钮走两个档位。
     *
     * 两档进的是同一页（[AnalysisActivity]）、同一套题卡（题号 / 状态 / 题干 / 我的答案 /
     * 正确答案 / 解析，连一屏一题和底部页码都一样），差别只有「错题解析」把做对的题筛掉，
     * 所以两个入口看到的观感完全一致。
     */
    private fun setupAnalysisEntry(record: ExamRecord) {
        setupLegend(record)
        setupNumberGrid(record)

        binding.btnWrongAnalysis.setOnClickListener {
            openAnalysis(record, AnalysisActivity.TAB_WRONG)
        }
        binding.btnAllAnalysis.setOnClickListener {
            openAnalysis(record, AnalysisActivity.TAB_ALL)
        }
        // 一道都没做错时「错题解析」进去是空的：按钮直接藏掉，全部解析独占整行
        binding.btnWrongAnalysis.visibility =
            if (record.results.any { it.isWrongResult() }) View.VISIBLE else View.GONE
    }

    /**
     * 图例：只给本次真出现过的状态（全对就不会有「答错」那一项），
     * 颜色与题号圆一一对应，看色就知道题号代表什么。
     */
    private fun setupLegend(record: ExamRecord) {
        val present = listOf("right", "partial", "wrong", "blank", "pending")
            .filter { status -> record.results.any { it.status == status } }

        val box = binding.legendRow
        box.removeAllViews()
        box.visibility = if (present.isEmpty()) View.GONE else View.VISIBLE

        val density = resources.displayMetrics.density
        val dotSize = (8 * density).toInt()
        present.forEachIndexed { index, status ->
            val item = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                if (index > 0) setPadding((16 * density).toInt(), 0, 0, 0)
            }
            val dot = View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = (6 * density).toInt()
                }
                background = ContextCompat.getDrawable(context, R.drawable.bg_fb_num_status)
                ViewCompat.setBackgroundTintList(this, statusTint(status))
            }
            box.addView(dot)
            box.addView(
                TextView(this).apply {
                    text = getString(legendLabel(status))
                    setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX,
                        resources.getDimension(R.dimen.fb_text_body_sm)
                    )
                    setTextColor(ContextCompat.getColor(context, R.color.fb_text_meta))
                }
            )
        }
    }

    /** 题号网格：一屏排得下几列按卡片内宽算（与答题卡同一套自适应），点题号带着题号进解析 */
    private fun setupNumberGrid(record: ExamRecord) {
        if (record.results.isEmpty()) return
        val density = resources.displayMetrics.density
        val cellDp = (resources.getDimension(R.dimen.fb_card_num_size) +
                resources.getDimension(R.dimen.fb_card_num_pad) * 2) / density
        val pagePad = resources.getDimension(R.dimen.fb_page_pad) / density
        val cardPad = resources.getDimension(R.dimen.fb_card_pad) / density
        val usableDp = resources.configuration.screenWidthDp - (pagePad + cardPad) * 2
        val columns = (usableDp / cellDp).toInt().coerceIn(5, 10)

        binding.gridNumbers.layoutManager = GridLayoutManager(this, columns)
        binding.gridNumbers.adapter = NumberGridAdapter(record.results) { no ->
            // 点题号 = 全部解析里那一题（错题档筛过，位置对不上原卷题号）
            openAnalysis(record, AnalysisActivity.TAB_ALL, no)
        }
    }

    /** 进逐题解析：带档位，[startNo] >= 0 时再带一个「翻到第几题」（原卷题号下标） */
    private fun openAnalysis(record: ExamRecord, tab: Int, startNo: Int = -1) {
        startActivity(
            android.content.Intent(this, AnalysisActivity::class.java).apply {
                putExtra(AnalysisActivity.EXTRA_RECORD_ID, record.id)
                putExtra(AnalysisActivity.EXTRA_TAB, tab)
                putExtra(AnalysisActivity.EXTRA_START_NO, startNo)
            }
        )
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

    /** 图例点的底色：深色状态与题号圆同色，浅色状态换深一档，8dp 也看得见 */
    private fun statusTint(status: String): ColorStateList =
        ColorStateList.valueOf(ContextCompat.getColor(this, legendDotColor(status)))

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

/** 题号配色：对 / 部分得分 / 错 是深底白字；未作答与待补答案是浅底深字 */
private fun numberColors(status: String): Pair<Int, Int> = when (status) {
    "right" -> R.color.fb_success to R.color.fb_white
    "partial" -> R.color.fb_warning to R.color.fb_white
    "wrong" -> R.color.fb_danger to R.color.fb_white
    "blank" -> R.color.fb_fill to R.color.fb_text_secondary
    else -> R.color.fb_warning_bg to R.color.fb_warning
}

/**
 * 图例小圆点的颜色：深色状态直接取题号圆底色，浅色状态换成深一档的令牌，
 * 否则 8dp 的小圆在白色卡片上等于没画。
 */
private fun legendDotColor(status: String): Int = when (status) {
    "right" -> R.color.fb_success
    "partial" -> R.color.fb_warning
    "wrong" -> R.color.fb_danger
    "blank" -> R.color.fb_text_placeholder
    else -> R.color.fb_warning
}

/** 答了但完全没对——与报告页「答错」统计、错题解析的口径一致（未作答 / 部分得分不算） */
private fun AnswerResult.isWrongResult() =
    !pending && userAnswer.isNotBlank() && !correct && !partial

/**
 * 「全部题目」的题号网格：一个格子一个 40dp 圆，底色按判分状态上色。
 * 点击回传的是原卷题号下标，报告页拿它去解析页定位，不做任何位置换算。
 */
private class NumberGridAdapter(
    private val results: List<AnswerResult>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<NumberGridAdapter.VH>() {

    class VH(val binding: ItemReportNumberBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(
            ItemReportNumberBinding.inflate(
                android.view.LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun getItemCount() = results.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val result = results[position]
        val ctx = holder.itemView.context
        val num = holder.binding.tvNum
        val (bg, fg) = numberColors(result.status)

        num.text = (position + 1).toString()
        // 未作答用带描边的浅灰圆（纯灰底在白卡上看不出形状），其余按状态填色
        if (result.status == "blank") {
            num.setBackgroundResource(R.drawable.bg_fb_num_blank_chip)
            num.backgroundTintList = null
        } else {
            num.setBackgroundResource(R.drawable.bg_fb_num_status)
            ViewCompat.setBackgroundTintList(num, ColorStateList.valueOf(ContextCompat.getColor(ctx, bg)))
        }
        num.setTextColor(ContextCompat.getColor(ctx, fg))
        num.contentDescription = ctx.getString(
            R.string.report_number_desc, position + 1, ctx.getString(legendLabel(result.status))
        )
        num.setOnClickListener { onClick(position) }
    }
}

/** 状态 → 图例文案（题号的无障碍描述也用它） */
private fun legendLabel(status: String) = when (status) {
    "right" -> R.string.report_legend_right
    "partial" -> R.string.report_legend_partial
    "wrong" -> R.string.report_legend_wrong
    "blank" -> R.string.report_legend_blank
    else -> R.string.pending_answer
}
