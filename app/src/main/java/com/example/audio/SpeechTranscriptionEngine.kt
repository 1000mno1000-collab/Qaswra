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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class NativeSpeechState(
    val isListening: Boolean = false,
    val isRoutingClip: Boolean = false,
    val localeTag: String = "ar-SA",
    val partialText: String = "",
    val committedText: String = "",
    val rmsDb: Float = 0f,
    val errorMessage: String? = null,
) {
    val displayText: String
        get() = listOf(committedText.trim(), partialText.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}

class StudioSpeechTranscriber(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(NativeSpeechState())
    val state: StateFlow<NativeSpeechState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    @Volatile private var keepListening = false
    @Volatile private var currentLocaleTag = "ar-SA"
    @Volatile private var currentSessionId = 0L

    private var endOfSpeechWatchdog: Runnable? = null

    fun isAvailable(): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context.applicationContext)
    }.getOrDefault(false)

    fun setLocale(localeTag: String) {
        currentLocaleTag = localeTag
        _state.update { it.copy(localeTag = localeTag) }
        if (keepListening) {
            mainHandler.post {
                if (keepListening) {
                    scheduleNextLoop(delayMs = 120L)
                }
            }
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
        _state.update {
            it.copy(
                committedText = "",
                partialText = "",
                errorMessage = null,
            )
        }
    }

    fun startListening(localeTag: String, isClipRouting: Boolean) {
        currentLocaleTag = localeTag
        mainHandler.post {
            if (!isAvailable()) {
                _state.update {
                    it.copy(
                        isListening = false,
                        isRoutingClip = false,
                        errorMessage = "Speech recognition service is not available on this device.",
                    )
                }
                return@post
            }
            cancelEndOfSpeechWatchdog()
            mainHandler.removeCallbacksAndMessages(null)
            keepListening = true
            _state.update {
                it.copy(
                    isListening = true,
                    isRoutingClip = isClipRouting,
                    localeTag = localeTag,
                    partialText = "",
                    errorMessage = null,
                )
            }
            beginSessionOnMain(++currentSessionId)
        }
    }

    fun stop() {
        keepListening = false
        currentSessionId++
        cancelEndOfSpeechWatchdog()
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            runCatching { recognizer?.stopListening() }
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            _state.update { cur ->
                val merged = cur.displayText
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

    private fun cancelEndOfSpeechWatchdog() {
        endOfSpeechWatchdog?.let { mainHandler.removeCallbacks(it) }
        endOfSpeechWatchdog = null
    }

    private fun commitPartialIntoTranscript() {
        _state.update { cur ->
            val pending = cur.partialText.trim()
            if (pending.isEmpty()) {
                cur
            } else {
                val base = cur.committedText.trim()
                val merged = when {
                    base.isEmpty() -> pending
                    base.endsWith(pending) -> base
                    else -> "$base $pending"
                }
                cur.copy(committedText = merged, partialText = "")
            }
        }
    }

    private fun scheduleNextLoop(delayMs: Long) {
        if (!keepListening) return
        cancelEndOfSpeechWatchdog()
        val nextId = ++currentSessionId
        mainHandler.postDelayed({
            if (keepListening && currentSessionId == nextId) {
                beginSessionOnMain(nextId)
            }
        }, delayMs)
    }

    private fun beginSessionOnMain(sessionId: Long) {
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
                    errorMessage = "Unable to initialize Android SpeechRecognizer.",
                )
            }
            return
        }
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sessionId != currentSessionId) return
                _state.update { it.copy(errorMessage = null) }
            }

            override fun onBeginningOfSpeech() {
                if (sessionId != currentSessionId) return
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
                // Fallback watchdog in case some OEM speech engines stall after onEndOfSpeech
                val watchdog = Runnable {
                    if (keepListening && sessionId == currentSessionId) {
                        commitPartialIntoTranscript()
                        scheduleNextLoop(delayMs = 150L)
                    }
                }
                endOfSpeechWatchdog = watchdog
                mainHandler.postDelayed(watchdog, 2600L)
            }

            override fun onError(error: Int) {
                if (sessionId != currentSessionId) return
                cancelEndOfSpeechWatchdog()

                // Continuous Dictation Loop: preserve any partial words & restart immediately on silence/timeout/busy
                val recoverableLoopError =
                    error == SpeechRecognizer.ERROR_NO_MATCH ||       // code 7
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || // code 6
                        error == SpeechRecognizer.ERROR_CLIENT ||         // code 5
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||// code 8
                        error == SpeechRecognizer.ERROR_AUDIO             // transient audio focus hiccup

                if (keepListening && recoverableLoopError) {
                    commitPartialIntoTranscript()
                    _state.update { it.copy(errorMessage = null, rmsDb = 0f) }
                    val restartDelay = when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 280L
                        SpeechRecognizer.ERROR_AUDIO -> 320L
                        else -> 120L
                    }
                    scheduleNextLoop(delayMs = restartDelay)
                    return
                }

                // Fatal errors only (e.g., revoked mic permission / missing offline pack / network error)
                keepListening = false
                commitPartialIntoTranscript()
                val msg = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Microphone permission is required for speech recognition."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech recognizer network unavailable (enable offline speech pack or check connection)."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "Selected recognition language pack is not installed on this device."
                    else -> "Speech recognition stopped (code $error)."
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
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val top = matches?.firstOrNull()?.trim().orEmpty()
                if (top.isNotEmpty()) {
                    _state.update { cur ->
                        val base = cur.committedText.trim()
                        val nextCommitted = if (base.isEmpty()) top else "$base $top"
                        cur.copy(committedText = nextCommitted, partialText = "", errorMessage = null)
                    }
                } else {
                    commitPartialIntoTranscript()
                }
                if (keepListening) {
                    scheduleNextLoop(delayMs = 110L)
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5_000L)
        }
        runCatching { sr.startListening(intent) }.onFailure { err ->
            if (keepListening) {
                scheduleNextLoop(delayMs = 260L)
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
        cancelEndOfSpeechWatchdog()
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }
}
