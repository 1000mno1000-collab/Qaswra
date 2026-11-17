package com.example.audio.dsp

import androidx.annotation.StringRes
import com.example.R
import com.example.audio.io.StereoAudioBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

enum class VoiceCharacterPreset(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int,
    val pitchSemitones: Float,
    val formantShift: Float,
    val warmthDb: Float,
    val presenceDb: Float
) {
    MALE_LUXURY(
        id = "male_luxury",
        titleRes = R.string.voice_char_male_luxury,
        descRes = R.string.voice_char_male_luxury_desc,
        pitchSemitones = -4.2f,
        formantShift = 0.89f,
        warmthDb = 2.8f,
        presenceDb = 1.6f
    ),
    MALE_CALM(
        id = "male_calm",
        titleRes = R.string.voice_char_male_calm,
        descRes = R.string.voice_char_male_calm_desc,
        pitchSemitones = -2.5f,
        formantShift = 0.93f,
        warmthDb = 2.0f,
        presenceDb = 1.0f
    ),
    FEMALE_SOFT(
        id = "female_soft",
        titleRes = R.string.voice_char_female_soft,
        descRes = R.string.voice_char_female_soft_desc,
        pitchSemitones = 3.6f,
        formantShift = 1.12f,
        warmthDb = -1.2f,
        presenceDb = 2.4f
    ),
    FEMALE_BALANCED(
        id = "female_balanced",
        titleRes = R.string.voice_char_female_balanced,
        descRes = R.string.voice_char_female_balanced_desc,
        pitchSemitones = 2.5f,
        formantShift = 1.07f,
        warmthDb = 0.0f,
        presenceDb = 1.8f
    ),
    CHILD(
        id = "child",
        titleRes = R.string.voice_char_child,
        descRes = R.string.voice_char_child_desc,
        pitchSemitones = 6.5f,
        formantShift = 1.24f,
        warmthDb = -2.2f,
        presenceDb = 2.8f
    )
}

/**
 * 100% offline Formant-Preserving Voice Transformer.
 * Decouples fundamental pitch (F0) from the vocal tract spectral envelope (F1..F3)
 * using WSOLA pitch-synchronous overlap-add followed by 1024-point STFT cepstral
 * envelope warping so voice transformations remain natural without chipmunk/monster artifacts.
 */
object FormantVoiceTransformer {

    private const val FFT_SIZE = 1024
    private const val HOP_SIZE = 256
    private const val BINS = FFT_SIZE / 2 + 1 // 513
    private const val CEPSTRUM_CUTOFF = 22

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

