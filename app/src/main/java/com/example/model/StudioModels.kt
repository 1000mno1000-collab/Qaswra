package com.example.model

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

@Serializable
data class EchoReverbParams(
    val echoStrength: Float = 0.4f,
    val echoDepth: Float = 0.45f,
    val delayTimeMs: Float = 280f,
    val feedback: Float = 0.35f,
    val reverbSize: Float = 0.52f,
    val reverbDecay: Float = 0.48f,
    val preDelayMs: Float = 22f,
    val wetDryMix: Float = 0.38f,
    val stereoWidth: Float = 0.65f,
    val earlyReflections: Float = 0.42f,
    val lateReflections: Float = 0.55f,
    val damping: Float = 0.38f,
    val diffusion: Float = 0.72f,
)

@Serializable
data class EqualizerParams(
    val band60HzDb: Float = 0f,
    val band230HzDb: Float = 0f,
    val band910HzDb: Float = 0f,
    val band3600HzDb: Float = 0f,
    val band14000HzDb: Float = 0f,
)

@Serializable
data class BassBoostParams(
    val gainDb: Float = 0f,
    val frequencyHz: Float = 95f,
)

@Serializable
data class TrebleParams(
    val gainDb: Float = 0f,
    val frequencyHz: Float = 6500f,
)

@Serializable
data class PitchShiftParams(
    val semitones: Float = 0f,
    val wetMix: Float = 1f,
)

@Serializable
data class TimeStretchParams(
    val tempoRatio: Float = 1f,
)

@Serializable
data class ChorusParams(
    val rateHz: Float = 1.2f,
    val depthMs: Float = 6.5f,
    val mix: Float = 0.35f,
)

@Serializable
data class FlangerParams(
    val rateHz: Float = 0.45f,
    val depthMs: Float = 3f,
    val feedback: Float = 0.4f,
    val mix: Float = 0.35f,
)

@Serializable
data class PhaserParams(
    val rateHz: Float = 0.6f,
    val depth: Float = 0.7f,
    val feedback: Float = 0.35f,
    val mix: Float = 0.4f,
)

@Serializable
enum class DistortionMode {
    SOFT_CLIP,
    HARD_CLIP,
    OVERDRIVE,
}

@Serializable
data class DistortionParams(
    val drive: Float = 2.5f,
    val toneHz: Float = 4200f,
    val mix: Float = 0.4f,
    val mode: DistortionMode = DistortionMode.OVERDRIVE,
)

@Serializable
data class CompressorParams(
    val thresholdDb: Float = -18f,
    val ratio: Float = 3.5f,
    val attackMs: Float = 12f,
    val releaseMs: Float = 140f,
    val makeupGainDb: Float = 2.5f,
)

@Serializable
data class LimiterParams(
    val ceilingDb: Float = -0.8f,
    val releaseMs: Float = 80f,
)

@Serializable
data class NoiseReductionParams(
    val thresholdDb: Float = -45f,
    val reductionDb: Float = 18f,
    val highPassHz: Float = 85f,
    val spectralStrength: Float = 0.68f,
    val deEssAmount: Float = 0.35f,
    val clarityBoostDb: Float = 2.5f,
    val spectralCleanerEnabled: Boolean = true,
)

@Serializable
data class EffectSlot(
    val type: EffectType,
    val enabled: Boolean,
)

val DEFAULT_CHAIN: List<EffectSlot> = listOf(
    EffectSlot(EffectType.NOISE_REDUCTION, true),
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
    EffectSlot(EffectType.LIMITER, true),
)

@Serializable
data class DspConfig(
    val bypassAll: Boolean = false,
    val masterGainDb: Float = 0f,
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
}

data class StudioPreset(
    val id: Long,
    val name: String,
    val description: String,
    val isBuiltIn: Boolean,
    val config: DspConfig,
)

