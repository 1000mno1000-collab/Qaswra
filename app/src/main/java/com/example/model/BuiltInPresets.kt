package com.example.model

val GLOBAL_PRO_PRESETS: List<GlobalProPreset> = listOf(
    run {
        val def = DspConfig()
        GlobalProPreset(
            id = GlobalProPresetId.BROADCAST_RADIO,
            titleKey = "pro_preset_broadcast",
            descKey = "pro_preset_broadcast_desc",
            ffmpegFilter = "acompressor=threshold=-18dB:ratio=4:attack=5:release=50,bass=g=5,treble=g=3,alimiter=limit=0.95",
            dspConfig = def
                .withSlotEnabled(EffectType.COMPRESSOR, true)
                .withSlotEnabled(EffectType.BASS_BOOST, true)
                .withSlotEnabled(EffectType.TREBLE, true)
                .withSlotEnabled(EffectType.LIMITER, true)
                .copy(
                    compressor = CompressorParams(
                        thresholdDb = -18f,
                        ratio = 4f,
                        attackMs = 5f,
                        releaseMs = 50f,
                        makeupGainDb = 3.2f,
                    ),
                    bassBoost = BassBoostParams(gainDb = 5f, frequencyHz = 105f),
                    treble = TrebleParams(gainDb = 3f, frequencyHz = 6500f),
                    limiter = LimiterParams(ceilingDb = -0.45f, releaseMs = 60f),
                ),
        )
    },
    run {
        val def = DspConfig()
        GlobalProPreset(
            id = GlobalProPresetId.AUTOTUNE_SYNTH,
            titleKey = "pro_preset_autotune",
            descKey = "pro_preset_autotune_desc",
            ffmpegFilter = "asetrate=44100*0.97,aresample=44100,aecho=0.8:0.88:40:0.3,treble=g=4",
            dspConfig = def
                .withSlotEnabled(EffectType.PITCH_SHIFT, true)
                .withSlotEnabled(EffectType.ECHO_REVERB, true)
                .withSlotEnabled(EffectType.TREBLE, true)
                .withSlotEnabled(EffectType.CHORUS, true)
                .withSlotEnabled(EffectType.LIMITER, true)
                .copy(
                    vibratoHz = 6f,
                    vibratoDepth = 0.42f,
                    pitchShift = PitchShiftParams(semitones = -0.53f, wetMix = 0.85f),
                    treble = TrebleParams(gainDb = 4f, frequencyHz = 6200f),
                    chorus = ChorusParams(rateHz = 4.2f, depthMs = 3.8f, mix = 0.28f),
                    echoReverb = EchoReverbParams(
                        echoStrength = 0.35f,
                        echoDepth = 0.4f,
                        delayTimeMs = 40f,
                        feedback = 0.3f,
                        reverbSize = 0.32f,
                        reverbDecay = 0.28f,
                        preDelayMs = 10f,
                        wetDryMix = 0.28f,
                        stereoWidth = 0.65f,
                        earlyReflections = 0.35f,
                        lateReflections = 0.32f,
                        damping = 0.3f,
                        diffusion = 0.65f,
                    ),
                    limiter = LimiterParams(ceilingDb = -0.6f, releaseMs = 70f),
                ),
        )
    },
    run {
        val def = DspConfig()
        GlobalProPreset(
            id = GlobalProPresetId.EPIC_CINEMATIC,
            titleKey = "pro_preset_cinematic",
            descKey = "pro_preset_cinematic_desc",
            ffmpegFilter = "asetrate=44100*0.86,aresample=44100,bass=g=7,aecho=0.8:0.9:80:0.45",
            dspConfig = def
                .withSlotEnabled(EffectType.PITCH_SHIFT, true)
                .withSlotEnabled(EffectType.BASS_BOOST, true)
                .withSlotEnabled(EffectType.COMPRESSOR, true)
                .withSlotEnabled(EffectType.ECHO_REVERB, true)
                .withSlotEnabled(EffectType.LIMITER, true)
                .copy(
                    pitchShift = PitchShiftParams(semitones = -2.6f, wetMix = 1f),
                    bassBoost = BassBoostParams(gainDb = 7f, frequencyHz = 95f),
                    compressor = CompressorParams(
                        thresholdDb = -18f,
                        ratio = 3.6f,
                        attackMs = 10f,
                        releaseMs = 130f,
                        makeupGainDb = 2.5f,
                    ),
                    echoReverb = EchoReverbParams(
                        echoStrength = 0.48f,
                        echoDepth = 0.68f,
                        delayTimeMs = 80f,
                        feedback = 0.45f,
                        reverbSize = 0.78f,
                        reverbDecay = 0.68f,
                        preDelayMs = 24f,
                        wetDryMix = 0.4f,
                        stereoWidth = 0.85f,
                        earlyReflections = 0.52f,
                        lateReflections = 0.68f,
                        damping = 0.35f,
                        diffusion = 0.82f,
                    ),
                    limiter = LimiterParams(ceilingDb = -0.6f, releaseMs = 80f),
                ),
        )
    },
    run {
        val def = DspConfig()
        GlobalProPreset(
            id = GlobalProPresetId.SURROUND_8D_CINEMA,
            titleKey = "pro_preset_8d",
            descKey = "pro_preset_8d_desc",
            ffmpegFilter = "apulsator=hz=0.125,aecho=0.8:0.88:60:0.4",
            dspConfig = def
                .withSlotEnabled(EffectType.ECHO_REVERB, true)
                .withSlotEnabled(EffectType.CHORUS, true)
                .withSlotEnabled(EffectType.LIMITER, true)
                .copy(
                    spatialPanHz = 0.125f,
                    chorus = ChorusParams(rateHz = 0.25f, depthMs = 8f, mix = 0.35f),
                    echoReverb = EchoReverbParams(
                        echoStrength = 0.62f,
                        echoDepth = 0.78f,
                        delayTimeMs = 60f,
                        feedback = 0.4f,
                        reverbSize = 0.84f,
                        reverbDecay = 0.72f,
                        preDelayMs = 22f,
                        wetDryMix = 0.44f,
                        stereoWidth = 0.98f,
                        earlyReflections = 0.64f,
                        lateReflections = 0.78f,
                        damping = 0.28f,
                        diffusion = 0.88f,
                    ),
                    limiter = LimiterParams(ceilingDb = -0.6f, releaseMs = 80f),
                ),
        )
    },
    run {
        val def = DspConfig()
        GlobalProPreset(
            id = GlobalProPresetId.CRYSTAL_VOCAL,
            titleKey = "pro_preset_crystal",
            descKey = "pro_preset_crystal_desc",
            ffmpegFilter = com.example.audio.fx.AudioSkillsPresets.CLEAN_PODCAST_MIC,
            dspConfig = com.example.audio.fx.AudioSkillsPresets.cleanPodcastMicDspConfig(),
        )
    },
)

