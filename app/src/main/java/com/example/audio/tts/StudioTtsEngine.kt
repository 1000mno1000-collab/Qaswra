package com.example.audio.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.StringRes
import com.example.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

enum class TtsDialect(
    val id: String,
    @param:StringRes val titleRes: Int,
    val primaryLocaleTag: String,
    val fallbackTags: List<String>,
    val sampleText: String,
    val defaultPitch: Float = 1.0f,
    val defaultRate: Float = 0.98f
) {
    IRAQI(
        id = "ar_iq",
        titleRes = R.string.tts_dialect_iraqi,
        primaryLocaleTag = "ar-IQ",
        fallbackTags = listOf("ar-IQ", "ar-SA", "ar-EG", "ar"),
        sampleText = "هلا بيكم باستوديو فويس برو، شلونكم اليوم؟ جاهزين نسجل صوت صافي واحترافي.",
        defaultPitch = 0.97f,
        defaultRate = 0.95f
    ),
    SAUDI(
        id = "ar_sa",
        titleRes = R.string.tts_dialect_saudi,
        primaryLocaleTag = "ar-SA",
        fallbackTags = listOf("ar-SA", "ar-AE", "ar"),
        sampleText = "مرحباً بكم في استوديو فويس برو، تجربة هندسة صوتية مجانية بجودة احترافية.",
        defaultPitch = 1.00f,
        defaultRate = 0.98f
    ),
    JORDANIAN(
        id = "ar_jo",
        titleRes = R.string.tts_dialect_jordanian,
        primaryLocaleTag = "ar-JO",
        fallbackTags = listOf("ar-JO", "ar-SY", "ar-SA", "ar"),
        sampleText = "أهلاً وسهلاً فيكم معنا اليوم، خلونا نسمع وضوح الصوت بعد المعالجة الذكية.",
        defaultPitch = 1.01f,
        defaultRate = 1.00f
    ),
    SYRIAN(
        id = "ar_sy",
        titleRes = R.string.tts_dialect_syrian,
        primaryLocaleTag = "ar-SY",
        fallbackTags = listOf("ar-SY", "ar-LB", "ar-JO", "ar"),
        sampleText = "يسعد أوقاتكم بكل خير، هون باستوديو الصوت منحوّل أفكاركم لتسجيلات نقية.",
        defaultPitch = 1.03f,
        defaultRate = 0.98f
    ),
    MOROCCAN(
        id = "ar_ma",
        titleRes = R.string.tts_dialect_moroccan,
        primaryLocaleTag = "ar-MA",
        fallbackTags = listOf("ar-MA", "ar-DZ", "ar-TN", "ar"),
        sampleText = "مرحبا بكم معنا فاستوديو فويس برو، صوت نقي وصدى احترافي بكل سهولة.",
        defaultPitch = 1.00f,
        defaultRate = 1.02f
    ),
    ALGERIAN(
        id = "ar_dz",
        titleRes = R.string.tts_dialect_algerian,
        primaryLocaleTag = "ar-DZ",
        fallbackTags = listOf("ar-DZ", "ar-MA", "ar-TN", "ar"),
        sampleText = "أهلا بكم خاوتنا في تطبيق فويس برو، رانا نجهزو لكم تسجيل صوتي صافي بزاف.",
        defaultPitch = 0.99f,
        defaultRate = 1.00f
    ),
    TUNISIAN(
        id = "ar_tn",
        titleRes = R.string.tts_dialect_tunisian,
        primaryLocaleTag = "ar-TN",
        fallbackTags = listOf("ar-TN", "ar-DZ", "ar-MA", "ar"),
        sampleText = "عسلامة ومرحبا بكم في فويس برو، توّة تنجم تعدّل الصوت وتزيد الصدى بسهولة.",
        defaultPitch = 1.02f,
        defaultRate = 1.00f
    ),
    ENGLISH_US(
        id = "en_us",
        titleRes = R.string.tts_dialect_english_us,
        primaryLocaleTag = "en-US",
        fallbackTags = listOf("en-US", "en-GB", "en"),
        sampleText = "Welcome to Voice Pro Studio. Your voice is crisp, warm, and ready for broadcast.",
        defaultPitch = 1.00f,
        defaultRate = 1.00f
    ),
    PERSIAN_IR(
        id = "fa_ir",
        titleRes = R.string.tts_dialect_persian,
        primaryLocaleTag = "fa-IR",
        fallbackTags = listOf("fa-IR", "fa"),
        sampleText = "به استودیوی ویس پرو خوش آمدید. صدای شما با کیفیت حرفه‌ای و شفاف آماده است.",
        defaultPitch = 1.00f,
        defaultRate = 0.96f
    ),
    TURKISH(
        id = "tr_tr",
        titleRes = R.string.tts_dialect_turkish,
        primaryLocaleTag = "tr-TR",
        fallbackTags = listOf("tr-TR", "tr"),
        sampleText = "Voice Pro stüdyosuna hoş geldiniz. Sesiniz artık net, güçlü ve yayına hazır.",
        defaultPitch = 1.00f,
        defaultRate = 1.00f
    )
}

/**
 * Free native Android TextToSpeech wrapper supporting live preview and WAV synthesis
 * across 10 Arabic/regional/international dialects for direct insertion into Voice Pro.
 */
