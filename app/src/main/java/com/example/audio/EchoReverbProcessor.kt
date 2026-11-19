package com.example.audio

import com.example.model.EchoReverbParams
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

private class DampedCombFilter(size: Int) {
    private val buffer = FloatArray(max(size, 64))
    private var idx = 0
    private var filterStore = 0f

    fun process(input: Float, feedback: Float, damp: Float): Float {
        val out = buffer[idx]
        filterStore = out * (1f - damp) + filterStore * damp
        var next = input + filterStore * feedback
        if (next > 4f) next = 4f else if (next < -4f) next = -4f
        buffer[idx] = next
        idx++
        if (idx >= buffer.size) idx = 0
        return out
    }

    fun reset() {
        buffer.fill(0f)
        idx = 0
        filterStore = 0f
    }
}

private class DiffuserAllpass(size: Int) {
    private val buffer = FloatArray(max(size, 16))
    private var idx = 0

    fun process(input: Float, g: Float): Float {
        val bufOut = buffer[idx]
        val out = -input * g + bufOut
        var store = input + bufOut * g
        if (store > 4f) store = 4f else if (store < -4f) store = -4f
        buffer[idx] = store
        idx++
        if (idx >= buffer.size) idx = 0
        return out
    }

    fun reset() {
        buffer.fill(0f)
        idx = 0
    }
}

class EchoReverbProcessor(private val sampleRate: Int = 44100) {
    private val srScale = sampleRate / 44100.0

    private val preDelayBufL = FloatArray((sampleRate * 0.25).toInt() + 4)
    private val preDelayBufR = FloatArray((sampleRate * 0.25).toInt() + 4)
    private var preDelayIdx = 0

    private val echoBufL = FloatArray((sampleRate * 2.2).toInt() + 8)
    private val echoBufR = FloatArray((sampleRate * 2.2).toInt() + 8)
    private var echoIdx = 0
    private var echoLpL = 0f
    private var echoLpR = 0f
    private var echoLfoPhase = 0.0

    private val erLineL = FloatArray((sampleRate * 0.12).toInt() + 8)
    private val erLineR = FloatArray((sampleRate * 0.12).toInt() + 8)
    private var erIdx = 0
    private val erTapsL = intArrayOf(311, 563, 887, 1297, 1811, 2399).map {
        max(16, (it * srScale).roundToInt())
    }.toIntArray()
    private val erTapsR = intArrayOf(359, 617, 941, 1381, 1907, 2521).map {
        max(16, (it * srScale).roundToInt())
    }.toIntArray()
    private val erGains = floatArrayOf(0.68f, 0.54f, 0.44f, 0.35f, 0.27f, 0.2f)

    private val fdnLines = intArrayOf(1493, 1901, 2383, 2897).map {
        FloatArray(max(64, (it * srScale).roundToInt()))
    }
    private val fdnIdx = IntArray(4)
    private val fdnLp = FloatArray(4)

    private val stereoSpread = (23 * srScale).roundToInt()
    private val combLengths = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassLengths = intArrayOf(556, 441, 341, 225)

    private val combsL = combLengths.map { DampedCombFilter((it * srScale).roundToInt()) }
    private val combsR = combLengths.map { DampedCombFilter((it * srScale).roundToInt() + stereoSpread) }
    private val allpassL = allpassLengths.map { DiffuserAllpass((it * srScale).roundToInt()) }
    private val allpassR = allpassLengths.map { DiffuserAllpass((it * srScale).roundToInt() + stereoSpread) }

    private val tankPreHpf = StereoBiquadFilter().apply { configureHighPass(sampleRate, 110.0, 0.707) }
    private val wetToneLpf = StereoBiquadFilter().apply { configureLowPass(sampleRate, 11000.0, 0.707) }

    private var smoothEchoStrength = 0f
    private var smoothEchoDepth = 0f
    private var smoothDelaySamples = 0.24f * sampleRate
    private var smoothFeedback = 0f
    private var smoothWetMix = 0f
    private var smoothStereoWidth = 0f

