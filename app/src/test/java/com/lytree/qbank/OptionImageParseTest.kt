package com.lytree.qbank

import com.lytree.qbank.data.model.Option
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.ui.MaterialRenderer
import com.lytree.qbank.ui.QuestionCardBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选项图文拆分。
 *
 * 图形推理的选项往往直接就是四张图，选项单元格里写的是图片地址；
 * 这里守住三条：纯文字不动、图片按出现顺序取出来、正文里不残留 `![](...)` 这种标记。
 */
class OptionImageParseTest {

    @Test
    fun `纯文字选项原样保留`() {
        val c = MaterialRenderer.splitOption("甲、乙两地相距 120 公里")
        assertEquals("甲、乙两地相距 120 公里", c.text)
        assertFalse(c.hasImages)
    }

    @Test
    fun `单张图片的选项没有正文`() {
        val c = MaterialRenderer.splitOption("![A项](/sdcard/试卷/a.png)")
        assertEquals("", c.text)
        assertEquals(1, c.images.size)
        assertEquals("/sdcard/试卷/a.png", c.images[0].src)
        assertEquals("A项", c.images[0].alt)
    }

    @Test
    fun `文字加图片：文字留着、图片按顺序`() {
        val c = MaterialRenderer.splitOption("见下图\n![图1](/tmp/1.png)\n![图2](/tmp/2.png)")
        assertEquals("见下图", c.text)
        assertEquals(listOf("/tmp/1.png", "/tmp/2.png"), c.images.map { it.src })
    }

    @Test
    fun `方括号图片写法也认`() {
        val c = MaterialRenderer.splitOption("[图片](/tmp/b.png)")
        assertEquals("", c.text)
        assertEquals("/tmp/b.png", c.images[0].src)
    }

    @Test
    fun `整格就是一个图片路径`() {
        val c = MaterialRenderer.splitOption("/sdcard/试卷/c.png")
        assertEquals("", c.text)
        assertEquals("/sdcard/试卷/c.png", c.images[0].src)
    }

    @Test
    fun `普通括号里的内容不当图片`() {
        val c = MaterialRenderer.splitOption("（见图）比重约为 3.2%")
        assertEquals("（见图）比重约为 3.2%", c.text)
        assertFalse(c.hasImages)
        // 只有一个名字（没有路径分隔符、没有扩展名的绝对地址）也不猜
        val d = MaterialRenderer.splitOption("增长 5.8%")
        assertFalse(d.hasImages)
    }

    @Test
    fun `摘要把图片收成图片二字`() {
        assertEquals("见下图 ［图片］", MaterialRenderer.optionSummary("见下图\n![图](/tmp/1.png)"))
        assertEquals("增长 5.8%", MaterialRenderer.optionSummary("增长 5.8%"))
        assertTrue(MaterialRenderer.splitOption("![x](data:image/png;base64,AAAA)").hasImages)
    }

    // ---------------- 版式分流：什么时候能横排一行 ----------------

    private fun q(vararg texts: String) = Question(
        options = texts.mapIndexed { i, t -> Option(('A' + i).toString(), t) }
    )

    @Test
    fun `四个只带图的选项走横排`() {
        assertTrue(
            QuestionCardBinder.isImageOnlyOptions(
                q(
                    "![A](/tmp/a.png)", "![B](/tmp/b.png)",
                    "![C](/tmp/c.png)", "![D](/tmp/d.png)"
                )
            )
        )
        // 两个、三个同理；整格一条路径也算纯图
        assertTrue(QuestionCardBinder.isImageOnlyOptions(q("/tmp/a.png", "/tmp/b.png")))
        assertTrue(
            QuestionCardBinder.isImageOnlyOptions(
                q("![A](/a.png)", "![B](/b.png)", "![C](/c.png)")
            )
        )
    }

    @Test
    fun `带文字或图太多仍走单列`() {
        // 文字 + 图：图会被压成小缩略图，横排反而读不出字
        assertFalse(QuestionCardBinder.isImageOnlyOptions(q("![A](/a.png)", "见下图 ![B](/b.png)")))
        // 选项不是图
        assertFalse(QuestionCardBinder.isImageOnlyOptions(q("甲", "乙", "丙", "丁")))
        // 一格里两张图：横排放不下
        assertFalse(QuestionCardBinder.isImageOnlyOptions(q("![A1](/a1.png)![A2](/a2.png)", "![B](/b.png)")))
        // 超过 4 项一行就挤成缩略图墙了
        assertFalse(
            QuestionCardBinder.isImageOnlyOptions(
                q("![A](/a.png)", "![B](/b.png)", "![C](/c.png)", "![D](/d.png)", "![E](/e.png)")
            )
        )
    }
}
