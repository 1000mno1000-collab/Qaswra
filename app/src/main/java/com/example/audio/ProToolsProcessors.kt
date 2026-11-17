package com.example.audio

import android.content.Context
import com.example.audio.cache.SmartCacheManager
import com.example.diagnostics.FfmpegKitBridge
import com.example.model.OneTapSocialPreset
import com.example.model.StereoAudioBuffer
import com.example.model.VocalSplitMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 17. Auto-Silence Remover (كاشف وحاذف السكتات الصامتة التلقائي):
 * Strips dead pauses/silences safely using FFmpegKit `silenceremove` with a pure-Kotlin
 * RMS envelope & short crossfade join fallback so vocal cuts sound completely smooth.
 */
suspend fun removeSilenceAuto(
    context: Context,
    buffer: StereoAudioBuffer,
    thresholdDb: Float = -38f,
    minSilenceSec: Float = 0.45f,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val norm = normalizeToStudioStereo44100(buffer)
    val n = norm.frameCount
    if (n <= 0) return@withContext norm

    val sr = norm.sampleRate.coerceAtLeast(8000)
    val safeThreshDb = thresholdDb.coerceIn(-60f, -15f)
    val safeMinSec = minSilenceSec.coerceIn(0.15f, 3.0f)

    // Attempt FFmpegKit silenceremove first
    if (FfmpegKitBridge.isAvailable()) {
        val dir = File(context.cacheDir, "pro_fx").apply { mkdirs() }
        val id = UUID.randomUUID().toString().take(8)
        val inWav = File(dir, "sil_in_$id.wav")
        val outWav = File(dir, "sil_out_$id.wav")
        try {
            withContext(Dispatchers.IO) { writeStereoWavFile(inWav, norm) }
            val cmd = "-y -i \"${inWav.absolutePath}\" " +
                "-af \"silenceremove=start_periods=1:start_duration=0.05:start_threshold=${safeThreshDb}dB:" +
                "stop_periods=-1:stop_duration=${"%.2f".format(Locale.US, safeMinSec)}:stop_threshold=${safeThreshDb}dB\" " +
                "-ar $sr -ac 2 \"${outWav.absolutePath}\""
            val ok = withContext(Dispatchers.IO) {
                FfmpegKitBridge.execute(cmd, toolName = "AutoSilenceRemover", logNonZeroFailure = false)
            }
            if (ok && outWav.exists() && outWav.length() > 44L) {
                withContext(Dispatchers.IO) { AudioDecoder.decodeFile(outWav) }?.let { decoded ->
                    if (decoded.frameCount > 0) return@withContext decoded
                }
            }
        } finally {
            runCatching { inWav.delete() }
            runCatching { outWav.delete() }
            SmartCacheManager.pruneManagedBuckets(context)
        }
    }

    // Pure-Kotlin RMS Block Silence Gate with smooth micro-fades between active islands
    val winFrames = (0.025f * sr).roundToInt().coerceAtLeast(64)
    val threshLin = 10.0.pow(safeThreshDb / 20.0).toFloat()
    val minSilentWindows = max(1, ((safeMinSec * sr) / winFrames).roundToInt())
    val totalWindows = (n + winFrames - 1) / winFrames
    val activeWin = BooleanArray(totalWindows)

    for (w in 0 until totalWindows) {
        if (w % 64 == 0) yield()
        val s = w * winFrames
        val e = min(n, s + winFrames)
        var peak = 0f
        for (i in s until e) {
            val a = max(abs(norm.left[i]), abs(norm.right[i]))
            if (a > peak) peak = a
        }
        activeWin[w] = peak >= threshLin
    }

    // Preserve short speech gaps shorter than minSilentWindows so words never sound clipped
    val keepWin = BooleanArray(totalWindows)
    var wIdx = 0
    while (wIdx < totalWindows) {
        if (activeWin[wIdx]) {
            keepWin[wIdx] = true
            // 1 window tail pad after speech stops
            if (wIdx + 1 < totalWindows) keepWin[wIdx + 1] = true
            wIdx++
        } else {
            var endGap = wIdx
            while (endGap < totalWindows && !activeWin[endGap]) endGap++
            val gapLen = endGap - wIdx
            if (gapLen < minSilentWindows) {
                for (k in wIdx until endGap) keepWin[k] = true
            }
            wIdx = endGap
        }
    }

    var keptCount = 0
    for (w in 0 until totalWindows) {
        if (keepWin[w]) {
            keptCount += min(n, (w + 1) * winFrames) - w * winFrames
        }
    }
    if (keptCount <= winFrames || keptCount >= n) return@withContext norm

    val outL = FloatArray(keptCount)
    val outR = FloatArray(keptCount)
    var dst = 0
    var inRun = false
    val edgeFade = min(winFrames / 2, (0.008f * sr).roundToInt().coerceAtLeast(16))

    for (w in 0 until totalWindows) {
        if (!keepWin[w]) {
            inRun = false
            continue
        }
        val s = w * winFrames
        val e = min(n, s + winFrames)
        val len = e - s
        System.arraycopy(norm.left, s, outL, dst, len)
        System.arraycopy(norm.right, s, outR, dst, len)
        if (!inRun && len >= edgeFade) {
            for (k in 0 until edgeFade) {
                val g = k.toFloat() / edgeFade
                outL[dst + k] *= g
                outR[dst + k] *= g
            }
        }
        inRun = true
        dst += len
    }

    StereoAudioBuffer(outL, outR, sr)
}

