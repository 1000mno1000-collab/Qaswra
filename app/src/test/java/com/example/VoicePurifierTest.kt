package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.audio.dsp.NoiseReductionProcessor
import com.example.audio.dsp.VoicePurifierEngine
import com.example.audio.dsp.VoicePurifyPreset
import com.example.audio.io.StereoAudioBuffer
import com.example.model.DspConfig
import com.example.model.NoiseReductionParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoicePurifierTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private fun rmsRange(samples: FloatArray, start: Int, end: Int): Float {
        var sumSq = 0.0
        for (i in start until end) {
            sumSq += samples[i] * samples[i]
        }
        return sqrt(sumSq / (end - start).coerceAtLeast(1)).toFloat()
    }

    @Test
    fun `offline voice purification dramatically improves snr while preserving speech`() {
        val sr = 44100
        val totalFrames = (sr * 1.6f).toInt()
        val speechStart = (sr * 0.45f).toInt()
        val speechEnd = (sr * 1.15f).toInt()
        val rng = Random(42L)

        val noisy = FloatArray(totalFrames)
        for (i in 0 until totalFrames) {
            // Broadband hiss + 55 Hz AC hum rumble
            val whiteNoise = ((rng.nextFloat() * 2f - 1f) * 0.045f)
            val hum55Hz = (sin(2.0 * PI * 55.0 * i / sr) * 0.08).toFloat()
            val speech = if (i in speechStart until speechEnd) {
                (0.24 * sin(2.0 * PI * 220.0 * i / sr) +
                    0.16 * sin(2.0 * PI * 440.0 * i / sr) +
                    0.12 * sin(2.0 * PI * 1100.0 * i / sr) +
                    0.08 * sin(2.0 * PI * 2600.0 * i / sr)).toFloat()
            } else {
                0f
            }
            noisy[i] = (speech + whiteNoise + hum55Hz).coerceIn(-1f, 1f)
        }

        val source = StereoAudioBuffer.fromMono(noisy, sr)
        val progressSteps = mutableListOf<Float>()
        val purified = VoicePurifierEngine.purifyBufferOffline(
            source = source,
            params = VoicePurifyPreset.DEEP_NOISE_ISOLATION.params
        ) { p -> progressSteps.add(p) }

        assertEquals(source.frameCount, purified.frameCount)
        assertEquals(1.0f, progressSteps.last(), 0.001f)

        for (i in 0 until purified.frameCount) {
            assertFalse("NaN at $i", purified.left[i].isNaN())
            assertTrue("Clipped at $i", abs(purified.left[i]) <= 1.0f)
        }

        // Compare noise-only section (0.05s..0.38s) and speech section (0.55s..1.05s)
        val nStart = (sr * 0.05f).toInt()
        val nEnd = (sr * 0.38f).toInt()
        val sStart = (sr * 0.55f).toInt()
        val sEnd = (sr * 1.05f).toInt()

        val originalNoiseRms = rmsRange(source.left, nStart, nEnd)
        val cleanedNoiseRms = rmsRange(purified.left, nStart, nEnd)
        val cleanedSpeechRms = rmsRange(purified.left, sStart, sEnd)

        val noiseReductionDb = 20.0 * log10((originalNoiseRms / cleanedNoiseRms.coerceAtLeast(1e-7f)).toDouble())
        val cleanedSnrDb = 20.0 * log10((cleanedSpeechRms / cleanedNoiseRms.coerceAtLeast(1e-7f)).toDouble())

        assertTrue("Noise reduction should exceed 12 dB, was ${noiseReductionDb}dB", noiseReductionDb > 12.0)
        assertTrue("Post-clean SNR should exceed 18 dB, was ${cleanedSnrDb}dB", cleanedSnrDb > 18.0)
        assertTrue("Speech body must remain strong, rms=$cleanedSpeechRms", cleanedSpeechRms > 0.14f)
    }

    @Test
    fun `realtime noise reduction processor handles large blocks and reports suppression`() {
        val processor = NoiseReductionProcessor(44100)
        val blockFrames = 3000
        val rng = Random(7L)
        val left = FloatArray(blockFrames) { ((rng.nextFloat() * 2f - 1f) * 0.03f) }
        val right = left.copyOf()

        processor.processBlock(
            left = left,
            right = right,
            numFrames = blockFrames,
            params = NoiseReductionParams(
                thresholdDb = -30f,
                amount = 0.85f,
                highPassHz = 95f,
                vocalClarity = 0.5f,
                deEssAmount = 0.4f,
                spectralClean = true
            )
        )

        assertTrue("Live noise suppression meter should report attenuation", processor.lastNoiseSuppressionDb > 1f)
        for (i in 0 until blockFrames) {
            assertFalse(left[i].isNaN())
            assertFalse(right[i].isNaN())
        }
    }

    @Test
    fun `legacy noise reduction json deserializes cleanly with defaults`() {
        val legacyJson = """{"bypassAll":false,"masterGainDb":0.0,"noiseReduction":{"thresholdDb":-40.0,"amount":0.75}}"""
        val restored = DspConfig.fromJson(legacyJson)
        assertEquals(-40f, restored.noiseReduction.thresholdDb, 0.01f)
        assertEquals(0.75f, restored.noiseReduction.amount, 0.01f)
        assertEquals(85f, restored.noiseReduction.highPassHz, 0.01f)
        assertTrue(restored.noiseReduction.spectralClean)
    }

    @Test
    fun `voice purifier workspace opens from home and applies studio preset`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("section_voice_purifier"))
        composeTestRule.onNodeWithTag("section_voice_purifier").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("purifier_enable_switch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("purify_preset_smart_balanced").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("purifier_enable_switch").performScrollTo().assertIsOn()
        composeTestRule.onNodeWithTag("purifier_amount_slider").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("purify_deep_apply_button").performScrollTo().assertIsDisplayed()
    }
}
