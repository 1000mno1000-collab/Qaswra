package com.example.audio.web

import android.content.Context
import com.example.audio.OnlineGuard
import com.example.audio.network.NetworkFetchOutcome
import com.example.audio.network.NetworkImmunityManager
import com.example.model.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        if (!NetworkImmunityManager.pingReachable(context)) {
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
     * natural human TTS / Podcast narration with 6-layer Network Immunity.
     */
    suspend fun extractCleanTextFromUrl(pageUrl: String): String = withContext(Dispatchers.IO) {
        val trimmed = pageUrl.trim()
        if (trimmed.isEmpty()) return@withContext ""
        val normalizedUrl = NetworkImmunityManager.enforceHttps(trimmed)

        // 1. Primary: Jina AI clean reader (plain text & markdown mirrors) with 30s timeout & backoff
        val jinaTextOutcome = NetworkImmunityManager.fetchTextWithFallback(
            urls = listOf("https://r.jina.ai/$normalizedUrl"),
            headers = mapOf(
                "Accept" to "text/plain, text/markdown;q=0.9, */*;q=0.5",
                "X-Return-Format" to "text",
                "X-No-Cache" to "true",
            ),
            timeoutMs = NetworkImmunityManager.DEFAULT_NETWORK_TIMEOUT_MS,
            providerLabel = "JinaReader",
        )
        if (jinaTextOutcome is NetworkFetchOutcome.Success) {
            val cleaned = cleanJinaText(jinaTextOutcome.value.body)
            if (cleaned.length >= 40) return@withContext cleaned.take(6000)
        }

        // 2. Fallback: Direct HTTPS HTML fetch + script/navigation stripping
        return@withContext fetchAndStripDirectHtml(normalizedUrl)
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

    private suspend fun fetchAndStripDirectHtml(normalizedUrl: String): String {
        val outcome = NetworkImmunityManager.fetchTextWithFallback(
            urls = listOf(normalizedUrl),
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5",
            ),
            timeoutMs = NetworkImmunityManager.DEFAULT_NETWORK_TIMEOUT_MS,
            providerLabel = "DirectHtmlExtractor",
        )
        val html = (outcome as? NetworkFetchOutcome.Success)?.value?.body ?: return ""
        return runCatching {
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
        }.getOrDefault("")
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
