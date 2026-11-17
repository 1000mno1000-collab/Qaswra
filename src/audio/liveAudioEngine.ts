import { DspConfig, EffectType, StereoAudioBuffer } from "../types/studio";
import { getFrameCount, resampleStereo } from "./audioEditorOps";
import { clamp } from "./biquad";
import { DspPipeline } from "./dspPipeline";
import { TARGET_SAMPLE_RATE } from "./wavCodec";

export interface PlaybackTelemetry {
  isPlaying: boolean;
  positionMs: number;
  durationMs: number;
  outputLevel: number;
  gainReductionDb: number;
  noiseSuppressionDb: number;
  waveformBars: number[];
}

export class StudioAudioPlayer {
  private buffer: StereoAudioBuffer | null = null;
  private config: DspConfig;
  private pipeline: DspPipeline | null = null;

  private audioCtx: AudioContext | null = null;
  private scriptNode: ScriptProcessorNode | null = null;
  private gainNode: GainNode | null = null;

  private playhead = 0; // fractional frame index
  private isPlaying = false;
  private loopEnabled = false;
  private loopStartFrac = 0;
  private loopEndFrac = 1;

  private latestBars: number[] = new Array(48).fill(0.06);
  private latestLevel = 0;
  private onTelemetry: (t: PlaybackTelemetry) => void;
  private tailFlushFramesRemaining = 0;

  constructor(initialConfig: DspConfig, onTelemetry: (t: PlaybackTelemetry) => void) {
    this.config = initialConfig;
    this.onTelemetry = onTelemetry;
  }

  setBuffer(buffer: StereoAudioBuffer | null, resetPosition = true): void {
    this.buffer = buffer;
    if (resetPosition) {
      this.playhead = 0;
      this.tailFlushFramesRemaining = 0;
      this.pipeline?.reset();
    } else if (buffer) {
      this.playhead = clamp(this.playhead, 0, Math.max(0, getFrameCount(buffer) - 1));
    }
    this.emitTelemetry();
  }

  updateConfig(config: DspConfig): void {
    this.config = config;
    this.pipeline?.updateConfig(config);
  }

  setLoop(enabled: boolean, startFrac = 0, endFrac = 1): void {
    this.loopEnabled = enabled;
    this.loopStartFrac = clamp(startFrac, 0, 0.99);
    this.loopEndFrac = clamp(endFrac, this.loopStartFrac + 0.01, 1);
  }

  seekToFraction(frac: number): void {
    if (!this.buffer) return;
    const total = getFrameCount(this.buffer);
    this.playhead = clamp(frac, 0, 1) * Math.max(0, total - 1);
    this.pipeline?.reset();
    this.emitTelemetry();
  }

