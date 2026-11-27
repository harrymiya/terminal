package dev.remote.terminal.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ProjectDirectory(
    val id: String,
    val name: String,
    val path: String,
    val icon: String = "📁",
)

data class OperationLogEntry(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val hostId: String,
    val hostName: String,
    val sshUsername: String,
    val directoryId: String,
    val directoryName: String,
    val directoryPath: String,
    val agentId: String,
    val agentName: String,
    val agentCommand: String,
    val sessionId: String,
    val sessionName: String,
    val client: String = "android",
    val action: String = "open",
    val account: String = "",
    val agentIcon: String = "",
    val titleSource: String = "default",
)

data class HostProfile(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val username: String,
    val password: String = "",
    val icon: String = "⌘",
    val directories: List<ProjectDirectory> = emptyList(),
    val syncFingerprint: String = "",
    val syncId: String = "",
    val lanAddress: String = "",
    val externalAddress: String = address,
    val legacyAddress: String = address,
    val lanPort: Int = port,
    val externalPort: Int = port,
    val agents: List<AgentPreset>? = null,
    val selectedAgent: String? = null,
    val selectedAgentCommand: String? = null,
)

data class HostEndpoint(
    val address: String,
    val port: Int,
    val isLan: Boolean,
)

fun HostProfile.endpoints(): List<HostEndpoint> {
    val list = mutableListOf<HostEndpoint>()
    if (lanAddress.isNotBlank() && lanPort in 1..65535) {
        list.add(HostEndpoint(lanAddress.trim(), lanPort, isLan = true))
    }
    if (externalAddress.isNotBlank() && externalPort in 1..65535) {
        list.add(HostEndpoint(externalAddress.trim(), externalPort, isLan = false))
    }
    if (list.isEmpty() && lanAddress.isBlank() && externalAddress.isBlank() && address.isNotBlank() && port in 1..65535) {
        list.add(HostEndpoint(address.trim(), port, isLan = false))
    }
    return list.distinctBy { it.address to it.port }
}

fun HostProfile.hasValidConfiguredPorts(): Boolean {
    val hasLan = lanAddress.isNotBlank()
    val hasExternal = externalAddress.isNotBlank()
    if (!hasLan && !hasExternal) return address.isNotBlank() && port in 1..65535
    return (!hasLan || lanPort in 1..65535) && (!hasExternal || externalPort in 1..65535)
}

data class HostRoute(
    val address: String,
    val port: Int,
    val isLan: Boolean,
    val reachable: Boolean = true,
)

object HostRouteResolver {
    private val activeRoutes = androidx.compose.runtime.mutableStateMapOf<String, HostRoute>()
    private val resolvedHostIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun getRoute(hostId: String): HostRoute? = activeRoutes[hostId]

    fun updateRoute(hostId: String, route: HostRoute) {
        activeRoutes[hostId] = route
        resolvedHostIds.add(hostId)
    }

    fun invalidate(hostId: String) {
        resolvedHostIds.remove(hostId)
    }

    fun isPortReachable(address: String, port: Int, timeoutMs: Int): Boolean {
        if (address.isBlank() || port !in 1..65535) return false
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(address, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun resolveHostRoute(host: HostProfile, force: Boolean = false): HostRoute = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!force && resolvedHostIds.contains(host.id)) {
            activeRoutes[host.id]?.let { return@withContext it }
        }
        val candidates = host.endpoints()
        for (ep in candidates) {
            val timeout = if (ep.isLan) 1200 else 1800
            if (isPortReachable(ep.address, ep.port, timeout)) {
                val route = HostRoute(ep.address, ep.port, ep.isLan, reachable = true)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    activeRoutes[host.id] = route
                    resolvedHostIds.add(host.id)
                }
                return@withContext route
            }
        }
        val fallback = candidates.firstOrNull() ?: HostEndpoint("", 0, false)
        val route = HostRoute(fallback.address, fallback.port, fallback.isLan, reachable = false)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            activeRoutes[host.id] = route
            resolvedHostIds.add(host.id)
        }
        route
    }
}

