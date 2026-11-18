package com.example.audio.aivoice

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class AiVoiceEngineKind(
    val id: String,
    val modelRepoId: String,
    val titleAr: String,
    val titleEn: String,
    val badgeAr: String,
    val badgeEn: String,
    val requiresRemoteGpu: Boolean,
) {
    VOXCPM_2(
        id = "voxcpm2",
        modelRepoId = "openbmb/VoxCPM2",
        titleAr = "VoxCPM2 (المحرك الأساسي العالمي)",
        titleEn = "VoxCPM2 (Primary Multilingual)",
        badgeAr = "48kHz • تصميم + استنساخ • 30 لغة",
        badgeEn = "48kHz • Design + Cloning • 30 Langs",
        requiresRemoteGpu = true,
    ),
    AUDAR_TTS_V1_FLASH(
        id = "audar_tts_flash",
        modelRepoId = "audarai/Audar-TTS-V1-Flash",
        titleAr = "Audar-TTS-V1-Flash (عربي مرخص تجارياً)",
        titleEn = "Audar-TTS-V1-Flash (Commercial Arabic)",
        badgeAr = "24kHz • لهجات عربية + مشاعر • Open License v1.0",
        badgeEn = "24kHz • Arabic Dialects + Expressive • Open License",
        requiresRemoteGpu = true,
    ),
    AUDAR_TTS_V1_TURBO(
        id = "audar_tts_v1_turbo",
        modelRepoId = "audarai/Audar-TTS-V1-Turbo",
        titleAr = "Audar-TTS-V1-Turbo (يتطلب موافقة تجارية)",
        titleEn = "Audar-TTS-V1-Turbo (Requires Commercial Approval)",
        badgeAr = "24kHz • لهجات عربية + مشاعر [whispers/laughs]",
        badgeEn = "24kHz • Arabic Dialects + Expressive Tags",
        requiresRemoteGpu = true,
    ),
    SONIX_LOCAL_STUDIO(
        id = "sonix_local_studio",
        modelRepoId = "local/sonix-studio",
        titleAr = "محرك الاستوديو المحلي (بديل بدون سيرفر)",
        titleEn = "Local Studio Engine (Offline Fallback)",
        badgeAr = "محلي • بدون GPU خارجي",
        badgeEn = "On-Device • No GPU Server Needed",
        requiresRemoteGpu = false,
    ),
    EDGE_NEURAL_FREE(
        id = "edge_neural_free",
        modelRepoId = "edge/neural-readaloud",
        titleAr = "Edge Neural Arabic TTS (سحابي مجاني)",
        titleEn = "Edge Neural TTS (Free Cloud Arabic)",
        badgeAr = "24kHz • 32 صوتاً عربياً + لهجات • بدون GPU أو بطاقة",
        badgeEn = "24kHz • 32 Arabic Voices + Dialects • Free No Card",
        requiresRemoteGpu = false,
    ),
    ;

    fun supportedEmotions(): List<AiVoiceEmotion> = when (this) {
        VOXCPM_2, SONIX_LOCAL_STUDIO -> AiVoiceEmotion.entries
        AUDAR_TTS_V1_FLASH, AUDAR_TTS_V1_TURBO -> listOf(
            AiVoiceEmotion.NEUTRAL,
            AiVoiceEmotion.SAD,
            AiVoiceEmotion.CRYING,
            AiVoiceEmotion.JOY,
            AiVoiceEmotion.EXCITED,
            AiVoiceEmotion.SURPRISED,
            AiVoiceEmotion.WHISPER,
            AiVoiceEmotion.LAUGH,
        )
        EDGE_NEURAL_FREE -> listOf(
            AiVoiceEmotion.NEUTRAL,
            AiVoiceEmotion.CALM,
            AiVoiceEmotion.SAD,
            AiVoiceEmotion.JOY,
            AiVoiceEmotion.EXCITED,
            AiVoiceEmotion.ANGER,
        )
    }

    fun supportsVoiceDesignPrompt(): Boolean = this == VOXCPM_2

    fun supportsEmotionIntensity(): Boolean =
        this == VOXCPM_2 || this == AUDAR_TTS_V1_FLASH || this == AUDAR_TTS_V1_TURBO

    fun supportsPitchShiftControl(): Boolean =
        this == SONIX_LOCAL_STUDIO || this == EDGE_NEURAL_FREE

    fun supportsVoiceCloning(): Boolean =
        this == VOXCPM_2 || this == AUDAR_TTS_V1_FLASH || this == AUDAR_TTS_V1_TURBO

    fun supportsLanguage(language: AiVoiceLanguage): Boolean = when (this) {
        AUDAR_TTS_V1_FLASH, AUDAR_TTS_V1_TURBO -> language.audarSupported
        SONIX_LOCAL_STUDIO -> language in setOf(
            AiVoiceLanguage.AR,
            AiVoiceLanguage.EN,
            AiVoiceLanguage.FA,
            AiVoiceLanguage.TR,
        )
        VOXCPM_2, EDGE_NEURAL_FREE -> language.voxCpmSupported
    }

    fun supportedLanguages(): List<AiVoiceLanguage> =
        AiVoiceLanguage.entries.filter { supportsLanguage(it) }

    val isUserSelectable: Boolean
        get() = this != AUDAR_TTS_V1_TURBO
}

