package com.example.audio

import com.example.model.ChorusParams
import com.example.model.CompressorParams
import com.example.model.DistortionMode
import com.example.model.DistortionParams
import com.example.model.EqualizerParams
import com.example.model.FlangerParams
import com.example.model.LimiterParams
import com.example.model.PhaserParams
import com.example.model.PitchShiftParams
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

class EqualizerProcessor {
    private val bands = Array(10) { StereoBiquadFilter() }
    private val activeBands = BooleanArray(10)

    fun configure(sampleRate: Int, p: EqualizerParams) {
        val gains = p.resolved10Bands()
        for (i in 0 until 10) {
            val g = gains[i].toDouble()
            activeBands[i] = abs(g) >= 0.02
            when (i) {
                0 -> bands[i].configureLowShelf(sampleRate, 31.0, 0.85, g)
                9 -> bands[i].configureHighShelf(sampleRate, 16000.0, 0.85, g)
                else -> bands[i].configurePeaking(
                    sampleRate = sampleRate,
                    freqHz = com.example.model.EQ_10_FREQUENCIES_HZ[i].toDouble(),
                    q = 1.41,
                    gainDb = g,
                )
            }
        }
    }

    fun processStereo(left: FloatArray, right: FloatArray, count: Int) {
        for (i in 0 until 10) {
            if (activeBands[i]) {
                bands[i].processStereo(left, right, count)
            }
        }
    }

    fun reset() {
        for (band in bands) band.reset()
    }

    companion object {
        fun computeMagnitudeCurveDb(
            sampleRate: Int,
            p: EqualizerParams,
            freqPointsHz: FloatArray,
        ): FloatArray {
            val sr = sampleRate.coerceAtLeast(8000)
            val gains = p.resolved10Bands()
            val filters = Array(10) { i ->
                StereoBiquadFilter().apply {
                    val g = gains[i].toDouble()
                    when (i) {
                        0 -> configureLowShelf(sr, 31.0, 0.85, g)
                        9 -> configureHighShelf(sr, 16000.0, 0.85, g)
                        else -> configurePeaking(sr, com.example.model.EQ_10_FREQUENCIES_HZ[i].toDouble(), 1.41, g)
                    }
                }
            }
            return FloatArray(freqPointsHz.size) { idx ->
                val f = freqPointsHz[idx].toDouble()
                var sumDb = 0.0
                for (i in 0 until 10) {
                    if (abs(gains[i]) >= 0.02f) {
                        sumDb += filters[i].magnitudeDbAt(sr, f)
                    }
                }
                sumDb.toFloat().coerceIn(-18f, 18f)
            }
        }
    }
}

class PitchShiftProcessor {
    private val bufSize = 16384
    private val mask = bufSize - 1
    private val ringL = FloatArray(bufSize)
    private val ringR = FloatArray(bufSize)
    private var writeIdx = 0
    private var readPosA = 0.0
    private var readPosB = 2048.0
    private val grainSize = 4096

    private fun readInterpolated(ring: FloatArray, pos: Double): Float {
        val i0 = floor(pos).toInt() and mask
        val i1 = (i0 + 1) and mask
        val frac = (pos - floor(pos)).toFloat()
        return ring[i0] + (ring[i1] - ring[i0]) * frac
    }

    fun processStereo(
        left: FloatArray,
        right: FloatArray,
        count: Int,
        p: PitchShiftParams,
    ) {
        if (abs(p.semitones) < 0.05f) return
        val pitchRatio = clamp(2.0.pow(p.semitones / 12.0), 0.5, 2.0)
        val wet = clamp(p.wetMix, 0f, 1f)
        val dry = 1f - wet
        val halfGrain = grainSize * 0.5

        for (i in 0 until count) {
            val inL = left[i]
            val inR = right[i]
            ringL[writeIdx] = inL
            ringR[writeIdx] = inR

            val phaseA = (((writeIdx - readPosA) % grainSize) + grainSize) % grainSize
            val normPhase = (phaseA / grainSize).coerceIn(0.0, 1.0)
            val weightA = (0.5 * (1.0 - kotlin.math.cos(2.0 * PI * normPhase))).toFloat()
            val weightB = 1f - weightA

            val outL = readInterpolated(ringL, readPosA) * weightA + readInterpolated(ringL, readPosB) * weightB
            val outR = readInterpolated(ringR, readPosA) * weightA + readInterpolated(ringR, readPosB) * weightB

            left[i] = inL * dry + outL * wet
            right[i] = inR * dry + outR * wet

            writeIdx = (writeIdx + 1) and mask
            readPosA = (readPosA + pitchRatio) % bufSize
            readPosB = (readPosB + pitchRatio) % bufSize

            val distA = ((writeIdx - readPosA) + bufSize) % bufSize
            if (distA < 128 || distA > grainSize + 2048) {
                readPosA = ((writeIdx - halfGrain) + bufSize) % bufSize
            }
            val distB = ((writeIdx - readPosB) + bufSize) % bufSize
            if (distB < 128 || distB > grainSize + 2048) {
                readPosB = ((writeIdx - grainSize.toDouble()) + bufSize) % bufSize
            }
        }
    }

