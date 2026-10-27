package com.lytree.qbank.ui.word

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.lytree.qbank.R
import com.lytree.qbank.data.BuiltinWords
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.WordCategory
import com.lytree.qbank.databinding.FragmentWordsBinding
import com.lytree.qbank.ui.Refreshable

/**
 * 词库页：成语和实词同表，按拼音首字母分组，右侧字母条直接跳组。
 *
 * 两维筛选，可叠加：
 * - 分类（全部 / 成语 / 实词）——固定三项；
 * - 标签（细分）——自由文本，个数随词表来源浮动，走弹窗多选，标签之间取并集。
 * 换分类会清空标签：标签清单本身是按分类统计的，留着旧标签多半一条都筛不出来。
 */
class WordListFragment : Fragment(), Refreshable {

    private var _binding: FragmentWordsBinding? = null
    private val binding get() = _binding!!

    private val adapter = WordAdapter()
    private lateinit var db: QBankDb

    /** 当前分类筛选，null = 全部 */
    private var filter: String? = null

    /** 当前标签筛选（细分原文，空串代表「未标注标签」），空表示不限 */
    private val tagFilter = LinkedHashSet<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentWordsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = QBankDb.get(requireContext())

        binding.titleBar.usePageTitleStyle()
        binding.titleBar.setTitle(getString(R.string.page_words))
        binding.titleBar.addTextAction(getString(R.string.action_import)) { openImport() }

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.chipAll.setOnClickListener { selectFilter(null) }
        binding.chipIdiom.setOnClickListener { selectFilter(WordCategory.IDIOM.code) }
        binding.chipWord.setOnClickListener { selectFilter(WordCategory.WORD.code) }
        binding.chipTag.setOnClickListener { openTagDialog() }
        binding.emptyAction.setOnClickListener { openImport() }
        binding.btnClearFilter.setOnClickListener { clearFilters() }

        // 内置词库：首次打开时后台灌一次（两千多条，主线程算拼音会卡），灌完再刷新
        binding.tvCount.setText(R.string.word_builtin_loading)
        BuiltinWords.seedIfNeeded(requireContext(), db) { n ->
            if (_binding == null || !isAdded) return@seedIfNeeded
            refresh()
            when {
                n > 0 -> toast(getString(R.string.word_builtin_done, n))
                n < 0 -> toast(getString(R.string.word_builtin_failed))
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private fun openImport() {
        startActivity(Intent(requireContext(), WordImportActivity::class.java))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun selectFilter(category: String?) {
        if (filter == category) return
        filter = category
        tagFilter.clear()
        binding.list.scrollToPosition(0)
        refresh()
    }

    private fun clearFilters() {
        filter = null
        tagFilter.clear()
        binding.list.scrollToPosition(0)
        refresh()
    }

    /** 标签清单只列当前分类下的（跟着分类走），弹窗里点确定才生效 */
    private fun openTagDialog() {
        val tags = db.listWordTags(filter)
        if (tags.isEmpty()) {
            toast(getString(R.string.word_tag_empty))
            return
        }
        WordTagDialog.show(requireContext(), scopeLabel(), tags, tagFilter) { picked ->
            tagFilter.clear()
            tagFilter.addAll(picked)
            binding.list.scrollToPosition(0)
            refresh()
        }
    }

    private fun scopeLabel(): String = when (filter) {
        WordCategory.IDIOM.code -> getString(R.string.word_filter_idiom)
        WordCategory.WORD.code -> getString(R.string.word_filter_word)
        else -> getString(R.string.word_filter_all)
    }

    override fun refresh() {
        val total = db.countWords()
        val words = if (total == 0) emptyList() else db.listWords(filter, tagFilter)
        adapter.submit(words)

        val noResult = total > 0 && words.isEmpty()
        binding.tvCount.text = if (total == 0) "" else getString(R.string.word_total_fmt, words.size)
        binding.emptyBox.visibility = if (total == 0) View.VISIBLE else View.GONE
        binding.noResultBox.visibility = if (noResult) View.VISIBLE else View.GONE

        buildIndex(adapter.sectionPositions())
        applyChipStyles()
        buildTagChips()
    }

    private fun applyChipStyles() {
        WordAdapter.applyChipStyle(binding.chipAll, filter == null)
        WordAdapter.applyChipStyle(binding.chipIdiom, filter == WordCategory.IDIOM.code)
        WordAdapter.applyChipStyle(binding.chipWord, filter == WordCategory.WORD.code)
        WordAdapter.applyChipStyle(binding.chipTag, tagFilter.isNotEmpty())
        binding.chipTag.text = if (tagFilter.isEmpty()) {
            getString(R.string.word_filter_tag)
        } else {
            getString(R.string.word_filter_tag_count, tagFilter.size)
        }
    }

    /** 已选标签单独一行：点一下就去掉这一个，不用再进弹窗翻找 */
    private fun buildTagChips() {
        val box = binding.tagChips
        box.removeAllViews()
        binding.tagBar.visibility = if (tagFilter.isEmpty()) View.GONE else View.VISIBLE
        if (tagFilter.isEmpty()) return

        val tagColor = ContextCompat.getColor(requireContext(), R.color.fb_type_tag_text)
        val tagBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_fb_tag)
        tagFilter.forEach { name ->
            val label = if (name.isEmpty()) getString(R.string.word_tag_untagged) else name
            val tv = TextView(requireContext()).apply {
                text = getString(R.string.word_tag_chip, label)
                textSize = 12f
                includeFontPadding = false
                setTextColor(tagColor)
                background = tagBg
                setPadding(dp(10), dp(5), dp(10), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    tagFilter.remove(name)
                    binding.list.scrollToPosition(0)
                    refresh()
                }
            }
            box.addView(tv)
        }
    }

    /** 右侧 A-Z 索引条：只给有词的字母建条目，点一下滚到该组顶部 */
    private fun buildIndex(sections: Map<String, Int>) {
        val bar = binding.indexBar
        bar.removeAllViews()
        bar.visibility = if (sections.size <= 1) View.GONE else View.VISIBLE

        val size = (11 * resources.displayMetrics.density).toInt()
        val color = ContextCompat.getColor(requireContext(), R.color.fb_primary)
        sections.forEach { (letter, position) ->
            val tv = TextView(requireContext()).apply {
                text = letter
                textSize = 9f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(color)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, size
                )
                setOnClickListener {
                    (binding.list.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(position, 0)
                }
            }
            bar.addView(tv)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
