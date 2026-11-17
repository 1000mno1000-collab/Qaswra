package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.model.ExportAudioFormat
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class ExportedAudioResult(
    val file: File,
    val extension: String,
    val mimeType: String,
)

private fun computeFlacCrc8(data: ByteArray, len: Int): Int {
    var crc = 0
    for (i in 0 until len) {
        crc = crc xor (data[i].toInt() and 0xff)
        for (b in 0 until 8) {
            crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xff else (crc shl 1) and 0xff
        }
    }
    return crc and 0xff
}

private fun computeFlacCrc16(data: ByteArray): Int {
    var crc = 0
    for (byte in data) {
        crc = crc xor ((byte.toInt() and 0xff) shl 8)
        for (b in 0 until 8) {
            crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x8005) and 0xffff else (crc shl 1) and 0xffff
        }
    }
    return crc and 0xffff
}

private fun appendUtf8Number(out: MutableList<Int>, value: Int) {
    if (value < 0x80) {
        out.add(value)
        return
    }
    val bytesNeeded = when {
        value < 0x800 -> 2
        value < 0x10000 -> 3
        value < 0x200000 -> 4
        value < 0x4000000 -> 5
        else -> 6
    }
    val leadMask = (0xff shl (8 - bytesNeeded)) and 0xff
    val firstShift = (bytesNeeded - 1) * 6
    out.add(leadMask or floor(value / 2.0.pow(firstShift)).toInt())
    for (i in (bytesNeeded - 2) downTo 0) {
        val shift = i * 6
        out.add(0x80 or (floor(value / 2.0.pow(shift)).toInt() and 0x3f))
    }
}

private fun buildFlacFrameHeader(
    frameNumber: Int,
    blockSize: Int,
    sampleRate: Int,
): ByteArray {
    val h = mutableListOf(0xff, 0xf8)
    val bsCode = when (blockSize) {
        4096 -> 0x0c
        2048 -> 0x0b
        1024 -> 0x0a
        else -> 0x07
    }
    val srCode = when (sampleRate) {
        44100 -> 0x09
        48000 -> 0x0a
        32000 -> 0x08
        24000 -> 0x06
        22050 -> 0x05
        16000 -> 0x04
        8000 -> 0x03
        else -> 0x00
    }
    h.add((bsCode shl 4) or srCode)
    h.add(0x18) // 2 ch independent | 16-bit
    appendUtf8Number(h, frameNumber)
    if (bsCode == 0x07) {
        val bsMinusOne = clamp(blockSize - 1, 0, 65535)
        h.add((bsMinusOne shr 8) and 0xff)
        h.add(bsMinusOne and 0xff)
    }
    val raw = ByteArray(h.size + 1)
    for (i in h.indices) raw[i] = h[i].toByte()
    raw[h.size] = computeFlacCrc8(raw, h.size).toByte()
    return raw
}

private fun encodeFlacBytes(
    buffer: StereoAudioBuffer,
    onProgress: ((Float) -> Unit)?,
): ByteArray {
    val totalSamples = buffer.frameCount
    val sampleRate = clamp(buffer.sampleRate, 1, 655350)
    val blockSize = 4096
    val stream = ByteArrayOutputStream()

    val intro = ByteArray(42)
    intro[0] = 0x66
    intro[1] = 0x4c
    intro[2] = 0x61
    intro[3] = 0x43
    intro[4] = 0x80.toByte()
    intro[5] = 0x00
    intro[6] = 0x00
    intro[7] = 34

    val minBlock = if (totalSamples in 16 until blockSize) totalSamples else blockSize
    intro[8] = ((minBlock shr 8) and 0xff).toByte()
    intro[9] = (minBlock and 0xff).toByte()
    intro[10] = ((blockSize shr 8) and 0xff).toByte()
    intro[11] = (blockSize and 0xff).toByte()

    val packed = ((sampleRate.toLong() and 0xfffffL) shl 44) or
        (1L shl 41) or
        (15L shl 36) or
        (totalSamples.toLong() and 0xffffffffL)
    for (i in 0 until 8) {
        intro[18 + i] = ((packed ushr ((7 - i) * 8)) and 0xffL).toByte()
    }
    stream.write(intro)

    var offset = 0
    var frameNumber = 0
    while (offset < totalSamples) {
        val count = min(blockSize, totalSamples - offset)
        val header = buildFlacFrameHeader(frameNumber, count, sampleRate)
        val frameData = ByteArray(header.size + 2 + count * 4)
        System.arraycopy(header, 0, frameData, 0, header.size)
        var p = header.size

        frameData[p++] = 0x02
        for (i in 0 until count) {
            val s = (clamp(buffer.left[offset + i], -1f, 1f) * 32767f).roundToInt()
            frameData[p++] = ((s shr 8) and 0xff).toByte()
            frameData[p++] = (s and 0xff).toByte()
        }

        frameData[p++] = 0x02
        for (i in 0 until count) {
            val s = (clamp(buffer.right[offset + i], -1f, 1f) * 32767f).roundToInt()
            frameData[p++] = ((s shr 8) and 0xff).toByte()
            frameData[p++] = (s and 0xff).toByte()
        }

        val crc16 = computeFlacCrc16(frameData)
        stream.write(frameData)
        stream.write((crc16 shr 8) and 0xff)
        stream.write(crc16 and 0xff)

        offset += count
        frameNumber++
        onProgress?.invoke(min(0.99f, offset.toFloat() / maxOf(1, totalSamples)))
    }
    onProgress?.invoke(1f)
    return stream.toByteArray()
}

