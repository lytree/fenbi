package com.lytree.qbank.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
 *
 * 图文排版：一段材料里图和字怎么摆，默认是「按原文顺序竖着排」，
 * 想并排或者换顺序就包一个 `:::figure` 块（见 [Block.Figure]）：
 * ```
 * :::figure
 * layout: left      # left 左图右文 / right 右图左文 / top 图在上 / bottom 图在下 / row 多图并排
 * width: 38%        # 并排时图片占的宽度比例（0.15~0.7）
 * size: natural     # natural 按原图尺寸（最多放大 2 倍）/ full 铺满这一栏
 * ![2024年各月增速](images/chart-01.png)
 * 上图是 2024 年各月的同比增速，可以看出 3 月之后一路走低……
 * :::
 * ```
 * 单张图也能单独指定尺寸：行尾写 `{size=full}`（或 `{full}`）让这张图铺满宽度。
 */
object MaterialRenderer {

    /**
     * 配图最多放大到原图像素的几倍。
     *
     * 图形推理的选项图常常只有几十像素（从截图里切出来的），铺满格子要放大三四倍，糊成一片；
     * 2 倍是「还能看」的折中——再想看清细节就点图看大图。
     */
    private const val UPSCALE_CAP = 2

    /** 图文块里图片的最高高度（左右并排时图不能顶着半屏，否则文字只剩一条缝） */
    private const val FIGURE_MAX_HEIGHT_DP = 320f

    /** 多图并排时一行最多几张 */
    private const val ROW_MAX_PER_LINE = 3

