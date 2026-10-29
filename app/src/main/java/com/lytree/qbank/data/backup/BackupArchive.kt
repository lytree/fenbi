package com.lytree.qbank.data.backup

import android.content.Context
import android.os.Build
import com.lytree.qbank.BuildConfig
import com.lytree.qbank.data.db.QBankDb
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份包：一个 zip，四样东西。
 *
 * ```
 * qbank-backup-20261029-2230.zip
 * ├── manifest.json     # 谁备的、什么时候、库版本、条数、体积、校验值
 * ├── qbank.db          # 题库 + 作答 + 笔迹 + 设置（整库）
 * ├── prefs.json        # shared_prefs 全量（笔色、浏览模式等）
 * └── pkg/…             # filesDir/qbank_pkg 下的材料配图
 * ```
 *
 * 为什么整库拷而不是「导出成 JSON」：库里有 12 张表、几千题、笔迹是长字符串，
 * 逐表导出再导入要重写一遍读写逻辑，还得处理外键与自增 id 的对应关系；
 * 换个设备最稳的还原就是**把那一份文件原样搬过去**，`onUpgrade` 会在打开时补列。
 *
 * 一致性靠 `wal_checkpoint(TRUNCATE)`：Android 默认开 WAL，只拷主库文件会漏掉
 * 还在 `-wal` 里没落盘的作答记录，所以打包前先把日志合并回主文件。
 */
object BackupArchive {

    /** 备份包格式版本。字段有增删时 +1，老包仍按老逻辑读 */
    const val SCHEMA = 1

    /** 包标记，避免把别的 App 的 zip 认成自己的备份 */
    const val APP_TAG = "qbank"

    const val MANIFEST_ENTRY = "manifest.json"
    const val DB_ENTRY = "qbank.db"
    const val PREFS_ENTRY = "prefs.json"
    const val PKG_PREFIX = "pkg/"

    /** 材料配图目录，与 ImportActivity 里的 `filesDir/qbank_pkg` 同源 */
    const val PKG_DIR = "qbank_pkg"

    private const val BUFFER = 64 * 1024

    class BackupException(message: String) : Exception(message)

    // ---------------------------------------------------------------- 模型

    data class Manifest(
        val schema: Int,
        val createdAt: Long,
        val versionName: String,
        val versionCode: Int,
        val dbVersion: Int,
        val device: String,
        val counts: Map<String, Int>,
        val imageCount: Int,
        val hasImages: Boolean,
        val dbBytes: Long,
        val totalBytes: Long,
        val dbMd5: String
    ) {

        fun toJson(): JSONObject = JSONObject().apply {
            put("schema", schema)
            put("app", APP_TAG)
            put("createdAt", createdAt)
            put("versionName", versionName)
            put("versionCode", versionCode)
            put("dbVersion", dbVersion)
            put("device", device)
            put("counts", JSONObject().apply { counts.forEach { (k, v) -> put(k, v) } })
            put("imageCount", imageCount)
            put("hasImages", hasImages)
            put("dbBytes", dbBytes)
            put("totalBytes", totalBytes)
            put("dbMd5", dbMd5)
        }

        companion object {
            fun fromJson(o: JSONObject): Manifest {
                if (o.optString("app") != APP_TAG) {
                    throw BackupException("这不是本应用的备份包")
                }
                val schema = o.optInt("schema", 0)
                if (schema <= 0 || schema > SCHEMA) {
                    throw BackupException("备份包格式版本不认识（$schema），请升级 App 后再恢复")
                }
                val countsObj = o.optJSONObject("counts") ?: JSONObject()
                val counts = LinkedHashMap<String, Int>()
                countsObj.keys().forEach { counts[it] = countsObj.optInt(it) }
                return Manifest(
                    schema = schema,
                    createdAt = o.optLong("createdAt"),
                    versionName = o.optString("versionName"),
                    versionCode = o.optInt("versionCode"),
                    dbVersion = o.optInt("dbVersion"),
                    device = o.optString("device"),
                    counts = counts,
                    imageCount = o.optInt("imageCount"),
                    hasImages = o.optBoolean("hasImages"),
                    dbBytes = o.optLong("dbBytes"),
                    totalBytes = o.optLong("totalBytes"),
                    dbMd5 = o.optString("dbMd5")
                )
            }
        }
    }

