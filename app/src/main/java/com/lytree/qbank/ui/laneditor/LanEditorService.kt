package com.lytree.qbank.ui.laneditor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
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

        /**
         * AI 配置里该写的口令：设了密码就是密码，没设就是固定保存的那串。
         * 两种情况都能让 MCP 直发，且配一次长期有效。
         */
        fun aiKey(context: Context): String =
            LanEditorPrefs.password(context) ?: LanEditorPrefs.mcpKey(context)

        fun lastRequest(): String = server?.lastRequest.orEmpty()

        /**
         * 系统是否把本 App 从电池优化里放出来了。
         *
         * 前台服务只保证「进程优先级高」，不保证「熄屏后还能跑」：小米 / HyperOS 的
         * 「省电策略」、原生 Android 的 Doze 都会在屏幕熄灭后冻结后台进程——进程一被 SIGSTOP，
         * TCP 三次握手还由内核照常完成，浏览器就卡在转圈直到超时（不是「连接被拒绝」）。
         * 这一项是 system 侧的开关，App 只能查、不能替用户开。
         */
        fun isBatteryUnrestricted(context: Context): Boolean = runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)

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

    /** 保活用的两台锁，见 [acquireKeepAlive]；服务停掉就释放 */
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null

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

        // intent 为 null = 进程被系统回收后按 START_STICKY 重启回来的，端口取落盘的那个
        val port = intent?.getIntExtra(EXTRA_PORT, savedPort(this)) ?: savedPort(this)
        savePort(this, port)

        // 一进来就先挂通知：Android 8+ 要求 startForegroundService 后 5 秒内必须 startForeground
        goForeground("正在启动…", "")

        val current = server
        if (current != null && current.isRunning) {
            // 已经在跑（重复点启动）：只把端口变化说清楚，不重建服务
            if (current.pageUrl.contains(":$port/")) {
                acquireKeepAlive()
                publishRunning(current)
            } else {
                LanEditorStatus.message.postValue("服务已在运行：${current.pageUrl}（改端口请先停止）")
                publishRunning(current)
            }
            return START_STICKY
        }

        val store = LanEditorStore(applicationContext)
        val s = LanEditorServer(applicationContext, port, store)
        val result = s.start()
        if (result.isSuccess) {
            server = s
            acquireKeepAlive()
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
        // STICKY：进程真被系统收走时，系统会带着 null intent 再拉一次，
        // 服务按落盘端口把监听重新架起来——否则「网页突然连不上」只能靠人回 App 手动点一遍
        return START_STICKY
    }

    /**
     * Android 15 起，前台服务如果按 dataSync 类型跑，累计 6 小时会被系统掐掉并回调这里。
     * 现在清单里用的是 specialUse（没有这个计时器），这里只是兜底：真被掐了就干脆停干净，
     * 别让用户看着通知栏以为还活着。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        shutdown()
        stopSelf()
        LanEditorStatus.message.postValue("系统已结束后台服务（前台服务超时），需要的话重新启动一次")
    }

    private fun publishRunning(s: LanEditorServer) {
        val url = s.pageUrl
        LanEditorStatus.running.postValue(true)
        LanEditorStatus.url.postValue(url)
        LanEditorStatus.message.postValue("同一 Wi-Fi 下用电脑浏览器打开下面这个地址")
        val nm = getSystemService(NotificationManager::class.java)
        val sub = if (isBatteryUnrestricted(this)) "点开可复制地址、停止服务"
        else "点开可复制地址；系统限制后台时点「后台保持」放行"
        nm.notify(NOTIF_ID, buildNotification(url, sub))
    }

    // ---------------------------------------------------------------- 保活

    /**
     * 两台锁：一台管 Wi-Fi 别进省电、一台管 CPU 别跟着熄屏睡死。
     *
     * 只有「前台服务」是不够的——屏幕一灭，Wi-Fi 芯片会按 DTIM 周期睡眠（部分 ROM 直接断连），
     * CPU 进深度睡眠，监听线程虽然没死但被挂起；电脑那边 TCP 已经握手成功，
     * 就是等不到一个字节，浏览器转圈到超时。这两台锁把这段空档补上。
     *
     * 代价是耗电，所以只在服务运行期间持有（通知栏一直有「停止」，用完就关）。
     */
    private fun acquireKeepAlive() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = runCatching { wm?.createWifiLock(mode, "qbank:lan-editor-wifi") }.getOrNull()
        }
        runCatching { wifiLock?.takeIf { !it.isHeld }?.acquire() }

        if (cpuLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            cpuLock = runCatching {
                pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qbank:lan-editor-cpu")
            }.getOrNull()
        }
        runCatching { cpuLock?.takeIf { !it.isHeld }?.acquire() }
    }

    private fun releaseKeepAlive() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        runCatching { cpuLock?.takeIf { it.isHeld }?.release() }
        cpuLock = null
    }

    /**
     * 前台化。Android 14+ 用三参版本显式声明类型（清单里是 specialUse）；
     * 10 分钟不升级的老系统没有类型概念，走两参版本。
     */
    private fun goForeground(title: String, sub: String) {
        val n = buildNotification(title, sub)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun shutdown() {
        server?.stop()
        server = null
        releaseKeepAlive()
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
        releaseKeepAlive()
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
