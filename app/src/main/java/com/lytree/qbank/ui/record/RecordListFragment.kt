package com.lytree.qbank.ui.record

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.databinding.FragmentListBinding
import com.lytree.qbank.ui.Refreshable
import com.lytree.qbank.ui.ReportActivity
import com.lytree.qbank.ui.settings.SettingsFragment

/**
 * 练习记录。
 *
 * 设置（判题规则 / 笔迹颜色）从底部导航挪到本页右上角齿轮进入——设置是低频配置，
 * 占一个一级 Tab 不划算，腾出的位置留给真正高频的页面。
 */
class RecordListFragment : Fragment(), Refreshable {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val adapter = RecordAdapter()
    private lateinit var db: QBankDb

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = QBankDb.get(requireContext())
        binding.titleBar.usePageTitleStyle()
        binding.titleBar.setTitle(getString(R.string.page_records))

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        adapter.onClick = { record ->
            startActivity(Intent(requireContext(), ReportActivity::class.java).apply {
                putExtra(ReportActivity.EXTRA_RECORD_ID, record.id)
            })
        }
        binding.emptyAction.visibility = View.GONE

        binding.titleBar.addIconAction(
            R.drawable.ic_fb_settings,
            getString(R.string.action_settings)
        ) { openSettings() }
    }

    /** 设置页不占底部导航，直接换掉本页内容；底部仍高亮「记录」 */
    private fun openSettings() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, SettingsFragment())
            .commit()
    }

    override fun refresh() {
        val list = db.listRecords()
        adapter.submit(list)
        binding.emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.empty.setText(if (list.isEmpty()) "交卷后这里会留下每场练习的成绩与用时" else "")
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
