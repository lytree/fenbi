package com.lytree.qbank.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lytree.qbank.R

/**
 * 材料渲染器。
 *
 * 题库里的「材料」字段是一段纯文本，里面可能夹着表格。行测资料分析的表格是核心信息，
 * 如果只按纯文本渲染会挤成一坨，所以这里做两件事：
 *
 * 1. 识别 Markdown 风格表格（`|` 分隔、`---` 分隔行），渲染成真正的表格控件；
 * 2. 其余部分按段落渲染，首行缩进、保留换行。
 *
 * 支持的写法：
 * ```
 * | 序号 | 名称 | 回收重量 |
 * | 1 | 废钢铁 | 25021.0 |
 * ```
 * 也支持用制表符或多个空格对齐的裸表格行（每行单元格数一致时自动识别）。
 */
object MaterialRenderer {

    /** 把材料文本渲染进 [container]。 */
    fun render(context: Context, container: ViewGroup, material: String) {
        container.removeAllViews()
        if (material.isBlank()) return

        val blocks = parse(material)
        blocks.forEach { block ->
            when (block) {
                is Block.Table -> container.addView(buildTable(context, block))
                is Block.Paragraph -> container.addView(buildParagraph(context, block.text))
            }
        }
    }

    // ---------------- 解析 ----------------

    private sealed class Block {
        data class Paragraph(val text: String) : Block()
        data class Table(val rows: List<List<String>>, val headerRows: Int) : Block()
    }

    private fun parse(material: String): List<Block> {
        val lines = material.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<Block>()
        val buf = ArrayList<String>()

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()

            // Markdown 表格：当前行含 | 且下一行是分隔行
            if (trimmed.contains('|') && i + 1 < lines.size && isSeparator(lines[i + 1])) {
                flushParagraph(blocks, buf)
                val rows = ArrayList<List<String>>()
                var headerRows = 0
                // 表头行
                rows.add(splitCells(trimmed))
                headerRows = 1
                i += 2 // 跳过表头与分隔行
                // 数据行：连续的含 | 行
                while (i < lines.size) {
                    val l = lines[i].trim()
                    if (!l.contains('|') || isSeparator(l)) break
                    rows.add(splitCells(l))
                    i++
                }
                blocks.add(Block.Table(rows, headerRows))
                continue
            }

            // 裸表格：连续多行、每行按制表符/多空格切分且列数一致
            val bare = readBareTable(lines, i)
            if (bare != null) {
                flushParagraph(blocks, buf)
                blocks.add(Block.Table(bare.rows, bare.headerRows))
                i = bare.next
                continue
            }

            if (trimmed.isEmpty()) {
                flushParagraph(blocks, buf)
            } else {
                buf.add(raw)
            }
            i++
        }
        flushParagraph(blocks, buf)
        return blocks
    }

    private fun flushParagraph(blocks: MutableList<Block>, buf: MutableList<String>) {
        if (buf.isEmpty()) return
        val text = buf.joinToString("\n").trim()
        if (text.isNotEmpty()) blocks.add(Block.Paragraph(text))
        buf.clear()
    }

    /** `|---|---|:--:|` 这类分隔行 */
    private fun isSeparator(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || !t.contains('-')) return false
        return t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
    }

    private fun splitCells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        return t.split('|').map { it.trim() }
    }

    private data class BareTable(val rows: List<List<String>>, val headerRows: Int, val next: Int)

    /**
     * 尝试从 [start] 处读一个「裸表格」：连续 2 行以上，每行用制表符或 2+ 空格分隔，
     * 且各行列数一致。没有则返回 null。
     */
    private fun readBareTable(lines: List<String>, start: Int): BareTable? {
        val rows = ArrayList<List<String>>()
        var i = start
        var cols = -1
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.isEmpty()) break
            val cells = splitBare(t)
            if (cells.size < 2) break
            if (cols == -1) cols = cells.size else if (cells.size != cols) break
            rows.add(cells)
            i++
        }
        if (rows.size < 2) return null
        // 首行看起来像表头（含"名称""单位""年份"等）时，标记为表头
        val headerHint = listOf("名称", "单位", "序号", "项目", "指标", "类别", "品种")
        val headerRows = if (rows.first().any { c -> headerHint.any { c.contains(it) } }) 1 else 0
        return BareTable(rows, headerRows, i)
    }

    private fun splitBare(line: String): List<String> {
        val byTab = line.split('\t').map { it.trim() }
        if (byTab.size >= 2 && byTab.all { it.isNotEmpty() }) return byTab
        return line.split(Regex("\\s{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ---------------- 渲染 ----------------

    private fun buildParagraph(context: Context, text: String): View {
        val tv = TextView(context)
        tv.text = text
        tv.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        tv.textSize = 14f
        tv.setLineSpacing(dp(context, 5f).toFloat(), 1f)
        tv.setPadding(0, dp(context, 2f), 0, dp(context, 2f))
        return tv
    }

    private fun buildTable(context: Context, table: Block.Table): View {
        val rows = table.rows
        val colCount = rows.maxOf { it.size }
        if (colCount == 0) return View(context)

        // 资料分析的表格列多，窄屏里塞不下：
        // 外面套一层横向滚动，表格按内容宽度撑开，滚动查看其余列
        val scroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val tableLayout = android.widget.TableLayout(context)
        tableLayout.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tableLayout.isShrinkAllColumns = false
        // 列宽按内容自适应，不做等分，避免长表头被压成竖排字
        for (c in 0 until colCount) tableLayout.setColumnShrinkable(c, false)

        // 每列的最小宽度：按该列最长内容的字符数估算
        val minWidths = IntArray(colCount) { c ->
            var longest = 0
            var est = 0
            for (row in rows) {
                val cellText = row.getOrElse(c) { "" }.trim()
                if (cellText.length > longest) longest = cellText.length
                var w = 0
                for (ch in cellText) {
                    w += if (ch.code > 0x2E80) 2 else 1
                }
                if (w > est) est = w
            }
            // 取「最宽的一行」与「表头长度」的较小值，避免过宽
            dp(context, 12f) * 2 + est.coerceAtMost(longest * 2 + 4) * dp(context, 6.5f)
        }

        rows.forEachIndexed { rowIndex, row ->
            val tr = android.widget.TableRow(context)

            for (c in 0 until colCount) {
                val value = row.getOrElse(c) { "" }
                val isHeader = rowIndex < table.headerRows
                val cell = TextView(context)
                cell.text = value
                cell.textSize = 12.5f
                cell.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (isHeader) R.color.text_primary else R.color.text_secondary
                    )
                )
                cell.gravity = Gravity.CENTER
                cell.setPadding(dp(context, 8f), dp(context, 7f), dp(context, 8f), dp(context, 7f))
                cell.setBackgroundResource(
                    if (isHeader) R.drawable.bg_fb_table_cell_header else R.drawable.bg_fb_table_cell
                )
                if (isHeader) cell.setTypeface(cell.typeface, android.graphics.Typeface.BOLD)
                cell.minWidth = minWidths[c]
                cell.layoutParams = android.widget.TableRow.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                tr.addView(cell)
            }
            tableLayout.addView(tr)
        }

        scroll.addView(tableLayout)

        val outer = LinearLayout(context)
        outer.orientation = LinearLayout.VERTICAL
        outer.addView(scroll)
        // 表格与后续段落的间距
        val spacer = View(context)
        spacer.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 6f)
        )
        outer.addView(spacer)
        return outer
    }

    private fun dp(context: Context, v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
    ).toInt()
}
