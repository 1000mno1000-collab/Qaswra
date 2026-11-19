package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Immutable
import com.example.diagnostics.AppDiagnosticLog
import com.example.diagnostics.FfmpegKitBridge
import com.example.model.StereoAudioBuffer
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

enum class SttBackendMode {
    AUTO,
    LOCAL_MOONSHINE_AR,
    ANDROID_SYSTEM,
}

@Immutable
data class SherpaAsrModelState(
    val isInstalled: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val activeStageLabel: String = "",
    val modelDirPath: String = "",
    val errorMessage: String? = null,
)

/**
 * Pure speech error & transcript helpers (shared across local Sherpa-ONNX and Android SpeechRecognizer).
 */
fun isRecoverableSpeechError(errorCode: Int): Boolean {
    return errorCode == SpeechRecognizer.ERROR_NO_MATCH ||           // 7
        errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||      // 6
        errorCode == SpeechRecognizer.ERROR_CLIENT ||              // 5
        errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||     // 8
        errorCode == SpeechRecognizer.ERROR_AUDIO ||               // 3
        errorCode == 10 ||                                         // ERROR_TOO_MANY_REQUESTS (API 31+)
        errorCode == 11                                            // ERROR_SERVER_DISCONNECTED (API 31+)
}

fun computeRetryBackoffMs(errorCode: Int, consecutiveFastErrors: Int): Long {
    val shift = consecutiveFastErrors.coerceIn(0, 3)
    return when (errorCode) {
        11, 10 -> min(3_200L, 650L shl shift)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_AUDIO -> 320L + shift * 180L
        else -> 120L
    }
}

/**
 * Appends newly recognized speech segment to committed transcript while deduplicating
 * overlapping boundary words and preserving every unique word.
 */
fun mergeTranscriptText(committed: String, incoming: String): String {
    val base = committed.trim()
    val next = incoming.trim()
    if (next.isEmpty()) return base
    if (base.isEmpty()) return next
    if (base == next || base.endsWith(next)) return base

    val baseWords = base.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val nextWords = next.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (baseWords.isEmpty() || nextWords.isEmpty()) return "$base $next"

    val maxOverlap = min(min(baseWords.size, nextWords.size), 8)
    var overlapCount = 0
    for (k in maxOverlap downTo 1) {
        val baseTail = baseWords.subList(baseWords.size - k, baseWords.size)
        val nextHead = nextWords.subList(0, k)
        if (baseTail == nextHead) {
            overlapCount = k
            break
        }
    }
    if (overlapCount >= nextWords.size) return base
    val remaining = nextWords.subList(overlapCount, nextWords.size).joinToString(" ")
    return if (remaining.isEmpty()) base else "$base $remaining"
}

data class ResolvedMoonshineModelPaths(
    val sileroVadPath: String,
    val encoderPath: String,
    val mergedDecoderPath: String,
    val uncachedDecoderPath: String,
    val cachedDecoderPath: String,
    val tokensPath: String,
)

/**
 * Local-first continuous Arabic speech recognition engine powered by
 * Sherpa-ONNX + Moonshine Base Arabic v2 (quantized ONNX/ORT) + Silero VAD.
 *
 * - Runs completely offline once the ~119 MB model + ~644 KB VAD are cached in `filesDir`.
 * - Reads 16 kHz mono PCM frames continuously via `AudioRecord` (no silent timeout).
 * - Guarded by a single-session mutex + generation ID so concurrent captures never overlap.
 */
class SherpaMoonshineAsrEngine(private val context: Context) {

    companion object {
        private const val MODEL_DIR_NAME = "sherpa_asr/moonshine_base_ar"
        private const val SILERO_VAD_FILE = "silero_vad.onnx"
        private const val READY_MARKER = ".ready"
        private const val ARCHIVE_TMP = "moonshine_base_ar.tar.bz2.part"

        const val MOONSHINE_AR_TAR_BZ2_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-base-ar-quantized-2026-02-27.tar.bz2"
        val SILERO_VAD_URLS = listOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            "https://huggingface.co/csukuangfj/vad/resolve/main/silero_vad.onnx",
        )
        const val ESTIMATED_ARCHIVE_BYTES = 119_143_068L
        const val SAMPLE_RATE_HZ = 16_000
        const val VAD_WINDOW_SAMPLES = 512

