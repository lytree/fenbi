package com.lytree.qbank.ui

import android.content.Context
import com.lytree.qbank.R
import com.lytree.qbank.data.model.PaperStructure

/**
 * 大类说明卡的取数逻辑。
 *
 * 参考应用的模考卷是「大类题型 → 材料 → 题目」的卷面结构：资料分析 (20题) / 常识判断 (20题) …
 * 每个大类在卷面上先出一张浅蓝说明卡，再往下排题。
 *
 * **划分**（哪些题属于同一个大类、从第几题开始、共几题）由 [PaperStructure] 统一算好，
 * 这里只负责「把结构翻译成一张卡」：标题 `模块名 (N题)` + 该模块的作答说明文案。
 *
 * 说明卡只在大类**首题**出现一次；`subject` 为空的大类（[PaperStructure.Section.name] 为 null）
 * 不出卡——手打题库常见单一题型，硬加一个空标题只会碍眼。
 */
object ModuleHeaders {

    /** 大类首题的说明卡；不需要出卡时返回 null */
    fun headerOf(context: Context, section: PaperStructure.Section): QuestionCardBinder.ModuleHeader? {
        val name = section.name ?: return null
        val title = context.getString(R.string.module_title_fmt, name, section.count)
        return QuestionCardBinder.ModuleHeader(title = title, desc = descOf(context, name))
    }

    /** 说明文案：优先按「资料分析 / 数量 / 言语 / 判断 / 常识」归类，认不出就不写说明 */
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
