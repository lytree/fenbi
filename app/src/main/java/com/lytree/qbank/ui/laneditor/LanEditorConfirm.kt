package com.lytree.qbank.ui.laneditor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.lytree.qbank.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会改题库的操作的「手机端闸门」。
 *
 * 网页地址不再带口令，同一 Wi-Fi 下谁都能打开编辑器、改草稿、传图；
 * 但**真正写库的那三件事**——发布、回滚、删除——网页只能「申请」，
 * 手机上 [LanEditorActivity] 弹框点一下才执行。这样电脑误点、别的标签页乱调接口，
 * 都改不动题库；许可始终留在手机这一侧。
 *
 * 例外是 AI（MCP）：它在配置里带 `X-Lan-Key` 请求头，
 * [LanEditorServer] 认出这个头就直接执行，不在这里排队——否则一次批量导入要点几十次手机。
 * 那串 key 不出现在网页 URL 上，只有主动复制给 AI 客户端的人才有。
 *
 * 请求只活在服务进程里：10 分钟没人理就作废，服务一停全部作废。
 * 页面那边靠轮询 [status] 拿结果，所以这里不能持久化——重启后旧的 requestId 一律当过期。
 */
object LanEditorConfirm {

    const val KIND_DELETE_SET = "delete_set"
    const val KIND_PUBLISH = "publish"
    const val KIND_RESTORE = "restore"

    const val STATUS_PENDING = "pending"
    const val STATUS_APPROVED = "approved"
    const val STATUS_REJECTED = "rejected"
    const val STATUS_EXPIRED = "expired"

    /** 手机已点确认、动作正在跑。对网页仍显示 pending，但拒绝「撤回」 */
    const val STATUS_RUNNING = "running"

    /** 等待时间：够人从电脑前拿起手机，又不至于让网页无限转圈 */
    private const val TTL_MS = 10 * 60 * 1000L

    /** 同时最多挂几条待确认：再多就是弹窗轰炸了 */
    private const val MAX_PENDING = 3

    private const val CHANNEL_ID = "lan_editor_confirm"
    private const val NOTIF_ID = 4202

    /**
     * @param payload  执行时才用的原始数据（发布 = title/replace/sheets，回滚 = versionId/setId）
     * @param result   执行成功后的返回值，网页轮询时原样取走
     */
    data class Request(
        val id: String,
        val kind: String,
        val setId: Long,
        val title: String,
        var detail: String,
        var payload: JSONObject,
        var createdAt: Long,
        var status: String = STATUS_PENDING,
        var error: String? = null,
        var result: JSONObject? = null,
    ) {
        val expired: Boolean get() = System.currentTimeMillis() - createdAt > TTL_MS
    }

    /** 待确认队列：Activity 观察它，有几个弹几个 */
    val pending = MutableLiveData<List<Request>>(emptyList())

    /** Activity 是否在前台：不在就发通知，免得请求躺在那儿没人看见 */
    @Volatile
    var activityVisible = false

    private var appContext: Context? = null
    private val items = LinkedHashMap<String, Request>()
    private var seq = 0

    // ---------------------------------------------------------------- 登记

    /** 网页发起删除一组：只登记，不动库。同组重复点就用回原来那条请求，别在手机上堆一串 */
    fun requestDeleteSet(context: Context, brief: JSONObject): Request {
        val ctx = context.applicationContext
        appContext = ctx
        val setId = brief.optLong("id")
        synchronized(items) {
            items.values.firstOrNull {
                it.kind == KIND_DELETE_SET && it.setId == setId && it.status == STATUS_PENDING && !it.expired
            }?.let { return it }
        }
        val detail = buildString {
            append("这组有 ${brief.optInt("paperCount")} 份卷、${brief.optInt("questionCount")} 道题")
            val records = brief.optInt("recordCount")
            if (records > 0) append("，还有 $records 条作答记录")
            append("。确认后题目和作答记录一起删除，恢复不了。")
        }
        return enqueue(
            ctx,
            kind = KIND_DELETE_SET,
            setId = setId,
            title = brief.optString("title"),
            detail = detail,
            payload = JSONObject().put("setId", setId),
        )
    }

