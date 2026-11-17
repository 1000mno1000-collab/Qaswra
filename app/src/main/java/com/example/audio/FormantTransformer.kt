package com.example.audio

import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

private fun timeStretchWsolaMono(
    input: FloatArray,
    sampleRate: Int,
    speedRatio: Double,
): FloatArray {
    val n = input.size
    if (n < 2048 || abs(speedRatio - 1.0) < 0.01) return input.copyOf()

    val winSize = (sampleRate * 0.048).roundToInt()
    val synHop = winSize / 2
    val anaHop = max(32, (synHop * speedRatio).roundToInt())
    val searchRadius = min(winSize / 3, (sampleRate * 0.012).roundToInt())

    val hann = FloatArray(winSize) { i ->
        (0.5 * (1.0 - cos((2.0 * PI * i) / (winSize - 1)))).toFloat()
    }

    val outLen = max(winSize, (n / speedRatio).roundToInt() + winSize)
    val out = FloatArray(outLen)
    val norm = FloatArray(outLen)

    var inPos = 0
    var outPos = 0

    val firstLen = min(winSize, n)
    for (i in 0 until firstLen) {
        out[i] += input[i] * hann[i]
        norm[i] += hann[i]
    }
    inPos += anaHop
    outPos += synHop

    while (inPos + winSize < n && outPos + winSize < outLen) {
        var bestShift = 0
        var bestCorr = Float.NEGATIVE_INFINITY
        val minShift = max(-searchRadius, -inPos)
        val maxShift = min(searchRadius, n - winSize - inPos)

        var s = minShift
        while (s <= maxShift) {
            var corr = 0f
            val candStart = inPos + s
            var i = 0
            while (i < synHop) {
                corr += out[outPos + i] * input[candStart + i]
                i += 4
            }
            if (corr > bestCorr) {
                bestCorr = corr
                bestShift = s
            }
            s += 2
        }

        val srcStart = inPos + bestShift
        for (i in 0 until winSize) {
            val w = hann[i]
            out[outPos + i] += input[srcStart + i] * w
            norm[outPos + i] += w
        }

        inPos = srcStart + anaHop
        outPos += synHop
    }

    for (i in 0 until outLen) {
        if (norm[i] > 1e-4f) out[i] /= norm[i]
    }

    val targetLen = max(1, (n / speedRatio).roundToInt())
    return out.copyOf(min(targetLen, out.size))
}

private fun resampleChannel(input: FloatArray, targetLen: Int): FloatArray {
    if (input.size == targetLen || input.size < 2) {
        val out = FloatArray(targetLen)
        System.arraycopy(input, 0, out, 0, min(input.size, targetLen))
        return out
    }
    val out = FloatArray(targetLen)
    val scale = (input.size - 1).toDouble() / max(1, targetLen - 1)
    for (i in 0 until targetLen) {
        val pos = i * scale
        val i0 = floor(pos).toInt()
        val i1 = min(i0 + 1, input.size - 1)
        val frac = (pos - i0).toFloat()
        out[i] = input[i0] + (input[i1] - input[i0]) * frac
    }
    return out
}

