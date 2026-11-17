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
 * for the Android build environment yet. Smart Auto-Master, Voice Purifier, Echo/Reverb,
 * 13-slot DSP chain, Formant Voice Transformer, and native 10-dialect TTS all run fully on-device.
 */
class FirebaseUnprovisionedAiRepository : AiRepository {
    override suspend fun transcribeAudio(
        buffer: StereoAudioBuffer,
        language: AppLanguage,
    ): AiResponse {
        val msg = when (language) {
            AppLanguage.EN ->
                "Cloud transcription requires Firebase AI Logic (Gemini) provisioning with a valid debug.keystore SHA fingerprint and google-services.json. All local DSP tools, Smart Auto-Master, Formant Transformer, and native TTS work offline."
            AppLanguage.FA ->
                "تبدیل گفتار ابری به متن نیازمند فعال‌سازی Firebase AI Logic (Gemini) با فایل google-services.json و کلید امضا است. مسترینگ هوشمند، حذف نویز، تغییر شخصیت صدا و TTS آفلاین فعال هستند."
            AppLanguage.AR ->
                "يتطلب التفريغ الصوتي السحابي تفعيل Firebase AI Logic (Gemini) وتزويد التطبيق بملف google-services.json وبصمة SHA. جميع أدوات المعالجة الصوتية، الماسترينغ الذكي، محوّل الشخصيات، وتحويل النص إلى صوت تعمل محلياً بالكامل."
        }
        return AiResponse.NeedsCloudProvisioning(msg)
    }

    override suspend fun askSearchAssistant(
        query: String,
        language: AppLanguage,
    ): AiResponse {
        val msg = when (language) {
            AppLanguage.EN ->
                "Live web-grounded AI search requires Firebase AI Logic (Gemini) provisioning in Android. Try Smart Auto-Master for instant offline spectral and dynamics recommendations."
            AppLanguage.FA ->
                "جستجوی زنده مبتنی بر وب نیازمند فعال‌سازی Firebase AI Logic (Gemini) است. برای تحلیل فوری صدا از مسترینگ هوشمند آفلاین استفاده کنید."
            AppLanguage.AR ->
                "يتطلب البحث السحابي المباشر تفعيل Firebase AI Logic (Gemini). يمكنك استخدام مهندس الماسترينغ الذكي حالياً لتحليل الصوت وضبط الإعدادات محلياً فوراً."
        }
        return AiResponse.NeedsCloudProvisioning(msg)
    }
}
