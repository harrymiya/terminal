package dev.remote.terminal.jarvis.perception

import dev.remote.terminal.data.AgentPreset
import dev.remote.terminal.data.HostProfile
import dev.remote.terminal.jarvis.intent.JarvisEntityResolver
import kotlin.math.min

/**
 * 飞书级语音识别后纠错与热词实体对齐引擎 (Jarvis Speech Corrector)。
 *
 * 针对端侧 Paraformer / 语音识别常见痛点进行毫秒级（< 1ms, 0 Token）纠错：
 * 1. 领域高频同音/近音错词纠偏（如：看着干活、派发任务、工作区、开发者、监工、退出唤醒态等）；
 * 2. 开发者与技术栈中英文音译还原（如：克劳德->Claude、扣代克斯->Codex、欧鹏扣->OpenCode、阿基->agy）；
 * 3. 基于 App 真实工作区/主机/开发者名称的动态模糊音似匹配（Fuzzy Phonetic & Edit-Distance Alignment）；
 * 4. 语气词与断句空格规整，输出高可读性的语义文本供意图路由与大模型使用。
 */
object JarvisSpeechCorrector {

    var wakeWordProvider: () -> String = { "贾维斯" }
    var workspacesProvider: () -> List<String> = { emptyList() }
    var hostsProvider: () -> List<HostProfile> = { emptyList() }
    var agentsProvider: () -> List<AgentPreset> = { emptyList() }

    data class CorrectionResult(
        val correctedText: String,
        val rawText: String,
        val wasModified: Boolean
    )

    // 1. 唤醒词常见同音误识别映射
    private val wakeHomophoneRegex = Regex(
        """(?:假维斯|家维斯|加维斯|查维斯|佳维斯|甲维斯|嘉维斯|价维斯|架维斯|夏维斯|甲微斯|贾微斯|贾维思|假维思|家维思)"""
    )

