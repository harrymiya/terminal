package dev.remote.terminal.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
)

data class AgentPreset(
    val id: String,
    val name: String,
    val command: String,
    val icon: String,
)

data class TerminalTask(
    val id: String,
    val host: HostProfile,
    val directory: ProjectDirectory,
    val agent: AgentPreset,
    val displayName: String,
)

class LocalStore(context: Context) {
    private val preferences = context.getSharedPreferences("remote_dev_terminal", Context.MODE_PRIVATE)
    private val vault = PasswordVault()

    fun loadHosts(): List<HostProfile> = runCatching {
        val items = JSONArray(preferences.getString("hosts", "[]"))
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val dirs = item.optJSONArray("directories") ?: JSONArray()
            val directories = (0 until dirs.length()).map { n ->
                dirs.getJSONObject(n).let { ProjectDirectory(it.getString("id"), it.getString("name"), it.getString("path"), it.optString("icon", "📁")) }
            }
            HostProfile(
                id = item.getString("id"), name = item.getString("name"), address = item.getString("address"),
                port = item.optInt("port", 22), username = item.getString("username"),
                password = vault.decrypt(preferences.getString("password_${item.getString("id")}", null)),
                icon = item.optString("icon", "⌘"), directories = directories,
            )
        }
    }.getOrDefault(emptyList())

    fun saveHosts(hosts: List<HostProfile>) {
        val oldIds = loadHosts().map { it.id }.toSet()
        val root = JSONArray()
        hosts.forEach { host ->
            val dirs = JSONArray()
            host.directories.forEach { dirs.put(JSONObject().put("id", it.id).put("name", it.name).put("path", it.path).put("icon", it.icon)) }
            root.put(JSONObject().put("id", host.id).put("name", host.name).put("address", host.address)
                .put("port", host.port).put("username", host.username).put("icon", host.icon).put("directories", dirs))
            preferences.edit().putString("password_${host.id}", vault.encrypt(host.password)).apply()
        }
        (oldIds - hosts.map { it.id }.toSet()).forEach { preferences.edit().remove("password_$it").apply() }
        preferences.edit().putString("hosts", root.toString()).apply()
    }

    fun loadAgents(): List<AgentPreset> = runCatching {
        val items = JSONArray(preferences.getString("agents", null) ?: "")
        (0 until items.length()).map { items.getJSONObject(it).let { a -> AgentPreset(a.getString("id"), a.getString("name"), a.getString("command"), a.optString("icon", "✳")) } }
    }.getOrElse {
        listOf(
            AgentPreset("codex", "Codex", "codex", "✳"),
            AgentPreset("claude", "Claude Code", "claude", "◈"),
            AgentPreset("opencode", "OpenCode", "opencode", "⌘"),
            AgentPreset("shell", "Shell", "bash -l", "\$_"),
        ).also(::saveAgents)
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
