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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * الاستخدام:
 *
 *   val vp = VoiceProcessor()
 *   vp.effect = VoiceEffect.DEEP
 *   vp.start(lifecycleScope)     // لازم صلاحية RECORD_AUDIO تكون ممنوحة قبل
 *   ...
 *   vp.stop()
 *
 * مهم: استخدم سماعات أذن، وإلا يصير صدى (feedback) من المايك للسبيكر.
 * ما يحتاج أي مكتبة إضافية غير kotlinx-coroutines.
 */

enum class VoiceEffect { NONE, ROBOT, ECHO, DEEP, CHIPMUNK }

class VoiceProcessor(private val sampleRate: Int = 44100) {

    @Volatile var effect: VoiceEffect = VoiceEffect.NONE
    @Volatile var noiseGateEnabled: Boolean = true
    @Volatile var gateThreshold: Float = 0.015f // أرفعه إذا الضوضاء كثيرة

    /** يستلم كل مقطع بعد المعالجة (مثلاً لتسجيله بملف). اختياري. */
    var onFrame: ((ShortArray) -> Unit)? = null

    private var job: Job? = null

    // المؤثرات
    private val deep = PitchShifter(0.75f)
    private val chipmunk = PitchShifter(1.5f)
    private val echo = EchoLine(sampleRate, delayMs = 250, feedback = 0.35f)
    private var robotPhase = 0.0
    private var gateGain = 1f

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return

        job = scope.launch(Dispatchers.IO) {
            val minRec = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val minTrack = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val frameSize = 1024

            // VOICE_COMMUNICATION يفعّل معالجة النظام (ضوضاء/صدى) عند توفرها
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minRec, frameSize * 4)
            )
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minTrack, frameSize * 4),
                AudioTrack.MODE_STREAM
            )

            // معالجات النظام الجاهزة (إزالة ضوضاء، تقوية تلقائية، إلغاء صدى)
            val ns = if (NoiseSuppressor.isAvailable())
                NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true } else null
            val agc = if (AutomaticGainControl.isAvailable())
                AutomaticGainControl.create(record.audioSessionId)?.also { it.enabled = true } else null
            val aec = if (AcousticEchoCanceler.isAvailable())
                AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true } else null

            val buf = ShortArray(frameSize)
            try {
                record.startRecording()
                track.play()
                while (isActive) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    process(buf, n)
                    track.write(buf, 0, n)
                    onFrame?.invoke(buf.copyOf(n))
                }
            } finally {
                runCatching { record.stop() }
                runCatching { track.stop() }
                ns?.release(); agc?.release(); aec?.release()
                record.release(); track.release()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun process(buf: ShortArray, n: Int) {
        // 1) بوابة ضوضاء: تخفض الصوت لما يكون المستوى واطي (سكوت/ضوضاء خلفية)
        if (noiseGateEnabled) {
            var sum = 0.0
            for (i in 0 until n) { val s = buf[i] / 32768f; sum += s * s }
            val rms = sqrt(sum / n).toFloat()
            val target = if (rms < gateThreshold) 0f else 1f
            // نعومة بالفتح والإغلاق حتى ما يصير طقطقة
            gateGain += (target - gateGain) * 0.2f
        } else gateGain = 1f

        val fx = effect
        for (i in 0 until n) {
            var x = buf[i] / 32768f * gateGain

            x = when (fx) {
                VoiceEffect.NONE -> x
                VoiceEffect.DEEP -> deep.process(x)
                VoiceEffect.CHIPMUNK -> chipmunk.process(x)
                VoiceEffect.ECHO -> echo.process(x)
                VoiceEffect.ROBOT -> {
                    // تعديل حلقي (ring modulation) بتردد 80Hz
                    robotPhase += 2.0 * PI * 80.0 / sampleRate
                    if (robotPhase > 2.0 * PI) robotPhase -= 2.0 * PI
                    (x * sin(robotPhase)).toFloat()
                }
            }

            buf[i] = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }
}

/** تغيير طبقة الصوت لحظياً. ratio < 1 أغلظ، ratio > 1 أرفع. */
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
        // نافذة مثلثية: المجموع دائماً = 1 فما يصير تذبذب بالمستوى
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

/** صدى بسيط بتأخير وتغذية راجعة. */
private class EchoLine(sampleRate: Int, delayMs: Int, private val feedback: Float) {
    private val size = sampleRate * delayMs / 1000
    private val buf = FloatArray(size)
    private var pos = 0

    fun process(x: Float): Float {
        val delayed = buf[pos]
        buf[pos] = x + delayed * feedback
        pos = (pos + 1) % size
        return x + delayed * 0.6f
    }
}
