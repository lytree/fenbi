package com.lytree.qbank.ui.recite

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.core.content.ContextCompat
import com.lytree.qbank.R

/**
 * 挖空段落的渲染：大纲页和聚焦背诵页共用这一份，免得两处各写一套、日后走形。
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
    ): CharSequence {
        val accent = ContextCompat.getColor(context, R.color.fb_primary)
        val soft = ContextCompat.getColor(context, R.color.fb_primary_soft)
        val hint = ContextCompat.getColor(context, R.color.fb_text_hint)

        val sb = SpannableStringBuilder()
        val parts = text.split("____")
        for (i in parts.indices) {
            sb.append(parts[i])
            if (i >= answers.size) continue
            val start = sb.length
            if (reveal) {
                sb.append(answers[i])
                sb.setSpan(
                    ForegroundColorSpan(accent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    BackgroundColorSpan(soft), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else {
                sb.append(CARD_MASK)
                sb.setSpan(
                    ForegroundColorSpan(hint), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

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
        if (reveal || spans.isEmpty() || text.isEmpty()) return text

        val hint = ContextCompat.getColor(context, R.color.fb_text_hint)
        val sb = SpannableStringBuilder()
        var cursor = 0
        for (span in spans) {
            if (span.first < cursor || span.last >= text.length) continue
            sb.append(text, cursor, span.first)
            val start = sb.length
            sb.append(MASK.repeat((span.last - span.first + 1).coerceIn(4, 20)))
            sb.setSpan(
                ForegroundColorSpan(hint), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            cursor = span.last + 1
        }
        sb.append(text, cursor, text.length)
        return sb
    }

    /** 卡片没给答案时的占位 */
    private const val CARD_MASK = "______"

    /** 导图行遮罩按原文长度铺，用单字符再 repeat */
    private const val MASK = "_"
}