data class AgentPreset(
    val id: String,
    val name: String,
    val command: String,
    val icon: String,
)

fun hostAgents(host: HostProfile?, globalAgents: List<AgentPreset>): List<AgentPreset> {
    if (host != null && host.agents != null && host.agents.isNotEmpty()) {
        return host.agents
    }
    return globalAgents
}

fun hostSelectedAgent(host: HostProfile?, availableAgents: List<AgentPreset>, store: LocalStore? = null): AgentPreset? {
    if (availableAgents.isEmpty()) return null
    if (host == null) return availableAgents.firstOrNull()

    val storedId = store?.loadHostSelectedAgent(host.id).orEmpty()
    if (storedId.isNotBlank()) {
        availableAgents.firstOrNull { it.id == storedId }?.let { return it }
    }

    if (!host.selectedAgent.isNullOrBlank()) {
        availableAgents.firstOrNull { it.id == host.selectedAgent }?.let { return it }
        val idx = host.selectedAgent.toIntOrNull()
        if (idx != null && idx in availableAgents.indices) {
            return availableAgents[idx]
        }
    }

    if (!host.selectedAgentCommand.isNullOrBlank()) {
        availableAgents.firstOrNull { it.command == host.selectedAgentCommand }?.let { return it }
    }

    return availableAgents.firstOrNull()
}

/** Returns the configured Codex CLI executable, including compatible codex2 builds. */
fun codexExecutable(command: String): String? {
    val executable = agentExecutable(command) ?: return null
    return executable.takeIf { it.substringAfterLast('/') in setOf("codex", "codex2", "codx") }
}

/** Returns the configured Antigravity CLI executable, including compatible agy2 builds. */
fun antigravityExecutable(command: String): String? {
    val executable = agentExecutable(command) ?: return null
    return executable.takeIf { it.substringAfterLast('/') == "agy" || it.substringAfterLast('/') == "agy2" }
}

private val AgentExecutablePattern = Regex("""^\s*(?:"[^"]+"|'[^']+'|[^\s"']+)(?=\s|$)""")

private fun agentExecutable(command: String): String? =
    AgentExecutablePattern.find(command)?.value?.trimStart()?.removeSurrounding("\"")?.removeSurrounding("'")

fun quotaExecutable(command: String): String? = codexExecutable(command) ?: antigravityExecutable(command)

data class QuotaWindow(val minutes: Int, val remainingPercent: Int, val resetsAt: Long? = null)

data class TerminalTask(
    val id: String,
    val host: HostProfile,
    val directory: ProjectDirectory,
    val agent: AgentPreset,
    val displayName: String,
    val titleSource: String = "default",
)

class LocalStore(context: Context) {
    companion object {
        private const val DEFAULT_SYNC_URL = "http://114.55.232.229:8080"
    }

    private val preferences = context.getSharedPreferences("remote_dev_terminal", Context.MODE_PRIVATE)
    private val vault = PasswordVault()

    fun sshPublicKey(): String? = preferences.getString("ssh_public_key", null)

    fun loadDirtyWorkspaces(): Set<String> = preferences.getStringSet("dirty_workspaces", emptySet())?.toSet().orEmpty()

    fun saveDirtyWorkspaces(hostIds: Set<String>) {
        preferences.edit().putStringSet("dirty_workspaces", hostIds.toSet()).apply()
    }

    fun loadDirtyHostProfiles(): Set<String> = preferences.getStringSet("dirty_host_profiles", emptySet())?.toSet().orEmpty()

    fun saveDirtyHostProfiles(hostIds: Set<String>) {
        preferences.edit().putStringSet("dirty_host_profiles", hostIds.toSet()).apply()
    }

    fun loadHostSelectedAgent(hostId: String): String = preferences.getString("host_selected_agent_$hostId", "").orEmpty()

    fun saveHostSelectedAgent(hostId: String, agentId: String) {
        preferences.edit().putString("host_selected_agent_$hostId", agentId).apply()
    }

