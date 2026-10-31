package com.lytree.qbank

import com.lytree.qbank.data.importer.ImportFormat
import com.lytree.qbank.data.importer.Importer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 仓库里 `templates/` 下的模板必须**真能被导入器吃下去**。
 *
 * `make_templates.js` 自带一轮回读校验，但它只核对 xlsx 结构，跑不到 App 的解析链路；
 * 这里直接用真模板走一遍 [Importer]，钉住「一份卷 5 题」「包内图片换成本机路径」。
 * 模板里改了表名、加删说明行、换列顺序，都会在这里立刻炸出来。
 */
class TemplateImportTest {

    private fun template(name: String): File =
        listOf(File("../templates/$name"), File("templates/$name"))
            .firstOrNull { it.exists() }
            ?: error("找不到模板 $name（当前工作目录 ${File(".").absolutePath}）")

    @Test
    fun `试卷模板导入成一份卷 5 道题`() {
        val f = template("试卷模板.xlsx")
        val out = Importer.buildPapers(ImportFormat.XLSX, f.readBytes(), f.name)

        assertEquals("模板应只有「试卷」表出题，「使用说明」不建卷", 1, out.papers.size)
        assertEquals(5, out.papers[0].questions.size)
        // 说明表被跳过，原因要落到提示里，不能悄悄消失
        assertTrue(
            "说明表应作为提示列出",
            out.issues.any { it.message.contains("使用说明") }
        )
    }

    @Test
    fun `试卷包模板导入成一份卷 且图片换成绝对路径`() {
        val f = template("试卷包模板.zip")
        val dir = File("build/tmp/template_pkg").apply { deleteRecursively(); mkdirs() }
        val out = Importer.buildPapers(ImportFormat.ZIP, f.readBytes(), f.name, dir)

        assertEquals(1, out.papers.size)
        val questions = out.papers[0].questions
        assertEquals(5, questions.size)

        val text = questions.joinToString("\n") { it.material + "\n" + it.stem }
        assertTrue("包里引用的图应被解析", text.contains("chart-01.png"))
        assertFalse("相对路径应已替换成本机绝对路径", text.contains("](images/"))
    }
}
