import {
  ChorusParams,
  CompressorParams,
  DistortionParams,
  EqualizerParams,
  FlangerParams,
  LimiterParams,
  PhaserParams,
  PitchShiftParams,
} from "../types/studio";
import { StereoBiquadFilter, clamp } from "./biquad";

export class EqualizerProcessor {
  private readonly bands = [
    new StereoBiquadFilter(),
    new StereoBiquadFilter(),
    new StereoBiquadFilter(),
    new StereoBiquadFilter(),
    new StereoBiquadFilter(),
  ];

  configure(sampleRate: number, p: EqualizerParams): void {
    this.bands[0].configureLowShelf(sampleRate, 60, 0.9, p.band60HzDb);
    this.bands[1].configurePeaking(sampleRate, 230, 1.0, p.band230HzDb);
    this.bands[2].configurePeaking(sampleRate, 910, 1.0, p.band910HzDb);
    this.bands[3].configurePeaking(sampleRate, 3600, 1.0, p.band3600HzDb);
    this.bands[4].configureHighShelf(sampleRate, 14000, 0.9, p.band14000HzDb);
  }

  processStereo(left: Float32Array, right: Float32Array, count: number): void {
    for (const band of this.bands) {
      band.processStereo(left, right, count);
    }
  }

  reset(): void {
    for (const band of this.bands) band.reset();
  }
}

export class PitchShiftProcessor {
  private readonly bufSize = 8192;
  private readonly mask = this.bufSize - 1;
  private readonly ringL = new Float32Array(8192);
  private readonly ringR = new Float32Array(8192);
  private writeIdx = 0;
  private readPosA = 0;
  private readPosB = 2048;
  private readonly grainSize = 2048;

  private readInterpolated(ring: Float32Array, pos: number): number {
    const i0 = Math.floor(pos) & this.mask;
    const i1 = (i0 + 1) & this.mask;
    const frac = pos - Math.floor(pos);
    return ring[i0] + (ring[i1] - ring[i0]) * frac;
  }

  processStereo(
    left: Float32Array,
    right: Float32Array,
    count: number,
    p: PitchShiftParams
  ): void {
    if (Math.abs(p.semitones) < 0.05) return;
    const pitchRatio = clamp(Math.pow(2, p.semitones / 12), 0.5, 2.0);
    const rateDiff = pitchRatio - 1.0;
    const wet = clamp(p.wetMix, 0, 1);
    const dry = 1 - wet;
    const halfGrain = this.grainSize * 0.5;

    for (let i = 0; i < count; i++) {
      const inL = left[i];
      const inR = right[i];
      this.ringL[this.writeIdx] = inL;
      this.ringR[this.writeIdx] = inR;

      const phaseA = (((this.writeIdx - this.readPosA) % this.grainSize) + this.grainSize) % this.grainSize;
      const weightA = 1 - Math.abs(phaseA - halfGrain) / halfGrain;
      const weightB = 1 - weightA;

      const outL =
        this.readInterpolated(this.ringL, this.readPosA) * weightA +
        this.readInterpolated(this.ringL, this.readPosB) * weightB;
      const outR =
        this.readInterpolated(this.ringR, this.readPosA) * weightA +
        this.readInterpolated(this.ringR, this.readPosB) * weightB;

      left[i] = inL * dry + outL * wet;
      right[i] = inR * dry + outR * wet;

      this.writeIdx = (this.writeIdx + 1) & this.mask;
      this.readPosA = (this.readPosA + pitchRatio) % this.bufSize;
      this.readPosB = (this.readPosB + pitchRatio) % this.bufSize;

      const distA = ((this.writeIdx - this.readPosA) + this.bufSize) % this.bufSize;
      if (distA < 64 || distA > this.grainSize + 512) {
        this.readPosA = ((this.writeIdx - halfGrain) + this.bufSize) % this.bufSize;
      }
      const distB = ((this.writeIdx - this.readPosB) + this.bufSize) % this.bufSize;
      if (distB < 64 || distB > this.grainSize + 512) {
        this.readPosB =
          ((this.writeIdx - (rateDiff >= 0 ? this.grainSize : halfGrain)) + this.bufSize) %
          this.bufSize;
      }
    }
  }

