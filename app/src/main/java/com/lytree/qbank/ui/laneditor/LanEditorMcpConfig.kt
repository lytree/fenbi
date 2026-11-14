package com.lytree.qbank.ui.laneditor

/**
 * 生成给 AI 客户端用的 MCP 配置片段。
 *
 * 地址已经不含口令（`http://192.168.x.x:8777/`），写权限单独用 `QBANK_KEY`
 * 走请求头 `X-Lan-Key` —— 这样口令不会出现在浏览器地址栏、历史和截图里。
 * 手机设了访问密码时，key 就是那串密码（服务重启也不变）；没设时才用每次启动的随机码。
 * 手拼这两段最容易漏字符，所以由 App 直接给一段能整段复制的 JSON。
 *
 * 路径只能留占位符——MCP server 跑在电脑上，手机不知道它装在哪。
 * 这里故意用中文尖括号，忘了改的话客户端会明确报「找不到文件」，比一个像模像样的假路径好排查。
 */
object LanEditorMcpConfig {

    const val PATH_PLACEHOLDER = "<换成电脑上 tools/qbank-mcp/dist/index.js 的绝对路径>"

    /** 服务没启动时给一份带占位地址的样例，让用户先看清结构 */
    const val SAMPLE_URL = "http://<手机IP>:8777"

    const val SAMPLE_KEY = "<口令>"

    fun json(pageUrl: String, accessKey: String = ""): String {
        val url = pageUrl.trim().trimEnd('/').ifEmpty { SAMPLE_URL }
        val key = accessKey.trim().ifEmpty { SAMPLE_KEY }
        return buildString {
            appendLine("{")
            appendLine("  \"mcpServers\": {")
            appendLine("    \"fenbi-qbank\": {")
            appendLine("      \"command\": \"node\",")
            appendLine("      \"args\": [\"$PATH_PLACEHOLDER\"],")
            appendLine("      \"env\": {")
            appendLine("        \"QBANK_URL\": \"$url\",")
            appendLine("        \"QBANK_KEY\": \"$key\"")
            appendLine("      }")
            appendLine("    }")
            appendLine("  }")
            append("}")
        }
    }
}
