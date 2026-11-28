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
import dev.remote.terminal.jarvis.memory.JarvisMemoryManager
import dev.remote.terminal.jarvis.perception.JarvisPerceptorFactory
import dev.remote.terminal.jarvis.perception.OfflineJarvisPerceptor
import dev.remote.terminal.jarvis.tts.JarvisSpeaker
import dev.remote.terminal.jarvis.model.RemoteCliModel
import dev.remote.terminal.jarvis.model.ApiChatModel
import dev.remote.terminal.jarvis.agent.RemoteDevAgent
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
    private var memoryManager: JarvisMemoryManager? = null
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

    var workspaceListProvider: () -> List<String> = { emptyList() }
    var storeProvider: (() -> dev.remote.terminal.data.LocalStore)? = null
    var hostsProvider: (() -> List<dev.remote.terminal.data.HostProfile>)? = null
    var agentsProvider: (() -> List<dev.remote.terminal.data.AgentPreset>)? = null
    var selectedHostIdProvider: () -> String? = { null }

    private val stateListeners = CopyOnWriteArrayList<(JarvisState) -> Unit>()

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val memory = JarvisMemoryManager(app, dev.remote.terminal.data.LocalStore(app))
        memoryManager = memory
        scope.launch(Dispatchers.IO) {
            runCatching { memory.initialize() }
                .onFailure { Log.w(TAG, "Jarvis memory initialization failed: ${it.message}") }
        }

        val sco = BluetoothScoManager(app)
        sco.onUnexpectedScoDisconnected = {
            (perceptor as? OfflineJarvisPerceptor)?.onScoExternallyDisconnected()
        }
        scoManager = sco
        speaker = JarvisSpeaker(app, sco, earconManager, config)
        intentRouter = JarvisIntentRouter(
            wakeWordProvider = { config.wakeWord }
        )
        dev.remote.terminal.jarvis.perception.JarvisSpeechCorrector.wakeWordProvider = { config.wakeWord }
        dev.remote.terminal.jarvis.perception.JarvisSpeechCorrector.workspacesProvider = { workspaceListProvider() }
        dev.remote.terminal.jarvis.perception.JarvisSpeechCorrector.hostsProvider = { hostsProvider?.invoke().orEmpty() }
        dev.remote.terminal.jarvis.perception.JarvisSpeechCorrector.agentsProvider = { agentsProvider?.invoke().orEmpty() }
        dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.closeAwakeHandler = { farewell ->
            closeAwakeState(farewell)
        }

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
            p.isAwakeProvider = { _conversationActive.value }
            p.cloudAsrConfigProvider = {
                val st = storeProvider?.invoke()
                if (st != null && st.loadJarvisCloudAsrEnabled()) {
                    val key = st.loadJarvisCloudAsrKey()
                    if (key.isNotBlank()) {
                        Triple(st.loadJarvisCloudAsrUrl(), st.loadJarvisCloudAsrModel(), key)
                    } else null
                } else null
            }
            p.hotwordsPromptProvider = {
                val ws = workspaceListProvider().take(12).joinToString(", ")
                val ag = agentsProvider?.invoke().orEmpty().map { it.name }.distinct().take(8).joinToString(", ")
                "${config.wakeWord}, Codex, Claude, OpenCode, agy, 看着干活, 派发任务, 工作区, 退出唤醒态, $ws, $ag"
            }
            p.onDiagnosticChanged = { status ->
                _diagnosticStatus.value = status
            }
        }
        perceptor = p
        p.start(this)

        updateState(JarvisState.STANDBY)
        Log.i(TAG, "Jarvis master coordinator started in STANDBY mode (wakeWord=\"${config.wakeWord}\", model=\"${config.targetModel}\", effort=\"${config.targetEffort}\")")
    }

    fun stop() {
        config.enabled = false
        runCatching { dev.remote.terminal.jarvis.watcher.JarvisWatchOverseer.stopWatching() }
        endConversationSession(resetToDisabled = true, forceCancelWork = true)
        _diagnosticStatus.value = "已关闭"
        chatJob?.cancel()
        chatJob = null
        RemoteCliModel.resetWorkers()
        perceptor?.stop()
        perceptor?.release()
        perceptor = null
        speaker?.stop()
        scoManager?.stopChannel()
        scoManager?.abandonAudioFocus()
        val ctx = appContext
        if (ctx != null && !dev.remote.terminal.ssh.TerminalConnectionRegistry.hasActiveConnections()) {
            runCatching { dev.remote.terminal.ssh.TerminalKeepAliveService.stop(ctx) }
        }
        Log.i(TAG, "Jarvis master coordinator stopped (audio channel released)")
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
        scope.launch(Dispatchers.IO) {
            runCatching { memoryManager?.restoreContextFromSqlite() }
                .onFailure { Log.w(TAG, "Failed to restore context from SQLite: ${it.message}") }
        }
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

    private fun endConversationSession(resetToDisabled: Boolean, forceCancelWork: Boolean = false) {
        conversationIdleJob?.cancel()
        conversationIdleJob = null
        if (forceCancelWork) {
            chatJob?.cancel()
            chatJob = null
        }
        scope.launch(Dispatchers.IO) {
            runCatching { memoryManager?.flushContextToSqlite() }
                .onFailure { Log.w(TAG, "Failed to flush context to SQLite on awake exit: ${it.message}") }
        }
        _conversationActive.value = false
        _idleRemainingSeconds.value = 0
        conversationAwakeUntilMs = 0L
        wakeConfirmedThisTurn = false
        userCurrentlySpeaking = false
        _subtitle.value = ""
        speaker?.stop()
        if (!resetToDisabled && config.enabled) {
            (perceptor as? OfflineJarvisPerceptor)?.returnToWakeListening()
            updateState(JarvisState.STANDBY)
            Log.i(TAG, "Popup/awake session closed -> returned to wake-word listening (STANDBY, wakeWord=\"${config.wakeWord}\")")
        } else {
            updateState(JarvisState.DISABLED)
        }
    }

    override fun onListeningWindowOpened() {
        scope.launch {
            if (isBusyTurn()) {
                Log.d(TAG, "Ignoring onListeningWindowOpened while busy (${_state.value})")
                return@launch
            }
            if (_conversationActive.value) {
                resetConversationIdleTimer()
                updateState(JarvisState.LISTENING)
            } else {
                // 未唤醒前保持在监听唤醒状态（STANDBY），不切换弹窗状态
                wakeConfirmedThisTurn = false
                if (_state.value != JarvisState.STANDBY && config.enabled) {
                    updateState(JarvisState.STANDBY)
                }
            }
        }
    }

    fun isAwake(): Boolean = _conversationActive.value

    /**
     * 退出唤醒态并关闭悬浮弹窗，重新进入监听唤醒词状态 (STANDBY)。
     * 若传入简短 farewell 告别语，播报完后立即退出，不再开启下一轮倾听窗口。
     */
    fun closeAwakeState(farewell: String? = null) {
        scope.launch {
            if (!farewell.isNullOrBlank()) {
                speakWithState(farewell) {
                    endConversationSession(resetToDisabled = false, forceCancelWork = true)
                }
            } else {
                earconManager.playActionSuccessChime()
                endConversationSession(resetToDisabled = false, forceCancelWork = true)
            }
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
            if (_conversationActive.value) {
                userCurrentlySpeaking = true
                conversationAwakeUntilMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
                _idleRemainingSeconds.value = 0
                updateState(JarvisState.LISTENING)
            } else if (_state.value != JarvisState.STANDBY && config.enabled) {
                // 未处于唤醒态时继续停留在监听唤醒状态（STANDBY）
                updateState(JarvisState.STANDBY)
            }
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

            memoryManager?.let { mgr ->
                scope.launch(Dispatchers.IO) {
                    mgr.recordUserTurn(text) { prompt -> summarizeTextWithModel(prompt) }
                }
            }

            runCatching { memoryManager?.beginTurn(text) }
                .onFailure { Log.w(TAG, "Jarvis memory initialization failed: ${it.message}") }
            val context = JarvisContext(workspaces = workspaceListProvider())

            val action = intentRouter?.resolveIntent(text, context) ?: JarvisAction.None
            if (action !is JarvisAction.DirectReply && action != JarvisAction.None) {
                earconManager.playThinkingChime()
            }
            executeAction(action, text)
        }
    }

    private fun executeAction(action: JarvisAction, userText: String) {
        when (action) {
            is JarvisAction.DirectReply -> {
                recordMemoryTurn(userText, action.voiceReply)
                speakWithState(action.voiceReply) {
                    openFollowUpListeningWindow()
                }
            }
            is JarvisAction.ExecuteTool -> {
                val summarize = RemoteDevAgent.isWorkspaceStatusTool(action.toolName)
                updateState(JarvisState.EXECUTING)
                val job = scope.launch {
                    val result = RemoteDevAgent.executeTool(action.toolName, action.arguments)
                    if (!result.isError && action.toolName == "close_awake" || !_conversationActive.value) {
                        return@launch
                    }
                    if (result.isError) {
                        earconManager.playAlertChime()
                        speakWithState(result.voiceSummary ?: "执行工具失败。") {
                            openFollowUpListeningWindow()
                        }
                    } else {
                        val fallback = result.voiceSummary ?: "操作已完成。"
                        val reply = if (summarize) {
                            _subtitle.value = "Jarvis 正在整理状态…"
                            val convContext = memoryManager?.getFormattedConversationContext().orEmpty()
                            runModel { complete ->
                                RemoteDevAgent.summarizeStatus(userText, result.content, convContext, complete)
                            }.getOrDefault(fallback)
                        } else fallback
                        if (!_conversationActive.value) return@launch
                        earconManager.playActionSuccessChime()
                        recordMemoryTurn(userText, reply)
                        speakWithState(reply) {
                            openFollowUpListeningWindow()
                        }
                    }
                }
                if (summarize) chatJob = job
            }
            is JarvisAction.AskAgent -> {
                if (chatJob?.isActive == true) {
                    Log.d(TAG, "App Agent turn already running")
                    return
                }
                updateState(JarvisState.EXECUTING)
                chatJob = scope.launch {
                    handleAgentRequest(action)
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
            if (!config.enabled) return@launch
            currentSpeaker.speak(message) {
                scope.launch {
                    if (!config.enabled) return@launch
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

    private suspend fun handleAgentRequest(action: JarvisAction.AskAgent) {
        _subtitle.value = "Jarvis 正在理解请求…"
        val memoryContext = try {
            memoryManager?.recallContext(action.message).orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Jarvis memory recall failed: ${e.message}")
            ""
        }
        val conversationContext = memoryManager?.getFormattedConversationContext().orEmpty()
        val result = runModel { complete ->
            RemoteDevAgent.respond(action.message, memoryContext, conversationContext, complete)
        }
        result.onSuccess { reply ->
            if (reply.isBlank() || !_conversationActive.value) return
            recordMemoryTurn(action.message, reply)
            conversationAwakeUntilMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
            earconManager.playActionSuccessChime()
            speakWithState(reply) { if (_conversationActive.value) openFollowUpListeningWindow() }
        }.onFailure { err ->
            if (!_conversationActive.value) return
            earconManager.playAlertChime()
            speakWithState(err.message ?: "模型暂时无响应。")
        }
    }

    private suspend fun runModel(
        runAgent: suspend (suspend (String) -> Result<String>) -> Result<String>
    ): Result<String> {
        val store = storeProvider?.invoke()
            ?: return Result.failure(IllegalStateException("当前无法读取 App 配置。"))
        if (!store.loadJarvisUsesModelApi()) return runCliModel(store, runAgent)

        val settings = kotlinx.coroutines.withContext(Dispatchers.IO) { store.loadJarvisModelApiSettings() }
        if (settings.key.isBlank()) {
            return Result.failure(IllegalStateException("请先在 Jarvis 的模型 API 设置中保存 API Key。"))
        }
        return runAgent { prompt -> ApiChatModel.complete(settings.url, settings.model, settings.key, prompt) }
    }

    private suspend fun runCliModel(
        store: dev.remote.terminal.data.LocalStore,
        runAgent: suspend (suspend (String) -> Result<String>) -> Result<String>
    ): Result<String> {
        val hosts = hostsProvider?.invoke().orEmpty()
        val targetHost = hosts.firstOrNull { config.targetHostId.isNotBlank() && it.id == config.targetHostId }
            ?: hosts.firstOrNull { it.id == selectedHostIdProvider() }
            ?: hosts.firstOrNull()
        val hostPresets = targetHost?.let {
            dev.remote.terminal.data.hostAgents(it, agentsProvider?.invoke().orEmpty())
        }.orEmpty()
        val model = config.targetModel
        val preset = hostPresets.firstOrNull {
            (config.targetAgentCommand.isBlank() || it.command == config.targetAgentCommand) &&
                RemoteCliModel.supports(it.command, model)
        }

        if (targetHost == null) {
            return Result.failure(IllegalStateException("请先在 Remote Dev 添加可用的模型主机。"))
        }
        if (preset == null) {
            return Result.failure(IllegalStateException("请在 Jarvis 设置中选择可用的远端 CLI 模型入口。"))
        }
        return RemoteCliModel.withTurn(targetHost, preset, store, model, config.targetEffort, runAgent)
    }

    private fun recordMemoryTurn(userText: String, assistantText: String?) {
        if (assistantText.isNullOrBlank()) return
        val manager = memoryManager ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                manager.recordAssistantTurn(assistantText) { prompt ->
                    summarizeTextWithModel(prompt)
                }
                manager.recordTurn(userText, assistantText)
            }.onFailure { Log.w(TAG, "Jarvis memory write failed: ${it.message}") }
        }
    }

    private suspend fun summarizeTextWithModel(prompt: String): String {
        return runCatching {
            runModel { complete ->
                complete("请将以下多轮对话内容压缩总结出核心要点，提炼用户意图、关键技术决策、工作区与任务进度，用精简中文输出要点：\n$prompt")
            }.getOrNull()
        }.getOrNull().orEmpty()
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
     * 用户主动关闭悬浮弹窗（点击关闭按钮或划走卡片）：
     * 立即退出唤醒态，重置会话状态并切回监听唤醒词状态 (STANDBY)。
     */
    fun dismissOverlay(force: Boolean = false) {
        scope.launch {
            if (!force && (_state.value == JarvisState.PROCESSING || _state.value == JarvisState.EXECUTING || chatJob?.isActive == true)) {
                Log.d(TAG, "Developer thinking in progress, keeping turn uninterrupted")
                return@launch
            }
            endConversationSession(resetToDisabled = false, forceCancelWork = force)
            Log.i(TAG, "Overlay closed (force=$force) -> returned to wake-word listening STANDBY")
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
