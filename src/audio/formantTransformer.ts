import { StereoAudioBuffer } from "../types/studio";
import { StereoBiquadFilter, clamp } from "./biquad";
import { radix2Fft } from "./voicePurifier";

function timeStretchWsolaMono(
  input: Float32Array,
  sampleRate: number,
  speedRatio: number
): Float32Array {
  const n = input.length;
  if (n < 2048 || Math.abs(speedRatio - 1) < 0.01) return new Float32Array(input);

  const winSize = Math.round(sampleRate * 0.048); // ~48ms
  const synHop = Math.floor(winSize / 2);
  const anaHop = Math.max(32, Math.round(synHop * speedRatio));
  const searchRadius = Math.min(Math.floor(winSize / 3), Math.round(sampleRate * 0.012));

  const hann = new Float32Array(winSize);
  for (let i = 0; i < winSize; i++) {
    hann[i] = 0.5 * (1 - Math.cos((2 * Math.PI * i) / (winSize - 1)));
  }

  const outLen = Math.max(winSize, Math.round(n / speedRatio) + winSize);
  const out = new Float32Array(outLen);
  const norm = new Float32Array(outLen);

  let inPos = 0;
  let outPos = 0;

  const firstLen = Math.min(winSize, n);
  for (let i = 0; i < firstLen; i++) {
    out[i] += input[i] * hann[i];
    norm[i] += hann[i];
  }
  inPos += anaHop;
  outPos += synHop;

  while (inPos + winSize < n && outPos + winSize < outLen) {
    let bestShift = 0;
    let bestCorr = -Infinity;
    const minShift = Math.max(-searchRadius, -inPos);
    const maxShift = Math.min(searchRadius, n - winSize - inPos);

    for (let s = minShift; s <= maxShift; s += 2) {
      let corr = 0;
      const candStart = inPos + s;
      for (let i = 0; i < synHop; i += 4) {
        corr += out[outPos + i] * input[candStart + i];
      }
      if (corr > bestCorr) {
        bestCorr = corr;
        bestShift = s;
      }
    }

    const srcStart = inPos + bestShift;
    for (let i = 0; i < winSize; i++) {
      const w = hann[i];
      out[outPos + i] += input[srcStart + i] * w;
      norm[outPos + i] += w;
    }

    inPos = srcStart + anaHop;
    outPos += synHop;
  }

  for (let i = 0; i < outLen; i++) {
    if (norm[i] > 1e-4) out[i] /= norm[i];
  }

  const targetLen = Math.max(1, Math.round(n / speedRatio));
  return out.subarray(0, Math.min(targetLen, out.length));
}

function resampleChannel(input: Float32Array, targetLen: number): Float32Array {
  if (input.length === targetLen || input.length < 2) {
    const out = new Float32Array(targetLen);
    out.set(input.subarray(0, Math.min(input.length, targetLen)));
    return out;
  }
  const out = new Float32Array(targetLen);
  const scale = (input.length - 1) / Math.max(1, targetLen - 1);
  for (let i = 0; i < targetLen; i++) {
    const pos = i * scale;
    const i0 = Math.floor(pos);
    const i1 = Math.min(i0 + 1, input.length - 1);
    const frac = pos - i0;
    out[i] = input[i0] + (input[i1] - input[i0]) * frac;
  }
  return out;
}