  reset(): void {
    this.ringL.fill(0);
    this.ringR.fill(0);
    this.writeIdx = 0;
    this.readPosA = 0;
    this.readPosB = this.grainSize / 2;
  }
}

export class ChorusProcessor {
  private readonly sampleRate: number;
  private readonly delayL: Float32Array;
  private readonly delayR: Float32Array;
  private idx = 0;
  private lfoPhase = 0;

  constructor(sampleRate = 44100) {
    this.sampleRate = sampleRate;
    const len = Math.floor(sampleRate * 0.08) + 8;
    this.delayL = new Float32Array(len);
    this.delayR = new Float32Array(len);
  }

  private readFrac(buf: Float32Array, delaySamples: number): number {
    const len = buf.length;
    let pos = this.idx - delaySamples;
    while (pos < 0) pos += len;
    const i0 = Math.floor(pos) % len;
    const i1 = (i0 + 1) % len;
    const frac = pos - Math.floor(pos);
    return buf[i0] + (buf[i1] - buf[i0]) * frac;
  }

  processStereo(
    left: Float32Array,
    right: Float32Array,
    count: number,
    p: ChorusParams
  ): void {
    const baseSamples = 0.015 * this.sampleRate;
    const depthSamples = clamp((p.depthMs / 1000) * this.sampleRate, 1, this.delayL.length * 0.4);
    const lfoInc = (2 * Math.PI * clamp(p.rateHz, 0.1, 8)) / this.sampleRate;
    const wet = clamp(p.mix, 0, 1);
    const dry = 1 - wet * 0.4;
    const len = this.delayL.length;

    for (let i = 0; i < count; i++) {
      const inL = left[i];
      const inR = right[i];
      this.delayL[this.idx] = inL;
      this.delayR[this.idx] = inR;

      const lfoL = Math.sin(this.lfoPhase);
      const lfoR = Math.sin(this.lfoPhase + Math.PI * 0.5);
      const voiceL1 = this.readFrac(this.delayL, baseSamples + lfoL * depthSamples);
      const voiceL2 = this.readFrac(this.delayL, baseSamples * 1.25 - lfoL * depthSamples * 0.7);
      const voiceR1 = this.readFrac(this.delayR, baseSamples + lfoR * depthSamples);
      const voiceR2 = this.readFrac(this.delayR, baseSamples * 1.25 - lfoR * depthSamples * 0.7);

      left[i] = clamp(inL * dry + (voiceL1 + voiceL2) * 0.5 * wet, -1, 1);
      right[i] = clamp(inR * dry + (voiceR1 + voiceR2) * 0.5 * wet, -1, 1);

      this.idx = (this.idx + 1) % len;
      this.lfoPhase += lfoInc;
      if (this.lfoPhase > Math.PI * 2) this.lfoPhase -= Math.PI * 2;
    }
  }

  reset(): void {
    this.delayL.fill(0);
    this.delayR.fill(0);
    this.idx = 0;
    this.lfoPhase = 0;
  }
}

export class FlangerProcessor {
  private readonly sampleRate: number;
  private readonly delayL: Float32Array;
  private readonly delayR: Float32Array;
  private idx = 0;
  private lfoPhase = 0;

  constructor(sampleRate = 44100) {
    this.sampleRate = sampleRate;
    const len = Math.floor(sampleRate * 0.03) + 8;
    this.delayL = new Float32Array(len);
    this.delayR = new Float32Array(len);
  }

  private readFrac(buf: Float32Array, delaySamples: number): number {
    const len = buf.length;
    let pos = this.idx - delaySamples;
    while (pos < 0) pos += len;
    const i0 = Math.floor(pos) % len;
    const i1 = (i0 + 1) % len;
    const frac = pos - Math.floor(pos);
    return buf[i0] + (buf[i1] - buf[i0]) * frac;
  }

