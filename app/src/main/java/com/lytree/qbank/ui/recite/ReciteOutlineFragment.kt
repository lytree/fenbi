package com.lytree.qbank.ui.recite

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
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
 * * 顶栏划掉的眼睛 = 遮答预览（默认关，打开才遮挖空、点行揭示）；
 * * 折叠状态、已背进度、字号都落库，切到词库再回来还是原样。
 */
class ReciteOutlineFragment : Fragment() {

    private var _binding: FragmentReciteOutlineBinding? = null
    private val binding get() = _binding!!

    private lateinit var library: ReciteLibrary
    private lateinit var adapter: RowAdapter
    private lateinit var modeAction: ImageView
    private lateinit var fontAction: TextView
    private lateinit var searchAction: ImageView

    private val rows = ArrayList<Row>()

    /** 搜索词：非空时整表按命中重建（命中分支自动展开，折叠不起作用） */
    private var query = ""

    /** 已背条目 id（卡片 + 导图行一起算），落库 `lib_done` */
    private var done: Set<String> = emptySet()

    /** 本轮临时揭示的条目：退出即清空，不落库 */
    private val revealed = HashSet<String>()

    /** 折叠起来的标签分支，落库 `lib_fold` */
    private val folded = HashSet<String>()

    /** 资产里真实存在的标签 id：用来判断一条是「打了标签」还是「未归类」 */
    private val tagIds by lazy { library.topicTags.map { it.id }.toHashSet() }

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

        // 划掉的眼睛 = 遮答预览：点了才把挖空遮起来，默认整篇可读
        modeAction = binding.titleBar.addIconAction(
            R.drawable.ic_recite_blind,
            getString(R.string.lib_blind)
        ) { setBlind(!blind) }
        fontAction = binding.titleBar.addTextAction("") { chooseFont() }
        fontAction.setTextColor(ContextCompat.getColor(context, R.color.fb_text_secondary))
        // addIconAction 的回调不带视图参数，先用空回调拿下这颗图标，再自己绑一次当菜单锚点
        searchAction = binding.titleBar.addIconAction(
            R.drawable.ic_search,
            getString(R.string.lib_search)
        ) { setSearching(!binding.searchRow.isVisible) }
        val more = binding.titleBar.addIconAction(
            R.drawable.ic_fb_more,
            getString(R.string.lib_more)
        ) {}
        more.setOnClickListener { showMore(more) }

