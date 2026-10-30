package com.lytree.qbank.ui.idiom

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.IdiomRepository
import com.lytree.qbank.data.model.Idiom
import com.lytree.qbank.data.model.IdiomCatalog
import com.lytree.qbank.databinding.FragmentIdiomBinding
import com.lytree.qbank.ui.LibrarySwitch
import com.lytree.qbank.ui.Refreshable
import com.lytree.qbank.ui.recite.ReciteFragment
import com.lytree.qbank.ui.word.WordListFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 成语汇总页。
 *
 * · 搜索：成语 / 释义 / 归类名都能命中，输入单个字母时按首字母匹配
 * · 排序：按文档 A-Z 分节排序，列表内带首字母分组头，右侧字母索引可一键跳转
 * · 归类：文档末尾的归类表以两级标签呈现（大类 → 子类），点标签即筛选
 * · 释义：默认两行，点条目展开 / 收起
 *
 * 本页不占底部导航（导航 4 项），从词库页顶部的「词库 / 成语 / 考点背诵」切换进来，
 * 切回词库同样是 replace，底部仍高亮「词库」。
 */
class IdiomListFragment : Fragment(), Refreshable {

    private var _binding: FragmentIdiomBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: IdiomAdapter
    private var catalog = IdiomCatalog.EMPTY
    private var query = ""
    private var selectedGroup: String? = null
    private var selectedSub: String? = null
    private val indexViews = LinkedHashMap<String, TextView>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentIdiomBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.titleBar.usePageTitleStyle()
        binding.titleBar.setTitle(getString(R.string.page_idioms))
        binding.titleBar.addTextAction(getString(R.string.action_reset)) { resetFilters() }

        // 词库 / 成语 / 考点背诵 切换：当前已在成语，点其余两段把本页换掉
        LibrarySwitch.bind(
            binding.librarySwitch,
            LibrarySwitch.PAGE_IDIOMS,
            onWords = { openWords() },
            onIdioms = {},
            onRecite = { openRecite() }
        )

