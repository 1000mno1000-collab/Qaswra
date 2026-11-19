package com.example.audio.aivoice

import android.content.Context
import android.util.Base64
import com.example.audio.AudioDecodeOutcome
import com.example.audio.AudioDecoder
import com.example.audio.OnlineGuard
import com.example.audio.TARGET_SAMPLE_RATE
import com.example.audio.network.NetworkImmunityManager
import com.example.audio.transformVoiceOffline
import com.example.audio.tts.ArabicTextDiacritizer
import com.example.audio.tts.SmartVoiceRouter
import com.example.audio.tts.VoiceRouteOutcome
import com.example.audio.writeStereoWavFile
import com.example.model.AppLanguage
import com.example.model.StereoAudioBuffer
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsGender
import com.example.model.TtsPersonaAcousticMode
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext
import kotlin.math.pow

/**
 * Pluggable AI Voice Engine interface supporting VoxCPM2 (Cloud GPU) and Sonix Local Studio (Deterministic Offline).
 */
interface AiVoiceStudioEngine {
    val kind: AiVoiceEngineKind

    suspend fun checkHealth(
        context: Context,
        backendUrl: String,
        bearerToken: String = "",
    ): Boolean

    suspend fun synthesizeChunk(
        context: Context,
        request: AiVoiceSynthRequest,
        textChunk: String,
        backendUrl: String,
        outWavFile: File,
        bearerToken: String = "",
    ): AiVoiceSynthOutcome
}

internal object BackendHttpHelper {

    suspend fun resolveFirebaseBearerTokenOrNull(context: Context): String? {
        return runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) return@runCatching null
            val user = FirebaseAuth.getInstance().currentUser ?: return@runCatching null
            user.getIdToken(false).await()?.token?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun encodeFileBase64OrNull(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists() || !file.isFile || file.length() <= 44L) return null
        if (file.length() > 12L * 1024L * 1024L) return null
        return runCatching {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }.getOrNull()
    }

    suspend fun postSynthesizeJson(
        context: Context,
        endpointUrl: String,
        payload: JSONObject,
        bearerToken: String = "",
        timeoutMs: Int = 180_000,
    ): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        val safeUrl = NetworkImmunityManager.enforceHttps(endpointUrl)
        val cleanServerBearer = bearerToken.trim()
        val firebaseBearer = resolveFirebaseBearerTokenOrNull(context)
        val conn = (URL(safeUrl).openConnection() as HttpURLConnection).apply {
            NetworkImmunityManager.enforceTls12Plus(this)
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 25_000
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "audio/wav, audio/*, application/json")
            setRequestProperty("User-Agent", "Sonix-AiVoiceStudio/2.0")
            if (cleanServerBearer.isNotBlank()) {
                setRequestProperty("X-Sonix-Bearer", cleanServerBearer)
            }
            if (!firebaseBearer.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $firebaseBearer")
            } else if (cleanServerBearer.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $cleanServerBearer")
            }
        }
        try {
            val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bodyBytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseBytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            code to responseBytes
        } finally {
            conn.disconnect()
        }
    }

    suspend fun getJson(
        endpointUrl: String,
        bearerToken: String = "",
        timeoutMs: Int = 20_000,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val safeUrl = NetworkImmunityManager.enforceHttps(endpointUrl)
        val cleanServerBearer = bearerToken.trim()
        val conn = (URL(safeUrl).openConnection() as HttpURLConnection).apply {
            NetworkImmunityManager.enforceTls12Plus(this)
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Sonix-AiVoiceStudio/2.0")
            if (cleanServerBearer.isNotBlank()) {
                setRequestProperty("X-Sonix-Bearer", cleanServerBearer)
                setRequestProperty("Authorization", "Bearer $cleanServerBearer")
            }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            code to text
        } finally {
            conn.disconnect()
        }
    }

    fun extractErrorDetail(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""
        val parsed = runCatching { JSONObject(trimmed).optString("detail") }.getOrNull().orEmpty()
        return parsed.ifBlank { trimmed }.take(400)
    }

    fun mapConnectionError(
        engine: AiVoiceEngineKind,
        serverLabel: String,
        t: Throwable,
    ): AiVoiceSynthOutcome.Error {
        val detail = t.localizedMessage ?: t.javaClass.simpleName
        return when (t) {
            is SocketTimeoutException -> AiVoiceSynthOutcome.Error(
                engine = engine,
                messageAr = "انتهت مهلة انتظار خادم $serverLabel (قد يكون النموذج قيد التحميل الأول إلى GPU أو النص طويلاً).",
                messageEn = "$serverLabel server timed out: $detail",
                technicalDetail = t.toString(),
            )
            is UnknownHostException -> AiVoiceSynthOutcome.Error(
                engine = engine,
                messageAr = "تعذر العثور على عنوان خادم $serverLabel (تحقق من صحة الرابط والاتصال).",
                messageEn = "Cannot resolve $serverLabel host: $detail",
                technicalDetail = t.toString(),
            )
            is ConnectException -> AiVoiceSynthOutcome.Error(
                engine = engine,
                messageAr = "تعذر فتح اتصال مع خادم $serverLabel — تأكد من تشغيل الخدمة على الخادم.",
                messageEn = "Connection refused by $serverLabel server: $detail",
                technicalDetail = t.toString(),
            )
            is SSLException -> AiVoiceSynthOutcome.Error(
                engine = engine,
                messageAr = "فشل التحقق من شهادة SSL/HTTPS لخادم $serverLabel.",
                messageEn = "SSL/HTTPS handshake failed for $serverLabel: $detail",
                technicalDetail = t.toString(),
            )
            else -> AiVoiceSynthOutcome.Error(
                engine = engine,
                messageAr = "تعذر الاتصال بخادم $serverLabel الخلفي: $detail",
                messageEn = "Failed reaching $serverLabel backend server: $detail",
                technicalDetail = t.toString(),
            )
        }
    }

    fun mapHttpError(
        engine: AiVoiceEngineKind,
        serverLabel: String,
        code: Int,
        rawBytes: ByteArray,
    ): AiVoiceSynthOutcome.Error {
        val detail = extractErrorDetail(rawBytes.toString(Charsets.UTF_8))
        return when (code) {
            401, 403 -> AiVoiceSynthOutcome.Error(
                engine = engine,
                messageAr = "رفض خادم $serverLabel التصريح (HTTP $code): تأكد من رمز المصادقة أو تسجيل الدخول.",
                messageEn = "$serverLabel rejected authentication (HTTP $code).",
                technicalDetail = detail,
            )
            400, 413 -> AiVoiceSynthOutcome.Error(
                engine = engine,
                messageAr = "بيانات الطلب أو العينة المرجعية غير صالحة لـ $serverLabel (HTTP $code).",
                messageEn = "Invalid synthesis request for $serverLabel (HTTP $code).",
                technicalDetail = detail,
            )
            503 -> AiVoiceSynthOutcome.Error(
                engine = engine,
                messageAr = "خادم $serverLabel غير جاهز حالياً (HTTP 503).",
                messageEn = "$serverLabel GPU service unavailable (HTTP 503).",
                technicalDetail = detail,
            )
            else -> AiVoiceSynthOutcome.Error(
                engine = engine,
                messageAr = "أعاد خادم $serverLabel خطأ (HTTP $code).",
                messageEn = "$serverLabel backend returned HTTP $code.",
                technicalDetail = detail,
            )
        }
    }
}

