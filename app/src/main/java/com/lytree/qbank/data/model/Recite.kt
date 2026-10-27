package com.lytree.qbank.data.model

/** 一个背诵考点：正文用 ____ 表示填空，answers 按出现顺序对应每个空。 */
data class ReciteCard(
    val id: String,
    val categoryId: String,
    val categoryName: String,
    val text: String,
    val answers: List<String>,
    val note: String,
    val source: String,
    val seq: Int,
)

data class ReciteCategory(
    val id: String,
    val name: String,
)

data class ReciteCatalog(
    val title: String,
    val updated: String,
    val intro: String,
    val categories: List<ReciteCategory>,
    val cards: List<ReciteCard>,
) {
    companion object {
        val EMPTY = ReciteCatalog("", "", "", emptyList(), emptyList())
    }
}
