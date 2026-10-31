package com.lytree.qbank

import com.lytree.qbank.ui.MaterialRenderer
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
}
