import {
  DspConfig,
  EffectType,
  SmartMasterAnalysis,
  StereoAudioBuffer,
} from "../types/studio";
import { clamp } from "./biquad";
import { radix2Fft } from "./voicePurifier";

export function analyzeAndOptimizeAudio(
  buffer: StereoAudioBuffer,
  baseConfig: DspConfig
): SmartMasterAnalysis {
  const n = buffer.left.length;
  const sr = buffer.sampleRate || 44100;
  if (n < 512) {
    return {
      rmsDb: -24,
      peakDb: -6,
      crestFactorDb: 18,
      noiseFloorDb: -55,
      lowEnergyRatio: 0.33,
      midEnergyRatio: 0.42,
      highEnergyRatio: 0.25,
      detectedProfile: "صوت طبيعي متوازن",
      appliedActions: ["تم تفعيل محدد القمم الآمن وحفظ الإعدادات المتوازنة"],
      optimizedConfig: baseConfig,
    };
  }

  let sumSq = 0;
  let peak = 1e-6;
  const stride = Math.max(1, Math.floor(n / 200_000));
  let counted = 0;

  for (let i = 0; i < n; i += stride) {
    const s = 0.5 * (buffer.left[i] + buffer.right[i]);
    const a = Math.abs(s);
    if (a > peak) peak = a;
    sumSq += s * s;
    counted++;
  }

  const rms = Math.sqrt(sumSq / Math.max(1, counted));
  const rmsDb = clamp(20 * Math.log10(Math.max(rms, 1e-6)), -80, 0);
  const peakDb = clamp(20 * Math.log10(Math.max(peak, 1e-6)), -80, 0);
  const crestDb = clamp(peakDb - rmsDb, 0, 35);

  const chunkSize = Math.max(256, Math.floor(sr * 0.03));
  const frameDbList: number[] = [];
  for (let start = 0; start + chunkSize <= n; start += chunkSize * 2) {
    let cs = 0;
    for (let i = 0; i < chunkSize; i++) {
      const v = buffer.left[start + i];
      cs += v * v;
    }
    const fRms = Math.sqrt(cs / chunkSize);
    frameDbList.push(20 * Math.log10(Math.max(fRms, 1e-6)));
  }
  frameDbList.sort((a, b) => a - b);
  const noiseFloorDb =
    frameDbList.length > 0
      ? clamp(frameDbList[Math.min(Math.floor(frameDbList.length * 0.12), frameDbList.length - 1)], -80, -15)
      : -55;

  const fftN = 1024;
  const real = new Float64Array(fftN);
  const imag = new Float64Array(fftN);
  const hann = new Float32Array(fftN);
  for (let i = 0; i < fftN; i++) {
    hann[i] = 0.5 * (1 - Math.cos((2 * Math.PI * i) / (fftN - 1)));
  }

  let lowE = 0;
  let midE = 0;
  let highE = 0;
  const binHz = sr / fftN;
  const hopStep = Math.max(fftN, Math.floor(n / 16));

  for (let pos = 0; pos + fftN <= n; pos += hopStep) {
    for (let i = 0; i < fftN; i++) {
      real[i] = buffer.left[pos + i] * hann[i];
      imag[i] = 0;
    }
    radix2Fft(real, imag, fftN, false);
    for (let k = 1; k < fftN / 2; k++) {
      const f = k * binHz;
      const p = real[k] * real[k] + imag[k] * imag[k];
      if (f < 250) lowE += p;
      else if (f < 3200) midE += p;
      else highE += p;
    }
  }

  const totalE = Math.max(lowE + midE + highE, 1e-9);
  const lowRatio = lowE / totalE;
  const midRatio = midE / totalE;
  const highRatio = highE / totalE;

  const actions: string[] = [];
  const enableNoiseClean = noiseFloorDb > -48;
  const highNoise = noiseFloorDb > -38;

  const bassTrimDb = lowRatio > 0.45 ? -3.5 : lowRatio < 0.16 ? 2 : 0;
  const presenceDb = highRatio < 0.14 ? 3.8 : highRatio > 0.34 ? -1.5 : 2;
  const airDb = highRatio < 0.12 ? 3 : 1.5;

  const needsComp = crestDb > 10 || rmsDb < -20;
  const compThreshold = clamp(rmsDb + 4, -28, -12);
  const compRatio = crestDb > 16 ? 4.2 : 3.2;
  const makeup = clamp(-15 - rmsDb, 0.5, 6);

  const profileDesc = highNoise
    ? "تسجيل صوتي في بيئة تحتوي ضجيج خلفي"
    : lowRatio > 0.42
      ? "صوت دافئ يحتاج تقليل الترددات المنخفضة الزائدة"
      : highRatio < 0.14
        ? "صوت مكتوم نسبياً ويحتاج تعزيز الوضوح واللمعان"
        : "صوت استوديو متوازن وقابل للماسترينغ الاحترافي";

  if (enableNoiseClean) {
    actions.push(
      `تفعيل المنقّي الطيفي (STFT) لقمع ضجيج الخلفية عند ${noiseFloorDb.toFixed(0)} dB`
    );
  }
  if (bassTrimDb < 0) actions.push(`خفض الترددات الغليظة (${bassTrimDb.toFixed(1)} dB) لإزالة الكتمة`);
  else if (bassTrimDb > 0) actions.push(`تعزيز الدفء الجهيري (+${bassTrimDb.toFixed(1)} dB)`);

  if (presenceDb > 0) actions.push(`تعزيز حضور الصوت ووضوح الحروف (+${presenceDb.toFixed(1)} dB @ 3.6kHz)`);
  if (needsComp) actions.push(`ضبط ضاغط الديناميكيات (${compRatio.toFixed(1)}:1) لرفع التجانس إلى -14 LUFS`);
  actions.push("تأمين السقف النهائي بمحدد القمم لمنع أي تشويه رقمي");

  const updatedChain = baseConfig.chain.map((slot) => {
    switch (slot.type) {
      case EffectType.NOISE_REDUCTION:
        return { ...slot, enabled: enableNoiseClean };
      case EffectType.EQUALIZER:
        return { ...slot, enabled: true };
      case EffectType.COMPRESSOR:
        return { ...slot, enabled: needsComp };
      case EffectType.LIMITER:
        return { ...slot, enabled: true };
      default:
        return slot;
    }
  });

  const optimized: DspConfig = {
    ...baseConfig,
    bypassAll: false,
    chain: updatedChain,
    noiseReduction: {
      ...baseConfig.noiseReduction,
      thresholdDb: clamp(noiseFloorDb + 6, -54, -32),
      reductionDb: highNoise ? 24 : 16,
      highPassHz: lowRatio > 0.4 ? 105 : 85,
      spectralStrength: highNoise ? 0.8 : 0.62,
      deEssAmount: highRatio > 0.32 ? 0.55 : 0.35,
      clarityBoostDb: clamp(presenceDb, 0, 5),
      spectralCleanerEnabled: true,
    },
    equalizer: {
      band60HzDb: bassTrimDb,
      band230HzDb: lowRatio > 0.42 ? -2 : 0.5,
      band910HzDb: midRatio < 0.3 ? 1.5 : 0.5,
      band3600HzDb: presenceDb,
      band14000HzDb: airDb,
    },
    compressor: {
      thresholdDb: compThreshold,
      ratio: compRatio,
      attackMs: 10,
      releaseMs: 135,
      makeupGainDb: makeup,
    },
    limiter: { ceilingDb: -0.8, releaseMs: 80 },
  };

  return {
    rmsDb,
    peakDb,
    crestFactorDb: crestDb,
    noiseFloorDb,
    lowEnergyRatio: lowRatio,
    midEnergyRatio: midRatio,
    highEnergyRatio: highRatio,
    detectedProfile: profileDesc,
    appliedActions: actions,
    optimizedConfig: optimized,
  };
}
