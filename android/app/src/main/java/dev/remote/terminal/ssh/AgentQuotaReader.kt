package dev.remote.terminal.ssh

import android.os.SystemClock
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import dev.remote.terminal.data.QuotaWindow
import dev.remote.terminal.data.antigravityExecutable
import dev.remote.terminal.data.codexExecutable
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import org.json.JSONObject

internal object AgentQuotaReader {
    fun read(session: Session, command: String, directory: String?): List<QuotaWindow>? {
        codexExecutable(command)?.let { return readCodexQuota(session, it, directory) }
        antigravityExecutable(command)?.let { return readAntigravityQuota(session, it, directory) }
        return null
    }

    private fun remoteCommand(directory: String?, command: String): String {
        val cd = if (directory.isNullOrBlank()) "" else "cd -- ${shellQuote(directory)} && "
        return cd + "\"${'$'}{SHELL:-/bin/bash}\" -l -i -c ${shellQuote(command)}"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun readCodexQuota(session: Session, executable: String, directory: String?): List<QuotaWindow>? {
        val channel = session.openChannel("exec") as ChannelExec
        try {
            // Leave simple command names unquoted so the login shell can expand aliases.
            val invocation = if (executable.matches(Regex("[A-Za-z0-9_]+"))) executable else shellQuote(executable)
            val quotaCommand = "$invocation app-server --listen stdio://"
            channel.setCommand(remoteCommand(directory, quotaCommand))
            val output = channel.outputStream
            val input = channel.inputStream
            channel.connect(10_000)
            output.write(("{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"remote_dev_terminal\",\"version\":\"1.0\"}}}\n").toByteArray())
            output.flush()
            val deadline = SystemClock.elapsedRealtime() + 15_000
            val pending = StringBuilder()
            var requested = false
            while (SystemClock.elapsedRealtime() < deadline && session.isConnected) {
                if (input.available() == 0) {
                    if (channel.isClosed) break
                    Thread.sleep(50)
                    continue
                }
                val byte = input.read()
                if (byte < 0) break
                if (byte != 10) {
                    if (pending.length < 1_000_000) pending.append(byte.toChar()) else return null
                    continue
                }
                val message = runCatching { JSONObject(pending.toString()) }.getOrNull()
                pending.setLength(0)
                if (message?.optInt("id", -1) == 1 && !requested) {
                    if (message.has("error")) return null
                    output.write(("{\"method\":\"initialized\",\"params\":{}}\n" +
                        "{\"id\":2,\"method\":\"account/rateLimits/read\",\"params\":{}}\n").toByteArray())
                    output.flush()
                    requested = true
                } else if (message?.optInt("id", -1) == 2) {
                    val snapshot = message.optJSONObject("result")?.optJSONObject("rateLimits") ?: return null
                    return listOf("primary", "secondary").mapNotNull { name ->
                        val window = snapshot.optJSONObject(name) ?: return@mapNotNull null
                        if (!window.has("usedPercent") || window.isNull("usedPercent") ||
                            !window.has("windowDurationMins") || window.isNull("windowDurationMins")) return@mapNotNull null
                        val minutes = window.optInt("windowDurationMins", 0)
                        if (minutes <= 0) return@mapNotNull null
                        QuotaWindow(minutes, (100 - window.optInt("usedPercent")).coerceIn(0, 100),
                            window.optLong("resetsAt").takeIf { it > 0 })
                    }.takeIf { it.isNotEmpty() }
                }
            }
            return null
        } finally {
            channel.disconnect()
        }
    }

    private fun readAntigravityQuota(session: Session, executable: String, directory: String?): List<QuotaWindow>? {
        val channel = session.openChannel("exec") as ChannelExec
        try {
            val invocation = if (executable == "agy" || executable == "agy2") executable else shellQuote(executable)
            val quotaCommand = "$invocation -p \"/usage\" --output-format json"
            channel.setCommand(remoteCommand(directory, quotaCommand))
            val input = channel.inputStream
            val stdin = channel.outputStream
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            channel.connect(10_000)
            stdin.close()
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (session.isConnected && SystemClock.elapsedRealtime() < deadline) {
                // The server can close the channel while stdout still has unread bytes.
                val closed = channel.isClosed
                val available = input.available()
                if (available == 0) {
                    if (closed) {
                        if (channel.exitStatus != 0) return null
                        return parseAntigravityQuota(output.toString(Charsets.UTF_8.name()))
                    }
                    Thread.sleep(50)
                    continue
                }
                val count = input.read(buffer, 0, minOf(available, buffer.size))
                if (count < 0 || output.size() + count > 1_000_000) return null
                output.write(buffer, 0, count)
            }
            return null
        } finally {
            channel.disconnect()
        }
    }

    private fun parseAntigravityQuota(output: String): List<QuotaWindow>? {
        val jsonStart = output.indexOf('{')
        if (jsonStart < 0) return null
        val root = JSONObject(output.substring(jsonStart))
        val groups = root.optJSONObject("command")?.optJSONObject("data")?.optJSONArray("groups") ?: return null
        val quota = sortedMapOf<Int, QuotaWindow>()
        for (groupIndex in 0 until groups.length()) {
            val group = groups.optJSONObject(groupIndex) ?: continue
            val isGeminiModels = group.optString("name").trim().equals("Gemini Models", ignoreCase = true)
            val buckets = group.optJSONArray("buckets") ?: continue
            for (bucketIndex in 0 until buckets.length()) {
                val bucket = buckets.optJSONObject(bucketIndex) ?: continue
                val id = bucket.optString("id").lowercase()
                if (!id.contains("gemini")) continue
                val minutes = when {
                    id.contains("weekly") || id.contains("7d") -> 10080
                    id.contains("5h") || id.contains("session") -> 300
                    else -> continue
                }
                if (minutes == 10080 && !isGeminiModels) continue
                val remaining = bucket.optDouble("remaining_fraction", Double.NaN)
                if (!remaining.isFinite()) continue
                val resetsAt = runCatching { OffsetDateTime.parse(bucket.optString("reset_time")).toEpochSecond() }.getOrNull()
                quota[minutes] = QuotaWindow(minutes, (remaining * 100).toInt().coerceIn(0, 100), resetsAt)
            }
        }
        return quota.values.toList().takeIf { it.isNotEmpty() }
    }
}