        fun modelRootDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

        fun resolveModelPaths(dir: File): ResolvedMoonshineModelPaths? {
            if (!dir.exists() || !dir.isDirectory) return null
            val silero = File(dir, SILERO_VAD_FILE)
            if (!silero.isFile || silero.length() < 100_000L) return null

            val files = dir.listFiles()?.filter { it.isFile && it.length() > 0L }.orEmpty()
            val tokens = files.firstOrNull { it.name.equals("tokens.txt", ignoreCase = true) } ?: return null
            val encoder = files.firstOrNull {
                val n = it.name.lowercase()
                n.contains("encoder") && (n.endsWith(".ort") || n.endsWith(".onnx"))
            } ?: return null
            val merged = files.firstOrNull {
                val n = it.name.lowercase()
                n.contains("decoder") && n.contains("merged") && (n.endsWith(".ort") || n.endsWith(".onnx"))
            }
            val uncached = files.firstOrNull {
                val n = it.name.lowercase()
                n.contains("uncached") && (n.endsWith(".ort") || n.endsWith(".onnx"))
            }
            val cached = files.firstOrNull {
                val n = it.name.lowercase()
                n.contains("cached") && !n.contains("uncached") && (n.endsWith(".ort") || n.endsWith(".onnx"))
            }
            if (merged == null && (uncached == null || cached == null)) return null

            return ResolvedMoonshineModelPaths(
                sileroVadPath = silero.absolutePath,
                encoderPath = encoder.absolutePath,
                mergedDecoderPath = merged?.absolutePath.orEmpty(),
                uncachedDecoderPath = uncached?.absolutePath.orEmpty(),
                cachedDecoderPath = cached?.absolutePath.orEmpty(),
                tokensPath = tokens.absolutePath,
            )
        }
    }

    private val _modelState = MutableStateFlow(SherpaAsrModelState())
    val modelState: StateFlow<SherpaAsrModelState> = _modelState.asStateFlow()

    private val sessionLock = Any()
    private val activeFlag = AtomicBoolean(false)
    private val sessionGen = AtomicLong(0L)
    @Volatile private var workerThread: Thread? = null
    @Volatile private var downloadJob: Job? = null

    init {
        refreshInstalledState()
    }

    fun isNativeLibraryAvailable(): Boolean {
        return try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
            Class.forName("com.k2fsa.sherpa.onnx.Vad")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isModelReady(): Boolean {
        val dir = modelRootDir(context)
        val ready = File(dir, READY_MARKER).exists()
        return ready && resolveModelPaths(dir) != null
    }

    fun refreshInstalledState(): Boolean {
        val dir = modelRootDir(context)
        val installed = isModelReady()
        _modelState.update {
            it.copy(
                isInstalled = installed,
                modelDirPath = dir.absolutePath,
                progress = if (installed) 1f else if (it.isDownloading) it.progress else 0f,
            )
        }
        return installed
    }

    fun isCurrentlyCapturing(): Boolean = activeFlag.get()

    suspend fun ensureModelsDownloaded(): Boolean = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            refreshInstalledState()
            return@withContext true
        }
        val dir = modelRootDir(context).apply { mkdirs() }
        val marker = File(dir, READY_MARKER)
        runCatching { marker.delete() }

        _modelState.update {
            it.copy(
                isDownloading = true,
                progress = 0.01f,
                downloadedBytes = 0L,
                totalBytes = ESTIMATED_ARCHIVE_BYTES + 650_000L,
                activeStageLabel = "جاري تحميل كاشف الصمت Silero VAD...",
                modelDirPath = dir.absolutePath,
                errorMessage = null,
            )
        }

        try {
            coroutineScope {
                downloadJob = coroutineContext[Job]
                val sileroFile = File(dir, SILERO_VAD_FILE)
                if (!sileroFile.isFile || sileroFile.length() < 100_000L) {
                    downloadWithFallback(
                        urls = SILERO_VAD_URLS,
                        destFile = sileroFile,
                    ) { downloaded, _ ->
                        ensureActive()
                        _modelState.update { cur ->
                            cur.copy(
                                downloadedBytes = downloaded,
                                progress = (downloaded.toFloat() / (ESTIMATED_ARCHIVE_BYTES + 650_000f)).coerceIn(0.01f, 0.04f),
                            )
                        }
                    }
                }

                ensureActive()
                val archivePart = File(dir, ARCHIVE_TMP)
                _modelState.update {
                    it.copy(activeStageLabel = "جاري تحميل نموذج Moonshine Arabic v2 (119 MB)...")
                }

                downloadWithFallback(
                    urls = listOf(MOONSHINE_AR_TAR_BZ2_URL),
                    destFile = archivePart,
                ) { downloaded, knownTotal ->
                    ensureActive()
                    val total = (if (knownTotal > 0L) knownTotal else ESTIMATED_ARCHIVE_BYTES) + 650_000L
                    val pct = (0.04f + 0.86f * (downloaded.toFloat() / total.toFloat())).coerceIn(0.04f, 0.90f)
                    _modelState.update { cur ->
                        cur.copy(
                            downloadedBytes = downloaded + 650_000L,
                            totalBytes = total,
                            progress = pct,
                        )
                    }
                }

                ensureActive()
                _modelState.update {
                    it.copy(
                        progress = 0.92f,
                        activeStageLabel = "جاري فك ضغط وتجهيز ملفات Moonshine AR...",
                    )
                }
                extractTarBz2Safely(archivePart, dir)
                runCatching { archivePart.delete() }

                val resolved = resolveModelPaths(dir)
                    ?: error("ملفات نموذج Moonshine العربية غير مكتملة بعد فك الضغط")
                marker.writeText("ok:${resolved.encoderPath}")

                _modelState.update {
                    it.copy(
                        isInstalled = true,
                        isDownloading = false,
                        progress = 1f,
                        activeStageLabel = "جاهز أوفلاين (Moonshine AR v2 + Silero VAD)",
                        errorMessage = null,
                    )
                }
            }
            true
        } catch (t: Throwable) {
            runCatching { File(dir, ARCHIVE_TMP).delete() }
            val cancelled = t is kotlinx.coroutines.CancellationException
            _modelState.update {
                it.copy(
                    isInstalled = isModelReady(),
                    isDownloading = false,
                    progress = 0f,
                    activeStageLabel = "",
                    errorMessage = if (cancelled) null else (t.localizedMessage ?: "تعذر تحميل نموذج الكلام المحلي"),
                )
            }
            AppDiagnosticLog.logRecoverable(
                toolName = "SherpaMoonshineAsrEngine",
                titleAr = "توقف تحميل نموذج الكلام المحلي",
                titleEn = "Local STT Model Download Stopped",
                causeAr = t.localizedMessage ?: "تعذر تحميل نموذج Moonshine العربي",
                causeEn = t.message ?: "Model download stopped",
                throwable = t,
            )
            false
        } finally {
            downloadJob = null
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _modelState.update {
            it.copy(
                isDownloading = false,
                progress = if (it.isInstalled) 1f else 0f,
                activeStageLabel = "",
            )
        }
    }

    fun clearCachedModels() {
        stop()
        cancelDownload()
        runCatching { modelRootDir(context).deleteRecursively() }
        _modelState.value = SherpaAsrModelState(modelDirPath = modelRootDir(context).absolutePath)
    }

    @SuppressLint("MissingPermission")
    fun startContinuousCapture(
        onPartialUpdate: (String) -> Unit,
        onSegmentCommitted: (String) -> Unit,
        onRmsDb: (Float) -> Unit,
        onFatalError: (String) -> Unit,
    ): Boolean {
        synchronized(sessionLock) {
            stopLocked()
            val paths = resolveModelPaths(modelRootDir(context))
            if (paths == null || !isNativeLibraryAvailable()) {
                return false
            }

            val myGen = sessionGen.incrementAndGet()
            activeFlag.set(true)

            val thread = Thread({
                runCaptureLoop(
                    genId = myGen,
                    paths = paths,
                    onPartialUpdate = onPartialUpdate,
                    onSegmentCommitted = onSegmentCommitted,
                    onRmsDb = onRmsDb,
                    onFatalError = onFatalError,
                )
            }, "Sonix-Moonshine-STT-$myGen").apply { isDaemon = true }

            workerThread = thread
            thread.start()
            return true
        }
    }

    fun stop() {
        synchronized(sessionLock) {
            stopLocked()
        }
    }

    /**
     * Decodes a pre-loaded 16 kHz mono float array offline through Silero VAD + Moonshine ASR
     * on a managed single-session worker thread without requiring microphone hardware.
     */
    fun startOfflineClipTranscription(
        samples16k: FloatArray,
        onPartialUpdate: (String) -> Unit,
        onSegmentCommitted: (String) -> Unit,
        onCompleted: () -> Unit,
        onFatalError: (String) -> Unit,
    ): Boolean {
        if (samples16k.isEmpty()) return false
        synchronized(sessionLock) {
            stopLocked()
            val paths = resolveModelPaths(modelRootDir(context))
            if (paths == null || !isNativeLibraryAvailable()) {
                return false
            }

            val myGen = sessionGen.incrementAndGet()
            activeFlag.set(true)

            val thread = Thread({
                runOfflineClipLoop(
                    genId = myGen,
                    paths = paths,
                    samples16k = samples16k,
                    onPartialUpdate = onPartialUpdate,
                    onSegmentCommitted = onSegmentCommitted,
                    onCompleted = onCompleted,
                    onFatalError = onFatalError,
                )
            }, "Sonix-Moonshine-Clip-$myGen").apply { isDaemon = true }

            workerThread = thread
            thread.start()
            return true
        }
    }

    suspend fun extractMono16kSamplesFromFile(file: File): FloatArray? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) return@withContext null
        if (FfmpegKitBridge.isAvailable()) {
            val safeIn = com.example.audio.cache.SmartCacheManager.ensureSafeFfmpegInputFile(context, file)
            val tmpWav = File(context.cacheDir, "stt_16k_${System.nanoTime()}.wav")
            try {
                val cmd = "-y -i \"${safeIn.absolutePath}\" -vn -ar $SAMPLE_RATE_HZ -ac 1 -acodec pcm_s16le \"${tmpWav.absolutePath}\""
                val ok = FfmpegKitBridge.execute(cmd, toolName = "MoonshineClipResample16k", logNonZeroFailure = false)
                if (ok && tmpWav.exists() && tmpWav.length() > 44L) {
                    val buf = AudioDecoder.decodeFile(tmpWav)
                    if (buf != null && buf.frameCount > 0) {
                        return@withContext resampleStereoToMono16k(buf)
                    }
                }
            } finally {
                if (safeIn != file) runCatching { safeIn.delete() }
                runCatching { tmpWav.delete() }
            }
        }
        val decoded = AudioDecoder.decodeFile(file) ?: return@withContext null
        if (decoded.frameCount <= 0) null else resampleStereoToMono16k(decoded)
    }

    private fun stopLocked() {
        activeFlag.set(false)
        sessionGen.incrementAndGet()
        val t = workerThread
        workerThread = null
        if (t != null && t != Thread.currentThread() && t.isAlive) {
            runCatching { t.join(1_500L) }
        }
    }

    private fun runOfflineClipLoop(
        genId: Long,
        paths: ResolvedMoonshineModelPaths,
        samples16k: FloatArray,
        onPartialUpdate: (String) -> Unit,
        onSegmentCommitted: (String) -> Unit,
        onCompleted: () -> Unit,
        onFatalError: (String) -> Unit,
    ) {
        var vad: Vad? = null
        var recognizer: OfflineRecognizer? = null
        try {
            val sileroConfig = SileroVadModelConfig(
                model = paths.sileroVadPath,
                threshold = 0.42f,
                minSilenceDuration = 0.55f,
                minSpeechDuration = 0.20f,
                windowSize = VAD_WINDOW_SAMPLES,
                maxSpeechDuration = 20.0f,
            )
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = sileroConfig,
                sampleRate = SAMPLE_RATE_HZ,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            vad = Vad(assetManager = null, config = vadConfig)

            val moonshineConfig = OfflineMoonshineModelConfig(
                preprocessor = "",
                encoder = paths.encoderPath,
                uncachedDecoder = paths.uncachedDecoderPath,
                cachedDecoder = paths.cachedDecoderPath,
                mergedDecoder = paths.mergedDecoderPath,
            )
            val modelConfig = OfflineModelConfig(
                moonshine = moonshineConfig,
                tokens = paths.tokensPath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
            val recConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
            )
            recognizer = OfflineRecognizer(assetManager = null, config = recConfig)

            var offset = 0
            val total = samples16k.size
            var anyCommitted = false
            while (offset < total && activeFlag.get() && sessionGen.get() == genId) {
                val end = min(offset + VAD_WINDOW_SAMPLES, total)
                val window = if (end - offset == VAD_WINDOW_SAMPLES) {
                    samples16k.copyOfRange(offset, end)
                } else {
                    FloatArray(VAD_WINDOW_SAMPLES).also { dst ->
                        System.arraycopy(samples16k, offset, dst, 0, end - offset)
                    }
                }
                vad.acceptWaveform(window)
                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    val decoded = decodeFloatArray(recognizer, segment.samples)
                    if (decoded.isNotBlank()) {
                        anyCommitted = true
                        onSegmentCommitted(decoded)
                        onPartialUpdate("")
                    }
                }
                offset += VAD_WINDOW_SAMPLES
            }

            if (activeFlag.get() && sessionGen.get() == genId) {
                vad.flush()
                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    val decoded = decodeFloatArray(recognizer, segment.samples)
                    if (decoded.isNotBlank()) {
                        anyCommitted = true
                        onSegmentCommitted(decoded)
                    }
                }
                // If Silero VAD filtered out soft speech on short clips, run direct full-clip fallback up to 25s
                if (!anyCommitted && samples16k.size <= SAMPLE_RATE_HZ * 25) {
                    val full = decodeFloatArray(recognizer, samples16k)
                    if (full.isNotBlank()) {
                        onSegmentCommitted(full)
                    }
                }
                onPartialUpdate("")
                activeFlag.set(false)
                onCompleted()
            }
        } catch (t: Throwable) {
            AppDiagnosticLog.logRecoverable(
                toolName = "SherpaMoonshineAsrEngine",
                titleAr = "تعذر تفريغ المقطع أوفلاين عبر Moonshine",
                titleEn = "Moonshine Offline Clip Transcription Error",
                causeAr = t.localizedMessage ?: "خطأ في تفريغ الملف الصوتي محلياً",
                causeEn = t.message ?: "Offline clip transcription failed",
                throwable = t,
            )
            if (activeFlag.get() && sessionGen.get() == genId) {
                activeFlag.set(false)
                onFatalError(t.localizedMessage ?: "تعذر تفريغ المقطع محلياً")
            }
        } finally {
            runCatching { vad?.release() }
            runCatching { recognizer?.release() }
            activeFlag.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun runCaptureLoop(
        genId: Long,
        paths: ResolvedMoonshineModelPaths,
        onPartialUpdate: (String) -> Unit,
        onSegmentCommitted: (String) -> Unit,
        onRmsDb: (Float) -> Unit,
        onFatalError: (String) -> Unit,
    ) {
        var vad: Vad? = null
        var recognizer: OfflineRecognizer? = null
        var audioRecord: AudioRecord? = null
        val activeSpeechSamples = ArrayList<Float>(SAMPLE_RATE_HZ * 12)

        try {
            val sileroConfig = SileroVadModelConfig(
                model = paths.sileroVadPath,
                threshold = 0.45f,
                minSilenceDuration = 0.65f,
                minSpeechDuration = 0.20f,
                windowSize = VAD_WINDOW_SAMPLES,
                maxSpeechDuration = 20.0f,
            )
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = sileroConfig,
                sampleRate = SAMPLE_RATE_HZ,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            vad = Vad(assetManager = null, config = vadConfig)

            val moonshineConfig = OfflineMoonshineModelConfig(
                preprocessor = "",
                encoder = paths.encoderPath,
                uncachedDecoder = paths.uncachedDecoderPath,
                cachedDecoder = paths.cachedDecoderPath,
                mergedDecoder = paths.mergedDecoderPath,
            )
            val modelConfig = OfflineModelConfig(
                moonshine = moonshineConfig,
                tokens = paths.tokensPath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
            val recConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
            )
            recognizer = OfflineRecognizer(assetManager = null, config = recConfig)

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(SAMPLE_RATE_HZ / 2)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("تعذر تهيئة الميكروفون للاستماع المستمر (16kHz PCM)")
            }
            audioRecord.startRecording()

            val shortBuf = ShortArray(VAD_WINDOW_SAMPLES)
            var lastPartialMs = 0L

            while (activeFlag.get() && sessionGen.get() == genId) {
                val read = audioRecord.read(shortBuf, 0, VAD_WINDOW_SAMPLES)
                if (read <= 0) continue

                var sumSq = 0.0
                val floats = FloatArray(read)
                for (i in 0 until read) {
                    val f = shortBuf[i] / 32768.0f
                    floats[i] = f
                    sumSq += (f * f)
                }
                val rms = sqrt(sumSq / read).toFloat().coerceAtLeast(1e-6f)
                val dbNormalized = ((20f * log10(rms)) + 50f).coerceIn(0f, 10f)
                onRmsDb(dbNormalized)

                vad.acceptWaveform(floats)

                if (vad.isSpeechDetected()) {
                    for (sample in floats) {
                        if (activeSpeechSamples.size < SAMPLE_RATE_HZ * 18) {
                            activeSpeechSamples.add(sample)
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (activeSpeechSamples.size >= SAMPLE_RATE_HZ / 2 && now - lastPartialMs >= 850L) {
                        lastPartialMs = now
                        val preview = decodeFloatArray(recognizer, activeSpeechSamples.toFloatArray())
                        if (preview.isNotBlank() && activeFlag.get() && sessionGen.get() == genId) {
                            onPartialUpdate(preview)
                        }
                    }
                } else {
                    if (activeSpeechSamples.isNotEmpty() && vad.empty()) {
                        activeSpeechSamples.clear()
                    }
                }

                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    activeSpeechSamples.clear()
                    val decoded = decodeFloatArray(recognizer, segment.samples)
                    if (decoded.isNotBlank()) {
                        onSegmentCommitted(decoded)
                        onPartialUpdate("")
                    }
                }
            }

            // Graceful stop flush: preserve final spoken phrase even if user stops mid-sentence
            vad.flush()
            while (!vad.empty()) {
                val segment = vad.front()
                vad.pop()
                activeSpeechSamples.clear()
                val decoded = decodeFloatArray(recognizer, segment.samples)
                if (decoded.isNotBlank()) {
                    onSegmentCommitted(decoded)
                }
            }
            if (activeSpeechSamples.size >= SAMPLE_RATE_HZ / 3) {
                val tail = decodeFloatArray(recognizer, activeSpeechSamples.toFloatArray())
                if (tail.isNotBlank()) {
                    onSegmentCommitted(tail)
                }
            }
            onPartialUpdate("")
        } catch (t: Throwable) {
            AppDiagnosticLog.logRecoverable(
                toolName = "SherpaMoonshineAsrEngine",
                titleAr = "تعذر الاستماع المستمر في Moonshine",
                titleEn = "Moonshine Continuous Capture Error",
                causeAr = t.localizedMessage ?: "خطأ في حلقة الالتقاط المستمر",
                causeEn = t.message ?: "Continuous capture failed",
                throwable = t,
            )
            if (activeFlag.get() && sessionGen.get() == genId) {
                activeFlag.set(false)
                onFatalError(t.localizedMessage ?: "تعذر تشغيل محرك التعرف المحلي على الكلام")
            }
        } finally {
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            runCatching { vad?.release() }
            runCatching { recognizer?.release() }
            activeFlag.set(false)
            onRmsDb(0f)
        }
    }

    private fun decodeFloatArray(recognizer: OfflineRecognizer, samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            runCatching { stream.release() }
        }
    }

    private fun downloadWithFallback(
        urls: List<String>,
        destFile: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        var lastErr: Throwable? = null
        for (rawUrl in urls) {
            val part = File(destFile.parentFile, "${destFile.name}.downloading")
            try {
                val conn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20_000
                    readTimeout = 35_000
                    setRequestProperty("User-Agent", "Sonix-Android-STT/1.0")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    throw IllegalStateException("HTTP $code downloading ${destFile.name}")
                }
                val totalLen = conn.contentLengthLong
                var copied = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            copied += n
                            onProgress(copied, totalLen)
                        }
                    }
                }
                conn.disconnect()
                if (destFile.exists()) destFile.delete()
                if (!part.renameTo(destFile)) {
                    part.copyTo(destFile, overwrite = true)
                    part.delete()
                }
                return
            } catch (t: Throwable) {
                lastErr = t
                runCatching { part.delete() }
                if (t is kotlinx.coroutines.CancellationException) throw t
            }
        }
        throw lastErr ?: IllegalStateException("Download failed for ${destFile.name}")
    }

    private fun extractTarBz2Safely(tarBz2File: File, targetDir: File) {
        val canonicalBase = targetDir.canonicalPath + File.separator
        BufferedInputStream(FileInputStream(tarBz2File)).use { buffered ->
            BZip2CompressorInputStream(buffered, true).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val entry = tarIn.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val fileName = entry.name.substringAfterLast('/').trim()
                        if (fileName.isEmpty() || fileName.contains("..")) continue
                        val lower = fileName.lowercase()
                        val relevant = lower.endsWith(".ort") ||
                            lower.endsWith(".onnx") ||
                            lower == "tokens.txt"
                        if (!relevant) continue

                        val outFile = File(targetDir, fileName)
                        if (!outFile.canonicalPath.startsWith(canonicalBase)) {
                            throw SecurityException("Unsafe archive entry path: ${entry.name}")
                        }
                        FileOutputStream(outFile).use { out ->
                            while (true) {
                                val r = tarIn.read(buf)
                                if (r <= 0) break
                                out.write(buf, 0, r)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Downmixes any [StereoAudioBuffer] and linearly resamples it to 16 kHz mono PCM floats
 * expected by Sherpa-ONNX Silero VAD and Moonshine ASR models.
 */
fun resampleStereoToMono16k(buffer: StereoAudioBuffer): FloatArray {
    val n = buffer.frameCount
    if (n <= 0) return FloatArray(0)
    val srcSr = buffer.sampleRate.coerceAtLeast(8000)
    val dstSr = SherpaMoonshineAsrEngine.SAMPLE_RATE_HZ
    val outCount = if (srcSr == dstSr) n else ((n.toLong() * dstSr) / srcSr).toInt().coerceAtLeast(1)
    val ratio = srcSr.toDouble() / dstSr.toDouble()
    return FloatArray(outCount) { i ->
        val srcPos = if (srcSr == dstSr) i.toDouble() else i * ratio
        val idx0 = srcPos.toInt().coerceIn(0, n - 1)
        val idx1 = (idx0 + 1).coerceIn(0, n - 1)
        val frac = (srcPos - idx0).toFloat()
        val m0 = 0.5f * (buffer.left[idx0] + buffer.right[idx0])
        val m1 = 0.5f * (buffer.left[idx1] + buffer.right[idx1])
        (m0 + (m1 - m0) * frac).coerceIn(-1f, 1f)
    }
}
