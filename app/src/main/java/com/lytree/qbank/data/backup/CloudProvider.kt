package com.lytree.qbank.data.backup

import android.content.Context
import java.io.File

/** 云端上的一个备份文件 */
data class CloudFile(
    val name: String,
    val bytes: Long,
    val modifiedAt: Long,
    /** 服务端路径（WebDAV 是相对的 href，别的实现可能是 fileId） */
    val path: String = name
) {
    val sizeText: String get() = BackupArchive.humanSize(bytes)
}

/**
 * 云存储适配层。目前只有 WebDAV 一个实现（见 [WebDavProvider]）。
 *
 * 为什么不是百度网盘 / 天翼云盘的 SDK：两家的开放平台都只对企业开放，
 * 个人开发者连创建应用的入口都没有（天翼原话「未面向个人开发者开放」）。
 * 硬接非官方接口违反条款、随时失效、还要处理验证码，不做。
 * 换成 WebDAV 之后坚果云、Nextcloud、群晖、InfiniCLOUD 都能用；
 * 百度 / 天翼则走「分享导出 → 存进网盘」「从网盘下载 → 选文件恢复」这条路。
 */
interface CloudProvider {

    val id: String

    val name: String

    /** 设置页里那行小字，说明这家怎么填 */
    val hint: String

    /** 地址与账号填全了没 */
    fun isConfigured(): Boolean

    /** 连通性自检，成功时返回一句给人看的描述（含目录名） */
    suspend fun test(): String

    /** 列出备份目录下的文件，新的在前 */
    suspend fun list(): List<CloudFile>

    suspend fun upload(file: File, onProgress: (Float) -> Unit = {}): CloudFile

    suspend fun download(file: CloudFile, dest: File, onProgress: (Float) -> Unit = {})

    suspend fun delete(file: CloudFile)
}

/**
 * 云备份相关偏好的唯一入口。
 *
 * 密码是明文存在 App 私有目录的 SharedPreferences 里——WebDAV 用的「应用密码」
 * 是个可随时吊销的独立口令（坚果云就是这么设计的），不是网盘登录密码；
 * 真要加密得上 Keystore 加额外依赖，对这个 App 不划算，这里如实记下这个取舍。
 */
object CloudStore {

    private const val PREFS = "cloud_backup"

    private const val KEY_URL = "webdav_url"
    private const val KEY_USER = "webdav_user"
    private const val KEY_PASS = "webdav_pass"
    private const val KEY_DIR = "webdav_dir"

    private const val KEY_AUTO = "auto_backup"
    private const val KEY_KEEP = "keep_count"
    private const val KEY_LAST_AT = "last_backup_at"
    private const val KEY_LAST_NAME = "last_backup_name"
    private const val KEY_LAST_SIZE = "last_backup_size"

    /** 默认保留最近 7 份，多了自动删旧的 */
    const val DEFAULT_KEEP = 7

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- WebDAV

    fun webdavUrl(context: Context): String = sp(context).getString(KEY_URL, "").orEmpty()

    fun webdavUser(context: Context): String = sp(context).getString(KEY_USER, "").orEmpty()

    fun webdavPass(context: Context): String = sp(context).getString(KEY_PASS, "").orEmpty()

    fun webdavDir(context: Context): String =
        sp(context).getString(KEY_DIR, "qbank").orEmpty().ifBlank { "qbank" }

    fun saveWebDav(context: Context, url: String, user: String, pass: String, dir: String) {
        sp(context).edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_USER, user.trim())
            .putString(KEY_PASS, pass)
            .putString(KEY_DIR, dir.trim().trim('/').ifBlank { "qbank" })
            .apply()
    }

    fun clearWebDav(context: Context) {
        sp(context).edit()
            .remove(KEY_URL)
            .remove(KEY_USER)
            .remove(KEY_PASS)
            .apply()
    }

    // ---------------------------------------------------------------- 自动备份

    fun autoBackup(context: Context): Boolean = sp(context).getBoolean(KEY_AUTO, false)

    fun setAutoBackup(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO, value).apply()
    }

    /** 保留份数（1..30） */
    fun keepCount(context: Context): Int =
        sp(context).getInt(KEY_KEEP, DEFAULT_KEEP).coerceIn(1, 30)

    fun saveKeepCount(context: Context, count: Int) {
        sp(context).edit().putInt(KEY_KEEP, count.coerceIn(1, 30)).apply()
    }

    fun lastBackupAt(context: Context): Long = sp(context).getLong(KEY_LAST_AT, 0L)

    fun lastBackupName(context: Context): String =
        sp(context).getString(KEY_LAST_NAME, "").orEmpty()

    fun lastBackupSize(context: Context): Long = sp(context).getLong(KEY_LAST_SIZE, 0L)

    fun markBackedUp(context: Context, name: String, bytes: Long) {
        sp(context).edit()
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .putString(KEY_LAST_NAME, name)
            .putLong(KEY_LAST_SIZE, bytes)
            .apply()
    }
}