/**
 * 24. Auto Compressor & Limiter (موازنة الصوت التلقائية):
 * Raises quiet vocal passages and cleanly limits hot peaks so dialogue sits broadcast-ready.
 */
suspend fun applyAutoCompressorLimiter(
    context: Context,
    buffer: StereoAudioBuffer,
    strengthPct: Float = 65f,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val norm = normalizeToStudioStereo44100(buffer)
    val n = norm.frameCount
    if (n <= 0) return@withContext norm

    val sr = norm.sampleRate.coerceAtLeast(8000)
    val normStrength = (strengthPct / 100f).coerceIn(0f, 1f)
    if (normStrength <= 0.01f) return@withContext norm

    val ratio = 2.0f + normStrength * 4.5f
    val threshDb = -16f - normStrength * 10f
    val makeupDb = 1.5f + normStrength * 4.5f

    if (FfmpegKitBridge.isAvailable()) {
        val dir = File(context.cacheDir, "pro_fx").apply { mkdirs() }
        val id = UUID.randomUUID().toString().take(8)
        val inWav = File(dir, "cmp_in_$id.wav")
        val outWav = File(dir, "cmp_out_$id.wav")
        try {
            withContext(Dispatchers.IO) { writeStereoWavFile(inWav, norm) }
            val cmd = "-y -i \"${inWav.absolutePath}\" " +
                "-af \"acompressor=threshold=${"%.1f".format(Locale.US, threshDb)}dB:ratio=${"%.1f".format(Locale.US, ratio)}:attack=8:release=95:makeup=${"%.1f".format(Locale.US, makeupDb)},alimiter=limit=0.94\" " +
                "-ar $sr -ac 2 \"${outWav.absolutePath}\""
            val ok = withContext(Dispatchers.IO) {
                FfmpegKitBridge.execute(cmd, toolName = "AutoCompressorLimiter", logNonZeroFailure = false)
            }
            if (ok && outWav.exists() && outWav.length() > 44L) {
                withContext(Dispatchers.IO) { AudioDecoder.decodeFile(outWav) }?.let { return@withContext it }
            }
        } finally {
            runCatching { inWav.delete() }
            runCatching { outWav.delete() }
            SmartCacheManager.pruneManagedBuckets(context)
        }
    }

    // Pure-Kotlin RMS Compressor + Look-ahead Soft Limiter fallback
    val outL = FloatArray(n)
    val outR = FloatArray(n)
    val threshLin = 10.0.pow(threshDb / 20.0).toFloat()
    val makeupLin = 10.0.pow(makeupDb / 20.0).toFloat()
    val attack = exp(-1.0 / (0.008 * sr)).toFloat()
    val release = exp(-1.0 / (0.095 * sr)).toFloat()
    var env = 0f

    for (i in 0 until n) {
        if (i % 16_384 == 0) yield()
        val det = max(abs(norm.left[i]), abs(norm.right[i]))
        env = if (det > env) attack * env + (1f - attack) * det else release * env + (1f - release) * det
        val gain = if (env > threshLin) {
            val over = env / threshLin
            over.toDouble().pow(1.0 / ratio - 1.0).toFloat()
        } else {
            1f
        }
        val totalGain = gain * makeupLin
        outL[i] = clamp(tanh((norm.left[i] * totalGain).toDouble()).toFloat() * 0.96f, -0.96f, 0.96f)
        outR[i] = clamp(tanh((norm.right[i] * totalGain).toDouble()).toFloat() * 0.96f, -0.96f, 0.96f)
    }

    StereoAudioBuffer(outL, outR, sr)
}