    fun transformOffline(
        source: StereoAudioBuffer,
        pitchSemitones: Float,
        formantShift: Float,
        warmthDb: Float = 0f,
        presenceDb: Float = 0f,
        mix: Float = 1.0f,
        onProgress: (Float) -> Unit = {}
    ): StereoAudioBuffer {
        val frames = source.frameCount
        if (frames < FFT_SIZE) return source

        val clampedPitchSt = pitchSemitones.coerceIn(-12f, 12f)
        val pitchRatio = 2.0.pow(clampedPitchSt / 12.0).toFloat()
        val targetFormant = formantShift.coerceIn(0.65f, 1.5f)
        val wetMix = mix.coerceIn(0f, 1f)

        onProgress(0.05f)

        // Stage 1: Time-preserving WSOLA pitch shift (resamples at pitchRatio, stretched back to original length)
        val pitchShifted = if (abs(clampedPitchSt) > 0.05f) {
            applyPitchShiftSameDuration(source, pitchRatio)
        } else {
            source
        }
        onProgress(0.45f)

        // Stage 2: Cepstral spectral-envelope formant correction in 1024-pt STFT domain
        val relativeWarp = (targetFormant / pitchRatio).coerceIn(0.45f, 2.2f)
        val formantAdjusted = if (abs(relativeWarp - 1.0f) > 0.015f) {
            applyCepstralFormantWarp(pitchShifted, relativeWarp) { stftFrac ->
                onProgress(0.45f + stftFrac * 0.45f)
            }
        } else {
            pitchShifted
        }

        // Stage 3: Character tonal shaping (warmth + vocal presence) & soft-limited dry/wet blend
        val outL = formantAdjusted.left.copyOf()
        val outR = formantAdjusted.right.copyOf()

        if (abs(warmthDb) > 0.1f) {
            val shelf = StereoBiquadFilter()
            shelf.configureLowShelf(source.sampleRate, 185f, 0.85f, warmthDb.coerceIn(-8f, 8f))
            shelf.processBlock(outL, outR, frames)
        }
        if (abs(presenceDb) > 0.1f) {
            val peak = StereoBiquadFilter()
            peak.configurePeaking(source.sampleRate, 3400f, 1.05f, presenceDb.coerceIn(-6f, 8f))
            peak.processBlock(outL, outR, frames)
        }

        // Match perceived RMS loudness gently with source so character switching stays balanced
        val srcRms = computeRms(source.left, frames)
        val wetRms = computeRms(outL, frames)
        val levelComp = if (wetRms > 1e-4f && srcRms > 1e-4f) {
            (srcRms / wetRms).coerceIn(0.65f, 1.55f)
        } else {
            1.0f
        }

        val dryMix = 1f - wetMix
        for (i in 0 until frames) {
            val l = source.left[i] * dryMix + (outL[i] * levelComp) * wetMix
            val r = source.right[i] * dryMix + (outR[i] * levelComp) * wetMix
            outL[i] = softLimit(l)
            outR[i] = softLimit(r)
        }

        onProgress(1.0f)
        return StereoAudioBuffer(outL, outR, source.sampleRate)
    }

    /**
     * Granular WSOLA overlap-add resampler keeping the exact output frame count.
     */
    private fun applyPitchShiftSameDuration(
        source: StereoAudioBuffer,
        pitchRatio: Float
    ): StereoAudioBuffer {
        val frames = source.frameCount
        // Step 1: Time-stretch by pitchRatio first using correlation-aligned WSOLA,
        // so that resampling at pitchRatio restores exact original duration!
        val stretchedLen = (frames * pitchRatio).toInt().coerceAtLeast(FFT_SIZE)
        val stretchL = FloatArray(stretchedLen)
        val stretchR = FloatArray(stretchedLen)
        val stretchNorm = FloatArray(stretchedLen)

        val winSize = 1024
        val hopOut = 256
        val hopIn = (hopOut / pitchRatio).coerceIn(48f, 640f)
        val searchRadius = 48

        val hann = FloatArray(winSize) { n ->
            (0.5 * (1.0 - cos(2.0 * PI * n / winSize))).toFloat()
        }

        var outPos = 0
        var inPosF = 0f
        while (outPos + winSize <= stretchedLen && inPosF.toInt() + winSize <= frames) {
            val nominalIn = inPosF.toInt()
            val alignedIn = if (outPos == 0) {
                nominalIn
            } else {
                findBestCorrelationOffset(
                    target = stretchL,
                    targetOffset = outPos,
                    source = source.left,
                    nominalOffset = nominalIn,
                    searchRadius = searchRadius,
                    maxSourceLen = frames - winSize
                )
            }
            for (k in 0 until winSize) {
                val w = hann[k]
                stretchL[outPos + k] += source.left[alignedIn + k] * w
                stretchR[outPos + k] += source.right[alignedIn + k] * w
                stretchNorm[outPos + k] += w
            }
            outPos += hopOut
            inPosF += hopIn
        }

        for (i in 0 until stretchedLen) {
            val n = stretchNorm[i]
            if (n > 1e-4f) {
                stretchL[i] /= n
                stretchR[i] /= n
            }
        }

        // Step 2: Cubic-like linear resample back to original frames at step = pitchRatio
        val resL = FloatArray(frames)
        val resR = FloatArray(frames)
        for (i in 0 until frames) {
            val pos = i * pitchRatio
            val idx0 = pos.toInt().coerceIn(0, stretchedLen - 1)
            val idx1 = (idx0 + 1).coerceAtMost(stretchedLen - 1)
            val frac = (pos - idx0).coerceIn(0f, 1f)
            resL[i] = stretchL[idx0] + (stretchL[idx1] - stretchL[idx0]) * frac
            resR[i] = stretchR[idx0] + (stretchR[idx1] - stretchR[idx0]) * frac
        }
        return StereoAudioBuffer(resL, resR, source.sampleRate)
    }

