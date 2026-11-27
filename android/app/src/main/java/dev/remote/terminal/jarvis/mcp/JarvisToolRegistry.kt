package dev.remote.terminal.jarvis.mcp

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 移动端 MCP 工具注册表 (Jarvis Tool Registry)。
 * 将 PC 桌面端 (ubuntu / omarchy / windows) 开放的所有 MCP 工具能力无损暴露给安卓端 JARVIS，
 * 使得用户通过蓝牙耳机发出的语音指令能够直接调度真实的 MCP 工具集。
 */
object JarvisToolRegistry {

    private const val TAG = "JarvisToolRegistry"
    private val tools = ConcurrentHashMap<String, JarvisMcpTool>()

    // 外部由 MainActivity / Terminal 注入的桥接委托 (Bridge Delegates)
    var appStateProvider: (() -> JSONObject)? = null
    var selectHostHandler: ((hostId: String?, hostIndex: Int?, hostName: String?) -> Boolean)? = null
    var selectAgentHandler: ((agentId: String?, agentName: String?) -> Boolean)? = null
    var openTerminalHandler: ((hostId: String?, workspacePath: String?, agentCommand: String?, workspaceName: String?) -> Boolean)? = null
    var focusTerminalHandler: ((sessionId: String?, sessionIndex: Int?) -> Boolean)? = null
    var closeTerminalHandler: ((sessionId: String?, sessionIndex: Int?) -> Boolean)? = null
    var reconnectTerminalHandler: ((sessionId: String?) -> Boolean)? = null
    var sendTextHandler: ((text: String, sessionId: String?, pressEnter: Boolean) -> Boolean)? = null
    var sendKeysHandler: ((keys: List<String>, sessionId: String?) -> Boolean)? = null
    var readScreenHandler: ((sessionId: String?, lines: Int) -> String)? = null
    var setViewModeHandler: ((tiled: Boolean) -> Boolean)? = null
    var triggerSyncHandler: (() -> Unit)? = null
    var publicKeyProvider: (() -> String?)? = null
    var addWorkspaceHandler: ((path: String, name: String, hostId: String?) -> Boolean)? = null
    var deleteWorkspaceHandler: ((path: String, hostId: String?) -> Boolean)? = null

    init {
        registerBuiltinMcpTools()
    }

