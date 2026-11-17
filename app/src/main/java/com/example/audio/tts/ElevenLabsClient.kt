package com.example.audio.tts

import android.content.Context
import com.example.audio.AudioDecoder
import com.example.audio.EdgeTtsSynthResult
import com.example.audio.OnlineGuard
import com.example.audio.applyPitchAndSpeedOffline
import com.example.audio.clamp
import com.example.audio.fx.AudioSkillsPresets
import com.example.audio.writeStereoWavFile
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsGender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.abs

sealed interface ElevenLabsOutcome {
    data class Success(val result: EdgeTtsSynthResult) : ElevenLabsOutcome
    data object MissingApiKey : ElevenLabsOutcome
    data object Offline : ElevenLabsOutcome
    data class ApiError(val code: Int, val detail: String) : ElevenLabsOutcome
}

object ElevenLabsClient {
    private const val PREFS_NAME = "sonix_elevenlabs_prefs"
    private const val KEY_API = "elevenlabs_api_key"
    private const val KEY_STABILITY = "elevenlabs_stability"
    private const val KEY_SIMILARITY = "elevenlabs_similarity"
    private const val MODEL_MULTILINGUAL_V2 = "eleven_multilingual_v2"

    private const val VOICE_ADAM = "pNInz6obpgDQGcFmaJgB"
    private const val VOICE_DANIEL = "onwK4e9ZLuTAKqWW03F9"
    private const val VOICE_RACHEL = "21m00Tcm4TlvDq8ikWAM"
    private const val VOICE_BELLA = "EXAVITQu4vr4xnSDxMaL"

    fun getSavedApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API, "").orEmpty().trim()
    }

    fun saveApiKey(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API, apiKey.trim())
            .apply()
    }

    fun getSavedStability(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_STABILITY, 0.45f)
            .coerceIn(0.05f, 0.98f)
    }

    fun saveStability(context: Context, stability: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_STABILITY, stability.coerceIn(0.05f, 0.98f))
            .apply()
    }

    fun getSavedSimilarity(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_SIMILARITY, 0.78f)
            .coerceIn(0.05f, 1.0f)
    }

    fun saveSimilarity(context: Context, similarity: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SIMILARITY, similarity.coerceIn(0.05f, 1.0f))
            .apply()
    }

    private fun selectVoiceId(
        @Suppress("UNUSED_PARAMETER") dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
    ): String {
        return when (gender) {
            TtsGender.MALE -> when (style) {
                TtsDeliveryStyle.DRAMATIC,
                TtsDeliveryStyle.RADIO_ANCHOR,
                TtsDeliveryStyle.STORYTELLING,
                -> VOICE_DANIEL
                else -> VOICE_ADAM
            }
            TtsGender.FEMALE -> when (style) {
                TtsDeliveryStyle.SAD,
                TtsDeliveryStyle.STORYTELLING,
                -> VOICE_BELLA
                else -> VOICE_RACHEL
            }
        }
    }

    suspend fun synthesize(
        context: Context,
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
        stability: Float = getSavedStability(context),
        similarityBoost: Float = getSavedSimilarity(context),
    ): ElevenLabsOutcome = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return@withContext ElevenLabsOutcome.ApiError(400, "Empty text")

        if (!OnlineGuard.isOnline(context)) return@withContext ElevenLabsOutcome.Offline
        val apiKey = getSavedApiKey(context)
        if (apiKey.isBlank()) return@withContext ElevenLabsOutcome.MissingApiKey

        val voiceId = selectVoiceId(dialect, gender, style)
        val endpoint = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128"

        val effStability = clamp(stability + style.elevenLabsStabilityDelta, 0.05f, 0.98f)
        val effSimilarity = clamp(similarityBoost, 0.05f, 1.0f)
        val effStyle = clamp(style.elevenLabsStyle, 0.0f, 1.0f)

        val payloadJson = JSONObject().apply {
            put("text", cleanText)
            put("model_id", MODEL_MULTILINGUAL_V2)
            put(
                "voice_settings",
                JSONObject().apply {
                    put("stability", effStability.toDouble())
                    put("similarity_boost", effSimilarity.toDouble())
                    put("style", effStyle.toDouble())
                    put("use_speaker_boost", true)
                },
            )
        }.toString()

        val cacheDir = File(context.cacheDir, "elevenlabs_tts").apply { mkdirs() }
        val rawMp3 = File(cacheDir, "el_${UUID.randomUUID()}.mp3")
        var conn: HttpURLConnection? = null

        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 25_000
                setRequestProperty("xi-api-key", apiKey)
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "audio/mpeg")
            }
            conn.outputStream.use { os ->
                os.write(payloadJson.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = runCatching {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
                val friendlyErr = when (code) {
                    401, 403 -> "مفتاح ElevenLabs غير صالح أو غير مفعّل (HTTP $code). تحقق من المفتاح الشخصي."
                    429 -> "تم تجاوز الحد المجاني المؤقت في ElevenLabs (HTTP 429). حاول لاحقاً أو استخدم مفتاحاً آخر."
                    else -> "تعذر التوليد من ElevenLabs (HTTP $code): ${errBody.take(120)}"
                }
                return@withContext ElevenLabsOutcome.ApiError(code, friendlyErr)
            }

            conn.inputStream.use { input ->
                rawMp3.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 16_384)
                }
            }
            if (!rawMp3.exists() || rawMp3.length() < 512L) {
                rawMp3.delete()
                return@withContext ElevenLabsOutcome.ApiError(500, "ملف الصوت العائد من ElevenLabs غير مكتمل.")
            }

            val decoded = AudioDecoder.decodeFile(rawMp3)
            rawMp3.delete()
            if (decoded == null || decoded.frameCount <= 0) {
                return@withContext ElevenLabsOutcome.ApiError(500, "تعذر فك ترميز الصوت المستلم من ElevenLabs.")
            }

            val targetTempo = clamp(speechRate * style.rateFactor, 0.65f, 1.6f)
            val semitones = clamp((pitch - 1.0f) * 6f, -5f, 5f)
            val prosodyAdjusted = if (abs(targetTempo - 1f) > 0.04f || abs(semitones) > 0.15f) {
                applyPitchAndSpeedOffline(decoded, semitones = semitones, tempoRatio = targetTempo)
            } else {
                decoded
            }

            val mastered = AudioSkillsPresets.applyStudioVocalMastering(
                buffer = prosodyAdjusted,
                extraWarmthDb = (style.warmthDb - 2.0f).coerceAtLeast(0f),
                extraPresenceDb = if (style == TtsDeliveryStyle.RADIO_ANCHOR) 1.5f else 0.7f,
            )

            val previewWav = File(cacheDir, "el_mastered_${UUID.randomUUID()}.wav")
            val wroteWav = runCatching {
                writeStereoWavFile(previewWav, mastered)
                previewWav.exists() && previewWav.length() > 44L
            }.getOrDefault(false)

            if (!wroteWav) {
                previewWav.delete()
                return@withContext ElevenLabsOutcome.ApiError(500, "فشل حفظ ملف المعاينة الصوتي.")
            }

            return@withContext ElevenLabsOutcome.Success(
                EdgeTtsSynthResult(buffer = mastered, mp3File = previewWav),
            )
        } catch (t: Throwable) {
            rawMp3.delete()
            return@withContext ElevenLabsOutcome.ApiError(
                0,
                t.localizedMessage ?: "تعذر الاتصال بخوادم ElevenLabs.",
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
