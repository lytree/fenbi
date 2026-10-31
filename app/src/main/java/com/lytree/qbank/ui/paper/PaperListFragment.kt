package com.lytree.qbank.ui.paper

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.lytree.qbank.R
import com.lytree.qbank.data.db.PaperSet
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.Paper
import com.lytree.qbank.databinding.FragmentListBinding
import com.lytree.qbank.ui.AnswerEntryActivity
import com.lytree.qbank.ui.AnswerImportUi
import com.lytree.qbank.ui.ExamActivity
import com.lytree.qbank.ui.FbDialog
import com.lytree.qbank.ui.ImportActivity
import com.lytree.qbank.ui.MainActivity
import com.lytree.qbank.ui.Refreshable

/**
 * 试卷列表：**导入标题 → 试卷**两级树。
 *
 * 一次导入就是一个标题（组），组下面是那次导入拆出来的各份卷，点卷直接开练。
 * 组标题可折叠、可重命名、可整组删除；组内卷子右键（长按）仍是查看 / 补答案 / 删除。
 *
 * 这是 App 的主页，也是唯一的导入入口——原本挂「题库」页的导入按钮随题库层一起并到这里。
 */
class PaperListFragment : Fragment(), Refreshable {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val adapter = PaperTreeAdapter()
    private lateinit var db: QBankDb
    private var pendingPaper: Paper? = null

    /** 收起的组 id（存本地，切 Tab / 重进 App 都保持） */
    private val collapsed = HashSet<Long>()

    /** 试卷列表直接补答案：不必进答题页，也不用重做 */
    private val answerPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val paper = pendingPaper
        pendingPaper = null
        val act = activity as? AppCompatActivity ?: return@registerForActivityResult
        if (uri == null || paper == null) return@registerForActivityResult
        val questions = db.getPaper(paper.id)?.questions ?: emptyList()
        AnswerImportUi.handle(act, db, questions, uri) { refresh() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = QBankDb.get(requireContext())
        binding.titleBar.usePageTitleStyle()
        binding.titleBar.setTitle(getString(R.string.page_papers))
        binding.titleBar.addTextAction(getString(R.string.action_import)) { openImport() }

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        adapter.onPaperClick = { paper ->
            startActivity(Intent(requireContext(), ExamActivity::class.java).apply {
                putExtra(ExamActivity.EXTRA_PAPER_ID, paper.id)
                putExtra(ExamActivity.EXTRA_PAPER_NAME, paper.name)
            })
        }
        adapter.onPaperLongClick = { paper -> showPaperActions(paper) }
        adapter.onToggleSet = { set, fold -> toggle(set, fold) }
        adapter.onSetLongClick = { set -> showSetActions(set) }
        binding.emptyAction.setText(R.string.action_import)
        binding.emptyAction.setOnClickListener { openImport() }
    }

    private fun openImport() {
        (activity as? MainActivity)?.openImport()
            ?: startActivity(Intent(requireContext(), ImportActivity::class.java))
    }

    // ---------------------------------------------------------------- 组（标题）

    private fun toggle(set: PaperSet, fold: Boolean) {
        val key = adapter.keyOf(set)
        if (fold) collapsed.add(key) else collapsed.remove(key)
        saveCollapsed()
        refresh()
    }

    /** 长按标题：重命名 / 删除整组（「未分组」是兜底伪组，只能删卷不能改名） */
    private fun showSetActions(set: PaperSet) {
        val actions = if (set.id > 0) {
            arrayOf("重命名标题", "删除这一组（${set.paperCount} 份试卷）")
        } else {
            arrayOf("删除这些试卷（${set.paperCount} 份）")
        }
        FbDialog.list(
            context = requireContext(),
            title = set.title,
            items = actions,
            onPick = { which ->
                if (set.id > 0 && which == 0) {
                    FbDialog.input(
                        context = requireContext(),
                        title = "重命名标题",
                        initial = set.title,
                        hint = "导入标题",
                        multiline = false,
                        okText = getString(R.string.action_confirm),
                        onOk = { text ->
                            val name = text.trim()
                            if (name.isNotEmpty()) {
                                if (name != set.title && db.findPaperSetByTitle(name) != null) {
                                    FbDialog.alert(requireContext(), "已经有同名标题「$name」，换一个吧。")
                                } else {
                                    db.renamePaperSet(set.id, name)
                                    refresh()
                                }
                            }
                        }
                    )
                } else {
                    confirmDeleteSet(set)
                }
            }
        )
    }

