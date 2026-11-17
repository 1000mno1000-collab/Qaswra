import React, { useEffect, useRef, useState } from "react";
import {
  ArrowDown,
  ArrowUp,
  RotateCcw,
  SlidersHorizontal,
  Sparkles,
  Waves,
} from "lucide-react";
import {
  StudioFocusTopBar,
  StudioParamSlider,
  StudioSectionCard,
} from "../components/StudioComponents";
import { useStudio } from "../context/StudioContext";
import { t } from "../i18n/translations";
import {
  DistortionMode,
  EFFECT_META,
  EchoDetailGroup,
  EchoPadAxisMode,
  EffectType,
} from "../types/studio";

export const EchoReverbScreen: React.FC = () => {
  const {
    language,
    navigateBack,
    dspConfig,
    toggleEffectEnabled,
    isSimpleEchoMode,
    setSimpleEchoMode,
    echoPadAxisMode,
    setEchoPadAxisMode,
    updateEchoReverbFromPad,
    updateEchoReverbParams,
    getEchoPadAxes,
    requestNavigateToEchoDetail,
  } = useStudio();

  const echoSlot = dspConfig.chain.find((s) => s.type === EffectType.ECHO_REVERB);
  const echoEnabled = Boolean(echoSlot?.enabled);
  const { x: padX, y: padY } = getEchoPadAxes();

  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const isDraggingRef = useRef(false);
  const trailRef = useRef<Array<{ x: number; y: number }>>([]);
  const [pulsePhase, setPulsePhase] = useState(0);

  useEffect(() => {
    let rafId = 0;
    const tick = () => {
      setPulsePhase((p) => (p + 0.03) % (Math.PI * 2));
      rafId = requestAnimationFrame(tick);
    };
    rafId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafId);
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    const w = Math.max(120, Math.floor(rect.width * dpr));
    const h = Math.max(120, Math.floor(rect.height * dpr));
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    ctx.clearRect(0, 0, w, h);

    // Subtle radial ambient glow
    const ox = padX * w;
    const oy = (1 - padY) * h;
    const rad = ctx.createRadialGradient(ox, oy, 4 * dpr, ox, oy, Math.max(w, h) * 0.55);
    rad.addColorStop(0, echoEnabled ? "rgba(34,211,238,0.28)" : "rgba(100,116,139,0.14)");
    rad.addColorStop(0.5, echoEnabled ? "rgba(167,139,250,0.12)" : "rgba(15,23,42,0.05)");
    rad.addColorStop(1, "rgba(7,11,20,0)");
    ctx.fillStyle = rad;
    ctx.fillRect(0, 0, w, h);

    // Grid crosshairs & concentric room depth rings
    ctx.strokeStyle = "rgba(40, 55, 89, 0.7)";
    ctx.lineWidth = 1 * dpr;
    for (let i = 1; i <= 3; i++) {
      ctx.beginPath();
      ctx.moveTo((w * i) / 4, 0);
      ctx.lineTo((w * i) / 4, h);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(0, (h * i) / 4);
      ctx.lineTo(w, (h * i) / 4);
      ctx.stroke();
    }

    // Ripple rings around orb
    for (let r = 0; r < 3; r++) {
      const rp = (pulsePhase / (Math.PI * 2) + r / 3) % 1;
      const radius = (18 + rp * 76) * dpr;
      ctx.strokeStyle = `rgba(34, 211, 238, ${(1 - rp) * (echoEnabled ? 0.45 : 0.15)})`;
      ctx.lineWidth = 1.5 * dpr;
      ctx.beginPath();
      ctx.arc(ox, oy, radius, 0, Math.PI * 2);
      ctx.stroke();
    }

    // Orbiting reflection satellites
    for (let k = 0; k < 4; k++) {
      const ang = pulsePhase * (k % 2 === 0 ? 1 : -1) + (k * Math.PI) / 2;
      const dist = (26 + dspConfig.echoReverb.stereoWidth * 24) * dpr;
      const sx = ox + Math.cos(ang) * dist;
      const sy = oy + Math.sin(ang) * dist;
      ctx.fillStyle = "rgba(167, 139, 250, 0.85)";
      ctx.beginPath();
      ctx.arc(sx, sy, 3.2 * dpr, 0, Math.PI * 2);
      ctx.fill();
    }

    // Trail
    const trail = trailRef.current;
    if (trail.length > 1) {
      ctx.strokeStyle = "rgba(34, 211, 238, 0.5)";
      ctx.lineWidth = 2.5 * dpr;
      ctx.beginPath();
      trail.forEach((pt, idx) => {
        if (idx === 0) ctx.moveTo(pt.x * w, (1 - pt.y) * h);
        else ctx.lineTo(pt.x * w, (1 - pt.y) * h);
      });
      ctx.stroke();
    }

    // Glowing orb
    ctx.fillStyle = echoEnabled ? "#22D3EE" : "#94A3B8";
    ctx.beginPath();
    ctx.arc(ox, oy, 11 * dpr, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = "#070B14";
    ctx.lineWidth = 2.5 * dpr;
    ctx.stroke();
  }, [padX, padY, echoEnabled, pulsePhase, dspConfig.echoReverb.stereoWidth]);

  const handlePointer = (clientX: number, clientY: number) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const nx = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
    const ny = Math.max(0, Math.min(1, 1 - (clientY - rect.top) / rect.height));
    trailRef.current = [...trailRef.current.slice(-14), { x: nx, y: ny }];
    updateEchoReverbFromPad(nx, ny);
  };

  const axisModes: Array<{ mode: EchoPadAxisMode; label: string }> = [
    { mode: EchoPadAxisMode.SPACE, label: "المساحة والعمق" },
    { mode: EchoPadAxisMode.DELAY, label: "الزمن والارتداد" },
    { mode: EchoPadAxisMode.WIDTH, label: "الستيريو والانتشار" },
    { mode: EchoPadAxisMode.DAMPING, label: "التخميد والسبق" },
  ];

  return (
    <div className="space-y-4">
      <StudioFocusTopBar
        title={t(language, "section_echo_title")}
        subtitle={t(language, "section_echo_subtitle")}
        onBack={navigateBack}
      />

      {/* Enable + Simple/Pro Mode */}
      <div className="rounded-[20px] bg-[#131B2E] border border-[#283759] p-3.5 flex items-center justify-between gap-3 flex-wrap">
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="checkbox"
            checked={echoEnabled}
            onChange={(e) => toggleEffectEnabled(EffectType.ECHO_REVERB, e.target.checked)}
            className="w-5 h-5 accent-[#22D3EE]"
          />
          <span className="text-sm font-bold text-[#F1F5F9]">
            {t(language, "effect_echo_reverb")}
          </span>
        </label>

        <div className="flex rounded-[12px] bg-[#070B14] p-1 border border-[#283759]">
          <button
            type="button"
            onClick={() => setSimpleEchoMode(true)}
            className={`px-3 py-1 rounded-[9px] text-xs font-bold cursor-pointer ${
              isSimpleEchoMode ? "bg-[#22D3EE] text-[#070B14]" : "text-[#94A3B8]"
            }`}
          >
            {t(language, "mode_simple")}
          </button>
          <button
            type="button"
            onClick={() => setSimpleEchoMode(false)}
            className={`px-3 py-1 rounded-[9px] text-xs font-bold cursor-pointer ${
              !isSimpleEchoMode ? "bg-[#A78BFA] text-[#070B14]" : "text-[#94A3B8]"
            }`}
          >
            {t(language, "mode_pro")}
          </button>
        </div>
      </div>

      {/* Axis Mode Chips */}
      <div className="flex flex-wrap gap-2">
        {axisModes.map((m) => (
          <button
            key={m.mode}
            type="button"
            onClick={() => setEchoPadAxisMode(m.mode)}
            className={`px-3 py-1.5 rounded-[12px] text-xs font-bold border transition cursor-pointer ${
              echoPadAxisMode === m.mode
                ? "bg-[#22D3EE]/20 border-[#22D3EE] text-[#22D3EE]"
                : "bg-[#131B2E] border-[#283759] text-[#94A3B8]"
            }`}
          >
            {m.label}
          </button>
        ))}
      </div>

      {/* Interactive Spatial Pad */}
      <div className="rounded-[24px] bg-[#131B2E] border border-[#283759] p-4 space-y-2.5">
        <p className="text-xs text-[#94A3B8]">{t(language, "echo_pad_hint")}</p>
        <canvas
          ref={canvasRef}
          onPointerDown={(e) => {
            isDraggingRef.current = true;
            e.currentTarget.setPointerCapture(e.pointerId);
            handlePointer(e.clientX, e.clientY);
          }}
          onPointerMove={(e) => {
            if (isDraggingRef.current) handlePointer(e.clientX, e.clientY);
          }}
          onPointerUp={() => {
            isDraggingRef.current = false;
          }}
          className="w-full h-64 rounded-[18px] bg-[#070B14] border border-[#283759] touch-none cursor-crosshair block"
        />
        <div className="grid grid-cols-3 gap-2 text-center pt-1 font-mono-telemetry text-[11px]">
          <div className="rounded-[10px] bg-[#070B14] border border-[#283759] py-1.5">
            <span className="text-[#94A3B8]">Strength: </span>
            <span className="text-[#22D3EE] font-bold">
              {Math.round(dspConfig.echoReverb.echoStrength * 100)}%
            </span>
          </div>
          <div className="rounded-[10px] bg-[#070B14] border border-[#283759] py-1.5">
            <span className="text-[#94A3B8]">Delay: </span>
            <span className="text-[#A78BFA] font-bold">
              {Math.round(dspConfig.echoReverb.delayTimeMs)} ms
            </span>
          </div>
          <div className="rounded-[10px] bg-[#070B14] border border-[#283759] py-1.5">
            <span className="text-[#94A3B8]">Wet/Dry: </span>
            <span className="text-[#10B981] font-bold">
              {Math.round(dspConfig.echoReverb.wetDryMix * 100)}%
            </span>
          </div>
        </div>
      </div>

      {/* Quick Simple Sliders */}
      <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-2">
        <StudioParamSlider
          label={t(language, "param_wet_dry")}
          value={dspConfig.echoReverb.wetDryMix}
          min={0}
          max={1}
          formattedValue={`${Math.round(dspConfig.echoReverb.wetDryMix * 100)}%`}
          accentColor="#22D3EE"
          onChange={(v) => updateEchoReverbParams((e) => ({ ...e, wetDryMix: v }))}
        />
        <StudioParamSlider
          label={t(language, "param_delay_time")}
          value={dspConfig.echoReverb.delayTimeMs}
          min={30}
          max={1000}
          step={5}
          formattedValue={`${Math.round(dspConfig.echoReverb.delayTimeMs)} ms`}
          accentColor="#A78BFA"
          onChange={(v) => updateEchoReverbParams((e) => ({ ...e, delayTimeMs: v }))}
        />
      </div>

      {!isSimpleEchoMode && (
        <div className="space-y-2.5">
          <StudioSectionCard
            title={t(language, "echo_node_core")}
            subtitle="شدة الصدى، العمق، زمن التأخير، والتغذية الراجعة"
            icon={<Waves className="w-5 h-5" />}
            accentColor="#22D3EE"
            onClick={() => requestNavigateToEchoDetail(EchoDetailGroup.CORE)}
          />
          <StudioSectionCard
            title={t(language, "echo_node_reflections")}
            subtitle="الانعكاسات المبكرة والمتأخرة، الانتشار، والتأخير المسبق"
            icon={<Sparkles className="w-5 h-5" />}
            accentColor="#A78BFA"
            onClick={() => requestNavigateToEchoDetail(EchoDetailGroup.REFLECTIONS)}
          />
          <StudioSectionCard
            title={t(language, "echo_node_space")}
            subtitle="حجم القاعة، التلاشي، التخميد، واتساع الستيريو"
            icon={<SlidersHorizontal className="w-5 h-5" />}
            accentColor="#10B981"
            onClick={() => requestNavigateToEchoDetail(EchoDetailGroup.SPACE)}
          />
        </div>
      )}
    </div>
  );
};

