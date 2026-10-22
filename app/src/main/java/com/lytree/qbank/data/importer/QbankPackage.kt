package com.lytree.qbank.data.importer

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 题库包（zip）。
 *
 * 标准目录结构：
 * ```
 * 资料分析题库包.zip
 * └── 资料分析题库包/
 *     ├── qbank.json          # 必需：题库主数据
 *     ├── images/             # 可选：材料配图
 *     │   └── chart-01.png
 *     └── README.txt          # 可选：说明（不会导入）
 * ```
 *
 * 规则：
 * 1. **主数据文件**按名字优先取 `qbank.json` → `questions.json` → `bank.json`，
 *    都没有时取压缩包根目录（或一层子目录）下唯一的数据文件（json / csv / xlsx）；
 * 2. 允许整个包多包一层目录（压缩软件默认行为），这层目录会被剥掉；
 * 3. 图片在题库里写**相对路径**（`images/chart-01.png`），导入时解压到应用私有目录
 *    并替换成绝对路径，之后换手机、清缓存都不影响已有题目；
 * 4. 解压只取普通文件，`..`、绝对路径、`__MACOSX/` 这类条目一律跳过。
 */
object QbankPackage {

    /** 主数据文件名，按优先级排列 */
    private val MAIN_NAMES = listOf("qbank.json", "questions.json", "bank.json", "题库.json")

    /** 不是数据的文件：不参与「唯一数据文件」推断 */
    private val META_NAMES = setOf(
        "manifest.json", "readme.txt", "readme.md", "说明.txt", "说明.md"
    )

    /** 兜底推断主数据时只认这几种扩展名（txt 容易和说明文件混淆） */
    private val DATA_EXTS = setOf("json", "csv", "xlsx")

    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /**
     * 解包结果。
     *
     * @param name 题库名（取自主数据的 `bank` / `name`，没有则为空）
     * @param assets 包内相对路径 → 解压后的本机绝对路径
     */
    data class Pack(
        val name: String,
        val description: String,
        val mainPath: String,
        val mainBytes: ByteArray,
        val assets: Map<String, String>
    )

    /** 判断一个 zip 是不是题库包（xlsx 也是 zip，靠主文件区分） */
    fun looksLikePackage(bytes: ByteArray): Boolean {
        val names = readNames(bytes) ?: return false
        return pickMain(names) != null
    }

    /**
     * 解包并落盘。
     *
     * @param targetDir 解压根目录（调用方传 `filesDir/qbank_pkg` 之类）
     */
    fun open(bytes: ByteArray, targetDir: File): Pack {
        val entries = readEntries(bytes)
        if (entries.isEmpty()) throw IllegalArgumentException("压缩包里没有文件")

        val names = entries.map { it.first }
        val mainPath = pickMain(names)
            ?: throw IllegalArgumentException(
                "压缩包里没有找到题库文件，请把题库命名为 qbank.json 放在包根目录"
            )
        val root = mainPath.substringBeforeLast('/', "")
        val mainBytes = entries.first { it.first == mainPath }.second

        val dir = File(targetDir, dirNameFor(root, bytes))
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalArgumentException("无法创建解压目录：${dir.absolutePath}")
        }
        val dirPath = dir.canonicalPath + File.separator

        val assets = LinkedHashMap<String, String>()
        entries.forEach { (path, data) ->
            val rel = underRoot(path, root) ?: return@forEach
            if (rel.isBlank()) return@forEach
            val out = File(dir, rel)
            // zip slip 防护：落盘路径必须还在解压目录里
            if (!out.canonicalPath.startsWith(dirPath)) return@forEach
            out.parentFile?.mkdirs()
            out.writeBytes(data)
            if (rel != underRoot(mainPath, root)) assets[rel] = out.absolutePath
        }

        val meta = readMeta(mainBytes)
        return Pack(
            name = meta.first,
            description = meta.second,
            mainPath = mainPath,
            mainBytes = mainBytes,
            assets = assets
        )
    }

    // ---------------- 内部 ----------------

    /** 主数据文件：先按名字找，再按「唯一数据文件」兜底 */
    private fun pickMain(names: List<String>): String? {
        names.firstOrNull { it.substringAfterLast('/') in MAIN_NAMES }?.let { return it }

        val candidates = names.filter { path ->
            val file = path.substringAfterLast('/')
            val ext = file.substringAfterLast('.', "").lowercase()
            file !in META_NAMES && ext in DATA_EXTS && depth(path) <= 1
        }
        return candidates.singleOrNull()
    }

    private fun depth(path: String): Int = path.count { it == '/' }

    private fun underRoot(path: String, root: String): String? = when {
        root.isEmpty() -> path
        path == root -> ""
        path.startsWith("$root/") -> path.removePrefix("$root/")
        else -> null
    }

    /**
     * 目录名 = 包名 + 内容 CRC32。
     * 同一个包重复导入落同一目录（覆盖），内容变了则换新目录，不会新旧图片混用。
     */
    private fun dirNameFor(root: String, bytes: ByteArray): String {
        val label = root.substringAfterLast('/').ifBlank { "pack" }
        val safe = label.replace(Regex("[^\\w\\u4e00-\\u9fa5.-]"), "_").take(40)
        val crc = java.util.zip.CRC32().apply { update(bytes) }.value
        return "%s-%08x".format(safe, crc)
    }

    /** 读 `bank` / `name` / `description`，解析不了就当没有 */
    private fun readMeta(mainBytes: ByteArray): Pair<String, String> {
        val text = runCatching { String(mainBytes, Charsets.UTF_8).trimStart() }.getOrNull()
        if (text == null || !text.startsWith("{")) return "" to ""
        return runCatching {
            val o = JSONObject(text)
            val name = listOf("bank", "name", "title", "名称", "题库")
                .firstNotNullOfOrNull { k -> o.optString(k).trim().takeIf { it.isNotEmpty() } }
                .orEmpty()
            val desc = listOf("description", "desc", "说明")
                .firstNotNullOfOrNull { k -> o.optString(k).trim().takeIf { it.isNotEmpty() } }
                .orEmpty()
            name to desc
        }.getOrDefault("" to "")
    }

    /** 只读条目名，用于格式探测 */
    private fun readNames(bytes: ByteArray): List<String>? = runCatching {
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            var guard = 0
            while (e != null && guard++ < 512) {
                normalize(e.name)?.let { names.add(it) }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        names
    }.getOrNull()

    private fun readEntries(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = normalize(entry.name)
                if (name != null && total < MAX_TOTAL_BYTES) {
                    val data = readLimited(zis, MAX_ENTRY_BYTES)
                    if (data != null) {
                        total += data.size
                        out.add(name to data)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return out
    }

    private fun readLimited(zis: ZipInputStream, limit: Long): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var read: Int
        while (true) {
            read = zis.read(chunk)
            if (read <= 0) break
            buf.write(chunk, 0, read)
            if (buf.size() > limit) return null // 单文件太大，跳过
        }
        return buf.toByteArray()
    }

    /** 过滤目录条目、隐藏文件与不安全的路径 */
    private fun normalize(raw: String): String? {
        var name = raw.replace('\\', '/').trim()
        if (name.isEmpty() || name.endsWith("/")) return null
        while (name.startsWith("./")) name = name.removePrefix("./")
        if (name.startsWith("/")) return null
        if (name.split('/').any { it == ".." }) return null
        if (name.startsWith("__MACOSX/") || name.substringAfterLast('/') == ".DS_Store") return null
        return name
    }
}
