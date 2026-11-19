package com.example.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Immutable
import com.example.diagnostics.AppDiagnosticLog
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

@Immutable
data class NativeSpeechState(
    val isListening: Boolean = false,
    val isRoutingClip: Boolean = false,
    val localeTag: String = "ar-IQ",
    val partialText: String = "",
    val committedText: String = "",
    val rmsDb: Float = 0f,
    val errorMessage: String? = null,
    val backendMode: SttBackendMode = SttBackendMode.AUTO,
    val activeEngineLabel: String = "",
    val localModelState: SherpaAsrModelState = SherpaAsrModelState(),
) {
    val displayText: String
        get() = listOf(committedText.trim(), partialText.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}

class StudioSpeechTranscriber(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val localMoonshineEngine = SherpaMoonshineAsrEngine(context.applicationContext)

    private val _state = MutableStateFlow(
        NativeSpeechState(localModelState = localMoonshineEngine.modelState.value),
    )
    val state: StateFlow<NativeSpeechState> = _state.asStateFlow()

    private val sessionGuard = Any()
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var keepListening = false
    @Volatile private var currentLocaleTag = "ar-IQ"
    @Volatile private var currentSessionId = 0L
    @Volatile private var currentBackendMode = SttBackendMode.AUTO
    @Volatile private var lastSessionStartMs = 0L
    @Volatile private var consecutiveFastDisconnects = 0

    private var endOfSpeechWatchdog: Runnable? = null

    init {
        scope.launch {
            localMoonshineEngine.modelState.collect { modelSt ->
                _state.update { it.copy(localModelState = modelSt) }
            }
        }
    }

    fun isAvailable(): Boolean {
        val localReady = localMoonshineEngine.isModelReady() && localMoonshineEngine.isNativeLibraryAvailable()
        val systemAvailable = runCatching {
            SpeechRecognizer.isRecognitionAvailable(context.applicationContext)
        }.getOrDefault(false)
        return localReady || systemAvailable
    }

    fun isLocalArabicModelReady(): Boolean = localMoonshineEngine.isModelReady()

    fun setBackendMode(mode: SttBackendMode) {
        currentBackendMode = mode
        _state.update { it.copy(backendMode = mode, errorMessage = null) }
        if (keepListening) {
            val clip = _state.value.isRoutingClip
            startListening(currentLocaleTag, isClipRouting = clip)
        }
    }

    suspend fun downloadLocalArabicModel(): Boolean = localMoonshineEngine.ensureModelsDownloaded()

    fun cancelLocalModelDownload() = localMoonshineEngine.cancelDownload()

    fun clearLocalArabicModel() {
        if (localMoonshineEngine.isCurrentlyCapturing()) {
            stop()
        }
        localMoonshineEngine.clearCachedModels()
    }

    fun setLocale(localeTag: String) {
        currentLocaleTag = localeTag
        _state.update { it.copy(localeTag = localeTag) }
        if (keepListening) {
            val clip = _state.value.isRoutingClip
            startListening(localeTag, isClipRouting = clip)
        }
    }

    fun setEditedText(newText: String) {
        _state.update {
            it.copy(
                committedText = newText,
                partialText = "",
                errorMessage = null,
            )
        }
    }

    fun clear() {
        val wasListening = keepListening
        val wasClip = _state.value.isRoutingClip
        val loc = currentLocaleTag
        if (wasListening) {
            synchronized(sessionGuard) {
                currentSessionId++
                localMoonshineEngine.stop()
                cancelEndOfSpeechWatchdog()
                mainHandler.removeCallbacksAndMessages(null)
                destroyAndroidRecognizerOnMain()
            }
        }
        _state.update {
            it.copy(
                committedText = "",
                partialText = "",
                errorMessage = null,
            )
        }
        if (wasListening) {
            startListening(loc, isClipRouting = wasClip)
        }
    }

    fun canTranscribeClipOffline(localeTag: String = currentLocaleTag): Boolean {
        val isArabic = localeTag.startsWith("ar", ignoreCase = true)
        if (!isArabic) return false
        if (currentBackendMode == SttBackendMode.ANDROID_SYSTEM) return false
        return localMoonshineEngine.isModelReady() && localMoonshineEngine.isNativeLibraryAvailable()
    }

    internal fun shouldUseLocalMoonshine(localeTag: String, isClipRouting: Boolean): Boolean {
        if (isClipRouting) return false
        val isArabic = localeTag.startsWith("ar", ignoreCase = true)
        if (!isArabic) return false
        val ready = localMoonshineEngine.isModelReady() && localMoonshineEngine.isNativeLibraryAvailable()
        if (!ready) return false
        return when (currentBackendMode) {
            SttBackendMode.AUTO, SttBackendMode.LOCAL_MOONSHINE_AR -> true
            SttBackendMode.ANDROID_SYSTEM -> false
        }
    }

    fun transcribeBufferOffline(buffer: StereoAudioBuffer): Boolean {
        return launchOfflineClipTranscription {
            resampleStereoToMono16k(buffer)
        }
    }

    fun transcribeAudioFileOffline(file: File): Boolean {
        return launchOfflineClipTranscription {
            localMoonshineEngine.extractMono16kSamplesFromFile(file)
        }
    }

    private fun launchOfflineClipTranscription(
        samplesLoader: suspend () -> FloatArray?,
    ): Boolean {
        if (!canTranscribeClipOffline(currentLocaleTag)) return false
        val mySessionId: Long
        synchronized(sessionGuard) {
            keepListening = false
            mySessionId = ++currentSessionId
            consecutiveFastDisconnects = 0
            localMoonshineEngine.stop()
            cancelEndOfSpeechWatchdog()
            mainHandler.removeCallbacksAndMessages(null)
            destroyAndroidRecognizerOnMain()
            _state.update {
                it.copy(
                    isListening = true,
                    isRoutingClip = true,
                    partialText = "",
                    rmsDb = 4f,
                    errorMessage = null,
                    activeEngineLabel = "Moonshine Arabic v2 (أوفلاين مباشر من المقطع)",
                )
            }
        }

        scope.launch(Dispatchers.IO) {
            val samples = runCatching { samplesLoader() }.getOrNull()
            if (currentSessionId != mySessionId) return@launch
            if (samples == null || samples.isEmpty()) {
                _state.update {
                    it.copy(
                        isListening = false,
                        isRoutingClip = false,
                        rmsDb = 0f,
                        errorMessage = "تعذر تجهيز المقطع للتفريغ النصي المحلي.",
                    )
                }
                return@launch
            }
            val started = localMoonshineEngine.startOfflineClipTranscription(
                samples16k = samples,
                onPartialUpdate = { partial ->
                    if (currentSessionId == mySessionId) {
                        _state.update { it.copy(partialText = partial, errorMessage = null) }
                    }
                },
                onSegmentCommitted = { segment ->
                    if (currentSessionId == mySessionId) {
                        _state.update { cur ->
                            val merged = mergeTranscriptText(cur.committedText, segment)
                            cur.copy(committedText = merged, partialText = "", errorMessage = null)
                        }
                    }
                },
                onCompleted = {
                    if (currentSessionId == mySessionId) {
                        _state.update { cur ->
                            val merged = mergeTranscriptText(cur.committedText, cur.partialText)
                            cur.copy(
                                isListening = false,
                                isRoutingClip = false,
                                committedText = merged,
                                partialText = "",
                                rmsDb = 0f,
                            )
                        }
                    }
                },
                onFatalError = { errMsg ->
                    if (currentSessionId == mySessionId) {
                        _state.update { cur ->
                            cur.copy(
                                isListening = false,
                                isRoutingClip = false,
                                partialText = "",
                                rmsDb = 0f,
                                errorMessage = errMsg,
                            )
                        }
                    }
                },
            )
            if (!started && currentSessionId == mySessionId) {
                _state.update {
                    it.copy(
                        isListening = false,
                        isRoutingClip = false,
                        rmsDb = 0f,
                        errorMessage = "نموذج Moonshine العربي المحلي غير جاهز أو غير مدعوم على هذا الجهاز.",
                    )
                }
            }
        }
        return true
    }

    fun startListening(localeTag: String, isClipRouting: Boolean) {
        synchronized(sessionGuard) {
            currentLocaleTag = localeTag
            keepListening = false
            val mySessionId = ++currentSessionId
            consecutiveFastDisconnects = 0
            localMoonshineEngine.stop()
            cancelEndOfSpeechWatchdog()
            mainHandler.removeCallbacksAndMessages(null)
            destroyAndroidRecognizerOnMain()

            val useLocal = shouldUseLocalMoonshine(localeTag, isClipRouting)
            if (currentBackendMode == SttBackendMode.LOCAL_MOONSHINE_AR && !localMoonshineEngine.isModelReady()) {
                _state.update {
                    it.copy(
                        isListening = false,
                        isRoutingClip = false,
                        activeEngineLabel = "",
                        errorMessage = "يرجى تحميل نموذج Moonshine Arabic المحلي أولاً أو اختيار التبديل التلقائي.",
                    )
                }
                return
            }

            if (useLocal) {
                keepListening = true
                _state.update {
                    it.copy(
                        isListening = true,
                        isRoutingClip = false,
                        localeTag = localeTag,
                        partialText = "",
                        errorMessage = null,
                        activeEngineLabel = "Moonshine Arabic v2 + Silero VAD (محلي مستمر)",
                    )
                }
                val started = localMoonshineEngine.startContinuousCapture(
                    onPartialUpdate = { partial ->
                        if (keepListening && currentSessionId == mySessionId) {
                            _state.update { it.copy(partialText = partial, errorMessage = null) }
                        }
                    },
                    onSegmentCommitted = { segment ->
                        if (currentSessionId == mySessionId) {
                            _state.update { cur ->
                                val merged = mergeTranscriptText(cur.committedText, segment)
                                cur.copy(committedText = merged, partialText = "", errorMessage = null)
                            }
                        }
                    },
                    onRmsDb = { db ->
                        if (keepListening && currentSessionId == mySessionId) {
                            _state.update { it.copy(rmsDb = db) }
                        }
                    },
                    onFatalError = { errMsg ->
                        if (currentSessionId == mySessionId) {
                            keepListening = false
                            _state.update { cur ->
                                cur.copy(
                                    isListening = false,
                                    isRoutingClip = false,
                                    partialText = "",
                                    committedText = cur.displayText,
                                    rmsDb = 0f,
                                    errorMessage = errMsg,
                                )
                            }
                        }
                    },
                )
                if (started) return
                // Fallback safely if native library cannot initialize on this ABI/device
                keepListening = false
            }

            mainHandler.post {
                if (currentSessionId != mySessionId) return@post
                val systemAvail = runCatching {
                    SpeechRecognizer.isRecognitionAvailable(context.applicationContext)
                }.getOrDefault(false)
                if (!systemAvail) {
                    _state.update {
                        it.copy(
                            isListening = false,
                            isRoutingClip = false,
                            activeEngineLabel = "",
                            errorMessage = "خدمة التعرف على الكلام غير متوفرة — حمّل نموذج Moonshine العربي للعمل محلياً.",
                        )
                    }
                    return@post
                }
                keepListening = true
                _state.update {
                    it.copy(
                        isListening = true,
                        isRoutingClip = isClipRouting,
                        localeTag = localeTag,
                        partialText = "",
                        errorMessage = null,
                        activeEngineLabel = "Android SpeechRecognizer (${localeTag})",
                    )
                }
                beginAndroidSessionOnMain(mySessionId)
            }
        }
    }

    fun stop() {
        synchronized(sessionGuard) {
            keepListening = false
            currentSessionId++
            consecutiveFastDisconnects = 0
            localMoonshineEngine.stop()
            cancelEndOfSpeechWatchdog()
            mainHandler.removeCallbacksAndMessages(null)
            destroyAndroidRecognizerOnMain()
            _state.update { cur ->
                val merged = mergeTranscriptText(cur.committedText, cur.partialText)
                cur.copy(
                    isListening = false,
                    isRoutingClip = false,
                    partialText = "",
                    committedText = merged,
                    rmsDb = 0f,
                    errorMessage = null,
                )
            }
        }
    }

    private fun destroyAndroidRecognizerOnMain() {
        val r = recognizer
        recognizer = null
        if (r != null) {
            mainHandler.post {
                runCatching { r.stopListening() }
                runCatching { r.cancel() }
                runCatching { r.destroy() }
            }
        }
    }

    private fun cancelEndOfSpeechWatchdog() {
        endOfSpeechWatchdog?.let { mainHandler.removeCallbacks(it) }
        endOfSpeechWatchdog = null
    }

    private fun commitPartialIntoTranscript() {
        _state.update { cur ->
            val merged = mergeTranscriptText(cur.committedText, cur.partialText)
            cur.copy(committedText = merged, partialText = "")
        }
    }

    private fun scheduleNextLoop(delayMs: Long) {
        if (!keepListening) return
        cancelEndOfSpeechWatchdog()
        val nextId = ++currentSessionId
        mainHandler.postDelayed({
            if (keepListening && currentSessionId == nextId) {
                beginAndroidSessionOnMain(nextId)
            }
        }, delayMs)
    }

    private fun beginAndroidSessionOnMain(sessionId: Long) {
        if (!keepListening || sessionId != currentSessionId) return
        cancelEndOfSpeechWatchdog()
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null

        val sr = runCatching { SpeechRecognizer.createSpeechRecognizer(context.applicationContext) }.getOrNull()
        if (sr == null) {
            keepListening = false
            _state.update {
                it.copy(
                    isListening = false,
                    isRoutingClip = false,
                    errorMessage = "تعذر تهيئة خدمة Android SpeechRecognizer.",
                )
            }
            return
        }
        recognizer = sr
        lastSessionStartMs = System.currentTimeMillis()
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sessionId != currentSessionId) return
                _state.update { it.copy(errorMessage = null) }
            }

            override fun onBeginningOfSpeech() {
                if (sessionId != currentSessionId) return
                consecutiveFastDisconnects = 0
                cancelEndOfSpeechWatchdog()
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (sessionId != currentSessionId) return
                _state.update { it.copy(rmsDb = rmsdB.coerceAtLeast(0f)) }
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (!keepListening || sessionId != currentSessionId) return
                cancelEndOfSpeechWatchdog()
                val watchdog = Runnable {
                    if (keepListening && sessionId == currentSessionId) {
                        commitPartialIntoTranscript()
                        scheduleNextLoop(delayMs = 160L)
                    }
                }
                endOfSpeechWatchdog = watchdog
                mainHandler.postDelayed(watchdog, 2_800L)
            }

            override fun onError(error: Int) {
                if (sessionId != currentSessionId) return
                cancelEndOfSpeechWatchdog()
                commitPartialIntoTranscript()

                val elapsed = System.currentTimeMillis() - lastSessionStartMs
                if (elapsed < 1_500L && (error == 11 || error == 10 || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) {
                    consecutiveFastDisconnects++
                } else if (elapsed >= 3_000L) {
                    consecutiveFastDisconnects = 0
                }

                // Recoverable errors include silence timeouts (6/7), transient client/busy/audio (3/5/8),
                // rate-limit (10), and Android 12+ service disconnects (11: ERROR_SERVER_DISCONNECTED).
                if (keepListening && isRecoverableSpeechError(error)) {
                    // Destroy broken service binder completely before reconnecting
                    runCatching { sr.cancel() }
                    runCatching { sr.destroy() }
                    if (recognizer === sr) recognizer = null

                    // If local Moonshine Arabic model is installed & user got repeated code 11/10 on Arabic, switch automatically!
                    if (error == 11 &&
                        consecutiveFastDisconnects >= 2 &&
                        shouldUseLocalMoonshine(currentLocaleTag, isClipRouting = false)
                    ) {
                        AppDiagnosticLog.logRecoverable(
                            toolName = "StudioSpeechTranscriber",
                            titleAr = "التبديل التلقائي إلى Moonshine المحلي",
                            titleEn = "Auto-switched STT from error 11 to Local Moonshine AR",
                            causeAr = "تكرار خطأ خدمة النظام (code 11) أثناء الإملاء المستمر",
                            causeEn = "Repeated SpeechRecognizer ERROR_SERVER_DISCONNECTED (11)",
                            errorCode = "STT_ERR_11",
                        )
                        startListening(currentLocaleTag, isClipRouting = _state.value.isRoutingClip)
                        return
                    }

                    val delayMs = computeRetryBackoffMs(error, consecutiveFastDisconnects)
                    _state.update { it.copy(errorMessage = null, rmsDb = 0f) }
                    scheduleNextLoop(delayMs = delayMs)
                    return
                }

                keepListening = false
                AppDiagnosticLog.logRecoverable(
                    toolName = "StudioSpeechTranscriber",
                    titleAr = "توقف خدمة التعرف على الكلام (code $error)",
                    titleEn = "SpeechRecognizer stopped (code $error)",
                    causeAr = "خطأ في خدمة التعرف على الكلام على الجهاز",
                    causeEn = "SpeechRecognizer error code=$error",
                    errorCode = "STT_ERR_$error",
                )
                val msg = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "صلاحية الميكروفون مطلوبة للتعرف على الكلام."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "تعذر الاتصال بخدمة الكلام السحابية — يمكنك تحميل نموذج Moonshine المحلي للعمل بدون إنترنت."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "حزمة اللغة المختارة غير مثبتة في خدمة النظام."
                    else -> "توقفت خدمة التعرف على الكلام (code $error)."
                }
                _state.update { cur ->
                    cur.copy(
                        isListening = false,
                        isRoutingClip = false,
                        partialText = "",
                        rmsDb = 0f,
                        errorMessage = msg,
                    )
                }
            }

            override fun onResults(results: Bundle?) {
                if (sessionId != currentSessionId) return
                cancelEndOfSpeechWatchdog()
                consecutiveFastDisconnects = 0
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val top = matches?.firstOrNull()?.trim().orEmpty()
                if (top.isNotEmpty()) {
                    _state.update { cur ->
                        val nextCommitted = mergeTranscriptText(cur.committedText, top)
                        cur.copy(committedText = nextCommitted, partialText = "", errorMessage = null)
                    }
                } else {
                    commitPartialIntoTranscript()
                }
                if (keepListening) {
                    scheduleNextLoop(delayMs = 120L)
                } else {
                    _state.update { it.copy(isListening = false, isRoutingClip = false, rmsDb = 0f) }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (sessionId != currentSessionId) return
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotEmpty()) {
                    consecutiveFastDisconnects = 0
                    _state.update { it.copy(partialText = partial, errorMessage = null) }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLocaleTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLocaleTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 15_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 12_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5_000L)
        }
        runCatching { sr.startListening(intent) }.onFailure { err ->
            if (keepListening) {
                scheduleNextLoop(delayMs = 450L)
            } else {
                _state.update {
                    it.copy(
                        isListening = false,
                        isRoutingClip = false,
                        errorMessage = err.localizedMessage ?: "Failed to start SpeechRecognizer",
                    )
                }
            }
        }
    }

    fun destroy() {
        keepListening = false
        currentSessionId++
        localMoonshineEngine.stop()
        localMoonshineEngine.cancelDownload()
        cancelEndOfSpeechWatchdog()
        mainHandler.removeCallbacksAndMessages(null)
        destroyAndroidRecognizerOnMain()
        scope.cancel()
    }
}
