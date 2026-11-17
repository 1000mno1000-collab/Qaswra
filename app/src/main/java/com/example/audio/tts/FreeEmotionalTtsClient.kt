package com.example.audio.tts

import android.content.Context
import com.example.audio.AudioDecoder
import com.example.audio.EdgeTtsClient
import com.example.audio.EdgeTtsSynthResult
import com.example.audio.OnlineGuard
import com.example.audio.clamp
import com.example.audio.fx.AudioSkillsPresets
import com.example.model.AppLanguage
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsGender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

sealed interface FreeEmotionalTtsOutcome {
    data class Success(val result: EdgeTtsSynthResult) : FreeEmotionalTtsOutcome
    data class Offline(val message: String) : FreeEmotionalTtsOutcome
    data class Error(val message: String) : FreeEmotionalTtsOutcome
}

/**
 * Free, unlimited, keyless Cloud Neural TTS engine powered by the Edge-TTS WebSocket API.
 * Supports Iraqi Arabic (`ar-IQ-BasselNeural` & `ar-IQ-RanaNeural`), Arabic dialects, and global voices
 * with SSML human emotion shaping (Natural, Happy/Excited, Sad/Calm, Angry/Sharp, Dramatic Cinematic)
 * and natural breathing pauses (`<break time='...'/>`).
 */
object FreeEmotionalTtsClient {

