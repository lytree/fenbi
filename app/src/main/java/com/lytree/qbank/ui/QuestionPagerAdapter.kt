package com.lytree.qbank.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ItemQuestionPagerPageBinding
import com.lytree.qbank.engine.Policy

/**
 * 单题浏览适配器。配合 [androidx.viewpager2.widget.ViewPager2] 实现「一屏一题、左右滑动切题」。
 *
 * 与 [QuestionListAdapter] 共用同一套卡片版式（`item_question_page.xml`）与同一份渲染逻辑
 * （[QuestionCardBinder]），差别只有承载容器：
 * - 这里每页外面套一层 [androidx.core.widget.NestedScrollView]，长题干 / 材料题可在页内纵向滚动，
 *   横向手势留给 ViewPager2；
 * - 题号仍是卡片内的「N.」，与多题浏览一致。
 */
class QuestionPagerAdapter(
    private val questions: List<Question>,
    private val answers: MutableMap<Long, String>,
    private val marked: MutableSet<Long>,
    private val favorites: MutableSet<Long>,
    private val policyProvider: () -> Policy,
    var fontScale: Float = 1f,
    var revealAnswers: Boolean = false,
    /** 夜间模式：透传给 [QuestionCardBinder] */
    var night: Boolean = false,
    onMarkToggle: (Question) -> Unit,
    onFavoriteToggle: (Question) -> Unit,
    onNoteClicked: (Question) -> Unit,
    /** 点卡片上的「笔」→ 进手写标注；不传时退回旧行为（打开笔记） */
    onInkClicked: (Question) -> Unit = onNoteClicked,
    onAnswerChanged: (Question) -> Unit,
    /** 材料卡右上角「弹出材料」 */
    onMaterialExpand: ((Question) -> Unit)? = null
) : RecyclerView.Adapter<QuestionPagerAdapter.VH>() {

    private val cb = QuestionCardBinder.Callbacks(
        onMarkToggle = onMarkToggle,
        onFavoriteToggle = onFavoriteToggle,
        onInkClicked = onInkClicked,
        onNoteClicked = onNoteClicked,
        onAnswerChanged = onAnswerChanged,
        onMaterialExpand = onMaterialExpand
    )

    class VH(val page: ItemQuestionPagerPageBinding) : RecyclerView.ViewHolder(page.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val holder = VH(
            ItemQuestionPagerPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
        applyContentWidth(holder.page.card.root)
        return holder
    }

    /** 宽屏上把卡片收窄到 520dp 居中，与多题浏览的列宽一致 */
    private fun applyContentWidth(card: View) {
        val res = card.resources
        val lp = card.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val side = maxOf(
            res.getDimension(R.dimen.fb_page_padding_h),
            (res.displayMetrics.widthPixels - res.getDimension(R.dimen.fb_content_max_width)) / 2f
        )
        lp.marginStart = side.toInt()
        lp.marginEnd = side.toInt()
        card.layoutParams = lp
    }

    override fun getItemCount() = questions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        QuestionCardBinder.bind(
            b = holder.page.card,
            position = position,
            q = questions[position],
            answers = answers,
            marked = marked,
            favorites = favorites,
            revealAnswers = revealAnswers,
            policy = policyProvider(),
            fontScale = fontScale,
            cb = cb,
            night = night
        )
    }
}
