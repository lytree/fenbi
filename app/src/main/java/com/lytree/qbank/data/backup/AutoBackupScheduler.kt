package com.lytree.qbank.data.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 每日自动备份的闹钟。没上 WorkManager：这个 App 一路零依赖，
 * 一天一次的「夜里传个备份包」用 [AlarmManager.setInexactRepeating] 足够，
 * 迟到几十分钟无所谓，Doze 下系统也会补上。
 *
 * 每次 App 启动调一次 [sync]：闹钟在重装/强制停止后会被清掉，得重新挂。
 */
object AutoBackupScheduler {

    const val ACTION = "com.lytree.qbank.action.AUTO_BACKUP"

    private const val REQUEST_CODE = 0x5142

    /** 凌晨 3:30，手机在充电又闲着的时候 */
    private const val HOUR = 3
    private const val MINUTE = 30

    fun sync(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(app)

        if (!CloudStore.autoBackup(app) || !WebDavProvider(app).isConfigured()) {
            manager.cancel(pending)
            pending.cancel()
            return
        }

        manager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextTriggerAt(),
            AlarmManager.INTERVAL_DAY,
            pending
        )
    }

    private fun nextTriggerAt(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR)
            set(Calendar.MINUTE, MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AutoBackupReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * 到点跑一次备份。失败静默——自动备份不该在半夜弹窗，
 * 用户在备份页能看到「上次备份」时间，没更新就说明没成。
 */
class AutoBackupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AutoBackupScheduler.ACTION) return

        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!CloudStore.autoBackup(app)) return@launch
                val provider = WebDavProvider(app)
                if (!provider.isConfigured()) return@launch
                BackupManager.runBackup(app, provider)
            } catch (_: Throwable) {
                // 网络断了、密码改了、空间满了——下次再说，不打扰
            } finally {
                pending.finish()
            }
        }
    }
}
