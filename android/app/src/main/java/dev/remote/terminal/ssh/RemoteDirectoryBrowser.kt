package dev.remote.terminal.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dev.remote.terminal.data.HostProfile
import dev.remote.terminal.data.LocalStore
import java.security.MessageDigest

data class RemoteDirectoryEntry(val name: String, val path: String)

data class RemoteDirectoryListing(val path: String, val directories: List<RemoteDirectoryEntry>)

class HostKeyApprovalRequiredException(val fingerprint: String) : Exception("需要确认 SSH 主机指纹")

object RemoteDirectoryBrowser {
    fun listDirectories(host: HostProfile, store: LocalStore, requestedPath: String): RemoteDirectoryListing {
        val hostKeys = BrowserHostKeyRepository(host, store)
        val jsch = JSch().apply { hostKeyRepository = hostKeys; store.addSshIdentity(this) }
        val session = jsch.getSession(host.username, host.address, host.port)
        var channel: ChannelSftp? = null
        try {
            session.setPassword(host.password)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            session.setServerAliveInterval(10_000)
            session.setServerAliveCountMax(2)
            session.setTimeout(20_000)
            try {
                session.connect(20_000)
            } catch (error: Exception) {
                hostKeys.untrustedFingerprint?.let { throw HostKeyApprovalRequiredException(it) }
                throw error
            }

            val sftp = session.openChannel("sftp") as ChannelSftp
            channel = sftp
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
            return RemoteDirectoryListing(normalizedPath, entries)
        } catch (error: Exception) {
            hostKeys.untrustedFingerprint?.let { throw HostKeyApprovalRequiredException(it) }
            throw error
        } finally {
            runCatching { channel?.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    private fun joinPath(parent: String, child: String): String =
        if (parent == "/") "/$child" else "${parent.trimEnd('/')}/$child"

    private class BrowserHostKeyRepository(
        private val host: HostProfile,
        private val store: LocalStore,
    ) : HostKeyRepository {
        @Volatile var untrustedFingerprint: String? = null
            private set

        override fun check(hostname: String, key: ByteArray): Int {
            val fingerprint = "SHA256:" + android.util.Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(key),
                android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            if (store.isHostKeyTrusted(host.address, host.port, fingerprint)) return HostKeyRepository.OK
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
