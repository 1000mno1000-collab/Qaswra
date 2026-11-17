package com.example.audio

import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.example.diagnostics.AppDiagnosticLog
import com.example.diagnostics.FfmpegKitBridge
import com.example.model.GlobalProPreset
import com.example.model.GlobalProPresetId
import com.example.model.PitchShiftParams
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.Locale
import java.util.Random
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
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Adds studio-grade vocal mastering warmth, compression, 8kHz air, and gentle loudness normalization.
 */
fun applyStudioWarmth(
    buffer: StereoAudioBuffer,
    warmthDb: Float = 2.5f,
    presenceDb: Float = 2.2f,
): StereoAudioBuffer = com.example.audio.fx.AudioSkillsPresets.applyStudioVocalMastering(
    buffer = buffer,
    extraWarmthDb = (warmthDb - 2.5f).coerceAtLeast(-2f),
    extraPresenceDb = (presenceDb - 1.5f).coerceAtLeast(-2f),
)

/**
 * Applies a Global Pro Preset with dedicated post-DSP shaping.
 */
suspend fun renderGlobalProPreset(
    buffer: StereoAudioBuffer,
    preset: GlobalProPreset,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val base = renderOfflineDsp(buffer, preset.dspConfig)
    if (base.frameCount <= 0) return@withContext base
    if (preset.id == GlobalProPresetId.BROADCAST_RADIO || preset.id == GlobalProPresetId.CRYSTAL_VOCAL) {
        normalizeAudio(base, -1.0f)
    } else {
        normalizeAudio(base, -0.8f)
    }
}

fun reverseAudio(buffer: StereoAudioBuffer): StereoAudioBuffer {
    val n = buffer.frameCount
    if (n <= 1) return buffer
    val outL = FloatArray(n)
    val outR = FloatArray(n)
    for (i in 0 until n) {
        outL[i] = buffer.left[n - 1 - i]
        outR[i] = buffer.right[n - 1 - i]
    }
    return StereoAudioBuffer(outL, outR, buffer.sampleRate)
}

fun generateSilence(
    durationSeconds: Float,
    sampleRate: Int = TARGET_SAMPLE_RATE,
): StereoAudioBuffer {
    val frames = max(sampleRate / 10, (clamp(durationSeconds, 0.5f, 120f) * sampleRate).roundToInt())
    return StereoAudioBuffer(
        left = FloatArray(frames),
        right = FloatArray(frames),
        sampleRate = sampleRate,
    )
}

/**
 * Inserts or generates silence on Dispatchers.IO via 64KB chunked disk streaming in context.cacheDir,
 * avoiding massive heap allocations or blocking the main thread.
 */
suspend fun insertSilenceStreaming(
    context: Context,
    currentBuffer: StereoAudioBuffer?,
    durationSeconds: Float,
    appendToCurrent: Boolean,
): StereoAudioBuffer = withContext(Dispatchers.IO) {
    val sr = currentBuffer?.sampleRate?.coerceAtLeast(8000) ?: TARGET_SAMPLE_RATE
    val workDir = File(context.cacheDir, "silence_stream").apply { mkdirs() }
    val id = UUID.randomUUID().toString().take(8)
    val silenceWav = File(workDir, "sil_$id.wav")
    val curWav = File(workDir, "cur_$id.wav")
    val outWav = File(workDir, "sil_out_$id.wav")

    try {
        writeSilenceWavStreaming(silenceWav, durationSeconds, sr)
        if (!appendToCurrent || currentBuffer == null || currentBuffer.frameCount <= 0) {
            return@withContext AudioDecoder.decodeFile(silenceWav) ?: generateSilence(durationSeconds, sr)
        }
        writeStereoWavFile(curWav, currentBuffer)
        val mergedOk = mergeWavFilesStreaming(curWav, silenceWav, outWav, crossfadeMs = 5, sampleRate = sr)
        if (mergedOk && outWav.exists() && outWav.length() > 44L) {
            AudioDecoder.decodeFile(outWav) ?: mergeClips(currentBuffer, generateSilence(durationSeconds, sr), crossfadeMs = 5)
        } else {
            mergeClips(currentBuffer, generateSilence(durationSeconds, sr), crossfadeMs = 5)
        }
    } finally {
        runCatching { silenceWav.delete() }
        runCatching { curWav.delete() }
        runCatching { outWav.delete() }
    }
}

/**
 * Surround Stereo M/S processor whose sliders start cleanly at 0% (neutral).
 * - midAdjustPct: -60f .. +60f (default 0% -> 1.0x center)
 * - sideBoostPct: 0f .. +120f (default 0% -> 1.0x side)
 * - widthPct: 0f .. 100f (default 0% -> 0ms Haas width)
 */
fun convertToSurroundStereoFromOffsets(
    buffer: StereoAudioBuffer,
    midAdjustPct: Float = 0f,
    sideBoostPct: Float = 0f,
    widthPct: Float = 0f,
): StereoAudioBuffer {
    if (abs(midAdjustPct) < 0.05f && abs(sideBoostPct) < 0.05f && abs(widthPct) < 0.05f) {
        return buffer
    }
    val midLevel = (1f + midAdjustPct / 100f).coerceIn(0.35f, 1.65f)
    val sideLevel = (1f + sideBoostPct / 100f).coerceIn(0.5f, 2.3f)
    val stereoWidth = (widthPct / 100f).coerceIn(0f, 1f)
    return convertToSurroundStereo(buffer, midLevel, sideLevel, stereoWidth)
}

