package com.lytree.qbank.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.data.model.PaperRef
import com.lytree.qbank.data.model.PaperStructure
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ActivityQuestionReviewBinding
import com.lytree.qbank.engine.Policy
import com.lytree.qbank.ui.paper.PaperDetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 解析页（成绩报告 → 点题号 / 「错题解析」）。
 *
 * 版式就是答题页本身：同一张题目卡 [QuestionCardBinder]、同一套题号 / 题型标签 / 题干字号 /
 * 选项圆标，连两种浏览方式都是答题页那两套——
 * - **多题**（默认）：连续卷面 [QuestionListAdapter]，一则材料带多道小题时材料只在组首题渲染一次，
 *   左侧树脊、「材料 N」标签、题间细线、大类说明卡全都在；
 * - **单题**：一屏一题 [QuestionPagerAdapter] + ViewPager2，材料组页上材料 / 下题目上下分栏。
 *
 * 顶栏右侧的「多题 / 单题」写的是**当前**方式，点一下换另一种，档位与答题页共用一份偏好
 * （`exam_prefs.multi_browse`）——做题时怎么翻，看解析时还是怎么翻。
 *
 * 与答题页的差别只有两处：
 * - **选项只读**：点不动、也不挂打叉；
 * - 卡尾多出解析块（正确答案 / 你的答案 / 结果 / 解析）与统计条、考点来源、笔记卡。
 *
 * 右上角三件事照旧：
 * - **笔**：盖一层可写薄纸直接在题上圈画，笔迹按「本条记录 + 题目」存取，
 *   答题时画过的圈回来看解析还在（与答题页同一个 [InkOverlay]，见 [setupInk]）；
 * - **标记**：只是**临时记号**——标的是「正在看的这一题」，换题就自动消掉，
 *   不写库、也不改动记录里的标记（成绩报告格子上的橙点还是交卷时留的那几道）；
 * - **收藏**：与答题页共用 `favorites` 表，收了就是收了，退出还在。
 *
 * [EXTRA_NUMBERS] 决定这次翻的是哪些题：错题解析只传错题在原卷里的下标，
 * 卡上的题号、顶栏的「第 N/M 题」都按原卷算，跟答卷对得上。
 */
class QuestionReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORD_ID = "record_id"
        /** 要翻的题：原卷题号下标（0 起）；不传 = 整卷 */
        const val EXTRA_NUMBERS = "numbers"
        const val EXTRA_START = "start"

        private const val PREFS = "exam_prefs"
        private const val KEY_FONT_SCALE = "font_scale"
        /** 与答题页共用：日间 / 夜间 */
        private const val KEY_NIGHT = "night_mode"
        /** 与答题页共用：多题连续卷面 / 单题一屏一题 */
        private const val KEY_MULTI_BROWSE = "multi_browse"
        /** 与答题页同一组字号档位，复习时字号不该和做题时不一样（整体比最初放大一号） */
        private val FONT_SCALES = floatArrayOf(1.0f, 1.15f, 1.3f)
    }

    private lateinit var binding: ActivityQuestionReviewBinding
    private lateinit var db: QBankDb
    private lateinit var inkToolbar: InkToolbarController

    /**
     * 「弹出材料」就地左右分屏：与答题页**同一个控制器**。
     *
     * 解析页在这里只改一件事——卡片绑定是只读的、卡尾多出答案 / 解析块（见 [setupList]），
     * 分屏的版式、比例记忆、左栏整页查看、中缝拖拽全部共用，不会做出两套手感。
     */
    private lateinit var splitPane: SplitPaneController

    /** 日间 / 夜间：与答题页共用 `exam_prefs.night_mode` */
    private var night = false

    /** 顶栏那颗「多题 / 单题」：文字写当前方式，点一下换另一种 */
    private lateinit var modeAction: TextView

    private var recordId = 0L
    private var record: ExamRecord? = null
    private var numbers: List<Int> = emptyList()

    /** 这次翻的题（卷面顺序）；[numbers] 是它们在原卷里的题号下标 */
    private var questions: List<Question> = emptyList()
    private var policy: Policy? = null

    /** 多题连续卷面 / 单题一屏一题：与答题页共用同一份偏好 */
    private var multiBrowse = true

    private var listAdapter: QuestionListAdapter? = null
    private var pagerAdapter: QuestionPagerAdapter? = null

    /** 当前正看着的题在 [questions] 里的下标（多题取首个可见项，单题取当前页首题） */
    private var currentPos = 0

    /** 作答（题目 id → 用户答案）：解析块里「你的答案 / 结果」由 binder 判 */
    private val answers = HashMap<Long, String>()

    /** 本次卷面结构：材料组划分与材料序号都从这里取（与答题页同一份口径） */
    private lateinit var paper: PaperStructure

    /** 解析三项（题目 id → 统计）：进页时一次读全，笔记卡 / 统计行都从这里取 */
    private var stats: Map<Long, QuestionStats> = emptyMap()

    /** 本卷笔记（题目 id → 正文）：进页时批读；编辑后就地更新这份映射再重绑 */
    private var notes: MutableMap<Long, String> = mutableMapOf()

    /**
     * 临时标记：最多一道题，换题就清。
     *
     * 与答题页的 `marked` 不是一回事——那边进进度、进记录，这边只是「看这一题时做个记号」，
     * 所以不落库、也不回写记录（见 [toggleMark]）。
     */
    private val marked = HashSet<Long>()

    /** 本卷已收藏的题，与答题页共用 `favorites` 表 */
    private val favorites = HashSet<Long>()

    /** 材料 → 组首题 id：材料锚点的稳定身份，与答题页同一口径（笔迹才对得上） */
    private var materialFirstId: Map<Long, Long> = emptyMap()

    /** 写画提示只说一次（同一页面里反复进出写画不必重复教学） */
    private var inkHintShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手机锁竖屏 / 平板跟随旋转（方向锁是 Activity 级的，答题页锁了这里也要跟着锁）
        ScreenAdapt.applyOrientation(this)
        binding = ActivityQuestionReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 没有底部操作条了，卷面白底直接顶到屏幕底边：最后一张卡要让开手势导航条
        binding.content.applyNavBarBottomPadding()
        db = QBankDb.get(this)

        recordId = intent.getLongExtra(EXTRA_RECORD_ID, 0L)
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val r = db.getRecord(recordId) ?: return@withContext null
                // 收藏是全局表，这里一次读全再按本卷过滤，免得每题一次查询
                val favoriteIds = db.listFavoriteIds()
                // 统计两项 + 笔记一次读全：统计每题查一次历史（本机数据量级小），
                // 笔记按题 id 集合批读——绑卡片时不再碰库
                val times = db.getQuestionTimes(recordId)
                val stats = HashMap<Long, QuestionStats>()
                val noteMap = db.getNotes(r.results.map { it.question.id }).toMutableMap()
                r.results.forEach { res ->
                    val (_, _, commonWrong) = db.questionHistoryStats(res.question.id)
                    stats[res.question.id] = QuestionStats(
                        seconds = times[res.question.id],
                        commonWrong = commonWrong
                    )
                }
                LoadedData(r, db.getPolicy(), favoriteIds, stats, noteMap)
            }
            if (isFinishing || isDestroyed) return@launch
            val data = loaded ?: run { finish(); return@launch }
            render(data)
        }
    }

    /** 一次后台装载的全部数据（onCreate 的 IO 块与 render 之间的载体） */
    private data class LoadedData(
        val record: ExamRecord,
        val policy: Policy,
        val favoriteIds: Set<Long>,
        val stats: Map<Long, QuestionStats>,
        val notes: MutableMap<Long, String>
    )

    /**
     * 退回时把还没落库的笔迹写出去——写画本来就是「抬笔即存」，再刷一次更稳。
     * 收藏的结果码在点星那一刻就给（见 [toggleFavorite]），不等到这里。
     */
    override fun onPause() {
        super.onPause()
        if (::inkToolbar.isInitialized) binding.inkOverlay.flush()
    }

    /** 分屏时返回键先收起分屏（与答题页同一个手势口径），再退解析页 */
    override fun onBackPressed() {
        if (::splitPane.isInitialized && splitPane.isOpen) {
            splitPane.close()
            return
        }
        super.onBackPressed()
    }

    /**
     * 平板旋转 / 多窗口改尺寸：**不重建页面，就地重排**。
     *
     * 清单里给这一页也配了 `configChanges`（与答题页同一份）。不配的话一旋转 Activity 就重建——
     * 「当前题」回到 intent 里的起点、临时标记与笔记编辑状态全丢、正开着的分屏被收起；
     * 而答题页同样操作是原地不动的。这里只做必要的事：方向锁按形态重下（手机万一本机型
     * 允许旋转，仍钉回竖屏）、两套容器按新宽度重绑、分屏重排并把位置留在当前题。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ScreenAdapt.applyOrientation(this)
        // 宽度变了：卡片里的表格 / 配图 / 选项行都要重新量一遍
        listAdapter?.notifyDataSetChanged()
        pagerAdapter?.notifyDataSetChanged()
        // 分屏：左栏材料按新栏宽重画、右栏整组卡片重绑，位置停在当前题
        if (::splitPane.isInitialized) splitPane.refresh()
        applySystemBars()
    }

    private fun render(data: LoadedData) {
        val record = data.record
        val policy = data.policy
        val favoriteIds = data.favoriteIds
        this.record = record
        this.policy = policy
        this.stats = data.stats
        this.notes = data.notes
        if (record.results.isEmpty()) {
            finish(); return
        }

        val asked = intent.getIntArrayExtra(EXTRA_NUMBERS)?.toList().orEmpty()
            .filter { it in record.results.indices }
        numbers = asked.ifEmpty { record.results.indices.toList() }
        val items = numbers.map { record.results[it] }
        questions = items.map { it.question }

        // 作答按题 id 带进卡片：解析块里的「你的答案 / 结果」直接由 binder 判
        answers.clear()
        record.results.forEach { answers[it.question.id] = it.userAnswer }
        // 材料锚点用「组首题 id」：同一则材料带的几道题共用一份材料笔迹（与答题页一致）
        val firstOfMaterial = HashMap<String, Long>()
        val firstIdByQuestion = HashMap<Long, Long>()
        record.results.forEach { r ->
            val m = r.question.material
            if (m.isNotBlank()) {
                val first = firstOfMaterial.getOrPut(m) { r.question.id }
                firstIdByQuestion[r.question.id] = first
            }
        }
        materialFirstId = firstIdByQuestion

        // 标记是临时记号，进页面时一律从空白开始；收藏按库里的来
        marked.clear()
        favorites.clear()
        favorites.addAll(record.results.map { it.question.id }.filter { it in favoriteIds })

        // 卷面结构：材料组 / 大类 / 组内题量都从这里取，与答题页同一份口径。
        // 错题解析给的是子集，结构按子集重算——相邻同材料的题照样并成一组。
        paper = PaperStructure(questions)
        // 题号按原卷编号：错题解析只翻错题，但卡上还得写「第 8 题」而不是「第 3 题」
        val numberOf: (Int) -> Int = { index -> numbers[index] }

        val start = intent.getIntExtra(EXTRA_START, 0).coerceIn(0, questions.lastIndex)
        currentPos = start

        // 显示口径先读进来：字号 / 日夜 / 浏览方式都与答题页共用一份偏好，
        // 做题时怎么设的，回来看解析还是什么样子。
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        night = prefs.getBoolean(KEY_NIGHT, false)
        multiBrowse = prefs.getBoolean(KEY_MULTI_BROWSE, !isTablet)

        setupList(answers, paper, numberOf)
        setupPager(answers, paper, numberOf)

        // 顶栏右侧：浏览方式（写当前方式，点一下换）+ 看原题 + 更多（字号 / 日夜）
        modeAction = binding.titleBar.addTextAction(getString(R.string.review_mode_multi)) {
            toggleBrowse()
        }
        // 原题里的材料表格 / 配图 / 图片选项，只有试卷详情渲染得全
        binding.titleBar.addTextAction(getString(R.string.report_action_open_paper)) {
            openInPaper(currentPos)
        }
        binding.titleBar.addIconAction(R.drawable.ic_fb_more, getString(R.string.action_more)) {
            toggleMorePanel()
        }

        setupSplitPane()
        setupMorePanel()
        setupInk()
        applyMode(persist = false)
        applyNightMode()
    }

    /** 多题连续卷面：与答题页同一个列表适配器，只读 + 答案解析 */
    private fun setupList(
        answers: MutableMap<Long, String>,
        paper: PaperStructure,
        numberOf: (Int) -> Int
    ) {
        val judge = policy ?: return
        val listAdapter = QuestionListAdapter(
            questions = questions,
            structure = paper,
            answers = answers,
            marked = marked,
            favorites = favorites,
            revealAnswers = true,
            policyProvider = { judge },
            fontScale = fontScale(),
            numberAt = numberOf,
            night = night,
            // 只读只管作答；笔 / 标记 / 收藏看解析时照样要做
            readOnly = true,
            showMarkFavorite = true,
            showInk = true,
            stats = stats,
            notes = notes,
            onMarkToggle = { q -> toggleMark(q) },
            onFavoriteToggle = { q -> toggleFavorite(q) },
            onNoteClicked = { q -> showNoteDialog(q) },
            onInkClicked = { q -> startInkFor(q) },
            onAnswerChanged = {},
            onMaterialExpand = { q -> openMaterial(q) }
        )
        this.listAdapter = listAdapter

        binding.questionList.layoutManager = LinearLayoutManager(this)
        binding.questionList.adapter = listAdapter
        binding.questionList.itemAnimator = null
        binding.questionList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // 笔迹钉在题目卡上，卡片位置一变就得重画（RecyclerView 不触发视图树的滚动回调）
                binding.inkOverlay.invalidate()
                if (!multiBrowse) return
                val first = (rv.layoutManager as? LinearLayoutManager)
                    ?.findFirstVisibleItemPosition() ?: return
                if (first < 0 || first == currentPos) return
                // 换题：临时标记跟着清（「临时」就是这意思）
                currentPos = first
                syncChrome()
                clearTempMark()
                syncInkQuestion()
            }
        })
    }

    /** 单题一屏一题：与答题页同一个分页适配器（材料组页上材料 / 下题目） */
    private fun setupPager(
        answers: MutableMap<Long, String>,
        paper: PaperStructure,
        numberOf: (Int) -> Int
    ) {
        val judge = policy ?: return
        val pagerAdapter = QuestionPagerAdapter(
            questions = questions,
            structure = paper,
            answers = answers,
            marked = marked,
            favorites = favorites,
            policyProvider = { judge },
            fontScale = fontScale(),
            revealAnswers = true,
            night = night,
            numberAt = numberOf,
            // 平板与答题页同一套版式：材料组按题拆页，材料固定在这页上区
            tabletSplit = isTablet,
            readOnly = true,
            showMarkFavorite = true,
            showInk = true,
            stats = stats,
            notes = notes,
            onMarkToggle = { q -> toggleMark(q) },
            onFavoriteToggle = { q -> toggleFavorite(q) },
            onNoteClicked = { q -> showNoteDialog(q) },
            onInkClicked = { q -> startInkFor(q) },
            onAnswerChanged = {},
            onMaterialExpand = { q -> openMaterial(q) }
        )
        this.pagerAdapter = pagerAdapter

        binding.pager.offscreenPageLimit = 1
        binding.pager.adapter = pagerAdapter
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (multiBrowse) return
                // 页码表里夹着「大类说明页」，换回题号才是当前题
                currentPos = pagerAdapter.questionOfPage(position)
                syncChrome()
                clearTempMark()
                syncInkQuestion()
                binding.inkOverlay.invalidate()
            }
        })
    }

    /**
     * 落地当前浏览方式：两个容器只显一个，位置接着刚才那道题。
     *
     * 多题 → 单题：翻到这道题所在的那一页；单题 → 多题：把这题滚到顶部。
     * [persist] 为 true 才写偏好（进页面时只是读出偏好，不该回写）。
     */
    private fun applyMode(persist: Boolean = true) {
        // 分屏开着时不动两个容器的显隐：收起分屏那一步会自己按当前题恢复
        if (splitPane.isOpen) return
        val multi = multiBrowse
        binding.questionList.visibility = if (multi) View.VISIBLE else View.GONE
        binding.pager.visibility = if (multi) View.GONE else View.VISIBLE
        modeAction.text = getString(
            if (multi) R.string.review_mode_multi else R.string.review_mode_single
        )

        if (multi) {
            binding.questionList.post {
                (binding.questionList.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(currentPos, 0)
            }
        } else {
            val page = pagerAdapter?.pageOfQuestion(currentPos) ?: 0
            binding.pager.setCurrentItem(page, false)
            // 页内还有多题（材料组页）时保住原来那道题：标题不该掉到组首
            if (pagerAdapter?.questionsOfPage(page)?.contains(currentPos) != true) {
                currentPos = pagerAdapter?.questionOfPage(page) ?: currentPos
            }
        }
        syncChrome()
        syncInkQuestion()
        binding.inkOverlay.invalidate()
        if (persist) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_MULTI_BROWSE, multi).apply()
        }
    }

    /** 顶栏「多题 / 单题」：换浏览方式并给一句反馈 */
    private fun toggleBrowse() {
        // 分屏里换浏览方式：先收起，再按新方式还原（否则容器显隐被分屏挡住）
        if (splitPane.isOpen) splitPane.close()
        multiBrowse = !multiBrowse
        applyMode()
        Toast.makeText(
            this,
            getString(
                R.string.review_mode_switched,
                getString(
                    if (multiBrowse) R.string.mode_multi_browse else R.string.mode_single_browse
                )
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 顶栏标题跟着当前题走（题号按原卷算） */
    private fun syncChrome() {
        val total = record?.results?.size ?: numbers.size
        val number = numbers.getOrNull(currentPos) ?: 0
        binding.titleBar.setTitle(getString(R.string.report_analysis_page_title, number + 1, total))
    }

    /**
     * 「弹出材料」：**与答题页一模一样，就地切成左右分屏**。
     *
     * 以前这里直接 startActivity 到整页只读，看解析时点一下材料，整个解析页就被顶走，
     * 回来还得重新定位；而且夜间模式下弹出来是白底（没把 night 传过去）。
     * 现在走同一个 [SplitPaneController]：左栏材料 / 右栏这组小题，中缝可拖、可收起、
     * 可再弹整页看表格；差别只有右栏卡片是只读的、卡尾带答案与解析。
     */
    private fun openMaterial(q: Question) {
        if (q.material.isBlank()) return
        binding.morePanel.visibility = View.GONE
        splitPane.open(q)
    }

    /**
     * 分屏接线：解析页只注入「只读 + 答案解析」的卡片绑定，其余与答题页同一份实现。
     */
    private fun setupSplitPane() {
        splitPane = SplitPaneController(
            ctx = this,
            views = SplitPaneController.Views(
                body = binding.splitBody,
                leftPane = binding.splitLeftPane,
                tvTag = binding.tvSplitTag,
                tvProgress = binding.tvSplitProgress,
                btnExpand = binding.btnSplitExpand,
                btnCollapse = binding.btnSplitCollapse,
                materialScroll = binding.splitMaterialScroll,
                materialContent = binding.splitMaterialContent,
                handle = binding.splitHandle,
                handleBar = binding.splitHandleBar,
                questionPane = binding.splitQuestionPane,
                // content 整块已经让过底部导航条（见 onCreate），材料别再让一次
                materialPadNavBar = false
            ),
            questions = { questions },
            structure = { paper },
            inkOverlay = binding.inkOverlay,
            isNight = { night },
            fontScale = { fontScale() },
            currentIndex = { currentPos },
            setCurrentIndex = { currentPos = it },
            onQuestionChanged = {
                // 分屏里滚到别的题：顶栏题号 / 笔迹跟着换（临时标记是「看这一题时」的记号）
                syncChrome()
                clearTempMark()
                syncInkQuestion()
                binding.inkOverlay.invalidate()
            },
            bindCard = { card, q, index, isLast -> bindSplitCard(card, q, index, isLast) },
            onOpenFullMaterial = { material -> openMaterialFullPage(material) },
            onVisibilityChanged = { open ->
                if (open) {
                    binding.splitBody.visibility = View.VISIBLE
                    binding.questionList.visibility = View.GONE
                    binding.pager.visibility = View.GONE
                } else {
                    binding.splitBody.visibility = View.GONE
                    // 收起分屏顺手收起「更多」面板（否则它浮在还原后的卷面上）
                    binding.morePanel.visibility = View.GONE
                    // 收起后按当前题恢复多题 / 单题版式
                    applyMode(persist = false)
                    syncChrome()
                    syncInkQuestion()
                }
                binding.inkOverlay.post { binding.inkOverlay.invalidate() }
            }
        )
    }

    /**
     * 分屏右栏的一张题卡：与卷面同一套绑定，只读 + 卡尾答案 / 解析 / 统计 / 笔记。
     * 材料已在左栏整栏展示，卡内不再重复渲染。
     */
    private fun bindSplitCard(
        card: com.lytree.qbank.databinding.ItemQuestionPageBinding,
        q: Question,
        index: Int,
        isLast: Boolean
    ) {
        val judge = policy ?: return
        QuestionCardBinder.bind(
            b = card,
            // 错题解析只翻错题：题号仍按原卷算
            position = numbers.getOrNull(index) ?: index,
            q = q,
            answers = answers,
            marked = marked,
            favorites = favorites,
            revealAnswers = true,
            policy = judge,
            fontScale = fontScale(),
            cb = QuestionCardBinder.Callbacks(
                // 标记 / 收藏 / 笔记的重绑都由 refreshCard 分流（分屏开着时落到控制器的卡片上）
                onMarkToggle = { qq -> toggleMark(qq) },
                onFavoriteToggle = { qq -> toggleFavorite(qq) },
                onInkClicked = { qq -> startInkFor(qq) },
                onNoteClicked = { qq -> showNoteDialog(qq) },
                onAnswerChanged = {}
            ),
            night = night,
            // 材料在左栏整栏展示，卡片不再重复渲染
            hideMaterial = true,
            moduleHeader = null,
            materialGroupSize = splitPane.groupSize,
            showDivider = !isLast,
            readOnly = true,
            showMarkFavorite = true,
            showInk = true,
            stats = stats,
            notes = notes
        )
    }

    /** 左栏「弹出材料」：整页只读看表格（与答题页同一个页面，夜间同色） */
    private fun openMaterialFullPage(material: String) {
        startActivity(
            Intent(this, MaterialPopupActivity::class.java).apply {
                putExtra(MaterialPopupActivity.EXTRA_MATERIAL, material)
                putExtra(MaterialPopupActivity.EXTRA_NIGHT, night)
                putExtra(MaterialPopupActivity.EXTRA_FONT_SCALE, fontScale())
            }
        )
    }

    // ---------------- 更多（字号 / 日夜）：与答题页共用偏好 ----------------

    /**
     * 「更多」面板：只放**显示相关**的两项（字号、日夜）。
     *
     * 答题页那份面板里还有自动下一题 / 练习模式 / 导入答案，那些是作答专属，
     * 解析页不该出现；字号与日夜是看题时的眼睛问题，做题时怎么调，看解析还能接着调，
     * 所以档位与答题页写同一份 `exam_prefs`。
     */
    private fun setupMorePanel() {
        binding.btnFontSmall.setOnClickListener { setFontScale(0) }
        binding.btnFontMedium.setOnClickListener { setFontScale(1) }
        binding.btnFontLarge.setOnClickListener { setFontScale(2) }
        binding.btnDay.setOnClickListener { setNightMode(false) }
        binding.btnNight.setOnClickListener { setNightMode(true) }
        refreshMorePanelState()
    }

    private fun toggleMorePanel() {
        binding.morePanel.visibility =
            if (binding.morePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** 字号：写回与答题页共用的档位，三套容器（列表 / 分页 / 分屏）一起重绑 */
    private fun setFontScale(idx: Int) {
        val i = idx.coerceIn(0, FONT_SCALES.lastIndex)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_FONT_SCALE, i).apply()
        val scale = FONT_SCALES[i]
        listAdapter?.fontScale = scale
        pagerAdapter?.fontScale = scale
        listAdapter?.notifyDataSetChanged()
        pagerAdapter?.notifyDataSetChanged()
        splitPane.refresh()
        refreshMorePanelState()
    }

    /** 日间 / 夜间：与答题页同一个偏好键，回答题页也是这个颜色 */
    private fun setNightMode(on: Boolean) {
        if (night == on) return
        night = on
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_NIGHT, on).apply()
        applyNightMode()
    }

    /**
     * 把夜间配色铺到整页：标题栏 / 卷面底 / 两套容器 / 遮盖层 / 写画工具条 / 分屏 / 系统栏。
     *
     * 与答题页同一套 `fb_night_*` 色板；解析页以前完全没有夜间（顶栏也没有入口），
     * 于是「做题时开着夜间，点错题解析白得刺眼」。
     */
    private fun applyNightMode() {
        val barColor = color(R.color.fb_night_bar, R.color.fb_bar_bg)
        binding.titleBar.applyNightMode(night)
        binding.content.setBackgroundColor(color(R.color.fb_night_sheet_bg, R.color.fb_sheet_bg))

        // 更多面板：底 / 分段轨道（选中胶囊在 refreshMorePanelState 里按 night 换）
        binding.morePanel.setBackgroundResource(
            if (night) R.drawable.bg_fb_panel_night else R.drawable.bg_fb_panel
        )
        binding.rowDayNight.setBackgroundResource(
            if (night) R.drawable.bg_fb_segment_track_night else R.drawable.bg_fb_segment_track
        )

        // 写画：工具条底色、图标两态、遮盖纸
        binding.inkBar.root.setBackgroundColor(barColor)
        inkToolbar.applyNightMode(night)
        binding.inkOverlay.night = night

        // 系统栏跟着顶栏走，否则深色顶栏上会压一条白状态栏
        applySystemBars()

        // 卡片：两套容器 + 分屏右栏（配色在 binder 里跟 night 走，重绑才生效）
        listAdapter?.night = night
        pagerAdapter?.night = night
        listAdapter?.notifyDataSetChanged()
        pagerAdapter?.notifyDataSetChanged()
        splitPane.refresh()
        refreshMorePanelState()
    }

    /** 状态栏 / 导航栏：夜间用深底 + 浅色图标，日间反之（旋转后窗口属性可能被系统重置，也要重下一次） */
    private fun applySystemBars() {
        val barColor = color(R.color.fb_night_bar, R.color.fb_bar_bg)
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).run {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    private fun refreshMorePanelState() {
        val idx = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_FONT_SCALE, 1)
        styleAa(binding.btnFontSmall, idx == 0)
        styleAa(binding.btnFontMedium, idx == 1)
        styleAa(binding.btnFontLarge, idx == 2)
        styleSegment(binding.btnDay, !night)
        styleSegment(binding.btnNight, night)
    }

    /** 字号档位：三个不同字号的「Aa」，选中加深、未选浅灰（与答题页同一套） */
    private fun styleAa(view: TextView, selected: Boolean) {
        view.setTextColor(
            color(
                if (selected && night) R.color.fb_night_text
                else if (selected) R.color.fb_text_title
                else if (night) R.color.fb_night_text_meta
                else R.color.fb_text_meta
            )
        )
        view.setTypeface(
            null,
            if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
    }

    /** 分段胶囊：选中项白底深字，未选中透明底灰字；夜间换成深色胶囊 */
    private fun styleSegment(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            when {
                selected && night -> R.drawable.bg_fb_segment_pill_night
                selected -> R.drawable.bg_fb_segment_pill
                else -> R.drawable.bg_fb_segment_pill_off
            }
        )
        view.setTextColor(
            color(
                if (selected && night) R.color.fb_night_segment_text_sel
                else if (selected) R.color.fb_segment_text_sel
                else if (night) R.color.fb_night_segment_text
                else R.color.fb_segment_text
            )
        )
    }

    private fun color(nightRes: Int, dayRes: Int): Int =
        ContextCompat.getColor(this, if (night) nightRes else dayRes)

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private fun openInPaper(position: Int) {
        val rec = record ?: return
        val qid = rec.results.getOrNull(numbers.getOrNull(position) ?: -1)?.question?.id ?: return
        if (!PaperRef.isValid(rec.paperId)) {
            Toast.makeText(this, R.string.report_paper_missing, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, PaperDetailActivity::class.java).apply {
                putExtra(PaperDetailActivity.EXTRA_PAPER_ID, rec.paperId)
                putExtra(PaperDetailActivity.EXTRA_PAPER_NAME, rec.displayTitle)
                putExtra(PaperDetailActivity.EXTRA_FOCUS_QUESTION_ID, qid)
                putExtra(PaperDetailActivity.EXTRA_RECORD_ID, rec.id)
            }
        )
    }

    // ---------------- 标记 / 收藏（只读页里唯一还能改的两件事） ----------------

    /**
     * 标记只是**临时记号**：点一下给当前这题挂颗细线星，换到别的题就自动消掉。
     *
     * 刻意不落库、也不改记录里的标记——成绩报告格子上的橙点记的是「交卷时标了哪几题」，
     * 看解析顺手标一下不该把历史记录改掉。
     */
    private fun toggleMark(q: Question) {
        // 同时只留一道题：这一题标住它，换到下一题就换人
        val on = q.id !in marked
        marked.clear()
        if (on) marked.add(q.id)
        refreshCard(q)
    }

    /** 收藏跨会话保留（与答题页同一个 `favorites` 表），本页只是把状态同步到卡片上 */
    private fun toggleFavorite(q: Question) {
        val fav = q.id !in favorites
        if (fav) favorites.add(q.id) else favorites.remove(q.id)
        // 单行写直接落库（与答题页同一份写法）：退回报告页读到的就是新状态，不必等写队列
        db.setFavorite(q.id, fav)
        // 结果码当场给：报告页回来要按库重画格子下方的收藏点
        setResult(RESULT_OK)
        refreshCard(q)
    }

    /**
     * 笔记编辑：与答题页长按「笔」同一个弹窗（[FbDialog.input]），保存后
     * 就地更新 [notes] 并重绑那一张卡——笔记卡立即回显，退回报告页再进来也还在。
     */
    private fun showNoteDialog(q: Question) {
        FbDialog.input(
            context = this,
            title = getString(R.string.note_title),
            initial = db.getNote(q.id).orEmpty(),
            hint = getString(R.string.note_hint),
            multiline = true,
            onOk = { text ->
                val note = text.trim()
                db.setNote(q.id, note)
                if (note.isBlank()) notes.remove(q.id) else notes[q.id] = note
                Toast.makeText(this, R.string.note_saved, Toast.LENGTH_SHORT).show()
                refreshCard(q)
            }
        )
    }

    /** 换题时抹掉临时标记：只重绑被标过的那几张卡，不动当前滚动位置 */
    private fun clearTempMark() {
        if (marked.isEmpty()) return
        val qids = marked.mapNotNull { qid -> questions.firstOrNull { it.id == qid } }
        marked.clear()
        if (qids.isEmpty()) return
        // 分屏右栏是另一套卡片容器：直接按题重绑（列表 / 分页器此时都是 GONE，通知它们也没用）
        if (splitPane.isOpen) {
            qids.forEach { splitPane.rebind(it) }
            return
        }
        val indexes = qids.mapNotNull { q ->
            questions.indexOfFirst { it.id == q.id }.takeIf { it >= 0 }
        }
        if (indexes.isEmpty()) return
        // 滚动回调里容器正在滚，放到下一帧再重绑
        val host: View = if (multiBrowse) binding.questionList else binding.pager
        host.post {
            indexes.forEach { idx ->
                if (multiBrowse) {
                    listAdapter?.notifyItemChanged(idx)
                } else {
                    pagerAdapter?.pageOfQuestion(idx)?.let { pagerAdapter?.notifyItemChanged(it) }
                }
            }
        }
    }

    /**
     * 只重绑这一张卡：整表刷新会把滚动位置 / 页码带偏，
     * 可能把「当前题」带跑——与答题页的刷新口径一致。
     */
    private fun refreshCard(q: Question) {
        // 分屏右栏：卡片在控制器的映射里
        if (splitPane.isOpen) {
            splitPane.rebind(q)
            return
        }
        val idx = questions.indexOfFirst { it.id == q.id }
        if (idx < 0) return
        if (multiBrowse) {
            listAdapter?.notifyItemChanged(idx)
        } else {
            pagerAdapter?.pageOfQuestion(idx)?.let { pagerAdapter?.notifyItemChanged(it) }
        }
    }

    // ---------------- 写画（笔） ----------------

    /**
     * 遮盖层接线：与答题页同一套 [InkOverlay] + [InkToolbarController]，
     * 连 `recordId` 都用同一条记录——**答题时画的圈，回来看解析还在**，
     * 看解析时补的圈也写回这条记录（下次从答题页 / 报告页进来都还在）。
     */
    private fun setupInk() {
        inkToolbar = InkToolbarController(
            inkOverlay = binding.inkOverlay,
            // 工具条是共用布局（view_fb_ink_bar.xml），子控件从 include 的绑定里取
            inkBar = binding.inkBar.root,
            btnPen = binding.inkBar.btnInkPen,
            btnEraser = binding.inkBar.btnInkEraser,
            btnUndo = binding.inkBar.btnInkUndo,
            btnRedo = binding.inkBar.btnInkRedo,
            btnShade = binding.inkBar.btnInkShade,
            btnClear = binding.inkBar.btnInkClear,
            btnClose = binding.inkBar.btnInkClose,
            styleInkButton = { }
        )
        inkToolbar.onModeChanged = { on ->
            // 进写画时把笔迹绑到当前题：撤销 / 清空 / 落库都落到它身上
            if (on) syncInkQuestion()
        }

        // 写画时按右边缘的拖拽条滚卷面：多题滚列表，单题滚当前页
        //（材料组页上区是材料、下面是题目，按落点分流；分屏时左栏材料 / 右栏整组题）。
        // 与答题页同一套手感：条顶让过写画工具条，条本身竖屏也画出来
        binding.inkOverlay.scrollTargetResolver = { x, y ->
            if (splitPane.isOpen) splitPane.scrollTargetForInk(x) else inkScrollTarget(y)
        }
        binding.inkOverlay.scrollBarTopInset =
            resources.getDimensionPixelSize(R.dimen.fb_ink_bar_h)
        binding.inkOverlay.scrollBarBottomInset = 0
        binding.inkOverlay.railVisible = true
        binding.inkOverlay.recordId = recordId
        // 笔迹钉在题目卡上：卷面滚到哪，圈画跟到哪
        binding.inkOverlay.anchorSource = { inkAnchors() }
        // 题卡里的滚动（长解析 / 材料内部）不走 RecyclerView 的 onScrolled，统一靠视图树回调重绘
        binding.root.viewTreeObserver.addOnScrollChangedListener { binding.inkOverlay.invalidate() }
        syncInkQuestion()
    }

    /** 写画时拖拽条要滚哪个容器：多题 = 列表；单题 = 当前页（材料组页按落点分上区 / 下区） */
    private fun inkScrollTarget(y: Float): View? {
        if (multiBrowse) return binding.questionList
        val root = currentPageRoot() ?: return binding.questionList
        val materialScroll = root.findViewById<View>(R.id.materialScroll)
        if (materialScroll != null) {
            val base = IntArray(2)
            binding.inkOverlay.getLocationOnScreen(base)
            val loc = IntArray(2)
            materialScroll.getLocationOnScreen(loc)
            val absY = base[1] + y
            if (absY >= loc[1] && absY <= loc[1] + materialScroll.height) return materialScroll
        }
        return root.findViewById<View>(R.id.pageScroll) ?: binding.questionList
    }

    /**
     * 遮盖层的内容锚点：卷面上每张（可见的）卡各给一个题目锚点，卡上有材料就再补一个材料锚点。
     *
     * 题目锚点的原点取题干顶边（带表格时在 `stemRich` 里）、命中区放宽到整张卡——
     * 与答题页同一套算法，同一道题在两处圈画才落在同一个位置。
     */
    private fun inkAnchors(): List<InkOverlay.Anchor> {
        // 分屏：左栏材料一个锚点、右栏每张卡一个（与答题页同一份算法）
        if (splitPane.isOpen) {
            val out = ArrayList<InkOverlay.Anchor>(8)
            val base = IntArray(2)
            binding.inkOverlay.getLocationOnScreen(base)
            splitPane.collectAnchors(out, base)
            return out
        }
        return if (multiBrowse) listAnchors() else pageAnchors()
    }

    /** 多题连续卷面：列表里每张可见卡 = 一道题 */
    private fun listAnchors(): List<InkOverlay.Anchor> {
        val out = ArrayList<InkOverlay.Anchor>(4)
        val rv = binding.questionList
        val base = IntArray(2)
        binding.inkOverlay.getLocationOnScreen(base)

        for (i in 0 until rv.childCount) {
            val card = rv.getChildAt(i)
            val pos = rv.getChildAdapterPosition(card)
            if (pos < 0) continue
            val qid = questions.getOrNull(pos)?.id ?: continue
            addQuestionAnchor(out, card, qid, base)
            addMaterialAnchor(out, card, qid, base)
        }
        return out
    }

    /** 单题一屏一题：一页可能装多道小题（材料组页），材料在上区独立滚动 */
    private fun pageAnchors(): List<InkOverlay.Anchor> {
        val out = ArrayList<InkOverlay.Anchor>(4)
        val root = currentPageRoot() ?: return out
        val box = root.findViewById<ViewGroup>(R.id.cardContainer) ?: return out
        val base = IntArray(2)
        binding.inkOverlay.getLocationOnScreen(base)

        val materialScroll = root.findViewById<View>(R.id.materialScroll)
        if (materialScroll != null) {
            // 组页：材料在上区单独一块，锚点按组首题收（与答题页对得上）
            val content = root.findViewById<View>(R.id.materialContent)
            val firstIdx = (box.getChildAt(0)?.tag as? QuestionPagerAdapter.CardHolder)?.index ?: -1
            val qid = questions.getOrNull(firstIdx)?.id ?: 0L
            if (content != null && qid != 0L && content.width > 0 && content.height > 0) {
                val loc = IntArray(2)
                content.getLocationOnScreen(loc)
                val left = (loc[0] - base[0]).toFloat()
                val top = (loc[1] - base[1]).toFloat()
                val w = content.width.toFloat()
                val h = content.height.toFloat()
                out.add(
                    InkOverlay.Anchor(
                        InkOverlay.TargetKind.MATERIAL,
                        materialFirstId[qid] ?: qid,
                        left, top, w, h
                    ).hitRegion(left, top, left + w, top + h)
                )
            }
        }

        for (i in 0 until box.childCount) {
            val card = box.getChildAt(i)
            val idx = (card.tag as? QuestionPagerAdapter.CardHolder)?.index ?: continue
            val qid = questions.getOrNull(idx)?.id ?: continue
            addQuestionAnchor(out, card, qid, base)
            // 组页的材料已在上区，卡片里的材料块是隐藏的，不重复给锚点
            if (materialScroll == null) addMaterialAnchor(out, card, qid, base)
        }
        return out
    }

    /** 题目锚点：原点取题干顶边，命中区放宽到整张卡 */
    private fun addQuestionAnchor(
        out: MutableList<InkOverlay.Anchor>,
        card: View,
        qid: Long,
        base: IntArray
    ) {
        if (qid == 0L || card.width <= 0 || card.height <= 0) return
        val cardLoc = IntArray(2)
        card.getLocationOnScreen(cardLoc)
        // 带表格的题干渲染在 stemRich 里（tvStem 此时不可见），锚点取可见的那个的顶边
        val stem = card.findViewById<View>(R.id.stemRich)?.takeIf { it.height > 0 }
            ?: card.findViewById<View>(R.id.tvStem)
        val stemY = if (stem != null && stem.height > 0) {
            val loc = IntArray(2)
            stem.getLocationOnScreen(loc)
            loc[1]
        } else {
            cardLoc[1]
        }
        val left = (cardLoc[0] - base[0]).toFloat()
        val top = (cardLoc[1] - base[1]).toFloat()
        out.add(
            InkOverlay.Anchor(
                qid,
                left,
                (stemY - base[1]).toFloat(),
                card.width.toFloat(),
                (cardLoc[1] + card.height - stemY).toFloat().coerceAtLeast(0f)
            ).hitRegion(left, top, left + card.width, top + card.height)
        )
    }

    /** 材料锚点：只在卡上真的渲染了材料时给（一材料多题时按组首题 id 收） */
    private fun addMaterialAnchor(
        out: MutableList<InkOverlay.Anchor>,
        card: View,
        qid: Long,
        base: IntArray
    ) {
        val box = card.findViewById<View>(R.id.materialBox) ?: return
        if (box.visibility != View.VISIBLE) return
        val content = card.findViewById<View>(R.id.materialContent) ?: return
        if (content.width <= 0 || content.height <= 0) return
        val loc = IntArray(2)
        content.getLocationOnScreen(loc)
        val left = (loc[0] - base[0]).toFloat()
        val top = (loc[1] - base[1]).toFloat()
        val w = content.width.toFloat()
        val h = content.height.toFloat()
        out.add(
            InkOverlay.Anchor(
                InkOverlay.TargetKind.MATERIAL,
                materialFirstId[qid] ?: qid,
                left, top, w, h
            ).hitRegion(left, top, left + w, top + h)
        )
    }

    /** 单题浏览时当前页的根视图（ViewPager2 里真正承载那一页的 View） */
    private fun currentPageRoot(): View? {
        val rv = binding.pager.getChildAt(0) as? RecyclerView ?: return null
        for (i in 0 until rv.childCount) {
            val page = rv.getChildAt(i) ?: continue
            if (rv.getChildAdapterPosition(page) == binding.pager.currentItem) return page
        }
        return null
    }

    /**
     * 把遮盖层绑到「当前题」：撤销 / 清空 / 落库都落到这道题上，
     * 不绑就会顺着上一题走。换题、切浏览方式、进写画、刚装配完都调一次。
     */
    private fun syncInkQuestion() {
        if (!::inkToolbar.isInitialized) return
        // 单题浏览停在大类说明页时这一页没有题目——不绑任何笔迹
        val onModuleIntro = !multiBrowse &&
            pagerAdapter?.isModuleIntroPage(binding.pager.currentItem) == true
        val qid = if (onModuleIntro) 0L else questions.getOrNull(currentPos)?.id ?: 0L
        binding.inkOverlay.bindQuestion(qid)
    }

    /**
     * 点题目卡上的「笔」直接进写画。
     *
     * 口径与答题页完全一致（见 [ExamActivity.startInkFor]）：
     * - **多题通铺不把这题顶到内容区最上面**：手指点的是眼前这一题，一进写画整卷往上跳一屏，
     *   题目前后的上下文全丢了，想补一笔还得先回想「刚才在看哪段」。卡片顶部本来就预留了
     *   一条工具条的高度（[QuestionCardBinder.bindInkBarReserve]），工具条盖的是那条空白；
     *   只有卡片顶边已经被工具条压住几十 dp 时补一点（[nudgeCardOutOfInkBar]），不做整题落位。
     *   以前这里走 `scrollToPositionWithOffset(idx, 0)`，点笔就等于跳题，题卡被顶到最上面、
     *   大半压在写画工具条底下。
     * - **单题仍要落位**：那边只是把这一页切出来，不是把卷面挪走。
     *
     * 材料组页一页多题，绑笔按**点的这一题**来（不跟着页首题走）。
     */
    private fun startInkFor(q: Question) {
        val idx = questions.indexOfFirst { it.id == q.id }
        if (idx >= 0) {
            currentPos = idx
            if (splitPane.isOpen) {
                // 分屏右栏是一条卷面：滚到这道题，别的容器都是隐藏的
                splitPane.showQuestion(idx)
            } else if (!multiBrowse) {
                val page = pagerAdapter?.pageOfQuestion(idx) ?: 0
                binding.pager.setCurrentItem(page, false)
                // 组页一页多题：落点就是点的这一题，别被翻页回调改成组首
                if (pagerAdapter?.questionsOfPage(page)?.contains(idx) == true) currentPos = idx
            }
            syncChrome()
        }
        // 落位要等下一帧：那时卡片已经排好版，遮罩才是对着这张卡铺的
        binding.inkOverlay.post {
            if (idx >= 0) {
                currentPos = idx
                when {
                    splitPane.isOpen -> splitPane.scrollTo(idx)
                    // 通铺：不整题落位，只把被工具条压住的那几十 dp 补回来
                    multiBrowse -> nudgeCardOutOfInkBar(idx)
                }
            }
            binding.inkOverlay.bindQuestion(q.id)
            inkToolbar.setInkMode(true)
            if (!inkHintShown) {
                inkHintShown = true
                Toast.makeText(this, R.string.ink_enter_hint, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 通铺里的「最小让位」：这道题的卡片顶边已经滑到工具条底下一点点时，把那一点补回来。
     *
     * 卡片顶边到第一行字之间正好留了一条工具条的高度（见 [QuestionCardBinder.bindInkBarReserve]），
     * 所以「题号不被压住」等价于「卡片顶边 ≥ 内容区顶边」。滑过头（卡片顶边早就在上面很远）就
     * 一点不动——那说明用户正在看题目中间，为了露出题号把内容往下拉一屏反而更烦。
     *
     * 补的距离最多一条工具条（32dp / 平板 36dp），而且是直接跳、不做动画：马上要落笔，
     * 让内容在手指底下滑半秒，笔迹会跟着卡片一起飘。与答题页同一份算法。
     */
    private fun nudgeCardOutOfInkBar(idx: Int) {
        val rv = binding.questionList
        val barH = resources.getDimensionPixelSize(R.dimen.fb_ink_bar_h)
        val card = (rv.layoutManager as? LinearLayoutManager)
            ?.findViewByPosition(idx) ?: return
        val top = card.top
        if (top < 0 && top >= -barH) rv.scrollBy(0, top)   // top < 0 → dy 取负，内容整块往下回来
    }

    /** 与答题页共用同一份字号偏好：复习时字号跟着用户在答题页调过的档位 */
    private fun fontScale(): Float {
        val idx = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_FONT_SCALE, 1)
        return FONT_SCALES.getOrElse(idx) { 1f }
    }

    private val isTablet: Boolean
        get() = ScreenAdapt.isTablet(resources.configuration)
}