    private var params: EchoReverbParams? = null
    private var lastSampleRateUsed = -1

    fun configure(params: EchoReverbParams) {
        val prevDamping = this.params?.damping
        this.params = params
        if (prevDamping != params.damping || lastSampleRateUsed != sampleRate) {
            lastSampleRateUsed = sampleRate
            val wetCutoffHz = 14000.0 - clamp(params.damping, 0f, 1f) * 9500.0
            wetToneLpf.configureLowPass(sampleRate, wetCutoffHz, 0.707)
        }
    }

    fun reset() {
        preDelayBufL.fill(0f)
        preDelayBufR.fill(0f)
        preDelayIdx = 0
        echoBufL.fill(0f)
        echoBufR.fill(0f)
        echoIdx = 0
        echoLpL = 0f
        echoLpR = 0f
        echoLfoPhase = 0.0
        erLineL.fill(0f)
        erLineR.fill(0f)
        erIdx = 0
        fdnLines.forEach { it.fill(0f) }
        fdnIdx.fill(0)
        fdnLp.fill(0f)
        combsL.forEach { it.reset() }
        combsR.forEach { it.reset() }
        allpassL.forEach { it.reset() }
        allpassR.forEach { it.reset() }
        tankPreHpf.reset()
        wetToneLpf.reset()
        smoothEchoStrength = 0f
        smoothEchoDepth = 0f
        smoothFeedback = 0f
        smoothWetMix = 0f
        smoothStereoWidth = 0f
    }

    private fun readFrac(buf: FloatArray, writeIdx: Int, delaySamples: Float): Float {
        val len = buf.size
        var pos = writeIdx - delaySamples
        while (pos < 0f) pos += len
        while (pos >= len) pos -= len
        val base = floor(pos).toInt()
        val i0 = base % len
        val i1 = (i0 + 1) % len
        val frac = pos - base
        return buf[i0] + (buf[i1] - buf[i0]) * frac
    }

