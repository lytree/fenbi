package com.lytree.qbank.ui.bank

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.databinding.FragmentListBinding
import com.lytree.qbank.ui.BankDetailActivity
import com.lytree.qbank.ui.ImportActivity
import com.lytree.qbank.ui.MainActivity
import com.lytree.qbank.ui.Refreshable

class BankListFragment : Fragment(), Refreshable {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val adapter = BankAdapter()
    private lateinit var db: QBankDb

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = QBankDb.get(requireContext())
        binding.titleBar.usePageTitleStyle()
        binding.titleBar.setTitle(getString(com.lytree.qbank.R.string.page_banks))
        binding.titleBar.addTextAction(getString(com.lytree.qbank.R.string.action_import)) { openImport() }

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        adapter.onClick = { bank ->
            startActivity(Intent(requireContext(), BankDetailActivity::class.java).apply {
                putExtra(BankDetailActivity.EXTRA_BANK_ID, bank.id)
                putExtra(BankDetailActivity.EXTRA_BANK_NAME, bank.name)
            })
        }
        binding.emptyAction.setText(com.lytree.qbank.R.string.action_import)
        binding.emptyAction.setOnClickListener { openImport() }
    }

    private fun openImport() {
        (activity as? MainActivity)?.openImport()
            ?: startActivity(Intent(requireContext(), ImportActivity::class.java))
    }

    override fun refresh() {
        val list = db.listBanks()
        adapter.submit(list)
        binding.emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyAction.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.empty.setText(if (list.isEmpty()) "还没有题库，导入一份 JSON / CSV / Excel / TXT 就能开始刷题" else "")
        binding.emptyAction.isEnabled = list.isEmpty()
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