fun convertToSurroundStereo(
    buffer: StereoAudioBuffer,
    midLevel: Float = 1.0f,
    sideLevel: Float = 1.35f,
    stereoWidth: Float = 0.78f,
): StereoAudioBuffer {
    val n = buffer.frameCount
    if (n == 0) return buffer
    val haasSamples = if (stereoWidth <= 0.005f) {
        0
    } else {
        max(1, (0.011f * buffer.sampleRate * clamp(stereoWidth, 0f, 1f)).roundToInt())
    }
    val outL = FloatArray(n)
    val outR = FloatArray(n)

    for (i in 0 until n) {
        val rawL = buffer.left[i]
        val rawR = buffer.right[i]
        val delayedR = if (haasSamples == 0) rawR else buffer.right[max(0, i - haasSamples)]
        val mid = 0.5f * (rawL + rawR) * midLevel
        val naturalSide = 0.5f * (rawL - rawR)
        val haasSide = if (haasSamples == 0) 0f else 0.5f * (rawL - delayedR) * clamp(stereoWidth, 0f, 1f)
        val side = (naturalSide + haasSide * 0.65f) * sideLevel
        outL[i] = clamp(mid + side, -1f, 1f)
        outR[i] = clamp(mid - side, -1f, 1f)
    }
    return normalizeAudio(StereoAudioBuffer(outL, outR, buffer.sampleRate), -1.0f)
}

fun splitAudioByTime(
    buffer: StereoAudioBuffer,
    splitFraction: Float,
): Pair<StereoAudioBuffer, StereoAudioBuffer> {
    val n = buffer.frameCount
    if (n < 4) return buffer to buffer
    val splitIdx = clamp(floor(splitFraction * n).toInt(), 1, n - 1)
    val first = StereoAudioBuffer(
        left = buffer.left.copyOfRange(0, splitIdx),
        right = buffer.right.copyOfRange(0, splitIdx),
        sampleRate = buffer.sampleRate,
    )
    val second = StereoAudioBuffer(
        left = buffer.left.copyOfRange(splitIdx, n),
        right = buffer.right.copyOfRange(splitIdx, n),
        sampleRate = buffer.sampleRate,
    )
    return first to second
}

fun splitStereoChannels(buffer: StereoAudioBuffer): Pair<StereoAudioBuffer, StereoAudioBuffer> {
    val leftOnly = StereoAudioBuffer(
        left = buffer.left.copyOf(),
        right = buffer.left.copyOf(),
        sampleRate = buffer.sampleRate,
    )
    val rightOnly = StereoAudioBuffer(
        left = buffer.right.copyOf(),
        right = buffer.right.copyOf(),
        sampleRate = buffer.sampleRate,
    )
    return leftOnly to rightOnly
}

suspend fun applyPitchAndSpeedOffline(
    buffer: StereoAudioBuffer,
    semitones: Float,
    tempoRatio: Float,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val base = com.example.model.DspConfig()
        .withSlotEnabled(com.example.model.EffectType.PITCH_SHIFT, abs(semitones) >= 0.05f)
        .withSlotEnabled(com.example.model.EffectType.TIME_STRETCH, abs(tempoRatio - 1f) >= 0.02f)
        .copy(
            pitchShift = PitchShiftParams(semitones = clamp(semitones, -12f, 12f), wetMix = 1f),
            timeStretch = com.example.model.TimeStretchParams(tempoRatio = clamp(tempoRatio, 0.5f, 2.0f)),
        )
    renderOfflineDsp(buffer, base)
}

enum class MergeStudioMode {
    SEQUENTIAL,
    CROSSFADE,
    OVERLAY,
}

data class MergeStudioParams(
    val mode: MergeStudioMode = MergeStudioMode.SEQUENTIAL,
    val crossfadeSec: Float = 2.0f,         // 0.1 .. 15.0s
    val overlayFadeInSec: Float = 0.0f,     // 0.0 .. 15.0s
    val overlayFadeOutSec: Float = 0.0f,    // 0.0 .. 15.0s
    val overlayStartOffsetSec: Float = 0.0f,// 0.0 .. 60.0s
    val primaryLevelPct: Float = 0.0f,      // -60% .. +60% (0% = 1.0x)
    val secondaryLevelPct: Float = 0.0f,    // -60% .. +60% (0% = 1.0x overlay/bg or clip2)
    val autoDuckingEnabled: Boolean = false,
    val duckingStrengthPct: Float = 0.0f,   // 0% .. 100%
    val autoLevelingEnabled: Boolean = false,
    val levelingStrengthPct: Float = 0.0f,  // 0% .. 100%
    val echoTransitionEnabled: Boolean = false,
    val echoTransitionPct: Float = 0.0f,    // 0% .. 100%
    val gapWhooshEnabled: Boolean = false,
    val transitionGapSec: Float = 0.0f,     // 0.0 .. 10.0s
    val whooshIntensityPct: Float = 0.0f,   // 0% .. 100%
)

