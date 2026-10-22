package com.lytree.qbank.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.WindowManager
import com.lytree.qbank.R
import com.lytree.qbank.databinding.DialogFbConfirmBinding

/**
 * 参考应用风格确认弹窗。
 *
 * 真机交卷确认框是自绘白卡（12dp 圆角）+ 两个等宽胶囊按钮，
 * 和 Material 的 AlertDialog 差别很大，所以这里不用 AlertDialog 而自建 [Dialog]。
 */
object FbDialog {

    fun confirm(
        context: Context,
        message: CharSequence,
        okText: CharSequence = context.getString(R.string.action_confirm),
        cancelText: CharSequence = context.getString(R.string.action_cancel),
        onOk: () -> Unit,
        onCancel: (() -> Unit)? = null
    ): Dialog {
        val b = DialogFbConfirmBinding.inflate(android.view.LayoutInflater.from(context))
        val dialog = Dialog(context)
        dialog.setContentView(b.root)
        dialog.setCanceledOnTouchOutside(true)

        b.tvMessage.text = message
        b.btnOk.text = okText
        b.btnCancel.text = cancelText
        b.btnOk.setOnClickListener {
            dialog.dismiss()
            onOk()
        }
        b.btnCancel.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = context.resources.displayMetrics
            // 真机弹窗宽约 300dp 以内；窄屏按屏宽 - 64dp
            val maxW = (320 * dm.density).toInt()
            val w = minOf(maxW, dm.widthPixels - (64 * dm.density).toInt())
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dialog.show()
        return dialog
    }
}
