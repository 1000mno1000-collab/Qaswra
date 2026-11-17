package com.example.audio

import com.example.model.StereoAudioBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

fun computeWaveformPeaks(
    buf: StereoAudioBuffer,
    buckets: Int = 120,
): List<Float> {
    val total = buf.frameCount
    if (total == 0 || buckets <= 0) return emptyList()
    val result = ArrayList<Float>(buckets)
    val step = total.toDouble() / buckets
    for (i in 0 until buckets) {
        val start = clamp(floor(i * step).toInt(), 0, total - 1)
        val end = clamp(floor((i + 1) * step).toInt(), start + 1, total)
        var peak = 0f
        val stride = max(1, (end - start) / 64)
        var idx = start
        while (idx < end) {
            val amp = max(abs(buf.left[idx]), abs(buf.right[idx]))
            if (amp > peak) peak = amp
            idx += stride
        }
        result.add(clamp(peak, 0f, 1f))
    }
    return result
}

fun resampleStereo(
    buffer: StereoAudioBuffer,
    targetRate: Int,
): StereoAudioBuffer {
    val total = buffer.frameCount
    if (buffer.sampleRate == targetRate || total == 0) return buffer
    val ratio = buffer.sampleRate.toDouble() / targetRate
    val outFrames = max(1, floor(total / ratio).toInt())
    val outL = FloatArray(outFrames)
    val outR = FloatArray(outFrames)
    val maxIdx = total - 1
    for (i in 0 until outFrames) {
        val pos = i * ratio
        val idx0 = clamp(floor(pos).toInt(), 0, maxIdx)
        val idx1 = clamp(idx0 + 1, 0, maxIdx)
        val frac = (pos - idx0).toFloat()
        outL[i] = buffer.left[idx0] + (buffer.left[idx1] - buffer.left[idx0]) * frac
        outR[i] = buffer.right[idx0] + (buffer.right[idx1] - buffer.right[idx0]) * frac
    }
    return StereoAudioBuffer(outL, outR, targetRate)
}

fun trimToSelection(
    buffer: StereoAudioBuffer,
    startFraction: Float,
    endFraction: Float,
): StereoAudioBuffer {
    val total = buffer.frameCount
    if (total == 0) return buffer
    val s = clamp(floor(startFraction * total).toInt(), 0, total - 1)
    val e = clamp(floor(endFraction * total).toInt(), s + 1, total)
    return StereoAudioBuffer(
        left = buffer.left.copyOfRange(s, e),
        right = buffer.right.copyOfRange(s, e),
        sampleRate = buffer.sampleRate,
    )
}

fun cutSelection(
    buffer: StereoAudioBuffer,
    startFraction: Float,
    endFraction: Float,
): StereoAudioBuffer {
    val total = buffer.frameCount
    if (total == 0) return buffer
    val s = clamp(floor(startFraction * total).toInt(), 0, total - 1)
    val e = clamp(floor(endFraction * total).toInt(), s + 1, total)
    val remaining = total - (e - s)
    if (remaining <= 0) return buffer

    val outL = FloatArray(remaining)
    val outR = FloatArray(remaining)
    System.arraycopy(buffer.left, 0, outL, 0, s)
    System.arraycopy(buffer.left, e, outL, s, total - e)
    System.arraycopy(buffer.right, 0, outR, 0, s)
    System.arraycopy(buffer.right, e, outR, s, total - e)
    return StereoAudioBuffer(outL, outR, buffer.sampleRate)
}

fun mergeClips(
    first: StereoAudioBuffer,
    second: StereoAudioBuffer,
    crossfadeMs: Int = 15,
): StereoAudioBuffer {
    val alignedSecond = if (second.sampleRate == first.sampleRate) {
        second
    } else {
        resampleStereo(second, first.sampleRate)
    }
    val n1 = first.frameCount
    val n2 = alignedSecond.frameCount
    if (n1 == 0) return alignedSecond
    if (n2 == 0) return first

    val xfade = minOf(
        floor((crossfadeMs / 1000.0) * first.sampleRate).toInt(),
        n1 / 4,
        n2 / 4,
    )
    val totalOut = n1 + n2 - xfade
    val outL = FloatArray(totalOut)
    val outR = FloatArray(totalOut)

    System.arraycopy(first.left, 0, outL, 0, n1 - xfade)
    System.arraycopy(first.right, 0, outR, 0, n1 - xfade)

    val overlapStart = n1 - xfade
    for (i in 0 until xfade) {
        val t = i.toFloat() / max(1, xfade)
        outL[overlapStart + i] = first.left[n1 - xfade + i] * (1f - t) + alignedSecond.left[i] * t
        outR[overlapStart + i] = first.right[n1 - xfade + i] * (1f - t) + alignedSecond.right[i] * t
    }

    System.arraycopy(alignedSecond.left, xfade, outL, n1, n2 - xfade)
    System.arraycopy(alignedSecond.right, xfade, outR, n1, n2 - xfade)
    return StereoAudioBuffer(outL, outR, first.sampleRate)
}

