package com.example.audio.ai

import android.content.Context
import androidx.compose.runtime.Immutable
import com.example.audio.AudioDecoder
import com.example.model.AppLanguage
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

@Immutable
data class ToolGuideResult(
    val toolId: String,
    val toolName: String,
    val matchReason: String,
    val howToUseSteps: List<String>,
)

@Immutable
data class OnlineSoundItem(
    val identifier: String,
    val title: String,
    val category: String,
    val streamUrl: String,
    val source: String,
)

@Immutable
data class PollinationsGuideResponse(
    val aiSummary: String?,
    val tools: List<ToolGuideResult>,
    val fromCloudAi: Boolean,
)

object SmartAssistantAndSearch {

    const val CAT_HORROR = "مؤثرات رعب وأصوات خرافية"
    const val CAT_NATURE = "أصوات طبيعة وأجواء"
    const val CAT_MUSIC = "موسيقى خلفية"
    const val CAT_GAMING = "مؤثرات ألعاب وحركة"
    const val CAT_GENERAL = "مؤثرات صوتية عامة"

    fun findBestToolWithTypoTolerance(rawQuery: String): List<ToolGuideResult> {
        val q = normalizeArabicText(rawQuery)
        if (q.isBlank()) return emptyList()

        val results = mutableListOf<ToolGuideResult>()

        if (q.containsAny("ضوضاء", "ضوجه", "ضجه", "هوا", "هواء", "وشه", "تشويش", "تنقيه", "تصفيه", "صافي", "مايك", "بودكاست", "noise", "clean", "denoise", "purify")) {
            results.add(
                ToolGuideResult(
                    toolId = "noise_reduction",
                    toolName = "عزل الضوضاء الذكي (Smart Noise Gate & Denoise)",
                    matchReason = "فهمت أنك تريد تنظيف الصوت من الهواء والتشويش الخلفي.",
                    howToUseSteps = listOf(
                        "1. اضغط على زر (افتح الأداة) واختر ملفك الصوتي.",
                        "2. اختر وضع (تنقية المايك والبودكاست) أو اضبط البوابة لعزل الهواء.",
                        "3. اضغط (موافقة وبدء المعالجة) للاستماع وحفظ النتيجة.",
                    ),
                ),
            )
        }

        if (q.containsAny("تغيير", "تغير", "طفل", "مرعب", "رعب", "تنين", "شبح", "اشباح", "وحش", "فخم", "عميق", "روبوت", "ساحر", "فضائي", "عملاق", "غابه", "راديو", "ملعون", "خرافي", "شخصيه", "voice", "pitch", "dragon")) {
            results.add(
                ToolGuideResult(
                    toolId = "voice_changer",
                    toolName = "محوّل الشخصيات والأصوات الخرافية (Voice & Mythical FX)",
                    matchReason = "فهمت أنك تبحث عن تغيير نبرة الصوت أو إضافة تأثير مرعب/خرافي.",
                    howToUseSteps = listOf(
                        "1. افتح الأداة واختر الشخصية (طفل، تنين أسطوري، كيان الظلام، ساحر، أشباح).",
                        "2. يمكنك تجربة المعاينة الفورية بالمايكروفون أو تعديل ملف مسجل.",
                        "3. اضغط (موافقة وبدء المعالجة) لتطبيق التأثير بجودة استوديو.",
                    ),
                ),
            )
        }

        if (q.containsAny("نص", "كتابه", "قراءه", "نطق", "تكلم", "معلق", "بشري", "عراقي", "سعودي", "مصري", "فصحى", "سوري", "مقال", "رابط", "tts", "speech", "podcast")) {
            results.add(
                ToolGuideResult(
                    toolId = "human_tts",
                    toolName = "استوديو الصوت البشري وقارئ المقالات (Human Neural TTS)",
                    matchReason = "فهمت أنك تريد تحويل نص مكتوب أو مقال من رابط إلى صوت بشري طبيعي.",
                    howToUseSteps = listOf(
                        "1. اكتب النص أو الصق رابط مقال لاستخراج نصه تلقائياً.",
                        "2. اختر اللهجة (عراقي، سعودي، مصري، فصحى) ونبرة الإلقاء.",
                        "3. اضغط (موافقة وبدء المعالجة) لتوليد الصوت بجودة استوديو.",
                    ),
                ),
            )
        }

        if (q.containsAny("فيديو", "رندر", "ببجي", "بوبجي", "سينمائي", "الوان", "وضوح", "موشن", "ريلز", "موجه", "فريم", "video", "hdr", "reshade", "reels", "120")) {
            results.add(
                ToolGuideResult(
                    toolId = "cinematic_video",
                    toolName = "رندر الفيديو السينمائي وصانع الريلز (Cinematic & Reels)",
                    matchReason = "فهمت أنك تريد تحسين ألوان ووضوح الفيديو (60/90/120 FPS) أو تحويل الصوت لفيديو ريلز.",
                    howToUseSteps = listOf(
                        "1. اختر الفيديو الخاص بك (أو استخدم الصوت الحالي لصنع فيديو ريلز 1080x1920).",
                        "2. اختر الفلتر السينمائي (HDR مشبع، وضوح الألعاب، طابع الأفلام).",
                        "3. اضغط (موافقة وبدء المعالجة) مع الحفاظ على كامل الفريمات الأصلية.",
                    ),
                ),
            )
        }

        if (q.containsAny("موسيقي", "موسيقى", "دمج", "خلفيه", "دوبلاج", "مكس", "mixer", "ducking", "music")) {
            results.add(
                ToolGuideResult(
                    toolId = "smart_mixer",
                    toolName = "دمج الموسيقى الذكي (Auto-Ducking Mixer)",
                    matchReason = "فهمت أنك تريد مزج الصوت مع موسيقى خلفية تخفت تلقائياً عند الكلام.",
                    howToUseSteps = listOf(
                        "1. افتح الدمج الذكي واختر مسار الكلام ثم مسار الموسيقى.",
                        "2. اضبط نسبة مستوى الموسيقى وعاين النتيجة مجاناً.",
                        "3. اضغط اعتماد وتصدير عند الرضا.",
                    ),
                ),
            )
        }

        if (q.containsAny("قص", "قطع", "تعديل", "دمج صوت", "حذف", "trim", "cut", "edit")) {
            results.add(
                ToolGuideResult(
                    toolId = "audio_editor",
                    toolName = "استوديو التحرير والقص (Audio Editor)",
                    matchReason = "فهمت أنك تريد قص جزء من المقطع الصوتي أو تلاشي البداية والنهاية.",
                    howToUseSteps = listOf(
                        "1. حدد البداية والنهاية على شريط الموجة.",
                        "2. استخدم (الإبقاء على المحدد) أو (حذف المحدد) مع التراجع بأي وقت.",
                        "3. احفظ وصدّر الصيغة المناسبة لك.",
                    ),
                ),
            )
        }

        if (q.containsAny("صدي", "صدى", "قالب", "استوديو", "اوتوتيون", "8d", "echo", "reverb", "preset")) {
            results.add(
                ToolGuideResult(
                    toolId = "presets_echo",
                    toolName = "القوالب العالمية ومساحة الصدى 3D",
                    matchReason = "فهمت أنك تريد إضافة صدى احترافي أو تفعيل قالب إذاعي/سينمائي.",
                    howToUseSteps = listOf(
                        "1. افتح القوالب العالمية واختر القالب المناسب للمعاينة الحية.",
                        "2. استخدم زر (مقارنة الأصل) لمقارنة الفرق فوراً.",
                    ),
                ),
            )
        }

        return results
    }