class StereoAudioBuffer(
    val left: FloatArray,
    val right: FloatArray,
    val sampleRate: Int = 44100,
) {
    val frameCount: Int get() = minOf(left.size, right.size)
    val durationMs: Long get() = if (sampleRate > 0) (frameCount * 1000L) / sampleRate else 0L
}

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

data class AudioRecordingItem(
    val id: Long,
    val title: String,
    val timestamp: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val sourceType: RecordingSourceType,
)

data class VoicePurifyPreset(
    val id: String,
    val titleAr: String,
    val titleEn: String,
    val subtitleAr: String,
    val subtitleEn: String,
    val highPassHz: Float,
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
        subtitleAr = "تنقية متوازنة تحفظ دفء الصوت وطبيعته",
        subtitleEn = "Balanced noise removal preserving natural vocal warmth",
        highPassHz = 85f,
        thresholdDb = -46f,
        reductionDb = 18f,
        spectralStrength = 0.65f,
        deEssAmount = 0.35f,
        clarityBoostDb = 2.5f,
        spectralCleanerEnabled = true,
    ),
    VoicePurifyPreset(
        id = "deep_isolate",
        titleAr = "عزل فائق للضوضاء",
        titleEn = "Deep Noise Isolate",
        subtitleAr = "قمع قوي لضجيج المكيف، المروحة، والشارع",
        subtitleEn = "Strong attenuation of AC hum, fans, and street noise",
        highPassHz = 115f,
        thresholdDb = -38f,
        reductionDb = 28f,
        spectralStrength = 0.88f,
        deEssAmount = 0.45f,
        clarityBoostDb = 3.5f,
        spectralCleanerEnabled = true,
    ),
    VoicePurifyPreset(
        id = "podcast_crisp",
        titleAr = "بودكاست ووضوح الحوار",
        titleEn = "Podcast Vocal Crisp",
        subtitleAr = "بروز فوري للحروف وإزالة الصدى القريب والصفير",
        subtitleEn = "Forward vocal presence with tight de-essing",
        highPassHz = 95f,
        thresholdDb = -43f,
        reductionDb = 21f,
        spectralStrength = 0.72f,
        deEssAmount = 0.55f,
        clarityBoostDb = 4.5f,
        spectralCleanerEnabled = true,
    ),
    VoicePurifyPreset(
        id = "gentle_denoise",
        titleAr = "تصفية خفيفة (حفظ الجو)",
        titleEn = "Gentle Ambient Safe",
        subtitleAr = "تخفيف خفيف للهسهسة بدون التأثير على الخلفية الهادئة",
        subtitleEn = "Subtle hiss taming without altering quiet ambience",
        highPassHz = 65f,
        thresholdDb = -54f,
        reductionDb = 12f,
        spectralStrength = 0.42f,
        deEssAmount = 0.2f,
        clarityBoostDb = 1f,
        spectralCleanerEnabled = true,
    ),
)

data class VoiceCharacterPreset(
    val id: String,
    val titleKey: String,
    val descKey: String,
    val pitchSemitones: Float,
    val formantShift: Float,
    val warmthDb: Float,
    val presenceDb: Float,
)

val VOICE_CHARACTER_PRESETS: List<VoiceCharacterPreset> = listOf(
    VoiceCharacterPreset("male_luxury", "voice_char_male_luxury", "voice_char_male_luxury_desc", -3.2f, 0.85f, 4.0f, 2.2f),
    VoiceCharacterPreset("male_calm", "voice_char_male_calm", "voice_char_male_calm_desc", -1.8f, 0.91f, 2.5f, 1.5f),
    VoiceCharacterPreset("female_soft", "voice_char_female_soft", "voice_char_female_soft_desc", 3.4f, 1.15f, 1.2f, 2.0f),
    VoiceCharacterPreset("female_balanced", "voice_char_female_balanced", "voice_char_female_balanced_desc", 2.2f, 1.1f, 0.5f, 3.0f),
    VoiceCharacterPreset("child", "voice_char_child", "voice_char_child_desc", 6.2f, 1.27f, -1.0f, 2.8f),
)

