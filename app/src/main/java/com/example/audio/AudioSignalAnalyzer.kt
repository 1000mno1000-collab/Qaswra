package com.example.audio

import com.example.model.StereoAudioBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time DSP signal & acoustic analysis utilities.
 * Measures actual peak dBFS on the loaded buffer and evaluates filter transfer responses
 * across consonant/fricative speech bands (2500 Hz - 6000 Hz) to raise smart warnings
 * ONLY when mathematically warranted.
 */
object AudioSignalAnalyzer {

    /**
     * Computes actual peak amplitude of [buffer] in dBFS (-96f .. 0f).
     * Returns -96f when buffer is null, empty, or digital silence.
     */
    fun analyzeBufferPeakDbfs(buffer: StereoAudioBuffer?): Float {
        if (buffer == null || buffer.frameCount <= 0) return -96f
        val left = buffer.left
        val right = buffer.right
        val n = buffer.frameCount
        // Sub-sample stride on huge tracks while remaining exact on typical clips
        val stride = if (n > 2_000_000) 2 else 1
        var maxAbs = 0f
        var i = 0
        while (i < n) {
            val a = abs(left[i])
            val b = abs(right[i])
            val p = if (a > b) a else b
            if (p > maxAbs) maxAbs = p
            i += stride
        }
        if (maxAbs < 1e-5f) return -96f
        return clamp((20.0 * log10(maxAbs.toDouble())).toFloat(), -96f, 0f)
    }

    /**
     * Returns true ONLY if applying [addedGainDb] to audio peaking at [originalPeakDbfs]
     * pushes the signal peak past digital ceiling 0.0 dBFS.
     */
    fun willGainCauseClipping(
        originalPeakDbfs: Float,
        addedGainDb: Float,
    ): Boolean {
        if (addedGainDb <= 0.05f) return false
        return (originalPeakDbfs + addedGainDb) > 0.0f
    }

    /**
     * Evaluates whether current noise/EQ filter parameters aggressively cut speech clarity
     * in the consonant/fricative band (2500 Hz – 6000 Hz, e.g., Arabic letters الهاء هـ & الخاء خ),
     * weighted against actual spectral energy present in [buffer].
     */
    fun willFricativeClarityDrop(
        buffer: StereoAudioBuffer?,
        highPassHz: Float,
        lowPassHz: Float,
        spectralStrength: Float,
        reductionDb: Float,
        deEssAmount: Float,
        clarityBoostDb: Float,
        eq3600Db: Float = 0f,
    ): Boolean {
        val sr = (buffer?.sampleRate ?: 44100).coerceAtLeast(8000).toDouble()
        val testFreqs = doubleArrayOf(2600.0, 3200.0, 3900.0, 4700.0, 5600.0)

        // Compute spectral weighting from actual audio if loaded; otherwise uniform speech weights
        val bandWeights = computeFricativeBandWeights(buffer, testFreqs)
        val hasSignificantConsonantEnergy = bandWeights.sum() > 0.0

        var weightedDbChange = 0.0
        var totalWeight = 0.0
        for (k in testFreqs.indices) {
            val f = testFreqs[k]
            val w = if (hasSignificantConsonantEnergy) bandWeights[k] else 1.0
            if (w <= 0.0) continue

            // 1. High-pass filter 2nd-order magnitude drop
            val hpDropDb = biquadHighPassDb(f, clamp(highPassHz, 20f, 1500f).toDouble(), sr)
            // 2. Low-pass filter 2nd-order magnitude drop
            val lpDropDb = biquadLowPassDb(f, clamp(lowPassHz, 1200f, 20000f).toDouble(), sr)
            // 3. Clarity presence shelf (+3400 Hz)
            val clarityGainDb = biquadHighShelfApproxDb(f, 3400.0, clarityBoostDb.toDouble())
            // 4. Optional EQ 3600 Hz bell contribution
            val eqBellDb = if (abs(eq3600Db) > 0.05f) {
                val distOct = abs(log10(f / 3600.0) / log10(2.0))
                eq3600Db * max(0.0, 1.0 - distOct * 1.15)
            } else {
                0.0
            }
            // 5. Over-aggressive de-esser / high-strength spectral gate attenuation on soft fricatives
            val spectralPenaltyDb = if (spectralStrength > 0.78f && reductionDb > 22f) {
                -((spectralStrength - 0.78f) * 14.0 + (reductionDb - 22f) * 0.16)
            } else {
                0.0
            }
            val deEssPenaltyDb = if (f >= 4400.0 && deEssAmount > 0.55f) {
                -(deEssAmount - 0.55f) * 8.5
            } else {
                0.0
            }

            val totalAtFreqDb = hpDropDb + lpDropDb + clarityGainDb + eqBellDb + spectralPenaltyDb + deEssPenaltyDb
            weightedDbChange += totalAtFreqDb * w
            totalWeight += w
        }

        if (totalWeight <= 0.0) return false
        val avgNetDb = weightedDbChange / totalWeight
        // Warn when average net articulation attenuation drops >= 4.2 dB (~39% consonant pressure drop)
        return avgNetDb <= -4.2 || lowPassHz < 4500f || highPassHz > 380f
    }

