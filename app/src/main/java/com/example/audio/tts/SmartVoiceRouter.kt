package com.example.audio.tts

import android.content.Context
import com.example.audio.EdgeTtsClient
import com.example.audio.EdgeTtsSynthResult
import com.example.audio.OnlineGuard
import com.example.audio.network.NetworkFetchOutcome
import com.example.audio.network.NetworkImmunityManager
import com.example.model.AppLanguage
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsEngineMode
import com.example.model.TtsGender
import com.example.util.SafeTextGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.util.Locale

sealed interface VoiceRouteOutcome {
    data class Success(val result: EdgeTtsSynthResult, val engineUsed: TtsEngineMode) : VoiceRouteOutcome
    data object Offline : VoiceRouteOutcome
    data class ProviderError(val message: String) : VoiceRouteOutcome
}

class SmartVoiceRouter {

    /**
     * يوجّه النص إلى محركات النطق العصبي السحابي المجانية 100% (أونلاين فقط، بدون أي مفتاح API):
     * 1. Microsoft Edge Neural TTS (الأصوات العصبية باللهجات العربية والعالمية)
     * 2. Google Translate Neural TTS HD + معالجة دفء ونقاء مخارج الحروف عبر FFmpegKit
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
     * يوجّه النص مباشرة لمحرك Edge Neural TTS ثم Google Translate TTS HD المجاني السحابي
     * دون أي خوادم معطلة أو مدفوعة (404 / 402).
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
        val edgeBytes = EdgeTtsClient.synthesizeRawMp3(
            text = cleanText,
            voiceName = voiceCode,
            localeTag = localeTag,
        )
        if (edgeBytes != null && NetworkImmunityManager.isValidAudioPayload(edgeBytes, "audio/mpeg")) {
            val wrote = runCatching {
                outputFile.writeBytes(edgeBytes)
                outputFile.length() > 512L
            }.getOrDefault(false)
            if (wrote) return@withContext true
        }

        val googleOk = tryGoogleTranslateTtsHd(cleanText, localeTag, outputFile, context)
        googleOk && outputFile.length() > 512L
    }

    suspend fun synthesizeStudioVoice(
        context: Context,
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
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
            )
        ) {
            is VoiceRouteOutcome.Success -> outcome.result
            else -> null
        }
    }

    private suspend fun tryGoogleTranslateTtsHd(
        text: String,
        localeTag: String,
        out: File,
        context: Context? = null,
    ): Boolean {
        val tl = localeTag.substringBefore('-').lowercase(Locale.US).ifBlank { "ar" }
        val chunks = splitTextIntoChunks(text, maxChars = 180)
        val combined = ByteArrayOutputStream()
        for (chunk in chunks) {
            val encoded = URLEncoder.encode(chunk, "UTF-8")
            val urls = listOf(
                "https://translate.googleapis.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$tl&q=$encoded",
                "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$tl&q=$encoded",
                "https://translate.googleapis.com/translate_tts?ie=UTF-8&client=gtx&tl=$tl&q=$encoded",
            )
            val outcome = NetworkImmunityManager.fetchAudioWithFallback(
                urls = urls,
                context = context,
                headers = mapOf("Referer" to "https://translate.google.com/"),
                providerLabel = "GoogleCloudTTS($tl)",
            )
            val chunkBytes = (outcome as? NetworkFetchOutcome.Success)?.value?.bytes ?: return false
            combined.write(chunkBytes)
        }
        val payload = combined.toByteArray()
        if (payload.size <= 512) return false
        return runCatching {
            out.writeBytes(payload)
            true
        }.getOrDefault(false)
    }

    private fun splitTextIntoChunks(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val result = mutableListOf<String>()
        val words = text.split(Regex("\\s+"))
        val current = StringBuilder()
        for (word in words) {
            if (word.isEmpty()) continue
            if (word.length > maxChars) {
                if (current.isNotEmpty()) {
                    result.add(current.toString().trim())
                    current.clear()
                }
                result.addAll(SafeTextGuard.safeChunkWord(word, maxChars))
            } else if (current.length + word.length + 1 > maxChars) {
                result.add(current.toString().trim())
                current.clear()
                current.append(word)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString().trim())
        return result.ifEmpty { listOf(text.take(maxChars)) }
    }
}
