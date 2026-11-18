package com.example.audio.fx

import com.example.audio.CompressorProcessor
import com.example.audio.StereoBiquadFilter
import com.example.audio.clamp
import com.example.audio.normalizeAudio
import com.example.audio.purifyBufferOffline
import com.example.model.CompressorParams
import com.example.model.DspConfig
import com.example.model.EchoReverbParams
import com.example.model.EffectType
import com.example.model.EqualizerParams
import com.example.model.LimiterParams
import com.example.model.NoiseReductionParams
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tanh

object AudioSkillsPresets {

    // 1. مهارة تحويل الصوت البشري إلى جودة استوديو دافئة احترافية
    const val STUDIO_VOCAL_MASTERING =
        "highpass=f=75,equalizer=f=180:width_type=h:width=120:g=3,equalizer=f=8000:width_type=h:width=2000:g=2,acompressor=threshold=-20dB:ratio=3:attack=10:release=80,aecho=0.8:0.88:18:0.12,loudnorm=I=-16:TP=-1.5:LRA=11"

    // 2. مهارة صوت الطفل الطبيعي (بدون بطء أو تشويه)
    const val NATURAL_CHILD_VOICE =
        "asetrate=44100*1.32,aresample=44100,atempo=0.76,equalizer=f=3000:width_type=h:width=1000:g=2"

    // 3. مهارة صوت التنين الأسطوري المرعب
    const val ABYSSAL_DRAGON =
        "asetrate=44100*0.62,aresample=44100,atempo=1.35,bass=g=14:f=70,vibrato=f=12:d=0.4,aecho=0.85:0.9:160:0.5,alimiter=limit=0.95"

    // 4. مهارة كيان الظلام المزدوج (صوتان في نفس اللحظة)
    const val SHADOW_LEGION_DUAL =
        "asplit=2[a][b];[a]asetrate=44100*0.75,aresample=44100,atempo=1.25[low];[b]asetrate=44100*0.92,aresample=44100,atempo=1.08[mid];[low][mid]amix=inputs=2:duration=first,aecho=0.8:0.85:90:0.36,alimiter=limit=0.95"

    // 5. مهارة عزل الضوضاء وتنقية المايكروفون
    const val CLEAN_PODCAST_MIC =
        "afftdn=nf=-25,agate=threshold=-35dB:ratio=2:attack=20:release=250,deesser=i=0.4:m=0.5:f=0.5:s=o,loudnorm=I=-16:TP=-1.5:LRA=11"

    val cleanPodcastMicParams: NoiseReductionParams = NoiseReductionParams(
        thresholdDb = -35f,
        reductionDb = 25f,
        highPassHz = 85f,
        spectralStrength = 0.85f,
        deEssAmount = 0.45f,
        clarityBoostDb = 2.8f,
        spectralCleanerEnabled = true,
    )

    fun studioVocalMasteringDspConfig(): DspConfig = DspConfig()
        .withSlotEnabled(EffectType.NOISE_REDUCTION, true)
        .withSlotEnabled(EffectType.EQUALIZER, true)
        .withSlotEnabled(EffectType.COMPRESSOR, true)
        .withSlotEnabled(EffectType.ECHO_REVERB, true)
        .withSlotEnabled(EffectType.LIMITER, true)
        .copy(
            noiseReduction = NoiseReductionParams(
                thresholdDb = -52f,
                reductionDb = 12f,
                highPassHz = 75f,
                spectralStrength = 0.45f,
                deEssAmount = 0.35f,
                clarityBoostDb = 2.0f,
                spectralCleanerEnabled = true,
            ),
            equalizer = EqualizerParams(
                band60HzDb = -1.0f,
                band230HzDb = 3.0f,
                band910HzDb = 0.4f,
                band3600HzDb = 2.0f,
                band14000HzDb = 2.0f,
            ),
            compressor = CompressorParams(
                thresholdDb = -20f,
                ratio = 3.0f,
                attackMs = 10f,
                releaseMs = 80f,
                makeupGainDb = 2.4f,
            ),
            echoReverb = EchoReverbParams(
                echoStrength = 0.16f,
                echoDepth = 0.18f,
                delayTimeMs = 18f,
                feedback = 0.10f,
                reverbSize = 0.16f,
                reverbDecay = 0.12f,
                preDelayMs = 4f,
                wetDryMix = 0.12f,
                stereoWidth = 0.48f,
                earlyReflections = 0.22f,
                lateReflections = 0.10f,
                damping = 0.45f,
                diffusion = 0.55f,
            ),
            limiter = LimiterParams(ceilingDb = -1.5f, releaseMs = 70f),
        )

