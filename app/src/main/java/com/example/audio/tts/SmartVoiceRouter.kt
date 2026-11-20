package com.example.audio.tts

import android.content.Context
import com.example.audio.AudioDecoder
import com.example.audio.OnlineGuard
import com.example.audio.fx.StudioLuxuryAudioFilter
import com.example.audio.writeStereoWavFile
import com.example.audio.aivoice.AiArabicDialect
import com.example.audio.aivoice.AiBreathCadence
import com.example.audio.aivoice.AiVoiceCategory
import com.example.audio.aivoice.AiVoiceEmotion
import com.example.audio.aivoice.AiVoiceEngineKind
import com.example.audio.aivoice.AiVoiceLanguage
import com.example.audio.aivoice.AiPitchContour
import com.example.audio.aivoice.AiVoiceTimbre
import com.example.audio.aivoice.AiVocalStyle
import com.example.audio.aivoice.AiVoiceStudioCoordinator
import com.example.audio.aivoice.AiVoiceSynthOutcome
import com.example.audio.aivoice.AiVoiceSynthRequest
import com.example.model.AppLanguage
import com.example.model.StereoAudioBuffer
import com.example.model.TTS_DIALECTS
import com.example.model.TTS_VOICE_PERSONAS
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsEngineMode
import com.example.model.TtsGender
import com.example.model.TtsPersonaAcousticMode
import com.example.model.TtsVoicePersona
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TtsSynthResult(
    val buffer: StereoAudioBuffer,
    val audioFile: File,
)

sealed interface VoiceRouteOutcome {
    data class Success(
        val result: TtsSynthResult,
        val engineUsed: TtsEngineMode = TtsEngineMode.VOX_NEURAL_ONLINE,
        val usedFallback: Boolean = false,
    ) : VoiceRouteOutcome

    data class ProviderError(val message: String) : VoiceRouteOutcome
}

