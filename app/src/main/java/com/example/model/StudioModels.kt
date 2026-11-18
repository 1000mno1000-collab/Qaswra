package com.example.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class AppLanguage(val tag: String, val nativeName: String, val shortBadge: String, val isRtl: Boolean) {
    AR("ar", "العربية", "عربي", true),
    EN("en", "English", "EN", false),
    FA("fa", "فارسی", "فارسی", true);

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: AR
    }
}

enum class EffectCategory {
    TIME_SPACE,
    TONE_EQ,
    PITCH_SPEED,
    MODULATION,
    CHARACTER_DYNAMICS,
    NOISE_CLEAN,
}

@Serializable
enum class EffectType(val titleKey: String, val category: EffectCategory, val isProDefault: Boolean) {
    NOISE_REDUCTION("effect_noise_reduction", EffectCategory.NOISE_CLEAN, false),
    EQUALIZER("effect_equalizer", EffectCategory.TONE_EQ, true),
    BASS_BOOST("effect_bass_boost", EffectCategory.TONE_EQ, false),
    TREBLE("effect_treble", EffectCategory.TONE_EQ, false),
    PITCH_SHIFT("effect_pitch_shift", EffectCategory.PITCH_SPEED, false),
    TIME_STRETCH("effect_time_stretch", EffectCategory.PITCH_SPEED, true),
    CHORUS("effect_chorus", EffectCategory.MODULATION, true),
    FLANGER("effect_flanger", EffectCategory.MODULATION, true),
    PHASER("effect_phaser", EffectCategory.MODULATION, true),
    DISTORTION("effect_distortion", EffectCategory.CHARACTER_DYNAMICS, true),
    COMPRESSOR("effect_compressor", EffectCategory.CHARACTER_DYNAMICS, true),
    ECHO_REVERB("effect_echo_reverb", EffectCategory.TIME_SPACE, false),
    LIMITER("effect_limiter", EffectCategory.CHARACTER_DYNAMICS, true),
}

@Immutable
@Serializable
data class EchoReverbParams(
    val echoStrength: Float = 0f,
    val echoDepth: Float = 0f,
    val delayTimeMs: Float = 240f,
    val feedback: Float = 0f,
    val reverbSize: Float = 0f,
    val reverbDecay: Float = 0f,
    val preDelayMs: Float = 0f,
    val wetDryMix: Float = 0f,
    val stereoWidth: Float = 0f,
    val earlyReflections: Float = 0f,
    val lateReflections: Float = 0f,
    val damping: Float = 0f,
    val diffusion: Float = 0f,
)

@Immutable
@Serializable
data class EqualizerParams(
    val band60HzDb: Float = 0f,
    val band230HzDb: Float = 0f,
    val band910HzDb: Float = 0f,
    val band3600HzDb: Float = 0f,
    val band14000HzDb: Float = 0f,
)

@Immutable
@Serializable
data class BassBoostParams(
    val gainDb: Float = 0f,
    val frequencyHz: Float = 95f,
)

@Immutable
@Serializable
data class TrebleParams(
    val gainDb: Float = 0f,
    val frequencyHz: Float = 6500f,
)

@Immutable
@Serializable
data class PitchShiftParams(
    val semitones: Float = 0f,
    val wetMix: Float = 1f,
)

@Immutable
@Serializable
data class TimeStretchParams(
    val tempoRatio: Float = 1f,
)

@Immutable
@Serializable
data class ChorusParams(
    val rateHz: Float = 1.2f,
    val depthMs: Float = 6.5f,
    val mix: Float = 0f,
)

@Immutable
@Serializable
data class FlangerParams(
    val rateHz: Float = 0.45f,
    val depthMs: Float = 3f,
    val feedback: Float = 0f,
    val mix: Float = 0f,
)

@Immutable
@Serializable
data class PhaserParams(
    val rateHz: Float = 0.6f,
    val depth: Float = 0f,
    val feedback: Float = 0f,
    val mix: Float = 0f,
)

@Serializable
enum class DistortionMode {
    SOFT_CLIP,
    HARD_CLIP,
    OVERDRIVE,
}

@Immutable
@Serializable
data class DistortionParams(
    val drive: Float = 1f,
    val toneHz: Float = 4200f,
    val mix: Float = 0f,
    val mode: DistortionMode = DistortionMode.OVERDRIVE,
)

@Immutable
@Serializable
data class CompressorParams(
    val thresholdDb: Float = 0f,
    val ratio: Float = 1f,
    val attackMs: Float = 12f,
    val releaseMs: Float = 140f,
    val makeupGainDb: Float = 0f,
)

