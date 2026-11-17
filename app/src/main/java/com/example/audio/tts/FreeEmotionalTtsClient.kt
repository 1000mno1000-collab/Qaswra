package com.example.audio.tts

import android.content.Context
import android.util.Log
import com.example.audio.AudioDecoder
import com.example.audio.EdgeTtsSynthResult
import com.example.audio.OnlineGuard
import com.example.audio.StereoBiquadFilter
import com.example.audio.TARGET_SAMPLE_RATE
import com.example.audio.applyPitchAndSpeedOffline
import com.example.audio.cache.SmartCacheManager
import com.example.audio.clamp
import com.example.audio.fx.StudioLuxuryAudioFilter
import com.example.audio.network.NetworkFetchOutcome
import com.example.audio.network.NetworkImmunityManager
import com.example.audio.normalizeAudio
import com.example.audio.resampleStereo
import com.example.audio.writeStereoWavFile
import com.example.diagnostics.AppDiagnosticLog
import com.example.diagnostics.FfmpegKitBridge
import com.example.model.AppLanguage
import com.example.model.StereoAudioBuffer
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsGender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

sealed interface FreeEmotionalTtsOutcome {
    data class Success(val result: EdgeTtsSynthResult) : FreeEmotionalTtsOutcome
    data class Offline(val message: String) : FreeEmotionalTtsOutcome
    data class Error(val message: String) : FreeEmotionalTtsOutcome
}

private data class PollinationsSynthResult(
    val buffer: StereoAudioBuffer?,
    val errors: List<String>,
)

/**
 * 100% Online, free, keyless Cloud Human Voice AI client powered exclusively by
 * Pollinations OpenAI Audio (`https://text.pollinations.ai/...?model=openai-audio&voice={onyx|nova|shimmer|alloy|echo}`),
 * gated strictly by OnlineGuard.
 */