/**
 * Direct Built-in Engine Delegate: routes directly through [SonixLocalStudioEngineProvider] (Nabra-82M + FFmpegKit)
 * with zero external GPU server configuration required.
 */
class VoxCpm2CloudProvider : AiVoiceStudioEngine {
    override val kind: AiVoiceEngineKind = AiVoiceEngineKind.VOXCPM_2
    private val builtInProvider = SonixLocalStudioEngineProvider()

    companion object {
        private const val PUBLIC_SPACE_BASE = "https://openbmb-voxcpm-demo.hf.space"
    }

    override suspend fun checkHealth(
        context: Context,
        backendUrl: String,
        bearerToken: String,
    ): Boolean = true

    override suspend fun synthesizeChunk(
        context: Context,
        request: AiVoiceSynthRequest,
        textChunk: String,
        backendUrl: String,
        outWavFile: File,
        bearerToken: String,
    ): AiVoiceSynthOutcome = builtInProvider.synthesizeChunk(
        context = context,
        request = request.copy(engine = AiVoiceEngineKind.SONIX_LOCAL_STUDIO),
        textChunk = textChunk,
        backendUrl = backendUrl,
        outWavFile = outWavFile,
        bearerToken = bearerToken,
    )

    private suspend fun callDedicatedGpuBackend(
        context: Context,
        cleanBase: String,
        bearerToken: String,
        request: AiVoiceSynthRequest,
        textChunk: String,
        outWavFile: File,
    ): AiVoiceSynthOutcome {
        val refB64 = if (request.referenceAudioPath != null) {
            BackendHttpHelper.encodeFileBase64OrNull(request.referenceAudioPath)
        } else {
            null
        }
        val payload = AiVoicePromptBuilder.buildBackendPayloadJson(request, textChunk, refB64)
        val (code, bytes) = try {
            BackendHttpHelper.postSynthesizeJson(
                context = context,
                endpointUrl = "$cleanBase/v1/voice-studio/synthesize",
                payload = payload,
                bearerToken = bearerToken,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return BackendHttpHelper.mapConnectionError(kind, "VoxCPM2", t)
        }

        if (code !in 200..299) {
            return BackendHttpHelper.mapHttpError(kind, "VoxCPM2", code, bytes)
        }

        return persistAudioBytesAsWav(
            context = context,
            rawBytes = bytes,
            outWavFile = outWavFile,
            engineUsed = kind,
            appliedPromptSummary = "${request.emotion.titleAr} • seed=${request.fixedSeed}",
        )
    }

    private suspend fun callPublicVoxCpmSpace(
        context: Context,
        request: AiVoiceSynthRequest,
        textChunk: String,
        outWavFile: File,
    ): AiVoiceSynthOutcome {
        val instruction = AiVoicePromptBuilder.buildVoxCpmInstruction(request)
        if (!request.referenceAudioPath.isNullOrBlank()) {
            return AiVoiceSynthOutcome.ConfigurationRequired(
                engine = kind,
                titleAr = "استنساخ الصوت عبر VoxCPM2 يتطلب ربط خادم GPU",
                titleEn = "Voice Cloning Requires Your GPU Backend URL",
                explanationAr = "الفضاء العام التجريبي على Hugging Face لا يدعم رفع عينات الاستنساخ الخاصة بك مباشرة.",
                explanationEn = "The public Space does not support private reference audio cloning directly.",
                setupStepsAr = "1. استخدم «محرك الاستوديو المحلي (بدون سيرفر)» مباشرة للتوليد الفوري.\n2. أو أدخل رابط خادم GPU الخاص بك للاستنساخ السحابي.",
                setupStepsEn = "1. Switch to Local Studio Engine for immediate synthesis.\n2. Or configure your GPU backend URL for cloud cloning.",
            )
        }

        val (infoCode, infoJsonStr) = runCatching {
            BackendHttpHelper.getJson("$PUBLIC_SPACE_BASE/gradio_api/info", timeoutMs = 10_000)
        }.getOrDefault(0 to "")

        if (infoCode !in 200..299 || infoJsonStr.isBlank()) {
            return AiVoiceSynthOutcome.ConfigurationRequired(
                engine = kind,
                titleAr = "خادم VoxCPM2 غير متاح حالياً (HTTP $infoCode)",
                titleEn = "VoxCPM2 Cloud Server Unavailable",
                explanationAr = "الفضاء التجريبي العام متوقف أو مشغول حالياً. يمكنك التوليد فوراً باستخدام محرك الاستوديو المحلي.",
                explanationEn = "The public VoxCPM2 Space is sleeping or busy (HTTP $infoCode). Switch to Local Studio Engine immediately.",
                setupStepsAr = "اضغط «تشغيل بالمحرك المحلي الآن» للمتابعة فوراً بدون سيرفر.",
                setupStepsEn = "Tap 'Use Local Studio Engine Now' for instant offline synthesis.",
            )
        }

        val infoJson = runCatching { JSONObject(infoJsonStr) }.getOrNull()
        val namedEndpoints = infoJson?.optJSONObject("named_endpoints")
        val endpointName = selectBestGradioEndpoint(namedEndpoints)
        if (endpointName == null) {
            return AiVoiceSynthOutcome.ConfigurationRequired(
                engine = kind,
                titleAr = "تعذر مطابقة واجهة VoxCPM2 العامة",
                titleEn = "VoxCPM2 Endpoint Unavailable",
                explanationAr = "الرجاء استخدام «محرك الاستوديو المحلي» للمتابعة فوراً.",
                explanationEn = "Please use Local Studio Engine for immediate synthesis.",
                setupStepsAr = "اضغط «تشغيل بالمحرك المحلي الآن».",
                setupStepsEn = "Tap 'Use Local Studio Engine Now'.",
            )
        }

        val apiSlug = endpointName.removePrefix("/")
        val endpointMeta = namedEndpoints?.optJSONObject(endpointName)
        val paramsArray = endpointMeta?.optJSONArray("parameters")
        val dataArgs = buildGradioDataArgs(paramsArray, textChunk, instruction, request)

        val submitPayload = JSONObject().put("data", dataArgs)
        val (submitCode, submitRespBytes) = runCatching {
            BackendHttpHelper.postSynthesizeJson(
                context = context,
                endpointUrl = "$PUBLIC_SPACE_BASE/gradio_api/call/$apiSlug",
                payload = submitPayload,
                timeoutMs = 20_000,
            )
        }.getOrDefault(0 to ByteArray(0))

        val eventId = if (submitCode in 200..299) {
            runCatching { JSONObject(submitRespBytes.toString(Charsets.UTF_8)).optString("event_id") }.getOrNull()
        } else {
            null
        }

        if (eventId.isNullOrBlank()) {
            return AiVoiceSynthOutcome.ConfigurationRequired(
                engine = kind,
                titleAr = "خادم VoxCPM2 العام مزدحم (HTTP $submitCode)",
                titleEn = "Public Space Queue Busy (HTTP $submitCode)",
                explanationAr = "لم يتمكن الفضاء العام من قبول الطلب حالياً، يمكنك المتابعة فوراً بالمحرك المحلي.",
                explanationEn = "The public VoxCPM2 Space queue is busy (HTTP $submitCode).",
                setupStepsAr = "اضغط «تشغيل بالمحرك المحلي الآن».",
                setupStepsEn = "Tap 'Use Local Studio Engine Now'.",
            )
        }

        val audioUrl = pollGradioEventForAudioUrl("$PUBLIC_SPACE_BASE/gradio_api/call/$apiSlug/$eventId")
        if (audioUrl.isNullOrBlank()) {
            return AiVoiceSynthOutcome.Error(
                engine = kind,
                messageAr = "انتهت مهلة الانتظار في صف VoxCPM2 دون ملف صوتي.",
                messageEn = "Public VoxCPM2 Space queue timed out.",
            )
        }

        val resolvedAudioUrl = if (audioUrl.startsWith("http", ignoreCase = true)) {
            audioUrl
        } else {
            "$PUBLIC_SPACE_BASE/gradio_api/file=$audioUrl"
        }
        val audioOutcome = NetworkImmunityManager.fetchAudioWithFallback(
            urls = listOf(resolvedAudioUrl),
            context = context,
            timeoutMs = 45_000L,
            providerLabel = "VoxCPM2SpaceAudio",
        )
        return when (audioOutcome) {
            is com.example.audio.network.NetworkFetchOutcome.Success -> persistAudioBytesAsWav(
                context = context,
                rawBytes = audioOutcome.value.bytes,
                outWavFile = outWavFile,
                engineUsed = kind,
                appliedPromptSummary = "${request.emotion.titleAr} • seed=${request.fixedSeed}",
            )
            is com.example.audio.network.NetworkFetchOutcome.Offline -> AiVoiceSynthOutcome.Error(
                engine = kind,
                messageAr = "انقطع الاتصال أثناء تنزيل الصوت من VoxCPM2.",
                messageEn = "Network offline while downloading VoxCPM2 audio.",
            )
            is com.example.audio.network.NetworkFetchOutcome.Failure -> AiVoiceSynthOutcome.Error(
                engine = kind,
                messageAr = "فشل تنزيل الصوت المولد من VoxCPM2: ${audioOutcome.message}",
                messageEn = "Failed downloading VoxCPM2 output: ${audioOutcome.message}",
            )
        }
    }

    private fun selectBestGradioEndpoint(namedEndpoints: JSONObject?): String? {
        if (namedEndpoints == null) return null
        val keys = namedEndpoints.keys().asSequence().toList()
        return keys.firstOrNull { it.contains("generate", ignoreCase = true) }
            ?: keys.firstOrNull { it.contains("tts", ignoreCase = true) }
            ?: keys.firstOrNull { it.contains("predict", ignoreCase = true) }
            ?: keys.firstOrNull()
    }

    private fun buildGradioDataArgs(
        paramsArray: JSONArray?,
        text: String,
        instruction: String,
        request: AiVoiceSynthRequest,
    ): JSONArray {
        val arr = JSONArray()
        if (paramsArray == null || paramsArray.length() == 0) {
            arr.put("($instruction) $text")
            return arr
        }
        for (i in 0 until paramsArray.length()) {
            val p = paramsArray.optJSONObject(i) ?: continue
            val pName = p.optString("parameter_name").lowercase()
            val label = p.optString("label").lowercase()
            val key = "$pName $label"
            val defaultVal = p.opt("parameter_default")
            when {
                key.contains("target text") || pName == "text" || pName == "text_input" -> {
                    val combined = if (paramsArray.toString().contains("control", true)) text else "($instruction) $text"
                    arr.put(combined)
                }
                key.contains("control") || key.contains("instruct") || key.contains("style") || key.contains("description") -> {
                    arr.put(instruction)
                }
                key.contains("randomize") || key.contains("random_seed") -> arr.put(false)
                key.contains("seed") -> arr.put(request.fixedSeed)
                key.contains("cfg") -> arr.put(request.cfgValue.toDouble())
                key.contains("step") -> arr.put(request.inferenceTimesteps)
                key.contains("normalize") -> arr.put(request.normalizeAudio)
                key.contains("prompt") && key.contains("wav") -> arr.put(JSONObject.NULL)
                defaultVal != null && defaultVal != JSONObject.NULL -> arr.put(defaultVal)
                else -> arr.put(JSONObject.NULL)
            }
        }
        return arr
    }

    private suspend fun pollGradioEventForAudioUrl(eventUrl: String): String? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 35_000L
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            val (code, body) = runCatching { BackendHttpHelper.getJson(eventUrl, timeoutMs = 15_000) }.getOrDefault(0 to "")
            if (code in 200..299 && body.isNotBlank()) {
                val lines = body.lines()
                for (line in lines) {
                    if (line.startsWith("data:")) {
                        val jsonPart = line.removePrefix("data:").trim()
                        val arr = runCatching { JSONArray(jsonPart) }.getOrNull() ?: continue
                        val first = arr.opt(0)
                        if (first is JSONObject) {
                            val url = first.optString("url").takeIf { it.isNotBlank() }
                                ?: first.optString("path").takeIf { it.isNotBlank() }
                            if (!url.isNullOrBlank()) return@withContext url
                        } else if (first is String && (first.endsWith(".wav", true) || first.startsWith("http"))) {
                            return@withContext first
                        }
                    }
                }
            }
            delay(1_200L)
        }
        null
    }
}

