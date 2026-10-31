package com.lytree.qbank.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.data.model.PaperStructure
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ActivityExamBinding
import com.lytree.qbank.engine.Judge
import java.util.Locale

/**
 * 答题页（竖屏）。
 *
 * - **顶栏**：X 关闭 / 闹钟+倒计时 / 居中标题「试卷名（题数）」/ 交卷 / 答题卡 / 更多
 * - **内容**：连续滚动的多题浏览（默认模式），每题 = 题目行（题号+题型+标记/笔记/收藏）+ 题干 + 选项
 * - **更多面板**：字号（小/中/大）、日间/夜间、多题浏览/单题浏览
 * - **答题卡**：底部弹层，圆形题号 + 按科目分组 + 通栏交卷
 *
 * 交互要点：
 * - 手写标注（触控笔）叠在题目之上，笔迹跟随本次作答记录按题目 / 材料分别持久化，切题不丢；
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
        private const val KEY_AUTO_NEXT = "auto_next"
        private const val KEY_PRACTICE = "practice_mode"

        /**
         * 草稿记录的来源标记（存在 records.mode）。
         * 答题页与分屏页各用一份，标注 / 续做互不串记录。
         */
        private const val DRAFT_SOURCE = "exam"

        /** 字号档位：小 / 中 / 大 */
        private val FONT_SCALES = floatArrayOf(0.9f, 1.0f, 1.15f)
    }

    private lateinit var binding: ActivityExamBinding
    private lateinit var db: QBankDb

    /**
     * 题目的唯一持有者。两套适配器共享这一个可变列表实例，
     * 补完答案后就地 clear + addAll，列表位置与作答状态都不用重建。
     */
    private val questions = ArrayList<Question>()
    /**
     * 试卷三级结构（大类题型 → 材料 → 题目）。
     * 单题分页、多题卷面的材料去重与说明卡、答题卡分组、弹出材料/分屏的组定位都从这里取数；
     * 题目集合变了（补答案后重读整卷）就重建一份，见 [rebuildStructure]。
     */
    private lateinit var structure: PaperStructure
    private val answers = HashMap<Long, String>()
    private val marked = HashSet<Long>()
    private val favorites = HashSet<Long>()
    /** 练习模式：作答后立即判分出解析的题 id（对齐参考应用「练习」口径） */
    private val revealed = HashSet<Long>()
    private var timer: CountDownTimer? = null
    private var paperId = 0L
    /** 本次作答的记录 id（草稿）：标注按它落库，交卷时转正 */
    private var recordId = 0L
    private var durationMin = 0
    private var startedAt = 0L
    private var submitted = false
    private var revealAnswers = false
    private var inkMode = false
    /** 练习模式开关（更多面板），持久化偏好 */
    private var practiceMode = false
    /** 「笔」按钮的用法提示只在本次进入页面时弹一次，别每点一下都弹 */
    private var inkHintShown = false
    /** 是从「交卷时提示缺答案」跳去导入的：补完就直接交卷出结果 */
    private var submitAfterImport = false

    /** 字号缩放系数 */
    private var fontScale = 1f
    /** 是否多题浏览（参考应用默认多题） */
    private var multiBrowse = true
    /** 夜间模式 */
    private var nightMode = false
    /** 单选题作答后自动滚到下一题 */
    private var autoNext = true
    /** 当前最靠上的题目位置（答题卡高亮「当前题」用） */
    private var currentPos = 0
    /**
     * 等待翻页落定后要在页内滚到的题号（材料组页一页多题，换页后还要滚到那一题）。
     * -1 表示没有待处理目标。
     */
    private var pendingPageFocus = -1

    // ---------------- 答题进度续做 ----------------

    /** 上次退出时已用掉的秒数（续做时从这里继续倒计时） */
    private var elapsedBaseSec = 0
    /** 本次会话开始时刻（计算「本次会话已用时」用） */
    private var sessionStartWall = 0L
    /** 倒计时当前剩余秒数（onTick 维护，-1 表示还没起表） */
    private var lastRemainingSec = -1

    // ---------------- 弹出材料：就地左右分屏 ----------------

    /** 平板判定：最小屏宽 ≥ 600dp（与系统 sw600dp 断点一致）。对齐参考应用——只有平板端「弹出材料」走就地分屏 */
    private val isTablet: Boolean
        get() = ScreenAdapt.isTablet(resources.configuration)

    /** 是否处于「弹出材料」分屏状态（对齐参考应用平板端：就地分屏，可收起还原） */
    private var splitMode = false
    /** 当前分屏的材料组（同一份材料的全部题，保持卷内顺序） */
    private var splitGroup: List<Question> = emptyList()
    /** 材料组第一题在整卷中的位置（0 起），右栏题号按整卷序号显示 */
    private var splitGroupOffset = 0
    /** 分屏右栏的连续题目列表（进入分屏时创建，收起时销毁） */
    private var splitList: androidx.recyclerview.widget.RecyclerView? = null
    private var splitListAdapter: QuestionListAdapter? = null

    private lateinit var inkToolbar: InkToolbarController
    /** 多题浏览适配器（连续滚动） */
    private lateinit var adapter: QuestionListAdapter
    /** 单题浏览适配器（ViewPager2，一屏一题） */
    private lateinit var pagerAdapter: QuestionPagerAdapter

    /** 补答案：选一个答案文件，解析匹配后写回本卷题目 */
    private val answerPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAnswers(uri) else submitAfterImport = false
    }

    /** 补录答案：不用答案文件，直接在补录页逐题填；回来后卷面就有标准答案了 */
    private val answerEntryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        reloadQuestions()
        refreshCards()
        refreshAnswerEntry()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手机继续锁竖屏（答题页是按竖屏设计的）；平板不能锁——见 ScreenAdapt 里的说明
        ScreenAdapt.applyOrientation(this)
        binding = ActivityExamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)

        loadPrefs()

        paperId = intent.getLongExtra(EXTRA_PAPER_ID, 0L)
        val paper = db.getPaper(paperId)
        if (paper == null || paper.questions.isEmpty()) {
            android.widget.Toast.makeText(this, "试卷不存在或为空", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        questions.addAll(paper.questions)
        structure = PaperStructure(questions)
        durationMin = paper.durationMin
        startedAt = System.currentTimeMillis()
        sessionStartWall = startedAt
        // 标注要跟随本次作答记录：先进库建（或取回）草稿，交卷时再转正
        recordId = db.ensureDraftRecord(paperId, DRAFT_SOURCE, startedAt)

        // 收藏跨会话保留：从库里读出本卷题目的收藏状态（对齐参考应用「我的收藏」）
        val qids = questions.map { it.id }.toHashSet()
        favorites.addAll(db.listFavoriteIds().filter { it in qids })

        setupList()
        setupPager()
        setupTopBar()
        setupMorePanel()
        setupInk()
        applyWindowInsets()

        // 「收起材料」：退出就地分屏，还原浏览模式
        binding.btnSplitCollapse.setOnClickListener { closeSplit() }

        // 启动时就把上次选的浏览模式落到位（旧实现只在点按钮时才应用，偏好读了等于没读）
        applyBrowseMode()
        // 夜间模式同理：偏好读进来后必须真的落到界面上，否则重进 App 面板显示「夜间」、界面却是白底
        applyNightMode()
        updateHeader()
        // 有未交卷进度先问「继续 / 重新开始」，再起倒计时（剩余时间要从上次退出的地方接）
        maybeResume()
    }

    // ---------------- 装配 ----------------

    private fun setupList() {
        adapter = QuestionListAdapter(
            questions = questions,
            structure = structure,
            answers = answers,
            marked = marked,
            favorites = favorites,
            revealAnswers = revealAnswers,
            revealed = revealed,
            policyProvider = { db.getPolicy() },
            fontScale = fontScale,
            onMarkToggle = { q -> toggleMark(q) },
            onFavoriteToggle = { q -> toggleFavorite(q) },
            onNoteClicked = { q -> showNoteDialog(q) },
            onInkClicked = { q -> startInkFor(q) },
            onAnswerChanged = { q -> onAnswered(q) },
            onMaterialExpand = { q -> showMaterialPopup(q) }
        )
        binding.questionList.layoutManager = LinearLayoutManager(this)
        binding.questionList.adapter = adapter
        binding.questionList.itemAnimator = null
        binding.questionList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                // 笔迹钉在题目卡上，卡片位置一变就得重画（RecyclerView 不触发视图树的滚动回调）
                binding.inkOverlay.invalidate()
                // 只有多题模式在滚动；单题模式的当前位置由 ViewPager2 回调维护，
                // 否则隐藏的列表会把手写笔迹 / 答题卡高亮带偏。
                if (!multiBrowse) return
                (rv.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()
                    ?.let {
                        if (it >= 0 && it != currentPos) {
                            currentPos = it
                            // 遮盖层常驻可见，滚动换题时要跟着换绑笔迹
                            syncInkQuestion()
                        }
                    }
            }
        })
    }

    /**
     * 单题浏览的容器。适配器在启动时就挂上，之后只是在两个容器之间切显隐，
     * 这样来回切换不会丢题、也不会重新走一遍 inflate。
     */
    private fun setupPager() {
        pagerAdapter = QuestionPagerAdapter(
            questions = questions,
            structure = structure,
            answers = answers,
            marked = marked,
            favorites = favorites,
            policyProvider = { db.getPolicy() },
            fontScale = fontScale,
            revealAnswers = revealAnswers,
            revealed = revealed,
            onMarkToggle = { q -> toggleMark(q) },
            onFavoriteToggle = { q -> toggleFavorite(q) },
            onNoteClicked = { q -> showNoteDialog(q) },
            onInkClicked = { q -> startInkFor(q) },
            onAnswerChanged = { q -> onAnswered(q) },
            onMaterialExpand = { q -> showMaterialPopup(q) }
        )
        binding.pager.offscreenPageLimit = 1
        binding.pager.adapter = pagerAdapter
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // 单题模式下由翻页维护「当前题」；多题模式以列表为准，不受影响。
                // 页码表里夹着「模块说明页」，所以要换回题号——否则说明页会被当成一道题。
                if (multiBrowse) return
                // 材料组页一页多题：页内正在看的那道题不该被翻页回调重置成组首题
                val range = pagerAdapter.questionsOfPage(position)
                if (range == null || currentPos !in range) {
                    currentPos = pagerAdapter.questionOfPage(position)
                }
                // 从答题卡 / 「笔」跳过来时，页内还要落到具体那一题（材料组页一页多题）
                val focus = pendingPageFocus
                if (focus >= 0 && range != null && focus in range) {
                    pendingPageFocus = -1
                    binding.pager.post { locateInPage(focus) }
                }
                syncInkQuestion()
            }
        })
    }

    /** 作答后的联动：练习模式即时判分；单选题「自动展示下一题」时翻到 / 滚到下一题 */
    private fun onAnswered(q: Question) {
        questions.indexOfFirst { it.id == q.id }.takeIf { it >= 0 }?.let { currentPos = it }
        if (practiceMode && !submitted && q.id !in revealed) {
            // 练习模式（参考应用「练习」口径）：作答立刻判色 + 内联解析，不等交卷
            revealed.add(q.id)
            refreshCard(q)
        }
        if (!autoNext) return
        if (q.type != com.lytree.qbank.data.model.QuestionType.SINGLE) return
        val next = currentPos + 1
        if (next >= questions.size) return
        if (multiBrowse) {
            binding.questionList.postDelayed({
                binding.questionList.smoothScrollToPosition(next)
            }, 220)
        } else if (pagerAdapter.pageOfQuestion(next) == binding.pager.currentItem) {
            // 同一则材料带多道小题：组内不再翻页，把下一题在本页里滚出来
            currentPos = next
            binding.pager.postDelayed({ locateInPage(next, smooth = true) }, 220)
        } else {
            // 翻到下一题（跳过夹在中间的模块说明页——作答流不该被说明页打断）
            binding.pager.postDelayed(
                { binding.pager.setCurrentItem(pagerAdapter.pageOfQuestion(next), true) }, 220
            )
        }
    }

    private fun setupTopBar() {
        binding.btnClose.setOnClickListener { confirmExit() }
        binding.btnCard.setOnClickListener { openAnswerCard() }
        binding.btnSubmitTop.setOnClickListener { confirmSubmit() }
        binding.btnDownload.setOnClickListener { exportPaper() }
        binding.btnMore.setOnClickListener { toggleMorePanel() }
    }

    /**
     * 顶栏「下载」：把当前试卷导出成 Excel（.xlsx）到应用外部目录（Android/data/.../files/paper/）。
     * 表头与统一模板一致，导出的文件可以直接再导入（下载 → 换手机 → 再导入的闭环）。
     */
    private fun exportPaper() {
        try {
            val paper = db.getPaper(intent.getLongExtra(EXTRA_PAPER_ID, 0L)) ?: return
            val dir = java.io.File(
                getExternalFilesDir(null) ?: filesDir, "paper"
            ).apply { mkdirs() }
            val name = (intent.getStringExtra(EXTRA_PAPER_NAME) ?: "试卷")
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val f = java.io.File(dir, "$name.xlsx")
            com.lytree.qbank.data.importer.XlsxWriter.writeToFile(paper.questions, f)
            android.widget.Toast.makeText(
                this, "已导出：${f.absolutePath}", android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "导出失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 更多面板：字号 / 日夜 / 浏览模式。
     * 交互对齐参考应用——点哪个就切哪个，选中态即时高亮。
     */
    private fun setupMorePanel() {
        binding.btnFontSmall.setOnClickListener { setFontScale(0) }
        binding.btnFontMedium.setOnClickListener { setFontScale(1) }
        binding.btnFontLarge.setOnClickListener { setFontScale(2) }
        binding.btnDay.setOnClickListener { setNightMode(false) }
        binding.btnNight.setOnClickListener { setNightMode(true) }
        binding.btnMultiBrowse.setOnClickListener { setBrowseMode(true) }
        binding.btnSingleBrowse.setOnClickListener { setBrowseMode(false) }

        // 开关：参考应用风格的小号胶囊开关（自绘，避免 Material Switch 被拉大）
        binding.switchAutoNext.setChecked(autoNext, animate = false)
        binding.switchAutoNext.onCheckedChange = { checked ->
            autoNext = checked
            savePrefs()
        }
        // 练习模式：作答后立即判对错并展开解析（参考应用「练习」作答口径）
        binding.switchPracticeMode.setChecked(practiceMode, animate = false)
        binding.switchPracticeMode.onCheckedChange = { on ->
            practiceMode = on
            if (!on) revealed.clear()
            savePrefs()
            refreshCards()
        }
        // 手写标注：打开就是「给题目盖一张可写的薄纸」，收起面板直接开写
        binding.switchInkMode.setChecked(inkMode, animate = false)
        binding.switchInkMode.onCheckedChange = { on ->
            binding.morePanel.visibility = View.GONE
            inkToolbar.setInkMode(on)
        }
        refreshMorePanelState()
        setupSplitEntry()
        setupAnswerEntry()
    }

    /**
     * 「导入答案」入口。试卷先导入、答案后补是这个 App 的常态：
     * 导入时没答案的题按「待补答案」入库，这里补上答案后就能正常判分对比。
     */
    private fun setupAnswerEntry() {
        binding.rowImportAnswer.setOnClickListener {
            binding.morePanel.visibility = View.GONE
            submitAfterImport = false
            launchAnswerPicker()
        }
        // 没有答案文件也能补：进补录页逐题填，回来卷面立刻可判
        binding.rowFillAnswer.setOnClickListener {
            binding.morePanel.visibility = View.GONE
            submitAfterImport = false
            answerEntryLauncher.launch(
                Intent(this, AnswerEntryActivity::class.java).apply {
                    putExtra(AnswerEntryActivity.EXTRA_PAPER_ID, paperId)
                }
            )
        }
        refreshAnswerEntry()
    }

    private fun refreshAnswerEntry() {
        val missing = questions.count { !Judge.hasStandard(it) }
        binding.tvImportAnswerLabel.text = if (missing > 0) {
            getString(R.string.more_import_answer_missing, missing)
        } else {
            getString(R.string.more_import_answer_ready)
        }
    }

    private fun launchAnswerPicker() {
        try {
            answerPicker.launch(AnswerImportUi.MIME)
        } catch (_: Exception) {
            answerPicker.launch(arrayOf("*/*"))
        }
    }

    private fun importAnswers(uri: Uri) {
        AnswerImportUi.handle(this, db, questions, uri) {
            reloadQuestions()
            // 已交卷状态下补答案：题面解析区要跟着换成真正的标准答案
            if (revealAnswers) {
                adapter.revealAnswers = true
                pagerAdapter.revealAnswers = true
            }
            refreshCards()
            if (submitAfterImport) {
                submitAfterImport = false
                submit(auto = false)
            }
        }
    }

    /** 从库里重新读一遍题目（答案已更新），就地刷新共享列表 */
    private fun reloadQuestions() {
        val fresh = db.getPaper(paperId)?.questions ?: return
        questions.clear()
        questions.addAll(fresh)
        rebuildStructure()
        refreshAnswerEntry()
        updateHeader()
    }

    /**
     * 题目集合变了就重建结构：分组（大类 / 材料）与单题页码表都挂在它上面，
     * 不重建就会继续按旧结构分页、按旧分组渲染材料。
     */
    private fun rebuildStructure() {
        structure = PaperStructure(questions)
        if (::adapter.isInitialized) adapter.updateStructure(structure)
        if (::pagerAdapter.isInitialized) pagerAdapter.updateStructure(structure)
    }

    /**
     * 「弹出材料」。
     *
     * 交互对齐参考应用，**平板 / 手机两套模式**：
     * - 平板（最小屏宽 ≥ 600dp）+ 材料组：**就地左右分屏**——左栏材料（可「收起材料」还原），
     *   右栏这组题目一屏一题、左右滑动切题，照常作答，且**卡片不再重复渲染材料**（左栏已有整栏）；
     * - 手机（或材料只带一道题）：**半屏浮层**——从底部弹出约 65% 屏高，材料独立滚动，
     *   A- / A+ 调字号，答题状态完全不打断。
     */
    private fun showMaterialPopup(q: Question) {
        if (q.material.isBlank()) return
        // 以材料为准：这组 = 该题所属的材料组（跨大类的同名材料本来就断成两组）
        val group = structure.groupOf(q)?.takeIf { it.isMaterialGroup }?.questions ?: listOf(q)
        if (group.size > 1 && isTablet) {
            openSplit(q, group)
        } else {
            showMaterialSheet(q.material)
        }
    }

    /** 「弹出材料」手机端：底部半屏浮层（材料独立滚动 + 字号调节），替代原先的整页跳转 */
    private fun showMaterialSheet(material: String) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_material, null)
        val box = v.findViewById<android.view.ViewGroup>(R.id.sheetMaterialContent)
        val scroll = v.findViewById<androidx.core.widget.NestedScrollView>(R.id.sheetScroll)
        val ratio = v.findViewById<TextView>(R.id.tvSheetRatio)

        // 浮层高度 ≈ 65% 屏高（与「上下分块」的材料区上限一致，保证正文还能瞥到一眼）
        v.findViewById<View>(R.id.sheetRoot).layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.65f).toInt()
            )

        var scale = fontScale
        fun render() {
            MaterialRenderer.render(this, box, material, nightMode, scale, chartScale = 1.3f)
            ratio.text = getString(R.string.material_sheet_scale, (scale * 100).toInt())
            scroll.scrollTo(0, 0)
        }
        v.findViewById<TextView>(R.id.btnFontMinus).setOnClickListener {
            scale = (scale - 0.15f).coerceAtLeast(0.85f); render()
        }
        v.findViewById<TextView>(R.id.btnFontPlus).setOnClickListener {
            scale = (scale + 0.15f).coerceAtMost(1.6f); render()
        }
        v.findViewById<View>(R.id.btnSheetClose).setOnClickListener { sheet.dismiss() }
        render()

        // 夜间模式：浮层底色 / 标签跟答题页一起换
        if (nightMode) {
            v.findViewById<View>(R.id.sheetRoot)
                .setBackgroundResource(R.drawable.bg_fb_sheet_night)
            v.findViewById<TextView>(R.id.tvSheetTag)
                .setBackgroundResource(R.drawable.bg_fb_material_tag_night)
        }
        sheet.setContentView(v)
        sheet.show()
    }

    /** 进入就地分屏：左材料 / 右题目（一屏一题），定位到打开分屏的那道题 */
    private fun openSplit(q: Question, group: List<Question>) {
        splitGroup = group
        splitGroupOffset = questions.indexOfFirst { it.id == group.first().id }.coerceAtLeast(0)
        splitMode = true

        // 写画模式与分屏互斥：分屏两栏各有自己的滚动，先把遮盖层收起来
        if (inkMode) inkToolbar.setInkMode(false)
        binding.morePanel.visibility = View.GONE

        // 分屏时左上角是「返回」而不是「关闭」：点它是收起材料，不会退出整套卷（对齐参考应用）
        binding.btnClose.setImageResource(R.drawable.ic_fb_back)
        binding.btnClose.contentDescription = getString(R.string.material_collapse)
        binding.btnClose.setOnClickListener { closeSplit() }

        renderSplitMaterial()
        setupSplitList(q)
        applySplitVisibility()
        refreshSplitProgress()
    }

    private fun renderSplitMaterial() {
        // 半屏空间比题目卡里还窄，图表不额外放大
        MaterialRenderer.render(this, binding.splitMaterialContent, splitGroup.first().material, nightMode, fontScale)
        binding.splitMaterialContent.applyNavBarBottomPadding()
        binding.splitMaterialScroll.scrollTo(0, 0)
    }

    /**
     * 右栏：这组题用连续列表展示（与参考应用一致——右边是一条卷面，往下就是下一题），
     * 与主容器共享作答 / 标记 / 收藏状态，题号按整卷序号显示。
     */
    private fun setupSplitList(openQuestion: Question) {
        val list = androidx.recyclerview.widget.RecyclerView(this)
        val adapter = QuestionListAdapter(
            questions = splitGroup,
            answers = answers,
            marked = marked,
            favorites = favorites,
            revealAnswers = revealAnswers,
            revealed = revealed,
            policyProvider = { db.getPolicy() },
            fontScale = fontScale,
            night = nightMode,
            onMarkToggle = { toggleMark(it) },
            onFavoriteToggle = { toggleFavorite(it) },
            onNoteClicked = { showNoteDialog(it) },
            onInkClicked = { startInkFor(it) },
            onAnswerChanged = { onSplitAnswered(it) },
            // 已在分屏里对着材料做题，卡片上的「弹出材料」不再需要
            onMaterialExpand = null
        )
        // 材料在左栏整栏展示，右栏卡片不再重复渲染材料；题号接着整卷往下排
        adapter.hideAllMaterials = true
        adapter.hideModuleHeaders = true
        adapter.indexOffset = splitGroupOffset
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null
        list.setPadding(0, 0, 0, 0)
        list.clipToPadding = false
        // 笔迹钉在题目卡上：右栏一滚锚点就变了，必须重画（RecyclerView 不触发视图树的滚动回调）
        list.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                binding.inkOverlay.invalidate()
            }
        })

        binding.splitQuestionPane.removeAllViews()
        binding.splitQuestionPane.addView(
            list,
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        splitList = list
        splitListAdapter = adapter
        val index = splitGroup.indexOfFirst { it.id == openQuestion.id }.coerceAtLeast(0)
        list.scrollToPosition(index)
    }

    /** 分屏里作答后的联动：与主容器 onAnswered 一致，练习模式即时判分、单选题自动滚到下一题 */
    private fun onSplitAnswered(q: Question) {
        currentPos = questions.indexOfFirst { it.id == q.id }.takeIf { it >= 0 } ?: currentPos
        if (practiceMode && !submitted && q.id !in revealed) {
            revealed.add(q.id)
            splitListAdapter?.revealed = revealed
            splitListAdapter?.notifyDataSetChanged()
        }
        refreshSplitProgress()
        if (!autoNext) return
        if (q.type != com.lytree.qbank.data.model.QuestionType.SINGLE) return
        val nextInGroup = splitGroup.indexOfFirst { it.id == q.id } + 1
        if (nextInGroup >= splitGroup.size) return
        splitList?.postDelayed({
            splitList?.smoothScrollToPosition(nextInGroup)
        }, 220)
    }

    /** 左栏头部的小进度：组内第几题 / 共几题 */
    private fun refreshSplitProgress() {
        val inGroup = (currentPos - splitGroupOffset).coerceIn(0, (splitGroup.size - 1).coerceAtLeast(0))
        binding.tvSplitProgress.text =
            getString(R.string.question_index, inGroup + 1, splitGroup.size)
    }

    /** 收起材料：还原为原浏览模式，把分屏里作答过的题同步到主容器卡片 */
    private fun closeSplit() {
        splitMode = false
        splitList = null
        splitListAdapter = null
        binding.btnClose.setImageResource(R.drawable.ic_fb_close)
        binding.btnClose.contentDescription = getString(R.string.action_close)
        binding.btnClose.setOnClickListener { confirmExit() }
        applySplitVisibility()
        refreshCards()
    }

    private fun applySplitVisibility() {
        if (splitMode) {
            binding.splitBody.visibility = View.VISIBLE
            binding.questionList.visibility = View.GONE
            binding.pager.visibility = View.GONE
        } else {
            binding.splitBody.visibility = View.GONE
            binding.splitQuestionPane.removeAllViews()
            // applyBrowseMode 会按 currentPos 恢复多题 / 单题的显隐与定位
            applyBrowseMode()
        }
        binding.inkOverlay.post { binding.inkOverlay.invalidate() }
    }

    /**
     * 左右分屏入口：参考应用资料分析题的「左材料 / 右题目」双栏。
     * 只有本卷含材料题时才显示；按当前题所属材料分组进入。
     */
    private fun setupSplitEntry() {
        val hasMaterial = questions.any { it.material.isNotBlank() }
        binding.rowSplit.visibility = if (hasMaterial) View.VISIBLE else View.GONE
        binding.splitDivider.visibility = if (hasMaterial) View.VISIBLE else View.GONE
        if (!hasMaterial) return

        binding.rowSplit.setOnClickListener {
            val material = questions.getOrNull(currentPos)?.material?.takeIf { it.isNotBlank() }
                ?: questions.firstOrNull { it.material.isNotBlank() }?.material.orEmpty()
            binding.morePanel.visibility = View.GONE
            startActivity(Intent(this, SplitExamActivity::class.java).apply {
                putExtra(SplitExamActivity.EXTRA_PAPER_ID, paperId)
                putExtra(SplitExamActivity.EXTRA_PAPER_NAME, defaultPaperName())
                if (material.isNotBlank()) putExtra(SplitExamActivity.EXTRA_GROUP_KEY, material)
            })
        }
    }

    private fun toggleMorePanel() {
        binding.morePanel.visibility =
            if (binding.morePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun setFontScale(idx: Int) {
        fontScale = FONT_SCALES[idx.coerceIn(0, FONT_SCALES.size - 1)]
        savePrefs()
        refreshMorePanelState()
        adapter.fontScale = fontScale
        adapter.notifyDataSetChanged()
        pagerAdapter.fontScale = fontScale
        pagerAdapter.notifyDataSetChanged()
        // 分屏右栏与左栏材料跟着一起缩放
        splitListAdapter?.fontScale = fontScale
        splitListAdapter?.notifyDataSetChanged()
        if (splitMode) renderSplitMaterial()
    }

    private fun setNightMode(on: Boolean) {
        if (nightMode == on) return
        nightMode = on
        savePrefs()
        refreshMorePanelState()
        // 夜间模式：整页反色配色（参考应用是纯色切换，这里用背景/文字色翻转实现）
        applyNightMode()
    }

    /**
     * 单题 / 多题浏览切换。
     *
     * 两套容器常驻，只切显隐，位置按「当前正在看的题」双向对齐：
     * 切过去时把目标容器定位到同一道题，所以从第 7 题切过去还在第 7 题。
     */
    private fun setBrowseMode(multi: Boolean) {
        if (multiBrowse == multi) return
        // 以「当前正在看的容器」为位置真值：多题看列表首个可见题，单题看 ViewPager2 当前页。
        // 不直接信 currentPos——它可能被隐藏容器的回调改写。单题这边的页码要先换成题号。
        currentPos = if (multiBrowse) listFirstVisible() else pagerAdapter.questionOfPage(binding.pager.currentItem)
        multiBrowse = multi
        savePrefs()
        refreshMorePanelState()
        applyBrowseMode()
        // 选完即收起面板，避免挡住题目
        binding.morePanel.visibility = View.GONE
    }

    private fun listFirstVisible(): Int =
        (binding.questionList.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition()
            ?.takeIf { it >= 0 } ?: currentPos

    /**
     * Android 15 起强制 edge-to-edge，顶栏会被状态栏压住。
     * 这里把状态栏高度加进顶栏高度内边距，保持「白色顶栏 + 倒计时居中」的原样。
     */
    private fun applyWindowInsets() {
        val density = resources.displayMetrics.density
        val baseH = (44 * density).toInt()
        val padL = binding.topBar.paddingLeft
        val padR = binding.topBar.paddingRight
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { v, insets ->
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            val lp = v.layoutParams
            val want = baseH + top
            if (lp.height != want) {
                lp.height = want
                v.layoutParams = lp
            }
            v.setPadding(padL, top, padR, 0)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupInk() {
        inkToolbar = InkToolbarController(
            inkOverlay = binding.inkOverlay,
            inkBar = binding.inkBar,
            btnInkShade = binding.btnInkShade,
            btnInkMark = binding.btnInkMark,
            btnInkDone = binding.btnInkDone,
            styleInkButton = { on ->
                binding.inkBar.alpha = if (on) 1f else 0.95f
            },
            onToggleMark = { currentQuestion()?.let { toggleMark(it) } },
            btnInkColor = binding.btnInkColor
        )
        inkToolbar.onModeChanged = { on ->
            inkMode = on
            // 面板里的开关与工具条是同一份状态：从顶栏「更多」进、从工具条「完成」出，两边都要对得上
            binding.switchInkMode.setChecked(on, animate = true)
            if (on) syncInkQuestion()
        }
        binding.btnInkUndo.setOnClickListener { binding.inkOverlay.undo() }
        binding.btnInkClear.setOnClickListener { binding.inkOverlay.clear() }

        // 写画模式的滚动通道：分屏时左半屏滚材料、右半屏滚当前题；
        // 多题滚列表，单题滚当前页；材料组页上下分栏，按纵向落点分流（上滚材料 / 下滚题目）
        binding.inkOverlay.scrollTargetResolver = { x, y ->
            when {
                splitMode && x <= binding.splitLeftPane.right -> binding.splitMaterialScroll
                splitMode -> splitList
                multiBrowse -> binding.questionList
                else -> currentPageScrollForInk(y)
            }
        }
        // 工具条悬在右下角，轨道底部让开 76dp，别画到它背后
        binding.inkOverlay.scrollBarBottomInset = (76 * resources.displayMetrics.density).toInt()
        // 竖屏答题页不画那条手柄：屏幕窄，轨道贴着题目文字太抢眼；
        // 热区收窄到屏幕最外沿 16dp，正文里写字不会被当成滚动，贴着边拖仍可滚动
        binding.inkOverlay.railVisible = false
        binding.inkOverlay.barTouchWidth = 16f * resources.displayMetrics.density
        // 笔迹按「本次作答记录 + 目标（题目/材料）」存取；换一轮练习就是另一套圈画
        binding.inkOverlay.recordId = recordId
        // 笔迹钉在题目卡上：卷面滚到哪，圈画跟到哪；不接锚点就退化成「钉在屏幕上」的临时标记
        binding.inkOverlay.anchorSource = { inkAnchors() }
        // 页内纵向滚动（NestedScrollView）不会走 RecyclerView 的 onScrolled，靠视图树的滚动回调统一重绘
        binding.root.viewTreeObserver.addOnScrollChangedListener {
            binding.inkOverlay.invalidate()
        }
        syncInkQuestion()
    }

    /**
     * 遮盖层的内容锚点：题目与材料各出各的锚点。
     *
     * 题目锚点取「卡片左边 + 题干顶边」：卡片左沿在多题 / 单题 / 分屏三种排版里都一样，
     * 题干顶边则不受模块说明卡、材料块这些「有的模式有、有的模式没有」的前置内容影响——
     * 只在卡片顶上打点的话，同一道题换个浏览模式圈画就会整体上下错一截。
     *
     * 材料锚点单独取材料正文（`materialContent`）的位置，target 用材料组首题 id。
     * 材料和题目分开存、分开锚定，材料块换版式时圈画跟着材料走，不会串到题干上。
     */
    private fun inkAnchors(): List<InkOverlay.Anchor> {
        val out = ArrayList<InkOverlay.Anchor>(8)
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

        /** 材料组首题 id：材料锚点的稳定身份（组内题共用一份材料） */
        fun materialIdOf(questionIndex: Int): Long =
            structure.groupAt(questionIndex)?.questions?.first()?.id ?: 0L

        fun addMaterial(view: View, materialId: Long, clip: View? = null) {
            if (materialId == 0L) return
            val r = rectOf(view) ?: return
            val anchor = InkOverlay.Anchor(
                InkOverlay.TargetKind.MATERIAL, materialId,
                r[0], r[1], r[2], r[3]
            )
            // 材料在独立滚动容器里：内容可能比可视区高，命中区裁到容器，别把下方题目算成材料
            if (clip != null) {
                val c = rectOf(clip)
                if (c != null) {
                    anchor.hitRegion(
                        maxOf(r[0], c[0]),
                        maxOf(r[1], c[1]),
                        minOf(r[0] + r[2], c[0] + c[2]),
                        minOf(r[1] + r[3], c[1] + c[3])
                    )
                }
            }
            out.add(anchor)
        }

        fun addQuestion(card: View, id: Long) {
            if (id == 0L || card.width <= 0 || card.height <= 0) return
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
                id,
                cardLeft,
                (stemY - base[1]).toFloat(),
                card.width.toFloat(),
                (cardLoc[1] + card.height - stemY).toFloat().coerceAtLeast(0f)
            )
            // 原点仍取题干顶边（跨浏览模式不错位），命中区放宽到整张卡：
            // 题号 / 操作栏上落笔也算这道题；材料块在卡内时取面积更小的材料锚点
            anchor.hitRegion(
                cardLeft, cardTop,
                cardLeft + card.width, cardTop + card.height
            )
            out.add(anchor)
        }

        /** 题目卡内嵌的材料块（多题浏览 / 普通单题页）：同一材料组只在组首题渲染一次 */
        fun addMaterialInCard(card: View, questionIndex: Int) {
            val box = card.findViewById<View>(R.id.materialBox) ?: return
            if (box.visibility != View.VISIBLE) return
            val content = card.findViewById<View>(R.id.materialContent) ?: return
            addMaterial(content, materialIdOf(questionIndex), card)
        }

        when {
            // 就地分屏：左栏材料 + 右栏这一组题，两边各锚各的
            splitMode -> {
                splitGroup.firstOrNull()?.let {
                    addMaterial(binding.splitMaterialContent, it.id, binding.splitMaterialScroll)
                }
                val rv = splitList ?: return out
                for (i in 0 until rv.childCount) {
                    val child = rv.getChildAt(i)
                    val pos = rv.getChildAdapterPosition(child)
                    if (pos < 0) continue
                    addQuestion(child, splitGroup.getOrNull(pos)?.id ?: 0L)
                }
            }
            // 多题浏览：列表里的每张卡 = 一道题（材料只在材料组首题卡上）
            multiBrowse -> {
                val rv = binding.questionList
                for (i in 0 until rv.childCount) {
                    val child = rv.getChildAt(i)
                    val pos = rv.getChildAdapterPosition(child)
                    if (pos < 0) continue
                    addQuestion(child, questions.getOrNull(pos)?.id ?: 0L)
                    addMaterialInCard(child, pos)
                }
            }
            // 单题浏览：一页可能装多道小题（材料组页），逐张卡算原点，组内每题的圈画都能对上
            else -> {
                val root = currentPageRoot() ?: return out
                val box = root.findViewById<ViewGroup>(R.id.cardContainer) ?: return out
                val materialScroll = root.findViewById<View>(R.id.materialScroll)
                if (materialScroll != null) {
                    // 材料组页：材料在上区独立滚动，卡片里的材料块是隐藏的
                    val materialContent = root.findViewById<View>(R.id.materialContent)
                    val firstIdx = (box.getChildAt(0)?.tag as? QuestionPagerAdapter.CardHolder)?.index ?: -1
                    if (materialContent != null && firstIdx >= 0) {
                        addMaterial(materialContent, materialIdOf(firstIdx), materialScroll)
                    }
                }
                for (i in 0 until box.childCount) {
                    val card = box.getChildAt(i)
                    val idx = (card.tag as? QuestionPagerAdapter.CardHolder)?.index ?: continue
                    addQuestion(card, questions.getOrNull(idx)?.id ?: 0L)
                    if (materialScroll == null) addMaterialInCard(card, idx)
                }
            }
        }
        return out
    }

    /** 单题浏览时当前页的根视图（ViewPager2 里真正承载那一页的 View） */
    private fun currentPageRoot(): View? {
        val rv = binding.pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return null
        for (i in 0 until rv.childCount) {
            val page = rv.getChildAt(i) ?: continue
            if (rv.getChildAdapterPosition(page) == binding.pager.currentItem) return page
        }
        return null
    }

    /** 单题浏览时当前页的滚动容器（普通页：整页 NestedScrollView；材料组页：悬浮题目面板） */
    private fun currentPageScroll(): View? = currentPageRoot()?.findViewById(R.id.pageScroll)

    /**
     * 写画模式下单题浏览的滚动落点：材料组页上下分栏——手柄以上滚上区材料，
     * 以下滚悬浮面板里的题目；普通页没有 materialScroll，直接滚整页。
     * 参数 y 是触摸点在遮盖层坐标系里的纵坐标。
     */
    private fun currentPageScrollForInk(y: Float): View? {
        val root = currentPageRoot() ?: return null
        val material = root.findViewById<View>(R.id.materialScroll) ?: return currentPageScroll()
        val question = root.findViewById<View>(R.id.pageScroll) ?: return material
        val base = IntArray(2)
        binding.inkOverlay.getLocationOnScreen(base)
        val qLoc = IntArray(2)
        question.getLocationOnScreen(qLoc)
        // 落点在悬浮面板顶边以上 → 材料区；否则 → 题目面板
        return if (y < qLoc[1] - base[1]) material else question
    }

    /**
     * 翻到某题并把它在页内露出来。
     *
     * 材料组页（一则材料带多道小题）一页装多题，从答题卡跳过来 / 点某题的「笔」时，
     * 光换页还不够——还要在页内滚到这道题，否则看到的还是组首题。
     */
    private fun showQuestion(questionIndex: Int, smooth: Boolean = false) {
        val page = pagerAdapter.pageOfQuestion(questionIndex)
        val samePage = page == binding.pager.currentItem
        // 换页时把「页内落位」交给翻页回调（那时页码与视图才对得上）；同页直接滚
        pendingPageFocus = if (samePage) -1 else questionIndex
        if (!samePage) binding.pager.setCurrentItem(page, smooth)
        binding.pager.post { locateInPage(questionIndex) }
    }

    /** 把页内某道题顶到可视区顶部（组页内切题 / 跳题用；单题页上什么也不做） */
    private fun locateInPage(questionIndex: Int, smooth: Boolean = false) {
        val root = currentPageRoot() ?: return
        val box = root.findViewById<ViewGroup>(R.id.cardContainer) ?: return
        val scroll = root.findViewById<androidx.core.widget.NestedScrollView>(R.id.pageScroll) ?: return
        for (i in 0 until box.childCount) {
            val child = box.getChildAt(i)
            val idx = (child.tag as? QuestionPagerAdapter.CardHolder)?.index ?: continue
            if (idx != questionIndex) continue
            if (smooth) scroll.smoothScrollTo(0, child.top) else scroll.scrollTo(0, child.top)
            return
        }
    }

    /** 当前正在看的那道题 */
    private fun currentQuestion(): Question? = questions.getOrNull(currentPos)

    /**
     * 把遮盖层绑到「当前题」。
     *
     * 遮盖层常驻可见——退出写画模式后笔迹还留在题目上，所以切题 / 翻页 / 滚动时都要换绑，
     * 否则撤销、清空会落到上一题头上。画面上并不是只画这一题：所有可见题的笔迹都由
     * [inkAnchors] 逐卡绘制，材料组页里同页其它小题的圈画照样显示。
     *
     * 工具条上的「标记」跟随当前题。
     */
    private fun syncInkQuestion() {
        if (!::inkToolbar.isInitialized) return   // onCreate 期间滚动回调可能先到
        // 单题浏览停在「模块说明页」时，这一页上没有题目——不绑任何笔迹
        val onModuleIntro = !multiBrowse && pagerAdapter.isModuleIntroPage(binding.pager.currentItem)
        val inkQ = if (onModuleIntro) null else currentQuestion()
        binding.inkOverlay.bindQuestion(inkQ?.id ?: 0L)
        val markQ = if (onModuleIntro) null else currentQuestion()
        inkToolbar.setMarked(markQ != null && markQ.id in marked)
    }

    /**
     * 从题目卡上的「笔」直接进手写标注。
     *
     * 以前这支笔点开的是「文字笔记」输入框，跟遮盖层没有任何关系——用户点了笔却看不到画布。
     * 现在点笔 = 给**这一题**盖上可写薄纸，并把这题顶到内容区最上面（笔迹是屏幕坐标，
     * 卡片对齐了圈画才落在题面上）；文字笔记改成长按这支笔。
     */
    private fun startInkFor(q: Question) {
        val idx = questions.indexOfFirst { it.id == q.id }
        if (idx >= 0) {
            currentPos = idx
            if (multiBrowse) {
                (binding.questionList.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(idx, 0)
            } else {
                showQuestion(idx)
            }
        }
        syncInkQuestion()
        // 滚动的落位要等下一帧，等排版好了再进写画，遮罩才是对着这张卡铺的
        binding.inkOverlay.post {
            if (idx >= 0) {
                currentPos = idx
                if (multiBrowse) {
                    (binding.questionList.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(idx, 0)
                } else {
                    // 材料组页里这一题未必是组首题，排版完再在页内对齐一次
                    locateInPage(idx)
                }
            }
            syncInkQuestion()
            inkToolbar.setInkMode(true)
            if (!inkHintShown) {
                inkHintShown = true
                android.widget.Toast.makeText(
                    this, R.string.ink_enter_hint, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
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

    /**
     * 起倒计时。[elapsedSec] 为续做时上次已用掉的秒数：
     * 剩余时间 = 总时长 - 已用时，接上次退出的地方继续，而不是重新满时长。
     */
    private fun startTimer(elapsedSec: Int = 0) {
        elapsedBaseSec = elapsedSec
        sessionStartWall = System.currentTimeMillis()
        if (durationMin <= 0) {
            binding.tvTimer.text = "不限时"
            return
        }
        var remaining = durationMin * 60_000L - elapsedSec * 1000L
        // 进度太久远导致「一进来就没时间」时，至少给一分钟缓冲，避免闪交
        if (remaining <= 0) remaining = 60_000L
        lastRemainingSec = (remaining / 1000).toInt()
        timer = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val s = millisUntilFinished / 1000
                lastRemainingSec = s.toInt()
                binding.tvTimer.text = String.format(Locale.CHINA, "%02d:%02d", s / 60, s % 60)
                if (s == 300L) {
                    android.widget.Toast.makeText(
                        this@ExamActivity, "离考试截止时间还剩5分钟，请合理安排答题时间",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onFinish() {
                lastRemainingSec = 0
                binding.tvTimer.text = "00:00"
                android.widget.Toast.makeText(
                    this@ExamActivity, "时间到，自动交卷", android.widget.Toast.LENGTH_LONG
                ).show()
                submit(auto = true)
            }
        }.also { it.start() }
    }

    /** 本次答题已用掉的秒数（保存进度用，含续做前几次会话的累计） */
    private fun elapsedSec(): Int {
        if (durationMin <= 0) {
            // 不限时卷按自然流逝记录，方便「用时」统计
            return elapsedBaseSec + ((System.currentTimeMillis() - sessionStartWall) / 1000).toInt()
        }
        // 限时卷：倒计时走过的时间（onTick 实时维护 lastRemainingSec，后台暂停时不误计）
        val consumed = if (lastRemainingSec < 0) 0 else durationMin * 60 - lastRemainingSec
        return (elapsedBaseSec + consumed.coerceAtLeast(0)).coerceAtMost(durationMin * 60)
    }

    // ---------------- 续做（中途退出保存进度） ----------------

    /**
     * 进上一次未交卷的进度：弹「继续 / 重新开始」。
     * 保存时机在退出确认与 onPause——所以这里能读到的进度一定是最后一次离开时的样子。
     */
    private fun maybeResume() {
        val progress = db.getProgress(paperId)
        if (progress == null) {
            startTimer()
            return
        }
        val answeredCount = progress.answers.size
        val firstUnanswered = questions.indexOfFirst { (progress.answers[it.id] ?: "").isBlank() }
        val pos = if (firstUnanswered >= 0) firstUnanswered + 1 else questions.size
        FbDialog.confirm(
            context = this,
            message = getString(R.string.resume_title) + "\n\n" +
                    getString(R.string.resume_msg, pos, answeredCount),
            okText = getString(R.string.resume_continue),
            cancelText = getString(R.string.resume_restart),
            cancelable = false,
            onOk = { restoreProgress(progress) },
            onCancel = {
                db.clearProgress(paperId)
                restartDraftRecord()
                startTimer()
            }
        )
    }

    /**
     * 重新开始：上一轮的进度与标注一起作废，另起一条草稿记录。
     * 标注是挂在记录上的，只清进度不清记录会把上一轮的圈画带到新一轮。
     */
    private fun restartDraftRecord() {
        db.discardDraftRecords(paperId, DRAFT_SOURCE)
        startedAt = System.currentTimeMillis()
        recordId = db.ensureDraftRecord(paperId, DRAFT_SOURCE, startedAt)
        binding.inkOverlay.recordId = recordId
        syncInkQuestion()
    }

    private fun restoreProgress(p: com.lytree.qbank.data.db.ExamProgress) {
        answers.putAll(p.answers)
        marked.addAll(p.marked)
        refreshCards()
        // 定位到第一道未作答的题（都做完了就停在第 1 题，方便复盘）
        currentPos = questions.indexOfFirst { (answers[it.id] ?: "").isBlank() }
            .takeIf { it >= 0 } ?: 0
        applyBrowseMode()
        syncInkQuestion()
        startTimer(p.elapsedSec)
    }

    /** 离开页面就把进度落库（交卷后不需要）。进程被杀也能从最后一次 onPause 恢复 */
    override fun onPause() {
        super.onPause()
        if (!submitted && paperId > 0L) {
            db.saveProgress(paperId, answers, marked, elapsedSec())
        }
    }

    /**
     * 回到答题页时重读笔迹：资料分析分屏页是另一个 Activity，同一道题可能刚在那边标过，
     * 不清缓存就会看到旧笔迹。每一笔抬手即落库，重读不会丢东西。
     */
    override fun onResume() {
        super.onResume()
        if (::inkToolbar.isInitialized && ::pagerAdapter.isInitialized) {
            binding.inkOverlay.reload()
            syncInkQuestion()
        }
    }

    /**
     * 旋转 / 分屏拖动 / 多窗口改尺寸：清单里声明了 configChanges，Activity 不重建，
     * 免得重走「继续答题 / 重新开始」；但题卡里的材料图与图表是按当时宽度渲染的，
     * 宽度变了要重绑一次，否则图片会按旧宽度留白或溢出。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 平板横竖屏都放开；手机万一本机型允许旋转，仍然钉在竖屏
        ScreenAdapt.applyOrientation(this)
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
            pagerAdapter.notifyDataSetChanged()
        }
        if (splitMode) renderSplitMaterial()
    }

    // ---------------- 标记 / 收藏 / 笔记 ----------------

    private fun toggleMark(q: Question) {
        if (!marked.add(q.id)) marked.remove(q.id)
        refreshCard(q)
        inkToolbar.setMarked(q.id in marked)
    }

    private fun toggleFavorite(q: Question) {
        val fav = q.id !in favorites
        if (fav) favorites.add(q.id) else favorites.remove(q.id)
        // 收藏跨会话保留：落库（对齐参考应用「我的收藏」）
        db.setFavorite(q.id, fav)
        refreshCard(q)
    }

    /**
     * 单张卡的状态变化只刷新这一条。
     * 不要用 notifyDataSetChanged——ViewPager2 在数据全集变更后会重新定位，
     * 可能回调一个偏移过的页码，把「当前题」带偏。
     */
    private fun refreshCard(q: Question) {
        val idx = questions.indexOfFirst { it.id == q.id }
        if (idx < 0) return
        adapter.notifyItemChanged(idx)
        pagerAdapter.notifyItemChanged(pagerAdapter.pageOfQuestion(idx))
    }

    /** 字号 / 夜间 / 交卷这类全局状态变化，两套容器都要重绘 */
    private fun refreshCards() {
        adapter.notifyDataSetChanged()
        pagerAdapter.notifyDataSetChanged()
    }

    private fun showNoteDialog(q: Question) {
        FbDialog.input(
            context = this,
            title = getString(R.string.note_title),
            initial = db.getNote(q.id).orEmpty(),
            hint = getString(R.string.note_hint),
            multiline = true,
            onOk = { text ->
                db.setNote(q.id, text.trim())
                android.widget.Toast.makeText(this, R.string.note_saved, android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ---------------- 更多面板：状态同步 ----------------

    private fun refreshMorePanelState() {
        val idx = FONT_SCALES.indexOfFirst { it == fontScale }.let { if (it < 0) 1 else it }
        styleAa(binding.btnFontSmall, idx == 0)
        styleAa(binding.btnFontMedium, idx == 1)
        styleAa(binding.btnFontLarge, idx == 2)

        styleSegment(binding.btnDay, !nightMode)
        styleSegment(binding.btnNight, nightMode)

        styleSegment(binding.btnMultiBrowse, multiBrowse)
        styleSegment(binding.btnSingleBrowse, !multiBrowse)
    }

    /** 字号档位：参考应用是三个不同字号的「Aa」，选中加深、未选浅灰 */
    private fun styleAa(view: TextView, selected: Boolean) {
        view.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    selected && nightMode -> R.color.fb_night_text
                    selected -> R.color.fb_text_title
                    nightMode -> R.color.fb_night_text_meta
                    else -> R.color.fb_text_meta
                }
            )
        )
        view.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    /** segmented 项（分段胶囊）：选中项白底深字，未选中透明底灰字；夜间换成深色胶囊 */
    private fun styleSegment(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            when {
                selected && nightMode -> R.drawable.bg_fb_segment_pill_night
                selected -> R.drawable.bg_fb_segment_pill
                else -> R.drawable.bg_fb_segment_pill_off
            }
        )
        view.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    selected && nightMode -> R.color.fb_night_segment_text_sel
                    selected -> R.color.fb_segment_text_sel
                    nightMode -> R.color.fb_night_segment_text
                    else -> R.color.fb_segment_text
                }
            )
        )
    }

    // ---------------- 浏览模式 ----------------

    /**
     * 把当前模式落到界面上，并把视图定位到 [currentPos]。
     * 多题浏览：连续滚动所有题；单题浏览：一屏一题，左右滑动切题。
     */
    private fun applyBrowseMode() {
        val target = currentPos.coerceIn(0, (questions.size - 1).coerceAtLeast(0))
        if (multiBrowse) {
            binding.pager.visibility = View.GONE
            binding.questionList.visibility = View.VISIBLE
            binding.questionList.scrollToPosition(target)
        } else {
            binding.questionList.visibility = View.GONE
            binding.pager.visibility = View.VISIBLE
            showQuestion(target)
        }
        // 容器刚切完还没排版，遮盖层这一帧拿不到「能不能滚动」，等排版完再让它重画一次右侧轨道
        binding.inkOverlay.post { binding.inkOverlay.invalidate() }
    }

    /** 定位到第 [position] 题（答题卡跳转 / 单题内切换共用），两种模式都生效 */
    private fun jumpTo(position: Int) {
        if (position !in questions.indices) return
        currentPos = position
        if (multiBrowse) {
            binding.questionList.scrollToPosition(position)
        } else {
            // 材料组页一页多题：换页之后还要在页内滚到这一题
            showQuestion(position)
        }
        syncInkQuestion()
    }

    // ---------------- 夜间模式 ----------------

    /**
     * 把夜间配色铺到整页。
     *
     * 本 App 的夜间是「答题页局部换色」，不用系统 DayNight（见 themes.xml 的说明），
     * 所以每一处颜色都得显式处理——早期版本只换了页面底色和顶栏，题目卡、状态栏、
     * 更多面板全是日间色，看起来就像「主题没生效」。
     *
     * 顺序有讲究：先定面板分段控件的选中态（依赖 [nightMode]），再统一刷新卡片。
     */
    private fun applyNightMode() {
        val night = nightMode

        val pageBg = color(if (night) R.color.fb_night_sheet_bg else R.color.fb_sheet_bg)
        val barBg = color(if (night) R.color.fb_night_bar else R.color.fb_bar_bg)
        val titleColor = color(if (night) R.color.fb_night_text else R.color.fb_text_title)
        val bodyColor = color(if (night) R.color.fb_night_text_body else R.color.fb_text_secondary)
        val metaColor = color(if (night) R.color.fb_night_text_meta else R.color.fb_text_meta)
        val iconColor = color(if (night) R.color.fb_night_icon else R.color.fb_icon)
        val dividerColor = color(if (night) R.color.fb_night_divider else R.color.fb_divider)

        // 页面与顶栏
        binding.root.setBackgroundColor(pageBg)
        binding.topBar.setBackgroundColor(barBg)
        binding.tvPaperName.setTextColor(titleColor)
        binding.tvTimer.setTextColor(bodyColor)

        // 顶栏图标：关闭 / 下载 / 交卷 / 答题卡 / 更多 / 倒计时
        listOf(
            binding.btnClose, binding.btnDownload, binding.btnCard, binding.btnMore,
            binding.icTimer, binding.icSubmit
        ).forEach { it.setColorFilter(iconColor) }
        binding.tvSubmitLabel.setTextColor(metaColor)

        // 更多面板
        binding.morePanel.setBackgroundResource(
            if (night) R.drawable.bg_fb_panel_night else R.drawable.bg_fb_panel
        )
        val trackRes =
            if (night) R.drawable.bg_fb_segment_track_night else R.drawable.bg_fb_segment_track
        binding.rowDayNight.setBackgroundResource(trackRes)
        binding.rowBrowseMode.setBackgroundResource(trackRes)
        binding.tvAutoNextLabel.setTextColor(titleColor)
        binding.tvPracticeModeLabel.setTextColor(titleColor)
        binding.tvInkModeLabel.setTextColor(titleColor)
        binding.switchInkMode.setNightMode(night)
        binding.tvSplitLabel.setTextColor(titleColor)
        binding.splitDivider.setBackgroundColor(dividerColor)
        binding.icSplitArrow.setColorFilter(metaColor)
        binding.tvImportAnswerLabel.setTextColor(titleColor)
        binding.answerDivider.setBackgroundColor(dividerColor)
        binding.icAnswerArrow.setColorFilter(metaColor)
        binding.switchAutoNext.setNightMode(night)

        // 遮盖层工具条
        binding.inkBar.setBackgroundResource(
            if (night) R.drawable.bg_fb_action_night else R.drawable.bg_fb_action
        )
        binding.btnInkUndo.setTextColor(titleColor)
        // 遮盖档位 / 标记 / 完成三个按钮的配色由控制器按状态算（标记态是橙色，不能一刀切换白）
        inkToolbar.applyNightMode(night)
        binding.inkOverlay.night = night
        // btnInkClear 保持红字，深底上同样醒目，不另设夜间色

        // 系统栏跟着顶栏走，否则深色顶栏上会压一条白状态栏
        applySystemBars(night, barBg)

        // 弹出材料就地分屏：左栏材料底 / 材料标签 / 进度 / 收起按钮 / 分隔线
        binding.splitLeftPane.setBackgroundColor(color(if (night) R.color.fb_night_material_bg else R.color.fb_bar_bg))
        binding.tvSplitTag.setBackgroundResource(if (night) R.drawable.bg_fb_tag_night else R.drawable.bg_fb_material_tag)
        binding.tvSplitTag.setTextColor(color(if (night) R.color.fb_night_tag_text else R.color.fb_blue_ink))
        binding.tvSplitProgress.setTextColor(metaColor)
        binding.btnSplitCollapse.setTextColor(metaColor)
        binding.splitPaneDivider.setBackgroundColor(color(if (night) R.color.fb_night_table_line else R.color.fb_table_line))
        splitListAdapter?.night = night
        splitListAdapter?.notifyDataSetChanged()
        if (splitMode) renderSplitMaterial()

        // 面板里分段控件的选中态依赖 nightMode，必须先刷新
        refreshMorePanelState()

        // 题目卡（两套容器共用 binder 的配色）
        adapter.night = night
        pagerAdapter.night = night
        refreshCards()
    }

    /** 状态栏 / 导航栏：夜间用深底 + 浅色图标，日间反之 */
    private fun applySystemBars(night: Boolean, barColor: Int) {
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    // ---------------- 偏好持久化 ----------------

    private fun loadPrefs() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        fontScale = FONT_SCALES.getOrElse(p.getInt(KEY_FONT_SCALE, 1)) { 1f }
        nightMode = p.getBoolean(KEY_NIGHT, false)
        multiBrowse = p.getBoolean(KEY_MULTI_BROWSE, true)
        autoNext = p.getBoolean(KEY_AUTO_NEXT, true)
        practiceMode = p.getBoolean(KEY_PRACTICE, false)
    }

    private fun savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().apply {
            putInt(KEY_FONT_SCALE, FONT_SCALES.indexOfFirst { it == fontScale }.let { if (it < 0) 1 else it })
            putBoolean(KEY_NIGHT, nightMode)
            putBoolean(KEY_MULTI_BROWSE, multiBrowse)
            putBoolean(KEY_AUTO_NEXT, autoNext)
            putBoolean(KEY_PRACTICE, practiceMode)
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
            putExtra(AnswerCardActivity.EXTRA_CURRENT, currentPos)
            putExtra(AnswerCardActivity.EXTRA_NIGHT, nightMode)
        }
        startActivityForResult(intent, REQ_CARD)
    }

    /** 按大类分组，得到每题所属的分组标题（与卷面「大类题型」同一口径，不另算一套） */
    private fun groupLabels(): ArrayList<String> {
        val out = ArrayList<String>(questions.size)
        questions.forEachIndexed { index, q ->
            // 大类名为空（多数手打试题不填 subject）时退回知识点，都没有就归「未分类」
            out.add(structure.sectionAt(index)?.name ?: q.knowledge.ifBlank { "未分类" })
        }
        return out
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CARD && resultCode == RESULT_OK && data != null) {
            // 答题卡底部的「交卷」
            if (data.getBooleanExtra(AnswerCardActivity.EXTRA_SUBMIT, false)) {
                confirmSubmit()
                return
            }
            val jump = data.getIntExtra(AnswerCardActivity.EXTRA_JUMP, -1)
            if (jump in questions.indices) {
                // 答题卡跳题针对整卷；分屏只看得到一组材料，先收起再跳
                if (splitMode) closeSplit()
                jumpTo(jump)
            }
        }
    }

    // ---------------- 交卷 ----------------

    private fun confirmExit() {
        if (submitted) { finish(); return }
        val unanswered = questions.count { (answers[it.id] ?: "").isBlank() }
        // 对齐参考应用：退出即保存进度，下次进入可「继续答题」接着做
        val msg = if (unanswered > 0) {
            "你还有 $unanswered 道题目未做完。退出后进度会保留，下次进入可以继续答题。"
        } else {
            "退出后进度会保留，下次进入可以继续答题。确定退出吗？"
        }
        FbDialog.confirm(this, msg, okText = "退出", cancelText = "继续答题", onOk = {
            db.saveProgress(paperId, answers, marked, elapsedSec())
            finish()
        })
    }

    private fun confirmSubmit() {
        val unanswered = questions.count { (answers[it.id] ?: "").isBlank() }
        val missing = questions.count { !Judge.hasStandard(it) }
        if (missing > 0) {
            promptMissingAnswers(unanswered, missing)
            return
        }
        val msg = if (unanswered > 0) {
            "你还有 $unanswered 道题目未作答，确定交卷吗？"
        } else {
            "确定交卷并查看结果吗？"
        }
        FbDialog.confirm(this, msg, onOk = { submit(auto = false) })
    }

    /**
     * 交卷时发现整卷没答案：先问清楚是「现在补答案」还是「先交卷、之后再补」。
     * 两条路都通——补答案后成绩报告会按已有作答直接重判，不用重做一遍；
     * 手上没答案文件也不要紧，交卷后可以在成绩报告里逐题补录。
     */
    private fun promptMissingAnswers(unanswered: Int, missing: Int) {
        val msg = buildString {
            if (unanswered > 0) append("你还有 $unanswered 道题目未作答。\n\n")
            append("本卷有 $missing 题缺少标准答案。")
            append("现在导入答案文件可以立刻判分；也可以先交卷，")
            append("之后在成绩报告里点「补答案 → 逐题补录」把答案填进来，按你的作答重新判分。")
        }
        FbDialog.list(
            context = this,
            title = getString(R.string.pending_answer),
            message = msg,
            items = arrayOf(getString(R.string.action_import_answer), "直接交卷"),
            onPick = { which ->
                if (which == 0) {
                    submitAfterImport = true
                    launchAnswerPicker()
                } else {
                    submit(auto = false)
                }
            }
        )
    }

    private fun submit(auto: Boolean) {
        if (submitted) return
        submitted = true
        timer?.cancel()
        // 已交卷：清掉未交卷进度，下次进入是全新一轮
        db.clearProgress(paperId)
        revealAnswers = true
        adapter.revealAnswers = true
        adapter.notifyDataSetChanged()
        pagerAdapter.revealAnswers = true
        pagerAdapter.notifyDataSetChanged()

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
            fullScore = summary.fullScore,
            // 标记随本次作答留档：交卷时 clearProgress 会删掉进度里的 marked，
            // 这里先把它写进记录，回看报告才知道当时标了哪几题
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
}