data class TtsDialect(
    val id: String,
    val titleKey: String,
    val localeTag: String,
    val fallbackLocaleTag: String,
    val sampleText: String,
    val defaultPitch: Float,
    val defaultRate: Float,
)

val TTS_DIALECTS: List<TtsDialect> = listOf(
    TtsDialect("iraqi", "tts_dialect_iraqi", "ar-IQ", "ar-SA", "هلا بيكم باستوديو فويس برو، شلونكم اليوم؟ خلينا نسجّل صوت نقي ومرتّب.", 0.98f, 0.95f),
    TtsDialect("saudi", "tts_dialect_saudi", "ar-SA", "ar", "حياكم الله في استوديو فويس برو، تجربة صوتية احترافية بجودة عالية جداً.", 1.0f, 0.96f),
    TtsDialect("jordanian", "tts_dialect_jordanian", "ar-JO", "ar-SA", "أهلاً وسهلاً فيكم، يسعد أوقاتكم بكل خير من استوديو المعالجة الصوتية.", 1.0f, 0.97f),
    TtsDialect("syrian", "tts_dialect_syrian", "ar-SY", "ar-LB", "يا مية أهلاً وسهلاً فيكم، نورتوا الاستوديو وصوتكم طالع بيشهي وواضح.", 1.02f, 0.96f),
    TtsDialect("moroccan", "tts_dialect_moroccan", "ar-MA", "ar-SA", "مرحبا بكم كاملين فهاد الاستوديو الزوين، كلشي واجد باش تخرجو صوت نقي بزاف.", 0.99f, 0.95f),
    TtsDialect("algerian", "tts_dialect_algerian", "ar-DZ", "ar-SA", "صحا وعافية ليكم كامل، رانا واجدين باش نسجلو أحسن مقطع صوتي بصوت صافي.", 0.98f, 0.96f),
    TtsDialect("tunisian", "tts_dialect_tunisian", "ar-TN", "ar-SA", "عسلامة ومرحبا بيكم الناس الكل، اليوم باش نخدمو على صوت مزيان برشا.", 1.01f, 0.96f),
    TtsDialect("en_us", "tts_dialect_english_us", "en-US", "en", "Welcome to Voice Pro Studio. Crystal clear acoustics ready for your next mix.", 1.0f, 1.0f),
    TtsDialect("persian", "tts_dialect_persian", "fa-IR", "fa", "سلام و خوش آمدید به استودیوی ویس پرو؛ صدای شما اکنون با شفافیت کامل آماده است.", 1.0f, 0.95f),
    TtsDialect("turkish", "tts_dialect_turkish", "tr-TR", "tr", "Voice Pro Stüdyo'ya hoş geldiniz. Sesiniz şimdi kristal netliğinde işlenmeye hazır.", 1.0f, 0.98f),
)

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

data class SearchSourceLink(
    val title: String,
    val uri: String,
    val domain: String,
)

data class AiStreamState(
    val isLoading: Boolean = false,
    val outputText: String = "",
    val errorMessage: String? = null,
    val searchQueries: List<String> = emptyList(),
    val sources: List<SearchSourceLink> = emptyList(),
)

data class TtsStudioUiState(
    val isPreviewing: Boolean = false,
    val isGenerating: Boolean = false,
    val errorMessage: String? = null,
)

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

data class PlaybackTelemetry(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val outputLevel: Float = 0f,
    val gainReductionDb: Float = 0f,
    val noiseSuppressionDb: Float = 0f,
    val waveformBars: List<Float> = List(48) { 0.06f },
)

data class RecorderTelemetry(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedMs: Long = 0L,
    val micLevel: Float = 0f,
    val waveformBars: List<Float> = List(48) { 0.05f },
)
