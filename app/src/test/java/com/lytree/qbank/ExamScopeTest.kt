package com.lytree.qbank

import com.lytree.qbank.data.model.ExamScope
import com.lytree.qbank.data.model.PaperStructure
import com.lytree.qbank.data.model.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分模块答题的范围切分：**每个层级都能当成一张小卷**。
 *
 * 钉住三件事：
 * 1. 整卷 / 大类 / 材料 切出来的题目与题量；
 * 2. 范围的 key 能原样还原（记录与未交卷进度就靠它分桶，还原不出来等于「续做串到别的模块」）；
 * 3. 结构变了（锚点题被删、卷子重新导入）时逐级退回，不会给出一张空卷。
 */
class ExamScopeTest {

    private fun q(id: Long, subject: String, material: String = "") = Question(
        id = id,
        stem = "第${id}题的题干（够长，便于匹配）",
        subject = subject,
        material = material
    )

    /**
     * 一份两模块的卷：
     * ```
     * 资料分析   材料甲 ×1 / 材料乙 ×2      （题 1..3）
     * 言语理解   无材料                      （题 4..5）
     * ```
     */
    private fun structure() = PaperStructure(
        listOf(
            q(1, "行测·资料分析", "材料甲"),
            q(2, "行测·资料分析", "材料乙"),
            q(3, "行测·资料分析", "材料乙"),
            q(4, "行测·言语理解"),
            q(5, "行测·言语理解")
        )
    )

    private fun ids(questions: List<Question>) = questions.map { it.id }

    @Test
    fun `整卷范围给回全部题目`() {
        val s = structure()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(ExamScope.FULL.resolve(s)))
        assertEquals("整卷的 key 是空串（老记录 / 老进度都按它存）", "", ExamScope.FULL.key)
        assertEquals("整卷没有范围标题", "", ExamScope.FULL.label(s))
    }

    @Test
    fun `大类范围只包含这一类的题`() {
        val s = structure()
        val section = s.sections.first { it.name == "资料分析" }
        val scope = ExamScope.ofSection(section)

        assertEquals(listOf(1L, 2L, 3L), ids(scope.resolve(s)))
        assertEquals("资料分析", scope.label(s))
        assertEquals("s:1", scope.key)
        assertEquals("名字归一后的大类名就是记录里的标题", "资料分析", section.name)
    }

    @Test
    fun `材料范围只包含这一则材料带的题`() {
        val s = structure()
        val second = s.groups.filter { it.isMaterialGroup }[1]
        val scope = ExamScope.ofMaterial(second)

        assertEquals(listOf(2L, 3L), ids(scope.resolve(s)))
        assertEquals("材料序号在同一大类内数", "资料分析 · 材料2", scope.label(s))
        assertEquals("m:2", scope.key)
    }

    @Test
    fun `没有材料的组不单独成卷`() {
        val s = structure()
        val single = s.groups.first { !it.isMaterialGroup }
        assertEquals("单题组退回整卷", ExamScope.FULL, ExamScope.ofMaterial(single))
    }

    @Test
    fun `key 往返后切出来的题一致`() {
        val s = structure()
        listOf(
            ExamScope.FULL,
            ExamScope.ofSection(s.sections[0]),
            ExamScope.ofSection(s.sections[1]),
            ExamScope.ofMaterial(s.groups.filter { it.isMaterialGroup }[0]),
            ExamScope.ofMaterial(s.groups.filter { it.isMaterialGroup }[1])
        ).forEach { scope ->
            val restored = ExamScope.fromKey(scope.key)
            assertEquals("key=${scope.key} 应能还原", ids(scope.resolve(s)), ids(restored.resolve(s)))
            assertEquals("范围标题也要一致", scope.label(s), restored.label(s))
        }
    }

    @Test
    fun `锚点题不存在时退回整卷而不是空卷`() {
        val s = structure()
        val scope = ExamScope.fromKey("s:9999")
        assertEquals(5, scope.resolve(s).size)
        assertFalse("整卷不该被当成空卷", scope.resolve(s).isEmpty())
    }

    @Test
    fun `认不出的 key 一律当整卷`() {
        assertTrue(ExamScope.fromKey("").isWholePaper)
        assertTrue(ExamScope.fromKey(null).isWholePaper)
        assertTrue(ExamScope.fromKey("乱七八糟").isWholePaper)
        assertTrue(ExamScope.fromKey("s:abc").isWholePaper)
    }

    @Test
    fun `没填科目的大类也能分模块做`() {
        val s = PaperStructure(listOf(q(7, ""), q(8, ""), q(9, "资料分析")))
        val unnamed = s.sections.first { it.name == null }
        val scope = ExamScope.ofSection(unnamed)

        assertEquals(listOf(7L, 8L), ids(scope.resolve(s)))
        assertEquals("s:7", scope.key)
        assertEquals("没有大类名时给个兜底标题", "本模块", scope.label(s))
    }
}
