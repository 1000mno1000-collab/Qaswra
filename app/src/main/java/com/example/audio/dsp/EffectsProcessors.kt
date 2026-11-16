package com.example.audio.dsp

import com.example.model.BassBoostParams
import com.example.model.ChorusParams
import com.example.model.CompressorParams
import com.example.model.DistortionParams
import com.example.model.EqualizerParams
import com.example.model.FlangerParams
import com.example.model.LimiterParams
import com.example.model.PhaserParams
import com.example.model.PitchShiftParams
import com.example.model.TrebleParams
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

class EqualizerProcessor(private val sampleRate: Int = 44100) {
    private val band60 = StereoBiquadFilter()
    private val band250 = StereoBiquadFilter()
    private val band1k = StereoBiquadFilter()
    private val band4k = StereoBiquadFilter()
    private val band12k = StereoBiquadFilter()

    private val bassShelf = StereoBiquadFilter()
    private val trebleShelf = StereoBiquadFilter()

    fun reset() {
        band60.reset()
        band250.reset()
        band1k.reset()
        band4k.reset()
        band12k.reset()
        bassShelf.reset()
        trebleShelf.reset()
    }

    fun processEq(left: FloatArray, right: FloatArray, numFrames: Int, params: EqualizerParams) {
        if (params.band60HzDb != 0f) {
            band60.configureLowShelf(sampleRate, 65f, 0.9f, params.band60HzDb)
            band60.processBlock(left, right, numFrames)
        }
        if (params.band250HzDb != 0f) {
            band250.configurePeaking(sampleRate, 250f, 1.1f, params.band250HzDb)
            band250.processBlock(left, right, numFrames)
        }
        if (params.band1kHzDb != 0f) {
            band1k.configurePeaking(sampleRate, 1000f, 1.1f, params.band1kHzDb)
            band1k.processBlock(left, right, numFrames)
        }
        if (params.band4kHzDb != 0f) {
            band4k.configurePeaking(sampleRate, 4000f, 1.1f, params.band4kHzDb)
            band4k.processBlock(left, right, numFrames)
        }
        if (params.band12kHzDb != 0f) {
            band12k.configureHighShelf(sampleRate, 11000f, 0.9f, params.band12kHzDb)
            band12k.processBlock(left, right, numFrames)
        }
    }

    fun processBassBoost(left: FloatArray, right: FloatArray, numFrames: Int, params: BassBoostParams) {
        if (params.gainDb <= 0.05f) return
        bassShelf.configureLowShelf(sampleRate, params.frequencyHz, 0.85f, params.gainDb)
        bassShelf.processBlock(left, right, numFrames)
    }

    fun processTreble(left: FloatArray, right: FloatArray, numFrames: Int, params: TrebleParams) {
        if (abs(params.gainDb) <= 0.05f) return
        trebleShelf.configureHighShelf(sampleRate, params.frequencyHz, 0.85f, params.gainDb)
        trebleShelf.processBlock(left, right, numFrames)
    }
}

class PitchShiftProcessor(private val sampleRate: Int = 44100) {
    // Dual-head Hann-windowed delay-line granular pitch shifter
    private val bufSize = 4096
    private val bufMask = bufSize - 1
    private val ringL = FloatArray(bufSize)
    private val ringR = FloatArray(bufSize)
    private var writePos = 0
    private var phase = 0.0
    private val grainLen = 1400.0

    fun reset() {
        ringL.fill(0f)
        ringR.fill(0f)
        writePos = 0
        phase = 0.0
    }