    private fun computeFricativeBandWeights(
        buffer: StereoAudioBuffer?,
        freqs: DoubleArray,
    ): DoubleArray {
        val weights = DoubleArray(freqs.size) { 1.0 }
        if (buffer == null || buffer.frameCount < 1024) return weights

        // Analyze a 4096-sample window centered near active energy via Goertzel DFT bins
        val winLen = min(4096, buffer.frameCount)
        val strideStep = max(1, (buffer.frameCount - winLen) / 8)
        var bestStart = 0
        var bestRms = 0.0
        var pos = 0
        while (pos + winLen <= buffer.frameCount) {
            var sumSq = 0.0
            var j = 0
            while (j < winLen) {
                val s = 0.5 * (buffer.left[pos + j] + buffer.right[pos + j])
                sumSq += s * s
                j += 8
            }
            if (sumSq > bestRms) {
                bestRms = sumSq
                bestStart = pos
            }
            pos += strideStep
        }

        if (bestRms < 1e-6) return weights // quiet/silent clip: keep analytical weights active

        val sr = buffer.sampleRate.toDouble()
        for (idx in freqs.indices) {
            val omega = 2.0 * PI * freqs[idx] / sr
            val coeff = 2.0 * cos(omega)
            var q1 = 0.0
            var q2 = 0.0
            for (i in 0 until winLen) {
                val x = 0.5 * (buffer.left[bestStart + i] + buffer.right[bestStart + i])
                val q0 = coeff * q1 - q2 + x
                q2 = q1
                q1 = q0
            }
            val magSq = max(1e-9, q1 * q1 + q2 * q2 - q1 * q2 * coeff)
            weights[idx] = max(0.25, sqrt(magSq))
        }
        return weights
    }

    private fun biquadLowPassDb(freqHz: Double, cutoffHz: Double, sampleRate: Double): Double {
        if (cutoffHz >= sampleRate * 0.45 || cutoffHz >= 15000.0) return 0.0
        val ratio = freqHz / max(200.0, cutoffHz)
        // 2nd-order Butterworth response: -10 * log10(1 + (f/fc)^4)
        return (-10.0 * log10(1.0 + ratio.pow(4.0))).coerceAtLeast(-30.0)
    }

    private fun biquadHighPassDb(freqHz: Double, cutoffHz: Double, sampleRate: Double): Double {
        if (cutoffHz <= 25.0 || cutoffHz >= sampleRate * 0.45) return 0.0
        val ratio = cutoffHz / max(20.0, freqHz)
        return (-10.0 * log10(1.0 + ratio.pow(4.0))).coerceAtLeast(-30.0)
    }

    private fun biquadHighShelfApproxDb(freqHz: Double, shelfHz: Double, gainDb: Float.() -> Double): Double = 0.0

    private fun biquadHighShelfApproxDb(freqHz: Double, shelfHz: Double, gainDb: Double): Double {
        if (abs(gainDb) < 0.05) return 0.0
        val x = clamp((freqHz - shelfHz * 0.55) / (shelfHz * 0.7), 0.0, 1.0)
        val smooth = x * x * (3.0 - 2.0 * x)
        return gainDb * smooth
    }

    private fun clamp(v: Double, minV: Double, maxV: Double): Double = max(minV, min(maxV, v))
}
