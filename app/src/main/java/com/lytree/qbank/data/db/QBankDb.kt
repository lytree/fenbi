package com.lytree.qbank.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lytree.qbank.data.model.Bank
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.data.model.Option
import com.lytree.qbank.data.model.Paper
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.data.model.QuestionType
import com.lytree.qbank.engine.Judge
import com.lytree.qbank.engine.Policy

/**
 * 本地题库存储。用原生 SQLiteOpenHelper，不引入 Room，避免注解处理器带来的构建复杂度。
 */
class QBankDb(context: Context) : SQLiteOpenHelper(context, "qbank.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE banks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                description TEXT DEFAULT '',
                subject TEXT DEFAULT '',
                created_at INTEGER DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE questions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                bank_id INTEGER NOT NULL,
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
        db.execSQL("CREATE INDEX idx_q_bank ON questions(bank_id)")
        db.execSQL("CREATE INDEX idx_q_knowledge ON questions(knowledge)")
        db.execSQL("CREATE INDEX idx_q_type ON questions(type)")
        db.execSQL(
            """CREATE TABLE papers(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                bank_id INTEGER DEFAULT 0,
                created_at INTEGER DEFAULT 0,
                duration_min INTEGER DEFAULT 0,
                mode TEXT DEFAULT ''
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE paper_questions(
                paper_id INTEGER NOT NULL,
                question_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY(paper_id, question_id)
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE records(
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
                full_score REAL DEFAULT 0.0
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE record_answers(
                record_id INTEGER NOT NULL,
                question_id INTEGER NOT NULL,
                user_answer TEXT DEFAULT '',
                earned REAL DEFAULT 0.0,
                correct INTEGER DEFAULT 0,
                PRIMARY KEY(record_id, question_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE inks(question_id INTEGER PRIMARY KEY, strokes TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE notes(question_id INTEGER PRIMARY KEY, note TEXT DEFAULT '')")
        createV3Tables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 无损升级：只补新增的表，不动用户已有的题库数据
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS inks(question_id INTEGER PRIMARY KEY, strokes TEXT DEFAULT '')")
            db.execSQL("CREATE TABLE IF NOT EXISTS notes(question_id INTEGER PRIMARY KEY, note TEXT DEFAULT '')")
        }
        if (oldVersion < 3) createV3Tables(db)
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

    // ---------------- Bank ----------------

    fun insertBank(name: String, description: String, subject: String): Long {
        val cv = ContentValues().apply {
            put("name", name)
            put("description", description)
            put("subject", subject)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("banks", null, cv)
    }

    fun listBanks(): List<Bank> {
        val out = ArrayList<Bank>()
        readableDatabase.rawQuery(
            """SELECT b.id, b.name, b.description, b.subject, b.created_at,
                      (SELECT COUNT(*) FROM questions q WHERE q.bank_id = b.id)
               FROM banks b ORDER BY b.created_at DESC""",
            null
        ).use { c -> while (c.moveToNext()) out.add(readBank(c)) }
        return out
    }

    fun getBank(id: Long): Bank? {
        readableDatabase.rawQuery(
            """SELECT b.id, b.name, b.description, b.subject, b.created_at,
                      (SELECT COUNT(*) FROM questions q WHERE q.bank_id = b.id)
               FROM banks b WHERE b.id = ?""",
            arrayOf(id.toString())
        ).use { c -> return if (c.moveToFirst()) readBank(c) else null }
    }

    private fun readBank(c: Cursor) = Bank(
        id = c.getLong(0),
        name = c.getString(1) ?: "",
        description = c.getString(2) ?: "",
        subject = c.getString(3) ?: "",
        createdAt = c.getLong(4),
        questionCount = c.getInt(5)
    )

    /** 删题库时连带删掉它生成的试卷，避免留下「空壳卷」 */
    fun deleteBank(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM paper_questions WHERE paper_id IN (SELECT id FROM papers WHERE bank_id = ?)",
                arrayOf<Any>(id)
            )
            db.delete("papers", "bank_id = ?", arrayOf(id.toString()))
            db.delete("questions", "bank_id = ?", arrayOf(id.toString()))
            db.delete("banks", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------- Question ----------------

    private fun questionValues(bankId: Long, q: Question): ContentValues = ContentValues().apply {
        put("bank_id", bankId)
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

    fun listQuestions(bankId: Long, limit: Int = 5000): List<Question> {
        val out = ArrayList<Question>()
        readableDatabase.rawQuery(
            "SELECT * FROM questions WHERE bank_id = ? ORDER BY id LIMIT ?",
            arrayOf(bankId.toString(), limit.toString())
        ).use { c -> while (c.moveToNext()) out.add(readQuestion(c)) }
        return out
    }

    fun countQuestions(bankId: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM questions WHERE bank_id = ?", arrayOf(bankId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun getQuestion(id: Long): Question? {
        readableDatabase.rawQuery("SELECT * FROM questions WHERE id = ?", arrayOf(id.toString()))
            .use { c -> return if (c.moveToFirst()) readQuestion(c) else null }
    }

    private fun readQuestion(c: Cursor) = Question(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        bankId = c.getLong(c.getColumnIndexOrThrow("bank_id")),
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
     * 导入即建卷：一个文件 → 一个题库 + 一份试卷，题目按文件里的顺序整卷入卷。
     *
     * 放在同一个事务里，避免出现「题库建好了、试卷却是空的」这类半成品数据。
     */
    fun importAsPaper(
        bankName: String,
        description: String,
        subject: String,
        paperName: String,
        durationMin: Int,
        questions: List<Question>
    ): ImportResult {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val bankId = db.insert("banks", null, ContentValues().apply {
                put("name", bankName)
                put("description", description)
                put("subject", subject)
                put("created_at", System.currentTimeMillis())
            })
            val ids = ArrayList<Long>(questions.size)
            for (q in questions) {
                val qid = db.insert("questions", null, questionValues(bankId, q))
                if (qid != -1L) ids.add(qid)
            }
            val paperId = db.insert("papers", null, paperValues(paperName, bankId, durationMin, MODE_IMPORT))
            ids.forEachIndexed { i, qid ->
                db.insert("paper_questions", null, ContentValues().apply {
                    put("paper_id", paperId)
                    put("question_id", qid)
                    put("position", i)
                })
            }
            db.setTransactionSuccessful()
            return ImportResult(bankId, paperId, ids.size, paperName)
        } finally {
            db.endTransaction()
        }
    }

    /** 用题库里现有的全部题目生成一份试卷（试卷被删后重新开练用） */
    fun createPaperFromBank(
        bankId: Long,
        paperName: String,
        durationMin: Int = 0,
        mode: String = MODE_IMPORT
    ): Long {
        val questions = listQuestions(bankId)
        if (questions.isEmpty()) return 0L
        val db = writableDatabase
        db.beginTransaction()
        try {
            val pid = db.insert("papers", null, paperValues(paperName, bankId, durationMin, mode))
            questions.forEachIndexed { i, q ->
                db.insert("paper_questions", null, ContentValues().apply {
                    put("paper_id", pid)
                    put("question_id", q.id)
                    put("position", i)
                })
            }
            db.setTransactionSuccessful()
            return pid
        } finally {
            db.endTransaction()
        }
    }

    /** 用给定的题目（按传入顺序）生成一份新试卷——「错题重练」用：从练习记录里挑出答错的题再练一遍 */
    fun createPaperFromQuestions(
        bankId: Long,
        paperName: String,
        questionIds: List<Long>,
        durationMin: Int = 0
    ): Long {
        if (questionIds.isEmpty()) return 0L
        val db = writableDatabase
        db.beginTransaction()
        try {
            val pid = db.insert("papers", null, paperValues(paperName, bankId, durationMin, MODE_RETRY))
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

    private fun paperValues(name: String, bankId: Long, durationMin: Int, mode: String): ContentValues =
        ContentValues().apply {
            put("name", name)
            put("bank_id", bankId)
            put("created_at", System.currentTimeMillis())
            put("duration_min", durationMin)
            put("mode", mode)
        }

    fun listPapers(): List<Paper> {
        val out = ArrayList<Paper>()
        readableDatabase.rawQuery(
            """SELECT p.id, p.name, p.bank_id, p.created_at, p.duration_min, p.mode,
                      (SELECT COUNT(*) FROM paper_questions pq WHERE pq.paper_id = p.id),
                      (SELECT COUNT(*) FROM paper_questions pq2
                         JOIN questions q2 ON q2.id = pq2.question_id
                         WHERE pq2.paper_id = p.id AND TRIM(IFNULL(q2.answer, '')) = '')
               FROM papers p ORDER BY p.created_at DESC""", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Paper(
                        id = c.getLong(0), name = c.getString(1) ?: "", bankId = c.getLong(2),
                        createdAt = c.getLong(3), durationMin = c.getInt(4), mode = c.getString(5) ?: "",
                        questionCount = c.getInt(6),
                        missingAnswers = c.getInt(7),
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

    /** 题库对应的试卷：一次导入只会产生一份卷，取最新的一份 */
    fun getPaperByBank(bankId: Long): Paper? {
        val id = readableDatabase.rawQuery(
            "SELECT id FROM papers WHERE bank_id = ? ORDER BY id DESC LIMIT 1",
            arrayOf(bankId.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return null
        return getPaper(id)
    }

    /** 同名试卷（导入重名时用来提示覆盖） */
    fun findPaperByName(name: String): Paper? =
        listPapers().firstOrNull { it.name == name }

    /** 同名题库（兼容升级前只建库没建卷的旧数据） */
    fun findBankByName(name: String): Bank? =
        listBanks().firstOrNull { it.name == name }

    fun deletePaper(id: Long) {
        writableDatabase.delete("paper_questions", "paper_id = ?", arrayOf(id.toString()))
        writableDatabase.delete("papers", "id = ?", arrayOf(id.toString()))
    }

    /**
     * 批量补充标准答案。
     *
     * 用于「试卷先导入、答案后补」：只写非空答案；解析为空时保留题目原有解析，
     * 避免一份纯「题号 + 答案」的表把已有解析冲掉。
     */
    fun updateAnswers(updates: List<AnswerUpdate>): Int {
        if (updates.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            var n = 0
            for (u in updates) {
                if (u.answer.isBlank()) continue
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
     * 用题库当前的标准答案重判一条练习记录。
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

    fun insertRecord(record: ExamRecord, results: List<com.lytree.qbank.data.model.AnswerResult>): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
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
            }
            val rid = db.insert("records", null, cv)
            for (r in results) {
                val cv2 = ContentValues().apply {
                    put("record_id", rid)
                    put("question_id", r.question.id)
                    put("user_answer", r.userAnswer)
                    put("earned", r.earned)
                    put("correct", if (r.correct) 1 else 0)
                }
                db.insert("record_answers", null, cv2)
            }
            db.setTransactionSuccessful()
            return rid
        } finally {
            db.endTransaction()
        }
    }

    fun listRecords(): List<ExamRecord> {
        val out = ArrayList<ExamRecord>()
        readableDatabase.rawQuery(
            "SELECT id, paper_id, name, mode, started_at, submitted_at, duration_sec, total_count, answered_count, correct_count, partial_count, score, full_score FROM records ORDER BY id DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ExamRecord(
                        id = c.getLong(0), paperId = c.getLong(1), name = c.getString(2) ?: "",
                        mode = c.getString(3) ?: "", startedAt = c.getLong(4), submittedAt = c.getLong(5),
                        durationSec = c.getInt(6), totalCount = c.getInt(7), answeredCount = c.getInt(8),
                        correctCount = c.getInt(9), partialCount = c.getInt(10),
                        score = c.getDouble(11), fullScore = c.getDouble(12)
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

    // ---------------- Ink（手写标注） ----------------

    /** 读取某题的手写笔迹，序列化后的字符串 */
    fun getInk(questionId: Long): String? {
        readableDatabase.rawQuery(
            "SELECT strokes FROM inks WHERE question_id = ?", arrayOf(questionId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    /** 写入某题的手写笔迹 */
    fun setInk(questionId: Long, strokes: String) {
        val cv = ContentValues().apply {
            put("question_id", questionId); put("strokes", strokes)
        }
        writableDatabase.insertWithOnConflict(
            "inks", null, cv, SQLiteDatabase.CONFLICT_REPLACE
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
        /** 试卷来源：由导入文件生成 */
        const val MODE_IMPORT = "导入"

        /** 试卷来源：错题重练（从练习记录的错题生成） */
        const val MODE_RETRY = "错题重练"

        @Volatile
        private var instance: QBankDb? = null

        fun get(context: Context): QBankDb =
            instance ?: synchronized(this) {
                instance ?: QBankDb(context.applicationContext).also { instance = it }
            }
    }
}

/** 导入结果：一个文件生成的一份题库 + 一份试卷 */
data class ImportResult(
    val bankId: Long,
    val paperId: Long,
    val questionCount: Int,
    val paperName: String
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
