import { NoiseReductionParams, StereoAudioBuffer } from "../types/studio";
import { StereoBiquadFilter, clamp } from "./biquad";

/**
 * In-place iterative radix-2 Cooley-Tukey FFT.
 */
export function radix2Fft(
  real: Float64Array,
  imag: Float64Array,
  n: number,
  inverse: boolean
): void {
  let j = 0;
  for (let i = 1; i < n; i++) {
    let bit = n >>> 1;
    while ((j & bit) !== 0) {
      j ^= bit;
      bit >>>= 1;
    }
    j ^= bit;
    if (i < j) {
      const tr = real[i];
      real[i] = real[j];
      real[j] = tr;
      const ti = imag[i];
      imag[i] = imag[j];
      imag[j] = ti;
    }
  }

  const sign = inverse ? 1 : -1;
  for (let len = 2; len <= n; len <<= 1) {
    const ang = (sign * 2 * Math.PI) / len;
    const wLenCos = Math.cos(ang);
    const wLenSin = Math.sin(ang);
    const half = len >>> 1;
    for (let i = 0; i < n; i += len) {
      let wCos = 1;
      let wSin = 0;
      for (let k = 0; k < half; k++) {
        const uR = real[i + k];
        const uI = imag[i + k];
        const idx = i + k + half;
        const vR = real[idx] * wCos - imag[idx] * wSin;
        const vI = real[idx] * wSin + imag[idx] * wCos;
        real[i + k] = uR + vR;
        imag[i + k] = uI + vI;
        real[idx] = uR - vR;
        imag[idx] = uI - vI;
        const nextWCos = wCos * wLenCos - wSin * wLenSin;
        wSin = wCos * wLenSin + wSin * wLenCos;
        wCos = nextWCos;
      }
    }
  }

  if (inverse) {
    const invN = 1 / n;
    for (let i = 0; i < n; i++) {
      real[i] *= invN;
      imag[i] *= invN;
    }
  }
}

/**
 * 512-pt sqrt-Hann 50%-overlap STFT Wiener spectral denoiser with adaptive minima noise tracking.
 */
export class SpectralVoiceCleaner {
  private static readonly FFT_SIZE = 512;
  private static readonly HOP_SIZE = 256;
  private static readonly BINS = SpectralVoiceCleaner.FFT_SIZE / 2 + 1;

  private readonly sqrtHann = new Float32Array(SpectralVoiceCleaner.FFT_SIZE);
  private readonly inRingL = new Float32Array(SpectralVoiceCleaner.FFT_SIZE);
  private readonly inRingR = new Float32Array(SpectralVoiceCleaner.FFT_SIZE);
  private readonly olaL = new Float32Array(SpectralVoiceCleaner.FFT_SIZE);
  private readonly olaR = new Float32Array(SpectralVoiceCleaner.FFT_SIZE);
  private readonly outRingL = new Float32Array(SpectralVoiceCleaner.HOP_SIZE);
  private readonly outRingR = new Float32Array(SpectralVoiceCleaner.HOP_SIZE);
  private hopFill = 0;

  private readonly noisePsd = new Float32Array(SpectralVoiceCleaner.BINS).fill(1e-5);
  private readonly prevGain = new Float32Array(SpectralVoiceCleaner.BINS).fill(1);
  private framesSeen = 0;
  private lastSuppressionDb = 0;

  private readonly fftReal = new Float64Array(SpectralVoiceCleaner.FFT_SIZE);
  private readonly fftImag = new Float64Array(SpectralVoiceCleaner.FFT_SIZE);
  private readonly fftRealR = new Float64Array(SpectralVoiceCleaner.FFT_SIZE);
  private readonly fftImagR = new Float64Array(SpectralVoiceCleaner.FFT_SIZE);

  constructor() {
    for (let i = 0; i < SpectralVoiceCleaner.FFT_SIZE; i++) {
      const hann =
        0.5 * (1 - Math.cos((2 * Math.PI * i) / (SpectralVoiceCleaner.FFT_SIZE - 1)));
      this.sqrtHann[i] = Math.sqrt(Math.max(hann, 0));
    }
  }

