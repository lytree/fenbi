package com.lytree.qbank.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
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
 */
class QuestionListAdapter(
    private val questions: List<Question>,
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
    /** 题号偏移：分屏右栏只装一组题，但题号仍按整卷序号显示 */
    var indexOffset: Int = 0,
    /** 分屏右栏很窄：卡片贴边留 16dp，不再按 520dp 居中 */
    var compactMargins: Boolean = false,
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val holder = VH(
            ItemQuestionPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
        applyContentWidth(holder.itemView)
        return holder
    }

    /**
     * 题目卡左右外边距。参考应用在宽屏上把内容列收窄到约 520dp 居中（真机 617dp 屏两侧各留 50dp），
     * 窄屏则保留 [R.dimen.fb_page_padding_h] 的贴边距。
     */
    private fun applyContentWidth(item: View) {
        val res = item.resources
        val lp = item.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val side = if (compactMargins) res.getDimension(R.dimen.fb_page_padding_h)
        else maxOf(
            res.getDimension(R.dimen.fb_page_padding_h),
            (res.displayMetrics.widthPixels - res.getDimension(R.dimen.fb_content_max_width)) / 2f
        )
        lp.marginStart = side.toInt()
        lp.marginEnd = side.toInt()
        item.layoutParams = lp
    }

    override fun getItemCount() = questions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
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
            // 资料分析常是一则材料带多题：材料只在该组第一题渲染一次，
            // 后面的题接着往下排（对齐参考应用的连续卷面）
            hideMaterial = hideAllMaterials ||
                    (position + indexOffset > 0 && sameMaterialAsPrevious(position))
        )
    }

    /** 与上一题材料完全一致（同一则材料下的连续小题） */
    private fun sameMaterialAsPrevious(position: Int): Boolean {
        val cur = questions[position].material.trim()
        if (cur.isEmpty()) return false
        if (position > 0) return cur == questions[position - 1].material.trim()
        // 组首题：整卷上一题（分屏右栏的 indexOffset 之前那道）材料相同就不再重复
        val prev = indexOffset - 1
        return prev in questions.indices && cur == questions[prev].material.trim()
    }

    companion object {
        /** 兼容旧调用点（资料分析分屏页还在用）：选项徽标两态 */
        fun applyCircleState(item: com.lytree.qbank.databinding.ItemOptionBinding, selected: Boolean) =
            QuestionCardBinder.applyCircleState(item, selected)

        fun fmtScore(v: Double): String = QuestionCardBinder.fmtScore(v)
    }
}
