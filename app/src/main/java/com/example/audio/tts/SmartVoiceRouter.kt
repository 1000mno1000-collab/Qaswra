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

sealed interface VoiceRouteOutcome {
    data class Success(val result: EdgeTtsSynthResult, val engineUsed: TtsEngineMode) : VoiceRouteOutcome
    data object Offline : VoiceRouteOutcome
    data class ProviderError(val message: String) : VoiceRouteOutcome
}

class SmartVoiceRouter {

    /**
     * يوجّه النص مباشرة إلى محرك الصوت البشري السحابي المجاني المفتوح (100% أونلاين، بدون مفتاح API):
     * [TtsEngineMode.FREE_EMOTIONAL_NEURAL]: Pollinations OpenAI Audio مع لهجات ومشاعر وتنفس طبيعي.
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
     * يوجّه النص لمحركات الصوت البشري السحابية المتاحة (Pollinations OpenAI Audio ثم EdgeTTS / StreamElements)
     * دون أي استخدام لمحرك أوفلاين أو روابط translate_tts.
     */
    suspend fun generateNaturalVoice(
        text: String,
        voiceCode: String = "ar-IQ-BasselNeural",
        outputFile: File,
        context: Context? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanText = ArabicTextDiacritizer.normalizeAndHintForTts(text.trim())
        if (cleanText.isEmpty()) return@withContext false
        if (!NetworkImmunityManager.pingReachable(context)) return@withContext false

        outputFile.parentFile?.mkdirs()

        if (tryPollinationsOpenAiAudio(cleanText, voiceCode, outputFile, context) && outputFile.length() > 512L) {
            return@withContext true
        }

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

        val streamOk = tryStreamElementsNeural(cleanText, voiceCode, outputFile, context)
        streamOk && outputFile.length() > 512L
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

    private suspend fun tryPollinationsOpenAiAudio(
        text: String,
        voiceCode: String,
        out: File,
        context: Context? = null,
    ): Boolean {
        val lower = voiceCode.lowercase()
        val isFemale = lower.contains("rana") || lower.contains("zariyah") ||
            lower.contains("salma") || lower.contains("amany") ||
            lower.contains("sana") || lower.contains("mouna") ||
            lower.contains("fatima") || lower.contains("reem") ||
            lower.contains("ava") || lower.contains("emel") ||
            lower.contains("dilara") || lower.contains("female")
        val voice = if (isFemale) "nova" else "onyx"
        val prompt = "Read ONLY this text naturally with clear human articulation and zero commentary: $text"
        val encoded = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
        val primaryUrl = "https://text.pollinations.ai/$encoded?model=openai-audio&voice=$voice"
        val fallbackUrl = "https://text.pollinations.ai/$encoded?model=openai-audio&voice=alloy"
        val outcome = NetworkImmunityManager.fetchAudioWithFallback(
            urls = listOf(primaryUrl, fallbackUrl),
            context = context,
            headers = mapOf("Referer" to "https://pollinations.ai/"),
            providerLabel = "SmartVoiceRouter.Pollinations",
        )
        val bytes = (outcome as? NetworkFetchOutcome.Success)?.value?.bytes ?: return false
        return runCatching {
            out.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    private suspend fun tryStreamElementsNeural(
        text: String,
        voiceCode: String,
        out: File,
        context: Context? = null,
    ): Boolean {
        val seVoices = resolveStreamElementsVoices(voiceCode)
        if (seVoices.isEmpty()) return false

        val chunks = splitTextIntoChunks(text, maxChars = 420)
        for (seVoice in seVoices) {
            val combined = ByteArrayOutputStream()
            var allChunksOk = true
            for (chunk in chunks) {
                val encoded = URLEncoder.encode(chunk, "UTF-8")
                val apiUrl = "https://api.streamelements.com/kappa/v2/speech?voice=$seVoice&text=$encoded"
                val outcome = NetworkImmunityManager.fetchAudioWithFallback(
                    urls = listOf(apiUrl),
                    context = context,
                    providerLabel = "StreamElements($seVoice)",
                )
                val chunkBytes = (outcome as? NetworkFetchOutcome.Success)?.value?.bytes
                if (chunkBytes == null) {
                    allChunksOk = false
                    break
                }
                combined.write(chunkBytes)
            }
            val payload = combined.toByteArray()
            if (allChunksOk && payload.size > 1024) {
                return runCatching {
                    out.writeBytes(payload)
                    true
                }.getOrDefault(false)
            }
        }
        return false
    }

    private fun resolveStreamElementsVoices(voiceCode: String): List<String> {
        val lower = voiceCode.lowercase()
        val isFemale = lower.contains("rana") || lower.contains("zariyah") ||
            lower.contains("salma") || lower.contains("amany") ||
            lower.contains("sana") || lower.contains("mouna") ||
            lower.contains("fatima") || lower.contains("reem") ||
            lower.contains("ava") || lower.contains("emel") ||
            lower.contains("dilara") || lower.contains("female")
        return when {
            lower.startsWith("ar") -> if (isFemale) listOf("Zeina", "Hala") else listOf("Zayd", "Zeina")
            lower.startsWith("en") -> if (isFemale) listOf("Joanna", "Amy") else listOf("Matthew", "Brian")
            lower.startsWith("tr") -> listOf("Filiz")
            lower.startsWith("fa") -> emptyList()
            else -> listOf("Brian")
        }
    }

    private fun isLikelyAudioPayload(bytes: ByteArray): Boolean {
        if (bytes.size < 256) return false
        val head = bytes.copyOfRange(0, minOf(32, bytes.size)).toString(Charsets.US_ASCII).trimStart()
        if (head.startsWith("<") || head.startsWith("{")) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val isId3 = head.startsWith("ID3")
        val isMp3FrameSync = b0 == 0xFF && (b1 and 0xE0) == 0xE0
        val isWav = head.startsWith("RIFF")
        val isOgg = head.startsWith("OggS")
        return isId3 || isMp3FrameSync || isWav || isOgg || bytes.size > 2048
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
