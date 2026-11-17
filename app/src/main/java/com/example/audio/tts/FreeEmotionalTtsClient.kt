package com.example.audio.tts

import android.content.Context
import android.util.Log
import com.example.audio.AudioDecoder
import com.example.audio.EdgeTtsSynthResult
import com.example.audio.OnlineGuard
import com.example.audio.StereoBiquadFilter
import com.example.audio.TARGET_SAMPLE_RATE
import com.example.audio.applyPitchAndSpeedOffline
import com.example.audio.clamp
import com.example.audio.fx.StudioLuxuryAudioFilter
import com.example.audio.normalizeAudio
import com.example.audio.resampleStereo
import com.example.audio.writeStereoWavFile
import com.example.model.AppLanguage
import com.example.model.StereoAudioBuffer
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsGender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

private enum class CloudTtsProvider {
    GOOGLE_OPEN_TTS,
    POLLINATIONS_AUDIO,
}

private data class CloudSynthResult(
    val buffer: StereoAudioBuffer?,
    val provider: CloudTtsProvider,
    val errors: List<String>,
)

/**
 * 100% Online, free, keyless Cloud Emotional TTS client.
 *
 * Replaces the deprecated Bing Edge-TTS WebSocket endpoint with stable open cloud gateways:
 * 1. Primary: Google Open Cloud TTS (`translate.google.com/translate_tts` & `translate.googleapis.com`)
 *    with intelligent sentence-boundary splitting, per-chunk decoding, silence trimming, and smooth PCM merging.
 * 2. Backup: Pollinations Audio Gateway (`text.pollinations.ai` `openai-audio`) with native male/female voices.
 *
 * Automatically shapes the resulting speech through FFmpegKit (`asetrate`, `atempo`, `equalizer`)
 * — with a seamless native Kotlin DSP mirror of the same filter graph when the FFmpegKit binary
 * is absent — to deliver rich, warm, expressive studio-grade voices without stutter or dropouts.
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
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return@withContext FreeEmotionalTtsOutcome.Error(
                when (language) {
                    AppLanguage.EN -> "Please enter text first."
                    AppLanguage.FA -> "لطفاً ابتدا متن را وارد کنید."
                    AppLanguage.AR -> "يرجى إدخال النص أولاً."
                },
            )
        }

        if (!OnlineGuard.isOnline(context)) {
            return@withContext FreeEmotionalTtsOutcome.Offline(OnlineGuard.offlineErrorMessage(language))
        }

        val cacheDir = File(context.cacheDir, "free_emotional_tts").apply { mkdirs() }
        val collectedErrors = mutableListOf<String>()

        // 1. Primary stable open cloud gateway: Google TTS with sentence chunking & smooth PCM stitching
        val googleResult = synthesizeViaGoogleOpenTts(
            cacheDir = cacheDir,
            text = cleanText,
            dialect = dialect,
            style = style,
        )
        collectedErrors += googleResult.errors

        val cloudAudio = if (googleResult.buffer != null && googleResult.buffer.frameCount > 0) {
            googleResult
        } else {
            // 2. Backup open cloud gateway: Pollinations Audio API (keyless)
            val pollinationsResult = synthesizeViaPollinationsAudio(
                cacheDir = cacheDir,
                text = cleanText,
                gender = gender,
                style = style,
            )
            collectedErrors += pollinationsResult.errors
            pollinationsResult
        }

        val stitchedBuffer = cloudAudio.buffer
        if (stitchedBuffer == null || stitchedBuffer.frameCount <= 0) {
            val detail = collectedErrors
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(3)
                .joinToString(" | ")
                .ifBlank { "Empty or invalid cloud audio payload" }
            val userMsg = when (language) {
                AppLanguage.EN -> "Cloud TTS connection failed: $detail"
                AppLanguage.FA -> "خطا در اتصال به سرور صوتی ابری: $detail"
                AppLanguage.AR -> "تعذر الاتصال بخادم الصوت السحابي: $detail"
            }
            return@withContext FreeEmotionalTtsOutcome.Error(userMsg)
        }

        // 3. Apply emotion + pitch + speed + studio tone shaping via FFmpegKit (atempo / asetrate / equalizer)
        val shapedBuffer = applyEmotionAndLuxuryShaping(
            context = context,
            cacheDir = cacheDir,
            inputBuffer = stitchedBuffer,
            sourceProvider = cloudAudio.provider,
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

        FreeEmotionalTtsOutcome.Success(
            EdgeTtsSynthResult(
                buffer = shapedBuffer,
                mp3File = previewWav,
            ),
        )
    }

    private suspend fun synthesizeViaGoogleOpenTts(
        cacheDir: File,
        text: String,
        dialect: TtsDialect,
        style: TtsDeliveryStyle,
    ): CloudSynthResult {
        val errors = mutableListOf<String>()
        val tl = resolveGoogleLangTag(dialect.localeTag)
        val chunks = splitSentencesSmoothly(text, maxChars = 170)
        if (chunks.isEmpty()) {
            errors += "GoogleTTS: No speakable characters found"
            return CloudSynthResult(null, CloudTtsProvider.GOOGLE_OPEN_TTS, errors)
        }

        val pauseMs = (style.breathPauseMs * 0.55f).roundToInt().coerceIn(75, 240)
        val decodedChunks = ArrayList<StereoAudioBuffer>(chunks.size)
        val total = chunks.size

        for ((index, chunk) in chunks.withIndex()) {
            coroutineContext.ensureActive()
            val encoded = URLEncoder.encode(chunk, "UTF-8")
            val primaryUrl =
                "https://translate.google.com/translate_tts?ie=UTF-8&tl=$tl&total=$total&idx=$index&textlen=${chunk.length}&client=tw-ob&q=$encoded"
            val secondaryUrl =
                "https://translate.googleapis.com/translate_tts?ie=UTF-8&tl=$tl&total=$total&idx=$index&textlen=${chunk.length}&client=gtx&q=$encoded"

            var bytes = fetchAudioChunk(
                apiUrl = primaryUrl,
                providerLabel = "GoogleTTS(tw-ob)",
                referer = "https://translate.google.com/",
                errors = errors,
            )
            if (bytes == null) {
                bytes = fetchAudioChunk(
                    apiUrl = secondaryUrl,
                    providerLabel = "GoogleTTS(gtx)",
                    referer = "https://translate.google.com/",
                    errors = errors,
                )
            }
            if (bytes == null) {
                return CloudSynthResult(null, CloudTtsProvider.GOOGLE_OPEN_TTS, errors)
            }

            val chunkFile = File(cacheDir, "gtts_chunk_${UUID.randomUUID()}.mp3")
            val decoded = try {
                chunkFile.writeBytes(bytes)
                AudioDecoder.decodeFile(chunkFile)
            } catch (e: Exception) {
                errors += "GoogleTTS decode ${e.javaClass.simpleName}: ${e.message ?: "decode failed"}"
                null
            } finally {
                chunkFile.delete()
            }

            if (decoded == null || decoded.frameCount <= 0) {
                errors += "GoogleTTS: AudioDecoder returned empty PCM for chunk ${index + 1}/$total"
                return CloudSynthResult(null, CloudTtsProvider.GOOGLE_OPEN_TTS, errors)
            }

            val unified = if (decoded.sampleRate == TARGET_SAMPLE_RATE) {
                decoded
            } else {
                resampleStereo(decoded, TARGET_SAMPLE_RATE)
            }
            decodedChunks += trimSilenceEdgesAndFade(unified, keepPadMs = 24)
        }

        val stitched = stitchSentenceBuffersSmoothly(decodedChunks, pauseMs)
        return CloudSynthResult(stitched, CloudTtsProvider.GOOGLE_OPEN_TTS, errors)
    }

    private suspend fun synthesizeViaPollinationsAudio(
        cacheDir: File,
        text: String,
        gender: TtsGender,
        style: TtsDeliveryStyle,
    ): CloudSynthResult {
        val errors = mutableListOf<String>()
        val voice = when (gender) {
            TtsGender.MALE -> if (style == TtsDeliveryStyle.ENERGETIC) "echo" else "onyx"
            TtsGender.FEMALE -> if (style == TtsDeliveryStyle.DRAMATIC || style == TtsDeliveryStyle.SAD) "shimmer" else "nova"
        }
        val chunks = splitSentencesSmoothly(text, maxChars = 420)
        if (chunks.isEmpty()) {
            errors += "Pollinations: No speakable characters"
            return CloudSynthResult(null, CloudTtsProvider.POLLINATIONS_AUDIO, errors)
        }

        val pauseMs = (style.breathPauseMs * 0.5f).roundToInt().coerceIn(70, 220)
        val decodedChunks = ArrayList<StereoAudioBuffer>(chunks.size)

        for ((index, chunk) in chunks.withIndex()) {
            coroutineContext.ensureActive()
            val prompt = "Read the exact text aloud verbatim without adding or translating any words: $chunk"
            val encodedPrompt = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
            val apiUrl = "https://text.pollinations.ai/$encodedPrompt?model=openai-audio&voice=$voice"

            val bytes = fetchAudioChunk(
                apiUrl = apiUrl,
                providerLabel = "Pollinations",
                referer = "https://pollinations.ai/",
                errors = errors,
                readTimeoutMs = 24_000,
            ) ?: return CloudSynthResult(null, CloudTtsProvider.POLLINATIONS_AUDIO, errors)

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
                return CloudSynthResult(null, CloudTtsProvider.POLLINATIONS_AUDIO, errors)
            }

            val unified = if (decoded.sampleRate == TARGET_SAMPLE_RATE) {
                decoded
            } else {
                resampleStereo(decoded, TARGET_SAMPLE_RATE)
            }
            decodedChunks += trimSilenceEdgesAndFade(unified, keepPadMs = 24)
        }

        val stitched = stitchSentenceBuffersSmoothly(decodedChunks, pauseMs)
        return CloudSynthResult(stitched, CloudTtsProvider.POLLINATIONS_AUDIO, errors)
    }

    private fun fetchAudioChunk(
        apiUrl: String,
        providerLabel: String,
        referer: String,
        errors: MutableList<String>,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 14_000,
    ): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "audio/mpeg, audio/wav, audio/*;q=0.9, */*;q=0.5")
                setRequestProperty("Referer", referer)
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val errSnippet = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                        ?.take(120)
                }.getOrNull().orEmpty()
                val reason = conn.responseMessage?.takeIf { it.isNotBlank() } ?: "HTTP Error"
                val detail = if (errSnippet.isNotEmpty() && !errSnippet.startsWith("<")) {
                    "$providerLabel responseCode=$code ($reason): $errSnippet"
                } else {
                    "$providerLabel responseCode=$code ($reason)"
                }
                Log.w(TAG, detail)
                errors += detail
                return null
            }

            val contentType = conn.contentType?.lowercase().orEmpty()
            val payload = conn.inputStream.use { it.readBytes() }
            if (contentType.contains("text/html") || contentType.contains("application/json") || !isLikelyAudioPayload(payload)) {
                val preview = payload.copyOfRange(0, min(100, payload.size))
                    .toString(Charsets.UTF_8)
                    .replace(Regex("\\s+"), " ")
                    .trim()
                val detail = "$providerLabel invalid payload (contentType=$contentType, size=${payload.size}): $preview"
                Log.w(TAG, detail)
                errors += detail
                return null
            }
            payload
        } catch (e: Exception) {
            val detail = "$providerLabel ${e.javaClass.simpleName}: ${e.message ?: "Network failure"}"
            Log.e(TAG, detail, e)
            errors += detail
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private suspend fun applyEmotionAndLuxuryShaping(
        context: Context,
        cacheDir: File,
        inputBuffer: StereoAudioBuffer,
        sourceProvider: CloudTtsProvider,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
        studioLuxuryEnhancement: Boolean,
    ): StereoAudioBuffer = withContext(Dispatchers.Default) {
        if (inputBuffer.frameCount <= 0) return@withContext inputBuffer

        val sr = inputBuffer.sampleRate.coerceAtLeast(8000)

        // Google open TTS returns a clean neutral female Arabic/global base voice;
        // compensate pitch down cleanly when Male is chosen to give deep masculine authority.
        val genderBaseSemitones = when {
            sourceProvider == CloudTtsProvider.GOOGLE_OPEN_TTS && gender == TtsGender.MALE -> -3.5f
            else -> 0.0f
        }
        val sliderSemitones = (12.0 * ln(pitch.coerceIn(0.65f, 1.45f).toDouble()) / ln(2.0)).toFloat()
        val styleSemitones = style.pitchOffsetHz / 12.0f
        val totalSemitones = clamp(genderBaseSemitones + sliderSemitones + styleSemitones, -9.0f, 9.0f)
        val pitchRatio = 2.0.pow(totalSemitones / 12.0).toFloat()

        val targetTempo = clamp(speechRate * style.rateFactor, 0.52f, 1.75f)

        val warmthDb = clamp(
            style.warmthDb + (if (gender == TtsGender.MALE) 1.4f else 0.4f) + (if (studioLuxuryEnhancement) 0.8f else 0f),
            1.2f,
            5.6f,
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

        // Build exact FFmpegKit filter chain: asetrate + aresample + atempo + highpass + equalizer (+ acompressor/alimiter)
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

        val ffmpegFilterGraph = filterStages.joinToString(",")
        val ffmpegResult = tryExecuteFfmpegKit(
            cacheDir = cacheDir,
            inputBuffer = inputBuffer,
            sampleRate = sr,
            filterGraph = ffmpegFilterGraph,
        )
        if (ffmpegResult != null && ffmpegResult.frameCount > 0) {
            return@withContext ffmpegResult
        }

        // Native Kotlin DSP mirror of the exact same asetrate/atempo/equalizer chain
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
            val ffmpegClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
            val returnCodeClass = Class.forName("com.arthenica.ffmpegkit.ReturnCode")
            val executeMethod = ffmpegClass.getMethod("execute", String::class.java)

            writeStereoWavFile(rawWav, inputBuffer)
            val cmd = "-y -i \"${rawWav.absolutePath}\" -af \"$filterGraph\" -ar $sampleRate -ac 2 \"${outWav.absolutePath}\""
            val session = executeMethod.invoke(null, cmd) ?: return@withContext null
            val getReturnCode = session.javaClass.getMethod("getReturnCode")
            val returnCode = getReturnCode.invoke(session) ?: return@withContext null
            val isSuccess = returnCodeClass.getMethod("isSuccess", returnCodeClass)
            val ok = (isSuccess.invoke(null, returnCode) as? Boolean) == true
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
            // Soften narrow sibilance / metallic TTS edge around 4.8kHz
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

    private fun resolveGoogleLangTag(localeTag: String): String {
        val base = localeTag.substringBefore('-').lowercase(Locale.ROOT).ifBlank { "ar" }
        return when (base) {
            "ar", "en", "fa", "tr", "fr", "es", "de" -> base
            else -> "ar"
        }
    }

    private fun splitSentencesSmoothly(rawText: String, maxChars: Int): List<String> {
        val normalized = rawText.replace(Regex("[\\t\\r ]+"), " ").trim()
        if (normalized.isEmpty()) return emptyList()

        // Split on major sentence delimiters first while preserving natural clause rhythm
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

        // Combine very short adjacent clauses when safe so playback flows smoothly with minimal requests
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
