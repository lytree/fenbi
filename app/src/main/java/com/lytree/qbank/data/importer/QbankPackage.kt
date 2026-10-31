package com.lytree.qbank.data.importer

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 题库包（zip）：**题库 Excel + 媒体文件**。
 *
 * 标准目录结构：
 * ```
 * 资料分析题库包.zip
 * └── 资料分析题库包/
 *     ├── 题库.xlsx            # 题库主数据（统一 Excel 模板）
 *     ├── images/              # 图片等媒体资源
 *     │   └── chart-01.png
 *     └── README.txt           # 可选：说明（不会导入）
 * ```
 *
 * 规则：
 * 1. **主数据不再靠文件名钦定**：包根目录（或一层子目录）下所有 xlsx 都作为候选收下来，
 *    交给 [Importer] 逐个试解析，最后按「真正能导入多少题」择优。
 * 2. 允许整个包多包一层目录（压缩软件默认行为），这层目录会被剥掉；包名（包内只有一卷时作默认试卷名）取这层目录名；
 * 3. 图片在题库里写**相对路径**（`images/chart-01.png`）或**纯文件名**（`src="x.png"`），
 *    导入时解压到应用私有目录并替换成绝对路径，之后换手机、清缓存都不影响已有题目；
 * 4. 解压只取普通文件，`..`、绝对路径、`__MACOSX/` 这类条目一律跳过；
 * 5. 素材**边读边写**，不在内存里攒，避免几百张图的包把低端机顶到 OOM。
 */
object QbankPackage {

    /** 主数据文件名，按优先级排列（同包多份 xlsx 时先试标准命名的） */
    private val MAIN_NAMES = listOf("题库.xlsx", "qbank.xlsx", "questions.xlsx", "bank.xlsx")

    /** 不是数据的文件：不参与候选推断 */
    private val META_NAMES = setOf(
        "manifest.json", "readme.txt", "readme.md", "说明.txt", "说明.md"
    )

    /** 候选主数据只认 xlsx：统一 Excel 格式，其他文件都是媒体资源 */
    private val DATA_EXTS = setOf("xlsx")

    /** 候选优先级：同名优先级之后再按这个排 */
    private val EXT_ORDER = listOf("xlsx")

    /** 最多留几个候选：正常包不会超过两三份，多的是垃圾文件 */
    private const val MAX_CANDIDATES = 6

    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

    /** 候选主数据（要整份读进内存试解析）单份上限 */
    private const val MAX_CANDIDATE_BYTES = 16L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /** 条目数上限：防止畸形压缩包把扫描拖住（正常题库包最多几千个条目） */
    private const val MAX_ENTRIES = 20_000

    /**
     * 包内的一个数据文件。
     *
     * @param path 包内相对路径（已剥掉外层目录）
     */
    data class Candidate(val path: String, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Candidate && other.path == path && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * path.hashCode() + bytes.contentHashCode()
    }

    /**
     * 解包结果。
     *
     * @param name 包名（默认试卷名；取自包根目录名，没有则为空）
     * @param candidates 候选数据文件，已按优先级排好序；**第一个是名义主数据**，不一定是最优解
     * @param assets 包内相对路径 → 解压后的本机绝对路径
     */
    data class Pack(
        val name: String,
        val description: String,
        val candidates: List<Candidate>,
        val assets: Map<String, String>
    )

    /** 判断一个 zip 是不是题库包（xlsx 也是 zip，靠「有没有数据文件」区分） */
    fun looksLikePackage(bytes: ByteArray): Boolean {
        val names = readNames(bytes, full = false) ?: return false
        return names.any { isCandidate(it) }
    }

    /**
     * 解包并落盘，同时把所有候选主数据读进内存（每份限 [MAX_CANDIDATE_BYTES]）。
     *
     * @param targetDir 解压根目录（调用方传 `filesDir/qbank_pkg` 之类）
     */
    fun open(bytes: ByteArray, targetDir: File): Pack {
        val names = readNames(bytes, full = true) ?: throw IllegalArgumentException("压缩包读取失败，文件可能已损坏")
        if (names.isEmpty()) throw IllegalArgumentException("压缩包里没有文件")

        val candidatePaths = candidateNames(names)
        if (candidatePaths.isEmpty()) {
            throw IllegalArgumentException(
                "压缩包里没有找到题库 Excel（.xlsx），请把「题库.xlsx」放在包根目录（或只包一层文件夹）"
            )
        }
        val candidateSet = candidatePaths.toHashSet()
        val root = candidatePaths.first().substringBeforeLast('/', "")

        val dir = File(targetDir, dirNameFor(root, bytes))
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalArgumentException("无法创建解压目录：${dir.absolutePath}")
        }
        val dirPath = dir.canonicalPath + File.separator

