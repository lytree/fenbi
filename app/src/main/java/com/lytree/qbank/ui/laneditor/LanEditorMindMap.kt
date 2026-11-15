package com.lytree.qbank.ui.laneditor

import android.content.Context
import com.lytree.qbank.data.MindMapRepository
import com.lytree.qbank.data.model.MindMapNode
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 局域网编辑器的思维导图接口。
 *
 * 数据面在 [MindMapRepository]：内置资产只读，编辑结果落在 filesDir 的用户覆盖文件里，
 * App 读内容时覆盖文件优先。这里只做三件事：
 *
 * - 整份读出：10 个板块 / 36 个主题 / 一千多节点的树，一次全量发给网页，编辑在前端做；
 * - 收下网页改过的整份树：结构校验（板块非空、主题 id 唯一、节点不空）走
 *   [MindMapRepository.validateUserCatalog]，网页保存还要过手机确认（[LanEditorConfirm]）；
 * - 重置回内置资产。
 *
 * 与背诵词库（[LanEditorRecite]）的区别：这边只有一个「整份」，没有政治 / 常识之分。
 */
object LanEditorMindMap {

    /** 整份导图发给网页：结构 + 统计 + 有没有被编辑过 */
    fun catalog(context: Context): JSONObject {
        val catalog = MindMapRepository.catalog(context)

        val sections = JSONArray()
        for (section in catalog.sections) {
            val topics = JSONArray()
            for (topic in section.topics) {
                val nodes = JSONArray()
                for (node in topic.nodes) nodes.put(nodeJson(node))
                val pairs = JSONArray()
                for (pair in topic.pairs) {
                    pairs.put(JSONObject().put("left", pair.left).put("right", pair.right))
                }
                topics.put(
                    JSONObject()
                        .put("id", topic.id)
                        .put("page", topic.page)
                        .put("label", topic.label)
                        .put("name", topic.name)
                        .put("nodeCount", topic.nodeCount)
                        .put("nodes", nodes)
                        .put("pairs", pairs)
                )
            }
            sections.put(
                JSONObject()
                    .put("id", section.id)
                    .put("name", section.name)
                    .put("nodeCount", section.nodeCount)
                    .put("topics", topics)
            )
        }

        return JSONObject()
            .put("version", 2)
            .put("title", catalog.title)
            .put("intro", catalog.intro)
            .put("updated", catalog.updated)
            .put("hasEdits", MindMapRepository.hasUserEdits(context))
            .put("topicCount", catalog.topicCount)
            .put("nodeCount", catalog.nodeCount)
            .put("sections", sections)
    }

    /**
     * 解析网页送来的整份导图，校验后重组（丢掉多余字段，只留模型认识的）。
     * 任何结构问题都以 [IllegalArgumentException] 抛出，消息直接给网页看。
     */
    fun parseSave(context: Context, payload: JSONObject): JSONObject {
        val sectionsIn = payload.optJSONArray("sections")
            ?: throw IllegalArgumentException("sections 缺失或不是数组")

        val sections = JSONArray()
        for (i in 0 until sectionsIn.length()) {
            val s = sectionsIn.optJSONObject(i)
                ?: throw IllegalArgumentException("sections[$i] 不是对象")
            val topicsIn = s.optJSONArray("topics") ?: JSONArray()
            val topics = JSONArray()
            for (j in 0 until topicsIn.length()) {
                val t = topicsIn.optJSONObject(j)
                    ?: throw IllegalArgumentException("sections[$i].topics[$j] 不是对象")
                val nodesIn = t.optJSONArray("nodes") ?: JSONArray()
                val nodes = JSONArray()
                for (k in 0 until nodesIn.length()) {
                    val n = nodesIn.optJSONObject(k)
                        ?: throw IllegalArgumentException("主题「${t.optString("id")}」的 nodes[$k] 不是对象")
                    normalizeNode(n)?.let { nodes.put(it) }
                }
                val pairsIn = t.optJSONArray("pairs") ?: JSONArray()
                val pairs = JSONArray()
                for (k in 0 until pairsIn.length()) {
                    val p = pairsIn.optJSONObject(k) ?: continue
                    val left = p.optString("left").trim()
                    val right = p.optString("right").trim()
                    if (left.isEmpty() && right.isEmpty()) continue
                    pairs.put(JSONObject().put("left", left).put("right", right))
                }
                topics.put(
                    JSONObject()
                        .put("id", t.optString("id").trim())
                        .put("page", t.optInt("page", 0))
                        .put("label", t.optString("label").trim())
                        .put("name", t.optString("name").trim())
                        .put("nodes", nodes)
                        .put("pairs", pairs)
                )
            }
            sections.put(
                JSONObject()
                    .put("id", s.optString("id").trim())
                    .put("name", s.optString("name").trim())
                    .put("topics", topics)
            )
        }

        val out = JSONObject()
            .put("version", 2)
            .put("title", payload.optString("title").trim().ifBlank { "政治理论思维导图大盘点" })
            .put("intro", payload.optString("intro").trim())
            .put("updated", today())
            .put("sections", sections)
        // 复用落盘层的结构校验：板块/主题/节点非空、id 唯一都在这里拦
        MindMapRepository.validateUserCatalog(out)
        return out
    }

