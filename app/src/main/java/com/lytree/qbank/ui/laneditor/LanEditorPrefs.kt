package com.lytree.qbank.ui.laneditor

import android.content.Context

/**
 * 局域网编辑器的持久化设置：端口 + 访问密码。
 *
 * 为什么单独抽出来：以前口令是每次启动随机生成的，**重启就得重新复制链接**；
 * 现在密码由用户自己定、存在这里，网页登录和 AI 写入口令共用同一串，
 * 手机重启、服务重启都不用再改配置。
 *
 * 密码就明文躺在 App 私有目录的 SharedPreferences 里：这不是账号体系，
 * 它保护的是「同一 Wi-Fi 下顺手打开地址的人」，不是拿到设备的人。
 * 系统备份会带走它——换机恢复后不用重设。
 */
object LanEditorPrefs {

    private const val PREFS = "lan_editor"
    private const val KEY_PORT = "port"
    private const val KEY_PASSWORD = "password"

    const val DEFAULT_PORT = 8777

    /** 密码最短长度：太短挡不住顺手试两下的人 */
    const val MIN_PASSWORD_LENGTH = 4

    fun port(context: Context): Int = prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun savePort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_PORT, port).apply()
    }

    /** 空串按「没设密码」处理，免得设置页留空保存后还把人拦在外面 */
    fun password(context: Context): String? =
        prefs(context).getString(KEY_PASSWORD, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun savePassword(context: Context, password: String?) {
        val value = password?.trim()?.takeIf { it.isNotEmpty() }
        prefs(context).edit().apply {
            if (value == null) remove(KEY_PASSWORD) else putString(KEY_PASSWORD, value)
        }.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
