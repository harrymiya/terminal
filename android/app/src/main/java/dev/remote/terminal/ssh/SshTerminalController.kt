package dev.remote.terminal.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import dev.remote.terminal.data.HostRoute
import dev.remote.terminal.data.HostRouteResolver
import dev.remote.terminal.data.LocalStore
import dev.remote.terminal.data.QuotaWindow
import dev.remote.terminal.data.endpoints
import dev.remote.terminal.data.TerminalTask
import android.os.SystemClock
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SshTerminalController(
    private val task: TerminalTask,
    private val store: LocalStore,
    private val restoreOnly: Boolean = false,
    private val onStatus: (String) -> Unit,
    private val onBytes: (String) -> Unit,
    private val onHostKey: (String, Int, String, (Boolean) -> Unit) -> Unit,
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
        val exitCopyMode: Boolean = false,
    )

    private val running = AtomicBoolean(false)
    @Volatile private var currentAddress: String = task.host.address
    @Volatile private var currentPort: Int = task.host.port
    @Volatile private var currentIsLan: Boolean = false
    private val activeConnection = AtomicReference<ActiveConnection?>(null)
    private val sessionRef = AtomicReference<Session?>(null)
    private val channelRef = AtomicReference<ChannelShell?>(null)
    private val outgoing = LinkedBlockingDeque<PendingInput>()
    private val pendingDisconnectReason = AtomicReference<String?>(null)
    private val retrySignal = Semaphore(0)
    private val writeLock = Any()
    private val pageScrollExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tmux-scroll-${task.id.take(8)}").apply { isDaemon = true }
    }
    // Accessed only on pageScrollExecutor, so paging and subsequent input stay ordered.
    private var copyModeConnection: ActiveConnection? = null
    private var checkedCopyModeConnection: ActiveConnection? = null
    @Volatile private var columns = 80
    @Volatile private var rows = 30

    fun isConnected(): Boolean = activeConnection.get()?.session?.isConnected == true
    fun isClosed(): Boolean = !running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread(::connectionLoop, "ssh-${task.id.take(8)}").start()
        Thread(::writerLoop, "ssh-input-${task.id.take(8)}").apply { isDaemon = true }.start()
    }

    fun send(text: String) {
        if (text.isEmpty() || !running.get()) return
        queueInput(text, null)
    }

    private fun queueInput(text: String, expectedConnection: ActiveConnection?, scroll: Boolean = false): Boolean = runCatching {
        pageScrollExecutor.execute {
            val connection = activeConnection.get()
            if (running.get() && (expectedConnection == null || activeConnection.get() === expectedConnection)) {
                val exitCopyMode = !scroll && connection != null &&
                    (connection === copyModeConnection || connection !== checkedCopyModeConnection)
                outgoing.offerLast(PendingInput(text.toByteArray(Charsets.UTF_8), expectedConnection, exitCopyMode))
                if (scroll) copyModeConnection = connection
                else if (exitCopyMode) {
                    copyModeConnection = null
                    checkedCopyModeConnection = connection
                }
            }
        }
        true
    }.getOrDefault(false)

    /** A tmux mouse-wheel binding can enter copy mode outside our page buttons. */
    fun sendScroll(text: String) {
        if (text.isNotEmpty() && running.get()) queueInput(text, null, scroll = true)
    }

    private fun runTmuxCommand(connection: ActiveConnection, action: String, enterCopyMode: Boolean = false) {
        val sessionName = "rdt_${task.id.replace("-", "").take(18)}"
        val channel = runCatching { connection.session.openChannel("exec") as ChannelExec }.getOrNull() ?: return
        try {
            val enter = if (enterCopyMode) "if [ \"$(tmux display-message -p -t $sessionName '#{pane_in_mode}')\" = 0 ]; then tmux copy-mode -t $sessionName; fi; " else ""
            channel.setCommand(enter + "tmux send-keys -t $sessionName -X $action")
            channel.connect(10_000)
            while (running.get() && !channel.isClosed && activeConnection.get() === connection) Thread.sleep(20)
        } catch (_: Exception) {
            // Connection recovery is handled by the main SSH loop.
        } finally {
            channel.disconnect()
        }
    }

    /** Enter tmux copy mode and scroll its history without sending page keys to the foreground app. */
    fun scrollTmuxPage(up: Boolean) {
        val connection = activeConnection.get() ?: return
        pageScrollExecutor.execute {
            if (!running.get() || activeConnection.get() !== connection) return@execute
            runTmuxCommand(connection, if (up) "page-up" else "page-down-and-cancel", enterCopyMode = up)
            if (up) copyModeConnection = connection
        }
    }

    /** Queue input only for the currently active SSH connection; never replay it after reconnect. */
    fun sendIfConnected(text: String): Boolean {
        if (text.isEmpty() || !running.get()) return false
        val connection = activeConnection.get() ?: return false
        return queueInput(text, connection)
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

    fun close(terminateRemote: Boolean = false) {
        running.set(false)
        pageScrollExecutor.shutdownNow()
        outgoing.clear()
        retrySignal.release()
        val connection = activeConnection.getAndSet(null)
        val activeChannel = channelRef.getAndSet(null)
        val activeSession = sessionRef.getAndSet(null)
        runCatching { activeChannel?.disconnect() }
        runCatching { connection?.channel?.disconnect() }
        val sessionsToClose = listOfNotNull(activeSession, connection?.session).distinct()
        if (terminateRemote && sessionsToClose.any { it.isConnected }) {
            val remoteSession = sessionsToClose.firstOrNull { it.isConnected }
            Thread({
                try {
                    if (remoteSession != null && remoteSession.isConnected) {
                        val exec = remoteSession.openChannel("exec") as ChannelExec
                        try {
                            exec.setCommand(buildTerminateCommand())
                            exec.connect(5_000)
                            val deadline = SystemClock.elapsedRealtime() + 3_000
                            while (!exec.isClosed && SystemClock.elapsedRealtime() < deadline) {
                                Thread.sleep(30)
                            }
                        } finally {
                            runCatching { exec.disconnect() }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    sessionsToClose.forEach { runCatching { it.disconnect() } }
                }
            }, "tmux-close-${task.id.take(8)}").apply { isDaemon = true; start() }
        } else {
            sessionsToClose.forEach { runCatching { it.disconnect() } }
        }
        onStatus("已断开")
    }

    private fun buildTerminateCommand(): String {
        val sessionName = "rdt_${task.id.replace("-", "").take(18)}"
        return "if command -v tmux >/dev/null 2>&1 && tmux has-session -t $sessionName 2>/dev/null; then " +
            "for p in \$(tmux list-panes -t $sessionName -F '#{pane_pid}' 2>/dev/null); do " +
            "pkill -TERM -P \"\$p\" 2>/dev/null || true; kill -TERM \"\$p\" 2>/dev/null || true; " +
            "done; tmux kill-session -t $sessionName 2>/dev/null || true; " +
            "elif command -v screen >/dev/null 2>&1; then screen -S $sessionName -X quit 2>/dev/null || true; fi"
    }

    fun readQuota(command: String, directory: String? = null): List<QuotaWindow>? {
        val session = activeConnection.get()?.session ?: return null
        return AgentQuotaReader.read(session, command, directory)
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
                if (pendingInput.exitCopyMode && activeConnection.get() === connection &&
                    (pendingInput.expectedConnection == null || pendingInput.expectedConnection === connection)) {
                    runTmuxCommand(connection, "cancel")
                }
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
                val resolved = HostRouteResolver.getRoute(task.host.id)
                val allCandidates = task.host.endpoints()
                val candidates = if (resolved != null) {
                    val matched = allCandidates.firstOrNull { it.address == resolved.address && it.port == resolved.port }
                    if (matched != null) listOf(matched) + allCandidates.filterNot { it == matched } else allCandidates
                } else allCandidates
                val initialCandidate = candidates.firstOrNull()
                onStatus(if (retry == 0) "正在连接 ${initialCandidate?.let { "${it.address}:${it.port}" } ?: "${task.host.address}:${task.host.port}"}" else "连接中断 · 正在重连 ${retry}…")
                val jsch = JSch()
                store.addSshIdentity(jsch)
                jsch.hostKeyRepository = TrustedHostRepository()
                var openedSession: Session? = null
                var connectFailure: Exception? = null
                for (endpoint in candidates) {
                    ensureRunning()
                    val candidate = jsch.getSession(task.host.username, endpoint.address, endpoint.port)
                    candidate.setPassword(task.host.password)
                    candidate.setConfig("StrictHostKeyChecking", "yes")
                    candidate.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
                    candidate.setServerAliveInterval(10_000)
                    candidate.setServerAliveCountMax(3)
                    sessionRef.set(candidate)
                    currentAddress = endpoint.address
                    currentPort = endpoint.port
                    currentIsLan = endpoint.isLan
                    try {
                        val connectTimeout = if (endpoint.isLan && candidates.size > 1) 3_000 else 12_000
                        candidate.connect(connectTimeout)
                        openedSession = candidate
                        break
                    } catch (error: Exception) {
                        candidate.disconnect()
                        sessionRef.compareAndSet(candidate, null)
                        connectFailure = error
                    }
                }
                val session = openedSession ?: throw (connectFailure ?: IOException("没有可用的主机地址"))
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
                onStatus("已连接 · ${task.host.username}@$currentAddress:$currentPort (${if (currentIsLan) "内网" else "外网"})")
                HostRouteResolver.updateRoute(task.host.id, HostRoute(currentAddress, currentPort, currentIsLan, reachable = true))
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
        if (restoreOnly) {
            return "cd -- $dir && if command -v tmux >/dev/null 2>&1 && tmux has-session -t $sessionName 2>/dev/null; then " +
                "tmux set-option -t $sessionName mouse on && tmux attach-session -t $sessionName; " +
                "elif command -v screen >/dev/null 2>&1 && screen -ls | grep -q '[.]$sessionName[[:space:]]'; then " +
                "screen -x $sessionName; " +
                "else printf '\n[提示] 远端会话不存在，无法恢复；请新建会话。\n'; fi\n"
        }
        return "cd -- $dir && if command -v tmux >/dev/null 2>&1; then " +
            "(tmux has-session -t $sessionName 2>/dev/null || tmux new-session -d -s $sessionName -c $dir $tmuxCommand) && " +
            "tmux set-option -t $sessionName mouse on && tmux attach-session -t $sessionName; " +
            "elif command -v screen >/dev/null 2>&1; then screen -xRR -S $sessionName sh -lc $tmuxCommand; " +
            "else printf '\\n[提示] 未找到 tmux 或 screen，断线后无法保留 CLI 会话\\n'; $agentInvocation; fi\n"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private inner class TrustedHostRepository : HostKeyRepository {
        override fun check(host: String, key: ByteArray): Int {
            val fingerprint = "SHA256:" + android.util.Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(key), android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            if (store.isHostKeyTrusted(currentAddress, currentPort, fingerprint)) return HostKeyRepository.OK

            val latch = CountDownLatch(1)
            var accepted = false
            val decide: (Boolean) -> Unit = { value -> accepted = value; latch.countDown() }
            hostKeyDecision = decide
            onHostKey(currentAddress, currentPort, fingerprint, decide)
            val answered = latch.await(2, TimeUnit.MINUTES)
            hostKeyDecision = null
            if (answered && accepted) {
                store.trustHostKey(currentAddress, currentPort, fingerprint)
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
