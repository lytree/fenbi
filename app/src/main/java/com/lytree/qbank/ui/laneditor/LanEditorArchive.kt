package com.lytree.qbank.ui.laneditor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 版本存档：每次「覆盖发布 / 回滚」之前，把这一组当时的样子原样存一份。
 *
 * 为什么要存：发布是按标题重导这一组，旧卷连同作答记录会被替换掉。
 * 作答记录救不回来（那是导入语义决定的），但**题目内容可以**——电脑上改错了、
 * 或者想看看上一版怎么写的，都能从版本列表里翻回来再「恢复」。
 *
 * 为什么落文件而不是建表：版本只是编辑器自己的东西，不进 App 的题库 schema，
 * 也就不必给主库升版本；一个版本一个 JSON，坏一个不影响别的，拷到电脑上也能直接看。
 */
class LanEditorArchive(root: File) {

    private val dir = File(root, "versions").apply { mkdirs() }

    /** 存一份快照，返回摘要（不含题目正文，列表用）；写盘失败返回 null，调用方据此决定提不提示 */
    fun save(title: String, setId: Long, reason: String, sheets: JSONArray): JSONObject? {
        val now = System.currentTimeMillis()
        val id = "$now-${(100..999).random()}"
        val obj = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("setId", setId)
            .put("reason", reason)
            .put("savedAt", now)
            .put("paperCount", sheets.length())
            .put("questionCount", countQuestions(sheets))
            .put("sheets", sheets)
        val written = runCatching { File(dir, "$id.json").writeText(obj.toString(), Charsets.UTF_8) }
        if (written.isFailure) return null
        prune(title)
        return summary(obj)
    }

    /** 某一组的历史版本，新的在前；[title] 为空时给全部（按时间倒序） */
    fun list(title: String?): JSONArray {
        val out = JSONArray()
        files()
            .mapNotNull { read(it) }
            .filter { title.isNullOrBlank() || it.optString("title") == title }
            .sortedByDescending { it.optLong("savedAt") }
            .forEach { out.put(summary(it)) }
        return out
    }

    fun read(id: String): JSONObject? {
        if (!SAFE_ID.matches(id)) return null
        return read(File(dir, "$id.json"))
    }

    fun delete(id: String): Boolean {
        if (!SAFE_ID.matches(id)) return false
        return File(dir, "$id.json").delete()
    }

    /** 每组只留最近 [keep] 份，更旧的直接清掉——版本堆到上百个既没用也占地方 */
    fun prune(title: String, keep: Int = 20) {
        files()
            .mapNotNull { f -> read(f)?.let { f to it } }
            .filter { it.second.optString("title") == title }
            .sortedByDescending { it.second.optLong("savedAt") }
            .drop(keep)
            .forEach { runCatching { it.first.delete() } }
    }

    private fun files(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList() ?: emptyList()

    private fun read(file: File): JSONObject? = runCatching {
        JSONObject(file.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun countQuestions(sheets: JSONArray): Int {
        var n = 0
        for (i in 0 until sheets.length()) {
            n += sheets.optJSONObject(i)?.optJSONArray("questions")?.length() ?: 0
        }
        return n
    }

    private fun summary(o: JSONObject): JSONObject = JSONObject()
        .put("id", o.optString("id"))
        .put("title", o.optString("title"))
        .put("reason", o.optString("reason"))
        .put("savedAt", o.optLong("savedAt"))
        .put("paperCount", o.optInt("paperCount"))
        .put("questionCount", o.optInt("questionCount"))

    private companion object {
        /** 文件名就是 id：`<毫秒时间戳>-<三位随机数>`，顺手挡掉 `../` 这类路径 */
        val SAFE_ID = Regex("""[0-9]{6,}-[0-9]{3}""")
    }
}
