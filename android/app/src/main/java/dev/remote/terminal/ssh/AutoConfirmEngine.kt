package dev.remote.terminal.ssh

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 后台独立的自动确认（Auto-Confirm）引擎。
 * 独立于 UI 线程，即便手机熄屏、Activity 进入后台或 UI 主线程被系统暂时冻结，
 * 也能依靠后台守护调度器与 CPU 局部 WakeLock 精准倒计时并向 SSH 发送回车。
 */
object AutoConfirmEngine {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "terminal-auto-confirm-worker").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    }

    private val mainHandler: Handler? by lazy {
        runCatching { Looper.getMainLooper()?.let(::Handler) }.getOrNull()
    }
    private fun postMain(action: () -> Unit) {
        mainHandler?.post(action)
    }
    @Volatile private var appContext: Context? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    // 匹配请求提示意图（包含 resume / recover / restore 等会话恢复意图）
    val ActionRequestRegex = Regex(
        "(?i)(?:approv(?:e|al)?|allow|would you like|do you want|proceed|continue|resume|recover|restore|permission|needs your approval|run this command\\?|run the following|confirm|execute|apply|install|是否|授权|批准|允许|确认|继续|恢复|执行)"
    )

    // 明确提示按回车（包含各种形式的 Press enter to continue / confirm / resume 及中文“按回车继续/恢复”）
    private val EnterPromptRegex = Regex(
        """(?i)(?:press|hit)\s+(?:<enter>|\[enter\]|enter|return)\b.*?(?:continue|confirm|approve|resume|proceed|accept|apply|run|execute|继续|确认|批准|同意|恢复|执行)|按\s*(?:<回车>|\[回车\]|回车|enter)\s*(?:键)?.*?(?:继续|确认|批准|同意|恢复|执行)|\[enter\]\s*(?:continue|confirm|approve|resume|proceed|继续|确认|恢复)"""
    )

    // 默认回车选择 Yes 的形式（支持 [Y/n], (Y/n), [Yes/no], [Y]/n 以及末尾带标点或光标符号）
    private val YesDefaultRegex = Regex(
        """(?i)(?:\[Y/(?:n|no)\]|\(Y/(?:n|no)\)|\[Yes/no\]|\(Yes/no\)|\[Y\]/n)\s*[:?？>›❯.!]*\s*$"""
    )

    // 多选菜单的光标选项
    private val OptionRegex = Regex(
        """^\s*(?:[›❯>➜→*●◉]|\[[xX*]\]|\([xX*]\))\s*(?:\d+[.)]\s*)?(.*)$"""
    )

    private val ExplicitCursorRegex = Regex(
        """^\s*(?:[›❯●◉]|\[[xX*]\]|\([xX*]\))"""
    )

    // 积极肯定的选项文字（包含 Yes, Allow, Approve, Proceed, Continue, Resume, Restore, 允许, 确认, 恢复 等）
    private val AffirmativeRegex = Regex(
        """(?i)^(?:yes\b|allow\b|approve\b|proceed\b|continue\b|resume\b|restore\b|confirm\b|always\b|accept\b|apply\b|run\b|execute\b|ok\b|允许|批准|同意|继续|确认|总是|恢复|执行|确定|应用)"""
    )

    fun init(context: Context) {
        if (appContext == null) {
            val ctx = context.applicationContext
            appContext = ctx
            runCatching {
                val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${ctx.packageName}:auto-confirm-lock").apply {
                    setReferenceCounted(false)
                }
            }
        }
    }

    /** 去除时钟、耗时、动效旋转字符等动态噪点，避免 spinner 导致 signature 抖动重置倒计时 */
    fun normalizeSignature(sig: String?): String {
        if (sig.isNullOrBlank()) return ""
        var s = sig
        // 去除动态时钟 (HH:MM:SS 或 HH:MM)
        s = s.replace(Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b"""), "")
        // 去除耗时指示 (例如 (2.5s) 或 (12s))
        s = s.replace(Regex("""\(\d+(?:\.\d+)?s\)"""), "")
        // 去除终端旋转动效字符
        s = s.replace(Regex("""[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]"""), "")
        return s.trim()
    }

    /** 从终端可见文本中识别当前待确认提示的签名 */
    fun detectPromptSignature(text: String): String? {
        val visibleText = text.trimEnd()
        val rawLines = visibleText.lineSequence()
            .map { it.trim().trim('│', '┃', '║', '|', ' ') }
            .filter { it.isNotBlank() && !Regex("""^\[(?:rdt_[0-9a-fA-F]+|\d+)\]\s+\d+:\S+""").containsMatchIn(it) }
            .toList()
        if (rawLines.isEmpty()) return null
        return detectClassicPromptSignature(rawLines) ?: detectWrappedApprovalSignature(rawLines)
    }

    private fun detectClassicPromptSignature(rawLines: List<String>): String? {
        // 1. 优先匹配交互式多选菜单（必须在单行 Enter 提示前检查，避免选中 "❯ No" 时误触发下方的 "Press enter to confirm"）
        val optionWindowStart = maxOf(0, rawLines.size - 45)
        for (i in (rawLines.size - 1) downTo optionWindowStart) {
            val line = rawLines[i]
            val match = OptionRegex.find(line) ?: continue
            val optionText = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (AffirmativeRegex.containsMatchIn(optionText)) {
                for (q in (i - 1) downTo maxOf(0, i - 100)) {
                    if (ActionRequestRegex.containsMatchIn(rawLines[q]) || rawLines[q].contains('?') || rawLines[q].contains('？')) {
                        val start = maxOf(0, q - 4)
                        return normalizeSignature(rawLines.subList(start, i + 1).joinToString("\n"))
                    }
                }
                val start = maxOf(0, i - 4)
                return normalizeSignature(rawLines.subList(start, i + 1).joinToString("\n"))
            }
            // 若明确的光标选中了否定/非积极选项（如 "❯ No, cancel"），立即停止以免误匹配上方的列表项
            if (ExplicitCursorRegex.containsMatchIn(line)) {
                return null
            }
        }

        // 2. 匹配后 20 行内的 [Y/n] 或 Press enter to continue/confirm/resume
        for (i in (rawLines.size - 1) downTo maxOf(0, rawLines.size - 20)) {
            val line = rawLines[i]
            if (YesDefaultRegex.containsMatchIn(line) || EnterPromptRegex.containsMatchIn(line)) {
                val start = maxOf(0, i - 4)
                return normalizeSignature(rawLines.subList(start, i + 1).joinToString("\n"))
            }
        }

        return null
    }

    private val WrappedApprovalFooter = Regex(
        """(?i)press\s+(?:enter|return)\s+to\s+confirm\s+or\s+(?:esc|escape)\s+to\s+cancel[.!]?"""
    )
    private val ApprovalCursor = Regex("""^(?:[›❯➜●◉]|>\s*\d+[.)])""")
    private val ApprovalChoice = Regex("""^(?:[›❯>➜●◉]\s*)?\d+[.)]\s*(.*)$""")
    private val ApproveOnce = Regex(
        """(?i)(?:yes,\s*(?:proceed|just this once|grant these permissions for this turn|grant for this turn with strict auto review)|allow once)\s*(?:\([a-z]\))?"""
    )

    private fun detectWrappedApprovalSignature(rawLines: List<String>): String? {
        val lines = rawLines.filterNot { it.all { char -> char in "─━═╭╮╰╯┌┐└┘+ " } }
        val footer = (lines.lastIndex downTo maxOf(0, lines.size - 6)).firstOrNull { start ->
            (start + 1..minOf(lines.size, start + 3)).any { end ->
                WrappedApprovalFooter.matches(lines.subList(start, end).joinToString(" "))
            }
        } ?: return null
        val selected = (footer - 1 downTo 0).firstOrNull { ApprovalCursor.containsMatchIn(lines[it]) } ?: return null
        val choice = ApprovalChoice.matchEntire(lines[selected]) ?: return null
        val continuation = lines.subList(selected + 1, minOf(footer, selected + 6))
            .takeWhile { !ApprovalChoice.containsMatchIn(it) }
        if (continuation.isEmpty()) return null
        val label = (listOf(choice.groupValues[1]) + continuation)
            .joinToString(" ").replace(Regex("""\s+"""), " ").trim()
        if (!ApproveOnce.matches(label) && !AffirmativeRegex.containsMatchIn(label)) return null
        return normalizeSignature((lines.subList(maxOf(0, selected - 2), selected) + label).joinToString("\n"))
    }

    interface SessionCallback {
        fun isConnected(): Boolean
        fun isClosed(): Boolean
        fun getVisibleTail(): String
        fun sendInput(text: String): Boolean
        fun onAutoEnterScheduled(deadlineUptimeMillis: Long, promptSignature: String)
        fun onAutoEnterCancelled()
        fun onAutoEnterTriggered(promptSignature: String)
        fun onNeedsActionChanged(needsAction: Boolean)
    }

    private class SessionState(
        var signature: String? = null,
        var detectedAtUptime: Long = 0L,
        var handledSignature: String? = null,
        var awaitingResponse: Boolean = false,
        var deadlineUptime: Long? = null,
    )

    private val sessions = ConcurrentHashMap<String, SessionCallback>()
    private val sessionStates = ConcurrentHashMap<String, SessionState>()
    private val queuedScans = ConcurrentHashMap.newKeySet<String>()
    private val wakeLockSessions = ConcurrentHashMap.newKeySet<String>()

    @Volatile var globalEnabled: Boolean = true
    @Volatile var globalDelaySeconds: Int = 3

    init {
        // 后台看门狗，每 300ms 扫描一次已连接会话，确保即便在熄屏或休眠下也不漏掉任何确认
        scheduler.scheduleWithFixedDelay({
            runCatching {
                sessions.forEach { (id, callback) ->
                    if (callback.isConnected() && !callback.isClosed()) {
                        processSession(id, callback)
                    }
                }
            }
        }, 500L, 300L, TimeUnit.MILLISECONDS)
    }

    fun registerSession(sessionId: String, callback: SessionCallback) {
        sessions[sessionId] = callback
        sessionStates.putIfAbsent(sessionId, SessionState())
    }

    fun unregisterSession(sessionId: String) {
        sessions.remove(sessionId)
        sessionStates.remove(sessionId)
        queuedScans.remove(sessionId)
        releaseWakeLock(sessionId)
    }

    fun onOutputReceived(sessionId: String) {
        val callback = sessions[sessionId] ?: return
        // Terminal output can arrive hundreds of times per second. A scan always
        // reads the latest screen, so queue at most one scan per session instead
        // of letting output chunks starve the periodic deadline watchdog.
        if (!queuedScans.add(sessionId)) return
        scheduler.execute {
            try {
                if (sessions[sessionId] === callback) processSession(sessionId, callback)
            } finally {
                queuedScans.remove(sessionId)
            }
        }
    }

    fun markHandled(sessionId: String, signature: String?) {
        val state = sessionStates[sessionId] ?: return
        synchronized(state) {
            state.handledSignature = signature ?: state.signature
            state.deadlineUptime = null
            state.awaitingResponse = false
        }
        releaseWakeLock(sessionId)
        sessions[sessionId]?.let { callback ->
            postMain { callback.onAutoEnterCancelled() }
        }
    }

    fun cancel(sessionId: String) {
        val state = sessionStates[sessionId] ?: return
        synchronized(state) {
            state.handledSignature = state.signature
            state.deadlineUptime = null
            state.awaitingResponse = false
        }
        releaseWakeLock(sessionId)
        sessions[sessionId]?.let { callback ->
            postMain { callback.onAutoEnterCancelled() }
        }
    }

    fun pause(sessionId: String) {
        val state = sessionStates[sessionId] ?: return
        synchronized(state) {
            state.deadlineUptime = null
            state.awaitingResponse = false
            state.handledSignature = null
            state.signature = null
        }
        releaseWakeLock(sessionId)
        sessions[sessionId]?.let { callback ->
            postMain { callback.onAutoEnterCancelled() }
        }
    }

    internal fun processSessionForTest(
        sessionId: String,
        callback: SessionCallback,
        nowMillis: Long,
    ) = processSession(sessionId, callback, nowMillis)

    private fun processSession(
        sessionId: String,
        callback: SessionCallback,
        nowMillis: Long = SystemClock.elapsedRealtime(),
    ) {
        val state = sessionStates[sessionId] ?: return
        synchronized(state) {
            if (!callback.isConnected() || callback.isClosed()) {
                if (state.deadlineUptime != null || state.signature != null || state.awaitingResponse) {
                    state.deadlineUptime = null
                    state.signature = null
                    state.handledSignature = null
                    state.awaitingResponse = false
                    releaseWakeLock(sessionId)
                    postMain { callback.onAutoEnterCancelled() }
                }
                return@synchronized
            }

            if (!globalEnabled) {
                if (state.deadlineUptime != null) {
                    state.deadlineUptime = null
                    releaseWakeLock(sessionId)
                    postMain { callback.onAutoEnterCancelled() }
                }
                return@synchronized
            }

            val tail = callback.getVisibleTail()
            val detectedSig = detectPromptSignature(tail)
            val hasRequest = detectedSig != null || ActionRequestRegex.containsMatchIn(tail)

            postMain { callback.onNeedsActionChanged(hasRequest) }

            if (detectedSig == null) {
                // 终端提示已消失（例如上一条命令已继续执行或已被清屏）
                if (state.deadlineUptime != null || state.signature != null) {
                    state.signature = null
                    state.handledSignature = null
                    state.awaitingResponse = false
                    state.deadlineUptime = null
                    releaseWakeLock(sessionId)
                    postMain { callback.onAutoEnterCancelled() }
                }
                return@synchronized
            }

            if (detectedSig == state.handledSignature) {
                // 该 prompt 已处理完毕或已被用户主动操作忽略
                return@synchronized
            }

            // 只要签名已经变化为新的未处理 prompt，说明远端已响应上一条确认并进入下一条确认
            state.awaitingResponse = false

            val now = nowMillis
            val delayMillis = globalDelaySeconds.coerceIn(1, 30) * 1_000L

            if (state.signature != detectedSig) {
                // 首次检测到该提示或提示内容发生变化，开始倒计时
                state.signature = detectedSig
                state.detectedAtUptime = now
                val deadline = now + delayMillis
                state.deadlineUptime = deadline

                // 持有 CPU WakeLock，防止手机在倒数期间进入 Deep Sleep
                acquireWakeLock(sessionId)

                postMain {
                    callback.onAutoEnterScheduled(deadline, detectedSig)
                    dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.onApprovalRequired(detectedSig.take(30))
                }
                return@synchronized
            }

            // signature 一致，检查倒计时是否到达
            val elapsed = now - state.detectedAtUptime
            if (elapsed >= delayMillis) {
                // 倒计时已满，直接在后台发送回车！
                try {
                    val sent = callback.sendInput("\r")
                    if (sent) {
                        state.handledSignature = detectedSig
                        state.awaitingResponse = true
                        state.deadlineUptime = null
                        postMain {
                            callback.onAutoEnterTriggered(detectedSig)
                        }
                    }
                } finally {
                    releaseWakeLock(sessionId)
                }
            }
        }
    }

    @Synchronized
    private fun acquireWakeLock(sessionId: String) {
        if (!wakeLockSessions.add(sessionId)) return
        if (wakeLockSessions.size == 1) runCatching { wakeLock?.acquire() }
    }

    @Synchronized
    private fun releaseWakeLock(sessionId: String) {
        if (!wakeLockSessions.remove(sessionId)) return
        if (wakeLockSessions.isEmpty()) {
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }
}
