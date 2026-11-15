package com.lytree.qbank.ui.laneditor

import android.content.Context
import com.lytree.qbank.data.ReciteLibraryRepository
import com.lytree.qbank.data.model.LibraryItem
import com.lytree.qbank.data.model.LibraryTag
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 局域网编辑器的「政治理论大纲」接口——统一库 `assets/recite_library.json`。
 *
 * 政治这一块早就改读统一库（大纲页），旧的政治卡片库（`recite_politics.json`）已经退场，
 * 所以编辑器这边也直接对着统一库改，不再绕一层卡片。
 *
 * 与思维导图（[LanEditorMindMap]）一样是**整份读、整份写**：
 *
 * - [catalog]：标签 + 条目一次全量发给网页（1400 多条 + 18 个标签，几百 KB），编辑在前端做；
 * - [parseSave]：收下网页改过的整份库，丢掉多余字段、重算标签计数、校验后落盘；
 * - [executeReset]：删掉覆盖文件，回到 APK 内置那份。
 *
 * 网页保存仍要过手机确认（[LanEditorConfirm]），和词库 / 导图同一套闸门。
 */
object LanEditorLibrary {

    /** 整份库发给网页：标签（带条目数）+ 条目 + 有没有被编辑过 */
    fun catalog(context: Context): JSONObject {
        val library = ReciteLibraryRepository.library(context)

        val counts = HashMap<String, Int>()
        for (tag in library.tags) counts[tag.id] = 0
        for (item in library.items) {
            for (tag in item.tags) counts[tag]?.let { counts[tag] = it + 1 }
        }

        val tags = JSONArray()
        for (tag in library.tags) {
            tags.put(
                JSONObject()
                    .put("id", tag.id)
                    .put("name", tag.name)
                    .put("kind", tag.kind)
                    .put("count", counts[tag.id] ?: 0)
            )
        }

        val items = JSONArray()
        for (item in library.items) {
            val answers = JSONArray()
            item.answers.forEach { answers.put(it) }
            val itemTags = JSONArray()
            item.tags.forEach { itemTags.put(it) }
            items.put(
                JSONObject()
                    .put("id", item.id)
                    .put("kind", item.kind)
                    .put("text", item.text)
                    .put("answers", answers)
                    .put("source", item.source)
                    .put("tags", itemTags)
            )
        }

        return JSONObject()
            .put("version", VERSION)
            .put("title", library.title)
            .put("intro", library.intro)
            .put("updated", library.updated)
            .put("hasEdits", ReciteLibraryRepository.hasUserEdits(context))
            .put("itemCount", library.items.size)
            .put("tags", tags)
            .put("items", items)
    }

    /**
     * 解析网页送来的整份库，校验后重组（只留模型认识的字段）。
     * 任何结构问题都以 [IllegalArgumentException] 抛出，消息直接给网页看。
     */
    fun parseSave(context: Context, payload: JSONObject): JSONObject {
        val tagsIn = payload.optJSONArray("tags")
            ?: throw IllegalArgumentException("tags 缺失或不是数组")
        val tags = JSONArray()
        for (i in 0 until tagsIn.length()) {
            val t = tagsIn.optJSONObject(i)
                ?: throw IllegalArgumentException("tags[$i] 不是对象")
            val id = t.optString("id").trim()
            if (id.isEmpty()) continue
            val kind = t.optString("kind", LibraryTag.KIND_TOPIC).trim()
                .ifBlank { LibraryTag.KIND_TOPIC }
            tags.put(
                JSONObject()
                    .put("id", id)
                    .put("name", t.optString("name").trim().ifBlank { id })
                    .put("kind", kind)
            )
        }

        val itemsIn = payload.optJSONArray("items")
            ?: throw IllegalArgumentException("items 缺失或不是数组")
        val items = JSONArray()
        val counts = HashMap<String, Int>()
        for (i in 0 until itemsIn.length()) {
            val o = itemsIn.optJSONObject(i)
                ?: throw IllegalArgumentException("items[$i] 不是对象")
            // 网页上把正文删空 = 这条不要了，直接丢，别让校验拦一次
            val text = o.optString("text").trim()
            if (text.isEmpty()) continue
            val id = o.optString("id").trim().ifBlank { "web-${items.length() + 1}" }
            val answersIn = o.optJSONArray("answers") ?: JSONArray()
            val answers = JSONArray()
            for (j in 0 until answersIn.length()) answers.put(answersIn.optString(j).trim())
            val tagsArr = JSONArray()
            val itemTagsIn = o.optJSONArray("tags") ?: JSONArray()
            val seenTags = HashSet<String>(itemTagsIn.length())
            for (j in 0 until itemTagsIn.length()) {
                val tid = itemTagsIn.optString(j).trim()
                if (tid.isEmpty() || !seenTags.add(tid)) continue
                tagsArr.put(tid)
                counts[tid] = (counts[tid] ?: 0) + 1
            }
            items.put(
                JSONObject()
                    .put("id", id)
                    .put("kind", o.optString("kind", LibraryItem.KIND_CARD).trim()
                        .ifBlank { LibraryItem.KIND_CARD })
                    .put("text", text)
                    .put("answers", answers)
                    .put("source", o.optString("source").trim())
                    .put("tags", tagsArr)
            )
        }

        // 标签计数由条目重算：别信网页端传上来的旧数字
        for (i in 0 until tags.length()) {
            val t = tags.getJSONObject(i)
            t.put("count", counts[t.getString("id")] ?: 0)
        }

        val out = JSONObject()
            .put("version", VERSION)
            .put("title", payload.optString("title").trim().ifBlank { "政治理论" })
            .put("intro", payload.optString("intro").trim())
            .put("updated", today())
            .put("tags", tags)
            .put("items", items)

        // 复用落盘层的结构校验：id 唯一、正文非空、____ 数 = 答案数都在这里拦
        ReciteLibraryRepository.validateUserCatalog(out)
        return out
    }

    /** AI / 已确认的保存：直接落盘 */
    fun executeSave(context: Context, payload: JSONObject): JSONObject {
        val normalized = parseSave(context, payload)
        ReciteLibraryRepository.saveUserCatalog(context, normalized)
        val library = ReciteLibraryRepository.library(context)
        return JSONObject()
            .put("ok", true)
            .put("itemCount", library.items.size)
            .put("tagCount", library.tags.size)
            .put("updated", library.updated)
    }

    /** 重置回内置资产：删覆盖文件 */
    fun executeReset(context: Context): JSONObject {
        val had = ReciteLibraryRepository.hasUserEdits(context)
        ReciteLibraryRepository.resetToAsset(context)
        val library = ReciteLibraryRepository.library(context)
        return JSONObject()
            .put("ok", true)
            .put("reset", had)
            .put("itemCount", library.items.size)
            .put("tagCount", library.tags.size)
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private const val VERSION = 2
}
