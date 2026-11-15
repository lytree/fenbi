package com.lytree.qbank.ui.recite

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.MindMapRepository
import com.lytree.qbank.data.model.MindMapCatalog
import com.lytree.qbank.data.model.MindMapNode
import com.lytree.qbank.data.model.MindMapSection
import com.lytree.qbank.data.model.MindMapTopic
import com.lytree.qbank.data.model.ReciteSubject
import com.lytree.qbank.databinding.FragmentMindmapBinding
import com.lytree.qbank.databinding.ItemMindmapPageBinding
import com.lytree.qbank.databinding.ItemMindmapSectionBinding

/**
 * 思维导图浏览页（政治背诵顶栏的「导图」入口）。
 *
 * 原书《政治理论思维导图大盘点》是扫描版导图，这里按坐标还原的树呈现：
 * 顶端按书里的 10 个板块筛选，列表一个板块一段，一段里一页书一张卡；
 * 卡片展开后是「中心主题 → 分支 → 要点」的树，有子节点的行可点着折叠，
 * 默认只摊开前三层，免得一页 40 多条要点一次铺满屏。
 *
 * · 不做填空：导图的内容是整句原文，用于通读 / 朗读，跟政治背诵卡的「____ 自测」互补
 * · 页面下半部的「内涵连线」做成配对卡：左提法右内涵，点选配对，错了给一次提示
 * · 展开状态只存内存，退出重进回到默认收起
 */
class MindMapFragment : Fragment() {

    private var _binding: FragmentMindmapBinding? = null
    private val binding get() = _binding!!

    private lateinit var catalog: MindMapCatalog
    private lateinit var adapter: RowAdapter

    /** 当前筛的板块 id；null = 全部板块 */
    private var activeSection: String? = null

    /** 已展开的主题卡 id */
    private val openTopics = HashSet<String>()

    /** 树节点展开状态的覆盖：没点过的按深度决定，点过就按点过的来。key = "主题id#路径" */
    private val nodeOpen = HashMap<String, Boolean>()

    private val rows = ArrayList<Row>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMindmapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        catalog = MindMapRepository.catalog(requireContext())

