package dev.remote.terminal.jarvis.intent

import dev.remote.terminal.data.AgentPreset
import dev.remote.terminal.data.HostProfile
import dev.remote.terminal.data.ProjectDirectory
import kotlin.math.max
import kotlin.math.min

/**
 * Shared fuzzy, bilingual, and alias-aware entity matcher for Jarvis.
 * Keeps Tier-1 routing, speech correction, and Android handlers aligned on
 * workspaces, hosts, and AI developers without extra LLM calls.
 */
object JarvisEntityResolver {

    private val separatorRegex = Regex("""[-_\s./\\]+""")

    private val semanticAliasPairs: List<Pair<String, List<String>>> = listOf(
        "前端" to listOf("front", "frontend", "web", "ui", "client", "h5"),
        "后端" to listOf("back", "backend", "server", "api", "service"),
        "安卓" to listOf("android", "app", "mobile", "apk"),
        "苹果" to listOf("ios", "iphone", "ipad", "apple", "mac"),
        "桌面" to listOf("desktop", "windows", "ubuntu", "omarchy", "pc"),
        "脚本" to listOf("script", "scripts", "tool", "tools"),
        "测试" to listOf("test", "tests", "qa"),
        "文档" to listOf("doc", "docs", "readme"),
        "终端" to listOf("terminal", "shell", "console", "term")
    )

    private val agentAliasMap: Map<String, List<String>> = mapOf(
        "claude" to listOf("claude", "claude-code", "claudecode", "克劳德", "克劳", "可劳德", "clod"),
        "codex" to listOf("codex", "code-x", "扣代克斯", "扣代", "扣德", "可代克斯"),
        "agy" to listOf("agy", "antigravity", "阿基", "阿吉", "安提格拉维蒂", "安提"),
        "opencode" to listOf("opencode", "open-code", "欧鹏扣", "欧朋扣", "欧鹏"),
        "gemini" to listOf("gemini", "gemini-cli", "杰米尼", "吉米尼"),
        "copilot" to listOf("copilot", "copilot-cli"),
        "pi" to listOf("pi", "派"),
        "shell" to listOf("shell", "bash", "zsh", "sh", "命令行", "普通终端")
    )

    fun normalizeKey(raw: String): String =
        raw.trim().lowercase().replace(separatorRegex, "")

    fun expandQueryTokens(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        val results = linkedSetOf(trimmed)
        for ((zh, enList) in semanticAliasPairs) {
            if (trimmed.contains(zh, ignoreCase = true)) {
                results.addAll(enList)
            }
        }
        return results.toList()
    }

    fun resolveWorkspaceName(query: String, workspaces: List<String>): String? {
        if (query.isBlank() || workspaces.isEmpty()) return null
        val scored = workspaces.distinct().mapNotNull { ws ->
            scoreCandidate(query, listOf(ws))?.let { ws to it }
        }.sortedByDescending { it.second }
        if (scored.isEmpty()) return null
        val top = scored.first()
        val second = scored.getOrNull(1)
        return if (second != null && top.second == second.second && top.second < 95) null else top.first
    }

