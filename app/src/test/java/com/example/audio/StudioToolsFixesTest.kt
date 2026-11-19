package com.example.audio

import com.example.model.ChorusParams
import com.example.model.DistortionParams
import com.example.model.DspConfig
import com.example.model.EffectType
import com.example.model.FlangerParams
import com.example.model.PhaserParams
import com.example.model.StereoAudioBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class StudioToolsFixesTest {

    @Test
    fun buildOggFfmpegCommandsTargetsLibOpusAndLibVorbisOggContainers() {
        val commands = buildOggFfmpegCommands(
            inputWavPath = "/tmp/input.wav",
            outputOggPath = "/tmp/output.ogg",
            bitrateKbps = 192,
        )

        assertEquals(2, commands.size)
        assertTrue(commands[0].contains("-c:a libopus"))
        assertTrue(commands[0].contains("-ar 48000"))
        assertTrue(commands[0].contains("-b:a 192k"))
        assertTrue(commands[0].contains("\"/tmp/output.ogg\""))
        assertTrue(commands[1].contains("-c:a libvorbis"))
        assertTrue(commands[1].contains("\"/tmp/output.ogg\""))
    }

    @Test
    fun buildFfmpegFilterForDspConfigIncludesChorusFlangerPhaserAndDistortion() {
        val cfg = DspConfig(
            chorus = ChorusParams(rateHz = 1.2f, depthMs = 5f, mix = 0.5f),
            flanger = FlangerParams(rateHz = 0.8f, depthMs = 3f, feedback = 0.35f, mix = 0.45f),
            phaser = PhaserParams(rateHz = 0.6f, depth = 0.7f, feedback = 0.4f, mix = 0.5f),
            distortion = DistortionParams(drive = 4.0f, toneHz = 4200f, mix = 0.7f),
        )
            .withSlotEnabled(EffectType.CHORUS, true)
            .withSlotEnabled(EffectType.FLANGER, true)
            .withSlotEnabled(EffectType.PHASER, true)
            .withSlotEnabled(EffectType.DISTORTION, true)

        val filter = buildFfmpegFilterForDspConfig(cfg)
        assertTrue("Expected chorus stage in filter: $filter", filter.contains("chorus="))
        assertTrue("Expected flanger stage in filter: $filter", filter.contains("flanger="))
        assertTrue("Expected aphaser stage in filter: $filter", filter.contains("aphaser="))
        assertTrue("Expected soft-clipping alimiter stage for distortion: $filter", filter.contains("alimiter="))
        assertTrue("Expected tone lowpass stage for distortion: $filter", filter.contains("lowpass="))
    }

    @Test
    fun resampleStereoToMono16kDownmixesAndResamplesAccurately() {
        val srcRate = 48_000
        val frames = srcRate / 2 // 0.5s
        val left = FloatArray(frames) { i -> 0.6f * sin(2.0 * PI * 440.0 * i / srcRate).toFloat() }
        val right = FloatArray(frames) { i -> 0.4f * sin(2.0 * PI * 440.0 * i / srcRate).toFloat() }
        val buffer = StereoAudioBuffer(left, right, srcRate)

        val mono16k = resampleStereoToMono16k(buffer)
        assertEquals(8_000, mono16k.size)
        assertTrue(mono16k.maxOf { abs(it) } in 0.45f..0.55f)
    }
}
