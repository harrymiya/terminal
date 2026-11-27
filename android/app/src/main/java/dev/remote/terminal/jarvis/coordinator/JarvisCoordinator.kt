package dev.remote.terminal.jarvis.coordinator

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.remote.terminal.jarvis.api.JarvisAction
import dev.remote.terminal.jarvis.api.JarvisConfig
import dev.remote.terminal.jarvis.api.JarvisContext
import dev.remote.terminal.jarvis.api.JarvisState
import dev.remote.terminal.jarvis.api.VoicePerceptor
import dev.remote.terminal.jarvis.api.VoicePerceptorCallback
import dev.remote.terminal.jarvis.audio.BluetoothScoManager
import dev.remote.terminal.jarvis.audio.EarconManager
import dev.remote.terminal.jarvis.intent.JarvisIntentRouter
import dev.remote.terminal.jarvis.perception.JarvisPerceptorFactory
import dev.remote.terminal.jarvis.perception.OfflineJarvisPerceptor
import dev.remote.terminal.jarvis.tts.JarvisSpeaker
import dev.remote.terminal.ssh.SshTerminalController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 钢铁侠贾维斯语音中枢总协调器 (Master Coordinator Singleton)。
 * 连接蓝牙通道、感知引擎、意图路由器、发音器与底层 SSH 终端会话，实现全流程闭环控制。
 */
object JarvisCoordinator : VoicePerceptorCallback {

    private const val TAG = "JarvisCoordinator"
    private const val CONVERSATION_IDLE_TIMEOUT_SEC = 20
    private const val CONVERSATION_WINDOW_MS = CONVERSATION_IDLE_TIMEOUT_SEC * 1_000L
    private val scope = CoroutineScope(Dispatchers.Main)

    val config = JarvisConfig()
    private var appContext: Context? = null

    private var scoManager: BluetoothScoManager? = null
    private val earconManager = EarconManager()
    private var speaker: JarvisSpeaker? = null
    private var intentRouter: JarvisIntentRouter? = null
    private var perceptor: VoicePerceptor? = null
    private var conversationIdleJob: kotlinx.coroutines.Job? = null
    private var chatJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var conversationAwakeUntilMs: Long = 0L

    @Volatile
    private var wakeConfirmedThisTurn: Boolean = false

    @Volatile
    private var userCurrentlySpeaking: Boolean = false

    private val _state = MutableStateFlow(JarvisState.DISABLED)
    val state: StateFlow<JarvisState> = _state.asStateFlow()

    private val _subtitle = MutableStateFlow("")
    val subtitle: StateFlow<String> = _subtitle.asStateFlow()

    private val _conversationActive = MutableStateFlow(false)
    val conversationActive: StateFlow<Boolean> = _conversationActive.asStateFlow()

    private val _idleRemainingSeconds = MutableStateFlow(0)
    val idleRemainingSeconds: StateFlow<Int> = _idleRemainingSeconds.asStateFlow()

    private val _diagnosticStatus = MutableStateFlow("未启动")
    val diagnosticStatus: StateFlow<String> = _diagnosticStatus.asStateFlow()

    var hapticFeedbackProvider: (() -> Unit)? = null

    @Volatile var activeTerminalController: SshTerminalController? = null
    @Volatile var currentWorkspaceName: String? = null
    @Volatile var currentWorkspacePath: String? = null
    var workspaceListProvider: () -> List<String> = { emptyList() }
    var agentListProvider: () -> List<String> = { emptyList() }
    var lastTerminalOutputProvider: () -> String? = { null }
    var onWorkspaceSwitchRequested: ((String) -> Unit)? = null
    var storeProvider: (() -> dev.remote.terminal.data.LocalStore)? = null
    var hostsProvider: (() -> List<dev.remote.terminal.data.HostProfile>)? = null
    var agentsProvider: (() -> List<dev.remote.terminal.data.AgentPreset>)? = null
    var selectedHostIdProvider: () -> String? = { null }
    var selectedDirectoryProvider: () -> String? = { null }

    private val stateListeners = CopyOnWriteArrayList<(JarvisState) -> Unit>()

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app

        val sco = BluetoothScoManager(app)
        scoManager = sco
        speaker = JarvisSpeaker(app, sco, earconManager, config)
        intentRouter = JarvisIntentRouter(
            wakeWordProvider = { config.wakeWord }
        )