  processStereo(
    left: Float32Array,
    right: Float32Array,
    count: number,
    p: FlangerParams
  ): void {
    const baseSamples = 0.0015 * this.sampleRate;
    const depthSamples = clamp((p.depthMs / 1000) * this.sampleRate, 2, this.delayL.length * 0.6);
    const lfoInc = (2 * Math.PI * clamp(p.rateHz, 0.05, 5)) / this.sampleRate;
    const fb = clamp(p.feedback, -0.85, 0.85);
    const wet = clamp(p.mix, 0, 1);
    const len = this.delayL.length;

    for (let i = 0; i < count; i++) {
      const inL = left[i];
      const inR = right[i];
      const lfoL = (Math.sin(this.lfoPhase) + 1) * 0.5;
      const lfoR = (Math.sin(this.lfoPhase + 0.6) + 1) * 0.5;

      const delayedL = this.readFrac(this.delayL, baseSamples + lfoL * depthSamples);
      const delayedR = this.readFrac(this.delayR, baseSamples + lfoR * depthSamples);

      this.delayL[this.idx] = clamp(inL + delayedL * fb, -1.5, 1.5);
      this.delayR[this.idx] = clamp(inR + delayedR * fb, -1.5, 1.5);

      left[i] = clamp(inL * (1 - wet * 0.5) + delayedL * wet, -1, 1);
      right[i] = clamp(inR * (1 - wet * 0.5) + delayedR * wet, -1, 1);

      this.idx = (this.idx + 1) % len;
      this.lfoPhase += lfoInc;
      if (this.lfoPhase > Math.PI * 2) this.lfoPhase -= Math.PI * 2;
    }
  }

  reset(): void {
    this.delayL.fill(0);
    this.delayR.fill(0);
    this.idx = 0;
    this.lfoPhase = 0;
  }
}

export class PhaserProcessor {
  private readonly sampleRate: number;
  private readonly zmL = new Float32Array(4);
  private readonly zmR = new Float32Array(4);
  private lastOutL = 0;
  private lastOutR = 0;
  private lfoPhase = 0;

  constructor(sampleRate = 44100) {
    this.sampleRate = sampleRate;
  }

  processStereo(
    left: Float32Array,
    right: Float32Array,
    count: number,
    p: PhaserParams
  ): void {
    const lfoInc = (2 * Math.PI * clamp(p.rateHz, 0.05, 6)) / this.sampleRate;
    const depth = clamp(p.depth, 0, 1);
    const fb = clamp(p.feedback, 0, 0.85);
    const wet = clamp(p.mix, 0, 1);
    const dry = 1 - wet * 0.35;

    for (let i = 0; i < count; i++) {
      const lfo = (Math.sin(this.lfoPhase) + 1) * 0.5;
      const sweepHz = 300 + lfo * depth * 3200;
      const d = (Math.PI * sweepHz) / this.sampleRate;
      const a1 = (1 - d) / (1 + d);

      let yL = left[i] + this.lastOutL * fb;
      let yR = right[i] + this.lastOutR * fb;
      for (let s = 0; s < 4; s++) {
        const tmpL = yL * -a1 + this.zmL[s];
        this.zmL[s] = tmpL * a1 + yL;
        yL = tmpL;

        const tmpR = yR * -a1 + this.zmR[s];
        this.zmR[s] = tmpR * a1 + yR;
        yR = tmpR;
      }
      this.lastOutL = clamp(yL, -1.5, 1.5);
      this.lastOutR = clamp(yR, -1.5, 1.5);

      left[i] = clamp(left[i] * dry + yL * wet, -1, 1);
      right[i] = clamp(right[i] * dry + yR * wet, -1, 1);

      this.lfoPhase += lfoInc;
      if (this.lfoPhase > Math.PI * 2) this.lfoPhase -= Math.PI * 2;
    }
  }

  reset(): void {
    this.zmL.fill(0);
    this.zmR.fill(0);
    this.lastOutL = 0;
    this.lastOutR = 0;
    this.lfoPhase = 0;
  }
}

export class DistortionProcessor {
  private readonly toneFilter = new StereoBiquadFilter();

  configure(sampleRate: number, p: DistortionParams): void {
    this.toneFilter.configureLowPass(sampleRate, p.toneHz, 0.707);
  }

