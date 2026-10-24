package com.lytree.qbank.ui

import android.content.Context
import com.lytree.qbank.R
import com.lytree.qbank.data.model.Question

/**
 * 模块说明卡的取数逻辑。
 *
 * 参考应用的模考卷是「模块 → 若干题」的卷面结构：资料分析 (20题) / 常识判断 (20题) …
 * 每个模块在卷面上先出一张浅蓝说明卡，再往下排题。本项目的题库模型里没有独立的模块表，
 * 所以按题目的 `subject`（科目）字段横向聚合：
 *
 * - 连续的相同 subject 视为同一模块；
 * - 只有 **该模块首题** 返回非空，后面同模块的题都返回 null（卷面上只出现一次）；
 * - subject 为空（大多数手打题库）时不渲染模块卡，避免给普通题库硬加一个空标题。
 */
object ModuleHeaders {

    /**
     * 该位置是否是一个模块的「起点」（首题，且 subject 非空）。
     * 单题浏览靠它决定在哪里插入一页独立的模块说明页。
     */
    fun isModuleStart(questions: List<Question>, position: Int): Boolean {
        if (position !in questions.indices) return false
        val name = moduleName(questions[position]) ?: return false
        return position == 0 || moduleName(questions[position - 1]) != name
    }

    /** 该位置的题是否要带模块说明卡；不需要返回 null */
    fun headerAt(context: Context, questions: List<Question>, position: Int): QuestionCardBinder.ModuleHeader? {
        if (!isModuleStart(questions, position)) return null
        val name = moduleName(questions[position]) ?: return null

        var count = 1
        var i = position + 1
        while (i < questions.size && moduleName(questions[i]) == name) {
            count++
            i++
        }
        val title = context.getString(R.string.module_title_fmt, name, count)
        return QuestionCardBinder.ModuleHeader(title = title, desc = descOf(context, name))
    }

    /** 「行测·资料分析」→「资料分析」；「资料分析」原样返回；空串返回 null */
    fun moduleName(q: Question): String? {
        val raw = q.subject.trim()
        if (raw.isEmpty()) return null
        val name = raw
            .substringAfterLast('·')
            .substringAfterLast('-')
            .substringAfterLast('/')
            .trim()
        return name.ifEmpty { raw }
    }

    /** 模块说明文案：优先按「资料分析 / 数量 / 言语 / 判断 / 常识」归类，认不出就不写说明 */
    private fun descOf(context: Context, name: String): String = when {
        // 「常识判断」也带「判断」二字，必须先命中常识，否则会被判断推理那条抢走
        name.contains("常识") -> context.getString(R.string.module_desc_common_sense)
        name.contains("资料") -> context.getString(R.string.module_desc_data_analysis)
        name.contains("数量") || name.contains("数学") -> context.getString(R.string.module_desc_quantitative)
        name.contains("言语") || name.contains("表达") -> context.getString(R.string.module_desc_verbal)
        name.contains("判断") || name.contains("推理") -> context.getString(R.string.module_desc_judgement)
        else -> ""
    }
}
