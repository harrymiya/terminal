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
import dev.remote.terminal.jarvis.mcp.JarvisToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 后台直连 AI 开发者桥接器（支持 MCP 双向联动）：
 * 1. 不依赖界面中是否打开终端窗口，直接通过后台 SSH ChannelExec 唤醒远端主机上的开发者 CLI；
 * 2. 将手机端 Remote Dev 全部 MCP 操作能力与实时状态注入开发者上下文，允许开发者通过 [[RDT_MCP:...]] 调度 App；
 * 3. 在主线程安全执行 MCP 动作；若是读取/查询类工具，自动把结果回传给开发者做第二轮整合播报；
 * 4. 保持多轮 conversation_id 连续上下文，返回精简、无思维链的纯语音友好文本。
 */
object BackgroundDeveloperBridge {

    private const val TAG = "JarvisDevBridge"
    private const val EXEC_TIMEOUT_MS = 60_000L
    private val MCP_TAG_REGEX = Regex("""\[\[RDT_MCP:(\{.*?\})\]\]""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val READ_TOOLS = setOf("get_app_state", "get_device_state", "read_terminal_screen", "get_ssh_public_key")

    private data class CachedSession(
        val session: Session,
        val address: String,
        val port: Int,
        val isLan: Boolean
    )

    private val sessionCache = ConcurrentHashMap<String, CachedSession>()
    private val conversationIds = ConcurrentHashMap<String, String>()
    private val codexThreadIds = ConcurrentHashMap<String, String>()

    fun clearConversations() {
        conversationIds.clear()
        codexThreadIds.clear()
    }

    suspend fun askDeveloper(
        host: HostProfile,
        agent: AgentPreset,
        directory: String?,
        userPrompt: String,
        store: LocalStore
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cached = getOrOpenSession(host, store)
            val convKey = "${host.id}:${agent.command}:${directory.orEmpty()}"
            val appStateJson = withContext(Dispatchers.Main) {
                runCatching { JarvisToolRegistry.appStateProvider?.invoke()?.toString() }.getOrNull()
            }.orEmpty().take(1800)

            val fullPrompt = buildPromptWithMcp(userPrompt, appStateJson)
            val firstReply = invokeDeveloperOnce(cached.session, agent.command, directory, fullPrompt, convKey)
                .getOrElse { return@withContext Result.failure(it) }

            val processed = executeEmbeddedMcpToolsAndMaybeFollowUp(
                rawReply = firstReply,
                session = cached.session,
                command = agent.command,
                directory = directory,
                convKey = convKey
            )
            Result.success(processed)
        } catch (e: Exception) {
            Log.e(TAG, "Background developer call failed: ${e.message}", e)
            sessionCache.remove(host.id)?.let { runCatching { it.session.disconnect() } }
            Result.failure(e)
        }
    }

    private suspend fun invokeDeveloperOnce(
        session: Session,
        command: String,
        directory: String?,
        prompt: String,
        convKey: String
    ): Result<String> {
        val cliCommand = buildAgentExecCommand(
            command = command,
            prompt = prompt,
            existingConvId = conversationIds[convKey],
            existingCodexThreadId = codexThreadIds[convKey]
        )
        val wrappedRemoteCmd = buildRemoteShellCommand(directory, cliCommand)

        Log.i(TAG, "Sending voice turn via $command [dir=$directory, conv=${conversationIds[convKey]?.take(8) ?: "new"}]")
        val execResult = runExec(session, wrappedRemoteCmd, EXEC_TIMEOUT_MS)
        val rawOut = execResult.stdout.trim()
        val rawErr = execResult.stderr.trim()
        Log.d(TAG, "Exec exit=${execResult.exitCode}, outLen=${rawOut.length}, errLen=${rawErr.length}")

        val parsed = parseDeveloperResponse(command, rawOut, rawErr, execResult.exitCode, convKey)
        if (parsed.isSuccess) return parsed

        // 若复用旧 conversation_id 失效，自动开启新会话重试一次
        if (conversationIds.remove(convKey) != null || codexThreadIds.remove(convKey) != null) {
            val freshCmd = buildRemoteShellCommand(
                directory,
                buildAgentExecCommand(command, prompt, null, null)
            )
            val retry = runExec(session, freshCmd, EXEC_TIMEOUT_MS)
            return parseDeveloperResponse(command, retry.stdout.trim(), retry.stderr.trim(), retry.exitCode, convKey)
        }
        return parsed
    }

    private suspend fun executeEmbeddedMcpToolsAndMaybeFollowUp(
        rawReply: String,
        session: Session,
        command: String,
        directory: String?,
        convKey: String
    ): String {
        val matches = MCP_TAG_REGEX.findAll(rawReply).toList()
        if (matches.isEmpty()) {
            return sanitizeForSpeech(rawReply)
        }

        val toolResults = mutableListOf<String>()
        var needsFollowUp = false
        var lastVoiceSummary: String? = null

        for (match in matches) {
            val jsonStr = match.groupValues[1].trim()
            val callObj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: continue
            val toolName = callObj.optString("tool").trim()
            if (toolName.isBlank()) continue
            val args = callObj.optJSONObject("arguments") ?: JSONObject()

            Log.i(TAG, "Developer requested Remote Dev MCP tool: $toolName args=$args")
            val result = withContext(Dispatchers.Main) {
                JarvisToolRegistry.executeTool(toolName, args)
            }
            toolResults.add("[$toolName] isError=${result.isError} content=${result.content.take(1200)}")
            if (!result.voiceSummary.isNullOrBlank()) {
                lastVoiceSummary = result.voiceSummary
            }
            if (toolName in READ_TOOLS && !result.isError) {
                needsFollowUp = true
            }
        }

        val visibleWithoutTags = rawReply.replace(MCP_TAG_REGEX, "").trim()
        if (needsFollowUp && toolResults.isNotEmpty()) {
            val followPrompt = "这是刚调用手机端 MCP 返回的实际结果，请直接用1到3句精简中文口语回答用户，不要再输出思维链、Markdown 或 RDT_MCP 标签：\n" +
                toolResults.joinToString("\n")
            val followReply = invokeDeveloperOnce(session, command, directory, followPrompt, convKey).getOrNull()
            if (!followReply.isNullOrBlank()) {
                // 若第二轮又附带了动作类 MCP，也一并执行
                val secondMatches = MCP_TAG_REGEX.findAll(followReply).toList()
                for (m in secondMatches) {
                    val obj = runCatching { JSONObject(m.groupValues[1].trim()) }.getOrNull() ?: continue
                    val tn = obj.optString("tool").trim()
                    if (tn.isNotBlank() && tn !in READ_TOOLS) {
                        val a = obj.optJSONObject("arguments") ?: JSONObject()
                        withContext(Dispatchers.Main) { JarvisToolRegistry.executeTool(tn, a) }
                    }
                }
                return sanitizeForSpeech(followReply.replace(MCP_TAG_REGEX, ""))
            }
        }

        return sanitizeForSpeech(visibleWithoutTags).ifBlank { lastVoiceSummary ?: "操作已完成。" }
    }

    private fun buildPromptWithMcp(userPrompt: String, appStateSnapshot: String): String {
        val stateLine = if (appStateSnapshot.isNotBlank()) "当前手机端状态快照：$appStateSnapshot\n" else ""
        return """你是用户的 AI 开发者助手，同时拥有手机端 Remote Dev App 的全部 MCP 操作权限（你可以替用户做任何人能做的界面和终端操作）。
可用 MCP 工具（需要执行操作时，请在回复末尾附带一行或多行 [[RDT_MCP:{"tool":"工具名","arguments":{...}}]]）：
- get_app_state() // 获取主机、工作区、开发者列表与所有终端会话状态
- select_host(host_id?, host_name?, host_index?) // 切换选中主机
- select_agent(agent_id?, agent_name?) // 切换选中开发者预设
- open_terminal(host_id?, workspace_path?, workspace_name?, agent_command?) // 在手机端打开指定工作区与开发者终端
- focus_terminal(session_id?, session_index?) // 聚焦切换到某个已打开的终端标签
- close_terminal(session_id?, session_index?) // 关闭终端会话
- reconnect_terminal(session_id?) // 重连已断开的终端
- send_terminal_text(text, session_id?, press_enter?) // 向打开的终端发送文本并回车
- send_terminal_keys(keys, session_id?) // 发送按键如 enter, tab, escape, ctrl+c, ctrl+d, up, down
- read_terminal_screen(session_id?, lines?) // 读取某终端最近输出
- set_view_mode(tiled) // true=平铺视图, false=标签视图
- add_workspace(path, name?, host_id?) / delete_workspace(path, host_id?) // 增删工作区
- trigger_sync() // 立即同步主机与会话
- navigate_screen(screen) // 跳转页面: home / terminal / settings / manage / agents / logs
- vibrate_phone(duration_ms?) / get_device_state() // 震动提醒或获取手机电量状态
${stateLine}规则：直接用1到3句精简中文口语回答（不要输出思维链、过程说明或Markdown代码块）；如果用户让你查看手机/终端状态或操作 Remote Dev App，直接附带对应 [[RDT_MCP:...]]。
用户说：$userPrompt"""
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
        existingCodexThreadId: String?
    ): String {
        val quotedPrompt = shellQuote(prompt)

        val agyBin = antigravityExecutable(command)
        if (agyBin != null) {
            val inv = if (agyBin.matches(Regex("[A-Za-z0-9_]+"))) agyBin else shellQuote(agyBin)
            val resumeFlag = if (!existingConvId.isNullOrBlank()) "-r ${shellQuote(existingConvId)} " else ""
            val agyArgs = "--effort low $resumeFlag-p $quotedPrompt --output-format json"
            // 直接将 $inv 放在命令首位以保证 bash 原位展开 ~/.bashrc 中的 alias（不可存入 $_cli 变量）
            return "if type $inv >/dev/null 2>&1; then $inv $agyArgs; " +
                "elif [ -x \"\$HOME/.local/bin/$agyBin\" ]; then \"\$HOME/.local/bin/$agyBin\" $agyArgs; " +
                "else agy $agyArgs; fi"
        }

        val codexBin = codexExecutable(command)
        if (codexBin != null) {
            val inv = if (codexBin.matches(Regex("[A-Za-z0-9_]+"))) codexBin else shellQuote(codexBin)
            return if (!existingCodexThreadId.isNullOrBlank()) {
                "$inv exec --json resume ${shellQuote(existingCodexThreadId)} $quotedPrompt"
            } else {
                "$inv exec --json $quotedPrompt"
            }
        }

        val firstToken = command.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        val binName = firstToken.substringAfterLast('/')
        return when (binName) {
            "claude" -> {
                val inv = if (firstToken.matches(Regex("[A-Za-z0-9_]+"))) firstToken else shellQuote(firstToken)
                val resumeFlag = if (!existingConvId.isNullOrBlank()) "-r ${shellQuote(existingConvId)} " else ""
                "$inv $resumeFlag-p $quotedPrompt --output-format json"
            }
            "opencode" -> {
                val inv = if (firstToken.matches(Regex("[A-Za-z0-9_]+"))) firstToken else shellQuote(firstToken)
                val resumeFlag = if (!existingConvId.isNullOrBlank()) "-s ${shellQuote(existingConvId)} " else ""
                "$inv run $resumeFlag$quotedPrompt"
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
        return cd + "\"${'$'}{SHELL:-/bin/bash}\" -l -i -c ${shellQuote(pathExport + command)}"
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
