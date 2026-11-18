package com.aistudio.voicepro.sonixk

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.audio.AudioDecoder
import com.example.audio.CompressorProcessor
import com.example.audio.EchoReverbProcessor
import com.example.audio.StereoBiquadFilter
import com.example.audio.TARGET_SAMPLE_RATE
import com.example.audio.cache.SmartCacheManager
import com.example.audio.clamp
import com.example.audio.normalizeAudio
import com.example.audio.readStereoWavFile
import com.example.audio.resampleStereo
import com.example.audio.timeStretchWsolaStereo
import com.example.audio.transformVoiceOffline
import com.example.audio.tts.ArabicTextDiacritizer
import com.example.audio.writeStereoWavFile
import com.example.diagnostics.AppDiagnosticLog
import com.example.diagnostics.FfmpegKitBridge
import com.example.model.AppLanguage
import com.example.model.CompressorParams
import com.example.model.EchoReverbParams
import com.example.model.StereoAudioBuffer
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsGender
import com.example.model.TtsPersonaAcousticMode
import com.example.model.TtsVoicePersona
import com.example.util.SafeTextGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
import kotlin.math.tanh

data class LocalTtsSynthResult(
    val buffer: StereoAudioBuffer,
    val audioFile: File,
)

sealed interface LocalTtsOutcome {
    data class Success(val result: LocalTtsSynthResult) : LocalTtsOutcome
    data class Error(val message: String) : LocalTtsOutcome
}

enum class LocalTtsVoiceProfile(
    val titleAr: String,
    val titleEn: String,
    val pitchMultiplier: Float,
    val rateMultiplier: Float,
    val formantShift: Float,
) {
    DEEP_MALE(
        titleAr = "رجل عميق",
        titleEn = "Deep Male",
        pitchMultiplier = 0.80f,
        rateMultiplier = 0.94f,
        formantShift = 0.86f,
    ),
    SOFT_FEMALE(
        titleAr = "امرأة ناعمة",
        titleEn = "Soft Female",
        pitchMultiplier = 1.15f,
        rateMultiplier = 0.96f,
        formantShift = 1.13f,
    ),
    DOCUMENTARY(
        titleAr = "معلق وثائقي",
        titleEn = "Documentary Narrator",
        pitchMultiplier = 0.86f,
        rateMultiplier = 0.90f,
        formantShift = 0.90f,
    ),
    PODCAST(
        titleAr = "بودكاست احترافي",
        titleEn = "Pro Podcast",
        pitchMultiplier = 0.96f,
        rateMultiplier = 0.98f,
        formantShift = 0.96f,
    ),
    CHILD(
        titleAr = "طفل",
        titleEn = "Child",
        pitchMultiplier = 1.32f,
        rateMultiplier = 1.05f,
        formantShift = 1.24f,
    ),
    HALL_ECHO(
        titleAr = "صدى قاعة",
        titleEn = "Hall Echo",
        pitchMultiplier = 0.94f,
        rateMultiplier = 0.93f,
        formantShift = 0.95f,
    ),
    NATURAL(
        titleAr = "إلقاء طبيعي",
        titleEn = "Natural Delivery",
        pitchMultiplier = 1.0f,
        rateMultiplier = 1.0f,
        formantShift = 1.0f,
    ),
}

/**
 * محرك النطق العربي المحلي (100% بدون إنترنت):
 * يعتمد كلياً على مكتبة Android القياسية [android.speech.tts.TextToSpeech]
 * عبر `tts.synthesizeToFile(...)` مدمجة مع `FFmpegKit` (فلاتر `asetrate`, `aresample`,
 * `atempo`, `equalizer`, `compand`, `aecho`) لتطبيق البصمة الصوتية والسرعة والحدة بدقة استوديو.
 */
object LocalArabicTtsEngine {

    private val ttsMutex = Mutex()
    private const val MAX_TTS_CHUNK_CHARS = 900