@Immutable
@Serializable
data class LimiterParams(
    val ceilingDb: Float = 0f,
    val releaseMs: Float = 80f,
)

@Immutable
@Serializable
data class NoiseReductionParams(
    val thresholdDb: Float = -70f,
    val reductionDb: Float = 0f,
    val highPassHz: Float = 20f,
    val lowPassHz: Float = 16000f,
    val spectralStrength: Float = 0f,
    val deEssAmount: Float = 0f,
    val clarityBoostDb: Float = 0f,
    val spectralCleanerEnabled: Boolean = false,
)

@Immutable
@Serializable
data class EffectSlot(
    val type: EffectType,
    val enabled: Boolean,
)

val DEFAULT_CHAIN: List<EffectSlot> = listOf(
    EffectSlot(EffectType.NOISE_REDUCTION, false),
    EffectSlot(EffectType.EQUALIZER, false),
    EffectSlot(EffectType.BASS_BOOST, false),
    EffectSlot(EffectType.TREBLE, false),
    EffectSlot(EffectType.PITCH_SHIFT, false),
    EffectSlot(EffectType.TIME_STRETCH, false),
    EffectSlot(EffectType.CHORUS, false),
    EffectSlot(EffectType.FLANGER, false),
    EffectSlot(EffectType.PHASER, false),
    EffectSlot(EffectType.DISTORTION, false),
    EffectSlot(EffectType.COMPRESSOR, false),
    EffectSlot(EffectType.ECHO_REVERB, false),
    EffectSlot(EffectType.LIMITER, false),
)

@Immutable
@Serializable
data class DspConfig(
    val bypassAll: Boolean = false,
    val masterGainDb: Float = 0f,
    val spatialPanHz: Float = 0f,
    val surroundMidPct: Float = 0f,
    val surroundSidePct: Float = 0f,
    val surroundWidthPct: Float = 0f,
    val vibratoHz: Float = 0f,
    val vibratoDepth: Float = 0f,
    val chain: List<EffectSlot> = DEFAULT_CHAIN,
    val echoReverb: EchoReverbParams = EchoReverbParams(),
    val equalizer: EqualizerParams = EqualizerParams(),
    val bassBoost: BassBoostParams = BassBoostParams(),
    val treble: TrebleParams = TrebleParams(),
    val pitchShift: PitchShiftParams = PitchShiftParams(),
    val timeStretch: TimeStretchParams = TimeStretchParams(),
    val chorus: ChorusParams = ChorusParams(),
    val flanger: FlangerParams = FlangerParams(),
    val phaser: PhaserParams = PhaserParams(),
    val distortion: DistortionParams = DistortionParams(),
    val compressor: CompressorParams = CompressorParams(),
    val limiter: LimiterParams = LimiterParams(),
    val noiseReduction: NoiseReductionParams = NoiseReductionParams(),
) {
    fun normalized(): DspConfig {
        val valid = chain.distinctBy { it.type }
        val existing = valid.map { it.type }.toSet()
        val missing = DEFAULT_CHAIN.filter { it.type !in existing }
        return copy(chain = valid + missing)
    }

    fun isEnabled(type: EffectType): Boolean =
        chain.firstOrNull { it.type == type }?.enabled == true

    fun withSlotEnabled(type: EffectType, enabled: Boolean): DspConfig =
        copy(chain = chain.map { if (it.type == type) it.copy(enabled = enabled) else it })

    fun hasLiveSurround(): Boolean =
        kotlin.math.abs(surroundMidPct) > 0.5f ||
            kotlin.math.abs(surroundSidePct) > 0.5f ||
            surroundWidthPct > 0.5f

    fun hasAnyActiveProcessing(): Boolean =
        !bypassAll && (
            chain.any { it.enabled } ||
                kotlin.math.abs(masterGainDb) > 0.05f ||
                spatialPanHz > 0.01f ||
                hasLiveSurround() ||
                (vibratoHz > 0.1f && vibratoDepth > 0.01f)
            )
}

@Serializable
data class StoredCustomPreset(
    val id: Long,
    val name: String,
    val description: String,
    val config: DspConfig,
    val createdAt: Long = System.currentTimeMillis(),
    val cloudDocId: String? = null,
)

@Immutable
data class StudioPreset(
    val id: Long,
    val name: String,
    val description: String,
    val isBuiltIn: Boolean,
    val config: DspConfig,
    val cloudDocId: String? = null,
)

