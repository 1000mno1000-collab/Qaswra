package com.example.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.model.DspConfig
import com.example.model.EffectType
import com.example.model.PlaybackTelemetry
import com.example.model.RecorderTelemetry
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

class StudioAudioPlayer(
    initialConfig: DspConfig,
    private val context: Context? = null,
    private val onTelemetry: (PlaybackTelemetry) -> Unit,
) {
    private val playerExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("StudioAudioPlayer", "Playback coroutine intercepted error safely", throwable)
        isPlaying.set(false)
        emitTelemetry()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + playerExceptionHandler)
    @Volatile private var buffer: StereoAudioBuffer? = null
    @Volatile private var sourceFile: File? = null
    @Volatile private var sourceDurationMs: Long = 0L
    @Volatile private var sourceSampleRate: Int = TARGET_SAMPLE_RATE
    @Volatile private var config: DspConfig = initialConfig.normalized()
    private var pipeline: DspPipeline? = null

    @Volatile private var playhead: Double = 0.0
    private val isPlaying = AtomicBoolean(false)
    @Volatile private var loopEnabled = false
    @Volatile private var loopStartFrac = 0f
    @Volatile private var loopEndFrac = 1f
    @Volatile private var liveClipGainDb = 0f
    @Volatile private var monitoringSpeed = 1.0f

    private var playbackJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var streamMediaPlayer: MediaPlayer? = null

    // Pitch & Speed decoupled native MediaPlayer.PlaybackParams live preview
    @Volatile private var pitchSpeedPreviewActive = false
    @Volatile private var previewPitchSemitones = 0f
    @Volatile private var previewTempoRatio = 1.0f
    private var cachedPitchLiveWavFile: File? = null
    private var cachedPitchLiveBufIdentity: Int = 0
    private var cachedPitchLiveBufFrames: Int = 0

    // Real-Time Dual-Track Merge / Crossfade / Overlay live preview
    @Volatile private var mergePreviewActive = false
    @Volatile private var mergePrimaryBuf: StereoAudioBuffer? = null
    @Volatile private var mergeSecondaryBuf: StereoAudioBuffer? = null
    @Volatile private var mergeParams: MergeStudioParams = MergeStudioParams()
    @Volatile private var mergeDurationOverrideMs: Long? = null

    private val audioManager: AudioManager? =
        context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isPlaying.get()) pause()
            }
        }
    }

    private var latestBars = List(48) { 0.06f }
    private var latestSpectrum = List(32) { 0.03f }
    private var latestLevel = 0f
    private var latestDbfs = -60f
    private val spectrumAnalyzer = RealTimeSpectrumAnalyzer()

    fun setBuffer(buffer: StereoAudioBuffer?, resetPosition: Boolean = true) {
        this.sourceFile = null
        this.sourceDurationMs = 0L
        this.sourceSampleRate = buffer?.sampleRate ?: TARGET_SAMPLE_RATE
        this.buffer = buffer
        if (resetPosition) {
            playhead = 0.0
            pipeline?.reset()
        } else if (buffer != null) {
            playhead = clamp(playhead, 0.0, max(0, buffer.frameCount - 1).toDouble())
        }
        emitTelemetry()
    }

    fun setSourceFile(
        file: File?,
        durationMs: Long,
        sampleRate: Int = TARGET_SAMPLE_RATE,
        resetPosition: Boolean = true,
    ) {
        this.buffer = null
        this.sourceFile = file
        this.sourceDurationMs = durationMs.coerceAtLeast(1000L)
        this.sourceSampleRate = sampleRate.coerceAtLeast(8000)
        val totalFrames = ((this.sourceDurationMs * this.sourceSampleRate) / 1000L).coerceAtLeast(1L)
        if (resetPosition) {
            playhead = 0.0
            pipeline?.reset()
        } else {
            playhead = clamp(playhead, 0.0, max(0L, totalFrames - 1L).toDouble())
        }
        emitTelemetry()
    }

    fun updateConfig(config: DspConfig) {
        val norm = config.normalized()
        this.config = norm
        pipeline?.updateConfig(norm)
        if (pitchSpeedPreviewActive && isPlaying.get()) {
            applyLivePlaybackParams(streamMediaPlayer)
        }
    }

    fun setClipGainDb(db: Float) {
        liveClipGainDb = clamp(db, -24f, 24f)
    }

    fun setMonitoringSpeed(speed: Float) {
        monitoringSpeed = speed.coerceIn(0.5f, 2.5f)
        if (pitchSpeedPreviewActive && isPlaying.get()) {
            applyLivePlaybackParams(streamMediaPlayer)
        }
        emitTelemetry()
    }

    fun setPitchSpeedPreview(active: Boolean, semitones: Float, tempoRatio: Float) {
        val wasActive = pitchSpeedPreviewActive
        pitchSpeedPreviewActive = active
        previewPitchSemitones = clamp(semitones, -12f, 12f)
        previewTempoRatio = clamp(tempoRatio, 0.5f, 2.5f)
        if (active && isPlaying.get()) {
            val mp = streamMediaPlayer
            if (mp != null) {
                applyLivePlaybackParams(mp)
            } else if (!wasActive) {
                // Switch seamlessly from AudioTrack to native MediaPlayer PlaybackParams
                play()
            }
        } else if (!active && wasActive && streamMediaPlayer != null && buffer != null) {
            // Restore AudioTrack DSP path when leaving Pitch & Speed tool
            if (isPlaying.get()) {
                play()
            } else {
                stopAudioThreadOnly()
            }
        }
    }

    fun setMergePreviewBuffers(
        active: Boolean,
        primary: StereoAudioBuffer?,
        secondary: StereoAudioBuffer?,
        params: MergeStudioParams,
        totalDurationOverrideMs: Long? = null,
    ) {
        mergePreviewActive = active
        mergePrimaryBuf = primary
        mergeSecondaryBuf = if (secondary != null && primary != null && secondary.sampleRate != primary.sampleRate) {
            resampleStereo(secondary, primary.sampleRate)
        } else {
            secondary
        }
        mergeParams = params
        mergeDurationOverrideMs = if (active) totalDurationOverrideMs else null

        val maxFrames = totalFrameCount()
        if (maxFrames > 0L && playhead >= maxFrames) {
            playhead = max(0L, maxFrames - 1L).toDouble()
        }
        emitTelemetry()
    }

    private fun applyLivePlaybackParams(mp: MediaPlayer?) {
        if (mp == null) return
        val applyEffects = pitchSpeedPreviewActive && !config.bypassAll
        val targetSpeed = if (applyEffects) {
            (previewTempoRatio * monitoringSpeed).coerceIn(0.5f, 2.5f)
        } else {
            monitoringSpeed.coerceIn(0.5f, 2.5f)
        }
        val targetPitch = if (applyEffects) {
            2.0.pow(previewPitchSemitones / 12.0).toFloat().coerceIn(0.5f, 2.0f)
        } else {
            1.0f
        }
        runCatching {
            val p = mp.playbackParams
            p.setSpeed(targetSpeed)
            p.setPitch(targetPitch)
            mp.playbackParams = p
        }.onFailure {
            runCatching {
                val p = mp.playbackParams
                p.setSpeed(targetSpeed.coerceIn(0.5f, 2.0f))
                p.setPitch(targetPitch)
                mp.playbackParams = p
            }
        }
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying.get()

    fun setLoop(enabled: Boolean, startFrac: Float = 0f, endFrac: Float = 1f) {
        loopEnabled = enabled
        loopStartFrac = clamp(startFrac, 0f, 0.9995f)
        loopEndFrac = clamp(endFrac, (loopStartFrac + 0.0005f).coerceAtMost(1f), 1f)
    }

    fun seekToFraction(frac: Float) {
        ExclusiveAudioController.onBeforeStudioPlayerSeek(this)
        val total = totalFrameCount()
        if (total <= 0L) return
        val clampedFrac = clamp(frac, 0f, 1f)
        playhead = clampedFrac * max(0L, total - 1L).toDouble()
        pipeline?.reset()
        val mp = streamMediaPlayer
        if (mp != null) {
            runCatching {
                val durMs = max(1L, currentDurationMs())
                val targetMs = (clampedFrac * durMs).toInt().coerceIn(0, durMs.toInt())
                mp.seekTo(targetMs)
            }
        } else if (isPlaying.get() && buffer == null && sourceFile != null) {
            // Restart disk stream cleanly from the new seek offset
            play()
            return
        }
        emitTelemetry()
    }

    private fun currentDurationMs(): Long {
        if (mergePreviewActive) {
            val p1 = mergePrimaryBuf
            val p2 = mergeSecondaryBuf
            if (p1 != null && p2 != null) {
                return computeMergeTimelineLayout(p1.durationMs, p2.durationMs, mergeParams).totalDurationMs
            }
            mergeDurationOverrideMs?.let { if (it > 0L) return it }
        }
        val buf = buffer
        if (buf != null) {
            val sr = buf.sampleRate.coerceAtLeast(8000)
            return (buf.frameCount.toLong() * 1000L) / sr
        }
        return sourceDurationMs
    }

    private fun totalFrameCount(): Long {
        if (mergePreviewActive) {
            val p1 = mergePrimaryBuf
            val p2 = mergeSecondaryBuf
            if (p1 != null && p2 != null) {
                val sr = p1.sampleRate.coerceAtLeast(8000)
                val durMs = computeMergeTimelineLayout(p1.durationMs, p2.durationMs, mergeParams).totalDurationMs
                return ((durMs * sr) / 1000L).coerceAtLeast(1L)
            }
            mergeDurationOverrideMs?.let { durMs ->
                if (durMs > 0L) {
                    val sr = sourceSampleRate.coerceAtLeast(8000)
                    return ((durMs * sr) / 1000L).coerceAtLeast(1L)
                }
            }
        }
        buffer?.let { return it.frameCount.toLong() }
        val file = sourceFile ?: return 0L
        if (!file.exists()) return 0L
        return ((sourceDurationMs * sourceSampleRate.coerceAtLeast(8000)) / 1000L).coerceAtLeast(1L)
    }

    fun play(): Boolean {
        ExclusiveAudioController.onBeforeStudioPlayerPlay(this)
        val buf = buffer
        val diskFile = sourceFile
        if (buf == null) {
            if (diskFile == null || diskFile.absolutePath.isBlank() || !diskFile.exists() || diskFile.length() <= 0L) {
                isPlaying.set(false)
                emitTelemetry()
                return false
            }
            return playDiskSourceFile(diskFile)
        }
        if (buf.frameCount <= 0) {
            isPlaying.set(false)
            emitTelemetry()
            return false
        }

        // Use native MediaPlayer + PlaybackParams for crack-free decoupled Pitch & Speed live preview
        if (pitchSpeedPreviewActive && context != null && !mergePreviewActive) {
            return playBufferWithNativePlaybackParams(buf)
        }

        stopAudioThreadOnly()
        requestAudioFocusSafely()

        val sr = buf.sampleRate.coerceAtLeast(8000)
        if (pipeline == null) {
            pipeline = DspPipeline(sr, config)
        } else {
            pipeline?.updateConfig(config)
        }

        val total = totalFrameCount().toInt().coerceAtLeast(buf.frameCount)
        if (loopEnabled) {
            val s = (loopStartFrac * total).toInt()
            val e = (loopEndFrac * total).toInt()
            if (playhead < s || playhead >= e) playhead = s.toDouble()
        } else if (playhead >= total - 1) {
            playhead = 0.0
        }

        val track: AudioTrack = try {
            val minBufBytes = AudioTrack.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT,
            ).coerceAtLeast(2048 * 8)

            val built = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(minBufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (built.state != AudioTrack.STATE_INITIALIZED) {
                try { built.release() } catch (_: Exception) {}
                isPlaying.set(false)
                abandonAudioFocusSafely()
                emitTelemetry()
                return false
            }
            audioTrack = built
            isPlaying.set(true)
            built.play()
            emitTelemetry()
            built
        } catch (e: Exception) {
            Log.e("StudioAudioPlayer", "Failed to initialize or start AudioTrack", e)
            isPlaying.set(false)
            val stale = audioTrack
            audioTrack = null
            if (stale != null) {
                try { stale.release() } catch (_: Exception) {}
            }
            abandonAudioFocusSafely()
            emitTelemetry()
            return false
        }

        playbackJob = scope.launch {
            val blockFrames = 2048
            val tmpL = FloatArray(blockFrames)
            val tmpR = FloatArray(blockFrames)
            val interleaved = FloatArray(blockFrames * 2)
            var tailFlushRemaining = 0
            var mergeDuckEnv = 0f
            var mergeDuckGain = 1.0f
            val mergeAttackCoeff = kotlin.math.exp(-1.0 / (0.180 * sr)).toFloat()
            val mergeReleaseCoeff = kotlin.math.exp(-1.0 / (0.900 * sr)).toFloat()
            val mergeGainAttack = kotlin.math.exp(-1.0 / (0.045 * sr)).toFloat()
            val mergeGainRelease = kotlin.math.exp(-1.0 / (0.350 * sr)).toFloat()

            try {
                while (isActive && isPlaying.get()) {
                    val activeBuf = buffer ?: break
                    val activePipeline = pipeline ?: break
                    val isMergeLive = mergePreviewActive && mergePrimaryBuf != null && mergeSecondaryBuf != null
                    val mPrimary = mergePrimaryBuf
                    val mSecondary = mergeSecondaryBuf
                    val mParams = mergeParams

                    val framesTotal = if (isMergeLive && mPrimary != null && mSecondary != null) {
                        val layout = computeMergeTimelineLayout(mPrimary.durationMs, mSecondary.durationMs, mParams)
                        ((layout.totalDurationMs * sr) / 1000L).toInt().coerceAtLeast(1)
                    } else {
                        activeBuf.frameCount
                    }
                    if (framesTotal == 0) break

                    val rangeStart = if (loopEnabled) (loopStartFrac * framesTotal).toInt() else 0
                    val rangeEnd = if (loopEnabled) {
                        max(rangeStart + 1, (loopEndFrac * framesTotal).toInt())
                    } else {
                        framesTotal
                    }

                    val cfg = config
                    val tsSlot = cfg.chain.firstOrNull { it.type == EffectType.TIME_STRETCH }
                    val dspTempo = if (!isMergeLive && !cfg.bypassAll && tsSlot?.enabled == true) {
                        clamp(cfg.timeStretch.tempoRatio, 0.5f, 2.5f).toDouble()
                    } else {
                        1.0
                    }
                    val rateRatio = (dspTempo * monitoringSpeed.toDouble()).coerceIn(0.4, 3.0)

                    var reachedEnd = false
                    if (isMergeLive && mPrimary != null && mSecondary != null) {
                        val p1Len = mPrimary.frameCount
                        val p2Len = mSecondary.frameCount
                        val p1Gain = (1f + mParams.primaryLevelPct / 100f).coerceIn(0.1f, 2.0f)
                        val p2Gain = (1f + mParams.secondaryLevelPct / 100f).coerceIn(0.1f, 2.0f)
                        val p1StartFrame = (mParams.primaryStartOffsetSec.coerceIn(0f, 60f) * sr).roundToInt()

                        when (mParams.mode) {
                            MergeStudioMode.SEQUENTIAL -> {
                                val gapFrames = (mParams.transitionGapSec.coerceIn(0f, 10f) * sr).roundToInt()
                                val p2StartFrame = p1StartFrame + p1Len + gapFrames
                                val joinXfFrames = minOf((0.020f * sr).toInt(), p1Len / 4, p2Len / 4)
                                for (i in 0 until blockFrames) {
                                    if (playhead >= rangeEnd) {
                                        if (loopEnabled) playhead = rangeStart.toDouble()
                                        else {
                                            tmpL[i] = 0f; tmpR[i] = 0f; reachedEnd = true; continue
                                        }
                                    }
                                    val pos = floor(playhead).toInt()
                                    if (cfg.bypassAll) {
                                        val pIdx = pos
                                        if (pIdx in 0 until p1Len) {
                                            tmpL[i] = mPrimary.left[pIdx]; tmpR[i] = mPrimary.right[pIdx]
                                        } else {
                                            tmpL[i] = 0f; tmpR[i] = 0f
                                        }
                                    } else {
                                        val i1 = pos - p1StartFrame
                                        val i2 = pos - p2StartFrame
                                        var sL = 0f
                                        var sR = 0f
                                        if (i1 in 0 until p1Len) {
                                            sL += mPrimary.left[i1] * p1Gain
                                            sR += mPrimary.right[i1] * p1Gain
                                        }
                                        if (i2 in 0 until p2Len) {
                                            sL += mSecondary.left[i2] * p2Gain
                                            sR += mSecondary.right[i2] * p2Gain
                                        }
                                        if (mParams.gapWhooshEnabled && mParams.whooshIntensityPct > 1f && gapFrames > 0) {
                                            val gapStart = p1StartFrame + p1Len
                                            val gIdx = pos - gapStart
                                            if (gIdx in 0 until gapFrames) {
                                                val gp = gIdx.toFloat() / max(1, gapFrames - 1)
                                                val env = sin(gp * PI).toFloat().pow(1.4f)
                                                val wAmt = (mParams.whooshIntensityPct / 100f) * env * 0.14f
                                                val tone = sin(gp * 42.0 * PI).toFloat()
                                                sL += wAmt * tone
                                                sR -= wAmt * tone
                                            }
                                        }
                                        tmpL[i] = tanh(sL.toDouble()).toFloat()
                                        tmpR[i] = tanh(sR.toDouble()).toFloat()
                                    }
                                    playhead += rateRatio
                                }
                            }

                            MergeStudioMode.CROSSFADE -> {
                                val reqXf = (mParams.crossfadeSec.coerceIn(0.1f, 15f) * sr).roundToInt()
                                val maxXf = minOf((p1Len * 0.85f).toInt(), (p2Len * 0.85f).toInt()).coerceAtLeast(0)
                                val xfade = min(reqXf, maxXf)
                                val p2StartFrame = (p1StartFrame + p1Len - xfade).coerceAtLeast(p1StartFrame)
                                for (i in 0 until blockFrames) {
                                    if (playhead >= rangeEnd) {
                                        if (loopEnabled) playhead = rangeStart.toDouble()
                                        else {
                                            tmpL[i] = 0f; tmpR[i] = 0f; reachedEnd = true; continue
                                        }
                                    }
                                    val pos = floor(playhead).toInt()
                                    if (cfg.bypassAll) {
                                        if (pos in 0 until p1Len) {
                                            tmpL[i] = mPrimary.left[pos]; tmpR[i] = mPrimary.right[pos]
                                        } else {
                                            tmpL[i] = 0f; tmpR[i] = 0f
                                        }
                                    } else {
                                        val i1 = pos - p1StartFrame
                                        val i2 = pos - p2StartFrame
                                        var g1Eff = p1Gain
                                        var g2Eff = p2Gain
                                        if (xfade > 0 && i2 in 0 until xfade && i1 in (p1Len - xfade) until p1Len) {
                                            val t = i2.toFloat() / max(1, xfade)
                                            g1Eff *= (1f - t)
                                            g2Eff *= t
                                        }
                                        var sL = 0f
                                        var sR = 0f
                                        if (i1 in 0 until p1Len) {
                                            sL += mPrimary.left[i1] * g1Eff
                                            sR += mPrimary.right[i1] * g1Eff
                                        }
                                        if (i2 in 0 until p2Len) {
                                            sL += mSecondary.left[i2] * g2Eff
                                            sR += mSecondary.right[i2] * g2Eff
                                        }
                                        tmpL[i] = tanh(sL.toDouble()).toFloat()
                                        tmpR[i] = tanh(sR.toDouble()).toFloat()
                                    }
                                    playhead += rateRatio
                                }
                            }

                            MergeStudioMode.OVERLAY -> {
                                val p2StartFrame = (mParams.overlayStartOffsetSec.coerceIn(0f, 60f) * sr).roundToInt()
                                val fadeInFrames = min(p2Len, (mParams.overlayFadeInSec.coerceIn(0f, 15f) * sr).roundToInt())
                                val fadeOutFrames = min(p2Len, (mParams.overlayFadeOutSec.coerceIn(0f, 15f) * sr).roundToInt())
                                val duckOn = mParams.autoDuckingEnabled
                                val duckStrength = clamp(mParams.duckingStrengthPct / 100f, 0f, 1f).let { if (it < 0.01f) 0.7f else it }
                                val duckMinGain = (1f - duckStrength * 0.85f).coerceIn(0.12f, 1.0f)
                                val secBaseGain = if (duckOn) (0.45f * p2Gain).coerceIn(0.1f, 1.3f) else p2Gain

                                for (i in 0 until blockFrames) {
                                    if (playhead >= rangeEnd) {
                                        if (loopEnabled) playhead = rangeStart.toDouble()
                                        else {
                                            tmpL[i] = 0f; tmpR[i] = 0f; reachedEnd = true; continue
                                        }
                                    }
                                    val pos = floor(playhead).toInt()
                                    if (cfg.bypassAll) {
                                        if (pos in 0 until p1Len) {
                                            tmpL[i] = mPrimary.left[pos]; tmpR[i] = mPrimary.right[pos]
                                        } else {
                                            tmpL[i] = 0f; tmpR[i] = 0f
                                        }
                                    } else {
                                        val i1 = pos - p1StartFrame
                                        val i2 = pos - p2StartFrame
                                        val vL = if (i1 in 0 until p1Len) mPrimary.left[i1] * p1Gain else 0f
                                        val vR = if (i1 in 0 until p1Len) mPrimary.right[i1] * p1Gain else 0f
                                        var secScale = secBaseGain
                                        if (duckOn) {
                                            val det = max(abs(vL), abs(vR))
                                            mergeDuckEnv = if (det > mergeDuckEnv) {
                                                mergeAttackCoeff * mergeDuckEnv + (1f - mergeAttackCoeff) * det
                                            } else {
                                                mergeReleaseCoeff * mergeDuckEnv + (1f - mergeReleaseCoeff) * det
                                            }
                                            val targetDuck = if (mergeDuckEnv > 0.02f) {
                                                val comp = (mergeDuckEnv / 0.02f).toDouble().pow(1.0 / (2.0 + 6.0 * duckStrength) - 1.0).toFloat()
                                                clamp(comp, duckMinGain, 1.0f)
                                            } else {
                                                1.0f
                                            }
                                            mergeDuckGain = if (targetDuck < mergeDuckGain) {
                                                mergeGainAttack * mergeDuckGain + (1f - mergeGainAttack) * targetDuck
                                            } else {
                                                mergeGainRelease * mergeDuckGain + (1f - mergeGainRelease) * targetDuck
                                            }
                                            secScale *= mergeDuckGain
                                        }
                                        if (i2 in 0 until p2Len) {
                                            if (fadeInFrames > 0 && i2 < fadeInFrames) {
                                                val t = i2.toDouble() / max(1, fadeInFrames - 1)
                                                secScale *= sin(t * PI * 0.5).toFloat()
                                            }
                                            if (fadeOutFrames > 0 && i2 >= p2Len - fadeOutFrames) {
                                                val idxOut = i2 - (p2Len - fadeOutFrames)
                                                val t = idxOut.toDouble() / max(1, fadeOutFrames - 1)
                                                secScale *= cos(t * PI * 0.5).toFloat()
                                            }
                                            tmpL[i] = tanh((vL + mSecondary.left[i2] * secScale).toDouble()).toFloat()
                                            tmpR[i] = tanh((vR + mSecondary.right[i2] * secScale).toDouble()).toFloat()
                                        } else {
                                            tmpL[i] = tanh(vL.toDouble()).toFloat()
                                            tmpR[i] = tanh(vR.toDouble()).toFloat()
                                        }
                                    }
                                    playhead += rateRatio
                                }
                            }
                        }
                    } else {
                        for (i in 0 until blockFrames) {
                            if (playhead >= rangeEnd) {
                                if (loopEnabled) {
                                    playhead = rangeStart.toDouble()
                                } else {
                                    tmpL[i] = 0f
                                    tmpR[i] = 0f
                                    reachedEnd = true
                                    continue
                                }
                            }
                            val i0 = clamp(floor(playhead).toInt(), 0, framesTotal - 1)
                            val i1 = clamp(i0 + 1, 0, framesTotal - 1)
                            val f = (playhead - i0).toFloat()
                            tmpL[i] = activeBuf.left[i0] + (activeBuf.left[i1] - activeBuf.left[i0]) * f
                            tmpR[i] = activeBuf.right[i0] + (activeBuf.right[i1] - activeBuf.right[i0]) * f
                            playhead += rateRatio
                        }
                        activePipeline.processStereoBlock(tmpL, tmpR, blockFrames)
                    }

                    val clipGain = liveClipGainDb
                    if (!cfg.bypassAll && abs(clipGain) > 0.05f) {
                        val lin = 10.0.pow(clipGain / 20.0).toFloat()
                        for (i in 0 until blockFrames) {
                            tmpL[i] = clamp(tmpL[i] * lin, -0.98f, 0.98f)
                            tmpR[i] = clamp(tmpR[i] * lin, -0.98f, 0.98f)
                        }
                    }

                    var peak = 0f
                    val barCount = 48
                    val binStep = max(1, blockFrames / barCount)
                    val newBars = ArrayList<Float>(barCount)
                    for (b in 0 until barCount) {
                        var binPeak = 0f
                        val start = b * binStep
                        val end = min(blockFrames, start + binStep)
                        for (k in start until end) {
                            val amp = max(abs(tmpL[k]), abs(tmpR[k]))
                            if (amp > binPeak) binPeak = amp
                        }
                        if (binPeak > peak) peak = binPeak
                        newBars.add(clamp(latestBars[b] * 0.45f + binPeak * 0.55f, 0.04f, 1f))
                    }

                    for (i in 0 until blockFrames) {
                        interleaved[i * 2] = tmpL[i]
                        interleaved[i * 2 + 1] = tmpR[i]
                    }

                    val written = track.write(interleaved, 0, interleaved.size, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) break

                    latestBars = newBars
                    latestSpectrum = spectrumAnalyzer.analyzeStereo(tmpL, tmpR, blockFrames, sr)
                    latestLevel = peak
                    latestDbfs = if (peak > 1e-4f) {
                        (20.0 * log10(peak.toDouble())).toFloat().coerceIn(-60f, 0f)
                    } else {
                        -60f
                    }

                    if (reachedEnd && !loopEnabled) {
                        if (tailFlushRemaining == 0) {
                            val echoOn = !cfg.bypassAll &&
                                cfg.chain.any { it.type == EffectType.ECHO_REVERB && it.enabled }
                            tailFlushRemaining = if (echoOn) (sr * 0.8).toInt() else 1
                        }
                        tailFlushRemaining -= blockFrames
                        if (tailFlushRemaining <= 0 || peak < 0.002f) {
                            isPlaying.set(false)
                            playhead = 0.0
                            latestLevel = 0f
                            latestDbfs = -60f
                            latestBars = List(48) { 0.06f }
                            latestSpectrum = List(32) { 0.03f }
                            abandonAudioFocusSafely()
                            break
                        }
                    }
                    emitTelemetry()
                }
            } catch (e: Exception) {
                Log.e("StudioAudioPlayer", "AudioTrack playback loop error", e)
                isPlaying.set(false)
            } finally {
                try { track.pause() } catch (_: Exception) {}
                try { track.flush() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
                if (audioTrack === track) audioTrack = null
                abandonAudioFocusSafely()
                emitTelemetry()
            }
        }
        return true
    }

    fun pause() {
        isPlaying.set(false)
        latestLevel = 0f
        latestDbfs = -60f
        stopAudioThreadOnly()
        abandonAudioFocusSafely()
        emitTelemetry()
    }

    fun stop() {
        isPlaying.set(false)
        playhead = 0.0
        latestLevel = 0f
        latestDbfs = -60f
        latestBars = List(48) { 0.06f }
        latestSpectrum = List(32) { 0.03f }
        pipeline?.reset()
        stopAudioThreadOnly()
        abandonAudioFocusSafely()
        emitTelemetry()
    }

    fun dispose() {
        ExclusiveAudioController.unregisterStudioPlayer(this)
        stop()
    }

    private fun requestAudioFocusSafely() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            }
        }
    }

    private fun abandonAudioFocusSafely() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusChangeListener)
            }
        }
    }

    private fun playBufferWithNativePlaybackParams(buf: StereoAudioBuffer): Boolean {
        val ctx = context ?: return false
        if (buf.frameCount <= 0) return false
        stopAudioThreadOnly()
        requestAudioFocusSafely()

        val sr = buf.sampleRate.coerceAtLeast(8000)
        val total = buf.frameCount.toLong().coerceAtLeast(1L)
        if (loopEnabled) {
            val s = (loopStartFrac * total).toLong()
            val e = (loopEndFrac * total).toLong()
            if (playhead < s || playhead >= e) playhead = s.toDouble()
        } else if (playhead >= total - 1L) {
            playhead = 0.0
        }

        isPlaying.set(true)
        emitTelemetry()

        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                val idHash = System.identityHashCode(buf)
                val existing = cachedPitchLiveWavFile
                val wavFile = if (existing != null &&
                    existing.exists() &&
                    existing.length() > 44L &&
                    cachedPitchLiveBufIdentity == idHash &&
                    cachedPitchLiveBufFrames == buf.frameCount
                ) {
                    existing
                } else {
                    val target = File(ctx.cacheDir, "pitch_live_src.wav")
                    writeStereoWavFile(target, buf)
                    cachedPitchLiveWavFile = target
                    cachedPitchLiveBufIdentity = idHash
                    cachedPitchLiveBufFrames = buf.frameCount
                    target
                }
                if (!isActive || !isPlaying.get()) return@launch
                if (!wavFile.exists() || wavFile.length() <= 0L) {
                    isPlaying.set(false)
                    emitTelemetry()
                    return@launch
                }
                val startMs = ((playhead * 1000.0) / sr).roundToInt().coerceAtLeast(0)
                streamDiskFileWithMediaPlayer(wavFile, startMs)
            } catch (e: Exception) {
                Log.e("StudioAudioPlayer", "playBufferWithNativePlaybackParams failed safely", e)
                isPlaying.set(false)
                abandonAudioFocusSafely()
                emitTelemetry()
            }
        }
        return true
    }

    private fun playDiskSourceFile(file: File): Boolean {
        if (file.absolutePath.isBlank() || !file.exists() || file.length() <= 0L) {
            isPlaying.set(false)
            emitTelemetry()
            return false
        }
        stopAudioThreadOnly()
        requestAudioFocusSafely()

        val sr = sourceSampleRate.coerceAtLeast(8000)
        val total = totalFrameCount().coerceAtLeast(1L)
        if (loopEnabled) {
            val s = (loopStartFrac * total).toLong()
            val e = (loopEndFrac * total).toLong()
            if (playhead < s || playhead >= e) playhead = s.toDouble()
        } else if (playhead >= total - 1L) {
            playhead = 0.0
        }

        isPlaying.set(true)
        emitTelemetry()

        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                val startUs = ((playhead * 1_000_000.0) / sr).toLong().coerceAtLeast(0L)
                val streamedViaCodec = if (pitchSpeedPreviewActive) {
                    false
                } else {
                    streamDiskFileWithMediaCodec(file, startUs)
                }
                if (!streamedViaCodec && isActive && isPlaying.get()) {
                    // Reliable OS path via MediaPlayer direct file path in cacheDir (also supports native PlaybackParams!)
                    streamDiskFileWithMediaPlayer(file, (startUs / 1000L).toInt())
                }
            } catch (e: Exception) {
                Log.e("StudioAudioPlayer", "playDiskSourceFile failed safely", e)
                isPlaying.set(false)
                abandonAudioFocusSafely()
                emitTelemetry()
            }
        }
        return true
    }

    private suspend fun streamDiskFileWithMediaCodec(file: File, startUs: Long): Boolean {
        if (file.absolutePath.isBlank() || !file.exists() || file.length() <= 0L) return false
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        return try {
            val ext = MediaExtractor()
            extractor = ext
            val setOk = runCatching { ext.setDataSource(file.absolutePath); true }.getOrElse {
                runCatching {
                    FileInputStream(file).use { fis -> ext.setDataSource(fis.fd) }
                    true
                }.getOrDefault(false)
            }
            if (!setOk) return false

            var audioIdx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ext.trackCount) {
                val tf = ext.getTrackFormat(i)
                if (tf.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioIdx = i
                    fmt = tf
                    break
                }
            }
            if (audioIdx < 0 || fmt == null) return false
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return false
            ext.selectTrack(audioIdx)
            if (startUs > 0L) {
                ext.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            var sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000) else TARGET_SAMPLE_RATE
            var chCount = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 2
            var pcmEnc = AudioFormat.ENCODING_PCM_16BIT

            sourceSampleRate = sr
            if (pipeline == null) {
                pipeline = DspPipeline(sr, config)
            } else {
                pipeline?.updateConfig(config)
            }

            fun buildTrack(sampleRateHz: Int): AudioTrack {
                val minBytes = AudioTrack.getMinBufferSize(
                    sampleRateHz,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                ).coerceAtLeast(2048 * 8)
                return AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRateHz)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build().also { it.play() }
            }

            track = buildTrack(sr)
            audioTrack = track

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(fmt, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleCount = 0
            val blockFrames = 2048
            val tmpL = FloatArray(blockFrames)
            val tmpR = FloatArray(blockFrames)
            val interleaved = FloatArray(blockFrames * 2)
            var fillCount = 0

            while (playbackJob?.isActive == true && isPlaying.get() && !outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(8_000L)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)
                        val sz = if (ib != null) ext.readSampleData(ib, 0) else -1
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, ext.sampleTime.coerceAtLeast(0L), 0)
                            ext.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 8_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        val newSr = if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) of.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000) else sr
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) chCount = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEnc = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        if (newSr != sr) {
                            sr = newSr
                            sourceSampleRate = sr
                            pipeline = DspPipeline(sr, config)
                            runCatching { track?.pause(); track?.flush(); track?.release() }
                            track = buildTrack(sr)
                            audioTrack = track
                        }
                    }
                    outIdx >= 0 -> {
                        idleCount = 0
                        val ob = codec.getOutputBuffer(outIdx)
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val order = ob.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val isFloat = pcmEnc == AudioFormat.ENCODING_PCM_FLOAT
                            val bps = if (isFloat) 4 else 2
                            val fb = bps * chCount
                            val framesHere = info.size / fb
                            for (f in 0 until framesHere) {
                                var l = 0f
                                var r = 0f
                                for (c in 0 until chCount) {
                                    val s = if (isFloat) clamp(order.float, -1f, 1f) else clamp(order.short / 32768f, -1f, 1f)
                                    if (c == 0) { l = s; r = s } else if (c == 1) { r = s }
                                }
                                tmpL[fillCount] = l
                                tmpR[fillCount] = r
                                fillCount++
                                playhead += 1.0
                                if (fillCount == blockFrames) {
                                    val activeTrack = track ?: break
                                    val cfg = config
                                    pipeline?.processStereoBlock(tmpL, tmpR, blockFrames)
                                    val clipGain = liveClipGainDb
                                    if (!cfg.bypassAll && abs(clipGain) > 0.05f) {
                                        val lin = 10.0.pow(clipGain / 20.0).toFloat()
                                        for (i in 0 until blockFrames) {
                                            tmpL[i] = clamp(tmpL[i] * lin, -0.98f, 0.98f)
                                            tmpR[i] = clamp(tmpR[i] * lin, -0.98f, 0.98f)
                                        }
                                    }
                                    var peak = 0f
                                    val barCount = 48
                                    val binStep = max(1, blockFrames / barCount)
                                    val newBars = ArrayList<Float>(barCount)
                                    for (b in 0 until barCount) {
                                        var binPeak = 0f
                                        val st = b * binStep
                                        val en = min(blockFrames, st + binStep)
                                        for (k in st until en) {
                                            val amp = max(abs(tmpL[k]), abs(tmpR[k]))
                                            if (amp > binPeak) binPeak = amp
                                        }
                                        if (binPeak > peak) peak = binPeak
                                        newBars.add(clamp(latestBars[b] * 0.45f + binPeak * 0.55f, 0.04f, 1f))
                                    }
                                    for (i in 0 until blockFrames) {
                                        interleaved[i * 2] = tmpL[i]
                                        interleaved[i * 2 + 1] = tmpR[i]
                                    }
                                    activeTrack.write(interleaved, 0, interleaved.size, AudioTrack.WRITE_BLOCKING)
                                    latestBars = newBars
                                    latestSpectrum = spectrumAnalyzer.analyzeStereo(tmpL, tmpR, blockFrames, sr)
                                    latestLevel = peak
                                    latestDbfs = if (peak > 1e-4f) (20.0 * log10(peak.toDouble())).toFloat().coerceIn(-60f, 0f) else -60f
                                    emitTelemetry()
                                    fillCount = 0
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            if (loopEnabled && isPlaying.get()) {
                                val loopStartUs = ((loopStartFrac.toDouble() * totalFrameCount() * 1_000_000.0) / sr).toLong()
                                playhead = loopStartFrac.toDouble() * totalFrameCount()
                                ext.seekTo(loopStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                codec.flush()
                                inputDone = false
                            } else {
                                outputDone = true
                            }
                        }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone && ++idleCount > 150) outputDone = true
                    }
                }
            }
            if (outputDone) {
                isPlaying.set(false)
                playhead = 0.0
                latestLevel = 0f
                latestDbfs = -60f
                latestBars = List(48) { 0.06f }
                latestSpectrum = List(32) { 0.03f }
                abandonAudioFocusSafely()
            }
            true
        } catch (e: Exception) {
            Log.e("StudioAudioPlayer", "streamDiskFileWithMediaCodec error", e)
            false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            try { track?.pause() } catch (_: Exception) {}
            try { track?.flush() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            if (audioTrack === track) audioTrack = null
            abandonAudioFocusSafely()
            emitTelemetry()
        }
    }

    private suspend fun streamDiskFileWithMediaPlayer(file: File, startMs: Int) {
        if (file.absolutePath.isBlank() || !file.exists() || file.length() <= 0L) {
            isPlaying.set(false)
            abandonAudioFocusSafely()
            emitTelemetry()
            return
        }
        var mp: MediaPlayer? = null
        var completed = false
        try {
            val playerInstance = MediaPlayer()
            mp = playerInstance
            streamMediaPlayer = playerInstance
            playerInstance.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            playerInstance.setDataSource(file.absolutePath)
            playerInstance.setOnErrorListener { _, _, _ ->
                completed = true
                isPlaying.set(false)
                emitTelemetry()
                true
            }
            try {
                playerInstance.prepare()
            } catch (e: Exception) {
                Log.e("StudioAudioPlayer", "MediaPlayer.prepare failed safely", e)
                isPlaying.set(false)
                return
            }
            val dur = playerInstance.duration.toLong().coerceAtLeast(currentDurationMs().coerceAtLeast(1L))
            if (buffer == null && !mergePreviewActive) {
                sourceDurationMs = dur
            }
            if (startMs in 1 until dur.toInt()) {
                try { playerInstance.seekTo(startMs) } catch (_: Exception) {}
            }
            playerInstance.setOnCompletionListener {
                if (loopEnabled && isPlaying.get()) {
                    val sMs = (loopStartFrac * dur).toInt()
                    try {
                        playerInstance.seekTo(sMs)
                        playerInstance.start()
                    } catch (_: Exception) {
                        completed = true
                        isPlaying.set(false)
                        emitTelemetry()
                    }
                } else {
                    completed = true
                    isPlaying.set(false)
                    playhead = 0.0
                    emitTelemetry()
                }
            }
            try {
                playerInstance.start()
            } catch (e: Exception) {
                Log.e("StudioAudioPlayer", "MediaPlayer.start failed safely", e)
                isPlaying.set(false)
                return
            }
            if (pitchSpeedPreviewActive || abs(monitoringSpeed - 1f) > 0.01f) {
                applyLivePlaybackParams(playerInstance)
            }
            val sr = (buffer?.sampleRate ?: sourceSampleRate).coerceAtLeast(8000)
            while (playbackJob?.isActive == true && isPlaying.get() && !completed) {
                var curMs = try { playerInstance.currentPosition } catch (_: Exception) { 0 }.coerceAtLeast(0)
                if (loopEnabled && dur > 0L && loopEndFrac < 0.9995f && loopStartFrac < loopEndFrac) {
                    val endBoundMs = (loopEndFrac * dur).toInt()
                    if (curMs >= endBoundMs) {
                        val sMs = (loopStartFrac * dur).toInt().coerceIn(0, max(0, endBoundMs - 100))
                        runCatching { playerInstance.seekTo(sMs) }
                        curMs = sMs
                    }
                }
                playhead = (curMs.toDouble() * sr) / 1000.0
                latestBars = List(48) { idx ->
                    val phase = ((curMs / 80.0) + idx * 0.4)
                    clamp(0.25f + 0.35f * abs(sin(phase)).toFloat(), 0.06f, 0.85f)
                }
                latestLevel = 0.45f
                latestDbfs = -7f
                emitTelemetry()
                delay(16L)
            }
        } catch (e: Exception) {
            Log.e("StudioAudioPlayer", "streamDiskFileWithMediaPlayer failed safely", e)
            isPlaying.set(false)
        } finally {
            mp?.let { playerInstance ->
                try {
                    if (playerInstance.isPlaying) playerInstance.stop()
                } catch (_: Exception) {}
                try { playerInstance.reset() } catch (_: Exception) {}
                try { playerInstance.release() } catch (_: Exception) {}
                if (streamMediaPlayer === playerInstance) streamMediaPlayer = null
            }
            abandonAudioFocusSafely()
            emitTelemetry()
        }
    }

    private fun stopAudioThreadOnly() {
        playbackJob?.cancel()
        playbackJob = null
        val mp = streamMediaPlayer
        streamMediaPlayer = null
        if (mp != null) {
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Exception) {}
            try { mp.reset() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
        }
        val t = audioTrack
        audioTrack = null
        if (t != null) {
            try { t.pause() } catch (_: Exception) {}
            try { t.flush() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
        }
    }

    private fun emitTelemetry() {
        val sr = (mergePrimaryBuf?.sampleRate ?: buffer?.sampleRate ?: sourceSampleRate).coerceAtLeast(TARGET_SAMPLE_RATE)
        val durationMs = currentDurationMs()
        val posMs = if (sr > 0) ((playhead * 1000.0) / sr).toLong().coerceIn(0L, durationMs) else 0L
        onTelemetry(
            PlaybackTelemetry(
                isPlaying = isPlaying.get(),
                positionMs = posMs,
                durationMs = durationMs,
                outputLevel = latestLevel,
                outputDbfs = latestDbfs,
                monitoringSpeed = monitoringSpeed,
                gainReductionDb = pipeline?.currentGainReductionDb ?: 0f,
                noiseSuppressionDb = pipeline?.currentNoiseSuppressionDb ?: 0f,
                waveformBars = latestBars,
                spectrumBands = latestSpectrum,
            ),
        )
    }
}