    fun resolveDirectory(
        nameQuery: String?,
        pathQuery: String?,
        directories: List<ProjectDirectory>
    ): ProjectDirectory? {
        if (directories.isEmpty()) return null
        if (!pathQuery.isNullOrBlank()) {
            directories.firstOrNull { it.path.equals(pathQuery, true) }?.let { return it }
            val pathMatches = directories.filter { it.path.contains(pathQuery, true) }
            if (pathMatches.size == 1) return pathMatches.first()
        }
        val q = nameQuery?.trim().orEmpty()
        if (q.isBlank()) return null
        val scored = directories.mapNotNull { dir ->
            val dirBase = dir.path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
            scoreCandidate(q, listOf(dir.name, dirBase, dir.path))?.let { dir to it }
        }.sortedByDescending { it.second }
        val top = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)
        return if (second != null && top.second == second.second && top.second < 95) null else top.first
    }

    fun resolveHost(query: String, hosts: List<HostProfile>): HostProfile? {
        if (query.isBlank() || hosts.isEmpty()) return null
        val scored = hosts.mapNotNull { host ->
            scoreCandidate(query, listOf(host.name, host.id, host.lanAddress, host.externalAddress))?.let { host to it }
        }.sortedByDescending { it.second }
        val top = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)
        return if (second != null && top.second == second.second && top.second < 95) null else top.first
    }

    fun resolveAgent(query: String, agents: List<AgentPreset>): AgentPreset? {
        if (query.isBlank() || agents.isEmpty()) return null
        val qTrim = query.trim()
        val qNorm = normalizeKey(qTrim)
        val expandedAliases = linkedSetOf(qTrim, qNorm)
        for ((canonical, aliases) in agentAliasMap) {
            if (aliases.any { a -> qNorm == normalizeKey(a) || qTrim.equals(a, true) || qNorm.contains(normalizeKey(a)) }) {
                expandedAliases.add(canonical)
                expandedAliases.addAll(aliases)
            }
        }

        var best: AgentPreset? = null
        var bestScore = 0
        var tieCount = 0
        for (agent in agents) {
            val cmdHead = agent.command.trim().substringBefore(' ').substringAfterLast('/')
            val candidates = listOf(agent.name, agent.command, cmdHead, agent.id).filter { it.isNotBlank() }
            var maxAgentScore = 0
            for (alias in expandedAliases) {
                val s = scoreCandidate(alias, candidates, allowAliases = false) ?: 0
                if (s > maxAgentScore) maxAgentScore = s
            }
            if (maxAgentScore > bestScore) {
                bestScore = maxAgentScore
                best = agent
                tieCount = 1
            } else if (maxAgentScore > 0 && maxAgentScore == bestScore) {
                tieCount++
            }
        }
        return if (bestScore >= 55 && (tieCount == 1 || bestScore >= 95)) best else null
    }

    fun scoreCandidate(
        query: String,
        targets: List<String>,
        allowAliases: Boolean = true
    ): Int? {
        val queries = if (allowAliases) expandQueryTokens(query) else listOf(query.trim())
        var best = 0
        for (q in queries) {
            val qTrim = q.trim()
            if (qTrim.isBlank()) continue
            val qNorm = normalizeKey(qTrim)
            for (t in targets) {
                val tTrim = t.trim()
                if (tTrim.isBlank()) continue
                val tNorm = normalizeKey(tTrim)
                val s = when {
                    tTrim.equals(qTrim, ignoreCase = true) -> 100
                    qNorm.isNotEmpty() && tNorm == qNorm -> 96
                    tTrim.startsWith(qTrim, ignoreCase = true) || (qNorm.length >= 2 && tNorm.startsWith(qNorm)) -> 86
                    qTrim.length >= 2 && tTrim.contains(qTrim, ignoreCase = true) -> 78
                    qNorm.length >= 2 && tNorm.contains(qNorm) -> 74
                    qNorm.length >= 3 && tNorm.length >= 3 -> {
                        val sim = similarity(qNorm, tNorm)
                        when {
                            sim >= 0.85 -> 72
                            sim >= 0.72 -> 60
                            else -> 0
                        }
                    }
                    else -> 0
                }
                if (s > best) best = s
            }
        }
        return best.takeIf { it >= 55 }
    }

    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 0.0
        val dist = levenshtein(a, b)
        return 1.0 - dist.toDouble() / maxLen.toDouble()
    }

    fun levenshtein(lhs: String, rhs: String): Int {
        val m = lhs.length
        val n = rhs.length
        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..n) {
                val temp = dp[j]
                val cost = if (lhs[i - 1] == rhs[j - 1]) 0 else 1
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + cost)
                prev = temp
            }
        }
        return dp[n]
    }
}
