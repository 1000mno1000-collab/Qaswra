package com.example.audio.dsp

import com.example.model.EchoReverbParams
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

/**
 * High-impact stereo Echo & Reverb processor combining:
 * 1. Pre-delay stage for hall/mosque acoustic separation
 * 2. Multi-tap stereo ping-pong echo with warm analog damping & LFO modulation
 * 3. 6-tap Early Reflections stereo room matrix
 * 4. 4-channel Hadamard Feedback Delay Network (FDN) for dense, lush ambient tails
 * 5. 8 parallel damped Schroeder combs + 4 series allpass diffusers
 * 6. Boosted wet gain stage + Mid/Side stereo widening
 */
class EchoReverbProcessor(private val sampleRate: Int = 44100) {

    // Pre-delay up to 220ms
    private val preDelayMax = (sampleRate * 0.22f).toInt()
    private val preDelayBufL = FloatArray(preDelayMax)
    private val preDelayBufR = FloatArray(preDelayMax)
    private var preDelayWrite = 0

    // Multi-tap Stereo Ping-Pong Echo up to 1.5s
    private val delayMax = (sampleRate * 1.5f).toInt()
    private val echoBufL = FloatArray(delayMax)
    private val echoBufR = FloatArray(delayMax)
    private var echoWrite = 0
    private var echoLpL = 0f
    private var echoLpR = 0f
    private var echoLfoPhase = 0f

    // Early Reflections tap buffer (120ms)
    private val erMax = (sampleRate * 0.12f).toInt()
    private val erBufL = FloatArray(erMax)
    private val erBufR = FloatArray(erMax)
    private var erWrite = 0
    private val erTapsL = intArrayOf(
        (0.0043f * sampleRate).toInt(),
        (0.0117f * sampleRate).toInt(),
        (0.0193f * sampleRate).toInt(),
        (0.0289f * sampleRate).toInt(),
        (0.0411f * sampleRate).toInt(),
        (0.0587f * sampleRate).toInt()
    )
    private val erTapsR = intArrayOf(
        (0.0059f * sampleRate).toInt(),
        (0.0139f * sampleRate).toInt(),
        (0.0227f * sampleRate).toInt(),
        (0.0331f * sampleRate).toInt(),
        (0.0467f * sampleRate).toInt(),
        (0.0643f * sampleRate).toInt()
    )
    private val erGains = floatArrayOf(0.88f, 0.74f, 0.62f, 0.51f, 0.40f, 0.30f)

    // 4-Channel Hadamard Feedback Delay Network (mutually prime lengths scaled to sampleRate)
    private val fdnBaseLengths = intArrayOf(
        (sampleRate * 0.0337f).toInt(), // ~33.7 ms
        (sampleRate * 0.0451f).toInt(), // ~45.1 ms
        (sampleRate * 0.0569f).toInt(), // ~56.9 ms
        (sampleRate * 0.0719f).toInt()  // ~71.9 ms
    )
    private val fdnBuffers = Array(4) { idx -> FloatArray((fdnBaseLengths[idx] * 2.4f).toInt().coerceAtLeast(64)) }
    private val fdnActiveLens = IntArray(4) { idx -> fdnBaseLengths[idx].coerceAtLeast(32) }
    private val fdnWritePos = IntArray(4)
    private val fdnLpState = FloatArray(4)

    // Schroeder 8 parallel damped combs + 4 series allpasses per channel
    private val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allPassTunings = intArrayOf(556, 441, 341, 225)
    private val stereoSpread = 23

    private val combsL = Array(8) { idx -> CombFilter((combTunings[idx] * 2.2f).toInt()) }
    private val combsR = Array(8) { idx -> CombFilter(((combTunings[idx] + stereoSpread) * 2.2f).toInt()) }
    private val allPassesL = Array(4) { idx -> AllPassFilter((allPassTunings[idx] * 1.8f).toInt()) }
    private val allPassesR = Array(4) { idx -> AllPassFilter(((allPassTunings[idx] + stereoSpread) * 1.8f).toInt()) }