@Immutable
data class CloudDraft(
    val docId: String,
    val name: String,
    val selectedPresetName: String,
    val durationMs: Long,
    val sampleRate: Int,
    val config: DspConfig,
    val updatedAtMs: Long,
)

enum class GlobalProPresetId {
    BROADCAST_RADIO,
    AUTOTUNE_SYNTH,
    EPIC_CINEMATIC,
    SURROUND_8D_CINEMA,
    CRYSTAL_VOCAL,
}

data class GlobalProPreset(
    val id: GlobalProPresetId,
    val titleKey: String,
    val descKey: String,
    val ffmpegFilter: String,
    val dspConfig: DspConfig,
)

class StereoAudioBuffer(
    val left: FloatArray,
    val right: FloatArray,
    val sampleRate: Int = 44100,
) {
    val frameCount: Int get() = minOf(left.size, right.size)
    val durationMs: Long get() = if (sampleRate > 0) (frameCount * 1000L) / sampleRate else 0L
}

@Immutable
data class DeviceAudioItem(
    val id: Long,
    val uriString: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val extension: String,
    val mimeType: String,
    val folderName: String,
    val dateAddedSec: Long,
)

enum class EchoPadAxisMode {
    SPACE,
    DELAY,
    WIDTH,
    DAMPING,
}

@Serializable
enum class EchoDetailGroup {
    CORE,
    REFLECTIONS,
    SPACE,
}

@Serializable
enum class AiToolType {
    TRANSCRIBE,
    SMART_MASTER,
    SEARCH,
}

enum class ExportAudioFormat(
    val displayName: String,
    val subtitle: String,
    val extension: String,
    val mimeType: String,
) {
    M4A("M4A", "ترميز استوديو عالي الكفاءة (AAC / M4A Container)", "m4a", "audio/mp4"),
    WAV("WAV", "جودة استوديو غير مضغوطة (PCM 16-bit)", "wav", "audio/wav"),
    FLAC("FLAC", "حفظ نقي بدون فقدان الجودة (Lossless)", "flac", "audio/flac"),
    OGG("OGG", "مقطع قياسي مضغوط (Opus / Audio)", "ogg", "audio/ogg"),
    MP3("MP3", "صيغة قياسية عالية التوافق", "mp3", "audio/mpeg"),
}

@Immutable
data class StudioProject(
    val id: Long,
    val name: String,
    val durationMs: Long,
    val sampleRate: Int,
    val config: DspConfig,
    val updatedAt: Long,
    val hasAudio: Boolean,
)

enum class RecordingSourceType {
    RECORDING,
    IMPORTED,
    TTS,
}

@Immutable
data class AudioRecordingItem(
    val id: Long,
    val title: String,
    val timestamp: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val sourceType: RecordingSourceType,
)

@Immutable
data class VoicePurifyPreset(
    val id: String,
    val titleAr: String,
    val titleEn: String,
    val subtitleAr: String,
    val subtitleEn: String,
    val highPassHz: Float,
    val lowPassHz: Float,
    val thresholdDb: Float,
    val reductionDb: Float,
    val spectralStrength: Float,
    val deEssAmount: Float,
    val clarityBoostDb: Float,
    val spectralCleanerEnabled: Boolean,
)

