package com.lytree.qbank.data.model

import androidx.annotation.StringRes
import com.lytree.qbank.R

/**
 * 背诵模块（「背诵」Tab 下的两块背诵内容）。
 *
 * 政治与常识各是一套独立内容：各自的内置资产、各自的分类分组、各自的重点收藏与
 * 上次背到哪张卡。字号和「背诵模式」是使用习惯，两边共用。
 */
enum class ReciteSubject(
    /** 存库时用作 key 的一部分，改了就相当于换一套数据，别动 */
    val id: String,
    /** assets 下的内容文件 */
    val asset: String,
    @StringRes val titleRes: Int,
) {
    POLITICS("politics", "recite_politics.json", R.string.title_recite_politics),
    COMMON("common", "recite_common.json", R.string.title_recite_common);

    companion object {
        fun of(id: String?): ReciteSubject = entries.firstOrNull { it.id == id } ?: POLITICS
    }
}
