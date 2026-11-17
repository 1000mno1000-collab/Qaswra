import { DspConfig, EffectType, StereoAudioBuffer } from "../types/studio";
import { StereoBiquadFilter, clamp } from "./biquad";
import { EchoReverbProcessor } from "./echoReverb";
import {
  ChorusProcessor,
  CompressorProcessor,
  DistortionProcessor,
  EqualizerProcessor,
  FlangerProcessor,
  LimiterProcessor,
  PhaserProcessor,
  PitchShiftProcessor,
} from "./effectsProcessors";
import { NoiseReductionProcessor } from "./voicePurifier";

export class DspPipeline {
  private readonly sampleRate: number;
  private config: DspConfig;

  private readonly noiseReduction: NoiseReductionProcessor;
  private readonly equalizer = new EqualizerProcessor();
  private readonly bassFilter = new StereoBiquadFilter();
  private readonly trebleFilter = new StereoBiquadFilter();
  private readonly pitchShift = new PitchShiftProcessor();
  private readonly chorus: ChorusProcessor;
  private readonly flanger: FlangerProcessor;
  private readonly phaser: PhaserProcessor;
  private readonly distortion = new DistortionProcessor();
  private readonly compressor: CompressorProcessor;
  private readonly echoReverb: EchoReverbProcessor;
  private readonly limiter: LimiterProcessor;

  private lastBassKey = "";
  private lastTrebleKey = "";
  private lastEqKey = "";
  private lastDistKey = "";

  constructor(sampleRate = 44100, initialConfig?: DspConfig) {
    this.sampleRate = sampleRate;
    this.noiseReduction = new NoiseReductionProcessor(sampleRate);
    this.chorus = new ChorusProcessor(sampleRate);
    this.flanger = new FlangerProcessor(sampleRate);
    this.phaser = new PhaserProcessor(sampleRate);
    this.compressor = new CompressorProcessor(sampleRate);
    this.echoReverb = new EchoReverbProcessor(sampleRate);
    this.limiter = new LimiterProcessor(sampleRate);
    this.config = initialConfig ?? ({} as DspConfig);
    if (initialConfig) this.updateConfig(initialConfig);
  }

  get currentGainReductionDb(): number {
    const compEnabled = this.config.chain?.some(
      (s) => s.type === EffectType.COMPRESSOR && s.enabled
    );
    return !this.config.bypassAll && compEnabled
      ? this.compressor.currentGainReductionDb
      : 0;
  }

  get currentNoiseSuppressionDb(): number {
    const nrEnabled = this.config.chain?.some(
      (s) => s.type === EffectType.NOISE_REDUCTION && s.enabled
    );
    return !this.config.bypassAll && nrEnabled
      ? this.noiseReduction.currentSuppressionDb
      : 0;
  }

  updateConfig(newConfig: DspConfig): void {
    this.config = newConfig;
    const eqKey = JSON.stringify(newConfig.equalizer);
    if (eqKey !== this.lastEqKey) {
      this.lastEqKey = eqKey;
      this.equalizer.configure(this.sampleRate, newConfig.equalizer);
    }

    const bassKey = `${newConfig.bassBoost.frequencyHz}|${newConfig.bassBoost.gainDb}`;
    if (bassKey !== this.lastBassKey) {
      this.lastBassKey = bassKey;
      this.bassFilter.configureLowShelf(
        this.sampleRate,
        newConfig.bassBoost.frequencyHz,
        0.9,
        newConfig.bassBoost.gainDb
      );
    }

    const trebleKey = `${newConfig.treble.frequencyHz}|${newConfig.treble.gainDb}`;
    if (trebleKey !== this.lastTrebleKey) {
      this.lastTrebleKey = trebleKey;
      this.trebleFilter.configureHighShelf(
        this.sampleRate,
        newConfig.treble.frequencyHz,
        0.9,
        newConfig.treble.gainDb
      );
    }

    const distKey = `${newConfig.distortion.toneHz}`;
    if (distKey !== this.lastDistKey) {
      this.lastDistKey = distKey;
      this.distortion.configure(this.sampleRate, newConfig.distortion);
    }

    this.echoReverb.configure(newConfig.echoReverb);
  }

