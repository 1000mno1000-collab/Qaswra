package com.example.audio.dsp

import com.example.audio.io.StereoAudioBuffer
import com.example.model.CompressorParams
import com.example.model.DspConfig
import com.example.model.EchoReverbParams
import com.example.model.EffectSlot
import com.example.model.EffectType
import com.example.model.EqualizerParams
import com.example.model.LimiterParams
import com.example.model.NoiseReductionParams
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class SmartMasterAnalysis(
    val peakDb: Float,
    val rmsDb: Float,
    val noiseFloorDb: Float,
    val crestFactorDb: Float,
    val lowEnergyRatio: Float,
    val midEnergyRatio: Float,
    val highEnergyRatio: Float,
    val spectralProfileLabel: String,
    val appliedAdjustments: List<String>,
    val optimizedConfig: DspConfig
)

/**
 * 100% Free Offline Intelligent Mastering Analyzer ("مهندس الصوت الذكي").
 * Scans RMS, crest factor, estimated noise floor, and 3-band spectral balance
 * to configure Noise Reduction, 5-Band Parametric EQ, Compressor, Limiter, and Studio Warmth.
 */
object SmartAutoMasterEngine {

    fun analyzeAndOptimize(
        buffer: StereoAudioBuffer,
        currentConfig: DspConfig = DspConfig()
    ): SmartMasterAnalysis {
        val n = buffer.frameCount
        val sr = buffer.sampleRate.coerceAtLeast(8000)
        if (n <= 0) {
            return SmartMasterAnalysis(
                peakDb = -60f,
                rmsDb = -60f,
                noiseFloorDb = -60f,
                crestFactorDb = 0f,
                lowEnergyRatio = 0.33f,
                midEnergyRatio = 0.34f,
                highEnergyRatio = 0.33f,
                spectralProfileLabel = "صامت",
                appliedAdjustments = listOf("قم بتسجيل أو استيراد مقطع صوتي ليتم تحليله."),
                optimizedConfig = currentConfig
            )
        }

        // Single-pole lowpass (~260Hz) and highpass (~4.2kHz) splitters to estimate 3-band energy
        val lpAlpha = (1.0 - exp(-2.0 * PI * 260.0 / sr)).toFloat()
        val hpAlpha = (exp(-2.0 * PI * 4200.0 / sr)).toFloat()

        var lpState = 0f
        var hpState = 0f
        var prevX = 0f

        var peak = 0f
        var totalSq = 0.0
        var lowSq = 0.0
        var midSq = 0.0
        var highSq = 0.0

        val windowFrames = (sr * 0.05f).toInt().coerceAtLeast(128)
        val windowRmsList = ArrayList<Float>((n / windowFrames) + 1)
        var winSq = 0.0
        var winCount = 0

        for (i in 0 until n) {
            val mono = 0.5f * (buffer.left[i] + buffer.right[i])
            val absX = abs(mono)
            if (absX > peak) peak = absX

            totalSq += mono * mono
            winSq += mono * mono
            winCount++
            if (winCount >= windowFrames) {
                windowRmsList.add(sqrt((winSq / winCount).toFloat()))
                winSq = 0.0
                winCount = 0
            }

            lpState += lpAlpha * (mono - lpState)
            hpState = hpAlpha * (hpState + mono - prevX)
            prevX = mono
            val midBand = mono - lpState - hpState

            lowSq += lpState * lpState
            midSq += midBand * midBand
            highSq += hpState * hpState
        }
        if (winCount > 32) {
            windowRmsList.add(sqrt((winSq / winCount).toFloat()))
        }

        val rms = sqrt((totalSq / n).toFloat())
        val peakDb = ampToDb(peak)
        val rmsDb = ampToDb(rms)
        val crestDb = (peakDb - rmsDb).coerceIn(0f, 36f)

        val sortedWindows = windowRmsList.sorted()
        val noiseFloorAmp = if (sortedWindows.isNotEmpty()) {
            sortedWindows[(sortedWindows.size * 0.15).toInt().coerceIn(0, sortedWindows.lastIndex)]
        } else {
            0.001f
        }
        val noiseFloorDb = ampToDb(noiseFloorAmp).coerceIn(-75f, -15f)

        val totalBandSq = (lowSq + midSq + highSq).coerceAtLeast(1e-12)
        val lowRatio = (lowSq / totalBandSq).toFloat()
        val midRatio = (midSq / totalBandSq).toFloat()
        val highRatio = (highSq / totalBandSq).toFloat()

        val adjustments = mutableListOf<String>()

        // 1. Noise Reduction calibration based on detected noise floor
        val enableNoiseGate = noiseFloorDb > -52f
        val nrAmount = when {
            noiseFloorDb > -36f -> 0.72f
            noiseFloorDb > -44f -> 0.52f
            noiseFloorDb > -52f -> 0.34f
            else -> 0.22f
        }
        val nrThreshold = (noiseFloorDb + 7f).coerceIn(-56f, -26f)
        if (enableNoiseGate) {
            adjustments += "تفعيل العزل الذكي للضوضاء (أرضية الضجيج: ${noiseFloorDb.toInt()} dBFS • قوة ${ (nrAmount * 100).toInt() }%)"
        } else {
            adjustments += "التسجيل نظيف جداً (${noiseFloorDb.toInt()} dBFS) — تم ضبط عزل لطيف لحماية الحروف"
        }

        // 2. 5-Band EQ tuning from spectral energy ratios
        val band60 = when {
            lowRatio > 0.48f -> -4.5f
            lowRatio > 0.36f -> -2.5f
            lowRatio < 0.14f -> 2.0f
            else -> -1.0f
        }
        val band250 = when {
            lowRatio > 0.42f -> -3.0f
            midRatio < 0.30f -> 1.5f
            else -> -1.2f
        }
        val band1k = if (midRatio < 0.32f) 2.2f else 0.8f
        val band4k = when {
            highRatio < 0.14f -> 4.2f
            highRatio < 0.24f -> 2.8f
            highRatio > 0.44f -> -1.2f
            else -> 1.8f
        }
        val band12k = when {
            highRatio > 0.42f -> -2.5f
            highRatio < 0.14f -> 3.0f
            else -> 1.2f
        }

        val profileLabel = when {
            lowRatio > 0.44f -> "صوت دافئ / غني بالجهير (تم تخفيف الطنين المنخفض)"
            highRatio > 0.40f -> "صوت ساطع حاد (تم تنعيم الترددات العليا)"
            midRatio > 0.46f -> "صوت حواري مركّز (تم إبراز الحضور والنعومة)"
            else -> "توازن طيفي طبيعي (تم صقل الوضوح واللمعان)"
        }
        adjustments += String.format(
            java.util.Locale.US,
            "معادلة طيفية 5-Band: (60Hz %+.1fdB، 250Hz %+.1fdB، 1kHz %+.1fdB، 4kHz %+.1fdB، 12kHz %+.1fdB)",
            band60, band250, band1k, band4k, band12k
        )

        // 3. Dynamic Compressor tuning from crest factor & RMS
        val compThreshold = (rmsDb - 4f).coerceIn(-28f, -12f)
        val compRatio = when {
            crestDb > 16f -> 4.0f
            crestDb > 11f -> 3.2f
            else -> 2.4f
        }
        val makeupGain = when {
            rmsDb < -26f -> 5.5f
            rmsDb < -19f -> 3.5f
            rmsDb < -14f -> 1.8f
            else -> 0.5f
        }
        adjustments += String.format(
            java.util.Locale.US,
            "ضبط الضاغط الديناميكي (Threshold %.0f dB، نسبة %.1f:1، تعويض %+.1f dB)",
            compThreshold, compRatio, makeupGain
        )

        // 4. Master Ceiling Limiter (-0.5 dBFS safe ceiling)
        adjustments += "تفعيل محدد القمم عند -0.5 dBFS لمنع التشويه الصوتي تماماً"

        // 5. Subtle Studio Air Reverb (preserves clarity while adding polish)
        val studioEcho = currentConfig.echoReverb.copy(
            echoStrength = 0.06f,
            echoDepth = 0.22f,
            reverbSize = 0.30f,
            reverbDecay = 0.28f,
            damping = 0.65f,
            wetDryMix = 0.14f,
            stereoWidth = 1.15f
        )
        adjustments += "إضافة عمق استوديو خفيف (14%) لاندماج الصوت بسلاسة"

        val updatedChain = buildOptimalChain(currentConfig.chain, enableNoiseGate)

        val optimized = currentConfig.copy(
            bypassAll = false,
            masterGainDb = 0.5f,
            chain = updatedChain,
            noiseReduction = NoiseReductionParams(
                amount = nrAmount,
                thresholdDb = nrThreshold,
                highPassHz = 85f,
                vocalClarity = if (highRatio < 0.22f) 0.58f else 0.42f,
                deEssAmount = if (highRatio > 0.36f) 0.48f else 0.25f,
                spectralClean = true
            ),
            equalizer = EqualizerParams(
                band60HzDb = band60,
                band250HzDb = band250,
                band1kHzDb = band1k,
                band4kHzDb = band4k,
                band12kHzDb = band12k
            ),
            compressor = CompressorParams(
                thresholdDb = compThreshold,
                ratio = compRatio,
                attackMs = 10f,
                releaseMs = 110f,
                makeupDb = makeupGain
            ),
            limiter = LimiterParams(
                ceilingDb = -0.5f,
                releaseMs = 55f
            ),
            echoReverb = studioEcho
        )

        return SmartMasterAnalysis(
            peakDb = peakDb,
            rmsDb = rmsDb,
            noiseFloorDb = noiseFloorDb,
            crestFactorDb = crestDb,
            lowEnergyRatio = lowRatio,
            midEnergyRatio = midRatio,
            highEnergyRatio = highRatio,
            spectralProfileLabel = profileLabel,
            appliedAdjustments = adjustments,
            optimizedConfig = optimized
        )
    }

    private fun buildOptimalChain(
        existing: List<EffectSlot>,
        enableNoiseReduction: Boolean
    ): List<EffectSlot> {
        val enabledByMaster = setOf(
            EffectType.EQUALIZER,
            EffectType.COMPRESSOR,
            EffectType.ECHO_REVERB,
            EffectType.LIMITER
        )
        return existing.map { slot ->
            when (slot.type) {
                EffectType.NOISE_REDUCTION -> slot.copy(enabled = enableNoiseReduction)
                in enabledByMaster -> slot.copy(enabled = true)
                else -> slot
            }
        }
    }

    private fun ampToDb(amp: Float): Float {
        return (20.0 * log10(max(amp, 1e-4f).toDouble())).toFloat()
    }
}
