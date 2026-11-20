package com.example.audio.ai

import android.content.Context
import androidx.compose.runtime.Immutable
import com.example.audio.AudioDecoder
import com.example.audio.OnlineGuard
import com.example.audio.cache.SmartCacheManager
import com.example.audio.network.NetworkFetchOutcome
import com.example.audio.network.NetworkImmunityManager
import com.example.audio.readStereoWavFile
import com.example.diagnostics.FfmpegKitBridge
import com.example.model.AppLanguage
import com.example.model.AssistantChatMessage
import com.example.model.OnlineSearchCategory
import com.example.model.StereoAudioBuffer
import com.example.util.SafeTextGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
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
@Serializable
data class OnlineSoundItem(
    val identifier: String,
    val title: String,
    val category: String,
    val streamUrl: String,
    val source: String,
    val searchCategory: OnlineSearchCategory = OnlineSearchCategory.SOUND_EFFECTS,
)

@Immutable
@Serializable
data class OnlineSoundHistoryItem(
    val sound: OnlineSoundItem,
    val listenedAtMs: Long,
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
    val isOfflineError: Boolean = false,
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

    private val RACING_MODELS = listOf("openai-fast", "mistral", "llama", "qwen")

    private val VALID_TOOL_IDS = setOf(
        "noise_reduction",
        "voice_changer",
        "reels_audio_templates",
        "extract_video",
        "smart_mixer",
        "audio_editor",
        "presets_echo",
        "surround_stereo",
        "equalizer",
        "pitch_speed",
    )

    private val AUDIO_INTENT_KEYWORDS = listOf(
        "ضوضاء", "ضوجه", "ضجه", "صوت", "ميكس", "دمج", "قص", "اكو", "صدى", "تردد",
        "ريلز", "بودكاست", "فيديو", "تنين", "شبح", "وحش", "رعب", "مقال", "نص",
        "باس", "مكتوم", "سرعه", "طبقه", "تلاوه", "تفريغ", "محيطي", "ستيريو",
        "noise", "denoise", "voice", "echo", "reverb", "eq", "bass", "mix", "trim",
        "reels", "podcast", "video", "extract", "speed", "pitch", "stereo", "help", "tool",
    )

    private val CONTEXTUAL_AUDIO_THESAURUS: List<Pair<List<String>, String>> = listOf(
        listOf("مطر", "امطار", "شتاء", "غيث", "rain") to "rain thunderstorm downpour water storm",
        listOf("رعد", "برق", "عاصفه", "صاعقه", "thunder") to "thunderstorm lightning storm rumble",
        listOf("طبيعه", "غابه", "اشجار", "طير", "طيور", "عصافير", "nature", "forest") to "forest birds nature ambient woodland",
        listOf("بحر", "موج", "امواج", "شاطئ", "محيط", "نهر", "ocean", "sea", "wave") to "ocean waves sea water river stream",
        listOf("ريح", "رياح", "هواء", "نسيم", "wind") to "wind breeze howling ambient air",
        listOf("نار", "حريق", "خشب", "مدفاه", "fire") to "fire crackling campfire flame burn",
        listOf("رعب", "مخيف", "مرعب", "فزع", "خوف", "horror", "scary") to "horror scary creepy spooky dark eerie",
        listOf("شبح", "اشباح", "جن", "همس", "ghost") to "ghost whisper eerie haunt paranormal",
        listOf("وحش", "تنين", "ديناصور", "زئير", "monster", "dragon") to "monster dragon roar beast creature growl",
        listOf("فوش", "انتقال", "سويش", "سينما", "سينمائي", "whoosh", "transition", "cinematic") to "whoosh swoosh transition cinematic impact riser",
        listOf("انفجار", "قنبله", "تدمير", "explosion") to "explosion blast boom impact debris",
        listOf("سلاح", "رصاص", "طلق", "مسدس", "سيف", "معركه", "حرب", "اكشن", "action", "weapon") to "gunshot sword weapon battle combat action",
        listOf("العاب", "لعبه", "ببجي", "جيم", "اركيد", "ليزر", "game", "gaming") to "game arcade laser powerup hit ui sound",
        listOf("خطوات", "مشي", "ركض", "جري", "footsteps") to "footsteps walking running gravel wood",
        listOf("باب", "طرق", "قفل", "مفتاح", "door") to "door knock creak slam lock open",
        listOf("جرس", "منبه", "انذار", "ساعه", "صفاره", "bell", "alarm") to "bell alarm clock chime siren ring",
        listOf("سياره", "محرك", "دراجة", "طائره", "قطار", "car", "engine") to "car engine vehicle traffic train airplane",
        listOf("موسيقى", "موسيقي", "لحن", "نغمه", "هادئه", "حزين", "music", "sad", "calm") to "instrumental ambient piano melody soundtrack",
        listOf("بيانو", "كمان", "عود", "جيتار", "ناي", "طبل", "ايقاع", "piano", "guitar", "oud") to "piano violin oud acoustic guitar flute drum",
        listOf("لوفاي", "تشيل", "تامل", "استرخاء", "نوم", "lofi", "relax", "sleep") to "lofi chill meditation ambient sleep calm",
        listOf("بودكاست", "حوار", "مقابله", "راديو", "اذاعه", "podcast", "radio", "talk") to "podcast radio talk show interview broadcast",
        listOf("معلق", "وثائقي", "قصه", "راوي", "خطاب", "القاء", "شعر", "voiceover", "narration") to "voiceover narration storytelling poetry speech monologue",
        listOf("قران", "تلاوه", "اذان", "دعاء", "نشيد", "انشوده", "ابتهال", "quran") to "quran recitation arabic vocal islamic chant",
        listOf("طفل", "اطفال", "ضحك", "بكاء", "baby", "laugh", "cry") to "baby child laughter crying crowd human",
        listOf("تصفيق", "جمهور", "ملعب", "تشجيع", "حفله", "applause", "crowd") to "applause crowd cheering stadium audience",
        listOf("فضاء", "روبوت", "الي", "خيال علمي", "تكنولوجيا", "كومبيوتر", "space", "scifi", "robot") to "scifi space robot futuristic synth tech ui",
        listOf("كوميدي", "مضحك", "فكاهي", "كرتون", "comedy", "cartoon") to "cartoon comedy funny boing pop humor",
        listOf("هاتف", "تلفون", "مكالمه", "رساله", "اشعار", "phone", "notification") to "telephone ringtone dial chime notification beep",
    )

    fun toolDisplayLabel(toolId: String, language: AppLanguage): String = when (toolId) {
        "noise_reduction" -> if (language == AppLanguage.EN) "Voice Purifier & Denoise" else "منقّي الصوت وعزل الضوضاء"
        "voice_changer" -> if (language == AppLanguage.EN) "Voice & Mythical FX" else "تغيير الصوت والشخصيات"
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
     * Zero-Latency local responder for simple greetings and pleasantries (<5ms)
     * so users never wait several seconds for a casual "هلو / شلونك / مرحبا".
     */
    private fun detectInstantGreetingReply(userText: String, language: AppLanguage): PollinationsChatTurn? {
        val norm = normalizeArabicText(userText)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (norm.isEmpty() || norm.length > 48) return null

        // Do not intercept if user is also asking a real audio tool question
        if (AUDIO_INTENT_KEYWORDS.any { norm.contains(it) }) return null

        val greetings = setOf(
            "هلو", "هلا", "هلاو", "هلوات", "مرحبا", "اهلا", "اهلا وسهلا", "اهلين",
            "السلام عليكم", "سلام عليكم", "سلام", "صباح الخير", "مساء الخير",
            "شلونك", "شلونكم", "شخبارك", "شخباركم", "شوكو ماكو", "شكو ماكو",
            "كيفك", "كيف حالك", "اخبارك", "هلا والله", "اهلا بك",
            "هلو شلونك", "مرحبا شلونك", "السلام عليكم شلونك", "شلونك شخبارك",
            "شكرا", "مشكور", "يسلمو", "تسلم", "يعطيك العافيه", "عاشت ايدك",
            "hello", "hi", "hey", "hi there", "hello there", "good morning",
            "good evening", "how are you", "thanks", "thank you",
            "سلام خوبی", "خوبی", "چطوری", "ممنون",
        )

        val words = norm.split(' ')
        val isGreetingOnly = norm in greetings || (words.size <= 3 && words.all { it in greetings || it == "والله" || it == "يا" || it == "حبيبي" || it == "صديقي" || it == "اخي" || it == "bro" })
        if (!isGreetingOnly) return null

        val isThanks = norm.containsAny("شكرا", "مشكور", "يسلمو", "تسلم", "عافيه", "عاشت", "thank", "ممنون")
        val reply = when (language) {
            AppLanguage.EN -> if (isThanks) {
                "You're very welcome! Anytime you need help mixing, cleaning noise, or shaping vocals in Sonix, just ask."
            } else {
                "Hello! Doing great and ready to engineer your audio. Tell me what you're working on today (cleaning noise, voice FX, Reels -14 LUFS, or mixing)!"
            }
            AppLanguage.FA -> if (isThanks) {
                "خواهش می‌کنم! هر زمان برای پاک‌سازی صدا، میکس یا افکت‌های صوتی در Sonix کمکی خواستید در خدمتم."
            } else {
                "سلام و درود! من عالی هستم و آماده کمک به مهندسی صدای شما هستم. بگویید روی چه پروژه‌ای کار می‌کنید؟"
            }
            AppLanguage.TR -> if (isThanks) {
                "Rica ederim! Sonix'te ses temizliği, miks veya vokal düzenleme için her zaman yardımcı olabilirim."
            } else {
                "Merhaba! Ses mühendisliği konusunda yardımcı olmaya hazırım. Bugün hangi proje üzerinde çalışıyorsunuz?"
            }
            AppLanguage.AR -> if (isThanks) {
                "حبيبي تدلل! أي وقت تحتاج مساعدة بتصفية الصوت، الهندسة، أو المونتاج الصوتي في Sonix أنا بالخدمة دائماً."
            } else {
                "هلا بيك يا غالي! بخير والحمد لله، وجاهز أساعدك بكل ما يخص هندسة الصوت. شنو حاب نشتغل عليه اليوم (عزل ضوضاء، تحسين بودكاست، تغيير صوت، لو مونتاج وميكس)؟"
            }
        }

        return PollinationsChatTurn(
            replyText = reply,
            recommendedToolId = null,
            recommendedToolLabel = null,
            fromCloudAi = true,
            isOfflineError = false,
        )
    }

    /**
     * Ultra-Fast Cloud AI Sound Engineer chat:
     * 1. Zero-Latency response (<5ms) for simple greetings (requires online state).
     * 2. Parallel Server Racing across 4 free Pollinations models (openai-fast, mistral, llama, qwen)
     *    returning the fastest valid answer and immediately cancelling the remaining coroutines.
     * 3. Strict SafeTextGuard cleaning guaranteeing zero raw JSON or code leaks in UI bubbles.
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
        if (context != null && OnlineGuard.requireOnlineOrError(context, language) != null) {
            return@withContext PollinationsChatTurn(
                replyText = OnlineGuard.offlineErrorMessage(language),
                recommendedToolId = null,
                recommendedToolLabel = null,
                fromCloudAi = false,
                isOfflineError = true,
            )
        }

        // Zero-Latency local responder for simple greetings
        detectInstantGreetingReply(clean, language)?.let { instant ->
            return@withContext instant
        }

        if (context != null && !NetworkImmunityManager.pingReachable(context)) {
            return@withContext PollinationsChatTurn(
                replyText = OnlineGuard.offlineErrorMessage(language),
                recommendedToolId = null,
                recommendedToolLabel = null,
                fromCloudAi = false,
                isOfflineError = true,
            )
        }

        val replyLangHint = when (language) {
            AppLanguage.AR -> "Arabic (understand Iraqi, Gulf, Egyptian, Levantine, and Fusha naturally)"
            AppLanguage.EN -> "English"
            AppLanguage.FA -> "Persian"
            AppLanguage.TR -> "Turkish"
        }

        val systemPrompt = buildString {
            append("You are the expert AI Sound Engineer inside Sonix Pure Audio Engineering Studio (100% free audio app; NO video rendering tools exist). ")
            append("Respond clearly and concisely in $replyLangHint (max 2-3 helpful sentences). Understand typos and spoken dialects (especially Iraqi, Gulf, Egyptian, Levantine, Fusha). ")
            append("Return STRICT valid JSON only with NO extra text: {\"reply\":\"your concise friendly answer\",\"toolId\":\"best_single_tool_id_or_empty\",\"toolLabel\":\"short tool title\"}. ")
            append("Leave toolId as \"\" if the user is just chatting or no specific studio tool applies. ")
            append("Allowed non-empty toolId values ONLY: ")
            append("noise_reduction, voice_changer, reels_audio_templates, extract_video, smart_mixer, audio_editor, presets_echo, surround_stereo, equalizer, pitch_speed.")
        }

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            history.takeLast(6).forEach { msg ->
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

        // Parallel 4-model racing: fastest valid reply wins immediately and cancels siblings
        val racedTurn = racePollinationsModels(
            models = RACING_MODELS,
            messagesArray = messagesArray,
            systemPrompt = systemPrompt,
            userQuery = clean,
            language = language,
        )
        if (racedTurn != null) {
            return@withContext racedTurn
        }

        // Backup GET wave through NetworkImmunityManager retry shield if POST race failed
        val backupRaw = queryPollinationsGetBackup(systemPrompt, clean, context)
        if (!backupRaw.isNullOrBlank()) {
            val extracted = SafeTextGuard.extractCleanAssistantReply(backupRaw, VALID_TOOL_IDS)
            if (extracted.replyText.isNotBlank() && !looksLikeHtmlOrServerError(extracted.replyText)) {
                val validId = extracted.toolId
                return@withContext PollinationsChatTurn(
                    replyText = extracted.replyText.take(650),
                    recommendedToolId = validId,
                    recommendedToolLabel = validId?.let { extracted.toolLabel ?: toolDisplayLabel(it, language) },
                    fromCloudAi = true,
                )
            }
        }

        val localMatch = findBestToolWithTypoTolerance(clean).firstOrNull()
        val fallbackText = localMatch?.let {
            when (language) {
                AppLanguage.EN -> "${it.matchReason} Tap the button below to open ${it.toolName}."
                AppLanguage.FA -> "${it.matchReason} برای باز کردن ابزار روی دکمه زیر بزنید."
                AppLanguage.TR -> "${it.matchReason} Aracı açmak için aşağıdaki düğmeye dokunun."
                AppLanguage.AR -> "${it.matchReason} اضغط على زر الأداة بالأسفل للانتقال إليها مباشرة."
            }
        } ?: when (language) {
            AppLanguage.EN -> "Tell me what you want to do with your audio (remove noise, boost vocals, -14 LUFS Reels, extract audio from video, or merge tracks)."
            AppLanguage.FA -> "بفرمایید روی صدا چه کاری می‌خواهید انجام دهید (حذف نویز، تقویت صدا، قالب ریلز -14 LUFS یا میکس) تا دقیق راهنمایی کنم."
            AppLanguage.TR -> "Sesinizle ne yapmak istediğinizi söyleyin (gürültüyü giderme, vokalleri güçlendirme, Reels sesini düzenleme veya parçaları birleştirme)."
            AppLanguage.AR -> "اشرح لي مشكلتك الصوتية (عزل ضوضاء، رفع صوت، قالب ريلز -14 LUFS، استخراج صوت من فيديو، أو دمج مقاطع) وسأساعدك فوراً."
        }

        PollinationsChatTurn(
            replyText = fallbackText,
            recommendedToolId = localMatch?.toolId,
            recommendedToolLabel = localMatch?.toolId?.let { toolDisplayLabel(it, language) },
            fromCloudAi = false,
        )
    }

    /**
     * Fires all 4 free Pollinations models simultaneously, picks the first clean valid response,
     * and cancels the slower 3 sibling coroutines right away.
     */
    private suspend fun racePollinationsModels(
        models: List<String>,
        messagesArray: JSONArray,
        systemPrompt: String,
        userQuery: String,
        language: AppLanguage,
    ): PollinationsChatTurn? = withTimeoutOrNull(16_000L) {
        coroutineScope {
            val winnerChannel = Channel<PollinationsChatTurn?>(capacity = models.size)
            val encodedPrompt = URLEncoder.encode(userQuery, "UTF-8").replace("+", "%20")
            val encodedSys = URLEncoder.encode(systemPrompt, "UTF-8")

            val jobs = models.mapIndexed { idx, model ->
                launch(Dispatchers.IO, start = CoroutineStart.DEFAULT) {
                    val result: PollinationsChatTurn? = try {
                        val useGetFallbackFirst = false
                        val bodyJson = JSONObject().apply {
                            put("messages", messagesArray)
                            put("model", model)
                            put("jsonMode", true)
                            put("seed", 100 + idx)
                        }
                        val postOutcome = NetworkImmunityManager.fetchTextFast(
                            url = "https://text.pollinations.ai/",
                            headers = mapOf("Accept" to "application/json, text/plain;q=0.9, */*;q=0.8"),
                            postJsonBody = bodyJson.toString(),
                            connectTimeoutMs = 5_500,
                            readTimeoutMs = 11_000,
                            timeoutMs = 12_500L,
                        )
                        var raw = (postOutcome as? NetworkFetchOutcome.Success)?.value?.body

                        if (raw.isNullOrBlank() || looksLikeHtmlOrServerError(raw)) {
                            val getUrl = "https://text.pollinations.ai/$encodedPrompt?model=$model&json=true&system=$encodedSys"
                            val getOutcome = NetworkImmunityManager.fetchTextFast(
                                url = getUrl,
                                headers = mapOf("Accept" to "text/plain, application/json;q=0.9"),
                                connectTimeoutMs = 5_500,
                                readTimeoutMs = 10_000,
                                timeoutMs = 11_500L,
                            )
                            raw = (getOutcome as? NetworkFetchOutcome.Success)?.value?.body
                        }

                        if (!raw.isNullOrBlank() && !looksLikeHtmlOrServerError(raw)) {
                            val extracted = SafeTextGuard.extractCleanAssistantReply(raw, VALID_TOOL_IDS)
                            if (extracted.replyText.isNotBlank() && !looksLikeHtmlOrServerError(extracted.replyText)) {
                                val validId = extracted.toolId
                                PollinationsChatTurn(
                                    replyText = extracted.replyText.take(650),
                                    recommendedToolId = validId,
                                    recommendedToolLabel = validId?.let { extracted.toolLabel ?: toolDisplayLabel(it, language) },
                                    fromCloudAi = true,
                                )
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        null
                    }
                    winnerChannel.trySend(result)
                }
            }

            var receivedCount = 0
            var winner: PollinationsChatTurn? = null
            while (receivedCount < jobs.size) {
                val item = winnerChannel.receive()
                receivedCount++
                if (item != null) {
                    winner = item
                    break
                }
            }
            jobs.forEach { it.cancel() }
            winnerChannel.close()
            winner
        }
    }

    private suspend fun queryPollinationsGetBackup(
        systemPrompt: String,
        userQuery: String,
        context: Context? = null,
    ): String? {
        val encodedPrompt = URLEncoder.encode(userQuery, "UTF-8").replace("+", "%20")
        val encodedSys = URLEncoder.encode(systemPrompt, "UTF-8")
        val urls = listOf(
            "https://text.pollinations.ai/$encodedPrompt?model=openai-fast&system=$encodedSys",
            "https://text.pollinations.ai/$encodedPrompt?model=mistral&system=$encodedSys",
        )
        val outcome = NetworkImmunityManager.fetchTextWithFallback(
            urls = urls,
            context = context,
            headers = mapOf("Accept" to "text/plain, application/json;q=0.9"),
            timeoutMs = 18_000L,
            providerLabel = "PollinationsChatBackup",
        )
        return (outcome as? NetworkFetchOutcome.Success)?.value?.body?.takeIf { it.isNotBlank() }
    }

    private fun looksLikeHtmlOrServerError(text: String): Boolean {
        val lower = text.trimStart().lowercase()
        return lower.startsWith("<!doctype") ||
            lower.startsWith("<html") ||
            lower.startsWith("<head") ||
            lower.contains("502 bad gateway") ||
            lower.contains("504 gateway") ||
            lower.contains("cloudflare") ||
            lower.contains("rate limit exceeded")
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

    /**
     * Context-Aware Archive.org Online Audio Library Search:
     * Translates Arabic audio concepts into rich English & bilingual audio keywords behind the scenes
     * and resolves 25–30 real playable Archive.org audio files concurrently.
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
            OnlineSearchCategory.VOICES_CLIPS -> "podcast speech narration"
            OnlineSearchCategory.SOUND_EFFECTS -> "sound effect sfx ambient"
        }
        val cleanQuery = query.trim().ifEmpty { defaultQuery }
        val collected = LinkedHashMap<String, OnlineSoundItem>(32)

        try {
            val queriesToRun = buildContextualArchiveQueries(cleanQuery, category)
            val candidateDocs = LinkedHashMap<String, Triple<String, String, String>>(64)

            for (archiveQuery in queriesToRun) {
                if (candidateDocs.size >= 48) break
                val encoded = URLEncoder.encode(archiveQuery, "UTF-8")
                val apiUrl =
                    "https://archive.org/advancedsearch.php?q=$encoded" +
                        "&fl[]=identifier,title,subject,description&rows=55&page=1&output=json"
                val outcome = NetworkImmunityManager.fetchTextWithFallback(
                    urls = listOf(apiUrl),
                    context = context,
                    timeoutMs = 20_000L,
                    providerLabel = "ArchiveSearch",
                )
                if (outcome is NetworkFetchOutcome.Success) {
                    val json = SafeTextGuard.safeParseJsonObject(outcome.value.body)
                    val docs = json?.optJSONObject("response")?.optJSONArray("docs")
                    if (docs != null) {
                        for (idx in 0 until docs.length()) {
                            val doc = docs.optJSONObject(idx) ?: continue
                            val id = doc.optString("identifier").trim()
                            if (id.isEmpty() || candidateDocs.containsKey(id)) continue
                            val title = doc.optString("title").ifBlank { id }
                            val subject = doc.optString("subject", "")
                            candidateDocs[id] = Triple(id, title, subject)
                        }
                    }
                }
            }

            // Concurrent resolution in batches using Semaphore(10) until 25–30 real playable items found
            val candidates = candidateDocs.values.toList().take(48)
            val semaphore = Semaphore(10)
            for (batch in candidates.chunked(12)) {
                if (collected.size >= 30) break
                val resolvedBatch = coroutineScope {
                    batch.map { (id, title, subject) ->
                        async {
                            semaphore.withPermit {
                                val directUrl = resolveDirectPlayableArchiveUrl(id)
                                    ?: return@withPermit null
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
                        }
                    }.awaitAll().filterNotNull()
                }
                for (item in resolvedBatch) {
                    if (collected.size >= 30) break
                    collected.putIfAbsent(item.identifier, item)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Return cleanly collected items (or empty) when network errors occur
        }

        val orderedCategories = if (category == OnlineSearchCategory.VOICES_CLIPS) {
            listOf(CAT_VOICES, CAT_PODCASTS, CAT_CLIPS)
        } else {
            listOf(CAT_HORROR, CAT_NATURE, CAT_MUSIC, CAT_GAMING, CAT_GENERAL)
        }
        val grouped = linkedMapOf<String, List<OnlineSoundItem>>()
        val byCategory = collected.values.groupBy { it.category }
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
            java.io.BufferedOutputStream(java.io.FileOutputStream(tmpFile), 8192).use { out ->
                out.write(bytes)
                out.flush()
            }
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

    private suspend fun resolveDirectPlayableArchiveUrl(identifier: String): String? {
        return try {
            val metaUrl = "https://archive.org/metadata/$identifier/files"
            val outcome = NetworkImmunityManager.fetchTextFast(
                url = metaUrl,
                connectTimeoutMs = 4_500,
                readTimeoutMs = 6_500,
                timeoutMs = 7_500L,
            )
            val text = (outcome as? NetworkFetchOutcome.Success)?.value?.body ?: return null
            val resultArr = SafeTextGuard.safeParseJsonObject(text)?.optJSONArray("result") ?: return null

            var bestMp3: String? = null
            var bestAlt: String? = null
            for (i in 0 until resultArr.length()) {
                val entry = resultArr.optJSONObject(i) ?: continue
                val name = entry.optString("name")
                val size = entry.optString("size").toLongOrNull() ?: 0L
                if (name.isEmpty() || size > 35_000_000L) continue
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

    /**
     * Context-Aware Arabic-to-English + Bilingual Audio Query Expansion:
     * Produces prioritized Archive.org queries so searching in Arabic yields 25-30 accurate English & Arabic audio results.
     */
    private fun buildContextualArchiveQueries(query: String, category: OnlineSearchCategory): List<String> {
        val norm = normalizeArabicText(query)
        val matchedEnglishTerms = mutableListOf<String>()
        for ((triggers, enExpansion) in CONTEXTUAL_AUDIO_THESAURUS) {
            if (triggers.any { norm.contains(it) }) {
                matchedEnglishTerms.addAll(enExpansion.split(' '))
            }
        }
        val asciiWords = query
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }

        val combinedEnglish = (asciiWords + matchedEnglishTerms).distinct().take(7)
        val hasArabic = query.any { it in '\u0600'..'\u06FF' }
        val queries = mutableListOf<String>()

        if (combinedEnglish.isNotEmpty()) {
            val enOrClause = combinedEnglish.joinToString(" OR ")
            val modeTag = if (category == OnlineSearchCategory.VOICES_CLIPS) "speech OR vocal OR podcast OR audio" else "sfx OR sound OR effect OR audio"
            queries.add("($enOrClause) AND ($modeTag) AND mediatype:(audio)")
            queries.add("($enOrClause) AND mediatype:(audio)")
        }

        if (hasArabic) {
            val cleanAr = query.replace(Regex("[\"()]"), " ").trim()
            if (cleanAr.isNotEmpty()) {
                queries.add("($cleanAr) AND mediatype:(audio)")
            }
        }

        val categoryFallback = if (category == OnlineSearchCategory.VOICES_CLIPS) {
            "(arabic speech OR podcast OR narration OR audiobook OR voice) AND mediatype:(audio)"
        } else {
            "(sound effects OR foley OR ambient OR cinematic sfx) AND mediatype:(audio)"
        }
        queries.add(categoryFallback)

        return queries.distinct()
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
            text.containsAny("rain", "forest", "wind", "thunder", "ocean", "bird", "nature", "ambient", "fire", "مطر", "غابه", "طبيعه", "ريح", "بحر", "نار") ->
                CAT_NATURE
            text.containsAny("music", "piano", "orchestra", "melody", "lofi", "synth", "guitar", "violin", "oud", "موسيقي", "موسيقى", "لحن", "بيانو", "عود") ->
                CAT_MUSIC
            text.containsAny("game", "laser", "explosion", "sword", "punch", "gun", "arcade", "action", "weapon", "لعبه", "العاب", "اكشن", "انفجار", "سلاح") ->
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
