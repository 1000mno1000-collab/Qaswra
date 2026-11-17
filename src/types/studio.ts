export type AppLanguage = "ar" | "en" | "fa";

export enum EffectCategory {
  TIME_SPACE = "TIME_SPACE",
  TONE_EQ = "TONE_EQ",
  PITCH_SPEED = "PITCH_SPEED",
  MODULATION = "MODULATION",
  CHARACTER_DYNAMICS = "CHARACTER_DYNAMICS",
  NOISE_CLEAN = "NOISE_CLEAN",
}

export enum EffectType {
  NOISE_REDUCTION = "NOISE_REDUCTION",
  EQUALIZER = "EQUALIZER",
  BASS_BOOST = "BASS_BOOST",
  TREBLE = "TREBLE",
  PITCH_SHIFT = "PITCH_SHIFT",
  TIME_STRETCH = "TIME_STRETCH",
  CHORUS = "CHORUS",
  FLANGER = "FLANGER",
  PHASER = "PHASER",
  DISTORTION = "DISTORTION",
  COMPRESSOR = "COMPRESSOR",
  ECHO_REVERB = "ECHO_REVERB",
  LIMITER = "LIMITER",
}

export const EFFECT_META: Record<
  EffectType,
  { titleKey: string; category: EffectCategory; isProDefault: boolean }
> = {
  [EffectType.NOISE_REDUCTION]: {
    titleKey: "effect_noise_reduction",
    category: EffectCategory.NOISE_CLEAN,
    isProDefault: false,
  },
  [EffectType.EQUALIZER]: {
    titleKey: "effect_equalizer",
    category: EffectCategory.TONE_EQ,
    isProDefault: true,
  },
  [EffectType.BASS_BOOST]: {
    titleKey: "effect_bass_boost",
    category: EffectCategory.TONE_EQ,
    isProDefault: false,
  },
  [EffectType.TREBLE]: {
    titleKey: "effect_treble",
    category: EffectCategory.TONE_EQ,
    isProDefault: false,
  },
  [EffectType.PITCH_SHIFT]: {
    titleKey: "effect_pitch_shift",
    category: EffectCategory.PITCH_SPEED,
    isProDefault: false,
  },
  [EffectType.TIME_STRETCH]: {
    titleKey: "effect_time_stretch",
    category: EffectCategory.PITCH_SPEED,
    isProDefault: true,
  },
  [EffectType.CHORUS]: {
    titleKey: "effect_chorus",
    category: EffectCategory.MODULATION,
    isProDefault: true,
  },
  [EffectType.FLANGER]: {
    titleKey: "effect_flanger",
    category: EffectCategory.MODULATION,
    isProDefault: true,
  },
  [EffectType.PHASER]: {
    titleKey: "effect_phaser",
    category: EffectCategory.MODULATION,
    isProDefault: true,
  },
  [EffectType.DISTORTION]: {
    titleKey: "effect_distortion",
    category: EffectCategory.CHARACTER_DYNAMICS,
    isProDefault: true,
  },
  [EffectType.COMPRESSOR]: {
    titleKey: "effect_compressor",
    category: EffectCategory.CHARACTER_DYNAMICS,
    isProDefault: true,
  },
  [EffectType.ECHO_REVERB]: {
    titleKey: "effect_echo_reverb",
    category: EffectCategory.TIME_SPACE,
    isProDefault: false,
  },
  [EffectType.LIMITER]: {
    titleKey: "effect_limiter",
    category: EffectCategory.CHARACTER_DYNAMICS,
    isProDefault: true,
  },
};

export interface EchoReverbParams {
  echoStrength: number;
  echoDepth: number;
  delayTimeMs: number;
  feedback: number;
  reverbSize: number;
  reverbDecay: number;
  preDelayMs: number;
  wetDryMix: number;
  stereoWidth: number;
  earlyReflections: number;
  lateReflections: number;
  damping: number;
  diffusion: number;
}

