package com.example.audio.ai

import android.content.Context
import androidx.compose.runtime.Immutable
import com.example.audio.AudioDecoder
import com.example.audio.TARGET_SAMPLE_RATE
import com.example.audio.clamp
import com.example.audio.resampleStereo
import com.example.model.StereoAudioBuffer
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsGender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Immutable
data class LocalModelStatus(
    val hasModelsDirectory: Boolean = false,
    val hasOnnxRuntime: Boolean = false,
    val hasTtsOnnxModel: Boolean = false,
    val hasVoiceConversionOnnxModel: Boolean = false,
    val discoveredFiles: List<String> = emptyList(),
) {
    val isTtsReady: Boolean get() = hasOnnxRuntime && hasTtsOnnxModel
    val isVoiceChangerReady: Boolean get() = hasOnnxRuntime && hasVoiceConversionOnnxModel
}

data class LocalNeuralSpeechResult(
    val buffer: StereoAudioBuffer,
    val modelFileName: String,
)

/**
 * واجهة محرك الذكاء الاصطناعي الصوتي المحلي (Hybrid ONNX Runtime Engine Interface).
 *
 * تفحص تلقائياً مجلد `assets/models/` للبحث عن نماذج ONNX المضغوطة (مثل `model.onnx` و `tokens.txt`
 * لخدمة تحويل النص إلى صوت، أو `voice_conv.onnx` / `rvc_model.onnx` لتحويل الصوت).
 *
 * - عند توفر النموذج المحلي وبيئة ONNX Runtime: يتم التوليد أو التحويل محلياً فائق السرعة.
 * - عند عدم توفر النموذج المحلي (الحالة الافتراضية قبل رفع النماذج): يعود المحرك بـ `null` بسلاسة
 *   دون أي أخطاء لينتقل التنفيذ تلقائياً إلى [SonixUnifiedAIEngine] (Gemini 2.5 Audio السحابي المجاني).
 */
object LocalNeuralVoiceEngine {

    private const val ASSETS_MODELS_DIR = "models"
    private val KNOWN_TTS_MODEL_FILES = listOf("model.onnx", "tts_model.onnx", "piper_model.onnx")
    private val KNOWN_VC_MODEL_FILES = listOf("voice_conv.onnx", "rvc_model.onnx", "vc_model.onnx")

    fun detectLocalModels(context: Context): LocalModelStatus {
        val assetFiles = runCatching {
            context.assets.list(ASSETS_MODELS_DIR)?.toList().orEmpty()
        }.getOrDefault(emptyList())

        val hasDir = assetFiles.isNotEmpty()
        val hasRuntime = runCatching {
            Class.forName("ai.onnxruntime.OrtEnvironment")
            true
        }.getOrDefault(false)

        val onnxFiles = assetFiles.filter { it.endsWith(".onnx", ignoreCase = true) }
        val hasTokens = assetFiles.any {
            it.equals("tokens.txt", ignoreCase = true) || it.endsWith(".onnx.json", ignoreCase = true)
        }
        val hasTtsModel = onnxFiles.any { name ->
            KNOWN_TTS_MODEL_FILES.any { it.equals(name, ignoreCase = true) }
        } || (hasTokens && onnxFiles.isNotEmpty())

        val hasVcModel = onnxFiles.any { name ->
            KNOWN_VC_MODEL_FILES.any { it.equals(name, ignoreCase = true) }
        }

        return LocalModelStatus(
            hasModelsDirectory = hasDir,
            hasOnnxRuntime = hasRuntime,
            hasTtsOnnxModel = hasTtsModel,
            hasVoiceConversionOnnxModel = hasVcModel,
            discoveredFiles = assetFiles,
        )
    }