    fun reset() {
        preDelayBufL.fill(0f)
        preDelayBufR.fill(0f)
        preDelayWrite = 0
        echoBufL.fill(0f)
        echoBufR.fill(0f)
        echoWrite = 0
        echoLpL = 0f
        echoLpR = 0f
        echoLfoPhase = 0f
        erBufL.fill(0f)
        erBufR.fill(0f)
        erWrite = 0
        for (k in 0 until 4) {
            fdnBuffers[k].fill(0f)
            fdnWritePos[k] = 0
            fdnLpState[k] = 0f
        }
        combsL.forEach { it.reset() }
        combsR.forEach { it.reset() }
        allPassesL.forEach { it.reset() }
        allPassesR.forEach { it.reset() }
    }

    fun processBlock(left: FloatArray, right: FloatArray, numFrames: Int, params: EchoReverbParams) {
        val preDelaySamples = ((params.preDelayMs / 1000f) * sampleRate).toInt().coerceIn(1, preDelayMax - 2)
        val baseDelaySamples = ((params.delayTimeMs / 1000f) * sampleRate).coerceIn(44f, (delayMax - 128).toFloat())
        val feedback = params.feedback.coerceIn(0f, 0.90f)
        val echoStrength = params.echoStrength.coerceIn(0f, 1f)
        val echoDepth = params.echoDepth.coerceIn(0f, 1f)

        val sizeScale = 0.55f + params.reverbSize.coerceIn(0f, 1f) * 1.45f
        val combFeedback = (0.64f + params.reverbDecay.coerceIn(0f, 0.95f) * 0.33f).coerceIn(0.45f, 0.96f)
        val fdnFeedback = (0.58f + params.reverbDecay.coerceIn(0f, 0.95f) * 0.38f).coerceIn(0.45f, 0.955f)
        val damp = params.damping.coerceIn(0.02f, 0.92f)
        val diffusionFeedback = 0.28f + params.diffusion.coerceIn(0f, 1f) * 0.48f
        val earlyMix = params.earlyReflections.coerceIn(0f, 1f)
        val lateMix = params.lateReflections.coerceIn(0f, 1f)
        val width = params.stereoWidth.coerceIn(0f, 2.2f)
        val wetMix = params.wetDryMix.coerceIn(0f, 1f)

        // Retain crisp vocal presence while letting high wet mixes bloom dramatically
        val dryMix = 1f - (wetMix * 0.36f)
        val wetBoostGain = (1.55f + echoDepth * 0.45f + lateMix * 0.25f)

        for (k in 0 until 4) {
            val len = (fdnBaseLengths[k] * sizeScale).toInt().coerceIn(32, fdnBuffers[k].size - 2)
            fdnActiveLens[k] = len
            if (fdnWritePos[k] >= len) fdnWritePos[k] = 0
        }
        for (c in 0 until 8) {
            combsL[c].setActiveLength((combTunings[c] * sizeScale).toInt())
            combsR[c].setActiveLength(((combTunings[c] + stereoSpread) * sizeScale).toInt())
        }
        val apSizeScale = 0.65f + params.diffusion * 0.65f
        for (a in 0 until 4) {
            allPassesL[a].setActiveLength((allPassTunings[a] * apSizeScale).toInt())
            allPassesR[a].setActiveLength(((allPassTunings[a] + stereoSpread) * apSizeScale).toInt())
        }

        val lfoInc = (2.0 * PI * 0.52 / sampleRate).toFloat()
        val modSamples = echoDepth * (sampleRate * 0.0032f)
        val echoToneAlpha = 0.78f - echoDepth * 0.40f
        val fdnTap = FloatArray(4)

        for (i in 0 until numFrames) {
            val dryL = left[i]
            val dryR = right[i]

            // 1. Pre-delay buffer
            preDelayBufL[preDelayWrite] = dryL
            preDelayBufR[preDelayWrite] = dryR
            var pdRead = preDelayWrite - preDelaySamples
            if (pdRead < 0) pdRead += preDelayMax
            val pdL = preDelayBufL[pdRead]
            val pdR = preDelayBufR[pdRead]
            preDelayWrite++
            if (preDelayWrite >= preDelayMax) preDelayWrite = 0

            // 2. Multi-tap Stereo Modulated Ping-Pong Echo (primary + secondary golden-ratio taps)
            echoLfoPhase += lfoInc
            if (echoLfoPhase > (2.0 * PI).toFloat()) echoLfoPhase -= (2.0 * PI).toFloat()
            val modOffset = sin(echoLfoPhase) * modSamples

            val tap1L = readFractional(echoBufL, echoWrite - (baseDelaySamples + modOffset), delayMax)
            val tap2L = readFractional(echoBufL, echoWrite - (baseDelaySamples * 0.618f - modOffset * 0.5f), delayMax)
            val tap3L = readFractional(echoBufR, echoWrite - (baseDelaySamples * 0.382f), delayMax)

            val tap1R = readFractional(echoBufR, echoWrite - (baseDelaySamples * (0.79f + echoDepth * 0.21f) - modOffset), delayMax)
            val tap2R = readFractional(echoBufR, echoWrite - (baseDelaySamples * 0.54f + modOffset * 0.5f), delayMax)
            val tap3R = readFractional(echoBufL, echoWrite - (baseDelaySamples * 0.34f), delayMax)

            val multiEchoL = tap1L * 0.70f + tap2L * 0.35f + tap3L * 0.24f
            val multiEchoR = tap1R * 0.70f + tap2R * 0.35f + tap3R * 0.24f

            // Warm feedback lowpass filter
            echoLpL += echoToneAlpha * (tap1L - echoLpL)
            echoLpR += echoToneAlpha * (tap1R - echoLpR)

            val cross = 0.30f + echoDepth * 0.55f
            val fbL = echoLpL * (1f - cross) + echoLpR * cross
            val fbR = echoLpR * (1f - cross) + echoLpL * cross

            echoBufL[echoWrite] = (dryL + fbL * feedback).coerceIn(-1.6f, 1.6f)
            echoBufR[echoWrite] = (dryR + fbR * feedback).coerceIn(-1.6f, 1.6f)
            echoWrite++
            if (echoWrite >= delayMax) echoWrite = 0

            val echoOutL = multiEchoL * (echoStrength * 1.25f)
            val echoOutR = multiEchoR * (echoStrength * 1.25f)

            // 3. Early Reflections (6-tap prime stereo room)
            val reverbInL = pdL + echoOutL * (0.42f + echoDepth * 0.32f)
            val reverbInR = pdR + echoOutR * (0.42f + echoDepth * 0.32f)
            erBufL[erWrite] = reverbInL
            erBufR[erWrite] = reverbInR
            var erSumL = 0f
            var erSumR = 0f
            val erScale = 0.6f + params.reverbSize * 0.7f
            for (t in 0 until 6) {
                var rIdxL = erWrite - (erTapsL[t] * erScale).toInt().coerceIn(1, erMax - 2)
                if (rIdxL < 0) rIdxL += erMax
                var rIdxR = erWrite - (erTapsR[t] * erScale).toInt().coerceIn(1, erMax - 2)
                if (rIdxR < 0) rIdxR += erMax
                erSumL += erBufL[rIdxL] * erGains[t]
                erSumR += erBufR[rIdxR] * erGains[t]
            }
            erWrite++
            if (erWrite >= erMax) erWrite = 0
            erSumL *= 0.36f * earlyMix
            erSumR *= 0.36f * earlyMix

            // 4. 4-Channel Hadamard Feedback Delay Network (lush stereo ambient density)
            for (k in 0 until 4) {
                val len = fdnActiveLens[k]
                val wPos = fdnWritePos[k]
                val raw = fdnBuffers[k][wPos]
                fdnLpState[k] = raw * (1f - damp * 0.72f) + fdnLpState[k] * (damp * 0.72f)
                if (fdnLpState[k] > -1e-18f && fdnLpState[k] < 1e-18f) fdnLpState[k] = 0f
                fdnTap[k] = fdnLpState[k]
            }
            val d0 = fdnTap[0]; val d1 = fdnTap[1]; val d2 = fdnTap[2]; val d3 = fdnTap[3]
            val h0 = 0.5f * (d0 + d1 + d2 + d3)
            val h1 = 0.5f * (d0 - d1 + d2 - d3)
            val h2 = 0.5f * (d0 + d1 - d2 - d3)
            val h3 = 0.5f * (d0 - d1 - d2 + d3)

            val fdnDriveL = reverbInL * 0.55f
            val fdnDriveR = reverbInR * 0.55f
            fdnBuffers[0][fdnWritePos[0]] = fdnDriveL + h0 * fdnFeedback
            fdnBuffers[1][fdnWritePos[1]] = fdnDriveR + h1 * fdnFeedback
            fdnBuffers[2][fdnWritePos[2]] = fdnDriveL + h2 * fdnFeedback
            fdnBuffers[3][fdnWritePos[3]] = -fdnDriveR + h3 * fdnFeedback
            for (k in 0 until 4) {
                fdnWritePos[k]++
                if (fdnWritePos[k] >= fdnActiveLens[k]) fdnWritePos[k] = 0
            }
            val fdnOutL = (d0 + d2 - d1 * 0.4f) * (0.48f * lateMix)
            val fdnOutR = (d1 + d3 - d0 * 0.4f) * (0.48f * lateMix)

            // 5. Schroeder Late Reverb (8 parallel damped combs + 4 series allpass diffusers)
            val monoSendL = (reverbInL * 0.75f + reverbInR * 0.25f) * 0.068f
            val monoSendR = (reverbInR * 0.75f + reverbInL * 0.25f) * 0.068f
            var schroederL = 0f
            var schroederR = 0f
            for (c in 0 until 8) {
                schroederL += combsL[c].process(monoSendL, combFeedback, damp)
                schroederR += combsR[c].process(monoSendR, combFeedback, damp)
            }
            for (a in 0 until 4) {
                schroederL = allPassesL[a].process(schroederL, diffusionFeedback)
                schroederR = allPassesR[a].process(schroederR, diffusionFeedback)
            }
            schroederL *= lateMix * 1.15f
            schroederR *= lateMix * 1.15f

            // 6. Combine Wet Bus + Boosted Gain + Mid/Side Stereo Widening
            val rawWetL = (echoOutL + erSumL + fdnOutL + schroederL) * wetBoostGain
            val rawWetR = (echoOutR + erSumR + fdnOutR + schroederR) * wetBoostGain
            val mid = (rawWetL + rawWetR) * 0.5f
            val side = (rawWetL - rawWetR) * 0.5f * (width * 1.15f)
            val wideWetL = tanh((mid + side).toDouble()).toFloat()
            val wideWetR = tanh((mid - side).toDouble()).toFloat()

            // 7. Wet / Dry Blend
            left[i] = dryL * dryMix + wideWetL * wetMix
            right[i] = dryR * dryMix + wideWetR * wetMix
        }
    }

