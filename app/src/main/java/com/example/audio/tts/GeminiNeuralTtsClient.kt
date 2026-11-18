package com.example.audio.tts

import android.content.Context
import com.example.audio.ai.SonixUnifiedAIEngine
import com.example.model.AppLanguage
import com.example.model.StereoAudioBuffer
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsGender
import java.io.File

internal data class GeminiTtsAttempt(
    val buffer: StereoAudioBuffer?,
    val failureReason: String? = null,
    val usedLocalOnnx: Boolean = false,
)

/**
 * Thin compatibility facade delegating directly to [SonixUnifiedAIEngine]
 * (Hybrid Local ONNX + Gemini 2.5 Audio API).
 */
internal object GeminiNeuralTtsClient {

    suspend fun diacritizeArabicIfAvailable(
        context: Context,
        rawText: String,
        dialect: TtsDialect,
    ): String? = SonixUnifiedAIEngine.diacritizeArabicIfAvailable(
        context = context,
        rawText = rawText,
        dialect = dialect,
    )

    suspend fun synthesizeNaturalHumanSpeech(
        context: Context,
        cacheDir: File,
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        language: AppLanguage = AppLanguage.AR,
    ): GeminiTtsAttempt {
        return when (
            val res = SonixUnifiedAIEngine.synthesizeHumanSpeech(
                context = context,
                cacheDir = cacheDir,
                text = text,
                dialect = dialect,
                gender = gender,
                style = style,
                speechRate = speechRate,
                language = language,
            )
        ) {
            is SonixUnifiedAIEngine.UnifiedSpeechResult.Success ->
                GeminiTtsAttempt(
                    buffer = res.buffer,
                    failureReason = null,
                    usedLocalOnnx = res.usedLocalOnnx,
                )
            is SonixUnifiedAIEngine.UnifiedSpeechResult.Offline ->
                GeminiTtsAttempt(buffer = null, failureReason = res.message)
            is SonixUnifiedAIEngine.UnifiedSpeechResult.Error ->
                GeminiTtsAttempt(buffer = null, failureReason = res.message)
        }
    }
}