    /** 已解开的备份包（解到临时目录，等调用方决定要不要落地） */
    class Opened(
        val manifest: Manifest,
        val dir: File,
        val dbFile: File,
        val prefsJson: JSONObject?
    ) {
        val pkgDir: File get() = File(dir, PKG_DIR)
    }

    // ---------------------------------------------------------------- 打包

    /**
     * 打一份备份到 [dest]。同步执行，调用方放 IO 线程。
     *
     * @param onProgress (0..1, 当前在做什么)
     */
    fun create(
        context: Context,
        dest: File,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Manifest {
        onProgress(0f, "整理数据库")
        val db = QBankDb.get(context)
        checkpoint(db)

        val dbFile = context.getDatabasePath(DB_ENTRY)
        if (!dbFile.isFile || dbFile.length() == 0L) {
            throw BackupException("本地还没有题库数据，先导入一份再来备份")
        }

        val prefsBytes = prefsToJson(context).toString().toByteArray(Charsets.UTF_8)
        val pkgDir = File(context.filesDir, PKG_DIR)
        val images = if (pkgDir.isDirectory) {
            pkgDir.walkTopDown().filter { it.isFile }.sortedBy { it.absolutePath }.toList()
        } else {
            emptyList()
        }

        val total = dbFile.length() + prefsBytes.size + images.sumOf { it.length() }
        val manifest = Manifest(
            schema = SCHEMA,
            createdAt = System.currentTimeMillis(),
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            dbVersion = QBankDb.DB_VERSION,
            device = deviceLabel(),
            counts = tableCounts(db),
            imageCount = images.size,
            hasImages = images.isNotEmpty(),
            dbBytes = dbFile.length(),
            totalBytes = total,
            dbMd5 = md5Of(dbFile)
        )

        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, dest.name + ".part")
        var written = 0L

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp), BUFFER)).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifest.toJson().toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                onProgress(0.02f, "写入题库数据库")
                zip.putNextEntry(ZipEntry(DB_ENTRY))
                written += pump(FileInputStream(dbFile), zip)
                zip.closeEntry()

                if (prefsBytes.isNotEmpty()) {
                    zip.putNextEntry(ZipEntry(PREFS_ENTRY))
                    zip.write(prefsBytes)
                    zip.closeEntry()
                }

                images.forEachIndexed { index, file ->
                    val rel = file.relativeTo(pkgDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(PKG_PREFIX + rel))
                    written += pump(FileInputStream(file), zip)
                    zip.closeEntry()
                    val ratio = if (total <= 0) 1f else (written.toFloat() / total).coerceIn(0f, 1f)
                    onProgress(ratio, "打包配图 ${index + 1}/${images.size}")
                }
            }
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()
            throw if (e is BackupException) e else BackupException("打包失败：${e.message}")
        }

        onProgress(1f, "备份包已生成")
        return manifest
    }

    // ---------------------------------------------------------------- 解包

    /**
     * 把备份包解到 [destDir]（会先清空）。只读不写库，落地由 [BackupManager.restore] 负责。
     */
    fun open(zip: File, destDir: File): Opened {
        if (!zip.isFile || zip.length() == 0L) throw BackupException("备份文件读不到")

        destDir.deleteRecursively()
        destDir.mkdirs()
        val root = destDir.canonicalPath + File.separator

        var manifest: Manifest? = null
        var prefs: JSONObject? = null

        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zip), BUFFER)).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (entry.isDirectory) {
                        zin.closeEntry()
                        continue
                    }
                    val name = entry.name
                    when {
                        name == MANIFEST_ENTRY ->
                            manifest = Manifest.fromJson(
                                JSONObject(zin.readBytes().toString(Charsets.UTF_8))
                            )

                        name == PREFS_ENTRY ->
                            prefs = runCatching {
                                JSONObject(zin.readBytes().toString(Charsets.UTF_8))
                            }.getOrNull()

                        name == DB_ENTRY || name.startsWith(PKG_PREFIX) -> {
                            val out = File(destDir, name)
                            // zip slip：条目里的 ../ 一律拒绝
                            if (!out.canonicalPath.startsWith(root)) {
                                throw BackupException("备份包里有非法路径：$name")
                            }
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zin.copyTo(it, BUFFER) }
                        }
                        // 其余条目（将来加的）忽略，保证老版本能读新包
                    }
                    zin.closeEntry()
                }
            }
        } catch (e: Exception) {
            throw if (e is BackupException) e else BackupException("备份包已损坏：${e.message}")
        }

        val m = manifest ?: throw BackupException("备份包里缺少 manifest.json")
        val dbFile = File(destDir, DB_ENTRY)
        if (!dbFile.isFile) throw BackupException("备份包里缺少数据库文件")

        if (m.dbMd5.isNotBlank()) {
            val actual = md5Of(dbFile)
            if (!actual.equals(m.dbMd5, ignoreCase = true)) {
                throw BackupException("备份包校验不通过（MD5 不一致），可能传输时被截断了")
            }
        }
        if (m.dbVersion > QBankDb.DB_VERSION) {
            throw BackupException(
                "这份备份来自更新的版本（库 v${m.dbVersion}），当前 App 只有 v${QBankDb.DB_VERSION}。" +
                    "先把 App 升级到最新版再恢复，否则会丢字段。"
            )
        }

        return Opened(m, destDir, dbFile, prefs)
    }

    // ---------------------------------------------------------------- 应用（落地）

    /**
     * 把解开的包换进本机：库文件、配图目录、偏好。
     *
     * 顺序上先把当前库改名留底（`qbank.db.before-restore`），中途出错能回滚；
     * 配图目录同理（`qbank_pkg.old`）。成功后清掉留底。
     *
     * **调用前必须先 [QBankDb.closeInstance]**。
     */
    fun install(context: Context, opened: Opened, onProgress: (Float, String) -> Unit = { _, _ -> }) {
        QBankDb.closeInstance()

        // 先换配图，再换库：配图这步失败时数据库还没动，整个恢复等于没发生；
        // 反过来（先换库）一旦配图失败就留下「新库配旧图」的半截状态，更难收拾。
        onProgress(0.1f, "替换材料配图")
        val pkgTarget = File(context.filesDir, PKG_DIR)
        val pkgOld = File(context.filesDir, "$PKG_DIR.old")
        pkgOld.deleteRecursively()
        val hadPkg = pkgTarget.exists()
        if (hadPkg && !pkgTarget.renameTo(pkgOld)) {
            throw BackupException("配图目录无法替换（可能被占用），恢复已中止")
        }
        try {
            val source = opened.pkgDir
            if (source.isDirectory) {
                source.copyRecursively(pkgTarget, overwrite = true)
            } else {
                pkgTarget.mkdirs()
            }
        } catch (e: Exception) {
            pkgTarget.deleteRecursively()
            if (hadPkg) pkgOld.renameTo(pkgTarget)
            throw BackupException("配图写入失败，已回滚：${e.message}")
        }

        onProgress(0.45f, "替换数据库")
        val dbTarget = context.getDatabasePath(DB_ENTRY)
        val dbBackup = File(dbTarget.parentFile, "$DB_ENTRY.before-restore")
        val wal = File(dbTarget.parentFile, "$DB_ENTRY-wal")
        val shm = File(dbTarget.parentFile, "$DB_ENTRY-shm")

        dbBackup.delete()
        if (dbTarget.exists() && !dbTarget.renameTo(dbBackup)) {
            pkgTarget.deleteRecursively()
            if (hadPkg) pkgOld.renameTo(pkgTarget)
            throw BackupException("旧库无法留底（可能仍被占用），恢复已中止")
        }
        try {
            dbTarget.parentFile?.mkdirs()
            opened.dbFile.copyTo(dbTarget, overwrite = true)
            // 旧库的 -wal / -shm 必须一起删掉，否则打开时会拿旧日志回放到新库上
            wal.delete()
            shm.delete()
        } catch (e: Exception) {
            dbTarget.delete()
            dbBackup.renameTo(dbTarget)
            pkgTarget.deleteRecursively()
            if (hadPkg) pkgOld.renameTo(pkgTarget)
            throw BackupException("数据库写入失败，已回滚：${e.message}")
        }

        onProgress(0.85f, "恢复偏好设置")
        opened.prefsJson?.let { runCatching { prefsFromJson(context, it) } }

        onProgress(1f, "恢复完成")
        dbBackup.delete()
        pkgOld.deleteRecursively()
    }

    // ---------------------------------------------------------------- 内部工具

    /** WAL 合并：把 -wal 里没落盘的作答记录并回主库，否则备份会丢最近一次做题 */
    private fun checkpoint(db: QBankDb) {
        runCatching {
            db.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
    }

    private fun tableCounts(db: QBankDb): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        listOf("banks", "questions", "papers", "records", "words").forEach { table ->
            runCatching {
                db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                    if (c.moveToFirst()) out[table] = c.getInt(0)
                }
            }
        }
        return out
    }

    private fun deviceLabel(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}"

    /** 边读边写，返回字节数（用来累计整体进度） */
    private fun pump(input: InputStream, output: OutputStream): Long {
        input.use { src ->
            val buf = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                total += n
            }
            return total
        }
    }

    fun md5Of(file: File): String = digestOf(file, "MD5")

    private fun digestOf(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        FileInputStream(file).use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- 偏好

    /** 把 shared_prefs 目录下所有 xml 读成 JSON：`{ "exam_prefs": { "k": {"t":"i","v":1} } }` */
    private fun prefsToJson(context: Context): JSONObject {
        val out = JSONObject()
        val dir = File(context.dataDir, "shared_prefs")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: return out
        files.forEach { file ->
            val name = file.name.removeSuffix(".xml")
            val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val bag = JSONObject()
            sp.all.forEach { (key, value) ->
                runCatching { bag.put(key, wrapValue(value)) }
            }
            if (bag.length() > 0) out.put(name, bag)
        }
        return out
    }

    /**
     * 值包一层类型标记。SharedPreferences 的 all 里 Int/Long/Float 都是 Number，
     * 直接塞进 JSON 再读出来会一律变成 Integer，恢复时写错类型会让开关读成 null。
     */
    private fun wrapValue(value: Any?): JSONObject = when (value) {
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
        else -> JSONObject().put("t", "s").put("v", value?.toString() ?: "")
    }

    @Suppress("UNCHECKED_CAST")
    private fun prefsFromJson(context: Context, json: JSONObject) {
        json.keys().forEach { name ->
            val bag = json.optJSONObject(name) ?: return@forEach
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            bag.keys().forEach { key ->
                val node = bag.optJSONObject(key) ?: return@forEach
                when (node.optString("t")) {
                    "b" -> editor.putBoolean(key, node.optBoolean("v"))
                    "i" -> editor.putInt(key, node.optInt("v"))
                    "l" -> editor.putLong(key, node.optLong("v"))
                    "f" -> editor.putFloat(key, node.optDouble("v").toFloat())
                    "ss" -> {
                        val arr = node.optJSONArray("v") ?: JSONArray()
                        val set = HashSet<String>(arr.length())
                        for (i in 0 until arr.length()) set.add(arr.optString(i))
                        editor.putStringSet(key, set)
                    }
                    else -> editor.putString(key, node.optString("v"))
                }
            }
            editor.commit()
        }
    }

    // ---------------------------------------------------------------- 展示用

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun fileStamp(millis: Long = System.currentTimeMillis()): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(
            "%04d%02d%02d-%02d%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
    }
}