val VOICE_PURIFY_PRESETS: List<VoicePurifyPreset> = listOf(
    VoicePurifyPreset(
        id = "studio_clean",
        titleAr = "نقاء استوديو ناعم",
        titleEn = "Soft Studio Clean",
        subtitleAr = "تنقية متوازنة تحفظ دفء الصوت ووضوح الحروف",
        subtitleEn = "Balanced noise removal preserving natural vocal warmth",
        highPassHz = 85f,
        lowPassHz = 15200f,
        thresholdDb = -46f,
        reductionDb = 18f,
        spectralStrength = 0.62f,
        deEssAmount = 0.25f,
        clarityBoostDb = 2.5f,
        spectralCleanerEnabled = true,
    ),
    VoicePurifyPreset(
        id = "deep_isolate",
        titleAr = "عزل فائق للضوضاء",
        titleEn = "Deep Noise Isolate",
        subtitleAr = "قمع قوي لضجيج المكيف، المروحة، وهواء الخلفية",
        subtitleEn = "Strong attenuation of AC hum, fans, and wind noise",
        highPassHz = 115f,
        lowPassHz = 12800f,
        thresholdDb = -40f,
        reductionDb = 24f,
        spectralStrength = 0.85f,
        deEssAmount = 0.30f,
        clarityBoostDb = 3.2f,
        spectralCleanerEnabled = true,
    ),
    VoicePurifyPreset(
        id = "podcast_crisp",
        titleAr = "بودكاست ووضوح الحوار",
        titleEn = "Podcast Vocal Crisp",
        subtitleAr = "بروز فوري للحروف وحماية مخارج الهاء والخاء",
        subtitleEn = "Forward vocal presence with full consonant clarity",
        highPassHz = 95f,
        lowPassHz = 15800f,
        thresholdDb = -44f,
        reductionDb = 20f,
        spectralStrength = 0.70f,
        deEssAmount = 0.25f,
        clarityBoostDb = 4.5f,
        spectralCleanerEnabled = true,
    ),
    VoicePurifyPreset(
        id = "clean_podcast_mic",
        titleAr = "تنقية المايك والبودكاست (Studio Mic)",
        titleEn = "Clean Podcast Mic Pro",
        subtitleAr = "عزل ذكي مع موازنة وحضور صوتي إذاعي متكامل",
        subtitleEn = "Studio denoise, rumble cleanup & crisp vocal presence EQ",
        highPassHz = 85f,
        lowPassHz = 14800f,
        thresholdDb = -40f,
        reductionDb = 22f,
        spectralStrength = 0.80f,
        deEssAmount = 0.30f,
        clarityBoostDb = 3.0f,
        spectralCleanerEnabled = true,
    ),
    VoicePurifyPreset(
        id = "gentle_denoise",
        titleAr = "تصفية خفيفة (حفظ الجو)",
        titleEn = "Gentle Ambient Safe",
        subtitleAr = "تخفيف لطيف للهسهسة دون المساس بنقاء الحديث",
        subtitleEn = "Subtle hiss taming without altering quiet ambience",
        highPassHz = 65f,
        lowPassHz = 16000f,
        thresholdDb = -54f,
        reductionDb = 12f,
        spectralStrength = 0.40f,
        deEssAmount = 0.15f,
        clarityBoostDb = 1.2f,
        spectralCleanerEnabled = true,
    ),
)

enum class VoiceCharacterCategory {
    HUMAN,
    MYTHICAL,
}

@Immutable
data class VoiceCharacterPreset(
    val id: String,
    val titleKey: String,
    val descKey: String,
    val pitchSemitones: Float,
    val formantShift: Float,
    val warmthDb: Float,
    val presenceDb: Float,
    val bassBoostDb: Float = 0f,
    val bassFreqHz: Float = 75f,
    val vibratoHz: Float = 0f,
    val vibratoDepth: Float = 0f,
    val echoDelayMs: Float = 0f,
    val echoDecay: Float = 0f,
    val dualLayerPitchSemitones: Float = 0f,
    val dualLayerFormantShift: Float = 1.0f,
    val dualLayerMix: Float = 0f,
    val category: VoiceCharacterCategory = VoiceCharacterCategory.HUMAN,
)