    private fun readFractional(buf: FloatArray, pos: Float, size: Int): Float {
        var p = pos
        while (p < 0f) p += size
        while (p >= size) p -= size
        val i0 = p.toInt()
        val i1 = if (i0 + 1 >= size) 0 else i0 + 1
        val frac = p - i0
        return buf[i0] + (buf[i1] - buf[i0]) * frac
    }

    private class CombFilter(maxSize: Int) {
        private val buffer = FloatArray(maxSize.coerceAtLeast(16))
        private var activeLen = buffer.size
        private var index = 0
        private var filterStore = 0f

        fun setActiveLength(len: Int) {
            activeLen = len.coerceIn(16, buffer.size)
            if (index >= activeLen) index = 0
        }

        fun reset() {
            buffer.fill(0f)
            index = 0
            filterStore = 0f
        }

        fun process(input: Float, feedback: Float, damp: Float): Float {
            val output = buffer[index]
            filterStore = output * (1f - damp) + filterStore * damp
            if (filterStore > -1e-18f && filterStore < 1e-18f) filterStore = 0f
            buffer[index] = input + filterStore * feedback
            index++
            if (index >= activeLen) index = 0
            return output
        }
    }

    private class AllPassFilter(maxSize: Int) {
        private val buffer = FloatArray(maxSize.coerceAtLeast(16))
        private var activeLen = buffer.size
        private var index = 0

        fun setActiveLength(len: Int) {
            activeLen = len.coerceIn(16, buffer.size)
            if (index >= activeLen) index = 0
        }

        fun reset() {
            buffer.fill(0f)
            index = 0
        }

        fun process(input: Float, feedback: Float): Float {
            val bufOut = buffer[index]
            val output = -input + bufOut
            buffer[index] = input + bufOut * feedback
            index++
            if (index >= activeLen) index = 0
            return output
        }
    }
}
