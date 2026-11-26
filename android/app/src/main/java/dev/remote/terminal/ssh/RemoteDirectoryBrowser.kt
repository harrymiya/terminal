package dev.remote.terminal.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dev.remote.terminal.data.HostProfile
import dev.remote.terminal.data.HostRoute
import dev.remote.terminal.data.HostRouteResolver
import dev.remote.terminal.data.LocalStore
import dev.remote.terminal.data.endpoints
import java.io.IOException
import java.security.MessageDigest

data class RemoteDirectoryEntry(val name: String, val path: String)

data class RemoteDirectoryListing(val path: String, val directories: List<RemoteDirectoryEntry>)

class HostKeyApprovalRequiredException(val address: String, val port: Int, val fingerprint: String) : Exception("需要确认 SSH 主机指纹")

object RemoteDirectoryBrowser {
    fun listDirectories(host: HostProfile, store: LocalStore, requestedPath: String): RemoteDirectoryListing {
        return withSshSession(host, store) { session ->
            val sftp = session.openChannel("sftp") as ChannelSftp
            try {
                sftp.connect(15_000)
                val normalizedPath = sftp.realpath(requestedPath.ifBlank { sftp.pwd() })
                val entries = sftp.ls(normalizedPath).mapNotNull { item ->
                    val entry = item as? ChannelSftp.LsEntry ?: return@mapNotNull null
                    if (entry.filename == "." || entry.filename == "..") return@mapNotNull null
                    val childPath = joinPath(normalizedPath, entry.filename)
                    val isDirectory = entry.attrs.isDir ||
                        (entry.attrs.isLink && runCatching { sftp.stat(childPath).isDir }.getOrDefault(false))
                    if (isDirectory) RemoteDirectoryEntry(entry.filename, childPath) else null
                }.sortedBy { it.name.lowercase() }
                RemoteDirectoryListing(normalizedPath, entries)
            } finally {
                runCatching { sftp.disconnect() }
            }
        }
    }

    internal fun <T> withSshSession(host: HostProfile, store: LocalStore, read: (Session) -> T): T {
        var currentAddress = host.address
        var currentPort = host.port
        val hostKeys = BrowserHostKeyRepository(store) { currentAddress to currentPort }
        val jsch = JSch().apply { hostKeyRepository = hostKeys; store.addSshIdentity(this) }
        val resolved = HostRouteResolver.getRoute(host.id)
        val allEndpoints = host.endpoints()
        val endpoints = if (resolved != null) {
            val matched = allEndpoints.firstOrNull { it.address == resolved.address && it.port == resolved.port }
            if (matched != null) listOf(matched) + allEndpoints.filterNot { it == matched } else allEndpoints
        } else allEndpoints
        var openedSession: Session? = null
        var connectFailure: Exception? = null

        for (endpoint in endpoints) {
            currentAddress = endpoint.address
            currentPort = endpoint.port
            val candidate = jsch.getSession(host.username.trim(), endpoint.address, endpoint.port)
            val effectivePassword = host.password.ifBlank { store.loadHostPassword(host.id) }
            candidate.setPassword(effectivePassword)
            candidate.setConfig("StrictHostKeyChecking", "yes")
            candidate.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            candidate.setServerAliveInterval(10_000)
            candidate.setServerAliveCountMax(2)
            candidate.setTimeout(20_000)
            try {
                val connectTimeout = if (endpoint.isLan && endpoints.size > 1) 3_000 else 15_000
                candidate.connect(connectTimeout)
                openedSession = candidate
                HostRouteResolver.updateRoute(host.id, HostRoute(endpoint.address, endpoint.port, endpoint.isLan, reachable = true))
                break
            } catch (error: Exception) {
                candidate.disconnect()
                hostKeys.untrustedFingerprint?.let { throw HostKeyApprovalRequiredException(endpoint.address, endpoint.port, it) }
                connectFailure = error
            }
        }
        val session = openedSession ?: run {
            hostKeys.untrustedFingerprint?.let { throw HostKeyApprovalRequiredException(currentAddress, currentPort, it) }
            throw (connectFailure ?: IOException("无法连接主机"))
        }

        try {
            return read(session)
        } catch (error: Exception) {
            hostKeys.untrustedFingerprint?.let { throw HostKeyApprovalRequiredException(currentAddress, currentPort, it) }
            throw error
        } finally {
            runCatching { session.disconnect() }
        }
    }

    private fun joinPath(parent: String, child: String): String =
        if (parent == "/") "/$child" else "${parent.trimEnd('/')}/$child"

    private class BrowserHostKeyRepository(
        private val store: LocalStore,
        private val currentTarget: () -> Pair<String, Int>,
    ) : HostKeyRepository {
        @Volatile var untrustedFingerprint: String? = null
            private set

        override fun check(hostname: String, key: ByteArray): Int {
            val fingerprint = "SHA256:" + android.util.Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(key),
                android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            val (address, port) = currentTarget()
            if (store.isHostKeyTrusted(address, port, fingerprint)) return HostKeyRepository.OK
            untrustedFingerprint = fingerprint
            return HostKeyRepository.NOT_INCLUDED
        }

        override fun add(hostkey: HostKey?, ui: com.jcraft.jsch.UserInfo?) = Unit
        override fun remove(hostname: String?, type: String?) = Unit
        override fun remove(hostname: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "RemoteDirectoryBrowser"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(hostname: String?, type: String?): Array<HostKey> = emptyArray()
    }
}
