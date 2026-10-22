package com.lytree.qbank

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lytree.qbank.data.importer.ImportFormat
import com.lytree.qbank.data.importer.Importer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 题库包（zip）导入：标准目录结构、主数据识别、图片相对路径 → 绝对路径、zip slip 防护。
 */
@RunWith(AndroidJUnit4::class)
class PackageImportTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 应用内置示例资源（主 assets/samples 下） */
    private fun asset(name: String): ByteArray =
        ctx.assets.open("samples/$name").use { it.readBytes() }

    private val mainJson = """
        {
          "bank": "包导入测试",
          "questions": [
            {
              "type": "单选",
              "stem": "下列图表中增长率最高的是？",
              "options": {"A": "甲", "B": "乙"},
              "answer": "A",
              "material": "见图。\n\n![增速](images/chart-01.png)"
            }
          ]
        }
    """.trimIndent()

    /** 造一个带外层目录 + images 的题库包 */
    private fun makeZip(
        prefix: String = "资料分析题库包/",
        extra: Map<String, ByteArray> = emptyMap(),
        json: String = mainJson
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            fun put(name: String, data: ByteArray) {
                zos.putNextEntry(ZipEntry(prefix + name))
                zos.write(data)
                zos.closeEntry()
            }
            put("qbank.json", json.toByteArray())
            put("images/chart-01.png", asset("资料分析折线图.png"))
            put("README.txt", "说明不会导入".toByteArray())
            extra.forEach { (n, d) -> put(n, d) }
        }
        return bos.toByteArray()
    }

    @Test
    fun detectsPackageAndReadsMainJson() {
        val bytes = makeZip()
        assertEquals(ImportFormat.ZIP, Importer.detect("题库包.zip", bytes))
        // 没有扩展名时也要靠内容认出来
        assertEquals(ImportFormat.ZIP, Importer.detect("题库包", bytes))
        // 普通 xlsx 仍然是 Excel，不会被误判成题库包
        assertEquals(ImportFormat.XLSX, Importer.detect("a.xlsx", asset("行测基础示例.xlsx")))
    }

    @Test
    fun extractsImagesAndRewritesRelativePath() {
        val dir = File(ctx.cacheDir, "pkg_test").apply { deleteRecursively(); mkdirs() }
        val out = Importer.build(ImportFormat.ZIP, makeZip(), pkgDir = dir)

        assertEquals(1, out.imported)
        assertEquals("题库包 · JSON", out.format)
        assertEquals("包导入测试", out.suggestedName)

        val q = out.questions.single()
        assertTrue("材料里应保留图片说明", q.material.contains("![增速]("))
        assertFalse("相对路径应已被替换", q.material.contains("](images/chart-01.png)"))

        // 抽出来的绝对路径必须真实存在，且能解码
        val path = Regex("\\]\\(([^)]+)\\)").find(q.material)!!.groupValues[1]
        assertTrue("解压后的图片应存在：$path", File(path).isFile)
        assertTrue(File(path).length() > 0)
        // /data/user/0 与 /data/data 可能是软链，按 canonical 比
        assertTrue(File(path).canonicalPath.startsWith(dir.canonicalPath))
    }

    @Test
    fun ignoresZipSlipEntries() {
        val dir = File(ctx.cacheDir, "pkg_slip").apply { deleteRecursively(); mkdirs() }
        val evil = File(ctx.cacheDir, "evil.txt").apply { delete() }
        val bytes = makeZip(extra = mapOf("../../evil.txt" to "bad".toByteArray()))
        Importer.build(ImportFormat.ZIP, bytes, pkgDir = dir)
        assertFalse("包外文件不应被写出", evil.exists())
    }

    @Test
    fun reportsMissingMainFile() {
        val dir = File(ctx.cacheDir, "pkg_empty").apply { deleteRecursively(); mkdirs() }
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("notes.txt"))
            zos.write("没有题库".toByteArray())
            zos.closeEntry()
        }
        val out = Importer.build(ImportFormat.ZIP, bos.toByteArray(), pkgDir = dir)
        assertEquals(0, out.imported)
        assertTrue(out.errorCount > 0)
    }
}