    suspend fun synthesize(
        context: Context,
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
        language: AppLanguage = AppLanguage.AR,
    ): FreeEmotionalTtsOutcome = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return@withContext FreeEmotionalTtsOutcome.Error("يرجى إدخال النص أولاً")
        }

        if (!OnlineGuard.isOnline(context)) {
            return@withContext FreeEmotionalTtsOutcome.Offline(OnlineGuard.offlineErrorMessage(language))
        }

        val voiceName = when (gender) {
            TtsGender.MALE -> dialect.maleVoiceName
            TtsGender.FEMALE -> dialect.femaleVoiceName
        }
        val localeTag = dialect.localeTag

        val combinedRate = clamp(speechRate * style.rateFactor, 0.52f, 1.75f)
        val ratePercent = ((combinedRate - 1.0f) * 100f).roundToInt()
        val rateStr = if (ratePercent >= 0) "+$ratePercent%" else "$ratePercent%"

        val pitchFromSliderHz = ((pitch - 1.0f) * 38f).roundToInt()
        val totalPitchHz = (pitchFromSliderHz + style.pitchOffsetHz).coerceIn(-60, 60)
        val pitchStr = if (totalPitchHz >= 0) "+${totalPitchHz}Hz" else "${totalPitchHz}Hz"

        val volPercent = style.volumeDeltaPercent.coerceIn(-20, 20)
        val volumeStr = if (volPercent >= 0) "+$volPercent%" else "$volPercent%"

        val emotionalBody = formatEmotionalSsmlBody(cleanText, style)

        // Try expressive <mstts:express-as> first when the emotion has a style tag;
        // if a specific regional voice does not support express-as metadata, immediately
        // synthesize via calibrated SSML prosody + natural breath pauses on the same Edge-TTS WS.
        var mp3Bytes: ByteArray? = null
        val expressStyle = style.ssmlExpressAsStyle
        if (!expressStyle.isNullOrBlank()) {
            val expressiveSsml = buildExpressiveSsml(
                localeTag = localeTag,
                voiceName = voiceName,
                expressStyle = expressStyle,
                styleDegree = style.ssmlStyleDegree,
                pitchStr = pitchStr,
                rateStr = rateStr,
                volumeStr = volumeStr,
                body = emotionalBody,
            )
            mp3Bytes = EdgeTtsClient.synthesizeRawSsmlMp3(expressiveSsml)
        }

        if (mp3Bytes == null) {
            val prosodySsml = buildUniversalProsodySsml(
                localeTag = localeTag,
                voiceName = voiceName,
                pitchStr = pitchStr,
                rateStr = rateStr,
                volumeStr = volumeStr,
                body = emotionalBody,
            )
            mp3Bytes = EdgeTtsClient.synthesizeRawSsmlMp3(prosodySsml)
        }

        if (mp3Bytes == null || mp3Bytes.size < 256) {
            val cloudErr = when (language) {
                AppLanguage.EN -> "Cloud Neural TTS server could not be reached. Please check your internet connection."
                AppLanguage.FA -> "اتصال به سرور صدای عصبی ابری برقرار نشد. لطفاً اینترنت خود را بررسی کنید."
                AppLanguage.AR -> "تعذر الاتصال بخادم الصوت البشري السحابي (Neural TTS). تحقق من اتصالك بالإنترنت وحاول مجدداً."
            }
            return@withContext FreeEmotionalTtsOutcome.Error(cloudErr)
        }

        val cacheDir = File(context.cacheDir, "free_emotional_tts").apply { mkdirs() }
        val mp3File = File(cacheDir, "neural_${UUID.randomUUID()}.mp3")
        mp3File.writeBytes(mp3Bytes)

        val decoded = AudioDecoder.decodeFile(mp3File)
        if (decoded == null || decoded.frameCount <= 0) {
            mp3File.delete()
            return@withContext FreeEmotionalTtsOutcome.Error("تعذر فك ترميز الصوت البشري الناتج")
        }

        val extraPresence = when (style) {
            TtsDeliveryStyle.ANGRY -> 1.8f
            TtsDeliveryStyle.RADIO_ANCHOR -> 1.6f
            TtsDeliveryStyle.DRAMATIC -> 1.2f
            TtsDeliveryStyle.ENERGETIC -> 1.3f
            TtsDeliveryStyle.SAD -> 0.4f
            else -> 0.8f
        }
        val mastered = AudioSkillsPresets.applyStudioVocalMastering(
            buffer = decoded,
            extraWarmthDb = (style.warmthDb - 1.8f).coerceAtLeast(0f),
            extraPresenceDb = extraPresence,
        )

        FreeEmotionalTtsOutcome.Success(
            EdgeTtsSynthResult(
                buffer = mastered,
                mp3File = mp3File,
            ),
        )
    }

    private fun buildExpressiveSsml(
        localeTag: String,
        voiceName: String,
        expressStyle: String,
        styleDegree: Float,
        pitchStr: String,
        rateStr: String,
        volumeStr: String,
        body: String,
    ): String {
        val deg = "%.2f".format(java.util.Locale.US, styleDegree.coerceIn(0.5f, 2.0f))
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
            "xmlns:mstts='https://www.w3.org/2001/mstts' xml:lang='$localeTag'>" +
            "<voice name='$voiceName'>" +
            "<mstts:express-as style='$expressStyle' styledegree='$deg'>" +
            "<prosody pitch='$pitchStr' rate='$rateStr' volume='$volumeStr'>$body</prosody>" +
            "</mstts:express-as>" +
            "</voice></speak>"
    }

    private fun buildUniversalProsodySsml(
        localeTag: String,
        voiceName: String,
        pitchStr: String,
        rateStr: String,
        volumeStr: String,
        body: String,
    ): String {
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$localeTag'>" +
            "<voice name='$voiceName'>" +
            "<prosody pitch='$pitchStr' rate='$rateStr' volume='$volumeStr'>$body</prosody>" +
            "</voice></speak>"
    }

    /**
     * Inserts natural human breathing pauses around punctuation, ellipses, and line breaks
     * calibrated to the selected emotional delivery style.
     */
    private fun formatEmotionalSsmlBody(rawText: String, style: TtsDeliveryStyle): String {
        val escaped = escapeXml(rawText)
        val commaPauseMs = (style.breathPauseMs * 0.65f).roundToInt().coerceIn(80, 260)
        val sentencePauseMs = style.breathPauseMs.coerceIn(130, 440)
        val paragraphPauseMs = (style.breathPauseMs * 1.45f).roundToInt().coerceIn(220, 620)

        return escaped
            .replace(Regex("\\n+"), " <break time='${paragraphPauseMs}ms'/> ")
            .replace(Regex("(\\.\\.\\.|…)\\s*"), "<break time='${paragraphPauseMs}ms'/> ")
            .replace(Regex("([،,])\\s*"), "$1 <break time='${commaPauseMs}ms'/> ")
            .replace(Regex("([.!؟?؛;])\\s+"), "$1 <break time='${sentencePauseMs}ms'/> ")
            .let { formatted ->
                if (style == TtsDeliveryStyle.DRAMATIC || style == TtsDeliveryStyle.SAD) {
                    "<break time='120ms'/> $formatted"
                } else {
                    formatted
                }
            }
    }

    private fun escapeXml(input: String): String = input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
