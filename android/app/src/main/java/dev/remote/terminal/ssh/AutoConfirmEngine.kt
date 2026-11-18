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

    private val mainHandler = Handler(Looper.getMainLooper())
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
        "(?i)^(?:yes\b|allow\b|approve\b|proceed\b|continue\b|resume\b|restore\b|confirm\b|always\b|accept\b|apply\b|run\b|execute\b|ok\b|允许|批准|同意|继续|确认|总是|恢复|执行|确定|应用)"
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

    private fun acquireWakeLock(timeoutMs: Long = 10_000L) {
        runCatching {
            wakeLock?.acquire(timeoutMs)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
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
        releaseWakeLock()
    }

    fun onOutputReceived(sessionId: String) {
        val callback = sessions[sessionId] ?: return
        scheduler.execute {
            processSession(sessionId, callback)
        }
    }

    fun markHandled(sessionId: String, signature: String?) {
        val state = sessionStates[sessionId] ?: return
        state.handledSignature = signature ?: state.signature
        state.deadlineUptime = null
        state.awaitingResponse = false
        releaseWakeLock()
        sessions[sessionId]?.let { callback ->
            mainHandler.post { callback.onAutoEnterCancelled() }
        }
    }

    fun cancel(sessionId: String) {
        val state = sessionStates[sessionId] ?: return
        state.deadlineUptime = null
        state.awaitingResponse = false
        releaseWakeLock()
        sessions[sessionId]?.let { callback ->
            mainHandler.post { callback.onAutoEnterCancelled() }
        }
    }

    fun pause(sessionId: String) {
        cancel(sessionId)
        val state = sessionStates[sessionId] ?: return
        state.handledSignature = null
        state.signature = null
    }

    private fun processSession(sessionId: String, callback: SessionCallback) {
        if (!globalEnabled || !callback.isConnected() || callback.isClosed()) {
            val state = sessionStates[sessionId]
            if (state?.deadlineUptime != null) {
                state.deadlineUptime = null
                mainHandler.post { callback.onAutoEnterCancelled() }
            }
            return
        }

        val state = sessionStates.getOrPut(sessionId) { SessionState() }
        val tail = callback.getVisibleTail()
        val detectedSig = detectPromptSignature(tail)
        val hasRequest = detectedSig != null || ActionRequestRegex.containsMatchIn(tail)

        mainHandler.post { callback.onNeedsActionChanged(hasRequest) }

        if (detectedSig == null) {
            // 终端提示已消失（例如上一条命令已继续执行或已被清屏）
            if (state.deadlineUptime != null || state.signature != null) {
                state.signature = null
                state.handledSignature = null
                state.awaitingResponse = false
                state.deadlineUptime = null
                mainHandler.post { callback.onAutoEnterCancelled() }
            }
            return
        }

        if (detectedSig == state.handledSignature) {
            // 该 prompt 已处理完毕或已被用户主动操作忽略
            return
        }

        if (state.awaitingResponse) {
            // 已发送回车，等待远端响应变动
            return
        }

        val now = SystemClock.elapsedRealtime()
        val delayMillis = globalDelaySeconds.coerceIn(1, 30) * 1_000L

        if (state.signature != detectedSig) {
            // 首次检测到该提示或提示内容发生变化，开始倒计时
            state.signature = detectedSig
            state.detectedAtUptime = now
            val deadline = now + delayMillis
            state.deadlineUptime = deadline

            // 持有 CPU WakeLock，防止手机在倒数期间进入 Deep Sleep
            acquireWakeLock(delayMillis + 3_000L)

            mainHandler.post {
                callback.onAutoEnterScheduled(deadline, detectedSig)
            }
            return
        }

        // signature 一致，检查倒计时是否到达
        val elapsed = now - state.detectedAtUptime
        if (elapsed >= delayMillis) {
            // 倒计时已满，直接在后台发送回车！
            try {
                acquireWakeLock(5_000L)
                val sent = callback.sendInput("\r")
                if (sent) {
                    state.handledSignature = detectedSig
                    state.awaitingResponse = true
                    state.deadlineUptime = null
                    mainHandler.post {
                        callback.onAutoEnterTriggered(detectedSig)
                    }
                }
            } finally {
                releaseWakeLock()
            }
        }
    }
}
