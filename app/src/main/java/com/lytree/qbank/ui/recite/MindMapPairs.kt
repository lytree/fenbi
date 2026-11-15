package com.lytree.qbank.ui.recite

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import com.lytree.qbank.data.model.MindMapPair

/**
 * 书页下半部「内涵连线」的配对自测。
 *
 * 导图本身不挖空（整句原文，通读用），连线题单独做成小测：
 * 左列是提法（保持原书顺序），右列是内涵（每次打开打乱）。
 * 先点左再点右，配对对了两边变绿并锁定，错了闪一下红再重选。
 *
 * 从 [MindMapFragment] 抽出来的：现在导图改成画布渲染，配对入口挪到了导图查看页，
 * 两边都要用同一套交互，就放这里。
 */
internal object MindMapPairs {

    fun show(context: Context, pairs: List<MindMapPair>) {
        if (pairs.isEmpty()) return
        val total = pairs.size
        val matched = BooleanArray(total)
        var selectedLeft = -1
        var matchedCount = 0

        val bgNormal = ContextCompat.getColor(context, R.color.fb_page_bg)
        val bgSelected = ContextCompat.getColor(context, R.color.fb_primary_soft)
        val bgRight = ContextCompat.getColor(context, R.color.fb_success_bg)
        val bgWrong = ContextCompat.getColor(context, R.color.fb_danger_bg)
        val textNormal = ContextCompat.getColor(context, R.color.fb_text_primary)
        val textSelected = ContextCompat.getColor(context, R.color.fb_primary)
        val textRight = ContextCompat.getColor(context, R.color.fb_success)
        val textWrong = ContextCompat.getColor(context, R.color.fb_danger)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 4))
        }
        val progress = TextView(context).apply {
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.fb_text_body_sm)
            )
            setTextColor(ContextCompat.getColor(context, R.color.fb_text_secondary))
            includeFontPadding = false
            text = context.getString(R.string.mindmap_pair_progress, 0, total)
        }
        root.addView(progress)
        root.addView(
            TextView(context).apply {
                text = context.getString(R.string.mindmap_pair_hint)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.fb_text_tag))
                setTextColor(ContextCompat.getColor(context, R.color.fb_text_meta))
                includeFontPadding = false
                setPadding(0, dp(context, 4), 0, dp(context, 10))
            }
        )

        val columns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val leftColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rightColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        columns.addView(
            leftColumn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(context, 8) }
        )
        columns.addView(
            rightColumn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(context, 8) }
        )

        leftColumn.addView(columnTitle(context, context.getString(R.string.mindmap_pair_left)))
        rightColumn.addView(columnTitle(context, context.getString(R.string.mindmap_pair_right)))

        val leftViews = ArrayList<TextView>(total)
        val rightOrder = pairs.indices.shuffled()
        val rightViews = ArrayList<TextView>(total)
        // 右列第 pos 个位置放的是原书第 rightOriginal[pos] 组的内涵
        val rightOriginal = ArrayList<Int>(total)
        var dialogRef: AlertDialog? = null

        fun paint(view: TextView, bg: Int, textColor: Int) {
            view.background = rounded(view.context, bg, 6)
            view.setTextColor(textColor)
        }

        fun clearPending() {
            for (i in 0 until total) {
                if (!matched[i]) {
                    paint(leftViews[i], bgNormal, textNormal)
                    leftViews[i].text = pairs[i].left
                }
                if (!matched[rightOriginal[i]]) {
                    paint(rightViews[i], bgNormal, textNormal)
                    rightViews[i].text = pairs[rightOriginal[i]].right
                }
            }
            selectedLeft = -1
        }

        for (i in 0 until total) {
            val view = pairItem(context, pairs[i].left)
            leftViews.add(view)
            view.setOnClickListener {
                if (matched[i]) return@setOnClickListener
                clearPending()
                selectedLeft = i
                paint(view, bgSelected, textSelected)
            }
            leftColumn.addView(view)
        }
        for (position in rightOrder.indices) {
            val original = rightOrder[position]
            val view = pairItem(context, pairs[original].right)
            rightViews.add(view)
            rightOriginal.add(original)
            view.setOnClickListener {
                if (matched[original] || selectedLeft < 0) return@setOnClickListener
                val leftIndex = selectedLeft
                if (leftIndex == original) {
                    matched[original] = true
                    matchedCount++
                    paint(leftViews[leftIndex], bgRight, textRight)
                    paint(view, bgRight, textRight)
                    // 对错不只靠颜色：✓ / ✗ 图标 + 震动，色弱和盲操都能感知
                    leftViews[leftIndex].text = "✓ " + pairs[leftIndex].left
                    view.text = "✓ " + pairs[original].right
                    leftViews[leftIndex].isClickable = false
                    view.isClickable = false
                    selectedLeft = -1
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    progress.text = if (matchedCount == total) {
                        context.getString(R.string.mindmap_pair_done, total)
                    } else {
                        context.getString(R.string.mindmap_pair_progress, matchedCount, total)
                    }
                    if (matchedCount == total) {
                        // 全配完了，正按钮从「关闭」变成「再开一局」，不用退出去重进
                        dialogRef?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                            setText(R.string.mindmap_pair_again)
                            setOnClickListener {
                                dialogRef?.dismiss()
                                show(context, pairs)
                            }
                        }
                    }
                } else {
                    paint(leftViews[leftIndex], bgWrong, textWrong)
                    paint(view, bgWrong, textWrong)
                    leftViews[leftIndex].text = "✗ " + pairs[leftIndex].left
                    view.text = "✗ " + pairs[original].right
                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    selectedLeft = -1
                    view.postDelayed({ clearPending() }, 650)
                }
            }
            rightColumn.addView(view)
        }

        root.addView(columns)

        val scroll = ScrollView(context).apply {
            addView(root)
            isVerticalScrollBarEnabled = true
        }
        dialogRef = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.mindmap_pair_section))
            .setView(scroll)
            .setPositiveButton(R.string.mindmap_pair_close, null)
            .create()
            .also { it.show() }
    }

    private fun columnTitle(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.fb_text_tag))
            setTextColor(ContextCompat.getColor(context, R.color.fb_text_meta))
            includeFontPadding = false
            setPadding(dp(context, 8), 0, dp(context, 8), dp(context, 6))
        }

    private fun pairItem(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.fb_text_body_sm)
            )
            setTextColor(ContextCompat.getColor(context, R.color.fb_text_primary))
            setLineSpacing(dp(context, 3).toFloat(), 1f)
            includeFontPadding = false
            setPadding(dp(context, 8), dp(context, 7), dp(context, 8), dp(context, 7))
            background = rounded(context, ContextCompat.getColor(context, R.color.fb_page_bg), 6)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 6); gravity = Gravity.START }
        }

    /** dp 换算：这里只有静态工具，密度从传入的 context 现取 */
    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(context: Context, color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(color)
        }
}