class StudioAudioRecorder(
    private val context: Context,
    private val onTelemetry: (RecorderTelemetry) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private val chunks = mutableListOf<FloatArray>()
    private var capturedFrames = 0
    private val sampleRate = TARGET_SAMPLE_RATE

    @Volatile private var isRecording = false
    @Volatile private var isPaused = false
    private var bars = List(48) { 0.05f }
    private var spectrum = List(32) { 0.03f }
    private var level = 0f
    private val spectrumAnalyzer = RealTimeSpectrumAnalyzer()

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (!hasMicPermission()) return false
        discard()

        val channelCfg = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_FLOAT
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, channelCfg, encoding)
            .coerceAtLeast(2048 * 4)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelCfg,
                encoding,
                minBytes * 2,
            )
        } catch (_: SecurityException) {
            return false
        } catch (_: Throwable) {
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return false
        }

        audioRecord = record
        isRecording = true
        isPaused = false
        runCatching { record.startRecording() }.onFailure {
            discard()
            return false
        }
        emit()

        recordJob = scope.launch {
            val block = FloatArray(2048)
            while (isActive && isRecording) {
                val read = record.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                if (read > 0 && !isPaused) {
                    val copy = block.copyOf(read)
                    synchronized(chunks) {
                        chunks.add(copy)
                        capturedFrames += read
                    }

                    var peak = 0f
                    val barCount = 48
                    val step = max(1, read / barCount)
                    val nextBars = ArrayList<Float>(barCount)
                    for (b in 0 until barCount) {
                        var bp = 0f
                        val s = b * step
                        val e = min(read, s + step)
                        for (k in s until e) {
                            val a = abs(copy[k])
                            if (a > bp) bp = a
                        }
                        if (bp > peak) peak = bp
                        nextBars.add(clamp(bars[b] * 0.4f + bp * 0.6f, 0.04f, 1f))
                    }
                    bars = nextBars
                    spectrum = spectrumAnalyzer.analyzeMono(copy, read, sampleRate)
                    level = peak
                    emit()
                }
            }
        }
        return true
    }

    fun pause() {
        if (!isRecording) return
        isPaused = true
        level = 0f
        emit()
    }

    fun resume() {
        if (!isRecording) return
        isPaused = false
        emit()
    }

    fun stopAndBuildBuffer(): StereoAudioBuffer? {
        val framesSnapshot: Int
        val chunkList: List<FloatArray>
        synchronized(chunks) {
            framesSnapshot = capturedFrames
            chunkList = chunks.toList()
            chunks.clear()
            capturedFrames = 0
        }
        cleanupRecord()
        isRecording = false
        isPaused = false
        level = 0f
        bars = List(48) { 0.05f }
        spectrum = List(32) { 0.03f }
        emit()

        if (framesSnapshot <= 0) return null
        val outL = FloatArray(framesSnapshot)
        val outR = FloatArray(framesSnapshot)
        var offset = 0
        for (c in chunkList) {
            System.arraycopy(c, 0, outL, offset, c.size)
            System.arraycopy(c, 0, outR, offset, c.size)
            offset += c.size
        }
        return StereoAudioBuffer(outL, outR, sampleRate)
    }

    fun discard() {
        cleanupRecord()
        synchronized(chunks) {
            chunks.clear()
            capturedFrames = 0
        }
        isRecording = false
        isPaused = false
        level = 0f
        bars = List(48) { 0.05f }
        spectrum = List(32) { 0.03f }
        emit()
    }

    private fun cleanupRecord() {
        isRecording = false
        recordJob?.cancel()
        recordJob = null
        val rec = audioRecord
        audioRecord = null
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }

    private fun emit() {
        val elapsedMs = if (sampleRate > 0) (capturedFrames * 1000L) / sampleRate else 0L
        val dbfs = if (level > 1e-4f) {
            (20.0 * log10(level.toDouble())).toFloat().coerceIn(-60f, 0f)
        } else {
            -60f
        }
        onTelemetry(
            RecorderTelemetry(
                isRecording = isRecording,
                isPaused = isPaused,
                elapsedMs = elapsedMs,
                micLevel = level,
                micDbfs = dbfs,
                waveformBars = bars,
                spectrumBands = spectrum,
            ),
        )
    }
}

