package com.example.model

data class StudioPreset(
    val id: Long = 0,
    val name: String,
    val description: String,
    val isBuiltIn: Boolean,
    val config: DspConfig
)

object BuiltInPresets {
    private fun chainWithEnabled(vararg enabled: EffectType): List<EffectSlot> {
        val enabledSet = enabled.toSet()
        return DspConfig.defaultChain().map { slot ->
            slot.copy(enabled = slot.type in enabledSet)
        }
    }

    val all: List<StudioPreset> = listOf(
        StudioPreset(
            id = -1,
            name = "صدى استوديو",
            description = "صدى متوازن ودافئ مناسب للغناء والبودكاست الاحترافي",
            isBuiltIn = true,
            config = DspConfig(
                chain = chainWithEnabled(
                    EffectType.NOISE_REDUCTION,
                    EffectType.EQUALIZER,
                    EffectType.COMPRESSOR,
                    EffectType.ECHO_REVERB,
                    EffectType.LIMITER
                ),
                noiseReduction = NoiseReductionParams(thresholdDb = -46f, amount = 0.55f),
                equalizer = EqualizerParams(band60HzDb = -2f, band250HzDb = -1f, band1kHzDb = 1.5f, band4kHzDb = 2.5f, band12kHzDb = 2f),
                compressor = CompressorParams(thresholdDb = -18f, ratio = 3.2f, attackMs = 10f, releaseMs = 120f, makeupDb = 2.5f),
                echoReverb = EchoReverbParams(
                    echoStrength = 0.22f,
                    echoDepth = 0.30f,
                    delayTimeMs = 210f,
                    feedback = 0.25f,
                    reverbSize = 0.35f,
                    reverbDecay = 0.38f,
                    preDelayMs = 14f,
                    wetDryMix = 0.26f,
                    stereoWidth = 1.1f,
                    earlyReflections = 0.45f,
                    lateReflections = 0.32f,
                    damping = 0.55f,
                    diffusion = 0.65f
                ),
                limiter = LimiterParams(ceilingDb = -0.8f, releaseMs = 70f)
            )
        ),
        StudioPreset(
            id = -2,
            name = "صدى قاعة كبيرة",
            description = "اتساع مسرحي غني بالانعكاسات الطبيعية للقاعات الواسعة",
            isBuiltIn = true,
            config = DspConfig(
                chain = chainWithEnabled(EffectType.EQUALIZER, EffectType.ECHO_REVERB, EffectType.LIMITER),
                equalizer = EqualizerParams(band250HzDb = -1.5f, band4kHzDb = 1.5f, band12kHzDb = 2.5f),
                echoReverb = EchoReverbParams(
                    echoStrength = 0.30f,
                    echoDepth = 0.48f,
                    delayTimeMs = 340f,
                    feedback = 0.38f,
                    reverbSize = 0.78f,
                    reverbDecay = 0.72f,
                    preDelayMs = 32f,
                    wetDryMix = 0.44f,
                    stereoWidth = 1.45f,
                    earlyReflections = 0.62f,
                    lateReflections = 0.76f,
                    damping = 0.36f,
                    diffusion = 0.80f
                )
            )
        ),
        StudioPreset(
            id = -3,
            name = "صدى سينمائي",
            description = "عمق درامي مهيب وتكرارات متدرجة للمقاطع السردية",
            isBuiltIn = true,
            config = DspConfig(
                chain = chainWithEnabled(EffectType.BASS_BOOST, EffectType.COMPRESSOR, EffectType.ECHO_REVERB, EffectType.LIMITER),
                bassBoost = BassBoostParams(gainDb = 3.5f, frequencyHz = 85f),
                compressor = CompressorParams(thresholdDb = -20f, ratio = 4.0f, attackMs = 8f, releaseMs = 180f, makeupDb = 3f),
                echoReverb = EchoReverbParams(
                    echoStrength = 0.46f,
                    echoDepth = 0.65f,
                    delayTimeMs = 460f,
                    feedback = 0.52f,
                    reverbSize = 0.88f,
                    reverbDecay = 0.80f,
                    preDelayMs = 48f,
                    wetDryMix = 0.52f,
                    stereoWidth = 1.65f,
                    earlyReflections = 0.55f,
                    lateReflections = 0.86f,
                    damping = 0.42f,
                    diffusion = 0.88f
                )
            )
        ),
        StudioPreset(
            id = -4,
            name = "صدى عميق",
            description = "تلاشي طويل ومغلف يمنح الصوت حضوراً غامراً",
            isBuiltIn = true,
            config = DspConfig(
                chain = chainWithEnabled(EffectType.ECHO_REVERB, EffectType.LIMITER),
                echoReverb = EchoReverbParams(
                    echoStrength = 0.55f,
                    echoDepth = 0.78f,
                    delayTimeMs = 520f,
                    feedback = 0.62f,
                    reverbSize = 0.94f,
                    reverbDecay = 0.86f,
                    preDelayMs = 24f,
                    wetDryMix = 0.60f,
                    stereoWidth = 1.5f,
                    earlyReflections = 0.48f,
                    lateReflections = 0.90f,
                    damping = 0.58f,
                    diffusion = 0.90f
                )
            )
        ),
        StudioPreset(
            id = -5,
            name = "صوت إذاعي",
            description = "ضغط ديناميكي محكم وجهير دافئ ووضوح حاد كمحطات البث",
            isBuiltIn = true,
            config = DspConfig(
                chain = chainWithEnabled(
                    EffectType.NOISE_REDUCTION,
                    EffectType.EQUALIZER,
                    EffectType.BASS_BOOST,
                    EffectType.TREBLE_CONTROL,
                    EffectType.COMPRESSOR,
                    EffectType.LIMITER
                ),
                noiseReduction = NoiseReductionParams(thresholdDb = -44f, amount = 0.68f),
                equalizer = EqualizerParams(band60HzDb = 2f, band250HzDb = -2.5f, band1kHzDb = 1.5f, band4kHzDb = 3.5f, band12kHzDb = 2f),
                bassBoost = BassBoostParams(gainDb = 4.0f, frequencyHz = 90f),
                treble = TrebleParams(gainDb = 2.5f, frequencyHz = 7000f),
                compressor = CompressorParams(thresholdDb = -22f, ratio = 5.5f, attackMs = 5f, releaseMs = 95f, makeupDb = 5.0f),
                limiter = LimiterParams(ceilingDb = -0.5f, releaseMs = 50f)
            )
        ),
        StudioPreset(
            id = -6,
            name = "صوت واضح ونقي",
            description = "عزل ذكي للضوضاء مع تصفية ترددات الصوت البشري",
            isBuiltIn = true,
            config = DspConfig(
                chain = chainWithEnabled(
                    EffectType.NOISE_REDUCTION,
                    EffectType.EQUALIZER,
                    EffectType.TREBLE_CONTROL,
                    EffectType.COMPRESSOR,
                    EffectType.LIMITER
                ),
                noiseReduction = NoiseReductionParams(thresholdDb = -40f, amount = 0.82f),
                equalizer = EqualizerParams(band60HzDb = -4f, band250HzDb = -1.5f, band1kHzDb = 2f, band4kHzDb = 3f, band12kHzDb = 2.5f),
                treble = TrebleParams(gainDb = 2.0f, frequencyHz = 8000f),
                compressor = CompressorParams(thresholdDb = -16f, ratio = 2.4f, attackMs = 14f, releaseMs = 130f, makeupDb = 2.0f),
                limiter = LimiterParams(ceilingDb = -0.8f, releaseMs = 60f)
            )
        ),
        StudioPreset(
            id = -7,
            name = "تأثير فضائي",
            description = "مزيج كورس وفيزر وتردد واسع الأبعاد بطابع كوني",
            isBuiltIn = true,
            config = DspConfig(
                chain = chainWithEnabled(
                    EffectType.PITCH_SHIFT,
                    EffectType.CHORUS,
                    EffectType.PHASER,
                    EffectType.ECHO_REVERB,
                    EffectType.LIMITER
                ),
                pitchShift = PitchShiftParams(semitones = -2.0f),
                chorus = ChorusParams(rateHz = 0.9f, depth = 0.72f, mix = 0.50f),
                phaser = PhaserParams(rateHz = 0.38f, depth = 0.75f, feedback = 0.55f, mix = 0.45f),
                echoReverb = EchoReverbParams(
                    echoStrength = 0.62f,
                    echoDepth = 0.82f,
                    delayTimeMs = 410f,
                    feedback = 0.64f,
                    reverbSize = 0.96f,
                    reverbDecay = 0.88f,
                    preDelayMs = 42f,
                    wetDryMix = 0.65f,
                    stereoWidth = 1.90f,
                    earlyReflections = 0.68f,
                    lateReflections = 0.92f,
                    damping = 0.22f,
                    diffusion = 0.94f
                )
            )
        )
    )
}