export interface EqualizerParams {
  band60HzDb: number;
  band230HzDb: number;
  band910HzDb: number;
  band3600HzDb: number;
  band14000HzDb: number;
}

export interface BassBoostParams {
  gainDb: number;
  frequencyHz: number;
}

export interface TrebleParams {
  gainDb: number;
  frequencyHz: number;
}

export interface PitchShiftParams {
  semitones: number;
  wetMix: number;
}

export interface TimeStretchParams {
  tempoRatio: number;
}

export interface ChorusParams {
  rateHz: number;
  depthMs: number;
  mix: number;
}

export interface FlangerParams {
  rateHz: number;
  depthMs: number;
  feedback: number;
  mix: number;
}

export interface PhaserParams {
  rateHz: number;
  depth: number;
  feedback: number;
  mix: number;
}

export type DistortionMode = "SOFT_CLIP" | "HARD_CLIP" | "OVERDRIVE";

export interface DistortionParams {
  drive: number;
  toneHz: number;
  mix: number;
  mode: DistortionMode;
}

export interface CompressorParams {
  thresholdDb: number;
  ratio: number;
  attackMs: number;
  releaseMs: number;
  makeupGainDb: number;
}

export interface LimiterParams {
  ceilingDb: number;
  releaseMs: number;
}

export interface NoiseReductionParams {
  thresholdDb: number;
  reductionDb: number;
  highPassHz: number;
  spectralStrength: number;
  deEssAmount: number;
  clarityBoostDb: number;
  spectralCleanerEnabled: boolean;
}

export interface EffectSlot {
  type: EffectType;
  enabled: boolean;
}

export const DEFAULT_CHAIN: EffectSlot[] = [
  { type: EffectType.NOISE_REDUCTION, enabled: true },
  { type: EffectType.EQUALIZER, enabled: false },
  { type: EffectType.BASS_BOOST, enabled: false },
  { type: EffectType.TREBLE, enabled: false },
  { type: EffectType.PITCH_SHIFT, enabled: false },
  { type: EffectType.TIME_STRETCH, enabled: false },
  { type: EffectType.CHORUS, enabled: false },
  { type: EffectType.FLANGER, enabled: false },
  { type: EffectType.PHASER, enabled: false },
  { type: EffectType.DISTORTION, enabled: false },
  { type: EffectType.COMPRESSOR, enabled: false },
  { type: EffectType.ECHO_REVERB, enabled: false },
  { type: EffectType.LIMITER, enabled: true },
];

export interface DspConfig {
  bypassAll: boolean;
  masterGainDb: number;
  chain: EffectSlot[];
  echoReverb: EchoReverbParams;
  equalizer: EqualizerParams;
  bassBoost: BassBoostParams;
  treble: TrebleParams;
  pitchShift: PitchShiftParams;
  timeStretch: TimeStretchParams;
  chorus: ChorusParams;
  flanger: FlangerParams;
  phaser: PhaserParams;
  distortion: DistortionParams;
  compressor: CompressorParams;
  limiter: LimiterParams;
  noiseReduction: NoiseReductionParams;
}

