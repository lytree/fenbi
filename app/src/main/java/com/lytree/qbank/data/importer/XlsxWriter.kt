package com.lytree.qbank.data.importer

import com.lytree.qbank.data.model.Question
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 极简 XLSX 写入器——与 [XlsxReader] 配对的导出侧。
 *
 * 不引入 Apache POI，直接按 OOXML 规范拼 zip + XML：
 * - 单元格用 **inlineStr**（内联字符串），不需要 sharedStrings 表，写出逻辑最短；
 *   Excel / WPS / 本 App 的读取器都认这种标准写法。
 * - 单 sheet，表名「题库」，表头与统一模板（templates/题库模板.xlsx）完全一致，
 *   导出的文件**可以直接再导入**（下载 → 换手机 → 再导入的闭环）。
 *
 * 列规则：`题型 / 题干 / 材料 / 图片` + 按选项实际用到的字母展开 `选项A–H`
 * （不足 A–D 也补满，和模板对齐）+ `答案 / 解析 / 知识点 / 难度 / 地区 / 科目 / 来源 / 年份 / 分值`。
 * 题图在导入时已并入材料（Markdown 图片），所以「图片」列恒为空。
 */
object XlsxWriter {

    /** 把一组题目写成 xlsx 字节流（一行一题，首行表头） */
    fun write(questions: List<Question>): ByteArray {
        val headers = mutableListOf("题型", "题干", "材料", "图片")
        // 选项列：A–D 恒给，出现更大字母（E–H）时扩到最大用到的那列
        val maxLetter = questions.maxOfOrNull { q ->
            q.options.mapNotNull { it.key.trim().uppercase().singleOrNull() }
                .filter { it in 'A'..'H' }
                .maxOrNull() ?: 'A'
        } ?: 'A'
        val optionCols = ('A'..maxOf('D', maxLetter)).toList()
        optionCols.forEach { headers.add("选项$it") }
        headers.addAll(listOf("答案", "解析", "知识点", "难度", "地区", "科目", "来源", "年份", "分值"))
        // 非字母 key（①、对错选项之类）没有自己的列，并到「选项」列里按 `key. text` 摆
        val hasMisc = questions.any { q -> q.options.any { it.key.trim().uppercase().singleOrNull() !in optionCols } }
        if (hasMisc) headers.add(headers.size - 9, "选项")

        val rows = mutableListOf<List<Any>>()
        rows.add(headers.toList())
        for (q in questions) {
            val row = mutableListOf<Any>(
                q.type.label,
                q.stem,
                q.material,
                "" // 图片已并入材料
            )
            for (c in optionCols) {
                row.add(q.options.firstOrNull { it.key.trim().uppercase() == c.toString() }?.text ?: "")
            }
            if (hasMisc) {
                row.add(
                    q.options.filter { it.key.trim().uppercase().singleOrNull() !in optionCols }
                        .joinToString("\n") { "${it.key}. ${it.text}" }
                )
            }
            row.add(q.displayAnswer())
            row.add(q.analysis)
            row.add(q.knowledge)
            if (q.difficulty > 0) row.add(q.difficulty) else row.add("")
            row.add(q.region)
            row.add(q.subject)
            row.add(q.source)
            row.add(q.year)
            row.add(q.score)
            rows.add(row)
        }

        val sheet = buildString {
            append(XML_DECL)
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            rows.forEachIndexed { r, cells ->
                append("<row r=\"").append(r + 1).append("\">")
                cells.forEachIndexed { c, v ->
                    val ref = colName(c) + (r + 1)
                    when (v) {
                        is Int -> append("<c r=\"$ref\"><v>${v}</v></c>")
                        is Double -> append("<c r=\"$ref\"><v>${v}</v></c>")
                        else -> {
                            val s = v.toString()
                            if (s.isNotEmpty()) {
                                append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                                append(esc(s))
                                append("</t></is></c>")
                            } else {
                                append("<c r=\"").append(ref).append("\"/>")
                            }
                        }
                    }
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }

        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            put(zip, "[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>
            """.trimIndent())
            put(zip, "_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>
            """.trimIndent())
            put(zip, "xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="题库" sheetId="1" r:id="rId1"/></sheets></workbook>
            """.trimIndent())
            put(zip, "xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>
            """.trimIndent())
            put(zip, "xl/worksheets/sheet1.xml", sheet)
        }
        return out.toByteArray()
    }

    /** 写到本地文件（下载导出走这里） */
    fun writeToFile(questions: List<Question>, file: java.io.File) {
        java.io.FileOutputStream(file).use { write(questions, it) }
    }

    fun write(questions: List<Question>, out: OutputStream) {
        out.write(write(questions))
    }

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    /** 0→A 1→B … 25→Z（本模板最多 22 列，单字母足够） */
    private fun colName(i: Int): String = (('A'.code + i)).toChar().toString()

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
}
