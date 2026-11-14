package com.lytree.qbank.data.model

/**
 * 思维导图的一条大纲行。
 *
 * level 0 中心主题 / 分组标签（一页可能有两个，如「五中全会 3」「五中全会 4」），
 * level 1 一级分支，2..4 逐层往右的要点。层级由原书导图的连线决定，
 * 渲染时只按 level 缩进，不再回推树结构。
 */
data class MindMapNode(val level: Int, val text: String)

/** 书页下半部「内涵连线」的一组对应：提法 ↔ 内涵 */
data class MindMapPair(val left: String, val right: String)

/** 一页导图 = 一个主题 */
data class MindMapPage(
    val id: String,
    /** 书页号（书页 02 起，源 PDF 前 3 页无内容） */
    val page: Int,
    /** 章节名，如「五中全会3-4 · 党性与从严治吏」 */
    val name: String,
    /** 弹层/列表里的归类，如「党的建设」，空串表示不分组 */
    val group: String,
    val nodes: List<MindMapNode>,
    val pairs: List<MindMapPair>,
) {
    /** 中心主题：没有 level 0 时退回章节名 */
    val topic: String
        get() = nodes.firstOrNull { it.level <= 0 }?.text?.takeIf { it.isNotBlank() } ?: name

    val branchCount: Int get() = nodes.count { it.level == 1 }

    val pointCount: Int get() = nodes.count { it.level >= 2 }
}

data class MindMapCatalog(
    val title: String,
    val updated: String,
    val intro: String,
    val pages: List<MindMapPage>,
) {
    companion object {
        val EMPTY = MindMapCatalog("", "", "", emptyList())
    }
}
