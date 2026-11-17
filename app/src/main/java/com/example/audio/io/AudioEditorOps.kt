package com.example.audio.io

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object AudioEditorOps {

    fun trimToSelection(
        buffer: StereoAudioBuffer,
        startFraction: Float,
        endFraction: Float
    ): StereoAudioBuffer {
        val total = buffer.frameCount
        if (total == 0) return buffer
        val s = (startFraction.coerceIn(0f, 1f) * total).toInt().coerceIn(0, total - 1)
        val e = (endFraction.coerceIn(0f, 1f) * total).toInt().coerceIn(s + 1, total)
        val newLen = e - s
        val outL = buffer.left.copyOfRange(s, e)
        val outR = buffer.right.copyOfRange(s, e)
        applyEdgeFades(outL, outR, min(256, newLen / 8))
        return StereoAudioBuffer(outL, outR, buffer.sampleRate)
    }

    fun cutSelection(
        buffer: StereoAudioBuffer,
        startFraction: Float,
        endFraction: Float
    ): StereoAudioBuffer {
        val total = buffer.frameCount
        if (total == 0) return buffer
        val s = (startFraction.coerceIn(0f, 1f) * total).toInt().coerceIn(0, total - 1)
        val e = (endFraction.coerceIn(0f, 1f) * total).toInt().coerceIn(s + 1, total)
        val remaining = total - (e - s)
        if (remaining <= 64) return buffer

        val outL = FloatArray(remaining)
        val outR = FloatArray(remaining)
        System.arraycopy(buffer.left, 0, outL, 0, s)
        System.arraycopy(buffer.right, 0, outR, 0, s)
        val tailLen = total - e
        System.arraycopy(buffer.left, e, outL, s, tailLen)
        System.arraycopy(buffer.right, e, outR, s, tailLen)

        // Smooth 5ms crossfade around splice point s to prevent clicks
        val fadeSamples = min(220, min(s, tailLen))
        for (i in 0 until fadeSamples) {
            val factor = i.toFloat() / fadeSamples.toFloat()
            if (s - fadeSamples + i >= 0) {
                outL[s - fadeSamples + i] *= (1f - factor * 0.35f)
                outR[s - fadeSamples + i] *= (1f - factor * 0.35f)
            }
            if (s + i < outL.size) {
                outL[s + i] *= (0.65f + factor * 0.35f)
                outR[s + i] *= (0.65f + factor * 0.35f)
            }
        }
        return StereoAudioBuffer(outL, outR, buffer.sampleRate)
    }

    fun mergeClips(first: StereoAudioBuffer, second: StereoAudioBuffer): StereoAudioBuffer {
        val alignedSecond = if (second.sampleRate != first.sampleRate) {
            WavAudioCodec.resample(second, first.sampleRate)
        } else {
            second
        }
        val len1 = first.frameCount
        val len2 = alignedSecond.frameCount
        if (len1 == 0) return alignedSecond
        if (len2 == 0) return first

        val overlap = min(440, min(len1 / 4, len2 / 4))
        val totalLen = len1 + len2 - overlap
        val outL = FloatArray(totalLen)
        val outR = FloatArray(totalLen)

        System.arraycopy(first.left, 0, outL, 0, len1)
        System.arraycopy(first.right, 0, outR, 0, len1)

        // Crossfade overlap region
        val joinStart = len1 - overlap
        for (i in 0 until overlap) {
            val t = i.toFloat() / overlap.toFloat()
            outL[joinStart + i] = outL[joinStart + i] * (1f - t) + alignedSecond.left[i] * t
            outR[joinStart + i] = outR[joinStart + i] * (1f - t) + alignedSecond.right[i] * t
        }
        System.arraycopy(alignedSecond.left, overlap, outL, len1, len2 - overlap)
        System.arraycopy(alignedSecond.right, overlap, outR, len1, len2 - overlap)

        return StereoAudioBuffer(outL, outR, first.sampleRate)
    }

    fun applyGainDb(buffer: StereoAudioBuffer, gainDb: Float): StereoAudioBuffer {
        val gain = 10.0.pow(gainDb / 20.0).toFloat()
        val n = buffer.frameCount
        val outL = FloatArray(n)
        val outR = FloatArray(n)
        for (i in 0 until n) {
            outL[i] = (buffer.left[i] * gain).coerceIn(-1f, 1f)
            outR[i] = (buffer.right[i] * gain).coerceIn(-1f, 1f)
        }
        return StereoAudioBuffer(outL, outR, buffer.sampleRate)
    }

    fun normalize(buffer: StereoAudioBuffer, targetPeak: Float = 0.944f): StereoAudioBuffer {
        val n = buffer.frameCount
        if (n == 0) return buffer
        var maxAmp = 0f
        for (i in 0 until n) {
            val a = max(abs(buffer.left[i]), abs(buffer.right[i]))
            if (a > maxAmp) maxAmp = a
        }
        if (maxAmp < 1e-5f) return buffer
        val scale = targetPeak / maxAmp
        val outL = FloatArray(n)
        val outR = FloatArray(n)
        for (i in 0 until n) {
            outL[i] = (buffer.left[i] * scale).coerceIn(-1f, 1f)
            outR[i] = (buffer.right[i] * scale).coerceIn(-1f, 1f)
        }
        return StereoAudioBuffer(outL, outR, buffer.sampleRate)
    }

    fun applyFadeIn(
        buffer: StereoAudioBuffer,
        durationSec: Float
    ): StereoAudioBuffer {
        val n = buffer.frameCount
        if (n <= 1) return buffer
        val clampedSec = durationSec.coerceIn(0.2f, 10f)
        val fadeSamples = min(n, (clampedSec * buffer.sampleRate).toInt().coerceAtLeast(1))
        val outL = buffer.left.copyOf()
        val outR = buffer.right.copyOf()
        for (i in 0 until fadeSamples) {
            val t = i.toFloat() / fadeSamples.toFloat()
            // Smooth cosine S-curve ramp from 0.0 to 1.0
            val gain = (0.5 - 0.5 * kotlin.math.cos(kotlin.math.PI * t)).toFloat()
            outL[i] *= gain
            outR[i] *= gain
        }
        return StereoAudioBuffer(outL, outR, buffer.sampleRate)
    }

    fun applyFadeOut(
        buffer: StereoAudioBuffer,
        durationSec: Float
    ): StereoAudioBuffer {
        val n = buffer.frameCount
        if (n <= 1) return buffer
        val clampedSec = durationSec.coerceIn(0.2f, 10f)
        val fadeSamples = min(n, (clampedSec * buffer.sampleRate).toInt().coerceAtLeast(1))
        val startIdx = n - fadeSamples
        val outL = buffer.left.copyOf()
        val outR = buffer.right.copyOf()
        for (i in 0 until fadeSamples) {
            val t = i.toFloat() / fadeSamples.toFloat()
            // Smooth cosine S-curve ramp from 1.0 down to 0.0
            val gain = (0.5 + 0.5 * kotlin.math.cos(kotlin.math.PI * t)).toFloat()
            outL[startIdx + i] *= gain
            outR[startIdx + i] *= gain
        }
        return StereoAudioBuffer(outL, outR, buffer.sampleRate)
    }

    fun mixTracks(
        mainBuffer: StereoAudioBuffer,
        backgroundBuffer: StereoAudioBuffer,
        mainGain: Float = 1.0f,
        backgroundGain: Float = 0.45f
    ): StereoAudioBuffer {
        val alignedBg = if (backgroundBuffer.sampleRate != mainBuffer.sampleRate) {
            WavAudioCodec.resample(backgroundBuffer, mainBuffer.sampleRate)
        } else {
            backgroundBuffer
        }
        val lenMain = mainBuffer.frameCount
        val lenBg = alignedBg.frameCount
        if (lenMain == 0) return applyGainFactor(alignedBg, backgroundGain)
        if (lenBg == 0) return applyGainFactor(mainBuffer, mainGain)

        val outLen = max(lenMain, lenBg)
        val gMain = mainGain.coerceIn(0f, 2f)
        val gBg = backgroundGain.coerceIn(0f, 2f)
        val outL = FloatArray(outLen)
        val outR = FloatArray(outLen)

        for (i in 0 until outLen) {
            val ml = if (i < lenMain) mainBuffer.left[i] * gMain else 0f
            val mr = if (i < lenMain) mainBuffer.right[i] * gMain else 0f
            val bl = if (i < lenBg) alignedBg.left[i] * gBg else 0f
            val br = if (i < lenBg) alignedBg.right[i] * gBg else 0f
            outL[i] = softClipSample(ml + bl)
            outR[i] = softClipSample(mr + br)
        }
        return StereoAudioBuffer(outL, outR, mainBuffer.sampleRate)
    }

    private fun applyGainFactor(buffer: StereoAudioBuffer, factor: Float): StereoAudioBuffer {
        val g = factor.coerceIn(0f, 2f)
        val n = buffer.frameCount
        val outL = FloatArray(n)
        val outR = FloatArray(n)
        for (i in 0 until n) {
            outL[i] = softClipSample(buffer.left[i] * g)
            outR[i] = softClipSample(buffer.right[i] * g)
        }
        return StereoAudioBuffer(outL, outR, buffer.sampleRate)
    }

    private fun softClipSample(x: Float): Float {
        if (x in -0.95f..0.95f) return x
        return kotlin.math.tanh(x.toDouble()).toFloat().coerceIn(-0.99f, 0.99f)
    }

    private fun applyEdgeFades(left: FloatArray, right: FloatArray, fadeSamples: Int) {
        if (fadeSamples <= 1) return
        val n = min(left.size, right.size)
        val count = min(fadeSamples, n / 2)
        for (i in 0 until count) {
            val g = i.toFloat() / count.toFloat()
            left[i] *= g
            right[i] *= g
            val endIdx = n - 1 - i
            left[endIdx] *= g
            right[endIdx] *= g
        }
    }
}
