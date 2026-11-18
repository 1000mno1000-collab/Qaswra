package com.example.audio.tts

import android.content.Context
import com.aistudio.voicepro.sonixk.LocalArabicTtsEngine
import com.aistudio.voicepro.sonixk.LocalTtsOutcome
import com.aistudio.voicepro.sonixk.LocalTtsSynthResult
import com.example.model.AppLanguage
import com.example.model.TTS_DIALECTS
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsEngineMode
import com.example.model.TtsGender
import com.example.model.TtsPersonaAcousticMode
import com.example.model.TtsVoicePersona
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface VoiceRouteOutcome {
    data class Success(val result: LocalTtsSynthResult, val engineUsed: TtsEngineMode) : VoiceRouteOutcome
    data class ProviderError(val message: String) : VoiceRouteOutcome
}

class SmartVoiceRouter {

    /**
     * يوجّه النص مباشرة إلى المحرك المحلي [LocalArabicTtsEngine] (Android TextToSpeech + FFmpegKit)
     * للعمل الفوري 100% بدون إنترنت.
     */
    suspend fun synthesizeStudioVoiceWithStatus(
        context: Context,
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
        language: AppLanguage = AppLanguage.AR,
        studioLuxuryEnhancement: Boolean = FreeEmotionalTtsClient.getSavedStudioLuxuryEnhancement(context),
        persona: TtsVoicePersona? = null,
        acousticMode: TtsPersonaAcousticMode? = null,
    ): VoiceRouteOutcome = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return@withContext VoiceRouteOutcome.ProviderError(
                if (language == AppLanguage.EN) "Please enter text first." else "يرجى إدخال النص أولاً.",
            )
        }

        when (
            val outcome = LocalArabicTtsEngine.synthesize(
                context = context,
                text = cleanText,
                dialect = dialect,
                gender = gender,
                style = style,
                speed = speechRate,
                pitch = pitch,
                language = language,
                studioLuxuryEnhancement = studioLuxuryEnhancement,
                persona = persona,
                acousticMode = acousticMode,
            )
        ) {
            is LocalTtsOutcome.Success -> VoiceRouteOutcome.Success(
                result = outcome.result,
                engineUsed = TtsEngineMode.LOCAL_ARABIC_STUDIO,
            )
            is LocalTtsOutcome.Error -> VoiceRouteOutcome.ProviderError(outcome.message)
        }
    }

    suspend fun generateNaturalVoice(
        text: String,
        dialect: TtsDialect = TTS_DIALECTS.first(),
        gender: TtsGender = TtsGender.MALE,
        outputFile: File,
        context: Context,
    ): Boolean = withContext(Dispatchers.IO) {
        val outcome = LocalArabicTtsEngine.synthesize(
            context = context,
            text = text,
            dialect = dialect,
            gender = gender,
            style = TtsDeliveryStyle.NATURAL_WARM,
            speed = 1.0f,
            pitch = 1.0f,
        )
        if (outcome is LocalTtsOutcome.Success) {
            outputFile.parentFile?.mkdirs()
            val copied = runCatching {
                outcome.result.audioFile.copyTo(outputFile, overwrite = true)
                true
            }.getOrDefault(false)
            runCatching { outcome.result.audioFile.delete() }
            copied && outputFile.exists() && outputFile.length() > 44L
        } else {
            false
        }
    }

    suspend fun synthesizeStudioVoice(
        context: Context,
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
        persona: TtsVoicePersona? = null,
        acousticMode: TtsPersonaAcousticMode? = null,
    ): LocalTtsSynthResult? = withContext(Dispatchers.IO) {
        when (
            val outcome = synthesizeStudioVoiceWithStatus(
                context = context,
                text = text,
                dialect = dialect,
                gender = gender,
                style = style,
                speechRate = speechRate,
                pitch = pitch,
                persona = persona,
                acousticMode = acousticMode,
            )
        ) {
            is VoiceRouteOutcome.Success -> outcome.result
            else -> null
        }
    }
}
