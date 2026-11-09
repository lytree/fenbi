package com.lytree.qbank.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.model.AnswerResult
import com.lytree.qbank.databinding.ItemReportQuestionBinding
import com.lytree.qbank.ui.record.RecordAdapter
import java.util.Locale

/**
 * 解析卡列表（「全部解析」页用：题干 / 我的答案 / 正确答案 / 解析一屏列全）。
 *
 * [marked] 是本次作答被标记（细线星）的题 id —— 交卷时随记录落库，
 * 所以回看旧记录也能看出当时标了哪几题，而不只是当轮答题卡上的点。
 * [times] 是每道题各自花掉的秒数（答题时逐题计时），做题节奏一目了然。
 * [onItemClick] 点卡片 = 去试卷里看这道题的完整资料（材料 / 选项 / 答案解析）。
 *
 * 成绩报告页本身只放「全部题目」对错总览，不再直接铺这一大串解析，
 * 解析看哪里由用户自己决定：单题看 [QuestionReviewActivity]（与答题页同款卷面），全量看这里。
 */
class ReportAdapter(
    private val items: List<AnswerResult>,
    private val marked: Set<Long> = emptySet(),
    private val times: Map<Long, Int> = emptyMap(),
    private val onItemClick: ((Int) -> Unit)? = null
) :
    RecyclerView.Adapter<ReportAdapter.VH>() {

    class VH(val binding: ItemReportQuestionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemReportQuestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val ctx = holder.itemView.context
        val sec = times[r.question.id] ?: 0
        holder.binding.tvIndex.text = buildString {
            append("第 ${position + 1} 题 · ${r.question.type.label}")
            // 单独记的每题用时：没计到（比如旧记录）就不显示，不硬塞一个 0 秒
            if (sec > 0) append(" · 用时 ${RecordAdapter.fmtDuration(sec)}")
        }
        holder.binding.tvStem.text = r.question.stem
        holder.binding.tvMark.visibility =
            if (r.question.id in marked) View.VISIBLE else View.GONE

        val (label, color) = statusStyle(ctx, r)
        holder.binding.tvStatus.text = label
        holder.binding.tvStatus.setTextColor(ContextCompat.getColor(ctx, color))

        holder.binding.tvMine.text = "你的答案：${r.userAnswer.ifBlank { "未作答" }}" +
            if (r.pending) "" else "　得分 ${fmt(r.earned)}/${fmt(r.full)}"
        holder.binding.tvCorrect.text =
            if (r.pending) ctx.getString(R.string.pending_answer_empty)
            else "正确答案：${r.question.displayAnswer()}"

        if (r.question.analysis.isNotBlank()) {
            holder.binding.tvAnalysis.visibility = View.VISIBLE
            holder.binding.tvAnalysis.text = "解析：${r.question.analysis}"
        } else {
            holder.binding.tvAnalysis.visibility = View.GONE
        }

        // 整卡可点：跳到试卷里对应的题（材料 / 选项 / 答案 / 解析都在那边看全）
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemClick?.invoke(pos)
        }
    }

    companion object {
        /** 判定状态 → 文案 + 配色（成绩报告的对错总览与全部解析共用一套口径） */
        fun statusStyle(ctx: android.content.Context, r: AnswerResult): Pair<String, Int> = when {
            r.pending -> ctx.getString(R.string.pending_answer) to R.color.partial
            r.userAnswer.isBlank() -> "未作答" to R.color.blank
            r.correct -> "正确" to R.color.right
            r.partial -> "部分得分" to R.color.partial
            else -> "错误" to R.color.wrong
        }

        fun fmt(v: Double) =
            if (v == v.toLong().toDouble()) v.toLong().toString()
            else String.format(Locale.CHINA, "%.1f", v)
    }
}
