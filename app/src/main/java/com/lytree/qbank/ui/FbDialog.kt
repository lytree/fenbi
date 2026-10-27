package com.lytree.qbank.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import com.lytree.qbank.databinding.DialogFbConfirmBinding
import com.lytree.qbank.databinding.DialogFbInputBinding
import com.lytree.qbank.databinding.DialogFbSheetBinding

/**
 * 全站统一弹窗。四种形态，一套视觉：
 *
 * | 形态 | 用在 | 样子 |
 * |---|---|---|
 * | [confirm] | 二次确认（交卷 / 删除 / 退出） | 居中白卡 12dp 圆角 + 两个等宽胶囊按钮 |
 * | [alert]   | 单按钮提示（出错 / 告知） | 同上，只留一个按钮 |
 * | [list]    | 长按菜单 / 多选一 | 底部弹层：52dp 动作行 + 独立「取消」块 |
 * | [input]   | 笔记 / 批量粘贴 | 居中白卡 + 多行输入框 |
 *
 * 为什么不用 Material 的 AlertDialog：粉笔的弹窗是自绘白卡（12dp 圆角、等宽胶囊按钮、
 * 无标题栏、无图标），Material 那套（左对齐标题 + 文字按钮 + 大圆角）一眼就能看出不是这个 App 的。
 * 所以这里全部自建 [Dialog]，只复用布局与样式。
 */
object FbDialog {

    // ---------------------------------------------------------------- 确认 / 提示

    fun confirm(
        context: Context,
        message: CharSequence,
        okText: CharSequence = context.getString(R.string.action_confirm),
        cancelText: CharSequence = context.getString(R.string.action_cancel),
        cancelable: Boolean = true,
        onOk: () -> Unit,
        onCancel: (() -> Unit)? = null
    ): Dialog {
        val b = DialogFbConfirmBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context)
        dialog.setContentView(b.root)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setCancelable(cancelable)

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

        centerCard(context, dialog)
        dialog.show()
        return dialog
    }

    /** 单按钮提示（出错 / 告知），不再弹系统 Dialog */
    fun alert(
        context: Context,
        message: CharSequence,
        okText: CharSequence = context.getString(R.string.action_confirm),
        title: CharSequence? = null,
        onOk: (() -> Unit)? = null
    ): Dialog {
        val b = DialogFbConfirmBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context)
        dialog.setContentView(b.root)
        dialog.setCanceledOnTouchOutside(true)

        b.tvMessage.text = if (title.isNullOrBlank()) message else "$title\n\n$message"
        b.btnOk.text = okText
        b.btnCancel.visibility = View.GONE
        b.btnOk.setOnClickListener {
            dialog.dismiss()
            onOk?.invoke()
        }

        centerCard(context, dialog)
        dialog.show()
        return dialog
    }

    // ---------------------------------------------------------------- 底部动作弹层

    /**
     * 长按菜单 / 多选一。标题与说明都可空；行数不固定，所有行共用 [R.style.FbSheetRow]。
     *
     * ```kotlin
     * FbDialog.list(context, "行测基础示例.json", items = arrayOf("补录答案", "删除试卷")) { which -> ... }
     * ```
     */
    fun list(
        context: Context,
        title: CharSequence? = null,
        items: Array<out CharSequence>,
        message: CharSequence? = null,
        cancelText: CharSequence = context.getString(R.string.action_cancel),
        onPick: (Int) -> Unit
    ): Dialog {
        val b = DialogFbSheetBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context)
        dialog.setContentView(b.root)

        b.tvTitle.text = title
        b.tvTitle.visibility = if (title.isNullOrBlank()) View.GONE else View.VISIBLE
        b.tvMessage.text = message
        b.tvMessage.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        b.btnCancel.text = cancelText
        b.btnCancel.setOnClickListener { dialog.dismiss() }

        val ripple = ContextCompat.getColor(context, R.color.fb_ripple)
        val dividerColor = ContextCompat.getColor(context, R.color.fb_divider)
        val dividerInset = context.resources.getDimensionPixelSize(R.dimen.fb_page_pad)
        val dividerHeight =
            (context.resources.displayMetrics.density * 0.5f).toInt().coerceAtLeast(1)

        items.forEachIndexed { index, label ->
            if (index > 0) {
                b.rows.addView(
                    View(context).apply { setBackgroundColor(dividerColor) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dividerHeight)
                        .apply { marginStart = dividerInset }
                )
            }
            // 直接 new 出来的 View 不会自动吃 style 里的 layout_*，行高要显式给
            b.rows.addView(
                TextView(context, null, 0, R.style.FbSheetRow).apply {
                    text = label
                    background = RippleDrawable(ColorStateList.valueOf(ripple), null, null)
                    setOnClickListener {
                        dialog.dismiss()
                        onPick(index)
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.resources.getDimensionPixelSize(R.dimen.fb_row_h)
                )
            )
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        return dialog
    }

    // ---------------------------------------------------------------- 输入弹窗

    /** 笔记 / 批量粘贴这类输入框，代替「系统 AlertDialog + setView」 */
    fun input(
        context: Context,
        title: CharSequence? = null,
        initial: CharSequence = "",
        hint: CharSequence? = null,
        multiline: Boolean = true,
        okText: CharSequence = context.getString(R.string.action_confirm),
        onOk: (String) -> Unit,
        onCancel: (() -> Unit)? = null
    ): Dialog {
        val b = DialogFbInputBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context)
        dialog.setContentView(b.root)

        b.tvTitle.text = title
        b.tvTitle.visibility = if (title.isNullOrBlank()) View.GONE else View.VISIBLE
        b.etInput.setText(initial)
        if (!hint.isNullOrBlank()) b.etInput.hint = hint
        b.etInput.inputType = if (multiline) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        b.etInput.minLines = if (multiline) 4 else 1
        b.etInput.setSelection(b.etInput.text?.length ?: 0)
        b.btnOk.text = okText
        b.btnOk.setOnClickListener {
            dialog.dismiss()
            onOk(b.etInput.text?.toString().orEmpty())
        }
        b.btnCancel.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }

        centerCard(context, dialog)
        dialog.show()
        return dialog
    }

    // ---------------------------------------------------------------- 内部：窗口尺寸

    /** 居中白卡：宽 320dp 以内，窄屏按「屏宽 - 64dp」收窄 */
    private fun centerCard(context: Context, dialog: Dialog) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = context.resources.displayMetrics
            val maxW = (320 * dm.density).toInt()
            val w = minOf(maxW, dm.widthPixels - (64 * dm.density).toInt())
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
    }
}