/**
 * 26. Vocal / Instrumental Splitter (عزل الصوت البشري عن الموسيقى الخلفية):
 * Uses Mid/Side decomposition (`Mid=(L+R)/2`, `Side=(L-R)/2`) combined with focused vocal-formant
 * band-shaping (`130Hz..7.5kHz`) to isolate centered human vocals or stereo instrumental backing.
 */
suspend fun splitVocalAndInstrumental(
    buffer: StereoAudioBuffer,
    mode: VocalSplitMode,
    blendRatio: Float = 0.5f,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val norm = normalizeToStudioStereo44100(buffer)
    val n = norm.frameCount
    if (n <= 0) return@withContext norm
    val sr = norm.sampleRate.coerceAtLeast(8000)

    val hp = StereoBiquadFilter().apply { configureHighPass(sr, 125.0, 0.707) }
    val lp = StereoBiquadFilter().apply { configureLowPass(sr, 7800.0, 0.707) }

    val midOnly = FloatArray(n)
    val sideL = FloatArray(n)
    val sideR = FloatArray(n)

    for (i in 0 until n) {
        val m = 0.5f * (norm.left[i] + norm.right[i])
        val s = 0.5f * (norm.left[i] - norm.right[i])
        midOnly[i] = m
        sideL[i] = norm.left[i] - m * 0.88f + s * 0.35f
        sideR[i] = norm.right[i] - m * 0.88f - s * 0.35f
    }

    // Focus the center vocal track within human speech presence frequencies
    val vocalTempR = midOnly.copyOf()
    val blockSize = 2048
    val bL = FloatArray(blockSize)
    val bR = FloatArray(blockSize)
    var off = 0
    while (off < n) {
        yield()
        val count = min(blockSize, n - off)
        System.arraycopy(midOnly, off, bL, 0, count)
        System.arraycopy(vocalTempR, off, bR, 0, count)
        hp.processStereo(bL, bR, count)
        lp.processStereo(bL, bR, count)
        System.arraycopy(bL, 0, midOnly, off, count)
        off += blockSize
    }

    val outL = FloatArray(n)
    val outR = FloatArray(n)
    when (mode) {
        VocalSplitMode.VOCALS_ONLY -> {
            for (i in 0 until n) {
                // Subtract high-correlation center estimation + vocal band emphasis
                val focused = midOnly[i] * 1.15f
                outL[i] = clamp(focused, -0.98f, 0.98f)
                outR[i] = clamp(focused, -0.98f, 0.98f)
            }
        }
        VocalSplitMode.INSTRUMENTAL_ONLY -> {
            for (i in 0 until n) {
                outL[i] = clamp(sideL[i], -0.98f, 0.98f)
                outR[i] = clamp(sideR[i], -0.98f, 0.98f)
            }
        }
        VocalSplitMode.CUSTOM_BLEND -> {
            val vocalWeight = clamp(blendRatio, 0f, 1f)
            val instWeight = 1f - vocalWeight
            for (i in 0 until n) {
                outL[i] = clamp(midOnly[i] * vocalWeight * 1.2f + sideL[i] * instWeight, -0.98f, 0.98f)
                outR[i] = clamp(midOnly[i] * vocalWeight * 1.2f + sideR[i] * instWeight, -0.98f, 0.98f)
            }
        }
    }
    normalizeAudio(StereoAudioBuffer(outL, outR, sr), -1.0f)
}

