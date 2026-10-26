package com.lytree.qbank.ui.word

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.WordCategory
import com.lytree.qbank.databinding.FragmentWordsBinding
import com.lytree.qbank.ui.Refreshable

/**
 * 词库页：成语和实词同表，按拼音首字母分组，右侧字母条直接跳组。
 *
 * 筛选只做分类这一维（全部 / 成语 / 实词）——细分标签各来源口径不一致，
 * 做成固定筛选项反而会漏词。
 */
class WordListFragment : Fragment(), Refreshable {

    private var _binding: FragmentWordsBinding? = null
    private val binding get() = _binding!!

    private val adapter = WordAdapter()
    private lateinit var db: QBankDb

    /** 当前分类筛选，null = 全部 */
    private var filter: String? = null

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
        binding.emptyAction.setOnClickListener { openImport() }
    }

    private fun openImport() {
        startActivity(Intent(requireContext(), WordImportActivity::class.java))
    }

    private fun selectFilter(category: String?) {
        if (filter == category) return
        filter = category
        WordAdapter.applyChipStyle(binding.chipAll, category == null)
        WordAdapter.applyChipStyle(binding.chipIdiom, category == WordCategory.IDIOM.code)
        WordAdapter.applyChipStyle(binding.chipWord, category == WordCategory.WORD.code)
        binding.list.scrollToPosition(0)
        refresh()
    }

    override fun refresh() {
        val total = db.countWords()
        val words = if (total == 0) emptyList() else db.listWords(filter)
        adapter.submit(words)

        binding.tvCount.text = if (total == 0) "" else getString(R.string.word_total_fmt, words.size)
        binding.emptyBox.visibility = if (total == 0) View.VISIBLE else View.GONE
        buildIndex(adapter.sectionPositions())

        WordAdapter.applyChipStyle(binding.chipAll, filter == null)
        WordAdapter.applyChipStyle(binding.chipIdiom, filter == WordCategory.IDIOM.code)
        WordAdapter.applyChipStyle(binding.chipWord, filter == WordCategory.WORD.code)
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
