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
import com.lytree.qbank.engine.Policy

/**
 * 本地题库存储。用原生 SQLiteOpenHelper，不引入 Room，避免注解处理器带来的构建复杂度。
 */
class QBankDb(context: Context) : SQLiteOpenHelper(context, "qbank.db", null, 2) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 无损升级：只补新增的表，不动用户已有的题库数据
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS inks(question_id INTEGER PRIMARY KEY, strokes TEXT DEFAULT '')")
            db.execSQL("CREATE TABLE IF NOT EXISTS notes(question_id INTEGER PRIMARY KEY, note TEXT DEFAULT '')")
        }
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

    fun deleteBank(id: Long) {
        writableDatabase.delete("questions", "bank_id = ?", arrayOf(id.toString()))
        writableDatabase.delete("banks", "id = ?", arrayOf(id.toString()))
    }

    // ---------------- Question ----------------

    fun insertQuestions(bankId: Long, list: List<Question>): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var n = 0
            for (q in list) {
                val cv = ContentValues().apply {
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
                if (db.insert("questions", null, cv) != -1L) n++
            }
            db.setTransactionSuccessful()
            return n
        } finally {
            db.endTransaction()
        }
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

    /** 抽题：按题型/知识点/难度/地区过滤 */
    fun pickQuestions(
        bankId: Long,
        types: Set<QuestionType>,
        knowledge: String?,
        difficulty: Int?,
        region: String?,
        limit: Int
    ): List<Question> {
        val where = StringBuilder("bank_id = ?")
        val args = ArrayList<String>().apply { add(bankId.toString()) }
        if (types.isNotEmpty()) {
            where.append(" AND type IN (")
            types.forEachIndexed { i, t -> if (i > 0) where.append(","); where.append("?"); args.add(t.code) }
            where.append(")")
        }
        if (!knowledge.isNullOrBlank()) { where.append(" AND knowledge LIKE ?"); args.add("%$knowledge%") }
        if (difficulty != null && difficulty > 0) { where.append(" AND difficulty = ?"); args.add(difficulty.toString()) }
        if (!region.isNullOrBlank()) { where.append(" AND region LIKE ?"); args.add("%$region%") }
        where.append(" ORDER BY RANDOM() LIMIT ?")
        args.add(limit.toString())
        val out = ArrayList<Question>()
        readableDatabase.rawQuery("SELECT * FROM questions WHERE $where", args.toTypedArray())
            .use { c -> while (c.moveToNext()) out.add(readQuestion(c)) }
        return out
    }

    /** 知识点清单 */
    fun listKnowledge(bankId: Long): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT knowledge FROM questions WHERE bank_id = ? AND knowledge <> '' ORDER BY knowledge",
            arrayOf(bankId.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0) ?: "") }
        return out
    }

    // ---------------- Paper ----------------

    fun insertPaper(name: String, bankId: Long, durationMin: Int, mode: String, questions: List<Question>): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("name", name)
                put("bank_id", bankId)
                put("created_at", System.currentTimeMillis())
                put("duration_min", durationMin)
                put("mode", mode)
            }
            val pid = db.insert("papers", null, cv)
            questions.forEachIndexed { i, q ->
                val cv2 = ContentValues().apply {
                    put("paper_id", pid)
                    put("question_id", q.id)
                    put("position", i)
                }
                db.insert("paper_questions", null, cv2)
            }
            db.setTransactionSuccessful()
            return pid
        } finally {
            db.endTransaction()
        }
    }

    fun listPapers(): List<Paper> {
        val out = ArrayList<Paper>()
        readableDatabase.rawQuery(
            """SELECT p.id, p.name, p.bank_id, p.created_at, p.duration_min, p.mode,
                      (SELECT COUNT(*) FROM paper_questions pq WHERE pq.paper_id = p.id)
               FROM papers p ORDER BY p.created_at DESC""", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Paper(
                        id = c.getLong(0), name = c.getString(1) ?: "", bankId = c.getLong(2),
                        createdAt = c.getLong(3), durationMin = c.getInt(4), mode = c.getString(5) ?: "",
                        questionCount = c.getInt(6),
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

    fun deletePaper(id: Long) {
        writableDatabase.delete("paper_questions", "paper_id = ?", arrayOf(id.toString()))
        writableDatabase.delete("papers", "id = ?", arrayOf(id.toString()))
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
                results.add(
                    com.lytree.qbank.data.model.AnswerResult(
                        question = q,
                        userAnswer = c.getString(c.getColumnIndexOrThrow("user_answer")) ?: "",
                        earned = c.getDouble(c.getColumnIndexOrThrow("earned")),
                        full = q.score,
                        correct = c.getInt(c.getColumnIndexOrThrow("correct")) == 1,
                        partial = false
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

    companion object {
        @Volatile
        private var instance: QBankDb? = null

        fun get(context: Context): QBankDb =
            instance ?: synchronized(this) {
                instance ?: QBankDb(context.applicationContext).also { instance = it }
            }
    }
}

/** Option 便捷扩展 */
fun List<Option>.keys(): List<String> = map { it.key }
