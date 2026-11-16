package com.lytree.qbank

import com.lytree.qbank.ui.recite.MindMapLanePacker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多根导图的分栏：**一页十几个中心主题时，别把图排成一条竖线**。
 *
 * 钉住三件事：
 * 1. 切栏按根的顺序切，覆盖全部根、不重不漏、每栏至少一根（否则绘制会漏掉整棵子树）；
 * 2. 栏数按画布比例挑：竖屏（要窄高）分得少、横屏（要宽扁）分得多，只有一两个根时不瞎分；
 * 3. 多分一栏换来的宽高比改善不够时，回到栏数少的那个（宁可少切，图更整）。
 */
class MindMapLanePackerTest {

    private fun heights(count: Int, each: Float) = FloatArray(count) { each }

    private fun laneWidth(range: IntRange): Float = 300f

    @Test
    fun `一个根不切栏`() {
        val ranges = MindMapLanePacker.split(heights(1, 500f), lanes = 4, gap = 18f)
        assertEquals(listOf(0..0), ranges)
    }

    @Test
    fun `没有根时返回空`() {
        assertTrue(MindMapLanePacker.split(FloatArray(0), lanes = 3, gap = 18f).isEmpty())
    }

    @Test
    fun `切栏覆盖全部根且每栏非空`() {
        val ranges = MindMapLanePacker.split(heights(19, 200f), lanes = 4, gap = 18f)
        assertTrue(ranges.size in 2..4)
        // 连续、从 0 开始、到最后一个结束
        assertEquals(0, ranges.first().first)
        assertEquals(18, ranges.last().last)
        ranges.zipWithNext { a, b -> assertEquals(a.last + 1, b.first) }
        // 每栏至少一根
        assertTrue(ranges.all { it.last >= it.first })
        // 等高：最长与最短差不超过一个根
        val sizes = ranges.map { it.last - it.first + 1 }
        assertTrue(sizes.max() - sizes.min() <= 1)
    }

    @Test
    fun `根数少于栏数时一栏一根`() {
        val ranges = MindMapLanePacker.split(heights(3, 200f), lanes = 8, gap = 18f)
        assertEquals(listOf(0..0, 1..1, 2..2), ranges)
    }

    @Test
    fun `按顺序切而不是按高度重排`() {
        // 中间那个特别高：切出来仍要按 0,1,2... 的顺序，高的那个自己占一段
        val ranges = MindMapLanePacker.split(floatArrayOf(100f, 900f, 100f, 100f), lanes = 2, gap = 0f)
        assertEquals(listOf(0..1, 2..3), ranges)
    }

    @Test
    fun `横屏比竖屏分得多`() {
        val hs = heights(19, 200f)
        fun pick(ratio: Float) = MindMapLanePacker.pickLaneCount(
            heights = hs,
            maxLanes = 6,
            gap = 18f,
            laneGap = 24f,
            targetRatio = ratio,
            laneWidth = ::laneWidth,
        )
        // 竖屏（高/宽 ≈ 2）要窄高的图，横屏（高/宽 ≈ 0.8）要宽扁的图，所以横屏反而分得多
        val portrait = pick(2f)
        val landscape = pick(0.8f)
        assertTrue("竖屏也该分栏（实际 $portrait）", portrait >= 2)
        assertTrue("横屏应该分得更多（$landscape vs $portrait）", landscape > portrait)
    }

    @Test
    fun `宽块横排时不分栏`() {
        // 每个根都很宽：再分栏只会让整幅图更宽，fit 之后更小
        val lanes = MindMapLanePacker.pickLaneCount(
            heights = heights(3, 100f),
            maxLanes = 6,
            gap = 18f,
            laneGap = 24f,
            targetRatio = 2f,
            laneWidth = { 1000f },
        )
        assertEquals(1, lanes)
    }

    @Test
    fun `挑出来的栏数确实是打分最低的`() {
        val hs = heights(19, 200f)
        fun scoreOf(lanes: Int): Float {
            var width = 0f
            var height = 0f
            MindMapLanePacker.split(hs, lanes, 18f).forEachIndexed { index, range ->
                if (index > 0) width += 24f
                width += laneWidth(range)
                var h = -18f
                for (i in range) h += 200f + 18f
                height = maxOf(height, h)
            }
            return maxOf(width, height / 2f)
        }
        val best = MindMapLanePacker.pickLaneCount(
            heights = hs,
            maxLanes = 6,
            gap = 18f,
            laneGap = 24f,
            targetRatio = 2f,
            laneWidth = ::laneWidth,
        )
        val bestScore = scoreOf(best)
        for (lanes in 1..6) {
            assertTrue(
                "第 $lanes 栏得分 ${scoreOf(lanes)} 不该优于选中的 $best（$bestScore）",
                scoreOf(lanes) >= bestScore - 0.5f,
            )
        }
    }
}