    fun resolveProfile(
        persona: TtsVoicePersona?,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        acousticMode: TtsPersonaAcousticMode?,
    ): LocalTtsVoiceProfile {
        if (acousticMode == TtsPersonaAcousticMode.HALL_ECHO) {
            return LocalTtsVoiceProfile.HALL_ECHO
        }
        return when (persona?.id) {
            "child_voice" -> LocalTtsVoiceProfile.CHILD
            "male_doc_deep", "male_promo_dramatic" ->
                if (acousticMode == TtsPersonaAcousticMode.DOCUMENTARY) LocalTtsVoiceProfile.DOCUMENTARY else LocalTtsVoiceProfile.DEEP_MALE
            "male_podcast_warm", "male_modern_energetic" ->
                if (acousticMode == TtsPersonaAcousticMode.PODCAST) LocalTtsVoiceProfile.PODCAST else LocalTtsVoiceProfile.DEEP_MALE
            "female_story_soft", "female_podcast_host", "female_news_anchor", "female_doc_dignified" ->
                when (acousticMode) {
                    TtsPersonaAcousticMode.DOCUMENTARY -> LocalTtsVoiceProfile.DOCUMENTARY
                    TtsPersonaAcousticMode.PODCAST -> LocalTtsVoiceProfile.PODCAST
                    else -> LocalTtsVoiceProfile.SOFT_FEMALE
                }
            else -> when (acousticMode) {
                TtsPersonaAcousticMode.DOCUMENTARY -> LocalTtsVoiceProfile.DOCUMENTARY
                TtsPersonaAcousticMode.PODCAST -> LocalTtsVoiceProfile.PODCAST
                TtsPersonaAcousticMode.HALL_ECHO -> LocalTtsVoiceProfile.HALL_ECHO
                else -> when {
                    gender == TtsGender.FEMALE -> LocalTtsVoiceProfile.SOFT_FEMALE
                    style == TtsDeliveryStyle.DRAMATIC -> LocalTtsVoiceProfile.DEEP_MALE
                    else -> LocalTtsVoiceProfile.NATURAL
                }
            }
        }
    }

