package com.lytree.qbank.data.model

/**
 * 思维导图的一个节点：一句话 + 它下面挂的要点。
 *
 * 树是原书导图连线的还原：中心主题（一页常有两三个，如「五中全会 3」「五中全会 4」）
 * 挂一级分支，分支再往下挂要点，最深见过 7 层。层级只由父子关系表达——
 * 原来的扁平 outline 带 level 字段，深层一多就分不清「缩进到哪一级」了，
 * 现在渲染按递归深度缩进，谁是谁的子节点一目了然。
 */
data class MindMapNode(val text: String, val children: List<MindMapNode>) {

    val hasChildren: Boolean get() = children.isNotEmpty()

    /** 自己 + 全部后代，用于「共 N 条」这种计数 */
    val size: Int get() = 1 + children.sumOf { it.size }
}

/** 书页下半部「内涵连线」的一组对应：提法 ↔ 内涵 */
data class MindMapPair(val left: String, val right: String)

/** 一页书 = 一个主题 */
data class MindMapTopic(
    val id: String,
    /** 源 PDF 页码（书页号 + 2），对照扫件用；0 表示未知 */
    val page: Int,
    /** 列表上的小标签，如「书页 02」 */
    val label: String,
    /** 章节名，如「五中全会3-4 · 党性与从严治吏」 */
    val name: String,
    /** 中心主题，通常 1~2 个 */
    val nodes: List<MindMapNode>,
    val pairs: List<MindMapPair>,
) {
    val nodeCount: Int get() = nodes.sumOf { it.size }

    /** 一级分支数（中心主题下直接挂的节点） */
    val branchCount: Int get() = nodes.sumOf { it.children.size }
}

/** 书里的一个大板块，如「党的建设与全面从严治党」 */
data class MindMapSection(
    val id: String,
    val name: String,
    val topics: List<MindMapTopic>,
) {
    val nodeCount: Int get() = topics.sumOf { it.nodeCount }

    val pairCount: Int get() = topics.sumOf { it.pairs.size }
}

/**
 * 整份导图。
 *
 * 与政治背诵卡是两份数据：那边是「____ 填空自测」，这边是「整段通读 + 连线配对」，
 * 所以不挖空——导图的句子就是原书原文，挖了反而读不顺。
 */
data class MindMapCatalog(
    val title: String,
    val updated: String,
    val intro: String,
    val sections: List<MindMapSection>,
) {
    val topics: List<MindMapTopic> get() = sections.flatMap { it.topics }

    val topicCount: Int get() = topics.size

    val nodeCount: Int get() = sections.sumOf { it.nodeCount }

    val isEmpty: Boolean get() = sections.isEmpty()

    fun sectionOf(topicId: String): MindMapSection? =
        sections.firstOrNull { s -> s.topics.any { it.id == topicId } }

    companion object {
        val EMPTY = MindMapCatalog("", "", "", emptyList())
    }
}