@Serializable
enum class AiVoiceLanguage(
    val code: String,
    val titleAr: String,
    val titleEn: String,
    val nativeName: String,
    val voxCpmSupported: Boolean = true,
    val audarSupported: Boolean = false,
) {
    AR("ar", "العربية (أولوية قصوى)", "Arabic (Priority)", "العربية", voxCpmSupported = true, audarSupported = true),
    EN("en", "الإنجليزية", "English", "English", voxCpmSupported = true, audarSupported = true),
    FR("fr", "الفرنسية", "French", "Français", voxCpmSupported = true, audarSupported = false),
    ES("es", "الإسبانية", "Spanish", "Español", voxCpmSupported = true, audarSupported = false),
    DE("de", "الألمانية", "German", "Deutsch", voxCpmSupported = true, audarSupported = false),
    TR("tr", "التركية", "Turkish", "Türkçe", voxCpmSupported = true, audarSupported = false),
    FA("fa", "الفارسية", "Persian", "فارسی", voxCpmSupported = true, audarSupported = false),
    ZH("zh", "الصينية", "Chinese", "中文", voxCpmSupported = true, audarSupported = false),
    JA("ja", "اليابانية", "Japanese", "日本語", voxCpmSupported = true, audarSupported = false),
    KO("ko", "الكورية", "Korean", "한국어", voxCpmSupported = true, audarSupported = false),
    RU("ru", "الروسية", "Russian", "Русский", voxCpmSupported = true, audarSupported = false),
    IT("it", "الإيطالية", "Italian", "Italiano", voxCpmSupported = true, audarSupported = false),
    PT("pt", "البرتغالية", "Portuguese", "Português", voxCpmSupported = true, audarSupported = false),
    HI("hi", "الهندية", "Hindi", "हिन्दी", voxCpmSupported = true, audarSupported = false),
    UR("ur", "الأردية", "Urdu", "اردو", voxCpmSupported = true, audarSupported = false),
    ID("id", "الإندونيسية", "Indonesian", "Bahasa Indonesia", voxCpmSupported = true, audarSupported = false),
}

