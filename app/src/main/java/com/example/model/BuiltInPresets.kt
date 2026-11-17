package com.example.model

val BUILT_IN_PRESETS: List<StudioPreset> = listOf(
    run {
        val def = DspConfig()
        StudioPreset(
            id = -1L,
            name = "بودكاست احترافي",
            description = "وضوح حوار عالٍ مع عزل ضوضاء وصدى استوديو خفيف جداً",
            isBuiltIn = true,
            config = def
                .withSlotEnabled(EffectType.NOISE_REDUCTION, true)
                .withSlotEnabled(EffectType.EQUALIZER, true)
                .withSlotEnabled(EffectType.COMPRESSOR, true)
                .withSlotEnabled(EffectType.ECHO_REVERB, true)
                .withSlotEnabled(EffectType.LIMITER, true)
                .copy(
                    noiseReduction = NoiseReductionParams(
                        thresholdDb = -46f,
                        reductionDb = 18f,
                        highPassHz = 95f,
                        spectralStrength = 0.72f,
                        deEssAmount = 0.45f,
                        clarityBoostDb = 3.5f,
                        spectralCleanerEnabled = true,
                    ),
                    equalizer = EqualizerParams(-3f, -1.5f, 1.5f, 4f, 2.5f),
                    compressor = CompressorParams(-19f, 4f, 10f, 130f, 3.5f),
                    echoReverb = def.echoReverb.copy(
                        echoStrength = 0.12f,
                        echoDepth = 0.15f,
                        wetDryMix = 0.12f,
                        reverbSize = 0.25f,
                        reverbDecay = 0.2f,
                    ),
                ),
        )
    },
    run {
        val def = DspConfig()
        StudioPreset(
            id = -2L,
            name = "غناء وقاعة فخمة",
            description = "صدى عميق واسع الستيريو مع لمعان غنائي وكورس دافئ",
            isBuiltIn = true,
            config = def
                .withSlotEnabled(EffectType.NOISE_REDUCTION, true)
                .withSlotEnabled(EffectType.EQUALIZER, true)
                .withSlotEnabled(EffectType.COMPRESSOR, true)
                .withSlotEnabled(EffectType.CHORUS, true)
                .withSlotEnabled(EffectType.ECHO_REVERB, true)
                .withSlotEnabled(EffectType.LIMITER, true)
                .copy(
                    equalizer = EqualizerParams(-1f, 1f, 1f, 3.5f, 4.5f),
                    chorus = ChorusParams(0.9f, 5f, 0.22f),
                    echoReverb = EchoReverbParams(
                        echoStrength = 0.55f,
                        echoDepth = 0.7f,
                        delayTimeMs = 340f,
                        feedback = 0.44f,
                        reverbSize = 0.78f,
                        reverbDecay = 0.72f,
                        preDelayMs = 38f,
                        wetDryMix = 0.48f,
                        stereoWidth = 0.88f,
                        earlyReflections = 0.55f,
                        lateReflections = 0.75f,
                        damping = 0.3f,
                        diffusion = 0.85f,
                    ),
                    compressor = CompressorParams(-16f, 3.2f, 15f, 160f, 2.5f),
                ),
        )
    },
    run {
        val def = DspConfig()
        StudioPreset(
            id = -3L,
            name = "راديو دافئ ومخملي",
            description = "دفء صوتي جهوري يشبه استوديوهات الإذاعة الكلاسيكية",
            isBuiltIn = true,
            config = def
                .withSlotEnabled(EffectType.NOISE_REDUCTION, true)
                .withSlotEnabled(EffectType.BASS_BOOST, true)
                .withSlotEnabled(EffectType.EQUALIZER, true)
                .withSlotEnabled(EffectType.COMPRESSOR, true)
                .withSlotEnabled(EffectType.ECHO_REVERB, true)
                .withSlotEnabled(EffectType.LIMITER, true)
                .copy(
                    bassBoost = BassBoostParams(5.5f, 110f),
                    equalizer = EqualizerParams(2.5f, 2f, 0.5f, 2f, -1f),
                    compressor = CompressorParams(-22f, 4.5f, 8f, 120f, 4f),
                    echoReverb = def.echoReverb.copy(
                        echoStrength = 0.1f,
                        echoDepth = 0.18f,
                        wetDryMix = 0.15f,
                        reverbSize = 0.3f,
                        damping = 0.65f,
                    ),
                ),
        )
    },
    run {
        val def = DspConfig()
        StudioPreset(
            id = -4L,
            name = "كاتدرائية سماوية",
            description = "قاعة ضخمة طويلة التلاشي للإنشاد والتأثيرات السينمائية",
            isBuiltIn = true,
            config = def
                .withSlotEnabled(EffectType.ECHO_REVERB, true)
                .withSlotEnabled(EffectType.TREBLE, true)
                .withSlotEnabled(EffectType.COMPRESSOR, true)
                .copy(
                    treble = TrebleParams(3f, 7500f),
                    echoReverb = EchoReverbParams(
                        echoStrength = 0.62f,
                        echoDepth = 0.86f,
                        delayTimeMs = 420f,
                        feedback = 0.52f,
                        reverbSize = 0.94f,
                        reverbDecay = 0.88f,
                        preDelayMs = 55f,
                        wetDryMix = 0.62f,
                        stereoWidth = 0.95f,
                        earlyReflections = 0.6f,
                        lateReflections = 0.9f,
                        damping = 0.24f,
                        diffusion = 0.92f,
                    ),
                ),
        )
    },
    run {
        val def = DspConfig()
        StudioPreset(
            id = -5L,
            name = "صوت فضائي خيالي",
            description = "طابع إلكتروني غامر مع فيزر وكورس وصدى متأرجح",
            isBuiltIn = true,
            config = def
                .withSlotEnabled(EffectType.EQUALIZER, true)
                .withSlotEnabled(EffectType.PHASER, true)
                .withSlotEnabled(EffectType.CHORUS, true)
                .withSlotEnabled(EffectType.ECHO_REVERB, true)
                .withSlotEnabled(EffectType.DISTORTION, true)
                .copy(
                    phaser = PhaserParams(0.85f, 0.82f, 0.5f, 0.45f),
                    chorus = ChorusParams(1.8f, 9f, 0.45f),
                    distortion = DistortionParams(3.2f, 3200f, 0.25f, DistortionMode.SOFT_CLIP),
                    echoReverb = def.echoReverb.copy(
                        echoStrength = 0.72f,
                        echoDepth = 0.75f,
                        delayTimeMs = 360f,
                        feedback = 0.6f,
                        wetDryMix = 0.52f,
                        stereoWidth = 0.96f,
                    ),
                ),
        )
    },
    run {
        val def = DspConfig()
        StudioPreset(
            id = -6L,
            name = "تعليق صوتي نقي",
            description = "توازن ذكي وعزل هواء مثالي للإعلانات والكتب الصوتية",
            isBuiltIn = true,
            config = def
                .withSlotEnabled(EffectType.NOISE_REDUCTION, true)
                .withSlotEnabled(EffectType.COMPRESSOR, true)
                .withSlotEnabled(EffectType.TREBLE, true)
                .withSlotEnabled(EffectType.LIMITER, true)
                .copy(
                    noiseReduction = NoiseReductionParams(
                        thresholdDb = -48f,
                        reductionDb = 22f,
                        highPassHz = 105f,
                        spectralStrength = 0.78f,
                        deEssAmount = 0.52f,
                        clarityBoostDb = 4f,
                        spectralCleanerEnabled = true,
                    ),
                    treble = TrebleParams(3.5f, 6000f),
                    compressor = CompressorParams(-18f, 3.8f, 12f, 145f, 3f),
                ),
        )
    },
    run {
        val def = DspConfig()
        StudioPreset(
            id = -7L,
            name = "سينيما عميق ومهيب",
            description = "خفض خفيف للطبقة مع جهير سينمائي وصدى درامي فخم",
            isBuiltIn = true,
            config = def
                .withSlotEnabled(EffectType.NOISE_REDUCTION, true)
                .withSlotEnabled(EffectType.PITCH_SHIFT, true)
                .withSlotEnabled(EffectType.EQUALIZER, true)
                .withSlotEnabled(EffectType.COMPRESSOR, true)
                .withSlotEnabled(EffectType.ECHO_REVERB, true)
                .withSlotEnabled(EffectType.LIMITER, true)
                .copy(
                    pitchShift = PitchShiftParams(-2.2f, 1f),
                    equalizer = EqualizerParams(3.5f, 1.5f, -1f, 2.5f, 1.5f),
                    echoReverb = def.echoReverb.copy(
                        echoStrength = 0.42f,
                        echoDepth = 0.65f,
                        delayTimeMs = 310f,
                        feedback = 0.35f,
                        reverbSize = 0.7f,
                        wetDryMix = 0.34f,
                        damping = 0.45f,
                    ),
                ),
        )
    },
)