val VOICE_CHARACTER_PRESETS: List<VoiceCharacterPreset> = listOf(
    VoiceCharacterPreset("male_luxury", "voice_char_male_luxury", "voice_char_male_luxury_desc", -3.2f, 0.85f, 4.0f, 2.2f),
    VoiceCharacterPreset("male_calm", "voice_char_male_calm", "voice_char_male_calm_desc", -1.8f, 0.91f, 2.5f, 1.5f),
    VoiceCharacterPreset("female_soft", "voice_char_female_soft", "voice_char_female_soft_desc", 3.4f, 1.15f, 1.2f, 2.0f),
    VoiceCharacterPreset("female_balanced", "voice_char_female_balanced", "voice_char_female_balanced_desc", 2.2f, 1.1f, 0.5f, 3.0f),
    VoiceCharacterPreset("child", "voice_char_child", "voice_char_child_desc", 4.85f, 1.26f, 0.2f, 2.2f),
    VoiceCharacterPreset(
        id = "abyssal_dragon",
        titleKey = "voice_char_abyssal_dragon",
        descKey = "voice_char_abyssal_dragon_desc",
        pitchSemitones = -8.25f,
        formantShift = 0.72f,
        warmthDb = 3.0f,
        presenceDb = 1.8f,
        bassBoostDb = 14.0f,
        bassFreqHz = 70f,
        vibratoHz = 12.0f,
        vibratoDepth = 0.40f,
        echoDelayMs = 160f,
        echoDecay = 0.50f,
        category = VoiceCharacterCategory.MYTHICAL,
    ),
    VoiceCharacterPreset(
        id = "shadow_legion",
        titleKey = "voice_char_shadow_legion",
        descKey = "voice_char_shadow_legion_desc",
        pitchSemitones = -5.0f,
        formantShift = 0.80f,
        warmthDb = 2.5f,
        presenceDb = 2.0f,
        bassBoostDb = 4.5f,
        bassFreqHz = 85f,
        echoDelayMs = 90f,
        echoDecay = 0.40f,
        dualLayerPitchSemitones = -1.45f,
        dualLayerFormantShift = 0.94f,
        dualLayerMix = 0.50f,
        category = VoiceCharacterCategory.MYTHICAL,
    ),
    VoiceCharacterPreset(
        id = "alien_wizard",
        titleKey = "voice_char_alien_wizard",
        descKey = "voice_char_alien_wizard_desc",
        pitchSemitones = 2.6f,
        formantShift = 1.24f,
        warmthDb = -1.5f,
        presenceDb = 4.5f,
        vibratoHz = 18.0f,
        vibratoDepth = 0.55f,
        echoDelayMs = 140f,
        echoDecay = 0.42f,
        dualLayerPitchSemitones = -2.2f,
        dualLayerFormantShift = 0.88f,
        dualLayerMix = 0.35f,
        category = VoiceCharacterCategory.MYTHICAL,
    ),
    VoiceCharacterPreset(
        id = "stone_giant",
        titleKey = "voice_char_stone_giant",
        descKey = "voice_char_stone_giant_desc",
        pitchSemitones = -9.5f,
        formantShift = 0.68f,
        warmthDb = 5.5f,
        presenceDb = 0.8f,
        bassBoostDb = 16.0f,
        bassFreqHz = 65f,
        echoDelayMs = 210f,
        echoDecay = 0.48f,
        category = VoiceCharacterCategory.MYTHICAL,
    ),
    VoiceCharacterPreset(
        id = "forest_spirit",
        titleKey = "voice_char_forest_spirit",
        descKey = "voice_char_forest_spirit_desc",
        pitchSemitones = 3.2f,
        formantShift = 1.18f,
        warmthDb = 1.5f,
        presenceDb = 5.0f,
        vibratoHz = 5.5f,
        vibratoDepth = 0.25f,
        echoDelayMs = 240f,
        echoDecay = 0.55f,
        dualLayerPitchSemitones = 7.0f,
        dualLayerFormantShift = 1.28f,
        dualLayerMix = 0.32f,
        category = VoiceCharacterCategory.MYTHICAL,
    ),
    VoiceCharacterPreset(
        id = "ghost_whisper",
        titleKey = "voice_char_ghost_whisper",
        descKey = "voice_char_ghost_whisper_desc",
        pitchSemitones = -1.5f,
        formantShift = 1.12f,
        warmthDb = -3.0f,
        presenceDb = 6.0f,
        vibratoHz = 7.5f,
        vibratoDepth = 0.35f,
        echoDelayMs = 310f,
        echoDecay = 0.62f,
        dualLayerPitchSemitones = 0.4f,
        dualLayerFormantShift = 1.22f,
        dualLayerMix = 0.45f,
        category = VoiceCharacterCategory.MYTHICAL,
    ),
    VoiceCharacterPreset(
        id = "cursed_radio",
        titleKey = "voice_char_cursed_radio",
        descKey = "voice_char_cursed_radio_desc",
        pitchSemitones = -3.6f,
        formantShift = 0.84f,
        warmthDb = -4.5f,
        presenceDb = 7.5f,
        vibratoHz = 14.0f,
        vibratoDepth = 0.30f,
        echoDelayMs = 85f,
        echoDecay = 0.38f,
        dualLayerPitchSemitones = -6.0f,
        dualLayerFormantShift = 0.78f,
        dualLayerMix = 0.28f,
        category = VoiceCharacterCategory.MYTHICAL,
    ),
)

enum class TtsGender {
    MALE,
    FEMALE,
}

enum class TtsEngineMode {
    FREE_EMOTIONAL_NEURAL,
}

