package com.example.util

import org.json.JSONObject

data class ExtractedAssistantJson(
    val replyText: String,
    val toolId: String?,
    val toolLabel: String?,
)

/**
 * Global text & JSON safety guard preventing StringIndexOutOfBoundsException,
 * malformed JSON leaks, and raw code artifacts across TTS, AI Chat, Search, and Network layers.
 */
object SafeTextGuard {

    private val CODE_FENCE_REGEX = Regex("```(?:json|JSON)?|```")
    private val REPLY_FIELD_REGEX = Regex(""""reply"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val SUMMARY_FIELD_REGEX = Regex(""""summary"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val ANSWER_FIELD_REGEX = Regex(""""answer"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val MESSAGE_FIELD_REGEX = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val TOOL_ID_FIELD_REGEX = Regex(""""toolId"\s*:\s*"([^"]*)"""")
    private val TOOL_LABEL_FIELD_REGEX = Regex(""""toolLabel"\s*:\s*"([^"]*)"""")

    /**
     * Bounds-checked substring that guarantees 0 <= safeStart <= safeEnd <= text.length
     * and never throws StringIndexOutOfBoundsException.
     */
    fun safeSubstring(text: String, startIndex: Int, endIndex: Int = text.length): String {
        if (text.isEmpty()) return ""
        val len = text.length
        val safeStart = startIndex.coerceIn(0, len)
        val safeEnd = endIndex.coerceIn(safeStart, len)
        return if (safeStart >= safeEnd) "" else text.substring(safeStart, safeEnd)
    }

    /**
     * Splits an oversized token safely into chunks of at most [maxChars] without raw index overflows.
     */
    fun safeChunkWord(word: String, maxChars: Int): List<String> {
        if (word.isEmpty()) return emptyList()
        val safeMax = maxChars.coerceAtLeast(1)
        if (word.length <= safeMax) return listOf(word)
        val chunks = ArrayList<String>((word.length + safeMax - 1) / safeMax)
        var idx = 0
        while (idx < word.length) {
            val end = (idx + safeMax).coerceAtMost(word.length)
            val part = safeSubstring(word, idx, end)
            if (part.isNotEmpty()) chunks.add(part)
            idx = end
        }
        return chunks
    }

    /**
     * Extracts and parses the first balanced/outermost JSON object safely without throwing.
     */
    fun safeParseJsonObject(raw: String?): JSONObject? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val direct = runCatching { JSONObject(text) }.getOrNull()
        if (direct != null) return direct

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val slice = safeSubstring(text, start, end + 1)
        if (slice.isEmpty()) return null
        return runCatching { JSONObject(slice) }.getOrNull()
    }

    /**
     * Multi-stage AI reply cleaner & JSON extractor:
     * 1. Parses strict or wrapped JSON cleanly.
     * 2. Falls back to Regex unescaping if AI returned truncated or malformed JSON.
     * 3. Strips all raw braces, JSON field names, quotes, and markdown fences.
     * 4. Validates [toolId] strictly against [validToolIds].
     */
    fun extractCleanAssistantReply(
        rawResponse: String,
        validToolIds: Set<String>,
    ): ExtractedAssistantJson {
        val cleanedRaw = CODE_FENCE_REGEX.replace(rawResponse, "").trim()
        if (cleanedRaw.isEmpty()) {
            return ExtractedAssistantJson(replyText = "", toolId = null, toolLabel = null)
        }

        // Stage 1: Proper JSONObject parsing
        val jsonObj = safeParseJsonObject(cleanedRaw)
        if (jsonObj != null) {
            val rawReply = listOf("reply", "summary", "answer", "message", "text", "content")
                .firstNotNullOfOrNull { key ->
                    jsonObj.optString(key, "").trim().takeIf { it.isNotEmpty() }
                }
                .orEmpty()

            val rawToolId = jsonObj.optString("toolId", "").trim()
            val nestedToolId = if (rawToolId.isEmpty()) {
                jsonObj.optJSONArray("tools")?.optJSONObject(0)?.optString("toolId", "")?.trim().orEmpty()
            } else {
                rawToolId
            }
            val validId = nestedToolId.takeIf { it.isNotEmpty() && it in validToolIds }
            val rawLabel = validId?.let {
                jsonObj.optString("toolLabel", "").trim().takeIf { lbl -> lbl.isNotEmpty() }
            }

            if (rawReply.isNotEmpty()) {
                val polished = stripJsonAndCodeNoise(rawReply)
                if (polished.isNotEmpty()) {
                    return ExtractedAssistantJson(
                        replyText = polished,
                        toolId = validId,
                        toolLabel = rawLabel,
                    )
                }
            }
        }

        // Stage 2: Resilient Regex extraction for partial / broken JSON strings
        val regexReply = listOf(REPLY_FIELD_REGEX, SUMMARY_FIELD_REGEX, ANSWER_FIELD_REGEX, MESSAGE_FIELD_REGEX)
            .firstNotNullOfOrNull { rx ->
                rx.find(cleanedRaw)?.groupValues?.getOrNull(1)?.let { unescapeJsonString(it) }?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        val regexToolId = TOOL_ID_FIELD_REGEX.find(cleanedRaw)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotEmpty() && it in validToolIds }
        val regexToolLabel = regexToolId?.let {
            TOOL_LABEL_FIELD_REGEX.find(cleanedRaw)?.groupValues?.getOrNull(1)
                ?.let { lbl -> unescapeJsonString(lbl) }?.trim()?.takeIf { lbl -> lbl.isNotEmpty() }
        }

        if (!regexReply.isNullOrBlank()) {
            val polished = stripJsonAndCodeNoise(regexReply)
            if (polished.isNotEmpty()) {
                return ExtractedAssistantJson(
                    replyText = polished,
                    toolId = regexToolId,
                    toolLabel = regexToolLabel,
                )
            }
        }

        // Stage 3: Pure prose fallback with aggressive stripping of JSON/code junk
        val proseOnly = stripJsonAndCodeNoise(cleanedRaw)
        return ExtractedAssistantJson(
            replyText = proseOnly,
            toolId = regexToolId,
            toolLabel = regexToolLabel,
        )
    }

    /**
     * Removes any residual JSON syntax, braces, property labels (`"toolId": ...`), or code formatting
     * so clean human language is guaranteed in UI bubbles.
     */
    fun stripJsonAndCodeNoise(raw: String): String {
        var text = CODE_FENCE_REGEX.replace(raw, "")
        text = unescapeJsonString(text)
        text = text
            .replace(Regex(""""(?:reply|toolId|toolLabel|summary|tools|answer|message)"\s*:\s*"""), " ")
            .replace(Regex("""\b(?:toolId|toolLabel)\s*:\s*"?[\w-]*"?"""), " ")
            .replace("{", " ")
            .replace("}", " ")
            .replace(Regex("""^\s*"+|"+\s*$"""), "")
            .replace(Regex("""",\s*""""), " ")
            .replace(Regex("[\\t\\r ]+"), " ")
            .replace(Regex("\\s+([،,.!؟?])"), "$1")
            .trim()
            .trim(',', '"', '`')
            .trim()
        return text
    }

    private fun unescapeJsonString(input: String): String {
        if (!input.contains('\\')) return input
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch == '\\' && i + 1 < input.length) {
                val next = input[i + 1]
                when (next) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append(' '); i += 2 }
                    't' -> { sb.append(' '); i += 2 }
                    'u' -> {
                        if (i + 6 <= input.length) {
                            val hex = safeSubstring(input, i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                            } else {
                                sb.append(ch); i++
                            }
                        } else {
                            sb.append(ch); i++
                        }
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