    /**
     * 网页点「发布到 App」：只登记。
     *
     * 同一组还在等确认时，用最新内容替换掉旧请求（还回同一个 id）——
     * 用户连点两下发布，手机上不该弹两次，也不该拿第一版内容去写库。
     */
    fun requestPublish(context: Context, title: String, replace: Boolean, detail: String, sheets: JSONArray): Request {
        val ctx = context.applicationContext
        appContext = ctx
        val payload = JSONObject()
            .put("title", title)
            .put("replace", replace)
            .put("sheets", sheets)
        var reuse: Request? = null
        synchronized(items) {
            items.values.firstOrNull {
                it.kind == KIND_PUBLISH && it.title == title && it.status == STATUS_PENDING && !it.expired
            }?.let { old ->
                old.payload = payload
                old.detail = detail
                old.createdAt = System.currentTimeMillis()
                reuse = old
            }
        }
        reuse?.let { old ->
            // 通知和 LiveData 都放到锁外发：notify() 会碰系统服务，不该占着队列锁
            publish()
            if (!activityVisible) notifyPending(ctx, old)
            return old
        }
        return enqueue(ctx, KIND_PUBLISH, 0L, title, detail, payload)
    }

    /** 网页点「回滚到这一版」：同样走确认，回滚会覆盖当前内容 */
    fun requestRestore(context: Context, title: String, versionId: String, setId: Long, detail: String): Request {
        val ctx = context.applicationContext
        appContext = ctx
        val payload = JSONObject().put("versionId", versionId).put("setId", setId)
        var reuse: Request? = null
        synchronized(items) {
            items.values.firstOrNull {
                it.kind == KIND_RESTORE && it.title == title && it.status == STATUS_PENDING && !it.expired
            }?.let { old ->
                old.payload = payload
                old.detail = detail
                old.createdAt = System.currentTimeMillis()
                reuse = old
            }
        }
        reuse?.let { old ->
            publish()
            if (!activityVisible) notifyPending(ctx, old)
            return old
        }
        return enqueue(ctx, KIND_RESTORE, setId, title, detail, payload)
    }

    private fun enqueue(
        ctx: Context,
        kind: String,
        setId: Long,
        title: String,
        detail: String,
        payload: JSONObject,
    ): Request {
        // 队列上限：地址没有口令，同一 Wi-Fi 的人乱点也能把你手机弹成筛子。
        // 满了就拒，等手机上先处理掉几条。
        val waiting = synchronized(items) {
            items.values.count { it.status == STATUS_PENDING && !it.expired }
        }
        require(waiting < MAX_PENDING) {
            "手机上还有 $waiting 条待确认请求，先在手机上处理掉再发"
        }
        val id = "r${System.currentTimeMillis().toString(36)}-${(++seq).toString(36)}"
        val req = Request(
            id = id,
            kind = kind,
            setId = setId,
            title = title,
            detail = detail,
            payload = payload,
            createdAt = System.currentTimeMillis(),
        )
        synchronized(items) { items[id] = req }
        publish()
        if (!activityVisible) notifyPending(ctx, req)
        return req
    }

    // ---------------------------------------------------------------- 查询

    /** 网页轮询用：pending / approved / rejected / expired */
    fun status(id: String): String = synchronized(items) {
        val req = items[id] ?: return STATUS_EXPIRED
        if (req.status == STATUS_PENDING && req.expired) {
            req.status = STATUS_EXPIRED
            pending.postValue(pendingOf())
        }
        // 「正在跑」对网页来说还没有结论，仍然回 pending，别让它以为请求已作废
        if (req.status == STATUS_RUNNING) STATUS_PENDING else req.status
    }

    /** 执行成功的返回值（发布结果 / 回滚结果），没成功就是 null */
    fun resultOf(id: String): JSONObject? = synchronized(items) { items[id] }?.result

    fun errorOf(id: String): String? = synchronized(items) { items[id] }?.error

    fun titleOf(id: String): String? = synchronized(items) { items[id] }?.title

