package com.lytree.qbank.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.data.model.QuestionType
import com.lytree.qbank.databinding.ItemQuestionPageBinding
import com.lytree.qbank.engine.Judge
import com.lytree.qbank.engine.Policy

/**
 * 题目分页适配器。配合 [androidx.viewpager2.widget.ViewPager2] 实现「单题浏览」模式的左右滑动切题。
 *
 * 与 [QuestionListAdapter] 共享同一套题目块布局（item_question_page.xml），
 * 区别只在于承载容器：ViewPager2（一屏一题）vs RecyclerView（连续滚动）。
 */
class QuestionPagerAdapter(
    private val questions: List<Question>,
    private val answers: MutableMap<Long, String>,
    private val marked: Set<Long>,
    private var revealAnswers: Boolean = false,
    private val policyProvider: () -> Policy,
    private val onAnswerChanged: (Question) -> Unit,
    private val onNoteClicked: (Question) -> Unit
) : RecyclerView.Adapter<QuestionPagerAdapter.VH>() {

    class VH(val binding: ItemQuestionPageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemQuestionPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = questions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val q = questions[position]
        val b = holder.binding

        b.tvIndex.text = "${position + 1}."
        b.tvType.text = q.type.label

        val isMarked = q.id in marked
        b.btnMark.setImageResource(
            if (isMarked) R.drawable.ic_fb_bookmark_filled else R.drawable.ic_fb_bookmark
        )
        b.btnMark.setColorFilter(
            androidx.core.content.ContextCompat.getColor(
                b.root.context, if (isMarked) R.color.fb_primary else R.color.fb_icon
            )
        )
        b.btnScratch.setOnClickListener { onNoteClicked(q) }

        if (q.material.isNotBlank()) {
            b.materialBox.visibility = android.view.View.VISIBLE
            MaterialRenderer.render(b.root.context, b.materialContent, q.material)
        } else {
            b.materialBox.visibility = android.view.View.GONE
        }

        b.tvStem.text = q.stem

        b.optionsBox.removeAllViews()
        buildOptions(q, holder)

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

    private fun buildOptions(q: Question, holder: VH) {
        val multi = q.type == QuestionType.MULTI || q.type == QuestionType.UNCERTAIN
        val inflater = LayoutInflater.from(holder.itemView.context)
        val selected = (answers[q.id] ?: "").split(',', '，', ' ')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toMutableSet()

        q.options.forEach { opt ->
            val item = com.lytree.qbank.databinding.ItemOptionBinding
                .inflate(inflater, holder.binding.optionsBox, false)
            item.tvKey.text = opt.key
            item.tvText.text = opt.text
            val isSel = selected.contains(opt.key.uppercase())
            QuestionListAdapter.applyCircleState(item, isSel)
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
            holder.binding.optionsBox.addView(item.root)
        }
    }

    companion object {
        fun fmtScore(v: Double): String =
            if (v == v.toLong().toDouble()) v.toLong().toString()
            else String.format(java.util.Locale.CHINA, "%.1f", v)
    }
}
