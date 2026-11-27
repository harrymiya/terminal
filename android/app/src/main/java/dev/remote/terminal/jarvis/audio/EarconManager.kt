package dev.remote.terminal.jarvis.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.ToneGenerator
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * 钢铁侠贾维斯式短促科技提示音 (Earcons) 生成器。
 * 完全在内存中实时合成 PCM 音频并通过 AudioTrack 极速输出，零磁盘 IO、零延迟。
 */
class EarconManager {

    companion object {
        private const val TAG = "JarvisEarcons"
        private const val SAMPLE_RATE = 44100
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * 唤醒确认音 (Wake Ack): 双升调短促蜂鸣 (880Hz -> 1320Hz, 120ms)
     * 犹如钢铁侠面罩闭合或系统就绪的清脆科技音。
     */
    fun playWakeChime() {
        scope.launch {
            playTones(
                Tone(frequency = 880.0, durationMs = 60, volume = 0.85f),
                Tone(frequency = 1320.0, durationMs = 80, volume = 0.95f)
            )
        }
    }

    /**
     * 思考中提示音 (Thinking Cue): 低频微弱短脉冲 (600Hz, 70ms)
     */
    fun playThinkingChime() {
        scope.launch {
            playTones(Tone(frequency = 600.0, durationMs = 70, volume = 0.80f))
        }
    }

    /**
     * 动作下发/成功执行音 (Success Chime): 欢快科技三连音 (784Hz -> 988Hz -> 1318Hz, 150ms)
     */
    fun playActionSuccessChime() {
        scope.launch {
            playTones(
                Tone(frequency = 784.0, durationMs = 50, volume = 0.85f),
                Tone(frequency = 988.0, durationMs = 50, volume = 0.90f),
                Tone(frequency = 1318.0, durationMs = 70, volume = 0.95f)
            )
        }
    }

    /**
     * 警报/拦截询问音 (Alert Question): 略带警示的降调提示 (988Hz -> 659Hz, 180ms)
     */
    fun playAlertChime() {
        scope.launch {
            playTones(
                Tone(frequency = 988.0, durationMs = 90, volume = 0.90f),
                Tone(frequency = 659.0, durationMs = 110, volume = 0.85f)
            )
        }
    }

    private data class Tone(val frequency: Double, val durationMs: Int, val volume: Float)

    private fun playTones(vararg tones: Tone) {
        try {
            val totalSamples = tones.sumOf { (it.durationMs * SAMPLE_RATE) / 1000 }
            val buffer = ShortArray(totalSamples)
            var offset = 0

            for (tone in tones) {
                val samplesForTone = (tone.durationMs * SAMPLE_RATE) / 1000
                for (i in 0 until samplesForTone) {
                    val angle = 2.0 * Math.PI * i / (SAMPLE_RATE / tone.frequency)
                    // 施加简单的正弦淡入淡出（Envelope），避免爆音
                    val envelope = when {
                        i < 100 -> i / 100.0
                        i > samplesForTone - 100 -> (samplesForTone - i) / 100.0
                        else -> 1.0
                    }
                    val sample = (sin(angle) * Short.MAX_VALUE * tone.volume * envelope).toInt()
                    buffer[offset++] = sample.toShort()
                }
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(buffer, 0, buffer.size)
            track.play()
            // 自动释放
            Thread.sleep(tones.sumOf { it.durationMs.toLong() } + 50)
            track.stop()
            track.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to synthesize earcon via AudioTrack: ${e.message}")
        }
    }
}