    // ---------------------------------------------------------------- 执行

    /** 手机上点了「确认」：后台线程真执行，跑完再改状态，网页那头的轮询自然拿到结果 */
    fun approve(context: Context, id: String) {
        // 「检查 + 占位」必须一次做完：手机点确认和网页点撤回可能同时到达，
        // 分成两步会出现「网页提示没执行、库里其实已经改了」
        val req = synchronized(items) {
            val r = items[id] ?: return
            if (r.status != STATUS_PENDING) return
            if (r.expired) {
                r.status = STATUS_EXPIRED
                publish()
                return
            }
            r.status = STATUS_RUNNING
            r
        }
        val ctx = context.applicationContext
        appContext = ctx
        cancelNotify(ctx)
        Thread({
            val r = runCatching { execute(ctx, req) }
            synchronized(items) {
                // 服务被停掉、clear() 已经把状态改掉的话，就别再写回了
                if (req.status != STATUS_RUNNING) return@synchronized
                r.fold(
                    onSuccess = { out ->
                        req.result = out
                        req.status = STATUS_APPROVED
                    },
                    onFailure = {
                        req.error = it.message ?: "执行失败"
                        req.status = STATUS_REJECTED
                    },
                )
            }
            publish()
        }, "lan-editor-approve").apply {
            isDaemon = true
            start()
        }
    }

    private fun execute(ctx: Context, req: Request): JSONObject {
        val store = LanEditorStore(ctx)
        return when (req.kind) {
            KIND_DELETE_SET -> store.deleteSet(req.setId)

            KIND_PUBLISH -> store.publish(
                title = req.payload.optString("title"),
                replace = req.payload.optBoolean("replace", false),
                sheets = req.payload.optJSONArray("sheets") ?: JSONArray(),
            )

            KIND_RESTORE -> store.restoreVersion(
                id = req.payload.optString("versionId"),
                targetSetId = req.payload.optLong("setId", 0L),
            )

            else -> throw IllegalStateException("不认识的操作：${req.kind}")
        }
    }

    /**
     * 手机上点了「不」/「不发布」，或网页上撤回了请求。
     *
     * @return false = 手机上已经确认、动作正在执行，撤不回来了
     */
    fun reject(context: Context?, id: String): Boolean {
        val ok = synchronized(items) {
            val req = items[id] ?: return false
            if (req.status != STATUS_PENDING) return false
            req.status = STATUS_REJECTED
            true
        }
        publish()
        cancelNotify(context ?: appContext)
        return ok
    }

    /** 服务停止时清空：还在等的请求一律作废 */
    fun clear() {
        synchronized(items) {
            items.values.forEach {
                if (it.status == STATUS_PENDING || it.status == STATUS_RUNNING) it.status = STATUS_EXPIRED
            }
            items.clear()
        }
        pending.postValue(emptyList())
        cancelNotify(appContext)
    }

    private fun publish() {
        val list = synchronized(items) { pendingOf() }
        pending.postValue(list)
    }

    /** 还在等手机确认的那些；调用方自己保证在 [items] 的锁里 */
    private fun pendingOf(): List<Request> =
        items.values.filter { it.status == STATUS_PENDING && !it.expired }.toList()

    // ---------------------------------------------------------------- 通知

    private fun notifyPending(context: Context, req: Request) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        createChannel(nm)
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, LanEditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val action = when (req.kind) {
            KIND_PUBLISH -> "发布"
            KIND_RESTORE -> "回滚"
            else -> "删除"
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("电脑请求${action}「${req.title}」")
            .setContentText("点开确认或拒绝；不点，10 分钟后自动作废")
            .setStyle(NotificationCompat.BigTextStyle().bigText(req.detail))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        runCatching { nm.notify(NOTIF_ID, notif) }
    }

    private fun cancelNotify(context: Context?) {
        if (context == null) return
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        }
    }

    private fun createChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "发布确认", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "电脑上的编辑器请求发布 / 回滚 / 删除时提醒，点开在手机上确认"
                setShowBadge(true)
            }
        )
    }
}