    fun processBlock(left: FloatArray, right: FloatArray, numFrames: Int, params: PitchShiftParams) {
        if (abs(params.semitones) < 0.05f) return
        val pitchRatio = 2.0.pow(params.semitones.coerceIn(-12f, 12f) / 12.0)
        val delta = (1.0 - pitchRatio) / grainLen

        for (i in 0 until numFrames) {
            ringL[writePos] = left[i]
            ringR[writePos] = right[i]

            val p1 = phase
            val p2 = (phase + 0.5) % 1.0

            val delay1 = p1 * grainLen + 4.0
            val delay2 = p2 * grainLen + 4.0

            val w1 = 0.5 * (1.0 - cos(2.0 * PI * p1))
            val w2 = 0.5 * (1.0 - cos(2.0 * PI * p2))
            val norm = (w1 + w2).coerceAtLeast(1e-6)

            val rPos1 = writePos - delay1
            val rPos2 = writePos - delay2

            val sL1 = readRing(ringL, rPos1)
            val sL2 = readRing(ringL, rPos2)
            val sR1 = readRing(ringR, rPos1)
            val sR2 = readRing(ringR, rPos2)

            left[i] = ((sL1 * w1 + sL2 * w2) / norm).toFloat()
            right[i] = ((sR1 * w1 + sR2 * w2) / norm).toFloat()

            phase += delta
            while (phase >= 1.0) phase -= 1.0
            while (phase < 0.0) phase += 1.0

            writePos = (writePos + 1) and bufMask
        }
    }

    private fun readRing(buf: FloatArray, pos: Double): Double {
        var p = pos
        while (p < 0.0) p += bufSize
        while (p >= bufSize) p -= bufSize
        val i0 = p.toInt() and bufMask
        val i1 = (i0 + 1) and bufMask
        val frac = p - i0
        return buf[i0] + (buf[i1] - buf[i0]) * frac
    }
}

class ChorusProcessor(private val sampleRate: Int = 44100) {
    private val maxDelay = (sampleRate * 0.06f).toInt()
    private val bufL = FloatArray(maxDelay)
    private val bufR = FloatArray(maxDelay)
    private var writePos = 0
    private var lfoPhase = 0.0

    fun reset() {
        bufL.fill(0f)
        bufR.fill(0f)
        writePos = 0
        lfoPhase = 0.0
    }

    fun processBlock(left: FloatArray, right: FloatArray, numFrames: Int, params: ChorusParams) {
        val mix = params.mix.coerceIn(0f, 1f)
        if (mix <= 0.005f) return
        val baseDelaySamples = sampleRate * 0.016f
        val depthSamples = params.depth.coerceIn(0f, 1f) * (sampleRate * 0.007f)
        val lfoInc = 2.0 * PI * params.rateHz.coerceIn(0.05f, 6f) / sampleRate

        for (i in 0 until numFrames) {
            val inL = left[i]
            val inR = right[i]
            bufL[writePos] = inL
            bufR[writePos] = inR

            val modL = (sin(lfoPhase) * 0.5 + 0.5).toFloat()
            val modR = (sin(lfoPhase + PI * 0.65) * 0.5 + 0.5).toFloat()

            val wetL = readLinear(bufL, writePos - (baseDelaySamples + modL * depthSamples))
            val wetR = readLinear(bufR, writePos - (baseDelaySamples + modR * depthSamples))

            left[i] = inL * (1f - mix * 0.4f) + wetL * mix
            right[i] = inR * (1f - mix * 0.4f) + wetR * mix

            lfoPhase += lfoInc
            if (lfoPhase > 2.0 * PI) lfoPhase -= 2.0 * PI
            writePos++
            if (writePos >= maxDelay) writePos = 0
        }
    }

    private fun readLinear(buf: FloatArray, pos: Float): Float {
        var p = pos
        while (p < 0f) p += maxDelay
        while (p >= maxDelay) p -= maxDelay
        val i0 = p.toInt()
        val i1 = if (i0 + 1 >= maxDelay) 0 else i0 + 1
        val frac = p - i0
        return buf[i0] + (buf[i1] - buf[i0]) * frac
    }
}

class FlangerProcessor(private val sampleRate: Int = 44100) {
    private val maxDelay = (sampleRate * 0.025f).toInt()
    private val bufL = FloatArray(maxDelay)
    private val bufR = FloatArray(maxDelay)
    private var writePos = 0
    private var lfoPhase = 0.0
    private var fbSampleL = 0f
    private var fbSampleR = 0f

    fun reset() {
        bufL.fill(0f)
        bufR.fill(0f)
        writePos = 0
        lfoPhase = 0.0
        fbSampleL = 0f
        fbSampleR = 0f
    }

