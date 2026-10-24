package com.lytree.qbank.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ItemModulePageBinding
import com.lytree.qbank.databinding.ItemQuestionPagerPageBinding
import com.lytree.qbank.engine.Policy

/**
 * 单题浏览适配器。配合 [androidx.viewpager2.widget.ViewPager2] 实现「一屏一题、左右滑动切题」。
 *
 * 与 [QuestionListAdapter] 共用同一套卡片版式（`item_question_page.xml`）与同一份渲染逻辑
 * （[QuestionCardBinder]），差别有两处：
 *
 * 1. 每页外面套一层 NestedScrollView，长题干 / 材料题可在页内纵向滚动，横向手势留给 ViewPager2；
 * 2. **模块说明单独占一页**：一屏一题的前提是一屏只放得下一题，如果说明卡和第一题挤在一起，
 *    题干 + 选项常被挤出屏幕。所以这里把页码表展开成「说明页 + 题目页」，
 *    说明卡（`view_module_header.xml`）在模块第一题之前单独翻一页，卡片样式与卷面内嵌版相同。
 *
 * 注意：对外仍然以**题号**为单位——[pageOfQuestion] / [questionOfPage] 负责在两种下标之间换算，
 * 调用方（答题卡跳转、自动下一题、模式切换、单卡刷新）不该直接拿 ViewPager2 的页码当题号用。
 */
class QuestionPagerAdapter(
    private val questions: List<Question>,
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
    }

    /**
     * 单题模式的一页。[questionIndex] 是这道题在整卷中的题号；
     * 说明页把它指向「该模块的第一题」，方便回到题目时落位。
     */
    class Page(val questionIndex: Int, val isModuleIntro: Boolean)

    /** 展开后的页码表：每个模块首页是说明页，其后才是题目页 */
    val pages: List<Page> = buildPages()

    /** 题号 → 页码。说明页不占题号，所以这里只会指向题目页 */
    private val pageOfQuestionIndex: IntArray = IntArray(questions.size) { -1 }.also { arr ->
        pages.forEachIndexed { page, p -> if (!p.isModuleIntro) arr[p.questionIndex] = page }
    }

    private fun buildPages(): List<Page> {
        val out = ArrayList<Page>(questions.size + 4)
        questions.indices.forEach { i ->
            if (ModuleHeaders.isModuleStart(questions, i)) out += Page(i, isModuleIntro = true)
            out += Page(i, isModuleIntro = false)
        }
        return out
    }

    /**
     * 题号 → 页码。找不到（空卷等异常输入）时退回 0，避免 setCurrentItem 抛下标越界。
     * 已知题号一定有对应题目页，因为每个题号都会生成一页。
     */
    fun pageOfQuestion(questionIndex: Int): Int =
        pageOfQuestionIndex.getOrNull(questionIndex)?.takeIf { it >= 0 } ?: 0

    /** 页码 → 题号。说明页返回该模块的第一题，调用方拿它当作"当前题"的落点即可 */
    fun questionOfPage(page: Int): Int = pages.getOrNull(page)?.questionIndex ?: 0

    /** 该页是不是模块说明页（说明页上没有题目，遮盖层不应绑任何笔迹） */
    fun isModuleIntroPage(page: Int): Boolean = pages.getOrNull(page)?.isModuleIntro == true

    private val cb = QuestionCardBinder.Callbacks(
        onMarkToggle = onMarkToggle,
        onFavoriteToggle = onFavoriteToggle,
        onInkClicked = onInkClicked,
        onNoteClicked = onNoteClicked,
        onAnswerChanged = onAnswerChanged,
        onMaterialExpand = onMaterialExpand
    )

    class QuestionVH(val page: ItemQuestionPagerPageBinding) : RecyclerView.ViewHolder(page.root)

    class ModuleVH(val page: ItemModulePageBinding) : RecyclerView.ViewHolder(page.root)

    override fun getItemCount() = pages.size

    override fun getItemViewType(position: Int): Int =
        if (pages[position].isModuleIntro) TYPE_MODULE else TYPE_QUESTION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        // 列宽同样交给 activity_exam.xml 的约束（手机撑满 / 平板 820dp 居中）
        return if (viewType == TYPE_MODULE) {
            ModuleVH(ItemModulePageBinding.inflate(inflater, parent, false))
        } else {
            QuestionVH(ItemQuestionPagerPageBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val page = pages[position]
        if (page.isModuleIntro) bindModuleIntro(holder as ModuleVH, page) else bindQuestion(holder as QuestionVH, page)
    }

    /** 模块说明页：一张说明卡 + 一行滑动提示，垂直居中 */
    private fun bindModuleIntro(holder: ModuleVH, page: Page) {
        val ctx = holder.page.root.context
        holder.page.pageScroll.setBackgroundColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_sheet_bg else R.color.fb_sheet_bg
            )
        )
        ModuleHeaders.headerAt(ctx, questions, page.questionIndex)?.let {
            QuestionCardBinder.bindModuleCard(holder.page.moduleCard, it, night)
        }
        holder.page.tvModuleHint.setTextColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_text_meta else R.color.fb_text_meta
            )
        )
    }

    private fun bindQuestion(holder: QuestionVH, page: Page) {
        QuestionCardBinder.bind(
            b = holder.page.card,
            position = page.questionIndex,
            q = questions[page.questionIndex],
            answers = answers,
            marked = marked,
            favorites = favorites,
            revealAnswers = revealAnswers,
            revealed = revealed,
            policy = policyProvider(),
            fontScale = fontScale,
            cb = cb,
            night = night,
            hideMaterial = hideMaterial,
            // 模块说明已经单独占一页，题目页不再内嵌那张卡
            moduleHeader = null,
            // 单题浏览下方没有下一题，不画题间分隔线
            showDivider = false
        )
    }
}
