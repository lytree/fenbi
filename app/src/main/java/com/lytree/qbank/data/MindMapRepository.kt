package com.lytree.qbank.data

import android.content.Context
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.MindMapCatalog
import com.lytree.qbank.data.model.MindMapNode
import com.lytree.qbank.data.model.MindMapPair
import com.lytree.qbank.data.model.MindMapSection
import com.lytree.qbank.data.model.MindMapTopic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 思维导图大盘点的内容。
 *
 * 内置资产 `assets/recite_mindmap.json` 是原书导图的层级还原（板块 → 主题 → 树），
 * 由 `tools/build_mindmap_asset.cs` 从 `tools/mindmap/tree.json` 生成。
 *
 * 内容也可以在设备上改：局域网编辑器（网页 / AI）保存的导图落在
 * filesDir 的覆盖文件里（[userFile]），读取时**优先于内置资产**——
 * 跟背诵词库一个套路，删掉覆盖文件就回到内置内容（[resetToAsset]）。
 *
 * 展开 / 收起状态只存在页面内存里，退出即恢复默认。
 */
object MindMapRepository {

    private const val ASSET = "recite_mindmap.json"

    /** 资产格式版本；改结构时同步 tools/build_mindmap_asset.cs */
    private const val VERSION = 2

    /** 递归解析的深度上限：正常最深 7 层，留点余量，防止手改坏了把栈压爆 */
    private const val MAX_DEPTH = 12

    /** 缓存带上覆盖文件的时间戳：被编辑器改写后自动重读 */
    private class Cached(val stamp: Long, val catalog: MindMapCatalog)

    @Volatile
    private var cache: Cached? = null

    /** 用户覆盖文件：存在即表示导图被编辑过 */
    fun userFile(context: Context): File =
        File(context.applicationContext.filesDir, "recite_mindmap_user.json")

    fun hasUserEdits(context: Context): Boolean = userFile(context).exists()

    fun catalog(context: Context): MindMapCatalog {
        val file = userFile(context)
        val stamp = if (file.exists()) file.lastModified() else -1L
        cache?.let { if (it.stamp == stamp) return it.catalog }
        synchronized(this) {
            cache?.let { if (it.stamp == stamp) return it.catalog }
            val text = runCatching {
                if (file.exists()) file.readText(Charsets.UTF_8)
                else context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse { return MindMapCatalog.EMPTY }
            val loaded = runCatching { parse(text) }.getOrElse { MindMapCatalog.EMPTY }
            cache = Cached(stamp, loaded)
            return loaded
        }
    }

    /**
     * 保存网页 / AI 送来的整份导图（结构校验通过后写覆盖文件）。
     * 先写临时文件再改名：写一半被杀最多丢这次保存，不会把旧内容写坏。
     */
    fun saveUserCatalog(context: Context, root: JSONObject) {
        validateUserCatalog(root)
        val file = userFile(context)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("覆盖文件写入失败（重命名没成功）")
        }
        cache = null
    }

    /**
     * 整份导图的结构校验。这些错了 App 里会显示成空板块 / 空卡片，
     * 不如存的时候就拦下来。校验失败抛 [IllegalArgumentException]，消息直接给网页看。
     */
    fun validateUserCatalog(root: JSONObject) {
        val sections = root.optJSONArray("sections")
            ?: throw IllegalArgumentException("sections 缺失或不是数组")
        if (sections.length() == 0) {
            throw IllegalArgumentException("至少要保留一个板块（导图页按板块分组）")
        }

        val sectionIds = HashSet<String>(sections.length())
        val topicIds = HashSet<String>()
        var totalNodes = 0

        for (i in 0 until sections.length()) {
            val s = sections.optJSONObject(i)
                ?: throw IllegalArgumentException("sections[$i] 不是对象")
            val sid = s.optString("id").trim()
            if (sid.isEmpty()) throw IllegalArgumentException("sections[$i] 缺少 id")
            if (!sectionIds.add(sid)) throw IllegalArgumentException("板块 id 重复：$sid")
            if (s.optString("name").isBlank()) throw IllegalArgumentException("板块「$sid」缺少 name")

            val topics = s.optJSONArray("topics")
                ?: throw IllegalArgumentException("板块「$sid」缺少 topics")
            if (topics.length() == 0) {
                throw IllegalArgumentException("板块「$sid」至少要有一个主题")
            }
            for (j in 0 until topics.length()) {
                val t = topics.optJSONObject(j)
                    ?: throw IllegalArgumentException("板块「$sid」的 topics[$j] 不是对象")
                val tid = t.optString("id").trim()
                if (tid.isEmpty()) throw IllegalArgumentException("板块「$sid」的 topics[$j] 缺少 id")
                if (!topicIds.add(tid)) throw IllegalArgumentException("主题 id 重复：$tid")
                if (t.optString("name").isBlank()) {
                    throw IllegalArgumentException("主题「$tid」缺少 name")
                }
                val nodes = t.optJSONArray("nodes")
                    ?: throw IllegalArgumentException("主题「$tid」缺少 nodes")
                if (nodes.length() == 0) {
                    throw IllegalArgumentException("主题「$tid」至少要有一个节点")
                }
                val count = countNodes(nodes, 1, tid)
                totalNodes += count

                val pairs = t.optJSONArray("pairs")
                if (pairs != null) {
                    for (k in 0 until pairs.length()) {
                        val p = pairs.optJSONObject(k)
                            ?: throw IllegalArgumentException("主题「$tid」的 pairs[$k] 不是对象")
                        if (p.optString("left").isBlank() || p.optString("right").isBlank()) {
                            throw IllegalArgumentException("主题「$tid」的 pairs[$k] 左右都得有内容")
                        }
                    }
                }
            }
        }

        if (topicIds.isEmpty()) throw IllegalArgumentException("整份导图没有主题")
        if (totalNodes == 0) throw IllegalArgumentException("整份导图没有节点")
    }

