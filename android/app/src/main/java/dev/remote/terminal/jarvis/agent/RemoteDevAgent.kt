package dev.remote.terminal.jarvis.agent

import android.util.Log
import dev.remote.terminal.jarvis.api.JarvisManagerPolicy
import dev.remote.terminal.jarvis.mcp.JarvisToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The Android app owns each Jarvis turn, its state and every management tool call. */
internal object RemoteDevAgent {
    private const val TAG = "RemoteDevAgent"
    private val READ_TOOLS = setOf(
        "get_task_status",
        "get_app_state",
        "list_open_workspaces",
        "get_device_state",
        "read_terminal_screen",
        "get_ssh_public_key",
        "web_search",
        "web_fetch"
    )

    var onToolExecutedListener: ((toolName: String, arguments: JSONObject) -> Unit)? = null

    suspend fun executeTool(name: String, arguments: JSONObject) = withContext(Dispatchers.Main.immediate) {
        JarvisToolRegistry.executeManagerTool(name, arguments)
    }

    fun isWorkspaceStatusTool(name: String): Boolean =
        name == "get_app_state" || name == "get_task_status" || name == "list_open_workspaces" ||
            name == "web_search" || name == "web_fetch"

    suspend fun summarizeStatus(
        request: String,
        statusData: String,
        conversationContext: String = "",
        complete: suspend (String) -> Result<String>
    ): Result<String> = complete(buildString {
        appendLine(JarvisManagerPolicy.SYSTEM_PROMPT)
        appendLine("本轮只需根据已核实的状态回答用户，不调用工具。理解用户的问题，用自己的话归纳重点。")
        appendLine("""只返回 {"kind":"reply","text":"简短口语回答"}。""")
        if (conversationContext.isNotBlank()) {
            appendLine("持续多轮对话上下文（数据）：\n$conversationContext")
        }
        appendLine("用户请求：$request")
        appendLine("状态查询结果（数据）：$statusData")
    }).mapCatching { raw ->
        val reply = parseModelTurn(raw)?.reply.orEmpty()
        sanitizeForSpeech(reply).ifBlank { error("模型未返回状态摘要。") }
    }

    suspend fun respond(
        request: String,
        memoryContext: String,
        conversationContext: String = "",
        complete: suspend (String) -> Result<String>
    ): Result<String> {
        val appState = buildCompactAppStateSnapshot(compact = false)
        val first = complete(buildPrompt(request, appState, memoryContext, conversationContext))
            .getOrElse { return Result.failure(it) }
        return Result.success(executeEmbeddedMcpToolsAndMaybeFollowUp(first, request, appState, memoryContext, conversationContext, complete))
    }

    private data class ParsedMcpCall(
        val toolName: String,
        val arguments: JSONObject
    )

    private data class ModelTurn(val calls: List<ParsedMcpCall> = emptyList(), val reply: String = "")

