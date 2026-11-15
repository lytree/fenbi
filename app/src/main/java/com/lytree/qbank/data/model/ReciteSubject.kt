package com.lytree.qbank.data.model

import androidx.annotation.StringRes
import com.lytree.qbank.R

/**
 * 背诵模块（「背诵」Tab 下的两块内容）。
 *
 * **现在只有常识还走卡片流**：政治早已换成统一库的大纲页（`recite_library.json`，
 * 见 `ReciteOutlineFragment`），[POLITICS] 只剩「从哪儿跳回政治」和进度 key 的用途，
 * 它的卡片资产 `recite_politics.json` 已经删了，读到空内容是正常的。
 *
 * 两块内容各有分类分组、重点收藏、上次背到哪张卡；字号与「背诵模式」共用。
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