        val assets = LinkedHashMap<String, String>()
        val found = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = normalize(entry.name)
                val rel = if (name == null) null else underRoot(name, root)
                if (name != null && rel != null && rel.isNotBlank() && total < MAX_TOTAL_BYTES) {
                    if (name in candidateSet) {
                        readLimited(zis, MAX_CANDIDATE_BYTES)?.let {
                            total += it.size
                            found[name] = it
                        }
                    } else {
                        val out = File(dir, rel)
                        // zip slip 防护：落盘路径必须还在解压目录里
                        if (out.canonicalPath.startsWith(dirPath)) {
                            out.parentFile?.mkdirs()
                            streamTo(zis, out, MAX_ENTRY_BYTES)?.let {
                                total += it
                                assets[rel] = out.absolutePath
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 按候选优先级重排（zip 里条目顺序取决于打包工具，不可信）
        val candidates = candidatePaths.mapNotNull { p ->
            found[p]?.let { Candidate(p, it) }
        }
        if (candidates.isEmpty()) {
            throw IllegalArgumentException("主数据文件为空或超过 ${MAX_CANDIDATE_BYTES / 1024 / 1024} MB")
        }

        // 包名取包根目录名（压缩软件多包的那层），没有外层目录就留空交给文件名兜底
        val name = root.substringAfterLast('/').trim()
        return Pack(
            name = name,
            description = "",
            candidates = candidates,
            assets = assets
        )
    }

    // ---------------- 内部 ----------------

    /** 数据文件（排除说明类与过深的路径） */
    private fun isCandidate(path: String): Boolean {
        val file = path.substringAfterLast('/')
        if (file in META_NAMES) return false
        if (depth(path) > 1) return false
        return file.substringAfterLast('.', "").lowercase() in DATA_EXTS
    }

    /**
     * 候选排序：标准命名 → 扩展名偏好 → 路径字典序（保证同样输入得到同样结果）。
     */
    private fun candidateNames(names: List<String>): List<String> {
        val all = names.filter { isCandidate(it) }.distinct()
        return all.sortedWith(
            compareBy(
                { path ->
                    val i = MAIN_NAMES.indexOf(path.substringAfterLast('/'))
                    if (i >= 0) i else MAIN_NAMES.size
                },
                { path -> depth(path) },
                { path ->
                    val i = EXT_ORDER.indexOf(path.substringAfterLast('.', "").lowercase())
                    if (i >= 0) i else EXT_ORDER.size
                },
                { it }
            )
        ).take(MAX_CANDIDATES)
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

    /**
     * 只读条目名。
     *
     * @param full false 时见到第一个数据文件就停（格式探测用，xlsx 这类包没必要扫完）；
     *             true 时必须扫全——主数据可能排在几百张图后面，半途停会漏掉真正的题目文件。
     */
    private fun readNames(bytes: ByteArray, full: Boolean): List<String>? = runCatching {
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            var guard = 0
            while (e != null && guard++ < MAX_ENTRIES) {
                val name = normalize(e.name)
                if (name != null) {
                    names.add(name)
                    if (!full && isCandidate(name)) break
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        names
    }.getOrNull()

    /** 读单个条目到内存，超过上限返回 null（候选主数据用） */
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

    /** 边读边写到磁盘；超过单文件上限就删掉半成品并返回 null */
    private fun streamTo(zis: ZipInputStream, out: File, limit: Long): Long? {
        var written = 0L
        var tooBig = false
        out.outputStream().use { os ->
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = zis.read(chunk)
                if (read <= 0) break
                written += read
                if (written > limit) {
                    tooBig = true
                    break
                }
                os.write(chunk, 0, read)
            }
        }
        if (tooBig) {
            out.delete()
            return null
        }
        return written
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
