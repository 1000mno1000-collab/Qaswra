import React, { useEffect, useRef } from "react";
import {
  ArrowLeft,
  ArrowRight,
  ChevronLeft,
  ChevronRight,
  GitCompare,
  Pause,
  Play,
  Repeat,
  Square,
} from "lucide-react";
import { formatDurationMs } from "../audio/audioEditorOps";
import { useStudio } from "../context/StudioContext";
import { t } from "../i18n/translations";

export const StudioFocusTopBar: React.FC<{
  title: string;
  subtitle?: string;
  onBack: () => void;
}> = ({ title, subtitle, onBack }) => {
  const { language } = useStudio();
  const isRtl = language === "ar" || language === "fa";

  return (
    <header className="w-full rounded-[20px] bg-[#131B2E]/90 border border-[#283759] px-3 py-2.5 flex items-center gap-3 backdrop-blur-md">
      <button
        type="button"
        onClick={onBack}
        className="h-10 px-3 rounded-[14px] bg-[#1B2640] hover:bg-[#243252] border border-[#283759] text-[#22D3EE] flex items-center gap-1.5 text-sm font-semibold transition cursor-pointer shrink-0"
      >
        {isRtl ? <ArrowRight className="w-4 h-4" /> : <ArrowLeft className="w-4 h-4" />}
        <span>{t(language, "back")}</span>
      </button>
      <div className="flex-1 min-w-0">
        <h1 className="text-base sm:text-lg font-bold text-[#F1F5F9] truncate">{title}</h1>
        {subtitle && (
          <p className="text-xs text-[#94A3B8] truncate">{subtitle}</p>
        )}
      </div>
    </header>
  );
};

export const StudioSectionCard: React.FC<{
  title: string;
  subtitle: string;
  icon: React.ReactNode;
  accentColor?: string;
  badgeText?: string;
  onClick: () => void;
}> = ({ title, subtitle, icon, accentColor = "#22D3EE", badgeText, onClick }) => {
  const { language } = useStudio();
  const isRtl = language === "ar" || language === "fa";

  return (
    <button
      type="button"
      onClick={onClick}
      className="w-full text-start rounded-[22px] bg-[#131B2E] hover:bg-[#1B2640]/90 border border-[#283759] hover:border-[#384C7A] p-4 flex items-center gap-3.5 transition-all cursor-pointer group"
    >
      <div
        className="w-12 h-12 rounded-[16px] flex items-center justify-center shrink-0"
        style={{
          backgroundColor: `${accentColor}1F`,
          border: `1px solid ${accentColor}59`,
          color: accentColor,
        }}
      >
        {icon}
      </div>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-[15px] font-bold text-[#F1F5F9]">{title}</span>
          {badgeText && (
            <span
              className="px-2 py-0.5 rounded-[10px] text-[11px] font-semibold"
              style={{
                backgroundColor: `${accentColor}28`,
                color: accentColor,
              }}
            >
              {badgeText}
            </span>
          )}
        </div>
        <p className="text-xs text-[#94A3B8] mt-0.5 line-clamp-2">{subtitle}</p>
      </div>
      <div className="text-[#64748B] group-hover:text-[#F1F5F9] transition shrink-0">
        {isRtl ? <ChevronLeft className="w-5 h-5" /> : <ChevronRight className="w-5 h-5" />}
      </div>
    </button>
  );
};

export const StudioParamSlider: React.FC<{
  label: string;
  value: number;
  min: number;
  max: number;
  step?: number;
  formattedValue: string;
  accentColor?: string;
  onChange: (v: number) => void;
}> = ({
  label,
  value,
  min,
  max,
  step = 0.01,
  formattedValue,
  accentColor = "#22D3EE",
  onChange,
}) => {
  return (
    <div className="w-full space-y-1.5 py-1">
      <div className="flex items-center justify-between gap-2 text-xs">
        <span className="text-[#F1F5F9] font-medium">{label}</span>
        <span
          className="font-mono-telemetry font-bold px-2 py-0.5 rounded-md bg-[#070B14] border border-[#283759]"
          style={{ color: accentColor }}
        >
          {formattedValue}
        </span>
      </div>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(parseFloat(e.target.value))}
        className="w-full studio-slider"
        style={{ "--slider-accent": accentColor } as React.CSSProperties}
      />
    </div>
  );
};

export const RealTimeWaveformCanvas: React.FC<{
  bars: number[];
  isActive: boolean;
  accentColor?: string;
  heightClass?: string;
}> = ({ bars, isActive, accentColor = "#22D3EE", heightClass = "h-24" }) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    const w = Math.max(100, Math.floor(rect.width * dpr));
    const h = Math.max(40, Math.floor(rect.height * dpr));
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    ctx.clearRect(0, 0, w, h);
    ctx.strokeStyle = "rgba(40, 55, 89, 0.6)";
    ctx.lineWidth = 1 * dpr;
    ctx.beginPath();
    ctx.moveTo(0, h / 2);
    ctx.lineTo(w, h / 2);
    ctx.stroke();

    const list = bars.length > 0 ? bars : new Array(48).fill(0.06);
    const barW = w / list.length;
    const midY = h / 2;
    const gap = Math.max(1.5 * dpr, barW * 0.25);

    for (let i = 0; i < list.length; i++) {
      const amp = Math.max(0.05, Math.min(1, list[i]));
      const barH = amp * (h * 0.84);
      const x = i * barW + gap / 2;
      const drawW = Math.max(2 * dpr, barW - gap);
      const y = midY - barH / 2;

      const grad = ctx.createLinearGradient(0, y, 0, y + barH);
      if (isActive) {
        grad.addColorStop(0, accentColor);
        grad.addColorStop(0.5, "#A78BFA");
        grad.addColorStop(1, accentColor);
      } else {
        grad.addColorStop(0, "#334155");
        grad.addColorStop(1, "#1E293B");
      }
      ctx.fillStyle = grad;
      const r = Math.min(drawW / 2, 4 * dpr);
      ctx.beginPath();
      ctx.roundRect(x, y, drawW, barH, r);
      ctx.fill();
    }
  }, [bars, isActive, accentColor]);

  return (
    <canvas
      ref={canvasRef}
      className={`w-full ${heightClass} rounded-[16px] bg-[#070B14] border border-[#283759] block`}
    />
  );
};