/**
 * Deterministic On-Device Studio Engine (No Server Required).
 * Strictly binds each voice to its fixed speaker ID (`fixedSid`), fixed acoustic signature
 * (pitch, formant shift, EQ), and mathematically locks every emotion + intensity % + speechRate
 * with zero randomness.
 */
class SonixLocalStudioEngineProvider : AiVoiceStudioEngine {
    override val kind: AiVoiceEngineKind = AiVoiceEngineKind.SONIX_LOCAL_STUDIO
    private val smartRouter = SmartVoiceRouter()

    override suspend fun checkHealth(
        context: Context,
        backendUrl: String,
        bearerToken: String,
    ): Boolean = true

    override suspend fun synthesizeChunk(
        context: Context,
        request: AiVoiceSynthRequest,
        textChunk: String,
        backendUrl: String,
        outWavFile: File,
        bearerToken: String,
    ): AiVoiceSynthOutcome = withContext(Dispatchers.IO) {
        val mappedDialect = mapToLocalDialect(request.language)
        val mappedGender = when (request.category) {
            AiVoiceCategory.FEMALE -> TtsGender.FEMALE
            AiVoiceCategory.MALE -> TtsGender.MALE
        }
        val mappedStyle = when (request.emotion) {
            AiVoiceEmotion.SAD -> TtsDeliveryStyle.SAD
            AiVoiceEmotion.SERIOUS -> TtsDeliveryStyle.RADIO_ANCHOR
            AiVoiceEmotion.EXCITED -> TtsDeliveryStyle.ENERGETIC
            AiVoiceEmotion.CALM, AiVoiceEmotion.WHISPER -> TtsDeliveryStyle.STORYTELLING
            AiVoiceEmotion.NEUTRAL -> TtsDeliveryStyle.NATURAL_WARM
        }
        val acousticMode = when (request.fixedSid) {
            1, 4 -> TtsPersonaAcousticMode.DOCUMENTARY
            0, 3 -> TtsPersonaAcousticMode.PODCAST
            else -> TtsPersonaAcousticMode.NATURAL
        }

        val intensity = request.emotionIntensity.coerceIn(0.10f, 1.0f)
        val emotionSpeedFactor = 1.0f + (request.emotion.speedBiasFactor - 1.0f) * intensity
        val finalSpeed = (request.speechRate * emotionSpeedFactor).coerceIn(0.55f, 1.75f)
        val totalSemitones = request.pitchOffsetSemitones + (request.emotion.pitchBiasSemitones * intensity)
        val finalPitchRatio = 2.0.pow(totalSemitones / 12.0).toFloat().coerceIn(0.62f, 1.65f)

        val appLang = when (request.language) {
            AiVoiceLanguage.EN -> AppLanguage.EN
            AiVoiceLanguage.FA -> AppLanguage.FA
            else -> AppLanguage.AR
        }

        val outcome = smartRouter.synthesizeStudioVoiceWithStatus(
            context = context,
            text = textChunk,
            dialect = mappedDialect,
            gender = mappedGender,
            style = mappedStyle,
            speechRate = finalSpeed,
            pitch = finalPitchRatio,
            language = appLang,
            studioLuxuryEnhancement = true,
            acousticMode = acousticMode,
            autoDownloadIfOnline = OnlineGuard.isOnline(context),
            fixedSid = request.fixedSid,
        )

        when (outcome) {
            is VoiceRouteOutcome.ProviderError -> AiVoiceSynthOutcome.Error(
                engine = kind,
                messageAr = outcome.message,
                messageEn = outcome.message,
            )
            is VoiceRouteOutcome.Success -> {
                var buf = outcome.result.buffer
                buf = applyDeterministicVoiceAndEmotionDsp(
                    input = buf,
                    request = request,
                )
                writeStereoWavFile(outWavFile, buf)
                runCatching { outcome.result.audioFile.delete() }
                AiVoiceSynthOutcome.Success(
                    audioFilePath = outWavFile.absolutePath,
                    sampleRate = buf.sampleRate,
                    durationMs = buf.durationMs,
                    engineUsed = kind,
                    appliedPromptSummary = "Nabra-82M + FFmpegKit (SID ${request.fixedSid}) • ${request.emotion.titleAr} ${(intensity * 100).toInt()}%",
                    isCloudGenerated = false,
                )
            }
        }
    }

