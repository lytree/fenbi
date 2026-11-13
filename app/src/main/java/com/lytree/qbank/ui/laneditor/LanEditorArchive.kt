package com.lytree.qbank.ui.laneditor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

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

    /**
     * 存一份快照，返回摘要（不含题目正文，列表用）。
     *
     * 写盘失败**直接抛**：存档是覆盖发布唯一的后悔药，调用方必须据此取消这次覆盖，
     * 而不是当作「没存也没关系」继续写。
     */
    fun save(title: String, setId: Long, reason: String, sheets: JSONArray): JSONObject {
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
        val meta = summary(obj)
        // 先落全量内容（关键），再落摘要（只是索引，写不成最多列表慢一点）
        writeAtomically(File(dir, "$id.json"), obj.toString())
        runCatching { writeAtomically(metaFile(id), meta.toString()) }
        prune(title)
        return meta
    }

    /** 某一组的历史版本，新的在前；[title] 为空时给全部（按时间倒序） */
    fun list(title: String?): JSONArray {
        val out = JSONArray()
        files()
            .mapNotNull { metaOf(it) }
            .filter { title.isNullOrBlank() || it.optString("title") == title }
            .sortedByDescending { it.optLong("savedAt") }
            .forEach { out.put(it) }
        return out
    }

    fun read(id: String): JSONObject? {
        if (!SAFE_ID.matches(id)) return null
        return readJson(File(dir, "$id.json"))
    }

    fun delete(id: String): Boolean {
        if (!SAFE_ID.matches(id)) return false
        metaFile(id).delete()
        return File(dir, "$id.json").delete()
    }

    /** 每组只留最近 [keep] 份，更旧的直接清掉——版本堆到上百个既没用也占地方 */
    fun prune(title: String, keep: Int = 20) {
        files()
            .mapNotNull { f -> metaOf(f)?.let { f to it } }
            .filter { it.second.optString("title") == title }
            .sortedByDescending { it.second.optLong("savedAt") }
            .drop(keep)
            .forEach { (file, _) ->
                metaFile(file.name.removeSuffix(".json")).delete()
                runCatching { file.delete() }
            }
    }

    private fun metaFile(id: String): File = File(dir, "$id.meta.json")

    private fun files(): List<File> =
        dir.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && !f.name.endsWith(".meta.json")
        }?.toList() ?: emptyList()

    /**
     * 列表只要 6 个字段，没必要为它把整卷题目都解析一遍。
     * 优先读 `.meta.json`；老存档没有 meta 就退回读全文，读完顺手补一份。
     */
    private fun metaOf(file: File): JSONObject? {
        val id = file.name.removeSuffix(".json")
        readJson(metaFile(id))?.let { return it }
        val full = readJson(file) ?: return null
        val meta = summary(full)
        runCatching { writeAtomically(metaFile(id), meta.toString()) }
        return meta
    }

    /**
     * 先写 `.tmp` 再改名：直接往目标文件写，进程中途被杀会留下半截 JSON，
     * [readJson] 解析失败后会被当成「不存在」，版本就这么静默消失了。
     */
    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("版本存档写入失败：${target.name}")
        }
    }

    private fun readJson(file: File): JSONObject? = runCatching {
        if (!file.isFile) return null
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
