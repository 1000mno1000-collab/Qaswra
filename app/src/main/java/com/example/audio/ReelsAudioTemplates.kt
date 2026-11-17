package com.example.audio

import android.content.Context
import com.example.diagnostics.AppDiagnosticLog
import com.example.diagnostics.FfmpegKitBridge
import com.example.model.NoiseReductionParams
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

enum class ReelsAudioTemplateId(
    val titleAr: String,
    val titleEn: String,
    val subtitleAr: String,
    val subtitleEn: String,
    val defaultBoostPct: Float,
    val defaultNoiseIsoPct: Float,
) {
    REELS_VIRAL_PUNCH(
        titleAr = "قالب الريلز الشائع (-14 LUFS)",
        titleEn = "Viral Reels Punch (-14 LUFS)",
        subtitleAr = "بروز حاد لصوت المعلق، عزل تشويش الغرفة، وثبات -14 LUFS لمنصات Reels و Shorts",
        subtitleEn = "Punchy announcer clarity, room denoise, and crisp -14 LUFS lock for Reels & Shorts",
        defaultBoostPct = 68f,
        defaultNoiseIsoPct = 70f,
    ),
    PODCAST_DEEP_ANNOUNCER(
        titleAr = "معلق البودكاست العميق (-14 LUFS)",
        titleEn = "Podcast Deep Announcer (-14 LUFS)",
        subtitleAr = "تدفئة طبقات الباس الصوتية وتفخيم الحضور الإذاعي مع عزل الضوضاء",
        subtitleEn = "Warm low-end announcer richness with controlled vocal compression & denoise",
        defaultBoostPct = 78f,
        defaultNoiseIsoPct = 62f,
    ),
    DOCUMENTARY_CRISP(
        titleAr = "الوثائقي والرواية النقية (-14 LUFS)",
        titleEn = "Crisp Documentary Narration (-14 LUFS)",
        subtitleAr = "وضوح فائق للحروف مع معالجة ديناميكية ناعمة وموازنة مستمرة",
        subtitleEn = "Ultra-articulate storytelling presence with transparent leveling",
        defaultBoostPct = 60f,
        defaultNoiseIsoPct = 75f,
    ),
    DRAMATIC_VOICEOVER(
        titleAr = "الإلقاء السينمائي الدرامي (-14 LUFS)",
        titleEn = "Dramatic Voiceover (-14 LUFS)",
        subtitleAr = "تركيز بؤري في وسط الصوت مع تعزيز الترددات الحاسمة للإعلانات والمونتاج",
        subtitleEn = "Focused center vocal punch tailored for ads, trailers, and promos",
        defaultBoostPct = 85f,
        defaultNoiseIsoPct = 66f,
    ),
}

data class ReelsTemplateParams(
    val templateId: ReelsAudioTemplateId = ReelsAudioTemplateId.REELS_VIRAL_PUNCH,
    val voiceBoostPct: Float = 68f,
    val noiseIsolationPct: Float = 70f,
    val targetLufs: Float = -14f,
)

/**
 * Pure audio engineering template processor for Reels & Podcasts.
 * Amplifies announcer voice, isolates background noise, and normalizes loudness
 * accurately to social media standard (-14 LUFS) via FFmpegKit or Kotlin DSP fallback.
 */
