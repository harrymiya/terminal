package dev.remote.terminal.jarvis.watcher

import android.os.SystemClock
import android.util.Log
import dev.remote.terminal.jarvis.coordinator.JarvisCoordinator
import dev.remote.terminal.jarvis.mcp.JarvisToolRegistry
import dev.remote.terminal.jarvis.mcp.SessionSelector
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
    val tokenUsed: Int = 0, // 0 Token 本地感知
    val lastObservedSummary: String = "",
    val currentPollIntervalMs: Long = 0
)

/**
 * 钢铁侠贾维斯任务监工中枢 (Jarvis Watch Overseer)。
 * 业界最科学的低功耗、低Token终端与工作区任务看护方案：
 * 1. 0 Token 本地感知：本地正则过滤 ANSI 乱码与光标/进度条抖动，仅通过内容哈希与本地规则检测完成/异常；
 * 2. 动态自适应心跳：终端无输出变化时指数级退避（3s -> 6s -> 12s -> 25s），终端持续输出时保持 3s 灵敏探测；
 * 3. 极低电池功耗：全协程非阻塞挂起，零 CPU 空转，支持 20 分钟超时自动解除保护，防止后台无限耗电；
 * 4. 关键里程碑主动语音通报：检测到构建成功、错误退出或等待人工确认时主动语音唤起。
 */
object JarvisWatchOverseer {
    private const val TAG = "JarvisWatchOverseer"
    private const val MIN_POLL_INTERVAL_MS = 3_000L
    private const val MAX_POLL_INTERVAL_MS = 25_000L
    private const val MAX_WATCH_DURATION_MS = 20 * 60 * 1_000L // 20分钟超时保护

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watchJob: Job? = null

    @Volatile
    private var currentTarget: WatchTarget? = null
    @Volatile
    private var lastCleanHash = ""
    @Volatile
    private var checkCounter = 0
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
        """(?i)(?:\[y/N\]|\[Y/n\]|\(yes/no\)|Are you sure you want to continue\?|password for|Press Enter to continue)"""
    )

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
        currentIntervalMs = MIN_POLL_INTERVAL_MS
        lastStatusDescription = "正在持续观察 $wsName"
        _isWatchingFlow.value = true

        watchJob = scope.launch {
            val startTime = SystemClock.elapsedRealtime()
            Log.i(TAG, "Started watching task in $wsName (dynamic adaptive backoff: 3s~25s, 0 Token)")

            while (isActive && _isWatchingFlow.value) {
                // 超时保护
                if (SystemClock.elapsedRealtime() - startTime > MAX_WATCH_DURATION_MS) {
                    Log.i(TAG, "Watch duration exceeded 20 minutes, auto-disarming to protect battery")
                    JarvisCoordinator.speakWithState("先生，工作区 $wsName 已监工超过 20 分钟，自动解除监工状态以节约电量。")
                    break
                }

                checkCounter++
                val screenResult = JarvisToolRegistry.readScreenHandler?.invoke(target.selector, 35)
                val rawScreen = screenResult?.second.orEmpty()
                val ok = screenResult?.first == true

                if (ok && rawScreen.isNotBlank()) {
                    val cleanScreen = sanitizeScreen(rawScreen)
                    val newHash = hashString(cleanScreen)

                    if (newHash == lastCleanHash) {
                        // 无实质语义变化 -> 指数级退避降低轮询频次，节省 CPU 与功耗
                        currentIntervalMs = (currentIntervalMs * 1.5).toLong().coerceAtMost(MAX_POLL_INTERVAL_MS)
                    } else {
                        // 检测到输出变化 -> 恢复灵敏探测间隔
                        lastCleanHash = newHash
                        currentIntervalMs = MIN_POLL_INTERVAL_MS

                        val lastLine = cleanScreen.lines().lastOrNull { it.isNotBlank() } ?: ""
                        lastStatusDescription = lastLine.take(60)

                        // 0 Token 本地匹配里程碑事件
                        if (target.notifyOnCompletion && COMPLETION_REGEX.containsMatchIn(cleanScreen)) {
                            Log.i(TAG, "Milestone detected: Task completed in $wsName")
                            JarvisCoordinator.speakWithState("先生，工作区 $wsName 的任务已顺利完成。")
                            break
                        } else if (target.notifyOnError && ERROR_REGEX.containsMatchIn(cleanScreen)) {
                            Log.i(TAG, "Milestone detected: Task error in $wsName")
                            JarvisCoordinator.speakWithState("先生，工作区 $wsName 出现执行错误，请查看。")
                            break
                        } else if (CONFIRMATION_BLOCKED_REGEX.containsMatchIn(cleanScreen)) {
                            Log.i(TAG, "Milestone detected: Confirmation required in $wsName")
                            JarvisCoordinator.speakWithState("先生，工作区 $wsName 正在等待确认操作。")
                        }
                    }
                } else {
                    currentIntervalMs = (currentIntervalMs * 1.5).toLong().coerceAtMost(MAX_POLL_INTERVAL_MS)
                }

                delay(currentIntervalMs)
            }

            _isWatchingFlow.value = false
            currentTarget = null
        }

        return true to "已开启对工作区 $wsName 的后台低功耗低Token监工。"
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
                tokenUsed = 0,
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
