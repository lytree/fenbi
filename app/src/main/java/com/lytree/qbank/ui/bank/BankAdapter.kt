package com.lytree.qbank.ui.bank

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.data.model.Bank
import com.lytree.qbank.databinding.ItemBankBinding

class BankAdapter : RecyclerView.Adapter<BankAdapter.VH>() {

    private val items = ArrayList<Bank>()
    var onClick: ((Bank) -> Unit)? = null
    var onLongClick: ((Bank) -> Unit)? = null

    fun submit(list: List<Bank>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemBankBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemBankBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = items[position]
        holder.binding.title.text = b.name
        val sub = buildString {
            if (b.subject.isNotBlank()) append(b.subject)
            if (b.description.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(b.description)
            }
        }
        holder.binding.subtitle.text = sub.ifBlank { "共 ${b.questionCount} 题" }
        holder.binding.meta.text = "${b.questionCount} 题"
        holder.binding.root.setOnClickListener { onClick?.invoke(b) }
        holder.binding.root.setOnLongClickListener { onLongClick?.invoke(b); true }
    }
}
