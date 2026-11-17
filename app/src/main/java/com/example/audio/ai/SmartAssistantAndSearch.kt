package com.example.audio.ai

import android.content.Context
import androidx.compose.runtime.Immutable
import com.example.audio.AudioDecoder
import com.example.audio.cache.SmartCacheManager
import com.example.audio.network.NetworkFetchOutcome
import com.example.audio.network.NetworkImmunityManager
import com.example.audio.readStereoWavFile
import com.example.diagnostics.FfmpegKitBridge
import com.example.model.AppLanguage
import com.example.model.AssistantChatMessage
import com.example.model.OnlineSearchCategory
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    val searchCategory: OnlineSearchCategory = OnlineSearchCategory.SOUND_EFFECTS,
)

@Immutable
data class PollinationsGuideResponse(
    val aiSummary: String?,
    val tools: List<ToolGuideResult>,
    val fromCloudAi: Boolean,
)

@Immutable
data class PollinationsChatTurn(
    val replyText: String,
    val recommendedToolId: String?,
    val recommendedToolLabel: String?,
    val fromCloudAi: Boolean,
)

object SmartAssistantAndSearch {

    const val CAT_VOICES = "أصوات بشرية ومعلقون"
    const val CAT_PODCASTS = "بودكاست وحوارات صوتية"
    const val CAT_CLIPS = "مقاطع وإلقاءات عامة"

    const val CAT_HORROR = "مؤثرات رعب وأصوات خرافية"
    const val CAT_NATURE = "أصوات طبيعة وأجواء"
    const val CAT_MUSIC = "موسيقى ومؤثرات سينمائية"
    const val CAT_GAMING = "مؤثرات ألعاب وحركة"
    const val CAT_GENERAL = "مؤثرات صوتية عامة"

    private val VALID_TOOL_IDS = setOf(
        "noise_reduction",
        "voice_changer",
        "human_tts",
        "reels_audio_templates",
        "extract_video",
        "smart_mixer",
        "audio_editor",
        "presets_echo",
        "surround_stereo",
        "equalizer",
        "pitch_speed",
    )

    fun toolDisplayLabel(toolId: String, language: AppLanguage): String = when (toolId) {
        "noise_reduction" -> if (language == AppLanguage.EN) "Voice Purifier & Denoise" else "منقّي الصوت وعزل الضوضاء"
        "voice_changer" -> if (language == AppLanguage.EN) "Voice & Mythical FX" else "تغيير الصوت والشخصيات"
        "human_tts" -> if (language == AppLanguage.EN) "Human Neural TTS" else "تحويل النص لصوت بشري"
        "reels_audio_templates" -> if (language == AppLanguage.EN) "Reels & Podcast -14 LUFS" else "قوالب الريلز والبودكاست -14 LUFS"
        "extract_video" -> if (language == AppLanguage.EN) "Extract Audio from Video" else "استخراج الصوت من الفيديو"
        "smart_mixer" -> if (language == AppLanguage.EN) "Merge & Smart Mixer" else "الدمج والميكس الذكي"
        "audio_editor" -> if (language == AppLanguage.EN) "Audio Trim & Volume Editor" else "محرر القص ومستوى الصوت"
        "presets_echo" -> if (language == AppLanguage.EN) "3D Echo & Pro Presets" else "الصدى 3D والقوالب"
        "surround_stereo" -> if (language == AppLanguage.EN) "M/S Surround Stereo" else "المحيطي ستيريو 3D"
        "equalizer" -> if (language == AppLanguage.EN) "5-Band Equalizer" else "موازن الترددات EQ"
        "pitch_speed" -> if (language == AppLanguage.EN) "Pitch & Speed" else "تغيير الطبقة والسرعة"
        else -> toolId
    }

