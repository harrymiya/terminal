package dev.remote.terminal.jarvis.mcp

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一的终端会话/工作区实例选择器。
 * 支持按 session_id / session_index / workspace_name / workspace_path / host_name / agent_name / query 灵活匹配。
 */
data class SessionSelector(
    val sessionId: String? = null,
    val sessionIndex: Int? = null,
    val workspaceName: String? = null,
    val workspacePath: String? = null,
    val hostName: String? = null,
    val agentName: String? = null,
    val query: String? = null
) {
    fun hasAnyFilter(): Boolean = listOfNotNull(
        sessionId, workspaceName, workspacePath, hostName, agentName, query
    ).any { it.isNotBlank() } || sessionIndex != null

    companion object {
        private fun optNonBlank(obj: JSONObject, vararg keys: String): String? {
            for (k in keys) {
                val v = obj.optString(k, "").trim()
                if (v.isNotBlank() && !v.equals("null", ignoreCase = true)) return v
            }
            return null
        }

        private fun optIntOrNull(obj: JSONObject, vararg keys: String): Int? {
            for (k in keys) {
                if (!obj.has(k) || obj.isNull(k)) continue
                val raw = obj.opt(k)
                when (raw) {
                    is Number -> return raw.toInt()
                    is String -> raw.trim().toIntOrNull()?.let { return it }
                }
            }
            return null
        }

        fun fromJson(arguments: JSONObject): SessionSelector = SessionSelector(
            sessionId = optNonBlank(arguments, "session_id", "id"),
            sessionIndex = optIntOrNull(arguments, "session_index", "index"),
            workspaceName = optNonBlank(arguments, "workspace_name", "directory_name", "workspace", "dir_name"),
            workspacePath = optNonBlank(arguments, "workspace_path", "directory_path", "path"),
            hostName = optNonBlank(arguments, "host_name", "host", "host_id"),
            agentName = optNonBlank(arguments, "agent_name", "agent", "agent_command"),
            query = optNonBlank(arguments, "target", "name", "display_name", "query")
        )
    }
}

/** 终端/工作区操作返回结果（含标准化细节、语音摘要及是否需要二次回传给模型）。 */
data class ToolOpResult(
    val ok: Boolean,
    val detail: String,
    val voiceSummary: String,
    val expectsFollowUp: Boolean = false
)

/**
 * 移动端 MCP 工具注册表 (Jarvis Tool Registry)。
 * 为本地 Jarvis 语音与 AI 开发者提供全量、带同步等待与退出码回传的 Remote Dev 工作区及应用操作接口。
 */
object JarvisToolRegistry {

    private const val TAG = "JarvisToolRegistry"
    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private val tools = ConcurrentHashMap<String, JarvisMcpTool>()

    // 外部由 MainActivity / Terminal 注入的桥接委托 (Bridge Delegates)
    var appStateProvider: (() -> JSONObject)? = null
    var selectHostHandler: ((hostId: String?, hostIndex: Int?, hostName: String?) -> Boolean)? = null
    var selectAgentHandler: ((agentId: String?, agentName: String?) -> Boolean)? = null
    var openTerminalHandler: ((
        hostId: String?,
        hostName: String?,
        workspacePath: String?,
        workspaceName: String?,
        agentCommand: String?,
        agentName: String?,
        reuseExisting: Boolean,
        initialCommand: String?
    ) -> ToolOpResult)? = null
    var runCommandHandler: (suspend (
        selector: SessionSelector,
        command: String,
        timeoutMs: Long,
        workingDir: String?,
        mode: String
    ) -> ToolOpResult)? = null
    var runInWorkspaceHandler: (suspend (
        selector: SessionSelector,
        text: String,
        pressEnter: Boolean,
        agentName: String?,
        sync: Boolean,
        timeoutMs: Long,
        mode: String?
    ) -> ToolOpResult)? = null
    var focusTerminalHandler: ((selector: SessionSelector) -> ToolOpResult)? = null
    var closeTerminalHandler: ((selector: SessionSelector) -> ToolOpResult)? = null
    var reconnectTerminalHandler: ((selector: SessionSelector) -> ToolOpResult)? = null
    var renameTerminalHandler: ((selector: SessionSelector, newName: String) -> ToolOpResult)? = null
    var scrollTerminalHandler: ((selector: SessionSelector, direction: String) -> ToolOpResult)? = null
    var confirmTerminalHandler: ((selector: SessionSelector, approve: Boolean) -> ToolOpResult)? = null
    var sendTextHandler: (suspend (
        text: String,
        selector: SessionSelector,
        pressEnter: Boolean,
        sync: Boolean,
        timeoutMs: Long,
        mode: String?
    ) -> ToolOpResult)? = null
    var sendKeysHandler: ((keys: List<String>, selector: SessionSelector) -> ToolOpResult)? = null
    var readScreenHandler: ((selector: SessionSelector, lines: Int) -> Pair<Boolean, String>)? = null
    var setViewModeHandler: ((tiled: Boolean) -> Boolean)? = null
    var autoConfirmHandler: ((enabled: Boolean?, delaySeconds: Int?) -> ToolOpResult)? = null
    var triggerSyncHandler: (() -> Unit)? = null
    var publicKeyProvider: (() -> String?)? = null
    var addWorkspaceHandler: ((path: String, name: String, hostId: String?, hostName: String?) -> Boolean)? = null
    var deleteWorkspaceHandler: ((path: String, hostId: String?, hostName: String?) -> Boolean)? = null
    var vibrateHandler: ((durationMs: Long?) -> Unit)? = null
    var deviceStateProvider: (() -> JSONObject)? = null
    var navigateScreenHandler: ((screen: String) -> Boolean)? = null