    fun reset() {
        ringL.fill(0f)
        ringR.fill(0f)
        writeIdx = 0
        readPosA = 0.0
        readPosB = grainSize / 2.0
    }
}

class ChorusProcessor(private val sampleRate: Int = 44100) {
    private val delayL = FloatArray((sampleRate * 0.08).toInt() + 8)
    private val delayR = FloatArray((sampleRate * 0.08).toInt() + 8)
    private var idx = 0
    private var lfoPhase = 0.0

    private fun readFrac(buf: FloatArray, delaySamples: Float): Float {
        val len = buf.size
        var pos = idx - delaySamples
        while (pos < 0f) pos += len
        val base = floor(pos).toInt()
        val i0 = base % len
        val i1 = (i0 + 1) % len
        val frac = pos - base
        return buf[i0] + (buf[i1] - buf[i0]) * frac
    }

    fun processStereo(
        left: FloatArray,
        right: FloatArray,
        count: Int,
        p: ChorusParams,
    ) {
        val baseSamples = 0.015f * sampleRate
        val depthSamples = clamp((p.depthMs / 1000f) * sampleRate, 1f, delayL.size * 0.4f)
        val lfoInc = (2.0 * PI * clamp(p.rateHz, 0.1f, 8f)) / sampleRate
        val wet = clamp(p.mix, 0f, 1f)
        val dry = 1f - wet * 0.4f
        val len = delayL.size

        for (i in 0 until count) {
            val inL = left[i]
            val inR = right[i]
            delayL[idx] = inL
            delayR[idx] = inR

            val lfoL = sin(lfoPhase).toFloat()
            val lfoR = sin(lfoPhase + PI * 0.5).toFloat()
            val voiceL1 = readFrac(delayL, baseSamples + lfoL * depthSamples)
            val voiceL2 = readFrac(delayL, baseSamples * 1.25f - lfoL * depthSamples * 0.7f)
            val voiceR1 = readFrac(delayR, baseSamples + lfoR * depthSamples)
            val voiceR2 = readFrac(delayR, baseSamples * 1.25f - lfoR * depthSamples * 0.7f)

            left[i] = clamp(inL * dry + (voiceL1 + voiceL2) * 0.5f * wet, -1f, 1f)
            right[i] = clamp(inR * dry + (voiceR1 + voiceR2) * 0.5f * wet, -1f, 1f)

            idx = (idx + 1) % len
            lfoPhase += lfoInc
            if (lfoPhase > PI * 2.0) lfoPhase -= PI * 2.0
        }
    }

    fun reset() {
        delayL.fill(0f)
        delayR.fill(0f)
        idx = 0
        lfoPhase = 0.0
    }
}

class FlangerProcessor(private val sampleRate: Int = 44100) {
    private val delayL = FloatArray((sampleRate * 0.03).toInt() + 8)
    private val delayR = FloatArray((sampleRate * 0.03).toInt() + 8)
    private var idx = 0
    private var lfoPhase = 0.0

    private fun readFrac(buf: FloatArray, delaySamples: Float): Float {
        val len = buf.size
        var pos = idx - delaySamples
        while (pos < 0f) pos += len
        val base = floor(pos).toInt()
        val i0 = base % len
        val i1 = (i0 + 1) % len
        val frac = pos - base
        return buf[i0] + (buf[i1] - buf[i0]) * frac
    }