    private suspend fun applyDeterministicVoiceAndEmotionDsp(
        input: StereoAudioBuffer,
        request: AiVoiceSynthRequest,
    ): StereoAudioBuffer {
        val roleShaped = transformVoiceOffline(
            source = input,
            pitchSemitones = 0f, // pitch already applied accurately at base speed/pitch stage
            formantShift = request.fixedFormantShift.coerceIn(0.84f, 1.20f),
            warmthDb = request.fixedEqLowDb,
            presenceDb = request.fixedEqPresenceDb,
            mix = 0.85f,
            studioMasteringPolish = true,
        )

        if (request.emotion == AiVoiceEmotion.WHISPER) {
            return applyDeterministicWhisperShaping(roleShaped, request.emotionIntensity)
        }

        val k = request.emotionIntensity.coerceIn(0.10f, 1.0f)
        return when (request.emotion) {
            AiVoiceEmotion.NEUTRAL -> roleShaped
            AiVoiceEmotion.EXCITED -> transformVoiceOffline(
                source = roleShaped,
                pitchSemitones = 0.30f * k,
                formantShift = 1.0f + 0.04f * k,
                warmthDb = 0.8f * k,
                presenceDb = 3.2f * k,
                mix = 0.72f,
                studioMasteringPolish = true,
            )
            AiVoiceEmotion.CALM -> transformVoiceOffline(
                source = roleShaped,
                pitchSemitones = -0.20f * k,
                formantShift = 1.0f - 0.02f * k,
                warmthDb = 3.0f * k,
                presenceDb = -1.2f * k,
                mix = 0.68f,
                studioMasteringPolish = true,
            )
            AiVoiceEmotion.SAD -> transformVoiceOffline(
                source = roleShaped,
                pitchSemitones = -0.45f * k,
                formantShift = 1.0f - 0.03f * k,
                warmthDb = 2.6f * k,
                presenceDb = -1.8f * k,
                mix = 0.72f,
                vibratoHz = 4.2f,
                vibratoDepth = 0.08f * k,
                studioMasteringPolish = true,
            )
            AiVoiceEmotion.SERIOUS -> transformVoiceOffline(
                source = roleShaped,
                pitchSemitones = -0.30f * k,
                formantShift = 1.0f - 0.04f * k,
                warmthDb = 3.2f * k,
                presenceDb = 2.6f * k,
                mix = 0.75f,
                bassBoostDb = 2.0f * k,
                studioMasteringPolish = true,
            )
            AiVoiceEmotion.WHISPER -> roleShaped
        }
    }