    init {
        registerBuiltinMcpTools()
    }

    private fun sessionSelectorProperties(): JSONObject = JSONObject().apply {
        put("session_id", JSONObject().put("type", "string").put("description", "会话 ID（也支持短前缀）"))
        put("session_index", JSONObject().put("type", "integer").put("description", "已打开终端列表索引（从 0 开始）"))
        put("workspace_name", JSONObject().put("type", "string").put("description", "工作区目录名称（如 remote-dev-terminal）"))
        put("workspace_path", JSONObject().put("type", "string").put("description", "工作区绝对路径"))
        put("host_name", JSONObject().put("type", "string").put("description", "所属主机名或 ID"))
        put("agent_name", JSONObject().put("type", "string").put("description", "所属开发者名（如 Claude / Codex / agy2）"))
    }

    private fun extractSyncFlag(arguments: JSONObject, defaultValue: Boolean): Boolean {
        if (arguments.has("sync") && !arguments.isNull("sync")) return arguments.optBoolean("sync", defaultValue)
        if (arguments.has("wait_for_output") && !arguments.isNull("wait_for_output")) return arguments.optBoolean("wait_for_output", defaultValue)
        if (arguments.has("wait_for_completion") && !arguments.isNull("wait_for_completion")) return arguments.optBoolean("wait_for_completion", defaultValue)
        return defaultValue
    }

    private fun extractTimeoutMs(arguments: JSONObject): Long {
        val raw = if (arguments.has("timeout_ms") && !arguments.isNull("timeout_ms")) {
            arguments.optLong("timeout_ms", DEFAULT_TIMEOUT_MS)
        } else {
            DEFAULT_TIMEOUT_MS
        }
        return raw.coerceIn(1_000L, 180_000L)
    }