    /**
     * يولّد النطق محلياً عبر ONNX Runtime إذا تم تضمين نموذج محلي داخل `assets/models/`.
     * يعيد `null` تلقائياً إذا لم يُرفع نموذج ONNX بعد ليتم التوجيه فوراً للسحابة.
     */
    suspend fun synthesizeTtsIfAvailable(
        context: Context,
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
    ): LocalNeuralSpeechResult? = withContext(Dispatchers.IO) {
        val status = detectLocalModels(context)
        if (!status.isTtsReady) return@withContext null

        val modelName = status.discoveredFiles.firstOrNull { name ->
            KNOWN_TTS_MODEL_FILES.any { it.equals(name, ignoreCase = true) }
        } ?: status.discoveredFiles.firstOrNull { it.endsWith(".onnx", ignoreCase = true) }
            ?: return@withContext null

        val tokensName = status.discoveredFiles.firstOrNull {
            it.equals("tokens.txt", ignoreCase = true)
        } ?: return@withContext null

        runCatching {
            val modelFile = stageAssetToCache(context, "$ASSETS_MODELS_DIR/$modelName", modelName)
            val tokensFile = stageAssetToCache(context, "$ASSETS_MODELS_DIR/$tokensName", tokensName)
            val tokenMap = loadTokensMap(tokensFile)
            val tokenIds = encodeTextTokens(text, tokenMap)
            if (tokenIds.isEmpty()) return@withContext null

            val envClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val getEnv = envClass.getMethod("getEnvironment")
            val env = getEnv.invoke(null) ?: return@withContext null
            val createSession = envClass.getMethod("createSession", String::class.java)
            val session = createSession.invoke(env, modelFile.absolutePath) ?: return@withContext null

            try {
                // Reflection hook ready for VITS / Piper ONNX graphs (`input` [1, T], `input_lengths` [1], `scales` [3])
                val onnTensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
                val createTensorLong2D = onnTensorClass.getMethod(
                    "createTensor",
                    envClass,
                    Array<LongArray>::class.java,
                )
                val createTensorLong1D = onnTensorClass.getMethod(
                    "createTensor",
                    envClass,
                    LongArray::class.java,
                )
                val createTensorFloat1D = onnTensorClass.getMethod(
                    "createTensor",
                    envClass,
                    FloatArray::class.java,
                )

                val inputTensor = createTensorLong2D.invoke(null, env, arrayOf(tokenIds))
                val lenTensor = createTensorLong1D.invoke(null, env, longArrayOf(tokenIds.size.toLong()))
                val noiseScale = when (style) {
                    TtsDeliveryStyle.ENERGETIC, TtsDeliveryStyle.DRAMATIC -> 0.72f
                    else -> 0.667f
                }
                val lengthScale = (1f / speechRate.coerceIn(0.6f, 1.5f))
                val scalesTensor = createTensorFloat1D.invoke(null, env, floatArrayOf(noiseScale, lengthScale, 0.8f))

                val inputs = linkedMapOf<String, Any?>(
                    "input" to inputTensor,
                    "input_lengths" to lenTensor,
                    "scales" to scalesTensor,
                )

                val runMethod = session.javaClass.getMethod("run", Map::class.java)
                val resultObj = runMethod.invoke(session, inputs) ?: return@withContext null
                val pcmMono = extractPcmFromOrtResult(resultObj) ?: return@withContext null
                if (pcmMono.isEmpty()) return@withContext null

                val stereo = StereoAudioBuffer(pcmMono, pcmMono.copyOf(), 22050)
                val resampled = if (stereo.sampleRate == TARGET_SAMPLE_RATE) {
                    stereo
                } else {
                    resampleStereo(stereo, TARGET_SAMPLE_RATE)
                }
                LocalNeuralSpeechResult(buffer = resampled, modelFileName = modelName)
            } finally {
                runCatching { session.javaClass.getMethod("close").invoke(session) }
            }
        }.getOrNull()
    }