    /** 多图并排时单张图的最高高度（格子只有屏宽三分之一） */
    private const val ROW_MAX_HEIGHT_DP = 200f

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
                is Block.Image -> container.addView(
                    buildImage(
                        context, block.image.src, block.image.alt, night,
                        fillWidth = block.image.size == ImageSize.FULL
                    )
                )
                is Block.Figure -> if (block.images.isNotEmpty() || block.text.isNotEmpty()) {
                    container.addView(buildFigure(context, block, night, scale))
                }
            }
        }
    }

    // ---------------- 解析 ----------------

    /** 图文块的排版方式（`:::figure` 的 `layout:`） */
    internal enum class FigureLayout {
        /** 左图右文 */
        LEFT,

        /** 右图左文 */
        RIGHT,

        /** 图在上、文字在下（不写 layout 时的默认值） */
        TOP,

        /** 文字在上、图在下 */
        BOTTOM,

        /** 多图横向并排，文字在图下方 */
        ROW
    }

    /** 图片宽度口径：按原图（最多放大 [UPSCALE_CAP] 倍）还是铺满这一栏 */
    internal enum class ImageSize { NATURAL, FULL }

    internal sealed class Block {
        data class Paragraph(val text: String) : Block()
        data class Table(val rows: List<List<String>>, val headerRows: Int) : Block()
        /** [spec] 为 null 表示图表块格式有误，渲染一条提示而不是把它当正文吞掉 */
        data class Chart(val spec: ChartSpec?) : Block()
        data class Image(val image: ImageRef) : Block()
        /**
         * 一段「图 + 文字」的组合块：图和字按 [layout] 摆，图片占 [widthRatio] 宽度
         * （只在左右并排时生效），[text] 为空就只排图。
         */
        data class Figure(
            val layout: FigureLayout,
            val widthRatio: Float,
            val size: ImageSize,
            val images: List<ImageRef>,
            val text: String
        ) : Block()
    }

    /** 一处图片引用。写法与材料 / 选项共用一套，[size] 来自行尾的 `{size=…}` */
    internal data class ImageRef(
        val src: String,
        val alt: String = "",
        val size: ImageSize = ImageSize.NATURAL
    )

    internal fun parse(material: String): List<Block> {
        val lines = material.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<Block>()
        val buf = ArrayList<String>()

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()

            // 图文块：:::figure ... :::（也接受 ```figure ... ```）
            val figureHead = figureFence(trimmed)
            if (figureHead != null) {
                flushParagraph(blocks, buf)
                val body = ArrayList<String>()
                var j = i + 1
                while (j < lines.size) {
                    val t = lines[j].trim()
                    if (t == figureHead.first) {
                        j++
                        break
                    }
                    body.add(lines[j])
                    j++
                }
                blocks.add(parseFigure(figureHead.second, body))
                i = j
                continue
            }

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
                blocks.add(Block.Image(image))
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

    // ---------------- 图文块 ----------------

    /** 并排时图片默认占的宽度比例（分屏、窄屏都还留得住半行字） */
    private const val DEFAULT_FIGURE_WIDTH = 0.38f

    /** `:::figure` / ```figure 返回（结束围栏，围栏后面的行内参数）。行内可以直接写布局：`:::figure left` */
    private fun figureFence(line: String): Pair<String, String>? {
        val fence = when {
            line.startsWith(":::figure", true) -> ":::"
            line.startsWith("```figure", true) -> "```"
            else -> return null
        }
        return fence to line.substring(9).trim()
    }

    /**
     * 解析图文块。块内只认三种指令（写在块首，正文开始后就不再当指令看）：
     * `layout:` 排版、`width:` 并排时图片宽度占比、`size:` 图片尺寸口径；其余行按图片 / 正文处理。
     *
     * 没有图片时渲染成普通段落，没有文字时只排图——写歪了顶多是「排版没生效」，不会吞内容。
     */
    internal fun parseFigure(head: String, body: List<String>): Block.Figure {
        var layout = parseFigureLayout(head) ?: FigureLayout.TOP
        var ratio = DEFAULT_FIGURE_WIDTH
        var size = ImageSize.NATURAL
        var directives = true
        val images = ArrayList<ImageRef>()
        val text = ArrayList<String>()

        body.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (directives) {
                val d = splitDirective(line)
                if (d != null) {
                    when (d.first) {
                        "layout", "排版", "布局" -> parseFigureLayout(d.second)?.let { layout = it }
                        "width", "宽度", "图宽" -> parseWidthRatio(d.second)?.let { ratio = it }
                        "size", "尺寸", "fit", "适配" -> size = parseImageSize(d.second)
                    }
                    return@forEach
                }
                directives = false
            }
            val img = parseImageLine(line)
            if (img != null) images.add(img) else text.add(line)
        }

        return Block.Figure(layout, ratio, size, images, text.joinToString("\n").trim())
    }

    private val FIGURE_KEYS = setOf(
        "layout", "排版", "布局", "width", "宽度", "图宽", "size", "尺寸", "fit", "适配"
    )

    /** `key: value` 指令行。key 不在 [FIGURE_KEYS] 里就当正文（正文句子里带冒号很正常） */
    private fun splitDirective(line: String): Pair<String, String>? {
        val sep = line.indexOfFirst { it == ':' || it == '：' }
        if (sep <= 0) return null
        val key = line.substring(0, sep).trim().lowercase()
        if (key !in FIGURE_KEYS) return null
        val value = line.substring(sep + 1).trim()
        if (value.isEmpty()) return null
        return key to value
    }

    /** 布局关键字：英文名、中文说法都认（`:::figure left 40%` 这种带尾巴的也认） */
    private fun parseFigureLayout(raw: String): FigureLayout? {
        val v = raw.trim().lowercase()
        if (v.isEmpty()) return null
        return when {
            v.contains("左图右文") || v.contains("图在左") || v.startsWith("left") || v == "l" -> FigureLayout.LEFT
            v.contains("右图左文") || v.contains("图在右") || v.startsWith("right") || v == "r" -> FigureLayout.RIGHT
            v.contains("并排") || v.contains("一行") || v.startsWith("row") -> FigureLayout.ROW
            v.contains("在下") || v.contains("图下") || v.startsWith("bottom") || v == "b" -> FigureLayout.BOTTOM
            v.contains("在上") || v.contains("图上") || v.startsWith("top") || v == "t" -> FigureLayout.TOP
            else -> null
        }
    }

    /** `38%` / `0.38` / `38` 都当比例；夹在 15%~75% 之间，太窄图看不清、太宽文字挤成一条 */
    private fun parseWidthRatio(raw: String): Float? {
        val v = raw.trim().removeSuffix("%").trim()
        val n = v.toFloatOrNull() ?: return null
        val ratio = if (raw.contains('%') || n > 1f) n / 100f else n
        return ratio.coerceIn(0.15f, 0.75f)
    }

    private fun parseImageSize(raw: String): ImageSize {
        val v = raw.trim().lowercase()
        return if (v.contains("full") || v.contains("fill") || v.contains("满") || v.contains("铺")) {
            ImageSize.FULL
        } else {
            ImageSize.NATURAL
        }
    }

    // ---------------- 图片 ----------------

    private val MD_IMAGE = Regex("^!\\[([^\\]]*)]\\((.+)\\)$")
    private val IMAGE_EXT = Regex("(?i).*\\.(png|jpe?g|webp|gif|bmp)(\\?.*)?$")

    /** 图片行尾的尺寸标记：`{size=full}`、`{满宽}`、`{natural}` */
    private val SIZE_SUFFIX = Regex(
        "(?i)\\{\\s*(?:(?:size|尺寸|fit|适配)\\s*[:=]\\s*)?" +
            "(full|fill|natural|original|满宽|铺满|原尺寸|原图)\\s*}\\s*$"
    )

    /** 选项正文里夹着的图片：`![说明](地址)`、`[图片](地址)` 两种写法，路径允许带空格 */
    private val MD_IMAGE_INLINE = Regex("""!\[([^\]]*)]\(\s*([^)\n]+?)\s*\)""")
    private val BRACKET_IMAGE_INLINE = Regex("""\[(?:图片|图片地址|图)]\(\s*([^)\n]+?)\s*\)""")

    /**
     * 图片行：`![图1](路径)`、`[图片](路径)`，或整行就是一个图片地址
     * （`/sdcard/...`、`file://`、`content://`、`asset://`、`data:image/...`）。
     * 行尾还可以挂尺寸：`![图](a.png){size=full}` / `{满宽}`，不写就按原图尺寸排。
     * 不是图片行返回 null。
     */
    internal fun parseImageLine(line: String): ImageRef? {
        var t = line.trim()
        var size = ImageSize.NATURAL
        SIZE_SUFFIX.find(t)?.let { m ->
            size = if (parseImageSize(m.groupValues[1]) == ImageSize.FULL) {
                ImageSize.FULL
            } else {
                ImageSize.NATURAL
            }
            t = t.substring(0, m.range.first).trim()
        }
        MD_IMAGE.matchEntire(t)?.let {
            return ImageRef(it.groupValues[2].trim(), it.groupValues[1].trim(), size)
        }
        // `[图片](路径)`：括号里没感叹号，单独认一种，别把普通链接当真
        BRACKET_IMAGE_INLINE.matchEntire(t)?.let {
            return ImageRef(it.groupValues[1].trim(), "", size)
        }
        val path = t.removePrefix("[图片]").trim()
        if (!IMAGE_EXT.matches(path) && !path.startsWith("data:image/", true)) return null
        val looksLikePath = path.contains("://") ||
            path.startsWith("/") ||
            Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path) ||
            path.startsWith("data:", true)
        return if (looksLikePath) ImageRef(path, "", size) else null
    }

    // ---------------- 选项图文 ----------------

    /** 选项里的一处图片引用（写法与材料图片同一套） */
    data class OptionImage(val src: String, val alt: String = "")

    /**
     * 选项正文拆出来的两块：[text] 是去掉图片引用后的文字，[images] 按出现顺序。
     * 图形推理那种「四个选项就是四张图」的题，选项单元格里写的就是图片地址。
     */
    data class OptionContent(val text: String, val images: List<OptionImage>) {
        val hasImages: Boolean get() = images.isNotEmpty()
    }

    /**
     * 拆选项正文。认三种写法（与材料图片同口径）：
     * `![说明](地址)`、`[图片](地址)`、整格只有一个图片地址。
     * 其余文字原样保留——文字在前、图片在后，不为了混排把一行字拆成好几段。
     */
    fun splitOption(text: String): OptionContent {
        if (text.isBlank()) return OptionContent("", emptyList())
        val images = ArrayList<OptionImage>(2)
        var rest = MD_IMAGE_INLINE.replace(text) { m ->
            images += OptionImage(m.groupValues[2].trim(), m.groupValues[1].trim())
            ""
        }
        rest = BRACKET_IMAGE_INLINE.replace(rest) { m ->
            images += OptionImage(m.groupValues[1].trim())
            ""
        }
        var plain = tidyOptionText(rest)
        if (images.isEmpty()) {
            // 整格就是一个图片地址（导出的试卷里常见）：没有别的文字才算，免得把题干话术当路径
            val rows = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (rows.size == 1) {
                parseImageLine(rows[0])?.let {
                    images += OptionImage(it.src, it.alt)
                    plain = ""
                }
            }
        }
        return OptionContent(plain, images)
    }

    /** 去掉图片引用后收拾一下：空行与行首尾空白（`![..](..)` 单独占一行时容易留下空行） */
    private fun tidyOptionText(raw: String): String =
        raw.replace("\r\n", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    /**
     * **题干 / 解析正文**里的图片：写法与选项同一套（`![说明](地址)`、`[图片](地址)`、整行就是地址），
     * 但保留原文的分段与换行——题干常常是多段文字，按行 trim 会把段落挤成一坨。
     *
     * 返回值与 [splitOption] 同型：文字走原来的 TextView（字号缩放、行距、笔迹锚点都不变），
     * 图片另外排在图区里。这样「题干里带一张图」不必把整块题面换成材料渲染器。
     */
    fun splitInline(text: String): OptionContent {
        if (text.isBlank()) return OptionContent("", emptyList())
        val images = ArrayList<OptionImage>(2)
        var rest = MD_IMAGE_INLINE.replace(text) { m ->
            images += OptionImage(m.groupValues[2].trim(), m.groupValues[1].trim())
            ""
        }
        rest = BRACKET_IMAGE_INLINE.replace(rest) { m ->
            images += OptionImage(m.groupValues[1].trim())
            ""
        }
        var plain = rest
        if (images.isEmpty()) {
            // 整段就是一个图片地址（图形推理的题干常这么写）：没有别的文字才算
            val rows = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (rows.size == 1) {
                parseImageLine(rows[0])?.let {
                    images += OptionImage(it.src, it.alt)
                    plain = ""
                }
            }
        }
        return OptionContent(tidyParagraphs(plain), images)
    }

    /** 段间空行压成一个、去掉首尾空行；段内的换行原样保留 */
    private fun tidyParagraphs(raw: String): String =
        raw.replace("\r\n", "\n")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    /** 列表 / 导入预览用的纯文字：图片引用收成「［图片］」，别把一长串路径糊到界面上 */
    fun optionSummary(text: String): String {
        val c = splitOption(text)
        if (!c.hasImages) return c.text
        return listOf(c.text, "［图片］").filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * 把选项里的图片铺进 [container]（按选项正文宽度，最高 240dp）。
     *
     * 尺寸口径与材料配图一致：按原图尺寸排、最多放大 2 倍，小图居中、不被拉满一行。
     * 材料配图点一下整屏看，这里也一样——选项整行仍然点一下选中，
     * 但**落在图上的点击**给预览（字母圈照样能选中，见 [QuestionCardBinder.bindOptionInteractions]）。
     *
     * [compact] / [widthPx] / [maxHeightDp] 给「四图一行」的图形推理排版用：
     * 格子只有屏宽的四分之一，加载占位与上下留白都要收一收，解码宽度也按格子给，
     * 免得一张小图按整屏宽度解码白占内存。
     *
     * [onLongPress] 非空时图片自己吃掉长按（选项图长按 = 打叉排除），
     * 否则图片可点会挡掉父容器的长按。
     */
    fun renderOptionImages(
        context: Context,
        container: ViewGroup,
        images: List<OptionImage>,
        night: Boolean = false,
        compact: Boolean = false,
        widthPx: Int = 0,
        maxHeightDp: Float = 240f,
        onLongPress: (() -> Unit)? = null
    ) {
        container.removeAllViews()
        images.forEach { img ->
            container.addView(
                buildImage(
                    context, img.src, img.alt, night,
                    maxHeightDp = maxHeightDp,
                    previewOnTap = true,
                    onImageLongPress = onLongPress,
                    reqWidthPx = widthPx.takeIf { it > 0 },
                    compact = compact
                )
            )
        }
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
     * 配图：按原图尺寸排，**最多放大 2 倍**，绝不为了填满一行硬拉（高度顶到 [maxHeightDp] 再回缩）。
     *
     * 以前是所有图一律铺满可用宽度：图形推理那种几十像素的选项图被拉到整行，
     * 一张 66×54 的图放成 200 多像素宽，糊得看不清细节。现在的口径：
     * - 宽度 = min(可用宽度, 原图宽 × 2)，图比这更窄就居中放着；2 倍是「小图还不至于太糊」的上限；
     * - 高度按原图比例算，超过 [maxHeightDp]（材料 420dp / 选项 240dp）就按比例把宽度也收回，
     *   不留左右空白，比例始终是原图的；
     * - 点图整屏预览（按原图重新解码，不复用格子里的低清位图）。
     *
     * [onImageLongPress] 非空时图片自己吃掉长按（选项图长按 = 打叉排除），否则图可点会把父容器的长按挡掉。
     */
    private fun buildImage(
        context: Context,
        src: String,
        alt: String,
        night: Boolean,
        maxHeightDp: Float = 420f,
        previewOnTap: Boolean = true,
        onImageLongPress: (() -> Unit)? = null,
        /** 解码目标宽度：默认按整屏算；选项图排成一行时按格子宽度给 */
        reqWidthPx: Int? = null,
        /** 紧凑排版：占位与上下留白收窄，给四图一行的格子用 */
        compact: Boolean = false,
        /** 铺满这一栏：不再受「原图宽 × [UPSCALE_CAP]」的限制（`size: full` / `{size=full}`） */
        fillWidth: Boolean = false
    ): View {
        val metaColor = ContextCompat.getColor(
            context, if (night) R.color.fb_night_text_meta else R.color.fb_text_meta
        )
        val holder = FrameLayout(context)
        holder.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            val v = dp(context, if (compact) 2f else 6f)
            topMargin = v
            bottomMargin = v
        }

        val hint = TextView(context)
        hint.text = context.getString(R.string.material_image_loading)
        hint.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            context.resources.getDimension(R.dimen.fb_text_item_sub)
        )
        hint.setTextColor(metaColor)
        hint.gravity = Gravity.CENTER
        hint.setPadding(0, dp(context, if (compact) 8f else 26f), 0, dp(context, if (compact) 8f else 26f))
        hint.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        holder.addView(hint)

        val image = AdaptiveImageView(context, dp(context, maxHeightDp), fillWidth)
        image.visibility = View.GONE
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.contentDescription = alt
        // 宽度由 onMeasure 按「原图多宽」定，父容器只负责把它居中
        image.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        holder.addView(image)

        val reqWidth = reqWidthPx
            ?: (context.resources.displayMetrics.widthPixels - dp(context, 88f))
                .coerceAtLeast(dp(context, 240f))
        ImageLoader.load(context, src, reqWidth) { loaded ->
            if (loaded == null) {
                hint.text = context.getString(R.string.material_image_failed, src)
            } else {
                hint.visibility = View.GONE
                image.setImageBitmap(loaded.bitmap)
                image.ratio = loaded.srcHeight.toFloat() / loaded.srcWidth.coerceAtLeast(1)
                // 原图像素宽 ×2 就是允许的最大显示宽度：比这更宽的行不再拉，比这窄就居中放着
                image.naturalWidthPx = loaded.srcWidth * UPSCALE_CAP
                image.visibility = View.VISIBLE
                image.requestLayout()
                if (previewOnTap) {
                    // 点图 = 看大图；没设预览时图保持不可点，点击继续交给整行（选中那一项）
                    image.setOnClickListener { openPreview(context, src) }
                }
                if (onImageLongPress != null) {
                    image.setOnLongClickListener { v ->
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onImageLongPress()
                        true
                    }
                }
            }
        }
        return holder
    }

    /** 整屏看大图：按屏幕宽度重新解码，别复用选项格子里那份小图 */
    private fun openPreview(context: Context, src: String) {
        val activity = context.findActivity() ?: return
        ImagePreviewDialog.show(activity, src)
    }

    // ---------------- 图文块渲染 ----------------

    /**
     * 把 [Block.Figure] 按 layout 摆开：
     *
     * - `TOP` / `BOTTOM`：图和文字上下叠，图占整栏宽；
     * - `LEFT` / `RIGHT`：横向两列，图占 [Block.Figure.widthRatio]，文字拿剩下的，
     *   多张图在图片列里竖着摞；没有文字时退化成「按比例占一栏的图」，不留半屏空白；
     * - `ROW`：每行最多 [ROW_MAX_PER_LINE] 张图并排，文字在图下方。
     *
     * 图和文字都沿用材料正文的字号 / 配色，缩放（材料弹窗的 A- / A+）一并跟着走。
     */
    private fun buildFigure(
        context: Context,
        fig: Block.Figure,
        night: Boolean,
        scale: Float
    ): View {
        val text = if (fig.text.isNotEmpty()) buildParagraph(context, fig.text, night, scale) else null
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (fig.images.isEmpty()) return text ?: View(context)

        when (fig.layout) {
            FigureLayout.TOP -> {
                root.addView(imageColumn(context, fig, night))
                text?.let { root.addView(it) }
            }
            FigureLayout.BOTTOM -> {
                text?.let { root.addView(it) }
                root.addView(imageColumn(context, fig, night))
            }
            FigureLayout.ROW -> {
                root.addView(imageRow(context, fig, night))
                text?.let { root.addView(it) }
            }
            FigureLayout.LEFT, FigureLayout.RIGHT -> {
                if (text == null) {
                    root.addView(imageColumn(context, fig, night, reqWidthPx = columnWidthPx(context, fig)))
                } else {
                    val row = LinearLayout(context)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.gravity = Gravity.TOP
                    row.layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    val images = imageColumn(context, fig, night, reqWidthPx = columnWidthPx(context, fig))
                    val imgLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, fig.widthRatio)
                    val txtLp = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, (1f - fig.widthRatio).coerceAtLeast(0.2f)
                    )
                    // 图文之间留 8dp：贴在一起时图的边框和字挤成一团
                    val gap = dp(context, 8f)
                    if (fig.layout == FigureLayout.LEFT) {
                        row.addView(images, imgLp)
                        txtLp.marginStart = gap
                        row.addView(text, txtLp)
                    } else {
                        imgLp.marginStart = gap
                        row.addView(text, txtLp)
                        row.addView(images, imgLp)
                    }
                    root.addView(row)
                }
            }
        }
        return root
    }

    /** 并排时图片那一栏大约多宽：拿来当解码目标宽度，别按整屏解码白占内存 */
    private fun columnWidthPx(context: Context, fig: Block.Figure): Int {
        val screen = context.resources.displayMetrics.widthPixels
        return (screen * fig.widthRatio).toInt().coerceAtLeast(dp(context, 160f))
    }

    /** 图片列：图竖着摞，每张按原图尺寸排（`size: full` 时铺满这一栏） */
    private fun imageColumn(
        context: Context,
        fig: Block.Figure,
        night: Boolean,
        reqWidthPx: Int? = null
    ): LinearLayout {
        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        fig.images.forEach { img ->
            col.addView(
                buildImage(
                    context, img.src, img.alt, night,
                    maxHeightDp = FIGURE_MAX_HEIGHT_DP,
                    reqWidthPx = reqWidthPx,
                    compact = true,
                    fillWidth = fig.size == ImageSize.FULL || img.size == ImageSize.FULL
                )
            )
        }
        return col
    }

    /**
     * 多图并排：每行最多 [ROW_MAX_PER_LINE] 张，格子均分宽度，图按格宽等比缩。
     * 超过 3 张自动折行——一排四五张就成了缩略图墙，谁也看不清。
     */
    private fun imageRow(context: Context, fig: Block.Figure, night: Boolean): View {
        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val perLine = ROW_MAX_PER_LINE.coerceAtLeast(1)
        val cellWidth = (context.resources.displayMetrics.widthPixels / perLine)
            .coerceAtLeast(dp(context, 96f))
        fig.images.chunked(perLine).forEach { chunk ->
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            chunk.forEach { img ->
                val cell = buildImage(
                    context, img.src, img.alt, night,
                    maxHeightDp = ROW_MAX_HEIGHT_DP,
                    reqWidthPx = cellWidth,
                    compact = true,
                    fillWidth = fig.size == ImageSize.FULL || img.size == ImageSize.FULL
                )
                val gap = dp(context, 3f)
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = gap
                        marginEnd = gap
                    }
                )
            }
            col.addView(row)
        }
        return col
    }

    /**
     * 按原图比例自适应：宽 = min(可用宽度, 原图宽 × [UPSCALE_CAP])，高 = 宽 × 比例；
     * 高超过 [maxHeight] 时按比例回缩宽度，保证图始终是原图比例、不留大片空白。
     * [fillWidth] 为真时不看原图宽度、直接铺满可用宽度（`size: full`）。
     */
    private class AdaptiveImageView(
        context: Context,
        private val maxHeight: Int,
        private val fillWidth: Boolean = false
    ) : AppCompatImageView(context) {

        var ratio: Float = 0f
        /** 允许的最大显示宽度（像素）= 原图宽 × 放大上限 */
        var naturalWidthPx: Int = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val avail = MeasureSpec.getSize(widthMeasureSpec)
            if (ratio <= 0f || avail <= 0 || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val cap = when {
                fillWidth -> avail
                naturalWidthPx > 0 -> minOf(avail, naturalWidthPx)
                else -> avail
            }
            var w = cap
            var h = (w * ratio).toInt()
            if (h > maxHeight) {
                h = maxHeight
                w = (h / ratio).toInt()
            }
            setMeasuredDimension(w.coerceAtLeast(1), h.coerceAtLeast(1))
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