@Serializable
enum class AiArabicDialect(
    val id: String,
    val titleAr: String,
    val titleEn: String,
    val promptHintEn: String,
    val promptHintAr: String,
    val localeTag: String,
) {
    MSA("msa", "العربية الفصحى", "Modern Standard Arabic", "Modern Standard Arabic (Fusha)", "بالعربية الفصحى السليمة الواضحة", "ar-SA"),
    IRAQI("iraqi", "اللهجة العراقية", "Iraqi Dialect", "authentic Iraqi Arabic dialect", "باللهجة العراقية الأصيلة الطبيعية", "ar-IQ"),
    GULF("gulf", "اللهجة الخليجية", "Gulf / Emirati Dialect", "natural Gulf Emirati Arabic dialect", "باللهجة الخليجية الإماراتية الطبيعية", "ar-AE"),
    SAUDI("saudi", "اللهجة السعودية", "Saudi Dialect", "natural Saudi Najdi/Hijazi Arabic dialect", "باللهجة السعودية الطبيعية", "ar-SA"),
    LEVANTINE("levantine", "اللهجة الشامية", "Levantine / Syrian Dialect", "natural Levantine Syrian-Lebanese Arabic dialect", "باللهجة الشامية الدافئة الطبيعية", "ar-SY"),
    EGYPTIAN("egyptian", "اللهجة المصرية", "Egyptian Dialect", "natural Egyptian Cairo Arabic dialect", "باللهجة المصرية الطبيعية", "ar-EG"),
    MOROCCAN("moroccan", "اللهجة المغربية", "Moroccan Dialect", "natural Moroccan Darija Arabic dialect", "باللهجة المغربية الطبيعية", "ar-MA"),
    JORDANIAN("jordanian", "اللهجة الأردنية", "Jordanian Dialect", "natural Jordanian Arabic dialect", "باللهجة الأردنية الطبيعية", "ar-JO"),
}

@Serializable
enum class AiVoiceEmotion(
    val id: String,
    val titleAr: String,
    val titleEn: String,
    val audarTag: String?,
    val voxDesignHintEn: String,
    val pitchBiasSemitones: Float,
    val speedBiasFactor: Float,
) {
    NEUTRAL("neutral", "طبيعي متزن", "Natural / Neutral", null, "natural balanced warm tone", 0f, 1.0f),
    SAD("sad", "الحزن والانكسار", "Sadness & Heartbreak", "[sighs]", "deeply sad, heartbroken, melancholic tone", -0.8f, 0.88f),
    CRYING("crying", "البكاء والتأثر", "Crying & Emotional", "[sighs]", "trembling, tearful, choked-up crying emotion", -0.4f, 0.84f),
    JOY("joy", "الفرح والسعادة", "Joy & Happiness", "[excited]", "bright, joyful, smiling, uplifting voice", 0.6f, 1.06f),
    ANGER("anger", "الغضب والعصبية", "Anger & Fury", null, "fierce, tense, sharp angry tone", 0.4f, 1.12f),
    FEAR("fear", "الخوف والتوتر", "Fear & Tension", null, "anxious, trembling, tense fearful breath", 0.5f, 1.08f),
    CALM("calm", "الهدوء والطمأنينة", "Calm & Serenity", null, "soothing, peaceful, reassuring soft cadence", -0.3f, 0.92f),
    EXCITED("excited", "الحماس والقوة", "Excitement & Power", "[excited]", "energetic, bold, powerful enthusiastic delivery", 0.7f, 1.10f),
    SURPRISED("surprised", "الدهشة والاستغراب", "Surprise & Awe", "[curious]", "astonished, wide-eyed surprised expressive tone", 0.8f, 1.03f),
    WHISPER("whisper", "الهمس", "Whisper", "[whispers]", "intimate soft airy whisper very close to mic", 0.2f, 0.90f),
    LAUGH("laugh", "الضحك", "Laughter", "[laughs]", "lighthearted laughing chuckle while speaking warmly", 0.5f, 1.04f),
}

