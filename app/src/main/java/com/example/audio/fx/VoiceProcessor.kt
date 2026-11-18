package com.example.audio.fx

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

enum class VoiceEffect {
    NONE,
    ROBOT,
    ECHO,
    DEEP,
    CHIPMUNK,
    CHILD_NATURAL,
    ABYSSAL_DRAGON,
    ALIEN_WIZARD,
}

/**
 * Live zero-latency microphone Voice Processor using Android AudioRecord + AudioTrack
 * with PitchShifter (glitch-free triangular OLA), Noise Gate, Ring-Modulation & Echo.
 */
class VoiceProcessor @JvmOverloads constructor(private val sampleRate: Int = 44100) {

    @Volatile var effect: VoiceEffect = VoiceEffect.NONE
    @Volatile var noiseGateEnabled: Boolean = true
    @Volatile var gateThreshold: Float = 0.015f

    /** Receives processed PCM frames for optional recording or waveform metering. */
    var onFrame: ((ShortArray) -> Unit)? = null

    private var job: Job? = null
    private var unityScope: CoroutineScope? = null

    private val deep = PitchShifter(0.74f, size = 2048)
    private val dragonLow = PitchShifter(0.62f, size = 2048)
    private val childNatural = PitchShifter(1.32f, size = 1536) // Natural child pitch without slowdown
    private val chipmunk = PitchShifter(1.50f, size = 1536)
    private val alienShift = PitchShifter(1.18f, size = 1536)

    private val echo = EchoLine(sampleRate, delayMs = 250, feedback = 0.35f)
    private val dragonEcho = EchoLine(sampleRate, delayMs = 160, feedback = 0.48f)

    private var robotPhase = 0.0
    private var alienPhase = 0.0
    private var dragonGrowlPhase = 0.0
    private var gateGain = 1f

    val isRunning: Boolean
        get() = job?.isActive == true

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return

