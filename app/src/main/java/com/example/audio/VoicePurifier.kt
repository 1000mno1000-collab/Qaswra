package com.example.audio

import com.example.model.NoiseReductionParams
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * In-place iterative radix-2 Cooley-Tukey FFT.
 */
fun radix2Fft(
    real: DoubleArray,
    imag: DoubleArray,
    n: Int,
    inverse: Boolean,
) {
    var j = 0
    for (i in 1 until n) {
        var bit = n ushr 1
        while ((j and bit) != 0) {
            j = j xor bit
            bit = bit ushr 1
        }
        j = j xor bit
        if (i < j) {
            val tr = real[i]
            real[i] = real[j]
            real[j] = tr
            val ti = imag[i]
            imag[i] = imag[j]
            imag[j] = ti
        }
    }

    val sign = if (inverse) 1.0 else -1.0
    var len = 2
    while (len <= n) {
        val ang = (sign * 2.0 * PI) / len
        val wLenCos = cos(ang)
        val wLenSin = sin(ang)
        val half = len ushr 1
        var i = 0
        while (i < n) {
            var wCos = 1.0
            var wSin = 0.0
            for (k in 0 until half) {
                val uR = real[i + k]
                val uI = imag[i + k]
                val idx = i + k + half
                val vR = real[idx] * wCos - imag[idx] * wSin
                val vI = real[idx] * wSin + imag[idx] * wCos
                real[i + k] = uR + vR
                imag[i + k] = uI + vI
                real[idx] = uR - vR
                imag[idx] = uI - vI
                val nextWCos = wCos * wLenCos - wSin * wLenSin
                wSin = wCos * wLenSin + wSin * wLenCos
                wCos = nextWCos
            }
            i += len
        }
        len = len shl 1
    }

    if (inverse) {
        val invN = 1.0 / n
        for (i in 0 until n) {
            real[i] *= invN
            imag[i] *= invN
        }
    }
}

/**
 * 512-pt sqrt-Hann 50%-overlap STFT Wiener spectral denoiser with adaptive minima noise tracking.
 */
class SpectralVoiceCleaner {
    companion object {
        private const val FFT_SIZE = 512
        private const val HOP_SIZE = 256
        private const val BINS = FFT_SIZE / 2 + 1
    }

    private val sqrtHann = FloatArray(FFT_SIZE) { i ->
        val hann = 0.5 * (1.0 - cos((2.0 * PI * i) / (FFT_SIZE - 1)))
        sqrt(max(hann, 0.0)).toFloat()
    }
    private val inRingL = FloatArray(FFT_SIZE)
    private val inRingR = FloatArray(FFT_SIZE)
    private val olaL = FloatArray(FFT_SIZE)
    private val olaR = FloatArray(FFT_SIZE)
    private val outRingL = FloatArray(HOP_SIZE)
    private val outRingR = FloatArray(HOP_SIZE)
    private var hopFill = 0

    private val noisePsd = FloatArray(BINS) { 1e-5f }
    private val prevGain = FloatArray(BINS) { 1f }
    private var framesSeen = 0
    var currentSuppressionDb: Float = 0f
        private set

    private val fftReal = DoubleArray(FFT_SIZE)
    private val fftImag = DoubleArray(FFT_SIZE)
    private val fftRealR = DoubleArray(FFT_SIZE)
    private val fftImagR = DoubleArray(FFT_SIZE)

    fun reset() {
        inRingL.fill(0f)
        inRingR.fill(0f)
        olaL.fill(0f)
        olaR.fill(0f)
        outRingL.fill(0f)
        outRingR.fill(0f)
        hopFill = 0
        noisePsd.fill(1e-5f)
        prevGain.fill(1f)
        framesSeen = 0
        currentSuppressionDb = 0f
    }

    fun processStereo(
        left: FloatArray,
        right: FloatArray,
        count: Int,
        strength: Float,
        floorGain: Float,
    ) {
        val clampedStrength = clamp(strength, 0f, 1f)
        for (i in 0 until count) {
            val outL = outRingL[hopFill]
            val outR = outRingR[hopFill]

            inRingL[HOP_SIZE + hopFill] = left[i]
            inRingR[HOP_SIZE + hopFill] = right[i]

            left[i] = outL
            right[i] = outR

            hopFill++
            if (hopFill >= HOP_SIZE) {
                processFrame(clampedStrength, floorGain)
                System.arraycopy(inRingL, HOP_SIZE, inRingL, 0, FFT_SIZE - HOP_SIZE)
                System.arraycopy(inRingR, HOP_SIZE, inRingR, 0, FFT_SIZE - HOP_SIZE)
                hopFill = 0
            }
        }
    }