export function createDefaultDspConfig(): DspConfig {
  return {
    bypassAll: false,
    masterGainDb: 0,
    chain: DEFAULT_CHAIN.map((s) => ({ ...s })),
    echoReverb: {
      echoStrength: 0.4,
      echoDepth: 0.45,
      delayTimeMs: 280,
      feedback: 0.35,
      reverbSize: 0.52,
      reverbDecay: 0.48,
      preDelayMs: 22,
      wetDryMix: 0.38,
      stereoWidth: 0.65,
      earlyReflections: 0.42,
      lateReflections: 0.55,
      damping: 0.38,
      diffusion: 0.72,
    },
    equalizer: {
      band60HzDb: 0,
      band230HzDb: 0,
      band910HzDb: 0,
      band3600HzDb: 0,
      band14000HzDb: 0,
    },
    bassBoost: { gainDb: 0, frequencyHz: 95 },
    treble: { gainDb: 0, frequencyHz: 6500 },
    pitchShift: { semitones: 0, wetMix: 1 },
    timeStretch: { tempoRatio: 1 },
    chorus: { rateHz: 1.2, depthMs: 6.5, mix: 0.35 },
    flanger: { rateHz: 0.45, depthMs: 3, feedback: 0.4, mix: 0.35 },
    phaser: { rateHz: 0.6, depth: 0.7, feedback: 0.35, mix: 0.4 },
    distortion: { drive: 2.5, toneHz: 4200, mix: 0.4, mode: "OVERDRIVE" },
    compressor: {
      thresholdDb: -18,
      ratio: 3.5,
      attackMs: 12,
      releaseMs: 140,
      makeupGainDb: 2.5,
    },
    limiter: { ceilingDb: -0.8, releaseMs: 80 },
    noiseReduction: {
      thresholdDb: -45,
      reductionDb: 18,
      highPassHz: 85,
      spectralStrength: 0.68,
      deEssAmount: 0.35,
      clarityBoostDb: 2.5,
      spectralCleanerEnabled: true,
    },
  };
}

export function normalizeDspConfig(raw?: Partial<DspConfig> | null): DspConfig {
  const def = createDefaultDspConfig();
  if (!raw) return def;
  const parsedChain = Array.isArray(raw.chain)
    ? raw.chain.filter((s) => Object.values(EffectType).includes(s.type))
    : [];
  const existingTypes = new Set(parsedChain.map((s) => s.type));
  const missingSlots = DEFAULT_CHAIN.filter((s) => !existingTypes.has(s.type));
  return {
    bypassAll: raw.bypassAll ?? def.bypassAll,
    masterGainDb: raw.masterGainDb ?? def.masterGainDb,
    chain: [...parsedChain, ...missingSlots],
    echoReverb: { ...def.echoReverb, ...(raw.echoReverb ?? {}) },
    equalizer: { ...def.equalizer, ...(raw.equalizer ?? {}) },
    bassBoost: { ...def.bassBoost, ...(raw.bassBoost ?? {}) },
    treble: { ...def.treble, ...(raw.treble ?? {}) },
    pitchShift: { ...def.pitchShift, ...(raw.pitchShift ?? {}) },
    timeStretch: { ...def.timeStretch, ...(raw.timeStretch ?? {}) },
    chorus: { ...def.chorus, ...(raw.chorus ?? {}) },
    flanger: { ...def.flanger, ...(raw.flanger ?? {}) },
    phaser: { ...def.phaser, ...(raw.phaser ?? {}) },
    distortion: { ...def.distortion, ...(raw.distortion ?? {}) },
    compressor: { ...def.compressor, ...(raw.compressor ?? {}) },
    limiter: { ...def.limiter, ...(raw.limiter ?? {}) },
    noiseReduction: { ...def.noiseReduction, ...(raw.noiseReduction ?? {}) },
  };
}

export interface StudioPreset {
  id: number;
  name: string;
  description: string;
  isBuiltIn: boolean;
  config: DspConfig;
}

export interface StereoAudioBuffer {
  left: Float32Array;
  right: Float32Array;
  sampleRate: number;
}

export enum EchoPadAxisMode {
  SPACE = "SPACE",
  DELAY = "DELAY",
  WIDTH = "WIDTH",
  DAMPING = "DAMPING",
}

export enum EchoDetailGroup {
  CORE = "CORE",
  REFLECTIONS = "REFLECTIONS",
  SPACE = "SPACE",
}

export enum AiToolType {
  TRANSCRIBE = "TRANSCRIBE",
  SMART_MASTER = "SMART_MASTER",
  SEARCH = "SEARCH",
}

