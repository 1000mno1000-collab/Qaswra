package com.example.audio.dsp

import com.example.audio.io.StereoAudioBuffer
import com.example.model.NoiseReductionParams
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class VoicePurifyPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val params: NoiseReductionParams
) {
    SMART_BALANCED(
        id = "smart_balanced",
        title = "تصفية ذكية",
        subtitle = "توازن مثالي لعزل الضوضاء والحفاظ على طبيعة الصوت",
        params = NoiseReductionParams(
            thresholdDb = -44f,
            amount = 0.68f,
            highPassHz = 85f,
            vocalClarity = 0.45f,
            deEssAmount = 0.30f,
            spectralClean = true
        )
    ),
    DEEP_NOISE_ISOLATION(
        id = "deep_isolation",
        title = "عزل قوي للضوضاء",
        subtitle = "إزالة ضجيج المراوح والهواء والشارع بفعالية عالية",
        params = NoiseReductionParams(
            thresholdDb = -36f,
            amount = 0.92f,
            highPassHz = 115f,
            vocalClarity = 0.55f,
            deEssAmount = 0.42f,
            spectralClean = true
        )
    ),
    PODCAST_CLARITY(
        id = "podcast_clarity",
        title = "صفاء البودكاست",
        subtitle = "حضور إذاعي دافئ ووضوح حاد للمخارج والكلمات",
        params = NoiseReductionParams(
            thresholdDb = -46f,
            amount = 0.62f,
            highPassHz = 90f,
            vocalClarity = 0.78f,
            deEssAmount = 0.45f,
            spectralClean = true
        )
    ),
    GENTLE_ACOUSTIC(
        id = "gentle_acoustic",
        title = "تنظيف لطيف",
        subtitle = "تخفيف الهمس الخفيف دون المساس بالتفاصيل الدقيقة",
        params = NoiseReductionParams(
            thresholdDb = -52f,
            amount = 0.42f,
            highPassHz = 65f,
            vocalClarity = 0.25f,
            deEssAmount = 0.20f,
            spectralClean = true
        )
    )
}

/**
 * 512-point sqrt-Hann WOLA Radix-2 STFT Spectral Wiener Noise Suppressor.
 * Guarantees exact COLA unity reconstruction when gain = 1 and suppresses broadband
 * hiss, fan noise, and room hum without metallic "musical noise" chirps.
 */
internal class SpectralVoiceCleaner(private val sampleRate: Int = 44100) {

    companion object {
        const val FFT_SIZE = 512
        const val HOP_SIZE = 256
        const val BINS = FFT_SIZE / 2 + 1 // 257 bins
    }

    private val sqrtHann = FloatArray(FFT_SIZE) { n ->
        sqrt(0.5 * (1.0 - cos(2.0 * PI * n / FFT_SIZE))).toFloat()
    }

    private val bitReverse = IntArray(FFT_SIZE).apply {
        var j = 0
        for (i in 0 until FFT_SIZE) {
            this[i] = j
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
        }
    }

    private val cosTable = DoubleArray(FFT_SIZE / 2) { k -> cos(-2.0 * PI * k / FFT_SIZE) }
    private val sinTable = DoubleArray(FFT_SIZE / 2) { k -> sin(-2.0 * PI * k / FFT_SIZE) }

    // Input & overlap-add buffers
    private val inBufL = FloatArray(FFT_SIZE)
    private val inBufR = FloatArray(FFT_SIZE)
    private val outOverlapL = FloatArray(FFT_SIZE)
    private val outOverlapR = FloatArray(FFT_SIZE)
    private val outReadyL = FloatArray(HOP_SIZE)
    private val outReadyR = FloatArray(HOP_SIZE)
    private var hopCursor = 0

    // Pre-allocated FFT work buffers (zero GC on audio thread)
    private val fftReL = DoubleArray(FFT_SIZE)
    private val fftImL = DoubleArray(FFT_SIZE)
    private val fftReR = DoubleArray(FFT_SIZE)
    private val fftImR = DoubleArray(FFT_SIZE)

    // Spectral noise tracking & smoothed gains
    private val noisePower = FloatArray(BINS) { 1e-7f }
    private val framePower = FloatArray(BINS)
    private val rawGain = FloatArray(BINS) { 1f }
    private val smoothGain = FloatArray(BINS) { 1f }
    private val prevGain = FloatArray(BINS) { 1f }

    private var noiseProfileLocked = false
    private var minFrameEnergy = 1e-6f
    private var framesSeen = 0