    fun processStereo(
        left: FloatArray,
        right: FloatArray,
        count: Int,
        p: FlangerParams,
    ) {
        val baseSamples = 0.0015f * sampleRate
        val depthSamples = clamp((p.depthMs / 1000f) * sampleRate, 2f, delayL.size * 0.6f)
        val lfoInc = (2.0 * PI * clamp(p.rateHz, 0.05f, 5f)) / sampleRate
        val fb = clamp(p.feedback, -0.85f, 0.85f)
        val wet = clamp(p.mix, 0f, 1f)
        val len = delayL.size

        for (i in 0 until count) {
            val inL = left[i]
            val inR = right[i]
            val lfoL = ((sin(lfoPhase) + 1.0) * 0.5).toFloat()
            val lfoR = ((sin(lfoPhase + 0.6) + 1.0) * 0.5).toFloat()

            val delayedL = readFrac(delayL, baseSamples + lfoL * depthSamples)
            val delayedR = readFrac(delayR, baseSamples + lfoR * depthSamples)

            delayL[idx] = clamp(inL + delayedL * fb, -1.5f, 1.5f)
            delayR[idx] = clamp(inR + delayedR * fb, -1.5f, 1.5f)

            left[i] = clamp(inL * (1f - wet * 0.5f) + delayedL * wet, -1f, 1f)
            right[i] = clamp(inR * (1f - wet * 0.5f) + delayedR * wet, -1f, 1f)

            idx = (idx + 1) % len
            lfoPhase += lfoInc
            if (lfoPhase > PI * 2.0) lfoPhase -= PI * 2.0
        }
    }

    fun reset() {
        delayL.fill(0f)
        delayR.fill(0f)
        idx = 0
        lfoPhase = 0.0
    }
}

class PhaserProcessor(private val sampleRate: Int = 44100) {
    private val zmL = FloatArray(4)
    private val zmR = FloatArray(4)
    private var lastOutL = 0f
    private var lastOutR = 0f
    private var lfoPhase = 0.0

    fun processStereo(
        left: FloatArray,
        right: FloatArray,
        count: Int,
        p: PhaserParams,
    ) {
        val lfoInc = (2.0 * PI * clamp(p.rateHz, 0.05f, 6f)) / sampleRate
        val depth = clamp(p.depth, 0f, 1f)
        val fb = clamp(p.feedback, 0f, 0.85f)
        val wet = clamp(p.mix, 0f, 1f)
        val dry = 1f - wet * 0.35f

        for (i in 0 until count) {
            val lfo = ((sin(lfoPhase) + 1.0) * 0.5).toFloat()
            val sweepHz = 300f + lfo * depth * 3200f
            val d = (PI * sweepHz / sampleRate).toFloat()
            val a1 = (1f - d) / (1f + d)

            var yL = left[i] + lastOutL * fb
            var yR = right[i] + lastOutR * fb
            for (s in 0 until 4) {
                val tmpL = yL * -a1 + zmL[s]
                zmL[s] = tmpL * a1 + yL
                yL = tmpL

                val tmpR = yR * -a1 + zmR[s]
                zmR[s] = tmpR * a1 + yR
                yR = tmpR
            }
            lastOutL = clamp(yL, -1.5f, 1.5f)
            lastOutR = clamp(yR, -1.5f, 1.5f)

            left[i] = clamp(left[i] * dry + yL * wet, -1f, 1f)
            right[i] = clamp(right[i] * dry + yR * wet, -1f, 1f)

            lfoPhase += lfoInc
            if (lfoPhase > PI * 2.0) lfoPhase -= PI * 2.0
        }
    }

    fun reset() {
        zmL.fill(0f)
        zmR.fill(0f)
        lastOutL = 0f
        lastOutR = 0f
        lfoPhase = 0.0
    }
}

class DistortionProcessor {
    private val toneFilter = StereoBiquadFilter()

    fun configure(sampleRate: Int, p: DistortionParams) {
        toneFilter.configureLowPass(sampleRate, p.toneHz.toDouble(), 0.707)
    }