enum class TtsDeliveryStyle(
    val titleKey: String,
    val rateFactor: Float,
    val pitchOffsetHz: Int,
    val warmthDb: Float,
    val ssmlExpressAsStyle: String? = null,
    val ssmlStyleDegree: Float = 1.0f,
    val volumeDeltaPercent: Int = 0,
    val breathPauseMs: Int = 210,
) {
    NATURAL_WARM("tts_style_natural", 1.0f, 0, 2.5f, null, 1.0f, 0, 210),
    ENERGETIC("tts_style_energetic", 1.09f, 8, 1.6f, "cheerful", 1.35f, 4, 150),
    SAD("tts_style_sad", 0.86f, -7, 3.4f, "sad", 1.25f, -6, 360),
    ANGRY("tts_style_angry", 1.12f, 6, 1.2f, "angry", 1.4f, 10, 130),
    DRAMATIC("tts_style_dramatic", 0.91f, -6, 3.8f, "dramatic", 1.35f, 2, 380),
    RADIO_ANCHOR("tts_style_radio", 0.96f, -6, 4.0f, "newscast", 1.1f, 2, 190),
    STORYTELLING("tts_style_story", 0.92f, -2, 3.0f, "narration-relaxed", 1.15f, 0, 290),
}

data class TtsDialect(
    val id: String,
    val titleKey: String,
    val localeTag: String,
    val fallbackLocaleTag: String,
    val maleVoiceName: String,
    val femaleVoiceName: String,
    val sampleText: String,
    val defaultPitch: Float,
    val defaultRate: Float,
)

val TTS_DIALECTS: List<TtsDialect> = listOf(
    TtsDialect(
        id = "iraqi",
        titleKey = "tts_dialect_iraqi",
        localeTag = "ar-IQ",
        fallbackLocaleTag = "ar-SA",
        maleVoiceName = "ar-IQ-BasselNeural",
        femaleVoiceName = "ar-IQ-RanaNeural",
        sampleText = "هلا بيكم باستوديو Sonix ✦، شلونكم اليوم؟ خلينا نسجّل صوت نقي ومرتّب.",
        defaultPitch = 0.98f,
        defaultRate = 0.96f,
    ),
    TtsDialect(
        id = "saudi",
        titleKey = "tts_dialect_saudi",
        localeTag = "ar-SA",
        fallbackLocaleTag = "ar",
        maleVoiceName = "ar-SA-HamedNeural",
        femaleVoiceName = "ar-SA-ZariyahNeural",
        sampleText = "حياكم الله في استوديو Sonix ✦، تجربة صوتية احترافية بجودة عالية جداً.",
        defaultPitch = 1.0f,
        defaultRate = 0.96f,
    ),
    TtsDialect(
        id = "egyptian",
        titleKey = "tts_dialect_egyptian",
        localeTag = "ar-EG",
        fallbackLocaleTag = "ar-SA",
        maleVoiceName = "ar-EG-ShakirNeural",
        femaleVoiceName = "ar-EG-SalmaNeural",
        sampleText = "أهلاً بيكم في استوديو Sonix ✦، صوتكم هنا بيطلع صافي واحترافي جداً.",
        defaultPitch = 1.0f,
        defaultRate = 0.97f,
    ),
    TtsDialect(
        id = "syrian",
        titleKey = "tts_dialect_syrian",
        localeTag = "ar-SY",
        fallbackLocaleTag = "ar-LB",
        maleVoiceName = "ar-SY-LaithNeural",
        femaleVoiceName = "ar-SY-AmanyNeural",
        sampleText = "يا مية أهلاً وسهلاً فيكم في Sonix ✦، نورتوا الاستوديو وصوتكم طالع بيشهي وواضح.",
        defaultPitch = 1.02f,
        defaultRate = 0.96f,
    ),
    TtsDialect(
        id = "standard",
        titleKey = "tts_dialect_standard",
        localeTag = "ar-LB",
        fallbackLocaleTag = "ar-SA",
        maleVoiceName = "ar-LB-RamiNeural",
        femaleVoiceName = "ar-LB-LaylaNeural",
        sampleText = "أهلاً بكم في استوديو Sonix ✦ المتطور، تجربة صوتية احترافية بالذكاء الاصطناعي.",
        defaultPitch = 1.0f,
        defaultRate = 0.96f,
    ),
    TtsDialect(
        id = "jordanian",
        titleKey = "tts_dialect_jordanian",
        localeTag = "ar-JO",
        fallbackLocaleTag = "ar-SA",
        maleVoiceName = "ar-JO-TaimNeural",
        femaleVoiceName = "ar-JO-SanaNeural",
        sampleText = "أهلاً وسهلاً فيكم في استوديو Sonix ✦، يسعد أوقاتكم بكل خير وإبداع صوتي.",
        defaultPitch = 1.0f,
        defaultRate = 0.97f,
    ),
    TtsDialect(
        id = "moroccan",
        titleKey = "tts_dialect_moroccan",
        localeTag = "ar-MA",
        fallbackLocaleTag = "ar-SA",
        maleVoiceName = "ar-MA-JamalNeural",
        femaleVoiceName = "ar-MA-MounaNeural",
        sampleText = "مرحبا بكم كاملين فـ Sonix ✦، كلشي واجد باش تخرجو صوت نقي بزاف.",
        defaultPitch = 0.99f,
        defaultRate = 0.95f,
    ),
    TtsDialect(
        id = "emirati",
        titleKey = "tts_dialect_emirati",
        localeTag = "ar-AE",
        fallbackLocaleTag = "ar-SA",
        maleVoiceName = "ar-AE-HamdanNeural",
        femaleVoiceName = "ar-AE-FatimaNeural",
        sampleText = "مرحباً بكم في استوديو Sonix ✦ الخليجي، هندسة صوتية راقية بلمسة احترافية.",
        defaultPitch = 1.0f,
        defaultRate = 0.96f,
    ),
    TtsDialect(
        id = "tunisian",
        titleKey = "tts_dialect_tunisian",
        localeTag = "ar-TN",
        fallbackLocaleTag = "ar-SA",
        maleVoiceName = "ar-TN-HediNeural",
        femaleVoiceName = "ar-TN-ReemNeural",
        sampleText = "عسلامة ومرحبا بيكم في Sonix ✦، اليوم باش نخدمو على صوت مزيان برشا.",
        defaultPitch = 1.01f,
        defaultRate = 0.96f,
    ),
    TtsDialect(
        id = "en_us",
        titleKey = "tts_dialect_english_us",
        localeTag = "en-US",
        fallbackLocaleTag = "en",
        maleVoiceName = "en-US-AndrewNeural",
        femaleVoiceName = "en-US-AvaNeural",
        sampleText = "Welcome to Sonix ✦ Studio. Crystal clear acoustics ready for your next mix.",
        defaultPitch = 1.0f,
        defaultRate = 1.0f,
    ),
    TtsDialect(
        id = "persian",
        titleKey = "tts_dialect_persian",
        localeTag = "fa-IR",
        fallbackLocaleTag = "fa",
        maleVoiceName = "fa-IR-FaridNeural",
        femaleVoiceName = "fa-IR-DilaraNeural",
        sampleText = "سلام و خوش آمدید به استودیوی Sonix ✦؛ صدای شما اکنون با شفافیت کامل آماده است.",
        defaultPitch = 1.0f,
        defaultRate = 0.95f,
    ),
    TtsDialect(
        id = "turkish",
        titleKey = "tts_dialect_turkish",
        localeTag = "tr-TR",
        fallbackLocaleTag = "tr",
        maleVoiceName = "tr-TR-AhmetNeural",
        femaleVoiceName = "tr-TR-EmelNeural",
        sampleText = "Sonix ✦ Stüdyo'ya hoş geldiniz. Sesiniz şimdi kristal netliğinde işlenmeye hazır.",
        defaultPitch = 1.0f,
        defaultRate = 0.98f,
    ),
)

