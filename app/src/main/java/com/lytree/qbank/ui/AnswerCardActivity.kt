package com.lytree.qbank.ui

import android.content.Intent
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
 * 答题卡（底部弹层）。对齐参考应用真机抓取（1080x1920 @280dpi）：
 * · 顶部「答题卡」居中标题 + 右上关闭，无图例
 * · 按模块分组，圆形题号网格（40dp）
 * · 格子状态：已答=蓝实心 / 未答=#F6F7FB 实心 / 当前题=蓝实心+淡蓝环；
 *   标记=右上橙点，收藏=下方粉点（对齐真机 answer_card_item_unsure / _collect 角标）
 * · 底部通栏「交卷」按钮（回传给答题页执行交卷）
 */
class AnswerCardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COUNT = "count"
        const val EXTRA_ANSWERED = "answered"
        const val EXTRA_MARKED = "marked"
        const val EXTRA_FAVORITE = "favorite"
        const val EXTRA_GROUPS = "groups"
        const val EXTRA_CURRENT = "current"
        const val EXTRA_JUMP = "jump"
        const val EXTRA_SUBMIT = "submit"
        /** 夜间模式：答题页切了夜间再开答题卡，弹层不能还是白的 */
        const val EXTRA_NIGHT = "night"
    }

    private lateinit var binding: ActivityAnswerCardBinding
    private val adapter = CardAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手机锁竖屏 / 平板跟随旋转（方向锁是 Activity 级的，答题页锁了这里也要跟着锁）
        ScreenAdapt.applyOrientation(this)
        binding = ActivityAnswerCardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val count = intent.getIntExtra(EXTRA_COUNT, 0)
        val answered = intent.getIntArrayExtra(EXTRA_ANSWERED)?.toSet() ?: emptySet()
        val marked = intent.getIntArrayExtra(EXTRA_MARKED)?.toSet() ?: emptySet()
        val favorite = intent.getIntArrayExtra(EXTRA_FAVORITE)?.toSet() ?: emptySet()
        val groups = intent.getStringArrayListExtra(EXTRA_GROUPS) ?: arrayListOf()
        val current = intent.getIntExtra(EXTRA_CURRENT, -1)
        val night = intent.getBooleanExtra(EXTRA_NIGHT, false)
        applyNightMode(night)
        adapter.night = night

        binding.btnClose.setOnClickListener { finish() }
        // 点遮罩关闭
        binding.root.setOnClickListener { finish() }
        binding.sheet.setOnClickListener { /* 吞掉，避免误触关闭 */ }

        // 真机每行 10 个；按实际屏宽自适应，保证格子不小于 44dp。
        // 平板（sw600dp）格子本身放大到 52dp，列数上限也放宽——否则一格 52dp、一行只排 10 列，
        // 800dp 宽的屏上右边会空出一大块。
        val cellDp = (resources.getDimension(R.dimen.fb_card_num_size)
                + resources.getDimension(R.dimen.fb_card_num_pad) * 2) / resources.displayMetrics.density
        val usableDp = resources.configuration.screenWidthDp - 20 // 列表左右各 10dp padding
        val columns = (usableDp / cellDp).toInt().coerceIn(5, 14)

        val lm = GridLayoutManager(this, columns)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isGroup(position)) columns else 1
        }
        binding.list.layoutManager = lm
        binding.list.adapter = adapter

        adapter.onClick = { pos ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_JUMP, pos))
            finish()
        }
        adapter.submit(count, answered, marked, favorite, groups, current)

        binding.btnSubmit.setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_SUBMIT, true))
            finish()
        }

        // edge-to-edge：给弹层底部留出导航栏高度，避免「交卷」被手势条压住
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.sheet) { v, insets ->
            val bottom = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            ).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
    }

    /** 夜间配色：弹层底、标题行、标题与关闭图标跟着答题页一起换 */
    private fun applyNightMode(night: Boolean) {
        if (!night) return
        binding.sheet.setBackgroundResource(R.drawable.bg_fb_sheet_night)
        binding.header.setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.fb_night_card)
        )
        binding.tvCardTitle.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.fb_night_text)
        )
        binding.btnClose.setColorFilter(
            androidx.core.content.ContextCompat.getColor(this, R.color.fb_night_icon)
        )
        // 状态栏压在深色遮罩上：底色同遮罩、图标转白
        window.statusBarColor = androidx.core.content.ContextCompat.getColor(this, R.color.fb_sheet_mask)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
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
    private var current = -1
    var onClick: ((Int) -> Unit)? = null
    /** 夜间模式：未答题号底色与分组标题配色 */
    var night: Boolean = false

    fun isGroup(position: Int) = rows.getOrNull(position) is Row.Group

    fun submit(
        count: Int,
        answered: Set<Int>,
        marked: Set<Int>,
        favorite: Set<Int>,
        groups: List<String>,
        current: Int
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
        this.current = current
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
            is Row.Group -> {
                val label = (holder as GroupVH).label
                label.text = row.label
                label.setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        label.context,
                        if (night) R.color.fb_night_text_meta else R.color.fb_text_group
                    )
                )
            }
            is Row.Num -> {
                val b = (holder as NumVH).binding
                val ctx = b.root.context
                val isCurrent = row.position == current
                b.tvNum.text = (row.position + 1).toString()

                // 底色：当前题 / 已答 → 蓝实心；其余 → 浅灰蓝实心（夜间换深一档）
                b.tvNum.setBackgroundResource(
                    when {
                        isCurrent -> R.drawable.bg_num_current
                        row.answered -> R.drawable.bg_num_answered
                        night -> R.drawable.bg_num_blank_night
                        else -> R.drawable.bg_num_blank
                    }
                )
                b.tvNum.setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        ctx,
                        when {
                            isCurrent || row.answered -> android.R.color.white
                            night -> R.color.fb_night_text_body
                            else -> R.color.fb_card_num_blank_text
                        }
                    )
                )
                b.vMark.visibility = if (row.marked) View.VISIBLE else View.GONE
                b.vFav.visibility = if (row.favorite) View.VISIBLE else View.GONE
                b.root.setOnClickListener { onClick?.invoke(row.position) }
            }
        }
    }
}
