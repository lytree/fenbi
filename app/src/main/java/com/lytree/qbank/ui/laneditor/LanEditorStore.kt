package com.lytree.qbank.ui.laneditor

import android.content.Context
import com.lytree.qbank.data.db.PaperImport
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.db.SetImport
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.data.model.Option
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.data.model.QuestionType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 局域网编辑器的数据层：把「题库」读写翻译成 App 现成的库操作。
 *
 * 与 App 的数据模型对齐，没有第二套结构：
 * - **一级标题 = 导入组 `paper_sets`**，Web 端的「保存」就是按标题重导这一组；
 * - **组里的每份卷 = 一张工作表**（Web 端顶部的卷标签），题按卷面顺序落 `paper_questions`；
 * - 图片统一放 `filesDir/lan_editor/images/`，题目里写 `images/xxx.png`，**入库时才换成绝对路径**
 *   （App 的 MaterialRenderer 认绝对路径，换机备份也跟着走）。
 *
 * 覆盖同一标题会走 `importSets(replace = true)`——那一组的旧卷连同作答记录一起被替换，
 * 这是「重新导入同一份卷」的既定口径。但**旧内容不会被白白冲掉**：覆盖前先由
 * [LanEditorArchive] 存一份快照，网页上能翻回任意一版再「恢复」。
 */
class LanEditorStore(private val context: Context) {

    private val db: QBankDb = QBankDb.get(context)

    val imageDir: File = File(context.filesDir, "lan_editor/images").apply { mkdirs() }

    private val archive = LanEditorArchive(File(context.filesDir, "lan_editor"))

    // ---------------------------------------------------------------- 读

