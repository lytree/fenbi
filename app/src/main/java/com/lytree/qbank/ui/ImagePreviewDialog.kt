package com.lytree.qbank.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.lytree.qbank.R

/**
 * 材料 / 选项配图的大图预览。
 *
 * 点图就整屏铺开（fitCenter，不裁切），点任意处或右上角「×」关闭，返回键也直接退出。
 * 深底是为了看图表时对比度足够。
 *
 * 传的是**图片地址**而不是已经解好的位图：卡片里的图按格宽降采样过（选项格子只有屏宽四分之一），
 * 拿那份位图放大只会更糊；这里按屏幕宽度重新解码一份。
 */
object ImagePreviewDialog {

    fun show(activity: Activity, src: String) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val density = activity.resources.displayMetrics.density

        val root = FrameLayout(activity).apply {
            // 纯黑不透明：半透明会让页面的字透上来，看细线图时反而费眼
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 点空白处关闭；图片自己是不可点的，所以点图也等于点空白
            setOnClickListener { dialog.dismiss() }
        }

        val hint = TextView(activity).apply {
            text = activity.getString(R.string.material_image_loading)
            setTextColor(Color.WHITE)
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                activity.resources.getDimension(R.dimen.fb_text_item_sub)
            )
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        root.addView(hint)

        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding((8 * density).toInt(), (52 * density).toInt(), (8 * density).toInt(), (32 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = ImageView.GONE
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

        // 按屏幕宽度解码：卡片里那份可能只是格宽大小，放大后会糊
        val reqWidth = activity.resources.displayMetrics.widthPixels
        ImageLoader.load(activity, src, reqWidth) { loaded ->
            if (!dialog.isShowing) return@load
            if (loaded == null) {
                hint.text = activity.getString(R.string.material_image_failed, src)
            } else {
                hint.visibility = ImageView.GONE
                image.setImageBitmap(loaded.bitmap)
                image.visibility = ImageView.VISIBLE
            }
        }
    }
}
