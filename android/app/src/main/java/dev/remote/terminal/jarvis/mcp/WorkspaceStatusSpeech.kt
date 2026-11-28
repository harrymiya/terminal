package dev.remote.terminal.jarvis.mcp

import org.json.JSONArray
import org.json.JSONObject

internal fun workspaceStatusPhrase(session: JSONObject, shared: Boolean = false): String {
    val name = session.optString("workspace").trim().trimEnd('/', '\\')
        .substringAfterLast('/').substringAfterLast('\\').ifBlank { "未命名工作区" }
    val status = when {
        session.optBoolean("needs_action") || session.optBoolean("pending_host_key") -> "需要确认"
        session.optBoolean("is_streaming") -> "正在输出"
        shared -> "为远端共享"
        session.optBoolean("connected") -> "已连接"
        else -> "未连接"
    }
    return "$name $status"
}

internal fun workspaceStatusSummary(sessions: JSONArray, shared: JSONArray = JSONArray()): String {
    val total = sessions.length() + shared.length()
    if (total == 0) return "当前没有工作区实例。"

    val local = (0 until sessions.length()).mapNotNull { sessions.optJSONObject(it) }
    if (total > 3) {
        val needsAction = local.count { it.optBoolean("needs_action") || it.optBoolean("pending_host_key") }
        val connected = local.count { it.optBoolean("connected") }
        val counts = listOfNotNull(
            "$connected 个已连接".takeIf { connected > 0 },
            "${shared.length()} 个远端共享".takeIf { shared.length() > 0 },
            "$needsAction 个需要确认".takeIf { needsAction > 0 }
        )
        return "当前有 $total 个工作区实例。" + counts.joinToString("，", postfix = if (counts.isEmpty()) "" else "。")
    }

    val sharedSessions = (0 until shared.length()).mapNotNull { shared.optJSONObject(it) }
    return (local.map { workspaceStatusPhrase(it) } + sharedSessions.map { workspaceStatusPhrase(it, shared = true) })
        .joinToString("，", postfix = "。")
}
