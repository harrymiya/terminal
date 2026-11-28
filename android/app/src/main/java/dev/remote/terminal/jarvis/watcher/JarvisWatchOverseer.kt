package dev.remote.terminal.jarvis.watcher

import android.os.SystemClock
import android.util.Log
import dev.remote.terminal.jarvis.coordinator.JarvisCoordinator
import dev.remote.terminal.jarvis.mcp.JarvisToolRegistry
import dev.remote.terminal.jarvis.mcp.SessionSelector
import dev.remote.terminal.jarvis.mcp.WatchSessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest

data class WatchTarget(
    val selector: SessionSelector,
    val workspaceName: String,
    val agentName: String = "",
    val notifyOnCompletion: Boolean = true,
    val notifyOnError: Boolean = true,
    val startedAt: Long = System.currentTimeMillis()
)

data class WatchStatusSnapshot(
    val isWatching: Boolean,
    val workspace: String = "",
    val agent: String = "",
    val durationSeconds: Long = 0,
    val checkCount: Int = 0,
    val tokenUsed: Int = 0, // 平时 0 Token 本地感知；仅卡在自动确认解不开的提示时才请求大模型
    val lastObservedSummary: String = "",
    val currentPollIntervalMs: Long = 0
)

/**
 * 钢铁侠贾维斯任务监工中枢 (Jarvis Watch Overseer)。
 * 业界最科学的低功耗、低Token终端与工作区任务看护方案：
 * 1. 0 Token 本地感知：本地正则过滤 ANSI 乱码与光标/进度条抖动，仅通过内容哈希与本地规则检测完成/异常；
 * 2. 动态自适应心跳：终端无输出变化时指数级退避（3s -> 6s -> 12s -> 25s），终端持续输出时保持 3s 灵敏探测；
 * 3. 大模型兜底确认：当开发者 Agent 卡在等待确认/选择菜单且内置自动回车解决不了时，自动请求大模型理解屏幕并完成按键/文本确认；
 * 4. 极低电池功耗与里程碑主动汇报：支持 20 分钟超时自动解除保护。
 */
object JarvisWatchOverseer {
    private const val TAG = "JarvisWatchOverseer"
    private const val MIN_POLL_INTERVAL_MS = 3_000L
    private const val MAX_POLL_INTERVAL_MS = 25_000L
    private const val MAX_WATCH_DURATION_MS = 20 * 60 * 1_000L // 20分钟超时保护
    private const val MAX_AUTO_LLM_CONFIRMS_PER_WATCH = 6

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watchJob: Job? = null

    @Volatile
    private var currentTarget: WatchTarget? = null
    @Volatile
    private var lastCleanHash = ""
    @Volatile
    private var checkCounter = 0
    @Volatile
    private var llmAssistCounter = 0
    @Volatile
    private var currentIntervalMs = MIN_POLL_INTERVAL_MS
    @Volatile
    private var lastStatusDescription = "待命"

    private val _isWatchingFlow = MutableStateFlow(false)
    val isWatchingFlow: StateFlow<Boolean> = _isWatchingFlow.asStateFlow()

    // 终端 ANSI 控制符正则过滤
    private val ANSI_REGEX = Regex("""\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])""")
    // 进度条/动态 Spinner/时间戳抖动本地过滤 (0 Token 净化)
    private val PROGRESS_SPINNER_REGEX = Regex("""(?m)[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏|/\\-]|[\[#=>\- ]+\d{1,3}%|\b\d{1,2}:\d{2}\b""")

    // 本地里程碑判定规则 (0 Token Local Decision)
    private val COMPLETION_REGEX = Regex(
        """(?i)(?:BUILD SUCCESSFUL|build completed|finished in \d+|done in \d+|process completed with exit code 0|successfully compiled|tests passed|test passed|all \d+ tests passed|commit created|pushed to|git push successful)"""
    )
    private val ERROR_REGEX = Regex(
        """(?i)(?:BUILD FAILED|compilation error|syntaxerror|fatal error|panic:|Traceback \(most recent call last\):|AssertionError|npm ERR!|FAILED \(failures=\d+\)|segmentation fault|exit code [1-9]\d*)"""
    )
    private val CONFIRMATION_BLOCKED_REGEX = Regex(
        """(?i)(?:\[y/N\]|\[Y/n\]|\(y/n\)|\(yes/no\)|\[yes/no\]|Are you sure|Press\s+(?:y|enter|return|any key)\s+to|Type\s+['"]?yes['"]?|Enter\s+(?:your\s+)?(?:choice|selection)|Choose\s+an\s+option|Select\s+(?:an\s+option|one)|Do you want to|Would you like|Allow\s+(?:once|always)|Approve|Waiting for confirmation|password for|请选择|请输入|是否(?:继续|允许|确认|批准|覆盖|执行))"""
    )
    private val NUMBERED_MENU_REGEX = Regex("""(?m)^\s*(?:[›❯>➜●◉]\s*)?(?:1[.)]|\[1\])\s+.+""")

