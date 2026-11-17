import { EchoReverbParams } from "../types/studio";
import { StereoBiquadFilter, clamp } from "./biquad";

class DampedCombFilter {
  private buffer: Float32Array;
  private idx = 0;
  private filterStore = 0;

  constructor(size: number) {
    this.buffer = new Float32Array(Math.max(size, 64));
  }

  process(input: number, feedback: number, damp: number): number {
    const out = this.buffer[this.idx];
    this.filterStore = out * (1 - damp) + this.filterStore * damp;
    let next = input + this.filterStore * feedback;
    if (next > 4) next = 4;
    else if (next < -4) next = -4;
    this.buffer[this.idx] = next;
    this.idx++;
    if (this.idx >= this.buffer.length) this.idx = 0;
    return out;
  }

  reset(): void {
    this.buffer.fill(0);
    this.idx = 0;
    this.filterStore = 0;
  }
}

class DiffuserAllpass {
  private buffer: Float32Array;
  private idx = 0;

  constructor(size: number) {
    this.buffer = new Float32Array(Math.max(size, 16));
  }

  process(input: number, g: number): number {
    const bufOut = this.buffer[this.idx];
    const out = -input * g + bufOut;
    let store = input + bufOut * g;
    if (store > 4) store = 4;
    else if (store < -4) store = -4;
    this.buffer[this.idx] = store;
    this.idx++;
    if (this.idx >= this.buffer.length) this.idx = 0;
    return out;
  }

  reset(): void {
    this.buffer.fill(0);
    this.idx = 0;
  }
}

export class EchoReverbProcessor {
  private readonly sampleRate: number;
  private readonly preDelayBufL: Float32Array;
  private readonly preDelayBufR: Float32Array;
  private preDelayIdx = 0;

  private readonly echoBufL: Float32Array;
  private readonly echoBufR: Float32Array;
  private echoIdx = 0;
  private echoLpL = 0;
  private echoLpR = 0;
  private echoLfoPhase = 0;

  private readonly erLineL: Float32Array;
  private readonly erLineR: Float32Array;
  private erIdx = 0;
  private readonly erTapsL: number[];
  private readonly erTapsR: number[];
  private readonly erGains = [0.68, 0.54, 0.44, 0.35, 0.27, 0.2];

  private readonly fdnLines: Float32Array[];
  private readonly fdnIdx = new Int32Array(4);
  private readonly fdnLp = new Float32Array(4);

  private readonly combsL: DampedCombFilter[];
  private readonly combsR: DampedCombFilter[];
  private readonly allpassL: DiffuserAllpass[];
  private readonly allpassR: DiffuserAllpass[];

  private readonly tankPreHpf = new StereoBiquadFilter();
  private readonly wetToneLpf = new StereoBiquadFilter();

  private smoothEchoStrength = 0.4;
  private smoothEchoDepth = 0.45;
  private smoothDelaySamples: number;
  private smoothFeedback = 0.35;
  private smoothWetMix = 0.38;
  private smoothStereoWidth = 0.65;

  private params: EchoReverbParams | null = null;
  private lastSampleRateUsed = -1;

  constructor(sampleRate = 44100) {
    this.sampleRate = sampleRate;
    const srScale = sampleRate / 44100;
    this.smoothDelaySamples = 0.28 * sampleRate;

    const maxPreDelaySamples = Math.floor(sampleRate * 0.25) + 4;
    this.preDelayBufL = new Float32Array(maxPreDelaySamples);
    this.preDelayBufR = new Float32Array(maxPreDelaySamples);

    const maxEchoSamples = Math.floor(sampleRate * 2.2) + 8;
    this.echoBufL = new Float32Array(maxEchoSamples);
    this.echoBufR = new Float32Array(maxEchoSamples);

    const erMaxSamples = Math.floor(sampleRate * 0.12) + 8;
    this.erLineL = new Float32Array(erMaxSamples);
    this.erLineR = new Float32Array(erMaxSamples);
    this.erTapsL = [311, 563, 887, 1297, 1811, 2399].map((s) =>
      Math.max(16, Math.round(s * srScale))
    );
    this.erTapsR = [359, 617, 941, 1381, 1907, 2521].map((s) =>
      Math.max(16, Math.round(s * srScale))
    );

    const fdnSizes = [1493, 1901, 2383, 2897];
    this.fdnLines = fdnSizes.map((sz) => new Float32Array(Math.max(64, Math.round(sz * srScale))));

    const combLengths = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617];
    const allpassLengths = [556, 441, 341, 225];
    const stereoSpread = Math.round(23 * srScale);

