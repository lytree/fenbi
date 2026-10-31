package com.lytree.qbank

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lytree.qbank.data.importer.ImportFormat
import com.lytree.qbank.data.importer.Importer
import com.lytree.qbank.data.model.QuestionType
import com.lytree.qbank.engine.Judge
import com.lytree.qbank.engine.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 导入 + 判题的端到端仪器化测试：统一 Excel 格式必须解析出全部题目、题型齐全，
 * 且关键题目的答案与判题结果符合预期；老格式（json / csv / txt）一律不再解析。
 *
 * 当前版本只支持选择题（单选 / 多选 / 不定项 / 判断），填空与主观题不再进入试卷。
 */
@RunWith(AndroidJUnit4::class)
class ImportInstrumentedTest {

    /** 示例试卷总题数（以 app 内置 samples/行测基础示例.xlsx 为准） */
    private val total = 11

    private fun asset(name: String): ByteArray {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return ctx.assets.open(name).use { it.readBytes() }
    }

    private fun importOf(name: String, format: ImportFormat = ImportFormat.XLSX) =
        Importer.build(format, asset(name))

    @Test
    fun xlsx_imports_all_questions_and_types() {
        val r = importOf("行测基础示例.xlsx")
        assertEquals("XLSX 应解析出 $total 题", total, r.imported)
        assertEquals("不应有错误", 0, r.errorCount)
        val types = r.questions.map { it.type }.toSet()
        assertTrue(
            "选择题题型都应存在，实际=$types",
            types.containsAll(
                setOf(
                    QuestionType.SINGLE, QuestionType.MULTI, QuestionType.UNCERTAIN,
                    QuestionType.TRUE_FALSE
                )
            )
        )
        // 第一题：单选，答案 A，四选项
        val q1 = r.questions[0]
        assertEquals(QuestionType.SINGLE, q1.type)
        assertEquals("A", q1.answer)
        assertEquals(4, q1.options.size)
        assertTrue(q1.analysis.isNotBlank())
        assertEquals("宪法·国家机构", q1.knowledge)
    }

    @Test
    fun material_with_table_is_parsed() {
        val r = importOf("行测基础示例.xlsx")
        // 资料分析题：材料应完整保留，且含 Markdown 表格
        val material = r.questions.first { it.material.isNotBlank() }
        assertTrue("材料应包含表格分隔行", material.material.contains("| --- |"))
        assertTrue("材料应包含表格数据行", material.material.contains("废钢铁"))
        assertEquals("资料分析题应为单选", QuestionType.SINGLE, material.type)
    }

    @Test
    fun format_detection_works() {
        // 普通 Excel 仍是 Excel
        assertEquals(ImportFormat.XLSX, Importer.detect("a.xlsx", asset("行测基础示例.xlsx")))
        assertEquals(ImportFormat.XLSX, Importer.detect("a", asset("行测基础示例.xlsx")))
        // 老格式一律按 Excel 处理（解析时明说只支持 Excel）
        assertEquals(ImportFormat.XLSX, Importer.detect("a.json", "{}".toByteArray()))
        assertEquals(ImportFormat.XLSX, Importer.detect("a.csv", "a,b".toByteArray()))
        assertEquals(ImportFormat.XLSX, Importer.detect("a.txt", "1.A".toByteArray()))
    }

    @Test
    fun legacy_formats_fail_loudly() {
        listOf("试卷.json" to "{}", "试卷.csv" to "题干,答案", "试卷.txt" to "1.A")
            .forEach { (name, content) ->
                val r = importOf(name, Importer.detect(name, content.toByteArray()))
                assertEquals("$name 应导入 0 题", 0, r.imported)
                assertTrue("$name 应报「只支持 Excel」", r.issues.any { it.message.contains("Excel") })
            }
    }

    @Test
    fun judging_rules_work() {
        val r = importOf("行测基础示例.xlsx")
        val p = Policy.DEFAULT

        val single = r.questions[0]
        assertTrue(Judge.judge(single, "A", p).correct)
        assertTrue(!Judge.judge(single, "B", p).correct)
        assertEquals(0.0, Judge.judge(single, "", p).earned, 0.0001)

        val multi = r.questions[1]
        assertTrue(Judge.judge(multi, "A,B,D", p).correct)
        // 多选少选：默认不得分
        val partial = Judge.judge(multi, "A,B", p)
        assertTrue(!partial.correct)
        assertEquals(0.0, partial.earned, 0.0001)
        // 开启「少选给部分分」后
        val partialOn = Judge.judge(multi, "A,B", p.copy(multiPartial = true))
        assertTrue(partialOn.partial)
        assertTrue(partialOn.earned > 0.0)
        // 错选一律 0
        assertEquals(0.0, Judge.judge(multi, "A,B,C", p.copy(multiPartial = true)).earned, 0.0001)

        val judge = r.questions[2]
        assertTrue(Judge.judge(judge, "A", p).correct)
        assertTrue(Judge.judge(judge, "正确", p).correct)
        assertTrue(Judge.judge(judge, "T", p).correct)
        assertTrue(!Judge.judge(judge, "B", p).correct)
    }

    @Test
    fun all_imported_questions_are_answerable() {
        // 当前版本只支持选择题：导入结果里不应再有填空/主观题
        val r = importOf("行测基础示例.xlsx")
        r.questions.forEach { q ->
            assertTrue("题目应为选择题，实际=${q.type}", q.type.answerable)
        }
    }
}
