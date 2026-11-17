import {
  DspConfig,
  EffectType,
  StudioPreset,
  createDefaultDspConfig,
} from "../types/studio";

function withUpdatedSlot(
  config: DspConfig,
  type: EffectType,
  enabled: boolean
): DspConfig {
  return {
    ...config,
    chain: config.chain.map((slot) =>
      slot.type === type ? { ...slot, enabled } : slot
    ),
  };
}

export const BUILT_IN_PRESETS: StudioPreset[] = [
  (() => {
    let cfg = createDefaultDspConfig();
    cfg = withUpdatedSlot(cfg, EffectType.NOISE_REDUCTION, true);
    cfg = withUpdatedSlot(cfg, EffectType.EQUALIZER, true);
    cfg = withUpdatedSlot(cfg, EffectType.COMPRESSOR, true);
    cfg = withUpdatedSlot(cfg, EffectType.ECHO_REVERB, true);
    cfg = withUpdatedSlot(cfg, EffectType.LIMITER, true);
    return {
      id: -1,
      name: "بودكاست احترافي",
      description: "وضوح حوار عالٍ مع عزل ضوضاء وصدى استوديو خفيف جداً",
      isBuiltIn: true,
      config: {
        ...cfg,
        noiseReduction: {
          thresholdDb: -46,
          reductionDb: 18,
          highPassHz: 95,
          spectralStrength: 0.72,
          deEssAmount: 0.45,
          clarityBoostDb: 3.5,
          spectralCleanerEnabled: true,
        },
        equalizer: {
          band60HzDb: -3,
          band230HzDb: -1.5,
          band910HzDb: 1.5,
          band3600HzDb: 4,
          band14000HzDb: 2.5,
        },
        compressor: {
          thresholdDb: -19,
          ratio: 4,
          attackMs: 10,
          releaseMs: 130,
          makeupGainDb: 3.5,
        },
        echoReverb: {
          ...cfg.echoReverb,
          echoStrength: 0.12,
          echoDepth: 0.15,
          wetDryMix: 0.12,
          reverbSize: 0.25,
          reverbDecay: 0.2,
        },
      },
    };
  })(),

  (() => {
    let cfg = createDefaultDspConfig();
    cfg = withUpdatedSlot(cfg, EffectType.NOISE_REDUCTION, true);
    cfg = withUpdatedSlot(cfg, EffectType.EQUALIZER, true);
    cfg = withUpdatedSlot(cfg, EffectType.COMPRESSOR, true);
    cfg = withUpdatedSlot(cfg, EffectType.CHORUS, true);
    cfg = withUpdatedSlot(cfg, EffectType.ECHO_REVERB, true);
    cfg = withUpdatedSlot(cfg, EffectType.LIMITER, true);
    return {
      id: -2,
      name: "غناء وقاعة فخمة",
      description: "صدى عميق واسع الستيريو مع لمعان غنائي وكورس دافئ",
      isBuiltIn: true,
      config: {
        ...cfg,
        equalizer: {
          band60HzDb: -1,
          band230HzDb: 1,
          band910HzDb: 1,
          band3600HzDb: 3.5,
          band14000HzDb: 4.5,
        },
        chorus: { rateHz: 0.9, depthMs: 5, mix: 0.22 },
        echoReverb: {
          echoStrength: 0.55,
          echoDepth: 0.7,
          delayTimeMs: 340,
          feedback: 0.44,
          reverbSize: 0.78,
          reverbDecay: 0.72,
          preDelayMs: 38,
          wetDryMix: 0.48,
          stereoWidth: 0.88,
          earlyReflections: 0.55,
          lateReflections: 0.75,
          damping: 0.3,
          diffusion: 0.85,
        },
        compressor: {
          thresholdDb: -16,
          ratio: 3.2,
          attackMs: 15,
          releaseMs: 160,
          makeupGainDb: 2.5,
        },
      },
    };
  })(),

  (() => {
    let cfg = createDefaultDspConfig();
    cfg = withUpdatedSlot(cfg, EffectType.NOISE_REDUCTION, true);
    cfg = withUpdatedSlot(cfg, EffectType.BASS_BOOST, true);
    cfg = withUpdatedSlot(cfg, EffectType.EQUALIZER, true);
    cfg = withUpdatedSlot(cfg, EffectType.COMPRESSOR, true);
    cfg = withUpdatedSlot(cfg, EffectType.ECHO_REVERB, true);
    cfg = withUpdatedSlot(cfg, EffectType.LIMITER, true);
    return {
      id: -3,
      name: "راديو دافئ ومخملي",
      description: "دفء صوتي جهوري يشبه استوديوهات الإذاعة الكلاسيكية",
      isBuiltIn: true,
      config: {
        ...cfg,
        bassBoost: { gainDb: 5.5, frequencyHz: 110 },
        equalizer: {
          band60HzDb: 2.5,
          band230HzDb: 2,
          band910HzDb: 0.5,
          band3600HzDb: 2,
          band14000HzDb: -1,
        },
        compressor: {
          thresholdDb: -22,
          ratio: 4.5,
          attackMs: 8,
          releaseMs: 120,
          makeupGainDb: 4,
        },
        echoReverb: {
          ...cfg.echoReverb,
          echoStrength: 0.1,
          echoDepth: 0.18,
          wetDryMix: 0.15,
          reverbSize: 0.3,
          damping: 0.65,
        },
      },
    };
  })(),

  (() => {
    let cfg = createDefaultDspConfig();
    cfg = withUpdatedSlot(cfg, EffectType.ECHO_REVERB, true);
    cfg = withUpdatedSlot(cfg, EffectType.TREBLE, true);
    cfg = withUpdatedSlot(cfg, EffectType.COMPRESSOR, true);
    return {
      id: -4,
      name: "كاتدرائية سماوية",
      description: "قاعة ضخمة طويلة التلاشي للإنشاد والتأثيرات السينمائية",
      isBuiltIn: true,
      config: {
        ...cfg,
        treble: { gainDb: 3, frequencyHz: 7500 },
        echoReverb: {
          echoStrength: 0.62,
          echoDepth: 0.86,
          delayTimeMs: 420,
          feedback: 0.52,
          reverbSize: 0.94,
          reverbDecay: 0.88,
          preDelayMs: 55,
          wetDryMix: 0.62,
          stereoWidth: 0.95,
          earlyReflections: 0.6,
          lateReflections: 0.9,
          damping: 0.24,
          diffusion: 0.92,
        },
      },
    };
  })(),

  (() => {
    let cfg = createDefaultDspConfig();
    cfg = withUpdatedSlot(cfg, EffectType.EQUALIZER, true);
    cfg = withUpdatedSlot(cfg, EffectType.PHASER, true);
    cfg = withUpdatedSlot(cfg, EffectType.CHORUS, true);
    cfg = withUpdatedSlot(cfg, EffectType.ECHO_REVERB, true);
    cfg = withUpdatedSlot(cfg, EffectType.DISTORTION, true);
    return {
      id: -5,
      name: "صوت فضائي خيالي",
      description: "طابع إلكتروني غامر مع فيزر وكورس وصدى متأرجح",
      isBuiltIn: true,
      config: {
        ...cfg,
        phaser: { rateHz: 0.85, depth: 0.82, feedback: 0.5, mix: 0.45 },
        chorus: { rateHz: 1.8, depthMs: 9, mix: 0.45 },
        distortion: { drive: 3.2, toneHz: 3200, mix: 0.25, mode: "SOFT_CLIP" },
        echoReverb: {
          ...cfg.echoReverb,
          echoStrength: 0.72,
          echoDepth: 0.75,
          delayTimeMs: 360,
          feedback: 0.6,
          wetDryMix: 0.52,
          stereoWidth: 0.96,
        },
      },
    };
  })(),

  (() => {
    let cfg = createDefaultDspConfig();
    cfg = withUpdatedSlot(cfg, EffectType.NOISE_REDUCTION, true);
    cfg = withUpdatedSlot(cfg, EffectType.COMPRESSOR, true);
    cfg = withUpdatedSlot(cfg, EffectType.TREBLE, true);
    cfg = withUpdatedSlot(cfg, EffectType.LIMITER, true);
    return {
      id: -6,
      name: "تعليق صوتي نقي",
      description: "توازن ذكي وعزل هواء مثالي للإعلانات والكتب الصوتية",
      isBuiltIn: true,
      config: {
        ...cfg,
        noiseReduction: {
          thresholdDb: -48,
          reductionDb: 22,
          highPassHz: 105,
          spectralStrength: 0.78,
          deEssAmount: 0.52,
          clarityBoostDb: 4,
          spectralCleanerEnabled: true,
        },
        treble: { gainDb: 3.5, frequencyHz: 6000 },
        compressor: {
          thresholdDb: -18,
          ratio: 3.8,
          attackMs: 12,
          releaseMs: 145,
          makeupGainDb: 3,
        },
      },
    };
  })(),

  (() => {
    let cfg = createDefaultDspConfig();
    cfg = withUpdatedSlot(cfg, EffectType.NOISE_REDUCTION, true);
    cfg = withUpdatedSlot(cfg, EffectType.PITCH_SHIFT, true);
    cfg = withUpdatedSlot(cfg, EffectType.EQUALIZER, true);
    cfg = withUpdatedSlot(cfg, EffectType.COMPRESSOR, true);
    cfg = withUpdatedSlot(cfg, EffectType.ECHO_REVERB, true);
    cfg = withUpdatedSlot(cfg, EffectType.LIMITER, true);
    return {
      id: -7,
      name: "سينيما عميق ومهيب",
      description: "خفض خفيف للطبقة مع جهير سينمائي وصدى درامي فخم",
      isBuiltIn: true,
      config: {
        ...cfg,
        pitchShift: { semitones: -2.2, wetMix: 1 },
        equalizer: {
          band60HzDb: 3.5,
          band230HzDb: 1.5,
          band910HzDb: -1,
          band3600HzDb: 2.5,
          band14000HzDb: 1.5,
        },
        echoReverb: {
          ...cfg.echoReverb,
          echoStrength: 0.42,
          echoDepth: 0.65,
          delayTimeMs: 310,
          feedback: 0.35,
          reverbSize: 0.7,
          wetDryMix: 0.34,
          damping: 0.45,
        },
      },
    };
  })(),
];
