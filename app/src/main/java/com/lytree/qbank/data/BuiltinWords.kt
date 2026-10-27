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
 * 由 `tools/build_builtin_words.py` 从源 docx 生成——改词库改源文档再重跑脚本，
 * 不要手改这份 csv。
 *
 * 只在**首次**自动灌一次（SharedPreferences 记标记）：
 * 用户后来自己删词、整表覆盖，都不该被偷偷塞回来。想重新灌就进导入页选「内置词库」。
 */
object BuiltinWords {

    const val ASSET = "builtin/words.csv"

    /** 导入页/示例入口展示用 */
    const val LABEL = "内置词库（成语 + 实词）"

    private const val PREF = "qbank_builtin_words"
    private const val KEY_SEEDED = "seeded_v1"

    fun isSeeded(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_SEEDED, false)

    fun markSeeded(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SEEDED, true).apply()
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
        if (isSeeded(app)) {
            onDone(0)
            return
        }
        val main = Handler(Looper.getMainLooper())
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
