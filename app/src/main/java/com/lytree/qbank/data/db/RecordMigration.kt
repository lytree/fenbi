package com.lytree.qbank.data.db

/**
 * 覆盖发布时的**作答记录搬迁**：把旧组已交卷的练习记录尽量带到新组头上。
 *
 * 以前覆盖发布（`importSets(replace = true)`）会把旧卷连同作答记录一起清掉——
 * 记录挂在 `paper_id` 上，卷拆掉重建后 id 全变，记录就成了孤儿。但有两根线没断：
 *
 * - **题目行原地复用**：网页编辑器发布时题目带着旧 id 回来（`LanEditorStore.toQuestion`
 *   读 `optLong("id")`），`insertPaper` 对可复用的 id 做**原地更新**——题目 id 不变，
 *   `record_answers` / `record_inks` / `record_question_times` 里挂的题目 id 仍然有效；
 * - **卷按卷名对应**：发布时卷名继承旧值（`LanEditorStore.publish` 按名继承描述与限时），
 *   旧卷记录可以按「卷名相同」挂到新卷头上；「整组作答」的虚拟卷（`paper_id = -组id`）
 *   跟着组 id 走，天然对得上。
 *
 * 中间只剩一种「断线」：题目在网页上**删了又加回来**（或旧 id 没随稿带回）——
 * 内容一样但拿了个新 id。这时用**题目内容指纹**（题型 + 题干 + 答案，去空白）把
 * 旧题目 id 映射到新行上，作答明细跟着过去。指纹匹配不到的（题目真被删了），
 * 对应的明细行只能舍弃——宁可少带，不挂悬空引用。
 *
 * 这里只放**纯映射逻辑**（不碰 SQLiteDatabase），SQL 读写壳在 [QBankDb.importSets]。
 * 搬的是 `submitted_at > 0` 的记录：已交卷的历史事实；没交卷的草稿跟着旧卷走（不搬）。
 */
object RecordMigration {

    /** 题目内容的比对键：题型 + 题干 + 答案，全部去空白后拼接 */
    data class QuestionKey(val type: String, val stem: String, val answer: String) {

        val fingerprint: String
            get() = "${type.trim()}\u0000${squash(stem)}\u0000${squash(answer)}"

        companion object {
            /** 去掉所有空白：全半角空格、换行、制表符、不换行空格（\u00A0）都当没有 */
            fun squash(s: String): String = s.filterNot { Character.isWhitespace(it) || Character.isSpaceChar(it) }
        }
    }

    /** 旧记录的一行（records 表；不含 id 以外的生成列） */
    data class RecordRow(
        val id: Long,
        val paperId: Long,
        val name: String,
        val mode: String,
        val scope: String,
        val startedAt: Long,
        val submittedAt: Long,
        val durationSec: Int,
        val totalCount: Int,
        val answeredCount: Int,
        val correctCount: Int,
        val partialCount: Int,
        val score: Double,
        val fullScore: Double,
        val marked: String,
        val status: String,
    )

    /** record_answers 的一行 */
    data class AnswerRow(val recordId: Long, val questionId: Long, val userAnswer: String, val earned: Double, val correct: Int)

    /** record_inks 的一行（target_id 是题目 id 或材料组首题 id） */
    data class InkRow(val recordId: Long, val kind: String, val targetId: Long, val strokes: String, val updatedAt: Long)

    /** record_question_times 的一行 */
    data class TimeRow(val recordId: Long, val questionId: Long, val seconds: Int, val updatedAt: Long)

    /** 旧组快照：覆盖前从库里舀出来的全部可搬迁数据 */
    data class Snapshot(
        val setId: Long,
        /** 旧卷 id → 卷名 */
        val papers: Map<Long, String>,
        val records: List<RecordRow>,
        val answers: List<AnswerRow>,
        val inks: List<InkRow>,
        val times: List<TimeRow>,
        /** 旧组出现过的题目：id → 内容键 */
        val questions: Map<Long, QuestionKey>,
    )

    /** 搬迁后的一条记录：旧字段 + 落到新组头上的位置 */
    data class PlannedRecord(
        val old: RecordRow,
        /** 新卷 id（正数）或新组虚拟卷 id（负数） */
        val newPaperId: Long,
        /** 重映射后的答题范围（锚点题换了 id 就跟着换；锚点丢了保持原样，读的时候逐级退回） */
        val scope: String,
        /** 重映射后的标记题目 id */
        val markedIds: List<Long>,
    )

