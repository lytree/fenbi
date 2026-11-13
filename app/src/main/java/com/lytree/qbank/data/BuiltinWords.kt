package com.lytree.qbank.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.importer.WordFormat
import com.lytree.qbank.data.importer.WordImporter
import kotlin.concurrent.thread

/**
 * 内置词库：把《成语与实词汇总（合并版）》预置进 App，装上就有词，不用先找文件导入。
 *
 * 数据是 `assets/builtin/words.csv`（表头：词条/拼音/分类/细分/释义/出处），
 * 打底由 `tools/BuildBuiltinWords.cs`（.NET file-based app，`dotnet run` 直接跑）从合并版 docx 生成；
 * 分组标签与 244 条新词由 `tools/extract_word_tags.cs` 读四海 800 词上下册补上。
 * 改词库请改源文档后重跑脚本，不要手改这份 csv。
 *
 * 只在**首次**自动灌一次（SharedPreferences 记标记）：
 * 用户后来自己删词、整表覆盖，都不该被偷偷塞回来。想重新灌就进导入页选「内置词库」。
 *
 * 标记从 v1 升到 v2 是因为词库补了「分组标签 + 四海 800 词的 244 条新词」，
 * 老用户升上来会再合并一次（按词条文本 upsert，不会产生重复行）。
 */
object BuiltinWords {

    const val ASSET = "builtin/words.csv"

    /** 导入页/示例入口展示用 */
    const val LABEL = "内置词库（成语 + 实词）"

    private const val PREF = "qbank_builtin_words"
    private const val KEY_SEEDED = "seeded_v2"

    /**
     * 分组标签补齐标记（v3）。
     *
     * v2 的词库里，来自《成语与实词汇总》的六百多条成语没有分组标签；
     * 这批标签由 `tools/extra_tags.json` 人工按释义补上，走 [seedIfNeeded] 的补标签分支，
     * 只 UPDATE 标签为空的词条——不新增、不覆盖，用户删过的词不会被塞回来。
     */
    private const val KEY_TAGS = "tags_v3"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isSeeded(context: Context): Boolean = prefs(context).getBoolean(KEY_SEEDED, false)

    fun markSeeded(context: Context) {
        prefs(context).edit().putBoolean(KEY_SEEDED, true).apply()
    }

    fun isTagPatched(context: Context): Boolean = prefs(context).getBoolean(KEY_TAGS, false)

    fun markTagPatched(context: Context) {
        prefs(context).edit().putBoolean(KEY_TAGS, true).apply()
    }

    fun readBytes(context: Context): ByteArray =
        context.assets.open(ASSET).use { it.readBytes() }

    /** 解析 asset（不落库），导入页复用它做预览 */
    fun parse(context: Context) =
        WordImporter.build(context, WordFormat.CSV, readBytes(context))

    /**
     * 首次启动灌库。回调在主线程：正数 = 新灌入条数，0 = 已灌过跳过，-1 = 失败。
     *
     * 放在后台线程是必须的：2200 多条要逐字算拼音再写库，主线程会卡出 ANR。
     */
    fun seedIfNeeded(context: Context, db: QBankDb, onDone: (Int) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())

        // 已经全量灌过：只做一次「补空白标签」，跑完就不再碰用户的词库
        if (isSeeded(app)) {
            if (isTagPatched(app)) {
                onDone(0)
                return
            }
            thread(name = "builtin-words-tags") {
                var result = 0
                try {
                    val outcome = WordImporter.build(app, WordFormat.CSV, readBytes(app))
                    val tags = HashMap<String, String>()
                    outcome.words.forEach { w ->
                        if (w.text.isNotBlank() && w.tag.isNotBlank()) tags[w.text] = w.tag
                    }
                    val n = db.fillWordTags(tags)
                    android.util.Log.i("BuiltinWords", "补标签：候选 ${tags.size}，更新 $n")
                    markTagPatched(app)
                } catch (e: Exception) {
                    android.util.Log.e("BuiltinWords", "补标签失败", e)
                    result = -1
                }
                // 补标签不算「新灌入」，回 0 让页面只刷新不提示条数
                main.post { onDone(result) }
            }
            return
        }

        thread(name = "builtin-words-seed") {
            var result = -1
            try {
                val outcome = WordImporter.build(app, WordFormat.CSV, readBytes(app))
                android.util.Log.i(
                    "BuiltinWords",
                    "内置词库解析：rows=${outcome.total} words=${outcome.words.size} " +
                        "idiom=${outcome.idiomCount} word=${outcome.contentCount}"
                )
                if (outcome.words.isNotEmpty()) {
                    result = db.upsertWords(outcome.words, replaceAll = false)
                    markSeeded(app)
                    markTagPatched(app)
                }
            } catch (e: Exception) {
                android.util.Log.e("BuiltinWords", "内置词库灌库失败", e)
                result = -1
            }
            android.util.Log.i("BuiltinWords", "灌库结束 result=$result")
            main.post { onDone(result) }
        }
    }
}
