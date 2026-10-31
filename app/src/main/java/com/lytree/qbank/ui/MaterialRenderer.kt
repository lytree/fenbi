package com.lytree.qbank.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.lytree.qbank.R

/**
 * 材料渲染器。
 *
 * 试卷里的「材料」字段是一段纯文本，里面可能夹着表格。行测资料分析的表格是核心信息，
 * 如果只按纯文本渲染会挤成一坨，所以这里做三件事：
 *
 * 1. 识别 Markdown 风格表格（`|` 分隔、`---` 分隔行），渲染成真正的表格控件；
 * 2. 识别图片（`![说明](路径)` 或单独一行的图片路径），渲染成可点开大图的配图——
 *    资料分析里的折线图 / 柱状图 / 饼图本来就该直接用原图的图片；
 * 3. 识别 `:::chart` 代码块，用 [ChartView] 按数据画图（纯文本试卷没有配图时可用）；
 * 4. 其余部分按段落渲染，首行缩进、保留换行。
 *
 * 支持的写法：
 * ```
 * | 序号 | 名称 | 回收重量 |
 * | 1 | 废钢铁 | 25021.0 |
 * ```
 * 也支持用制表符或多个空格对齐的裸表格行（每行单元格数一致时自动识别）。
 *
 * 图表写法（资料分析常考的折线图 / 柱状图）：
 * ```
 * :::chart
 * title: 2024-2025年2月某企业总收入同比增速（单位：%）
 * type: line
 * x: 2024年1-2月, 1-3月, 1-4月
 * series: 总收入同比增速=8.9, 7.2, 7.0
 * series: 研发费用同比增速=3.7, 4.3, 2.8
 * :::
 * ```
 */
object MaterialRenderer {

    /**
     * 把材料文本渲染进 [container]。
     * [night] 为真时用夜间配色；[scale] 是字号缩放（材料弹窗里的 A- / A+ 用）。
     */
    fun render(
        context: Context,
        container: ViewGroup,
        material: String,
        night: Boolean = false,
        scale: Float = 1f,
        chartScale: Float = 1f
    ) {
        container.removeAllViews()
        if (material.isBlank()) return

        val blocks = parse(material)
        blocks.forEach { block ->
            when (block) {
                is Block.Table -> container.addView(buildTable(context, block, night, scale))
                is Block.Paragraph -> container.addView(buildParagraph(context, block.text, night, scale))
                is Block.Chart -> container.addView(buildChart(context, block.spec, night, chartScale))
                is Block.Image -> container.addView(buildImage(context, block, night))
            }
        }
    }

    // ---------------- 解析 ----------------

    private sealed class Block {
        data class Paragraph(val text: String) : Block()
        data class Table(val rows: List<List<String>>, val headerRows: Int) : Block()
        /** [spec] 为 null 表示图表块格式有误，渲染一条提示而不是把它当正文吞掉 */
        data class Chart(val spec: ChartSpec?) : Block()
        data class Image(val src: String, val alt: String) : Block()
    }

    private fun parse(material: String): List<Block> {
        val lines = material.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<Block>()
        val buf = ArrayList<String>()

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()

            // 图表块：:::chart ... :::（也接受 ```chart ... ```）
            val chartHead = chartFence(trimmed)
            if (chartHead != null) {
                flushParagraph(blocks, buf)
                val body = ArrayList<String>()
                var j = i + 1
                while (j < lines.size) {
                    val t = lines[j].trim()
                    if (t == chartHead) {
                        j++
                        break
                    }
                    body.add(lines[j])
                    j++
                }
                blocks.add(Block.Chart(parseChartSpec(body)))
                i = j
                continue
            }

            // 图片：![说明](路径) 或单独一行的图片路径
            val image = parseImageLine(trimmed)
            if (image != null) {
                flushParagraph(blocks, buf)
                blocks.add(Block.Image(image.first, image.second))
                i++
                continue
            }

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

    // ---------------- 图表 ----------------

    /** `:::chart` / ```chart 返回对应的结束围栏，否则 null */
    private fun chartFence(line: String): String? = when {
        line.startsWith(":::chart", true) -> ":::"
        line.startsWith("```chart", true) -> "```"
        else -> null
    }

    /** 解析图表块。缺 x 轴或没有数据系列时视为无效，渲染一条提示。 */
    private fun parseChartSpec(body: List<String>): ChartSpec? {
        var title = ""
        var type = ChartSpec.Type.LINE
        var unit = ""
        var showValues = true
        var minY: Double? = null
        var maxY: Double? = null
        val xLabels = ArrayList<String>()
        val series = ArrayList<ChartSpec.Series>()

        body.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEach
            val sep = line.indexOfFirst { it == ':' || it == '：' }
            if (sep <= 0) return@forEach
            val key = line.substring(0, sep).trim().lowercase()
            val value = line.substring(sep + 1).trim()
            when (key) {
                "title", "标题" -> title = value
                "type", "类型", "chart" -> type =
                    if (value.contains("bar", true) || value.contains("柱")) ChartSpec.Type.BAR
                    else ChartSpec.Type.LINE
                "x", "x轴", "横轴", "labels" -> xLabels.addAll(splitList(value))
                "series", "y", "数据", "values" -> parseSeries(value, series)
                "unit", "单位" -> unit = value
                "showvalues", "show_values", "标注数值" ->
                    showValues = !(value == "0" || value.equals("false", true) || value == "否")
                "min", "ymin", "最小值" -> minY = value.toDoubleOrNull()
                "max", "ymax", "最大值" -> maxY = value.toDoubleOrNull()
            }
        }

        if (xLabels.isEmpty() || series.isEmpty()) return null
        return ChartSpec(
            title = title,
            type = type,
            xLabels = xLabels,
            series = series,
            unit = unit,
            showValues = showValues,
            minY = minY,
            maxY = maxY
        )
    }