  reset(): void {
    this.noiseReduction.reset();
    this.equalizer.reset();
    this.bassFilter.reset();
    this.trebleFilter.reset();
    this.pitchShift.reset();
    this.chorus.reset();
    this.flanger.reset();
    this.phaser.reset();
    this.distortion.reset();
    this.compressor.reset();
    this.echoReverb.reset();
    this.limiter.reset();
  }

  processStereoBlock(
    left: Float32Array,
    right: Float32Array,
    count: number
  ): void {
    const cfg = this.config;
    if (!cfg || cfg.bypassAll || count <= 0) return;

    for (const slot of cfg.chain) {
      if (!slot.enabled) continue;
      switch (slot.type) {
        case EffectType.NOISE_REDUCTION:
          this.noiseReduction.processStereo(left, right, count, cfg.noiseReduction);
          break;
        case EffectType.EQUALIZER:
          this.equalizer.processStereo(left, right, count);
          break;
        case EffectType.BASS_BOOST:
          this.bassFilter.processStereo(left, right, count);
          break;
        case EffectType.TREBLE:
          this.trebleFilter.processStereo(left, right, count);
          break;
        case EffectType.PITCH_SHIFT:
          this.pitchShift.processStereo(left, right, count, cfg.pitchShift);
          break;
        case EffectType.TIME_STRETCH:
          // Real-time tempo handled by playback rate / offline WSOLA
          break;
        case EffectType.CHORUS:
          this.chorus.processStereo(left, right, count, cfg.chorus);
          break;
        case EffectType.FLANGER:
          this.flanger.processStereo(left, right, count, cfg.flanger);
          break;
        case EffectType.PHASER:
          this.phaser.processStereo(left, right, count, cfg.phaser);
          break;
        case EffectType.DISTORTION:
          this.distortion.processStereo(left, right, count, cfg.distortion);
          break;
        case EffectType.COMPRESSOR:
          this.compressor.processStereo(left, right, count, cfg.compressor);
          break;
        case EffectType.ECHO_REVERB:
          this.echoReverb.processStereo(left, right, count);
          break;
        case EffectType.LIMITER:
          this.limiter.processStereo(left, right, count, cfg.limiter);
          break;
      }
    }

    const masterLinear = Math.pow(10, clamp(cfg.masterGainDb, -18, 18) / 20);
    const brickwall = 0.944; // -0.5 dBFS
    for (let i = 0; i < count; i++) {
      left[i] = clamp(Math.tanh(left[i] * masterLinear), -brickwall, brickwall);
      right[i] = clamp(Math.tanh(right[i] * masterLinear), -brickwall, brickwall);
    }
  }
}

