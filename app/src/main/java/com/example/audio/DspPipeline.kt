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
import kotlin.math.sin
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

    private var spatialPhase = 0.0
    private var vibratoPhase = 0.0
    private val vibSize = 512
    private val vibMask = vibSize - 1
    private val vibRingL = FloatArray(vibSize)
    private val vibRingR = FloatArray(vibSize)
    private var vibIdx = 0

    private val surSize = 1024
    private val surMask = surSize - 1
    private val surSideRing = FloatArray(surSize)
    private var surIdx = 0

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
        val bands10 = eq.resolved10Bands()
        val eqKey = buildString(64) {
            for (i in bands10.indices) {
                if (i > 0) append('|')
                append((bands10[i] * 100f).toInt())
            }
        }
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
        spatialPhase = 0.0
        vibratoPhase = 0.0
        vibRingL.fill(0f)
        vibRingR.fill(0f)
        vibIdx = 0
        surSideRing.fill(0f)
        surIdx = 0
    }

    @Synchronized
    fun processStereoBlock(
        left: FloatArray,
        right: FloatArray,
        count: Int,
    ) {
        val cfg = config
        if (count <= 0 || !cfg.hasAnyActiveProcessing()) return

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

        if (cfg.vibratoHz > 0.1f && cfg.vibratoDepth > 0.01f) {
            val vibInc = (2.0 * PI * cfg.vibratoHz) / sampleRate
            val maxDelay = min(vibSize - 8.0, sampleRate * 0.004 * cfg.vibratoDepth)
            for (i in 0 until count) {
                vibRingL[vibIdx] = left[i]
                vibRingR[vibIdx] = right[i]
                val lfo = 0.5 * (1.0 + sin(vibratoPhase))
                val delay = 2.0 + lfo * maxDelay
                val rp = (vibIdx - delay + vibSize) % vibSize
                val i0 = floor(rp).toInt() and vibMask
                val i1 = (i0 + 1) and vibMask
                val f = (rp - floor(rp)).toFloat()
                left[i] = vibRingL[i0] + (vibRingL[i1] - vibRingL[i0]) * f
                right[i] = vibRingR[i0] + (vibRingR[i1] - vibRingR[i0]) * f
                vibIdx = (vibIdx + 1) and vibMask
                vibratoPhase += vibInc
                if (vibratoPhase > 2.0 * PI) vibratoPhase -= 2.0 * PI
            }
        }

        if (cfg.spatialPanHz > 0.01f) {
            val panInc = (2.0 * PI * cfg.spatialPanHz) / sampleRate
            for (i in 0 until count) {
                val angle = 0.5 * (1.0 + sin(spatialPhase)) * (PI / 2.0)
                val gL = cos(angle).toFloat()
                val gR = sin(angle).toFloat()
                val mono = 0.5f * (left[i] + right[i])
                left[i] = clamp(mono * gL * 1.12f + left[i] * 0.25f, -1f, 1f)
                right[i] = clamp(mono * gR * 1.12f + right[i] * 0.25f, -1f, 1f)
                spatialPhase += panInc
                if (spatialPhase > 2.0 * PI) spatialPhase -= 2.0 * PI
            }
        }

        if (cfg.hasLiveSurround()) {
            val midScale = clamp(1f + cfg.surroundMidPct / 100f, 0.35f, 1.65f)
            val sideScale = clamp(1f + cfg.surroundSidePct / 100f, 0.5f, 2.3f)
            val widthNorm = clamp(cfg.surroundWidthPct / 100f, 0f, 1f)
            val haasSamples = if (widthNorm <= 0.005f) {
                0
            } else {
                min(surSize - 4, max(1, (0.011f * sampleRate * widthNorm).roundToInt()))
            }
            for (i in 0 until count) {
                val rawL = left[i]
                val rawR = right[i]
                surSideRing[surIdx] = rawR
                val delayedR = if (haasSamples == 0) rawR else surSideRing[(surIdx - haasSamples + surSize) and surMask]
                surIdx = (surIdx + 1) and surMask
                val mid = 0.5f * (rawL + rawR) * midScale
                val naturalSide = 0.5f * (rawL - rawR)
                val haasSide = if (haasSamples == 0) 0f else 0.5f * (rawL - delayedR) * widthNorm
                val side = (naturalSide + haasSide * 0.65f) * sideScale
                left[i] = clamp(mid + side, -1.2f, 1.2f)
                right[i] = clamp(mid - side, -1.2f, 1.2f)
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

internal fun timeStretchWsolaStereo(
    source: StereoAudioBuffer,
    tempoRatio: Float,
): StereoAudioBuffer {
    val n = source.frameCount
    if (n < 2048 || abs(tempoRatio - 1f) < 0.015f) return source
    val clampedTempo = clamp(tempoRatio, 0.5f, 2.0f)
    val windowSize = 1024
    val halfWin = windowSize / 2
    val searchRadius = 256
    val expectedOutFrames = max(windowSize, (n / clampedTempo).roundToInt() + windowSize)

    val outL = FloatArray(expectedOutFrames)
    val outR = FloatArray(expectedOutFrames)
    val normW = FloatArray(expectedOutFrames)
    val hann = FloatArray(windowSize) { i ->
        (0.5 * (1.0 - cos((2.0 * PI * i) / (windowSize - 1)))).toFloat()
    }

    var outPos = 0
    var inPos = 0
    for (i in 0 until min(windowSize, n)) {
        val w = hann[i]
        outL[i] = source.left[i] * w
        outR[i] = source.right[i] * w
        normW[i] += w
    }

    val synHop = halfWin
    val anaHop = (synHop * clampedTempo).roundToInt().coerceAtLeast(1)

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
            normW[tailStartOut + i] += w
        }

        outPos += synHop
        inPos = bestIn
    }

    val finalLen = min(outPos + windowSize, expectedOutFrames)
    val finalL = FloatArray(finalLen)
    val finalR = FloatArray(finalLen)
    for (i in 0 until finalLen) {
        val nw = normW[i]
        if (nw > 1e-4f) {
            finalL[i] = clamp(outL[i] / nw, -1f, 1f)
            finalR[i] = clamp(outR[i] / nw, -1f, 1f)
        }
    }
    return StereoAudioBuffer(
        left = finalL,
        right = finalR,
        sampleRate = source.sampleRate,
    )
}

internal fun timeStretchChainedStereo(
    source: StereoAudioBuffer,
    tempoRatio: Double,
): StereoAudioBuffer {
    var rem = tempoRatio.coerceIn(0.125, 8.0)
    var cur = source
    while (rem > 2.0 + 1e-3) {
        cur = timeStretchWsolaStereo(cur, 2.0f)
        rem /= 2.0
    }
    while (rem < 0.5 - 1e-3) {
        cur = timeStretchWsolaStereo(cur, 0.5f)
        rem /= 0.5
    }
    if (abs(rem - 1.0) >= 0.015) {
        cur = timeStretchWsolaStereo(cur, rem.toFloat())
    }
    return cur
}

internal fun pitchAndSpeedKotlinHq(
    source: StereoAudioBuffer,
    semitones: Float,
    tempoRatio: Float,
    preserveFormants: Boolean = true,
): StereoAudioBuffer {
    if (source.frameCount < 16) return source
    val clampedSemi = clamp(semitones, -12f, 12f)
    val clampedTempo = clamp(tempoRatio, 0.5f, 2.5f)
    val pitchFactor = 2.0.pow(clampedSemi / 12.0).coerceIn(0.5, 2.0)

    // First adjust duration cleanly by netTempo = tempoRatio / pitchFactor,
    // then linearly resample by 1 / pitchFactor at original sampleRate for click-free pitch shifting.
    val netTempo = (clampedTempo / pitchFactor).coerceIn(0.125, 8.0)
    val stretched = timeStretchChainedStereo(source, netTempo)

    val pitched = if (abs(clampedSemi) >= 0.05f) {
        val sr = stretched.sampleRate.coerceAtLeast(8000)
        val targetVirtualRate = (sr / pitchFactor).roundToInt().coerceIn(4000, 192000)
        val resampled = resampleStereo(stretched, targetVirtualRate)
        if (preserveFormants && abs(clampedSemi) >= 0.35f) {
            val compFormant = (1.0 / pitchFactor).coerceIn(0.58, 1.72)
            StereoAudioBuffer(
                left = shiftFormantsStft(resampled.left, compFormant),
                right = shiftFormantsStft(resampled.right, compFormant),
                sampleRate = sr,
            )
        } else {
            StereoAudioBuffer(
                left = resampled.left,
                right = resampled.right,
                sampleRate = sr,
            )
        }
    } else {
        stretched
    }

    // Gentle HPF (45Hz) + soft limiter (0.95) matching our FFmpeg chain
    val hp = StereoBiquadFilter().apply {
        configureHighPass(pitched.sampleRate, 45.0, 0.707)
    }
    val outL = pitched.left.copyOf()
    val outR = pitched.right.copyOf()
    hp.processStereo(outL, outR, pitched.frameCount)
    for (i in 0 until pitched.frameCount) {
        outL[i] = (0.95 * tanh((outL[i] / 0.95).toDouble())).toFloat()
        outR[i] = (0.95 * tanh((outR[i] / 0.95).toDouble())).toFloat()
    }
    return StereoAudioBuffer(outL, outR, pitched.sampleRate)
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
        timeStretchChainedStereo(source, normCfg.timeStretch.tempoRatio.toDouble().coerceIn(0.5, 2.5))
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
