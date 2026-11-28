package dev.remote.terminal.jarvis.model

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
import kotlinx.coroutines.Dispatchers
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

/** SSH transport for the remote CLI model. Request interpretation and tools live on Android. */
internal object RemoteCliModel {

    private const val TAG = "RemoteCliModel"
    private const val EXEC_TIMEOUT_MS = 25_000L
    private data class CachedSession(
        val session: Session,
        val address: String,
        val port: Int,
        val isLan: Boolean
    )

    private class PersistentAgyWorker(
        val session: Session,
        val channel: ChannelExec,
        val stdin: OutputStream,
        val stdout: BufferedReader
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

    fun resetWorkers() {
        streamWorkers.values.forEach { it.closeQuietly() }
        streamWorkers.clear()
        conversationIds.clear()
        codexThreadIds.clear()
    }

    fun supports(command: String, model: String): Boolean {
        val effective = resolveEffectiveCommand(command, model)
        return antigravityExecutable(effective) != null || codexExecutable(effective) != null ||
            effective.trim().substringBefore(' ').substringAfterLast('/') in setOf("claude", "opencode")
    }

    suspend fun prewarm(
        host: HostProfile,
        preset: AgentPreset,
        store: LocalStore,
        model: String,
        effort: String
    ) = withContext(Dispatchers.IO) {
        val command = resolveEffectiveCommand(preset.command, model)
        if (!supports(preset.command, model)) return@withContext
        val key = "${host.id}:$command:$model:$effort"
        runCatching {
            val session = getOrOpenSession(host, store).session
            if (antigravityExecutable(command) != null) {
                getOrSpawnWorker(session, key, command, model, effort)
            }
        }
    }

    suspend fun withTurn(
        host: HostProfile,
        preset: AgentPreset,
        store: LocalStore,
        model: String,
        effort: String,
        runAgent: suspend (suspend (String) -> Result<String>) -> Result<String>
    ): Result<String> = withContext(Dispatchers.IO) {
        val command = resolveEffectiveCommand(preset.command, model)
        if (!supports(preset.command, model)) {
            return@withContext Result.failure(IOException("当前预设不是受支持的远端 CLI 模型入口。"))
        }
        val key = "${host.id}:$command:$model:$effort"
        try {
            val session = getOrOpenSession(host, store).session
            runAgent { prompt -> complete(session, command, key, prompt, model, effort) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Remote CLI model call failed: ${e.message}", e)
            streamWorkers.remove(key)?.closeQuietly()
            conversationIds.remove(key)
            codexThreadIds.remove(key)
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

    private suspend fun complete(
        session: Session,
        command: String,
        key: String,
        prompt: String,
        model: String,
        effort: String
    ): Result<String> {
        if (antigravityExecutable(command) != null) {
            val streamResult = trySendViaPersistentWorker(session, key, command, model, effort, prompt)
            if (streamResult.isSuccess) return streamResult
            Log.w(TAG, "Persistent worker fallback to one-shot")
        }
        return invokeOneShotExec(session, command, prompt, key, model, effort)
    }

    private suspend fun trySendViaPersistentWorker(
        session: Session,
        workerKey: String,
        command: String,
        model: String,
        effort: String,
        prompt: String
    ): Result<String> {
        val worker = getOrSpawnWorker(session, workerKey, command, model, effort)
            ?: return Result.failure(IOException("无法启动常驻开发者进程"))

        return worker.mutex.withLock {
            try {
                if (!worker.isAlive()) {
                    streamWorkers.remove(workerKey, worker)
                    worker.closeQuietly()
                    return@withLock Result.failure(IOException("常驻进程已断开"))
                }

                val eventJson = JSONObject().apply {
                    put("event", "user")
                    put("message", JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
            spawnPersistentAgyWorker(session, workerKey, command, model, effort)
        }.getOrNull()
    }

    private fun spawnPersistentAgyWorker(
        session: Session,
        workerKey: String,
        command: String,
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
        val wrapped = buildRemoteShellCommand(cliCmd)

        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(wrapped)
        val stdin = channel.outputStream
        val stdout = BufferedReader(InputStreamReader(channel.inputStream, Charsets.UTF_8))
        channel.connect(12_000)

        val worker = PersistentAgyWorker(session, channel, stdin, stdout)
        streamWorkers[workerKey] = worker
        return worker
    }

    private suspend fun invokeOneShotExec(
        session: Session,
        command: String,
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
        val wrappedRemoteCmd = buildRemoteShellCommand(cliCommand)

        Log.i(TAG, "Sending one-shot turn via $command [model=${model.ifBlank { "default" }}, effort=$effort]")
        val execResult = runExec(session, wrappedRemoteCmd, EXEC_TIMEOUT_MS)
        val rawOut = execResult.stdout.trim()
        val rawErr = execResult.stderr.trim()

        val parsed = parseDeveloperResponse(command, rawOut, rawErr, execResult.exitCode, convKey)
        if (parsed.isSuccess) return parsed

        if (conversationIds.remove(convKey) != null || codexThreadIds.remove(convKey) != null) {
            val freshCmd = buildRemoteShellCommand(
                buildAgentExecCommand(command, prompt, null, null, model, effort)
            )
            val retry = runExec(session, freshCmd, EXEC_TIMEOUT_MS)
            return parseDeveloperResponse(command, retry.stdout.trim(), retry.stderr.trim(), retry.exitCode, convKey)
        }
        return parsed
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
                "$inv exec $modelFlag$effortFlag--sandbox read-only --skip-git-repo-check --json resume ${shellQuote(existingCodexThreadId)} $quotedPrompt"
            } else {
                "$inv exec $modelFlag$effortFlag--sandbox read-only --skip-git-repo-check --json $quotedPrompt"
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
            else -> error("Unsupported remote CLI model preset")
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

    private fun buildRemoteShellCommand(command: String): String {
        val pathExport = "export PATH=\"\$HOME/.local/bin:\$HOME/.local/nodejs/bin:\$HOME/.npm-global/bin:\$HOME/bin:/usr/local/bin:\$PATH\"; "
        // 使用非交互非登录 /bin/sh -c 跳过冗长的 bashrc/profile 初始化，降低 CLI 启动延迟
        return "cd \"\$HOME\" && /bin/sh -c ${shellQuote(pathExport + command)}"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun stripAnsi(raw: String): String =
        raw.replace(Regex("\u001B\\[[;?0-9]*[a-zA-Z]"), "")
            .replace(Regex("[\r\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "")

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
