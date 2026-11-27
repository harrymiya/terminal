package dev.remote.terminal.jarvis.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dev.remote.terminal.jarvis.api.VoiceSynthesizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Android 原生离线 TextToSpeech 合成器。
 * 0 延迟、0 流量、极度省电，经过音高与语速调校呈现沉稳英伦管家声线。
 */
class NativeJarvisSynthesizer : VoiceSynthesizer {

    companion object {
        private const val TAG = "NativeJarvisTTS"
    }

    private var tts: TextToSpeech? = null
    @Volatile private var isReady = false
    @Volatile private var speakingCount = 0

    private var pitch: Float = 0.85f
    private var rate: Float = 1.08f
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    override val isSpeaking: Boolean
        get() = speakingCount > 0 || tts?.isSpeaking == true

    override fun initialize(context: Context, onReady: (Boolean) -> Unit) {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val currentTts = tts
                if (currentTts != null) {
                    val langResult = currentTts.setLanguage(Locale.CHINESE)
                    if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Chinese not supported by default engine, falling back to English")
                        currentTts.setLanguage(Locale.ENGLISH)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        val audioAttributes = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setLegacyStreamType(android.media.AudioManager.STREAM_MUSIC)
                            .build()
                        currentTts.setAudioAttributes(audioAttributes)
                    }
                    applyParams()
                    currentTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            speakingCount++
                        }

                        override fun onDone(utteranceId: String?) {
                            speakingCount = maxOf(0, speakingCount - 1)
                            utteranceId?.let { id ->
                                callbacks.remove(id)?.invoke()
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            speakingCount = maxOf(0, speakingCount - 1)
                            utteranceId?.let { id ->
                                callbacks.remove(id)?.invoke()
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            speakingCount = maxOf(0, speakingCount - 1)
                            Log.w(TAG, "TTS utterance error: $utteranceId, code: $errorCode")
                            utteranceId?.let { id ->
                                callbacks.remove(id)?.invoke()
                            }
                        }
                    })
                    isReady = true
                    Log.i(TAG, "Native Jarvis TTS initialized successfully")
                    onReady(true)
                } else {
                    onReady(false)
                }
            } else {
                Log.e(TAG, "Failed to initialize Native TextToSpeech, status=$status")
                onReady(false)
            }
        }
    }

    override fun updateParams(pitch: Float, rate: Float) {
        this.pitch = pitch
        this.rate = rate
        applyParams()
    }

    private fun applyParams() {
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
    }

    override fun speak(text: String, queueMode: Int, onDone: (() -> Unit)?) {
        if (!isReady || tts == null) {
            Log.w(TAG, "TTS not ready, discarding speak request: $text")
            onDone?.invoke()
            return
        }

        val utteranceId = UUID.randomUUID().toString()
        if (onDone != null) {
            callbacks[utteranceId] = onDone
        }

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        }

        val mode = if (queueMode == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, params, utteranceId)
    }

    override fun stop() {
        callbacks.clear()
        speakingCount = 0
        tts?.stop()
    }

    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
