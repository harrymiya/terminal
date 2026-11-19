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
        """(?:\[Y/(?:n|no)\]|\(Y/(?:n|no)\)|\[Yes/no\]|\(Yes/no\)|\[Y\]/n)\s*[:?？>›❯.!]*\s*$"""
    )
    private val YesNoPromptRegex = Regex(YesDefaultRegex.pattern, RegexOption.IGNORE_CASE)

    // 多选菜单的光标选项
    private val OptionRegex = Regex(
        """^\s*(?:[›❯>➜→*●◉]|\[[xX*]\]|\([xX*]\))\s*(?:\d+[.)]\s*)?(.*)$"""
    )

    private val ExplicitCursorRegex = Regex(
        """^\s*(?:[›❯●◉]|\[[xX*]\]|\([xX*]\))"""
    )
    private val CodexFooter = Regex("""(?i)^press\s+(?:enter|return)\s+to\s+confirm\b""")
    private val CodexTitle = Regex(
        """(?i)^(?:would you like to (?:run|send input|grant|make)|do you want to (?:approve network access|make)|.+ needs your approval\.)"""
    )
    private val NumberedOption = Regex("""^(?:[›❯>]\s*)?\d+[.)]\s*""")
    private val CodexOnce = Regex(
        """(?i)^yes(?:,\s*(?:proceed|just this once|grant these permissions for this turn|grant for this turn with strict auto review))?\s*(?:\([^)]*\)|\[[^]]*\])?$"""
    )

    // 积极肯定的选项文字（包含 Yes, Allow, Approve, Proceed, Continue, Resume, Restore, 允许, 确认, 恢复 等）
    private val AffirmativeRegex = Regex(
        """(?i)^(?:yes\b|allow\b|approve\b|proceed\b|continue\b|resume\b|restore\b|confirm\b|authorize\b|authorise\b|grant\b|always\b|accept\b|apply\b|run\b|execute\b|ok\b|允许|批准|同意|继续|确认|总是|恢复|执行|确定|应用|授权)"""
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
    fun detectPromptSignature(text: String, codex: Boolean = false): String? {
        val visibleText = text.trimEnd()
        val rawLines = visibleText.lineSequence()
            .map { it.trim().trim('│', '┃', '║', '|', ' ') }
            .filter { it.isNotBlank() && !it.all { char -> char in "─━═╭╮╰╯┌┐└┘+ " } &&
                !Regex("""^\[(?:rdt_[0-9a-fA-F]+|\d+)\]\s+\d+:\S+""").containsMatchIn(it) }
            .toList()
        if (rawLines.isEmpty()) return null
        if (codex) return detectCodexApproval(rawLines)

        // An inline yes/no prompt can follow an older menu still on screen.
        val optionWindowStart = maxOf(0, rawLines.size - 45)
        for (i in (rawLines.size - 1) downTo optionWindowStart) {
            val line = rawLines[i]
            if (i >= rawLines.size - 20 && YesNoPromptRegex.containsMatchIn(line)) {
                if (!YesDefaultRegex.containsMatchIn(line)) return null
                return normalizeSignature(rawLines.subList(maxOf(0, i - 4), i + 1).joinToString("\n"))
            }
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

        // Check menu selections first because an Enter hint can appear below a rejection.
        for (i in (rawLines.size - 1) downTo maxOf(0, rawLines.size - 20)) {
            val line = rawLines[i]
            if (EnterPromptRegex.containsMatchIn(line)) {
                val start = maxOf(0, i - 4)
                return normalizeSignature(rawLines.subList(start, i + 1).joinToString("\n"))
            }
        }

        return null
    }

    private fun detectCodexApproval(lines: List<String>): String? {
        val footer = (lines.lastIndex downTo maxOf(0, lines.size - 6)).firstOrNull { index ->
            CodexFooter.containsMatchIn(lines.drop(index).take(3).joinToString(" "))
        }
        val end = footer ?: lines.size
        val selected = (end - 1 downTo maxOf(0, end - 45)).firstOrNull {
            ExplicitCursorRegex.containsMatchIn(lines[it]) || lines[it].startsWith("> ")
        }
        if (selected == null) {
            val inline = lines.last()
            return inline.takeIf { footer == null && YesDefaultRegex.containsMatchIn(it) }?.let(::normalizeSignature)
        }
        val title = (selected - 1 downTo 0).firstOrNull { CodexTitle.containsMatchIn(lines[it]) }
        if (footer == null && title == null) return null
        val option = OptionRegex.find(lines[selected]) ?: return null
        val parts = listOf(option.groupValues[1].trim()) +
            lines.subList(selected + 1, end).take(3).takeWhile { !NumberedOption.containsMatchIn(it) }
        val label = parts.joinToString(" ").replace(Regex("""\s+"""), " ").trim()
        if (!CodexOnce.matches(label) || (title == null && ',' !in label)) return null

        // Use the approval text next to the choices. Output elsewhere in the viewport
        // can be repainted while Codex is still waiting on the same request.
        val start = maxOf(title ?: 0, selected - 2)
        return (listOfNotNull(title?.takeIf { it < start }?.let(lines::get)) +
            lines.subList(start, selected) + label).joinToString("\n")
    }

    interface SessionCallback {
        fun isConnected(): Boolean
        fun isClosed(): Boolean
        fun usesCodexApproval(): Boolean
        fun getVisibleTail(): String
        fun sendInput(text: String): Boolean
        fun onAutoEnterScheduled(deadlineUptimeMillis: Long)
        fun onAutoEnterCancelled()
        fun onNeedsActionChanged(needsAction: Boolean)
    }

    private class SessionState(
        val callback: SessionCallback,
        var signature: String? = null,
        var detectedAtUptime: Long = 0L,
        var handled: Boolean = false,
        var deadlineUptime: Long? = null,
        var timer: ScheduledFuture<*>? = null,
    )

    private val sessions = ConcurrentHashMap<String, SessionState>()

    @Volatile var globalEnabled: Boolean = true
    @Volatile var globalDelayTenths: Int = 30

    init {
        // 后台看门狗，每 300ms 扫描一次已连接会话，确保即便在熄屏或休眠下也不漏掉任何确认
        scheduler.scheduleWithFixedDelay({
            sessions.forEach { (id, state) ->
                runCatching { processSession(id, state) }
            }
        }, 500L, 300L, TimeUnit.MILLISECONDS)
    }

    fun registerSession(sessionId: String, callback: SessionCallback) {
        sessions[sessionId] = SessionState(callback)
    }

    fun unregisterSession(sessionId: String) {
        sessions.remove(sessionId)?.let { state -> synchronized(state) { clearDeadline(state) } }
    }

    fun onOutputReceived(sessionId: String) {
        val state = sessions[sessionId] ?: return
        scheduler.execute {
            processSession(sessionId, state)
        }
    }

    fun markHandled(sessionId: String) {
        val state = sessions[sessionId] ?: return
        synchronized(state) {
            state.signature = detectPromptSignature(state.callback.getVisibleTail(), state.callback.usesCodexApproval())
            state.handled = true
            clearDeadline(state)
        }
    }

    fun reset(sessionId: String) {
        val state = sessions[sessionId] ?: return
        synchronized(state) {
            state.signature = null
            state.handled = false
            clearDeadline(state)
        }
    }

    private fun clearDeadline(state: SessionState) {
        state.timer?.cancel(false)
        state.timer = null
        if (state.deadlineUptime == null) return
        state.deadlineUptime = null
        mainHandler.post { state.callback.onAutoEnterCancelled() }
    }

    private fun processSession(sessionId: String, state: SessionState) {
        synchronized(state) {
            if (sessions[sessionId] !== state) return
            val callback = state.callback
            if (!globalEnabled || !callback.isConnected() || callback.isClosed()) {
                state.signature = null
                state.handled = false
                clearDeadline(state)
                return
            }

            val tail = callback.getVisibleTail()
            val codex = callback.usesCodexApproval()
            val detectedSig = detectPromptSignature(tail, codex)
            val hasRequest = detectedSig != null || ActionRequestRegex.containsMatchIn(tail)
            mainHandler.post { callback.onNeedsActionChanged(hasRequest) }

            if (detectedSig == null) {
                state.signature = null
                state.handled = false
                clearDeadline(state)
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (state.signature != detectedSig) {
                // An agent can replace one prompt with the next in a single redraw.
                state.signature = detectedSig
                state.detectedAtUptime = now
                state.handled = false
            }
            if (state.handled) return

            val deadline = state.detectedAtUptime + globalDelayTenths.coerceIn(1, 300) * 100L
            if (now < deadline) {
                if (state.deadlineUptime != deadline) {
                    state.timer?.cancel(false)
                    state.timer = scheduler.schedule({ processSession(sessionId, state) }, deadline - now, TimeUnit.MILLISECONDS)
                    state.deadlineUptime = deadline
                    acquireWakeLock(deadline - now + 3_000L)
                    mainHandler.post { callback.onAutoEnterScheduled(deadline) }
                }
                return
            }
            // Check the visible selection again before sending Enter.
            if (globalEnabled && callback.isConnected() && sessions[sessionId] === state &&
                detectPromptSignature(callback.getVisibleTail(), codex) == detectedSig && callback.sendInput("\r")) {
                state.handled = true
                clearDeadline(state)
            }
        }
    }
}
