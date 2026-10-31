package com.lytree.qbank.ui

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.model.PaperStructure
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ItemGroupPagerPageBinding
import com.lytree.qbank.databinding.ItemModulePageBinding
import com.lytree.qbank.databinding.ItemQuestionPageBinding
import com.lytree.qbank.databinding.ItemQuestionPagerPageBinding
import com.lytree.qbank.engine.Policy

/**
 * 单题浏览适配器。配合 [androidx.viewpager2.widget.ViewPager2] 实现「左右滑动切题」。
 *
 * 与 [QuestionListAdapter] 共用同一套卡片版式（`item_question_page.xml`）与同一份渲染逻辑
 * （[QuestionCardBinder]），差别在承载容器与分页口径：
 *
 * 1. 每页外面套一层 NestedScrollView，长题干 / 材料组可在页内纵向滚动，横向手势留给 ViewPager2；
 * 2. **单题模式的答题内容以材料为准划分**：一个材料组（[PaperStructure.Group]）= 一页，
 *    组内不再分页——资料分析这种「一篇材料带 5 道小题」的题组，翻页翻不出材料；
 *    **多题材料组用上下分栏**（`item_group_pager_page.xml`）：材料固定上区独立滚动，
 *    小题放底部悬浮面板（圆角 + 投影）独立滚动，中间手柄可上下拖动调占比（20%～80%，偏好记忆）；
 *    单题组仍走旧版整页滚动版式（`item_question_pager_page.xml`）；
 * 3. **大类说明单独占一页**：说明卡（`view_module_header.xml`）在大类第一题之前单独翻一页，
 *    卡片样式与卷面内嵌版相同；`subject` 为空的大类不插说明页。
 *
 * 页码表的划分依据全部来自 [PaperStructure]（大类 → 材料 → 题目一次算好），
 * 这里不再自己比较 subject / material——否则一处的口径改了，另一处就悄悄漂移。
 *
 * 注意：对外仍然以**题号**为单位——[pageOfQuestion] / [questionOfPage] / [questionsOfPage]
 * 负责在两种下标之间换算，调用方（答题卡跳转、自动下一题、模式切换、单卡刷新）不该直接拿
 * ViewPager2 的页码当题号用。
 */
