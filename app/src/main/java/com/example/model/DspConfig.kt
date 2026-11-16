package com.example.model

import androidx.annotation.StringRes
import com.example.R
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

enum class EffectType(@StringRes val titleRes: Int, val category: EffectCategory) {
    NOISE_REDUCTION(R.string.effect_noise_reduction, EffectCategory.CLEANUP),
    EQUALIZER(R.string.effect_equalizer, EffectCategory.TONE),
    BASS_BOOST(R.string.effect_bass_boost, EffectCategory.TONE),
    TREBLE_CONTROL(R.string.effect_treble, EffectCategory.TONE),
    PITCH_SHIFT(R.string.effect_pitch_shift, EffectCategory.TIME_PITCH),
    TIME_STRETCH(R.string.effect_time_stretch, EffectCategory.TIME_PITCH),
    COMPRESSOR(R.string.effect_compressor, EffectCategory.DYNAMICS),
    DISTORTION(R.string.effect_distortion, EffectCategory.CHARACTER),
    CHORUS(R.string.effect_chorus, EffectCategory.MODULATION),
    FLANGER(R.string.effect_flanger, EffectCategory.MODULATION),
    PHASER(R.string.effect_phaser, EffectCategory.MODULATION),
    ECHO_REVERB(R.string.effect_echo_reverb, EffectCategory.SPACE),
    LIMITER(R.string.effect_limiter, EffectCategory.DYNAMICS)
}

enum class EffectCategory(val arabicLabel: String) {
    CLEANUP("تنقية"),
    TONE("تشكيل الترددات"),
    TIME_PITCH("الطبقة والزمن"),
    DYNAMICS("الديناميكيات"),
    CHARACTER("التشبيع واللون"),
    MODULATION("التعديل الحركي"),
    SPACE("الفضاء والصدى")
}

@JsonClass(generateAdapter = true)
data class EffectSlot(
    val type: EffectType,
    val enabled: Boolean = true
)

@JsonClass(generateAdapter = true)
data class EchoReverbParams(
    val echoStrength: Float = 0.28f,      // 0..1
    val echoDepth: Float = 0.40f,         // 0..1 (modulation & multi-tap richness)
    val delayTimeMs: Float = 260f,        // 20..1200 ms
    val feedback: Float = 0.35f,          // 0..0.90
    val reverbSize: Float = 0.45f,        // 0..1
    val reverbDecay: Float = 0.45f,       // 0..0.95
    val preDelayMs: Float = 18f,          // 0..150 ms
    val wetDryMix: Float = 0.32f,         // 0..1
    val stereoWidth: Float = 1.15f,       // 0..2.0
    val earlyReflections: Float = 0.42f,  // 0..1
    val lateReflections: Float = 0.48f,   // 0..1
    val damping: Float = 0.45f,           // 0..1 (HF absorption)
    val diffusion: Float = 0.65f          // 0..1 (all-pass density)
)

@JsonClass(generateAdapter = true)
data class EqualizerParams(
    val band60HzDb: Float = 0f,    // -12..+12 dB
    val band250HzDb: Float = 0f,
    val band1kHzDb: Float = 0f,
    val band4kHzDb: Float = 0f,
    val band12kHzDb: Float = 0f
)

@JsonClass(generateAdapter = true)
data class BassBoostParams(
    val gainDb: Float = 0f,        // 0..12 dB
    val frequencyHz: Float = 95f   // 50..180 Hz
)

@JsonClass(generateAdapter = true)
data class TrebleParams(
    val gainDb: Float = 0f,        // -12..+12 dB
    val frequencyHz: Float = 7500f // 3500..12000 Hz
)

@JsonClass(generateAdapter = true)
data class PitchShiftParams(
    val semitones: Float = 0f      // -12..+12 semitones
)

@JsonClass(generateAdapter = true)
data class TimeStretchParams(
    val rate: Float = 1.0f         // 0.5..2.0x
)

@JsonClass(generateAdapter = true)
data class ChorusParams(
    val rateHz: Float = 1.2f,      // 0.1..5.0 Hz
    val depth: Float = 0.45f,      // 0..1
    val mix: Float = 0.35f         // 0..1
)

@JsonClass(generateAdapter = true)
data class FlangerParams(
    val rateHz: Float = 0.45f,     // 0.05..3.0 Hz
    val depth: Float = 0.55f,      // 0..1
    val feedback: Float = 0.45f,   // 0..0.85
    val mix: Float = 0.40f         // 0..1
)

@JsonClass(generateAdapter = true)
data class PhaserParams(
    val rateHz: Float = 0.6f,      // 0.05..4.0 Hz
    val depth: Float = 0.6f,       // 0..1
    val feedback: Float = 0.4f,    // 0..0.85
    val mix: Float = 0.45f         // 0..1
)

@JsonClass(generateAdapter = true)
data class DistortionParams(
    val drive: Float = 0.25f,      // 0..1
    val tone: Float = 0.65f,       // 0..1 (LPF brightness)
    val mix: Float = 0.40f         // 0..1
)

@JsonClass(generateAdapter = true)
data class CompressorParams(
    val thresholdDb: Float = -18f, // -40..0 dB
    val ratio: Float = 3.5f,       // 1..20
    val attackMs: Float = 12f,     // 1..100 ms
    val releaseMs: Float = 140f,   // 20..600 ms
    val makeupDb: Float = 3f       // 0..18 dB
)

@JsonClass(generateAdapter = true)
data class LimiterParams(
    val ceilingDb: Float = -0.8f,  // -12..-0.1 dB
    val releaseMs: Float = 80f     // 10..400 ms
)

@JsonClass(generateAdapter = true)
data class NoiseReductionParams(
    val thresholdDb: Float = -42f, // -65..-15 dB
    val amount: Float = 0.70f      // 0..1
)

@JsonClass(generateAdapter = true)
data class DspConfig(
    val bypassAll: Boolean = false,
    val masterGainDb: Float = 0f,
    val chain: List<EffectSlot> = defaultChain(),
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
    val noiseReduction: NoiseReductionParams = NoiseReductionParams()
) {
    fun isEffectEnabled(type: EffectType): Boolean =
        !bypassAll && chain.firstOrNull { it.type == type }?.enabled == true

    companion object {
        fun defaultChain(): List<EffectSlot> = listOf(
            EffectSlot(EffectType.NOISE_REDUCTION, enabled = false),
            EffectSlot(EffectType.PITCH_SHIFT, enabled = false),
            EffectSlot(EffectType.TIME_STRETCH, enabled = false),
            EffectSlot(EffectType.EQUALIZER, enabled = false),
            EffectSlot(EffectType.BASS_BOOST, enabled = false),
            EffectSlot(EffectType.TREBLE_CONTROL, enabled = false),
            EffectSlot(EffectType.COMPRESSOR, enabled = false),
            EffectSlot(EffectType.DISTORTION, enabled = false),
            EffectSlot(EffectType.CHORUS, enabled = false),
            EffectSlot(EffectType.FLANGER, enabled = false),
            EffectSlot(EffectType.PHASER, enabled = false),
            EffectSlot(EffectType.ECHO_REVERB, enabled = true),
            EffectSlot(EffectType.LIMITER, enabled = true)
        )

        private val moshi: Moshi = Moshi.Builder().build()
        private val adapter = moshi.adapter(DspConfig::class.java)

        fun toJson(config: DspConfig): String = adapter.toJson(config)

        fun fromJson(json: String): DspConfig =
            runCatching { adapter.fromJson(json) }.getOrNull() ?: DspConfig()
    }
}
