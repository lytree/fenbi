package com.lytree.qbank.ui.laneditor

import android.content.Context
import kotlin.random.Random

/**
 * 局域网编辑器的持久化设置：端口 + 访问密码 + AI 写入口令。
 *
 * 为什么单独抽出来：以前口令是每次启动随机生成的，**重启就得重新复制链接**；
 * 现在密码由用户自己定、存在这里，网页登录和 AI 写入口令共用同一串，
 * 手机重启、服务重启都不用再改配置。没设密码时也有一个固定口令（[mcpKey]），
 * 同样不会因为重启而变。
 *
 * 这些值就明文躺在 App 私有目录的 SharedPreferences 里：这不是账号体系，
 * 它保护的是「同一 Wi-Fi 下顺手打开地址的人」，不是拿到设备的人。
 * 系统备份会带走它——换机恢复后不用重设。
 */
object LanEditorPrefs {

    private const val PREFS = "lan_editor"
    private const val KEY_PORT = "port"
    private const val KEY_PASSWORD = "password"
    private const val KEY_MCP_KEY = "mcp_key"
    private const val KEY_KEEPALIVE_PROMPTED = "keepalive_prompted"

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

    /**
     * AI（MCP）写入口令，首次调用时生成后固定保存。
     *
     * 以前没设密码时这串是每次启动随机生成的，服务一重启配置就失效，
     * AI 客户端那边要回来重新复制一遍；固定在本地之后，
     * 手机上「复制 MCP 配置」和网页上的「复制 MCP」拿到的都是同一串，配一次长期有效。
     */
    @Synchronized
    fun mcpKey(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_MCP_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val created = buildString {
            val pool = "abcdefghijkmnpqrstuvwxyz23456789"
            repeat(32) { append(pool[Random.nextInt(pool.length)]) }
        }
        prefs.edit().putString(KEY_MCP_KEY, created).apply()
        return created
    }

    /**
     * 「后台可能被系统冻住」这条提醒是否提过了。
     *
     * 只提一次：没放宽后台限制的话网页迟早失联，但每次启动都弹会变成骚扰，
     * 页面上的「后台保持」卡片随时能再进设置。
     */
    fun keepAlivePrompted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEPALIVE_PROMPTED, false)

    fun markKeepAlivePrompted(context: Context) {
        prefs(context).edit().putBoolean(KEY_KEEPALIVE_PROMPTED, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