    fun loadWorkspaceRevision(identity: String): Int = preferences.getInt("workspace_revision_$identity", -1)
    fun saveWorkspaceRevision(identity: String, revision: Int) {
        preferences.edit().putInt("workspace_revision_$identity", revision).apply()
    }

    fun loadAgentRevision(identity: String): Int = preferences.getInt("agent_revision_$identity", -1)
    fun saveAgentRevision(identity: String, revision: Int) {
        preferences.edit().putInt("agent_revision_$identity", revision).apply()
    }

    fun loadSyncUrl(): String {
        preferences.getString("sync_url", null)?.let { return it }
        val legacy = runCatching { JSONArray(preferences.getString("hosts", "[]")) }.getOrNull()
        val value = (0 until (legacy?.length() ?: 0)).asSequence().mapNotNull { legacy?.optJSONObject(it)?.optString("sync_url") }.firstOrNull { it.isNotBlank() }
            ?: DEFAULT_SYNC_URL
        preferences.edit().putString("sync_url", value).apply()
        return value
    }
    fun loadSyncToken(): String {
        preferences.getString("sync_token", null)?.let { return vault.decrypt(it) }
        val ids = runCatching { JSONArray(preferences.getString("hosts", "[]")) }.getOrNull()
        val legacy = (0 until (ids?.length() ?: 0)).asSequence().mapNotNull { ids?.optJSONObject(it)?.optString("id") }
            .map { vault.decrypt(preferences.getString("sync_token_$it", null)) }.firstOrNull { it.isNotBlank() }.orEmpty()
        if (legacy.isNotBlank()) preferences.edit().putString("sync_token", vault.encrypt(legacy)).apply()
        return legacy
    }
    fun saveSyncSettings(url: String, token: String) {
        preferences.edit().putString("sync_url", url.trim()).putString("sync_token", vault.encrypt(token)).apply()
    }

