package com.lytree.qbank.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.AnswerResult
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.data.model.PaperRef
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ActivityQuestionReviewBinding
import com.lytree.qbank.databinding.ItemQuestionPageBinding
import com.lytree.qbank.databinding.ItemQuestionPagerPageBinding
import com.lytree.qbank.engine.Policy
import com.lytree.qbank.ui.paper.PaperDetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 解析页（成绩报告 → 点题号 / 「错题解析」）。
 *
 * 版式就是答题页本身：同一张题目卡 [QuestionCardBinder]、同一套题号 / 题型标签 / 题干字号 /
 * 选项圆标，连滑动翻题的手势都一样。区别只在**选项**只读——点不动、也不挂打叉；
 * 而解析块（正确答案 / 你的答案 / 结果 / 解析）正是答题页交卷后的那一块。
 *
 * 只读只管作答，右上角三件事照旧：
 * - **笔**：盖一层可写薄纸直接在题上圈画，笔迹按「本条记录 + 题目」存取，
 *   答题时画过的圈回来看解析还在（与答题页同一个 [InkOverlay]，见 [setupInk]）；
 * - **标记**：只是**临时记号**——标的是「正在看的这一题」，翻到别的题就自动消掉，
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
        /** 与答题页同一组字号档位，复习时字号不该和做题时不一样（整体比最初放大一号） */
        private val FONT_SCALES = floatArrayOf(1.0f, 1.15f, 1.3f)
    }

    private lateinit var binding: ActivityQuestionReviewBinding
    private lateinit var db: QBankDb
    private lateinit var inkToolbar: InkToolbarController
    private var recordId = 0L
    private var record: ExamRecord? = null
    private var numbers: List<Int> = emptyList()
    private var adapter: ReviewPagerAdapter? = null

    /**
     * 临时标记：最多一道题，翻页就清。
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

    /** 正在写画：底部翻页按钮先让位（遮盖层没盖到那条栏，误触会把这一笔按到别的题上） */
    private var inkMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手机锁竖屏 / 平板跟随旋转（方向锁是 Activity 级的，答题页锁了这里也要跟着锁）
        ScreenAdapt.applyOrientation(this)
        binding = ActivityQuestionReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)

        recordId = intent.getLongExtra(EXTRA_RECORD_ID, 0L)
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val r = db.getRecord(recordId) ?: return@withContext null
                // 收藏是全局表，这里一次读全再按本卷过滤，免得每题一次查询
                Triple(r, db.getPolicy(), db.listFavoriteIds())
            }
            if (isFinishing || isDestroyed) return@launch
            if (loaded == null) {
                finish(); return@launch
            }
            render(loaded.first, loaded.second, loaded.third)
        }
    }

    /**
     * 退回时把还没落库的笔迹写出去——写画本来就是「抬笔即存」，再刷一次更稳。
     * 收藏的结果码在点星那一刻就给（见 [toggleFavorite]），不等到这里。
     */
    override fun onPause() {
        super.onPause()
        if (::inkToolbar.isInitialized) binding.inkOverlay.flush()
    }

    private fun render(record: ExamRecord, policy: Policy, favoriteIds: Set<Long>) {
        this.record = record
        if (record.results.isEmpty()) {
            finish(); return
        }

        val asked = intent.getIntArrayExtra(EXTRA_NUMBERS)?.toList().orEmpty()
            .filter { it in record.results.indices }
        numbers = asked.ifEmpty { record.results.indices.toList() }
        val items = numbers.map { record.results[it] }

        // 作答按题 id 带进卡片：解析块里的「你的答案 / 结果」直接由 binder 判
        val answers = HashMap<Long, String>()
        record.results.forEach { answers[it.question.id] = it.userAnswer }
        // 一则材料带多道小题时才给「弹出材料」入口（与答题页同一口径）
        val materialCounts = record.results
            .filter { it.question.material.isNotBlank() }
            .groupingBy { it.question.material }
            .eachCount()
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

        val pagerAdapter = ReviewPagerAdapter(
            items = items,
            numbers = numbers,
            answers = answers,
            marked = marked,
            favorites = favorites,
            policy = policy,
            materialCounts = materialCounts,
            fontScale = fontScale(),
            onMaterialExpand = { q -> openMaterial(q) },
            onMarkToggle = { q -> toggleMark(q) },
            onFavoriteToggle = { q -> toggleFavorite(q) },
            onInkClicked = { q -> startInkFor(q) }
        )
        adapter = pagerAdapter
        binding.pager.offscreenPageLimit = 1
        binding.pager.adapter = pagerAdapter

        val start = intent.getIntExtra(EXTRA_START, 0).coerceIn(0, items.lastIndex)
        binding.pager.setCurrentItem(start, false)
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                syncChrome(position)
                // 翻走就把临时标记抹掉：回到原题不会又冒出来（这就是「临时」的意思）
                clearTempMark()
                syncInkQuestion()
            }
        })
        syncChrome(start)

        binding.btnPrev.setOnClickListener { goTo(binding.pager.currentItem - 1) }
        binding.btnNext.setOnClickListener { goTo(binding.pager.currentItem + 1) }
        // 原题里的材料表格 / 配图 / 图片选项，只有试卷详情渲染得全
        binding.titleBar.addTextAction(getString(R.string.report_action_open_paper)) {
            openInPaper(binding.pager.currentItem)
        }
        setupInk()
    }

    /** 顶栏标题与两个翻页按钮跟着当前页走（第一题没有上一题、最后一题没有下一题） */
    private fun syncChrome(position: Int) {
        val total = record?.results?.size ?: numbers.size
        binding.titleBar.setTitle(
            getString(R.string.report_analysis_page_title, numbers[position] + 1, total)
        )
        // 写画时不许翻页：遮盖层只铺在卷面上，底部那条栏还露着
        val hasPrev = !inkMode && position > 0
        binding.btnPrev.isEnabled = hasPrev
        binding.btnPrev.alpha = if (hasPrev) 1f else 0.4f
        val hasNext = !inkMode && position < numbers.lastIndex
        binding.btnNext.isEnabled = hasNext
        binding.btnNext.alpha = if (hasNext) 1f else 0.4f
    }

    private fun goTo(position: Int) {
        if (position in numbers.indices) binding.pager.setCurrentItem(position, true)
    }

    private fun openMaterial(q: Question) {
        if (q.material.isBlank()) return
        startActivity(
            Intent(this, MaterialPopupActivity::class.java).apply {
                putExtra(MaterialPopupActivity.EXTRA_MATERIAL, q.material)
                putExtra(MaterialPopupActivity.EXTRA_FONT_SCALE, fontScale())
            }
        )
    }

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
     * 标记只是**临时记号**：点一下给当前这题挂颗细线星，翻到别的题就自动消掉。
     *
     * 刻意不落库、也不改记录里的标记——成绩报告格子上的橙点记的是「交卷时标了哪几题」，
     * 看解析顺手标一下不该把历史记录改掉。
     */
    private fun toggleMark(q: Question) {
        // 同时只留一道题：这一页标住它，下一页就换人
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

    /** 翻页时抹掉临时标记：只重绑被标过的那一页，不动当前页的滚动位置 */
    private fun clearTempMark() {
        if (marked.isEmpty()) return
        val pages = marked.mapNotNull { qid -> adapter?.indexOf(qid)?.takeIf { it >= 0 } }
        marked.clear()
        if (pages.isEmpty()) return
        // 翻页回调里 RecyclerView 可能正在滚，放到下一帧再重绑
        binding.pager.post { pages.forEach { adapter?.notifyItemChanged(it) } }
    }

    /**
     * 只重绑这一页：ViewPager2 上整表刷新会重新定位页码，
     * 可能把「当前题」带偏——与答题页的刷新口径一致。
     */
    private fun refreshCard(q: Question) {
        val idx = adapter?.indexOf(q.id) ?: return
        if (idx < 0) return
        adapter?.notifyItemChanged(idx)
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
            inkMode = on
            // 写画时把底部两个翻页按钮淡掉（见 syncChrome），退出再按当前页复位
            syncChrome(binding.pager.currentItem)
            if (on) syncInkQuestion()
        }

        // 写画时按右边缘的轨道滚动当前页（一屏一题，只有一页可滚）
        binding.inkOverlay.scrollTargetResolver = { _, _ -> currentPageScroll() }
        binding.inkOverlay.scrollBarBottomInset = 0
        // 与答题页同一套手感：不画手柄、热区收窄到屏幕最外沿 16dp，正文里写字不会被当成滚动
        binding.inkOverlay.railVisible = false
        binding.inkOverlay.barTouchWidth = 16f * resources.displayMetrics.density
        binding.inkOverlay.recordId = recordId
        // 笔迹钉在题目卡上：页内滚到哪，圈画跟到哪
        binding.inkOverlay.anchorSource = { inkAnchors() }
        // 页内纵向滚动（NestedScrollView）不走 RecyclerView 的 onScrolled，靠视图树的滚动回调统一重绘
        binding.root.viewTreeObserver.addOnScrollChangedListener { binding.inkOverlay.invalidate() }
        syncInkQuestion()
    }

    /**
     * 遮盖层的内容锚点：当前页上每张卡各给一个题目锚点，卡上有材料就再补一个材料锚点。
     *
     * 题目锚点的原点取题干顶边（带表格时在 `stemRich` 里）、命中区放宽到整张卡——
     * 与答题页同一套算法，同一道题在两处圈画才落在同一个位置。
     */
    private fun inkAnchors(): List<InkOverlay.Anchor> {
        val out = ArrayList<InkOverlay.Anchor>(2)
        val root = currentPageRoot() ?: return out
        val box = root.findViewById<ViewGroup>(R.id.cardContainer) ?: return out
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

        for (i in 0 until box.childCount) {
            val card = box.getChildAt(i)
            val qid = card.tag as? Long ?: continue
            if (qid == 0L || card.width <= 0 || card.height <= 0) continue

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

            // 材料：只在卡上真的渲染了材料时给（一材料多题时按组首题 id 收，与答题页对得上）
            val materialBox = card.findViewById<View>(R.id.materialBox) ?: continue
            if (materialBox.visibility != View.VISIBLE) continue
            val content = card.findViewById<View>(R.id.materialContent) ?: continue
            val r = rectOf(content) ?: continue
            out.add(
                InkOverlay.Anchor(
                    InkOverlay.TargetKind.MATERIAL,
                    materialFirstId[qid] ?: qid,
                    r[0], r[1], r[2], r[3]
                ).hitRegion(r[0], r[1], r[0] + r[2], r[1] + r[3])
            )
        }
        return out
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

    /** 当前页的滚动容器（一屏一题，就是那层 NestedScrollView） */
    private fun currentPageScroll(): View? = currentPageRoot()?.findViewById(R.id.pageScroll)

    /**
     * 把遮盖层绑到「当前题」：撤销 / 清空 / 落库都落到这道题上，
     * 不绑就会顺着上一页的题走。翻页、进写画、刚装配完都调一次。
     */
    private fun syncInkQuestion() {
        if (!::inkToolbar.isInitialized) return
        val qid = adapter?.questionIdAt(binding.pager.currentItem) ?: 0L
        binding.inkOverlay.bindQuestion(qid)
    }

    /**
     * 点题目卡上的「笔」直接进写画。
     *
     * 卡上的笔属于当前页这道题（一屏一题），所以只要换绑当前题、铺上遮盖纸；
     * 落位交给下一帧——那时卡片已经排好版，遮罩才是对着这张卡铺的。
     */
    private fun startInkFor(q: Question) {
        val idx = adapter?.indexOf(q.id) ?: -1
        if (idx >= 0 && idx != binding.pager.currentItem) binding.pager.setCurrentItem(idx, false)
        binding.inkOverlay.post {
            syncInkQuestion()
            inkToolbar.setInkMode(true)
            if (!inkHintShown) {
                inkHintShown = true
                Toast.makeText(this, R.string.ink_enter_hint, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 与答题页共用同一份字号偏好：复习时字号跟着用户在答题页调过的档位 */
    private fun fontScale(): Float {
        val idx = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_FONT_SCALE, 1)
        return FONT_SCALES.getOrElse(idx) { 1f }
    }
}

/**
 * 解析页的一页 = 答题页同款单题容器（`item_question_pager_page.xml` + `item_question_page.xml`），
 * 绑定同样走 [QuestionCardBinder.bind]，只是「选项」那半是只读（`readOnly`），
 * 而右上角的笔 / 标记 / 收藏照旧可点（`showMarkFavorite`、`showInk`）。
 */
private class ReviewPagerAdapter(
    private val items: List<AnswerResult>,
    private val numbers: List<Int>,
    private val answers: MutableMap<Long, String>,
    private val marked: MutableSet<Long>,
    private val favorites: MutableSet<Long>,
    private val policy: Policy,
    private val materialCounts: Map<String, Int>,
    private val fontScale: Float,
    private val onMaterialExpand: (Question) -> Unit,
    onMarkToggle: (Question) -> Unit,
    onFavoriteToggle: (Question) -> Unit,
    onInkClicked: (Question) -> Unit
) : RecyclerView.Adapter<ReviewPagerAdapter.VH>() {

    class VH(val page: ItemQuestionPagerPageBinding, val card: ItemQuestionPageBinding) :
        RecyclerView.ViewHolder(page.root)

    /** 题 id → 页码：标了 / 收了只重绑这一页 */
    private val pageOfQuestion: Map<Long, Int> = HashMap<Long, Int>().also { map ->
        items.forEachIndexed { index, r -> if (!map.containsKey(r.question.id)) map[r.question.id] = index }
    }

    /** 找不到（正常不会发生）返回 -1，调用方跳过刷新即可 */
    fun indexOf(questionId: Long): Int = pageOfQuestion[questionId] ?: -1

    /** 第 [index] 页是哪道题（写画遮盖层要绑当前题） */
    fun questionIdAt(index: Int): Long = items.getOrNull(index)?.question?.id ?: 0L

    /**
     * 只读页面：作答没有承接方（回调留空、选项也不挂手势）；
     * 笔 / 标记 / 收藏都有承接方，走 Activity 传进来的三个动作。
     */
    private val cb = QuestionCardBinder.Callbacks(
        onMarkToggle = onMarkToggle,
        onFavoriteToggle = onFavoriteToggle,
        onInkClicked = onInkClicked,
        // 文字笔记要在答题页里写，解析页只做展示，长按这支笔不弹输入框
        onNoteClicked = {},
        onAnswerChanged = {},
        onMaterialExpand = { q -> onMaterialExpand(q) }
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val page = ItemQuestionPagerPageBinding.inflate(inflater, parent, false)
        val card = ItemQuestionPageBinding.inflate(inflater, page.cardContainer, false)
        page.cardContainer.addView(card.root)
        return VH(page, card)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        // 卡上挂本题 id：遮盖层按它算锚点（与答题页 CardHolder.index 的作用相当）
        holder.card.root.tag = r.question.id
        QuestionCardBinder.bind(
            b = holder.card,
            // 题号按原卷编号：错题解析只翻错题，但卡上还得写「第 8 题」而不是「第 3 题」
            position = numbers[position],
            q = r.question,
            answers = answers,
            marked = marked,
            favorites = favorites,
            revealAnswers = true,
            policy = policy,
            fontScale = fontScale,
            cb = cb,
            hideMaterial = false,
            materialGroupSize = materialCounts[r.question.material] ?: 1,
            moduleHeader = null,
            showDivider = false,
            readOnly = true,
            // 选项照旧只读，但笔 / 标记 / 收藏这三件事看解析时照样要做
            showMarkFavorite = true,
            showInk = true
        )
        // 复用的页可能停在上一题滚到一半的位置
        holder.page.pageScroll.scrollTo(0, 0)
    }
}
