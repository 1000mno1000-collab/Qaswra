package com.example.audio

import android.content.Context
import com.aistudio.voicepro.sonixk.RealWaveformExtractor
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.audio.cache.SmartCacheManager
import com.example.diagnostics.AppDiagnosticLog
import com.example.diagnostics.FfmpegKitBridge
import com.example.model.StereoAudioBuffer
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class VadSpeechSegment(
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class VadAnalysisResult(
    val speechSegments: List<VadSpeechSegment>,
    val silenceSegments: List<VadSpeechSegment>,
    val totalDurationMs: Long,
    val usedSileroOnnx: Boolean,
    val statusNote: String = "",
)

/**
 * Real Silero VAD (snakers4/silero-vad via k2-fsa/sherpa-onnx) + FFmpegKit / PCM speech & silence engine.
 * - Detects genuine human speech boundaries with Arabic-safe pre/post padding so initial consonants
 *   and soft trailing letters are never truncated.
 * - Streams 16 kHz mono PCM from large disk files without loading entire audio into RAM.
 */
object SileroVadProcessor {

    const val SAMPLE_RATE_HZ = 16_000
    private const val WINDOW_SAMPLES = 512
    private const val SILERO_VAD_FILE = "silero_vad.onnx"
    private const val MIN_VALID_MODEL_BYTES = 100_000L

    // Arabic-safe padding so initial stops/glottals and trailing weak letters are preserved
    private const val ARABIC_PRE_PAD_MS = 150L
    private const val ARABIC_POST_PAD_MS = 240L

    private val SILERO_VAD_URLS = listOf(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        "https://huggingface.co/csukuangfj/vad/resolve/main/silero_vad.onnx",
    )

    fun isSherpaVadAvailable(): Boolean = runCatching {
        Class.forName("com.k2fsa.sherpa.onnx.Vad")
        Class.forName("com.k2fsa.sherpa.onnx.SileroVadModelConfig")
        true
    }.getOrDefault(false)

    fun findExistingSileroModel(context: Context): File? {
        val candidates = listOf(
            File(SherpaMoonshineAsrEngine.modelRootDir(context), SILERO_VAD_FILE),
            File(File(context.filesDir, "sherpa_vad"), SILERO_VAD_FILE),
        )
        return candidates.firstOrNull { it.isFile && it.length() >= MIN_VALID_MODEL_BYTES }
    }

    suspend fun ensureSileroModelReady(
        context: Context,
        onProgress: (Float) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        findExistingSileroModel(context)?.let {
            onProgress(1f)
            return@withContext it
        }
        if (!OnlineGuard.isOnline(context)) return@withContext null

        val targetDir = File(context.filesDir, "sherpa_vad").apply { mkdirs() }
        val destFile = File(targetDir, SILERO_VAD_FILE)
        val partFile = File(targetDir, "$SILERO_VAD_FILE.part")

        for (rawUrl in SILERO_VAD_URLS) {
            try {
                coroutineContext.ensureActive()
                val conn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "Sonix-SileroVAD/1.0")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    continue
                }
                val totalLen = conn.contentLengthLong.coerceAtLeast(640_000L)
                var copied = 0L
                conn.inputStream.use { input ->
                    partFile.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            copied += n
                            onProgress((copied.toFloat() / totalLen).coerceIn(0.05f, 0.98f))
                        }
                    }
                }
                conn.disconnect()
                if (partFile.length() >= MIN_VALID_MODEL_BYTES) {
                    if (destFile.exists()) destFile.delete()
                    if (!partFile.renameTo(destFile)) {
                        partFile.copyTo(destFile, overwrite = true)
                        partFile.delete()
                    }
                    onProgress(1f)
                    return@withContext destFile
                } else {
                    runCatching { partFile.delete() }
                }
            } catch (ce: CancellationException) {
                runCatching { partFile.delete() }
                throw ce
            } catch (_: Throwable) {
                runCatching { partFile.delete() }
            }
        }
        null
    }

    suspend fun detectSpeechAndSilence(
        context: Context,
        buffer: StereoAudioBuffer?,
        diskFile: File?,
        vadThreshold: Float = 0.42f,
        minSilenceMs: Int = 400,
        minSpeechMs: Int = 180,
        onProgress: (Float) -> Unit = {},
    ): VadAnalysisResult = withContext(Dispatchers.IO) {
        onProgress(0.05f)
        val totalDurationMs = when {
            buffer != null && buffer.frameCount > 0 -> buffer.durationMs
            diskFile != null && diskFile.exists() -> RealWaveformExtractor.extractTrueDurationMs(diskFile)
            else -> 0L
        }
        if (totalDurationMs <= 0L) {
            return@withContext VadAnalysisResult(
                speechSegments = emptyList(),
                silenceSegments = emptyList(),
                totalDurationMs = 0L,
                usedSileroOnnx = false,
                statusNote = "Empty audio source",
            )
        }

        val sileroModel = if (isSherpaVadAvailable()) {
            ensureSileroModelReady(context) { p -> onProgress(0.05f + p * 0.25f) }
        } else {
            null
        }

        if (sileroModel != null) {
            val sileroSegments = runCatching {
                runSileroOnnxStream(
                    context = context,
                    modelFile = sileroModel,
                    buffer = buffer,
                    diskFile = diskFile,
                    threshold = vadThreshold.coerceIn(0.20f, 0.75f),
                    minSilenceMs = minSilenceMs.coerceIn(150, 3000),
                    minSpeechMs = minSpeechMs.coerceIn(80, 1000),
                    onProgress = { p -> onProgress(0.30f + p * 0.65f) },
                )
            }.onFailure { t ->
                if (t is CancellationException) throw t
                AppDiagnosticLog.logRecoverable(
                    toolName = "SileroVAD",
                    titleAr = "تعذر تشغيل نموذج Silero VAD المحلي",
                    titleEn = "Silero VAD Local Execution Fallback",
                    causeAr = t.localizedMessage ?: "تم استخدام التحليل الصوتي البديل مؤقتاً.",
                    causeEn = t.message ?: "Used adaptive energy VAD fallback.",
                    throwable = t,
                )
            }.getOrNull()

            if (sileroSegments != null) {
                val padded = mergeAndPadSpeechSegments(
                    raw = sileroSegments,
                    totalDurationMs = totalDurationMs,
                    prePadMs = ARABIC_PRE_PAD_MS,
                    postPadMs = ARABIC_POST_PAD_MS,
                    mergeGapMs = minSilenceMs.toLong().coerceAtLeast(180L),
                )
                val silences = deriveSilenceSegments(padded, totalDurationMs, minSilenceMs.toLong())
                onProgress(1f)
                return@withContext VadAnalysisResult(
                    speechSegments = padded,
                    silenceSegments = silences,
                    totalDurationMs = totalDurationMs,
                    usedSileroOnnx = true,
                    statusNote = "Silero VAD (ONNX)",
                )
            }
        }

        // Adaptive noise-floor + ZCR energy VAD fallback when offline & model not yet downloaded
        val fallbackRaw = runAdaptiveEnergyVad(
            context = context,
            buffer = buffer,
            diskFile = diskFile,
            totalDurationMs = totalDurationMs,
            minSilenceMs = minSilenceMs,
            minSpeechMs = minSpeechMs,
            onProgress = { p -> onProgress(0.30f + p * 0.65f) },
        )
        val padded = mergeAndPadSpeechSegments(
            raw = fallbackRaw,
            totalDurationMs = totalDurationMs,
            prePadMs = ARABIC_PRE_PAD_MS,
            postPadMs = ARABIC_POST_PAD_MS,
            mergeGapMs = minSilenceMs.toLong().coerceAtLeast(180L),
        )
        val silences = deriveSilenceSegments(padded, totalDurationMs, minSilenceMs.toLong())
        onProgress(1f)
        VadAnalysisResult(
            speechSegments = padded,
            silenceSegments = silences,
            totalDurationMs = totalDurationMs,
            usedSileroOnnx = false,
            statusNote = "Adaptive Energy VAD",
        )
    }

    private suspend fun runSileroOnnxStream(
        context: Context,
        modelFile: File,
        buffer: StereoAudioBuffer?,
        diskFile: File?,
        threshold: Float,
        minSilenceMs: Int,
        minSpeechMs: Int,
        onProgress: (Float) -> Unit,
    ): List<VadSpeechSegment> {
        val sileroConfig = SileroVadModelConfig(
            model = modelFile.absolutePath,
            threshold = threshold,
            minSilenceDuration = minSilenceMs / 1000f,
            minSpeechDuration = minSpeechMs / 1000f,
            windowSize = WINDOW_SAMPLES,
            maxSpeechDuration = 30.0f,
        )
        val vadConfig = VadModelConfig(
            sileroVadModelConfig = sileroConfig,
            sampleRate = SAMPLE_RATE_HZ,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        val vad = Vad(assetManager = null, config = vadConfig)
        val collected = ArrayList<VadSpeechSegment>()

        try {
            streamMono16kFloatChunks(context, buffer, diskFile, onProgress) { window ->
                vad.acceptWaveform(window)
                while (!vad.empty()) {
                    val seg = vad.front()
                    vad.pop()
                    val startMs = (seg.start.toLong() * 1000L) / SAMPLE_RATE_HZ
                    val endMs = ((seg.start.toLong() + seg.samples.size.toLong()) * 1000L) / SAMPLE_RATE_HZ
                    if (endMs > startMs) {
                        collected += VadSpeechSegment(startMs, endMs)
                    }
                }
            }
            vad.flush()
            while (!vad.empty()) {
                val seg = vad.front()
                vad.pop()
                val startMs = (seg.start.toLong() * 1000L) / SAMPLE_RATE_HZ
                val endMs = ((seg.start.toLong() + seg.samples.size.toLong()) * 1000L) / SAMPLE_RATE_HZ
                if (endMs > startMs) {
                    collected += VadSpeechSegment(startMs, endMs)
                }
            }
        } finally {
            runCatching { vad.release() }
        }
        return collected
    }

    private suspend fun runAdaptiveEnergyVad(
        context: Context,
        buffer: StereoAudioBuffer?,
        diskFile: File?,
        totalDurationMs: Long,
        minSilenceMs: Int,
        minSpeechMs: Int,
        onProgress: (Float) -> Unit,
    ): List<VadSpeechSegment> {
        val windowEnergies = ArrayList<Float>(1024)
        streamMono16kFloatChunks(context, buffer, diskFile, onProgress) { window ->
            var sumSq = 0.0
            for (s in window) sumSq += s * s
            windowEnergies += sqrt(sumSq / max(1, window.size)).toFloat()
        }
        if (windowEnergies.isEmpty()) return emptyList()

        val sorted = windowEnergies.sorted()
        val noiseFloor = sorted[(sorted.size * 0.15).toInt().coerceIn(0, sorted.lastIndex)]
        val peakRms = sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.lastIndex)]
        val threshold = max(0.008f, noiseFloor * 2.8f + (peakRms - noiseFloor) * 0.12f)
        val winMs = (WINDOW_SAMPLES * 1000.0) / SAMPLE_RATE_HZ
        val minSpeechWins = max(1, (minSpeechMs / winMs).roundToInt())

        val segments = ArrayList<VadSpeechSegment>()
        var inSpeech = false
        var startWin = 0
        var activeCount = 0

        for (i in windowEnergies.indices) {
            val active = windowEnergies[i] >= threshold
            if (active) {
                if (!inSpeech) {
                    inSpeech = true
                    startWin = i
                    activeCount = 1
                } else {
                    activeCount++
                }
            } else if (inSpeech) {
                if (activeCount >= minSpeechWins) {
                    val sMs = (startWin * winMs).toLong().coerceIn(0L, totalDurationMs)
                    val eMs = (i * winMs).toLong().coerceIn(sMs, totalDurationMs)
                    if (eMs > sMs) segments += VadSpeechSegment(sMs, eMs)
                }
                inSpeech = false
                activeCount = 0
            }
        }
        if (inSpeech && activeCount >= minSpeechWins) {
            val sMs = (startWin * winMs).toLong().coerceIn(0L, totalDurationMs)
            val eMs = totalDurationMs
            if (eMs > sMs) segments += VadSpeechSegment(sMs, eMs)
        }
        return segments
    }

    private suspend fun streamMono16kFloatChunks(
        context: Context,
        buffer: StereoAudioBuffer?,
        diskFile: File?,
        onProgress: (Float) -> Unit,
        onWindow: (FloatArray) -> Unit,
    ) {
        if (buffer != null && buffer.frameCount > 0) {
            val mono16k = if (buffer.sampleRate == SAMPLE_RATE_HZ) {
                FloatArray(buffer.frameCount) { i -> 0.5f * (buffer.left[i] + buffer.right[i]) }
            } else {
                val resampled = resampleStereo(buffer, SAMPLE_RATE_HZ)
                FloatArray(resampled.frameCount) { i -> 0.5f * (resampled.left[i] + resampled.right[i]) }
            }
            val window = FloatArray(WINDOW_SAMPLES)
            var offset = 0
            while (offset < mono16k.size) {
                coroutineContext.ensureActive()
                val count = min(WINDOW_SAMPLES, mono16k.size - offset)
                window.fill(0f)
                System.arraycopy(mono16k, offset, window, 0, count)
                onWindow(window)
                offset += WINDOW_SAMPLES
                if ((offset / WINDOW_SAMPLES) % 64 == 0) {
                    onProgress((offset.toFloat() / mono16k.size).coerceIn(0f, 1f))
                }
            }
            onProgress(1f)
            return
        }

        if (diskFile == null || !diskFile.exists()) return
        val workDir = File(context.cacheDir, "vad_tmp").apply { mkdirs() }
        val id = UUID.randomUUID().toString().take(8)
        val pcmFile = File(workDir, "vad_$id.pcm")
        var safeInputCopy: File? = null

        try {
            val needsSafeCopy = !diskFile.absolutePath.all { it.code in 32..126 && it != '"' && it != '\'' }
            val inputFile = if (needsSafeCopy) {
                val ext = diskFile.extension.takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) } ?: "audio"
                val copy = File(workDir, "in_$id.$ext")
                diskFile.inputStream().use { ins -> copy.outputStream().use { outs -> ins.copyTo(outs, 16 * 1024) } }
                safeInputCopy = copy
                copy
            } else {
                diskFile
            }

            if (FfmpegKitBridge.isAvailable()) {
                val cmd = "-y -threads 0 -i \"${inputFile.absolutePath}\" -vn -ac 1 -ar $SAMPLE_RATE_HZ -f s16le \"${pcmFile.absolutePath}\""
                val session = FFmpegKit.execute(cmd)
                coroutineContext.ensureActive()
                if (ReturnCode.isSuccess(session.returnCode) && pcmFile.exists() && pcmFile.length() >= 4L) {
                    val totalBytes = pcmFile.length().coerceAtLeast(1L)
                    val byteBuf = ByteArray(WINDOW_SAMPLES * 2)
                    val floatWin = FloatArray(WINDOW_SAMPLES)
                    var readTotal = 0L
                    FileInputStream(pcmFile).use { fis ->
                        while (true) {
                            coroutineContext.ensureActive()
                            var filled = 0
                            while (filled < byteBuf.size) {
                                val r = fis.read(byteBuf, filled, byteBuf.size - filled)
                                if (r <= 0) break
                                filled += r
                            }
                            if (filled < 2) break
                            readTotal += filled
                            floatWin.fill(0f)
                            val samples = filled / 2
                            for (i in 0 until samples) {
                                val lo = byteBuf[i * 2].toInt() and 0xFF
                                val hi = byteBuf[i * 2 + 1].toInt()
                                val sample = ((hi shl 8) or lo).toShort()
                                floatWin[i] = sample / 32768f
                            }
                            onWindow(floatWin)
                            if ((readTotal / byteBuf.size) % 64L == 0L) {
                                onProgress((readTotal.toFloat() / totalBytes).coerceIn(0f, 1f))
                            }
                        }
                    }
                    onProgress(1f)
                    return
                }
            }

            // Fallback decode for small/medium files if FFmpegKit unavailable
            val decoded = AudioDecoder.decodeFile(inputFile) ?: return
            val mono16k = resampleStereo(decoded, SAMPLE_RATE_HZ)
            val window = FloatArray(WINDOW_SAMPLES)
            var offset = 0
            while (offset < mono16k.frameCount) {
                coroutineContext.ensureActive()
                val count = min(WINDOW_SAMPLES, mono16k.frameCount - offset)
                window.fill(0f)
                for (i in 0 until count) {
                    window[i] = 0.5f * (mono16k.left[offset + i] + mono16k.right[offset + i])
                }
                onWindow(window)
                offset += WINDOW_SAMPLES
            }
            onProgress(1f)
        } finally {
            runCatching { pcmFile.delete() }
            runCatching { safeInputCopy?.delete() }
        }
    }

    /**
     * Removes long non-speech silence from [buffer] using Silero VAD speech segmentation,
     * preserving Arabic consonant onsets and word tails with 14ms raised-cosine splice crossfades.
     */
    suspend fun removeSilenceWithSileroVad(
        context: Context,
        buffer: StereoAudioBuffer,
        thresholdDb: Float = -38f,
        minSilenceSec: Float = 0.45f,
    ): StereoAudioBuffer? = withContext(Dispatchers.Default) {
        val norm = normalizeToStudioStereo44100(buffer)
        val n = norm.frameCount
        if (n <= 0) return@withContext norm
        val sr = norm.sampleRate

        // Map thresholdDb (-60 .. -15) to Silero speech probability (0.24 .. 0.62)
        val normThresh = ((thresholdDb.coerceIn(-60f, -15f) + 60f) / 45f)
        val vadProb = (0.24f + normThresh * 0.38f).coerceIn(0.22f, 0.65f)
        val minSilMs = (minSilenceSec.coerceIn(0.15f, 4.0f) * 1000f).roundToInt()

        val analysis = detectSpeechAndSilence(
            context = context,
            buffer = norm,
            diskFile = null,
            vadThreshold = vadProb,
            minSilenceMs = minSilMs,
            minSpeechMs = 160,
        )
        if (!analysis.usedSileroOnnx && analysis.speechSegments.isEmpty()) {
            return@withContext null
        }
        val segments = analysis.speechSegments
        if (segments.isEmpty()) return@withContext norm

        // Convert speech segments to frame ranges and splice cleanly with 14ms crossfade
        val ranges = segments.mapNotNull { seg ->
            val s = ((seg.startMs * sr) / 1000L).toInt().coerceIn(0, n - 1)
            val e = ((seg.endMs * sr) / 1000L).toInt().coerceIn(s + 1, n)
            if (e > s) s to e else null
        }
        if (ranges.isEmpty()) return@withContext norm

        val xfadeFrames = (0.014f * sr).roundToInt().coerceAtLeast(32)
        var totalFrames = 0
        for (idx in ranges.indices) {
            val len = ranges[idx].second - ranges[idx].first
            val overlap = if (idx > 0) minOf(xfadeFrames, len / 3, (ranges[idx - 1].second - ranges[idx - 1].first) / 3) else 0
            totalFrames += len - overlap
        }
        if (totalFrames <= 0 || totalFrames >= n) return@withContext norm

        val outL = FloatArray(totalFrames)
        val outR = FloatArray(totalFrames)
        var dst = 0

        for (idx in ranges.indices) {
            val (s, e) = ranges[idx]
            val len = e - s
            if (idx == 0) {
                System.arraycopy(norm.left, s, outL, 0, len)
                System.arraycopy(norm.right, s, outR, 0, len)
                val edgeFade = min(len / 4, (0.008f * sr).roundToInt())
                for (k in 0 until edgeFade) {
                    val g = sin((k.toDouble() / max(1, edgeFade)) * PI * 0.5).toFloat()
                    outL[k] *= g
                    outR[k] *= g
                }
                dst = len
            } else {
                val prevLen = ranges[idx - 1].second - ranges[idx - 1].first
                val overlap = minOf(xfadeFrames, len / 3, prevLen / 3).coerceAtLeast(0)
                val joinStart = dst - overlap
                for (k in 0 until overlap) {
                    val t = k.toDouble() / max(1, overlap)
                    val gOut = cos(t * PI * 0.5).toFloat()
                    val gIn = sin(t * PI * 0.5).toFloat()
                    outL[joinStart + k] = outL[joinStart + k] * gOut + norm.left[s + k] * gIn
                    outR[joinStart + k] = outR[joinStart + k] * gOut + norm.right[s + k] * gIn
                }
                val rem = len - overlap
                if (rem > 0) {
                    System.arraycopy(norm.left, s + overlap, outL, dst, rem)
                    System.arraycopy(norm.right, s + overlap, outR, dst, rem)
                    dst += rem
                }
            }
        }
        val tailFade = min(dst / 4, (0.010f * sr).roundToInt())
        for (k in 0 until tailFade) {
            val g = cos((k.toDouble() / max(1, tailFade)) * PI * 0.5).toFloat()
            val pos = dst - tailFade + k
            outL[pos] *= g
            outR[pos] *= g
        }
        StereoAudioBuffer(outL, outR, sr)
    }

    /**
     * Streams silence removal on a large disk file by detecting speech intervals via Silero VAD
     * and splicing them with FFmpeg `atrim` + `concat`.
     */
    suspend fun removeSilenceFileWithSileroVad(
        context: Context,
        inputFile: File,
        thresholdDb: Float = -38f,
        minSilenceSec: Float = 0.45f,
    ): File? = withContext(Dispatchers.IO) {
        if (!inputFile.exists() || !FfmpegKitBridge.isAvailable()) return@withContext null
        val normThresh = ((thresholdDb.coerceIn(-60f, -15f) + 60f) / 45f)
        val vadProb = (0.24f + normThresh * 0.38f).coerceIn(0.22f, 0.65f)
        val minSilMs = (minSilenceSec.coerceIn(0.15f, 4.0f) * 1000f).roundToInt()

        val analysis = detectSpeechAndSilence(
            context = context,
            buffer = null,
            diskFile = inputFile,
            vadThreshold = vadProb,
            minSilenceMs = minSilMs,
            minSpeechMs = 160,
        )
        val segments = analysis.speechSegments
        if (segments.isEmpty() || segments.size > 48) {
            // Fall back to FFmpeg silenceremove when there are >48 segments so filter_complex stays fast & safe
            return@withContext null
        }

        val safeIn = SmartCacheManager.ensureSafeFfmpegInputFile(context, inputFile)
        val outDir = File(context.cacheDir, "staged_inputs").apply { mkdirs() }
        val outFile = File(outDir, "vad_clean_${UUID.randomUUID().toString().take(8)}.m4a")
        try {
            val fc = buildString {
                segments.forEachIndexed { idx, seg ->
                    val sSec = String.format(Locale.US, "%.3f", seg.startMs / 1000.0)
                    val eSec = String.format(Locale.US, "%.3f", seg.endMs / 1000.0)
                    append("[0:a]atrim=start=$sSec:end=$eSec,asetpts=PTS-STARTPTS[v$idx];")
                }
                segments.indices.forEach { idx -> append("[v$idx]") }
                append("concat=n=${segments.size}:v=0:a=1[out]")
            }
            val cmd = "-y -i \"${safeIn.absolutePath}\" -filter_complex \"$fc\" -map \"[out]\" -vn -ar 44100 -ac 2 -c:a aac -b:a 192k \"${outFile.absolutePath}\""
            val ok = FfmpegKitBridge.execute(cmd, toolName = "SileroVadTrimLarge", logNonZeroFailure = false)
            if (ok && outFile.exists() && outFile.length() > 512L) {
                outFile
            } else {
                runCatching { outFile.delete() }
                null
            }
        } finally {
            if (safeIn != inputFile) runCatching { safeIn.delete() }
        }
    }

    private fun mergeAndPadSpeechSegments(
        raw: List<VadSpeechSegment>,
        totalDurationMs: Long,
        prePadMs: Long,
        postPadMs: Long,
        mergeGapMs: Long,
    ): List<VadSpeechSegment> {
        if (raw.isEmpty() || totalDurationMs <= 0L) return emptyList()
        val padded = raw.sortedBy { it.startMs }.map { seg ->
            VadSpeechSegment(
                startMs = (seg.startMs - prePadMs).coerceAtLeast(0L),
                endMs = (seg.endMs + postPadMs).coerceAtMost(totalDurationMs),
            )
        }
        val merged = ArrayList<VadSpeechSegment>(padded.size)
        var cur = padded.first()
        for (i in 1 until padded.size) {
            val next = padded[i]
            if (next.startMs <= cur.endMs + mergeGapMs) {
                cur = VadSpeechSegment(cur.startMs, max(cur.endMs, next.endMs))
            } else {
                merged += cur
                cur = next
            }
        }
        merged += cur
        return merged
    }

    private fun deriveSilenceSegments(
        speech: List<VadSpeechSegment>,
        totalDurationMs: Long,
        minSilenceMs: Long,
    ): List<VadSpeechSegment> {
        if (totalDurationMs <= 0L) return emptyList()
        if (speech.isEmpty()) return listOf(VadSpeechSegment(0L, totalDurationMs))
        val silences = ArrayList<VadSpeechSegment>()
        var cursor = 0L
        for (s in speech) {
            if (s.startMs - cursor >= minSilenceMs) {
                silences += VadSpeechSegment(cursor, s.startMs)
            }
            cursor = max(cursor, s.endMs)
        }
        if (totalDurationMs - cursor >= minSilenceMs) {
            silences += VadSpeechSegment(cursor, totalDurationMs)
        }
        return silences
    }
}
