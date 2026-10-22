package com.lytree.qbank.ui

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lytree.qbank.R

/**
 * 遮盖层工具条控制器（答题页 / 资料分析分屏页共用）。
 *
 * 悬浮工具条提供五个操作：
 * - **撤销**：去掉最后一笔笔迹
 * - **清空**：清掉当前题的全部笔迹
 * - **遮盖**：切换遮盖纸档位（关 / 浅 / 中），写字时题目淡一层，一眼能看出在写画模式
 * - **标记**：给当前题打标记（和题目卡上的标记同一份数据）
 * - **完成**：退出写画模式——笔迹留在原处，但不再拦截触摸，可以继续选答案、翻页
 *
 * 「手指绘制」开关已经删掉：遮盖层本身就是全拦截的，手指和触控笔都能直接写。
 *
 * 笔迹与遮盖档位由 [InkOverlay] 负责，这里只管 UI 状态。
 *
 * @param styleInkButton 写画模式开关时对主按钮的样式回调。答题页是开关控件，
 *       分屏页底部是 TextView（用背景变色），由各页自己实现。
 * @param onToggleMark 点「标记」时的回调，由页面处理标记落库与刷新。
 */
class InkToolbarController(
    private val inkOverlay: InkOverlay,
    private val inkBar: View,
    private val btnInkShade: TextView,
    private val btnInkMark: TextView,
    private val btnInkDone: TextView,
    private val styleInkButton: (Boolean) -> Unit,
    private val onToggleMark: (() -> Unit)? = null
) {

    var onModeChanged: ((Boolean) -> Unit)? = null

    private var inkMode = false
    private var marked = false
    private var night = false

    /** 当前是否处于写画模式 */
    val isInkMode: Boolean get() = inkMode

    init {
        btnInkShade.setOnClickListener { inkOverlay.cycleShade() }
        btnInkMark.setOnClickListener { onToggleMark?.invoke() }
        btnInkDone.setOnClickListener { setInkMode(false) }
        inkOverlay.onShadeChanged = { renderShade() }
        renderShade()
        renderMark()
    }

    /** 切换写画模式 */
    fun toggle() = setInkMode(!inkMode)

    fun setInkMode(on: Boolean) {
        inkMode = on
        inkOverlay.writeMode = on
        inkBar.visibility = if (on) View.VISIBLE else View.GONE
        styleInkButton(on)
        renderShade()
        renderMark()
        onModeChanged?.invoke(on)
    }

    /** 页面侧同步标记状态（切题 / 点击标记后调用） */
    fun setMarked(value: Boolean) {
        if (marked == value) return
        marked = value
        renderMark()
    }

    /** 夜间模式：工具条按钮文字跟着换色 */
    fun applyNightMode(on: Boolean) {
        night = on
        renderShade()
        renderMark()
        btnInkDone.setTextColor(ContextCompat.getColor(inkBar.context, R.color.fb_primary))
    }

    private fun renderShade() {
        btnInkShade.text = "遮盖·${inkOverlay.shade.label}"
        val none = inkOverlay.shade == InkOverlay.Shade.NONE
        btnInkShade.setTextColor(
            ContextCompat.getColor(
                btnInkShade.context,
                when {
                    !none -> R.color.fb_primary
                    night -> R.color.fb_night_text_meta
                    else -> R.color.fb_text_meta
                }
            )
        )
    }

    private fun renderMark() {
        btnInkMark.text = if (marked) "已标记" else "标记"
        btnInkMark.setTextColor(
            ContextCompat.getColor(
                btnInkMark.context,
                when {
                    marked -> R.color.fb_card_num_marked
                    night -> R.color.fb_night_text
                    else -> R.color.fb_text_primary
                }
            )
        )
    }
}
