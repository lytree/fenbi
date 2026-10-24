package com.lytree.qbank.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * 屏幕形态判定 + 方向策略（手机 / 平板两套）。
 *
 * 为什么要在这里锁方向，而不是在 AndroidManifest 里写 `screenOrientation`：
 * 清单里一旦对某个 Activity 写死 `portrait`，平板（Android 12L+ 以及各家 ROM 的兼容模式）
 * 会把它当成「不支持大屏」的应用，塞进一块手机比例的兼容窗口里、两侧留黑边——也就是
 * 用户看到的「答题页被收缩成手机」。所以清单里不写方向，改在运行时按形态决定：
 *
 * - **手机**：锁竖屏（整套答题版式是按 1080x1920 竖屏量出来的）
 * - **平板**（最小宽度 ≥ 600dp）：不锁，跟随设备旋转、铺满全屏
 */
object ScreenAdapt {

    /** 平板判定：与系统 sw600dp 资源断点一致 */
    fun isTablet(config: Configuration): Boolean = config.smallestScreenWidthDp >= 600

    fun Activity.isTablet(): Boolean = isTablet(resources.configuration)

    /**
     * 按当前形态落地方向。答题链路上的每个 Activity 都要调一次——
     * 方向锁是 Activity 级的，答题页锁住了，答题卡 / 成绩报告这些子页面不会自动继承，
     * 手机横过来时会各自旋转出去。
     */
    fun applyOrientation(activity: Activity) {
        if (!isTablet(activity.resources.configuration)) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}