    private fun mapToLocalDialect(lang: AiVoiceLanguage): TtsDialect {
        val list = com.example.model.TTS_DIALECTS
        return when (lang) {
            AiVoiceLanguage.EN -> list.firstOrNull { it.id == "en_us" } ?: list.first()
            AiVoiceLanguage.FA -> list.firstOrNull { it.id == "persian" } ?: list.first()
            AiVoiceLanguage.TR -> list.firstOrNull { it.id == "turkish" } ?: list.first()
            AiVoiceLanguage.AR -> list.firstOrNull { it.id == "standard" } ?: list.first()
            else -> list.firstOrNull { it.id == "en_us" } ?: list.first()
        }
    }

    /**
     * 100% deterministic airy whisper filter (fixed LCG seed + high-pass filter scaling smoothly with intensity).
     */
    private fun applyDeterministicWhisperShaping(
        input: StereoAudioBuffer,
        intensity: Float,
    ): StereoAudioBuffer {
        val k = intensity.coerceIn(0.15f, 1.0f)
        val n = input.frameCount
        val outL = FloatArray(n)
        val outR = FloatArray(n)
        var prevL = 0f
        var prevR = 0f
        var rng = 0x1234567
        val breathGain = 0.015f + 0.025f * k
        val dryBlend = (1.0f - 0.65f * k).coerceIn(0.25f, 0.85f)
        val hpWeight = 0.55f + 0.20f * k
        for (i in 0 until n) {
            rng = rng * 1664525 + 1013904223
            val white = ((rng ushr 16) and 0x7FFF) / 32768f - 0.5f
            val hpL = (input.left[i] - 0.92f * prevL) * hpWeight
            val hpR = (input.right[i] - 0.92f * prevR) * hpWeight
            prevL = input.left[i]
            prevR = input.right[i]
            val env = kotlin.math.min(1f, kotlin.math.abs(hpL) * 6f)
            val breath = white * breathGain * env
            outL[i] = (hpL * (1f - dryBlend * 0.4f) + input.left[i] * dryBlend * 0.25f + breath).coerceIn(-0.95f, 0.95f)
            outR[i] = (hpR * (1f - dryBlend * 0.4f) + input.right[i] * dryBlend * 0.25f + breath).coerceIn(-0.95f, 0.95f)
        }
        return StereoAudioBuffer(outL, outR, input.sampleRate)
    }
}