export type WorkspaceDestination =
  | { screen: "HOME" }
  | { screen: "RECORD" }
  | { screen: "VOICE_PURIFIER" }
  | { screen: "ECHO_SPACE" }
  | { screen: "ECHO_DETAIL"; group: EchoDetailGroup }
  | { screen: "EFFECTS_CHAIN" }
  | { screen: "EFFECT_DETAIL"; effect: EffectType }
  | { screen: "EDITOR" }
  | { screen: "PRESETS" }
  | { screen: "PROJECTS_EXPORT" }
  | { screen: "AI_STUDIO" }
  | { screen: "AI_TOOL_DETAIL"; tool: AiToolType }
  | { screen: "TTS_STUDIO" }
  | { screen: "VOICE_TRANSFORMER" }
  | { screen: "SETTINGS" };

export enum ExportAudioFormat {
  MP3 = "MP3",
  WAV = "WAV",
  M4A = "M4A",
  OGG = "OGG",
  FLAC = "FLAC",
}

export const EXPORT_FORMAT_META: Record<
  ExportAudioFormat,
  { displayName: string; subtitle: string; extension: string; mimeType: string }
> = {
  [ExportAudioFormat.MP3]: {
    displayName: "MP3",
    subtitle: "صيغة مضغوطة شائعة عالية التوافق (192 kbps)",
    extension: "mp3",
    mimeType: "audio/mpeg",
  },
  [ExportAudioFormat.WAV]: {
    displayName: "WAV",
    subtitle: "جودة استوديو غير مضغوطة (PCM 16-bit)",
    extension: "wav",
    mimeType: "audio/wav",
  },
  [ExportAudioFormat.M4A]: {
    displayName: "M4A",
    subtitle: "ترميز ويب عالي الكفاءة (AAC / Audio Container)",
    extension: "m4a",
    mimeType: "audio/mp4",
  },
  [ExportAudioFormat.OGG]: {
    displayName: "OGG",
    subtitle: "صيغة مفتوحة مضغوطة عالية الوضوح (Opus / WebM-Ogg)",
    extension: "ogg",
    mimeType: "audio/ogg",
  },
  [ExportAudioFormat.FLAC]: {
    displayName: "FLAC",
    subtitle: "حفظ نقي بدون فقدان الجودة (Lossless)",
    extension: "flac",
    mimeType: "audio/flac",
  },
};

export interface StudioProject {
  id: number;
  name: string;
  durationMs: number;
  sampleRate: number;
  config: DspConfig;
  updatedAt: number;
  hasAudio: boolean;
}

export interface AudioRecordingEntity {
  id: number;
  title: string;
  timestamp: number;
  durationMs: number;
  sampleRate: number;
  sourceType: "RECORDING" | "IMPORTED" | "TTS";
}

export interface VoicePurifyPreset {
  id: string;
  titleAr: string;
  titleEn: string;
  subtitleAr: string;
  subtitleEn: string;
  highPassHz: number;
  thresholdDb: number;
  reductionDb: number;
  spectralStrength: number;
  deEssAmount: number;
  clarityBoostDb: number;
  spectralCleanerEnabled: boolean;
}