    var lastSuppressionDb: Float = 0f
        private set

    fun reset() {
        inBufL.fill(0f)
        inBufR.fill(0f)
        outOverlapL.fill(0f)
        outOverlapR.fill(0f)
        outReadyL.fill(0f)
        outReadyR.fill(0f)
        hopCursor = 0
        noisePower.fill(1e-7f)
        framePower.fill(0f)
        rawGain.fill(1f)
        smoothGain.fill(1f)
        prevGain.fill(1f)
        noiseProfileLocked = false
        minFrameEnergy = 1e-6f
        framesSeen = 0
        lastSuppressionDb = 0f
    }

    /**
     * Scans an audio buffer for its quietest non-silent windows to build an accurate
     * spectral noise fingerprint before offline purification.
     */
    fun primeNoiseProfile(source: StereoAudioBuffer) {
        reset()
        val total = source.frameCount
        if (total < FFT_SIZE) return

        val candidateIndices = mutableListOf<Pair<Float, Int>>()
        var start = 0
        while (start + FFT_SIZE <= total) {
            var sumSq = 0.0
            for (n in 0 until FFT_SIZE) {
                val m = (source.left[start + n] + source.right[start + n]) * 0.5f
                sumSq += m * m
            }
            val rms = sqrt(sumSq / FFT_SIZE).toFloat()
            if (rms > 2e-5f) {
                candidateIndices.add(rms to start)
            }
            start += HOP_SIZE
        }
        if (candidateIndices.isEmpty()) return

        candidateIndices.sortBy { it.first }
        val takeCount = (candidateIndices.size / 8).coerceIn(1, min(20, candidateIndices.size))

        val accumPower = DoubleArray(BINS)
        for (c in 0 until takeCount) {
            val offset = candidateIndices[c].second
            for (n in 0 until FFT_SIZE) {
                val m = ((source.left[offset + n] + source.right[offset + n]) * 0.5f) * sqrtHann[n]
                fftReL[n] = m.toDouble()
                fftImL[n] = 0.0
            }
            fft512(fftReL, fftImL, inverse = false)
            for (k in 0 until BINS) {
                accumPower[k] += fftReL[k] * fftReL[k] + fftImL[k] * fftImL[k]
            }
        }

        // Slightly safety-pad noise floor by 1.15x so transient room fluctuations are caught
        for (k in 0 until BINS) {
            noisePower[k] = ((accumPower[k] / takeCount) * 1.15).toFloat().coerceAtLeast(1e-8f)
        }
        noiseProfileLocked = true
    }

    fun processBlock(left: FloatArray, right: FloatArray, numFrames: Int, amount: Float, thresholdDb: Float) {
        val cleanAmount = amount.coerceIn(0f, 1f)
        if (cleanAmount <= 0.01f) {
            lastSuppressionDb = 0f
            return
        }

        for (i in 0 until numFrames) {
            val inL = left[i]
            val inR = right[i]

            // Output ready sample from previous OLA frame, feed new sample into second half of inBuf
            left[i] = outReadyL[hopCursor]
            right[i] = outReadyR[hopCursor]
            inBufL[HOP_SIZE + hopCursor] = inL
            inBufR[HOP_SIZE + hopCursor] = inR

            hopCursor++
            if (hopCursor >= HOP_SIZE) {
                processSpectralHop(cleanAmount, thresholdDb)
                hopCursor = 0
            }
        }
    }

