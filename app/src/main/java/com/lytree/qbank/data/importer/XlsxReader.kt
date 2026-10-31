package com.lytree.qbank.data.importer

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 极简 XLSX 读取器。
 * 不引入 Apache POI（体积大、Android 兼容差），直接按 OOXML 规范解 zip + 拉式解析 XML。
 *
 * 支持：sharedStrings、inlineStr、数字、布尔、公式缓存值；多 sheet 全部读出来（[readAll]），
 * 只需一份数据时用 [read] 取第一张有内容的。
 * 用 XmlPullParserFactory（而非 android.util.Xml）取解析器，JVM 单元测试加 kxml2 即可复用。
 */
object XlsxReader {

    private val parserFactory = XmlPullParserFactory.newInstance()

    private fun newParser(): XmlPullParser = parserFactory.newPullParser()

    data class Sheet(val name: String, val rows: List<List<String>>)

    /** 只要一份数据（答题卡、词表）：第一张有内容的 sheet，都没有就给第一张 */
    fun read(input: InputStream): Sheet {
        val sheets = readAll(input)
        return sheets.firstOrNull { it.rows.size > 1 }
            ?: sheets.firstOrNull()
            ?: Sheet("Sheet1", emptyList())
    }

    /**
     * 读出全部工作表（按工作表顺序，跳过完全没有内容的那几张）。
     *
     * 「一份 Excel 里有多份试卷」最常见的写法就是**一个工作表一份卷**，所以这里不能只取第一张。
     */
    fun readAll(input: InputStream): List<Sheet> {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory) {
                    entries[e.name] = zis.readBytes()
                }
            }
        }

        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])

        // sheet 名表
        val sheetNames = parseWorkbookSheets(entries["xl/workbook.xml"])

        val sheetFiles = entries.keys
            .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { extractSheetIndex(it) }

        return sheetFiles.mapNotNull { sf ->
            val rows = parseSheet(entries[sf]!!, shared).trimTrailingBlankRows()
            if (rows.isEmpty()) return@mapNotNull null
            val idx = extractSheetIndex(sf)
            Sheet(sheetNames.getOrElse(idx) { "Sheet$idx" }, rows)
        }
    }

    /** 末尾的空行没有意义，留着只会让「最后一张表」被误判成有内容 */
    private fun List<List<String>>.trimTrailingBlankRows(): List<List<String>> {
        var end = size
        while (end > 0 && this[end - 1].all { it.isBlank() }) end--
        return if (end == size) this else subList(0, end)
    }

    private fun extractSheetIndex(path: String): Int =
        Regex("sheet(\\d+)\\.xml").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun parseWorkbookSheets(xml: ByteArray?): Map<Int, String> {
        if (xml == null) return emptyMap()
        val out = HashMap<Int, String>()
        val parser = newParser()
        parser.setInput(xml.inputStream(), null)
        var event = parser.eventType
        var order = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                out[++order] = parser.getAttributeValue(null, "name") ?: "Sheet$order"
            }
            event = parser.next()
        }
        return out
    }

    private fun parseSharedStrings(xml: ByteArray?): List<String> {
        if (xml == null) return emptyList()
        val out = ArrayList<String>()
        val parser = newParser()
        parser.setInput(xml.inputStream(), null)
        var event = parser.eventType
        var cur = StringBuilder()
        var inSi = false
        var inT = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when {
                event == XmlPullParser.START_TAG && parser.name == "si" -> {
                    inSi = true; cur = StringBuilder()
                }
                event == XmlPullParser.START_TAG && parser.name == "t" -> inT = true
                event == XmlPullParser.TEXT && inT -> cur.append(parser.text)
                event == XmlPullParser.END_TAG && parser.name == "t" -> inT = false
                event == XmlPullParser.END_TAG && parser.name == "si" -> {
                    inSi = false
                    out.add(cur.toString())
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun parseSheet(xml: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val parser = newParser()
        parser.setInput(xml.inputStream(), null)

        var event = parser.eventType
        var curRow: MutableList<String>? = null
        var curCell: StringBuilder? = null
        var cellType = ""
        var cellCol = 0
        var nextCol = 0
        var inValue = false
        var inInlineT = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when {
                event == XmlPullParser.START_TAG && parser.name == "row" -> {
                    curRow = ArrayList()
                    nextCol = 0
                }
                event == XmlPullParser.START_TAG && parser.name == "c" -> {
                    val ref = parser.getAttributeValue(null, "r")
                    cellCol = if (ref != null) colIndexOf(ref) else nextCol
                    cellType = parser.getAttributeValue(null, "t") ?: ""
                    curCell = StringBuilder()
                }
                event == XmlPullParser.START_TAG && parser.name == "v" -> inValue = true
                event == XmlPullParser.START_TAG && parser.name == "t" && cellType == "inlineStr" -> inInlineT = true
                event == XmlPullParser.TEXT && (inValue || inInlineT) -> curCell?.append(parser.text)
                event == XmlPullParser.END_TAG && parser.name == "v" -> inValue = false
                event == XmlPullParser.END_TAG && parser.name == "t" -> inInlineT = false
                event == XmlPullParser.END_TAG && parser.name == "c" -> {
                    val row = curRow ?: ArrayList()
                    val raw = curCell?.toString() ?: ""
                    val value = when (cellType) {
                        "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        "inlineStr" -> raw
                        "b" -> if (raw == "1") "TRUE" else "FALSE"
                        "str" -> raw
                        else -> raw
                    }
                    // 补齐中间空列
                    while (row.size < cellCol) row.add("")
                    if (row.size == cellCol) row.add(value) else row[cellCol] = value
                    nextCol = cellCol + 1
                    curCell = null
                }
                event == XmlPullParser.END_TAG && parser.name == "row" -> {
                    curRow?.let { rows.add(it) }
                    curRow = null
                }
            }
            event = parser.next()
        }
        return rows
    }

    /** "AB12" -> 列索引 27 */
    fun colIndexOf(ref: String): Int {
        var idx = 0
        for (c in ref) {
            if (c in 'A'..'Z') idx = idx * 26 + (c - 'A' + 1)
            else if (c in 'a'..'z') idx = idx * 26 + (c - 'a' + 1)
            else break
        }
        return (idx - 1).coerceAtLeast(0)
    }
}
