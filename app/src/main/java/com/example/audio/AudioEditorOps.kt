package com.example.audio

import android.content.Context
import com.example.audio.cache.SmartCacheManager
import com.example.diagnostics.AppDiagnosticLog
import com.example.diagnostics.FfmpegKitBridge
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
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

/**
 * Downsamples waveform peaks on a background worker thread so huge audio files
 * never block or lag the Compose UI thread.
 */
suspend fun computeWaveformPeaksAsync(
    buf: StereoAudioBuffer,
    buckets: Int = 800,
): List<Float> = withContext(Dispatchers.IO) {
    val total = buf.frameCount
    val safeBuckets = buckets.coerceIn(0, 1500)
    if (total == 0 || safeBuckets <= 0) return@withContext emptyList()
    val result = ArrayList<Float>(safeBuckets)
    val step = total.toDouble() / safeBuckets
    for (i in 0 until safeBuckets) {
        if (i % 32 == 0) kotlin.coroutines.coroutineContext.ensureActive()
        val start = clamp(floor(i * step).toInt(), 0, total - 1)
        val end = clamp(floor((i + 1) * step).toInt(), start + 1, total)
        var peak = 0f
        val stride = max(1, (end - start) / 48)
        var idx = start
        while (idx < end) {
            val amp = max(abs(buf.left[idx]), abs(buf.right[idx]))
            if (amp > peak) peak = amp
            idx += stride
        }
        result.add(clamp(peak, 0f, 1f))
    }
    result
}

/**
 * Normalizes any buffer to standard studio 44100 Hz stereo so merges/overlays
 * never suffer pitch shifts, slowdowns, or single-ear mono dropouts.
 */
fun normalizeToStudioStereo44100(buffer: StereoAudioBuffer): StereoAudioBuffer {
    val resampled = if (buffer.sampleRate != TARGET_SAMPLE_RATE) {
        resampleStereo(buffer, TARGET_SAMPLE_RATE)
    } else {
        buffer
    }
    val n = resampled.frameCount
    if (n <= 0) return resampled
    var leftEnergy = 0.0
    var rightEnergy = 0.0
    val step = max(1, n / 256)
    var i = 0
    while (i < n) {
        leftEnergy += abs(resampled.left[i])
        rightEnergy += abs(resampled.right[i])
        i += step
    }
    // Auto-match silent channel if source came from a broken single-ear mono rip
    if (leftEnergy > 1e-3 && rightEnergy < 1e-6) {
        return StereoAudioBuffer(resampled.left, resampled.left.copyOf(), TARGET_SAMPLE_RATE)
    }
    if (rightEnergy > 1e-3 && leftEnergy < 1e-6) {
        return StereoAudioBuffer(resampled.right.copyOf(), resampled.right, TARGET_SAMPLE_RATE)
    }
    return resampled
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
): StereoAudioBuffer = trimToSelectionWithFade(buffer, startFraction, endFraction, autoFadeSec = 0f)

fun trimToSelectionWithFade(
    buffer: StereoAudioBuffer,
    startFraction: Float,
    endFraction: Float,
    autoFadeSec: Float = 0.2f,
): StereoAudioBuffer {
    val total = buffer.frameCount
    if (total == 0) return buffer
    val s = clamp(floor(startFraction * total).toInt(), 0, total - 1)
    val e = clamp(floor(endFraction * total).toInt(), s + 1, total)
    val trimmed = StereoAudioBuffer(
        left = buffer.left.copyOfRange(s, e),
        right = buffer.right.copyOfRange(s, e),
        sampleRate = buffer.sampleRate,
    )
    return if (autoFadeSec > 0f) applyMicroEdgeFades(trimmed, autoFadeSec) else trimmed
}

fun cutSelection(
    buffer: StereoAudioBuffer,
    startFraction: Float,
    endFraction: Float,
): StereoAudioBuffer = cutSelectionWithFade(buffer, startFraction, endFraction, autoFadeSec = 0f)

