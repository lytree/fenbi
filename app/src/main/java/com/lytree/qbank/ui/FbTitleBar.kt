package com.lytree.qbank.ui

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lytree.qbank.R

/**
 * 全站统一标题栏。
 *
 * 视觉口径与答题页顶栏完全一致（白底 44dp / 细线图标 28dp / 标题 17sp 加粗），
 * 状态栏高度自动并入 padding，避免 Android 15 强制 edge-to-edge 后被状态栏压住。
 *
 * ```kotlin
 * titleBar.setTitle("导入试卷")
 * titleBar.showBack(true)
 * titleBar.addTextAction("全选") { toggleAll() }
 * ```
 */
class FbTitleBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tvTitle: TextView
    private val btnBack: ImageView
    private val actions: LinearLayout
    private val divider: View
    private val timerPill: View
    private val tvTimer: TextView
    private val icTimer: ImageView

    private val density = resources.displayMetrics.density
    private val baseHeight = resources.getDimensionPixelSize(R.dimen.fb_bar_h)
    private var lastInset = -1

    /** 返回键回调；不设置时默认 finish() */
    var onBack: (() -> Unit)? = null

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.fb_bar_bg))
        LayoutInflater.from(context).inflate(R.layout.view_fb_title_bar, this, true)
        tvTitle = findViewById(R.id.tvTitle)
        btnBack = findViewById(R.id.btnBack)
        actions = findViewById(R.id.barActions)
        divider = findViewById(R.id.barDivider)
        timerPill = findViewById(R.id.timerPill)
        tvTimer = findViewById(R.id.tvTimer)
        icTimer = findViewById(R.id.icTimer)

        btnBack.setOnClickListener {
            onBack?.invoke() ?: (context as? Activity)?.finish()
        }

        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (top != lastInset) {
                lastInset = top
                v.setPadding(v.paddingLeft, top, v.paddingRight, 0)
                v.layoutParams?.let { lp ->
                    lp.height = baseHeight + top
                    v.layoutParams = lp
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    fun setTitle(title: CharSequence?) {
        tvTitle.text = title
    }

    /**
     * 顶栏计时胶囊（返回键右侧的「🕐 01:08」）。
     * 传 null 或空串即隐藏——默认就是隐藏的，只有分屏这类专注作答的页面会打开。
     */
    fun setTimer(text: CharSequence?, textColor: Int = 0) {
        val show = !text.isNullOrBlank()
        timerPill.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        tvTimer.text = text
        if (textColor != 0) {
            tvTimer.setTextColor(textColor)
            icTimer.setColorFilter(textColor)
        }
    }

    /** Tab 页：左对齐 20sp 大标题（参考应用首页口径），并默认隐藏返回键 */
    fun usePageTitleStyle(marginStart: Int = 16) {
        val lp = tvTitle.layoutParams as LayoutParams
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        lp.marginStart = (marginStart * density).toInt()
        tvTitle.layoutParams = lp
        tvTitle.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        tvTitle.setPadding(0, 0, 0, 0)
        tvTitle.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(R.dimen.fb_text_page_title)
        )
        btnBack.visibility = View.GONE
    }

    fun showBack(show: Boolean) {
        btnBack.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun showDivider(show: Boolean) {
        divider.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun clearActions() = actions.removeAllViews()

    /** 右侧文字动作（主蓝 13sp），最后一个动作不再留右边距 */
    fun addTextAction(text: CharSequence, onClick: () -> Unit): TextView {
        val tv = TextView(context).apply {
            this.text = text
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.fb_text_body_sm)
            )
            setTextColor(ContextCompat.getColor(context, R.color.fb_primary))
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(
                resources.getDimensionPixelSize(R.dimen.fb_space_8),
                resources.getDimensionPixelSize(R.dimen.fb_space_4),
                resources.getDimensionPixelSize(R.dimen.fb_space_8),
                resources.getDimensionPixelSize(R.dimen.fb_space_4)
            )
            isClickable = true
            setOnClickListener { onClick() }
        }
        actions.addView(tv)
        return tv
    }

    /** 右侧图标动作（28dp 细线图标） */
    fun addIconAction(@DrawableRes icon: Int, desc: CharSequence?, onClick: () -> Unit): ImageView {
        val iv = ImageView(context).apply {
            val size = resources.getDimensionPixelSize(R.dimen.fb_bar_item_width)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setImageResource(icon)
            contentDescription = desc
            val pad = resources.getDimensionPixelSize(R.dimen.fb_space_4)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            setOnClickListener { onClick() }
        }
        actions.addView(iv)
        return iv
    }
}
