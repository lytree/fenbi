package com.lytree.qbank.data.backup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 备份编排：打包 → 落地 → 清理旧包；以及反向的校验 → 落地。
 *
 * 打包与恢复的脏活都在 [BackupArchive]，这里只管顺序、进度和「本地留几份」。
 * 备份包不出本机：想存网盘就走「分享备份包」，恢复从文件选择器选 zip。
 */
object BackupManager {

    /** 本地缓存里最多留几份，够手动分享就行 */
    private const val LOCAL_KEEP = 2

    /** 恢复时解包用的临时目录（cache 下），预览阶段也用它 */
    private const val RESTORE_WORK = "restore-work"

    /** 自动备份生成的文件名前缀，清理时只认自己生成的，别删用户放进同目录的东西 */
    const val NAME_PREFIX = "qbank-backup-"

    data class Outcome(
        val localFile: File,
        val manifest: BackupArchive.Manifest
    )

    fun localDir(context: Context): File =
        File(context.cacheDir, "backups").apply { if (!exists()) mkdirs() }

    fun newFileName(now: Long = System.currentTimeMillis()): String =
        NAME_PREFIX + BackupArchive.fileStamp(now) + ".zip"

    /**
     * 备份一次：打包落在本机缓存，不上传任何地方。
     * [auto] 留给以后可能的自动备份（现在只有手动入口），只影响历史里的来源标记。
     *
     * 同步执行，调用方自己放协程——但 API 做成 suspend 更省事，这里直接切 IO。
     */
    suspend fun runBackup(
        context: Context,
        auto: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Outcome = withContext(Dispatchers.IO) {
        val target = File(localDir(context), newFileName())
        val manifest = BackupArchive.create(context, target) { p, label ->
            onProgress(p, label)
        }

        trimLocal(context)
        // 历史跟文件是两回事：本地包会被 trimLocal 删掉，记录留着，用户才知道哪天备过
        runCatching {
            BackupHistory.record(
                context,
                BackupHistory.Entry(
                    name = target.name,
                    at = manifest.createdAt,
                    // 记 zip 的实际体积，跟 manifest.totalBytes（解包后的内容量）不是一个口径，
                    // 混着显示会被当成两种东西
                    bytes = target.length(),
                    auto = auto,
                    papers = manifest.counts["papers"] ?: 0,
                    questions = manifest.counts["questions"] ?: 0,
                    records = manifest.counts["records"] ?: 0,
                    device = manifest.device,
                    version = manifest.versionName
                )
            )
        }
        onProgress(1f, "备份完成")
        Outcome(target, manifest)
    }

    /**
     * 只解包 + 校验，**不碰本机数据**——给「恢复前预览」用。
     *
     * 校验（app 标记 / schema / MD5 / 库版本）在这一步就做完，包坏了、来自更新版本、
     * 传输被截断都会当场抛 [BackupArchive.BackupException]，用户不会等到覆盖一半才发现。
     * 解开的内容留在 cache 的 `restore-work` 里，交给 [installOpened] 落地；
     * 用户看完取消就调 [discardOpened] 清掉。
     */
    suspend fun openForRestore(context: Context, zip: File): BackupArchive.Opened =
        withContext(Dispatchers.IO) {
            val temp = File(context.cacheDir, RESTORE_WORK)
            BackupArchive.open(zip, temp)
        }

    /**
     * 把 [openForRestore] 解开的包换进本机，完事清掉临时目录。
     *
     * 中途失败时 [BackupArchive.install] 已把旧库放回原位，这里不再兜底——
     * 但调用方仍应在成功后重启进程，避免各页面拿着旧 id。
     */
    suspend fun installOpened(
        context: Context,
        opened: BackupArchive.Opened,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): BackupArchive.Manifest = withContext(Dispatchers.IO) {
        try {
            BackupArchive.install(context, opened) { p, label ->
                onProgress(p, label)
            }
            opened.manifest
        } finally {
            opened.dir.deleteRecursively()
        }
    }

    /** 预览之后决定不恢复了：把解开的临时目录删掉 */
    fun discardOpened(opened: BackupArchive.Opened) {
        runCatching { opened.dir.deleteRecursively() }
    }

    /** 一步到位的恢复（解包 → 校验 → 落地），预览流程用 [openForRestore] + [installOpened] */
    suspend fun restore(
        context: Context,
        zip: File,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): BackupArchive.Manifest {
        val opened = openForRestore(context, zip)
        return installOpened(context, opened, onProgress)
    }

    // ---------------------------------------------------------------- 清理

    private fun trimLocal(context: Context) {
        localDir(context).listFiles { f -> f.isFile && f.name.startsWith(NAME_PREFIX) }
            ?.sortedByDescending { it.name }
            ?.drop(LOCAL_KEEP)
            ?.forEach { it.delete() }
    }

    /** 本地已有的备份包（分享 / 手动导出用） */
    fun localBackups(context: Context): List<File> =
        localDir(context).listFiles { f -> f.isFile && f.name.startsWith(NAME_PREFIX) }
            ?.sortedByDescending { it.name }
            ?.toList()
            ?: emptyList()
}
