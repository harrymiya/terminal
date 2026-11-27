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
    val voiceSummary: String? = null,
    val expectsFollowUp: Boolean = false
)

interface JarvisMcpTool {
    val definition: McpToolDefinition
    suspend fun execute(arguments: JSONObject): McpToolResult
}

object McpKeyMap {
    private val BASE_KEYS: Map<String, String> = mapOf(
        "enter" to "\r",
        "return" to "\r",
        "cr" to "\r",
        "tab" to "\t",
        "shift+tab" to "\u001b[Z",
        "backtab" to "\u001b[Z",
        "escape" to "\u001b",
        "esc" to "\u001b",
        "backspace" to "\u007f",
        "bs" to "\u007f",
        "delete" to "\u001b[3~",
        "del" to "\u001b[3~",
        "insert" to "\u001b[2~",
        "ins" to "\u001b[2~",
        "space" to " ",
        "up" to "\u001b[A",
        "down" to "\u001b[B",
        "right" to "\u001b[C",
        "left" to "\u001b[D",
        "home" to "\u001b[H",
        "end" to "\u001b[F",
        "pageup" to "\u001b[5~",
        "page_up" to "\u001b[5~",
        "pgup" to "\u001b[5~",
        "pagedown" to "\u001b[6~",
        "page_down" to "\u001b[6~",
        "pgdn" to "\u001b[6~",
        "f1" to "\u001bOP",
        "f2" to "\u001bOQ",
        "f3" to "\u001bOR",
        "f4" to "\u001bOS",
        "f5" to "\u001b[15~",
        "f6" to "\u001b[17~",
        "f7" to "\u001b[18~",
        "f8" to "\u001b[19~",
        "f9" to "\u001b[20~",
        "f10" to "\u001b[21~",
        "f11" to "\u001b[23~",
        "f12" to "\u001b[24~"
    )

    private val ARROW_SUFFIX = mapOf("up" to 'A', "down" to 'B', "right" to 'C', "left" to 'D', "home" to 'H', "end" to 'F')

    /**
     * 完整解析单键及任意组合键（支持 ctrl+c、alt+m、meta+k、shift+tab、ctrl+alt+x、alt+enter、C-c、M-m 等终端转义序列）。
     */
    fun resolveKey(rawKey: String): String? {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) return null
        val normalized = trimmed.lowercase()
        BASE_KEYS[normalized]?.let { return it }

        val parts = if (normalized.contains('+')) {
            normalized.split('+')
        } else if (normalized.contains('-') && !normalized.startsWith("-")) {
            normalized.split('-')
        } else {
            return null
        }.map { it.trim() }.filter { it.isNotEmpty() }

        if (parts.isEmpty()) return null
        if (parts.size == 1) return BASE_KEYS[parts[0]]

        var ctrl = false
        var alt = false
        var shift = false
        val keyPart = parts.last()
        for (mod in parts.dropLast(1)) {
            when (mod) {
                "ctrl", "control", "c" -> ctrl = true
                "alt", "meta", "opt", "option", "a", "m" -> alt = true
                "shift", "s" -> shift = true
                else -> return null
            }
        }

        // 处理方向键与 Home/End 修饰组合（xterm modifier code = 1 + shift(1) + alt(2) + ctrl(4)）
        ARROW_SUFFIX[keyPart]?.let { dir ->
            val modMask = (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
            return if (modMask == 0) "\u001b[$dir" else "\u001b[1;${1 + modMask}$dir"
        }

        var seq: String? = null
        if (ctrl) {
            seq = when {
                keyPart.length == 1 && keyPart[0] in 'a'..'z' -> ((keyPart[0] - 'a') + 1).toChar().toString()
                keyPart == "[" -> "\u001b"
                keyPart == "\\" -> "\u001c"
                keyPart == "]" -> "\u001d"
                keyPart == "^" || keyPart == "6" -> "\u001e"
                keyPart == "_" || keyPart == "-" -> "\u001f"
                keyPart == "@" || keyPart == "space" || keyPart == "2" -> "\u0000"
                keyPart == "backspace" || keyPart == "bs" -> "\u0008"
                keyPart == "enter" || keyPart == "return" -> "\n"
                else -> null
            }
        } else if (keyPart in BASE_KEYS) {
            seq = if (keyPart == "tab" && shift) "\u001b[Z" else BASE_KEYS[keyPart]
        } else if (keyPart.length == 1) {
            val ch = keyPart[0]
            seq = (if (shift && ch in 'a'..'z') ch.uppercaseChar() else ch).toString()
        }

        if (seq == null) return null
        return if (alt && !seq.startsWith("\u001b")) "\u001b$seq"
        else if (alt && keyPart in setOf("enter", "return", "backspace", "bs", "tab", "space")) "\u001b$seq"
        else seq
    }
}
