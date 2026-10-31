package com.lytree.qbank.ui.paper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.db.PaperSet
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.Paper
import com.lytree.qbank.databinding.ItemPaperBinding
import com.lytree.qbank.databinding.ItemPaperSetBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 试卷列表的树形适配器：**导入标题（组）→ 组内试卷**。
 *
 * 只有两层，不再有「题库」这一层——一次导入就是一个标题，标题下面才是那份文件拆出来的卷。
 * 组可折叠（状态由 Fragment 存，重建列表时按原样还原），长按组可重命名 / 整组删除。
 */
class PaperTreeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Node {
        data class Header(val set: PaperSet, val collapsed: Boolean) : Node
        data class Row(val paper: Paper) : Node
    }

    private val nodes = ArrayList<Node>()

    var onPaperClick: ((Paper) -> Unit)? = null
    var onPaperLongClick: ((Paper) -> Unit)? = null

    /** 点了组标题：参数是这一组和「点完之后是否收起」 */
    var onToggleSet: ((PaperSet, Boolean) -> Unit)? = null
    var onSetLongClick: ((PaperSet) -> Unit)? = null

    /**
     * @param collapsed 已收起的组 id；组 id 为 0（未分组）时用 [UNGROUPED_KEY] 表示
     */
    fun submit(sets: List<PaperSet>, collapsed: Set<Long>) {
        nodes.clear()
        sets.forEach { s ->
            val folded = keyOf(s) in collapsed
            nodes.add(Node.Header(s, folded))
            if (!folded) s.papers.forEach { nodes.add(Node.Row(it)) }
        }
        notifyDataSetChanged()
    }

    /** 折叠状态用的键：未分组（id = 0）用 -1，避免和真实 id 撞上 */
    fun keyOf(set: PaperSet): Long = if (set.id > 0) set.id else UNGROUPED_KEY

    override fun getItemViewType(position: Int): Int =
        if (nodes[position] is Node.Header) TYPE_SET else TYPE_PAPER

    class SetVH(val binding: ItemPaperSetBinding) : RecyclerView.ViewHolder(binding.root)
    class PaperVH(val binding: ItemPaperBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SET) {
            SetVH(ItemPaperSetBinding.inflate(inflater, parent, false))
        } else {
            PaperVH(ItemPaperBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount() = nodes.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val node = nodes[position]) {
            is Node.Header -> bindHeader(holder as SetVH, node)
            is Node.Row -> bindPaper(holder as PaperVH, node.paper)
        }
    }

    private fun bindHeader(holder: SetVH, node: Node.Header) {
        val set = node.set
        holder.binding.arrow.text = if (node.collapsed) "▸" else "▾"
        holder.binding.title.text = set.title
        holder.binding.subtitle.text = buildString {
            append("${set.paperCount} 份 · ${set.questionCount} 题")
            if (set.missingAnswers > 0) append(" · ${set.missingAnswers} 题待补答案")
            dateText(set.createdAt)?.let { append(" · ").append(it) }
        }
        holder.binding.root.setOnClickListener {
            onToggleSet?.invoke(set, !node.collapsed)
        }
        holder.binding.root.setOnLongClickListener {
            onSetLongClick?.invoke(set)
            true
        }
    }

    private fun bindPaper(holder: PaperVH, p: Paper) {
        holder.binding.title.text = p.name
        holder.binding.subtitle.text = buildString {
            val count = if (p.questionCount > 0) p.questionCount else p.questions.size
            append("$count 题")
            if (p.durationMin > 0) append(" · 限时 ${p.durationMin} 分钟")
            // 还缺标准答案的卷子标出来，长按试卷可以补答案
            if (p.missingAnswers > 0) append(" · ${p.missingAnswers} 题待补答案")
            // 导入生成的卷子占绝大多数，不必每题都挂个「导入」；
            // 只标注历史遗留的组卷来源，方便区分。
            if (p.mode.isNotBlank() && p.mode != QBankDb.MODE_IMPORT) append(" · ${p.mode}")
        }
        holder.binding.score.text = ""
        indent(holder.binding.root)
        holder.binding.root.setOnClickListener { onPaperClick?.invoke(p) }
        holder.binding.root.setOnLongClickListener { onPaperLongClick?.invoke(p); true }
    }

    /** 组内卷子往里缩一格：靠缩进读出层级，不额外画连接线 */
    private fun indent(view: View) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val base = view.resources.getDimensionPixelSize(R.dimen.fb_page_pad)
        val extra = (view.resources.displayMetrics.density * 14f).toInt()
        if (lp.marginStart == base + extra) return
        lp.marginStart = base + extra
        view.layoutParams = lp
    }

    private fun dateText(millis: Long): String? {
        if (millis <= 0L) return null
        return try {
            SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(millis))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TYPE_SET = 0
        private const val TYPE_PAPER = 1

        /** 折叠状态里「未分组」这一伪组用的键（不可能是真实 id） */
        const val UNGROUPED_KEY = -1L
    }
}
