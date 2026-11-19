package com.example.audio

import com.example.model.DspConfig
import com.example.model.EffectType
import com.example.model.SmartMasterAnalysis
import com.example.model.StereoAudioBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

fun analyzeAndOptimizeAudio(
    buffer: StereoAudioBuffer,
    baseConfig: DspConfig,
): SmartMasterAnalysis {
    val n = buffer.frameCount
    val sr = if (buffer.sampleRate > 0) buffer.sampleRate else 44100
    if (n < 512) {
        return SmartMasterAnalysis(
            rmsDb = -24f,
            peakDb = -6f,
            crestFactorDb = 18f,
            noiseFloorDb = -55f,
            lowEnergyRatio = 0.33f,
            midEnergyRatio = 0.42f,
            highEnergyRatio = 0.25f,
            detectedProfile = "صوت طبيعي متوازن",
            appliedActions = listOf("تم تفعيل محدد القمم الآمن وحفظ الإعدادات المتوازنة"),
            optimizedConfig = baseConfig,
        )
    }

    var sumSq = 0.0
    var peak = 1e-6
    val stride = max(1, n / 200_000)
    var counted = 0

    var idx = 0
    while (idx < n) {
        val s = 0.5 * (buffer.left[idx] + buffer.right[idx])
        val a = abs(s)
        if (a > peak) peak = a
        sumSq += s * s
        counted++
        idx += stride
    }

    val rms = sqrt(sumSq / max(1, counted))
    val rmsDb = clamp(20.0 * log10(max(rms, 1e-6)), -80.0, 0.0).toFloat()
    val peakDb = clamp(20.0 * log10(max(peak, 1e-6)), -80.0, 0.0).toFloat()
    val crestDb = clamp(peakDb - rmsDb, 0f, 35f)

    val chunkSize = max(256, (sr * 0.03).toInt())
    val frameDbList = ArrayList<Float>()
    var start = 0
    while (start + chunkSize <= n) {
        var cs = 0.0
        for (i in 0 until chunkSize) {
            val v = buffer.left[start + i].toDouble()
            cs += v * v
        }
        val fRms = sqrt(cs / chunkSize)
        frameDbList.add((20.0 * log10(max(fRms, 1e-6))).toFloat())
        start += chunkSize * 2
    }
    frameDbList.sort()
    val noiseFloorDb = if (frameDbList.isNotEmpty()) {
        val sampleIdx = min(floor(frameDbList.size * 0.12).toInt(), frameDbList.size - 1)
        clamp(frameDbList[sampleIdx], -80f, -15f)
    } else {
        -55f
    }

    val fftN = 2048
    val real = DoubleArray(fftN)
    val imag = DoubleArray(fftN)
    val hann = FloatArray(fftN) { i ->
        (0.5 * (1.0 - cos((2.0 * PI * i) / (fftN - 1)))).toFloat()
    }

    var lowE = 0.0
    var midE = 0.0
    var highE = 0.0
    val bandEnergies = DoubleArray(10)
    val bandEdgesHz = doubleArrayOf(20.0, 44.0, 88.0, 177.0, 355.0, 710.0, 1420.0, 2840.0, 5680.0, 11360.0, 22000.0)
    val binHz = sr.toDouble() / fftN
    val hopStep = max(fftN, n / 20)

    var pos = 0
    while (pos + fftN <= n) {
        for (i in 0 until fftN) {
            real[i] = (0.5f * (buffer.left[pos + i] + buffer.right[pos + i]) * hann[i]).toDouble()
            imag[i] = 0.0
        }
        radix2Fft(real, imag, fftN, false)
        for (k in 1 until fftN / 2) {
            val f = k * binHz
            val p = real[k] * real[k] + imag[k] * imag[k]
            when {
                f < 250.0 -> lowE += p
                f < 3200.0 -> midE += p
                else -> highE += p
            }
            for (b in 0 until 10) {
                if (f >= bandEdgesHz[b] && f < bandEdgesHz[b + 1]) {
                    bandEnergies[b] += p
                    break
                }
            }
        }
        pos += hopStep
    }

    val totalE = max(lowE + midE + highE, 1e-9)
    val lowRatio = (lowE / totalE).toFloat()
    val midRatio = (midE / totalE).toFloat()
    val highRatio = (highE / totalE).toFloat()

    val actions = mutableListOf<String>()
    val enableNoiseClean = noiseFloorDb > -48f
    val highNoise = noiseFloorDb > -38f

    // Target studio voice/mix spectral slope across 10 ISO octave bands (31Hz .. 16kHz)
    val targetRatios = doubleArrayOf(0.03, 0.07, 0.14, 0.17, 0.18, 0.14, 0.12, 0.08, 0.05, 0.02)
    val bands10 = FloatArray(10) { idx ->
        val actualRatio = bandEnergies[idx] / totalE
        val rawCorrDb = if (actualRatio > 1e-7) {
            (2.2 * log10(targetRatios[idx] / actualRatio)).toFloat()
        } else {
            0f
        }
        when (idx) {
            0 -> clamp(if (lowRatio > 0.42f) -4.0f else rawCorrDb * 0.5f, -5.0f, 1.5f)
            1 -> clamp(if (lowRatio > 0.44f) -3.5f else rawCorrDb * 0.7f, -4.5f, 3.0f)
            2 -> clamp(rawCorrDb * 0.75f, -3.5f, 2.5f)
            3 -> clamp(if (lowRatio > 0.42f) -2.2f else rawCorrDb * 0.65f, -3.5f, 2.5f)
            4, 5 -> clamp(rawCorrDb * 0.6f, -2.0f, 2.5f)
            6 -> clamp(rawCorrDb * 0.7f + if (midRatio < 0.30f) 1.2f else 0.4f, -2.0f, 3.5f)
            7 -> clamp(if (highRatio < 0.14f) 3.8f else if (highRatio > 0.34f) -1.5f else 2.0f, -2.5f, 4.5f)
            8 -> clamp(if (highRatio < 0.13f) 3.2f else if (highRatio > 0.36f) -1.2f else 1.8f, -2.0f, 4.0f)
            else -> clamp(if (highRatio < 0.12f) 2.8f else 1.2f, -1.5f, 3.5f)
        }
    }

    val bassTrimDb = bands10[1]
    val presenceDb = bands10[7]
    val needsComp = crestDb > 9.5f || rmsDb < -19f
    val compThreshold = clamp(rmsDb + 4f, -28f, -12f)
    val compRatio = if (crestDb > 16f) 4.0f else 3.2f
    val makeup = clamp(-16f - rmsDb, 0.5f, 6f)

    val profileDesc = when {
        highNoise -> "تسجيل صوتي في بيئة تحتوي ضجيج خلفي"
        lowRatio > 0.42f -> "صوت دافئ يحتاج تقليل الترددات المنخفضة الزائدة"
        highRatio < 0.14f -> "صوت مكتوم نسبياً ويحتاج تعزيز الوضوح واللمعان"
        else -> "صوت استوديو متوازن وقابل للماسترينغ الاحترافي"
    }

    if (enableNoiseClean) {
        actions.add("تفعيل المنقّي الطيفي (STFT) لقمع ضجيج الخلفية عند ${"%.0f".format(noiseFloorDb)} dB")
    }
    if (bassTrimDb < -0.5f) {
        actions.add("معادل 10 نطاقات: خفض الترددات الغليظة (${"%.1f".format(bassTrimDb)} dB @ 62Hz)")
    } else if (bassTrimDb > 0.5f) {
        actions.add("معادل 10 نطاقات: تعزيز الدفء الجهيري (+${"%.1f".format(bassTrimDb)} dB @ 62Hz)")
    }
    if (presenceDb > 0.5f) {
        actions.add("معادل 10 نطاقات: تعزيز حضور الحروف (+${"%.1f".format(presenceDb)} dB @ 4kHz)")
    }
    if (needsComp) {
        actions.add("ضاغط ديناميكيات (${"%.1f".format(compRatio)}:1) + موازنة ITU-R BS.1770 (-16 LUFS)")
    } else {
        actions.add("موازنة ITU-R BS.1770 (-16 LUFS / -1.5 dBTP)")
    }
    actions.add("تأمين السقف النهائي بمحدد القمم لمنع أي تشويه رقمي")

    val normalizedBase = baseConfig.normalized()
    val updatedChain = normalizedBase.chain.map { slot ->
        when (slot.type) {
            EffectType.NOISE_REDUCTION -> slot.copy(enabled = enableNoiseClean)
            EffectType.EQUALIZER -> slot.copy(enabled = true)
            EffectType.COMPRESSOR -> slot.copy(enabled = needsComp)
            EffectType.LIMITER -> slot.copy(enabled = true)
            else -> slot
        }
    }

    val optimized = normalizedBase.copy(
        bypassAll = false,
        chain = updatedChain,
        noiseReduction = normalizedBase.noiseReduction.copy(
            thresholdDb = clamp(noiseFloorDb + 6f, -54f, -32f),
            reductionDb = if (highNoise) 24f else 16f,
            highPassHz = if (lowRatio > 0.4f) 105f else 82f,
            spectralStrength = if (highNoise) 0.8f else 0.62f,
            deEssAmount = if (highRatio > 0.32f) 0.55f else 0.35f,
            clarityBoostDb = clamp(presenceDb, 0f, 5f),
            spectralCleanerEnabled = true,
        ),
        equalizer = com.example.model.EqualizerParams.from10Bands(bands10),
        compressor = normalizedBase.compressor.copy(
            thresholdDb = compThreshold,
            ratio = compRatio,
            attackMs = 10f,
            releaseMs = 135f,
            makeupGainDb = makeup,
        ),
        limiter = normalizedBase.limiter.copy(ceilingDb = -0.8f, releaseMs = 80f),
    )

    val masterFilterStages = mutableListOf<String>()
    masterFilterStages += "highpass=f=${if (lowRatio > 0.4f) 95 else 75}"
    if (enableNoiseClean) {
        masterFilterStages += "afftdn=nf=${noiseFloorDb.toInt().coerceIn(-42, -18)}"
    }
    val freqs10 = com.example.model.EQ_10_FREQUENCIES_HZ
    for (i in 0 until 10) {
        if (abs(bands10[i]) >= 0.4f) {
            masterFilterStages += String.format(
                java.util.Locale.US,
                "equalizer=f=%d:t=q:w=%s:g=%.2f",
                freqs10[i],
                if (i == 0 || i == 9) "0.85" else "1.41",
                bands10[i],
            )
        }
    }
    masterFilterStages += "dynaudnorm=f=150:g=15:p=0.90:m=8"
    masterFilterStages += "loudnorm=I=-16:TP=-1.5:LRA=11"
    masterFilterStages += "alimiter=limit=0.945:attack=5:release=50"

    return SmartMasterAnalysis(
        rmsDb = rmsDb,
        peakDb = peakDb,
        crestFactorDb = crestDb,
        noiseFloorDb = noiseFloorDb,
        lowEnergyRatio = lowRatio,
        midEnergyRatio = midRatio,
        highEnergyRatio = highRatio,
        detectedProfile = profileDesc,
        appliedActions = actions,
        optimizedConfig = optimized,
        recommendedFfmpegFilter = masterFilterStages.joinToString(","),
    )
}
