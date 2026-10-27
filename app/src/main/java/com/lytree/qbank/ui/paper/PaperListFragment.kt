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
 * 试卷列表：一次导入 = 一份试卷，点进去直接开练。
 * 不再提供随机组卷入口，右上角换成「导入」。
 */
class PaperListFragment : Fragment(), Refreshable {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val adapter = PaperAdapter()
    private lateinit var db: QBankDb
    private var pendingPaper: Paper? = null

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
        adapter.onClick = { paper ->
            startActivity(Intent(requireContext(), ExamActivity::class.java).apply {
                putExtra(ExamActivity.EXTRA_PAPER_ID, paper.id)
                putExtra(ExamActivity.EXTRA_PAPER_NAME, paper.name)
            })
        }
        adapter.onLongClick = { paper -> showPaperActions(paper) }
        binding.emptyAction.setText(R.string.action_import)
        binding.emptyAction.setOnClickListener { openImport() }
    }

    private fun openImport() {
        (activity as? MainActivity)?.openImport()
            ?: startActivity(Intent(requireContext(), ImportActivity::class.java))
    }

    /** 长按：补录答案 / 导入答案 / 删除。导入的试卷常常没答案，补答案是这里的日常操作 */
    private fun showPaperActions(paper: Paper) {
        val actions = arrayOf(
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
                    0 -> startActivity(Intent(requireContext(), AnswerEntryActivity::class.java).apply {
                        putExtra(AnswerEntryActivity.EXTRA_PAPER_ID, paper.id)
                    })
                    1 -> {
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
            "删除试卷「$name」？题目仍保留在「题库」里，可再从题库开练。",
            okText = getString(R.string.action_delete),
            onOk = {
                db.deletePaper(paperId)
                refresh()
            }
        )
    }

    override fun refresh() {
        val list = db.listPapers()
        adapter.submit(list)
        binding.emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyAction.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.empty.setText(if (list.isEmpty()) getString(R.string.empty_papers) else "")
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
