package dev.remote.terminal.ssh

import com.jcraft.jsch.ChannelShell
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class SshTerminalController(
    private val task: TerminalTask,
    private val store: LocalStore,
    private val onStatus: (String) -> Unit,
    private val onBytes: (String) -> Unit,
    private val onHostKey: (String, (Boolean) -> Unit) -> Unit,
) {
    private data class ActiveConnection(
        val session: Session,
        val channel: ChannelShell,
        val input: OutputStream,
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
    private val networkGeneration = AtomicLong(0)
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

    fun onNetworkAvailable() {
        if (!running.get()) return
        // Wake a pending retry, but let an existing TCP connection prove it is dead
        // before replacing it. Wi-Fi handoffs often recover the same connection.
        retrySignal.release()
        var shouldReconnect = false
        val connection = synchronized(writeLock) {
            if (!running.get() || activeConnection.get() != null) return@synchronized null
            shouldReconnect = true
            networkGeneration.incrementAndGet()
            pendingDisconnectReason.set("网络已恢复，正在恢复 SSH 会话")
            activeConnection.getAndSet(null)
        }
        if (!shouldReconnect) return
        onStatus("网络已恢复 · 正在恢复 SSH 会话")
        if (connection != null) {
            runCatching { connection.channel.disconnect() }
            runCatching { connection.session.disconnect() }
        } else {
            runCatching { channelRef.get()?.disconnect() }
            runCatching { sessionRef.get()?.disconnect() }
        }
    }

    fun resize(cols: Int, lines: Int) {
        val nextColumns = cols.coerceIn(30, 200)
        val nextRows = lines.coerceIn(10, 100)
        if (nextColumns == columns && nextRows == rows) return
        columns = nextColumns
        rows = nextRows
        synchronized(writeLock) {
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
                val attemptNetworkGeneration = networkGeneration.get()
                val jsch = JSch()
                jsch.hostKeyRepository = TrustedHostRepository()
                val session = jsch.getSession(task.host.username, task.host.address, task.host.port)
                sessionRef.set(session)
                verifyNetworkGeneration(attemptNetworkGeneration)
                session.setPassword(task.host.password)
                session.setConfig("StrictHostKeyChecking", "yes")
                session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
                session.setServerAliveInterval(10_000)
                session.setServerAliveCountMax(3)
                session.connect(25_000)
                verifyNetworkGeneration(attemptNetworkGeneration)

                val channel = session.openChannel("shell") as ChannelShell
                channelRef.set(channel)
                channel.setPty(true)
                channel.setPtyType("xterm-256color")
                channel.setPtySize(columns, rows, 0, 0)
                val input = channel.outputStream
                val reader = channel.inputStream
                channel.connect(20_000)
                verifyNetworkGeneration(attemptNetworkGeneration)
                val connection = ActiveConnection(session, channel, input)
                // The initial command must reach the remote shell before queued user input.
                synchronized(writeLock) {
                    verifyNetworkGeneration(attemptNetworkGeneration)
                    input.write(buildAttachCommand().toByteArray(Charsets.UTF_8))
                    input.flush()
                    pendingDisconnectReason.set(null)
                    activeConnection.set(connection)
                }
                connectedAt = SystemClock.elapsedRealtime()
                onStatus("已连接 · ${task.host.username}@${task.host.address}")
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
                        networkGeneration.get() == attemptNetworkGeneration && pendingDisconnectReason.get() == null
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

    private fun verifyNetworkGeneration(attemptGeneration: Long) {
        if (networkGeneration.get() != attemptGeneration) throw IOException("网络已变化")
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
        return "cd -- $dir && if command -v tmux >/dev/null 2>&1; then tmux new-session -A -D -s $sessionName -c $dir $tmuxCommand; elif command -v screen >/dev/null 2>&1; then screen -D -RR -S $sessionName sh -lc $tmuxCommand; else printf '\\n[提示] 未找到 tmux 或 screen，断线后无法保留 CLI 会话\\n'; $agentInvocation; fi\n"
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
