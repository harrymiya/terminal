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
import java.util.concurrent.atomic.AtomicBoolean

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

    // 匹配提示的正则表达式，对齐 PC 桌面端
    val ActionRequestRegex = Regex(
        "(?i)(?:approv(?:e|al)|allow|would you like|do you want|proceed|continue|permission|needs your approval|run this command\\?|授权|批准|是否允许|需要确认|是否继续|确认执行)"
    )

    private val EnterPromptRegex = Regex(
        "(?i)(?:press\\s+(?:enter|return)\\s+to\\s+(?:continue|confirm|approve)|按\\s*(?:回车|enter)\\s*(?:继续|确认|批准|同意))[:\\s?.!]*$"
    )

    private val YesDefaultRegex = Regex(
        "(?i)(?:\\[Y/(?:n|no)\\]|\\(Y/(?:n|no)\\)|\\[Yes/no\\]|\\(Yes/no\\))[:\\s?.]*$"
    )

    private val OptionRegex = Regex(
        """^\s*(?:[›❯>➜→*●◉]|\[[xX*]\]|\([xX*]\))\s*(?:\d+[.)]\s*)?(.*)$"""
    )

    private val AffirmativeRegex = Regex(
        "(?i)^(?:yes\\b|allow\\b|approve\\b|proceed\\b|continue\\b|always\\b|允许|批准|同意|继续|确认|总是)"
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

        // 1. 优先匹配后 6 行内的 [Y/n] 或 Press enter to continue
        for (i in (rawLines.size - 1) downTo maxOf(0, rawLines.size - 6)) {
            val line = rawLines[i]
            if (YesDefaultRegex.containsMatchIn(line) || EnterPromptRegex.containsMatchIn(line)) {
                val start = maxOf(0, i - 4)
                return normalizeSignature(rawLines.subList(start, i + 1).joinToString("\n"))
            }
        }

        // 2. 匹配交互式多选菜单：当前选中了明确的积极选项 (Yes/允许/批准/继续) 且前文存在审批诉求
        for (i in (rawLines.size - 1) downTo maxOf(0, rawLines.size - 12)) {
            val match = OptionRegex.find(rawLines[i]) ?: continue
            val optionText = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (!AffirmativeRegex.containsMatchIn(optionText)) return null

            for (q in (i - 1) downTo maxOf(0, i - 25)) {
                if (ActionRequestRegex.containsMatchIn(rawLines[q])) {
                    val start = maxOf(0, q - 4)
                    return normalizeSignature(rawLines.subList(start, i + 1).joinToString("\n"))
                }
            }
            return null
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

    private class ScheduledTask(
        val sessionId: String,
        val promptSignature: String,
        val future: ScheduledFuture<*>,
        val deadlineUptimeMillis: Long,
    )

    private val sessions = ConcurrentHashMap<String, SessionCallback>()
    private val activeTasks = ConcurrentHashMap<String, ScheduledTask>()
    private val handledSignatures = ConcurrentHashMap<String, String>()
    private val awaitingResponses = ConcurrentHashMap<String, Boolean>()

    init {
        // 后台看门狗，每 500ms 扫描一次已连接会话，防止在息屏或极端休眠状态下漏掉未触发的确认
        scheduler.scheduleWithFixedDelay({
            runCatching {
                sessions.forEach { (id, callback) ->
                    if (callback.isConnected() && !callback.isClosed()) {
                        checkSessionPrompt(id, callback, fromWatchdog = true)
                    }
                }
            }
        }, 1_000L, 500L, TimeUnit.MILLISECONDS)
    }

    fun registerSession(sessionId: String, callback: SessionCallback) {
        sessions[sessionId] = callback
    }

    fun unregisterSession(sessionId: String) {
        sessions.remove(sessionId)
        cancel(sessionId)
        handledSignatures.remove(sessionId)
        awaitingResponses.remove(sessionId)
    }

    fun onOutputReceived(sessionId: String) {
        val callback = sessions[sessionId] ?: return
        scheduler.execute {
            checkSessionPrompt(sessionId, callback, fromWatchdog = false)
        }
    }

    fun markHandled(sessionId: String, signature: String?) {
        if (signature != null) {
            handledSignatures[sessionId] = signature
        }
        cancel(sessionId)
    }

    fun cancel(sessionId: String) {
        val task = activeTasks.remove(sessionId)
        task?.future?.cancel(false)
        awaitingResponses.remove(sessionId)
        releaseWakeLock()
        sessions[sessionId]?.let { callback ->
            mainHandler.post { callback.onAutoEnterCancelled() }
        }
    }

    fun pause(sessionId: String) {
        cancel(sessionId)
        handledSignatures.remove(sessionId)
    }

    @Volatile var globalEnabled: Boolean = true
    @Volatile var globalDelaySeconds: Int = 3

    private fun checkSessionPrompt(sessionId: String, callback: SessionCallback, fromWatchdog: Boolean) {
        if (!globalEnabled || !callback.isConnected() || callback.isClosed()) {
            if (activeTasks.containsKey(sessionId)) cancel(sessionId)
            return
        }

        val tail = callback.getVisibleTail()
        val hasRequest = ActionRequestRegex.containsMatchIn(tail)
        mainHandler.post { callback.onNeedsActionChanged(hasRequest) }

        val signature = if (hasRequest) detectPromptSignature(tail) else null
        val currentTask = activeTasks[sessionId]
        val handled = handledSignatures[sessionId]

        if (signature == null) {
            if (currentTask != null) cancel(sessionId)
            handledSignatures.remove(sessionId)
            awaitingResponses.remove(sessionId)
            return
        }

        if (signature == handled) {
            // 该 prompt 已处理或已被用户手动操作取消，等待 prompt 发生改变
            return
        }

        if (awaitingResponses[sessionId] == true) {
            // 已发送回车，等待远端响应变动
            return
        }

        if (currentTask != null) {
            if (currentTask.promptSignature != signature) {
                // 提示内容发生变化，取消原任务并重新倒计时
                cancel(sessionId)
            } else {
                // 签名一致且倒计时任务已在运行中，继续保持
                return
            }
        }

        // 启动后台倒计时调度
        val delaySec = globalDelaySeconds.coerceIn(1, 30)
        val delayMillis = delaySec * 1_000L
        val deadline = SystemClock.uptimeMillis() + delayMillis

        // 倒计时期间持有 CPU WakeLock，防止手机在倒数期间进入 Deep Sleep
        acquireWakeLock(delayMillis + 3_000L)

        val scheduledFuture = scheduler.schedule({
            executeAutoEnter(sessionId, signature, callback)
        }, delayMillis, TimeUnit.MILLISECONDS)

        activeTasks[sessionId] = ScheduledTask(sessionId, signature, scheduledFuture, deadline)
        mainHandler.post {
            callback.onAutoEnterScheduled(deadline, signature)
        }
    }

    private fun executeAutoEnter(sessionId: String, expectedSignature: String, callback: SessionCallback) {
        try {
            // 确保执行时持有 WakeLock 唤醒 CPU
            acquireWakeLock(5_000L)
            val currentTask = activeTasks[sessionId]
            if (currentTask == null || currentTask.promptSignature != expectedSignature) return

            if (!globalEnabled || !callback.isConnected() || callback.isClosed()) {
                cancel(sessionId)
                return
            }

            // 再次校验当前的终端提示是否仍然有效
            val currentTail = callback.getVisibleTail()
            val currentSignature = detectPromptSignature(currentTail)
            if (currentSignature != expectedSignature) {
                cancel(sessionId)
                return
            }

            // 核心步骤：直接在后台向 SSH 发送回车字符！
            val sent = callback.sendInput("\r")
            activeTasks.remove(sessionId)
            if (sent) {
                awaitingResponses[sessionId] = true
                handledSignatures[sessionId] = expectedSignature
                mainHandler.post {
                    callback.onAutoEnterTriggered(expectedSignature)
                }
            }
        } finally {
            releaseWakeLock()
        }
    }
}