    private fun confirmDeleteSet(set: PaperSet) {
        FbDialog.confirm(
            requireContext(),
            "删除「${set.title}」这一组？组里 ${set.paperCount} 份试卷的题目和作答记录会一起删除，删了不能恢复。",
            okText = getString(R.string.action_delete),
            onOk = {
                if (set.id > 0) {
                    db.deletePaperSet(set.id)
                } else {
                    set.papers.forEach { db.deletePaper(it.id) }
                }
                collapsed.remove(adapter.keyOf(set))
                saveCollapsed()
                refresh()
            }
        )
    }

    // ---------------------------------------------------------------- 试卷

    /** 长按：查看题目 / 补录答案 / 导入答案 / 删除。导入的试卷常常没答案，补答案是这里的日常操作 */
    private fun showPaperActions(paper: Paper) {
        val actions = arrayOf(
            getString(R.string.action_view_questions),
            getString(R.string.action_fill_answer),
            getString(R.string.action_import_answer),
            getString(R.string.action_delete)
        )
        FbDialog.list(
            context = requireContext(),
            title = paper.name,
            items = actions,
            onPick = { which ->
                when (which) {
                    0 -> startActivity(Intent(requireContext(), PaperDetailActivity::class.java).apply {
                        putExtra(PaperDetailActivity.EXTRA_PAPER_ID, paper.id)
                        putExtra(PaperDetailActivity.EXTRA_PAPER_NAME, paper.name)
                    })
                    1 -> startActivity(Intent(requireContext(), AnswerEntryActivity::class.java).apply {
                        putExtra(AnswerEntryActivity.EXTRA_PAPER_ID, paper.id)
                    })
                    2 -> {
                        pendingPaper = paper
                        try {
                            answerPicker.launch(AnswerImportUi.MIME)
                        } catch (_: Exception) {
                            answerPicker.launch(arrayOf("*/*"))
                        }
                    }
                    else -> confirmDelete(paper.id, paper.name)
                }
            }
        )
    }

    private fun confirmDelete(paperId: Long, name: String) {
        FbDialog.confirm(
            requireContext(),
            "删除试卷「$name」？这份卷的题目和它的作答记录会一起删除，删了不能恢复。",
            okText = getString(R.string.action_delete),
            onOk = {
                db.deletePaper(paperId)
                refresh()
            }
        )
    }

    // ---------------------------------------------------------------- 数据

    override fun refresh() {
        val sets = db.listPaperSets()
        val empty = sets.all { it.papers.isEmpty() }
        // 空组（卷都被删干净了）不占列表：db.deletePaper 已顺手清理，这里只兜个底
        adapter.submit(if (empty) emptyList() else sets, collapsed)
        binding.emptyBox.visibility = if (empty) View.VISIBLE else View.GONE
        binding.emptyAction.visibility = if (empty) View.VISIBLE else View.GONE
        binding.empty.setText(if (empty) getString(R.string.empty_papers) else "")
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun prefs() = requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun loadCollapsed() {
        collapsed.clear()
        prefs().getString(KEY_COLLAPSED, "").orEmpty()
            .split(',')
            .forEach { it.trim().toLongOrNull()?.let(collapsed::add) }
    }

    private fun saveCollapsed() {
        prefs().edit().putString(KEY_COLLAPSED, collapsed.joinToString(",")).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadCollapsed()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val PREFS = "paper_tree"
        private const val KEY_COLLAPSED = "collapsed"
    }
}
