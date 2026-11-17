import React, { useState } from "react";
import {
  AudioLines,
  CheckCircle2,
  Copy,
  ExternalLink,
  FileText,
  Globe,
  Loader2,
  Play,
  Search,
  Square,
  Volume2,
  Wand2,
} from "lucide-react";
import { LanguageSelectorDialog } from "../components/Dialogs";
import {
  StudioFocusTopBar,
  StudioParamSlider,
  StudioSectionCard,
} from "../components/StudioComponents";
import { useStudio } from "../context/StudioContext";
import { APP_LANGUAGES, t } from "../i18n/translations";
import { AiToolType, TTS_DIALECTS, VOICE_CHARACTER_PRESETS } from "../types/studio";

export const AiStudioScreen: React.FC = () => {
  const { language, navigateBack, navigateTo } = useStudio();

  return (
    <div className="space-y-4">
      <StudioFocusTopBar
        title={t(language, "section_ai_title")}
        subtitle={t(language, "section_ai_subtitle")}
        onBack={navigateBack}
      />

      <div className="space-y-3">
        <StudioSectionCard
          title="تفريغ الصوت إلى نص (Gemini AI)"
          subtitle="تحويل تسجيلاتك الصوتية إلى نص عربي أو إنجليزي بدقة عالية"
          icon={<FileText className="w-6 h-6" />}
          accentColor="#22D3EE"
          badgeText="Transcribe"
          onClick={() => navigateTo({ screen: "AI_TOOL_DETAIL", tool: AiToolType.TRANSCRIBE })}
        />

        <StudioSectionCard
          title="مهندس الماسترينغ الذكي (Smart Auto-Master)"
          subtitle="تحليل فوري للضجيج والطيف وتوازن الترددات مع ضبط تلقائي للـ DSP"
          icon={<Wand2 className="w-6 h-6" />}
          accentColor="#10B981"
          badgeText="Offline DSP"
          onClick={() => navigateTo({ screen: "AI_TOOL_DETAIL", tool: AiToolType.SMART_MASTER })}
        />

        <StudioSectionCard
          title="مساعد الاستوديو والبحث المباشر"
          subtitle="اسأل عن هندسة الصوت وإعدادات EQ والضاغط مع مصادر ويب موثقة"
          icon={<Search className="w-6 h-6" />}
          accentColor="#A78BFA"
          badgeText="Search"
          onClick={() => navigateTo({ screen: "AI_TOOL_DETAIL", tool: AiToolType.SEARCH })}
        />
      </div>
    </div>
  );
};

