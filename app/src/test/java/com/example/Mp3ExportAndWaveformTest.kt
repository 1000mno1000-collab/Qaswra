package com.example

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.audio.dsp.DspPipeline
import com.example.audio.export.Mp3ExportCoordinator
import com.example.audio.export.Mp3ExportRequest
import com.example.audio.export.Mp3ExportService
import com.example.audio.export.Mp3ExportState
import com.example.audio.io.Mp3AudioEncoder
import com.example.audio.io.StereoAudioBuffer
import com.example.model.BuiltInPresets
import com.example.model.DspConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Mp3ExportAndWaveformTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private fun generateSineBuffer(
        durationSec: Float = 0.5f,
        freqHz: Float = 440f,
        amp: Float = 0.5f
    ): StereoAudioBuffer {
        val sr = 44100
        val count = (sr * durationSec).toInt()
        val mono = FloatArray(count) { i ->
            (sin(2.0 * PI * freqHz * i / sr) * amp).toFloat()
        }
        return StereoAudioBuffer.fromMono(mono, sr)
    }

    private fun containsMpegFrameSync(bytes: ByteArray): Boolean {
        for (i in 0 until bytes.size - 1) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                return true
            }
        }
        return false
    }

    @Test
    fun `mp3 encoder encodes stereo buffer to valid mpeg frames with monotonic progress`() {
        val src = generateSineBuffer(durationSec = 0.6f, freqHz = 440f, amp = 0.55f)
        val progressValues = mutableListOf<Float>()

        val mp3Bytes = Mp3AudioEncoder.encodeStereoBufferToMp3Bytes(
            buffer = src,
            bitrateKbps = 192
        ) { p ->
            progressValues.add(p)
        }

        assertTrue("Encoded MP3 should not be empty", mp3Bytes.size > 1024)
        assertTrue("Encoded MP3 must contain valid MPEG frame sync", containsMpegFrameSync(mp3Bytes))
        assertTrue("Progress callback should fire", progressValues.isNotEmpty())
        assertEquals(1.0f, progressValues.last(), 0.001f)
        for (i in 1 until progressValues.size) {
            assertTrue("Progress should be monotonic", progressValues[i] >= progressValues[i - 1])
        }
    }

    @Test
    fun `dsp offline render followed by mp3 stream encoding produces valid audio`() {
        val src = generateSineBuffer(durationSec = 0.4f, freqHz = 523.25f, amp = 0.45f)
        val config = BuiltInPresets.all.first().config
        val rendered = DspPipeline(src.sampleRate).renderOffline(src, config)

        val out = ByteArrayOutputStream()
        Mp3AudioEncoder.encodeStereoBufferToMp3Stream(
            buffer = rendered,
            output = out,
            bitrateKbps = 192
        )
        val bytes = out.toByteArray()
        assertTrue(bytes.size > 1024)
        assertTrue(containsMpegFrameSync(bytes))
    }

    @Test
    fun `mp3 export service processes request in background and emits completed state`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val src = generateSineBuffer(durationSec = 0.35f, freqHz = 440f, amp = 0.5f)
        val request = Mp3ExportRequest(
            sourceBuffer = src,
            dspConfig = DspConfig(),
            destinationUri = null,
            fileName = "Studio_Test_Clip",
            isShare = true
        )

        Mp3ExportCoordinator.acknowledgeTerminalState()
        Mp3ExportCoordinator.submitAndStart(context, request)

        val controller = Robolectric.buildService(Mp3ExportService::class.java, Intent(context, Mp3ExportService::class.java))
        controller.create().startCommand(0, 1)

        val deadline = System.currentTimeMillis() + 8000L
        var finalState: Mp3ExportState = Mp3ExportCoordinator.state.value
        while (finalState !is Mp3ExportState.Completed && finalState !is Mp3ExportState.Error && System.currentTimeMillis() < deadline) {
            Thread.sleep(40)
            finalState = Mp3ExportCoordinator.state.value
        }

        assertTrue("Expected Completed state, got $finalState", finalState is Mp3ExportState.Completed)
        val completed = finalState as Mp3ExportState.Completed
        assertEquals("Studio_Test_Clip", completed.fileName)
        assertTrue(completed.isShare)

        val exportedBytes = context.contentResolver.openInputStream(completed.outputUri)?.use { it.readBytes() }
        assertTrue("Exported MP3 URI should be readable", exportedBytes != null && exportedBytes.size > 1024)
        assertTrue(containsMpegFrameSync(exportedBytes!!))

        Mp3ExportCoordinator.acknowledgeTerminalState()
        assertTrue(Mp3ExportCoordinator.state.value is Mp3ExportState.Idle)
        controller.destroy()
    }

    @Test
    fun `home recording and projects screens display real time waveform and mp3 export actions`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_realtime_waveform").assertIsDisplayed()

        // Navigate to Recording Studio and verify real-time waveform Canvas node
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("section_recording"))
        composeTestRule.onNodeWithTag("section_recording").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("realtime_waveform_canvas").performScrollTo().assertIsDisplayed()

        // Navigate back to Home
        composeTestRule.onNodeWithTag("back_button").performClick()
        composeTestRule.waitForIdle()

        // Navigate to Projects & Export and verify MP3 export & share buttons
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("section_projects_export"))
        composeTestRule.onNodeWithTag("section_projects_export").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("export_mp3_button"))
        composeTestRule.onNodeWithTag("export_mp3_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("share_mp3_button").assertIsDisplayed()
    }
}