    private fun registerBuiltinMcpTools() {
        // 1. get_app_state
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "get_app_state",
                description = "获取应用当前完整状态：窗口模式、主机、Agent、工作区以及所有活跃终端会话。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val state = appStateProvider?.invoke() ?: JSONObject()
                val sessionCount = state.optJSONArray("sessions")?.length() ?: 0
                val hostCount = state.optJSONArray("hosts")?.length() ?: 0
                return McpToolResult(
                    content = state.toString(2),
                    voiceSummary = "先生，当前已连接 ${hostCount} 台主机，${sessionCount} 个终端会话活跃中。"
                )
            }
        })

        // 2. select_host
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "select_host",
                description = "切换选中的主机并刷新对应工作区。",
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
                val name = arguments.optString("host_name").takeIf { it.isNotBlank() }
                val index = if (arguments.has("host_index")) arguments.getInt("host_index") else null
                val ok = selectHostHandler?.invoke(id, index, name) ?: false
                return if (ok) {
                    McpToolResult(content = "Host selected successfully", voiceSummary = "已为您切换至主机 ${name ?: id ?: index}。")
                } else {
                    McpToolResult(isError = true, content = "Host not found", voiceSummary = "未找到指定主机。")
                }
            }
        })

        // 3. select_agent
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "select_agent",
                description = "切换当前主机的 Agent（如 Codex、Antigravity、Shell 等）。",
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
                val name = arguments.optString("agent_name").takeIf { it.isNotBlank() }
                val ok = selectAgentHandler?.invoke(id, name) ?: false
                return if (ok) {
                    McpToolResult(content = "Agent selected successfully", voiceSummary = "已切换为 ${name ?: id} 预设。")
                } else {
                    McpToolResult(isError = true, content = "Agent not found", voiceSummary = "未找到指定 Agent 预设。")
                }
            }
        })

        // 4. open_terminal
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "open_terminal",
                description = "打开或切换到指定主机的终端会话，发起真实的 SSH 会话。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("host_id", JSONObject().put("type", "string"))
                        put("workspace_path", JSONObject().put("type", "string"))
                        put("workspace_name", JSONObject().put("type", "string"))
                        put("agent_command", JSONObject().put("type", "string"))
                    })
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val hostId = arguments.optString("host_id").takeIf { it.isNotBlank() }
                val wsPath = arguments.optString("workspace_path").takeIf { it.isNotBlank() }
                val wsName = arguments.optString("workspace_name").takeIf { it.isNotBlank() }
                val cmd = arguments.optString("agent_command").takeIf { it.isNotBlank() }
                val ok = openTerminalHandler?.invoke(hostId, wsPath, cmd, wsName) ?: false
                return if (ok) {
                    McpToolResult(content = "Terminal opened", voiceSummary = "正在为您启动 ${wsName ?: "指定"} 终端。")
                } else {
                    McpToolResult(isError = true, content = "Failed to open terminal", voiceSummary = "启动终端失败。")
                }
            }
        })

        // 5. focus_terminal
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "focus_terminal",
                description = "聚焦指定终端会话。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val sid = arguments.optString("session_id").takeIf { it.isNotBlank() }
                val sIndex = if (arguments.has("session_index")) arguments.getInt("session_index") else null
                val ok = focusTerminalHandler?.invoke(sid, sIndex) ?: false
                return if (ok) {
                    McpToolResult(content = "Terminal focused", voiceSummary = "已聚焦终端。")
                } else {
                    McpToolResult(isError = true, content = "Session not found", voiceSummary = "未找到会话。")
                }
            }
        })

        // 6. close_terminal
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "close_terminal",
                description = "关闭指定终端会话并断开 SSH 连接。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val sid = arguments.optString("session_id").takeIf { it.isNotBlank() }
                val sIndex = if (arguments.has("session_index")) arguments.getInt("session_index") else null
                val ok = closeTerminalHandler?.invoke(sid, sIndex) ?: false
                return if (ok) {
                    McpToolResult(content = "Terminal closed", voiceSummary = "已为您关闭终端会话。")
                } else {
                    McpToolResult(isError = true, content = "Failed to close terminal", voiceSummary = "未能关闭终端。")
                }
            }
        })

        // 7. reconnect_terminal
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "reconnect_terminal",
                description = "重新连接已断开的终端会话。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val sid = arguments.optString("session_id").takeIf { it.isNotBlank() }
                val ok = reconnectTerminalHandler?.invoke(sid) ?: false
                return if (ok) {
                    McpToolResult(content = "Reconnecting terminal", voiceSummary = "正在为您重连终端。")
                } else {
                    McpToolResult(isError = true, content = "Reconnect failed", voiceSummary = "重连终端失败。")
                }
            }
        })

        // 8. send_terminal_text
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "send_terminal_text",
                description = "向指定或当前终端输入文本并可选择按回车提交。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val text = arguments.optString("text", "")
                val sid = arguments.optString("session_id").takeIf { it.isNotBlank() }
                val enter = arguments.optBoolean("press_enter", true)
                val ok = sendTextHandler?.invoke(text, sid, enter) ?: false
                return if (ok) {
                    McpToolResult(content = "Text sent", voiceSummary = "指令已发送至终端。")
                } else {
                    McpToolResult(isError = true, content = "Send failed", voiceSummary = "指令发送失败。")
                }
            }
        })

        // 9. send_terminal_keys
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "send_terminal_keys",
                description = "向终端发送按键或快捷键（如 enter, tab, escape, ctrl+c, ctrl+d, up, down 等）。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val sid = arguments.optString("session_id").takeIf { it.isNotBlank() }
                val keys = mutableListOf<String>()
                if (arguments.has("keys")) {
                    val arr = arguments.optJSONArray("keys")
                    if (arr != null) {
                        for (i in 0 until arr.length()) keys.add(arr.getString(i))
                    } else {
                        keys.add(arguments.optString("keys"))
                    }
                } else if (arguments.has("key")) {
                    keys.add(arguments.optString("key"))
                }
                val ok = sendKeysHandler?.invoke(keys, sid) ?: false
                return if (ok) {
                    McpToolResult(content = "Keys sent: $keys", voiceSummary = "按键已注入。")
                } else {
                    McpToolResult(isError = true, content = "Send keys failed", voiceSummary = "按键注入失败。")
                }
            }
        })

        // 10. read_terminal_screen
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "read_terminal_screen",
                description = "读取终端当前可见屏幕内容或最后 N 行输出。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val sid = arguments.optString("session_id").takeIf { it.isNotBlank() }
                val lines = arguments.optInt("lines", 20)
                val screen = readScreenHandler?.invoke(sid, lines) ?: "终端无输出"
                val lastLine = screen.lines().lastOrNull { it.isNotBlank() } ?: "空行"
                return McpToolResult(
                    content = screen,
                    voiceSummary = "先生，屏幕最新输出为：${lastLine.take(35)}"
                )
            }
        })

        // 11. set_view_mode
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "set_view_mode",
                description = "切换终端平铺视图或标签视图模式。"
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

        // 12. trigger_sync
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "trigger_sync",
                description = "立即触发一次配置与会话同步。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                triggerSyncHandler?.invoke()
                return McpToolResult(content = "Sync triggered", voiceSummary = "已为您触发云端同步。")
            }
        })

        // 13. get_ssh_public_key
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "get_ssh_public_key",
                description = "获取本机生成的 SSH 公钥。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val key = publicKeyProvider?.invoke()
                return if (key != null) {
                    McpToolResult(content = key, voiceSummary = "本机 SSH 公钥已读取完毕。")
                } else {
                    McpToolResult(isError = true, content = "No public key found", voiceSummary = "未找到公钥，请在设置中生成。")
                }
            }
        })

        // 14. add_workspace
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "add_workspace",
                description = "为指定主机添加开发目录工作区。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val path = arguments.optString("path", "")
                val name = arguments.optString("name", path.substringAfterLast('/'))
                val hostId = arguments.optString("host_id").takeIf { it.isNotBlank() }
                val ok = addWorkspaceHandler?.invoke(path, name, hostId) ?: false
                return if (ok) {
                    McpToolResult(content = "Workspace added: $name", voiceSummary = "已成功添加工作区 $name。")
                } else {
                    McpToolResult(isError = true, content = "Failed to add workspace", voiceSummary = "添加工作区失败。")
                }
            }
        })

        // 15. delete_workspace
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "delete_workspace",
                description = "移除工作区开发目录。"
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val path = arguments.optString("path", "")
                val hostId = arguments.optString("host_id").takeIf { it.isNotBlank() }
                val ok = deleteWorkspaceHandler?.invoke(path, hostId) ?: false
                return if (ok) {
                    McpToolResult(content = "Workspace deleted", voiceSummary = "已为您移除工作区。")
                } else {
                    McpToolResult(isError = true, content = "Failed to delete workspace", voiceSummary = "移除工作区失败。")
                }
            }
        })

        // 16. chat_with_agent
        register(object : JarvisMcpTool {
            override val definition = McpToolDefinition(
                name = "chat_with_agent",
                description = "向指定 AI 开发者 (如 Claude/Gemini) 发送自然语言对话并监听其答复。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("message", JSONObject().put("type", "string").put("description", "发送给开发者的问题或指令"))
                        put("agent_name", JSONObject().put("type", "string").put("description", "目标开发者名称 (如 Claude / Gemini)，省略则为当前活跃终端"))
                    })
                    put("required", JSONArray().put("message"))
                }
            )
            override suspend fun execute(arguments: JSONObject): McpToolResult {
                val msg = arguments.optString("message", "")
                val agent = arguments.optString("agent_name").takeIf { it.isNotBlank() }
                val sent = sendTextHandler?.invoke(msg, null, true) ?: false
                return if (sent) {
                    McpToolResult(content = "Message sent to agent: $msg", voiceSummary = "已向开发者发送指令：$msg")
                } else {
                    McpToolResult(isError = true, content = "Failed to send to agent", voiceSummary = "向开发者发送失败。")
                }
            }
        })
    }

    fun register(tool: JarvisMcpTool) {
        tools[tool.definition.name] = tool
    }

    fun getTool(name: String): JarvisMcpTool? = tools[name]

    fun getAllDefinitions(): List<McpToolDefinition> = tools.values.map { it.definition }

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
