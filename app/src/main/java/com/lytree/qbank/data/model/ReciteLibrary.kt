package com.lytree.qbank.data.model

/**
 * 统一内容库里的一条内容。
 *
 * 政治理论原来分成两份资产（考点卡 + 思维导图），现在合成一份：
 * 「板块 / 章节」那两层分组**不再存在**，只剩标签（[LibraryTag]），
 * 一条内容可以同时命中多个标签，[mainTag] 是它的主归组（大纲里挂在哪一支）。
 *
 * · [kind] = card：填空题，正文里的 `____` 按 [answers] 顺序替换；
 * · [kind] = line：导图还原出来的行，[depth] 表示层级（0 为一级分支）。
 */
data class LibraryItem(
    val id: String,
    val kind: String,
    val text: String,
    val answers: List<String>,
    /** 来自哪一讲 / 哪个主题，大纲里作视觉分段的小标题（不是分组层级） */
    val source: String,
    /** 命中的全部标签 id，内容标签在前、来源标签在后 */
    val tags: List<String>,
    /** 主归组标签 id（第一个内容标签）；空串表示没打上标签，归「未归类」 */
    val mainTag: String,
    /** 导图主题 id（card 为空） */
    val topic: String,
    val topicName: String,
    /** 导图条目在主题内的路径，如 "0.1"；card 为空 */
    val path: String,
    val depth: Int,
    /**
     * 遮答用的挖空区间（字符下标，含头含尾），由 `tools/build_library.cs` 预先算好。
     * 只有导图行有；空表示这条是单句短语，遮起来没法猜，不如整条显示。
     * 卡片不走这里——它自带 `____` + [answers]。
     */
    val cloze: List<IntRange> = emptyList(),
) {
    val isCard: Boolean get() = kind == KIND_CARD

    companion object {
        const val KIND_CARD = "card"
        const val KIND_LINE = "line"
    }
}

/** 一个标签：kind=topic 是内容标签（大纲一级分支），kind=source 是来源标签（只用来筛） */
data class LibraryTag(
    val id: String,
    val name: String,
    val kind: String,
    val count: Int,
) {
    val isTopic: Boolean get() = kind == KIND_TOPIC

    companion object {
        const val KIND_TOPIC = "topic"
        const val KIND_SOURCE = "source"
    }
}

/** 整份政治理论内容库（assets/recite_library.json） */
data class ReciteLibrary(
    val title: String,
    val updated: String,
    val intro: String,
    val tags: List<LibraryTag>,
    val items: List<LibraryItem>,
) {
    /** 内容标签：大纲一级分支只用这一批，来源标签不进大纲 */
    val topicTags: List<LibraryTag> get() = tags.filter { it.isTopic }

    val isEmpty: Boolean get() = items.isEmpty()

    /** 已背进度按条算，卡片和导图行一起数 */
    fun doneCount(done: Set<String>): Int = items.count { it.id in done }

    fun tagName(id: String): String = tags.firstOrNull { it.id == id }?.name ?: id

    companion object {
        val EMPTY = ReciteLibrary("", "", "", emptyList(), emptyList())
    }
}
