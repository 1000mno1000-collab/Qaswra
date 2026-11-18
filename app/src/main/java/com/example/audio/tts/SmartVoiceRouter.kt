package com.example.audio.tts

import android.content.Context
import com.example.audio.EdgeTtsClient
import com.example.audio.EdgeTtsSynthResult
import com.example.audio.OnlineGuard
import com.example.audio.network.NetworkImmunityManager
import com.example.model.AppLanguage
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsEngineMode
import com.example.model.TtsGender
import com.example.model.TtsPersonaAcousticMode
import com.example.model.TtsVoicePersona
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

sealed interface VoiceRouteOutcome {
    data class Success(val result: EdgeTtsSynthResult, val engineUsed: TtsEngineMode) : VoiceRouteOutcome
    data object Offline : VoiceRouteOutcome
    data class ProviderError(val message: String) : VoiceRouteOutcome
}

class SmartVoiceRouter {

    /**
     * يوجّه النص مباشرة إلى محرك Microsoft Edge Neural TTS عبر WebSocket (`wss://speech.platform.bing.com`)
     * بصيغة `audio-24khz-48kbitrate-mono-mp3` + معالجة الوثائقي والبودكاست عبر FFmpegKit (أونلاين 100% ومجاني بدون Gemini API).
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
        if (cleanText.isEmpty()) return@withContext VoiceRouteOutcome.ProviderError("النص فارغ")
        if (!OnlineGuard.isOnline(context)) return@withContext VoiceRouteOutcome.Offline

        when (
            val freeOutcome = FreeEmotionalTtsClient.synthesize(
                context = context,
                text = cleanText,
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
            is FreeEmotionalTtsOutcome.Success -> VoiceRouteOutcome.Success(
                result = freeOutcome.result,
                engineUsed = TtsEngineMode.FREE_EMOTIONAL_NEURAL,
            )
            is FreeEmotionalTtsOutcome.Offline -> VoiceRouteOutcome.Offline
            is FreeEmotionalTtsOutcome.Error -> VoiceRouteOutcome.ProviderError(freeOutcome.message)
        }
    }

    /**
     * يوجّه النص مباشرة لمحرك Microsoft Edge Neural TTS عبر WebSocket وحفظه كملف MP3 جاهز للتشغيل.
     */
    suspend fun generateNaturalVoice(
        text: String,
        voiceCode: String = "ar-IQ-BasselNeural",
        outputFile: File,
        context: Context? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanText = ArabicTextDiacritizer.normalizeAndHintForTts(text.trim(), applyLightDiacritics = true)
        if (cleanText.isEmpty()) return@withContext false
        if (!NetworkImmunityManager.pingReachable(context)) return@withContext false

        outputFile.parentFile?.mkdirs()

        val localeTag = voiceCode.split("-").take(2).joinToString("-").ifBlank { "ar-IQ" }
        var edgeBytes = EdgeTtsClient.synthesizeRawMp3(
            text = cleanText,
            voiceName = voiceCode,
            localeTag = localeTag,
        )
        if (edgeBytes == null || !NetworkImmunityManager.isValidAudioPayload(edgeBytes, "audio/mpeg")) {
            val backupVoice = if (voiceCode.contains("Rana", ignoreCase = true) ||
                voiceCode.contains("Zariyah", ignoreCase = true) ||
                voiceCode.contains("Salma", ignoreCase = true) ||
                voiceCode.contains("Amany", ignoreCase = true) ||
                voiceCode.contains("Sana", ignoreCase = true)
            ) {
                "ar-SA-ZariyahNeural"
            } else {
                "ar-SA-HamedNeural"
            }
            if (backupVoice != voiceCode) {
                edgeBytes = EdgeTtsClient.synthesizeRawMp3(
                    text = cleanText,
                    voiceName = backupVoice,
                    localeTag = "ar-SA",
                )
            }
        }
        if (edgeBytes != null && NetworkImmunityManager.isValidAudioPayload(edgeBytes, "audio/mpeg")) {
            return@withContext runCatching {
                BufferedOutputStream(FileOutputStream(outputFile), 16384).use { out ->
                    out.write(edgeBytes)
                    out.flush()
                }
                outputFile.length() > 512L
            }.getOrDefault(false)
        }
        false
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
    ): EdgeTtsSynthResult? = withContext(Dispatchers.IO) {
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