@Serializable
enum class AiDeliveryPace(
    val id: String,
    val titleAr: String,
    val titleEn: String,
    val speedMultiplier: Float,
    val promptHintEn: String,
) {
    VERY_SLOW("very_slow", "بطيء جداً وعميق", "Very Slow & Deep", 0.78f, "very slow deliberate contemplative pacing"),
    SLOW("slow", "بطيء متأني", "Slow & Thoughtful", 0.89f, "measured unhurried thoughtful pacing"),
    NORMAL("normal", "إيقاع طبيعي", "Normal Pace", 1.0f, "natural conversational cadence"),
    FAST("fast", "سريع وحيوي", "Fast & Energetic", 1.14f, "brisk energetic rapid cadence"),
    DRAMATIC_PAUSES("dramatic", "درامي بوقفات مؤثرة", "Dramatic Pauses", 0.91f, "dramatic cinema pacing with emotional pauses"),
}

@Serializable
enum class AiVoiceCategory(
    val id: String,
    val titleAr: String,
    val titleEn: String,
) {
    MALE("male", "رجل", "Male"),
    FEMALE("female", "امرأة", "Female"),
    CHILD("child", "طفل", "Child"),
    ELDERLY("elderly", "مسن", "Elderly"),
    NARRATOR("narrator", "راوي / وثائقي", "Narrator"),
    CARTOON("cartoon", "شخصية كرتونية", "Cartoon"),
    CUSTOM_DESIGN("custom", "تصميم مخصص", "Custom Design");

    fun toGroupFilter(): AiVoiceGroupFilter = when (this) {
        MALE -> AiVoiceGroupFilter.MALE
        FEMALE -> AiVoiceGroupFilter.FEMALE
        CHILD, ELDERLY, NARRATOR, CARTOON, CUSTOM_DESIGN -> AiVoiceGroupFilter.CHARACTERS
    }
}

enum class AiVoiceGroupFilter(
    val id: String,
    val titleAr: String,
    val titleEn: String,
) {
    MALE("male", "رجالي", "Male"),
    FEMALE("female", "نسائي", "Female"),
    CHARACTERS("characters", "شخصيات", "Characters"),
    FAVORITES("favorites", "المفضلة", "Favorites"),
}

fun countScriptWords(text: String): Int =
    text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

@Immutable
@Serializable
data class AiVoiceCharacter(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val category: AiVoiceCategory,
    val language: AiVoiceLanguage = AiVoiceLanguage.AR,
    val dialect: AiArabicDialect = AiArabicDialect.MSA,
    val preferredEngine: AiVoiceEngineKind = AiVoiceEngineKind.VOXCPM_2,
    val voiceDescriptionPrompt: String,
    val defaultEmotion: AiVoiceEmotion = AiVoiceEmotion.NEUTRAL,
    val defaultEmotionIntensity: Float = 0.65f,
    val defaultPace: AiDeliveryPace = AiDeliveryPace.NORMAL,
    val pitchOffsetSemitones: Float = 0f,
    val referenceAudioPath: String? = null,
    val referenceAudioName: String? = null,
    val referenceTranscript: String = "",
    val isBuiltIn: Boolean = false,
    val createdAtMs: Long = 0L,
) {
    fun displayName(isEnglish: Boolean): String = if (isEnglish && nameEn.isNotBlank()) nameEn else nameAr
}

@Immutable
@Serializable
data class AiDialogueTurn(
    val id: String,
    val characterId: String,
    val text: String,
    val emotion: AiVoiceEmotion = AiVoiceEmotion.NEUTRAL,
    val emotionIntensity: Float = 0.65f,
    val pace: AiDeliveryPace = AiDeliveryPace.NORMAL,
    val pauseAfterMs: Int = 450,
    val generatedWavPath: String? = null,
)

