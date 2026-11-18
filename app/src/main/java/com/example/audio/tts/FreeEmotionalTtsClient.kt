package com.example.audio.tts

import android.content.Context
import com.example.audio.AudioDecoder
import com.example.audio.EdgeTtsClient
import com.example.audio.EdgeTtsSynthResult
import com.example.audio.OnlineGuard
import com.example.audio.TARGET_SAMPLE_RATE
import com.example.audio.cache.SmartCacheManager
import com.example.audio.clamp
import com.example.audio.fx.StudioLuxuryAudioFilter
import com.example.audio.network.NetworkImmunityManager
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
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface FreeEmotionalTtsOutcome {
    data class Success(val result: EdgeTtsSynthResult) : FreeEmotionalTtsOutcome
    data class Offline(val message: String) : FreeEmotionalTtsOutcome
    data class Error(val message: String) : FreeEmotionalTtsOutcome
}

private data class EdgeNeuralSynthesisAttempt(
    val mp3File: File?,
    val decodedBuffer: StereoAudioBuffer?,
    val errors: List<String>,
)

/**
 * 100% Online, free, keyless Microsoft Edge Neural TTS client (`wss://speech.platform.bing.com`).
 * - Streams SSML directly via WebSocket (`audio-24khz-48kbitrate-mono-mp3`) with full Arabic diacritics,
 *   rate (%), pitch (Hz), and multi-dialect human neural voices (`ar-IQ`, `ar-SA`, `ar-EG`, `ar-SY`, `ar-AE`, `ar-JO`).
 * - Saves ready-to-play MP3 and runs FFmpegKit acoustic mastering for Documentary, Podcast, and Studio Luxury presets.
 * - Zero Gemini API and zero Android legacy TTS dependency.
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

        // Local high-speed Arabic pronunciation shaping & Tashkeel hinting (Zero Gemini API)
        val neuralText = ArabicTextDiacritizer.normalizeAndHintForTts(rawClean, applyLightDiacritics = true)
        val primaryVoice = resolveEdgeVoiceName(dialect = dialect, gender = gender)
        val personaRateFactor = resolvePersonaRateFactor(persona)
        val personaPitchHz = resolvePersonaPitchOffsetHz(persona)

        // 1. Primary: Direct Microsoft Edge Neural TTS via WebSocket (audio-24khz-48kbitrate-mono-mp3)
        var attempt = synthesizeViaEdgeWebSocket(
            cacheDir = cacheDir,
            text = neuralText,
            voiceName = primaryVoice,
            style = style,
            speechRate = speechRate * personaRateFactor,
            pitch = pitch,
            extraPitchOffsetHz = personaPitchHz,
        )
        collectedErrors += attempt.errors

        // 2. Regional Neural Voice fallback if specific regional Edge voice fails
        if (attempt.mp3File == null && (attempt.decodedBuffer == null || attempt.decodedBuffer.frameCount <= 0)) {
            val backupVoice = resolveBackupEdgeVoice(dialect, gender)
            if (backupVoice != null && backupVoice != primaryVoice) {
                attempt = synthesizeViaEdgeWebSocket(
                    cacheDir = cacheDir,
                    text = neuralText,
                    voiceName = backupVoice,
                    style = style,
                    speechRate = speechRate * personaRateFactor,
                    pitch = pitch,
                    extraPitchOffsetHz = personaPitchHz,
                )
                collectedErrors += attempt.errors
            }
        }

        val rawMp3File = attempt.mp3File
        val rawDecoded = attempt.decodedBuffer
        if (rawMp3File == null && (rawDecoded == null || rawDecoded.frameCount <= 0)) {
            val detail = collectedErrors
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(3)
                .joinToString(" | ")
                .ifBlank { "Microsoft Edge Neural WebSocket unreachable" }
            AppDiagnosticLog.logRecoverable(
                toolName = "Edge Neural TTS",
                titleAr = "تعذر توليد الصوت البشري عبر Edge WebSocket",
                titleEn = "Edge Neural TTS Failed",
                causeAr = detail,
                causeEn = detail,
                errorCode = "EDGE_WS_TTS_ERR",
            )
            val userMsg = when (language) {
                AppLanguage.EN -> "Edge Neural Voice connection failed: $detail"
                AppLanguage.FA -> "خطا در اتصال به سرور Microsoft Edge TTS: $detail"
                AppLanguage.AR -> "تعذر الاتصال بمحرك Microsoft Edge Neural TTS: $detail"
            }
            return@withContext FreeEmotionalTtsOutcome.Error(userMsg)
        }

        // 3. Apply FFmpegKit Documentary / Podcast / Studio Luxury Mastering & save playable MP3
        val masteredOutcome = processWithFfmpegKitAndSavePlayable(
            context = context,
            cacheDir = cacheDir,
            rawMp3File = rawMp3File,
            fallbackDecodedBuffer = rawDecoded,
            gender = gender,
            style = style,
            acousticMode = effectiveMode,
            studioLuxuryEnhancement = studioLuxuryEnhancement,
        )

        if (masteredOutcome == null || masteredOutcome.buffer.frameCount <= 0) {
            runCatching { rawMp3File?.delete() }
            return@withContext FreeEmotionalTtsOutcome.Error(
                when (language) {
                    AppLanguage.EN -> "Failed to decode synthesized MP3 stream."
                    AppLanguage.FA -> "پردازش فایل MP3 ناموفق بود."
                    AppLanguage.AR -> "فشل تجهيز أو فك ترميز ملف MP3 الصوتي."
                },
            )
        }

        SmartCacheManager.pruneManagedBuckets(context)
        FreeEmotionalTtsOutcome.Success(masteredOutcome)
    }

    private fun resolveEdgeVoiceName(dialect: TtsDialect, gender: TtsGender): String {
        return when (dialect.id) {
            "iraqi" -> if (gender == TtsGender.MALE) "ar-IQ-BasselNeural" else "ar-IQ-RanaNeural"
            "saudi" -> if (gender == TtsGender.MALE) "ar-SA-HamedNeural" else "ar-SA-ZariyahNeural"
            "egyptian" -> if (gender == TtsGender.MALE) "ar-EG-ShakirNeural" else "ar-EG-SalmaNeural"
            "syrian" -> if (gender == TtsGender.MALE) "ar-SY-LaithNeural" else "ar-SY-AmanyNeural"
            "standard" -> if (gender == TtsGender.MALE) "ar-AE-HamdanNeural" else "ar-JO-SanaNeural"
            else -> if (gender == TtsGender.MALE) dialect.maleVoiceName else dialect.femaleVoiceName
        }
    }

    private fun resolvePersonaRateFactor(persona: TtsVoicePersona?): Float = when (persona?.id) {
        "male_doc_deep" -> 0.92f
        "male_podcast_warm" -> 0.98f
        "male_promo_dramatic" -> 1.02f
        "male_modern_energetic" -> 1.06f
        "female_doc_dignified" -> 0.93f
        "female_podcast_host" -> 0.98f
        "female_news_anchor" -> 1.00f
        "female_story_soft" -> 0.92f
        else -> 1.0f
    }

    private fun resolvePersonaPitchOffsetHz(persona: TtsVoicePersona?): Int = when (persona?.id) {
        "male_doc_deep" -> -10
        "male_podcast_warm" -> -4
        "male_promo_dramatic" -> -6
        "male_modern_energetic" -> 4
        "female_doc_dignified" -> -5
        "female_podcast_host" -> 0
        "female_news_anchor" -> 2
        "female_story_soft" -> 4
        else -> 0
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

    private suspend fun synthesizeViaEdgeWebSocket(
        cacheDir: File,
        text: String,
        voiceName: String,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
        extraPitchOffsetHz: Int,
    ): EdgeNeuralSynthesisAttempt {
        val errors = mutableListOf<String>()
        val chunks = splitSentencesSmoothly(text, maxChars = 1050)
        if (chunks.isEmpty()) {
            return EdgeNeuralSynthesisAttempt(null, null, listOf("EdgeNeural: Empty chunks"))
        }

        val combinedRate = clamp(speechRate * style.rateFactor, 0.55f, 1.75f)
        val localeTag = voiceName.split("-").take(2).joinToString("-").ifBlank { "ar-AE" }
        val rawMp3Chunks = ArrayList<ByteArray>(chunks.size)

        for (chunk in chunks) {
            coroutineContext.ensureActive()
            val edgeAttempt = EdgeTtsClient.synthesizeRawMp3WithReason(
                text = chunk,
                voiceName = voiceName,
                localeTag = localeTag,
                speechRate = combinedRate,
                pitch = pitch,
                pitchOffsetHz = style.pitchOffsetHz + extraPitchOffsetHz,
            )
            val bytes = edgeAttempt.bytes
            if (bytes == null || bytes.size < 512) {
                errors += edgeAttempt.failureReason ?: "EdgeNeural($voiceName) returned empty audio"
                return EdgeNeuralSynthesisAttempt(null, null, errors)
            }
            rawMp3Chunks += bytes
        }

        // Single chunk: save genuine audio-24khz-48kbitrate-mono-mp3 directly ready for playback & FFmpegKit
        if (rawMp3Chunks.size == 1) {
            val mp3File = File(cacheDir, "edge_raw_${UUID.randomUUID()}.mp3")
            val wrote = runCatching {
                BufferedOutputStream(FileOutputStream(mp3File), 16384).use { out ->
                    out.write(rawMp3Chunks[0])
                    out.flush()
                }
                mp3File.exists() && mp3File.length() > 512L
            }.getOrDefault(false)
            return if (wrote) {
                EdgeNeuralSynthesisAttempt(mp3File = mp3File, decodedBuffer = null, errors = errors)
            } else {
                mp3File.delete()
                EdgeNeuralSynthesisAttempt(null, null, errors + "Failed to save raw Edge MP3")
            }
        }

        // Multi-chunk: decode & stitch cleanly with smooth breath pauses
        val pauseMs = (style.breathPauseMs * 0.45f).roundToInt().coerceIn(70, 220)
        val decodedChunks = ArrayList<StereoAudioBuffer>(rawMp3Chunks.size)
        for ((index, bytes) in rawMp3Chunks.withIndex()) {
            val chunkFile = File(cacheDir, "edge_chunk_${UUID.randomUUID()}.mp3")
            val decoded = try {
                BufferedOutputStream(FileOutputStream(chunkFile), 16384).use { out ->
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
                errors += "EdgeNeural($voiceName): Invalid MP3 on chunk ${index + 1}/${rawMp3Chunks.size}"
                return EdgeNeuralSynthesisAttempt(null, null, errors)
            }

            val unified = if (decoded.sampleRate == TARGET_SAMPLE_RATE) {
                decoded
            } else {
                resampleStereo(decoded, TARGET_SAMPLE_RATE)
            }
            decodedChunks += trimSilenceEdgesAndFade(unified, keepPadMs = 55)
        }

        val stitched = stitchSentenceBuffersSmoothly(decodedChunks, pauseMs)
        return EdgeNeuralSynthesisAttempt(mp3File = null, decodedBuffer = stitched, errors = errors)
    }

    private suspend fun processWithFfmpegKitAndSavePlayable(
        context: Context,
        cacheDir: File,
        rawMp3File: File?,
        fallbackDecodedBuffer: StereoAudioBuffer?,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        acousticMode: TtsPersonaAcousticMode,
        studioLuxuryEnhancement: Boolean,
    ): EdgeTtsSynthResult? = withContext(Dispatchers.IO) {
        val candidates = StudioLuxuryAudioFilter.buildAcousticMasteringFfmpegGraphCandidates(
            mode = acousticMode,
            style = style,
            gender = gender,
            luxuryBoost = studioLuxuryEnhancement,
        )

        var sourceWavForFfmpeg: File? = null
        try {
            val ffmpegInputFile = when {
                rawMp3File != null && rawMp3File.exists() -> rawMp3File
                fallbackDecodedBuffer != null && fallbackDecodedBuffer.frameCount > 0 -> {
                    File(cacheDir, "edge_stitched_in_${UUID.randomUUID()}.wav").also { wav ->
                        writeStereoWavFile(wav, fallbackDecodedBuffer)
                        sourceWavForFfmpeg = wav
                    }
                }
                else -> return@withContext null
            }

            if (FfmpegKitBridge.isAvailable()) {
                // Step 1: Try FFmpegKit acoustic mastering saving directly as ready-to-play MP3
                val outMp3 = File(cacheDir, "tts_mastered_${UUID.randomUUID()}.mp3")
                for (graph in candidates) {
                    if (outMp3.exists()) outMp3.delete()
                    val cmd = "-y -i \"${ffmpegInputFile.absolutePath}\" -af \"$graph\" -vn -ar 44100 -ac 2 -c:a libmp3lame -b:a 128k \"${outMp3.absolutePath}\""
                    val ok = FfmpegKitBridge.execute(
                        command = cmd,
                        toolName = "EdgeTtsFfmpegMp3_${acousticMode.name}",
                        logNonZeroFailure = false,
                    )
                    if (ok && outMp3.exists() && outMp3.length() > 512L) {
                        val decodedMastered = AudioDecoder.decodeFile(outMp3)
                        if (decodedMastered != null && decodedMastered.frameCount > 0) {
                            runCatching { rawMp3File?.delete() }
                            return@withContext EdgeTtsSynthResult(
                                buffer = decodedMastered,
                                mp3File = outMp3,
                            )
                        }
                    }
                }
                runCatching { outMp3.delete() }

                // Step 2: If libmp3lame isn't bundled in device FFmpegKit build, apply exact FFmpegKit filters to WAV
                val outWav = File(cacheDir, "tts_mastered_${UUID.randomUUID()}.wav")
                for (graph in candidates) {
                    if (outWav.exists()) outWav.delete()
                    val cmd = "-y -i \"${ffmpegInputFile.absolutePath}\" -af \"$graph\" -ar 44100 -ac 2 \"${outWav.absolutePath}\""
                    val ok = FfmpegKitBridge.execute(
                        command = cmd,
                        toolName = "EdgeTtsFfmpegWav_${acousticMode.name}",
                        logNonZeroFailure = false,
                    )
                    if (ok && outWav.exists() && outWav.length() > 44L) {
                        val decodedMastered = AudioDecoder.decodeFile(outWav)
                        if (decodedMastered != null && decodedMastered.frameCount > 0) {
                            val playableFile = if (rawMp3File != null && !studioLuxuryEnhancement && acousticMode == TtsPersonaAcousticMode.NATURAL) {
                                runCatching { outWav.delete() }
                                rawMp3File
                            } else {
                                runCatching { rawMp3File?.delete() }
                                outWav
                            }
                            return@withContext EdgeTtsSynthResult(
                                buffer = decodedMastered,
                                mp3File = playableFile,
                            )
                        }
                    }
                }
                runCatching { outWav.delete() }
            }

            // Step 3: Pure-Kotlin acoustic mastering fallback when FFmpegKit is absent
            val decoded = fallbackDecodedBuffer ?: (rawMp3File?.let { AudioDecoder.decodeFile(it) }) ?: return@withContext null
            val mastered = StudioLuxuryAudioFilter.applyAcousticMastering(
                context = context,
                decodedBuffer = decoded,
                mode = acousticMode,
                style = style,
                gender = gender,
                luxuryBoost = studioLuxuryEnhancement,
            )
            if (rawMp3File != null && rawMp3File.exists() && !studioLuxuryEnhancement && acousticMode == TtsPersonaAcousticMode.NATURAL) {
                return@withContext EdgeTtsSynthResult(buffer = mastered, mp3File = rawMp3File)
            }
            val previewWav = File(cacheDir, "tts_out_${UUID.randomUUID()}.wav")
            writeStereoWavFile(previewWav, mastered)
            runCatching { rawMp3File?.delete() }
            EdgeTtsSynthResult(buffer = mastered, mp3File = previewWav)
        } finally {
            runCatching { sourceWavForFfmpeg?.delete() }
        }
    }

    private fun trimSilenceEdgesAndFade(
        buffer: StereoAudioBuffer,
        keepPadMs: Int,
    ): StereoAudioBuffer {
        val n = buffer.frameCount
        if (n <= 64) return buffer
        val sr = buffer.sampleRate.coerceAtLeast(8000)
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
}
