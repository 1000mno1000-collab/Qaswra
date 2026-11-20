package com.example.audio.ai

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Base64OutputStream
import com.example.BuildConfig
import com.example.audio.AudioDecoder
import com.example.audio.OnlineGuard
import com.example.audio.SherpaMoonshineAsrEngine
import com.example.audio.TARGET_SAMPLE_RATE
import com.example.audio.cache.SmartCacheManager
import com.example.audio.clamp
import com.example.audio.fx.StudioLuxuryAudioFilter
import com.example.audio.mergeTranscriptText
import com.example.audio.network.NetworkImmunityManager
import com.example.audio.processAudioFileWithFfmpeg
import com.example.audio.resampleStereo
import com.example.audio.resampleStereoToMono16k
import com.example.audio.transformVoiceOffline
import com.example.audio.tts.ArabicTextDiacritizer
import com.example.audio.tts.SmartVoiceRouter
import com.example.audio.tts.VoiceRouteOutcome
import com.example.audio.writeStereoWavFile
import com.example.diagnostics.AppDiagnosticLog
import com.example.diagnostics.FfmpegKitBridge
import com.example.model.AppLanguage
import com.example.model.DspConfig
import com.example.model.SmartMasterAnalysis
import com.example.model.StereoAudioBuffer
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsGender
import com.example.model.TtsPersonaAcousticMode
import com.example.model.TtsVoicePersona
import com.example.model.VoiceCharacterPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * مركز الذكاء الاصطناعي الصوتي الموحد في Sonix (Sonix Unified AI Engine).
 *
 * يربط أدوات الذكاء الاصطناعي الأربعة بنموذج **Gemini 2.5 Multimodal Audio API**
 * (أونلاين 100% ومجاناً للمستخدم عبر المفتاح المدمج بالمشروع دون أي مطالبة بمفتاح API)،
 * مع تكامل هجين (Hybrid Routing) مع محرك الشبكة العصبية المحلي [LocalNeuralVoiceEngine] (ONNX):
 * 1. استوديو النطق البشري (Human TTS Studio)
 * 2. محول الشخصيات الصوتية الذكي (AI Voice-to-Voice Transformer + معالجة FFmpegKit)
 * 3. تفريغ الصوت إلى نص (Multimodal Audio Transcription + تشكيل + تمييز المتحدثين)
 * 4. مهندس الصوت الذكي ومساعد الهندسة الصوتية (Acoustic Analysis + توصيات FFmpegKit فورية بضغطة زر)
 */
object SonixUnifiedAIEngine {

    private const val ENDPOINT_MULTIMODAL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    private const val ENDPOINT_AUDIO_TTS =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent"

    private const val IO_BUFFER_SIZE = 8192
    private const val TRANSCRIBE_CHUNK_SEC = 75
    private const val MAX_TRANSCRIBE_CHUNKS = 24 // Up to 30 minutes sequentially without RAM spikes

    sealed interface UnifiedSpeechResult {
        data class Success(
            val buffer: StereoAudioBuffer,
            val usedLocalOnnx: Boolean,
            val engineSummaryAr: String,
        ) : UnifiedSpeechResult

        data class Offline(val message: String) : UnifiedSpeechResult
        data class Error(val message: String) : UnifiedSpeechResult
    }

    sealed interface UnifiedTextResult {
        data class Success(val text: String) : UnifiedTextResult
        data class Offline(val message: String) : UnifiedTextResult
        data class Error(val message: String) : UnifiedTextResult
    }

    data class SmartEngineerRecommendation(
        val detectedProfile: String,
        val recommendations: List<String>,
        val diagnosticNotes: String,
        val recommendedFfmpegFilter: String,
    )

    private fun resolveProjectApiKey(): String {
        return BuildConfig.GEMINI_API_KEY
            .trim()
            .takeIf { it.startsWith("AIza") }
            .orEmpty()
    }

    private fun hasGeminiApiKey(): Boolean = resolveProjectApiKey().isNotBlank()

    // -------------------------------------------------------------------------
    // 1. Arabic Tashkeel, Punctuation & Breath-Pause Optimizer (Local or Gemini)
    // -------------------------------------------------------------------------

