package com.lytree.qbank.ui.recite

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.MindMapRepository
import com.lytree.qbank.data.model.MindMapCatalog
import com.lytree.qbank.data.model.MindMapSection
import com.lytree.qbank.data.model.MindMapTopic
import com.lytree.qbank.data.model.ReciteSubject
import com.lytree.qbank.databinding.FragmentMindmapBinding
import com.lytree.qbank.databinding.ItemMindmapPageBinding
import com.lytree.qbank.databinding.ItemMindmapSectionBinding
import com.lytree.qbank.ui.LibrarySwitch
import com.lytree.qbank.ui.word.WordListFragment

/**
 * 思维导图列表页（政治背诵顶栏的「导图」入口）。
 *
 * 页面骨架与词库 / 政治 / 常识一致：页标题 + 三段切换（高亮「政治」）+ 一行板块胶囊，
 * 所以从这一页能直接切到另外三段，不用先退回政治。
 *
 * 这一页只做「挑主题」：顶端按书里的 10 个板块筛选，列表一个板块一段，
 * 一段里一页书一张卡。点卡片进 [MindMapGraphDialog] 看图形化的导图
 * （节点 + 连线、双指缩放、点节点折叠）——这里不再把层级拍成缩进大纲，
 * 一页 60 多条要点平铺成行既看不出结构，也读不完。
 *
 * · 卡片上给的信息：书页号、章节名、分支数 / 要点数 /（有的话）连线组数
 * · 「内涵连线」不挂在列表上，进导图页后单独配对自测
 * · 板块筛选跟着上次选的那一个（落库），退出重进不用从头再筛
 */
class MindMapFragment : Fragment() {

    private var _binding: FragmentMindmapBinding? = null
    private val binding get() = _binding!!

    private lateinit var catalog: MindMapCatalog
    private lateinit var adapter: RowAdapter

    /** 当前筛的板块 id；null = 全部板块 */
    private var activeSection: String? = null

    /** 胶囊视图与它对应的板块 id：切筛选时只改选中态，不整排重建 */
    private val chipViews = ArrayList<Pair<String?, TextView>>()

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

        binding.titleBar.usePageTitleStyle()
        binding.titleBar.setTitle(getString(R.string.mindmap_title))
        // 顶栏返回键：本页是从大纲页「鸟瞰导图」开出来的二级页，切回政治大纲
        binding.titleBar.showBack(true)
        binding.titleBar.onBack = { backToOutline() }
        // 系统返回键同样回政治背诵：别一按就退出 App
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // 先关掉自己再换页：否则事务排队期间又按一次会连着导航两次
                    isEnabled = false
                    backToOutline()
                }
            }
        )

        // 三段切换与词库 / 政治 / 常识同一套：导图算政治这一段的二级页
        LibrarySwitch.bind(
            binding.librarySwitch,
            LibrarySwitch.PAGE_POLITICS,
            onWords = { openWords() },
            onPolitics = { openRecite() },
            onCommon = { openRecite(ReciteSubject.COMMON) }
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
            // 上次筛的板块还在就沿用；资产更新后板块没了就退回「全部」
            activeSection = MindMapRepository.sectionFilter(requireContext())
                ?.takeIf { id -> catalog.sections.any { it.id == id } }
            buildChips()
            rebuildRows()
            // 布局完才有宽度，等一帧再把选中的胶囊滚进视野
            binding.sectionBar.post { if (_binding != null) revealSelectedChip(smooth = false) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 回政治大纲：本页是从大纲页「鸟瞰导图」开出来的二级页 */
    private fun backToOutline() {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, ReciteOutlineFragment())
            .commit()
    }

    private fun openRecite(subject: ReciteSubject = ReciteSubject.POLITICS) {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        val page = if (subject == ReciteSubject.POLITICS) {
            ReciteOutlineFragment()
        } else {
            ReciteFragment.newInstance(subject)
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, page)
            .commit()
    }

    private fun openWords() {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, WordListFragment())
            .commit()
    }

    // ---------------------------------------------------------------- 板块筛选

    private fun buildChips() {
        val bar = binding.sectionChips
        bar.removeAllViews()
        chipViews.clear()
        addChip(
            bar,
            null,
            getString(R.string.mindmap_section_all, catalog.topicCount),
        )
        for (section in catalog.sections) {
            addChip(
                bar,
                section.id,
                getString(R.string.mindmap_section_chip_fmt, section.name, section.topics.size),
            )
        }
    }

    private fun addChip(bar: LinearLayout, id: String?, text: String) {
        val view = chip(text, id == activeSection) { selectSection(id) }
        bar.addView(view)
        chipViews.add(id to view)
    }

    /** 板块胶囊：与词库 / 背诵页筛选行同一套（30dp 高、浅底 / 主蓝实心） */
    private fun chip(text: String, on: Boolean, onClick: () -> Unit): TextView {
        val ctx = requireContext()
        val view = TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.fb_text_item_sub))
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        styleChip(view, on)
        return view.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                resources.getDimensionPixelSize(R.dimen.fb_chip_h)
            ).apply { marginEnd = dp(6) }
        }
    }

    /** 胶囊两态；切筛选时只动这一下，不重建整排视图 */
    private fun styleChip(view: TextView, on: Boolean) {
        val ctx = requireContext()
        view.setBackgroundResource(
            if (on) R.drawable.bg_fb_chip_selected else R.drawable.bg_fb_chip
        )
        view.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (on) R.color.fb_text_on_primary else R.color.fb_text_secondary
            )
        )
        view.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        view.isSelected = on
    }

    private fun selectSection(id: String?) {
        if (activeSection == id) return
        activeSection = id
        MindMapRepository.setSectionFilter(requireContext(), id)
        // 只更新选中态：整排重建会让横滑位置和焦点一起丢
        chipViews.forEach { (chipId, view) -> styleChip(view, chipId == id) }
        revealSelectedChip(smooth = true)
        rebuildRows()
        binding.list.smoothScrollToPosition(0)
    }

    /** 让当前选中的胶囊滑进可视区（横滑条可能已经滚到别的板块） */
    private fun revealSelectedChip(smooth: Boolean) {
        val target = chipViews.firstOrNull { it.first == activeSection }?.second ?: return
        val bar = binding.sectionBar
        val viewport = bar.width
        if (viewport == 0) return
        val targetScroll = when {
            target.left < bar.scrollX -> target.left - dp(16)
            target.right > bar.scrollX + viewport -> target.right - viewport + dp(16)
            else -> return
        }.coerceAtLeast(0)
        if (smooth) bar.smoothScrollTo(targetScroll, 0) else bar.scrollTo(targetScroll, 0)
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
            item.header.setOnClickListener {
                MindMapGraphDialog.show(parentFragmentManager, topic.id)
            }
        }
    }

    // ---------------------------------------------------------------- 小工具

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val TYPE_HEAD = 0
        const val TYPE_CARD = 1
    }
}