  get currentSuppressionDb(): number {
    return this.lastSuppressionDb;
  }

  reset(): void {
    this.inRingL.fill(0);
    this.inRingR.fill(0);
    this.olaL.fill(0);
    this.olaR.fill(0);
    this.outRingL.fill(0);
    this.outRingR.fill(0);
    this.hopFill = 0;
    this.noisePsd.fill(1e-5);
    this.prevGain.fill(1);
    this.framesSeen = 0;
    this.lastSuppressionDb = 0;
  }

  processStereo(
    left: Float32Array,
    right: Float32Array,
    count: number,
    strength: number,
    floorGain: number
  ): void {
    const clampedStrength = clamp(strength, 0, 1);
    const N = SpectralVoiceCleaner.FFT_SIZE;
    const HOP = SpectralVoiceCleaner.HOP_SIZE;

    for (let i = 0; i < count; i++) {
      const outL = this.outRingL[this.hopFill];
      const outR = this.outRingR[this.hopFill];

      this.inRingL[HOP + this.hopFill] = left[i];
      this.inRingR[HOP + this.hopFill] = right[i];

      left[i] = outL;
      right[i] = outR;

      this.hopFill++;
      if (this.hopFill >= HOP) {
        this.processFrame(clampedStrength, floorGain);
        this.inRingL.copyWithin(0, HOP, N);
        this.inRingR.copyWithin(0, HOP, N);
        this.hopFill = 0;
      }
    }
  }

  private processFrame(strength: number, floorGain: number): void {
    const N = SpectralVoiceCleaner.FFT_SIZE;
    const HOP = SpectralVoiceCleaner.HOP_SIZE;
    const BINS = SpectralVoiceCleaner.BINS;

    for (let i = 0; i < N; i++) {
      const w = this.sqrtHann[i];
      this.fftReal[i] = this.inRingL[i] * w;
      this.fftImag[i] = 0;
      this.fftRealR[i] = this.inRingR[i] * w;
      this.fftImagR[i] = 0;
    }

    radix2Fft(this.fftReal, this.fftImag, N, false);
    radix2Fft(this.fftRealR, this.fftImagR, N, false);

    this.framesSeen++;
    const spectralFloor = Math.max(floorGain, 0.03);
    const overSub = 1.0 + strength * 2.2;
    let avgGainSum = 0;
    let totalFrameEnergy = 0;

    for (let k = 0; k < BINS; k++) {
      const magSqL =
        this.fftReal[k] * this.fftReal[k] + this.fftImag[k] * this.fftImag[k];
      const magSqR =
        this.fftRealR[k] * this.fftRealR[k] + this.fftImagR[k] * this.fftImagR[k];
      const power = 0.5 * (magSqL + magSqR);
      totalFrameEnergy += power;
    }

    const avgPower = totalFrameEnergy / BINS;
    const isLikelyPause = this.framesSeen <= 8 || avgPower < 0.08;

    for (let k = 0; k < BINS; k++) {
      const magSqL =
        this.fftReal[k] * this.fftReal[k] + this.fftImag[k] * this.fftImag[k];
      const magSqR =
        this.fftRealR[k] * this.fftRealR[k] + this.fftImagR[k] * this.fftImagR[k];
      const power = 0.5 * (magSqL + magSqR);

      if (this.framesSeen <= 4) {
        this.noisePsd[k] = this.noisePsd[k] * 0.5 + power * 0.5;
      } else if (power < this.noisePsd[k]) {
        this.noisePsd[k] = this.noisePsd[k] * 0.8 + power * 0.2;
      } else if (isLikelyPause && power < this.noisePsd[k] * 3.5) {
        this.noisePsd[k] = this.noisePsd[k] * 0.965 + power * 0.035;
      } else {
        this.noisePsd[k] = this.noisePsd[k] * 0.9992 + power * 0.0008;
      }

      const noiseEst = Math.max(this.noisePsd[k] * overSub, 1e-9);
      const snrPost = power / noiseEst;
      const wiener = clamp((snrPost - 1.0) / (snrPost + 0.35), spectralFloor, 1.0);

      const alpha = wiener > this.prevGain[k] ? 0.55 : 0.78;
      const smoothG = alpha * this.prevGain[k] + (1 - alpha) * wiener;
      this.prevGain[k] = smoothG;

      const finalG = 1 - strength + strength * smoothG;
      avgGainSum += finalG;

      this.fftReal[k] *= finalG;
      this.fftImag[k] *= finalG;
      this.fftRealR[k] *= finalG;
      this.fftImagR[k] *= finalG;

      if (k > 0 && k < N / 2) {
        const conjIdx = N - k;
        this.fftReal[conjIdx] *= finalG;
        this.fftImag[conjIdx] *= finalG;
        this.fftRealR[conjIdx] *= finalG;
        this.fftImagR[conjIdx] *= finalG;
      }
    }

    const avgGain = clamp(avgGainSum / BINS, 0.02, 1);
    const db = -20 * Math.log10(avgGain);
    this.lastSuppressionDb = clamp(db, 0, 32);

    radix2Fft(this.fftReal, this.fftImag, N, true);
    radix2Fft(this.fftRealR, this.fftImagR, N, true);

    for (let i = 0; i < N; i++) {
      const w = this.sqrtHann[i];
      this.olaL[i] += this.fftReal[i] * w;
      this.olaR[i] += this.fftRealR[i] * w;
    }

    for (let i = 0; i < HOP; i++) {
      this.outRingL[i] = clamp(this.olaL[i], -1, 1);
      this.outRingR[i] = clamp(this.olaR[i], -1, 1);
    }

    this.olaL.copyWithin(0, HOP, N);
    this.olaR.copyWithin(0, HOP, N);
    this.olaL.fill(0, HOP, N);
    this.olaR.fill(0, HOP, N);
  }
}

