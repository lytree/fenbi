package com.lytree.qbank.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.data.model.QuestionType
import com.lytree.qbank.databinding.ItemOptionBinding
import com.lytree.qbank.databinding.ItemQuestionPageBinding
import com.lytree.qbank.engine.Judge
import com.lytree.qbank.engine.Policy

/**
 * 题目列表适配器（多题浏览）。
 *
 * 题目块结构：
 * 题目行（题号 `N.` + 题型标签 + 标记/笔记/收藏三图标）→ 题干 → 选项。
 *
 * 与旧版 [QuestionPagerAdapter] 的差异：
 * - 题号由 Activity 侧传入（列表模式里题号在题块内部，不在顶栏）；
 * - 每题自带 标记 / 笔记 / 收藏 三个动作按钮；
 * - 选项徽标遵循参考应用三态：未选（灰描边）/ 已选（蓝实心白字）/ 判题后正确错误。
 */
class QuestionListAdapter(
    private val questions: List<Question>,
    private val answers: MutableMap<Long, String>,
    private val marked: MutableSet<Long>,
    private val favorites: MutableSet<Long>,
    private var revealAnswers: Boolean = false,
    private val policyProvider: () -> Policy,
    private val fontScale: Float = 1f,
    private val onMarkToggle: (Question) -> Unit,
    private val onFavoriteToggle: (Question) -> Unit,
    private val onNoteClicked: (Question) -> Unit,
    private val onAnswerChanged: (Question) -> Unit
) : RecyclerView.Adapter<QuestionListAdapter.VH>() {

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
    private fun applyContentWidth(item: android.view.View) {
        val res = item.resources
        val lp = item.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val side = maxOf(
            res.getDimension(R.dimen.fb_page_padding_h),
            (res.displayMetrics.widthPixels - res.getDimension(R.dimen.fb_content_max_width)) / 2f
        )
        lp.marginStart = side.toInt()
        lp.marginEnd = side.toInt()
        item.layoutParams = lp
    }

    override fun getItemCount() = questions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val q = questions[position]
        val b = holder.binding
        val ctx = b.root.context

        // 题目行：题号 + 题型标签 + 三图标
        b.tvIndex.text = "${position + 1}."
        b.tvType.text = q.type.label
        b.tvType.setTextColor(
            androidx.core.content.ContextCompat.getColor(ctx, R.color.fb_type_tag_text)
        )

        val isMarked = q.id in marked
        val isFavorite = q.id in favorites
        b.btnMark.setImageResource(
            if (isMarked) R.drawable.ic_fb_bookmark_filled else R.drawable.ic_fb_bookmark
        )
        b.btnMark.setColorFilter(
            androidx.core.content.ContextCompat.getColor(
                ctx, if (isMarked) R.color.fb_primary else R.color.fb_icon
            )
        )
        b.btnFavorite.setImageResource(
            if (isFavorite) R.drawable.ic_fb_star_filled else R.drawable.ic_fb_star
        )
        b.btnFavorite.setColorFilter(
            androidx.core.content.ContextCompat.getColor(
                ctx, if (isFavorite) R.color.fb_card_num_favorite else R.color.fb_icon
            )
        )

        b.btnMark.setOnClickListener { onMarkToggle(q) }
        b.btnFavorite.setOnClickListener { onFavoriteToggle(q) }
        b.btnScratch.setOnClickListener { onNoteClicked(q) }

        // 题干（字号随「更多」面板的字号设置缩放；基准 17sp / 行距 6dp）
        b.tvStem.text = q.stem
        b.tvStem.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f * fontScale)
        b.tvStem.setLineSpacing(
            6f * ctx.resources.displayMetrics.density * fontScale, 1f
        )

        // 材料
        if (q.material.isNotBlank()) {
            b.materialBox.visibility = android.view.View.VISIBLE
            MaterialRenderer.render(ctx, b.materialContent, q.material)
        } else {
            b.materialBox.visibility = android.view.View.GONE
        }

        // 选项
        b.optionsBox.removeAllViews()
        buildOptions(q, holder, b)

        // 解析
        if (revealAnswers) {
            b.tvAnalysis.visibility = android.view.View.VISIBLE
            val userAns = answers[q.id] ?: ""
            val r = Judge.judge(q, userAns, policyProvider())
            b.tvAnalysis.text = buildString {
                append("正确答案：${q.displayAnswer()}\n")
                append("你的答案：${userAns.ifBlank { "未作答" }}\n")
                append(
                    "结果：${when {
                        r.correct -> "正确"
                        r.partial -> "部分得分 ${fmtScore(r.earned)}/${fmtScore(r.full)}"
                        else -> "错误"
                    }}\n"
                )
                if (q.analysis.isNotBlank()) append("解析：${q.analysis}")
            }
        } else {
            b.tvAnalysis.visibility = android.view.View.GONE
        }
    }

    private fun buildOptions(q: Question, holder: VH, b: ItemQuestionPageBinding) {
        val multi = q.type == QuestionType.MULTI || q.type == QuestionType.UNCERTAIN
        val inflater = LayoutInflater.from(holder.itemView.context)
        val selected = (answers[q.id] ?: "").split(',', '，', ' ')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toMutableSet()

        q.options.forEach { opt ->
            val item = ItemOptionBinding.inflate(inflater, b.optionsBox, false)
            item.tvKey.text = opt.key
            item.tvKey.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f * fontScale)
            item.tvText.text = opt.text
            item.tvText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
            item.tvText.setLineSpacing(
                5f * item.root.resources.displayMetrics.density * fontScale, 1f
            )

            val isSel = selected.contains(opt.key.uppercase())
            applyCircleState(item, isSel)
            item.root.setOnClickListener {
                if (multi) {
                    if (selected.contains(opt.key.uppercase())) selected.remove(opt.key.uppercase())
                    else selected.add(opt.key.uppercase())
                } else {
                    selected.clear()
                    selected.add(opt.key.uppercase())
                }
                answers[q.id] = selected.sorted().joinToString(",")
                onAnswerChanged(q)
                notifyItemChanged(holder.bindingAdapterPosition)
            }
            b.optionsBox.addView(item.root)
        }
    }

    companion object {
        /** 圆形徽标两态：未选（灰描边灰字）/ 已选（蓝实心白字） */
        fun applyCircleState(item: ItemOptionBinding, selected: Boolean) {
            val ctx = item.root.context
            if (selected) {
                item.tvKey.setBackgroundResource(R.drawable.bg_fb_circle_selected)
                item.tvKey.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.fb_circle_on))
            } else {
                item.tvKey.setBackgroundResource(R.drawable.bg_fb_circle)
                item.tvKey.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.fb_circle_text))
            }
        }

        fun fmtScore(v: Double): String =
            if (v == v.toLong().toDouble()) v.toLong().toString()
            else String.format(java.util.Locale.CHINA, "%.1f", v)
    }
}
