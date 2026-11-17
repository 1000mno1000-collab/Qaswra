import { Mp3Encoder } from "@breezystack/lamejs";
import { ExportAudioFormat, EXPORT_FORMAT_META, StereoAudioBuffer } from "../types/studio";
import { clamp } from "./biquad";
import { getFrameCount } from "./audioEditorOps";
import { encodeStereoWavBytes } from "./wavCodec";

function encodeMp3Bytes(
  buffer: StereoAudioBuffer,
  bitrateKbps = 192,
  onProgress?: (p: number) => void
): Uint8Array {
  const n = getFrameCount(buffer);
  const encoder = new Mp3Encoder(2, buffer.sampleRate, bitrateKbps);
  const chunks: Uint8Array[] = [];
  const blockSize = 1152;
  const leftI16 = new Int16Array(blockSize);
  const rightI16 = new Int16Array(blockSize);

  for (let offset = 0; offset < n; offset += blockSize) {
    const count = Math.min(blockSize, n - offset);
    for (let i = 0; i < count; i++) {
      leftI16[i] = Math.round(clamp(buffer.left[offset + i], -1, 1) * 32767);
      rightI16[i] = Math.round(clamp(buffer.right[offset + i], -1, 1) * 32767);
    }
    const mp3buf = encoder.encodeBuffer(
      leftI16.subarray(0, count),
      rightI16.subarray(0, count)
    );
    if (mp3buf.length > 0) chunks.push(new Uint8Array(mp3buf));
    onProgress?.(Math.min(0.95, (offset + count) / Math.max(1, n)));
  }

  const end = encoder.flush();
  if (end.length > 0) chunks.push(new Uint8Array(end));
  onProgress?.(1);

  const totalLen = chunks.reduce((acc, c) => acc + c.length, 0);
  const out = new Uint8Array(totalLen);
  let pos = 0;
  for (const c of chunks) {
    out.set(c, pos);
    pos += c.length;
  }
  return out;
}

function computeFlacCrc8(data: Uint8Array, len: number): number {
  let crc = 0;
  for (let i = 0; i < len; i++) {
    crc ^= data[i] & 0xff;
    for (let b = 0; b < 8; b++) {
      crc = (crc & 0x80) !== 0 ? ((crc << 1) ^ 0x07) & 0xff : (crc << 1) & 0xff;
    }
  }
  return crc & 0xff;
}

function computeFlacCrc16(data: Uint8Array): number {
  let crc = 0;
  for (let i = 0; i < data.length; i++) {
    crc ^= (data[i] & 0xff) << 8;
    for (let b = 0; b < 8; b++) {
      crc = (crc & 0x8000) !== 0 ? ((crc << 1) ^ 0x8005) & 0xffff : (crc << 1) & 0xffff;
    }
  }
  return crc & 0xffff;
}

function appendUtf8Number(out: number[], value: number): void {
  if (value < 0x80) {
    out.push(value);
    return;
  }
  let bytesNeeded = 6;
  if (value < 0x800) bytesNeeded = 2;
  else if (value < 0x10000) bytesNeeded = 3;
  else if (value < 0x200000) bytesNeeded = 4;
  else if (value < 0x4000000) bytesNeeded = 5;

  const leadMask = (0xff << (8 - bytesNeeded)) & 0xff;
  const firstShift = (bytesNeeded - 1) * 6;
  out.push(leadMask | Math.floor(value / Math.pow(2, firstShift)));
  for (let i = bytesNeeded - 2; i >= 0; i--) {
    const shift = i * 6;
    out.push(0x80 | (Math.floor(value / Math.pow(2, shift)) & 0x3f));
  }
}

function buildFlacFrameHeader(
  frameNumber: number,
  blockSize: number,
  sampleRate: number
): Uint8Array {
  const h: number[] = [0xff, 0xf8];
  const bsCode =
    blockSize === 4096 ? 0x0c : blockSize === 2048 ? 0x0b : blockSize === 1024 ? 0x0a : 0x07;
  const srMap: Record<number, number> = {
    44100: 0x09,
    48000: 0x0a,
    32000: 0x08,
    24000: 0x06,
    22050: 0x05,
    16000: 0x04,
    8000: 0x03,
  };
  const srCode = srMap[sampleRate] ?? 0x00;
  h.push((bsCode << 4) | srCode);
  h.push(0x18); // 2 ch independent | 16-bit
  appendUtf8Number(h, frameNumber);
  if (bsCode === 0x07) {
    const bsMinusOne = clamp(blockSize - 1, 0, 65535);
    h.push((bsMinusOne >> 8) & 0xff, bsMinusOne & 0xff);
  }
  const raw = new Uint8Array(h.length + 1);
  raw.set(h, 0);
  raw[h.length] = computeFlacCrc8(raw, h.length);
  return raw;
}

