package dev.remote.terminal.jarvis.perception

import android.content.Context
import android.util.Log
import dev.remote.terminal.jarvis.api.VoicePerceptor
import dev.remote.terminal.jarvis.api.VoicePerceptorCallback

/**
 * 云端在线语音感知器扩展实现 (可插拔备用)。
 * 未来接入云端流式 ASR (如 FunASR / Whisper / 腾讯云) 时直接替换此处实现。
 */
class OnlineJarvisPerceptor(
    private val context: Context,
    private val endpointUrl: String = "",
    private val apiKey: String = ""
) : VoicePerceptor {

    companion object {
        private const val TAG = "OnlineVoicePerceptor"
    }

    @Volatile override var isRunning: Boolean = false
        private set

    override fun start(callback: VoicePerceptorCallback) {
        isRunning = true
        Log.i(TAG, "Online Jarvis Perceptor initialized and ready for streaming")
    }

    override fun stop() {
        isRunning = false
    }

    override fun release() {
        stop()
    }
}
