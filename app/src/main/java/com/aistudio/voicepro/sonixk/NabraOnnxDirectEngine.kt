package com.aistudio.voicepro.sonixk

import android.content.Context
import android.content.res.AssetManager
import androidx.compose.runtime.Immutable
import com.example.audio.OnlineGuard
import com.example.audio.TARGET_SAMPLE_RATE
import com.example.audio.cache.SmartCacheManager
import com.example.audio.clamp
import com.example.audio.resampleStereo
import com.example.audio.tts.ArabicTextDiacritizer
import com.example.audio.writeStereoWavFile
import com.example.diagnostics.AppDiagnosticLog
import com.example.model.AppLanguage
import com.example.model.StereoAudioBuffer
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsGender
import com.example.model.TtsPersonaAcousticMode
import com.example.model.TtsVoicePersona
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

@Immutable
data class NabraDownloadState(
    val isReady: Boolean = false,
    val isDownloading: Boolean = false,
    val currentFileName: String = "",
    val completedFiles: Int = 0,
    val totalFiles: Int = 3,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val progress: Float = 0f,
    val sherpaAvailable: Boolean = false,
    val modelDirPath: String = "",
    val errorMessage: String? = null,
) {
    val progressPercent: Int get() = (progress * 100f).roundToInt().coerceIn(0, 100)
}

sealed interface NabraSynthOutcome {
    data class Success(
        val result: LocalTtsSynthResult,
        val usedNabraNeural: Boolean,
    ) : NabraSynthOutcome

    data class Error(val message: String) : NabraSynthOutcome
}

private data class NabraModelAssetSpec(
    val fileName: String,
    val urls: List<String>,
    val minValidBytes: Long,
    val estimatedBytes: Long,
)

/**
 * محرك الصوت العربي العصبي المباشر (Nabra-82M Sherpa-ONNX):
 * 1. يفحص وجود ملفات النموذج الثلاثة (`model.int8.onnx`, `voices.bin`, `tokens.txt`) داخل `context.filesDir/nabra_onnx`.
 * 2. عند عدم توفرها يحمّلها مباشرة عبر `java.net.URL` مع تحديث نسبة التقدم لحظياً عبر [progressFlow].
 * 3. يهيئ محرك `OfflineTts` من حزمة `com.k2fsa.sherpa.onnx` باستخدام `OfflineTtsKokoroModelConfig`،
 *    ويحفظ الصوت الناتج بصيغة WAV لتمريره مباشرة إلى معالجات `FFmpegKit` الموجودة في التطبيق.
 */
object NabraOnnxDirectEngine {

    private const val DIR_NAME = "nabra_onnx"
    private const val FILE_MODEL = "model.int8.onnx"
    private const val FILE_VOICES = "voices.bin"
    private const val FILE_TOKENS = "tokens.txt"
    private const val MAX_CHUNK_CHARS = 480

    private val MODEL_SPECS = listOf(
        NabraModelAssetSpec(
            fileName = FILE_MODEL,
            urls = listOf(
                "https://huggingface.co/marwanelamami/nabra-82m-sherpa-onnx/resolve/main/model.int8.onnx",
                "https://huggingface.co/csukuangfj/nabra-82m-arabic-int8/resolve/main/model.int8.onnx",
            ),
            minValidBytes = 1_000_000L,
            estimatedBytes = 87_000_000L,
        ),
        NabraModelAssetSpec(
            fileName = FILE_VOICES,
            urls = listOf(
                "https://huggingface.co/marwanelamami/nabra-82m-sherpa-onnx/resolve/main/voices.bin",
                "https://huggingface.co/csukuangfj/nabra-82m-arabic-int8/resolve/main/voices.bin",
            ),
            minValidBytes = 1_024L,
            estimatedBytes = 524_288L,
        ),
        NabraModelAssetSpec(
            fileName = FILE_TOKENS,
            urls = listOf(
                "https://huggingface.co/marwanelamami/nabra-82m-sherpa-onnx/resolve/main/tokens.txt",
                "https://huggingface.co/csukuangfj/nabra-82m-arabic-int8/resolve/main/tokens.txt",
            ),
            minValidBytes = 64L,
            estimatedBytes = 4_096L,
        ),
    )

    private val _progressFlow = MutableStateFlow(NabraDownloadState())
    val progressFlow: StateFlow<NabraDownloadState> = _progressFlow.asStateFlow()

