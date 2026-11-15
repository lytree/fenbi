package com.lytree.qbank.data

import android.content.Context
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.LibraryItem
import com.lytree.qbank.data.model.LibraryTag
import com.lytree.qbank.data.model.ReciteLibrary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 政治理论统一内容库（assets/recite_library.json）。
 *
 * 由 `tools/build_library.cs` 把原来的考点卡与思维导图合并而成：去重、剥离连线题伪节点、
 * 长句断句、打标签，产出「标签 + 条目」两层，**原来的板块 / 章节分组已经不在资产里**。
 *
 * 和背诵词库、导图一个套路：设备上可以改，网页 / AI 保存的内容落在 filesDir 的覆盖文件里，
 * 读取时优先于内置资产；删掉覆盖文件就回到内置内容（[resetToAsset]）。
 *
 * 已背进度、折叠状态按库存一份（`lib_done` / `lib_fold`），字号沿用背诵页那一套。
 */
object ReciteLibraryRepository {

    private const val ASSET = "recite_library.json"

    /** 缓存带上覆盖文件的时间戳：被改写后自动重读 */
    private class Cached(val stamp: Long, val library: ReciteLibrary)

    @Volatile
    private var cache: Cached? = null

    fun userFile(context: Context): File =
        File(context.applicationContext.filesDir, "recite_library_user.json")

    fun hasUserEdits(context: Context): Boolean = userFile(context).exists()

    fun library(context: Context): ReciteLibrary {
        val file = userFile(context)
        val stamp = if (file.exists()) file.lastModified() else -1L
        cache?.let { if (it.stamp == stamp) return it.library }
        synchronized(this) {
            cache?.let { if (it.stamp == stamp) return it.library }
            val text = runCatching {
                if (file.exists()) file.readText(Charsets.UTF_8)
                else context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse { return ReciteLibrary.EMPTY }
            val loaded = runCatching { parse(text) }.getOrElse { ReciteLibrary.EMPTY }
            cache = Cached(stamp, loaded)
            return loaded
        }
    }

    /** 删掉用户覆盖文件，回到内置内容 */
    fun resetToAsset(context: Context) {
        userFile(context).delete()
        cache = null
    }

    private fun parse(text: String): ReciteLibrary {
        val root = JSONObject(text)

        val tagsJson = root.optJSONArray("tags")
        val tags = ArrayList<LibraryTag>(tagsJson?.length() ?: 0)
        if (tagsJson != null) {
            for (i in 0 until tagsJson.length()) {
                val o = tagsJson.getJSONObject(i)
                tags.add(
                    LibraryTag(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        kind = o.optString("kind", LibraryTag.KIND_TOPIC),
                        count = o.optInt("count"),
                    )
                )
            }
        }
        val topicIds = tags.filter { it.isTopic }.map { it.id }.toHashSet()

        val itemsJson = root.optJSONArray("items")
        val items = ArrayList<LibraryItem>(itemsJson?.length() ?: 0)
        if (itemsJson != null) {
            for (i in 0 until itemsJson.length()) {
                val o = itemsJson.getJSONObject(i)
                val tagList = ArrayList<String>()
                o.optJSONArray("tags")?.let { a ->
                    for (j in 0 until a.length()) a.optString(j).takeIf { it.isNotBlank() }?.let(tagList::add)
                }
                val answers = ArrayList<String>()
                o.optJSONArray("answers")?.let { a ->
                    for (j in 0 until a.length()) answers.add(a.optString(j))
                }
                val ctx = o.optJSONObject("context")
                items.add(
                    LibraryItem(
                        id = o.getString("id"),
                        kind = o.optString("kind", LibraryItem.KIND_CARD),
                        text = o.optString("text"),
                        answers = answers,
                        source = o.optString("source"),
                        tags = tagList,
                        // 主归组取第一个内容标签：来源标签（超格 / 精编 / 导图）不参与分组
                        mainTag = tagList.firstOrNull { it in topicIds }.orEmpty(),
                        topic = ctx?.optString("topic").orEmpty(),
                        topicName = ctx?.optString("name").orEmpty(),
                        path = ctx?.optString("path").orEmpty(),
                        depth = ctx?.optInt("depth") ?: 0,
                        cloze = clozeSpans(o.optJSONArray("cloze")),
                    )
                )
            }
        }

        return ReciteLibrary(
            title = root.optString("title"),
            updated = root.optString("updated"),
            intro = root.optString("intro"),
            tags = tags,
            items = items,
        )
    }

    /** 挖空区间：[start, end) 转成含头含尾的 IntRange；越界的直接丢掉，坏数据不该让页面崩 */
    private fun clozeSpans(array: JSONArray?): List<IntRange> {
        if (array == null || array.length() == 0) return emptyList()
        val out = ArrayList<IntRange>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val start = o.optInt("start", -1)
            val end = o.optInt("end", -1)
            if (start in 0 until end) out.add(start until end)
        }
        return out
    }