@Immutable
data class SmartMasterAnalysis(
    val rmsDb: Float,
    val peakDb: Float,
    val crestFactorDb: Float,
    val noiseFloorDb: Float,
    val lowEnergyRatio: Float,
    val midEnergyRatio: Float,
    val highEnergyRatio: Float,
    val detectedProfile: String,
    val appliedActions: List<String>,
    val optimizedConfig: DspConfig,
)

@Immutable
data class SearchSourceLink(
    val title: String,
    val uri: String,
    val domain: String,
)

@Immutable
data class AiStreamState(
    val isLoading: Boolean = false,
    val outputText: String = "",
    val errorMessage: String? = null,
    val searchQueries: List<String> = emptyList(),
    val sources: List<SearchSourceLink> = emptyList(),
)

@Immutable
data class TtsStudioUiState(
    val selectedEngine: TtsEngineMode = TtsEngineMode.FREE_EMOTIONAL_NEURAL,
    val activeEngineUsed: TtsEngineMode? = null,
    val isSynthesizingPreview: Boolean = false,
    val isPreviewing: Boolean = false,
    val isGenerating: Boolean = false,
    val usedFallbackEngine: Boolean = false,
    val isOffline: Boolean = false,
    val errorMessage: String? = null,
)

@Immutable
@Serializable
sealed interface AuthSession {
    @Serializable
    data object SignedOut : AuthSession

    @Serializable
    data object Guest : AuthSession

    @Serializable
    data class SignedIn(
        val uid: String,
        val displayName: String,
        val email: String,
    ) : AuthSession
}

