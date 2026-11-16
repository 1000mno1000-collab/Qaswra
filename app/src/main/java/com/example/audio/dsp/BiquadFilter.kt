package com.example.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class StereoBiquadFilter {
    private var b0 = 1.0f
    private var b1 = 0.0f
    private var b2 = 0.0f
    private var a1 = 0.0f
    private var a2 = 0.0f

    private var z1L = 0.0f
    private var z2L = 0.0f
    private var z1R = 0.0f
    private var z2R = 0.0f

    private var lastKey = 0L

    fun reset() {
        z1L = 0f
        z2L = 0f
        z1R = 0f
        z2R = 0f
    }

    fun configurePeaking(sampleRate: Int, freqHz: Float, q: Float, gainDb: Float) {
        val key = (1L shl 50) xor (freqHz.toBits().toLong() shl 20) xor gainDb.toBits().toLong()
        if (key == lastKey) return
        lastKey = key

        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz.coerceIn(20f, sampleRate * 0.45f) / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q.coerceAtLeast(0.1f))

        val nb0 = 1.0 + alpha * a
        val nb1 = -2.0 * cosW0
        val nb2 = 1.0 - alpha * a
        val na0 = 1.0 + alpha / a
        val na1 = -2.0 * cosW0
        val na2 = 1.0 - alpha / a

        setNormalized(nb0, nb1, nb2, na0, na1, na2)
    }

    fun configureLowShelf(sampleRate: Int, freqHz: Float, slope: Float, gainDb: Float) {
        val key = (2L shl 50) xor (freqHz.toBits().toLong() shl 20) xor gainDb.toBits().toLong()
        if (key == lastKey) return
        lastKey = key

        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz.coerceIn(20f, sampleRate * 0.45f) / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope.coerceAtLeast(0.1f) - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val nb0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
        val nb1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
        val nb2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
        val na0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
        val na1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
        val na2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha

        setNormalized(nb0, nb1, nb2, na0, na1, na2)
    }

    fun configureHighShelf(sampleRate: Int, freqHz: Float, slope: Float, gainDb: Float) {
        val key = (3L shl 50) xor (freqHz.toBits().toLong() shl 20) xor gainDb.toBits().toLong()
        if (key == lastKey) return
        lastKey = key

        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz.coerceIn(20f, sampleRate * 0.45f) / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope.coerceAtLeast(0.1f) - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val nb0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
        val nb1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
        val nb2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
        val na0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
        val na1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
        val na2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha

        setNormalized(nb0, nb1, nb2, na0, na1, na2)
    }

    fun configureLowPass(sampleRate: Int, freqHz: Float, q: Float = 0.707f) {
        val key = (4L shl 50) xor (freqHz.toBits().toLong() shl 20) xor q.toBits().toLong()
        if (key == lastKey) return
        lastKey = key

        val w0 = 2.0 * PI * freqHz.coerceIn(40f, sampleRate * 0.45f) / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)

        val nb0 = (1.0 - cosW0) / 2.0
        val nb1 = 1.0 - cosW0
        val nb2 = (1.0 - cosW0) / 2.0
        val na0 = 1.0 + alpha
        val na1 = -2.0 * cosW0
        val na2 = 1.0 - alpha

        setNormalized(nb0, nb1, nb2, na0, na1, na2)
    }

    private fun setNormalized(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        val invA0 = 1.0 / na0
        b0 = (nb0 * invA0).toFloat()
        b1 = (nb1 * invA0).toFloat()
        b2 = (nb2 * invA0).toFloat()
        a1 = (na1 * invA0).toFloat()
        a2 = (na2 * invA0).toFloat()
    }

    fun processBlock(left: FloatArray, right: FloatArray, numFrames: Int) {
        for (i in 0 until numFrames) {
            val inL = left[i]
            val outL = b0 * inL + z1L
            z1L = b1 * inL - a1 * outL + z2L
            z2L = b2 * inL - a2 * outL
            left[i] = outL

            val inR = right[i]
            val outR = b0 * inR + z1R
            z1R = b1 * inR - a1 * outR + z2R
            z2R = b2 * inR - a2 * outR
            right[i] = outR
        }
    }
}
