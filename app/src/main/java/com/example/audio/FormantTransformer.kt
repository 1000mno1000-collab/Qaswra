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

internal fun shiftFormantsStft(
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

private fun renderVoiceLayer(
    monoIn: FloatArray,
    sampleRate: Int,
    pitchSemitones: Float,
    formantShift: Float,
): FloatArray {
    val n = monoIn.size
    val pitchFactor = clamp(2.0.pow(clamp(pitchSemitones, -12f, 12f) / 12.0), 0.5, 2.0)
    val pitched = if (abs(pitchFactor - 1.0) > 0.01) {
        val stretched = timeStretchWsolaMono(monoIn, sampleRate, 1.0 / pitchFactor)
        resampleChannel(stretched, n)
    } else {
        monoIn.copyOf()
    }
    val clampedFormant = clamp(formantShift, 0.65f, 1.45f).toDouble()
    return if (abs(clampedFormant - 1.0) > 0.015) {
        shiftFormantsStft(pitched, clampedFormant)
    } else {
        pitched
    }
}

suspend fun transformVoiceOffline(
    source: StereoAudioBuffer,
    pitchSemitones: Float,
    formantShift: Float,
    warmthDb: Float,
    presenceDb: Float,
    mix: Float,
    bassBoostDb: Float = 0f,
    bassFreqHz: Float = 75f,
    vibratoHz: Float = 0f,
    vibratoDepth: Float = 0f,
    echoDelayMs: Float = 0f,
    echoDecay: Float = 0f,
    dualLayerPitchSemitones: Float = 0f,
    dualLayerFormantShift: Float = 1.0f,
    dualLayerMix: Float = 0f,
    studioMasteringPolish: Boolean = true,
    onProgress: ((Float) -> Unit)? = null,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val n = source.frameCount
    if (n == 0) return@withContext source
    val sr = source.sampleRate.coerceAtLeast(8000)

    onProgress?.invoke(0.08f)
    yield()

    val monoIn = FloatArray(n) { i ->
        0.5f * (source.left[i] + source.right[i])
    }

    // Step 1 & 2: Primary character pitch + vocal tract formant transformation
    val primaryLayer = renderVoiceLayer(monoIn, sr, pitchSemitones, formantShift)

    onProgress?.invoke(0.42f)
    yield()

    // Optional Dual-Voice Legion split layer ([low] + [mid] blended simultaneously)
    val hasDualLayer = dualLayerMix > 0.02f
    val secondaryLayer = if (hasDualLayer) {
        renderVoiceLayer(monoIn, sr, dualLayerPitchSemitones, dualLayerFormantShift)
    } else {
        null
    }

    onProgress?.invoke(0.68f)
    yield()

    // Step 3: Stereo shaping, bass boost, warmth, presence, vibrato & studio polish
    val rumbleHpf = StereoBiquadFilter().apply {
        configureHighPass(sr, 70.0, 0.707)
    }
    val subBassFilter = StereoBiquadFilter().apply {
        configureLowShelf(sr, clamp(bassFreqHz, 50f, 180f).toDouble(), 0.75, clamp(bassBoostDb, -6f, 16f).toDouble())
    }
    val warmthFilter = StereoBiquadFilter().apply {
        configureLowShelf(sr, 200.0, 0.75, clamp(warmthDb, -6f, 10f).toDouble())
    }
    val presenceFilter = StereoBiquadFilter().apply {
        configurePeaking(sr, 3100.0, 1.15, clamp(presenceDb, -6f, 10f).toDouble())
    }
    val studioAirFilter = StereoBiquadFilter().apply {
        configurePeaking(sr, 8000.0, 2.0, 1.5)
    }

    val hasEcho = echoDelayMs > 5f && echoDecay > 0.02f
    val tailFrames = if (hasEcho) min(sr * 2, ((echoDelayMs * 4f) / 1000f * sr).roundToInt()) else 0
    val totalOutFrames = n + tailFrames

    val wetL = FloatArray(totalOutFrames)
    val wetR = FloatArray(totalOutFrames)

    val dualHaasShift = max(1, (0.011f * sr).roundToInt())
    val dualBlend = clamp(dualLayerMix, 0f, 1f)
    val primGain = 1f - dualBlend * 0.35f
    val secGain = dualBlend * 0.88f

    // Vibrato LFO ring buffer for creature/dragon growl modulation
    val vibOn = vibratoHz > 0.2f && vibratoDepth > 0.01f
    val vibRingSize = 512
    val vibMask = vibRingSize - 1
    val vibRingL = FloatArray(vibRingSize)
    val vibRingR = FloatArray(vibRingSize)
    var vibWriteIdx = 0
    var vibPhase = 0.0
    val vibInc = (2.0 * PI * clamp(vibratoHz, 0.5f, 24f)) / sr
    val vibMaxDelay = min(vibRingSize - 8.0, sr * 0.0045 * clamp(vibratoDepth, 0f, 1f))

    // Multi-tap stereo echo buffers (Abyssal Dragon 160ms/0.5 or Shadow Legion 90ms/0.4)
    val echoDelaySamplesL = max(16, (clamp(echoDelayMs, 10f, 500f) / 1000f * sr).roundToInt())
    val echoDelaySamplesR = max(16, (echoDelaySamplesL * 1.12f).roundToInt())
    val echoBufL = FloatArray(echoDelaySamplesL + 8)
    val echoBufR = FloatArray(echoDelaySamplesR + 8)
    var echoIdxL = 0
    var echoIdxR = 0
    val fbCoeff = clamp(echoDecay, 0f, 0.78f)
    val dryEchoKeep = if (hasEcho) 0.82f else 1.0f

    for (i in 0 until totalOutFrames) {
        var sampleL = if (i < n) primaryLayer[i] else 0f
        var sampleR = sampleL

        if (secondaryLayer != null) {
            val secL = if (i < n) secondaryLayer[i] else 0f
            val secIdxR = i - dualHaasShift
            val secR = if (secIdxR in 0 until n) secondaryLayer[secIdxR] else 0f
            sampleL = sampleL * primGain + secL * secGain
            sampleR = sampleR * primGain + secR * secGain
        }

        if (vibOn) {
            vibRingL[vibWriteIdx] = sampleL
            vibRingR[vibWriteIdx] = sampleR
            val lfo = 0.5 * (1.0 + kotlin.math.sin(vibPhase))
            val delay = 2.0 + lfo * vibMaxDelay
            val rp = (vibWriteIdx - delay + vibRingSize) % vibRingSize
            val i0 = floor(rp).toInt() and vibMask
            val i1 = (i0 + 1) and vibMask
            val frac = (rp - floor(rp)).toFloat()
            sampleL = vibRingL[i0] + (vibRingL[i1] - vibRingL[i0]) * frac
            sampleR = vibRingR[i0] + (vibRingR[i1] - vibRingR[i0]) * frac
            vibWriteIdx = (vibWriteIdx + 1) and vibMask
            vibPhase += vibInc
            if (vibPhase > 2.0 * PI) vibPhase -= 2.0 * PI
        }

        if (studioMasteringPolish) {
            sampleL = rumbleHpf.processLeft(sampleL)
            sampleR = rumbleHpf.processRight(sampleR)
        }
        if (abs(bassBoostDb) > 0.1f) {
            sampleL = subBassFilter.processLeft(sampleL)
            sampleR = subBassFilter.processRight(sampleR)
        }
        if (abs(warmthDb) > 0.1f) {
            sampleL = warmthFilter.processLeft(sampleL)
            sampleR = warmthFilter.processRight(sampleR)
        }
        if (abs(presenceDb) > 0.1f) {
            sampleL = presenceFilter.processLeft(sampleL)
            sampleR = presenceFilter.processRight(sampleR)
        }
        if (studioMasteringPolish) {
            sampleL = studioAirFilter.processLeft(sampleL)
            sampleR = studioAirFilter.processRight(sampleR)
        }

        if (hasEcho) {
            val delayedL = echoBufL[echoIdxL]
            val delayedR = echoBufR[echoIdxR]
            echoBufL[echoIdxL] = clamp(sampleL + delayedR * fbCoeff, -1.5f, 1.5f)
            echoBufR[echoIdxR] = clamp(sampleR + delayedL * fbCoeff, -1.5f, 1.5f)
            echoIdxL = (echoIdxL + 1) % echoDelaySamplesL
            echoIdxR = (echoIdxR + 1) % echoDelaySamplesR
            sampleL = sampleL * dryEchoKeep + delayedL * fbCoeff
            sampleR = sampleR * dryEchoKeep + delayedR * fbCoeff
        }

        wetL[i] = tanh(sampleL.toDouble()).toFloat()
        wetR[i] = tanh(sampleR.toDouble()).toFloat()
    }

    onProgress?.invoke(0.9f)
    yield()

    val limiter = LimiterProcessor(sr)
    val limParams = com.example.model.LimiterParams(ceilingDb = -0.45f, releaseMs = 70f) // ~0.95 brickwall ceiling
    limiter.processStereo(wetL, wetR, totalOutFrames, limParams)

    val wetMix = clamp(mix, 0f, 1f)
    val dryMix = 1f - wetMix
    val outL = FloatArray(totalOutFrames)
    val outR = FloatArray(totalOutFrames)
    for (i in 0 until totalOutFrames) {
        val dL = if (i < n) source.left[i] else 0f
        val dR = if (i < n) source.right[i] else 0f
        outL[i] = clamp(dL * dryMix + wetL[i] * wetMix, -0.95f, 0.95f)
        outR[i] = clamp(dR * dryMix + wetR[i] * wetMix, -0.95f, 0.95f)
    }

    onProgress?.invoke(1f)
    StereoAudioBuffer(outL, outR, sr)
}
