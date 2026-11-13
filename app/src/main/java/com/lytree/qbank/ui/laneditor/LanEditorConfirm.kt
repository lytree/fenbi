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
import org.json.JSONObject

/**
 * 破坏性操作的「手机端闸门」。
 *
 * 网页端只有读 / 发布 / 传图；删除这类操作网页只能**发起请求**，
 * 真正动库的是手机上这一下——[LanEditorActivity] 弹出确认框，人点了「确认删除」才执行。
 * 这样即便电脑上误点、或者浏览器里别的标签页想搞事，也删不掉题库。
 *
 * 请求只活在服务进程里：10 分钟没人理就作废，服务一停全部作废。
 * 页面那边靠轮询 [status] 拿结果，所以这里不能持久化——重启后旧的 requestId 一律当过期。
 */
object LanEditorConfirm {

    const val KIND_DELETE_SET = "delete_set"

    const val STATUS_PENDING = "pending"
    const val STATUS_APPROVED = "approved"
    const val STATUS_REJECTED = "rejected"
    const val STATUS_EXPIRED = "expired"

    /** 手机已点确认、删除正在跑。对网页仍显示 pending，但拒绝「撤回」 */
    const val STATUS_DELETING = "deleting"

    /** 等待时间：够人从电脑前拿起手机，又不至于让网页无限转圈 */
    private const val TTL_MS = 10 * 60 * 1000L
    private const val CHANNEL_ID = "lan_editor_confirm"
    private const val NOTIF_ID = 4202

    data class Request(
        val id: String,
        val kind: String,
        val setId: Long,
        val title: String,
        val detail: String,
        val createdAt: Long,
        var status: String = STATUS_PENDING,
        var error: String? = null,
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
        val id = "r${System.currentTimeMillis().toString(36)}-${(++seq).toString(36)}"
        val detail = buildString {
            append("这组有 ${brief.optInt("paperCount")} 份卷、${brief.optInt("questionCount")} 道题")
            val records = brief.optInt("recordCount")
            if (records > 0) append("，还有 $records 条作答记录")
            append("。确认后题目和作答记录一起删除，恢复不了。")
        }
        val req = Request(
            id = id,
            kind = KIND_DELETE_SET,
            setId = brief.optLong("id"),
            title = brief.optString("title"),
            detail = detail,
            createdAt = System.currentTimeMillis(),
        )
        synchronized(items) { items[id] = req }
        publish()
        if (!activityVisible) notifyPending(ctx, req)
        return req
    }

    /** 网页轮询用：pending / approved / rejected / expired */
    fun status(id: String): String = synchronized(items) {
        val req = items[id] ?: return STATUS_EXPIRED
        if (req.status == STATUS_PENDING && req.expired) {
            req.status = STATUS_EXPIRED
            pending.postValue(pendingOf())
        }
        // 「正在删」对网页来说还没有结论，仍然回 pending，别让它以为请求已作废
        if (req.status == STATUS_DELETING) STATUS_PENDING else req.status
    }

    fun errorOf(id: String): String? = synchronized(items) { items[id] }?.error

    /** 手机上点了「确认删除」：后台线程真删，删完再改状态，网页那头的轮询自然拿到结果 */
    fun approve(context: Context, id: String) {
        // 「检查 + 占位」必须一次做完：手机点确认和网页点撤回可能同时到达，
        // 分成两步会出现「网页提示没删、库里其实已经删了」
        val req = synchronized(items) {
            val r = items[id] ?: return
            if (r.status != STATUS_PENDING) return
            if (r.expired) {
                r.status = STATUS_EXPIRED
                publish()
                return
            }
            r.status = STATUS_DELETING
            r
        }
        val ctx = context.applicationContext
        appContext = ctx
        cancelNotify(ctx)
        Thread({
            val r = runCatching { LanEditorStore(ctx).deleteSet(req.setId) }
            synchronized(items) {
                // 服务被停掉、clear() 已经把状态改掉的话，就别再写回了
                if (req.status != STATUS_DELETING) return@synchronized
                req.error = r.exceptionOrNull()?.message
                req.status = if (r.isSuccess) STATUS_APPROVED else STATUS_REJECTED
            }
            publish()
        }, "lan-editor-delete").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 手机上点了「不删」，或网页上撤回了请求。
     *
     * @return false = 手机上已经确认、删除正在执行，撤不回来了
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
                if (it.status == STATUS_PENDING || it.status == STATUS_DELETING) it.status = STATUS_EXPIRED
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
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("电脑请求删除「${req.title}」")
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
            NotificationChannel(CHANNEL_ID, "删除确认", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "电脑上的编辑器请求删除时提醒，点开在手机上确认"
                setShowBadge(true)
            }
        )
    }
}
