package dev.remote.terminal.jarvis.perception

import android.content.Context
import dev.remote.terminal.jarvis.api.JarvisConfig
import dev.remote.terminal.jarvis.api.VoicePerceptor

/**
 * 语音感知器可插拔构建工厂 (Factory Pattern)。
 * 根据用户配置动态构建离线模式、系统模式或在线云端感知引擎。
 */
object JarvisPerceptorFactory {
    fun createPerceptor(context: Context, config: JarvisConfig): VoicePerceptor {
        return when (config.asrEngineMode) {
            "online" -> OnlineJarvisPerceptor(context)
            else -> OfflineJarvisPerceptor(context, wakeWord = config.wakeWord)
        }
    }
}