  processStereo(
    left: Float32Array,
    right: Float32Array,
    count: number,
    p: DistortionParams
  ): void {
    const drive = clamp(p.drive, 1, 20);
    const wet = clamp(p.mix, 0, 1);
    const dry = 1 - wet;
    const normFactor = 1 / Math.max(Math.tanh(drive), 0.1);

    for (let i = 0; i < count; i++) {
      const xL = left[i];
      const xR = right[i];
      const dL = xL * drive;
      const dR = xR * drive;

      let shapedL: number;
      let shapedR: number;
      if (p.mode === "HARD_CLIP") {
        shapedL = clamp(dL, -0.85, 0.85) / 0.85;
        shapedR = clamp(dR, -0.85, 0.85) / 0.85;
      } else if (p.mode === "SOFT_CLIP") {
        shapedL = Math.tanh(dL) * normFactor;
        shapedR = Math.tanh(dR) * normFactor;
      } else {
        shapedL = Math.tanh(dL + 0.18 * dL * dL) * normFactor;
        shapedR = Math.tanh(dR + 0.18 * dR * dR) * normFactor;
      }

      const tonedL = this.toneFilter.processLeft(shapedL);
      const tonedR = this.toneFilter.processRight(shapedR);

      left[i] = clamp(xL * dry + tonedL * wet, -1, 1);
      right[i] = clamp(xR * dry + tonedR * wet, -1, 1);
    }
  }

  reset(): void {
    this.toneFilter.reset();
  }
}

export class CompressorProcessor {
  private readonly sampleRate: number;
  private envelope = 0;
  public currentGainReductionDb = 0;

  constructor(sampleRate = 44100) {
    this.sampleRate = sampleRate;
  }

  processStereo(
    left: Float32Array,
    right: Float32Array,
    count: number,
    p: CompressorParams
  ): void {
    const threshold = clamp(p.thresholdDb, -48, 0);
    const ratio = clamp(p.ratio, 1, 20);
    const attackCoeff = Math.exp(-1 / (clamp(p.attackMs, 1, 200) * 0.001 * this.sampleRate));
    const releaseCoeff = Math.exp(-1 / (clamp(p.releaseMs, 20, 1000) * 0.001 * this.sampleRate));
    const makeup = Math.pow(10, p.makeupGainDb / 20);
    let maxGr = 0;

    for (let i = 0; i < count; i++) {
      const peak = Math.max(Math.abs(left[i]), Math.abs(right[i]));
      this.envelope =
        peak > this.envelope
          ? attackCoeff * this.envelope + (1 - attackCoeff) * peak
          : releaseCoeff * this.envelope + (1 - releaseCoeff) * peak;

      const envDb = 20 * Math.log10(Math.max(this.envelope, 1e-6));
      let gainReductionDb = 0;
      if (envDb > threshold) {
        const excess = envDb - threshold;
        gainReductionDb = excess - excess / ratio;
      }
      if (gainReductionDb > maxGr) maxGr = gainReductionDb;

      const gain = Math.pow(10, -gainReductionDb / 20) * makeup;
      left[i] = Math.tanh(left[i] * gain);
      right[i] = Math.tanh(right[i] * gain);
    }
    this.currentGainReductionDb = maxGr;
  }

  reset(): void {
    this.envelope = 0;
    this.currentGainReductionDb = 0;
  }
}

export class LimiterProcessor {
  private readonly sampleRate: number;
  private gain = 1;

  constructor(sampleRate = 44100) {
    this.sampleRate = sampleRate;
  }

  processStereo(
    left: Float32Array,
    right: Float32Array,
    count: number,
    p: LimiterParams
  ): void {
    const ceiling = Math.pow(10, clamp(p.ceilingDb, -12, 0) / 20);
    const releaseCoeff = Math.exp(-1 / (clamp(p.releaseMs, 10, 500) * 0.001 * this.sampleRate));

    for (let i = 0; i < count; i++) {
      const peak = Math.max(Math.abs(left[i]), Math.abs(right[i]));
      const targetGain = peak > ceiling ? ceiling / peak : 1;
      if (targetGain < this.gain) {
        this.gain = targetGain;
      } else {
        this.gain = releaseCoeff * this.gain + (1 - releaseCoeff) * targetGain;
      }
      left[i] = clamp(left[i] * this.gain, -ceiling, ceiling);
      right[i] = clamp(right[i] * this.gain, -ceiling, ceiling);
    }
  }

  reset(): void {
    this.gain = 1;
  }
}