    /**
     * `系列名=8.9, 7.2, -`。不写名字时按序号补「系列N」；
     * 缺失值（`-` / 空 / 非数字）记作 NaN，折线在这一点断开。
     */
    private fun parseSeries(value: String, out: MutableList<ChartSpec.Series>) {
        val eq = value.indexOfFirst { it == '=' || it == '＝' }
        val name: String
        val rest: String
        if (eq > 0) {
            name = value.substring(0, eq).trim()
            rest = value.substring(eq + 1)
        } else {
            name = "系列${out.size + 1}"
            rest = value
        }
        val values = splitList(rest).map { it.toDoubleOrNull() ?: Double.NaN }
        if (values.isNotEmpty()) out.add(ChartSpec.Series(name, values))
    }

    private fun splitList(value: String): List<String> =
        value.split(Regex("[,，、|;；\\s]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // ---------------- 图片 ----------------

    private val MD_IMAGE = Regex("^!\\[([^\\]]*)]\\((.+)\\)$")
    private val IMAGE_EXT = Regex("(?i).*\\.(png|jpe?g|webp|gif|bmp)(\\?.*)?$")

    /**
     * 图片行：`![图1](路径)`、`[图片](路径)`，或整行就是一个图片地址
     * （`/sdcard/...`、`file://`、`content://`、`asset://`、`data:image/...`）。
     * 返回 (地址, 说明)，不是图片行返回 null。
     */
    private fun parseImageLine(line: String): Pair<String, String>? {
        MD_IMAGE.matchEntire(line)?.let {
            return it.groupValues[2].trim() to it.groupValues[1].trim()
        }
        val t = line.removePrefix("[图片]").trim()
        if (!IMAGE_EXT.matches(t) && !t.startsWith("data:image/", true)) return null
        val looksLikePath = t.contains("://") ||
            t.startsWith("/") ||
            Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(t) ||
            t.startsWith("data:", true)
        return if (looksLikePath) t to "" else null
    }

    // ---------------- 渲染 ----------------

    private fun buildParagraph(
        context: Context,
        text: String,
        night: Boolean,
        scale: Float
    ): View {
        val tv = TextView(context)
        tv.text = text
        tv.setTextColor(
            ContextCompat.getColor(
                context, if (night) R.color.fb_night_text_body else R.color.text_primary
            )
        )
        // 材料正文比题干略小一档（题干 16sp / 材料 15sp），行距松一些，长段落更好读
        tv.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            context.resources.getDimension(R.dimen.fb_text_item_title) * scale
        )
        tv.setLineSpacing(dp(context, 6f).toFloat(), 1f)
        tv.setPadding(0, dp(context, 2f), 0, dp(context, 2f))
        return tv
    }

    private fun buildTable(
        context: Context,
        table: Block.Table,
        night: Boolean,
        scale: Float
    ): View {
        val rows = table.rows
        val colCount = rows.maxOf { it.size }
        if (colCount == 0) return View(context)

        // 资料分析的表格必须一屏放得下（参考应用从不横向裁切）：按各列内容宽度分配列宽，
        // 内容长的列多分、短的列少分，单元格里的文字自己换行。
        val weights = FloatArray(colCount) { c ->
            var longest = 0f
            for (row in rows) {
                var w = 0f
                for (ch in row.getOrElse(c) { "" }.trim()) {
                    w += if (ch.code > 0x2E80) 2f else 1f
                }
                if (w > longest) longest = w
            }
            longest.coerceIn(3f, 20f)
        }

        // 列一多，字号跟着收一档，否则再窄的屏也塞不下
        val fontSp = (
            when {
                colCount >= 9 -> 9.5f
                colCount >= 7 -> 10.5f
                else -> 12f
            } * scale
            )

        val tableLayout = FitTableLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            columnWeights = weights
            baseFontSp = fontSp
        }

        rows.forEachIndexed { rowIndex, row ->
            val tr = FillTableRow(context)
            for (c in 0 until colCount) {
                val value = row.getOrElse(c) { "" }
                val isHeader = rowIndex < table.headerRows
                val cell = TextView(context)
                cell.text = value
                cell.textSize = fontSp
                cell.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (night) {
                            if (isHeader) R.color.fb_night_text else R.color.fb_night_text_body
                        } else {
                            if (isHeader) R.color.text_primary else R.color.text_secondary
                        }
                    )
                )
                cell.gravity = Gravity.CENTER
                cell.minWidth = 0
                cell.setPadding(
                    dp(context, 4f), dp(context, 7f), dp(context, 4f), dp(context, 7f)
                )
                cell.setBackgroundResource(
                    when {
                        night && isHeader -> R.drawable.bg_fb_table_cell_header_night
                        night -> R.drawable.bg_fb_table_cell_night
                        isHeader -> R.drawable.bg_fb_table_cell_header
                        else -> R.drawable.bg_fb_table_cell
                    }
                )
                if (isHeader) cell.setTypeface(cell.typeface, android.graphics.Typeface.BOLD)
                cell.layoutParams = android.widget.TableRow.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                tr.addView(cell)
            }
            tableLayout.addView(
                tr,
                android.widget.TableLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val outer = LinearLayout(context)
        outer.orientation = LinearLayout.VERTICAL
        outer.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        outer.setPadding(0, dp(context, 4f), 0, dp(context, 4f))
        outer.addView(tableLayout)
        return outer
    }