    private fun processFrame(strength: Float, floorGain: Float) {
        for (i in 0 until FFT_SIZE) {
            val w = sqrtHann[i].toDouble()
            fftReal[i] = inRingL[i] * w
            fftImag[i] = 0.0
            fftRealR[i] = inRingR[i] * w
            fftImagR[i] = 0.0
        }

        radix2Fft(fftReal, fftImag, FFT_SIZE, false)
        radix2Fft(fftRealR, fftImagR, FFT_SIZE, false)

        framesSeen++
        val spectralFloor = max(floorGain, 0.03f).toDouble()
        val overSub = 1.0 + strength * 2.2
        var avgGainSum = 0.0
        var totalFrameEnergy = 0.0

        for (k in 0 until BINS) {
            val magSqL = fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]
            val magSqR = fftRealR[k] * fftRealR[k] + fftImagR[k] * fftImagR[k]
            totalFrameEnergy += 0.5 * (magSqL + magSqR)
        }

        val avgPower = totalFrameEnergy / BINS
        val isLikelyPause = framesSeen <= 8 || avgPower < 0.08

        for (k in 0 until BINS) {
            val magSqL = fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]
            val magSqR = fftRealR[k] * fftRealR[k] + fftImagR[k] * fftImagR[k]
            val power = 0.5 * (magSqL + magSqR)

            val prevNoise = noisePsd[k].toDouble()
            noisePsd[k] = when {
                framesSeen <= 4 -> (prevNoise * 0.5 + power * 0.5).toFloat()
                power < prevNoise -> (prevNoise * 0.8 + power * 0.2).toFloat()
                isLikelyPause && power < prevNoise * 3.5 -> (prevNoise * 0.965 + power * 0.035).toFloat()
                else -> (prevNoise * 0.9992 + power * 0.0008).toFloat()
            }

            val noiseEst = max(noisePsd[k] * overSub, 1e-9)
            val snrPost = power / noiseEst
            val wiener = clamp((snrPost - 1.0) / (snrPost + 0.35), spectralFloor, 1.0)

            val alpha = if (wiener > prevGain[k]) 0.55 else 0.78
            val smoothG = alpha * prevGain[k] + (1.0 - alpha) * wiener
            prevGain[k] = smoothG.toFloat()

            val finalG = 1.0 - strength + strength * smoothG
            avgGainSum += finalG

            fftReal[k] *= finalG
            fftImag[k] *= finalG
            fftRealR[k] *= finalG
            fftImagR[k] *= finalG

            if (k > 0 && k < FFT_SIZE / 2) {
                val conjIdx = FFT_SIZE - k
                fftReal[conjIdx] *= finalG
                fftImag[conjIdx] *= finalG
                fftRealR[conjIdx] *= finalG
                fftImagR[conjIdx] *= finalG
            }
        }

        val avgGain = clamp(avgGainSum / BINS, 0.02, 1.0)
        val db = -20.0 * log10(avgGain)
        currentSuppressionDb = clamp(db.toFloat(), 0f, 32f)

        radix2Fft(fftReal, fftImag, FFT_SIZE, true)
        radix2Fft(fftRealR, fftImagR, FFT_SIZE, true)

        for (i in 0 until FFT_SIZE) {
            val w = sqrtHann[i]
            olaL[i] += (fftReal[i] * w).toFloat()
            olaR[i] += (fftRealR[i] * w).toFloat()
        }

        for (i in 0 until HOP_SIZE) {
            outRingL[i] = clamp(olaL[i], -1f, 1f)
            outRingR[i] = clamp(olaR[i], -1f, 1f)
        }

        System.arraycopy(olaL, HOP_SIZE, olaL, 0, FFT_SIZE - HOP_SIZE)
        System.arraycopy(olaR, HOP_SIZE, olaR, 0, FFT_SIZE - HOP_SIZE)
        olaL.fill(0f, HOP_SIZE, FFT_SIZE)
        olaR.fill(0f, HOP_SIZE, FFT_SIZE)
    }
}

class NoiseReductionProcessor(private val sampleRate: Int = 44100) {
    private val hpf = StereoBiquadFilter()
    private val lpf = StereoBiquadFilter()
    private val humNotch50 = StereoBiquadFilter()
    private val sibilanceBand = StereoBiquadFilter()
    private val clarityShelf = StereoBiquadFilter()
    private val spectralCleaner = SpectralVoiceCleaner()

    private var gateGain = 1.0
    private var env = 0.0
    private var deEssEnv = 0.0
    private var lastConfigKey = ""
    private var lpfEnabled = false

    var currentSuppressionDb: Float = 0f
        private set

    fun configure(params: NoiseReductionParams) {
        val key = "$sampleRate|${params.highPassHz}|${params.lowPassHz}|${params.clarityBoostDb}"
        if (key == lastConfigKey) return
        lastConfigKey = key

        hpf.configureHighPass(sampleRate, clamp(params.highPassHz, 20f, 450f).toDouble(), 0.707)
        val clampedLpf = clamp(params.lowPassHz, 2200f, 16000f)
        lpfEnabled = clampedLpf < 15800f
        if (lpfEnabled) {
            lpf.configureLowPass(sampleRate, clampedLpf.toDouble(), 0.707)
        }
        humNotch50.configurePeaking(sampleRate, 60.0, 4.5, -9.0)
        sibilanceBand.configureBandPass(sampleRate, 6400.0, 1.4)
        clarityShelf.configureHighShelf(sampleRate, 3400.0, 0.7, clamp(params.clarityBoostDb, -3f, 12f).toDouble())
    }

