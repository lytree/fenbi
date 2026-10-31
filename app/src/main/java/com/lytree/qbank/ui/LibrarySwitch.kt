package com.lytree.qbank.ui

import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import com.lytree.qbank.databinding.ViewLibrarySwitchBinding

/**
 * 「背诵」Tab 顶部的「词库 / 政治 / 常识」切换条。
 *
 * 底部导航只有 4 个入口，词库、政治背诵、常识背诵三块内容都并进这一个 Tab，
 * 三个页面各自带这一条切换互相跳转。样式在这里统一（主蓝 + 加粗 + 下划线），
 * 各页只负责把点击映射到自己的跳转。
 */
object LibrarySwitch {

    const val PAGE_WORDS = 0
    const val PAGE_POLITICS = 1
    const val PAGE_COMMON = 2

    fun bind(
        binding: ViewLibrarySwitchBinding,
        selected: Int,
        onWords: () -> Unit,
        onPolitics: () -> Unit,
        onCommon: () -> Unit
    ) {
        binding.switchWords.setOnClickListener { onWords() }
        binding.switchPolitics.setOnClickListener { onPolitics() }
        binding.switchCommon.setOnClickListener { onCommon() }
        style(binding.switchWordsText, binding.switchWordsLine, selected == PAGE_WORDS)
        style(binding.switchPoliticsText, binding.switchPoliticsLine, selected == PAGE_POLITICS)
        style(binding.switchCommonText, binding.switchCommonLine, selected == PAGE_COMMON)
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
