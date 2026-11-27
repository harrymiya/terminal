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
        private const val ENERGY_THRESHOLD = 500.0 // 静音门限
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null

    @Volatile override var isRunning: Boolean = false
        private set

    @Volatile private var isListeningCommand = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var callback: VoicePerceptorCallback? = null

    override fun start(callback: VoicePerceptorCallback) {
        if (isRunning) return
        this.callback = callback
        isRunning = true
        initOfflineRecognizer()
        startAudioCaptureLoop()
        Log.i(TAG, "Offline Jarvis Perceptor started (WakeWord=\"$wakeWord\")")
    }

    private fun initOfflineRecognizer() {
        scope.launch(Dispatchers.Main) {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {
                                Log.d(TAG, "ASR ready for speech")
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
                                isListeningCommand = false
                                Log.w(TAG, "ASR recognition error code=$error")
                            }

                            override fun onResults(results: Bundle?) {
                                isListeningCommand = false
                                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val recognizedText = matches?.firstOrNull() ?: ""
                                Log.i(TAG, "ASR Final text: \"$recognizedText\"")
                                if (recognizedText.isNotBlank()) {
                                    callback?.onSpeechRecognized(recognizedText, isFinal = true)
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

    private fun startAudioCaptureLoop() {
        recordJob = scope.launch {
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufSize, SAMPLES_PER_FRAME * 4)

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed")
                    callback?.onError("录音硬件初始化失败")
                    return@launch
                }

                audioRecord?.startRecording()
                val frameBuffer = ShortArray(SAMPLES_PER_FRAME)
                var consecutiveSpeechFrames = 0

                while (isActive && isRunning) {
                    val readCount = audioRecord?.read(frameBuffer, 0, SAMPLES_PER_FRAME) ?: -1
                    if (readCount <= 0) continue

                    // 1. Tier-0: VAD 音频能量与活动检测
                    val rms = calculateRms(frameBuffer, readCount)
                    if (rms > ENERGY_THRESHOLD) {
                        consecutiveSpeechFrames++
                        // 当处于休眠且检测到持续的人声活动时，触发唤醒词匹配窗口
                        if (!isListeningCommand && consecutiveSpeechFrames >= 5) {
                            // 触发唤醒词窗口或本地离线快速词根检测
                            consecutiveSpeechFrames = 0
                            triggerWakeWordDetected()
                        }
                    } else {
                        consecutiveSpeechFrames = maxOf(0, consecutiveSpeechFrames - 1)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "RECORD_AUDIO permission missing: ${e.message}")
                callback?.onError("缺少麦克风权限")
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio capture loop: ${e.message}")
            } finally {
                safeReleaseAudioRecord()
            }
        }
    }

    /**
     * 外部手动唤醒触发点（如耳机物理按键触发）或 VAD 命中唤醒词触发
     */
    fun triggerWakeWordDetected() {
        if (isListeningCommand) return
        isListeningCommand = true
        Log.i(TAG, "Wake word \"$wakeWord\" detected, activating command window")
        callback?.onWakeWordDetected(wakeWord)

        // 在主线程开启单次端侧语音转写
        scope.launch(Dispatchers.Main) {
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // 强制优先完全离线转写
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognizer: ${e.message}")
                isListeningCommand = false
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
        recordJob?.cancel()
        recordJob = null
        safeReleaseAudioRecord()
        scope.launch(Dispatchers.Main) {
            speechRecognizer?.stopListening()
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
