package dev.remote.terminal.jarvis.mcp

import org.json.JSONObject

/**
 * MCP (Model Context Protocol) 移动端工具定义与执行结果。
 * 保持与 PC 桌面端 (ubuntu / omarchy / windows) 的 27 个标准 MCP 工具一致。
 */
data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }
)

data class McpToolResult(
    val isError: Boolean = false,
    val content: String,
    val voiceSummary: String? = null
)

interface JarvisMcpTool {
    val definition: McpToolDefinition
    suspend fun execute(arguments: JSONObject): McpToolResult
}

object McpKeyMap {
    val KEY_MAP: Map<String, String> = buildMap {
        put("enter", "\r")
        put("return", "\r")
        put("tab", "\t")
        put("shift+tab", "\u001b[Z")
        put("escape", "\u001b")
        put("esc", "\u001b")
        put("backspace", "\u007f")
        put("delete", "\u001b[3~")
        put("space", " ")
        put("up", "\u001b[A")
        put("down", "\u001b[B")
        put("right", "\u001b[C")
        put("left", "\u001b[D")
        put("home", "\u001b[H")
        put("end", "\u001b[F")
        put("pageup", "\u001b[5~")
        put("page_up", "\u001b[5~")
        put("pagedown", "\u001b[6~")
        put("page_down", "\u001b[6~")
        // Ctrl+a ~ Ctrl+z
        for (i in 1..26) {
            val char = ('a'.code + i - 1).toChar()
            put("ctrl+$char", (i).toChar().toString())
        }
    }

    fun resolveKey(key: String): String? {
        val normalized = key.trim().lowercase()
        return KEY_MAP[normalized]
    }
}
