package com.lytree.qbank

import com.lytree.qbank.ui.MaterialRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题干里的表格。
 *
 * 题干平时是用 TextView 排的（字号缩放、行距、笔迹锚点都靠它），只有带表格时才整块
 * 交给 MaterialRenderer.renderStemRich——所以「哪段题干算带表格」这个判断是分叉点：
 * 判错一次，要么 `|` 原样露出成一行竖线，要么所有题干都被迫走重路径。
 */
class MaterialStemTableTest {

    private fun tablesOf(stem: String) =
        MaterialRenderer.parse(stem).filterIsInstance<MaterialRenderer.Block.Table>()

    @Test
    fun `Markdown 表格能认出来`() {
        val stem = """
            根据下表回答问题：

            | 年份 | 收入 |
            | --- | --- |
            | 2024 | 5280 |
            | 2025 | 6119 |
        """.trimIndent()
        assertTrue(MaterialRenderer.stemHasTable(stem))
        val table = tablesOf(stem).single()
        assertEquals(1, table.headerRows)
        assertEquals(3, table.rows.size)
        assertEquals(listOf("2024", "5280"), table.rows[1])
    }

    @Test
    fun `制表符对齐的裸表格也算`() {
        val stem = "项目\t数值\n总收入\t5280\n研发费用\t611.9"
        assertTrue(MaterialRenderer.stemHasTable(stem))
        assertEquals(3, tablesOf(stem).single().rows.size)
    }

    @Test
    fun `纯文字题干不走表格路径`() {
        assertFalse(MaterialRenderer.stemHasTable("2025年1-11月，B市研发费用中工业企业占比同比约："))
        // 只有一条竖线、凑不成表格的，也不算（否则整段会被当成表格吞掉）
        assertFalse(MaterialRenderer.stemHasTable("比较 A | B 两种说法"))
    }

    @Test
    fun `带表格的题干里，表格前后的文字都还在`() {
        val stem = """
            下列数据见下表：

            | 年份 | 收入 |
            | --- | --- |
            | 2024 | 5280 |

            则 2024 年的收入约为：
        """.trimIndent()
        val blocks = MaterialRenderer.parse(stem)
        assertEquals(3, blocks.size)
        assertTrue((blocks[0] as MaterialRenderer.Block.Paragraph).text.startsWith("下列数据见下表"))
        assertTrue(blocks[1] is MaterialRenderer.Block.Table)
        assertTrue((blocks[2] as MaterialRenderer.Block.Paragraph).text.contains("2024 年的收入"))
    }

    @Test
    fun `题干里的图片与表格混排，顺序不变`() {
        val stem = "看图：\n\n![图1](/sdcard/a.png)\n\n| 年份 | 收入 |\n| --- | --- |\n| 2024 | 5280 |"
        val blocks = MaterialRenderer.parse(stem)
        assertTrue(blocks[1] is MaterialRenderer.Block.Image)
        assertTrue(blocks[2] is MaterialRenderer.Block.Table)
    }
}
