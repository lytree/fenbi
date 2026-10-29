package com.lytree.qbank.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.data.model.PaperStructure
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ActivitySplitExamBinding
import com.lytree.qbank.databinding.ItemOptionBinding
import com.lytree.qbank.databinding.ItemSplitQuestionBinding
import com.lytree.qbank.engine.Judge
import java.util.Locale

/**
 * 资料分析分屏页。
 *
 * 对应参考应用的「资料分析左右分屏」：左侧是材料（表格渲染成真表格），右侧是这一组材料
 * 对应的若干道题。资料分析的特点是「一道材料带 3~5 问」，逐题翻页时材料会反复出现，
 * 分屏能让材料始终停在左边，做题时可以直接对着数据看。
 *
 * 进入方式：答题页「更多 → 开启左右分屏」，或资料分析题组的入口。
 */
class SplitExamActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAPER_ID = "paper_id"
        const val EXTRA_PAPER_NAME = "paper_name"
        /** 只显示这组题（同一份材料）；为空则显示整卷 */
        const val EXTRA_GROUP_KEY = "group_key"
        private const val REQ_CARD = 1001

        /** 草稿记录的来源标记（存在 records.mode），与答题页的草稿分开 */
        private const val DRAFT_SOURCE = "split"
    }

    private lateinit var binding: ActivitySplitExamBinding
    private lateinit var db: QBankDb

    private var paperId = 0L
    /** 本次分屏练习的记录 id（草稿）：标注按它落库，交卷时转正 */
    private var recordId = 0L
    private var questions: List<Question> = emptyList()
    private lateinit var structure: PaperStructure
    private val answers = HashMap<Long, String>()
    private val marked = HashSet<Long>()
    private var startedAt = 0L
    private var submitted = false
    private var revealAnswers = false
    private var inkMode = false
    private var currentIndex = 0
    /** 本组题在整卷里的起始序号：右栏题号与顶部进度都按整卷编号 */
    private var indexOffset = 0
    private lateinit var inkToolbar: InkToolbarController
    private lateinit var tvIndex: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手机锁竖屏 / 平板跟随旋转（方向锁是 Activity 级的，答题页锁了这里也要跟着锁）
        ScreenAdapt.applyOrientation(this)
        binding = ActivitySplitExamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)

        paperId = intent.getLongExtra(EXTRA_PAPER_ID, 0L)
        val paper = db.getPaper(paperId)
        if (paper == null || paper.questions.isEmpty()) {
            android.widget.Toast.makeText(this, "试卷不存在或为空", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 按材料分组：直接用试卷结构里的材料组——同一材料跨大类会断成两组，
        // 按文本 filter 会把它们并成一组、题号也会错，这里取的正是这题所属的那一组
        structure = PaperStructure(paper.questions)
        val groupKey = intent.getStringExtra(EXTRA_GROUP_KEY)?.trim().orEmpty()
        val group = if (groupKey.isEmpty()) {
            null
        } else {
            structure.groups.firstOrNull {
                it.isMaterialGroup && it.material.material.trim() == groupKey
            }
        }
        questions = group?.questions ?: paper.questions
        // 题号接着整卷往下排（参考应用分屏右栏显示的是 106./107./108.，不是 1./2./3.）
        indexOffset = group?.startIndex ?: 0

        startedAt = System.currentTimeMillis()
        // 标注跟随本次分屏作答：先建（或取回）草稿记录，交卷时转正
        recordId = db.ensureDraftRecord(paperId, DRAFT_SOURCE, startedAt)
        binding.inkOverlay.recordId = recordId
        binding.titleBar.showDivider(false)
        // 对齐参考应用的分屏页：顶栏只留「返回 + 计时」，不放卷名与右侧动作
        binding.titleBar.setTitle("")
        binding.titleBar.onBack = { confirmExit() }
        tvIndex = binding.titleBar.addTextAction("") { }
        binding.titleBar.setTimer("00:00")
        binding.bottomBar.applyNavBarBottomPadding()

        binding.btnCard.setOnClickListener { openAnswerCard() }
        binding.btnSubmit.setOnClickListener { confirmSubmit() }
        binding.btnInk.setOnClickListener { toggleInk() }
        binding.btnInkUndo.setOnClickListener { binding.inkOverlay.undo() }
        binding.btnInkClear.setOnClickListener { binding.inkOverlay.clear() }
        // 左栏只有半屏宽，图 / 表挤在一起时弹到独立页面看（不再用 Dialog 浮层）
        binding.btnMaterialExpand.setOnClickListener {
            val material = groupMaterial()
            if (material.isNotBlank()) {
                startActivity(Intent(this, MaterialPopupActivity::class.java).apply {
                    putExtra(MaterialPopupActivity.EXTRA_MATERIAL, material)
                })
            }
        }

        // 左右占比可调：拖动中间手柄改变左栏宽度（25%～75%），比例记忆到偏好
        setupSplitResize()

        inkToolbar = InkToolbarController(
            inkOverlay = binding.inkOverlay,
            inkBar = binding.inkBar,
            btnInkShade = binding.btnInkShade,
            btnInkMark = binding.btnInkMark,
            btnInkDone = binding.btnInkDone,
            styleInkButton = { on ->
                binding.btnInk.setBackgroundResource(
                    if (on) R.drawable.bg_fb_mark_on else R.drawable.bg_fb_action
                )
            },
            onToggleMark = { toggleMarkCurrent() },
            btnInkColor = binding.btnInkColor
        )
        inkToolbar.onModeChanged = { on ->
            inkMode = on
            if (on) binding.inkOverlay.bindQuestion(questions.getOrNull(currentIndex)?.id ?: 0L)
        }

        // 写画模式的滚动通道：手势落在左半屏滚材料、右半屏滚题目（分屏的意义就是两边对着看）
        binding.inkOverlay.scrollTargetResolver = { x, _ ->
            if (x <= binding.leftPane.right) binding.materialScroll else binding.rightPane
        }
        // 左边缘也放一条手柄，写画时材料还能翻
        binding.inkOverlay.leftRailEnabled = true
        // 右栏滚动手柄让开工具条
        binding.inkOverlay.scrollBarBottomInset = (60 * resources.displayMetrics.density).toInt()

        // 笔迹钉在题目卡上（右栏每张卡一道题），滚材料 / 滚题目都不再和圈画错位
        binding.inkOverlay.anchorSource = { inkAnchors() }
        binding.root.viewTreeObserver.addOnScrollChangedListener {
            binding.inkOverlay.invalidate()
        }

        renderMaterial()
        renderQuestions()
        updateHeader()
    }

    /**
     * 遮盖层的内容锚点：右栏每张题目卡 + 左栏材料，各自独立。
     *
     * 题目锚点取「卡片左边 + 题干顶边」，与答题页同一口径，两种页面之间来回看不会整体错位；
     * 材料锚点取材料正文（`materialContent`）的位置，target 用材料组首题 id，
     * 与题目分开存——在材料上画的圈不会因为题干换行而偏移。
     */
    private fun inkAnchors(): List<InkOverlay.Anchor> {
        val out = ArrayList<InkOverlay.Anchor>(questions.size + 1)
        val base = IntArray(2)
        binding.inkOverlay.getLocationOnScreen(base)

        fun rectOf(view: View): FloatArray? {
            if (view.width <= 0 || view.height <= 0 || view.visibility != View.VISIBLE) return null
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            return floatArrayOf(
                (loc[0] - base[0]).toFloat(),
                (loc[1] - base[1]).toFloat(),
                view.width.toFloat(),
                view.height.toFloat()
            )
        }

        // 左栏材料：材料在独立滚动容器里，命中区裁到容器可视范围
        val materialQ = questions.firstOrNull { it.material.isNotBlank() }
        if (materialQ != null) {
            val content = rectOf(binding.materialContent)
            if (content != null) {
                val materialId = structure.groupOf(materialQ)?.questions?.first()?.id
                    ?: materialQ.id
                val anchor = InkOverlay.Anchor(
                    InkOverlay.TargetKind.MATERIAL, materialId,
                    content[0], content[1], content[2], content[3]
                )
                val viewport = rectOf(binding.materialScroll)
                if (viewport != null) {
                    anchor.hitRegion(
                        maxOf(content[0], viewport[0]),
                        maxOf(content[1], viewport[1]),
                        minOf(content[0] + content[2], viewport[0] + viewport[2]),
                        minOf(content[1] + content[3], viewport[1] + viewport[3])
                    )
                }
                out.add(anchor)
            }
        }

        for (i in 0 until binding.questionList.childCount) {
            val card = binding.questionList.getChildAt(i)
            val q = questions.getOrNull(i) ?: continue
            if (card.width <= 0 || card.height <= 0) continue
            val cardLoc = IntArray(2)
            card.getLocationOnScreen(cardLoc)
            val stem = card.findViewById<View>(R.id.tvStem)
            val stemY = if (stem != null && stem.height > 0) {
                val stemLoc = IntArray(2)
                stem.getLocationOnScreen(stemLoc)
                stemLoc[1]
            } else {
                cardLoc[1]
            }
            val cardLeft = (cardLoc[0] - base[0]).toFloat()
            val cardTop = (cardLoc[1] - base[1]).toFloat()
            val anchor = InkOverlay.Anchor(
                q.id,
                cardLeft,
                (stemY - base[1]).toFloat(),
                card.width.toFloat(),
                (cardLoc[1] + card.height - stemY).toFloat().coerceAtLeast(0f)
            )
            anchor.hitRegion(
                cardLeft, cardTop,
                cardLeft + card.width, cardTop + card.height
            )
            out.add(anchor)
        }
        return out
    }

    // ---------------- 左右占比可调 ----------------

    /**
     * 拖动中间手柄改变左栏宽度：材料长就往左拖大，图表矮就往右收。
     * 比例存偏好，重进沿用（25%～75%，默认 50%）。
     */
    private fun setupSplitResize() {
        val prefs = getSharedPreferences("exam_prefs", MODE_PRIVATE)
        val key = "split_left_ratio"
        val minPct = 0.25f
        val maxPct = 0.75f

        fun applyRatio(pct: Float) {
            val lp = binding.leftPane.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                ?: return
            lp.matchConstraintPercentWidth = pct
            binding.leftPane.layoutParams = lp
        }
        applyRatio(prefs.getFloat(key, 0.5f).coerceIn(minPct, maxPct))

        var startX = 0f
        var startPct = 0.5f
        binding.splitHandle.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startPct = prefs.getFloat(key, 0.5f).coerceIn(minPct, maxPct)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dw = ev.rawX - startX
                    val pct = (startPct + dw / binding.root.width)
                        .coerceIn(minPct, maxPct)
                    applyRatio(pct)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val lp = binding.leftPane.layoutParams
                        as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                    prefs.edit().putFloat(key, lp.matchConstraintPercentWidth).apply()
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    // ---------------- 左栏：材料 ----------------

    private fun renderMaterial() {
        val material = groupMaterial()
        if (material.isBlank()) {
            binding.materialContent.removeAllViews()
            val tip = TextView(this)
            tip.text = "本组题目无材料"
            tip.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.fb_text_body_sm)
            )
            tip.setTextColor(ContextCompat.getColor(this, R.color.fb_text_meta))
            binding.materialContent.addView(tip)
            return
        }
        MaterialRenderer.render(this, binding.materialContent, material)
    }

    /** 本组共用的一份材料（同一材料的题分到一组） */
    private fun groupMaterial(): String =
        questions.firstOrNull { it.material.isNotBlank() }?.material.orEmpty()

    // ---------------- 右栏：题目 ----------------

    private fun renderQuestions() {
        binding.questionList.removeAllViews()
        val inflater = LayoutInflater.from(this)

        questions.forEachIndexed { i, q ->
            val card = ItemSplitQuestionBinding.inflate(inflater, binding.questionList, false)
            card.tvNum.text = "${i + 1 + indexOffset}."
            card.tvType.text = q.type.label
            card.tvStem.text = q.stem

            val multi = q.type == com.lytree.qbank.data.model.QuestionType.MULTI ||
                q.type == com.lytree.qbank.data.model.QuestionType.UNCERTAIN
            val selected = (answers[q.id] ?: "").split(',', '，', ' ')
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .toMutableSet()

            q.options.forEach { opt ->
                val item = ItemOptionBinding.inflate(inflater, card.optionsBox, false)
                item.tvKey.text = opt.key
                item.tvText.text = opt.text
                // 卷面已经给了左右留白（fb_content_padding_h），选项行不再二次缩进，否则会比题干多缩一截
                item.root.setPadding(0, item.root.paddingTop, 0, item.root.paddingBottom)
                val isSel = selected.contains(opt.key.uppercase())
                QuestionCardBinder.applyCircleState(item, isSel)
                item.root.setOnClickListener {
                    if (multi) {
                        if (selected.contains(opt.key.uppercase())) selected.remove(opt.key.uppercase())
                        else selected.add(opt.key.uppercase())
                    } else {
                        selected.clear()
                        selected.add(opt.key.uppercase())
                    }
                    answers[q.id] = selected.sorted().joinToString(",")
                    currentIndex = i
                    updateHeader()
                    renderQuestions()
                    binding.inkOverlay.bindQuestion(q.id)
                }
                card.optionsBox.addView(item.root)
            }

            if (revealAnswers) {
                card.tvAnalysis.visibility = android.view.View.VISIBLE
                val userAns = answers[q.id] ?: ""
                val r = Judge.judge(q, userAns, db.getPolicy())
                card.tvAnalysis.text = buildString {
                    append("正确答案：${q.displayAnswer()}\n")
                    append("你的答案：${userAns.ifBlank { "未作答" }}\n")
                    append("结果：${when { r.correct -> "正确"; r.partial -> "部分得分"; else -> "错误" }}\n")
                    if (q.analysis.isNotBlank()) append("解析：${q.analysis}")
                }
            }

            binding.questionList.addView(card.root)
        }
    }

    private fun updateHeader() {
        tvIndex.text = getString(R.string.question_index, currentIndex + 1, questions.size)
        updateTimer()
        syncInkQuestion()
    }

    /** 顶栏计时：从进入分屏起正计时（参考应用分屏页左上是「🕐 01:08」） */
    private fun updateTimer() {
        if (startedAt <= 0L) return
        val sec = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
        val text = if (sec >= 3600) {
            String.format(Locale.CHINA, "%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)
        } else {
            String.format(Locale.CHINA, "%02d:%02d", sec / 60, sec % 60)
        }
        binding.titleBar.setTimer(text)
    }

    // ---------------- 手写 ----------------

    private fun toggleInk() {
        inkToolbar.toggle()
    }

    /** 遮盖层跟着「当前题」走：翻题后笔迹与标记状态都要换 */
    private fun syncInkQuestion() {
        if (!::inkToolbar.isInitialized) return
        val q = questions.getOrNull(currentIndex)
        binding.inkOverlay.bindQuestion(q?.id ?: 0L)
        inkToolbar.setMarked(q != null && q.id in marked)
    }

    /** 工具条上的「标记」：和答题卡里的已标记是同一份数据 */
    private fun toggleMarkCurrent() {
        val q = questions.getOrNull(currentIndex) ?: return
        if (!marked.add(q.id)) marked.remove(q.id)
        inkToolbar.setMarked(q.id in marked)
    }

    // ---------------- 答题卡 ----------------

    private fun openAnswerCard() {
        val answeredPos = questions.indices.filter { (answers[questions[it].id] ?: "").isNotBlank() }
        val markedPos = questions.indices.filter { questions[it].id in marked }
        startActivityForResult(
            Intent(this, AnswerCardActivity::class.java).apply {
                putExtra(AnswerCardActivity.EXTRA_COUNT, questions.size)
                putExtra(AnswerCardActivity.EXTRA_ANSWERED, answeredPos.toIntArray())
                putExtra(AnswerCardActivity.EXTRA_MARKED, markedPos.toIntArray())
            },
            REQ_CARD
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CARD && resultCode == RESULT_OK && data != null) {
            val jump = data.getIntExtra(AnswerCardActivity.EXTRA_JUMP, -1)
            if (jump in questions.indices) {
                currentIndex = jump
                updateHeader()
                binding.inkOverlay.bindQuestion(questions[jump].id)
                val card = binding.questionList.getChildAt(jump)
                card?.let { binding.rightPane.smoothScrollTo(0, it.top) }
            }
        }
    }

    // ---------------- 交卷 ----------------

    private fun confirmExit() {
        if (submitted) { finish(); return }
        FbDialog.confirm(
            context = this,
            message = "你还有题目未做完，确定退出吗？",
            okText = "退出",
            cancelText = "继续答题",
            onOk = {
                // 分屏页没有续做入口：放弃这次练习就把草稿（含标注）一起丢掉，
                // 避免下次进来只看到上一轮圈画、作答却是空的
                db.discardDraftRecords(paperId, DRAFT_SOURCE)
                finish()
            }
        )
    }

    private fun confirmSubmit() {
        val unanswered = questions.count { (answers[it.id] ?: "").isBlank() }
        val msg = if (unanswered > 0) {
            "你还有 $unanswered 道题目未作答，确定交卷吗？"
        } else {
            "确定交卷并查看结果吗？"
        }
        FbDialog.confirm(
            context = this,
            message = msg,
            okText = getString(R.string.action_confirm),
            onOk = { submit() }
        )
    }

    private fun submit() {
        if (submitted) return
        submitted = true
        revealAnswers = true

        val policy = db.getPolicy()
        val results = questions.map { Judge.judge(it, answers[it.id] ?: "", policy) }
        val summary = Judge.summarize(results)
        val now = System.currentTimeMillis()

        val record = ExamRecord(
            paperId = intent.getLongExtra(EXTRA_PAPER_ID, 0L),
            name = "分屏练习记录",
            mode = "资料分析分屏",
            startedAt = startedAt,
            submittedAt = now,
            durationSec = ((now - startedAt) / 1000).toInt(),
            totalCount = summary.total,
            answeredCount = summary.answered,
            correctCount = summary.correct,
            partialCount = summary.partial,
            score = summary.score,
            fullScore = summary.fullScore,
            // 与答题页同口径：标记跟着这条记录走
            marked = marked.toSet()
        )
        // 草稿转正：记录 id 不变，挂在它上面的标注（record_inks）继续可用
        val rid = db.finalizeRecord(recordId, record, results)
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

    // ---------------- 计时 ----------------

    /** 每秒刷新顶栏计时；页面不可见时摘掉回调用免空转 */
    private val timerTick = object : Runnable {
        override fun run() {
            updateTimer()
            binding.root.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.root.postDelayed(timerTick, 1000)
        // 答题页那边可能刚改过同一道题的笔迹，回来重读一遍
        binding.inkOverlay.reload()
    }

    override fun onPause() {
        super.onPause()
        binding.root.removeCallbacks(timerTick)
    }
}
