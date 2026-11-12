package com.lytree.qbank

import com.lytree.qbank.data.model.PaperRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 卷 id 约定：正数 = 一份卷，负数 = 一个导入组（整组作答的虚拟卷） */
class PaperRefTest {

    @Test
    fun `组 id 与虚拟卷 id 互为相反数`() {
        assertEquals(-12L, PaperRef.groupPaperId(12L))
        assertEquals(12L, PaperRef.setIdOf(-12L))
    }

    @Test
    fun `无效组 id 不产生虚拟卷`() {
        assertEquals(0L, PaperRef.groupPaperId(0L))
        assertEquals(0L, PaperRef.groupPaperId(-3L))
    }

    @Test
    fun `正数是单卷 负数是组`() {
        assertFalse(PaperRef.isGroup(7L))
        assertTrue(PaperRef.isGroup(-7L))
    }

    @Test
    fun `0 是无效 id`() {
        assertFalse(PaperRef.isValid(0L))
        assertTrue(PaperRef.isValid(7L))
        assertTrue(PaperRef.isValid(-7L))
    }
}