    private val downloadMutex = Mutex()
    private val ttsMutex = Mutex()

    @Volatile
    private var cachedOfflineTts: Any? = null

    @Volatile
    private var cachedModelDir: String? = null

    fun modelDirectory(context: Context): File = File(context.filesDir, DIR_NAME)

    fun isSherpaOnnxAvailable(): Boolean = runCatching {
        Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
        Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig")
        true
    }.getOrDefault(false)

    fun areModelsPresent(context: Context): Boolean {
        val dir = modelDirectory(context)
        return MODEL_SPECS.all { spec ->
            val f = File(dir, spec.fileName)
            f.exists() && f.isFile && f.length() >= spec.minValidBytes
        }
    }

    fun refreshStatus(context: Context): NabraDownloadState {
        val dir = modelDirectory(context)
        val presentCount = MODEL_SPECS.count { spec ->
            val f = File(dir, spec.fileName)
            f.exists() && f.isFile && f.length() >= spec.minValidBytes
        }
        val ready = presentCount == MODEL_SPECS.size
        val sherpaOk = isSherpaOnnxAvailable()
        val cur = _progressFlow.value
        val next = if (cur.isDownloading) {
            cur.copy(sherpaAvailable = sherpaOk, modelDirPath = dir.absolutePath)
        } else {
            NabraDownloadState(
                isReady = ready,
                isDownloading = false,
                currentFileName = if (ready) "" else cur.currentFileName,
                completedFiles = presentCount,
                totalFiles = MODEL_SPECS.size,
                downloadedBytes = if (ready) MODEL_SPECS.sumOf { File(dir, it.fileName).length() } else 0L,
                totalBytes = if (ready) MODEL_SPECS.sumOf { File(dir, it.fileName).length() } else MODEL_SPECS.sumOf { it.estimatedBytes },
                progress = if (ready) 1f else (presentCount.toFloat() / MODEL_SPECS.size.toFloat()).coerceIn(0f, 0.99f),
                sherpaAvailable = sherpaOk,
                modelDirPath = dir.absolutePath,
                errorMessage = if (ready) null else cur.errorMessage,
            )
        }
        _progressFlow.value = next
        return next
    }

    /**
     * يتحقق من وجود الملفات الثلاثة داخل `filesDir/nabra_onnx` ويحمل الناقص منها عبر `java.net.URL`
     * مع تحديث نسبة التقدم لحظياً.
     */
    suspend fun ensureModelsDownloaded(
        context: Context,
        language: AppLanguage = AppLanguage.AR,
        onProgress: ((NabraDownloadState) -> Unit)? = null,
    ): NabraDownloadState = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            val dir = modelDirectory(context).apply { mkdirs() }
            val sherpaOk = isSherpaOnnxAvailable()
            if (areModelsPresent(context)) {
                val readyState = refreshStatus(context)
                onProgress?.invoke(readyState)
                return@withLock readyState
            }

            if (!OnlineGuard.isOnline(context)) {
                val msg = when (language) {
                    AppLanguage.EN -> "Offline: connect once to download Nabra-82M model files."
                    AppLanguage.FA -> "آفلاین: برای دانلود فایل‌های Nabra-82M یک‌بار به اینترنت متصل شوید."
                    AppLanguage.AR -> "لا يوجد اتصال بالإنترنت لتحميل ملفات نموذج Nabra-82M الآن."
                }
                val failState = refreshStatus(context).copy(isDownloading = false, errorMessage = msg)
                _progressFlow.value = failState
                onProgress?.invoke(failState)
                return@withLock failState
            }

            val totalEstimatedAll = MODEL_SPECS.sumOf { it.estimatedBytes }
            var completedCount = 0
            var cumulativeExistingBytes = 0L

            for (spec in MODEL_SPECS) {
                val existing = File(dir, spec.fileName)
                if (existing.exists() && existing.isFile && existing.length() >= spec.minValidBytes) {
                    completedCount++
                    cumulativeExistingBytes += existing.length()
                }
            }

