export class StereoBiquadFilter {
  private b0 = 1;
  private b1 = 0;
  private b2 = 0;
  private a1 = 0;
  private a2 = 0;

  private x1L = 0;
  private x2L = 0;
  private y1L = 0;
  private y2L = 0;

  private x1R = 0;
  private x2R = 0;
  private y1R = 0;
  private y2R = 0;

  reset(): void {
    this.x1L = 0;
    this.x2L = 0;
    this.y1L = 0;
    this.y2L = 0;
    this.x1R = 0;
    this.x2R = 0;
    this.y1R = 0;
    this.y2R = 0;
  }

  private setNormalized(
    b0: number,
    b1: number,
    b2: number,
    a0: number,
    a1: number,
    a2: number
  ): void {
    const invA0 = a0 !== 0 ? 1 / a0 : 1;
    this.b0 = b0 * invA0;
    this.b1 = b1 * invA0;
    this.b2 = b2 * invA0;
    this.a1 = a1 * invA0;
    this.a2 = a2 * invA0;
  }

  configurePeaking(
    sampleRate: number,
    freqHz: number,
    q: number,
    gainDb: number
  ): void {
    if (Math.abs(gainDb) < 0.01) {
      this.b0 = 1;
      this.b1 = 0;
      this.b2 = 0;
      this.a1 = 0;
      this.a2 = 0;
      return;
    }
    const clampedFreq = Math.min(Math.max(freqHz, 20), sampleRate * 0.48);
    const a = Math.pow(10, gainDb / 40);
    const w0 = (2 * Math.PI * clampedFreq) / sampleRate;
    const sinW0 = Math.sin(w0);
    const cosW0 = Math.cos(w0);
    const alpha = sinW0 / (2 * Math.max(q, 0.1));

    this.setNormalized(
      1 + alpha * a,
      -2 * cosW0,
      1 - alpha * a,
      1 + alpha / a,
      -2 * cosW0,
      1 - alpha / a
    );
  }

  configureLowShelf(
    sampleRate: number,
    freqHz: number,
    slope: number,
    gainDb: number
  ): void {
    const clampedFreq = Math.min(Math.max(freqHz, 20), sampleRate * 0.45);
    const a = Math.pow(10, gainDb / 40);
    const w0 = (2 * Math.PI * clampedFreq) / sampleRate;
    const sinW0 = Math.sin(w0);
    const cosW0 = Math.cos(w0);
    const alpha =
      (sinW0 / 2) *
      Math.sqrt(Math.max((a + 1 / a) * (1 / Math.max(slope, 0.1) - 1) + 2, 0.0001));
    const twoSqrtAAlpha = 2 * Math.sqrt(a) * alpha;

    this.setNormalized(
      a * (a + 1 - (a - 1) * cosW0 + twoSqrtAAlpha),
      2 * a * (a - 1 - (a + 1) * cosW0),
      a * (a + 1 - (a - 1) * cosW0 - twoSqrtAAlpha),
      a + 1 + (a - 1) * cosW0 + twoSqrtAAlpha,
      -2 * (a - 1 + (a + 1) * cosW0),
      a + 1 + (a - 1) * cosW0 - twoSqrtAAlpha
    );
  }

  configureHighShelf(
    sampleRate: number,
    freqHz: number,
    slope: number,
    gainDb: number
  ): void {
    const clampedFreq = Math.min(Math.max(freqHz, 20), sampleRate * 0.48);
    const a = Math.pow(10, gainDb / 40);
    const w0 = (2 * Math.PI * clampedFreq) / sampleRate;
    const sinW0 = Math.sin(w0);
    const cosW0 = Math.cos(w0);
    const alpha =
      (sinW0 / 2) *
      Math.sqrt(Math.max((a + 1 / a) * (1 / Math.max(slope, 0.1) - 1) + 2, 0.0001));
    const twoSqrtAAlpha = 2 * Math.sqrt(a) * alpha;

    this.setNormalized(
      a * (a + 1 + (a - 1) * cosW0 + twoSqrtAAlpha),
      -2 * a * (a - 1 + (a + 1) * cosW0),
      a * (a + 1 + (a - 1) * cosW0 - twoSqrtAAlpha),
      a + 1 - (a - 1) * cosW0 + twoSqrtAAlpha,
      2 * (a - 1 - (a + 1) * cosW0),
      a + 1 - (a - 1) * cosW0 - twoSqrtAAlpha
    );
  }