    private fun findBestCorrelationOffset(
        target: FloatArray,
        targetOffset: Int,
        source: FloatArray,
        nominalOffset: Int,
        searchRadius: Int,
        maxSourceLen: Int
    ): Int {
        val compareLen = 128
        if (targetOffset + compareLen > target.size) return nominalOffset.coerceIn(0, maxSourceLen)
        val minOff = (nominalOffset - searchRadius).coerceAtLeast(0)
        val maxOff = (nominalOffset + searchRadius).coerceAtMost(maxSourceLen)
        var bestOff = nominalOffset.coerceIn(minOff, maxOff)
        var bestScore = Float.NEGATIVE_INFINITY
        var off = minOff
        while (off <= maxOff) {
            var corr = 0f
            for (k in 0 until compareLen step 2) {
                corr += target[targetOffset + k] * source[off + k]
            }
            if (corr > bestScore) {
                bestScore = corr
                bestOff = off
            }
            off += 2
        }
        return bestOff
    }

    /**
     * Extracts the smooth cepstral vocal-tract spectral envelope of each frame,
     * warps it by [relativeWarp], and applies the gain ratio to the STFT complex spectrum.
     */
    private fun applyCepstralFormantWarp(
        input: StereoAudioBuffer,
        relativeWarp: Float,
        onProgress: (Float) -> Unit
    ): StereoAudioBuffer {
        val frames = input.frameCount
        val outL = FloatArray(frames)
        val outR = FloatArray(frames)

        val reL = DoubleArray(FFT_SIZE)
        val imL = DoubleArray(FFT_SIZE)
        val reR = DoubleArray(FFT_SIZE)
        val imR = DoubleArray(FFT_SIZE)
        val envRe = DoubleArray(FFT_SIZE)
        val envIm = DoubleArray(FFT_SIZE)
        val envMag = FloatArray(BINS)
        val warpGain = FloatArray(BINS)

        val lifterWeights = DoubleArray(FFT_SIZE)
        for (q in 0 until FFT_SIZE) {
            val d = min(q, FFT_SIZE - q)
            lifterWeights[q] = when {
                d == 0 -> 1.0
                d < CEPSTRUM_CUTOFF -> 1.0
                d < CEPSTRUM_CUTOFF + 6 -> 0.5 * (1.0 + cos(PI * (d - CEPSTRUM_CUTOFF) / 6.0))
                else -> 0.0
            }
        }

        var pos = 0
        while (pos < frames) {
            val avail = min(FFT_SIZE, frames - pos)
            for (n in 0 until FFT_SIZE) {
                val w = sqrtHann[n].toDouble()
                if (n < avail) {
                    reL[n] = input.left[pos + n] * w
                    reR[n] = input.right[pos + n] * w
                } else {
                    reL[n] = 0.0
                    reR[n] = 0.0
                }
                imL[n] = 0.0
                imR[n] = 0.0
            }

            fft1024(reL, imL, inverse = false)
            fft1024(reR, imR, inverse = false)

            // Estimate joint stereo spectral envelope via real cepstrum
            for (k in 0 until BINS) {
                val magL = sqrt(reL[k] * reL[k] + imL[k] * imL[k])
                val magR = sqrt(reR[k] * reR[k] + imR[k] * imR[k])
                val avgMag = max((magL + magR) * 0.5, 1e-4)
                val logM = ln(avgMag)
                envRe[k] = logM
                envIm[k] = 0.0
                if (k in 1 until (FFT_SIZE / 2)) {
                    envRe[FFT_SIZE - k] = logM
                    envIm[FFT_SIZE - k] = 0.0
                }
            }

            // IFFT -> Cepstrum -> Lifter -> FFT -> Smooth Vocal Tract Log-Envelope
            fft1024(envRe, envIm, inverse = true)
            for (q in 0 until FFT_SIZE) {
                envRe[q] *= lifterWeights[q]
                envIm[q] = 0.0
            }
            fft1024(envRe, envIm, inverse = false)

            for (k in 0 until BINS) {
                envMag[k] = exp(envRe[k].coerceIn(-12.0, 12.0)).toFloat()
            }

            for (k in 0 until BINS) {
                val srcBin = (k / relativeWarp).coerceIn(0f, (BINS - 1).toFloat())
                val i0 = srcBin.toInt().coerceIn(0, BINS - 2)
                val frac = srcBin - i0
                val targetEnv = envMag[i0] + (envMag[i0 + 1] - envMag[i0]) * frac
                val currentEnv = max(envMag[k], 1e-4f)
                // Dampen extreme high-bin noise amplification above ~10 kHz
                val hfTaper = if (k > 340) (1f - (k - 340f) / 220f).coerceIn(0.35f, 1f) else 1f
                val ratio = (targetEnv / currentEnv).coerceIn(0.30f, 3.2f)
                warpGain[k] = 1f + (ratio - 1f) * hfTaper
            }

            for (k in 0 until BINS) {
                val g = warpGain[k].toDouble()
                reL[k] *= g
                imL[k] *= g
                reR[k] *= g
                imR[k] *= g
                if (k in 1 until (FFT_SIZE / 2)) {
                    val mirror = FFT_SIZE - k
                    reL[mirror] *= g
                    imL[mirror] *= g
                    reR[mirror] *= g
                    imR[mirror] *= g
                }
            }

            fft1024(reL, imL, inverse = true)
            fft1024(reR, imR, inverse = true)

            // 75% sqrtHann^2 overlap-add sum equals 2.0 -> multiply by 0.5 for unity COLA
            for (n in 0 until avail) {
                val synW = sqrtHann[n] * 0.5f
                outL[pos + n] += (reL[n] * synW).toFloat()
                outR[pos + n] += (reR[n] * synW).toFloat()
            }

            pos += HOP_SIZE
            if ((pos / HOP_SIZE) % 24 == 0) {
                onProgress((pos.toFloat() / frames.toFloat()).coerceIn(0f, 1f))
            }
        }
        onProgress(1f)
        return StereoAudioBuffer(outL, outR, input.sampleRate)
    }

