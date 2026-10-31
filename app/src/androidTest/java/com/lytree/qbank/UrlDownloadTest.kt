package com.lytree.qbank

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lytree.qbank.data.importer.UrlDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * 链接导入的下载层：本机起一个极简 HTTP 服务，验证文件名识别、跳转与协议校验。
 *
 * 不碰界面，下载成功后仍由 Importer 解析，因此这里只断言「字节 + 文件名」。
 */
@RunWith(AndroidJUnit4::class)
class UrlDownloadTest {

    private val json = """{"bank":"测试","questions":[]}"""

    /** 起一个只处理单个请求的本地服务，routes 返回完整 HTTP 响应文本 */
    private fun withServer(routes: Map<String, String>, block: (base: String) -> Unit) {
        val server = ServerSocket(0)
        val pool = Executors.newSingleThreadExecutor()
        pool.execute {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val request = reader.readLine() ?: return@use
                        val path = request.split(' ').getOrNull(1).orEmpty()
                        while (true) {
                            val line = reader.readLine()
                            if (line.isNullOrEmpty()) break
                        }
                        val body = routes[path] ?: "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(body.toByteArray(Charsets.UTF_8))
                            flush()
                        }
                    }
                } catch (_: Exception) {
                    // 服务关闭时 accept 会抛异常，直接退出
                }
            }
        }
        try {
            block("http://127.0.0.1:${server.localPort}")
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }

    private fun ok(body: String, extraHeaders: String = ""): String =
        "HTTP/1.1 200 OK\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            extraHeaders +
            "\r\n" + body

    @Test
    fun 文件名优先取_content_disposition() {
        withServer(
            mapOf(
                "/download" to ok(
                    json,
                    "Content-Disposition: attachment; filename*=UTF-8''%E9%A2%98%E5%BA%93.json\r\n"
                )
            )
        ) { base ->
            val result = UrlDownloader.download("$base/download?id=1")
            assertEquals("试卷.json", result.fileName)
            assertEquals(json, String(result.bytes, Charsets.UTF_8))
        }
    }

    @Test
    fun 没有_disposition_时用链接最后一段() {
        withServer(mapOf("/files/行测.csv" to ok("题干,答案\nA,B", "Content-Type: text/csv\r\n"))) { base ->
            val result = UrlDownloader.download("$base/files/%E8%A1%8C%E6%B5%8B.csv")
            assertEquals("行测.csv", result.fileName)
        }
    }

    @Test
    fun 跟随_server_端跳转() {
        val routes = mapOf(
            "/old" to "HTTP/1.1 302 Found\r\nLocation: /new\r\nContent-Length: 0\r\n\r\n",
            "/new" to ok(json)
        )
        withServer(routes) { base ->
            val result = UrlDownloader.download("$base/old")
            assertEquals(json, String(result.bytes, Charsets.UTF_8))
        }
    }

    @Test
    fun 非_http_协议直接拒绝() {
        assertNull(UrlDownloader.normalize("file:///sdcard/qbank.json"))
        assertNull(UrlDownloader.normalize("ftp://example.com/a.json"))
        assertNotNull(UrlDownloader.normalize("https://example.com/a.json"))
    }

    @Test
    fun http_错误码报可读错误() {
        withServer(emptyMap()) { base ->
            val error = try {
                UrlDownloader.download("$base/missing")
                null
            } catch (e: Exception) {
                e
            }
            assertTrue("应抛下载异常，实际=$error", error is UrlDownloader.DownloadException)
            assertTrue(error!!.message.orEmpty().contains("404"))
        }
    }
}