    fun findBestToolWithTypoTolerance(rawQuery: String): List<ToolGuideResult> {
        val q = normalizeArabicText(rawQuery)
        if (q.isBlank()) return emptyList()

        val results = mutableListOf<ToolGuideResult>()

        if (q.containsAny("ضوضاء", "ضوجه", "ضجه", "هوا", "هواء", "وشه", "تشويش", "تنقيه", "تصفيه", "صافي", "مايك", "نقي", "noise", "clean", "denoise", "purify", "wind", "hum")) {
            results.add(
                ToolGuideResult(
                    toolId = "noise_reduction",
                    toolName = "منقّي الصوت وعزل الضوضاء (3 مراحل احترافية)",
                    matchReason = "لعزل الضجيج والهواء، فلترة الترددات المزعجة، وإبراز وضوح الكلام.",
                    howToUseSteps = listOf(
                        "1. فعّل عزل الضوضاء والهواء الذكي.",
                        "2. اضبط ترددات الباس والحدة بأمان دون اقتطاع مخارج الحروف.",
                        "3. عاين النتيجة ثم اضغط تطبيق.",
                    ),
                ),
            )
        }

        if (q.containsAny("تغيير", "تغير", "طفل", "مرعب", "رعب", "تنين", "شبح", "اشباح", "وحش", "فخم", "عميق", "روبوت", "ساحر", "فضائي", "عملاق", "غابه", "خرافي", "شخصيه", "voice", "dragon", "monster")) {
            results.add(
                ToolGuideResult(
                    toolId = "voice_changer",
                    toolName = "محوّل الشخصيات والأصوات الخرافية",
                    matchReason = "لتغيير نبرة الصوت أو تقمص شخصيات احترافية وأسطورية.",
                    howToUseSteps = listOf(
                        "1. اختر الشخصية المناسبة.",
                        "2. اضغط معاينة التعديل للاستماع قبل الحفظ.",
                    ),
                ),
            )
        }

        if (q.containsAny("نص", "كتابه", "قراءه", "نطق", "تكلم", "معلق", "بشري", "عراقي", "سعودي", "مصري", "فصحى", "سوري", "مقال", "رابط", "tts", "speech")) {
            results.add(
                ToolGuideResult(
                    toolId = "human_tts",
                    toolName = "استوديو الصوت البشري وقارئ المقالات (أونلاين)",
                    matchReason = "لتحويل النصوص أو روابط المقالات إلى تعليق صوتي بشري طبيعي.",
                    howToUseSteps = listOf(
                        "1. أدخل النص أو رابط المقال.",
                        "2. اختر اللهجة العربية أو العالمية ونبرة الإلقاء.",
                    ),
                ),
            )
        }

        if (q.containsAny("ريلز", "بودكاست", "تيك", "تيكتوك", "شورتس", "سوشيال", "lufs", "14", "مذيع", "reels", "podcast", "shorts")) {
            results.add(
                ToolGuideResult(
                    toolId = "reels_audio_templates",
                    toolName = "قوالب الهندسة الصوتية للريلز والبودكاست (-14 LUFS)",
                    matchReason = "لتضخيم صوت المعلق، عزل الضوضاء، وموازنة الشدة الصوتية -14 LUFS لمنصات السوشيال.",
                    howToUseSteps = listOf(
                        "1. اختر قالب الريلز أو البودكاست المناسب.",
                        "2. عاين النتيجة واضغط تطبيق الآن.",
                    ),
                ),
            )
        }

        if (q.containsAny("فيديو", "استخراج", "سحب", "فصل الصوت", "mp4", "mkv", "extract", "video")) {
            results.add(
                ToolGuideResult(
                    toolId = "extract_video",
                    toolName = "استخراج الصوت من الفيديو",
                    matchReason = "لاستخلاص المسار الصوتي النقي (WAV / MP3 / FLAC) من أي ملف فيديو.",
                    howToUseSteps = listOf(
                        "1. اختر الفيديو من الجهاز أو من المكتبة.",
                        "2. حدد صيغة الاستخراج واضغط استيراد.",
                    ),
                ),
            )
        }

        if (q.containsAny("موسيقي", "موسيقى", "دمج", "خلفيه", "دوبلاج", "مكس", "تراكب", "mixer", "ducking", "merge")) {
            results.add(
                ToolGuideResult(
                    toolId = "smart_mixer",
                    toolName = "استوديو الدمج والميكس الذكي",
                    matchReason = "لدمج مسارين بالتتابع أو بالتراكب مع خفض الموسيقى تلقائياً عند الكلام.",
                    howToUseSteps = listOf(
                        "1. حدد المقطع الأساسي والمقطع الثاني.",
                        "2. فعّل الخفض التلقائي وعاين الدمج قبل الاعتماد.",
                    ),
                ),
            )
        }

        if (q.containsAny("قص", "قطع", "صوت", "عالي", "ناصي", "رفع", "تخفيض", "تعديل", "تلاشي", "trim", "cut", "volume", "gain", "fade")) {
            results.add(
                ToolGuideResult(
                    toolId = "audio_editor",
                    toolName = "استوديو القص والتحرير ورفع الصوت",
                    matchReason = "لقص الأجزاء الزائدة أو رفع مستوى الصوت وحماية التشويه.",
                    howToUseSteps = listOf(
                        "1. حرّك مؤشر الشدة أو حدّد نطاق القص.",
                        "2. استمع مباشرة للتعديلات أو اضغط تطبيق.",
                    ),
                ),
            )
        }

        if (q.containsAny("محيطي", "ستيريو", "مسرح", "اتساع", "surround", "stereo", "3d", "8d")) {
            results.add(
                ToolGuideResult(
                    toolId = "surround_stereo",
                    toolName = "المحيطي ستيريو (M/S Surround)",
                    matchReason = "لتوسيع المساحة الفراغية بين السماعتين فورياً وأثناء التشغيل.",
                    howToUseSteps = listOf(
                        "1. حرّك أشرطة Mid/Side والاتساع واستمع لحظياً.",
                    ),
                ),
            )
        }

        if (q.containsAny("ايكو", "صدى", "صدي", "قالب", "قاعه", "مسجد", "كنيسه", "echo", "reverb", "preset")) {
            results.add(
                ToolGuideResult(
                    toolId = "presets_echo",
                    toolName = "الصدى 3D والقوالب الاحترافية",
                    matchReason = "لإضافة عمق مكاني وصدى ناعم أو احترافي.",
                    howToUseSteps = listOf(
                        "1. استخدم الوضع البسيط للاستماع المباشر أو الاحترافي للتحكم العميق.",
                    ),
                ),
            )
        }

        if (q.containsAny("تردد", "ترددات", "اكوالايزر", "ايكوالايزر", "eq", "bass", "treble", "باس")) {
            results.add(
                ToolGuideResult(
                    toolId = "equalizer",
                    toolName = "موازن الترددات (Equalizer)",
                    matchReason = "لضبط ترددات الباس والوسط والحدة فورياً أثناء التشغيل.",
                    howToUseSteps = listOf(
                        "1. ارفع أو خفّض الترددات الخمسة واستمع للتغيير لحظياً.",
                    ),
                ),
            )
        }

        if (q.containsAny("سرعه", "بطيء", "سريع", "طبقه", "تنعيم", "تغليظ", "speed", "tempo", "pitch")) {
            results.add(
                ToolGuideResult(
                    toolId = "pitch_speed",
                    toolName = "تعديل السرعة والطبقة (Pitch & Speed)",
                    matchReason = "لتسريع أو تبطيء المقطع أو تغيير حدّة الصوت فورياً.",
                    howToUseSteps = listOf(
                        "1. اسحب مؤشر السرعة أو الطبقة واستمع للمعاينة الحية فوراً.",
                    ),
                ),
            )
        }

        return results
    }