fun cutSelectionWithFade(
    buffer: StereoAudioBuffer,
    startFraction: Float,
    endFraction: Float,
    autoFadeSec: Float = 0.2f,
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

    // Smooth boundary around the splice point s to avoid clicks/pops when autoFadeSec > 0
    if (autoFadeSec > 0f && s > 0 && s < remaining) {
        val joinFade = minOf(s, remaining - s, (autoFadeSec * buffer.sampleRate * 0.5f).roundToInt()).coerceAtLeast(1)
        for (i in 0 until joinFade) {
            val factorOut = cos((i.toDouble() / joinFade) * PI * 0.5).toFloat()
            val factorIn = sin(((i + 1).toDouble() / joinFade) * PI * 0.5).toFloat()
            outL[s - joinFade + i] *= factorOut
            outR[s - joinFade + i] *= factorOut
            outL[s + i] *= factorIn
            outR[s + i] *= factorIn
        }
    }

    val result = StereoAudioBuffer(outL, outR, buffer.sampleRate)
    return if (autoFadeSec > 0f) applyMicroEdgeFades(result, autoFadeSec * 0.5f) else result
}

fun applyMicroEdgeFades(
    buffer: StereoAudioBuffer,
    fadeSeconds: Float = 0.2f,
): StereoAudioBuffer {
    val total = buffer.frameCount
    if (total <= 16 || fadeSeconds <= 0f) return buffer
    val fadeFrames = min(total / 4, (fadeSeconds * buffer.sampleRate).roundToInt()).coerceAtLeast(1)
    val outL = buffer.left.copyOf()
    val outR = buffer.right.copyOf()
    val denom = max(1, fadeFrames - 1)
    for (i in 0 until fadeFrames) {
        val t = i.toDouble() / denom
        val gIn = sin(t * PI * 0.5).toFloat()
        val gOut = cos(t * PI * 0.5).toFloat()
        outL[i] *= gIn
        outR[i] *= gIn
        val tailIdx = total - fadeFrames + i
        outL[tailIdx] *= gOut
        outR[tailIdx] *= gOut
    }
    return StereoAudioBuffer(outL, outR, buffer.sampleRate)
}

/**
 * Heap-safe clip merge: merges in chunks without creating extra giant ByteArrays,
 * and guards against OOM cleanly by capping extreme lengths cleanly if needed.
 */
fun mergeClips(
    first: StereoAudioBuffer,
    second: StereoAudioBuffer,
    crossfadeMs: Int = 15,
    maxCrossfadeFraction: Float = 0.85f,
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

    val safeFrac = maxCrossfadeFraction.coerceIn(0.05f, 0.95f)
    val xfade = minOf(
        floor((crossfadeMs / 1000.0) * first.sampleRate).toInt(),
        max(0, (n1 * safeFrac).toInt()),
        max(0, (n2 * safeFrac).toInt()),
    ).coerceAtLeast(0)
    val totalOut = n1 + n2 - xfade
    val outL = FloatArray(totalOut)
    val outR = FloatArray(totalOut)

    val preLen = n1 - xfade
    var copied = 0
    val chunkFrames = 16_384 // 64KB equivalent stereo PCM frame chunk
    while (copied < preLen) {
        val len = min(chunkFrames, preLen - copied)
        System.arraycopy(first.left, copied, outL, copied, len)
        System.arraycopy(first.right, copied, outR, copied, len)
        copied += len
    }

    val overlapStart = n1 - xfade
    for (i in 0 until xfade) {
        val t = i.toFloat() / max(1, xfade)
        outL[overlapStart + i] = first.left[overlapStart + i] * (1f - t) + alignedSecond.left[i] * t
        outR[overlapStart + i] = first.right[overlapStart + i] * (1f - t) + alignedSecond.right[i] * t
    }

    val postLen = n2 - xfade
    var postCopied = 0
    while (postCopied < postLen) {
        val len = min(chunkFrames, postLen - postCopied)
        System.arraycopy(alignedSecond.left, xfade + postCopied, outL, n1 + postCopied, len)
        System.arraycopy(alignedSecond.right, xfade + postCopied, outR, n1 + postCopied, len)
        postCopied += len
    }
    return StereoAudioBuffer(outL, outR, first.sampleRate)
}

/**
 * Streams clip merging on Dispatchers.IO inside context.cacheDir using 64KB chunks
 * (or FFmpegKit concat/acrossfade when present) to prevent OutOfMemoryError on main thread.
 */
