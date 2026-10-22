package com.lytree.qbank.ui.paper

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.data.model.Paper
import com.lytree.qbank.databinding.ItemPaperBinding

class PaperAdapter : RecyclerView.Adapter<PaperAdapter.VH>() {

    private val items = ArrayList<Paper>()
    var onClick: ((Paper) -> Unit)? = null
    var onLongClick: ((Paper) -> Unit)? = null

    fun submit(list: List<Paper>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(val binding: ItemPaperBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPaperBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.binding.title.text = p.name
        holder.binding.subtitle.text = buildString {
            val count = if (p.questionCount > 0) p.questionCount else p.questions.size
            append("$count 题")
            if (p.durationMin > 0) append(" · 限时 ${p.durationMin} 分钟")
            if (p.mode.isNotBlank()) append(" · ${p.mode}")
        }
        holder.binding.score.text = ""
        holder.binding.root.setOnClickListener { onClick?.invoke(p) }
        holder.binding.root.setOnLongClickListener { onLongClick?.invoke(p); true }
    }
}