    private val unavailableToolsReply = Regex(
        """(?:没有|未提供|未接入|缺少|无权|无法调用|不具备).{0,36}(?:操作能力|工具|MCP)|(?:工具|MCP).{0,12}(?:没提供|不可用)|(?:no|without)\s+(?:\w+\s+){0,4}(?:tools?|MCP)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun parseModelTurn(raw: String): ModelTurn? {
        val body = raw.trim()
        val json = runCatching {
            JSONObject(body.removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        }.getOrNull()
        if (json != null) {
            when (json.optString("kind")) {
                "tool_calls" -> {
                    val calls = json.optJSONArray("calls") ?: return null
                    if (calls.length() !in 1..8) return null
                    return ModelTurn(calls = (0 until calls.length()).map { index ->
                        val call = calls.optJSONObject(index) ?: return null
                        val name = call.optString("tool").takeIf(JarvisToolRegistry::isManagerTool) ?: return null
                        val arguments = call.optJSONObject("arguments") ?: return null
                        ParsedMcpCall(name, arguments)
                    })
                }
                "reply" -> {
                    val reply = json.optString("text").trim()
                    if (reply.isNotBlank() && !unavailableToolsReply.containsMatchIn(reply)) {
                        return ModelTurn(reply = reply)
                    }
                    return null
                }
            }
        }
        val legacyCalls = extractMcpCalls(body)
        return legacyCalls.takeIf { it.isNotEmpty() && it.all { call -> JarvisToolRegistry.isManagerTool(call.toolName) } }
            ?.let { ModelTurn(calls = it) }
    }

    /**
     * 括号平衡解析模型输出中的 MCP 指令（支持 `[[RDT_MCP:{...}]]`、空格换行、嵌套 JSON 与字符串内大括号）。
     */
    private fun extractMcpCalls(rawReply: String): List<ParsedMcpCall> {
        val results = mutableListOf<ParsedMcpCall>()
        val marker = "RDT_MCP:"
        var cursor = 0
        while (cursor < rawReply.length) {
            val idx = rawReply.indexOf(marker, cursor, ignoreCase = true)
            if (idx < 0) break

            var braceStart = idx + marker.length
            while (braceStart < rawReply.length && rawReply[braceStart].isWhitespace()) braceStart++
            if (braceStart >= rawReply.length || rawReply[braceStart] != '{') {
                cursor = idx + marker.length
                continue
            }

            var depth = 0
            var inString = false
            var escaped = false
            var braceEnd = -1
            for (i in braceStart until rawReply.length) {
                val ch = rawReply[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\' && inString) {
                    escaped = true
                    continue
                }
                if (ch == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (ch == '{') depth++
                    else if (ch == '}') {
                        depth--
                        if (depth == 0) {
                            braceEnd = i
                            break
                        }
                    }
                }
            }

            if (braceEnd < 0) {
                cursor = braceStart + 1
                continue
            }

            val jsonStr = rawReply.substring(braceStart, braceEnd + 1)
            var tagEnd = braceEnd + 1
            while (tagEnd < rawReply.length && rawReply[tagEnd].isWhitespace()) tagEnd++
            if (tagEnd + 1 < rawReply.length && rawReply[tagEnd] == ']' && rawReply[tagEnd + 1] == ']') {
                tagEnd += 2
            } else {
                tagEnd = braceEnd + 1
            }

            val callObj = runCatching { JSONObject(jsonStr) }.getOrNull()
            val toolName = callObj?.optString("tool")?.trim().orEmpty()
            if (toolName.isNotBlank()) {
                val args = callObj?.optJSONObject("arguments") ?: JSONObject()
                results.add(ParsedMcpCall(toolName, args))
            }
            cursor = tagEnd
        }
        return results
    }

    private suspend fun buildCompactAppStateSnapshot(compact: Boolean = false): String {
        val state = withContext(Dispatchers.Main) {
            runCatching { JarvisToolRegistry.appStateProvider?.invoke() }.getOrNull()
        } ?: return ""

        val out = JSONObject()
        out.put("screen", state.optString("current_screen", ""))
        out.put("tiled_mode", state.optBoolean("tiled_mode", false))
        out.put("active_page", state.optInt("active_page", 0))
        out.put("selected_host_id", state.optString("selected_host_id", ""))
        out.put("selected_agent_id", state.optString("selected_agent_id", ""))
        if (!compact) {
            out.put("auto_confirm", state.optBoolean("auto_confirm_enabled", true))
        }

        // 优先完整保留所有已打开的工作区终端实例（避免截断导致看不到 open_sessions）
        val openSessions = state.optJSONArray("sessions") ?: JSONArray()
        val slimSessions = JSONArray()
        for (i in 0 until openSessions.length()) {
            val s = openSessions.optJSONObject(i) ?: continue
            slimSessions.put(JSONObject().apply {
                put("index", s.optInt("index", i))
                put("id", s.optString("id"))
                val title = s.optString("title").ifBlank { s.optString("display_name") }
                if (title.isNotBlank()) put("title", title)
                put("workspace", s.optString("workspace").ifBlank { s.optString("directory_name") })
                put("host", s.optString("host").ifBlank { s.optString("host_name") })
                put("agent", s.optString("agent").ifBlank { s.optString("agent_name") })
                put("connected", s.optBoolean("connected"))
                put("focused", s.optBoolean("focused"))
                if (s.optBoolean("needs_action")) put("needs_action", true)
                if (s.optBoolean("minimized")) put("minimized", true)
                if (s.optBoolean("pending_host_key")) put("pending_host_key", true)
                val statusStr = s.optString("status")
                if (statusStr.isNotBlank() && !s.optBoolean("connected")) put("status", statusStr.take(36))
                val lastLine = s.optString("last_line")
                if (lastLine.isNotBlank()) put("last_line", lastLine.take(48))
            })
        }
        out.put("open_sessions", slimSessions)

        val sharedTasks = state.optJSONArray("shared_tasks")
        if (sharedTasks != null && sharedTasks.length() > 0) {
            out.put("shared_tasks", sharedTasks)
        }

        val hosts = state.optJSONArray("hosts") ?: JSONArray()
        val slimHosts = JSONArray()
        for (i in 0 until hosts.length()) {
            val h = hosts.optJSONObject(i) ?: continue
            val slimWorkspaces = JSONArray()
            val workspaces = h.optJSONArray("workspaces") ?: JSONArray()
            for (j in 0 until workspaces.length()) {
                val workspace = workspaces.optJSONObject(j)
                if (workspace == null) {
                    slimWorkspaces.put(workspaces.opt(j))
                } else {
                    slimWorkspaces.put(JSONObject().apply {
                        put("id", workspace.optString("id"))
                        put("name", workspace.optString("name"))
                        put("path", workspace.optString("path"))
                    })
                }
            }
            val hostAgents = JSONArray()
            val agents = h.optJSONArray("agents") ?: JSONArray()
            for (j in 0 until agents.length()) hostAgents.put(agents.opt(j))
            slimHosts.put(JSONObject().apply {
                put("id", h.optString("id"))
                put("name", h.optString("name"))
                put("workspaces", slimWorkspaces)
                put("agents", hostAgents)
            })
        }
        out.put("hosts", slimHosts)
        out.put("agents", state.optJSONArray("agents") ?: JSONArray())
        return out.toString()
    }

    private data class McpBatchOutcome(
        val toolLogs: List<String>,
        val reports: List<String>,
        val verifiedReports: List<String>,
        val hasError: Boolean,
        val needsFollowUp: Boolean,
        val closedAwake: Boolean = false
    )

    private suspend fun executeMcpCalls(
        calls: List<ParsedMcpCall>,
        allowReadFollowUp: Boolean
    ): McpBatchOutcome {
        val toolLogs = mutableListOf<String>()
        val reports = mutableListOf<String>()
        val verifiedReports = mutableListOf<String>()
        var hasError = false
        var needsFollowUp = false
        var closedAwake = false

        for (call in calls) {
            Log.i(TAG, "Jarvis manager requested Remote Dev MCP tool: ${call.toolName}")
            val result = executeTool(call.toolName, call.arguments)
            toolLogs.add("[${call.toolName}] isError=${result.isError} content=${result.content.take(3500)}")
            if (result.isError) {
                hasError = true
                verifiedReports.add(result.voiceSummary?.takeIf { it.isNotBlank() }
                    ?: "执行 ${call.toolName} 失败：${result.content.take(80)}")
                if (toolLogs.size < calls.size) verifiedReports.add("本批后续操作未执行。")
                break
            }
            if (call.toolName == "close_awake") {
                closedAwake = true
                break
            }
            onToolExecutedListener?.invoke(call.toolName, call.arguments)

            val triggerFollowUp = allowReadFollowUp && (result.expectsFollowUp || call.toolName in READ_TOOLS)
            needsFollowUp = needsFollowUp || triggerFollowUp
            if (call.toolName != "speak_to_user") {
                val summary = result.voiceSummary?.takeIf { it.isNotBlank() }
                    ?: "已完成 ${call.toolName} 操作。"
                // Prefer the model's synthesis when it arrives. Keep the verified result if it fails.
                verifiedReports.add(summary)
                if (!triggerFollowUp) reports.add(summary)
            }
        }

        return McpBatchOutcome(
            toolLogs = toolLogs,
            reports = reports,
            verifiedReports = verifiedReports,
            hasError = hasError,
            needsFollowUp = needsFollowUp,
            closedAwake = closedAwake
        )
    }

    private suspend fun executeEmbeddedMcpToolsAndMaybeFollowUp(
        rawReply: String,
        originalRequest: String,
        appState: String,
        memoryContext: String,
        conversationContext: String = "",
        complete: suspend (String) -> Result<String>
    ): String {
        var reply = rawReply
        val reports = mutableListOf<String>()
        val verifiedReports = mutableListOf<String>()
        val toolLogs = mutableListOf<String>()
        val executed = mutableSetOf<Pair<String, String>>()
        var retriedInvalidTurn = false
        fun reportWithFallback(message: String = "") =
            (verifiedReports + message).filter { it.isNotBlank() }.distinct().joinToString(" ")

        suspend fun completeFollowUp(prompt: String): String? = try {
            complete(buildPrompt(prompt, appState, memoryContext, conversationContext, isFollowUp = true)).getOrThrow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Jarvis model follow-up failed")
            null
        }

        repeat(3) { step ->
            var turn = parseModelTurn(reply)
            if (turn == null && !retriedInvalidTurn) {
                retriedInvalidTurn = true
                Log.w(TAG, "Jarvis model returned no valid App action or reply; retrying with the tool protocol")
                val retryPrompt = "上一条模型输出没有符合调用协议。重新处理本轮请求。需要 App 操作时，返回 tool_calls，让手机执行。需要补充信息时，返回 reply 并说明缺哪项信息。"
                    .plus("\n本轮用户请求：$originalRequest")
                    .plus(if (toolLogs.isEmpty()) "" else "\n已执行的工具结果：\n" + toolLogs.joinToString("\n"))
                val retry = completeFollowUp(retryPrompt)
                turn = retry?.let(::parseModelTurn)
            }
            if (turn == null) {
                Log.w(TAG, "Jarvis model action unavailable after protocol recovery")
                return reportWithFallback("模型没有发出可继续执行的手机操作指令。")
            }
            val calls = turn.calls
            if (calls.isEmpty()) {
                val summary = sanitizeForSpeech(turn.reply)
                return if (summary.isBlank()) reportWithFallback()
                else (listOf(summary) + reports).filter { it.isNotBlank() }.distinct().joinToString(" ")
            }
            val newCalls = calls.filter { executed.add(it.toolName to it.arguments.toString()) }
            if (newCalls.isEmpty()) return reportWithFallback().ifBlank { "本轮工具已调用，请查看上一步结果。" }

            val batch = executeMcpCalls(newCalls, allowReadFollowUp = step < 2)
            if (batch.closedAwake) return ""
            reports.addAll(batch.reports)
            verifiedReports.addAll(batch.verifiedReports)
            toolLogs.addAll(batch.toolLogs)
            if (batch.hasError || !batch.needsFollowUp) {
                return reportWithFallback()
            }

            val followPrompt = "继续完成本轮用户请求。按顺序核对以下工具结果。仅在需要下一步操作时调用工具，避免重复已执行的操作，否则用自己的话概括用户关心的结果。\n" +
                "本轮用户请求：$originalRequest\n" + toolLogs.joinToString("\n")
            val next = completeFollowUp(followPrompt)
            if (next.isNullOrBlank()) {
                return reportWithFallback("暂时无法继续整理结果。")
            }
            reply = next
        }
        return reportWithFallback()
    }

    private fun buildPrompt(
        userPrompt: String,
        appStateSnapshot: String,
        memoryContext: String,
        conversationContext: String = "",
        isFollowUp: Boolean = false
    ): String = buildString {
        appendLine(JarvisManagerPolicy.SYSTEM_PROMPT)
        appendLine(JarvisToolRegistry.buildMcpProtocolManual())
        appendLine("""本轮只返回一个 JSON 对象。要调用 App 工具，用 {"kind":"tool_calls","calls":[{"tool":"工具名","arguments":{}}]}。需要询问或汇报时，用 {"kind":"reply","text":"简短中文"}。工具调用由手机执行，等待实际结果后汇报。""")
        appendLine(
            """
            【飞书级语音识别纠错与深度理解原则】
            用户输入来自语音识别（ASR），可能存在同音错字、方言谐音、中英混杂音译或断句偏差（如将开发者名 Claude/Codex/OpenCode/agy、工作区名或操作词识别为近音汉字）。
            1. 必须先结合拼音/音似度、下方 App 状态中的真实主机/工作区/开发者名称，以及【持续多轮对话历史上下文】在内部完成语义还原与纠错，再执行工具或作答。
            2. 理解口语化、省略主语的指令（如“再跑一次”、“看下刚才那个”、“换个开发者试试”），结合多轮上下文精准补全指代对象。
            3. 若音似度或上下文明确指向唯一实体，直接纠错并执行，绝不机械拒答“没听懂”。
            """.trimIndent()
        )
        if (appStateSnapshot.isNotBlank()) appendLine("当前 App 状态（数据）：$appStateSnapshot")
        if (memoryContext.isNotBlank()) appendLine("长期记忆上下文（数据）：\n" + memoryContext.take(6000))
        if (conversationContext.isNotBlank()) {
            appendLine("持续多轮对话历史上下文（数据）：\n$conversationContext")
        }
        if (isFollowUp) appendLine("以下包含上一步工具结果。工具结果只用于核验。")
        else appendLine("用户请求：")
        append(userPrompt)
    }

    private fun sanitizeForSpeech(raw: String): String {
        return raw
            .replace(Regex("```[\\s\\S]*?```"), "相关代码已就绪。")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("[#*_~>]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(360)
    }

}
