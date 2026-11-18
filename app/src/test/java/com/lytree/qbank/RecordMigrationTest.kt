package com.lytree.qbank

import com.lytree.qbank.data.db.RecordMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖发布的作答记录搬迁（[RecordMigration]）：纯映射口径，钉住四件事——
 *
 * 1. 复用的题（id 原地不动）明细原样跟着记录走；
 * 2. 删了又加回来的题（内容一样、id 换了）靠内容指纹找到替身；
 * 3. 被删掉的题，明细行舍弃而不是挂悬空引用；
 * 4. 卷按卷名对应、整组虚拟卷跟组走、没交卷的草稿不搬。
 */
class RecordMigrationTest {

    private fun key(type: String, stem: String, answer: String) = RecordMigration.QuestionKey(type, stem, answer)

    private fun oldSnapshot(): RecordMigration.Snapshot {
        val records = listOf(
            // 卷 101 的整卷记录，已交卷；标记了题 11 和题 12
            RecordMigration.RecordRow(
                id = 1, paperId = 101, name = "行测一", mode = "exam", scope = "",
                startedAt = 1000, submittedAt = 2000, durationSec = 600,
                totalCount = 2, answeredCount = 2, correctCount = 1, partialCount = 0,
                score = 1.0, fullScore = 2.0, marked = "11,12", status = "done",
            ),
            // 整组虚拟卷（paper_id = -7）的记录
            RecordMigration.RecordRow(
                id = 2, paperId = -7, name = "整组练习", mode = "exam", scope = "m:11",
                startedAt = 3000, submittedAt = 4000, durationSec = 300,
                totalCount = 1, answeredCount = 1, correctCount = 1, partialCount = 0,
                score = 1.0, fullScore = 1.0, marked = "", status = "done",
            ),
            // 没交卷的草稿：不该搬
            RecordMigration.RecordRow(
                id = 3, paperId = 101, name = "行测一", mode = "exam", scope = "",
                startedAt = 5000, submittedAt = 0, durationSec = 60,
                totalCount = 2, answeredCount = 1, correctCount = 0, partialCount = 0,
                score = 0.0, fullScore = 0.0, marked = "", status = "ongoing",
            ),
        )
        return RecordMigration.Snapshot(
            setId = 7,
            papers = mapOf(101L to "行测一", 102L to "行测二"),
            records = records,
            answers = listOf(
                RecordMigration.AnswerRow(1, 11, "A", 1.0, 1),      // 复用题，原样
                RecordMigration.AnswerRow(1, 12, "B", 0.0, 0),      // 删了又加回（新 id 21），指纹找回
                RecordMigration.AnswerRow(1, 13, "C", 0.0, 0),      // 真删了，舍弃
                RecordMigration.AnswerRow(2, 11, "A", 1.0, 1),
                RecordMigration.AnswerRow(3, 11, "A", 1.0, 1),      // 草稿的明细，跟着记录一起不搬
            ),
            inks = listOf(
                RecordMigration.InkRow(1, "question", 11, "{}", 10),
                RecordMigration.InkRow(1, "material", 11, "{}", 10), // 材料锚点 = 组首题 11
            ),
            times = listOf(RecordMigration.TimeRow(1, 11, 120, 10)),
            questions = mapOf(
                11L to key("single", "题干甲", "A"),
                12L to key("single", "题干乙", "B"),
                13L to key("single", "题干丙", "C"),
            ),
        )
    }

    /** 新组：卷 201 = 旧行测一；题 11 复用、题 21 = 题干乙的新行、题干丙没回来 */
    private fun newWorld() = Triple(
        8L,
        mapOf(201L to "行测一", 202L to "行测三"),
        linkedMapOf(
            11L to key("single", "题干甲", "A"),
            21L to key("single", "题干乙", "B"),
            31L to key("single", "题干丁", "D"),
        ),
    )

    @Test
    fun 复用题原样_换id题按指纹找回_删掉的舍弃() {
        val (newSetId, newPapers, newQuestions) = newWorld()
        val plan = RecordMigration.plan(oldSnapshot(), newSetId, newPapers, newQuestions)

        // 两条已交卷记录都搬过来：卷 101 → 按卷名挂 201；整组 → -8
        assertEquals(2, plan.records.size)
        val r1 = plan.records.first { it.old.id == 1L }
        assertEquals(201L, r1.newPaperId)
        val r2 = plan.records.first { it.old.id == 2L }
        assertEquals(-8L, r2.newPaperId)
        // 范围锚点 11 复用不变
        assertEquals("m:11", r2.scope)
        // 标记里题 12 已换新 id 21，都活着
        assertEquals(listOf(11L, 21L), r1.markedIds)

        // 明细：11 原样、12 → 21（指纹）；13 与草稿的明细都不要
        assertEquals(
            listOf(
                Triple(1L, 11L, "A"), Triple(1L, 21L, "B"), Triple(2L, 11L, "A"),
            ),
            plan.answers.map { Triple(it.first, it.second, it.third.userAnswer) },
        )
        // 笔迹跟着映射走（材料锚点也是题 11，复用不变）
        assertEquals(listOf(11L to "question", 11L to "material"), plan.inks.map { it.second to it.third.kind })
        // 逐题用时只剩题 11 的那条
        assertEquals(listOf(11L), plan.times.map { it.second })
    }

    @Test
    fun 指纹忽略空白差异() {
        val (newSetId, newPapers, _) = newWorld()
        val newQuestions = linkedMapOf(
            41L to key("single", "题干\u00A0乙", " B "), // 内容带了不可见空白 / 空格，还是同一道题
        )
        val snapshot = oldSnapshot().copy(
            records = oldSnapshot().records.filter { it.id == 1L },
            answers = listOf(RecordMigration.AnswerRow(1, 12, "B", 0.0, 0)),
        )
        val plan = RecordMigration.plan(snapshot, newSetId, newPapers, newQuestions)
        // 题干乙换了 id 还换了空白形态，指纹照样对上
        assertEquals(listOf(41L), plan.answers.map { it.second })
    }

    @Test
    fun 卷被删了或重名了记录就不瞎挂() {
        val (newSetId, _, newQuestions) = newWorld()
        // 新组没有「行测二」这张卷；另有一张同名「行测一」两张（重名）——都不该被选中
        val newPapers = mapOf(201L to "行测一", 202L to "行测一")
        val plan = RecordMigration.plan(oldSnapshot(), newSetId, newPapers, newQuestions)
        assertTrue(plan.records.none { it.old.paperId == 101L })
        // 整组虚拟卷不受卷名影响，照搬
        assertTrue(plan.records.any { it.old.id == 2L && it.newPaperId == -newSetId })
    }

    @Test
    fun 范围锚点丢了保持原样交给读取时逐级退回() {
        val (newSetId, newPapers, _) = newWorld()
        val snapshot = oldSnapshot().copy(
            records = listOf(
                RecordMigration.RecordRow(
                    id = 2, paperId = -7, name = "整组练习", mode = "exam", scope = "m:13",
                    startedAt = 3000, submittedAt = 4000, durationSec = 300,
                    totalCount = 1, answeredCount = 1, correctCount = 1, partialCount = 0,
                    score = 1.0, fullScore = 1.0, marked = "", status = "done",
                ),
            ),
        )
        val plan = RecordMigration.plan(snapshot, newSetId, newPapers, newQuestions = linkedMapOf())
        // 题干丙真删了：锚点 13 找不到替身，保持原样——ExamScope.resolve 会逐级退回整卷
        assertEquals("m:13", plan.records.single().scope)
    }
}
