package com.lytree.qbank.data.importer

import com.lytree.qbank.data.model.PaperStructure
import com.lytree.qbank.data.model.Question

/**
 * 导入后的「卷面结构体检」。
 *
 * 三级结构（大类题型 → 材料 → 题目）不落库，完全由 `subject` / `material` 在卷面上的**连续重复**派生
 * （见 [PaperStructure]）。也就是说：文件里一行写歪，卷面结构就变形，而解析阶段一点错都看不出来。
 * 这里只提示、不改数据——自动「沿用上一行材料」会把材料组后面的独立题误并进组，比原样导入更糟。
 *
 * 三类提示：
 * 1. 同一则材料在卷面不连续 → 会拆成多个材料组，材料重复渲染、单题分页也会多出好几页；
 * 2. 某题没填材料、却紧跟在带材料的**同科目**题后面 → 大概率是「材料只写了组首行」；
 * 3. 有题没填科目 → 会一起沉到「未分类」段，那一大段不出大类说明卡。
 */
object StructureCheck {

    fun check(questions: List<Question>): List<ImportIssue> {
        if (questions.isEmpty()) return emptyList()
        val out = ArrayList<ImportIssue>()
        val structure = PaperStructure(questions)

        checkSplitMaterial(structure, out)
        checkMaterialOnlyFirstRow(questions, out)
        checkMissingSubject(questions, out)
        return out
    }

    /** 同一材料文本被拆到多个组：按出现顺序各成一组，材料就要渲染多次 */
    private fun checkSplitMaterial(structure: PaperStructure, out: MutableList<ImportIssue>) {
        val seen = LinkedHashMap<String, MutableList<Int>>()
        structure.groups.filter { it.isMaterialGroup }.forEach { group ->
            seen.getOrPut(group.material.material.trim()) { ArrayList() } += group.startIndex + 1
        }
        val split = seen.filterValues { it.size > 1 }
        if (split.isEmpty()) return

        split.entries.take(2).forEach { (_, ordinals) ->
            out += ImportIssue(
                0,
                ImportIssue.Level.WARN,
                "同一则材料分别出现在第 ${ordinals.joinToString("、")} 题，中间隔了别的题，" +
                    "会拆成 ${ordinals.size} 个材料组（材料重复显示）；同一材料的题请排在一起"
            )
        }
        if (split.size > 2) {
            out += ImportIssue(
                0,
                ImportIssue.Level.WARN,
                "另有 ${split.size - 2} 则材料同样被拆到了卷面不同位置"
            )
        }
    }

    /**
     * 「材料只写了组首行」的典型形态：上一题有材料，本题材料为空，但两题同科目。
     * 现在的规则里本题会被当成独立题，所以只提醒，不替用户猜。
     */
    private fun checkMaterialOnlyFirstRow(questions: List<Question>, out: MutableList<ImportIssue>) {
        val hits = ArrayList<Int>()
        for (i in 1 until questions.size) {
            val prev = questions[i - 1]
            val cur = questions[i]
            if (prev.material.isNotBlank() && cur.material.isBlank() &&
                PaperStructure.sectionName(prev) == PaperStructure.sectionName(cur)
            ) {
                hits += i + 1
            }
        }
        if (hits.isEmpty()) return
        out += ImportIssue(
            0,
            ImportIssue.Level.WARN,
            "第 ${hits.take(8).joinToString("、")}${if (hits.size > 8) " 等" else ""} 题没填「材料」，" +
                "但紧跟在带材料的同科目题之后：会按独立题处理；若它们共用上一题的材料，" +
                "请把「材料」列在这些行逐行重复填写"
        )
    }

    private fun checkMissingSubject(questions: List<Question>, out: MutableList<ImportIssue>) {
        val missing = questions.count { it.subject.isBlank() }
        if (missing == 0) return
        out += ImportIssue(
            0,
            ImportIssue.Level.INFO,
            "有 $missing 题没填「科目」：这些题会并入「未分类」段，不出大类说明卡"
        )
    }
}