    suspend fun diacritizeArabicIfAvailable(
        context: Context,
        rawText: String,
        dialect: TtsDialect,
        style: TtsDeliveryStyle = TtsDeliveryStyle.NATURAL_WARM,
        acousticMode: TtsPersonaAcousticMode = TtsPersonaAcousticMode.NATURAL,
    ): String? = withContext(Dispatchers.IO) {
        val hasArabic = rawText.any { it in '\u0600'..'\u06FF' }
        if (!hasArabic || rawText.length > 1200) return@withContext null
        val diacriticCount = rawText.count { it in '\u064B'..'\u0652' }
        val alreadyRichlyFormatted = diacriticCount >= (rawText.length / 6).coerceAtLeast(4) &&
            acousticMode == TtsPersonaAcousticMode.NATURAL
        if (alreadyRichlyFormatted) return@withContext null
        if (!hasGeminiApiKey() || !OnlineGuard.isOnline(context)) {
            return@withContext ArabicTextDiacritizer.normalizeAndHintForTts(
                rawText = rawText,
                applyLightDiacritics = true,
            ).takeIf { it.isNotBlank() && it != rawText }
        }

        val dialectDesc = describeDialectArabic(dialect.id)
        val cadenceDirective = when (acousticMode) {
            TtsPersonaAcousticMode.DOCUMENTARY ->
                "اضبط علامات الترقيم والوقف الدرامي (... و ، و .) لتناسب إلقاءً وثائقياً رصيناً بوقفات تنفس مدروسة."
            TtsPersonaAcousticMode.PODCAST ->
                "اضبط الفواصل الطبيعية (،) وعلامات النبرة العفوية لتناسب بودكاست دافئ وسلس قريب من المايكروفون."
            TtsPersonaAcousticMode.HALL_ECHO ->
                "اضبط علامات الوقف الرخيم لتناسب قاعة فسيحة بصدى متزن ووضوح عالٍ."
            TtsPersonaAcousticMode.NATURAL ->
                "حافظ على سلاسة الجمل وضع فقط التشكيل وعلامات الترقيم الضرورية لإلقاء طبيعي نقي."
        }
        val emotionHint = when (style) {
            TtsDeliveryStyle.DRAMATIC -> "مع إبراز مواضع التفخيم والوقف السينمائي."
            TtsDeliveryStyle.STORYTELLING -> "مع لمسة شاعرية لطيفة في علامات السرد."
            TtsDeliveryStyle.RADIO_ANCHOR -> "بصياغة إخبارية دقيقة واضحة الأواخر."
            else -> ""
        }
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                "أنت محرر نطق عربي محترف. أضف علامات التشكيل الدقيقة للكلمات باللهجة ($dialectDesc) دون تغيير أو إضافة أو حذف أي كلمة أو شرح. $cadenceDirective $emotionHint\nأعد النص المُشكّل فقط:\n$rawText",
                            ),
                        ),
                    ),
                ),
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.12)
                    put("maxOutputTokens", 1500)
                },
            )
        }

        val outcome = postGeminiJson(
            urlStr = "$ENDPOINT_MULTIMODAL?key=${resolveProjectApiKey()}",
            bodyJson = payload.toString(),
            connectTimeoutMs = 3_500,
            readTimeoutMs = 6_000,
            language = AppLanguage.AR,
        )
        extractTextFromResponse(outcome.json)
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= rawText.length * 2 + 160 }
    }

    // -------------------------------------------------------------------------
    // 2. Unified Human TTS Studio (Local ONNX -> Gemini 2.5 Audio -> FFmpegKit)
    // -------------------------------------------------------------------------

    suspend fun synthesizeHumanSpeech(
        context: Context,
        cacheDir: File,
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        language: AppLanguage = AppLanguage.AR,
        persona: TtsVoicePersona? = null,
        acousticMode: TtsPersonaAcousticMode? = null,
    ): UnifiedSpeechResult = withContext(Dispatchers.IO) {
        // Hybrid Priority 1: Local ONNX Runtime model in assets/models/ (if bundled)
        val localResult = LocalNeuralVoiceEngine.synthesizeTtsIfAvailable(
            context = context,
            text = text,
            dialect = dialect,
            gender = gender,
            style = style,
            speechRate = speechRate,
        )
        if (localResult != null && localResult.buffer.frameCount > 0) {
            return@withContext UnifiedSpeechResult.Success(
                buffer = localResult.buffer,
                usedLocalOnnx = true,
                engineSummaryAr = "محرك الشبكة العصبية المحلي (${localResult.modelFileName})",
            )
        }

        val effectiveMode = acousticMode ?: persona?.defaultAcousticMode ?: TtsPersonaAcousticMode.NATURAL
        // Priority 2: Gemini 2.5 Audio TTS only when GEMINI_API_KEY is explicitly configured & online
        if (hasGeminiApiKey() && OnlineGuard.isOnline(context) && NetworkImmunityManager.pingReachable(context)) {
            val voiceName = persona?.geminiVoiceName ?: pickPrebuiltVoice(dialect, gender, style)
            val dialectCoaching = buildDialectInstruction(
                dialect = dialect,
                gender = gender,
                style = style,
                speechRate = speechRate,
                persona = persona,
                acousticMode = effectiveMode,
            )
            val prompt = "$dialectCoaching\n\nالنص المراد نطقه بصوت بشري طبيعي تماماً:\n\"$text\""

            val audioAttempt = requestGeminiAudioOutput(
                cacheDir = cacheDir,
                prompt = prompt,
                voiceName = voiceName,
                language = language,
            )
            if (audioAttempt is UnifiedSpeechResult.Success) {
                return@withContext audioAttempt
            }
        }

        // Priority 3: VoxCPM2 neural cloud fallback.
        val routed = SmartVoiceRouter().synthesizeStudioVoiceWithStatus(
            context = context,
            text = text,
            dialect = dialect,
            gender = gender,
            style = style,
            speechRate = speechRate,
            pitch = if (gender == TtsGender.FEMALE) 1.08f else 0.96f,
            language = language,
            studioLuxuryEnhancement = true,
            acousticMode = effectiveMode,
        )
        when (routed) {
            is VoiceRouteOutcome.Success -> UnifiedSpeechResult.Success(
                buffer = routed.result.buffer,
                usedLocalOnnx = false,
                engineSummaryAr = "VoxCPM2 Neural Cloud",
            )
            is VoiceRouteOutcome.ProviderError -> UnifiedSpeechResult.Error(routed.message)
        }
    }

    // -------------------------------------------------------------------------
    // 3. AI Voice-to-Voice Transformer (Local ONNX -> Gemini Audio + FFmpegKit)
    // -------------------------------------------------------------------------

    suspend fun convertVoiceToVoice(
        context: Context,
        sourceBuffer: StereoAudioBuffer?,
        sourceFile: File?,
        targetPreset: VoiceCharacterPreset?,
        targetLabel: String,
        pitchSemitones: Float,
        formantShift: Float,
        warmthDb: Float,
        presenceDb: Float,
        language: AppLanguage = AppLanguage.AR,
        targetPersona: TtsVoicePersona? = null,
        acousticMode: TtsPersonaAcousticMode = targetPersona?.defaultAcousticMode ?: TtsPersonaAcousticMode.NATURAL,
    ): UnifiedSpeechResult = withContext(Dispatchers.IO) {
        val workingDir = File(context.cacheDir, "sonix_ai_v2v").apply { mkdirs() }

        // Hybrid Priority 1: Local ONNX Voice Conversion if bundled
        val boundedForLocal = sourceBuffer ?: sourceFile?.let {
            AudioDecoder.decodeSampleWindowForAnalysis(it, maxSeconds = 28f)
        }
        if (boundedForLocal != null && boundedForLocal.frameCount > 0) {
            val localVc = LocalNeuralVoiceEngine.convertVoiceIfAvailable(
                context = context,
                inputAudio = boundedForLocal,
                targetCharacterId = targetPersona?.id ?: targetPreset?.id ?: "custom",
                pitchSemitones = pitchSemitones,
            )
            if (localVc != null && localVc.buffer.frameCount > 0) {
                val mastered = polishConvertedVoiceWithFfmpeg(
                    context = context,
                    cacheDir = workingDir,
                    buffer = localVc.buffer,
                    warmthDb = warmthDb,
                    presenceDb = presenceDb,
                    bassBoostDb = targetPreset?.bassBoostDb ?: 0f,
                    acousticMode = acousticMode,
                    style = targetPersona?.defaultStyle ?: TtsDeliveryStyle.NATURAL_WARM,
                    gender = targetPersona?.gender ?: TtsGender.MALE,
                )
                return@withContext UnifiedSpeechResult.Success(
                    buffer = mastered,
                    usedLocalOnnx = true,
                    engineSummaryAr = "محرك ONNX المحلي لتحويل الأصوات (${localVc.modelFileName})",
                )
            }
        }

        // Priority 2: If no Gemini key or offline, use local Formant + FFmpegKit studio character transformation
        val canUseGemini = hasGeminiApiKey() && OnlineGuard.isOnline(context) && NetworkImmunityManager.pingReachable(context)
        if (!canUseGemini) {
            return@withContext runLocalVoiceConversion(
                context = context,
                workingDir = workingDir,
                sourceBuffer = sourceBuffer,
                sourceFile = sourceFile,
                targetPreset = targetPreset,
                pitchSemitones = pitchSemitones,
                formantShift = formantShift,
                warmthDb = warmthDb,
                presenceDb = presenceDb,
                language = language,
                targetPersona = targetPersona,
                acousticMode = acousticMode,
            )
        }

        val audioB64 = extractBoundedWavChunkBase64(
            context = context,
            cacheDir = workingDir,
            sourceBuffer = sourceBuffer,
            sourceFile = sourceFile,
            startSec = 0,
            durationSec = 35,
        ) ?: return@withContext runLocalVoiceConversion(
            context = context,
            workingDir = workingDir,
            sourceBuffer = sourceBuffer,
            sourceFile = sourceFile,
            targetPreset = targetPreset,
            pitchSemitones = pitchSemitones,
            formantShift = formantShift,
            warmthDb = warmthDb,
            presenceDb = presenceDb,
            language = language,
            targetPersona = targetPersona,
            acousticMode = acousticMode,
        )

        val acousticStyleNote = when (acousticMode) {
            TtsPersonaAcousticMode.DOCUMENTARY -> "مع إضافة التشكيل وعلامات الوقف الوثائقية (... و ،)"
            TtsPersonaAcousticMode.PODCAST -> "مع إضافة الفواصل الطبيعية المناسبة لحديث البودكاست"
            TtsPersonaAcousticMode.HALL_ECHO -> "مع تنفس واضح ومساحة ترددية متوازنة تناسب القاعات"
            TtsPersonaAcousticMode.NATURAL -> "مع التشكيل الخفيف وعلامات الترقيم الدقيقة"
        }

        // Step A: Analyze exact words, dialect, emotion & cadence via Gemini 2.5 Flash Multimodal Audio
        val analysisPrompt = """
            استمع إلى التسجيل الصوتي المرفق بدقة شديدة واستخرج JSON فقط بهذه الحقول:
            {
              "transcript": "النص المنطوق حرفياً بالكامل $acousticStyleNote",
              "emotion": "إحدى القيم: WARM | DRAMATIC | SAD | ENERGETIC | ANGRY | RADIO | STORYTELLING",
              "dialect": "اسم اللهجة العربية أو اللغة المنطوقة مثل: عراقي، سعودي، مصري، شامي، مغربي، فصحى، إنجليزي",
              "gender_hint": "MALE أو FEMALE",
              "has_speech": true أو false
            }
            إذا لم يحتوي المقطع على كلام بشري واضح ضع "has_speech": false.
        """.trimIndent()

        val analyzePayload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject().apply {
                                        put("mimeType", "audio/wav")
                                        put("data", audioB64)
                                    },
                                ),
                            )
                            .put(JSONObject().put("text", analysisPrompt)),
                    ),
                ),
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.15)
                    put("responseMimeType", "application/json")
                },
            )
        }

        val analysisResp = postGeminiJson(
            urlStr = "$ENDPOINT_MULTIMODAL?key=${resolveProjectApiKey()}",
            bodyJson = analyzePayload.toString(),
            connectTimeoutMs = 5_000,
            readTimeoutMs = 20_000,
            language = language,
        )
        if (analysisResp.json == null) {
            return@withContext runLocalVoiceConversion(
                context = context,
                workingDir = workingDir,
                sourceBuffer = sourceBuffer,
                sourceFile = sourceFile,
                targetPreset = targetPreset,
                pitchSemitones = pitchSemitones,
                formantShift = formantShift,
                warmthDb = warmthDb,
                presenceDb = presenceDb,
                language = language,
                targetPersona = targetPersona,
                acousticMode = acousticMode,
            )
        }

        val rawJsonText = extractTextFromResponse(analysisResp.json).orEmpty()
        val parsed = parseJsonObjectSafe(rawJsonText)
        val hasSpeech = parsed?.optBoolean("has_speech", true) ?: true
        val verbatimText = parsed?.optString("transcript")?.trim().orEmpty()
        if (!hasSpeech || verbatimText.isBlank()) {
            return@withContext runLocalVoiceConversion(
                context = context,
                workingDir = workingDir,
                sourceBuffer = sourceBuffer,
                sourceFile = sourceFile,
                targetPreset = targetPreset,
                pitchSemitones = pitchSemitones,
                formantShift = formantShift,
                warmthDb = warmthDb,
                presenceDb = presenceDb,
                language = language,
                targetPersona = targetPersona,
                acousticMode = acousticMode,
            )
        }

        val detectedDialect = parsed?.optString("dialect")?.trim().orEmpty().ifBlank { "العربية الطبيعية" }
        val detectedEmotion = parsed?.optString("emotion")?.trim().orEmpty().ifBlank { "WARM" }

        val targetCharacterSpec = buildVoiceCharacterCoaching(
            preset = targetPreset,
            targetPersona = targetPersona,
            acousticMode = acousticMode,
            targetLabel = targetPersona?.titleAr ?: targetLabel,
            pitchSemitones = pitchSemitones,
            formantShift = formantShift,
            detectedDialect = detectedDialect,
            detectedEmotion = detectedEmotion,
        )
        val renderPrompt =
            "$targetCharacterSpec\n\nأعد نطق الكلام التالي حرفياً بنفس المعنى والشعور الصوتي والوقفات المدروسة:\n\"$verbatimText\""

        val targetVoiceName = targetPersona?.geminiVoiceName
            ?: pickCharacterPrebuiltVoice(targetPreset, pitchSemitones, formantShift)
        val rendered = requestGeminiAudioOutput(
            cacheDir = workingDir,
            prompt = renderPrompt,
            voiceName = targetVoiceName,
            language = language,
        )
        return@withContext when (rendered) {
            is UnifiedSpeechResult.Success -> {
                val polished = polishConvertedVoiceWithFfmpeg(
                    context = context,
                    cacheDir = workingDir,
                    buffer = rendered.buffer,
                    warmthDb = warmthDb,
                    presenceDb = presenceDb,
                    bassBoostDb = targetPreset?.bassBoostDb ?: 0f,
                    acousticMode = acousticMode,
                    style = targetPersona?.defaultStyle ?: TtsDeliveryStyle.NATURAL_WARM,
                    gender = targetPersona?.gender
                        ?: if (formantShift > 1.05f || pitchSemitones > 1.5f) TtsGender.FEMALE else TtsGender.MALE,
                )
                UnifiedSpeechResult.Success(
                    buffer = polished,
                    usedLocalOnnx = false,
                    engineSummaryAr = "Gemini 2.5 Audio ($targetVoiceName • ${acousticMode.titleAr}) + FFmpegKit",
                )
            }
            else -> runLocalVoiceConversion(
                context = context,
                workingDir = workingDir,
                sourceBuffer = sourceBuffer,
                sourceFile = sourceFile,
                targetPreset = targetPreset,
                pitchSemitones = pitchSemitones,
                formantShift = formantShift,
                warmthDb = warmthDb,
                presenceDb = presenceDb,
                language = language,
                targetPersona = targetPersona,
                acousticMode = acousticMode,
            )
        }
    }

    private suspend fun runLocalVoiceConversion(
        context: Context,
        workingDir: File,
        sourceBuffer: StereoAudioBuffer?,
        sourceFile: File?,
        targetPreset: VoiceCharacterPreset?,
        pitchSemitones: Float,
        formantShift: Float,
        warmthDb: Float,
        presenceDb: Float,
        language: AppLanguage,
        targetPersona: TtsVoicePersona?,
        acousticMode: TtsPersonaAcousticMode,
    ): UnifiedSpeechResult {
        val inputBuf = sourceBuffer ?: sourceFile?.let {
            AudioDecoder.decodeFile(it) ?: AudioDecoder.decodeSampleWindowForAnalysis(it, maxSeconds = 45f)
        }
        if (inputBuf == null || inputBuf.frameCount <= 0) {
            return UnifiedSpeechResult.Error(
                if (language == AppLanguage.EN) "No playable audio found for voice transformation."
                else "تعذر قراءة المقطع الصوتي للمعالجة المحلية.",
            )
        }
        val transformed = transformVoiceOffline(
            source = inputBuf,
            pitchSemitones = pitchSemitones,
            formantShift = formantShift,
            warmthDb = warmthDb,
            presenceDb = presenceDb,
            mix = 0.88f,
            bassBoostDb = targetPreset?.bassBoostDb ?: 0f,
            vibratoHz = targetPreset?.vibratoHz ?: 0f,
            vibratoDepth = targetPreset?.vibratoDepth ?: 0f,
            echoDelayMs = targetPreset?.echoDelayMs ?: 0f,
            echoDecay = targetPreset?.echoDecay ?: 0f,
            dualLayerPitchSemitones = targetPreset?.dualLayerPitchSemitones ?: 0f,
            dualLayerFormantShift = targetPreset?.dualLayerFormantShift ?: 1.0f,
            dualLayerMix = targetPreset?.dualLayerMix ?: 0f,
            studioMasteringPolish = true,
        )
        val mastered = polishConvertedVoiceWithFfmpeg(
            context = context,
            cacheDir = workingDir,
            buffer = transformed,
            warmthDb = warmthDb,
            presenceDb = presenceDb,
            bassBoostDb = targetPreset?.bassBoostDb ?: 0f,
            acousticMode = acousticMode,
            style = targetPersona?.defaultStyle ?: TtsDeliveryStyle.NATURAL_WARM,
            gender = targetPersona?.gender
                ?: if (formantShift > 1.05f || pitchSemitones > 1.5f) TtsGender.FEMALE else TtsGender.MALE,
        )
        return UnifiedSpeechResult.Success(
            buffer = mastered,
            usedLocalOnnx = true,
            engineSummaryAr = "محرك الاستوديو المحلي (Formant DSP + FFmpegKit)",
        )
    }

    // -------------------------------------------------------------------------
    // 4. Multimodal Audio Transcription (Buffer OR Large Streaming Disk File)
    // -------------------------------------------------------------------------

    suspend fun transcribeAudioMultimodal(
        context: Context,
        buffer: StereoAudioBuffer?,
        sourceFile: File?,
        language: AppLanguage,
        onChunkProgress: ((String) -> Unit)? = null,
    ): UnifiedTextResult = withContext(Dispatchers.IO) {
        if (!hasGeminiApiKey() || !OnlineGuard.isOnline(context) || !NetworkImmunityManager.pingReachable(context)) {
            return@withContext transcribeLocallyWithSherpa(
                context = context,
                buffer = buffer,
                sourceFile = sourceFile,
                language = language,
                onChunkProgress = onChunkProgress,
            )
        }

        val workingDir = File(context.cacheDir, "sonix_ai_transcribe").apply { mkdirs() }
        val totalDurationSec = when {
            buffer != null && buffer.frameCount > 0 ->
                (buffer.frameCount.toDouble() / buffer.sampleRate.coerceAtLeast(8000)).coerceAtLeast(1.0)
            sourceFile != null && sourceFile.exists() ->
                (probeDurationMs(sourceFile) / 1000.0).coerceAtLeast(1.0)
            else -> return@withContext UnifiedTextResult.Error(
                if (language == AppLanguage.EN) "No active audio loaded to transcribe."
                else "لا يوجد مقطع صوتي حالياً لتفريغه. سجّل أو استورد صوتاً أولاً.",
            )
        }

        val totalChunks = ceil(totalDurationSec / TRANSCRIBE_CHUNK_SEC.toDouble())
            .toInt()
            .coerceIn(1, MAX_TRANSCRIBE_CHUNKS)

        val transcriptBuilder = StringBuilder()

        for (chunkIdx in 0 until totalChunks) {
            coroutineContext.ensureActive()
            val startSec = chunkIdx * TRANSCRIBE_CHUNK_SEC
            val durSec = min(
                TRANSCRIBE_CHUNK_SEC.toDouble(),
                totalDurationSec - startSec.toDouble(),
            ).roundToInt().coerceAtLeast(2)

            if (totalChunks > 1) {
                onChunkProgress?.invoke(
                    if (language == AppLanguage.EN) {
                        "Transcribing segment ${chunkIdx + 1} of $totalChunks..."
                    } else {
                        "جاري تفريغ المقطع ${chunkIdx + 1} من $totalChunks..."
                    },
                )
            }

            val audioB64 = extractBoundedWavChunkBase64(
                context = context,
                cacheDir = workingDir,
                sourceBuffer = buffer,
                sourceFile = sourceFile,
                startSec = startSec,
                durationSec = durSec,
            ) ?: continue

            val prompt = """
                فرّغ التسجيل الصوتي المرفق إلى نص دقيق ومفهوم بالكامل:
                - إذا كان الكلام بالعربية: اكتب العربية الفصحى أو اللهجة المنطوقة بدقة مع علامات الترقيم والتشكيل الخفيف للكلمات الملتبسة.
                - إذا تعدد المتحدثون أو تغيرت الأصوات: ميّز بينهم بعناوين واضحة مثل (المتحدث 1:) و (المتحدث 2:).
                - لا تضف أي مقدمات أو شروحات جانبية، وأعد النص المفرّغ مباشرة فقط.
            """.trimIndent()

            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray()
                                .put(
                                    JSONObject().put(
                                        "inlineData",
                                        JSONObject().apply {
                                            put("mimeType", "audio/wav")
                                            put("data", audioB64)
                                        },
                                    ),
                                )
                                .put(JSONObject().put("text", prompt)),
                        ),
                    ),
                )
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("temperature", 0.1)
                    },
                )
            }

            val resp = postGeminiJson(
                urlStr = "$ENDPOINT_MULTIMODAL?key=${resolveProjectApiKey()}",
                bodyJson = payload.toString(),
                connectTimeoutMs = 5_000,
                readTimeoutMs = 35_000,
                language = language,
            )
            if (resp.json == null) {
                if (transcriptBuilder.isNotBlank()) {
                    // Return what was already transcribed gracefully
                    break
                }
                val localAttempt = transcribeLocallyWithSherpa(
                    context = context,
                    buffer = buffer,
                    sourceFile = sourceFile,
                    language = language,
                    onChunkProgress = onChunkProgress,
                )
                return@withContext if (localAttempt is UnifiedTextResult.Success) {
                    localAttempt
                } else {
                    UnifiedTextResult.Error(
                        resp.error ?: friendlyNetworkError(language, "تعذر إتمام التفريغ الصوتي السحابي"),
                    )
                }
            }

            val chunkText = extractTextFromResponse(resp.json)?.trim().orEmpty()
            if (chunkText.isNotBlank()) {
                if (totalChunks > 1) {
                    val mm = startSec / 60
                    val ss = startSec % 60
                    if (transcriptBuilder.isNotEmpty()) transcriptBuilder.append("\n\n")
                    transcriptBuilder.append("[${"%02d:%02d".format(Locale.US, mm, ss)}]\n")
                }
                transcriptBuilder.append(chunkText)
            }
        }

        val finalTranscript = transcriptBuilder.toString().trim()
        if (finalTranscript.isBlank()) {
            return@withContext UnifiedTextResult.Error(
                if (language == AppLanguage.EN) {
                    "Could not detect clear spoken words in this audio."
                } else {
                    "لم يتم العثور على كلام واضح قابل للتفريغ داخل المقطع الصوتي."
                },
            )
        }
        UnifiedTextResult.Success(finalTranscript)
    }

    private suspend fun transcribeLocallyWithSherpa(
        context: Context,
        buffer: StereoAudioBuffer?,
        sourceFile: File?,
        language: AppLanguage,
        onChunkProgress: ((String) -> Unit)?,
    ): UnifiedTextResult {
        if (buffer == null && (sourceFile == null || !sourceFile.exists())) {
            return UnifiedTextResult.Error(
                if (language == AppLanguage.EN) "No active audio loaded to transcribe."
                else "لا يوجد مقطع صوتي حالياً لتفريغه. سجّل أو استورد صوتاً أولاً.",
            )
        }
        val engine = SherpaMoonshineAsrEngine(context.applicationContext)
        if (!engine.isNativeLibraryAvailable()) {
            return UnifiedTextResult.Error(
                if (language == AppLanguage.EN) "Local Sherpa-ONNX engine is unavailable on this device ABI."
                else "محرك Sherpa-ONNX المحلي غير متوافق مع معمارية هذا الجهاز.",
            )
        }
        if (!engine.isModelReady()) {
            if (OnlineGuard.isOnline(context)) {
                onChunkProgress?.invoke(
                    if (language == AppLanguage.EN) "Downloading local Sherpa-ONNX Arabic model..."
                    else "جاري تجهيز نموذج Sherpa-ONNX المحلي للتفريغ...",
                )
                engine.ensureModelsDownloaded()
            }
            if (!engine.isModelReady()) {
                return UnifiedTextResult.Error(
                    if (language == AppLanguage.EN) {
                        "Local Sherpa-ONNX model is not downloaded yet. Open Speech-to-Text to download it once or connect to the internet."
                    } else {
                        "يرجى تحميل نموذج Sherpa-ONNX المحلي مرة واحدة من شاشة تحويل الكلام إلى نص للعمل بدون إنترنت."
                    },
                )
            }
        }
        onChunkProgress?.invoke(
            if (language == AppLanguage.EN) "Transcribing locally with Sherpa-ONNX..."
            else "جاري التفريغ محلياً عبر Sherpa-ONNX + Silero VAD...",
        )
        val samples16k = when {
            buffer != null && buffer.frameCount > 0 -> resampleStereoToMono16k(buffer)
            sourceFile != null -> engine.extractMono16kSamplesFromFile(sourceFile)
            else -> null
        } ?: return UnifiedTextResult.Error(
            if (language == AppLanguage.EN) "Failed to decode audio samples for local transcription."
            else "تعذر استخراج عينات الصوت للتفريغ المحلي.",
        )

        val localText = suspendCancellableCoroutine<String?> { cont ->
            val sb = StringBuilder()
            val started = engine.startOfflineClipTranscription(
                samples16k = samples16k,
                onPartialUpdate = {},
                onSegmentCommitted = { seg ->
                    val merged = mergeTranscriptText(sb.toString(), seg)
                    sb.clear()
                    sb.append(merged)
                },
                onCompleted = {
                    if (cont.isActive) cont.resume(sb.toString().trim())
                },
                onFatalError = {
                    if (cont.isActive) cont.resume(sb.toString().trim().ifBlank { null })
                },
            )
            if (!started && cont.isActive) {
                cont.resume(null)
            }
            cont.invokeOnCancellation {
                engine.stop()
            }
        }?.trim().orEmpty()

        return if (localText.isNotBlank()) {
            UnifiedTextResult.Success(localText)
        } else {
            UnifiedTextResult.Error(
                if (language == AppLanguage.EN) "No clear speech was detected by local Sherpa-ONNX."
                else "لم يرصد محرك Sherpa-ONNX كلاماً واضحاً في المقطع.",
            )
        }
    }

    // -------------------------------------------------------------------------
    // 5. Smart AI Audio Engineer (Multimodal Diagnosis + Safe FFmpeg Filter Graph)
    // -------------------------------------------------------------------------

    suspend fun analyzeAudioForSmartEngineer(
        context: Context,
        sourceBuffer: StereoAudioBuffer?,
        sourceFile: File?,
        acousticMetrics: SmartMasterAnalysis,
        language: AppLanguage,
    ): SmartEngineerRecommendation? = withContext(Dispatchers.IO) {
        if (!hasGeminiApiKey() || !OnlineGuard.isOnline(context) || !NetworkImmunityManager.pingReachable(context)) {
            return@withContext buildLocalSmartEngineerRecommendation(acousticMetrics, language)
        }
        val workingDir = File(context.cacheDir, "sonix_ai_engineer").apply { mkdirs() }
        val audioB64 = extractBoundedWavChunkBase64(
            context = context,
            cacheDir = workingDir,
            sourceBuffer = sourceBuffer,
            sourceFile = sourceFile,
            startSec = 0,
            durationSec = 20,
        ) ?: return@withContext null

        val langInstr = if (language == AppLanguage.EN) {
            "Write profile, recommendations, and diagnosticNotes in clear concise English."
        } else {
            "اكتب الحقول profile و recommendations و diagnosticNotes بالعربية الاحترافية الواضحة."
        }

        val engineerPrompt = """
            أنت مهندس صوت خبير (Mastering & Vocal Engineer). استمع للعينة الصوتية المرفقة، واستعن بالقياسات الفيزيائية التالية:
            - RMS: ${"%.1f".format(Locale.US, acousticMetrics.rmsDb)} dB
            - Peak: ${"%.1f".format(Locale.US, acousticMetrics.peakDb)} dB
            - Crest Factor: ${"%.1f".format(Locale.US, acousticMetrics.crestFactorDb)} dB
            - Noise Floor: ${"%.1f".format(Locale.US, acousticMetrics.noiseFloorDb)} dB
            - Energy Split (Low/Mid/High): ${(acousticMetrics.lowEnergyRatio * 100).toInt()}% / ${(acousticMetrics.midEnergyRatio * 100).toInt()}% / ${(acousticMetrics.highEnergyRatio * 100).toInt()}%

            $langInstr
            أعد كائن JSON فقط بالتنسيق التالي:
            {
              "profile": "وصف دقيق لطبيعة الصوت وجودة التسجيل",
              "diagnosticNotes": "تشخيص هندسي مختصر يشرح التشويه أو الصدى أو المكتوم أو الضجيج والحل الأنسب",
              "recommendations": [
                "توصية هندسية 1",
                "توصية هندسية 2",
                "توصية هندسية 3"
              ],
              "ffmpegFilter": "سلسلة فلاتر FFmpeg جاهزة للتنفيذ الصوتي مثل: highpass=f=80,equalizer=f=220:width_type=h:width=160:g=-2.0,equalizer=f=2800:width_type=h:width=1200:g=2.5,acompressor=threshold=-18dB:ratio=3.2:attack=8:release=85:makeup=2.5,alimiter=limit=0.95"
            }
            تنبيه صارم حول ffmpegFilter: استخدم فلاتر FFmpeg القياسية المضمونة فقط (highpass, lowpass, equalizer, afftdn, acompressor, alimiter, bass, treble) مفصولة بفاصلة (,) بدون علامات اقتباس أو فواصل منقوطة.
        """.trimIndent()

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject().apply {
                                        put("mimeType", "audio/wav")
                                        put("data", audioB64)
                                    },
                                ),
                            )
                            .put(JSONObject().put("text", engineerPrompt)),
                    ),
                ),
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.2)
                    put("responseMimeType", "application/json")
                },
            )
        }

        val resp = postGeminiJson(
            urlStr = "$ENDPOINT_MULTIMODAL?key=${resolveProjectApiKey()}",
            bodyJson = payload.toString(),
            connectTimeoutMs = 4_500,
            readTimeoutMs = 18_000,
            language = language,
        )
        val json = parseJsonObjectSafe(extractTextFromResponse(resp.json).orEmpty())
            ?: return@withContext buildLocalSmartEngineerRecommendation(acousticMetrics, language)

        val profile = json.optString("profile").trim().ifBlank { acousticMetrics.detectedProfile }
        val diagNotes = json.optString("diagnosticNotes").trim()
        val recs = mutableListOf<String>()
        val recArr = json.optJSONArray("recommendations")
        if (recArr != null) {
            for (i in 0 until recArr.length()) {
                val item = recArr.optString(i).trim()
                if (item.isNotEmpty()) recs += item
            }
        }
        val rawFilter = json.optString("ffmpegFilter").trim()
        val safeFilter = sanitizeFfmpegAudioFilterGraph(rawFilter)
            .ifBlank { buildFallbackFfmpegGraph(acousticMetrics) }

        SmartEngineerRecommendation(
            detectedProfile = profile,
            recommendations = recs,
            diagnosticNotes = diagNotes,
            recommendedFfmpegFilter = safeFilter,
        )
    }

    private fun buildLocalSmartEngineerRecommendation(
        metrics: SmartMasterAnalysis,
        language: AppLanguage,
    ): SmartEngineerRecommendation {
        val graph = buildFallbackFfmpegGraph(metrics)
        val recs = mutableListOf<String>()
        if (language == AppLanguage.EN) {
            if (metrics.noiseFloorDb > -48f) {
                recs += "Apply spectral noise attenuation (noise floor ${"%.1f".format(Locale.US, metrics.noiseFloorDb)} dB)"
            }
            if (metrics.lowEnergyRatio > 0.40f) {
                recs += "Cut low-end mud around 220 Hz and engage 100 Hz high-pass"
            }
            if (metrics.highEnergyRatio < 0.14f) {
                recs += "Lift vocal presence around 2.6 kHz for clearer articulation"
            }
            if (recs.isEmpty()) {
                recs += "Gentle dynamic compression and broadcast loudness normalization (-16 LUFS)"
            }
            return SmartEngineerRecommendation(
                detectedProfile = metrics.detectedProfile,
                recommendations = recs,
                diagnosticNotes = "Local acoustic inspection via DSP + FFmpegKit graph ready to apply.",
                recommendedFfmpegFilter = graph,
            )
        } else {
            if (metrics.noiseFloorDb > -48f) {
                recs += "عزل الضوضاء الطيفية لخفض أرضية الضجيج (${"%.1f".format(Locale.US, metrics.noiseFloorDb)} dB)"
            }
            if (metrics.lowEnergyRatio > 0.40f) {
                recs += "تخفيف الترددات المنخفضة المكتومة عند 220Hz مع فلتر high-pass عند 100Hz"
            }
            if (metrics.highEnergyRatio < 0.14f) {
                recs += "إبراز وضوح الصوت والحضور عند 2.6kHz"
            }
            if (recs.isEmpty()) {
                recs += "ضغط ديناميكي متوازن وموازنة الشدة (-16 LUFS) عبر FFmpegKit"
            }
            return SmartEngineerRecommendation(
                detectedProfile = metrics.detectedProfile,
                recommendations = recs,
                diagnosticNotes = "تم التحليل الصوتي محلياً وتجهيز سلسلة فلاتر FFmpegKit المناسبة للتنفيذ الفوري.",
                recommendedFfmpegFilter = graph,
            )
        }
    }

    /**
     * يطبّق سلسلة فلاتر FFmpeg الموصى بها من مهندس الصوت الذكي مباشرة بضغطة زر
     * سواء على [StereoAudioBuffer] في الذاكرة أو على ملف ضخم على القرص [sourceFile] (Zero-RAM).
     */
    suspend fun applyRecommendedFfmpegFilter(
        context: Context,
        sourceBuffer: StereoAudioBuffer?,
        sourceFile: File?,
        filterGraph: String,
    ): Pair<StereoAudioBuffer?, File?> = withContext(Dispatchers.IO) {
        val safeGraph = sanitizeFfmpegAudioFilterGraph(filterGraph)
        if (safeGraph.isBlank() || !FfmpegKitBridge.isAvailable()) {
            return@withContext null to null
        }

        if (sourceBuffer != null) {
            val cacheDir = File(context.cacheDir, "sonix_ai_master").apply { mkdirs() }
            val inWav = File(cacheDir, "ai_eng_in_${UUID.randomUUID()}.wav")
            val outWav = File(cacheDir, "ai_eng_out_${UUID.randomUUID()}.wav")
            try {
                writeStereoWavFile(inWav, sourceBuffer)
                val sr = sourceBuffer.sampleRate.coerceAtLeast(8000)
                val cmd = "-y -i \"${inWav.absolutePath}\" -af \"$safeGraph\" -ar $sr -ac 2 \"${outWav.absolutePath}\""
                val ok = FfmpegKitBridge.execute(cmd, toolName = "SmartAiAudioEngineer", logNonZeroFailure = true)
                if (ok && outWav.exists() && outWav.length() > 44L) {
                    val decoded = AudioDecoder.decodeFile(outWav)
                    if (decoded != null && decoded.frameCount > 0) {
                        return@withContext decoded to null
                    }
                }
            } finally {
                runCatching { inWav.delete() }
                runCatching { outWav.delete() }
            }
        } else if (sourceFile != null && sourceFile.exists()) {
            val processedFile = processAudioFileWithFfmpeg(
                context = context,
                inputFile = sourceFile,
                audioFilter = safeGraph,
                toolName = "SmartAiAudioEngineer",
            )
            if (processedFile != null && processedFile.exists()) {
                return@withContext null to processedFile
            }
        }
        null to null
    }

    // -------------------------------------------------------------------------
    // 6. Audio Engineering Knowledge Assistant (gemini-2.5-flash)
    // -------------------------------------------------------------------------

    suspend fun askAudioAssistant(
        context: Context,
        query: String,
        language: AppLanguage,
    ): UnifiedTextResult = withContext(Dispatchers.IO) {
        if (hasGeminiApiKey() && OnlineGuard.isOnline(context) && NetworkImmunityManager.pingReachable(context)) {
            val langInstr = when (language) {
                AppLanguage.EN -> "Answer clearly in English as a senior audio mastering engineer."
                AppLanguage.FA -> "به زبان فارسی روان و تخصصی به عنوان مهندس صدابرداری پاسخ بده."
                AppLanguage.TR -> "Kıdemli bir ses mastering mühendisi olarak Türkçe ve anlaşılır yanıt ver."
                AppLanguage.AR -> "أجب باللغة العربية الاحترافية الواضحة بصفتك مهندس صوت وماسترينغ خبير."
            }
            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "text",
                                    "$langInstr\nقدّم خطوات عملية واضحة وأرقام ترددات (Hz/dB) عند الحاجة دون حشو.\n\nسؤال المستخدم:\n$query",
                                ),
                            ),
                        ),
                    ),
                )
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("temperature", 0.35)
                        put("maxOutputTokens", 1400)
                    },
                )
            }

            val resp = postGeminiJson(
                urlStr = "$ENDPOINT_MULTIMODAL?key=${resolveProjectApiKey()}",
                bodyJson = payload.toString(),
                connectTimeoutMs = 4_500,
                readTimeoutMs = 20_000,
                language = language,
            )
            val geminiAnswer = extractTextFromResponse(resp.json)?.trim().orEmpty()
            if (geminiAnswer.isNotBlank()) {
                return@withContext UnifiedTextResult.Success(geminiAnswer)
            }
        }

        // Local & Free Assistant Fallback (when GEMINI_API_KEY is blank or offline)
        if (OnlineGuard.isOnline(context)) {
            val freeTurn = runCatching {
                SmartAssistantAndSearch.askPollinationsChat(
                    history = emptyList(),
                    userMessage = query,
                    language = language,
                    context = context,
                )
            }.getOrNull()
            if (freeTurn != null && !freeTurn.isOfflineError && freeTurn.replyText.isNotBlank()) {
                val matchedTools = SmartAssistantAndSearch.findBestToolWithTypoTolerance(query)
                val formatted = buildString {
                    append(freeTurn.replyText.trim())
                    if (matchedTools.isNotEmpty()) {
                        append("\n\n")
                        append(formatLocalToolGuide(matchedTools.first(), language))
                    }
                }
                return@withContext UnifiedTextResult.Success(formatted)
            }
        }

        val localMatches = SmartAssistantAndSearch.findBestToolWithTypoTolerance(query)
        if (localMatches.isNotEmpty()) {
            val combined = localMatches.take(2).joinToString("\n\n") { tool ->
                formatLocalToolGuide(tool, language)
            }
            return@withContext UnifiedTextResult.Success(combined)
        }

        val generalGuidance = when (language) {
            AppLanguage.EN -> buildString {
                append("Smart Audio Guide (Local):\n")
                append("• Remove noise/hum: use Voice Purifier (80 Hz high-pass + spectral denoise).\n")
                append("• Vocal clarity: boost +2 to +3 dB around 2.5–3 kHz and trim mud at 220 Hz.\n")
                append("• Social loudness: use Reels & Podcast -14 LUFS template or Auto-Master.")
            }
            AppLanguage.FA -> buildString {
                append("راهنمای هوشمند صدابرداری (محلی):\n")
                append("• حذف نویز: از تصفیه‌کننده صدا (High-pass 80Hz + کاهش نویز طیفی) استفاده کنید.\n")
                append("• وضوح صدا: فرکانس 2.5kHz تا 3kHz را 2-3 dB تقویت و 220Hz را کمی کاهش دهید.")
            }
            AppLanguage.TR -> buildString {
                append("Akıllı Ses Rehberi (Yerel):\n")
                append("• Gürültüyü temizlemek için Ses Arındırıcı'yı (80 Hz high-pass + spektral gürültü azaltma) kullanın.\n")
                append("• Konuşma netliği için 2,5–3 kHz bandını 2–3 dB artırıp 220 Hz'i hafifçe azaltın.")
            }
            AppLanguage.AR -> buildString {
                append("مرشد الهندسة الصوتية الذكي (محلي):\n")
                append("• لعزل الضوضاء والهواء: استخدم أداة «منقّي الصوت» (High-Pass 80Hz + عزل طيفي).\n")
                append("• لرفع وضوح الكلام: ارفع التردد 2.5–3 kHz بمقدار +2.5 dB وخفّض 220 Hz بمقدار -2 dB.\n")
                append("• للريلز والبودكاست: استخدم قالب -14 LUFS أو الماسترينغ الذكي تلقائياً.")
            }
        }
        UnifiedTextResult.Success(generalGuidance)
    }

    private fun formatLocalToolGuide(tool: ToolGuideResult, language: AppLanguage): String {
        val header = if (language == AppLanguage.EN) {
            "Recommended Tool: ${tool.toolName}"
        } else {
            "الأداة الأنسب في Sonix: ${tool.toolName}"
        }
        return buildString {
            append(header)
            append("\n")
            append(tool.matchReason)
            if (tool.howToUseSteps.isNotEmpty()) {
                append("\n")
                append(tool.howToUseSteps.joinToString("\n"))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal Helpers: Audio Output, Zero-RAM Streaming Slice, FFmpeg Polish
    // -------------------------------------------------------------------------

    private suspend fun requestGeminiAudioOutput(
        cacheDir: File,
        prompt: String,
        voiceName: String,
        language: AppLanguage,
    ): UnifiedSpeechResult {
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt)),
                    ),
                ),
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", voiceName),
                            ),
                        ),
                    )
                },
            )
        }

        val outcome = postGeminiJson(
            urlStr = "$ENDPOINT_AUDIO_TTS?key=${resolveProjectApiKey()}",
            bodyJson = payload.toString(),
            connectTimeoutMs = 4_500,
            readTimeoutMs = 18_000,
            language = language,
        )
        if (outcome.json == null) {
            return UnifiedSpeechResult.Error(
                outcome.error ?: friendlyNetworkError(language, "تعذر الاتصال بمحرك Gemini الصوتي"),
            )
        }

        val parts = outcome.json
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return UnifiedSpeechResult.Error("لم يُرجع الخادم بيانات صوتية صالحة")

        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val b64 = inline.optString("data")
            val mime = inline.optString("mimeType").lowercase(Locale.US)
            if (b64.isBlank()) continue

            val rawAudio = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: continue
            if (rawAudio.size < 64) continue

            val buffer = decodeInlineAudioBytes(cacheDir, rawAudio, mime)
            if (buffer != null && buffer.frameCount > 0) {
                return UnifiedSpeechResult.Success(
                    buffer = buffer,
                    usedLocalOnnx = false,
                    engineSummaryAr = "Gemini 2.5 Audio ($voiceName)",
                )
            }
        }
        return UnifiedSpeechResult.Error("لم يتم استلام صوت صالح من الخادم السحابي")
    }

    private suspend fun polishConvertedVoiceWithFfmpeg(
        context: Context,
        cacheDir: File,
        buffer: StereoAudioBuffer,
        warmthDb: Float,
        presenceDb: Float,
        bassBoostDb: Float,
        acousticMode: TtsPersonaAcousticMode = TtsPersonaAcousticMode.NATURAL,
        style: TtsDeliveryStyle = TtsDeliveryStyle.NATURAL_WARM,
        gender: TtsGender = TtsGender.MALE,
    ): StereoAudioBuffer {
        if (buffer.frameCount <= 0) return buffer
        // Use exact Acoustic Mastering FFmpegKit chains when Documentary or Podcast mode is active
        if (acousticMode == TtsPersonaAcousticMode.DOCUMENTARY || acousticMode == TtsPersonaAcousticMode.PODCAST) {
            return StudioLuxuryAudioFilter.applyAcousticMastering(
                context = context,
                decodedBuffer = buffer,
                mode = acousticMode,
                style = style,
                gender = gender,
                luxuryBoost = true,
            )
        }
        if (abs(warmthDb) < 0.25f && abs(presenceDb) < 0.25f && abs(bassBoostDb) < 0.25f) {
            return StudioLuxuryAudioFilter.applyAcousticMastering(
                context = context,
                decodedBuffer = buffer,
                mode = TtsPersonaAcousticMode.NATURAL,
                style = style,
                gender = gender,
                luxuryBoost = false,
            )
        }
        if (!FfmpegKitBridge.isAvailable()) return buffer
        val rawWav = File(cacheDir, "v2v_raw_${UUID.randomUUID()}.wav")
        val outWav = File(cacheDir, "v2v_pol_${UUID.randomUUID()}.wav")
        return try {
            writeStereoWavFile(rawWav, buffer)
            val totalWarmth = (warmthDb + bassBoostDb * 0.2f).coerceIn(-3f, 5f)
            val pres = presenceDb.coerceIn(-3f, 5f)
            val graph = listOf(
                "highpass=f=65",
                "equalizer=f=150:width_type=h:width=115:g=${fmt(totalWarmth)}",
                "equalizer=f=2600:width_type=h:width=1100:g=${fmt(pres)}",
                "alimiter=limit=0.96",
            ).joinToString(",")
            val sr = buffer.sampleRate.coerceAtLeast(8000)
            val cmd = "-y -i \"${rawWav.absolutePath}\" -af \"$graph\" -ar $sr -ac 2 \"${outWav.absolutePath}\""
            val ok = FfmpegKitBridge.execute(cmd, toolName = "VoiceToVoicePolish", logNonZeroFailure = false)
            if (ok && outWav.exists() && outWav.length() > 44L) {
                AudioDecoder.decodeFile(outWav) ?: buffer
            } else {
                buffer
            }
        } catch (_: Throwable) {
            buffer
        } finally {
            runCatching { rawWav.delete() }
            runCatching { outWav.delete() }
        }
    }

    /**
     * Zero-RAM compliant audio segment extractor -> 16kHz Mono WAV Base64 using 8192-byte streaming.
     * Supports BOTH in-memory [StereoAudioBuffer] AND huge multi-hundred-MB [sourceFile] on disk!
     */
    private suspend fun extractBoundedWavChunkBase64(
        context: Context,
        cacheDir: File,
        sourceBuffer: StereoAudioBuffer?,
        sourceFile: File?,
        startSec: Int,
        durationSec: Int,
    ): String? {
        cacheDir.mkdirs()
        val chunkWav = File(cacheDir, "chunk_${UUID.randomUUID()}.wav")
        return try {
            val wroteChunk = when {
                sourceBuffer != null && sourceBuffer.frameCount > 0 -> {
                    val sr = sourceBuffer.sampleRate.coerceAtLeast(8000)
                    val startFrame = (startSec.toLong() * sr).coerceIn(0L, sourceBuffer.frameCount.toLong()).toInt()
                    val endFrame = ((startSec.toLong() + durationSec.toLong()) * sr)
                        .coerceIn(startFrame.toLong(), sourceBuffer.frameCount.toLong()).toInt()
                    if (endFrame - startFrame < 400) return null
                    val sliced = StereoAudioBuffer(
                        left = sourceBuffer.left.copyOfRange(startFrame, endFrame),
                        right = sourceBuffer.right.copyOfRange(startFrame, endFrame),
                        sampleRate = sr,
                    )
                    val monoResampled = downsampleForUpload(sliced, targetRate = 16000)
                    writeStereoWavFile(chunkWav, monoResampled)
                    chunkWav.exists() && chunkWav.length() > 44L
                }
                sourceFile != null && sourceFile.exists() -> {
                    if (FfmpegKitBridge.isAvailable()) {
                        val safeIn = SmartCacheManager.ensureSafeFfmpegInputFile(context, sourceFile)
                        try {
                            val cmd = "-y -ss $startSec -t $durationSec -i \"${safeIn.absolutePath}\" -vn -ar 16000 -ac 1 -c:a pcm_s16le \"${chunkWav.absolutePath}\""
                            FfmpegKitBridge.execute(cmd, toolName = "SonixAudioSlice", logNonZeroFailure = false) &&
                                chunkWav.exists() && chunkWav.length() > 44L
                        } finally {
                            if (safeIn != sourceFile) runCatching { safeIn.delete() }
                        }
                    } else if (startSec == 0) {
                        val windowBuf = AudioDecoder.decodeSampleWindowForAnalysis(
                            sourceFile,
                            maxSeconds = durationSec.coerceIn(4, 30).toFloat(),
                        )
                        if (windowBuf != null && windowBuf.frameCount > 0) {
                            writeStereoWavFile(chunkWav, downsampleForUpload(windowBuf, targetRate = 16000))
                            chunkWav.exists() && chunkWav.length() > 44L
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
                else -> false
            }

            if (!wroteChunk) return null

            // Encode the bounded small chunk WAV using a fixed 8192-byte stream buffer (Zero full-file load)
            val baos = ByteArrayOutputStream(chunkWav.length().toInt().coerceAtLeast(1024))
            Base64OutputStream(baos, Base64.NO_WRAP).use { b64Out ->
                BufferedInputStream(FileInputStream(chunkWav), IO_BUFFER_SIZE).use { input ->
                    val buf = ByteArray(IO_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        b64Out.write(buf, 0, read)
                    }
                }
                b64Out.flush()
            }
            baos.toString(Charsets.US_ASCII.name())
        } finally {
            runCatching { chunkWav.delete() }
        }
    }

    private fun downsampleForUpload(buffer: StereoAudioBuffer, targetRate: Int): StereoAudioBuffer {
        val unified = if (buffer.sampleRate == targetRate) buffer else resampleStereo(buffer, targetRate)
        val n = unified.frameCount
        val mono = FloatArray(n) { i -> clamp(0.5f * (unified.left[i] + unified.right[i]), -1f, 1f) }
        return StereoAudioBuffer(mono, mono, targetRate)
    }

    private fun probeDurationMs(file: File): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 20_000L
        } catch (_: Throwable) {
            20_000L
        } finally {
            runCatching { mmr.release() }
        }
    }

    private suspend fun decodeInlineAudioBytes(
        cacheDir: File,
        bytes: ByteArray,
        mime: String,
    ): StereoAudioBuffer? {
        if (mime.contains("l16") || mime.contains("pcm")) {
            val rateMatch = Regex("rate=(\\d+)").find(mime)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val sr = (rateMatch ?: 24000).coerceIn(8000, 48000)
            val sampleCount = bytes.size / 2
            if (sampleCount <= 0) return null
            val mono = FloatArray(sampleCount)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) {
                mono[i] = clamp(bb.short / 32768f, -1f, 1f)
            }
            val rawStereo = StereoAudioBuffer(mono, mono.copyOf(), sr)
            return if (sr == TARGET_SAMPLE_RATE) rawStereo else resampleStereo(rawStereo, TARGET_SAMPLE_RATE)
        }

        val ext = when {
            mime.contains("wav") -> "wav"
            mime.contains("ogg") || mime.contains("opus") -> "ogg"
            mime.contains("mp3") || mime.contains("mpeg") -> "mp3"
            else -> "wav"
        }
        val tmp = File(cacheDir, "gem_tts_${UUID.randomUUID()}.$ext")
        return try {
            BufferedOutputStream(FileOutputStream(tmp), IO_BUFFER_SIZE).use { out ->
                out.write(bytes)
                out.flush()
            }
            AudioDecoder.decodeFile(tmp)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun extractTextFromResponse(json: JSONObject?): String? {
        val parts = json
            ?.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val txt = parts.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() } ?: continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(txt)
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun parseJsonObjectSafe(raw: String): JSONObject? {
        val cleaned = raw
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
    }

    fun sanitizeFfmpegAudioFilterGraph(raw: String): String {
        val allowedFilters = setOf(
            "highpass", "lowpass", "equalizer", "afftdn", "acompressor",
            "alimiter", "loudnorm", "bass", "treble", "dynaudnorm",
            "aecho", "anequalizer", "deesser", "compand", "volume",
        )
        val tokens = raw
            .replace("\"", "")
            .replace(";", ",")
            .split(',')
            .map { it.trim() }
            .filter { token ->
                if (token.isEmpty()) return@filter false
                val name = token.substringBefore('=').lowercase(Locale.US)
                name in allowedFilters && token.all { c ->
                    c.isLetterOrDigit() || c in listOf('=', '.', '-', ':', '_', '+')
                }
            }
        return tokens.joinToString(",")
    }

    private fun buildFallbackFfmpegGraph(metrics: SmartMasterAnalysis): String {
        val filters = mutableListOf<String>()
        val hp = if (metrics.lowEnergyRatio > 0.40f) 100 else 75
        filters += "highpass=f=$hp"
        if (metrics.noiseFloorDb > -48f) {
            filters += "afftdn=nf=${metrics.noiseFloorDb.roundToInt().coerceIn(-45, -18)}"
        }
        if (metrics.lowEnergyRatio > 0.42f) {
            filters += "equalizer=f=220:width_type=h:width=160:g=-2.5"
        }
        val presenceGain = if (metrics.highEnergyRatio < 0.14f) 3.2f else 2.0f
        filters += "equalizer=f=2600:width_type=h:width=1200:g=${fmt(presenceGain)}"
        filters += "dynaudnorm=f=150:g=15:p=0.90:m=8"
        filters += "acompressor=threshold=-18dB:ratio=3.2:attack=8:release=85:makeup=2.5"
        filters += "loudnorm=I=-16:TP=-1.5:LRA=11"
        filters += "alimiter=limit=0.945:attack=5:release=50"
        return filters.joinToString(",")
    }

    private fun describeDialectArabic(id: String): String = when (id) {
        "iraqi" -> "العامية العراقية البغدادية الأصيلة"
        "saudi" -> "اللهجة السعودية الخليجية البيضاء"
        "egyptian" -> "اللهجة المصرية القاهرية الطبيعية"
        "syrian" -> "اللهجة الشامية السورية الطبيعية"
        "jordanian" -> "اللهجة الأردنية الطبيعية"
        "moroccan" -> "الدارجة المغربية الواضحة"
        "emirati" -> "اللهجة الإماراتية الخليجية"
        "tunisian" -> "اللهجة التونسية الواضحة"
        "en_us" -> "American English"
        "persian" -> "الفارسية المعيارية"
        "turkish" -> "التركية الطبيعية"
        else -> "العربية الفصحى السليمة"
    }

    private fun pickPrebuiltVoice(
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
    ): String {
        return when (gender) {
            TtsGender.MALE -> when (style) {
                TtsDeliveryStyle.DRAMATIC -> "Charon"
                TtsDeliveryStyle.RADIO_ANCHOR -> if (dialect.id == "iraqi" || dialect.id == "saudi") "Orus" else "Charon"
                TtsDeliveryStyle.ENERGETIC,
                TtsDeliveryStyle.ANGRY,
                -> "Fenrir"
                TtsDeliveryStyle.NATURAL_WARM -> "Orus"
                TtsDeliveryStyle.STORYTELLING -> "Charon"
                else -> "Puck"
            }
            TtsGender.FEMALE -> when (style) {
                TtsDeliveryStyle.SAD,
                TtsDeliveryStyle.STORYTELLING,
                -> "Leda"
                TtsDeliveryStyle.DRAMATIC -> "Zephyr"
                TtsDeliveryStyle.RADIO_ANCHOR,
                TtsDeliveryStyle.ENERGETIC,
                -> "Kore"
                else -> "Aoede"
            }
        }
    }

    private fun pickCharacterPrebuiltVoice(
        preset: VoiceCharacterPreset?,
        pitchSemitones: Float,
        formantShift: Float,
    ): String {
        val id = preset?.id
        return when {
            id == "male_luxury" -> "Charon"
            id == "male_calm" -> "Orus"
            id == "female_soft" -> "Leda"
            id == "female_balanced" -> "Aoede"
            id == "child" -> "Puck"
            pitchSemitones <= -2.0f || formantShift <= 0.92f -> "Charon"
            pitchSemitones >= 2.0f || formantShift >= 1.12f -> "Aoede"
            else -> "Orus"
        }
    }

    private fun buildDialectInstruction(
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        persona: TtsVoicePersona? = null,
        acousticMode: TtsPersonaAcousticMode = TtsPersonaAcousticMode.NATURAL,
    ): String {
        val dialectLine = when (dialect.id) {
            "iraqi" -> "تحدث بلكنة عراقية بغدادية أصيلة وطبيعية تماماً (كأنك مذيع أو متحدث عراقي حقيقي)"
            "saudi" -> "تحدث بلكنة سعودية نجدية/خليجية طبيعية ودافئة"
            "egyptian" -> "تحدث بلكنة مصرية قاهرية دافئة وطبيعية جداً"
            "syrian" -> "تحدث بلكنة شامية سورية دمشقية طبيعية ومريحة"
            "jordanian" -> "تحدث بلكنة أردنية أصيلة وواضحة"
            "moroccan" -> "تحدث بالدارجة المغربية الأصيلة الواضحة"
            "emirati" -> "تحدث بلكنة إماراتية خليجية راقية وطبيعية"
            "tunisian" -> "تحدث باللهجة التونسية الطبيعية الواضحة"
            "en_us" -> "Speak in natural, warm conversational American English"
            "persian" -> "به زبان فارسی معیار کاملاً طبیعی، گرم و انسانی صحبت کن"
            "turkish" -> "Doğal, akıcı ve sıcak bir Türkçe ile konuş"
            else -> "تحدث بالعربية الفصحى السليمة بنطق بشري دافئ ومخارج حروف واضحة"
        }
        val emotionLine = when (style) {
            TtsDeliveryStyle.NATURAL_WARM -> "بأسلوب بشري دافئ ومريح وطبيعي جداً مع تنفّس طبيعي بين الجمل"
            TtsDeliveryStyle.ENERGETIC -> "بنبرة حيوية ومتحمسة ومشرقة"
            TtsDeliveryStyle.SAD -> "بنبرة حزينة هادئة ومؤثرة وإنسانية"
            TtsDeliveryStyle.ANGRY -> "بنبرة حازمة وقوية وانفعال واضح"
            TtsDeliveryStyle.DRAMATIC -> "بأداء درامي فخم وعميق كالمعلقين السينمائيين"
            TtsDeliveryStyle.RADIO_ANCHOR -> "بصوت مذيع إذاعي محترف وواثق ورصين"
            TtsDeliveryStyle.STORYTELLING -> "بأسلوب سرد قصصي شائق وهادئ يجذب المستمع"
        }
        val acousticGuidance = when (acousticMode) {
            TtsPersonaAcousticMode.DOCUMENTARY ->
                "أسلوب الإلقاء الوثائقي: تمهّل بثقة مع وقفات تنفس درامية واضحة وعزم عميق في الصوت."
            TtsPersonaAcousticMode.PODCAST ->
                "أسلوب البودكاست: حديث عفوي دافئ ومباشر كأنك تتكلم أمام مايكروفون استوديو قريب من المستمع."
            TtsPersonaAcousticMode.HALL_ECHO ->
                "أسلوب صدى القاعة: إلقاء رصين ومهيب بوقفات متوازنة تبرز رحابة المكان."
            TtsPersonaAcousticMode.NATURAL ->
                "أسلوب إلقاء طبيعي نقي: انسيابية بشرية هادئة بدون مبالغة أو تصنّع."
        }
        val paceHint = when {
            speechRate < 0.85f -> "بوتيرة هادئة ومتمهلة"
            speechRate > 1.15f -> "بوتيرة سريعة ورشيقة"
            else -> "بوتيرة كلامية طبيعية معتدلة"
        }
        val voiceType = if (gender == TtsGender.MALE) "صوت رجل بشري" else "صوت امرأة بشرية"
        val personaLine = persona?.geminiDirectionPromptAr?.let { "\nالتوجيه الخاص بالشخصية: $it" }.orEmpty()
        return "اقرأ النص التالي بصوت $voiceType حقيقي 100% بدون أي طابع آلي أو تقطيع: $dialectLine، $emotionLine، $paceHint.\n$acousticGuidance$personaLine\nانطق النص المكتوب حرفياً فقط واحترم التشكيل وعلامات الوقف."
    }

    private fun buildVoiceCharacterCoaching(
        preset: VoiceCharacterPreset?,
        targetPersona: TtsVoicePersona?,
        acousticMode: TtsPersonaAcousticMode,
        targetLabel: String,
        pitchSemitones: Float,
        formantShift: Float,
        detectedDialect: String,
        detectedEmotion: String,
    ): String {
        val personaDesc = targetPersona?.geminiDirectionPromptAr ?: when (preset?.id) {
            "male_luxury" -> "بصوت رجل وقور، جهوري فخم وعميق يشبه صوت المعلقين السينمائيين"
            "male_calm" -> "بصوت شاب هادئ، متزن ودافئ"
            "female_soft" -> "بصوت أنثوي ناعم، رقيق وواضح"
            "female_balanced" -> "بصوت امرأة واثق ومتوازن كالمذيعة المحترفة"
            "child" -> "بصوت طفل طبيعي لطيف وبريء"
            else -> when {
                pitchSemitones <= -2.5f -> "بصوت عميق فخم ومؤثر ($targetLabel)"
                pitchSemitones >= 2.5f -> "بصوت حاد نسبياً وواضح ($targetLabel)"
                formantShift < 0.9f -> "بنبرة ذكورية رصينة ($targetLabel)"
                formantShift > 1.1f -> "بنبرة أنثوية طبيعية ($targetLabel)"
                else -> "بشخصية صوتية طبيعية ($targetLabel)"
            }
        }
        val acousticHint = when (acousticMode) {
            TtsPersonaAcousticMode.DOCUMENTARY -> "بإلقاء وثائقي عميق مع وقفات درامية مدروسة"
            TtsPersonaAcousticMode.PODCAST -> "بأسلوب بودكاست حميمي قريب من المايكروفون"
            TtsPersonaAcousticMode.HALL_ECHO -> "برحابة قاعة فخمة وصدى متوازن"
            TtsPersonaAcousticMode.NATURAL -> "بنقاء صوتي طبيعي وواضح"
        }
        return "تقمص الشخصية التالية بالكامل وبصوت بشري حقيقي 100%: $personaDesc ($acousticHint)، مع الحفاظ على اللهجة ($detectedDialect) والانفعال ($detectedEmotion) دون أي طابع آلي."
    }

    private data class JsonPostOutcome(val json: JSONObject?, val error: String? = null)

    private fun postGeminiJson(
        urlStr: String,
        bodyJson: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        language: AppLanguage,
    ): JsonPostOutcome {
        val apiKeyParam = if (urlStr.contains("?key=")) {
            urlStr.substringAfter("?key=").substringBefore("&")
        } else {
            ""
        }
        if (urlStr.contains("?key=") && apiKeyParam.isBlank()) {
            return JsonPostOutcome(null, null)
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                NetworkImmunityManager.enforceTls12Plus(this)
                requestMethod = "POST"
                doOutput = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { out ->
                out.write(bodyJson.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JsonPostOutcome(JSONObject(text), null)
            } else {
                val errBody = runCatching {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
                AppDiagnosticLog.logRecoverable(
                    toolName = "SonixUnifiedAIEngine",
                    titleAr = "استجابة HTTP غير متوقعة من خادم Gemini ($code)",
                    titleEn = "Gemini API HTTP $code",
                    causeAr = errBody.take(300).ifBlank { "HTTP $code" },
                    causeEn = errBody.take(300).ifBlank { "HTTP $code" },
                    errorCode = "GEMINI_HTTP_$code",
                )
                JsonPostOutcome(null, translateHttpError(language, code, errBody))
            }
        } catch (t: UnknownHostException) {
            JsonPostOutcome(
                null,
                if (language == AppLanguage.EN) "No internet connection detected (DNS unreachable)."
                else "تعذر الوصول إلى خادم الذكاء الاصطناعي، يرجى التحقق من اتصال الإنترنت.",
            )
        } catch (t: SocketTimeoutException) {
            JsonPostOutcome(
                null,
                if (language == AppLanguage.EN) "AI server timed out. Please retry with a stronger connection."
                else "انتهت مهلة الاتصال بخادم الذكاء الاصطناعي، يرجى المحاولة مرة أخرى.",
            )
        } catch (t: Throwable) {
            JsonPostOutcome(
                null,
                friendlyNetworkError(language, "${t.javaClass.simpleName}: ${t.message.orEmpty()}"),
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun translateHttpError(language: AppLanguage, code: Int, body: String): String {
        return when (code) {
            400 -> if (language == AppLanguage.EN) "Invalid request format sent to AI service." else "تعذر فهم تنسيق الطلب المرسل لخادم الذكاء الاصطناعي."
            401, 403 -> if (language == AppLanguage.EN) "AI service access was rejected by project policy ($code)." else "تم رفض الوصول من خادم الذكاء الاصطناعي ($code)، تحقق من صلاحيات المشروع."
            429 -> if (language == AppLanguage.EN) "AI service is currently busy (rate limit). Wait a moment and retry." else "الخادم مشغول حالياً بكثرة الطلبات (429)، انتظر لحظة وحاول مجدداً."
            404 -> if (language == AppLanguage.EN) "AI model endpoint temporarily unavailable (404)." else "نقطة نموذج الذكاء الاصطناعي غير متوفرة حالياً (404)."
            in 500..599 -> if (language == AppLanguage.EN) "Temporary Google AI server error ($code)." else "عطل مؤقت في خادم الذكاء الاصطناعي ($code)، حاول مجدداً."
            else -> if (language == AppLanguage.EN) "Cloud AI error HTTP $code ${body.take(80)}" else "تعذر الاتصال بخادم الذكاء الاصطناعي (رمز $code)"
        }
    }

    private fun friendlyNetworkError(language: AppLanguage, detail: String): String {
        return if (language == AppLanguage.EN) {
            "Cloud AI network error: $detail"
        } else {
            "تعذر الاتصال بخدمة الذكاء الاصطناعي عبر الإنترنت: $detail"
        }
    }

    private fun fmt(value: Float): String = "%.2f".format(Locale.US, value)
}
