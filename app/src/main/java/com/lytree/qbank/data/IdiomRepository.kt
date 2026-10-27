package com.lytree.qbank.data

import android.content.Context
import com.lytree.qbank.data.model.Idiom
import com.lytree.qbank.data.model.IdiomCatalog
import com.lytree.qbank.data.model.IdiomGroup
import com.lytree.qbank.data.model.IdiomSub
import com.lytree.qbank.data.model.IdiomTag
import org.json.JSONArray
import org.json.JSONObject

/**
 * 成语词典资源读取。数据随 App 内置在 assets/idioms.json，
 * 首次打开时解析并缓存，后续切换 Tab 不再重复读取。
 */
object IdiomRepository {

    @Volatile
    private var cached: IdiomCatalog? = null

    fun catalog(context: Context): IdiomCatalog {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = runCatching { parse(context) }.getOrElse { IdiomCatalog.EMPTY }
            cached = loaded
            return loaded
        }
    }

    private fun parse(context: Context): IdiomCatalog {
        val text = context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)

        val groupsJson = root.getJSONArray("groups")
        val groups = ArrayList<IdiomGroup>(groupsJson.length())
        for (i in 0 until groupsJson.length()) {
            val g = groupsJson.getJSONObject(i)
            val subsJson = g.getJSONArray("subs")
            val subs = ArrayList<IdiomSub>(subsJson.length())
            for (j in 0 until subsJson.length()) {
                val s = subsJson.getJSONObject(j)
                subs.add(IdiomSub(s.getString("name"), s.optInt("count")))
            }
            groups.add(IdiomGroup(g.getString("name"), g.optInt("count"), subs))
        }

        val idiomsJson = root.getJSONArray("idioms")
        val idioms = ArrayList<Idiom>(idiomsJson.length())
        for (i in 0 until idiomsJson.length()) {
            val o = idiomsJson.getJSONObject(i)
            val tagsJson = o.optJSONArray("tags") ?: JSONArray()
            val tags = ArrayList<IdiomTag>(tagsJson.length())
            for (j in 0 until tagsJson.length()) {
                val pair = tagsJson.getJSONArray(j)
                tags.add(IdiomTag(pair.getString(0), if (pair.isNull(1)) null else pair.getString(1)))
            }
            idioms.add(Idiom(o.getString("w"), o.getString("d"), o.getString("l"), tags))
        }
        return IdiomCatalog(idioms, groups)
    }

    private const val ASSET_NAME = "idioms.json"
}
