package com.lytree.qbank.ui.record

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.databinding.FragmentListBinding
import com.lytree.qbank.ui.ExamActivity
import com.lytree.qbank.ui.FbDialog
import com.lytree.qbank.ui.Refreshable
import com.lytree.qbank.ui.ReportActivity
import com.lytree.qbank.ui.settings.SettingsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 练习记录。
 *
 * **一进答题页就留痕**：记录不再等交卷才生成，所以这一页里既有「已完成」（可看成绩与用时），
 * 也有「未完成」（点一下回答题页接着做）。长按可删掉某一条。
 *
 * 设置（判题规则 / 笔迹颜色）从底部导航挪到本页右上角齿轮进入——设置是低频配置，
 * 占一个一级 Tab 不划算，腾出的位置留给真正高频的页面。
 */
class RecordListFragment : Fragment(), Refreshable {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val adapter = RecordAdapter()
    private lateinit var db: QBankDb
    /** 列表刷新任务：连续刷新时取消上一次，避免旧结果后到覆盖新结果 */
    private var refreshJob: Job? = null

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
        adapter.onClick = { record -> open(record) }
        adapter.onActions = { record -> showActions(record) }
        binding.emptyAction.visibility = View.GONE

        binding.titleBar.addIconAction(
            R.drawable.ic_fb_settings,
            getString(R.string.action_settings)
        ) { openSettings() }
    }

    /**
     * 行尾「⋯」（或长按）：看 / 继续 / 删。
     *
     * 删除放在最后一项、并且要二次确认——记录里带的是这一场的作答、成绩和笔迹，
     * 删了没有回收站。
     */
    private fun showActions(record: ExamRecord) {
        val items = arrayOf(
            getString(if (record.isDone) R.string.record_action_open else R.string.record_action_resume),
            getString(R.string.action_delete)
        )
        FbDialog.list(
            context = requireContext(),
            title = record.displayTitle,
            items = items,
            onPick = { which ->
                if (which == 0) open(record) else confirmDelete(record)
            }
        )
    }

    /**
     * 已完成 → 成绩报告（得分 / 正确率 / 用时 / 逐题解析与每题用时）；
     * 未完成 → 回答题页，进去会问「继续答题 / 重新开始」，倒计时从上次退出的地方接着走。
     */
    private fun open(record: ExamRecord) {
        if (record.isDone) {
            startActivity(Intent(requireContext(), ReportActivity::class.java).apply {
                putExtra(ReportActivity.EXTRA_RECORD_ID, record.id)
            })
            return
        }
        if (record.paperId <= 0L) {
            FbDialog.alert(requireContext(), "这份试卷已经删掉了，这条记录只看得到当时做了多少题。")
            return
        }
        // 卷是否存在要查库：放后台查，回来再开页面；本页视图销毁后结果直接丢弃
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val exists = withContext(Dispatchers.IO) { db.getPaper(record.paperId) != null }
            if (exists) {
                startActivity(Intent(ctx, ExamActivity::class.java).apply {
                    putExtra(ExamActivity.EXTRA_PAPER_ID, record.paperId)
                    putExtra(ExamActivity.EXTRA_PAPER_NAME, record.displayTitle)
                })
            } else {
                FbDialog.alert(ctx, "这份试卷已经删掉了，这条记录只看得到当时做了多少题。")
            }
        }
    }

    private fun confirmDelete(record: ExamRecord) {
        FbDialog.confirm(
            context = requireContext(),
            message = getString(R.string.record_delete_confirm, record.displayTitle),
            okText = getString(R.string.action_delete),
            onOk = {
                // 未完成的记录删掉等于放弃这一轮：连进度一起清，别让下次进卷又弹「继续答题」
                if (!record.isDone) db.clearProgress(record.paperId)
                db.deleteRecord(record.id)
                refresh()
                Toast.makeText(requireContext(), R.string.record_deleted, Toast.LENGTH_SHORT).show()
            }
        )
    }

    /** 设置页不占底部导航，直接换掉本页内容；底部仍高亮「记录」 */
    private fun openSettings() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, SettingsFragment())
            .commit()
    }

    override fun refresh() {
        // 记录列表每次回到本页都要刷：查询放后台，主线程只更新视图
        if (_binding == null) return
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { db.listRecords() }
            if (_binding == null) return@launch
            adapter.submit(list)
            binding.emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.empty.setText(if (list.isEmpty()) getString(R.string.empty_records_hint) else "")
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