/**
 * Lightweight zero-allocation radix-2 FFT analyzer producing 32 logarithmic
 * studio frequency bands (55 Hz – 14 kHz) from live PCM frames.
 */
private class RealTimeSpectrumAnalyzer(
    private val bandCount: Int = 32,
    private val fftSize: Int = 512,
) {
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val mags = FloatArray(fftSize / 2)
    private val hannWindow = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))).toFloat()
    }
    private val bandEdgesHz = DoubleArray(bandCount + 1) { i ->
        55.0 * (14000.0 / 55.0).pow(i.toDouble() / bandCount.toDouble())
    }
    private val smoothed = FloatArray(bandCount) { 0.03f }

    fun analyzeStereo(left: FloatArray, right: FloatArray, count: Int, sampleRate: Int): List<Float> {
        val n = min(count, fftSize)
        for (i in 0 until fftSize) {
            val sample = if (i < n) (left[i] + right[i]) * 0.5f else 0f
            re[i] = sample * hannWindow[i]
            im[i] = 0f
        }
        return computeBands(sampleRate)
    }

    fun analyzeMono(mono: FloatArray, count: Int, sampleRate: Int): List<Float> {
        val n = min(count, fftSize)
        for (i in 0 until fftSize) {
            val sample = if (i < n) mono[i] else 0f
            re[i] = sample * hannWindow[i]
            im[i] = 0f
        }
        return computeBands(sampleRate)
    }

    private fun computeBands(sampleRate: Int): List<Float> {
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until fftSize) {
            var bit = fftSize shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }

        // Iterative Cooley-Tukey FFT
        var len = 2
        while (len <= fftSize) {
            val half = len shr 1
            val angleStep = -2.0 * PI / len
            for (start in 0 until fftSize step len) {
                for (k in 0 until half) {
                    val angle = angleStep * k
                    val wr = cos(angle).toFloat()
                    val wi = sin(angle).toFloat()
                    val evenIdx = start + k
                    val oddIdx = evenIdx + half
                    val tr = wr * re[oddIdx] - wi * im[oddIdx]
                    val ti = wr * im[oddIdx] + wi * re[oddIdx]
                    re[oddIdx] = re[evenIdx] - tr
                    im[oddIdx] = im[evenIdx] - ti
                    re[evenIdx] += tr
                    im[evenIdx] += ti
                }
            }
            len = len shl 1
        }

        val normFactor = 2.0f / fftSize
        val halfBins = fftSize / 2
        for (k in 0 until halfBins) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) * normFactor
            mags[k] = mag
        }

        val sr = sampleRate.coerceAtLeast(8000).toDouble()
        val hzPerBin = sr / fftSize.toDouble()
        val out = ArrayList<Float>(bandCount)

        for (b in 0 until bandCount) {
            val loBin = max(1, (bandEdgesHz[b] / hzPerBin).toInt())
            val hiBin = max(loBin + 1, min(halfBins - 1, (bandEdgesHz[b + 1] / hzPerBin).toInt()))
            var peakMag = 0f
            for (bin in loBin..hiBin) {
                if (mags[bin] > peakMag) peakMag = mags[bin]
            }
            val db = 20.0 * ln(peakMag.toDouble().coerceAtLeast(1e-5)) / ln(10.0)
            val norm = ((db + 58.0) / 52.0).toFloat().coerceIn(0.03f, 1.0f)
            smoothed[b] = clamp(smoothed[b] * 0.35f + norm * 0.65f, 0.03f, 1.0f)
            out.add(smoothed[b])
        }
        return out
    }
}