    private fun registerBuiltinMcpTools() {
        // 1. run_terminal_command (同步等待执行并返回标准化 stdout / stderr / exit_code / duration_ms / timed_out)
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "run_terminal_command",
                description = "在指定工作区同步执行 Shell 命令并等待进程结束，直接返回包含 exit_code、stdout、stderr、duration_ms、timed_out 的标准化 JSON 结果（默认超时 30000ms）。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties().apply {
                        put("command", JSONObject().put("type", "string").put("description", "要同步执行的命令"))
                        put("timeout_ms", JSONObject().put("type", "integer").put("description", "执行超时毫秒数，默认 30000，范围 1000~180000"))
                        put("working_dir", JSONObject().put("type", "string").put("description", "可选：覆盖工作区执行目录"))
                        put("mode", JSONObject().put("type", "string").put("description", "ssh_exec（默认，独立 SSH exec 通道获取精准退出码与输出）或 pty（通过哨兵在可见 PTY 内执行）"))
                    })
                    put("required", JSONArray().put("command"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val cmd = arguments.optString("command", "").ifBlank { arguments.optString("text", "") }.trim()
                if (cmd.isBlank()) {
                    return McpToolResult(isError = true, content = "{\"ok\":false,\"exit_code\":-1,\"error\":\"Missing command\"}", voiceSummary = "未指定要执行的命令。")
                }
                val selector = SessionSelector.fromJson(arguments)
                val timeoutMs = extractTimeoutMs(arguments)
                val workingDir = arguments.optString("working_dir", "").takeIf { it.isNotBlank() }
                val mode = arguments.optString("mode", "ssh_exec").trim().lowercase().ifBlank { "ssh_exec" }
                val res = runCommandHandler?.invoke(selector, cmd, timeoutMs, workingDir, mode)
                    ?: ToolOpResult(false, "{\"ok\":false,\"exit_code\":-1,\"error\":\"runCommandHandler unavailable\"}", "同步执行接口不可用。")
                return McpToolResult(
                    isError = !res.ok,
                    content = res.detail,
                    voiceSummary = res.voiceSummary,
                    expectsFollowUp = true
                )
            }
        })

        // 2. get_app_state
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "get_app_state",
                description = "获取应用当前完整状态：当前页面、所有已打开的工作区终端实例（含索引、工作区名、主机、开发者、连接状态、末行输出）、全部主机与工作区列表、开发者预设。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val state = appStateProvider?.invoke() ?: JSONObject()
                val sessionCount = state.optJSONArray("sessions")?.length() ?: 0
                val hostCount = state.optJSONArray("hosts")?.length() ?: 0
                return McpToolResult(
                    content = state.toString(2),
                    voiceSummary = "当前共 ${hostCount} 台主机，${sessionCount} 个打开的工作区终端实例。",
                    expectsFollowUp = true
                )
            }
        })

        // 3. list_open_workspaces
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "list_open_workspaces",
                description = "快速列出当前所有已打开的工作区终端实例及其连接状态与最新一行输出。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val state = appStateProvider?.invoke() ?: JSONObject()
                val arr = state.optJSONArray("sessions") ?: JSONArray()
                if (arr.length() == 0) {
                    return McpToolResult(content = "[]", voiceSummary = "当前没有打开任何工作区终端。", expectsFollowUp = true)
                }
                val names = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val ws = obj.optString("workspace").ifBlank { obj.optString("display_name") }
                    val agent = obj.optString("agent")
                    val conn = if (obj.optBoolean("connected")) "在线" else "重连/断开"
                    names.add("${i}号 ${ws}(${agent},$conn)")
                }
                return McpToolResult(
                    content = arr.toString(2),
                    voiceSummary = "当前已打开 ${arr.length()} 个工作区实例：${names.joinToString("、")}。",
                    expectsFollowUp = true
                )
            }
        })

        // 4. select_host
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "select_host",
                description = "切换当前选中的主机并刷新工作区列表。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("host_id", JSONObject().put("type", "string"))
                        put("host_name", JSONObject().put("type", "string"))
                        put("host_index", JSONObject().put("type", "integer"))
                    })
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val id = arguments.optString("host_id").takeIf { it.isNotBlank() }
                val name = arguments.optString("host_name").ifBlank { arguments.optString("host") }.takeIf { it.isNotBlank() }
                val index = if (arguments.has("host_index")) arguments.optInt("host_index") else null
                val ok = selectHostHandler?.invoke(id, index, name) ?: false
                return if (ok) {
                    McpToolResult(content = "Host selected successfully", voiceSummary = "已为您切换至主机 ${name ?: id ?: index}。")
                } else {
                    McpToolResult(isError = true, content = "Host not found", voiceSummary = "未找到指定主机。")
                }
            }
        })

        // 5. select_agent
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "select_agent",
                description = "切换当前主机的默认 AI 开发者预设（如 Codex、Claude、Antigravity、Shell 等）。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("agent_id", JSONObject().put("type", "string"))
                        put("agent_name", JSONObject().put("type", "string"))
                    })
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val id = arguments.optString("agent_id").takeIf { it.isNotBlank() }
                val name = arguments.optString("agent_name").ifBlank { arguments.optString("agent") }.takeIf { it.isNotBlank() }
                val ok = selectAgentHandler?.invoke(id, name) ?: false
                return if (ok) {
                    McpToolResult(content = "Agent selected successfully", voiceSummary = "已切换为 ${name ?: id} 预设。")
                } else {
                    McpToolResult(isError = true, content = "Agent not found", voiceSummary = "未找到指定开发者预设。")
                }
            }
        })

        // 6. open_terminal
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "open_terminal",
                description = "打开指定主机+工作区的终端实例（默认优先复用并聚焦已打开的同工作区会话，可选附带 initial_command 立即执行命令）。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("host_id", JSONObject().put("type", "string"))
                        put("host_name", JSONObject().put("type", "string"))
                        put("workspace_path", JSONObject().put("type", "string"))
                        put("workspace_name", JSONObject().put("type", "string"))
                        put("agent_command", JSONObject().put("type", "string"))
                        put("agent_name", JSONObject().put("type", "string"))
                        put("reuse_existing", JSONObject().put("type", "boolean").put("description", "默认 true：若工作区已打开则直接聚焦复用"))
                        put("initial_command", JSONObject().put("type", "string").put("description", "可选：打开或聚焦后立即发送执行的命令"))
                    })
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val hostId = arguments.optString("host_id").takeIf { it.isNotBlank() }
                val hostName = arguments.optString("host_name").ifBlank { arguments.optString("host") }.takeIf { it.isNotBlank() }
                val wsPath = arguments.optString("workspace_path").ifBlank { arguments.optString("directory_path") }.takeIf { it.isNotBlank() }
                val wsName = arguments.optString("workspace_name").ifBlank {
                    arguments.optString("directory_name").ifBlank { arguments.optString("workspace") }
                }.takeIf { it.isNotBlank() }
                val cmd = arguments.optString("agent_command").takeIf { it.isNotBlank() }
                val agentName = arguments.optString("agent_name").ifBlank { arguments.optString("agent") }.takeIf { it.isNotBlank() }
                val reuseExisting = arguments.optBoolean("reuse_existing", true)
                val initialCommand = arguments.optString("initial_command", "").takeIf { it.isNotBlank() }
                val res = openTerminalHandler?.invoke(hostId, hostName, wsPath, wsName, cmd, agentName, reuseExisting, initialCommand)
                    ?: ToolOpResult(false, "openTerminalHandler unavailable", "打开终端不可用。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary, expectsFollowUp = res.expectsFollowUp)
            }
        })

        // 7. run_in_workspace
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "run_in_workspace",
                description = "在指定工作区执行命令或发送文本；支持 sync=true（默认）同步等待执行结束并返回标准化 JSON（含 exit_code、stdout、duration_ms、timed_out），也支持 timeout_ms 与 mode(ssh_exec/pty)。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties().apply {
                        put("text", JSONObject().put("type", "string").put("description", "要发送执行的命令或对话文本"))
                        put("press_enter", JSONObject().put("type", "boolean").put("description", "是否自动回车提交，默认 true"))
                        put("sync", JSONObject().put("type", "boolean").put("description", "是否同步等待命令完成并返回输出与退出码，默认 true"))
                        put("timeout_ms", JSONObject().put("type", "integer").put("description", "同步等待超时毫秒数，默认 30000"))
                        put("mode", JSONObject().put("type", "string").put("description", "可选：ssh_exec 或 pty"))
                    })
                    put("required", JSONArray().put("text"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val selector = SessionSelector.fromJson(arguments)
                val text = arguments.optString("text", "").ifBlank { arguments.optString("command", "") }
                val enter = arguments.optBoolean("press_enter", true)
                val sync = extractSyncFlag(arguments, defaultValue = true)
                val timeoutMs = extractTimeoutMs(arguments)
                val mode = arguments.optString("mode", "").trim().lowercase().takeIf { it.isNotBlank() }
                if (text.isBlank()) {
                    return McpToolResult(isError = true, content = "Missing text/command", voiceSummary = "未指定要执行的命令内容。")
                }
                val res = runInWorkspaceHandler?.invoke(selector, text, enter, selector.agentName, sync, timeoutMs, mode)
                    ?: ToolOpResult(false, "runInWorkspaceHandler unavailable", "执行工作区命令不可用。")
                return McpToolResult(
                    isError = !res.ok,
                    content = res.detail,
                    voiceSummary = res.voiceSummary,
                    expectsFollowUp = res.expectsFollowUp
                )
            }
        })

        // 8. focus_terminal
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "focus_terminal",
                description = "切换并聚焦到指定的已打开工作区终端标签页。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties())
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val res = focusTerminalHandler?.invoke(SessionSelector.fromJson(arguments))
                    ?: ToolOpResult(false, "focusTerminalHandler unavailable", "未找到会话。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary)
            }
        })

        // 9. close_terminal
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "close_terminal",
                description = "关闭指定的已打开工作区终端会话并断开 SSH 连接。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties())
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val res = closeTerminalHandler?.invoke(SessionSelector.fromJson(arguments))
                    ?: ToolOpResult(false, "closeTerminalHandler unavailable", "未能关闭终端。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary)
            }
        })

        // 10. reconnect_terminal
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "reconnect_terminal",
                description = "重新连接指定或当前的已打开工作区终端会话。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties())
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val res = reconnectTerminalHandler?.invoke(SessionSelector.fromJson(arguments))
                    ?: ToolOpResult(false, "reconnectTerminalHandler unavailable", "重连终端失败。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary)
            }
        })

        // 11. rename_terminal
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "rename_terminal",
                description = "重命名指定的已打开工作区终端标签页。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties().apply {
                        put("name", JSONObject().put("type", "string").put("description", "新的会话显示名称"))
                    })
                    put("required", JSONArray().put("name"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val newName = arguments.optString("new_name", "").ifBlank { arguments.optString("name", "") }.trim()
                if (newName.isBlank()) {
                    return McpToolResult(isError = true, content = "Missing name", voiceSummary = "未提供新的会话名称。")
                }
                val res = renameTerminalHandler?.invoke(SessionSelector.fromJson(arguments), newName)
                    ?: ToolOpResult(false, "renameTerminalHandler unavailable", "重命名失败。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary)
            }
        })

        // 12. scroll_terminal
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "scroll_terminal",
                description = "在指定终端工作区向上/向下翻页或滚回底部 (direction: up | down | bottom)。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties().apply {
                        put("direction", JSONObject().put("type", "string").put("description", "up / down / bottom"))
                    })
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val dir = arguments.optString("direction", "up").trim().lowercase()
                val res = scrollTerminalHandler?.invoke(SessionSelector.fromJson(arguments), dir)
                    ?: ToolOpResult(false, "scrollTerminalHandler unavailable", "滚动终端失败。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary)
            }
        })

        // 13. confirm_terminal
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "confirm_terminal",
                description = "响应指定工作区终端的待确认提示（approve=true 发送回车批准，false 发送 Ctrl+C 取消）。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties().apply {
                        put("approve", JSONObject().put("type", "boolean").put("description", "true 确认回车，false 取消"))
                    })
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val approve = arguments.optBoolean("approve", true)
                val res = confirmTerminalHandler?.invoke(SessionSelector.fromJson(arguments), approve)
                    ?: ToolOpResult(false, "confirmTerminalHandler unavailable", "终端确认操作失败。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary)
            }
        })

        // 14. send_terminal_text
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "send_terminal_text",
                description = "向已打开的工作区终端实例发送文本或命令；支持 sync=true 同步等待执行完成并返回 stdout、exit_code、duration_ms、timed_out（默认 timeout_ms=30000）。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties().apply {
                        put("text", JSONObject().put("type", "string").put("description", "要发送的文本或命令"))
                        put("press_enter", JSONObject().put("type", "boolean").put("description", "发送后是否自动按回车，默认 true"))
                        put("sync", JSONObject().put("type", "boolean").put("description", "是否同步等待命令结束并返回 stdout 与退出码，默认 false；当希望拿到返回值时请设 true 或使用 run_terminal_command"))
                        put("timeout_ms", JSONObject().put("type", "integer").put("description", "同步等待超时毫秒数，默认 30000"))
                        put("mode", JSONObject().put("type", "string").put("description", "可选：ssh_exec 或 pty"))
                    })
                    put("required", JSONArray().put("text"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val text = arguments.optString("text", "").ifBlank { arguments.optString("command", "") }
                if (text.isBlank()) {
                    return McpToolResult(isError = true, content = "Empty text", voiceSummary = "发送的指令文本为空。")
                }
                val enter = arguments.optBoolean("press_enter", true)
                val sync = extractSyncFlag(arguments, defaultValue = false) && enter
                val timeoutMs = extractTimeoutMs(arguments)
                val mode = arguments.optString("mode", "").trim().lowercase().takeIf { it.isNotBlank() }
                val res = sendTextHandler?.invoke(text, SessionSelector.fromJson(arguments), enter, sync, timeoutMs, mode)
                    ?: ToolOpResult(false, "sendTextHandler unavailable", "指令发送失败。")
                return McpToolResult(
                    isError = !res.ok,
                    content = res.detail,
                    voiceSummary = res.voiceSummary,
                    expectsFollowUp = res.expectsFollowUp
                )
            }
        })

        // 15. send_terminal_keys
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "send_terminal_keys",
                description = "向指定已打开工作区终端发送按键或快捷键（如 enter, tab, escape, ctrl+c, ctrl+d, up, down, left, right）。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties().apply {
                        put("keys", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                        put("key", JSONObject().put("type", "string"))
                    })
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val keys = mutableListOf<String>()
                if (arguments.has("keys")) {
                    val arr = arguments.optJSONArray("keys")
                    if (arr != null) {
                        for (i in 0 until arr.length()) keys.add(arr.getString(i))
                    } else {
                        val single = arguments.optString("keys").trim()
                        if (single.isNotBlank()) keys.add(single)
                    }
                } else if (arguments.has("key")) {
                    val single = arguments.optString("key").trim()
                    if (single.isNotBlank()) keys.add(single)
                }
                if (keys.isEmpty()) {
                    return McpToolResult(isError = true, content = "No keys specified", voiceSummary = "未指定要发送的按键。")
                }
                val res = sendKeysHandler?.invoke(keys, SessionSelector.fromJson(arguments))
                    ?: ToolOpResult(false, "sendKeysHandler unavailable", "按键注入失败。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary)
            }
        })

        // 16. read_terminal_screen
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "read_terminal_screen",
                description = "读取指定已打开工作区终端的当前可见屏幕输出（最后 N 行）。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties().apply {
                        put("lines", JSONObject().put("type", "integer").put("description", "读取末尾行数，默认 25，最大 100"))
                    })
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val lines = arguments.optInt("lines", 25)
                val pair = readScreenHandler?.invoke(SessionSelector.fromJson(arguments), lines)
                    ?: (false to "终端无输出")
                if (!pair.first) {
                    return McpToolResult(isError = true, content = pair.second, voiceSummary = pair.second.take(60), expectsFollowUp = true)
                }
                val screen = pair.second
                val lastLine = screen.lines().lastOrNull { it.isNotBlank() } ?: "空行"
                return McpToolResult(
                    content = screen,
                    voiceSummary = "屏幕最新输出为：${lastLine.take(40)}",
                    expectsFollowUp = true
                )
            }
        })

        // 17. set_view_mode
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "set_view_mode",
                description = "切换终端平铺分屏模式 (tiled=true) 或单页标签模式 (tiled=false)。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("tiled", JSONObject().put("type", "boolean"))
                    })
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val tiled = arguments.optBoolean("tiled", true)
                val ok = setViewModeHandler?.invoke(tiled) ?: false
                return if (ok) {
                    McpToolResult(content = "View mode set to tiled=$tiled", voiceSummary = "已切换为${if (tiled) "平铺" else "标签"}模式。")
                } else {
                    McpToolResult(isError = true, content = "Failed to set view mode", voiceSummary = "切换布局模式失败。")
                }
            }
        })

        // 18. set_auto_confirm
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "set_auto_confirm",
                description = "开启/关闭或调整后台自动确认（自动回车）开关与延迟秒数。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("enabled", JSONObject().put("type", "boolean"))
                        put("delay_seconds", JSONObject().put("type", "integer"))
                    })
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val enabled = if (arguments.has("enabled") && !arguments.isNull("enabled")) arguments.optBoolean("enabled") else null
                val delay = if (arguments.has("delay_seconds") && !arguments.isNull("delay_seconds")) arguments.optInt("delay_seconds") else null
                val res = autoConfirmHandler?.invoke(enabled, delay)
                    ?: ToolOpResult(false, "autoConfirmHandler unavailable", "调整自动确认失败。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary)
            }
        })

        // 19. trigger_sync
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "trigger_sync",
                description = "立即触发一次主机、工作区与共享会话云端同步。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                triggerSyncHandler?.invoke()
                return McpToolResult(content = "Sync triggered", voiceSummary = "已为您触发云端同步。")
            }
        })

        // 20. get_ssh_public_key
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "get_ssh_public_key",
                description = "获取本机生成的 SSH 公钥。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val key = publicKeyProvider?.invoke()
                return if (key != null) {
                    McpToolResult(content = key, voiceSummary = "本机 SSH 公钥已读取完毕。", expectsFollowUp = true)
                } else {
                    McpToolResult(isError = true, content = "No public key found", voiceSummary = "未找到公钥，请在设置中生成。")
                }
            }
        })

        // 21. add_workspace
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "add_workspace",
                description = "为指定主机添加开发目录工作区。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().put("type", "string"))
                        put("name", JSONObject().put("type", "string"))
                        put("host_id", JSONObject().put("type", "string"))
                        put("host_name", JSONObject().put("type", "string"))
                    })
                    put("required", JSONArray().put("path"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val path = arguments.optString("path", "").trim()
                val name = arguments.optString("name", path.substringAfterLast('/')).trim().ifBlank { path }
                val hostId = arguments.optString("host_id").takeIf { it.isNotBlank() }
                val hostName = arguments.optString("host_name").ifBlank { arguments.optString("host") }.takeIf { it.isNotBlank() }
                val ok = if (path.isNotBlank()) addWorkspaceHandler?.invoke(path, name, hostId, hostName) ?: false else false
                return if (ok) {
                    McpToolResult(content = "Workspace added: $name ($path)", voiceSummary = "已成功添加工作区 $name。")
                } else {
                    McpToolResult(isError = true, content = "Failed to add workspace", voiceSummary = "添加工作区失败。")
                }
            }
        })

        // 22. delete_workspace
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "delete_workspace",
                description = "移除主机上的开发目录工作区。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().put("type", "string"))
                        put("host_id", JSONObject().put("type", "string"))
                        put("host_name", JSONObject().put("type", "string"))
                    })
                    put("required", JSONArray().put("path"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val path = arguments.optString("path", "").trim()
                val hostId = arguments.optString("host_id").takeIf { it.isNotBlank() }
                val hostName = arguments.optString("host_name").ifBlank { arguments.optString("host") }.takeIf { it.isNotBlank() }
                val ok = if (path.isNotBlank()) deleteWorkspaceHandler?.invoke(path, hostId, hostName) ?: false else false
                return if (ok) {
                    McpToolResult(content = "Workspace deleted", voiceSummary = "已为您移除工作区。")
                } else {
                    McpToolResult(isError = true, content = "Failed to delete workspace", voiceSummary = "移除工作区失败。")
                }
            }
        })

        // 23. chat_with_agent
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "chat_with_agent",
                description = "向指定已打开工作区中的 AI 开发者发送文本指令，并支持同步等待回显。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties().apply {
                        put("message", JSONObject().put("type", "string"))
                        put("sync", JSONObject().put("type", "boolean").put("description", "是否同步等待响应"))
                        put("timeout_ms", JSONObject().put("type", "integer"))
                    })
                    put("required", JSONArray().put("message"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val msg = arguments.optString("message", "").ifBlank { arguments.optString("text", "") }
                if (msg.isBlank()) {
                    return McpToolResult(isError = true, content = "Empty message", voiceSummary = "发送内容为空。")
                }
                val sync = extractSyncFlag(arguments, defaultValue = false)
                val timeoutMs = extractTimeoutMs(arguments)
                val res = sendTextHandler?.invoke(msg, SessionSelector.fromJson(arguments), true, sync, timeoutMs, "pty")
                    ?: ToolOpResult(false, "sendTextHandler unavailable", "向开发者发送失败。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary, expectsFollowUp = res.expectsFollowUp)
            }
        })

        // 24. speak_to_user
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "speak_to_user",
                description = "通过手机蓝牙耳机/扬声器直接向用户语音播报消息 (TTS)。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("message", JSONObject().put("type", "string"))
                    })
                    put("required", JSONArray().put("message"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val msg = arguments.optString("message", "")
                return if (msg.isNotBlank()) {
                    dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.speakWithState(msg)
                    McpToolResult(content = "Spoke: $msg", voiceSummary = "已向用户耳语播报。")
                } else {
                    McpToolResult(isError = true, content = "Empty message", voiceSummary = "播报内容为空。")
                }
            }
        })

        // 25. vibrate_phone
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "vibrate_phone",
                description = "触发手机震动提醒用户。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("duration_ms", JSONObject().put("type", "integer"))
                    })
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val dur = arguments.optLong("duration_ms", 350L)
                vibrateHandler?.invoke(dur)
                return McpToolResult(content = "Vibrated for ${dur}ms", voiceSummary = "已触发手机震动提醒。")
            }
        })

        // 26. get_device_state
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "get_device_state",
                description = "获取手机端硬件及系统状态（电量、充电状态、会话数）。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val state = deviceStateProvider?.invoke() ?: JSONObject()
                return McpToolResult(
                    content = state.toString(2),
                    voiceSummary = "当前手机电量为 ${state.optInt("battery_level", -1)}%。",
                    expectsFollowUp = true
                )
            }
        })

        // 27. navigate_screen
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "navigate_screen",
                description = "切换 Remote Dev 手机端页面（home / terminal / settings / manage / agents / logs）。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("screen", JSONObject().put("type", "string"))
                    })
                    put("required", JSONArray().put("screen"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val screen = arguments.optString("screen", "home").trim().lowercase()
                val ok = navigateScreenHandler?.invoke(screen) ?: false
                return if (ok) {
                    McpToolResult(content = "Navigated to $screen", voiceSummary = "已为您切换至 $screen 页面。")
                } else {
                    McpToolResult(isError = true, content = "Unknown or unavailable screen: $screen", voiceSummary = "无法切换至该页面。")
                }
            }
        })
    }

    fun register(tool: JarvisMcpTool) {
        tools[tool.definition.name] = tool
    }

    fun getTool(name: String): JarvisMcpTool? = tools[name]

    fun getAllDefinitions(): List<McpToolDefinition> = tools.values.map { it.definition }.sortedBy { it.name }

    /**
     * 导出所有已注册 Android MCP 工具的结构化定义（含完整 inputSchema 参数定义），
     * 用于直接作为上下文发送给需要执行操作的 JARVIS / LLM。
     */
    fun buildMcpToolsSchemaJson(): JSONArray = JSONArray().apply {
        getAllDefinitions().forEach { def ->
            put(JSONObject().apply {
                put("name", def.name)
                put("description", def.description)
                put("inputSchema", def.inputSchema)
            })
        }
    }

    /**
     * 动态从实时注册表生成 Android MCP 可操作能力与调用协议说明文本。
     * 确保需要执行操作的 JARVIS 在首轮与多轮对话中始终掌握最新、全量的工作区同步执行与端侧操作方法。
     */
    fun buildMcpProtocolManual(compact: Boolean = false): String {
        val defs = getAllDefinitions()
        val lines = StringBuilder()
        if (!compact) {
            lines.appendLine("【Remote Dev 安卓端 MCP 协议与同步执行接口手册（共 ${defs.size} 项）】")
            lines.appendLine("调用语法：在回复正文或末尾附加一行或多行 [[RDT_MCP:{\"tool\":\"工具名\",\"arguments\":{...}}]]，系统会按顺序在安卓端真实执行并返回结果。")
            lines.appendLine("同步等待约定：执行需要查看输出或退出码的 Shell 命令请优先调用 run_terminal_command（或在 run_in_workspace / send_terminal_text 传 sync=true, timeout_ms=30000），接口会等待结束并回传包含 exit_code、stdout、stderr、duration_ms、timed_out 的完整 JSON 供你汇总汇报。")
            lines.appendLine("目标工作区定位：先查看状态中的 open_sessions，优先传入 workspace_name、session_index 或 session_id。")
            lines.appendLine("可用接口与参数列表（* 为必填参数）：")
        } else {
            lines.appendLine("【Android MCP 操作协议与全量接口（附 [[RDT_MCP:{\"tool\":\"...\",\"arguments\":{...}}]]；同步命令优先用 run_terminal_command 或 sync=true）】")
        }
        for (def in defs) {
            val props = def.inputSchema.optJSONObject("properties")
            val requiredSet = buildSet {
                val reqArr = def.inputSchema.optJSONArray("required")
                if (reqArr != null) {
                    for (i in 0 until reqArr.length()) add(reqArr.optString(i))
                }
            }
            val paramText = if (props != null && props.length() > 0) {
                val names = props.keys().asSequence().toList()
                names.joinToString(", ") { k ->
                    val type = props.optJSONObject(k)?.optString("type")?.ifBlank { "any" } ?: "any"
                    val star = if (k in requiredSet) "*" else ""
                    "$k:$type$star"
                }
            } else ""
            val desc = if (compact) def.description.take(48) else def.description
            lines.appendLine("- ${def.name}($paramText): $desc")
        }
        return lines.toString().trimEnd()
    }

    suspend fun executeTool(name: String, arguments: JSONObject): McpToolResult {
        val tool = tools[name]
        if (tool == null) {
            Log.w(TAG, "Tool not found: $name")
            return McpToolResult(isError = true, content = "Tool \"$name\" not registered", voiceSummary = "未找到相应 MCP 工具。")
        }
        return try {
            tool.execute(arguments)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing MCP tool $name: ${e.message}", e)
            McpToolResult(isError = true, content = "Exception: ${e.message}", voiceSummary = "执行工具失败。")
        }
    }
}
