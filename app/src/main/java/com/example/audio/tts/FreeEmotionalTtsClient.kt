package com.example.audio.tts

import android.content.Context
import com.example.audio.AudioDecoder
import com.example.audio.EdgeTtsClient
import com.example.audio.EdgeTtsSynthResult
import com.example.audio.OnlineGuard
import com.example.audio.StereoBiquadFilter
import com.example.audio.TARGET_SAMPLE_RATE
import com.example.audio.applyPitchAndSpeedOffline
import com.example.audio.buildPitchAndSpeedFfmpegChains
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
import com.example.model.TtsPersonaAcousticMode
import com.example.model.TtsVoicePersona
import com.example.util.SafeTextGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface FreeEmotionalTtsOutcome {
    data class Success(val result: EdgeTtsSynthResult) : FreeEmotionalTtsOutcome
    data class Offline(val message: String) : FreeEmotionalTtsOutcome
    data class Error(val message: String) : FreeEmotionalTtsOutcome
}

private data class CloudTtsSynthesisAttempt(
    val buffer: StereoAudioBuffer?,
    val prosodyAppliedInEngine: Boolean,
    val errors: List<String>,
)

/**
 * 100% Online, free, keyless Cloud Human Neural Voice client:
 * 1. Primary: Microsoft Edge Neural Voices (`speech.platform.bing.com` — e.g. `ar-IQ-BasselNeural`,
 *    `ar-SA-HamedNeural`, `ar-EG-ShakirNeural`, `en-US-AndrewNeural`, `fa-IR-FaridNeural`, `tr-TR-AhmetNeural`)
 *    with native Rate (%), Pitch (Hz), emotional cadence & full Arabic diacritics.
 * 2. Fallback: Google Translate Neural TTS HD (`translate.googleapis.com` / `translate.google.com`)
 *    plus gender/emotion shaping.
 * 3. Optional FFmpegKit human vocal warmth & Arabic articulation mastering (clarity for هـ / خ / ح).
 */
