package com.lytree.qbank.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 图表数据。
 *
 * 资料分析的材料里经常带折线图 / 柱状图，但题库是纯文本导入的，
 * 所以约定用一段 `:::chart` 代码块描述图表，由 [MaterialRenderer] 解析成 [ChartSpec]，
 * 再由 [ChartView] 画出来——不引第三方图表库，也不依赖图片。
 *
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
 * 缺失值写 `-` 或留空，折线会在此断开。
 */
data class ChartSpec(
    val title: String = "",
    val type: Type = Type.LINE,
    val xLabels: List<String> = emptyList(),
    val series: List<Series> = emptyList(),
    val unit: String = "",
    val showValues: Boolean = true,
    val minY: Double? = null,
    val maxY: Double? = null
) {
    enum class Type { LINE, BAR }

    /** [values] 用 [Double.NaN] 表示该点缺数据 */
    data class Series(val name: String, val values: List<Double>)
}

/**
 * 自绘折线 / 柱状图。
 *
 * 版式对齐参考应用的资料分析图：标题居中、淡横向网格、Y 轴刻度在左、
 * X 轴标签拥挤时旋转 45°、每个数据点上方标数值、底部图例。
 * 全部用 Canvas 画，夜间的网格与文字换深色配色，尺寸仍按 dp 走，切换不位移。
 */
class ChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var spec: ChartSpec? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** 夜间模式：只换颜色，不动排版 */
    var night: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** 绘图区高度倍数：材料弹窗里空间大，把图拉高一点更好读 */
    var heightScale: Float = 1f
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private fun dp(v: Float): Float = v * density

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        gridPaint.style = Paint.Style.STROKE
        gridPaint.strokeWidth = dp(0.7f)
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = dp(1.5f)
        linePaint.strokeJoin = Paint.Join.ROUND
        linePaint.strokeCap = Paint.Cap.ROUND
        barPaint.style = Paint.Style.FILL
    }

    // ---------------- 测量 ----------------

    /** 高度按内容固定：标题带 + 绘图区 + X 标签带 + 图例带 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measured = MeasureSpec.getSize(widthMeasureSpec)
        val w = if (measured > 0) measured else (320 * density).toInt()
        val s = spec
        val titleH = if (s != null && s.title.isNotBlank()) dp(24f) else dp(8f)
        val band = if (s != null && rotateLabels(s)) dp(52f) else dp(18f)
        val h = titleH + dp(126f) * heightScale + band + dp(22f) + dp(6f)
        setMeasuredDimension(w, h.toInt())
    }

    /** X 标签多或长时旋转 45°，否则横排 */
    private fun rotateLabels(s: ChartSpec): Boolean {
        val longest = s.xLabels.maxOfOrNull { it.length } ?: 0
        return s.xLabels.size > 5 || longest > 5
    }

    // ---------------- 绘制 ----------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = spec
        if (s == null || s.xLabels.isEmpty() || s.series.isEmpty()) {
            drawEmpty(canvas)
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val rotate = rotateLabels(s)
        val titleH = if (s.title.isNotBlank()) dp(24f) else dp(8f)
        val legendH = dp(22f)
        val xBand = if (rotate) dp(52f) else dp(18f)
        val padLeft = dp(34f)
        val padRight = dp(10f)

        val plotTop = titleH
        val plotBottom = h - legendH - xBand
        val plotLeft = padLeft
        val plotRight = w - padRight
        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

        val textColor = color(if (night) R.color.fb_night_text_meta else R.color.fb_text_meta)
        val gridColor = color(if (night) R.color.fb_night_table_line else R.color.fb_divider)
        val axisColor = color(if (night) R.color.fb_night_divider else R.color.fb_table_line)

        drawTitle(canvas, s, w, textColor)

        // ---- Y 轴刻度 ----
        val all = s.series.flatMap { it.values }.filter { !it.isNaN() }
        val dataMax = all.maxOrNull() ?: 1.0
        val dataMin = all.minOrNull() ?: 0.0
        val minY = s.minY ?: minOf(0.0, dataMin)
        val maxY = s.maxY ?: dataMax
        val (step, topY) = niceScale(minY, maxY)
        val span = (topY - minY).let { if (it <= 0.0) 1.0 else it }
        val tickCount = ((topY - minY) / step).roundToInt().coerceIn(1, 10)
        val decimals = if (step >= 1.0 && topY > 10.0) 0 else 1

        labelPaint.textSize = dp(9f)
        labelPaint.color = textColor
        labelPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..tickCount) {
            val v = minY + step * i
            val y = plotBottom - (plotH * ((v - minY) / span)).toFloat()
            gridPaint.color = if (i == 0) axisColor else gridColor
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            canvas.drawText(
                String.format(Locale.CHINA, "%.${decimals}f", v),
                plotLeft - dp(5f), y + dp(3.2f), labelPaint
            )
        }
        // 左侧纵轴
        gridPaint.color = axisColor
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, gridPaint)

        val n = s.xLabels.size
        fun xAt(i: Int): Float = if (s.type == ChartSpec.Type.LINE) {
            if (n == 1) plotLeft + plotW / 2f else plotLeft + plotW * i / (n - 1f)
        } else {
            plotLeft + plotW * (i + 0.5f) / n
        }

        fun yOf(v: Double): Float =
            plotBottom - (plotH * ((v - minY) / span)).toFloat()

        // ---- 数据 ----
        val colors = palette()
        // 柱状图按「一簇 N 根」排布；折线图每点独占一个 x
        val band = plotW / n
        val groupW = band * 0.62f
        val barW = groupW / s.series.size.coerceAtLeast(1)

        // 数值标签的位置：折线标在点上，柱状标在「这根柱子」的正上方（不是整簇中心）
        fun valueX(i: Int, si: Int): Float = if (s.type == ChartSpec.Type.LINE) {
            xAt(i)
        } else {
            xAt(i) - groupW / 2f + barW * si + barW / 2f
        }

        s.series.forEachIndexed { si, ser ->
            val c = colors[si % colors.size]
            if (s.type == ChartSpec.Type.LINE) {
                drawLineSeries(canvas, ser, c, n, ::xAt, ::yOf)
            } else {
                drawBarSeries(
                    canvas, ser, c, si, n, groupW, barW, plotBottom, ::xAt, ::yOf
                )
            }

            if (s.showValues) {
                valuePaint.textSize = dp(8.5f)
                valuePaint.color = c
                valuePaint.textAlign = Paint.Align.CENTER
                // 一条线里有小数就整条线都留一位小数，别出现「7」「3」和「8.9」混排
                val decimals =
                    if (ser.values.any { !it.isNaN() && abs(it - it.roundToInt()) > 1e-9 }) 1 else 0
                ser.values.forEachIndexed { i, v ->
                    if (i >= n || v.isNaN()) return@forEachIndexed
                    var y = yOf(v) - dp(5f)
                    // 顶到标题下方时翻到点的下面，避免和图例 / 标题打架
                    if (y < plotTop + dp(7f)) y = yOf(v) + dp(10f)
                    canvas.drawText(fmtValue(v, decimals), valueX(i, si), y, valuePaint)
                }
            }
        }

        drawXLabels(canvas, s, rotate, rotateLabelBaseY = plotBottom, xAt = ::xAt, color = textColor)
        drawLegend(canvas, s, colors, w, h, legendH, textColor)
    }

    private fun drawTitle(canvas: Canvas, s: ChartSpec, w: Float, color: Int) {
        if (s.title.isBlank()) return
        titlePaint.textSize = dp(11f)
        titlePaint.color = color
        titlePaint.textAlign = Paint.Align.CENTER
        val t = TextUtils.ellipsize(s.title, titlePaint, w - dp(12f), TextUtils.TruncateAt.END)
        canvas.drawText(t, 0, t.length, w / 2f, dp(16f), titlePaint)
    }

    private fun drawLineSeries(
        canvas: Canvas,
        ser: ChartSpec.Series,
        color: Int,
        n: Int,
        xAt: (Int) -> Float,
        yOf: (Double) -> Float
    ) {
        val path = Path()
        var started = false
        ser.values.forEachIndexed { i, v ->
            if (i >= n || v.isNaN()) {
                started = false
                return@forEachIndexed
            }
            val x = xAt(i)
            val y = yOf(v)
            if (started) path.lineTo(x, y) else path.moveTo(x, y).also { started = true }
        }
        linePaint.color = color
        canvas.drawPath(path, linePaint)

        // 数据点：小圆点，和参考应用一样只用色点不用大标记
        barPaint.color = color
        val r = dp(1.9f)
        ser.values.forEachIndexed { i, v ->
            if (i >= n || v.isNaN()) return@forEachIndexed
            canvas.drawCircle(xAt(i), yOf(v), r, barPaint)
        }
    }

    private fun drawBarSeries(
        canvas: Canvas,
        ser: ChartSpec.Series,
        color: Int,
        seriesIndex: Int,
        n: Int,
        groupW: Float,
        barW: Float,
        plotBottom: Float,
        xAt: (Int) -> Float,
        yOf: (Double) -> Float
    ) {
        barPaint.color = color
        ser.values.forEachIndexed { i, v ->
            if (i >= n || v.isNaN()) return@forEachIndexed
            val cx = xAt(i)
            val left = cx - groupW / 2f + barW * seriesIndex
            val top = yOf(v)
            if (top >= plotBottom) return@forEachIndexed
            canvas.drawRoundRect(
                RectF(left, top, left + barW - dp(1f), plotBottom),
                dp(1.5f), dp(1.5f), barPaint
            )
        }
    }

    private fun drawXLabels(
        canvas: Canvas,
        s: ChartSpec,
        rotate: Boolean,
        rotateLabelBaseY: Float,
        xAt: (Int) -> Float,
        color: Int
    ) {
        labelPaint.textSize = dp(9f)
        labelPaint.color = color
        if (rotate) {
            labelPaint.textAlign = Paint.Align.RIGHT
            val baseY = rotateLabelBaseY + dp(7f)
            s.xLabels.forEachIndexed { i, label ->
                val x = xAt(i)
                canvas.save()
                canvas.rotate(-45f, x, baseY)
                canvas.drawText(label, x, baseY, labelPaint)
                canvas.restore()
            }
        } else {
            labelPaint.textAlign = Paint.Align.CENTER
            s.xLabels.forEachIndexed { i, label ->
                canvas.drawText(label, xAt(i), rotateLabelBaseY + dp(12f), labelPaint)
            }
        }
    }

    private fun drawLegend(
        canvas: Canvas,
        s: ChartSpec,
        colors: IntArray,
        w: Float,
        h: Float,
        legendH: Float,
        textColor: Int
    ) {
        labelPaint.textSize = dp(9.5f)
        labelPaint.color = textColor
        labelPaint.textAlign = Paint.Align.LEFT
        val swatch = dp(14f)
        val gap = dp(5f)
        val itemGap = dp(14f)
        val widths = s.series.map { labelPaint.measureText(it.name) }
        var total = 0f
        widths.forEachIndexed { i, tw ->
            total += swatch + gap + tw + if (i > 0) itemGap else 0f
        }
        var x = ((w - total) / 2f).coerceAtLeast(dp(4f))
        val y = h - legendH / 2f
        s.series.forEachIndexed { i, ser ->
            linePaint.color = colors[i % colors.size]
            canvas.drawLine(x, y, x + swatch, y, linePaint)
            canvas.drawText(ser.name, x + swatch + gap, y + dp(3.2f), labelPaint)
            x += swatch + gap + widths[i] + itemGap
        }
    }

    private fun drawEmpty(canvas: Canvas) {
        labelPaint.textSize = dp(11f)
        labelPaint.color = color(if (night) R.color.fb_night_text_meta else R.color.fb_text_meta)
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("暂无图表数据", width / 2f, height / 2f + dp(4f), labelPaint)
    }

    // ---------------- 数值 / 刻度 ----------------

    /**
     * 把数据范围收成「好看的刻度」：步长取 1 / 2 / 2.5 / 5 × 10^n，
     * 顶部向上取整到步长的整数倍，保证刻度线间距均匀且标签是整数或一位小数。
     */
    private fun niceScale(minV: Double, maxV: Double): Pair<Double, Double> {
        if (maxV - minV < 1e-9) {
            val step = if (abs(maxV) < 1e-9) 1.0 else maxV / 4.0
            return step to (minV + step * 4)
        }
        val rough = (maxV - minV) / 4.0
        val mag = 10.0.pow(floor(log10(rough)))
        val norm = rough / mag
        val step = when {
            norm <= 1.0 -> 1.0
            norm <= 2.0 -> 2.0
            norm <= 2.5 -> 2.5
            norm <= 5.0 -> 5.0
            else -> 10.0
        } * mag
        val top = ceil(maxV / step - 1e-9) * step
        return step to top
    }

    private fun fmtValue(v: Double, decimals: Int): String =
        if (decimals <= 0 && abs(v - v.roundToInt()) < 1e-9) v.roundToInt().toString()
        else String.format(Locale.CHINA, "%.${decimals}f", v)

    private fun color(res: Int): Int = ContextCompat.getColor(context, res)

    /** 日间 / 夜间两套系列色：都在 colors.xml 里（fb_chart_*），夜间整体提亮，深底上不糊 */
    private fun palette(): IntArray = if (night) {
        intArrayOf(
            color(R.color.fb_chart_night_1), color(R.color.fb_chart_night_2),
            color(R.color.fb_chart_night_3), color(R.color.fb_chart_night_4),
            color(R.color.fb_chart_night_5)
        )
    } else {
        intArrayOf(
            color(R.color.fb_chart_1), color(R.color.fb_chart_2), color(R.color.fb_chart_3),
            color(R.color.fb_chart_4), color(R.color.fb_chart_5)
        )
    }
}