    /**
     * Multi-turn online Cloud AI chat powered by keyless https://text.pollinations.ai.
     * Understands Iraqi & Fusha Arabic dialects, repairs typos, explains concisely, and
     * picks a single best actionable Studio tool for a Compact Action Chip.
     */
    suspend fun askPollinationsChat(
        history: List<AssistantChatMessage>,
        userMessage: String,
        language: AppLanguage,
        context: Context? = null,
    ): PollinationsChatTurn = withContext(Dispatchers.IO) {
        val clean = userMessage.trim()
        if (clean.isEmpty()) {
            return@withContext PollinationsChatTurn(
                replyText = "",
                recommendedToolId = null,
                recommendedToolLabel = null,
                fromCloudAi = false,
            )
        }
        if (context != null && !NetworkImmunityManager.pingReachable(context)) {
            return@withContext PollinationsChatTurn(
                replyText = com.example.audio.OnlineGuard.offlineErrorMessage(language),
                recommendedToolId = null,
                recommendedToolLabel = null,
                fromCloudAi = false,
            )
        }

        val replyLangHint = when (language) {
            AppLanguage.AR -> "Arabic (understand Iraqi, Gulf, Egyptian, Levantine, and Fusha naturally)"
            AppLanguage.EN -> "English"
            AppLanguage.FA -> "Persian"
        }

        val systemPrompt = buildString {
            append("You are the expert AI Sound Engineer inside Sonix Pure Audio Engineering Studio (100% free audio app; NO video rendering tools exist). ")
            append("Respond clearly and concisely in $replyLangHint (max 2-3 helpful sentences). Understand typos and spoken dialects (especially Iraqi, Gulf, Egyptian, Levantine, Fusha). ")
            append("Return STRICT valid JSON: {\"reply\":\"your concise friendly answer\",\"toolId\":\"best_single_tool_id_or_empty\",\"toolLabel\":\"short tool title\"}. ")
            append("Allowed toolId values ONLY: ")
            append("noise_reduction (3-stage AI noise cleaner + rumble/hiss filters), ")
            append("voice_changer (character voices like child, dragon, deep epic), ")
            append("human_tts (online Cloud Human Neural TTS & article reader), ")
            append("reels_audio_templates (audio-only Reels & Podcast presets normalized to -14 LUFS), ")
            append("extract_video (extract pure WAV/MP3/FLAC audio from a video file), ")
            append("smart_mixer (merge tracks, crossfade, overlay, auto-ducking music), ")
            append("audio_editor (trim, cut, fade, clip gain volume), ")
            append("presets_echo (3D Echo & broadcast presets), ")
            append("surround_stereo (M/S Surround Stereo live widener), ")
            append("equalizer (5-band live EQ), ")
            append("pitch_speed (live speed & pitch adjuster).")
        }

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            history.takeLast(8).forEach { msg ->
                put(JSONObject().apply {
                    put("role", if (msg.isUser) "user" else "assistant")
                    put("content", msg.text)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", clean)
            })
        }

        val rawResponse = queryPollinationsPostMessages(messagesArray, context)
            ?: queryPollinationsGet(systemPrompt, clean, context)

        if (!rawResponse.isNullOrBlank()) {
            val parsedTurn = parsePollinationsChatJson(rawResponse, clean, language)
            if (parsedTurn != null) return@withContext parsedTurn

            val inferredId = VALID_TOOL_IDS.firstOrNull { rawResponse.contains(it, ignoreCase = true) }
                ?: findBestToolWithTypoTolerance(clean).firstOrNull()?.toolId
            return@withContext PollinationsChatTurn(
                replyText = rawResponse.trim().take(520),
                recommendedToolId = inferredId,
                recommendedToolLabel = inferredId?.let { toolDisplayLabel(it, language) },
                fromCloudAi = true,
            )
        }

        val localMatch = findBestToolWithTypoTolerance(clean).firstOrNull()
        val fallbackText = localMatch?.let {
            if (language == AppLanguage.EN) {
                "${it.matchReason} Tap the button below to open ${it.toolName}."
            } else {
                "${it.matchReason} اضغط على الشريحة بالأسفل للانتقال مباشرة إلى الأداة."
            }
        } ?: if (language == AppLanguage.EN) {
            "Tell me what you want to do with your audio (remove noise, boost vocals, -14 LUFS Reels, extract audio from video, or merge tracks)."
        } else {
            "اشرح لي مشكلتك الصوتية (عزل ضوضاء، رفع صوت، قالب ريلز -14 LUFS، استخراج صوت من فيديو، أو دمج مقاطع) وسأوجهك للأداة الأنسب فوراً."
        }

        PollinationsChatTurn(
            replyText = fallbackText,
            recommendedToolId = localMatch?.toolId,
            recommendedToolLabel = localMatch?.toolId?.let { toolDisplayLabel(it, language) },
            fromCloudAi = false,
        )
    }

