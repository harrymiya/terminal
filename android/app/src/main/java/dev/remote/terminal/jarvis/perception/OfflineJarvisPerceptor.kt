package dev.remote.terminal.jarvis.perception

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.remote.terminal.jarvis.api.JarvisState
import dev.remote.terminal.jarvis.api.VoicePerceptor
import dev.remote.terminal.jarvis.api.VoicePerceptorCallback
import dev.remote.terminal.jarvis.audio.BluetoothScoManager
import dev.remote.terminal.jarvis.coordinator.JarvisCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * 基于 Sherpa-ONNX 端侧流式模型的完全离线语音感知器。
 * 1. 16kHz PCM 持续通过轻量能量 VAD 过滤环境底噪，静默时不触发推理，保持低功耗；
 * 2. 维护 440ms 环形预卷缓冲区，起音零丢字直接送入内置 Zipformer CTC 离线中文识别引擎；
 * 3. 不依赖厂商系统 SpeechRecognizer，解决国行 ROM 无语音识别或第三方引擎权限报错问题，且熄屏可稳定工作。
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
        private const val PRE_ROLL_FRAMES = 22 // ~440ms 音频预卷，保证“老六”首字不丢音
        private const val ENERGY_THRESHOLD = 340.0
        private const val BT_ENERGY_THRESHOLD = 135.0
        private const val POST_SPEAKING_ECHO_GUARD_MS = 650L
        private const val MAX_UTTERANCE_MS = 9000L
        private const val COMMAND_WINDOW_MS = 9000L
        private const val BT_POLL_INTERVAL_MS = 1500L
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private var sherpaRecognizer: OnlineRecognizer? = null
    private var sherpaStream: OnlineStream? = null

    @Volatile override var isRunning: Boolean = false
        private set

    @Volatile private var forceListeningUntilMs: Long = 0L
    @Volatile private var resetRequested: Boolean = false
    @Volatile private var lastSpeakingSeenAtMs: Long = 0L

    private var callback: VoicePerceptorCallback? = null
    var scoManager: BluetoothScoManager? = null
    var preferBluetoothMicProvider: () -> Boolean = { true }
    var wakeWordProvider: () -> String = { wakeWord }
    var onDiagnosticChanged: ((String) -> Unit)? = null

    override fun start(callback: VoicePerceptorCallback) {
        if (isRunning) return
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPermission) {
            Log.w(TAG, "Cannot start OfflineJarvisPerceptor: RECORD_AUDIO not granted")
            onDiagnosticChanged?.invoke("未授予麦克风权限")
            callback.onError("缺少麦克风权限")
            return
        }
        this.callback = callback
        isRunning = true
        onDiagnosticChanged?.invoke("正在加载离线语音引擎...")
        startAudioCaptureLoop()
    }

    private fun ensureSherpaInitialized(): Boolean {
        if (sherpaRecognizer != null && sherpaStream != null) return true
        return try {
            val featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f)
            val modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "sherpa-onnx-zh/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "sherpa-onnx-zh/decoder-epoch-99-avg-1.onnx",
                    joiner = "sherpa-onnx-zh/joiner-epoch-99-avg-1.int8.onnx"
                ),
                tokens = "sherpa-onnx-zh/tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "zipformer"
            )
            val endpointConfig = EndpointConfig(
                rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.0f, minUtteranceLength = 0f),
                rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 0.7f, minUtteranceLength = 0f),
                rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0f, minUtteranceLength = 15.0f)
            )
            val recognizerConfig = OnlineRecognizerConfig(
                featConfig = featConfig,
                modelConfig = modelConfig,
                endpointConfig = endpointConfig,
                enableEndpoint = true,
                decodingMethod = "greedy_search"
            )
            val recognizer = OnlineRecognizer(context.assets, recognizerConfig)
            runSelfTestSample(recognizer)
            val stream = recognizer.createStream("")
            sherpaRecognizer = recognizer
            sherpaStream = stream
            val currentWake = wakeWordProvider().ifBlank { "贾维斯" }
            onDiagnosticChanged?.invoke("监听待命中 (端侧离线 · 唤醒词: $currentWake)")
            Log.i(TAG, "Sherpa-ONNX offline recognizer initialized successfully (WakeWord=\"$currentWake\")")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Sherpa-ONNX recognizer: ${e.message}", e)
            onDiagnosticChanged?.invoke("端侧语音引擎加载失败: ${e.message ?: "unknown"}")
            false
        }
    }

    private fun startAudioCaptureLoop() {
        recordJob = scope.launch {
            if (!ensureSherpaInitialized()) {
                isRunning = false
                callback?.onError("sherpa_init_failed")
                return@launch
            }

            val recognizer = sherpaRecognizer ?: return@launch
            val stream = sherpaStream ?: return@launch

            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufSize, SAMPLES_PER_FRAME * 8)

            try {
                var activeBtDevice = if (preferBluetoothMicProvider()) scoManager?.findBluetoothInputDevice() else null
                audioRecord = createConfiguredAudioRecord(activeBtDevice, bufferSize)

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed")
                    onDiagnosticChanged?.invoke("录音硬件初始化失败")
                    callback?.onError("录音硬件初始化失败")
                    return@launch
                }

                audioRecord?.startRecording()
                logAudioRouting(audioRecord, activeBtDevice)
                updateIdleDiagnostic(activeBtDevice != null)

                val frameBuffer = ShortArray(SAMPLES_PER_FRAME)
                val preRoll = ArrayDeque<FloatArray>(PRE_ROLL_FRAMES)
                var consecutiveSpeechFrames = 0
                var ambientRms = if (activeBtDevice != null) 45.0 else 120.0
                var isDecodingUtterance = false
                var utteranceStartedAtMs = 0L
                var lastPartialText = ""
                var wasInEchoGuard = false
                var lastBtPollAtMs = SystemClock.elapsedRealtime()

                while (isActive && isRunning) {
                    val now = SystemClock.elapsedRealtime()
                    val isSpeaking = JarvisCoordinator.state.value == JarvisState.SPEAKING
                    if (isSpeaking) {
                        lastSpeakingSeenAtMs = now
                    }
                    val inEchoGuard = isSpeaking || (now - lastSpeakingSeenAtMs) < POST_SPEAKING_ECHO_GUARD_MS

                    if (resetRequested) {
                        resetRequested = false
                        if (isDecodingUtterance) {
                            recognizer.reset(stream)
                            isDecodingUtterance = false
                        }
                        lastPartialText = ""
                        preRoll.clear()
                    }

                    // 从播报返回拾音后，自动重新激活蓝牙耳机麦克风通道
                    if (wasInEchoGuard && !inEchoGuard) {
                        wasInEchoGuard = false
                        val desiredBt = if (preferBluetoothMicProvider()) scoManager?.findBluetoothInputDevice() else null
                        if (desiredBt != null) {
                            scoManager?.startChannel()
                            delay(150)
                            audioRecord?.preferredDevice = desiredBt
                            activeBtDevice = desiredBt
                            logAudioRouting(audioRecord, desiredBt)
                        }
                    }

                    // 空闲期定期检查蓝牙耳机热插拔，自动在耳机麦/手机麦间无缝切换
                    if (!isDecodingUtterance && !inEchoGuard && (now - lastBtPollAtMs) >= BT_POLL_INTERVAL_MS) {
                        lastBtPollAtMs = now
                        val desiredBt = if (preferBluetoothMicProvider()) scoManager?.findBluetoothInputDevice() else null
                        if (desiredBt?.id != activeBtDevice?.id) {
                            Log.i(TAG, "Audio input device changed: ${activeBtDevice?.productName} -> ${desiredBt?.productName}")
                            safeReleaseAudioRecord()
                            if (desiredBt == null) {
                                scoManager?.stopChannel()
                            }
                            activeBtDevice = desiredBt
                            audioRecord = createConfiguredAudioRecord(activeBtDevice, bufferSize)
                            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                                audioRecord?.startRecording()
                                logAudioRouting(audioRecord, activeBtDevice)
                                updateIdleDiagnostic(activeBtDevice != null)
                            }
                            ambientRms = if (activeBtDevice != null) 45.0 else 120.0
                        }
                    }

                    val record = audioRecord ?: break
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        try {
                            record.startRecording()
                        } catch (_: Exception) {
                        }
                    }

                    val readCount = record.read(frameBuffer, 0, SAMPLES_PER_FRAME)
                    if (readCount <= 0) {
                        delay(20)
                        continue
                    }

                    if (inEchoGuard) {
                        wasInEchoGuard = true
                        consecutiveSpeechFrames = 0
                        if (isDecodingUtterance) {
                            recognizer.reset(stream)
                            isDecodingUtterance = false
                            lastPartialText = ""
                        }
                        preRoll.clear()
                        continue
                    }

                    val floatSamples = FloatArray(readCount) { idx -> frameBuffer[idx] / 32768.0f }
                    val rms = calculateRms(frameBuffer, readCount)
                    val inForcedWindow = now < forceListeningUntilMs

                    var fedCurrentFrame = false
                    if (!isDecodingUtterance) {
                        if (preRoll.size >= PRE_ROLL_FRAMES) {
                            preRoll.removeFirst()
                        }
                        preRoll.addLast(floatSamples)

                        val floor = if (activeBtDevice != null) BT_ENERGY_THRESHOLD else ENERGY_THRESHOLD
                        val adaptiveThreshold = (ambientRms * 2.6).coerceIn(floor, 1100.0)
                        if (inForcedWindow || rms > adaptiveThreshold) {
                            consecutiveSpeechFrames++
                            if (inForcedWindow || consecutiveSpeechFrames >= 2) {
                                consecutiveSpeechFrames = 0
                                isDecodingUtterance = true
                                utteranceStartedAtMs = now
                                lastPartialText = ""
                                recognizer.reset(stream)
                                while (preRoll.isNotEmpty()) {
                                    stream.acceptWaveform(preRoll.removeFirst(), SAMPLE_RATE)
                                }
                                fedCurrentFrame = true
                                Log.d(
                                    TAG,
                                    "Speech onset (bt=${activeBtDevice != null}, rms=${rms.toInt()}, threshold=${adaptiveThreshold.toInt()}, forced=$inForcedWindow)"
                                )
                                if (!inForcedWindow) {
                                    callback?.onListeningWindowOpened()
                                }
                            }
                        } else {
                            ambientRms = ambientRms * 0.96 + rms * 0.04
                            consecutiveSpeechFrames = maxOf(0, consecutiveSpeechFrames - 1)
                        }
                    }

                    if (isDecodingUtterance) {
                        if (!fedCurrentFrame) {
                            stream.acceptWaveform(floatSamples, SAMPLE_RATE)
                        }
                        while (recognizer.isReady(stream)) {
                            recognizer.decode(stream)
                        }
                        val partial = normalizeRecognizedText(recognizer.getResult(stream).text)
                        if (partial.isNotBlank() && partial != lastPartialText) {
                            lastPartialText = partial
                            Log.d(TAG, "Sherpa Partial: \"$partial\"")
                            onDiagnosticChanged?.invoke("识别中：$partial")
                            callback?.onSpeechRecognized(partial, isFinal = false)
                        }

                        val endpoint = recognizer.isEndpoint(stream)
                        val timedOut = (now - utteranceStartedAtMs) >= MAX_UTTERANCE_MS
                        if (endpoint || timedOut) {
                            val finalText = partial.ifBlank { lastPartialText }
                            recognizer.reset(stream)
                            isDecodingUtterance = false
                            forceListeningUntilMs = 0L
                            lastPartialText = ""
                            preRoll.clear()

                            Log.i(TAG, "Sherpa Final text: \"$finalText\" (endpoint=$endpoint, timedOut=$timedOut)")
                            val currentWake = wakeWordProvider().ifBlank { "贾维斯" }
                            if (finalText.isNotBlank()) {
                                onDiagnosticChanged?.invoke("最近识别：$finalText")
                                callback?.onSpeechRecognized(finalText, isFinal = true)
                            } else {
                                onDiagnosticChanged?.invoke("监听待命中 (端侧离线 · 唤醒词: $currentWake)")
                                callback?.onError("empty_result")
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "RECORD_AUDIO permission missing: ${e.message}")
                onDiagnosticChanged?.invoke("缺少麦克风权限")
                callback?.onError("缺少麦克风权限")
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio capture loop: ${e.message}", e)
                onDiagnosticChanged?.invoke("拾音异常: ${e.message}")
            } finally {
                isRunning = false
                safeReleaseAudioRecord()
            }
        }
    }

    /**
     * 外部手动唤醒触发点（如测试按钮或耳机物理按键触发）
     */
    fun triggerWakeWordDetected() {
        val currentWake = wakeWordProvider().ifBlank { "贾维斯" }
        forceListeningUntilMs = SystemClock.elapsedRealtime() + COMMAND_WINDOW_MS
        resetRequested = true
        Log.i(TAG, "Manual wake triggered (WakeWord=\"$currentWake\")")
        callback?.onWakeWordDetected(currentWake)
    }

    /**
     * 对话激活窗口内免唤醒词再次开启识别（例如回答“我在”后继续倾听后续指令）
     */
    fun openCommandWindow() {
        val currentWake = wakeWordProvider().ifBlank { "贾维斯" }
        forceListeningUntilMs = SystemClock.elapsedRealtime() + COMMAND_WINDOW_MS
        resetRequested = true
        Log.d(TAG, "Command follow-up listening window opened (WakeWord=\"$currentWake\")")
        callback?.onListeningWindowOpened()
    }

    fun cancelListening() {
        forceListeningUntilMs = 0L
        resetRequested = true
    }

    private fun createConfiguredAudioRecord(btInput: AudioDeviceInfo?, bufferSize: Int): AudioRecord? {
        if (btInput != null) {
            scoManager?.startChannel()
            Thread.sleep(150)
        }
        val source = if (btInput != null) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        return AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize).apply {
            if (btInput != null && state == AudioRecord.STATE_INITIALIZED) {
                preferredDevice = btInput
            }
        }
    }

    private fun logAudioRouting(record: AudioRecord?, targetBt: AudioDeviceInfo?) {
        val routed = runCatching { record?.routedDevice }.getOrNull()
        Log.i(
            TAG,
            "AudioRecord route: targetBt=${targetBt?.productName}(type=${targetBt?.type}), actualRouted=${routed?.productName}(type=${routed?.type})"
        )
    }

    private fun updateIdleDiagnostic(usingBluetoothMic: Boolean) {
        val currentWake = wakeWordProvider().ifBlank { "贾维斯" }
        val micLabel = if (usingBluetoothMic) "蓝牙耳机麦" else "手机麦克风"
        onDiagnosticChanged?.invoke("监听待命中 ($micLabel · 唤醒词: $currentWake)")
    }

    private fun runSelfTestSample(recognizer: OnlineRecognizer) {
        try {
            val bytes = context.assets.open("sherpa-onnx-zh/0.wav").use { it.readBytes() }
            if (bytes.size <= 44) return
            val sampleCount = (bytes.size - 44) / 2
            val floats = FloatArray(sampleCount) { i ->
                val lo = bytes[44 + i * 2].toInt() and 0xFF
                val hi = bytes[44 + i * 2 + 1].toInt()
                ((hi shl 8) or lo).toShort() / 32768.0f
            }
            val testStream = recognizer.createStream("")
            testStream.acceptWaveform(floats, SAMPLE_RATE)
            testStream.acceptWaveform(FloatArray(SAMPLE_RATE / 2), SAMPLE_RATE)
            testStream.inputFinished()
            while (recognizer.isReady(testStream)) {
                recognizer.decode(testStream)
            }
            val resultText = normalizeRecognizedText(recognizer.getResult(testStream).text)
            testStream.release()
            Log.i(TAG, "Sherpa self-test 0.wav -> \"$resultText\"")
        } catch (e: Throwable) {
            Log.w(TAG, "Sherpa self-test skipped/failed: ${e.message}")
        }
    }

    private fun normalizeRecognizedText(raw: String): String {
        return raw.trim()
            .replace(Regex("""(?<=[\u4e00-\u9fa5])\s+(?=[\u4e00-\u9fa5])"""), "")
            .replace(Regex("""\s+"""), " ")
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
        forceListeningUntilMs = 0L
        resetRequested = false
        recordJob?.cancel()
        recordJob = null
        safeReleaseAudioRecord()
    }

    override fun release() {
        stop()
        try {
            sherpaStream?.release()
        } catch (_: Exception) {
        }
        try {
            sherpaRecognizer?.release()
        } catch (_: Exception) {
        }
        sherpaStream = null
        sherpaRecognizer = null
        callback = null
    }
}