  configureLowPass(sampleRate: number, freqHz: number, q = 0.707): void {
    const clampedFreq = Math.min(Math.max(freqHz, 40), sampleRate * 0.48);
    const w0 = (2 * Math.PI * clampedFreq) / sampleRate;
    const sinW0 = Math.sin(w0);
    const cosW0 = Math.cos(w0);
    const alpha = sinW0 / (2 * Math.max(q, 0.1));

    this.setNormalized(
      (1 - cosW0) * 0.5,
      1 - cosW0,
      (1 - cosW0) * 0.5,
      1 + alpha,
      -2 * cosW0,
      1 - alpha
    );
  }

  configureHighPass(sampleRate: number, freqHz: number, q = 0.707): void {
    const clampedFreq = Math.min(Math.max(freqHz, 20), sampleRate * 0.45);
    const w0 = (2 * Math.PI * clampedFreq) / sampleRate;
    const sinW0 = Math.sin(w0);
    const cosW0 = Math.cos(w0);
    const alpha = sinW0 / (2 * Math.max(q, 0.1));

    this.setNormalized(
      (1 + cosW0) * 0.5,
      -(1 + cosW0),
      (1 + cosW0) * 0.5,
      1 + alpha,
      -2 * cosW0,
      1 - alpha
    );
  }

  configureBandPass(sampleRate: number, freqHz: number, q = 1.2): void {
    const clampedFreq = Math.min(Math.max(freqHz, 40), sampleRate * 0.45);
    const w0 = (2 * Math.PI * clampedFreq) / sampleRate;
    const sinW0 = Math.sin(w0);
    const cosW0 = Math.cos(w0);
    const alpha = sinW0 / (2 * Math.max(q, 0.1));

    this.setNormalized(alpha, 0, -alpha, 1 + alpha, -2 * cosW0, 1 - alpha);
  }

  processLeft(x: number): number {
    let y =
      this.b0 * x +
      this.b1 * this.x1L +
      this.b2 * this.x2L -
      this.a1 * this.y1L -
      this.a2 * this.y2L;
    if (!Number.isFinite(y)) y = 0;
    this.x2L = this.x1L;
    this.x1L = x;
    this.y2L = this.y1L;
    this.y1L = y;
    return y;
  }

  processRight(x: number): number {
    let y =
      this.b0 * x +
      this.b1 * this.x1R +
      this.b2 * this.x2R -
      this.a1 * this.y1R -
      this.a2 * this.y2R;
    if (!Number.isFinite(y)) y = 0;
    this.x2R = this.x1R;
    this.x1R = x;
    this.y2R = this.y1R;
    this.y1R = y;
    return y;
  }

  processStereo(left: Float32Array, right: Float32Array, count: number): void {
    const { b0, b1, b2, a1, a2 } = this;
    for (let i = 0; i < count; i++) {
      const xl = left[i];
      let yl = b0 * xl + b1 * this.x1L + b2 * this.x2L - a1 * this.y1L - a2 * this.y2L;
      if (!Number.isFinite(yl)) yl = 0;
      this.x2L = this.x1L;
      this.x1L = xl;
      this.y2L = this.y1L;
      this.y1L = yl;
      left[i] = yl;

      const xr = right[i];
      let yr = b0 * xr + b1 * this.x1R + b2 * this.x2R - a1 * this.y1R - a2 * this.y2R;
      if (!Number.isFinite(yr)) yr = 0;
      this.x2R = this.x1R;
      this.x1R = xr;
      this.y2R = this.y1R;
      this.y1R = yr;
      right[i] = yr;
    }
  }
}

export function clamp(v: number, min: number, max: number): number {
  return v < min ? min : v > max ? max : v;
}