    // ---------------------------------------------------------------- 已背进度

    /**
     * 已背条目 id。
     *
     * 第一次读时把老卡片流背过的（`recite_mastered`）并进来：合并后卡片 id 没变，
     * 不该因为换了界面就把进度清零。只迁一次，之后以 `lib_done` 为准。
     */
    fun doneIds(context: Context): Set<String> {
        val db = QBankDb.get(context)
        val raw = db.getSetting(KEY_DONE)
        if (raw == null && db.getSetting(KEY_MIGRATED) == null) {
            db.setSetting(KEY_MIGRATED, "1")
            val legacy = db.getSetting(KEY_LEGACY_MASTERED).orEmpty()
            if (legacy.isNotBlank()) {
                db.setSetting(KEY_DONE, legacy)
                return legacy.split(',').filter { it.isNotBlank() }.toHashSet()
            }
        }
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',').filter { it.isNotBlank() }.toHashSet()
    }

    fun setDone(context: Context, id: String, done: Boolean): Set<String> {
        val ids = doneIds(context).toMutableSet()
        if (done) ids.add(id) else ids.remove(id)
        writeSet(context, KEY_DONE, ids)
        return ids
    }

    fun clearDone(context: Context) {
        QBankDb.get(context).setSetting(KEY_DONE, "")
    }

    // ---------------------------------------------------------------- 折叠状态

    /** 折叠起来的标签 id；没记过时返回 null，让页面自己定默认折叠 */
    fun foldedTags(context: Context): Set<String>? {
        val raw = QBankDb.get(context).getSetting(KEY_FOLD)
        if (raw == null) return null
        return raw.split(',').filter { it.isNotBlank() }.toHashSet()
    }

    fun saveFoldedTags(context: Context, ids: Set<String>) {
        QBankDb.get(context).setSetting(KEY_FOLD, ids.joinToString(","))
    }

    // ---------------------------------------------------------------- 筛选

    /** 只看未背：大纲页的开关，切走再回来保持 */
    fun onlyUnmastered(context: Context): Boolean =
        QBankDb.get(context).getSetting(KEY_ONLY_UNMASTERED) == "1"

    fun setOnlyUnmastered(context: Context, on: Boolean) {
        QBankDb.get(context).setSetting(KEY_ONLY_UNMASTERED, if (on) "1" else "0")
    }

    /**
     * 遮答预览：遮住挖空、点行才揭示。
     *
     * 和背诵页的 `recite_mode` 是两件事：那边默认就遮（那边就是用来背的），
     * 大纲页默认**不遮**——打开一屏全是下划线没法读，用户会以为页面坏了。
     */
    fun blind(context: Context): Boolean =
        QBankDb.get(context).getSetting(KEY_BLIND) == "1"

    fun setBlind(context: Context, on: Boolean) {
        QBankDb.get(context).setSetting(KEY_BLIND, if (on) "1" else "0")
    }

    // ---------------------------------------------------------------- 滚动位置

    /** 上次停在第几行（行号只对同一套折叠状态有意义，切筛选后会回到顶部） */
    fun lastScroll(context: Context): Int =
        QBankDb.get(context).getSetting(KEY_SCROLL)?.toIntOrNull() ?: -1

    fun rememberScroll(context: Context, position: Int) {
        QBankDb.get(context).setSetting(KEY_SCROLL, position.toString())
    }

    // ---------------------------------------------------------------- 背诵流位置

    /** 上次背到哪一条（`lib_last`）：聚焦背诵页每切一条写一次，大纲页「继续」读它 */
    fun lastDrill(context: Context): String? =
        QBankDb.get(context).getSetting(KEY_LAST)?.takeIf { it.isNotBlank() }

    fun rememberDrill(context: Context, itemId: String) {
        QBankDb.get(context).setSetting(KEY_LAST, itemId)
    }

    // ---------------------------------------------------------------- 小工具

    private fun writeSet(context: Context, key: String, ids: Set<String>) {
        QBankDb.get(context).setSetting(key, ids.joinToString(","))
    }

    private const val KEY_DONE = "lib_done"
    private const val KEY_FOLD = "lib_fold"
    private const val KEY_ONLY_UNMASTERED = "lib_only_unmastered"
    private const val KEY_BLIND = "lib_blind"
    private const val KEY_SCROLL = "lib_scroll"
    private const val KEY_LAST = "lib_last"
    private const val KEY_MIGRATED = "lib_migrated"

    /** 老卡片流的「已掌握」集合，只在新库第一次读时搬一次 */
    private const val KEY_LEGACY_MASTERED = "recite_mastered"
}