    fun cleanPodcastMicDspConfig(): DspConfig = DspConfig()
        .withSlotEnabled(EffectType.NOISE_REDUCTION, true)
        .withSlotEnabled(EffectType.EQUALIZER, true)
        .withSlotEnabled(EffectType.COMPRESSOR, true)
        .withSlotEnabled(EffectType.LIMITER, true)
        .copy(
            noiseReduction = cleanPodcastMicParams,
            equalizer = EqualizerParams(
                band60HzDb = -2.5f,
                band230HzDb = 1.2f,
                band910HzDb = 0.6f,
                band3600HzDb = 2.4f,
                band14000HzDb = 1.5f,
            ),
            compressor = CompressorParams(
                thresholdDb = -20f,
                ratio = 3.2f,
                attackMs = 12f,
                releaseMs = 110f,
                makeupGainDb = 2.8f,
            ),
            limiter = LimiterParams(ceilingDb = -1.5f, releaseMs = 75f),
        )

    /**
     * Native Kotlin execution of [STUDIO_VOCAL_MASTERING]:
     * 75Hz HPF -> 180Hz (+3 dB) Warmth -> 8kHz (+2 dB) Air -> Compressor (-20dB, 3:1, 10/80ms)
     * -> Tight 18ms studio vocal ambience (aecho=0.8:0.88:18:0.12) -> Loudness normalization (-1.5 dBFS ceiling).
     */
    fun applyStudioVocalMastering(
        buffer: StereoAudioBuffer,
        extraWarmthDb: Float = 0f,
        extraPresenceDb: Float = 0f,
    ): StereoAudioBuffer {
        val n = buffer.frameCount
        if (n <= 0) return buffer
        val sr = buffer.sampleRate.coerceAtLeast(8000)

        val outL = buffer.left.copyOf()
        val outR = buffer.right.copyOf()

        val highPass = StereoBiquadFilter().apply {
            configureHighPass(sr, 75.0, 0.707)
        }
        val warmthEq = StereoBiquadFilter().apply {
            // width=120 around 180Hz -> Q = 180 / 120 = 1.5
            configurePeaking(sr, 180.0, 1.5, clamp(3.0 + extraWarmthDb, -3.0, 7.5))
        }
        val presenceEq = StereoBiquadFilter().apply {
            configurePeaking(sr, 3300.0, 1.1, clamp(extraPresenceDb.toDouble(), -3.0, 6.0))
        }
        val airEq = StereoBiquadFilter().apply {
            // width=2000 around 8000Hz -> smooth air boost
            configurePeaking(sr, 8000.0, 2.2, 2.0)
        }
        val comp = CompressorProcessor(sr)
        val compParams = CompressorParams(
            thresholdDb = -20f,
            ratio = 3.0f,
            attackMs = 10f,
            releaseMs = 80f,
            makeupGainDb = 2.4f,
        )

        val blockSize = 2048
        val bL = FloatArray(blockSize)
        val bR = FloatArray(blockSize)
        var off = 0
        while (off < n) {
            val count = min(blockSize, n - off)
            System.arraycopy(outL, off, bL, 0, count)
            System.arraycopy(outR, off, bR, 0, count)

            highPass.processStereo(bL, bR, count)
            warmthEq.processStereo(bL, bR, count)
            if (abs(extraPresenceDb) > 0.05f) {
                presenceEq.processStereo(bL, bR, count)
            }
            airEq.processStereo(bL, bR, count)
            comp.processStereo(bL, bR, count, compParams)

            System.arraycopy(bL, 0, outL, off, count)
            System.arraycopy(bR, 0, outR, off, count)
            off += blockSize
        }

        // Subtle studio vocal air echo: 18ms right / 21ms left for spacious intimacy without noticeable slap
        val delaySamplesL = max(1, (0.018f * sr).roundToInt())
        val delaySamplesR = max(1, (0.021f * sr).roundToInt())
        val masteredL = FloatArray(n)
        val masteredR = FloatArray(n)
        for (i in 0 until n) {
            val wetL = if (i >= delaySamplesL) outR[i - delaySamplesL] * 0.12f else 0f
            val wetR = if (i >= delaySamplesR) outL[i - delaySamplesR] * 0.12f else 0f
            masteredL[i] = tanh((outL[i] * 0.88f + wetL).toDouble()).toFloat()
            masteredR[i] = tanh((outR[i] * 0.88f + wetR).toDouble()).toFloat()
        }

        return normalizeAudio(StereoAudioBuffer(masteredL, masteredR, sr), -1.5f)
    }

    /**
     * Native Kotlin execution of [CLEAN_PODCAST_MIC]:
     * STFT spectral denoise (-25dB floor) + soft gate (-35dB) + de-esser + -1.5dBFS studio loudness norm.
     */
    suspend fun renderCleanPodcastMic(
        buffer: StereoAudioBuffer,
        onProgress: ((Float) -> Unit)? = null,
    ): StereoAudioBuffer = withContext(Dispatchers.Default) {
        if (buffer.frameCount <= 0) return@withContext buffer
        val purified = purifyBufferOffline(buffer, cleanPodcastMicParams, onProgress)
        yield()
        normalizeAudio(purified, -1.5f)
    }
}
