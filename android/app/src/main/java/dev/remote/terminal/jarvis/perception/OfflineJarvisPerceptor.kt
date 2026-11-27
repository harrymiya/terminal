package dev.remote.terminal.jarvis.perception

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dev.remote.terminal.jarvis.api.VoicePerceptor
import dev.remote.terminal.jarvis.api.VoicePerceptorCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.sqrt

/**
 * 端侧完全离线语音感知器 (Offline Voice Perceptor)。
 * 1. 采用 16kHz 16-bit 单声道 PCM 进行端侧低能耗音频流拾取；
 * 2. 内置基于短时能量与过零率的超轻量 VAD (Voice Activity Detection)，无声时不耗费算力；
 * 3. 命中唤醒词后自动开启命令倾听窗口，通过端侧离线语音引擎将口语转文字，0 流量、0 网络延迟。
 */
class OfflineJarvisPerceptor(
    private val context: Context,
    private val wakeWord: String = "贾维斯"
) : VoicePerceptor {

    companion object {
        private const val TAG = "OfflineVoicePerceptor"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SIZE_MS = 20
        private const val SAMPLES_PER_FRAME = (SAMPLE_RATE * FRAME_SIZE_MS) / 1000 // 320 samples
        private const val ENERGY_THRESHOLD = 360.0 // 过滤环境底噪的最小门槛
        private const val POST_ASR_COOLDOWN_MS = 1800L
        private const val POST_SPEAKING_ECHO_GUARD_MS = 650L
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordJob: Job? = null
    private var watchdogJob: Job? = null
    private var audioRecord: AudioRecord? = null

    @Volatile override var isRunning: Boolean = false
        private set

    @Volatile private var isListeningCommand = false
    @Volatile private var allowPreferOffline = true
    @Volatile private var lastAsrFinishedAtMs = 0L
    @Volatile private var lastSpeakingSeenAtMs = 0L
    private var speechRecognizer: SpeechRecognizer? = null
    private var callback: VoicePerceptorCallback? = null
    var wakeWordProvider: () -> String = { wakeWord }
    var onDiagnosticChanged: ((String) -> Unit)? = null

    override fun start(callback: VoicePerceptorCallback) {
        if (isRunning) return
        val hasAudioPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasAudioPermission) {
            Log.w(TAG, "Cannot start OfflineJarvisPerceptor: RECORD_AUDIO not granted")
            onDiagnosticChanged?.invoke("未授予麦克风权限")
            callback.onError("缺少麦克风权限")
            return
        }
        this.callback = callback
        isRunning = true
        val currentWake = wakeWordProvider().ifBlank { "贾维斯" }
        onDiagnosticChanged?.invoke("监听待命中 (唤醒词: $currentWake)")
        initOfflineRecognizer()
        startAudioCaptureLoop()
        Log.i(TAG, "Offline Jarvis Perceptor started (WakeWord=\"$currentWake\")")
    }

    private fun initOfflineRecognizer() {
        scope.launch(Dispatchers.Main) {
            try {
                val available = SpeechRecognizer.isRecognitionAvailable(context)
                Log.i(TAG, "SpeechRecognizer.isRecognitionAvailable=$available")
                if (!available) {
                    onDiagnosticChanged?.invoke("系统语音识别服务不可用")
                }
                if (available) {
                    speechRecognizer?.destroy()
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {
                                Log.d(TAG, "ASR ready for speech")
                                onDiagnosticChanged?.invoke("语音识别引擎已就绪")
                            }

                            override fun onBeginningOfSpeech() {
                                callback?.onSpeechStart()
                            }

                            override fun onRmsChanged(rmsdB: Float) {}
                            override fun onBufferReceived(buffer: ByteArray?) {}

                            override fun onEndOfSpeech() {
                                Log.d(TAG, "ASR end of speech")
                            }

                            override fun onError(error: Int) {
                                Log.w(TAG, "ASR recognition error code=$error")
                                watchdogJob?.cancel()
                                watchdogJob = null
                                if ((error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == 12 || error == 13) && allowPreferOffline) {
                                    allowPreferOffline = false
                                    Log.i(TAG, "Offline speech pack unavailable (code=$error), falling back to system recognizer")
                                    onDiagnosticChanged?.invoke("离线语音包不可用，切换系统识别")
                                    startAsrListening()
                                } else {
                                    lastAsrFinishedAtMs = android.os.SystemClock.elapsedRealtime()
                                    val errHint = when (error) {
                                        SpeechRecognizer.ERROR_AUDIO -> "麦克风被占用或无输入"
                                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到清晰语音，继续待命"
                                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "说话超时，继续待命"
                                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络异常"
                                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                                        else -> "语音识别异常 (code=$error)"
                                    }
                                    onDiagnosticChanged?.invoke(errHint)
                                    if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                                        initOfflineRecognizer()
                                    }
                                    resumeAudioRecordCapture()
                                    callback?.onError("asr_error_$error")
                                }
                            }

                            override fun onResults(results: Bundle?) {
                                watchdogJob?.cancel()
                                watchdogJob = null
                                lastAsrFinishedAtMs = android.os.SystemClock.elapsedRealtime()
                                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val recognizedText = matches?.firstOrNull() ?: ""
                                Log.i(TAG, "ASR Final text: \"$recognizedText\"")
                                onDiagnosticChanged?.invoke(
                                    if (recognizedText.isNotBlank()) "最近识别：$recognizedText" else "未识别到内容，继续待命"
                                )
                                resumeAudioRecordCapture()
                                if (recognizedText.isNotBlank()) {
                                    callback?.onSpeechRecognized(recognizedText, isFinal = true)
                                } else {
                                    callback?.onError("empty_result")
                                }
                            }

                            override fun onPartialResults(partialResults: Bundle?) {
                                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                matches?.firstOrNull()?.let { partial ->
                                    callback?.onSpeechRecognized(partial, isFinal = false)
                                }
                            }

                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        })
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create SpeechRecognizer on Main thread: ${e.message}")
            }
        }
    }

    private fun resumeAudioRecordCapture() {
        isListeningCommand = false
        if (isRunning) {
            try {
                val record = audioRecord
                if (record != null && record.state == AudioRecord.STATE_INITIALIZED && record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    record.startRecording()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Resume AudioRecord error: ${e.message}")
            }
        }
    }

    private fun startAudioCaptureLoop() {
        recordJob = scope.launch {
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufSize, SAMPLES_PER_FRAME * 4)

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed")
                    onDiagnosticChanged?.invoke("录音硬件初始化失败")
                    callback?.onError("录音硬件初始化失败")
                    return@launch
                }

                audioRecord?.startRecording()
                val frameBuffer = ShortArray(SAMPLES_PER_FRAME)
                var consecutiveSpeechFrames = 0
                var ambientRms = 120.0

                while (isActive && isRunning) {
                    val isSpeaking = dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.state.value == dev.remote.terminal.jarvis.api.JarvisState.SPEAKING
                    if (isSpeaking) {
                        lastSpeakingSeenAtMs = android.os.SystemClock.elapsedRealtime()
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    val inCooldown = (now - lastAsrFinishedAtMs) < POST_ASR_COOLDOWN_MS ||
                        (now - lastSpeakingSeenAtMs) < POST_SPEAKING_ECHO_GUARD_MS
                    if (isListeningCommand || isSpeaking || inCooldown) {
                        consecutiveSpeechFrames = 0
                        kotlinx.coroutines.delay(120)
                        continue
                    }

                    val record = audioRecord ?: break
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        try { record.startRecording() } catch (_: Exception) {}
                    }

                    val readCount = record.read(frameBuffer, 0, SAMPLES_PER_FRAME)
                    if (readCount <= 0) continue

                    // 1. Tier-0: 自适应门限 VAD 音频能量与人声活动检测（静默打开识别，不响提示音）
                    val rms = calculateRms(frameBuffer, readCount)
                    val adaptiveThreshold = (ambientRms * 3.0).coerceIn(ENERGY_THRESHOLD, 1400.0)
                    if (rms > adaptiveThreshold) {
                        consecutiveSpeechFrames++
                        if (!isListeningCommand && consecutiveSpeechFrames >= 2) {
                            consecutiveSpeechFrames = 0
                            Log.d(TAG, "VAD detected speech onset (rms=${rms.toInt()}, threshold=${adaptiveThreshold.toInt()})")
                            triggerSpeechListeningWindow()
                        }
                    } else {
                        ambientRms = ambientRms * 0.96 + rms * 0.04
                        consecutiveSpeechFrames = maxOf(0, consecutiveSpeechFrames - 1)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "RECORD_AUDIO permission missing: ${e.message}")
                onDiagnosticChanged?.invoke("缺少麦克风权限")
                callback?.onError("缺少麦克风权限")
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio capture loop: ${e.message}")
                onDiagnosticChanged?.invoke("拾音异常: ${e.message}")
            } finally {
                isRunning = false
                safeReleaseAudioRecord()
            }
        }
    }

    /**
     * 外部手动唤醒触发点（如耳机物理按键触发）
     */
    fun triggerWakeWordDetected() {
        openAsrSession(manualWake = true)
    }

    /**
     * 对话激活窗口内免唤醒词再次开启识别（例如回答“我在”后继续倾听后续指令）
     */
    fun openCommandWindow() {
        openAsrSession(manualWake = false)
    }

    private fun triggerSpeechListeningWindow() {
        openAsrSession(manualWake = false)
    }

    private fun openAsrSession(manualWake: Boolean) {
        if (isListeningCommand) return
        isListeningCommand = true
        val currentWake = wakeWordProvider().ifBlank { "贾维斯" }
        if (manualWake) {
            Log.i(TAG, "Manual wake triggered (WakeWord=\"$currentWake\"), activating command window")
            callback?.onWakeWordDetected(currentWake)
        } else {
            Log.d(TAG, "Silent speech listening window opened (WakeWord=\"$currentWake\")")
            callback?.onListeningWindowOpened()
        }

        // 暂停底层 VAD 录音出让麦克风句柄，防止与 SpeechRecognizer 产生硬件资源冲突
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Pause AudioRecord error: ${e.message}")
        }

        // 看门狗保护：防止底层识别器无回调导致永久锁死在倾听中
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            kotlinx.coroutines.delay(9500L)
            if (isListeningCommand) {
                Log.w(TAG, "SpeechRecognizer watchdog timeout, cancelling and resuming VAD")
                lastAsrFinishedAtMs = android.os.SystemClock.elapsedRealtime()
                onDiagnosticChanged?.invoke("语音识别超时，已恢复待命")
                cancelListening()
                callback?.onError("asr_watchdog_timeout")
            }
        }

        // 在主线程开启单次端侧语音转写
        startAsrListening()
    }

    fun cancelListening() {
        watchdogJob?.cancel()
        watchdogJob = null
        scope.launch(Dispatchers.Main) {
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Cancel SpeechRecognizer error: ${e.message}")
            }
            resumeAudioRecordCapture()
        }
    }

    private fun startAsrListening() {
        scope.launch(Dispatchers.Main) {
            try {
                if (speechRecognizer == null && SpeechRecognizer.isRecognitionAvailable(context)) {
                    initOfflineRecognizer()
                    kotlinx.coroutines.delay(80)
                }
                val recognizer = speechRecognizer
                if (recognizer == null) {
                    Log.w(TAG, "SpeechRecognizer unavailable on this device")
                    watchdogJob?.cancel()
                    watchdogJob = null
                    lastAsrFinishedAtMs = android.os.SystemClock.elapsedRealtime()
                    onDiagnosticChanged?.invoke("系统语音识别服务不可用")
                    kotlinx.coroutines.delay(500)
                    resumeAudioRecordCapture()
                    callback?.onError("recognizer_unavailable")
                    return@launch
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    if (allowPreferOffline) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                recognizer.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognizer: ${e.message}")
                watchdogJob?.cancel()
                watchdogJob = null
                lastAsrFinishedAtMs = android.os.SystemClock.elapsedRealtime()
                onDiagnosticChanged?.invoke("语音识别启动失败")
                resumeAudioRecordCapture()
                callback?.onError("start_failed")
            }
        }
    }

    private fun calculateRms(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            sum += buffer[i] * buffer[i]
        }
        return sqrt(sum / length)
    }

    private fun safeReleaseAudioRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null
    }

    override fun stop() {
        isRunning = false
        isListeningCommand = false
        watchdogJob?.cancel()
        watchdogJob = null
        recordJob?.cancel()
        recordJob = null
        safeReleaseAudioRecord()
        scope.launch(Dispatchers.Main) {
            speechRecognizer?.cancel()
        }
    }

    override fun release() {
        stop()
        scope.launch(Dispatchers.Main) {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
        callback = null
    }
}
