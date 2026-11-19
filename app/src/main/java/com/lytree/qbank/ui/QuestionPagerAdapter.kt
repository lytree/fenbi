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
    /**
     * 平板版式：材料组**按题拆页**，每页一道题，材料固定在这页上区（上下分栏）。
     *
     * 平板一屏够宽、竖向够高，材料常驻上面、题目一次只铺一道，才不用点「弹出材料」再分成
     * 左右两半；页内中缝手柄上下拖动调占比（比例与手机材料组页共用一份偏好）。
     */
    private val tabletSplit: Boolean = false,
    /**
     * 逐题题号（题号下标 → 原卷题号，0 起）。解析页的错题解析只翻错题时题号不连号，
     * 不传就按列表下标原样写。
     */
    private val numberAt: ((Int) -> Int)? = null,
    /** 只读复习（解析页）：选项点不动、也不挂打叉，判色与版式照旧 */
    private val readOnly: Boolean = false,
    /** 右上角「标记 / 收藏」是否显示；解析页显式传 true */
    private val showMarkFavorite: Boolean = !readOnly,
    /** 右上角「笔」是否显示；解析页接了同一套遮盖层 */
    private val showInk: Boolean = !readOnly,
    /** 解析页统计两项（题目 id → 统计）；不传就没有统计行 */
    private val stats: Map<Long, QuestionStats>? = null,
    /** 解析页笔记（题目 id → 正文）；传了（哪怕全空）才画笔记卡 */
    private val notes: Map<Long, String>? = null,
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
     *
     * 平板（[tabletSplit]）另有口径：材料组**一题一页**，材料跟着每页一起上屏。
     */
    private fun buildPages(): List<Page> {
        val out = ArrayList<Page>(questions.size + structure.sections.size)
        for (section in structure.sections) {
            // subject 为空的大类不插说明页（与卷面内嵌版口径一致）
            if (section.name != null) out += Page(section.startIndex, 0, isModuleIntro = true)
            for (group in section.groups) {
                if (tabletSplit && group.isMaterialGroup) {
                    // 平板：材料组不整组一页，拆成「一题一页」——材料固定在这页上区
                    for (i in group.startIndex until group.endIndex) {
                        out += Page(i, 1, isModuleIntro = false)
                    }
                } else {
                    out += Page(group.startIndex, group.count, isModuleIntro = false)
                }
            }
        }
        return out
    }

    /** 这一页是不是「上材料 / 下题目」的上下分栏页（平板一题一页的材料页） */
    private fun isSplitPage(page: Page): Boolean =
        tabletSplit && !page.isModuleIntro && page.questionCount == 1 &&
            questions.getOrNull(page.start)?.material?.isNotBlank() == true

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
        // 平板材料页与多题材料组页共用一套版式（上材料 / 下题目 + 中缝手柄）
        pages[position].isGroup || isSplitPage(pages[position]) -> TYPE_GROUP
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
            page.isGroup || isSplitPage(page) -> bindGroupPage(holder as GroupVH, page)
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
            // 单题模式里材料组只有「一材料一题」会整块留在卡上（多题的组走上区独立滚动），
            // 这时补一条两节点的树脊：材料 → 这一题，与多题卷面同一套视觉。
            val group = structure.groupAt(qi)
            val inTree = !hideMaterial && group?.isMaterialGroup == true
            QuestionCardBinder.bind(
                b = card,
                // 解析页的错题解析题号不连号：调用方给映射就按原卷题号写
                position = numberAt?.invoke(qi) ?: qi,
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
                // 「弹出材料」只给一材料多题留入口：单题材料这一页材料整段在卡上，不必再弹
                materialGroupSize = group?.count ?: 1,
                // 大类说明已经单独占一页，组页不再内嵌那张卡
                moduleHeader = null,
                // 组内有题与题的细线；页内最后一题下面没有下一题，不画
                showDivider = k < count - 1,
                rail = if (inTree && group != null) {
                    QuestionCardBinder.RailMark(
                        above = k > 0,
                        below = k < count - 1,
                        materialNode = k == 0
                    )
                } else {
                    null
                },
                materialLabel = if (inTree && group != null) {
                    card.root.context.getString(
                        R.string.tree_material_label, structure.materialOrdinal(group)
                    )
                } else {
                    null
                },
                // 解析页：选项只读，但笔 / 标记 / 收藏照旧；统计与笔记卡只在传了才画
                readOnly = readOnly,
                showMarkFavorite = showMarkFavorite,
                showInk = showInk,
                stats = stats,
                notes = notes
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
        // 弹出材料 = 就地左右分屏：单题 / 多题、手机 / 平板都留着这个入口——
        // 上下分栏里材料只有半屏高，表格 / 图表想看清还是得切成左右分屏
        b.btnGroupMaterialExpand.visibility =
            if (!hideMaterial && expandMaterial != null) View.VISIBLE else View.GONE
        b.btnGroupMaterialExpand.setOnClickListener { expandMaterial?.invoke(questions[page.start]) }
        val groupAtPage = structure.groupAt(page.start)
        // 平板一题一页的材料页：告诉用户这是这则材料的第几题
        val splitPage = isSplitPage(page)
        if (splitPage && groupAtPage != null && groupAtPage.count > 1) {
            b.tvGroupProgress.visibility = View.VISIBLE
            b.tvGroupProgress.setTextColor(metaColor)
            b.tvGroupProgress.text = ctx.getString(
                R.string.material_sheet_question_index,
                page.start - groupAtPage.startIndex + 1,
                groupAtPage.count
            )
        } else {
            b.tvGroupProgress.visibility = View.GONE
        }

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
                position = numberAt?.invoke(qi) ?: qi,
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
                showDivider = k < page.questionCount - 1,
                readOnly = readOnly,
                showMarkFavorite = showMarkFavorite,
                showInk = showInk,
                stats = stats,
                notes = notes
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
