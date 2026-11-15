package dev.remote.terminal.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.ChannelExec
import org.json.JSONObject
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import dev.remote.terminal.data.LocalStore
import dev.remote.terminal.data.TerminalTask
import android.os.SystemClock
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SshTerminalController(
    private val task: TerminalTask,
    private val store: LocalStore,
    private val onStatus: (String) -> Unit,
    private val onBytes: (String) -> Unit,
    private val onHostKey: (String, (Boolean) -> Unit) -> Unit,
    private val onQuota: (List<Pair<Int, Int>>?) -> Unit,
) {
    private data class ActiveConnection(
        val session: Session,
        val channel: ChannelShell,
        val input: OutputStream,
        val establishedAt: Long,
    )

    private data class PendingInput(
        val bytes: ByteArray,
        val expectedConnection: ActiveConnection? = null,
    )

    private val running = AtomicBoolean(false)
    private val activeConnection = AtomicReference<ActiveConnection?>(null)
    private val sessionRef = AtomicReference<Session?>(null)
    private val channelRef = AtomicReference<ChannelShell?>(null)
    private val outgoing = LinkedBlockingDeque<PendingInput>()
    private val pendingDisconnectReason = AtomicReference<String?>(null)
    private val retrySignal = Semaphore(0)
    private val writeLock = Any()
    @Volatile private var columns = 80
    @Volatile private var rows = 30

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread(::connectionLoop, "ssh-${task.id.take(8)}").start()
        Thread(::writerLoop, "ssh-input-${task.id.take(8)}").apply { isDaemon = true }.start()
    }

    fun send(text: String) {
        if (text.isEmpty() || !running.get()) return
        outgoing.offerLast(PendingInput(text.toByteArray(Charsets.UTF_8)))
    }

    /** Queue input only for the currently active SSH connection; never replay it after reconnect. */
    fun sendIfConnected(text: String): Boolean {
        if (text.isEmpty() || !running.get()) return false
        val connection = activeConnection.get() ?: return false
        return outgoing.offerLast(PendingInput(text.toByteArray(Charsets.UTF_8), connection))
    }

    fun onNetworkAvailable(recoveryStartedAt: Long) {
        if (!running.get()) return
        // Android has reported a stable replacement default network. An established
        // TCP socket belongs to the old network and can otherwise remain half-open.
        // Never interrupt an attempt that has not finished connecting yet.
        val connection = activeConnection.get()
        if (connection != null && connection.establishedAt <= recoveryStartedAt && activeConnection.compareAndSet(connection, null)) {
            pendingDisconnectReason.set("网络已切换")
            onStatus("网络已切换 · 正在恢复 SSH 会话")
            runCatching { connection.channel.disconnect() }
            runCatching { connection.session.disconnect() }
        }
        retrySignal.release()
    }

    fun resize(cols: Int, lines: Int, force: Boolean = false) {
        val nextColumns = cols.coerceIn(24, 200)
        val nextRows = lines.coerceIn(8, 100)
        synchronized(writeLock) {
            if (!force && nextColumns == columns && nextRows == rows) return
            columns = nextColumns
            rows = nextRows
            val connection = activeConnection.get() ?: return@synchronized
            runCatching { connection.channel.setPtySize(columns, rows, 0, 0) }
        }
    }

    fun approveHostKey(approved: Boolean) { hostKeyDecision?.invoke(approved) }
    @Volatile private var hostKeyDecision: ((Boolean) -> Unit)? = null

    fun close() {
        running.set(false)
        outgoing.clear()
        retrySignal.release()
        val connection = activeConnection.getAndSet(null)
        runCatching { channelRef.getAndSet(null)?.disconnect() }
        runCatching { sessionRef.getAndSet(null)?.disconnect() }
        connection?.let {
            runCatching { it.channel.disconnect() }
            runCatching { it.session.disconnect() }
        }
        onStatus("已断开")
    }

    private fun quotaLoop(connection: ActiveConnection) {
        while (running.get() && activeConnection.get() === connection && connection.session.isConnected) {
            val result = runCatching {
                when (task.agent.id) {
                    "codex" -> readCodexQuota(connection.session)
                    "antigravity" -> readAntigravityQuota(connection.session)
                    else -> null
                }
            }.getOrNull()
            if (activeConnection.get() !== connection || !running.get()) break
            onQuota(result)
            repeat(60) {
                if (!running.get() || activeConnection.get() !== connection) return
                Thread.sleep(1_000)
            }
        }
    }

    private fun readCodexQuota(session: Session): List<Pair<Int, Int>>? {
        val channel = session.openChannel("exec") as ChannelExec
        try {
            channel.setCommand("bash -lc 'exec codex app-server --listen stdio://'")
            val output = channel.outputStream
            val input = channel.inputStream
            channel.connect(10_000)
            output.write(("{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"remote_dev_terminal\",\"version\":\"1.0\"}}}\n").toByteArray())
            output.flush()
            val deadline = SystemClock.elapsedRealtime() + 15_000
            val pending = StringBuilder()
            var requested = false
            while (SystemClock.elapsedRealtime() < deadline && !channel.isClosed && running.get()) {
                if (input.available() == 0) { Thread.sleep(50); continue }
                val byte = input.read()
                if (byte < 0) break
                if (byte != 10) {
                    if (pending.length < 1_000_000) pending.append(byte.toChar()) else return null
                    continue
                }
                val message = runCatching { JSONObject(pending.toString()) }.getOrNull()
                pending.setLength(0)
                if (message?.optInt("id", -1) == 1 && !requested) {
                    if (message.has("error")) return null
                    output.write(("{\"method\":\"initialized\",\"params\":{}}\n" +
                        "{\"id\":2,\"method\":\"account/rateLimits/read\",\"params\":{}}\n").toByteArray())
                    output.flush()
                    requested = true
                } else if (message?.optInt("id", -1) == 2) {
                    val snapshot = message.optJSONObject("result")?.optJSONObject("rateLimits") ?: return null
                    return listOf("primary", "secondary").mapNotNull { name ->
                        val window = snapshot.optJSONObject(name) ?: return@mapNotNull null
                        if (!window.has("usedPercent") || window.isNull("usedPercent") ||
                            !window.has("windowDurationMins") || window.isNull("windowDurationMins")) return@mapNotNull null
                        val minutes = window.optInt("windowDurationMins", 0)
                        if (minutes <= 0) return@mapNotNull null
                        minutes to (100 - window.optInt("usedPercent")).coerceIn(0, 100)
                    }.takeIf { it.isNotEmpty() }
                }
            }
            return null
        } finally {
            channel.disconnect()
        }
    }

    private fun readAntigravityQuota(session: Session): List<Pair<Int, Int>>? {
        val channel = session.openChannel("exec") as ChannelExec
        try {
            channel.setCommand("bash -lc 'agy -p \"/usage\" --output-format json'")
            channel.connect(10_000)
            val input = channel.inputStream
            val output = StringBuilder()
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline && !channel.isClosed && running.get()) {
                if (input.available() == 0) { Thread.sleep(50); continue }
                val byte = input.read()
                if (byte < 0) break
                if (output.length >= 1_000_000) return null
                output.append(byte.toChar())
            }
            val root = runCatching { JSONObject(output.toString()) }.getOrNull() ?: return null
            val groups = root.optJSONObject("command")?.optJSONObject("data")?.optJSONArray("groups") ?: return null
            val quota = linkedMapOf<String, Int>()
            for (groupIndex in 0 until groups.length()) {
                val group = groups.optJSONObject(groupIndex) ?: continue
                val buckets = group.optJSONArray("buckets") ?: continue
                for (bucketIndex in 0 until buckets.length()) {
                    val bucket = buckets.optJSONObject(bucketIndex) ?: continue
                    val id = bucket.optString("id").lowercase()
                    if (!id.contains("gemini")) continue
                    val minutes = when {
                        id.contains("weekly") || id.contains("7d") -> 10080
                        id.contains("5h") || id.contains("session") -> 300
                        else -> continue
                    }
                    val remaining = bucket.optDouble("remaining_fraction", Double.NaN)
                    if (!remaining.isFinite()) continue
                    quota[if (minutes == 300) "hour" else "week"] = (remaining * 100).toInt().coerceIn(0, 100)
                }
            }
            return listOfNotNull(quota["hour"]?.let { 300 to it }, quota["week"]?.let { 10080 to it })
                .takeIf { it.isNotEmpty() }
        } finally {
            channel.disconnect()
        }
    }

    /** All keystrokes go through one writer. A stale stream can only invalidate its own channel. */
    private fun writerLoop() {
        while (running.get()) {
            try {
                val connection = activeConnection.get()
                if (connection == null) {
                    Thread.sleep(40)
                    continue
                }
                val pendingInput = outgoing.pollFirst(100, TimeUnit.MILLISECONDS) ?: continue
                synchronized(writeLock) {
                    if (activeConnection.get() !== connection) {
                        if (pendingInput.expectedConnection == null) outgoing.offerFirst(pendingInput)
                    } else if (pendingInput.expectedConnection != null && pendingInput.expectedConnection !== connection) {
                        // Auto-confirm input belongs to the old connection and must not reach a resumed session.
                    } else {
                        try {
                            connection.input.write(pendingInput.bytes)
                            connection.input.flush()
                        } catch (error: Exception) {
                            val reason = error.message?.takeIf { it.isNotBlank() }
                                ?: error.javaClass.simpleName
                            if (activeConnection.compareAndSet(connection, null)) {
                                // A failed write may have been partial. Do not replay queued commands.
                                outgoing.clear()
                                pendingDisconnectReason.set("输入发送失败：$reason")
                                onStatus("输入发送失败：$reason · 正在恢复 SSH")
                                retrySignal.release()
                                runCatching { connection.channel.disconnect() }
                                runCatching { connection.session.disconnect() }
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                if (!running.get()) break
            }
        }
    }

    private fun connectionLoop() {
        var retry = 0
        var connectedAt = 0L
        while (running.get()) {
            try {
                connectedAt = 0L
                onStatus(if (retry == 0) "正在连接 ${task.host.address}:${task.host.port}" else "连接中断 · 正在重连 ${retry}…")
                val jsch = JSch()
                store.addSshIdentity(jsch)
                jsch.hostKeyRepository = TrustedHostRepository()
                val session = jsch.getSession(task.host.username, task.host.address, task.host.port)
                sessionRef.set(session)
                ensureRunning()
                session.setPassword(task.host.password)
                session.setConfig("StrictHostKeyChecking", "yes")
                session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
                session.setServerAliveInterval(20_000)
                session.setServerAliveCountMax(6)
                session.connect(25_000)
                ensureRunning()

                val channel = session.openChannel("shell") as ChannelShell
                channelRef.set(channel)
                channel.setPty(true)
                channel.setPtyType("xterm-256color")
                channel.setPtySize(columns, rows, 0, 0)
                val input = channel.outputStream
                val reader = channel.inputStream
                channel.connect(20_000)
                ensureRunning()
                val connection = ActiveConnection(session, channel, input, SystemClock.elapsedRealtime())
                // The initial command must reach the remote shell before queued user input.
                synchronized(writeLock) {
                    ensureRunning()
                    input.write(buildAttachCommand().toByteArray(Charsets.UTF_8))
                    input.flush()
                    // A viewport change can arrive while the SSH channel is opening.
                    // Apply the latest size again before exposing the connection.
                    channel.setPtySize(columns, rows, 0, 0)
                    pendingDisconnectReason.set(null)
                    activeConnection.set(connection)
                }
                connectedAt = SystemClock.elapsedRealtime()
                onStatus("已连接 · ${task.host.username}@${task.host.address}")
                if ((task.agent.id == "codex" && task.agent.command.trim().matches(Regex("^codex(?:\\s.*)?$"))) ||
                    (task.agent.id == "antigravity" && task.agent.command.trim().matches(Regex("^agy(?:\\s.*)?$")))) {
                    Thread({ quotaLoop(connection) }, "codex-quota-${task.id.take(8)}").apply { isDaemon = true; start() }
                }
                val textReader = InputStreamReader(reader, Charsets.UTF_8)
                val buffer = CharArray(4096)
                var remoteTerminalEnded = false
                while (running.get() && session.isConnected && !channel.isClosed) {
                    val count = textReader.read(buffer)
                    if (count < 0) {
                        val disconnectReason = pendingDisconnectReason.getAndSet(null)
                        if (disconnectReason != null) throw IOException(disconnectReason)
                        if (session.isConnected && channel.isClosed && channel.exitStatus >= 0) {
                            remoteTerminalEnded = true
                            break
                        }
                        throw IOException("SSH 传输读取结束")
                    }
                    onBytes(String(buffer, 0, count))
                }
                val remoteExitStatus = channel.exitStatus
                remoteTerminalEnded = remoteTerminalEnded ||
                    (session.isConnected && channel.isClosed && remoteExitStatus >= 0 && pendingDisconnectReason.get() == null)
                val completedNormally = synchronized(writeLock) {
                    activeConnection.compareAndSet(connection, null)
                    channelRef.compareAndSet(channel, null)
                    sessionRef.compareAndSet(session, null)
                    val finished = running.get() && remoteTerminalEnded &&
                        pendingDisconnectReason.get() == null
                    if (finished) running.set(false)
                    finished
                }
                channel.disconnect()
                session.disconnect()
                if (completedNormally) {
                    outgoing.clear()
                    onStatus(if (remoteExitStatus >= 0) "远端终端已结束（退出码 $remoteExitStatus）" else "远端终端已结束")
                } else if (running.get()) {
                    throw IOException(pendingDisconnectReason.getAndSet(null) ?: "SSH 传输已断开")
                }
            } catch (error: Exception) {
                if (connectedAt > 0 && SystemClock.elapsedRealtime() - connectedAt >= 30_000L) retry = 0
                val reason = pendingDisconnectReason.getAndSet(null)
                    ?: error.message?.takeIf { it.isNotBlank() }
                    ?: "连接失败（${error.javaClass.simpleName}）"
                activeConnection.getAndSet(null)
                channelRef.getAndSet(null)?.let { runCatching { it.disconnect() } }
                sessionRef.getAndSet(null)?.let { runCatching { it.disconnect() } }
                if (!running.get()) break
                retry++
                val backoff = (1_000L shl (retry - 1).coerceAtMost(4)).coerceAtMost(15_000L)
                onStatus("$reason · ${backoff / 1_000L} 秒后重连")
                awaitRetry(backoff)
            }
        }
    }

    private fun ensureRunning() {
        if (!running.get()) throw IOException("会话已关闭")
    }

    private fun awaitRetry(delayMillis: Long) {
        try {
            retrySignal.tryAcquire(delayMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            if (!running.get()) return
        }
        while (retrySignal.tryAcquire()) Unit
    }

    private fun buildAttachCommand(): String {
        val sessionName = "rdt_${task.id.replace("-", "").take(18)}"
        val dir = shellQuote(task.directory.path)
        val shell = "\"${'$'}{SHELL:-/bin/bash}\""
        val agentInvocation = "$shell -l -i -c ${shellQuote(task.agent.command)}"
        val keepSessionCommand = "$agentInvocation; agent_exit_code=${'$'}?; printf '\\n[Agent exited with status %s. Shell remains open.]\\n' \"${'$'}agent_exit_code\"; exec $shell -l -i"
        val tmuxCommand = shellQuote(keepSessionCommand)
        return "cd -- $dir && if command -v tmux >/dev/null 2>&1; then " +
            "(tmux has-session -t $sessionName 2>/dev/null || tmux new-session -d -s $sessionName -c $dir $tmuxCommand) && " +
            "tmux set-option -t $sessionName mouse on && tmux attach-session -d -t $sessionName; " +
            "elif command -v screen >/dev/null 2>&1; then screen -D -RR -S $sessionName sh -lc $tmuxCommand; " +
            "else printf '\\n[提示] 未找到 tmux 或 screen，断线后无法保留 CLI 会话\\n'; $agentInvocation; fi\n"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private inner class TrustedHostRepository : HostKeyRepository {
        override fun check(host: String, key: ByteArray): Int {
            val fingerprint = "SHA256:" + android.util.Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(key), android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            if (store.isHostKeyTrusted(task.host.address, task.host.port, fingerprint)) return HostKeyRepository.OK

            val latch = CountDownLatch(1)
            var accepted = false
            val decide: (Boolean) -> Unit = { value -> accepted = value; latch.countDown() }
            hostKeyDecision = decide
            onHostKey(fingerprint, decide)
            val answered = latch.await(2, TimeUnit.MINUTES)
            hostKeyDecision = null
            if (answered && accepted) {
                store.trustHostKey(task.host.address, task.host.port, fingerprint)
                return HostKeyRepository.OK
            }
            return HostKeyRepository.NOT_INCLUDED
        }

        override fun add(hostkey: com.jcraft.jsch.HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "RemoteDevTerminal"
        override fun getHostKey(): Array<com.jcraft.jsch.HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<com.jcraft.jsch.HostKey> = emptyArray()
    }
}