    /** AI / 已确认的保存：直接落盘 */
    fun executeSave(context: Context, payload: JSONObject): JSONObject {
        val normalized = parseSave(context, payload)
        MindMapRepository.saveUserCatalog(context, normalized)
        val catalog = MindMapRepository.catalog(context)
        return JSONObject()
            .put("ok", true)
            .put("sectionCount", catalog.sections.size)
            .put("topicCount", catalog.topicCount)
            .put("nodeCount", catalog.nodeCount)
            .put("updated", catalog.updated)
    }

    /** 重置回内置资产：删覆盖文件 */
    fun executeReset(context: Context): JSONObject {
        val had = MindMapRepository.hasUserEdits(context)
        MindMapRepository.resetToAsset(context)
        val catalog = MindMapRepository.catalog(context)
        return JSONObject()
            .put("ok", true)
            .put("reset", had)
            .put("sectionCount", catalog.sections.size)
            .put("topicCount", catalog.topicCount)
            .put("nodeCount", catalog.nodeCount)
    }

    // ---------------------------------------------------------------- 小工具

    /** 整份导图的节点总数，确认框和保存结果里要用 */
    fun countNodes(root: JSONObject): Int {
        val sections = root.optJSONArray("sections") ?: return 0
        var total = 0
        for (i in 0 until sections.length()) {
            val topics = sections.optJSONObject(i)?.optJSONArray("topics") ?: continue
            for (j in 0 until topics.length()) {
                val nodes = topics.optJSONObject(j)?.optJSONArray("nodes") ?: continue
                total += countNodeArray(nodes)
            }
        }
        return total
    }

    private fun countNodeArray(nodes: JSONArray): Int {
        var n = 0
        for (i in 0 until nodes.length()) {
            n++
            val kids = nodes.optJSONObject(i)?.optJSONArray("children") ?: continue
            n += countNodeArray(kids)
        }
        return n
    }

    private fun nodeJson(node: MindMapNode): JSONObject {
        val children = JSONArray()
        for (child in node.children) children.put(nodeJson(child))
        val json = JSONObject().put("text", node.text)
        if (node.children.isNotEmpty()) json.put("children", children)
        return json
    }

    /** 只留 text / children，顺手丢掉空白节点（网页上删空一行很常见） */
    private fun normalizeNode(json: JSONObject): JSONObject? {
        val text = json.optString("text").trim()
        if (text.isEmpty()) return null
        val kidsIn = json.optJSONArray("children")
        val kids = JSONArray()
        if (kidsIn != null) {
            for (i in 0 until kidsIn.length()) {
                val kid = kidsIn.optJSONObject(i) ?: continue
                normalizeNode(kid)?.let { kids.put(it) }
            }
        }
        val out = JSONObject().put("text", text)
        if (kids.length() > 0) out.put("children", kids)
        return out
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