export const EchoDetailScreen: React.FC<{ group: EchoDetailGroup }> = ({ group }) => {
  const { language, navigateBack, dspConfig, updateEchoReverbParams } = useStudio();
  const e = dspConfig.echoReverb;

  const titleKey =
    group === EchoDetailGroup.CORE
      ? "echo_node_core"
      : group === EchoDetailGroup.REFLECTIONS
        ? "echo_node_reflections"
        : "echo_node_space";

  return (
    <div className="space-y-4">
      <StudioFocusTopBar title={t(language, titleKey)} onBack={navigateBack} />
      <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-2">
        {group === EchoDetailGroup.CORE && (
          <>
            <StudioParamSlider
              label={t(language, "param_echo_strength")}
              value={e.echoStrength}
              min={0}
              max={1}
              formattedValue={`${Math.round(e.echoStrength * 100)}%`}
              onChange={(v) => updateEchoReverbParams((p) => ({ ...p, echoStrength: v }))}
            />
            <StudioParamSlider
              label={t(language, "param_echo_depth")}
              value={e.echoDepth}
              min={0}
              max={1}
              formattedValue={`${Math.round(e.echoDepth * 100)}%`}
              onChange={(v) => updateEchoReverbParams((p) => ({ ...p, echoDepth: v }))}
            />
            <StudioParamSlider
              label={t(language, "param_delay_time")}
              value={e.delayTimeMs}
              min={30}
              max={1200}
              step={5}
              formattedValue={`${Math.round(e.delayTimeMs)} ms`}
              onChange={(v) => updateEchoReverbParams((p) => ({ ...p, delayTimeMs: v }))}
            />
            <StudioParamSlider
              label={t(language, "param_feedback")}
              value={e.feedback}
              min={0}
              max={0.9}
              formattedValue={`${Math.round(e.feedback * 100)}%`}
              onChange={(v) => updateEchoReverbParams((p) => ({ ...p, feedback: v }))}
            />
          </>
        )}

        {group === EchoDetailGroup.REFLECTIONS && (
          <>
            <StudioParamSlider
              label={t(language, "param_pre_delay")}
              value={e.preDelayMs}
              min={0}
              max={180}
              step={1}
              formattedValue={`${Math.round(e.preDelayMs)} ms`}
              onChange={(v) => updateEchoReverbParams((p) => ({ ...p, preDelayMs: v }))}
            />
            <StudioParamSlider
              label={t(language, "param_early_reflections")}
              value={e.earlyReflections}
              min={0}
              max={1}
              formattedValue={`${Math.round(e.earlyReflections * 100)}%`}
              onChange={(v) => updateEchoReverbParams((p) => ({ ...p, earlyReflections: v }))}
            />
            <StudioParamSlider
              label={t(language, "param_late_reflections")}
              value={e.lateReflections}
              min={0}
              max={1}
              formattedValue={`${Math.round(e.lateReflections * 100)}%`}
              onChange={(v) => updateEchoReverbParams((p) => ({ ...p, lateReflections: v }))}
            />
            <StudioParamSlider
              label={t(language, "param_diffusion")}
              value={e.diffusion}
              min={0}
              max={1}
              formattedValue={`${Math.round(e.diffusion * 100)}%`}
              onChange={(v) => updateEchoReverbParams((p) => ({ ...p, diffusion: v }))}
            />
          </>
        )}

        {group === EchoDetailGroup.SPACE && (
          <>
            <StudioParamSlider
              label={t(language, "param_reverb_size")}
              value={e.reverbSize}
              min={0}
              max={1}
              formattedValue={`${Math.round(e.reverbSize * 100)}%`}
              onChange={(v) => updateEchoReverbParams((p) => ({ ...p, reverbSize: v }))}
            />
            <StudioParamSlider
              label={t(language, "param_reverb_decay")}
              value={e.reverbDecay}
              min={0}
              max={1}
              formattedValue={`${Math.round(e.reverbDecay * 100)}%`}
              onChange={(v) => updateEchoReverbParams((p) => ({ ...p, reverbDecay: v }))}
            />
            <StudioParamSlider
              label={t(language, "param_damping")}
              value={e.damping}
              min={0}
              max={1}
              formattedValue={`${Math.round(e.damping * 100)}%`}
              onChange={(v) => updateEchoReverbParams((p) => ({ ...p, damping: v }))}
            />
            <StudioParamSlider
              label={t(language, "param_stereo_width")}
              value={e.stereoWidth}
              min={0}
              max={1}
              formattedValue={`${Math.round(e.stereoWidth * 100)}%`}
              onChange={(v) => updateEchoReverbParams((p) => ({ ...p, stereoWidth: v }))}
            />
            <StudioParamSlider
              label={t(language, "param_wet_dry")}
              value={e.wetDryMix}
              min={0}
              max={1}
              formattedValue={`${Math.round(e.wetDryMix * 100)}%`}
              onChange={(v) => updateEchoReverbParams((p) => ({ ...p, wetDryMix: v }))}
            />
          </>
        )}
      </div>
    </div>
  );
};

