package com.lytree.qbank.data.model

/**
 * 成语词典条目。
 *
 * 数据来自飞书文档《成语汇总》，随 App 以 asset JSON 内置；释义、首字母（按文档 A-Z 分节）
 * 与归类（来自文档末尾的归类表）一并保存在资源里。
 */
data class Idiom(
    val word: String,
    val definition: String,
    val letter: String,
    val tags: List<IdiomTag>,
)

/** 一条归类路径：所属大类 + 子类（子类可能为空） */
data class IdiomTag(val group: String, val sub: String?)

/** 归类表的一个大类 */
data class IdiomGroup(val name: String, val count: Int, val subs: List<IdiomSub>)

/** 归类表大类下的子类 */
data class IdiomSub(val name: String, val count: Int)

/** 整份词典：成语按文档顺序（A-Z 分节）排列 */
data class IdiomCatalog(
    val idioms: List<Idiom>,
    val groups: List<IdiomGroup>,
) {
    companion object {
        val EMPTY = IdiomCatalog(emptyList(), emptyList())
    }
}