/**
 * Unified Merge & Overlay Studio processor:
 * - Sequential merge (with optional Echo transition & Gap + Whoosh transition)
 * - Smart End-to-Start Crossfade (0.1s..15.0s via FFmpegKit `acrossfade` or smooth Kotlin crossfade)
 * - Free Overlay & Mix with Fade-In, Fade-Out, Start-Offset, Auto-Ducking, & Auto-Leveling (`loudnorm`)
 */
suspend fun executeUnifiedMergeOverlay(
    context: Context,
    primary: StereoAudioBuffer,
    secondary: StereoAudioBuffer,
    params: MergeStudioParams,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val normPrimary = normalizeToStudioStereo44100(primary)
    val alignedSec = normalizeToStudioStereo44100(secondary)
    val sr = TARGET_SAMPLE_RATE

    val gain1 = (1f + params.primaryLevelPct / 100f).coerceIn(0.1f, 2.0f)
    val gain2 = (1f + params.secondaryLevelPct / 100f).coerceIn(0.1f, 2.0f)

    var p1 = if (abs(gain1 - 1f) > 0.01f) StereoAudioBuffer(
        FloatArray(normPrimary.frameCount) { clamp(normPrimary.left[it] * gain1, -1f, 1f) },
        FloatArray(normPrimary.frameCount) { clamp(normPrimary.right[it] * gain1, -1f, 1f) },
        sr,
    ) else normPrimary

    var p2 = if (abs(gain2 - 1f) > 0.01f) StereoAudioBuffer(
        FloatArray(alignedSec.frameCount) { clamp(alignedSec.left[it] * gain2, -1f, 1f) },
        FloatArray(alignedSec.frameCount) { clamp(alignedSec.right[it] * gain2, -1f, 1f) },
        sr,
    ) else alignedSec

    // Service 2: Smart Auto-Leveling (equalize perceived loudness of both clips before combining)
    if (params.autoLevelingEnabled) {
        val blend = clamp(params.levelingStrengthPct / 100f, 0f, 1f).let { if (it < 0.01f) 0.7f else it }
        p1 = applySmartAutoLevel(p1, strength = blend)
        p2 = applySmartAutoLevel(p2, strength = blend)
    }

    // Service 3: Echo & Beat Transition on tail of clip 1
    if (params.echoTransitionEnabled && params.mode != MergeStudioMode.OVERLAY) {
        val mix = clamp(params.echoTransitionPct / 100f, 0f, 1f).let { if (it < 0.01f) 0.55f else it }
        p1 = applyTailEchoTransition(p1, intensity = mix)
    }

    val combined = when (params.mode) {
        MergeStudioMode.SEQUENTIAL -> {
            val midGap = if (params.gapWhooshEnabled && params.transitionGapSec > 0.05f) {
                synthesizeTransitionGapWhoosh(
                    durationSec = params.transitionGapSec,
                    whooshIntensity = clamp(params.whooshIntensityPct / 100f, 0f, 1f),
                    sampleRate = sr,
                )
            } else {
                null
            }
            if (midGap != null) {
                val p1PlusGap = mergeClipsStreamingOnDisk(context, p1, midGap, crossfadeMs = 40)
                mergeClipsStreamingOnDisk(context, p1PlusGap, p2, crossfadeMs = 40)
            } else {
                mergeClipsStreamingOnDisk(context, p1, p2, crossfadeMs = 20)
            }
        }

        MergeStudioMode.CROSSFADE -> {
            val xfadeMs = (clamp(params.crossfadeSec, 0.1f, 15.0f) * 1000f).roundToInt()
            if (params.gapWhooshEnabled && params.whooshIntensityPct > 2f) {
                val merged = mergeClipsStreamingOnDisk(context, p1, p2, crossfadeMs = xfadeMs)
                overlayWhooshAtFrame(
                    target = merged,
                    centerFrame = (p1.frameCount - (xfadeMs * sr / 2000)).coerceIn(0, merged.frameCount - 1),
                    whooshIntensity = clamp(params.whooshIntensityPct / 100f, 0f, 1f),
                )
            } else {
                mergeClipsStreamingOnDisk(context, p1, p2, crossfadeMs = xfadeMs)
            }
        }

        MergeStudioMode.OVERLAY -> {
            var shapedSec = p2
            if (params.overlayFadeInSec > 0.05f) {
                shapedSec = applyFadeIn(shapedSec, params.overlayFadeInSec)
            }
            if (params.overlayFadeOutSec > 0.05f) {
                shapedSec = applyFadeOut(shapedSec, params.overlayFadeOutSec)
            }
            val offsetFrames = (clamp(params.overlayStartOffsetSec, 0f, 60f) * sr).roundToInt()
            if (params.autoDuckingEnabled) {
                val duckStrength = clamp(params.duckingStrengthPct / 100f, 0f, 1f).let { if (it < 0.01f) 0.7f else it }
                mixWithSidechainAutoDuckingWithOffset(
                    context = context,
                    voice = p1,
                    music = shapedSec,
                    offsetFrames = offsetFrames,
                    musicBaseVolume = (0.45f * gain2).coerceIn(0.1f, 1.3f),
                    duckDepth = duckStrength,
                )
            } else {
                overlayWithOffset(p1, shapedSec, offsetFrames = offsetFrames)
            }
        }
    }

    if (params.autoLevelingEnabled) {
        val id = UUID.randomUUID().toString().take(8)
        val dir = File(context.cacheDir, "stream_ops").apply { mkdirs() }
        val inF = File(dir, "ln_in_$id.wav")
        val outF = File(dir, "ln_out_$id.wav")
        try {
            writeStereoWavFile(inF, combined)
            val cmd = "-y -i \"${inF.absolutePath}\" -af \"loudnorm=I=-14:TP=-1.0:LRA=11\" -ar $sr -ac 2 \"${outF.absolutePath}\""
            val ok = FfmpegKitBridge.execute(cmd, toolName = "AutoLeveling", logNonZeroFailure = false)
            if (ok && outF.exists() && outF.length() > 44L) {
                AudioDecoder.decodeFile(outF) ?: normalizeAudio(combined, -1.0f)
            } else {
                normalizeAudio(combined, -1.0f)
            }
        } finally {
            runCatching { inF.delete() }
            runCatching { outF.delete() }
        }
    } else {
        combined
    }
}