/**
 * 29. Optional Smart Auto-Fade Out (تلاشي النهاية الذكي الاختياري):
 * Analyzes trailing speech activity from the end of the waveform backwards to locate the end
 * of the last spoken word/syllable, then applies a smooth logarithmic fade-out right from that point.
 */
suspend fun applyOptionalSmartAutoFadeOut(
    buffer: StereoAudioBuffer,
    durationSec: Float = 1.8f,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val n = buffer.frameCount
    if (n <= 64) return@withContext buffer
    val sr = buffer.sampleRate.coerceAtLeast(8000)

    // Locate overall track peak to adapt threshold cleanly even on quiet recordings
    var globalRms = 0.0
    val step = max(1, n / 2048)
    var samples = 0
    var pIdx = 0
    while (pIdx < n) {
        val s = 0.5f * (buffer.left[pIdx] + buffer.right[pIdx])
        globalRms += s * s
        samples++
        pIdx += step
    }
    val speechThresh = max(0.012f, (sqrt(globalRms / max(1, samples)) * 0.28).toFloat())

    // Scan backwards in 20ms windows to locate last active spoken syllable
    val winSize = (0.020f * sr).roundToInt().coerceAtLeast(128)
    var lastSpokenFrame = n - 1
    var cursor = n - winSize
    while (cursor >= 0) {
        var winPeak = 0f
        for (i in cursor until min(n, cursor + winSize)) {
            val amp = max(abs(buffer.left[i]), abs(buffer.right[i]))
            if (amp > winPeak) winPeak = amp
        }
        if (winPeak >= speechThresh) {
            lastSpokenFrame = min(n - 1, cursor + winSize)
            break
        }
        cursor -= winSize
    }

    val fadeFrames = min(n / 2, (clamp(durationSec, 0.3f, 8.0f) * sr).roundToInt()).coerceAtLeast(64)
    val fadeEnd = min(n, lastSpokenFrame + (0.25f * sr).roundToInt())
    val fadeStart = max(0, fadeEnd - fadeFrames)
    val actualFade = max(1, fadeEnd - fadeStart)

    val outL = buffer.left.copyOf()
    val outR = buffer.right.copyOf()

    for (i in fadeStart until fadeEnd) {
        val progress = (i - fadeStart).toFloat() / actualFade
        // Smooth logarithmic / raised cosine fade curve
        val gain = (cos(progress * PI * 0.5)).toFloat().pow(1.6f)
        outL[i] *= gain
        outR[i] *= gain
    }
    for (i in fadeEnd until n) {
        outL[i] = 0f
        outR[i] = 0f
    }

    StereoAudioBuffer(outL, outR, sr)
}

/**
 * 22. Reverse Audio via FFmpegKit `areverse` with safe Kotlin fallback.
 */