function shiftFormantsStft(
  input: Float32Array,
  formantShift: number
): Float32Array {
  const n = input.length;
  if (n < 1024 || Math.abs(formantShift - 1) < 0.015) return new Float32Array(input);

  const N = 1024;
  const HOP = 256;
  const HALF = N / 2;

  const sqrtHann = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    const h = 0.5 * (1 - Math.cos((2 * Math.PI * i) / (N - 1)));
    sqrtHann[i] = Math.sqrt(Math.max(h, 0));
  }

  const out = new Float32Array(n + N);
  const norm = new Float32Array(n + N);

  const real = new Float64Array(N);
  const imag = new Float64Array(N);
  const logSpecReal = new Float64Array(N);
  const logSpecImag = new Float64Array(N);

  const mag = new Float64Array(HALF + 1);
  const envOrig = new Float64Array(HALF + 1);
  const envShifted = new Float64Array(HALF + 1);

  const cepstrumOrder = 34;

  for (let start = 0; start < n; start += HOP) {
    real.fill(0);
    imag.fill(0);
    const copyLen = Math.min(N, n - start);
    for (let i = 0; i < copyLen; i++) {
      real[i] = input[start + i] * sqrtHann[i];
    }

    radix2Fft(real, imag, N, false);

    for (let k = 0; k <= HALF; k++) {
      const m = Math.hypot(real[k], imag[k]);
      mag[k] = m;
      const logM = Math.log(m + 1e-6);
      logSpecReal[k] = logM;
      logSpecImag[k] = 0;
      if (k > 0 && k < HALF) {
        logSpecReal[N - k] = logM;
        logSpecImag[N - k] = 0;
      }
    }

    radix2Fft(logSpecReal, logSpecImag, N, true);

    for (let q = cepstrumOrder; q <= N - cepstrumOrder; q++) {
      logSpecReal[q] = 0;
    }
    logSpecImag.fill(0);

    radix2Fft(logSpecReal, logSpecImag, N, false);

    for (let k = 0; k <= HALF; k++) {
      envOrig[k] = Math.exp(clamp(logSpecReal[k], -12, 12));
    }

    for (let k = 0; k <= HALF; k++) {
      const srcBin = k / formantShift;
      if (srcBin <= 0) {
        envShifted[k] = envOrig[0];
      } else if (srcBin >= HALF) {
        envShifted[k] = envOrig[HALF] * 0.65;
      } else {
        const b0 = Math.floor(srcBin);
        const b1 = Math.min(b0 + 1, HALF);
        const f = srcBin - b0;
        envShifted[k] = envOrig[b0] + (envOrig[b1] - envOrig[b0]) * f;
      }

      const ratio = clamp(envShifted[k] / Math.max(envOrig[k], 1e-5), 0.18, 4.5);
      real[k] *= ratio;
      imag[k] *= ratio;
      if (k > 0 && k < HALF) {
        real[N - k] *= ratio;
        imag[N - k] *= ratio;
      }
    }

    radix2Fft(real, imag, N, true);

    for (let i = 0; i < N; i++) {
      const w = sqrtHann[i];
      out[start + i] += real[i] * w;
      norm[start + i] += w * w;
    }
  }

  const finalOut = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    finalOut[i] = norm[i] > 1e-5 ? clamp(out[i] / norm[i], -1, 1) : 0;
  }
  return finalOut;
}

export async function transformVoiceOffline(
  source: StereoAudioBuffer,
  pitchSemitones: number,
  formantShift: number,
  warmthDb: number,
  presenceDb: number,
  mix: number,
  onProgress?: (p: number) => void
): Promise<StereoAudioBuffer> {
  const n = source.left.length;
  if (n === 0) return source;
  const sr = source.sampleRate;

  onProgress?.(0.08);
  await new Promise((r) => setTimeout(r, 0));

  const monoIn = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    monoIn[i] = 0.5 * (source.left[i] + source.right[i]);
  }

  // Step 1: Pitch Shift preserving tempo
  const pitchFactor = clamp(Math.pow(2, clamp(pitchSemitones, -12, 12) / 12), 0.5, 2.0);
  let pitched: Float32Array;
  if (Math.abs(pitchFactor - 1) > 0.01) {
    const stretched = timeStretchWsolaMono(monoIn, sr, 1 / pitchFactor);
    pitched = resampleChannel(stretched, n);
  } else {
    pitched = monoIn;
  }

  onProgress?.(0.45);
  await new Promise((r) => setTimeout(r, 0));

  // Step 2: Vocal tract formant shift
  const clampedFormant = clamp(formantShift, 0.65, 1.45);
  const formanted =
    Math.abs(clampedFormant - 1) > 0.015
      ? shiftFormantsStft(pitched, clampedFormant)
      : pitched;

  onProgress?.(0.8);
  await new Promise((r) => setTimeout(r, 0));

  // Step 3: Warmth + Presence + Wet/Dry mix
  const warmthFilter = new StereoBiquadFilter();
  const presenceFilter = new StereoBiquadFilter();
  warmthFilter.configureLowShelf(sr, 210, 0.75, clamp(warmthDb, -6, 9));
  presenceFilter.configurePeaking(sr, 3300, 1.1, clamp(presenceDb, -6, 9));

  const wetMix = clamp(mix, 0, 1);
  const dryMix = 1 - wetMix;
  const outL = new Float32Array(n);
  const outR = new Float32Array(n);

  for (let i = 0; i < n; i++) {
    let processed = formanted[i];
    if (Math.abs(warmthDb) > 0.1) processed = warmthFilter.processLeft(processed);
    if (Math.abs(presenceDb) > 0.1) processed = presenceFilter.processLeft(processed);
    processed = Math.tanh(processed);

    outL[i] = clamp(source.left[i] * dryMix + processed * wetMix, -1, 1);
    outR[i] = clamp(source.right[i] * dryMix + processed * wetMix, -1, 1);
  }

  onProgress?.(1);
  return { left: outL, right: outR, sampleRate: sr };
}