        if (config.enabled) {
            start()
        }
    }

    fun start() {
        val ctx = appContext ?: return
        if (perceptor?.isRunning == true) return
        val hasAudioPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasAudioPermission) {
            Log.w(TAG, "Cannot start Jarvis without RECORD_AUDIO permission")
            _diagnosticStatus.value = "未授予麦克风权限"
            _subtitle.value = "请先授予麦克风权限"
            updateState(JarvisState.DISABLED)
            return
        }

        config.enabled = true
        speaker?.rebuildSynthesizer()
        // 启动含 MICROPHONE 前台服务类型的保活服务，防止熄屏/后台被系统静音
        runCatching { dev.remote.terminal.ssh.TerminalKeepAliveService.start(ctx) }
            .onFailure { Log.w(TAG, "Failed to start TerminalKeepAliveService for Jarvis: ${it.message}") }

        val p = JarvisPerceptorFactory.createPerceptor(ctx, config)
        if (p is OfflineJarvisPerceptor) {
            p.scoManager = scoManager
            p.preferBluetoothMicProvider = { config.bluetoothScoEnabled }
            p.wakeWordProvider = { config.wakeWord }
            p.onDiagnosticChanged = { status ->
                _diagnosticStatus.value = status
            }
        }
        perceptor = p
        p.start(this)

        updateState(JarvisState.STANDBY)
        Log.i(TAG, "Jarvis master coordinator started in STANDBY mode (wakeWord=\"${config.wakeWord}\", model=\"${config.targetModel}\", effort=\"${config.targetEffort}\")")
        warmupDeveloperWorker()
    }

    fun warmupDeveloperWorker() {
        if (!config.enabled) return
        val store = storeProvider?.invoke() ?: return
        val hosts = hostsProvider?.invoke().orEmpty()
        val agents = agentsProvider?.invoke().orEmpty()
        val targetHost = hosts.firstOrNull { config.targetHostId.isNotBlank() && it.id == config.targetHostId }
            ?: hosts.firstOrNull { it.id == selectedHostIdProvider() }
            ?: hosts.firstOrNull() ?: return
        val targetAgent = agents.firstOrNull { config.targetAgentCommand.isNotBlank() && it.command == config.targetAgentCommand }
            ?: agents.firstOrNull() ?: return
        val targetDir = currentWorkspacePath?.takeIf { it.isNotBlank() }
            ?: selectedDirectoryProvider()?.takeIf { it.isNotBlank() }
            ?: targetHost.directories.firstOrNull()?.path
        BackgroundDeveloperBridge.warmup(
            host = targetHost,
            agent = targetAgent,
            directory = targetDir,
            model = config.targetModel,
            effort = config.targetEffort,
            store = store
        )
    }

    fun stop() {
        config.enabled = false
        endConversationSession(resetToDisabled = true)
        _diagnosticStatus.value = "已关闭"
        chatJob?.cancel()
        chatJob = null
        BackgroundDeveloperBridge.resetWorkers()
        perceptor?.stop()
        perceptor?.release()
        perceptor = null
        speaker?.stop()
        scoManager?.stopChannel()
        Log.i(TAG, "Jarvis master coordinator stopped")
    }

    fun toggleEnabled(enable: Boolean) {
        if (enable) start() else stop()
    }

    /**
     * 外部手动唤醒触发点（如耳机按键长按、双击，或屏幕浮动按钮点击）
     */
    fun triggerManualWake(speakTestPhrase: Boolean = false) {
        if (!config.enabled) {
            start()
        }
        if (speakTestPhrase) {
            onWakeWordDetected(config.wakeWord)
            scope.launch {
                kotlinx.coroutines.delay(220)
                speakWithState("先生，我在，当前语音音量跟随系统媒体音量，随时为您效劳。") {
                    openFollowUpListeningWindow()
                }
            }
            return
        }
        val p = perceptor
        if (p is OfflineJarvisPerceptor) {
            p.triggerWakeWordDetected()
        } else {
            onWakeWordDetected(config.wakeWord)
        }
    }

    private fun isBusyTurn(): Boolean {
        val s = _state.value
        return s == JarvisState.PROCESSING || s == JarvisState.EXECUTING || s == JarvisState.SPEAKING || chatJob?.isActive == true
    }

    private fun enterConversationMode() {
        _conversationActive.value = true
        conversationAwakeUntilMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
    }

    private fun resetConversationIdleTimer() {
        if (!config.enabled) return
        _conversationActive.value = true
        conversationAwakeUntilMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
        conversationIdleJob?.cancel()
        _idleRemainingSeconds.value = CONVERSATION_IDLE_TIMEOUT_SEC
        conversationIdleJob = scope.launch {
            var remaining = CONVERSATION_IDLE_TIMEOUT_SEC
            while (_conversationActive.value && config.enabled) {
                kotlinx.coroutines.delay(1_000L)
                if (!_conversationActive.value || !config.enabled) break
                if (isBusyTurn() || userCurrentlySpeaking) {
                    // 说话、思考、执行或播报期间不扣除 20 秒静默倒计时
                    remaining = CONVERSATION_IDLE_TIMEOUT_SEC
                    conversationAwakeUntilMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
                    _idleRemainingSeconds.value = 0
                    continue
                }
                remaining -= 1
                _idleRemainingSeconds.value = remaining.coerceAtLeast(0)
                if (remaining <= 0) {
                    Log.i(TAG, "No voice input for ${CONVERSATION_IDLE_TIMEOUT_SEC}s, closing conversation popup")
                    endConversationSession(resetToDisabled = false)
                    break
                }
            }
        }
    }

    private fun endConversationSession(resetToDisabled: Boolean) {
        conversationIdleJob?.cancel()
        conversationIdleJob = null
        _conversationActive.value = false
        _idleRemainingSeconds.value = 0
        conversationAwakeUntilMs = 0L
        wakeConfirmedThisTurn = false
        userCurrentlySpeaking = false
        _subtitle.value = ""
        updateState(if (!resetToDisabled && config.enabled) JarvisState.STANDBY else JarvisState.DISABLED)
    }

    override fun onListeningWindowOpened() {
        scope.launch {
            if (isBusyTurn()) {
                Log.d(TAG, "Ignoring onListeningWindowOpened while busy (${_state.value})")
                return@launch
            }
            if (!_conversationActive.value) {
                wakeConfirmedThisTurn = false
            } else {
                resetConversationIdleTimer()
            }
            updateState(JarvisState.LISTENING)
        }
    }

    override fun onWakeWordDetected(keyword: String) {
        scope.launch {
            if (isBusyTurn()) {
                Log.d(TAG, "Ignoring wake word \"$keyword\" while thinking/speaking (${_state.value})")
                return@launch
            }
            Log.i(TAG, "Wake word confirmed: $keyword")
            enterConversationMode()
            wakeConfirmedThisTurn = true
            userCurrentlySpeaking = false
            if (_subtitle.value.isBlank()) {
                _subtitle.value = "我在，请讲..."
            }
            hapticFeedbackProvider?.invoke()
            updateState(JarvisState.LISTENING)
            resetConversationIdleTimer()
            earconManager.playWakeChime()
        }
    }

    override fun onSpeechStart() {
        scope.launch {
            if (isBusyTurn()) return@launch
            userCurrentlySpeaking = true
            if (_conversationActive.value) {
                conversationAwakeUntilMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
                _idleRemainingSeconds.value = 0
            }
            updateState(JarvisState.LISTENING)
        }
    }

    override fun onSpeechRecognized(text: String, isFinal: Boolean) {
        scope.launch {
            if (isBusyTurn()) {
                Log.d(TAG, "Ignoring speech \"$text\" (final=$isFinal) while busy (${_state.value})")
                return@launch
            }
            val hasWake = intentRouter?.containsWakeWord(text) == true
            val inWindow = _conversationActive.value || SystemClock.elapsedRealtime() < conversationAwakeUntilMs
            if (!isFinal) {
                Log.d(TAG, "Partial speech: \"$text\" (hasWake=$hasWake, inWindow=$inWindow)")
                if (hasWake || inWindow) {
                    if (!_conversationActive.value) {
                        enterConversationMode()
                    }
                    userCurrentlySpeaking = true
                    _idleRemainingSeconds.value = 0
                    conversationAwakeUntilMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
                    _subtitle.value = text
                    updateState(JarvisState.LISTENING)
                }
                return@launch
            }

            userCurrentlySpeaking = false
            if (!hasWake && !inWindow) {
                Log.d(TAG, "Ignoring speech without wake word \"${config.wakeWord}\": \"$text\"")
                if (!_conversationActive.value) {
                    _subtitle.value = ""
                    updateState(if (config.enabled) JarvisState.STANDBY else JarvisState.DISABLED)
                }
                return@launch
            }

            enterConversationMode()
            if (hasWake && !wakeConfirmedThisTurn) {
                wakeConfirmedThisTurn = true
                hapticFeedbackProvider?.invoke()
                earconManager.playWakeChime()
            }
            Log.i(TAG, "Final speech recognized: \"$text\"")
            _subtitle.value = text
            updateState(JarvisState.PROCESSING)

            val context = JarvisContext(
                currentWorkspaceName = currentWorkspaceName,
                currentWorkspacePath = currentWorkspacePath,
                workspaces = workspaceListProvider(),
                agents = agentListProvider(),
                lastTerminalOutput = lastTerminalOutputProvider()
            )

            val action = intentRouter?.resolveIntent(text, context) ?: JarvisAction.None
            if (action !is JarvisAction.DirectReply && action != JarvisAction.None) {
                earconManager.playThinkingChime()
            }
            executeAction(action)
        }
    }

    private fun executeAction(action: JarvisAction) {
        when (action) {
            is JarvisAction.InjectKeystroke -> {
                updateState(JarvisState.EXECUTING)
                val ctrl = activeTerminalController
                if (ctrl != null) {
                    ctrl.send(action.text)
                    earconManager.playActionSuccessChime()
                    Log.i(TAG, "Injected keystroke to active terminal: ${action.text.replace("\n", "\\n")}")
                } else {
                    Log.w(TAG, "No active terminal controller to inject keystroke")
                }
                action.voiceReply?.let { reply ->
                    speakWithState(reply) { openFollowUpListeningWindow() }
                } ?: openFollowUpListeningWindow()
            }
            is JarvisAction.SwitchWorkspace -> {
                updateState(JarvisState.EXECUTING)
                onWorkspaceSwitchRequested?.invoke(action.workspaceName)
                earconManager.playActionSuccessChime()
                action.voiceReply?.let { reply ->
                    speakWithState(reply) { openFollowUpListeningWindow() }
                } ?: openFollowUpListeningWindow()
            }
            is JarvisAction.QueryStatus -> {
                speakWithState(action.voiceReply) {
                    openFollowUpListeningWindow()
                }
            }
            is JarvisAction.DirectReply -> {
                speakWithState(action.voiceReply) {
                    openFollowUpListeningWindow()
                }
            }
            is JarvisAction.ExecuteTool -> {
                updateState(JarvisState.EXECUTING)
                scope.launch {
                    val result = dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.executeTool(action.toolName, action.arguments)
                    if (result.isError) {
                        earconManager.playAlertChime()
                        speakWithState(result.voiceSummary ?: "执行工具失败。") {
                            openFollowUpListeningWindow()
                        }
                    } else {
                        earconManager.playActionSuccessChime()
                        speakWithState(result.voiceSummary ?: action.voiceReply ?: "操作已完成。") {
                            openFollowUpListeningWindow()
                        }
                    }
                }
            }
            is JarvisAction.ChatWithDeveloper -> {
                if (chatJob?.isActive == true) {
                    Log.d(TAG, "Developer turn already running, refusing interruption")
                    return
                }
                updateState(JarvisState.EXECUTING)
                chatJob = scope.launch {
                    handleChatWithDeveloper(action)
                }
            }
            is JarvisAction.ErrorAction -> {
                earconManager.playAlertChime()
                speakWithState(action.message) {
                    openFollowUpListeningWindow()
                }
            }
            JarvisAction.None -> {
                if (_conversationActive.value) {
                    openFollowUpListeningWindow()
                } else {
                    updateState(JarvisState.STANDBY)
                }
            }
        }
    }

    fun speakWithState(message: String, onFinished: (() -> Unit)? = null) {
        _subtitle.value = message
        _conversationActive.value = true
        _idleRemainingSeconds.value = 0
        hapticFeedbackProvider?.invoke()
        updateState(JarvisState.SPEAKING)
        val currentSpeaker = speaker
        if (currentSpeaker == null) {
            updateState(if (config.enabled) JarvisState.LISTENING else JarvisState.DISABLED)
            onFinished?.invoke() ?: openFollowUpListeningWindow()
            return
        }
        scope.launch {
            val wasSco = scoManager?.isScoActive == true
            scoManager?.stopChannel()
            if (wasSco) {
                kotlinx.coroutines.delay(180)
            }
            currentSpeaker.speak(message) {
                scope.launch {
                    updateState(if (config.enabled) JarvisState.LISTENING else JarvisState.DISABLED)
                    if (onFinished != null) {
                        onFinished.invoke()
                    } else {
                        openFollowUpListeningWindow()
                    }
                }
            }
        }
    }

    private fun openFollowUpListeningWindow() {
        if (!config.enabled) return
        scope.launch {
            kotlinx.coroutines.delay(180)
            if (config.enabled && !isBusyTurn()) {
                enterConversationMode()
                resetConversationIdleTimer()
                (perceptor as? OfflineJarvisPerceptor)?.openCommandWindow()
                updateState(JarvisState.LISTENING)
            }
        }
    }

    /**
     * 透明传话桥梁：优先直接在后台通过 SSH 静默连向目标主机 + 目标开发者，
     * 不依赖前台是否已打开终端页；拿到回复后不加多余前缀，直接原文播报给用户并保持对话窗口。
     */
    private suspend fun handleChatWithDeveloper(action: JarvisAction.ChatWithDeveloper) {
        val store = storeProvider?.invoke()
        val hosts = hostsProvider?.invoke().orEmpty()
        val agents = agentsProvider?.invoke().orEmpty()

        val targetHost = hosts.firstOrNull { config.targetHostId.isNotBlank() && it.id == config.targetHostId }
            ?: hosts.firstOrNull { it.id == selectedHostIdProvider() }
            ?: hosts.firstOrNull()

        val targetAgent = when {
            !action.agentName.isNullOrBlank() -> {
                val q = action.agentName.trim().lowercase()
                agents.firstOrNull {
                    it.name.lowercase().contains(q) || it.command.lowercase().contains(q)
                }
            }
            else -> null
        } ?: agents.firstOrNull { config.targetAgentCommand.isNotBlank() && it.command == config.targetAgentCommand }
            ?: agents.firstOrNull()

        val targetDirectory = currentWorkspacePath?.takeIf { it.isNotBlank() }
            ?: selectedDirectoryProvider()?.takeIf { it.isNotBlank() }
            ?: targetHost?.directories?.firstOrNull()?.path

        if (store != null && targetHost != null && targetAgent != null) {
            _subtitle.value = "${targetAgent.name} 思考中..."
            val result = BackgroundDeveloperBridge.askDeveloper(
                host = targetHost,
                agent = targetAgent,
                directory = targetDirectory,
                userPrompt = action.message,
                store = store,
                model = config.targetModel,
                effort = config.targetEffort
            )
            result.onSuccess { reply ->
                conversationAwakeUntilMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
                earconManager.playActionSuccessChime()
                speakWithState(reply) {
                    openFollowUpListeningWindow()
                }
            }.onFailure { err ->
                earconManager.playAlertChime()
                speakWithState(err.message ?: "开发者暂时无响应。")
            }
            return
        }

        // 兜底：若用户尚未在设置中配置可用主机/开发者，尝试透传到当前打开的终端会话
        val baseline = kotlinx.coroutines.withContext(Dispatchers.Main) {
            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.executeTool(
                "read_terminal_screen",
                org.json.JSONObject().put("lines", 10)
            ).content
        }
        val sent = kotlinx.coroutines.withContext(Dispatchers.Main) {
            !dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.executeTool(
                "send_terminal_text",
                org.json.JSONObject().put("text", action.message).put("press_enter", true)
            ).isError
        } || (activeTerminalController?.let {
            it.send(action.message.trimEnd('\r', '\n'))
            kotlinx.coroutines.delay(120)
            it.send("\r")
            true
        } ?: false)

        if (!sent) {
            earconManager.playAlertChime()
            speakWithState("请先在主页添加主机与开发者，再开始对话。")
            return
        }

        var replyFound = false
        var lastRead = baseline
        var unchangedCount = 0
        for (sec in 1..15) {
            kotlinx.coroutines.delay(1000)
            val currentTail = kotlinx.coroutines.withContext(Dispatchers.Main) {
                dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.executeTool(
                    "read_terminal_screen",
                    org.json.JSONObject().put("lines", 10)
                ).content
            }
            if (currentTail != lastRead && currentTail.isNotBlank()) {
                lastRead = currentTail
                unchangedCount = 0
            } else if (lastRead != baseline && lastRead.isNotBlank()) {
                unchangedCount++
                if (unchangedCount >= 2) {
                    replyFound = true
                    break
                }
            }
        }

        if (replyFound || (lastRead != baseline && lastRead.isNotBlank())) {
            conversationAwakeUntilMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
            earconManager.playActionSuccessChime()
            speakWithState(cleanTerminalOutput(lastRead)) {
                openFollowUpListeningWindow()
            }
        } else {
            speakWithState("终端仍在处理中。")
        }
    }

    private fun cleanTerminalOutput(raw: String): String {
        val noAnsi = raw.replace(Regex("\u001B\\[[;?0-9]*[a-zA-Z]"), "")
            .replace(Regex("[\r\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "")
        val lines = noAnsi.lines().map { it.trim() }.filter { it.isNotBlank() }
        val usefulLines = lines.filterNot { it.startsWith("$") || it.startsWith(">") || it.startsWith("❯") }
        val summary = (if (usefulLines.isNotEmpty()) usefulLines.takeLast(3) else lines.takeLast(2)).joinToString("，")
        return summary.take(160).ifBlank { "已执行完成。" }
    }

    /**
     * 智能体权限确认拦截回调。
     * AutoConfirmEngine 已支持终端全自动回车确认，JARVIS 保持静默，不主动打扰用户。
     */
    fun onApprovalRequired(promptDescription: String) {
        Log.d(TAG, "Approval prompt detected (handled automatically by AutoConfirmEngine): $promptDescription")
    }

    /**
     * 智能体/终端任务完成主动汇报。
     * 若当前正处于语音思考/播报中，先排队等待本轮结束再主动播报，不打断当前对话。
     */
    fun onTaskCompleted(workspace: String, status: String) {
        if (!config.enabled || !config.notifyOnTaskFinished) return
        val cleanStatus = status.trim().ifBlank { "已完成" }.take(80)
        scope.launch {
            var waited = 0
            while (isBusyTurn() && waited < 30_000) {
                kotlinx.coroutines.delay(400)
                waited += 400
            }
            if (!config.enabled || !config.notifyOnTaskFinished || isBusyTurn()) return@launch
            Log.i(TAG, "Task completed in $workspace: $cleanStatus")
            conversationAwakeUntilMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
            earconManager.playActionSuccessChime()
            val text = "${workspace} 已完成操作：$cleanStatus"
            speakWithState(text) {
                openFollowUpListeningWindow()
            }
        }
    }

    override fun onError(error: String) {
        scope.launch {
            Log.w(TAG, "Perception error: $error")
            userCurrentlySpeaking = false
            if (isBusyTurn()) {
                Log.d(TAG, "Ignoring perception error \"$error\" while thinking/speaking")
                return@launch
            }
            if (_conversationActive.value) {
                // 多轮对话期间若偶发空音段，保持弹窗与 20 秒静默倒计时不中断
                resetConversationIdleTimer()
                updateState(JarvisState.LISTENING)
                return@launch
            }
            if (_state.value == JarvisState.LISTENING || _subtitle.value == "正在倾听..." || _subtitle.value == "先生，我在，请讲..." || _subtitle.value == "先生，我在。") {
                _subtitle.value = ""
                updateState(if (config.enabled) JarvisState.STANDBY else JarvisState.DISABLED)
            }
        }
    }

    /**
     * 用户主动横向划走或关闭悬浮提示卡片，中止当前倾听/播报并复位至待命。
     */
    fun dismissOverlay() {
        scope.launch {
            if (_state.value == JarvisState.PROCESSING || _state.value == JarvisState.EXECUTING || chatJob?.isActive == true) {
                Log.d(TAG, "Developer thinking in progress, keeping turn uninterrupted")
                return@launch
            }
            (perceptor as? OfflineJarvisPerceptor)?.cancelListening()
            speaker?.stop()
            scoManager?.stopChannel()
            endConversationSession(resetToDisabled = false)
            Log.i(TAG, "Overlay dismissed by user")
        }
    }

    private fun updateState(newState: JarvisState) {
        _state.value = newState
        when (newState) {
            JarvisState.LISTENING -> {
                if (_conversationActive.value && conversationIdleJob?.isActive != true && !userCurrentlySpeaking) {
                    resetConversationIdleTimer()
                }
            }
            JarvisState.PROCESSING -> _subtitle.value = "正在理解指令..."
            JarvisState.EXECUTING -> if (_subtitle.value.isBlank()) _subtitle.value = "正在调度终端执行..."
            JarvisState.STANDBY -> { /* 保持当前状态，由 20s 静默计时器或用户划走清空 */ }
            JarvisState.DISABLED -> _subtitle.value = ""
            JarvisState.SPEAKING -> { /* 由 speakWithState 负责设置 */ }
        }
        stateListeners.forEach { it.invoke(newState) }
    }

    fun addStateListener(listener: (JarvisState) -> Unit) {
        stateListeners.add(listener)
        listener(_state.value)
    }

    fun removeStateListener(listener: (JarvisState) -> Unit) {
        stateListeners.remove(listener)
    }
}
