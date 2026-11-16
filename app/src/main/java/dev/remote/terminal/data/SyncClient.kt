package dev.remote.terminal.data

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

object SyncClient {
    val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "session-sync").apply { isDaemon = true } }.asCoroutineDispatcher()
    private fun request(host: HostProfile, method: String, path: String, payload: JSONObject? = null): JSONObject {
        val url = URL(host.syncUrl.trimEnd('/') + path)
        require(url.protocol == "http" || url.protocol == "https") { "同步地址必须以 http:// 或 https:// 开头" }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Authorization", "Bearer ${host.syncToken}")
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode in 200..299) { "同步服务返回 ${connection.responseCode}" }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    fun list(host: HostProfile): List<JSONObject> {
        val array = request(host, "GET", "/v1/sessions").getJSONArray("sessions")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    fun save(task: TerminalTask) {
        val id = task.id.replace("-", "")
        val item = JSONObject().put("id", id).put("directory_path", task.directory.path)
            .put("directory_name", task.directory.name).put("agent_name", task.agent.name)
            .put("agent_command", task.agent.command).put("display_name", task.displayName)
        request(task.host, "PUT", "/v1/sessions/$id", item)
    }

    fun delete(task: TerminalTask) { request(task.host, "DELETE", "/v1/sessions/${task.id.replace("-", "")}") }
}
