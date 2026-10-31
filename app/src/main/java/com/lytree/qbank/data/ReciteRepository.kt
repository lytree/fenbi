package com.lytree.qbank.data

import android.content.Context
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ReciteCard
import com.lytree.qbank.data.model.ReciteCatalog
import com.lytree.qbank.data.model.ReciteCategory
import com.lytree.qbank.data.model.ReciteSubject
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 背诵数据。政治、常识各有一份随 App 内置的资产
 * （assets/recite_politics.json、assets/recite_common.json），
 * 掌握状态 / 重点收藏 / 分组编排走 settings 表，换设备升级后仍然保留。
 *
 * 两套内容的收藏、范围、上次卡片分开存（key 里带模块 id），字号与背诵模式共用。
 * 政治模块沿用老版本的 key，升级上来原有的收藏和进度不会丢。
 */
object ReciteRepository {

    private val cache = ConcurrentHashMap<String, ReciteCatalog>()

    fun catalog(context: Context, subject: ReciteSubject): ReciteCatalog {
        cache[subject.id]?.let { return it }
        synchronized(this) {
            cache[subject.id]?.let { return it }
            val loaded = runCatching { parse(context.applicationContext, subject) }
                .getOrElse { ReciteCatalog.EMPTY }
            cache[subject.id] = loaded
            return loaded
        }
    }

    private fun parse(context: Context, subject: ReciteSubject): ReciteCatalog {
        val text = context.assets.open(subject.asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)

        val categoriesJson = root.getJSONArray("categories")
        val categories = ArrayList<ReciteCategory>(categoriesJson.length())
        val names = HashMap<String, String>(categoriesJson.length())
        val groups = HashMap<String, String>(categoriesJson.length())
        for (i in 0 until categoriesJson.length()) {
            val o = categoriesJson.getJSONObject(i)
            val category = ReciteCategory(
                id = o.getString("id"),
                name = o.getString("name"),
                group = o.optString("group"),
            )
            categories.add(category)
            // 详情弹窗里显示「分组 · 分类」，分类名因此可以只写简称
            names[category.id] =
                if (category.group.isBlank()) category.name else "${category.group} · ${category.name}"
            groups[category.id] = category.group
        }

        val cardsJson = root.getJSONArray("cards")
        val cards = ArrayList<ReciteCard>(cardsJson.length())
        for (i in 0 until cardsJson.length()) {
            val o = cardsJson.getJSONObject(i)
            val answersJson = o.getJSONArray("answers")
            val answers = ArrayList<String>(answersJson.length())
            for (j in 0 until answersJson.length()) answers.add(answersJson.getString(j))
            val categoryId = o.getString("cat")
            cards.add(
                ReciteCard(
                    id = o.getString("id"),
                    categoryId = categoryId,
                    categoryName = names[categoryId] ?: categoryId,
                    categoryGroup = groups[categoryId].orEmpty(),
                    text = o.getString("text"),
                    answers = answers,
                    note = o.optString("note"),
                    source = o.optString("source"),
                    seq = i + 1,
                )
            )
        }

        return ReciteCatalog(
            title = root.optString("title", context.getString(subject.titleRes)),
            updated = root.optString("updated"),
            intro = root.optString("intro"),
            categories = categories,
            cards = cards,
        )
    }

    // ---------------------------------------------------------------- 分组编排