        job = scope.launch(Dispatchers.IO) {
            var record: AudioRecord? = null
            var track: AudioTrack? = null
            var ns: NoiseSuppressor? = null
            var agc: AutomaticGainControl? = null
            var aec: AcousticEchoCanceler? = null
            try {
                val minRec = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(2048)
                val minTrack = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(2048)
                val frameSize = 1024

                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minRec, frameSize * 4),
                )
                track = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minTrack, frameSize * 4),
                    AudioTrack.MODE_STREAM,
                )

                ns = if (NoiseSuppressor.isAvailable()) {
                    runCatching { NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true } }.getOrNull()
                } else {
                    null
                }
                agc = if (AutomaticGainControl.isAvailable()) {
                    runCatching { AutomaticGainControl.create(record.audioSessionId)?.also { it.enabled = true } }.getOrNull()
                } else {
                    null
                }
                aec = if (AcousticEchoCanceler.isAvailable()) {
                    runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true } }.getOrNull()
                } else {
                    null
                }

                val buf = ShortArray(frameSize)
                if (record.state != AudioRecord.STATE_INITIALIZED || track.state != AudioTrack.STATE_INITIALIZED) {
                    return@launch
                }
                record.startRecording()
                track.play()
                while (isActive) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    process(buf, n)
                    track.write(buf, 0, n)
                    onFrame?.invoke(buf.copyOf(n))
                }
            } catch (_: Exception) {
            } finally {
                try { record?.stop() } catch (_: Exception) {}
                try { track?.stop() } catch (_: Exception) {}
                try { ns?.release() } catch (_: Exception) {}
                try { agc?.release() } catch (_: Exception) {}
                try { aec?.release() } catch (_: Exception) {}
                try { record?.release() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Unity C# AndroidJavaObject helper: launches live zero-latency voice processing
     * using an internal IO CoroutineScope without requiring coroutines on the C# side.
     */
    fun startForUnity() {
        unityScope?.coroutineContext?.get(Job)?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        unityScope = scope
        start(scope)
    }

    /**
     * Unity C# AndroidJavaObject helper: selects a real-time [VoiceEffect] by string name
     * (e.g. "DEEP", "CHIPMUNK", "ROBOT", "ECHO", "CHILD_NATURAL", "ABYSSAL_DRAGON", "NONE").
     */
    fun setEffectByName(name: String) {
        val clean = name.trim().uppercase()
        effect = runCatching { VoiceEffect.valueOf(clean) }.getOrDefault(VoiceEffect.NONE)
    }

    fun stop() {
        job?.cancel()
        job = null
        unityScope?.coroutineContext?.get(Job)?.cancel()
        unityScope = null
    }

    private fun process(buf: ShortArray, n: Int) {
        if (noiseGateEnabled) {
            var sum = 0.0
            for (i in 0 until n) {
                val s = buf[i] / 32768f
                sum += s * s
            }
            val rms = sqrt(sum / n).toFloat()
            val target = if (rms < gateThreshold) 0f else 1f
            gateGain += (target - gateGain) * 0.2f
        } else {
            gateGain = 1f
        }

        val fx = effect
        for (i in 0 until n) {
            var x = (buf[i] / 32768f) * gateGain

            x = when (fx) {
                VoiceEffect.NONE -> x
                VoiceEffect.DEEP -> deep.process(x)
                VoiceEffect.CHILD_NATURAL -> childNatural.process(x) * 0.72f + x * 0.28f
                VoiceEffect.CHIPMUNK -> chipmunk.process(x)
                VoiceEffect.ECHO -> echo.process(x)
                VoiceEffect.ROBOT -> {
                    robotPhase += 2.0 * PI * 80.0 / sampleRate
                    if (robotPhase > 2.0 * PI) robotPhase -= 2.0 * PI
                    (x * sin(robotPhase)).toFloat() * 1.15f
                }
                VoiceEffect.ABYSSAL_DRAGON -> {
                    dragonGrowlPhase += 2.0 * PI * 12.0 / sampleRate
                    if (dragonGrowlPhase > 2.0 * PI) dragonGrowlPhase -= 2.0 * PI
                    val growl = (1.0 + 0.28 * sin(dragonGrowlPhase)).toFloat()
                    dragonEcho.process(dragonLow.process(x) * growl)
                }
                VoiceEffect.ALIEN_WIZARD -> {
                    alienPhase += 2.0 * PI * 38.0 / sampleRate
                    if (alienPhase > 2.0 * PI) alienPhase -= 2.0 * PI
                    val shifted = alienShift.process(x)
                    val mod = (0.78 + 0.22 * sin(alienPhase)).toFloat()
                    echo.process(shifted * mod)
                }
            }

            buf[i] = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }
}

/** Real-time PitchShifter with triangular overlap-add crossfading (ratio < 1 deeper, ratio > 1 brighter). */
private class PitchShifter(private val ratio: Float, private val size: Int = 2048) {
    private val buf = FloatArray(size)
    private var writePos = 0
    private var phase = 0f

    fun process(x: Float): Float {
        buf[writePos] = x
        writePos = (writePos + 1) % size

        phase += (1f - ratio) / size
        if (phase >= 1f) phase -= 1f
        if (phase < 0f) phase += 1f

        val p2 = (phase + 0.5f) % 1f
        val g1 = 1f - abs(2f * phase - 1f)
        val g2 = 1f - abs(2f * p2 - 1f)
        return read(phase * size) * g1 + read(p2 * size) * g2
    }

    private fun read(delay: Float): Float {
        var pos = writePos - delay
        while (pos < 0f) pos += size
        val i = pos.toInt()
        val f = pos - i
        val a = buf[i % size]
        val b = buf[(i + 1) % size]
        return a + (b - a) * f
    }
}

private class EchoLine(sampleRate: Int, delayMs: Int, private val feedback: Float) {
    private val size = (sampleRate * delayMs / 1000).coerceAtLeast(64)
    private val buf = FloatArray(size)
    private var pos = 0

    fun process(x: Float): Float {
        val delayed = buf[pos]
        buf[pos] = (x + delayed * feedback).coerceIn(-1.4f, 1.4f)
        pos = (pos + 1) % size
        return x * 0.85f + delayed * 0.55f
    }
}
