package com.lytree.qbank.data.backup

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 网盘客户端共用的 HTTP 小工具。
 *
 * 为什么是 OkHttp 而不是 [java.net.HttpURLConnection]：WebDAV 要发 `PROPFIND`、`MKCOL`，
 * 而 Android 的 HttpURLConnection 对方法名有硬编码白名单（GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE），
 * 传 PROPFIND 会抛 `ProtocolException: Expected one of … but was PROPFIND`。
 * OkHttp 的 `Request.Builder.method()` 对任意方法放行，这是引它的唯一原因。
 *
 * 其余照旧：不建自己的缓存、不重试（备份包几 MB，失败了让用户重来更清楚）。
 */
internal object CloudHttp {

    const val UA_BROWSER =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    private const val BUFFER = 64 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val ZIP: MediaType? = "application/zip".toMediaTypeOrNull()

    class Response(
        val code: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap()
    ) {
        fun json(): org.json.JSONObject = org.json.JSONObject(body)

        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response =
        call(url, "GET", headers, null)

    fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap()
    ): Response {
        val body = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return call(
            url,
            "POST",
            headers + mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
            body.toRequestBody()
        )
    }

    fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): Response = call(
        url,
        "POST",
        headers + mapOf("Content-Type" to "application/json; charset=UTF-8"),
        body.toRequestBody()
    )

    /** 任意方法（PROPFIND / MKCOL 走这里） */
    fun request(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null
    ): Response = call(
        url,
        method,
        headers,
        body?.toRequestBody(if (body.isEmpty()) null else "application/xml; charset=UTF-8".toMediaTypeOrNull())
    )

    /** multipart 上传单个文件，边写边回调进度 */
    fun postMultipart(
        url: String,
        fields: Map<String, String>,
        fileField: String,
        file: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (Float) -> Unit = {}
    ): Response {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (k, v) -> builder.addFormDataPart(k, v) }
        builder.addFormDataPart(fileField, file.name, ProgressBody(file, ZIP, onProgress))
        return call(url, "POST", headers, builder.build())
    }

    fun putBytes(
        url: String,
        bytes: ByteArray,
        headers: Map<String, String> = emptyMap()
    ): Response = call(url, "PUT", headers, bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))

    /** 整个文件 PUT 上去（WebDAV 上传就是这一条） */
    fun putFile(
        url: String,
        file: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (Float) -> Unit = {}
    ): Response = call(url, "PUT", headers, ProgressBody(file, ZIP, onProgress))

    /** 下载到文件，按已收字节回调进度 */
    fun download(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (Float) -> Unit = {}
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA_BROWSER)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw CloudException("下载失败：HTTP ${response.code} ${response.message}")
                }
                val body = response.body ?: throw CloudException("下载失败：响应是空的")
                val total = body.contentLength().coerceAtLeast(1L)
                var got = 0L
                dest.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            got += n
                            onProgress((got.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } catch (e: CloudException) {
            throw e
        } catch (e: IOException) {
            throw CloudException("下载失败：${e.message ?: "网络异常"}")
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun call(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: RequestBody?
    ): Response {
        try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", UA_BROWSER)
                .header("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.method(method, body)

            client.newCall(builder.build()).execute().use { response ->
                val text = try {
                    response.body?.string().orEmpty()
                } catch (_: IOException) {
                    ""
                }
                val map = HashMap<String, String>()
                response.headers.forEach { pair -> map[pair.first] = pair.second }
                return Response(response.code, text, map)
            }
        } catch (e: IOException) {
            throw CloudException("网络异常：${e.message ?: "连不上"}")
        } catch (e: IllegalArgumentException) {
            throw CloudException("地址不对：$url")
        }
    }

    fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")
}

/** 带进度的文件体：OkHttp 写 socket 时按块上报（0..1） */
private class ProgressBody(
    private val file: File,
    private val type: MediaType?,
    private val onProgress: (Float) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = type

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length().coerceAtLeast(1L)
        var sent = 0L
        file.source().use { source ->
            val buffer = okio.Buffer()
            while (true) {
                val read = source.read(buffer, 64L * 1024)
                if (read == -1L) break
                sink.write(buffer, read)
                sent += read
                onProgress((sent.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }
}

/** 云端操作失败。message 直接给人看，不夹英文堆栈 */
class CloudException(message: String) : Exception(message)
