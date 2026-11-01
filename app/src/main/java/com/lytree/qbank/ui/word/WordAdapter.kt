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
 *
 * 另外带一套「多选删除」状态：长按任意词条进入，行前冒出勾选框，点一行就是勾 / 取消；
 * 选中态由本类维护，条数变化通过 [onSelectionChanged] 回调给页面去改顶栏。
 */
class WordAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_WORD = 1

        /** 筛选胶囊的选中态切换（样式在 styles.xml 里定义，运行时只换背景和字色） */
        fun applyChipStyle(tv: TextView, selected: Boolean) {
            tv.setBackgroundResource(
                if (selected) R.drawable.bg_fb_chip_selected else R.drawable.bg_fb_chip
            )
            val color = if (selected) R.color.fb_text_on_primary else R.color.fb_text_secondary
            tv.setTextColor(androidx.core.content.ContextCompat.getColor(tv.context, color))
        }
    }

    private class Row(val header: String? = null, val headerCount: Int = 0, val word: Word? = null)

    private val rows = ArrayList<Row>()

    /** 展开的词条 id（点一行看例句 / 出处），换筛选时清空 */
    private val expanded = HashSet<Long>()

    /** 多选删除：选中的词条 id（LinkedHashSet 保序，删完提示里不用再排） */
    private val selected = LinkedHashSet<Long>()

    private var selectionMode = false

    /** 长按词条（非选择态）：页面据此进入多选 */
    var onLongPress: ((Word) -> Unit)? = null

    /** 选中数变化（0 = 已退出选择态） */
    var onSelectionChanged: ((Int) -> Unit)? = null

    val isSelecting: Boolean get() = selectionMode
    val selectedCount: Int get() = selected.size
    val selectedIds: List<Long> get() = selected.toList()

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
        // 列表整批换了，选中的 id 可能已经不在了，直接退出选择态最稳
        if (selectionMode) {
            selectionMode = false
            selected.clear()
            onSelectionChanged?.invoke(0)
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

    // ---------------- 多选删除 ----------------

    fun startSelection(first: Word) {
        selectionMode = true
        selected.clear()
        if (first.id > 0) selected.add(first.id)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selected.size)
    }

    fun toggleSelection(word: Word) {
        if (!selectionMode || word.id <= 0) return
        if (!selected.remove(word.id)) selected.add(word.id)
        notifyItemChanged(positionOf(word.id))
        onSelectionChanged?.invoke(selected.size)
    }

    /** 全选 / 取消全选：已经全选就清空，否则补全 */
    fun toggleSelectAll() {
        if (!selectionMode) return
        val ids = rows.mapNotNull { it.word?.id }.filter { it > 0 }
        if (ids.isNotEmpty() && selected.size >= ids.size) selected.clear() else selected.addAll(ids)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selected.size)
    }

    fun exitSelection() {
        if (!selectionMode) return
        selectionMode = false
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    private fun positionOf(id: Long): Int {
        rows.forEachIndexed { index, row -> if (row.word?.id == id) return index }
        return -1
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
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
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

        val selecting = selectionMode
        b.cbSelect.visibility = if (selecting) View.VISIBLE else View.GONE
        b.cbSelect.isChecked = selecting && w.id in selected

        val isExpanded = !selecting && w.id in expanded
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
            if (selecting) {
                toggleSelection(w)
            } else if (canExpand) {
                if (!expanded.remove(w.id)) expanded.add(w.id)
                notifyItemChanged(position)
            }
        }
        holder.itemView.setOnLongClickListener {
            if (selecting) toggleSelection(w) else onLongPress?.invoke(w)
            true
        }
    }

    class HeaderVH(val b: ItemWordHeaderBinding) : RecyclerView.ViewHolder(b.root)

    class WordVH(val b: ItemWordBinding) : RecyclerView.ViewHolder(b.root)
}
