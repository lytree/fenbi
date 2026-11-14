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
import com.lytree.qbank.data.model.MindMapPage
import com.lytree.qbank.data.model.ReciteSubject
import com.lytree.qbank.databinding.FragmentMindmapBinding
import com.lytree.qbank.databinding.ItemMindmapPageBinding

/**
 * 思维导图浏览页（政治背诵顶栏的「导图」入口）。
 *
 * 原书《政治理论思维导图大盘点》是扫描版导图，这里按书页还原成层级大纲：
 * 中心主题（level 0）→ 一级分支（1）→ 逐层要点（2..4）。一页一张卡，点标题展开。
 *
 * · 不做填空：导图的内容是整句原文，用于通读 / 朗读，跟政治背诵卡的「____ 自测」互补
 * · 页面下半部的「内涵连线」做成配对卡：左提法右内涵，点选配对，错了给一次提示
 * · 展开状态只存内存，退出重进回到全部收起
 */
class MindMapFragment : Fragment() {

    private var _binding: FragmentMindmapBinding? = null
    private val binding get() = _binding!!

    private lateinit var catalog: MindMapCatalog
    private lateinit var adapter: PageAdapter

    /** 已展开的主题 id；收起即移除 */
    private val expanded = HashSet<String>()

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

        binding.tvIntro.text = getString(R.string.mindmap_intro_fmt, catalog.pages.size)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        adapter = PageAdapter()
        binding.list.adapter = adapter
        binding.list.isVisible = catalog.pages.isNotEmpty()
        binding.emptyText.isVisible = catalog.pages.isEmpty()
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

    private fun toggle(page: MindMapPage) {
        if (!expanded.remove(page.id)) expanded.add(page.id)
        val index = catalog.pages.indexOfFirst { it.id == page.id }
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    // ---------------------------------------------------------------- 列表

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {

        override fun getItemCount(): Int = catalog.pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val inflater = LayoutInflater.from(parent.context)
            return PageHolder(ItemMindmapPageBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.bind(catalog.pages[position])
        }
    }

    private inner class PageHolder(
        private val item: ItemMindmapPageBinding
    ) : RecyclerView.ViewHolder(item.root) {

        fun bind(page: MindMapPage) {
            item.tvIndex.text = getString(R.string.mindmap_index_fmt, page.page)
            item.tvName.text = page.name
            item.tvMeta.text = if (page.pairs.isEmpty()) {
                getString(R.string.mindmap_meta_fmt, page.branchCount, page.pointCount)
            } else {
                getString(
                    R.string.mindmap_meta_pairs_fmt,
                    page.branchCount, page.pointCount, page.pairs.size
                )
            }

            val open = page.id in expanded
            item.tvArrow.text = if (open) "▾" else "▸"
            item.body.isVisible = open
            item.body.removeAllViews()
            if (open) fillBody(page)
            item.header.setOnClickListener { toggle(page) }
        }

        private fun fillBody(page: MindMapPage) {
            page.nodes.forEach { item.body.addView(nodeView(it)) }
            if (page.pairs.isNotEmpty()) item.body.addView(pairRow(page))
        }
    }

    // ---------------------------------------------------------------- 大纲行

    /** 按 level 缩进：0 主题标签 / 1 分支加粗带竖条 / 2..4 逐层缩进，3 起加圆点 */
    private fun nodeView(node: MindMapNode): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        when (node.level) {
            0 -> {
                val tag = TextView(context).apply {
                    text = node.text
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.fb_text_tag))
                    setTextColor(ContextCompat.getColor(context, R.color.fb_primary))
                    setTypeface(null, Typeface.BOLD)
                    includeFontPadding = false
                    setPadding(dp(9), dp(3), dp(9), dp(3))
                    background = rounded(ContextCompat.getColor(context, R.color.fb_primary_soft), 5)
                }
                lp.topMargin = dp(12)
                row.addView(tag, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                row.layoutParams = lp
                return row
            }
            1 -> {
                row.addView(
                    View(context).apply {
                        setBackgroundColor(ContextCompat.getColor(context, R.color.fb_primary))
                    },
                    LinearLayout.LayoutParams(dp(3), dp(14)).apply { marginEnd = dp(8) }
                )
                lp.topMargin = dp(12)
            }
            else -> lp.topMargin = dp(6)
        }

        val indent = when (node.level) {
            1 -> 0
            2 -> dp(14)
            3 -> dp(32)
            else -> dp(48)
        }
        val sizeRes = when (node.level) {
            1 -> R.dimen.fb_text_item_title
            2 -> R.dimen.fb_text_body_size
            else -> R.dimen.fb_text_body_sm
        }
        val colorRes = when (node.level) {
            1 -> R.color.fb_text_title
            2 -> R.color.fb_text_primary
            3 -> R.color.fb_text_secondary
            else -> R.color.fb_text_meta
        }
        val text = TextView(context).apply {
            this.text = if (node.level >= 3) "· ${node.text}" else node.text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(sizeRes))
            setTextColor(ContextCompat.getColor(context, colorRes))
            setLineSpacing(dp(4).toFloat(), 1f)
            includeFontPadding = false
            if (node.level == 1) setTypeface(null, Typeface.BOLD)
            setPadding(indent, 0, 0, 0)
        }
        row.addView(
            text,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.layoutParams = lp
        return row
    }

    /** 「内涵连线 · 14 组   开始配对」 */
    private fun pairRow(page: MindMapPage): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        row.addView(
            TextView(context).apply {
                text = getString(R.string.mindmap_pair_section) + " · " +
                    getString(R.string.mindmap_pair_count_fmt, page.pairs.size)
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
                setOnClickListener { showPairs(page) }
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
    private fun showPairs(page: MindMapPage) {
        val context = requireContext()
        val pairs = page.pairs
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
}
