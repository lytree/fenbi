package com.lytree.qbank.ui.backup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lytree.qbank.R
import com.lytree.qbank.data.backup.BackupArchive
import com.lytree.qbank.data.backup.BackupHistory
import com.lytree.qbank.data.backup.BackupManager
import com.lytree.qbank.databinding.FragmentBackupHistoryBinding
import com.lytree.qbank.databinding.ItemBackupHistoryBinding
import com.lytree.qbank.ui.FbDialog
import com.lytree.qbank.ui.Refreshable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 备份历史：备份与恢复页的二级页，把每次备份都列出来。
 *
 * 为什么单独一页：备份页已经背着备份 / 恢复两段，历史铺进去会把「立即备份」
 * 顶到看不见的地方；而且历史要能翻到几十条、要能一条条收拾，卡片里放不下。
 *
 * 每条点开是动作菜单（恢复 / 分享 / 删记录），恢复仍走 [RestoreSession] 那套
 * 「校验 → 预览 → 确认覆盖」——历史只是入口，不提供绕过确认的捷径。
 */
class BackupHistoryFragment : Fragment(), Refreshable {

    private var _binding: FragmentBackupHistoryBinding? = null
    private val binding get() = _binding!!

    private var busy = false

    private val restore by lazy {
        RestoreSession(
            fragment = this,
            runTask = { title, block -> runTask(title, block) },
            isAlive = { _binding != null }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBackupHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.titleBar.usePageTitleStyle()
        binding.titleBar.setTitle("备份历史")
        binding.titleBar.showBack(true)
        binding.titleBar.onBack = { if (!busy) backToBackup() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (!busy) backToBackup()
        }

        render()
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        if (_binding == null) return
        val box = binding.listBox
        box.removeAllViews()

        val entries = BackupHistory.all(requireContext())
        val hasAny = entries.isNotEmpty()
        binding.tvGroup.text = if (hasAny) "备份记录 · ${entries.size} 条" else "备份记录"
        binding.tvEmpty.visibility = if (hasAny) View.GONE else View.VISIBLE

        // 标题栏右侧「清空」跟着有没有记录走：空列表上摆一个清空按钮没意义
        binding.titleBar.clearActions()
        if (hasAny) binding.titleBar.addTextAction("清空") { askClear() }

        entries.forEachIndexed { index, entry ->
            if (index > 0) box.addView(rowDivider())
            box.addView(historyRow(entry))
        }
    }

    private fun historyRow(entry: BackupHistory.Entry): View {
        val row = ItemBackupHistoryBinding.inflate(layoutInflater, binding.listBox, false)
        row.tvTitle.text = backupTimeText(entry.at)
        row.tvSub.text = describe(entry)
        row.root.setOnClickListener { showActions(entry) }
        return row.root
    }

    private fun describe(entry: BackupHistory.Entry): String = buildString {
        append(if (entry.auto) "自动" else "手动")
        append(" · ")
        append(BackupArchive.humanSize(entry.bytes))
        if (entry.papers > 0 || entry.questions > 0) {
            append(" · 试卷 ${entry.papers} / 题 ${entry.questions}")
        }
        if (entry.records > 0) append(" · 记录 ${entry.records}")
    }

    /** 卡片内的缩进细线，与布局里那些 FbDividerInset 同款（动态加的 View 吃不到 style） */
    private fun rowDivider(): View {
        val ctx = requireContext()
        return View(ctx).apply {
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.fb_divider))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 0.5f).toInt().coerceAtLeast(1)
            ).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.fb_card_pad)
            }
        }
    }

    // ---------------------------------------------------------------- 动作

    private fun showActions(entry: BackupHistory.Entry) {
        val local = localPackage(entry)
        val actions = ArrayList<String>(3).apply {
            add("恢复这份备份")
            if (local != null) add("分享备份包")
            add("删除这条记录")
        }
        FbDialog.list(
            requireContext(),
            title = "${backupTimeText(entry.at)} 的备份",
            items = actions.toTypedArray()
        ) { index ->
            when (actions.getOrNull(index)) {
                "恢复这份备份" -> restoreEntry(entry)
                "分享备份包" -> local?.let { shareBackupFile(this, it) }
                "删除这条记录" -> forget(entry)
            }
        }
    }

    private fun localPackage(entry: BackupHistory.Entry): File? =
        BackupManager.localBackups(requireContext()).firstOrNull { it.name == entry.name }

    /**
     * 本机还留着包就直接用。包被「本地只留 2 份」清掉就只能重备了——
     * 恢复仍落到 [RestoreSession] 的「解包校验 → 预览 → 覆盖」上。
     */
    private fun restoreEntry(entry: BackupHistory.Entry) {
        if (busy) return
        val ctx = requireContext()

        localPackage(entry)?.let {
            restore.fromLocal(ctx, it)
            return
        }
        FbDialog.alert(
            ctx,
            "这份的备份包已经随「本地只留 2 份」清掉了，找不回来。\n\n想留得住的：备份后点「分享」存进网盘；恢复时从网盘下载回来，用「从备份文件导入」。"
        )
    }

    private fun forget(entry: BackupHistory.Entry) {
        val ctx = requireContext()
        FbDialog.confirm(
            ctx,
            "只从列表里删掉这条记录，备份包本身不动。",
            okText = "删除记录",
            onOk = {
                BackupHistory.remove(ctx, entry.name)
                render()
            }
        )
    }

    private fun askClear() {
        val ctx = requireContext()
        FbDialog.confirm(
            ctx,
            "清空备份历史？\n\n只删记录，备份包一份都不会动。",
            okText = "清空",
            onOk = {
                BackupHistory.clear(ctx)
                render()
            }
        )
    }

    // ---------------------------------------------------------------- 通用

    private fun runTask(
        title: String,
        block: suspend (progress: (Float, String) -> Unit) -> Unit
    ) {
        if (busy) return
        busy = true
        binding.progressBox.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvProgress.text = title

        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val post: (Float, String) -> Unit = { p, label ->
            main.post {
                if (_binding != null) {
                    binding.progressBar.progress = (p.coerceIn(0f, 1f) * 100).toInt()
                    binding.tvProgress.text = label
                }
            }
        }

        lifecycleScope.launch {
            try {
                block(post)
            } catch (e: Throwable) {
                if (_binding != null) FbDialog.alert(requireContext(), e.message ?: "操作失败")
            } finally {
                withContext(Dispatchers.Main) {
                    _binding?.progressBox?.visibility = View.GONE
                    busy = false
                    render()
                }
            }
        }
    }

    private fun backToBackup() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, BackupFragment())
            .commit()
    }

    override fun refresh() = Unit

    override fun onDestroyView() {
        restore.discard()
        super.onDestroyView()
        _binding = null
    }
}
