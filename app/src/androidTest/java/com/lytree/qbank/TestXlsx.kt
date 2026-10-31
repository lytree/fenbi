package com.lytree.qbank

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 测试用的最小 xlsx 构建器（inlineStr 单元格，无 sharedStrings）。
 *
 * 统一 Excel 导入后，仪器化测试里的试卷 / 答案文件都从这里造。
 */
object TestXlsx {

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

    /** 生成一个最小可读的 xlsx（sheet 名固定「试卷」，inlineStr 单元格） */
    fun build(rows: List<List<String>>): ByteArray {
        val sheet = rows.mapIndexed { ri, r ->
            "<row r=\"${ri + 1}\">" + r.mapIndexed { ci, c ->
                if (c.isEmpty()) ""
                else "<c r=\"${colName(ci)}${ri + 1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(c)}</t></is></c>"
            }.joinToString("") + "</row>"
        }.joinToString("")
        val entries = mapOf(
            "[Content_Types].xml" to """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="试卷" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$sheet</sheetData></worksheet>""",
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, data) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(data.toByteArray())
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** 试卷表（17 列统一模板；options 传 A–D，answer 可空） */
    fun bank(vararg questions: QuestionRow): ByteArray {
        val header = listOf("题型", "题干", "材料", "图片", "选项A", "选项B", "选项C", "选项D", "答案", "解析")
        val rows = questions.map { q ->
            listOf(
                q.type, q.stem, q.material ?: "", q.image ?: "",
                q.options.getOrElse(0) { "" }, q.options.getOrElse(1) { "" },
                q.options.getOrElse(2) { "" }, q.options.getOrElse(3) { "" },
                q.answer ?: "", q.analysis ?: ""
            )
        }
        return build(listOf(header) + rows)
    }

    data class QuestionRow(
        val type: String,
        val stem: String,
        val options: List<String>,
        val answer: String? = null,
        val analysis: String? = null,
        val material: String? = null,
        val image: String? = null
    )

    /** 答案表（表头任意带「题号 / 答案 / 解析 / 题干」列即可） */
    fun answerSheet(header: List<String>, rows: List<List<String>>): ByteArray =
        build(listOf(header) + rows)
}
