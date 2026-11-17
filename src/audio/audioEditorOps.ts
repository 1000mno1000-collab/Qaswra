import { StereoAudioBuffer } from "../types/studio";
import { clamp } from "./biquad";

export function getFrameCount(buf: StereoAudioBuffer): number {
  return Math.min(buf.left.length, buf.right.length);
}

export function getDurationMs(buf: StereoAudioBuffer): number {
  const frames = getFrameCount(buf);
  return buf.sampleRate > 0 ? Math.round((frames * 1000) / buf.sampleRate) : 0;
}

export function computeWaveformPeaks(
  buf: StereoAudioBuffer,
  buckets = 120
): number[] {
  const total = getFrameCount(buf);
  if (total === 0 || buckets <= 0) return [];
  const result: number[] = [];
  const step = total / buckets;
  for (let i = 0; i < buckets; i++) {
    const start = Math.max(0, Math.min(total - 1, Math.floor(i * step)));
    const end = Math.max(start + 1, Math.min(total, Math.floor((i + 1) * step)));
    let peak = 0;
    const stride = Math.max(1, Math.floor((end - start) / 64));
    for (let idx = start; idx < end; idx += stride) {
      const amp = Math.max(Math.abs(buf.left[idx]), Math.abs(buf.right[idx]));
      if (amp > peak) peak = amp;
    }
    result.push(clamp(peak, 0, 1));
  }
  return result;
}

export function resampleStereo(
  buffer: StereoAudioBuffer,
  targetRate: number
): StereoAudioBuffer {
  const total = getFrameCount(buffer);
  if (buffer.sampleRate === targetRate || total === 0) return buffer;
  const ratio = buffer.sampleRate / targetRate;
  const outFrames = Math.max(1, Math.floor(total / ratio));
  const outL = new Float32Array(outFrames);
  const outR = new Float32Array(outFrames);
  const maxIdx = total - 1;
  for (let i = 0; i < outFrames; i++) {
    const pos = i * ratio;
    const idx0 = clamp(Math.floor(pos), 0, maxIdx);
    const idx1 = clamp(idx0 + 1, 0, maxIdx);
    const frac = pos - idx0;
    outL[i] = buffer.left[idx0] + (buffer.left[idx1] - buffer.left[idx0]) * frac;
    outR[i] = buffer.right[idx0] + (buffer.right[idx1] - buffer.right[idx0]) * frac;
  }
  return { left: outL, right: outR, sampleRate: targetRate };
}

export function trimToSelection(
  buffer: StereoAudioBuffer,
  startFraction: number,
  endFraction: number
): StereoAudioBuffer {
  const total = getFrameCount(buffer);
  if (total === 0) return buffer;
  const s = clamp(Math.floor(startFraction * total), 0, total - 1);
  const e = clamp(Math.floor(endFraction * total), s + 1, total);
  return {
    left: buffer.left.slice(s, e),
    right: buffer.right.slice(s, e),
    sampleRate: buffer.sampleRate,
  };
}

export function cutSelection(
  buffer: StereoAudioBuffer,
  startFraction: number,
  endFraction: number
): StereoAudioBuffer {
  const total = getFrameCount(buffer);
  if (total === 0) return buffer;
  const s = clamp(Math.floor(startFraction * total), 0, total - 1);
  const e = clamp(Math.floor(endFraction * total), s + 1, total);
  const remaining = total - (e - s);
  if (remaining <= 0) return buffer;

  const outL = new Float32Array(remaining);
  const outR = new Float32Array(remaining);
  outL.set(buffer.left.subarray(0, s), 0);
  outL.set(buffer.left.subarray(e, total), s);
  outR.set(buffer.right.subarray(0, s), 0);
  outR.set(buffer.right.subarray(e, total), s);
  return { left: outL, right: outR, sampleRate: buffer.sampleRate };
}

export function mergeClips(
  first: StereoAudioBuffer,
  second: StereoAudioBuffer,
  crossfadeMs = 15
): StereoAudioBuffer {
  const alignedSecond =
    second.sampleRate === first.sampleRate
      ? second
      : resampleStereo(second, first.sampleRate);
  const n1 = getFrameCount(first);
  const n2 = getFrameCount(alignedSecond);
  if (n1 === 0) return alignedSecond;
  if (n2 === 0) return first;

  const xfade = Math.min(
    Math.floor((crossfadeMs / 1000) * first.sampleRate),
    Math.floor(n1 / 4),
    Math.floor(n2 / 4)
  );
  const totalOut = n1 + n2 - xfade;
  const outL = new Float32Array(totalOut);
  const outR = new Float32Array(totalOut);

  outL.set(first.left.subarray(0, n1 - xfade), 0);
  outR.set(first.right.subarray(0, n1 - xfade), 0);

  const overlapStart = n1 - xfade;
  for (let i = 0; i < xfade; i++) {
    const t = i / Math.max(1, xfade);
    outL[overlapStart + i] =
      first.left[n1 - xfade + i] * (1 - t) + alignedSecond.left[i] * t;
    outR[overlapStart + i] =
      first.right[n1 - xfade + i] * (1 - t) + alignedSecond.right[i] * t;
  }

  outL.set(alignedSecond.left.subarray(xfade, n2), n1);
  outR.set(alignedSecond.right.subarray(xfade, n2), n1);
  return { left: outL, right: outR, sampleRate: first.sampleRate };
}

