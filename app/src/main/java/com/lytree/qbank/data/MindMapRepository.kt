package com.lytree.qbank.data

import android.content.Context
import com.lytree.qbank.data.model.MindMapCatalog
import com.lytree.qbank.data.model.MindMapNode
import com.lytree.qbank.data.model.MindMapPage
import com.lytree.qbank.data.model.MindMapPair
import org.json.JSONObject

/**
 * 思维导图大盘点的内容。只读内置资产 `assets/recite_mindmap.json`——
 * 这份内容是原书导图的层级还原（中心主题 → 分支 → 要点），
 * 不做填空、不支持网页编辑，因此也没有用户覆盖层与存库状态。
 *
 * 与政治背诵卡是两份数据：那边是「____ 填空自测」，这边是「整段通读 + 连线配对」。
 * 展开 / 收起状态只存在页面内存里，退出即恢复默认收起。
 */
object MindMapRepository {

    private const val ASSET = "recite_mindmap.json"

    @Volatile
    private var cached: MindMapCatalog? = null

    fun catalog(context: Context): MindMapCatalog {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = runCatching { parse(context) }.getOrElse { MindMapCatalog.EMPTY }
            cached = loaded
            return loaded
        }
    }

    private fun parse(context: Context): MindMapCatalog {
        val text = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)

        val pagesJson = root.getJSONArray("pages")
        val pages = ArrayList<MindMapPage>(pagesJson.length())
        for (i in 0 until pagesJson.length()) {
            val o = pagesJson.getJSONObject(i)
            val nodesJson = o.optJSONArray("outline")
            val nodes = ArrayList<MindMapNode>(nodesJson?.length() ?: 0)
            if (nodesJson != null) {
                for (j in 0 until nodesJson.length()) {
                    val n = nodesJson.getJSONObject(j)
                    val nodeText = n.optString("text")
                    if (nodeText.isBlank()) continue
                    nodes.add(MindMapNode(n.optInt("level", 1).coerceIn(0, 4), nodeText))
                }
            }
            val pairsJson = o.optJSONArray("pairs")
            val pairs = ArrayList<MindMapPair>(pairsJson?.length() ?: 0)
            if (pairsJson != null) {
                for (j in 0 until pairsJson.length()) {
                    val p = pairsJson.getJSONObject(j)
                    val left = p.optString("left")
                    val right = p.optString("right")
                    if (left.isNotBlank() && right.isNotBlank()) pairs.add(MindMapPair(left, right))
                }
            }
            pages.add(
                MindMapPage(
                    id = o.getString("id"),
                    page = o.optInt("page"),
                    name = o.optString("name"),
                    group = o.optString("group"),
                    nodes = nodes,
                    pairs = pairs,
                )
            )
        }

        return MindMapCatalog(
            title = root.optString("title", context.getString(com.lytree.qbank.R.string.mindmap_title)),
            updated = root.optString("updated"),
            intro = root.optString("intro"),
            pages = pages,
        )
    }
}
