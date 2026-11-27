package dev.remote.terminal.jarvis.api

import android.content.Context

enum class JarvisState {
    DISABLED,       // 禁用状态
    STANDBY,        // 低功耗监听唤醒词
    LISTENING,      // 正在倾听用户说话 (SCO 录音中)
    PROCESSING,     // 意图解析中 (本地正则 / 云端LLM)
    EXECUTING,      // 执行终端动作 (注入按键/切换会话)
    SPEAKING        // 正在语音播报 (TTS 输出中)
}

data class JarvisConfig(
    var enabled: Boolean = false,
    var wakeWord: String = "贾维斯",
    var asrEngineMode: String = "offline",     // "offline" (Sherpa/SenseVoice), "system" (Android SpeechRecognizer), "online" (Cloud ASR)
    var ttsEngineMode: String = "native",      // "native" (Android TTS), "cloud" (Online TTS)
    var autoWhisperConfirm: Boolean = true,    // 是否在遇到权限确认时主动耳语
    var notifyOnTaskFinished: Boolean = true,  // 任务完成时是否主动播报
    var bluetoothScoEnabled: Boolean = true,   // 是否启用蓝牙耳机 SCO 宽频语音通道
    var headsetButtonHook: Boolean = true,     // 是否启用耳机按键唤醒保底
    var speechRate: Float = 1.08f,             // 语速 (管家风格干练)
    var speechPitch: Float = 0.85f             // 音高 (管家风格沉稳磁性)
)

data class JarvisContext(
    val currentHostName: String? = null,
    val currentWorkspaceName: String? = null,
    val currentWorkspacePath: String? = null,
    val workspaces: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
    val activeSessionId: String? = null,
    val lastTerminalOutput: String? = null
)

sealed class JarvisAction {
    data class InjectKeystroke(val text: String, val voiceReply: String? = null) : JarvisAction()
    data class SwitchWorkspace(val workspaceName: String, val voiceReply: String? = null) : JarvisAction()
    data class QueryStatus(val voiceReply: String) : JarvisAction()
    data class DirectReply(val voiceReply: String) : JarvisAction()
    data class ExecuteTool(val toolName: String, val arguments: org.json.JSONObject = org.json.JSONObject(), val voiceReply: String? = null) : JarvisAction()
    data class ChatWithDeveloper(val agentName: String? = null, val message: String, val voiceReply: String? = null) : JarvisAction()
    data class ErrorAction(val message: String) : JarvisAction()
    object None : JarvisAction()
}

/**
 * 语音感知器接口 (负责 VAD、KWS 唤醒词与语音转文字 ASR)
 * 可插拔替换为：OfflineJarvisPerceptor / OnlineJarvisPerceptor / SystemSpeechPerceptor
 */
interface VoicePerceptor {
    fun start(callback: VoicePerceptorCallback)
    fun stop()
    fun release()
    val isRunning: Boolean
}

interface VoicePerceptorCallback {
    fun onWakeWordDetected(keyword: String)
    fun onSpeechStart()
    fun onSpeechRecognized(text: String, isFinal: Boolean)
    fun onError(error: String)
}

/**
 * 语音合成器接口 (负责文字转语音 TTS)
 * 可插拔替换为：NativeJarvisSynthesizer (原生) / CloudJarvisSynthesizer (在线)
 */
interface VoiceSynthesizer {
    fun initialize(context: Context, onReady: (Boolean) -> Unit)
    fun speak(text: String, queueMode: Int = 0, onDone: (() -> Unit)? = null)
    fun stop()
    val isSpeaking: Boolean
    fun release()
    fun updateParams(pitch: Float, rate: Float)
}

/**
 * 意图解析中枢 (负责将用户口语转化为 JarvisAction)
 * 具备分层设计：Tier-1 极速本地正则 (0 Token) + Tier-2 云端大模型轻量元数据派发
 */
interface IntentRouter {
    suspend fun resolveIntent(text: String, context: JarvisContext): JarvisAction
}

/**
 * 音频通道管理接口 (负责蓝牙耳机 SCO 与听筒/扬声器路由)
 */
interface AudioChannelManager {
    fun startChannel()
    fun stopChannel()
    val isHeadsetConnected: Boolean
    val isScoActive: Boolean
    fun addStateListener(listener: (Boolean) -> Unit)
    fun removeStateListener(listener: (Boolean) -> Unit)
    fun release()
}