@Immutable
data class PlaybackTelemetry(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val outputLevel: Float = 0f,
    val outputDbfs: Float = -60f,
    val monitoringSpeed: Float = 1.0f,
    val gainReductionDb: Float = 0f,
    val noiseSuppressionDb: Float = 0f,
    val waveformBars: List<Float> = List(48) { 0.06f },
    val spectrumBands: List<Float> = List(32) { 0.03f },
)

@Immutable
data class RecorderTelemetry(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedMs: Long = 0L,
    val micLevel: Float = 0f,
    val micDbfs: Float = -60f,
    val waveformBars: List<Float> = List(48) { 0.05f },
    val spectrumBands: List<Float> = List(32) { 0.03f },
)

@Immutable
@Serializable
data class AudioMarker(
    val id: String,
    val positionFrac: Float,
    val label: String,
    val colorIndex: Int = 0,
)

enum class PlatformLoudnessTarget(val targetLufs: Float?, val titleKey: String) {
    ORIGINAL(null, "export_lufs_original"),
    YOUTUBE_TIKTOK_14(-14f, "export_lufs_social_14"),
    PODCAST_16(-16f, "export_lufs_podcast_16"),
    WHATSAPP_VOICE(-15f, "export_lufs_whatsapp"),
}

enum class VocalSplitMode(val titleKey: String) {
    VOCALS_ONLY("vocal_split_vocals"),
    INSTRUMENTAL_ONLY("vocal_split_instrumental"),
    CUSTOM_BLEND("vocal_split_blend"),
}

enum class OneTapSocialPreset(val titleKey: String, val descKey: String) {
    LUXURY_PODCAST("social_preset_podcast", "social_preset_podcast_desc"),
    DOCUMENTARY_NARRATOR("social_preset_doc", "social_preset_doc_desc"),
    VINTAGE_PHONE("social_preset_phone", "social_preset_phone_desc"),
    GRAND_HALL_REVERB("social_preset_hall", "social_preset_hall_desc"),
    FM_RADIO("social_preset_fm", "social_preset_fm_desc"),
}

enum class SleepTimerOption(val minutes: Int?, val labelKey: String) {
    OFF(null, "sleep_timer_off"),
    MIN_15(15, "sleep_timer_15"),
    MIN_30(30, "sleep_timer_30"),
    MIN_60(60, "sleep_timer_60"),
}

@Serializable
enum class OnlineSearchCategory {
    VOICES_CLIPS,
    SOUND_EFFECTS,
}

@Immutable
@Serializable
data class AssistantChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val recommendedToolId: String? = null,
    val recommendedToolLabel: String? = null,
    val timestampMs: Long = 0L,
)

@Immutable
@Serializable
data class SavedAssistantChatSession(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val messages: List<AssistantChatMessage>,
)

enum class BatchFilterPreset(val titleKey: String) {
    NONE("batch_filter_none"),
    VOICE_CLARITY("batch_filter_clarity"),
    BASS_WARMTH("batch_filter_bass"),
    STUDIO_RADIO("batch_filter_radio"),
    ECHO_SPACE("batch_filter_echo"),
    BRIGHT_AIR("batch_filter_air"),
    ACTIVE_STUDIO_DSP("batch_filter_active_dsp"),
}

enum class BatchItemStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
}

@Immutable
data class BatchDspSettings(
    val pitchSemitones: Float = 0f,
    val speedRatio: Float = 1.0f,
    val filterPreset: BatchFilterPreset = BatchFilterPreset.VOICE_CLARITY,
    val normalizeOutput: Boolean = true,
    val outputFormat: ExportAudioFormat = ExportAudioFormat.M4A,
)

@Immutable
data class BatchQueueItem(
    val id: String,
    val displayName: String,
    val sourceUriString: String,
    val localFilePath: String? = null,
    val durationMs: Long = 0L,
    val status: BatchItemStatus = BatchItemStatus.QUEUED,
    val itemProgress: Float = 0f,
    val outputFilePath: String? = null,
    val outputFormat: ExportAudioFormat = ExportAudioFormat.M4A,
    val errorMessage: String? = null,
)

@Immutable
data class BatchQueueUiState(
    val items: List<BatchQueueItem> = emptyList(),
    val settings: BatchDspSettings = BatchDspSettings(),
    val isRunning: Boolean = false,
    val currentIndex: Int = -1,
    val overallProgress: Float = 0f,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val statusSummary: String? = null,
    val previewingItemId: String? = null,
)

