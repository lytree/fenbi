package com.lytree.qbank.ui.laneditor

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * 局域网题库编辑器 —— 手机里跑一个 HTTP 服务，同一 Wi-Fi 下的电脑用浏览器打开就能加题。
 *
 * 为什么自己写：项目全程零 Web 依赖（不引 NanoHTTPD / Ktor），这里只需要
 * 「读请求行 + 头 + body，按路径回字节」这一小撮能力，标准库 `ServerSocket` 足够。
 *
 * 安全：
 * - 只有带上 App 里那串 token（`/?t=xxx`，打开一次写 Cookie）才放行 `/api` 下的接口，
 *   同 Wi-Fi 的其他人扫到端口也改不了数据；
 * - 只暴露这几件事：读题库、发布一组卷、传图 / 列资源 / 删图、**申请删除一组**；
 *   删除请求不会直接执行，必须由手机上的 [LanEditorActivity] 点「确认删除」才动库；
 * - 服务由前台 Service 拉起，用户点停止或杀掉 App 就结束，不会常驻。
 *
 * 线程模型：一个 accept 线程 + 4 个 worker，连接级 `Connection: close`，
 * 请求之间有 `soTimeout`，浏览器刷新再频繁也不会把线程吃满。
 */
class LanEditorServer(
    private val context: Context,
    private val port: Int,
    private val store: LanEditorStore,
) {

    companion object {
        private const val TAG = "LanEditorServer"
        private const val MAX_BODY = 32 * 1024 * 1024
        private const val JSON_TYPE = "application/json; charset=utf-8"
        private const val TEXT_TYPE = "text/plain; charset=utf-8"
        private const val HTML_TYPE = "text/html; charset=utf-8"
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    /** 每次启动换一串，电脑端第一次必须用 App 里显示的完整链接打开 */
    val token: String = buildString {
        val pool = "abcdefghijkmnpqrstuvwxyz23456789"
        repeat(12) { append(pool[Random.nextInt(pool.length)]) }
    }

    /** 最近一条请求（App 界面用来显示「谁在操作」，出问题时好排查） */
    @Volatile
    var lastRequest: String = ""
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService = newWorkers()

    /** 已经接进来、还没走完的请求；停止服务时要一并掐掉，否则它们会继续往库里写 */
    private val clients: MutableSet<Socket> = Collections.synchronizedSet(HashSet())

    /** stop() 会把线程池 shutdownNow，重启时得换一个新的 */
    private fun newWorkers(): ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "lan-editor-worker").apply { isDaemon = true }
    }

    val pageUrl: String
        get() = "http://${LanNet.ipv4() ?: "127.0.0.1"}:$port/?t=$token"

    /** 启动；端口被占时返回失败原因，界面直接显示 */
    fun start(): Result<Unit> {
        if (isRunning) return Result.success(Unit)
        return try {
            val socket = ServerSocket().apply {
                // SO_REUSEADDR 必须在 bind 之前设：建完 socket 再设是无效的，
                // 停止后马上重启会撞上上一轮遗留的 TIME_WAIT，报「端口被占用」误导人去换端口
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            serverSocket = socket
            isRunning = true
            acceptThread = Thread({ acceptLoop(socket) }, "lan-editor-accept").apply {
                isDaemon = true
                start()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            isRunning = false
            serverSocket = null
            val msg = if (e is java.net.BindException) "端口 $port 被占用，换一个端口再试"
            else (e.message ?: "启动失败")
            Result.failure(IllegalStateException(msg, e))
        }
    }

    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        // 在途请求也要断：点了「停止」就该立刻停，不能还有 publish 在后台写库
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { workers.shutdownNow() }
        workers = newWorkers()
    }

    // ---------------------------------------------------------------- 连接

    private fun acceptLoop(socket: ServerSocket) {
        while (isRunning) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break                                   // stop() 关掉了 socket
            }
            workers.execute {
                runCatching { handle(client) }
            }
        }
    }

    private fun handle(client: Socket) {
        clients.add(client)
        try {
            client.use { s ->
                s.soTimeout = 20_000
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                val req = try {
                    readRequest(input)
                } catch (e: RequestTooLarge) {
                    // 超限要回一句 413 再断开，否则前端只看到「连接被重置」，查不出原因
                    writeResponse(output, jsonError(413, e.message ?: "请求体太大"))
                    return
                } ?: return
                lastRequest = "${req.method} ${req.path}"
                val resp = try {
                    dispatch(req)
                } catch (e: IllegalArgumentException) {
                    // 参数/校验类错误对客户端是「你的请求不对」，不是服务器崩了
                    jsonError(400, e.message ?: "请求参数不对")
                } catch (e: Exception) {
                    jsonError(500, e.message ?: "服务器出错")
                }
                writeResponse(output, resp)
            }
        } finally {
            clients.remove(client)
        }
    }

    private class RequestTooLarge(message: String) : Exception(message)

    private fun readRequest(input: InputStream): Req? {
        val line = readLine(input)?.trim() ?: return null
        if (line.isEmpty()) return null
        val parts = line.split(' ')
        if (parts.size < 2) return null

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0) throw RequestTooLarge("Content-Length 不合法")
        if (len > MAX_BODY) throw RequestTooLarge("请求体太大（上限 ${MAX_BODY / 1024 / 1024}MB）")
        val body = if (len > 0) readFully(input, len) else ByteArray(0)

        val target = parts[1]
        val qi = target.indexOf('?')
        val path = if (qi >= 0) target.substring(0, qi) else target
        val query = if (qi >= 0) parseQuery(target.substring(qi + 1)) else emptyMap()
        return Req(parts[0].uppercase(), path, query, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
            if (buf.size() > 16 * 1024) return null
        }
    }

    private fun readFully(input: InputStream, len: Int): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(out, off, len - off)
            if (n <= 0) break
            off += n
        }
        return out
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split('&').forEach { pair ->
            val i = pair.indexOf('=')
            val k = if (i >= 0) pair.substring(0, i) else pair
            val v = if (i >= 0) pair.substring(i + 1) else ""
            // 文件名里可能有 '+'，别按表单编码当空格
            out[decode(k)] = decode(v)
        }
        return out
    }

    private fun readJson(req: Req): JSONObject? =
        runCatching { JSONObject(String(req.body, Charsets.UTF_8)) }.getOrNull()

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    // ---------------------------------------------------------------- 路由

    private fun dispatch(req: Req): Resp {
        val path = req.path

        if (path == "/" || path == "/index.html") {
            if (!authorized(req)) return Resp(403, HTML_TYPE, forbiddenPage())
            val body = asset("index.html") ?: return Resp(500, TEXT_TYPE, "缺少界面资源".toByteArray())
            return Resp(
                200, HTML_TYPE, body,
                listOf("Set-Cookie" to "le_token=${token}; Path=/; Max-Age=604800; SameSite=Lax")
            )
        }

        if (path.startsWith("/web/")) {
            val name = path.removePrefix("/web/")
            if (name.contains("..")) return Resp(404, TEXT_TYPE, "not found".toByteArray())
            val body = asset(name) ?: return Resp(404, TEXT_TYPE, "not found".toByteArray())
            return Resp(200, mimeOf(name), body)
        }

        if (!path.startsWith("/api/")) return Resp(404, TEXT_TYPE, "not found".toByteArray())
        if (!authorized(req)) return jsonError(401, "未授权：请用 App 里显示的完整链接打开（带 ?t=… 那串）")

        return when (path) {
            "/api/meta" -> json(
                JSONObject()
                    .put("port", port)
                    .put("url", pageUrl)
                    .put("app", "fenbi")
            )

            "/api/papers" -> json(store.listSets())

            "/api/paper" -> {
                val id = req.query["id"]?.toLongOrNull() ?: return jsonError(400, "缺少试卷组 id")
                json(store.loadSet(id))
            }

            "/api/publish" -> {
                val body = String(req.body, Charsets.UTF_8)
                if (body.isBlank()) return jsonError(400, "请求体为空")
                val payload = runCatching { JSONObject(body) }.getOrElse {
                    return jsonError(400, "请求不是合法 JSON")
                }
                val title = payload.optString("title").trim()
                if (title.isEmpty()) return jsonError(400, "标题不能为空（它会成为 App 首页的那一组）")
                val replace = payload.optBoolean("replace", false)
                val sheets = payload.optJSONArray("sheets") ?: return jsonError(400, "没有收到试卷内容")
                runCatching { store.publish(title, replace, sheets) }
                    .fold(
                        onSuccess = { json(it) },
                        onFailure = { jsonError(400, it.message ?: "发布失败") }
                    )
            }

            "/api/records" -> json(store.listRecords(req.query["setId"]?.toLongOrNull()))

            "/api/record" -> {
                val id = req.query["id"]?.toLongOrNull() ?: return jsonError(400, "缺少记录 id")
                runCatching { store.loadRecord(id) }.fold(
                    onSuccess = { json(it) },
                    onFailure = { jsonError(404, it.message ?: "找不到这条记录") },
                )
            }

            "/api/versions" -> json(store.listVersions(req.query["title"]))

            "/api/version/restore" -> {
                val body = readJson(req) ?: return jsonError(400, "请求不是合法 JSON")
                runCatching { store.restoreVersion(body.optString("id"), body.optLong("setId")) }.fold(
                    onSuccess = { json(it) },
                    onFailure = { jsonError(400, it.message ?: "恢复失败") },
                )
            }

            "/api/version/delete" -> {
                val body = readJson(req) ?: return jsonError(400, "请求不是合法 JSON")
                runCatching { store.deleteVersion(body.optString("id")) }.fold(
                    onSuccess = { json(it) },
                    onFailure = { jsonError(400, it.message ?: "删除版本失败") },
                )
            }

            // ---- 删除：网页只能「申请」，执行权在手机 ----
            "/api/delete/request" -> {
                val body = readJson(req) ?: return jsonError(400, "请求不是合法 JSON")
                val setId = body.optLong("setId")
                if (setId <= 0) return jsonError(400, "「未分组」不支持在这里删，请回 App 里操作")
                runCatching {
                    LanEditorConfirm.requestDeleteSet(context, store.setBrief(setId))
                }.fold(
                    onSuccess = { r ->
                        json(
                            JSONObject()
                                .put("requestId", r.id)
                                .put("title", r.title)
                                .put("detail", r.detail)
                                .put("ttlSec", 600)
                        )
                    },
                    onFailure = { jsonError(400, it.message ?: "发起删除请求失败") },
                )
            }

            "/api/delete/status" -> {
                val id = req.query["id"] ?: return jsonError(400, "缺少请求 id")
                json(
                    JSONObject()
                        .put("status", LanEditorConfirm.status(id))
                        .put("error", LanEditorConfirm.errorOf(id) ?: JSONObject.NULL)
                )
            }

            "/api/delete/cancel" -> {
                val body = readJson(req) ?: return jsonError(400, "请求不是合法 JSON")
                val ok = LanEditorConfirm.reject(context, body.optString("id"))
                // 手机上已经点了确认时不能回 ok：否则网页会显示「已撤回」，题库却已经被删了
                json(
                    JSONObject()
                        .put("ok", ok)
                        .put("error", if (ok) JSONObject.NULL else "手机上已经点了确认，删除正在执行，撤不回来了")
                )
            }

            "/api/image" -> {
                val setId = req.query["setId"]?.toLongOrNull() ?: 0L
                val name = req.query["name"] ?: "image.png"
                if (req.body.isEmpty()) return jsonError(400, "图片内容为空")
                val rel = store.saveImage(setId, name, req.body, req.query["replace"])
                json(JSONObject().put("path", rel))
            }

            // ---- 资源管理：按组编号列图 / 删图 ----
            "/api/resources" -> {
                val setId = req.query["setId"]?.toLongOrNull() ?: 0L
                json(store.listResources(setId))
            }

            "/api/resource" -> {
                if (req.method != "DELETE") return jsonError(400, "删图片请用 DELETE")
                val p = req.query["p"] ?: return jsonError(400, "缺少图片路径")
                json(store.deleteResource(p))
            }

            // 图片迁移：把老散图按组编号归位，路径写回题库；body 里 setId=0 表示全库
            "/api/images/migrate" -> {
                val body = readJson(req)
                json(store.migrateImages(body?.optLong("setId") ?: 0L))
            }

            "/api/img" -> {
                val p = req.query["p"] ?: return jsonError(400, "缺少图片路径")
                val file = store.resolveImage(p) ?: return jsonError(404, "图片不存在：$p")
                Resp(200, imageMimeOf(file.name), file.readBytes())
            }

            else -> jsonError(404, "接口不存在：$path")
        }
    }

    private fun authorized(req: Req): Boolean {
        if (req.query["t"] == token) return true
        val cookie = req.headers["cookie"] ?: return false
        return cookie.split(';').any { it.trim() == "le_token=$token" }
    }

    private fun asset(name: String): ByteArray? = runCatching {
        context.assets.open("webeditor/$name").use { it.readBytes() }
    }.getOrNull()

    // ---------------------------------------------------------------- 响应

    private class Req(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class Resp(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val headers: List<Pair<String, String>> = emptyList(),
    )

    private fun json(obj: JSONObject, status: Int = 200) =
        Resp(status, JSON_TYPE, obj.toString().toByteArray(Charsets.UTF_8))

    private fun jsonError(status: Int, message: String) =
        json(JSONObject().put("error", message), status)

    private fun writeResponse(out: OutputStream, resp: Resp) {
        val head = buildString {
            append("HTTP/1.1 ").append(resp.status).append(' ').append(statusText(resp.status)).append("\r\n")
            append("Content-Type: ").append(resp.contentType).append("\r\n")
            append("Content-Length: ").append(resp.body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            resp.headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(resp.body)
        out.flush()
    }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun forbiddenPage(): ByteArray = """
        <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>需要访问口令</title>
        <style>body{font:15px/1.7 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif;
        background:#f2f5f9;color:#16202c;display:grid;place-items:center;height:100vh;margin:0}
        .card{background:#fff;border:1px solid #e3e9f1;border-radius:14px;padding:28px 30px;max-width:440px;
        box-shadow:0 10px 30px rgba(16,32,56,.08)}h1{font-size:17px;margin:0 0 10px}
        p{margin:0 0 8px;color:#5b6a7c}code{background:#eef2f8;padding:1px 5px;border-radius:4px}</style>
        </head><body><div class="card">
        <h1>这个页面需要访问口令</h1>
        <p>请在手机的「设置 → 局域网编辑器」里打开服务，然后照那里显示的完整链接访问
        （形如 <code>http://192.168.x.x:8777/?t=…</code>）。</p>
        <p>直接扫端口是进不来的——这串口令就是用来挡住同一 Wi-Fi 下的其他人的。</p>
        </div></body></html>
    """.trimIndent().toByteArray(Charsets.UTF_8)

    /**
     * 图片接口只回真图片的 MIME。
     *
     * [mimeOf] 会把 `.html` 当 `text/html`、`.svg` 当 `image/svg+xml` 回给浏览器——
     * 用在 `/web/` 静态资源上没问题，用在用户上传的文件上就是同源 XSS：
     * 传一个带脚本的 html 上来，脚本能拿着 Cookie 调 `/api/publish`。
     * 所以这里非白名单一律当二进制下载，`nosniff` 已在响应头里加过。
     */
    private fun imageMimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> HTML_TYPE
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> JSON_TYPE
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        else -> "application/octet-stream"
    }
}

/** 取本机局域网 IPv4：优先 192.168 → 10 → 172.16-31，都取不到就用回环 */
object LanNet {
    fun ipv4(): String? {
        val found = ArrayList<Pair<Int, String>>()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.inetAddresses.toList().forEach { addr ->
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: return@forEach
                        val score = when {
                            ip.startsWith("192.168.") -> 0
                            ip.startsWith("10.") -> 1
                            ip.startsWith("172.") -> 2
                            else -> 3
                        }
                        found.add(score to ip)
                    }
                }
            }
        }
        return found.minByOrNull { it.first }?.second
    }
}