    /** 搬迁计划：QBankDb 按顺序插 records，再按 oldRecordId 落明细 */
    data class Plan(
        val records: List<PlannedRecord>,
        /** (旧记录 id, 新题目 id, 明细字段)——新题目 id 为空表示题目没了，舍弃 */
        val answers: List<Triple<Long, Long, AnswerRow>>,
        val inks: List<Triple<Long, Long, InkRow>>,
        val times: List<Triple<Long, Long, TimeRow>>,
    )

    /**
     * 出搬迁计划。
     *
     * @param newSetId   新组的 id（覆盖发布后重新分配的）
     * @param newPapers  新卷 id → 卷名
     * @param newQuestions 新组出现过的题目：id → 内容键（按 id 升序喂进来，指纹撞车时先到的赢）
     */
    fun plan(
        snapshot: Snapshot,
        newSetId: Long,
        newPapers: Map<Long, String>,
        newQuestions: Map<Long, QuestionKey>,
    ): Plan {
        // 指纹 → 新题 id：所有新组题目都参与（复用的旧题也在），删了又加回来的题才能被找到
        val fpToNew = HashMap<String, Long>()
        newQuestions.forEach { (id, key) -> fpToNew.putIfAbsent(key.fingerprint, id) }

        /** 旧题 id → 新题 id：还活着（复用）就原样，死了就按内容找替身，找不到返回 null */
        fun mapQuestion(oldId: Long): Long? {
            if (newQuestions.containsKey(oldId)) return oldId
            val key = snapshot.questions[oldId] ?: return null
            return fpToNew[key.fingerprint]
        }

        // 旧卷名 → 新卷 id：两边都唯一同名才认，重名（或卷被删了）不瞎猜
        val newByName = newPapers.entries.groupBy({ it.value }, { it.key })
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
        val oldByName = snapshot.papers.entries.groupBy({ it.value }, { it.key })
        val paperMap = HashMap<Long, Long>()
        oldByName.forEach { (name, oldIds) ->
            if (oldIds.size == 1) newByName[name]?.let { paperMap[oldIds.single()] = it }
        }

        val scopeRe = Regex("^([sm])(\\d+)$")

        val records = ArrayList<PlannedRecord>()
        val recordIds = HashSet<Long>()
        snapshot.records.forEach { r ->
            // 只搬交过卷的历史；没交的草稿跟旧卷一起走（进度表也不搬，别半途的答案接不准）
            if (r.submittedAt <= 0L) return@forEach
            val newPaper = when {
                r.paperId > 0 -> paperMap[r.paperId]
                // 整组虚拟卷：组重建了 id 也变了，跟着新组走
                r.paperId < 0 && r.paperId == -snapshot.setId -> -newSetId
                else -> null
            } ?: return@forEach

            val scope = remapScope(r.scope, scopeRe, ::mapQuestion)
            val marked = r.marked.split(',').mapNotNull { it.trim().toLongOrNull() }.mapNotNull { mapQuestion(it) }

            records.add(PlannedRecord(r, newPaper, scope, marked))
            recordIds.add(r.id)
        }

        val answers = snapshot.answers.mapNotNull { a ->
            mapQuestion(a.questionId)?.let { Triple(a.recordId, it, a) }
        }.filter { it.first in recordIds }
        val inks = snapshot.inks.mapNotNull { i ->
            mapQuestion(i.targetId)?.let { Triple(i.recordId, it, i) }
        }.filter { it.first in recordIds }
        val times = snapshot.times.mapNotNull { t ->
            mapQuestion(t.questionId)?.let { Triple(t.recordId, it, t) }
        }.filter { it.first in recordIds }

        return Plan(records, answers, inks, times)
    }

    /**
     * 答题范围的锚点重映射：`s:<id>` / `m:<id>` 里的数字按题目映射换新 id。
     *
     * 锚点找不到时**保持原样**而不是清空：`ExamScope.resolve` 读的时候本来就对
     * 悬空锚点逐级退回（材料 → 大类 → 整卷），清空反而会把「当时只做了这则材料」
     * 抹成「做了整卷」，记录页的统计就撒谎了。
     */
    private fun remapScope(scope: String, re: Regex, mapQuestion: (Long) -> Long?): String {
        val m = re.find(scope.trim()) ?: return scope
        val anchor = m.groupValues[2].toLongOrNull() ?: return scope
        val mapped = mapQuestion(anchor) ?: return scope
        return "${m.groupValues[1]}$mapped"
    }
}
