package com.lytree.qbank.ui.backup

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.lytree.qbank.data.backup.BackupArchive
import com.lytree.qbank.data.backup.BackupManager
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.ui.FbDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 恢复流程：解包校验 → 预览确认 → 覆盖落地。
 *
 * 备份页和历史页两个入口共用这一份。恢复是全 App 唯一会「把整库换掉」的动作，
 * 各写一遍早晚会走岔（一处补了校验、另一处没补），所以统一收在这里；
 * 进度条 / 任务外壳由页面通过 [runTask] 注入，页面各自有自己的布局。
 */
class RestoreSession(
    private val fragment: Fragment,
    /** 页面自己的「显示进度 → 执行 → 收起」外壳 */
    private val runTask: (String, suspend ((Float, String) -> Unit) -> Unit) -> Unit,
    /** 页面还活着吗（视图在不在），用来决定解包完是预览还是直接丢掉 */
    private val isAlive: () -> Boolean
) {

    /** 预览中、等用户点「覆盖并恢复」的那份；取消或落地完就清空 */
    private var pending: BackupArchive.Opened? = null

    /** 本机 zip：先解包校验，再把包里的内容摆给用户看 */
    fun fromLocal(ctx: Context, zip: File) {
        runTask("检查备份包") { _ -> openAndPreview(ctx, zip) }
    }

    /** 预览还没确认就离开页面：解包目录是临时的，跟着清掉 */
    fun discard() {
        pending?.let { BackupManager.discardOpened(it) }
        pending = null
    }

    private suspend fun openAndPreview(ctx: Context, zip: File) {
        val opened = BackupManager.openForRestore(ctx, zip)
        withContext(Dispatchers.Main) {
            if (isAlive()) showPreview(ctx, opened) else BackupManager.discardOpened(opened)
        }
    }

    /**
     * 覆盖前把包里有什么摊开：什么时候备的、来自哪台机器、多少卷多少题、库版本会不会被降级。
     *
     * 校验在 [openAndPreview] 里就做完了，包坏了、来自更新版本、传输截断都到不了这一步。
     */
    private fun showPreview(ctx: Context, opened: BackupArchive.Opened) {
        val m = opened.manifest
        val counts = m.counts
        pending = opened

        val detail = buildString {
            append("备份时间：${backupTimeText(m.createdAt)}")
            append("\n内容：试卷 ${counts["papers"] ?: 0} 份 · 题目 ${counts["questions"] ?: 0} 道")
            append(" · 记录 ${counts["records"] ?: 0} 次")
            if (m.imageCount > 0) append(" · 配图 ${m.imageCount} 张")
            val bytes = if (m.totalBytes > 0) m.totalBytes else opened.dbFile.length()
            append("\n体积：${BackupArchive.humanSize(bytes)}")
            if (m.device.isNotBlank() || m.versionName.isNotBlank()) {
                append("\n来源：")
                append(m.device.ifBlank { "未知设备" })
                if (m.versionName.isNotBlank()) append(" · App ${m.versionName}")
            }
            append("\n库版本：v${m.dbVersion}")
            if (m.dbVersion < QBankDb.DB_VERSION) {
                append("（本机 v${QBankDb.DB_VERSION}，恢复后打开时自动升级）")
            }
            append("\n\n覆盖后，当前所有试卷、作答记录、笔迹与设置都会被这份备份替换。")
        }

        var applied = false
        val dialog = FbDialog.confirm(
            ctx,
            detail,
            okText = "覆盖并恢复",
            cancelText = "取消",
            onOk = {
                applied = true
                apply(ctx, opened)
            }
        )
        dialog.setOnDismissListener {
            // 点「覆盖并恢复」时 dismiss 先于 onOk 触发，这条判断得推到队尾，
            // 否则会把刚要落地的解包目录删掉
            Handler(Looper.getMainLooper()).post {
                if (!applied && pending === opened) {
                    pending = null
                    BackupManager.discardOpened(opened)
                }
            }
        }
    }

    /** 用户确认后才真正换库；排到下一帧，避开上一个任务收尾时还没复位的 busy */
    private fun apply(ctx: Context, opened: BackupArchive.Opened) {
        pending = null
        Handler(Looper.getMainLooper()).post {
            if (!isAlive()) {
                BackupManager.discardOpened(opened)
                return@post
            }
            runTask("恢复中") { progress ->
                val manifest = BackupManager.installOpened(ctx, opened) { p, label ->
                    progress(p, label)
                }
                announce(ctx, manifest)
            }
        }
    }

    private suspend fun announce(ctx: Context, manifest: BackupArchive.Manifest) {
        val counts = manifest.counts
        withContext(Dispatchers.Main) {
            val dialog = FbDialog.alert(
                ctx,
                "已恢复到 ${backupTimeText(manifest.createdAt)} 的备份。\n\n" +
                    "试卷 ${counts["papers"] ?: 0} 份 · 题目 ${counts["questions"] ?: 0} 道 · " +
                    "记录 ${counts["records"] ?: 0} 次\n\n" +
                    "App 需要重启一次才能读到新数据。",
                title = "恢复完成",
                okText = "重启",
                onOk = { restartApp(ctx) }
            )
            // 不能点外部关掉：旧页面还拿着已关闭的数据库句柄，不重启会到处报错
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
        }
    }

    private fun restartApp(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        Process.killProcess(Process.myPid())
    }
}

/** 备份相关时间统一到分钟，够用且不占宽度 */
internal fun backupTimeText(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/** 把备份包交给系统选择器（存网盘 / 发给自己），备份页与历史页共用 */
internal fun shareBackupFile(fragment: Fragment, file: File) {
    val ctx = fragment.requireContext()
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    fragment.startActivity(Intent.createChooser(send, "把备份包存到"))
}
