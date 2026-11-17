import React, { useRef } from "react";
import {
  Check,
  FolderOpen,
  Mic,
  Pause,
  Play,
  Sparkles,
  Trash2,
  Upload,
  Wand2,
} from "lucide-react";
import { formatDurationMs } from "../audio/audioEditorOps";
import {
  RealTimeWaveformCanvas,
  StudioFocusTopBar,
  StudioParamSlider,
} from "../components/StudioComponents";
import { useStudio } from "../context/StudioContext";
import { t } from "../i18n/translations";
import { EffectType, VOICE_PURIFY_PRESETS } from "../types/studio";

export const RecordingStudioScreen: React.FC = () => {
  const {
    language,
    navigateBack,
    recorder,
    playback,
    hasAudio,
    micPermissionError,
    startRecording,
    pauseRecording,
    resumeRecording,
    finishRecording,
    discardRecording,
    importAudioFile,
    recordings,
    loadRecordingIntoStudio,
    deleteRecording,
    isBusy,
  } = useStudio();

  const fileInputRef = useRef<HTMLInputElement | null>(null);

  return (
    <div className="space-y-4">
      <StudioFocusTopBar
        title={t(language, "section_record_title")}
        subtitle={t(language, "section_record_subtitle")}
        onBack={navigateBack}
      />

      {micPermissionError && (
        <div className="rounded-[20px] bg-[#131B2E] border border-[#F43F5E]/50 p-4 space-y-2">
          <div className="text-sm font-bold text-[#F43F5E]">
            {t(language, "mic_permission_title")}
          </div>
          <p className="text-xs text-[#94A3B8]">{micPermissionError}</p>
          <button
            type="button"
            onClick={startRecording}
            className="h-10 px-4 rounded-[12px] bg-[#F43F5E] text-white text-xs font-bold cursor-pointer"
          >
            {t(language, "grant_permission")}
          </button>
        </div>
      )}

      {/* Acoustic Capture Stage */}
      <div className="rounded-[24px] bg-[#131B2E] border border-[#283759] p-5 space-y-4">
        <div className="flex items-center justify-between">
          <div>
            <span className="text-xs text-[#94A3B8]">{t(language, "mic_level")}</span>
            <div className="font-mono-telemetry text-3xl font-extrabold text-[#F1F5F9] mt-0.5">
              {formatDurationMs(recorder.elapsedMs)}
            </div>
          </div>

          <div
            className={`w-14 h-14 rounded-full flex items-center justify-center transition-all ${
              recorder.isRecording && !recorder.isPaused
                ? "bg-[#F43F5E]/25 border-2 border-[#F43F5E] animate-pulse"
                : recorder.isPaused
                  ? "bg-[#F59E0B]/20 border border-[#F59E0B]"
                  : "bg-[#1B2640] border border-[#283759]"
            }`}
          >
            <Mic
              className={`w-6 h-6 ${
                recorder.isRecording && !recorder.isPaused
                  ? "text-[#F43F5E]"
                  : recorder.isPaused
                    ? "text-[#F59E0B]"
                    : "text-[#22D3EE]"
              }`}
            />
          </div>
        </div>

        {/* Live Waveform */}
        <RealTimeWaveformCanvas
          bars={recorder.isRecording ? recorder.waveformBars : playback.waveformBars}
          isActive={recorder.isRecording || playback.isPlaying || hasAudio}
          accentColor={recorder.isRecording ? "#F43F5E" : "#22D3EE"}
          heightClass="h-28"
        />

        {/* Mic input bar */}
        <div className="space-y-1">
          <div className="w-full h-2.5 rounded-full bg-[#070B14] border border-[#283759] overflow-hidden">
            <div
              className="h-full transition-all duration-75 rounded-full"
              style={{
                width: `${Math.min(100, Math.round(recorder.micLevel * 100))}%`,
                backgroundColor:
                  recorder.micLevel > 0.85
                    ? "#F43F5E"
                    : recorder.micLevel > 0.55
                      ? "#F59E0B"
                      : "#10B981",
              }}
            />
          </div>
        </div>

        {/* Controls */}
        {!recorder.isRecording ? (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
            <button
              type="button"
              onClick={startRecording}
              disabled={isBusy}
              className="h-12 rounded-[16px] bg-[#F43F5E] hover:bg-[#FB7185] text-white font-bold text-sm flex items-center justify-center gap-2 transition cursor-pointer"
            >
              <Mic className="w-4 h-4" />
              <span>{t(language, "record_start")}</span>
            </button>

            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={isBusy}
              className="h-12 rounded-[16px] bg-[#1B2640] hover:bg-[#243252] border border-[#22D3EE]/50 text-[#22D3EE] font-bold text-sm flex items-center justify-center gap-2 transition cursor-pointer"
            >
              <Upload className="w-4 h-4" />
              <span>{t(language, "import_audio")}</span>
            </button>
          </div>
        ) : (
          <div className="space-y-2.5">
            <div className="grid grid-cols-2 gap-2.5">
              <button
                type="button"
                onClick={recorder.isPaused ? resumeRecording : pauseRecording}
                className="h-11 rounded-[14px] bg-[#F59E0B]/20 border border-[#F59E0B] text-[#F59E0B] font-bold text-xs flex items-center justify-center gap-1.5 cursor-pointer"
              >
                {recorder.isPaused ? (
                  <>
                    <Play className="w-4 h-4" />
                    <span>{t(language, "record_resume")}</span>
                  </>
                ) : (
                  <>
                    <Pause className="w-4 h-4" />
                    <span>{t(language, "record_pause")}</span>
                  </>
                )}
              </button>

              <button
                type="button"
                onClick={discardRecording}
                className="h-11 rounded-[14px] bg-[#1B2640] border border-[#F43F5E]/50 text-[#F43F5E] font-bold text-xs flex items-center justify-center gap-1.5 cursor-pointer"
              >
                <Trash2 className="w-4 h-4" />
                <span>{t(language, "record_discard")}</span>
              </button>
            </div>

            <button
              type="button"
              onClick={finishRecording}
              className="w-full h-12 rounded-[16px] bg-[#10B981] hover:bg-[#34D399] text-[#070B14] font-bold text-sm flex items-center justify-center gap-2 cursor-pointer"
            >
              <Check className="w-4 h-4" />
              <span>{t(language, "record_finish")}</span>
            </button>
          </div>
        )}

        <input
          ref={fileInputRef}
          type="file"
          accept="audio/*"
          className="hidden"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) importAudioFile(file);
            e.target.value = "";
          }}
        />
      </div>

      {/* Saved / Indexed Recordings Archive */}
      <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-3">
        <h2 className="text-sm font-bold text-[#F1F5F9]">
          أرشيف التسجيلات والملفات ({recordings.length})
        </h2>
        {recordings.length === 0 ? (
          <p className="text-xs text-[#64748B]">
            سيتم حفظ جميع تسجيلاتك وملفاتك المستوردة هنا للرجوع إليها في أي وقت.
          </p>
        ) : (
          <div className="space-y-2">
            {recordings.map((rec) => (
              <div
                key={rec.id}
                className="rounded-[16px] bg-[#070B14] border border-[#283759] px-3.5 py-2.5 flex items-center justify-between gap-2"
              >
                <div className="min-w-0">
                  <div className="text-sm font-bold text-[#F1F5F9] truncate">{rec.title}</div>
                  <div className="text-[11px] text-[#94A3B8] font-mono-telemetry">
                    {formatDurationMs(rec.durationMs)} • {rec.sourceType}
                  </div>
                </div>
                <div className="flex items-center gap-1.5 shrink-0">
                  <button
                    type="button"
                    onClick={() => loadRecordingIntoStudio(rec)}
                    className="h-9 px-3 rounded-[10px] bg-[#22D3EE]/15 border border-[#22D3EE]/50 text-[#22D3EE] text-xs font-bold flex items-center gap-1 cursor-pointer"
                  >
                    <FolderOpen className="w-3.5 h-3.5" />
                    <span>{t(language, "load")}</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => deleteRecording(rec)}
                    className="h-9 w-9 rounded-[10px] bg-[#1B2640] text-[#F43F5E] flex items-center justify-center cursor-pointer"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export const VoicePurifierScreen: React.FC = () => {
  const {
    language,
    navigateBack,
    dspConfig,
    toggleEffectEnabled,
    updateDspConfig,
    applyVoicePurifyPreset,
    purifyAudioOffline,
    purifyProgress,
    playback,
    hasAudio,
    isBusy,
  } = useStudio();

  const nr = dspConfig.noiseReduction;
  const nrSlot = dspConfig.chain.find((s) => s.type === EffectType.NOISE_REDUCTION);
  const isEnabled = Boolean(nrSlot?.enabled);

  const updateNr = (patch: Partial<typeof nr>) => {
    updateDspConfig((prev) => ({
      ...prev,
      bypassAll: false,
      chain: prev.chain.map((s) =>
        s.type === EffectType.NOISE_REDUCTION ? { ...s, enabled: true } : s
      ),
      noiseReduction: { ...prev.noiseReduction, ...patch },
    }));
  };

  return (
    <div className="space-y-4">
      <StudioFocusTopBar
        title={t(language, "section_purifier_title")}
        subtitle={t(language, "section_purifier_subtitle")}
        onBack={navigateBack}
      />

      {/* Live Purifier Card & Suppression Meter */}
      <div className="rounded-[22px] bg-[#131B2E] border border-[#10B981]/50 p-4 space-y-3">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <Wand2 className="w-5 h-5 text-[#10B981]" />
            <span className="text-sm font-bold text-[#F1F5F9]">
              التنقية الحية الفورية (Real-Time)
            </span>
          </div>
          <input
            type="checkbox"
            checked={isEnabled}
            onChange={(e) => toggleEffectEnabled(EffectType.NOISE_REDUCTION, e.target.checked)}
            className="w-5 h-5 accent-[#10B981] cursor-pointer"
          />
        </div>

        <div className="flex items-center justify-between text-xs">
          <span className="text-[#94A3B8]">مستوى قمع الضوضاء الحالي:</span>
          <span className="font-mono-telemetry font-bold text-[#10B981]">
            -{playback.noiseSuppressionDb.toFixed(1)} dB
          </span>
        </div>
        <div className="w-full h-2.5 rounded-full bg-[#070B14] border border-[#283759] overflow-hidden">
          <div
            className="h-full bg-[#10B981] transition-all duration-100 rounded-full"
            style={{ width: `${Math.min(100, (playback.noiseSuppressionDb / 32) * 100)}%` }}
          />
        </div>

        <label className="flex items-center justify-between pt-1 cursor-pointer">
          <span className="text-xs text-[#F1F5F9]">
            تفعيل المنقّي الطيفي الذكي (512-pt STFT Wiener)
          </span>
          <input
            type="checkbox"
            checked={nr.spectralCleanerEnabled}
            onChange={(e) => updateNr({ spectralCleanerEnabled: e.target.checked })}
            className="w-4 h-4 accent-[#22D3EE]"
          />
        </label>
      </div>

      {/* 4 Purify Presets */}
      <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-2.5">
        <h2 className="text-sm font-bold text-[#22D3EE]">أوضاع التنقية السريعة</h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
          {VOICE_PURIFY_PRESETS.map((preset) => (
            <button
              key={preset.id}
              type="button"
              onClick={() => applyVoicePurifyPreset(preset)}
              className="rounded-[16px] bg-[#070B14] hover:bg-[#1B2640] border border-[#283759] hover:border-[#10B981] p-3 text-start transition cursor-pointer"
            >
              <div className="text-sm font-bold text-[#F1F5F9]">
                {language === "en" ? preset.titleEn : preset.titleAr}
              </div>
              <div className="text-[11px] text-[#94A3B8] mt-0.5">
                {language === "en" ? preset.subtitleEn : preset.subtitleAr}
              </div>
            </button>
          ))}
        </div>
      </div>

      {/* Sliders */}
      <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-2">
        <StudioParamSlider
          label="قوة العزل الطيفي (Spectral Strength)"
          value={nr.spectralStrength}
          min={0}
          max={1}
          step={0.01}
          formattedValue={`${Math.round(nr.spectralStrength * 100)}%`}
          accentColor="#10B981"
          onChange={(v) => updateNr({ spectralStrength: v })}
        />
        <StudioParamSlider
          label="عتبة البوابة الصوتية (Noise Gate Threshold)"
          value={nr.thresholdDb}
          min={-75}
          max={-15}
          step={0.5}
          formattedValue={`${nr.thresholdDb.toFixed(1)} dB`}
          accentColor="#22D3EE"
          onChange={(v) => updateNr({ thresholdDb: v })}
        />
        <StudioParamSlider
          label="مقدار الخفض (Noise Reduction)"
          value={nr.reductionDb}
          min={0}
          max={36}
          step={0.5}
          formattedValue={`-${nr.reductionDb.toFixed(1)} dB`}
          accentColor="#22D3EE"
          onChange={(v) => updateNr({ reductionDb: v })}
        />
        <StudioParamSlider
          label="فلتر إزالة الهمهمة والهواء (High-Pass)"
          value={nr.highPassHz}
          min={30}
          max={260}
          step={1}
          formattedValue={`${Math.round(nr.highPassHz)} Hz`}
          accentColor="#F59E0B"
          onChange={(v) => updateNr({ highPassHz: v })}
        />
        <StudioParamSlider
          label="إزالة الصفير وتنعيم السين (De-Esser)"
          value={nr.deEssAmount}
          min={0}
          max={1}
          step={0.01}
          formattedValue={`${Math.round(nr.deEssAmount * 100)}%`}
          accentColor="#A78BFA"
          onChange={(v) => updateNr({ deEssAmount: v })}
        />
        <StudioParamSlider
          label="تعزيز وضوح الحروف (Clarity Boost)"
          value={nr.clarityBoostDb}
          min={-2}
          max={10}
          step={0.2}
          formattedValue={`+${nr.clarityBoostDb.toFixed(1)} dB`}
          accentColor="#10B981"
          onChange={(v) => updateNr({ clarityBoostDb: v })}
        />
      </div>

      {/* Deep Offline Clean */}
      <div className="rounded-[22px] bg-[#131B2E] border border-[#22D3EE]/50 p-4 space-y-3">
        {purifyProgress !== null && (
          <div className="w-full h-2 rounded-full bg-[#070B14] overflow-hidden">
            <div
              className="h-full bg-[#10B981] transition-all"
              style={{ width: `${Math.round(purifyProgress * 100)}%` }}
            />
          </div>
        )}
        <button
          type="button"
          onClick={purifyAudioOffline}
          disabled={!hasAudio || isBusy}
          className="w-full h-12 rounded-[16px] bg-[#10B981] hover:bg-[#34D399] disabled:bg-[#1B2640] disabled:text-[#64748B] text-[#070B14] font-bold text-sm flex items-center justify-center gap-2 transition cursor-pointer"
        >
          <Sparkles className="w-4 h-4" />
          <span>تطبيق التنقية العميقة وحفظ النتيجة بالمقطع</span>
        </button>
      </div>
    </div>
  );
};