export const EffectsChainScreen: React.FC = () => {
  const {
    language,
    navigateBack,
    navigateTo,
    dspConfig,
    updateDspConfig,
    toggleEffectEnabled,
    moveEffectInChain,
    resetAllEffects,
  } = useStudio();

  return (
    <div className="space-y-4">
      <StudioFocusTopBar
        title={t(language, "section_chain_title")}
        subtitle={t(language, "section_chain_subtitle")}
        onBack={navigateBack}
      />

      <div className="rounded-[20px] bg-[#131B2E] border border-[#283759] p-4 space-y-3">
        <StudioParamSlider
          label="مستوى الخرج الرئيسي (Master Gain)"
          value={dspConfig.masterGainDb}
          min={-18}
          max={18}
          step={0.5}
          formattedValue={`${dspConfig.masterGainDb >= 0 ? "+" : ""}${dspConfig.masterGainDb.toFixed(1)} dB`}
          accentColor="#22D3EE"
          onChange={(v) => updateDspConfig((prev) => ({ ...prev, masterGainDb: v }))}
        />

        <div className="flex items-center justify-between gap-2 pt-1">
          <span className="text-xs text-[#94A3B8]">{t(language, "chain_order_hint")}</span>
          <button
            type="button"
            onClick={resetAllEffects}
            className="h-9 px-3 rounded-[12px] bg-[#1B2640] border border-[#F43F5E]/40 text-[#F43F5E] text-xs font-bold flex items-center gap-1.5 shrink-0 cursor-pointer"
          >
            <RotateCcw className="w-3.5 h-3.5" />
            <span>تصفير</span>
          </button>
        </div>
      </div>

      <div className="space-y-2">
        {dspConfig.chain.map((slot, index) => {
          const meta = EFFECT_META[slot.type];
          return (
            <div
              key={slot.type}
              className={`rounded-[18px] px-3.5 py-2.5 border flex items-center justify-between gap-2 transition ${
                slot.enabled
                  ? "bg-[#131B2E] border-[#22D3EE]/60"
                  : "bg-[#0C1220] border-[#283759]"
              }`}
            >
              <button
                type="button"
                onClick={() => navigateTo({ screen: "EFFECT_DETAIL", effect: slot.type })}
                className="flex-1 text-start min-w-0 flex items-center gap-3 cursor-pointer"
              >
                <span className="font-mono-telemetry text-xs font-bold w-6 h-6 rounded-lg bg-[#1B2640] text-[#94A3B8] flex items-center justify-center shrink-0">
                  {index + 1}
                </span>
                <div className="min-w-0">
                  <div className="text-sm font-bold text-[#F1F5F9] truncate">
                    {t(language, meta.titleKey)}
                  </div>
                  <div className="text-[11px] text-[#22D3EE]">
                    {slot.enabled ? "مفعّل — اضغط للضبط" : "متوقف"}
                  </div>
                </div>
              </button>

              <div className="flex items-center gap-1.5 shrink-0">
                <button
                  type="button"
                  disabled={index === 0}
                  onClick={() => moveEffectInChain(index, index - 1)}
                  title={t(language, "move_up")}
                  className="w-8 h-8 rounded-[10px] bg-[#1B2640] disabled:opacity-30 text-[#94A3B8] hover:text-[#F1F5F9] flex items-center justify-center cursor-pointer"
                >
                  <ArrowUp className="w-4 h-4" />
                </button>
                <button
                  type="button"
                  disabled={index === dspConfig.chain.length - 1}
                  onClick={() => moveEffectInChain(index, index + 1)}
                  title={t(language, "move_down")}
                  className="w-8 h-8 rounded-[10px] bg-[#1B2640] disabled:opacity-30 text-[#94A3B8] hover:text-[#F1F5F9] flex items-center justify-center cursor-pointer"
                >
                  <ArrowDown className="w-4 h-4" />
                </button>
                <input
                  type="checkbox"
                  checked={slot.enabled}
                  onChange={(e) => toggleEffectEnabled(slot.type, e.target.checked)}
                  className="w-5 h-5 accent-[#10B981] cursor-pointer ms-1"
                />
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export const EffectDetailScreen: React.FC<{ effect: EffectType }> = ({ effect }) => {
  const { language, navigateBack, dspConfig, updateDspConfig, playback } = useStudio();
  const meta = EFFECT_META[effect];
  const eqCanvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    if (effect !== EffectType.EQUALIZER) return;
    const canvas = eqCanvasRef.current;
    if (!canvas) return;
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    canvas.width = Math.max(120, Math.floor(rect.width * dpr));
    canvas.height = Math.max(80, Math.floor(rect.height * dpr));
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    ctx.strokeStyle = "rgba(40,55,89,0.7)";
    ctx.lineWidth = 1 * dpr;
    ctx.beginPath();
    ctx.moveTo(0, h / 2);
    ctx.lineTo(w, h / 2);
    ctx.stroke();

    const gains = [
      dspConfig.equalizer.band60HzDb,
      dspConfig.equalizer.band230HzDb,
      dspConfig.equalizer.band910HzDb,
      dspConfig.equalizer.band3600HzDb,
      dspConfig.equalizer.band14000HzDb,
    ];
    const pts = gains.map((g, idx) => ({
      x: (idx + 0.5) * (w / 5),
      y: h / 2 - (g / 12) * (h * 0.42),
    }));

    ctx.strokeStyle = "#22D3EE";
    ctx.lineWidth = 3 * dpr;
    ctx.beginPath();
    pts.forEach((p, idx) => {
      if (idx === 0) ctx.moveTo(p.x, p.y);
      else ctx.lineTo(p.x, p.y);
    });
    ctx.stroke();

    for (const p of pts) {
      ctx.fillStyle = "#A78BFA";
      ctx.beginPath();
      ctx.arc(p.x, p.y, 5 * dpr, 0, Math.PI * 2);
      ctx.fill();
    }
  }, [effect, dspConfig.equalizer]);

  if (effect === EffectType.ECHO_REVERB) {
    return <EchoReverbScreen />;
  }

  return (
    <div className="space-y-4">
      <StudioFocusTopBar title={t(language, meta.titleKey)} onBack={navigateBack} />

      <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-3">
        {effect === EffectType.EQUALIZER && (
          <>
            <canvas
              ref={eqCanvasRef}
              className="w-full h-32 rounded-[14px] bg-[#070B14] border border-[#283759] block"
            />
            <StudioParamSlider
              label="60 Hz (Sub Bass)"
              value={dspConfig.equalizer.band60HzDb}
              min={-12}
              max={12}
              step={0.5}
              formattedValue={`${dspConfig.equalizer.band60HzDb.toFixed(1)} dB`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, equalizer: { ...p.equalizer, band60HzDb: v } }))
              }
            />
            <StudioParamSlider
              label="230 Hz (Warmth)"
              value={dspConfig.equalizer.band230HzDb}
              min={-12}
              max={12}
              step={0.5}
              formattedValue={`${dspConfig.equalizer.band230HzDb.toFixed(1)} dB`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, equalizer: { ...p.equalizer, band230HzDb: v } }))
              }
            />
            <StudioParamSlider
              label="910 Hz (Midrange)"
              value={dspConfig.equalizer.band910HzDb}
              min={-12}
              max={12}
              step={0.5}
              formattedValue={`${dspConfig.equalizer.band910HzDb.toFixed(1)} dB`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, equalizer: { ...p.equalizer, band910HzDb: v } }))
              }
            />
            <StudioParamSlider
              label="3.6 kHz (Presence)"
              value={dspConfig.equalizer.band3600HzDb}
              min={-12}
              max={12}
              step={0.5}
              formattedValue={`${dspConfig.equalizer.band3600HzDb.toFixed(1)} dB`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, equalizer: { ...p.equalizer, band3600HzDb: v } }))
              }
            />
            <StudioParamSlider
              label="14 kHz (Air)"
              value={dspConfig.equalizer.band14000HzDb}
              min={-12}
              max={12}
              step={0.5}
              formattedValue={`${dspConfig.equalizer.band14000HzDb.toFixed(1)} dB`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, equalizer: { ...p.equalizer, band14000HzDb: v } }))
              }
            />
          </>
        )}

        {effect === EffectType.BASS_BOOST && (
          <>
            <StudioParamSlider
              label="تعزيز الجهير (Gain)"
              value={dspConfig.bassBoost.gainDb}
              min={-6}
              max={14}
              step={0.5}
              formattedValue={`${dspConfig.bassBoost.gainDb.toFixed(1)} dB`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, bassBoost: { ...p.bassBoost, gainDb: v } }))
              }
            />
            <StudioParamSlider
              label="التردد المركزي (Frequency)"
              value={dspConfig.bassBoost.frequencyHz}
              min={50}
              max={250}
              step={5}
              formattedValue={`${Math.round(dspConfig.bassBoost.frequencyHz)} Hz`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, bassBoost: { ...p.bassBoost, frequencyHz: v } }))
              }
            />
          </>
        )}

        {effect === EffectType.TREBLE && (
          <>
            <StudioParamSlider
              label="الحدة واللمعان (Treble Gain)"
              value={dspConfig.treble.gainDb}
              min={-10}
              max={14}
              step={0.5}
              formattedValue={`${dspConfig.treble.gainDb.toFixed(1)} dB`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, treble: { ...p.treble, gainDb: v } }))
              }
            />
            <StudioParamSlider
              label="التردد المركزي"
              value={dspConfig.treble.frequencyHz}
              min={2500}
              max={12000}
              step={100}
              formattedValue={`${Math.round(dspConfig.treble.frequencyHz)} Hz`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, treble: { ...p.treble, frequencyHz: v } }))
              }
            />
          </>
        )}

        {effect === EffectType.PITCH_SHIFT && (
          <>
            <StudioParamSlider
              label="تغيير الطبقة (Semitones)"
              value={dspConfig.pitchShift.semitones}
              min={-12}
              max={12}
              step={0.2}
              formattedValue={`${dspConfig.pitchShift.semitones >= 0 ? "+" : ""}${dspConfig.pitchShift.semitones.toFixed(1)} st`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, pitchShift: { ...p.pitchShift, semitones: v } }))
              }
            />
            <StudioParamSlider
              label="المزج (Wet Mix)"
              value={dspConfig.pitchShift.wetMix}
              min={0}
              max={1}
              formattedValue={`${Math.round(dspConfig.pitchShift.wetMix * 100)}%`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, pitchShift: { ...p.pitchShift, wetMix: v } }))
              }
            />
          </>
        )}

        {effect === EffectType.TIME_STRETCH && (
          <StudioParamSlider
            label="سرعة التشغيل (Tempo Ratio)"
            value={dspConfig.timeStretch.tempoRatio}
            min={0.5}
            max={2.0}
            step={0.02}
            formattedValue={`${dspConfig.timeStretch.tempoRatio.toFixed(2)}x`}
            onChange={(v) =>
              updateDspConfig((p) => ({ ...p, timeStretch: { tempoRatio: v } }))
            }
          />
        )}

        {effect === EffectType.CHORUS && (
          <>
            <StudioParamSlider
              label="السرعة (Rate)"
              value={dspConfig.chorus.rateHz}
              min={0.1}
              max={6}
              step={0.05}
              formattedValue={`${dspConfig.chorus.rateHz.toFixed(2)} Hz`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, chorus: { ...p.chorus, rateHz: v } }))
              }
            />
            <StudioParamSlider
              label="العمق (Depth)"
              value={dspConfig.chorus.depthMs}
              min={1}
              max={18}
              step={0.2}
              formattedValue={`${dspConfig.chorus.depthMs.toFixed(1)} ms`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, chorus: { ...p.chorus, depthMs: v } }))
              }
            />
            <StudioParamSlider
              label="المزج (Mix)"
              value={dspConfig.chorus.mix}
              min={0}
              max={1}
              formattedValue={`${Math.round(dspConfig.chorus.mix * 100)}%`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, chorus: { ...p.chorus, mix: v } }))
              }
            />
          </>
        )}

        {effect === EffectType.FLANGER && (
          <>
            <StudioParamSlider
              label="السرعة (Rate)"
              value={dspConfig.flanger.rateHz}
              min={0.05}
              max={4}
              step={0.05}
              formattedValue={`${dspConfig.flanger.rateHz.toFixed(2)} Hz`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, flanger: { ...p.flanger, rateHz: v } }))
              }
            />
            <StudioParamSlider
              label="العمق (Depth)"
              value={dspConfig.flanger.depthMs}
              min={0.5}
              max={10}
              step={0.1}
              formattedValue={`${dspConfig.flanger.depthMs.toFixed(1)} ms`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, flanger: { ...p.flanger, depthMs: v } }))
              }
            />
            <StudioParamSlider
              label="الارتداد (Feedback)"
              value={dspConfig.flanger.feedback}
              min={-0.8}
              max={0.8}
              formattedValue={`${Math.round(dspConfig.flanger.feedback * 100)}%`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, flanger: { ...p.flanger, feedback: v } }))
              }
            />
            <StudioParamSlider
              label="المزج (Mix)"
              value={dspConfig.flanger.mix}
              min={0}
              max={1}
              formattedValue={`${Math.round(dspConfig.flanger.mix * 100)}%`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, flanger: { ...p.flanger, mix: v } }))
              }
            />
          </>
        )}

        {effect === EffectType.PHASER && (
          <>
            <StudioParamSlider
              label="السرعة (Rate)"
              value={dspConfig.phaser.rateHz}
              min={0.05}
              max={5}
              step={0.05}
              formattedValue={`${dspConfig.phaser.rateHz.toFixed(2)} Hz`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, phaser: { ...p.phaser, rateHz: v } }))
              }
            />
            <StudioParamSlider
              label="العمق (Depth)"
              value={dspConfig.phaser.depth}
              min={0}
              max={1}
              formattedValue={`${Math.round(dspConfig.phaser.depth * 100)}%`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, phaser: { ...p.phaser, depth: v } }))
              }
            />
            <StudioParamSlider
              label="المزج (Mix)"
              value={dspConfig.phaser.mix}
              min={0}
              max={1}
              formattedValue={`${Math.round(dspConfig.phaser.mix * 100)}%`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, phaser: { ...p.phaser, mix: v } }))
              }
            />
          </>
        )}

        {effect === EffectType.DISTORTION && (
          <>
            <div className="flex gap-2 pb-1">
              {(["SOFT_CLIP", "HARD_CLIP", "OVERDRIVE"] as DistortionMode[]).map((m) => (
                <button
                  key={m}
                  type="button"
                  onClick={() =>
                    updateDspConfig((p) => ({ ...p, distortion: { ...p.distortion, mode: m } }))
                  }
                  className={`flex-1 py-2 rounded-[10px] text-xs font-bold border cursor-pointer ${
                    dspConfig.distortion.mode === m
                      ? "bg-[#F43F5E]/20 border-[#F43F5E] text-[#F43F5E]"
                      : "bg-[#070B14] border-[#283759] text-[#94A3B8]"
                  }`}
                >
                  {m}
                </button>
              ))}
            </div>
            <StudioParamSlider
              label="شدة التشويه (Drive)"
              value={dspConfig.distortion.drive}
              min={1}
              max={18}
              step={0.2}
              formattedValue={`${dspConfig.distortion.drive.toFixed(1)}x`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, distortion: { ...p.distortion, drive: v } }))
              }
            />
            <StudioParamSlider
              label="فلتر اللون (Tone)"
              value={dspConfig.distortion.toneHz}
              min={600}
              max={12000}
              step={100}
              formattedValue={`${Math.round(dspConfig.distortion.toneHz)} Hz`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, distortion: { ...p.distortion, toneHz: v } }))
              }
            />
            <StudioParamSlider
              label="المزج (Mix)"
              value={dspConfig.distortion.mix}
              min={0}
              max={1}
              formattedValue={`${Math.round(dspConfig.distortion.mix * 100)}%`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, distortion: { ...p.distortion, mix: v } }))
              }
            />
          </>
        )}

        {effect === EffectType.COMPRESSOR && (
          <>
            <div className="rounded-[12px] bg-[#070B14] border border-[#283759] p-3 space-y-1">
              <div className="flex justify-between text-xs">
                <span className="text-[#94A3B8]">Gain Reduction:</span>
                <span className="font-mono-telemetry text-[#F59E0B] font-bold">
                  -{playback.gainReductionDb.toFixed(1)} dB
                </span>
              </div>
              <div className="w-full h-2 rounded-full bg-[#1B2640] overflow-hidden">
                <div
                  className="h-full bg-[#F59E0B] transition-all duration-75"
                  style={{ width: `${Math.min(100, (playback.gainReductionDb / 20) * 100)}%` }}
                />
              </div>
            </div>

            <StudioParamSlider
              label="العتبة (Threshold)"
              value={dspConfig.compressor.thresholdDb}
              min={-42}
              max={0}
              step={0.5}
              formattedValue={`${dspConfig.compressor.thresholdDb.toFixed(1)} dB`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, compressor: { ...p.compressor, thresholdDb: v } }))
              }
            />
            <StudioParamSlider
              label="النسبة (Ratio)"
              value={dspConfig.compressor.ratio}
              min={1}
              max={16}
              step={0.2}
              formattedValue={`${dspConfig.compressor.ratio.toFixed(1)}:1`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, compressor: { ...p.compressor, ratio: v } }))
              }
            />
            <StudioParamSlider
              label="تعويض الشدة (Makeup Gain)"
              value={dspConfig.compressor.makeupGainDb}
              min={0}
              max={14}
              step={0.5}
              formattedValue={`+${dspConfig.compressor.makeupGainDb.toFixed(1)} dB`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, compressor: { ...p.compressor, makeupGainDb: v } }))
              }
            />
          </>
        )}

        {effect === EffectType.LIMITER && (
          <>
            <StudioParamSlider
              label="السقف الأقصى (Ceiling)"
              value={dspConfig.limiter.ceilingDb}
              min={-12}
              max={-0.1}
              step={0.1}
              formattedValue={`${dspConfig.limiter.ceilingDb.toFixed(1)} dB`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, limiter: { ...p.limiter, ceilingDb: v } }))
              }
            />
            <StudioParamSlider
              label="زمن التحرير (Release)"
              value={dspConfig.limiter.releaseMs}
              min={15}
              max={400}
              step={5}
              formattedValue={`${Math.round(dspConfig.limiter.releaseMs)} ms`}
              onChange={(v) =>
                updateDspConfig((p) => ({ ...p, limiter: { ...p.limiter, releaseMs: v } }))
              }
            />
          </>
        )}

        {effect === EffectType.NOISE_REDUCTION && (
          <>
            <StudioParamSlider
              label="قوة العزل الطيفي"
              value={dspConfig.noiseReduction.spectralStrength}
              min={0}
              max={1}
              formattedValue={`${Math.round(dspConfig.noiseReduction.spectralStrength * 100)}%`}
              onChange={(v) =>
                updateDspConfig((p) => ({
                  ...p,
                  noiseReduction: { ...p.noiseReduction, spectralStrength: v },
                }))
              }
            />
            <StudioParamSlider
              label="الخفض (Reduction)"
              value={dspConfig.noiseReduction.reductionDb}
              min={0}
              max={36}
              step={0.5}
              formattedValue={`-${dspConfig.noiseReduction.reductionDb.toFixed(1)} dB`}
              onChange={(v) =>
                updateDspConfig((p) => ({
                  ...p,
                  noiseReduction: { ...p.noiseReduction, reductionDb: v },
                }))
              }
            />
          </>
        )}
      </div>
    </div>
  );
};
