package com.example.audio.tts

import android.content.Context
import com.example.audio.AudioDecoder
import com.example.audio.EdgeTtsClient
import com.example.audio.EdgeTtsSynthResult
import com.example.audio.NetworkUtils
import com.example.audio.applyPitchAndSpeedOffline
import com.example.audio.clamp
import com.example.audio.fx.AudioSkillsPresets
import com.example.audio.writeStereoWavFile
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsGender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.abs

class SmartVoiceRouter {

    /**
     * يوجّه النص تلقائياً لأقوى مزود صوتي طبيعي سحابي متاح بدون أي مفتاح API
     * ولا يعود أبداً إلى الصوت الآلي الروبوتي المحلي.
     */
    suspend fun generateNaturalVoice(
        text: String,
        voiceCode: String = "ar-IQ-BasselNeural", // يدعم: ar-IQ, ar-SA, ar-EG, ar-SY, en-US, fa-IR, tr-TR
        outputFile: File,
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return@withContext false

        outputFile.parentFile?.mkdirs()

        // 1. المحاولة الأولى: محرك النطق العصبي Microsoft Edge Neural
        val localeTag = voiceCode.split("-").take(2).joinToString("-").ifBlank { "ar-IQ" }
        val edgeBytes = EdgeTtsClient.synthesizeRawMp3(
            text = cleanText,
            voiceName = voiceCode,
            localeTag = localeTag,
        )
        if (edgeBytes != null && isLikelyAudioPayload(edgeBytes)) {
            val wrote = runCatching {
                outputFile.writeBytes(edgeBytes)
                outputFile.length() > 512L
            }.getOrDefault(false)
            if (wrote) return@withContext true
        }

        // 2. المحاولة الثانية: محرك StreamElements العصبي عالي الجودة
        val primarySuccess = tryStreamElementsNeural(cleanText, voiceCode, outputFile)
        if (primarySuccess && outputFile.length() > 512L) return@withContext true

        // 3. المحاولة الثالثة (البديل السحابي عالي الوضوح): محرك Google Translate HD TTS
        val lang = voiceCode.substringBefore("-").lowercase().ifBlank { "ar" }
        return@withContext tryGoogleFallbackTts(cleanText, lang, outputFile)
    }

    /**
     * يولّد الصوت البشري السحابي بأعلى جودة ممكنة مع تطبيق مهارة [AudioSkillsPresets.STUDIO_VOCAL_MASTERING]
     * لإخراج دفء استوديو احترافي. يعيد null عند عدم توفر أي مزود سحابي طبيعي (دون أي رجوع لصوت آلي).
     */
    suspend fun synthesizeStudioVoice(
        context: Context,
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
    ): EdgeTtsSynthResult? = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return@withContext null
        if (!NetworkUtils.isInternetAvailable(context)) return@withContext null

        // Primary neural WebSocket synthesis (supports dialects, rate, pitch & SSML)
        val edgeResult = EdgeTtsClient.synthesize(
            context = context,
            text = cleanText,
            dialect = dialect,
            gender = gender,
            style = style,
            speechRate = speechRate,
            pitch = pitch,
        )
        if (edgeResult != null) {
            val masteredWav = File(context.cacheDir, "edge_tts/tts_mastered_${UUID.randomUUID()}.wav").apply {
                parentFile?.mkdirs()
            }
            val wavSaved = runCatching {
                writeStereoWavFile(masteredWav, edgeResult.buffer)
                masteredWav.exists() && masteredWav.length() > 44L
            }.getOrDefault(false)
            return@withContext if (wavSaved) {
                edgeResult.mp3File.delete()
                EdgeTtsSynthResult(buffer = edgeResult.buffer, mp3File = masteredWav)
            } else {
                masteredWav.delete()
                edgeResult
            }
        }