  async play(): Promise<void> {
    if (!this.buffer || getFrameCount(this.buffer) === 0) return;

    const AudioCtx =
      window.AudioContext ||
      (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    if (!this.audioCtx) {
      this.audioCtx = new AudioCtx({ sampleRate: this.buffer.sampleRate });
    }
    if (this.audioCtx.state === "suspended") {
      await this.audioCtx.resume();
    }

    if (!this.pipeline || this.audioCtx.sampleRate !== this.buffer.sampleRate) {
      this.pipeline = new DspPipeline(this.audioCtx.sampleRate, this.config);
    } else {
      this.pipeline.updateConfig(this.config);
    }

    const total = getFrameCount(this.buffer);
    if (this.loopEnabled) {
      const s = Math.floor(this.loopStartFrac * total);
      const e = Math.floor(this.loopEndFrac * total);
      if (this.playhead < s || this.playhead >= e) this.playhead = s;
    } else if (this.playhead >= total - 1) {
      this.playhead = 0;
    }

    this.tearDownNodes();

    const blockFrames = 2048;
    const node = this.audioCtx.createScriptProcessor(blockFrames, 0, 2);
    const outGain = this.audioCtx.createGain();
    outGain.gain.value = 1;

    const tmpL = new Float32Array(blockFrames);
    const tmpR = new Float32Array(blockFrames);

    node.onaudioprocess = (ev) => {
      const outL = ev.outputBuffer.getChannelData(0);
      const outR = ev.outputBuffer.getChannelData(1);

      if (!this.isPlaying || !this.buffer || !this.pipeline) {
        outL.fill(0);
        outR.fill(0);
        return;
      }

      const buf = this.buffer;
      const framesTotal = getFrameCount(buf);
      const rangeStart = this.loopEnabled
        ? Math.floor(this.loopStartFrac * framesTotal)
        : 0;
      const rangeEnd = this.loopEnabled
        ? Math.max(rangeStart + 1, Math.floor(this.loopEndFrac * framesTotal))
        : framesTotal;

      const tsSlot = this.config.chain.find((s) => s.type === EffectType.TIME_STRETCH);
      const rateRatio =
        !this.config.bypassAll && tsSlot?.enabled
          ? clamp(this.config.timeStretch.tempoRatio, 0.5, 2.0)
          : 1.0;

      const srRatio = buf.sampleRate / (this.audioCtx?.sampleRate || buf.sampleRate);
      const step = rateRatio * srRatio;

      let reachedEnd = false;
      for (let i = 0; i < blockFrames; i++) {
        if (this.playhead >= rangeEnd) {
          if (this.loopEnabled) {
            this.playhead = rangeStart;
          } else {
            tmpL[i] = 0;
            tmpR[i] = 0;
            reachedEnd = true;
            continue;
          }
        }
        const i0 = clamp(Math.floor(this.playhead), 0, framesTotal - 1);
        const i1 = clamp(i0 + 1, 0, framesTotal - 1);
        const f = this.playhead - i0;
        tmpL[i] = buf.left[i0] + (buf.left[i1] - buf.left[i0]) * f;
        tmpR[i] = buf.right[i0] + (buf.right[i1] - buf.right[i0]) * f;
        this.playhead += step;
      }

      this.pipeline.processStereoBlock(tmpL, tmpR, blockFrames);

      let peak = 0;
      const barCount = 48;
      const binStep = Math.max(1, Math.floor(blockFrames / barCount));
      const newBars = new Array<number>(barCount);
      for (let b = 0; b < barCount; b++) {
        let binPeak = 0;
        const start = b * binStep;
        const end = Math.min(blockFrames, start + binStep);
        for (let k = start; k < end; k++) {
          const amp = Math.max(Math.abs(tmpL[k]), Math.abs(tmpR[k]));
          if (amp > binPeak) binPeak = amp;
        }
        if (binPeak > peak) peak = binPeak;
        newBars[b] = clamp(this.latestBars[b] * 0.45 + binPeak * 0.55, 0.04, 1);
      }

      outL.set(tmpL);
      outR.set(tmpR);
      this.latestBars = newBars;
      this.latestLevel = peak;

      if (reachedEnd && !this.loopEnabled) {
        if (this.tailFlushFramesRemaining === 0) {
          const echoOn =
            !this.config.bypassAll &&
            this.config.chain.some((s) => s.type === EffectType.ECHO_REVERB && s.enabled);
          this.tailFlushFramesRemaining = echoOn
            ? Math.floor((this.audioCtx?.sampleRate || 44100) * 0.8)
            : 1;
        }
        this.tailFlushFramesRemaining -= blockFrames;
        if (this.tailFlushFramesRemaining <= 0 || peak < 0.002) {
          this.isPlaying = false;
          this.playhead = 0;
          this.tailFlushFramesRemaining = 0;
          this.latestLevel = 0;
          this.latestBars = new Array(48).fill(0.06);
          queueMicrotask(() => this.tearDownNodes());
        }
      }

      this.emitTelemetry();
    };

    node.connect(outGain);
    outGain.connect(this.audioCtx.destination);
    this.scriptNode = node;
    this.gainNode = outGain;
    this.isPlaying = true;
    this.tailFlushFramesRemaining = 0;
    this.emitTelemetry();
  }

  pause(): void {
    this.isPlaying = false;
    this.latestLevel = 0;
    this.tearDownNodes();
    this.emitTelemetry();
  }

  stop(): void {
    this.isPlaying = false;
    this.playhead = 0;
    this.tailFlushFramesRemaining = 0;
    this.latestLevel = 0;
    this.latestBars = new Array(48).fill(0.06);
    this.pipeline?.reset();
    this.tearDownNodes();
    this.emitTelemetry();
  }

  dispose(): void {
    this.stop();
    if (this.audioCtx) {
      this.audioCtx.close().catch(() => undefined);
      this.audioCtx = null;
    }
  }

  private tearDownNodes(): void {
    if (this.scriptNode) {
      try {
        this.scriptNode.disconnect();
      } catch {
        // ignore
      }
      this.scriptNode.onaudioprocess = null;
      this.scriptNode = null;
    }
    if (this.gainNode) {
      try {
        this.gainNode.disconnect();
      } catch {
        // ignore
      }
      this.gainNode = null;
    }
  }

  private emitTelemetry(): void {
    const frames = this.buffer ? getFrameCount(this.buffer) : 0;
    const sr = this.buffer?.sampleRate || 44100;
    const durationMs = sr > 0 ? Math.round((frames * 1000) / sr) : 0;
    const positionMs = sr > 0 ? Math.round((this.playhead * 1000) / sr) : 0;
    this.onTelemetry({
      isPlaying: this.isPlaying,
      positionMs: clamp(positionMs, 0, durationMs),
      durationMs,
      outputLevel: this.latestLevel,
      gainReductionDb: this.pipeline?.currentGainReductionDb ?? 0,
      noiseSuppressionDb: this.pipeline?.currentNoiseSuppressionDb ?? 0,
      waveformBars: this.latestBars,
    });
  }
}

export interface RecorderTelemetry {
  isRecording: boolean;
  isPaused: boolean;
  elapsedMs: number;
  micLevel: number;
  waveformBars: number[];
}

export class StudioAudioRecorder {
  private audioCtx: AudioContext | null = null;
  private stream: MediaStream | null = null;
  private sourceNode: MediaStreamAudioSourceNode | null = null;
  private procNode: ScriptProcessorNode | null = null;
  private silentGain: GainNode | null = null;

