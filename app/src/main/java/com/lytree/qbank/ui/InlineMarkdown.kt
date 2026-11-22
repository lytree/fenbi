package com.lytree.qbank.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.core.content.ContextCompat
import com.lytree.qbank.R

/**
 * 行内 Markdown：`**粗体**` 与 `` `代码` ``，与网页端 `renderInline` 认同一套写法。
 *
 * 只认成对出现的标记，落单的 `**` / `` ` `` 原样保留——正文里的乘号、引号不会被当成格式吃掉。
 * 不支持嵌套：粗体里的代码按粗体渲染（和网页端「先粗体再代码」的结果一致）。
 *
 * [TOKEN] 是留给背诵挖空的占位字符：[runs] 会把它单独切一块出来，
 * 调用方在那一段回填答案或下划线，其余文字照常走行内格式。
 */
object InlineMarkdown {

    /** 挖空占位：正文里的 `____` / 挖空区间先换成它，解析完再回填 */
    const val TOKEN: Char = '\uE000'

    /** 一段文字：是否粗体 / 行内代码 / 挖空占位（token 段的 [text] 为空） */
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val code: Boolean = false,
        val token: Boolean = false,
    )

    /** 行内渲染：没有格式标记时直接返回原文，免得无谓地建一份 Spannable */
    fun render(context: Context, text: CharSequence): CharSequence {
        val runs = runs(text)
        if (runs.none { it.bold || it.code }) return text
        val sb = SpannableStringBuilder()
        appendRuns(context, sb, runs)
        return sb
    }

    /**
     * 把已经切好的段落按 run 逐个写进 [sb]。段落视图自己做挖空时也能复用这一段，
     * 保证粗体 / 代码的样式和材料段落完全一致。
     */
    fun appendRuns(context: Context, sb: SpannableStringBuilder, runs: List<Run>) {
        val codeBg = ContextCompat.getColor(context, R.color.fb_primary_soft)
        for (run in runs) {
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
                    BackgroundColorSpan(codeBg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    /** 切成 run：普通文字、粗体段、代码段、挖空占位段，顺序与原文一致 */
    fun runs(text: CharSequence): List<Run> = scan(text, bold = false, code = false)

    private fun scan(text: CharSequence, bold: Boolean, code: Boolean): List<Run> {
        val out = ArrayList<Run>()
        val buf = StringBuilder()

        fun flush() {
            if (buf.isEmpty()) return
            out += Run(buf.toString(), bold, code)
            buf.setLength(0)
        }

        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == TOKEN) {
                flush()
                out += Run("", bold, code, token = true)
                i++
                continue
            }
            // **粗体**：从一个 ** 找到下一个 **，中间必须非空
            if (!code && c == '*' && i + 1 < n && text[i + 1] == '*') {
                val end = indexOfDoubleStar(text, i + 2)
                if (end > i + 2) {
                    flush()
                    out += scan(text.subSequence(i + 2, end), bold = true, code = code)
                    i = end + 2
                    continue
                }
            }
            // `代码`：找到下一个反引号，中间必须非空
            if (!code && c == '`') {
                val end = indexOfChar(text, '`', i + 1)
                if (end > i + 1) {
                    flush()
                    out += scan(text.subSequence(i + 1, end), bold = bold, code = true)
                    i = end + 1
                    continue
                }
            }
            buf.append(c)
            i++
        }
        flush()
        return out
    }

    private fun indexOfDoubleStar(text: CharSequence, from: Int): Int {
        var i = from
        while (i + 1 < text.length) {
            if (text[i] == '*' && text[i + 1] == '*') return i
            i++
        }
        return -1
    }

    private fun indexOfChar(text: CharSequence, target: Char, from: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == target) return i
            i++
        }
        return -1
    }
}
