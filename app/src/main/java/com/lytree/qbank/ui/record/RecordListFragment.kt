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
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.data.model.PaperRef
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
 * 记录只增不减（每进一次卷就留一条），列表**分页往下取**（每页 [RecordAdapter.PAGE_SIZE] 条），
 * 滚到接近底部自动补下一页，不一次读全表。
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

    /** 已经装进列表的条数（= 下一页的 offset），刷新时按它重新取，不跳回第一页 */
    private var loaded = 0
    /** 库里一共多少条：判断还有没有下一页 */
    private var total = 0
    /** 正在取下一页，挡住滚动回调的重复请求 */
    private var loadingMore = false

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

        // 往下滚到离底部还剩几条时就取下一页：真滚到底时下一页多半已经拼上了
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return // 只在下滑时取，往回翻不触发
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - PREFETCH) loadMore()
            }
        })

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
     * 已完成 → 成绩报告（得分 / 正确率 / 用时 / 全部题目对错一览，错题解析与全部解析按需展开）；
     * 未完成 → 回答题页，进去会问「继续答题 / 重新开始」，倒计时从上次退出的地方接着走。
     */
    private fun open(record: ExamRecord) {
        if (record.isDone) {
            startActivity(Intent(requireContext(), ReportActivity::class.java).apply {
                putExtra(ReportActivity.EXTRA_RECORD_ID, record.id)
            })
            return
        }
        if (!PaperRef.isValid(record.paperId)) {
            FbDialog.alert(requireContext(), "这份试卷已经删掉了，这条记录只看得到当时做了多少题。")
            return
        }
        // 卷是否存在要查库：放后台查，回来再开页面；本页视图销毁后结果直接丢弃
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val exists = withContext(Dispatchers.IO) { db.getPaperOrGroup(record.paperId) != null }
            if (exists) {
                startActivity(Intent(ctx, ExamActivity::class.java).apply {
                    putExtra(ExamActivity.EXTRA_PAPER_ID, record.paperId)
                    // 卷名不带范围，范围另外带：答题页自己拼「卷名 · 资料分析」，
                    // 免得把记录标题里已经拼过一次的范围名再加一遍
                    putExtra(
                        ExamActivity.EXTRA_PAPER_NAME,
                        record.paperName.ifBlank { record.displayTitle }
                    )
                    putExtra(ExamActivity.EXTRA_SCOPE_KEY, record.scope)
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
                // 只清这条记录所属范围的那一份进度（整卷 / 某个模块各存一份）
                if (!record.isDone) db.clearProgress(record.paperId, record.scope)
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

    /**
     * 刷新：**保留已经翻出来的条数**，不跳回第一页。
     *
     * 从报告页 / 答题页回来一次就重置成 20 条，等于把翻到一半的位置扔掉；这里按
     * 「已经看到哪」重新取一遍，删掉一条也不会把视线甩回顶部。
     */
    override fun refresh() {
        if (_binding == null) return
        refreshJob?.cancel()
        val want = loaded.coerceAtLeast(RecordAdapter.PAGE_SIZE)
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                db.countRecords() to db.listRecords(want, 0)
            }
            if (_binding == null) return@launch
            total = page.first
            loaded = page.second.size
            loadingMore = false
            adapter.submit(page.second, hasMore = loaded < total)
            binding.emptyBox.visibility = if (page.second.isEmpty()) View.VISIBLE else View.GONE
            binding.empty.setText(
                if (page.second.isEmpty()) getString(R.string.empty_records_hint) else ""
            )
            // 一页没填满屏幕（平板 / 大屏）：先续取一页，否则尾部停着「上滑加载更多」却滑不动
            binding.list.post {
                if (_binding != null && !binding.list.canScrollVertically(1)) loadMore()
            }
        }
    }

    /** 取下一页：接在已加载的条数后面，取完了把 hasMore 交回适配器决定尾部写什么 */
    private fun loadMore() {
        if (_binding == null || loadingMore || loaded >= total) return
        loadingMore = true
        adapter.setLoading(true)
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val more = withContext(Dispatchers.IO) {
                db.listRecords(RecordAdapter.PAGE_SIZE, loaded)
            }
            loadingMore = false
            if (_binding == null) return@launch
            loaded += more.size
            // 取到空页（这期间记录被删了）也算到底：否则 loaded 不动会一直请求同一页
            adapter.append(more, hasMore = more.isNotEmpty() && loaded < total)
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

    private companion object {
        /** 还剩几条进入视野时就去取下一页（含尾部状态行所占的一行） */
        const val PREFETCH = 3
    }
}
