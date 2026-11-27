package dev.remote.terminal.jarvis.tts

import android.content.Context
import android.util.Log
import dev.remote.terminal.jarvis.api.JarvisConfig
import dev.remote.terminal.jarvis.api.VoiceSynthesizer
import dev.remote.terminal.jarvis.audio.BluetoothScoManager
import dev.remote.terminal.jarvis.audio.EarconManager

/**
 * 贾维斯专属发音门面 (Speaker Facade)。
 * 支持动态切换原生离线合成与云端高保真合成，自动管理音频焦点与蓝牙耳机通道。
 */
class JarvisSpeaker(
    private val context: Context,
    private val scoManager: BluetoothScoManager,
    private val earconManager: EarconManager,
    private val config: JarvisConfig
) {
    companion object {
        private const val TAG = "JarvisSpeaker"
    }

    private var activeSynthesizer: VoiceSynthesizer = NativeJarvisSynthesizer()

    init {
        rebuildSynthesizer()
    }

    fun rebuildSynthesizer() {
        activeSynthesizer.release()
        activeSynthesizer = if (config.ttsEngineMode == "cloud") {
            CloudJarvisSynthesizer()
        } else {
            NativeJarvisSynthesizer()
        }
        activeSynthesizer.initialize(context) { ready ->
            if (ready) {
                activeSynthesizer.updateParams(config.speechPitch, config.speechRate)
            }
        }
    }

    val isSpeaking: Boolean
        get() = activeSynthesizer.isSpeaking

    /**
     * 贾维斯核心耳语输出。
     * 在开始播报前夺取瞬态音频焦点，播报完毕后归还焦点。
     */
    fun speak(text: String, queueMode: Int = 0, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        Log.i(TAG, "Jarvis speaking: \"$text\"")
        scoManager.stopChannel()
        scoManager.requestAudioFocus()
        activeSynthesizer.speak(text, queueMode) {
            scoManager.abandonAudioFocus()
            onDone?.invoke()
        }
    }

    fun stop() {
        activeSynthesizer.stop()
        scoManager.abandonAudioFocus()
    }

    fun release() {
        activeSynthesizer.release()
    }
}
