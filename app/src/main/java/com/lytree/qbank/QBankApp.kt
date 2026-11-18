package com.lytree.qbank

import android.app.Application
import android.os.Bundle
import com.lytree.qbank.ui.laneditor.LanEditorConfirm

/**
 * 全局 Application：只干一件事——用 ActivityLifecycleCallbacks 记着「App 现在在前台吗」。
 *
 * 局域网编辑器的确认弹窗要在**任何界面**弹出（不只停在开关页）：请求一来，
 * 只要 App 在前台就立刻把确认弹层盖到当前画面上（见 [LanEditorConfirm]），
 * 判断依据就是这个前台计数——`started` 活动数 > 0 即在前台。
 */
class QBankApp : Application() {

    private var started = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                started++
                LanEditorConfirm.appVisible = started > 0
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                started = (started - 1).coerceAtLeast(0)
                LanEditorConfirm.appVisible = started > 0
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }
}