class StudioTtsEngine(private val context: Context) {

    private val appContext = context.applicationContext
    private val initMutex = Mutex()

    @Volatile
    private var tts: TextToSpeech? = null
    @Volatile
    private var isInitialized = false

    suspend fun ensureInitialized(): Boolean = initMutex.withLock {
        if (isInitialized && tts != null) return@withLock true
        withContext(Dispatchers.Main) {
            val ready = CompletableDeferred<Boolean>()
            var instance: TextToSpeech? = null
            runCatching {
                instance = TextToSpeech(appContext) { status ->
                    ready.complete(status == TextToSpeech.SUCCESS)
                }
            }.onFailure {
                ready.complete(false)
            }
            val ok = withTimeoutOrNull(6000L) { ready.await() } ?: false
            if (ok && instance != null) {
                tts = instance
                isInitialized = true
                true
            } else {
                runCatching { instance?.shutdown() }
                false
            }
        }
    }

    private fun configureLocaleAndTone(
        engine: TextToSpeech,
        dialect: TtsDialect,
        speechRate: Float,
        pitch: Float
    ): Boolean {
        var applied = false
        for (tag in dialect.fallbackTags) {
            val loc = Locale.forLanguageTag(tag)
            val availability = runCatching { engine.isLanguageAvailable(loc) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = loc
                applied = true
                break
            }
        }
        if (!applied) {
            // Best-effort assignment even when system reports missing extra voice pack
            runCatching { engine.language = Locale.forLanguageTag(dialect.primaryLocaleTag) }
        }
        engine.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
        return applied
    }

    suspend fun speakPreview(
        text: String,
        dialect: TtsDialect,
        speechRate: Float,
        pitch: Float,
        onFinished: (Boolean, String?) -> Unit
    ) {
        if (!ensureInitialized()) {
            onFinished(false, "محرك النطق (Android Text-to-Speech) غير متاح على هذا الجهاز.")
            return
        }
        val engine = tts ?: return
        val utteranceId = "preview_${UUID.randomUUID()}"
        withContext(Dispatchers.Main) {
            configureLocaleAndTone(engine, dialect, speechRate, pitch)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) onFinished(true, null)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId) onFinished(false, "تعذر تشغيل معاينة الصوت بهذه اللغة.")
                }
                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) onFinished(false, "تعذر تشغيل معاينة الصوت (رمز $errorCode).")
                }
            })
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            val code = engine.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (code != TextToSpeech.SUCCESS) {
                onFinished(false, "تعذر بدء معاينة النطق على الجهاز.")
            }
        }
    }

    fun stopPreview() {
        runCatching { tts?.stop() }
    }

    suspend fun synthesizeToWavFile(
        text: String,
        dialect: TtsDialect,
        speechRate: Float,
        pitch: Float,
        outFile: File
    ): Result<File> {
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("يرجى إدخال نص قبل التوليد."))
        }
        if (!ensureInitialized()) {
            return Result.failure(IllegalStateException("محرك تحويل النص إلى كلام غير متاح. يرجى تفعيل Google Text-to-Speech في إعدادات الجهاز."))
        }
        val engine = tts ?: return Result.failure(IllegalStateException("TTS غير مهيأ"))
        val utteranceId = "synth_${UUID.randomUUID()}"

        val result = withTimeoutOrNull(25_000L) {
            suspendCancellableCoroutine<Result<File>> { cont ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        if (id == utteranceId && cont.isActive) {
                            if (outFile.exists() && outFile.length() > 44L) {
                                cont.resume(Result.success(outFile))
                            } else {
                                cont.resume(Result.failure(IllegalStateException("حزمة الصوت لهذه اللهجة غير مثبتة على الجهاز.")))
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id == utteranceId && cont.isActive) {
                            cont.resume(Result.failure(IllegalStateException("فشل توليد الصوت — تحقق من توفر حزمة اللغة في إعدادات النطق.")))
                        }
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId && cont.isActive) {
                            cont.resume(Result.failure(IllegalStateException("فشل توليد الصوت لهذه اللهجة (رمز $errorCode).")))
                        }
                    }
                })

                withContextOnMainImmediate(engine, dialect, speechRate, pitch, text, outFile, utteranceId) { ok ->
                    if (!ok && cont.isActive) {
                        cont.resume(Result.failure(IllegalStateException("تعذر بدء توليد ملف الصوت.")))
                    }
                }
            }
        }

        return result ?: Result.failure(IllegalStateException("انتهت مهلة توليد الصوت."))
    }

    private fun withContextOnMainImmediate(
        engine: TextToSpeech,
        dialect: TtsDialect,
        speechRate: Float,
        pitch: Float,
        text: String,
        outFile: File,
        utteranceId: String,
        onStarted: (Boolean) -> Unit
    ) {
        configureLocaleAndTone(engine, dialect, speechRate, pitch)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val res = runCatching {
            engine.synthesizeToFile(text.trim(), params, outFile, utteranceId)
        }.getOrDefault(TextToSpeech.ERROR)
        onStarted(res == TextToSpeech.SUCCESS)
    }

    fun release() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        isInitialized = false
    }
}
