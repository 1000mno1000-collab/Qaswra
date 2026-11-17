import { StereoAudioBuffer } from "../types/studio";
import { clamp } from "./biquad";
import { getFrameCount, resampleStereo } from "./audioEditorOps";

export const TARGET_SAMPLE_RATE = 44100;

export function encodeStereoWavBytes(buffer: StereoAudioBuffer): Uint8Array {
  const numFrames = getFrameCount(buffer);
  const numChannels = 2;
  const bitsPerSample = 16;
  const byteRate = buffer.sampleRate * numChannels * (bitsPerSample / 8);
  const blockAlign = numChannels * (bitsPerSample / 8);
  const dataSize = numFrames * blockAlign;
  const totalBytes = 44 + dataSize;

  const arrayBuf = new ArrayBuffer(totalBytes);
  const view = new DataView(arrayBuf);

  const writeStr = (offset: number, s: string) => {
    for (let i = 0; i < s.length; i++) view.setUint8(offset + i, s.charCodeAt(i));
  };

  writeStr(0, "RIFF");
  view.setUint32(4, 36 + dataSize, true);
  writeStr(8, "WAVE");
  writeStr(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, numChannels, true);
  view.setUint32(24, buffer.sampleRate, true);
  view.setUint32(28, byteRate, true);
  view.setUint16(32, blockAlign, true);
  view.setUint16(34, bitsPerSample, true);
  writeStr(36, "data");
  view.setUint32(40, dataSize, true);

  let pos = 44;
  for (let i = 0; i < numFrames; i++) {
    const l = Math.round(clamp(buffer.left[i], -1, 1) * 32767);
    const r = Math.round(clamp(buffer.right[i], -1, 1) * 32767);
    view.setInt16(pos, l, true);
    view.setInt16(pos + 2, r, true);
    pos += 4;
  }

  return new Uint8Array(arrayBuf);
}

export function encodeCompactMonoWavBase64(
  buffer: StereoAudioBuffer,
  targetSampleRate = 16000
): string {
  const resampled =
    buffer.sampleRate !== targetSampleRate
      ? resampleStereo(buffer, targetSampleRate)
      : buffer;
  const maxFrames = targetSampleRate * 300; // cap 5 mins
  const numFrames = Math.min(getFrameCount(resampled), maxFrames);
  const dataSize = numFrames * 2;
  const arrayBuf = new ArrayBuffer(44 + dataSize);
  const view = new DataView(arrayBuf);

  const writeStr = (offset: number, s: string) => {
    for (let i = 0; i < s.length; i++) view.setUint8(offset + i, s.charCodeAt(i));
  };

  writeStr(0, "RIFF");
  view.setUint32(4, 36 + dataSize, true);
  writeStr(8, "WAVE");
  writeStr(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true); // Mono
  view.setUint32(24, targetSampleRate, true);
  view.setUint32(28, targetSampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeStr(36, "data");
  view.setUint32(40, dataSize, true);

  let pos = 44;
  for (let i = 0; i < numFrames; i++) {
    const mono = clamp((resampled.left[i] + resampled.right[i]) * 0.5, -1, 1);
    view.setInt16(pos, Math.round(mono * 32767), true);
    pos += 2;
  }

  return uint8ToBase64(new Uint8Array(arrayBuf));
}

export function uint8ToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

export function base64ToUint8(b64: string): Uint8Array {
  const binary = atob(b64);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    out[i] = binary.charCodeAt(i);
  }
  return out;
}

export async function decodeAudioArrayBuffer(
  arrayBuffer: ArrayBuffer
): Promise<StereoAudioBuffer> {
  const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
  const ctx = new AudioCtx({ sampleRate: TARGET_SAMPLE_RATE });
  try {
    const decoded = await ctx.decodeAudioData(arrayBuffer.slice(0));
    const left = new Float32Array(decoded.getChannelData(0));
    const right =
      decoded.numberOfChannels > 1
        ? new Float32Array(decoded.getChannelData(1))
        : new Float32Array(left);
    const raw: StereoAudioBuffer = {
      left,
      right,
      sampleRate: decoded.sampleRate,
    };
    return raw.sampleRate !== TARGET_SAMPLE_RATE
      ? resampleStereo(raw, TARGET_SAMPLE_RATE)
      : raw;
  } finally {
    await ctx.close().catch(() => undefined);
  }
}

export async function decodeAudioFile(file: File): Promise<StereoAudioBuffer> {
  const buf = await file.arrayBuffer();
  return decodeAudioArrayBuffer(buf);
}
