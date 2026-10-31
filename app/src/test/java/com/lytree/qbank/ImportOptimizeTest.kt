package com.lytree.qbank

import com.lytree.qbank.data.importer.CsvImporter
import com.lytree.qbank.data.importer.Field
import com.lytree.qbank.data.importer.ImportFormat
import com.lytree.qbank.data.importer.ImportIssue
import com.lytree.qbank.data.importer.Importer
import com.lytree.qbank.data.importer.XlsxWriter
import com.lytree.qbank.data.model.Option
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.data.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导入方案的宽容度与择优逻辑（统一 Excel 格式后）。
 *
 * 钉住四件事：包里多份 Excel 时的择优、批次内去重、图片引用的编码/大小写兜底、
 * 以及「非 Excel 的老格式一律不再解析」。
 */
class ImportOptimizeTest {

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

    // ---------------- 最小 xlsx 构建器（inlineStr，无 sharedStrings） ----------------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun colName(i0: Int): String {
        var s = ""
        var i = i0 + 1
        while (i > 0) {
            val m = (i - 1) % 26
            s = ('A' + m) + s
            i = (i - 1) / 26
        }
        return s
    }

    /** 生成一个最小可读的 xlsx（表名固定「题库」，inlineStr 单元格） */
    private fun xlsx(vararg rows: List<String>): ByteArray {
        val sheet = rows.mapIndexed { ri, r ->
            "<row r=\"${ri + 1}\">" + r.mapIndexed { ci, c ->
                if (c.isEmpty()) ""
                else "<c r=\"${colName(ci)}${ri + 1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(c)}</t></is></c>"
            }.joinToString("") + "</row>"
        }.joinToString("")
        val entries = mapOf(
            "[Content_Types].xml" to """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="题库" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$sheet</sheetData></worksheet>""",
        )
        return zip { put -> entries.forEach { (n, d) -> put(n, d.toByteArray()) } }
    }

    private fun bankRows(vararg stems: String): List<List<String>> =
        (listOf(listOf("题型", "题干", "材料", "图片", "选项A", "选项B", "答案")) +
                stems.map { s -> listOf("单选", s, "", "", "甲", "乙", "A") })

    // ---------------- 格式探测 ----------------

    @Test
    fun non_zip_bytes_are_treated_as_xlsx_and_fail_loudly() {
        // 老格式的 JSON / CSV / TXT 不再解析：一律按 Excel 走，解析失败时明说
        val json = """[{"stem":"题","answer":"A"}]""".toByteArray()
        val out = Importer.build(Importer.detect("题库.json", json), json)
        assertEquals(0, out.imported)
        assertTrue(out.errorCount > 0)
        assertTrue(out.issues.any { it.message.contains("Excel") })
    }

    // ---------------- 导出回环 ----------------

    /** 顶栏「下载」导出的 xlsx 必须能原样再导入：题型/题干/选项/答案/解析一圈不丢 */
    @Test
    fun exported_xlsx_round_trips_back_through_the_importer() {
        val questions = listOf(
            Question(
                type = QuestionType.SINGLE,
                stem = "回环第一题：下列哪个是导出格式？",
                options = listOf(Option("A", "xlsx"), Option("B", "json"), Option("C", "csv"), Option("D", "txt")),
                answer = "A",
                analysis = "统一 Excel 后导出也是 xlsx",
                knowledge = "回环",
                difficulty = 2
            ),
            Question(
                type = QuestionType.MULTI,
                stem = "回环第二题：哪些会一起导出？",
                options = listOf(Option("A", "题干"), Option("B", "选项"), Option("C", "答案"), Option("D", "解析")),
                answer = "A,B,C,D",
                material = "材料行\n![](images/x.png)"
            )
        )
        val bytes = XlsxWriter.write(questions)

        assertEquals(ImportFormat.XLSX, Importer.detect("试卷.xlsx", bytes))
        val out = Importer.build(Importer.detect("试卷.xlsx", bytes), bytes)
        assertEquals(2, out.imported)
        assertEquals(0, out.errorCount)

        val q1 = out.questions[0]
        assertEquals(QuestionType.SINGLE, q1.type)
        assertEquals("回环第一题：下列哪个是导出格式？", q1.stem)
        assertEquals("A", q1.answer)
        assertEquals("统一 Excel 后导出也是 xlsx", q1.analysis)
        assertEquals("xlsx", q1.options.firstOrNull { it.key == "A" }?.text)

        val q2 = out.questions[1]
        assertEquals(QuestionType.MULTI, q2.type)
        assertTrue(q2.material.contains("![](images/x.png)"))
        assertEquals(4, q2.options.size)
    }

    // ---------------- 多候选择优 ----------------

    /** 包里同时有几份 xlsx：真正能出题的那份要被选中，理由要说出来 */
    @Test
    fun package_picks_the_file_that_actually_yields_questions() {
        val bytes = zip { put ->
            put("题库-坏.xlsx", "这不是一个 xlsx".toByteArray())
            put("题库.xlsx", xlsx(*bankRows("第一题：甲乙谁对？", "第二题：丙丁谁对？").toTypedArray()))
            put("images/s01_q01.png", ByteArray(32) { 0x1 })
        }
        val out = Importer.build(ImportFormat.ZIP, bytes, pkgDir = pkgDir())

        assertEquals(2, out.imported)
        assertEquals(0, out.errorCount)
        val note = out.issues.first { it.message.contains("个数据文件") }
        assertTrue(note.message, note.message.contains("题库.xlsx"))
    }

    @Test
    fun package_reports_every_candidate_when_none_works() {
        val bytes = zip { put ->
            put("题库-坏1.xlsx", "乱七八糟".toByteArray())
            put("题库-坏2.xlsx", "同样不是 xlsx".toByteArray())
        }
        val out = Importer.build(ImportFormat.ZIP, bytes, pkgDir = pkgDir())

        assertEquals(0, out.imported)
        assertTrue(out.errorCount > 0)
        assertTrue(out.issues.any { it.message.contains("题库-坏1.xlsx") })
        assertTrue(out.issues.any { it.message.contains("题库-坏2.xlsx") })
    }

    /** 纯媒体包（只有图片没有 Excel）要明确报错，提示包里缺 题库.xlsx */
    @Test
    fun media_only_zip_fails_with_helpful_message() {
        val bytes = zip { put ->
            put("images/a.png", ByteArray(32) { 0x1 })
            put("README.txt", "只有图".toByteArray())
        }
        val out = Importer.build(ImportFormat.ZIP, bytes, pkgDir = pkgDir())

        assertEquals(0, out.imported)
        assertTrue(out.errorCount > 0)
        assertTrue(out.issues.any { it.message.contains("题库.xlsx") || it.message.contains("Excel") })
    }

    // ---------------- 去重 ----------------

    @Test
    fun duplicated_questions_are_skipped_and_counted() {
        val bytes = xlsx(*bankRows("同一道题", "同一道题", "另一道题").toTypedArray())
        val out = Importer.build(Importer.detect("题库.xlsx", bytes), bytes)

        assertEquals(2, out.imported)
        assertEquals(1, out.duplicateSkipped)
        assertTrue(out.issues.any { it.message.contains("重复") })
    }

    @Test
    fun whitespace_differences_still_count_as_duplicate() {
        val bytes = xlsx(
            listOf("题型", "题干", "材料", "图片", "选项A", "选项B", "答案"),
            listOf("单选", "same  question", "", "", "甲", "乙", "A"),
            listOf("单选", "same question", "", "", "甲", "乙", "A")
        )
        val out = Importer.build(Importer.detect("题库.xlsx", bytes), bytes)

        assertEquals(1, out.imported)
        assertEquals(1, out.duplicateSkipped)
    }

    // ---------------- 图片引用 ----------------

    /** 网页导出的材料里中文文件名常被百分号转义，库里还是原名 */
    @Test
    fun percent_encoded_image_name_is_resolved() {
        val rows = listOf(
            listOf("题型", "题干", "材料", "图片", "选项A", "选项B", "答案"),
            listOf("单选", "第1题", "<img src=\"%E5%9B%BE%E6%8E%A8%2001.png\">", "", "甲", "乙", "A")
        )
        val bytes = zip { put ->
            put("题库.xlsx", xlsx(*rows.toTypedArray()))
            put("images/图推 01.png", ByteArray(32) { 0x1 })
        }
        val out = Importer.build(ImportFormat.ZIP, bytes, pkgDir = pkgDir())

        val material = out.questions.single().material
        assertTrue(material, !material.contains("%E5"))
        val path = material.substringAfter("src=\"").substringBefore("\"")
        assertTrue(path, path.endsWith("图推 01.png"))
        assertTrue(path, File(path).exists())
    }

    /** Excel 里写的大小写和包里的文件名经常对不上 */
    @Test
    fun image_name_matching_is_case_insensitive() {
        val rows = listOf(
            listOf("题型", "题干", "材料", "图片", "选项A", "选项B", "答案"),
            listOf("单选", "第1题", "<img src=\"S01_Q01.PNG\">", "", "甲", "乙", "A")
        )
        val bytes = zip { put ->
            put("题库.xlsx", xlsx(*rows.toTypedArray()))
            put("images/s01_q01.png", ByteArray(32) { 0x1 })
        }
        val out = Importer.build(ImportFormat.ZIP, bytes, pkgDir = pkgDir())

        val path = out.questions.single().material.substringAfter("src=\"").substringBefore("\"")
        assertTrue(path, path.endsWith("s01_q01.png"))
    }

    // ---------------- 无表头嗅探（xlsx 的内部表格引擎） ----------------

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
