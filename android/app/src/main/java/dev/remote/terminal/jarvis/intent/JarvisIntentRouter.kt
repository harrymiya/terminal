package dev.remote.terminal.jarvis.intent

import android.util.Log
import dev.remote.terminal.jarvis.api.IntentRouter
import dev.remote.terminal.jarvis.api.JarvisAction
import dev.remote.terminal.jarvis.api.JarvisContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极省 Token 的分层意图路由中枢。
 * 1. Tier-1 本地快速规则拦截 (0 Token, < 5ms 响应): 覆盖回车确认、中止、切换会话、进度查询。
 * 2. Tier-2 骨架式元数据压缩派发: 仅向轻量云端大模型发送工作区与 Agent 名单，不上传繁重的终端屏幕输出。
 */
class JarvisIntentRouter(
    private val syncUrlProvider: () -> String = { "" },
    private val syncTokenProvider: () -> String = { "" },
    private val wakeWordProvider: () -> String = { "贾维斯" }
) : IntentRouter {

    companion object {
        private const val TAG = "JarvisIntentRouter"

        // 本地极速意图匹配正则 (Tier-1: 0 Token)
        private val ConfirmRegex = Regex(
            """(?i)^\s*(?:允许|确认|同意|继续|执行|跑吧|回车|好的|可以|对|嗯|好|行|确定|approve|yes|confirm|proceed|ok|enter)\s*[:?？!！.。]*$"""
        )

        private val CancelRegex = Regex(
            """(?i)^\s*(?:取消|停止|中止|算了|别动|停|住手|别执行|cancel|stop|abort|no|quit)\s*[:?？!！.。]*$"""
        )

        private val SwitchWorkspaceRegex = Regex(
            """(?i)^\s*(?:切换到|切到|换到|打开工作区|切到工作区)\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\.]+)\s*[。！!]*$"""
        )

        private val StatusQueryRegex = Regex(
            """(?i)^\s*(?:现在|目前)?(?:什么情况|进度如何|进度怎么样|好了吗|完成了吗|当前状态|状态怎么样|status)[?？!！.。]*$"""
        )

        private val ReadScreenRegex = Regex(
            """(?i)(?:读屏幕|看屏幕|念屏幕|屏幕内容|显示什么|输出什么)"""
        )

        private val ReconnectRegex = Regex(
            """(?i)(?:重连|重新连接|断线重连)"""
        )

        private val CloseTerminalRegex = Regex(
            """(?i)(?:关闭终端|关掉终端|关掉会话|关闭会话)"""
        )

        private val TiledViewRegex = Regex(
            """(?i)(?:切换平铺|平铺模式|变成平铺)"""
        )

        private val TabViewRegex = Regex(
            """(?i)(?:切换标签|标签模式|变成标签)"""
        )

        private val SyncRegex = Regex(
            """(?i)(?:触发同步|立即同步|同步一下|同步数据)"""
        )

        private val PublicKeyRegex = Regex(
            """(?i)(?:读取公钥|查看公钥|我的公钥|公钥是什么)"""
        )

        private val AppStateRegex = Regex(
            """(?i)(?:系统状态|应用状态|全部状态|整体状态)"""
        )

        private val TalkToAgentRegex = Regex(
            """(?i)^(?:跟|向|让|对|问一下|问问|告诉|让开发者)\s*([a-zA-Z0-9_\u4e00-\u9fa5\-]+)?\s*(?:说|聊|问|查|做|执行|看一下|分析下|排查下)?[\s:：,，]*(.+)"""
        )

        private val SwitchHostRegex = Regex(
            """(?i)(?:切换到主机|换到主机|切到主机|换主机)\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\.]+)"""
        )

        private val HelpRegex = Regex(
            """(?i)(?:你能做什么|帮助|功能|指令|help|who are you)"""
        )

        private val GreetingRegex = Regex(
            """(?i)^\s*(?:在吗|你在吗|在不在|你好|您好|听得到吗|听得见吗|能听见吗|能听到吗|早上好|晚上好|喂)[?？!！.。]*${'$'}"""
        )

        private val DIGIT_MAP = mapOf(
            '零' to '0', '一' to '1', '二' to '2', '两' to '2', '三' to '3',
            '四' to '4', '五' to '5', '六' to '6', '七' to '7', '八' to '8', '九' to '9'
        )
    }

    fun wakeVariants(): List<String> {
        val configured = wakeWordProvider().trim()
        val items = linkedSetOf<String>()
        if (configured.isNotBlank()) {
            items.add(configured)
            val mapped = buildString {
                configured.forEach { ch -> append(DIGIT_MAP[ch] ?: ch) }
            }
            if (mapped != configured) items.add(mapped)
        }
        items.addAll(listOf("贾维斯", "查维斯", "加维斯", "家维斯", "贾维", "jarvis"))
        return items.sortedByDescending { it.length }
    }

    fun containsWakeWord(text: String): Boolean {
        val lower = text.trim().lowercase()
        return wakeVariants().any { w -> lower.contains(w.lowercase()) }
    }

    private fun buildWakePattern(): String =
        wakeVariants().joinToString("|") { Regex.escape(it) }

    override suspend fun resolveIntent(text: String, context: JarvisContext): JarvisAction = withContext(Dispatchers.Default) {
        val rawText = text.trim()
        if (rawText.isBlank()) return@withContext JarvisAction.None

        Log.d(TAG, "Resolving intent for input: \"$rawText\"")

        val wakePattern = buildWakePattern()
        val pureWakeRegex = Regex(
            """^(?:(?:喂|嘿|嗨|你好|哈喽|请问|呼叫|胡教|胡作|网络|叫一声|叫下|叫)\s*)*(?:$wakePattern)[，,、\s!！?？.。]*${'$'}""",
            RegexOption.IGNORE_CASE
        )
        if (pureWakeRegex.matches(rawText)) {
            Log.i(TAG, "Pure wake word matched for \"${wakeWordProvider()}\"")
            return@withContext JarvisAction.DirectReply("我在，请讲。")
        }

        // 去除指令句首的唤醒词前缀（如："老六，查看状态" -> "查看状态"）
        val wakeWordPrefix = Regex("""^(?:(?:喂|嘿|嗨|哈喽|呼叫|胡教|胡作|网络|叫)\s*)*(?:$wakePattern)[，,、\s:：]*""", RegexOption.IGNORE_CASE)
        val cleanText = rawText.replace(wakeWordPrefix, "").trim().ifBlank { rawText }

        if (GreetingRegex.matches(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Greeting / Presence check (0 Token)")
            return@withContext JarvisAction.DirectReply("我在，听得非常清楚，请指示。")
        }

        // 1. Tier-1 本地 0-Token 拦截
        if (ConfirmRegex.matches(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Confirm / Enter (0 Token)")
            return@withContext JarvisAction.InjectKeystroke("\r", "好的，已确认执行。")
        }

        if (CancelRegex.matches(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Cancel / SIGINT (0 Token)")
            return@withContext JarvisAction.InjectKeystroke("\u0003", "已为您中止当前进程。")
        }

        val switchMatch = SwitchWorkspaceRegex.find(cleanText)
        if (switchMatch != null) {
            val targetRaw = switchMatch.groupValues[1].trim()
            val matchedWorkspace = context.workspaces.firstOrNull { ws ->
                ws.equals(targetRaw, ignoreCase = true) || ws.contains(targetRaw, ignoreCase = true)
            }
            if (matchedWorkspace != null) {
                Log.i(TAG, "Tier-1 matched: Switch Workspace to $matchedWorkspace (0 Token)")
                return@withContext JarvisAction.SwitchWorkspace(matchedWorkspace, "已为您切换至 $matchedWorkspace 工作区。")
            }
        }

        if (ReadScreenRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP read_terminal_screen (0 Token)")
            return@withContext JarvisAction.ExecuteTool("read_terminal_screen", JSONObject().put("lines", 15))
        }

        if (ReconnectRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP reconnect_terminal (0 Token)")
            return@withContext JarvisAction.ExecuteTool("reconnect_terminal")
        }

        if (CloseTerminalRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP close_terminal (0 Token)")
            return@withContext JarvisAction.ExecuteTool("close_terminal")
        }

        if (TiledViewRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP set_view_mode (tiled=true, 0 Token)")
            return@withContext JarvisAction.ExecuteTool("set_view_mode", JSONObject().put("tiled", true))
        }

        if (TabViewRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP set_view_mode (tiled=false, 0 Token)")
            return@withContext JarvisAction.ExecuteTool("set_view_mode", JSONObject().put("tiled", false))
        }

        if (SyncRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP trigger_sync (0 Token)")
            return@withContext JarvisAction.ExecuteTool("trigger_sync")
        }

        if (PublicKeyRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP get_ssh_public_key (0 Token)")
            return@withContext JarvisAction.ExecuteTool("get_ssh_public_key")
        }

        if (AppStateRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP get_app_state (0 Token)")
            return@withContext JarvisAction.ExecuteTool("get_app_state")
        }

        val hostMatch = SwitchHostRegex.find(cleanText)
        if (hostMatch != null) {
            val targetHost = hostMatch.groupValues[1].trim()
            Log.i(TAG, "Tier-1 matched: MCP select_host to $targetHost (0 Token)")
            return@withContext JarvisAction.ExecuteTool("select_host", JSONObject().put("host_name", targetHost))
        }

        val talkMatch = TalkToAgentRegex.find(cleanText)
        if (talkMatch != null) {
            val rawAgent = talkMatch.groupValues[1].trim()
            val prompt = talkMatch.groupValues[2].trim()
            val matchesConfiguredAgent = rawAgent.isNotBlank() && context.agents.any {
                it.equals(rawAgent, ignoreCase = true) || it.contains(rawAgent, ignoreCase = true)
            }
            if (matchesConfiguredAgent && prompt.isNotBlank()) {
                Log.i(TAG, "Tier-1 matched: Talk to agent [$rawAgent]: \"$prompt\"")
                return@withContext JarvisAction.ChatWithDeveloper(
                    agentName = rawAgent,
                    message = prompt,
                    voiceReply = "好的先生，正在与${rawAgent}沟通。"
                )
            }
        }

        if (StatusQueryRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Status Query (0 Token)")
            val summary = generateLocalStatusSummary(context)
            return@withContext JarvisAction.QueryStatus(summary)
        }

        if (HelpRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Help Query (0 Token)")
            return@withContext JarvisAction.DirectReply("先生，我是贾维斯。已为您接入全套 MCP 工具集，您可以吩咐我读屏幕、切换主机、重连会话、平铺布局，或派发后台开发任务。")
        }

        // 2. Tier-2 复杂开放式指令：压缩元数据后由服务端轻量模型处理
        return@withContext resolveWithCompressedLlm(cleanText, context)
    }

    private fun generateLocalStatusSummary(context: JarvisContext): String {
        val ws = context.currentWorkspaceName ?: "当前工作区"
        val lastOutput = context.lastTerminalOutput?.trim() ?: ""
        val lastLine = lastOutput.lines().lastOrNull { it.isNotBlank() } ?: "暂无输出"
        val sanitizedLine = lastLine.take(40)
        return "先生，${ws} 终端最后状态为：$sanitizedLine"
    }

    /**
     * 骨架式元数据请求：不传全量终端日志，仅传当前环境与工作区列表，消耗极低 Token。
     */
    private suspend fun resolveWithCompressedLlm(userPrompt: String, context: JarvisContext): JarvisAction = withContext(Dispatchers.IO) {
        val serverUrl = syncUrlProvider().trimEnd('/')
        val token = syncTokenProvider()
        if (serverUrl.isBlank()) {
            // 没有配置中枢同步服务器时，默认直接借力当前终端正在运行的 AI 开发者
            Log.w(TAG, "No sync server configured for LLM parsing, routing as ChatWithDeveloper")
            return@withContext JarvisAction.ChatWithDeveloper(null, userPrompt, "好的先生，已将任务交由当前开发者处理。")
        }

        try {
            val endpoint = URL("$serverUrl/v1/jarvis/dispatch")
            val conn = endpoint.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 7000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }

            val appState = runCatching { dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.appStateProvider?.invoke() }.getOrNull()
            val payload = JSONObject().apply {
                put("voice_input", userPrompt)
                put("context", JSONObject().apply {
                    put("current_workspace", context.currentWorkspaceName ?: "")
                    put("workspaces", org.json.JSONArray(context.workspaces))
                    put("agents", org.json.JSONArray(context.agents))
                    if (appState != null) {
                        put("app_state", appState)
                    }
                })
                // 将完整的 Android MCP 协议能力、操作方法说明与结构化 Schema 作为上下文发送给调度端
                put("mcp_protocol_manual", dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.buildMcpProtocolManual(compact = false))
                put("available_tools", dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.buildMcpToolsSchemaJson())
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseStr)
                val voiceReply = json.optString("voice_reply", "已为您处理。")
                val cmd = json.optString("command", "")
                val targetWs = json.optString("target_workspace", "")

                if (json.has("tool")) {
                    val toolName = json.getString("tool")
                    val toolArgs = json.optJSONObject("arguments") ?: JSONObject()
                    JarvisAction.ExecuteTool(toolName, toolArgs, voiceReply)
                } else if (targetWs.isNotBlank() && targetWs != context.currentWorkspaceName) {
                    JarvisAction.SwitchWorkspace(targetWs, voiceReply)
                } else if (cmd.isNotBlank()) {
                    JarvisAction.InjectKeystroke(cmd.let { if (it.endsWith("\n")) it else "$it\n" }, voiceReply)
                } else {
                    JarvisAction.DirectReply(voiceReply)
                }
            } else {
                Log.w(TAG, "Jarvis dispatch server returned HTTP $responseCode, fallback to direct input")
                JarvisAction.InjectKeystroke("$userPrompt\n", "好的先生，已为您提交任务。")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch via remote LLM: ${e.message}")
            JarvisAction.InjectKeystroke("$userPrompt\n", "网络请求异常，已直接将指令注入当前终端。")
        }
    }
}
