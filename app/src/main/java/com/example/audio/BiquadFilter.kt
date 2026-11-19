package com.example.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

fun clamp(v: Float, minVal: Float, maxVal: Float): Float =
    if (v < minVal) minVal else if (v > maxVal) maxVal else v

fun clamp(v: Double, minVal: Double, maxVal: Double): Double =
    if (v < minVal) minVal else if (v > maxVal) maxVal else v

fun clamp(v: Int, minVal: Int, maxVal: Int): Int =
    if (v < minVal) minVal else if (v > maxVal) maxVal else v

class StereoBiquadFilter {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var x1L = 0.0
    private var x2L = 0.0
    private var y1L = 0.0
    private var y2L = 0.0

    private var x1R = 0.0
    private var x2R = 0.0
    private var y1R = 0.0
    private var y2R = 0.0

    fun reset() {
        x1L = 0.0
        x2L = 0.0
        y1L = 0.0
        y2L = 0.0
        x1R = 0.0
        x2R = 0.0
        y1R = 0.0
        y2R = 0.0
    }

    private fun setNormalized(
        b0: Double,
        b1: Double,
        b2: Double,
        a0: Double,
        a1: Double,
        a2: Double,
    ) {
        val invA0 = if (a0 != 0.0) 1.0 / a0 else 1.0
        this.b0 = b0 * invA0
        this.b1 = b1 * invA0
        this.b2 = b2 * invA0
        this.a1 = a1 * invA0
        this.a2 = a2 * invA0
    }

    fun configurePeaking(
        sampleRate: Int,
        freqHz: Double,
        q: Double,
        gainDb: Double,
    ) {
        if (abs(gainDb) < 0.01) {
            b0 = 1.0
            b1 = 0.0
            b2 = 0.0
            a1 = 0.0
            a2 = 0.0
            return
        }
        val clampedFreq = min(max(freqHz, 20.0), sampleRate * 0.48)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = (2.0 * PI * clampedFreq) / sampleRate
        val sinW0 = sin(w0)
        val cosW0 = cos(w0)
        val alpha = sinW0 / (2.0 * max(q, 0.1))

        setNormalized(
            b0 = 1.0 + alpha * a,
            b1 = -2.0 * cosW0,
            b2 = 1.0 - alpha * a,
            a0 = 1.0 + alpha / a,
            a1 = -2.0 * cosW0,
            a2 = 1.0 - alpha / a,
        )
    }

    fun configureLowShelf(
        sampleRate: Int,
        freqHz: Double,
        slope: Double,
        gainDb: Double,
    ) {
        val clampedFreq = min(max(freqHz, 20.0), sampleRate * 0.45)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = (2.0 * PI * clampedFreq) / sampleRate
        val sinW0 = sin(w0)
        val cosW0 = cos(w0)
        val alpha = (sinW0 / 2.0) *
            sqrt(max((a + 1.0 / a) * (1.0 / max(slope, 0.1) - 1.0) + 2.0, 0.0001))
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        setNormalized(
            b0 = a * (a + 1.0 - (a - 1.0) * cosW0 + twoSqrtAAlpha),
            b1 = 2.0 * a * (a - 1.0 - (a + 1.0) * cosW0),
            b2 = a * (a + 1.0 - (a - 1.0) * cosW0 - twoSqrtAAlpha),
            a0 = a + 1.0 + (a - 1.0) * cosW0 + twoSqrtAAlpha,
            a1 = -2.0 * (a - 1.0 + (a + 1.0) * cosW0),
            a2 = a + 1.0 + (a - 1.0) * cosW0 - twoSqrtAAlpha,
        )
    }

    fun configureHighShelf(
        sampleRate: Int,
        freqHz: Double,
        slope: Double,
        gainDb: Double,
    ) {
        val clampedFreq = min(max(freqHz, 20.0), sampleRate * 0.48)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = (2.0 * PI * clampedFreq) / sampleRate
        val sinW0 = sin(w0)
        val cosW0 = cos(w0)
        val alpha = (sinW0 / 2.0) *
            sqrt(max((a + 1.0 / a) * (1.0 / max(slope, 0.1) - 1.0) + 2.0, 0.0001))
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        setNormalized(
            b0 = a * (a + 1.0 + (a - 1.0) * cosW0 + twoSqrtAAlpha),
            b1 = -2.0 * a * (a - 1.0 + (a + 1.0) * cosW0),
            b2 = a * (a + 1.0 + (a - 1.0) * cosW0 - twoSqrtAAlpha),
            a0 = a + 1.0 - (a - 1.0) * cosW0 + twoSqrtAAlpha,
            a1 = 2.0 * (a - 1.0 - (a + 1.0) * cosW0),
            a2 = a + 1.0 - (a - 1.0) * cosW0 - twoSqrtAAlpha,
        )
    }

