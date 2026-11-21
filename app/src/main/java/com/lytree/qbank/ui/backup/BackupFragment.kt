package com.lytree.qbank.ui.backup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lytree.qbank.R
import com.lytree.qbank.data.backup.BackupArchive
import com.lytree.qbank.data.backup.BackupHistory
import com.lytree.qbank.data.backup.BackupManager
import com.lytree.qbank.databinding.FragmentBackupBinding
import com.lytree.qbank.databinding.ItemBackupHistoryBinding
import com.lytree.qbank.ui.FbDialog
import com.lytree.qbank.ui.Refreshable
import com.lytree.qbank.ui.settings.SettingsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 备份与恢复。设置页的二级页。
 *
 * 三件事：把整库打成一个 zip、把 zip 分享出去（能直接存进任何网盘）、从 zip 恢复。
 * 备份包不出本机——想多一份保险就「分享」存进网盘，恢复时从文件选择器选回来。
 *
 * 历史记录另开一页（[BackupHistoryFragment]）：这一页本来就长，历史铺进来会把
 * 「立即备份」顶到看不见的地方；而且历史要能翻、要能清，独立页面才装得下。
 */
class BackupFragment : Fragment(), Refreshable {

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!

    private var busy = false

    /** 恢复链路（校验 → 预览 → 覆盖）与历史页共用一份实现，进度条仍走本页 */
    private val restore by lazy {
        RestoreSession(
            fragment = this,
            runTask = { title, block -> runTask(title, block) },
            isAlive = { _binding != null }
        )
    }

    private val pickRestoreFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val name = displayName(uri)
            if (!name.endsWith(".zip", true)) {
                FbDialog.alert(requireContext(), "选中的不是 .zip 备份包。\n\n备份包文件名形如 qbank-backup-20261029-2230.zip。")
                return@registerForActivityResult
            }
            // 先落到缓存，再交给「校验 → 预览 → 覆盖」那条链路
            val temp = File(requireContext().cacheDir, "restore-src").apply { mkdirs() }
            val local = File(temp, name)
            val ok = runCatching {
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    local.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("读不到这个文件")
            }.isSuccess
            if (!ok || !local.isFile || local.length() == 0L) {
                FbDialog.alert(requireContext(), "这个文件读不出来，换个位置再选一次（或先复制到「下载」目录）。")
                return@registerForActivityResult
            }
            restore.fromLocal(requireContext(), local)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBackupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.titleBar.usePageTitleStyle()
        binding.titleBar.setTitle("备份与恢复")
        binding.titleBar.showBack(true)
        binding.titleBar.onBack = { if (!busy) backToSettings() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            // 备份/恢复跑到一半别让页面退出：恢复是「换文件」，退出会打断流程
            if (!busy) backToSettings()
        }

        binding.rowBackupNow.setOnClickListener { backupNow() }
        binding.rowShare.setOnClickListener { shareLatest() }
        binding.rowHistory.setOnClickListener { openHistory() }
        binding.rowImport.setOnClickListener {
            pickRestoreFile.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }

        render()
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        if (_binding == null) return

        // 「上次备份」直接取最新一条历史：历史上每次成功备份都记了，
        // 比单独记一份 prefs 少一个会打架的来源
        val history = BackupHistory.all(requireContext())
        binding.tvLastBackup.text = history.firstOrNull()?.let {
            "${backupTimeText(it.at)} · ${BackupArchive.humanSize(it.bytes)} · ${it.name}"
        } ?: "还没有备份过"

        binding.tvHistorySub.text = if (history.isEmpty()) {
            "还没有备份记录"
        } else {
            "共 ${history.size} 条 · 最近 ${backupTimeText(history.first().at)}"
        }

        renderRestore(history)
    }

    /**
     * 恢复区直接把「手上能恢复的包」列出来，点一下就走预览确认——
     * 不再让用户先点一行、再从弹层里挑。本机只留 2 份，列出来也就这么长。
     *
     * 条目信息优先取历史（时间 / 条数都在里面），历史里没有的（清过历史）就退回文件时间与体积。
     */
    private fun renderRestore(history: List<BackupHistory.Entry>) {
        val box = binding.restoreBox
        box.removeAllViews()

        val local = BackupManager.localBackups(requireContext())
        binding.tvRestoreEmpty.visibility = if (local.isEmpty()) View.VISIBLE else View.GONE

        val byName = history.associateBy { it.name }
        local.forEachIndexed { index, file ->
            if (index > 0) box.addView(rowDivider())
            box.addView(restoreRow(file, byName[file.name]))
        }
    }

    private fun restoreRow(file: File, entry: BackupHistory.Entry?): View {
        val row = ItemBackupHistoryBinding.inflate(layoutInflater, binding.restoreBox, false)
        row.tvTitle.text = backupTimeText(entry?.at ?: file.lastModified())
        row.tvSub.text = buildString {
            append(BackupArchive.humanSize(file.length()))
            if (entry != null) {
                if (entry.papers > 0 || entry.questions > 0) {
                    append(" · 试卷 ${entry.papers} / 题 ${entry.questions}")
                }
                if (entry.records > 0) append(" · 记录 ${entry.records}")
            }
            append(" · ")
            append(file.name)
        }
        row.root.setOnClickListener { restore.fromLocal(requireContext(), file) }
        return row.root
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

    // ---------------------------------------------------------------- 备份

    private fun backupNow() {
        if (busy) return
        runTask("备份中") { progress ->
            val outcome = BackupManager.runBackup(requireContext(), onProgress = progress)
            val counts = outcome.manifest.counts
            val detail = buildString {
                append("试卷 ${counts["papers"] ?: 0} 份 · 题目 ${counts["questions"] ?: 0} 道")
                append(" · 记录 ${counts["records"] ?: 0} 次")
                if (outcome.manifest.imageCount > 0) append(" · 配图 ${outcome.manifest.imageCount} 张")
                append("\n${BackupArchive.humanSize(outcome.localFile.length())}")
            }
            FbDialog.confirm(
                requireContext(),
                "备份包已生成。\n\n$detail",
                okText = "分享 / 存到网盘",
                cancelText = "知道了",
                onOk = { shareBackupFile(this, outcome.localFile) }
            )
        }
    }

    private fun shareLatest() {
        val list = BackupManager.localBackups(requireContext())
        if (list.isEmpty()) {
            FbDialog.confirm(
                requireContext(),
                "本地还没有备份包，先备一份？",
                okText = "立即备份",
                onOk = { backupNow() }
            )
            return
        }
        shareBackupFile(this, list.first())
    }

    // ---------------------------------------------------------------- 历史

    private fun openHistory() {
        if (busy) return
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, BackupHistoryFragment())
            .commit()
    }

    // ---------------------------------------------------------------- 通用

    /** 统一的任务外壳：显示进度 → 执行 → 收起 → 出错弹提示 */
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

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "backup.zip"
        runCatching {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
        }
        return name
    }

    private fun backToSettings() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, SettingsFragment())
            .commit()
    }

    override fun refresh() = Unit

    override fun onDestroyView() {
        // 预览还没确认就离开页面：解包目录是临时的，跟着清掉
        restore.discard()
        super.onDestroyView()
        _binding = null
    }
}
