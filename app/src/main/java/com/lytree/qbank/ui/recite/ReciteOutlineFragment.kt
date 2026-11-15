package com.lytree.qbank.ui.recite

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.ReciteLibraryRepository
import com.lytree.qbank.data.ReciteRepository
import com.lytree.qbank.data.model.LibraryItem
import com.lytree.qbank.data.model.ReciteLibrary
import com.lytree.qbank.data.model.ReciteSubject
import com.lytree.qbank.databinding.FragmentReciteOutlineBinding
import com.lytree.qbank.databinding.ItemOutlineLineBinding
import com.lytree.qbank.databinding.ItemOutlineSourceBinding
import com.lytree.qbank.databinding.ItemOutlineTagBinding
import com.lytree.qbank.ui.LibrarySwitch
import com.lytree.qbank.ui.word.WordListFragment

/**
 * 政治理论大纲页——「政治」这一段的主页。
 *
 * 原来的政治有两套内容、两个入口：考点卡（卡片流）和思维导图（图形画布）。现在内容合成
 * 一份库（`assets/recite_library.json`），页面也只剩这一棵大纲：
 *
 * ```
 * ▾ 新思想                                   128 / 550
 *     超格 · 政治理论考情介绍+新思想导论        ← 来源小标题，只是视觉分段
 *     习近平新时代…同____相结合的重大成果        ○   ← 点正文揭示答案，点圆点标记已背
 *     ▍中国式现代化（导图主题）
 *        · 是人口规模巨大的现代化               ●   ← 导图行按 depth 缩进
 * ```
 *
 * 设计口径：
 * * **一级分支 = 内容标签**，板块 / 章节那两层分组已在资产里取消，别在这里加回来；
 * * **来源只做视觉分段**（灰色小字 + 组间空隙），不可折叠；
 * * 点正文只揭示答案、**不改背诵状态**，已背用右侧 40dp 圆点单独点——读的时候不会误触；
 * * 顶栏睁眼图标 = 遮答预览（默认关，打开才遮挖空、点行揭示）；
 * * 折叠状态、已背进度、字号都落库，切到词库再回来还是原样。
 */
class ReciteOutlineFragment : Fragment() {

    private var _binding: FragmentReciteOutlineBinding? = null
    private val binding get() = _binding!!

    private lateinit var library: ReciteLibrary
    private lateinit var adapter: RowAdapter
    private lateinit var modeAction: ImageView
    private lateinit var fontAction: TextView

    private val rows = ArrayList<Row>()

    /** 已背条目 id（卡片 + 导图行一起算），落库 `lib_done` */
    private var done: Set<String> = emptySet()

    /** 本轮临时揭示的条目：退出即清空，不落库 */
    private val revealed = HashSet<String>()

    /** 折叠起来的标签分支，落库 `lib_fold` */
    private val folded = HashSet<String>()

    private var fontLevel = 0
    /** 遮答预览：默认关。大纲页是「读」的地方，一进来就满屏下划线没法读 */
    private var blind = false
    private var onlyUnmastered = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReciteOutlineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        library = ReciteLibraryRepository.library(context)
        done = ReciteLibraryRepository.doneIds(context)
        fontLevel = ReciteRepository.fontLevel(context)
        blind = ReciteLibraryRepository.blind(context)
        onlyUnmastered = ReciteLibraryRepository.onlyUnmastered(context)
        folded += ReciteLibraryRepository.foldedTags(context) ?: defaultFolded()

        binding.titleBar.usePageTitleStyle()
        binding.titleBar.setTitle(
            library.title.ifBlank { getString(R.string.title_recite_politics) }
        )

        // 睁眼图标 = 遮答预览：点了才把挖空遮起来，默认整篇可读
        modeAction = binding.titleBar.addIconAction(
            R.drawable.ic_recite_mode,
            getString(R.string.lib_blind)
        ) { setBlind(!blind) }
        fontAction = binding.titleBar.addTextAction("") { cycleFont() }
        fontAction.setTextColor(ContextCompat.getColor(context, R.color.fb_text_secondary))
        // addIconAction 的回调不带视图参数，先用空回调拿下这颗图标，再自己绑一次当菜单锚点
        val more = binding.titleBar.addIconAction(
            R.drawable.ic_fb_more,
            getString(R.string.lib_more)
        ) {}
        more.setOnClickListener { showMore(more) }

        LibrarySwitch.bind(
            binding.librarySwitch,
            LibrarySwitch.PAGE_POLITICS,
            onWords = { openWords() },
            onPolitics = { /* 已在本段 */ },
            onCommon = { openRecite(ReciteSubject.COMMON) },
        )