private fun applySmartAutoLevel(buffer: StereoAudioBuffer, strength: Float): StereoAudioBuffer {
    val n = buffer.frameCount
    if (n <= 0 || strength <= 0.01f) return buffer
    var sumSq = 0.0
    for (i in 0 until n) {
        val m = 0.5f * (buffer.left[i] + buffer.right[i])
        sumSq += m * m
    }
    val rms = sqrt(sumSq / n).toFloat().coerceAtLeast(1e-4f)
    val targetRms = 0.22f
    val rawGain = (targetRms / rms).coerceIn(0.35f, 3.2f)
    val gain = 1f + (rawGain - 1f) * strength
    val outL = FloatArray(n) { i -> tanh((buffer.left[i] * gain).toDouble()).toFloat() }
    val outR = FloatArray(n) { i -> tanh((buffer.right[i] * gain).toDouble()).toFloat() }
    return StereoAudioBuffer(outL, outR, buffer.sampleRate)
}

private fun applyTailEchoTransition(buffer: StereoAudioBuffer, intensity: Float): StereoAudioBuffer {
    val n = buffer.frameCount
    val sr = buffer.sampleRate.coerceAtLeast(8000)
    if (n < sr / 4 || intensity <= 0.01f) return buffer
    val tailStart = max(0, n - (1.8f * sr).roundToInt())
    val extraTail = (1.1f * sr).roundToInt()
    val outFrames = n + extraTail
    val outL = FloatArray(outFrames)
    val outR = FloatArray(outFrames)
    System.arraycopy(buffer.left, 0, outL, 0, n)
    System.arraycopy(buffer.right, 0, outR, 0, n)

    val taps = listOf(
        (0.24f * sr).roundToInt() to 0.52f * intensity,
        (0.48f * sr).roundToInt() to 0.34f * intensity,
        (0.76f * sr).roundToInt() to 0.20f * intensity,
    )
    for ((delay, gain) in taps) {
        for (i in tailStart until n) {
            val dst = i + delay
            if (dst < outFrames) {
                val env = (i - tailStart).toFloat() / max(1, n - tailStart)
                outL[dst] = clamp(outL[dst] + buffer.left[i] * gain * env, -1f, 1f)
                outR[dst] = clamp(outR[dst] + buffer.right[i] * gain * env, -1f, 1f)
            }
        }
    }
    return StereoAudioBuffer(outL, outR, sr)
}

private fun synthesizeTransitionGapWhoosh(
    durationSec: Float,
    whooshIntensity: Float,
    sampleRate: Int,
): StereoAudioBuffer {
    val frames = max(sampleRate / 10, (clamp(durationSec, 0.05f, 10f) * sampleRate).roundToInt())
    val outL = FloatArray(frames)
    val outR = FloatArray(frames)
    if (whooshIntensity <= 0.02f) return StereoAudioBuffer(outL, outR, sampleRate)

    val rng = Random(42L)
    var lpL = 0f
    var lpR = 0f
    for (i in 0 until frames) {
        val pos = i.toFloat() / max(1, frames - 1)
        val env = sin(pos * PI).toFloat().pow(1.4f)
        val alpha = 0.03f + 0.32f * env
        val nL = rng.nextFloat() * 2f - 1f
        val nR = rng.nextFloat() * 2f - 1f
        lpL += alpha * (nL - lpL)
        lpR += alpha * (nR - lpR)
        val panL = cos(pos * PI * 0.5).toFloat()
        val panR = sin(pos * PI * 0.5).toFloat()
        val scale = 0.28f * whooshIntensity * env
        outL[i] = clamp(lpL * scale * (0.6f + panL * 0.6f), -1f, 1f)
        outR[i] = clamp(lpR * scale * (0.6f + panR * 0.6f), -1f, 1f)
    }
    return StereoAudioBuffer(outL, outR, sampleRate)
}

