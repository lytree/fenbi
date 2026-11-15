package com.lytree.qbank.ui.recite

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.lytree.qbank.R

/**
 * 顶栏「A小 / A中 / A大」的选字号弹层。
 *
 * 原来是连点循环：想从「小」调到「大」得点三次，点过头还要再绕一圈。
 * 直接弹三选一，一次到位；三档本来就是有限选项，不需要「下一档」这种隐藏状态。
 */
internal object ReciteFontPicker {

    fun show(context: Context, current: Int, onPick: (Int) -> Unit) {
        val labels = arrayOf(
            context.getString(R.string.recite_font_small),
            context.getString(R.string.recite_font_medium),
            context.getString(R.string.recite_font_large),
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.recite_font_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                if (which != current) onPick(which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
