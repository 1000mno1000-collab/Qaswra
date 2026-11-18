package com.example.audio.aivoice

import android.content.Context
import android.util.Base64
import com.example.audio.AudioDecodeOutcome
import com.example.audio.AudioDecoder
import com.example.audio.OnlineGuard
import com.example.audio.TARGET_SAMPLE_RATE
import com.example.audio.network.NetworkImmunityManager
import com.example.audio.transformVoiceOffline
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

/**
 * Pluggable AI Voice Engine interface so providers (VoxCPM2, Audar-TTS-V1-Flash, custom GPU endpoints,
 * or on-device fallback) can be swapped cleanly without changing any UI code.
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
        // Cap reference clip to 12 MB raw bytes
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
            setRequestProperty("User-Agent", "Sonix-AiVoiceStudio/1.1")
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
            setRequestProperty("User-Agent", "Sonix-AiVoiceStudio/1.1")
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
                messageEn = "$serverLabel server timed out (cold model load or heavy inference): $detail",
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
                messageAr = "تعذر فتح اتصال مع خادم $serverLabel — تأكد من تشغيل خدمة FastAPI على الخادم.",
                messageEn = "Connection refused by $serverLabel server: $detail",
                technicalDetail = t.toString(),
            )
            is SSLException -> AiVoiceSynthOutcome.Error(
                engine = engine,
                messageAr = "فشل التحقق من شهادة SSL/HTTPS لخادم $serverLabel. استخدم رابط HTTPS صالحاً.",
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
                messageAr = "رفض خادم $serverLabel التصريح (HTTP $code): تأكد من رمز الخادم X-Sonix-Bearer أو تسجيل الدخول.",
                messageEn = "$serverLabel rejected authentication (HTTP $code): check X-Sonix-Bearer token or sign-in.",
                technicalDetail = detail,
            )
            400, 413 -> AiVoiceSynthOutcome.Error(
                engine = engine,
                messageAr = "بيانات الطلب أو العينة المرجعية غير صالحة لـ $serverLabel (HTTP $code).",
                messageEn = "Invalid synthesis request or reference audio for $serverLabel (HTTP $code).",
                technicalDetail = detail,
            )
            503 -> AiVoiceSynthOutcome.Error(
                engine = engine,
                messageAr = "خادم $serverLabel غير جاهز حالياً (CUDA غير متاح أو نفدت ذاكرة GPU VRAM — HTTP 503).",
                messageEn = "$serverLabel GPU service unavailable or VRAM OOM (HTTP 503).",
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
 * Primary Multilingual Engine: openbmb/VoxCPM2 (2B diffusion-AR, 48kHz).
 * Connects to configured GPU FastAPI Backend (`/v1/voice-studio/synthesize`), or probes
 * Hugging Face's official public Gradio Space (`openbmb/VoxCPM-Demo`) via HTTP Gradio API when
 * no private GPU server URL is provided. Never fabricates fake audio.
 */
class VoxCpm2CloudProvider : AiVoiceStudioEngine {
    override val kind: AiVoiceEngineKind = AiVoiceEngineKind.VOXCPM_2

    companion object {
        private const val PUBLIC_SPACE_BASE = "https://openbmb-voxcpm-demo.hf.space"
    }

