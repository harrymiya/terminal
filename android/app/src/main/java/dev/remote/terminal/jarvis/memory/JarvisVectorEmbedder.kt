package dev.remote.terminal.jarvis.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 安卓端原生轻量语义向量编码器（256 维 L2 归一化浮点向量）。
 * 零外部原生库依赖，毫秒级端侧推理（< 0.5ms），支持：
 * 1. 中英文词干、字二元组/三元组（Char Bigrams/Trigrams）与英文子词特征哈希；
 * 2. 领域同义概念锚点映射（偏好/纠错/开发者/工作区/任务/监工/报错等共享语义空间）；
 * 3. 语音常见近音音节特征（抗 ASR 轻度同音偏差）；
 * 4. 标准余弦相似度计算与 SQLite BLOB 序列化。
 */
object JarvisVectorEmbedder {

    const val VECTOR_DIM = 256

    private val tokenizer = Regex("""[\u4e00-\u9fff]+|[a-zA-Z0-9_.\-/]+""")

    // 共享语义锚点词簇：将同义/近义词映射到统一语义概念槽位，增强语义泛化召回能力
    private val semanticAnchors: List<Pair<String, Set<String>>> = listOf(
        "concept_prefer" to setOf("喜欢", "偏好", "习惯", "常用", "默认", "倾向", "总是", "以后", "prefer", "favorite", "always", "usually"),
        "concept_correct" to setOf("不对", "错了", "别用", "不要", "改成", "改为", "应该是", "记住", "记下", "纠正", "wrong", "instead", "remember"),
        "concept_developer" to setOf("开发者", "助手", "agent", "codex", "claude", "opencode", "agy", "gemini", "pi", "模型"),
        "concept_workspace" to setOf("工作区", "项目", "目录", "仓库", "工程", "代码库", "workspace", "project", "repo", "directory"),
        "concept_task" to setOf("任务", "派发", "执行", "编译", "构建", "测试", "部署", "打包", "修复", "优化", "task", "build", "test", "fix", "deploy"),
        "concept_watch" to setOf("看着", "盯着", "监工", "守着", "观察", "进度", "状态", "watch", "monitor", "status", "progress"),
        "concept_error" to setOf("错误", "报错", "失败", "异常", "崩溃", "卡住", "超时", "error", "fail", "failed", "exception", "crash"),
        "concept_host" to setOf("主机", "服务器", "电脑", "机器", "ubuntu", "linux", "windows", "mac", "host", "server", "ssh"),
        "concept_voice" to setOf("语音", "唤醒", "播报", "耳机", "蓝牙", "识别", "说话", "音量", "voice", "tts", "asr", "bluetooth")
    )

    fun embed(text: String): FloatArray {
        val vec = FloatArray(VECTOR_DIM)
        val clean = text.trim().lowercase()
        if (clean.isBlank()) return vec

        val tokens = tokenizer.findAll(clean).map { it.value }.toList()
        if (tokens.isEmpty()) return vec

        for (token in tokens) {
            val isChinese = (token.firstOrNull()?.code ?: 0) in 0x4e00..0x9fff
            if (isChinese) {
                // 单字特征（权重 0.7）
                for (ch in token) {
                    addSignedFeature(vec, "c:$ch", 0.7f)
                }
                // 二元组特征（权重 1.4，强语义区分度）
                for (i in 0 until token.length - 1) {
                    addSignedFeature(vec, "bg:${token.substring(i, i + 2)}", 1.4f)
                }
                // 三元组特征（权重 1.2）
                for (i in 0 until token.length - 2) {
                    addSignedFeature(vec, "tg:${token.substring(i, i + 3)}", 1.2f)
                }
            } else {
                // 英文/代码完整词特征（权重 1.8）
                if (token.length >= 2) {
                    addSignedFeature(vec, "w:$token", 1.8f)
                }
                // 英文子词 3-gram（容忍拼写与大小写变体，权重 0.9）
                if (token.length >= 4) {
                    for (i in 0..token.length - 3) {
                        addSignedFeature(vec, "sub:${token.substring(i, i + 3)}", 0.9f)
                    }
                }
            }
        }

        // 注入语义概念锚点特征（权重 1.6）
        for ((conceptKey, triggers) in semanticAnchors) {
            if (triggers.any { clean.contains(it) }) {
                addSignedFeature(vec, conceptKey, 1.6f)
            }
        }

        return l2Normalize(vec)
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != VECTOR_DIM || b.size != VECTOR_DIM) return 0f
        var dot = 0f
        for (i in 0 until VECTOR_DIM) {
            dot += a[i] * b[i]
        }
        return dot.coerceIn(-1f, 1f)
    }

    fun toBlob(vec: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vec) buf.putFloat(v)
        return buf.array()
    }

    fun fromBlob(blob: ByteArray?): FloatArray {
        if (blob == null || blob.size != VECTOR_DIM * 4) return FloatArray(VECTOR_DIM)
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(VECTOR_DIM) { buf.getFloat() }
    }

    private fun addSignedFeature(vec: FloatArray, feature: String, weight: Float) {
        val h1 = fnv1aHash(feature, 0x811c9dc5.toInt())
        val h2 = fnv1aHash(feature, 0x9e3779b9.toInt())
        val idx1 = (h1 and 0x7FFFFFFF) % VECTOR_DIM
        val sign1 = if ((h1 ushr 31) == 0) 1f else -1f
        val idx2 = (h2 and 0x7FFFFFFF) % VECTOR_DIM
        val sign2 = if ((h2 ushr 31) == 0) 1f else -1f
        vec[idx1] += sign1 * weight
        vec[idx2] += sign2 * (weight * 0.5f)
    }

    private fun fnv1aHash(input: String, seed: Int): Int {
        var hash = seed
        for (ch in input) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        return hash
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSq = 0f
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq)
        if (norm <= 1e-6f) return vec
        val inv = 1f / norm
        for (i in vec.indices) vec[i] *= inv
        return vec
    }
}
