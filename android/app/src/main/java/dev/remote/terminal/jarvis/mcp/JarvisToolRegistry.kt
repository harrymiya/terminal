package dev.remote.terminal.jarvis.mcp

import android.util.Log
import dev.remote.terminal.jarvis.navigation.JarvisScreen
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
            query = optNonBlank(arguments, "target", "title", "task_name", "name", "display_name", "query")
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
    private val developerWorkTools = setOf(
        "run_terminal_command", "run_in_workspace", "send_terminal_text", "send_terminal_keys", "chat_with_agent"
    )

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
        initialCommand: String?,
        focus: Boolean
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
        mode: String?,
        focus: Boolean
    ) -> ToolOpResult)? = null
    var dispatchTaskHandler: (suspend (arguments: JSONObject) -> ToolOpResult)? = null
    var focusTerminalHandler: (suspend (selector: SessionSelector) -> ToolOpResult)? = null
    var closeTerminalHandler: (suspend (selector: SessionSelector) -> ToolOpResult)? = null
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
    var sendKeysHandler: (suspend (keys: List<String>, selector: SessionSelector, sync: Boolean, waitMs: Long) -> ToolOpResult)? = null
    var readScreenHandler: (suspend (selector: SessionSelector, lines: Int) -> Pair<Boolean, String>)? = null
    var setViewModeHandler: ((tiled: Boolean) -> Boolean)? = null
    var autoConfirmHandler: ((enabled: Boolean?, delaySeconds: Int?) -> ToolOpResult)? = null
    var triggerSyncHandler: (() -> Unit)? = null
    var publicKeyProvider: (() -> String?)? = null
    var addWorkspaceHandler: ((path: String, name: String, hostId: String?, hostName: String?) -> Boolean)? = null
    var deleteWorkspaceHandler: ((path: String, hostId: String?, hostName: String?) -> Boolean)? = null
    var vibrateHandler: ((durationMs: Long?) -> Unit)? = null
    var deviceStateProvider: (() -> JSONObject)? = null
    var navigateScreenHandler: ((screen: String) -> Boolean)? = null
    var closeAwakeHandler: ((farewell: String?) -> Unit)? = null
    var taskStatusHandler: (suspend (selector: SessionSelector, lines: Int) -> ToolOpResult)? = null
    var manageHostHandler: (suspend (action: String, args: JSONObject) -> ToolOpResult)? = null
    var manageAgentHandler: ((action: String, args: JSONObject) -> ToolOpResult)? = null
    var manageWorkspaceHandler: (suspend (action: String, args: JSONObject) -> ToolOpResult)? = null
    var manageLogsHandler: (suspend (action: String, args: JSONObject) -> ToolOpResult)? = null
    var updateSettingsHandler: (suspend (args: JSONObject) -> ToolOpResult)? = null
    var sessionWindowHandler: ((selector: SessionSelector, action: String, args: JSONObject) -> ToolOpResult)? = null

    init {
        registerBuiltinMcpTools()
    }

    private fun sessionSelectorProperties(): JSONObject = JSONObject().apply {
        put("session_id", JSONObject().put("type", "string").put("description", "会话 ID（也支持短前缀）"))
        put("session_index", JSONObject().put("type", "integer").put("description", "进行中任务列表索引（从 0 开始）"))
        put("workspace_name", JSONObject().put("type", "string").put("description", "工作区目录名称（如 remote-dev-terminal）"))
        put("workspace_path", JSONObject().put("type", "string").put("description", "工作区绝对路径"))
        put("title", JSONObject().put("type", "string").put("description", "首页卡片展示的任务标题或别名"))
        put("query", JSONObject().put("type", "string").put("description", "实例标题、工作区名称或部分关键词"))
        put("target", JSONObject().put("type", "string").put("description", "实例标题、工作区名称或部分关键词"))
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
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "get_capabilities",
                description = "查看 Jarvis 当前可调用的 Remote Dev 安卓功能、工具用途及参数。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult = McpToolResult(
                content = buildMcpToolsSchemaJson().toString(),
                voiceSummary = buildCapabilitiesSummary()
            )
        })

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
                val sessions = state.optJSONArray("sessions") ?: JSONArray()
                val shared = state.optJSONArray("shared_tasks") ?: JSONArray()
                return McpToolResult(
                    content = state.toString(2),
                    voiceSummary = workspaceStatusSummary(sessions, shared),
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
                return McpToolResult(
                    content = arr.toString(2),
                    voiceSummary = workspaceStatusSummary(arr),
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
                        put("reuse_existing", JSONObject().put("type", "boolean").put("description", "默认 true：若工作区已打开则直接复用"))
                        put("initial_command", JSONObject().put("type", "string").put("description", "可选：打开后立即发送执行的命令"))
                        put("focus", JSONObject().put("type", "boolean").put("description", "是否跳转到终端详情页，默认 true；设为 false 可后台静默打开不离开当前首页"))
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
                val focus = arguments.optBoolean("focus", true)
                val res = openTerminalHandler?.invoke(hostId, hostName, wsPath, wsName, cmd, agentName, reuseExisting, initialCommand, focus)
                    ?: ToolOpResult(false, "openTerminalHandler unavailable", "打开终端不可用。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary, expectsFollowUp = res.expectsFollowUp)
            }
        })

        // 7. run_in_workspace
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "run_in_workspace",
                description = "直接在后台向进行中的工作区任务发送命令并可同步等待返回值，无需跳进终端页（focus 默认 false 保持在首页）；支持 sync=true、timeout_ms 与 mode(ssh_exec/pty)。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties().apply {
                        put("text", JSONObject().put("type", "string").put("description", "要发送执行的命令或对话文本"))
                        put("press_enter", JSONObject().put("type", "boolean").put("description", "是否自动回车提交，默认 true"))
                        put("sync", JSONObject().put("type", "boolean").put("description", "是否同步等待命令完成并返回输出与退出码，默认 true"))
                        put("timeout_ms", JSONObject().put("type", "integer").put("description", "同步等待超时毫秒数，默认 30000"))
                        put("mode", JSONObject().put("type", "string").put("description", "可选：ssh_exec 或 pty"))
                        put("focus", JSONObject().put("type", "boolean").put("description", "是否切入该终端界面，默认 false（在首页静默执行不跳转）"))
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
                val focus = arguments.optBoolean("focus", false)
                if (text.isBlank()) {
                    return McpToolResult(isError = true, content = "Missing text/command", voiceSummary = "未指定要执行的命令内容。")
                }
                val res = runInWorkspaceHandler?.invoke(selector, text, enter, selector.agentName, sync, timeoutMs, mode, focus)
                    ?: ToolOpResult(false, "runInWorkspaceHandler unavailable", "执行工作区命令不可用。")
                return McpToolResult(
                    isError = !res.ok,
                    content = res.detail,
                    voiceSummary = res.voiceSummary,
                    expectsFollowUp = res.expectsFollowUp
                )
            }
        })

        // Task dispatch is the manager-level operation: select or start a developer in any configured workspace.
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "dispatch_task",
                description = "将任务立即分配给指定主机、工作区和开发者。自动复用已打开的本地/共享实例；工作区尚未打开时在后台启动开发者后派发。返回 session_id 供 get_task_status 监工。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("host_id", JSONObject().put("type", "string").put("description", "目标主机 ID；优先于 host_name"))
                        put("host_name", JSONObject().put("type", "string").put("description", "目标主机名称"))
                        put("workspace_path", JSONObject().put("type", "string").put("description", "目标工作区绝对路径；可指定尚未打开的目录"))
                        put("workspace_name", JSONObject().put("type", "string").put("description", "目标工作区名称；应结合主机消歧"))
                        put("agent_id", JSONObject().put("type", "string").put("description", "目标开发者预设 ID"))
                        put("agent_name", JSONObject().put("type", "string").put("description", "目标开发者名称或命令，如 Codex、Claude、agy2"))
                        put("session_id", JSONObject().put("type", "string").put("description", "指定已打开或共享的任务会话 ID；用于精确复用或监工目标"))
                        put("task_prompt", JSONObject().put("type", "string").put("description", "交给开发者执行的完整任务说明"))
                        put("focus", JSONObject().put("type", "boolean").put("description", "是否切换到目标终端页面，默认 false；后台派发建议 false"))
                    })
                    put("required", JSONArray().put("task_prompt"))
                }
            )

            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val prompt = arguments.optString("task_prompt").ifBlank { arguments.optString("prompt") }.trim()
                if (prompt.isBlank()) {
                    return McpToolResult(isError = true, content = "Missing task_prompt", voiceSummary = "没有提供要派发的任务内容。")
                }
                if (prompt.length > 12_000) {
                    return McpToolResult(isError = true, content = "task_prompt exceeds 12000 characters", voiceSummary = "任务说明过长，无法派发。")
                }
                val result = dispatchTaskHandler?.invoke(arguments)
                    ?: ToolOpResult(false, "dispatchTaskHandler unavailable", "任务派发接口不可用。")
                return McpToolResult(
                    isError = !result.ok,
                    content = result.detail,
                    voiceSummary = result.voiceSummary,
                    expectsFollowUp = result.expectsFollowUp
                )
            }
        })

        // 8. focus_terminal
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "focus_terminal",
                description = "打开并聚焦指定工作区实例；若目标是首页的远端共享实例，会先接入该共享会话再打开终端页。按 session_id、首页标题、工作区名称或 query 匹配。",
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
                description = "Jarvis手机端管理工具：关闭指定的本地或远端共享工作区实例；可按 session_id、首页实例标题、工作区名称/路径和主机名定位。共享实例会先连接并确认目标，再关闭远端会话。",
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
                description = "在任何页面向进行中工作区实例发送命令、自然语言或 Agent 内置斜杠命令（如 codex/claude 的 /model、/status、/compact）；支持 sync=true 等待并返回最新输出/退出码。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties().apply {
                        put("text", JSONObject().put("type", "string").put("description", "要发送的文本、命令或 /model、/status 等内置命令"))
                        put("press_enter", JSONObject().put("type", "boolean").put("description", "发送后是否自动按回车，默认 true"))
                        put("sync", JSONObject().put("type", "boolean").put("description", "是否同步等待响应并返回 stdout/屏幕结果，默认 false"))
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
                description = "向任意进行中工作区自由发送单键或组合键（支持 alt+m、ctrl+c、shift+tab、ctrl+d、alt+enter、esc、up/down/left/right、enter 等）；设 sync=true 可等待并回传最新屏幕状态。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties().apply {
                        put("keys", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                        put("key", JSONObject().put("type", "string"))
                        put("sync", JSONObject().put("type", "boolean").put("description", "发送组合键后是否短暂等待并回读当前屏幕，默认 false"))
                        put("wait_ms", JSONObject().put("type", "integer").put("description", "sync=true 时的等待毫秒数，默认 800"))
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
                val sync = extractSyncFlag(arguments, defaultValue = false)
                val waitMs = arguments.optLong("wait_ms", 800L).coerceIn(150L, 5_000L)
                val res = sendKeysHandler?.invoke(keys, SessionSelector.fromJson(arguments), sync, waitMs)
                    ?: ToolOpResult(false, "sendKeysHandler unavailable", "按键注入失败。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary, expectsFollowUp = res.expectsFollowUp)
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
                description = "切换 Remote Dev 手机端页面，包括首页、终端、设置、主机管理、开发者、历史记录和登录。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("screen", JSONObject().put("type", "string")
                            .put("enum", JSONArray(JarvisScreen.entries.map { it.id })))
                    })
                    put("required", JSONArray().put("screen"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val screen = JarvisScreen.fromIdOrAlias(arguments.optString("screen"))
                    ?: return McpToolResult(isError = true, content = "Missing or unknown screen", voiceSummary = "请指定要打开的页面。")
                val navigate = navigateScreenHandler
                    ?: return McpToolResult(isError = true, content = "Navigation handler unavailable", voiceSummary = "手机页面还未就绪，请稍后再试。")
                val ok = navigate(screen.id)
                return if (ok) {
                    McpToolResult(content = "Navigated to ${screen.id}", voiceSummary = "已切换到${screen.label}。")
                } else {
                    McpToolResult(isError = true, content = "Unavailable screen: ${screen.id}", voiceSummary = "当前无法打开${screen.label}。")
                }
            }
        })

        // 28. close_awake
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "close_awake",
                description = "关闭 JARVIS 唤醒态与悬浮弹窗，切回后台监听唤醒词状态。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("farewell", JSONObject().put("type", "string").put("description", "可选：关闭前简短告别语，省略则立即静默退回监听唤醒态"))
                    })
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val farewell = arguments.optString("farewell", "").trim().takeIf { it.isNotBlank() }
                val close = closeAwakeHandler
                    ?: return McpToolResult(isError = true, content = "Voice interface unavailable", voiceSummary = "语音界面还未就绪。")
                close(farewell)
                return McpToolResult(
                    content = "Awake state closed, returned to standby wake listening",
                    voiceSummary = farewell ?: "已关闭唤醒，进入监听待命状态。"
                )
            }
        })

        // 29. get_task_status
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "get_task_status",
                description = "Jarvis监工入口：在任何页面读取指定任务或全部本地/共享任务的状态（连接、输出流、待确认、配额、末尾屏幕输出）。派发任务后优先用返回的session_id查询；不传筛选器可巡检全部任务。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties().apply {
                        put("lines", JSONObject().put("type", "integer").put("description", "每个任务读取末尾输出行数，默认 15"))
                    })
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val lines = arguments.optInt("lines", 15).coerceIn(1, 100)
                val res = taskStatusHandler?.invoke(SessionSelector.fromJson(arguments), lines)
                    ?: ToolOpResult(false, "taskStatusHandler unavailable", "读取任务状态失败。")
                return McpToolResult(
                    isError = !res.ok,
                    content = res.detail,
                    voiceSummary = res.voiceSummary,
                    expectsFollowUp = true
                )
            }
        })

        // 30. manage_host
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "manage_host",
                description = "管理 SSH 主机：添加(add)、修改(update)、删除(delete)、选中(select)、探测内/外网可达性(probe)或排序(move)。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().put("type", "string").put("description", "add | update | delete | select | probe | move"))
                        put("host_id", JSONObject().put("type", "string"))
                        put("name", JSONObject().put("type", "string"))
                        put("lan_address", JSONObject().put("type", "string"))
                        put("lan_port", JSONObject().put("type", "integer"))
                        put("external_address", JSONObject().put("type", "string"))
                        put("external_port", JSONObject().put("type", "integer"))
                        put("username", JSONObject().put("type", "string"))
                        put("password", JSONObject().put("type", "string"))
                        put("icon", JSONObject().put("type", "string"))
                        put("to_index", JSONObject().put("type", "integer"))
                    })
                    put("required", JSONArray().put("action"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val action = arguments.optString("action", "").trim().lowercase()
                val res = manageHostHandler?.invoke(action, arguments)
                    ?: ToolOpResult(false, "manageHostHandler unavailable", "主机管理接口不可用。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary, expectsFollowUp = action == "probe")
            }
        })

        // 31. manage_agent
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "manage_agent",
                description = "管理 AI 开发者预设：添加(add)、修改(update)、删除(delete)、选中(select)或排序(move)。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().put("type", "string").put("description", "add | update | delete | select | move"))
                        put("agent_id", JSONObject().put("type", "string"))
                        put("name", JSONObject().put("type", "string"))
                        put("command", JSONObject().put("type", "string"))
                        put("icon", JSONObject().put("type", "string"))
                        put("to_index", JSONObject().put("type", "integer"))
                    })
                    put("required", JSONArray().put("action"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val action = arguments.optString("action", "").trim().lowercase()
                val res = manageAgentHandler?.invoke(action, arguments)
                    ?: ToolOpResult(false, "manageAgentHandler unavailable", "开发者管理接口不可用。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary)
            }
        })

        // 32. manage_workspace
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "manage_workspace",
                description = "管理主机开发目录：添加(add)、编辑重命名(update)、删除(delete)、调整排序(move)或通过 SFTP 浏览远端目录(list_remote)。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().put("type", "string").put("description", "add | update | delete | move | list_remote"))
                        put("host_id", JSONObject().put("type", "string"))
                        put("host_name", JSONObject().put("type", "string"))
                        put("path", JSONObject().put("type", "string"))
                        put("new_path", JSONObject().put("type", "string"))
                        put("name", JSONObject().put("type", "string"))
                        put("icon", JSONObject().put("type", "string"))
                        put("to_index", JSONObject().put("type", "integer"))
                    })
                    put("required", JSONArray().put("action"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val action = arguments.optString("action", "").trim().lowercase()
                val res = manageWorkspaceHandler?.invoke(action, arguments)
                    ?: ToolOpResult(false, "manageWorkspaceHandler unavailable", "工作区目录管理接口不可用。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary, expectsFollowUp = action == "list_remote")
            }
        })

        // 33. manage_logs
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "manage_logs",
                description = "同步并查看操作历史记录(list)，或从某条历史记录直接恢复会话(restore)。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().put("type", "string").put("description", "list | restore"))
                        put("session_id", JSONObject().put("type", "string"))
                        put("query", JSONObject().put("type", "string"))
                        put("limit", JSONObject().put("type", "integer"))
                        put("focus", JSONObject().put("type", "boolean").put("description", "restore 时是否切入终端页，默认 false"))
                    })
                    put("required", JSONArray().put("action"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val action = arguments.optString("action", "list").trim().lowercase()
                val res = manageLogsHandler?.invoke(action, arguments)
                    ?: ToolOpResult(false, "manageLogsHandler unavailable", "历史日志接口不可用。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary, expectsFollowUp = action == "list")
            }
        })

        // 34. update_settings
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "update_settings",
                description = "修改参数所列的 App 设置，包括布局、自动确认、同步和 Jarvis 配置。可生成 SSH 密钥。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("device_layout_mode", JSONObject().put("type", "string"))
                        put("tiled_mode", JSONObject().put("type", "boolean"))
                        put("auto_confirm_enabled", JSONObject().put("type", "boolean"))
                        put("auto_confirm_delay", JSONObject().put("type", "integer"))
                        put("offline_mode", JSONObject().put("type", "boolean"))
                        put("auto_sync_enabled", JSONObject().put("type", "boolean"))
                        put("sync_url", JSONObject().put("type", "string"))
                        put("sync_interval", JSONObject().put("type", "integer"))
                        put("quota_interval", JSONObject().put("type", "integer"))
                        put("jarvis_model", JSONObject().put("type", "string"))
                        put("jarvis_effort", JSONObject().put("type", "string"))
                        put("jarvis_wake_word", JSONObject().put("type", "string"))
                        put("jarvis_notify_finished", JSONObject().put("type", "boolean"))
                        put("jarvis_bluetooth_sco", JSONObject().put("type", "boolean"))
                        put("generate_ssh_key", JSONObject().put("type", "boolean"))
                    })
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val res = updateSettingsHandler?.invoke(arguments)
                    ?: ToolOpResult(false, "updateSettingsHandler unavailable", "设置更新接口不可用。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary, expectsFollowUp = res.expectsFollowUp)
            }
        })

        // 35. manage_session_window
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "manage_session_window",
                description = "执行会话窗口控制：最小化(minimize)、恢复卡片(restore)，或审批待信任 SSH 主机指纹弹窗(approve_host_key)。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", sessionSelectorProperties().apply {
                        put("action", JSONObject().put("type", "string").put("description", "minimize | restore | approve_host_key"))
                        put("approve", JSONObject().put("type", "boolean").put("description", "approve_host_key 时 true=信任并连接，false=拒绝"))
                    })
                    put("required", JSONArray().put("action"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val action = arguments.optString("action", "").trim().lowercase()
                val res = sessionWindowHandler?.invoke(SessionSelector.fromJson(arguments), action, arguments)
                    ?: ToolOpResult(false, "sessionWindowHandler unavailable", "窗口控制接口不可用。")
                return McpToolResult(isError = !res.ok, content = res.detail, voiceSummary = res.voiceSummary)
            }
        })
    }

    fun register(tool: JarvisMcpTool) {
        tools[tool.definition.name] = tool
    }

    fun getTool(name: String): JarvisMcpTool? = tools[name]

    fun isManagerTool(name: String): Boolean = name in tools.keys && name !in developerWorkTools

    private fun managerDefinitions(): List<McpToolDefinition> = tools.values
        .map { it.definition }
        .filter { isManagerTool(it.name) }
        .sortedBy { it.name }
        .map { definition ->
            if (definition.name != "open_terminal") return@map definition
            val schema = JSONObject(definition.inputSchema.toString())
            schema.getJSONObject("properties").remove("initial_command")
            definition.copy(description = "打开或复用指定主机、工作区和开发者的终端。可选择打开页面或在后台启动。", inputSchema = schema)
        }

    private fun buildCapabilitiesSummary(): String {
        val groups = listOf(
            setOf("get_app_state", "get_task_status", "read_terminal_screen") to "查看实例状态和终端输出",
            setOf("open_terminal", "focus_terminal", "close_terminal", "rename_terminal", "reconnect_terminal") to "打开、接入、关闭、重命名和重连实例",
            setOf("manage_host", "manage_workspace", "manage_agent") to "管理主机、工作区和开发者",
            setOf("manage_logs") to "查询历史并恢复会话",
            setOf("navigate_screen", "manage_session_window", "update_settings", "trigger_sync") to "切换页面、调整窗口和设置、同步数据",
            setOf("dispatch_task", "get_task_status") to "分配开发任务并查看进度",
            setOf("close_awake") to "退出唤醒态"
        )
        val actions = groups.filter { (names, _) -> names.all(::isManagerTool) }.map { it.second }
        return "我可以${actions.joinToString("；")}。"
    }

    /**
     * 导出 Jarvis 管理模型可调用的工具及完整参数定义。
     */
    fun buildMcpToolsSchemaJson(): JSONArray = JSONArray().apply {
        managerDefinitions().forEach { def ->
            put(JSONObject().apply {
                put("name", def.name)
                put("description", def.description)
                put("inputSchema", def.inputSchema)
            })
        }
    }

    fun buildMcpProtocolManual(): String = """
        【本轮可调用的 Android 工具】
        以下工具由 Remote Dev 安卓 App 执行。通过文本桥接调用时输出独立标记：
        [[RDT_MCP:{"tool":"工具名","arguments":{}}]]
        实例管理：用 get_app_state 查看 sessions 和 shared_tasks，按首页标题找到唯一记录，将其 id 传作 session_id。
        接入已有实例用 focus_terminal，关闭用 close_terminal，改名用 rename_terminal。打开尚未启动的工作区用 open_terminal，传主机和工作区，不发送命令。目标有歧义时请用户明确目标。
        派发和监工：从 get_app_state 选主机、工作区和开发者，向 dispatch_task 提交 task_prompt。已有实例可指定 session_id，没有实例时工具会在所选工作区启动开发者。随后将返回的 session_id 传给 get_task_status 核验接收或进度。
        配置管理：用 get_app_state 读取当前资源和设置，从 manage_host、manage_workspace、manage_agent 选择对应的 action 并传入目标。用 update_settings 写入用户要求修改的字段，再读取状态核验。
        历史和界面：用 manage_logs 的 list 查询历史，用 restore 和会话 ID 恢复会话；用 navigate_screen 切换页面，用 manage_session_window 管理会话窗口；用 close_awake 退出唤醒态。
        调用示例：[[RDT_MCP:{"tool":"get_task_status","arguments":{}}]]
        读写时使用下方参数定义。收到 App 返回结果后报告操作结果。
        ${buildMcpToolsSchemaJson()}
    """.trimIndent()

    suspend fun executeManagerTool(name: String, arguments: JSONObject): McpToolResult {
        if (!isManagerTool(name)) {
            return McpToolResult(
                isError = true,
                content = "Tool is not available to Jarvis: $name",
                voiceSummary = if (name in developerWorkTools) "开发任务请通过任务派发交给开发者。" else "手机端没有找到工具 $name。"
            )
        }
        if (name == "open_terminal" && arguments.optString("initial_command", "").isNotBlank()) {
            return McpToolResult(
                isError = true,
                content = "Opening a terminal cannot include a command. Use dispatch_task for developer work.",
                voiceSummary = "可以打开终端。如需执行开发任务，请派发给开发者。"
            )
        }
        return executeTool(name, arguments)
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
