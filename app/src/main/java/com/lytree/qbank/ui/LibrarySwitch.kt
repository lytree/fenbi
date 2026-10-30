package com.lytree.qbank.ui

import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import com.lytree.qbank.databinding.ViewLibrarySwitchBinding

/**
 * 词库页顶部的「词库 / 成语 / 考点背诵」切换条。
 *
 * 底部导航只有 4 个入口，成语汇总和考点背诵都并进词库页，三个页面各自带这一条切换互相跳转。
 * 样式在这里统一（主蓝 + 加粗 + 下划线），各页只负责把点击映射到自己的跳转。
 */
object LibrarySwitch {

    const val PAGE_WORDS = 0
    const val PAGE_IDIOMS = 1
    const val PAGE_RECITE = 2

    fun bind(
        binding: ViewLibrarySwitchBinding,
        selected: Int,
        onWords: () -> Unit,
        onIdioms: () -> Unit,
        onRecite: () -> Unit
    ) {
        binding.switchWords.setOnClickListener { onWords() }
        binding.switchIdioms.setOnClickListener { onIdioms() }
        binding.switchRecite.setOnClickListener { onRecite() }
        style(binding.switchWordsText, binding.switchWordsLine, selected == PAGE_WORDS)
        style(binding.switchIdiomsText, binding.switchIdiomsLine, selected == PAGE_IDIOMS)
        style(binding.switchReciteText, binding.switchReciteLine, selected == PAGE_RECITE)
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
