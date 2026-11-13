package com.lytree.qbank

import com.lytree.qbank.ui.MaterialRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图文排版块（`:::figure`）解析。
 *
 * 一段材料里图和字怎么摆，App（MaterialRenderer）和网页预览（markdown.ts）必须同一套口径：
 * 布局关键字、宽度比例、尺寸标记三样都要认，认不出来时退回默认排版而不是把内容吞掉。
 */
class MaterialFigureTest {

    private fun figureOf(material: String) =
        MaterialRenderer.parse(material).filterIsInstance<MaterialRenderer.Block.Figure>().firstOrNull()

    private fun imageOf(material: String) =
        MaterialRenderer.parse(material).filterIsInstance<MaterialRenderer.Block.Image>().firstOrNull()

    @Test
    fun `默认图文块：图在上、文字在下`() {
        val f = figureOf(
            """
            :::figure
            ![图1](/sdcard/试卷/a.png)
            这段文字和图排在一起
            :::
            """.trimIndent()
        )
        assertNotNull(f)
        assertEquals(MaterialRenderer.FigureLayout.TOP, f!!.layout)
        assertEquals(1, f.images.size)
        assertEquals("/sdcard/试卷/a.png", f.images[0].src)
        assertEquals("这段文字和图排在一起", f.text)
    }

    @Test
    fun `布局关键字：左右上下并排都认`() {
        fun layoutOf(layout: String) = figureOf(
            """
            :::figure
            layout: $layout
            ![图1](/a.png)
            说明
            :::
            """.trimIndent()
        )?.layout

        assertEquals(MaterialRenderer.FigureLayout.LEFT, layoutOf("left"))
        assertEquals(MaterialRenderer.FigureLayout.RIGHT, layoutOf("right"))
        assertEquals(MaterialRenderer.FigureLayout.TOP, layoutOf("top"))
        assertEquals(MaterialRenderer.FigureLayout.BOTTOM, layoutOf("bottom"))
        assertEquals(MaterialRenderer.FigureLayout.ROW, layoutOf("row"))
        // 中文说法也认
        assertEquals(MaterialRenderer.FigureLayout.LEFT, layoutOf("左图右文"))
        assertEquals(MaterialRenderer.FigureLayout.RIGHT, layoutOf("右图左文"))
        assertEquals(MaterialRenderer.FigureLayout.ROW, layoutOf("并排"))
    }

    @Test
    fun `围栏后面直接写布局`() {
        val f = figureOf(
            """
            :::figure right
            ![图1](/a.png)
            说明
            :::
            """.trimIndent()
        )
        assertEquals(MaterialRenderer.FigureLayout.RIGHT, f?.layout)
    }

    @Test
    fun `宽度比例三种写法都能算出来`() {
        fun ratioOf(width: String) = figureOf(
            """
            :::figure
            width: $width
            ![图1](/a.png)
            :::
            """.trimIndent()
        )?.widthRatio

        assertEquals(0.38f, ratioOf("38%")!!, 0.001f)
        assertEquals(0.45f, ratioOf("45%")!!, 0.001f)
        assertEquals(0.4f, ratioOf("0.4")!!, 0.001f)
        // 太宽 / 太窄都要夹住：宽了文字只剩一条缝，窄了图看不清
        assertEquals(0.75f, ratioOf("90%")!!, 0.001f)
        assertEquals(0.15f, ratioOf("5%")!!, 0.001f)
    }

    @Test
    fun `尺寸标记：块级和行尾都认`() {
        val f = figureOf(
            """
            :::figure
            size: full
            ![图1](/a.png)
            :::
            """.trimIndent()
        )
        assertEquals(MaterialRenderer.ImageSize.FULL, f?.size)

        // 单张图行尾挂 {size=full}：这一张铺满宽度
        val img = imageOf("![图1](/a.png){size=full}")
        assertEquals(MaterialRenderer.ImageSize.FULL, img?.image?.size)
        assertEquals("/a.png", img?.image?.src)

        // 不写就是按原图尺寸
        assertEquals(MaterialRenderer.ImageSize.NATURAL, imageOf("![图1](/a.png)")?.image?.size)
    }

    @Test
    fun `正文里的冒号不会被当成指令`() {
        val f = figureOf(
            """
            :::figure
            layout: left
            2019年：全年增速 5.8%
            ![图1](/a.png)
            :::
            """.trimIndent()
        )
        assertEquals(MaterialRenderer.FigureLayout.LEFT, f?.layout)
        assertTrue(f!!.text.contains("2019年：全年增速 5.8%"))
    }

    @Test
    fun `多张图按出现顺序取出来`() {
        val f = figureOf(
            """
            :::figure
            layout: row
            ![图1](/1.png)
            ![图2](/2.png)
            ![图3](/3.png)
            ![图4](/4.png)
            :::
            """.trimIndent()
        )
        assertEquals(MaterialRenderer.FigureLayout.ROW, f?.layout)
        assertEquals(listOf("/1.png", "/2.png", "/3.png", "/4.png"), f?.images?.map { it.src })
    }

    @Test
    fun `没有图片时退回普通段落，内容不丢`() {
        val blocks = MaterialRenderer.parse(
            """
            :::figure
            layout: left
            只有文字，没有图
            :::
            """.trimIndent()
        )
        val fig = blocks.filterIsInstance<MaterialRenderer.Block.Figure>().firstOrNull()
        assertNotNull(fig)
        assertTrue(fig!!.images.isEmpty())
        assertEquals("只有文字，没有图", fig.text)
    }
}
