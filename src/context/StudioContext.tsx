import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import {
  AiStreamState,
  AppLanguage,
  AudioRecordingEntity,
  AuthSession,
  DspConfig,
  EchoDetailGroup,
  EchoPadAxisMode,
  EchoReverbParams,
  EffectType,
  ExportAudioFormat,
  SmartMasterAnalysis,
  StereoAudioBuffer,
  StudioPreset,
  StudioProject,
  TtsDialect,
  TtsStudioUiState,
  VoicePurifyPreset,
  WorkspaceDestination,
  createDefaultDspConfig,
} from "../types/studio";
import { BUILT_IN_PRESETS } from "../data/builtInPresets";
import {
  applyFadeIn,
  applyFadeOut,
  applyGainDb,
  computeWaveformPeaks,
  cutSelection,
  getDurationMs,
  getFrameCount,
  mergeClips,
  mixTracks,
  normalizeAudio,
  trimToSelection,
} from "../audio/audioEditorOps";
import { clamp } from "../audio/biquad";
import { renderOfflineDsp } from "../audio/dspPipeline";
import { purifyBufferOffline } from "../audio/voicePurifier";
import { transformVoiceOffline as runTransformVoiceOffline } from "../audio/formantTransformer";
import { analyzeAndOptimizeAudio } from "../audio/smartAutoMaster";
import {
  base64ToUint8,
  decodeAudioArrayBuffer,
  decodeAudioFile,
  encodeCompactMonoWavBase64,
} from "../audio/wavCodec";
import { exportAudioBlob, triggerDownloadBlob } from "../audio/audioExporter";
import {
  PlaybackTelemetry,
  RecorderTelemetry,
  StudioAudioPlayer,
  StudioAudioRecorder,
} from "../audio/liveAudioEngine";
import {
  deleteCustomPresetRecord,
  deleteProjectRecord,
  deleteRecordingRecord,
  indexAudioRecording,
  listCustomPresets,
  listProjects,
  listRecordings,
  loadAuthSession,
  loadAutoLoopPref,
  loadProjectAudioBuffer,
  loadRecordingBuffer,
  loadSavedLanguage,
  registerLocalEmail,
  saveAuthSession,
  saveAutoLoopPref,
  saveCustomPresetRecord,
  saveLanguagePref,
  saveProjectRecord,
  signInLocalEmail,
} from "../storage/studioDb";
import { t } from "../i18n/translations";

function mapNormalized(tVal: number, min: number, max: number): number {
  return clamp(min + clamp(tVal, 0, 1) * (max - min), Math.min(min, max), Math.max(min, max));
}

function unmapNormalized(v: number, min: number, max: number): number {
  return clamp((v - min) / (max - min), 0, 1);
}

interface StudioContextValue {
  // Language & Auth
  language: AppLanguage;
  setLanguage: (lang: AppLanguage) => void;
  authSession: AuthSession;
  continueAsGuest: () => void;
  requestSignIn: () => void;
  signOut: () => void;
  signInWithEmail: (email: string, password: string) => string | null;
  createAccountWithEmail: (name: string, email: string, password: string) => string | null;

  // Navigation
  destination: WorkspaceDestination;
  navigateTo: (dest: WorkspaceDestination) => void;
  navigateBack: () => void;

  // Audio & DSP State
  hasAudio: boolean;
  durationMs: number;
  waveformPeaks: number[];
  dspConfig: DspConfig;
  activePresetId: number | null;
  isSimpleEchoMode: boolean;
  setSimpleEchoMode: (simple: boolean) => void;
  echoPadAxisMode: EchoPadAxisMode;
  setEchoPadAxisMode: (mode: EchoPadAxisMode) => void;
  isHoldBypassing: boolean;
  setHoldBypass: (holding: boolean) => void;
  toggleBypass: () => void;
  updateDspConfig: (updater: (prev: DspConfig) => DspConfig) => void;
  resetAllEffects: () => void;
  toggleEffectEnabled: (type: EffectType, enabled: boolean) => void;
  moveEffectInChain: (fromIdx: number, toIdx: number) => void;
  updateEchoReverbParams: (updater: (prev: EchoReverbParams) => EchoReverbParams) => void;
  updateEchoReverbFromPad: (x: number, y: number) => void;
  getEchoPadAxes: () => { x: number; y: number };

  // Transport & Telemetry
  playback: PlaybackTelemetry;
  recorder: RecorderTelemetry;
  micPermissionError: string | null;
  togglePlayPause: () => void;
  stopPlayback: () => void;
  seekToFraction: (frac: number) => void;
  loopSelection: boolean;
  toggleLoopSelection: () => void;
  autoLoopOnPlayback: boolean;
  setAutoLoopOnPlayback: (enabled: boolean) => void;

  // Recording & Import
  startRecording: () => Promise<void>;
  pauseRecording: () => void;
  resumeRecording: () => void;
  finishRecording: () => Promise<void>;
  discardRecording: () => void;
  importAudioFile: (file: File) => Promise<void>;

  // Purifier
  applyVoicePurifyPreset: (preset: VoicePurifyPreset) => void;
  purifyAudioOffline: () => Promise<void>;

  // Editor
  trimStart: number;
  trimEnd: number;
  updateTrimRange: (start: number, end: number) => void;
  resetSelection: () => void;
  clipGainDb: number;
  setClipGainDb: (db: number) => void;
  bakeClipGainDb: (db: number) => void;
  trimKeepSelection: () => void;
  cutSelectedRange: () => void;
  normalizeAudioBuffer: () => void;
  applyFadeInSeconds: (seconds: number) => void;
  applyFadeOutSeconds: (seconds: number) => void;
  mixBgName: string | null;
  mixBgDurationMs: number;
  loadMixBackgroundTrack: (file: File) => Promise<void>;
  clearMixBackgroundTrack: () => void;
  mixWithBackgroundTrack: (mainGain: number, bgGain: number) => void;
  appendAudioFileToCurrent: (file: File) => Promise<void>;
  appendSavedProjectToCurrent: (project: StudioProject) => Promise<void>;
  canUndoEdit: boolean;
  canRedoEdit: boolean;
  hasUnsavedChanges: boolean;
  undoLastEdit: () => void;
  redoLastEdit: () => void;
  saveEditsPermanent: () => Promise<void>;

