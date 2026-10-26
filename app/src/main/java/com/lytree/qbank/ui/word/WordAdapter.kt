package com.lytree.qbank.ui.word

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.model.Word
import com.lytree.qbank.databinding.ItemWordBinding
import com.lytree.qbank.databinding.ItemWordHeaderBinding

/**
 * 词库列表适配器：按拼音首字母分组（A…Z、#），组头显示组内条数。
 *
 * 分组不在数据库里做——[Word.initial] 入库时就算好了，`ORDER BY initial, pinyin, text`
 * 取出来天然是分好的顺序，这里只负责在换组的地方插一行组头。
 */
class WordAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_WORD = 1

        /** 筛选胶囊的选中态切换（样式在 themes.xml 里定义，运行时只换背景和字色） */
        fun applyChipStyle(tv: TextView, selected: Boolean) {
            tv.setBackgroundResource(
                if (selected) R.drawable.bg_fb_segment_selected else R.drawable.bg_fb_segment
            )
            val color = if (selected) R.color.fb_segment_text_sel else R.color.fb_segment_text
            tv.setTextColor(androidx.core.content.ContextCompat.getColor(tv.context, color))
        }
    }

    private class Row(val header: String? = null, val headerCount: Int = 0, val word: Word? = null)

    private val rows = ArrayList<Row>()

    /** 展开的词条 id（点一行看例句 / 出处），换筛选时清空 */
    private val expanded = HashSet<Long>()

    fun submit(words: List<Word>) {
        rows.clear()
        expanded.clear()
        var i = 0
        while (i < words.size) {
            val initial = words[i].initial.ifBlank { "#" }
            var j = i
            while (j < words.size && words[j].initial.ifBlank { "#" } == initial) j++
            rows.add(Row(header = initial, headerCount = j - i))
            for (k in i until j) rows.add(Row(word = words[k]))
            i = j
        }
        notifyDataSetChanged()
    }

    /** 字母 → 该组第一个词条在列表里的绝对位置（右侧索引条跳转用） */
    fun sectionPositions(): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        rows.forEachIndexed { index, row ->
            val h = row.header ?: return@forEachIndexed
            if (!out.containsKey(h)) out[h] = index
        }
        return out
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position].header != null) TYPE_HEADER else TYPE_WORD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemWordHeaderBinding.inflate(inflater, parent, false))
        } else {
            WordVH(ItemWordBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (holder is HeaderVH) {
            holder.b.tvLetter.text = row.header
            holder.b.tvCount.text = row.headerCount.toString()
            return
        }
        holder as WordVH
        val w = row.word ?: return
        val ctx = holder.itemView.context
        val b = holder.b

        b.tvWord.text = w.text
        b.tvPinyin.text = w.pinyin
        b.tvTag.text = if (w.tag.isNotBlank()) "${w.categoryLabel} · ${w.tag}" else w.categoryLabel
        b.tvMeaning.text = w.meaning.ifBlank { ctx.getString(R.string.word_meaning_empty) }

        val isExpanded = w.id in expanded
        b.tvMeaning.maxLines = if (isExpanded) Int.MAX_VALUE else 2

        val extra = buildString {
            if (w.example.isNotBlank()) {
                append(ctx.getString(R.string.word_example_label, w.example))
            }
            if (w.source.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(ctx.getString(R.string.word_source_label, w.source))
            }
        }
        // 有例句 / 出处，或释义本身较长，才值得点开
        val canExpand = extra.isNotBlank() || w.meaning.length > 40
        b.tvExtra.text = extra
        b.tvExtra.visibility = if (isExpanded && extra.isNotBlank()) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            if (!canExpand) return@setOnClickListener
            if (!expanded.remove(w.id)) expanded.add(w.id)
            notifyItemChanged(position)
        }
    }

    class HeaderVH(val b: ItemWordHeaderBinding) : RecyclerView.ViewHolder(b.root)

    class WordVH(val b: ItemWordBinding) : RecyclerView.ViewHolder(b.root)
}
