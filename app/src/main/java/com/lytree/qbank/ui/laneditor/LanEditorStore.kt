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
 * - 图片按组编号分目录放 `filesDir/lan_editor/images/<组编号>/`，题目里写 `images/<组编号>/xxx.png`，
 *   **入库时才换成绝对路径**（App 的 MaterialRenderer 认绝对路径，换机备份也跟着走）。
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

    /**
     * 按标题查组 id；没有就是 null。
     *
     * 发布前先用它判「是不是覆盖」：网页拿不到写权限，得在**登记确认请求之前**
     * 就把「已有同名组」告诉它，否则等手机点了确认才报错，白等一分钟。
     */
    fun findSetIdByTitle(title: String): Long? =
        db.listPaperSets(withPapers = false).firstOrNull { it.title == title }?.id

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

    /**
     * 打开一组：组里的每份卷 → 一张工作表 → 逐题。
     *
     * @param limit 单卷最多读多少题。读和写必须是同一批数据——`listQuestions` 有 5000 条上限，
     *   如果读的时候被截断、发布时又整卷写回去，后面那些题就被冲掉了。所以宁可整卷拒绝编辑，
     *   也不能让用户在不知情的情况下保存。版本快照传 `Int.MAX_VALUE`（存档必须全量）。
     */
    fun loadSet(setId: Long, limit: Int = MAX_EDIT_QUESTIONS): JSONObject {
        require(setId > 0) { "「未分组」里的卷不支持在线编辑，请先在 App 里把它们归到某个标题下" }
        val set = db.listPaperSets(withPapers = true).firstOrNull { it.id == setId }
            ?: throw IllegalArgumentException("找不到这组试卷（可能已在 App 里被删掉）")

        val sheets = JSONArray()
        set.papers.forEach { p ->
            val total = db.countQuestions(p.id)
            if (total > limit) {
                throw IllegalStateException(
                    "「${p.name}」有 $total 题，超过网页编辑器单卷 $limit 题的上限。" +
                        "在线读只能读到前 $limit 题，保存时会把后面的题冲掉——" +
                        "请先在 App 里把这份卷拆成几份，再回来编辑。"
                )
            }
            val questions = db.listQuestions(p.id, limit)
            sheets.put(
                JSONObject()
                    .put("name", p.name)
                    .put("questions", JSONArray().apply { questions.forEach { put(questionToJson(it, setId)) } })
            )
        }
        return JSONObject()
            .put("id", set.id)
            .put("title", set.title)
            .put("sheets", sheets)
            .put("questionCount", set.questionCount)
            .put("missingAnswers", set.missingAnswers)
    }

    /**
     * @param setId 当前打开的组；大于 0 时把题目里的图片搬进 `images/<setId>/` 并改写成该相对路径。
     *              作答记录（[loadRecord]）不传，图片路径保持库里的原样。
     */
    private fun questionToJson(q: Question, setId: Long = 0): JSONObject = JSONObject().apply {
        put("type", typeKey(q.type))
        put("subject", q.subject)
        put("material", editorText(q.material, setId))
        put("images", "")
        put("stem", editorText(q.stem, setId))
        put(
            "options", JSONArray().apply {
                q.options.forEach { o ->
                    put(JSONObject().put("key", o.key).put("text", editorText(o.text, setId)))
                }
            }
        )
        put("answer", answerForEditor(q))
        put("analysis", editorText(q.analysis, setId))
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
        // 卷描述 / 限时 / 组来源都是 App 侧的东西，编辑器不显示也不该顺手改掉：
        // 按卷名把旧值继承回来，只有真正新增的卷才用默认值
        val oldPapers = if (existing != null) {
            db.listPapers().filter { it.setId == existing.id }.associateBy { it.name }
        } else {
            emptyMap()
        }
        val oldSource = existing?.source?.takeIf { it.isNotBlank() }

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
            val old = oldPapers[name]
            papers.add(
                PaperImport(
                    name = name,
                    description = old?.description?.takeIf { it.isNotBlank() } ?: "局域网编辑器",
                    durationMin = old?.durationMin ?: 0,
                    questions = list,
                )
            )
        }
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(
                errors.take(8).joinToString("\n") +
                    if (errors.size > 8) "\n…另有 ${errors.size - 8} 处问题" else ""
            )
        }

        // 校验都过了再留底：被拦下的发布不该在版本列表里留一条空记录。
        // 存档是唯一的后悔药——存不下来就不能覆盖，否则旧内容真的没了。
        val version = existing?.let {
            try {
                archive.save(it.title, it.id, reason, loadSet(it.id, limit = Int.MAX_VALUE).getJSONArray("sheets"))
            } catch (e: Exception) {
                throw IllegalStateException("旧内容存档失败，已取消覆盖：${e.message ?: "未知原因"}", e)
            }
        }

        val result = db.importSets(
            listOf(SetImport(title = title, source = oldSource ?: "局域网编辑器", papers = papers)),
            replace = replace || existing != null,
            // 原地更新题目行：收藏 / 笔记挂在题目 id 上，重建就等于把它们清空了
            reuseQuestionIds = true,
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
        // 图是按组编号分目录存的，组没了就一并清掉，不留孤儿文件
        runCatching { imageDirFor(setId).deleteRecursively() }
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
            // 前端会把打开时拿到的题目 id 原样带回来。带回来是为了让发布走「原地更新」，
            // 收藏 / 笔记挂在题目 id 上，删了重建就等于把用户攒的东西清空。
            // 只认旧组里真实存在的 id（校验在 importSets 里做），伪造的 id 不会命中别的组。
            id = o.optLong("id", 0L).coerceAtLeast(0L),
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

    /**
     * 存一张上传的图，返回题目里该写的相对路径 `images/<组编号>/<文件名>`。
     *
     * 图按试卷组（`paper_sets.id`）分目录：一组一个文件夹，同名图不会互相顶掉，
     * 删组时也能连图一起清。还没发布的组没有编号，先放 `images/draft/`，
     * 下次打开这一组时由 [editorText] 搬进正式目录。
     *
     * 类型按文件头判断，不信客户端给的扩展名——网页按扩展名回吐 Content-Type，
     * 传个 .html 上来就变成同源脚本了。体积也在这里卡住，别让大请求体落盘。
     *
     * @param replacePath 传了就原地换掉这张图（题目里的引用不用改），否则存成新文件。
     */
    fun saveImage(setId: Long, rawName: String, bytes: ByteArray, replacePath: String? = null): String {
        require(bytes.isNotEmpty()) { "图片内容为空" }
        require(bytes.size <= MAX_IMAGE_BYTES) { "图片太大了，单张上限 ${MAX_IMAGE_BYTES / 1024 / 1024}MB" }
        val ext = sniffImageExt(bytes) ?: throw IllegalArgumentException("只支持 png / jpg / gif / webp 图片")

        val dir = imageDirFor(setId)
        if (!replacePath.isNullOrBlank()) {
            val old = imageFile(replacePath)
                ?: throw IllegalArgumentException("要替换的图片不在资源目录里")
            if (old.parentFile?.canonicalFile != dir.canonicalFile) {
                throw IllegalArgumentException("只能替换本组目录里的图片")
            }
            old.writeBytes(bytes)
            return relativeImagePath(old)
        }

        dir.mkdirs()
        val base = rawName.substringAfterLast('/').substringAfterLast('\\')
        val stem = base.substringBeforeLast('.', base)
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
            .take(60)
            .ifBlank { "image" }

        var candidate = "$stem.$ext"
        var n = 1
        while (File(dir, candidate).exists()) {
            candidate = "$stem-$n.$ext"
            n++
        }
        File(dir, candidate).writeBytes(bytes)
        return relativeImagePath(File(dir, candidate))
    }

    /** 资源列表：这一组目录下已有的图（网页右栏「资源」页签用） */
    fun listResources(setId: Long): JSONObject {
        val dir = imageDirFor(setId)
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        return JSONObject()
            .put("setId", setId)
            .put("dir", "$IMAGE_PREFIX/${dir.name}")
            .put(
                "files", JSONArray().apply {
                    files.forEach { f ->
                        put(
                            JSONObject()
                                .put("path", relativeImagePath(f))
                                .put("name", f.name)
                                .put("size", f.length())
                                .put("mtime", f.lastModified())
                        )
                    }
                }
            )
    }

    /** 删一张图；删完题目里的引用会显示成掉的图，正好提醒去补 */
    fun deleteResource(path: String): JSONObject {
        val file = imageFile(path) ?: throw IllegalArgumentException("只能删资源目录里的图片")
        if (!file.isFile) throw IllegalArgumentException("这张图已经不在手机里了")
        if (!file.delete()) throw IllegalStateException("删除失败：文件可能被占用")
        return JSONObject().put("ok", true).put("path", path)
    }

    /** 认文件头，不认扩展名 */
    private fun sniffImageExt(b: ByteArray): String? {
        fun at(i: Int): Int = if (i < b.size) b[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "jpg"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38 -> "gif"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "webp"
            else -> null
        }
    }

    /** 解析要回给浏览器的图片：只认资源目录里的 `images/…`，绝对路径一律不服务 */
    fun resolveImage(path: String): File? = imageFile(path)?.takeIf { it.isFile }

    /** 一组一个图片文件夹；还没发布的组先落在 draft */
    private fun imageDirFor(setId: Long): File =
        File(imageDir, if (setId > 0) setId.toString() else DRAFT_DIR)

    private fun relativeImagePath(file: File): String {
        val rel = file.canonicalFile.relativeTo(imageDir.canonicalFile).invariantSeparatorsPath
        return "$IMAGE_PREFIX/$rel"
    }

    /**
     * `images/…` → 资源目录里的真实文件。
     *
     * `images/../../databases/qbank.db` 能一路翻到数据库和备份包，先把 `..` 挡掉；
     * 规范化之后再确认没跑出图片根目录。
     */
    private fun imageFile(path: String): File? {
        val p = path.trim().removePrefix("./")
        if (!p.startsWith("$IMAGE_PREFIX/")) return null
        val rel = p.substringAfter("$IMAGE_PREFIX/")
        if (rel.isEmpty() || rel.contains("..") || rel.contains('\\') || rel.startsWith("/")) return null
        val root = imageDir.canonicalFile
        val target = File(imageDir, rel).canonicalFile
        return if (target.path.startsWith(root.path + File.separator)) target else null
    }

    /**
     * 题目正文 → 编辑器里的写法：图片一律落成 `images/<setId>/<文件名>`。
     *
     * 老数据有两种来源要搬：平铺的 `images/x.png`、导入试卷包留下的绝对路径。
     * 搬不动的（文件已经丢了）原样留着，预览会显示成掉的图，一眼能看出问题。
     */
    private fun editorText(text: String, setId: Long): String {
        if (text.isBlank() || setId <= 0) return text
        var out = text
        if (out.contains("$IMAGE_PREFIX/")) {
            out = IMAGE_REF.replace(out) { m ->
                val file = File(imageDir, m.groupValues[1])
                importIntoSet(file, setId) ?: m.value
            }
        }
        val root = context.filesDir.absolutePath.trimEnd('/')
        if (out.contains(root)) {
            out = Regex("""${Regex.escape(root)}[^\s)"'<>]+""").replace(out) { m ->
                val file = File(m.value)
                if (file.isFile) importIntoSet(file, setId) ?: m.value else m.value
            }
        }
        return out
    }

    /**
     * 把一张图搬进 `images/<setId>/`，返回题目里该写的路径。
     * 已经在目标目录里就直接给相对路径；找不到文件返回 null。
     */
    private fun importIntoSet(file: File, setId: Long): String? {
        if (!file.isFile) return null
        val dir = imageDirFor(setId)
        if (file.parentFile?.canonicalFile == dir.canonicalFile) return relativeImagePath(file)
        val target = uniqueTarget(dir, file)
        if (!target.exists()) {
            dir.mkdirs()
            if (runCatching { file.copyTo(target) }.isFailure) return null
        }
        return relativeImagePath(target)
    }

    /** 同名同大小当作同一张图；内容不同就加 `-1` `-2` 后缀，别把别人的图顶掉 */
    private fun uniqueTarget(dir: File, source: File): File {
        var candidate = File(dir, source.name)
        if (!candidate.exists()) return candidate
        if (candidate.length() == source.length()) return candidate
        val stem = source.name.substringBeforeLast('.', source.name)
        val ext = source.name.substringAfterLast('.', "")
        var n = 1
        while (true) {
            candidate = File(dir, if (ext.isEmpty()) "$stem-$n" else "$stem-$n.$ext")
            if (!candidate.exists()) return candidate
            if (candidate.length() == source.length()) return candidate
            n++
        }
    }

    // ---------------------------------------------------------------- 图片迁移

    /**
     * 把题目里引用的图片整理进 `images/<组编号>/`，并把库里的路径改成新位置。
     *
     * 迁什么：
     * - 老的平铺图 `images/x.png`、导入试卷包留下的任意绝对路径，只要被题目引用就复制一份
     *   进本组目录（同名同大小视为同一张，不重复复制）；
     * - 只搬「有引用」的图：没人引用的散图判断不了归属，留在原处，报告里给出 `orphans`。
     *
     * 不改题目内容：只重写题干 / 材料 / 解析 / 选项里的图片路径，答案、难度、收藏都不动。
     * 源文件也不删——网页草稿、其他组、历史版本可能还指着它；删除整组时才清该组目录。
     *
     * 打开网页编辑器时本来就会按组迁移引用，这里补的是**不改题也能整理**的全库入口：
     * 老题库升级后跑一次，磁盘上的图就都按组编号归位了。
     *
     * @param setId 只整理这一组；传 0 整理所有组。
     */
    fun migrateImages(setId: Long = 0): JSONObject {
        val all = db.listPaperSets(withPapers = true)
        val sets = all.filter { it.id > 0 && (setId <= 0 || it.id == setId) }
        if (setId > 0 && sets.isEmpty()) {
            throw IllegalArgumentException("找不到这组试卷（可能已在 App 里被删掉）")
        }

        val stats = MigrationStats()
        // 同一道题可能同时挂在多份卷上（错题重练），只迁一次
        val seen = HashSet<Long>()

        sets.forEach { set ->
            val changed = ArrayList<Question>()
            set.papers.forEach { paper ->
                db.listQuestions(paper.id, Int.MAX_VALUE).forEach { q ->
                    if (!seen.add(q.id)) return@forEach
                    stats.questions++
                    migrateQuestion(q, set.id, stats)?.let { changed.add(it) }
                }
            }
            if (changed.isNotEmpty()) stats.updated += db.updateQuestionTexts(changed)
        }

        stats.orphans = imageDir.listFiles()?.count { it.isFile } ?: 0
        return JSONObject()
            .put("ok", true)
            .put("sets", sets.size)
            .put("questions", stats.questions)
            .put("updated", stats.updated)
            .put("moved", stats.moved)
            .put("reused", stats.reused)
            .put("missing", stats.missing)
            .put("orphans", stats.orphans)
    }

    /**
     * 逐题迁移：返回改写后的题目，没变化返回 null（不白写一遍库）。
     *
     * 走的是「复制 + 改路径」而不是「移动」：源文件可能是导入包解出来的共享图，
     * 直接移走会让别处引用它的题一起断图。
     */
    private fun migrateQuestion(q: Question, setId: Long, stats: MigrationStats): Question? {
        var changed = false
        fun fix(text: String): String {
            val next = migrateText(text, setId, stats)
            if (next != text) changed = true
            return next
        }

        val stem = fix(q.stem)
        val material = fix(q.material)
        val analysis = fix(q.analysis)
        val options = q.options.map { o ->
            val text = fix(o.text)
            if (text == o.text) o else Option(o.key, text)
        }
        if (!changed) return null
        return q.copy(stem = stem, material = material, analysis = analysis, options = options)
    }

    /** 相对路径和绝对路径各扫一遍；认不出的（文件已丢）原样留着并计数 */
    private fun migrateText(text: String, setId: Long, stats: MigrationStats): String {
        if (text.isBlank()) return text
        var out = text

        if (out.contains("$IMAGE_PREFIX/")) {
            out = IMAGE_REF.replace(out) { m ->
                val rel = m.groupValues[1]
                val file = File(imageDir, rel)
                if (!IMAGE_EXT.containsMatchIn(rel)) {
                    m.value
                } else {
                    val target = importIntoSet(file, setId)
                    if (target == null) {
                        stats.missing++
                        m.value
                    } else {
                        if (isInSetDir(file, setId)) stats.reused++ else stats.moved++
                        absoluteImagePath(target)
                    }
                }
            }
        }

        val root = context.filesDir.absolutePath.trimEnd('/')
        if (out.contains(root)) {
            out = Regex("""${Regex.escape(root)}[^\s)"'<>]+""").replace(out) { m ->
                val file = File(m.value)
                if (!file.isFile || !IMAGE_EXT.containsMatchIn(file.name)) {
                    if (!file.isFile) stats.missing++
                    m.value
                } else if (isInSetDir(file, setId)) {
                    // 上一遍刚写进本组目录的绝对路径，或是本来就归好位的图，保持原样
                    m.value
                } else {
                    val rel = importIntoSet(file, setId)
                    if (rel == null) {
                        stats.missing++
                        m.value
                    } else {
                        stats.moved++
                        absoluteImagePath(rel)
                    }
                }
            }
        }
        return out
    }

    private fun isInSetDir(file: File, setId: Long): Boolean =
        file.isFile && file.parentFile?.canonicalFile == imageDirFor(setId).canonicalFile

    private fun absoluteImagePath(rel: String): String =
        File(imageDir, rel.removePrefix("$IMAGE_PREFIX/")).absolutePath

    private class MigrationStats {
        var questions = 0
        var updated = 0
        var moved = 0
        var reused = 0
        var missing = 0
        var orphans = 0
    }

    private companion object {
        /** 题目里引用图片统一用这个前缀，后面跟组编号 */
        const val IMAGE_PREFIX = "images"

        /** 还没发布的组没有编号，图片先落这里，下次打开时搬进正式目录 */
        const val DRAFT_DIR = "draft"

        /** 网页编辑器单卷最多读多少题。再大就只读一部分，发布时会把剩下的冲掉 */
        const val MAX_EDIT_QUESTIONS = 5000

        /** 单张上传图的上限 */
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

        /**
         * `./images/x.png` / `images/子目录/x.png`。
         *
         * 前面的负向环视很关键：绝对路径 `…/qbank_pkg/images/x.png` 里的 `images/`
         * 前面是 `/`，不能被当成相对路径改写，否则老题里的图会全部指错地方。
         */
        val IMAGE_REF = Regex("""(?<![\w./\\-])(?:\./)?images/([^)\s"'<>]+)""")

        /** 迁移时只认图片文件，别把正文里出现的其他路径也搬进图片目录 */
        val IMAGE_EXT = Regex("""\.(png|jpe?g|gif|webp|bmp)$""", RegexOption.IGNORE_CASE)
    }
}
