package com.lytree.qbank.data.importer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 极简 DOCX 读取器：只解 `word/document.xml`，把**表格**和**段落**捞出来。
 *
 * 词库最常见的形态就是一张 Word 表（词条 / 拼音 / 分类 / 释义 / 例句 / 出处），
 * 所以这里优先按表格解析；文档里一个表都没有时，退化成「一段一个词条」。
 *
 * 不引第三方库，与 [XlsxReader] 一样走 OOXML + XmlPullParser，避免 APK 膨胀。
 */
object DocxReader {

    /** 一份文档：所有一级表格 + 表格外的段落文本 */
    data class Doc(
        val tables: List<List<List<String>>>,
        val paragraphs: List<String>
    ) {
        val isEmpty: Boolean get() = tables.isEmpty() && paragraphs.isEmpty()
    }

    fun read(input: InputStream): Doc {
        val bytes = input.readBytes()
        val xml = readEntry(bytes, "word/document.xml") ?: return Doc(emptyList(), emptyList())
        return parse(xml)
    }

    /** zip 里有没有 word/document.xml —— 用来把「改名成 zip 的 docx」也认出来 */
    fun looksLikeDocx(bytes: ByteArray): Boolean = readEntry(bytes, "word/document.xml") != null

    private fun readEntry(bytes: ByteArray, path: String): ByteArray? {
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (!e.isDirectory && e.name == path) return zis.readBytes()
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parse(xml: ByteArray): Doc {
        val tables = ArrayList<List<List<String>>>()
        val paragraphs = ArrayList<String>()

        // 当前表格
        var tableRows: ArrayList<List<String>>? = null
        var rowCells: ArrayList<String>? = null
        var cellText: StringBuilder? = null
        var tableDepth = 0

        // 表格外的段落
        var paraText: StringBuilder? = null
        var inText = false

        val parser = Xml.newPullParser()
        parser.setInput(xml.inputStream(), null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when {
                event == XmlPullParser.START_TAG && name == "tbl" -> {
                    tableDepth++
                    // 只收一级表格；嵌套表格的内容并进外层单元格，不单独成表
                    if (tableDepth == 1) {
                        tableRows = ArrayList()
                    }
                }

                event == XmlPullParser.END_TAG && name == "tbl" -> {
                    if (tableDepth == 1) {
                        tableRows?.let { rows ->
                            if (rows.any { r -> r.any { it.isNotBlank() } }) tables.add(rows)
                        }
                        tableRows = null
                    }
                    if (tableDepth > 0) tableDepth--
                }

                event == XmlPullParser.START_TAG && name == "tr" && tableDepth == 1 -> {
                    rowCells = ArrayList()
                }

                event == XmlPullParser.END_TAG && name == "tr" && tableDepth == 1 -> {
                    rowCells?.let { tableRows?.add(it) }
                    rowCells = null
                }

                event == XmlPullParser.START_TAG && name == "tc" && tableDepth == 1 -> {
                    cellText = StringBuilder()
                }

                event == XmlPullParser.END_TAG && name == "tc" && tableDepth == 1 -> {
                    val v = cellText?.toString()?.trim()?.replace(Regex("[ \\t]*\\n[ \\t]*"), " ") ?: ""
                    rowCells?.add(v)
                    cellText = null
                }

                // 单元格里的多个段落用换行分隔，最后统一压成空格
                event == XmlPullParser.START_TAG && name == "p" &&
                        tableDepth == 1 && cellText != null -> {
                    if (cellText!!.isNotEmpty()) cellText!!.append('\n')
                }

                event == XmlPullParser.START_TAG && name == "p" && tableDepth == 0 -> {
                    paraText = StringBuilder()
                }

                event == XmlPullParser.END_TAG && name == "p" && tableDepth == 0 -> {
                    paraText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { paragraphs.add(it) }
                    paraText = null
                }

                event == XmlPullParser.START_TAG && name == "t" -> inText = true

                event == XmlPullParser.END_TAG && name == "t" -> inText = false

                event == XmlPullParser.TEXT && inText -> {
                    if (cellText != null) cellText!!.append(parser.text)
                    else paraText?.append(parser.text)
                }

                event == XmlPullParser.START_TAG && name == "tab" -> {
                    if (cellText != null) cellText!!.append(' ')
                    else paraText?.append(' ')
                }

                event == XmlPullParser.START_TAG && name == "br" -> {
                    if (cellText != null) cellText!!.append('\n')
                }
            }
            event = parser.next()
        }

        return Doc(tables, paragraphs)
    }
}