        binding.searchInput.doAfterTextChanged { text ->
            val next = text?.toString().orEmpty()
            if (next == query) return@doAfterTextChanged
            query = next
            rebuildRows()
            binding.list.scrollToPosition(0)
        }
        binding.searchCancel.setOnClickListener { setSearching(false) }

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
        refreshEmpty()
    }

    override fun onPause() {
        super.onPause()
        val context = context ?: return
        ReciteLibraryRepository.saveFoldedTags(context, folded)
        // 搜索态的行号对不上整棵树，别把它记成「上次看到的那一行」
        if (query.isNotBlank()) return
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
        val q = query.trim().lowercase()

        // 先按主标签全量归组：计数要用全量，不能被「只看未背」过滤掉
        val byTag = LinkedHashMap<String, MutableList<LibraryItem>>()
        library.topicTags.forEach { byTag[it.id] = ArrayList() }
        val untagged = ArrayList<LibraryItem>()
        for (item in library.items) {
            val bucket = byTag[item.mainTag]
            if (bucket != null) bucket.add(item) else untagged.add(item)
        }

        byTag.forEach { (tagId, all) ->
            addBranch(tagId, library.tagName(tagId), all, q)
        }
        if (untagged.isNotEmpty()) {
            addBranch(UNTAGGED, getString(R.string.lib_untagged), untagged, q)
        }
        adapter.notifyDataSetChanged()
        refreshEmpty()
    }

    private fun addBranch(tagId: String, name: String, all: List<LibraryItem>, q: String = "") {
        if (all.isEmpty()) return
        val searching = q.isNotEmpty()
        // 搜索：分支名命中就整支带出，否则只留下命中的条目
        val base = if (!searching || name.lowercase().contains(q)) {
            all
        } else {
            all.filter { matches(it, q) }
        }
        val visible = if (onlyUnmastered) base.filter { it.id !in done } else base
        // 只看未背时，已经背完的分支整支不占位置；搜索时没命中的分支同样不占位置
        if (visible.isEmpty() && (onlyUnmastered || searching)) return

        val meta = if (searching) getString(R.string.lib_search_hit_fmt, visible.size) else tagMeta(all)
        // 搜索结果直接摊开：用户要找的是具体那一条，不是再来点一次展开
        val isFolded = !searching && tagId in folded
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

    /** 搜索命中口径：正文 / 答案 / 来源 / 导图主题名，忽略大小写 */
    private fun matches(item: LibraryItem, q: String): Boolean =
        item.text.lowercase().contains(q) ||
            item.source.lowercase().contains(q) ||
            item.topicName.lowercase().contains(q) ||
            item.answers.any { it.lowercase().contains(q) }

    /** 分支右侧计数：已背 x / n；只看未背时改成「还剩几条」 */
    private fun tagMeta(all: List<LibraryItem>): String {
        val doneCount = all.count { it.id in done }
        return when {
            onlyUnmastered -> getString(R.string.lib_tag_remain_fmt, all.size - doneCount)
            doneCount > 0 -> getString(R.string.recite_progress_fmt, doneCount, all.size)
            else -> getString(R.string.lib_tag_count_fmt, all.size)
        }
    }

    /** 某一支的全部条目（含已背）：标已背后只重算这一支的计数，口径与 [rebuildRows] 一致 */
    private fun itemsOf(tagId: String): List<LibraryItem> = library.items.filter { item ->
        val tagged = item.mainTag in tagIds
        if (tagId == UNTAGGED) !tagged else item.mainTag == tagId
    }

    private fun toggleFold(tagId: String) {
        if (!folded.remove(tagId)) folded.add(tagId)
        ReciteLibraryRepository.saveFoldedTags(requireContext(), folded)
        rebuildRows()
    }

    /** 全部展开 / 全部折叠：结果落库，下次进来还是这个形态 */
    private fun setAllFolded(value: Boolean) {
        folded.clear()
        if (value) {
            folded += library.topicTags.map { it.id }
            if (library.items.any { it.mainTag !in tagIds }) folded += UNTAGGED
        }
        ReciteLibraryRepository.saveFoldedTags(requireContext(), folded)
        rebuildRows()
        binding.list.scrollToPosition(0)
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

        /** 上一次绑定的分支与折叠态：只有「同一支被点开 / 收起」才播旋转，首次排版直接到位 */
        private var boundTagId: String? = null
        private var boundFolded: Boolean? = null

        fun bind(row: Row.Tag) {
            // 字号跟着「A小/中/大」走：之前只有条目会变，大字号时条目反而比分支标题还大
            item.tvTagName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f + fontLevel * 2)
            item.tvTagMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f + fontLevel)
            item.tvTagName.text = row.name
            item.tvTagMeta.text = row.meta

            // ▾ 基准朝下，收起时转 -90° 变 ▸：用旋转而不是换字，折叠时箭头有个明确的动势
            val target = if (row.folded) -90f else 0f
            val animate = boundTagId == row.tagId && boundFolded != row.folded
            if (animate) {
                item.tvChevron.animate().rotation(target).setDuration(160).start()
            } else {
                item.tvChevron.animate().cancel()
                item.tvChevron.rotation = target
            }
            boundTagId = row.tagId
            boundFolded = row.folded

            // 读屏用户听得到「展开 / 收起」，而不是一串数字和箭头
            ViewCompat.setStateDescription(
                item.root,
                getString(if (row.folded) R.string.lib_collapsed else R.string.lib_expanded)
            )
            item.root.setOnClickListener { toggleFold(row.tagId) }
        }
    }

    private inner class SourceHolder(
        private val item: ItemOutlineSourceBinding
    ) : RecyclerView.ViewHolder(item.root) {

        fun bind(row: Row.Source) {
            item.tvSource.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f + fontLevel)
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

            // 能揭示的只有两种：卡片（自带 ____）和有挖空区间的导图行
            val canReveal = isCard || entry.cloze.isNotEmpty()
            val branch = !isCard && entry.depth == 0
            item.tvText.bind(
                text = entry.text,
                answers = entry.answers,
                cloze = entry.cloze,
                reveal = !blind || entry.id in revealed,
                card = isCard,
                fontSp = 15f + fontLevel * 2,
                lineSpacingDp = 4f,
                colorRes = if (branch) R.color.fb_text_title else R.color.fb_text_primary,
                bold = branch,
            )

            item.row.isClickable = true
            item.row.setOnClickListener {
                // 盲态点行就地揭示；非盲态整篇可读、没得揭示，点了就进这一条的聚焦背诵
                if (blind && canReveal) toggleReveal(entry.id) else openDrill(entry.id)
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
        renderProgress()

        val entryIndex = rows.indexOfFirst { it is Row.Entry && it.item.id == id }
        if (entryIndex < 0) return
        adapter.notifyItemChanged(entryIndex)
        updateBranchMeta(rows[entryIndex] as Row.Entry)
        // 「只看未背」下先不抽走这一行：手指底下正看着的行突然消失最别扭，也没法反手取消；
        // 等下次整表重建（折叠 / 切筛选 / 重置）时它自然消失
        if (!onlyUnmastered) refreshEmpty()
    }

    /** 标已背后只刷新这一条所属分支的计数，不整表重建（展开全量时有两千多行） */
    private fun updateBranchMeta(entry: Row.Entry) {
        val tagId = entry.item.mainTag.takeIf { it in tagIds } ?: UNTAGGED
        val index = rows.indexOfFirst { it is Row.Tag && it.tagId == tagId }
        if (index < 0) return
        val row = rows[index] as Row.Tag
        val all = itemsOf(tagId)
        val q = query.trim().lowercase()
        val meta = if (q.isEmpty()) {
            tagMeta(all)
        } else {
            // 搜索态下计数是「命中几条」，不能换成全量口径
            val base = if (row.name.lowercase().contains(q)) all else all.filter { matches(it, q) }
            val visible = if (onlyUnmastered) base.filter { it.id !in done } else base
            getString(R.string.lib_search_hit_fmt, visible.size)
        }
        rows[index] = row.copy(meta = meta)
        adapter.notifyItemChanged(index)
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

    /** 顶栏放大镜：展开 / 收起搜索行；收起时把关键字一起清掉，免得留下看不见的筛选 */
    private fun setSearching(on: Boolean) {
        val context = requireContext()
        binding.searchRow.isVisible = on
        searchAction.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (on) R.color.fb_primary else R.color.fb_icon)
        )
        searchAction.contentDescription =
            getString(if (on) R.string.lib_search_close else R.string.lib_search)

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (on) {
            binding.searchInput.requestFocus()
            binding.searchInput.post { imm.showSoftInput(binding.searchInput, 0) }
        } else {
            imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
            // 清关键字会触发 watcher 重建，不用再手动 rebuild
            binding.searchInput.setText("")
            binding.list.scrollToPosition(0)
        }
    }

    // ---------------------------------------------------------------- 渲染

    private fun renderProgress() {
        val total = library.items.size
        val doneCount = library.doneCount(done)
        binding.tvProgress.text = getString(R.string.lib_progress_fmt, doneCount, total)

        // 细进度条按权重分摊，省得自己算像素宽度
        val fill = if (total == 0) 0 else {
            (doneCount.toFloat() / total * WEIGHT_TOTAL).toInt().coerceIn(0, WEIGHT_TOTAL)
        }
        (binding.progressFill.layoutParams as LinearLayout.LayoutParams).weight = fill.toFloat()
        (binding.progressRest.layoutParams as LinearLayout.LayoutParams).weight =
            (WEIGHT_TOTAL - fill).toFloat()
        binding.progressFill.requestLayout()
        binding.progressRest.requestLayout()
    }

    /** 列表空态：整库没内容、「只看未背」筛空、搜索无命中，是三种要分开说的情况 */
    private fun refreshEmpty() {
        val empty = rows.isEmpty()
        binding.emptyText.isVisible = empty
        binding.emptyText.text = when {
            query.isNotBlank() -> getString(R.string.lib_search_empty_fmt, query.trim())
            library.isEmpty -> getString(R.string.lib_empty)
            else -> getString(R.string.lib_empty_unmastered)
        }
        binding.list.isVisible = !empty
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

    private fun chooseFont() {
        ReciteFontPicker.show(requireContext(), fontLevel) { level ->
            fontLevel = level
            ReciteRepository.setFontLevel(requireContext(), level)
            updateFontAction()
            adapter.notifyDataSetChanged()
        }
    }

    // ---------------------------------------------------------------- 更多菜单

    private fun showMore(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            // 继续放第一条：背到一半回来，最想点的就是它
            menu.add(0, MENU_CONTINUE, 0, R.string.drill_continue)
            menu.add(0, MENU_RESET, 1, R.string.lib_reset_done)
            // 搜索时整棵树已经摊开，展开 / 折叠在这个状态下没有意义
            if (query.isBlank()) {
                menu.add(0, MENU_EXPAND_ALL, 2, R.string.lib_expand_all)
                menu.add(0, MENU_COLLAPSE_ALL, 3, R.string.lib_collapse_all)
            }
            menu.add(0, MENU_GRAPH, 4, R.string.lib_graph)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CONTINUE -> continueDrill()
                    MENU_RESET -> confirmReset()
                    MENU_EXPAND_ALL -> setAllFolded(false)
                    MENU_COLLAPSE_ALL -> setAllFolded(true)
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
        const val MENU_EXPAND_ALL = 3
        const val MENU_COLLAPSE_ALL = 4
        const val MENU_GRAPH = 5

        /** 细进度条按权重分摊时的总份数 */
        const val WEIGHT_TOTAL = 1000

        /** 没打上标签的条目归到这一支（资产里是挖空把关键词挖走的那些） */
        const val UNTAGGED = "__untagged__"
    }
}
