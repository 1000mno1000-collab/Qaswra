import React, { useState } from "react";
import {
  AudioLines,
  BookmarkCheck,
  FolderKanban,
  LogIn,
  LogOut,
  Mic,
  Scissors,
  Settings,
  SlidersHorizontal,
  Sparkles,
  UserCheck,
  UserCircle2,
  Volume2,
  Wand2,
  Waves,
} from "lucide-react";
import { formatDurationMs } from "../audio/audioEditorOps";
import { LanguageBadgeButton, LanguageSelectorDialog } from "../components/Dialogs";
import { RealTimeWaveformCanvas, StudioSectionCard } from "../components/StudioComponents";
import { useStudio } from "../context/StudioContext";
import { t } from "../i18n/translations";
import { WorkspaceDestination } from "../types/studio";

export const AuthScreen: React.FC = () => {
  const {
    language,
    setLanguage,
    continueAsGuest,
    signInWithEmail,
    createAccountWithEmail,
  } = useStudio();

  const [isSignUp, setIsSignUp] = useState(false);
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [showLangDialog, setShowLangDialog] = useState(false);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const err = isSignUp
      ? createAccountWithEmail(displayName, email, password)
      : signInWithEmail(email, password);
    setErrorMsg(err);
  };

  return (
    <div className="min-h-screen flex items-center justify-center p-4">
      <div className="w-full max-w-md rounded-[28px] bg-[#131B2E]/95 border border-[#283759] p-6 space-y-5 shadow-2xl">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <img
              src="/assets/voice_pro_icon.jpg"
              alt="Sonix"
              className="w-12 h-12 rounded-[14px] object-cover border border-[#22D3EE]/50"
            />
            <div>
              <h1 className="text-xl font-extrabold text-[#F1F5F9]">
                {t(language, "app_name")}
              </h1>
              <p className="text-xs text-[#22D3EE]">{t(language, "studio_tagline")}</p>
            </div>
          </div>
          <LanguageBadgeButton
            currentLanguage={language}
            onClick={() => setShowLangDialog(true)}
          />
        </div>

        <form onSubmit={handleSubmit} className="space-y-3">
          <div className="flex rounded-[14px] bg-[#070B14] p-1 border border-[#283759]">
            <button
              type="button"
              onClick={() => {
                setIsSignUp(false);
                setErrorMsg(null);
              }}
              className={`flex-1 py-2 rounded-[10px] text-xs font-bold transition cursor-pointer ${
                !isSignUp ? "bg-[#22D3EE] text-[#070B14]" : "text-[#94A3B8]"
              }`}
            >
              {t(language, "auth_sign_in")}
            </button>
            <button
              type="button"
              onClick={() => {
                setIsSignUp(true);
                setErrorMsg(null);
              }}
              className={`flex-1 py-2 rounded-[10px] text-xs font-bold transition cursor-pointer ${
                isSignUp ? "bg-[#22D3EE] text-[#070B14]" : "text-[#94A3B8]"
              }`}
            >
              {t(language, "auth_create_account")}
            </button>
          </div>

          {isSignUp && (
            <input
              type="text"
              placeholder="الاسم الكامل / Display Name"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              className="w-full h-11 rounded-[14px] bg-[#070B14] border border-[#283759] focus:border-[#22D3EE] px-3.5 text-sm text-[#F1F5F9] outline-none"
            />
          )}

          <input
            type="email"
            placeholder="name@example.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            className="w-full h-11 rounded-[14px] bg-[#070B14] border border-[#283759] focus:border-[#22D3EE] px-3.5 text-sm text-[#F1F5F9] outline-none"
          />

          <input
            type="password"
            placeholder="••••••"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            className="w-full h-11 rounded-[14px] bg-[#070B14] border border-[#283759] focus:border-[#22D3EE] px-3.5 text-sm text-[#F1F5F9] outline-none"
          />

          {errorMsg && (
            <p className="text-xs text-[#F43F5E] bg-[#F43F5E]/10 border border-[#F43F5E]/40 rounded-[12px] px-3 py-2">
              {errorMsg}
            </p>
          )}

          <button
            type="submit"
            className="w-full h-12 rounded-[16px] bg-[#22D3EE] hover:bg-[#38BDF8] text-[#070B14] font-bold text-sm flex items-center justify-center gap-2 transition cursor-pointer"
          >
            <LogIn className="w-4 h-4" />
            <span>
              {isSignUp ? t(language, "auth_create_account") : t(language, "auth_email_signin")}
            </span>
          </button>
        </form>

        <div className="relative py-1">
          <div className="border-t border-[#283759]" />
        </div>

        <button
          type="button"
          onClick={continueAsGuest}
          className="w-full h-12 rounded-[16px] bg-[#1B2640] hover:bg-[#243252] border border-[#10B981]/50 text-[#10B981] font-bold text-sm flex items-center justify-center gap-2 transition cursor-pointer"
        >
          <UserCheck className="w-4 h-4" />
          <span>{t(language, "auth_continue_guest")}</span>
        </button>
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

export const HomeScreen: React.FC = () => {
  const {
    language,
    setLanguage,
    authSession,
    requestSignIn,
    signOut,
    hasAudio,
    durationMs,
    activeProjectName,
    dspConfig,
    playback,
    navigateTo,
  } = useStudio();

  const [showLangDialog, setShowLangDialog] = useState(false);

  const enabledEffectsCount = dspConfig.chain.filter((s) => s.enabled).length;
  const openSection = (dest: WorkspaceDestination) => navigateTo(dest);

  return (
    <div className="space-y-3.5">
      {/* Studio Hero Card */}
      <div className="relative h-44 rounded-[26px] overflow-hidden border border-[#283759]">
        <img
          src="/assets/img_studio_aura.jpg"
          alt="Studio Aura"
          className="absolute inset-0 w-full h-full object-cover"
        />
        <div
          className="absolute inset-0"
          style={{
            background:
              "linear-gradient(180deg, rgba(7,11,20,0.45) 0%, rgba(12,18,32,0.92) 70%, rgba(19,27,46,0.98) 100%)",
          }}
        />
        <div className="relative z-10 h-full p-4 flex flex-col justify-between">
          <div className="flex items-start justify-between gap-2">
            <div>
              <h1 className="text-2xl font-extrabold text-[#F1F5F9] tracking-tight">
                {t(language, "app_name")}
              </h1>
              <p className="text-xs font-medium text-[#22D3EE]">
                {t(language, "studio_tagline")}
              </p>
            </div>

            <div className="flex items-center gap-2">
              {authSession.type === "SIGNED_IN" ? (
                <button
                  type="button"
                  onClick={signOut}
                  className="h-8 px-2.5 rounded-[12px] bg-[#1B2640]/90 border border-[#10B981]/50 text-[#10B981] text-xs font-semibold flex items-center gap-1.5 cursor-pointer"
                >
                  <UserCircle2 className="w-3.5 h-3.5" />
                  <span className="max-w-[90px] truncate">{authSession.displayName}</span>
                  <LogOut className="w-3 h-3 text-[#F43F5E]" />
                </button>
              ) : (
                <button
                  type="button"
                  onClick={requestSignIn}
                  className="h-8 px-2.5 rounded-[12px] bg-[#1B2640]/90 border border-[#283759] text-[#F1F5F9] text-xs font-semibold flex items-center gap-1.5 cursor-pointer"
                >
                  <LogIn className="w-3.5 h-3.5 text-[#22D3EE]" />
                  <span>{t(language, "auth_sign_in")}</span>
                </button>
              )}

              <LanguageBadgeButton
                currentLanguage={language}
                onClick={() => setShowLangDialog(true)}
              />
            </div>
          </div>

          <RealTimeWaveformCanvas
            bars={playback.waveformBars}
            isActive={playback.isPlaying || hasAudio}
            accentColor="#22D3EE"
            heightClass="h-16"
          />
        </div>
      </div>

      {/* Active Project Summary Bar */}
      <div className="rounded-[20px] bg-[#131B2E] border border-[#283759] px-4 py-3 flex items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="text-[11px] text-[#94A3B8]">{t(language, "active_project")}</div>
          <div className="text-sm font-bold text-[#F1F5F9] truncate">{activeProjectName}</div>
        </div>
        <div className="text-end shrink-0">
          <div className="font-mono-telemetry text-sm font-bold text-[#22D3EE]">
            {hasAudio ? formatDurationMs(durationMs) : "--:--"}
          </div>
          <div className="text-[11px] text-[#10B981] font-semibold">
            {dspConfig.bypassAll
              ? t(language, "bypass_all")
              : `${enabledEffectsCount} DSP Active`}
          </div>
        </div>
      </div>

      {!hasAudio && (
        <div className="rounded-[16px] bg-[#1B2640]/60 border border-[#22D3EE]/30 px-4 py-2.5 text-xs text-[#94A3B8]">
          {t(language, "no_audio_loaded")}
        </div>
      )}

      {/* Main Studio Sections */}
      <div className="space-y-2.5">
        <StudioSectionCard
          title={t(language, "section_record_title")}
          subtitle={t(language, "section_record_subtitle")}
          icon={<Mic className="w-6 h-6" />}
          accentColor="#F43F5E"
          badgeText={hasAudio ? formatDurationMs(durationMs) : undefined}
          onClick={() => openSection({ screen: "RECORD" })}
        />

        <StudioSectionCard
          title={t(language, "section_purifier_title")}
          subtitle={t(language, "section_purifier_subtitle")}
          icon={<Wand2 className="w-6 h-6" />}
          accentColor="#10B981"
          badgeText="STFT"
          onClick={() => openSection({ screen: "VOICE_PURIFIER" })}
        />

        <StudioSectionCard
          title={t(language, "section_echo_title")}
          subtitle={t(language, "section_echo_subtitle")}
          icon={<Waves className="w-6 h-6" />}
          accentColor="#22D3EE"
          onClick={() => openSection({ screen: "ECHO_SPACE" })}
        />

        <StudioSectionCard
          title={t(language, "section_chain_title")}
          subtitle={t(language, "section_chain_subtitle")}
          icon={<SlidersHorizontal className="w-6 h-6" />}
          accentColor="#A78BFA"
          badgeText={`${enabledEffectsCount}`}
          onClick={() => openSection({ screen: "EFFECTS_CHAIN" })}
        />

        <StudioSectionCard
          title={t(language, "section_editor_title")}
          subtitle={t(language, "section_editor_subtitle")}
          icon={<Scissors className="w-6 h-6" />}
          accentColor="#F59E0B"
          onClick={() => openSection({ screen: "EDITOR" })}
        />

        <StudioSectionCard
          title={t(language, "section_presets_title")}
          subtitle={t(language, "section_presets_subtitle")}
          icon={<BookmarkCheck className="w-6 h-6" />}
          accentColor="#10B981"
          onClick={() => openSection({ screen: "PRESETS" })}
        />

        <StudioSectionCard
          title={t(language, "section_projects_title")}
          subtitle={t(language, "section_projects_subtitle")}
          icon={<FolderKanban className="w-6 h-6" />}
          accentColor="#22D3EE"
          onClick={() => openSection({ screen: "PROJECTS_EXPORT" })}
        />

        <StudioSectionCard
          title={t(language, "section_ai_title")}
          subtitle={t(language, "section_ai_subtitle")}
          icon={<Sparkles className="w-6 h-6" />}
          accentColor="#A78BFA"
          badgeText="Gemini AI"
          onClick={() => openSection({ screen: "AI_STUDIO" })}
        />

        <StudioSectionCard
          title={t(language, "section_tts_title")}
          subtitle={t(language, "section_tts_subtitle")}
          icon={<Volume2 className="w-6 h-6" />}
          accentColor="#22D3EE"
          badgeText={t(language, "tts_badge_dialects")}
          onClick={() => openSection({ screen: "TTS_STUDIO" })}
        />

        <StudioSectionCard
          title={t(language, "section_voice_transformer_title")}
          subtitle={t(language, "section_voice_transformer_subtitle")}
          icon={<AudioLines className="w-6 h-6" />}
          accentColor="#A78BFA"
          badgeText={t(language, "voice_transformer_badge")}
          onClick={() => openSection({ screen: "VOICE_TRANSFORMER" })}
        />

        <StudioSectionCard
          title={t(language, "section_settings_title")}
          subtitle={t(language, "section_settings_subtitle")}
          icon={<Settings className="w-6 h-6" />}
          accentColor="#94A3B8"
          badgeText={t(language, "settings_badge")}
          onClick={() => openSection({ screen: "SETTINGS" })}
        />
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
