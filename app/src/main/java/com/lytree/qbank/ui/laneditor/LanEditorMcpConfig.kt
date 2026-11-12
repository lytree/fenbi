package com.lytree.qbank.ui.laneditor

/**
 * 生成给 AI 客户端用的 MCP 配置片段。
 *
 * 为什么由 App 生成：口令每次启动都会变，手拼地址 + `?t=` 最容易漏字符；
 * 页面上直接给一段能整段复制的 JSON，粘到 Claude Desktop / Cursor 的配置里就能用。
 *
 * 路径只能留占位符——MCP server 跑在电脑上，手机不知道它装在哪。
 * 这里故意用中文尖括号，忘了改的话客户端会明确报「找不到文件」，比一个像模像样的假路径好排查。
 */
object LanEditorMcpConfig {

    const val PATH_PLACEHOLDER = "<换成电脑上 tools/qbank-mcp/dist/index.js 的绝对路径>"

    /** 服务没启动时给一份带占位地址的样例，让用户先看清结构 */
    const val SAMPLE_URL = "http://<手机IP>:8777/?t=<口令>"

    fun json(pageUrl: String): String {
        val url = pageUrl.trim().ifEmpty { SAMPLE_URL }
        return buildString {
            appendLine("{")
            appendLine("  \"mcpServers\": {")
            appendLine("    \"fenbi-qbank\": {")
            appendLine("      \"command\": \"node\",")
            appendLine("      \"args\": [\"$PATH_PLACEHOLDER\"],")
            appendLine("      \"env\": { \"QBANK_URL\": \"$url\" }")
            appendLine("    }")
            appendLine("  }")
            append("}")
        }
    }
}
