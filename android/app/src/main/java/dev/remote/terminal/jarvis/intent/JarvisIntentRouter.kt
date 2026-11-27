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
    private val syncTokenProvider: () -> String = { "" }
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
            """(?i)(?:切换到|切到|换到|看下|查看|进入|打开)\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\.]+)"""
        )

        private val StatusQueryRegex = Regex(
            """(?i)(?:什么情况|进度|好了吗|完成了吗|状态|当前|情况怎么样|status)"""
        )

        private val HelpRegex = Regex(
            """(?i)(?:你能做什么|帮助|功能|指令|help|who are you)"""
        )
    }

    override suspend fun resolveIntent(text: String, context: JarvisContext): JarvisAction = withContext(Dispatchers.Default) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return@withContext JarvisAction.None

        Log.d(TAG, "Resolving intent for input: \"$cleanText\"")

        // 1. Tier-1 本地 0-Token 拦截
        if (ConfirmRegex.matches(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Confirm / Enter (0 Token)")
            return@withContext JarvisAction.InjectKeystroke("\n", "好的，已确认执行。")
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

        if (StatusQueryRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Status Query (0 Token)")
            val summary = generateLocalStatusSummary(context)
            return@withContext JarvisAction.QueryStatus(summary)
        }

        if (HelpRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Help Query (0 Token)")
            return@withContext JarvisAction.DirectReply("先生，我是贾维斯。您可以吩咐我切换工作区、确认授权推进，或直接交代开发调试任务。")
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
            // 没有配置中枢同步服务器时，默认兜底直接将口语转化为终端回车命令
            Log.w(TAG, "No sync server configured for LLM parsing, injecting speech as command")
            return@withContext JarvisAction.InjectKeystroke("$userPrompt\n", "好的先生，已将任务下发到当前终端。")
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

            val payload = JSONObject().apply {
                put("voice_input", userPrompt)
                put("context", JSONObject().apply {
                    put("current_workspace", context.currentWorkspaceName ?: "")
                    put("workspaces", org.json.JSONArray(context.workspaces))
                    put("agents", org.json.JSONArray(context.agents))
                })
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

                if (targetWs.isNotBlank() && targetWs != context.currentWorkspaceName) {
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