function timeStretchWsolaStereo(
  source: StereoAudioBuffer,
  tempoRatio: number
): StereoAudioBuffer {
  const n = source.left.length;
  if (n < 2048 || Math.abs(tempoRatio - 1) < 0.02) return source;
  const clampedTempo = clamp(tempoRatio, 0.5, 2.0);
  const windowSize = 1024;
  const halfWin = windowSize / 2;
  const searchRadius = 256;
  const expectedOutFrames = Math.max(windowSize, Math.round(n / clampedTempo) + windowSize);

  const outL = new Float32Array(expectedOutFrames);
  const outR = new Float32Array(expectedOutFrames);
  const hann = new Float32Array(windowSize);
  for (let i = 0; i < windowSize; i++) {
    hann[i] = 0.5 * (1 - Math.cos((2 * Math.PI * i) / (windowSize - 1)));
  }

  let outPos = 0;
  let inPos = 0;
  for (let i = 0; i < Math.min(windowSize, n); i++) {
    outL[i] = source.left[i] * hann[i];
    outR[i] = source.right[i] * hann[i];
  }

  const synHop = halfWin;
  const anaHop = Math.round(synHop * clampedTempo);

  while (outPos + synHop + windowSize < expectedOutFrames) {
    const nominalIn = inPos + anaHop;
    if (nominalIn + windowSize >= n) break;

    let bestOffset = 0;
    let bestCorr = -Infinity;
    const tailStartOut = outPos + synHop;

    for (let offset = -searchRadius; offset <= searchRadius; offset += 2) {
      const candIn = nominalIn + offset;
      if (candIn < 0 || candIn + halfWin >= n) continue;
      let corr = 0;
      for (let i = 0; i < halfWin; i += 4) {
        corr += outL[tailStartOut + i] * source.left[candIn + i];
      }
      if (corr > bestCorr) {
        bestCorr = corr;
        bestOffset = offset;
      }
    }

    const bestIn = nominalIn + bestOffset;
    for (let i = 0; i < windowSize; i++) {
      const w = hann[i];
      outL[tailStartOut + i] += source.left[bestIn + i] * w;
      outR[tailStartOut + i] += source.right[bestIn + i] * w;
    }

    outPos += synHop;
    inPos = bestIn;
  }

  const finalLen = Math.min(outPos + windowSize, expectedOutFrames);
  return {
    left: outL.slice(0, finalLen),
    right: outR.slice(0, finalLen),
    sampleRate: source.sampleRate,
  };
}

export function renderOfflineDsp(
  source: StereoAudioBuffer,
  config: DspConfig
): StereoAudioBuffer {
  if (source.left.length === 0) return source;
  if (config.bypassAll) {
    return {
      left: new Float32Array(source.left),
      right: new Float32Array(source.right),
      sampleRate: source.sampleRate,
    };
  }

  const timeStretchSlot = config.chain.find((s) => s.type === EffectType.TIME_STRETCH);
  const stretched =
    timeStretchSlot?.enabled && Math.abs(config.timeStretch.tempoRatio - 1) > 0.02
      ? timeStretchWsolaStereo(source, config.timeStretch.tempoRatio)
      : source;

  const echoEnabled = config.chain.some(
    (s) => s.type === EffectType.ECHO_REVERB && s.enabled
  );
  const tailSeconds = echoEnabled
    ? clamp(0.6 + config.echoReverb.reverbDecay * 2.2, 0.4, 3.0)
    : 0.05;
  const tailFrames = Math.floor(stretched.sampleRate * tailSeconds);
  const totalFrames = stretched.left.length + tailFrames;

  const outL = new Float32Array(totalFrames);
  const outR = new Float32Array(totalFrames);
  outL.set(stretched.left);
  outR.set(stretched.right);

  const pipeline = new DspPipeline(stretched.sampleRate, config);
  const blockSize = 1024;
  const blockL = new Float32Array(blockSize);
  const blockR = new Float32Array(blockSize);

  for (let offset = 0; offset < totalFrames; offset += blockSize) {
    const count = Math.min(blockSize, totalFrames - offset);
    blockL.set(outL.subarray(offset, offset + count));
    blockR.set(outR.subarray(offset, offset + count));
    pipeline.processStereoBlock(blockL, blockR, count);
    outL.set(blockL.subarray(0, count), offset);
    outR.set(blockR.subarray(0, count), offset);
  }

  // Trim silent tail end after reverb fades below -68 dB
  const silenceThreshold = 0.0004;
  let lastAudible = stretched.left.length;
  for (let i = totalFrames - 1; i >= stretched.left.length; i--) {
    if (Math.abs(outL[i]) > silenceThreshold || Math.abs(outR[i]) > silenceThreshold) {
      lastAudible = Math.min(totalFrames, i + Math.floor(stretched.sampleRate * 0.04));
      break;
    }
  }

  return {
    left: outL.slice(0, lastAudible),
    right: outR.slice(0, lastAudible),
    sampleRate: stretched.sampleRate,
  };
}