            try {
                for (spec in MODEL_SPECS) {
                    coroutineContext.ensureActive()
                    val destFile = File(dir, spec.fileName)
                    if (destFile.exists() && destFile.isFile && destFile.length() >= spec.minValidBytes) {
                        continue
                    }

                    val initialState = NabraDownloadState(
                        isReady = false,
                        isDownloading = true,
                        currentFileName = spec.fileName,
                        completedFiles = completedCount,
                        totalFiles = MODEL_SPECS.size,
                        downloadedBytes = cumulativeExistingBytes,
                        totalBytes = totalEstimatedAll,
                        progress = (cumulativeExistingBytes.toFloat() / totalEstimatedAll.toFloat()).coerceIn(0.01f, 0.99f),
                        sherpaAvailable = sherpaOk,
                        modelDirPath = dir.absolutePath,
                        errorMessage = null,
                    )
                    _progressFlow.value = initialState
                    onProgress?.invoke(initialState)

                    val downloadedSize = downloadAssetViaJavaNetUrl(
                        urls = spec.urls,
                        destFile = destFile,
                        minValidBytes = spec.minValidBytes,
                    ) { fileRead, fileTotal ->
                        val effectiveFileTotal = if (fileTotal > 0L) fileTotal else spec.estimatedBytes
                        val totalAll = (cumulativeExistingBytes + effectiveFileTotal).coerceAtLeast(totalEstimatedAll)
                        val globalRead = cumulativeExistingBytes + fileRead
                        val overall = (globalRead.toFloat() / totalAll.toFloat()).coerceIn(0.01f, 0.99f)
                        val progressState = NabraDownloadState(
                            isReady = false,
                            isDownloading = true,
                            currentFileName = spec.fileName,
                            completedFiles = completedCount,
                            totalFiles = MODEL_SPECS.size,
                            downloadedBytes = globalRead,
                            totalBytes = totalAll,
                            progress = overall,
                            sherpaAvailable = sherpaOk,
                            modelDirPath = dir.absolutePath,
                            errorMessage = null,
                        )
                        _progressFlow.value = progressState
                        onProgress?.invoke(progressState)
                    }

                    cumulativeExistingBytes += downloadedSize
                    completedCount++
                }

                // Clear cached TTS instance so it picks up newly downloaded weights
                releaseCachedTts()
                val readyState = refreshStatus(context).copy(
                    isReady = areModelsPresent(context),
                    isDownloading = false,
                    progress = 1f,
                    errorMessage = null,
                )
                _progressFlow.value = readyState
                onProgress?.invoke(readyState)
                readyState
            } catch (ce: kotlinx.coroutines.CancellationException) {
                val cancelled = refreshStatus(context).copy(isDownloading = false)
                _progressFlow.value = cancelled
                onProgress?.invoke(cancelled)
                throw ce
            } catch (t: Throwable) {
                val reason = t.localizedMessage ?: t.javaClass.simpleName
                AppDiagnosticLog.logRecoverable(
                    toolName = "NabraOnnxDirectEngine",
                    titleAr = "تعذر تنزيل ملفات Nabra-82M من HuggingFace",
                    titleEn = "Nabra-82M Model Download Failed",
                    causeAr = reason,
                    causeEn = reason,
                    errorCode = "NABRA_DOWNLOAD_ERR",
                    throwable = t,
                )
                val errState = refreshStatus(context).copy(
                    isDownloading = false,
                    errorMessage = if (language == AppLanguage.EN) {
                        "Failed to download Nabra-82M ($reason)"
                    } else {
                        "تعذر تحميل ملفات Nabra-82M ($reason)"
                    },
                )
                _progressFlow.value = errState
                onProgress?.invoke(errState)
                errState
            }
        }
    }

    /**
     * التوليد الصوتي الموحد عبر Nabra-82M Sherpa-ONNX + FFmpegKit:
     * - يتأكد من ملفات `nabra_onnx` (ويحملها عند الاتصال إذا لم تكن موجودة).
     * - يولد الصوت الخام عبر `OfflineTts` (`OfflineTtsKokoroModelConfig`) بصيغة WAV.
     * - يمرر الملف فوراً إلى معالجات `FFmpegKit` (`asetrate`, `aresample`, `atempo`, `equalizer`, `compand`, `aecho`).
     * - وإذا لم تتوفر مكتبة `com.k2fsa.sherpa.onnx` في وقت التشغيل أو تعذر التنزيل بلا إنترنت،
     *   يتحوّل بسلاسة إلى [LocalArabicTtsEngine] لضمان استجابة أزرار المعاينة والمعالجة فوراً بدون أخطاء.
     */
    suspend fun synthesize(
        context: Context,
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speed: Float,
        pitch: Float,
        language: AppLanguage = AppLanguage.AR,
        studioLuxuryEnhancement: Boolean = true,
        persona: TtsVoicePersona? = null,
        acousticMode: TtsPersonaAcousticMode? = null,
        autoDownloadIfOnline: Boolean = true,
    ): NabraSynthOutcome = withContext(Dispatchers.IO) {
        val cleanInput = ArabicTextDiacritizer.normalizeAndHintForTts(text.trim(), applyLightDiacritics = true)
        if (cleanInput.isEmpty()) {
            return@withContext NabraSynthOutcome.Error(
                when (language) {
                    AppLanguage.EN -> "Please enter text first."
                    AppLanguage.FA -> "لطفاً ابتدا متن را وارد کنید."
                    AppLanguage.AR -> "يرجى إدخال النص أولاً."
                },
            )
        }

        val effectiveAcousticMode = acousticMode ?: persona?.defaultAcousticMode ?: when (style) {
            TtsDeliveryStyle.DRAMATIC, TtsDeliveryStyle.STORYTELLING -> TtsPersonaAcousticMode.DOCUMENTARY
            TtsDeliveryStyle.RADIO_ANCHOR -> TtsPersonaAcousticMode.PODCAST
            else -> TtsPersonaAcousticMode.NATURAL
        }
        val profile = LocalArabicTtsEngine.resolveProfile(
            persona = persona,
            gender = gender,
            style = style,
            acousticMode = effectiveAcousticMode,
        )

        val stylePitchRatio = 1f + (style.pitchOffsetHz / 120f)
        val targetPitch = clamp(pitch * profile.pitchMultiplier * stylePitchRatio, 0.55f, 1.75f)
        val targetSpeed = clamp(speed * style.rateFactor * profile.rateMultiplier, 0.50f, 1.85f)

        val sherpaPresent = isSherpaOnnxAvailable()
        var modelsReady = areModelsPresent(context)
        if (!modelsReady && autoDownloadIfOnline && OnlineGuard.isOnline(context)) {
            val dl = ensureModelsDownloaded(context, language)
            modelsReady = dl.isReady
        } else {
            refreshStatus(context)
        }

        if (sherpaPresent && modelsReady) {
            val neuralResult = synthesizeWithSherpaKokoroAndFfmpeg(
                context = context,
                cleanInput = cleanInput,
                dialect = dialect,
                gender = gender,
                style = style,
                profile = profile,
                targetSpeed = targetSpeed,
                targetPitch = targetPitch,
                luxuryBoost = studioLuxuryEnhancement,
            )
            if (neuralResult != null) {
                SmartCacheManager.pruneManagedBuckets(context)
                return@withContext NabraSynthOutcome.Success(
                    result = neuralResult,
                    usedNabraNeural = true,
                )
            }
        }

        // Fallback seamless route when com.k2fsa.sherpa.onnx native AAR is not in classpath
        // or when model weights have not yet been downloaded offline.
        when (
            val localOutcome = LocalArabicTtsEngine.synthesize(
                context = context,
                text = text,
                dialect = dialect,
                gender = gender,
                style = style,
                speed = speed,
                pitch = pitch,
                language = language,
                studioLuxuryEnhancement = studioLuxuryEnhancement,
                persona = persona,
                acousticMode = acousticMode,
            )
        ) {
            is LocalTtsOutcome.Success -> NabraSynthOutcome.Success(
                result = localOutcome.result,
                usedNabraNeural = false,
            )
            is LocalTtsOutcome.Error -> NabraSynthOutcome.Error(localOutcome.message)
        }
    }

    private suspend fun synthesizeWithSherpaKokoroAndFfmpeg(
        context: Context,
        cleanInput: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        profile: LocalTtsVoiceProfile,
        targetSpeed: Float,
        targetPitch: Float,
        luxuryBoost: Boolean,
    ): LocalTtsSynthResult? = withContext(Dispatchers.IO) {
        val dir = modelDirectory(context)
        val tts = obtainOrCreateOfflineTts(dir, dialect) ?: return@withContext null

        val chunks = LocalArabicTtsEngine.splitSentencesSmoothly(cleanInput, MAX_CHUNK_CHARS)
        if (chunks.isEmpty()) return@withContext null

        val workDir = File(context.cacheDir, "nabra_tts").apply { mkdirs() }
        val processedChunks = ArrayList<StereoAudioBuffer>(chunks.size)
        var singleChunkFile: File? = null

        ttsMutex.withLock {
            val sid = resolveSpeakerId(tts, gender)
            for (chunk in chunks) {
                coroutineContext.ensureActive()
                val rawWav = File(workDir, "nabra_raw_${UUID.randomUUID()}.wav")
                val masteredWav = File(workDir, "nabra_fx_${UUID.randomUUID()}.wav")
                try {
                    val rawGenerated = generateChunkWavViaOfflineTts(
                        tts = tts,
                        text = chunk,
                        sid = sid,
                        speed = 1.0f,
                        outWavFile = rawWav,
                    )
                    if (!rawGenerated || !rawWav.exists() || rawWav.length() <= 44L) {
                        return@withContext null
                    }

                    val masteredBuffer = LocalArabicTtsEngine.applyStudioProfileWithFfmpegOrDsp(
                        rawWavFile = rawWav,
                        outWavFile = masteredWav,
                        profile = profile,
                        targetSpeed = targetSpeed,
                        targetPitch = targetPitch,
                        style = style,
                        luxuryBoost = luxuryBoost,
                    ) ?: return@withContext null

                    if (chunks.size == 1) {
                        singleChunkFile = masteredWav
                        processedChunks += masteredBuffer
                    } else {
                        processedChunks += LocalArabicTtsEngine.trimSilenceEdgesAndFade(masteredBuffer, keepPadMs = 45)
                        runCatching { masteredWav.delete() }
                    }
                } finally {
                    runCatching { rawWav.delete() }
                }
            }
        }

        return@withContext if (chunks.size == 1 && singleChunkFile != null) {
            LocalTtsSynthResult(
                buffer = processedChunks.first(),
                audioFile = singleChunkFile!!,
            )
        } else {
            val pauseMs = (style.breathPauseMs * 0.45f).roundToInt().coerceIn(70, 220)
            val stitched = LocalArabicTtsEngine.stitchSentenceBuffersSmoothly(processedChunks, pauseMs)
                ?: return@withContext null
            val stitchedWav = File(workDir, "nabra_final_${UUID.randomUUID()}.wav")
            writeStereoWavFile(stitchedWav, stitched)
            LocalTtsSynthResult(buffer = stitched, audioFile = stitchedWav)
        }
    }

    private suspend fun obtainOrCreateOfflineTts(
        modelDir: File,
        dialect: TtsDialect,
    ): Any? = ttsMutex.withLock {
        val dirPath = modelDir.absolutePath
        cachedOfflineTts?.let { existing ->
            if (cachedModelDir == dirPath) return@withLock existing
        }
        releaseCachedTtsInternal()

        return@withLock runCatching {
            val modelPath = File(modelDir, FILE_MODEL).absolutePath
            val voicesPath = File(modelDir, FILE_VOICES).absolutePath
            val tokensPath = File(modelDir, FILE_TOKENS).absolutePath

            val kokoroConfig = createKokoroModelConfig(
                modelPath = modelPath,
                voicesPath = voicesPath,
                tokensPath = tokensPath,
                lang = if (dialect.id == "en_us") "en" else "ar",
            ) ?: return@runCatching null

            val modelConfig = createOfflineTtsModelConfig(kokoroConfig) ?: return@runCatching null
            val ttsConfig = createOfflineTtsConfig(modelConfig) ?: return@runCatching null

            val ttsClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
            val assetCtor = ttsClass.constructors.firstOrNull { ctor ->
                val p = ctor.parameterTypes
                p.size == 2 && p[0] == AssetManager::class.java && p[1].name.endsWith("OfflineTtsConfig")
            }
            val configOnlyCtor = ttsClass.constructors.firstOrNull { ctor ->
                val p = ctor.parameterTypes
                p.size == 1 && p[0].name.endsWith("OfflineTtsConfig")
            }
            val instance = when {
                assetCtor != null -> assetCtor.newInstance(null, ttsConfig)
                configOnlyCtor != null -> configOnlyCtor.newInstance(ttsConfig)
                else -> null
            }
            if (instance != null) {
                cachedOfflineTts = instance
                cachedModelDir = dirPath
            }
            instance
        }.onFailure { err ->
            AppDiagnosticLog.logRecoverable(
                toolName = "NabraOnnxDirectEngine",
                titleAr = "تعذر تهيئة محرك Sherpa-ONNX Kokoro",
                titleEn = "Sherpa-ONNX OfflineTts Init Failed",
                causeAr = err.localizedMessage ?: err.javaClass.simpleName,
                causeEn = err.localizedMessage ?: err.javaClass.simpleName,
                errorCode = "SHERPA_INIT_ERR",
                throwable = err,
            )
        }.getOrNull()
    }

    private fun createKokoroModelConfig(
        modelPath: String,
        voicesPath: String,
        tokensPath: String,
        lang: String,
    ): Any? {
        val cls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig")
        val noArgCtor = cls.constructors.firstOrNull { it.parameterCount == 0 }
        if (noArgCtor != null) {
            val cfg = noArgCtor.newInstance()
            invokeSetter(cfg, "setModel", modelPath)
            invokeSetter(cfg, "setVoices", voicesPath)
            invokeSetter(cfg, "setTokens", tokensPath)
            invokeSetter(cfg, "setDataDir", "")
            invokeSetter(cfg, "setLexicon", "")
            invokeSetter(cfg, "setLang", lang)
            invokeSetter(cfg, "setDictDir", "")
            runCatching {
                cls.getMethod("setLengthScale", Float::class.javaPrimitiveType).invoke(cfg, 1.0f)
            }
            return cfg
        }
        // Full 8-arg Kotlin data class constructor fallback
        val fullCtor = cls.constructors.firstOrNull { ctor ->
            ctor.parameterCount >= 7 && ctor.parameterTypes.take(7).all { it == String::class.java }
        }
        if (fullCtor != null) {
            val args = arrayOfNulls<Any?>(fullCtor.parameterCount)
            args[0] = modelPath
            args[1] = voicesPath
            args[2] = tokensPath
            args[3] = "" // dataDir
            args[4] = "" // lexicon
            args[5] = lang
            args[6] = "" // dictDir
            if (fullCtor.parameterCount >= 8) {
                args[7] = 1.0f
            }
            return fullCtor.newInstance(*args)
        }
        return null
    }

    private fun createOfflineTtsModelConfig(kokoroConfig: Any): Any? {
        val cls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsModelConfig")
        val noArgCtor = cls.constructors.firstOrNull { it.parameterCount == 0 } ?: return null
        val modelCfg = noArgCtor.newInstance()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        cls.methods.firstOrNull { it.name == "setKokoro" && it.parameterCount == 1 }?.invoke(modelCfg, kokoroConfig)
        runCatching { cls.getMethod("setNumThreads", Int::class.javaPrimitiveType).invoke(modelCfg, threads) }
        runCatching { cls.getMethod("setDebug", Boolean::class.javaPrimitiveType).invoke(modelCfg, false) }
        runCatching { cls.getMethod("setProvider", String::class.java).invoke(modelCfg, "cpu") }
        return modelCfg
    }

    private fun createOfflineTtsConfig(modelConfig: Any): Any? {
        val cls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsConfig")
        val noArgCtor = cls.constructors.firstOrNull { it.parameterCount == 0 } ?: return null
        val ttsCfg = noArgCtor.newInstance()
        cls.methods.firstOrNull { it.name == "setModel" && it.parameterCount == 1 }?.invoke(ttsCfg, modelConfig)
        runCatching { cls.getMethod("setMaxNumSentences", Int::class.javaPrimitiveType).invoke(ttsCfg, 1) }
        return ttsCfg
    }

    private fun resolveSpeakerId(tts: Any, gender: TtsGender): Int {
        val numSpeakers = runCatching {
            tts.javaClass.getMethod("numSpeakers").invoke(tts) as? Int
        }.getOrNull() ?: 1
        return if (numSpeakers <= 1) 0 else if (gender == TtsGender.FEMALE) 0 else 1.coerceAtMost(numSpeakers - 1)
    }

    private fun generateChunkWavViaOfflineTts(
        tts: Any,
        text: String,
        sid: Int,
        speed: Float,
        outWavFile: File,
    ): Boolean {
        return runCatching {
            val generateMethod = tts.javaClass.methods.firstOrNull { m ->
                m.name == "generate" &&
                    m.parameterCount == 3 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[2] == Float::class.javaPrimitiveType
            } ?: return@runCatching false

            val generatedAudio = generateMethod.invoke(tts, text, sid, speed) ?: return@runCatching false

            val samples = runCatching {
                generatedAudio.javaClass.getMethod("getSamples").invoke(generatedAudio) as? FloatArray
            }.getOrNull()
            val sampleRate = runCatching {
                generatedAudio.javaClass.getMethod("getSampleRate").invoke(generatedAudio) as? Int
            }.getOrNull()?.takeIf { it in 8000..96000 } ?: 24000

            if (samples != null && samples.isNotEmpty()) {
                val monoClamped = FloatArray(samples.size) { i -> clamp(samples[i], -1f, 1f) }
                val rawBuf = StereoAudioBuffer(monoClamped, monoClamped.copyOf(), sampleRate)
                val targetBuf = if (sampleRate == TARGET_SAMPLE_RATE) rawBuf else resampleStereo(rawBuf, TARGET_SAMPLE_RATE)
                writeStereoWavFile(outWavFile, targetBuf)
                return@runCatching outWavFile.exists() && outWavFile.length() > 44L
            }

            // Fallback: use GeneratedAudio.save(filename)
            val saved = runCatching {
                val saveMethod = generatedAudio.javaClass.getMethod("save", String::class.java)
                saveMethod.invoke(generatedAudio, outWavFile.absolutePath) as? Boolean
            }.getOrNull() ?: false

            saved && outWavFile.exists() && outWavFile.length() > 44L
        }.getOrDefault(false)
    }

    private fun invokeSetter(target: Any, methodName: String, value: String) {
        runCatching {
            target.javaClass.getMethod(methodName, String::class.java).invoke(target, value)
        }
    }

    private suspend fun downloadAssetViaJavaNetUrl(
        urls: List<String>,
        destFile: File,
        minValidBytes: Long,
        onBytesProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): Long {
        var lastError: Throwable? = null
        val partFile = File(destFile.parentFile, "${destFile.name}.part")

        for (candidateUrl in urls) {
            var conn: HttpURLConnection? = null
            try {
                if (partFile.exists()) partFile.delete()
                conn = openUrlWithRedirects(candidateUrl)
                val totalLength = conn.contentLengthLong
                var readTotal = 0L

                BufferedInputStream(conn.inputStream, 32 * 1024).use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var lastUiNotifyMs = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n > 0) {
                                output.write(buffer, 0, n)
                                readTotal += n
                                val now = System.currentTimeMillis()
                                if (now - lastUiNotifyMs >= 120L) {
                                    lastUiNotifyMs = now
                                    onBytesProgress(readTotal, totalLength)
                                }
                            }
                        }
                        output.flush()
                    }
                }
                onBytesProgress(readTotal, if (totalLength > 0L) totalLength else readTotal)

                if (partFile.length() < minValidBytes) {
                    throw IllegalStateException("Downloaded ${destFile.name} is too small (${partFile.length()} bytes)")
                }

                if (destFile.exists()) destFile.delete()
                if (!partFile.renameTo(destFile)) {
                    partFile.copyTo(destFile, overwrite = true)
                    partFile.delete()
                }
                return destFile.length()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                runCatching { partFile.delete() }
                throw ce
            } catch (t: Throwable) {
                runCatching { partFile.delete() }
                lastError = t
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        throw lastError ?: IllegalStateException("Unable to download ${destFile.name}")
    }

    private fun openUrlWithRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        for (hop in 0 until 8) {
            val url = URL(currentUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "SonixStudio-Android/1.0")
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code in 300..308 && code != 304) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("HTTP $code redirect without Location header")
                }
                currentUrl = URL(url, location).toString()
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("HTTP $code downloading $currentUrl")
            }
            return conn
        }
        throw IllegalStateException("Too many redirects for $initialUrl")
    }

    fun releaseCachedTts() {
        releaseCachedTtsInternal()
    }

    private fun releaseCachedTtsInternal() {
        val current = cachedOfflineTts
        cachedOfflineTts = null
        cachedModelDir = null
        if (current != null) {
            runCatching { current.javaClass.getMethod("release").invoke(current) }
        }
    }
}
