package com.lytree.qbank.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.databinding.ActivityAnswerCardBinding
import com.lytree.qbank.databinding.ItemCardNumBinding

/**
 * 答题卡（底部弹层）。对齐粉笔真机抓取：
 * · 顶部「答题卡」标题 + 右上关闭
 * · 按科目分组，圆形题号网格
 * · 格子状态：已答=蓝实心 / 未答=浅灰描边 / 标记=橙描边 / 收藏=粉描边
 * · 底部通栏「交卷」按钮
 */
class AnswerCardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COUNT = "count"
        const val EXTRA_ANSWERED = "answered"
        const val EXTRA_MARKED = "marked"
        const val EXTRA_FAVORITE = "favorite"
        const val EXTRA_GROUPS = "groups"
        const val EXTRA_JUMP = "jump"
    }

    private lateinit var binding: ActivityAnswerCardBinding
    private val adapter = CardAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnswerCardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val count = intent.getIntExtra(EXTRA_COUNT, 0)
        val answered = intent.getIntArrayExtra(EXTRA_ANSWERED)?.toSet() ?: emptySet()
        val marked = intent.getIntArrayExtra(EXTRA_MARKED)?.toSet() ?: emptySet()
        val favorite = intent.getIntArrayExtra(EXTRA_FAVORITE)?.toSet() ?: emptySet()
        val groups = intent.getStringArrayListExtra(EXTRA_GROUPS) ?: arrayListOf()

        binding.btnClose.setOnClickListener { finish() }
        // 点遮罩关闭
        binding.root.setOnClickListener { finish() }
        binding.sheet.setOnClickListener { /* 吞掉，避免误触关闭 */ }

        binding.list.layoutManager = GridLayoutManager(this, 5)
        binding.list.adapter = adapter
        adapter.onClick = { pos ->
            setResult(RESULT_OK, android.content.Intent().putExtra(EXTRA_JUMP, pos))
            finish()
        }
        adapter.submit(count, answered, marked, favorite, groups)

        val un = count - answered.size
        binding.tvLegend.text =
            getString(R.string.card_legend, count, answered.size, un, marked.size)
    }
}

/** 答题卡适配器。按分组标题把题号切成若干「标题 + 网格」的段。 */
class CardAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Group(val label: String) : Row()
        data class Num(
            val position: Int,
            val answered: Boolean,
            val marked: Boolean,
            val favorite: Boolean
        ) : Row()
    }

    private var rows: List<Row> = emptyList()
    var onClick: ((Int) -> Unit)? = null

    fun submit(
        count: Int,
        answered: Set<Int>,
        marked: Set<Int>,
        favorite: Set<Int>,
        groups: List<String>
    ) {
        val out = ArrayList<Row>()
        var lastGroup: String? = null
        for (i in 0 until count) {
            val g = groups.getOrNull(i) ?: "未分类"
            if (g != lastGroup) {
                out.add(Row.Group(g))
                lastGroup = g
            }
            out.add(
                Row.Num(
                    position = i,
                    answered = i in answered,
                    marked = i in marked,
                    favorite = i in favorite
                )
            )
        }
        rows = out
        notifyDataSetChanged()
    }

    class GroupVH(val label: android.widget.TextView) : RecyclerView.ViewHolder(label)
    class NumVH(val binding: ItemCardNumBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = if (rows[position] is Row.Group) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            val tv = inflater.inflate(R.layout.item_card_group, parent, false) as android.widget.TextView
            GroupVH(tv)
        } else {
            NumVH(ItemCardNumBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Group -> (holder as GroupVH).label.text = row.label
            is Row.Num -> {
                val b = (holder as NumVH).binding
                val ctx = b.root.context
                b.tvNum.text = (row.position + 1).toString()

                // 状态优先级：已答 > 标记 > 收藏 > 未答
                val bg = when {
                    row.answered -> R.drawable.bg_num_answered
                    row.marked -> R.drawable.bg_num_marked
                    row.favorite -> R.drawable.bg_num_favorite
                    else -> R.drawable.bg_num_blank
                }
                b.tvNum.setBackgroundResource(bg)
                b.tvNum.setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        ctx,
                        if (row.answered) android.R.color.white else R.color.fb_card_num_blank_text
                    )
                )
                b.root.setOnClickListener { onClick?.invoke(row.position) }
            }
        }
    }
}