private fun overlayWhooshAtFrame(
    target: StereoAudioBuffer,
    centerFrame: Int,
    whooshIntensity: Float,
): StereoAudioBuffer {
    val sr = target.sampleRate
    val whoosh = synthesizeTransitionGapWhoosh(1.1f, whooshIntensity, sr)
    val start = max(0, centerFrame - whoosh.frameCount / 2)
    val outL = target.left.copyOf()
    val outR = target.right.copyOf()
    for (i in 0 until whoosh.frameCount) {
        val dst = start + i
        if (dst >= outL.size) break
        outL[dst] = tanh((outL[dst] + whoosh.left[i]).toDouble()).toFloat()
        outR[dst] = tanh((outR[dst] + whoosh.right[i]).toDouble()).toFloat()
    }
    return StereoAudioBuffer(outL, outR, sr)
}

private fun overlayWithOffset(
    primary: StereoAudioBuffer,
    secondary: StereoAudioBuffer,
    offsetFrames: Int,
): StereoAudioBuffer {
    val safeOffset = max(0, offsetFrames)
    val outFrames = max(primary.frameCount, safeOffset + secondary.frameCount)
    val outL = FloatArray(outFrames)
    val outR = FloatArray(outFrames)
    System.arraycopy(primary.left, 0, outL, 0, primary.frameCount)
    System.arraycopy(primary.right, 0, outR, 0, primary.frameCount)
    for (i in 0 until secondary.frameCount) {
        val dst = safeOffset + i
        outL[dst] = tanh((outL[dst] + secondary.left[i]).toDouble()).toFloat()
        outR[dst] = tanh((outR[dst] + secondary.right[i]).toDouble()).toFloat()
    }
    return StereoAudioBuffer(outL, outR, primary.sampleRate)
}

suspend fun mixWithSidechainAutoDuckingWithOffset(
    context: Context,
    voice: StereoAudioBuffer,
    music: StereoAudioBuffer,
    offsetFrames: Int,
    musicBaseVolume: Float = 0.4f,
    duckDepth: Float = 0.7f,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val sr = voice.sampleRate.coerceAtLeast(8000)
    val safeOffset = max(0, offsetFrames)
    val totalFrames = max(voice.frameCount, safeOffset + music.frameCount)
    val alignedMusic = if (music.sampleRate == sr) music else resampleStereo(music, sr)

    // Attempt FFmpegKit sidechaincompress when offset is 0 and FFmpegKit is bundled
    if (safeOffset == 0 && FfmpegKitBridge.isAvailable()) {
        val id = UUID.randomUUID().toString().take(8)
        val dir = File(context.cacheDir, "stream_ops").apply { mkdirs() }
        val vWav = File(dir, "sc_v_$id.wav")
        val mWav = File(dir, "sc_m_$id.wav")
        val outWav = File(dir, "sc_out_$id.wav")
        try {
            writeStereoWavFile(vWav, voice)
            writeStereoWavFile(mWav, alignedMusic)
            val ratio = (2.0f + 8.0f * duckDepth).coerceIn(2f, 12f)
            val cmd = "-y -i \"${vWav.absolutePath}\" -i \"${mWav.absolutePath}\" " +
                "-filter_complex \"[1:a]volume=${"%.2f".format(Locale.US, musicBaseVolume)}[bg0];" +
                "[bg0][0:a]sidechaincompress=threshold=0.02:ratio=${"%.1f".format(Locale.US, ratio)}:attack=180:release=900[bg];" +
                "[0:a][bg]amix=inputs=2:duration=longest:normalize=0\" -ar $sr -ac 2 \"${outWav.absolutePath}\""
            val ok = FfmpegKitBridge.execute(cmd, toolName = "AutoDucking", logNonZeroFailure = false)
            if (ok && outWav.exists() && outWav.length() > 44L) {
                AudioDecoder.decodeFile(outWav)?.let { return@withContext it }
            }
        } finally {
            runCatching { vWav.delete() }
            runCatching { mWav.delete() }
            runCatching { outWav.delete() }
        }
    }

    val outL = FloatArray(totalFrames)
    val outR = FloatArray(totalFrames)
    val attackCoeff = kotlin.math.exp(-1.0 / (180f * 0.001f * sr)).toFloat()
    val releaseCoeff = kotlin.math.exp(-1.0 / (900f * 0.001f * sr)).toFloat()
    val safeThresh = 0.02f
    val minGain = (1f - duckDepth * 0.85f).coerceIn(0.12f, 1.0f)

    var env = 0f
    var duckGain = 1.0f
    val gainAttack = kotlin.math.exp(-1.0 / (0.045f * sr)).toFloat()
    val gainRelease = kotlin.math.exp(-1.0 / (0.350f * sr)).toFloat()

    for (i in 0 until totalFrames) {
        if (i % 16384 == 0) yield()
        val vL = if (i < voice.frameCount) voice.left[i] else 0f
        val vR = if (i < voice.frameCount) voice.right[i] else 0f
        val det = max(abs(vL), abs(vR))
        env = if (det > env) attackCoeff * env + (1f - attackCoeff) * det else releaseCoeff * env + (1f - releaseCoeff) * det
        val targetDuck = if (env > safeThresh) {
            val comp = (env / safeThresh).toDouble().pow(1.0 / (2.0 + 6.0 * duckDepth) - 1.0).toFloat()
            clamp(comp, minGain, 1.0f)
        } else {
            1.0f
        }
        duckGain = if (targetDuck < duckGain) {
            gainAttack * duckGain + (1f - gainAttack) * targetDuck
        } else {
            gainRelease * duckGain + (1f - gainRelease) * targetDuck
        }

        val mIdx = i - safeOffset
        val bgL = if (mIdx in 0 until alignedMusic.frameCount) alignedMusic.left[mIdx] * musicBaseVolume * duckGain else 0f
        val bgR = if (mIdx in 0 until alignedMusic.frameCount) alignedMusic.right[mIdx] * musicBaseVolume * duckGain else 0f
        outL[i] = tanh((vL + bgL).toDouble()).toFloat()
        outR[i] = tanh((vR + bgR).toDouble()).toFloat()
    }
    StereoAudioBuffer(outL, outR, sr)
}