    fun processBlock(left: FloatArray, right: FloatArray, numFrames: Int, params: FlangerParams) {
        val mix = params.mix.coerceIn(0f, 1f)
        if (mix <= 0.005f) return
        val minSamples = sampleRate * 0.0006f
        val maxModSamples = params.depth.coerceIn(0f, 1f) * (sampleRate * 0.0075f)
        val fb = params.feedback.coerceIn(0f, 0.85f)
        val lfoInc = 2.0 * PI * params.rateHz.coerceIn(0.05f, 4f) / sampleRate

        for (i in 0 until numFrames) {
            val inL = left[i]
            val inR = right[i]
            bufL[writePos] = (inL + fbSampleL * fb).coerceIn(-1.5f, 1.5f)
            bufR[writePos] = (inR + fbSampleR * fb).coerceIn(-1.5f, 1.5f)

            val lfoL = (sin(lfoPhase) * 0.5 + 0.5).toFloat()
            val lfoR = (sin(lfoPhase + 0.4) * 0.5 + 0.5).toFloat()

            fbSampleL = readLinear(bufL, writePos - (minSamples + lfoL * maxModSamples))
            fbSampleR = readLinear(bufR, writePos - (minSamples + lfoR * maxModSamples))

            left[i] = inL * (1f - mix * 0.5f) + fbSampleL * mix
            right[i] = inR * (1f - mix * 0.5f) + fbSampleR * mix

            lfoPhase += lfoInc
            if (lfoPhase > 2.0 * PI) lfoPhase -= 2.0 * PI
            writePos++
            if (writePos >= maxDelay) writePos = 0
        }
    }

    private fun readLinear(buf: FloatArray, pos: Float): Float {
        var p = pos
        while (p < 0f) p += maxDelay
        while (p >= maxDelay) p -= maxDelay
        val i0 = p.toInt()
        val i1 = if (i0 + 1 >= maxDelay) 0 else i0 + 1
        val frac = p - i0
        return buf[i0] + (buf[i1] - buf[i0]) * frac
    }
}

class PhaserProcessor(private val sampleRate: Int = 44100) {
    private val stagesL = FloatArray(4)
    private val stagesR = FloatArray(4)
    private var fbL = 0f
    private var fbR = 0f
    private var lfoPhase = 0.0

    fun reset() {
        stagesL.fill(0f)
        stagesR.fill(0f)
        fbL = 0f
        fbR = 0f
        lfoPhase = 0.0
    }

    fun processBlock(left: FloatArray, right: FloatArray, numFrames: Int, params: PhaserParams) {
        val mix = params.mix.coerceIn(0f, 1f)
        if (mix <= 0.005f) return
        val fb = params.feedback.coerceIn(0f, 0.85f)
        val depth = params.depth.coerceIn(0f, 1f)
        val lfoInc = 2.0 * PI * params.rateHz.coerceIn(0.05f, 5f) / sampleRate

        for (i in 0 until numFrames) {
            val mod = (sin(lfoPhase) * 0.5 + 0.5).toFloat()
            val freq = 320f + mod * depth * 2800f
            val tanVal = tan(PI * freq / sampleRate).toFloat()
            val a1 = (tanVal - 1f) / (tanVal + 1f)

            val inL = left[i]
            var yL = inL + fbL * fb
            for (s in 0 until 4) {
                val out = a1 * yL + stagesL[s]
                stagesL[s] = yL - a1 * out
                yL = out
            }
            fbL = yL

            val inR = right[i]
            var yR = inR + fbR * fb
            for (s in 0 until 4) {
                val out = a1 * yR + stagesR[s]
                stagesR[s] = yR - a1 * out
                yR = out
            }
            fbR = yR

            left[i] = inL * (1f - mix * 0.5f) + yL * mix * 0.7f
            right[i] = inR * (1f - mix * 0.5f) + yR * mix * 0.7f

            lfoPhase += lfoInc
            if (lfoPhase > 2.0 * PI) lfoPhase -= 2.0 * PI
        }
    }
}