    private fun processSpectralHop(amount: Float, thresholdDb: Float) {
        framesSeen++

        for (n in 0 until FFT_SIZE) {
            val w = sqrtHann[n].toDouble()
            fftReL[n] = inBufL[n] * w
            fftImL[n] = 0.0
            fftReR[n] = inBufR[n] * w
            fftImR[n] = 0.0
        }

        fft512(fftReL, fftImL, inverse = false)
        fft512(fftReR, fftImR, inverse = false)

        var totalFrameEnergy = 0f
        for (k in 0 until BINS) {
            val pL = (fftReL[k] * fftReL[k] + fftImL[k] * fftImL[k]).toFloat()
            val pR = (fftReR[k] * fftReR[k] + fftImR[k] * fftImR[k]).toFloat()
            val p = (pL + pR) * 0.5f
            framePower[k] = p
            totalFrameEnergy += p
        }
        val avgEnergy = totalFrameEnergy / BINS
        if (framesSeen <= 6) {
            minFrameEnergy = if (framesSeen == 1) avgEnergy else min(minFrameEnergy, avgEnergy)
        } else {
            minFrameEnergy = if (avgEnergy < minFrameEnergy) {
                minFrameEnergy * 0.6f + avgEnergy * 0.4f
            } else {
                minFrameEnergy * 0.996f + avgEnergy * 0.004f
            }
        }

        // Adaptive noise spectrum update (online mode when not offline-primed)
        val isLikelyBackground = avgEnergy < max(minFrameEnergy * 2.4f, 1e-6f)
        if (!noiseProfileLocked) {
            if (framesSeen <= 3 && avgEnergy > 1e-10f) {
                for (k in 0 until BINS) {
                    noisePower[k] = if (framesSeen == 1) {
                        max(framePower[k], 1e-8f)
                    } else {
                        min(noisePower[k], max(framePower[k], 1e-8f))
                    }
                }
            } else {
                val upRate = if (isLikelyBackground) 0.045f else 0.002f
                for (k in 0 until BINS) {
                    val p = max(framePower[k], 1e-8f)
                    noisePower[k] = if (p < noisePower[k]) {
                        noisePower[k] * 0.70f + p * 0.30f
                    } else {
                        noisePower[k] * (1f - upRate) + p * upRate
                    }
                }
            }
        }

        // Threshold bias allows user's gate/sensitivity slider to tighten or relax spectral cleaning
        val threshScale = 10.0.pow(((thresholdDb + 42f) / 28f).toDouble()).toFloat().coerceIn(0.35f, 3.2f)
        val alphaBase = 0.85f + amount * 2.65f
        val spectralFloor = (1f - amount * 0.91f).coerceIn(0.06f, 1f)
        val hzPerBin = sampleRate.toFloat() / FFT_SIZE

        for (k in 0 until BINS) {
            val freq = k * hzPerBin
            // Protect fundamental speech core (250..3200 Hz) gently, clean hiss (>3500 Hz) & hum (<220 Hz) more firmly
            val bandFactor = when {
                freq < 220f -> 1.15f
                freq in 250f..3400f -> 0.88f
                else -> 1.25f
            }
            val nEst = noisePower[k] * threshScale
            val p = framePower[k]
            val overSub = alphaBase * bandFactor
            val g = if (p <= 1e-9f) {
                spectralFloor
            } else {
                val cleanEst = (p - overSub * nEst).coerceAtLeast(0f)
                (cleanEst / p).coerceIn(spectralFloor, 1f)
            }
            rawGain[k] = g
        }

        // Frequency-axis smoothing [0.18, 0.64, 0.18] eliminates musical noise peaks
        var avgGainSum = 0f
        for (k in 0 until BINS) {
            val leftG = rawGain[max(0, k - 1)]
            val rightG = rawGain[min(BINS - 1, k + 1)]
            val freqSmoothed = 0.18f * leftG + 0.64f * rawGain[k] + 0.18f * rightG

            // Asymmetric temporal smoothing: fast open for consonants, gentle release for natural tails
            val tCoef = if (freqSmoothed > prevGain[k]) 0.42f else 0.78f
            val finalG = (tCoef * prevGain[k] + (1f - tCoef) * freqSmoothed).coerceIn(spectralFloor, 1f)
            prevGain[k] = finalG
            smoothGain[k] = finalG
            avgGainSum += finalG
        }

        val meanGain = (avgGainSum / BINS).coerceIn(0.05f, 1f)
        lastSuppressionDb = (-20f * log10(meanGain)).coerceIn(0f, 26f)

        // Apply symmetric Hermitian real gain to L & R spectra
        for (k in 0 until BINS) {
            val g = smoothGain[k].toDouble()
            fftReL[k] *= g
            fftImL[k] *= g
            fftReR[k] *= g
            fftImR[k] *= g
            if (k in 1 until (FFT_SIZE / 2)) {
                val mirror = FFT_SIZE - k
                fftReL[mirror] *= g
                fftImL[mirror] *= g
                fftReR[mirror] *= g
                fftImR[mirror] *= g
            }
        }

        fft512(fftReL, fftImL, inverse = true)
        fft512(fftReR, fftImR, inverse = true)

        // Synthesis windowing + Overlap-Add
        for (n in 0 until FFT_SIZE) {
            val w = sqrtHann[n]
            outOverlapL[n] += (fftReL[n] * w).toFloat()
            outOverlapR[n] += (fftReR[n] * w).toFloat()
        }

        // Emit first HOP_SIZE samples, shift overlap buffer left by HOP_SIZE
        System.arraycopy(outOverlapL, 0, outReadyL, 0, HOP_SIZE)
        System.arraycopy(outOverlapR, 0, outReadyR, 0, HOP_SIZE)
        System.arraycopy(outOverlapL, HOP_SIZE, outOverlapL, 0, HOP_SIZE)
        System.arraycopy(outOverlapR, HOP_SIZE, outOverlapR, 0, HOP_SIZE)
        outOverlapL.fill(0f, HOP_SIZE, FFT_SIZE)
        outOverlapR.fill(0f, HOP_SIZE, FFT_SIZE)

        // Shift input buffer left by HOP_SIZE
        System.arraycopy(inBufL, HOP_SIZE, inBufL, 0, HOP_SIZE)
        System.arraycopy(inBufR, HOP_SIZE, inBufR, 0, HOP_SIZE)
    }