private fun encodeM4aFile(
    buffer: StereoAudioBuffer,
    outputFile: File,
    bitrateKbps: Int = 320,
    onProgress: ((Float) -> Unit)?,
): Boolean {
    val mime = MediaFormat.MIMETYPE_AUDIO_AAC
    val sampleRate = buffer.sampleRate
    val numFrames = buffer.frameCount
    if (numFrames <= 0) return false

    var codec: MediaCodec? = null
    var muxer: MediaMuxer? = null
    var muxerStarted = false

    return try {
        val safeBitrate = bitrateKbps.coerceIn(32, 320) * 1000
        val format = MediaFormat.createAudioFormat(mime, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, safeBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()

        var frameOffset = 0
        var inputDone = false
        var outputDone = false
        val timeoutUs = 10_000L

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(timeoutUs)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)
                    if (frameOffset >= numFrames || inBuf == null) {
                        codec.queueInputBuffer(
                            inIdx,
                            0,
                            0,
                            (frameOffset * 1_000_000L) / sampleRate,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        inBuf.clear()
                        val maxFramesInBuf = inBuf.remaining() / 4
                        val framesToWrite = minOf(1024, maxFramesInBuf, numFrames - frameOffset)
                        val tmpBytes = ByteArray(framesToWrite * 4)
                        val bb = ByteBuffer.wrap(tmpBytes).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until framesToWrite) {
                            val l = (clamp(buffer.left[frameOffset + i], -1f, 1f) * 32767f).roundToInt().toShort()
                            val r = (clamp(buffer.right[frameOffset + i], -1f, 1f) * 32767f).roundToInt().toShort()
                            bb.putShort(l)
                            bb.putShort(r)
                        }
                        inBuf.put(tmpBytes)
                        val ptsUs = (frameOffset * 1_000_000L) / sampleRate
                        codec.queueInputBuffer(inIdx, 0, tmpBytes.size, ptsUs, 0)
                        frameOffset += framesToWrite
                        onProgress?.invoke(min(0.95f, frameOffset.toFloat() / numFrames))
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                outIdx >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outIdx)
                    if (encodedData != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                        muxerStarted
                    ) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }
        }
        onProgress?.invoke(1f)
        true
    } catch (_: Throwable) {
        false
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        if (muxerStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
    }
}

suspend fun exportRenderedAudioToFile(
    context: Context,
    buffer: StereoAudioBuffer,
    format: ExportAudioFormat,
    baseFileName: String,
    bitrateKbps: Int = 320,
    onProgress: ((Float) -> Unit)? = null,
): ExportedAudioResult = withContext(Dispatchers.IO) {
    val cleanBase = baseFileName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Sonix_Export" }
    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }

    when (format) {
        ExportAudioFormat.WAV -> {
            val out = File(exportDir, "$cleanBase.wav")
            out.writeBytes(encodeStereoWavBytes(buffer))
            onProgress?.invoke(1f)
            ExportedAudioResult(out, "wav", "audio/wav")
        }
        ExportAudioFormat.FLAC -> {
            val out = File(exportDir, "$cleanBase.flac")
            out.writeBytes(encodeFlacBytes(buffer, onProgress))
            ExportedAudioResult(out, "flac", "audio/flac")
        }
        ExportAudioFormat.M4A,
        ExportAudioFormat.MP3,
        ExportAudioFormat.OGG -> {
            // Try hardware AAC / M4A first; fall back cleanly to WAV if device codec fails
            val m4aFile = File(exportDir, "$cleanBase.m4a")
            if (encodeM4aFile(buffer, m4aFile, bitrateKbps, onProgress)) {
                ExportedAudioResult(m4aFile, "m4a", "audio/mp4")
            } else {
                val wavFallback = File(exportDir, "$cleanBase.wav")
                wavFallback.writeBytes(encodeStereoWavBytes(buffer))
                onProgress?.invoke(1f)
                ExportedAudioResult(wavFallback, "wav", "audio/wav")
            }
        }
    }
}