val BUILT_IN_PRESETS: List<StudioPreset> = listOf(
    StudioPreset(
        id = -8L,
        name = "ماسترينغ الاستوديو الدافئ (Studio Vocal)",
        description = "جودة صوت بشري فاخرة: فلتر 75Hz، دفء 180Hz، هواء 8kHz وضغط 3:1",
        isBuiltIn = true,
        config = com.example.audio.fx.AudioSkillsPresets.studioVocalMasteringDspConfig(),
    ),
    StudioPreset(
        id = -9L,
        name = "تنقية البودكاست والمايك (Clean Mic)",
        description = "عزل طيفي للضوضاء -25dB وبوابة ذكية مع إزالة الصفير وموازنة البث",
        isBuiltIn = true,
        config = com.example.audio.fx.AudioSkillsPresets.cleanPodcastMicDspConfig(),
    ),
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
                    echoReverb = EchoReverbParams(
                        echoStrength = 0.12f,
                        echoDepth = 0.15f,
                        delayTimeMs = 160f,
                        feedback = 0.15f,
                        reverbSize = 0.25f,
                        reverbDecay = 0.2f,
                        preDelayMs = 12f,
                        wetDryMix = 0.12f,
                        stereoWidth = 0.45f,
                        earlyReflections = 0.25f,
                        lateReflections = 0.22f,
                        damping = 0.42f,
                        diffusion = 0.6f,
                    ),
                    limiter = LimiterParams(ceilingDb = -0.8f, releaseMs = 80f),
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
                    noiseReduction = NoiseReductionParams(
                        thresholdDb = -48f,
                        reductionDb = 14f,
                        highPassHz = 80f,
                        spectralStrength = 0.55f,
                        deEssAmount = 0.35f,
                        clarityBoostDb = 2f,
                        spectralCleanerEnabled = true,
                    ),
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
                    limiter = LimiterParams(ceilingDb = -0.8f, releaseMs = 80f),
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
                    noiseReduction = NoiseReductionParams(
                        thresholdDb = -48f,
                        reductionDb = 16f,
                        highPassHz = 70f,
                        spectralStrength = 0.6f,
                        deEssAmount = 0.35f,
                        clarityBoostDb = 2f,
                        spectralCleanerEnabled = true,
                    ),
                    bassBoost = BassBoostParams(5.5f, 110f),
                    equalizer = EqualizerParams(2.5f, 2f, 0.5f, 2f, -1f),
                    compressor = CompressorParams(-22f, 4.5f, 8f, 120f, 4f),
                    echoReverb = EchoReverbParams(
                        echoStrength = 0.1f,
                        echoDepth = 0.18f,
                        delayTimeMs = 190f,
                        feedback = 0.18f,
                        reverbSize = 0.3f,
                        reverbDecay = 0.25f,
                        preDelayMs = 14f,
                        wetDryMix = 0.15f,
                        stereoWidth = 0.5f,
                        earlyReflections = 0.28f,
                        lateReflections = 0.25f,
                        damping = 0.65f,
                        diffusion = 0.58f,
                    ),
                    limiter = LimiterParams(ceilingDb = -0.8f, releaseMs = 80f),
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
                    compressor = CompressorParams(-16f, 2.8f, 14f, 160f, 2f),
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
                    echoReverb = EchoReverbParams(
                        echoStrength = 0.72f,
                        echoDepth = 0.75f,
                        delayTimeMs = 360f,
                        feedback = 0.6f,
                        reverbSize = 0.68f,
                        reverbDecay = 0.6f,
                        preDelayMs = 24f,
                        wetDryMix = 0.52f,
                        stereoWidth = 0.96f,
                        earlyReflections = 0.5f,
                        lateReflections = 0.65f,
                        damping = 0.35f,
                        diffusion = 0.8f,
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
                    limiter = LimiterParams(ceilingDb = -0.8f, releaseMs = 80f),
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
                    noiseReduction = NoiseReductionParams(
                        thresholdDb = -48f,
                        reductionDb = 16f,
                        highPassHz = 75f,
                        spectralStrength = 0.6f,
                        deEssAmount = 0.3f,
                        clarityBoostDb = 2f,
                        spectralCleanerEnabled = true,
                    ),
                    pitchShift = PitchShiftParams(-2.2f, 1f),
                    equalizer = EqualizerParams(3.5f, 1.5f, -1f, 2.5f, 1.5f),
                    compressor = CompressorParams(-18f, 3.5f, 12f, 140f, 2.5f),
                    echoReverb = EchoReverbParams(
                        echoStrength = 0.42f,
                        echoDepth = 0.65f,
                        delayTimeMs = 310f,
                        feedback = 0.35f,
                        reverbSize = 0.7f,
                        reverbDecay = 0.58f,
                        preDelayMs = 26f,
                        wetDryMix = 0.34f,
                        stereoWidth = 0.8f,
                        earlyReflections = 0.45f,
                        lateReflections = 0.6f,
                        damping = 0.45f,
                        diffusion = 0.78f,
                    ),
                    limiter = LimiterParams(ceilingDb = -0.8f, releaseMs = 80f),
                ),
        )
    },
)