    fun isWatching(): Boolean = _isWatchingFlow.value

    fun startWatching(
        selector: SessionSelector,
        notifyOnCompletion: Boolean = true,
        notifyOnError: Boolean = true
    ): Pair<Boolean, String> {
        val wsName = selector.workspaceName ?: selector.query ?: "当前工作区"
        stopWatching()

        val target = WatchTarget(
            selector = selector,
            workspaceName = wsName,
            agentName = selector.agentName.orEmpty(),
            notifyOnCompletion = notifyOnCompletion,
            notifyOnError = notifyOnError
        )
        currentTarget = target
        lastCleanHash = ""
        checkCounter = 0
        llmAssistCounter = 0
        currentIntervalMs = MIN_POLL_INTERVAL_MS
        lastStatusDescription = "正在持续观察 $wsName"
        _isWatchingFlow.value = true

        watchJob = scope.launch {
            val startTime = SystemClock.elapsedRealtime()
            val attemptedConfirmHashes = mutableSetOf<String>()
            var pendingConfirmHash = ""
            var pendingConfirmSinceMs = 0L

            Log.i(TAG, "Started watching task in $wsName (dynamic adaptive backoff: 3s~25s)")

            while (isActive && _isWatchingFlow.value) {
                if (SystemClock.elapsedRealtime() - startTime > MAX_WATCH_DURATION_MS) {
                    Log.i(TAG, "Watch duration exceeded 20 minutes, auto-disarming to protect battery")
                    JarvisCoordinator.speakWithState("先生，工作区 $wsName 已监工超过 20 分钟，自动解除监工状态以节约电量。")
                    break
                }

                checkCounter++
                val snap = fetchSnapshot(target.selector, 60)
                val effectiveWs = snap?.workspace?.takeIf { it.isNotBlank() } ?: wsName
                val effectiveAgent = snap?.agent?.takeIf { it.isNotBlank() } ?: target.agentName
                val rawScreen = snap?.screenTail.orEmpty()

                if (snap != null && rawScreen.isNotBlank()) {
                    val cleanScreen = sanitizeScreen(rawScreen)
                    val newHash = hashString(cleanScreen)
                    val hashChanged = newHash != lastCleanHash

                    if (hashChanged) {
                        lastCleanHash = newHash
                        currentIntervalMs = MIN_POLL_INTERVAL_MS
                        pendingConfirmHash = ""
                        pendingConfirmSinceMs = 0L

                        val lastLine = cleanScreen.lines().lastOrNull { it.isNotBlank() } ?: ""
                        lastStatusDescription = lastLine.take(60)

                        // 0 Token 本地匹配完成或严重错误里程碑
                        if (target.notifyOnCompletion && COMPLETION_REGEX.containsMatchIn(cleanScreen)) {
                            Log.i(TAG, "Milestone detected: Task completed in $effectiveWs")
                            JarvisCoordinator.speakWithState("先生，工作区 $effectiveWs 的任务已顺利完成。")
                            break
                        } else if (target.notifyOnError && ERROR_REGEX.containsMatchIn(cleanScreen)) {
                            Log.i(TAG, "Milestone detected: Task error in $effectiveWs")
                            JarvisCoordinator.speakWithState("先生，工作区 $effectiveWs 出现执行错误，请查看。")
                            break
                        }
                    } else {
                        currentIntervalMs = (currentIntervalMs * 1.5).toLong().coerceAtMost(MAX_POLL_INTERVAL_MS)
                    }

                    // 检测是否卡在「等待用户确认 / 选择」且内置 AutoConfirmEngine 未能处理
                    val tailSnippet = cleanScreen.lines().filter { it.isNotBlank() }.takeLast(14).joinToString("\n")
                    val looksLikeConfirmation = snap.pendingHostKey ||
                        snap.needsAction ||
                        CONFIRMATION_BLOCKED_REGEX.containsMatchIn(tailSnippet) ||
                        (NUMBERED_MENU_REGEX.containsMatchIn(tailSnippet) && tailSnippet.contains('?'))

                    if (looksLikeConfirmation && !snap.autoEnterPending && newHash !in attemptedConfirmHashes) {
                        val now = SystemClock.elapsedRealtime()
                        if (pendingConfirmHash != newHash) {
                            // 先留出约 4 秒窗口给 0-Token AutoConfirmEngine 优先处理
                            pendingConfirmHash = newHash
                            pendingConfirmSinceMs = now
                            currentIntervalMs = MIN_POLL_INTERVAL_MS
                        } else if (now - pendingConfirmSinceMs >= 4_000L && !snap.isStreaming) {
                            attemptedConfirmHashes.add(newHash)
                            if (llmAssistCounter < MAX_AUTO_LLM_CONFIRMS_PER_WATCH) {
                                llmAssistCounter++
                                lastStatusDescription = "正在请求大模型理解并处理确认…"
                                val outcome = JarvisCoordinator.resolveWatchConfirmationWithModel(
                                    sessionId = snap.sessionId,
                                    workspaceName = effectiveWs,
                                    agentName = effectiveAgent,
                                    screenTail = cleanScreen,
                                    pendingHostKey = snap.pendingHostKey
                                )
                                when (outcome) {
                                    JarvisCoordinator.WatchConfirmOutcome.RESOLVED -> {
                                        lastStatusDescription = "已通过大模型完成确认并继续执行"
                                        currentIntervalMs = MIN_POLL_INTERVAL_MS
                                        pendingConfirmHash = ""
                                    }
                                    JarvisCoordinator.WatchConfirmOutcome.ASKED_USER -> {
                                        lastStatusDescription = "已语音请示用户确认"
                                    }
                                    JarvisCoordinator.WatchConfirmOutcome.WAIT -> {
                                        lastStatusDescription = "开发者仍在输出中"
                                    }
                                    JarvisCoordinator.WatchConfirmOutcome.UNAVAILABLE -> {
                                        Log.i(TAG, "Milestone fallback: Confirmation required in $effectiveWs")
                                        JarvisCoordinator.speakWithState("先生，工作区 $effectiveWs 正在等待确认操作。")
                                    }
                                }
                            } else {
                                JarvisCoordinator.speakWithState("先生，工作区 $effectiveWs 多次需要确认，请亲自查看一下。")
                            }
                        }
                    } else if (snap.autoEnterPending) {
                        // AutoConfirmEngine 正在倒计时处理，保持灵敏心跳等待结果
                        currentIntervalMs = MIN_POLL_INTERVAL_MS
                    }
                } else {
                    currentIntervalMs = (currentIntervalMs * 1.5).toLong().coerceAtMost(MAX_POLL_INTERVAL_MS)
                }

                delay(currentIntervalMs)
            }

            _isWatchingFlow.value = false
            currentTarget = null
        }

        return true to "已开启对工作区 $wsName 的后台低功耗监工。"
    }

