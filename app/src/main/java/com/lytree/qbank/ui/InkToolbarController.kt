package com.lytree.qbank.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.lytree.qbank.R

/**
 * 写画工具条控制器（答题页 / 资料分析分屏页共用）。
 *
 * 形态对齐参考应用：**一条顶在卷面上方的图标栏**，不进文字，选中项用主色。
 * 左起八格：
 * - **退出**（×）：关掉写画模式，笔迹留在卷面上
 * - **笔**：写画；与橡皮互斥高亮
 * - **橡皮**：划过的笔迹整笔擦掉（见 [InkOverlay.Tool.ERASER]），落点画一枚圈
 * - **撤销 / 重做**：撤销一笔、把撤销的那笔放回来；没得撤 / 没得重做时图标变淡
 * - **遮盖**（两层纸）：循环切遮盖档位「关 → 浅 → 中 → 深」，写字时题目淡一层
 * - **笔色**（圆点）：当前笔色就是那枚圆点的颜色，点一下换下一个色，顺手切回笔
 * - **清空**（垃圾桶）：去掉当前题的全部笔迹
 *
 * 笔迹、工具、撤销栈、笔色与遮盖档位都在 [InkOverlay] 里，这里只管 UI 状态与点击。
 *
 * @param styleInkButton 写画开关按钮的样式回调（答题页 / 分屏页各自的入口按钮长的不一样）
 * @param inkColorDot 笔色圆点（工具条里那枚 20dp 的小圆）；不传就退回给格子本身画背景
 */
class InkToolbarController(
    private val inkOverlay: InkOverlay,
    private val inkBar: View,
    private val btnPen: ImageView,
    private val btnEraser: ImageView,
    private val btnUndo: ImageView,
    private val btnRedo: ImageView,
    private val btnShade: ImageView,
    private val btnClear: ImageView,
    private val btnClose: ImageView,
    private val btnColor: View,
    private val styleInkButton: (Boolean) -> Unit,
    private val inkColorDot: View? = null
) {

    var onModeChanged: ((Boolean) -> Unit)? = null

    private var inkMode = false
    private var night = false

    /** 当前是否处于写画模式 */
    val isInkMode: Boolean get() = inkMode

    init {
        btnClose.setOnClickListener { setInkMode(false) }
        btnPen.setOnClickListener { inkOverlay.tool = InkOverlay.Tool.PEN }
        btnEraser.setOnClickListener { inkOverlay.tool = InkOverlay.Tool.ERASER }
        btnUndo.setOnClickListener { inkOverlay.undo() }
        btnRedo.setOnClickListener { inkOverlay.redo() }
        btnClear.setOnClickListener { inkOverlay.clear() }

        btnShade.setOnClickListener {
            inkOverlay.cycleShade()
            toast("遮盖：${inkOverlay.shade.label}")
        }
        // 换色顺手切回笔：点色点的人下一步一定是要写字
        btnColor.setOnClickListener {
            inkOverlay.cycleColor()
            inkOverlay.tool = InkOverlay.Tool.PEN
            toast("笔色：${PenColors.labelOf(inkOverlay.penColor)}")
        }

        inkOverlay.onToolChanged = { renderTool() }
        inkOverlay.onShadeChanged = { renderShade() }
        inkOverlay.onColorChanged = { renderColor() }
        // 抬笔 / 撤销 / 重做 / 擦除都会改笔数，撤销与重做的可用性跟着变
        inkOverlay.onStrokeCountChanged = { renderHistory() }

        render()
    }

    /** 切换写画模式 */
    fun toggle() = setInkMode(!inkMode)

    fun setInkMode(on: Boolean) {
        inkMode = on
        inkOverlay.writeMode = on
        inkBar.visibility = if (on) View.VISIBLE else View.GONE
        // 每次进来都是「笔」，不会上次留着橡皮、这次一写一片空白
        if (on) inkOverlay.tool = InkOverlay.Tool.PEN
        styleInkButton(on)
        render()
        onModeChanged?.invoke(on)
    }

    /** 夜间模式：图标跟着换成深色卷面上看得清的那一档 */
    fun applyNightMode(on: Boolean) {
        night = on
        render()
    }

    private fun render() {
        renderTool()
        renderHistory()
        renderShade()
        renderColor()
    }

    /** 笔 / 橡皮互斥高亮：选中的那枚用主色 */
    private fun renderTool() {
        val pen = inkOverlay.tool == InkOverlay.Tool.PEN
        tint(btnPen, if (pen) R.color.fb_primary else iconColor())
        tint(btnEraser, if (pen) iconColor() else R.color.fb_primary)
    }

    /** 撤销 / 重做：没得操作时变淡，按了也不会有反应 */
    private fun renderHistory() {
        val dim = if (night) R.color.fb_night_text_meta else R.color.fb_text_hint
        val canUndo = inkOverlay.canUndo
        val canRedo = inkOverlay.canRedo
        // 先定可用性再上色：tint 里按 isEnabled 决定透明度
        btnUndo.isEnabled = canUndo
        btnRedo.isEnabled = canRedo
        btnClear.isEnabled = canUndo
        tint(btnUndo, if (canUndo) iconColor() else dim)
        tint(btnRedo, if (canRedo) iconColor() else dim)
        tint(btnClear, if (canUndo) iconColor() else dim)
    }

    /** 遮盖：没铺纸时是灰的，铺了纸用主色 —— 一眼看出题目为什么变淡了 */
    private fun renderShade() {
        val on = inkOverlay.shade != InkOverlay.Shade.NONE
        tint(btnShade, if (on) R.color.fb_primary else iconColor())
        btnShade.contentDescription = "题目遮盖：${inkOverlay.shade.label}"
    }

    /**
     * 色点：实心圆 = 当前笔色，外面一圈细描边。
     * 用背景 drawable 现画，不额外引资源；夜间把描边换成半透明白，深底上也看得见。
     */
    private fun renderColor() {
        val dot = inkColorDot ?: btnColor
        val d = dot.resources.displayMetrics.density
        val ring = if (night) 0x99FFFFFF.toInt() else 0x33000000
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(inkOverlay.penColor)
            setStroke((1.5f * d).toInt().coerceAtLeast(1), ring)
        }
        dot.contentDescription = "笔迹颜色：${PenColors.labelOf(inkOverlay.penColor)}"
    }

    private fun iconColor() = if (night) R.color.fb_night_icon else R.color.fb_icon

    /** 矢量图标换色：imageTintList 会盖掉 vector 里写死的 strokeColor */
    private fun tint(v: ImageView, colorRes: Int) {
        v.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(v.context, colorRes))
        v.alpha = if (v.isEnabled) 1f else ALPHA_DISABLED
    }

    private fun toast(msg: String) {
        Toast.makeText(inkBar.context, msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 不可用状态：淡到看得出「现在没得点」，又不至于像没画出来 */
        const val ALPHA_DISABLED = 0.32f
    }
}