    /**
     * يحوّل نبرة الصوت محلياً عبر نموذج ONNX للتحويل الصوتي إذا كان مرفوعاً داخل `assets/models/`.
     * يعيد `null` تلقائياً عند عدم وجود نموذج محلي لينتقل التوجيه فوراً إلى السحابة.
     */
    suspend fun convertVoiceIfAvailable(
        context: Context,
        inputAudio: StereoAudioBuffer,
        targetCharacterId: String,
        pitchSemitones: Float,
    ): LocalNeuralSpeechResult? = withContext(Dispatchers.IO) {
        val status = detectLocalModels(context)
        if (!status.isVoiceChangerReady) return@withContext null

        val vcModelName = status.discoveredFiles.firstOrNull { name ->
            KNOWN_VC_MODEL_FILES.any { it.equals(name, ignoreCase = true) }
        } ?: return@withContext null

        runCatching {
            val modelFile = stageAssetToCache(context, "$ASSETS_MODELS_DIR/$vcModelName", vcModelName)
            val envClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val env = envClass.getMethod("getEnvironment").invoke(null) ?: return@withContext null
            val session = envClass.getMethod("createSession", String::class.java)
                .invoke(env, modelFile.absolutePath) ?: return@withContext null

            try {
                val sr = inputAudio.sampleRate.coerceAtLeast(8000)
                val frameCount = inputAudio.frameCount.coerceAtMost(sr * 45)
                if (frameCount <= 0) return@withContext null

                val mono = FloatArray(frameCount) { i ->
                    clamp(0.5f * (inputAudio.left[i] + inputAudio.right[i]), -1f, 1f)
                }
                val onnTensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
                val createFloat2D = onnTensorClass.getMethod("createTensor", envClass, Array<FloatArray>::class.java)
                val createFloat1D = onnTensorClass.getMethod("createTensor", envClass, FloatArray::class.java)

                val audioTensor = createFloat2D.invoke(null, env, arrayOf(mono))
                val pitchTensor = createFloat1D.invoke(null, env, floatArrayOf(pitchSemitones))

                val inputs = linkedMapOf<String, Any?>(
                    "audio" to audioTensor,
                    "pitch_shift" to pitchTensor,
                )
                val resultObj = session.javaClass.getMethod("run", Map::class.java).invoke(session, inputs)
                    ?: return@withContext null
                val outMono = extractPcmFromOrtResult(resultObj) ?: return@withContext null
                if (outMono.isEmpty()) return@withContext null

                val rawStereo = StereoAudioBuffer(outMono, outMono.copyOf(), sr)
                val unified = if (sr == TARGET_SAMPLE_RATE) rawStereo else resampleStereo(rawStereo, TARGET_SAMPLE_RATE)
                LocalNeuralSpeechResult(buffer = unified, modelFileName = vcModelName)
            } finally {
                runCatching { session.javaClass.getMethod("close").invoke(session) }
            }
        }.getOrNull()
    }

    private fun stageAssetToCache(context: Context, assetPath: String, fileName: String): File {
        val dir = File(context.cacheDir, "onnx_models").apply { mkdirs() }
        val dst = File(dir, fileName)
        if (dst.exists() && dst.length() > 1024L) return dst
        context.assets.open(assetPath).use { input ->
            FileOutputStream(dst).use { out ->
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
                out.flush()
            }
        }
        return dst
    }

    private fun loadTokensMap(tokensFile: File): Map<String, Long> {
        val map = HashMap<String, Long>(256)
        tokensFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isNotEmpty()) {
                    val parts = line.split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2) {
                        val sym = parts[0]
                        val id = parts[1].toLongOrNull()
                        if (id != null) map[sym] = id
                    }
                }
            }
        }
        return map
    }

    private fun encodeTextTokens(text: String, tokenMap: Map<String, Long>): LongArray {
        val bos = tokenMap["^"] ?: 1L
        val eos = tokenMap["$"] ?: 2L
        val pad = tokenMap["_"] ?: 0L
        val space = tokenMap[" "] ?: pad
        val list = ArrayList<Long>(text.length * 2 + 2)
        list += bos
        for (ch in text) {
            val id = tokenMap[ch.toString()] ?: if (ch.isWhitespace()) space else null
            if (id != null) {
                list += id
                list += pad
            }
        }
        list += eos
        return list.toLongArray()
    }

    private fun extractPcmFromOrtResult(resultObj: Any): FloatArray? {
        return runCatching {
            val getMethod = resultObj.javaClass.getMethod("get", Int::class.javaPrimitiveType)
            val firstVal = getMethod.invoke(resultObj, 0) ?: return null
            val rawValue = firstVal.javaClass.getMethod("getValue").invoke(firstVal) ?: return null
            val flat = flattenFloatOutput(rawValue)
            runCatching { resultObj.javaClass.getMethod("close").invoke(resultObj) }
            flat
        }.getOrNull()
    }

    private fun flattenFloatOutput(value: Any): FloatArray? {
        return when (value) {
            is FloatArray -> FloatArray(value.size) { clamp(value[it], -1f, 1f) }
            is Array<*> -> {
                val first = value.firstOrNull() ?: return null
                flattenFloatOutput(first)
            }
            else -> null
        }
    }
}