export const VOICE_PURIFY_PRESETS: VoicePurifyPreset[] = [
  {
    id: "studio_clean",
    titleAr: "نقاء استوديو ناعم",
    titleEn: "Soft Studio Clean",
    subtitleAr: "تنقية متوازنة تحفظ دفء الصوت وطبيعته",
    subtitleEn: "Balanced noise removal preserving natural vocal warmth",
    highPassHz: 85,
    thresholdDb: -46,
    reductionDb: 18,
    spectralStrength: 0.65,
    deEssAmount: 0.35,
    clarityBoostDb: 2.5,
    spectralCleanerEnabled: true,
  },
  {
    id: "deep_isolate",
    titleAr: "عزل فائق للضوضاء",
    titleEn: "Deep Noise Isolate",
    subtitleAr: "قمع قوي لضجيج المكيف، المروحة، والشارع",
    subtitleEn: "Strong attenuation of AC hum, fans, and street noise",
    highPassHz: 115,
    thresholdDb: -38,
    reductionDb: 28,
    spectralStrength: 0.88,
    deEssAmount: 0.45,
    clarityBoostDb: 3.5,
    spectralCleanerEnabled: true,
  },
  {
    id: "podcast_crisp",
    titleAr: "بودكاست ووضوح الحوار",
    titleEn: "Podcast Vocal Crisp",
    subtitleAr: "بروز فوري للحروف وإزالة الصدى القريب والصفير",
    subtitleEn: "Forward vocal presence with tight de-essing",
    highPassHz: 95,
    thresholdDb: -43,
    reductionDb: 21,
    spectralStrength: 0.72,
    deEssAmount: 0.55,
    clarityBoostDb: 4.5,
    spectralCleanerEnabled: true,
  },
  {
    id: "gentle_denoise",
    titleAr: "تصفية خفيفة (حفظ الجو)",
    titleEn: "Gentle Ambient Safe",
    subtitleAr: "تخفيف خفيف للهسهسة بدون التأثير على الخلفية الهادئة",
    subtitleEn: "Subtle hiss taming without altering quiet ambience",
    highPassHz: 65,
    thresholdDb: -54,
    reductionDb: 12,
    spectralStrength: 0.42,
    deEssAmount: 0.2,
    clarityBoostDb: 1,
    spectralCleanerEnabled: true,
  },
];

export interface VoiceCharacterPreset {
  id: string;
  titleKey: string;
  descKey: string;
  pitchSemitones: number;
  formantShift: number;
  warmthDb: number;
  presenceDb: number;
}

export const VOICE_CHARACTER_PRESETS: VoiceCharacterPreset[] = [
  {
    id: "male_luxury",
    titleKey: "voice_char_male_luxury",
    descKey: "voice_char_male_luxury_desc",
    pitchSemitones: -3.2,
    formantShift: 0.85,
    warmthDb: 4.0,
    presenceDb: 2.2,
  },
  {
    id: "male_calm",
    titleKey: "voice_char_male_calm",
    descKey: "voice_char_male_calm_desc",
    pitchSemitones: -1.8,
    formantShift: 0.91,
    warmthDb: 2.5,
    presenceDb: 1.5,
  },
  {
    id: "female_soft",
    titleKey: "voice_char_female_soft",
    descKey: "voice_char_female_soft_desc",
    pitchSemitones: 3.4,
    formantShift: 1.15,
    warmthDb: 1.2,
    presenceDb: 2.0,
  },
  {
    id: "female_balanced",
    titleKey: "voice_char_female_balanced",
    descKey: "voice_char_female_balanced_desc",
    pitchSemitones: 2.2,
    formantShift: 1.1,
    warmthDb: 0.5,
    presenceDb: 3.0,
  },
  {
    id: "child",
    titleKey: "voice_char_child",
    descKey: "voice_char_child_desc",
    pitchSemitones: 6.2,
    formantShift: 1.27,
    warmthDb: -1.0,
    presenceDb: 2.8,
  },
];

export interface TtsDialect {
  id: string;
  titleKey: string;
  localeTag: string;
  fallbackLocaleTag: string;
  sampleText: string;
  defaultPitch: number;
  defaultRate: number;
}

