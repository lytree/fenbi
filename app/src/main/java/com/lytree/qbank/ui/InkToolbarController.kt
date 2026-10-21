package com.lytree.qbank.ui

import android.view.View
import android.widget.TextView
import com.lytree.qbank.R

/**
 * 手写工具条控制器（答题页 / 资料分析分屏页共用）。
 *
 * 悬浮工具条上提供四个操作：
 * - **撤销**：去掉最后一笔笔迹
 * - **清空**：清掉当前题的全部笔迹
 * - **手指绘制**：默认只响应触控笔，开启后手指也能写（用于没有触控笔的设备）
 * - **完成**：关闭手写模式，把手写层隐藏
 *
 * 笔迹本身由 [InkOverlay] 负责绘制与持久化，这里只管 UI 状态。
 *
 * @param styleInkButton 手写模式开关时对主按钮的样式回调。答题页顶部是 ImageView
 *        （用 tint 变色），分屏页底部是 TextView（用背景变色），由各页自己实现。
 */
class InkToolbarController(
    private val inkOverlay: InkOverlay,
    private val inkBar: View,
    private val btnInkFinger: TextView,
    private val btnInkDone: TextView,
    private val styleInkButton: (Boolean) -> Unit
) {

    var onModeChanged: ((Boolean) -> Unit)? = null

    private var inkMode = false

    /** 当前是否处于手写模式 */
    val isInkMode: Boolean get() = inkMode

    init {
        btnInkFinger.setOnClickListener { toggleFingerDrawing() }
        btnInkDone.setOnClickListener { setInkMode(false) }
        syncFingerButton()
    }

    /** 切换手写模式 */
    fun toggle() = setInkMode(!inkMode)

    fun setInkMode(on: Boolean) {
        inkMode = on
        inkOverlay.visibility = if (on) View.VISIBLE else View.GONE
        inkBar.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) inkOverlay.allowFingerDrawing = false
        styleInkButton(on)
        syncFingerButton()
        onModeChanged?.invoke(on)
    }

    private fun toggleFingerDrawing() {
        inkOverlay.allowFingerDrawing = !inkOverlay.allowFingerDrawing
        syncFingerButton()
    }

    private fun syncFingerButton() {
        val on = inkOverlay.allowFingerDrawing
        btnInkFinger.text = if (on) "手指绘制：开" else "手指绘制：关"
        btnInkFinger.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                btnInkFinger.context,
                if (on) R.color.fb_primary else R.color.fb_text_primary
            )
        )
    }
}