    fun configureLowPass(sampleRate: Int, freqHz: Double, q: Double = 0.707) {
        val clampedFreq = min(max(freqHz, 40.0), sampleRate * 0.48)
        val w0 = (2.0 * PI * clampedFreq) / sampleRate
        val sinW0 = sin(w0)
        val cosW0 = cos(w0)
        val alpha = sinW0 / (2.0 * max(q, 0.1))

        setNormalized(
            b0 = (1.0 - cosW0) * 0.5,
            b1 = 1.0 - cosW0,
            b2 = (1.0 - cosW0) * 0.5,
            a0 = 1.0 + alpha,
            a1 = -2.0 * cosW0,
            a2 = 1.0 - alpha,
        )
    }

    fun configureHighPass(sampleRate: Int, freqHz: Double, q: Double = 0.707) {
        val clampedFreq = min(max(freqHz, 20.0), sampleRate * 0.45)
        val w0 = (2.0 * PI * clampedFreq) / sampleRate
        val sinW0 = sin(w0)
        val cosW0 = cos(w0)
        val alpha = sinW0 / (2.0 * max(q, 0.1))

        setNormalized(
            b0 = (1.0 + cosW0) * 0.5,
            b1 = -(1.0 + cosW0),
            b2 = (1.0 + cosW0) * 0.5,
            a0 = 1.0 + alpha,
            a1 = -2.0 * cosW0,
            a2 = 1.0 - alpha,
        )
    }

    fun configureBandPass(sampleRate: Int, freqHz: Double, q: Double = 1.2) {
        val clampedFreq = min(max(freqHz, 40.0), sampleRate * 0.45)
        val w0 = (2.0 * PI * clampedFreq) / sampleRate
        val sinW0 = sin(w0)
        val cosW0 = cos(w0)
        val alpha = sinW0 / (2.0 * max(q, 0.1))

        setNormalized(alpha, 0.0, -alpha, 1.0 + alpha, -2.0 * cosW0, 1.0 - alpha)
    }

    fun processLeft(x: Float): Float {
        val xd = x.toDouble()
        var y = b0 * xd + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        if (!y.isFinite()) y = 0.0
        x2L = x1L
        x1L = xd
        y2L = y1L
        y1L = y
        return y.toFloat()
    }

    fun processRight(x: Float): Float {
        val xd = x.toDouble()
        var y = b0 * xd + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
        if (!y.isFinite()) y = 0.0
        x2R = x1R
        x1R = xd
        y2R = y1R
        y1R = y
        return y.toFloat()
    }

    fun processStereo(left: FloatArray, right: FloatArray, count: Int) {
        val lb0 = b0
        val lb1 = b1
        val lb2 = b2
        val la1 = a1
        val la2 = a2

        for (i in 0 until count) {
            val xl = left[i].toDouble()
            var yl = lb0 * xl + lb1 * x1L + lb2 * x2L - la1 * y1L - la2 * y2L
            if (!yl.isFinite()) yl = 0.0
            x2L = x1L
            x1L = xl
            y2L = y1L
            y1L = yl
            left[i] = yl.toFloat()

            val xr = right[i].toDouble()
            var yr = lb0 * xr + lb1 * x1R + lb2 * x2R - la1 * y1R - la2 * y2R
            if (!yr.isFinite()) yr = 0.0
            x2R = x1R
            x1R = xr
            y2R = y1R
            y1R = yr
            right[i] = yr.toFloat()
        }
    }

    fun magnitudeDbAt(sampleRate: Int, freqHz: Double): Double {
        if (sampleRate <= 0 || freqHz <= 0.0 || freqHz >= sampleRate * 0.5) return 0.0
        val w = (2.0 * PI * freqHz) / sampleRate
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)

        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = -(b1 * sinW + b2 * sin2W)
        val denRe = 1.0 + a1 * cosW + a2 * cos2W
        val denIm = -(a1 * sinW + a2 * sin2W)

        val numMagSq = numRe * numRe + numIm * numIm
        val denMagSq = max(denRe * denRe + denIm * denIm, 1e-12)
        val mag = sqrt(max(numMagSq / denMagSq, 1e-12))
        return clamp(20.0 * kotlin.math.log10(mag), -24.0, 24.0)
    }
}