export class NoiseReductionProcessor {
  private readonly sampleRate: number;
  private readonly hpf = new StereoBiquadFilter();
  private readonly humNotch50 = new StereoBiquadFilter();
  private readonly sibilanceBand = new StereoBiquadFilter();
  private readonly clarityShelf = new StereoBiquadFilter();
  private readonly spectralCleaner = new SpectralVoiceCleaner();

  private gateGain = 1;
  private env = 0;
  private deEssEnv = 0;
  private lastConfigKey = "";

  public currentSuppressionDb = 0;

  constructor(sampleRate = 44100) {
    this.sampleRate = sampleRate;
  }

  configure(params: NoiseReductionParams): void {
    const key = `${this.sampleRate}|${params.highPassHz}|${params.clarityBoostDb}`;
    if (key === this.lastConfigKey) return;
    this.lastConfigKey = key;

    this.hpf.configureHighPass(this.sampleRate, clamp(params.highPassHz, 20, 300), 0.707);
    this.humNotch50.configurePeaking(this.sampleRate, 60, 4.5, -9);
    this.sibilanceBand.configureBandPass(this.sampleRate, 6400, 1.4);
    this.clarityShelf.configureHighShelf(
      this.sampleRate,
      3400,
      0.7,
      clamp(params.clarityBoostDb, -3, 12)
    );
  }

