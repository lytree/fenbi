package com.lytree.qbank

import com.lytree.qbank.data.importer.ImportFormat
import com.lytree.qbank.data.importer.ImportOutcome
import com.lytree.qbank.data.importer.Importer
import com.lytree.qbank.data.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Anki 纯文本（TSV）版式导入。
 *
 * Anki 的「导出 → 笔记 → 纯文本」是**没有表头**的，列含义靠字段顺序，所以走的是专门的
 * [com.lytree.qbank.data.importer.AnkiImporter]，这里把它的几条约定钉住：
 * 列位映射、图片进材料、字母答案合成占位选项、对错合成判断题、包内只写文件名的图片路径。
 */
class AnkiImportTest {

    /** 和真实导入包同构：正面是整题图，背面一个字母，第 3 列备注，第 4 列标签 */
    private val ankiTsv = listOf(
        "#separator:tab",
        "#html:true",
        "#tags column:4",
        "<img src=\"s01_q01.png\">\tD\t第1套 第1题\t图推700题",
        "<img src=\"s01_q02.png\">\tA\t第1套 第2题\t图推700题"
    ).joinToString("\n")

    private fun outcome(text: String, fileName: String = "anki_import.tsv"): ImportOutcome {
        val bytes = text.toByteArray()
        return Importer.build(Importer.detect(fileName, bytes), bytes)
    }

    // ---------------- 格式探测 ----------------

    @Test
    fun anki_header_detected_as_tsv() {
        val bytes = ankiTsv.toByteArray()
        assertEquals(ImportFormat.TSV, Importer.detect("anki_import.tsv", bytes))
        // 老版本 Anki 默认导出 .txt，也得认
        assertEquals(ImportFormat.TSV, Importer.detect("anki_import.txt", bytes))
        // 没有指令头的普通 tsv 仍按扩展名走
        assertEquals(ImportFormat.TSV, Importer.detect("a.tsv", "题干\t答案".toByteArray()))
    }

    // ---------------- 列位映射 ----------------

    @Test
    fun columns_map_to_stem_material_answer_and_tags() {
        val out = outcome(ankiTsv)
        assertEquals(2, out.imported)

        val q = out.questions[0]
        // 正面只有图 → 图进材料，备注顶上来当题干
        assertEquals("第1套 第1题", q.stem)
        assertEquals("![](s01_q01.png)", q.material)
        assertEquals("D", q.answer)
        assertEquals("图推700题", q.knowledge)
        assertEquals(QuestionType.SINGLE, q.type)
        // 没有选项列 → 按字母合成 A–D 占位
        assertEquals(listOf("A", "B", "C", "D"), q.options.map { it.key })
        assertTrue(q.options.all { it.text.isNotBlank() })
        assertEquals(0, out.errorCount)
    }

    @Test
    fun option_count_follows_max_answer_letter() {
        val tsv = listOf(
            "#separator:tab",
            "#html:true",
            "题干一\tD",
            "题干二\tE"
        ).joinToString("\n")
        val q = outcome(tsv).questions
        assertEquals(2, q.size)
        assertEquals(listOf("A", "B", "C", "D", "E"), q[0].options.map { it.key })
    }

    @Test
    fun front_with_text_and_image_keeps_text_as_stem() {
        val tsv = listOf(
            "#separator:tab",
            "#html:true",
            "1.（26广东事业）最有可能由两个立体图形组成的是：<div><img src=\"s09_q18.png\"></div>\tB"
        ).joinToString("\n")
        val q = outcome(tsv).questions.single()
        assertEquals("1.（26广东事业）最有可能由两个立体图形组成的是：", q.stem)
        assertEquals("![](s09_q18.png)", q.material)
        assertEquals("B", q.answer)
    }

    @Test
    fun html_tags_and_entities_are_cleaned() {
        val tsv = listOf(
            "#separator:tab",
            "#html:true",
            "<div>A&nbsp;&amp;&nbsp;B &lt;1&gt;<br>第二行</div>\t对"
        ).joinToString("\n")
        val q = outcome(tsv).questions.single()
        assertEquals("A & B <1>\n第二行", q.stem)
    }

    // ---------------- 题型 ----------------

    @Test
    fun judge_card_becomes_true_false() {
        val tsv = listOf(
            "#separator:tab",
            "#html:true",
            "地球是圆的\t对",
            "太阳从西边升起\t×"
        ).joinToString("\n")
        val qs = outcome(tsv).questions
        assertEquals(2, qs.size)
        assertEquals(QuestionType.TRUE_FALSE, qs[0].type)
        assertEquals(listOf("正确", "错误"), qs[0].options.map { it.text })
        assertEquals("T", qs[0].answer)
        assertEquals("F", qs[1].answer)
    }

    @Test
    fun multi_letter_answer_becomes_multi_choice() {
        val tsv = listOf(
            "#separator:tab",
            "#html:true",
            "下列属于法律形式的有哪些？\tA,C,D"
        ).joinToString("\n")
        val q = outcome(tsv).questions.single()
        assertEquals(QuestionType.MULTI, q.type)
        assertEquals("A,C,D", q.answer)
    }

    @Test
    fun blank_back_imports_as_pending_answer() {
        val tsv = listOf(
            "#separator:tab",
            "#html:true",
            "有图但没答案\t",
            "有答案的\tC"
        ).joinToString("\n")
        val out = outcome(tsv)
        assertEquals(2, out.imported)
        assertEquals("", out.questions[0].answer)
        assertTrue(out.issues.any { it.message.contains("待补答案") })
    }

    @Test
    fun prose_back_is_reported_not_silently_dropped() {
        val tsv = listOf(
            "#separator:tab",
            "#html:true",
            "什么是通货膨胀？\t物价总水平持续上涨"
        ).joinToString("\n")
        val out = outcome(tsv)
        // 问答型卡片当前无法作答：既要有提示，也不能一声不响地少题
        assertTrue(out.issues.any { it.level.name == "ERROR" })
    }

    // ---------------- 题库包 ----------------

    /** 和用户拿到的导入包一致：zip 根目录一个 tsv + images/，卡片里只写文件名 */
    private fun makePackage(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            fun put(name: String, data: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
            put("anki_import.tsv", ankiTsv.toByteArray())
            put("images/s01_q01.png", ByteArray(64) { 0x1 })
            put("images/s01_q02.png", ByteArray(64) { 0x2 })
        }
        return bos.toByteArray()
    }

    @Test
    fun package_with_tsv_resolves_bare_file_names() {
        val bytes = makePackage()
        assertEquals(ImportFormat.ZIP, Importer.detect("图推700题_tsv导入包.zip", bytes))

        val dir = File(System.getProperty("java.io.tmpdir"), "qbank_pkg_test")
        val out = Importer.build(ImportFormat.ZIP, bytes, pkgDir = dir)
        assertEquals(2, out.imported)
        assertEquals(0, out.errorCount)

        val material = out.questions[0].material
        // 卡片里写的是 s01_q01.png，入库后必须换成解压后的绝对路径
        assertTrue(material, !material.contains("](s01_q01.png)"))
        val path = material.substringAfter("](").substringBefore(")")
        assertNotNull(path)
        assertTrue(path, path.endsWith("s01_q01.png"))
        assertTrue(path, File(path).exists())
    }
}