  // Projects, Recordings, Presets, Export
  projects: StudioProject[];
  recordings: AudioRecordingEntity[];
  allPresets: StudioPreset[];
  activeProjectId: number;
  activeProjectName: string;
  setActiveProjectName: (name: string) => void;
  newProject: () => void;
  saveCurrentProject: () => Promise<void>;
  loadProject: (project: StudioProject) => Promise<void>;
  deleteProject: (project: StudioProject) => Promise<void>;
  loadRecordingIntoStudio: (rec: AudioRecordingEntity) => Promise<void>;
  deleteRecording: (rec: AudioRecordingEntity) => Promise<void>;
  applyPreset: (preset: StudioPreset) => void;
  saveCustomPreset: (name: string, description: string) => Promise<void>;
  deleteCustomPreset: (presetId: number) => Promise<void>;
  exportRenderedAudio: (format: ExportAudioFormat, fileName: string) => Promise<void>;

  // AI Studio
  transcriptionState: AiStreamState;
  transcribeActiveAudio: () => Promise<void>;
  clearTranscription: () => void;
  smartMasterState: SmartMasterAnalysis | null;
  runSmartAutoMaster: () => Promise<void>;
  clearSmartMasterAnalysis: () => void;
  searchAssistantState: AiStreamState;
  askSearchAssistant: (query: string) => Promise<void>;

  // TTS & Formant Voice Transformer
  ttsUiState: TtsStudioUiState;
  previewTtsSpeech: (
    text: StringLike,
    dialect: TtsDialect,
    speechRate: number,
    pitch: number
  ) => void;
  stopTtsPreview: () => void;
  generateTtsToStudio: (
    text: string,
    dialect: TtsDialect,
    speechRate: number,
    pitch: number,
    appendToExisting: boolean
  ) => Promise<void>;
  transformVoiceOffline: (
    pitchSemitones: number,
    formantShift: number,
    warmthDb: number,
    presenceDb: number,
    mix: number,
    characterLabel: string
  ) => Promise<void>;

  // UI feedback
  statusBanner: string | null;
  clearBanner: () => void;
  isBusy: boolean;
  exportProgress: number | null;
  purifyProgress: number | null;
  requestNavigateToEchoDetail: (group: EchoDetailGroup) => void;
}

type StringLike = string;

const StudioContext = createContext<StudioContextValue | null>(null);