object FreeEmotionalTtsClient {
    private const val PREFS_NAME = "sonix_free_tts_prefs"
    private const val KEY_LUXURY_ENHANCEMENT = "studio_luxury_enhancement"

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
        persona: TtsVoicePersona? = null,
        acousticMode: TtsPersonaAcousticMode? = null,
    ): FreeEmotionalTtsOutcome = withContext(Dispatchers.IO) {
        val rawClean = ArabicTextDiacritizer.normalizeAndHintForTts(text.trim(), applyLightDiacritics = false)
        if (rawClean.isEmpty()) {
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
        val collectedErrors = mutableListOf<String>()

        val effectiveMode = acousticMode ?: persona?.defaultAcousticMode ?: when (style) {
            TtsDeliveryStyle.DRAMATIC, TtsDeliveryStyle.STORYTELLING -> TtsPersonaAcousticMode.DOCUMENTARY
            TtsDeliveryStyle.RADIO_ANCHOR -> TtsPersonaAcousticMode.PODCAST
            else -> TtsPersonaAcousticMode.NATURAL
        }

        // Background Arabic pronunciation shaping: Tashkeel, punctuation & documentary/podcast pauses
        val neuralText = GeminiNeuralTtsClient.diacritizeArabicIfAvailable(
            context = context,
            rawText = rawClean,
            dialect = dialect,
            style = style,
            acousticMode = effectiveMode,
        ) ?: ArabicTextDiacritizer.normalizeAndHintForTts(rawClean, applyLightDiacritics = true)

        // 1A. Primary: Hybrid Local ONNX Engine -> Gemini 2.5 Flash Native Human Audio TTS
        var attempt = run {
            val gem = GeminiNeuralTtsClient.synthesizeNaturalHumanSpeech(
                context = context,
                cacheDir = cacheDir,
                text = neuralText,
                dialect = dialect,
                gender = gender,
                style = style,
                speechRate = speechRate,
                language = language,
                persona = persona,
                acousticMode = effectiveMode,
            )
            if (gem.failureReason != null) collectedErrors += gem.failureReason
            CloudTtsSynthesisAttempt(
                buffer = gem.buffer,
                prosodyAppliedInEngine = true,
                errors = if (gem.failureReason != null) listOf(gem.failureReason) else emptyList(),
            )
        }

        // 1B. Primary/High-Availability Neural: Microsoft Azure/Edge 96kbps HD Neural Voices
        if (attempt.buffer == null || attempt.buffer.frameCount <= 0) {
            val primaryVoice = if (gender == TtsGender.MALE) dialect.maleVoiceName else dialect.femaleVoiceName
            attempt = synthesizeViaEdgeNeural(
                cacheDir = cacheDir,
                text = neuralText,
                voiceName = primaryVoice,
                localeTag = dialect.localeTag,
                style = style,
                speechRate = speechRate,
                pitch = pitch,
            )
            collectedErrors += attempt.errors
        }

        // 1C. Regional Neural Voice fallback (e.g., ar-SA neural) if specific regional Edge voice fails
        if (attempt.buffer == null || attempt.buffer.frameCount <= 0) {
            val primaryVoice = if (gender == TtsGender.MALE) dialect.maleVoiceName else dialect.femaleVoiceName
            val backupVoice = resolveBackupEdgeVoice(dialect, gender)
            if (backupVoice != null && backupVoice != primaryVoice) {
                attempt = synthesizeViaEdgeNeural(
                    cacheDir = cacheDir,
                    text = neuralText,
                    voiceName = backupVoice,
                    localeTag = dialect.fallbackLocaleTag,
                    style = style,
                    speechRate = speechRate,
                    pitch = pitch,
                )
                collectedErrors += attempt.errors
            }
        }

        // 2. Last-resort Online Fallback: Google Cloud Translate TTS HD (without harsh robotic pitch warping)
        if (attempt.buffer == null || attempt.buffer.frameCount <= 0) {
            attempt = synthesizeViaGoogleTranslateHd(
                context = context,
                cacheDir = cacheDir,
                text = neuralText,
                dialect = dialect,
                style = style,
                speechRate = speechRate,
            )
            collectedErrors += attempt.errors
        }

        val stitchedBuffer = attempt.buffer
        if (stitchedBuffer == null || stitchedBuffer.frameCount <= 0) {
            val detail = collectedErrors
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(3)
                .joinToString(" | ")
                .ifBlank { "Cloud neural TTS servers unreachable" }
            AppDiagnosticLog.logRecoverable(
                toolName = "Cloud Neural TTS",
                titleAr = "تعذر توليد الصوت البشري السحابي",
                titleEn = "Cloud Neural TTS Failed",
                causeAr = detail,
                causeEn = detail,
                errorCode = "CLOUD_NEURAL_TTS_ERR",
            )
            val userMsg = when (language) {
                AppLanguage.EN -> "Cloud AI Voice connection failed: $detail"
                AppLanguage.FA -> "خطا در اتصال به موتور صدای ابری: $detail"
                AppLanguage.AR -> "تعذر الاتصال بمحرك النطق السحابي: $detail"
            }
            return@withContext FreeEmotionalTtsOutcome.Error(userMsg)
        }

        val shapedBuffer = applyEmotionAndLuxuryShaping(
            context = context,
            cacheDir = cacheDir,
            inputBuffer = stitchedBuffer,
            gender = gender,
            style = style,
            acousticMode = effectiveMode,
            speechRate = speechRate,
            pitch = pitch,
            prosodyAlreadyAppliedInEngine = attempt.prosodyAppliedInEngine,
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

    private fun resolveBackupEdgeVoice(dialect: TtsDialect, gender: TtsGender): String? {
        val loc = dialect.localeTag.lowercase(Locale.US)
        return when {
            loc.startsWith("ar") -> if (gender == TtsGender.MALE) "ar-SA-HamedNeural" else "ar-SA-ZariyahNeural"
            loc.startsWith("en") -> if (gender == TtsGender.MALE) "en-US-GuyNeural" else "en-US-JennyNeural"
            loc.startsWith("fa") -> if (gender == TtsGender.MALE) "fa-IR-FaridNeural" else "fa-IR-DilaraNeural"
            loc.startsWith("tr") -> if (gender == TtsGender.MALE) "tr-TR-AhmetNeural" else "tr-TR-EmelNeural"
            else -> null
        }
    }

    private suspend fun synthesizeViaEdgeNeural(
        cacheDir: File,
        text: String,
        voiceName: String,
        localeTag: String,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
    ): CloudTtsSynthesisAttempt {
        val errors = mutableListOf<String>()
        // Large 1050-char paragraph slices keep human intonation continuous instead of choppy micro-clips
        val chunks = splitSentencesSmoothly(text, maxChars = 1050)
        if (chunks.isEmpty()) {
            return CloudTtsSynthesisAttempt(null, true, listOf("EdgeNeural: Empty chunks"))
        }

        val pauseMs = (style.breathPauseMs * 0.45f).roundToInt().coerceIn(70, 220)
        val decodedChunks = ArrayList<StereoAudioBuffer>(chunks.size)
        val combinedRate = clamp(speechRate * style.rateFactor, 0.65f, 1.55f)

        for ((index, chunk) in chunks.withIndex()) {
            coroutineContext.ensureActive()
            val edgeAttempt = EdgeTtsClient.synthesizeRawMp3WithReason(
                text = chunk,
                voiceName = voiceName,
                localeTag = localeTag,
                speechRate = combinedRate,
                pitch = pitch,
                pitchOffsetHz = style.pitchOffsetHz,
            )
            val bytes = edgeAttempt.bytes
            if (bytes == null || bytes.size < 512) {
                errors += edgeAttempt.failureReason ?: "EdgeNeural($voiceName) returned empty audio"
                return CloudTtsSynthesisAttempt(null, true, errors)
            }

            val chunkFile = File(cacheDir, "edge_chunk_${UUID.randomUUID()}.mp3")
            val decoded = try {
                BufferedOutputStream(FileOutputStream(chunkFile), 8192).use { out ->
                    out.write(bytes)
                    out.flush()
                }
                AudioDecoder.decodeFile(chunkFile)
            } catch (e: Exception) {
                errors += "Edge decode ${e.javaClass.simpleName}: ${e.message ?: "decode failed"}"
                null
            } finally {
                chunkFile.delete()
            }

            if (decoded == null || decoded.frameCount <= 0) {
                errors += "EdgeNeural($voiceName): Invalid MP3 on chunk ${index + 1}/${chunks.size}"
                return CloudTtsSynthesisAttempt(null, true, errors)
            }

            val unified = if (decoded.sampleRate == TARGET_SAMPLE_RATE) {
                decoded
            } else {
                resampleStereo(decoded, TARGET_SAMPLE_RATE)
            }
            decodedChunks += trimSilenceEdgesAndFade(unified, keepPadMs = 55)
        }

        val stitched = stitchSentenceBuffersSmoothly(decodedChunks, pauseMs)
        return CloudTtsSynthesisAttempt(stitched, prosodyAppliedInEngine = true, errors = errors)
    }

    private suspend fun synthesizeViaGoogleTranslateHd(
        context: Context,
        cacheDir: File,
        text: String,
        dialect: TtsDialect,
        style: TtsDeliveryStyle,
        speechRate: Float,
    ): CloudTtsSynthesisAttempt {
        val errors = mutableListOf<String>()
        val tl = resolveGoogleLangCode(dialect.localeTag)
        val chunks = splitSentencesSmoothly(text, maxChars = 180)
        if (chunks.isEmpty()) {
            return CloudTtsSynthesisAttempt(null, false, listOf("GoogleTTS: Empty chunks"))
        }

        val pauseMs = (style.breathPauseMs * 0.50f).roundToInt().coerceIn(75, 240)
        val speedParam = fmt(clamp(speechRate * style.rateFactor, 0.50f, 1.45f))
        val decodedChunks = ArrayList<StereoAudioBuffer>(chunks.size)

        for ((index, chunk) in chunks.withIndex()) {
            coroutineContext.ensureActive()
            val encoded = URLEncoder.encode(chunk, "UTF-8")
            val urls = listOf(
                "https://translate.googleapis.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$tl&ttsspeed=$speedParam&q=$encoded",
                "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$tl&ttsspeed=$speedParam&q=$encoded",
                "https://translate.googleapis.com/translate_tts?ie=UTF-8&client=gtx&tl=$tl&ttsspeed=$speedParam&q=$encoded",
            )

            val outcome = NetworkImmunityManager.fetchAudioWithFallback(
                urls = urls,
                context = context,
                headers = mapOf("Referer" to "https://translate.google.com/"),
                timeoutMs = 22_000L,
                providerLabel = "GoogleCloudTTS($tl)",
            )
            val bytes = when (outcome) {
                is NetworkFetchOutcome.Success -> outcome.value.bytes
                is NetworkFetchOutcome.Offline -> {
                    errors += outcome.reason
                    return CloudTtsSynthesisAttempt(null, false, errors)
                }
                is NetworkFetchOutcome.Failure -> {
                    errors += "${outcome.errorCode}: ${outcome.message}"
                    return CloudTtsSynthesisAttempt(null, false, errors)
                }
            }

            val chunkFile = File(cacheDir, "gtts_chunk_${UUID.randomUUID()}.mp3")
            val decoded = try {
                BufferedOutputStream(FileOutputStream(chunkFile), 8192).use { out ->
                    out.write(bytes)
                    out.flush()
                }
                AudioDecoder.decodeFile(chunkFile)
            } catch (e: Exception) {
                errors += "GoogleTTS decode ${e.javaClass.simpleName}: ${e.message ?: "decode failed"}"
                null
            } finally {
                chunkFile.delete()
            }

            if (decoded == null || decoded.frameCount <= 0) {
                errors += "GoogleTTS: Invalid stream on chunk ${index + 1}/${chunks.size}"
                return CloudTtsSynthesisAttempt(null, false, errors)
            }

            val unified = if (decoded.sampleRate == TARGET_SAMPLE_RATE) {
                decoded
            } else {
                resampleStereo(decoded, TARGET_SAMPLE_RATE)
            }
            decodedChunks += trimSilenceEdgesAndFade(unified, keepPadMs = 50)
        }

        val stitched = stitchSentenceBuffersSmoothly(decodedChunks, pauseMs)
        return CloudTtsSynthesisAttempt(stitched, prosodyAppliedInEngine = false, errors = errors)
    }

    private fun resolveGoogleLangCode(localeTag: String): String {
        val base = localeTag.substringBefore('-').lowercase(Locale.US)
        return when (base) {
            "ar", "en", "fa", "tr" -> base
            else -> "ar"
        }
    }

    private suspend fun applyEmotionAndLuxuryShaping(
        context: Context,
        cacheDir: File,
        inputBuffer: StereoAudioBuffer,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        acousticMode: TtsPersonaAcousticMode,
        speechRate: Float,
        pitch: Float,
        prosodyAlreadyAppliedInEngine: Boolean,
        studioLuxuryEnhancement: Boolean,
    ): StereoAudioBuffer = withContext(Dispatchers.Default) {
        if (inputBuffer.frameCount <= 0) return@withContext inputBuffer

        val sr = inputBuffer.sampleRate.coerceAtLeast(8000)

        // Preserve authentic Gemini / Edge Neural voice timbre with zero offline pitch/tempo warping
        val sliderSemitones = (12.0 * ln(pitch.coerceIn(0.65f, 1.45f).toDouble()) / ln(2.0)).toFloat()
        val totalSemitones = if (prosodyAlreadyAppliedInEngine) 0f else clamp(sliderSemitones, -4.5f, 4.5f)
        val targetTempo = 1.0f

        val needsPitchOrTempo = abs(totalSemitones) >= 0.08f || abs(targetTempo - 1.0f) >= 0.03f

        if (!needsPitchOrTempo) {
            return@withContext StudioLuxuryAudioFilter.applyAcousticMastering(
                context = context,
                decodedBuffer = inputBuffer,
                mode = acousticMode,
                style = style,
                gender = gender,
                luxuryBoost = studioLuxuryEnhancement,
            )
        }

        val (rubberbandChain, soxrFallbackChain) = buildPitchAndSpeedFfmpegChains(
            semitones = totalSemitones,
            tempoRatio = targetTempo,
        )
        val acousticCandidates = StudioLuxuryAudioFilter.buildAcousticMasteringFfmpegGraphCandidates(
            mode = acousticMode,
            style = style,
            gender = gender,
            luxuryBoost = studioLuxuryEnhancement,
        )
        val ffmpegCandidates: List<String> = buildList {
            for (pitchChain in listOf(rubberbandChain, soxrFallbackChain)) {
                for (acGraph in acousticCandidates) {
                    add("$pitchChain,$acGraph")
                }
            }
        }

        val ffmpegResult = tryExecuteFfmpegKit(
            cacheDir = cacheDir,
            inputBuffer = inputBuffer,
            sampleRate = sr,
            filterGraphs = ffmpegCandidates,
        )
        if (ffmpegResult != null && ffmpegResult.frameCount > 0) {
            return@withContext ffmpegResult
        }

        // Pure-Kotlin click-free fallback if FFmpegKit is unavailable
        val pitchedAndTimed = applyPitchAndSpeedOffline(
            buffer = inputBuffer,
            semitones = totalSemitones,
            tempoRatio = targetTempo,
        )
        StudioLuxuryAudioFilter.applyAcousticMastering(
            context = context,
            decodedBuffer = pitchedAndTimed,
            mode = acousticMode,
            style = style,
            gender = gender,
            luxuryBoost = studioLuxuryEnhancement,
        )
    }

    private suspend fun tryExecuteFfmpegKit(
        cacheDir: File,
        inputBuffer: StereoAudioBuffer,
        sampleRate: Int,
        filterGraphs: List<String>,
    ): StereoAudioBuffer? = withContext(Dispatchers.IO) {
        val rawWav = File(cacheDir, "ff_in_${UUID.randomUUID()}.wav")
        val outWav = File(cacheDir, "ff_out_${UUID.randomUUID()}.wav")
        try {
            if (!FfmpegKitBridge.isAvailable()) return@withContext null
            writeStereoWavFile(rawWav, inputBuffer)
            for (graph in filterGraphs) {
                if (outWav.exists()) outWav.delete()
                val cmd = "-y -i \"${rawWav.absolutePath}\" -af \"$graph\" -ar $sampleRate -ac 2 \"${outWav.absolutePath}\""
                val ok = FfmpegKitBridge.execute(cmd, toolName = "CloudNeuralTTS", logNonZeroFailure = false)
                if (ok && outWav.exists() && outWav.length() > 44L) {
                    val decoded = AudioDecoder.decodeFile(outWav)
                    if (decoded != null && decoded.frameCount > 0) {
                        return@withContext decoded
                    }
                }
            }
            null
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
            StereoBiquadFilter().apply { configureHighPass(sr, 65.0, 0.707) },
            StereoBiquadFilter().apply { configurePeaking(sr, 155.0, 1.4, warmthDb.toDouble()) },
            StereoBiquadFilter().apply { configurePeaking(sr, 315.0, 1.7, 1.4) },
            StereoBiquadFilter().apply { configurePeaking(sr, 2450.0, 2.0, presenceDb.toDouble()) },
            StereoBiquadFilter().apply { configurePeaking(sr, 4800.0, 2.4, -1.5) },
            StereoBiquadFilter().apply { configurePeaking(sr, 9200.0, 2.2, airDb.toDouble()) },
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

    private fun trimSilenceEdgesAndFade(
        buffer: StereoAudioBuffer,
        keepPadMs: Int,
    ): StereoAudioBuffer {
        val n = buffer.frameCount
        if (n <= 64) return buffer
        val sr = buffer.sampleRate.coerceAtLeast(8000)
        // Soft -58 dB threshold preserves natural Arabic breath consonants (هـ، ح، س، ش، ف)
        val thresh = 0.0012f

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

        val fadeFrames = min(outLen / 8, ((20.0 / 1000.0) * sr).roundToInt())
        for (i in 0 until fadeFrames) {
            val phase = (i.toDouble() / max(1, fadeFrames)) * PI
            val g = (0.5 * (1.0 - cos(phase))).toFloat()
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
            if (word.isEmpty()) continue
            if (word.length > maxChars) {
                if (cur.isNotEmpty()) {
                    out += cur.toString().trim()
                    cur.clear()
                }
                out += SafeTextGuard.safeChunkWord(word, maxChars)
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

    private fun fmt(value: Float): String = "%.2f".format(Locale.US, value)
}
