package com.lytree.qbank

import com.lytree.qbank.data.importer.ImportFormat
import com.lytree.qbank.data.importer.ImportIssue
import com.lytree.qbank.data.importer.Importer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「一份 Excel 多份试卷」的拆卷规则。
 *
 * 钉住四件事：**一个工作表 = 一份试卷**、表内「试卷」列可按行细分、
 * 两张表同名也不合并（否则覆盖导入会互相吃掉）、单表文件行为完全不变。
 */
class MultiSheetImportTest {

    // ---------------- 多工作表 xlsx 构建器（inlineStr，无 sharedStrings） ----------------

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

    private fun sheetXml(rows: List<List<String>>): String {
        val body = rows.mapIndexed { ri, r ->
            "<row r=\"${ri + 1}\">" + r.mapIndexed { ci, c ->
                if (c.isEmpty()) ""
                else "<c r=\"${colName(ci)}${ri + 1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(c)}</t></is></c>"
            }.joinToString("") + "</row>"
        }.joinToString("")
        return """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$body</sheetData></worksheet>"""
    }

    /** 按顺序生成多张工作表的 xlsx；sheet1.xml 对应第一个名字 */
    private fun xlsx(vararg sheets: Pair<String, List<List<String>>>): ByteArray {
        val workbookSheets = sheets.mapIndexed { i, (name, _) ->
            "<sheet name=\"${esc(name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>"
        }.joinToString("")
        val rels = sheets.mapIndexed { i, _ ->
            """<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>"""
        }.joinToString("")
        val overrides = sheets.mapIndexed { i, _ ->
            """<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
        }.joinToString("")

        val entries = LinkedHashMap<String, String>()
        entries["[Content_Types].xml"] = """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>$overrides</Types>"""
        entries["_rels/.rels"] = """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
        entries["xl/workbook.xml"] = """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>$workbookSheets</sheets></workbook>"""
        entries["xl/_rels/workbook.xml.rels"] = """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels</Relationships>"""
        sheets.forEachIndexed { i, (_, rows) ->
            entries["xl/worksheets/sheet${i + 1}.xml"] = sheetXml(rows)
        }
        return zip { put -> entries.forEach { (n, d) -> put(n, d.toByteArray()) } }
    }

    private val header = listOf("题干", "选项A", "选项B", "答案")

    private fun row(stem: String, answer: String = "A") = listOf(stem, "甲", "乙", answer)

    private fun sheet(vararg stems: String): List<List<String>> =
        listOf(header) + stems.map { row(it) }

    private fun ImportedNames(out: com.lytree.qbank.data.importer.MultiPaperOutcome) =
        out.papers.map { it.name }

    // ---------------- 主规则：一个工作表一份试卷 ----------------

    @Test
    fun every_worksheet_becomes_its_own_paper() {
        val bytes = xlsx(
            "2023国考" to sheet("二〇二三第一题", "二〇二三第二题"),
            "2024国考" to sheet("二〇二四第一题", "二〇二四第二题", "二〇二四第三题")
        )
        val out = Importer.buildPapers(ImportFormat.XLSX, bytes, "行测真题.xlsx")

        assertEquals(2, out.papers.size)
        assertEquals(listOf("2023国考", "2024国考"), ImportedNames(out))
        assertEquals(2, out.papers[0].questions.size)
        assertEquals(3, out.papers[1].questions.size)
        assertEquals(5, out.questionCount)
        assertEquals(0, out.errorCount)
        assertTrue(out.multi)
        // 拆分依据要能讲清楚，用户才知道卷名是哪来的
        assertTrue(out.issues.any { it.message.contains("2 份试卷") })
        assertEquals("工作表「2023国考」", out.papers[0].origin)
    }

    /** 只有一张表：跟以前一样，卷名用文件名，不多出任何「多卷」痕迹 */
    @Test
    fun single_sheet_file_still_yields_one_paper_named_after_the_file() {
        val bytes = xlsx("Sheet1" to sheet("第一题", "第二题"))
        val out = Importer.buildPapers(ImportFormat.XLSX, bytes, "2023国考行测.xlsx")

        assertEquals(1, out.papers.size)
        assertFalse(out.multi)
        assertEquals("2023国考行测", out.papers[0].name)
        assertEquals(2, out.questionCount)
        assertTrue(out.issues.none { it.message.contains("份试卷") })
    }

    // ---------------- 表内「试卷」列 ----------------

    /** 同一张表里排了多套题：按列拆；试卷名只写在首行时，后面的空行要沿用上面那份 */
    @Test
    fun paper_column_splits_rows_inside_one_sheet() {
        val rows = listOf(
            listOf("试卷", "题干", "选项A", "选项B", "答案"),
            listOf("2023国考", "一题", "甲", "乙", "A"),
            listOf("", "二题", "甲", "乙", "B"),
            listOf("2024国考", "三题", "甲", "乙", "A")
        )
        val out = Importer.buildPapers(ImportFormat.XLSX, xlsx("真题" to rows), "合集.xlsx")

        assertEquals(listOf("2023国考", "2024国考"), ImportedNames(out))
        assertEquals(2, out.papers[0].questions.size)
        assertEquals(1, out.papers[1].questions.size)
        assertEquals("「试卷」列", out.papers[0].origin)
    }

    /** 「试卷」列只填了一个值：同表即一卷，卷名仍按单卷口径（文件名）取，不被列值劫持 */
    @Test
    fun paper_column_with_a_single_value_is_not_a_split() {
        val rows = listOf(
            listOf("试卷", "题干", "选项A", "选项B", "答案"),
            listOf("某模拟卷", "一题", "甲", "乙", "A"),
            listOf("", "二题", "甲", "乙", "B")
        )
        val out = Importer.buildPapers(ImportFormat.XLSX, xlsx("Sheet1" to rows), "模拟卷合集.xlsx")

        assertEquals(1, out.papers.size)
        assertEquals("模拟卷合集", out.papers[0].name)
        assertEquals(2, out.questionCount)
    }

    // ---------------- 边界 ----------------

    /** 两张表卷名相同也必须各自成卷，并且名字要错开：同名会被「覆盖导入」互相删掉 */
    @Test
    fun sheets_with_the_same_name_are_kept_apart() {
        val bytes = xlsx(
            "第一套" to sheet("一题"),
            "第二套" to sheet("二题")
        )
        val out = Importer.buildPapers(ImportFormat.XLSX, bytes, "同名卷.xlsx")
        assertEquals(2, out.papers.size)
        assertEquals(2, out.papers.map { it.name }.distinct().size)

        val dup = xlsx(
            "考场A" to (listOf(listOf("试卷", "题干", "选项A", "选项B", "答案")) +
                listOf(listOf("国考", "一题", "甲", "乙", "A"))),
            "考场B" to (listOf(listOf("试卷", "题干", "选项A", "选项B", "答案")) +
                listOf(listOf("国考", "二题", "甲", "乙", "A")))
        )
        val out2 = Importer.buildPapers(ImportFormat.XLSX, dup, "双考场.xlsx")
        assertEquals(2, out2.papers.size)
        assertEquals(2, out2.papers.map { it.name }.distinct().size)
        assertTrue(out2.papers.any { it.name.contains("考场A") })
    }

    /** 去重按卷内做：同一道题出现在两份卷子里是正常的，不能跨卷吞掉 */
    @Test
    fun dedup_is_per_paper_not_across_papers() {
        val bytes = xlsx(
            "第一套" to sheet("同一道真题"),
            "第二套" to sheet("同一道真题", "另一道题")
        )
        val out = Importer.buildPapers(ImportFormat.XLSX, bytes, "真题两套.xlsx")

        assertEquals(3, out.questionCount)
        assertEquals(0, out.duplicateSkipped)
    }

    /** 「说明」这类没有题目的工作表：其余表能出题时只提示，不必报红 */
    @Test
    fun a_sheet_without_questions_is_only_a_hint() {
        val bytes = xlsx(
            "说明" to listOf(listOf("本文件说明"), listOf("第一个工作表对应 2023 国考")),
            "2023国考" to sheet("一题", "二题")
        )
        val out = Importer.buildPapers(ImportFormat.XLSX, bytes, "带说明的卷子.xlsx")

        assertEquals(1, out.papers.size)
        assertEquals(2, out.questionCount)
        assertEquals(0, out.errorCount)
        assertTrue(out.issues.any { it.message.contains("说明") && it.level == ImportIssue.Level.INFO })
    }

    /** 一张表都出不了题：每张表都得给出原因，否则用户只知道「0 题」 */
    @Test
    fun when_no_sheet_yields_questions_every_sheet_reports_why() {
        val bytes = xlsx(
            "甲" to listOf(listOf("随便写写")),
            "乙" to listOf(listOf("还是随便写写"))
        )
        val out = Importer.buildPapers(ImportFormat.XLSX, bytes, "空表.xlsx")

        assertEquals(0, out.papers.size)
        assertTrue(out.errorCount >= 2)
        assertTrue(out.issues.any { it.message.contains("甲") })
        assertTrue(out.issues.any { it.message.contains("乙") })
    }

    /** 默认表名（Sheet1 / 工作表1）当卷名没意义：多表时回落到「文件名-序号」 */
    @Test
    fun default_sheet_names_fall_back_to_the_file_name() {
        val bytes = xlsx(
            "Sheet1" to sheet("一题"),
            "工作表2" to sheet("二题")
        )
        val out = Importer.buildPapers(ImportFormat.XLSX, bytes, "套卷.xlsx")

        assertEquals(listOf("套卷-1", "套卷-2"), ImportedNames(out))
    }

    /** 老接口 `build` 仍然给出摊平后的单卷视图，别让既有调用方踩空 */
    @Test
    fun build_flattens_multiple_papers_for_legacy_callers() {
        val bytes = xlsx(
            "第一套" to sheet("一题", "二题"),
            "第二套" to sheet("三题")
        )
        val out = Importer.build(ImportFormat.XLSX, bytes, fileName = "两套.xlsx")

        assertEquals(3, out.imported)
        assertEquals(3, out.questions.size)
        assertEquals(2, out.papers.size)
        assertEquals("第一套", out.suggestedName)
    }

    /** 内置示例「多套卷示例.xlsx」必须能开出两卷：它同时是 openpyxl 导出（sharedStrings）的回归样本 */
    @Test
    fun bundled_multi_paper_sample_opens_as_two_papers() {
        val file = listOf(
            File("src/main/assets/samples/多套卷示例.xlsx"),
            File("app/src/main/assets/samples/多套卷示例.xlsx")
        ).firstOrNull { it.exists() }
        assumeTrue("找不到内置示例（换了个工作目录），跳过", file != null)

        val out = Importer.buildPapers(ImportFormat.XLSX, file!!.readBytes(), file.name)

        assertEquals(listOf("2023国考（节选）", "2024国考（节选）"), ImportedNames(out))
        assertEquals(5, out.questionCount)
        assertEquals(0, out.errorCount)
        assertTrue(out.issues.any { it.message.contains("使用说明") })
    }
}
