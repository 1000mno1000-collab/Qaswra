import React, { useEffect, useRef, useState } from "react";
import {
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  CopyPlus,
  Download,
  FileAudio,
  Layers,
  Mic,
  Play,
  Redo2,
  Repeat,
  RotateCcw,
  Save,
  Scissors,
  Sliders,
  Sparkles,
  Square,
  Trash2,
  TrendingDown,
  TrendingUp,
  Undo2,
  Upload,
  Volume2,
  X,
} from "lucide-react";
import { formatDurationMs } from "../audio/audioEditorOps";
import { ExportFormatDialog } from "../components/Dialogs";
import { StudioFocusTopBar, StudioParamSlider } from "../components/StudioComponents";
import { useStudio } from "../context/StudioContext";
import { t } from "../i18n/translations";
import { TTS_DIALECTS, VOICE_CHARACTER_PRESETS } from "../types/studio";

export const AudioEditorScreen: React.FC = () => {
  const {
    language,
    navigateBack,
    hasAudio,
    durationMs,
    waveformPeaks,
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
    projects,
    canUndoEdit,
    canRedoEdit,
    undoLastEdit,
    redoLastEdit,
    saveEditsPermanent,
    loopSelection,
    toggleLoopSelection,
    playback,
    exportRenderedAudio,
    exportProgress,
    isBusy,
    activeProjectName,
    ttsUiState,
    previewTtsSpeech,
    stopTtsPreview,
    generateTtsToStudio,
    purifyProgress,
    transformVoiceOffline,
  } = useStudio();

  const [fadeInSec, setFadeInSec] = useState(2.0);
  const [fadeOutSec, setFadeOutSec] = useState(3.0);
  const [mainMixGain, setMainMixGain] = useState(1.0);
  const [bgMixGain, setBgMixGain] = useState(0.45);
  const [showExportDialog, setShowExportDialog] = useState(false);

  // Embedded TTS Card State
  const [ttsExpanded, setTtsExpanded] = useState(!hasAudio);
  const [selectedDialect, setSelectedDialect] = useState(TTS_DIALECTS[0]);
  const [ttsText, setTtsText] = useState(TTS_DIALECTS[0].sampleText);
  const [ttsRate, setTtsRate] = useState(TTS_DIALECTS[0].defaultRate);
  const [ttsPitch, setTtsPitch] = useState(TTS_DIALECTS[0].defaultPitch);
  const [ttsAppend, setTtsAppend] = useState(hasAudio);

  // Embedded Voice Transformer Card State
  const [vtExpanded, setVtExpanded] = useState(false);
  const [vtPreset, setVtPreset] = useState(VOICE_CHARACTER_PRESETS[0]);
  const [vtPitchSt, setVtPitchSt] = useState(VOICE_CHARACTER_PRESETS[0].pitchSemitones);
  const [vtFormant, setVtFormant] = useState(VOICE_CHARACTER_PRESETS[0].formantShift);
  const [vtWarmth, setVtWarmth] = useState(VOICE_CHARACTER_PRESETS[0].warmthDb);
  const [vtPresence, setVtPresence] = useState(VOICE_CHARACTER_PRESETS[0].presenceDb);

  const mixFileRef = useRef<HTMLInputElement | null>(null);
  const appendFileRef = useRef<HTMLInputElement | null>(null);

  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const dragModeRef = useRef<"START" | "END" | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    const w = Math.max(120, Math.floor(rect.width * dpr));
    const h = Math.max(80, Math.floor(rect.height * dpr));
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    ctx.clearRect(0, 0, w, h);

    // Selection shading
    const sx = trimStart * w;
    const ex = trimEnd * w;
    ctx.fillStyle = "rgba(34, 211, 238, 0.14)";
    ctx.fillRect(sx, 0, Math.max(2, ex - sx), h);

    // Waveform bars
    const peaks = waveformPeaks.length > 0 ? waveformPeaks : new Array(60).fill(0.08);
    const barW = w / peaks.length;
    const midY = h / 2;
    for (let i = 0; i < peaks.length; i++) {
      const frac = i / peaks.length;
      const inside = frac >= trimStart && frac <= trimEnd;
      const amp = Math.max(0.06, peaks[i]);
      const barH = amp * h * 0.82;
      ctx.fillStyle = inside ? "#22D3EE" : "#334155";
      ctx.beginPath();
      ctx.roundRect(
        i * barW + 1,
        midY - barH / 2,
        Math.max(1.5, barW - 2),
        barH,
        2 * dpr
      );
      ctx.fill();
    }

    // Playhead cursor
    if (durationMs > 0) {
      const px = (playback.positionMs / durationMs) * w;
      ctx.strokeStyle = "#F43F5E";
      ctx.lineWidth = 2 * dpr;
      ctx.beginPath();
      ctx.moveTo(px, 0);
      ctx.lineTo(px, h);
      ctx.stroke();
    }

    // Handles
    const drawHandle = (x: number, color: string) => {
      ctx.strokeStyle = color;
      ctx.lineWidth = 3 * dpr;
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, h);
      ctx.stroke();

      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.arc(x, 14 * dpr, 7 * dpr, 0, Math.PI * 2);
      ctx.fill();
    };
    drawHandle(sx, "#10B981");
    drawHandle(ex, "#F59E0B");
  }, [waveformPeaks, trimStart, trimEnd, playback.positionMs, durationMs]);

  const handleWaveformPointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const frac = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    const distStart = Math.abs(frac - trimStart);
    const distEnd = Math.abs(frac - trimEnd);
    dragModeRef.current = distStart <= distEnd ? "START" : "END";
    e.currentTarget.setPointerCapture(e.pointerId);
    if (dragModeRef.current === "START") updateTrimRange(frac, trimEnd);
    else updateTrimRange(trimStart, frac);
  };

  const handleWaveformPointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (!dragModeRef.current || !canvasRef.current) return;
    const rect = canvasRef.current.getBoundingClientRect();
    const frac = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    if (dragModeRef.current === "START") updateTrimRange(frac, trimEnd);
    else updateTrimRange(trimStart, frac);
  };

  const startMs = Math.round(trimStart * durationMs);
  const endMs = Math.round(trimEnd * durationMs);

  return (
    <div className="space-y-4">
      <StudioFocusTopBar
        title={t(language, "section_editor_title")}
        subtitle={t(language, "section_editor_subtitle")}
        onBack={navigateBack}
      />

      {/* Waveform & Selection Toolbar */}
      <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-3">
        <div className="flex items-center justify-between gap-2 flex-wrap">
          <div className="font-mono-telemetry text-xs text-[#22D3EE]">
            {formatDurationMs(startMs)} → {formatDurationMs(endMs)} ({formatDurationMs(Math.max(0, endMs - startMs))})
          </div>

          <div className="flex items-center gap-1.5">
            <button
              type="button"
              onClick={toggleLoopSelection}
              className={`h-9 px-2.5 rounded-[10px] border text-xs font-bold flex items-center gap-1 cursor-pointer ${
                loopSelection
                  ? "bg-[#10B981]/20 border-[#10B981] text-[#10B981]"
                  : "bg-[#1B2640] border-[#283759] text-[#94A3B8]"
              }`}
            >
              <Repeat className="w-3.5 h-3.5" />
              <span>{t(language, "loop_selection")}</span>
            </button>
            <button
              type="button"
              disabled={!canUndoEdit}
              onClick={undoLastEdit}
              title={t(language, "undo_edit")}
              className="h-9 w-9 rounded-[10px] bg-[#1B2640] disabled:opacity-35 border border-[#283759] text-[#F1F5F9] flex items-center justify-center cursor-pointer"
            >
              <Undo2 className="w-4 h-4" />
            </button>
            <button
              type="button"
              disabled={!canRedoEdit}
              onClick={redoLastEdit}
              title={t(language, "redo_edit")}
              className="h-9 w-9 rounded-[10px] bg-[#1B2640] disabled:opacity-35 border border-[#283759] text-[#F1F5F9] flex items-center justify-center cursor-pointer"
            >
              <Redo2 className="w-4 h-4" />
            </button>
            <button
              type="button"
              disabled={!hasAudio || isBusy}
              onClick={saveEditsPermanent}
              title={t(language, "save_changes")}
              className="h-9 px-2.5 rounded-[10px] bg-[#10B981] hover:bg-[#34D399] disabled:opacity-40 text-[#070B14] text-xs font-bold flex items-center gap-1 cursor-pointer"
            >
              <Save className="w-3.5 h-3.5" />
              <span>{t(language, "save_changes")}</span>
            </button>
          </div>
        </div>

        <canvas
          ref={canvasRef}
          onPointerDown={handleWaveformPointerDown}
          onPointerMove={handleWaveformPointerMove}
          onPointerUp={() => {
            dragModeRef.current = null;
          }}
          className="w-full h-36 rounded-[16px] bg-[#070B14] border border-[#283759] touch-none cursor-ew-resize block"
        />

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
          <button
            type="button"
            disabled={!hasAudio}
            onClick={trimKeepSelection}
            className="h-10 rounded-[12px] bg-[#22D3EE]/15 border border-[#22D3EE]/50 text-[#22D3EE] disabled:opacity-40 text-xs font-bold flex items-center justify-center gap-1 cursor-pointer"
          >
            <Scissors className="w-3.5 h-3.5" />
            <span>{t(language, "trim_keep")}</span>
          </button>

          <button
            type="button"
            disabled={!hasAudio}
            onClick={cutSelectedRange}
            className="h-10 rounded-[12px] bg-[#F43F5E]/15 border border-[#F43F5E]/50 text-[#F43F5E] disabled:opacity-40 text-xs font-bold flex items-center justify-center gap-1 cursor-pointer"
          >
            <Trash2 className="w-3.5 h-3.5" />
            <span>{t(language, "cut_selection")}</span>
          </button>

          <button
            type="button"
            disabled={!hasAudio}
            onClick={resetSelection}
            className="h-10 rounded-[12px] bg-[#1B2640] border border-[#283759] text-[#F1F5F9] disabled:opacity-40 text-xs font-bold flex items-center justify-center gap-1 cursor-pointer"
          >
            <RotateCcw className="w-3.5 h-3.5" />
            <span>{t(language, "reset_selection")}</span>
          </button>

          <button
            type="button"
            disabled={!hasAudio}
            onClick={normalizeAudioBuffer}
            className="h-10 rounded-[12px] bg-[#10B981]/15 border border-[#10B981]/50 text-[#10B981] disabled:opacity-40 text-xs font-bold flex items-center justify-center gap-1 cursor-pointer"
          >
            <Sliders className="w-3.5 h-3.5" />
            <span>{t(language, "normalize_audio")}</span>
          </button>
        </div>

        {/* Clip Gain */}
        <div className="pt-2 border-t border-[#283759] flex items-end gap-3">
          <div className="flex-1">
            <StudioParamSlider
              label={t(language, "clip_volume")}
              value={clipGainDb}
              min={-18}
              max={18}
              step={0.5}
              formattedValue={`${clipGainDb >= 0 ? "+" : ""}${clipGainDb.toFixed(1)} dB`}
              accentColor="#F59E0B"
              onChange={setClipGainDb}
            />
          </div>
          <button
            type="button"
            disabled={!hasAudio || Math.abs(clipGainDb) < 0.1}
            onClick={() => bakeClipGainDb(clipGainDb)}
            className="h-10 px-4 rounded-[12px] bg-[#F59E0B] disabled:bg-[#1B2640] disabled:text-[#64748B] text-[#070B14] font-bold text-xs cursor-pointer mb-1"
          >
            تطبيق الشدة
          </button>
        </div>
      </div>

      {/* Fade In / Fade Out Card */}
      <div className="rounded-[20px] bg-[#131B2E] border border-[#283759] p-4 space-y-3">
        <h2 className="text-sm font-bold text-[#F1F5F9]">تدرج الصوت (Fade In / Fade Out)</h2>

        <StudioParamSlider
          label={t(language, "fade_in_title")}
          value={fadeInSec}
          min={1}
          max={10}
          step={0.5}
          formattedValue={`${fadeInSec.toFixed(1)} ثانية`}
          accentColor="#10B981"
          onChange={setFadeInSec}
        />
        <button
          type="button"
          disabled={!hasAudio}
          onClick={() => applyFadeInSeconds(fadeInSec)}
          className="w-full h-11 rounded-[14px] bg-[#10B981] hover:bg-[#34D399] disabled:bg-[#1B2640] disabled:text-[#64748B] text-[#070B14] font-bold text-xs flex items-center justify-center gap-1.5 cursor-pointer"
        >
          <TrendingUp className="w-4 h-4" />
          <span>تطبيق ظهور تدريجي</span>
        </button>

        <StudioParamSlider
          label={t(language, "fade_out_title")}
          value={fadeOutSec}
          min={1}
          max={10}
          step={0.5}
          formattedValue={`${fadeOutSec.toFixed(1)} ثانية`}
          accentColor="#22D3EE"
          onChange={setFadeOutSec}
        />
        <button
          type="button"
          disabled={!hasAudio}
          onClick={() => applyFadeOutSeconds(fadeOutSec)}
          className="w-full h-11 rounded-[14px] bg-[#22D3EE] hover:bg-[#38BDF8] disabled:bg-[#1B2640] disabled:text-[#64748B] text-[#070B14] font-bold text-xs flex items-center justify-center gap-1.5 cursor-pointer"
        >
          <TrendingDown className="w-4 h-4" />
          <span>تطبيق اختفاء تدريجي</span>
        </button>
      </div>

      {/* Dual Track Mix Card */}
      <div className="rounded-[20px] bg-[#131B2E] border border-[#22D3EE]/45 p-4 space-y-3">
        <div className="flex items-center gap-2 text-[#22D3EE]">
          <Layers className="w-5 h-5" />
          <h2 className="text-sm font-bold">{t(language, "mix_tracks_title")}</h2>
        </div>
        <p className="text-xs text-[#94A3B8]">
          استورد ملفاً صوتياً ثانياً لدمجه فوق مسار صوتك في نفس التوقيت (خلفية موسيقية أو مؤثر مزدوج) مع تحكم مستقل بمستوى كل مسار.
        </p>

        {!mixBgName ? (
          <button
            type="button"
            onClick={() => mixFileRef.current?.click()}
            className="w-full h-11 rounded-[14px] border border-[#22D3EE]/60 text-[#22D3EE] hover:bg-[#22D3EE]/10 font-bold text-xs flex items-center justify-center gap-2 cursor-pointer"
          >
            <Upload className="w-4 h-4" />
            <span>استيراد مسار الخلفية للمزج</span>
          </button>
        ) : (
          <div className="space-y-3">
            <div className="rounded-[14px] bg-[#070B14] border border-[#10B981]/50 px-3 py-2 flex items-center justify-between gap-2">
              <div className="min-w-0">
                <div className="text-xs font-bold text-[#F1F5F9] truncate">{mixBgName}</div>
                <div className="text-[11px] text-[#10B981] font-mono-telemetry">
                  مدة الخلفية: {formatDurationMs(mixBgDurationMs)}
                </div>
              </div>
              <button
                type="button"
                onClick={clearMixBackgroundTrack}
                className="w-8 h-8 rounded-full text-[#94A3B8] hover:text-[#F43F5E] flex items-center justify-center cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <StudioParamSlider
              label="مستوى الصوت الرئيسي (Main Voice)"
              value={mainMixGain}
              min={0}
              max={1.5}
              step={0.05}
              formattedValue={`${Math.round(mainMixGain * 100)}%`}
              accentColor="#10B981"
              onChange={setMainMixGain}
            />
            <StudioParamSlider
              label="مستوى مسار الخلفية (Background)"
              value={bgMixGain}
              min={0}
              max={1.5}
              step={0.05}
              formattedValue={`${Math.round(bgMixGain * 100)}%`}
              accentColor="#A78BFA"
              onChange={setBgMixGain}
            />

            <button
              type="button"
              disabled={!hasAudio}
              onClick={() => mixWithBackgroundTrack(mainMixGain, bgMixGain)}
              className="w-full h-11 rounded-[14px] bg-[#22D3EE] hover:bg-[#38BDF8] text-[#070B14] font-bold text-xs flex items-center justify-center gap-1.5 cursor-pointer"
            >
              <Layers className="w-4 h-4" />
              <span>تطبيق المزج الصوتي</span>
            </button>
          </div>
        )}

        <input
          ref={mixFileRef}
          type="file"
          accept="audio/*"
          className="hidden"
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) loadMixBackgroundTrack(f);
            e.target.value = "";
          }}
        />
      </div>

      {/* Embedded Voice Transformer Card */}
      <div className="rounded-[20px] bg-[#131B2E] border border-[#A78BFA]/45 p-4 space-y-3">
        <button
          type="button"
          onClick={() => setVtExpanded((v) => !v)}
          className="w-full flex items-center justify-between text-start cursor-pointer"
        >
          <div className="flex items-center gap-2">
            <Mic className="w-5 h-5 text-[#A78BFA]" />
            <div>
              <div className="text-sm font-bold text-[#A78BFA]">
                {t(language, "section_voice_transformer_title")}
              </div>
              <div className="text-xs text-[#94A3B8]">
                {t(language, "section_voice_transformer_subtitle")}
              </div>
            </div>
          </div>
          {vtExpanded ? (
            <ChevronUp className="w-5 h-5 text-[#A78BFA]" />
          ) : (
            <ChevronDown className="w-5 h-5 text-[#A78BFA]" />
          )}
        </button>

        {vtExpanded && (
          <div className="space-y-2.5 pt-2">
            {VOICE_CHARACTER_PRESETS.map((preset) => {
              const selected = vtPreset.id === preset.id;
              return (
                <button
                  key={preset.id}
                  type="button"
                  onClick={() => {
                    setVtPreset(preset);
                    setVtPitchSt(preset.pitchSemitones);
                    setVtFormant(preset.formantShift);
                    setVtWarmth(preset.warmthDb);
                    setVtPresence(preset.presenceDb);
                  }}
                  className={`w-full rounded-[14px] px-3 py-2.5 border flex items-center justify-between text-start cursor-pointer ${
                    selected
                      ? "bg-[#A78BFA]/15 border-[#A78BFA] text-[#A78BFA]"
                      : "bg-[#070B14] border-[#283759] text-[#F1F5F9]"
                  }`}
                >
                  <span className="text-xs font-bold">{t(language, preset.titleKey)}</span>
                  {selected && <CheckCircle2 className="w-4 h-4 text-[#A78BFA]" />}
                </button>
              );
            })}

            <StudioParamSlider
              label={t(language, "voice_pitch_semitones")}
              value={vtPitchSt}
              min={-9}
              max={10}
              step={0.1}
              formattedValue={`${vtPitchSt >= 0 ? "+" : ""}${vtPitchSt.toFixed(1)} st`}
              accentColor="#22D3EE"
              onChange={setVtPitchSt}
            />
            <StudioParamSlider
              label={t(language, "voice_formant_shift")}
              value={vtFormant}
              min={0.72}
              max={1.38}
              step={0.01}
              formattedValue={`${vtFormant.toFixed(2)}x`}
              accentColor="#A78BFA"
              onChange={setVtFormant}
            />
            {purifyProgress !== null && (
              <div className="w-full h-1.5 rounded-full bg-[#070B14] overflow-hidden">
                <div
                  className="h-full bg-[#A78BFA]"
                  style={{ width: `${Math.round(purifyProgress * 100)}%` }}
                />
              </div>
            )}
            <button
              type="button"
              disabled={!hasAudio || isBusy}
              onClick={() =>
                transformVoiceOffline(
                  vtPitchSt,
                  vtFormant,
                  vtWarmth,
                  vtPresence,
                  1,
                  t(language, vtPreset.titleKey)
                )
              }
              className="w-full h-11 rounded-[14px] bg-[#A78BFA] hover:bg-[#C4B5FD] disabled:bg-[#1B2640] disabled:text-[#64748B] text-[#070B14] font-bold text-xs flex items-center justify-center gap-1.5 cursor-pointer"
            >
              <Sparkles className="w-4 h-4" />
              <span>{t(language, "voice_apply_transform")}</span>
            </button>
          </div>
        )}
      </div>

      {/* Embedded TTS Card */}
      <div className="rounded-[20px] bg-[#131B2E] border border-[#22D3EE]/45 p-4 space-y-3">
        <button
          type="button"
          onClick={() => setTtsExpanded((v) => !v)}
          className="w-full flex items-center justify-between text-start cursor-pointer"
        >
          <div className="flex items-center gap-2">
            <Volume2 className="w-5 h-5 text-[#22D3EE]" />
            <div>
              <div className="text-sm font-bold text-[#22D3EE]">
                {t(language, "section_tts_title")}
              </div>
              <div className="text-xs text-[#94A3B8]">
                {t(language, "section_tts_subtitle")}
              </div>
            </div>
          </div>
          {ttsExpanded ? (
            <ChevronUp className="w-5 h-5 text-[#22D3EE]" />
          ) : (
            <ChevronDown className="w-5 h-5 text-[#22D3EE]" />
          )}
        </button>

        {ttsExpanded && (
          <div className="space-y-3 pt-2">
            <textarea
              rows={3}
              value={ttsText}
              onChange={(e) => setTtsText(e.target.value)}
              placeholder={t(language, "tts_input_label")}
              className="w-full rounded-[14px] bg-[#070B14] border border-[#283759] focus:border-[#22D3EE] p-3 text-sm text-[#F1F5F9] outline-none"
            />
            <div className="flex flex-wrap gap-1.5">
              {TTS_DIALECTS.map((d) => {
                const selected = d.id === selectedDialect.id;
                return (
                  <button
                    key={d.id}
                    type="button"
                    onClick={() => {
                      const wasSample =
                        !ttsText.trim() ||
                        TTS_DIALECTS.some((x) => x.sampleText === ttsText);
                      setSelectedDialect(d);
                      setTtsRate(d.defaultRate);
                      setTtsPitch(d.defaultPitch);
                      if (wasSample) setTtsText(d.sampleText);
                    }}
                    className={`px-2.5 py-1.5 rounded-[10px] text-[11px] font-bold border cursor-pointer ${
                      selected
                        ? "bg-[#22D3EE]/20 border-[#22D3EE] text-[#22D3EE]"
                        : "bg-[#1B2640] border-[#283759] text-[#F1F5F9]"
                    }`}
                  >
                    {t(language, d.titleKey)}
                  </button>
                );
              })}
            </div>
            <StudioParamSlider
              label={t(language, "tts_speed_label")}
              value={ttsRate}
              min={0.55}
              max={1.65}
              step={0.05}
              formattedValue={`${ttsRate.toFixed(2)}x`}
              onChange={setTtsRate}
            />
            <StudioParamSlider
              label={t(language, "tts_pitch_label")}
              value={ttsPitch}
              min={0.65}
              max={1.45}
              step={0.05}
              formattedValue={`${ttsPitch.toFixed(2)}x`}
              accentColor="#A78BFA"
              onChange={setTtsPitch}
            />
            {hasAudio && (
              <label className="flex items-center justify-between text-xs text-[#94A3B8] cursor-pointer">
                <span>{t(language, "tts_append_toggle")}</span>
                <input
                  type="checkbox"
                  checked={ttsAppend}
                  onChange={(e) => setTtsAppend(e.target.checked)}
                  className="w-4 h-4 accent-[#10B981]"
                />
              </label>
            )}
            {ttsUiState.errorMessage && (
              <p className="text-xs text-[#F43F5E]">{ttsUiState.errorMessage}</p>
            )}
            <div className="flex gap-2">
              <button
                type="button"
                disabled={!ttsText.trim() || ttsUiState.isGenerating}
                onClick={() =>
                  ttsUiState.isPreviewing
                    ? stopTtsPreview()
                    : previewTtsSpeech(ttsText, selectedDialect, ttsRate, ttsPitch)
                }
                className="flex-1 h-11 rounded-[14px] border border-[#22D3EE]/50 text-[#22D3EE] text-xs font-bold flex items-center justify-center gap-1.5 cursor-pointer"
              >
                {ttsUiState.isPreviewing ? (
                  <>
                    <Square className="w-4 h-4" />
                    <span>{t(language, "tts_stop_preview")}</span>
                  </>
                ) : (
                  <>
                    <Play className="w-4 h-4" />
                    <span>{t(language, "tts_preview_button")}</span>
                  </>
                )}
              </button>
              <button
                type="button"
                disabled={!ttsText.trim() || ttsUiState.isGenerating || isBusy}
                onClick={() =>
                  generateTtsToStudio(
                    ttsText,
                    selectedDialect,
                    ttsRate,
                    ttsPitch,
                    hasAudio && ttsAppend
                  )
                }
                className="flex-1 h-11 rounded-[14px] bg-[#10B981] text-[#070B14] font-bold text-xs flex items-center justify-center gap-1.5 cursor-pointer"
              >
                <Sparkles className="w-4 h-4" />
                <span>{t(language, "tts_insert_button")}</span>
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Sequential Merge & Multi-Format Export */}
      <div className="rounded-[20px] bg-[#131B2E] border border-[#283759] p-4 space-y-3">
        <div className="flex items-center justify-between gap-2">
          <span className="text-sm font-bold text-[#F1F5F9]">دمج متسلسل وتصدير نهائي</span>
          <button
            type="button"
            onClick={() => appendFileRef.current?.click()}
            className="h-9 px-3 rounded-[12px] bg-[#1B2640] border border-[#F59E0B]/50 text-[#F59E0B] text-xs font-bold flex items-center gap-1.5 cursor-pointer"
          >
            <CopyPlus className="w-4 h-4" />
            <span>{t(language, "merge_clip")}</span>
          </button>
        </div>

        <input
          ref={appendFileRef}
          type="file"
          accept="audio/*"
          className="hidden"
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) appendAudioFileToCurrent(f);
            e.target.value = "";
          }}
        />

        {projects.filter((p) => p.hasAudio).length > 0 && (
          <div className="space-y-1.5">
            <div className="text-xs text-[#94A3B8]">أو دمج من مشاريعك المحفوظة:</div>
            <div className="flex flex-wrap gap-1.5">
              {projects
                .filter((p) => p.hasAudio)
                .slice(0, 6)
                .map((p) => (
                  <button
                    key={p.id}
                    type="button"
                    onClick={() => appendSavedProjectToCurrent(p)}
                    className="px-2.5 py-1.5 rounded-[10px] bg-[#070B14] border border-[#283759] hover:border-[#22D3EE] text-xs text-[#F1F5F9] flex items-center gap-1 cursor-pointer"
                  >
                    <FileAudio className="w-3.5 h-3.5 text-[#22D3EE]" />
                    <span>+ {p.name}</span>
                  </button>
                ))}
            </div>
          </div>
        )}

        {exportProgress !== null && (
          <div className="w-full h-2 rounded-full bg-[#070B14] overflow-hidden">
            <div
              className="h-full bg-[#22D3EE]"
              style={{ width: `${Math.round(exportProgress * 100)}%` }}
            />
          </div>
        )}

        <button
          type="button"
          disabled={!hasAudio || isBusy}
          onClick={() => setShowExportDialog(true)}
          className="w-full h-12 rounded-[16px] bg-[#22D3EE] hover:bg-[#38BDF8] disabled:bg-[#1B2640] disabled:text-[#64748B] text-[#070B14] font-bold text-sm flex items-center justify-center gap-2 cursor-pointer"
        >
          <Download className="w-4 h-4" />
          <span>{t(language, "save_export_audio")}</span>
        </button>
      </div>

      <ExportFormatDialog
        open={showExportDialog}
        language={language}
        initialFileName={activeProjectName}
        onClose={() => setShowExportDialog(false)}
        onConfirm={(fmt, name) => exportRenderedAudio(fmt, name)}
      />
    </div>
  );
};
