package com.lytree.qbank.data.model

/**
 * 答题范围：一张卷既能整卷做，也能「每个层级各自当成一张小卷」做。
 *
 * 试卷是三级结构（大类题型 → 材料 → 题目，见 [PaperStructure]），这里把「这次做哪一截」
 * 表达成一个可以落库的短串（[key]）。记录与未交卷进度都按它分桶：整卷、资料分析、
 * 某一则材料各做各的，互不覆盖——在「资料分析」里作答不会把整卷的进度冲掉。
 *
 * 三种范围与对应的 key：
 *
 * | 范围 | key | 说明 |
 * |---|---|---|
 * | 整卷 | 空串 | 老记录、老进度都是这个口径，不用迁移 |
 * | 一个大类 | `s:<锚点题 id>` | 锚点 = 这个大类首题的 id |
 * | 一则材料 | `m:<锚点题 id>` | 锚点 = 材料组首题的 id |
 *
 * 用**题目 id** 而不是大类名当锚点：大类名是 `subject` 归一出来的，重命名 / 重新导入后
 * 可能变；题目 id 在库里是稳定的。范围只存 key，展示用的标题（「资料分析 · 材料2」）
 * 每次进卷时按当前结构现算（[label]），不落库也就不会过期。
 *
 * 纯派生对象：题目集合变了（补答案后重读整卷）就按新结构重新 [resolve]。
 */
data class ExamScope(
    /** 锚点题 id：大类首题 / 材料组首题；0 = 整卷 */
    val anchorQuestionId: Long = 0L,
    /** 只做这一则材料；false = 整个大类 */
    val material: Boolean = false
) {

    val isWholePaper: Boolean get() = anchorQuestionId <= 0L

    /** 落库用的短串（见类注释的表格）；整卷为空串 */
    val key: String
        get() = when {
            isWholePaper -> ""
            material -> "$KEY_MATERIAL$anchorQuestionId"
            else -> "$KEY_SECTION$anchorQuestionId"
        }

    /**
     * 本范围包含的题目（卷面顺序）。
     *
     * 锚点在当前结构里找不到（题目被删 / 卷子重新导入过）时**逐级退回**：
     * 材料 → 该题所在大类 → 整卷。宁可多做几题，也不能点了「资料分析」进去空着。
     */
    fun resolve(structure: PaperStructure): List<Question> {
        if (isWholePaper) return structure.questions

        if (material) {
            structure.groups.firstOrNull { group -> group.questions.any { it.id == anchorQuestionId } }
                ?.let { return it.questions }
        }

        val index = structure.questions.indexOfFirst { it.id == anchorQuestionId }
        if (index < 0) return structure.questions
        return structure.sectionAt(index)?.questions ?: structure.questions
    }

    /**
     * 记录名 / 顶栏用的短标题：「资料分析」「资料分析 · 材料2」；整卷返回空串。
     * 每次都按当前结构现算，所以改了 `subject` 之后标题会跟着变，不会留下过期名字。
     */
    fun label(structure: PaperStructure): String {
        if (isWholePaper) return ""
        if (material) {
            val group = materialGroupOf(structure)
            if (group != null) {
                val sectionName = structure.sectionAt(group.startIndex)?.name
                // 材料序号在同一大类内数：跨大类的同名材料本来就是两组（见 PaperStructure）
                val ordinal = structure.groups.count { other ->
                    other.isMaterialGroup &&
                        other.startIndex <= group.startIndex &&
                        structure.sectionAt(other.startIndex)?.name == sectionName
                }
                val mat = "材料$ordinal"
                return if (sectionName.isNullOrBlank()) mat else "$sectionName · $mat"
            }
        }
        val index = structure.questions.indexOfFirst { it.id == anchorQuestionId }
        val section = if (index >= 0) structure.sectionAt(index) else null
        return section?.name
            ?: section?.questions?.firstOrNull()?.knowledge?.takeIf { it.isNotBlank() }
            ?: "本模块"
    }

    private fun materialGroupOf(structure: PaperStructure): PaperStructure.Group? =
        structure.groups.firstOrNull { group ->
            group.isMaterialGroup && group.questions.any { it.id == anchorQuestionId }
        }

    companion object {
        private const val KEY_SECTION = "s:"
        private const val KEY_MATERIAL = "m:"

        /** 整卷 */
        val FULL = ExamScope()

        /** 整个大类（[section] 是 [PaperStructure.sections] 里的那一段） */
        fun ofSection(section: PaperStructure.Section): ExamScope =
            ExamScope(anchorQuestionId = section.questions.firstOrNull()?.id ?: 0L)

        /**
         * 一则材料。无材料（单题组）不单独成卷——它本来就是一题，
         * 想单做这一题用「单题浏览」就够了。
         */
        fun ofMaterial(group: PaperStructure.Group): ExamScope =
            if (group.isMaterialGroup) {
                ExamScope(anchorQuestionId = group.questions.firstOrNull()?.id ?: 0L, material = true)
            } else {
                FULL
            }

        /** 解析落库的 key；空串 / 认不出的都退回整卷 */
        fun fromKey(key: String?): ExamScope {
            val raw = key.orEmpty().trim()
            if (raw.isEmpty()) return FULL
            return when {
                raw.startsWith(KEY_MATERIAL) -> ExamScope(
                    anchorQuestionId = raw.removePrefix(KEY_MATERIAL).toLongOrNull() ?: 0L,
                    material = true
                )
                raw.startsWith(KEY_SECTION) -> ExamScope(
                    anchorQuestionId = raw.removePrefix(KEY_SECTION).toLongOrNull() ?: 0L
                )
                else -> FULL
            }
        }
    }
}
