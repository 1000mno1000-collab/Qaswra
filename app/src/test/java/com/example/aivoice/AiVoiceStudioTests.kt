package com.example.aivoice

import com.example.audio.aivoice.AiArabicDialect
import com.example.audio.aivoice.AiDeliveryPace
import com.example.audio.aivoice.AiDialogueTurn
import com.example.audio.aivoice.AiVoiceAudioStitcher
import com.example.audio.aivoice.AiVoiceBackendHealth
import com.example.audio.aivoice.AiVoiceCategory
import com.example.audio.aivoice.AiVoiceCharacter
import com.example.audio.aivoice.AiVoiceEmotion
import com.example.audio.aivoice.AiVoiceEngineKind
import com.example.audio.aivoice.AiVoiceLanguage
import com.example.audio.aivoice.AiVoicePromptBuilder
import com.example.audio.aivoice.AiVoiceStudioUiState
import com.example.audio.aivoice.AiVoiceSynthRequest
import com.example.audio.aivoice.BUILT_IN_AI_VOICE_CHARACTERS
import com.example.audio.aivoice.WavStitchSegment
import com.example.audio.writeStereoWavFile
import com.example.model.StereoAudioBuffer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AiVoiceStudioTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun voxCpmInstructionIncludesCategoryLanguageEmotionAndFreeText() {
        val request = AiVoiceSynthRequest(
            engine = AiVoiceEngineKind.VOXCPM_2,
            text = "مرحباً بكم في الاستوديو.",
            language = AiVoiceLanguage.AR,
            dialect = AiArabicDialect.MSA,
            category = AiVoiceCategory.MALE,
            voiceDescriptionPrompt = "رجل عراقي في الأربعين بصوت دافئ عميق",
            emotion = AiVoiceEmotion.SAD,
            emotionIntensity = 0.85f,
            pace = AiDeliveryPace.SLOW,
        )

        val instruction = AiVoicePromptBuilder.buildVoxCpmInstruction(request)

        assertTrue(instruction.contains("male adult speaker"))
        assertTrue(instruction.contains("speaking Arabic"))
        assertTrue(instruction.contains("strong intense"))
        assertTrue(instruction.contains("melancholic tone"))
        assertTrue(instruction.contains("measured unhurried pacing"))
        assertTrue(instruction.contains("رجل عراقي في الأربعين"))
    }

    @Test
    fun chunkSplitterSplitsLongArabicParagraphsOnSentenceBoundaries() {
        val text = "الجملة الأولى في المشهد الدرامي تتحدث عن البداية! " +
            "هل سمعت الصوت القادِم من البعيد؟ " +
            "هذه جملة ثالثة طويلة قليلاً لاختبار التقطيع المنطقي للنصوص العربية دون تقطيع الكلمات في المنتصف؛ " +
            "والجملة الرابعة تختم الحوار بهدوء."

        val chunks = AiVoicePromptBuilder.splitTextIntoChunks(text, maxCharsPerChunk = 95)

        assertTrue("Expected multiple chunks for long script", chunks.size >= 3)
        assertTrue(chunks.all { it.length <= 95 })
        assertTrue(chunks.joinToString(" ").contains("تختم الحوار بهدوء."))
    }

    @Test
    fun wavStitcherCombinesDifferentSampleRatesAndAddsPreciseSilencePadding() {
        val sr1 = 24000
        val frames1 = sr1 / 4 // 250ms tone
        val left1 = FloatArray(frames1) { i -> 0.5f * sin(2.0 * PI * 440.0 * i / sr1).toFloat() }
        val buf1 = StereoAudioBuffer(left1, left1.copyOf(), sr1)
        val f1 = tempFolder.newFile("turn1_24k.wav")
        writeStereoWavFile(f1, buf1)

        val sr2 = 48000
        val frames2 = sr2 / 4 // 250ms tone
        val left2 = FloatArray(frames2) { i -> 0.4f * sin(2.0 * PI * 660.0 * i / sr2).toFloat() }
        val buf2 = StereoAudioBuffer(left2, left2.copyOf(), sr2)
        val f2 = tempFolder.newFile("turn2_48k.wav")
        writeStereoWavFile(f2, buf2)

        val stitchedOut = tempFolder.newFile("dialogue_stitched_48k.wav")
        val stitched = AiVoiceAudioStitcher.stitchWavSegments(
            segments = listOf(
                WavStitchSegment(f1, pauseAfterMs = 500),
                WavStitchSegment(f2, pauseAfterMs = 400),
            ),
            outFile = stitchedOut,
            targetSampleRate = 48000,
        )

        assertEquals(48000, stitched.sampleRate)
        assertTrue("Expected stitched duration ~1000ms, got ${stitched.durationMs}", stitched.durationMs in 980L..1020L)

        val parsed = AiVoiceAudioStitcher.readSimplePcmWav(stitchedOut)
        assertNotNull(parsed)
        assertEquals(48000, parsed!!.sampleRate)

        val gapIndex = (48000 * 0.50).toInt()
        assertTrue("Expected silent pause in inter-turn gap", abs(parsed.left[gapIndex]) < 0.005f)
        val turn1Sample = (48000 * 0.125).toInt()
        val turn2Sample = (48000 * 0.875).toInt()
        assertTrue(abs(parsed.left[turn1Sample]) > 0.05f || abs(parsed.left[turn1Sample + 10]) > 0.05f)
        assertTrue(abs(parsed.left[turn2Sample]) > 0.05f || abs(parsed.left[turn2Sample + 10]) > 0.05f)
    }

    @Test
    fun builtInCharactersAndDialogueTurnsSerializeRoundTripCleanly() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encodedChars = json.encodeToString(BUILT_IN_AI_VOICE_CHARACTERS)
        val decodedChars = json.decodeFromString<List<AiVoiceCharacter>>(encodedChars)

        assertEquals(BUILT_IN_AI_VOICE_CHARACTERS.size, decodedChars.size)
        assertTrue(decodedChars.any { it.category == AiVoiceCategory.MALE })
        assertTrue(decodedChars.any { it.category == AiVoiceCategory.FEMALE })
        assertTrue(decodedChars.all { it.preferredEngine == AiVoiceEngineKind.VOXCPM_2 })

        val turn = AiDialogueTurn(
            id = "t1",
            characterId = decodedChars.first().id,
            text = "تجربة حوار",
            emotion = AiVoiceEmotion.WHISPER,
            emotionIntensity = 0.72f,
            pace = AiDeliveryPace.DRAMATIC_PAUSES,
            pauseAfterMs = 600,
        )
        val decodedTurn = json.decodeFromString<AiDialogueTurn>(json.encodeToString(turn))
        assertEquals(turn, decodedTurn)
    }

    @Test
    fun allSixStudioEmotionsArePresent() {
        assertEquals(6, AiVoiceEmotion.entries.size)
        assertTrue(AiVoiceEmotion.entries.contains(AiVoiceEmotion.NEUTRAL))
        assertTrue(AiVoiceEmotion.entries.contains(AiVoiceEmotion.EXCITED))
        assertTrue(AiVoiceEmotion.entries.contains(AiVoiceEmotion.CALM))
        assertTrue(AiVoiceEmotion.entries.contains(AiVoiceEmotion.SAD))
        assertTrue(AiVoiceEmotion.entries.contains(AiVoiceEmotion.SERIOUS))
        assertTrue(AiVoiceEmotion.entries.contains(AiVoiceEmotion.WHISPER))
    }

    @Test
    fun voxCpm2OfficialRepoAndHealthUiStateDefaultsMatchSpec() {
        assertEquals("openbmb/VoxCPM2", AiVoiceEngineKind.VOXCPM_2.modelRepoId)
        assertEquals("voxcpm2", AiVoiceEngineKind.VOXCPM_2.id)
        assertTrue(AiVoiceEngineKind.VOXCPM_2.requiresRemoteGpu)

        val defaultHealth = AiVoiceBackendHealth()
        assertEquals(false, defaultHealth.voxCpm2Ready)
        assertEquals(false, defaultHealth.voxcpm2PackageInstalled)
        assertEquals(false, defaultHealth.voxcpm2Loaded)
        assertEquals(false, defaultHealth.cudaAvailable)
        assertEquals("", defaultHealth.voxcpm2LoadError)

        val defaultUi = AiVoiceStudioUiState()
        assertEquals("", defaultUi.backendServerUrl)
        assertEquals("", defaultUi.backendBearerToken)
        assertEquals(AiVoiceEngineKind.VOXCPM_2, defaultUi.selectedEngine)
    }

    @Test
    fun userSelectableEnginesContainPrimaryVoxCpm2AndOfflineLocalStudio() {
        val selectable = AiVoiceEngineKind.entries.filter { it.isUserSelectable }
        assertTrue(selectable.contains(AiVoiceEngineKind.VOXCPM_2))
        assertTrue(selectable.contains(AiVoiceEngineKind.SONIX_LOCAL_STUDIO))
    }
}