export const PersistentTransportBar: React.FC = () => {
  const {
    language,
    hasAudio,
    playback,
    dspConfig,
    isHoldBypassing,
    setHoldBypass,
    toggleBypass,
    togglePlayPause,
    stopPlayback,
    seekToFraction,
    autoLoopOnPlayback,
    setAutoLoopOnPlayback,
  } = useStudio();

  const dur = Math.max(1, playback.durationMs);
  const progress = hasAudio ? Math.min(1, Math.max(0, playback.positionMs / dur)) : 0;

  return (
    <div className="fixed bottom-0 inset-x-0 z-40 px-3 pb-3 pt-2 bg-gradient-to-t from-[#070B14] via-[#070B14]/95 to-transparent pointer-events-none">
      <div className="max-w-[760px] mx-auto rounded-[22px] bg-[#131B2E]/95 border border-[#283759] px-3.5 py-2.5 shadow-2xl backdrop-blur-md pointer-events-auto space-y-2">
        <div className="flex items-center gap-2">
          <span className="font-mono-telemetry text-[11px] text-[#94A3B8] w-11 text-center">
            {formatDurationMs(playback.positionMs)}
          </span>
          <input
            type="range"
            min={0}
            max={1}
            step={0.002}
            disabled={!hasAudio}
            value={progress}
            onChange={(e) => seekToFraction(parseFloat(e.target.value))}
            className="flex-1 studio-slider disabled:opacity-40"
            style={{ "--slider-accent": "#22D3EE" } as React.CSSProperties}
          />
          <span className="font-mono-telemetry text-[11px] text-[#94A3B8] w-11 text-center">
            {formatDurationMs(playback.durationMs)}
          </span>
        </div>

        <div className="flex items-center justify-between gap-2 flex-wrap">
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={togglePlayPause}
              disabled={!hasAudio}
              className="h-10 px-4 rounded-[14px] bg-[#22D3EE] hover:bg-[#38BDF8] disabled:bg-[#1B2640] disabled:text-[#64748B] text-[#070B14] font-bold text-xs flex items-center gap-1.5 transition cursor-pointer"
            >
              {playback.isPlaying ? (
                <>
                  <Pause className="w-4 h-4 fill-current" />
                  <span>{t(language, "pause")}</span>
                </>
              ) : (
                <>
                  <Play className="w-4 h-4 fill-current" />
                  <span>{t(language, "play")}</span>
                </>
              )}
            </button>

            <button
              type="button"
              onClick={stopPlayback}
              disabled={!hasAudio}
              title={t(language, "stop")}
              className="h-10 w-10 rounded-[14px] bg-[#1B2640] hover:bg-[#243252] disabled:opacity-40 border border-[#283759] text-[#F1F5F9] flex items-center justify-center transition cursor-pointer"
            >
              <Square className="w-4 h-4 fill-current" />
            </button>

            <button
              type="button"
              onClick={() => setAutoLoopOnPlayback(!autoLoopOnPlayback)}
              title={t(language, "loop_repeat")}
              className={`h-10 w-10 rounded-[14px] border flex items-center justify-center transition cursor-pointer ${
                autoLoopOnPlayback
                  ? "bg-[#10B981]/20 border-[#10B981] text-[#10B981]"
                  : "bg-[#1B2640] border-[#283759] text-[#94A3B8]"
              }`}
            >
              <Repeat className="w-4 h-4" />
            </button>
          </div>

          {/* Output meter + Instant A/B Hold/Toggle Bypass */}
          <div className="flex items-center gap-2">
            <div className="hidden sm:flex items-center gap-1 w-20 h-2 rounded-full bg-[#070B14] border border-[#283759] overflow-hidden p-[1px]">
              <div
                className="h-full rounded-full transition-all duration-75"
                style={{
                  width: `${Math.min(100, Math.round(playback.outputLevel * 100))}%`,
                  background:
                    playback.outputLevel > 0.85
                      ? "#F43F5E"
                      : playback.outputLevel > 0.65
                        ? "#F59E0B"
                        : "#10B981",
                }}
              />
            </div>

            <button
              type="button"
              onClick={toggleBypass}
              onPointerDown={() => setHoldBypass(true)}
              onPointerUp={() => setHoldBypass(false)}
              onPointerLeave={() => setHoldBypass(false)}
              onPointerCancel={() => setHoldBypass(false)}
              className={`h-10 px-3 rounded-[14px] border text-xs font-semibold flex items-center gap-1.5 transition cursor-pointer select-none ${
                dspConfig.bypassAll || isHoldBypassing
                  ? "bg-[#F59E0B]/20 border-[#F59E0B] text-[#F59E0B]"
                  : "bg-[#1B2640] border-[#283759] text-[#A78BFA]"
              }`}
            >
              <GitCompare className="w-4 h-4" />
              <span>
                {dspConfig.bypassAll || isHoldBypassing
                  ? t(language, "bypass_all")
                  : t(language, "dsp_active")}
              </span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