    /**
     * 表格宽度自适应：测量时把可用宽度按列权重分给每一列，
     * 表格因此永远不超过材料可用宽度，不会再出现右侧被裁掉一列。
     */
    private class FitTableLayout(context: Context) : android.widget.TableLayout(context) {

        var columnWeights: FloatArray = FloatArray(0)
        var baseFontSp: Float = 12f
        private var appliedScale: Float = -1f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val avail = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            if (avail > 0 && columnWeights.isNotEmpty()) {
                // 除以列数得到平均列宽：分屏左栏 / 窄屏塞不下时按比例缩字号（参考应用也是这么做的），
                // 保证表格永远完整显示在可视宽度里
                val per = avail.toFloat() / columnWeights.size
                val factor = when {
                    per < 46 * resources.displayMetrics.density -> 0.6f
                    per < 60 * resources.displayMetrics.density -> 0.7f
                    per < 76 * resources.displayMetrics.density -> 0.83f
                    else -> 1f
                }
                if (factor != appliedScale) {
                    appliedScale = factor
                    for (r in 0 until childCount) {
                        val row = getChildAt(r) as? android.widget.TableRow ?: continue
                        for (c in 0 until row.childCount) {
                            val cell = row.getChildAt(c) as? TextView ?: continue
                            cell.setTextSize(
                                TypedValue.COMPLEX_UNIT_SP, baseFontSp * factor
                            )
                        }
                    }
                }
                // 单元格左右 padding 4dp + 文字至少两个字，给出最小列宽，避免「序号」被拆成竖排
                val minCell = (40 * resources.displayMetrics.density).toInt()
                val widths = fitWidths(avail, columnWeights, minCell)
                for (r in 0 until childCount) {
                    val row = getChildAt(r) as? android.widget.TableRow ?: continue
                    val count = minOf(row.childCount, widths.size)
                    for (c in 0 until count) {
                        val cell = row.getChildAt(c)
                        val lp = cell.layoutParams as? android.widget.TableRow.LayoutParams
                        if (lp != null && lp.width != widths[c]) {
                            lp.width = widths[c]
                            cell.forceLayout()
                        }
                    }
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        /** 按权重分宽度，不足保底宽度的列先补到保底，差额再从宽列里按比例扣回来 */
        private fun fitWidths(avail: Int, weights: FloatArray, minCell: Int): IntArray {
            val n = weights.size
            if (n == 0) return IntArray(0)
            val total = weights.sum().coerceAtLeast(0.001f)
            val out = IntArray(n) { (avail * weights[it] / total).toInt().coerceAtLeast(1) }
            // 列太密（保底宽度总和都超过可用宽度）就放弃保底，纯按比例分，宁可字小一点也别裁
            if (n * minCell >= avail) return out
            var deficit = 0
            val flexible = ArrayList<Int>(n)
            for (i in 0 until n) {
                if (out[i] < minCell) {
                    deficit += minCell - out[i]
                    out[i] = minCell
                } else {
                    flexible.add(i)
                }
            }
            if (deficit > 0 && flexible.isNotEmpty()) {
                val flexTotal = flexible.sumOf { out[it] }.toFloat().coerceAtLeast(1f)
                for (i in flexible) {
                    out[i] = (out[i] - deficit * out[i] / flexTotal).toInt().coerceAtLeast(minCell)
                }
            }
            // 取整误差补到最后一列，保证整表正好等于可用宽度
            val diff = avail - out.sum()
            out[out.lastIndex] = (out[out.lastIndex] + diff).coerceAtLeast(1)
            return out
        }
    }

    /** 一行里矮的单元格只画到自己内容高度会让表格竖线断开，这里统一拉满整行高度 */
    private class FillTableRow(context: Context) : android.widget.TableRow(context) {
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var x = paddingLeft
            val top = paddingTop
            val bottom = height - paddingBottom
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.layout(x, top, x + c.measuredWidth, bottom)
                x += c.measuredWidth
            }
        }
    }