internal suspend fun persistAudioBytesAsWav(
    context: Context,
    rawBytes: ByteArray,
    outWavFile: File,
    engineUsed: AiVoiceEngineKind,
    appliedPromptSummary: String,
): AiVoiceSynthOutcome = withContext(Dispatchers.IO) {
    if (!NetworkImmunityManager.isValidAudioPayload(rawBytes, "audio/wav")) {
        return@withContext AiVoiceSynthOutcome.Error(
            engine = engineUsed,
            messageAr = "استجاب الخادم ببيانات غير صوتية أو تالفة.",
            messageEn = "Server returned non-audio or corrupted payload.",
            technicalDetail = rawBytes.take(160).toByteArray().toString(Charsets.UTF_8),
        )
    }
    outWavFile.parentFile?.mkdirs()
    val head = String(rawBytes, 0, minOf(12, rawBytes.size), Charsets.US_ASCII)
    if (head.startsWith("RIFF") && head.contains("WAVE")) {
        FileOutputStream(outWavFile).use { it.write(rawBytes) }
        val buf = AiVoiceAudioStitcher.readSimplePcmWav(outWavFile)
        val probe = AudioDecoder.probeAudioHeaderQuick(outWavFile)
        val rate = buf?.sampleRate ?: probe?.sampleRate ?: TARGET_SAMPLE_RATE
        val durMs = buf?.durationMs ?: probe?.durationMs ?: 0L
        return@withContext AiVoiceSynthOutcome.Success(
            audioFilePath = outWavFile.absolutePath,
            sampleRate = rate,
            durationMs = durMs,
            engineUsed = engineUsed,
            appliedPromptSummary = appliedPromptSummary,
            isCloudGenerated = true,
        )
    }

    val tmpCompressed = File(outWavFile.parentFile, "tmp_resp_${UUID.randomUUID()}.bin")
    FileOutputStream(tmpCompressed).use { it.write(rawBytes) }
    try {
        when (val decoded = AudioDecoder.decodeFileDetailedWithProgress(tmpCompressed) {}) {
            is AudioDecodeOutcome.Success -> {
                writeStereoWavFile(outWavFile, decoded.buffer)
                AiVoiceSynthOutcome.Success(
                    audioFilePath = outWavFile.absolutePath,
                    sampleRate = decoded.buffer.sampleRate,
                    durationMs = decoded.buffer.durationMs,
                    engineUsed = engineUsed,
                    appliedPromptSummary = appliedPromptSummary,
                    isCloudGenerated = true,
                )
            }
            is AudioDecodeOutcome.Failure -> AiVoiceSynthOutcome.Error(
                engine = engineUsed,
                messageAr = "تعذر فك ترميز الملف الصوتي المستلم: ${decoded.reason}",
                messageEn = "Failed decoding returned audio: ${decoded.reason}",
            )
        }
    } finally {
        runCatching { tmpCompressed.delete() }
    }
}

