package com.example.audio.io

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.example.audio.dsp.DspPipeline
import com.example.model.DspConfig
import com.example.model.EffectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

class AudioPlayerEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _outputLevel = MutableStateFlow(0f)
    val outputLevel: StateFlow<Float> = _outputLevel.asStateFlow()

    private val _gainReductionDb = MutableStateFlow(0f)
    val gainReductionDb: StateFlow<Float> = _gainReductionDb.asStateFlow()

    @Volatile
    private var activeBuffer: StereoAudioBuffer? = null

    @Volatile
    private var liveConfig: DspConfig = DspConfig()

    @Volatile
    private var loopEnabled: Boolean = false

    @Volatile
    private var loopStartFraction: Float = 0f

    @Volatile
    private var loopEndFraction: Float = 1f

    @Volatile
    private var pendingSeekFraction: Float? = null

    private var currentFrameIndex = 0

    fun setBuffer(buffer: StereoAudioBuffer?, resetPosition: Boolean = true) {
        val wasPlaying = _isPlaying.value
        if (wasPlaying) stop()
        activeBuffer = buffer
        if (resetPosition || buffer == null) {
            currentFrameIndex = 0
            _progress.value = 0f
        } else {
            currentFrameIndex = currentFrameIndex.coerceIn(0, (buffer.frameCount - 1).coerceAtLeast(0))
        }
    }

    fun updateDspConfig(config: DspConfig) {
        liveConfig = config
    }

    fun setLoopRange(enabled: Boolean, startFraction: Float, endFraction: Float) {
        loopEnabled = enabled
        loopStartFraction = startFraction.coerceIn(0f, 0.99f)
        loopEndFraction = endFraction.coerceIn(loopStartFraction + 0.01f, 1f)
    }

    fun seekTo(fraction: Float) {
        val buf = activeBuffer ?: return
        val clamped = fraction.coerceIn(0f, 1f)
        currentFrameIndex = (clamped * buf.frameCount).toInt().coerceIn(0, (buf.frameCount - 1).coerceAtLeast(0))
        _progress.value = clamped
        pendingSeekFraction = clamped
    }

    fun play() {
        val buf = activeBuffer ?: return
        if (buf.frameCount == 0) return
        if (_isPlaying.value) return

        _isPlaying.value = true
        playJob = scope.launch {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }

            val sampleRate = buf.sampleRate
            val minBufBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(max(minBufBytes, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val pipeline = DspPipeline(sampleRate)
            val blockFrames = 1024
            val blockL = FloatArray(blockFrames)
            val blockR = FloatArray(blockFrames)
            val pcmShorts = ShortArray(blockFrames * 2)

            // Real-time overlap-add time-stretch state
            val grainWin = 1024
            val hann = FloatArray(grainWin) { i ->
                (0.5 * (1.0 - cos(2.0 * PI * i / grainWin))).toFloat()
            }

            try {
                track.play()
                var tailCountdown = 0

                while (isActive && _isPlaying.value) {
                    val currentBuf = activeBuffer ?: break
                    val total = currentBuf.frameCount
                    if (total == 0) break

                    pendingSeekFraction?.let { frac ->
                        currentFrameIndex = (frac * total).toInt().coerceIn(0, total - 1)
                        pipeline.reset()
                        pendingSeekFraction = null
                    }

                    val cfg = liveConfig
                    val startFrame = if (loopEnabled) (loopStartFraction * total).toInt().coerceIn(0, total - 1) else 0
                    val endFrame = if (loopEnabled) (loopEndFraction * total).toInt().coerceIn(startFrame + 1, total) else total

                    val speedRate = if (!cfg.bypassAll && cfg.isEffectEnabled(EffectType.TIME_STRETCH)) {
                        cfg.timeStretch.rate.coerceIn(0.5f, 2.0f)
                    } else {
                        1.0f
                    }

                    var framesFilled = 0
                    if (currentFrameIndex < endFrame) {
                        if (abs(speedRate - 1.0f) < 0.02f) {
                            val avail = min(blockFrames, endFrame - currentFrameIndex)
                            System.arraycopy(currentBuf.left, currentFrameIndex, blockL, 0, avail)
                            System.arraycopy(currentBuf.right, currentFrameIndex, blockR, 0, avail)
                            if (avail < blockFrames) {
                                blockL.fill(0f, avail, blockFrames)
                                blockR.fill(0f, avail, blockFrames)
                            }
                            currentFrameIndex += avail
                            framesFilled = blockFrames
                        } else {
                            // Real-time pitch-preserving granular time stretch read
                            framesFilled = fillStretchedBlock(
                                currentBuf,
                                startFrame,
                                endFrame,
                                speedRate,
                                blockL,
                                blockR,
                                blockFrames,
                                hann
                            )
                        }
                    } else if (loopEnabled) {
                        currentFrameIndex = startFrame
                        continue
                    } else {
                        // Let reverb/echo tail finish naturally up to 1.2s
                        val reverbOn = cfg.isEffectEnabled(EffectType.ECHO_REVERB) && cfg.echoReverb.wetDryMix > 0.05f
                        if (reverbOn && tailCountdown == 0) {
                            tailCountdown = (sampleRate * 1.1f).toInt()
                        }
                        if (tailCountdown > 0) {
                            blockL.fill(0f)
                            blockR.fill(0f)
                            tailCountdown -= blockFrames
                            framesFilled = blockFrames
                        } else {
                            currentFrameIndex = 0
                            _progress.value = 0f
                            _isPlaying.value = false
                            break
                        }
                    }

                    pipeline.processStereoBlock(blockL, blockR, framesFilled, cfg)
                    _gainReductionDb.value = pipeline.compressorGainReductionDb

                    var peak = 0f
                    var sIdx = 0
                    for (i in 0 until framesFilled) {
                        val l = blockL[i].coerceIn(-1f, 1f)
                        val r = blockR[i].coerceIn(-1f, 1f)
                        val a = max(abs(l), abs(r))
                        if (a > peak) peak = a
                        pcmShorts[sIdx++] = (l * 32767f).toInt().toShort()
                        pcmShorts[sIdx++] = (r * 32767f).toInt().toShort()
                    }
                    _outputLevel.value = (_outputLevel.value * 0.35f + peak * 0.65f).coerceIn(0f, 1f)
                    _progress.value = (currentFrameIndex.toFloat() / total.toFloat()).coerceIn(0f, 1f)

                    track.write(pcmShorts, 0, framesFilled * 2)
                }
            } finally {
                _outputLevel.value = 0f
                _gainReductionDb.value = 0f
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
            }
        }
    }

    private fun fillStretchedBlock(
        buf: StereoAudioBuffer,
        startFrame: Int,
        endFrame: Int,
        rate: Float,
        outL: FloatArray,
        outR: FloatArray,
        blockFrames: Int,
        hann: FloatArray
    ): Int {
        outL.fill(0f)
        outR.fill(0f)
        val win = hann.size
        val hopOut = 256
        val hopIn = (hopOut * rate).toInt().coerceIn(64, win)
        var outCursor = 0
        var pos = currentFrameIndex

        while (outCursor < blockFrames && pos < endFrame) {
            val grainCopy = min(win, blockFrames - outCursor)
            for (k in 0 until grainCopy) {
                val srcIdx = (pos + k).coerceIn(startFrame, endFrame - 1)
                val w = hann[k]
                outL[outCursor + k] += buf.left[srcIdx] * w * 0.55f
                outR[outCursor + k] += buf.right[srcIdx] * w * 0.55f
            }
            pos += hopIn
            outCursor += hopOut
        }
        currentFrameIndex = pos.coerceAtMost(endFrame)
        return blockFrames
    }

    fun pause() {
        _isPlaying.value = false
        playJob?.cancel()
        playJob = null
        _outputLevel.value = 0f
    }

    fun stop() {
        _isPlaying.value = false
        playJob?.cancel()
        playJob = null
        currentFrameIndex = 0
        _progress.value = 0f
        _outputLevel.value = 0f
        _gainReductionDb.value = 0f
    }
}
