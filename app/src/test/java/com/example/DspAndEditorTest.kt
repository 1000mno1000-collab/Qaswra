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
import org.junit.Assert.assertNull
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

    @Test
    fun `readWavStream returns null safely on truncated or oversized chunks`() {
        // Non-WAV MP3-like header
        assertNull(WavAudioCodec.readWavStream(ByteArrayInputStream(byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00))))

        // Truncated WAV with oversized data chunk (> 50MB)
        val bb = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(100_000_000)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(2)
        bb.putInt(44100)
        bb.putInt(176400)
        bb.putShort(4)
        bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(80_000_000) // > 50MB
        assertNull(WavAudioCodec.readWavStream(ByteArrayInputStream(bb.array())))
    }

    @Test
    fun `equalizer +-15db and brickwall limiter prevent clipping above -0_5dBFS`() {
        val src = generateSineBuffer(0.4f, 1000f, 0.85f)
        val eqBoostConfig = DspConfig(
            chain = listOf(
                EffectSlot(EffectType.EQUALIZER, enabled = true),
                EffectSlot(EffectType.LIMITER, enabled = true)
            ),
            equalizer = com.example.model.EqualizerParams(band1kHzDb = 15f)
        )
        val rendered = DspPipeline(44100).renderOffline(src, eqBoostConfig)
        val ceiling = 0.9445f // -0.5 dBFS ≈ 0.94406
        val maxPeak = rendered.left.maxOf { abs(it) }
        assertTrue("Brickwall limiter must keep peak <= -0.5dBFS ($ceiling), was $maxPeak", maxPeak <= ceiling)
        assertTrue("Boosted audio should approach ceiling loudly, was $maxPeak", maxPeak > 0.85f)
    }

    @Test
    fun `auto normalize scales quiet buffer to optimal -0_5dBFS peak`() {
        val quiet = generateSineBuffer(0.3f, 440f, 0.12f)
        val normalized = AudioEditorOps.normalize(quiet)
        val peak = normalized.left.maxOf { abs(it) }
        assertEquals(0.944f, peak, 0.01f)
    }

    @Test
    fun `fade in and fade out shape start and end envelopes smoothly`() {
        val src = generateSineBuffer(2.0f, 440f, 0.8f)
        val fadedIn = AudioEditorOps.applyFadeIn(src, durationSec = 1.0f)
        assertEquals(src.frameCount, fadedIn.frameCount)
        assertEquals(0f, abs(fadedIn.left[0]), 0.001f)
        val midAmpIn = fadedIn.left.sliceArray(50000 until 60000).maxOf { abs(it) }
        assertTrue("Post fade-in audio should retain full amplitude", midAmpIn > 0.7f)

        val fadedOut = AudioEditorOps.applyFadeOut(src, durationSec = 1.0f)
        assertEquals(src.frameCount, fadedOut.frameCount)
        assertEquals(0f, abs(fadedOut.left.last()), 0.005f)
        val earlyAmpOut = fadedOut.left.sliceArray(5000 until 15000).maxOf { abs(it) }
        assertTrue("Pre fade-out audio should retain full amplitude", earlyAmpOut > 0.7f)
    }

    @Test
    fun `dual track mix overlays two buffers simultaneously within safe bounds`() {
        val voice = generateSineBuffer(0.6f, 300f, 0.6f)
        val bg = generateSineBuffer(0.8f, 600f, 0.5f)
        val mixed = AudioEditorOps.mixTracks(
            mainBuffer = voice,
            backgroundBuffer = bg,
            mainGain = 1.0f,
            backgroundGain = 0.5f
        )
        assertEquals(bg.frameCount, mixed.frameCount)
        val maxPeak = mixed.left.maxOf { abs(it) }
        assertTrue("Mixed track should stay safely bounded without harsh clipping", maxPeak in 0.4f..1.0f)
    }

    @Test
    fun `flac exporter writes valid fLaC stream marker and verbatim audio frames`() {
        val src = generateSineBuffer(0.25f, 440f, 0.5f)
        val baos = ByteArrayOutputStream()
        com.example.audio.export.AudioFormatExporter.writeFlacStream(baos, src)
        val bytes = baos.toByteArray()
        assertTrue("FLAC output should be non-trivial", bytes.size > 1000)
        assertEquals('f'.code.toByte(), bytes[0])
        assertEquals('L'.code.toByte(), bytes[1])
        assertEquals('a'.code.toByte(), bytes[2])
        assertEquals('C'.code.toByte(), bytes[3])
    }

    @Test
    fun `smart auto master analyzes buffer and enables eq, compressor, and limiter`() {
        val src = generateSineBuffer(1.0f, 220f, 0.25f)
        val result = com.example.audio.dsp.SmartAutoMasterEngine.analyzeAndOptimize(src)
        assertTrue(result.appliedAdjustments.isNotEmpty())
        assertTrue(result.optimizedConfig.isEffectEnabled(EffectType.EQUALIZER))
        assertTrue(result.optimizedConfig.isEffectEnabled(EffectType.COMPRESSOR))
        assertTrue(result.optimizedConfig.isEffectEnabled(EffectType.LIMITER))
    }

    @Test
    fun `formant voice transformer preserves duration and produces safe non-NaN output across characters`() {
        val src = generateSineBuffer(durationSec = 0.6f, freqHz = 210f, amp = 0.45f)
        val presetsToCheck = listOf(
            com.example.audio.dsp.VoiceCharacterPreset.MALE_LUXURY,
            com.example.audio.dsp.VoiceCharacterPreset.FEMALE_SOFT,
            com.example.audio.dsp.VoiceCharacterPreset.CHILD
        )
        for (preset in presetsToCheck) {
            val out = com.example.audio.dsp.FormantVoiceTransformer.transformOffline(
                source = src,
                pitchSemitones = preset.pitchSemitones,
                formantShift = preset.formantShift,
                warmthDb = preset.warmthDb,
                presenceDb = preset.presenceDb
            )
            assertEquals(src.frameCount, out.frameCount)
            var peak = 0f
            for (i in 0 until out.frameCount) {
                assertTrue("NaN in ${preset.id}", !out.left[i].isNaN() && !out.right[i].isNaN())
                val a = abs(out.left[i])
                assertTrue("Sample bounded for ${preset.id}", a <= 1.0f)
                if (a > peak) peak = a
            }
            assertTrue("Transformed audio should remain audible for ${preset.id}", peak > 0.08f)
        }
    }

    @Test
    fun `tts studio exposes all 10 requested dialects and languages`() {
        val dialects = com.example.audio.tts.TtsDialect.entries
        assertEquals(10, dialects.size)
        val tags = dialects.map { it.primaryLocaleTag }.toSet()
        assertTrue(tags.containsAll(listOf("ar-IQ", "ar-SA", "ar-JO", "ar-SY", "ar-MA", "ar-DZ", "ar-TN", "en-US", "fa-IR", "tr-TR")))
    }
}

