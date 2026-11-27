package dev.remote.terminal.jarvis.intent

import android.util.Log
import dev.remote.terminal.jarvis.api.IntentRouter
import dev.remote.terminal.jarvis.api.JarvisAction
import dev.remote.terminal.jarvis.api.JarvisContext
import dev.remote.terminal.jarvis.navigation.JarvisScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Resolves deterministic App actions before a request is handed to the App Agent. */
class JarvisIntentRouter(
    private val wakeWordProvider: () -> String = { "贾维斯" }
) : IntentRouter {

    companion object {
        private const val TAG = "JarvisIntentRouter"

        // 本地极速意图匹配正则 (Tier-1: 0 Token)
        private val ConfirmRegex = Regex(
            """(?i)^\s*(?:允许|确认|同意|继续|执行|跑吧|回车|好的|可以|对|嗯|好|行|确定|approve|yes|confirm|proceed|ok|enter)\s*[:?？!！.。]*$"""
        )

        private val CancelRegex = Regex(
            """(?i)^\s*(?:取消|停止|中止|算了|别动|停|住手|别执行|cancel|stop|abort|no|quit)\s*[:?？!！.。]*$"""
        )

        private val SwitchWorkspaceRegex = Regex(
            """(?i)^\s*(?:切换到|切到|换到|打开工作区|切到工作区)\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\.]+)\s*[。！!]*$"""
        )

        private val StatusQueryRegex = Regex(
            """(?i)^\s*(?:现在|目前)?(?:什么情况|进度如何|进度怎么样|好了吗|完成了吗|当前状态|状态怎么样|status)[?？!！.。]*$"""
        )

        private val InstanceStatusQueryRegex = Regex(
            """(?i)(?:(?:请|帮我)?(?:查看|看下|看一下|看看|查查|查一下|查询|列出|汇报|告诉我|了解).{0,12}(?:工作区\s*)?(?:远端|远程|本地)?(?:实例|任务|会话)|(?:工作区\s*)?(?:远端|远程|本地)?(?:实例|任务|会话)(?:的)?(?:(?:现在|目前|当前|实时)?(?:列表|状态(?:怎么样|如何|是什么)?|进度(?:如何|怎么样)?|情况)|最近在做什么|正在做什么|在做什么))"""
        )

        private val ReadScreenRegex = Regex(
            """(?i)(?:读屏幕|看屏幕|念屏幕|屏幕内容|显示什么|输出什么)"""
        )

        private val ReconnectRegex = Regex(
            """(?i)(?:重连|重新连接|断线重连)"""
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
            """(?i)(?:切换到主机|换到主机|切到主机|换主机)\s*([a-zA-Z0-9_\u4e00-\u9fa5\-\.]+)"""
        )

        private val HelpRegex = Regex(
            """(?i)^(?:请)?(?:你能做什么|你能做些什么|你会做什么|你会什么|你有什么功能|你有哪些功能|你有哪些能力|你能调用哪些工具|帮助|功能|指令|help|who are you)[\s?？!！.。]*$"""
        )

        private val GreetingRegex = Regex(
            """(?i)^\s*(?:在吗|你在吗|在不在|你好|您好|听得到吗|听得见吗|能听见吗|能听到吗|早上好|晚上好|喂)[?？!！.。]*${'$'}"""
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

    private fun resolveCloseInstance(text: String): JarvisAction? {
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
        if (title.isBlank() || title in setOf("这", "那", "一个", "所有", "全部")) {
            if (target in setOf("终端", "当前终端", "会话", "当前会话")) {
                return JarvisAction.ExecuteTool("close_terminal")
            }
            return JarvisAction.DirectReply("请告诉我要关闭的实例名称。")
        }
        return JarvisAction.ExecuteTool("close_terminal", JSONObject().put("title", title))
    }

    private fun extractInstanceQuery(text: String): String {
        var query = text.replaceFirst(OpenInstancePrefixRegex, "").trim()
        query = query.replace(Regex("""(?i)^(?:(?:一下|下|当前|这个|那个)\s*)*(?:(?:远端|远程|共享)\s*)*(?:工作区\s*)?(?:实例|任务|会话)\s*"""), "")
        query = query.replace(Regex("""(?i)^(?:叫|名称是|名为|名叫|标题是|为|是|[:：])\s*"""), "")
        query = query.replace(Regex("""(?i)\s*(?:(?:远端|远程|共享)\s*)?(?:工作区\s*)?(?:实例|任务|会话)\s*$"""), "")
        return query.trim().trim('“', '”', '"', '「', '」', '『', '』', '。', '！', '!', '?', '？', ' ', '，', ',')
    }

    override suspend fun resolveIntent(text: String, context: JarvisContext): JarvisAction = withContext(Dispatchers.Default) {
        val rawText = text.trim()
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

        // 1. Tier-1 本地 0-Token 拦截
        if (ConfirmRegex.matches(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Confirm / Enter (0 Token)")
            return@withContext JarvisAction.ExecuteTool("confirm_terminal", JSONObject().put("approve", true))
        }

        if (CancelRegex.matches(cleanText)) {
            Log.i(TAG, "Tier-1 matched: Cancel / SIGINT (0 Token)")
            return@withContext JarvisAction.ExecuteTool("confirm_terminal", JSONObject().put("approve", false))
        }

        resolveCloseInstance(cleanText)?.let { return@withContext it }

        val switchMatch = SwitchWorkspaceRegex.find(cleanText)
        if (switchMatch != null) {
            val targetRaw = switchMatch.groupValues[1].trim()
            val matchedWorkspace = context.workspaces.firstOrNull { ws ->
                ws.equals(targetRaw, ignoreCase = true) || ws.contains(targetRaw, ignoreCase = true)
            }
            if (matchedWorkspace != null) {
                Log.i(TAG, "Tier-1 matched: Switch Workspace to $matchedWorkspace (0 Token)")
                val tool = if (cleanText.startsWith("打开工作区")) "open_terminal" else "focus_terminal"
                return@withContext JarvisAction.ExecuteTool(tool, JSONObject().put("workspace_name", matchedWorkspace))
            }
        }

        if (ReadScreenRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP read_terminal_screen (0 Token)")
            return@withContext JarvisAction.ExecuteTool("read_terminal_screen", JSONObject().put("lines", 15))
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
            Log.i(TAG, "Tier-1 matched: MCP focus_terminal / attach shared instance [$query]")
            return@withContext JarvisAction.ExecuteTool(
                "focus_terminal",
                JSONObject().put("query", query)
            )
        }

        val hostMatch = SwitchHostRegex.find(cleanText)
        if (hostMatch != null) {
            val targetHost = hostMatch.groupValues[1].trim()
            Log.i(TAG, "Tier-1 matched: MCP select_host to $targetHost (0 Token)")
            return@withContext JarvisAction.ExecuteTool("select_host", JSONObject().put("host_name", targetHost))
        }

        if (StatusQueryRegex.matches(cleanText) || InstanceStatusQueryRegex.containsMatchIn(cleanText)) {
            Log.i(TAG, "Tier-1 matched: MCP get_task_status for local and shared instances")
            return@withContext JarvisAction.ExecuteTool("get_task_status")
        }

        if (HelpRegex.containsMatchIn(cleanText)) {
            return@withContext JarvisAction.ExecuteTool("get_capabilities")
        }

        JarvisAction.AskAgent(cleanText)
    }
}
