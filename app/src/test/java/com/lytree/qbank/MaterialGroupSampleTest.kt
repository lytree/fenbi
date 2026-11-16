package com.lytree.qbank

import com.lytree.qbank.data.importer.ImportFormat
import com.lytree.qbank.data.importer.Importer
import com.lytree.qbank.data.model.PaperStructure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置示例「材料题示例.xlsx」：一则材料带多道小题，**多组**。
 *
 * 「弹出材料」现在是一页一题、左右滑动切换，一组的题量直接决定页码与翻页边界，
 * 所以示例数据本身要钉住：4 组材料、题量 4/5/2/2、其中两组同属「言语理解」但材料不同
 * （验证「同一大类里材料一变就断开成两组」），还要有一组带 Markdown 表格
 * （浮层上段能不能滚表格，就靠它试）。
 *
 * 数据源是仓库根的 `sample/材料题示例.json`，`tools/make_samples.js` 生成到
 * `app/src/main/assets/samples/`；改示例而不重跑脚本，这个测试会先炸。
 */
class MaterialGroupSampleTest {

    private fun sample(name: String): File =
        listOf(File("src/main/assets/samples/$name"), File("app/src/main/assets/samples/$name"))
            .firstOrNull { it.exists() }
            ?: error("找不到示例 $name（当前工作目录 ${File(".").absolutePath}）")

    @Test
    fun `材料题示例拆成四组一材多题`() {
        val f = sample("材料题示例.xlsx")
        val out = Importer.buildPapers(ImportFormat.XLSX, f.readBytes(), f.name)

        assertEquals("示例只有一张工作表 = 一份卷", 1, out.papers.size)
        val questions = out.papers[0].questions
        assertEquals(13, questions.size)

        val groups = PaperStructure(questions).groups.filter { it.isMaterialGroup }
        assertEquals("四组材料各自成组", 4, groups.size)
        assertEquals("组题量 4 / 5 / 2 / 2", listOf(4, 5, 2, 2), groups.map { it.count })

        // 组内每行都要重复填同一段材料，只写组首行会让后面的题变成「不带材料的独立题」
        assertTrue("每组至少两道小题", groups.all { it.count >= 2 })

        // 浮层上半段要能滚表格：至少一组材料带 Markdown 表格
        assertTrue(
            "应有一组带表格材料",
            groups.any { it.material.material.contains("| --- |") }
        )

        // 同科目不同材料也要断开：言语理解那一大类下正好两组
        assertEquals(
            "言语理解下两组材料",
            2,
            groups.count { it.material.subject == "行测·言语理解" }
        )

        // 每组题号在整卷里连续（页码 x/y 与卡片左上角题号都靠这个前提）
        groups.forEach { g ->
            val fromPaper = questions.subList(g.startIndex, g.endIndex).map { it.id }
            assertEquals("组内题号连续", fromPaper, g.questions.map { it.id })
        }
    }
}