@Immutable
data class AiVoiceSynthRequest(
    val engine: AiVoiceEngineKind,
    val text: String,
    val language: AiVoiceLanguage,
    val dialect: AiArabicDialect,
    val category: AiVoiceCategory,
    val voiceDescriptionPrompt: String,
    val emotion: AiVoiceEmotion,
    val emotionIntensity: Float,
    val pace: AiDeliveryPace,
    val pitchOffsetSemitones: Float = 0f,
    val referenceAudioPath: String? = null,
    val referenceTranscript: String = "",
    val cfgValue: Float = 2.0f,
    val inferenceTimesteps: Int = 10,
    val normalizeAudio: Boolean = true,
)

sealed interface AiVoiceSynthOutcome {
    data class Success(
        val audioFilePath: String,
        val sampleRate: Int,
        val durationMs: Long,
        val engineUsed: AiVoiceEngineKind,
        val appliedPromptSummary: String,
        val isCloudGenerated: Boolean,
    ) : AiVoiceSynthOutcome

    data class ConfigurationRequired(
        val engine: AiVoiceEngineKind,
        val titleAr: String,
        val titleEn: String,
        val explanationAr: String,
        val explanationEn: String,
        val setupStepsAr: String,
        val setupStepsEn: String,
    ) : AiVoiceSynthOutcome

    data class Error(
        val engine: AiVoiceEngineKind,
        val messageAr: String,
        val messageEn: String,
        val technicalDetail: String? = null,
    ) : AiVoiceSynthOutcome
}

@Immutable
data class AiVoiceBackendHealth(
    val isChecked: Boolean = false,
    val isChecking: Boolean = false,
    val serverReachable: Boolean = false,
    val voxCpm2Ready: Boolean = false,
    val audarTurboReady: Boolean = false,
    val voxcpm2PackageInstalled: Boolean = false,
    val voxcpm2Loaded: Boolean = false,
    val cudaAvailable: Boolean = false,
    val authRequired: Boolean = false,
    val voxcpm2LoadError: String = "",
    val voxCpmPublicSpaceChecked: Boolean = false,
    val voxCpmPublicSpaceAvailable: Boolean = false,
    val edgeNeuralReady: Boolean = true,
    val statusDetail: String = "",
    val serverGpuInfo: String = "",
)

@Immutable
data class AiVoiceStudioUiState(
    val activeTab: Int = 0, // 0=Single, 1=Dialogue, 2=Characters, 3=Server
    val selectedEngine: AiVoiceEngineKind = AiVoiceEngineKind.VOXCPM_2,
    val selectedLanguage: AiVoiceLanguage = AiVoiceLanguage.AR,
    val selectedDialect: AiArabicDialect = AiArabicDialect.IRAQI,
    val selectedCategory: AiVoiceCategory = AiVoiceCategory.MALE,
    val selectedCharacterId: String = "iraqi_man_deep_40s",
    val selectedEmotion: AiVoiceEmotion = AiVoiceEmotion.NEUTRAL,
    val emotionIntensity: Float = 0.65f,
    val selectedPace: AiDeliveryPace = AiDeliveryPace.NORMAL,
    val pitchOffsetSemitones: Float = 0f,
    val voiceDescriptionPrompt: String = "رجل عراقي في الأربعين من عمره، صوته عميق وخشن قليلاً، يتحدث ببطء وبنبرة متزنة ومؤثرة.",
    val scriptText: String = "أهلاً بكم في استوديو الأصوات الذكي، حيث تتحول الكلمات إلى أداء بشري نابض بالمشاعر.",
    val referenceAudioPath: String? = null,
    val referenceAudioName: String? = null,
    val referenceAudioDurationMs: Long = 0L,
    val referenceTranscript: String = "",
    val referenceRightsConfirmed: Boolean = true,
    val isSynthesizing: Boolean = false,
    val synthesisProgress: Float = 0f,
    val synthesisStatusLabel: String = "",
    val errorTitle: String? = null,
    val errorDetail: String? = null,
    val setupRequiredGuide: String? = null,
    val lastOutputWavPath: String? = null,
    val lastOutputDurationMs: Long = 0L,
    val lastOutputSampleRate: Int = 44100,
    val lastOutputEngine: AiVoiceEngineKind? = null,
    val lastOutputAppliedPrompt: String? = null,
    val isPlayingOutput: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val savedCharacters: List<AiVoiceCharacter> = emptyList(),
    val favoriteCharacterIds: Set<String> = emptySet(),
    val dialogueTurns: List<AiDialogueTurn> = DEFAULT_DIALOGUE_TURNS,
    val backendServerUrl: String = "",
    val backendBearerToken: String = "",
    val backendHealth: AiVoiceBackendHealth = AiVoiceBackendHealth(),
) {
    val allCharacters: List<AiVoiceCharacter>
        get() = savedCharacters + BUILT_IN_AI_VOICE_CHARACTERS

    fun isCharacterFavorite(characterId: String): Boolean =
        characterId in favoriteCharacterIds || savedCharacters.any { it.id == characterId }
}

