package com.example

import com.example.audio.dsp.DspPipeline
import com.example.audio.io.AudioEditorOps
import com.example.audio.io.StereoAudioBuffer
import com.example.audio.io.WavAudioCodec
import com.example.model.BuiltInPresets
import com.example.model.DspConfig
import com.example.model.EffectSlot
import com.example.model.EffectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DspAndEditorTest {

    private fun generateSineBuffer(durationSec: Float = 0.5f, freqHz: Float = 440f, amp: Float = 0.5f): StereoAudioBuffer {
        val sr = 44100
        val count = (sr * durationSec).toInt()
        val mono = FloatArray(count) { i ->
            (sin(2.0 * PI * freqHz * i / sr) * amp).toFloat()
        }
        return StereoAudioBuffer.fromMono(mono, sr)
    }

    @Test
    fun `dsp pipeline alters audio when echo and effects are enabled`() {
        val src = generateSineBuffer(0.6f, 440f, 0.4f)
        val config = BuiltInPresets.all.first { it.name == "صدى قاعة كبيرة" }.config
        val rendered = DspPipeline(44100).renderOffline(src, config)

        // Tail should extend beyond source frameCount because of Echo/Reverb
        assertTrue(rendered.frameCount > src.frameCount)
        // Output should stay safely bounded in [-1, 1]
        val maxPeak = rendered.left.maxOf { abs(it) }
        assertTrue(maxPeak > 0.05f && maxPeak <= 1.0f)
    }

    @Test
    fun `dsp time stretch offline changes output duration`() {
        val src = generateSineBuffer(1.0f, 330f, 0.5f)
        val config = DspConfig(
            chain = listOf(EffectSlot(EffectType.TIME_STRETCH, enabled = true)),
            timeStretch = com.example.model.TimeStretchParams(rate = 1.5f)
        )
        val rendered = DspPipeline(44100).renderOffline(src, config)
        assertTrue(rendered.frameCount < (src.frameCount * 0.85f).toInt())
    }

    @Test
    fun `audio editor trim cut and merge produce valid buffers`() {
        val clipA = generateSineBuffer(1.0f, 440f, 0.5f)
        val clipB = generateSineBuffer(0.5f, 880f, 0.5f)

        val trimmed = AudioEditorOps.trimToSelection(clipA, 0.25f, 0.75f)
        assertEquals(clipA.frameCount / 2, trimmed.frameCount)

        val cut = AudioEditorOps.cutSelection(clipA, 0.4f, 0.6f)
        assertTrue(cut.frameCount < clipA.frameCount)

        val merged = AudioEditorOps.mergeClips(clipA, clipB)
        assertTrue(merged.frameCount > clipA.frameCount)
    }

    @Test
    fun `wav codec roundtrip preserves sample rate and frame count`() {
        val src = generateSineBuffer(0.25f, 523.25f, 0.6f)
        val out = ByteArrayOutputStream()
        WavAudioCodec.writeWavStream(out, src)
        val decoded = WavAudioCodec.readWavStream(ByteArrayInputStream(out.toByteArray()))
        assertNotNull(decoded)
        assertEquals(src.sampleRate, decoded!!.sampleRate)
        assertEquals(src.frameCount, decoded.frameCount)
    }

    @Test
    fun `compact mono wav encoding produces valid 16khz payload under size cap`() {
        val src = generateSineBuffer(1.0f, 440f, 0.5f)
        val bytes = WavAudioCodec.encodeCompactMonoWavBytes(src, targetSampleRate = 16000)
        assertTrue(bytes.size > 44)
        assertTrue(bytes.size < 40_000) // ~32KB/s mono 16kHz
        val decoded = WavAudioCodec.readWavStream(ByteArrayInputStream(bytes))
        assertNotNull(decoded)
    }

    @Test
    fun `dsp config json serialization roundtrip succeeds`() {
        val original = BuiltInPresets.all.last().config
        val json = DspConfig.toJson(original)
        val restored = DspConfig.fromJson(json)
        assertEquals(original, restored)
    }
}