    /**
     * 用户对分组的编排：返回（自定义分组名列表, 分类 id -> 分组名 覆盖）。
     * 资产里的 group 只是默认值，用户改过的以这里为准。
     */
    fun groupLayout(context: Context, subject: ReciteSubject): Pair<List<String>, Map<String, String>> {
        val raw = QBankDb.get(context).getSetting(key(subject, KEY_GROUP_LAYOUT)).orEmpty()
        if (raw.isBlank()) return emptyList<String>() to emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val groups = ArrayList<String>()
            root.optJSONArray("groups")?.let { a ->
                for (i in 0 until a.length()) {
                    a.optString(i).takeIf { it.isNotBlank() }?.let(groups::add)
                }
            }
            val overrides = HashMap<String, String>()
            root.optJSONObject("map")?.let { m ->
                m.keys().forEach { key -> overrides[key] = m.optString(key) }
            }
            groups to (overrides as Map<String, String>)
        }.getOrElse { emptyList<String>() to emptyMap() }
    }

    fun saveGroupLayout(
        context: Context,
        subject: ReciteSubject,
        groups: List<String>,
        overrides: Map<String, String>
    ) {
        val root = JSONObject()
        root.put("groups", JSONArray(groups))
        val map = JSONObject()
        overrides.forEach { (k, v) -> map.put(k, v) }
        root.put("map", map)
        QBankDb.get(context).setSetting(key(subject, KEY_GROUP_LAYOUT), root.toString())
    }

    // ---------------------------------------------------------------- 掌握 / 重点

    fun masteredIds(context: Context, subject: ReciteSubject): Set<String> =
        readIds(context, key(subject, KEY_MASTERED))

    fun setMastered(context: Context, subject: ReciteSubject, cardId: String, mastered: Boolean) {
        writeIds(context, key(subject, KEY_MASTERED), masteredIds(context, subject), cardId, mastered)
    }

    fun favoriteIds(context: Context, subject: ReciteSubject): Set<String> =
        readIds(context, key(subject, KEY_FAVORITE))

    fun setFavorite(context: Context, subject: ReciteSubject, cardId: String, favorite: Boolean) {
        writeIds(context, key(subject, KEY_FAVORITE), favoriteIds(context, subject), cardId, favorite)
    }

    private fun readIds(context: Context, key: String): Set<String> {
        val raw = QBankDb.get(context).getSetting(key).orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw.split(',').filter { it.isNotBlank() }.toHashSet()
    }

    private fun writeIds(context: Context, key: String, current: Set<String>, id: String, add: Boolean) {
        val ids = current.toMutableSet()
        if (add) ids.add(id) else ids.remove(id)
        QBankDb.get(context).setSetting(key, ids.sorted().joinToString(","))
    }

    // ---------------------------------------------------------------- 字号 / 范围

    /** 字号与背诵模式是使用习惯，政治 / 常识共用 */
    fun fontLevel(context: Context): Int =
        QBankDb.get(context).getSetting(KEY_FONT)?.toIntOrNull()?.coerceIn(0, 2) ?: 0

    fun setFontLevel(context: Context, level: Int) {
        QBankDb.get(context).setSetting(KEY_FONT, level.coerceIn(0, 2).toString())
    }

    fun rememberedCategory(context: Context, subject: ReciteSubject): String? =
        QBankDb.get(context).getSetting(key(subject, KEY_CATEGORY))?.takeIf { it.isNotBlank() }

    fun rememberCategory(context: Context, subject: ReciteSubject, categoryId: String?) {
        QBankDb.get(context).setSetting(key(subject, KEY_CATEGORY), categoryId.orEmpty())
    }

    /** 上次背到哪张卡：切到词库 / 另一块背诵再切回来，接着上一张背 */
    fun lastCard(context: Context, subject: ReciteSubject): String? =
        QBankDb.get(context).getSetting(key(subject, KEY_LAST_CARD))?.takeIf { it.isNotBlank() }

    fun rememberCard(context: Context, subject: ReciteSubject, cardId: String) {
        QBankDb.get(context).setSetting(key(subject, KEY_LAST_CARD), cardId)
    }

    /** 背诵模式：开着就直接显示答案（通读 / 朗读），关着就长按看答案（自测） */
    fun reciteMode(context: Context): Boolean =
        QBankDb.get(context).getSetting(KEY_RECITE_MODE) == "1"

    fun setReciteMode(context: Context, on: Boolean) {
        QBankDb.get(context).setSetting(KEY_RECITE_MODE, if (on) "1" else "0")
    }

    /**
     * 按模块拼存库 key：政治是老模块，沿用 `recite_xxx` 这一批 key——
     * 之前存的收藏 / 范围 / 上次卡片升级后仍能读到；常识则是 `recite_common_xxx`。
     */
    private fun key(subject: ReciteSubject, base: String): String =
        if (subject == ReciteSubject.POLITICS) "recite_$base" else "recite_${subject.id}_$base"

    private const val KEY_GROUP_LAYOUT = "group_layout"
    private const val KEY_MASTERED = "mastered"
    private const val KEY_FAVORITE = "favorites"
    private const val KEY_CATEGORY = "category"
    private const val KEY_LAST_CARD = "last_card"

    /** 字号与背诵模式是使用习惯，政治 / 常识共用同一份，不带模块前缀 */
    private const val KEY_FONT = "recite_font_level"
    private const val KEY_RECITE_MODE = "recite_mode"
}