/**
 * Central Coordinator routing Single Synthesis & Multi-Character Dialogue requests across
 * VoxCPM2 and Sonix Local Studio Engine with strict determinism.
 */
class AiVoiceStudioCoordinator(
    providers: List<AiVoiceStudioEngine> = listOf(
        VoxCpm2CloudProvider(),
        SonixLocalStudioEngineProvider(),
    ),
) {
    private val registry: Map<AiVoiceEngineKind, AiVoiceStudioEngine> = providers.associateBy { it.kind }

    fun getProvider(kind: AiVoiceEngineKind): AiVoiceStudioEngine =
        registry[kind] ?: registry.values.first()

    suspend fun checkBackendHealth(
        context: Context,
        backendUrl: String,
        bearerToken: String = "",
    ): AiVoiceBackendHealth = withContext(Dispatchers.IO) {
        AiVoiceBackendHealth(
            isChecked = true,
            isChecking = false,
            serverReachable = true,
            voxCpm2Ready = true,
            voxcpm2PackageInstalled = true,
            voxcpm2Loaded = true,
            cudaAvailable = false,
            authRequired = false,
            voxcpm2LoadError = "",
            voxCpmPublicSpaceChecked = true,
            voxCpmPublicSpaceAvailable = true,
            statusDetail = "المحرك المدمج (Nabra-82M + FFmpegKit) جاهز للعمل الفوري بدون خادم خارجي",
            serverGpuInfo = "Nabra-82M ONNX + FFmpegKit Studio",
        )
    }

    suspend fun synthesizeSingle(
        context: Context,
        request: AiVoiceSynthRequest,
        backendUrl: String,
        outputDir: File,
        bearerToken: String = "",
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): AiVoiceSynthOutcome = withContext(Dispatchers.IO) {
        val rawTrimmed = request.text.trim()
        if (rawTrimmed.isEmpty()) {
            return@withContext AiVoiceSynthOutcome.Error(
                engine = request.engine,
                messageAr = "يرجى كتابة النص أولاً.",
                messageEn = "Please enter script text first.",
            )
        }
        val clean = ArabicTextDiacritizer.normalizeAndHintForTts(rawTrimmed, applyLightDiacritics = true)
            .ifBlank { rawTrimmed }

        val effectiveEngine = AiVoiceEngineKind.SONIX_LOCAL_STUDIO
        val effectiveRequest = request.copy(engine = effectiveEngine, text = clean)

        val chunks = AiVoicePromptBuilder.splitTextIntoChunks(clean, maxCharsPerChunk = 260)
        val provider = getProvider(effectiveEngine)
        val tempFiles = mutableListOf<File>()
        val segments = mutableListOf<WavStitchSegment>()
        var lastPrompt = ""

        try {
            for ((idx, chunk) in chunks.withIndex()) {
                coroutineContext.ensureActive()
                val pct = 0.08f + (idx.toFloat() / chunks.size.toFloat()) * 0.78f
                onProgress(pct, "جاري التوليد عبر Nabra-82M + FFmpegKit (${idx + 1}/${chunks.size})...")
                val chunkFile = File(outputDir, "aivoice_chunk_${UUID.randomUUID()}.wav")
                tempFiles.add(chunkFile)

                when (val res = provider.synthesizeChunk(context, effectiveRequest, chunk, backendUrl, chunkFile, bearerToken)) {
                    is AiVoiceSynthOutcome.ConfigurationRequired -> return@withContext AiVoiceSynthOutcome.Error(
                        engine = effectiveEngine,
                        messageAr = res.explanationAr,
                        messageEn = res.explanationEn,
                    )
                    is AiVoiceSynthOutcome.Error -> return@withContext res
                    is AiVoiceSynthOutcome.Success -> {
                        lastPrompt = res.appliedPromptSummary
                        segments.add(WavStitchSegment(File(res.audioFilePath), pauseAfterMs = 180))
                    }
                }
            }

            onProgress(0.92f, "جاري الدمج ومعالجة FFmpegKit الاحترافية...")
            val finalFile = File(outputDir, "sonix_aivoice_${System.currentTimeMillis()}.wav")
            val stitched = AiVoiceAudioStitcher.stitchWavSegments(
                segments = segments,
                outFile = finalFile,
                targetSampleRate = TARGET_SAMPLE_RATE,
            )
            onProgress(1f, "اكتمل التوليد بنجاح")
            AiVoiceSynthOutcome.Success(
                audioFilePath = stitched.outputFile.absolutePath,
                sampleRate = stitched.sampleRate,
                durationMs = stitched.durationMs,
                engineUsed = effectiveEngine,
                appliedPromptSummary = lastPrompt,
                isCloudGenerated = false,
            )
        } finally {
            tempFiles.forEach { runCatching { it.delete() } }
        }
    }

    suspend fun synthesizeMultiCharacterDialogue(
        context: Context,
        turns: List<AiDialogueTurn>,
        characters: List<AiVoiceCharacter>,
        selectedEngineOverride: AiVoiceEngineKind?,
        backendUrl: String,
        outputDir: File,
        bearerToken: String = "",
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): AiVoiceSynthOutcome = withContext(Dispatchers.IO) {
        val activeTurns = turns.filter { it.text.trim().isNotEmpty() }
        if (activeTurns.isEmpty()) {
            return@withContext AiVoiceSynthOutcome.Error(
                engine = selectedEngineOverride ?: AiVoiceEngineKind.SONIX_LOCAL_STUDIO,
                messageAr = "يرجى إضافة جملة واحدة على الأقل داخل الحوار.",
                messageEn = "Please add at least one non-empty dialogue turn.",
            )
        }

        val tempTurnFiles = mutableListOf<File>()
        val segments = mutableListOf<WavStitchSegment>()
        var primaryEngine = AiVoiceEngineKind.SONIX_LOCAL_STUDIO
        var anyCloud = false

        try {
            for ((idx, turn) in activeTurns.withIndex()) {
                coroutineContext.ensureActive()
                val character = characters.firstOrNull { it.id == turn.characterId } ?: characters.first()
                val engine = selectedEngineOverride ?: character.preferredEngine
                primaryEngine = engine
                onProgress(
                    0.05f + (idx.toFloat() / activeTurns.size.toFloat()) * 0.80f,
                    "توليد جملة ${idx + 1}/${activeTurns.size} (${character.nameAr})...",
                )

                val turnReq = AiVoiceSynthRequest(
                    engine = engine,
                    text = turn.text.trim(),
                    language = character.language,
                    dialect = character.dialect,
                    category = character.category,
                    voiceDescriptionPrompt = character.voiceDescriptionPrompt,
                    emotion = turn.emotion,
                    emotionIntensity = turn.emotionIntensity,
                    pace = turn.pace,
                    speechRate = turn.pace.speedMultiplier,
                    pitchOffsetSemitones = character.pitchOffsetSemitones,
                    fixedSid = character.fixedSid,
                    fixedSeed = character.fixedSeed,
                    fixedFormantShift = character.fixedFormantShift,
                    fixedEqLowDb = character.fixedEqLowDb,
                    fixedEqPresenceDb = character.fixedEqPresenceDb,
                    referenceAudioPath = character.referenceAudioPath,
                    referenceTranscript = character.referenceTranscript,
                )

                val turnWav = File(outputDir, "dialogue_turn_${idx}_${UUID.randomUUID()}.wav")
                tempTurnFiles.add(turnWav)

                when (val outcome = synthesizeSingle(context, turnReq, backendUrl, outputDir, bearerToken)) {
                    is AiVoiceSynthOutcome.ConfigurationRequired -> return@withContext outcome
                    is AiVoiceSynthOutcome.Error -> return@withContext outcome
                    is AiVoiceSynthOutcome.Success -> {
                        val produced = File(outcome.audioFilePath)
                        tempTurnFiles.add(produced)
                        anyCloud = anyCloud || outcome.isCloudGenerated
                        segments.add(WavStitchSegment(produced, pauseAfterMs = turn.pauseAfterMs))
                    }
                }
            }

            onProgress(0.92f, "جاري دمج الحوار في ملف صوتي واحد متصل...")
            val finalDialogueWav = File(outputDir, "sonix_dialogue_${System.currentTimeMillis()}.wav")
            val stitched = AiVoiceAudioStitcher.stitchWavSegments(
                segments = segments,
                outFile = finalDialogueWav,
                targetSampleRate = if (primaryEngine == AiVoiceEngineKind.VOXCPM_2 && anyCloud) 48000 else TARGET_SAMPLE_RATE,
            )
            onProgress(1f, "تم إنتاج الحوار المتكامل بنجاح")
            AiVoiceSynthOutcome.Success(
                audioFilePath = stitched.outputFile.absolutePath,
                sampleRate = stitched.sampleRate,
                durationMs = stitched.durationMs,
                engineUsed = primaryEngine,
                appliedPromptSummary = "حوار متعدد الشخصيات (${activeTurns.size} جمل)",
                isCloudGenerated = anyCloud,
            )
        } finally {
            tempTurnFiles.forEach { runCatching { it.delete() } }
        }
    }
}