/**
 * Smart Background Music Mixer with Sidechain Auto-Ducking.
 */
suspend fun mixWithSidechainAutoDucking(
    voice: StereoAudioBuffer,
    music: StereoAudioBuffer,
    musicBaseVolume: Float = 0.28f,
    voiceGain: Float = 1.0f,
    threshold: Float = 0.02f,
    ratio: Float = 6.0f,
    attackMs: Float = 200f,
    releaseMs: Float = 1000f,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val n = voice.frameCount
    if (n <= 0) return@withContext voice
    val sr = voice.sampleRate.coerceAtLeast(8000)

    val alignedMusic = if (music.sampleRate == sr) music else resampleStereo(music, sr)
    val musicFrames = alignedMusic.frameCount
    if (musicFrames <= 0) return@withContext applyGainDb(voice, (20.0 * kotlin.math.log10(max(voiceGain, 0.01f))).toFloat())

    val outL = FloatArray(n)
    val outR = FloatArray(n)

    val attackCoeff = kotlin.math.exp(-1.0 / (max(1f, attackMs) * 0.001f * sr)).toFloat()
    val releaseCoeff = kotlin.math.exp(-1.0 / (max(1f, releaseMs) * 0.001f * sr)).toFloat()
    val safeThresh = max(0.002f, threshold)
    val safeRatio = max(1.0f, ratio)
    val baseBg = clamp(musicBaseVolume, 0f, 1.5f)
    val fadeLoopFrames = min(sr / 4, musicFrames / 8)

    var env = 0f
    var duckGain = 1.0f
    val gainAttackCoeff = kotlin.math.exp(-1.0 / (0.045f * sr)).toFloat()
    val gainReleaseCoeff = kotlin.math.exp(-1.0 / (0.350f * sr)).toFloat()

    for (i in 0 until n) {
        if (i % 16384 == 0) yield()
        val vL = voice.left[i] * voiceGain
        val vR = voice.right[i] * voiceGain
        val detector = max(abs(vL), abs(vR))

        env = if (detector > env) {
            attackCoeff * env + (1f - attackCoeff) * detector
        } else {
            releaseCoeff * env + (1f - releaseCoeff) * detector
        }

        val targetDuck = if (env > safeThresh) {
            val overRatio = env / safeThresh
            val compressed = overRatio.toDouble().pow(1.0 / safeRatio - 1.0).toFloat()
            clamp(compressed, 0.14f, 1.0f)
        } else {
            1.0f
        }

        duckGain = if (targetDuck < duckGain) {
            gainAttackCoeff * duckGain + (1f - gainAttackCoeff) * targetDuck
        } else {
            gainReleaseCoeff * duckGain + (1f - gainReleaseCoeff) * targetDuck
        }

        val mIdx = i % musicFrames
        val loopEnv = if (musicFrames > fadeLoopFrames * 2 && fadeLoopFrames > 0) {
            when {
                mIdx < fadeLoopFrames -> mIdx.toFloat() / fadeLoopFrames
                mIdx > musicFrames - fadeLoopFrames -> (musicFrames - mIdx).toFloat() / fadeLoopFrames
                else -> 1f
            }
        } else {
            1f
        }

        val bgScale = baseBg * duckGain * loopEnv
        val bgL = alignedMusic.left[mIdx] * bgScale
        val bgR = alignedMusic.right[mIdx] * bgScale

        outL[i] = tanh((vL + bgL).toDouble()).toFloat()
        outR[i] = tanh((vR + bgR).toDouble()).toFloat()
    }

    StereoAudioBuffer(outL, outR, sr)
}

sealed interface RingtoneSaveResult {
    data class Saved(val uri: Uri, val appliedAsDefault: Boolean, val needsWriteSettings: Boolean) : RingtoneSaveResult
    data class Error(val message: String) : RingtoneSaveResult
}