    private fun fft512(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until FFT_SIZE) {
            val j = bitReverse[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var halfLen = 1
        while (halfLen < FFT_SIZE) {
            val len = halfLen shl 1
            val step = FFT_SIZE / len
            var start = 0
            while (start < FFT_SIZE) {
                var tableIdx = 0
                for (k in 0 until halfLen) {
                    val wr = cosTable[tableIdx]
                    val wi = if (inverse) -sinTable[tableIdx] else sinTable[tableIdx]
                    val idx1 = start + k
                    val idx2 = idx1 + halfLen
                    val tr = wr * re[idx2] - wi * im[idx2]
                    val ti = wr * im[idx2] + wi * re[idx2]
                    re[idx2] = re[idx1] - tr
                    im[idx2] = im[idx1] - ti
                    re[idx1] += tr
                    im[idx1] += ti
                    tableIdx += step
                }
                start += len
            }
            halfLen = len
        }

        if (inverse) {
            val invN = 1.0 / FFT_SIZE
            for (i in 0 until FFT_SIZE) {
                re[i] *= invN
                im[i] *= invN
            }
        }
    }
}

/**
 * Complete 5-stage Studio Voice Purifier & Noise Cleaner:
 * 1. DC Offset + 24 dB/oct Sub-Bass Rumble & Wind High-Pass Filter
 * 2. Adaptive 512-pt STFT Wiener Spectral Noise Suppressor
 * 3. Hysteresis Soft Noise Gate with Hold Time (prevents clipped syllable tails)
 * 4. Sidechain 6.5 kHz Dynamic De-Esser (tames harsh sibilance without dulling tone)
 * 5. Vocal Clarity & Presence Shaper (cuts boxy mud @ 270Hz, lifts presence @ 3.2kHz & air @ 10kHz)
 */
class NoiseReductionProcessor(private val sampleRate: Int = 44100) {

    // Stage 1: DC blocker + 2 cascaded 2nd-order HPFs
    private var dcPrevInL = 0f
    private var dcPrevOutL = 0f
    private var dcPrevInR = 0f
    private var dcPrevOutR = 0f
    private val hpfStage1 = StereoBiquadFilter()
    private val hpfStage2 = StereoBiquadFilter()

    // Stage 2: Adaptive Spectral Wiener Cleaner
    internal val spectralCleaner = SpectralVoiceCleaner(sampleRate)

    // Stage 3: Smooth dynamic downward expander + hysteresis noise gate
    private var env = 0f
    private var currentGateGain = 1f
    private var holdSamples = 0
    private val holdDurationSamples = (0.055 * sampleRate).toInt()
    private val attackCoef = exp(-1.0 / (0.003 * sampleRate)).toFloat()
    private val releaseCoef = exp(-1.0 / (0.110 * sampleRate)).toFloat()
    private val gainAttackCoef = exp(-1.0 / (0.004 * sampleRate)).toFloat()
    private val gainReleaseCoef = exp(-1.0 / (0.095 * sampleRate)).toFloat()

    // Stage 4: Dynamic De-Esser
    private val deEssSidechain = StereoBiquadFilter()
    private val deEssNotch = StereoBiquadFilter()
    private var sidechainL = FloatArray(2048)
    private var sidechainR = FloatArray(2048)
    private var sibilanceEnv = 0f

    // Stage 5: Vocal Clarity & Presence Shaper
    private val mudCutFilter = StereoBiquadFilter()
    private val presenceFilter = StereoBiquadFilter()
    private val airFilter = StereoBiquadFilter()

    var lastNoiseSuppressionDb: Float = 0f
        private set

