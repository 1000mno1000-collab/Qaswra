package com.example.data.ai

import com.example.model.AppLanguage
import com.example.model.SearchSourceLink
import com.example.model.StereoAudioBuffer

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

    suspend fun askSearchAssistant(
        query: String,
        language: AppLanguage,
    ): AiResponse
}

/**
 * Default client implementation when Firebase AI Logic (ai_logic) has not been provisioned
 * for the Android build environment yet. Audio DSP tools, Voice Purifier, Echo/Reverb,
 * and Formant Voice Transformer run on-device, while AI & Neural TTS features are 100% online.
 */
class FirebaseUnprovisionedAiRepository : AiRepository {
    override suspend fun transcribeAudio(
        buffer: StereoAudioBuffer,
        language: AppLanguage,
    ): AiResponse {
        val msg = when (language) {
            AppLanguage.EN ->
                "Cloud AI transcription works 100% online and requires Firebase AI Logic (Gemini) provisioning with a valid debug.keystore SHA-1 fingerprint and google-services.json."
            AppLanguage.FA ->
                "تبدیل گفتار ابری به متن به صورت ۱۰۰٪ آنلاین کار می‌کند و نیازمند فعال‌سازی Firebase AI Logic (Gemini) با فایل google-services.json و اثر انگشت SHA-1 است."
            AppLanguage.AR ->
                "يعمل التفريغ الصوتي بالذكاء الاصطناعي أونلاين 100% فقط ويتطلب تفعيل Firebase AI Logic (Gemini) وإضافة بصمة SHA-1 وتحديث ملف google-services.json."
        }
        return AiResponse.NeedsCloudProvisioning(msg)
    }

    override suspend fun askSearchAssistant(
        query: String,
        language: AppLanguage,
    ): AiResponse {
        val msg = when (language) {
            AppLanguage.EN ->
                "Live web-grounded AI search works 100% online and requires Firebase AI Logic (Gemini) provisioning in Android. Try Smart Auto-Master for instant spectral and dynamics optimization."
            AppLanguage.FA ->
                "جستجوی زنده مبتنی بر وب به صورت ۱۰۰٪ آنلاین کار می‌کند و نیازمند فعال‌سازی Firebase AI Logic (Gemini) است."
            AppLanguage.AR ->
                "يعمل المساعد البحثي السحابي أونلاين 100% فقط ويتطلب تفعيل Firebase AI Logic (Gemini). يمكنك أيضاً استخدام مهندس الماسترينغ الذكي لضبط الصوتيات."
        }
        return AiResponse.NeedsCloudProvisioning(msg)
    }
}
