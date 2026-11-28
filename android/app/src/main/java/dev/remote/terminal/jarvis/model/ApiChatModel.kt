package dev.remote.terminal.jarvis.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal const val DEFAULT_MODEL_API_URL = "https://api.deepseek.com"
internal const val DEFAULT_MODEL_API_NAME = "deepseek-flash"

internal object ApiChatModel {
    fun endpoint(baseUrl: String): URI {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", true) && uri.host != null &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
        ) { "请输入有效的 HTTPS API 地址，例如 $DEFAULT_MODEL_API_URL。" }
        val path = uri.rawPath.trimEnd('/')
        val base = uri.toASCIIString().trimEnd('/')
        return URI(if (path.endsWith("/chat/completions")) base else "$base/chat/completions")
    }

    suspend fun complete(
        baseUrl: String,
        model: String,
        apiKey: String,
        prompt: String,
        connectionTest: Boolean = false
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                require(model.isNotBlank()) { "请填写 API 模型名称。" }
                require(apiKey.isNotBlank() && apiKey.none { it.isWhitespace() }) { "请填写有效的 API Key。" }
                val target = endpoint(baseUrl)
                val connection = target.toURL().openConnection() as HttpsURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = if (connectionTest) 6_000 else 4_000
                    connection.readTimeout = if (connectionTest) 12_000 else 14_000
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Authorization", "Bearer $apiKey")
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("Accept", "application/json")
                    val body = JSONObject()
                        .put("model", model)
                        .put("stream", false)
                        .put("max_tokens", if (connectionTest) 64 else 384)
                        .put("response_format", JSONObject().put("type", "json_object"))
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                    if (target.host.equals("api.deepseek.com", true)) {
                        body.put("thinking", JSONObject().put("type", "disabled"))
                    } else if (target.host.contains("siliconflow", true)) {
                        body.put("enable_thinking", false)
                    }
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }

                    val status = connection.responseCode
                    if (status !in 200..299) throw IOException(statusMessage(status))
                    val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val choice = JSONObject(response).optJSONArray("choices")?.optJSONObject(0)
                    val content = choice?.optJSONObject("message")?.opt("content") as? String
                    Result.success(content.orEmpty())
                } finally {
                    connection.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                Result.failure(IOException("模型 API 响应超时，请稍后再试。"))
            } catch (e: IOException) {
                Result.failure(IOException(e.message?.takeIf { it.startsWith("模型 API") }
                    ?: "无法连接模型 API，请检查网络和接口地址。"))
            } catch (e: IllegalArgumentException) {
                Result.failure(e)
            } catch (_: Exception) {
                Result.failure(IOException("模型 API 返回了无法识别的响应。"))
            }
        }

    private fun statusMessage(status: Int): String = when (status) {
        401, 403 -> "模型 API 拒绝了认证，请检查 API Key 和访问权限（HTTP $status）。"
        402 -> "模型 API 余额不足（HTTP 402）。"
        429 -> "模型 API 请求过于频繁，请稍后再试（HTTP 429）。"
        400, 404, 422 -> "模型 API 无法处理请求，请检查接口地址、模型名称和 JSON 输出支持（HTTP $status）。"
        else -> "模型 API 请求失败（HTTP $status）。"
    }
}