suspend fun mergeClipsStreamingOnDisk(
    context: Context,
    first: StereoAudioBuffer,
    second: StereoAudioBuffer,
    crossfadeMs: Int = 15,
    maxCrossfadeFraction: Float = 0.85f,
): StereoAudioBuffer = withContext(Dispatchers.IO) {
    val normFirst = normalizeToStudioStereo44100(first)
    val normSecond = normalizeToStudioStereo44100(second)
    val sr = TARGET_SAMPLE_RATE
    val workDir = File(context.cacheDir, "stream_ops").apply { mkdirs() }
    val id = UUID.randomUUID().toString().take(8)
    val wav1 = File(workDir, "merge1_$id.wav")
    val wav2 = File(workDir, "merge2_$id.wav")
    val outWav = File(workDir, "merged_$id.wav")

    try {
        writeStereoWavFile(wav1, normFirst)
        writeStereoWavFile(wav2, normSecond)

        val safeFrac = maxCrossfadeFraction.coerceIn(0.05f, 0.95f)
        val maxAllowedSec = min(normFirst.durationMs, normSecond.durationMs) / 1000f * safeFrac
        val xfadeSec = min(crossfadeMs / 1000f, maxAllowedSec).coerceAtLeast(0.01f)
        val ffmpegOk = if (FfmpegKitBridge.isAvailable()) {
            if (crossfadeMs >= 50 && xfadeSec >= 0.04f) {
                val cmd = "-y -i \"${wav1.absolutePath}\" -i \"${wav2.absolutePath}\" " +
                    "-filter_complex \"[0:a]aresample=44100,aformat=channel_layouts=stereo[a0];" +
                    "[1:a]aresample=44100,aformat=channel_layouts=stereo[a1];" +
                    "[a0][a1]acrossfade=d=${"%.2f".format(java.util.Locale.US, xfadeSec)}:c1=tri:c2=tri\" " +
                    "-ar $sr -ac 2 \"${outWav.absolutePath}\""
                FfmpegKitBridge.execute(cmd, toolName = "MergeClipsStreaming", logNonZeroFailure = false)
            } else {
                val cmd = "-y -i \"${wav1.absolutePath}\" -i \"${wav2.absolutePath}\" " +
                    "-filter_complex \"[0:a]aresample=44100,aformat=channel_layouts=stereo[a0];" +
                    "[1:a]aresample=44100,aformat=channel_layouts=stereo[a1];" +
                    "[a0][a1]concat=n=2:v=0:a=1\" " +
                    "-ar $sr -ac 2 \"${outWav.absolutePath}\""
                FfmpegKitBridge.execute(cmd, toolName = "MergeClipsConcat", logNonZeroFailure = false)
            }
        } else {
            false
        }

        val mergedReady = if (ffmpegOk && outWav.exists() && outWav.length() > 44L) {
            true
        } else {
            mergeWavFilesStreaming(
                wav1,
                wav2,
                outWav,
                crossfadeMs = crossfadeMs,
                sampleRate = sr,
                maxCrossfadeFraction = safeFrac,
            )
        }

        if (mergedReady && outWav.exists() && outWav.length() > 44L) {
            AudioDecoder.decodeFile(outWav) ?: mergeClips(normFirst, normSecond, crossfadeMs, safeFrac)
        } else {
            mergeClips(normFirst, normSecond, crossfadeMs, safeFrac)
        }
    } catch (oom: OutOfMemoryError) {
        AppDiagnosticLog.logRecoverable(
            toolName = "MergeClips",
            titleAr = "تم حماية الذاكرة أثناء دمج ملفات كبيرة",
            titleEn = "Memory Guard During Large Audio Merge",
            causeAr = "المقطع المدمج كبير جداً بالنسبة للذاكرة المتاحة؛ يرجى تقسيم أو قص أحد المقاطع.",
            causeEn = "Merged audio size exceeded available heap memory.",
            errorCode = "OOM_GUARD",
            throwable = oom,
        )
        normFirst
    } finally {
        runCatching { wav1.delete() }
        runCatching { wav2.delete() }
        runCatching { outWav.delete() }
        SmartCacheManager.pruneManagedBuckets(context)
    }
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
    return "%02d:%02d".format(java.util.Locale.US, minutes, seconds)
}

fun formatDurationCentisMs(ms: Long): String {
    val clamped = max(0L, ms)
    val totalCentis = clamped / 10L
    val centis = totalCentis % 100L
    val totalSeconds = totalCentis / 100L
    val seconds = totalSeconds % 60L
    val minutes = totalSeconds / 60L
    return "%02d:%02d.%02d".format(java.util.Locale.US, minutes, seconds, centis)
}
