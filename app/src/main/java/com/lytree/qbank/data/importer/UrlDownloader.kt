package com.lytree.qbank.data.importer

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.zip.GZIPInputStream

/**
 * 从链接下载试卷文件。
 *
 * 只做「拿到字节 + 猜一个文件名」这一件事，解析仍走 [Importer]，
 * 因此链接导入和本地文件导入共用同一套格式探测、校验与预览流程。
 */
object UrlDownloader {

    /** 单个文件上限，超过就提示改用文件导入，避免误填大文件链接拖垮手机 */
    const val MAX_BYTES = 64L * 1024 * 1024

    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    data class Result(val fileName: String, val bytes: ByteArray)

    class DownloadException(message: String) : Exception(message)

    /** 把用户输入的文本规整成 http/https 链接，其他协议（file:// 等）一律拒绝 */
    fun normalize(raw: String): URL? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val url = try {
            URL(text)
        } catch (_: Exception) {
            return null
        }
        return url.takeIf { it.protocol.equals("http", true) || it.protocol.equals("https", true) }
    }

    /**
     * 同步下载，调用方自行放到 IO 线程。
     *
     * 手动处理 3xx：既兼容 http → https 的跨协议跳转，也能在跳转次数上兜底。
     */
    fun download(raw: String): Result {
        var current = normalize(raw)
            ?: throw DownloadException("链接格式不对，请填写 http/https 开头的地址")
        var redirects = 0

        while (true) {
            val conn = open(current)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw DownloadException("下载失败：服务器跳转缺少地址（HTTP $code）")
                    if (++redirects > MAX_REDIRECTS) {
                        throw DownloadException("下载失败：跳转次数过多")
                    }
                    current = try {
                        URL(current, location)
                    } catch (_: Exception) {
                        throw DownloadException("下载失败：跳转地址无效")
                    }
                    continue
                }
                if (code !in 200..299) throw DownloadException("下载失败：HTTP $code")

                val length = conn.contentLengthLong
                if (length > MAX_BYTES) throw tooLarge()

                val bytes = conn.inputStream.use { input ->
                    val stream = if (conn.contentEncoding?.contains("gzip", true) == true) {
                        GZIPInputStream(input)
                    } else {
                        input
                    }
                    stream.readLimited()
                }
                if (bytes.isEmpty()) throw DownloadException("下载失败：服务器返回了空文件")

                return Result(fileNameOf(conn, current), bytes)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun open(url: URL): HttpURLConnection {
        val conn = url.openConnection() as? HttpURLConnection
            ?: throw DownloadException("下载失败：不支持的链接")
        conn.instanceFollowRedirects = false
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) QBank")
        conn.setRequestProperty("Accept", "*/*")
        return conn
    }

    private fun InputStream.readLimited(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw tooLarge()
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun tooLarge() =
        DownloadException("文件太大（超过 ${MAX_BYTES / 1024 / 1024}MB），请改用文件导入")

    /**
     * 文件名优先取 `Content-Disposition`，其次取链接最后一段。
     *
     * 拿不到像样的名字时返回「导入文件」，格式仍能靠内容探测。
     */
    private fun fileNameOf(conn: HttpURLConnection, url: URL): String {
        parseDisposition(conn.getHeaderField("Content-Disposition"))?.let { return it }

        val segment = url.path.orEmpty().substringAfterLast('/')
        val decoded = try {
            URLDecoder.decode(segment, "UTF-8")
        } catch (_: Exception) {
            segment
        }
        return sanitize(decoded.ifBlank { "导入文件" })
    }

    private fun parseDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null

        // RFC 5987：filename*=UTF-8''%E9%A2%98%E5%BA%93.json
        STAR.find(header)?.let { m ->
            val charset = m.groupValues[1].ifBlank { "UTF-8" }
            val raw = m.groupValues[2].trim().trim('"')
            val decoded = try {
                URLDecoder.decode(raw, charset)
            } catch (_: Exception) {
                raw
            }
            return sanitize(decoded)
        }

        PLAIN.find(header)?.let { m ->
            return sanitize(m.groupValues[1])
        }
        return null
    }

    /** 只留展示用的名字：去掉路径分隔符与非法字符，避免拼进提示文案时出现怪符号 */
    private fun sanitize(name: String): String {
        val cleaned = name
            .substringAfterLast('\\')
            .substringAfterLast('/')
            .replace(Regex("[\\u0000-\\u001F:*?\"<>|]"), "_")
            .trim()
        return cleaned.take(80).ifBlank { "导入文件" }
    }

    private val STAR = Regex("filename\\*\\s*=\\s*([^']*)''([^;]+)", RegexOption.IGNORE_CASE)
    private val PLAIN = Regex("filename\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
}
