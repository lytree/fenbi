package com.lytree.qbank.ui.idiom

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.model.Idiom
import com.lytree.qbank.databinding.ItemIdiomBinding
import com.lytree.qbank.databinding.ItemIdiomHeaderBinding

/** 成语列表的一行：首字母分组头或成语条目 */
sealed interface IdiomRow {
    data class Header(val letter: String) : IdiomRow
    data class Entry(val idiom: Idiom) : IdiomRow
}

class IdiomAdapter(
    private val onTagClick: (String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = ArrayList<IdiomRow>()
    private val expanded = HashSet<String>() // 已展开释义的成语

    fun submit(newRows: List<IdiomRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    fun letters(): List<String> =
        rows.filterIsInstance<IdiomRow.Header>().map { it.letter }

    fun positionOfLetter(letter: String): Int =
        rows.indexOfFirst { it is IdiomRow.Header && it.letter == letter }

    /** 指定位置向上找最近的分组头，用于滚动时高亮右侧索引 */
    fun letterAt(position: Int): String? {
        if (rows.isEmpty()) return null
        for (i in position.coerceIn(0, rows.lastIndex) downTo 0) {
            val row = rows[i]
            if (row is IdiomRow.Header) return row.letter
        }
        return null
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) =
        if (rows[position] is IdiomRow.Header) TYPE_HEADER else TYPE_ENTRY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemIdiomHeaderBinding.inflate(inflater, parent, false))
        } else {
            EntryVH(ItemIdiomBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is IdiomRow.Header -> (holder as HeaderVH).binding.letter.text = row.letter
            is IdiomRow.Entry -> (holder as EntryVH).bind(row.idiom)
        }
    }

    private class HeaderVH(val binding: ItemIdiomHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class EntryVH(val binding: ItemIdiomBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(idiom: Idiom) {
            val context = binding.root.context
            binding.word.text = idiom.word
            binding.definition.text = idiom.definition

            val isExpanded = expanded.contains(idiom.word)
            binding.definition.maxLines = if (isExpanded) Int.MAX_VALUE else 2
            binding.expandToggle.rotation = if (isExpanded) 180f else 0f

            binding.tagsContainer.removeAllViews()
            binding.tagsContainer.isVisible = idiom.tags.isNotEmpty()
            val density = context.resources.displayMetrics.density
            for (tag in idiom.tags) {
                val chip = TextView(context, null, 0, R.style.FbTag).apply {
                    text = tag.group
                    isClickable = true
                    setOnClickListener { onTagClick(tag.group) }
                }
                binding.tagsContainer.addView(
                    chip,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginEnd = dp(density, 6)
                        bottomMargin = dp(density, 4)
                    },
                )
            }

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (!expanded.remove(idiom.word)) expanded.add(idiom.word)
                notifyItemChanged(pos)
            }
        }

        private fun dp(density: Float, value: Int) = (density * value).toInt()
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ENTRY = 1
    }
}