val BUILT_IN_AI_VOICE_CHARACTERS: List<AiVoiceCharacter> = listOf(
    AiVoiceCharacter(
        id = "iraqi_man_deep_40s",
        nameAr = "رجل عراقي عميق (الأربعينات)",
        nameEn = "Deep Iraqi Man (40s)",
        category = AiVoiceCategory.MALE,
        language = AiVoiceLanguage.AR,
        dialect = AiArabicDialect.IRAQI,
        preferredEngine = AiVoiceEngineKind.AUDAR_TTS_V1_FLASH,
        voiceDescriptionPrompt = "رجل عراقي في الأربعين من عمره، صوته عميق وخشن قليلاً، يتحدث ببطء وبنبرة مؤثرة ووقورة.",
        defaultEmotion = AiVoiceEmotion.SAD,
        defaultEmotionIntensity = 0.70f,
        defaultPace = AiDeliveryPace.SLOW,
        pitchOffsetSemitones = -2.2f,
        isBuiltIn = true,
    ),
    AiVoiceCharacter(
        id = "young_woman_warm_joy",
        nameAr = "امرأة شابة دافئة ومبتسمة",
        nameEn = "Warm Smiling Young Woman",
        category = AiVoiceCategory.FEMALE,
        language = AiVoiceLanguage.AR,
        dialect = AiArabicDialect.MSA,
        preferredEngine = AiVoiceEngineKind.VOXCPM_2,
        voiceDescriptionPrompt = "امرأة شابة بصوت هادئ وناعم، تتحدث بفرح وابتسامة واضحة في نبرة صوتها وبأسلوب مريح قريب من الميكروفون.",
        defaultEmotion = AiVoiceEmotion.JOY,
        defaultEmotionIntensity = 0.65f,
        defaultPace = AiDeliveryPace.NORMAL,
        pitchOffsetSemitones = 2.0f,
        isBuiltIn = true,
    ),
    AiVoiceCharacter(
        id = "elderly_tired_man",
        nameAr = "رجل مسن بصوت متعب",
        nameEn = "Weathered Elderly Man",
        category = AiVoiceCategory.ELDERLY,
        language = AiVoiceLanguage.AR,
        dialect = AiArabicDialect.MSA,
        preferredEngine = AiVoiceEngineKind.VOXCPM_2,
        voiceDescriptionPrompt = "رجل مسن في السبعين من عمره بصوت متعب وأجش قليلاً، مع تنفّس بطيء وحنين صادق في كل عبارة.",
        defaultEmotion = AiVoiceEmotion.SAD,
        defaultEmotionIntensity = 0.75f,
        defaultPace = AiDeliveryPace.VERY_SLOW,
        pitchOffsetSemitones = -3.4f,
        isBuiltIn = true,
    ),
    AiVoiceCharacter(
        id = "cinematic_narrator",
        nameAr = "راوي وثائقي سينمائي",
        nameEn = "Cinematic Documentary Narrator",
        category = AiVoiceCategory.NARRATOR,
        language = AiVoiceLanguage.AR,
        dialect = AiArabicDialect.MSA,
        preferredEngine = AiVoiceEngineKind.VOXCPM_2,
        voiceDescriptionPrompt = "راوي أفلام وثائقية بصوت جهوري فخم وعميق، إلقاء درامي رصين مع وقفات تنفس متقنة بين الجمل.",
        defaultEmotion = AiVoiceEmotion.CALM,
        defaultEmotionIntensity = 0.60f,
        defaultPace = AiDeliveryPace.DRAMATIC_PAUSES,
        pitchOffsetSemitones = -2.8f,
        isBuiltIn = true,
    ),
    AiVoiceCharacter(
        id = "cheerful_child",
        nameAr = "طفل مرح وفضولي",
        nameEn = "Cheerful Curious Child",
        category = AiVoiceCategory.CHILD,
        language = AiVoiceLanguage.AR,
        dialect = AiArabicDialect.MSA,
        preferredEngine = AiVoiceEngineKind.VOXCPM_2,
        voiceDescriptionPrompt = "طفل صغير في السابعة من عمره بصوت طفولي نقي مشرق ومليء بالحماس والفضول البريء.",
        defaultEmotion = AiVoiceEmotion.EXCITED,
        defaultEmotionIntensity = 0.75f,
        defaultPace = AiDeliveryPace.NORMAL,
        pitchOffsetSemitones = 4.6f,
        isBuiltIn = true,
    ),
    AiVoiceCharacter(
        id = "cartoon_adventurer",
        nameAr = "شخصية كرتونية مرحة",
        nameEn = "Expressive Cartoon Character",
        category = AiVoiceCategory.CARTOON,
        language = AiVoiceLanguage.AR,
        dialect = AiArabicDialect.EGYPTIAN,
        preferredEngine = AiVoiceEngineKind.VOXCPM_2,
        voiceDescriptionPrompt = "شخصية كرتونية مرحة سريعة البديهة بصوت مطاطي مبالغ في التعبير والدهشة والضحك.",
        defaultEmotion = AiVoiceEmotion.SURPRISED,
        defaultEmotionIntensity = 0.85f,
        defaultPace = AiDeliveryPace.FAST,
        pitchOffsetSemitones = 3.2f,
        isBuiltIn = true,
    ),
    AiVoiceCharacter(
        id = "gulf_host_female",
        nameAr = "مقدمة بودكاست خليجية",
        nameEn = "Gulf Podcast Host (Female)",
        category = AiVoiceCategory.FEMALE,
        language = AiVoiceLanguage.AR,
        dialect = AiArabicDialect.GULF,
        preferredEngine = AiVoiceEngineKind.AUDAR_TTS_V1_FLASH,
        voiceDescriptionPrompt = "امرأة خليجية لبقة بصوت دافئ رصين وهادئ يناسب البودكاست والحوار الثقافي.",
        defaultEmotion = AiVoiceEmotion.CALM,
        defaultEmotionIntensity = 0.60f,
        defaultPace = AiDeliveryPace.NORMAL,
        pitchOffsetSemitones = 1.6f,
        isBuiltIn = true,
    ),
    AiVoiceCharacter(
        id = "saudi_anchor_male",
        nameAr = "إعلامي سعودي فصيح",
        nameEn = "Saudi Broadcast Presenter (Male)",
        category = AiVoiceCategory.MALE,
        language = AiVoiceLanguage.AR,
        dialect = AiArabicDialect.SAUDI,
        preferredEngine = AiVoiceEngineKind.VOXCPM_2,
        voiceDescriptionPrompt = "رجل سعودي بصوت إذاعي واضح ومتزن ومقنع، إلقاء إخباري وبودكاست احترافي.",
        defaultEmotion = AiVoiceEmotion.NEUTRAL,
        defaultEmotionIntensity = 0.60f,
        defaultPace = AiDeliveryPace.NORMAL,
        pitchOffsetSemitones = -1.2f,
        isBuiltIn = true,
    ),
    AiVoiceCharacter(
        id = "egyptian_actress_drama",
        nameAr = "ممثلة مصرية درامية",
        nameEn = "Egyptian Drama Actress",
        category = AiVoiceCategory.FEMALE,
        language = AiVoiceLanguage.AR,
        dialect = AiArabicDialect.EGYPTIAN,
        preferredEngine = AiVoiceEngineKind.VOXCPM_2,
        voiceDescriptionPrompt = "امرأة مصرية بصوت معبر دافئ ومفعم بالعاطفة والانفعال الدرامي المتقن.",
        defaultEmotion = AiVoiceEmotion.SAD,
        defaultEmotionIntensity = 0.72f,
        defaultPace = AiDeliveryPace.DRAMATIC_PAUSES,
        pitchOffsetSemitones = 1.4f,
        isBuiltIn = true,
    ),
    AiVoiceCharacter(
        id = "global_english_narrator",
        nameAr = "راوي عالمي (English Male)",
        nameEn = "Global English Storyteller",
        category = AiVoiceCategory.NARRATOR,
        language = AiVoiceLanguage.EN,
        dialect = AiArabicDialect.MSA,
        preferredEngine = AiVoiceEngineKind.VOXCPM_2,
        voiceDescriptionPrompt = "Rich, cinematic English male baritone storyteller with crisp articulation and calm authority.",
        defaultEmotion = AiVoiceEmotion.CALM,
        defaultEmotionIntensity = 0.60f,
        defaultPace = AiDeliveryPace.SLOW,
        pitchOffsetSemitones = -2.0f,
        isBuiltIn = true,
    ),
    AiVoiceCharacter(
        id = "global_english_female",
        nameAr = "مقدمة عالمية (English Female)",
        nameEn = "Global English Studio Host",
        category = AiVoiceCategory.FEMALE,
        language = AiVoiceLanguage.EN,
        dialect = AiArabicDialect.MSA,
        preferredEngine = AiVoiceEngineKind.VOXCPM_2,
        voiceDescriptionPrompt = "Warm, expressive English female host with bright smiling resonance and natural studio intimacy.",
        defaultEmotion = AiVoiceEmotion.JOY,
        defaultEmotionIntensity = 0.65f,
        defaultPace = AiDeliveryPace.NORMAL,
        pitchOffsetSemitones = 1.8f,
        isBuiltIn = true,
    ),
)

