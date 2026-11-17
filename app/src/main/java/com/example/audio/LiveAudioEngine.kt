package com.example.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.example.model.DspConfig
import com.example.model.EffectType
import com.example.model.PlaybackTelemetry
import com.example.model.RecorderTelemetry
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class StudioAudioPlayer(
    initialConfig: DspConfig,
    private val onTelemetry: (PlaybackTelemetry) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var buffer: StereoAudioBuffer? = null
    @Volatile private var config: DspConfig = initialConfig.normalized()
    private var pipeline: DspPipeline? = null

    @Volatile private var playhead: Double = 0.0
    private val isPlaying = AtomicBoolean(false)
    @Volatile private var loopEnabled = false
    @Volatile private var loopStartFrac = 0f
    @Volatile private var loopEndFrac = 1f

    private var playbackJob: Job? = null
    private var audioTrack: AudioTrack? = null

    private var latestBars = List(48) { 0.06f }
    private var latestSpectrum = List(32) { 0.03f }
    private var latestLevel = 0f
    private val spectrumAnalyzer = RealTimeSpectrumAnalyzer()

    fun setBuffer(buffer: StereoAudioBuffer?, resetPosition: Boolean = true) {
        this.buffer = buffer
        if (resetPosition) {
            playhead = 0.0
            pipeline?.reset()
        } else if (buffer != null) {
            playhead = clamp(playhead, 0.0, max(0, buffer.frameCount - 1).toDouble())
        }
        emitTelemetry()
    }

    fun updateConfig(config: DspConfig) {
        val norm = config.normalized()
        this.config = norm
        pipeline?.updateConfig(norm)
    }

    fun setLoop(enabled: Boolean, startFrac: Float = 0f, endFrac: Float = 1f) {
        loopEnabled = enabled
        loopStartFrac = clamp(startFrac, 0f, 0.99f)
        loopEndFrac = clamp(endFrac, loopStartFrac + 0.01f, 1f)
    }

    fun seekToFraction(frac: Float) {
        val buf = buffer ?: return
        val total = buf.frameCount
        playhead = clamp(frac, 0f, 1f) * max(0, total - 1).toDouble()
        pipeline?.reset()
        emitTelemetry()
    }

    fun play() {
        val buf = buffer ?: return
        if (buf.frameCount == 0) return

        stopAudioThreadOnly()

        val sr = buf.sampleRate.coerceAtLeast(8000)
        if (pipeline == null) {
            pipeline = DspPipeline(sr, config)
        } else {
            pipeline?.updateConfig(config)
        }

        val total = buf.frameCount
        if (loopEnabled) {
            val s = (loopStartFrac * total).toInt()
            val e = (loopEndFrac * total).toInt()
            if (playhead < s || playhead >= e) playhead = s.toDouble()
        } else if (playhead >= total - 1) {
            playhead = 0.0
        }

        val minBufBytes = AudioTrack.getMinBufferSize(
            sr,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(2048 * 8)

        val track = AudioTrack.Builder()
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

        audioTrack = track
        isPlaying.set(true)
        track.play()
        emitTelemetry()

        playbackJob = scope.launch {
            val blockFrames = 2048
            val tmpL = FloatArray(blockFrames)
            val tmpR = FloatArray(blockFrames)
            val interleaved = FloatArray(blockFrames * 2)
            var tailFlushRemaining = 0

            try {
                while (isActive && isPlaying.get()) {
                    val activeBuf = buffer ?: break
                    val activePipeline = pipeline ?: break
                    val framesTotal = activeBuf.frameCount
                    if (framesTotal == 0) break

                    val rangeStart = if (loopEnabled) (loopStartFrac * framesTotal).toInt() else 0
                    val rangeEnd = if (loopEnabled) {
                        max(rangeStart + 1, (loopEndFrac * framesTotal).toInt())
                    } else {
                        framesTotal
                    }

                    val cfg = config
                    val tsSlot = cfg.chain.firstOrNull { it.type == EffectType.TIME_STRETCH }
                    val rateRatio = if (!cfg.bypassAll && tsSlot?.enabled == true) {
                        clamp(cfg.timeStretch.tempoRatio, 0.5f, 2.0f).toDouble()
                    } else {
                        1.0
                    }

                    var reachedEnd = false
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
                            latestBars = List(48) { 0.06f }
                            latestSpectrum = List(32) { 0.03f }
                            break
                        }
                    }
                    emitTelemetry()
                }
            } finally {
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
                if (audioTrack === track) audioTrack = null
                emitTelemetry()
            }
        }
    }

    fun pause() {
        isPlaying.set(false)
        latestLevel = 0f
        stopAudioThreadOnly()
        emitTelemetry()
    }

    fun stop() {
        isPlaying.set(false)
        playhead = 0.0
        latestLevel = 0f
        latestBars = List(48) { 0.06f }
        latestSpectrum = List(32) { 0.03f }
        pipeline?.reset()
        stopAudioThreadOnly()
        emitTelemetry()
    }

    fun dispose() {
        stop()
    }

    private fun stopAudioThreadOnly() {
        playbackJob?.cancel()
        playbackJob = null
        val t = audioTrack
        audioTrack = null
        if (t != null) {
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.release() }
        }
    }

    private fun emitTelemetry() {
        val buf = buffer
        val frames = buf?.frameCount ?: 0
        val sr = buf?.sampleRate ?: TARGET_SAMPLE_RATE
        val durationMs = if (sr > 0) (frames * 1000L) / sr else 0L
        val posMs = if (sr > 0) ((playhead * 1000.0) / sr).toLong().coerceIn(0L, durationMs) else 0L
        onTelemetry(
            PlaybackTelemetry(
                isPlaying = isPlaying.get(),
                positionMs = posMs,
                durationMs = durationMs,
                outputLevel = latestLevel,
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
        onTelemetry(
            RecorderTelemetry(
                isRecording = isRecording,
                isPaused = isPaused,
                elapsedMs = elapsedMs,
                micLevel = level,
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


