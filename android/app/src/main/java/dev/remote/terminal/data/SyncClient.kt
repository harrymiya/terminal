package dev.remote.terminal.data

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

    fun authenticate(url: String, action: String, username: String, password: String): JSONObject {
        val endpoint = URL(url.trimEnd('/') + "/v1/auth/$action")
        require(endpoint.protocol == "http" || endpoint.protocol == "https") { "同步地址必须以 http:// 或 https:// 开头" }
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val payload = JSONObject().put("username", username).put("password", password)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = JSONObject(stream.bufferedReader().use { it.readText() })
            check(code in 200..299) { response.optString("error", "登录失败（HTTP $code）") }
            return response
        } finally { connection.disconnect() }
    }

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
        val array = request(host, "GET", sessionPath(host)).getJSONArray("sessions")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    fun save(task: TerminalTask) {
        val id = task.id.replace("-", "")
        val item = JSONObject().put("id", id).put("directory_path", task.directory.path)
            .put("directory_name", task.directory.name).put("agent_name", task.agent.name)
            .put("agent_command", task.agent.command).put("display_name", task.displayName)
        request(task.host, "PUT", sessionPath(task.host) + "/$id", item)
    }

    fun delete(task: TerminalTask) { request(task.host, "DELETE", sessionPath(task.host) + "/${task.id.replace("-", "")}") }

    private fun sessionPath(host: HostProfile): String = "/v1/hosts/${workspaceKey(host)}/users/${URLEncoder.encode(host.username, "UTF-8").replace("+", "%20")}/sessions"

    private fun workspaceKey(host: HostProfile): String {
        val manualId = host.syncId.trim()
        val source = if (manualId.isNotBlank()) "manual:$manualId" else host.syncFingerprint.ifBlank { host.address.lowercase() }
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    fun hostIdentity(host: HostProfile): String = workspaceKey(host)

    fun getHostState(host: HostProfile): JSONObject = request(host, "GET", "/v1/hosts/${workspaceKey(host)}/state")

    fun updateCurrentAgent(host: HostProfile, command: String) {
        updateHostState(host) { state -> state.put("selected_agent_command", command) }
    }

    fun updateHostProfile(host: HostProfile, command: String) {
        updateHostState(host) { state ->
            state.put("name", host.name).put("icon", host.icon).put("selected_agent_command", command)
        }
    }

    private fun updateHostState(host: HostProfile, update: (JSONObject) -> Unit) {
        val path = "/v1/hosts/${workspaceKey(host)}/state"
        repeat(3) {
            val remote = request(host, "GET", path)
            val state = remote.optJSONObject("state") ?: JSONObject()
            update(state)
            runCatching {
                request(host, "PUT", path, JSONObject().put("state", state).put("base_revision", remote.optInt("revision", 0)))
            }.onSuccess { return }.onFailure { if (it.message?.contains("409") != true) throw it }
        }
    }

    fun getWorkspacesSnapshot(host: HostProfile): JSONObject = request(host, "GET", "/v1/workspaces/${workspaceKey(host)}")

    fun listWorkspaces(host: HostProfile): List<ProjectDirectory> {
        val array = getWorkspacesSnapshot(host).getJSONArray("workspaces")
        return (0 until array.length()).map { index ->
            array.getJSONObject(index).let { ProjectDirectory(it.getString("id"), it.getString("name"), it.getString("path"), it.getString("icon")) }
        }
    }

    fun hasWorkspaces(host: HostProfile): Boolean = request(host, "GET", "/v1/workspaces/${workspaceKey(host)}").optBoolean("configured", false)

    fun saveWorkspaces(host: HostProfile, directories: List<ProjectDirectory>, baseRevision: Int): JSONObject {
        val array = JSONArray()
        directories.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("path", it.path).put("icon", it.icon)) }
        return request(host, "PUT", "/v1/workspaces/${workspaceKey(host)}", JSONObject().put("workspaces", array).put("base_revision", baseRevision))
    }

    fun agentIdentity(): String = "account"
    fun agentRevisionIdentity(username: String): String = "${username.lowercase()}:${agentIdentity()}"

    fun getAgentsSnapshot(host: HostProfile): JSONObject = request(host, "GET", "/v1/agents/${agentIdentity()}")

    fun agentsFromSnapshot(snapshot: JSONObject): List<AgentPreset> {
        val array = snapshot.getJSONArray("agents")
        return (0 until array.length()).map { index ->
            array.getJSONObject(index).let { AgentPreset(it.getString("id"), it.getString("name"), it.getString("command"), it.getString("icon")) }
        }
    }

    fun listAgents(host: HostProfile): List<AgentPreset> = agentsFromSnapshot(getAgentsSnapshot(host))

    fun hasAgents(host: HostProfile): Boolean = getAgentsSnapshot(host).optBoolean("configured", false)

    fun saveAgents(host: HostProfile, agents: List<AgentPreset>, baseRevision: Int): JSONObject {
        val array = JSONArray()
        agents.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("command", it.command).put("icon", it.icon)) }
        return request(host, "PUT", "/v1/agents/${agentIdentity()}", JSONObject().put("agents", array).put("base_revision", baseRevision))
    }
}