    /**
     * 材料配图：按原图比例铺满材料宽度（最高 420dp），点一下整屏看。
     * 加载中先占位，失败直接写清是哪张图没取到——刷题时最怕「图没了但不知道哪张」。
     */
    private fun buildImage(context: Context, block: Block.Image, night: Boolean): View {
        val metaColor = ContextCompat.getColor(
            context, if (night) R.color.fb_night_text_meta else R.color.fb_text_meta
        )
        val holder = FrameLayout(context)
        holder.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(context, 6f)
            bottomMargin = dp(context, 6f)
        }

        val hint = TextView(context)
        hint.text = context.getString(R.string.material_image_loading)
        hint.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            context.resources.getDimension(R.dimen.fb_text_item_sub)
        )
        hint.setTextColor(metaColor)
        hint.gravity = Gravity.CENTER
        hint.setPadding(0, dp(context, 26f), 0, dp(context, 26f))
        hint.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        holder.addView(hint)

        val image = RatioImageView(context, dp(context, 420f))
        image.visibility = View.GONE
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.contentDescription = block.alt
        image.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        holder.addView(image)

        val reqWidth = (context.resources.displayMetrics.widthPixels - dp(context, 88f))
            .coerceAtLeast(dp(context, 240f))
        ImageLoader.load(context, block.src, reqWidth) { bmp ->
            if (bmp == null) {
                hint.text = context.getString(R.string.material_image_failed, block.src)
            } else {
                hint.visibility = View.GONE
                image.setImageBitmap(bmp)
                image.ratio = bmp.height.toFloat() / bmp.width.coerceAtLeast(1)
                image.visibility = View.VISIBLE
                image.requestLayout()
                image.setOnClickListener {
                    context.findActivity()?.let { ImagePreviewDialog.show(it, bmp) }
                }
            }
        }
        return holder
    }

    /** 高度按原图比例算，超过 [maxHeight] 就截住（长截图不会一屏糊满） */
    private class RatioImageView(context: Context, private val maxHeight: Int) :
        AppCompatImageView(context) {

        var ratio: Float = 0f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            if (ratio > 0f && w > 0) {
                setMeasuredDimension(w, (w * ratio).toInt().coerceAtMost(maxHeight))
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }

    /** 图表块：数据合法时画图；格式不对时给一条提示，别静默吞掉内容 */
    private fun buildChart(
        context: Context,
        spec: ChartSpec?,
        night: Boolean,
        chartScale: Float
    ): View {
        if (spec == null) {
            val tv = TextView(context)
            tv.text = context.getString(R.string.material_chart_invalid)
            tv.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                context.resources.getDimension(R.dimen.fb_text_item_sub)
            )
            tv.setTextColor(
                ContextCompat.getColor(
                    context, if (night) R.color.fb_night_text_meta else R.color.fb_text_meta
                )
            )
            tv.setPadding(0, dp(context, 4f), 0, dp(context, 4f))
            return tv
        }
        val chart = ChartView(context)
        chart.spec = spec
        chart.night = night
        chart.heightScale = chartScale
        chart.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(context, 6f)
            bottomMargin = dp(context, 6f)
        }
        return chart
    }

    private fun dp(context: Context, v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
    ).toInt()
}