    suspend fun askPollinationsToolGuide(
        rawQuery: String,
        language: AppLanguage,
    ): PollinationsGuideResponse {
        val turn = askPollinationsChat(emptyList(), rawQuery, language)
        val matched = findBestToolWithTypoTolerance(rawQuery)
        val singleTool = turn.recommendedToolId?.let { id ->
            matched.firstOrNull { it.toolId == id } ?: ToolGuideResult(
                toolId = id,
                toolName = turn.recommendedToolLabel ?: toolDisplayLabel(id, language),
                matchReason = turn.replyText,
                howToUseSteps = emptyList(),
            )
        }
        return PollinationsGuideResponse(
            aiSummary = turn.replyText,
            tools = listOfNotNull(singleTool),
            fromCloudAi = turn.fromCloudAi,
        )
    }

    private suspend fun queryPollinationsPostMessages(
        messages: JSONArray,
        context: Context? = null,
    ): String? {
        val bodyJson = JSONObject().apply {
            put("messages", messages)
            put("model", "openai")
            put("jsonMode", true)
        }
        val outcome = NetworkImmunityManager.fetchTextWithFallback(
            urls = listOf(
                "https://text.pollinations.ai/",
                "https://text.pollinations.ai/openai",
            ),
            context = context,
            headers = mapOf("Accept" to "application/json, text/plain;q=0.9, */*;q=0.8"),
            postJsonBody = bodyJson.toString(),
            timeoutMs = NetworkImmunityManager.DEFAULT_NETWORK_TIMEOUT_MS,
            providerLabel = "PollinationsChatPOST",
        )
        return (outcome as? NetworkFetchOutcome.Success)?.value?.body?.takeIf { it.isNotBlank() }
    }

