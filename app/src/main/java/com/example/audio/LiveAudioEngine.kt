package com.example.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.example.model.DspConfig
import com.example.model.EffectType
import com.example.model.PlaybackTelemetry
import com.example.model.RecorderTelemetry
import com.example.model.StereoAudioBuffer
import com.example.model.TtsDialect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

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
    private var latestLevel = 0f

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
    private var level = 0f

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
            ),
        )
    }
}

class AndroidTtsEngine(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var initReady = false
    private val initDeferred = CompletableDeferred<Boolean>()
    private var previewDoneCallback: (() -> Unit)? = null
    private val synthWaiters = mutableMapOf<String, CompletableDeferred<Boolean>>()

    init {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                initReady = status == TextToSpeech.SUCCESS
                if (!initDeferred.isCompleted) initDeferred.complete(initReady)
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "preview") {
                        previewDoneCallback?.invoke()
                    } else if (utteranceId != null) {
                        synthWaiters.remove(utteranceId)?.complete(true)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == "preview") {
                        previewDoneCallback?.invoke()
                    } else if (utteranceId != null) {
                        synthWaiters.remove(utteranceId)?.complete(false)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == "preview") {
                        previewDoneCallback?.invoke()
                    } else if (utteranceId != null) {
                        synthWaiters.remove(utteranceId)?.complete(false)
                    }
                }
            })
        }.onFailure {
            initReady = false
            if (!initDeferred.isCompleted) initDeferred.complete(false)
        }
    }

    private fun resolveLocale(engine: TextToSpeech, dialect: TtsDialect) {
        val primary = Locale.forLanguageTag(dialect.localeTag)
        val res = engine.setLanguage(primary)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.forLanguageTag(dialect.fallbackLocaleTag))
        }
    }

    suspend fun preview(
        text: String,
        dialect: TtsDialect,
        speechRate: Float,
        pitch: Float,
        onFinished: () -> Unit,
    ): Boolean {
        val ready = initDeferred.await()
        val engine = tts ?: return false
        if (!ready) return false
        resolveLocale(engine, dialect)
        engine.setSpeechRate(clamp(speechRate, 0.5f, 1.8f))
        engine.setPitch(clamp(pitch, 0.6f, 1.6f))
        previewDoneCallback = onFinished
        val params = Bundle()
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "preview")
        return result == TextToSpeech.SUCCESS
    }

    fun stopPreview() {
        runCatching { tts?.stop() }
        previewDoneCallback?.invoke()
    }

    suspend fun synthesizeToBuffer(
        text: String,
        dialect: TtsDialect,
        speechRate: Float,
        pitch: Float,
    ): StereoAudioBuffer? = withContext(Dispatchers.IO) {
        val ready = initDeferred.await()
        val engine = tts ?: return@withContext null
        if (!ready) return@withContext null

        resolveLocale(engine, dialect)
        engine.setSpeechRate(clamp(speechRate, 0.5f, 1.8f))
        engine.setPitch(clamp(pitch, 0.6f, 1.6f))

        val outFile = File(context.cacheDir, "tts_${UUID.randomUUID()}.wav")
        val uttId = UUID.randomUUID().toString()
        val waiter = CompletableDeferred<Boolean>()
        synthWaiters[uttId] = waiter

        val res = engine.synthesizeToFile(text, Bundle(), outFile, uttId)
        if (res != TextToSpeech.SUCCESS) {
            synthWaiters.remove(uttId)
            outFile.delete()
            return@withContext null
        }

        val ok = waiter.await()
        val decoded = if (ok && outFile.exists()) AudioDecoder.decodeFile(outFile) else null
        outFile.delete()
        decoded
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }
}
