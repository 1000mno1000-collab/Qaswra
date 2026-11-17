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

    fun isAvailable(): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context.applicationContext)
    }.getOrDefault(false)

    fun setLocale(localeTag: String) {
        currentLocaleTag = localeTag
        _state.update { it.copy(localeTag = localeTag) }
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
            beginSessionOnMain()
        }
    }

    fun stop() {
        keepListening = false
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
                )
            }
        }
    }

    private fun beginSessionOnMain() {
        if (!keepListening) return
        runCatching { recognizer?.destroy() }
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
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                _state.update { it.copy(rmsDb = rmsdB.coerceAtLeast(0f)) }
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                val shouldRetry = keepListening && (
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    )
                if (shouldRetry) {
                    mainHandler.postDelayed({ beginSessionOnMain() }, 240L)
                    return
                }
                if (error == SpeechRecognizer.ERROR_CLIENT) return
                keepListening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error while capturing speech."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for speech recognition."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech recognizer network unavailable (enable offline speech pack or check connection)."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy; please retry."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "Selected recognition language pack is not installed on this device."
                    else -> "Speech recognition stopped (code $error)."
                }
                _state.update { cur ->
                    cur.copy(
                        isListening = false,
                        isRoutingClip = false,
                        committedText = cur.displayText,
                        partialText = "",
                        rmsDb = 0f,
                        errorMessage = msg,
                    )
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val top = matches?.firstOrNull()?.trim().orEmpty()
                if (top.isNotEmpty()) {
                    _state.update { cur ->
                        val nextCommitted = if (cur.committedText.isBlank()) top else "${cur.committedText.trim()} $top"
                        cur.copy(committedText = nextCommitted, partialText = "")
                    }
                } else {
                    _state.update { it.copy(partialText = "") }
                }
                if (keepListening) {
                    mainHandler.postDelayed({ beginSessionOnMain() }, 180L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotEmpty()) {
                    _state.update { it.copy(partialText = partial) }
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
        }
        runCatching { sr.startListening(intent) }.onFailure { err ->
            keepListening = false
            _state.update {
                it.copy(
                    isListening = false,
                    isRoutingClip = false,
                    errorMessage = err.localizedMessage ?: "Failed to start SpeechRecognizer",
                )
            }
        }
    }

    fun destroy() {
        keepListening = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }
}