fun applyGainDb(
    buffer: StereoAudioBuffer,
    gainDb: Float,
): StereoAudioBuffer {
    val n = buffer.frameCount
    val linear = 10.0.pow(gainDb / 20.0).toFloat()
    val outL = FloatArray(n) { i -> clamp(buffer.left[i] * linear, -1f, 1f) }
    val outR = FloatArray(n) { i -> clamp(buffer.right[i] * linear, -1f, 1f) }
    return StereoAudioBuffer(outL, outR, buffer.sampleRate)
}

fun normalizeAudio(
    buffer: StereoAudioBuffer,
    targetPeakDb: Float = -1.0f,
): StereoAudioBuffer {
    val n = buffer.frameCount
    if (n == 0) return buffer
    var maxPeak = 0f
    for (i in 0 until n) {
        val p = max(abs(buffer.left[i]), abs(buffer.right[i]))
        if (p > maxPeak) maxPeak = p
    }
    if (maxPeak < 1e-5f) return buffer
    val targetLinear = 10.0.pow(targetPeakDb / 20.0).toFloat()
    val scale = targetLinear / maxPeak
    val outL = FloatArray(n) { i -> clamp(buffer.left[i] * scale, -1f, 1f) }
    val outR = FloatArray(n) { i -> clamp(buffer.right[i] * scale, -1f, 1f) }
    return StereoAudioBuffer(outL, outR, buffer.sampleRate)
}

fun applyFadeIn(
    buffer: StereoAudioBuffer,
    fadeSeconds: Float,
): StereoAudioBuffer {
    val total = buffer.frameCount
    if (total == 0 || fadeSeconds <= 0f) return buffer
    val fadeFrames = min(total, (fadeSeconds * buffer.sampleRate).roundToInt())
    val outL = buffer.left.copyOf()
    val outR = buffer.right.copyOf()
    val denom = max(1, fadeFrames - 1)
    for (i in 0 until fadeFrames) {
        val t = i.toDouble() / denom
        val gain = sin(t * PI * 0.5).toFloat()
        outL[i] *= gain
        outR[i] *= gain
    }
    return StereoAudioBuffer(outL, outR, buffer.sampleRate)
}

fun applyFadeOut(
    buffer: StereoAudioBuffer,
    fadeSeconds: Float,
): StereoAudioBuffer {
    val total = buffer.frameCount
    if (total == 0 || fadeSeconds <= 0f) return buffer
    val fadeFrames = min(total, (fadeSeconds * buffer.sampleRate).roundToInt())
    val outL = buffer.left.copyOf()
    val outR = buffer.right.copyOf()
    val start = total - fadeFrames
    val denom = max(1, fadeFrames - 1)
    for (i in 0 until fadeFrames) {
        val t = i.toDouble() / denom
        val gain = cos(t * PI * 0.5).toFloat()
        outL[start + i] *= gain
        outR[start + i] *= gain
    }
    return StereoAudioBuffer(outL, outR, buffer.sampleRate)
}

fun mixTracks(
    main: StereoAudioBuffer,
    background: StereoAudioBuffer,
    mainGain: Float = 1.0f,
    bgGain: Float = 0.45f,
): StereoAudioBuffer {
    val n1 = main.frameCount
    if (n1 == 0) return background
    val alignedBg = if (background.sampleRate == main.sampleRate) {
        background
    } else {
        resampleStereo(background, main.sampleRate)
    }
    val n2 = alignedBg.frameCount
    if (n2 == 0) return main

    val outFrames = max(n1, n2)
    val outL = FloatArray(outFrames)
    val outR = FloatArray(outFrames)
    for (i in 0 until outFrames) {
        val mL = if (i < n1) main.left[i] * mainGain else 0f
        val mR = if (i < n1) main.right[i] * mainGain else 0f
        val bL = if (i < n2) alignedBg.left[i] * bgGain else 0f
        val bR = if (i < n2) alignedBg.right[i] * bgGain else 0f
        outL[i] = tanh((mL + bL).toDouble()).toFloat()
        outR[i] = tanh((mR + bR).toDouble()).toFloat()
    }
    return StereoAudioBuffer(outL, outR, main.sampleRate)
}

fun formatDurationMs(ms: Long): String {
    val totalSeconds = max(0L, ms / 1000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