        binding.chipUnmastered.setOnClickListener { setOnlyUnmastered(!onlyUnmastered) }

        binding.list.layoutManager = LinearLayoutManager(context)
        // 揭示答案会改行高，默认动画会抖一下；大纲页的阅读节奏比动画重要
        binding.list.itemAnimator = null
        adapter = RowAdapter()
        binding.list.adapter = adapter

        updateModeAction()
        updateFontAction()
        renderProgress()
        renderChip()
        rebuildRows()
        // 回来时落在上次看到的那一行：大纲一支展开就是几百条，从头顶重来很难受
        val last = ReciteLibraryRepository.lastScroll(context)
        if (last in rows.indices) binding.list.scrollToPosition(last)
        binding.emptyText.isVisible = library.isEmpty
        binding.list.isVisible = !library.isEmpty
    }

    override fun onPause() {
        super.onPause()
        val context = context ?: return
        ReciteLibraryRepository.saveFoldedTags(context, folded)
        val list = _binding?.list ?: return
        val position = (list.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: -1
        if (position >= 0) ReciteLibraryRepository.rememberScroll(context, position)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------------------------------------------------------------- 页面切换

    private fun openWords() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, WordListFragment())
            .commit()
    }

    private fun openRecite(subject: ReciteSubject) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, ReciteFragment.newInstance(subject))
            .commit()
    }

    /** 鸟瞰：原来的图形导图，退出主路径后从这里进 */
    private fun openGraph() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, MindMapFragment())
            .commit()
    }

    /** 聚焦背诵：范围由背诵页按「同标签同来源 / 同主题」自己算，这里只给起始那条 */
    private fun openDrill(anchorId: String) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, ReciteDrillFragment.newInstance(anchorId))
            .commit()
    }

    /** 继续上次：`lib_last` 记的是上次切到的那条，找不到（资产重建过）就提示一句 */
    private fun continueDrill() {
        val id = ReciteLibraryRepository.lastDrill(requireContext())
        val exists = id != null && library.items.any { it.id == id }
        if (id == null || !exists) {
            toast(R.string.drill_last_none)
            return
        }
        openDrill(id)
    }

    // ---------------------------------------------------------------- 行数据

    private sealed interface Row {
        data class Tag(val tagId: String, val name: String, val meta: String, val folded: Boolean) : Row
        data class Source(val text: String) : Row
        data class Entry(val item: LibraryItem) : Row
    }

    /**
     * 第一次进来全部折叠：一支展开就是几百条，先给一张「目录」比直接糊一脸正文好。
     * 之后按用户自己折的来（折叠状态落库 `lib_fold`）。
     */
    private fun defaultFolded(): Set<String> =
        (library.topicTags.map { it.id } + UNTAGGED).toHashSet()

    /**
     * 来源小标题：导图条目的 source 是「板块 / 主题」，取主题名更有信息量；
     * 卡片的 source 本来就是「超格 · 第几讲」，原样显示。
     */
    private fun sourceLabel(raw: String): String {
        val cut = raw.lastIndexOf(" / ")
        return if (cut >= 0) raw.substring(cut + 3).trim().ifBlank { raw } else raw
    }

    private fun rebuildRows() {
        rows.clear()

        // 先按主标签全量归组：计数要用全量，不能被「只看未背」过滤掉
        val byTag = LinkedHashMap<String, MutableList<LibraryItem>>()
        library.topicTags.forEach { byTag[it.id] = ArrayList() }
        val untagged = ArrayList<LibraryItem>()
        for (item in library.items) {
            val bucket = byTag[item.mainTag]
            if (bucket != null) bucket.add(item) else untagged.add(item)
        }

        byTag.forEach { (tagId, all) ->
            addBranch(tagId, library.tagName(tagId), all)
        }
        if (untagged.isNotEmpty()) {
            addBranch(UNTAGGED, getString(R.string.lib_untagged), untagged)
        }
        adapter.notifyDataSetChanged()
    }

    private fun addBranch(tagId: String, name: String, all: List<LibraryItem>) {
        if (all.isEmpty()) return
        val doneCount = all.count { it.id in done }
        val visible = if (onlyUnmastered) all.filter { it.id !in done } else all
        // 只看未背时，已经背完的分支整支不占位置
        if (visible.isEmpty() && onlyUnmastered) return

        val meta = when {
            onlyUnmastered -> getString(R.string.lib_tag_remain_fmt, visible.size)
            doneCount > 0 -> getString(R.string.recite_progress_fmt, doneCount, all.size)
            else -> getString(R.string.lib_tag_count_fmt, all.size)
        }
        val isFolded = tagId in folded
        rows.add(Row.Tag(tagId, name, meta, isFolded))
        if (isFolded) return

        var lastSource = ""
        for (item in visible) {
            // 按原始 source 判断是否换了段，显示时才收成主题名（两个板块可能有同名主题）
            if (item.source.isNotBlank() && item.source != lastSource) {
                rows.add(Row.Source(sourceLabel(item.source)))
                lastSource = item.source
            }
            rows.add(Row.Entry(item))
        }
    }

    private fun toggleFold(tagId: String) {
        if (!folded.remove(tagId)) folded.add(tagId)
        ReciteLibraryRepository.saveFoldedTags(requireContext(), folded)
        rebuildRows()
    }

    // ---------------------------------------------------------------- 适配器

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Tag -> TYPE_TAG
            is Row.Source -> TYPE_SOURCE
            is Row.Entry -> TYPE_ENTRY
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_TAG -> TagHolder(ItemOutlineTagBinding.inflate(inflater, parent, false))
                TYPE_SOURCE -> SourceHolder(ItemOutlineSourceBinding.inflate(inflater, parent, false))
                else -> EntryHolder(ItemOutlineLineBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Tag -> (holder as TagHolder).bind(row)
                is Row.Source -> (holder as SourceHolder).bind(row)
                is Row.Entry -> (holder as EntryHolder).bind(row.item)
            }
        }
    }

    private inner class TagHolder(
        private val item: ItemOutlineTagBinding
    ) : RecyclerView.ViewHolder(item.root) {

        fun bind(row: Row.Tag) {
            item.tvTagName.text = row.name
            item.tvTagMeta.text = row.meta
            item.tvChevron.text = if (row.folded) "▸" else "▾"
            item.root.setOnClickListener { toggleFold(row.tagId) }
        }
    }

    private inner class SourceHolder(
        private val item: ItemOutlineSourceBinding
    ) : RecyclerView.ViewHolder(item.root) {

        fun bind(row: Row.Source) {
            item.tvSource.text = row.text
            item.tvSource.isVisible = row.text.isNotBlank()
        }
    }

    private inner class EntryHolder(
        private val item: ItemOutlineLineBinding
    ) : RecyclerView.ViewHolder(item.root) {

        fun bind(entry: LibraryItem) {
            val context = requireContext()
            val isCard = entry.isCard

            // 缩进 = 层级：卡片没有层级，从一级起；导图行按 depth 每级 14dp
            val indent = if (isCard) {
                dp(24)
            } else {
                dp(24 + entry.depth * 14)
            }
            item.row.setPaddingRelative(indent, 0, dp(4), 0)
            item.indentBar.isVisible = !isCard && entry.depth == 0

            item.tvText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f + fontLevel * 2)
            // 能揭示的只有两种：卡片（自带 ____）和有挖空区间的导图行
            val canReveal = isCard || entry.cloze.isNotEmpty()
            if (isCard) {
                item.tvText.text = ReciteCloze.card(
                    requireContext(), entry.text, entry.answers,
                    reveal = !blind || entry.id in revealed,
                )
                item.tvText.setTextColor(ContextCompat.getColor(context, R.color.fb_text_primary))
                item.tvText.setTypeface(null, Typeface.NORMAL)
            } else {
                item.tvText.text = ReciteCloze.line(
                    requireContext(), entry.text, entry.cloze,
                    reveal = !blind || entry.id in revealed,
                )
                val branch = entry.depth == 0
                item.tvText.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (branch) R.color.fb_text_title else R.color.fb_text_primary
                    )
                )
                item.tvText.setTypeface(null, if (branch) Typeface.BOLD else Typeface.NORMAL)
            }

            item.row.isClickable = true
            item.row.setOnClickListener {
                // 有挖空的就地揭示；没得揭示的（单句短语、短标题）直接进背诵流
                if (canReveal) toggleReveal(entry.id) else openDrill(entry.id)
            }
            item.row.setOnLongClickListener {
                openDrill(entry.id)
                true
            }

            val isDone = entry.id in done
            item.tvDot.text = if (isDone) "●" else "○"
            item.tvDot.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isDone) R.color.fb_primary else R.color.fb_text_hint
                )
            )
            item.tvDot.contentDescription = getString(
                if (isDone) R.string.lib_mark_undone else R.string.lib_mark_done
            )
            item.tvDot.setOnClickListener { setDone(entry.id, !isDone) }
        }
    }

    // ---------------------------------------------------------------- 交互

    private fun toggleReveal(id: String) {
        if (!blind) return
        if (!revealed.remove(id)) revealed.add(id)
        notifyEntry(id)
    }

    private fun setDone(id: String, value: Boolean) {
        done = ReciteLibraryRepository.setDone(requireContext(), id, value)
        binding.list.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        // 计数、进度、以及「只看未背」过滤后的行数都会变，整表重建最省事也最不容易错
        rebuildRows()
        renderProgress()
    }

    /** 找到某条内容所在的行号，改了只刷新这一行，不整表重建 */
    private fun notifyEntry(id: String) {
        val index = rows.indexOfFirst { it is Row.Entry && it.item.id == id }
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    private fun setOnlyUnmastered(value: Boolean) {
        if (onlyUnmastered == value) return
        onlyUnmastered = value
        ReciteLibraryRepository.setOnlyUnmastered(requireContext(), value)
        renderChip()
        rebuildRows()
        binding.list.scrollToPosition(0)
    }

    private fun setBlind(on: Boolean) {
        if (blind == on) return
        blind = on
        ReciteLibraryRepository.setBlind(requireContext(), on)
        // 关掉遮答时把「本轮已揭示」一起清掉，下次打开还是干净的
        if (!on) revealed.clear()
        updateModeAction()
        toast(if (on) R.string.lib_blind_on else R.string.lib_blind_off)
        adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- 渲染

    private fun renderProgress() {
        binding.tvProgress.text = getString(
            R.string.lib_progress_fmt,
            library.doneCount(done),
            library.items.size,
        )
    }

    /** 「只看未背」胶囊两态：开 = 主色实心白字，关 = 浅底次要色 */
    private fun renderChip() {
        val context = requireContext()
        binding.chipUnmastered.setBackgroundResource(
            if (onlyUnmastered) R.drawable.bg_fb_chip_selected else R.drawable.bg_fb_chip
        )
        binding.chipUnmastered.setTextColor(
            ContextCompat.getColor(
                context,
                if (onlyUnmastered) R.color.fb_text_on_primary else R.color.fb_text_secondary
            )
        )
        binding.chipUnmastered.setTypeface(null, if (onlyUnmastered) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun updateModeAction() {
        val context = requireContext()
        modeAction.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (blind) R.color.fb_primary else R.color.fb_icon)
        )
        modeAction.background = if (blind) {
            ContextCompat.getDrawable(context, R.drawable.bg_recite_mode_on)
        } else {
            null
        }
        modeAction.isSelected = blind
        modeAction.contentDescription =
            getString(if (blind) R.string.lib_blind_on else R.string.lib_blind)
    }

    private fun updateFontAction() {
        val labels = arrayOf(
            R.string.recite_font_small,
            R.string.recite_font_medium,
            R.string.recite_font_large,
        )
        fontAction.text = getString(R.string.recite_font_fmt, getString(labels[fontLevel]))
    }

    private fun cycleFont() {
        fontLevel = (fontLevel + 1) % 3
        ReciteRepository.setFontLevel(requireContext(), fontLevel)
        updateFontAction()
        adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- 更多菜单

    private fun showMore(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            // 继续放第一条：背到一半回来，最想点的就是它
            menu.add(0, MENU_CONTINUE, 0, R.string.drill_continue)
            menu.add(0, MENU_RESET, 1, R.string.lib_reset_done)
            menu.add(0, MENU_GRAPH, 2, R.string.lib_graph)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CONTINUE -> continueDrill()
                    MENU_RESET -> confirmReset()
                    MENU_GRAPH -> openGraph()
                }
                true
            }
            show()
        }
    }

    private fun confirmReset() {
        val count = library.doneCount(done)
        if (count == 0) {
            toast(R.string.lib_reset_none)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.lib_reset_done)
            .setMessage(getString(R.string.lib_reset_msg, count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ReciteLibraryRepository.clearDone(requireContext())
                done = emptySet()
                revealed.clear()
                rebuildRows()
                renderProgress()
                toast(R.string.lib_reset_ok)
            }
            .show()
    }

    // ---------------------------------------------------------------- 小工具

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val TYPE_TAG = 0
        const val TYPE_SOURCE = 1
        const val TYPE_ENTRY = 2

        const val MENU_CONTINUE = 1
        const val MENU_RESET = 2
        const val MENU_GRAPH = 3

        /** 没打上标签的条目归到这一支（资产里是挖空把关键词挖走的那些） */
        const val UNTAGGED = "__untagged__"
    }
}
