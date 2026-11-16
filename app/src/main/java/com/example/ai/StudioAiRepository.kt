package com.example.ai

import android.content.Context
import android.util.Log
import com.example.audio.io.StereoAudioBuffer
import com.example.audio.io.WavAudioCodec
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
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

sealed interface MusicGenState {
    data object Idle : MusicGenState
    data object Loading : MusicGenState
    data class Success(
        val buffer: StereoAudioBuffer,
        val notes: String?,
        val modelUsed: String
    ) : MusicGenState
    data class Error(val message: String) : MusicGenState
}

enum class LyriaTrackLength(val modelName: String, val arabicLabel: String, val subtitle: String) {
    SHORT_CLIP(
        modelName = "lyria-3-clip-preview",
        arabicLabel = "مقطع قصير (حتى ٣٠ ثانية)",
        subtitle = "نموذج lyria-3-clip-preview — مثالي للحلقات والإنترو"
    ),
    FULL_TRACK(
        modelName = "lyria-3-pro-preview",
        arabicLabel = "مقطوعة موسيقية كاملة",
        subtitle = "نموذج lyria-3-pro-preview — بنية موسيقية متكاملة"
    )
}

class StudioAiRepository(private val context: Context) {

    // 1. Audio Transcription Model (gemini-3.5-transcribe)
    private val transcribeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-transcribe",
            systemInstruction = content {
                text(
                    "أنت مفرّغ صوتي احترافي دقيق. استمع للتسجيل الصوتي وأخرج النص المنطوق بدقة كاملة " +
                        "بلغته الأصلية (العربية أو غيرها) مع علامات الترقيم المناسبة دون إضافة تعليقات خارجية."
                )
            }
        )
    }

    // 2. Music Generation Models (lyria-3-clip-preview & lyria-3-pro-preview)
    private val lyriaClipModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "lyria-3-clip-preview",
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO, ResponseModality.TEXT)
            }
        )
    }

    private val lyriaProModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "lyria-3-pro-preview",
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO, ResponseModality.TEXT)
            }
        )
    }

    // 3. Google Search Grounded Assistant (gemini-4-hydrogen + googleSearch tool)
    private val searchGroundedModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-4-hydrogen",
            tools = listOf(Tool.googleSearch()),
            systemInstruction = content {
                text(
                    "أنت مستشار هندسة صوتية وإنتاج موسيقي داخل تطبيق Voice Pro. أجب باللغة العربية بأسلوب عملي دقيق " +
                        "واستخدم أداة البحث في Google للتحقق من المعلومات الحديثة، والمعدات، والترددات، وأساليب الهندسة الصوتية."
                )
            }
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

            val promptContent = content {
                inlineData(wavBytes, "audio/wav")
                text("قم بتفريغ هذا المقطع الصوتي إلى نص دقيق (اللغة المرجحة: $languageHint).")
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
            emit(AiTextState.Error(e.localizedMessage ?: "فشل التفريغ الصوتي."))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateMusic(
        prompt: String,
        trackLength: LyriaTrackLength
    ): MusicGenState = withContext(Dispatchers.IO) {
        try {
            val model = when (trackLength) {
                LyriaTrackLength.SHORT_CLIP -> lyriaClipModel
                LyriaTrackLength.FULL_TRACK -> lyriaProModel
            }
            val response = withTimeout(60_000L) {
                model.generateContent(prompt)
            }
            val audioPart = response.inlineDataParts.firstOrNull {
                it.mimeType.startsWith("audio/")
            } ?: response.inlineDataParts.firstOrNull()

            if (audioPart == null) {
                val fallbackText = response.text
                return@withContext MusicGenState.Error(
                    if (!fallbackText.isNullOrBlank()) {
                        "لم يُعد النموذج ملفاً صوتياً: $fallbackText"
                    } else {
                        "لم يتم توليد بيانات صوتية من النموذج."
                    }
                )
            }

            val decoded = WavAudioCodec.decodeAudioBytes(
                context = context,
                bytes = audioPart.inlineData,
                mimeType = audioPart.mimeType
            ) ?: return@withContext MusicGenState.Error(
                "تعذر فك تشفير الصوت المولد (${audioPart.mimeType})."
            )

            MusicGenState.Success(
                buffer = decoded,
                notes = response.text?.takeIf { it.isNotBlank() },
                modelUsed = trackLength.modelName
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Music generation timed out", e)
            MusicGenState.Error("انتهت مهلة توليد الموسيقى بعد 60 ثانية. حاول تبسيط الوصف أو اختيار مقطع قصير.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Music generation failed", e)
            MusicGenState.Error(e.localizedMessage ?: "فشل توليد الموسيقى.")
        }
    }

    fun askWithGoogleSearch(query: String): Flow<AiTextState> = flow {
        emit(AiTextState.Loading)
        try {
            val accumulated = StringBuilder()
            val searchQueries = linkedSetOf<String>()
            val sources = linkedMapOf<String, GroundingSource>()

            withTimeout(60_000L) {
                searchGroundedModel.generateContentStream(query).collect { chunk ->
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
            emit(AiTextState.Error(e.localizedMessage ?: "فشل تنفيذ البحث الذكي."))
        }
    }.flowOn(Dispatchers.IO)
}
