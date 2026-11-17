package com.example.audio.web

import android.content.Context
import com.example.audio.OnlineGuard
import com.example.model.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

sealed interface ArticleExtractionResult {
    data class Success(val text: String) : ArticleExtractionResult
    data class Offline(val message: String) : ArticleExtractionResult
    data object EmptyOrFailed : ArticleExtractionResult
}

object WebArticleExtractor {

    /**
     * Online-only web article extractor guarded by [OnlineGuard].
     * Uses Jina AI Reader (`https://r.jina.ai/{url}`) and strips markdown/noise cleanly for TTS.
     */
    suspend fun extractCleanTextFromUrl(
        context: Context,
        pageUrl: String,
        language: AppLanguage = AppLanguage.AR,
    ): ArticleExtractionResult = withContext(Dispatchers.IO) {
        if (!OnlineGuard.isOnline(context)) {
            return@withContext ArticleExtractionResult.Offline(OnlineGuard.offlineErrorMessage(language))
        }
        val text = extractCleanTextFromUrl(pageUrl)
        if (text.isBlank()) {
            ArticleExtractionResult.EmptyOrFailed
        } else {
            ArticleExtractionResult.Success(text)
        }
    }

    /**
     * Fetches a web page and extracts clean readable article text suitable for
     * natural human TTS / Podcast narration.
     * Primary engine: Jina Reader API (`https://r.jina.ai/{url}`) with clean Markdown stripping.
     * Fallback engine: Direct HTML fetch with script/style/navigation tag stripping.
     */
    suspend fun extractCleanTextFromUrl(pageUrl: String): String = withContext(Dispatchers.IO) {
        val trimmed = pageUrl.trim()
        if (trimmed.isEmpty()) return@withContext ""
        val normalizedUrl = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }

        // 1. Primary: Jina AI clean reader (handles JS-heavy sites, news, Wikipedia, blogs)
        val jinaDirect = fetchViaJinaReader("https://r.jina.ai/$normalizedUrl", requestPlainText = true)
        if (jinaDirect.length >= 40) {
            return@withContext jinaDirect.take(6000)
        }

        val jinaMarkdown = fetchViaJinaReader("https://r.jina.ai/$normalizedUrl", requestPlainText = false)
        if (jinaMarkdown.length >= 40) {
            return@withContext jinaMarkdown.take(6000)
        }

        // 2. Fallback: Direct HTML fetch + HTML tag/script stripping
        return@withContext fetchAndStripDirectHtml(normalizedUrl)
    }

    private fun fetchViaJinaReader(jinaUrl: String, requestPlainText: Boolean): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(jinaUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) SonixStudio/1.0")
                setRequestProperty("Accept", "text/plain, text/markdown;q=0.9, */*;q=0.5")
                if (requestPlainText) {
                    setRequestProperty("X-Return-Format", "text")
                }
                setRequestProperty("X-No-Cache", "true")
                connectTimeout = 9000
                readTimeout = 14000
            }
            if (conn.responseCode !in 200..299) return ""
            val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            cleanJinaText(raw)
        } catch (_: Throwable) {
            ""
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun cleanJinaText(raw: String): String {
        val lines = raw.lines()
        val filtered = ArrayList<String>(lines.size)
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("URL Source:", ignoreCase = true) ||
                t.startsWith("Published Time:", ignoreCase = true) ||
                t.startsWith("Markdown Content:", ignoreCase = true) ||
                t.startsWith("Warning:", ignoreCase = true) ||
                t.startsWith("Title:", ignoreCase = true)
            ) {
                val afterColon = t.substringAfter(':', "").trim()
                if (t.startsWith("Title:", ignoreCase = true) && afterColon.isNotEmpty()) {
                    filtered.add(afterColon)
                }
                continue
            }
            // Strip markdown images, links, reference links, headings, tables, and horizontal rules
            val cleanedLine = t
                .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
                .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
                .replace(Regex("\\[([^\\]]+)]\\[[^\\]]*]"), "$1")
                .replace(Regex("^\\|[-:\\s|]+\\|$"), "")
                .replace(Regex("^#{1,6}\\s+"), "")
                .replace(Regex("^[-*_]{3,}$"), "")
                .replace(Regex("[`*_~>|]"), " ")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
            if (cleanedLine.isNotEmpty()) {
                filtered.add(cleanedLine)
            }
        }
        return filtered.joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun fetchAndStripDirectHtml(normalizedUrl: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36",
                )
                setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                connectTimeout = 8000
                readTimeout = 10000
            }
            if (conn.responseCode !in 200..299) return ""

            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val articleChunk = Regex("<article[^>]*>([\\s\\S]*?)</article>", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?: html

            val stripped = articleChunk
                .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<noscript[^>]*>[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<(nav|header|footer|aside|form)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("</(p|div|h1|h2|h3|h4|li|br)\\s*>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), " ")

            decodeHtmlEntities(stripped)
                .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
                .replace(Regex("\\n{2,}"), "\n")
                .trim()
                .take(4000)
        } catch (_: Exception) {
            ""
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun decodeHtmlEntities(text: String): String {
        val base = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&mdash;", " — ")
            .replace("&ndash;", " - ")
        return Regex("&#(\\d+);").replace(base) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: " "
        }
    }
}