object FreeEmotionalTtsClient {
    private const val TAG = "FreeEmotionalTts"
    private const val PREFS_NAME = "sonix_free_tts_prefs"
    private const val KEY_LUXURY_ENHANCEMENT = "studio_luxury_enhancement"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun getSavedStudioLuxuryEnhancement(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LUXURY_ENHANCEMENT, true)
    }

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
    ): FreeEmotionalTtsOutcome = withContext(Dispatchers.IO) {
        val cleanText = ArabicTextDiacritizer.normalizeAndHintForTts(text.trim())
        if (cleanText.isEmpty()) {
            return@withContext FreeEmotionalTtsOutcome.Error(
                when (language) {
                    AppLanguage.EN -> "Please enter text first."
                    AppLanguage.FA -> "لطفاً ابتدا متن را وارد کنید."
                    AppLanguage.AR -> "يرجى إدخال النص أولاً."
                },
            )
        }

        if (!NetworkImmunityManager.pingReachable(context)) {
            return@withContext FreeEmotionalTtsOutcome.Offline(OnlineGuard.offlineErrorMessage(language))
        }

        val cacheDir = File(context.cacheDir, "free_emotional_tts").apply { mkdirs() }
        val primaryVoice = resolvePollinationsVoice(gender, style)
        val fallbackVoice = when (gender) {
            TtsGender.MALE -> if (primaryVoice == "onyx") "alloy" else "onyx"
            TtsGender.FEMALE -> if (primaryVoice == "nova") "shimmer" else "nova"
        }

        val collectedErrors = mutableListOf<String>()
        var result = synthesizeViaPollinationsAudio(
            context = context,
            cacheDir = cacheDir,
            text = cleanText,
            dialect = dialect,
            voice = primaryVoice,
            style = style,
        )
        collectedErrors += result.errors

        if (result.buffer == null || result.buffer.frameCount <= 0) {
            result = synthesizeViaPollinationsAudio(
                context = context,
                cacheDir = cacheDir,
                text = cleanText,
                dialect = dialect,
                voice = fallbackVoice,
                style = style,
            )
            collectedErrors += result.errors
        }

        val stitchedBuffer = result.buffer
        if (stitchedBuffer == null || stitchedBuffer.frameCount <= 0) {
            val detail = collectedErrors
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(3)
                .joinToString(" | ")
                .ifBlank { "Empty or invalid Pollinations audio payload" }
            AppDiagnosticLog.logRecoverable(
                toolName = "Pollinations OpenAI Audio TTS",
                titleAr = "تعذر توليد الصوت البشري السحابي",
                titleEn = "Pollinations OpenAI Audio TTS Failed",
                causeAr = detail,
                causeEn = detail,
                errorCode = "POLLINATIONS_TTS_ERR",
            )
            val userMsg = when (language) {
                AppLanguage.EN -> "Cloud AI Voice connection failed: $detail"
                AppLanguage.FA -> "خطا در اتصال به صدای انسانی ابری: $detail"
                AppLanguage.AR -> "تعذر الاتصال بخادم الصوت البشري السحابي: $detail"
            }
            return@withContext FreeEmotionalTtsOutcome.Error(userMsg)
        }

        val shapedBuffer = applyEmotionAndLuxuryShaping(
            context = context,
            cacheDir = cacheDir,
            inputBuffer = stitchedBuffer,
            gender = gender,
            style = style,
            speechRate = speechRate,
            pitch = pitch,
            studioLuxuryEnhancement = studioLuxuryEnhancement,
        )

        val previewWav = File(cacheDir, "cloud_tts_out_${UUID.randomUUID()}.wav")
        val wroteWav = runCatching {
            writeStereoWavFile(previewWav, shapedBuffer)
            previewWav.exists() && previewWav.length() > 44L
        }.getOrDefault(false)

        if (!wroteWav) {
            previewWav.delete()
            return@withContext FreeEmotionalTtsOutcome.Error(
                when (language) {
                    AppLanguage.EN -> "Failed to write preview WAV file."
                    AppLanguage.FA -> "ساخت فایل پیش‌نمایش صوتی ناموفق بود."
                    AppLanguage.AR -> "فشل تجهيز ملف المعاينة الصوتي."
                },
            )
        }
        SmartCacheManager.pruneManagedBuckets(context)

        FreeEmotionalTtsOutcome.Success(
            EdgeTtsSynthResult(
                buffer = shapedBuffer,
                mp3File = previewWav,
            ),
        )
    }

    private fun resolvePollinationsVoice(gender: TtsGender, style: TtsDeliveryStyle): String {
        return when (gender) {
            TtsGender.MALE -> when (style) {
                TtsDeliveryStyle.ENERGETIC -> "echo"
                TtsDeliveryStyle.RADIO_ANCHOR, TtsDeliveryStyle.STORYTELLING -> "alloy"
                else -> "onyx"
            }
            TtsGender.FEMALE -> when (style) {
                TtsDeliveryStyle.DRAMATIC, TtsDeliveryStyle.SAD -> "shimmer"
                else -> "nova"
            }
        }
    }

    private suspend fun synthesizeViaPollinationsAudio(
        context: Context,
        cacheDir: File,
        text: String,
        dialect: TtsDialect,
        voice: String,
        style: TtsDeliveryStyle,
    ): PollinationsSynthResult {
        val errors = mutableListOf<String>()
        val chunks = splitSentencesSmoothly(text, maxChars = 360)
        if (chunks.isEmpty()) {
            errors += "Pollinations: No speakable characters"
            return PollinationsSynthResult(null, errors)
        }

        val dialectCue = buildDialectActingCue(dialect)
        val emotionCue = buildEmotionActingCue(style)
        val pauseMs = (style.breathPauseMs * 0.55f).roundToInt().coerceIn(80, 250)
        val decodedChunks = ArrayList<StereoAudioBuffer>(chunks.size)

        for ((index, chunk) in chunks.withIndex()) {
            coroutineContext.ensureActive()
            val prompt = buildString {
                append("Act as a professional human voice actor. ")
                append(dialectCue)
                append(' ')
                append(emotionCue)
                append(" Read ONLY the following script aloud naturally with authentic pronunciation, human breathing pauses, and zero commentary or translation: ")
                append(chunk)
            }
            val encodedPrompt = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
            val primaryUrl = "https://text.pollinations.ai/$encodedPrompt?model=openai-audio&voice=$voice"
            val mirrorUrl = "https://text.pollinations.ai/$encodedPrompt?model=openai-audio&voice=alloy"

            val outcome = NetworkImmunityManager.fetchAudioWithFallback(
                urls = listOf(primaryUrl, mirrorUrl),
                context = context,
                headers = mapOf("Referer" to "https://pollinations.ai/"),
                timeoutMs = 30_000L,
                providerLabel = "Pollinations($voice)",
            )
            val bytes = when (outcome) {
                is NetworkFetchOutcome.Success -> outcome.value.bytes
                is NetworkFetchOutcome.Offline -> {
                    errors += outcome.reason
                    return PollinationsSynthResult(null, errors)
                }
                is NetworkFetchOutcome.Failure -> {
                    errors += "${outcome.errorCode}: ${outcome.message}"
                    return PollinationsSynthResult(null, errors)
                }
            }

            val ext = if (bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) "wav" else "mp3"
            val chunkFile = File(cacheDir, "polli_chunk_${UUID.randomUUID()}.$ext")
            val decoded = try {
                chunkFile.writeBytes(bytes)
                AudioDecoder.decodeFile(chunkFile)
            } catch (e: Exception) {
                errors += "Pollinations decode ${e.javaClass.simpleName}: ${e.message ?: "decode failed"}"
                null
            } finally {
                chunkFile.delete()
            }

            if (decoded == null || decoded.frameCount <= 0) {
                errors += "Pollinations: Invalid audio stream on chunk ${index + 1}/${chunks.size}"
                return PollinationsSynthResult(null, errors)
            }

            val unified = if (decoded.sampleRate == TARGET_SAMPLE_RATE) {
                decoded
            } else {
                resampleStereo(decoded, TARGET_SAMPLE_RATE)
            }
            decodedChunks += trimSilenceEdgesAndFade(unified, keepPadMs = 28)
        }

        val stitched = stitchSentenceBuffersSmoothly(decodedChunks, pauseMs)
        return PollinationsSynthResult(stitched, errors)
    }

    private fun buildDialectActingCue(dialect: TtsDialect): String = when (dialect.id) {
        "iraqi" -> "Speak in authentic warm Iraqi Baghdad Arabic dialect."
        "egyptian" -> "Speak in natural fluent Egyptian Arabic dialect."
        "saudi" -> "Speak in clear authentic Saudi Najdi Arabic dialect."
        "emirati" -> "Speak in smooth authentic Gulf Emirati Arabic dialect."
        "syrian" -> "Speak in warm authentic Levantine Syrian Arabic dialect."
        "jordanian" -> "Speak in natural Jordanian Levantine Arabic dialect."
        "moroccan" -> "Speak in natural Moroccan Darija Arabic dialect."
        "algerian" -> "Speak in natural Algerian Arabic dialect."
        "tunisian" -> "Speak in natural Tunisian Arabic dialect."
        "standard" -> "Speak in eloquent Modern Standard Arabic (Fusha) with crisp articulation."
        "en_us" -> "Speak in natural American English."
        "persian" -> "Speak in fluent Iranian Persian (Farsi)."
        "turkish" -> "Speak in natural Istanbul Turkish."
        else -> "Speak naturally in ${dialect.localeTag} language/accent."
    }

    private fun buildEmotionActingCue(style: TtsDeliveryStyle): String = when (style) {
        TtsDeliveryStyle.NATURAL_WARM -> "Deliver with a warm, calm, conversational human tone."
        TtsDeliveryStyle.RADIO_ANCHOR -> "Deliver like a confident studio radio presenter with steady authority."
        TtsDeliveryStyle.STORYTELLING -> "Deliver with captivating cinematic storytelling pacing and natural pauses."
        TtsDeliveryStyle.ENERGETIC -> "Deliver with upbeat, joyful, energetic enthusiasm."
        TtsDeliveryStyle.DRAMATIC -> "Deliver with deep dramatic cinema-trailer gravity and emotional resonance."
        TtsDeliveryStyle.SAD -> "Deliver gently with heartfelt melancholic sadness and soft reflective breaths."
        TtsDeliveryStyle.ANGRY -> "Deliver with intense, sharp, determined emotional force."
    }

    private suspend fun applyEmotionAndLuxuryShaping(
        context: Context,
        cacheDir: File,
        inputBuffer: StereoAudioBuffer,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
        studioLuxuryEnhancement: Boolean,
    ): StereoAudioBuffer = withContext(Dispatchers.Default) {
        if (inputBuffer.frameCount <= 0) return@withContext inputBuffer

        val sr = inputBuffer.sampleRate.coerceAtLeast(8000)
        val sliderSemitones = (12.0 * ln(pitch.coerceIn(0.65f, 1.45f).toDouble()) / ln(2.0)).toFloat()
        val styleSemitones = style.pitchOffsetHz / 14.0f
        val totalSemitones = clamp(sliderSemitones + styleSemitones, -9.0f, 9.0f)
        val pitchRatio = 2.0.pow(totalSemitones / 12.0).toFloat()

        val targetTempo = clamp(speechRate * style.rateFactor, 0.52f, 1.75f)

        val warmthDb = clamp(
            style.warmthDb + (if (gender == TtsGender.MALE) 1.2f else 0.4f) + (if (studioLuxuryEnhancement) 0.8f else 0f),
            1.2f,
            5.4f,
        )
        val presenceDb = when (style) {
            TtsDeliveryStyle.DRAMATIC -> 2.4f
            TtsDeliveryStyle.RADIO_ANCHOR -> 2.8f
            TtsDeliveryStyle.ENERGETIC -> 2.3f
            TtsDeliveryStyle.ANGRY -> 2.6f
            TtsDeliveryStyle.SAD -> 1.2f
            TtsDeliveryStyle.STORYTELLING -> 1.9f
            TtsDeliveryStyle.NATURAL_WARM -> 2.0f
        }
        val airDb = when (style) {
            TtsDeliveryStyle.SAD -> 0.9f
            TtsDeliveryStyle.DRAMATIC -> 1.5f
            else -> 1.8f
        }

        val filterStages = mutableListOf<String>()
        val applyPitchInFfmpeg = abs(pitchRatio - 1.0f) >= 0.015f
        if (applyPitchInFfmpeg) {
            filterStages += "asetrate=${sr}*${fmt(pitchRatio)}"
            filterStages += "aresample=$sr"
        }
        val compensatedTempo = if (applyPitchInFfmpeg) targetTempo / pitchRatio else targetTempo
        appendFfmpegAtempoStages(filterStages, compensatedTempo)

        filterStages += "highpass=f=70"
        filterStages += "equalizer=f=155:width_type=h:width=110:g=${fmt(warmthDb)}"
        filterStages += "equalizer=f=320:width_type=h:width=185:g=1.4"
        filterStages += "equalizer=f=4800:width_type=h:width=1600:g=-1.6"
        filterStages += "equalizer=f=2800:width_type=h:width=1200:g=${fmt(presenceDb)}"
        filterStages += "equalizer=f=9500:width_type=h:width=2600:g=${fmt(airDb)}"

        if (studioLuxuryEnhancement) {
            filterStages += "acompressor=threshold=-18dB:ratio=3.5:attack=8:release=75:makeup=3.0"
            filterStages += "alimiter=limit=0.95"
        }

        val ffmpegResult = tryExecuteFfmpegKit(
            cacheDir = cacheDir,
            inputBuffer = inputBuffer,
            sampleRate = sr,
            filterGraph = filterStages.joinToString(","),
        )
        if (ffmpegResult != null && ffmpegResult.frameCount > 0) {
            return@withContext ffmpegResult
        }

        val pitchedAndTimed = if (abs(totalSemitones) >= 0.05f || abs(targetTempo - 1.0f) >= 0.02f) {
            applyPitchAndSpeedOffline(
                buffer = inputBuffer,
                semitones = totalSemitones,
                tempoRatio = targetTempo,
            )
        } else {
            inputBuffer
        }

        val eqSmoothed = applyEmotionalToneEqualizer(
            buffer = pitchedAndTimed,
            warmthDb = warmthDb,
            presenceDb = presenceDb,
            airDb = airDb,
        )

        if (studioLuxuryEnhancement) {
            StudioLuxuryAudioFilter.applyLuxuryStudioEnhancement(
                context = context,
                inputAudioFile = null,
                decodedBuffer = eqSmoothed,
                style = style,
            )
        } else {
            normalizeAudio(eqSmoothed, -1.6f)
        }
    }

    private suspend fun tryExecuteFfmpegKit(
        cacheDir: File,
        inputBuffer: StereoAudioBuffer,
        sampleRate: Int,
        filterGraph: String,
    ): StereoAudioBuffer? = withContext(Dispatchers.IO) {
        val rawWav = File(cacheDir, "ff_in_${UUID.randomUUID()}.wav")
        val outWav = File(cacheDir, "ff_out_${UUID.randomUUID()}.wav")
        try {
            if (!FfmpegKitBridge.isAvailable()) return@withContext null
            writeStereoWavFile(rawWav, inputBuffer)
            val cmd = "-y -i \"${rawWav.absolutePath}\" -af \"$filterGraph\" -ar $sampleRate -ac 2 \"${outWav.absolutePath}\""
            val ok = FfmpegKitBridge.execute(cmd, toolName = "PollinationsTTS", logNonZeroFailure = true)
            if (ok && outWav.exists() && outWav.length() > 44L) {
                AudioDecoder.decodeFile(outWav)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            rawWav.delete()
            outWav.delete()
        }
    }

    private fun applyEmotionalToneEqualizer(
        buffer: StereoAudioBuffer,
        warmthDb: Float,
        presenceDb: Float,
        airDb: Float,
    ): StereoAudioBuffer {
        val n = buffer.frameCount
        if (n <= 0) return buffer
        val sr = buffer.sampleRate.coerceAtLeast(8000)
        val outL = buffer.left.copyOf()
        val outR = buffer.right.copyOf()

        val filters = listOf(
            StereoBiquadFilter().apply { configureHighPass(sr, 70.0, 0.707) },
            StereoBiquadFilter().apply { configurePeaking(sr, 155.0, 1.4, warmthDb.toDouble()) },
            StereoBiquadFilter().apply { configurePeaking(sr, 320.0, 1.7, 1.4) },
            StereoBiquadFilter().apply { configurePeaking(sr, 4800.0, 2.5, -1.6) },
            StereoBiquadFilter().apply { configurePeaking(sr, 2800.0, 2.0, presenceDb.toDouble()) },
            StereoBiquadFilter().apply { configurePeaking(sr, 9500.0, 2.2, airDb.toDouble()) },
        )

        val blockSize = 2048
        val bL = FloatArray(blockSize)
        val bR = FloatArray(blockSize)
        var off = 0
        while (off < n) {
            val count = min(blockSize, n - off)
            System.arraycopy(outL, off, bL, 0, count)
            System.arraycopy(outR, off, bR, 0, count)
            for (f in filters) {
                f.processStereo(bL, bR, count)
            }
            System.arraycopy(bL, 0, outL, off, count)
            System.arraycopy(bR, 0, outR, off, count)
            off += blockSize
        }

        return StereoAudioBuffer(outL, outR, sr)
    }

    private fun appendFfmpegAtempoStages(stages: MutableList<String>, rawTempo: Float) {
        var remaining = rawTempo.coerceIn(0.35f, 3.0f)
        while (remaining < 0.5f) {
            stages += "atempo=0.5"
            remaining /= 0.5f
        }
        while (remaining > 2.0f) {
            stages += "atempo=2.0"
            remaining /= 2.0f
        }
        if (abs(remaining - 1.0f) >= 0.01f) {
            stages += "atempo=${fmt(remaining)}"
        }
    }

    private fun trimSilenceEdgesAndFade(
        buffer: StereoAudioBuffer,
        keepPadMs: Int,
    ): StereoAudioBuffer {
        val n = buffer.frameCount
        if (n <= 64) return buffer
        val sr = buffer.sampleRate.coerceAtLeast(8000)
        val thresh = 0.008f

        var firstActive = 0
        while (firstActive < n && max(abs(buffer.left[firstActive]), abs(buffer.right[firstActive])) < thresh) {
            firstActive++
        }
        var lastActive = n - 1
        while (lastActive > firstActive && max(abs(buffer.left[lastActive]), abs(buffer.right[lastActive])) < thresh) {
            lastActive--
        }

        if (firstActive >= lastActive) return buffer

        val padFrames = ((keepPadMs / 1000.0) * sr).roundToInt()
        val start = max(0, firstActive - padFrames)
        val end = min(n, lastActive + 1 + padFrames)
        val outLen = end - start
        val outL = buffer.left.copyOfRange(start, end)
        val outR = buffer.right.copyOfRange(start, end)

        val fadeFrames = min(outLen / 6, ((10.0 / 1000.0) * sr).roundToInt())
        for (i in 0 until fadeFrames) {
            val g = i.toFloat() / max(1, fadeFrames)
            outL[i] *= g
            outR[i] *= g
            val tail = outLen - 1 - i
            outL[tail] *= g
            outR[tail] *= g
        }
        return StereoAudioBuffer(outL, outR, sr)
    }

    private fun stitchSentenceBuffersSmoothly(
        chunks: List<StereoAudioBuffer>,
        pauseMs: Int,
    ): StereoAudioBuffer? {
        if (chunks.isEmpty()) return null
        if (chunks.size == 1) return chunks.first()

        val sr = chunks.first().sampleRate
        val pauseFrames = ((pauseMs / 1000.0) * sr).roundToInt().coerceAtLeast(0)
        val totalFrames = chunks.sumOf { it.frameCount } + pauseFrames * (chunks.size - 1)
        val outL = FloatArray(totalFrames)
        val outR = FloatArray(totalFrames)

        var cursor = 0
        for ((idx, chunk) in chunks.withIndex()) {
            System.arraycopy(chunk.left, 0, outL, cursor, chunk.frameCount)
            System.arraycopy(chunk.right, 0, outR, cursor, chunk.frameCount)
            cursor += chunk.frameCount
            if (idx < chunks.lastIndex) {
                cursor += pauseFrames
            }
        }
        return StereoAudioBuffer(outL, outR, sr)
    }

    private fun splitSentencesSmoothly(rawText: String, maxChars: Int): List<String> {
        val normalized = rawText.replace(Regex("[\\t\\r ]+"), " ").trim()
        if (normalized.isEmpty()) return emptyList()

        val primaryClauses = normalized
            .split(Regex("(?<=[.!؟?\\n])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val clauses = mutableListOf<String>()
        for (clause in primaryClauses) {
            if (clause.length <= maxChars) {
                clauses += clause
            } else {
                val subClauses = clause.split(Regex("(?<=[،,؛;:])\\s+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                for (sub in subClauses) {
                    if (sub.length <= maxChars) {
                        clauses += sub
                    } else {
                        clauses += splitByWords(sub, maxChars)
                    }
                }
            }
        }

        val packed = mutableListOf<String>()
        val cur = StringBuilder()
        for (item in clauses) {
            if (cur.isEmpty()) {
                cur.append(item)
            } else if (cur.length + 1 + item.length <= maxChars) {
                cur.append(' ').append(item)
            } else {
                packed += cur.toString().trim()
                cur.clear()
                cur.append(item)
            }
        }
        if (cur.isNotEmpty()) packed += cur.toString().trim()

        return packed
            .filter { chunk -> chunk.any { it.isLetterOrDigit() } }
            .ifEmpty { listOf(normalized.take(maxChars)) }
    }

    private fun splitByWords(text: String, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        val words = text.split(Regex("\\s+"))
        val cur = StringBuilder()
        for (word in words) {
            if (word.length > maxChars) {
                if (cur.isNotEmpty()) {
                    out += cur.toString().trim()
                    cur.clear()
                }
                var idx = 0
                while (idx < word.length) {
                    val end = min(idx + maxChars, word.length)
                    out += word.substring(idx, end)
                    idx = end
                }
            } else if (cur.length + 1 + word.length > maxChars) {
                out += cur.toString().trim()
                cur.clear()
                cur.append(word)
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(word)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString().trim()
        return out
    }

    private fun isLikelyAudioPayload(bytes: ByteArray): Boolean {
        if (bytes.size < 256) return false
        val head = bytes.copyOfRange(0, min(32, bytes.size)).toString(Charsets.US_ASCII).trimStart()
        if (head.startsWith("<") || head.startsWith("{")) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val isId3 = head.startsWith("ID3")
        val isMp3FrameSync = b0 == 0xFF && (b1 and 0xE0) == 0xE0
        val isWav = head.startsWith("RIFF")
        val isOgg = head.startsWith("OggS")
        return isId3 || isMp3FrameSync || isWav || isOgg || bytes.size > 2048
    }

    private fun fmt(value: Float): String = "%.2f".format(Locale.US, value)
}
