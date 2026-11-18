package com.example.data.ai

import android.content.Context
import com.example.audio.ai.SonixUnifiedAIEngine
import com.example.model.AppLanguage
import com.example.model.SearchSourceLink
import com.example.model.StereoAudioBuffer
import java.io.File

sealed interface AiResponse {
    data class Success(
        val text: String,
        val searchQueries: List<String> = emptyList(),
        val sources: List<SearchSourceLink> = emptyList(),
    ) : AiResponse

    data class NeedsCloudProvisioning(val message: String) : AiResponse
}

interface AiRepository {
    suspend fun transcribeAudio(
        buffer: StereoAudioBuffer,
        language: AppLanguage,
    ): AiResponse

    suspend fun transcribeAudioSource(
        buffer: StereoAudioBuffer?,
        sourceFile: File?,
        language: AppLanguage,
        onChunkProgress: ((String) -> Unit)? = null,
    ): AiResponse = if (buffer != null) {
        transcribeAudio(buffer, language)
    } else {
        AiResponse.NeedsCloudProvisioning("لا يوجد مقطع صوتي حالياً لتفريغه.")
    }

    suspend fun askSearchAssistant(
        query: String,
        language: AppLanguage,
    ): AiResponse
}

/**
 * المستودع الموحد للذكاء الاصطناعي في Sonix — متصل أونلاين 100% ومجاناً عبر [SonixUnifiedAIEngine]
 * (Gemini 2.5 Flash Multimodal Audio API) دون مطالبة المستخدم بأي مفاتيح.
 */
class SonixUnifiedAiRepository(
    private val context: Context,
) : AiRepository {

    override suspend fun transcribeAudio(
        buffer: StereoAudioBuffer,
        language: AppLanguage,
    ): AiResponse = transcribeAudioSource(
        buffer = buffer,
        sourceFile = null,
        language = language,
        onChunkProgress = null,
    )

    override suspend fun transcribeAudioSource(
        buffer: StereoAudioBuffer?,
        sourceFile: File?,
        language: AppLanguage,
        onChunkProgress: ((String) -> Unit)?,
    ): AiResponse {
        return when (
            val res = SonixUnifiedAIEngine.transcribeAudioMultimodal(
                context = context,
                buffer = buffer,
                sourceFile = sourceFile,
                language = language,
                onChunkProgress = onChunkProgress,
            )
        ) {
            is SonixUnifiedAIEngine.UnifiedTextResult.Success -> AiResponse.Success(text = res.text)
            is SonixUnifiedAIEngine.UnifiedTextResult.Offline -> AiResponse.NeedsCloudProvisioning(res.message)
            is SonixUnifiedAIEngine.UnifiedTextResult.Error -> AiResponse.NeedsCloudProvisioning(res.message)
        }
    }

    override suspend fun askSearchAssistant(
        query: String,
        language: AppLanguage,
    ): AiResponse {
        return when (
            val res = SonixUnifiedAIEngine.askAudioAssistant(
                context = context,
                query = query,
                language = language,
            )
        ) {
            is SonixUnifiedAIEngine.UnifiedTextResult.Success -> AiResponse.Success(text = res.text)
            is SonixUnifiedAIEngine.UnifiedTextResult.Offline -> AiResponse.NeedsCloudProvisioning(res.message)
            is SonixUnifiedAIEngine.UnifiedTextResult.Error -> AiResponse.NeedsCloudProvisioning(res.message)
        }
    }
}

class FirebaseUnprovisionedAiRepository : AiRepository {
    override suspend fun transcribeAudio(
        buffer: StereoAudioBuffer,
        language: AppLanguage,
    ): AiResponse = AiResponse.NeedsCloudProvisioning("Use SonixUnifiedAiRepository")

    override suspend fun askSearchAssistant(
        query: String,
        language: AppLanguage,
    ): AiResponse = AiResponse.NeedsCloudProvisioning("Use SonixUnifiedAiRepository")
}