        binding.titleBar.setTitle(getString(R.string.mindmap_title))
        binding.titleBar.showBack(true)
        binding.titleBar.onBack = { openRecite() }
        // 系统返回键同样回到政治背诵，别一按就退出 App
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = openRecite()
            }
        )

        val empty = catalog.isEmpty
        binding.tvIntro.text = if (empty) {
            ""
        } else {
            getString(R.string.mindmap_intro_fmt, catalog.sections.size, catalog.topicCount)
        }
        binding.sectionBar.isVisible = !empty

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        adapter = RowAdapter()
        binding.list.adapter = adapter
        binding.list.isVisible = !empty
        binding.emptyText.isVisible = empty

        if (!empty) {
            buildChips()
            rebuildRows()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 回政治背诵：本页只是它顶栏里开出来的二级页 */
    private fun openRecite() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, ReciteFragment.newInstance(ReciteSubject.POLITICS))
            .commit()
    }

    // ---------------------------------------------------------------- 板块筛选

    private fun buildChips() {
        val bar = binding.sectionChips
        bar.removeAllViews()
        bar.addView(
            chip(
                text = getString(R.string.mindmap_section_all, catalog.topicCount),
                on = activeSection == null,
            ) { selectSection(null) }
        )
        for (section in catalog.sections) {
            bar.addView(
                chip(
                    text = getString(R.string.mindmap_section_chip_fmt, section.name, section.topics.size),
                    on = activeSection == section.id,
                ) { selectSection(section.id) }
            )
        }
    }

    private fun chip(text: String, on: Boolean, onClick: () -> Unit): View {
        val ctx = requireContext()
        val view = TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.fb_text_tag))
            includeFontPadding = false
            setPadding(dp(11), dp(6), dp(11), dp(6))
            isClickable = true
            isFocusable = true
            setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (on) R.color.fb_primary else R.color.fb_text_secondary
                )
            )
            setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            background = rounded(
                ContextCompat.getColor(
                    ctx,
                    if (on) R.color.fb_primary_soft else R.color.fb_card_bg
                ),
                14
            )
            setOnClickListener { onClick() }
        }
        return view.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }
    }

    private fun selectSection(id: String?) {
        if (activeSection == id) return
        activeSection = id
        buildChips()
        rebuildRows()
        binding.list.scrollToPosition(0)
    }

    // ---------------------------------------------------------------- 列表

    private sealed interface Row {
        data class Head(val section: MindMapSection) : Row
        data class Card(val section: MindMapSection, val topic: MindMapTopic) : Row
    }

    private fun rebuildRows() {
        rows.clear()
        val sections = catalog.sections.filter { activeSection == null || it.id == activeSection }
        for (section in sections) {
            rows.add(Row.Head(section))
            for (topic in section.topics) rows.add(Row.Card(section, topic))
        }
        adapter.notifyDataSetChanged()
    }

    private fun toggleTopic(topic: MindMapTopic) {
        if (!openTopics.remove(topic.id)) openTopics.add(topic.id)
        val index = rows.indexOfFirst { it is Row.Card && it.topic.id == topic.id }
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    private fun toggleNode(topic: MindMapTopic, key: String, depth: Int) {
        val current = nodeOpen[key] ?: (depth < DEFAULT_OPEN_DEPTH)
        nodeOpen[key] = !current
        val index = rows.indexOfFirst { it is Row.Card && it.topic.id == topic.id }
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Head -> TYPE_HEAD
            is Row.Card -> TYPE_CARD
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEAD) {
                SectionHolder(ItemMindmapSectionBinding.inflate(inflater, parent, false))
            } else {
                CardHolder(ItemMindmapPageBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Head -> (holder as SectionHolder).bind(row.section)
                is Row.Card -> (holder as CardHolder).bind(row.topic)
            }
        }
    }

    private inner class SectionHolder(
        private val item: ItemMindmapSectionBinding
    ) : RecyclerView.ViewHolder(item.root) {

        fun bind(section: MindMapSection) {
            item.tvSectionName.text = section.name
            item.tvSectionMeta.text = getString(
                R.string.mindmap_section_meta_fmt, section.topics.size, section.nodeCount
            )
        }
    }

    private inner class CardHolder(
        private val item: ItemMindmapPageBinding
    ) : RecyclerView.ViewHolder(item.root) {

        fun bind(topic: MindMapTopic) {
            // 书页标签是「书页 02」，列表上只要那两位数字；新建的主题没有号，留个占位
            item.tvIndex.text = topic.label.filter { it.isDigit() }.takeLast(2).ifEmpty { "·" }
            item.tvName.text = topic.name
            item.tvMeta.text = if (topic.pairs.isEmpty()) {
                getString(R.string.mindmap_meta_fmt, topic.branchCount, topic.nodeCount)
            } else {
                getString(
                    R.string.mindmap_meta_pairs_fmt,
                    topic.branchCount, topic.nodeCount, topic.pairs.size
                )
            }

            val open = topic.id in openTopics
            item.tvArrow.text = if (open) "▾" else "▸"
            item.body.isVisible = open
            item.body.removeAllViews()
            if (open) fillBody(topic)
            item.header.setOnClickListener { toggleTopic(topic) }
        }

        private fun fillBody(topic: MindMapTopic) {
            topic.nodes.forEachIndexed { index, node ->
                addTreeRow(item.body, topic, node, "$index", 0)
            }
            if (topic.pairs.isNotEmpty()) item.body.addView(pairRow(topic))
        }
    }

    // ---------------------------------------------------------------- 树

    /**
     * 递归把节点加成行。折叠的分支不下钻，所以一次只渲染看得见的那些行——
     * 一页 40 多条要点全渲染会让滚动发滞。
     */
    private fun addTreeRow(
        container: LinearLayout,
        topic: MindMapTopic,
        node: MindMapNode,
        path: String,
        depth: Int,
    ) {
        val key = "${topic.id}#$path"
        val hasKids = node.hasChildren
        val open = hasKids && (nodeOpen[key] ?: (depth < DEFAULT_OPEN_DEPTH))
        container.addView(nodeRow(node, depth, hasKids, open) { toggleNode(topic, key, depth) })
        if (!open) return
        node.children.forEachIndexed { index, child ->
            addTreeRow(container, topic, child, "$path.$index", depth + 1)
        }
    }

    /** 按深度缩进：0 中心主题（色块）/ 1 分支加粗 / 2 正文 / 3 起次要色带圆点 */
    private fun nodeRow(
        node: MindMapNode,
        depth: Int,
        hasKids: Boolean,
        open: Boolean,
        onToggle: () -> Unit,
    ): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val indent = when (depth) {
            0 -> 0
            1 -> dp(12)
            2 -> dp(26)
            3 -> dp(40)
            else -> dp(54)
        }
        if (indent > 0) {
            row.addView(View(ctx), LinearLayout.LayoutParams(indent, 1))
        }
        if (hasKids) {
            row.addView(
                TextView(ctx).apply {
                    text = if (open) "▾" else "▸"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.fb_text_tag))
                    setTextColor(ContextCompat.getColor(ctx, R.color.fb_text_meta))
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(5) }
            )
        }

        val sizeRes = when (depth) {
            0 -> R.dimen.fb_text_item_title
            1 -> R.dimen.fb_text_body_size
            2 -> R.dimen.fb_text_body_size
            else -> R.dimen.fb_text_body_sm
        }
        val colorRes = when (depth) {
            0 -> R.color.fb_primary
            1 -> R.color.fb_text_title
            2 -> R.color.fb_text_primary
            else -> R.color.fb_text_secondary
        }
        val body = TextView(ctx).apply {
            text = if (depth >= 3) "· ${node.text}" else node.text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(sizeRes))
            setTextColor(ContextCompat.getColor(ctx, colorRes))
            setLineSpacing(dp(4).toFloat(), 1f)
            includeFontPadding = false
            if (depth <= 1) setTypeface(null, Typeface.BOLD)
            if (depth == 0) {
                setPadding(dp(9), dp(3), dp(9), dp(3))
                background = rounded(ContextCompat.getColor(ctx, R.color.fb_primary_soft), 5)
            }
        }
        row.addView(
            body,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = if (depth <= 1) dp(10) else dp(5) }

        if (hasKids) {
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { onToggle() }
        }
        return row
    }

    /** 「内涵连线 · 14 组   开始配对」 */
    private fun pairRow(topic: MindMapTopic): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        row.addView(
            TextView(context).apply {
                text = getString(R.string.mindmap_pair_section) + " · " +
                    getString(R.string.mindmap_pair_count_fmt, topic.pairs.size)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.fb_text_body_sm))
                setTextColor(ContextCompat.getColor(context, R.color.fb_text_secondary))
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            TextView(context).apply {
                text = getString(R.string.mindmap_pair_start)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.fb_text_body_sm))
                setTextColor(ContextCompat.getColor(context, R.color.fb_primary))
                setTypeface(null, Typeface.BOLD)
                includeFontPadding = false
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = ripple()
                isClickable = true
                setOnClickListener { showPairs(topic) }
            }
        )
        return row
    }

    // ---------------------------------------------------------------- 配对自测

    /**
     * 配对卡：左列提法（保持原书顺序）、右列内涵（每次打开都打乱）。
     * 先点左再点右：配对正确两边变绿并锁定；错了闪一下红再让人重选。
     * 期望答案就是资产里 pairs 的对应关系，不含序号，展示时也不给提示。
     */
    private fun showPairs(topic: MindMapTopic) {
        val context = requireContext()
        val pairs = topic.pairs
        val total = pairs.size
        val matched = BooleanArray(total)
        var selectedLeft = -1
        var matchedCount = 0

        val bgNormal = ContextCompat.getColor(context, R.color.fb_page_bg)
        val bgSelected = ContextCompat.getColor(context, R.color.fb_primary_soft)
        val bgRight = ContextCompat.getColor(context, R.color.fb_success_bg)
        val bgWrong = ContextCompat.getColor(context, R.color.fb_danger_bg)
        val textNormal = ContextCompat.getColor(context, R.color.fb_text_primary)
        val textSelected = ContextCompat.getColor(context, R.color.fb_primary)
        val textRight = ContextCompat.getColor(context, R.color.fb_success)
        val textWrong = ContextCompat.getColor(context, R.color.fb_danger)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(4))
        }
        val progress = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.fb_text_body_sm))
            setTextColor(ContextCompat.getColor(context, R.color.fb_text_secondary))
            includeFontPadding = false
            text = getString(R.string.mindmap_pair_progress, 0, total)
        }
        root.addView(progress)
        root.addView(
            TextView(context).apply {
                text = getString(R.string.mindmap_pair_hint)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.fb_text_tag))
                setTextColor(ContextCompat.getColor(context, R.color.fb_text_meta))
                includeFontPadding = false
                setPadding(0, dp(4), 0, dp(10))
            }
        )

        val columns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val leftColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rightColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        columns.addView(leftColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(8) })
        columns.addView(rightColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(8) })

        leftColumn.addView(columnTitle(getString(R.string.mindmap_pair_left)))
        rightColumn.addView(columnTitle(getString(R.string.mindmap_pair_right)))

        val leftViews = ArrayList<TextView>(total)
        val rightOrder = pairs.indices.shuffled()
        val rightViews = ArrayList<TextView>(total)
        // 右列第 pos 个位置放的是原书第 rightOriginal[pos] 组的内涵
        val rightOriginal = ArrayList<Int>(total)

        fun paint(view: TextView, bg: Int, textColor: Int) {
            view.background = rounded(bg, 6)
            view.setTextColor(textColor)
        }

        fun clearPending() {
            for (i in 0 until total) {
                if (!matched[i]) paint(leftViews[i], bgNormal, textNormal)
                if (!matched[rightOriginal[i]]) paint(rightViews[i], bgNormal, textNormal)
            }
            selectedLeft = -1
        }

        for (i in 0 until total) {
            val view = pairItem(pairs[i].left)
            leftViews.add(view)
            view.setOnClickListener {
                if (matched[i]) return@setOnClickListener
                clearPending()
                selectedLeft = i
                paint(view, bgSelected, textSelected)
            }
            leftColumn.addView(view)
        }
        for (position in rightOrder.indices) {
            val original = rightOrder[position]
            val view = pairItem(pairs[original].right)
            rightViews.add(view)
            rightOriginal.add(original)
            view.setOnClickListener {
                if (matched[original] || selectedLeft < 0) return@setOnClickListener
                val leftIndex = selectedLeft
                if (leftIndex == original) {
                    matched[original] = true
                    matchedCount++
                    paint(leftViews[leftIndex], bgRight, textRight)
                    paint(view, bgRight, textRight)
                    leftViews[leftIndex].isClickable = false
                    view.isClickable = false
                    selectedLeft = -1
                    progress.text = if (matchedCount == total) {
                        getString(R.string.mindmap_pair_done, total)
                    } else {
                        getString(R.string.mindmap_pair_progress, matchedCount, total)
                    }
                } else {
                    paint(leftViews[leftIndex], bgWrong, textWrong)
                    paint(view, bgWrong, textWrong)
                    selectedLeft = -1
                    view.postDelayed({ clearPending() }, 650)
                }
            }
            rightColumn.addView(view)
        }

        root.addView(columns)

        val scroll = ScrollView(context).apply {
            addView(root)
            isVerticalScrollBarEnabled = true
        }
        AlertDialog.Builder(context)
            .setTitle(getString(R.string.mindmap_pair_section))
            .setView(scroll)
            .setPositiveButton(R.string.mindmap_pair_close, null)
            .show()
    }

    private fun columnTitle(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.fb_text_tag))
        setTextColor(ContextCompat.getColor(requireContext(), R.color.fb_text_meta))
        includeFontPadding = false
        setPadding(dp(8), 0, dp(8), dp(6))
    }

    private fun pairItem(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.fb_text_body_sm))
        setTextColor(ContextCompat.getColor(requireContext(), R.color.fb_text_primary))
        setLineSpacing(dp(3).toFloat(), 1f)
        includeFontPadding = false
        setPadding(dp(8), dp(7), dp(8), dp(7))
        background = rounded(ContextCompat.getColor(requireContext(), R.color.fb_page_bg), 6)
        isClickable = true
        isFocusable = true
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(6)
        layoutParams = lp
    }

    // ---------------------------------------------------------------- 小工具

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun ripple() = run {
        val out = TypedValue()
        requireContext().theme.resolveAttribute(
            android.R.attr.selectableItemBackground, out, true
        )
        ContextCompat.getDrawable(requireContext(), out.resourceId)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val TYPE_HEAD = 0
        const val TYPE_CARD = 1

        /** 默认摊开到第几层：0 是中心主题，1 是一级分支的子节点 */
        const val DEFAULT_OPEN_DEPTH = 2
    }
}
