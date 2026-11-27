package dev.remote.terminal.jarvis.coordinator

import android.os.SystemClock
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import dev.remote.terminal.data.AgentPreset
import dev.remote.terminal.data.HostProfile
import dev.remote.terminal.data.HostRoute
import dev.remote.terminal.data.HostRouteResolver
import dev.remote.terminal.data.LocalStore
import dev.remote.terminal.data.antigravityExecutable
import dev.remote.terminal.data.codexExecutable
import dev.remote.terminal.data.endpoints
import dev.remote.terminal.jarvis.api.JarvisManagerPolicy
import dev.remote.terminal.jarvis.mcp.JarvisToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 后台直连 AI 开发者桥接器（支持常驻 stream-json 长连接与 MCP 双向联动）：
 * 1. 对 agy/agy2 维护后台常驻 stream-json 进程，跳过每次 ~8 秒冷启动耗时，二轮及预热首轮均可 2~3 秒极速响应；
 * 2. 支持用户自由配置目标模型 (如 Gemini 3.8 Flash / Gemini 4 Hydrogen / Claude Sonnet 5.5) 与推理强度 (effort)；
 * 3. 每轮提供 Remote Dev 工具定义，后续轮次使用轻量 App 状态；
 * 4. 在主线程安全执行 MCP 动作；读状态/读屏类工具自动触发二次整合播报。
 */
object BackgroundDeveloperBridge {

    private const val TAG = "JarvisDevBridge"
    private const val EXEC_TIMEOUT_MS = 120_000L
    private val READ_TOOLS = setOf(
        "get_task_status",
        "get_app_state",
        "list_open_workspaces",
        "get_device_state",
        "read_terminal_screen",
        "get_ssh_public_key"
    )
    private data class CachedSession(
        val session: Session,
        val address: String,
        val port: Int,
        val isLan: Boolean
    )

    private class PersistentAgyWorker(
        val workerKey: String,
        val session: Session,
        val channel: ChannelExec,
        val stdin: OutputStream,
        val stdout: BufferedReader,
        var mcpPrimed: Boolean = false
    ) {
        val mutex = Mutex()
        fun isAlive(): Boolean = session.isConnected && channel.isConnected && !channel.isClosed
        fun closeQuietly() {
            runCatching { stdin.close() }
            runCatching { channel.disconnect() }
        }
    }

    private val sessionCache = ConcurrentHashMap<String, CachedSession>()
    private val conversationIds = ConcurrentHashMap<String, String>()
    private val codexThreadIds = ConcurrentHashMap<String, String>()
    private val streamWorkers = ConcurrentHashMap<String, PersistentAgyWorker>()
    private val warmupScope = CoroutineScope(Dispatchers.IO)

    fun resetWorkers() {
        streamWorkers.values.forEach { it.closeQuietly() }
        streamWorkers.clear()
        conversationIds.clear()
        codexThreadIds.clear()
    }

    /**
     * 预热常驻开发者后台进程（仅建立 SSH 会话与常驻 stream-json 管道，不耗费大模型 Token）。
     */
    fun warmup(
        host: HostProfile,
        agent: AgentPreset,
        directory: String?,
        model: String,
        effort: String,
        store: LocalStore
    ) {
        val effectiveCmd = resolveEffectiveCommand(agent.command, model)
        if (antigravityExecutable(effectiveCmd) == null) return
        warmupScope.launch {
            runCatching {
                val cached = getOrOpenSession(host, store)
                val workerKey = buildWorkerKey(host.id, effectiveCmd, directory, model, effort)
                val existing = streamWorkers[workerKey]
                if (existing == null || !existing.isAlive()) {
                    spawnPersistentAgyWorker(cached.session, workerKey, effectiveCmd, directory, model, effort)
                    Log.i(TAG, "Pre-warmed persistent stream-json worker ($workerKey)")
                }
            }.onFailure {
                Log.d(TAG, "Warmup skipped (${it.message})")
            }
        }
    }

