package com.lytree.qbank.data.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份历史：每成功备份一次记一条，与「本地留几份」无关。
 *
 * 为什么不直接拿文件列表当历史：备份包是会滚动的——本地只留 2 份（BackupManager.LOCAL_KEEP），
 * zip 迟早会没。但「哪天备过、多大、装了多少东西」这件事本身
 * 值得留着：用户查的是这个，而不是去文件管理器里数文件。
 *
 * 存在自己的 prefs 文件（[PREFS]）里，并且**不进备份包**——`BackupArchive.prefsToJson`
 * 会把整个 shared_prefs 目录打包，恢复时逐文件 clear 再写回；历史跟着旧包一起回来会让人
 * 以为刚备过，所以打包时跳过这个文件名。
 */
object BackupHistory {

    /** prefs 文件名。改这里要同步 [BackupArchive] 的打包排除名单 */
    const val PREFS = "backup_meta"

    private const val KEY_HISTORY = "history"

    /** 最多留 40 条，够翻一个多月；更早的连包都没了，留着只是噪音 */
    const val MAX = 40

    data class Entry(
        /** 备份包文件名，也是这条记录的 id（文件名里带分钟级时间戳，天然不重） */
        val name: String,
        /** 打包时刻，取自 manifest，不是「分享出去」的时刻 */
        val at: Long,
        val bytes: Long,
        /** 夜间闹钟自动跑的，还是用户点的（自动备份只有云端时代的历史，本地备份都是手动） */
        val auto: Boolean,
        val papers: Int,
        val questions: Int,
        val records: Int,
        val device: String = "",
        val version: String = ""
    )

    /** 新的在前 */
    fun all(context: Context): List<Entry> =
        read(context).sortedByDescending { it.at }

    /**
     * 记一条。同名包重复备（同一分钟点两次）只留最新那条，顺带把总条数压回 [MAX]。
     *
     * 用 commit 而不是 apply：自动备份跑在广播接收器里，进程可能马上被回收，
     * 异步落盘有丢记录的风险；这里就几十字节，同步写无所谓。
     */
    fun record(context: Context, entry: Entry) {
        val kept = read(context).filterNot { it.name == entry.name }
        val next = (listOf(entry) + kept.sortedByDescending { it.at }).take(MAX)
        write(context, next)
    }

    /** 只删记录，不动任何文件 */
    fun remove(context: Context, name: String) {
        write(context, read(context).filterNot { it.name == name })
    }

    fun clear(context: Context) {
        write(context, emptyList())
    }

    // ---------------------------------------------------------------- 存取

    private fun sp(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): List<Entry> {
        val raw = sp(context).getString(KEY_HISTORY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isBlank()) continue
            out += Entry(
                name = name,
                at = o.optLong("at"),
                bytes = o.optLong("bytes"),
                auto = o.optBoolean("auto"),
                papers = o.optInt("papers"),
                questions = o.optInt("questions"),
                records = o.optInt("records"),
                device = o.optString("device"),
                version = o.optString("version")
            )
        }
        return out
    }

    private fun write(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("name", e.name)
                    put("at", e.at)
                    put("bytes", e.bytes)
                    put("auto", e.auto)
                    put("papers", e.papers)
                    put("questions", e.questions)
                    put("records", e.records)
                    put("device", e.device)
                    put("version", e.version)
                }
            )
        }
        sp(context).edit().putString(KEY_HISTORY, arr.toString()).commit()
    }
}