  private chunksL: Float32Array[] = [];
  private chunksR: Float32Array[] = [];
  private capturedFrames = 0;
  private sampleRate = TARGET_SAMPLE_RATE;

  private isRecording = false;
  private isPaused = false;
  private bars: number[] = new Array(48).fill(0.05);
  private level = 0;
  private onTelemetry: (t: RecorderTelemetry) => void;

  constructor(onTelemetry: (t: RecorderTelemetry) => void) {
    this.onTelemetry = onTelemetry;
  }

  async start(): Promise<boolean> {
    try {
      this.discard();
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: false,
          noiseSuppression: false,
          autoGainControl: false,
        },
      });
      const AudioCtx =
        window.AudioContext ||
        (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      const ctx = new AudioCtx({ sampleRate: TARGET_SAMPLE_RATE });
      if (ctx.state === "suspended") await ctx.resume();

      this.sampleRate = ctx.sampleRate;
      const source = ctx.createMediaStreamSource(stream);
      const proc = ctx.createScriptProcessor(2048, 2, 1);
      const zeroGain = ctx.createGain();
      zeroGain.gain.value = 0;

      proc.onaudioprocess = (ev) => {
        if (!this.isRecording || this.isPaused) return;
        const inL = ev.inputBuffer.getChannelData(0);
        const inR =
          ev.inputBuffer.numberOfChannels > 1 ? ev.inputBuffer.getChannelData(1) : inL;

        this.chunksL.push(new Float32Array(inL));
        this.chunksR.push(new Float32Array(inR));
        this.capturedFrames += inL.length;

        let peak = 0;
        const barCount = 48;
        const step = Math.max(1, Math.floor(inL.length / barCount));
        const nextBars = new Array<number>(barCount);
        for (let b = 0; b < barCount; b++) {
          let bp = 0;
          const s = b * step;
          const e = Math.min(inL.length, s + step);
          for (let k = s; k < e; k++) {
            const a = Math.max(Math.abs(inL[k]), Math.abs(inR[k]));
            if (a > bp) bp = a;
          }
          if (bp > peak) peak = bp;
          nextBars[b] = clamp(this.bars[b] * 0.4 + bp * 0.6, 0.04, 1);
        }
        this.bars = nextBars;
        this.level = peak;
        this.emit();
      };

      source.connect(proc);
      proc.connect(zeroGain);
      zeroGain.connect(ctx.destination);

      this.audioCtx = ctx;
      this.stream = stream;
      this.sourceNode = source;
      this.procNode = proc;
      this.silentGain = zeroGain;
      this.isRecording = true;
      this.isPaused = false;
      this.emit();
      return true;
    } catch {
      this.discard();
      return false;
    }
  }

  pause(): void {
    if (!this.isRecording) return;
    this.isPaused = true;
    this.level = 0;
    this.emit();
  }

  resume(): void {
    if (!this.isRecording) return;
    this.isPaused = false;
    this.emit();
  }

  stopAndBuildBuffer(): StereoAudioBuffer | null {
    const frames = this.capturedFrames;
    const sr = this.sampleRate;
    const cL = this.chunksL;
    const cR = this.chunksR;
    this.cleanupAudioResources();
    this.isRecording = false;
    this.isPaused = false;
    this.level = 0;
    this.bars = new Array(48).fill(0.05);
    this.chunksL = [];
    this.chunksR = [];
    this.capturedFrames = 0;
    this.emit();

    if (frames === 0) return null;
    const outL = new Float32Array(frames);
    const outR = new Float32Array(frames);
    let offset = 0;
    for (let i = 0; i < cL.length; i++) {
      outL.set(cL[i], offset);
      outR.set(cR[i], offset);
      offset += cL[i].length;
    }
    const raw: StereoAudioBuffer = { left: outL, right: outR, sampleRate: sr };
    return sr !== TARGET_SAMPLE_RATE ? resampleStereo(raw, TARGET_SAMPLE_RATE) : raw;
  }

  discard(): void {
    this.cleanupAudioResources();
    this.chunksL = [];
    this.chunksR = [];
    this.capturedFrames = 0;
    this.isRecording = false;
    this.isPaused = false;
    this.level = 0;
    this.bars = new Array(48).fill(0.05);
    this.emit();
  }

  private cleanupAudioResources(): void {
    try {
      this.procNode?.disconnect();
    } catch {
      // ignore
    }
    try {
      this.sourceNode?.disconnect();
    } catch {
      // ignore
    }
    try {
      this.silentGain?.disconnect();
    } catch {
      // ignore
    }
    if (this.procNode) this.procNode.onaudioprocess = null;
    this.stream?.getTracks().forEach((t) => t.stop());
    this.audioCtx?.close().catch(() => undefined);
    this.procNode = null;
    this.sourceNode = null;
    this.silentGain = null;
    this.stream = null;
    this.audioCtx = null;
  }

  private emit(): void {
    const elapsedMs =
      this.sampleRate > 0 ? Math.round((this.capturedFrames * 1000) / this.sampleRate) : 0;
    this.onTelemetry({
      isRecording: this.isRecording,
      isPaused: this.isPaused,
      elapsedMs,
      micLevel: this.level,
      waveformBars: this.bars,
    });
  }
}