export const TTS_DIALECTS: TtsDialect[] = [
  {
    id: "iraqi",
    titleKey: "tts_dialect_iraqi",
    localeTag: "ar-IQ",
    fallbackLocaleTag: "ar-SA",
    sampleText: "هلا بيكم باستوديو فويس برو، شلونكم اليوم؟ خلينا نسجّل صوت نقي ومرتّب.",
    defaultPitch: 0.98,
    defaultRate: 0.95,
  },
  {
    id: "saudi",
    titleKey: "tts_dialect_saudi",
    localeTag: "ar-SA",
    fallbackLocaleTag: "ar",
    sampleText: "حياكم الله في استوديو فويس برو، تجربة صوتية احترافية بجودة عالية جداً.",
    defaultPitch: 1.0,
    defaultRate: 0.96,
  },
  {
    id: "jordanian",
    titleKey: "tts_dialect_jordanian",
    localeTag: "ar-JO",
    fallbackLocaleTag: "ar-SA",
    sampleText: "أهلاً وسهلاً فيكم، يسعد أوقاتكم بكل خير من استوديو المعالجة الصوتية.",
    defaultPitch: 1.0,
    defaultRate: 0.97,
  },
  {
    id: "syrian",
    titleKey: "tts_dialect_syrian",
    localeTag: "ar-SY",
    fallbackLocaleTag: "ar-LB",
    sampleText: "يا مية أهلاً وسهلاً فيكم، نورتوا الاستوديو وصوتكم طالع بيشهي وواضح.",
    defaultPitch: 1.02,
    defaultRate: 0.96,
  },
  {
    id: "moroccan",
    titleKey: "tts_dialect_moroccan",
    localeTag: "ar-MA",
    fallbackLocaleTag: "ar-SA",
    sampleText: "مرحبا بكم كاملين فهاد الاستوديو الزوين، كلشي واجد باش تخرجو صوت نقي بزاف.",
    defaultPitch: 0.99,
    defaultRate: 0.95,
  },
  {
    id: "algerian",
    titleKey: "tts_dialect_algerian",
    localeTag: "ar-DZ",
    fallbackLocaleTag: "ar-SA",
    sampleText: "صحا وعافية ليكم كامل، رانا واجدين باش نسجلو أحسن مقطع صوتي بصوت صافي.",
    defaultPitch: 0.98,
    defaultRate: 0.96,
  },
  {
    id: "tunisian",
    titleKey: "tts_dialect_tunisian",
    localeTag: "ar-TN",
    fallbackLocaleTag: "ar-SA",
    sampleText: "عسلامة ومرحبا بيكم الناس الكل، اليوم باش نخدمو على صوت مزيان برشا.",
    defaultPitch: 1.01,
    defaultRate: 0.96,
  },
  {
    id: "en_us",
    titleKey: "tts_dialect_english_us",
    localeTag: "en-US",
    fallbackLocaleTag: "en",
    sampleText: "Welcome to Voice Pro Studio. Crystal clear acoustics ready for your next mix.",
    defaultPitch: 1.0,
    defaultRate: 1.0,
  },
  {
    id: "persian",
    titleKey: "tts_dialect_persian",
    localeTag: "fa-IR",
    fallbackLocaleTag: "fa",
    sampleText: "سلام و خوش آمدید به استودیوی ویس پرو؛ صدای شما اکنون با شفافیت کامل آماده است.",
    defaultPitch: 1.0,
    defaultRate: 0.95,
  },
  {
    id: "turkish",
    titleKey: "tts_dialect_turkish",
    localeTag: "tr-TR",
    fallbackLocaleTag: "tr",
    sampleText: "Voice Pro Stüdyo'ya hoş geldiniz. Sesiniz şimdi kristal netliğinde işlenmeye hazır.",
    defaultPitch: 1.0,
    defaultRate: 0.98,
  },
];

export interface SmartMasterAnalysis {
  rmsDb: number;
  peakDb: number;
  crestFactorDb: number;
  noiseFloorDb: number;
  lowEnergyRatio: number;
  midEnergyRatio: number;
  highEnergyRatio: number;
  detectedProfile: string;
  appliedActions: string[];
  optimizedConfig: DspConfig;
}

export interface SearchSourceLink {
  title: string;
  uri: string;
  domain: string;
}

export interface AiStreamState {
  isLoading: boolean;
  outputText: string;
  errorMessage: string | null;
  searchQueries: string[];
  sources: SearchSourceLink[];
}

export interface TtsStudioUiState {
  isPreviewing: boolean;
  isGenerating: boolean;
  errorMessage: string | null;
}

export type AuthSession =
  | { type: "SIGNED_OUT" }
  | { type: "GUEST" }
  | { type: "SIGNED_IN"; uid: string; displayName: string; email: string };