        adapter = IdiomAdapter { group -> filterByGroup(group) }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                syncIndexHighlight()
            }
        })

        binding.search.doAfterTextChanged {
            query = it?.toString().orEmpty()
            applyFilter(scrollTop = true)
        }

        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { IdiomRepository.catalog(appContext) }
            if (_binding == null) return@launch
            catalog = loaded
            renderGroupChips()
            applyFilter(scrollTop = false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        indexViews.clear()
        _binding = null
    }

    override fun refresh() {
        if (_binding != null) applyFilter(scrollTop = false)
    }

    /** 换回词库页：仍在「词库」这个底部 Tab 里，只是页面内容换了 */
    private fun openWords() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, WordListFragment())
            .commit()
    }

    /** 换到考点背诵页：同样留在「词库」Tab 里 */
    private fun openRecite() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, ReciteFragment())
            .commit()
    }

    // ---------------------------------------------------------------- 筛选

    private fun applyFilter(scrollTop: Boolean) {
        if (_binding == null) return
        val keyword = query.trim()
        val filtered = catalog.idioms.filter { matchesGroup(it) && matchesQuery(it, keyword) }

        adapter.submit(buildRows(filtered))
        binding.emptyBox.isVisible = filtered.isEmpty()
        binding.empty.text = if (catalog.idioms.isEmpty()) {
            getString(R.string.idiom_load_failed)
        } else {
            getString(R.string.idiom_empty)
        }
        binding.count.text = countText(filtered.size, keyword)

        buildLetterIndex()
        if (scrollTop) {
            (binding.list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        }
        syncIndexHighlight()
    }

    private fun matchesGroup(idiom: Idiom): Boolean {
        val group = selectedGroup ?: return true
        return idiom.tags.any { tag ->
            tag.group == group && (selectedSub == null || tag.sub == selectedSub)
        }
    }

    private fun matchesQuery(idiom: Idiom, keyword: String): Boolean {
        if (keyword.isEmpty()) return true
        if (idiom.word.contains(keyword)) return true
        if (idiom.definition.contains(keyword)) return true
        if (keyword.length == 1 && idiom.letter.equals(keyword, ignoreCase = true)) return true
        return idiom.tags.any { tag ->
            tag.group.contains(keyword) || tag.sub?.contains(keyword) == true
        }
    }

    private fun countText(size: Int, keyword: String): String {
        val selected = when {
            selectedSub != null -> "$selectedGroup · $selectedSub"
            selectedGroup != null -> selectedGroup!!
            else -> null
        }
        return when {
            selected != null -> getString(R.string.idiom_count_group, selected, size)
            keyword.isNotEmpty() -> getString(R.string.idiom_count_filtered, size)
            else -> getString(R.string.idiom_count_all, size)
        }
    }

    private fun buildRows(list: List<Idiom>): List<IdiomRow> {
        val rows = ArrayList<IdiomRow>(list.size + 26)
        var lastLetter: String? = null
        for (idiom in list) {
            if (idiom.letter != lastLetter) {
                rows.add(IdiomRow.Header(idiom.letter))
                lastLetter = idiom.letter
            }
            rows.add(IdiomRow.Entry(idiom))
        }
        return rows
    }

    // ---------------------------------------------------------------- 归类标签

    private fun renderGroupChips() {
        binding.groupTags.removeAllViews()
        var selectedView: View? = null

        val allChip = chip(getString(R.string.idiom_all), selectedGroup == null) {
            selectGroup(null, null)
        }
        binding.groupTags.addView(allChip)
        if (selectedGroup == null) selectedView = allChip

        for (group in catalog.groups) {
            val chipView = chip(group.name, selectedGroup == group.name) {
                selectGroup(group.name, null)
            }
            binding.groupTags.addView(chipView)
            if (selectedGroup == group.name) selectedView = chipView
        }
        selectedView?.let { target ->
            binding.groupScroll.post {
                binding.groupScroll.smoothScrollTo((target.left - dp(16)).coerceAtLeast(0), 0)
            }
        }
        renderSubChips()
    }

    private fun renderSubChips() {
        binding.subTags.removeAllViews()
        val group = catalog.groups.firstOrNull { it.name == selectedGroup }
        val subs = group?.subs.orEmpty()
        binding.subScroll.isVisible = subs.isNotEmpty()
        if (subs.isEmpty()) return

        var selectedView: View? = null
        val allChip = chip(getString(R.string.idiom_all), selectedSub == null) {
            selectGroup(group?.name, null)
        }
        binding.subTags.addView(allChip)
        if (selectedSub == null) selectedView = allChip

        for (sub in subs) {
            val chipView = chip(sub.name, selectedSub == sub.name) {
                selectGroup(group?.name, sub.name)
            }
            binding.subTags.addView(chipView)
            if (selectedSub == sub.name) selectedView = chipView
        }
        selectedView?.let { target ->
            binding.subScroll.post {
                binding.subScroll.smoothScrollTo((target.left - dp(16)).coerceAtLeast(0), 0)
            }
        }
    }

    private fun selectGroup(group: String?, sub: String?) {
        selectedGroup = group
        selectedSub = sub
        renderGroupChips()
        applyFilter(scrollTop = true)
    }

    /** 点条目上的归类标签：直接按该大类筛选，并清空搜索词避免两个条件互相抵消 */
    private fun filterByGroup(group: String) {
        if (query.isNotEmpty()) binding.search.setText("")
        selectedGroup = group
        selectedSub = null
        renderGroupChips()
        applyFilter(scrollTop = true)
    }

    private fun resetFilters() {
        if (query.isNotEmpty()) binding.search.setText("")
        selectedGroup = null
        selectedSub = null
        renderGroupChips()
        applyFilter(scrollTop = true)
    }

    /** 筛选胶囊：未选/选中两态共用 styles.xml 里的 FbChip / FbChipSelected，不再各写一份字号配色 */
    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val context = requireContext()
        val style = if (selected) R.style.FbChipSelected else R.style.FbChip
        return TextView(context, null, 0, style).apply {
            this.text = text
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                resources.getDimensionPixelSize(R.dimen.fb_chip_h),
            ).apply { marginEnd = dp(8) }
        }
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    // ---------------------------------------------------------------- 字母索引

    private fun buildLetterIndex() {
        binding.letterIndex.removeAllViews()
        indexViews.clear()
        val letters = adapter.letters()
        binding.letterIndex.isVisible = letters.isNotEmpty()
        if (letters.isEmpty()) return

        val density = resources.displayMetrics.density
        val itemHeight = (15 * density).toInt()
        for (letter in letters) {
            val tv = TextView(requireContext()).apply {
                text = letter
                setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(R.dimen.fb_nav_text)
                )
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(ContextCompat.getColor(requireContext(), R.color.fb_text_meta))
                isClickable = true
                setOnClickListener { jumpToLetter(letter) }
            }
            indexViews[letter] = tv
            binding.letterIndex.addView(
                tv,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, itemHeight),
            )
        }
    }

    private fun jumpToLetter(letter: String) {
        val position = adapter.positionOfLetter(letter)
        if (position < 0) return
        (binding.list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
        highlightIndex(letter)
    }

    private fun syncIndexHighlight() {
        if (_binding == null || indexViews.isEmpty()) return
        val manager = binding.list.layoutManager as? LinearLayoutManager ?: return
        highlightIndex(adapter.letterAt(manager.findFirstVisibleItemPosition()))
    }

    private fun highlightIndex(letter: String?) {
        indexViews.forEach { (key, view) ->
            val color = if (key == letter) R.color.fb_primary else R.color.fb_text_meta
            view.setTextColor(ContextCompat.getColor(requireContext(), color))
        }
    }
}