    fun reset() {
        dcPrevInL = 0f; dcPrevOutL = 0f
        dcPrevInR = 0f; dcPrevOutR = 0f
        hpfStage1.reset()
        hpfStage2.reset()
        spectralCleaner.reset()
        env = 0f
        currentGateGain = 1f
        holdSamples = 0
        deEssSidechain.reset()
        deEssNotch.reset()
        sibilanceEnv = 0f
        mudCutFilter.reset()
        presenceFilter.reset()
        airFilter.reset()
        lastNoiseSuppressionDb = 0f
    }

    internal fun primeNoiseProfile(source: StereoAudioBuffer) {
        spectralCleaner.primeNoiseProfile(source)
    }

    fun processBlock(left: FloatArray, right: FloatArray, numFrames: Int, params: NoiseReductionParams) {
        if (numFrames <= 0) return

        // 1. DC offset removal + High-Pass rumble filter
        for (i in 0 until numFrames) {
            val xL = left[i]
            val yL = xL - dcPrevInL + 0.995f * dcPrevOutL
            dcPrevInL = xL; dcPrevOutL = yL
            left[i] = yL

            val xR = right[i]
            val yR = xR - dcPrevInR + 0.995f * dcPrevOutR
            dcPrevInR = xR; dcPrevOutR = yR
            right[i] = yR
        }

        // Steep 24 dB/oct high-pass rumble filter anchored at >= 85 Hz
        val hpHz = params.highPassHz.coerceIn(85f, 220f)
        hpfStage1.configureHighPass(sampleRate, hpHz, 0.707f)
        hpfStage1.processBlock(left, right, numFrames)
        hpfStage2.configureHighPass(sampleRate, hpHz, 0.707f)
        hpfStage2.processBlock(left, right, numFrames)

        // 2. Adaptive STFT Spectral Noise Suppressor
        if (params.spectralClean && params.amount > 0.02f) {
            spectralCleaner.processBlock(left, right, numFrames, params.amount, params.thresholdDb)
        }

        // 3. Dynamic Downward Expander + Hysteresis Soft Noise Gate
        val threshDb = params.thresholdDb.coerceIn(-70f, -10f)
        val openThresh = 10.0.pow(threshDb / 20.0).toFloat()
        val closeThresh = openThresh * 0.62f // ~4.1 dB hysteresis prevents chatter
        val amountClamped = params.amount.coerceIn(0f, 1f)
        val floorGain = (1f - amountClamped * 0.97f).coerceIn(0.015f, 1f)
        val expanderSlope = 1.2f + amountClamped * 3.8f

        var gateAttenSum = 0f
        for (i in 0 until numFrames) {
            val level = max(abs(left[i]), abs(right[i]))
            val coef = if (level > env) attackCoef else releaseCoef
            env = coef * env + (1f - coef) * level

            val targetGain = when {
                env >= openThresh -> {
                    holdSamples = holdDurationSamples
                    1f
                }
                holdSamples > 0 -> {
                    holdSamples--
                    1f
                }
                env >= closeThresh -> {
                    val t = ((env - closeThresh) / (openThresh - closeThresh).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
                    val kneeFloor = max(floorGain, 0.25f)
                    kneeFloor + (1f - kneeFloor) * t
                }
                else -> {
                    val envDb = 20f * log10(env.coerceAtLeast(1e-6f))
                    val underDb = (threshDb - envDb).coerceAtLeast(0f)
                    val expandedGain = 10.0.pow(-(underDb * (expanderSlope - 1f)) / 20.0).toFloat()
                    expandedGain.coerceIn(floorGain, 1f)
                }
            }
            val smoothCoef = if (targetGain > currentGateGain) gainAttackCoef else gainReleaseCoef
            currentGateGain = smoothCoef * currentGateGain + (1f - smoothCoef) * targetGain

            left[i] *= currentGateGain
            right[i] *= currentGateGain
            gateAttenSum += currentGateGain
        }
        val avgGateGain = (gateAttenSum / numFrames).coerceIn(0.015f, 1f)
        val gateDb = (-20f * log10(avgGateGain)).coerceIn(0f, 32f)

        // 4. Sidechain Dynamic De-Esser
        val deEss = params.deEssAmount.coerceIn(0f, 1f)
        if (deEss > 0.02f) {
            if (sidechainL.size < numFrames) {
                sidechainL = FloatArray(numFrames)
                sidechainR = FloatArray(numFrames)
            }
            System.arraycopy(left, 0, sidechainL, 0, numFrames)
            System.arraycopy(right, 0, sidechainR, 0, numFrames)
            deEssSidechain.configureBandPass(sampleRate, 6600f, 2.0f)
            deEssSidechain.processBlock(sidechainL, sidechainR, numFrames)

            var maxSib = 0f
            for (i in 0 until numFrames) {
                val sib = max(abs(sidechainL[i]), abs(sidechainR[i]))
                sibilanceEnv = sibilanceEnv * 0.94f + sib * 0.06f
                if (sibilanceEnv > maxSib) maxSib = sibilanceEnv
            }
            val sibThresh = 0.035f
            val excess = ((maxSib - sibThresh) / 0.16f).coerceIn(0f, 1f)
            val cutDb = -(excess * deEss * 7.5f)
            if (cutDb < -0.15f) {
                deEssNotch.configurePeaking(sampleRate, 6600f, 1.8f, cutDb)
                deEssNotch.processBlock(left, right, numFrames)
            }
        }

        // 5. Vocal Clarity & +4.5 dB Vocal Presence Shelf at 3.5 kHz
        val clarity = params.vocalClarity.coerceIn(0f, 1f)
        if (clarity > 0.02f) {
            mudCutFilter.configurePeaking(sampleRate, 265f, 1.2f, -2.6f * clarity)
            mudCutFilter.processBlock(left, right, numFrames)
        }

        presenceFilter.configureHighShelf(sampleRate, 3500f, 0.85f, 4.5f)
        presenceFilter.processBlock(left, right, numFrames)

        if (clarity > 0.02f) {
            airFilter.configureHighShelf(sampleRate, 10200f, 0.85f, 1.8f * clarity)
            airFilter.processBlock(left, right, numFrames)
        }

        val spectralDb = if (params.spectralClean) spectralCleaner.lastSuppressionDb else 0f
        lastNoiseSuppressionDb = max(spectralDb, gateDb).coerceIn(0f, 34f)
    }
}

/**
 * High-precision offline voice purification engine that profiles the clip's true noise floor
 * first, compensates for STFT lookahead delay to keep exact sample sync, and safeguards headroom.
 */
object VoicePurifierEngine {

