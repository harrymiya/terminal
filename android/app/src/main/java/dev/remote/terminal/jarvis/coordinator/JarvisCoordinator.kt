package dev.remote.terminal.jarvis.coordinator

import android.content.Context
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
    private val scope = CoroutineScope(Dispatchers.Main)

    val config = JarvisConfig()
    private var appContext: Context? = null

    private var scoManager: BluetoothScoManager? = null
    private val earconManager = EarconManager()
    private var speaker: JarvisSpeaker? = null
    private var intentRouter: JarvisIntentRouter? = null
    private var perceptor: VoicePerceptor? = null

    private val _state = MutableStateFlow(JarvisState.DISABLED)
    val state: StateFlow<JarvisState> = _state.asStateFlow()

    @Volatile var activeTerminalController: SshTerminalController? = null
    @Volatile var currentWorkspaceName: String? = null
    @Volatile var currentWorkspacePath: String? = null
    var workspaceListProvider: () -> List<String> = { emptyList() }
    var agentListProvider: () -> List<String> = { emptyList() }
    var lastTerminalOutputProvider: () -> String? = { null }
    var onWorkspaceSwitchRequested: ((String) -> Unit)? = null

    private val stateListeners = CopyOnWriteArrayList<(JarvisState) -> Unit>()

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app

        val sco = BluetoothScoManager(app)
        scoManager = sco
        speaker = JarvisSpeaker(app, sco, earconManager, config)
        intentRouter = JarvisIntentRouter()

        if (config.enabled) {
            start()
        }
    }

    fun start() {
        val ctx = appContext ?: return
        if (perceptor?.isRunning == true) return

        config.enabled = true
        scoManager?.startChannel()
        speaker?.rebuildSynthesizer()

        val p = JarvisPerceptorFactory.createPerceptor(ctx, config)
        perceptor = p
        p.start(this)

        updateState(JarvisState.STANDBY)
        Log.i(TAG, "Jarvis master coordinator started in STANDBY mode")
    }

    fun stop() {
        config.enabled = false
        perceptor?.stop()
        perceptor?.release()
        perceptor = null
        speaker?.stop()
        scoManager?.stopChannel()
        updateState(JarvisState.DISABLED)
        Log.i(TAG, "Jarvis master coordinator stopped")
    }

    fun toggleEnabled(enable: Boolean) {
        if (enable) start() else stop()
    }

    /**
     * 外部手动唤醒触发点（如耳机按键长按、双击，或屏幕浮动按钮点击）
     */
    fun triggerManualWake() {
        if (!config.enabled) {
            start()
        }
        val p = perceptor
        if (p is OfflineJarvisPerceptor) {
            p.triggerWakeWordDetected()
        } else {
            onWakeWordDetected(config.wakeWord)
        }
    }

    override fun onWakeWordDetected(keyword: String) {
        scope.launch {
            Log.i(TAG, "Wake word detected: $keyword")
            updateState(JarvisState.LISTENING)
            earconManager.playWakeChime()
        }
    }

    override fun onSpeechStart() {
        scope.launch {
            updateState(JarvisState.LISTENING)
        }
    }

    override fun onSpeechRecognized(text: String, isFinal: Boolean) {
        scope.launch {
            if (!isFinal) {
                Log.d(TAG, "Partial speech: \"$text\"")
                return@launch
            }

            Log.i(TAG, "Final speech recognized: \"$text\"")
            updateState(JarvisState.PROCESSING)
            earconManager.playThinkingChime()

            val context = JarvisContext(
                currentWorkspaceName = currentWorkspaceName,
                currentWorkspacePath = currentWorkspacePath,
                workspaces = workspaceListProvider(),
                agents = agentListProvider(),
                lastTerminalOutput = lastTerminalOutputProvider()
            )

            val action = intentRouter?.resolveIntent(text, context) ?: JarvisAction.None
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
                    speakWithState(reply)
                } ?: updateState(JarvisState.STANDBY)
            }
            is JarvisAction.SwitchWorkspace -> {
                updateState(JarvisState.EXECUTING)
                onWorkspaceSwitchRequested?.invoke(action.workspaceName)
                earconManager.playActionSuccessChime()
                action.voiceReply?.let { reply ->
                    speakWithState(reply)
                } ?: updateState(JarvisState.STANDBY)
            }
            is JarvisAction.QueryStatus -> {
                speakWithState(action.voiceReply)
            }
            is JarvisAction.DirectReply -> {
                speakWithState(action.voiceReply)
            }
            is JarvisAction.ErrorAction -> {
                earconManager.playAlertChime()
                speakWithState(action.message)
            }
            JarvisAction.None -> {
                updateState(JarvisState.STANDBY)
            }
        }
    }

    private fun speakWithState(message: String) {
        updateState(JarvisState.SPEAKING)
        speaker?.speak(message) {
            scope.launch {
                updateState(JarvisState.STANDBY)
            }
        }
    }

    /**
     * 智能体权限确认拦截主动耳语回调。
     * 当 AutoConfirmEngine 捕获到等待用户确认时，调用此方法向蓝牙耳机主动耳语。
     */
    fun onApprovalRequired(promptDescription: String) {
        if (!config.enabled || !config.autoWhisperConfirm) return
        scope.launch {
            Log.i(TAG, "Approval required for terminal prompt, whispering to headset")
            earconManager.playAlertChime()
            val text = "先生，智能体请求授权：$promptDescription，是否允许？"
            speakWithState(text)
        }
    }

    /**
     * 智能体任务完成主动耳语通知。
     */
    fun onTaskCompleted(workspace: String, status: String) {
        if (!config.enabled || !config.notifyOnTaskFinished) return
        scope.launch {
            Log.i(TAG, "Task completed in $workspace, whispering to headset")
            earconManager.playActionSuccessChime()
            val text = "先生，${workspace} 的任务已推进完毕，状态：$status。"
            speakWithState(text)
        }
    }

    override fun onError(error: String) {
        scope.launch {
            Log.w(TAG, "Perception error: $error")
            updateState(JarvisState.STANDBY)
        }
    }

    private fun updateState(newState: JarvisState) {
        _state.value = newState
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