export const StudioProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [language, setLanguageState] = useState<AppLanguage>(() => loadSavedLanguage());
  const [authSession, setAuthSession] = useState<AuthSession>(() => loadAuthSession());
  const [navStack, setNavStack] = useState<WorkspaceDestination[]>([{ screen: "HOME" }]);

  const [dspConfig, setDspConfig] = useState<DspConfig>(() => createDefaultDspConfig());
  const [activePresetId, setActivePresetId] = useState<number | null>(null);
  const [isSimpleEchoMode, setSimpleEchoMode] = useState(true);
  const [echoPadAxisMode, setEchoPadAxisMode] = useState<EchoPadAxisMode>(EchoPadAxisMode.SPACE);
  const [isHoldBypassing, setIsHoldBypassing] = useState(false);

  const [hasAudio, setHasAudio] = useState(false);
  const [durationMs, setDurationMs] = useState(0);
  const [waveformPeaks, setWaveformPeaks] = useState<number[]>([]);
  const [trimStart, setTrimStart] = useState(0);
  const [trimEnd, setTrimEnd] = useState(1);
  const [clipGainDb, setClipGainDb] = useState(0);
  const [loopSelection, setLoopSelection] = useState(false);
  const [autoLoopOnPlayback, setAutoLoopState] = useState(() => loadAutoLoopPref());
  const [canUndoEdit, setCanUndoEdit] = useState(false);
  const [canRedoEdit, setCanRedoEdit] = useState(false);
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);

  const [projects, setProjects] = useState<StudioProject[]>([]);
  const [recordings, setRecordings] = useState<AudioRecordingEntity[]>([]);
  const [customPresets, setCustomPresets] = useState<StudioPreset[]>([]);
  const [activeProjectId, setActiveProjectId] = useState(0);
  const [activeProjectName, setActiveProjectName] = useState(() =>
    t(loadSavedLanguage(), "untitled_project")
  );

  const [mixBgName, setMixBgName] = useState<string | null>(null);
  const [mixBgDurationMs, setMixBgDurationMs] = useState(0);
  const mixBgBufferRef = useRef<StereoAudioBuffer | null>(null);

  const [statusBanner, setStatusBanner] = useState<string | null>(null);
  const [isBusy, setIsBusy] = useState(false);
  const [exportProgress, setExportProgress] = useState<number | null>(null);
  const [purifyProgress, setPurifyProgress] = useState<number | null>(null);
  const [micPermissionError, setMicPermissionError] = useState<string | null>(null);

  const [playback, setPlayback] = useState<PlaybackTelemetry>({
    isPlaying: false,
    positionMs: 0,
    durationMs: 0,
    outputLevel: 0,
    gainReductionDb: 0,
    noiseSuppressionDb: 0,
    waveformBars: new Array(48).fill(0.06),
  });

  const [recorder, setRecorder] = useState<RecorderTelemetry>({
    isRecording: false,
    isPaused: false,
    elapsedMs: 0,
    micLevel: 0,
    waveformBars: new Array(48).fill(0.05),
  });

  const [transcriptionState, setTranscriptionState] = useState<AiStreamState>({
    isLoading: false,
    outputText: "",
    errorMessage: null,
    searchQueries: [],
    sources: [],
  });
  const [smartMasterState, setSmartMasterState] = useState<SmartMasterAnalysis | null>(null);
  const [searchAssistantState, setSearchAssistantState] = useState<AiStreamState>({
    isLoading: false,
    outputText: "",
    errorMessage: null,
    searchQueries: [],
    sources: [],
  });
  const [ttsUiState, setTtsUiState] = useState<TtsStudioUiState>({
    isPreviewing: false,
    isGenerating: false,
    errorMessage: null,
  });

  const activeBufferRef = useRef<StereoAudioBuffer | null>(null);
  const undoStackRef = useRef<StereoAudioBuffer[]>([]);
  const redoStackRef = useRef<StereoAudioBuffer[]>([]);
  const dspConfigRef = useRef(dspConfig);
  dspConfigRef.current = dspConfig;

  const playerRef = useRef<StudioAudioPlayer | null>(null);
  const recorderRef = useRef<StudioAudioRecorder | null>(null);

  useEffect(() => {
    const rtl = language === "ar" || language === "fa";
    document.documentElement.lang = language;
    document.documentElement.dir = rtl ? "rtl" : "ltr";
  }, [language]);

  useEffect(() => {
    if (!statusBanner) return;
    const id = window.setTimeout(() => setStatusBanner(null), 4200);
    return () => window.clearTimeout(id);
  }, [statusBanner]);

  const refreshLists = useCallback(async () => {
    try {
      const [projs, recs, cust] = await Promise.all([
        listProjects(),
        listRecordings(),
        listCustomPresets(),
      ]);
      setProjects(projs);
      setRecordings(recs);
      setCustomPresets(cust);
    } catch {
      // ignore initial IDB hiccup
    }
  }, []);

  useEffect(() => {
    refreshLists();
    const player = new StudioAudioPlayer(dspConfigRef.current, (tel) => setPlayback(tel));
    const rec = new StudioAudioRecorder((tel) => setRecorder(tel));
    playerRef.current = player;
    recorderRef.current = rec;
    return () => {
      player.dispose();
      rec.discard();
      window.speechSynthesis?.cancel();
    };
  }, [refreshLists]);

  useEffect(() => {
    const effectiveCfg = isHoldBypassing ? { ...dspConfig, bypassAll: true } : dspConfig;
    playerRef.current?.updateConfig(effectiveCfg);
  }, [dspConfig, isHoldBypassing]);

  useEffect(() => {
    const shouldLoop = loopSelection || autoLoopOnPlayback;
    playerRef.current?.setLoop(shouldLoop, trimStart, trimEnd);
  }, [loopSelection, autoLoopOnPlayback, trimStart, trimEnd]);

  const postBanner = useCallback((msg: string) => setStatusBanner(msg), []);

  const setLanguage = useCallback((lang: AppLanguage) => {
    saveLanguagePref(lang);
    setLanguageState(lang);
  }, []);

  const continueAsGuest = useCallback(() => {
    const s: AuthSession = { type: "GUEST" };
    saveAuthSession(s);
    setAuthSession(s);
  }, []);

  const requestSignIn = useCallback(() => {
    const s: AuthSession = { type: "SIGNED_OUT" };
    saveAuthSession(s);
    setAuthSession(s);
  }, []);

  const signOut = useCallback(() => {
    const s: AuthSession = { type: "SIGNED_OUT" };
    saveAuthSession(s);
    setAuthSession(s);
  }, []);

  const signInWithEmail = useCallback((email: string, password: string): string | null => {
    const res = signInLocalEmail(email, password);
    if (res.ok) {
      setAuthSession(res.session);
      return null;
    }
    return res.error;
  }, []);

  const createAccountWithEmail = useCallback(
    (name: string, email: string, password: string): string | null => {
      const res = registerLocalEmail(name, email, password);
      if (res.ok) {
        setAuthSession(res.session);
        return null;
      }
      return res.error;
    },
    []
  );

  const destination = navStack[navStack.length - 1];

  const navigateTo = useCallback((dest: WorkspaceDestination) => {
    setNavStack((prev) => {
      const top = prev[prev.length - 1];
      if (JSON.stringify(top) === JSON.stringify(dest)) return prev;
      return [...prev, dest];
    });
  }, []);

  const navigateBack = useCallback(() => {
    setNavStack((prev) => (prev.length > 1 ? prev.slice(0, -1) : prev));
  }, []);

  const setActiveBuffer = useCallback(
    (buf: StereoAudioBuffer, recordUndo: boolean) => {
      if (recordUndo && activeBufferRef.current) {
        if (undoStackRef.current.length >= 12) undoStackRef.current.shift();
        undoStackRef.current.push(activeBufferRef.current);
        redoStackRef.current = [];
      } else if (!recordUndo) {
        undoStackRef.current = [];
        redoStackRef.current = [];
      }
      activeBufferRef.current = buf;
      playerRef.current?.setBuffer(buf, true);
      setHasAudio(true);
      setDurationMs(getDurationMs(buf));
      setWaveformPeaks(computeWaveformPeaks(buf, 120));
      setTrimStart(0);
      setTrimEnd(1);
      setCanUndoEdit(undoStackRef.current.length > 0);
      setCanRedoEdit(redoStackRef.current.length > 0);
      setHasUnsavedChanges(recordUndo);
    },
    []
  );

  const updateDspConfig = useCallback((updater: (prev: DspConfig) => DspConfig) => {
    setDspConfig((prev) => updater(prev));
    setActivePresetId(null);
    setHasUnsavedChanges(true);
  }, []);

  const resetAllEffects = useCallback(() => {
    const def = createDefaultDspConfig();
    def.chain = def.chain.map((s) => ({ ...s, enabled: false }));
    setDspConfig(def);
    setActivePresetId(null);
    setHasUnsavedChanges(true);
    postBanner("تم إيقاف كافة المؤثرات وإرجاعها للإعداد الافتراضي");
  }, [postBanner]);

  const toggleEffectEnabled = useCallback((type: EffectType, enabled: boolean) => {
    setDspConfig((prev) => ({
      ...prev,
      bypassAll: false,
      chain: prev.chain.map((s) => (s.type === type ? { ...s, enabled } : s)),
    }));
    setActivePresetId(null);
    setHasUnsavedChanges(true);
  }, []);

  const moveEffectInChain = useCallback((fromIdx: number, toIdx: number) => {
    setDspConfig((prev) => {
      if (
        fromIdx < 0 ||
        toIdx < 0 ||
        fromIdx >= prev.chain.length ||
        toIdx >= prev.chain.length ||
        fromIdx === toIdx
      ) {
        return prev;
      }
      const next = [...prev.chain];
      const [moved] = next.splice(fromIdx, 1);
      next.splice(toIdx, 0, moved);
      return { ...prev, chain: next };
    });
    setActivePresetId(null);
    setHasUnsavedChanges(true);
  }, []);

  const updateEchoReverbParams = useCallback(
    (updater: (prev: EchoReverbParams) => EchoReverbParams) => {
      setDspConfig((prev) => ({
        ...prev,
        bypassAll: false,
        chain: prev.chain.map((s) =>
          s.type === EffectType.ECHO_REVERB ? { ...s, enabled: true } : s
        ),
        echoReverb: updater(prev.echoReverb),
      }));
      setActivePresetId(null);
      setHasUnsavedChanges(true);
    },
    []
  );

  const updateEchoReverbFromPad = useCallback(
    (rawX: number, rawY: number) => {
      const nx = clamp(rawX, 0, 1);
      const ny = clamp(rawY, 0, 1);
      updateEchoReverbParams((prev) => {
        switch (echoPadAxisMode) {
          case EchoPadAxisMode.SPACE:
            return {
              ...prev,
              echoStrength: nx,
              echoDepth: ny,
              reverbSize: mapNormalized(ny, 0.2, 0.96),
              reverbDecay: mapNormalized(ny, 0.15, 0.92),
              wetDryMix: mapNormalized((nx + ny) * 0.5, 0.08, 0.85),
            };
          case EchoPadAxisMode.DELAY:
            return {
              ...prev,
              delayTimeMs: mapNormalized(nx, 40, 1000),
              feedback: mapNormalized(ny, 0.05, 0.85),
            };
          case EchoPadAxisMode.WIDTH:
            return {
              ...prev,
              stereoWidth: nx,
              diffusion: mapNormalized(ny, 0.15, 0.96),
              earlyReflections: mapNormalized(ny, 0.15, 0.9),
            };
          case EchoPadAxisMode.DAMPING:
            return {
              ...prev,
              damping: nx,
              preDelayMs: mapNormalized(ny, 0, 140),
              lateReflections: mapNormalized(1 - nx * 0.35, 0.25, 0.9),
            };
        }
      });
    },
    [echoPadAxisMode, updateEchoReverbParams]
  );

  const getEchoPadAxes = useCallback((): { x: number; y: number } => {
    const e = dspConfig.echoReverb;
    switch (echoPadAxisMode) {
      case EchoPadAxisMode.SPACE:
        return { x: clamp(e.echoStrength, 0, 1), y: clamp(e.echoDepth, 0, 1) };
      case EchoPadAxisMode.DELAY:
        return {
          x: unmapNormalized(e.delayTimeMs, 40, 1000),
          y: unmapNormalized(e.feedback, 0.05, 0.85),
        };
      case EchoPadAxisMode.WIDTH:
        return {
          x: clamp(e.stereoWidth, 0, 1),
          y: unmapNormalized(e.diffusion, 0.15, 0.96),
        };
      case EchoPadAxisMode.DAMPING:
        return {
          x: clamp(e.damping, 0, 1),
          y: unmapNormalized(e.preDelayMs, 0, 140),
        };
    }
  }, [dspConfig.echoReverb, echoPadAxisMode]);

  const toggleBypass = useCallback(() => {
    setDspConfig((prev) => ({ ...prev, bypassAll: !prev.bypassAll }));
  }, []);

  const togglePlayPause = useCallback(() => {
    if (!activeBufferRef.current) {
      postBanner("سجّل أو استورد مقطعاً صوتياً أولاً");
      return;
    }
    if (playback.isPlaying) {
      playerRef.current?.pause();
    } else {
      playerRef.current?.play();
    }
  }, [playback.isPlaying, postBanner]);

  const stopPlayback = useCallback(() => {
    playerRef.current?.stop();
  }, []);

  const seekToFraction = useCallback((frac: number) => {
    playerRef.current?.seekToFraction(frac);
  }, []);

  const toggleLoopSelection = useCallback(() => {
    setLoopSelection((v) => !v);
  }, []);

  const setAutoLoopOnPlayback = useCallback((enabled: boolean) => {
    saveAutoLoopPref(enabled);
    setAutoLoopState(enabled);
  }, []);

  // Recording & Import
  const startRecording = useCallback(async () => {
    setMicPermissionError(null);
    playerRef.current?.stop();
    const ok = await recorderRef.current?.start();
    if (!ok) {
      setMicPermissionError(
        "تعذر الوصول إلى الميكروفون. يرجى منح صلاحية الميكروفون من المتصفح ثم المحاولة."
      );
    }
  }, []);

  const pauseRecording = useCallback(() => {
    recorderRef.current?.pause();
  }, []);

  const resumeRecording = useCallback(() => {
    recorderRef.current?.resume();
  }, []);

  const finishRecording = useCallback(async () => {
    const buf = recorderRef.current?.stopAndBuildBuffer();
    if (!buf || getFrameCount(buf) === 0) {
      postBanner("التسجيل قصير جداً أو لم يتم التقاط صوت");
      return;
    }
    setActiveBuffer(buf, false);
    const nowStr = new Date().toLocaleTimeString("en-GB", { hour12: false });
    await indexAudioRecording(`تسجيل ${nowStr}`, buf, "RECORDING");
    await refreshLists();
    postBanner("تم حفظ التسجيل في الاستوديو بنجاح");
  }, [postBanner, refreshLists, setActiveBuffer]);

  const discardRecording = useCallback(() => {
    recorderRef.current?.discard();
  }, []);

  const importAudioFile = useCallback(
    async (file: File) => {
      setIsBusy(true);
      try {
        playerRef.current?.stop();
        const buf = await decodeAudioFile(file);
        setActiveBuffer(buf, false);
        const title = file.name.replace(/\.[^.]+$/, "") || "ملف مستورد";
        if (activeProjectId === 0) setActiveProjectName(title);
        await indexAudioRecording(title, buf, "IMPORTED");
        await refreshLists();
        postBanner("تم استيراد الملف الصوتي وتحميل الموجات بنجاح");
      } catch {
        postBanner("تعذر فك ترميز الملف الصوتي المحدد");
      } finally {
        setIsBusy(false);
      }
    },
    [activeProjectId, postBanner, refreshLists, setActiveBuffer]
  );

  // Voice Purifier
  const applyVoicePurifyPreset = useCallback(
    (preset: VoicePurifyPreset) => {
      setDspConfig((prev) => ({
        ...prev,
        bypassAll: false,
        chain: prev.chain.map((s) =>
          s.type === EffectType.NOISE_REDUCTION ? { ...s, enabled: true } : s
        ),
        noiseReduction: {
          thresholdDb: preset.thresholdDb,
          reductionDb: preset.reductionDb,
          highPassHz: preset.highPassHz,
          spectralStrength: preset.spectralStrength,
          deEssAmount: preset.deEssAmount,
          clarityBoostDb: preset.clarityBoostDb,
          spectralCleanerEnabled: preset.spectralCleanerEnabled,
        },
      }));
      setActivePresetId(null);
      setHasUnsavedChanges(true);
      postBanner(`تم تطبيق وضع التنقية: ${preset.titleAr}`);
    },
    [postBanner]
  );

  const purifyAudioOffline = useCallback(async () => {
    const src = activeBufferRef.current;
    if (!src) {
      postBanner("سجّل أو استورد مقطعاً صوتياً أولاً لتنقيته");
      return;
    }
    setIsBusy(true);
    setPurifyProgress(0.02);
    try {
      const cleaned = await purifyBufferOffline(src, dspConfigRef.current.noiseReduction, (p) =>
        setPurifyProgress(p)
      );
      setActiveBuffer(cleaned, true);
      postBanner("تمت تصفية وعزل الضوضاء من المقطع وحفظ النسخة في التراجع");
    } finally {
      setIsBusy(false);
      setPurifyProgress(null);
    }
  }, [postBanner, setActiveBuffer]);

  // Editor Ops
  const updateTrimRange = useCallback((s: number, e: number) => {
    const cs = clamp(s, 0, 0.98);
    const ce = clamp(e, cs + 0.02, 1);
    setTrimStart(cs);
    setTrimEnd(ce);
  }, []);

  const resetSelection = useCallback(() => {
    setTrimStart(0);
    setTrimEnd(1);
    postBanner("تم تحديد المقطع بالكامل");
  }, [postBanner]);

  const bakeClipGainDb = useCallback(
    (db: number) => {
      const src = activeBufferRef.current;
      if (!src) return;
      const gained = applyGainDb(src, db);
      setActiveBuffer(gained, true);
      setClipGainDb(0);
      postBanner("تم تعديل شدة الصوت في المقطع بنجاح");
    },
    [postBanner, setActiveBuffer]
  );

  const trimKeepSelection = useCallback(() => {
    const src = activeBufferRef.current;
    if (!src) return;
    const trimmed = trimToSelection(src, trimStart, trimEnd);
    setActiveBuffer(trimmed, true);
    postBanner("تم اقتصاص المقطع والإبقاء على الجزء المحدد");
  }, [postBanner, setActiveBuffer, trimEnd, trimStart]);

  const cutSelectedRange = useCallback(() => {
    const src = activeBufferRef.current;
    if (!src) return;
    const cut = cutSelection(src, trimStart, trimEnd);
    setActiveBuffer(cut, true);
    postBanner("تم حذف الجزء المحدد من المقطع");
  }, [postBanner, setActiveBuffer, trimEnd, trimStart]);

  const normalizeAudioBuffer = useCallback(() => {
    const src = activeBufferRef.current;
    if (!src) return;
    const normed = normalizeAudio(src, -1);
    setActiveBuffer(normed, true);
    postBanner("تمت موازنة مستوى الصوت إلى -1 dBFS");
  }, [postBanner, setActiveBuffer]);

  const applyFadeInSeconds = useCallback(
    (seconds: number) => {
      const src = activeBufferRef.current;
      if (!src) {
        postBanner("قم بتسجيل أو استيراد صوت أولاً لتطبيق التدرج");
        return;
      }
      setActiveBuffer(applyFadeIn(src, seconds), true);
      postBanner(`تم تطبيق الظهور التدريجي (${seconds.toFixed(1)} ثانية)`);
    },
    [postBanner, setActiveBuffer]
  );

  const applyFadeOutSeconds = useCallback(
    (seconds: number) => {
      const src = activeBufferRef.current;
      if (!src) {
        postBanner("قم بتسجيل أو استيراد صوت أولاً لتطبيق التدرج");
        return;
      }
      setActiveBuffer(applyFadeOut(src, seconds), true);
      postBanner(`تم تطبيق الاختفاء التدريجي (${seconds.toFixed(1)} ثانية)`);
    },
    [postBanner, setActiveBuffer]
  );

  const loadMixBackgroundTrack = useCallback(
    async (file: File) => {
      setIsBusy(true);
      try {
        const bg = await decodeAudioFile(file);
        mixBgBufferRef.current = bg;
        setMixBgName(file.name);
        setMixBgDurationMs(getDurationMs(bg));
        postBanner("تم تحميل مسار الخلفية بنجاح، يمكنك الآن ضبط المستويات والمزج");
      } catch {
        postBanner("تعذر قراءة ملف الخلفية الصوتية");
      } finally {
        setIsBusy(false);
      }
    },
    [postBanner]
  );

  const clearMixBackgroundTrack = useCallback(() => {
    mixBgBufferRef.current = null;
    setMixBgName(null);
    setMixBgDurationMs(0);
  }, []);

  const mixWithBackgroundTrack = useCallback(
    (mainGain: number, bgGain: number) => {
      const main = activeBufferRef.current;
      const bg = mixBgBufferRef.current;
      if (!main || !bg) {
        postBanner("يرجى التأكد من وجود الصوت الرئيسي واستيراد مسار الخلفية أولاً");
        return;
      }
      const mixed = mixTracks(main, bg, mainGain, bgGain);
      setActiveBuffer(mixed, true);
      postBanner("تم مزج المسارين معاً في مسار استوديو واحد بنجاح");
    },
    [postBanner, setActiveBuffer]
  );

  const appendAudioFileToCurrent = useCallback(
    async (file: File) => {
      const src = activeBufferRef.current;
      if (!src) {
        await importAudioFile(file);
        return;
      }
      setIsBusy(true);
      try {
        const extra = await decodeAudioFile(file);
        setActiveBuffer(mergeClips(src, extra), true);
        postBanner("تم دمج المقطع الصوتي في نهاية الصوت بنجاح");
      } catch {
        postBanner("تعذر فك ترميز المقطع الإضافي للدمج");
      } finally {
        setIsBusy(false);
      }
    },
    [importAudioFile, postBanner, setActiveBuffer]
  );

  const appendSavedProjectToCurrent = useCallback(
    async (project: StudioProject) => {
      setIsBusy(true);
      try {
        const extra = await loadProjectAudioBuffer(project.id);
        if (!extra) {
          postBanner("هذا المشروع لا يحتوي على ملف صوتي للدمج");
          return;
        }
        const cur = activeBufferRef.current;
        if (!cur) {
          setActiveBuffer(extra, false);
        } else {
          setActiveBuffer(mergeClips(cur, extra), true);
        }
        postBanner(`تم دمج "${project.name}" في نهاية المقطع بنجاح`);
      } finally {
        setIsBusy(false);
      }
    },
    [postBanner, setActiveBuffer]
  );

  const undoLastEdit = useCallback(() => {
    const prev = undoStackRef.current.pop();
    if (!prev) return;
    if (activeBufferRef.current) {
      if (redoStackRef.current.length >= 12) redoStackRef.current.shift();
      redoStackRef.current.push(activeBufferRef.current);
    }
    activeBufferRef.current = prev;
    playerRef.current?.setBuffer(prev, true);
    setDurationMs(getDurationMs(prev));
    setWaveformPeaks(computeWaveformPeaks(prev, 120));
    setTrimStart(0);
    setTrimEnd(1);
    setCanUndoEdit(undoStackRef.current.length > 0);
    setCanRedoEdit(redoStackRef.current.length > 0);
    postBanner("تم التراجع عن آخر تعديل");
  }, [postBanner]);

  const redoLastEdit = useCallback(() => {
    const next = redoStackRef.current.pop();
    if (!next) return;
    if (activeBufferRef.current) {
      if (undoStackRef.current.length >= 12) undoStackRef.current.shift();
      undoStackRef.current.push(activeBufferRef.current);
    }
    activeBufferRef.current = next;
    playerRef.current?.setBuffer(next, true);
    setDurationMs(getDurationMs(next));
    setWaveformPeaks(computeWaveformPeaks(next, 120));
    setTrimStart(0);
    setTrimEnd(1);
    setCanUndoEdit(undoStackRef.current.length > 0);
    setCanRedoEdit(redoStackRef.current.length > 0);
    postBanner("تمت إعادة التعديل");
  }, [postBanner]);

  // Projects & Recordings & Presets
  const saveCurrentProject = useCallback(async () => {
    setIsBusy(true);
    try {
      const saved = await saveProjectRecord(
        activeProjectId,
        activeProjectName,
        activeBufferRef.current,
        dspConfigRef.current
      );
      setActiveProjectId(saved.id);
      setActiveProjectName(saved.name);
      setHasUnsavedChanges(false);
      await refreshLists();
      postBanner("تم حفظ المشروع بنجاح");
    } finally {
      setIsBusy(false);
    }
  }, [activeProjectId, activeProjectName, postBanner, refreshLists]);

  const saveEditsPermanent = useCallback(async () => {
    undoStackRef.current = [];
    redoStackRef.current = [];
    setCanUndoEdit(false);
    setCanRedoEdit(false);
    await saveCurrentProject();
  }, [saveCurrentProject]);

  const newProject = useCallback(() => {
    playerRef.current?.stop();
    activeBufferRef.current = null;
    undoStackRef.current = [];
    redoStackRef.current = [];
    setActiveProjectId(0);
    setActiveProjectName(t(language, "untitled_project"));
    setHasAudio(false);
    setDurationMs(0);
    setWaveformPeaks([]);
    setDspConfig(createDefaultDspConfig());
    setActivePresetId(null);
    setHasUnsavedChanges(false);
    setCanUndoEdit(false);
    setCanRedoEdit(false);
    playerRef.current?.setBuffer(null, true);
    postBanner("تم إنشاء مشروع صوتي جديد");
  }, [language, postBanner]);

  const loadProject = useCallback(
    async (project: StudioProject) => {
      setIsBusy(true);
      try {
        playerRef.current?.stop();
        const buf = await loadProjectAudioBuffer(project.id);
        setActiveProjectId(project.id);
        setActiveProjectName(project.name);
        setDspConfig(project.config);
        setActivePresetId(null);
        if (buf) {
          setActiveBuffer(buf, false);
        } else {
          activeBufferRef.current = null;
          setHasAudio(false);
          setDurationMs(0);
          setWaveformPeaks([]);
          playerRef.current?.setBuffer(null, true);
        }
        setHasUnsavedChanges(false);
        postBanner(`تم فتح المشروع "${project.name}"`);
      } finally {
        setIsBusy(false);
      }
    },
    [postBanner, setActiveBuffer]
  );

  const deleteProject = useCallback(
    async (project: StudioProject) => {
      await deleteProjectRecord(project.id);
      if (activeProjectId === project.id) setActiveProjectId(0);
      await refreshLists();
      postBanner(`تم حذف المشروع "${project.name}"`);
    },
    [activeProjectId, postBanner, refreshLists]
  );

  const loadRecordingIntoStudio = useCallback(
    async (rec: AudioRecordingEntity) => {
      setIsBusy(true);
      try {
        const buf = await loadRecordingBuffer(rec.id);
        if (!buf) {
          postBanner("تعذر تحميل التسجيل المحفوظ");
          return;
        }
        setActiveBuffer(buf, false);
        postBanner(`تم فتح "${rec.title}" في الاستوديو بنجاح`);
      } finally {
        setIsBusy(false);
      }
    },
    [postBanner, setActiveBuffer]
  );

  const deleteRecording = useCallback(
    async (rec: AudioRecordingEntity) => {
      await deleteRecordingRecord(rec.id);
      await refreshLists();
      postBanner(`تم حذف التسجيل "${rec.title}"`);
    },
    [postBanner, refreshLists]
  );

  const allPresets = [...customPresets, ...BUILT_IN_PRESETS];

  const applyPreset = useCallback(
    (preset: StudioPreset) => {
      setDspConfig({ ...preset.config, bypassAll: false });
      setActivePresetId(preset.id);
      setHasUnsavedChanges(true);
      postBanner(`تم تطبيق قالب "${preset.name}"`);
    },
    [postBanner]
  );

  const saveCustomPreset = useCallback(
    async (name: string, description: string) => {
      if (!name.trim()) return;
      await saveCustomPresetRecord(name, description, dspConfigRef.current);
      await refreshLists();
      postBanner(`تم حفظ القالب "${name}"`);
    },
    [postBanner, refreshLists]
  );

  const deleteCustomPreset = useCallback(
    async (presetId: number) => {
      await deleteCustomPresetRecord(presetId);
      await refreshLists();
      postBanner("تم حذف القالب المخصص");
    },
    [postBanner, refreshLists]
  );

  const exportRenderedAudio = useCallback(
    async (format: ExportAudioFormat, fileName: string) => {
      const src = activeBufferRef.current;
      if (!src) {
        postBanner("لا يوجد صوت لتصديره حالياً");
        return;
      }
      setIsBusy(true);
      setExportProgress(0.05);
      try {
        await new Promise((r) => setTimeout(r, 0));
        const rendered = renderOfflineDsp(src, dspConfigRef.current);
        setExportProgress(0.45);
        const { blob, extension } = await exportAudioBlob(rendered, format, (p) => {
          setExportProgress(0.45 + p * 0.55);
        });
        const cleanName =
          fileName.trim().replace(/[\\/:*?"<>|]/g, "_") || "VoicePro_Export";
        triggerDownloadBlob(blob, `${cleanName}.${extension}`);
        postBanner(`تم حفظ الملف بصيغة ${extension.toUpperCase()} بنجاح`);
      } catch {
        postBanner("تعذر تصدير الملف الصوتي");
      } finally {
        setIsBusy(false);
        setExportProgress(null);
      }
    },
    [postBanner]
  );

  // AI Studio
  const transcribeActiveAudio = useCallback(async () => {
    const src = activeBufferRef.current;
    if (!src) {
      setTranscriptionState({
        isLoading: false,
        outputText: "",
        errorMessage: "لا يوجد مقطع صوتي حالياً لتفريغه. سجّل أو استورد صوتاً أولاً.",
        searchQueries: [],
        sources: [],
      });
      return;
    }

    setTranscriptionState({
      isLoading: true,
      outputText: "",
      errorMessage: null,
      searchQueries: [],
      sources: [],
    });

    try {
      const wavB64 = encodeCompactMonoWavBase64(src, 16000);
      const resp = await fetch("/api/ai/transcribe", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          audioBase64: wavB64,
          mimeType: "audio/wav",
          languageHint: language,
        }),
      });
      if (!resp.ok || !resp.body) {
        throw new Error(`HTTP ${resp.status}`);
      }

      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      let bufferStr = "";
      let accumulated = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        bufferStr += decoder.decode(value, { stream: true });
        const events = bufferStr.split("\n\n");
        bufferStr = events.pop() ?? "";
        for (const ev of events) {
          const line = ev.split("\n").find((l) => l.startsWith("data: "));
          if (!line) continue;
          const payload = JSON.parse(line.slice(6));
          if (payload.error) {
            throw new Error(payload.error);
          }
          if (payload.chunk) {
            accumulated += payload.chunk;
            setTranscriptionState((s) => ({ ...s, outputText: accumulated }));
          }
          if (payload.done) {
            setTranscriptionState((s) => ({ ...s, isLoading: false }));
          }
        }
      }
      setTranscriptionState((s) => ({ ...s, isLoading: false }));
    } catch (err: unknown) {
      setTranscriptionState((s) => ({
        ...s,
        isLoading: false,
        errorMessage:
          err instanceof Error
            ? err.message
            : "تعذر إتمام التفريغ الصوتي. تأكد من الاتصال بالخادم.",
      }));
    }
  }, [language]);

  const clearTranscription = useCallback(() => {
    setTranscriptionState({
      isLoading: false,
      outputText: "",
      errorMessage: null,
      searchQueries: [],
      sources: [],
    });
  }, []);

  const runSmartAutoMaster = useCallback(async () => {
    const src = activeBufferRef.current;
    if (!src) {
      postBanner("قم بتسجيل أو استيراد مقطع صوتي أولاً لتحليله");
      return;
    }
    setIsBusy(true);
    try {
      await new Promise((r) => setTimeout(r, 0));
      const analysis = analyzeAndOptimizeAudio(src, dspConfigRef.current);
      setSmartMasterState(analysis);
      setDspConfig(analysis.optimizedConfig);
      setActivePresetId(null);
      setHasUnsavedChanges(true);
      postBanner("تم تحليل الصوت وتطبيق الماسترينغ الذكي تلقائياً");
    } finally {
      setIsBusy(false);
    }
  }, [postBanner]);

  const clearSmartMasterAnalysis = useCallback(() => {
    setSmartMasterState(null);
  }, []);

  const askSearchAssistant = useCallback(
    async (query: string) => {
      if (!query.trim()) return;
      setSearchAssistantState({
        isLoading: true,
        outputText: "",
        errorMessage: null,
        searchQueries: [],
        sources: [],
      });

      try {
        const resp = await fetch("/api/ai/search", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ query: query.trim(), language }),
        });
        if (!resp.ok || !resp.body) {
          throw new Error(`HTTP ${resp.status}`);
        }
        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let bufferStr = "";
        let accumulated = "";

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          bufferStr += decoder.decode(value, { stream: true });
          const events = bufferStr.split("\n\n");
          bufferStr = events.pop() ?? "";
          for (const ev of events) {
            const line = ev.split("\n").find((l) => l.startsWith("data: "));
            if (!line) continue;
            const payload = JSON.parse(line.slice(6));
            if (payload.error) throw new Error(payload.error);
            if (payload.chunk) {
              accumulated += payload.chunk;
              setSearchAssistantState((s) => ({ ...s, outputText: accumulated }));
            }
            if (payload.done) {
              setSearchAssistantState((s) => ({
                ...s,
                isLoading: false,
                searchQueries: payload.searchQueries ?? [],
                sources: payload.sources ?? [],
              }));
            }
          }
        }
        setSearchAssistantState((s) => ({ ...s, isLoading: false }));
      } catch (err: unknown) {
        setSearchAssistantState((s) => ({
          ...s,
          isLoading: false,
          errorMessage:
            err instanceof Error ? err.message : "تعذر إتمام البحث الفوري الآن.",
        }));
      }
    },
    [language]
  );

  // TTS Studio (Web Speech API Preview + Gemini 3.8 TTS WAV buffer synthesis)
  const previewTtsSpeech = useCallback(
    (text: string, dialect: TtsDialect, speechRate: number, pitch: number) => {
      if (!text.trim()) {
        setTtsUiState((s) => ({ ...s, errorMessage: "يرجى إدخال النص أولاً." }));
        return;
      }
      if (!("speechSynthesis" in window)) {
        setTtsUiState((s) => ({
          ...s,
          errorMessage: "معاينة النطق الفوري غير مدعومة في هذا المتصفح، يمكنك استخدام التوليد المباشر.",
        }));
        return;
      }
      window.speechSynthesis.cancel();
      const utter = new SpeechSynthesisUtterance(text);
      utter.lang = dialect.localeTag;
      utter.rate = clamp(speechRate, 0.5, 2.0);
      utter.pitch = clamp(pitch, 0.5, 2.0);
      const voices = window.speechSynthesis.getVoices();
      const matched =
        voices.find((v) => v.lang.toLowerCase() === dialect.localeTag.toLowerCase()) ??
        voices.find((v) => v.lang.toLowerCase().startsWith(dialect.fallbackLocaleTag.toLowerCase()));
      if (matched) utter.voice = matched;

      utter.onstart = () => setTtsUiState((s) => ({ ...s, isPreviewing: true, errorMessage: null }));
      utter.onend = () => setTtsUiState((s) => ({ ...s, isPreviewing: false }));
      utter.onerror = () =>
        setTtsUiState((s) => ({ ...s, isPreviewing: false, errorMessage: null }));

      setTtsUiState((s) => ({ ...s, isPreviewing: true, errorMessage: null }));
      window.speechSynthesis.speak(utter);
    },
    []
  );

  const stopTtsPreview = useCallback(() => {
    window.speechSynthesis?.cancel();
    setTtsUiState((s) => ({ ...s, isPreviewing: false }));
  }, []);

  const generateTtsToStudio = useCallback(
    async (
      text: string,
      dialect: TtsDialect,
      speechRate: number,
      pitch: number,
      appendToExisting: boolean
    ) => {
      if (!text.trim()) {
        setTtsUiState((s) => ({ ...s, errorMessage: "يرجى إدخال نص لتوليد الصوت." }));
        return;
      }
      setTtsUiState({ isPreviewing: false, isGenerating: true, errorMessage: null });
      setIsBusy(true);
      try {
        const resp = await fetch("/api/ai/tts", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            text,
            dialectTitle: t(language, dialect.titleKey),
            dialectTag: dialect.localeTag,
            speechRate,
            pitch,
          }),
        });
        const data = await resp.json();
        if (!resp.ok || !data.audioBase64) {
          throw new Error(data.error || "تعذر توليد الصوت");
        }
        const wavBytes = base64ToUint8(data.audioBase64);
        const decoded = await decodeAudioArrayBuffer(wavBytes.buffer as ArrayBuffer);

        const cur = activeBufferRef.current;
        const nextBuf =
          appendToExisting && cur ? mergeClips(cur, decoded) : decoded;
        setActiveBuffer(nextBuf, Boolean(cur));

        const nowStr = new Date().toLocaleTimeString("en-GB", { hour12: false });
        await indexAudioRecording(`TTS ${nowStr}`, decoded, "TTS");
        await refreshLists();
        setTtsUiState({ isPreviewing: false, isGenerating: false, errorMessage: null });
        postBanner("تم توليد الصوت وإدراجه في الاستوديو بنجاح");
      } catch (err: unknown) {
        setTtsUiState({
          isPreviewing: false,
          isGenerating: false,
          errorMessage: err instanceof Error ? err.message : "تعذر توليد الصوت",
        });
      } finally {
        setIsBusy(false);
      }
    },
    [language, postBanner, refreshLists, setActiveBuffer]
  );

  // Offline Formant Voice Transformer
  const transformVoiceOffline = useCallback(
    async (
      pitchSemitones: number,
      formantShift: number,
      warmthDb: number,
      presenceDb: number,
      mix: number,
      characterLabel: string
    ) => {
      const src = activeBufferRef.current;
      if (!src) {
        postBanner("قم بتسجيل أو استيراد مقطع صوتي أولاً لتحويله");
        return;
      }
      setIsBusy(true);
      setPurifyProgress(0.02);
      try {
        const transformed = await runTransformVoiceOffline(
          src,
          pitchSemitones,
          formantShift,
          warmthDb,
          presenceDb,
          mix,
          (p) => setPurifyProgress(p)
        );
        setActiveBuffer(transformed, true);
        postBanner(`تم تحويل الشخصية الصوتية (${characterLabel}) بنجاح`);
      } finally {
        setIsBusy(false);
        setPurifyProgress(null);
      }
    },
    [postBanner, setActiveBuffer]
  );

  const requestNavigateToEchoDetail = useCallback(
    (group: EchoDetailGroup) => {
      navigateTo({ screen: "ECHO_DETAIL", group });
    },
    [navigateTo]
  );

  const value: StudioContextValue = {
    language,
    setLanguage,
    authSession,
    continueAsGuest,
    requestSignIn,
    signOut,
    signInWithEmail,
    createAccountWithEmail,
    destination,
    navigateTo,
    navigateBack,
    hasAudio,
    durationMs,
    waveformPeaks,
    dspConfig,
    activePresetId,
    isSimpleEchoMode,
    setSimpleEchoMode,
    echoPadAxisMode,
    setEchoPadAxisMode,
    isHoldBypassing,
    setHoldBypass: setIsHoldBypassing,
    toggleBypass,
    updateDspConfig,
    resetAllEffects,
    toggleEffectEnabled,
    moveEffectInChain,
    updateEchoReverbParams,
    updateEchoReverbFromPad,
    getEchoPadAxes,
    playback,
    recorder,
    micPermissionError,
    togglePlayPause,
    stopPlayback,
    seekToFraction,
    loopSelection,
    toggleLoopSelection,
    autoLoopOnPlayback,
    setAutoLoopOnPlayback,
    startRecording,
    pauseRecording,
    resumeRecording,
    finishRecording,
    discardRecording,
    importAudioFile,
    applyVoicePurifyPreset,
    purifyAudioOffline,
    trimStart,
    trimEnd,
    updateTrimRange,
    resetSelection,
    clipGainDb,
    setClipGainDb,
    bakeClipGainDb,
    trimKeepSelection,
    cutSelectedRange,
    normalizeAudioBuffer,
    applyFadeInSeconds,
    applyFadeOutSeconds,
    mixBgName,
    mixBgDurationMs,
    loadMixBackgroundTrack,
    clearMixBackgroundTrack,
    mixWithBackgroundTrack,
    appendAudioFileToCurrent,
    appendSavedProjectToCurrent,
    canUndoEdit,
    canRedoEdit,
    hasUnsavedChanges,
    undoLastEdit,
    redoLastEdit,
    saveEditsPermanent,
    projects,
    recordings,
    allPresets,
    activeProjectId,
    activeProjectName,
    setActiveProjectName,
    newProject,
    saveCurrentProject,
    loadProject,
    deleteProject,
    loadRecordingIntoStudio,
    deleteRecording,
    applyPreset,
    saveCustomPreset,
    deleteCustomPreset,
    exportRenderedAudio,
    transcriptionState,
    transcribeActiveAudio,
    clearTranscription,
    smartMasterState,
    runSmartAutoMaster,
    clearSmartMasterAnalysis,
    searchAssistantState,
    askSearchAssistant,
    ttsUiState,
    previewTtsSpeech,
    stopTtsPreview,
    generateTtsToStudio,
    transformVoiceOffline,
    statusBanner,
    clearBanner: () => setStatusBanner(null),
    isBusy,
    exportProgress,
    purifyProgress,
    requestNavigateToEchoDetail,
  };

  return <StudioContext.Provider value={value}>{children}</StudioContext.Provider>;
};

export function useStudio(): StudioContextValue {
  const ctx = useContext(StudioContext);
  if (!ctx) throw new Error("useStudio must be used inside StudioProvider");
  return ctx;
}