    fun purifyBufferOffline(
        source: StereoAudioBuffer,
        params: NoiseReductionParams,
        onProgress: (Float) -> Unit = {}
    ): StereoAudioBuffer {
        val total = source.frameCount
        if (total <= 0) {
            onProgress(1f)
            return source
        }

        val processor = NoiseReductionProcessor(source.sampleRate)
        val lookahead = if (params.spectralClean && params.amount > 0.02f) {
            processor.primeNoiseProfile(source)
            SpectralVoiceCleaner.FFT_SIZE
        } else {
            0
        }

        val paddedTotal = total + lookahead
        val paddedL = FloatArray(paddedTotal)
        val paddedR = FloatArray(paddedTotal)
        System.arraycopy(source.left, 0, paddedL, 0, total)
        System.arraycopy(source.right, 0, paddedR, 0, total)

        val blockSize = 1024
        val blockL = FloatArray(blockSize)
        val blockR = FloatArray(blockSize)
        var offset = 0
        while (offset < paddedTotal) {
            val count = min(blockSize, paddedTotal - offset)
            System.arraycopy(paddedL, offset, blockL, 0, count)
            System.arraycopy(paddedR, offset, blockR, 0, count)
            processor.processBlock(blockL, blockR, count, params)
            System.arraycopy(blockL, 0, paddedL, offset, count)
            System.arraycopy(blockR, 0, paddedR, offset, count)
            offset += count
            onProgress((offset.toFloat() / paddedTotal.toFloat()).coerceIn(0f, 0.96f))
        }

        // Compensate STFT hop delay so waveform timing stays sample-accurate
        val outL = FloatArray(total)
        val outR = FloatArray(total)
        System.arraycopy(paddedL, lookahead, outL, 0, total)
        System.arraycopy(paddedR, lookahead, outR, 0, total)

        // Safe headroom protection without squashing dynamics
        var peak = 0f
        for (i in 0 until total) {
            val a = max(abs(outL[i]), abs(outR[i]))
            if (a > peak) peak = a
        }
        if (peak > 0.94f) {
            val scale = 0.94f / peak
            for (i in 0 until total) {
                outL[i] *= scale
                outR[i] *= scale
            }
        }

        onProgress(1f)
        return StereoAudioBuffer(outL, outR, source.sampleRate)
    }
}
