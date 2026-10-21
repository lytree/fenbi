package com.lytree.qbank.ui

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ActivityExamBinding
import com.lytree.qbank.engine.Judge
import java.util.Locale

/**
 * 答题页。对齐粉笔真机 `com.fenbi.android.exercise.objective.exercise.ObjectiveExerciseActivity`（竖屏）：
 *
 * - **顶栏**：X 关闭 / 闹钟+倒计时 / 居中标题「试卷名（题数）」/ 交卷 / 答题卡 / 更多
 * - **内容**：连续滚动的多题浏览（粉笔默认模式），每题 = 题目行（题号+题型+标记/笔记/收藏）+ 题干 + 选项
 * - **更多面板**：字号（小/中/大）、日间/夜间、多题浏览/单题浏览
 * - **答题卡**：底部弹层，圆形题号 + 按科目分组 + 通栏交卷
 *
 * 交互要点：
 * - 手写标注（触控笔）叠在题目之上，笔迹按题持久化，切题不丢；
 * - 标记 / 收藏 / 笔记按题保存；
 * - 倒计时到点自动交卷，提前 5 分钟提醒。
 */
class ExamActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAPER_ID = "paper_id"
        const val EXTRA_PAPER_NAME = "paper_name"
        private const val REQ_CARD = 1001

        private const val PREFS = "exam_prefs"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_NIGHT = "night_mode"
        private const val KEY_MULTI_BROWSE = "multi_browse"

        /** 字号档位：小 / 中 / 大 */
        private val FONT_SCALES = floatArrayOf(0.9f, 1.0f, 1.15f)
    }

    private lateinit var binding: ActivityExamBinding
    private lateinit var db: QBankDb

    private var questions: List<Question> = emptyList()
    private val answers = HashMap<Long, String>()
    private val marked = HashSet<Long>()
    private val favorites = HashSet<Long>()
    private var timer: CountDownTimer? = null
    private var durationMin = 0
    private var startedAt = 0L
    private var submitted = false
    private var revealAnswers = false
    private var inkMode = false

    /** 字号缩放系数 */
    private var fontScale = 1f
    /** 是否多题浏览（粉笔默认多题） */
    private var multiBrowse = true
    /** 夜间模式 */
    private var nightMode = false

    private lateinit var inkToolbar: InkToolbarController
    private lateinit var adapter: QuestionListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)

        loadPrefs()

        val paperId = intent.getLongExtra(EXTRA_PAPER_ID, 0L)
        val paper = db.getPaper(paperId)
        if (paper == null || paper.questions.isEmpty()) {
            android.widget.Toast.makeText(this, "试卷不存在或为空", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        questions = paper.questions
        durationMin = paper.durationMin
        startedAt = System.currentTimeMillis()

        setupList()
        setupTopBar()
        setupMorePanel()
        setupInk()

        updateHeader()
        startTimer()
    }

    // ---------------- 装配 ----------------

    private fun setupList() {
        adapter = QuestionListAdapter(
            questions = questions,
            answers = answers,
            marked = marked,
            favorites = favorites,
            revealAnswers = revealAnswers,
            policyProvider = { db.getPolicy() },
            fontScale = fontScale,
            onMarkToggle = { q -> toggleMark(q) },
            onFavoriteToggle = { q -> toggleFavorite(q) },
            onNoteClicked = { q -> showNoteDialog(q) },
            onAnswerChanged = { }
        )
        binding.questionList.layoutManager = LinearLayoutManager(this)
        binding.questionList.adapter = adapter
        binding.questionList.itemAnimator = null
    }

    private fun setupTopBar() {
        binding.btnClose.setOnClickListener { confirmExit() }
        binding.btnCard.setOnClickListener { openAnswerCard() }
        binding.btnSubmitTop.setOnClickListener { confirmSubmit() }
        binding.btnMore.setOnClickListener { toggleMorePanel() }
    }

    /**
     * 更多面板：字号 / 日夜 / 浏览模式。
     * 交互对齐粉笔——点哪个就切哪个，选中态即时高亮。
     */
    private fun setupMorePanel() {
        binding.btnFontSmall.setOnClickListener { setFontScale(0) }
        binding.btnFontMedium.setOnClickListener { setFontScale(1) }
        binding.btnFontLarge.setOnClickListener { setFontScale(2) }
        binding.btnDay.setOnClickListener { setNightMode(false) }
        binding.btnNight.setOnClickListener { setNightMode(true) }
        binding.btnMultiBrowse.setOnClickListener { setBrowseMode(true) }
        binding.btnSingleBrowse.setOnClickListener { setBrowseMode(false) }
        refreshMorePanelState()
    }

    private fun toggleMorePanel() {
        binding.morePanel.visibility =
            if (binding.morePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun setFontScale(idx: Int) {
        fontScale = FONT_SCALES[idx.coerceIn(0, FONT_SCALES.size - 1)]
        savePrefs()
        refreshMorePanelState()
        adapter.notifyDataSetChanged()
    }

    private fun setNightMode(on: Boolean) {
        if (nightMode == on) return
        nightMode = on
        savePrefs()
        refreshMorePanelState()
        // 夜间模式：整页反色配色（粉笔是纯色切换，这里用背景/文字色翻转实现）
        applyNightMode()
    }

    private fun setBrowseMode(multi: Boolean) {
        if (multiBrowse == multi) return
        multiBrowse = multi
        savePrefs()
        refreshMorePanelState()
        // 粉笔的多题浏览=连续滚动，单题浏览=一屏一题。当前实现统一为连续滚动，
        // 单题模式通过「每次只显示一题」的方式收敛——用滚动定位 + 隐藏其余题实现。
        applyBrowseMode()
    }

    private fun setupInk() {
        inkToolbar = InkToolbarController(
            inkOverlay = binding.inkOverlay,
            inkBar = binding.inkBar,
            btnInkFinger = binding.btnInkFinger,
            btnInkDone = binding.btnInkDone,
            styleInkButton = { on ->
                binding.inkBar.alpha = if (on) 1f else 0.95f
            }
        )
        inkToolbar.onModeChanged = { on ->
            inkMode = on
        }
        binding.btnInkUndo.setOnClickListener { binding.inkOverlay.undo() }
        binding.btnInkClear.setOnClickListener { binding.inkOverlay.clear() }
    }

    // ---------------- 顶栏状态 ----------------

    private fun updateHeader() {
        val name = intent.getStringExtra(EXTRA_PAPER_NAME) ?: defaultPaperName()
        binding.tvPaperName.text = getString(R.string.paper_title, name, questions.size)
    }

    private fun defaultPaperName(): String {
        val paper = db.getPaper(intent.getLongExtra(EXTRA_PAPER_ID, 0L))
        return paper?.name ?: "练习"
    }

    // ---------------- 计时 ----------------

    private fun startTimer() {
        if (durationMin <= 0) {
            binding.tvTimer.text = "不限时"
            return
        }
        val total = durationMin * 60_000L
        timer = object : CountDownTimer(total, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val s = millisUntilFinished / 1000
                binding.tvTimer.text = String.format(Locale.CHINA, "%02d:%02d", s / 60, s % 60)
                if (s == 300L) {
                    android.widget.Toast.makeText(
                        this@ExamActivity, "离考试截止时间还剩5分钟，请合理安排答题时间",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onFinish() {
                binding.tvTimer.text = "00:00"
                android.widget.Toast.makeText(
                    this@ExamActivity, "时间到，自动交卷", android.widget.Toast.LENGTH_LONG
                ).show()
                submit(auto = true)
            }
        }.also { it.start() }
    }

    // ---------------- 标记 / 收藏 / 笔记 ----------------

    private fun toggleMark(q: Question) {
        if (!marked.add(q.id)) marked.remove(q.id)
        adapter.notifyDataSetChanged()
    }

    private fun toggleFavorite(q: Question) {
        if (!favorites.add(q.id)) favorites.remove(q.id)
        adapter.notifyDataSetChanged()
    }

    private fun showNoteDialog(q: Question) {
        val input = EditText(this).apply {
            setText(db.getNote(q.id))
            hint = getString(R.string.note_hint)
            minLines = 4
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.note_title))
            .setView(wrap)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                db.setNote(q.id, input.text.toString().trim())
                android.widget.Toast.makeText(this, R.string.note_saved, android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------------- 更多面板：状态同步 ----------------

    private fun refreshMorePanelState() {
        val idx = FONT_SCALES.indexOfFirst { it == fontScale }.let { if (it < 0) 1 else it }
        styleSegment(binding.btnFontSmall, idx == 0)
        styleSegment(binding.btnFontMedium, idx == 1)
        styleSegment(binding.btnFontLarge, idx == 2)

        styleSegment(binding.btnDay, !nightMode)
        styleSegment(binding.btnNight, nightMode)

        styleSegment(binding.btnMultiBrowse, multiBrowse)
        styleSegment(binding.btnSingleBrowse, !multiBrowse)
    }

    /** segmented 项的选中态样式（粉笔：选中项白底蓝字，未选中灰底灰字） */
    private fun styleSegment(view: TextView, selected: Boolean) {
        view.setBackgroundResource(if (selected) R.drawable.bg_fb_segment_selected else R.drawable.bg_fb_segment)
        view.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.fb_segment_text_sel else R.color.fb_segment_text
            )
        )
        // 字号档位用 Aa 的大小差异表达
        view.textSize = when (view.id) {
            R.id.btnFontSmall -> 12f
            R.id.btnFontLarge -> 18f
            else -> 15f
        }
    }

    // ---------------- 浏览模式 ----------------

    /**
     * 多题浏览：连续滚动所有题。
     * 单题浏览：一屏一题，用一个 ViewPager2 承载（左右滑动切题）。
     */
    private fun applyBrowseMode() {
        if (multiBrowse) {
            binding.questionList.visibility = View.VISIBLE
            binding.pager.visibility = View.GONE
        } else {
            binding.questionList.visibility = View.GONE
            binding.pager.visibility = View.VISIBLE
            binding.pager.adapter = pagerAdapter
            binding.pager.setCurrentItem(currentPagerIndex, false)
        }
    }

    private var currentPagerIndex = 0

    private val pagerAdapter by lazy {
        QuestionPagerAdapter(
            questions = questions,
            answers = answers,
            marked = marked,
            revealAnswers = revealAnswers,
            policyProvider = { db.getPolicy() },
            onAnswerChanged = { },
            onNoteClicked = { q -> showNoteDialog(q) }
        )
    }

    // ---------------- 夜间模式 ----------------

    private fun applyNightMode() {
        val night = nightMode
        val bg = ContextCompat.getColor(this, if (night) R.color.fb_night_bg else R.color.fb_page_bg)
        val card = ContextCompat.getColor(this, if (night) R.color.fb_night_card else R.color.fb_card_bg)
        val text = ContextCompat.getColor(this, if (night) R.color.fb_night_text else R.color.fb_text_body)
        val title = ContextCompat.getColor(this, if (night) R.color.fb_night_text else R.color.fb_text_title)

        binding.root.setBackgroundColor(bg)
        binding.topBar.setBackgroundColor(card)
        binding.tvPaperName.setTextColor(title)
        binding.tvTimer.setTextColor(text)
        adapter.notifyDataSetChanged()
    }

    // ---------------- 偏好持久化 ----------------

    private fun loadPrefs() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        fontScale = FONT_SCALES.getOrElse(p.getInt(KEY_FONT_SCALE, 1)) { 1f }
        nightMode = p.getBoolean(KEY_NIGHT, false)
        multiBrowse = p.getBoolean(KEY_MULTI_BROWSE, true)
    }

    private fun savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().apply {
            putInt(KEY_FONT_SCALE, FONT_SCALES.indexOfFirst { it == fontScale }.let { if (it < 0) 1 else it })
            putBoolean(KEY_NIGHT, nightMode)
            putBoolean(KEY_MULTI_BROWSE, multiBrowse)
        }.apply()
    }

    // ---------------- 答题卡 ----------------

    private fun openAnswerCard() {
        // 答题卡用的是「题号位置」(0-based)，不是数据库里的 question.id
        val answeredPos = questions.indices.filter { (answers[questions[it].id] ?: "").isNotBlank() }
        val markedPos = questions.indices.filter { questions[it].id in marked }
        val favoritePos = questions.indices.filter { questions[it].id in favorites }
        val intent = Intent(this, AnswerCardActivity::class.java).apply {
            putExtra(AnswerCardActivity.EXTRA_COUNT, questions.size)
            putExtra(AnswerCardActivity.EXTRA_ANSWERED, answeredPos.toIntArray())
            putExtra(AnswerCardActivity.EXTRA_MARKED, markedPos.toIntArray())
            putExtra(AnswerCardActivity.EXTRA_FAVORITE, favoritePos.toIntArray())
            putExtra(AnswerCardActivity.EXTRA_GROUPS, groupLabels())
        }
        startActivityForResult(intent, REQ_CARD)
    }

    /** 按科目分组，得到每题所属的分组标题（与粉笔「政治理论/常识判断…」一致） */
    private fun groupLabels(): ArrayList<String> {
        val out = ArrayList<String>()
        for (q in questions) {
            val label = q.subject.ifBlank { q.knowledge.ifBlank { "未分类" } }
            out.add(label)
        }
        return out
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CARD && resultCode == RESULT_OK && data != null) {
            val jump = data.getIntExtra(AnswerCardActivity.EXTRA_JUMP, -1)
            if (jump in questions.indices) {
                if (multiBrowse) {
                    binding.questionList.scrollToPosition(jump)
                } else {
                    currentPagerIndex = jump
                    binding.pager.setCurrentItem(jump, false)
                }
            }
        }
    }

    // ---------------- 交卷 ----------------

    private fun confirmExit() {
        if (submitted) { finish(); return }
        val unanswered = questions.count { (answers[it.id] ?: "").isBlank() }
        val msg = if (unanswered > 0) {
            "你还有 $unanswered 道题目未做完，确定退出吗？"
        } else {
            "确定退出吗？"
        }
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("退出") { _, _ -> finish() }
            .setNegativeButton("继续答题", null)
            .show()
    }

    private fun confirmSubmit() {
        val unanswered = questions.count { (answers[it.id] ?: "").isBlank() }
        val msg = if (unanswered > 0) {
            "你还有 $unanswered 道题目未作答，确定交卷吗？"
        } else {
            "确定交卷并查看结果吗？"
        }
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ -> submit(auto = false) }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun submit(auto: Boolean) {
        if (submitted) return
        submitted = true
        timer?.cancel()
        revealAnswers = true
        adapter.notifyDataSetChanged()

        val policy = db.getPolicy()
        val results = questions.map { Judge.judge(it, answers[it.id] ?: "", policy) }
        val summary = Judge.summarize(results)
        val now = System.currentTimeMillis()

        val record = ExamRecord(
            paperId = intent.getLongExtra(EXTRA_PAPER_ID, 0L),
            name = "练习记录",
            mode = if (auto) "超时自动交卷" else "手动交卷",
            startedAt = startedAt,
            submittedAt = now,
            durationSec = ((now - startedAt) / 1000).toInt(),
            totalCount = summary.total,
            answeredCount = summary.answered,
            correctCount = summary.correct,
            partialCount = summary.partial,
            score = summary.score,
            fullScore = summary.fullScore
        )
        val rid = db.insertRecord(record, results)

        startActivity(
            Intent(this, ReportActivity::class.java).apply {
                putExtra(ReportActivity.EXTRA_RECORD_ID, rid)
            }
        )
        finish()
    }

    override fun onBackPressed() {
        if (submitted) { super.onBackPressed(); return }
        confirmExit()
    }
}
