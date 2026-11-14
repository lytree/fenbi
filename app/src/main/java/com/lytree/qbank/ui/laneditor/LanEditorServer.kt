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
 * 安全模型（两层）：
 * - **登录**：手机里可以设一个固定密码（[LanEditorPrefs]）。设了之后网页先要输密码，
 *   `/api` 下的接口一律凭会话 Cookie 放行；没设就是整站开放（地址本身不带口令）。
 * - **写库确认**：就算登录了，**发布 / 回滚 / 删除**也只登记一条请求，
 *   必须由手机上的 [LanEditorActivity] 点「确认」才动库（见 [LanEditorConfirm]）。
 * - AI（MCP）在请求头里带 `X-Lan-Key: <密码>` 才允许直接发布——批量导入没法每步都点手机。
 *   没设密码时那串是每次启动随机生成的 accessKey。key 不出现在任何 URL 上。
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

        /** 登录会话的 Cookie 名，和网页侧无关（浏览器自己带，前端代码不碰） */
        private const val SESSION_COOKIE = "le_session"

        /** 连错这么多次就锁一分钟：4 位数字也经不起一直试 */
        private const val MAX_LOGIN_FAILS = 8
        private const val LOGIN_LOCK_MS = 60_000L
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * 给 AI 客户端用的写入口令（请求头 `X-Lan-Key`）。
     *
     * 用户在手机上设了访问密码之后，**密码本身就是写入口令**（见 [hasAccessKey]）；
     * 没设密码时用这串每次启动新生成的随机码，AI 配置照样能直发。
     * 两者都不进 URL，网页端拿不到，所以网页的写操作一律要过手机确认。
     */
    val accessKey: String = randomToken()

    /** 网页登录口令；null = 没设密码，打开地址就能用 */
    @Volatile
    private var password: String? = LanEditorPrefs.password(context)

    /** 登录成功后的会话 token（只在内存里，服务重启就失效，得重新输密码） */
    private val sessions: MutableSet<String> =
        Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** 登录失败节流：连错 8 次锁 1 分钟，别让人拿 4 位数字试穿 */
    @Volatile
    private var failedLogins: Int = 0

    @Volatile
    private var lockedUntil: Long = 0L

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
        get() = "http://${LanNet.ipv4() ?: "127.0.0.1"}:$port/"

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
            // 设了密码又没登录：先给登录页。前端 bundle 里没有数据，登录后再加载也一样
            if (needLogin() && !hasSession(req)) return Resp(200, HTML_TYPE, loginPage())
            val body = asset("index.html") ?: return Resp(500, TEXT_TYPE, "缺少界面资源".toByteArray())
            return Resp(200, HTML_TYPE, body)
        }

        if (path.startsWith("/web/")) {
            val name = path.removePrefix("/web/")
            if (name.contains("..")) return Resp(404, TEXT_TYPE, "not found".toByteArray())
            val body = asset(name) ?: return Resp(404, TEXT_TYPE, "not found".toByteArray())
            return Resp(200, mimeOf(name), body)
        }

        if (!path.startsWith("/api/")) return Resp(404, TEXT_TYPE, "not found".toByteArray())

        // 登录接口自己不能要登录态
        if (path == "/api/login") return handleLogin(req)

        if (!authorized(req)) return jsonError(401, "需要登录：刷新页面重新输入密码")

        return when (path) {
            "/api/meta" -> json(
                JSONObject()
                    .put("port", port)
                    .put("url", pageUrl)
                    .put("app", "fenbi")
                    // 网页据此提示「发布要手机确认」，不用把这句话硬编码在前端
                    .put("publishNeedsConfirm", true)
            )

            "/api/papers" -> json(store.listSets())

            "/api/paper" -> {
                val id = req.query["id"]?.toLongOrNull() ?: return jsonError(400, "缺少试卷组 id")
                json(store.loadSet(id))
            }

            // ---- 发布：AI 带 key 直发，网页登记一条「等手机点确认」 ----
            "/api/publish" -> handlePublish(req)

            "/api/publish/status" -> {
                val id = req.query["id"] ?: return jsonError(400, "缺少请求 id")
                json(
                    JSONObject()
                        .put("status", LanEditorConfirm.status(id))
                        .put("result", LanEditorConfirm.resultOf(id) ?: JSONObject.NULL)
                        .put("error", LanEditorConfirm.errorOf(id) ?: JSONObject.NULL)
                )
            }

            "/api/publish/cancel" -> {
                val body = readJson(req) ?: return jsonError(400, "请求不是合法 JSON")
                val id = body.optString("id")
                val ok = LanEditorConfirm.reject(context, id)
                json(
                    JSONObject()
                        .put("ok", ok)
                        .put("error", if (ok) JSONObject.NULL else "手机上已经点了确认，正在写入，撤不回来了")
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

            "/api/version/restore" -> handleRestore(req)

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

    /**
     * 发布一组。
     *
     * 两个入口：
     * - AI 客户端带 `X-Lan-Key` → 直接写库，返回发布结果（和以前一样）；
     * - 网页（没有 key）→ 先校验、再登记一条待确认请求，返回 `needConfirm=true` + requestId，
     *   网页拿它轮询 `/api/publish/status`。手机不点，题库一个字都不会变。
     */
    private fun handlePublish(req: Req): Resp {
        val payload = readJson(req) ?: return jsonError(400, "请求不是合法 JSON")
        val title = payload.optString("title").trim()
        if (title.isEmpty()) return jsonError(400, "标题不能为空（它会成为 App 首页的那一组）")
        val replace = payload.optBoolean("replace", false)
        val sheets = payload.optJSONArray("sheets") ?: return jsonError(400, "没有收到试卷内容")
        if (sheets.length() == 0) return jsonError(400, "至少要有一份试卷")

        val existing = store.findSetIdByTitle(title)
        // 已有同名组又没说要覆盖：先让网页问一句，别把带作答记录的那一组顶掉
        if (existing != null && !replace) {
            return jsonError(400, "已经有同名的组「$title」，确认覆盖请点「覆盖发布」")
        }

        if (hasAccessKey(req)) {
            return runCatching { store.publish(title, replace, sheets) }.fold(
                onSuccess = { json(it) },
                onFailure = { jsonError(400, it.message ?: "发布失败") },
            )
        }

        val questionCount = (0 until sheets.length()).sumOf { i ->
            sheets.optJSONObject(i)?.optJSONArray("questions")?.length() ?: 0
        }
        val detail = buildString {
            append("共 ${sheets.length()} 份卷、$questionCount 道题。")
            if (existing != null) {
                append("会覆盖同名组，原有作答记录一并清掉；旧内容会自动存一版，还能滚回来。")
            } else {
                append("确认后写进手机题库，App 首页就能看到。")
            }
        }
        val r = LanEditorConfirm.requestPublish(context, title, replace, detail, sheets)
        return json(
            JSONObject()
                .put("needConfirm", true)
                .put("requestId", r.id)
                .put("title", title)
                .put("detail", detail)
                .put("ttlSec", 600)
                .put("replaced", existing != null),
        )
    }

    /** 回滚到某个版本：同样「AI 直发、网页等手机确认」 */
    private fun handleRestore(req: Req): Resp {
        val body = readJson(req) ?: return jsonError(400, "请求不是合法 JSON")
        val versionId = body.optString("id")
        if (versionId.isEmpty()) return jsonError(400, "缺少版本 id")
        val setId = body.optLong("setId", 0L)

        if (hasAccessKey(req)) {
            return runCatching { store.restoreVersion(versionId, setId) }.fold(
                onSuccess = { json(it) },
                onFailure = { jsonError(400, it.message ?: "恢复失败") },
            )
        }

        val title = body.optString("title").trim().ifEmpty {
            runCatching { store.setBrief(setId).optString("title") }.getOrDefault("题库")
        }
        val detail = "会把当前内容换成这一版。换之前的当前内容会先存一版，滚错了还能滚回来。"
        val r = LanEditorConfirm.requestRestore(context, title, versionId, setId, detail)
        return json(
            JSONObject()
                .put("needConfirm", true)
                .put("requestId", r.id)
                .put("title", title)
                .put("detail", detail)
                .put("ttlSec", 600),
        )
    }

    // ---------------------------------------------------------------- 登录

    /**
     * 用户在手机上改密码：立刻生效，并踢掉所有已登录的浏览器。
     *
     * 不重启服务——加题加到一半被断掉很烦；正在等手机确认的发布请求也不受影响
     * （它们走 [LanEditorConfirm] 的队列，不依赖会话）。
     */
    fun updatePassword(newPassword: String?) {
        password = newPassword?.trim()?.takeIf { it.isNotEmpty() }
        sessions.clear()
        failedLogins = 0
        lockedUntil = 0L
    }

    /** 没设密码时整站开放，和以前一样；设了就要登录 */
    private fun needLogin(): Boolean = password != null

    private fun authorized(req: Req): Boolean =
        !needLogin() || hasSession(req) || hasAccessKey(req)

    private fun hasSession(req: Req): Boolean {
        val token = sessionToken(req) ?: return false
        return sessions.contains(token)
    }

    private fun sessionToken(req: Req): String? {
        val cookie = req.headers["cookie"] ?: return null
        return cookie.split(';').firstNotNullOfOrNull { part ->
            val t = part.trim()
            if (t.startsWith("$SESSION_COOKIE=")) t.removePrefix("$SESSION_COOKIE=") else null
        }
    }

    private fun handleLogin(req: Req): Resp {
        if (!needLogin()) return json(JSONObject().put("ok", true))
        if (req.method != "POST") return jsonError(400, "登录请用 POST")

        val now = System.currentTimeMillis()
        if (now < lockedUntil) {
            val left = (lockedUntil - now) / 1000 + 1
            return jsonError(429, "试错太多次，$left 秒后再试")
        }
        val body = readJson(req) ?: return jsonError(400, "请求不是合法 JSON")
        val input = body.optString("password")
        if (input.isEmpty() || input != password) {
            failedLogins++
            if (failedLogins >= MAX_LOGIN_FAILS) {
                lockedUntil = now + LOGIN_LOCK_MS
                failedLogins = 0
            }
            return jsonError(401, "密码不对")
        }
        failedLogins = 0
        val token = randomToken()
        sessions.add(token)
        return Resp(
            200,
            JSON_TYPE,
            JSONObject().put("ok", true).toString().toByteArray(Charsets.UTF_8),
            // HttpOnly：脚本读不到，XSS 也偷不走；同源请求浏览器会自动带上
            listOf("Set-Cookie" to "$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800"),
        )
    }

    /**
     * AI 客户端的写入口令。
     *
     * 只认请求头，不认 query：口令进了 URL 就会出现在浏览器历史、日志、截图里。
     * 认两种：用户设的固定密码（推荐，重启不变），以及没设密码时的随机 accessKey。
     */
    private fun hasAccessKey(req: Req): Boolean {
        val key = req.headers["x-lan-key"]?.trim().orEmpty()
        if (key.isEmpty()) return false
        if (key == accessKey) return true
        val pwd = password ?: return false
        return key == pwd
    }

    private fun randomToken(): String = buildString {
        val pool = "abcdefghijkmnpqrstuvwxyz23456789"
        repeat(32) { append(pool[Random.nextInt(pool.length)]) }
    }

    /** 没设密码时不显示这一页，所以这里只考虑「已设密码」的文案 */
    private fun loginPage(): ByteArray = """
        <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>题库编辑器 · 登录</title>
        <style>
        *{box-sizing:border-box}
        body{font:15px/1.7 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif;background:#f2f5f9;
        color:#16202c;display:grid;place-items:center;min-height:100vh;margin:0;padding:24px}
        .card{background:#fff;border:1px solid #e3e9f1;border-radius:14px;padding:28px 30px;max-width:380px;
        width:100%;box-shadow:0 10px 30px rgba(16,32,56,.08)}
        h1{font-size:17px;margin:0 0 6px}
        p{margin:0 0 18px;color:#5b6a7c;font-size:13px}
        input{width:100%;height:40px;border:1px solid #d7dfe9;border-radius:9px;padding:0 12px;
        font-size:15px;outline:none;font-family:inherit}
        input:focus{border-color:#3b82f6}
        button{width:100%;height:40px;margin-top:12px;border:0;border-radius:9px;background:#3b82f6;
        color:#fff;font-size:15px;cursor:pointer;font-family:inherit}
        button:disabled{opacity:.6}
        .err{margin-top:10px;color:#b91c1c;font-size:12.5px;min-height:18px}
        .tip{margin-top:14px;color:#8a97a8;font-size:12px}
        </style></head><body>
        <form class="card" id="f">
        <h1>题库编辑器</h1>
        <p>请输入手机上设置的访问密码。</p>
        <input id="p" type="password" autocomplete="current-password" placeholder="访问密码" autofocus>
        <button type="submit" id="b">进入</button>
        <div class="err" id="e"></div>
        <div class="tip">密码在手机 App 的「设置 → 局域网编辑器 → 访问密码」里设置。</div>
        </form>
        <script>
        var f=document.getElementById('f'),p=document.getElementById('p'),
            e=document.getElementById('e'),b=document.getElementById('b');
        f.addEventListener('submit',async function(ev){ev.preventDefault();
        if(!p.value){e.textContent='请输入密码';return}
        b.disabled=true;e.textContent='';
        try{var r=await fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({password:p.value})});
        if(r.ok){location.replace('/');return}
        var d=await r.json().catch(function(){return{}});e.textContent=d.error||'密码不对'}
        catch(x){e.textContent='连不上手机，稍后再试'}finally{b.disabled=false}});
        </script></body></html>
    """.trimIndent().toByteArray(Charsets.UTF_8)

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
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    /**
     * 图片接口只回真图片的 MIME。
     *
     * [mimeOf] 会把 `.html` 当 `text/html`、`.svg` 当 `image/svg+xml` 回给浏览器——
     * 用在 `/web/` 静态资源上没问题，用在用户上传的文件上就是同源 XSS：
     * 传一个带脚本的 html 上来，脚本就能以同源身份调 `/api` 下的接口（发布仍需手机确认，
     * 但读题库、传图、改草稿都挡不住）。所以这里非白名单一律当二进制下载，
     * `nosniff` 已在响应头里加过。
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
