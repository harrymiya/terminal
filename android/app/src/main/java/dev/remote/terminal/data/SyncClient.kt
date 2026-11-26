package dev.remote.terminal.data

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

class SyncConflictException(val revision: Int, val body: JSONObject) : Exception("版本冲突(409)")

data class SessionSyncBatch(
    val remoteItems: List<JSONObject>,
    val completedMutationIds: Set<String>,
    val failedMutations: Int,
)

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

    private fun request(host: HostProfile?, method: String, path: String, payload: JSONObject? = null): JSONObject {
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
            val code = connection.responseCode
            if (code == 409) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val errorBody = runCatching { JSONObject(errorText) }.getOrDefault(JSONObject())
                val revision = errorBody.optInt("revision", 0)
                throw SyncConflictException(revision, errorBody)
            }
            check(code in 200..299) { "同步服务返回 $code" }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun accountRequest(method: String, path: String, payload: JSONObject? = null): JSONObject = request(null, method, path, payload)

    fun hostProfile(host: HostProfile, selectedAgentCommand: String): JSONObject {
        val aliases = legacySyncKeys(host)
        val legacy = JSONArray().apply { aliases.forEach { put(it) } }
        return JSONObject().put("id", host.id).put("name", host.name)
            .put("lan_address", host.lanAddress).put("external_address", host.externalAddress)
            .put("lan_port", host.lanPort).put("external_port", host.externalPort)
            .put("port", host.port).put("username", host.username).put("icon", host.icon)
            .put("selected_agent_command", selectedAgentCommand).put("legacy_keys", legacy)
    }

    fun listHosts(): List<JSONObject> {
        val array = accountRequest("GET", "/v1/hosts").getJSONArray("hosts")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    fun saveHostProfile(host: HostProfile, selectedAgentCommand: String): JSONObject {
        val payload = hostProfile(host, selectedAgentCommand)
        return accountRequest("PUT", "/v1/hosts/${host.id}", payload)
    }

    fun deleteHostProfile(hostId: String) { accountRequest("DELETE", "/v1/hosts/$hostId") }

    private fun legacySyncKeys(host: HostProfile): List<String> {
        val sources = buildList {
            if (host.syncId.isNotBlank()) add("manual:${host.syncId.trim()}")
            if (host.syncFingerprint.isNotBlank()) add(host.syncFingerprint)
            if (host.legacyAddress.isNotBlank()) add(host.legacyAddress.lowercase())
            if (host.address.isNotBlank()) add(host.address.lowercase())
            if (host.lanAddress.isNotBlank()) add(host.lanAddress.lowercase())
            if (host.externalAddress.isNotBlank()) add(host.externalAddress.lowercase())
        }
        return sources.map { source -> MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) } }.distinct()
    }

    fun matchesLegacyHost(host: HostProfile, profile: JSONObject): Boolean {
        val aliases = profile.optJSONArray("legacy_keys") ?: return false
        val remote = (0 until aliases.length()).map { aliases.optString(it) }.toSet()
        return legacySyncKeys(host).any { it in remote }
    }

    fun list(host: HostProfile): List<JSONObject> {
        val array = request(host, "GET", sessionPath(host)).getJSONArray("sessions")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    fun syncSessions(host: HostProfile, pending: List<JSONObject>): SessionSyncBatch {
        val remoteItems = list(host)
        val completed = mutableSetOf<String>()
        var failures = 0
        pending.forEach { mutation ->
            val sessionId = mutation.optString("session_id").replace("-", "")
            val mutationId = mutation.optString("mutation_id")
            try {
                when (mutation.optString("method")) {
                    "PUT" -> {
                        val item = mutation.optJSONObject("item") ?: error("missing session payload")
                        request(host, "PUT", sessionPath(host) + "/$sessionId", item)
                    }
                    "DELETE" -> request(host, "DELETE", sessionPath(host) + "/$sessionId")
                    else -> error("invalid session mutation")
                }
                if (mutationId.isNotBlank()) completed.add(mutationId)
            } catch (_: Exception) {
                failures += 1
            }
        }
        return SessionSyncBatch(remoteItems, completed, failures)
    }

    fun mergeSessionItems(remoteItems: List<JSONObject>, mutations: List<JSONObject>): List<JSONObject> {
        val merged = linkedMapOf<String, JSONObject>()
        remoteItems.forEach { item -> item.optString("id").takeIf(String::isNotBlank)?.let { merged[it] = item } }
        mutations.forEach { mutation ->
            val id = mutation.optString("session_id")
            if (id.isBlank()) return@forEach
            when (mutation.optString("method")) {
                "DELETE" -> merged.remove(id)
                "PUT" -> mutation.optJSONObject("item")?.let { merged[id] = it }
            }
        }
        val byWorkspace = linkedMapOf<Pair<String, String>, JSONObject>()
        merged.values.forEach { item ->
            val dir = item.optString("directory_path")
            val cmd = item.optString("agent_command")
            val key = if (dir.isNotBlank() && cmd.isNotBlank()) dir to cmd else "id" to item.optString("id")
            byWorkspace.remove(key)
            byWorkspace[key] = item
        }
        return byWorkspace.values.toList()
    }

    fun save(task: TerminalTask) {
        val id = task.id.replace("-", "")
        val item = sessionItem(task)
        request(task.host, "PUT", sessionPath(task.host) + "/$id", item)
    }

    fun sessionItem(task: TerminalTask): JSONObject = JSONObject().put("id", task.id.replace("-", ""))
        .put("directory_path", task.directory.path).put("directory_name", task.directory.name)
        .put("agent_name", task.agent.name).put("agent_command", task.agent.command)
        .put("display_name", task.displayName).put("title_source", task.titleSource)

    fun delete(task: TerminalTask) { request(task.host, "DELETE", sessionPath(task.host) + "/${task.id.replace("-", "")}") }

    private fun sessionPath(host: HostProfile): String = "/v1/hosts/${workspaceKey(host)}/users/${URLEncoder.encode(host.username, "UTF-8").replace("+", "%20")}/sessions"

    private fun workspaceKey(host: HostProfile): String {
        return host.id
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
        var revision = baseRevision
        var lastException: Exception? = null
        for (attempt in 0 until 3) {
            try {
                return request(host, "PUT", "/v1/workspaces/${workspaceKey(host)}", JSONObject().put("workspaces", array).put("base_revision", revision))
            } catch (e: SyncConflictException) {
                lastException = e
                revision = e.revision
            }
        }
        throw lastException ?: IllegalStateException("工作区保存失败")
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
        var revision = baseRevision
        var lastException: Exception? = null
        for (attempt in 0 until 3) {
            try {
                return request(host, "PUT", "/v1/agents/${agentIdentity()}", JSONObject().put("agents", array).put("base_revision", revision))
            } catch (e: SyncConflictException) {
                lastException = e
                revision = e.revision
            }
        }
        throw lastException ?: IllegalStateException("Agent 保存失败")
    }

    fun operationLogJson(entry: OperationLogEntry): JSONObject = JSONObject()
        .put("id", entry.id)
        .put("created_at", entry.createdAt)
        .put("updated_at", entry.updatedAt)
        .put("host_id", entry.hostId)
        .put("host_name", entry.hostName)
        .put("ssh_username", entry.sshUsername)
        .put("directory_id", entry.directoryId)
        .put("directory_name", entry.directoryName)
        .put("directory_path", entry.directoryPath)
        .put("agent_id", entry.agentId)
        .put("agent_name", entry.agentName)
        .put("agent_command", entry.agentCommand)
        .put("session_id", entry.sessionId)
        .put("session_name", entry.sessionName)
        .put("title_source", entry.titleSource)
        .put("client", entry.client)
        .put("action", entry.action)
        .put("agent_icon", entry.agentIcon)

    fun operationLogFromJson(obj: JSONObject): OperationLogEntry = OperationLogEntry(
        id = obj.getString("id"),
        createdAt = obj.getLong("created_at"),
        updatedAt = obj.optLong("updated_at", obj.getLong("created_at")),
        hostId = obj.optString("host_id", ""),
        hostName = obj.optString("host_name", ""),
        sshUsername = obj.optString("ssh_username", ""),
        directoryId = obj.optString("directory_id", ""),
        directoryName = obj.optString("directory_name", ""),
        directoryPath = obj.optString("directory_path", ""),
        agentId = obj.optString("agent_id", ""),
        agentName = obj.optString("agent_name", ""),
        agentCommand = obj.optString("agent_command", ""),
        sessionId = obj.optString("session_id", ""),
        sessionName = obj.optString("session_name", ""),
        titleSource = obj.optString("title_source", "default"),
        client = obj.optString("client", ""),
        action = obj.optString("action", "open"),
        agentIcon = obj.optString("agent_icon", ""),
    )

    fun fetchOperationLogs(limit: Int = 200): List<OperationLogEntry> {
        val array = accountRequest("GET", "/v1/operation-logs?limit=$limit").getJSONArray("logs")
        return (0 until array.length()).map { operationLogFromJson(array.getJSONObject(it)) }
    }

    fun pushOperationLogs(logs: List<OperationLogEntry>): List<OperationLogEntry> {
        val array = JSONArray()
        logs.forEach { array.put(operationLogJson(it)) }
        val response = accountRequest("POST", "/v1/operation-logs", JSONObject().put("logs", array))
        val result = response.optJSONArray("logs") ?: return logs
        return (0 until result.length()).map { operationLogFromJson(result.getJSONObject(it)) }
    }
}