    private val VALID_TOOL_IDS = setOf(
        "noise_reduction",
        "voice_changer",
        "human_tts",
        "cinematic_video",
        "smart_mixer",
        "audio_editor",
        "presets_echo",
    )

    /**
     * Queries the free keyless Pollinations Text AI endpoint (https://text.pollinations.ai)
     * to understand user requests even with Arabic dialects or spelling typos and guide them
     * to the right Sonix studio tool(s). Must only be called when device has internet.
     */
    suspend fun askPollinationsToolGuide(
        rawQuery: String,
        language: AppLanguage,
    ): PollinationsGuideResponse = withContext(Dispatchers.IO) {
        val cleanQuery = rawQuery.trim()
        if (cleanQuery.isEmpty()) {
            return@withContext PollinationsGuideResponse(
                aiSummary = null,
                tools = emptyList(),
                fromCloudAi = false,
            )
        }

        val replyLangHint = when (language) {
            AppLanguage.AR -> "Arabic"
            AppLanguage.EN -> "English"
            AppLanguage.FA -> "Persian"
        }
        val systemPrompt = buildString {
            append("You are the smart studio guide inside Sonix Audio & Video Studio. ")
            append("Understand user questions even with typos, slang, or Arabic dialects (Iraqi, Saudi, Egyptian, Syrian, Moroccan, Fusha). ")
            append("Reply in $replyLangHint using strictly valid JSON with schema: ")
            append("{\"summary\":\"short helpful advice\",\"tools\":[{\"toolId\":\"one_id\",\"toolName\":\"display name\",\"matchReason\":\"why it fits\",\"steps\":[\"step 1\",\"step 2\"]}]}. ")
            append("Allowed toolId values ONLY: ")
            append("noise_reduction (AI noise & hum removal), ")
            append("voice_changer (Natural child, male/female, dragon, shadow legion, ghost, alien live & offline morphing), ")
            append("human_tts (Cloud Neural Human TTS in 12 dialects & web article podcast extractor), ")
            append("cinematic_video (ReShade HDR video renderer up to 120 FPS & 1080x1920 Reels audiogram creator), ")
            append("smart_mixer (Auto-ducking music + voice mixer), ")
            append("audio_editor (Trim, cut, merge, normalize, fade), ")
            append("presets_echo (Pro broadcast presets & 3D spatial echo). ")
            append("Pick 1 or 2 most relevant tools.")
        }

        val rawResponse = queryPollinationsPost(systemPrompt, cleanQuery)
            ?: queryPollinationsGet(systemPrompt, cleanQuery)

        if (!rawResponse.isNullOrBlank()) {
            val parsed = parsePollinationsJson(rawResponse)
            if (parsed != null && (parsed.tools.isNotEmpty() || !parsed.aiSummary.isNullOrBlank())) {
                val mergedTools = if (parsed.tools.isNotEmpty()) {
                    parsed.tools
                } else {
                    findBestToolWithTypoTolerance(cleanQuery)
                }
                return@withContext PollinationsGuideResponse(
                    aiSummary = parsed.aiSummary ?: rawResponse.take(360),
                    tools = mergedTools,
                    fromCloudAi = true,
                )
            }

            // Pollinations answered in natural prose rather than JSON: surface the prose + infer matching tools
            val inferred = inferToolsFromText(rawResponse)
                .ifEmpty { findBestToolWithTypoTolerance(cleanQuery) }
            return@withContext PollinationsGuideResponse(
                aiSummary = rawResponse.trim().take(480),
                tools = inferred,
                fromCloudAi = true,
            )
        }

        // Only if online endpoint temporarily timed out while connected, gracefully provide fuzzy matches
        val fallbackTools = findBestToolWithTypoTolerance(cleanQuery)
        PollinationsGuideResponse(
            aiSummary = null,
            tools = fallbackTools,
            fromCloudAi = false,
        )
    }

