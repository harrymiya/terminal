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
)

data class HostEndpoint(
    val address: String,
    val port: Int,
    val isLan: Boolean,
)

fun HostProfile.endpoints(): List<HostEndpoint> {
    val list = mutableListOf<HostEndpoint>()
    if (lanAddress.isNotBlank()) {
        list.add(HostEndpoint(lanAddress.trim(), lanPort, isLan = true))
    }
    if (externalAddress.isNotBlank()) {
        list.add(HostEndpoint(externalAddress.trim(), externalPort, isLan = false))
    }
    if (list.isEmpty() && address.isNotBlank()) {
        list.add(HostEndpoint(address.trim(), port, isLan = false))
    }
    return list.distinctBy { it.address to it.port }
}

data class HostRoute(
    val address: String,
    val port: Int,
    val isLan: Boolean,
    val reachable: Boolean = true,
)

object HostRouteResolver {
    private val activeRoutes = androidx.compose.runtime.mutableStateMapOf<String, HostRoute>()

    fun getRoute(hostId: String): HostRoute? = activeRoutes[hostId]

    fun updateRoute(hostId: String, route: HostRoute) {
        activeRoutes[hostId] = route
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

    suspend fun resolveHostRoute(host: HostProfile): HostRoute = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val candidates = host.endpoints()
        for (ep in candidates) {
            val timeout = if (ep.isLan) 1200 else 1800
            if (isPortReachable(ep.address, ep.port, timeout)) {
                val route = HostRoute(ep.address, ep.port, ep.isLan, reachable = true)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    activeRoutes[host.id] = route
                }
                return@withContext route
            }
        }
        val fallback = candidates.firstOrNull() ?: HostEndpoint(host.address, host.port, false)
        val route = HostRoute(fallback.address, fallback.port, fallback.isLan, reachable = false)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            activeRoutes[host.id] = route
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

/** Returns the configured Codex CLI executable, including compatible codex2 builds. */
fun codexExecutable(command: String): String? {
    val configuredExecutable = command.trim().split(Regex("\\s+"), limit = 2).firstOrNull() ?: return null
    val executable = configuredExecutable.removeSurrounding("\"").removeSurrounding("'")
    return executable.takeIf { it.substringAfterLast('/') == "codex" || it.substringAfterLast('/') == "codex2" }
}

data class TerminalTask(
    val id: String,
    val host: HostProfile,
    val directory: ProjectDirectory,
    val agent: AgentPreset,
    val displayName: String,
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
            val port = item.optInt("port", 22)
            val lanPort = if (item.has("lan_port") && !item.isNull("lan_port")) item.optInt("lan_port", port) else port
            val externalPort = if (item.has("external_port") && !item.isNull("external_port")) item.optInt("external_port", port) else port
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
            )
        }
    }.getOrDefault(emptyList())

    fun saveHosts(hosts: List<HostProfile>) {
        val oldIds = loadHosts().map { it.id }.toSet()
        val root = JSONArray()
        hosts.forEach { host ->
            val dirs = JSONArray()
            host.directories.forEach { dirs.put(JSONObject().put("id", it.id).put("name", it.name).put("path", it.path).put("icon", it.icon)) }
            root.put(JSONObject().put("id", host.id).put("name", host.name).put("address", host.externalAddress.ifBlank { host.lanAddress })
                .put("port", host.port).put("lan_port", host.lanPort).put("external_port", host.externalPort)
                .put("username", host.username).put("icon", host.icon).put("directories", dirs)
                .put("sync_fingerprint", host.syncFingerprint).put("sync_id", host.syncId.trim())
                .put("lan_address", host.lanAddress).put("external_address", host.externalAddress).put("legacy_address", host.legacyAddress))
            preferences.edit().putString("password_${host.id}", vault.encrypt(host.password)).apply()
        }
        (oldIds - hosts.map { it.id }.toSet()).forEach { preferences.edit().remove("password_$it").apply() }
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
                AgentPreset("shell", "Shell", "bash -l", "\$_"),
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

    fun isHostKeyTrusted(address: String, port: Int, fingerprint: String): Boolean =
        preferences.getStringSet("trusted_host_keys", emptySet()).orEmpty().contains("$address:$port|$fingerprint")

    fun trustHostKey(address: String, port: Int, fingerprint: String) {
        val keys = preferences.getStringSet("trusted_host_keys", emptySet()).orEmpty().toMutableSet()
        keys += "$address:$port|$fingerprint"
        preferences.edit().putStringSet("trusted_host_keys", keys).apply()
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
