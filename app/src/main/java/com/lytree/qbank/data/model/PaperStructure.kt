package com.lytree.qbank.data.model

/**
 * 试卷三级结构：大类题型 → 材料 → 题目。
 *
 * 试卷文件是「一题一行」平铺导入的（`paper_questions` 只记顺序），但真实试卷的组织方式是：
 *
 * ```
 * 试卷
 * └── 大类题型（资料分析 / 言语理解 / 常识判断…）—— 连续相同 subject
 *     └── 材料（一则材料，含表格 / 配图 / 图表）—— 连续相同 material
 *         └── 题目（全卷连续编号 1..N）
 * ```
 *
 * 这个结构在这里**一次算好**：单题浏览分页（[com.lytree.qbank.ui.QuestionPagerAdapter]）、
 * 多题浏览的材料去重与说明卡（[com.lytree.qbank.ui.QuestionListAdapter]）、答题卡分组、
 * 弹出材料与左右分屏都从这里取数。以前每处各写一份「连续同 subject / 连续同 material」的判据，
 * 改一处漏三处。
 *
 * 口径（对齐 `docs/08-试卷三级结构设计.md`）：
 * - **题号 = 题目在整卷中的顺序（1..N）**，材料组内不复用组内编号；
 * - **大类边界优先于材料**：同一材料跨大类时在边界断开，说明页才不会被卷进组页；
 * - `subject` 为空的题归入一个 [Section.name] 为 null 的大类（卷面不出说明卡）；
 * - 同一材料在卷面**不连续**（中间插了别的题）时按出现位置各成一组，不强行并组——
 *   保证卷面顺序与题号始终一致。
 *
 * 纯派生对象，不落库；题目集合变化（如补答案后重读整卷）就重建一个。
 */
class PaperStructure(private val questions: List<Question>) {

    /**
     * 材料组：一则材料 + 它带的全部小题。
     * 无材料的题各自成一组（[isMaterialGroup] = false），保证「每道题都属于且只属于一组」。
     */
    class Group(
        /** 所属大类在 [PaperStructure.sections] 中的下标 */
        val sectionIndex: Int,
        /** 组内第一题在整卷中的位置（0 起） */
        val startIndex: Int,
        /** 材料代表题：材料组取组首题（材料 / 配图 / 图表字段都在它身上），单题组就是它自己 */
        val material: Question,
        /** 组内题目（卷面顺序） */
        val questions: List<Question>
    ) {
        val count: Int get() = questions.size

        /** 组尾（不含） */
        val endIndex: Int get() = startIndex + count

        val isMaterialGroup: Boolean get() = material.material.isNotBlank()

        fun contains(questionIndex: Int): Boolean = questionIndex in startIndex until endIndex
    }

    /** 大类题型（模块）：卷面上先出一张说明卡，再往下排这一类的材料组 */
    class Section(val name: String?, val groups: List<Group>) {
        /** 本大类第一题在整卷中的位置 */
        val startIndex: Int = groups.firstOrNull()?.startIndex ?: 0

        /** 本大类的题量（说明卡标题里的「N题」） */
        val count: Int = groups.sumOf { it.questions.size }

        /** 本大类按卷面顺序的全部题目 */
        val questions: List<Question> = groups.flatMap { it.questions }

        val endIndex: Int get() = startIndex + count
    }

    /** 大类列表（卷面顺序） */
    val sections: List<Section>

    /** 全卷平铺的材料组（卷面顺序），供单题分页 / 分屏直接索引 */
    val groups: List<Group>

    /** 题号（0 起）→ 组下标；结构未覆盖的题号为 -1 */
    private val groupIndexByQuestion: IntArray

    /** 题号（0 起）→ 大类下标 */
    private val sectionIndexByQuestion: IntArray

    /** 题目 id → 题号（0 起），[groupOf] 用；id 为 0（尚未入库）的题退回按引用查找 */
    private val indexById: Map<Long, Int>

    init {
        val builtGroups = ArrayList<Group>()
        val builtSections = ArrayList<Section>()

        var i = 0
        while (i < questions.size) {
            val sectionName = sectionName(questions[i])
            val sectionGroups = ArrayList<Group>()

            // 大类内：连续相同的材料归一组；无材料的题单独成组
            var j = i
            while (j < questions.size && sectionName(questions[j]) == sectionName) {
                val materialKey = questions[j].material.trim()
                var end = j + 1
                if (materialKey.isNotEmpty()) {
                    while (end < questions.size &&
                        sectionName(questions[end]) == sectionName &&
                        questions[end].material.trim() == materialKey
                    ) {
                        end++
                    }
                }
                sectionGroups += Group(
                    sectionIndex = builtSections.size,
                    startIndex = j,
                    material = questions[j],
                    // 复制一份：外部持有的题目列表会就地 clear + addAll（补答案后重读），
                    // 直接拿 subList 视图会在下一次变动时炸掉
                    questions = questions.subList(j, end).toList()
                )
                j = end
            }

            builtSections += Section(sectionName, sectionGroups)
            builtGroups += sectionGroups
            i = j
        }

        sections = builtSections
        groups = builtGroups

        groupIndexByQuestion = IntArray(questions.size) { -1 }
        sectionIndexByQuestion = IntArray(questions.size) { -1 }
        groups.forEachIndexed { groupIndex, group ->
            for (k in group.startIndex until group.endIndex) groupIndexByQuestion[k] = groupIndex
        }
        sections.forEachIndexed { sectionIndex, section ->
            for (k in section.startIndex until section.endIndex) sectionIndexByQuestion[k] = sectionIndex
        }
        indexById = HashMap<Long, Int>(questions.size).also { map ->
            questions.forEachIndexed { index, q -> if (q.id != 0L) map.putIfAbsent(q.id, index) }
        }
    }

    val size: Int get() = questions.size

    /** 题号（0 起）→ 所属材料组；越界返回 null */
    fun groupAt(questionIndex: Int): Group? =
        groupIndexByQuestion.getOrNull(questionIndex)?.takeIf { it >= 0 }?.let { groups[it] }

    /** 题号（0 起）→ 所属大类；越界返回 null */
    fun sectionAt(questionIndex: Int): Section? =
        sectionIndexByQuestion.getOrNull(questionIndex)?.takeIf { it >= 0 }?.let { sections[it] }

    /** 题目 → 所属材料组（按 id 或引用找位置，不依赖调用方自己算下标） */
    fun groupOf(q: Question): Group? {
        val index = indexById[q.id] ?: questions.indexOfFirst { it === q }
        return groupAt(index)
    }

    /** 该题是否是一个材料组的**组首**（组内后面的题不再重复渲染材料） */
    fun isGroupStart(questionIndex: Int): Boolean =
        groupAt(questionIndex)?.startIndex == questionIndex

    /** 该题是否是一个大类的**首题**（说明卡只在首题前出现一次） */
    fun isSectionStart(questionIndex: Int): Boolean =
        sectionAt(questionIndex)?.startIndex == questionIndex

    companion object {
        /**
         * 大类命名归一：`行测·资料分析` / `行测-资料分析` / `行测/资料分析` → `资料分析`；
         * 空串返回 null（这段题不渲染说明卡，避免给手打试题硬塞一个空标题）。
         */
        fun sectionName(q: Question): String? {
            val raw = q.subject.trim()
            if (raw.isEmpty()) return null
            val name = raw
                .substringAfterLast('·')
                .substringAfterLast('-')
                .substringAfterLast('/')
                .trim()
            return name.ifEmpty { raw }
        }
    }
}
