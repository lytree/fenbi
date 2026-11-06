package com.lytree.qbank

import com.lytree.qbank.ui.OptionCross
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选项打叉（长按划掉）的状态存储。
 *
 * 这里钉住三件事：长按是**切换**而不是单向标记；同一题的多项各自独立；
 * 状态按题 id 隔离（主答题页 / 材料组页 / 分屏页共用一份，不会串题）。
 */
class OptionCrossTest {

    @Test
    fun `长按一次划掉 再长按恢复`() {
        val qid = 9_000_001L
        assertFalse(OptionCross.isCrossed(qid, "B"))

        assertTrue(OptionCross.toggle(qid, "B"))
        assertTrue(OptionCross.isCrossed(qid, "B"))
        assertEquals(setOf("B"), OptionCross.keys(qid))

        assertFalse(OptionCross.toggle(qid, "B"))
        assertFalse(OptionCross.isCrossed(qid, "B"))
        assertTrue(OptionCross.keys(qid).isEmpty())
    }

    @Test
    fun `同一题多选项各自独立`() {
        val qid = 9_000_002L
        OptionCross.toggle(qid, "A")
        OptionCross.toggle(qid, "C")
        assertEquals(setOf("A", "C"), OptionCross.keys(qid))

        OptionCross.toggle(qid, "A")
        assertEquals(setOf("C"), OptionCross.keys(qid))
    }

    @Test
    fun `题目之间互不影响`() {
        val a = 9_000_003L
        val b = 9_000_004L
        OptionCross.toggle(a, "D")
        assertTrue(OptionCross.isCrossed(a, "D"))
        assertFalse(OptionCross.isCrossed(b, "D"))
        assertTrue(OptionCross.keys(b).isEmpty())
    }

    @Test
    fun `小写字母归到大写键`() {
        val qid = 9_000_005L
        OptionCross.toggle(qid, "b")
        assertTrue(OptionCross.isCrossed(qid, "B"))
        assertTrue(OptionCross.isCrossed(qid, "b"))
        assertEquals(setOf("B"), OptionCross.keys(qid))
    }
}
