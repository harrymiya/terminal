package dev.remote.terminal.jarvis.search

import dev.remote.terminal.jarvis.memory.JarvisVectorEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class WebSearchItem(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * 低 Token 消耗的互联网检索与网页精准摘要客户端 (Jarvis Web Search Client)。
 *
 * 控 Token 与高效率设计：
 * 1. 零第三方付费 Key 依赖：内置 Bing (CN/Global) -> DuckDuckGo HTML -> Wikipedia REST API 多引擎瀑布流检索；
 * 2. 端侧本地降噪裁剪：HTML 标签、脚本、导航栏全在端侧毫秒级剥离，永不向大模型投喂原始网页；
 * 3. 严格 Token 上限控制：搜索仅返回 Top 5 高相关精简摘要（总计 <= 1,400 字符 / ~450 Token）；
 * 4. 网页正文向量重排提取：抓取指定 URL 时基于端侧向量余弦相似度只保留与问题最匹配的 Top 4 段落（<= 1,500 字符）；
 * 5. 10 分钟内存结果缓存：重复或追问检索 0 网络开销、0 额外等待。
 */
object JarvisWebSearchClient {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    private const val CACHE_TTL_MS = 10 * 60 * 1_000L
    private const val MAX_CACHE_ENTRIES = 32
    private const val MAX_SEARCH_OUTPUT_CHARS = 1_400
    private const val MAX_PAGE_READ_BYTES = 220 * 1024
    private const val MAX_FETCH_OUTPUT_CHARS = 1_500

    private data class CachedEntry(val text: String, val savedAt: Long)

    private val searchCache = ConcurrentHashMap<String, CachedEntry>()

    suspend fun search(query: String, maxResults: Int = 5): Result<String> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().take(240)
        if (cleanQuery.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("检索关键词不能为空"))
        }

        val cacheKey = cleanQuery.lowercase()
        val cached = searchCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.savedAt < CACHE_TTL_MS) {
            return@withContext Result.success(cached.text)
        }

        val limit = maxResults.coerceIn(1, 5)
        var items = runCatching { searchBing(cleanQuery, limit) }.getOrDefault(emptyList())
        if (items.isEmpty()) {
            items = runCatching { searchDuckDuckGo(cleanQuery, limit) }.getOrDefault(emptyList())
        }
        if (items.isEmpty()) {
            items = runCatching { searchWikipedia(cleanQuery) }.getOrDefault(emptyList())
        }

        if (items.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("未能从互联网获取到与“$cleanQuery”相关的有效结果。"))
        }

        val formatted = formatCompactSearchResults(cleanQuery, items)
        if (searchCache.size >= MAX_CACHE_ENTRIES) {
            val oldest = searchCache.entries.minByOrNull { it.value.savedAt }?.key
            if (oldest != null) searchCache.remove(oldest)
        }
        searchCache[cacheKey] = CachedEntry(formatted, System.currentTimeMillis())
        Result.success(formatted)
    }

    suspend fun fetchRelevantPassages(rawUrl: String, query: String = ""): Result<String> = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull()
        if (uri == null || (uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank()) {
            return@withContext Result.failure(IllegalArgumentException("无效的网页链接：$rawUrl"))
        }
        val html = runCatching { httpGetText(uri.toASCIIString(), MAX_PAGE_READ_BYTES) }
            .getOrElse { return@withContext Result.failure(it) }

        val cleanText = stripHtmlToCleanParagraphs(html)
        if (cleanText.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("网页正文为空或受保护无法提取。"))
        }

        val selected = if (query.isBlank()) {
            cleanText.take(4)
        } else {
            val qVec = JarvisVectorEmbedder.embed(query)
            cleanText.map { para ->
                val pVec = JarvisVectorEmbedder.embed(para)
                val sim = JarvisVectorEmbedder.cosineSimilarity(qVec, pVec)
                para to sim
            }.sortedByDescending { it.second }.take(4).map { it.first }
        }

        val compact = buildString {
            appendLine("网页核心摘录（${uri.host}）：")
            for (para in selected) {
                val line = "- ${para.take(360)}\n"
                if (length + line.length > MAX_FETCH_OUTPUT_CHARS) break
                append(line)
            }
        }.trim()

        Result.success(compact)
    }

    private fun searchBing(query: String, limit: Int): List<WebSearchItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = httpGetText("https://cn.bing.com/search?q=$encoded&ensearch=0", MAX_PAGE_READ_BYTES)
        val algoRegex = Regex("""<li class="b_algo"[^>]*>(.*?)</li>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val h2Regex = Regex("""<h2[^>]*>(.*?)</h2>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val hrefRegex = Regex("""href="(https?://[^"]+)"""", RegexOption.IGNORE_CASE)
        val pRegex = Regex("""<p[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        val results = mutableListOf<WebSearchItem>()
        for (match in algoRegex.findAll(html)) {
            val block = match.groupValues[1]
            val h2 = h2Regex.find(block)?.groupValues?.get(1).orEmpty()
            val title = cleanHtmlText(h2).take(80)
            val url = decodeHtmlEntities(hrefRegex.find(h2)?.groupValues?.get(1).orEmpty())
            val snippetRaw = pRegex.find(block)?.groupValues?.get(1).orEmpty()
            val snippet = cleanHtmlText(snippetRaw).take(180)
            if (title.isNotBlank() && snippet.isNotBlank()) {
                results.add(WebSearchItem(title, url, snippet))
                if (results.size >= limit) break
            }
        }
        return results
    }

    private fun searchDuckDuckGo(query: String, limit: Int): List<WebSearchItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = httpGetText("https://html.duckduckgo.com/html/?q=$encoded", MAX_PAGE_READ_BYTES)
        val linkRegex = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val snippetRegex = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        val links = linkRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).toList()
        val results = mutableListOf<WebSearchItem>()
        for (i in links.indices) {
            val url = decodeHtmlEntities(links[i].groupValues[1])
            val title = cleanHtmlText(links[i].groupValues[2]).take(80)
            val snippet = cleanHtmlText(snippets.getOrNull(i)?.groupValues?.get(1).orEmpty()).take(180)
            if (title.isNotBlank() && snippet.isNotBlank()) {
                results.add(WebSearchItem(title, url, snippet))
                if (results.size >= limit) break
            }
        }
        return results
    }

    private fun searchWikipedia(query: String): List<WebSearchItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val jsonStr = httpGetText("https://zh.wikipedia.org/api/rest_v1/page/summary/$encoded", 64 * 1024)
        val json = JSONObject(jsonStr)
        val title = json.optString("title").trim()
        val extract = json.optString("extract").trim()
        val pageUrl = json.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page").orEmpty()
        if (title.isBlank() || extract.isBlank()) return emptyList()
        return listOf(WebSearchItem(title.take(80), pageUrl, extract.take(260)))
    }

    private fun formatCompactSearchResults(query: String, items: List<WebSearchItem>): String = buildString {
        appendLine("联网检索“$query”精简摘要：")
        items.forEachIndexed { idx, item ->
            val host = runCatching { URI(item.url).host }.getOrNull().orEmpty().removePrefix("www.")
            val sourceTag = if (host.isNotBlank()) " ($host)" else ""
            val entry = "${idx + 1}. ${item.title}$sourceTag：${item.snippet}\n"
            if (length + entry.length > MAX_SEARCH_OUTPUT_CHARS) return@forEachIndexed
            append(entry)
        }
    }.trim()

    private fun httpGetText(urlStr: String, maxBytes: Int): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 4_500
            readTimeout = 6_000
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            val stream = conn.inputStream
            val buf = ByteArray(4096)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (total < maxBytes) {
                val n = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toString("UTF-8")
        } finally {
            conn.disconnect()
        }
    }

    private fun stripHtmlToCleanParagraphs(html: String): List<String> {
        val noScripts = html
            .replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<(nav|footer|header|aside|svg)[\s\S]*?</\1>""", RegexOption.IGNORE_CASE), " ")
        val withNewlines = noScripts
            .replace(Regex("""</?(p|div|li|h[1-6]|br|tr|article|section)[^>]*>""", RegexOption.IGNORE_CASE), "\n")
        val plain = cleanHtmlTextPreservingNewlines(withNewlines)
        return plain.lines()
            .map { it.trim() }
            .filter { it.length >= 24 }
            .distinct()
            .take(40)
    }

    private fun cleanHtmlText(raw: String): String {
        val noTags = raw.replace(Regex("""<[^>]+>"""), " ")
        return decodeHtmlEntities(noTags).replace(Regex("""\s+"""), " ").trim()
    }

    private fun cleanHtmlTextPreservingNewlines(raw: String): String {
        val noTags = raw.replace(Regex("""<[^>]+>"""), " ")
        return decodeHtmlEntities(noTags)
            .replace(Regex("""[ \t\r]+"""), " ")
            .replace(Regex("""\n\s*\n+"""), "\n")
            .trim()
    }

    private fun decodeHtmlEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace("&#x27;", "'")
        .replace("&#x2F;", "/")
}