private fun shiftFormantsStft(
    input: FloatArray,
    formantShift: Double,
): FloatArray {
    val n = input.size
    if (n < 1024 || abs(formantShift - 1.0) < 0.015) return input.copyOf()

    val fftN = 1024
    val hop = 256
    val half = fftN / 2

    val sqrtHann = FloatArray(fftN) { i ->
        val h = 0.5 * (1.0 - cos((2.0 * PI * i) / (fftN - 1)))
        sqrt(max(h, 0.0)).toFloat()
    }

    val out = FloatArray(n + fftN)
    val norm = FloatArray(n + fftN)

    val real = DoubleArray(fftN)
    val imag = DoubleArray(fftN)
    val logSpecReal = DoubleArray(fftN)
    val logSpecImag = DoubleArray(fftN)

    val envOrig = DoubleArray(half + 1)
    val envShifted = DoubleArray(half + 1)

    val cepstrumOrder = 34

    var start = 0
    while (start < n) {
        real.fill(0.0)
        imag.fill(0.0)
        val copyLen = min(fftN, n - start)
        for (i in 0 until copyLen) {
            real[i] = (input[start + i] * sqrtHann[i]).toDouble()
        }

        radix2Fft(real, imag, fftN, false)

        for (k in 0..half) {
            val m = hypot(real[k], imag[k])
            val logM = ln(m + 1e-6)
            logSpecReal[k] = logM
            logSpecImag[k] = 0.0
            if (k in 1 until half) {
                logSpecReal[fftN - k] = logM
                logSpecImag[fftN - k] = 0.0
            }
        }

        radix2Fft(logSpecReal, logSpecImag, fftN, true)

        for (q in cepstrumOrder..(fftN - cepstrumOrder)) {
            logSpecReal[q] = 0.0
        }
        logSpecImag.fill(0.0)

        radix2Fft(logSpecReal, logSpecImag, fftN, false)

        for (k in 0..half) {
            envOrig[k] = exp(clamp(logSpecReal[k], -12.0, 12.0))
        }

        for (k in 0..half) {
            val srcBin = k / formantShift
            envShifted[k] = when {
                srcBin <= 0.0 -> envOrig[0]
                srcBin >= half -> envOrig[half] * 0.65
                else -> {
                    val b0 = floor(srcBin).toInt()
                    val b1 = min(b0 + 1, half)
                    val f = srcBin - b0
                    envOrig[b0] + (envOrig[b1] - envOrig[b0]) * f
                }
            }

            val ratio = clamp(envShifted[k] / max(envOrig[k], 1e-5), 0.18, 4.5)
            real[k] *= ratio
            imag[k] *= ratio
            if (k in 1 until half) {
                real[fftN - k] *= ratio
                imag[fftN - k] *= ratio
            }
        }

        radix2Fft(real, imag, fftN, true)

        for (i in 0 until fftN) {
            val w = sqrtHann[i]
            out[start + i] += (real[i] * w).toFloat()
            norm[start + i] += w * w
        }
        start += hop
    }

    val finalOut = FloatArray(n)
    for (i in 0 until n) {
        finalOut[i] = if (norm[i] > 1e-5f) clamp(out[i] / norm[i], -1f, 1f) else 0f
    }
    return finalOut
}

suspend fun transformVoiceOffline(
    source: StereoAudioBuffer,
    pitchSemitones: Float,
    formantShift: Float,
    warmthDb: Float,
    presenceDb: Float,
    mix: Float,
    onProgress: ((Float) -> Unit)? = null,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val n = source.frameCount
    if (n == 0) return@withContext source
    val sr = source.sampleRate

    onProgress?.invoke(0.08f)
    yield()

    val monoIn = FloatArray(n) { i ->
        0.5f * (source.left[i] + source.right[i])
    }

    // Step 1: Pitch shift preserving tempo
    val pitchFactor = clamp(2.0.pow(clamp(pitchSemitones, -12f, 12f) / 12.0), 0.5, 2.0)
    val pitched = if (abs(pitchFactor - 1.0) > 0.01) {
        val stretched = timeStretchWsolaMono(monoIn, sr, 1.0 / pitchFactor)
        resampleChannel(stretched, n)
    } else {
        monoIn
    }

    onProgress?.invoke(0.45f)
    yield()

    // Step 2: Vocal tract formant shift
    val clampedFormant = clamp(formantShift, 0.65f, 1.45f).toDouble()
    val formanted = if (abs(clampedFormant - 1.0) > 0.015) {
        shiftFormantsStft(pitched, clampedFormant)
    } else {
        pitched
    }

    onProgress?.invoke(0.8f)
    yield()

    // Step 3: Warmth + Presence + Wet/Dry mix
    val warmthFilter = StereoBiquadFilter().apply {
        configureLowShelf(sr, 210.0, 0.75, clamp(warmthDb, -6f, 9f).toDouble())
    }
    val presenceFilter = StereoBiquadFilter().apply {
        configurePeaking(sr, 3300.0, 1.1, clamp(presenceDb, -6f, 9f).toDouble())
    }

    val wetMix = clamp(mix, 0f, 1f)
    val dryMix = 1f - wetMix
    val outL = FloatArray(n)
    val outR = FloatArray(n)

    for (i in 0 until n) {
        var processed = formanted[i]
        if (abs(warmthDb) > 0.1f) processed = warmthFilter.processLeft(processed)
        if (abs(presenceDb) > 0.1f) processed = presenceFilter.processLeft(processed)
        processed = tanh(processed.toDouble()).toFloat()

        outL[i] = clamp(source.left[i] * dryMix + processed * wetMix, -1f, 1f)
        outR[i] = clamp(source.right[i] * dryMix + processed * wetMix, -1f, 1f)
    }

    onProgress?.invoke(1f)
    StereoAudioBuffer(outL, outR, sr)
}
