package com.lytree.qbank.data.backup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 备份编排：打包 → 上传 → 清理旧包；以及反向的下载 → 校验 → 落地。
 *
 * 打包与恢复的脏活都在 [BackupArchive]，这里只管顺序、进度和「留几份」。
 */
object BackupManager {

    /** 本地缓存里最多留几份，够手动分享就行，长期留存交给云端 */
    private const val LOCAL_KEEP = 2

    /** 自动备份生成的文件名前缀，清理时只认自己生成的，别删用户放进同目录的东西 */
    const val NAME_PREFIX = "qbank-backup-"

    data class Outcome(
        val localFile: File,
        val manifest: BackupArchive.Manifest,
        val uploaded: CloudFile?
    ) {
        val isCloud: Boolean get() = uploaded != null
    }

    fun localDir(context: Context): File =
        File(context.cacheDir, "backups").apply { if (!exists()) mkdirs() }

    fun newFileName(now: Long = System.currentTimeMillis()): String =
        NAME_PREFIX + BackupArchive.fileStamp(now) + ".zip"

    /**
     * 备份一次。[provider] 为空时只生成备份包（本地导出 / 分享），不上云。
     *
     * 同步执行，调用方自己放协程——但 API 做成 suspend 更省事，这里直接切 IO。
     */
    suspend fun runBackup(
        context: Context,
        provider: CloudProvider?,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Outcome = withContext(Dispatchers.IO) {
        val target = File(localDir(context), newFileName())
        val manifest = BackupArchive.create(context, target) { p, label ->
            onProgress(p * 0.55f, label)
        }

        var uploaded: CloudFile? = null
        if (provider != null) {
            if (!provider.isConfigured()) throw CloudException("云盘还没配置好")
            onProgress(0.58f, "上传到${provider.name}")
            uploaded = provider.upload(target) { p ->
                onProgress(0.58f + p * 0.38f, "上传中 ${(p * 100).toInt()}%")
            }
            CloudStore.markBackedUp(context, uploaded.name, uploaded.bytes)
            runCatching { trimCloud(provider, CloudStore.keepCount(context)) }
        }

        trimLocal(context)
        onProgress(1f, "备份完成")
        Outcome(target, manifest, uploaded)
    }

    /**
     * 从备份包恢复。中途失败时 [BackupArchive.install] 已把旧库放回原位，
     * 这里不再兜底——但调用方仍应在成功后重启进程，避免各页面拿着旧 id。
     */
    suspend fun restore(
        context: Context,
        zip: File,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): BackupArchive.Manifest = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "restore-work")
        val opened = BackupArchive.open(zip, temp)
        try {
            BackupArchive.install(context, opened) { p, label ->
                onProgress(p, label)
            }
            opened.manifest
        } finally {
            temp.deleteRecursively()
        }
    }

    /** 云端列表（只留本 App 生成的包），用于恢复页展示 */
    suspend fun listCloud(provider: CloudProvider): List<CloudFile> =
        withContext(Dispatchers.IO) {
            provider.list().filter { it.name.startsWith(NAME_PREFIX) }
        }

    /** 下载云端某一份到缓存，返回本地文件 */
    suspend fun downloadFromCloud(
        context: Context,
        provider: CloudProvider,
        remote: CloudFile,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "restore-src").apply { mkdirs() }
        val dest = File(dir, remote.name)
        if (dest.exists()) dest.delete()
        provider.download(remote, dest, onProgress)
        dest
    }

    // ---------------------------------------------------------------- 清理

    /** 云端只留最近 N 份（按文件名里的时间戳倒序，不走服务端排序，口径统一） */
    private suspend fun trimCloud(provider: CloudProvider, keep: Int) {
        val mine = provider.list()
            .filter { it.name.startsWith(NAME_PREFIX) }
            .sortedByDescending { it.name }
        mine.drop(keep.coerceAtLeast(1)).forEach { old ->
            runCatching { provider.delete(old) }
        }
    }

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
