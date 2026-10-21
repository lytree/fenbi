package com.lytree.qbank.data.importer

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/**
 * JSON 题库解析。
 *
 * 兼容三种结构：
 *  1. {"bank":"行测真题", "questions":[{...}, ...]}
 *  2. {"questions":[...]}  /  {"data":[...]} /  {"list":[...]}
 *  3. 顶层直接是数组 [...]
 *
 * 单题字段支持多种写法：
 *  - options: {"A":"文本"} / [{"key":"A","text":"..."}] / ["A. 文本","B. 文本"]
 *  - 也有 A/B/C/D 平铺字段
 */
object JsonImporter {

    fun parse(input: InputStream, issues: MutableList<ImportIssue>): ImportResult {
        val text = input.readBytes().toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "文件内容为空"))
            return ImportResult(emptyList(), issues, "JSON", 0)
        }

        val root = try {
            JSONTokenerCompat.parse(text)
        } catch (e: Exception) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "JSON 解析失败：${e.message}"))
            return ImportResult(emptyList(), issues, "JSON", 0)
        }

        val array: JSONArray = when {
            root is JSONArray -> root
            root is JSONObject -> {
                var arr: JSONArray? = null
                for (k in listOf("questions", "questionList", "data", "list", "items", "rows", "题目", "题库")) {
                    val v = root.opt(k)
                    if (v is JSONArray) { arr = v; break }
                }
                if (arr == null) {
                    // 兼容 {"A": {...}, "B": {...}} 这种 key 即答案的结构
                    val keys = root.keys()
                    val tmp = JSONArray()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = root.opt(k)
                        if (v is JSONObject) {
                            val o = JSONObject(v.toString())
                            if (!o.has(Field.ANSWER)) o.put(Field.ANSWER, k)
                            tmp.put(o)
                        }
                    }
                    arr = tmp
                }
                arr
            }
            else -> JSONArray()
        }

        val raws = ArrayList<RawQuestion>()
        for (i in 0 until array.length()) {
            val rowNo = i + 1
            val obj = array.opt(i)
            if (obj !is JSONObject) {
                issues.add(ImportIssue(rowNo, ImportIssue.Level.ERROR, "第 $rowNo 项不是对象，已跳过"))
                continue
            }
            val raw = RawQuestion(rowNo)

            // 先抽选项
            when (val ov = obj.opt("options")) {
                is JSONObject -> {
                    val keys = ov.keys()
                    val sorted = mutableListOf<String>()
                    while (keys.hasNext()) sorted.add(keys.next())
                    sorted.sortedBy { it }
                    sorted.forEach { k ->
                        val v = ov.opt(k)
                        raw.options[k.uppercase()] = when (v) {
                            is JSONObject -> v.optString("text", v.optString("t", v.optString("value", "")))
                            null -> ""
                            else -> v.toString()
                        }
                    }
                }
                is JSONArray -> {
                    for (j in 0 until ov.length()) {
                        val e = ov.opt(j)
                        when (e) {
                            is JSONObject -> {
                                val k = e.optString("key", e.optString("k", e.optString("id", "")))
                                val t = e.optString("text", e.optString("t", e.optString("value", e.optString("content", ""))))
                                if (k.isNotEmpty()) raw.options[k.uppercase()] = t
                            }
                            is String -> {
                                val parsed = parseInlineOption(e)
                                if (parsed != null) raw.options[parsed.first] = parsed.second
                            }
                            else -> Unit
                        }
                    }
                }
                is String -> {
                    parseInlineOptions(raw, ov)
                }
                else -> Unit
            }

            // 平铺的 A/B/C/D
            for (k in listOf("A", "B", "C", "D", "E", "F", "G", "H")) {
                if (!raw.options.containsKey(k)) {
                    val v = obj.opt(k)
                    if (v is String && v.isNotBlank()) raw.options[k] = v
                }
            }

            // 其它字段
            for (field in listOf(
                Field.TYPE, Field.STEM, Field.MATERIAL, Field.ANSWER, Field.ANALYSIS,
                Field.KNOWLEDGE, Field.DIFFICULTY, Field.REGION, Field.SUBJECT, Field.SOURCE,
                Field.YEAR, Field.SCORE, Field.KEYWORDS
            )) {
                val v = obj.opt(field)
                if (v != null && v !is JSONArray && v !is JSONObject) {
                    raw.put(field, v.toString())
                }
            }
            // 中文别名兜底
            val aliasFallback = mapOf(
                "题干" to Field.STEM, "题目" to Field.STEM, "题型" to Field.TYPE,
                "答案" to Field.ANSWER, "正确答案" to Field.ANSWER, "解析" to Field.ANALYSIS,
                "知识点" to Field.KNOWLEDGE, "难度" to Field.DIFFICULTY, "地区" to Field.REGION,
                "科目" to Field.SUBJECT, "来源" to Field.SOURCE, "年份" to Field.YEAR,
                "分值" to Field.SCORE, "材料" to Field.MATERIAL, "得分点" to Field.KEYWORDS
            )
            for ((zh, field) in aliasFallback) {
                if (raw.get(field).isEmpty()) {
                    val v = obj.opt(zh)
                    if (v != null && v !is JSONArray && v !is JSONObject) raw.put(field, v.toString())
                }
            }
            // 未知字段也保留，便于用户排查
            val extra = JSONObject()
            for (k in obj.keys()) {
                if (k in setOf("options", "A", "B", "C", "D", "E", "F", "G", "H")) continue
                if (FieldAliases.resolve(k) != null) continue
                if (aliasFallback.containsKey(k)) continue
                val v = obj.opt(k)
                if (v is String || v is Number || v is Boolean) extra.put(k, v)
            }
            if (extra.length() > 0) raw.put("__extra__", extra.toString())

            raws.add(raw)
        }

        return ImportResult(raws, issues, "JSON", array.length())
    }

    /** "A. 文本" -> ("A", "文本")；"A、文本" 同理；无前缀则按顺序取字母 */
    fun parseInlineOption(s: String): Pair<String, String>? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val m = Regex("^([A-Ha-h])\\s*[.、．:：)）]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(t)
        if (m != null) return m.groupValues[1].uppercase() to m.groupValues[2].trim()
        return null
    }

    /** 解析一整块选项文本，支持换行 / 竖线 / 分号分隔 */
    fun parseInlineOptions(raw: RawQuestion, text: String) {
        val parts = text.split('\n', '|', '；', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        for (p in parts) {
            val parsed = parseInlineOption(p)
            if (parsed != null) {
                raw.options[parsed.first] = parsed.second
            } else {
                // 无前缀：按出现顺序分配字母
                val idx = raw.options.size
                if (idx < 8) raw.options[('A'.code + idx).toChar().toString()] = p
            }
        }
    }
}

/** org.json 在 Android 上有，在 JVM 单元测试里没有，这里做一层薄封装 */
object JSONTokenerCompat {
    fun parse(text: String): Any {
        return org.json.JSONTokener(text).nextValue()
    }
}
