package com.lytree.qbank.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.lytree.qbank.R

/**
 * 参考应用风格的开关（iOS 样式的小号 switch）。
 *
 * 不用 [androidx.appcompat.widget.SwitchCompat] 是因为 Material 的开关在宽屏上会被拉大、
 * 且 tint 行为不稳定，画出来和真机的胶囊开关差得很远。
 *
 * 尺寸：38dp x 22dp，轨道圆角 11dp，滑块 18dp 白色圆，开=主蓝 / 关=#E5E7EB。
 */
class FbSwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val onColor = ContextCompat.getColor(context, R.color.fb_primary)
    private val offColor = 0xFFE5E7EB.toInt()
    private val thumbColor = 0xFFFFFFFF.toInt()

    private val density = resources.displayMetrics.density
    private val inset = 2f * density
    private val thumbR = 9f * density

    /** 0f = 关，1f = 开 */
    private var fraction = 0f
    private var animator: ValueAnimator? = null

    var isChecked: Boolean = false
        private set

    /** 开关状态变化回调（仅在用户点击 / 程序设置时触发一次） */
    var onCheckedChange: ((Boolean) -> Unit)? = null

    init {
        trackPaint.style = Paint.Style.FILL
        thumbPaint.style = Paint.Style.FILL
        thumbPaint.color = thumbColor
        setOnClickListener { toggle() }
        isClickable = true
    }

    fun setChecked(checked: Boolean, animate: Boolean = false, notify: Boolean = false) {
        if (isChecked == checked && fraction != 0f) return
        isChecked = checked
        val target = if (checked) 1f else 0f
        animator?.cancel()
        if (animate) {
            animator = ValueAnimator.ofFloat(fraction, target).apply {
                duration = 160
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    fraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            fraction = target
            invalidate()
        }
        if (notify) onCheckedChange?.invoke(checked)
    }

    private fun toggle() {
        isChecked = !isChecked
        val target = if (isChecked) 1f else 0f
        animator?.cancel()
        animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        onCheckedChange?.invoke(isChecked)
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        rect.set(0f, 0f, w, h)
        val r = h / 2f

        // 轨道：关 → 灰，开 → 主蓝（按 fraction 混色）
        trackPaint.color = blend(offColor, onColor, fraction)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        // 滑块
        val cx = (thumbR + inset) + (w - (thumbR + inset) * 2) * fraction
        canvas.drawCircle(cx, h / 2f, thumbR, thumbPaint)
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val fr = (from shr 16) and 0xFF
        val fg = (from shr 8) and 0xFF
        val fb = from and 0xFF
        val tr = (to shr 16) and 0xFF
        val tg = (to shr 8) and 0xFF
        val tb = to and 0xFF
        val r = (fr + (tr - fr) * t).toInt()
        val g = (fg + (tg - fg) * t).toInt()
        val b = (fb + (tb - fb) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