suspend fun processReelsAudioTemplate(
    context: Context,
    source: StereoAudioBuffer,
    params: ReelsTemplateParams,
    onProgress: ((Float) -> Unit)? = null,
): StereoAudioBuffer = withContext(Dispatchers.IO) {
    if (source.frameCount <= 0) return@withContext source
    onProgress?.invoke(0.08f)

    val isoNorm = clamp(params.noiseIsolationPct / 100f, 0f, 1f)
    val boostNorm = clamp(params.voiceBoostPct / 100f, 0f, 1f)
    val lufsTarget = clamp(params.targetLufs, -23f, -10f)

    val dir = File(context.cacheDir, "reels_audio_fx").apply { mkdirs() }
    val inWav = File(dir, "in_${UUID.randomUUID()}.wav")
    val outWav = File(dir, "out_${UUID.randomUUID()}.wav")

    try {
        writeStereoWavFile(inWav, source)
        onProgress?.invoke(0.24f)

        val hpHz = when (params.templateId) {
            ReelsAudioTemplateId.PODCAST_DEEP_ANNOUNCER -> 75
            ReelsAudioTemplateId.DRAMATIC_VOICEOVER -> 85
            else -> 92
        }
        val presenceDb = when (params.templateId) {
            ReelsAudioTemplateId.REELS_VIRAL_PUNCH -> 1.8f + boostNorm * 3.8f
            ReelsAudioTemplateId.PODCAST_DEEP_ANNOUNCER -> 1.2f + boostNorm * 3.0f
            ReelsAudioTemplateId.DOCUMENTARY_CRISP -> 1.5f + boostNorm * 3.2f
            ReelsAudioTemplateId.DRAMATIC_VOICEOVER -> 2.0f + boostNorm * 4.2f
        }
        val warmthDb = when (params.templateId) {
            ReelsAudioTemplateId.PODCAST_DEEP_ANNOUNCER -> 2.2f + boostNorm * 3.0f
            ReelsAudioTemplateId.DRAMATIC_VOICEOVER -> 1.6f + boostNorm * 2.5f
            else -> 0.8f + boostNorm * 1.4f
        }
        val nrDb = (isoNorm * 24f).toInt().coerceIn(4, 28)
        val thresholdGain = (1.5f + boostNorm * 3.5f)
        val filterChain = buildString {
            append("highpass=f=$hpHz,")
            if (isoNorm > 0.08f) {
                append("afftdn=nr=$nrDb:nf=-36:tn=1,")
            }
            append("equalizer=f=185:t=q:w=1.1:g=${"%.1f".format(warmthDb)},")
            append("equalizer=f=3200:t=q:w=1.2:g=${"%.1f".format(presenceDb)},")
            append("acompressor=threshold=-19dB:ratio=3.2:attack=8:release=110:makeup=${"%.1f".format(thresholdGain)},")
            append("loudnorm=I=${"%.1f".format(lufsTarget)}:TP=-1.0:LRA=9")
        }

        val cmd = "-y -i \"${inWav.absolutePath}\" -af \"$filterChain\" -ar ${source.sampleRate} -ac 2 -c:a pcm_s16le \"${outWav.absolutePath}\""
        val ffmpegOk = FfmpegKitBridge.execute(
            command = cmd,
            toolName = "Reels & Podcast Audio Templates",
            logNonZeroFailure = false,
        )
        onProgress?.invoke(0.75f)
        if (ffmpegOk && outWav.exists() && outWav.length() > 128L) {
            val decoded = readStereoWavFile(outWav)
            if (decoded != null && decoded.frameCount > 0) {
                onProgress?.invoke(1.0f)
                return@withContext decoded
            }
        }
    } catch (t: Throwable) {
        AppDiagnosticLog.logRecoverable(
            toolName = "Reels & Podcast Audio Templates",
            titleAr = "تم التحويل للمعالج الاحتياطي لقوالب الريلز",
            titleEn = "Reels Template Fallback Triggered",
            causeAr = t.localizedMessage ?: "Fallback DSP",
            causeEn = t.localizedMessage ?: "Fallback DSP",
            errorCode = t.javaClass.simpleName,
            throwable = t,
        )
    } finally {
        inWav.delete()
        outWav.delete()
    }

    // Pure Kotlin DSP fallback matching Noise Isolation + Presence EQ + -14 LUFS Loudness Normalization
    var work = if (isoNorm > 0.08f) {
        purifyBufferOffline(
            source = source,
            params = NoiseReductionParams(
                highPassHz = 85f,
                lowPassHz = 15500f,
                thresholdDb = -44f + isoNorm * 10f,
                reductionDb = isoNorm * 24f,
                spectralStrength = isoNorm * 0.8f,
                deEssAmount = 0.25f,
                clarityBoostDb = 1.5f + boostNorm * 3.5f,
                spectralCleanerEnabled = true,
            ),
        )
    } else {
        source
    }
    onProgress?.invoke(0.65f)
    work = applyPresenceAndNormalizeToLufs(work, boostNorm, params.templateId, lufsTarget)
    onProgress?.invoke(1.0f)
    work
}

private fun applyPresenceAndNormalizeToLufs(
    buffer: StereoAudioBuffer,
    boostNorm: Float,
    templateId: ReelsAudioTemplateId,
    targetLufs: Float,
): StereoAudioBuffer {
    val n = buffer.frameCount
    if (n == 0) return buffer
    val sr = buffer.sampleRate
    val outL = buffer.left.copyOf()
    val outR = buffer.right.copyOf()

    val presenceFilter = StereoBiquadFilter().apply {
        configurePeaking(sr, 3200.0, 1.15, (1.5 + boostNorm * 3.8))
    }
    val warmthFilter = StereoBiquadFilter().apply {
        val g = if (templateId == ReelsAudioTemplateId.PODCAST_DEEP_ANNOUNCER) 2.6 + boostNorm * 2.4 else 1.0 + boostNorm * 1.2
        configurePeaking(sr, 185.0, 1.1, g)
    }

    warmthFilter.processStereo(outL, outR, n)
    presenceFilter.processStereo(outL, outR, n)

    // Measure integrated RMS energy (approximation of LUFS) & scale smoothly to targetLufs (-14 LUFS)
    var sumSq = 0.0
    for (i in 0 until n) {
        sumSq += 0.5 * (outL[i] * outL[i] + outR[i] * outR[i])
    }
    val rms = sqrt(max(1e-9, sumSq / n))
    val approxLufs = -0.691 + 20.0 * log10(rms)
    val deltaDb = clamp((targetLufs - approxLufs).toFloat(), -12f, 18f)
    val gainLin = 10.0.pow(deltaDb / 20.0).toFloat()
    val brickwall = 0.891f // -1.0 dBFS ceiling

    for (i in 0 until n) {
        outL[i] = clamp(tanh((outL[i] * gainLin).toDouble()).toFloat(), -brickwall, brickwall)
        outR[i] = clamp(tanh((outR[i] * gainLin).toDouble()).toFloat(), -brickwall, brickwall)
    }
    return StereoAudioBuffer(outL, outR, sr)
}
