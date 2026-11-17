package com.example.audio.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object WebArticleExtractor {

    /**
     * Fetches a web page and extracts clean readable article text suitable for
     * natural human TTS / Podcast narration without HTML tags, scripts, or styling clutter.
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

        var conn: HttpURLConnection? = null
        return@withContext try {
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
            if (conn.responseCode !in 200..299) return@withContext ""

            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // Prefer <article> body when present for cleaner podcast narration
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
