package com.lytree.qbank

import com.lytree.qbank.data.importer.ImportFormat
import com.lytree.qbank.data.importer.Importer
import com.lytree.qbank.data.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 「图推700题题库包」的导入回归：**按套拆成 35 份试卷，每份 20 题**。
 *
 * 数据来自 Anki 导入包（一张图一道题、答案 A–D、题干与选项都画在图里），
 * 由 `tools/qbank/build_app_package.py` 转成题库包。这里不自己造 xlsx，
 * 直接拿生成好的真实包走一遍完整导入链路（QbankPackage 解压 → XlsxReader 多表 →
 * PaperSplitter 拆卷 → Normalizer 规范化 → 图片相对路径改写为解压后的绝对路径），
 * 钉住「一表一卷」在真实数据上的表现。
 *
 * 包文件在 `qbank_out/`（本地生成、不入库），没有就跳过，不影响 CI。
 */
class TuituiPackageTest {

    private fun pkgFile(): File? = listOf(
        File("../qbank_out/图推700题题库包.zip"),   // 从 app/ 跑
        File("qbank_out/图推700题题库包.zip"),      // 从仓库根跑
        File("../../qbank_out/图推700题题库包.zip")
    ).firstOrNull { it.isFile }

    /**
     * 干净的临时解压根目录。
     *
     * 解压目录按「包名-CRC32」命名，包内容一变就换新目录、旧目录保留（已导入的题还指着它）。
     * 测试若复用同一个根目录，跨版本的残留会把下面的图片计数翻倍——所以每次先清空。
     */
    private fun freshPkgDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "qbank_pkg_tuitui")
        dir.deleteRecursively()
        return dir
    }

    @Test
    fun splits_into_35_papers_of_20_questions() {
        val file = pkgFile()
        assumeTrue("qbank_out 里没有图推题库包，跳过", file != null)
        val pkgDir = freshPkgDir()

        val out = Importer.build(
            ImportFormat.ZIP, file!!.readBytes(),
            fileName = file.name, pkgDir = pkgDir
        )

        // 35 套 × 20 题，全部干净导入
        assertEquals(0, out.errorCount)
        assertEquals(0, out.warnCount)
        assertEquals(35, out.papers.size)
        assertEquals(700, out.imported)
        assertEquals(700, out.total)
        assertEquals(0, out.duplicateSkipped)

        // 卷名取工作表名，按套号排好
        out.papers.forEachIndexed { i, paper ->
            val no = i + 1
            assertEquals("图推第%02d套".format(no), paper.name)
            assertEquals(20, paper.questions.size)
            assertTrue(paper.origin, paper.origin.contains("工作表"))
            // 每卷一套，题号在卷内连续
            assertEquals(20, paper.totalRows)
            assertEquals("SINGLE", paper.questions.first().type.name)
            assertEquals(QuestionType.SINGLE, paper.questions.first().type)
        }

        // 每道题：4 个选项、答案 A–D、题图已落到解压目录且文件真实存在
        val wrong = ArrayList<String>()
        out.papers.forEach { paper ->
            paper.questions.forEachIndexed { qi, q ->
                if (q.options.size != 4) wrong += "${paper.name}#${qi + 1} 选项数 ${q.options.size}"
                if (q.answer !in listOf("A", "B", "C", "D")) wrong += "${paper.name}#${qi + 1} 答案 ${q.answer}"
                val src = Regex("""!\[[^\]]*]\(([^)]+)\)""").find(q.material)?.groupValues?.get(1)
                if (src == null) {
                    wrong += "${paper.name}#${qi + 1} 材料里没有题图"
                } else {
                    if (src.startsWith("images/")) wrong += "${paper.name}#${qi + 1} 图片路径没改写：$src"
                    if (!File(src).isFile) wrong += "${paper.name}#${qi + 1} 图片不存在：$src"
                }
                if (q.stem.isEmpty()) wrong += "${paper.name}#${qi + 1} 题干为空"
                if (q.subject != "行测·图形推理") wrong += "${paper.name}#${qi + 1} 科目 ${q.subject}"
            }
        }
        assertTrue("有 ${wrong.size} 处不对：\n" + wrong.take(10).joinToString("\n"), wrong.isEmpty())

        // 题图数量对得上：解压后 700 张（解压目录是 pkgDir/<包名>-<CRC32>/，按内容递归数）
        val imgs = pkgDir.walkTopDown().count { it.isFile && it.extension == "png" }
        assertEquals(700, imgs)
    }

    /** 同一份包连导两次，卷名与题数必须稳定（重导入不应多出卷或丢题） */
    @Test
    fun reimport_is_stable() {
        val file = pkgFile()
        assumeTrue("qbank_out 里没有图推题库包，跳过", file != null)
        val pkgDir = freshPkgDir()
        fun once() = Importer.build(
            ImportFormat.ZIP, file!!.readBytes(), fileName = file.name, pkgDir = pkgDir
        )
        val a = once()
        val b = once()
        assertEquals(a.papers.map { it.name }, b.papers.map { it.name })
        assertEquals(a.imported, b.imported)
    }
}
