package com.lytree.qbank.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
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
 * 资料分析分屏页（「更多 → 开启左右分屏」进来的独立页）。
 *
 * 对应参考应用的「资料分析左右分屏」：左侧是材料（表格渲染成真表格），右侧是**这一组材料
 * 对应的整组小题，铺成一条连续卷面**；左右滑动整组翻页，左栏材料跟着换。
 *
 * 与平板答题页的区别（那边是上下栏、一页一题）：
 * - 左栏材料永远在屏，一组题摊成一条卷面，改答案 / 对照上下两题不用来回翻页；
 * - 换组 = 换一整套（材料 + 整组题），不是一题一翻；
 * - 进写画（「手写标注」）时题目自动回到顶部：笔迹按卡片坐标钉在题目上，从题首开始画最稳。
 *
 * 进入方式：答题页「更多 → 开启左右分屏」，或资料分析题组的入口。
 */
class SplitExamActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAPER_ID = "paper_id"
        const val EXTRA_PAPER_NAME = "paper_name"
        /** 进来时停在哪一组（按材料文本定位）；为空则停在第一组 */
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
    /** 本次范围的题目（整卷 or 一个模块）：每一题都会出现在右栏某一组里 */
    private var questions: List<Question> = emptyList()
    /** 范围按卷面顺序切成的组：一页 = 一组（材料 + 它的全部小题） */
    private var groups: List<PaperStructure.Group> = emptyList()
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
    /** 当前看的是范围里的第几题（0 起）：页内滚到哪就跟着走，计时 / 笔迹 / 答题卡都用它 */
    private var currentIndex = 0
    private lateinit var inkToolbar: InkToolbarController
    private lateinit var tvIndex: TextView
    /** 判题规则：进页面时一次读回，解析区渲染与交卷判分复用（不再每题渲染查一次库） */
    private var judgePolicy: Policy = Policy.DEFAULT
    /** 卷名：启动批量读里一起取回，交卷记录用（不再交卷时再查一次库） */
    private var paperTitle = ""

    /** 右栏的分页器：一页 = 一组题（整组铺成一条连续卷面） */
    private var pager: ViewPager2? = null
    private var sheetAdapter: SheetAdapter? = null
    /**
     * 答题卡跳题时的落点题号（-1 = 没有）。
     *
     * 换页回调默认把当前题拨到组首、并把卷面滚到顶；从答题卡跳到组内第 3 题时这两件事都不该做，
     * 所以先把落点记下来，回调里只切材料，滚动交给 [scrollToQuestion]。
     */
    private var pendingJump = -1
    /**
     * 每题的选项行刷新器：作答 / 打叉后就地刷新那几行，不重建整页。
     * 重建会把页内滚动位置顶回顶部——一条卷面几十行，答一题跳回组首根本没法用。
     */
    private val optionRefreshers = HashMap<Long, MutableList<() -> Unit>>()

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

            // 按组切：直接用试卷结构里的组——同一材料跨大类会断成两组，
            // 按文本 filter 会把它们并成一组、题号也会错，这里取的正是这题所属的那一组
            structure = PaperStructure(data.questions)
            questions = data.questions
            groups = structure.groups
            val groupKey = intent.getStringExtra(EXTRA_GROUP_KEY)?.trim().orEmpty()
            val startPage = if (groupKey.isEmpty()) {
                0
            } else {
                groups.indexOfFirst {
                    it.isMaterialGroup && it.material.material.trim() == groupKey
                }.takeIf { it >= 0 } ?: 0
            }
            currentIndex = groups.getOrNull(
                startPage.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
            )?.startIndex ?: 0

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
                val material = currentMaterial()
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
                // 工具条是共用布局（view_fb_ink_bar.xml），子控件从 include 的绑定里取
                inkBar = binding.inkBar.root,
                btnPen = binding.inkBar.btnInkPen,
                btnEraser = binding.inkBar.btnInkEraser,
                btnUndo = binding.inkBar.btnInkUndo,
                btnRedo = binding.inkBar.btnInkRedo,
                btnShade = binding.inkBar.btnInkShade,
                btnClear = binding.inkBar.btnInkClear,
                btnClose = binding.inkBar.btnInkClose,
                styleInkButton = { on ->
                    binding.btnInk.setBackgroundResource(
                        if (on) R.drawable.bg_fb_mark_on else R.drawable.bg_fb_action
                    )
                }
            )
            inkToolbar.onModeChanged = { on ->
                inkMode = on
                if (on) {
                    // 进写画先把这一组题回到顶部：笔迹钉在卡片上，从题首开始画才对得上
                    scrollSheetToTop()
                    binding.inkOverlay.bindQuestion(questions.getOrNull(currentIndex)?.id ?: 0L)
                }
            }

            // 写画模式的滚动通道：手势落在左半屏滚材料、右半屏滚题目（分屏的意义就是两边对着看）
            binding.inkOverlay.scrollTargetResolver = { x, _ ->
                if (x <= binding.leftPane.right) binding.materialScroll else rightScroll()
            }
            // 左边缘也放一条手柄，写画时材料还能翻
            binding.inkOverlay.leftRailEnabled = true
            // 工具条在顶上，右侧轨道可以用到底
            binding.inkOverlay.scrollBarBottomInset = 0

            // 笔迹钉在题目卡上（右栏一组题一条卷面），滚材料 / 滚题目都不再和圈画错位
            binding.inkOverlay.anchorSource = { inkAnchors() }
            binding.root.viewTreeObserver.addOnScrollChangedListener {
                binding.inkOverlay.invalidate()
            }

            setupSheetPager(startPage)
            renderMaterial()
            updateHeader()
        }
    }

    // ---------------- 右栏：一组题一页 ----------------

    /**
     * 右栏分页器：**一组题一页**、左右滑动整组翻页。
     *
     * 与平板答题页的「一页一题」相反——左右分栏的场景是「对着这则材料连做几道小题」，
     * 整组铺成一条卷面才不用为了对数据反复翻页；换组时左栏材料跟着整块换掉。
     */
    private fun setupSheetPager(startPage: Int) {
        val viewPager = ViewPager2(this).apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            // 左右各留一页：滑到下一组时卷面已经绑好，不会白一下
            offscreenPageLimit = 1
        }
        val adapter = SheetAdapter()
        viewPager.adapter = adapter
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val group = groups.getOrNull(position) ?: return
                // 左栏材料整块换成这一组的
                renderMaterial()
                if (pendingJump >= 0) {
                    // 答题卡跳题：当前题与页内落位由 onActivityResult 负责，这里不滚回组首
                    currentIndex = pendingJump
                } else {
                    currentIndex = group.startIndex
                    // 换组：题目回到这一组的第一题
                    scrollSheetToTop()
                }
                updateHeader()
                binding.inkOverlay.invalidate()
            }
        })

        binding.rightPane.removeAllViews()
        binding.rightPane.addView(
            viewPager,
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        pager = viewPager
        sheetAdapter = adapter
        // 初次定位不带动画，也不触发翻页回调：材料 / 顶部状态手动刷一次
        viewPager.setCurrentItem(startPage.coerceIn(0, (groups.size - 1).coerceAtLeast(0)), false)
    }

    /** 一组题一页：页内是**连续卷面**（组内小题自上而下依次排开，照常作答） */
    private inner class SheetAdapter : RecyclerView.Adapter<SheetHolder>() {

        override fun getItemCount(): Int = groups.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SheetHolder {
            val hPad = resources.getDimensionPixelSize(R.dimen.fb_content_padding_h)
            val topPad = resources.getDimensionPixelSize(R.dimen.fb_space_8)
            val bottomPad = resources.getDimensionPixelSize(R.dimen.fb_space_24)
            val page = NestedScrollView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                // 页底留白铺在滚动区里，滚到底最后一道题不会贴着底栏
                setPadding(0, 0, 0, bottomPad)
                clipToPadding = false
            }
            val list = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(hPad, topPad, hPad, 0)
            }
            page.addView(list)
            // 页内滚动 → 当前题跟着走（计时 / 笔迹 / 顶栏题号都按它）
            page.setOnScrollChangeListener(
                NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                    onSheetScrolled(list, scrollY)
                }
            )
            return SheetHolder(page, list)
        }

        override fun onBindViewHolder(holder: SheetHolder, position: Int) {
            val group = groups.getOrNull(position) ?: return
            val list = holder.list
            // 组结构没变就复用卡片：作答重绑 / 翻回来都不重建，页内滚动位置保住
            if (holder.startIndex != group.startIndex || list.childCount != group.count) {
                list.removeAllViews()
                val inflater = LayoutInflater.from(list.context)
                group.questions.forEachIndexed { k, _ ->
                    val card = ItemSplitQuestionBinding.inflate(inflater, list, false)
                    card.root.tag = SheetCard(group.startIndex + k, card)
                    list.addView(card.root)
                }
                holder.startIndex = group.startIndex
            }
            group.questions.forEachIndexed { k, q ->
                val card = (list.getChildAt(k).tag as SheetCard).binding
                bindCard(card, q, group.startIndex + k, isLast = k == group.count - 1)
            }
        }
    }

    /** 一页的载体：滚动容器 + 页内卡片列 */
    private class SheetHolder(val scroll: NestedScrollView, val list: LinearLayout) :
        RecyclerView.ViewHolder(scroll) {
        /** 这页装的是哪一组（组首题号），用来判断能不能复用页内卡片 */
        var startIndex: Int = -1
    }

    /** 页内一张卡：整卷题号 + 卡片绑定，重绑时按题号判断能否复用 */
    private class SheetCard(val index: Int, val binding: ItemSplitQuestionBinding)

    /**
     * 页内滚动：把「当前题」拨到最上面露出来的那道。
     *
     * 一条卷面装整组小题，只有题号跟着滚才谈得上「每题用时」和「笔迹绑哪一题」；
     * 每帧都算太费，只在下标真的变了才刷新顶栏与计时。
     */
    private fun onSheetScrolled(list: LinearLayout, scrollY: Int) {
        val line = scrollY + (resources.displayMetrics.density * 24).toInt()
        var index = -1
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child.top <= line) index = (child.tag as? SheetCard)?.index ?: index
        }
        if (index < 0 || index == currentIndex) return
        currentIndex = index
        updateHeader()
    }

    private fun rightScroll(): View? = currentPageScroll() ?: binding.rightPane

    /** 当前页（正在看的这一组）的滚动容器 */
    private fun currentPageScroll(): NestedScrollView? = currentPageRoot() as? NestedScrollView

    /** 当前页的根视图（ViewPager2 里真正承载这一页的 View） */
    private fun currentPageRoot(): View? {
        val p = pager ?: return null
        val rv = p.getChildAt(0) as? RecyclerView ?: return null
        for (i in 0 until rv.childCount) {
            val page = rv.getChildAt(i) ?: continue
            if (rv.getChildAdapterPosition(page) == p.currentItem) return page
        }
        return null
    }

    private fun currentPageList(): LinearLayout? =
        currentPageScroll()?.getChildAt(0) as? LinearLayout

    /** 题目回到这一组的第一题 */
    private fun scrollSheetToTop() = currentPageScroll()?.scrollTo(0, 0)

    /** 把页内某道题顶到可视区顶部（答题卡跳题用） */
    private fun scrollToQuestion(questionIndex: Int, smooth: Boolean = false) {
        val list = currentPageList() ?: return
        val scroll = currentPageScroll() ?: return
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val card = child.tag as? SheetCard ?: continue
            if (card.index != questionIndex) continue
            if (smooth) scroll.smoothScrollTo(0, child.top) else scroll.scrollTo(0, child.top)
            return
        }
    }

    /** 题号 → 它所在的组（页）下标；找不到退回 0 */
    private fun pageOfQuestion(questionIndex: Int): Int =
        groups.indexOfFirst { questionIndex in it.startIndex until it.endIndex }.takeIf { it >= 0 } ?: 0

    // ---------------- 笔迹锚点 ----------------

    /**
     * 遮盖层的内容锚点：右栏当前页每张题目卡 + 左栏材料，各自独立。
     *
     * 题目锚点取「卡片左边 + 题干顶边」，与答题页同一口径，两种页面之间来回看不会整体错位；
     * 材料锚点取材料正文（`materialContent`）的位置，target 用材料组首题 id，
     * 与题目分开存——在材料上画的圈不会因为题干换行而偏移。
     */
    private fun inkAnchors(): List<InkOverlay.Anchor> {
        val out = ArrayList<InkOverlay.Anchor>(groups.size + 1)
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
        val group = groups.getOrNull(pager?.currentItem ?: 0)
        val materialQ = group?.takeIf { it.isMaterialGroup }?.material
        if (materialQ != null) {
            val content = rectOf(binding.materialContent)
            if (content != null) {
                val anchor = InkOverlay.Anchor(
                    InkOverlay.TargetKind.MATERIAL, materialQ.id,
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

        // 右栏：只算当前这一页的卡（别页的卡在屏幕外，锚点算出来是错的）
        val list = currentPageList() ?: return out
        for (i in 0 until list.childCount) {
            val card = list.getChildAt(i)
            val index = (card.tag as? SheetCard)?.index ?: continue
            val q = questions.getOrNull(index) ?: continue
            if (card.width <= 0 || card.height <= 0) continue
            val cardLoc = IntArray(2)
            card.getLocationOnScreen(cardLoc)
            // 带表格的题干渲染在 stemRich 里（tvStem 此时不可见），锚点取可见的那个的顶边
            val stem = card.findViewById<View>(R.id.stemRich)?.takeIf { it.height > 0 }
                ?: card.findViewById<View>(R.id.tvStem)
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
        val material = currentMaterial()
        binding.materialContent.removeAllViews()
        if (material.isBlank()) {
            val tip = TextView(this)
            tip.text = "本组题目无材料"
            tip.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.fb_text_body_sm)
            )
            tip.setTextColor(ContextCompat.getColor(this, R.color.fb_text_meta))
            binding.materialContent.addView(tip)
            binding.materialScroll.scrollTo(0, 0)
            return
        }
        MaterialRenderer.render(this, binding.materialContent, material)
        binding.materialScroll.scrollTo(0, 0)
    }

    /** 当前这一组共用的那份材料（一组 = 一则材料 + 它带的全部小题） */
    private fun currentMaterial(): String =
        groups.getOrNull(pager?.currentItem ?: 0)
            ?.takeIf { it.isMaterialGroup }?.material?.material.orEmpty()

    // ---------------- 右栏：题目卡 ----------------

    /**
     * 一页里的一道题：题号 + 题干 + 选项 + （已交卷时）解析。
     *
     * 题干里夹的图片摘到图区渲染，文字仍留在 TextView（与主卷面同一套口径）；
     * 带表格的题干整块走材料渲染器，`|` 才会变成真表格。
     */
    private fun bindCard(
        card: ItemSplitQuestionBinding,
        q: Question,
        index: Int,
        isLast: Boolean
    ) {
        card.tvNum.text = "${index + 1}."
        card.tvType.text = q.type.label
        if (MaterialRenderer.stemHasTable(q.stem)) {
            card.tvStem.visibility = View.GONE
            card.tvStem.text = ""
            card.stemMedia.visibility = View.GONE
            card.stemMedia.removeAllViews()
            card.stemRich.visibility = View.VISIBLE
            MaterialRenderer.renderStemRich(
                this, card.stemRich, q.stem, night = false, maxImageHeightDp = 360f
            )
        } else {
            card.stemRich.visibility = View.GONE
            card.stemRich.removeAllViews()
            val stem = MaterialRenderer.splitInline(q.stem)
            card.tvStem.text = stem.text
            card.tvStem.visibility = if (stem.text.isBlank()) View.GONE else View.VISIBLE
            if (stem.hasImages) {
                card.stemMedia.visibility = View.VISIBLE
                MaterialRenderer.renderOptionImages(
                    this, card.stemMedia, stem.images, night = false, maxHeightDp = 360f
                )
            } else {
                card.stemMedia.visibility = View.GONE
                card.stemMedia.removeAllViews()
            }
        }

        val multi = q.type == com.lytree.qbank.data.model.QuestionType.MULTI ||
            q.type == com.lytree.qbank.data.model.QuestionType.UNCERTAIN
        val selected = (answers[q.id] ?: "").split(',', '，', ' ')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toMutableSet()

        card.optionsBox.removeAllViews()
        // 这一题的选项行刷新器：作答 / 打叉后就地重刷这几行，不整页重建
        val refreshers = ArrayList<() -> Unit>(q.options.size)
        optionRefreshers[q.id] = refreshers

        // 选中某项 → 记答案、刷新这一题的选项行（页内滚动位置不动）
        val pick: (String) -> Unit = { key ->
            if (multi) {
                if (selected.contains(key)) selected.remove(key) else selected.add(key)
            } else {
                selected.clear()
                selected.add(key)
            }
            answers[q.id] = selected.sorted().joinToString(",")
            currentIndex = index
            updateHeader()
            refreshers.forEach { it() }
            binding.inkOverlay.bindQuestion(q.id)
        }

        // 打叉排除一个已选项：排除与「已选」互斥，答案跟着撤；重画由选项行自己负责，
        // 这里不整页重绘——长按调用的还是被按住的这一行，重建会把手指下的 View 拆掉
        val deselectCrossed: (String, Boolean) -> Unit = { key, crossed ->
            if (crossed && selected.remove(key)) {
                answers[q.id] = selected.sorted().joinToString(",")
                currentIndex = index
                updateHeader()
            }
        }

        // 图形推理「四个选项就是四张图」：和主答题页同口径，横排一行、按格宽缩图
        if (QuestionCardBinder.isImageOnlyOptions(q)) {
            // 图片行不会自己重画选中态（以前靠整页重建兜住），选完就地刷一遍
            var imageRow: View? = null
            val row = QuestionCardBinder.buildImageOptionsRow(
                card.optionsBox, q, { selected }, night = false,
                applyRowPadding = false,
                onPick = { key ->
                    pick(key)
                    imageRow?.let { refreshImageOptionRow(it, q) }
                },
                onCrossChanged = deselectCrossed
            )
            imageRow = row
            card.optionsBox.addView(row)
        } else {
            val inflater = LayoutInflater.from(this)
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
                refreshers.add(refresh)
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

        // 题间细线：组内最后一题下面没有下一题，不画
        val divider = (card.root as? ViewGroup)?.getChildAt(card.root.childCount - 1)
        divider?.visibility = if (isLast) View.GONE else View.VISIBLE
    }

    /**
     * 图形选项行（四个选项都是图）重画选中 / 打叉态。
     *
     * 行内的刷新闭包不外露，这里按答案重新算一遍：不重建行本身，
     * 图片不用重新解码，点一下也就不会闪。
     */
    private fun refreshImageOptionRow(row: View, q: Question) {
        val selected = (answers[q.id] ?: "").split(',', '，', ' ')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val box = row as? ViewGroup ?: return
        for (i in 0 until box.childCount) {
            val item = box.getChildAt(i).tag as? ItemOptionBinding ?: continue
            val key = item.tvKey.text?.toString()?.uppercase().orEmpty()
            QuestionCardBinder.applyOptionState(
                item, selected.contains(key), OptionCross.isCrossed(q.id, key)
            )
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

    /** 遮盖层跟着「当前题」走：滚到哪一题就绑哪一题 */
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
                // 跳到别组：整页换过去，再把这道题顶到页顶
                val page = pageOfQuestion(jump)
                if (page != pager?.currentItem) {
                    // 先记落点：换页回调别把题号和滚动重置到组首
                    pendingJump = jump
                    pager?.setCurrentItem(page, false)
                }
                updateHeader()
                binding.inkOverlay.bindQuestion(questions[jump].id)
                binding.rightPane.post {
                    scrollToQuestion(jump)
                    pendingJump = -1
                }
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
     * 分屏页显示的是整个范围（可跨组翻页），但进度与答题页是同一份；
     * 用 merge 才不会把答题页刚做的题冲掉。
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
            // 与答题页共用同一份进度：先合并，再按**整个范围**判分
            val elapsed = elapsedForRecord()
            val merged = saveMergedProgress()
            val results = questions.map { Judge.judge(it, merged[it.id] ?: "", policy) }
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
        // 答完立刻把对错铺在卷面上（整组一页，整页重绑一次就全带上了）
        sheetAdapter?.notifyDataSetChanged()
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
        val total = questions.size
        val scope = scopeKey
        DbWrites.submit {
            val merged = db.saveProgress(paperId, answersCopy, markedCopy, elapsed, merge = true, scope = scope)
            db.updateRecordLive(rid, merged.count { it.value.isNotBlank() }, total, elapsed)
        }
        // 页面离开前等队列落完，进程被回收也不会丢这一笔
        DbWrites.flush()
    }
}
