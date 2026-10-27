package com.lytree.qbank.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.lytree.qbank.R

/**
 * 材料配图的大图预览。
 *
 * 点材料里的图片就整屏铺开（fitCenter，不裁切），点任意处或右上角「×」关闭，
 * 返回键也直接退出。深底是为了看图表时对比度足够。
 */
object ImagePreviewDialog {

    fun show(activity: Activity, bitmap: Bitmap) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val density = activity.resources.displayMetrics.density

        val root = FrameLayout(activity).apply {
            setBackgroundColor(0xF5000000.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val image = ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding((10 * density).toInt(), (48 * density).toInt(), (10 * density).toInt(), (24 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(image)

        val close = TextView(activity).apply {
            text = "×"
            setTextColor(Color.WHITE)
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                activity.resources.getDimension(R.dimen.fb_text_page_title)
            )
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                (40 * density).toInt(), (40 * density).toInt()
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (24 * density).toInt()
                rightMargin = (10 * density).toInt()
            }
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(close)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
    }
}
