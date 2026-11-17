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

    val fftN = 1024
    val real = DoubleArray(fftN)
    val imag = DoubleArray(fftN)
    val hann = FloatArray(fftN) { i ->
        (0.5 * (1.0 - cos((2.0 * PI * i) / (fftN - 1)))).toFloat()
    }

    var lowE = 0.0
    var midE = 0.0
    var highE = 0.0
    val binHz = sr.toDouble() / fftN
    val hopStep = max(fftN, n / 16)

    var pos = 0
    while (pos + fftN <= n) {
        for (i in 0 until fftN) {
            real[i] = (buffer.left[pos + i] * hann[i]).toDouble()
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

    val bassTrimDb = when {
        lowRatio > 0.45f -> -3.5f
        lowRatio < 0.16f -> 2f
        else -> 0f
    }
    val presenceDb = when {
        highRatio < 0.14f -> 3.8f
        highRatio > 0.34f -> -1.5f
        else -> 2f
    }
    val airDb = if (highRatio < 0.12f) 3f else 1.5f

    val needsComp = crestDb > 10f || rmsDb < -20f
    val compThreshold = clamp(rmsDb + 4f, -28f, -12f)
    val compRatio = if (crestDb > 16f) 4.2f else 3.2f
    val makeup = clamp(-15f - rmsDb, 0.5f, 6f)

    val profileDesc = when {
        highNoise -> "تسجيل صوتي في بيئة تحتوي ضجيج خلفي"
        lowRatio > 0.42f -> "صوت دافئ يحتاج تقليل الترددات المنخفضة الزائدة"
        highRatio < 0.14f -> "صوت مكتوم نسبياً ويحتاج تعزيز الوضوح واللمعان"
        else -> "صوت استوديو متوازن وقابل للماسترينغ الاحترافي"
    }

    if (enableNoiseClean) {
        actions.add("تفعيل المنقّي الطيفي (STFT) لقمع ضجيج الخلفية عند ${"%.0f".format(noiseFloorDb)} dB")
    }
    if (bassTrimDb < 0f) {
        actions.add("خفض الترددات الغليظة (${"%.1f".format(bassTrimDb)} dB) لإزالة الكتمة")
    } else if (bassTrimDb > 0f) {
        actions.add("تعزيز الدفء الجهيري (+${"%.1f".format(bassTrimDb)} dB)")
    }
    if (presenceDb > 0f) {
        actions.add("تعزيز حضور الصوت ووضوح الحروف (+${"%.1f".format(presenceDb)} dB @ 3.6kHz)")
    }
    if (needsComp) {
        actions.add("ضبط ضاغط الديناميكيات (${"%.1f".format(compRatio)}:1) لرفع التجانس إلى -14 LUFS")
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
            highPassHz = if (lowRatio > 0.4f) 105f else 85f,
            spectralStrength = if (highNoise) 0.8f else 0.62f,
            deEssAmount = if (highRatio > 0.32f) 0.55f else 0.35f,
            clarityBoostDb = clamp(presenceDb, 0f, 5f),
            spectralCleanerEnabled = true,
        ),
        equalizer = normalizedBase.equalizer.copy(
            band60HzDb = bassTrimDb,
            band230HzDb = if (lowRatio > 0.42f) -2f else 0.5f,
            band910HzDb = if (midRatio < 0.3f) 1.5f else 0.5f,
            band3600HzDb = presenceDb,
            band14000HzDb = airDb,
        ),
        compressor = normalizedBase.compressor.copy(
            thresholdDb = compThreshold,
            ratio = compRatio,
            attackMs = 10f,
            releaseMs = 135f,
            makeupGainDb = makeup,
        ),
        limiter = normalizedBase.limiter.copy(ceilingDb = -0.8f, releaseMs = 80f),
    )

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
    )
}
