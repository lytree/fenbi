package com.lytree.qbank.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.model.AnswerResult
import com.lytree.qbank.databinding.ItemAnalysisPageBinding
import com.lytree.qbank.ui.record.RecordAdapter
import java.util.Locale

/**
 * 逐题解析的分页适配器（一屏一题，左右滑）。
 *
 * [items] 是（原卷题号 → 该题结果）的行数据：错题档被筛过一遍，题号不能拿页码凑，
 * 否则「第 1 题」会变成筛后第一道错题的序号，跟卷面对不上。
 * [marked] 是本次作答被标记（细线星）的题 id —— 交卷时随记录落库，
 * 所以回看旧记录也能看出当时标了哪几题，而不只是当轮答题卡上的点。
 * [times] 是每道题各自花掉的秒数（答题时逐题计时），做题节奏一目了然。
 * [onItemClick] 点卡片 = 去试卷里看这道题的完整资料（材料 / 选项 / 答案解析），入参是原卷题号下标。
 */
class AnalysisPagerAdapter(
    private val items: List<Row>,
    private val marked: Set<Long> = emptySet(),
    private val times: Map<Long, Int> = emptyMap(),
    private val onItemClick: ((Int) -> Unit)? = null
) :
    RecyclerView.Adapter<AnalysisPagerAdapter.VH>() {

    /** 一行：原卷题号下标（从 0 开始）+ 这道题的作答结果 */
    data class Row(val no: Int, val result: AnswerResult)

    class VH(val binding: ItemAnalysisPageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemAnalysisPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        val r = row.result
        val b = holder.binding.card
        val ctx = holder.itemView.context
        val sec = times[r.question.id] ?: 0

        b.tvIndex.text = buildString {
            append("第 ${row.no + 1} 题 · ${r.question.type.label}")
            // 单独记的每题用时：没计到（比如旧记录）就不显示，不硬塞一个 0 秒
            if (sec > 0) append(" · 用时 ${RecordAdapter.fmtDuration(sec)}")
        }
        b.tvStem.text = r.question.stem
        b.tvMark.visibility =
            if (r.question.id in marked) android.view.View.VISIBLE else android.view.View.GONE

        val (label, color) = when (r.status) {
            "right" -> "正确" to R.color.right
            "partial" -> "部分得分" to R.color.partial
            "blank" -> "未作答" to R.color.blank
            "pending" -> ctx.getString(R.string.pending_answer) to R.color.partial
            else -> "错误" to R.color.wrong
        }
        b.tvStatus.text = label
        b.tvStatus.setTextColor(ContextCompat.getColor(ctx, color))

        b.tvMine.text = "你的答案：${r.userAnswer.ifBlank { "未作答" }}" +
            if (r.pending) "" else "　得分 ${fmt(r.earned)}/${fmt(r.full)}"
        b.tvCorrect.text =
            if (r.pending) ctx.getString(R.string.pending_answer_empty)
            else "正确答案：${r.question.displayAnswer()}"

        if (r.question.analysis.isNotBlank()) {
            b.tvAnalysis.visibility = android.view.View.VISIBLE
            b.tvAnalysis.text = "解析：${r.question.analysis}"
        } else {
            b.tvAnalysis.visibility = android.view.View.GONE
        }

        // 整卡可点：跳到试卷里对应的题（材料 / 选项 / 答案 / 解析都在那边看全）。
        // 注意监听要挂在卡片根节点上——itemView 是外层滚动容器，卡片自己 clickable 会把点击吃掉
        b.root.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemClick?.invoke(items[pos].no)
        }
    }

    private fun fmt(v: Double) =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.CHINA, "%.1f", v)
}
