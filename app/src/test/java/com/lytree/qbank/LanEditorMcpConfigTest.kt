package com.lytree.qbank

import com.lytree.qbank.ui.laneditor.LanEditorMcpConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 配置片段：给用户整段复制的东西，错一个引号客户端就起不来，所以钉住三件事——
 * 当前地址原样带进去、没启动时给占位地址、括号配对（能直接当 JSON 用）。
 */
class LanEditorMcpConfigTest {

    @Test
    fun json_carries_the_current_url_and_token() {
        val json = LanEditorMcpConfig.json("http://192.168.1.23:8777/?t=abc123")
        assertTrue(json.contains("\"QBANK_URL\": \"http://192.168.1.23:8777/?t=abc123\""))
        assertTrue(json.contains("\"fenbi-qbank\""))
        assertTrue(json.contains("\"command\": \"node\""))
        assertTrue(json.contains(LanEditorMcpConfig.PATH_PLACEHOLDER))
    }

    @Test
    fun empty_url_falls_back_to_a_sample_instead_of_an_empty_string() {
        val json = LanEditorMcpConfig.json("")
        assertTrue(json.contains(LanEditorMcpConfig.SAMPLE_URL))
        assertTrue(!json.contains("\"QBANK_URL\": \"\""))
    }

    @Test
    fun braces_and_brackets_are_balanced() {
        val json = LanEditorMcpConfig.json("http://10.0.0.2:8777/?t=x")
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
        assertTrue(json.trim().startsWith("{"))
        assertTrue(json.trim().endsWith("}"))
        // mcpServers 是客户端认的那一层
        assertTrue(json.contains("\"mcpServers\""))
    }
}
