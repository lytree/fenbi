package com.lytree.qbank.ui.recite

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import com.lytree.qbank.ui.InlineMarkdown

/**
 * 挖空段落的渲染：大纲页、聚焦背诵页和常识卡片流共用这一份。
 *
 * 正文先按行内 Markdown 处理（`**粗体**`、`` `代码` ``，与材料 / 网页端同一套写法），
 * 再把挖空位置换成答案或下划线——两件事在同一个 run 列表里完成，交叉写法也不会错位。
 *
 * 两种内容形态：
 * * **卡片**：正文里的 `____` 按 [answers] 顺序替换。未揭示是灰色下划线，揭示后是主色加粗 + 淡底；
 * * **导图行**：用资产里预先算好的 [spans] 遮（[com.lytree.qbank.data.model.LibraryItem.cloze]）。
 *   区间由 `tools/build_library.cs` 对在引号术语 / 数字 / 顿号并列 / 分句边界上，**不会切在词中间**；
 *   [spans] 为空表示这条是单句短语，遮住就一点线索都没有，直接整条显示。
 *
 * 这里**不做**任何自动挖空判断：规则算错一次就会挖在词中间，比不遮更糟，所以一律交给构建期。
 */
object ReciteCloze {

    /** 卡片正文 */
    fun card(
        context: Context,
        text: String,
        answers: List<String>,
        reveal: Boolean,
        prefix: CharSequence = "",
    ): CharSequence =
        build(context, prefix.toString() + text, answers, lineMode = false, reveal = reveal)

    /**
     * 导图行：按 [spans]（含头含尾）遮起来。
     *
     * 遮罩长度跟着原文走（限 4~20 个下划线），点亮揭示时行高不会跳；越界的区间直接跳过，
     * 坏数据只该让这一段不遮，不该整条不显示。
     */
    fun line(
        context: Context,
        text: String,
        spans: List<IntRange>,
        reveal: Boolean,
    ): CharSequence {
        // 揭示状态下没有任何遮罩，正文照常走行内 Markdown，别给答案加高亮
        if (reveal || spans.isEmpty() || text.isEmpty()) return InlineMarkdown.render(context, text)
        return build(context, text, emptyList(), true, false, spans)
    }

    private fun build(
        context: Context,
        text: String,
        answers: List<String>,
        lineMode: Boolean,
        reveal: Boolean,
        spans: List<IntRange> = emptyList(),
    ): CharSequence {
        val composed = StringBuilder()
        val blanks = ArrayList<String>()

        if (!lineMode) {
            // 卡片：____ 换成占位符，答案按出现顺序排好
            var cursor = 0
            var answerIndex = 0
            while (true) {
                val idx = text.indexOf(CARD_BLANK, cursor)
                if (idx < 0) {
                    composed.append(text, cursor, text.length)
                    break
                }
                composed.append(text, cursor, idx)
                val answer = answers.getOrNull(answerIndex)
                if (answer == null) {
                    // 答案没配齐：保留原文的下划线，别把一个空直接吃掉
                    composed.append(CARD_BLANK)
                } else {
                    blanks += if (reveal) answer else CARD_MASK
                    composed.append(InlineMarkdown.TOKEN)
                }
                answerIndex++
                cursor = idx + CARD_BLANK.length
            }
        } else {
            // 导图行：按预算好的区间遮，区间越界就跳过这一段
            var cursor = 0
            for (span in spans) {
                if (span.first < cursor || span.last >= text.length || span.last < span.first) continue
                composed.append(text, cursor, span.first)
                blanks += MASK.repeat((span.last - span.first + 1).coerceIn(4, 20))
                composed.append(InlineMarkdown.TOKEN)
                cursor = span.last + 1
            }
            composed.append(text, cursor, text.length)
        }

        val accent = ContextCompat.getColor(context, R.color.fb_primary)
        val soft = ContextCompat.getColor(context, R.color.fb_primary_soft)
        val hint = ContextCompat.getColor(context, R.color.fb_text_hint)
        val codeBg = ContextCompat.getColor(context, R.color.fb_primary_soft)

        val sb = SpannableStringBuilder()
        var blankIndex = 0
        for (run in InlineMarkdown.runs(composed)) {
            if (run.token) {
                val start = sb.length
                sb.append(blanks.getOrElse(blankIndex) { CARD_MASK })
                blankIndex++
                if (reveal) {
                    sb.setSpan(
                        ForegroundColorSpan(accent),
                        start,
                        sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    sb.setSpan(
                        BackgroundColorSpan(soft),
                        start,
                        sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    sb.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else {
                    sb.setSpan(
                        ForegroundColorSpan(hint),
                        start,
                        sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                sb.setSpan(
                    UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                continue
            }

            val start = sb.length
            sb.append(run.text)
            if (sb.length == start) continue
            if (run.bold) {
                sb.setSpan(
                    StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (run.code) {
                sb.setSpan(
                    TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    BackgroundColorSpan(codeBg),
                    start,
                    sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return sb
    }

    /** 卡片里填空位的原文写法 */
    private const val CARD_BLANK = "____"

    /** 卡片没给答案时的占位 */
    private const val CARD_MASK = "______"

    /** 导图行遮罩按原文长度铺，用单字符再 repeat */
    private const val MASK = "_"
}
