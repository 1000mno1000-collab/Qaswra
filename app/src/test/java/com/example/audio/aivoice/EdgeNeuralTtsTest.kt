package com.example.audio.aivoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeNeuralTtsTest {

    @Test
    fun secMsGecV2MatchesOfficialEdgeTtsReferenceDigest() {
        val token = EdgeNeuralDrm.generateSecMsGecV2(1795020300L)
        assertEquals(
            "35622E61597095DE-A53D6947A50C9CE5A195D2B71F3832A0373B183DD5F9AF7DEEF1D9040D82720F",
            token,
        )
    }

    @Test
    fun muidAndClockSkewHeaderParsingBehaveAsExpected() {
        val muid = EdgeNeuralDrm.generateMuid()
        assertEquals(32, muid.length)
        assertTrue(muid.all { it in '0'..'9' || it in 'A'..'F' })

        assertTrue(EdgeNeuralDrm.updateClockSkewFromDateHeader("Wed, 18 Nov 2026 16:50:00 GMT"))
        assertFalse(EdgeNeuralDrm.updateClockSkewFromDateHeader("   "))
    }

    @Test
    fun voiceResolverMapsArabicDialectsAndCategoriesToVerifiedEdgeNeuralVoices() {
        assertEquals(
            "ar-IQ-BasselNeural",
            EdgeNeuralVoiceResolver.resolveVoice(AiVoiceLanguage.AR, AiArabicDialect.IRAQI, AiVoiceCategory.MALE),
        )
        assertEquals(
            "ar-IQ-RanaNeural",
            EdgeNeuralVoiceResolver.resolveVoice(AiVoiceLanguage.AR, AiArabicDialect.IRAQI, AiVoiceCategory.FEMALE),
        )
        assertEquals(
            "ar-AE-HamdanNeural",
            EdgeNeuralVoiceResolver.resolveVoice(AiVoiceLanguage.AR, AiArabicDialect.GULF, AiVoiceCategory.NARRATOR),
        )
        assertEquals(
            "ar-SA-ZariyahNeural",
            EdgeNeuralVoiceResolver.resolveVoice(AiVoiceLanguage.AR, AiArabicDialect.SAUDI, AiVoiceCategory.CHILD),
        )
        assertEquals(
            "ar-SY-LaithNeural",
            EdgeNeuralVoiceResolver.resolveVoice(AiVoiceLanguage.AR, AiArabicDialect.LEVANTINE, AiVoiceCategory.ELDERLY),
        )
        assertEquals(
            "ar-EG-SalmaNeural",
            EdgeNeuralVoiceResolver.resolveVoice(AiVoiceLanguage.AR, AiArabicDialect.EGYPTIAN, AiVoiceCategory.FEMALE),
        )
        assertEquals(
            "ar-SA-HamedNeural",
            EdgeNeuralVoiceResolver.resolveVoice(AiVoiceLanguage.AR, AiArabicDialect.MSA, AiVoiceCategory.MALE),
        )
    }

    @Test
    fun ssmlBuilderEscapesXmlAndEncodesProsodyRateAndPitch() {
        val req = AiVoiceSynthRequest(
            engine = AiVoiceEngineKind.EDGE_NEURAL_FREE,
            text = "مرحباً <بكم> & أهلاً!",
            language = AiVoiceLanguage.AR,
            dialect = AiArabicDialect.IRAQI,
            category = AiVoiceCategory.ELDERLY,
            voiceDescriptionPrompt = "",
            emotion = AiVoiceEmotion.SAD,
            emotionIntensity = 0.7f,
            pace = AiDeliveryPace.SLOW,
            pitchOffsetSemitones = -1.0f,
        )
        val rate = EdgeNeuralVoiceResolver.formatRateString(req)
        val pitch = EdgeNeuralVoiceResolver.formatPitchString(req)
        val ssml = EdgeNeuralVoiceResolver.buildSsml(req.text, "ar-IQ-BasselNeural", rate, pitch)

        assertTrue(rate.startsWith("-"))
        assertTrue(pitch.startsWith("-") && pitch.endsWith("Hz"))
        assertTrue(ssml.contains("xml:lang='ar-IQ'"))
        assertTrue(ssml.contains("<voice name='ar-IQ-BasselNeural'>"))
        assertTrue(ssml.contains("&lt;بكم&gt; &amp; أهلاً!"))
        assertFalse(AiVoiceEngineKind.EDGE_NEURAL_FREE.requiresRemoteGpu)
        assertEquals("edge_neural_free", AiVoiceEngineKind.EDGE_NEURAL_FREE.id)
    }
}
