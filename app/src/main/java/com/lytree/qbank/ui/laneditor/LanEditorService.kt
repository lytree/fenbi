package com.lytree.qbank.ui.laneditor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.lytree.qbank.R

/** 服务状态：界面观察它，不用去摸 Service 实例 */
object LanEditorStatus {
    val running = MutableLiveData(false)
    val url = MutableLiveData("")
    val message = MutableLiveData("")
}

/**
 * 前台服务：把 [LanEditorServer] 托起来，屏幕熄了、切到别的 App 也不断。
 *
 * 用前台服务而不是「Activity 里起个线程」的原因很实际——加题是坐在电脑前干半小时的活，
 * 手机上要能锁屏、要能切去别的应用；没前台服务，进程一被回收网页就 502。
 * 通知栏常驻一条，写明地址和「停止」，不会偷跑。
 */
class LanEditorService : Service() {

    companion object {
        const val ACTION_START = "com.lytree.qbank.action.LAN_EDITOR_START"
        const val ACTION_STOP = "com.lytree.qbank.action.LAN_EDITOR_STOP"
        const val EXTRA_PORT = "port"

        private const val CHANNEL_ID = "lan_editor"
        private const val NOTIF_ID = 4201

        @Volatile
        private var server: LanEditorServer? = null

        fun isRunning(): Boolean = server?.isRunning == true

        fun pageUrl(): String = server?.pageUrl.orEmpty()

        /** 随机写入口令：只在**没设密码**时给 AI 用 */
        fun accessKey(): String = server?.accessKey.orEmpty()

        /**
         * AI 配置里该写的口令：设了密码就是密码（重启也不变），没设才用随机码。
         * 两种情况都能让 MCP 直发；网页端永远拿不到这串。
         */
        fun aiKey(context: Context): String =
            LanEditorPrefs.password(context) ?: accessKey()

        fun lastRequest(): String = server?.lastRequest.orEmpty()

        fun savedPort(context: Context): Int = LanEditorPrefs.port(context)

        fun savePort(context: Context, port: Int) = LanEditorPrefs.savePort(context, port)

        /**
         * 改访问密码：先落盘，服务在跑就热更新（会话全部失效，网页要重新登录）。
         * 传 null / 空串表示取消密码，网页回到「打开就能用」。
         */
        fun updatePassword(context: Context, password: String?) {
            LanEditorPrefs.savePassword(context, password)
            server?.updatePassword(password)
        }

        fun start(context: Context, port: Int) {
            val intent = Intent(context, LanEditorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LanEditorService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, savedPort(this)) ?: savedPort(this)
        savePort(this, port)

        // 一进来就先挂通知：Android 8+ 要求 startForegroundService 后 5 秒内必须 startForeground
        startForeground(NOTIF_ID, buildNotification("正在启动…", ""))

        val current = server
        if (current != null && current.isRunning) {
            // 已经在跑（重复点启动）：只把端口变化说清楚，不重建服务
            if (current.pageUrl.contains(":$port/")) {
                publishRunning(current)
            } else {
                LanEditorStatus.message.postValue("服务已在运行：${current.pageUrl}（改端口请先停止）")
                publishRunning(current)
            }
            return START_NOT_STICKY
        }

        val store = LanEditorStore(applicationContext)
        val s = LanEditorServer(applicationContext, port, store)
        val result = s.start()
        if (result.isSuccess) {
            server = s
            publishRunning(s)
        } else {
            server = null
            val msg = result.exceptionOrNull()?.message ?: "启动失败"
            LanEditorStatus.running.postValue(false)
            LanEditorStatus.url.postValue("")
            LanEditorStatus.message.postValue(msg)
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification("启动失败：$msg", ""))
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun publishRunning(s: LanEditorServer) {
        val url = s.pageUrl
        LanEditorStatus.running.postValue(true)
        LanEditorStatus.url.postValue(url)
        LanEditorStatus.message.postValue("同一 Wi-Fi 下用电脑浏览器打开下面这个地址")
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(url, "点开可复制地址、停止服务"))
    }

    private fun shutdown() {
        server?.stop()
        server = null
        // 还在等手机确认的请求（发布 / 回滚 / 删除）一并作废：服务都没了，网页那头也不用再等
        LanEditorConfirm.clear()
        LanEditorStatus.running.postValue(false)
        LanEditorStatus.url.postValue("")
        LanEditorStatus.message.postValue("已停止")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        LanEditorConfirm.clear()
        LanEditorStatus.running.postValue(false)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "局域网编辑器", NotificationManager.IMPORTANCE_LOW).apply {
                description = "在手机上起一个网页服务，电脑加题用"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(title: String, sub: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, LanEditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LanEditorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(sub)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
