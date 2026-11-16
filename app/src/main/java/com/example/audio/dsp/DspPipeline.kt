package com.example.audio.dsp

import com.example.audio.io.StereoAudioBuffer
import com.example.model.DspConfig
import com.example.model.EffectType
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tanh

class DspPipeline(val sampleRate: Int = 44100) {

    private val noiseReduction = NoiseReductionProcessor(sampleRate)
    private val equalizer = EqualizerProcessor(sampleRate)
    private val pitchShift = PitchShiftProcessor(sampleRate)
    private val compressor = CompressorProcessor(sampleRate)
    private val distortion = DistortionProcessor(sampleRate)
    private val chorus = ChorusProcessor(sampleRate)
    private val flanger = FlangerProcessor(sampleRate)
    private val phaser = PhaserProcessor(sampleRate)
    private val echoReverb = EchoReverbProcessor(sampleRate)
    private val limiter = LimiterProcessor(sampleRate)

    val compressorGainReductionDb: Float
        get() = compressor.lastGainReductionDb

    fun reset() {
        noiseReduction.reset()
        equalizer.reset()
        pitchShift.reset()
        compressor.reset()
        distortion.reset()
        chorus.reset()
        flanger.reset()
        phaser.reset()
        echoReverb.reset()
        limiter.reset()
    }

    fun processStereoBlock(
        left: FloatArray,
        right: FloatArray,
        numFrames: Int,
        config: DspConfig
    ) {
        if (config.bypassAll || numFrames <= 0) return

        for (slot in config.chain) {
            if (!slot.enabled) continue
            when (slot.type) {
                EffectType.NOISE_REDUCTION -> noiseReduction.processBlock(left, right, numFrames, config.noiseReduction)
                EffectType.PITCH_SHIFT -> pitchShift.processBlock(left, right, numFrames, config.pitchShift)
                EffectType.TIME_STRETCH -> { /* Handled at playback/render frame-rate stage */ }
                EffectType.EQUALIZER -> equalizer.processEq(left, right, numFrames, config.equalizer)
                EffectType.BASS_BOOST -> equalizer.processBassBoost(left, right, numFrames, config.bassBoost)
                EffectType.TREBLE_CONTROL -> equalizer.processTreble(left, right, numFrames, config.treble)
                EffectType.COMPRESSOR -> compressor.processBlock(left, right, numFrames, config.compressor)
                EffectType.DISTORTION -> distortion.processBlock(left, right, numFrames, config.distortion)
                EffectType.CHORUS -> chorus.processBlock(left, right, numFrames, config.chorus)
                EffectType.FLANGER -> flanger.processBlock(left, right, numFrames, config.flanger)
                EffectType.PHASER -> phaser.processBlock(left, right, numFrames, config.phaser)
                EffectType.ECHO_REVERB -> echoReverb.processBlock(left, right, numFrames, config.echoReverb)
                EffectType.LIMITER -> limiter.processBlock(left, right, numFrames, config.limiter)
            }
        }

        if (abs(config.masterGainDb) > 0.05f) {
            val master = 10.0.pow(config.masterGainDb / 20.0).toFloat()
            for (i in 0 until numFrames) {
                left[i] = tanh((left[i] * master).toDouble()).toFloat()
                right[i] = tanh((right[i] * master).toDouble()).toFloat()
            }
        } else {
            for (i in 0 until numFrames) {
                left[i] = left[i].coerceIn(-1f, 1f)
                right[i] = right[i].coerceIn(-1f, 1f)
            }
        }
    }

    /**
     * Offline time-stretch + full DSP chain render + reverb/echo tail flush for high-quality export.
     */
    fun renderOffline(
        source: StereoAudioBuffer,
        config: DspConfig,
        onProgress: (Float) -> Unit = {}
    ): StereoAudioBuffer {
        val offlinePipeline = DspPipeline(source.sampleRate)
        val speedRate = if (config.isEffectEnabled(EffectType.TIME_STRETCH)) {
            config.timeStretch.rate.coerceIn(0.5f, 2.0f)
        } else {
            1.0f
        }

        val stretched = if (abs(speedRate - 1.0f) > 0.02f) {
            applyTimeStretchOffline(source, speedRate)
        } else {
            source
        }

        // Add tail space if Echo/Reverb is active so echoes decay cleanly
        val tailFrames = if (config.isEffectEnabled(EffectType.ECHO_REVERB) &&
            config.echoReverb.wetDryMix > 0.02f
        ) {
            (source.sampleRate * (0.6f + config.echoReverb.reverbDecay * 1.6f)).toInt()
        } else {
            0
        }

        val totalFrames = stretched.frameCount + tailFrames
        val outL = FloatArray(totalFrames)
        val outR = FloatArray(totalFrames)
        System.arraycopy(stretched.left, 0, outL, 0, stretched.frameCount)
        System.arraycopy(stretched.right, 0, outR, 0, stretched.frameCount)

        val blockSize = 1024
        val blockL = FloatArray(blockSize)
        val blockR = FloatArray(blockSize)
        var offset = 0
        while (offset < totalFrames) {
            val count = min(blockSize, totalFrames - offset)
            System.arraycopy(outL, offset, blockL, 0, count)
            System.arraycopy(outR, offset, blockR, 0, count)
            offlinePipeline.processStereoBlock(blockL, blockR, count, config)
            System.arraycopy(blockL, 0, outL, offset, count)
            System.arraycopy(blockR, 0, outR, offset, count)
            offset += count
            onProgress((offset.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f))
        }
        onProgress(1f)
        return StereoAudioBuffer(outL, outR, source.sampleRate)
    }

    /**
     * Granular WSOLA-inspired overlap-add time stretch preserving pitch independently of speed.
     */
    private fun applyTimeStretchOffline(source: StereoAudioBuffer, rate: Float): StereoAudioBuffer {
        val inLen = source.frameCount
        if (inLen < 512) return source
        val winSize = 1024
        val hopOut = 256
        val hopIn = (hopOut * rate).toInt().coerceIn(64, winSize)
        val estOutFrames = ((inLen - winSize).coerceAtLeast(1) / hopIn + 2) * hopOut
        val outL = FloatArray(estOutFrames)
        val outR = FloatArray(estOutFrames)
        val norm = FloatArray(estOutFrames)

        val window = FloatArray(winSize) { i ->
            (0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / winSize))).toFloat()
        }

        var inPos = 0
        var outPos = 0
        while (inPos + winSize < inLen && outPos + winSize < estOutFrames) {
            for (k in 0 until winSize) {
                val w = window[k]
                outL[outPos + k] += source.left[inPos + k] * w
                outR[outPos + k] += source.right[inPos + k] * w
                norm[outPos + k] += w
            }
            inPos += hopIn
            outPos += hopOut
        }

        val finalLen = (outPos + hopOut).coerceIn(1, estOutFrames)
        val resL = FloatArray(finalLen)
        val resR = FloatArray(finalLen)
        for (i in 0 until finalLen) {
            val n = norm[i]
            if (n > 1e-4f) {
                resL[i] = (outL[i] / n).coerceIn(-1f, 1f)
                resR[i] = (outR[i] / n).coerceIn(-1f, 1f)
            }
        }
        return StereoAudioBuffer(resL, resR, source.sampleRate)
    }
}