    private fun fft1024(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until FFT_SIZE) {
            val j = bitReverse[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= FFT_SIZE) {
            val half = size shr 1
            val step = FFT_SIZE / size
            var i = 0
            while (i < FFT_SIZE) {
                var tableIdx = 0
                for (j in 0 until half) {
                    val wr = cosTable[tableIdx]
                    val wi = if (inverse) -sinTable[tableIdx] else sinTable[tableIdx]
                    val idx1 = i + j
                    val idx2 = idx1 + half
                    val tRe = re[idx2] * wr - im[idx2] * wi
                    val tIm = re[idx2] * wi + im[idx2] * wr
                    re[idx2] = re[idx1] - tRe
                    im[idx2] = im[idx1] - tIm
                    re[idx1] += tRe
                    im[idx1] += tIm
                    tableIdx += step
                }
                i += size
            }
            size = size shl 1
        }
        if (inverse) {
            val invN = 1.0 / FFT_SIZE
            for (i in 0 until FFT_SIZE) {
                re[i] *= invN
                im[i] *= invN
            }
        }
    }

    private fun computeRms(samples: FloatArray, count: Int): Float {
        var sumSq = 0.0
        for (i in 0 until count) {
            val s = samples[i]
            sumSq += s * s
        }
        return sqrt(sumSq / count.coerceAtLeast(1)).toFloat()
    }

    private fun softLimit(x: Float): Float {
        val ax = abs(x)
        return if (ax <= 0.90f) x else (tanh((x * 1.05).toDouble()) * 0.945).toFloat()
    }
}