    fun loadAutoSyncEnabled(): Boolean = preferences.getBoolean("auto_sync_enabled", false)
    fun saveAutoSyncEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("auto_sync_enabled", enabled).apply()
    }

    fun loadOfflineMode(): Boolean = preferences.getBoolean("offline_mode", false)
    fun saveOfflineMode(enabled: Boolean) {
        preferences.edit().putBoolean("offline_mode", enabled).apply()
    }

    fun loadSyncIntervalSeconds(): Int = preferences.getInt("sync_interval_seconds", 5).coerceIn(5, 300)
    fun saveSyncIntervalSeconds(seconds: Int) {
        preferences.edit().putInt("sync_interval_seconds", seconds.coerceIn(5, 300)).apply()
    }

    @Synchronized
    fun enqueueSessionSync(task: TerminalTask, method: String, item: JSONObject? = null) {
        require(method == "PUT" || method == "DELETE")
        val current = runCatching { JSONArray(preferences.getString("pending_session_sync", "[]")) }.getOrDefault(JSONArray())
        val account = loadSyncUsername().trim().lowercase()
        val sessionId = task.id.replace("-", "")
        val retained = (0 until current.length()).mapNotNull { current.optJSONObject(it) }
            .filterNot { pending ->
                pending.optString("host_id") == task.host.id &&
                    pending.optString("username") == task.host.username &&
                    pending.optString("session_id") == sessionId &&
                    pending.optString("account").lowercase() == account
            }
        val updated = JSONArray()
        retained.forEach { updated.put(it) }
        updated.put(JSONObject()
            .put("mutation_id", java.util.UUID.randomUUID().toString())
            .put("account", account)
            .put("host_id", task.host.id)
            .put("username", task.host.username)
            .put("session_id", sessionId)
            .put("method", method)
            .put("item", item))
        check(preferences.edit().putString("pending_session_sync", updated.toString()).commit()) { "无法保存待同步会话变更" }
    }

    @Synchronized
    fun loadPendingSessionSync(accountName: String, host: HostProfile): List<JSONObject> {
        val account = accountName.trim().lowercase()
        val current = runCatching { JSONArray(preferences.getString("pending_session_sync", "[]")) }.getOrDefault(JSONArray())
        return (0 until current.length()).mapNotNull { current.optJSONObject(it) }
            .filter { pending ->
                pending.optString("host_id") == host.id &&
                    pending.optString("username") == host.username &&
                    pending.optString("account").lowercase() in setOf("", account)
            }
            .map { JSONObject(it.toString()) }
    }

    @Synchronized
    fun clearPendingSessionSync(mutationIds: Set<String>) {
        if (mutationIds.isEmpty()) return
        val current = runCatching { JSONArray(preferences.getString("pending_session_sync", "[]")) }.getOrDefault(JSONArray())
        val updated = JSONArray()
        var changed = false
        for (index in 0 until current.length()) {
            val item = current.optJSONObject(index) ?: continue
            if (item.optString("mutation_id") in mutationIds) changed = true else updated.put(item)
        }
        if (changed) check(preferences.edit().putString("pending_session_sync", updated.toString()).commit()) { "无法清理已同步会话变更" }
    }

    @Synchronized
    fun remapPendingSessionSyncHostIds(remaps: Map<String, String>) {
        if (remaps.isEmpty()) return
        val current = runCatching { JSONArray(preferences.getString("pending_session_sync", "[]")) }.getOrDefault(JSONArray())
        val updated = JSONArray()
        var changed = false
        for (index in 0 until current.length()) {
            val item = current.optJSONObject(index) ?: continue
            val oldHostId = item.optString("host_id")
            val canonicalId = remaps[oldHostId]
            if (canonicalId != null && canonicalId != oldHostId) {
                item.put("host_id", canonicalId)
                changed = true
            }
            updated.put(item)
        }
        if (changed) check(preferences.edit().putString("pending_session_sync", updated.toString()).commit()) { "无法更新待同步会话主机标识" }
    }

    fun loadSyncUsername(): String = preferences.getString("sync_username", "").orEmpty()
    fun loadSyncPassword(): String = vault.decrypt(preferences.getString("sync_password", null))
    fun saveSyncCredentials(username: String, password: String, remember: Boolean) {
        val editor = preferences.edit().putString("sync_username", username.trim())
        if (remember) editor.putString("sync_password", vault.encrypt(password)) else editor.remove("sync_password")
        editor.apply()
    }

    @Synchronized
    fun generateSshKey(): String {
        sshPublicKey()?.let { return it }
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        try {
            val privateBytes = ByteArrayOutputStream().also { pair.writePrivateKey(it) }.toByteArray()
            val publicBytes = ByteArrayOutputStream().also { pair.writePublicKey(it, "remote-dev-terminal") }.toByteArray()
            val publicKey = String(publicBytes, Charsets.UTF_8).trim()
            val saved = preferences.edit().putString("ssh_private_key", vault.encrypt(String(privateBytes, Charsets.UTF_8)))
                .putString("ssh_public_key", publicKey).commit()
            check(saved) { "无法保存 SSH 密钥" }
            privateBytes.fill(0)
            return publicKey
        } finally {
            pair.dispose()
        }
    }

    fun addSshIdentity(jsch: JSch) {
        val encrypted = preferences.getString("ssh_private_key", null) ?: return
        val privateKey = vault.decrypt(encrypted)
        check(privateKey.isNotEmpty()) { "无法读取本机 SSH 私钥" }
        jsch.addIdentity("remote-dev-terminal", privateKey.toByteArray(Charsets.UTF_8), null, null)
    }

    fun loadHosts(): List<HostProfile> = runCatching {
        val items = JSONArray(preferences.getString("hosts", "[]"))
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val dirs = item.optJSONArray("directories") ?: JSONArray()
            val directories = (0 until dirs.length()).map { n ->
                dirs.getJSONObject(n).let { ProjectDirectory(it.getString("id"), it.getString("name"), it.getString("path"), it.optString("icon", "📁")) }
            }
            val ags = item.optJSONArray("agents")
            val hostAgents = ags?.let { arr ->
                (0 until arr.length()).mapNotNull { n ->
                    arr.optJSONObject(n)?.let { a ->
                        AgentPreset(
                            id = a.optString("id", a.optString("name")),
                            name = a.optString("name"),
                            command = a.optString("command"),
                            icon = a.optString("icon", "✳"),
                        )
                    }
                }
            }
            val port = item.optInt("port", 0)
            val lanPort = if (item.has("lan_port") && !item.isNull("lan_port")) item.optInt("lan_port", port) else port
            val externalPort = if (item.has("external_port") && !item.isNull("external_port")) item.optInt("external_port", port) else port
            val selectedAgent = item.optString("selected_agent").takeIf { it.isNotBlank() }
                ?: item.optString("selected_agent_id").takeIf { it.isNotBlank() }
            val selectedAgentCommand = item.optString("selected_agent_command").takeIf { it.isNotBlank() }
            HostProfile(
                id = item.getString("id"), name = item.getString("name"), address = item.optString("external_address", item.getString("address")),
                port = port, username = item.getString("username"),
                password = vault.decrypt(preferences.getString("password_${item.getString("id")}", null)),
                icon = item.optString("icon", "⌘"), directories = directories,
                syncFingerprint = item.optString("sync_fingerprint", ""),
                syncId = item.optString("sync_id", ""),
                lanAddress = item.optString("lan_address", ""),
                externalAddress = item.optString("external_address", item.optString("address", "")),
                legacyAddress = item.optString("legacy_address", item.optString("address", "")),
                lanPort = lanPort,
                externalPort = externalPort,
                agents = hostAgents,
                selectedAgent = selectedAgent,
                selectedAgentCommand = selectedAgentCommand,
            )
        }
    }.getOrDefault(emptyList())

    fun loadHostPassword(hostId: String): String = runCatching {
        vault.decrypt(preferences.getString("password_$hostId", null))
    }.getOrDefault("")

    fun removeHostPassword(hostId: String) {
        preferences.edit().remove("password_$hostId").apply()
    }

    fun saveHosts(hosts: List<HostProfile>) {
        val existingPasswords = loadHosts().associate { it.id to it.password }
        val root = JSONArray()
        hosts.forEach { host ->
            val dirs = JSONArray()
            host.directories.forEach { dirs.put(JSONObject().put("id", it.id).put("name", it.name).put("path", it.path).put("icon", it.icon)) }
            val hostObj = JSONObject().put("id", host.id).put("name", host.name).put("address", host.externalAddress.ifBlank { host.lanAddress })
                .put("port", host.port).put("lan_port", host.lanPort).put("external_port", host.externalPort)
                .put("username", host.username).put("icon", host.icon).put("directories", dirs)
                .put("sync_fingerprint", host.syncFingerprint).put("sync_id", host.syncId.trim())
                .put("lan_address", host.lanAddress).put("external_address", host.externalAddress).put("legacy_address", host.legacyAddress)
            if (host.agents != null) {
                val agsArray = JSONArray()
                host.agents.forEach { ag ->
                    agsArray.put(JSONObject().put("id", ag.id).put("name", ag.name).put("command", ag.command).put("icon", ag.icon))
                }
                hostObj.put("agents", agsArray)
            }
            host.selectedAgent?.let { hostObj.put("selected_agent", it) }
            host.selectedAgentCommand?.let { hostObj.put("selected_agent_command", it) }
            root.put(hostObj)
            val effectivePassword = host.password.ifBlank { existingPasswords[host.id].orEmpty().ifBlank { loadHostPassword(host.id) } }
            if (effectivePassword.isNotBlank()) {
                preferences.edit().putString("password_${host.id}", vault.encrypt(effectivePassword)).apply()
            }
        }
        if (hosts.isNotEmpty()) {
            (existingPasswords.keys - hosts.map { it.id }.toSet()).forEach { preferences.edit().remove("password_$it").apply() }
        }
        preferences.edit().putString("hosts", root.toString()).apply()
    }

    fun loadAgents(): List<AgentPreset> {
        var agents = runCatching {
            val items = JSONArray(preferences.getString("agents", null) ?: "")
            (0 until items.length()).map { items.getJSONObject(it).let { a -> AgentPreset(a.getString("id"), a.getString("name"), a.getString("command"), a.optString("icon", "✳")) } }
        }.getOrElse {
            listOf(
                AgentPreset("codex", "Codex", "codex", "✳"),
                AgentPreset("opencode", "OpenCode", "opencode", "⌘"),
                AgentPreset("antigravity", "Antigravity", "agy", "🚀"),
                AgentPreset("shell", "Shell", "bash -l", "brand:shell"),
            )
        }
        val addAntigravity = !preferences.getBoolean("antigravity_preset_migrated", false)
        val removeDefaultClaude = !preferences.getBoolean("claude_default_removed", false)
        if (addAntigravity || removeDefaultClaude) {
            if (addAntigravity && agents.none { it.id == "antigravity" || it.command.trim() == "agy" }) {
                agents = agents + AgentPreset("antigravity", "Antigravity", "agy", "🚀")
            }
            if (removeDefaultClaude) {
                agents = agents.filterNot { it == AgentPreset("claude", "Claude Code", "claude", "◈") }
            }
            saveAgents(agents)
            preferences.edit().putBoolean("antigravity_preset_migrated", true)
                .putBoolean("claude_default_removed", true).apply()
        }
        return agents
    }

    fun saveAgents(agents: List<AgentPreset>) {
        val array = JSONArray()
        agents.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("command", it.command).put("icon", it.icon)) }
        preferences.edit().putString("agents", array.toString()).apply()
    }

    fun loadAutoEnterEnabled(): Boolean = preferences.getBoolean("auto_enter_enabled", false)

    fun saveAutoEnterEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("auto_enter_enabled", enabled).apply()
    }

    fun loadAutoEnterDelaySeconds(): Int = preferences.getInt("auto_enter_delay_seconds", 3).coerceIn(1, 30)

    fun saveAutoEnterDelaySeconds(seconds: Int) {
        preferences.edit().putInt("auto_enter_delay_seconds", seconds.coerceIn(1, 30)).apply()
    }

    fun loadQuotaIntervalSeconds(): Int = preferences.getInt("agent_quota_interval_seconds", 60).coerceIn(15, 300)

    fun saveQuotaIntervalSeconds(seconds: Int) {
        preferences.edit().putInt("agent_quota_interval_seconds", seconds.coerceIn(15, 300)).apply()
    }

    fun loadDeviceLayoutMode(): String = preferences.getString("device_layout_mode", "auto").orEmpty().ifBlank { "auto" }

    fun saveDeviceLayoutMode(mode: String) {
        preferences.edit().putString("device_layout_mode", mode.trim().lowercase()).apply()
    }

    fun loadTiledMode(): Boolean = preferences.getBoolean("tiled_mode", true)

    fun loadJarvisEnabled(): Boolean = preferences.getBoolean("jarvis_enabled", false)
    fun saveJarvisEnabled(enabled: Boolean) = preferences.edit().putBoolean("jarvis_enabled", enabled).apply()

    fun loadJarvisWakeWord(): String = preferences.getString("jarvis_wake_word", "贾维斯") ?: "贾维斯"
    fun saveJarvisWakeWord(word: String) = preferences.edit().putString("jarvis_wake_word", word).apply()

    fun loadJarvisAutoConfirm(): Boolean = preferences.getBoolean("jarvis_auto_confirm", true)
    fun saveJarvisAutoConfirm(enabled: Boolean) = preferences.edit().putBoolean("jarvis_auto_confirm", enabled).apply()

    fun loadJarvisNotifyFinished(): Boolean = preferences.getBoolean("jarvis_notify_finished", true)
    fun saveJarvisNotifyFinished(enabled: Boolean) = preferences.edit().putBoolean("jarvis_notify_finished", enabled).apply()

    fun loadJarvisBluetoothSco(): Boolean = preferences.getBoolean("jarvis_sco_enabled", true)
    fun saveJarvisBluetoothSco(enabled: Boolean) = preferences.edit().putBoolean("jarvis_sco_enabled", enabled).apply()

    fun loadJarvisAsrMode(): String = preferences.getString("jarvis_asr_mode", "offline") ?: "offline"
    fun saveJarvisAsrMode(mode: String) = preferences.edit().putString("jarvis_asr_mode", mode).apply()

    fun loadJarvisTargetHostId(): String = preferences.getString("jarvis_target_host_id", "") ?: ""
    fun saveJarvisTargetHostId(hostId: String) = preferences.edit().putString("jarvis_target_host_id", hostId).apply()

    fun loadJarvisTargetAgentCommand(): String = preferences.getString("jarvis_target_agent_command", "") ?: ""
    fun saveJarvisTargetAgentCommand(cmd: String) = preferences.edit().putString("jarvis_target_agent_command", cmd).apply()

    fun saveTiledMode(tiled: Boolean) {
        preferences.edit().putBoolean("tiled_mode", tiled).apply()
    }

    fun isHostKeyTrusted(address: String, port: Int, fingerprint: String): Boolean =
        preferences.getStringSet("trusted_host_keys", emptySet()).orEmpty().contains("$address:$port|$fingerprint")

    fun trustHostKey(address: String, port: Int, fingerprint: String) {
        val keys = preferences.getStringSet("trusted_host_keys", emptySet()).orEmpty().toMutableSet()
        keys += "$address:$port|$fingerprint"
        preferences.edit().putStringSet("trusted_host_keys", keys).apply()
    }

    private fun loadAllOperationLogs(): List<OperationLogEntry> = runCatching {
        val raw = preferences.getString("operation_logs", null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            OperationLogEntry(
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
                client = obj.optString("client", "android"),
                action = obj.optString("action", "open"),
                account = obj.optString("account", "").trim().lowercase(),
                agentIcon = obj.optString("agent_icon", ""),
                titleSource = obj.optString("title_source", "default"),
            )
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    fun loadOperationLogs(account: String = loadSyncUsername()): List<OperationLogEntry> {
        val target = account.trim().lowercase()
        return loadAllOperationLogs().filter { it.account == target }.take(200)
    }

    fun saveOperationLogs(logs: List<OperationLogEntry>) {
        val trimmed = logs.distinctBy { it.id }.sortedByDescending { it.createdAt }.take(500)
        val array = JSONArray()
        trimmed.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("created_at", entry.createdAt)
                    .put("updated_at", entry.updatedAt)
                    .put("account", entry.account.trim().lowercase())
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
                    .put("client", entry.client)
                    .put("action", entry.action)
                    .put("agent_icon", entry.agentIcon)
                    .put("title_source", entry.titleSource),
            )
        }
        preferences.edit().putString("operation_logs", array.toString()).apply()
    }

    fun appendOperationLog(entry: OperationLogEntry): List<OperationLogEntry> {
        val normalized = entry.copy(account = entry.account.trim().lowercase())
        val merged = (listOf(normalized) + loadAllOperationLogs())
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
            .take(500)
        saveOperationLogs(merged)
        return merged.filter { it.account == normalized.account }.take(200)
    }

    fun updateOperationLogSessionTitle(sessionId: String, title: String, titleSource: String): List<OperationLogEntry> {
        val normalizedId = sessionId.replace("-", "").lowercase()
        val updatedAt = System.currentTimeMillis()
        val updated = loadAllOperationLogs().map { entry ->
            if (entry.sessionId.replace("-", "").lowercase() == normalizedId) {
                entry.copy(sessionName = title, titleSource = titleSource, updatedAt = updatedAt)
            } else entry
        }
        saveOperationLogs(updated)
        return updated.filter { it.account == loadSyncUsername().trim().lowercase() }.take(200)
    }

    fun mergeOperationLogs(remote: List<OperationLogEntry>, account: String = loadSyncUsername()): List<OperationLogEntry> {
        val target = account.trim().lowercase()
        val taggedRemote = remote.map { it.copy(account = target) }
        val merged = (taggedRemote + loadAllOperationLogs())
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
            .take(500)
        saveOperationLogs(merged)
        return merged.filter { it.account == target }.take(200)
    }
}

private class PasswordVault {
    private val alias = "remote_dev_terminal_password_key"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return runCatching {
            val data = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
