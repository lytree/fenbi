package com.lytree.qbank.ui.laneditor

import android.content.Context
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.importer.PinyinTable
import com.lytree.qbank.data.model.Word
import com.lytree.qbank.data.model.WordCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * 局域网编辑器的词库（词条 CRUD）接口。
 *
 * 与背诵词库（[LanEditorRecite]）不同，词条是**逐条小改**：列表带搜索 /
 * 分类 / 标签筛选，编辑单条的释义、标签、分类，加词删词都直接写库
 * （QBankDb.words 表）——和网页上改草稿、传图同级，不过手机确认；
 * 真正重的那一步（整表清空）没有在这里开接口。
 *
 * 拼音：新词 / 改了词条正文的，按内置字表逐字算（[PinyinTable.fullPinyin]）；
 * 网页明确给了拼音就用给的（去声调规范化）。
 */
object LanEditorWords {

    /** 列表：搜索 + 分类 + 标签筛选，一次给全（当前词库两千多条，没必要分页） */
    fun list(context: Context, query: Map<String, String>): JSONObject {
        val kw = query["q"]?.trim().orEmpty()
        val category = query["category"]?.trim().takeUnless { it.isNullOrBlank() || it == "all" }
        val tag = query["tag"]?.trim().takeUnless { it.isNullOrBlank() || it == "all" }
        val db = QBankDb.get(context)
        val words = db.listWords(
            category = category,
            tags = if (tag == null) emptySet() else setOf(tag),
            query = kw,
        )

        val arr = JSONArray()
        for (w in words) arr.put(wordJson(w))

        val counts = JSONObject()
        db.countWordsByCategory().forEach { (code, n) -> counts.put(code, n) }

        val tags = JSONArray()
        for (t in db.listWordTags(category)) {
            tags.put(JSONObject().put("name", t.name).put("count", t.count))
        }

        return JSONObject()
            .put("words", arr)
            .put("total", db.countWords())
            .put("counts", counts)
            .put("tags", tags)
    }

    /**
     * 保存一条词条（新增 id=0 / 编辑 id>0）。
     * 返回保存后的词条 JSON。错误以 [IllegalArgumentException] 抛出给路由回 400。
     */
    fun save(context: Context, payload: JSONObject): JSONObject {
        val text = payload.optString("text").trim()
        if (text.isEmpty()) throw IllegalArgumentException("词条不能为空")
        if (text.length > 16) throw IllegalArgumentException("「${text.take(8)}…」有 ${text.length} 个字，像是整句，词库只收词条")

        PinyinTable.ensure(context)
        val id = payload.optLong("id", 0L)
        val old = if (id > 0L) QBankDb.get(context).getWord(id) else null
        if (id > 0L && old == null) {
            throw IllegalArgumentException("词条不存在（可能已被删掉），刷新列表再试")
        }

        // 拼音：给了就用给的；没给时只有正文变了才重算，别把用户手改过的拼音冲掉
        val rawPinyin = payload.optString("pinyin").trim()
        val textChanged = old == null || old.text != text
        val pinyin = when {
            rawPinyin.isNotEmpty() -> PinyinTable.normalizePinyin(rawPinyin)
            old != null && !textChanged -> old.pinyin
            else -> PinyinTable.fullPinyin(text)
        }

        val category = WordCategory.parse(payload.optString("category"))
            ?: (text.length == 4).let { if (it) WordCategory.IDIOM else WordCategory.WORD }

        val word = Word(
            text = text,
            pinyin = pinyin,
            initial = PinyinTable.initialOf(pinyin),
            category = category.code,
            tag = payload.optString("tag"),
            meaning = payload.optString("meaning").trim(),
            example = payload.optString("example").trim(),
            source = payload.optString("source").trim().ifEmpty {
                old?.source?.takeIf { it.isNotBlank() } ?: "网页编辑"
            },
            createdAt = old?.createdAt ?: System.currentTimeMillis(),
        )

        val error = QBankDb.get(context).saveWord(word, id)
        if (error != null) throw IllegalArgumentException(error)

        val saved = if (id > 0L) word.copy(id = id) else {
            // 新增后要拿回自增 id：词条唯一，按正文反查是稳的
            findIdByText(context, text)?.let { word.copy(id = it) } ?: word
        }
        return wordJson(saved)
    }

    private fun findIdByText(context: Context, text: String): Long? =
        QBankDb.get(context).readableDatabase
            .rawQuery("SELECT id FROM words WHERE text = ?", arrayOf(text))
            .use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** 删一条；词条不存在不算错（幂等，重复点删除不炸） */
    fun delete(context: Context, id: Long): JSONObject {
        QBankDb.get(context).deleteWord(id)
        return JSONObject().put("ok", true).put("id", id)
    }

    private fun wordJson(w: Word): JSONObject = JSONObject()
        .put("id", w.id)
        .put("text", w.text)
        .put("pinyin", w.pinyin)
        .put("initial", w.initial)
        .put("category", w.category)
        .put("tag", w.tag)
        .put("meaning", w.meaning)
        .put("example", w.example)
        .put("source", w.source)
}
