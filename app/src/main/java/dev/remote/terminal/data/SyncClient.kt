package dev.remote.terminal.data

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

object SyncClient {
    val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "session-sync").apply { isDaemon = true } }.asCoroutineDispatcher()
    @Volatile private var syncUrl: String = ""
    @Volatile private var syncToken: String = ""

    fun configure(url: String, token: String) { syncUrl = url; syncToken = token }

    private fun request(host: HostProfile, method: String, path: String, payload: JSONObject? = null): JSONObject {
        val url = URL(syncUrl.trimEnd('/') + path)
        require(url.protocol == "http" || url.protocol == "https") { "同步地址必须以 http:// 或 https:// 开头" }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Authorization", "Bearer $syncToken")
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

    private fun workspaceKey(host: HostProfile): String {
        val source = "${host.username}@${host.address.lowercase()}:${host.port}"
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun listWorkspaces(host: HostProfile): List<ProjectDirectory> {
        val array = request(host, "GET", "/v1/workspaces/${workspaceKey(host)}").getJSONArray("workspaces")
        return (0 until array.length()).map { index ->
            array.getJSONObject(index).let { ProjectDirectory(it.getString("id"), it.getString("name"), it.getString("path"), it.getString("icon")) }
        }
    }

    fun hasWorkspaces(host: HostProfile): Boolean = request(host, "GET", "/v1/workspaces/${workspaceKey(host)}").optBoolean("configured", false)

    fun saveWorkspaces(host: HostProfile, directories: List<ProjectDirectory>) {
        val array = JSONArray()
        directories.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("path", it.path).put("icon", it.icon)) }
        request(host, "PUT", "/v1/workspaces/${workspaceKey(host)}", JSONObject().put("workspaces", array))
    }

    fun listAgents(host: HostProfile): List<AgentPreset> {
        val array = request(host, "GET", "/v1/agents/global").getJSONArray("agents")
        return (0 until array.length()).map { index ->
            array.getJSONObject(index).let { AgentPreset(it.getString("id"), it.getString("name"), it.getString("command"), it.getString("icon")) }
        }
    }

    fun hasAgents(host: HostProfile): Boolean = request(host, "GET", "/v1/agents/global").optBoolean("configured", false)

    fun saveAgents(host: HostProfile, agents: List<AgentPreset>) {
        val array = JSONArray()
        agents.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("command", it.command).put("icon", it.icon)) }
        request(host, "PUT", "/v1/agents/global", JSONObject().put("agents", array))
    }
}
