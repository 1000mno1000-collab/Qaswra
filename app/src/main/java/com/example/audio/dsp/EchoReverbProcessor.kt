package com.example.audio.dsp

import com.example.model.EchoReverbParams
import kotlin.math.PI
import kotlin.math.sin

class EchoReverbProcessor(private val sampleRate: Int = 44100) {

    // Pre-delay up to 200ms
    private val preDelayMax = (sampleRate * 0.22f).toInt()
    private val preDelayBufL = FloatArray(preDelayMax)
    private val preDelayBufR = FloatArray(preDelayMax)
    private var preDelayWrite = 0

    // Stereo Ping-Pong / Dual Delay up to 1.5s
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
    private val erGains = floatArrayOf(0.84f, 0.71f, 0.60f, 0.49f, 0.38f, 0.29f)

    // Freeverb-inspired 8 parallel damped combs + 4 series allpasses per channel
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

        val sizeScale = 0.55f + params.reverbSize.coerceIn(0f, 1f) * 1.35f
        val combFeedback = (0.62f + params.reverbDecay.coerceIn(0f, 0.95f) * 0.34f).coerceIn(0.4f, 0.96f)
        val damp = params.damping.coerceIn(0.02f, 0.92f)
        val diffusionFeedback = 0.25f + params.diffusion.coerceIn(0f, 1f) * 0.45f
        val earlyMix = params.earlyReflections.coerceIn(0f, 1f)
        val lateMix = params.lateReflections.coerceIn(0f, 1f)
        val width = params.stereoWidth.coerceIn(0f, 2.0f)
        val wetMix = params.wetDryMix.coerceIn(0f, 1f)
        val dryMix = 1f - (wetMix * 0.45f)

        // Update comb lengths & parameters once per block
        for (c in 0 until 8) {
            combsL[c].setActiveLength((combTunings[c] * sizeScale).toInt())
            combsR[c].setActiveLength(((combTunings[c] + stereoSpread) * sizeScale).toInt())
        }
        val apSizeScale = 0.65f + params.diffusion * 0.65f
        for (a in 0 until 4) {
            allPassesL[a].setActiveLength((allPassTunings[a] * apSizeScale).toInt())
            allPassesR[a].setActiveLength(((allPassTunings[a] + stereoSpread) * apSizeScale).toInt())
        }

        val lfoInc = (2.0 * PI * 0.45 / sampleRate).toFloat()
        val modSamples = echoDepth * (sampleRate * 0.0028f) // subtle analog chorus drift on echo taps
        val echoToneAlpha = 0.72f - echoDepth * 0.42f

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

            // 2. Stereo Modulated Ping-Pong Echo
            echoLfoPhase += lfoInc
            if (echoLfoPhase > (2.0 * PI).toFloat()) echoLfoPhase -= (2.0 * PI).toFloat()
            val modOffset = sin(echoLfoPhase) * modSamples

            val readPosL = echoWrite - (baseDelaySamples + modOffset)
            val readPosR = echoWrite - (baseDelaySamples * (0.78f + echoDepth * 0.22f) - modOffset)
            val delayedL = readFractional(echoBufL, readPosL, delayMax)
            val delayedR = readFractional(echoBufR, readPosR, delayMax)

            // Warm feedback lowpass filter
            echoLpL = echoLpL + echoToneAlpha * (delayedL - echoLpL)
            echoLpR = echoLpR + echoToneAlpha * (delayedR - echoLpR)

            // Ping-pong cross feedback blended by echoDepth
            val cross = 0.25f + echoDepth * 0.55f
            val fbL = echoLpL * (1f - cross) + echoLpR * cross
            val fbR = echoLpR * (1f - cross) + echoLpL * cross

            echoBufL[echoWrite] = (dryL + fbL * feedback).coerceIn(-1.5f, 1.5f)
            echoBufR[echoWrite] = (dryR + fbR * feedback).coerceIn(-1.5f, 1.5f)
            echoWrite++
            if (echoWrite >= delayMax) echoWrite = 0

            val echoOutL = delayedL * echoStrength
            val echoOutR = delayedR * echoStrength

            // 3. Early Reflections (6-tap prime stereo room)
            val reverbInL = pdL + echoOutL * (0.35f + echoDepth * 0.3f)
            val reverbInR = pdR + echoOutR * (0.35f + echoDepth * 0.3f)
            erBufL[erWrite] = reverbInL
            erBufR[erWrite] = reverbInR
            var erSumL = 0f
            var erSumR = 0f
            val erScale = 0.6f + params.reverbSize * 0.6f
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
            erSumL *= 0.28f * earlyMix
            erSumR *= 0.28f * earlyMix

            // 4. Late Reverb (8 parallel damped combs + 4 series allpass diffusers)
            val monoSendL = (reverbInL * 0.8f + reverbInR * 0.2f) * 0.028f
            val monoSendR = (reverbInR * 0.8f + reverbInL * 0.2f) * 0.028f
            var lateL = 0f
            var lateR = 0f
            for (c in 0 until 8) {
                lateL += combsL[c].process(monoSendL, combFeedback, damp)
                lateR += combsR[c].process(monoSendR, combFeedback, damp)
            }
            for (a in 0 until 4) {
                lateL = allPassesL[a].process(lateL, diffusionFeedback)
                lateR = allPassesR[a].process(lateR, diffusionFeedback)
            }
            lateL *= lateMix * 1.4f
            lateR *= lateMix * 1.4f

            // 5. Combine Wet Bus + Mid/Side Stereo Width
            val rawWetL = echoOutL + erSumL + lateL
            val rawWetR = echoOutR + erSumR + lateR
            val mid = (rawWetL + rawWetR) * 0.5f
            val side = (rawWetL - rawWetR) * 0.5f * width
            val wideWetL = mid + side
            val wideWetR = mid - side

            // 6. Wet / Dry Mix
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
            // Denormal guard
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
