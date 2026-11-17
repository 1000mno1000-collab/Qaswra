package com.example.ai

import android.content.Context
import android.util.Log
import com.example.audio.io.StereoAudioBuffer
import com.example.audio.io.WavAudioCodec
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TAG = "StudioAiRepository"

data class GroundingSource(
    val title: String,
    val uri: String,
    val domain: String
)

sealed interface AiTextState {
    data object Idle : AiTextState
    data object Loading : AiTextState
    data class Streaming(val text: String) : AiTextState
    data class Success(
        val text: String,
        val searchQueries: List<String> = emptyList(),
        val sources: List<GroundingSource> = emptyList()
    ) : AiTextState
    data class Error(val message: String) : AiTextState
}

/**
 * 100% Free-tier Firebase AI Logic repository.
 * Uses gemini-2.5-flash without systemInstruction parameter to ensure compatibility
 * across all free-tier projects without "Developer instruction is not enabled" errors.
 */
class StudioAiRepository(@Suppress("unused") private val context: Context) {

    // 1. Free-tier Audio Transcription Model (gemini-2.5-flash, no systemInstruction)
    private val transcribeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-2.5-flash"
        )
    }

    // 2. Free-tier Google Search Grounded Assistant (gemini-2.5-flash + googleSearch tool, no systemInstruction)
    private val searchGroundedModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-2.5-flash",
            tools = listOf(Tool.googleSearch())
        )
    }

    fun transcribeAudioStream(
        buffer: StereoAudioBuffer,
        languageHint: String = "العربية"
    ): Flow<AiTextState> = flow {
        emit(AiTextState.Loading)
        try {
            val wavBytes = withContext(Dispatchers.Default) {
                WavAudioCodec.encodeCompactMonoWavBytes(buffer, targetSampleRate = 16000)
            }
            if (wavBytes.size > 19 * 1024 * 1024) {
                emit(AiTextState.Error("حجم الملف الصوتي يتجاوز الحد المسموح (20MB). يرجى قص المقطع أولاً."))
                return@flow
            }

            // Pass instructions inside regular user content block (no systemInstruction)
            val promptContent = content {
                inlineData(wavBytes, "audio/wav")
                text(
                    "أنت مفرّغ صوتي احترافي دقيق. استمع للملف الصوتي المرفق وأخرج النص المنطوق فقط بدقة كاملة " +
                        "بلغته الأصلية ($languageHint أو أي لغة منطوقة) مع علامات الترقيم المناسبة، دون إضافة أي مقدمات أو تعليقات."
                )
            }

            val accumulated = StringBuilder()
            withTimeout(60_000L) {
                transcribeModel.generateContentStream(promptContent).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        emit(AiTextState.Streaming(accumulated.toString()))
                    }
                }
            }
            val finalResult = accumulated.toString().trim()
            if (finalResult.isNotEmpty()) {
                emit(AiTextState.Success(text = finalResult))
            } else {
                emit(AiTextState.Error("لم يتم العثور على كلام واضح في التسجيل الصوتي."))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Transcription timed out", e)
            emit(AiTextState.Error("انتهت مهلة التفريغ الصوتي بعد 60 ثانية. يرجى المحاولة بمقطع أقصر."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            emit(AiTextState.Error(mapAiExceptionToArabic(e, "فشل التفريغ الصوتي.")))
        }
    }.flowOn(Dispatchers.IO)

    fun askWithGoogleSearch(query: String): Flow<AiTextState> = flow {
        emit(AiTextState.Loading)
        try {
            val accumulated = StringBuilder()
            val searchQueries = linkedSetOf<String>()
            val sources = linkedMapOf<String, GroundingSource>()

            val promptContent = content {
                text(
                    "أنت مستشار هندسة صوتية وإنتاج موسيقي داخل تطبيق Voice Pro. أجب باللغة العربية بأسلوب عملي دقيق " +
                        "واستخدم بحث Google للتحقق من المعلومات والترددات وأساليب الهندسة الصوتية.\n\nسؤال المستخدم: $query"
                )
            }

            withTimeout(60_000L) {
                searchGroundedModel.generateContentStream(promptContent).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        emit(AiTextState.Streaming(accumulated.toString()))
                    }
                    val meta = chunk.candidates.firstOrNull()?.groundingMetadata
                    meta?.webSearchQueries?.let { queries ->
                        searchQueries.addAll(queries.filter { it.isNotBlank() })
                    }
                    meta?.groundingChunks?.forEach { gChunk ->
                        val web = gChunk.web
                        if (web != null && !web.uri.isNullOrBlank()) {
                            sources[web.uri!!] = GroundingSource(
                                title = web.title ?: web.domain ?: web.uri!!,
                                uri = web.uri!!,
                                domain = web.domain.orEmpty()
                            )
                        }
                    }
                }
            }

            val finalText = accumulated.toString().trim()
            if (finalText.isNotEmpty()) {
                emit(
                    AiTextState.Success(
                        text = finalText,
                        searchQueries = searchQueries.toList(),
                        sources = sources.values.toList()
                    )
                )
            } else {
                emit(AiTextState.Error("لم يتم إرجاع إجابة من النموذج."))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Search-grounded query timed out", e)
            emit(AiTextState.Error("انتهت مهلة البحث والرد بعد 60 ثانية."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Search-grounded query failed", e)
            emit(AiTextState.Error(mapAiExceptionToArabic(e, "فشل تنفيذ البحث الذكي.")))
        }
    }.flowOn(Dispatchers.IO)

    private fun mapAiExceptionToArabic(e: Throwable, fallback: String): String {
        val raw = (e.message ?: e.localizedMessage).orEmpty().lowercase()
        return when {
            "403" in raw || "app check" in raw || "appcheck" in raw ->
                "تعذر التحقق من أمان التطبيق (App Check). يرجى التأكد من اتصال الإنترنت وإعادة المحاولة."
            "quota" in raw || "429" in raw || "resource_exhausted" in raw ->
                "تم تجاوز حد الطلبات المؤقت للخدمة المجانية. يرجى الانتظار دقيقة ثم المحاولة."
            "network" in raw || "unable to resolve" in raw || "timeout" in raw || "connect" in raw ->
                "لا يوجد اتصال مستقر بالإنترنت. تحقق من الشبكة وحاول مرة أخرى."
            "developer instruction" in raw ->
                "تعذر معالجة صيغة الطلب الحالية، تم تحديث النموذج — حاول مرة أخرى."
            else -> e.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
        }
    }
}