function encodeFlacBytes(
  buffer: StereoAudioBuffer,
  onProgress?: (p: number) => void
): Uint8Array {
  const totalSamples = getFrameCount(buffer);
  const sampleRate = clamp(buffer.sampleRate, 1, 655350);
  const blockSize = 4096;
  const parts: Uint8Array[] = [];

  // fLaC magic + STREAMINFO header
  const intro = new Uint8Array(42);
  intro[0] = 0x66;
  intro[1] = 0x4c;
  intro[2] = 0x61;
  intro[3] = 0x43;
  intro[4] = 0x80;
  intro[5] = 0x00;
  intro[6] = 0x00;
  intro[7] = 34;

  const minBlock = totalSamples >= 16 && totalSamples < blockSize ? totalSamples : blockSize;
  intro[8] = (minBlock >> 8) & 0xff;
  intro[9] = minBlock & 0xff;
  intro[10] = (blockSize >> 8) & 0xff;
  intro[11] = blockSize & 0xff;

  const packed =
    (BigInt(sampleRate & 0xfffff) << 44n) |
    (1n << 41n) |
    (15n << 36n) |
    BigInt(totalSamples & 0xffffffff);
  for (let i = 0; i < 8; i++) {
    intro[18 + i] = Number((packed >> BigInt((7 - i) * 8)) & 0xffn);
  }
  parts.push(intro);

  let offset = 0;
  let frameNumber = 0;
  while (offset < totalSamples) {
    const count = Math.min(blockSize, totalSamples - offset);
    const header = buildFlacFrameHeader(frameNumber, count, sampleRate);
    const frameData = new Uint8Array(header.length + 2 + count * 4);
    frameData.set(header, 0);
    let p = header.length;

    frameData[p++] = 0x02;
    for (let i = 0; i < count; i++) {
      const s = Math.round(clamp(buffer.left[offset + i], -1, 1) * 32767);
      frameData[p++] = (s >> 8) & 0xff;
      frameData[p++] = s & 0xff;
    }

    frameData[p++] = 0x02;
    for (let i = 0; i < count; i++) {
      const s = Math.round(clamp(buffer.right[offset + i], -1, 1) * 32767);
      frameData[p++] = (s >> 8) & 0xff;
      frameData[p++] = s & 0xff;
    }

    const crc16 = computeFlacCrc16(frameData);
    const footer = new Uint8Array([(crc16 >> 8) & 0xff, crc16 & 0xff]);
    parts.push(frameData, footer);

    offset += count;
    frameNumber++;
    onProgress?.(Math.min(0.99, offset / Math.max(1, totalSamples)));
  }

  onProgress?.(1);
  const totalBytes = parts.reduce((acc, p) => acc + p.length, 0);
  const out = new Uint8Array(totalBytes);
  let pos = 0;
  for (const part of parts) {
    out.set(part, pos);
    pos += part.length;
  }
  return out;
}

