package com.lytree.qbank.ui.backup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lytree.qbank.R
import com.lytree.qbank.data.backup.AutoBackupScheduler
import com.lytree.qbank.data.backup.BackupArchive
import com.lytree.qbank.data.backup.BackupManager
import com.lytree.qbank.data.backup.CloudFile
import com.lytree.qbank.data.backup.CloudStore
import com.lytree.qbank.data.backup.WebDavProvider
import com.lytree.qbank.databinding.FragmentBackupBinding
import com.lytree.qbank.ui.FbDialog
import com.lytree.qbank.ui.Refreshable
import com.lytree.qbank.ui.settings.SettingsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复。设置页的二级页。
 *
 * 三件事：把整库打成一个 zip、把 zip 分享出去（能直接存进任何网盘）、从 zip 恢复。
 * 云端是可选项——配置 WebDAV 之后能自动上传；不配也能用，走「分享 → 存网盘」这条路。
 */
class BackupFragment : Fragment(), Refreshable {

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!

    private val provider by lazy { WebDavProvider(requireContext()) }

    private var busy = false

    private val pickRestoreFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val name = displayName(uri)
            if (!name.endsWith(".zip", true)) {
                FbDialog.alert(requireContext(), "选中的不是 .zip 备份包。\n\n备份包文件名形如 qbank-backup-20261029-2230.zip。")
                return@registerForActivityResult
            }
            confirmRestore(name) {
                val temp = File(requireContext().cacheDir, "restore-src").apply { mkdirs() }
                val local = File(temp, name)
                val ok = runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        local.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("读不到这个文件")
                }.isSuccess
                if (!ok || !local.isFile || local.length() == 0L) {
                    FbDialog.alert(requireContext(), "这个文件读不出来，换个位置再选一次（或先复制到「下载」目录）。")
                    return@confirmRestore
                }
                restoreFromLocal(local)
            }
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

        binding.rowBackupNow.setOnClickListener { backupNow(cloud = false) }
        binding.rowShare.setOnClickListener { shareLatest() }
        binding.rowRestoreFile.setOnClickListener {
            pickRestoreFile.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        binding.rowRestoreCloud.setOnClickListener { pickCloudBackup() }
        binding.rowKeep.setOnClickListener { pickKeepCount() }

        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnUploadNow.setOnClickListener { backupNow(cloud = true) }

        binding.swAuto.setChecked(CloudStore.autoBackup(requireContext()))
        binding.swAuto.onCheckedChange = { checked ->
            saveWebDav()
            CloudStore.setAutoBackup(requireContext(), checked)
            AutoBackupScheduler.sync(requireContext())
            if (checked && !provider.isConfigured()) {
                FbDialog.alert(
                    requireContext(),
                    "自动备份需要先填好 WebDAV 地址与账号，否则到点也传不上去。\n\n没配云端也能用：点「立即备份」再「分享备份包」，手动存进百度网盘或天翼云盘。"
                )
            }
            render()
        }

        fillWebDav()
        render()
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        if (_binding == null) return
        val ctx = requireContext()
        val lastAt = CloudStore.lastBackupAt(ctx)
        binding.tvLastBackup.text = if (lastAt <= 0L) {
            "还没有备份过"
        } else {
            val size = CloudStore.lastBackupSize(ctx)
            val prefix = if (size > 0) "${formatTime(lastAt)} · ${BackupArchive.humanSize(size)}" else formatTime(lastAt)
            "$prefix · ${CloudStore.lastBackupName(ctx)}"
        }

        val keep = CloudStore.keepCount(ctx)
        binding.tvKeep.text = "$keep 份，超出自动删最旧的"

        val configured = provider.isConfigured()
        binding.tvRestoreCloudSub.text = if (configured) {
            "从 ${CloudStore.webdavDir(ctx)} 目录挑一份"
        } else {
            "先配好下面的 WebDAV 才能用"
        }
        binding.rowRestoreCloud.alpha = if (configured) 1f else 0.45f
        binding.btnUploadNow.alpha = if (configured) 1f else 0.45f
        binding.tvAutoSub.text = if (configured && CloudStore.autoBackup(ctx)) {
            "每天夜间自动传一份到云端"
        } else {
            "夜间闲时跑一次；没配 WebDAV 时不生效"
        }
    }

    private fun fillWebDav() {
        binding.inputUrl.setText(CloudStore.webdavUrl(requireContext()))
        binding.inputUser.setText(CloudStore.webdavUser(requireContext()))
        binding.inputPass.setText(CloudStore.webdavPass(requireContext()))
        binding.inputDir.setText(CloudStore.webdavDir(requireContext()))
    }

    private fun saveWebDav() {
        CloudStore.saveWebDav(
            requireContext(),
            binding.inputUrl.text?.toString().orEmpty(),
            binding.inputUser.text?.toString().orEmpty(),
            binding.inputPass.text?.toString().orEmpty(),
            binding.inputDir.text?.toString().orEmpty()
        )
    }

    // ---------------------------------------------------------------- 备份

    private fun backupNow(cloud: Boolean) {
        if (busy) return
        saveWebDav()
        if (cloud && !provider.isConfigured()) {
            FbDialog.alert(requireContext(), "还没填 WebDAV 地址与账号。\n\n只想把备份存进百度网盘/天翼云盘的话，用「立即备份」再「分享备份包」。")
            return
        }
        runTask(if (cloud) "备份到云端" else "备份中") { progress ->
            val outcome = BackupManager.runBackup(
                requireContext(),
                if (cloud) provider else null,
                progress
            )
            val counts = outcome.manifest.counts
            val detail = buildString {
                append("试卷 ${counts["papers"] ?: 0} 份 · 题目 ${counts["questions"] ?: 0} 道")
                append(" · 记录 ${counts["records"] ?: 0} 次")
                if (outcome.manifest.imageCount > 0) append(" · 配图 ${outcome.manifest.imageCount} 张")
                append("\n${BackupArchive.humanSize(outcome.localFile.length())}")
            }
            if (outcome.isCloud) {
                FbDialog.alert(requireContext(), "已上传到云端。\n\n$detail", title = "备份完成")
            } else {
                FbDialog.confirm(
                    requireContext(),
                    "备份包已生成。\n\n$detail",
                    okText = "分享 / 存到网盘",
                    cancelText = "知道了",
                    onOk = { shareFile(outcome.localFile) }
                )
            }
        }
    }

    private fun shareLatest() {
        val list = BackupManager.localBackups(requireContext())
        if (list.isEmpty()) {
            FbDialog.confirm(
                requireContext(),
                "本地还没有备份包，先备一份？",
                okText = "立即备份",
                onOk = { backupNow(cloud = false) }
            )
            return
        }
        shareFile(list.first())
    }

    private fun shareFile(file: File) {
        val ctx = requireContext()
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "把备份包存到"))
    }

    // ---------------------------------------------------------------- 恢复

    private fun pickCloudBackup() {
        saveWebDav()
        if (!provider.isConfigured()) {
            FbDialog.alert(requireContext(), "先填好 WebDAV 地址与账号。\n\n也可以把网盘里的 zip 下载到手机，再用「从备份文件恢复」。")
            return
        }
        runTask("读取云端列表") { _ ->
            val list = BackupManager.listCloud(provider)
            withContext(Dispatchers.Main) {
                if (list.isEmpty()) {
                    FbDialog.alert(requireContext(), "云端 ${CloudStore.webdavDir(requireContext())} 目录里还没有备份。")
                } else {
                    showCloudPicker(list)
                }
            }
        }
    }

    private fun showCloudPicker(list: List<CloudFile>) {
        val labels = list.map {
            "${it.name.replace(BackupManager.NAME_PREFIX, "").removeSuffix(".zip")}  ·  ${it.sizeText}"
        }
        FbDialog.list(requireContext(), "选择一份云端备份", labels.toTypedArray()) { index ->
            val picked = list.getOrNull(index) ?: return@list
            confirmRestore(picked.name) { restoreFromCloud(picked) }
        }
    }

    private fun confirmRestore(source: String, onConfirm: () -> Unit) {
        FbDialog.confirm(
            requireContext(),
            "将用这份备份覆盖当前所有试卷、作答记录、笔迹与设置：\n\n$source\n\n当前数据库会留一份 .before-restore 兜底，但仍建议先自己备份一次。",
            okText = "覆盖并恢复",
            onOk = { onConfirm() }
        )
    }

    /** 本地 zip 直接落地 */
    private fun restoreFromLocal(zip: File) {
        runTask("恢复中") { progress ->
            val manifest = BackupManager.restore(requireContext(), zip) { p, label ->
                progress(p, label)
            }
            announceRestored(manifest)
        }
    }

    /** 云端先下载再落地，两段进度接成一条 */
    private fun restoreFromCloud(file: CloudFile) {
        runTask("恢复中") { progress ->
            val local = BackupManager.downloadFromCloud(
                requireContext(), provider, file
            ) { p -> progress(p * 0.5f, "下载中 ${(p * 100).toInt()}%") }
            val manifest = BackupManager.restore(requireContext(), local) { p, label ->
                progress(0.5f + p * 0.5f, label)
            }
            announceRestored(manifest)
        }
    }

    private suspend fun announceRestored(manifest: BackupArchive.Manifest) {
        val counts = manifest.counts
        withContext(Dispatchers.Main) {
            val dialog = FbDialog.alert(
                requireContext(),
                "已恢复到 ${formatTime(manifest.createdAt)} 的备份。\n\n" +
                    "试卷 ${counts["papers"] ?: 0} 份 · 题目 ${counts["questions"] ?: 0} 道 · " +
                    "记录 ${counts["records"] ?: 0} 次\n\n" +
                    "App 需要重启一次才能读到新数据。",
                title = "恢复完成",
                okText = "重启",
                onOk = { restartApp() }
            )
            // 不能点外部关掉：旧页面还拿着已关闭的数据库句柄，不重启会到处报错
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
        }
    }

    private fun restartApp() {
        val ctx = requireContext()
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        Process.killProcess(Process.myPid())
    }

    // ---------------------------------------------------------------- 云端

    private fun testConnection() {
        saveWebDav()
        if (!provider.isConfigured()) {
            FbDialog.alert(requireContext(), "地址和账号先填上")
            return
        }
        runTask("连接中") { _ ->
            val msg = provider.test()
            withContext(Dispatchers.Main) {
                FbDialog.alert(requireContext(), msg, title = "连接成功")
            }
        }
    }

    private fun pickKeepCount() {
        val options = intArrayOf(3, 5, 7, 10, 15, 30)
        val current = CloudStore.keepCount(requireContext())
        val labels = options.map { if (it == current) "$it 份（当前）" else "$it 份" }.toTypedArray()
        FbDialog.list(requireContext(), "云端最多留几份", labels) { index ->
            CloudStore.saveKeepCount(requireContext(), options[index])
            render()
        }
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

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    private fun backToSettings() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, SettingsFragment())
            .commit()
    }

    override fun refresh() = Unit

    override fun onPause() {
        super.onPause()
        if (_binding != null) saveWebDav()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
