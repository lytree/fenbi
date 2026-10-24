package com.lytree.qbank

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lytree.qbank.data.model.PaperStructure
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.data.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 试卷三级结构（大类题型 → 材料 → 题目）的划分规则测试。
 *
 * 口径见 `docs/08-试卷三级结构设计.md`：
 * 1. 连续相同 subject 归同一大类，`行测·资料分析` 归一成 `资料分析`；
 * 2. 大类内连续相同 material 归同一材料组，无材料的题各自成组；
 * 3. 大类边界优先于材料（同一材料跨大类断成两组）；
 * 4. 同一材料不连续出现时不并组，尊重卷面顺序；
 * 5. subject 为空归 name=null 的大类，不出说明卡。
 */
@RunWith(AndroidJUnit4::class)
class PaperStructureTest {

    private fun q(
        id: Long,
        subject: String = "",
        material: String = "",
        stem: String = "题$id"
    ) = Question(
        id = id,
        type = QuestionType.SINGLE,
        stem = stem,
        material = material,
        subject = subject
    )

    @Test
    fun divide_by_section_material_question() {
        val list = listOf(
            q(1, subject = "行测·常识判断"),
            q(2, subject = "行测·常识判断"),
            q(3, subject = "行测·资料分析", material = "材料A"),
            q(4, subject = "行测·资料分析", material = "材料A"),
            q(5, subject = "行测·资料分析", material = "材料B"),
            q(6, subject = "行测·资料分析", material = "材料B")
        )
        val s = PaperStructure(list)

        // 大类：常识判断 2 题 / 资料分析 4 题，名称去掉「行测·」前缀
        assertEquals(2, s.sections.size)
        assertEquals(listOf("常识判断", "资料分析"), s.sections.map { it.name })
        assertEquals(listOf(2, 4), s.sections.map { it.count })
        assertEquals(0, s.sections[0].startIndex)
        assertEquals(2, s.sections[1].startIndex)

        // 材料组：常识两题各成一组，资料分析两个材料各一组
        assertEquals(4, s.groups.size)
        assertEquals(listOf(1, 1, 2, 2), s.groups.map { it.count })
        assertFalse(s.groups[0].isMaterialGroup)
        assertTrue(s.groups[2].isMaterialGroup)

        // 题号 → 组 / 大类
        assertSame(s.groups[2], s.groupAt(3))
        assertSame(s.sections[1], s.sectionAt(5))
        assertEquals("资料分析", s.sectionAt(5)?.name)
        // 组首判据：材料只在组首渲染一次
        assertTrue(s.isGroupStart(2))
        assertFalse(s.isGroupStart(3))
        assertTrue(s.isSectionStart(2))
        assertFalse(s.isSectionStart(3))
        // 按题目对象找组
        assertSame(s.groups[2], s.groupOf(list[3]))
    }

    @Test
    fun same_material_across_sections_is_split() {
        val list = listOf(
            q(1, subject = "言语理解", material = "同一段材料"),
            q(2, subject = "常识判断", material = "同一段材料")
        )
        val s = PaperStructure(list)

        assertEquals(2, s.sections.size)
        assertEquals(2, s.groups.size)
        assertEquals(1, s.groups[0].count)
        assertEquals(1, s.groups[1].count)
    }

    @Test
    fun non_consecutive_same_material_keeps_paper_order() {
        val list = listOf(
            q(1, subject = "资料分析", material = "材料A"),
            q(2, subject = "资料分析", material = "材料B"),
            q(3, subject = "资料分析", material = "材料A")
        )
        val s = PaperStructure(list)

        assertEquals(3, s.groups.size)
        // 题号仍然连续，组按出现位置切
        assertEquals(listOf(0, 1, 2), s.groups.map { it.startIndex })
        assertEquals(listOf(1, 1, 1), s.groups.map { it.count })
    }

    @Test
    fun blank_subject_gives_unnamed_section() {
        val list = listOf(
            q(1, material = "材料A"),
            q(2, material = "材料A"),
            q(3)
        )
        val s = PaperStructure(list)

        assertEquals(1, s.sections.size)
        assertNull(s.sections[0].name)
        assertEquals(3, s.sections[0].count)
        // 材料组 2 题 + 无材料单题 1 组
        assertEquals(2, s.groups.size)
        assertEquals(2, s.groups[0].count)
        assertTrue(s.groups[1].startIndex == 2)
    }

    @Test
    fun empty_paper_has_no_sections() {
        val s = PaperStructure(emptyList())
        assertEquals(0, s.size)
        assertTrue(s.sections.isEmpty())
        assertTrue(s.groups.isEmpty())
        assertNull(s.groupAt(0))
        assertNull(s.sectionAt(0))
    }
}