async function encodeViaMediaRecorder(
  buffer: StereoAudioBuffer,
  preferredMimeTypes: string[],
  onProgress?: (p: number) => void
): Promise<{ bytes: Uint8Array; mimeType: string } | null> {
  if (typeof MediaRecorder === "undefined") return null;
  const mime = preferredMimeTypes.find((m) => MediaRecorder.isTypeSupported(m));
  if (!mime) return null;

  const AudioCtx =
    window.AudioContext ||
    (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
  const ctx = new AudioCtx({ sampleRate: buffer.sampleRate });
  try {
    const frames = getFrameCount(buffer);
    const audioBuf = ctx.createBuffer(2, frames, buffer.sampleRate);
    audioBuf.copyToChannel(new Float32Array(buffer.left.subarray(0, frames)), 0);
    audioBuf.copyToChannel(new Float32Array(buffer.right.subarray(0, frames)), 1);

    const dest = ctx.createMediaStreamDestination();
    const src = ctx.createBufferSource();
    src.buffer = audioBuf;
    src.connect(dest);

    const chunks: Blob[] = [];
    const recorder = new MediaRecorder(dest.stream, { mimeType: mime, audioBitsPerSecond: 192000 });
    const donePromise = new Promise<Blob>((resolve) => {
      recorder.ondataavailable = (e) => {
        if (e.data.size > 0) chunks.push(e.data);
      };
      recorder.onstop = () => resolve(new Blob(chunks, { type: mime }));
    });

    const durationMs = Math.max(200, (frames * 1000) / buffer.sampleRate);
    const startMs = Date.now();
    const interval = window.setInterval(() => {
      onProgress?.(clamp((Date.now() - startMs) / durationMs, 0.05, 0.95));
    }, 120);

    recorder.start();
    src.start();
    await new Promise<void>((resolve) => {
      src.onended = () => resolve();
    });
    window.clearInterval(interval);
    recorder.stop();
    const blob = await donePromise;
    onProgress?.(1);
    const ab = await blob.arrayBuffer();
    return { bytes: new Uint8Array(ab), mimeType: mime };
  } catch {
    return null;
  } finally {
    await ctx.close().catch(() => undefined);
  }
}

export async function exportAudioBlob(
  buffer: StereoAudioBuffer,
  format: ExportAudioFormat,
  onProgress?: (p: number) => void
): Promise<{ blob: Blob; extension: string; mimeType: string }> {
  const meta = EXPORT_FORMAT_META[format];
  if (format === ExportAudioFormat.WAV) {
    const bytes = encodeStereoWavBytes(buffer);
    onProgress?.(1);
    return {
      blob: new Blob([bytes.buffer as ArrayBuffer], { type: meta.mimeType }),
      extension: meta.extension,
      mimeType: meta.mimeType,
    };
  }
  if (format === ExportAudioFormat.MP3) {
    const bytes = encodeMp3Bytes(buffer, 192, onProgress);
    return {
      blob: new Blob([bytes.buffer as ArrayBuffer], { type: meta.mimeType }),
      extension: meta.extension,
      mimeType: meta.mimeType,
    };
  }
  if (format === ExportAudioFormat.FLAC) {
    const bytes = encodeFlacBytes(buffer, onProgress);
    return {
      blob: new Blob([bytes.buffer as ArrayBuffer], { type: meta.mimeType }),
      extension: meta.extension,
      mimeType: meta.mimeType,
    };
  }
  if (format === ExportAudioFormat.OGG) {
    const rec = await encodeViaMediaRecorder(
      buffer,
      ["audio/ogg;codecs=opus", "audio/webm;codecs=opus", "audio/ogg"],
      onProgress
    );
    if (rec) {
      return {
        blob: new Blob([rec.bytes.buffer as ArrayBuffer], { type: rec.mimeType }),
        extension: rec.mimeType.includes("ogg") ? "ogg" : "webm",
        mimeType: rec.mimeType,
      };
    }
    const mp3 = encodeMp3Bytes(buffer, 192, onProgress);
    return {
      blob: new Blob([mp3.buffer as ArrayBuffer], { type: "audio/mpeg" }),
      extension: "mp3",
      mimeType: "audio/mpeg",
    };
  }

  // M4A (AAC in MP4 if browser MediaRecorder supports it, otherwise AAC/MP3 or WAV)
  const m4a = await encodeViaMediaRecorder(
    buffer,
    ["audio/mp4;codecs=mp4a.40.2", "audio/mp4", "audio/aac"],
    onProgress
  );
  if (m4a) {
    return {
      blob: new Blob([m4a.bytes.buffer as ArrayBuffer], { type: m4a.mimeType }),
      extension: "m4a",
      mimeType: m4a.mimeType,
    };
  }
  const mp3 = encodeMp3Bytes(buffer, 192, onProgress);
  return {
    blob: new Blob([mp3.buffer as ArrayBuffer], { type: "audio/mpeg" }),
    extension: "mp3",
    mimeType: "audio/mpeg",
  };
}

export function triggerDownloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.setTimeout(() => URL.revokeObjectURL(url), 5000);
}
