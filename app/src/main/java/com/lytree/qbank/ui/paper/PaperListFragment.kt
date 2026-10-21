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
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.fab.text = getString(com.lytree.qbank.R.string.action_compose)
        adapter.onClick = { paper ->
            startActivity(Intent(requireContext(), ExamActivity::class.java).apply {
                putExtra(ExamActivity.EXTRA_PAPER_ID, paper.id)
            })
        }
        binding.fab.setOnClickListener {
            startActivity(Intent(requireContext(), ComposeActivity::class.java))
        }
    }

    override fun refresh() {
        val list = db.listPapers()
        adapter.submit(list)
        binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.empty.setText(
            if (list.isEmpty()) {
                if (db.listBanks().isEmpty()) "还没有题库，先导入题库再组卷" else "还没有试卷，点右下角「组卷」开始"
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