    suspend fun synthesize(
        context: Context,
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speed: Float,
        pitch: Float,
        language: AppLanguage = AppLanguage.AR,
        studioLuxuryEnhancement: Boolean = true,
        persona: TtsVoicePersona? = null,
        acousticMode: TtsPersonaAcousticMode? = null,
    ): LocalTtsOutcome = withContext(Dispatchers.IO) {
        val cleanInput = ArabicTextDiacritizer.normalizeAndHintForTts(text.trim(), applyLightDiacritics = true)
        if (cleanInput.isEmpty()) {
            return@withContext LocalTtsOutcome.Error(
                when (language) {
                    AppLanguage.EN -> "Please enter text first."
                    AppLanguage.FA -> "لطفاً ابتدا متن را وارد کنید."
                    AppLanguage.AR -> "يرجى إدخال النص أولاً."
                },
            )
        }

        val effectiveAcousticMode = acousticMode ?: persona?.defaultAcousticMode ?: when (style) {
            TtsDeliveryStyle.DRAMATIC, TtsDeliveryStyle.STORYTELLING -> TtsPersonaAcousticMode.DOCUMENTARY
            TtsDeliveryStyle.RADIO_ANCHOR -> TtsPersonaAcousticMode.PODCAST
            else -> TtsPersonaAcousticMode.NATURAL
        }
        val profile = resolveProfile(
            persona = persona,
            gender = gender,
            style = style,
            acousticMode = effectiveAcousticMode,
        )

        val stylePitchRatio = 1f + (style.pitchOffsetHz / 120f)
        val targetPitch = clamp(pitch * profile.pitchMultiplier * stylePitchRatio, 0.55f, 1.75f)
        val targetSpeed = clamp(speed * style.rateFactor * profile.rateMultiplier, 0.50f, 1.85f)

        val cacheDir = File(context.cacheDir, "local_arabic_tts").apply { mkdirs() }
        val chunks = splitSentencesSmoothly(cleanInput, MAX_TTS_CHUNK_CHARS)
        if (chunks.isEmpty()) {
            return@withContext LocalTtsOutcome.Error(
                if (language == AppLanguage.EN) "No speakable text provided." else "النص المدخل لا يحتوي على كلمات صالحة للنطق.",
            )
        }

        val processedChunkBuffers = ArrayList<StereoAudioBuffer>(chunks.size)
        var singleChunkOutputFile: File? = null

        try {
            ttsMutex.withLock {
                for ((index, chunkText) in chunks.withIndex()) {
                    coroutineContext.ensureActive()
                    val rawTtsWav = File(cacheDir, "tts_raw_${UUID.randomUUID()}.wav")
                    val processedWav = File(cacheDir, "tts_fx_${UUID.randomUUID()}.wav")
                    try {
                        val synthResult = synthesizeChunkWithAndroidTts(
                            context = context.applicationContext,
                            chunkText = chunkText,
                            dialect = dialect,
                            gender = gender,
                            tempWavFile = rawTtsWav,
                            language = language,
                        )
                        if (!synthResult.success || !rawTtsWav.exists() || rawTtsWav.length() <= 44L) {
                            val reason = synthResult.errorMessage ?: "Android TextToSpeech synthesizeToFile failed"
                            AppDiagnosticLog.logRecoverable(
                                toolName = "LocalArabicTtsEngine",
                                titleAr = "تعذر توليد الصوت عبر محرك النطق المحلي في النظام",
                                titleEn = "Android TextToSpeech Failed",
                                causeAr = reason,
                                causeEn = reason,
                                errorCode = "LOCAL_TTS_SYNTH_ERR",
                            )
                            return@withContext LocalTtsOutcome.Error(reason)
                        }

                        val masteredBuffer = applyStudioProfileWithFfmpegOrDsp(
                            rawWavFile = rawTtsWav,
                            outWavFile = processedWav,
                            profile = profile,
                            targetSpeed = targetSpeed,
                            targetPitch = targetPitch,
                            style = style,
                            luxuryBoost = studioLuxuryEnhancement,
                        )

                        if (masteredBuffer == null || masteredBuffer.frameCount <= 0) {
                            runCatching { processedWav.delete() }
                            return@withContext LocalTtsOutcome.Error(
                                when (language) {
                                    AppLanguage.EN -> "Failed to process synthesized audio output."
                                    AppLanguage.FA -> "پردازش خروجی صوتی ناموفق بود."
                                    AppLanguage.AR -> "تعذر معالجة الملف الصوتي الناتج."
                                },
                            )
                        }

                        if (chunks.size == 1) {
                            singleChunkOutputFile = processedWav
                            processedChunkBuffers += masteredBuffer
                        } else {
                            processedChunkBuffers += trimSilenceEdgesAndFade(masteredBuffer, keepPadMs = 50)
                            runCatching { processedWav.delete() }
                        }
                    } finally {
                        runCatching { rawTtsWav.delete() }
                        if (chunks.size > 1 && index == chunks.lastIndex) {
                            // Per-chunk processedWav deleted above.
                        }
                    }
                }
            }

            val finalResult = if (chunks.size == 1 && singleChunkFileOrNull(singleChunkOutputFile) != null) {
                LocalTtsSynthResult(
                    buffer = processedChunkBuffers.first(),
                    audioFile = singleChunkOutputFile!!,
                )
            } else {
                val pauseMs = (style.breathPauseMs * 0.45f).roundToInt().coerceIn(70, 220)
                val stitched = stitchSentenceBuffersSmoothly(processedChunkBuffers, pauseMs)
                    ?: return@withContext LocalTtsOutcome.Error(
                        if (language == AppLanguage.EN) "Failed to stitch synthesized segments." else "تعذر دمج المقاطع الصوتية المولدة.",
                    )
                val stitchedWav = File(cacheDir, "tts_final_${UUID.randomUUID()}.wav")
                writeStereoWavFile(stitchedWav, stitched)
                LocalTtsSynthResult(buffer = stitched, audioFile = stitchedWav)
            }

            SmartCacheManager.pruneManagedBuckets(context)
            return@withContext LocalTtsOutcome.Success(finalResult)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            runCatching { singleChunkOutputFile?.delete() }
            throw ce
        } catch (t: Throwable) {
            runCatching { singleChunkOutputFile?.delete() }
            val reason = t.localizedMessage ?: t.javaClass.simpleName
            AppDiagnosticLog.logRecoverable(
                toolName = "LocalArabicTtsEngine",
                titleAr = "تعذر إتمام التوليد الصوتي المحلي",
                titleEn = "Local TTS Processing Error",
                causeAr = reason,
                causeEn = reason,
                errorCode = t.javaClass.simpleName,
                throwable = t,
            )
            return@withContext LocalTtsOutcome.Error(
                if (language == AppLanguage.EN) "Unable to synthesize speech ($reason)."
                else "تعذر توليد الصوت محلياً ($reason).",
            )
        }
    }