    private suspend fun fetchSnapshot(selector: SessionSelector, lines: Int): WatchSessionSnapshot? {
        JarvisToolRegistry.watchSnapshotHandler?.invoke(selector, lines)?.let { return it }
        val legacy = JarvisToolRegistry.readScreenHandler?.invoke(selector, lines) ?: return null
        if (!legacy.first) return null
        return WatchSessionSnapshot(
            sessionId = selector.sessionId.orEmpty(),
            workspace = selector.workspaceName ?: selector.query ?: "",
            agent = selector.agentName.orEmpty(),
            connected = true,
            isStreaming = false,
            needsAction = CONFIRMATION_BLOCKED_REGEX.containsMatchIn(legacy.second),
            autoEnterPending = false,
            pendingHostKey = false,
            screenTail = legacy.second
        )
    }

    fun stopWatching(): Pair<Boolean, String> {
        val target = currentTarget
        watchJob?.cancel()
        watchJob = null
        _isWatchingFlow.value = false
        currentTarget = null
        lastStatusDescription = "监工已停止"
        val msg = if (target != null) "已停止对工作区 ${target.workspaceName} 的监工。" else "当前未在监工。"
        Log.i(TAG, "Stopped watching")
        return true to msg
    }

    fun getStatus(): WatchStatusSnapshot {
        val target = currentTarget
        return if (target != null && _isWatchingFlow.value) {
            WatchStatusSnapshot(
                isWatching = true,
                workspace = target.workspaceName,
                agent = target.agentName,
                durationSeconds = (System.currentTimeMillis() - target.startedAt) / 1000L,
                checkCount = checkCounter,
                tokenUsed = llmAssistCounter,
                lastObservedSummary = lastStatusDescription,
                currentPollIntervalMs = currentIntervalMs
            )
        } else {
            WatchStatusSnapshot(
                isWatching = false,
                lastObservedSummary = lastStatusDescription
            )
        }
    }

    private fun sanitizeScreen(raw: String): String {
        val stripped = ANSI_REGEX.replace(raw, "")
        return PROGRESS_SPINNER_REGEX.replace(stripped, " ").trim()
    }

    private fun hashString(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