suspend fun reverseAudioWithFfmpeg(
    context: Context,
    buffer: StereoAudioBuffer,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val norm = normalizeToStudioStereo44100(buffer)
    if (norm.frameCount <= 1) return@withContext norm
    if (FfmpegKitBridge.isAvailable()) {
        val dir = File(context.cacheDir, "pro_fx").apply { mkdirs() }
        val id = UUID.randomUUID().toString().take(8)
        val inWav = File(dir, "rev_in_$id.wav")
        val outWav = File(dir, "rev_out_$id.wav")
        try {
            withContext(Dispatchers.IO) { writeStereoWavFile(inWav, norm) }
            val cmd = "-y -i \"${inWav.absolutePath}\" -af \"areverse\" -ar 44100 -ac 2 \"${outWav.absolutePath}\""
            val ok = withContext(Dispatchers.IO) {
                FfmpegKitBridge.execute(cmd, toolName = "ReverseAudio", logNonZeroFailure = false)
            }
            if (ok && outWav.exists() && outWav.length() > 44L) {
                withContext(Dispatchers.IO) { AudioDecoder.decodeFile(outWav) }?.let { return@withContext it }
            }
        } finally {
            runCatching { inWav.delete() }
            runCatching { outWav.delete() }
            SmartCacheManager.pruneManagedBuckets(context)
        }
    }
    reverseAudio(norm)
}

/**
 * 20. 1-Tap Social Media Audio Presets (بودكاست فخم، تعليق وثائقي، مكالمة هاتف قديمة، صدى قاعة كبيرة، راديو FM).
 */
