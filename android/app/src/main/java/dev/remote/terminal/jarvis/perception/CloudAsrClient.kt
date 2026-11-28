package dev.remote.terminal.jarvis.perception

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/**
 * 混合云端高精度语音识别客户端（OpenAI Whisper / Groq / SiliconFlow SenseVoice 兼容协议）。
 * 配合端侧 Silero VAD 切分后的干净语音段使用：
 * - 唤醒前保持 0 网络、0 功耗本地监听；
 * - 唤醒后对话阶段若已配置云端 ASR 接口，将高精增益后的语音段携带热词提示词发送至云端识别，
 *   出现网络异常或超时则毫秒级无缝回退至本地 Paraformer + 热词纠错引擎。
 */
internal object CloudAsrClient {

    fun resolveEndpoint(baseUrl: String): URI? {
        val raw = baseUrl.trim().trimEnd('/')
        if (raw.isBlank()) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.host.isNullOrBlank()) return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val full = when {
            path.endsWith("/audio/transcriptions") -> raw
            path.endsWith("/v1") -> "$raw/audio/transcriptions"
            else -> "$raw/v1/audio/transcriptions"
        }
        return runCatching { URI(full) }.getOrNull()
    }

    suspend fun transcribe(
        baseUrl: String,
        model: String,
        apiKey: String,
        samples: FloatArray,
        sampleRate: Int = 16000,
        hotwordsPrompt: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val target = resolveEndpoint(baseUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("无效的 ASR API 地址"))
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("未配置 ASR API Key"))
        }
        val wavBytes = encodeWav16BitMono(samples, sampleRate)
        val boundary = "----RdtJarvisBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val bodyBytes = buildMultipartBody(
            boundary = boundary,
            wavBytes = wavBytes,
            model = model.ifBlank { "whisper-1" },
            prompt = hotwordsPrompt
        )

        val connection = (URL(target.toASCIIString()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 3_000
            readTimeout = 6_000
            doOutput = true
            useCaches = false
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(bodyBytes.size)
        }
        try {
            connection.outputStream.use { it.write(bodyBytes) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val respText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                return@withContext Result.failure(IOException("Cloud ASR HTTP $status"))
            }
            val text = runCatching { JSONObject(respText).optString("text", "") }
                .getOrElse { respText }
                .trim()
            if (text.isBlank()) Result.failure(IOException("Cloud ASR empty text"))
            else Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildMultipartBody(
        boundary: String,
        wavBytes: ByteArray,
        model: String,
        prompt: String
    ): ByteArray {
        val out = ByteArrayOutputStream(wavBytes.size + 1024)
        val dos = DataOutputStream(out)
        fun writeField(name: String, value: String) {
            dos.writeBytes("--$boundary\r\n")
            dos.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            dos.write(value.toByteArray(Charsets.UTF_8))
            dos.writeBytes("\r\n")
        }

        writeField("model", model)
        writeField("language", "zh")
        writeField("response_format", "json")
        if (prompt.isNotBlank()) {
            writeField("prompt", prompt.take(600))
        }

        dos.writeBytes("--$boundary\r\n")
        dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n")
        dos.writeBytes("Content-Type: audio/wav\r\n\r\n")
        dos.write(wavBytes)
        dos.writeBytes("\r\n")
        dos.writeBytes("--$boundary--\r\n")
        dos.flush()
        return out.toByteArray()
    }

    private fun encodeWav16BitMono(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val dos = DataOutputStream(out)

        fun writeIntLE(v: Int) {
            dos.write(v and 0xFF)
            dos.write((v shr 8) and 0xFF)
            dos.write((v shr 16) and 0xFF)
            dos.write((v shr 24) and 0xFF)
        }

        fun writeShortLE(v: Int) {
            dos.write(v and 0xFF)
            dos.write((v shr 8) and 0xFF)
        }

        dos.writeBytes("RIFF")
        writeIntLE(36 + dataSize)
        dos.writeBytes("WAVE")
        dos.writeBytes("fmt ")
        writeIntLE(16) // PCM chunk size
        writeShortLE(1) // PCM format
        writeShortLE(1) // Mono
        writeIntLE(sampleRate)
        writeIntLE(sampleRate * 2) // ByteRate = SampleRate * NumChannels * BitsPerSample/8
        writeShortLE(2) // BlockAlign
        writeShortLE(16) // BitsPerSample
        dos.writeBytes("data")
        writeIntLE(dataSize)

        for (sample in samples) {
            val pcm = (sample.coerceIn(-1.0f, 1.0f) * 32767f).toInt().coerceIn(-32768, 32767)
            writeShortLE(pcm)
        }
        dos.flush()
        return out.toByteArray()
    }
}
