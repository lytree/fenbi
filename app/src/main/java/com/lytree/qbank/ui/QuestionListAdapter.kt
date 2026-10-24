package com.lytree.qbank.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.data.model.PaperStructure
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ItemQuestionPageBinding
import com.lytree.qbank.engine.Policy

/**
 * 题目列表适配器（多题浏览）。
 *
 * 题目块结构：题目行（题号 `N.` + 题型标签 + 标记/笔记/收藏三图标）→ 题干 → 选项 → 解析。
 * 渲染细节全部委托给 [QuestionCardBinder]，与单题浏览的 [QuestionPagerAdapter] 共用一套逻辑。
 *
 * 与 [QuestionPagerAdapter] 的差异只在承载容器与定位方式：这里是连续滚动，
 * 题号在题块内部；那边是一屏一题，靠左右滑动切题。
 *
 * 「材料要不要重复渲染」「大类说明卡挂在哪一题前」都从 [PaperStructure] 取数：
 * 材料组只在组首渲染一次，大类说明只在大类首题出现一次。
 */
class QuestionListAdapter(
    private val questions: List<Question>,
    /** 试卷三级结构；题目集合变化时用 [updateStructure] 换一份 */
    private var structure: PaperStructure = PaperStructure(questions),
    private val answers: MutableMap<Long, String>,
    private val marked: MutableSet<Long>,
    private val favorites: MutableSet<Long>,
    var revealAnswers: Boolean = false,
    /** 练习模式：已触发即时判分的题 id（作答后立即出对错与解析） */
    var revealed: Set<Long> = emptySet(),
    private val policyProvider: () -> Policy,
    var fontScale: Float = 1f,
    /** 夜间模式：透传给 [QuestionCardBinder]，切换后靠 notifyDataSetChanged 重绑生效 */
    var night: Boolean = false,
    /** 分屏右栏：材料在左栏展示，所有卡片都不再重复渲染材料 */
    var hideAllMaterials: Boolean = false,
    /** 分屏右栏：材料模块说明已经在左栏/卷面上出现过，右栏不再重复 */
    var hideModuleHeaders: Boolean = false,
    /** 题号偏移：分屏右栏只装一组题，但题号仍按整卷序号显示 */
    var indexOffset: Int = 0,
    onMarkToggle: (Question) -> Unit,
    onFavoriteToggle: (Question) -> Unit,
    onNoteClicked: (Question) -> Unit,
    /** 点卡片上的「笔」→ 进手写标注；不传时退回旧行为（打开笔记） */
    onInkClicked: (Question) -> Unit = onNoteClicked,
    onAnswerChanged: (Question) -> Unit,
    /** 材料卡右上角「弹出材料」 */
    onMaterialExpand: ((Question) -> Unit)? = null
) : RecyclerView.Adapter<QuestionListAdapter.VH>() {

    private val cb = QuestionCardBinder.Callbacks(
        onMarkToggle = onMarkToggle,
        onFavoriteToggle = onFavoriteToggle,
        onInkClicked = onInkClicked,
        onNoteClicked = onNoteClicked,
        onAnswerChanged = onAnswerChanged,
        onMaterialExpand = onMaterialExpand
    )

    class VH(val binding: ItemQuestionPageBinding) : RecyclerView.ViewHolder(binding.root)

    /** 换一份结构（补答案后重读整卷时用）；分组变了要整表重绑 */
    fun updateStructure(structure: PaperStructure) {
        this.structure = structure
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        // 列宽（手机撑满 / 平板 820dp 居中）由 activity_exam.xml 的约束负责，
        // 这里不再按 displayMetrics 反算左右 margin——多窗口、分屏、旋转时那套算法都不准。
        VH(ItemQuestionPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = questions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ctx = holder.binding.root.context
        QuestionCardBinder.bind(
            b = holder.binding,
            position = position + indexOffset,
            q = questions[position],
            answers = answers,
            marked = marked,
            favorites = favorites,
            revealAnswers = revealAnswers,
            revealed = revealed,
            policy = policyProvider(),
            fontScale = fontScale,
            cb = cb,
            night = night,
            // 资料分析常是一则材料带多题：材料只在材料组的第一题渲染一次，
            // 后面的题接着往下排（对齐参考应用的连续卷面）
            hideMaterial = hideAllMaterials || !structure.isGroupStart(position),
            // 大类说明卡：同一个大类只在首题出现一次（对齐参考应用的「资料分析 (20题)」）
            moduleHeader = if (hideModuleHeaders) {
                null
            } else {
                structure.sectionAt(position)
                    ?.takeIf { structure.isSectionStart(position) }
                    ?.let { ModuleHeaders.headerOf(ctx, it) }
            }
        )
    }

    companion object {
        /** 兼容旧调用点（资料分析分屏页还在用）：选项徽标两态 */
        fun applyCircleState(item: com.lytree.qbank.databinding.ItemOptionBinding, selected: Boolean) =
            QuestionCardBinder.applyCircleState(item, selected)

        fun fmtScore(v: Double): String = QuestionCardBinder.fmtScore(v)
    }
}
