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
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
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
import kotlin.math.sqrt

/**
 * 基于 Silero 神经网络 VAD + Paraformer 高精离线中文识别器的语音感知引擎。
 * 1. Silero VAD (512 samples / 32ms 窗口) 智能区分人声与噪音，尾部静音容忍 1.45s，支持整段长句不停顿截断；
 * 2. Paraformer-zh-small 离线模型同时兼顾中英文混合与地方口音，整段转写精准率大幅优于极小流式模型；
 * 3. 自动绑定蓝牙耳机麦克风（OpenRock X 等），在播报与思考期间静默保护，杜绝自回声和打断。
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
        private const val VAD_WINDOW_SAMPLES = 512 // 32ms @ 16kHz (Silero VAD exact window)
        private const val PRE_ROLL_FRAMES = 12 // ~384ms 音频预缓冲，避免首字被吞
        private const val MAX_SPEECH_SAMPLES = 25 * SAMPLE_RATE
        private const val STREAM_PREVIEW_INTERVAL_MS = 550L
        private const val MIN_PREVIEW_SAMPLES = (SAMPLE_RATE * 0.45f).toInt()
        private const val POST_SPEAKING_ECHO_GUARD_MS = 650L
        private const val BT_POLL_INTERVAL_MS = 1500L
        private const val BT_MIC_GAIN = 1.6f
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private var sileroVad: Vad? = null
    private var offlineRecognizer: OfflineRecognizer? = null
    private val decoderLock = Any()

    @Volatile override var isRunning: Boolean = false
        private set

    @Volatile private var resetRequested: Boolean = false
    @Volatile private var lastSpeakingSeenAtMs: Long = 0L
    @Volatile private var previewDecodeBusy: Boolean = false

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
        onDiagnosticChanged?.invoke("正在加载高精离线语音引擎...")
        startAudioCaptureLoop()
    }

    private fun ensureEnginesInitialized(): Boolean {
        if (sileroVad != null && offlineRecognizer != null) return true
        return try {
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "sherpa-onnx-zh/silero_vad.int8.onnx",
                    threshold = 0.42f,
                    minSilenceDuration = 1.15f,
                    minSpeechDuration = 0.22f,
                    windowSize = VAD_WINDOW_SAMPLES,
                    maxSpeechDuration = 24.0f
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
            val vad = Vad(context.assets, vadConfig)

            val offModel = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = "sherpa-onnx-zh/model.int8.onnx"
                ),
                tokens = "sherpa-onnx-zh/tokens.txt",
                numThreads = 4,
                debug = false,
                provider = "cpu",
                modelType = "paraformer"
            )
            val recConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = offModel,
                decodingMethod = "greedy_search"
            )
            val recognizer = OfflineRecognizer(context.assets, recConfig)
            runSelfTestSample(recognizer)

            sileroVad = vad
            offlineRecognizer = recognizer
            Log.i(TAG, "Silero VAD + Paraformer offline recognizer initialized (WakeWord=\"${wakeWordProvider()}\")")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Silero/Paraformer: ${e.message}", e)
            onDiagnosticChanged?.invoke("语音引擎加载失败: ${e.message ?: "unknown"}")
            false
        }
    }

    private fun startAudioCaptureLoop() {
        recordJob = scope.launch {
            if (!ensureEnginesInitialized()) {
                isRunning = false
                callback?.onError("sherpa_init_failed")
                return@launch
            }

            val vad = sileroVad ?: return@launch
            val recognizer = offlineRecognizer ?: return@launch

            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufSize, VAD_WINDOW_SAMPLES * 8)

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

                val frameBuffer = ShortArray(VAD_WINDOW_SAMPLES)
                val preRollRing = ArrayDeque<FloatArray>(PRE_ROLL_FRAMES + 1)
                val speechBuf = FloatArray(MAX_SPEECH_SAMPLES)
                var speechLen = 0
                var speechSegmentPending = false
                var lastPreviewAtMs = 0L
                var lastPreviewText = ""
                var wasInSpeech = false
                var wasInEchoGuard = false
                var lastBtPollAtMs = SystemClock.elapsedRealtime()
                var speechStartedAtMs = 0L

                while (isActive && isRunning) {
                    val now = SystemClock.elapsedRealtime()
                    val coordState = JarvisCoordinator.state.value
                    val isBusy = coordState == JarvisState.SPEAKING ||
                        coordState == JarvisState.PROCESSING ||
                        coordState == JarvisState.EXECUTING
                    if (coordState == JarvisState.SPEAKING) {
                        lastSpeakingSeenAtMs = now
                    }
                    // 对话思考/执行/播报期间禁止拾音打断，并在播报后保留回声保护窗口
                    val inEchoGuard = isBusy || (now - lastSpeakingSeenAtMs) < POST_SPEAKING_ECHO_GUARD_MS

                    if (resetRequested) {
                        resetRequested = false
                        vad.clear()
                        wasInSpeech = false
                        speechSegmentPending = false
                        speechLen = 0
                        preRollRing.clear()
                        lastPreviewText = ""
                    }

                    // 思考/播报结束恢复待命后，按需重连蓝牙耳机麦克风通道并刷新诊断状态
                    if (wasInEchoGuard && !inEchoGuard) {
                        wasInEchoGuard = false
                        vad.clear()
                        wasInSpeech = false
                        speechSegmentPending = false
                        speechLen = 0
                        preRollRing.clear()
                        lastPreviewText = ""
                        val desiredBt = if (preferBluetoothMicProvider()) scoManager?.findBluetoothInputDevice() else null
                        if (desiredBt != null && scoManager?.isScoActive != true) {
                            scoManager?.startChannel()
                            delay(150)
                            audioRecord?.preferredDevice = desiredBt
                            activeBtDevice = desiredBt
                            logAudioRouting(audioRecord, desiredBt)
                        }
                        updateIdleDiagnostic(activeBtDevice != null)
                    }

                    // 空闲期定期检查蓝牙耳机热插拔，自动在耳机麦/手机麦间无缝切换
                    if (!wasInSpeech && !speechSegmentPending && !inEchoGuard && (now - lastBtPollAtMs) >= BT_POLL_INTERVAL_MS) {
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
                            vad.clear()
                            preRollRing.clear()
                        }
                    }

                    val record = audioRecord ?: break
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        try {
                            record.startRecording()
                        } catch (_: Exception) {
                        }
                    }

                    val readCount = readExactSamples(record, frameBuffer)
                    if (readCount < VAD_WINDOW_SAMPLES) {
                        delay(15)
                        continue
                    }

                    if (inEchoGuard) {
                        wasInEchoGuard = true
                        if (wasInSpeech || speechSegmentPending || !vad.empty()) {
                            vad.clear()
                            wasInSpeech = false
                            speechSegmentPending = false
                            speechLen = 0
                            preRollRing.clear()
                            lastPreviewText = ""
                        }
                        continue
                    }

                    val gain = if (activeBtDevice != null) BT_MIC_GAIN else 1.0f
                    val floatSamples = FloatArray(VAD_WINDOW_SAMPLES) { idx ->
                        ((frameBuffer[idx] / 32768.0f) * gain).coerceIn(-0.99f, 0.99f)
                    }

                    vad.acceptWaveform(floatSamples)
                    val speechActiveNow = vad.isSpeechDetected()
                    if (speechActiveNow && !wasInSpeech) {
                        wasInSpeech = true
                        speechStartedAtMs = now
                        if (!speechSegmentPending) {
                            speechSegmentPending = true
                            speechLen = 0
                            lastPreviewText = ""
                            lastPreviewAtMs = now
                            for (pre in preRollRing) {
                                val copyLen = minOf(pre.size, speechBuf.size - speechLen)
                                if (copyLen > 0) {
                                    pre.copyInto(speechBuf, destinationOffset = speechLen, startIndex = 0, endIndex = copyLen)
                                    speechLen += copyLen
                                }
                            }
                        }
                        val rms = calculateRms(frameBuffer, readCount).toInt()
                        Log.d(TAG, "Silero VAD speech start (bt=${activeBtDevice != null}, rms=$rms)")
                        onDiagnosticChanged?.invoke("正在倾听语音...")
                        callback?.onSpeechStart()
                    } else if (!speechActiveNow && wasInSpeech) {
                        wasInSpeech = false
                    }

                    if (speechSegmentPending) {
                        val copyLen = minOf(floatSamples.size, speechBuf.size - speechLen)
                        if (copyLen > 0) {
                            floatSamples.copyInto(speechBuf, destinationOffset = speechLen, startIndex = 0, endIndex = copyLen)
                            speechLen += copyLen
                        }
                        if (speechLen >= MIN_PREVIEW_SAMPLES &&
                            !previewDecodeBusy &&
                            (now - lastPreviewAtMs) >= STREAM_PREVIEW_INTERVAL_MS
                        ) {
                            lastPreviewAtMs = now
                            previewDecodeBusy = true
                            val snapshot = speechBuf.copyOf(speechLen)
                            scope.launch(Dispatchers.Default) {
                                try {
                                    val partial = decodeAudioSamples(recognizer, snapshot)
                                    if (partial.isNotBlank() && partial != lastPreviewText && !inEchoGuard) {
                                        lastPreviewText = partial
                                        onDiagnosticChanged?.invoke("实时识别：$partial")
                                        callback?.onSpeechRecognized(partial, isFinal = false)
                                    }
                                } finally {
                                    previewDecodeBusy = false
                                }
                            }
                        }
                    } else {
                        if (preRollRing.size >= PRE_ROLL_FRAMES) preRollRing.removeFirst()
                        preRollRing.addLast(floatSamples)
                    }

                    while (!vad.empty()) {
                        val segment = vad.front()
                        vad.pop()
                        speechSegmentPending = false
                        speechLen = 0
                        preRollRing.clear()
                        lastPreviewText = ""
                        val samples = segment.samples
                        val durationSec = samples.size / SAMPLE_RATE.toFloat()
                        if (samples.size < SAMPLE_RATE / 5) {
                            // 过滤低于 200ms 的极短杂音，并通知结束本次人声触发
                            callback?.onError("short_noise")
                            continue
                        }
                        val decodeT0 = SystemClock.elapsedRealtime()
                        val finalText = decodeAudioSamples(recognizer, samples)
                        val decodeMs = SystemClock.elapsedRealtime() - decodeT0

                        Log.i(
                            TAG,
                            "Paraformer decoded (${"%.2f".format(durationSec)}s audio in ${decodeMs}ms): \"$finalText\""
                        )
                        if (finalText.isNotBlank()) {
                            onDiagnosticChanged?.invoke("最近识别：$finalText")
                            callback?.onSpeechRecognized(finalText, isFinal = true)
                        } else {
                            updateIdleDiagnostic(activeBtDevice != null)
                            callback?.onError("empty_result")
                        }
                    }

                    // 超长保护：避免极端噪音环境语音持续不释放
                    if (wasInSpeech && (now - speechStartedAtMs) > 25_000L) {
                        vad.flush()
                        wasInSpeech = false
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

    private fun readExactSamples(record: AudioRecord, dst: ShortArray): Int {
        var offset = 0
        while (offset < dst.size && isRunning) {
            val n = record.read(dst, offset, dst.size - offset)
            if (n <= 0) return offset
            offset += n
        }
        return offset
    }

    /**
     * 外部手动唤醒触发点（如测试按钮或耳机物理按键触发）
     */
    fun triggerWakeWordDetected() {
        val currentWake = wakeWordProvider().ifBlank { "贾维斯" }
        Log.i(TAG, "Manual wake triggered (WakeWord=\"$currentWake\")")
        callback?.onWakeWordDetected(currentWake)
    }

    /**
     * 对话激活窗口内免唤醒词再次开启识别（Silero VAD 常驻连续监听，仅通知状态进入 LISTENING）
     */
    fun openCommandWindow() {
        val currentWake = wakeWordProvider().ifBlank { "贾维斯" }
        Log.d(TAG, "Command follow-up listening window ready (WakeWord=\"$currentWake\")")
        callback?.onListeningWindowOpened()
    }

    fun cancelListening() {
        resetRequested = true
    }

    fun returnToWakeListening() {
        resetRequested = true
        updateIdleDiagnostic(preferBluetoothMicProvider() && scoManager?.findBluetoothInputDevice() != null)
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
        val micLabel = if (usingBluetoothMic) "蓝牙耳机麦 · Paraformer" else "手机麦克风 · Paraformer"
        onDiagnosticChanged?.invoke("监听唤醒中 ($micLabel · 唤醒词: $currentWake)")
    }

    private fun runSelfTestSample(recognizer: OfflineRecognizer) {
        try {
            val bytes = context.assets.open("sherpa-onnx-zh/0.wav").use { it.readBytes() }
            if (bytes.size <= 44) return
            val sampleCount = (bytes.size - 44) / 2
            val floats = FloatArray(sampleCount) { i ->
                val lo = bytes[44 + i * 2].toInt() and 0xFF
                val hi = bytes[44 + i * 2 + 1].toInt()
                ((hi shl 8) or lo).toShort() / 32768.0f
            }
            val testStream = recognizer.createStream()
            testStream.acceptWaveform(floats, SAMPLE_RATE)
            recognizer.decode(testStream)
            val resultText = normalizeRecognizedText(recognizer.getResult(testStream).text)
            testStream.release()
            Log.i(TAG, "Paraformer self-test 0.wav -> \"$resultText\"")
        } catch (e: Throwable) {
            Log.w(TAG, "Paraformer self-test skipped/failed: ${e.message}")
        }
    }

    private fun decodeAudioSamples(recognizer: OfflineRecognizer, samples: FloatArray): String {
        return synchronized(decoderLock) {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)
                normalizeRecognizedText(recognizer.getResult(stream).text)
            } finally {
                stream.release()
            }
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
        return sqrt(sum / maxOf(1, length))
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
        resetRequested = false
        recordJob?.cancel()
        recordJob = null
        safeReleaseAudioRecord()
    }

    override fun release() {
        stop()
        try {
            sileroVad?.release()
        } catch (_: Exception) {
        }
        try {
            offlineRecognizer?.release()
        } catch (_: Exception) {
        }
        sileroVad = null
        offlineRecognizer = null
        callback = null
    }
}