val DEFAULT_DIALOGUE_TURNS: List<AiDialogueTurn> = listOf(
    AiDialogueTurn(
        id = "turn_1",
        characterId = "iraqi_man_deep_40s",
        text = "لقد انتظرت هذه اللحظة طويلاً، والآن حان وقت الحقيقة.",
        emotion = AiVoiceEmotion.SAD,
        emotionIntensity = 0.70f,
        pace = AiDeliveryPace.SLOW,
        pauseAfterMs = 500,
    ),
    AiDialogueTurn(
        id = "turn_2",
        characterId = "young_woman_warm_joy",
        text = "لا تقلق أبداً، كل شيء سيتغير للأفضل من هذه اللحظة.",
        emotion = AiVoiceEmotion.CALM,
        emotionIntensity = 0.65f,
        pace = AiDeliveryPace.NORMAL,
        pauseAfterMs = 450,
    ),
    AiDialogueTurn(
        id = "turn_3",
        characterId = "elderly_tired_man",
        text = "أتمنى أن تصدق كلماتكما يا أبنائي... فقد تعبت من الانتظار.",
        emotion = AiVoiceEmotion.CRYING,
        emotionIntensity = 0.75f,
        pace = AiDeliveryPace.VERY_SLOW,
        pauseAfterMs = 400,
    ),
)