    /** 删掉用户覆盖文件，回到内置资产 */
    fun resetToAsset(context: Context) {
        userFile(context).delete()
        cache = null
    }

    // ---------------------------------------------------------------- 板块筛选

    /** 上次筛的板块 id；空串 = 全部。重进页面沿用，和背诵进度一样属于阅读状态 */
    fun sectionFilter(context: Context): String? =
        QBankDb.get(context).getSetting(KEY_SECTION)?.takeIf { it.isNotBlank() }

    fun setSectionFilter(context: Context, id: String?) {
        QBankDb.get(context).setSetting(KEY_SECTION, id.orEmpty())
    }

    private const val KEY_SECTION = "mindmap_section"

    // ---------------------------------------------------------------- 解析

    private fun parse(text: String): MindMapCatalog {
        val root = JSONObject(text)
        val version = root.optInt("version", 0)
        if (version != VERSION) {
            // 旧版资产（扁平 outline）不再支持：结构换成树以后渲染口径全变了，
            // 硬兼容只会让「到底读的哪份」说不清
            return MindMapCatalog.EMPTY
        }

        val sectionsJson = root.optJSONArray("sections") ?: return MindMapCatalog.EMPTY
        val sections = ArrayList<MindMapSection>(sectionsJson.length())
        for (i in 0 until sectionsJson.length()) {
            val s = sectionsJson.optJSONObject(i) ?: continue
            val topicsJson = s.optJSONArray("topics") ?: continue
            val topics = ArrayList<MindMapTopic>(topicsJson.length())
            for (j in 0 until topicsJson.length()) {
                val t = topicsJson.optJSONObject(j) ?: continue
                val id = t.optString("id").trim()
                if (id.isEmpty()) continue
                val nodesJson = t.optJSONArray("nodes")
                val nodes = ArrayList<MindMapNode>(nodesJson?.length() ?: 0)
                if (nodesJson != null) {
                    for (k in 0 until nodesJson.length()) {
                        val n = nodesJson.optJSONObject(k) ?: continue
                        parseNode(n, 1)?.let { nodes.add(it) }
                    }
                }
                if (nodes.isEmpty()) continue

                val pairsJson = t.optJSONArray("pairs")
                val pairs = ArrayList<MindMapPair>(pairsJson?.length() ?: 0)
                if (pairsJson != null) {
                    for (k in 0 until pairsJson.length()) {
                        val p = pairsJson.optJSONObject(k) ?: continue
                        val left = p.optString("left").trim()
                        val right = p.optString("right").trim()
                        if (left.isNotEmpty() && right.isNotEmpty()) {
                            pairs.add(MindMapPair(left, right))
                        }
                    }
                }

                topics.add(
                    MindMapTopic(
                        id = id,
                        page = t.optInt("page", 0),
                        label = t.optString("label").trim(),
                        name = t.optString("name").trim(),
                        nodes = nodes,
                        pairs = pairs,
                    )
                )
            }
            if (topics.isEmpty()) continue
            sections.add(
                MindMapSection(
                    id = s.optString("id").trim().ifEmpty { "s${i + 1}" },
                    name = s.optString("name").trim(),
                    topics = topics,
                )
            )
        }

        return MindMapCatalog(
            title = root.optString("title"),
            updated = root.optString("updated"),
            intro = root.optString("intro"),
            sections = sections,
        )
    }

    private fun parseNode(json: JSONObject, depth: Int): MindMapNode? {
        val text = json.optString("text").trim()
        if (text.isEmpty()) return null
        val children = ArrayList<MindMapNode>()
        val kidsJson = json.optJSONArray("children")
        if (kidsJson != null && depth < MAX_DEPTH) {
            for (i in 0 until kidsJson.length()) {
                val kid = kidsJson.optJSONObject(i) ?: continue
                parseNode(kid, depth + 1)?.let { children.add(it) }
            }
        }
        return MindMapNode(text, children)
    }

    /** 校验用：递归数节点，顺手把空文本拦下来 */
    private fun countNodes(nodes: JSONArray, depth: Int, topicId: String): Int {
        var n = 0
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i)
                ?: throw IllegalArgumentException("主题「$topicId」的节点不是对象")
            if (node.optString("text").isBlank()) {
                throw IllegalArgumentException("主题「$topicId」有空白节点")
            }
            if (depth > MAX_DEPTH) {
                throw IllegalArgumentException("主题「$topicId」层级超过 $MAX_DEPTH 层，检查一下是不是套错了")
            }
            n++
            val kids = node.optJSONArray("children")
            if (kids != null && kids.length() > 0) n += countNodes(kids, depth + 1, topicId)
        }
        return n
    }
}