suspend fun saveAndSetRingtone(
    context: Context,
    buffer: StereoAudioBuffer,
    title: String,
    setAsDefault: Boolean,
): RingtoneSaveResult = withContext(Dispatchers.IO) {
    val cleanTitle = title.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Sonix_Ringtone" }
    val exported = exportRenderedAudioToFile(
        context = context,
        buffer = buffer,
        format = com.example.model.ExportAudioFormat.M4A,
        baseFileName = cleanTitle,
        bitrateKbps = 320,
    )

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "${cleanTitle}.${exported.extension}")
        put(MediaStore.MediaColumns.TITLE, cleanTitle)
        put(MediaStore.MediaColumns.MIME_TYPE, exported.mimeType)
        put(MediaStore.Audio.Media.IS_RINGTONE, 1)
        put(MediaStore.Audio.Media.IS_NOTIFICATION, 0)
        put(MediaStore.Audio.Media.IS_ALARM, 0)
        put(MediaStore.Audio.Media.IS_MUSIC, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val uri = runCatching {
        resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull() ?: return@withContext RingtoneSaveResult.Error("تعذر حفظ النغمة في مجلد الرنين")

    return@withContext try {
        resolver.openOutputStream(uri)?.use { out ->
            exported.file.inputStream().use { inp -> inp.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, finish, null, null)
        }
        var applied = false
        var needsPerm = false
        if (setAsDefault) {
            if (Settings.System.canWrite(context)) {
                runCatching {
                    RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, uri)
                    applied = true
                }
            } else {
                needsPerm = true
            }
        }
        RingtoneSaveResult.Saved(uri = uri, appliedAsDefault = applied, needsWriteSettings = needsPerm)
    } catch (t: Throwable) {
        RingtoneSaveResult.Error(t.message ?: "خطأ أثناء كتابة ملف النغمة")
    }
}

suspend fun stageLargeFileToCleanAsciiCache(
    context: Context,
    uploadedFile: File,
    prefix: String = "large",
): File = withContext(Dispatchers.IO) {
    val stagedDir = File(context.cacheDir, "staged_inputs").apply { mkdirs() }
    val cleanExt = uploadedFile.extension.lowercase()
        .replace(Regex("[^a-z0-9]"), "")
        .take(8)
        .ifBlank { "audio" }
    val target = File(stagedDir, "${prefix}_${UUID.randomUUID()}.$cleanExt")
    if (uploadedFile.renameTo(target) && target.exists() && target.length() > 0L) {
        return@withContext target
    }
    uploadedFile.inputStream().buffered(65_536).use { inp ->
        target.outputStream().buffered(65_536).use { out ->
            inp.copyTo(out, 65_536)
        }
    }
    runCatching { uploadedFile.delete() }
    target
}

suspend fun processAudioFileWithFfmpeg(
    context: Context,
    inputFile: File,
    audioFilter: String,
    toolName: String,
    preInputArgs: String = "",
    postInputArgs: String = "",
): File? = withContext(Dispatchers.IO) {
    if (!inputFile.exists() || !FfmpegKitBridge.isAvailable()) return@withContext null
    val safeIn = com.example.audio.cache.SmartCacheManager.ensureSafeFfmpegInputFile(context, inputFile)
    val stagedDir = File(context.cacheDir, "staged_inputs").apply { mkdirs() }
    val outFile = File(stagedDir, "ff_proc_${UUID.randomUUID()}.m4a")
    val filterPart = if (audioFilter.isNotBlank()) "-af \"$audioFilter\"" else ""
    val cmd = buildString {
        append("-y ")
        if (preInputArgs.isNotBlank()) append("$preInputArgs ")
        append("-i \"${safeIn.absolutePath}\" ")
        if (postInputArgs.isNotBlank()) append("$postInputArgs ")
        if (filterPart.isNotBlank()) append("$filterPart ")
        append("-vn -ar 44100 -ac 2 -c:a aac -b:a 192k \"${outFile.absolutePath}\"")
    }
    val ok = FfmpegKitBridge.execute(cmd, toolName = toolName, logNonZeroFailure = true)
    if (safeIn != inputFile) runCatching { safeIn.delete() }
    if (ok && outFile.exists() && outFile.length() > 512L) {
        outFile
    } else {
        runCatching { outFile.delete() }
        null
    }
}

