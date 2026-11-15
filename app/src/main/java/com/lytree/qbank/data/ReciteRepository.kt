package com.lytree.qbank.data

import android.content.Context
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ReciteCard
import com.lytree.qbank.data.model.ReciteCatalog
import com.lytree.qbank.data.model.ReciteCategory
import com.lytree.qbank.data.model.ReciteSubject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 背诵数据。常识有一份随 App 内置的资产（assets/recite_common.json）；
 * 政治卡片库已经退场（政治页改读 `recite_library.json` 的大纲页），
 * 这里读政治只会得到空目录，保留枚举只是为了共用跳转与进度 key。
 * 掌握状态 / 重点收藏 / 分组编排走 settings 表，换设备升级后仍然保留。
 *
 * 内容本身也可以在设备上改：局域网编辑器（网页 / AI）保存的词库落在
 * filesDir 的用户覆盖文件里（[userFile]），读取时**优先于内置资产**——
 * 内置资产只在 APK 里改不了，覆盖文件删掉就回到内置内容（[resetToAsset]）。
 *
 * 两套内容的收藏、范围、上次卡片分开存（key 里带模块 id），字号与背诵模式共用。
 * 政治模块沿用老版本的 key，升级上来原有的收藏和进度不会丢。
 */
object ReciteRepository {

    /** 缓存带上内容来源的时间戳：覆盖文件被改写后（lastModified 变化）自动重读 */
    private class Cached(val stamp: Long, val catalog: ReciteCatalog)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** 用户覆盖文件：存在即表示这块内容被编辑过 */
    fun userFile(context: Context, subject: ReciteSubject): File =
        File(context.applicationContext.filesDir, "recite_${subject.id}_user.json")

    fun hasUserEdits(context: Context, subject: ReciteSubject): Boolean =
        userFile(context, subject).exists()

    fun catalog(context: Context, subject: ReciteSubject): ReciteCatalog {
        val file = userFile(context, subject)
        val stamp = if (file.exists()) file.lastModified() else -1L
        cache[subject.id]?.let { if (it.stamp == stamp) return it.catalog }
        synchronized(this) {
            cache[subject.id]?.let { if (it.stamp == stamp) return it.catalog }
            val loaded = runCatching { parse(context, subject, file) }
                .getOrElse { ReciteCatalog.EMPTY }
            cache[subject.id] = Cached(stamp, loaded)
            return loaded
        }
    }

    /**
     * 保存网页 / AI 送来的整份词库（结构校验通过后写覆盖文件）。
     *
     * 先写临时文件再改名：写一半崩溃 / 被杀，最多丢这次保存，不会把旧内容写坏。
     */
    fun saveUserCatalog(context: Context, subject: ReciteSubject, root: JSONObject) {
        validateUserCatalog(root)
        val file = userFile(context, subject)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("覆盖文件写入失败（重命名没成功）")
        }
        cache.remove(subject.id)
    }

    /**
     * 整份词库的结构校验：____ 数量必须与 answers 数量一致、id 不得重复、
     * cat 必须命中某个分类。这些错了 App 里会显示成乱码或对不上分类名，
     * 不如存的时候就拦下来。校验失败抛 [IllegalArgumentException]，消息直接给网页看。
     *
     * 独立出来给编辑器侧用：先 validate、重组字段，再落盘，避免「存到一半才报错」。
     */
    fun validateUserCatalog(root: JSONObject) {
        val categories = root.optJSONArray("categories")
            ?: throw IllegalArgumentException("categories 缺失或不是数组")
        val cards = root.optJSONArray("cards")
            ?: throw IllegalArgumentException("cards 缺失或不是数组")

        val catIds = HashSet<String>(categories.length())
        for (i in 0 until categories.length()) {
            val c = categories.optJSONObject(i)
                ?: throw IllegalArgumentException("categories[$i] 不是对象")
            val id = c.optString("id").trim()
            if (id.isEmpty()) throw IllegalArgumentException("categories[$i] 缺少 id")
            if (!catIds.add(id)) throw IllegalArgumentException("分类 id 重复：$id")
            if (c.optString("name").isBlank()) {
                throw IllegalArgumentException("分类「$id」缺少 name")
            }
        }

        val seen = HashSet<String>(cards.length())
        for (i in 0 until cards.length()) {
            val c = cards.optJSONObject(i) ?: throw IllegalArgumentException("cards[$i] 不是对象")
            val id = c.optString("id").trim()
            val text = c.optString("text")
            if (id.isEmpty()) throw IllegalArgumentException("cards[$i] 缺少 id")
            if (!seen.add(id)) throw IllegalArgumentException("卡片 id 重复：$id")
            val cat = c.optString("cat").trim()
            if (!catIds.contains(cat)) throw IllegalArgumentException("卡片「$id」的分类 $cat 不存在")
            if (text.isBlank()) throw IllegalArgumentException("卡片「$id」正文为空")
            val blanks = countBlanks(text)
            val answers = c.optJSONArray("answers") ?: JSONArray()
            if (blanks != answers.length()) {
                throw IllegalArgumentException(
                    "卡片「$id」正文有 $blanks 个 ____，但给了 ${answers.length()} 个答案"
                )
            }
        }
    }

    /** 删掉用户覆盖文件，回到内置资产的内容 */
    fun resetToAsset(context: Context, subject: ReciteSubject) {
        userFile(context, subject).delete()
        cache.remove(subject.id)
    }

    private fun countBlanks(text: String): Int {
        var n = 0
        var i = text.indexOf(BLANK)
        while (i >= 0) {
            n++
            i = text.indexOf(BLANK, i + BLANK.length)
        }
        return n
    }

    private const val BLANK = "____"

    private fun parse(context: Context, subject: ReciteSubject, override: File): ReciteCatalog {
        val text = if (override.exists()) {
            override.readText(Charsets.UTF_8)
        } else {
            context.assets.open(subject.asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
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
