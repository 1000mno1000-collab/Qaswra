package com.example.audio.io

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class StereoAudioBuffer(
    val left: FloatArray,
    val right: FloatArray,
    val sampleRate: Int = 44100
) {
    val frameCount: Int get() = min(left.size, right.size)

    val durationMs: Long
        get() = if (sampleRate > 0) (frameCount * 1000L) / sampleRate else 0L

    fun computeWaveformPeaks(buckets: Int = 120): List<Float> {
        val total = frameCount
        if (total == 0 || buckets <= 0) return emptyList()
        val result = ArrayList<Float>(buckets)
        val step = total.toFloat() / buckets
        for (i in 0 until buckets) {
            val start = (i * step).toInt().coerceIn(0, total - 1)
            val end = ((i + 1) * step).toInt().coerceIn(start + 1, total)
            var peak = 0f
            // Sample stride for very long recordings to keep UI instant
            val stride = max(1, (end - start) / 64)
            var idx = start
            while (idx < end) {
                val amp = max(abs(left[idx]), abs(right[idx]))
                if (amp > peak) peak = amp
                idx += stride
            }
            result.add(peak.coerceIn(0f, 1f))
        }
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StereoAudioBuffer) return false
        return sampleRate == other.sampleRate && left.contentEquals(other.left) && right.contentEquals(other.right)
    }

    override fun hashCode(): Int {
        var result = left.contentHashCode()
        result = 31 * result + right.contentHashCode()
        result = 31 * result + sampleRate
        return result
    }

    companion object {
        fun fromMono(mono: FloatArray, sampleRate: Int = 44100): StereoAudioBuffer =
            StereoAudioBuffer(left = mono.copyOf(), right = mono.copyOf(), sampleRate = sampleRate)
    }
}
