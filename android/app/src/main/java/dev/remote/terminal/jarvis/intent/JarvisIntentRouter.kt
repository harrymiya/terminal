package dev.remote.terminal.jarvis.intent

import android.util.Log
import dev.remote.terminal.jarvis.api.IntentRouter
import dev.remote.terminal.jarvis.api.JarvisAction
import dev.remote.terminal.jarvis.api.JarvisContext
import dev.remote.terminal.jarvis.api.JarvisSessionBrief
import dev.remote.terminal.jarvis.navigation.JarvisScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Resolves deterministic and context-aware App actions before a request is handed to the App Agent. */
class JarvisIntentRouter(
    private val wakeWordProvider: () -> String = { "贾维斯" }
) : IntentRouter {

    var tacitHabitMatcher: ((String) -> JarvisAction?)? = null

    companion object {
        private const val TAG = "JarvisIntentRouter"

        // 本地极速意图匹配正则 (Tier-1: 0 Token)
        private val ConfirmRegex = Regex(
            """(?i)^\s*(?:再)?(?:允许|确认|同意|批准|按回车|回车|approve|confirm|proceed|enter)(?:一次|一下|吧|啊)?\s*[:?？!！.。]*$"""
        )

        private val SoftConfirmRegex = Regex(
            """(?i)^\s*(?:好的|可以|行|确定|就这么干|没问题|嗯|好|对|yes|ok)(?:吧|啊)?\s*[:?？!！.。]*$"""
        )

        private val ContinueBriefRegex = Regex(
            """(?i)^\s*(?:继续|继续吧|继续啊|接着干|接着写|接着来|接着跑|继续跑|继续刚才(?:那个|的)?(?:任务)?|往下跑|继续执行)\s*[。！!]*$"""
        )

        private val RetryLastRegex = Regex(
            """(?i)^\s*(?:再跑一次|再试一次|重跑|重跑一次|再来一遍|再来一次|重新跑|重新执行|再执行一次|按老规矩|老规矩)\s*[。！!]*$"""
        )

        private val CancelRegex = Regex(
            """(?i)^\s*(?:取消|停止|中止|算了|别动|停|住手|别执行|别跑了|停下来|cancel|stop|abort|no|quit)\s*[:?？!！.。]*$"""
        )

        private val SwitchWorkspaceRegex = Regex(
            """(?i)^\s*(?:请|帮我|麻烦)?\s*(?:切换到|切到|换到|打开|进入|进一下|看下|看看)\s*(?:工作区\s*)?([a-zA-Z0-9_\u4e00-\u9fa5\-\.\s]+?)(?:\s*工作区)?(?:\s*一下|\s*吧)?\s*[。！!]*$"""
        )

        private val StatusQueryRegex = Regex(
            """(?i)^\s*(?:现在|目前|当前)?(?:什么情况|进度如何|进度怎么样|好了吗|好了没|完成了吗|完成没|跑完了吗|跑完没有|干得怎么样|干得咋样|跑得咋样|跑得怎么样|还在跑吗|在干嘛|在做什么|什么状态|当前状态|状态怎么样|看看进展|看看状态|查下状态|查看状态|status)[?？!！.。]*$"""
        )

        private val TargetStatusRegex = Regex(
            """(?i)^\s*(?:请|帮我|麻烦)?\s*(?:看看|看下|看一下|查下|查一下|查询|问下)?\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\.\s]{1,36}?)\s*(?:现在|目前|当前)?(?:怎么样了|怎么样|如何|什么情况|进度如何|进度怎么样|好了吗|好了没|完成了吗|完成没|跑完了吗|跑完没|跑完没有|干得怎么样|干得咋样|在干嘛|在做什么|什么状态|状态怎么样|报错了吗)[?？!！.。]*$"""
        )

        private val InstanceStatusQueryRegex = Regex(
            """(?i)(?:(?:请|帮我)?(?:查看|看下|看一下|看看|查查|查一下|查询|列出|汇报|告诉我|了解).{0,12}(?:工作区\s*)?(?:远端|远程|本地)?(?:实例|任务|会话)|(?:工作区\s*)?(?:远端|远程|本地)?(?:实例|任务|会话)(?:的)?(?:(?:现在|目前|当前|实时)?(?:列表|状态(?:怎么样|如何|是什么)?|进度(?:如何|怎么样)?|情况)|最近在做什么|正在做什么|在做什么))"""
        )

        private val ReadScreenRegex = Regex(
            """(?i)(?:读屏幕|看屏幕|念屏幕|屏幕内容|显示什么|输出什么|屏幕上写了啥|看看终端写了啥|读一下终端|念一下输出|看看它写了啥|屏幕输出)"""
        )

        private val ReconnectRegex = Regex(
            """(?i)(?:重连|重新连接|断线重连|重新连上)"""
        )

        private val CloseInstancePrefixRegex = Regex(
            """^\s*(?:(?:请|麻烦|帮我|给我)\s*){0,3}(?:(?:核对|确认|检查|查看)(?:一下)?\s*(?:后再|然后|并且|并|再)\s*)?(?:关闭|关掉|关了|结束|停止)\s*(?:一下\s*)?"""
        )

        private val CloseInstanceObjectRegex = Regex(
            """^\s*(?:(?:请|麻烦|帮我|给我)\s*){0,3}(?:把|将)\s*(.+?)\s*(?:关闭|关掉|关了|结束|停止)(?:掉|一下|吧)?$"""
        )

        private val CloseInstanceSuffixRegex = Regex(
            """\s*(?:(?:的|这个|那个|这条|那条|当前)\s*)?(?:(?:远端|远程|共享|本地)\s*)*(?:工作区\s*)?(?:任务实例|终端|会话|实例|任务)$"""
        )

        private val CloseInstanceTypePrefixRegex = Regex(
            """^(?:(?:这个|那个|当前)\s*)?(?:(?:远端|远程|共享|本地)\s*)*(?:工作区\s*)?(?:任务实例|终端|会话|实例|任务)(?=\s|[“"「『：:]|名叫|名为|叫|$)"""
        )

        private val CloseAnaphoraRegex = Regex(
            """(?i)^\s*(?:请|帮我|麻烦)?\s*(?:把\s*)?(?:它|这个|那个|刚才那个|当前这个|刚刚那个)\s*(?:关掉|关闭|关了|结束)|(?:关掉|关闭|关了|结束)\s*(?:它|这个|那个|刚才那个|当前这个|刚刚那个)\s*(?:吧|一下)?[。！!]*$"""
        )

        private val OpenAnaphoraRegex = Regex(
            """(?i)^\s*(?:请|帮我|麻烦)?\s*(?:打开|切回|切到|回到|进入|聚焦|看看)\s*(?:它|刚才那个|刚刚那个|上一个|上面那个)\s*(?:吧|一下)?[。！!]*$"""
        )

        private val PrevNextSessionRegex = Regex(
            """(?i)^\s*(?:请|帮我|麻烦)?\s*(?:切到|换到|打开|看下|回到)\s*(上一个|前一个|下一个|后一个|最后一个)(?:工作区|实例|会话|任务)?\s*(?:吧|一下)?[。！!]*$"""
        )

        private val OrdinalSessionRegex = Regex(
            """(?i)^\s*(?:请|帮我|麻烦)?\s*(打开|切到|进入|看看|看下|查下|关闭|关掉|盯着|看着)?\s*第\s*([1-9一二两三四五六七八九])\s*个\s*(?:工作区|实例|会话|任务)?\s*(怎么样|好了吗|好了没|什么情况|吧|一下)?\s*[?？!！.。]*$"""
        )

        private val DismissAwakeRegex = Regex(
            """(?i)^\s*(?:(?:关闭唤醒|退出唤醒)(?:态|状态)?|关闭弹窗|关掉弹窗|关闭浮窗|结束对话|退出对话|退下|下去吧|行了没事了|没事了|不用了|先这样|就这些|休眠|回到待命|闭嘴|再见|拜拜|关闭|退出|goodbye|bye|sleep|dismiss)\s*[:?？!！.。]*$"""
        )

        private val TiledViewRegex = Regex(
            """(?i)(?:切换平铺|平铺模式|变成平铺)"""
        )

        private val TabViewRegex = Regex(
            """(?i)(?:切换标签|标签模式|变成标签)"""
        )

        private val SyncRegex = Regex(
            """(?i)(?:触发同步|立即同步|同步一下|同步数据)"""
        )

        private val PublicKeyRegex = Regex(
            """(?i)(?:读取公钥|查看公钥|我的公钥|公钥是什么)"""
        )

        private val AppStateRegex = Regex(
            """(?i)(?:系统状态|应用状态|全部状态|整体状态)"""
        )

        private val OpenInstancePrefixRegex = Regex(
            """(?i)^\s*(?:(?:请|麻烦|帮我)\s*){0,2}(?:(?:查看|看下|看一下|看看|核对|检查).{0,8}?(?:并)?\s*)?(?:打开|接入|进入|切换到|切到|聚焦到|聚焦)\s*"""
        )

        private val InstanceTargetRegex = Regex(
            """(?i)(?:远端|远程|共享)?\s*(?:工作区\s*)?(?:实例|任务|会话)"""
        )

        private val SwitchHostRegex = Regex(
            """(?i)(?:切换到主机|换到主机|切到主机|换主机|切主机)\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\.]+)"""
        )

        private val HelpRegex = Regex(
            """(?i)^(?:请)?(?:你能做什么|你能做些什么|你会做什么|你会什么|你有什么功能|你有哪些功能|你有哪些能力|你能调用哪些工具|帮助|功能|指令|help|who are you)[\s?？!！.。]*$"""
        )

        private val PraiseRegex = Regex(
            """(?i)^\s*(?:不错|很好|干得好|太棒了|没错|就是这个|挺好|可以的|牛|赞)[。！!]*$"""
        )

        private val GreetingRegex = Regex(
            """(?i)^\s*(?:在吗|你在吗|在不在|你好|您好|听得到吗|听得见吗|能听见吗|能听到吗|早上好|晚上好|喂)[?？!！.。]*${'$'}"""
        )

        // 监工 / 看着干活相关正则 (Tier-1: 0 Token)
        private val StartWatchRegex = Regex(
            """(?i)(?:(?:帮我|给我|替我)?(?:看着|盯着|盯一下|盯着点|看着点|守着|看着干活|开始监工|帮我监工|盯紧|监工)|(?:看着|盯着|守着|监工).*(?:干活|工作|任务|代码|工作区))"""
        )
        private val StopWatchRegex = Regex(
            """(?i)(?:(?:不用|别|停止|取消)(?:看着|盯着|守着|监工|观察|盯了|看了)|停止监工|结束监工|不用盯了|不用守着了)"""
        )
        private val WatchStatusRegex = Regex(
            """(?i)(?:监工状态|看着怎么样|看的怎么样|盯得怎么样|盯得如何|监工进度)"""
        )
        private val MemoryStatsRegex = Regex(
            """(?i)(?:记忆统计|长记忆库|记住了多少|沉淀了多少记忆|查看长记忆)"""
        )
        private val WebSearchRegex = Regex(
            """(?i)^\s*(?:请|帮我|麻烦)?\s*(?:上网|网上|联网|去网上|去互联网|百度|谷歌|google)\s*(?:搜一下|搜索一下|查一下|搜一搜|查一查|检索一下|检索|搜索|查|搜)\s*[:：]?\s*(.+)$"""
        )
        private val DirectSearchRegex = Regex(
            """(?i)^\s*(?:请|帮我|麻烦)?\s*(?:搜一下|搜索一下|上网查|网上搜|联网查|百度一下)\s*[:：]?\s*(.+)$"""
        )

        // 开发者选择与工作区任务派发相关正则 (Tier-1: 0 Token)
        private val DispatchWithDeveloperRegex = Regex(
            """(?i)^\s*(?:请|帮我|麻烦)?\s*(?:选择开发者|选开发者|用开发者|让开发者|用|让)\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\.]+)\s*(?:在|去|打开|切到|进入)?\s*(?:工作区)?\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\./]+)?\s*(?:派发任务|执行任务|跑任务|执行|做|处理|修|改|写)\s*[:：]?\s*(.+)$"""
        )
        private val OpenWorkspaceWithDeveloperRegex = Regex(
            """(?i)^\s*(?:请|帮我|麻烦)?\s*(?:选择开发者|选开发者|用开发者|让开发者|用|让)\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\.]+)\s*(?:打开|切到|进入)\s*(?:工作区)?\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\./]+)\s*[。！!]*$"""
        )
        private val WorkspaceDispatchRegex = Regex(
            """(?i)^\s*(?:请|帮我|麻烦)?\s*(?:在|进入|打开)\s*(?:工作区)?\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\./]+)\s*(?:用|让|选择开发者)?\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\.]+)?\s*(?:派发任务|执行任务)\s*[:：]?\s*(.+)$"""
        )
        private val SelectDeveloperOnlyRegex = Regex(
            """(?i)^\s*(?:请|帮我|麻烦)?\s*(?:选择开发者|选开发者|切换开发者|换开发者|设为开发者|换个开发者|切换成|换成|用|让)\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\.]+)\s*(?:来|来吧|试试|吧)?\s*[。！!]*$"""
        )
        private val RetryWithAgentRegex = Regex(
            """(?i)^\s*(?:请|帮我|麻烦)?\s*(?:换|用|让|改用)\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\.]+)\s*(?:重来|重新跑|再试一次|试试|接着干|接着写|继续|跑一遍)\s*[。！!]*$"""
        )

        // 用户纠错 / 改口（如 "不对，是后端"、"不是这个，换成 Claude"）
        private val CorrectionSlotRegex = Regex(
            """(?i)^\s*(?:不对|错了|搞错了|弄错了|不是这个|不是那个|不是)(?:[，,。\s]*(?:我是说|应该是|应该用|要|换成|改用|改成|是|用))?\s*[:：]?\s*(.+?)\s*[。！!]*$"""
        )

        private val DIGIT_MAP = mapOf(
            '零' to '0', '一' to '1', '二' to '2', '两' to '2', '三' to '3',
            '四' to '4', '五' to '5', '六' to '6', '七' to '7', '八' to '8', '九' to '9'
        )
    }

    fun wakeVariants(): List<String> {
        val configured = wakeWordProvider().trim()
        val items = linkedSetOf<String>()
        if (configured.isNotBlank()) {
            items.add(configured)
            val mapped = buildString {
                configured.forEach { ch -> append(DIGIT_MAP[ch] ?: ch) }
            }
            if (mapped != configured) items.add(mapped)
        }
        items.addAll(listOf("贾维斯", "查维斯", "加维斯", "家维斯", "贾维", "jarvis"))
        return items.sortedByDescending { it.length }
    }

    fun containsWakeWord(text: String): Boolean {
        val lower = text.trim().lowercase()
        return wakeVariants().any { w -> lower.contains(w.lowercase()) }
    }

    private fun buildWakePattern(): String =
        wakeVariants().joinToString("|") { Regex.escape(it) }

    private fun resolveWorkspaceTarget(rawQuery: String, context: JarvisContext): String? {
        val q = rawQuery.trim()
        if (q.isBlank()) return null
        val allWs = (context.workspaces + context.openSessions.map { it.workspace } + context.sharedTasks.map { it.workspace })
            .filter { it.isNotBlank() }
            .distinct()
        return JarvisEntityResolver.resolveWorkspaceName(q, allWs)
    }

    private fun resolveAgentTarget(rawQuery: String, context: JarvisContext): String? {
        val q = rawQuery.trim()
        if (q.isBlank()) return null
        val allAgents = (context.agents + context.openSessions.map { it.agent })
            .filter { it.isNotBlank() }
            .distinct()
        return JarvisEntityResolver.resolveWorkspaceName(q, allAgents)
            ?: inferCanonicalAgentFromAlias(q, allAgents)
    }

    private fun inferCanonicalAgentFromAlias(raw: String, available: List<String>): String? {
        val norm = JarvisEntityResolver.normalizeKey(raw)
        val canonicalMap = listOf(
            listOf("claude", "claudecode", "克劳德", "克劳", "可劳德") to "claude",
            listOf("codex", "codexcli", "扣代克斯", "扣代", "扣德") to "codex",
            listOf("agy", "antigravity", "阿基", "阿吉", "安提") to "agy",
            listOf("opencode", "欧鹏扣", "欧鹏") to "opencode",
            listOf("gemini", "杰米尼", "吉米尼") to "gemini",
            listOf("shell", "bash", "zsh", "命令行", "普通终端") to "shell"
        )
        for ((aliases, canonical) in canonicalMap) {
            if (aliases.any { norm == it || norm.contains(it) }) {
                return available.firstOrNull { a ->
                    val aNorm = JarvisEntityResolver.normalizeKey(a)
                    aliases.any { aNorm.contains(it) } || aNorm.contains(canonical)
                } ?: canonical
            }
        }
        return null
    }

    private fun resolveAnchorSession(context: JarvisContext): JarvisSessionBrief? {
        if (context.lastSessionId.isNotBlank()) {
            context.openSessions.firstOrNull { it.id == context.lastSessionId }?.let { return it }
            context.sharedTasks.firstOrNull { it.id == context.lastSessionId }?.let { return it }
        }
        if (context.lastWorkspace.isNotBlank()) {
            context.openSessions.firstOrNull { it.workspace.equals(context.lastWorkspace, true) }?.let { return it }
            context.sharedTasks.firstOrNull { it.workspace.equals(context.lastWorkspace, true) }?.let { return it }
        }
        return context.openSessions.firstOrNull { it.focused } ?: context.openSessions.firstOrNull()
    }

    private fun parseChineseOrdinal(token: String): Int? {
        val ch = token.trim().firstOrNull() ?: return null
        val mapped = DIGIT_MAP[ch] ?: ch
        return mapped.digitToIntOrNull()?.let { it - 1 }
    }

    private fun resolveCloseInstance(text: String, context: JarvisContext): JarvisAction? {
        if (CloseAnaphoraRegex.matches(text)) {
            val anchor = resolveAnchorSession(context)
            return if (anchor != null) {
                JarvisAction.ExecuteTool("close_terminal", JSONObject().put("session_id", anchor.id))
            } else {
                JarvisAction.ExecuteTool("close_terminal")
            }
        }
        val command = text.trimEnd(' ', '。', '！', '!', '，', ',').removeSuffix("吧").trimEnd()
        val objectFirst = CloseInstanceObjectRegex.matchEntire(command)
        val verbFirst = CloseInstancePrefixRegex.find(command)
        val target = objectFirst?.groupValues?.get(1)
            ?: verbFirst?.let { command.substring(it.range.last + 1) }
            ?: return null
        val suffix = CloseInstanceSuffixRegex.find(target)
        val prefix = CloseInstanceTypePrefixRegex.find(target)
        if (suffix == null && prefix == null) return null
        val title = when {
            suffix != null -> target.substring(0, suffix.range.first)
            else -> target.substring(prefix!!.range.last + 1)
        }.trim().removePrefix("名叫").removePrefix("名为").removePrefix("叫")
            .trim(' ', '：', ':', '“', '”', '"', '「', '」', '『', '』')
        if (title.isBlank() || title in setOf("这", "那", "一个", "所有", "全部", "它", "当前", "刚才那个")) {
            if (target in setOf("终端", "当前终端", "会话", "当前会话", "当前实例", "它")) {
                val anchor = resolveAnchorSession(context)
                return if (anchor != null) {
                    JarvisAction.ExecuteTool("close_terminal", JSONObject().put("session_id", anchor.id))
                } else {
                    JarvisAction.ExecuteTool("close_terminal")
                }
            }
            return JarvisAction.DirectReply("请告诉我要关闭哪个工作区。")
        }
        val resolvedWs = resolveWorkspaceTarget(title, context) ?: title
        return JarvisAction.ExecuteTool("close_terminal", JSONObject().put("title", resolvedWs).put("workspace_name", resolvedWs))
    }

    private fun extractInstanceQuery(text: String): String {
        var query = text.replaceFirst(OpenInstancePrefixRegex, "").trim()
        query = query.replace(Regex("""(?i)^(?:(?:一下|下|当前|这个|那个)\s*)*(?:(?:远端|远程|共享)\s*)*(?:工作区\s*)?(?:实例|任务|会话)\s*"""), "")
        query = query.replace(Regex("""(?i)^(?:叫|名称是|名为|名叫|标题是|为|是|[:：])\s*"""), "")
        query = query.replace(Regex("""(?i)\s*(?:(?:远端|远程|共享)\s*)?(?:工作区\s*)?(?:实例|任务|会话)\s*$"""), "")
        return query.trim().trim('“', '”', '"', '「', '」', '『', '』', '。', '！', '!', '?', '？', ' ', '，', ',')
    }

    override suspend fun resolveIntent(text: String, context: JarvisContext): JarvisAction = withContext(Dispatchers.Default) {
        val rawText = dev.remote.terminal.jarvis.perception.JarvisSpeechCorrector.correct(text, isFinal = true).correctedText
        if (rawText.isBlank()) return@withContext JarvisAction.None

        Log.d(TAG, "Resolving intent for input: \"$rawText\"")

        val wakePattern = buildWakePattern()
        val pureWakeRegex = Regex(
            """^(?:(?:喂|嘿|嗨|你好|哈喽|请问|呼叫|胡教|胡作|网络|叫一声|叫下|叫)\s*)*(?:$wakePattern)[，,、\s!！?？.。]*${'$'}""",
            RegexOption.IGNORE_CASE
        )
        if (pureWakeRegex.matches(rawText)) {
            Log.i(TAG, "Pure wake word matched for \"${wakeWordProvider()}\"")
            return@withContext JarvisAction.DirectReply("我在，请讲。")
        }

        // 去除指令句首的唤醒词前缀（如："老六，查看状态" -> "查看状态"）
        val wakeWordPrefix = Regex("""^(?:(?:喂|嘿|嗨|哈喽|呼叫|胡教|胡作|网络|叫)\s*)*(?:$wakePattern)[，,、\s:：]*""", RegexOption.IGNORE_CASE)
        val cleanText = rawText.replace(wakeWordPrefix, "").trim().ifBlank { rawText }

        JarvisScreen.fromCommand(cleanText)?.let { screen ->
            Log.i(TAG, "Routing App navigation locally: ${screen.id}")
            return@withContext JarvisAction.ExecuteTool("navigate_screen", JSONObject().put("screen", screen.id))
        }

        if (GreetingRegex.matches(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Greeting / Presence check (0 Token)")
            return@withContext JarvisAction.DirectReply("我在，听得非常清楚，请指示。")
        }

        if (PraiseRegex.matches(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Praise feedback (0 Token)")
            return@withContext JarvisAction.DirectReply("收到，随时为您效劳。")
        }

        // 0. 立即纠正上一步目标（"不对，是后端" / "不是这个，换成 Claude"）
        val withinCorrectionWindow = context.lastActionTimeMs > 0L &&
            (System.currentTimeMillis() - context.lastActionTimeMs) <= 90_000L
        val corrMatch = CorrectionSlotRegex.matchEntire(cleanText)
        if (corrMatch != null && withinCorrectionWindow) {
            val slotRaw = corrMatch.groupValues[1].trim()
            val slotClean = slotRaw.removePrefix("工作区").removePrefix("开发者").removePrefix("主机").trim()
            val resolvedAgent = resolveAgentTarget(slotClean, context)
            val resolvedWs = if (resolvedAgent == null) resolveWorkspaceTarget(slotClean, context) else null
            val resolvedHost = if (resolvedAgent == null && resolvedWs == null) {
                JarvisEntityResolver.resolveWorkspaceName(slotClean, context.hosts)
            } else null

            when {
                resolvedAgent != null -> {
                    val tool = when (context.lastToolName) {
                        "dispatch_task" -> "dispatch_task"
                        "open_terminal" -> "open_terminal"
                        else -> "select_agent"
                    }
                    val args = JSONObject().put("agent_name", resolvedAgent)
                    if (tool == "dispatch_task" && context.lastWorkspace.isNotBlank() && context.lastTaskPrompt.isNotBlank()) {
                        args.put("workspace_name", context.lastWorkspace)
                        args.put("task_prompt", context.lastTaskPrompt)
                        if (context.lastHost.isNotBlank()) args.put("host_name", context.lastHost)
                    } else if (tool == "open_terminal" && context.lastWorkspace.isNotBlank()) {
                        args.put("workspace_name", context.lastWorkspace)
                        if (context.lastHost.isNotBlank()) args.put("host_name", context.lastHost)
                    }
                    Log.i(TAG, "Tier-1 matched: Immediate slot-swap correction -> $tool(agent=$resolvedAgent)")
                    return@withContext JarvisAction.ExecuteTool(tool, args)
                }
                resolvedWs != null -> {
                    val tool = when (context.lastToolName) {
                        "dispatch_task", "open_terminal", "focus_terminal", "start_watching_task", "get_task_status" -> context.lastToolName
                        else -> "focus_terminal"
                    }
                    val args = JSONObject().put("workspace_name", resolvedWs)
                    if (tool == "dispatch_task" && context.lastTaskPrompt.isNotBlank()) {
                        args.put("task_prompt", context.lastTaskPrompt)
                        if (context.lastAgent.isNotBlank()) args.put("agent_name", context.lastAgent)
                    }
                    Log.i(TAG, "Tier-1 matched: Immediate slot-swap correction -> $tool(workspace=$resolvedWs)")
                    return@withContext JarvisAction.ExecuteTool(tool, args)
                }
                resolvedHost != null -> {
                    Log.i(TAG, "Tier-1 matched: Immediate slot-swap correction -> select_host($resolvedHost)")
                    return@withContext JarvisAction.ExecuteTool("select_host", JSONObject().put("host_name", resolvedHost))
                }
            }
        }

        // 1. Tier-1 本地 0-Token 拦截
        if (ConfirmRegex.matches(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Confirm / Enter (0 Token)")
            return@withContext JarvisAction.ExecuteTool("confirm_terminal", JSONObject().put("approve", true))
        }

        // 智能区分：口头赞许/肯定 vs 待确认回车 vs 继续执行
        if (SoftConfirmRegex.matches(cleanText)) {
            val pendingHost = context.openSessions.firstOrNull { it.pendingHostKey }
            if (pendingHost != null) {
                return@withContext JarvisAction.ExecuteTool(
                    "manage_session_window",
                    JSONObject().put("action", "approve_host_key").put("approve", true).put("session_id", pendingHost.id)
                )
            }
            val needsAction = context.openSessions.firstOrNull { it.needsAction }
            if (needsAction != null) {
                return@withContext JarvisAction.ExecuteTool(
                    "confirm_terminal",
                    JSONObject().put("approve", true).put("session_id", needsAction.id)
                )
            }
            if (cleanText.trim('。', '！', '!', ' ').equals("对", true)) {
                return@withContext JarvisAction.DirectReply("好的，收到。")
            }
            return@withContext JarvisAction.ExecuteTool("confirm_terminal", JSONObject().put("approve", true))
        }

        if (ContinueBriefRegex.matches(cleanText)) {
            val pendingHost = context.openSessions.firstOrNull { it.pendingHostKey }
            if (pendingHost != null) {
                return@withContext JarvisAction.ExecuteTool(
                    "manage_session_window",
                    JSONObject().put("action", "approve_host_key").put("approve", true).put("session_id", pendingHost.id)
                )
            }
            val needsAction = context.openSessions.firstOrNull { it.needsAction }
            if (needsAction != null) {
                return@withContext JarvisAction.ExecuteTool(
                    "confirm_terminal",
                    JSONObject().put("approve", true).put("session_id", needsAction.id)
                )
            }
            val anchor = resolveAnchorSession(context)
            if (anchor != null) {
                if (anchor.isStreaming) {
                    return@withContext JarvisAction.DirectReply("${anchor.workspace} 正在干活，我继续帮你盯着。")
                }
                val args = JSONObject()
                    .put("session_id", anchor.id)
                    .put("workspace_name", anchor.workspace)
                    .put("task_prompt", "继续")
                if (anchor.agent.isNotBlank()) args.put("agent_name", anchor.agent)
                Log.i(TAG, "Tier-1 matched: Smart continue -> dispatch_task(continue) on ${anchor.workspace}")
                return@withContext JarvisAction.ExecuteTool("dispatch_task", args)
            }
            return@withContext JarvisAction.ExecuteTool("confirm_terminal", JSONObject().put("approve", true))
        }

        if (RetryLastRegex.matches(cleanText)) {
            if (context.lastWorkspace.isNotBlank() && context.lastTaskPrompt.isNotBlank()) {
                val args = JSONObject()
                    .put("workspace_name", context.lastWorkspace)
                    .put("task_prompt", context.lastTaskPrompt)
                if (context.lastAgent.isNotBlank()) args.put("agent_name", context.lastAgent)
                if (context.lastHost.isNotBlank()) args.put("host_name", context.lastHost)
                Log.i(TAG, "Tier-1 matched: Retry last task in ${context.lastWorkspace}")
                return@withContext JarvisAction.ExecuteTool("dispatch_task", args)
            }
        }

        if (CancelRegex.matches(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Cancel / SIGINT (0 Token)")
            return@withContext JarvisAction.ExecuteTool("confirm_terminal", JSONObject().put("approve", false))
        }

        resolveCloseInstance(cleanText, context)?.let { return@withContext it }

        // 监工 / 看着干活 (0 Token)
        if (StopWatchRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP stop_watching_task (0 Token)")
            return@withContext JarvisAction.ExecuteTool("stop_watching_task")
        }

        if (WatchStatusRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP get_watch_status (0 Token)")
            return@withContext JarvisAction.ExecuteTool("get_watch_status")
        }

        if (MemoryStatsRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP manage_memory stats (0 Token)")
            return@withContext JarvisAction.ExecuteTool("manage_memory", JSONObject().put("action", "stats"))
        }

        if (StartWatchRegex.containsMatchIn(cleanText)) {
            val matchedWs = context.workspaces.firstOrNull { ws ->
                cleanText.contains(ws, ignoreCase = true)
            } ?: resolveWorkspaceTarget(
                cleanText.replace(StartWatchRegex, "").trim(),
                context
            ) ?: resolveAnchorSession(context)?.workspace
            val args = JSONObject()
            if (!matchedWs.isNullOrBlank()) args.put("workspace_name", matchedWs)
            Log.i(TAG, "Tier-1 matched: MCP start_watching_task [workspace=$matchedWs] (0 Token)")
            return@withContext JarvisAction.ExecuteTool("start_watching_task", args)
        }

        // 指代 / 上一个下一个 / 序号快捷操作 (0 Token)
        if (OpenAnaphoraRegex.matches(cleanText)) {
            val anchor = resolveAnchorSession(context)
            if (anchor != null) {
                return@withContext JarvisAction.ExecuteTool("focus_terminal", JSONObject().put("session_id", anchor.id))
            } else if (context.lastWorkspace.isNotBlank()) {
                return@withContext JarvisAction.ExecuteTool("open_terminal", JSONObject().put("workspace_name", context.lastWorkspace).put("focus", true))
            }
        }

        val prevNextMatch = PrevNextSessionRegex.find(cleanText)
        if (prevNextMatch != null && context.openSessions.isNotEmpty()) {
            val dir = prevNextMatch.groupValues[1]
            val focusedIdx = context.openSessions.indexOfFirst { it.focused }.coerceAtLeast(0)
            val targetIdx = when {
                dir.contains("上") || dir.contains("前") -> (focusedIdx - 1).coerceAtLeast(0)
                dir.contains("最后") -> context.openSessions.lastIndex
                else -> (focusedIdx + 1).coerceAtMost(context.openSessions.lastIndex)
            }
            val targetSession = context.openSessions[targetIdx]
            return@withContext JarvisAction.ExecuteTool("focus_terminal", JSONObject().put("session_id", targetSession.id))
        }

        val ordinalMatch = OrdinalSessionRegex.matchEntire(cleanText)
        if (ordinalMatch != null) {
            val verb = ordinalMatch.groupValues[1].trim()
            val idx = parseChineseOrdinal(ordinalMatch.groupValues[2])
            val allSessions = context.openSessions + context.sharedTasks
            if (idx != null && idx in allSessions.indices) {
                val target = allSessions[idx]
                val suffixQuery = ordinalMatch.groupValues[3].trim()
                return@withContext when {
                    verb.contains("关") -> JarvisAction.ExecuteTool("close_terminal", JSONObject().put("session_id", target.id))
                    verb.contains("盯") || verb.contains("看") && verb == "看着" ->
                        JarvisAction.ExecuteTool("start_watching_task", JSONObject().put("workspace_name", target.workspace).put("session_id", target.id))
                    suffixQuery.isNotBlank() || verb.contains("查") || verb == "看看" || verb == "看下" ->
                        JarvisAction.ExecuteTool("get_task_status", JSONObject().put("session_id", target.id))
                    else -> JarvisAction.ExecuteTool("focus_terminal", JSONObject().put("session_id", target.id))
                }
            }
        }

        // 换开发者重跑（如 "用 Claude 重来" / "换 Codex 试试"）
        val retryWithAgentMatch = RetryWithAgentRegex.matchEntire(cleanText)
        if (retryWithAgentMatch != null) {
            val rawAgent = retryWithAgentMatch.groupValues[1].trim()
            val resolvedAgent = resolveAgentTarget(rawAgent, context)
            if (resolvedAgent != null) {
                val anchor = resolveAnchorSession(context)
                val ws = context.lastWorkspace.ifBlank { anchor?.workspace.orEmpty() }
                val prompt = context.lastTaskPrompt.ifBlank {
                    if (cleanText.contains("接着") || cleanText.contains("继续")) "继续" else ""
                }
                if (ws.isNotBlank() && prompt.isNotBlank()) {
                    val args = JSONObject()
                        .put("agent_name", resolvedAgent)
                        .put("workspace_name", ws)
                        .put("task_prompt", prompt)
                    if (context.lastHost.isNotBlank()) args.put("host_name", context.lastHost)
                    Log.i(TAG, "Tier-1 matched: retry with agent=$resolvedAgent in workspace=$ws")
                    return@withContext JarvisAction.ExecuteTool("dispatch_task", args)
                }
                return@withContext JarvisAction.ExecuteTool("select_agent", JSONObject().put("agent_name", resolvedAgent))
            }
        }

        // 开发者选择与工作区任务派发相关正则 (Tier-1: 0 Token)
        val dispatchDevMatch = DispatchWithDeveloperRegex.find(cleanText)
        if (dispatchDevMatch != null) {
            val rawAgent = dispatchDevMatch.groupValues[1].trim()
            val agent = resolveAgentTarget(rawAgent, context) ?: rawAgent
            val rawWs = dispatchDevMatch.groupValues[2].trim()
            val matchedWs = if (rawWs.isNotBlank()) {
                resolveWorkspaceTarget(rawWs, context) ?: rawWs
            } else {
                resolveAnchorSession(context)?.workspace?.takeIf { it.isNotBlank() }
            }
            val prompt = dispatchDevMatch.groupValues[3].trim()
            Log.i(TAG, "Tier-1 matched: dispatch_task with agent=$agent, workspace=$matchedWs")
            val args = JSONObject().apply {
                put("agent_name", agent)
                if (!matchedWs.isNullOrBlank()) put("workspace_name", matchedWs)
                put("task_prompt", prompt)
            }
            return@withContext JarvisAction.ExecuteTool("dispatch_task", args)
        }

        val openWsDevMatch = OpenWorkspaceWithDeveloperRegex.find(cleanText)
        if (openWsDevMatch != null) {
            val rawAgent = openWsDevMatch.groupValues[1].trim()
            val agent = resolveAgentTarget(rawAgent, context) ?: rawAgent
            val rawWs = openWsDevMatch.groupValues[2].trim()
            val matchedWs = resolveWorkspaceTarget(rawWs, context) ?: rawWs
            Log.i(TAG, "Tier-1 matched: open_terminal with agent=$agent, workspace=$matchedWs")
            val args = JSONObject().apply {
                put("agent_name", agent)
                put("workspace_name", matchedWs)
                put("focus", true)
            }
            return@withContext JarvisAction.ExecuteTool("open_terminal", args)
        }

        val wsDispatchMatch = WorkspaceDispatchRegex.find(cleanText)
        if (wsDispatchMatch != null) {
            val rawWs = wsDispatchMatch.groupValues[1].trim()
            val matchedWs = resolveWorkspaceTarget(rawWs, context) ?: rawWs
            val rawAgent = wsDispatchMatch.groupValues[2].trim().takeIf { it.isNotBlank() }
            val agent = rawAgent?.let { resolveAgentTarget(it, context) ?: it }
            val prompt = wsDispatchMatch.groupValues[3].trim()
            Log.i(TAG, "Tier-1 matched: dispatch_task from workspace=$matchedWs with agent=$agent")
            val args = JSONObject().apply {
                put("workspace_name", matchedWs)
                if (agent != null) put("agent_name", agent)
                put("task_prompt", prompt)
            }
            return@withContext JarvisAction.ExecuteTool("dispatch_task", args)
        }

        val selectDevMatch = SelectDeveloperOnlyRegex.find(cleanText)
        if (selectDevMatch != null) {
            val rawAgent = selectDevMatch.groupValues[1].trim()
            val resolvedAgent = resolveAgentTarget(rawAgent, context)
            if (resolvedAgent != null) {
                Log.i(TAG, "Tier-1 matched: select_agent=$resolvedAgent")
                return@withContext JarvisAction.ExecuteTool("select_agent", JSONObject().put("agent_name", resolvedAgent))
            }
        }

        if (StatusQueryRegex.matches(cleanText) || InstanceStatusQueryRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP get_task_status for local and shared instances")
            return@withContext JarvisAction.ExecuteTool("get_task_status")
        }

        // 工作区/开发者简略状态查询（如 "后端好了吗"、"remote-dev 什么情况"、"Codex 跑完了吗"、"它怎么样了"）
        val targetStatusMatch = TargetStatusRegex.matchEntire(cleanText)
        if (targetStatusMatch != null) {
            val rawTarget = targetStatusMatch.groupValues[1].trim()
            if (rawTarget in setOf("它", "这个", "那个", "刚才那个", "当前这个")) {
                val anchor = resolveAnchorSession(context)
                if (anchor != null) {
                    return@withContext JarvisAction.ExecuteTool("get_task_status", JSONObject().put("session_id", anchor.id))
                }
            }
            val matchedWs = resolveWorkspaceTarget(rawTarget, context)
            if (matchedWs != null) {
                Log.i(TAG, "Tier-1 matched: get_task_status(workspace=$matchedWs)")
                return@withContext JarvisAction.ExecuteTool("get_task_status", JSONObject().put("workspace_name", matchedWs))
            }
            val matchedAgent = resolveAgentTarget(rawTarget, context)
            if (matchedAgent != null) {
                Log.i(TAG, "Tier-1 matched: get_task_status(agent=$matchedAgent)")
                return@withContext JarvisAction.ExecuteTool("get_task_status", JSONObject().put("agent_name", matchedAgent))
            }
        }

        val switchMatch = SwitchWorkspaceRegex.find(cleanText)
        if (switchMatch != null) {
            val targetRaw = switchMatch.groupValues[1].trim()
            val matchedWorkspace = resolveWorkspaceTarget(targetRaw, context)
            if (matchedWorkspace != null) {
                Log.i(TAG, "Tier-1 matched: Switch Workspace to $matchedWorkspace (0 Token)")
                val tool = if (cleanText.contains("打开")) "open_terminal" else "focus_terminal"
                return@withContext JarvisAction.ExecuteTool(tool, JSONObject().put("workspace_name", matchedWorkspace))
            }
        }

        if (ReadScreenRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP read_terminal_screen (0 Token)")
            val anchor = resolveAnchorSession(context)
            val args = JSONObject().put("lines", 15)
            if (anchor != null) args.put("session_id", anchor.id)
            return@withContext JarvisAction.ExecuteTool("read_terminal_screen", args)
        }

        if (ReconnectRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP reconnect_terminal (0 Token)")
            return@withContext JarvisAction.ExecuteTool("reconnect_terminal")
        }

        if (DismissAwakeRegex.matches(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Dismiss awake popup (0 Token)")
            return@withContext JarvisAction.ExecuteTool("close_awake", JSONObject().put("farewell", "好的，随时叫我。"))
        }

        if (TiledViewRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP set_view_mode (tiled=true, 0 Token)")
            return@withContext JarvisAction.ExecuteTool("set_view_mode", JSONObject().put("tiled", true))
        }

        if (TabViewRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP set_view_mode (tiled=false, 0 Token)")
            return@withContext JarvisAction.ExecuteTool("set_view_mode", JSONObject().put("tiled", false))
        }

        if (SyncRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP trigger_sync (0 Token)")
            return@withContext JarvisAction.ExecuteTool("trigger_sync")
        }

        if (PublicKeyRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP get_ssh_public_key (0 Token)")
            return@withContext JarvisAction.ExecuteTool("get_ssh_public_key")
        }

        if (AppStateRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP get_app_state")
            return@withContext JarvisAction.ExecuteTool("get_app_state")
        }

        if (OpenInstancePrefixRegex.containsMatchIn(cleanText) && InstanceTargetRegex.containsMatchIn(cleanText)) {
            val query = extractInstanceQuery(cleanText)
            if (query.isBlank()) {
                Log.i(TAG, "Tier-1 matched: MCP get_task_status before opening an unspecified instance")
                return@withContext JarvisAction.ExecuteTool("get_task_status")
            }
            val resolvedWs = resolveWorkspaceTarget(query, context) ?: query
            Log.i(TAG, "Tier-1 matched: MCP focus_terminal / attach shared instance [$resolvedWs]")
            return@withContext JarvisAction.ExecuteTool(
                "focus_terminal",
                JSONObject().put("query", resolvedWs)
            )
        }

        val hostMatch = SwitchHostRegex.find(cleanText)
        if (hostMatch != null) {
            val targetHostRaw = hostMatch.groupValues[1].trim()
            val resolvedHost = JarvisEntityResolver.resolveWorkspaceName(targetHostRaw, context.hosts) ?: targetHostRaw
            Log.i(TAG, "Tier-1 matched: MCP select_host to $resolvedHost (0 Token)")
            return@withContext JarvisAction.ExecuteTool("select_host", JSONObject().put("host_name", resolvedHost))
        }

        if (HelpRegex.containsMatchIn(cleanText)) {
            return@withContext JarvisAction.ExecuteTool("get_capabilities")
        }

        val webMatch = WebSearchRegex.find(cleanText) ?: DirectSearchRegex.find(cleanText)
        if (webMatch != null) {
            val query = webMatch.groupValues[1].trim().trimEnd('。', '！', '!', '？', '?', ' ')
            if (query.isNotBlank()) {
                Log.i(TAG, "Tier-1 matched: MCP web_search [$query]")
                return@withContext JarvisAction.ExecuteTool("web_search", JSONObject().put("query", query))
            }
        }

        // Tier-1.5 零 Token 个性化默契匹配（由历史高频习惯学习生成）
        tacitHabitMatcher?.invoke(cleanText)?.let { tacitAction ->
            Log.i(TAG, "Tier-1.5 matched tacit user habit for \"$cleanText\" (0 Token)")
            return@withContext tacitAction
        }

        JarvisAction.AskAgent(cleanText)
    }
}