    private fun singleChunkFileOrNull(file: File?): File? {
        return file?.takeIf { it.exists() && it.length() > 44L }
    }

    private data class AndroidTtsChunkResult(
        val success: Boolean,
        val errorMessage: String? = null,
    )

    private suspend fun synthesizeChunkWithAndroidTts(
        context: Context,
        chunkText: String,
        dialect: TtsDialect,
        gender: TtsGender,
        tempWavFile: File,
        language: AppLanguage,
    ): AndroidTtsChunkResult {
        tempWavFile.parentFile?.mkdirs()
        if (tempWavFile.exists()) tempWavFile.delete()

        val initDeferred = CompletableDeferred<Int>()
        var ttsInstance: TextToSpeech? = null

        try {
            val createdTts = withContext(Dispatchers.Main) {
                TextToSpeech(context) { status ->
                    if (!initDeferred.isCompleted) {
                        initDeferred.complete(status)
                    }
                }
            }
            ttsInstance = createdTts

            val initStatus = withTimeoutOrNull(8_000L) { initDeferred.await() }
            if (initStatus != TextToSpeech.SUCCESS) {
                val msg = when (language) {
                    AppLanguage.EN -> "Device TextToSpeech engine is unavailable or not configured."
                    AppLanguage.FA -> "موتور گفتار محلی دستگاه در دسترس نیست."
                    AppLanguage.AR -> "محرك النطق المحلي في الجهاز غير متاح حالياً. تأكد من تفعيل محرك Text-to-Speech في إعدادات النظام."
                }
                return AndroidTtsChunkResult(false, msg)
            }

            configureLocaleAndVoice(createdTts, dialect, gender)
            runCatching { createdTts.setSpeechRate(1.0f) }
            runCatching { createdTts.setPitch(1.0f) }

            val utteranceId = "sonix_tts_${UUID.randomUUID()}"
            val synthDeferred = CompletableDeferred<Boolean>()
            var synthErrorCode: Int? = null

            createdTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}

                override fun onDone(id: String?) {
                    if (id == utteranceId && !synthDeferred.isCompleted) {
                        synthDeferred.complete(true)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId && !synthDeferred.isCompleted) {
                        synthDeferred.complete(false)
                    }
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && !synthDeferred.isCompleted) {
                        synthErrorCode = errorCode
                        synthDeferred.complete(false)
                    }
                }
            })

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

            val enqueueCode = withContext(Dispatchers.Main) {
                createdTts.synthesizeToFile(chunkText, params, tempWavFile, utteranceId)
            }
            if (enqueueCode != TextToSpeech.SUCCESS) {
                val msg = when (language) {
                    AppLanguage.EN -> "TextToSpeech.synthesizeToFile rejected request (code $enqueueCode)."
                    AppLanguage.FA -> "درخواست تبدیل متن به گفتار رد شد (کد $enqueueCode)."
                    AppLanguage.AR -> "تعذر بدء توليد الملف الصوتي عبر محرك النظام (رمز $enqueueCode)."
                }
                return AndroidTtsChunkResult(false, msg)
            }

            val done = withTimeoutOrNull(25_000L) { synthDeferred.await() }
            if (done != true || !tempWavFile.exists() || tempWavFile.length() <= 44L) {
                val msg = when {
                    done == null -> if (language == AppLanguage.EN) {
                        "Local TTS timed out while synthesizing audio."
                    } else {
                        "انتهت مهلة محرك النطق المحلي أثناء توليد الصوت."
                    }
                    else -> if (language == AppLanguage.EN) {
                        "Local TTS engine failed (code ${synthErrorCode ?: -1}). Ensure Arabic/target voice data is installed in Android TTS settings."
                    } else {
                        "تعذر توليد الصوت من محرك النطق المحلي (رمز ${synthErrorCode ?: -1}). تأكد من تثبيت بيانات الصوت للغة المختارة في إعدادات جهازك."
                    }
                }
                return AndroidTtsChunkResult(false, msg)
            }

            return AndroidTtsChunkResult(true, null)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return AndroidTtsChunkResult(false, t.localizedMessage ?: t.javaClass.simpleName)
        } finally {
            val toClose = ttsInstance
            if (toClose != null) {
                runCatching {
                    withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                        runCatching { toClose.stop() }
                        runCatching { toClose.shutdown() }
                    }
                }
            }
        }
    }

    private fun configureLocaleAndVoice(
        tts: TextToSpeech,
        dialect: TtsDialect,
        gender: TtsGender,
    ) {
        val primaryLocale = Locale.forLanguageTag(dialect.localeTag)
        val fallbackLocale = Locale.forLanguageTag(dialect.fallbackLocaleTag)
        val langOnlyLocale = Locale(primaryLocale.language.ifBlank { "ar" })

        val candidates = listOf(primaryLocale, fallbackLocale, langOnlyLocale, Locale.getDefault())
        var chosenLocale: Locale? = null
        for (loc in candidates) {
            val res = runCatching { tts.setLanguage(loc) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                chosenLocale = loc
                break
            }
        }

        val installedVoices = runCatching { tts.voices }.getOrNull().orEmpty()
        if (installedVoices.isEmpty()) return

        val targetLang = (chosenLocale ?: primaryLocale).language
        val matchingVoices = installedVoices.filter { v ->
            v.locale.language.equals(targetLang, ignoreCase = true) && !v.isNetworkConnectionRequired
        }.ifEmpty {
            installedVoices.filter { it.locale.language.equals(targetLang, ignoreCase = true) }
        }
        if (matchingVoices.isEmpty()) return

        val preferred = pickGenderMatchedVoice(matchingVoices, gender) ?: matchingVoices.firstOrNull()
        if (preferred != null) {
            runCatching { tts.voice = preferred }
        }
    }

    private fun pickGenderMatchedVoice(voices: List<Voice>, gender: TtsGender): Voice? {
        val femaleHints = listOf("female", "woman", "sana", "zariyah", "salma", "rana", "amany", "fatima", "leda", "kore", "ard", "wavenet-a", "wavenet-c", "network-a", "x-ard", "local-a")
        val maleHints = listOf("male", "man", "hamed", "bassel", "shakir", "laith", "hamdan", "taim", "wavenet-b", "wavenet-d", "network-b", "x-arc", "local-b")
        val hints = if (gender == TtsGender.FEMALE) femaleHints else maleHints
        return voices.firstOrNull { v ->
            val nameLower = v.name.lowercase(Locale.US)
            hints.any { hint -> nameLower.contains(hint) }
        }
    }

    internal suspend fun applyStudioProfileWithFfmpegOrDsp(
        rawWavFile: File,
        outWavFile: File,
        profile: LocalTtsVoiceProfile,
        targetSpeed: Float,
        targetPitch: Float,
        style: TtsDeliveryStyle,
        luxuryBoost: Boolean,
    ): StereoAudioBuffer? = withContext(Dispatchers.IO) {
        val inputSampleRate = readWavSampleRate(rawWavFile).coerceIn(8000, 96000)

        if (FfmpegKitBridge.isAvailable()) {
            val filterGraphs = buildFfmpegFilterGraphCandidates(
                inputSampleRate = inputSampleRate,
                profile = profile,
                targetSpeed = targetSpeed,
                targetPitch = targetPitch,
                style = style,
                luxuryBoost = luxuryBoost,
            )
            for (graph in filterGraphs) {
                if (outWavFile.exists()) outWavFile.delete()
                val cmd =
                    "-y -i \"${rawWavFile.absolutePath}\" -af \"$graph\" -ar $TARGET_SAMPLE_RATE -ac 2 \"${outWavFile.absolutePath}\""
                val ok = FfmpegKitBridge.execute(
                    command = cmd,
                    toolName = "LocalArabicTts_${profile.name}",
                    logNonZeroFailure = false,
                )
                if (ok && outWavFile.exists() && outWavFile.length() > 44L) {
                    val decoded = readStereoWavFile(outWavFile) ?: AudioDecoder.decodeFile(outWavFile)
                    if (decoded != null && decoded.frameCount > 0) {
                        return@withContext decoded
                    }
                }
            }
            runCatching { outWavFile.delete() }
        }

        // Pure-Kotlin studio DSP fallback when optional FFmpegKit AAR is absent
        val rawDecoded = readStereoWavFile(rawWavFile) ?: AudioDecoder.decodeFile(rawWavFile) ?: return@withContext null
        val mastered = withContext(Dispatchers.Default) {
            processBufferPureKotlinDsp(
                source = rawDecoded,
                profile = profile,
                targetSpeed = targetSpeed,
                targetPitch = targetPitch,
                style = style,
                luxuryBoost = luxuryBoost,
            )
        }
        writeStereoWavFile(outWavFile, mastered)
        mastered
    }

    fun buildFfmpegFilterGraphCandidates(
        inputSampleRate: Int,
        profile: LocalTtsVoiceProfile,
        targetSpeed: Float,
        targetPitch: Float,
        style: TtsDeliveryStyle,
        luxuryBoost: Boolean,
    ): List<String> {
        val clampedPitch = clamp(targetPitch, 0.55f, 1.75f)
        val clampedSpeed = clamp(targetSpeed, 0.50f, 1.85f)
        val shiftedRate = (inputSampleRate * clampedPitch).roundToInt().coerceIn(6000, 192000)
        val compensatedTempo = (clampedSpeed / clampedPitch).toDouble().coerceIn(0.30, 3.30)

        val coreFilters = mutableListOf<String>()
        coreFilters += "asetrate=$shiftedRate"
        coreFilters += "aresample=$TARGET_SAMPLE_RATE"
        coreFilters += buildAtempoChain(compensatedTempo)

        val extraWarmth = if (luxuryBoost) 1.0f else 0.0f
        when (profile) {
            LocalTtsVoiceProfile.DEEP_MALE -> {
                coreFilters += "highpass=f=55"
                coreFilters += "equalizer=f=115:width_type=h:width=95:g=${fmt(4.2f + extraWarmth)}"
                coreFilters += "equalizer=f=240:width_type=h:width=140:g=1.4"
                coreFilters += "equalizer=f=2400:width_type=h:width=1100:g=1.6"
            }
            LocalTtsVoiceProfile.SOFT_FEMALE -> {
                coreFilters += "highpass=f=75"
                coreFilters += "equalizer=f=220:width_type=h:width=130:g=${fmt(1.8f + extraWarmth * 0.6f)}"
                coreFilters += "equalizer=f=3600:width_type=h:width=1400:g=2.4"
                coreFilters += "equalizer=f=8500:width_type=h:width=2200:g=1.6"
            }
            LocalTtsVoiceProfile.DOCUMENTARY -> {
                coreFilters += "highpass=f=55"
                coreFilters += "equalizer=f=110:width_type=h:width=85:g=${fmt(4.0f + extraWarmth)}"
                coreFilters += "equalizer=f=250:width_type=h:width=130:g=1.2"
                coreFilters += "equalizer=f=2500:width_type=h:width=1100:g=2.1"
            }
            LocalTtsVoiceProfile.PODCAST -> {
                coreFilters += "highpass=f=70"
                coreFilters += "equalizer=f=150:width_type=h:width=110:g=${fmt(2.8f + extraWarmth)}"
                coreFilters += "equalizer=f=2800:width_type=h:width=1200:g=2.8"
                coreFilters += "equalizer=f=8800:width_type=h:width=2400:g=1.8"
            }
            LocalTtsVoiceProfile.CHILD -> {
                coreFilters += "highpass=f=95"
                coreFilters += "equalizer=f=3500:width_type=h:width=1500:g=2.6"
                coreFilters += "equalizer=f=7500:width_type=h:width=2200:g=1.5"
            }
            LocalTtsVoiceProfile.HALL_ECHO -> {
                coreFilters += "highpass=f=65"
                coreFilters += "equalizer=f=130:width_type=h:width=100:g=${fmt(2.8f + extraWarmth)}"
                coreFilters += "equalizer=f=2600:width_type=h:width=1150:g=2.0"
            }
            LocalTtsVoiceProfile.NATURAL -> {
                coreFilters += "highpass=f=65"
                if (luxuryBoost) {
                    coreFilters += "equalizer=f=160:width_type=h:width=115:g=${fmt(2.2f + (style.warmthDb - 2f).coerceAtLeast(0f) * 0.4f)}"
                    coreFilters += "equalizer=f=3200:width_type=h:width=1300:g=1.6"
                }
            }
        }

        val compandFilter =
            "compand=attacks=0.015:decays=0.22:points=-80/-80|-28/-24|-16/-12|-6/-4|0/-1.5:soft-knee=6:gain=2.2"
        val acompressorFallback = "acompressor=threshold=-18dB:ratio=3.2:attack=8:release=85:makeup=2.6"

        val primary = buildList {
            addAll(coreFilters)
            add(compandFilter)
            if (profile == LocalTtsVoiceProfile.HALL_ECHO) {
                add("aecho=0.8:0.78:70|140|230:0.42|0.28|0.18")
            }
            add("alimiter=limit=0.95")
        }.joinToString(",")

        val safeFallback = buildList {
            add("asetrate=$shiftedRate")
            add("aresample=$TARGET_SAMPLE_RATE")
            add(buildAtempoChain(compensatedTempo))
            add("highpass=f=65")
            add(acompressorFallback)
            if (profile == LocalTtsVoiceProfile.HALL_ECHO) {
                add("aecho=0.8:0.78:75|160:0.38|0.22")
            }
            add("alimiter=limit=0.95")
        }.joinToString(",")

        return listOf(primary, safeFallback)
    }

    private fun buildAtempoChain(tempo: Double): String {
        val stages = mutableListOf<Double>()
        var remaining = tempo.coerceIn(0.25, 4.0)
        while (remaining > 2.0) {
            stages += 2.0
            remaining /= 2.0
        }
        while (remaining < 0.5) {
            stages += 0.5
            remaining /= 0.5
        }
        stages += remaining
        return stages.joinToString(",") { "atempo=${"%.3f".format(Locale.US, it)}" }
    }

    private suspend fun processBufferPureKotlinDsp(
        source: StereoAudioBuffer,
        profile: LocalTtsVoiceProfile,
        targetSpeed: Float,
        targetPitch: Float,
        style: TtsDeliveryStyle,
        luxuryBoost: Boolean,
    ): StereoAudioBuffer {
        var current = if (source.sampleRate != TARGET_SAMPLE_RATE) {
            resampleStereo(source, TARGET_SAMPLE_RATE)
        } else {
            source
        }

        // 1. Speed modification (WSOLA time-stretch preserves formants & prevents robotic artifacts)
        val clampedSpeed = clamp(targetSpeed, 0.50f, 1.85f)
        if (abs(clampedSpeed - 1.0f) > 0.02f) {
            current = timeStretchWsolaStereo(current, clampedSpeed)
        }

        // 2. Pitch + Formant voice profile shaping
        val pitchSemitones = (12.0 * ln(clamp(targetPitch, 0.55f, 1.75f).toDouble()) / ln(2.0)).toFloat()
        val warmthDb = when (profile) {
            LocalTtsVoiceProfile.DEEP_MALE -> 4.2f
            LocalTtsVoiceProfile.DOCUMENTARY -> 3.8f
            LocalTtsVoiceProfile.PODCAST -> 2.8f
            LocalTtsVoiceProfile.SOFT_FEMALE -> 1.8f
            LocalTtsVoiceProfile.CHILD -> 0.5f
            LocalTtsVoiceProfile.HALL_ECHO -> 2.6f
            LocalTtsVoiceProfile.NATURAL -> if (luxuryBoost) 2.2f else 0.6f
        } + (if (luxuryBoost) 0.8f else 0f)

        val presenceDb = when (profile) {
            LocalTtsVoiceProfile.PODCAST -> 2.8f
            LocalTtsVoiceProfile.SOFT_FEMALE -> 2.4f
            LocalTtsVoiceProfile.CHILD -> 2.6f
            LocalTtsVoiceProfile.DOCUMENTARY -> 2.1f
            LocalTtsVoiceProfile.HALL_ECHO -> 2.0f
            LocalTtsVoiceProfile.DEEP_MALE -> 1.6f
            LocalTtsVoiceProfile.NATURAL -> if (style == TtsDeliveryStyle.RADIO_ANCHOR) 2.2f else 1.4f
        }

        if (abs(pitchSemitones) > 0.15f || abs(profile.formantShift - 1.0f) > 0.02f) {
            current = transformVoiceOffline(
                source = current,
                pitchSemitones = pitchSemitones,
                formantShift = profile.formantShift,
                warmthDb = warmthDb,
                presenceDb = presenceDb,
                mix = 1.0f,
                studioMasteringPolish = true,
            )
        } else {
            val sr = current.sampleRate
            val hpf = StereoBiquadFilter().apply { configureHighPass(sr, 65.0, 0.707) }
            val warm = StereoBiquadFilter().apply { configureLowShelf(sr, 160.0, 0.75, warmthDb.toDouble()) }
            val pres = StereoBiquadFilter().apply { configurePeaking(sr, 2800.0, 1.15, presenceDb.toDouble()) }
            val outL = current.left.copyOf()
            val outR = current.right.copyOf()
            hpf.processStereo(outL, outR, current.frameCount)
            warm.processStereo(outL, outR, current.frameCount)
            pres.processStereo(outL, outR, current.frameCount)
            current = StereoAudioBuffer(outL, outR, sr)
        }

        // 3. Studio Compand / Dynamics compression
        val comp = CompressorProcessor(current.sampleRate)
        val compL = current.left.copyOf()
        val compR = current.right.copyOf()
        comp.processStereo(
            compL,
            compR,
            current.frameCount,
            CompressorParams(
                thresholdDb = -18f,
                ratio = 3.2f,
                attackMs = 8f,
                releaseMs = 85f,
                makeupGainDb = 2.6f,
            ),
        )
        for (i in 0 until current.frameCount) {
            compL[i] = tanh((compL[i] * 1.04).toDouble()).toFloat()
            compR[i] = tanh((compR[i] * 1.04).toDouble()).toFloat()
        }
        current = StereoAudioBuffer(compL, compR, current.sampleRate)

        // 4. Hall Echo spatial reverb if selected
        if (profile == LocalTtsVoiceProfile.HALL_ECHO) {
            val tailFrames = (current.sampleRate * 0.9f).roundToInt()
            val extL = FloatArray(current.frameCount + tailFrames)
            val extR = FloatArray(current.frameCount + tailFrames)
            System.arraycopy(current.left, 0, extL, 0, current.frameCount)
            System.arraycopy(current.right, 0, extR, 0, current.frameCount)
            val reverb = EchoReverbProcessor(current.sampleRate).apply {
                configure(
                    EchoReverbParams(
                        echoStrength = 0.28f,
                        echoDepth = 0.42f,
                        delayTimeMs = 155f,
                        feedback = 0.24f,
                        reverbSize = 0.72f,
                        reverbDecay = 0.56f,
                        preDelayMs = 24f,
                        wetDryMix = 0.34f,
                        stereoWidth = 0.78f,
                        earlyReflections = 0.48f,
                        lateReflections = 0.55f,
                        damping = 0.42f,
                        diffusion = 0.68f,
                    ),
                )
            }
            reverb.processStereo(extL, extR, extL.size)
            current = StereoAudioBuffer(extL, extR, current.sampleRate)
        }

        return normalizeAudio(current, -1.1f)
    }

    private fun readWavSampleRate(wavFile: File): Int {
        if (!wavFile.exists() || wavFile.length() < 28L) return TARGET_SAMPLE_RATE
        return runCatching {
            FileInputStream(wavFile).use { fis ->
                val hdr = ByteArray(28)
                var read = 0
                while (read < hdr.size) {
                    val n = fis.read(hdr, read, hdr.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < 28) return@use TARGET_SAMPLE_RATE
                if (String(hdr, 0, 4, Charsets.US_ASCII) != "RIFF") return@use TARGET_SAMPLE_RATE
                val bb = ByteBuffer.wrap(hdr, 24, 4).order(ByteOrder.LITTLE_ENDIAN)
                bb.int.takeIf { it in 8000..192000 } ?: TARGET_SAMPLE_RATE
            }
        }.getOrDefault(TARGET_SAMPLE_RATE)
    }

    internal fun trimSilenceEdgesAndFade(
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

    internal fun stitchSentenceBuffersSmoothly(
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

    internal fun splitSentencesSmoothly(rawText: String, maxChars: Int): List<String> {
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