suspend fun applyOneTapSocialPreset(
    context: Context,
    buffer: StereoAudioBuffer,
    preset: OneTapSocialPreset,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val norm = normalizeToStudioStereo44100(buffer)
    val n = norm.frameCount
    if (n <= 0) return@withContext norm
    val sr = norm.sampleRate

    val filterGraph = when (preset) {
        OneTapSocialPreset.LUXURY_PODCAST ->
            "highpass=f=85,equalizer=f=150:width_type=h:width=100:g=3.5,equalizer=f=3200:width_type=h:width=1100:g=2.5,acompressor=threshold=-18dB:ratio=3.2:attack=8:release=80:makeup=3,loudnorm=I=-14:TP=-1.0:LRA=9"
        OneTapSocialPreset.DOCUMENTARY_NARRATOR ->
            "highpass=f=70,equalizer=f=120:width_type=h:width=90:g=4.5,equalizer=f=2400:width_type=h:width=900:g=2.0,acompressor=threshold=-20dB:ratio=4.0:attack=10:release=120:makeup=4,loudnorm=I=-15:TP=-1.0:LRA=8"
        OneTapSocialPreset.VINTAGE_PHONE ->
            "highpass=f=420,lowpass=f=3100,equalizer=f=1300:width_type=h:width=800:g=5.5,acompressor=threshold=-16dB:ratio=6:attack=5:release=50:makeup=4,alimiter=limit=0.92"
        OneTapSocialPreset.GRAND_HALL_REVERB ->
            "highpass=f=90,aecho=0.8:0.85:65|145|240:0.42|0.28|0.18,equalizer=f=2800:width_type=h:width=1200:g=1.8,alimiter=limit=0.95"
        OneTapSocialPreset.FM_RADIO ->
            "highpass=f=95,equalizer=f=140:width_type=h:width=110:g=3.8,equalizer=f=4200:width_type=h:width=1500:g=3.2,acompressor=threshold=-15dB:ratio=5.5:attack=4:release=55:makeup=5,alimiter=limit=0.96"
    }

    if (FfmpegKitBridge.isAvailable()) {
        val dir = File(context.cacheDir, "pro_fx").apply { mkdirs() }
        val id = UUID.randomUUID().toString().take(8)
        val inWav = File(dir, "soc_in_$id.wav")
        val outWav = File(dir, "soc_out_$id.wav")
        try {
            withContext(Dispatchers.IO) { writeStereoWavFile(inWav, norm) }
            val cmd = "-y -i \"${inWav.absolutePath}\" -af \"$filterGraph\" -ar 44100 -ac 2 \"${outWav.absolutePath}\""
            val ok = withContext(Dispatchers.IO) {
                FfmpegKitBridge.execute(cmd, toolName = "SocialPreset:${preset.name}", logNonZeroFailure = false)
            }
            if (ok && outWav.exists() && outWav.length() > 44L) {
                withContext(Dispatchers.IO) { AudioDecoder.decodeFile(outWav) }?.let { return@withContext it }
            }
        } finally {
            runCatching { inWav.delete() }
            runCatching { outWav.delete() }
            SmartCacheManager.pruneManagedBuckets(context)
        }
    }

    // Pure-Kotlin equivalent processing fallback
    val outL = norm.left.copyOf()
    val outR = norm.right.copyOf()
    val filters = when (preset) {
        OneTapSocialPreset.LUXURY_PODCAST -> listOf(
            StereoBiquadFilter().apply { configureHighPass(sr, 85.0, 0.707) },
            StereoBiquadFilter().apply { configurePeaking(sr, 150.0, 1.4, 3.5) },
            StereoBiquadFilter().apply { configurePeaking(sr, 3200.0, 1.8, 2.5) },
        )
        OneTapSocialPreset.DOCUMENTARY_NARRATOR -> listOf(
            StereoBiquadFilter().apply { configureHighPass(sr, 70.0, 0.707) },
            StereoBiquadFilter().apply { configurePeaking(sr, 120.0, 1.3, 4.5) },
            StereoBiquadFilter().apply { configurePeaking(sr, 2400.0, 1.7, 2.0) },
        )
        OneTapSocialPreset.VINTAGE_PHONE -> listOf(
            StereoBiquadFilter().apply { configureHighPass(sr, 420.0, 0.9) },
            StereoBiquadFilter().apply { configureLowPass(sr, 3100.0, 0.9) },
            StereoBiquadFilter().apply { configurePeaking(sr, 1300.0, 1.2, 5.0) },
        )
        OneTapSocialPreset.GRAND_HALL_REVERB -> listOf(
            StereoBiquadFilter().apply { configureHighPass(sr, 90.0, 0.707) },
            StereoBiquadFilter().apply { configurePeaking(sr, 2800.0, 1.8, 1.8) },
        )
        OneTapSocialPreset.FM_RADIO -> listOf(
            StereoBiquadFilter().apply { configureHighPass(sr, 95.0, 0.707) },
            StereoBiquadFilter().apply { configurePeaking(sr, 140.0, 1.3, 3.8) },
            StereoBiquadFilter().apply { configurePeaking(sr, 4200.0, 1.6, 3.2) },
        )
    }

    val blockSize = 2048
    val bL = FloatArray(blockSize)
    val bR = FloatArray(blockSize)
    var off = 0
    while (off < n) {
        yield()
        val count = min(blockSize, n - off)
        System.arraycopy(outL, off, bL, 0, count)
        System.arraycopy(outR, off, bR, 0, count)
        for (f in filters) f.processStereo(bL, bR, count)
        System.arraycopy(bL, 0, outL, off, count)
        System.arraycopy(bR, 0, outR, off, count)
        off += blockSize
    }

    var shaped = StereoAudioBuffer(outL, outR, sr)
    if (preset == OneTapSocialPreset.GRAND_HALL_REVERB) {
        val taps = listOf((0.065f * sr).roundToInt() to 0.40f, (0.145f * sr).roundToInt() to 0.25f, (0.24f * sr).roundToInt() to 0.16f)
        val revL = shaped.left.copyOf()
        val revR = shaped.right.copyOf()
        for ((d, g) in taps) {
            for (i in d until n) {
                revL[i] = clamp(revL[i] + shaped.left[i - d] * g, -0.98f, 0.98f)
                revR[i] = clamp(revR[i] + shaped.right[i - d] * g, -0.98f, 0.98f)
            }
        }
        shaped = StereoAudioBuffer(revL, revR, sr)
    }

    val compStrength = when (preset) {
        OneTapSocialPreset.FM_RADIO -> 85f
        OneTapSocialPreset.DOCUMENTARY_NARRATOR -> 72f
        OneTapSocialPreset.LUXURY_PODCAST -> 65f
        OneTapSocialPreset.VINTAGE_PHONE -> 70f
        OneTapSocialPreset.GRAND_HALL_REVERB -> 35f
    }
    applyAutoCompressorLimiter(context, shaped, strengthPct = compStrength)
}