    fun processStereo(
        left: FloatArray,
        right: FloatArray,
        count: Int,
        p: DistortionParams,
    ) {
        val drive = clamp(p.drive, 1f, 20f).toDouble()
        val wet = clamp(p.mix, 0f, 1f)
        val dry = 1f - wet
        val normFactor = 1.0 / max(tanh(drive), 0.1)

        for (i in 0 until count) {
            val xL = left[i]
            val xR = right[i]
            val dL = xL * drive
            val dR = xR * drive

            val shapedL: Float
            val shapedR: Float
            when (p.mode) {
                DistortionMode.HARD_CLIP -> {
                    shapedL = clamp(dL, -0.85, 0.85).toFloat() / 0.85f
                    shapedR = clamp(dR, -0.85, 0.85).toFloat() / 0.85f
                }
                DistortionMode.SOFT_CLIP -> {
                    shapedL = (tanh(dL) * normFactor).toFloat()
                    shapedR = (tanh(dR) * normFactor).toFloat()
                }
                DistortionMode.OVERDRIVE -> {
                    shapedL = (tanh(dL + 0.18 * dL * dL) * normFactor).toFloat()
                    shapedR = (tanh(dR + 0.18 * dR * dR) * normFactor).toFloat()
                }
            }

            val tonedL = toneFilter.processLeft(shapedL)
            val tonedR = toneFilter.processRight(shapedR)

            left[i] = clamp(xL * dry + tonedL * wet, -1f, 1f)
            right[i] = clamp(xR * dry + tonedR * wet, -1f, 1f)
        }
    }

    fun reset() {
        toneFilter.reset()
    }
}

class CompressorProcessor(private val sampleRate: Int = 44100) {
    private var envelope = 0.0
    var currentGainReductionDb: Float = 0f
        private set

    fun processStereo(
        left: FloatArray,
        right: FloatArray,
        count: Int,
        p: CompressorParams,
    ) {
        val threshold = clamp(p.thresholdDb, -48f, 0f).toDouble()
        val ratio = clamp(p.ratio, 1f, 20f).toDouble()
        val attackCoeff = exp(-1.0 / (clamp(p.attackMs, 1f, 200f) * 0.001 * sampleRate))
        val releaseCoeff = exp(-1.0 / (clamp(p.releaseMs, 20f, 1000f) * 0.001 * sampleRate))
        val makeup = 10.0.pow(p.makeupGainDb / 20.0)
        var maxGr = 0.0

        for (i in 0 until count) {
            val peak = max(abs(left[i]), abs(right[i])).toDouble()
            envelope = if (peak > envelope) {
                attackCoeff * envelope + (1.0 - attackCoeff) * peak
            } else {
                releaseCoeff * envelope + (1.0 - releaseCoeff) * peak
            }

            val envDb = 20.0 * log10(max(envelope, 1e-6))
            var gainReductionDb = 0.0
            if (envDb > threshold) {
                val excess = envDb - threshold
                gainReductionDb = excess - excess / ratio
            }
            if (gainReductionDb > maxGr) maxGr = gainReductionDb

            val gain = 10.0.pow(-gainReductionDb / 20.0) * makeup
            left[i] = tanh(left[i] * gain).toFloat()
            right[i] = tanh(right[i] * gain).toFloat()
        }
        currentGainReductionDb = maxGr.toFloat()
    }

    fun reset() {
        envelope = 0.0
        currentGainReductionDb = 0f
    }
}

class LimiterProcessor(private val sampleRate: Int = 44100) {
    private var gain = 1.0

    fun processStereo(
        left: FloatArray,
        right: FloatArray,
        count: Int,
        p: LimiterParams,
    ) {
        val ceiling = 10.0.pow(clamp(p.ceilingDb, -12f, 0f) / 20.0)
        val releaseCoeff = exp(-1.0 / (clamp(p.releaseMs, 10f, 500f) * 0.001 * sampleRate))
        val ceilingF = ceiling.toFloat()

        for (i in 0 until count) {
            val peak = max(abs(left[i]), abs(right[i])).toDouble()
            val targetGain = if (peak > ceiling) ceiling / peak else 1.0
            gain = if (targetGain < gain) {
                targetGain
            } else {
                releaseCoeff * gain + (1.0 - releaseCoeff) * targetGain
            }
            left[i] = clamp((left[i] * gain).toFloat(), -ceilingF, ceilingF)
            right[i] = clamp((right[i] * gain).toFloat(), -ceilingF, ceilingF)
        }
    }

    fun reset() {
        gain = 1.0
    }
}
