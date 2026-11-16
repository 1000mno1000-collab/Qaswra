package com.example.audio.io

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

enum class RecordingState {
    IDLE,
    RECORDING,
    PAUSED
}

class AudioRecorderEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null

    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _inputLevel = MutableStateFlow(0f)
    val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _liveWaveform = MutableStateFlow<List<Float>>(emptyList())
    val liveWaveform: StateFlow<List<Float>> = _liveWaveform.asStateFlow()

    private val chunksLock = Any()
    private val recordedChunks = mutableListOf<FloatArray>()
    private var totalFrames = 0

    @SuppressLint("MissingPermission")
    fun startOrResume(): Boolean {
        if (_state.value == RecordingState.RECORDING) return true
        if (_state.value == RecordingState.IDLE) {
            synchronized(chunksLock) {
                recordedChunks.clear()
                totalFrames = 0
            }
            _durationMs.value = 0L
            _liveWaveform.value = emptyList()
        }

        val sampleRate = WavAudioCodec.TARGET_SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (_: SecurityException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return false
        }

        _state.value = RecordingState.RECORDING
        recordJob = scope.launch {
            val readBuf = ShortArray(2048)
            var peakWindowMax = 0f
            var peakSamples = 0
            val waveformAccum = _liveWaveform.value.toMutableList()

            try {
                audioRecord.startRecording()
                while (isActive && _state.value == RecordingState.RECORDING) {
                    val read = audioRecord.read(readBuf, 0, readBuf.size)
                    if (read > 0) {
                        val floatChunk = FloatArray(read)
                        var localPeak = 0f
                        for (i in 0 until read) {
                            val s = readBuf[i] / 32768f
                            floatChunk[i] = s
                            val a = abs(s)
                            if (a > localPeak) localPeak = a
                        }
                        synchronized(chunksLock) {
                            recordedChunks.add(floatChunk)
                            totalFrames += read
                            _durationMs.value = (totalFrames * 1000L) / sampleRate
                        }
                        _inputLevel.value = (_inputLevel.value * 0.4f + localPeak * 0.6f).coerceIn(0f, 1f)

                        peakWindowMax = max(peakWindowMax, localPeak)
                        peakSamples += read
                        if (peakSamples >= sampleRate / 25) {
                            waveformAccum.add(peakWindowMax.coerceIn(0.03f, 1f))
                            if (waveformAccum.size > 160) {
                                waveformAccum.removeAt(0)
                            }
                            _liveWaveform.value = waveformAccum.toList()
                            peakWindowMax = 0f
                            peakSamples = 0
                        }
                    }
                }
            } finally {
                runCatching { audioRecord.stop() }
                runCatching { audioRecord.release() }
                _inputLevel.value = 0f
            }
        }
        return true
    }

    fun pause() {
        if (_state.value != RecordingState.RECORDING) return
        _state.value = RecordingState.PAUSED
        recordJob?.cancel()
        recordJob = null
        _inputLevel.value = 0f
    }

    fun stopAndBuildBuffer(): StereoAudioBuffer? {
        _state.value = RecordingState.IDLE
        recordJob?.cancel()
        recordJob = null
        _inputLevel.value = 0f

        val merged = synchronized(chunksLock) {
            if (totalFrames <= 0) return@synchronized null
            val mono = FloatArray(totalFrames)
            var offset = 0
            for (chunk in recordedChunks) {
                System.arraycopy(chunk, 0, mono, offset, chunk.size)
                offset += chunk.size
            }
            recordedChunks.clear()
            totalFrames = 0
            mono
        } ?: return null

        _durationMs.value = 0L
        _liveWaveform.value = emptyList()
        return StereoAudioBuffer.fromMono(merged, WavAudioCodec.TARGET_SAMPLE_RATE)
    }

    fun discard() {
        _state.value = RecordingState.IDLE
        recordJob?.cancel()
        recordJob = null
        synchronized(chunksLock) {
            recordedChunks.clear()
            totalFrames = 0
        }
        _inputLevel.value = 0f
        _durationMs.value = 0L
        _liveWaveform.value = emptyList()
    }
}
