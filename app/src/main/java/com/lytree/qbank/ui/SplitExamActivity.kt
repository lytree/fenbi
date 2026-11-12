package com.lytree.qbank.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lytree.qbank.R
import com.lytree.qbank.data.db.DbWrites
import com.lytree.qbank.data.db.ExamProgress
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.data.model.ExamScope
import com.lytree.qbank.data.model.Paper
import com.lytree.qbank.data.model.PaperRef
import com.lytree.qbank.data.model.PaperStructure
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ActivitySplitExamBinding
import com.lytree.qbank.databinding.ItemOptionBinding
import com.lytree.qbank.databinding.ItemSplitQuestionBinding
import com.lytree.qbank.engine.Judge
import com.lytree.qbank.engine.Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        /**
         * 答题范围：与答题页同一个口径（空串 = 整卷，`s:` 大类，`m:` 材料）。
         * 从分模块答题里开分屏时带过来，判分 / 进度 / 记录都只算这一截。
         */
        const val EXTRA_SCOPE_KEY = "scope_key"
        /** 返回给答题页：本页已经交卷，答题页不要再拿内存里的旧作答覆盖这条记录 */
        const val EXTRA_SUBMITTED = "submitted"
        private const val REQ_CARD = 1001

        /** 草稿记录的来源标记（存在 records.mode），与答题页的草稿分开 */
        private const val DRAFT_SOURCE = "split"
    }

    private lateinit var binding: ActivitySplitExamBinding
    private lateinit var db: QBankDb

    private var paperId = 0L
    /** 答题范围（空串 = 整卷）：判分、进度、交卷都只看这一截 */
    private var scopeKey = ""
    /** 范围短标题（「资料分析 · 材料2」），整卷为空串；拼记录名用 */
    private var scopeTitle = ""
    /** 本次分屏练习的记录 id（草稿）：一进来就落库，标注与每题用时都挂在它上面 */
    private var recordId = 0L
    /** 每题用时：切题时把上一题的秒数累加进库 */
    private lateinit var questionTimer: QuestionTimer
    /** 本页显示的题（可能只是整卷里的一个材料组） */
    private var questions: List<Question> = emptyList()
    /** 本次范围的题目：交卷与进度统计都以它为准，分屏页只显示其中一组 */
    private var allQuestions: List<Question> = emptyList()
    private lateinit var structure: PaperStructure
    private val answers = HashMap<Long, String>()
    private val marked = HashSet<Long>()
    /** 进页面时从库里读到的已用时长（答题页存下的基线），本次分屏会话叠在它上面 */
    private var savedElapsedSec = 0
    /** 本页进入时间：顶栏正计时与「本次用了多久」都按它算 */
    private var startedAt = 0L
    /** 这条记录最初开始的时间（和答题页可能是同一条，别被本页的进入时间覆盖） */
    private var recordStartedAt = 0L
    private var submitted = false
    private var revealAnswers = false
    private var inkMode = false
    private var currentIndex = 0
    /** 本组题在整卷里的起始序号：右栏题号与顶部进度都按整卷编号 */
    private var indexOffset = 0
    private lateinit var inkToolbar: InkToolbarController
    private lateinit var tvIndex: TextView
    /** 判题规则：进页面时一次读回，解析区渲染与交卷判分复用（不再每题渲染查一次库） */
    private var judgePolicy: Policy = Policy.DEFAULT
    /** 卷名：启动批量读里一起取回，交卷记录用（不再交卷时再查一次库） */
    private var paperTitle = ""

    /**
     * 进页面时一次性批量读回的数据。
     *
     * 原来 `getPaper` 之外还有 ensureDraftRecord / recordStartedAt / getProgress 在主线程串行查，
     * 现在并进同一个 IO 批；主线程只做结构装配与界面绑定。
     */
    private class StartupData(
        val paper: Paper,
        /** 本次范围里的题目（整卷 = 全卷题目）：判分与统计都按它算 */
        val questions: List<Question>,
        /** 范围短标题；整卷为空串 */
        val scopeTitle: String,
        val recordId: Long,
        val recordStartedAt: Long,
        val sessionStartWall: Long,
        val progress: ExamProgress?,
        val policy: Policy
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手机锁竖屏 / 平板跟随旋转（方向锁是 Activity 级的，答题页锁了这里也要跟着锁）
        ScreenAdapt.applyOrientation(this)
        binding = ActivitySplitExamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)

        paperId = intent.getLongExtra(EXTRA_PAPER_ID, 0L)
        scopeKey = intent.getStringExtra(EXTRA_SCOPE_KEY).orEmpty()
        // 启动批量读：读卷 + 建/取草稿 + 首次开始时间 + 整卷未交卷进度 + 判题规则，一次 IO 拿全
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val paper = db.getPaperOrGroup(paperId) ?: return@withContext null
                if (paper.questions.isEmpty()) return@withContext null
                // 先把范围切出来：分模块答题时「整卷」其实是那一个模块的题
                val full = PaperStructure(paper.questions)
                val scoped = ExamScope.fromKey(scopeKey)
                val questions = scoped.resolve(full)
                val scopeTitle = scoped.label(full)
                val wall = System.currentTimeMillis()
                val title = intent.getStringExtra(EXTRA_PAPER_NAME)?.takeIf { it.isNotBlank() }
                    ?: paper.name.ifBlank { "分屏练习记录" }
                val name = if (scopeTitle.isBlank()) title else "$title · $scopeTitle"
                // **一进来就有记录**：先落一条「未完成」，交卷时转成「已完成」；
                // 标注与每题用时都按这条记录存
                val rid = db.ensureDraftRecord(
                    paperId, DRAFT_SOURCE, wall, name, scoped.key, questions.size
                )
                // 记录最初的开始时间单独留着：顶栏的正计时要从「进这一页」算起，
                // 而记录上的「开始时间」得是最早那次进卷的时刻
                val firstStart = db.recordStartedAt(rid).takeIf { it > 0L } ?: wall
                StartupData(
                    paper = paper,
                    questions = questions,
                    scopeTitle = scopeTitle,
                    recordId = rid,
                    recordStartedAt = firstStart,
                    sessionStartWall = wall,
                    progress = db.getProgress(paperId, scoped.key),
                    policy = db.getPolicy()
                )
            }
            if (isFinishing || isDestroyed) return@launch
            if (data == null) {
                android.widget.Toast.makeText(this@SplitExamActivity, "试卷不存在或为空", android.widget.Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            val paper = data.paper

            // 按材料分组：直接用试卷结构里的材料组——同一材料跨大类会断成两组，
            // 按文本 filter 会把它们并成一组、题号也会错，这里取的正是这题所属的那一组
            structure = PaperStructure(data.questions)
            val groupKey = intent.getStringExtra(EXTRA_GROUP_KEY)?.trim().orEmpty()
            val group = if (groupKey.isEmpty()) {
                null
            } else {
                structure.groups.firstOrNull {
                    it.isMaterialGroup && it.material.material.trim() == groupKey
                }
            }
            allQuestions = data.questions
            questions = group?.questions ?: data.questions
            // 题号接着整卷往下排（参考应用分屏右栏显示的是 106./107./108.，不是 1./2./3.）
            indexOffset = group?.startIndex ?: 0

            startedAt = data.sessionStartWall
            recordId = data.recordId
            recordStartedAt = data.recordStartedAt
            paperTitle = paper.name
            scopeTitle = data.scopeTitle
            judgePolicy = data.policy
            // 与答题页共用同一份进度：进页面先把已作答的题读回来，
            // 否则「在答题页做的题」在这儿看不到、「在这儿改的答案」退出后也留不住
            data.progress?.let { p ->
                answers.putAll(p.answers)
                marked.addAll(p.marked)
                savedElapsedSec = p.elapsedSec
            }
            questionTimer = QuestionTimer(db).apply { bindRecord(recordId) }
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
            // 左栏只有半屏宽，图 / 表挤在一起时弹到独立页面看（不再用 Dialog 浮层）
            binding.btnMaterialExpand.setOnClickListener {
                val material = groupMaterial()
                if (material.isNotBlank()) {
                    startActivity(Intent(this@SplitExamActivity, MaterialPopupActivity::class.java).apply {
                        putExtra(MaterialPopupActivity.EXTRA_MATERIAL, material)
                    })
                }
            }

            // 左右占比可调：拖动中间手柄改变左栏宽度（25%～75%），比例记忆到偏好
            setupSplitResize()

            inkToolbar = InkToolbarController(
                inkOverlay = binding.inkOverlay,
                inkBar = binding.inkBar,
                btnPen = binding.btnInkPen,
                btnEraser = binding.btnInkEraser,
                btnUndo = binding.btnInkUndo,
                btnRedo = binding.btnInkRedo,
                btnShade = binding.btnInkShade,
                btnClear = binding.btnInkClear,
                btnClose = binding.btnInkClose,
                styleInkButton = { on ->
                    binding.btnInk.setBackgroundResource(
                        if (on) R.drawable.bg_fb_mark_on else R.drawable.bg_fb_action
                    )
                }
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
            // 工具条在顶上，右侧轨道可以用到底
            binding.inkOverlay.scrollBarBottomInset = 0

            // 笔迹钉在题目卡上（右栏每张卡一道题），滚材料 / 滚题目都不再和圈画错位
            binding.inkOverlay.anchorSource = { inkAnchors() }
            binding.root.viewTreeObserver.addOnScrollChangedListener {
                binding.inkOverlay.invalidate()
            }

            renderMaterial()
            renderQuestions()
            updateHeader()
        }
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

            // 选中某项 → 记答案、刷新整栏（分屏页选项少，直接重绘最省事）
            val pick: (String) -> Unit = { key ->
                if (multi) {
                    if (selected.contains(key)) selected.remove(key) else selected.add(key)
                } else {
                    selected.clear()
                    selected.add(key)
                }
                answers[q.id] = selected.sorted().joinToString(",")
                currentIndex = i
                updateHeader()
                renderQuestions()
                binding.inkOverlay.bindQuestion(q.id)
            }

            // 打叉排除一个已选项：排除与「已选」互斥，答案跟着撤；重画由选项行自己负责，
            // 这里不整栏重绘——长按调用的还是被按住的这一行，重建会把手指下的 View 拆掉
            val deselectCrossed: (String, Boolean) -> Unit = { key, crossed ->
                if (crossed && selected.remove(key)) {
                    answers[q.id] = selected.sorted().joinToString(",")
                    currentIndex = i
                    updateHeader()
                }
            }

            // 图形推理「四个选项就是四张图」：和主答题页同口径，横排一行、按格宽缩图
            if (QuestionCardBinder.isImageOnlyOptions(q)) {
                card.optionsBox.addView(
                    QuestionCardBinder.buildImageOptionsRow(
                        card.optionsBox, q, { selected }, night = false,
                        applyRowPadding = false, onPick = pick,
                        onCrossChanged = deselectCrossed
                    )
                )
            } else {
                q.options.forEach { opt ->
                    val item = ItemOptionBinding.inflate(inflater, card.optionsBox, false)
                    item.tvKey.text = opt.key
                    // 卷面已经给了左右留白（fb_content_padding_h），选项行不再二次缩进，否则会比题干多缩一截
                    item.root.setPadding(0, item.root.paddingTop, 0, item.root.paddingBottom)
                    val key = opt.key.uppercase()
                    val refresh: () -> Unit = {
                        QuestionCardBinder.applyOptionState(
                            item, selected.contains(key), OptionCross.isCrossed(q.id, key)
                        )
                    }
                    // 文字 + 配图（图形推理的选项可能就是一张图）；分屏页暂无夜间配色。
                    // 图上的长按要接回「打叉」，否则图片可点会吃掉整行的长按
                    QuestionCardBinder.bindOptionBody(
                        item, opt.text, night = false,
                        onImageLongPress = {
                            val crossed = OptionCross.toggle(q.id, key)
                            deselectCrossed(key, crossed)
                            refresh()
                        }
                    )
                    refresh()
                    // 点 = 选，长按 = 打叉，点已打叉的项 = 恢复可选（与主答题页同一套手感）
                    QuestionCardBinder.bindOptionInteractions(
                        item, q.id, key,
                        onPick = { pick(key) },
                        onCrossChanged = { crossed ->
                            deselectCrossed(key, crossed)
                            refresh()
                        }
                    )
                    card.optionsBox.addView(item.root)
                }
            }

            if (revealAnswers) {
                // 作答小结与解析走主卷面同一块（左边「正确答案」右「你的答案」）
                AnswerBlock.bind(
                    card.answerBlock, q, answers[q.id] ?: "", judgePolicy, night = false
                )
            } else {
                AnswerBlock.hide(card.answerBlock)
            }

            binding.questionList.addView(card.root)
        }
    }

    private fun updateHeader() {
        tvIndex.text = getString(R.string.question_index, currentIndex + 1, questions.size)
        updateTimer()
        syncInkQuestion()
        // 每题用时：滚到 / 点到哪一题就把表拨到哪一题
        if (::questionTimer.isInitialized) questionTimer.switchTo(questions.getOrNull(currentIndex)?.id)
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

    /** 遮盖层跟着「当前题」走：翻题后笔迹跟着换到新题 */
    private fun syncInkQuestion() {
        if (!::inkToolbar.isInitialized) return
        val q = questions.getOrNull(currentIndex)
        binding.inkOverlay.bindQuestion(q?.id ?: 0L)
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

    // ---------------- 进度保存 ----------------

    /** 整卷已用时长：答题页存下的基线 + 本次分屏会话 */
    private fun elapsedForRecord(): Int =
        savedElapsedSec + ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceAtLeast(0)

    /**
     * 把本页作答并进整卷进度再落库。
     *
     * 分屏页只显示一组题，直接覆盖会把答题页做的其它题冲掉，所以用 merge；
     * 返回的合并结果用于统计整卷作答数与交卷判分。
     */
    private fun saveMergedProgress(): Map<Long, String> =
        db.saveProgress(paperId, answers, marked, elapsedForRecord(), merge = true, scope = scopeKey)

    // ---------------- 交卷 ----------------

    private fun confirmExit() {
        if (submitted) { finish(); return }
        FbDialog.confirm(
            context = this,
            message = "你还有题目未做完，确定退出吗？",
            okText = "退出",
            cancelText = "继续答题",
            onOk = {
                // 退出分屏只是离开这个页面：记录和那一轮的圈画都留着（和答题页是同一条记录），
                // 下次进来仍是同一场练习
                if (::questionTimer.isInitialized) questionTimer.stop()
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
        // 记下交卷开始时刻：失败回滚后用它把失败窗口从「已用时长」里摘掉
        val submitStartedAt = System.currentTimeMillis()
        if (::questionTimer.isInitialized) questionTimer.stop()
        // 交卷前把节流中的笔迹写出去，别让最后几笔只留在内存里
        binding.inkOverlay.flush()
        // 队列里排着的每题用时先落完，再合并本页作答并交卷，顺序不会反
        DbWrites.flush()

        val policy = judgePolicy
        // 交卷整条链路放进 try：先合并本页作答，再走统一交卷事务。
        // 任一步失败就回到可重试状态——答案还在内存与库里，不会被清掉。
        val rid = try {
            // 分屏页只显示一组题：先把本页作答并进整卷进度，再按**整卷**判分。
            // 直接拿这一组的结果交卷，会把与答题页共享的草稿记录写成「只有这一组」的已完成记录
            val elapsed = elapsedForRecord()
            val merged = saveMergedProgress()
            val results = allQuestions.map { Judge.judge(it, merged[it.id] ?: "", policy) }
            val summary = Judge.summarize(results)
            val now = System.currentTimeMillis()

            val record = ExamRecord(
                paperId = paperId,
                // 记录名带范围：模块分屏的记录在列表里能一眼分清
                name = if (scopeTitle.isBlank()) {
                    paperTitle.ifBlank { "分屏练习记录" }
                } else {
                    "${paperTitle.ifBlank { "分屏练习" }} · $scopeTitle"
                },
                mode = "资料分析分屏",
                scope = scopeKey,
                startedAt = if (recordStartedAt > 0L) recordStartedAt else startedAt,
                submittedAt = now,
                durationSec = elapsed,
                totalCount = summary.total,
                answeredCount = summary.answered,
                correctCount = summary.correct,
                partialCount = summary.partial,
                score = summary.score,
                fullScore = summary.fullScore,
                // 与答题页同口径：标记跟着这条记录走
                marked = marked.toSet(),
                // 交卷即「已完成」：成绩与每题用时都能在记录里回看
                status = QBankDb.STATUS_DONE
            )
            // 草稿转正 + 清未交卷进度是一个事务（记录 id 不变，挂在它上面的标注继续可用）
            db.finishSubmission(paperId, recordId, record, results, scopeKey)
        } catch (e: Exception) {
            submitted = false
            resumeTimersAfterFailure(submitStartedAt)
            android.util.Log.e("SplitExamActivity", "交卷失败，已整体回滚，可重试", e)
            android.widget.Toast.makeText(
                this, "交卷失败，答案与进度已保留，请重试交卷", android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        revealAnswers = true
        // 告知答题页本页已交卷，让它退出，避免它再用内存里的旧作答覆盖这条已完成记录
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SUBMITTED, true))
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

    /**
     * 交卷失败、事务回滚之后把计时接回去。
     *
     * 顶栏是正计时：把 [startedAt] 往后挪掉「交卷失败到回滚」这段窗口，
     * 失败期间不算作答，显示也不会往回跳；失败多少次就各挪各自的窗口，不会叠加。
     * 每题用时 [QuestionTimer] 在交卷时已 stop，这里重新拨回当前题。
     */
    private fun resumeTimersAfterFailure(submitStartedAt: Long) {
        startedAt += (System.currentTimeMillis() - submitStartedAt).coerceAtLeast(0L)
        // updateHeader 会把每题计时重新拨回当前题，并刷新顶栏正计时
        updateHeader()
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
        // 离开分屏页前先把节流中的笔迹写出去（退出 / 切后台 / 被回收都不会丢最后一笔）
        binding.inkOverlay.flush()
        // 切后台就结算：当前题的秒数落库，作答进度与记录里的整卷计数一起刷新。
        // 只在这里写（不放 onStop），一次生命周期只落一次，避免重复写
        if (submitted || !PaperRef.isValid(paperId)) return
        if (::questionTimer.isInitialized) questionTimer.stop()
        // 整卷合并写 + 记录计数写都排进写队列（合并结果只在队列内使用），顺序与「退出前落库」都保住
        val answersCopy = answers.toMap()
        val markedCopy = marked.toSet()
        val elapsed = elapsedForRecord()
        val rid = recordId
        val total = allQuestions.size
        val scope = scopeKey
        DbWrites.submit {
            val merged = db.saveProgress(paperId, answersCopy, markedCopy, elapsed, merge = true, scope = scope)
            db.updateRecordLive(rid, merged.count { it.value.isNotBlank() }, total, elapsed)
        }
        // 页面离开前等队列落完，进程被回收也不会丢这一笔
        DbWrites.flush()
    }
}