    this.combsL = combLengths.map((len) => new DampedCombFilter(Math.round(len * srScale)));
    this.combsR = combLengths.map(
      (len) => new DampedCombFilter(Math.round(len * srScale) + stereoSpread)
    );
    this.allpassL = allpassLengths.map((len) => new DiffuserAllpass(Math.round(len * srScale)));
    this.allpassR = allpassLengths.map(
      (len) => new DiffuserAllpass(Math.round(len * srScale) + stereoSpread)
    );

    this.tankPreHpf.configureHighPass(sampleRate, 110, 0.707);
    this.wetToneLpf.configureLowPass(sampleRate, 11000, 0.707);
  }

  configure(params: EchoReverbParams): void {
    const prevDamping = this.params?.damping;
    this.params = params;
    if (prevDamping !== params.damping || this.lastSampleRateUsed !== this.sampleRate) {
      this.lastSampleRateUsed = this.sampleRate;
      const wetCutoffHz = 14000 - clamp(params.damping, 0, 1) * 9500;
      this.wetToneLpf.configureLowPass(this.sampleRate, wetCutoffHz, 0.707);
    }
  }

  reset(): void {
    this.preDelayBufL.fill(0);
    this.preDelayBufR.fill(0);
    this.preDelayIdx = 0;
    this.echoBufL.fill(0);
    this.echoBufR.fill(0);
    this.echoIdx = 0;
    this.echoLpL = 0;
    this.echoLpR = 0;
    this.echoLfoPhase = 0;
    this.erLineL.fill(0);
    this.erLineR.fill(0);
    this.erIdx = 0;
    for (const line of this.fdnLines) line.fill(0);
    this.fdnIdx.fill(0);
    this.fdnLp.fill(0);
    for (const c of this.combsL) c.reset();
    for (const c of this.combsR) c.reset();
    for (const a of this.allpassL) a.reset();
    for (const a of this.allpassR) a.reset();
    this.tankPreHpf.reset();
    this.wetToneLpf.reset();
  }

  private readFrac(buf: Float32Array, writeIdx: number, delaySamples: number): number {
    const len = buf.length;
    let pos = writeIdx - delaySamples;
    while (pos < 0) pos += len;
    while (pos >= len) pos -= len;
    const i0 = Math.floor(pos) % len;
    const i1 = (i0 + 1) % len;
    const frac = pos - Math.floor(pos);
    return buf[i0] + (buf[i1] - buf[i0]) * frac;
  }

  processStereo(left: Float32Array, right: Float32Array, count: number): void {
    const p = this.params;
    if (!p) return;

    const targetEchoStrength = clamp(p.echoStrength, 0, 1);
    const targetEchoDepth = clamp(p.echoDepth, 0, 1);
    const targetDelaySamples = clamp(
      (p.delayTimeMs / 1000) * this.sampleRate,
      this.sampleRate * 0.02,
      this.echoBufL.length - 16
    );
    const targetFeedback = clamp(p.feedback, 0, 0.92);
    const targetWet = clamp(p.wetDryMix, 0, 1);
    const targetWidth = clamp(p.stereoWidth, 0, 1);

    const preDelaySamples = Math.round(
      clamp((p.preDelayMs / 1000) * this.sampleRate, 0, this.preDelayBufL.length - 2)
    );
    const size = clamp(p.reverbSize, 0, 1);
    const decay = clamp(p.reverbDecay, 0, 1);
    const combFeedback = clamp(0.68 + decay * 0.2 + size * 0.08, 0.5, 0.96);
    const fdnFeedback = clamp(0.45 + decay * 0.34 + size * 0.11, 0.35, 0.9);
    const damp = clamp(p.damping * 0.62, 0.04, 0.72);
    const diffusion = clamp(0.35 + p.diffusion * 0.45, 0.2, 0.85);
    const earlyGain = clamp(p.earlyReflections, 0, 1);
    const lateGain = clamp(p.lateReflections, 0, 1);
    const lfoInc = (2 * Math.PI * 0.55) / this.sampleRate;

    const smoothCoeff = 0.0025;
    const erLen = this.erLineL.length;
    const preLen = this.preDelayBufL.length;
    const echoLen = this.echoBufL.length;

    for (let i = 0; i < count; i++) {
      this.smoothEchoStrength += (targetEchoStrength - this.smoothEchoStrength) * smoothCoeff;
      this.smoothEchoDepth += (targetEchoDepth - this.smoothEchoDepth) * smoothCoeff;
      this.smoothDelaySamples += (targetDelaySamples - this.smoothDelaySamples) * smoothCoeff;
      this.smoothFeedback += (targetFeedback - this.smoothFeedback) * smoothCoeff;
      this.smoothWetMix += (targetWet - this.smoothWetMix) * smoothCoeff;
      this.smoothStereoWidth += (targetWidth - this.smoothStereoWidth) * smoothCoeff;

      const dryL = left[i];
      const dryR = right[i];

      this.preDelayBufL[this.preDelayIdx] = dryL;
      this.preDelayBufR[this.preDelayIdx] = dryR;
      const preReadIdx = (this.preDelayIdx - preDelaySamples + preLen) % preLen;
      const preL = this.preDelayBufL[preReadIdx];
      const preR = this.preDelayBufR[preReadIdx];
      this.preDelayIdx = (this.preDelayIdx + 1) % preLen;

      // Clean tank input above 110 Hz to keep speech crisp
      const tankInL = this.tankPreHpf.processLeft(preL);
      const tankInR = this.tankPreHpf.processRight(preR);

      // 1. Multi-tap Modulated Ping-Pong Echo
      this.echoLfoPhase += lfoInc;
      if (this.echoLfoPhase > Math.PI * 2) this.echoLfoPhase -= Math.PI * 2;
      const modOffset = Math.sin(this.echoLfoPhase) * (1 + this.smoothEchoDepth * 4);

      const d1 = Math.max(8, this.smoothDelaySamples + modOffset);
      const d2 = Math.max(8, this.smoothDelaySamples * 0.6667 - modOffset * 0.7);
      const d3 = Math.max(8, this.smoothDelaySamples * 1.3333 + modOffset * 0.5);

      const tapL1 = this.readFrac(this.echoBufL, this.echoIdx, d1);
      const tapR1 = this.readFrac(this.echoBufR, this.echoIdx, d1);
      const tapL2 = this.readFrac(this.echoBufR, this.echoIdx, d2);
      const tapR2 = this.readFrac(this.echoBufL, this.echoIdx, d2);
      const tapL3 = this.readFrac(this.echoBufL, this.echoIdx, d3);
      const tapR3 = this.readFrac(this.echoBufR, this.echoIdx, d3);

      const echoOutL =
        (tapL1 * 0.58 + tapL2 * 0.27 + tapL3 * 0.15) * this.smoothEchoStrength;
      const echoOutR =
        (tapR1 * 0.58 + tapR2 * 0.27 + tapR3 * 0.15) * this.smoothEchoStrength;

      const pingCross = 0.25 + this.smoothStereoWidth * 0.55;
      const fbCoeff = this.smoothFeedback * (0.55 + this.smoothEchoDepth * 0.4);
      const echoColor = 0.35 + (1 - damp) * 0.45;
      const rawFbL =
        tankInL +
        (tapL1 * (1 - pingCross) + tapR1 * pingCross) * fbCoeff * this.smoothEchoDepth;
      const rawFbR =
        tankInR +
        (tapR1 * (1 - pingCross) + tapL1 * pingCross) * fbCoeff * this.smoothEchoDepth;

      this.echoLpL += (rawFbL - this.echoLpL) * echoColor;
      this.echoLpR += (rawFbR - this.echoLpR) * echoColor;
      this.echoBufL[this.echoIdx] = Math.tanh(this.echoLpL);
      this.echoBufR[this.echoIdx] = Math.tanh(this.echoLpR);
      this.echoIdx = (this.echoIdx + 1) % echoLen;

      // 2. 6-Tap Early Reflections
      this.erLineL[this.erIdx] = tankInL + echoOutL * 0.2;
      this.erLineR[this.erIdx] = tankInR + echoOutR * 0.2;
      let erSumL = 0;
      let erSumR = 0;
      const roomStretch = 0.45 + size * 0.85;
      for (let t = 0; t < 6; t++) {
        const tapOffsetL = Math.round(this.erTapsL[t] * roomStretch);
        const tapOffsetR = Math.round(this.erTapsR[t] * roomStretch);
        const idxL = (this.erIdx - tapOffsetL + erLen) % erLen;
        const idxR = (this.erIdx - tapOffsetR + erLen) % erLen;
        erSumL += this.erLineL[idxL] * this.erGains[t];
        erSumR += this.erLineR[idxR] * this.erGains[t];
      }
      this.erIdx = (this.erIdx + 1) % erLen;
      const earlyL = erSumL * 0.42 * earlyGain;
      const earlyR = erSumR * 0.42 * earlyGain;

      // 3. 4-Channel Hadamard FDN Tail
      const fdnIn = (tankInL + tankInR) * 0.5 + (echoOutL + echoOutR) * 0.24 + (earlyL + earlyR) * 0.25;
      const f0 = this.fdnLines[0][this.fdnIdx[0]];
      const f1 = this.fdnLines[1][this.fdnIdx[1]];
      const f2 = this.fdnLines[2][this.fdnIdx[2]];
      const f3 = this.fdnLines[3][this.fdnIdx[3]];

      const h0 = 0.5 * (f0 + f1 + f2 + f3);
      const h1 = 0.5 * (f0 - f1 + f2 - f3);
      const h2 = 0.5 * (f0 + f1 - f2 - f3);
      const h3 = 0.5 * (f0 - f1 - f2 + f3);
      const hs = [h0, h1, h2, h3];

      const fdnDampCoeff = 1 - damp * 0.65;
      for (let ch = 0; ch < 4; ch++) {
        const dampedVal = this.fdnLp[ch] + (hs[ch] - this.fdnLp[ch]) * fdnDampCoeff;
        this.fdnLp[ch] = dampedVal;
        const nextFdn = fdnIn * 0.35 + dampedVal * fdnFeedback;
        this.fdnLines[ch][this.fdnIdx[ch]] = nextFdn > 3 ? 3 : nextFdn < -3 ? -3 : nextFdn;
        this.fdnIdx[ch] = (this.fdnIdx[ch] + 1) % this.fdnLines[ch].length;
      }

      // 4. 8 Parallel Schroeder Combs + 4 Allpass Diffusers
      const monoInput = fdnIn * 0.3 + (h0 + h1 + h2 + h3) * 0.22;
      let combSumL = 0;
      let combSumR = 0;
      for (let c = 0; c < 8; c++) {
        combSumL += this.combsL[c].process(monoInput, combFeedback, damp);
        combSumR += this.combsR[c].process(monoInput, combFeedback, damp);
      }
      let diffL = combSumL * 0.14;
      let diffR = combSumR * 0.14;
      for (let a = 0; a < 4; a++) {
        diffL = this.allpassL[a].process(diffL, diffusion);
        diffR = this.allpassR[a].process(diffR, diffusion);
      }

      const reverbTailL = ((h0 + h2) * 0.22 + diffL * 0.85) * lateGain * (0.4 + this.smoothEchoDepth * 0.6);
      const reverbTailR = ((h1 + h3) * 0.22 + diffR * 0.85) * lateGain * (0.4 + this.smoothEchoDepth * 0.6);

      const rawWetL = echoOutL * 0.85 + earlyL + reverbTailL;
      const rawWetR = echoOutR * 0.85 + earlyR + reverbTailR;
      const wetL = this.wetToneLpf.processLeft(rawWetL);
      const wetR = this.wetToneLpf.processRight(rawWetR);

      const mid = (wetL + wetR) * 0.5;
      const side = (wetL - wetR) * 0.5 * (0.3 + this.smoothStereoWidth * 1.35);
      const wideWetL = mid + side;
      const wideWetR = mid - side;

      const dryKeep = 1 - this.smoothWetMix * 0.45;
      const wetGain = this.smoothWetMix * 1.15;

      left[i] = Math.tanh(dryL * dryKeep + wideWetL * wetGain);
      right[i] = Math.tanh(dryR * dryKeep + wideWetR * wetGain);
    }
  }
}