  processStereo(
    left: Float32Array,
    right: Float32Array,
    count: number,
    params: NoiseReductionParams
  ): void {
    this.configure(params);

    // 1. Subsonic rumble removal + hum tame
    this.hpf.processStereo(left, right, count);
    this.humNotch50.processStereo(left, right, count);

    // 2. STFT Wiener spectral denoiser
    const gateFloor = Math.pow(10, -clamp(params.reductionDb, 0, 36) / 20);
    if (params.spectralCleanerEnabled && params.spectralStrength > 0.02) {
      this.spectralCleaner.processStereo(
        left,
        right,
        count,
        params.spectralStrength,
        Math.max(gateFloor, 0.04)
      );
    }

    // 3. Envelope expander / soft downward gate
    const thresholdLin = Math.pow(10, clamp(params.thresholdDb, -80, -10) / 20);
    const attack = 0.02;
    const release = 0.0008;
    const deEssStrength = clamp(params.deEssAmount, 0, 1);
    let maxGateDb = 0;

    for (let i = 0; i < count; i++) {
      let l = left[i];
      let r = right[i];

      const peak = Math.max(Math.abs(l), Math.abs(r));
      this.env = peak > this.env ? this.env + (peak - this.env) * attack : this.env + (peak - this.env) * release;

      const targetGain =
        this.env < thresholdLin
          ? Math.max(gateFloor, (this.env / Math.max(thresholdLin, 1e-6)) * (1 - gateFloor) + gateFloor)
          : 1;

      const rate = targetGain > this.gateGain ? 0.015 : 0.002;
      this.gateGain += (targetGain - this.gateGain) * rate;
      l *= this.gateGain;
      r *= this.gateGain;

      const gDb = -20 * Math.log10(Math.max(this.gateGain, 0.01));
      if (gDb > maxGateDb) maxGateDb = gDb;

      // 4. Dynamic De-esser
      if (deEssStrength > 0.01) {
        const sibL = this.sibilanceBand.processLeft(l);
        const sibR = this.sibilanceBand.processRight(r);
        const sibEnergy = Math.max(Math.abs(sibL), Math.abs(sibR));
        this.deEssEnv =
          sibEnergy > this.deEssEnv
            ? this.deEssEnv * 0.7 + sibEnergy * 0.3
            : this.deEssEnv * 0.992 + sibEnergy * 0.008;
        if (this.deEssEnv > 0.045) {
          const excess = (this.deEssEnv - 0.045) * 8 * deEssStrength;
          const sibCut = clamp(excess, 0, 0.65);
          l -= sibL * sibCut;
          r -= sibR * sibCut;
        }
      }

      // 5. Vocal clarity presence shelf
      if (Math.abs(params.clarityBoostDb) > 0.05) {
        l = this.clarityShelf.processLeft(l);
        r = this.clarityShelf.processRight(r);
      }

      left[i] = Math.tanh(l);
      right[i] = Math.tanh(r);
    }

    const spectralDb = params.spectralCleanerEnabled ? this.spectralCleaner.currentSuppressionDb : 0;
    this.currentSuppressionDb = clamp(Math.max(spectralDb, maxGateDb * 0.65), 0, 36);
  }

  reset(): void {
    this.hpf.reset();
    this.humNotch50.reset();
    this.sibilanceBand.reset();
    this.clarityShelf.reset();
    this.spectralCleaner.reset();
    this.gateGain = 1;
    this.env = 0;
    this.deEssEnv = 0;
    this.currentSuppressionDb = 0;
  }
}

export async function purifyBufferOffline(
  source: StereoAudioBuffer,
  params: NoiseReductionParams,
  onProgress?: (p: number) => void
): Promise<StereoAudioBuffer> {
  const n = source.left.length;
  const outL = new Float32Array(source.left);
  const outR = new Float32Array(source.right);
  const proc = new NoiseReductionProcessor(source.sampleRate);
  proc.configure(params);

  const blockSize = 4096;
  const bL = new Float32Array(blockSize);
  const bR = new Float32Array(blockSize);

  for (let offset = 0; offset < n; offset += blockSize) {
    const count = Math.min(blockSize, n - offset);
    bL.set(outL.subarray(offset, offset + count), 0);
    bR.set(outR.subarray(offset, offset + count), 0);
    proc.processStereo(bL, bR, count, params);
    outL.set(bL.subarray(0, count), offset);
    outR.set(bR.subarray(0, count), offset);
    onProgress?.(Math.min(1, (offset + count) / Math.max(1, n)));
    if (offset % (blockSize * 8) === 0) {
      await new Promise((r) => setTimeout(r, 0));
    }
  }
  return { left: outL, right: outR, sampleRate: source.sampleRate };
}
