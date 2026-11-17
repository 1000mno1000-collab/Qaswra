package com.example.audio

import com.example.model.DspConfig
import com.example.model.EffectType
import com.example.model.StereoAudioBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

class DspPipeline(
    private val sampleRate: Int = 44100,
    initialConfig: DspConfig = DspConfig(),
) {
    @Volatile
    private var config: DspConfig = initialConfig.normalized()

    private val noiseReduction = NoiseReductionProcessor(sampleRate)
    private val equalizer = EqualizerProcessor()
    private val bassFilter = StereoBiquadFilter()
    private val trebleFilter = StereoBiquadFilter()
    private val pitchShift = PitchShiftProcessor()
    private val chorus = ChorusProcessor(sampleRate)
    private val flanger = FlangerProcessor(sampleRate)
    private val phaser = PhaserProcessor(sampleRate)
    private val distortion = DistortionProcessor()
    private val compressor = CompressorProcessor(sampleRate)
    private val echoReverb = EchoReverbProcessor(sampleRate)
    private val limiter = LimiterProcessor(sampleRate)

    private var lastBassKey = ""
    private var lastTrebleKey = ""
    private var lastEqKey = ""
    private var lastDistKey = ""

    init {
        updateConfig(initialConfig)
    }

    val currentGainReductionDb: Float
        get() {
            val cfg = config
            val compEnabled = cfg.chain.any { it.type == EffectType.COMPRESSOR && it.enabled }
            return if (!cfg.bypassAll && compEnabled) compressor.currentGainReductionDb else 0f
        }

    val currentNoiseSuppressionDb: Float
        get() {
            val cfg = config
            val nrEnabled = cfg.chain.any { it.type == EffectType.NOISE_REDUCTION && it.enabled }
            return if (!cfg.bypassAll && nrEnabled) noiseReduction.currentSuppressionDb else 0f
        }

    @Synchronized
    fun updateConfig(newConfig: DspConfig) {
        val norm = newConfig.normalized()
        this.config = norm

        val eq = norm.equalizer
        val eqKey = "${eq.band60HzDb}|${eq.band230HzDb}|${eq.band910HzDb}|${eq.band3600HzDb}|${eq.band14000HzDb}"
        if (eqKey != lastEqKey) {
            lastEqKey = eqKey
            equalizer.configure(sampleRate, eq)
        }

        val bassKey = "${norm.bassBoost.frequencyHz}|${norm.bassBoost.gainDb}"
        if (bassKey != lastBassKey) {
            lastBassKey = bassKey
            bassFilter.configureLowShelf(
                sampleRate,
                norm.bassBoost.frequencyHz.toDouble(),
                0.9,
                norm.bassBoost.gainDb.toDouble(),
            )
        }

        val trebleKey = "${norm.treble.frequencyHz}|${norm.treble.gainDb}"
        if (trebleKey != lastTrebleKey) {
            lastTrebleKey = trebleKey
            trebleFilter.configureHighShelf(
                sampleRate,
                norm.treble.frequencyHz.toDouble(),
                0.9,
                norm.treble.gainDb.toDouble(),
            )
        }

        val distKey = "${norm.distortion.toneHz}"
        if (distKey != lastDistKey) {
            lastDistKey = distKey
            distortion.configure(sampleRate, norm.distortion)
        }

        echoReverb.configure(norm.echoReverb)
    }

    @Synchronized
    fun reset() {
        noiseReduction.reset()
        equalizer.reset()
        bassFilter.reset()
        trebleFilter.reset()
        pitchShift.reset()
        chorus.reset()
        flanger.reset()
        phaser.reset()
        distortion.reset()
        compressor.reset()
        echoReverb.reset()
        limiter.reset()
    }

    @Synchronized
    fun processStereoBlock(
        left: FloatArray,
        right: FloatArray,
        count: Int,
    ) {
        val cfg = config
        if (cfg.bypassAll || count <= 0) return

        for (slot in cfg.chain) {
            if (!slot.enabled) continue
            when (slot.type) {
                EffectType.NOISE_REDUCTION -> noiseReduction.processStereo(left, right, count, cfg.noiseReduction)
                EffectType.EQUALIZER -> equalizer.processStereo(left, right, count)
                EffectType.BASS_BOOST -> bassFilter.processStereo(left, right, count)
                EffectType.TREBLE -> trebleFilter.processStereo(left, right, count)
                EffectType.PITCH_SHIFT -> pitchShift.processStereo(left, right, count, cfg.pitchShift)
                EffectType.TIME_STRETCH -> Unit
                EffectType.CHORUS -> chorus.processStereo(left, right, count, cfg.chorus)
                EffectType.FLANGER -> flanger.processStereo(left, right, count, cfg.flanger)
                EffectType.PHASER -> phaser.processStereo(left, right, count, cfg.phaser)
                EffectType.DISTORTION -> distortion.processStereo(left, right, count, cfg.distortion)
                EffectType.COMPRESSOR -> compressor.processStereo(left, right, count, cfg.compressor)
                EffectType.ECHO_REVERB -> echoReverb.processStereo(left, right, count)
                EffectType.LIMITER -> limiter.processStereo(left, right, count, cfg.limiter)
            }
        }

        val masterLinear = 10.0.pow(clamp(cfg.masterGainDb, -18f, 18f) / 20.0)
        val brickwall = 0.944f // -0.5 dBFS
        for (i in 0 until count) {
            left[i] = clamp(tanh(left[i] * masterLinear).toFloat(), -brickwall, brickwall)
            right[i] = clamp(tanh(right[i] * masterLinear).toFloat(), -brickwall, brickwall)
        }
    }
}