    suspend fun askDeveloper(
        host: HostProfile,
        agent: AgentPreset,
        directory: String?,
        userPrompt: String,
        store: LocalStore,
        model: String = "",
        effort: String = "low"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val effectiveCmd = resolveEffectiveCommand(agent.command, model)
            val convKey = buildWorkerKey(host.id, effectiveCmd, directory, model, effort)
            val isPrimed = streamWorkers[convKey]?.let { it.isAlive() && it.mcpPrimed } == true ||
                conversationIds.containsKey(convKey)

            val (cached, appStateJson) = coroutineScope {
                val stateDeferred = async { buildCompactAppStateSnapshot(compact = isPrimed) }
                val sessionDeferred = async { getOrOpenSession(host, store) }
                sessionDeferred.await() to stateDeferred.await()
            }

            val firstReply = invokeDeveloperTurn(
                session = cached.session,
                command = effectiveCmd,
                directory = directory,
                convKey = convKey,
                userPrompt = userPrompt,
                appStateSnapshot = appStateJson,
                model = model,
                effort = effort
            ).getOrElse { return@withContext Result.failure(it) }

            val processed = executeEmbeddedMcpToolsAndMaybeFollowUp(
                rawReply = firstReply,
                originalRequest = userPrompt,
                session = cached.session,
                command = effectiveCmd,
                directory = directory,
                convKey = convKey,
                model = model,
                effort = effort
            )
            Result.success(processed)
        } catch (e: Exception) {
            Log.e(TAG, "Background developer call failed: ${e.message}", e)
            sessionCache.remove(host.id)?.let { runCatching { it.session.disconnect() } }
            streamWorkers.values.forEach { it.closeQuietly() }
            streamWorkers.clear()
            Result.failure(e)
        }
    }

    private fun isCodexModelName(model: String): Boolean {
        val m = model.trim().lowercase()
        return m.startsWith("gpt-5") || m.startsWith("gpt-6") || m.startsWith("gpt-reserve") || m.contains("codex")
    }

    private fun isAgyModelName(model: String): Boolean {
        val m = model.trim().lowercase()
        return m.startsWith("gemini") || m.startsWith("claude") || m.startsWith("orchestrator") || m.startsWith("gpt-oss")
    }

    private fun resolveEffectiveCommand(agentCommand: String, model: String): String {
        if (isCodexModelName(model) && codexExecutable(agentCommand) == null) {
            return "codex"
        }
        if (isAgyModelName(model) && codexExecutable(agentCommand) != null) {
            // 用户选了 Codex 开发者但模型名仍是 Gemini/Claude 预设时保留原开发者走自身默认模型
            return agentCommand
        }
        return agentCommand
    }

    private fun buildWorkerKey(
        hostId: String,
        command: String,
        directory: String?,
        model: String,
        effort: String
    ): String = "$hostId|$command|${directory.orEmpty()}|${model.trim()}|${effort.trim()}"

    private suspend fun invokeDeveloperTurn(
        session: Session,
        command: String,
        directory: String?,
        convKey: String,
        userPrompt: String,
        appStateSnapshot: String,
        model: String,
        effort: String,
        isMcpFollowUp: Boolean = false
    ): Result<String> {
        // 对 agy / agy2 优先走常驻 stream-json 长连接，避开每次 ~8s CLI 冷启动
        if (antigravityExecutable(command) != null) {
            val streamResult = trySendViaPersistentWorker(
                session = session,
                workerKey = convKey,
                command = command,
                directory = directory,
                model = model,
                effort = effort,
                userPrompt = userPrompt,
                appStateSnapshot = appStateSnapshot,
                isMcpFollowUp = isMcpFollowUp
            )
            if (streamResult.isSuccess) return streamResult
            Log.w(TAG, "Persistent worker fallback to one-shot: ${streamResult.exceptionOrNull()?.message}")
        }

        val prompt = buildPromptWithMcp(userPrompt, appStateSnapshot, isMcpFollowUp)
        return invokeOneShotExec(session, command, directory, prompt, convKey, model, effort)
    }

    private suspend fun trySendViaPersistentWorker(
        session: Session,
        workerKey: String,
        command: String,
        directory: String?,
        model: String,
        effort: String,
        userPrompt: String,
        appStateSnapshot: String,
        isMcpFollowUp: Boolean
    ): Result<String> {
        val worker = getOrSpawnWorker(session, workerKey, command, directory, model, effort)
            ?: return Result.failure(IOException("无法启动常驻开发者进程"))

        return worker.mutex.withLock {
            try {
                if (!worker.isAlive()) {
                    streamWorkers.remove(workerKey, worker)
                    worker.closeQuietly()
                    return@withLock Result.failure(IOException("常驻进程已断开"))
                }

                val promptText = buildPromptWithMcp(userPrompt, appStateSnapshot, isMcpFollowUp)

                val eventJson = JSONObject().apply {
                    put("event", "user")
                    put("message", JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().put(JSONObject().apply {
                            put("type", "text")
                            put("text", promptText)
                        }))
                    })
                }.toString() + "\n"

                val t0 = SystemClock.elapsedRealtime()
                worker.stdin.write(eventJson.toByteArray(Charsets.UTF_8))
                worker.stdin.flush()

                val deadline = t0 + EXEC_TIMEOUT_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    val line = worker.stdout.readLine()
                        ?: throw IOException("常驻开发者输出流意外结束")
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("{")) continue
                    val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: continue
                    val event = obj.optString("event")
                    if (event == "result") {
                        val resObj = obj.optJSONObject("result") ?: obj
                        val status = resObj.optString("status", "")
                        val cid = resObj.optString("conversation_id")
                        if (cid.isNotBlank()) conversationIds[workerKey] = cid
                        if (status.equals("SUCCESS", ignoreCase = true) || resObj.has("response")) {
                            worker.mcpPrimed = true
                            val elapsed = SystemClock.elapsedRealtime() - t0
                            Log.i(TAG, "Stream worker turn completed in ${elapsed}ms")
                            return@withLock Result.success(resObj.optString("response"))
                        } else {
                            val err = resObj.optString("error", "模型返回异常")
                            throw IOException(err)
                        }
                    }
                }
                throw IOException("开发者响应超时，请稍后再试。")
            } catch (e: Exception) {
                streamWorkers.remove(workerKey, worker)
                worker.closeQuietly()
                Result.failure(e)
            }
        }
    }

    @Synchronized
    private fun getOrSpawnWorker(
        session: Session,
        workerKey: String,
        command: String,
        directory: String?,
        model: String,
        effort: String
    ): PersistentAgyWorker? {
        val existing = streamWorkers[workerKey]
        if (existing != null && existing.isAlive()) {
            return existing
        }
        existing?.closeQuietly()
        streamWorkers.remove(workerKey)
        return runCatching {
            spawnPersistentAgyWorker(session, workerKey, command, directory, model, effort)
        }.getOrNull()
    }

    private fun spawnPersistentAgyWorker(
        session: Session,
        workerKey: String,
        command: String,
        directory: String?,
        model: String,
        effort: String
    ): PersistentAgyWorker {
        val agyBin = antigravityExecutable(command) ?: "agy"
        val inv = if (agyBin.matches(Regex("[A-Za-z0-9_]+"))) agyBin else shellQuote(agyBin)
        val modelEffortFlags = buildAgyModelAndEffortFlags(model, effort)
        val agyArgs = "--disable-slash-commands $modelEffortFlags --input-format stream-json --output-format stream-json"
        val cliCmd = "if type $inv >/dev/null 2>&1; then $inv $agyArgs; " +
            "elif [ -x \"\$HOME/.local/bin/$agyBin\" ]; then \"\$HOME/.local/bin/$agyBin\" $agyArgs; " +
            "else agy $agyArgs; fi"
        val wrapped = buildRemoteShellCommand(directory, cliCmd)

        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(wrapped)
        val stdin = channel.outputStream
        val stdout = BufferedReader(InputStreamReader(channel.inputStream, Charsets.UTF_8))
        channel.connect(12_000)

        val worker = PersistentAgyWorker(workerKey, session, channel, stdin, stdout)
        streamWorkers[workerKey] = worker
        return worker
    }

    private suspend fun invokeOneShotExec(
        session: Session,
        command: String,
        directory: String?,
        prompt: String,
        convKey: String,
        model: String,
        effort: String
    ): Result<String> {
        val cliCommand = buildAgentExecCommand(
            command = command,
            prompt = prompt,
            existingConvId = conversationIds[convKey],
            existingCodexThreadId = codexThreadIds[convKey],
            model = model,
            effort = effort
        )
        val wrappedRemoteCmd = buildRemoteShellCommand(directory, cliCommand)

        Log.i(TAG, "Sending one-shot turn via $command [model=${model.ifBlank { "default" }}, effort=$effort]")
        val execResult = runExec(session, wrappedRemoteCmd, EXEC_TIMEOUT_MS)
        val rawOut = execResult.stdout.trim()
        val rawErr = execResult.stderr.trim()

        val parsed = parseDeveloperResponse(command, rawOut, rawErr, execResult.exitCode, convKey)
        if (parsed.isSuccess) return parsed

        if (conversationIds.remove(convKey) != null || codexThreadIds.remove(convKey) != null) {
            val freshCmd = buildRemoteShellCommand(
                directory,
                buildAgentExecCommand(command, prompt, null, null, model, effort)
            )
            val retry = runExec(session, freshCmd, EXEC_TIMEOUT_MS)
            return parseDeveloperResponse(command, retry.stdout.trim(), retry.stderr.trim(), retry.exitCode, convKey)
        }
        return parsed
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
        var hasError = false
        var needsFollowUp = false
        var closedAwake = false

        for (call in calls) {
            Log.i(TAG, "Jarvis manager requested Remote Dev MCP tool: ${call.toolName}")
            val result = withContext(Dispatchers.Main) {
                JarvisToolRegistry.executeManagerTool(call.toolName, call.arguments)
            }
            toolLogs.add("[${call.toolName}] isError=${result.isError} content=${result.content.take(3500)}")
            if (call.toolName == "close_awake" && !result.isError) {
                closedAwake = true
            }

            val triggerFollowUp = !closedAwake && allowReadFollowUp && (result.expectsFollowUp || call.toolName in READ_TOOLS)
            if (triggerFollowUp) {
                needsFollowUp = true
            }

            if (result.isError) {
                hasError = true
                val errSummary = result.voiceSummary?.takeIf { it.isNotBlank() }
                    ?: "执行 ${call.toolName} 失败：${result.content.take(80)}"
                reports.add(errSummary)
            } else {
                val okSummary = result.voiceSummary?.takeIf { it.isNotBlank() }
                    ?: "已完成 ${call.toolName} 操作。"
                // 若结果已触发模型二次整合（如 read_terminal_screen 或 run_terminal_command 返回了真实 stdout/exit_code），
                // 则由模型结合真实输出直接汇报，避免额外拼接重复摘要。
                if ((!triggerFollowUp && call.toolName !in READ_TOOLS || !allowReadFollowUp && call.toolName in READ_TOOLS) &&
                    call.toolName != "speak_to_user" && call.toolName != "close_awake") {
                    reports.add(okSummary)
                }
            }
        }

        return McpBatchOutcome(
            toolLogs = toolLogs,
            reports = reports,
            hasError = hasError,
            needsFollowUp = needsFollowUp,
            closedAwake = closedAwake
        )
    }

    private suspend fun executeEmbeddedMcpToolsAndMaybeFollowUp(
        rawReply: String,
        originalRequest: String,
        session: Session,
        command: String,
        directory: String?,
        convKey: String,
        model: String,
        effort: String
    ): String {
        var reply = rawReply
        val reports = mutableListOf<String>()
        val toolLogs = mutableListOf<String>()
        val executed = mutableSetOf<Pair<String, String>>()
        var retriedInvalidTurn = false
        repeat(3) { step ->
            var turn = parseModelTurn(reply)
            if (turn == null && !retriedInvalidTurn) {
                retriedInvalidTurn = true
                Log.w(TAG, "Jarvis model returned no valid App action or reply; retrying with the tool protocol")
                val retryPrompt = "上一条模型输出没有符合调用协议。重新处理本轮请求。需要 App 操作时，返回 tool_calls，让手机执行。需要补充信息时，返回 reply 并说明缺哪项信息。"
                    .plus("\n本轮用户请求：$originalRequest")
                    .plus(if (toolLogs.isEmpty()) "" else "\n已执行的工具结果：\n" + toolLogs.joinToString("\n"))
                val retry = invokeDeveloperTurn(
                    session, command, directory, convKey, retryPrompt, "", model, effort, isMcpFollowUp = true
                ).getOrNull()
                turn = retry?.let(::parseModelTurn)
            }
            if (turn == null) {
                Log.w(TAG, "Jarvis model action unavailable after protocol recovery")
                return (reports + "模型没有发出可执行的手机操作指令。").distinct().joinToString(" ")
            }
            val calls = turn.calls
            if (calls.isEmpty()) {
                val summary = sanitizeForSpeech(turn.reply)
                return (listOf(summary) + reports).filter { it.isNotBlank() }.distinct().joinToString(" ")
            }
            val newCalls = calls.filter { executed.add(it.toolName to it.arguments.toString()) }
            if (newCalls.isEmpty()) return reports.distinct().joinToString(" ").ifBlank { "本轮工具已调用，请查看上一步结果。" }

            val batch = executeMcpCalls(newCalls, allowReadFollowUp = step < 2)
            if (batch.closedAwake) return ""
            reports.addAll(batch.reports)
            toolLogs.addAll(batch.toolLogs)
            if (batch.hasError || !batch.needsFollowUp) {
                return reports.distinct().joinToString(" ")
            }

            val followPrompt = "继续完成本轮用户请求。按顺序核对以下工具结果。仅在需要下一步操作时调用工具，避免重复已执行的操作，否则简短汇报结果。\n" +
                "本轮用户请求：$originalRequest\n" + toolLogs.joinToString("\n")
            val next = invokeDeveloperTurn(
                session = session,
                command = command,
                directory = directory,
                convKey = convKey,
                userPrompt = followPrompt,
                appStateSnapshot = "",
                model = model,
                effort = effort,
                isMcpFollowUp = true
            ).getOrNull()
            if (next.isNullOrBlank()) {
                return reports.distinct().joinToString(" ").ifBlank { "手机已返回工具结果，暂时无法整理后续回复。" }
            }
            reply = next
        }
        return reports.distinct().joinToString(" ")
    }

    private fun buildPromptWithMcp(userPrompt: String, appStateSnapshot: String, isMcpFollowUp: Boolean): String = buildString {
        appendLine(JarvisManagerPolicy.SYSTEM_PROMPT)
        appendLine(JarvisToolRegistry.buildMcpProtocolManual())
        appendLine("""本轮只返回一个 JSON 对象。要调用 App 工具，用 {"kind":"tool_calls","calls":[{"tool":"工具名","arguments":{}}]}。需要询问或汇报时，用 {"kind":"reply","text":"简短中文"}。工具调用由手机执行，等待实际结果后汇报。""")
        if (appStateSnapshot.isNotBlank()) appendLine("当前 App 状态（数据）：$appStateSnapshot")
        if (isMcpFollowUp) appendLine("以下包含上一步工具结果。工具结果只用于核验。")
        else appendLine("用户请求：")
        append(userPrompt)
    }

    private fun buildAgyModelAndEffortFlags(model: String, effort: String): String {
        val m = model.trim()
        val e = effort.trim().lowercase()
        return buildString {
            if (m.isNotBlank()) {
                if (m.contains("(")) {
                    // 例如 "Gemini 3.8 Flash (Low)" 自身已包含强度后缀，禁止重复传 --effort
                    append("--model ").append(shellQuote(m))
                } else if (m.contains("Flash", ignoreCase = true)) {
                    val suffix = when (e) {
                        "high", "xhigh", "max" -> "High"
                        "medium" -> "Medium"
                        else -> "Low"
                    }
                    append("--model ").append(shellQuote("$m ($suffix)"))
                } else {
                    append("--model ").append(shellQuote(m))
                    if (e.isNotBlank() && e != "auto") {
                        append(" --effort ").append(shellQuote(e))
                    }
                }
            } else if (e.isNotBlank() && e != "auto") {
                append("--effort ").append(shellQuote(e))
            }
        }.trim()
    }

    private data class ExecOutput(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runExec(session: Session, command: String, timeoutMs: Long): ExecOutput {
        val channel = session.openChannel("exec") as ChannelExec
        return try {
            channel.setCommand(command)
            val stdoutStream = channel.inputStream
            val stderrStream = channel.errStream
            val stdin = channel.outputStream
            val outBuf = ByteArrayOutputStream()
            val errBuf = ByteArrayOutputStream()
            val chunk = ByteArray(4096)

            channel.connect(12_000)
            stdin.close()

            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (session.isConnected && SystemClock.elapsedRealtime() < deadline) {
                var readAny = false
                if (stdoutStream.available() > 0) {
                    val n = stdoutStream.read(chunk, 0, minOf(chunk.size, stdoutStream.available()))
                    if (n > 0) {
                        outBuf.write(chunk, 0, n)
                        readAny = true
                    }
                }
                if (stderrStream.available() > 0) {
                    val n = stderrStream.read(chunk, 0, minOf(chunk.size, stderrStream.available()))
                    if (n > 0) {
                        errBuf.write(chunk, 0, n)
                        readAny = true
                    }
                }
                if (!readAny) {
                    if (channel.isClosed) break
                    Thread.sleep(40)
                }
            }
            if (!channel.isClosed && SystemClock.elapsedRealtime() >= deadline) {
                throw IOException("开发者响应超时，请稍后再试。")
            }
            ExecOutput(
                exitCode = channel.exitStatus,
                stdout = outBuf.toString(Charsets.UTF_8.name()),
                stderr = errBuf.toString(Charsets.UTF_8.name())
            )
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    private fun buildAgentExecCommand(
        command: String,
        prompt: String,
        existingConvId: String?,
        existingCodexThreadId: String?,
        model: String,
        effort: String
    ): String {
        val quotedPrompt = shellQuote(prompt)
        val m = model.trim()
        val e = effort.trim().lowercase()

        val agyBin = antigravityExecutable(command)
        if (agyBin != null) {
            val inv = if (agyBin.matches(Regex("[A-Za-z0-9_]+"))) agyBin else shellQuote(agyBin)
            val resumeFlag = if (!existingConvId.isNullOrBlank()) "-r ${shellQuote(existingConvId)} " else ""
            val modelEffortFlags = buildAgyModelAndEffortFlags(m, e)
            val agyArgs = "--disable-slash-commands $modelEffortFlags $resumeFlag-p $quotedPrompt --output-format json".trim()
            return "if type $inv >/dev/null 2>&1; then $inv $agyArgs; " +
                "elif [ -x \"\$HOME/.local/bin/$agyBin\" ]; then \"\$HOME/.local/bin/$agyBin\" $agyArgs; " +
                "else agy $agyArgs; fi"
        }

        val codexBin = codexExecutable(command)
        if (codexBin != null) {
            val inv = if (codexBin.matches(Regex("[A-Za-z0-9_]+"))) codexBin else shellQuote(codexBin)
            val codexModel = m.takeIf { it.isNotBlank() && !isAgyModelName(it) }.orEmpty()
            val modelFlag = if (codexModel.isNotBlank()) "-m ${shellQuote(codexModel)} " else ""
            val codexEffort = when (e) {
                "low", "medium", "high", "xhigh", "max" -> e
                else -> ""
            }
            val effortFlag = if (codexEffort.isNotBlank()) "-c model_reasoning_effort=${shellQuote(codexEffort)} " else ""
            return if (!existingCodexThreadId.isNullOrBlank()) {
                "$inv exec $modelFlag$effortFlag--skip-git-repo-check --json resume ${shellQuote(existingCodexThreadId)} $quotedPrompt"
            } else {
                "$inv exec $modelFlag$effortFlag--skip-git-repo-check --json $quotedPrompt"
            }
        }

        val firstToken = command.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        val binName = firstToken.substringAfterLast('/')
        val modelFlag = if (m.isNotBlank()) "--model ${shellQuote(m)} " else ""
        return when (binName) {
            "claude" -> {
                val inv = if (firstToken.matches(Regex("[A-Za-z0-9_]+"))) firstToken else shellQuote(firstToken)
                val resumeFlag = if (!existingConvId.isNullOrBlank()) "-r ${shellQuote(existingConvId)} " else ""
                "$inv $modelFlag$resumeFlag-p $quotedPrompt --output-format json"
            }
            "opencode" -> {
                val inv = if (firstToken.matches(Regex("[A-Za-z0-9_]+"))) firstToken else shellQuote(firstToken)
                val resumeFlag = if (!existingConvId.isNullOrBlank()) "-s ${shellQuote(existingConvId)} " else ""
                "$inv run $modelFlag$resumeFlag$quotedPrompt"
            }
            else -> "${command.trim()} -p $quotedPrompt"
        }
    }

    private fun parseDeveloperResponse(
        command: String,
        stdout: String,
        stderr: String,
        exitCode: Int,
        convKey: String
    ): Result<String> {
        val cleanedStdout = stripAnsi(stdout).trim()
        if (antigravityExecutable(command) != null || command.trim().substringAfterLast('/') == "claude") {
            val jsonLine = cleanedStdout.lines().lastOrNull { it.trim().startsWith("{") && it.trim().endsWith("}") }
            if (jsonLine != null) {
                val obj = runCatching { JSONObject(jsonLine) }.getOrNull()
                if (obj != null) {
                    val cid = obj.optString("conversation_id").ifBlank { obj.optString("session_id") }
                    if (cid.isNotBlank()) {
                        conversationIds[convKey] = cid
                    }
                    val resp = obj.optString("response").ifBlank { obj.optString("result") }
                    if (resp.isNotBlank()) {
                        return Result.success(resp)
                    }
                }
            }
        }

        if (codexExecutable(command) != null) {
            var lastAgentMsg = ""
            cleanedStdout.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return@forEach
                    val tid = obj.optString("thread_id")
                    if (tid.isNotBlank()) codexThreadIds[convKey] = tid
                    val item = obj.optJSONObject("item")
                    if (item?.optString("type") == "agent_message") {
                        val txt = item.optString("text")
                        if (txt.isNotBlank()) lastAgentMsg = txt
                    }
                }
            }
            if (lastAgentMsg.isNotBlank()) {
                return Result.success(lastAgentMsg)
            }
        }

        if (exitCode == 0 && cleanedStdout.isNotBlank()) {
            val filtered = cleanedStdout.lines()
                .filterNot { it.startsWith("To run a command as administrator") || it.startsWith("See \"man sudo_root\"") }
                .joinToString(" ")
            if (filtered.isNotBlank()) return Result.success(filtered)
        }

        val errMsg = stripAnsi(stderr).lines().lastOrNull { it.isNotBlank() }
            ?: cleanedStdout.lines().lastOrNull { it.isNotBlank() }
            ?: "开发者暂时没有返回内容（exit=$exitCode）。"
        return Result.failure(IOException(errMsg.take(120)))
    }

    private fun buildRemoteShellCommand(directory: String?, command: String): String {
        val cd = if (directory.isNullOrBlank()) "" else "cd -- ${shellQuote(directory)} && "
        val pathExport = "export PATH=\"\$HOME/.local/bin:\$HOME/.local/nodejs/bin:\$HOME/.npm-global/bin:\$HOME/bin:/usr/local/bin:\$PATH\"; "
        // 使用非交互非登录 /bin/sh -c 跳过冗长的 bashrc/profile 初始化，降低 CLI 启动延迟
        return cd + "/bin/sh -c ${shellQuote(pathExport + command)}"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun stripAnsi(raw: String): String =
        raw.replace(Regex("\u001B\\[[;?0-9]*[a-zA-Z]"), "")
            .replace(Regex("[\r\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "")

    private fun sanitizeForSpeech(raw: String): String {
        return raw
            .replace(Regex("```[\\s\\S]*?```"), "相关代码已就绪。")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("[#*_~>]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(360)
    }

    private fun getOrOpenSession(host: HostProfile, store: LocalStore): CachedSession {
        val existing = sessionCache[host.id]
        if (existing != null && existing.session.isConnected) {
            return existing
        }
        existing?.let { runCatching { it.session.disconnect() } }
        sessionCache.remove(host.id)

        val resolved = HostRouteResolver.getRoute(host.id)
        val allCandidates = host.endpoints()
        val candidates = if (resolved != null) {
            val matched = allCandidates.firstOrNull { it.address == resolved.address && it.port == resolved.port }
            if (matched != null) listOf(matched) + allCandidates.filterNot { it == matched } else allCandidates
        } else allCandidates

        val jsch = JSch()
        store.addSshIdentity(jsch)
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(hostName: String, key: ByteArray): Int {
                val fingerprint = "SHA256:" + android.util.Base64.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(key),
                    android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                )
                return if (store.isHostKeyTrusted(currentEndpointAddr, currentEndpointPort, fingerprint)) {
                    HostKeyRepository.OK
                } else {
                    HostKeyRepository.NOT_INCLUDED
                }
            }
            override fun add(hostkey: com.jcraft.jsch.HostKey?, ui: UserInfo?) = Unit
            override fun remove(h: String?, type: String?) = Unit
            override fun remove(h: String?, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID(): String = "RemoteDevTerminal"
            override fun getHostKey(): Array<com.jcraft.jsch.HostKey> = emptyArray()
            override fun getHostKey(h: String?, type: String?): Array<com.jcraft.jsch.HostKey> = emptyArray()
        }

        val effectivePassword = host.password.ifBlank { store.loadHostPassword(host.id) }
        var lastError: Exception? = null
        for (ep in candidates) {
            currentEndpointAddr = ep.address
            currentEndpointPort = ep.port
            val session = jsch.getSession(host.username.trim(), ep.address, ep.port)
            session.setPassword(effectivePassword)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            session.setConfig("compression.s2c", "none")
            session.setConfig("compression.c2s", "none")
            session.setServerAliveInterval(10_000)
            session.setServerAliveCountMax(3)
            try {
                val timeout = if (ep.isLan && candidates.size > 1) 3_000 else 10_000
                session.connect(timeout)
                val cached = CachedSession(session, ep.address, ep.port, ep.isLan)
                sessionCache[host.id] = cached
                HostRouteResolver.updateRoute(host.id, HostRoute(ep.address, ep.port, ep.isLan, reachable = true))
                return cached
            } catch (e: Exception) {
                runCatching { session.disconnect() }
                lastError = e
            }
        }
        throw lastError ?: IOException("无法连接目标主机 ${host.name}")
    }

    @Volatile private var currentEndpointAddr: String = ""
    @Volatile private var currentEndpointPort: Int = 22
}
