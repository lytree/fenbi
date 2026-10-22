package com.lytree.qbank.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import com.lytree.qbank.databinding.DialogMaterialBinding

/**
 * 「弹出材料」浮层。
 *
 * 资料分析的材料往往是「一段文字 + 一张表 / 一张图」，塞在题目卡里只能看到一部分，
 * 做题时要反复上下滚。参考应用的做法是给材料卡右上角一个「弹出材料」，
 * 点开占大半屏、独立滚动，页内题目位置不受影响。
 *
 * 这里不新起 Activity：材料只是当前题的附属内容，用 [Dialog] 更轻，
 * 关掉之后答题状态、手写笔迹、滚动位置都原样保留。
 * 右上角 A- / A+ 直接重渲染正文（段落与表格同步缩放，图表按宽度自适应）。
 */
object MaterialDialog {

    private const val MIN_SCALE = 0.85f
    private const val MAX_SCALE = 1.6f
    private const val STEP = 0.15f

    fun show(activity: Activity, material: String, night: Boolean = false) {
        if (material.isBlank()) return

        val dialog = Dialog(activity)
        val b = DialogMaterialBinding.inflate(activity.layoutInflater)
        dialog.setContentView(b.root)
        dialog.setCanceledOnTouchOutside(true)

        b.root.setBackgroundResource(
            if (night) R.drawable.bg_fb_dialog_night else R.drawable.bg_fb_dialog
        )
        val titleColor = ContextCompat.getColor(
            activity, if (night) R.color.fb_night_text else R.color.fb_text_title
        )
        val metaColor = ContextCompat.getColor(
            activity, if (night) R.color.fb_night_text_meta else R.color.fb_text_meta
        )
        b.tvMaterialTitle.setTextColor(titleColor)
        b.btnMaterialZoomIn.setTextColor(metaColor)
        b.btnMaterialZoomOut.setTextColor(metaColor)
        b.btnMaterialClose.setColorFilter(metaColor)
        b.materialDialogDivider.setBackgroundColor(
            ContextCompat.getColor(
                activity, if (night) R.color.fb_night_divider else R.color.fb_divider
            )
        )

        var scale = 1f
        fun render() {
            // 弹窗里空间大，图表按 1.4 倍高画，刻度与折线更好读
            MaterialRenderer.render(
                activity, b.materialDialogContent, material, night, scale, chartScale = 1.4f
            )
            b.materialDialogScroll.scrollTo(0, 0)
        }
        render()

        b.btnMaterialZoomIn.setOnClickListener {
            scale = (scale + STEP).coerceAtMost(MAX_SCALE)
            render()
        }
        b.btnMaterialZoomOut.setOnClickListener {
            scale = (scale - STEP).coerceAtLeast(MIN_SCALE)
            render()
        }
        b.btnMaterialClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = activity.resources.displayMetrics
            val margin = (24 * dm.density).toInt()
            setLayout(dm.widthPixels - margin * 2, (dm.heightPixels * 0.86f).toInt())
            setGravity(Gravity.CENTER)
            // 弹层高度已按屏幕 86% 算好，别再让系统按内容重新量一遍
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            b.root.layoutParams = b.root.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }
}