private fun timeStretchWsolaStereo(
    source: StereoAudioBuffer,
    tempoRatio: Float,
): StereoAudioBuffer {
    val n = source.frameCount
    if (n < 2048 || abs(tempoRatio - 1f) < 0.02f) return source
    val clampedTempo = clamp(tempoRatio, 0.5f, 2.0f)
    val windowSize = 1024
    val halfWin = windowSize / 2
    val searchRadius = 256
    val expectedOutFrames = max(windowSize, (n / clampedTempo).roundToInt() + windowSize)

    val outL = FloatArray(expectedOutFrames)
    val outR = FloatArray(expectedOutFrames)
    val hann = FloatArray(windowSize) { i ->
        (0.5 * (1.0 - cos((2.0 * PI * i) / (windowSize - 1)))).toFloat()
    }

    var outPos = 0
    var inPos = 0
    for (i in 0 until min(windowSize, n)) {
        outL[i] = source.left[i] * hann[i]
        outR[i] = source.right[i] * hann[i]
    }

    val synHop = halfWin
    val anaHop = (synHop * clampedTempo).roundToInt()

    while (outPos + synHop + windowSize < expectedOutFrames) {
        val nominalIn = inPos + anaHop
        if (nominalIn + windowSize >= n) break

        var bestOffset = 0
        var bestCorr = Float.NEGATIVE_INFINITY
        val tailStartOut = outPos + synHop

        var offset = -searchRadius
        while (offset <= searchRadius) {
            val candIn = nominalIn + offset
            if (candIn >= 0 && candIn + halfWin < n) {
                var corr = 0f
                var i = 0
                while (i < halfWin) {
                    corr += outL[tailStartOut + i] * source.left[candIn + i]
                    i += 4
                }
                if (corr > bestCorr) {
                    bestCorr = corr
                    bestOffset = offset
                }
            }
            offset += 2
        }

        val bestIn = nominalIn + bestOffset
        for (i in 0 until windowSize) {
            val w = hann[i]
            outL[tailStartOut + i] += source.left[bestIn + i] * w
            outR[tailStartOut + i] += source.right[bestIn + i] * w
        }

        outPos += synHop
        inPos = bestIn
    }

    val finalLen = min(outPos + windowSize, expectedOutFrames)
    return StereoAudioBuffer(
        left = outL.copyOf(finalLen),
        right = outR.copyOf(finalLen),
        sampleRate = source.sampleRate,
    )
}

fun renderOfflineDsp(
    source: StereoAudioBuffer,
    config: DspConfig,
): StereoAudioBuffer {
    if (source.frameCount == 0) return source
    val normCfg = config.normalized()
    if (normCfg.bypassAll) {
        return StereoAudioBuffer(
            left = source.left.copyOf(),
            right = source.right.copyOf(),
            sampleRate = source.sampleRate,
        )
    }

    val timeStretchSlot = normCfg.chain.firstOrNull { it.type == EffectType.TIME_STRETCH }
    val stretched = if (timeStretchSlot?.enabled == true && abs(normCfg.timeStretch.tempoRatio - 1f) > 0.02f) {
        timeStretchWsolaStereo(source, normCfg.timeStretch.tempoRatio)
    } else {
        source
    }

    val echoEnabled = normCfg.chain.any { it.type == EffectType.ECHO_REVERB && it.enabled }
    val tailSeconds = if (echoEnabled) {
        clamp(0.6f + normCfg.echoReverb.reverbDecay * 2.2f, 0.4f, 3.0f)
    } else {
        0.05f
    }
    val tailFrames = floor(stretched.sampleRate * tailSeconds).toInt()
    val totalFrames = stretched.frameCount + tailFrames

    val outL = FloatArray(totalFrames)
    val outR = FloatArray(totalFrames)
    System.arraycopy(stretched.left, 0, outL, 0, stretched.frameCount)
    System.arraycopy(stretched.right, 0, outR, 0, stretched.frameCount)

    val pipeline = DspPipeline(stretched.sampleRate, normCfg)
    val blockSize = 1024
    val blockL = FloatArray(blockSize)
    val blockR = FloatArray(blockSize)

    var offset = 0
    while (offset < totalFrames) {
        val count = min(blockSize, totalFrames - offset)
        System.arraycopy(outL, offset, blockL, 0, count)
        System.arraycopy(outR, offset, blockR, 0, count)
        pipeline.processStereoBlock(blockL, blockR, count)
        System.arraycopy(blockL, 0, outL, offset, count)
        System.arraycopy(blockR, 0, outR, offset, count)
        offset += blockSize
    }

    // Trim silent tail end after reverb fades below -68 dB
    val silenceThreshold = 0.0004f
    var lastAudible = stretched.frameCount
    for (i in totalFrames - 1 downTo stretched.frameCount) {
        if (abs(outL[i]) > silenceThreshold || abs(outR[i]) > silenceThreshold) {
            lastAudible = min(totalFrames, i + floor(stretched.sampleRate * 0.04).toInt())
            break
        }
    }

    return StereoAudioBuffer(
        left = outL.copyOf(lastAudible),
        right = outR.copyOf(lastAudible),
        sampleRate = stretched.sampleRate,
    )
}