class DistortionProcessor(private val sampleRate: Int = 44100) {
    private val toneFilter = StereoBiquadFilter()

    fun reset() {
        toneFilter.reset()
    }

    fun processBlock(left: FloatArray, right: FloatArray, numFrames: Int, params: DistortionParams) {
        val mix = params.mix.coerceIn(0f, 1f)
        if (mix <= 0.005f) return
        val driveGain = 1f + params.drive.coerceIn(0f, 1f) * 18f
        val toneHz = 1200f + params.tone.coerceIn(0f, 1f) * 12000f
        toneFilter.configureLowPass(sampleRate, toneHz, 0.707f)

        val wetL = FloatArray(numFrames)
        val wetR = FloatArray(numFrames)
        for (i in 0 until numFrames) {
            wetL[i] = tanh((left[i] * driveGain).toDouble()).toFloat()
            wetR[i] = tanh((right[i] * driveGain).toDouble()).toFloat()
        }
        toneFilter.processBlock(wetL, wetR, numFrames)

        for (i in 0 until numFrames) {
            left[i] = left[i] * (1f - mix) + wetL[i] * mix
            right[i] = right[i] * (1f - mix) + wetR[i] * mix
        }
    }
}

class CompressorProcessor(private val sampleRate: Int = 44100) {
    private var envDb = -96f
    var lastGainReductionDb: Float = 0f
        private set

    fun reset() {
        envDb = -96f
        lastGainReductionDb = 0f
    }

    fun processBlock(left: FloatArray, right: FloatArray, numFrames: Int, params: CompressorParams) {
        val thresholdDb = params.thresholdDb.coerceIn(-45f, 0f)
        val ratio = params.ratio.coerceIn(1f, 20f)
        val attackCoef = exp(-1.0 / ((params.attackMs.coerceAtLeast(1f) / 1000.0) * sampleRate)).toFloat()
        val releaseCoef = exp(-1.0 / ((params.releaseMs.coerceAtLeast(10f) / 1000.0) * sampleRate)).toFloat()
        val makeupGain = 10.0.pow(params.makeupDb.coerceIn(0f, 24f) / 20.0).toFloat()

        var maxGr = 0f
        for (i in 0 until numFrames) {
            val peak = max(abs(left[i]), abs(right[i])).coerceAtLeast(1e-6f)
            val inputDb = 20f * log10(peak)
            val coef = if (inputDb > envDb) attackCoef else releaseCoef
            envDb = coef * envDb + (1f - coef) * inputDb

            val overDb = (envDb - thresholdDb).coerceAtLeast(0f)
            val grDb = overDb * (1f - 1f / ratio)
            if (grDb > maxGr) maxGr = grDb
            val gain = 10.0.pow(-grDb / 20.0).toFloat() * makeupGain

            left[i] *= gain
            right[i] *= gain
        }
        lastGainReductionDb = maxGr
    }
}

class LimiterProcessor(private val sampleRate: Int = 44100) {
    private var gainEnv = 1f

    fun reset() {
        gainEnv = 1f
    }

    fun processBlock(left: FloatArray, right: FloatArray, numFrames: Int, params: LimiterParams) {
        val ceiling = 10.0.pow(params.ceilingDb.coerceIn(-12f, -0.1f) / 20.0).toFloat()
        val releaseCoef = exp(-1.0 / ((params.releaseMs.coerceAtLeast(10f) / 1000.0) * sampleRate)).toFloat()

        for (i in 0 until numFrames) {
            val peak = max(abs(left[i]), abs(right[i]))
            val targetGain = if (peak > ceiling && peak > 1e-6f) ceiling / peak else 1f
            gainEnv = if (targetGain < gainEnv) {
                targetGain // instant attack brickwall
            } else {
                releaseCoef * gainEnv + (1f - releaseCoef) * targetGain
            }
            left[i] = (left[i] * gainEnv).coerceIn(-ceiling, ceiling)
            right[i] = (right[i] * gainEnv).coerceIn(-ceiling, ceiling)
        }
    }
}