export const AiToolDetailScreen: React.FC<{ tool: AiToolType }> = ({ tool }) => {
  const {
    navigateBack,
    hasAudio,
    transcriptionState,
    transcribeActiveAudio,
    clearTranscription,
    smartMasterState,
    runSmartAutoMaster,
    searchAssistantState,
    askSearchAssistant,
    isBusy,
  } = useStudio();

  const [query, setQuery] = useState("");
  const [copied, setCopied] = useState(false);

  const title =
    tool === AiToolType.TRANSCRIBE
      ? "تفريغ الصوت إلى نص"
      : tool === AiToolType.SMART_MASTER
        ? "مهندس الماسترينغ الذكي"
        : "مساعد الاستوديو بالبحث المباشر";

  const copyText = async (txt: string) => {
    try {
      await navigator.clipboard.writeText(txt);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      // ignore
    }
  };

  return (
    <div className="space-y-4">
      <StudioFocusTopBar title={title} onBack={navigateBack} />

      {tool === AiToolType.TRANSCRIBE && (
        <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-3">
          <div className="flex gap-2">
            <button
              type="button"
              disabled={!hasAudio || transcriptionState.isLoading}
              onClick={transcribeActiveAudio}
              className="flex-1 h-11 rounded-[14px] bg-[#22D3EE] hover:bg-[#38BDF8] disabled:bg-[#1B2640] disabled:text-[#64748B] text-[#070B14] font-bold text-xs flex items-center justify-center gap-2 cursor-pointer"
            >
              {transcriptionState.isLoading ? (
                <Loader2 className="w-4 h-4 animate-spin" />
              ) : (
                <FileText className="w-4 h-4" />
              )}
              <span>بدء التفريغ الصوتي الآن</span>
            </button>
            {transcriptionState.outputText && (
              <button
                type="button"
                onClick={clearTranscription}
                className="h-11 px-4 rounded-[14px] bg-[#1B2640] text-[#94A3B8] text-xs font-bold cursor-pointer"
              >
                مسح
              </button>
            )}
          </div>

          {transcriptionState.errorMessage && (
            <div className="rounded-[12px] bg-[#F43F5E]/10 border border-[#F43F5E]/40 p-3 text-xs text-[#F43F5E]">
              {transcriptionState.errorMessage}
            </div>
          )}

          {transcriptionState.outputText && (
            <div className="rounded-[16px] bg-[#070B14] border border-[#283759] p-4 space-y-2">
              <div className="flex justify-between items-center">
                <span className="text-xs font-bold text-[#22D3EE]">النص المفرّغ</span>
                <button
                  type="button"
                  onClick={() => copyText(transcriptionState.outputText)}
                  className="text-xs text-[#10B981] flex items-center gap-1 cursor-pointer"
                >
                  <Copy className="w-3.5 h-3.5" />
                  <span>{copied ? "تم النسخ!" : "نسخ"}</span>
                </button>
              </div>
              <p className="text-sm text-[#F1F5F9] whitespace-pre-wrap leading-relaxed">
                {transcriptionState.outputText}
              </p>
            </div>
          )}
        </div>
      )}

      {tool === AiToolType.SMART_MASTER && (
        <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-4">
          <button
            type="button"
            disabled={!hasAudio || isBusy}
            onClick={runSmartAutoMaster}
            className="w-full h-12 rounded-[16px] bg-[#10B981] hover:bg-[#34D399] disabled:bg-[#1B2640] disabled:text-[#64748B] text-[#070B14] font-bold text-sm flex items-center justify-center gap-2 cursor-pointer"
          >
            <Wand2 className="w-4 h-4" />
            <span>تحليل الصوت وضبط الماسترينغ تلقائياً</span>
          </button>

          {smartMasterState && (
            <div className="space-y-3">
              <div className="rounded-[16px] bg-[#070B14] border border-[#10B981]/40 p-3.5">
                <div className="text-xs text-[#94A3B8]">التشخيص الصوتي:</div>
                <div className="text-sm font-bold text-[#10B981] mt-0.5">
                  {smartMasterState.detectedProfile}
                </div>
              </div>

              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-center font-mono-telemetry">
                <div className="rounded-[12px] bg-[#070B14] border border-[#283759] p-2.5">
                  <div className="text-[10px] text-[#94A3B8]">RMS</div>
                  <div className="text-sm font-bold text-[#22D3EE]">
                    {smartMasterState.rmsDb.toFixed(1)} dB
                  </div>
                </div>
                <div className="rounded-[12px] bg-[#070B14] border border-[#283759] p-2.5">
                  <div className="text-[10px] text-[#94A3B8]">Peak</div>
                  <div className="text-sm font-bold text-[#F59E0B]">
                    {smartMasterState.peakDb.toFixed(1)} dB
                  </div>
                </div>
                <div className="rounded-[12px] bg-[#070B14] border border-[#283759] p-2.5">
                  <div className="text-[10px] text-[#94A3B8]">Crest</div>
                  <div className="text-sm font-bold text-[#A78BFA]">
                    {smartMasterState.crestFactorDb.toFixed(1)} dB
                  </div>
                </div>
                <div className="rounded-[12px] bg-[#070B14] border border-[#283759] p-2.5">
                  <div className="text-[10px] text-[#94A3B8]">Noise Floor</div>
                  <div className="text-sm font-bold text-[#10B981]">
                    {smartMasterState.noiseFloorDb.toFixed(1)} dB
                  </div>
                </div>
              </div>

              <div className="space-y-1.5">
                <div className="text-xs font-bold text-[#F1F5F9]">الإجراءات المطبّقة:</div>
                {smartMasterState.appliedActions.map((act, idx) => (
                  <div
                    key={idx}
                    className="rounded-[12px] bg-[#070B14] border border-[#283759] px-3 py-2 text-xs text-[#F1F5F9] flex items-center gap-2"
                  >
                    <CheckCircle2 className="w-4 h-4 text-[#10B981] shrink-0" />
                    <span>{act}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {tool === AiToolType.SEARCH && (
        <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-3">
          <form
            onSubmit={(e) => {
              e.preventDefault();
              askSearchAssistant(query);
            }}
            className="flex gap-2"
          >
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="مثال: أفضل إعدادات EQ و Compressor لصوت البودكاست..."
              className="flex-1 h-11 rounded-[12px] bg-[#070B14] border border-[#283759] focus:border-[#A78BFA] px-3 text-sm text-[#F1F5F9] outline-none"
            />
            <button
              type="submit"
              disabled={!query.trim() || searchAssistantState.isLoading}
              className="h-11 px-4 rounded-[12px] bg-[#A78BFA] hover:bg-[#C4B5FD] disabled:bg-[#1B2640] text-[#070B14] font-bold text-xs flex items-center gap-1.5 cursor-pointer"
            >
              {searchAssistantState.isLoading ? (
                <Loader2 className="w-4 h-4 animate-spin" />
              ) : (
                <Search className="w-4 h-4" />
              )}
              <span>بحث</span>
            </button>
          </form>

          {searchAssistantState.errorMessage && (
            <div className="rounded-[12px] bg-[#F43F5E]/10 border border-[#F43F5E]/40 p-3 text-xs text-[#F43F5E]">
              {searchAssistantState.errorMessage}
            </div>
          )}

          {searchAssistantState.outputText && (
            <div className="rounded-[16px] bg-[#070B14] border border-[#283759] p-4 space-y-3">
              <p className="text-sm text-[#F1F5F9] whitespace-pre-wrap leading-relaxed">
                {searchAssistantState.outputText}
              </p>

              {searchAssistantState.sources.length > 0 && (
                <div className="pt-2 border-t border-[#283759] space-y-1.5">
                  <div className="text-xs font-bold text-[#A78BFA]">المصادر الموثقة:</div>
                  <div className="flex flex-wrap gap-2">
                    {searchAssistantState.sources.map((src) => (
                      <a
                        key={src.uri}
                        href={src.uri}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="px-2.5 py-1.5 rounded-[10px] bg-[#131B2E] border border-[#283759] hover:border-[#22D3EE] text-xs text-[#22D3EE] flex items-center gap-1.5"
                      >
                        <ExternalLink className="w-3 h-3" />
                        <span className="truncate max-w-[200px]">{src.title || src.domain}</span>
                      </a>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export const TextToSpeechStudioScreen: React.FC = () => {
  const {
    language,
    navigateBack,
    hasAudio,
    isBusy,
    ttsUiState,
    previewTtsSpeech,
    stopTtsPreview,
    generateTtsToStudio,
  } = useStudio();

  const [selectedDialect, setSelectedDialect] = useState(TTS_DIALECTS[0]);
  const [text, setText] = useState(TTS_DIALECTS[0].sampleText);
  const [speechRate, setSpeechRate] = useState(TTS_DIALECTS[0].defaultRate);
  const [pitch, setPitch] = useState(TTS_DIALECTS[0].defaultPitch);
  const [append, setAppend] = useState(false);

  return (
    <div className="space-y-4">
      <StudioFocusTopBar
        title={t(language, "section_tts_title")}
        subtitle={t(language, "section_tts_subtitle")}
        onBack={navigateBack}
      />

      <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-4">
        <div className="space-y-1.5">
          <div className="flex items-center justify-between">
            <label className="text-xs font-bold text-[#22D3EE]">
              {t(language, "tts_input_label")}
            </label>
            <button
              type="button"
              onClick={() => setText(selectedDialect.sampleText)}
              className="text-xs text-[#A78BFA] hover:underline cursor-pointer"
            >
              {t(language, "tts_sample_button")}
            </button>
          </div>
          <textarea
            rows={4}
            value={text}
            onChange={(e) => setText(e.target.value)}
            className="w-full rounded-[14px] bg-[#070B14] border border-[#283759] focus:border-[#22D3EE] p-3 text-sm text-[#F1F5F9] outline-none"
          />
        </div>

        <div className="space-y-2">
          <div className="text-xs font-bold text-[#94A3B8]">
            {t(language, "tts_dialect_section")}
          </div>
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
            {TTS_DIALECTS.map((d) => {
              const selected = d.id === selectedDialect.id;
              return (
                <button
                  key={d.id}
                  type="button"
                  onClick={() => {
                    const wasSample =
                      !text.trim() || TTS_DIALECTS.some((x) => x.sampleText === text);
                    setSelectedDialect(d);
                    setSpeechRate(d.defaultRate);
                    setPitch(d.defaultPitch);
                    if (wasSample) setText(d.sampleText);
                  }}
                  className={`px-3 py-2.5 rounded-[12px] text-xs font-bold border text-start transition cursor-pointer ${
                    selected
                      ? "bg-[#22D3EE]/20 border-[#22D3EE] text-[#22D3EE]"
                      : "bg-[#070B14] border-[#283759] text-[#F1F5F9]"
                  }`}
                >
                  {t(language, d.titleKey)}
                </button>
              );
            })}
          </div>
        </div>

        <StudioParamSlider
          label={t(language, "tts_speed_label")}
          value={speechRate}
          min={0.55}
          max={1.65}
          step={0.05}
          formattedValue={`${speechRate.toFixed(2)}x`}
          onChange={setSpeechRate}
        />

        <StudioParamSlider
          label={t(language, "tts_pitch_label")}
          value={pitch}
          min={0.65}
          max={1.45}
          step={0.05}
          formattedValue={`${pitch.toFixed(2)}x`}
          accentColor="#A78BFA"
          onChange={setPitch}
        />

        {hasAudio && (
          <label className="flex items-center justify-between text-xs text-[#94A3B8] cursor-pointer">
            <span>{t(language, "tts_append_toggle")}</span>
            <input
              type="checkbox"
              checked={append}
              onChange={(e) => setAppend(e.target.checked)}
              className="w-4 h-4 accent-[#10B981]"
            />
          </label>
        )}

        {ttsUiState.errorMessage && (
          <p className="text-xs text-[#F43F5E] bg-[#F43F5E]/10 border border-[#F43F5E]/40 rounded-[12px] p-2.5">
            {ttsUiState.errorMessage}
          </p>
        )}

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
          <button
            type="button"
            disabled={!text.trim() || ttsUiState.isGenerating}
            onClick={() =>
              ttsUiState.isPreviewing
                ? stopTtsPreview()
                : previewTtsSpeech(text, selectedDialect, speechRate, pitch)
            }
            className="h-12 rounded-[14px] border border-[#22D3EE]/60 text-[#22D3EE] font-bold text-xs flex items-center justify-center gap-2 cursor-pointer"
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
            disabled={!text.trim() || ttsUiState.isGenerating || isBusy}
            onClick={() =>
              generateTtsToStudio(
                text,
                selectedDialect,
                speechRate,
                pitch,
                hasAudio && append
              )
            }
            className="h-12 rounded-[14px] bg-[#10B981] hover:bg-[#34D399] disabled:bg-[#1B2640] disabled:text-[#64748B] text-[#070B14] font-bold text-xs flex items-center justify-center gap-2 cursor-pointer"
          >
            {ttsUiState.isGenerating ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : (
              <Volume2 className="w-4 h-4" />
            )}
            <span>{t(language, "tts_insert_button")}</span>
          </button>
        </div>
      </div>
    </div>
  );
};

export const VoiceTransformerScreen: React.FC = () => {
  const {
    language,
    navigateBack,
    hasAudio,
    isBusy,
    purifyProgress,
    transformVoiceOffline,
  } = useStudio();

  const [selectedPreset, setSelectedPreset] = useState(VOICE_CHARACTER_PRESETS[0]);
  const [pitchSemitones, setPitchSemitones] = useState(
    VOICE_CHARACTER_PRESETS[0].pitchSemitones
  );
  const [formantShift, setFormantShift] = useState(
    VOICE_CHARACTER_PRESETS[0].formantShift
  );
  const [warmthDb, setWarmthDb] = useState(VOICE_CHARACTER_PRESETS[0].warmthDb);
  const [presenceDb, setPresenceDb] = useState(VOICE_CHARACTER_PRESETS[0].presenceDb);
  const [mix, setMix] = useState(1.0);

  return (
    <div className="space-y-4">
      <StudioFocusTopBar
        title={t(language, "section_voice_transformer_title")}
        subtitle={t(language, "section_voice_transformer_subtitle")}
        onBack={navigateBack}
      />

      <div className="space-y-2.5">
        {VOICE_CHARACTER_PRESETS.map((preset) => {
          const selected = preset.id === selectedPreset.id;
          return (
            <button
              key={preset.id}
              type="button"
              onClick={() => {
                setSelectedPreset(preset);
                setPitchSemitones(preset.pitchSemitones);
                setFormantShift(preset.formantShift);
                setWarmthDb(preset.warmthDb);
                setPresenceDb(preset.presenceDb);
              }}
              className={`w-full rounded-[18px] p-3.5 border text-start flex items-center justify-between gap-3 transition cursor-pointer ${
                selected
                  ? "bg-[#A78BFA]/15 border-[#A78BFA]"
                  : "bg-[#131B2E] border-[#283759] hover:border-[#384C7A]"
              }`}
            >
              <div>
                <div
                  className={`text-sm font-bold ${
                    selected ? "text-[#A78BFA]" : "text-[#F1F5F9]"
                  }`}
                >
                  {t(language, preset.titleKey)}
                </div>
                <div className="text-xs text-[#94A3B8] mt-0.5">
                  {t(language, preset.descKey)}
                </div>
              </div>
              {selected && <CheckCircle2 className="w-5 h-5 text-[#A78BFA] shrink-0" />}
            </button>
          );
        })}
      </div>

      <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-2">
        <StudioParamSlider
          label={t(language, "voice_pitch_semitones")}
          value={pitchSemitones}
          min={-9}
          max={10}
          step={0.1}
          formattedValue={`${pitchSemitones >= 0 ? "+" : ""}${pitchSemitones.toFixed(1)} st`}
          accentColor="#22D3EE"
          onChange={setPitchSemitones}
        />
        <StudioParamSlider
          label={t(language, "voice_formant_shift")}
          value={formantShift}
          min={0.72}
          max={1.38}
          step={0.01}
          formattedValue={`${formantShift.toFixed(2)}x`}
          accentColor="#A78BFA"
          onChange={setFormantShift}
        />
        <StudioParamSlider
          label={t(language, "voice_warmth")}
          value={warmthDb}
          min={-6}
          max={9}
          step={0.2}
          formattedValue={`${warmthDb >= 0 ? "+" : ""}${warmthDb.toFixed(1)} dB`}
          accentColor="#F59E0B"
          onChange={setWarmthDb}
        />
        <StudioParamSlider
          label={t(language, "voice_presence")}
          value={presenceDb}
          min={-6}
          max={9}
          step={0.2}
          formattedValue={`${presenceDb >= 0 ? "+" : ""}${presenceDb.toFixed(1)} dB`}
          accentColor="#10B981"
          onChange={setPresenceDb}
        />
        <StudioParamSlider
          label={t(language, "voice_mix")}
          value={mix}
          min={0.2}
          max={1}
          step={0.05}
          formattedValue={`${Math.round(mix * 100)}%`}
          accentColor="#22D3EE"
          onChange={setMix}
        />

        {purifyProgress !== null && (
          <div className="w-full h-2 rounded-full bg-[#070B14] overflow-hidden mt-2">
            <div
              className="h-full bg-[#A78BFA] transition-all"
              style={{ width: `${Math.round(purifyProgress * 100)}%` }}
            />
          </div>
        )}

        <button
          type="button"
          disabled={!hasAudio || isBusy}
          onClick={() =>
            transformVoiceOffline(
              pitchSemitones,
              formantShift,
              warmthDb,
              presenceDb,
              mix,
              t(language, selectedPreset.titleKey)
            )
          }
          className="w-full h-12 mt-2 rounded-[16px] bg-[#A78BFA] hover:bg-[#C4B5FD] disabled:bg-[#1B2640] disabled:text-[#64748B] text-[#070B14] font-bold text-sm flex items-center justify-center gap-2 cursor-pointer"
        >
          <AudioLines className="w-4 h-4" />
          <span>{t(language, "voice_apply_transform")}</span>
        </button>
      </div>
    </div>
  );
};

export const SettingsScreen: React.FC = () => {
  const {
    language,
    setLanguage,
    navigateBack,
    autoLoopOnPlayback,
    setAutoLoopOnPlayback,
  } = useStudio();

  const [showLangDialog, setShowLangDialog] = useState(false);
  const currentLangObj = APP_LANGUAGES.find((l) => l.tag === language) ?? APP_LANGUAGES[0];

  return (
    <div className="space-y-4">
      <StudioFocusTopBar
        title={t(language, "section_settings_title")}
        subtitle={t(language, "section_settings_subtitle")}
        onBack={navigateBack}
      />

      <button
        type="button"
        onClick={() => setShowLangDialog(true)}
        className="w-full rounded-[22px] bg-[#131B2E] border border-[#283759] hover:border-[#22D3EE] p-4 flex items-center justify-between gap-3 text-start cursor-pointer"
      >
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-[14px] bg-[#22D3EE]/15 border border-[#22D3EE]/40 text-[#22D3EE] flex items-center justify-center">
            <Globe className="w-5 h-5" />
          </div>
          <div>
            <div className="text-sm font-bold text-[#F1F5F9]">
              {t(language, "app_language_title")}
            </div>
            <div className="text-xs text-[#94A3B8]">{currentLangObj.nativeName}</div>
          </div>
        </div>
        <span className="px-3 py-1 rounded-[10px] bg-[#22D3EE]/15 text-[#22D3EE] text-xs font-bold">
          {currentLangObj.shortBadge}
        </span>
      </button>

      <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-3">
        <div className="text-sm font-bold text-[#F1F5F9]">
          {t(language, "settings_playback_section")}
        </div>

        <label className="flex items-center justify-between gap-3 cursor-pointer">
          <div>
            <div className="text-sm font-medium text-[#F1F5F9]">
              {t(language, "loop_repeat")}
            </div>
            <div className="text-xs text-[#94A3B8]">
              إعادة تشغيل المقطع الصوتي تلقائياً عند نهايته
            </div>
          </div>
          <input
            type="checkbox"
            checked={autoLoopOnPlayback}
            onChange={(e) => setAutoLoopOnPlayback(e.target.checked)}
            className="w-5 h-5 accent-[#10B981]"
          />
        </label>
      </div>

      <LanguageSelectorDialog
        open={showLangDialog}
        currentLanguage={language}
        onSelect={setLanguage}
        onClose={() => setShowLangDialog(false)}
      />
    </div>
  );
};