    fun processStereo(left: FloatArray, right: FloatArray, count: Int) {
        val p = params ?: return

        val targetEchoStrength = clamp(p.echoStrength, 0f, 1f)
        val targetEchoDepth = clamp(p.echoDepth, 0f, 1f)
        val targetDelaySamples = clamp(
            (p.delayTimeMs / 1000f) * sampleRate,
            sampleRate * 0.02f,
            (echoBufL.size - 16).toFloat(),
        )
        val targetFeedback = clamp(p.feedback, 0f, 0.86f)
        val targetWet = clamp(p.wetDryMix, 0f, 1f)
        val targetWidth = clamp(p.stereoWidth, 0f, 1f)
        if (targetWet <= 0.0005f && smoothWetMix <= 0.0005f) return

        val preDelaySamples = clamp(
            ((p.preDelayMs / 1000f) * sampleRate).roundToInt(),
            0,
            preDelayBufL.size - 2,
        )
        val size = clamp(p.reverbSize, 0f, 1f)
        val decay = clamp(p.reverbDecay, 0f, 1f)
        val combFeedback = clamp(0.68f + decay * 0.2f + size * 0.08f, 0.5f, 0.96f)
        val fdnFeedback = clamp(0.45f + decay * 0.34f + size * 0.11f, 0.35f, 0.9f)
        val damp = clamp(p.damping * 0.62f, 0.04f, 0.72f)
        val diffusion = clamp(0.35f + p.diffusion * 0.45f, 0.2f, 0.85f)
        val earlyGain = clamp(p.earlyReflections, 0f, 1f)
        val lateGain = clamp(p.lateReflections, 0f, 1f)
        val lfoInc = (2.0 * PI * 0.55) / sampleRate

        val smoothCoeff = 0.0025f
        val erLen = erLineL.size
        val preLen = preDelayBufL.size
        val echoLen = echoBufL.size
        val roomStretch = 0.45f + size * 0.85f
        val fdnDampCoeff = 1f - damp * 0.65f

        for (i in 0 until count) {
            smoothEchoStrength += (targetEchoStrength - smoothEchoStrength) * smoothCoeff
            smoothEchoDepth += (targetEchoDepth - smoothEchoDepth) * smoothCoeff
            smoothDelaySamples += (targetDelaySamples - smoothDelaySamples) * smoothCoeff
            smoothFeedback += (targetFeedback - smoothFeedback) * smoothCoeff
            smoothWetMix += (targetWet - smoothWetMix) * smoothCoeff
            smoothStereoWidth += (targetWidth - smoothStereoWidth) * smoothCoeff

            val dryL = left[i]
            val dryR = right[i]

            preDelayBufL[preDelayIdx] = dryL
            preDelayBufR[preDelayIdx] = dryR
            val preReadIdx = (preDelayIdx - preDelaySamples + preLen) % preLen
            val preL = preDelayBufL[preReadIdx]
            val preR = preDelayBufR[preReadIdx]
            preDelayIdx = (preDelayIdx + 1) % preLen

            val tankInL = tankPreHpf.processLeft(preL)
            val tankInR = tankPreHpf.processRight(preR)

            // 1. Multi-tap Modulated Ping-Pong Echo
            echoLfoPhase += lfoInc
            if (echoLfoPhase > PI * 2.0) echoLfoPhase -= PI * 2.0
            val modOffset = (sin(echoLfoPhase) * (1.0 + smoothEchoDepth * 4.0)).toFloat()

            val d1 = max(8f, smoothDelaySamples + modOffset)
            val d2 = max(8f, smoothDelaySamples * 0.6667f - modOffset * 0.7f)
            val d3 = max(8f, smoothDelaySamples * 1.3333f + modOffset * 0.5f)

            val tapL1 = readFrac(echoBufL, echoIdx, d1)
            val tapR1 = readFrac(echoBufR, echoIdx, d1)
            val tapL2 = readFrac(echoBufR, echoIdx, d2)
            val tapR2 = readFrac(echoBufL, echoIdx, d2)
            val tapL3 = readFrac(echoBufL, echoIdx, d3)
            val tapR3 = readFrac(echoBufR, echoIdx, d3)

            val echoOutL = (tapL1 * 0.58f + tapL2 * 0.27f + tapL3 * 0.15f) * smoothEchoStrength
            val echoOutR = (tapR1 * 0.58f + tapR2 * 0.27f + tapR3 * 0.15f) * smoothEchoStrength

            val pingCross = 0.25f + smoothStereoWidth * 0.50f
            val fbCoeff = clamp(smoothFeedback * (0.58f + smoothEchoDepth * 0.30f), 0f, 0.78f)
            val echoColor = 0.35f + (1f - damp) * 0.45f
            val fbReturnL = (tapL1 * (1f - pingCross) + tapR1 * pingCross) * fbCoeff
            val fbReturnR = (tapR1 * (1f - pingCross) + tapL1 * pingCross) * fbCoeff
            val rawFbL = tankInL * 0.88f + tanh((fbReturnL * 0.85).toDouble()).toFloat()
            val rawFbR = tankInR * 0.88f + tanh((fbReturnR * 0.85).toDouble()).toFloat()

            echoLpL += (rawFbL - echoLpL) * echoColor
            echoLpR += (rawFbR - echoLpR) * echoColor
            echoBufL[echoIdx] = tanh(echoLpL.toDouble()).toFloat()
            echoBufR[echoIdx] = tanh(echoLpR.toDouble()).toFloat()
            echoIdx = (echoIdx + 1) % echoLen

            // 2. 6-Tap Early Reflections
            erLineL[erIdx] = tankInL + echoOutL * 0.2f
            erLineR[erIdx] = tankInR + echoOutR * 0.2f
            var erSumL = 0f
            var erSumR = 0f
            for (t in 0 until 6) {
                val tapOffsetL = (erTapsL[t] * roomStretch).roundToInt()
                val tapOffsetR = (erTapsR[t] * roomStretch).roundToInt()
                val idxL = (erIdx - tapOffsetL + erLen) % erLen
                val idxR = (erIdx - tapOffsetR + erLen) % erLen
                erSumL += erLineL[idxL] * erGains[t]
                erSumR += erLineR[idxR] * erGains[t]
            }
            erIdx = (erIdx + 1) % erLen
            val earlyL = erSumL * 0.42f * earlyGain
            val earlyR = erSumR * 0.42f * earlyGain

            // 3. 4-Channel Hadamard FDN Tail
            val fdnIn = (tankInL + tankInR) * 0.5f + (echoOutL + echoOutR) * 0.24f + (earlyL + earlyR) * 0.25f
            val f0 = fdnLines[0][fdnIdx[0]]
            val f1 = fdnLines[1][fdnIdx[1]]
            val f2 = fdnLines[2][fdnIdx[2]]
            val f3 = fdnLines[3][fdnIdx[3]]

            val h0 = 0.5f * (f0 + f1 + f2 + f3)
            val h1 = 0.5f * (f0 - f1 + f2 - f3)
            val h2 = 0.5f * (f0 + f1 - f2 - f3)
            val h3 = 0.5f * (f0 - f1 - f2 + f3)

            for (ch in 0 until 4) {
                val h = when (ch) {
                    0 -> h0
                    1 -> h1
                    2 -> h2
                    else -> h3
                }
                val dampedVal = fdnLp[ch] + (h - fdnLp[ch]) * fdnDampCoeff
                fdnLp[ch] = dampedVal
                var nextFdn = fdnIn * 0.35f + dampedVal * fdnFeedback
                if (nextFdn > 3f) nextFdn = 3f else if (nextFdn < -3f) nextFdn = -3f
                fdnLines[ch][fdnIdx[ch]] = nextFdn
                fdnIdx[ch] = (fdnIdx[ch] + 1) % fdnLines[ch].size
            }

            // 4. 8 Parallel Schroeder Combs + 4 Allpass Diffusers
            val monoInput = fdnIn * 0.3f + (h0 + h1 + h2 + h3) * 0.22f
            var combSumL = 0f
            var combSumR = 0f
            for (c in 0 until 8) {
                combSumL += combsL[c].process(monoInput, combFeedback, damp)
                combSumR += combsR[c].process(monoInput, combFeedback, damp)
            }
            var diffL = combSumL * 0.14f
            var diffR = combSumR * 0.14f
            for (a in 0 until 4) {
                diffL = allpassL[a].process(diffL, diffusion)
                diffR = allpassR[a].process(diffR, diffusion)
            }

            val tailDepth = 0.4f + smoothEchoDepth * 0.6f
            val reverbTailL = ((h0 + h2) * 0.22f + diffL * 0.85f) * lateGain * tailDepth
            val reverbTailR = ((h1 + h3) * 0.22f + diffR * 0.85f) * lateGain * tailDepth

            val rawWetL = echoOutL * 0.68f + earlyL * 0.82f + reverbTailL * 0.82f
            val rawWetR = echoOutR * 0.68f + earlyR * 0.82f + reverbTailR * 0.82f
            val wetL = wetToneLpf.processLeft(rawWetL)
            val wetR = wetToneLpf.processRight(rawWetR)

            val mid = (wetL + wetR) * 0.5f
            val side = (wetL - wetR) * 0.5f * (0.3f + smoothStereoWidth * 1.25f)
            val wideWetL = mid + side
            val wideWetR = mid - side

            val dryKeep = 1f - smoothWetMix * 0.40f
            val wetGain = smoothWetMix * 0.85f

            left[i] = (0.94 * tanh(((dryL * dryKeep + wideWetL * wetGain) * 0.92).toDouble())).toFloat()
            right[i] = (0.94 * tanh(((dryR * dryKeep + wideWetR * wetGain) * 0.92).toDouble())).toFloat()
        }
    }
}
