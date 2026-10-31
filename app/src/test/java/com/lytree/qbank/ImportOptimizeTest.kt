package com.lytree.qbank

import com.lytree.qbank.data.importer.AnkiImporter
import com.lytree.qbank.data.importer.CsvImporter
import com.lytree.qbank.data.importer.Field
import com.lytree.qbank.data.importer.ImportFormat
import com.lytree.qbank.data.importer.ImportIssue
import com.lytree.qbank.data.importer.Importer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导入方案的宽容度与择优逻辑。
 *
 * 钉住四件事：包里多份数据文件时的择优、批次内去重、图片引用的编码/大小写兜底、
 * 答案与表头的各种现实写法。
 */
class ImportOptimizeTest {

    private val ankiTsv = listOf(
        "#separator:tab",
        "#html:true",
        "#tags column:4",
        "<img src=\"s01_q01.png\">\tD\t第1套 第1题\t图推700题",
        "<img src=\"s01_q02.png\">\tA\t第1套 第2题\t图推700题"
    ).joinToString("\n")

    private fun zip(block: (put: (String, ByteArray) -> Unit) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            block { name, data ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun pkgDir() = File(System.getProperty("java.io.tmpdir"), "qbank_pkg_test")

    // ---------------- 格式探测 ----------------

    /** Windows 导出的老题库常是 GBK，扩展名又写成 .txt，得靠内容认出是 CSV */
    @Test
    fun gbk_csv_with_txt_extension_is_detected() {
        val text = "题干,答案\n下列哪一项正确,A\n第二道题呢,B\n"
        val bytes = text.toByteArray(charset("GBK"))
        assertEquals(ImportFormat.CSV, Importer.detect("题库导出.txt", bytes))
    }

    // ---------------- 多候选择优 ----------------

    /**
     * 用户真实遇到的那种包：`questions.json` 是 PDF 定位数据（没有题干），
     * 真正的题目在 `anki_import.tsv` 里。以前按文件名选中 json → 导入 0 题。
     */
    @Test
    fun package_picks_the_file_that_actually_yields_questions() {
        val bytes = zip { put ->
            put(
                "questions.json",
                """[{"set":1,"qnum":1,"image":"s01_q01.png","answer":"D","page":3,"top":100,"bottom":200}]"""
                    .toByteArray()
            )
            put("anki_import.tsv", ankiTsv.toByteArray())
            put("images/s01_q01.png", ByteArray(32) { 0x1 })
            put("images/s01_q02.png", ByteArray(32) { 0x2 })
        }
        val out = Importer.build(ImportFormat.ZIP, bytes, pkgDir = pkgDir())

        assertEquals(2, out.imported)
        assertEquals(0, out.errorCount)
        // 明明选中了 tsv，理由要说出来（用户才知道包里另有一份 json）
        val note = out.issues.first { it.message.contains("个数据文件") }
        assertTrue(note.message, note.message.contains("anki_import.tsv"))
        assertTrue(note.message, note.message.contains("questions.json"))
    }

    @Test
    fun package_reports_every_candidate_when_none_works() {
        val bytes = zip { put ->
            put("questions.json", """[{"set":1,"qnum":1,"page":3}]""".toByteArray())
            put("notes.csv", "foo,bar\nxx,yy\n".toByteArray())
        }
        val out = Importer.build(ImportFormat.ZIP, bytes, pkgDir = pkgDir())

        assertEquals(0, out.imported)
        assertTrue(out.errorCount > 0)
        assertTrue(out.issues.any { it.message.contains("questions.json") })
        assertTrue(out.issues.any { it.message.contains("notes.csv") })
    }

    // ---------------- 去重 ----------------

    @Test
    fun duplicated_questions_are_skipped_and_counted() {
        val csv = listOf(
            "题干,选项A,选项B,答案",
            "\"同一道题\",\"甲\",\"乙\",\"A\"",
            "\"同一道题\",\"甲\",\"乙\",\"A\"",
            "\"另一道题\",\"丙\",\"丁\",\"B\""
        ).joinToString("\n")
        val bytes = csv.toByteArray()
        val out = Importer.build(Importer.detect("a.csv", bytes), bytes)

        assertEquals(2, out.imported)
        assertEquals(1, out.duplicateSkipped)
        assertTrue(out.issues.any { it.message.contains("重复") })
    }

    @Test
    fun whitespace_differences_still_count_as_duplicate() {
        val csv = listOf(
            "题干,选项A,选项B,答案",
            "\"same  question\",\"甲\",\"乙\",\"A\"",
            "\"same question\",\"甲\",\"乙\",\"A\""
        ).joinToString("\n")
        val bytes = csv.toByteArray()
        val out = Importer.build(Importer.detect("a.csv", bytes), bytes)

        assertEquals(1, out.imported)
        assertEquals(1, out.duplicateSkipped)
    }

    // ---------------- 图片引用 ----------------

    /** 中文文件名经 Anki 导出会被百分号转义，库里还是原名 */
    @Test
    fun percent_encoded_image_name_is_resolved() {
        val tsv = listOf(
            "#separator:tab",
            "#html:true",
            "<img src=\"%E5%9B%BE%E6%8E%A8%2001.png\">\tC\t第1题"
        ).joinToString("\n")
        val bytes = zip { put ->
            put("anki_import.tsv", tsv.toByteArray())
            put("images/图推 01.png", ByteArray(32) { 0x1 })
        }
        val out = Importer.build(ImportFormat.ZIP, bytes, pkgDir = pkgDir())

        val material = out.questions.single().material
        assertTrue(material, !material.contains("%E5"))
        val path = material.substringAfter("](").substringBefore(")")
        assertTrue(path, path.endsWith("图推 01.png"))
        assertTrue(path, File(path).exists())
    }

    /** 卡片里的大小写和包里的文件名经常对不上 */
    @Test
    fun image_name_matching_is_case_insensitive() {
        val tsv = listOf(
            "#separator:tab",
            "#html:true",
            "<img src=\"S01_Q01.PNG\">\tC\t第1题"
        ).joinToString("\n")
        val bytes = zip { put ->
            put("anki_import.tsv", tsv.toByteArray())
            put("images/s01_q01.png", ByteArray(32) { 0x1 })
        }
        val out = Importer.build(ImportFormat.ZIP, bytes, pkgDir = pkgDir())

        val path = out.questions.single().material.substringAfter("](").substringBefore(")")
        assertTrue(path, path.endsWith("s01_q01.png"))
    }

    // ---------------- 答案写法 ----------------

    @Test
    fun answer_prefixes_and_brackets_are_tolerated() {
        assertEquals(listOf("A"), AnkiImporter.answerKeys("答案：A"))
        assertEquals(listOf("B"), AnkiImporter.answerKeys("正确答案是 B"))
        assertEquals(listOf("C"), AnkiImporter.answerKeys("（C）"))
        assertEquals(listOf("D"), AnkiImporter.answerKeys("D."))
        assertEquals(listOf("A"), AnkiImporter.answerKeys("选 A"))
        assertEquals(listOf("A"), AnkiImporter.answerKeys("A 项"))
        assertEquals(listOf("A", "B", "D"), AnkiImporter.answerKeys("A、B、D"))
    }

    /** 认不出字母就必须返回空，绝不能把一段解析当成答案键 */
    @Test
    fun prose_answer_is_not_mistaken_for_option_keys() {
        assertTrue(AnkiImporter.answerKeys("物价总水平持续上涨").isEmpty())
        assertTrue(AnkiImporter.answerKeys("A 项正确，B 项错误").isEmpty())
        assertTrue(AnkiImporter.answerKeys("对").isEmpty())
    }

    // ---------------- 无表头嗅探 ----------------

    @Test
    fun headless_csv_columns_are_sniffed_by_content() {
        val csv = listOf(
            "1.太阳从东边升起属于什么现象\tA\t光学现象",
            "2.水在标准大气压下100度沸腾吗\tB\t热学现象",
            "3.声音在真空中能传播吗\tB\t声学现象"
        ).joinToString("\n")
        val issues = ArrayList<ImportIssue>()
        val res = CsvImporter.parse(ByteArrayInputStream(csv.toByteArray()), issues)

        assertEquals(3, res.questions.size)
        assertEquals("1.太阳从东边升起属于什么现象", res.questions[0].get(Field.STEM))
        assertEquals("A", res.questions[0].get(Field.ANSWER))
        assertTrue(issues.any { it.message.contains("按内容推断") })
    }

    /** 完全看不出结构时宁可不猜，保持原来的报错 */
    @Test
    fun unstructured_csv_still_fails_loudly() {
        val csv = "foo,bar\nxx,yy\n"
        val issues = ArrayList<ImportIssue>()
        val res = CsvImporter.parse(ByteArrayInputStream(csv.toByteArray()), issues)

        assertEquals(0, res.questions.size)
        assertTrue(issues.any { it.level == ImportIssue.Level.ERROR })
    }
}