    private fun queryPollinationsPost(systemPrompt: String, userQuery: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val bodyJson = JSONObject().apply {
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userQuery)
                        })
                    },
                )
                put("model", "openai")
                put("jsonMode", true)
            }
            conn = (URL("https://text.pollinations.ai/").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json, text/plain;q=0.9, */*;q=0.8")
                setRequestProperty("User-Agent", "SonixApp/1.0")
                connectTimeout = 7000
                readTimeout = 11000
            }
            conn.outputStream.use { out ->
                out.write(bodyJson.toString().toByteArray(Charsets.UTF_8))
                out.flush()
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun queryPollinationsGet(systemPrompt: String, userQuery: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val encodedPrompt = URLEncoder.encode(userQuery, "UTF-8").replace("+", "%20")
            val encodedSys = URLEncoder.encode(systemPrompt, "UTF-8")
            val urlStr = "https://text.pollinations.ai/$encodedPrompt?model=openai&system=$encodedSys"
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SonixApp/1.0")
                setRequestProperty("Accept", "text/plain, application/json;q=0.9")
                connectTimeout = 7000
                readTimeout = 11000
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun parsePollinationsJson(raw: String): PollinationsGuideResponse? {
        return runCatching {
            val stripped = raw
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val startIdx = stripped.indexOf('{')
            val endIdx = stripped.lastIndexOf('}')
            if (startIdx < 0 || endIdx <= startIdx) return null
            val jsonObj = JSONObject(stripped.substring(startIdx, endIdx + 1))
            val summary = jsonObj.optString("summary").trim().ifEmpty { null }
            val toolsArr = jsonObj.optJSONArray("tools") ?: jsonObj.optJSONArray("recommended")
            val parsedTools = mutableListOf<ToolGuideResult>()
            if (toolsArr != null) {
                for (i in 0 until toolsArr.length()) {
                    val obj = toolsArr.optJSONObject(i) ?: continue
                    val id = obj.optString("toolId").trim()
                    if (id !in VALID_TOOL_IDS) continue
                    val name = obj.optString("toolName").trim().ifBlank { id }
                    val reason = obj.optString("matchReason").ifBlank { obj.optString("reason") }.trim()
                    val stepsArr = obj.optJSONArray("steps") ?: obj.optJSONArray("howToUseSteps")
                    val steps = mutableListOf<String>()
                    if (stepsArr != null) {
                        for (s in 0 until stepsArr.length()) {
                            val line = stepsArr.optString(s).trim()
                            if (line.isNotEmpty()) steps.add(line)
                        }
                    }
                    parsedTools.add(
                        ToolGuideResult(
                            toolId = id,
                            toolName = name,
                            matchReason = reason,
                            howToUseSteps = steps,
                        ),
                    )
                }
            }
            PollinationsGuideResponse(
                aiSummary = summary,
                tools = parsedTools,
                fromCloudAi = true,
            )
        }.getOrNull()
    }

    private fun inferToolsFromText(text: String): List<ToolGuideResult> {
        val matchedIds = VALID_TOOL_IDS.filter { id -> text.contains(id, ignoreCase = true) }
        val baseCatalog = findBestToolWithTypoTolerance(
            "ضوضاء تغيير صوت نص فيديو موسيقى قص صدى",
        ).associateBy { it.toolId }
        return matchedIds.mapNotNull { baseCatalog[it] }
    }

    /**
     * Searches Archive.org public audio collections, resolves direct MP3/OGG stream URLs
     * asynchronously, and distributes items into structured categories.
     */
    suspend fun searchInternetSoundsOrganized(query: String): Map<String, List<OnlineSoundItem>> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim().ifEmpty { "cinematic ambient" }
            val allSounds = mutableListOf<OnlineSoundItem>()
            try {
                val searchTerms = buildArchiveQuery(cleanQuery)
                val encoded = URLEncoder.encode(searchTerms, "UTF-8")
                val apiUrl =
                    "https://archive.org/advancedsearch.php?q=$encoded" +
                        "&fl[]=identifier,title,subject,description&rows=18&page=1&output=json"
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "SonixApp/1.0")
                    connectTimeout = 8000
                    readTimeout = 10000
                }

                if (conn.responseCode in 200..299) {
                    val jsonText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    conn.disconnect()
                    val json = JSONObject(jsonText)
                    val docs = json.optJSONObject("response")?.optJSONArray("docs")
                    if (docs != null) {
                        val rawDocs = (0 until docs.length()).mapNotNull { idx ->
                            val doc = docs.optJSONObject(idx) ?: return@mapNotNull null
                            val id = doc.optString("identifier").trim()
                            if (id.isEmpty()) return@mapNotNull null
                            val title = doc.optString("title").ifBlank { id }
                            val subject = doc.optString("subject", "")
                            Triple(id, title, subject)
                        }.take(12)

                        val resolvedItems = coroutineScope {
                            rawDocs.map { (id, title, subject) ->
                                async {
                                    val directUrl = resolveDirectPlayableArchiveUrl(id)
                                        ?: return@async null
                                    val cat = classifyCategory(title = title, subject = subject, query = cleanQuery)
                                    OnlineSoundItem(
                                        identifier = id,
                                        title = title,
                                        category = cat,
                                        streamUrl = directUrl,
                                        source = "Archive.org",
                                    )
                                }
                            }.awaitAll().filterNotNull()
                        }
                        allSounds.addAll(resolvedItems)
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // Return cleanly structured empty map when offline
            }

            val orderedCategories = listOf(CAT_HORROR, CAT_NATURE, CAT_MUSIC, CAT_GAMING, CAT_GENERAL)
            val grouped = linkedMapOf<String, List<OnlineSoundItem>>()
            val byCategory = allSounds.groupBy { it.category }
            orderedCategories.forEach { cat ->
                val list = byCategory[cat].orEmpty()
                if (list.isNotEmpty()) {
                    grouped[cat] = list
                }
            }
            grouped
        }

    suspend fun downloadSoundToStudioBuffer(
        context: Context,
        item: OnlineSoundItem,
    ): StereoAudioBuffer? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "archive_sfx").apply { mkdirs() }
        val ext = item.streamUrl.substringAfterLast('.', "mp3").substringBefore('?').take(4).ifBlank { "mp3" }
        val tmpFile = File(dir, "sfx_${UUID.randomUUID()}.$ext")
        var conn: HttpURLConnection? = null
        return@withContext try {
            conn = (URL(item.streamUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SonixApp/1.0")
                connectTimeout = 9000
                readTimeout = 15000
            }
            if (conn.responseCode !in 200..299) return@withContext null
            conn.inputStream.use { input ->
                tmpFile.outputStream().use { out ->
                    val buf = ByteArray(16384)
                    var total = 0L
                    val maxBytes = 25L * 1024L * 1024L // cap at 25MB safety
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        total += read
                        if (total > maxBytes) break
                        out.write(buf, 0, read)
                    }
                }
            }
            AudioDecoder.decodeFile(tmpFile)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
            tmpFile.delete()
        }
    }

    private fun resolveDirectPlayableArchiveUrl(identifier: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val metaUrl = "https://archive.org/metadata/$identifier/files"
            conn = (URL(metaUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "SonixApp/1.0")
                connectTimeout = 4500
                readTimeout = 5500
            }
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val resultArr = JSONObject(text).optJSONArray("result") ?: return null

            var bestMp3: String? = null
            var bestAlt: String? = null
            for (i in 0 until resultArr.length()) {
                val entry = resultArr.optJSONObject(i) ?: continue
                val name = entry.optString("name")
                val size = entry.optString("size").toLongOrNull() ?: 0L
                if (name.isEmpty() || size > 30_000_000L) continue
                val lower = name.lowercase()
                when {
                    lower.endsWith(".mp3") -> {
                        bestMp3 = name
                        break
                    }
                    (lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".m4a")) && bestAlt == null -> {
                        bestAlt = name
                    }
                }
            }
            val chosen = bestMp3 ?: bestAlt ?: return null
            val encodedFile = URLEncoder.encode(chosen, "UTF-8").replace("+", "%20")
            "https://archive.org/download/$identifier/$encodedFile"
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun buildArchiveQuery(query: String): String {
        val norm = normalizeArabicText(query)
        val mappedKeywords = when {
            norm.containsAny("رعب", "شبح", "تنين", "وحش", "خرافي") -> "horror monster dark scary sound"
            norm.containsAny("مطر", "طبيعه", "غابه", "بحر", "ريح", "رعد") -> "rain nature forest ambient"
            norm.containsAny("موسيقي", "موسيقى", "هادئه", "بيانو", "سينما") -> "cinematic ambient background music"
            norm.containsAny("العاب", "اكشن", "ضربه", "سلاح", "انفجار", "ببجي") -> "game sound effect action impact"
            else -> {
                // Keep ASCII/English tokens if present; otherwise combine with general audio terms
                val asciiOnly = query.replace(Regex("[^a-zA-Z0-9\\s]"), " ").trim()
                if (asciiOnly.isNotBlank()) "$asciiOnly sound effect" else "sound effect audio"
            }
        }
        return "($mappedKeywords) AND mediatype:(audio)"
    }

    private fun classifyCategory(title: String, subject: String, query: String): String {
        val text = normalizeArabicText("$title $subject $query")
        return when {
            text.containsAny("horror", "scary", "ghost", "dragon", "monster", "dark", "creepy", "رعب", "شبح", "تنين", "وحش") ->
                CAT_HORROR
            text.containsAny("rain", "forest", "wind", "thunder", "ocean", "bird", "nature", "ambient", "مطر", "غابه", "طبيعه", "ريح") ->
                CAT_NATURE
            text.containsAny("music", "piano", "orchestra", "melody", "lofi", "synth", "موسيقي", "موسيقى", "لحن") ->
                CAT_MUSIC
            text.containsAny("game", "laser", "explosion", "sword", "punch", "gun", "arcade", "action", "لعبه", "العاب", "اكشن") ->
                CAT_GAMING
            else -> CAT_GENERAL
        }
    }

    fun normalizeArabicText(input: String): String {
        return input
            .lowercase()
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "") // Strip Arabic diacritics
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace('ؤ', 'و')
            .replace('ئ', 'ي')
            .trim()
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it) }
}
