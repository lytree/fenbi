package com.lytree.qbank.data.importer

import android.content.Context

/**
 * 本地汉字拼音表（`assets/pinyin.txt`，约 2.7 万字，单字 → 常用读音）。
 *
 * 为什么内置而不是联网：词库导入是离线场景，词条几千条，逐字请求接口既慢又不可靠；
 * 这张表由 `tools/gen_pinyin.py` 从 [pinyin-data](https://github.com/mozillazg/pinyin-data) 生成，
 * 只取每个字的**第一个读音**（多音字按最常见读音），去声调、`ü` 记作 `v`。
 *
 * 导入时只需要两件事：整词拼音、首字母。表加载一次常驻内存（首次约 30ms），
 * 相同的拼音串做了池化，避免 2 万多个重复 String 对象。
 */
object PinyinTable {

    private const val ASSET = "pinyin.txt"

    private val table = HashMap<Char, String>(32768)
    private val pool = HashMap<String, String>(8192)

    @Volatile
    private var loaded = false

    /** 加载内置拼音表；重复调用无副作用 */
    fun ensure(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                context.applicationContext.assets.open(ASSET)
                    .bufferedReader(Charsets.UTF_8)
                    .useLines { lines ->
                        lines.forEach { line ->
                            if (line.isEmpty() || line[0] == '#') return@forEach
                            val sp = line.indexOf(' ')
                            // 格式是「字 拼音」：汉字只占 1 个 UTF-16 单元，空格必然在 index 1，
                            // 所以这里只能判「没有空格」，判 sp <= 1 会把整张表全跳过
                            if (sp < 1) return@forEach
                            val py = line.substring(sp + 1).trim()
                            if (py.isEmpty()) return@forEach
                            table[line[0]] = pool.getOrPut(py) { py }
                        }
                    }
            } catch (_: Exception) {
                // 资产缺失时降级：拼音留空、首字母统一 `#`，导入照常进行
            }
            loaded = true
        }
    }

    fun isLoaded(): Boolean = loaded

    /** 单个汉字的拼音；非汉字返回空串 */
    fun pinyinOf(c: Char): String = table[c] ?: ""

    /** 是否收录了这个字（用来判断「是不是汉字」） */
    fun isHan(c: Char): Boolean = table.containsKey(c)

    /**
     * 整词拼音：汉字逐字取音，英文 / 数字原样保留，其余符号（括号、间隔号）丢掉。
     * 多字之间用空格分隔，作为展示与二次排序的依据。
     */
    fun fullPinyin(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            if (c.isWhitespace()) continue
            val py = table[c]
            if (!py.isNullOrEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(py)
                continue
            }
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(c.lowercaseChar())
                }
            }
        }
        return sb.toString()
    }

    /** 首字母大写 A-Z；取不到返回 `#` */
    fun initialOf(pinyin: String): String {
        val c = pinyin.firstOrNull { it in 'a'..'z' || it in 'A'..'Z' } ?: return "#"
        return c.uppercaseChar().toString()
    }

    /** 排序用的紧凑串（去掉空格） */
    fun sortKey(pinyin: String): String = pinyin.replace(" ", "").lowercase()

    /** 词条里有没有汉字（纯英文 / 纯数字 / 纯符号的行不当词条） */
    fun hasHan(text: String): Boolean = text.any { isHan(it) }

    private val TONES = mapOf(
        'ā' to 'a', 'á' to 'a', 'ǎ' to 'a', 'à' to 'a',
        'ē' to 'e', 'é' to 'e', 'ě' to 'e', 'è' to 'e', 'ê' to 'e',
        'ī' to 'i', 'í' to 'i', 'ǐ' to 'i', 'ì' to 'i',
        'ō' to 'o', 'ó' to 'o', 'ǒ' to 'o', 'ò' to 'o',
        'ū' to 'u', 'ú' to 'u', 'ǔ' to 'u', 'ù' to 'u',
        'ǖ' to 'v', 'ǘ' to 'v', 'ǚ' to 'v', 'ǜ' to 'v', 'ü' to 'v',
        'ń' to 'n', 'ň' to 'n', 'ǹ' to 'n', 'ḿ' to 'm'
    )

    /**
     * 归一化来源表里自带的拼音：去声调、`ü`→`v`、只留字母和空格、压掉多余空格。
     *
     * 表格里常见写法有 `huà shé tiān zú` / `huàshétiānzú` / `hua she tian zu` 三种，
     * 归一后前两种都能用于首字母与排序，展示时也不带声调（列表字小，带声调反而糊）。
     */
    fun normalizePinyin(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw.trim()) {
            val lower = ch.lowercaseChar()
            when {
                TONES.containsKey(lower) -> sb.append(TONES.getValue(lower))
                lower in 'a'..'z' -> sb.append(lower)
                lower.isWhitespace() || lower == ',' || lower == '，' || lower == '/' || lower == '·' ->
                    if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                else -> Unit
            }
        }
        return sb.toString().trim().replace(Regex(" +"), " ")
    }
}
