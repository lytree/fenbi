package com.lytree.qbank.ui

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.lytree.qbank.R
import com.lytree.qbank.data.model.PaperStructure
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ItemQuestionPageBinding

/**
 * 「弹出材料」就地左右分屏：**答题页与解析页共用这一份**。
 *
 * 交互对齐粉笔资料分析：一点就把当前这组题就地切成左右两栏——
 * 左栏材料（可「收起材料」还原、可再弹整页看表格、中缝可拖动改占比），
 * 右栏这一组题铺成一条连续卷面，左右滑动整组翻页；顶栏不换。
 *
 * 两页只差「卡片怎么绑」：答题页要选答案、判分，解析页只读并多出答案 / 解析块。
 * 那部分由调用方在 [bindCard] 里注入，[SplitPaneController] 只管版式、分页、滚动与锚点，
 * 所以同一个手势、同一份中缝比例、同一套写画锚点在两页里不会走出两种手感。
 *
 * 分屏不是独立页面：容器显隐交给调用方（[onVisibilityChanged]），
 * 这样「收起」后回到原来的多题 / 单题版式，停在同一道题上。
 */
class SplitPaneController(
    private val ctx: Context,
    private val views: Views,
    private val questions: () -> List<Question>,
    private val structure: () -> PaperStructure,
    private val inkOverlay: InkOverlay,
    private val isNight: () -> Boolean,
    private val fontScale: () -> Float,
    /** 页面记的「当前题」：分屏里滚到哪道题就回写到这里 */
    private val currentIndex: () -> Int,
    private val setCurrentIndex: (Int) -> Unit,
    /** 分屏内换了一道题（页内滚动 / 翻组）：刷新顶栏、绑笔迹等由页面接 */
    private val onQuestionChanged: (Int) -> Unit,
    /** 绑一张题卡：答题页带作答与判分，解析页只读 + 答案 / 解析块 */
    private val bindCard: (ItemQuestionPageBinding, Question, Int, Boolean) -> Unit,
    /** 左栏「弹出材料」：半栏宽看不清表格 / 图表时弹到整页看 */
    private val onOpenFullMaterial: (String) -> Unit,
    /** 分屏显隐：打开时页面要把列表 / 分页器藏起来，收起时按当前题还原 */
    private val onVisibilityChanged: (Boolean) -> Unit
) {

    /** 分屏用到的视图。两页 id 相同，构造时按各自的 binding 传进来。 */
    class Views(
        val body: ViewGroup,
        val leftPane: ViewGroup,
        val tvTag: TextView,
        val tvProgress: TextView,
        val btnExpand: ViewGroup,
        val btnCollapse: TextView,
        val materialScroll: NestedScrollView,
        val materialContent: LinearLayout,
        val handle: View,
        val handleBar: View,
        val questionPane: FrameLayout,
        /**
         * 材料正文是否自己让开底部导航条。
         *
         * 答题页的分屏挂在根布局上，得自己让；解析页的 `content` 已经整块让过一次，
         * 再让一次就会在材料底部多出一截空白。
         */
        val materialPadNavBar: Boolean = true
    )

    private companion object {
        const val PREFS = "exam_prefs"
        /** 中缝比例（左栏占整宽的比例），与答题页历史键一致 */
        const val KEY_RATIO = "split_material_ratio"
        const val RATIO_MIN = 0.25f
        const val RATIO_MAX = 0.75f
        const val RATIO_DEFAULT = 0.4f
    }

    /** 一组一页：换组时左栏材料整块换掉，右栏换一整组题 */
    private var pages: List<PaperStructure.Group> = emptyList()
    private var group: List<Question> = emptyList()
    /** 这组第一题在整卷里的位置（0 起；右栏题号按整卷序号显示） */
    private var groupStartIndex = 0
    private var pager: ViewPager2? = null
    private var adapter: SheetAdapter? = null

    /** 分屏右栏的卡片索引：标记 / 收藏 / 笔记后只重绑那一张 */
    private val cards = HashMap<Long, ItemQuestionPageBinding>()

    /** 分屏是不是正开着（页面用它决定滚动 / 锚点 / 返回键走哪条路） */
    var isOpen: Boolean = false
        private set

    /** 当前这组有几道小题（卡片绑定时用来兜「一材料一题不弹」的入口判定） */
    val groupSize: Int
        get() = group.size

    /** 这组第一题在整卷里的位置（自动下一题判断「是不是组尾」用） */
    val groupOffset: Int
        get() = groupStartIndex

    init {
        installResize()
        views.btnCollapse.setOnClickListener { close() }
        views.btnExpand.setOnClickListener {
            val material = group.firstOrNull()?.material.orEmpty()
            if (material.isNotBlank()) onOpenFullMaterial(material)
        }
    }

    // ---------------- 开 / 关 ----------------

    /**
     * 打开分屏并定位到 [q]。
     *
     * 只在「一则材料带多道小题」时成立：一材料一题时材料在卡上已完整展开，弹出只是重看一遍。
     * 返回 false 表示没开（调用方可以据此兜底提示）。
     */
    fun open(q: Question): Boolean {
        val target = structure().groupOf(q)?.takeIf { it.isMaterialGroup }?.questions.orEmpty()
        if (target.size < 2) return false
        // 分页表按整卷结构切：一组一页，滑到下一组时左栏材料整块换掉
        pages = structure().groups.filter { it.questions.isNotEmpty() }
        val page = pages.indexOfFirst { g -> g.questions.any { it.id == q.id } }
            .takeIf { it >= 0 } ?: 0
        isOpen = true
        applyGroup(pages.getOrNull(page))
        setCurrentIndex(questions().indexOfFirst { it.id == q.id }.takeIf { it >= 0 } ?: currentIndex())
        refreshProgress()
        // 先把分屏放上屏：材料按真实栏宽排版（表格列宽靠宽度算），隐藏时宽度是 0
        onVisibilityChanged(true)
        renderMaterial()
        setupPager(page)
        // 左栏材料底色 / 标签 / 手柄：打开时按当前日夜刷一次（收起期间改过日夜也跟得上）
        applyColors()
        // 写画（笔记）模式下左右两栏各滚各的：左边缘也放一条拖拽条，左栏材料长了才翻得动
        inkOverlay.leftRailEnabled = true
        onQuestionChanged(currentIndex())
        inkOverlay.invalidate()
        return true
    }

    /** 收起材料：还原成原来的浏览版式，停在分屏里正在看的那道题 */
    fun close() {
        if (!isOpen) return
        isOpen = false
        pager = null
        adapter = null
        cards.clear()
        views.questionPane.removeAllViews()
        // 退出分屏就收掉左边缘那条拖拽条：普通页面左边缘是书写区，只留右边一条
        inkOverlay.leftRailEnabled = false
        onVisibilityChanged(false)
    }

    // ---------------- 装配 ----------------

    private fun applyGroup(g: PaperStructure.Group?) {
        group = g?.questions.orEmpty()
        groupStartIndex = g?.startIndex ?: 0
    }

    private fun setupPager(page: Int) {
        val pager = ViewPager2(ctx).apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            // 左右各留一页：滑到下一组时卷面已经绑好，不会白一下
            offscreenPageLimit = 1
        }
        val adapter = SheetAdapter()
        pager.adapter = adapter
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val g = pages.getOrNull(position) ?: return
                applyGroup(g)
                // 左栏材料整块换成这一组的
                renderMaterial()
                setCurrentIndex(g.startIndex)
                refreshProgress()
                currentScroll()?.scrollTo(0, 0)
                onQuestionChanged(currentIndex())
                inkOverlay.invalidate()
            }
        })

        views.questionPane.removeAllViews()
        views.questionPane.addView(
            pager,
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        this.pager = pager
        this.adapter = adapter
        // 初次定位不带动画，也不触发翻页回调：材料 / 顶部状态手动刷过一次了
        pager.setCurrentItem(page.coerceIn(0, (pages.size - 1).coerceAtLeast(0)), false)
    }

    /** 右栏一页：独立滚动的容器 + 页内连续排开的题目卡 */
    private inner class SheetAdapter : RecyclerView.Adapter<SheetHolder>() {

        override fun getItemCount(): Int = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SheetHolder {
            val hPad = ctx.resources.getDimensionPixelSize(R.dimen.fb_content_padding_h)
            val topPad = ctx.resources.getDimensionPixelSize(R.dimen.fb_space_8)
            val bottomPad = ctx.resources.getDimensionPixelSize(R.dimen.fb_space_24)
            val page = NestedScrollView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                // 页底留白铺在滚动区里，滚到底最后一道题不会贴着屏幕边
                setPadding(0, 0, 0, bottomPad)
                clipToPadding = false
            }
            val list = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(hPad, topPad, hPad, 0)
            }
            page.addView(list)
            // 页内滚动 → 当前题跟着走（笔迹 / 左栏进度都按它）
            page.setOnScrollChangeListener(
                NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                    onSheetScrolled(list, scrollY)
                }
            )
            return SheetHolder(page, list)
        }

        override fun onBindViewHolder(holder: SheetHolder, position: Int) {
            val g = pages.getOrNull(position) ?: return
            val list = holder.list
            // 组结构没变就复用卡片：重绑 / 翻回来都不重建，页内滚动位置保住
            if (holder.startIndex != g.startIndex || list.childCount != g.count) {
                // 只清这一页自己的卡片映射；左右各缓存的那一页留着
                for (i in 0 until list.childCount) {
                    val ref = list.getChildAt(i).tag as? SheetCard ?: continue
                    cards.remove(questions().getOrNull(ref.index)?.id)
                }
                list.removeAllViews()
                val inflater = LayoutInflater.from(ctx)
                g.questions.forEachIndexed { k, _ ->
                    val card = ItemQuestionPageBinding.inflate(inflater, list, false)
                    card.root.tag = SheetCard(g.startIndex + k, card)
                    list.addView(card.root)
                }
                holder.startIndex = g.startIndex
            }
            g.questions.forEachIndexed { k, q ->
                val card = (list.getChildAt(k).tag as SheetCard).binding
                cards[q.id] = card
                bindCard(card, q, g.startIndex + k, k == g.count - 1)
            }
        }
    }

    /** 一页的载体：滚动容器 + 页内卡片列 */
    private class SheetHolder(
        val scroll: NestedScrollView,
        val list: LinearLayout
    ) : RecyclerView.ViewHolder(scroll) {
        /** 这页装的是哪一组（组首题号），用来判断能不能复用页内卡片 */
        var startIndex: Int = -1
    }

    /** 页内一张卡：整卷题号 + 卡片绑定 */
    private class SheetCard(val index: Int, val binding: ItemQuestionPageBinding)

    // ---------------- 定位 / 刷新 ----------------

    /** 只重绑一张卡：作答 / 标记 / 收藏后就地刷新，不动页内滚动位置 */
    fun rebind(q: Question) {
        val card = cards[q.id] ?: return
        val idx = questions().indexOfFirst { it.id == q.id }
        if (idx < 0) return
        val lastId = pages.firstOrNull { it.contains(idx) }?.questions?.lastOrNull()?.id
        bindCard(card, q, idx, lastId == q.id)
    }

    /**
     * 分屏里翻到某一题：同组滚到那张卡，跨组整页换过去（左栏材料跟着换）。
     * 收起后迟到的「自动下一题」之类的调用不会动当前题定位。
     */
    fun showQuestion(index: Int, smooth: Boolean = true) {
        if (!isOpen) return
        val page = pages.indexOfFirst { it.contains(index) }
        if (page < 0) return
        if (page != pager?.currentItem) {
            pager?.setCurrentItem(page, smooth)
            setCurrentIndex(index)
            // 换页后页内排版才落定，滚动放到下一帧
            views.questionPane.post { scrollTo(index, smooth = smooth) }
        } else {
            scrollTo(index, smooth = smooth)
        }
        onQuestionChanged(currentIndex())
    }

    /** 把页内某道题顶到可视区顶部 */
    fun scrollTo(index: Int, smooth: Boolean = false) {
        val list = currentList() ?: return
        val scroll = currentScroll() ?: return
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val ref = child.tag as? SheetCard ?: continue
            if (ref.index != index) continue
            if (smooth) scroll.smoothScrollTo(0, child.top) else scroll.scrollTo(0, child.top)
            return
        }
    }

    /**
     * 字号 / 夜间变了：整组卡片按新档位重绑，左栏材料重排并重新着色，
     * 位置滚回当前题（重排会把页内滚动顶回顶部）。
     */
    fun refresh() {
        if (!isOpen) return
        applyColors()
        adapter?.notifyDataSetChanged()
        renderMaterial()
        views.questionPane.post { scrollTo(currentIndex()) }
    }

    /** 分屏内的写画滚动通道：落点在左栏滚材料，右栏滚当前这组题 */
    fun scrollTargetForInk(x: Float): View? {
        if (!isOpen) return null
        return if (x <= views.leftPane.right) views.materialScroll else currentScroll()
    }

    // ---------------- 写画锚点 ----------------

    /**
     * 分屏的内容锚点：左栏材料一个，右栏每张卡各一个。
     *
     * 算法与卷面同一份（原点取题干顶边、命中区放宽到整张卡；材料命中区裁到左栏滚动容器），
     * 所以同一道题在卷面 / 分屏里圈画落在同一个位置。
     */
    fun collectAnchors(out: MutableList<InkOverlay.Anchor>, base: IntArray) {
        if (!isOpen) return
        group.firstOrNull()?.let {
            addMaterial(views.materialContent, it.id, views.materialScroll, base, out)
        }
        val list = currentList() ?: return
        for (i in 0 until list.childCount) {
            val card = list.getChildAt(i)
            val idx = (card.tag as? SheetCard)?.index ?: continue
            addQuestion(card, questions().getOrNull(idx)?.id ?: 0L, base, out)
        }
    }

    private fun rectOf(view: View, base: IntArray): FloatArray? {
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

    private fun addMaterial(
        view: View,
        materialId: Long,
        clip: View?,
        base: IntArray,
        out: MutableList<InkOverlay.Anchor>
    ) {
        if (materialId == 0L) return
        val r = rectOf(view, base) ?: return
        val anchor = InkOverlay.Anchor(
            InkOverlay.TargetKind.MATERIAL, materialId,
            r[0], r[1], r[2], r[3]
        )
        // 材料在独立滚动容器里：内容可能比可视区高，命中区裁到容器，别把下方题目算成材料
        if (clip != null) {
            val c = rectOf(clip, base)
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

    private fun addQuestion(
        card: View,
        id: Long,
        base: IntArray,
        out: MutableList<InkOverlay.Anchor>
    ) {
        if (id == 0L || card.width <= 0 || card.height <= 0) return
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
            id,
            cardLeft,
            (stemY - base[1]).toFloat(),
            card.width.toFloat(),
            (cardLoc[1] + card.height - stemY).toFloat().coerceAtLeast(0f)
        )
        anchor.hitRegion(cardLeft, cardTop, cardLeft + card.width, cardTop + card.height)
        out.add(anchor)
    }

    // ---------------- 页内滚动 / 进度 ----------------

    private fun currentScroll(): NestedScrollView? {
        val pager = pager ?: return null
        val rv = pager.getChildAt(0) as? RecyclerView ?: return null
        for (i in 0 until rv.childCount) {
            val page = rv.getChildAt(i)
            if (rv.getChildAdapterPosition(page) == pager.currentItem) {
                return page as? NestedScrollView
            }
        }
        return null
    }

    private fun currentList(): LinearLayout? =
        currentScroll()?.getChildAt(0) as? LinearLayout

    /**
     * 页内滚动：把「当前题」拨到最上面露出来的那道。
     *
     * 一条卷面装整组小题，只有题号跟着滚才谈得上「笔迹绑哪一题」；
     * 每帧都算太费，只在下标真的变了才刷新。
     */
    private fun onSheetScrolled(list: LinearLayout, scrollY: Int) {
        val line = scrollY + (ctx.resources.displayMetrics.density * 24).toInt()
        var index = -1
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child.top <= line) index = (child.tag as? SheetCard)?.index ?: index
        }
        if (index < 0 || index == currentIndex()) return
        setCurrentIndex(index)
        refreshProgress()
        onQuestionChanged(currentIndex())
        inkOverlay.invalidate()
    }

    /** 左栏头部的小进度：组内第几题 / 共几题 */
    private fun refreshProgress() {
        val inGroup = (currentIndex() - groupStartIndex)
            .coerceIn(0, (group.size - 1).coerceAtLeast(0))
        views.tvProgress.text = ctx.getString(R.string.question_index, inGroup + 1, group.size)
    }

    // ---------------- 材料 / 中缝 ----------------

    private fun renderMaterial() {
        MaterialRenderer.render(
            ctx,
            views.materialContent,
            group.firstOrNull()?.material.orEmpty(),
            isNight(),
            fontScale()
        )
        if (views.materialPadNavBar) views.materialContent.applyNavBarBottomPadding()
        views.materialScroll.scrollTo(0, 0)
    }

    /**
     * 中缝手柄：左右拖动改「材料 / 题目」两栏占比（25%～75%，默认材料 40%，对齐粉笔）。
     *
     * 材料常常比题目长得多：固定五五分要么材料翻半天、要么题目只剩几个字；
     * 看表格的时候把材料拉宽，读题的时候再收回去。比例存偏好，重进沿用。
     */
    private fun installResize() {
        val prefs = ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
        applyRatio(prefs.getFloat(KEY_RATIO, RATIO_DEFAULT))

        var startX = 0f
        var startPct = RATIO_DEFAULT
        views.handle.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startPct = prefs.getFloat(KEY_RATIO, RATIO_DEFAULT).coerceIn(RATIO_MIN, RATIO_MAX)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val w = views.body.width.coerceAtLeast(1)
                    applyRatio(startPct + (ev.rawX - startX) / w)
                    inkOverlay.invalidate()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val left = views.leftPane.layoutParams as LinearLayout.LayoutParams
                    prefs.edit().putFloat(KEY_RATIO, left.weight / 1000f).apply()
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun applyRatio(pct: Float) {
        val r = pct.coerceIn(RATIO_MIN, RATIO_MAX)
        val left = views.leftPane.layoutParams as? LinearLayout.LayoutParams ?: return
        val right = views.questionPane.layoutParams as? LinearLayout.LayoutParams ?: return
        // 权重按 0~1000 分（LinearLayout 只认整数权重，用浮点会四舍五入到 1:1）
        left.weight = r * 1000f
        right.weight = 1000f - left.weight
        views.leftPane.layoutParams = left
        views.questionPane.layoutParams = right
    }

    /**
     * 左栏配色（夜间跟换；版式不动）。
     *
     * 材料底 / 「材料」标签 / 进度 / 收起按钮 / 中缝 grip / 整页查看入口的图标与文字一起换，
     * 与答题页顶栏那份夜间色板同源（`fb_night_*`）。
     */
    fun applyColors() {
        val night = isNight()
        views.leftPane.setBackgroundColor(
            ContextCompat.getColor(ctx, if (night) R.color.fb_night_material_bg else R.color.fb_bar_bg)
        )
        views.tvTag.setBackgroundResource(
            if (night) R.drawable.bg_fb_tag_night else R.drawable.bg_fb_material_tag
        )
        views.tvTag.setTextColor(
            ContextCompat.getColor(ctx, if (night) R.color.fb_night_tag_text else R.color.fb_blue_ink)
        )
        val meta = ContextCompat.getColor(
            ctx, if (night) R.color.fb_night_text_meta else R.color.fb_text_meta
        )
        views.tvProgress.setTextColor(meta)
        views.btnCollapse.setTextColor(meta)
        (views.btnExpand.getChildAt(0) as? android.widget.ImageView)?.setColorFilter(meta)
        (views.btnExpand.getChildAt(1) as? TextView)?.setTextColor(meta)
        views.handleBar.setBackgroundResource(
            if (night) R.drawable.bg_fb_drag_bar_night else R.drawable.bg_fb_drag_bar
        )
    }
}