    fun processStereo(
        left: FloatArray,
        right: FloatArray,
        count: Int,
        params: NoiseReductionParams,
    ) {
        configure(params)

        // 1. Annoying frequency filter (subsonic HPF, AC 60Hz notch, harsh hiss LPF)
        hpf.processStereo(left, right, count)
        humNotch50.processStereo(left, right, count)
        if (lpfEnabled) {
            lpf.processStereo(left, right, count)
        }

        // 2. STFT Wiener spectral denoiser
        val gateFloor = 10.0.pow(-clamp(params.reductionDb, 0f, 36f) / 20.0).toFloat()
        if (params.spectralCleanerEnabled && params.spectralStrength > 0.02f) {
            spectralCleaner.processStereo(
                left = left,
                right = right,
                count = count,
                strength = params.spectralStrength,
                floorGain = max(gateFloor, 0.04f),
            )
        }

        // 3. Envelope expander / soft downward gate + de-esser + clarity
        val thresholdLin = 10.0.pow(clamp(params.thresholdDb, -80f, -10f) / 20.0)
        val attack = 0.02
        val release = 0.0008
        val deEssStrength = clamp(params.deEssAmount, 0f, 1f).toDouble()
        val gateFloorD = gateFloor.toDouble()
        var maxGateDb = 0.0

        for (i in 0 until count) {
            var l = left[i].toDouble()
            var r = right[i].toDouble()

            val peak = max(abs(l), abs(r))
            env = if (peak > env) env + (peak - env) * attack else env + (peak - env) * release

            val targetGain = if (env < thresholdLin) {
                max(gateFloorD, (env / max(thresholdLin, 1e-6)) * (1.0 - gateFloorD) + gateFloorD)
            } else {
                1.0
            }

            val rate = if (targetGain > gateGain) 0.015 else 0.002
            gateGain += (targetGain - gateGain) * rate
            l *= gateGain
            r *= gateGain

            val gDb = -20.0 * log10(max(gateGain, 0.01))
            if (gDb > maxGateDb) maxGateDb = gDb

            // 4. Dynamic De-esser
            if (deEssStrength > 0.01) {
                val sibL = sibilanceBand.processLeft(l.toFloat()).toDouble()
                val sibR = sibilanceBand.processRight(r.toFloat()).toDouble()
                val sibEnergy = max(abs(sibL), abs(sibR))
                deEssEnv = if (sibEnergy > deEssEnv) {
                    deEssEnv * 0.7 + sibEnergy * 0.3
                } else {
                    deEssEnv * 0.992 + sibEnergy * 0.008
                }
                if (deEssEnv > 0.045) {
                    val excess = (deEssEnv - 0.045) * 8.0 * deEssStrength
                    val sibCut = clamp(excess, 0.0, 0.65)
                    l -= sibL * sibCut
                    r -= sibR * sibCut
                }
            }

            // 5. Vocal clarity presence shelf
            if (abs(params.clarityBoostDb) > 0.05f) {
                l = clarityShelf.processLeft(l.toFloat()).toDouble()
                r = clarityShelf.processRight(r.toFloat()).toDouble()
            }

            left[i] = tanh(l).toFloat()
            right[i] = tanh(r).toFloat()
        }

        val spectralDb = if (params.spectralCleanerEnabled) spectralCleaner.currentSuppressionDb else 0f
        currentSuppressionDb = clamp(max(spectralDb, (maxGateDb * 0.65).toFloat()), 0f, 36f)
    }

    fun reset() {
        hpf.reset()
        lpf.reset()
        humNotch50.reset()
        sibilanceBand.reset()
        clarityShelf.reset()
        spectralCleaner.reset()
        gateGain = 1.0
        env = 0.0
        deEssEnv = 0.0
        currentSuppressionDb = 0f
    }
}

suspend fun purifyBufferOffline(
    source: StereoAudioBuffer,
    params: NoiseReductionParams,
    onProgress: ((Float) -> Unit)? = null,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val n = source.left.size
    val outL = source.left.copyOf()
    val outR = source.right.copyOf()
    val proc = NoiseReductionProcessor(source.sampleRate)
    proc.configure(params)

    val blockSize = 4096
    val bL = FloatArray(blockSize)
    val bR = FloatArray(blockSize)

    var offset = 0
    while (offset < n) {
        val count = min(blockSize, n - offset)
        System.arraycopy(outL, offset, bL, 0, count)
        System.arraycopy(outR, offset, bR, 0, count)
        proc.processStereo(bL, bR, count, params)
        System.arraycopy(bL, 0, outL, offset, count)
        System.arraycopy(bR, 0, outR, offset, count)
        onProgress?.invoke(min(1f, (offset + count).toFloat() / max(1, n)))
        if (offset % (blockSize * 8) == 0) yield()
        offset += blockSize
    }
    StereoAudioBuffer(outL, outR, source.sampleRate)
}