    override suspend fun checkHealth(
        context: Context,
        backendUrl: String,
        bearerToken: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!OnlineGuard.isOnline(context)) return@withContext false
        val cleanBase = backendUrl.trim().trimEnd('/')
        if (cleanBase.isNotEmpty()) {
            val (code, body) = runCatching {
                BackendHttpHelper.getJson("$cleanBase/health", bearerToken = bearerToken)
            }.getOrDefault(0 to "")
            if (code in 200..299) {
                val obj = runCatching { JSONObject(body) }.getOrNull()
                return@withContext obj?.optBoolean("voxcpm2_ready", false) ?: false
            }
            return@withContext false
        }
        // Check whether official public Hugging Face Space is awake
        val (spaceCode, _) = runCatching {
            BackendHttpHelper.getJson("$PUBLIC_SPACE_BASE/gradio_api/info", timeoutMs = 8_000)
        }.getOrDefault(0 to "")
        spaceCode in 200..299
    }

    override suspend fun synthesizeChunk(
        context: Context,
        request: AiVoiceSynthRequest,
        textChunk: String,
        backendUrl: String,
        outWavFile: File,
        bearerToken: String,
    ): AiVoiceSynthOutcome = withContext(Dispatchers.IO) {
        if (!OnlineGuard.isOnline(context)) {
            return@withContext AiVoiceSynthOutcome.Error(
                engine = kind,
                messageAr = "لا يوجد اتصال نشط بالإنترنت لتشغيل محرك VoxCPM2 السحابي.",
                messageEn = "No verified internet connection for VoxCPM2 cloud synthesis.",
            )
        }

        val cleanBase = backendUrl.trim().trimEnd('/')
        if (cleanBase.isNotEmpty()) {
            return@withContext callDedicatedGpuBackend(
                context = context,
                cleanBase = cleanBase,
                bearerToken = bearerToken,
                request = request,
                textChunk = textChunk,
                outWavFile = outWavFile,
            )
        }

        // Try official Hugging Face Space Gradio API as zero-config public endpoint before asking user for GPU server
        callPublicVoxCpmSpace(context, request, textChunk, outWavFile)
    }

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
            appliedPromptSummary = AiVoicePromptBuilder.buildVoxCpmInstruction(request),
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
                titleAr = "استنساخ الصوت عبر VoxCPM2 يتطلب ربط خادم GPU الخلفي",
                titleEn = "Voice Cloning Requires Your GPU Backend URL",
                explanationAr = "الفضاء العام التجريبي على Hugging Face لا يقبل رفع ملفات الاستنساخ المرجعية الخاصة بك عبر API مباشرة، وخدمات Inference Providers لا تستضيف openbmb/VoxCPM2 كخدمة serverless مجانية.",
                explanationEn = "The public Hugging Face Space does not support private reference audio uploads cleanly without a dedicated GPU server, and openbmb/VoxCPM2 has no managed serverless HF Inference Provider.",
                setupStepsAr = "1. شغّل خادم `backend/` المرفق على GPU (RunPod / Modal / HF Endpoints / Docker).\n2. ضع رابط الخادم (HTTPS) في تبويب «السيرفر والربط».\n3. يمكنك فوراً اختيار «محرك الاستوديو المحلي» مؤقتاً كبديل بدون سيرفر.",
                setupStepsEn = "1. Deploy the included `backend/` FastAPI GPU server.\n2. Enter its HTTPS URL in the Server Setup tab.\n3. Or choose the Local Studio Engine for immediate offline synthesis.",
            )
        }

        val (infoCode, infoJsonStr) = runCatching {
            BackendHttpHelper.getJson("$PUBLIC_SPACE_BASE/gradio_api/info", timeoutMs = 10_000)
        }.getOrDefault(0 to "")

        if (infoCode !in 200..299 || infoJsonStr.isBlank()) {
            return AiVoiceSynthOutcome.ConfigurationRequired(
                engine = kind,
                titleAr = "خادم VoxCPM2 غير متصل أو يحتاج ربط GPU خاص",
                titleEn = "VoxCPM2 Cloud Server Setup Required",
                explanationAr = "نموذج openbmb/VoxCPM2 بحجم 2B باراميتر يحتاج GPU بسعة VRAM عالية ولا يتوفر له مزود Inference Serverless افتراضي على Hugging Face، كما أن الفضاء التجريبي العام متوقف أو مشغول حالياً (HTTP $infoCode).",
                explanationEn = "openbmb/VoxCPM2 (2B params) requires GPU VRAM and has no managed serverless Inference Provider on Hugging Face, and the public Space is unavailable or sleeping (HTTP $infoCode).",
                setupStepsAr = "1. انشر السيرفر الخلفي في مجلد `backend/` على أي خادم GPU مع مفتاح HF_TOKEN في متغيرات السيرفر فقط.\n2. أدخل رابط السيرفر الآمن HTTPS في تبويب «السيرفر والربط».\n3. أو بدّل الآن إلى «محرك الاستوديو المحلي» للمتابعة فوراً دون خادم.",
                setupStepsEn = "1. Deploy `backend/` on a GPU instance (with server-side HF_TOKEN).\n2. Paste your HTTPS backend URL in Server Settings.\n3. Or switch to the Local Studio Engine to work immediately offline.",
            )
        }

        val infoJson = runCatching { JSONObject(infoJsonStr) }.getOrNull()
        val namedEndpoints = infoJson?.optJSONObject("named_endpoints")
        val endpointName = selectBestGradioEndpoint(namedEndpoints)
        if (endpointName == null) {
            return AiVoiceSynthOutcome.ConfigurationRequired(
                engine = kind,
                titleAr = "يلزم ربط خادم GPU الخاص لتشغيل VoxCPM2",
                titleEn = "Dedicated GPU Backend Required for VoxCPM2",
                explanationAr = "تعذر مطابقة واجهة الفضاء العام لـ VoxCPM تلقائياً. يرجى ربط خادم `backend/` الخاص بك للحصول على واجهة API ثابتة بدقة 48kHz.",
                explanationEn = "Public Space Gradio endpoints changed or require authentication. Connect your `backend/` GPU server for stable 48kHz generation.",
                setupStepsAr = "ضع رابط خادم FastAPI HTTPS من مجلد `backend/` داخل تبويب الإعدادات.",
                setupStepsEn = "Configure your HTTPS `backend/` URL in the Server Setup tab.",
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
                titleAr = "الفضاء العام مزدحم أو مقيد (HTTP $submitCode)",
                titleEn = "Public Space Queue Busy or Unavailable (HTTP $submitCode)",
                explanationAr = "لم يتمكن الفضاء العام لـ VoxCPM2 من قبول الطلب في الوقت الحالي، ويفضّل ربط خادم GPU الخلفي في `backend/` لضمان الاستقرار والسرعة والخصوصية.",
                explanationEn = "The public VoxCPM2 demo queue did not accept the job (HTTP $submitCode). Connect your private `backend/` GPU server for reliable synthesis.",
                setupStepsAr = "أدخل رابط خادم GPU الخلفي HTTPS في تبويب «إعدادات السيرفر والربط» أو اختر «محرك الاستوديو المحلي» مؤقتاً.",
                setupStepsEn = "Enter your HTTPS GPU backend URL in Server Setup, or choose Local Studio Engine.",
            )
        }

        // Poll SSE stream briefly for Gradio result
        val audioUrl = pollGradioEventForAudioUrl("$PUBLIC_SPACE_BASE/gradio_api/call/$apiSlug/$eventId")
        if (audioUrl.isNullOrBlank()) {
            return AiVoiceSynthOutcome.Error(
                engine = kind,
                messageAr = "انتهت مهلة الانتظار في صف الفضاء العام لـ VoxCPM2 دون ملف صوتي.",
                messageEn = "Public VoxCPM2 Space queue timed out without returning audio.",
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
                appliedPromptSummary = instruction,
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
 * Arabic Specialist Engine: audarai/Audar-TTS-V1-Flash (Open License v1.0, Arabic-first + inline tags + zero-shot clone)
 * with legacy support for audarai/Audar-TTS-V1-Turbo when configured on a licensed backend.
 * Neither model has a managed public Hugging Face Serverless Inference Provider, so cloud GPU
 * synthesis connects to our `backend/` server.
 */
class AudarTtsCloudProvider(
    override val kind: AiVoiceEngineKind = AiVoiceEngineKind.AUDAR_TTS_V1_FLASH,
) : AiVoiceStudioEngine {

    private val isFlash: Boolean
        get() = kind == AiVoiceEngineKind.AUDAR_TTS_V1_FLASH

    private val engineShortLabel: String
        get() = if (isFlash) "Audar-TTS-V1-Flash" else "Audar-TTS-V1-Turbo"

    override suspend fun checkHealth(
        context: Context,
        backendUrl: String,
        bearerToken: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanBase = backendUrl.trim().trimEnd('/')
        if (cleanBase.isEmpty() || !OnlineGuard.isOnline(context)) return@withContext false
        val (code, body) = runCatching {
            BackendHttpHelper.getJson("$cleanBase/health", bearerToken = bearerToken)
        }.getOrDefault(0 to "")
        if (code !in 200..299) return@withContext false
        val obj = runCatching { JSONObject(body) }.getOrNull()
        if (isFlash) {
            obj?.optBoolean("audar_flash_ready", obj.optBoolean("audar_tts_ready", false)) ?: false
        } else {
            obj?.optBoolean("audar_tts_ready", false) ?: false
        }
    }

    override suspend fun synthesizeChunk(
        context: Context,
        request: AiVoiceSynthRequest,
        textChunk: String,
        backendUrl: String,
        outWavFile: File,
        bearerToken: String,
    ): AiVoiceSynthOutcome = withContext(Dispatchers.IO) {
        val cleanBase = backendUrl.trim().trimEnd('/')
        if (cleanBase.isEmpty()) {
            return@withContext AiVoiceSynthOutcome.ConfigurationRequired(
                engine = kind,
                titleAr = "محرك $engineShortLabel يتطلب ربط خادم GPU الخلفي",
                titleEn = "$engineShortLabel Requires Backend GPU Server",
                explanationAr = "بحسب صفحة النموذج الرسمية على Hugging Face، فإن نموذج ${kind.modelRepoId} لا يتوفر له أي مزود Inference Serverless عام مجاني من Hugging Face ويحتاج إلى استضافة GPU عبر خادم `backend/` (llama.cpp + NeuCodec / Transformers).",
                explanationEn = "Per Hugging Face's official model registry, ${kind.modelRepoId} has no managed public Serverless Inference Provider (`inference: false`) and requires connecting your GPU `backend/` server.",
                setupStepsAr = "1. شغّل الخادم المرفق في مجلد `backend/` على أي خادم GPU (مع وضع HF_TOKEN في السيرفر فقط دون وضعه في التطبيق).\n2. أدخل رابط HTTPS للخادم في تبويب «السيرفر والربط».\n3. أو اختر الآن «محرك الاستوديو المحلي» للعمل فوراً على الهاتف بدون سيرفر.",
                setupStepsEn = "1. Deploy the included `backend/` server on GPU (keeping HF_TOKEN strictly server-side).\n2. Enter the server HTTPS URL in the Server Setup tab.\n3. Or select the Local Studio Engine to synthesize immediately on-device.",
            )
        }

        if (!OnlineGuard.isOnline(context)) {
            return@withContext AiVoiceSynthOutcome.Error(
                engine = kind,
                messageAr = "لا يوجد اتصال نشط بالإنترنت للوصول إلى خادم $engineShortLabel.",
                messageEn = "No internet connectivity to reach $engineShortLabel backend.",
            )
        }

        val refB64 = BackendHttpHelper.encodeFileBase64OrNull(request.referenceAudioPath)
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
            return@withContext BackendHttpHelper.mapConnectionError(kind, engineShortLabel, t)
        }

        if (code !in 200..299) {
            return@withContext BackendHttpHelper.mapHttpError(kind, engineShortLabel, code, bytes)
        }

        val formattedPreview = AiVoicePromptBuilder.buildAudarExpressiveText(
            textChunk,
            request.emotion,
            request.emotionIntensity,
        )
        persistAudioBytesAsWav(
            context = context,
            rawBytes = bytes,
            outWavFile = outWavFile,
            engineUsed = kind,
            appliedPromptSummary = "$engineShortLabel • ${request.dialect.titleAr} • $formattedPreview",
        )
    }
}

/**
 * Explicit On-Device Fallback Engine (never masquerades as VoxCPM2 or Audar-TTS).
 * Uses Sonix's real local neural/TTS synthesis + expressive DSP shaping (whisper, sadness, anger,
 * child, elderly, cartoon, deep male) directly on device when user chooses local mode.
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
        val mappedDialect = mapToLocalDialect(request.language, request.dialect)
        val mappedGender = when (request.category) {
            AiVoiceCategory.FEMALE, AiVoiceCategory.CHILD -> TtsGender.FEMALE
            else -> TtsGender.MALE
        }
        val mappedStyle = when (request.emotion) {
            AiVoiceEmotion.SAD, AiVoiceEmotion.CRYING -> TtsDeliveryStyle.SAD
            AiVoiceEmotion.ANGER -> TtsDeliveryStyle.ANGRY
            AiVoiceEmotion.EXCITED, AiVoiceEmotion.JOY, AiVoiceEmotion.LAUGH -> TtsDeliveryStyle.ENERGETIC
            AiVoiceEmotion.CALM, AiVoiceEmotion.WHISPER -> TtsDeliveryStyle.STORYTELLING
            AiVoiceEmotion.FEAR, AiVoiceEmotion.SURPRISED -> TtsDeliveryStyle.DRAMATIC
            AiVoiceEmotion.NEUTRAL -> TtsDeliveryStyle.NATURAL_WARM
        }
        val acousticMode = when (request.category) {
            AiVoiceCategory.NARRATOR -> TtsPersonaAcousticMode.DOCUMENTARY
            AiVoiceCategory.ELDERLY, AiVoiceCategory.MALE, AiVoiceCategory.FEMALE -> TtsPersonaAcousticMode.PODCAST
            else -> TtsPersonaAcousticMode.NATURAL
        }
        val speed = (request.pace.speedMultiplier * request.emotion.speedBiasFactor).coerceIn(0.60f, 1.60f)
        val pitchRatio = Math.pow(2.0, ((request.pitchOffsetSemitones + request.emotion.pitchBiasSemitones) / 12.0)).toFloat()
            .coerceIn(0.65f, 1.60f)

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
            speechRate = speed,
            pitch = pitchRatio,
            language = appLang,
            studioLuxuryEnhancement = true,
            acousticMode = acousticMode,
            autoDownloadIfOnline = false,
        )

        when (outcome) {
            is VoiceRouteOutcome.ProviderError -> AiVoiceSynthOutcome.Error(
                engine = kind,
                messageAr = outcome.message,
                messageEn = outcome.message,
            )
            is VoiceRouteOutcome.Success -> {
                var buf = outcome.result.buffer
                buf = if (request.emotion == AiVoiceEmotion.WHISPER) {
                    applySoftWhisperShaping(buf)
                } else {
                    applyCategoryFormantShaping(buf, request.category, request.emotion, request.emotionIntensity)
                }
                writeStereoWavFile(outWavFile, buf)
                runCatching { outcome.result.audioFile.delete() }
                AiVoiceSynthOutcome.Success(
                    audioFilePath = outWavFile.absolutePath,
                    sampleRate = buf.sampleRate,
                    durationMs = buf.durationMs,
                    engineUsed = kind,
                    appliedPromptSummary = "${request.category.titleAr} • ${request.emotion.titleAr} (محلي)",
                    isCloudGenerated = false,
                )
            }
        }
    }

    private suspend fun applyCategoryFormantShaping(
        input: StereoAudioBuffer,
        category: AiVoiceCategory,
        emotion: AiVoiceEmotion,
        intensity: Float = 0.65f,
    ): StereoAudioBuffer {
        val catShaped = when (category) {
            AiVoiceCategory.CHILD -> transformVoiceOffline(
                source = input,
                pitchSemitones = 2.6f,
                formantShift = 1.18f,
                warmthDb = 0f,
                presenceDb = 2.2f,
                mix = 0.85f,
                studioMasteringPolish = true,
            )
            AiVoiceCategory.CARTOON -> transformVoiceOffline(
                source = input,
                pitchSemitones = 4.2f,
                formantShift = 1.28f,
                warmthDb = -1f,
                presenceDb = 3.0f,
                mix = 0.90f,
                studioMasteringPolish = true,
            )
            AiVoiceCategory.ELDERLY -> transformVoiceOffline(
                source = input,
                pitchSemitones = -1.6f,
                formantShift = 0.92f,
                warmthDb = 2.5f,
                presenceDb = -1.5f,
                mix = 0.80f,
                vibratoHz = 5.4f,
                vibratoDepth = if (emotion == AiVoiceEmotion.SAD || emotion == AiVoiceEmotion.CRYING) 0.14f else 0.08f,
                studioMasteringPolish = true,
            )
            AiVoiceCategory.NARRATOR -> transformVoiceOffline(
                source = input,
                pitchSemitones = -1.4f,
                formantShift = 0.94f,
                warmthDb = 3.2f,
                presenceDb = 2.5f,
                mix = 0.78f,
                bassBoostDb = 2.5f,
                studioMasteringPolish = true,
            )
            else -> input
        }
        val k = intensity.coerceIn(0.25f, 1.0f)
        return when (emotion) {
            AiVoiceEmotion.CRYING -> transformVoiceOffline(
                source = catShaped,
                pitchSemitones = 0.25f * k,
                formantShift = 0.98f,
                warmthDb = 2.2f * k,
                presenceDb = -1.2f * k,
                mix = 0.65f,
                vibratoHz = 6.2f,
                vibratoDepth = 0.18f * k,
                studioMasteringPolish = true,
            )
            AiVoiceEmotion.SAD -> transformVoiceOffline(
                source = catShaped,
                pitchSemitones = -0.5f * k,
                formantShift = 0.97f,
                warmthDb = 2.5f * k,
                presenceDb = -1.5f * k,
                mix = 0.55f,
                vibratoHz = 4.4f,
                vibratoDepth = 0.08f * k,
                studioMasteringPolish = true,
            )
            AiVoiceEmotion.FEAR -> transformVoiceOffline(
                source = catShaped,
                pitchSemitones = 0.6f * k,
                formantShift = 1.04f,
                warmthDb = -1.0f * k,
                presenceDb = 2.2f * k,
                mix = 0.65f,
                vibratoHz = 7.4f,
                vibratoDepth = 0.20f * k,
                studioMasteringPolish = true,
            )
            AiVoiceEmotion.ANGER -> transformVoiceOffline(
                source = catShaped,
                pitchSemitones = -0.3f * k,
                formantShift = 0.96f,
                warmthDb = 1.5f * k,
                presenceDb = 3.8f * k,
                mix = 0.70f,
                bassBoostDb = 2.0f * k,
                studioMasteringPolish = true,
            )
            AiVoiceEmotion.JOY, AiVoiceEmotion.EXCITED, AiVoiceEmotion.LAUGH, AiVoiceEmotion.SURPRISED -> transformVoiceOffline(
                source = catShaped,
                pitchSemitones = 0.7f * k,
                formantShift = 1.04f,
                warmthDb = 0.6f * k,
                presenceDb = 2.8f * k,
                mix = 0.60f,
                vibratoHz = if (emotion == AiVoiceEmotion.LAUGH) 8.5f else 0f,
                vibratoDepth = if (emotion == AiVoiceEmotion.LAUGH) 0.14f * k else 0f,
                studioMasteringPolish = true,
            )
            AiVoiceEmotion.CALM -> transformVoiceOffline(
                source = catShaped,
                pitchSemitones = -0.35f * k,
                formantShift = 0.98f,
                warmthDb = 3.0f * k,
                presenceDb = -1.0f * k,
                mix = 0.55f,
                studioMasteringPolish = true,
            )
            else -> catShaped
        }
    }

    private fun mapToLocalDialect(lang: AiVoiceLanguage, dialect: AiArabicDialect): TtsDialect {
        val list = com.example.model.TTS_DIALECTS
        return when (lang) {
            AiVoiceLanguage.EN -> list.firstOrNull { it.id == "en_us" } ?: list.first()
            AiVoiceLanguage.FA -> list.firstOrNull { it.id == "persian" } ?: list.first()
            AiVoiceLanguage.TR -> list.firstOrNull { it.id == "turkish" } ?: list.first()
            AiVoiceLanguage.AR -> when (dialect) {
                AiArabicDialect.IRAQI -> list.firstOrNull { it.id == "iraqi" } ?: list.first()
                AiArabicDialect.GULF -> list.firstOrNull { it.id == "emirati" } ?: list.first()
                AiArabicDialect.SAUDI -> list.firstOrNull { it.id == "saudi" } ?: list.first()
                AiArabicDialect.LEVANTINE -> list.firstOrNull { it.id == "syrian" } ?: list.first()
                AiArabicDialect.EGYPTIAN -> list.firstOrNull { it.id == "egyptian" } ?: list.first()
                AiArabicDialect.MOROCCAN -> list.firstOrNull { it.id == "moroccan" } ?: list.first()
                AiArabicDialect.JORDANIAN -> list.firstOrNull { it.id == "jordanian" } ?: list.first()
                AiArabicDialect.MSA -> list.firstOrNull { it.id == "standard" } ?: list.first()
            }
            else -> list.firstOrNull { it.id == "en_us" } ?: list.first()
        }
    }

    private fun applySoftWhisperShaping(input: StereoAudioBuffer): StereoAudioBuffer {
        val n = input.frameCount
        val outL = FloatArray(n)
        val outR = FloatArray(n)
        var prevL = 0f
        var prevR = 0f
        var rng = 0x1234567
        for (i in 0 until n) {
            rng = rng * 1664525 + 1013904223
            val white = ((rng ushr 16) and 0x7FFF) / 32768f - 0.5f
            val hpL = (input.left[i] - 0.92f * prevL) * 0.58f
            val hpR = (input.right[i] - 0.92f * prevR) * 0.58f
            prevL = input.left[i]
            prevR = input.right[i]
            val breath = white * 0.025f * kotlin.math.min(1f, kotlin.math.abs(hpL) * 6f)
            outL[i] = (hpL + breath).coerceIn(-0.95f, 0.95f)
            outR[i] = (hpR + breath).coerceIn(-0.95f, 0.95f)
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

    // If backend returned MP3/OGG/FLAC/M4A, decode cleanly into WAV so dialogue stitching and preview always work reliably
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
 * pluggable [AiVoiceStudioEngine] implementations.
 */
class AiVoiceStudioCoordinator(
    providers: List<AiVoiceStudioEngine> = listOf(
        VoxCpm2CloudProvider(),
        AudarTtsCloudProvider(AiVoiceEngineKind.AUDAR_TTS_V1_FLASH),
        AudarTtsCloudProvider(AiVoiceEngineKind.AUDAR_TTS_V1_TURBO),
        SonixLocalStudioEngineProvider(),
        EdgeNeuralCloudProvider(),
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
        if (!OnlineGuard.isOnline(context)) {
            return@withContext AiVoiceBackendHealth(
                isChecked = true,
                isChecking = false,
                serverReachable = false,
                statusDetail = "لا يوجد اتصال نشط بالإنترنت",
            )
        }
        val cleanBase = backendUrl.trim().trimEnd('/')
        if (cleanBase.isNotEmpty()) {
            val result = runCatching {
                BackendHttpHelper.getJson("$cleanBase/health", bearerToken = bearerToken, timeoutMs = 15_000)
            }
            val throwable = result.exceptionOrNull()
            if (throwable != null) {
                return@withContext AiVoiceBackendHealth(
                    isChecked = true,
                    isChecking = false,
                    serverReachable = false,
                    statusDetail = "تعذر الاتصال بالخادم: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}",
                )
            }
            val (code, body) = result.getOrDefault(0 to "")
            if (code in 200..299 && body.isNotBlank()) {
                val obj = runCatching { JSONObject(body) }.getOrNull()
                val voxReady = obj?.optBoolean("voxcpm2_ready", false) ?: false
                val audarReady = obj?.optBoolean("audar_flash_ready", obj.optBoolean("audar_tts_ready", false)) ?: false
                val pkgInstalled = obj?.optBoolean("voxcpm2_package_installed", false) ?: false
                val voxLoaded = obj?.optBoolean("voxcpm2_loaded", false) ?: false
                val cudaAvail = obj?.optBoolean("cuda_available", false) ?: false
                val authReq = obj?.optBoolean("auth_required", false) ?: false
                val loadErr = obj?.optString("voxcpm2_load_error").orEmpty().let {
                    if (it == "null") "" else it
                }
                val gpuName = obj?.optString("gpu_name").orEmpty()
                val vramTotal = obj?.optDouble("vram_total_mb", 0.0) ?: 0.0
                val vramUsed = obj?.optDouble("vram_used_mb", 0.0) ?: 0.0
                val gpuDisplay = if (gpuName.isNotBlank() && vramTotal > 0.0) {
                    "$gpuName (${vramUsed.toInt()} / ${vramTotal.toInt()} MB VRAM)"
                } else {
                    gpuName
                }
                val statusMsg = when {
                    voxReady -> "متصل بالخادم بنجاح — VoxCPM2 جاهز على GPU (HTTP $code)"
                    !cudaAvail -> "متصل بالخادم لكن CUDA/GPU غير متاح حالياً (HTTP $code)"
                    !pkgInstalled -> "متصل بالخادم لكن حزمة voxcpm غير مثبتة بعد (HTTP $code)"
                    loadErr.isNotBlank() -> "متصل بالخادم مع تعذر تحميل VoxCPM2 (HTTP $code)"
                    else -> "متصل بالخادم (HTTP $code)"
                }
                return@withContext AiVoiceBackendHealth(
                    isChecked = true,
                    isChecking = false,
                    serverReachable = true,
                    voxCpm2Ready = voxReady,
                    audarTurboReady = audarReady,
                    voxcpm2PackageInstalled = pkgInstalled,
                    voxcpm2Loaded = voxLoaded,
                    cudaAvailable = cudaAvail,
                    authRequired = authReq,
                    voxcpm2LoadError = loadErr,
                    edgeNeuralReady = obj?.optBoolean("edge_neural_free_ready", true) ?: true,
                    statusDetail = statusMsg,
                    serverGpuInfo = gpuDisplay,
                )
            }
            val errDetail = BackendHttpHelper.extractErrorDetail(body)
            return@withContext AiVoiceBackendHealth(
                isChecked = true,
                isChecking = false,
                serverReachable = false,
                statusDetail = "تعذر الوصول للخادم المحدد (HTTP $code)${if (errDetail.isNotBlank()) ": $errDetail" else ""}",
            )
        }
        val spaceOk = getProvider(AiVoiceEngineKind.VOXCPM_2).checkHealth(context, "", bearerToken)
        AiVoiceBackendHealth(
            isChecked = true,
            isChecking = false,
            serverReachable = false,
            voxCpmPublicSpaceChecked = true,
            voxCpmPublicSpaceAvailable = spaceOk,
            statusDetail = if (spaceOk) {
                "الفضاء العام لـ VoxCPM2 متاح مؤقتاً؛ Audar-TTS-V1-Flash واستنساخ الصوت يتطلبان ربط خادم GPU"
            } else {
                "لم يتم ربط خادم GPU خلفي بعد"
            },
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
        val clean = request.text.trim()
        if (clean.isEmpty()) {
            return@withContext AiVoiceSynthOutcome.Error(
                engine = request.engine,
                messageAr = "يرجى كتابة النص أولاً.",
                messageEn = "Please enter script text first.",
            )
        }

        val autoSwitchedOffline = request.engine != AiVoiceEngineKind.SONIX_LOCAL_STUDIO &&
            !OnlineGuard.isOnline(context)
        val effectiveEngine = if (autoSwitchedOffline) AiVoiceEngineKind.SONIX_LOCAL_STUDIO else request.engine
        val effectiveRequest = if (autoSwitchedOffline) {
            request.copy(engine = effectiveEngine)
        } else {
            request
        }

        val chunks = AiVoicePromptBuilder.splitTextIntoChunks(clean, maxCharsPerChunk = 260)
        val provider = getProvider(effectiveEngine)
        val tempFiles = mutableListOf<File>()
        val segments = mutableListOf<WavStitchSegment>()
        var lastPrompt = ""
        var isCloud = false

        try {
            for ((idx, chunk) in chunks.withIndex()) {
                coroutineContext.ensureActive()
                val pct = 0.08f + (idx.toFloat() / chunks.size.toFloat()) * 0.78f
                val progressMsg = if (autoSwitchedOffline) {
                    "انقطع الإنترنت — التوليد التلقائي بالمحرك المحلي (${idx + 1}/${chunks.size})..."
                } else {
                    "جاري توليد المقطع ${idx + 1} من ${chunks.size}..."
                }
                onProgress(pct, progressMsg)
                val chunkFile = File(outputDir, "aivoice_chunk_${UUID.randomUUID()}.wav")
                tempFiles.add(chunkFile)

                when (val res = provider.synthesizeChunk(context, effectiveRequest, chunk, backendUrl, chunkFile, bearerToken)) {
                    is AiVoiceSynthOutcome.ConfigurationRequired -> return@withContext res
                    is AiVoiceSynthOutcome.Error -> return@withContext res
                    is AiVoiceSynthOutcome.Success -> {
                        lastPrompt = res.appliedPromptSummary
                        isCloud = res.isCloudGenerated
                        segments.add(WavStitchSegment(File(res.audioFilePath), pauseAfterMs = 180))
                    }
                }
            }

            onProgress(0.92f, "جاري دمج ومعالجة الجودة النهائية...")
            val finalFile = File(outputDir, "sonix_aivoice_${System.currentTimeMillis()}.wav")
            val stitched = AiVoiceAudioStitcher.stitchWavSegments(
                segments = segments,
                outFile = finalFile,
                targetSampleRate = if (effectiveEngine == AiVoiceEngineKind.VOXCPM_2 && isCloud) 48000 else TARGET_SAMPLE_RATE,
            )
            onProgress(1f, "اكتمل التوليد بنجاح")
            val summaryText = if (autoSwitchedOffline) {
                "$lastPrompt • تبديل أوفلاين تلقائي"
            } else {
                lastPrompt
            }
            AiVoiceSynthOutcome.Success(
                audioFilePath = stitched.outputFile.absolutePath,
                sampleRate = stitched.sampleRate,
                durationMs = stitched.durationMs,
                engineUsed = effectiveEngine,
                appliedPromptSummary = summaryText,
                isCloudGenerated = isCloud,
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
                engine = selectedEngineOverride ?: AiVoiceEngineKind.VOXCPM_2,
                messageAr = "يرجى إضافة جملة واحدة على الأقل داخل الحوار.",
                messageEn = "Please add at least one non-empty dialogue turn.",
            )
        }

        val tempTurnFiles = mutableListOf<File>()
        val segments = mutableListOf<WavStitchSegment>()
        var primaryEngine = selectedEngineOverride ?: AiVoiceEngineKind.VOXCPM_2
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
                    pitchOffsetSemitones = character.pitchOffsetSemitones,
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