suspend fun mixAudioFilesWithFfmpeg(
    context: Context,
    primaryFile: File,
    secondaryFile: File,
    primaryGain: Float = 1f,
    secondaryGain: Float = 0.25f,
    autoDucking: Boolean = false,
    mode: MergeStudioMode = MergeStudioMode.OVERLAY,
    crossfadeSec: Float = 2f,
    offsetSec: Float = 0f,
    outFile: File? = null,
): File? = withContext(Dispatchers.IO) {
    if (!primaryFile.exists() || !secondaryFile.exists() || !FfmpegKitBridge.isAvailable()) {
        return@withContext null
    }
    val safe1 = com.example.audio.cache.SmartCacheManager.ensureSafeFfmpegInputFile(context, primaryFile)
    val safe2 = com.example.audio.cache.SmartCacheManager.ensureSafeFfmpegInputFile(context, secondaryFile)
    val stagedDir = File(context.cacheDir, "staged_inputs").apply { mkdirs() }
    val target = outFile ?: File(stagedDir, "ff_mix_${UUID.randomUUID()}.m4a")
    val g1 = String.format(Locale.US, "%.2f", primaryGain.coerceIn(0.1f, 2.5f))
    val g2 = String.format(Locale.US, "%.2f", secondaryGain.coerceIn(0.05f, 2.5f))
    val delayMs = (offsetSec.coerceIn(0f, 60f) * 1000f).roundToInt()

    val filterComplex = when (mode) {
        MergeStudioMode.SEQUENTIAL -> {
            "[0:a]aresample=44100,aformat=channel_layouts=stereo,volume=$g1[a0];" +
                "[1:a]aresample=44100,aformat=channel_layouts=stereo,volume=$g2[a1];" +
                "[a0][a1]concat=n=2:v=0:a=1[out]"
        }
        MergeStudioMode.CROSSFADE -> {
            val xf = String.format(Locale.US, "%.2f", crossfadeSec.coerceIn(0.1f, 15f))
            "[0:a]aresample=44100,aformat=channel_layouts=stereo,volume=$g1[a0];" +
                "[1:a]aresample=44100,aformat=channel_layouts=stereo,volume=$g2[a1];" +
                "[a0][a1]acrossfade=d=$xf:c1=tri:c2=tri[out]"
        }
        MergeStudioMode.OVERLAY -> {
            val delayFilter = if (delayMs > 0) ",adelay=${delayMs}|${delayMs}" else ""
            if (autoDucking) {
                "[0:a]aresample=44100,aformat=channel_layouts=stereo,volume=$g1,asplit=2[v][sc];" +
                    "[1:a]aresample=44100,aformat=channel_layouts=stereo,volume=$g2$delayFilter[m];" +
                    "[m][sc]sidechaincompress=threshold=0.04:ratio=5:attack=25:release=260[ducked];" +
                    "[v][ducked]amix=inputs=2:duration=first:normalize=0[out]"
            } else {
                "[0:a]aresample=44100,aformat=channel_layouts=stereo,volume=$g1[a0];" +
                    "[1:a]aresample=44100,aformat=channel_layouts=stereo,volume=$g2$delayFilter[a1];" +
                    "[a0][a1]amix=inputs=2:duration=first:normalize=0[out]"
            }
        }
    }

    val isWav = target.extension.equals("wav", ignoreCase = true)
    val codecArgs = if (isWav) "-acodec pcm_s16le -ar 44100 -ac 2" else "-ar 44100 -ac 2 -c:a aac -b:a 192k"
    val cmd = "-y -i \"${safe1.absolutePath}\" -i \"${safe2.absolutePath}\" -filter_complex \"$filterComplex\" -map \"[out]\" -vn $codecArgs \"${target.absolutePath}\""
    val ok = FfmpegKitBridge.execute(cmd, toolName = "LargeFileFfmpegMix", logNonZeroFailure = true)
    if (safe1 != primaryFile) runCatching { safe1.delete() }
    if (safe2 != secondaryFile) runCatching { safe2.delete() }
    if (ok && target.exists() && target.length() > 512L) {
        target
    } else {
        runCatching { target.delete() }
        null
    }
}

suspend fun exportSourceFileDirectlyWithFfmpeg(
    context: Context,
    sourceFile: File,
    format: com.example.model.ExportAudioFormat,
    baseFileName: String,
    loudnessTarget: com.example.model.PlatformLoudnessTarget,
): ExportedAudioResult? = withContext(Dispatchers.IO) {
    if (!sourceFile.exists() || !FfmpegKitBridge.isAvailable()) return@withContext null
    val safeIn = com.example.audio.cache.SmartCacheManager.ensureSafeFfmpegInputFile(context, sourceFile)
    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val cleanBase = baseFileName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Sonix_Export" }
    val stamp = UUID.randomUUID().toString().take(6)

    val (ext, mime, codecArgs) = when (format) {
        com.example.model.ExportAudioFormat.WAV -> Triple("wav", "audio/wav", "-acodec pcm_s16le -ar 44100 -ac 2")
        com.example.model.ExportAudioFormat.FLAC -> Triple("flac", "audio/flac", "-c:a flac -ar 44100 -ac 2")
        com.example.model.ExportAudioFormat.MP3 -> Triple("mp3", "audio/mpeg", "-c:a libmp3lame -b:a 320k -ar 44100 -ac 2")
        com.example.model.ExportAudioFormat.M4A,
        com.example.model.ExportAudioFormat.OGG -> Triple("m4a", "audio/mp4", "-c:a aac -b:a 320k -ar 44100 -ac 2")
    }
    val outFile = File(exportDir, "${cleanBase}_$stamp.$ext")
    val lufs = loudnessTarget.targetLufs
    val afArg = if (lufs != null) "-af \"loudnorm=I=$lufs:TP=-1.0:LRA=9\"" else ""
    val cmd = "-y -i \"${safeIn.absolutePath}\" -vn $afArg $codecArgs \"${outFile.absolutePath}\""
    val ok = FfmpegKitBridge.execute(cmd, toolName = "LargeFileFfmpegExport", logNonZeroFailure = true)
    if (safeIn != sourceFile) runCatching { safeIn.delete() }
    if (ok && outFile.exists() && outFile.length() > 512L) {
        ExportedAudioResult(outFile, ext, mime)
    } else {
        runCatching { outFile.delete() }
        null
    }
}

