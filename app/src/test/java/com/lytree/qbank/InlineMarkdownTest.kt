package com.lytree.qbank

import com.lytree.qbank.ui.InlineMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内 Markdown 的切段规则：背诵正文、材料段落、表格单元格都走它。
 *
 * 这里钉三件事：成对的标记真的被切出来、落单的标记原样保留（正文里的乘号 / 引号不能丢）、
 * 挖空占位符会单独成段（背诵回填答案靠它）。
 */
class InlineMarkdownTest {

    private fun texts(input: String) = InlineMarkdown.runs(input).map { it.text }

    @Test
    fun `成对的加粗被切成单独一段`() {
        val runs = InlineMarkdown.runs("坚持**两个结合**不动摇")
        assertEquals(listOf("坚持", "两个结合", "不动摇"), runs.map { it.text })
        assertTrue(runs[1].bold)
        assertEquals(false, runs[0].bold)
    }

    @Test
    fun `行内代码被切成单独一段`() {
        val runs = InlineMarkdown.runs("参数用 `size=full` 控制")
        assertEquals(listOf("参数用 ", "size=full", " 控制"), runs.map { it.text })
        assertTrue(runs[1].code)
    }

    @Test
    fun `落单的标记原样保留`() {
        assertEquals(listOf("3 ** 4 = 12"), texts("3 ** 4 = 12"))
        assertEquals(listOf("a`b"), texts("a`b"))
    }

    @Test
    fun `挖空占位符单独成段`() {
        val runs = InlineMarkdown.runs("宪法是${InlineMarkdown.TOKEN}年颁布的")
        assertEquals(3, runs.size)
        assertTrue(runs[1].token)
        assertEquals("", runs[1].text)
    }

    @Test
    fun `粗体与挖空交叉时都不丢`() {
        val runs = InlineMarkdown.runs("**现行宪法是${InlineMarkdown.TOKEN}年**")
        assertEquals(listOf("现行宪法是", "", "年"), runs.map { it.text })
        assertTrue(runs[0].bold)
        assertTrue(runs[1].token)
        assertTrue(runs[2].bold)
    }
}
