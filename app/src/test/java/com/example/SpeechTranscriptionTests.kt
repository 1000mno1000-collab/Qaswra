package com.example

import android.content.Context
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.example.audio.StudioSpeechTranscriber
import com.example.audio.SttBackendMode
import com.example.audio.computeRetryBackoffMs
import com.example.audio.isRecoverableSpeechError
import com.example.audio.mergeTranscriptText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechTranscriptionTests {

    @Test
    fun errorCode11ServerDisconnectedIsRecoverableWithExponentialBackoff() {
        assertTrue("Code 11 (ERROR_SERVER_DISCONNECTED) must recover automatically", isRecoverableSpeechError(11))
        assertTrue("Code 10 (ERROR_TOO_MANY_REQUESTS) must recover with backoff", isRecoverableSpeechError(10))
        assertTrue(isRecoverableSpeechError(SpeechRecognizer.ERROR_NO_MATCH))
        assertTrue(isRecoverableSpeechError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertFalse(isRecoverableSpeechError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))

        val b0 = computeRetryBackoffMs(11, 0)
        val b1 = computeRetryBackoffMs(11, 1)
        val b2 = computeRetryBackoffMs(11, 2)
        val b5 = computeRetryBackoffMs(11, 5)
        assertTrue("Expected escalating backoff: b0=$b0 b1=$b1 b2=$b2", b0 < b1 && b1 < b2)
        assertTrue("Backoff must stay bounded <= 3200ms", b5 <= 3_200L)
    }

    @Test
    fun mergeTranscriptTextDeduplicatesOverlapWithoutDroppingWords() {
        assertEquals("شلونكم اليوم", mergeTranscriptText("", "شلونكم اليوم"))
        assertEquals(
            "مرحباً بكم في استوديو الصوت الذكي",
            mergeTranscriptText("مرحباً بكم في", "بكم في استوديو الصوت الذكي"),
        )
        assertEquals(
            "أهلاً وسهلاً بكم",
            mergeTranscriptText("أهلاً وسهلاً بكم", "أهلاً وسهلاً بكم"),
        )
        assertEquals(
            "السلام عليكم شلون الصحة",
            mergeTranscriptText("السلام عليكم", "شلون الصحة"),
        )
    }

    @Test
    fun transcriberStateMaintainsEditsAndStopCommitsWithoutOverlap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transcriber = StudioSpeechTranscriber(context)
        try {
            transcriber.setLocale("ar-IQ")
            transcriber.setBackendMode(SttBackendMode.AUTO)
            transcriber.setEditedText("مرحبا")
            assertEquals("ar-IQ", transcriber.state.value.localeTag)
            assertEquals("مرحبا", transcriber.state.value.displayText)

            transcriber.stop()
            assertFalse(transcriber.state.value.isListening)
            assertEquals("مرحبا", transcriber.state.value.committedText)

            transcriber.clear()
            assertEquals("", transcriber.state.value.displayText)
        } finally {
            transcriber.destroy()
        }
    }
}
