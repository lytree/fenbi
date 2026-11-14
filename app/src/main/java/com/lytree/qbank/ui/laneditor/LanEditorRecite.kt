package com.lytree.qbank.ui.laneditor

import android.content.Context
import com.lytree.qbank.data.ReciteRepository
import com.lytree.qbank.data.model.ReciteSubject
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 局域网编辑器的背诵词库（政治 / 常识）接口。
 *
 * 数据面在 [ReciteRepository]：内置资产只读，编辑结果落在 filesDir 的用户覆盖文件里，
 * App 读内容时覆盖文件优先。这里只做三件事：
 *
 * - 摘要：两块内容各有多少分类 / 卡片、有没有被编辑过；
 * - 整份读出：卡片量在千级（政治 1200+），一次全量发给网页，编辑在前端做；
 * - 整份写回：结构校验（____ 数 = 答案数、id 唯一、cat 引用存在）走
 *   [ReciteRepository.saveUserCatalog]，网页保存还要过手机确认（[LanEditorConfirm]）。
 *
 * 词库（词条 CRUD）在 [LanEditorWords]，那边是逐条小改，不在这里。
 */
object LanEditorRecite {

    /** 两块内容一起列：网页左侧列表用 */
    fun subjects(context: Context): JSONObject {
        val arr = JSONArray()
        for (subject in ReciteSubject.entries) {
            val catalog = ReciteRepository.catalog(context, subject)
            arr.put(
                JSONObject()
                    .put("id", subject.id)
                    .put("title", catalog.title.ifBlank { titleOf(context, subject) })
                    .put("intro", catalog.intro)
                    .put("updated", catalog.updated)
                    .put("categoryCount", catalog.categories.size)
                    .put("cardCount", catalog.cards.size)
                    .put("hasEdits", ReciteRepository.hasUserEdits(context, subject))
            )
        }
        return JSONObject().put("subjects", arr)
    }

    /** 整份词库发给网页。subject 认不出直接抛，让路由回 400 */
    fun catalog(context: Context, subjectId: String): JSONObject {
        val subject = ReciteSubject.of(subjectId)
        if (ReciteSubject.entries.none { it.id == subjectId }) {
            throw IllegalArgumentException("不认识的背诵词库：$subjectId（政治 politics / 常识 common）")
        }
        val catalog = ReciteRepository.catalog(context, subject)

        val categories = JSONArray()
        val counts = HashMap<String, Int>()
        for (c in catalog.categories) counts[c.id] = 0
        for (card in catalog.cards) counts[card.categoryId]?.let { counts[card.categoryId] = it + 1 }
        for (c in catalog.categories) {
            categories.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("group", c.group)
                    .put("count", counts[c.id] ?: 0)
            )
        }

        val cards = JSONArray()
        for (card in catalog.cards) {
            val answers = JSONArray()
            card.answers.forEach { answers.put(it) }
            cards.put(
                JSONObject()
                    .put("id", card.id)
                    .put("cat", card.categoryId)
                    .put("text", card.text)
                    .put("answers", answers)
                    .put("note", card.note)
                    .put("source", card.source)
            )
        }

        return JSONObject()
            .put("subject", subject.id)
            .put("title", catalog.title.ifBlank { titleOf(context, subject) })
            .put("intro", catalog.intro)
            .put("updated", catalog.updated)
            .put("hasEdits", ReciteRepository.hasUserEdits(context, subject))
            .put("categories", categories)
            .put("cards", cards)
    }

    /**
     * 解析网页送来的整份词库，校验后重组（丢掉多余字段，只留模型认识的）。
     * 返回（subject, 要落盘的 JSONObject）。任何结构问题都以 [IllegalArgumentException] 抛出。
     */
    fun parseSave(context: Context, payload: JSONObject): Pair<ReciteSubject, JSONObject> {
        val subjectId = payload.optString("subject")
        if (ReciteSubject.entries.none { it.id == subjectId }) {
            throw IllegalArgumentException("不认识的背诵词库：$subjectId（政治 politics / 常识 common）")
        }
        val subject = ReciteSubject.of(subjectId)

        val categoriesIn = payload.optJSONArray("categories")
            ?: throw IllegalArgumentException("categories 缺失或不是数组")
        if (categoriesIn.length() == 0) {
            throw IllegalArgumentException("至少要保留一个分类（App 的范围弹层靠它编排）")
        }
        val categories = JSONArray()
        for (i in 0 until categoriesIn.length()) {
            val c = categoriesIn.optJSONObject(i)
                ?: throw IllegalArgumentException("categories[$i] 不是对象")
            categories.put(
                JSONObject()
                    .put("id", c.optString("id").trim())
                    .put("name", c.optString("name").trim())
                    .put("group", c.optString("group").trim())
            )
        }

        val cardsIn = payload.optJSONArray("cards") ?: JSONArray()
        val cards = JSONArray()
        for (i in 0 until cardsIn.length()) {
            val c = cardsIn.optJSONObject(i)
                ?: throw IllegalArgumentException("cards[$i] 不是对象")
            val answers = JSONArray()
            val answersIn = c.optJSONArray("answers")
            if (answersIn != null) {
                for (j in 0 until answersIn.length()) answers.put(answersIn.optString(j))
            }
            cards.put(
                JSONObject()
                    .put("id", c.optString("id").trim())
                    .put("cat", c.optString("cat").trim())
                    .put("text", c.optString("text"))
                    .put("answers", answers)
                    .put("note", c.optString("note"))
                    .put("source", c.optString("source"))
            )
        }

        val out = JSONObject()
            .put("title", payload.optString("title").trim())
            .put("intro", payload.optString("intro").trim())
            .put("updated", today())
            .put("categories", categories)
            .put("cards", cards)
        // 复用落盘层的结构校验：____ 数与答案数、id 唯一、cat 引用都在这里拦
        ReciteRepository.validateUserCatalog(out)
        return subject to out
    }

    /** AI / 已确认的保存：直接落盘 */
    fun executeSave(context: Context, payload: JSONObject): JSONObject {
        val (subject, normalized) = parseSave(context, payload)
        ReciteRepository.saveUserCatalog(context, subject, normalized)
        val catalog = ReciteRepository.catalog(context, subject)
        return JSONObject()
            .put("ok", true)
            .put("subject", subject.id)
            .put("title", catalog.title)
            .put("categoryCount", catalog.categories.size)
            .put("cardCount", catalog.cards.size)
            .put("updated", catalog.updated)
    }

    /** 重置回内置资产：删覆盖文件 */
    fun executeReset(context: Context, subjectId: String): JSONObject {
        if (ReciteSubject.entries.none { it.id == subjectId }) {
            throw IllegalArgumentException("不认识的背诵词库：$subjectId（政治 politics / 常识 common）")
        }
        val subject = ReciteSubject.of(subjectId)
        val had = ReciteRepository.hasUserEdits(context, subject)
        ReciteRepository.resetToAsset(context, subject)
        val catalog = ReciteRepository.catalog(context, subject)
        return JSONObject()
            .put("ok", true)
            .put("subject", subject.id)
            .put("reset", had)
            .put("categoryCount", catalog.categories.size)
            .put("cardCount", catalog.cards.size)
    }

    private fun titleOf(context: Context, subject: ReciteSubject): String =
        context.getString(subject.titleRes)

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
