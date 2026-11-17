package com.example.audio

import com.example.model.StereoAudioBuffer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

const val TARGET_SAMPLE_RATE = 44100

fun encodeStereoWavBytes(buffer: StereoAudioBuffer): ByteArray {
    val numFrames = buffer.frameCount
    val numChannels = 2
    val bitsPerSample = 16
    val byteRate = buffer.sampleRate * numChannels * (bitsPerSample / 8)
    val blockAlign = numChannels * (bitsPerSample / 8)
    val dataSize = numFrames * blockAlign
    val totalBytes = 44 + dataSize

    val out = ByteArray(totalBytes)
    val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

    bb.put("RIFF".toByteArray(Charsets.US_ASCII))
    bb.putInt(36 + dataSize)
    bb.put("WAVE".toByteArray(Charsets.US_ASCII))
    bb.put("fmt ".toByteArray(Charsets.US_ASCII))
    bb.putInt(16) // PCM header chunk size
    bb.putShort(1) // PCM format
    bb.putShort(numChannels.toShort())
    bb.putInt(buffer.sampleRate)
    bb.putInt(byteRate)
    bb.putShort(blockAlign.toShort())
    bb.putShort(bitsPerSample.toShort())
    bb.put("data".toByteArray(Charsets.US_ASCII))
    bb.putInt(dataSize)

    for (i in 0 until numFrames) {
        val l = (clamp(buffer.left[i], -1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
        val r = (clamp(buffer.right[i], -1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
        bb.putShort(l.toShort())
        bb.putShort(r.toShort())
    }
    return out
}

fun decodeWavBytes(bytes: ByteArray): StereoAudioBuffer? {
    if (bytes.size < 44) return null
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val riff = ByteArray(4)
    bb.get(riff)
    if (!riff.contentEquals("RIFF".toByteArray(Charsets.US_ASCII))) return null
    bb.int // chunk size
    val wave = ByteArray(4)
    bb.get(wave)
    if (!wave.contentEquals("WAVE".toByteArray(Charsets.US_ASCII))) return null

    var audioFormat = 1
    var channels = 2
    var sampleRate = TARGET_SAMPLE_RATE
    var bitsPerSample = 16
    var dataOffset = -1
    var dataSize = 0

    while (bb.remaining() >= 8) {
        val chunkId = ByteArray(4)
        bb.get(chunkId)
        val rawChunkSize = bb.int
        if (rawChunkSize < 0) break
        val available = bb.remaining()
        val idStr = String(chunkId, Charsets.US_ASCII)
        when (idStr) {
            "fmt " -> {
                val safeSize = minOf(rawChunkSize, available)
                val chunkStart = bb.position()
                if (safeSize >= 16) {
                    audioFormat = bb.short.toInt() and 0xFFFF
                    channels = bb.short.toInt() and 0xFFFF
                    sampleRate = bb.int
                    bb.int // byteRate
                    bb.short // blockAlign
                    bitsPerSample = bb.short.toInt() and 0xFFFF
                    if (audioFormat == 0xFFFE && safeSize >= 26) {
                        bb.short // cbSize
                        bb.short // wValidBitsPerSample
                        bb.int // dwChannelMask
                        val subFormat = bb.short.toInt() and 0xFFFF
                        if (subFormat == 1 || subFormat == 3) {
                            audioFormat = subFormat
                        }
                    }
                }
                val padded = rawChunkSize + (rawChunkSize and 1)
                val nextPos = minOf(bb.limit(), chunkStart + padded)
                bb.position(nextPos)
            }
            "data" -> {
                dataOffset = bb.position()
                dataSize = if (rawChunkSize == 0 || rawChunkSize > available) available else rawChunkSize
                break
            }
            else -> {
                val padded = rawChunkSize + (rawChunkSize and 1)
                if (padded > available) break
                bb.position(bb.position() + padded)
            }
        }
    }

    if (dataOffset < 0 || dataSize <= 0 || channels <= 0) return null
    if (audioFormat != 1 && audioFormat != 3) return null

    bb.position(dataOffset)
    val bytesPerSample = bitsPerSample / 8
    if (bytesPerSample <= 0) return null
    val frameBytes = channels * bytesPerSample
    val totalFrames = dataSize / frameBytes
    if (totalFrames <= 0) return null

    val left = FloatArray(totalFrames)
    val right = FloatArray(totalFrames)

    for (i in 0 until totalFrames) {
        var lSample = 0f
        var rSample = 0f
        for (ch in 0 until channels) {
            val v = when {
                audioFormat == 3 && bitsPerSample == 32 -> bb.float
                bitsPerSample == 16 -> bb.short / 32768f
                bitsPerSample == 8 -> ((bb.get().toInt() and 0xFF) - 128) / 128f
                bitsPerSample == 24 -> {
                    val b0 = bb.get().toInt() and 0xFF
                    val b1 = bb.get().toInt() and 0xFF
                    val b2 = bb.get().toInt()
                    ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                }
                bitsPerSample == 32 -> bb.int / 2147483648f
                else -> 0f
            }
            if (ch == 0) {
                lSample = clamp(v, -1f, 1f)
                rSample = lSample
            } else if (ch == 1) {
                rSample = clamp(v, -1f, 1f)
            }
        }
        left[i] = lSample
        right[i] = rSample
    }

    val raw = StereoAudioBuffer(left, right, sampleRate)
    return if (sampleRate != TARGET_SAMPLE_RATE) resampleStereo(raw, TARGET_SAMPLE_RATE) else raw
}

fun writeStereoWavFile(file: File, buffer: StereoAudioBuffer) {
    file.parentFile?.mkdirs()
    file.writeBytes(encodeStereoWavBytes(buffer))
}

fun readStereoWavFile(file: File): StereoAudioBuffer? {
    if (!file.exists()) return null
    return decodeWavBytes(file.readBytes())
}
