package com.lytree.qbank.ui.recite

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.lytree.qbank.ui.MaterialRenderer

/**
 * 背诵正文的富文本容器：和材料一样按块排——段落 / Markdown 表格 / 图片行，
 * 段落里的行内 Markdown 与挖空都交给 [ReciteCloze]。
 *
 * 绝大多数条目只有一个段落，此时它就是一张普通的 TextView；只有正文里真的写了表格或图片，
 * 才会多出几块。这样大纲页两千多行也不会为「可能出现的表格」付额外代价。
 *
 * 字号 / 行距 / 字色由调用方按页面（常识卡、聚焦背诵、大纲行）传进来，和原来直接设
 * TextView 的口径一致，换字号时重新 [bind] 即可。
 */
class ReciteRichView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    init {
        orientation = VERTICAL
    }

    /**
     * 渲染一条内容。
     *
     * [card] 为真按卡片的 `____` + [answers] 填答案，否则按导图行的 [cloze] 区间遮；
     * [text] 可以自带序号的「12. 」前缀（常识卡片流就这么用）。
     */
    fun bind(
        text: String,
        answers: List<String>,
        cloze: List<IntRange>,
        reveal: Boolean,
        card: Boolean,
        fontSp: Float,
        lineSpacingDp: Float,
        @ColorRes colorRes: Int,
        bold: Boolean = false,
    ) {
        removeAllViews()
        if (text.isBlank()) return

        val blocks = MaterialRenderer.parse(text)
        var cursor = 0
        blocks.forEachIndexed { index, block ->
            val view: View = when (block) {
                is MaterialRenderer.Block.Paragraph -> {
                    val start = text.indexOf(block.text, cursor)
                    val before = if (start >= 0) countBlanks(text, start) else 0
                    if (start >= 0) cursor = start + block.text.length
                    paragraph(
                        text = block.text,
                        answers = if (card) answers.drop(before) else emptyList(),
                        cloze = if (card) emptyList() else localCloze(cloze, start, block.text.length),
                        reveal = reveal,
                        card = card,
                        fontSp = fontSp,
                        lineSpacingDp = lineSpacingDp,
                        colorRes = colorRes,
                        bold = bold,
                    )
                }
                is MaterialRenderer.Block.Table ->
                    MaterialRenderer.buildTable(context, block, night = false, scale = 1f)
                is MaterialRenderer.Block.Image ->
                    MaterialRenderer.buildImage(
                        context = context,
                        src = block.image.src,
                        alt = block.image.alt,
                        night = false,
                        fillWidth = block.image.size == MaterialRenderer.ImageSize.FULL,
                    )
            }
            addBlock(view, first = index == 0)
        }
    }

    private fun paragraph(
        text: String,
        answers: List<String>,
        cloze: List<IntRange>,
        reveal: Boolean,
        card: Boolean,
        fontSp: Float,
        lineSpacingDp: Float,
        @ColorRes colorRes: Int,
        bold: Boolean,
    ): TextView {
        val tv = TextView(context)
        tv.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
        tv.setLineSpacing(dp(lineSpacingDp).toFloat(), 1f)
        tv.setTextColor(ContextCompat.getColor(context, colorRes))
        if (bold) tv.setTypeface(null, Typeface.BOLD)
        tv.text = if (card) {
            ReciteCloze.card(context, text, answers, reveal)
        } else {
            ReciteCloze.line(context, text, cloze, reveal)
        }
        return tv
    }

    private fun addBlock(view: View, first: Boolean) {
        val lp = (view.layoutParams as? LayoutParams)
            ?: LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        if (!first) lp.topMargin = dp(6f)
        addView(view, lp)
    }

    /** 段落之前的 `____` 个数：把答案按段切给各自的段落，序号不会从第二段重新数 */
    private fun countBlanks(text: String, end: Int): Int {
        var count = 0
        var from = 0
        while (true) {
            val idx = text.indexOf("____", from)
            if (idx < 0 || idx >= end) break
            count++
            from = idx + 4
        }
        return count
    }

    /** 全局挖空区间换算成段落内下标；跨段或越界的直接丢掉（坏数据只影响那一段） */
    private fun localCloze(spans: List<IntRange>, start: Int, length: Int): List<IntRange> {
        if (start < 0 || spans.isEmpty()) return emptyList()
        val end = start + length
        return spans.mapNotNull { span ->
            if (span.first >= start && span.last < end && span.last >= span.first) {
                (span.first - start)..(span.last - start)
            } else {
                null
            }
        }
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