export function applyGainDb(
  buffer: StereoAudioBuffer,
  gainDb: number
): StereoAudioBuffer {
  const n = getFrameCount(buffer);
  const linear = Math.pow(10, gainDb / 20);
  const outL = new Float32Array(n);
  const outR = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    outL[i] = clamp(buffer.left[i] * linear, -1, 1);
    outR[i] = clamp(buffer.right[i] * linear, -1, 1);
  }
  return { left: outL, right: outR, sampleRate: buffer.sampleRate };
}

export function normalizeAudio(
  buffer: StereoAudioBuffer,
  targetPeakDb = -1.0
): StereoAudioBuffer {
  const n = getFrameCount(buffer);
  if (n === 0) return buffer;
  let maxPeak = 0;
  for (let i = 0; i < n; i++) {
    const p = Math.max(Math.abs(buffer.left[i]), Math.abs(buffer.right[i]));
    if (p > maxPeak) maxPeak = p;
  }
  if (maxPeak < 1e-5) return buffer;
  const targetLinear = Math.pow(10, targetPeakDb / 20);
  const scale = targetLinear / maxPeak;
  const outL = new Float32Array(n);
  const outR = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    outL[i] = clamp(buffer.left[i] * scale, -1, 1);
    outR[i] = clamp(buffer.right[i] * scale, -1, 1);
  }
  return { left: outL, right: outR, sampleRate: buffer.sampleRate };
}

export function applyFadeIn(
  buffer: StereoAudioBuffer,
  fadeSeconds: number
): StereoAudioBuffer {
  const total = getFrameCount(buffer);
  if (total === 0 || fadeSeconds <= 0) return buffer;
  const fadeFrames = Math.min(total, Math.round(fadeSeconds * buffer.sampleRate));
  const outL = new Float32Array(buffer.left);
  const outR = new Float32Array(buffer.right);
  const denom = Math.max(1, fadeFrames - 1);
  for (let i = 0; i < fadeFrames; i++) {
    const t = i / denom;
    const gain = Math.sin(t * Math.PI * 0.5);
    outL[i] *= gain;
    outR[i] *= gain;
  }
  return { left: outL, right: outR, sampleRate: buffer.sampleRate };
}

export function applyFadeOut(
  buffer: StereoAudioBuffer,
  fadeSeconds: number
): StereoAudioBuffer {
  const total = getFrameCount(buffer);
  if (total === 0 || fadeSeconds <= 0) return buffer;
  const fadeFrames = Math.min(total, Math.round(fadeSeconds * buffer.sampleRate));
  const outL = new Float32Array(buffer.left);
  const outR = new Float32Array(buffer.right);
  const start = total - fadeFrames;
  const denom = Math.max(1, fadeFrames - 1);
  for (let i = 0; i < fadeFrames; i++) {
    const t = i / denom;
    const gain = Math.cos(t * Math.PI * 0.5);
    outL[start + i] *= gain;
    outR[start + i] *= gain;
  }
  return { left: outL, right: outR, sampleRate: buffer.sampleRate };
}

export function mixTracks(
  main: StereoAudioBuffer,
  background: StereoAudioBuffer,
  mainGain = 1.0,
  bgGain = 0.45
): StereoAudioBuffer {
  const n1 = getFrameCount(main);
  if (n1 === 0) return background;
  const alignedBg =
    background.sampleRate === main.sampleRate
      ? background
      : resampleStereo(background, main.sampleRate);
  const n2 = getFrameCount(alignedBg);
  if (n2 === 0) return main;

  const outFrames = Math.max(n1, n2);
  const outL = new Float32Array(outFrames);
  const outR = new Float32Array(outFrames);
  for (let i = 0; i < outFrames; i++) {
    const mL = i < n1 ? main.left[i] * mainGain : 0;
    const mR = i < n1 ? main.right[i] * mainGain : 0;
    const bL = i < n2 ? alignedBg.left[i] * bgGain : 0;
    const bR = i < n2 ? alignedBg.right[i] * bgGain : 0;
    outL[i] = Math.tanh(mL + bL);
    outR[i] = Math.tanh(mR + bR);
  }
  return { left: outL, right: outR, sampleRate: main.sampleRate };
}

export function formatDurationMs(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}
