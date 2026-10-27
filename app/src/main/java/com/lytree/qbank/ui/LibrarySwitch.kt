package com.lytree.qbank.ui

import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import com.lytree.qbank.databinding.ViewLibrarySwitchBinding

/**
 * 词库页顶部的「词库 / 成语」切换条。
 *
 * 底部导航最多 5 个入口，成语汇总并进词库页，两个页面各自带这一条切换互相跳转。
 * 样式在这里统一（主蓝 + 加粗 + 下划线），各页只负责把点击映射到自己的跳转。
 */
object LibrarySwitch {

    fun bind(
        binding: ViewLibrarySwitchBinding,
        idiomSelected: Boolean,
        onWords: () -> Unit,
        onIdioms: () -> Unit
    ) {
        binding.switchWords.setOnClickListener { onWords() }
        binding.switchIdioms.setOnClickListener { onIdioms() }
        style(binding.switchWordsText, binding.switchWordsLine, !idiomSelected)
        style(binding.switchIdiomsText, binding.switchIdiomsLine, idiomSelected)
    }

    private fun style(text: TextView, line: View, selected: Boolean) {
        text.setTextColor(
            ContextCompat.getColor(
                text.context,
                if (selected) R.color.fb_primary else R.color.fb_text_secondary
            )
        )
        text.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        line.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }
}
