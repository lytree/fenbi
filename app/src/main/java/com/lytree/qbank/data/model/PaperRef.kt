package com.lytree.qbank.data.model

/**
 * 「卷 id」的约定：**正数 = 一份试卷，负数 = 一个导入组**，0 = 无效。
 *
 * 首页是四级树：导入组 → 试卷 → 大类 → 材料。每一层都能当成一张卷来答，最顶上那一层
 * （导入组）不是库里的一行 `papers`，但记录、未交卷进度、报告都只认一个 `paper_id`，
 * 所以给组造一个**虚拟卷 id**：`-组 id`。这样整组作答与组里任何一份卷的记录 / 进度
 * 天然分桶，数据库一行不用改。
 *
 * 0 留给「查不到卷」，负数不会与自增主键撞上。
 */
object PaperRef {

    /** 能不能拿去读卷（0 是无效 id） */
    fun isValid(id: Long): Boolean = id != 0L

    /** 是不是「整组」虚拟卷 */
    fun isGroup(id: Long): Boolean = id < 0L

    /** 导入组 id → 整组虚拟卷 id */
    fun groupPaperId(setId: Long): Long = if (setId > 0L) -setId else 0L

    /** 整组虚拟卷 id → 导入组 id */
    fun setIdOf(paperId: Long): Long = if (paperId < 0L) -paperId else 0L
}