    /** 组列表：左侧栏用，只带计数，不带题目正文 */
    fun listSets(): JSONObject {
        val sets = db.listPaperSets(withPapers = true)
        val arr = JSONArray()
        sets.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("source", s.source)
                    .put("paperCount", s.paperCount)
                    .put("questionCount", s.questionCount)
                    .put("missingAnswers", s.missingAnswers)
                    .put("editable", s.id > 0)
                    .put(
                        "papers", JSONArray().apply {
                            s.papers.forEach { p ->
                                put(
                                    JSONObject()
                                        .put("id", p.id)
                                        .put("name", p.name)
                                        .put("questionCount", p.questionCount)
                                        .put("missingAnswers", p.missingAnswers)
                                )
                            }
                        }
                    )
            )
        }
        return JSONObject()
            .put("sets", arr)
            .put("questionTotal", sets.sumOf { it.questionCount })
            .put("paperTotal", sets.sumOf { it.paperCount })
    }

    /** 打开一组：组里的每份卷 → 一张工作表 → 逐题 */
    fun loadSet(setId: Long): JSONObject {
        require(setId > 0) { "「未分组」里的卷不支持在线编辑，请先在 App 里把它们归到某个标题下" }
        val set = db.listPaperSets(withPapers = true).firstOrNull { it.id == setId }
            ?: throw IllegalArgumentException("找不到这组试卷（可能已在 App 里被删掉）")

        val sheets = JSONArray()
        set.papers.forEach { p ->
            val questions = db.listQuestions(p.id)
            sheets.put(
                JSONObject()
                    .put("name", p.name)
                    .put("questions", JSONArray().apply { questions.forEach { put(questionToJson(it)) } })
            )
        }
        return JSONObject()
            .put("id", set.id)
            .put("title", set.title)
            .put("sheets", sheets)
            .put("questionCount", set.questionCount)
            .put("missingAnswers", set.missingAnswers)
    }

    private fun questionToJson(q: Question): JSONObject = JSONObject().apply {
        put("type", typeKey(q.type))
        put("subject", q.subject)
        put("material", q.material)
        put("images", "")
        put("stem", q.stem)
        put(
            "options", JSONArray().apply {
                q.options.forEach { o ->
                    put(JSONObject().put("key", o.key).put("text", o.text))
                }
            }
        )
        put("answer", answerForEditor(q))
        put("analysis", q.analysis)
        put("knowledge", q.knowledge)
        put("difficulty", if (q.difficulty > 0) q.difficulty.toString() else "")
        put("region", q.region)
        put("source", q.source)
        put("year", q.year)
        put("score", q.score)
        put("keywords", q.keywords)
        put("id", q.id)
    }

    /** Web 端用 `truefalse`；App 里这个题型的 code 是 `judge`，两头各转一次 */
    private fun typeKey(t: QuestionType): String = if (t == QuestionType.TRUE_FALSE) "truefalse" else t.code

    /** 判断题在 Web 端显示「正确 / 错误」，库里可能是 A/B 或 T/F，这里统一成中文 */
    private fun answerForEditor(q: Question): String {
        if (q.type != QuestionType.TRUE_FALSE) return q.answer
        return when (q.answer.trim().uppercase()) {
            "A", "T", "TRUE", "Y", "YES", "正确", "对", "√", "1" -> "正确"
            "B", "F", "FALSE", "N", "NO", "错误", "错", "×", "0" -> "错误"
            else -> q.answer
        }
    }

    // ---------------------------------------------------------------- 作答记录（给 AI 批改用）

    /**
     * 作答记录列表。传 setId 就只看这一组的记录（卷的记录 + 「整组作答」那条虚拟卷）。
     *
     * 记录是只读的：批改 / 讲评由 AI 在对话里做，MCP 不提供「改分数」的入口——
     * 让人工确认过的分数被模型悄悄改掉，比不批改更糟。
     */
    fun listRecords(setId: Long?): JSONObject {
        val all = db.listRecords()
        val filtered = if (setId != null && setId > 0) {
            val paperIds = db.listPapers().filter { it.setId == setId }.map { it.id }.toSet()
            all.filter { it.paperId in paperIds || it.paperId == -setId }
        } else {
            all
        }
        val arr = JSONArray()
        filtered.take(200).forEach { arr.put(recordToJson(it)) }
        return JSONObject().put("records", arr).put("total", filtered.size)
    }

    /** 一次作答的逐题明细：用户答案、标准答案、对错、得分，按卷面顺序 */
    fun loadRecord(id: Long): JSONObject {
        val rec = db.getRecord(id) ?: throw IllegalArgumentException("找不到这条作答记录（可能已被删掉）")
        // 按卷面顺序排，AI 说「第 12 题」才和 App 里看到的是同一题
        val order = if (rec.paperId > 0) {
            db.listQuestions(rec.paperId).mapIndexed { i, q -> q.id to i + 1 }.toMap()
        } else {
            emptyMap()
        }
        val answers = JSONArray()
        rec.results.sortedBy { order[it.question.id] ?: Int.MAX_VALUE }.forEachIndexed { i, a ->
            val q = questionToJson(a.question).apply {
                put("no", order[a.question.id] ?: (i + 1))
                put("userAnswer", a.userAnswer)
                put("correct", a.correct)
                put("partial", a.partial)
                put("pending", a.pending)
                put("earned", a.earned)
                put("full", a.full)
            }
            answers.put(q)
        }
        return recordToJson(rec).put("answers", answers)
    }

    private fun recordToJson(r: ExamRecord): JSONObject = JSONObject()
        .put("id", r.id)
        .put("paperId", r.paperId)
        .put("name", r.name)
        .put("paperName", r.paperName)
        .put("mode", r.mode)
        .put("status", r.status)
        .put("startedAt", r.startedAt)
        .put("submittedAt", r.submittedAt)
        .put("durationSec", r.durationSec)
        .put("total", r.totalCount)
        .put("answered", r.answeredCount)
        .put("correct", r.correctCount)
        .put("partial", r.partialCount)
        .put("score", r.score)
        .put("fullScore", r.fullScore)

    // ---------------------------------------------------------------- 写

    /**
     * 发布一组：标题 + 若干份卷。
     *
     * @param replace 同标题已存在时是否覆盖；false 时抛错让前端先确认，
     *                免得手滑把有作答记录的一组顶掉。
     * @param reason  覆盖前那份快照的来由（`publish` = 发布前的旧版，`restore` = 回滚前的旧版），
     *                版本列表里显示成「发布前存档 / 回滚前存档」。
     */
    fun publish(
        title: String,
        replace: Boolean,
        sheets: JSONArray,
        reason: String = "publish",
    ): JSONObject {
        if (sheets.length() == 0) throw IllegalArgumentException("至少要有一份试卷")
        val existing = db.listPaperSets(withPapers = false).firstOrNull { it.title == title }
        if (existing != null && !replace) {
            throw IllegalArgumentException("已经有同名的组「$title」，确认覆盖请点「覆盖发布」")
        }

        val errors = ArrayList<String>()
        val papers = ArrayList<PaperImport>()
        for (si in 0 until sheets.length()) {
            val sheet = sheets.getJSONObject(si)
            val name = sheet.optString("name").trim().ifEmpty { "试卷${si + 1}" }
            val rawQs = sheet.optJSONArray("questions") ?: JSONArray()
            val list = ArrayList<Question>(rawQs.length())
            for (qi in 0 until rawQs.length()) {
                toQuestion(rawQs.getJSONObject(qi), name, qi, errors)?.let { list.add(it) }
            }
            if (list.isEmpty()) throw IllegalArgumentException("「$name」里一道题都没有，先加题再发布")
            papers.add(PaperImport(name = name, description = "局域网编辑器", durationMin = 0, questions = list))
        }
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(
                errors.take(8).joinToString("\n") +
                    if (errors.size > 8) "\n…另有 ${errors.size - 8} 处问题" else ""
            )
        }

        // 校验都过了再留底：被拦下的发布不该在版本列表里留一条空记录
        val version = existing?.let {
            runCatching { archive.save(it.title, it.id, reason, loadSet(it.id).getJSONArray("sheets")) }.getOrNull()
        }

        val result = db.importSets(
            listOf(SetImport(title = title, source = "局域网编辑器", papers = papers)),
            replace = replace || existing != null
        ).firstOrNull() ?: throw IllegalStateException("写入失败：没有生成任何试卷")

        return JSONObject()
            .put("ok", true)
            .put("setId", result.setId)
            .put("title", result.title)
            .put("questionCount", result.questionCount)
            .put("replaced", existing != null)
            .put("version", version ?: JSONObject.NULL)
            .put(
                "papers", JSONArray().apply {
                    result.papers.forEach { put(JSONObject().put("name", it.paperName).put("questionCount", it.questionCount)) }
                }
            )
    }

    // ---------------------------------------------------------------- 版本

    /** 某一组的历史版本（新的在前）；空标题给全部 */
    fun listVersions(title: String?): JSONObject =
        JSONObject().put("versions", archive.list(title))

    /**
     * 回滚到某一版：把快照里的卷原样重新发布一遍。
     *
     * 回滚本身也是「覆盖」，所以会先把当前内容再存一版——回滚错了还能再滚回来。
     *
     * @param targetSetId 当前打开的组。组名可能已经被改过，回滚要落回**这个组**，
     *                    而不是快照里那个旧名字，否则会在列表里凭空多出一组。
     */
    fun restoreVersion(id: String, targetSetId: Long = 0): JSONObject {
        val snap = archive.read(id) ?: throw IllegalArgumentException("这个版本找不到了（可能已被清理）")
        val current = if (targetSetId > 0) {
            db.listPaperSets(withPapers = false).firstOrNull { it.id == targetSetId }
        } else {
            null
        }
        val title = current?.title ?: snap.optString("title").trim()
        if (title.isEmpty()) throw IllegalArgumentException("版本里没有组名，恢复不了")
        val sheets = snap.optJSONArray("sheets") ?: throw IllegalArgumentException("版本内容不完整")
        return publish(title, replace = true, sheets = sheets, reason = "restore")
            .put("restoredFrom", id)
    }

    fun deleteVersion(id: String): JSONObject {
        if (!archive.delete(id)) throw IllegalArgumentException("这个版本已经不在存档里了")
        return JSONObject().put("ok", true)
    }

    // ---------------------------------------------------------------- 删除（要手机确认）

    /** 删除确认弹窗要显示的信息：多少卷、多少题、多少条作答记录 */
    fun setBrief(setId: Long): JSONObject {
        val set = db.listPaperSets(withPapers = true).firstOrNull { it.id == setId }
            ?: throw IllegalArgumentException("这组已经不存在了")
        return JSONObject()
            .put("id", set.id)
            .put("title", set.title)
            .put("paperCount", set.paperCount)
            .put("questionCount", set.questionCount)
            .put("recordCount", recordCount(setId))
    }

    /** 真正删掉一组：只在手机上点过「确认删除」之后才会走到这里 */
    fun deleteSet(setId: Long): JSONObject {
        require(setId > 0) { "「未分组」不支持在这里删，请回 App 里操作" }
        val set = db.listPaperSets(withPapers = true).firstOrNull { it.id == setId }
            ?: throw IllegalArgumentException("这组已经不存在了")
        db.deletePaperSet(setId)
        return JSONObject()
            .put("ok", true)
            .put("title", set.title)
            .put("paperCount", set.paperCount)
            .put("questionCount", set.questionCount)
    }

    /** 这组有多少条作答记录（卷的 + 「整组作答」那条虚拟卷的） */
    private fun recordCount(setId: Long): Int {
        if (setId <= 0) return 0
        val sql = "SELECT COUNT(*) FROM records WHERE paper_id IN " +
            "(SELECT id FROM papers WHERE set_id = ?) OR paper_id = ?"
        return db.readableDatabase.rawQuery(sql, arrayOf(setId.toString(), (-setId).toString())).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * 一行 JSON → 一道题。
     *
     * 与 App 导入同一套底线：题干必备、选择题至少两个选项；不达标就记一条错误，
     * 整批不写（宁可让用户在网页上改好，也不要悄悄少几题）。
     */
    private fun toQuestion(o: JSONObject, sheetName: String, index: Int, errors: MutableList<String>): Question? {
        val where = "「$sheetName」第 ${index + 1} 题"
        val stem = localizeImages(o.optString("stem").trim())
        if (stem.isEmpty()) {
            errors.add("$where：题干为空")
            return null
        }
        val type = QuestionType.parse(o.optString("type")) ?: QuestionType.SINGLE

        val options = ArrayList<Option>()
        o.optJSONArray("options")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val key = item.optString("key").trim().ifEmpty { ('A' + i).toString() }
                val text = localizeImages(item.optString("text")).trim()
                if (text.isNotEmpty()) options.add(Option(key.uppercase(), text))
            }
        }
        if (type.hasOptions && options.size < 2) {
            errors.add("$where：选择题至少要 2 个有内容的选项")
            return null
        }

        var answer = o.optString("answer").trim()
        if (type == QuestionType.TRUE_FALSE) {
            answer = when (answer.uppercase()) {
                "A", "T", "TRUE", "Y", "YES", "正确", "对", "√", "1" -> "正确"
                "B", "F", "FALSE", "N", "NO", "错误", "错", "×", "0" -> "错误"
                else -> answer
            }
            if (answer.isEmpty()) errors.add("$where：判断题还没填答案")
        } else if (type.hasOptions) {
            val letters = answer.uppercase().filter { it in 'A'..'H' }.toSortedSet()
            if (letters.isEmpty()) errors.add("$where：还没填答案")
            answer = letters.joinToString(",")
        }

        val score = o.opt("score")?.toString()?.trim()?.toDoubleOrNull() ?: defaultScore(type)
        val difficulty = o.optString("difficulty").trim().toIntOrNull()?.coerceIn(0, 3) ?: 0

        return Question(
            type = type,
            stem = stem,
            material = localizeImages(o.optString("material")),
            options = options,
            answer = answer,
            analysis = localizeImages(o.optString("analysis")),
            knowledge = o.optString("knowledge").trim(),
            difficulty = difficulty,
            region = o.optString("region").trim(),
            subject = o.optString("subject").trim(),
            source = o.optString("source").trim(),
            year = o.optString("year").trim(),
            score = score,
            keywords = o.optString("keywords").trim()
        )
    }

    private fun defaultScore(type: QuestionType): Double = when (type) {
        QuestionType.MULTI, QuestionType.UNCERTAIN -> 2.0
        QuestionType.SUBJECTIVE -> 10.0
        else -> 1.0
    }

    /**
     * 把 `images/xxx.png` 换成手机上的绝对路径。
     *
     * 网页上写相对路径（短、可读、跟试卷包一个口径），但 App 里材料渲染认的是绝对路径；
     * 图不存在时原样留着——发布后题目还会显示路径文字，一眼能看出哪张图掉了。
     */
    private fun localizeImages(text: String): String {
        if (text.isBlank() || !text.contains("images/")) return text
        return IMAGE_REF.replace(text) { m ->
            val name = m.groupValues[1]
            val file = File(imageDir, name)
            if (file.isFile) file.absolutePath else m.value
        }
    }

    // ---------------------------------------------------------------- 图片

    /** 存一张上传的图，返回题目里该写的相对路径 `images/xxx.png` */
    fun saveImage(rawName: String, bytes: ByteArray): String {
        imageDir.mkdirs()
        val safe = rawName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
            .ifBlank { "image.png" }
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ".png"

        var candidate = "$stem$ext"
        var n = 1
        while (File(imageDir, candidate).exists()) {
            candidate = "$stem-$n$ext"
            n++
        }
        File(imageDir, candidate).writeBytes(bytes)
        return "images/$candidate"
    }

    /**
     * 解析要回给浏览器的图片路径：只允许 `images/…`（编辑器上传的）
     * 和 App 私有目录里的绝对路径（导入试卷包解出来的图），别的一律拒绝。
     */
    fun resolveImage(path: String): File? {
        val p = path.trim()
        if (p.isEmpty()) return null
        val file = if (p.startsWith("images/") || p.startsWith("./images/")) {
            File(imageDir, p.substringAfter("images/"))
        } else {
            File(p)
        }
        if (!file.isFile) return null
        val allowedRoot = context.filesDir.absolutePath
        val absolute = file.absolutePath
        return if (absolute.startsWith(allowedRoot)) file else null
    }

    private companion object {
        /**
         * `./images/x.png` / `images/子目录/x.png`。
         *
         * 前面的负向环视很关键：绝对路径 `…/qbank_pkg/images/x.png` 里的 `images/`
         * 前面是 `/`，不能被当成相对路径改写，否则老题里的图会全部指错地方。
         */
        val IMAGE_REF = Regex("""(?<![\w./\\-])(?:\./)?images/([^)\s"'<>]+)""")
    }
}
