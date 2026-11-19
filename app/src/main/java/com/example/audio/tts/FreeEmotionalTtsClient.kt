package com.example.audio.tts

import android.content.Context
import com.example.model.AppLanguage
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsGender
import com.example.model.TtsPersonaAcousticMode
import com.example.model.TtsVoicePersona

sealed interface FreeEmotionalTtsOutcome {
    data class Success(val result: TtsSynthResult) : FreeEmotionalTtsOutcome
    data class Error(val message: String) : FreeEmotionalTtsOutcome
}

object FreeEmotionalTtsClient {
    private const val PREFS_NAME = "sonix_free_tts_prefs"
    private const val KEY_LUXURY_ENHANCEMENT = "studio_luxury_enhancement"

    fun getSavedStudioLuxuryEnhancement(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LUXURY_ENHANCEMENT, true)

    fun saveStudioLuxuryEnhancement(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LUXURY_ENHANCEMENT, enabled)
            .apply()
    }

    suspend fun synthesize(
        context: Context,
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
        language: AppLanguage = AppLanguage.AR,
        studioLuxuryEnhancement: Boolean = getSavedStudioLuxuryEnhancement(context),
        persona: TtsVoicePersona? = null,
        acousticMode: TtsPersonaAcousticMode? = null,
    ): FreeEmotionalTtsOutcome =
        when (
            val outcome = SmartVoiceRouter().synthesizeStudioVoiceWithStatus(
                context = context,
                text = text,
                dialect = dialect,
                gender = gender,
                style = style,
                speechRate = speechRate,
                pitch = pitch,
                language = language,
                studioLuxuryEnhancement = studioLuxuryEnhancement,
                persona = persona,
                acousticMode = acousticMode,
            )
        ) {
            is VoiceRouteOutcome.Success -> FreeEmotionalTtsOutcome.Success(outcome.result)
            is VoiceRouteOutcome.ProviderError -> FreeEmotionalTtsOutcome.Error(outcome.message)
        }
}
