package com.lytree.qbank.data.backup

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * WebDAV 客户端。一家实现对多家网盘：坚果云、Nextcloud、群晖 DSM、InfiniCLOUD 都走这套协议。
 *
 * 用到的动词只有五个：`PROPFIND` 列目录、`MKCOL` 建目录、`PUT` 上传、`GET` 下载、`DELETE` 删除，
 * 全部带 Basic 认证。坚果云的「应用密码」就是在网页端生成、专门给第三方用的那串，
 * 不是登录密码，吊销即可失效，所以存本地明文的风险可控。
 */
class WebDavProvider(private val context: Context) : CloudProvider {

    override val id: String = "webdav"

    override val name: String = "WebDAV 网盘"

    override val hint: String = "坚果云 dav.jianguoyun.com/dav/ · Nextcloud · 群晖 · InfiniCLOUD"

    // ---------------------------------------------------------------- 配置与地址

    private fun baseUrl(): String {
        var url = CloudStore.webdavUrl(context).trim().trimEnd('/')
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "https://$url"
        }
        return url
    }

    private fun dirSegments(): String =
        CloudStore.webdavDir(context)
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { CloudHttp.enc(it) }

    private fun folderUrl(): String {
        val dir = dirSegments()
        return if (dir.isEmpty()) baseUrl() else "${baseUrl()}/$dir"
    }

    private fun fileUrl(name: String): String = "${folderUrl()}/${CloudHttp.enc(name)}"

    private fun auth(): Map<String, String> {
        val raw = "${CloudStore.webdavUser(context)}:${CloudStore.webdavPass(context)}"
        val token = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return mapOf(
            "Authorization" to "Basic $token",
            "User-Agent" to CloudHttp.UA_BROWSER
        )
    }

    override fun isConfigured(): Boolean =
        baseUrl().isNotBlank() && CloudStore.webdavUser(context).isNotBlank()

    // ---------------------------------------------------------------- 连接自检

    override suspend fun test(): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) throw CloudException("先把 WebDAV 地址和账号填上")
        ensureFolder()
        val res = CloudHttp.request(
            folderUrl() + "/",
            "PROPFIND",
            auth() + mapOf("Depth" to "0"),
            PROPFIND_BODY.toByteArray(Charsets.UTF_8)
        )
        when (res.code) {
            207, 200 -> "已连接：${CloudStore.webdavDir(context)} 目录可用"
            401 -> throw CloudException("账号或应用密码不对（401）")
            403 -> throw CloudException("没有权限访问这个目录（403）")
            404 -> throw CloudException("地址不对，找不到 WebDAV 根路径（404）")
            else -> throw CloudException("连接失败：HTTP ${res.code}")
        }
    }

    // ---------------------------------------------------------------- 列表

    override suspend fun list(): List<CloudFile> = withContext(Dispatchers.IO) {
        if (!isConfigured()) throw CloudException("先把 WebDAV 地址和账号填上")
        val res = CloudHttp.request(
            folderUrl() + "/",
            "PROPFIND",
            auth() + mapOf("Depth" to "1"),
            PROPFIND_BODY.toByteArray(Charsets.UTF_8)
        )
        when (res.code) {
            207, 200 -> parseMultiStatus(res.body)
            404 -> emptyList()          // 目录还没建，等于没有备份
            401 -> throw CloudException("账号或应用密码不对（401）")
            else -> throw CloudException("读取云端列表失败：HTTP ${res.code}")
        }
    }

    // ---------------------------------------------------------------- 上传 / 下载 / 删除

    override suspend fun upload(file: File, onProgress: (Float) -> Unit): CloudFile =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) throw CloudException("先把 WebDAV 地址和账号填上")
            ensureFolder()

            val res = CloudHttp.putFile(
                fileUrl(file.name),
                file,
                auth() + mapOf("Content-Type" to "application/zip"),
                onProgress
            )
            when (res.code) {
                200, 201, 204 -> CloudFile(file.name, file.length(), System.currentTimeMillis())
                401 -> throw CloudException("账号或应用密码不对（401）")
                507, 413 -> throw CloudException("云端空间不足，先删几份旧备份（HTTP ${res.code}）")
                else -> throw CloudException("上传失败：HTTP ${res.code} ${res.body.take(120)}")
            }
        }

    override suspend fun download(
        file: CloudFile,
        dest: File,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val url = if (file.path.startsWith("http", true)) file.path else fileUrl(file.name)
        CloudHttp.download(url, dest, auth(), onProgress)
        if (!dest.isFile || dest.length() == 0L) throw CloudException("下载下来的文件是空的")
    }

    override suspend fun delete(file: CloudFile) = withContext(Dispatchers.IO) {
        val url = if (file.path.startsWith("http", true)) file.path else fileUrl(file.name)
        val res = CloudHttp.request(url, "DELETE", auth())
        when (res.code) {
            200, 204, 404 -> Unit
            401 -> throw CloudException("账号或应用密码不对（401）")
            else -> throw CloudException("删除失败：HTTP ${res.code}")
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 备份目录不存在就建；已存在时服务端会回 405（坚果云）或 301，都算成 */
    private fun ensureFolder() {
        val res = CloudHttp.request(folderUrl(), "MKCOL", auth())
        when (res.code) {
            in 200..299, 301, 405 -> Unit
            401 -> throw CloudException("账号或应用密码不对（401）")
            409 -> throw CloudException("上一级目录不存在（409），检查路径里的父目录")
            403 -> throw CloudException("没有权限在根目录建文件夹（403）")
            else -> throw CloudException("创建备份目录失败：HTTP ${res.code}")
        }
    }

    private fun parseMultiStatus(xml: String): List<CloudFile> {
        val out = ArrayList<CloudFile>()
        runCatching {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            var href: String? = null
            var size = -1L
            var modified = 0L
            var collection = false

            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (localName(parser)) {
                        "href" -> href = parser.nextText()
                        "getcontentlength" -> size = parser.nextText().trim().toLongOrNull() ?: -1L
                        "getlastmodified" -> modified = parseHttpDate(parser.nextText())
                        "collection" -> collection = true
                    }
                } else if (event == XmlPullParser.END_TAG && localName(parser) == "response") {
                    val link = href
                    if (link != null && !collection && size >= 0) {
                        val name = decodeName(link)
                        if (name.endsWith(".zip", true)) {
                            out.add(CloudFile(name, size, modified, link.trim()))
                        }
                    }
                    href = null
                    size = -1L
                    modified = 0L
                    collection = false
                }
                event = parser.next()
            }
        }
        return out.sortedByDescending { it.modifiedAt }
    }

    private fun localName(parser: XmlPullParser): String =
        parser.name.substringAfter(':').lowercase(Locale.ROOT)

    private fun decodeName(href: String): String {
        val tail = href.trimEnd('/').substringAfterLast('/')
        return runCatching { URLDecoder.decode(tail, "UTF-8") }.getOrDefault(tail)
    }

    private fun parseHttpDate(text: String): Long = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .parse(text.trim())?.time ?: 0L
    }.getOrDefault(0L)

    private companion object {
        const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/>
</d:prop></d:propfind>"""
    }
}
