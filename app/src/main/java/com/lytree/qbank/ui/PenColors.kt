package com.lytree.qbank.ui

import android.content.Context

/**
 * 手写笔色板。
 *
 * 颜色只存一处：`ink_prefs / pen_color`，**只在设置页「手写标注 → 笔迹颜色」里改**。
 * 写画工具条不带换色按钮 —— 答题时手忙脚乱地点色点容易误触，颜色定好一次就够了。
 *
 * 每笔笔迹自己带颜色（[InkOverlay] 里按笔存），所以换色不影响已经写上去的圈画——
 * 红笔划的关键词不会因为你后来换成蓝笔而变色。
 */
object PenColors {

    const val PREFS = "ink_prefs"
    private const val KEY_COLOR = "pen_color"

    /** 默认红笔：卷面是白底，红色最像老师批注 */
    const val DEFAULT: Int = 0xFFF04C4C.toInt()

    class Item(val label: String, val color: Int)

    /** 五色够用：红（批注）·蓝（笔记）·绿（对）·橙（重点）·黑（算式） */
    val items: List<Item> = listOf(
        Item("红", 0xFFF04C4C.toInt()),
        Item("蓝", 0xFF3C7CFC.toInt()),
        Item("绿", 0xFF12A05B.toInt()),
        Item("橙", 0xFFF5A300.toInt()),
        Item("黑", 0xFF1F2329.toInt())
    )

    fun load(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_COLOR, DEFAULT)

    fun save(context: Context, color: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COLOR, color).apply()
    }

    fun labelOf(color: Int): String =
        items.firstOrNull { it.color == color }?.label ?: "自定义"
}
