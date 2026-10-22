package com.lytree.qbank.ui.paper

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.Paper
import com.lytree.qbank.databinding.FragmentListBinding
import com.lytree.qbank.ui.ComposeActivity
import com.lytree.qbank.ui.ExamActivity
import com.lytree.qbank.ui.Refreshable

class PaperListFragment : Fragment(), Refreshable {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val adapter = PaperAdapter()
    private lateinit var db: QBankDb

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = QBankDb.get(requireContext())
        binding.titleBar.usePageTitleStyle()
        binding.titleBar.setTitle(getString(com.lytree.qbank.R.string.page_papers))
        binding.titleBar.addTextAction(getString(com.lytree.qbank.R.string.action_compose)) { openCompose() }

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        adapter.onClick = { paper ->
            startActivity(Intent(requireContext(), ExamActivity::class.java).apply {
                putExtra(ExamActivity.EXTRA_PAPER_ID, paper.id)
            })
        }
        binding.emptyAction.setText(com.lytree.qbank.R.string.action_compose)
        binding.emptyAction.setOnClickListener { openCompose() }
    }

    private fun openCompose() {
        startActivity(Intent(requireContext(), ComposeActivity::class.java))
    }

    override fun refresh() {
        val list = db.listPapers()
        adapter.submit(list)
        binding.emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyAction.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.empty.setText(
            if (list.isEmpty()) {
                if (db.listBanks().isEmpty()) "还没有题库，先到「题库」页导入，再回来组卷" else "还没有试卷，点右上角「组卷」开始"
            } else ""
        )
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
