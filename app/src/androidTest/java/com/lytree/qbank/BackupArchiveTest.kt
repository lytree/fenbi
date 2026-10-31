package com.lytree.qbank

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lytree.qbank.data.backup.BackupArchive
import com.lytree.qbank.data.db.QBankDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份包的打包 / 解包回归。
 *
 * 只测「生成 → 解开 → 校验」这一段，**不动真实数据库**：
 * [BackupArchive.create] 是只读的（先 wal_checkpoint 再读文件），
 * 真正会覆盖数据的 [BackupArchive.install] 不在测试范围里——那条路径一跑就把测试机上
 * 的试卷换掉了，不适合放进自动化测试。
 */
@RunWith(AndroidJUnit4::class)
class BackupArchiveTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val workDir = File(ctx.cacheDir, "backup-archive-test")

    @Test
    fun package_round_trips() {
        workDir.deleteRecursively()
        workDir.mkdirs()
        val zip = File(workDir, "round-trip.zip")

        val manifest = BackupArchive.create(ctx, zip) { _, _ -> }

        assertTrue("备份包应该写出来了", zip.isFile && zip.length() > 0)
        assertTrue("库文件应该有内容", manifest.dbBytes > 0)
        assertEquals("库版本要对得上", QBankDb.DB_VERSION, manifest.dbVersion)

        val opened = BackupArchive.open(zip, File(workDir, "out"))
        assertEquals("MD5 校验值要一致", manifest.dbMd5, opened.manifest.dbMd5)
        assertEquals("解出来的库要和记录的一致", manifest.dbBytes, opened.dbFile.length())
        assertTrue("库文件要解出来", opened.dbFile.length() > 0)
        assertTrue("manifest 的创建时间要有", opened.manifest.createdAt > 0)
    }

    @Test
    fun junk_zip_is_rejected() {
        workDir.mkdirs()
        val bad = File(workDir, "junk.zip")
        ZipOutputStream(bad.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("not a backup".toByteArray())
            zip.closeEntry()
        }

        val error = runCatching { BackupArchive.open(bad, File(workDir, "junk-out")) }
            .exceptionOrNull()
        assertTrue("缺 manifest 的包必须拒绝", error is BackupArchive.BackupException)
    }

    @Test
    fun foreign_manifest_is_rejected() {
        workDir.mkdirs()
        val bad = File(workDir, "foreign.zip")
        ZipOutputStream(bad.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"app":"someone-else","schema":1}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("qbank.db"))
            zip.write(ByteArray(16))
            zip.closeEntry()
        }

        val error = runCatching { BackupArchive.open(bad, File(workDir, "foreign-out")) }
            .exceptionOrNull()
        assertTrue("别的 App 的包必须拒绝", error is BackupArchive.BackupException)
    }
}