class SmartVoiceRouter(
    private val coordinator: AiVoiceStudioCoordinator = AiVoiceStudioCoordinator(),
) {
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
        persona: TtsVoicePersona? = null,
        acousticMode: TtsPersonaAcousticMode? = null,
        fixedSid: Int? = null,
    ): VoiceRouteOutcome = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return@withContext VoiceRouteOutcome.ProviderError(
                if (language == AppLanguage.EN) "Please enter text first." else "يرجى إدخال النص أولاً.",
            )
        }
        if (TtsTextLimits.countWords(cleanText) > TtsTextLimits.MAX_WORDS) {
            return@withContext VoiceRouteOutcome.ProviderError(
                if (language == AppLanguage.EN) {
                    "Text is limited to ${TtsTextLimits.MAX_WORDS} words."
                } else {
                    "الحد الأقصى للنص هو ${TtsTextLimits.MAX_WORDS} كلمة."
                },
            )
        }
        if (!OnlineGuard.isOnline(context)) {
            return@withContext VoiceRouteOutcome.ProviderError(
                if (language == AppLanguage.EN) {
                    "VoxCPM2 requires an internet connection."
                } else {
                    "يتطلب محرك VoxCPM2 اتصالاً بالإنترنت."
                },
            )
        }

        val outputDir = File(context.cacheDir, "vox_tts").apply { mkdirs() }
        val prompt = buildString {
            append(persona?.titleEn ?: if (gender == TtsGender.MALE) "adult male voice" else "adult female voice")
            append(". ")
            append(persona?.descEn.orEmpty())
            append(". ")
            append("Delivery style: ")
            append(style.name.lowercase().replace('_', ' '))
            append(". Dialect: ")
            append(dialect.id)
            if (acousticMode != null) {
                append(". Acoustic profile: ")
                append(acousticMode.name.lowercase().replace('_', ' '))
            }
            if (studioLuxuryEnhancement) append(". Clean studio mastering.")
        }
        val request = AiVoiceSynthRequest(
            engine = AiVoiceEngineKind.VOXCPM_2,
            text = cleanText,
            language = dialect.toAiLanguage(language),
            dialect = AiArabicDialect.MSA,
            category = if (gender == TtsGender.MALE) AiVoiceCategory.MALE else AiVoiceCategory.FEMALE,
            voiceDescriptionPrompt = prompt,
            emotion = style.toAiEmotion(),
            emotionIntensity = 0.65f,
            speechRate = speechRate.coerceIn(0.60f, 1.50f),
            pitchOffsetSemitones = (12.0 * kotlin.math.ln(pitch.coerceIn(0.62f, 1.65f)) / kotlin.math.ln(2.0)).toFloat(),
            fixedSid = fixedSid ?: persona.toStableSpeakerId(),
            fixedSeed = 42,
            fixedFormantShift = persona?.defaultFormantShift ?: 1f,
            fixedEqLowDb = persona?.defaultWarmthDb ?: 0f,
            fixedEqPresenceDb = persona?.defaultPresenceDb ?: 0f,
            vocalStyle = style.toAiVocalStyle(),
            breathCadence = style.toAiBreathCadence(),
            pitchContour = style.toAiPitchContour(),
            timbre = acousticMode.toAiVoiceTimbre(),
            articulationClarity = when (style) {
                TtsDeliveryStyle.RADIO_ANCHOR -> 0.82f
                TtsDeliveryStyle.ENERGETIC -> 0.88f
                TtsDeliveryStyle.STORYTELLING -> 0.66f
                else -> 0.72f
            },
        )
        when (
            val outcome = coordinator.synthesizeSingle(
                context = context,
                request = request,
                backendUrl = "",
                outputDir = outputDir,
            )
        ) {
            is AiVoiceSynthOutcome.Success -> {
                val file = File(outcome.audioFilePath)
                val decoded = AudioDecoder.decodeFile(file)
                if (decoded == null || decoded.frameCount <= 0) {
                    file.delete()
                    VoiceRouteOutcome.ProviderError(
                        if (language == AppLanguage.EN) {
                            "VoxCPM2 returned audio that could not be decoded."
                        } else {
                            "أعاد VoxCPM2 ملفاً صوتياً تعذر فك ترميزه."
                        },
                    )
                } else {
                    try {
                        val mastered = if (studioLuxuryEnhancement) {
                            StudioLuxuryAudioFilter.applyAcousticMastering(
                                context = context,
                                decodedBuffer = decoded,
                                mode = acousticMode ?: persona?.defaultAcousticMode ?: TtsPersonaAcousticMode.NATURAL,
                                style = style,
                                gender = gender,
                                luxuryBoost = true,
                                cancelPreviousFfmpeg = false,
                            )
                        } else {
                            decoded
                        }
                        if (mastered !== decoded) writeStereoWavFile(file, mastered)
                        VoiceRouteOutcome.Success(TtsSynthResult(mastered, file))
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        file.delete()
                        throw ce
                    } catch (e: Exception) {
                        file.delete()
                        VoiceRouteOutcome.ProviderError(
                            if (language == AppLanguage.EN) {
                                "VoxCPM2 audio mastering failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
                            } else {
                                "فشلت معالجة صوت VoxCPM2: ${e.localizedMessage ?: e.javaClass.simpleName}"
                            },
                        )
                    }
                }
            }
            is AiVoiceSynthOutcome.Error -> VoiceRouteOutcome.ProviderError(
                if (language == AppLanguage.EN) outcome.messageEn else outcome.messageAr,
            )
            is AiVoiceSynthOutcome.ConfigurationRequired -> VoiceRouteOutcome.ProviderError(
                if (language == AppLanguage.EN) outcome.explanationEn else outcome.explanationAr,
            )
        }
    }

    suspend fun generateNaturalVoice(
        text: String,
        dialect: TtsDialect = TTS_DIALECTS.first(),
        gender: TtsGender = TtsGender.MALE,
        outputFile: File,
        context: Context,
    ): Boolean = withContext(Dispatchers.IO) {
        val outcome = synthesizeStudioVoiceWithStatus(
            context = context,
            text = text,
            dialect = dialect,
            gender = gender,
            style = TtsDeliveryStyle.NATURAL_WARM,
            speechRate = 1.0f,
            pitch = 1.0f,
        )
        if (outcome !is VoiceRouteOutcome.Success) return@withContext false
        try {
            outputFile.parentFile?.mkdirs()
            val copied = outcome.result.audioFile.copyTo(outputFile, overwrite = true)
            copied.exists() && copied.length() > 44L
        } finally {
            outcome.result.audioFile.delete()
        }
    }

    suspend fun synthesizeStudioVoice(
        context: Context,
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
        persona: TtsVoicePersona? = null,
        acousticMode: TtsPersonaAcousticMode? = null,
    ): TtsSynthResult? = when (
        val outcome = synthesizeStudioVoiceWithStatus(
            context = context,
            text = text,
            dialect = dialect,
            gender = gender,
            style = style,
            speechRate = speechRate,
            pitch = pitch,
            persona = persona,
            acousticMode = acousticMode,
        )
    ) {
        is VoiceRouteOutcome.Success -> outcome.result
        is VoiceRouteOutcome.ProviderError -> null
    }

    private fun TtsDeliveryStyle.toAiEmotion(): AiVoiceEmotion = when (this) {
        TtsDeliveryStyle.SAD -> AiVoiceEmotion.SAD
        TtsDeliveryStyle.ANGRY -> AiVoiceEmotion.SERIOUS
        TtsDeliveryStyle.ENERGETIC -> AiVoiceEmotion.EXCITED
        TtsDeliveryStyle.DRAMATIC, TtsDeliveryStyle.RADIO_ANCHOR -> AiVoiceEmotion.SERIOUS
        TtsDeliveryStyle.STORYTELLING -> AiVoiceEmotion.CALM
        TtsDeliveryStyle.NATURAL_WARM -> AiVoiceEmotion.NEUTRAL
    }

    private fun TtsVoicePersona?.toStableSpeakerId(): Int {
        val selected = this ?: return 0
        val builtInIndex = TTS_VOICE_PERSONAS.indexOfFirst { it.id == selected.id }
        return builtInIndex.takeIf { it >= 0 }?.plus(1) ?: 0
    }

    private fun TtsDeliveryStyle.toAiVocalStyle(): AiVocalStyle = when (this) {
        TtsDeliveryStyle.NATURAL_WARM -> AiVocalStyle.WARM
        TtsDeliveryStyle.ENERGETIC, TtsDeliveryStyle.ANGRY -> AiVocalStyle.ENERGETIC
        TtsDeliveryStyle.SAD, TtsDeliveryStyle.STORYTELLING -> AiVocalStyle.STORYTELLING
        TtsDeliveryStyle.DRAMATIC -> AiVocalStyle.DRAMATIC
        TtsDeliveryStyle.RADIO_ANCHOR -> AiVocalStyle.NEWS_ANCHOR
    }

    private fun TtsDeliveryStyle.toAiBreathCadence(): AiBreathCadence = when (this) {
        TtsDeliveryStyle.DRAMATIC, TtsDeliveryStyle.SAD, TtsDeliveryStyle.STORYTELLING -> AiBreathCadence.MEASURED
        TtsDeliveryStyle.ENERGETIC, TtsDeliveryStyle.ANGRY -> AiBreathCadence.BRISK
        else -> AiBreathCadence.NATURAL
    }

    private fun TtsDeliveryStyle.toAiPitchContour(): AiPitchContour = when (this) {
        TtsDeliveryStyle.ENERGETIC -> AiPitchContour.GENTLE_RISE
        TtsDeliveryStyle.STORYTELLING, TtsDeliveryStyle.DRAMATIC, TtsDeliveryStyle.SAD -> AiPitchContour.GENTLE_FALL
        else -> AiPitchContour.STEADY
    }

    private fun TtsPersonaAcousticMode?.toAiVoiceTimbre(): AiVoiceTimbre = when (this) {
        TtsPersonaAcousticMode.DOCUMENTARY -> AiVoiceTimbre.DEEP
        TtsPersonaAcousticMode.PODCAST -> AiVoiceTimbre.WARM
        TtsPersonaAcousticMode.HALL_ECHO, TtsPersonaAcousticMode.NATURAL, null -> AiVoiceTimbre.BALANCED
    }

    private fun TtsDialect.toAiLanguage(fallback: AppLanguage): AiVoiceLanguage =
        when (localeTag.substringBefore('-').lowercase()) {
            "en" -> AiVoiceLanguage.EN
            "fa" -> AiVoiceLanguage.FA
            "tr" -> AiVoiceLanguage.TR
            "ar" -> AiVoiceLanguage.AR
            else -> when (fallback) {
                AppLanguage.EN -> AiVoiceLanguage.EN
                AppLanguage.FA -> AiVoiceLanguage.FA
                AppLanguage.TR -> AiVoiceLanguage.TR
                AppLanguage.AR -> AiVoiceLanguage.AR
            }
        }
}