    private suspend fun queryPollinationsGet(
        systemPrompt: String,
        userQuery: String,
        context: Context? = null,
    ): String? {
        val encodedPrompt = URLEncoder.encode(userQuery, "UTF-8").replace("+", "%20")
        val encodedSys = URLEncoder.encode(systemPrompt, "UTF-8")
        val urlPrimary = "https://text.pollinations.ai/$encodedPrompt?model=openai&system=$encodedSys"
        val urlFallback = "https://text.pollinations.ai/$encodedPrompt?model=openai-fast&system=$encodedSys"
        val outcome = NetworkImmunityManager.fetchTextWithFallback(
            urls = listOf(urlPrimary, urlFallback),
            context = context,
            headers = mapOf("Accept" to "text/plain, application/json;q=0.9"),
            timeoutMs = NetworkImmunityManager.DEFAULT_NETWORK_TIMEOUT_MS,
            providerLabel = "PollinationsChatGET",
        )
        return (outcome as? NetworkFetchOutcome.Success)?.value?.body?.takeIf { it.isNotBlank() }
    }

    private fun parsePollinationsChatJson(
        raw: String,
        originalUserQuery: String,
        language: AppLanguage,
    ): PollinationsChatTurn? {
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

            val reply = jsonObj.optString("reply")
                .ifBlank { jsonObj.optString("summary") }
                .trim()
            if (reply.isEmpty()) return null

            var rawToolId = jsonObj.optString("toolId").trim()
            if (rawToolId !in VALID_TOOL_IDS) {
                val toolsArr = jsonObj.optJSONArray("tools")
                val firstId = toolsArr?.optJSONObject(0)?.optString("toolId")?.trim().orEmpty()
                rawToolId = if (firstId in VALID_TOOL_IDS) {
                    firstId
                } else {
                    findBestToolWithTypoTolerance(originalUserQuery).firstOrNull()?.toolId ?: ""
                }
            }
            val validId = rawToolId.takeIf { it in VALID_TOOL_IDS }
            val label = validId?.let { id ->
                jsonObj.optString("toolLabel").trim().ifBlank { toolDisplayLabel(id, language) }
            }

            PollinationsChatTurn(
                replyText = reply,
                recommendedToolId = validId,
                recommendedToolLabel = label,
                fromCloudAi = true,
            )
        }.getOrNull()
    }

    /**
     * Searches Archive.org public audio libraries separated cleanly into:
     * 1) Voices & Clips (speech, poetry, narrations, podcasts, vocal clips)
     * 2) Sound Effects - SFX (foley, horror, nature, gaming, impacts)
     */
    suspend fun searchOnlineCatalog(
        query: String,
        category: OnlineSearchCategory,
        context: Context? = null,
    ): Map<String, List<OnlineSoundItem>> = withContext(Dispatchers.IO) {
        if (context != null && !NetworkImmunityManager.pingReachable(context)) {
            return@withContext emptyMap()
        }
        val defaultQuery = when (category) {
            OnlineSearchCategory.VOICES_CLIPS -> "arabic speech podcast narration"
            OnlineSearchCategory.SOUND_EFFECTS -> "sound effect sfx ambient"
        }
        val cleanQuery = query.trim().ifEmpty { defaultQuery }
        val allSounds = mutableListOf<OnlineSoundItem>()
        try {
            val searchTerms = buildArchiveQuery(cleanQuery, category)
            val encoded = URLEncoder.encode(searchTerms, "UTF-8")
            val fallbackEncoded = URLEncoder.encode("mediatype:(audio) AND ($defaultQuery)", "UTF-8")
            val apiUrl =
                "https://archive.org/advancedsearch.php?q=$encoded" +
                    "&fl[]=identifier,title,subject,description&rows=18&page=1&output=json"
            val fallbackApiUrl =
                "https://archive.org/advancedsearch.php?q=$fallbackEncoded" +
                    "&fl[]=identifier,title,subject,description&rows=12&page=1&output=json"
            val outcome = NetworkImmunityManager.fetchTextWithFallback(
                urls = listOf(apiUrl, fallbackApiUrl),
                context = context,
                timeoutMs = NetworkImmunityManager.DEFAULT_NETWORK_TIMEOUT_MS,
                providerLabel = "ArchiveSearch",
            )
            if (outcome is NetworkFetchOutcome.Success) {
                val json = JSONObject(outcome.value.body)
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
                                val directUrl = resolveDirectPlayableArchiveUrl(id, context)
                                    ?: return@async null
                                val cat = classifyCategory(
                                    title = title,
                                    subject = subject,
                                    query = cleanQuery,
                                    mode = category,
                                )
                                OnlineSoundItem(
                                    identifier = id,
                                    title = title,
                                    category = cat,
                                    streamUrl = directUrl,
                                    source = "Archive.org",
                                    searchCategory = category,
                                )
                            }
                        }.awaitAll().filterNotNull()
                    }
                    allSounds.addAll(resolvedItems)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Return cleanly empty map when network errors occur
        }

        val orderedCategories = if (category == OnlineSearchCategory.VOICES_CLIPS) {
            listOf(CAT_VOICES, CAT_PODCASTS, CAT_CLIPS)
        } else {
            listOf(CAT_HORROR, CAT_NATURE, CAT_MUSIC, CAT_GAMING, CAT_GENERAL)
        }
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

    suspend fun searchInternetSoundsOrganized(query: String): Map<String, List<OnlineSoundItem>> =
        searchOnlineCatalog(query, OnlineSearchCategory.SOUND_EFFECTS)

    /**
     * Downloads any public track from Archive.org through NetworkImmunityManager (with payload shield),
     * automatically converting/extracting pure WAV stereo PCM via FFmpegKit if needed.
     */
    suspend fun downloadSoundToStudioBuffer(
        context: Context,
        item: OnlineSoundItem,
    ): StereoAudioBuffer? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "archive_sfx").apply { mkdirs() }
        val ext = item.streamUrl.substringAfterLast('.', "mp3").substringBefore('?').take(4).ifBlank { "mp3" }
        val tmpFile = File(dir, "dl_${UUID.randomUUID()}.$ext")
        val wavFallback = File(dir, "ext_${UUID.randomUUID()}.wav")
        return@withContext try {
            val outcome = NetworkImmunityManager.fetchAudioWithFallback(
                urls = listOf(item.streamUrl),
                context = context,
                timeoutMs = NetworkImmunityManager.DEFAULT_NETWORK_TIMEOUT_MS,
                providerLabel = "ArchiveDownload",
            )
            val bytes = (outcome as? NetworkFetchOutcome.Success)?.value?.bytes ?: return@withContext null
            tmpFile.writeBytes(bytes)
            SmartCacheManager.pruneManagedBuckets(context)
            AudioDecoder.decodeFile(tmpFile) ?: run {
                val cmd = "-y -i \"${tmpFile.absolutePath}\" -vn -ac 2 -ar 44100 -c:a pcm_s16le \"${wavFallback.absolutePath}\""
                val ok = FfmpegKitBridge.execute(
                    command = cmd,
                    toolName = "Archive.org Audio Decoder",
                    logNonZeroFailure = false,
                )
                if (ok && wavFallback.exists()) {
                    readStereoWavFile(wavFallback)
                } else {
                    null
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        } finally {
            tmpFile.delete()
            wavFallback.delete()
        }
    }

    private suspend fun resolveDirectPlayableArchiveUrl(
        identifier: String,
        context: Context? = null,
    ): String? {
        return try {
            val metaUrl = "https://archive.org/metadata/$identifier/files"
            val outcome = NetworkImmunityManager.fetchTextWithFallback(
                urls = listOf(metaUrl),
                context = context,
                timeoutMs = 12_000L,
                providerLabel = "ArchiveMetadata",
            )
            val text = (outcome as? NetworkFetchOutcome.Success)?.value?.body ?: return null
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
            NetworkImmunityManager.enforceHttps("https://archive.org/download/$identifier/$encodedFile")
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    private fun buildArchiveQuery(query: String, category: OnlineSearchCategory): String {
        val norm = normalizeArabicText(query)
        val mappedKeywords = if (category == OnlineSearchCategory.VOICES_CLIPS) {
            when {
                norm.containsAny("قران", "تلاوه", "نشيد", "انشوده") -> "quran recitation arabic vocal"
                norm.containsAny("بودكاست", "حوار", "مقابله", "قصه") -> "podcast speech narration talk"
                norm.containsAny("معلق", "شعر", "القاء", "خطاب") -> "voiceover poetry speech narration"
                norm.containsAny("موسيقي", "موسيقى", "عود", "بيانو") -> "oud piano acoustic vocal music"
                else -> {
                    val asciiOnly = query.replace(Regex("[^a-zA-Z0-9\\s]"), " ").trim()
                    if (asciiOnly.isNotBlank()) "$asciiOnly speech vocal audio" else "arabic speech vocal narration"
                }
            }
        } else {
            when {
                norm.containsAny("رعب", "شبح", "تنين", "وحش", "خرافي") -> "horror monster dark scary sound effect"
                norm.containsAny("مطر", "طبيعه", "غابه", "بحر", "ريح", "رعد") -> "rain nature forest thunder sfx"
                norm.containsAny("موسيقي", "موسيقى", "هادئه", "بيانو", "سينما") -> "cinematic dramatic transition sound effect"
                norm.containsAny("العاب", "اكشن", "ضربه", "سلاح", "انفجار", "ببجي") -> "game sound effect action impact foley"
                else -> {
                    val asciiOnly = query.replace(Regex("[^a-zA-Z0-9\\s]"), " ").trim()
                    if (asciiOnly.isNotBlank()) "$asciiOnly sound effect sfx" else "sound effect sfx foley"
                }
            }
        }
        return "($mappedKeywords) AND mediatype:(audio)"
    }

    private fun classifyCategory(
        title: String,
        subject: String,
        query: String,
        mode: OnlineSearchCategory,
    ): String {
        val text = normalizeArabicText("$title $subject $query")
        if (mode == OnlineSearchCategory.VOICES_CLIPS) {
            return when {
                text.containsAny("podcast", "interview", "talk", "radio", "بودكاست", "حوار", "مقابله", "راديو") -> CAT_PODCASTS
                text.containsAny("voice", "speech", "quran", "poetry", "narration", "صوت", "معلق", "القاء", "تلاوه", "شعر") -> CAT_VOICES
                else -> CAT_CLIPS
            }
        }
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
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
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