    // 2. 开发者/AI 工具音译与英文连写纠错规则
    private val techTermRules: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)(?:扣代克斯|可代克斯|考代克斯|科代克斯|扣代克思|可代克|扣带克斯|扣得克斯|扣代的|扣代|扣德|code\s*x|co\s*dex|codex)""") to "Codex",
        Regex("""(?i)(?:克劳德|可劳德|卡劳德|克劳的|克老德|可老德|科劳德|克罗德|可劳|克劳|claud\b|clod\b|claude)""") to "Claude",
        Regex("""(?i)(?:欧鹏扣|欧朋扣|欧彭扣|偶鹏扣|欧鹏口|欧鹏|奥鹏|open\s*code|opencode)""") to "OpenCode",
        Regex("""(?i)(?:安提格拉维蒂|安提格拉维|阿基|阿吉|艾吉|a\s*g\s*y\b)""") to "agy",
        Regex("""(?i)(?:杰米尼|吉米尼|杰米奈|吉米奈|gemini)""") to "Gemini",
        Regex("""(?i)(?:吉特哈布|给特哈布|git\s*hub)""") to "GitHub",
        Regex("""(?i)(?:给特|吉特)(?=\s*(?:拉取|推送|提交|状态|分支|pull|push|commit|status|diff|log))""") to "git",
        Regex("""(?i)\bgit\s*(?:普尔|扑尔|破)\b""") to "git pull",
        Regex("""(?i)\bgit\s*(?:普什|扑食)\b""") to "git push",
        Regex("""(?i)(?:艾斯丘莱特|爱思Q莱特|sql\s*lite|sqlite)""") to "SQLite",
        Regex("""(?i)(?:安卓|安桌|android)""") to "Android",
        Regex("""(?i)(?:派森|排森|python)""") to "Python"
    )

    // 3. 高频操作指令同音纠偏规则
    private val commandPhraseRules: List<Pair<Regex, String>> = listOf(
        Regex("""(?:看这干活|看者干活|看字干活|看住干活|看着敢活|看着干火|看着赶活)""") to "看着干活",
        Regex("""(?:派发热舞|拍发任务|排发任务|派法任务|派发人务|拍法任务)""") to "派发任务",
        Regex("""(?:工作去|工作取|功作区|公做区|攻做区|工作驱)""") to "工作区",
        Regex("""(?:开法者|开发着|凯发者|开发展|开法着)""") to "开发者",
        Regex("""(?:终断|中端|重端|忠端)(?=页|页面|里|里面|上|输出|屏幕|关闭|打开|重连|\s*$)""") to "终端",
        Regex("""(?:重联|从连|虫连)""") to "重连",
        Regex("""(?:退出环醒|退出幻醒|退出唤星|关闭环醒|关闭幻醒)(?:态|状态)?""") to "退出唤醒态",
        Regex("""(?:度屏幕|堵屏幕|督屏幕|读屏木|看屏木)""") to "读屏幕",
        Regex("""(?<=(?:开始|停止|结束|查看|帮我|继续|在|处于))\s*(?:建工|兼工|见工|件工|简工)""") to "监工",
        Regex("""(?:建工|兼工|见工|件工)(?=状态|进度|一下|吧)""") to "监工",
        Regex("""(?:平普模式|评铺模式|屏铺模式|切到平普|切换平普)""") to "平铺模式",
        Regex("""(?:标前模式|表签模式|标千模式|切换标前)""") to "标签模式",
        Regex("""(?:却认|缺认|确人)(?=\s*$|一下|执行|操作|吧)""") to "确认",
        Regex("""(?:帮我听一下|帮我定一下|帮我丁一下)(?=.*(?:任务|工作区|干活|代码|构建|编译))""") to "帮我盯一下",
        Regex("""(?:在马|在嘛|你在马|你在嘛)(?=[？?\s]*$)""") to "在吗",
        Regex("""(?:盯紧一点|顶紧点|定紧点|丁紧点|看着一点|盯这|顶着)(?=(?:吧|啊|它|工作区|任务|\s*$))""") to "盯着点",
        Regex("""(?:搞定了没|搞定了吗|弄好了没|弄好了吗|跑得咋样|跑的咋样|跑的怎么样|干的怎么样)""") to "进度怎么样"
    )

    // 常见中英文声母/音节近似映射，用于动态实体（工作区名/主机名/开发者名）纠偏
    private val phoneticMap: Map<String, String> = mapOf(
        "白肯" to "back", "拜肯" to "back", "百肯" to "back", "白肯德" to "backend", "百肯德" to "backend",
        "弗朗特" to "front", "福朗特" to "front", "前段" to "前端",
        "瑞莫特" to "remote", "雷莫特" to "remote", "远端" to "remote",
        "特米诺" to "terminal", "特米努" to "terminal", "终端" to "terminal",
        "戴夫" to "dev", "代夫" to "dev", "德夫" to "dev",
        "艾普" to "app", "阿普" to "app",
        "爱欧斯" to "ios", "安卓" to "android",
        "乌班图" to "ubuntu", "友班图" to "ubuntu",
        "欧玛奇" to "omarchy", "温豆丝" to "windows",
        "派" to "pi", "扣" to "code"
    )

    // 句首或句尾常见口语迟疑语气词（避免干扰指令匹配）
    private val leadingFillerRegex = Regex("""^(?:[嗯呃啊哦额那个就是然后]\s*[,，、]?\s*)+""")
    private val trailingFillerRegex = Regex("""(?<=[\u4e00-\u9fa5a-zA-Z0-9])\s*(?:[啊呀呢吧嘛]+)\s*$""")

    fun correct(rawText: String, isFinal: Boolean = true): CorrectionResult {
        val original = rawText.trim()
        if (original.isBlank()) return CorrectionResult("", original, false)

        var text = original
            .replace(Regex("""(?<=[\u4e00-\u9fa5])\s+(?=[\u4e00-\u9fa5])"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        // Step 1: 唤醒词同音修正
        val wakeWord = wakeWordProvider().trim().ifBlank { "贾维斯" }
        text = wakeHomophoneRegex.replace(text, wakeWord)

        // Step 2: 领域技术名词 & 开发者名音译纠错
        for ((pattern, replacement) in techTermRules) {
            text = pattern.replace(text, replacement)
        }

        // Step 3: 高频指令短语同音纠错
        for ((pattern, replacement) in commandPhraseRules) {
            text = pattern.replace(text, replacement)
        }
        // 专项处理“建工/兼工/见工”
        text = text.replace(Regex("""(开始|停止|结束|查看|帮我|继续)\s*(?:建工|兼工|见工|件工|简工)""")) {
            "${it.groupValues[1]}监工"
        }

        // Step 4: 仅在最终句（或长句）执行动态实体与口语清洗，避免预览态抖动
        if (isFinal) {
            text = alignWithDynamicEntities(text)
            text = text.replace(leadingFillerRegex, "").trim()
            // 仅在指令型短句末尾移除多余语气词，避免破坏正常长句提问
            if (text.length <= 24 && !text.endsWith("吗") && !text.endsWith("呢")) {
                text = text.replace(trailingFillerRegex, "").trim()
            }
        }

        // Step 5: 中英文混排空格规整
        text = text
            .replace(Regex("""([\u4e00-\u9fa5])([A-Za-z0-9])"""), "$1 $2")
            .replace(Regex("""([A-Za-z0-9])([\u4e00-\u9fa5])"""), "$1 $2")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val finalOut = text.ifBlank { original }
        return CorrectionResult(
            correctedText = finalOut,
            rawText = original,
            wasModified = finalOut != original
        )
    }

    /**
     * 将语音识别文本中的近似音译或大小写偏差，对齐到 App 当前真实存在的工作区、开发者、主机名称。
     */
    private fun alignWithDynamicEntities(input: String): String {
        var text = input

        // 先应用常见中英文音节片段映射（如 "白肯德" -> "backend"）
        for ((zhPhonetic, enToken) in phoneticMap) {
            if (text.contains(zhPhonetic)) {
                val hasMatchingEntity = hasEntityContaining(enToken)
                if (hasMatchingEntity) {
                    text = text.replace(zhPhonetic, enToken, ignoreCase = true)
                }
            }
        }

        // 1. 对动态开发者名称进行大小写、音译与近拼写对齐
        val agents = runCatching { agentsProvider() }.getOrDefault(emptyList())
        for (agent in agents) {
            val candidates = listOf(agent.name, agent.command).filter { it.length >= 2 }
            for (cand in candidates) {
                if (text.contains(cand, ignoreCase = true) && !text.contains(cand, ignoreCase = false)) {
                    text = text.replace(Regex(Regex.escape(cand), RegexOption.IGNORE_CASE), cand)
                }
            }
        }
        text = fuzzyAlignTokens(text, agents.map { it.name }.filter { it.length >= 3 }, minSliceLen = 3, threshold = 0.78)

        // 2. 对动态主机名称进行模糊对齐
        val hosts = runCatching { hostsProvider() }.getOrDefault(emptyList())
        text = fuzzyAlignTokens(text, hosts.map { it.name }.filter { it.length >= 3 }, minSliceLen = 3, threshold = 0.78)

        // 3. 对动态工作区名称进行模糊匹配对齐（如识别出 "remote dev terminal" -> "remote-dev-terminal"）
        val workspaces = runCatching { workspacesProvider() }.getOrDefault(emptyList())
        text = fuzzyAlignTokens(text, workspaces.filter { it.length >= 3 }, minSliceLen = 4, threshold = 0.75)

        return text
    }

    private fun fuzzyAlignTokens(
        input: String,
        entities: List<String>,
        minSliceLen: Int,
        threshold: Double
    ): String {
        if (entities.isEmpty()) return input
        var text = input
        for (entity in entities) {
            val normalizedEntity = JarvisEntityResolver.normalizeKey(entity)
            if (normalizedEntity.length < minSliceLen) continue
            val tokens = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) continue
            for (i in tokens.indices) {
                for (len in 1..min(3, tokens.size - i)) {
                    val slice = tokens.subList(i, i + len).joinToString(" ")
                    val sliceNorm = JarvisEntityResolver.normalizeKey(slice)
                    if (sliceNorm.length >= minSliceLen &&
                        !sliceNorm.equals(normalizedEntity, ignoreCase = false) &&
                        JarvisEntityResolver.similarity(sliceNorm, normalizedEntity) >= threshold
                    ) {
                        text = text.replace(slice, entity, ignoreCase = true)
                        break
                    }
                }
            }
        }
        return text
    }

    private fun hasEntityContaining(token: String): Boolean {
        val workspaces = runCatching { workspacesProvider() }.getOrDefault(emptyList())
        if (workspaces.any { it.contains(token, ignoreCase = true) }) return true
        val hosts = runCatching { hostsProvider() }.getOrDefault(emptyList())
        if (hosts.any { it.name.contains(token, ignoreCase = true) }) return true
        val agents = runCatching { agentsProvider() }.getOrDefault(emptyList())
        if (agents.any { it.name.contains(token, ignoreCase = true) || it.command.contains(token, ignoreCase = true) }) return true
        return false
    }
}
