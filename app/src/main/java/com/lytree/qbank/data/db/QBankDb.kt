package com.lytree.qbank.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.data.model.Option
import com.lytree.qbank.data.model.Paper
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.data.model.QuestionType
import com.lytree.qbank.data.model.Word
import com.lytree.qbank.data.model.WordTagCount
import com.lytree.qbank.engine.Judge
import com.lytree.qbank.engine.Policy

/**
 * 本地试卷存储。用原生 SQLiteOpenHelper，不引入 Room，避免注解处理器带来的构建复杂度。
 */
class QBankDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createPaperTables(db)
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE inks(question_id INTEGER PRIMARY KEY, strokes TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE notes(question_id INTEGER PRIMARY KEY, note TEXT DEFAULT '')")
        createV3Tables(db)
        createV4Tables(db)
        createV7Tables(db)
        createV8Columns(db)
        createV10Tables(db)
        createV11Tables(db)
    }

    /**
     * 题目 / 试卷 / 作答记录这几张核心表。
     *
     * 没有 `banks`：一份试卷就是一份卷，题目通过 `paper_questions` 挂到卷上。
     * 导入时一题一行写进 `questions`，同一份文件只落一份题；错题重练另建一份卷时
     * 复用原有题目行（多对多），收藏、笔记、笔迹因此能跨卷延续。
     */
    private fun createPaperTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS questions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                stem TEXT NOT NULL,
                material TEXT DEFAULT '',
                options TEXT DEFAULT '',
                answer TEXT NOT NULL,
                analysis TEXT DEFAULT '',
                knowledge TEXT DEFAULT '',
                difficulty INTEGER DEFAULT 0,
                region TEXT DEFAULT '',
                subject TEXT DEFAULT '',
                source TEXT DEFAULT '',
                year TEXT DEFAULT '',
                score REAL DEFAULT 1.0,
                keywords TEXT DEFAULT ''
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_q_knowledge ON questions(knowledge)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_q_type ON questions(type)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS papers(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                description TEXT DEFAULT '',
                created_at INTEGER DEFAULT 0,
                duration_min INTEGER DEFAULT 0,
                mode TEXT DEFAULT ''
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS paper_questions(
                paper_id INTEGER NOT NULL,
                question_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY(paper_id, question_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pq_question ON paper_questions(question_id)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS records(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                paper_id INTEGER DEFAULT 0,
                name TEXT DEFAULT '',
                mode TEXT DEFAULT '',
                started_at INTEGER DEFAULT 0,
                submitted_at INTEGER DEFAULT 0,
                duration_sec INTEGER DEFAULT 0,
                total_count INTEGER DEFAULT 0,
                answered_count INTEGER DEFAULT 0,
                correct_count INTEGER DEFAULT 0,
                partial_count INTEGER DEFAULT 0,
                score REAL DEFAULT 0.0,
                full_score REAL DEFAULT 0.0,
                marked TEXT DEFAULT '',
                status TEXT DEFAULT 'done'
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS record_answers(
                record_id INTEGER NOT NULL,
                question_id INTEGER NOT NULL,
                user_answer TEXT DEFAULT '',
                earned REAL DEFAULT 0.0,
                correct INTEGER DEFAULT 0,
                PRIMARY KEY(record_id, question_id)
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 无损升级：只补新增的表，不动用户已有的试卷数据
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS inks(question_id INTEGER PRIMARY KEY, strokes TEXT DEFAULT '')")
            db.execSQL("CREATE TABLE IF NOT EXISTS notes(question_id INTEGER PRIMARY KEY, note TEXT DEFAULT '')")
        }
        if (oldVersion < 3) createV3Tables(db)
        if (oldVersion < 4) createV4Tables(db)
        // v5 清重复标签，v6 再去掉「第 N 组」前缀，同一套规范化，跑一次就都覆盖
        if (oldVersion < 6) normalizeWordTags(db)
        if (oldVersion < 7) createV7Tables(db)
        if (oldVersion < 8) createV8Columns(db)
        // v9：取消「题库」这一层（banks），题目改为直接挂在试卷上。
        // 结构无法原地兼容，题库相关表整体重建；词库、背诵进度与设置不受影响。
        if (oldVersion < 9) {
            QUESTION_TABLES.forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            createPaperTables(db)
            createV3Tables(db)
            createV7Tables(db)
            db.execSQL("CREATE TABLE IF NOT EXISTS inks(question_id INTEGER PRIMARY KEY, strokes TEXT DEFAULT '')")
            db.execSQL("CREATE TABLE IF NOT EXISTS notes(question_id INTEGER PRIMARY KEY, note TEXT DEFAULT '')")
        }
        // v10：一次导入 = 一个标题（组）。旧数据的卷子按「导入自 xxx」来源回填成组，不丢卷
        if (oldVersion < 10) createV10Tables(db)
        // v11：记录从「交卷才生成」改成「开始答题就生成」，并补上每题用时
        if (oldVersion < 11) createV11Tables(db)
    }

    /** v3：收藏表 + 答题进度表（对齐参考应用「收藏跨会话保留 / 中途退出可续做」） */
    private fun createV3Tables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS favorites(question_id INTEGER PRIMARY KEY)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS exam_progress(
                paper_id INTEGER PRIMARY KEY,
                answers TEXT DEFAULT '',
                marked TEXT DEFAULT '',
                elapsed_sec INTEGER DEFAULT 0,
                updated_at INTEGER DEFAULT 0
            )""".trimIndent()
        )
    }

    /** v4：词库表（成语 + 实词同表，按拼音首字母索引） */
    private fun createV4Tables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS words(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                pinyin TEXT DEFAULT '',
                initial TEXT DEFAULT '',
                category TEXT DEFAULT '',
                tag TEXT DEFAULT '',
                meaning TEXT DEFAULT '',
                example TEXT DEFAULT '',
                source TEXT DEFAULT '',
                created_at INTEGER DEFAULT 0
            )""".trimIndent()
        )
        // 词条唯一：重复导入走覆盖，不会越导越多
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_word_text ON words(text)")
        // 列表查询顺序与索引一致：首字母 → 全拼 → 词条
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_word_sort ON words(initial, pinyin)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_word_category ON words(category)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_word_tag ON words(tag)")
    }

    /**
     * v7：遮盖层笔迹改为跟随**本次作答记录**保存，且题目与材料分开锚定。
     *
     * 旧 `inks` 表是「一题一份、跨所有练习共享」，交卷后换一轮练习还会看到上一轮的圈画；
     * 材料块的圈画也只能按题干定位，材料排版一变就偏移。新表按
     * `(record_id, kind, target_id)` 存：kind = question / material，
     * target_id 是题目 id 或材料组首题 id，同一记录同一目标只保留最新一份笔迹。
     */
    private fun createV7Tables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS record_inks(
                record_id INTEGER NOT NULL,
                kind TEXT NOT NULL,
                target_id INTEGER NOT NULL,
                strokes TEXT DEFAULT '',
                updated_at INTEGER DEFAULT 0,
                PRIMARY KEY(record_id, kind, target_id)
            )""".trimIndent()
        )
    }

    /**
     * v8：标记（细线星）跟随**做题记录**留档。
     *
     * 以前标记只写 `exam_progress.marked`（未交卷进度），交卷时 `clearProgress` 一并删掉，
     * 回头翻记录看不出当时标了哪几题。`records.marked` 存逗号串（与进度表同一种编码），
     * 交卷时一并落库；旧记录补列后是空串，等价于「那次没标记」。
     */
    private fun createV8Columns(db: SQLiteDatabase) {
        if (!hasColumn(db, "records", "marked")) {
            db.execSQL("ALTER TABLE records ADD COLUMN marked TEXT DEFAULT ''")
        }
    }

    /**
     * v10：**一次导入 = 一个标题**。
     *
     * 以前导入只落「试卷」，多份文件导进来全平铺在一页里，谁也说不清哪几份是同一批。
     * 新增 `paper_sets`（导入组）后，试卷挂到组上：导 3 个文件就有 3 个标题，
     * 每个标题下面才是那份文件拆出来的卷子，列表按「标题 → 试卷 → 题目」三级展开。
     *
     * 旧数据没有组信息，按卷子 description 里的「导入自 xxx.xlsx」回填——
     * 同一份文件导出来的卷天然落进同一组，实在没来源的归到「历史试卷」。
     */
    private fun createV10Tables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS paper_sets(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                source TEXT DEFAULT '',
                created_at INTEGER DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_set_created ON paper_sets(created_at)")
        if (!hasColumn(db, "papers", "set_id")) {
            db.execSQL("ALTER TABLE papers ADD COLUMN set_id INTEGER DEFAULT 0")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_paper_set ON papers(set_id)")
        backfillPaperSets(db)
    }

    /**
     * v11：**记录从「开始答题」就开始，而不是交卷才生成**；同时记下**每道题各花了多久**。
     *
     * 旧实现只在交卷时插一行 `records`，中途退出等于没练过——记录页看不到、也没法回头接着做。
     * 现在进卷即落一条 `status = 'ongoing'`（未完成）的记录：作答数、已用时随答题实时回写，
     * 交卷改成 `status = 'done'`（已完成）并补齐成绩；重新开始时上一轮改记 `abandoned`
     * （同样归到「未完成」，只是不再续做），这样任何一次进场都在记录里留痕。
     *
     * 每题用时单开一张 `record_question_times`：一题累计一行（切题时把上一题的秒数加进去），
     * 交卷后成绩报告里能逐题回看，看哪道题磨了很久。
     */
    private fun createV11Tables(db: SQLiteDatabase) {
        if (!hasColumn(db, "records", "status")) {
            db.execSQL("ALTER TABLE records ADD COLUMN status TEXT DEFAULT ''")
        }
        // 老记录都是「交卷才生成」的，一律回填成已完成，别让它们凭空变成未完成
        db.execSQL(
            """UPDATE records SET status = CASE WHEN submitted_at > 0 THEN '$STATUS_DONE' ELSE '$STATUS_ONGOING' END
               WHERE IFNULL(status, '') = ''""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_record_status ON records(status)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS record_question_times(
                record_id INTEGER NOT NULL,
                question_id INTEGER NOT NULL,
                seconds INTEGER DEFAULT 0,
                updated_at INTEGER DEFAULT 0,
                PRIMARY KEY(record_id, question_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rqt_record ON record_question_times(record_id)")
    }

    /** 旧卷子按来源文件回填成组：同一份「导入自 X」的卷归一组，没有来源的落到「历史试卷」 */
    private fun backfillPaperSets(db: SQLiteDatabase) {
        data class Row(val id: Long, val source: String, val createdAt: Long)

        val rows = ArrayList<Row>()
        db.rawQuery(
            "SELECT id, IFNULL(description,''), IFNULL(created_at,0) FROM papers WHERE IFNULL(set_id,0) = 0",
            null
        ).use { c ->
            while (c.moveToNext()) rows.add(Row(c.getLong(0), c.getString(1) ?: "", c.getLong(2)))
        }
        if (rows.isEmpty()) return

        val groups = LinkedHashMap<String, MutableList<Row>>()
        rows.forEach { r -> groups.getOrPut(setTitleOf(r.source)) { ArrayList() }.add(r) }

        groups.forEach { (title, list) ->
            val created = list.map { it.createdAt }.filter { it > 0 }.minOrNull()
                ?: System.currentTimeMillis()
            val setId = db.insert("paper_sets", null, ContentValues().apply {
                put("title", title)
                put("source", list.first().source)
                put("created_at", created)
            })
            list.forEach { r ->
                db.execSQL(
                    "UPDATE papers SET set_id = ? WHERE id = ?",
                    arrayOf<Any>(setId, r.id)
                )
            }
        }
    }

    /** 补列前先查一次，重复执行不会因为「列已存在」抛异常 */
    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIndex = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) if (c.getString(nameIndex) == column) return true
        }
        return false
    }

    /**
     * v5：清掉历史词条里重复拼接的细分标签（「A·对比辨析·A」）；
     * v6：去掉标签里的「第 N 组」前缀，只留真正的细分名。
     *
     * 两件事都是重写 tag 列，索性全表扫一遍统一走 [Word.normalizeTag]，
     * 其它字段与用户试卷数据一律不动。
     */
    private fun normalizeWordTags(db: SQLiteDatabase) {
        val fixes = ArrayList<Pair<Long, String>>()
        db.rawQuery("SELECT id, tag FROM words WHERE tag <> ''", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val old = c.getString(1) ?: ""
                val fixed = Word.normalizeTag(old)
                if (fixed != old) fixes.add(id to fixed)
            }
        }
        fixes.forEach { (id, tag) ->
            db.execSQL("UPDATE words SET tag = ? WHERE id = ?", arrayOf<Any>(tag, id))
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_word_tag ON words(tag)")
    }

    // ---------------- Word（词库） ----------------

    /**
     * 批量写入词条。
     *
     * 以「词条文本」为唯一键做 upsert：同名词条更新释义/分类/拼音，不会产生重复行。
     * [replaceAll] 为 true 时先清空（「整库重导」），适合词表整体换版本的场景。
     */
    fun upsertWords(words: List<Word>, replaceAll: Boolean = false): Int {
        if (words.isEmpty() && !replaceAll) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replaceAll) db.delete("words", null, null)
            var n = 0
            for (w in words) {
                if (w.text.isBlank()) continue
                val cv = ContentValues().apply {
                    put("text", w.text)
                    put("pinyin", w.pinyin)
                    put("initial", w.initial.ifBlank { "#" })
                    put("category", w.category)
                    // 标签按 · 拆段去重，避免同一来源重复追加后拼出「A·对比辨析·A」
                    put("tag", Word.normalizeTag(w.tag))
                    put("meaning", w.meaning)
                    put("example", w.example)
                    put("source", w.source)
                    put("created_at", if (w.createdAt > 0) w.createdAt else System.currentTimeMillis())
                }
                n += if (db.insertWithOnConflict("words", null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L) 1 else 0
            }
            db.setTransactionSuccessful()
            return n
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 按拼音首字母排序的词条列表。
     *
     * [category] 为空表示不限分类；[tags] 非空时只保留标签段命中其中之一的词条
     * （标签之间是「或」，与分类是「且」）。空字符串代表「未标注标签」。
     * 标签按段匹配：「对比辨析」既能命中只标了这个的词，也能命中「对比辨析·第十组」。
     * [query] 非空时在词条 / 拼音 / 释义 / 标签上做包含匹配（成语汇总并进本页后，
     * 搜索就在这一层做，列表页不必自己过滤）。
     */
    fun listWords(
        category: String? = null,
        tags: Set<String> = emptySet(),
        query: String = "",
        limit: Int = 20000
    ): List<Word> {
        val out = ArrayList<Word>()
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        if (!category.isNullOrBlank()) {
            clauses.add("category = ?")
            args.add(category)
        }
        if (tags.isNotEmpty()) {
            val ors = ArrayList<String>()
            tags.forEach { raw ->
                val t = raw.trim()
                if (t.isEmpty()) {
                    // 未标注：tag 为空串（历史数据里也可能是只有 · 的脏值，一并算进来）
                    ors.add("(tag = '' OR tag IS NULL)")
                } else {
                    ors.add("tag = ?")
                    args.add(t)
                    ors.add("tag LIKE ?")
                    args.add("$t${Word.TAG_SEP}%")
                    ors.add("tag LIKE ?")
                    args.add("%${Word.TAG_SEP}$t")
                    ors.add("tag LIKE ?")
                    args.add("%${Word.TAG_SEP}$t${Word.TAG_SEP}%")
                }
            }
            clauses.add("(" + ors.joinToString(" OR ") + ")")
        }
        val keyword = query.trim()
        if (keyword.isNotEmpty()) {
            val like = "%" + keyword.replace("%", "").replace("_", "") + "%"
            clauses.add("(text LIKE ? OR pinyin LIKE ? OR meaning LIKE ? OR tag LIKE ?)")
            repeat(4) { args.add(like) }
        }
        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        readableDatabase.rawQuery(
            "SELECT * FROM words $where ORDER BY initial, pinyin, text LIMIT $limit",
            if (args.isEmpty()) null else args.toTypedArray()
        ).use { c -> while (c.moveToNext()) out.add(readWord(c)) }
        return out
    }

    /**
     * 标签清单（含条数），按条数从多到少；「未标注」永远排最后。
     *
     * 标签按 · 拆成段统计——词条同时挂在多个分组下时，每个分组各记一次，
     * 这样点「对比辨析」能把所有带它的词都筛出来，而不是只命中裸标签的那批。
     * [category] 非空时只统计该分类下的标签——筛选弹窗跟着当前分类走。
     */
    fun listWordTags(category: String? = null): List<WordTagCount> {
        val where = if (category.isNullOrBlank()) "" else "WHERE category = ?"
        val args = if (category.isNullOrBlank()) null else arrayOf(category)
        val counts = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT tag FROM words $where", args).use { c ->
            while (c.moveToNext()) {
                Word.splitTags(c.getString(0)).forEach { t ->
                    counts[t] = (counts[t] ?: 0) + 1
                }
            }
        }
        val out = counts.map { WordTagCount(it.key, it.value) }
        return out.sortedWith(
            compareByDescending<WordTagCount> { it.name.isNotEmpty() }
                .thenByDescending { it.count }
                .thenBy { it.name }
        )
    }

    /** 分类计数：key 为分类 code（idiom / word / other），空词库返回空表 */
    fun countWordsByCategory(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        readableDatabase.rawQuery("SELECT category, COUNT(*) FROM words GROUP BY category", null)
            .use { c -> while (c.moveToNext()) out[c.getString(0) ?: ""] = c.getInt(1) }
        return out
    }

    fun countWords(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM words", null)
            .use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun deleteWord(id: Long) {
        writableDatabase.delete("words", "id = ?", arrayOf(id.toString()))
    }

    fun clearWords() {
        writableDatabase.delete("words", null, null)
    }

    private fun readWord(c: Cursor) = Word(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        text = c.getString(c.getColumnIndexOrThrow("text")) ?: "",
        pinyin = c.getString(c.getColumnIndexOrThrow("pinyin")) ?: "",
        initial = c.getString(c.getColumnIndexOrThrow("initial")) ?: "#",
        category = c.getString(c.getColumnIndexOrThrow("category")) ?: "",
        tag = c.getString(c.getColumnIndexOrThrow("tag")) ?: "",
        meaning = c.getString(c.getColumnIndexOrThrow("meaning")) ?: "",
        example = c.getString(c.getColumnIndexOrThrow("example")) ?: "",
        source = c.getString(c.getColumnIndexOrThrow("source")) ?: "",
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
    )

    // ---------------- Question ----------------

    private fun questionValues(q: Question): ContentValues = ContentValues().apply {
        put("type", q.type.code)
        put("stem", q.stem)
        put("material", q.material)
        put("options", q.optionsJson())
        put("answer", q.answer)
        put("analysis", q.analysis)
        put("knowledge", q.knowledge)
        put("difficulty", q.difficulty)
        put("region", q.region)
        put("subject", q.subject)
        put("source", q.source)
        put("year", q.year)
        put("score", q.score)
        put("keywords", q.keywords)
    }

    /** 某份试卷的题目，按卷面顺序 */
    fun listQuestions(paperId: Long, limit: Int = 5000): List<Question> {
        val out = ArrayList<Question>()
        readableDatabase.rawQuery(
            """SELECT q.* FROM paper_questions pq
               JOIN questions q ON q.id = pq.question_id
               WHERE pq.paper_id = ? ORDER BY pq.position LIMIT ?""",
            arrayOf(paperId.toString(), limit.toString())
        ).use { c -> while (c.moveToNext()) out.add(readQuestion(c)) }
        return out
    }

    fun countQuestions(paperId: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM paper_questions WHERE paper_id = ?", arrayOf(paperId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun getQuestion(id: Long): Question? {
        readableDatabase.rawQuery("SELECT * FROM questions WHERE id = ?", arrayOf(id.toString()))
            .use { c -> return if (c.moveToFirst()) readQuestion(c) else null }
    }

    private fun readQuestion(c: Cursor) = Question(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        type = QuestionType.parse(c.getString(c.getColumnIndexOrThrow("type"))) ?: QuestionType.SINGLE,
        stem = c.getString(c.getColumnIndexOrThrow("stem")) ?: "",
        material = c.getString(c.getColumnIndexOrThrow("material")) ?: "",
        options = Question.optionsFromJson(c.getString(c.getColumnIndexOrThrow("options")) ?: ""),
        answer = c.getString(c.getColumnIndexOrThrow("answer")) ?: "",
        analysis = c.getString(c.getColumnIndexOrThrow("analysis")) ?: "",
        knowledge = c.getString(c.getColumnIndexOrThrow("knowledge")) ?: "",
        difficulty = c.getInt(c.getColumnIndexOrThrow("difficulty")),
        region = c.getString(c.getColumnIndexOrThrow("region")) ?: "",
        subject = c.getString(c.getColumnIndexOrThrow("subject")) ?: "",
        source = c.getString(c.getColumnIndexOrThrow("source")) ?: "",
        year = c.getString(c.getColumnIndexOrThrow("year")) ?: "",
        score = c.getDouble(c.getColumnIndexOrThrow("score")),
        keywords = c.getString(c.getColumnIndexOrThrow("keywords")) ?: ""
    )

    // ---------------- Paper ----------------

    /**
     * 导入即建卷：一份试卷落进一个**新建的组**（标题 = 卷名）。
     *
     * 单元测试与「单卷直建」的调用方走这里；正式导入页走 [importSets]，
     * 因为一次导入可能是一组里挂好几份卷。
     */
    fun importPaper(
        name: String,
        description: String,
        durationMin: Int,
        questions: List<Question>
    ): ImportResult {
        val sets = importSets(
            listOf(SetImport(name, description, listOf(PaperImport(name, description, durationMin, questions)))),
            replace = false
        )
        return sets.firstOrNull()?.papers?.firstOrNull() ?: ImportResult(0, 0, name)
    }

    /**
     * 按「导入组」一次写入：一组 = 一次导入 = 列表里的一个标题。
     *
     * 全程一个事务：中途失败不会留下「前几组建好了、后面没了」的残局。
     * [replace] 为真时先清掉**同标题**的旧组（连同组里的卷、题目、作答记录），
     * 于是同一个文件重导一次不会越堆越多，也不会把别的组误删。
     */
    fun importSets(sets: List<SetImport>, replace: Boolean = true): List<SetImportResult> {
        val usable = sets
            .map { s -> s.copy(papers = s.papers.filter { it.questions.isNotEmpty() }) }
            .filter { it.papers.isNotEmpty() }
        if (usable.isEmpty()) return emptyList()

        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replace) {
                val titles = usable.map { it.title }.toSet()
                listPaperSets(withPapers = false)
                    .filter { it.id > 0 && it.title in titles }
                    .forEach { deletePaperSet(it.id) }
            }
            val out = ArrayList<SetImportResult>(usable.size)
            usable.forEach { s ->
                val setId = db.insert("paper_sets", null, setValues(s.title, s.source))
                val papers = s.papers.map { p ->
                    insertPaper(db, setId, p.name, p.description, p.durationMin, p.questions)
                }
                out.add(SetImportResult(setId, s.title, papers))
            }
            db.setTransactionSuccessful()
            return out
        } finally {
            db.endTransaction()
        }
    }

    /** 建卷 + 挂题（不开事务，由调用方决定事务边界） */
    private fun insertPaper(
        db: SQLiteDatabase,
        setId: Long,
        name: String,
        description: String,
        durationMin: Int,
        questions: List<Question>
    ): ImportResult {
        val paperId = db.insert(
            "papers", null,
            paperValues(name, description, durationMin, MODE_IMPORT, setId)
        )
        val ids = ArrayList<Long>(questions.size)
        for (q in questions) {
            val qid = db.insert("questions", null, questionValues(q))
            if (qid != -1L) ids.add(qid)
        }
        ids.forEachIndexed { i, qid ->
            db.insert("paper_questions", null, ContentValues().apply {
                put("paper_id", paperId)
                put("question_id", qid)
                put("position", i)
            })
        }
        return ImportResult(paperId, ids.size, name)
    }

    /**
     * 用给定的题目（按传入顺序）生成一份新试卷——「错题重练」用：从练习记录里挑出答错的题再练一遍。
     *
     * 复用原有题目行，不复制题干：同一道题可以同时属于原卷和重练卷，
     * 收藏 / 笔记 / 笔迹按题目 id 挂，练第二轮时照样在。
     */
    fun createPaperFromQuestions(
        paperName: String,
        questionIds: List<Long>,
        durationMin: Int = 0,
        description: String = ""
    ): Long {
        if (questionIds.isEmpty()) return 0L
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 重练卷不是导入来的，统一挂在「错题重练」这一组下面，别把导入标题混脏
            val setId = findOrCreateSet(RETRY_SET_TITLE, "错题重练")
            val pid = db.insert(
                "papers", null,
                paperValues(paperName, description, durationMin, MODE_RETRY, setId)
            )
            questionIds.forEachIndexed { i, qid ->
                db.insert("paper_questions", null, ContentValues().apply {
                    put("paper_id", pid)
                    put("question_id", qid)
                    put("position", i)
                })
            }
            db.setTransactionSuccessful()
            return pid
        } finally {
            db.endTransaction()
        }
    }

    private fun paperValues(
        name: String,
        description: String,
        durationMin: Int,
        mode: String,
        setId: Long = 0
    ): ContentValues = ContentValues().apply {
        put("name", name)
        put("description", description)
        put("created_at", System.currentTimeMillis())
        put("duration_min", durationMin)
        put("mode", mode)
        put("set_id", setId)
    }

    private fun setValues(title: String, source: String): ContentValues = ContentValues().apply {
        put("title", title)
        put("source", source)
        put("created_at", System.currentTimeMillis())
    }

    // ---------------- PaperSet（导入组 / 标题） ----------------

    /** 组 id；没有就新建一个（错题重练这类的固定组用） */
    private fun findOrCreateSet(title: String, source: String): Long {
        val db = writableDatabase
        db.rawQuery("SELECT id FROM paper_sets WHERE title = ? LIMIT 1", arrayOf(title)).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return db.insert("paper_sets", null, setValues(title, source))
    }

    fun findPaperSetByTitle(title: String): PaperSet? =
        listPaperSets(withPapers = false).firstOrNull { it.title == title }

    /**
     * 列表用：组 + 组内试卷（不含题目正文）。
     *
     * 按导入时间倒序，最新的那批在最上面；组内按建卷顺序（id 升序），
     * 也就是文件里第 1 个工作表排第 1，不会因为先插进去的时间戳差几毫秒而乱序。
     * `set_id = 0` 的漏网卷子兜底归到「未分组」，不让它从列表里消失。
     */
    fun listPaperSets(withPapers: Boolean = true): List<PaperSet> {
        val sets = ArrayList<PaperSet>()
        readableDatabase.rawQuery(
            """SELECT s.id, s.title, s.source, s.created_at,
                      (SELECT COUNT(*) FROM papers p WHERE p.set_id = s.id),
                      (SELECT COUNT(*) FROM paper_questions pq JOIN papers p ON p.id = pq.paper_id
                         WHERE p.set_id = s.id),
                      (SELECT COUNT(*) FROM paper_questions pq JOIN papers p ON p.id = pq.paper_id
                         JOIN questions q ON q.id = pq.question_id
                         WHERE p.set_id = s.id AND TRIM(IFNULL(q.answer, '')) = '')
               FROM paper_sets s ORDER BY s.created_at DESC, s.id DESC""",
            null
        ).use { c ->
            while (c.moveToNext()) {
                sets.add(
                    PaperSet(
                        id = c.getLong(0),
                        title = c.getString(1) ?: "",
                        source = c.getString(2) ?: "",
                        createdAt = c.getLong(3),
                        paperCount = c.getInt(4),
                        questionCount = c.getInt(5),
                        missingAnswers = c.getInt(6)
                    )
                )
            }
        }
        if (!withPapers) return sets

        val papers = listPapers()
        val known = sets.map { it.id }.toHashSet()
        val orphans = papers.filter { it.setId !in known }
        val grouped = papers.groupBy { it.setId }
        val out = sets.map { s ->
            s.copy(papers = grouped[s.id].orEmpty().sortedBy { it.id })
        }.toMutableList()
        if (orphans.isNotEmpty()) {
            out.add(
                PaperSet(
                    id = 0L,
                    title = UNGROUPED_SET_TITLE,
                    source = "",
                    createdAt = orphans.maxOfOrNull { it.createdAt } ?: 0L,
                    paperCount = orphans.size,
                    questionCount = orphans.sumOf { it.questionCount },
                    missingAnswers = orphans.sumOf { it.missingAnswers },
                    papers = orphans.sortedBy { it.id }
                )
            )
        }
        return out
    }

    /** 改导入组的标题（只是换个名字，卷与记录都不动） */
    fun renamePaperSet(id: Long, title: String) {
        val name = title.trim()
        if (id <= 0 || name.isEmpty()) return
        writableDatabase.update(
            "paper_sets",
            ContentValues().apply { put("title", name) },
            "id = ?",
            arrayOf(id.toString())
        )
    }

    /** 删掉一整组：组里的每份卷连同作答记录一起走，最后删组本身 */
    fun deletePaperSet(id: Long) {
        if (id <= 0) return
        val paperIds = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT id FROM papers WHERE set_id = ?", arrayOf(id.toString()))
            .use { c -> while (c.moveToNext()) paperIds.add(c.getLong(0)) }
        paperIds.forEach { deletePaper(it, keepSetCleanup = true) }
        writableDatabase.delete("paper_sets", "id = ?", arrayOf(id.toString()))
    }

    fun listPapers(): List<Paper> {
        val out = ArrayList<Paper>()
        readableDatabase.rawQuery(
            """SELECT p.id, p.name, p.description, p.created_at, p.duration_min, p.mode,
                      (SELECT COUNT(*) FROM paper_questions pq WHERE pq.paper_id = p.id),
                      (SELECT COUNT(*) FROM paper_questions pq2
                         JOIN questions q2 ON q2.id = pq2.question_id
                         WHERE pq2.paper_id = p.id AND TRIM(IFNULL(q2.answer, '')) = ''),
                      IFNULL(p.set_id, 0)
               FROM papers p ORDER BY p.created_at DESC""", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Paper(
                        id = c.getLong(0), name = c.getString(1) ?: "",
                        description = c.getString(2) ?: "",
                        createdAt = c.getLong(3), durationMin = c.getInt(4), mode = c.getString(5) ?: "",
                        questionCount = c.getInt(6),
                        missingAnswers = c.getInt(7),
                        setId = c.getLong(8),
                        questions = emptyList()
                    )
                )
            }
        }
        return out
    }

    fun getPaper(id: Long): Paper? {
        val p = listPapers().firstOrNull { it.id == id } ?: return null
        val qs = ArrayList<Question>()
        readableDatabase.rawQuery(
            """SELECT q.* FROM paper_questions pq
               JOIN questions q ON q.id = pq.question_id
               WHERE pq.paper_id = ? ORDER BY pq.position""",
            arrayOf(id.toString())
        ).use { c -> while (c.moveToNext()) qs.add(readQuestion(c)) }
        return p.copy(questions = qs)
    }

    /** 同名试卷（导入重名时用来提示覆盖） */
    fun findPaperByName(name: String): Paper? =
        listPapers().firstOrNull { it.name == name }

    /**
     * 删除一份试卷：卷面关系、只属于这份卷的题目、以及这份卷的作答记录一起清掉。
     *
     * 「只属于」指没有别的卷引用它——错题重练复用原卷的题目行，那些题要留着。
     * 收藏 / 笔记 / 笔迹按题目 id 挂，题目真的没人用了才一并删除，不留孤儿行。
     */
    fun deletePaper(id: Long) = deletePaper(id, keepSetCleanup = false)

    /**
     * @param keepSetCleanup 为真时不动组（整组删除流程在最后统一删组行），
     *   为假时删完这份卷如果组里一卷不剩就把空组一起清掉，列表上不留空标题
     */
    private fun deletePaper(id: Long, keepSetCleanup: Boolean) {
        val db = writableDatabase
        val setId = db.rawQuery("SELECT IFNULL(set_id,0) FROM papers WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        val args = arrayOf<Any>(id, id)
        val orphanQuestions = """
            SELECT question_id FROM paper_questions
            WHERE paper_id = ? AND question_id NOT IN
                  (SELECT question_id FROM paper_questions WHERE paper_id <> ?)
        """.trimIndent()
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM record_answers WHERE record_id IN (SELECT id FROM records WHERE paper_id = ?)",
                arrayOf<Any>(id)
            )
            db.execSQL(
                "DELETE FROM record_inks WHERE record_id IN (SELECT id FROM records WHERE paper_id = ?)",
                arrayOf<Any>(id)
            )
            db.execSQL(
                "DELETE FROM record_question_times WHERE record_id IN (SELECT id FROM records WHERE paper_id = ?)",
                arrayOf<Any>(id)
            )
            db.execSQL("DELETE FROM records WHERE paper_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM exam_progress WHERE paper_id = ?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM favorites WHERE question_id IN ($orphanQuestions)", args)
            db.execSQL("DELETE FROM notes WHERE question_id IN ($orphanQuestions)", args)
            db.execSQL("DELETE FROM questions WHERE id IN ($orphanQuestions)", args)
            db.delete("paper_questions", "paper_id = ?", arrayOf(id.toString()))
            db.delete("papers", "id = ?", arrayOf(id.toString()))
            if (!keepSetCleanup && setId > 0) {
                val left = db.rawQuery(
                    "SELECT COUNT(*) FROM papers WHERE set_id = ?", arrayOf(setId.toString())
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                if (left == 0) db.delete("paper_sets", "id = ?", arrayOf(setId.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 批量补充标准答案和/或解析。
     *
     * 用于「试卷先导入、答案后补」：
     * - 答案非空时写入答案；解析非空时写入解析——两个开关相互独立，
     *   避免「题号 + 答案」表冲掉已有解析，也支持「题号 + 解析」的解析文件只更新解析；
     * - 两个都为空的条目跳过。
     */
    fun updateAnswers(updates: List<AnswerUpdate>): Int {
        if (updates.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            var n = 0
            for (u in updates) {
                val cv = ContentValues()
                if (u.answer.isNotBlank()) cv.put("answer", u.answer)
                if (u.analysis.isNotBlank()) cv.put("analysis", u.analysis)
                if (cv.size() == 0) continue
                n += db.update("questions", cv, "id = ?", arrayOf(u.questionId.toString()))
            }
            db.setTransactionSuccessful()
            return n
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 手动补录答案（逐题填写，不经过答案文件）。
     *
     * 与 [updateAnswers] 的差别：**允许写入空串**——用户在补录页把某题答案清空时，
     * 这道题要退回「待补答案」，而不是留着上一次的答案继续判分。
     */
    fun setAnswersManually(updates: List<AnswerUpdate>): Int {
        if (updates.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            var n = 0
            for (u in updates) {
                val cv = ContentValues().apply { put("answer", u.answer) }
                if (u.analysis.isNotBlank()) cv.put("analysis", u.analysis)
                n += db.update("questions", cv, "id = ?", arrayOf(u.questionId.toString()))
            }
            db.setTransactionSuccessful()
            return n
        } finally {
            db.endTransaction()
        }
    }

    /** 某份试卷里还缺标准答案的题数 */
    fun countMissingAnswers(paperId: Long): Int {
        readableDatabase.rawQuery(
            """SELECT COUNT(*) FROM paper_questions pq
               JOIN questions q ON q.id = pq.question_id
               WHERE pq.paper_id = ? AND TRIM(IFNULL(q.answer, '')) = ''""",
            arrayOf(paperId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /**
     * 用当前的标准答案重判一条练习记录。
     *
     * 补完答案后调用：保留用户原来的作答，只重算得分与对错，
     * 所以「先做完、后补答案」的卷子能直接出对比结果，不需要重做一遍。
     */
    fun rejudgeRecord(recordId: Long): ExamRecord? {
        val record = getRecord(recordId) ?: return null
        val policy = getPolicy()
        val results = record.results.map { Judge.judge(it.question, it.userAnswer, policy) }
        val summary = Judge.summarize(results)

        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in results) {
                db.update(
                    "record_answers",
                    ContentValues().apply {
                        put("earned", r.earned)
                        put("correct", if (r.correct) 1 else 0)
                    },
                    "record_id = ? AND question_id = ?",
                    arrayOf(recordId.toString(), r.question.id.toString())
                )
            }
            db.update(
                "records",
                ContentValues().apply {
                    put("answered_count", summary.answered)
                    put("correct_count", summary.correct)
                    put("partial_count", summary.partial)
                    put("score", summary.score)
                    put("full_score", summary.fullScore)
                },
                "id = ?",
                arrayOf(recordId.toString())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return getRecord(recordId)
    }

    // ---------------- Record ----------------

    /** 直接落一条已交卷的记录（导入 / 测试用；答题页走草稿 → [finalizeRecord]） */
    fun insertRecord(record: ExamRecord, results: List<com.lytree.qbank.data.model.AnswerResult>): Long =
        finalizeRecord(0L, record, results)

    /**
     * 取本卷当前未交卷的草稿记录，没有就新建一条。
     *
     * **进卷即建记录**：以前正式记录是交卷时才生成的，中途退出等于没练过，记录页也查不到；
     * 现在一进来就落一条 `status = 'ongoing'` 的记录，作答数 / 已用时随答题回写，
     * 交卷时再由 [finalizeRecord] 转成 `done`——所以「未完成」也能在记录列表里看到并点回来接着做。
     *
     * 标注也挂在它上面：`source` 存在 `mode` 列，用来区分答题页 / 分屏页各自的草稿，
     * 两个页面不会互相覆盖对方的笔迹。
     */
    fun ensureDraftRecord(
        paperId: Long,
        source: String,
        startedAt: Long,
        name: String = ""
    ): Long {
        // 按试卷找「进行中」，不看来源：答题页与分屏页是同一场练习，不能各挂一条未完成
        readableDatabase.rawQuery(
            "SELECT id FROM records WHERE paper_id = ? AND submitted_at = 0 AND status = ? ORDER BY id DESC LIMIT 1",
            arrayOf(paperId.toString(), STATUS_ONGOING)
        ).use { c -> if (c.moveToFirst()) return c.getLong(0) }

        val cv = ContentValues().apply {
            put("paper_id", paperId)
            put("name", name.ifBlank { paperName(paperId).ifBlank { "答题记录" } })
            put("mode", source)
            put("started_at", startedAt)
            put("submitted_at", 0)
            put("total_count", countQuestions(paperId))
            put("status", STATUS_ONGOING)
        }
        return writableDatabase.insert("records", null, cv)
    }

    /** 卷名（记录标题用它，别让列表里全是「练习记录」） */
    fun paperName(paperId: Long): String =
        readableDatabase.rawQuery("SELECT name FROM papers WHERE id = ?", arrayOf(paperId.toString()))
            .use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }

    /**
     * 一条记录**第一次开始**的时间。
     *
     * 中途退出再进卷复用的是同一条记录，`started_at` 要保住最初那一刻——
     * 否则每次续做都把「开始时间」刷新成今天，记录列表里的时间就全乱了。
     */
    fun recordStartedAt(recordId: Long): Long =
        readableDatabase.rawQuery(
            "SELECT IFNULL(started_at, 0) FROM records WHERE id = ?", arrayOf(recordId.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /**
     * 放弃本轮：把未交卷的草稿记成「未完成」而不是删掉。
     *
     * 「重新开始」时会走这里——上一轮已经花掉的时间、答过的题得留在记录里，
     * 但不能再被 `ensureDraftRecord` 当成「进行中」捡回来续做。
     */
    fun abandonDraftRecords(paperId: Long, source: String? = null) {
        val cv = ContentValues().apply { put("status", STATUS_ABANDONED) }
        val where = StringBuilder("paper_id = ? AND submitted_at = 0 AND status = ?")
        val args = arrayListOf(paperId.toString(), STATUS_ONGOING)
        if (!source.isNullOrBlank()) {
            where.append(" AND mode = ?")
            args.add(source)
        }
        writableDatabase.update("records", cv, where.toString(), args.toTypedArray())
    }

    /** 正在答题时把「答了几题 / 一共几题 / 已用多久」回写到这条未完成记录上 */
    fun updateRecordLive(recordId: Long, answered: Int, total: Int, durationSec: Int) {
        if (recordId <= 0L) return
        val cv = ContentValues().apply {
            put("answered_count", answered)
            put("total_count", total)
            put("duration_sec", durationSec)
            put("status", STATUS_ONGOING)
        }
        writableDatabase.update(
            "records", cv, "id = ? AND submitted_at = 0", arrayOf(recordId.toString())
        )
    }

    /**
     * 丢弃未交卷的草稿（连同作答与笔迹）。只留给「这一轮彻底不要了」的场景，
     * 正常的「重新开始」走 [abandonDraftRecords]，记录本身要留下。
     */
    fun discardDraftRecords(paperId: Long, source: String) {
        val db = writableDatabase
        val args = arrayOf(paperId.toString(), source)
        val where = "record_id IN (SELECT id FROM records WHERE paper_id = ? AND submitted_at = 0 AND mode = ?)"
        db.beginTransaction()
        try {
            db.delete("record_inks", where, args)
            db.delete("record_answers", where, args)
            db.delete("record_question_times", where, args)
            db.delete("records", "paper_id = ? AND submitted_at = 0 AND mode = ?", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 删掉一条记录（含逐题作答、笔迹、每题用时）；未完成的记录删掉就等于放弃这一轮 */
    fun deleteRecord(recordId: Long) {
        if (recordId <= 0L) return
        val db = writableDatabase
        val id = arrayOf(recordId.toString())
        db.beginTransaction()
        try {
            db.delete("record_answers", "record_id = ?", id)
            db.delete("record_inks", "record_id = ?", id)
            db.delete("record_question_times", "record_id = ?", id)
            db.delete("records", "id = ?", id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------- 每题用时 ----------------

    /** 给某题的用时**累加** [seconds]（一题一行，切题时把上一段秒数加进来） */
    fun addQuestionTime(recordId: Long, questionId: Long, seconds: Int) {
        if (recordId <= 0L || questionId <= 0L || seconds <= 0) return
        val db = writableDatabase
        val args = arrayOf(recordId.toString(), questionId.toString())
        val exist = db.rawQuery(
            "SELECT seconds FROM record_question_times WHERE record_id = ? AND question_id = ?", args
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
        if (exist >= 0) {
            // 不用 UPSERT 语法：Android 7 上的 SQLite 还不支持 ON CONFLICT DO UPDATE
            db.execSQL(
                "UPDATE record_question_times SET seconds = IFNULL(seconds, 0) + ?, updated_at = ? WHERE record_id = ? AND question_id = ?",
                arrayOf<Any>(seconds, System.currentTimeMillis(), recordId, questionId)
            )
        } else {
            db.insert("record_question_times", null, ContentValues().apply {
                put("record_id", recordId)
                put("question_id", questionId)
                put("seconds", seconds)
                put("updated_at", System.currentTimeMillis())
            })
        }
    }

    /** 一条记录的逐题用时：题目 id → 秒数 */
    fun getQuestionTimes(recordId: Long): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        if (recordId <= 0L) return out
        readableDatabase.rawQuery(
            "SELECT question_id, seconds FROM record_question_times WHERE record_id = ?",
            arrayOf(recordId.toString())
        ).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getInt(1)
        }
        return out
    }

    /**
     * 把草稿转成正式记录：补上交卷时间与成绩，并用本次判分结果替换逐题作答。
     *
     * 笔迹（`record_inks`）与每题用时（`record_question_times`）都不删——它们本来就挂在
     * 这条记录上，交卷后仍能回看。[recordId] 为 0 或记录已不存在时直接新插一条。
     */
    fun finalizeRecord(
        recordId: Long,
        record: ExamRecord,
        results: List<com.lytree.qbank.data.model.AnswerResult>
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = recordValues(record)
            val rid = if (recordId > 0L && recordExists(db, recordId)) {
                db.update("records", values, "id = ?", arrayOf(recordId.toString()))
                db.delete("record_answers", "record_id = ?", arrayOf(recordId.toString()))
                recordId
            } else {
                db.insert("records", null, values)
            }
            for (r in results) {
                val cv = ContentValues().apply {
                    put("record_id", rid)
                    put("question_id", r.question.id)
                    put("user_answer", r.userAnswer)
                    put("earned", r.earned)
                    put("correct", if (r.correct) 1 else 0)
                }
                db.insert("record_answers", null, cv)
            }
            db.setTransactionSuccessful()
            return rid
        } finally {
            db.endTransaction()
        }
    }

    private fun recordExists(db: SQLiteDatabase, id: Long): Boolean =
        db.rawQuery("SELECT 1 FROM records WHERE id = ?", arrayOf(id.toString()))
            .use { it.moveToFirst() }

    private fun recordValues(record: ExamRecord): ContentValues = ContentValues().apply {
        put("paper_id", record.paperId)
        put("name", record.name)
        put("mode", record.mode)
        put("started_at", record.startedAt)
        put("submitted_at", record.submittedAt)
        put("duration_sec", record.durationSec)
        put("total_count", record.totalCount)
        put("answered_count", record.answeredCount)
        put("correct_count", record.correctCount)
        put("partial_count", record.partialCount)
        put("score", record.score)
        put("full_score", record.fullScore)
        // 标记随记录留档（与 exam_progress.marked 同一种逗号串编码）
        put("marked", record.marked.joinToString(","))
        put("status", record.status.ifBlank { STATUS_DONE })
    }

    /**
     * 记录列表：**已交卷的、还没做完的都出**（需求：一进答题页就留痕）。
     *
     * 按记录 id 倒序，最新练的在最上面；未完成的点进去就能接着做。
     */
    fun listRecords(): List<ExamRecord> {
        val out = ArrayList<ExamRecord>()
        readableDatabase.rawQuery(
            """SELECT r.id, r.paper_id, r.name, r.mode, r.started_at, r.submitted_at,
                      r.duration_sec, r.total_count, r.answered_count, r.correct_count,
                      r.partial_count, r.score, r.full_score, r.marked,
                      IFNULL(r.status, ''), IFNULL(p.name, '')
               FROM records r LEFT JOIN papers p ON p.id = r.paper_id
               ORDER BY r.id DESC""",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ExamRecord(
                        id = c.getLong(0), paperId = c.getLong(1), name = c.getString(2) ?: "",
                        mode = c.getString(3) ?: "", startedAt = c.getLong(4), submittedAt = c.getLong(5),
                        durationSec = c.getInt(6), totalCount = c.getInt(7), answeredCount = c.getInt(8),
                        correctCount = c.getInt(9), partialCount = c.getInt(10),
                        score = c.getDouble(11), fullScore = c.getDouble(12),
                        marked = parseIdTextSet(c.getString(13) ?: ""),
                        status = c.getString(14).orEmpty().ifBlank { STATUS_DONE },
                        paperName = c.getString(15) ?: ""
                    )
                )
            }
        }
        return out
    }

    /** 载入一条记录及其逐题作答 */
    fun getRecord(id: Long): ExamRecord? {
        val base = listRecords().firstOrNull { it.id == id } ?: return null
        val results = ArrayList<com.lytree.qbank.data.model.AnswerResult>()
        readableDatabase.rawQuery(
            """SELECT q.*, ra.user_answer, ra.earned, ra.correct
               FROM record_answers ra
               JOIN questions q ON q.id = ra.question_id
               WHERE ra.record_id = ? ORDER BY q.id""",
            arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val q = readQuestion(c)
                val earned = c.getDouble(c.getColumnIndexOrThrow("earned"))
                val full = q.score
                results.add(
                    com.lytree.qbank.data.model.AnswerResult(
                        question = q,
                        userAnswer = c.getString(c.getColumnIndexOrThrow("user_answer")) ?: "",
                        earned = earned,
                        full = full,
                        correct = c.getInt(c.getColumnIndexOrThrow("correct")) == 1,
                        // 部分得分没有单独存表，按「有得分但没全对」还原
                        partial = earned > 0.0 && earned < full,
                        // 题目还没有标准答案 → 待补答案，不参与统计
                        pending = !Judge.hasStandard(q)
                    )
                )
            }
        }
        return base.copy(results = results)
    }

    // ---------------- Settings ----------------
    fun getPolicy(): Policy {
        val v = getSetting("policy") ?: return Policy.DEFAULT
        return try {
            val o = org.json.JSONObject(v)
            Policy(
                multiPartial = o.optBoolean("multiPartial", false),
                uncertainPartial = o.optBoolean("uncertainPartial", true),
                uncertainPartialRatio = o.optDouble("uncertainPartialRatio", 0.5),
                fillPartial = o.optBoolean("fillPartial", true),
                subjectivePartial = o.optBoolean("subjectivePartial", true),
                judgeLenient = o.optBoolean("judgeLenient", true)
            )
        } catch (_: Exception) {
            Policy.DEFAULT
        }
    }

    fun setPolicy(p: Policy) {
        val o = org.json.JSONObject().apply {
            put("multiPartial", p.multiPartial)
            put("uncertainPartial", p.uncertainPartial)
            put("uncertainPartialRatio", p.uncertainPartialRatio)
            put("fillPartial", p.fillPartial)
            put("subjectivePartial", p.subjectivePartial)
            put("judgeLenient", p.judgeLenient)
        }
        setSetting("policy", o.toString())
    }

    fun getSetting(key: String): String? {
        readableDatabase.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key))
            .use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    fun setSetting(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key); put("value", value)
        }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------------- Ink（手写标注，跟随作答记录） ----------------

    /**
     * 读取一条记录里某个目标的笔迹。
     *
     * [kind] 见 [INK_KIND_QUESTION] / [INK_KIND_MATERIAL]；[targetId] 是题目 id
     * 或材料组首题 id。旧 `inks` 表不再读取——那份数据没有记录归属，无法安全迁移到某一轮练习。
     */
    fun getInk(recordId: Long, kind: String, targetId: Long): String? {
        if (recordId <= 0L) return null
        readableDatabase.rawQuery(
            "SELECT strokes FROM record_inks WHERE record_id = ? AND kind = ? AND target_id = ?",
            arrayOf(recordId.toString(), kind, targetId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    /** 写入笔迹：同一记录、同一目标只有一份，后写的覆盖前一份（只保留最新批注） */
    fun setInk(recordId: Long, kind: String, targetId: Long, strokes: String) {
        if (recordId <= 0L) return
        val cv = ContentValues().apply {
            put("record_id", recordId)
            put("kind", kind)
            put("target_id", targetId)
            put("strokes", strokes)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "record_inks", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** 撤销到一笔不剩时删掉这一行，别在库里留空串 */
    fun deleteInk(recordId: Long, kind: String, targetId: Long) {
        if (recordId <= 0L) return
        writableDatabase.delete(
            "record_inks", "record_id = ? AND kind = ? AND target_id = ?",
            arrayOf(recordId.toString(), kind, targetId.toString())
        )
    }

    // ---------------- Note（笔记） ----------------

    fun getNote(questionId: Long): String {
        readableDatabase.rawQuery(
            "SELECT note FROM notes WHERE question_id = ?", arrayOf(questionId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getString(0) ?: "" else "" }
    }

    fun setNote(questionId: Long, note: String) {
        val cv = ContentValues().apply {
            put("question_id", questionId); put("note", note)
        }
        writableDatabase.insertWithOnConflict(
            "notes", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ---------------- Favorite（收藏，跨会话持久） ----------------

    fun isFavorite(questionId: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM favorites WHERE question_id = ?", arrayOf(questionId.toString())
        ).use { return it.moveToFirst() }
    }

    fun setFavorite(questionId: Long, fav: Boolean) {
        if (fav) {
            writableDatabase.insertWithOnConflict(
                "favorites", null,
                ContentValues().apply { put("question_id", questionId) },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } else {
            writableDatabase.delete("favorites", "question_id = ?", arrayOf(questionId.toString()))
        }
    }

    /** 全部收藏题 id（答题页按本卷题 id 过滤使用） */
    fun listFavoriteIds(): Set<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery("SELECT question_id FROM favorites", null).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    // ---------------- ExamProgress（答题进度，中途退出可续做） ----------------

    /** 某份试卷的未交卷进度；无进度或进度为空时返回 null */
    fun getProgress(paperId: Long): ExamProgress? {
        readableDatabase.rawQuery(
            "SELECT answers, marked, elapsed_sec FROM exam_progress WHERE paper_id = ?",
            arrayOf(paperId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            val answers = parseIdTextMap(c.getString(0) ?: "")
            val marked = parseIdTextSet(c.getString(1) ?: "")
            if (answers.isEmpty() && marked.isEmpty()) return null
            return ExamProgress(paperId, answers, marked, c.getInt(2))
        }
    }

    fun saveProgress(paperId: Long, answers: Map<Long, String>, marked: Set<Long>, elapsedSec: Int) {
        writableDatabase.insertWithOnConflict(
            "exam_progress", null,
            ContentValues().apply {
                put("paper_id", paperId)
                put("answers", answers.entries.joinToString(";") { "${it.key},${it.value}" })
                put("marked", marked.joinToString(","))
                put("elapsed_sec", elapsedSec)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun clearProgress(paperId: Long) {
        writableDatabase.delete("exam_progress", "paper_id = ?", arrayOf(paperId.toString()))
    }

    private fun parseIdTextMap(raw: String): HashMap<Long, String> {
        val out = HashMap<Long, String>()
        if (raw.isBlank()) return out
        for (entry in raw.split(';')) {
            val i = entry.indexOf(',')
            if (i <= 0) continue
            val id = entry.substring(0, i).toLongOrNull() ?: continue
            out[id] = entry.substring(i + 1)
        }
        return out
    }

    private fun parseIdTextSet(raw: String): HashSet<Long> {
        val out = HashSet<Long>()
        if (raw.isBlank()) return out
        raw.split(',').forEach { it.toLongOrNull()?.let(out::add) }
        return out
    }

    companion object {
        /** 数据库文件名（云备份打交道时用，保持一致） */
        const val DB_NAME = "qbank.db"

        /** 库版本。云备份包里的 manifest 会记下它，恢复时据此判断能否直接落地 */
        const val DB_VERSION = 11

        /** 重练卷统一挂的组名 */
        private const val RETRY_SET_TITLE = "错题重练"

        /** 没有组信息的卷子在列表里的兜底标题 */
        private const val UNGROUPED_SET_TITLE = "未分组"

        /** 旧数据回填时，连来源都没有的卷子归到这组 */
        private const val LEGACY_SET_TITLE = "历史试卷"

        /** 从「导入自 xxx.xlsx」这类来源串里取出组标题（去前缀、去扩展名） */
        fun setTitleOf(source: String): String {
            val s = source.trim()
            if (s.isEmpty()) return LEGACY_SET_TITLE
            val name = s.removePrefix("导入自").trim()
            val base = name.substringBeforeLast('.', name).trim()
            return base.ifBlank { LEGACY_SET_TITLE }
        }

        /** v9 重建时整体清空、一并重建的表（题库相关；词库 / 背诵 / 设置不动） */
        private val QUESTION_TABLES = listOf(
            "banks", "paper_questions", "questions", "papers",
            "records", "record_answers", "record_inks", "exam_progress",
            "favorites", "notes", "inks"
        )

        /** 试卷来源：由导入文件生成 */
        const val MODE_IMPORT = "导入"

        /** 试卷来源：错题重练（从练习记录的错题生成） */
        const val MODE_RETRY = "错题重练"

        /** 笔迹目标：题目（target_id = 题目 id） */
        const val INK_KIND_QUESTION = "question"

        /** 笔迹目标：材料（target_id = 材料组首题 id） */
        const val INK_KIND_MATERIAL = "material"

        /** 记录状态：还没交卷，正在做（可以从记录列表点回来接着答） */
        const val STATUS_ONGOING = "ongoing"

        /** 记录状态：已交卷，成绩与用时都可回看 */
        const val STATUS_DONE = "done"

        /** 记录状态：这一轮放弃了（「重新开始」把上一轮留成这个状态），仍算「未完成」 */
        const val STATUS_ABANDONED = "abandoned"

        @Volatile
        private var instance: QBankDb? = null

        fun get(context: Context): QBankDb =
            instance ?: synchronized(this) {
                instance ?: QBankDb(context.applicationContext).also { instance = it }
            }

        /**
         * 关掉单例。云备份恢复要整库替换文件，必须先放开句柄，
         * 否则换过去的库会被仍在打开的连接覆盖回去。
         */
        fun closeInstance() = synchronized(this) {
            runCatching { instance?.close() }
            instance = null
        }
    }
}

/** 导入结果：一个文件生成的一份试卷 */
data class ImportResult(
    val paperId: Long,
    val questionCount: Int,
    val paperName: String
)

/**
 * 一次导入 = 一个标题（树上的第一层）。
 *
 * [papers] 只在 `listPaperSets()` 里带内容，`withPapers = false` 时为空。
 */
data class PaperSet(
    val id: Long = 0L,
    val title: String = "",
    /** 来源说明：「导入自 xxx.xlsx」/「错题重练」 */
    val source: String = "",
    val createdAt: Long = 0L,
    val paperCount: Int = 0,
    val questionCount: Int = 0,
    /** 组里还缺标准答案的题数 */
    val missingAnswers: Int = 0,
    val papers: List<Paper> = emptyList()
)

/** 待写入的一组导入：标题 + 来源 + 组里的各份卷 */
data class SetImport(
    val title: String,
    val source: String,
    val papers: List<PaperImport>
)

/** 一组导入写完的结果 */
data class SetImportResult(
    val setId: Long,
    val title: String,
    val papers: List<ImportResult>
) {
    val questionCount: Int get() = papers.sumOf { it.questionCount }
}

/** 待建的一份试卷（批量导入用） */
data class PaperImport(
    val name: String,
    val description: String,
    val durationMin: Int,
    val questions: List<Question>
)

/** 待写入的标准答案（[analysis] 为空时保留题目原有解析） */
data class AnswerUpdate(
    val questionId: Long,
    val answer: String,
    val analysis: String = ""
)

/** 未交卷的答题进度（中途退出保存，重进可续做） */
data class ExamProgress(
    val paperId: Long,
    val answers: Map<Long, String>,
    val marked: Set<Long>,
    /** 已用掉的秒数（续做时从剩余时长继续倒计时） */
    val elapsedSec: Int
)

/** Option 便捷扩展 */
fun List<Option>.keys(): List<String> = map { it.key }