class QuestionPagerAdapter(
    private val questions: List<Question>,
    /** 试卷三级结构；题目集合变化时用 [updateStructure] 换一份，页码表会跟着重建 */
    private var structure: PaperStructure = PaperStructure(questions),
    private val answers: MutableMap<Long, String>,
    private val marked: MutableSet<Long>,
    private val favorites: MutableSet<Long>,
    private val policyProvider: () -> Policy,
    var fontScale: Float = 1f,
    var revealAnswers: Boolean = false,
    /** 练习模式：已触发即时判分的题 id（作答后立即出对错与解析） */
    var revealed: Set<Long> = emptySet(),
    /** 夜间模式：透传给 [QuestionCardBinder] */
    var night: Boolean = false,
    /** 分屏右栏：材料已在左栏展示，卡片不再重复渲染材料 */
    var hideMaterial: Boolean = false,
    onMarkToggle: (Question) -> Unit,
    onFavoriteToggle: (Question) -> Unit,
    onNoteClicked: (Question) -> Unit,
    /** 点卡片上的「笔」→ 进手写标注；不传时退回旧行为（打开笔记） */
    onInkClicked: (Question) -> Unit = onNoteClicked,
    onAnswerChanged: (Question) -> Unit,
    /** 材料卡右上角「弹出材料」 */
    onMaterialExpand: ((Question) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_QUESTION = 0
        const val TYPE_MODULE = 1
        /** 多题材料组页：上材料 / 中手柄 / 下悬浮题目（item_group_pager_page.xml） */
        const val TYPE_GROUP = 2

        /** 材料组上下占比（手柄距页顶）：偏好键与上下限，比例存 exam_prefs */
        const val PREFS = "exam_prefs"
        const val KEY_GROUP_RATIO = "group_split_ratio"
        const val RATIO_MIN = 0.2f
        const val RATIO_MAX = 0.8f
        const val RATIO_DEFAULT = 0.45f
    }

    /**
     * 单题模式的一页。
     *
     * - 普通组页：[questionCount] = 1，一题一页；
     * - 材料组页：同一则材料的连续小题合在一页（[questionCount] > 1），题号区间 `start until end`；
     * - 说明页：[isModuleIntro]，落在该大类第一题之前，页上没有题目。
     */
    class Page(val start: Int, val questionCount: Int, val isModuleIntro: Boolean) {
        val end: Int get() = start + questionCount
        val isGroup: Boolean get() = !isModuleIntro && questionCount > 1
    }

    /** 页内一张卡：题号 + 绑定对象，重绑时按题号判断能否复用（复用就不丢页内滚动位置） */
    class CardHolder(val index: Int, val binding: ItemQuestionPageBinding)

    /** 展开后的页码表：大类首页是说明页，其后才是材料组页 / 单题页（结构变更时由 [updateStructure] 重建） */
    var pages: List<Page> = buildPages()
        private set

    /** 题号 → 页码。说明页不占题号，所以这里只会指向组页 */
    private var pageOfQuestionIndex: IntArray = buildPageIndex()

    /**
     * 页码表：按 [PaperStructure] 走一遍——每个大类先来一页说明页（name 非空时），
     * 然后每个材料组一页（材料组页 / 单题页）。
     */
    private fun buildPages(): List<Page> {
        val out = ArrayList<Page>(questions.size + structure.sections.size)
        for (section in structure.sections) {
            // subject 为空的大类不插说明页（与卷面内嵌版口径一致）
            if (section.name != null) out += Page(section.startIndex, 0, isModuleIntro = true)
            for (group in section.groups) {
                out += Page(group.startIndex, group.count, isModuleIntro = false)
            }
        }
        return out
    }

    private fun buildPageIndex(): IntArray =
        IntArray(questions.size) { -1 }.also { arr ->
            pages.forEachIndexed { page, p ->
                if (!p.isModuleIntro) for (i in p.start until p.end) arr[i] = page
            }
        }

    /**
     * 换一份结构并重建页码表（补答案后重读整卷时用）。
     * 重建可能改变页序，所以整表刷新，由调用方负责「当前页」的重新定位。
     */
    fun updateStructure(structure: PaperStructure) {
        this.structure = structure
        pages = buildPages()
        pageOfQuestionIndex = buildPageIndex()
        notifyDataSetChanged()
    }

    /**
     * 题号 → 页码。找不到（空卷等异常输入）时退回 0，避免 setCurrentItem 抛下标越界。
     * 已知题号一定有对应题目页，因为每个题号都会落进某一页。
     */
    fun pageOfQuestion(questionIndex: Int): Int =
        pageOfQuestionIndex.getOrNull(questionIndex)?.takeIf { it >= 0 } ?: 0

    /** 页码 → 该页第一题的题号。说明页返回该大类的第一题，调用方拿它当作"当前题"的落点即可 */
    fun questionOfPage(page: Int): Int = pages.getOrNull(page)?.start ?: 0

    /** 页码 → 该页覆盖的题号区间；越界返回 null */
    fun questionsOfPage(page: Int): IntRange? =
        pages.getOrNull(page)?.takeIf { !it.isModuleIntro }?.let { it.start until it.end }

    /** 该页是不是大类说明页（说明页上没有题目，遮盖层不应绑任何笔迹） */
    fun isModuleIntroPage(page: Int): Boolean = pages.getOrNull(page)?.isModuleIntro == true

    private val cb = QuestionCardBinder.Callbacks(
        onMarkToggle = onMarkToggle,
        onFavoriteToggle = onFavoriteToggle,
        onInkClicked = onInkClicked,
        onNoteClicked = onNoteClicked,
        onAnswerChanged = onAnswerChanged,
        onMaterialExpand = onMaterialExpand
    )

    /**
     * 材料组页头上的「弹出材料」。
     *
     * 构造参数只在初始化器里可见，而组页是在 [bindGroupPage] 里现绑的，所以先存一份；
     * 独题页 / 列表里走的是题目卡上的同名入口（[QuestionCardBinder]），两处最终都落到
     * 答题页的 `showMaterialPopup`——**一则材料带多道小题时，单题模式同样能弹出**。
     */
    private val expandMaterial: ((Question) -> Unit)? = onMaterialExpand

    class QuestionVH(val page: ItemQuestionPagerPageBinding) : RecyclerView.ViewHolder(page.root)

    class ModuleVH(val page: ItemModulePageBinding) : RecyclerView.ViewHolder(page.root)

    /** 多题材料组页：上材料 / 中手柄 / 下悬浮题目 */
    class GroupVH(val page: ItemGroupPagerPageBinding) : RecyclerView.ViewHolder(page.root)

    override fun getItemCount() = pages.size

    override fun getItemViewType(position: Int): Int = when {
        pages[position].isModuleIntro -> TYPE_MODULE
        pages[position].isGroup -> TYPE_GROUP
        else -> TYPE_QUESTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        // 宽度交给 activity_exam.xml 的约束（铺满可用宽度），这里不参与列宽计算
        return when (viewType) {
            TYPE_MODULE -> ModuleVH(ItemModulePageBinding.inflate(inflater, parent, false))
            TYPE_GROUP -> {
                val b = ItemGroupPagerPageBinding.inflate(inflater, parent, false)
                // 手柄拖拽只挂一次（重绑不重复挂），拖动时改 guideline 占比
                attachGroupDrag(b)
                GroupVH(b)
            }
            else -> QuestionVH(ItemQuestionPagerPageBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val page = pages[position]
        when {
            page.isModuleIntro -> bindModuleIntro(holder as ModuleVH, page)
            page.isGroup -> bindGroupPage(holder as GroupVH, page)
            else -> bindQuestionPage(holder as QuestionVH, page)
        }
    }

    /** 大类说明页：一张说明卡 + 一行滑动提示，垂直居中 */
    private fun bindModuleIntro(holder: ModuleVH, page: Page) {
        val ctx = holder.page.root.context
        holder.page.pageScroll.setBackgroundColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_sheet_bg else R.color.fb_sheet_bg
            )
        )
        structure.sectionAt(page.start)?.let { section ->
            ModuleHeaders.headerOf(ctx, section)?.let { header ->
                QuestionCardBinder.bindModuleCard(holder.page.moduleCard, header, night)
            }
        }
        holder.page.tvModuleHint.setTextColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_text_meta else R.color.fb_text_meta
            )
        )
    }

    /**
     * 题页：一页一题（普通题 / 单题材料组，材料随卡滚动）。
     *
     * 结构复用：页内题量没变就只重绑已有卡片。否则重绑会 removeAllViews 重建，
     * NestedScrollView 的滚动位置会被顶回顶部——组页里答完一题就跳回组首，根本没法用。
     */
    private fun bindQuestionPage(holder: QuestionVH, page: Page) {
        val container = holder.page.cardContainer
        val count = page.questionCount

        // 页底留白也铺卷面色（夜间换深色），短页不会透出外层底色
        holder.page.pageScroll.setBackgroundColor(
            ContextCompat.getColor(
                container.context,
                if (night) R.color.fb_night_sheet_bg else R.color.fb_sheet_bg
            )
        )

        if (!sameStructure(container, page)) {
            container.removeAllViews()
            val inflater = LayoutInflater.from(container.context)
            for (qi in page.start until page.end) {
                val card = ItemQuestionPageBinding.inflate(inflater, container, false)
                card.root.tag = CardHolder(qi, card)
                container.addView(card.root)
            }
        }

        for (k in 0 until count) {
            val qi = page.start + k
            val card = (container.getChildAt(k).tag as CardHolder).binding
            QuestionCardBinder.bind(
                b = card,
                position = qi,
                q = questions[qi],
                answers = answers,
                marked = marked,
                favorites = favorites,
                revealAnswers = revealAnswers,
                revealed = revealed,
                policy = policyProvider(),
                fontScale = fontScale,
                cb = cb,
                night = night,
                // 组页里材料只在第一题渲染一次，后面的小题接着往下排
                hideMaterial = hideMaterial || k > 0,
                // 大类说明已经单独占一页，组页不再内嵌那张卡
                moduleHeader = null,
                // 组内有题与题的细线；页内最后一题下面没有下一题，不画
                showDivider = k < count - 1
            )
        }
    }

    /** 页内已有的卡片是否正好对应这一页的题号（题量、顺序都对得上才复用） */
    private fun sameStructure(container: ViewGroup, page: Page): Boolean {
        if (container.childCount != page.questionCount) return false
        for (k in 0 until page.questionCount) {
            if ((container.getChildAt(k).tag as? CardHolder)?.index != page.start + k) return false
        }
        return true
    }

    // ---------------- 多题材料组页：上材料 / 中手柄 / 下悬浮题目 ----------------

    /**
     * 材料组页（一则材料带多道小题）：材料固定上区独立滚动，本组小题放底部**悬浮面板**
     * （圆角 + 投影，独立滚动），中间手柄上下拖动调节占比——做题时材料始终在屏，
     * 不用再「往下滚很久找题、再滚回来对材料」。
     */
    private fun bindGroupPage(holder: GroupVH, page: Page) {
        val b = holder.page
        val ctx = b.root.context

        // 卷面底色 / 手柄 grip / 悬浮面板（夜间跟换；版式不动）
        b.root.setBackgroundColor(
            ContextCompat.getColor(ctx, if (night) R.color.fb_night_sheet_bg else R.color.fb_sheet_bg)
        )
        b.groupHandleBar.setBackgroundResource(
            if (night) R.drawable.bg_fb_drag_bar_night else R.drawable.bg_fb_drag_bar
        )
        b.questionPane.setBackgroundResource(
            if (night) R.drawable.bg_fb_sheet_night else R.drawable.bg_fb_sheet
        )

        // 材料头：与题目卡上的材料头同一套配色；「弹出材料」在组页也留着——
        // 上区再宽也就半屏，表格 / 图表想看清还是得弹出来（平板就地分屏 / 手机半屏浮层）
        b.tvGroupMaterialTag.setBackgroundResource(
            if (night) R.drawable.bg_fb_material_tag_night else R.drawable.bg_fb_material_tag
        )
        b.tvGroupMaterialTag.setTextColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_tag_text else R.color.fb_type_tag_text
            )
        )
        val metaColor = ContextCompat.getColor(
            ctx, if (night) R.color.fb_night_text_meta else R.color.fb_text_meta
        )
        b.tvGroupMaterialExpand.setTextColor(metaColor)
        b.icGroupMaterialExpand.setColorFilter(metaColor)
        b.btnGroupMaterialExpand.visibility =
            if (!hideMaterial && expandMaterial != null) View.VISIBLE else View.GONE
        b.btnGroupMaterialExpand.setOnClickListener { expandMaterial?.invoke(questions[page.start]) }

        // 上下占比：偏好记忆（拖动结束时写回，见 attachGroupDrag）
        applyGroupRatio(b, groupRatioPrefs(ctx).getFloat(KEY_GROUP_RATIO, RATIO_DEFAULT))

        // 材料：只在这页的上区渲染一次；材料 / 夜间任一变化、或宽度在「非 0 → 不同的非 0」
        // （旋转 / 多窗口）时才重排——重排会把上区滚动位置顶回顶部，作答后的重绑不该动它。
        // 首绑时 width 还是 0，不算宽度变化，否则排版后的第一次重绑就会误重排。
        val material = questions[page.start].material
        val identity = "${material.hashCode()}:$night"
        val w = b.materialContent.width
        val old = b.materialContent.tag as? String
        val oldIdentity = old?.substringBeforeLast(':')
        val oldW = old?.substringAfterLast(':')?.toIntOrNull() ?: -1
        if (oldIdentity != identity || (w > 0 && oldW > 0 && oldW != w)) {
            b.materialContent.tag = "$identity:$w"
            MaterialRenderer.render(ctx, b.materialContent, material, night)
        }

        // 悬浮面板里的题目卡：复用判定与普通页同款（作答重绑不丢面板内滚动位置）
        val container = b.cardContainer
        if (!sameStructure(container, page)) {
            container.removeAllViews()
            val inflater = LayoutInflater.from(ctx)
            for (qi in page.start until page.end) {
                val card = ItemQuestionPageBinding.inflate(inflater, container, false)
                card.root.tag = CardHolder(qi, card)
                container.addView(card.root)
            }
        }
        for (k in 0 until page.questionCount) {
            val qi = page.start + k
            val card = (container.getChildAt(k).tag as CardHolder).binding
            QuestionCardBinder.bind(
                b = card,
                position = qi,
                q = questions[qi],
                answers = answers,
                marked = marked,
                favorites = favorites,
                revealAnswers = revealAnswers,
                revealed = revealed,
                policy = policyProvider(),
                fontScale = fontScale,
                cb = cb,
                night = night,
                // 材料已在上区整块展示，题目卡不再重复渲染（「弹出材料」入口随之收起）
                hideMaterial = true,
                moduleHeader = null,
                // 面板内题与题的细线；最后一题下面没有下一题，不画
                showDivider = k < page.questionCount - 1
            )
        }
    }

    private fun groupRatioPrefs(ctx: android.content.Context) =
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    /** 把上下占比落到 guideline 上（比例 = 手柄中心距页顶；guidePercent 是公开字段，不是属性） */
    private fun applyGroupRatio(b: ItemGroupPagerPageBinding, pct: Float) {
        val lp = b.groupGuideline.layoutParams as? ConstraintLayout.LayoutParams ?: return
        lp.guidePercent = pct.coerceIn(RATIO_MIN, RATIO_MAX)
        b.groupGuideline.layoutParams = lp
    }

    /**
     * 手柄拖拽（onCreateViewHolder 时挂一次）：按手指的屏幕纵坐标直接算占比，
     * 松手写偏好。拖动消费掉事件，不会被 ViewPager2 当成翻页手势。
     */
    private fun attachGroupDrag(b: ItemGroupPagerPageBinding) {
        val rootLoc = IntArray(2)
        b.groupHandle.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_MOVE -> {
                    b.root.getLocationOnScreen(rootLoc)
                    val pct = (ev.rawY - rootLoc[1]) / b.root.height.coerceAtLeast(1)
                    applyGroupRatio(b, pct)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val pct = (b.groupGuideline.layoutParams as ConstraintLayout.LayoutParams).guidePercent
                    groupRatioPrefs(v.context).edit().putFloat(KEY_GROUP_RATIO, pct).apply()
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }
}
