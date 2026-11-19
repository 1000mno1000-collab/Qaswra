package com.example.audio.aivoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EdgeNeuralTtsTest {

    @Test
    fun backendPayloadEncodesVoiceDesignAndCloneFieldsDeterministically() {
        val designReq = AiVoiceSynthRequest(
            engine = AiVoiceEngineKind.VOXCPM_2,
            text = "مرحباً بكم في استوديو الصوت!",
            language = AiVoiceLanguage.AR,
            dialect = AiArabicDialect.MSA,
            category = AiVoiceCategory.MALE,
            voiceDescriptionPrompt = "Warm broadcast announcer",
            emotion = AiVoiceEmotion.SERIOUS,
            emotionIntensity = 0.75f,
            pace = AiDeliveryPace.SLOW,
            speechRate = 0.90f,
            pitchOffsetSemitones = -1.0f,
            fixedSid = 2,
            fixedSeed = FIXED_DETERMINISTIC_SEED,
        )

        val designJson = AiVoicePromptBuilder.buildBackendPayloadJson(designReq, designReq.text)
        assertEquals("voxcpm2", designJson.getString("engine"))
        assertEquals("openbmb/VoxCPM2", designJson.getString("model_repo"))
        assertEquals("design", designJson.getString("voice_mode"))
        assertEquals("ar", designJson.getString("language"))
        assertEquals("serious", designJson.getString("emotion"))
        assertEquals(2, designJson.getInt("fixed_sid"))
        assertEquals(FIXED_DETERMINISTIC_SEED, designJson.getInt("seed"))

        val cloneJson = AiVoicePromptBuilder.buildBackendPayloadJson(
            request = designReq.copy(referenceTranscript = "مرجع نصي"),
            textChunk = designReq.text,
            referenceAudioBase64 = "UklGRg==",
        )
        assertEquals("clone", cloneJson.getString("voice_mode"))
        assertEquals("مرجع نصي", cloneJson.getString("reference_transcript"))
    }

    @Test
    fun engineCapabilitiesReflectOnlineAndOfflineModesAccurately() {
        assertTrue(AiVoiceEngineKind.VOXCPM_2.requiresRemoteGpu)
        assertTrue(AiVoiceEngineKind.VOXCPM_2.supportsVoiceCloning())
        assertTrue(AiVoiceEngineKind.VOXCPM_2.supportsVoiceDesignPrompt())
        assertEquals(AiVoiceLanguage.entries.size, AiVoiceEngineKind.VOXCPM_2.supportedLanguages().size)

        assertFalse(AiVoiceEngineKind.SONIX_LOCAL_STUDIO.requiresRemoteGpu)
        assertFalse(AiVoiceEngineKind.SONIX_LOCAL_STUDIO.supportsVoiceCloning())
        assertTrue(AiVoiceEngineKind.SONIX_LOCAL_STUDIO.supportsLanguage(AiVoiceLanguage.AR))
        assertTrue(AiVoiceEngineKind.SONIX_LOCAL_STUDIO.supportsLanguage(AiVoiceLanguage.EN))
    }
}
