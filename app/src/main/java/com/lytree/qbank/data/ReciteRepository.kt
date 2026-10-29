package com.lytree.qbank.data

import android.content.Context
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ReciteCard
import com.lytree.qbank.data.model.ReciteCatalog
import com.lytree.qbank.data.model.ReciteCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * 考点背诵数据。正文与分类随 App 内置在 assets/recite_politics.json，
 * 掌握状态 / 重点收藏走 settings 表，换设备升级后仍然保留。
 */
object ReciteRepository {

    @Volatile
    private var cached: ReciteCatalog? = null

    fun catalog(context: Context): ReciteCatalog {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = runCatching { parse(context.applicationContext) }.getOrElse { ReciteCatalog.EMPTY }
            cached = loaded
            return loaded
        }
    }

    private fun parse(context: Context): ReciteCatalog {
        val text = context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
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
            title = root.optString("title", "考点背诵"),
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
    fun groupLayout(context: Context): Pair<List<String>, Map<String, String>> {
        val raw = QBankDb.get(context).getSetting(KEY_GROUP_LAYOUT).orEmpty()
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

    fun saveGroupLayout(context: Context, groups: List<String>, overrides: Map<String, String>) {
        val root = JSONObject()
        root.put("groups", JSONArray(groups))
        val map = JSONObject()
        overrides.forEach { (k, v) -> map.put(k, v) }
        root.put("map", map)
        QBankDb.get(context).setSetting(KEY_GROUP_LAYOUT, root.toString())
    }

    // ---------------------------------------------------------------- 掌握 / 重点

    fun masteredIds(context: Context): Set<String> = readIds(context, KEY_MASTERED)

    fun setMastered(context: Context, cardId: String, mastered: Boolean) {
        writeIds(context, KEY_MASTERED, masteredIds(context), cardId, mastered)
    }

    fun favoriteIds(context: Context): Set<String> = readIds(context, KEY_FAVORITE)

    fun setFavorite(context: Context, cardId: String, favorite: Boolean) {
        writeIds(context, KEY_FAVORITE, favoriteIds(context), cardId, favorite)
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

    fun fontLevel(context: Context): Int =
        QBankDb.get(context).getSetting(KEY_FONT)?.toIntOrNull()?.coerceIn(0, 2) ?: 0

    fun setFontLevel(context: Context, level: Int) {
        QBankDb.get(context).setSetting(KEY_FONT, level.coerceIn(0, 2).toString())
    }

    fun rememberedCategory(context: Context): String? =
        QBankDb.get(context).getSetting(KEY_CATEGORY)?.takeIf { it.isNotBlank() }

    fun rememberCategory(context: Context, categoryId: String?) {
        QBankDb.get(context).setSetting(KEY_CATEGORY, categoryId.orEmpty())
    }

    private const val KEY_GROUP_LAYOUT = "recite_group_layout"
    private const val ASSET_NAME = "recite_politics.json"
    private const val KEY_MASTERED = "recite_mastered"
    private const val KEY_FAVORITE = "recite_favorites"
    private const val KEY_FONT = "recite_font_level"
    private const val KEY_CATEGORY = "recite_category"
}