        // Backup natural cloud providers (StreamElements Neural / Google HD TTS) -> no offline robotic TTS
        val voiceName = if (gender == TtsGender.MALE) dialect.maleVoiceName else dialect.femaleVoiceName
        val cacheDir = File(context.cacheDir, "edge_tts").apply { mkdirs() }
        val backupMp3 = File(cacheDir, "router_${UUID.randomUUID()}.mp3")
        val ok = generateNaturalVoice(
            text = cleanText,
            voiceCode = voiceName,
            outputFile = backupMp3,
        )
        if (!ok || !backupMp3.exists() || backupMp3.length() <= 512L) {
            backupMp3.delete()
            return@withContext null
        }

        val decoded = AudioDecoder.decodeFile(backupMp3)
        backupMp3.delete()
        if (decoded == null || decoded.frameCount <= 0) return@withContext null

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
            extraPresenceDb = if (style == TtsDeliveryStyle.RADIO_ANCHOR) 1.6f else 0.8f,
        )
        val previewWav = File(cacheDir, "router_mastered_${UUID.randomUUID()}.wav")
        val saved = runCatching {
            writeStereoWavFile(previewWav, mastered)
            previewWav.exists() && previewWav.length() > 44L
        }.getOrDefault(false)
        if (!saved) {
            previewWav.delete()
            return@withContext null
        }
        EdgeTtsSynthResult(buffer = mastered, mp3File = previewWav)
    }

    private fun tryStreamElementsNeural(text: String, voiceCode: String, out: File): Boolean {
        val seVoices = resolveStreamElementsVoices(voiceCode)
        if (seVoices.isEmpty()) return false

        val chunks = splitTextIntoChunks(text, maxChars = 420)
        for (seVoice in seVoices) {
            val combined = ByteArrayOutputStream()
            var allChunksOk = true
            for (chunk in chunks) {
                val encoded = URLEncoder.encode(chunk, "UTF-8")
                val apiUrl = "https://api.streamelements.com/kappa/v2/speech?voice=$seVoice&text=$encoded"
                val chunkBytes = fetchHttpAudioBytes(apiUrl, connectTimeoutMs = 7000, readTimeoutMs = 10000)
                if (chunkBytes == null || !isLikelyAudioPayload(chunkBytes)) {
                    allChunksOk = false
                    break
                }
                combined.write(chunkBytes)
            }
            val payload = combined.toByteArray()
            if (allChunksOk && payload.size > 512) {
                return runCatching {
                    out.writeBytes(payload)
                    true
                }.getOrDefault(false)
            }
        }
        return false
    }

    private fun tryGoogleFallbackTts(text: String, lang: String, out: File): Boolean {
        val chunks = splitTextIntoChunks(text, maxChars = 180)
        val combined = ByteArrayOutputStream()
        for (chunk in chunks) {
            val encoded = URLEncoder.encode(chunk, "UTF-8")
            val apiUrl = "https://translate.google.com/translate_tts?ie=UTF-8&tl=$lang&client=tw-ob&q=$encoded"
            val chunkBytes = fetchHttpAudioBytes(
                apiUrl = apiUrl,
                connectTimeoutMs = 7000,
                readTimeoutMs = 10000,
                referer = "https://translate.google.com/",
            ) ?: return false
            if (!isLikelyAudioPayload(chunkBytes)) return false
            combined.write(chunkBytes)
        }
        val payload = combined.toByteArray()
        if (payload.size <= 512) return false
        return runCatching {
            out.writeBytes(payload)
            true
        }.getOrDefault(false)
    }

    private fun fetchHttpAudioBytes(
        apiUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        referer: String? = null,
    ): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
                )
                setRequestProperty("Accept", "audio/mpeg, audio/*;q=0.9, */*;q=0.5")
                if (referer != null) setRequestProperty("Referer", referer)
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val contentType = conn.contentType?.lowercase().orEmpty()
            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                return null
            }
            conn.inputStream.use { it.readBytes() }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
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
            lower.startsWith("fa") -> emptyList() // Use Google HD fa-IR directly instead of mispronounced non-Persian voice
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
            if (word.length > maxChars) {
                if (current.isNotEmpty()) {
                    result.add(current.toString().trim())
                    current.clear()
                }
                var idx = 0
                while (idx < word.length) {
                    val end = minOf(idx + maxChars, word.length)
                    result.add(word.substring(idx, end))
                    idx = end
                }
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
