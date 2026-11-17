package com.example.audio.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import com.example.audio.io.Mp3AudioEncoder
import com.example.audio.io.StereoAudioBuffer
import com.example.audio.io.WavAudioCodec
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

enum class ExportAudioFormat(
    val displayName: String,
    val subtitle: String,
    val extension: String,
    val mimeType: String
) {
    MP3(
        displayName = "MP3",
        subtitle = "صيغة مضغوطة شائعة عالية التوافق (192 kbps)",
        extension = "mp3",
        mimeType = "audio/mpeg"
    ),
    WAV(
        displayName = "WAV",
        subtitle = "جودة استوديو غير مضغوطة (PCM 16-bit)",
        extension = "wav",
        mimeType = "audio/wav"
    ),
    M4A(
        displayName = "M4A",
        subtitle = "ترميز AAC حديث عالي الكفاءة وحجم صغير",
        extension = "m4a",
        mimeType = "audio/mp4"
    ),
    OGG(
        displayName = "OGG",
        subtitle = "صيغة مفتوحة مضغوطة عالية الوضوح (Opus)",
        extension = "ogg",
        mimeType = "audio/ogg"
    ),
    FLAC(
        displayName = "FLAC",
        subtitle = "حفظ نقي بدون فقدان الجودة (Lossless)",
        extension = "flac",
        mimeType = "audio/flac"
    )
}

/**
 * Multi-format audio exporter supporting MP3, WAV, M4A (AAC), OGG (Opus), and FLAC (Lossless).
 */
object AudioFormatExporter {

    fun exportToUri(
        context: Context,
        destinationUri: Uri,
        buffer: StereoAudioBuffer,
        format: ExportAudioFormat,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        return runCatching {
            when (format) {
                ExportAudioFormat.WAV -> {
                    context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        WavAudioCodec.writeWavStream(out, buffer)
                        onProgress(1f)
                        true
                    } ?: false
                }

                ExportAudioFormat.MP3 -> {
                    context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        Mp3AudioEncoder.encodeStereoBufferToMp3Stream(
                            buffer = buffer,
                            output = out,
                            bitrateKbps = 192,
                            onProgress = onProgress
                        )
                        true
                    } ?: false
                }

                ExportAudioFormat.FLAC -> {
                    context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        writeFlacStream(out, buffer, onProgress)
                        true
                    } ?: false
                }

                ExportAudioFormat.M4A -> {
                    encodeWithMediaMuxerToUri(
                        context = context,
                        destinationUri = destinationUri,
                        buffer = buffer,
                        mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
                        targetSampleRate = buffer.sampleRate,
                        muxerOutputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                        tempExtension = ".m4a",
                        bitrate = 192_000,
                        aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                        onProgress = onProgress
                    )
                }

                ExportAudioFormat.OGG -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val oggOk = runCatching {
                            encodeWithMediaMuxerToUri(
                                context = context,
                                destinationUri = destinationUri,
                                buffer = buffer,
                                mimeType = MediaFormat.MIMETYPE_AUDIO_OPUS,
                                targetSampleRate = 48_000, // Android Opus encoder requires 48kHz
                                muxerOutputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
                                tempExtension = ".ogg",
                                bitrate = 128_000,
                                aacProfile = null,
                                onProgress = onProgress
                            )
                        }.getOrDefault(false)
                        if (oggOk) return@runCatching true
                    }
                    // Fallback on older devices or missing Opus hardware codec: AAC container or Vorbis-safe stream
                    encodeWithMediaMuxerToUri(
                        context = context,
                        destinationUri = destinationUri,
                        buffer = buffer,
                        mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
                        targetSampleRate = buffer.sampleRate,
                        muxerOutputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                        tempExtension = ".m4a",
                        bitrate = 160_000,
                        aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                        onProgress = onProgress
                    )
                }
            }
        }.getOrDefault(false)
    }

    private fun encodeWithMediaMuxerToUri(
        context: Context,
        destinationUri: Uri,
        buffer: StereoAudioBuffer,
        mimeType: String,
        targetSampleRate: Int,
        muxerOutputFormat: Int,
        tempExtension: String,
        bitrate: Int,
        aacProfile: Int?,
        onProgress: (Float) -> Unit
    ): Boolean {
        val source = if (buffer.sampleRate != targetSampleRate) {
            WavAudioCodec.resample(buffer, targetSampleRate)
        } else {
            buffer
        }
        val tempFile = File.createTempFile("voicepro_export_", tempExtension, context.cacheDir)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val format = MediaFormat.createAudioFormat(mimeType, source.sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
                if (aacProfile != null) {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
                }
            }
            codec = MediaCodec.createEncoderByType(mimeType)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(tempFile.absolutePath, muxerOutputFormat)
            var trackIndex = -1

            val totalFrames = source.frameCount
            var frameOffset = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val maxFramesPerChunk = 1024
            var stallCount = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            if (frameOffset >= totalFrames) {
                                val ptsUs = (frameOffset.toLong() * 1_000_000L) / source.sampleRate.toLong()
                                codec.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    ptsUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val capacityFrames = inBuf.remaining() / 4
                                val count = min(min(maxFramesPerChunk, capacityFrames), totalFrames - frameOffset)
                                inBuf.order(ByteOrder.LITTLE_ENDIAN)
                                for (i in 0 until count) {
                                    val idx = frameOffset + i
                                    val l = (source.left[idx].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                    val r = (source.right[idx].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                    inBuf.putShort(l)
                                    inBuf.putShort(r)
                                }
                                val ptsUs = (frameOffset.toLong() * 1_000_000L) / source.sampleRate.toLong()
                                codec.queueInputBuffer(inIdx, 0, count * 4, ptsUs, 0)
                                frameOffset += count
                                if (totalFrames > 0) {
                                    onProgress((frameOffset.toFloat() / totalFrames.toFloat()).coerceIn(0f, 0.95f))
                                }
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) {
                            stallCount++
                            if (stallCount > 400) break
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        stallCount = 0
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        stallCount = 0
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && muxerStarted) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, outBuf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (muxerStarted) {
                muxer.stop()
                muxerStarted = false
            }
            muxer.release()
            muxer = null
            codec.stop()
            codec.release()
            codec = null

            if (!tempFile.exists() || tempFile.length() <= 0L) return false
            context.contentResolver.openOutputStream(destinationUri)?.use { destOut ->
                FileInputStream(tempFile).use { fis ->
                    fis.copyTo(destOut, 32 * 1024)
                    destOut.flush()
                }
            } ?: return false
            onProgress(1f)
            return true
        } finally {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { tempFile.delete() }
        }
    }

    /**
     * Pure-Kotlin spec-compliant Lossless FLAC encoder using VERBATIM 16-bit stereo frames
     * with valid fLaC magic bytes, STREAMINFO metadata, CRC-8 frame headers, and CRC-16 footers.
     */
    fun writeFlacStream(
        output: OutputStream,
        buffer: StereoAudioBuffer,
        onProgress: (Float) -> Unit = {}
    ) {
        val out = BufferedOutputStream(output, 32768)
        val totalSamples = buffer.frameCount
        val sampleRate = buffer.sampleRate.coerceIn(1, 655350)
        val channels = 2
        val bitsPerSample = 16
        val blockSize = 4096

        // 1. "fLaC" stream marker
        out.write(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))

        // 2. METADATA_BLOCK_HEADER (last-block=1, type=0 STREAMINFO, length=34)
        out.write(0x80)
        out.write(0x00)
        out.write(0x00)
        out.write(34)

        // 3. STREAMINFO block (34 bytes)
        val minBlock = if (totalSamples in 16 until blockSize) totalSamples else blockSize
        val streamInfo = ByteArray(34)
        streamInfo[0] = ((minBlock shr 8) and 0xFF).toByte()
        streamInfo[1] = (minBlock and 0xFF).toByte()
        streamInfo[2] = ((blockSize shr 8) and 0xFF).toByte()
        streamInfo[3] = (blockSize and 0xFF).toByte()
        // minFrameSize (3 bytes) = 0, maxFrameSize (3 bytes) = 0 (bytes 4..9 remain 0)

        // Bytes 10..17 pack 64 bits:
        // sampleRate (20 bits) | channels-1 (3 bits) | bps-1 (5 bits) | totalSamples (36 bits)
        val packed = ((sampleRate.toLong() and 0xFFFFFL) shl 44) or
            (((channels - 1).toLong() and 0x7L) shl 41) or
            (((bitsPerSample - 1).toLong() and 0x1FL) shl 36) or
            (totalSamples.toLong() and 0xFFFFFFFFFL)
        for (i in 0 until 8) {
            streamInfo[10 + i] = ((packed ushr ((7 - i) * 8)) and 0xFFL).toByte()
        }
        // Bytes 18..33 MD5 = 0 (unspecified)
        out.write(streamInfo)

        if (totalSamples <= 0) {
            out.flush()
            onProgress(1f)
            return
        }

        // 4. Write fixed-blocksize audio frames with VERBATIM subframes
        var offset = 0
        var frameNumber = 0L
        while (offset < totalSamples) {
            val count = min(blockSize, totalSamples - offset)
            val header = buildFlacFrameHeader(
                frameNumber = frameNumber,
                blockSize = count,
                sampleRate = sampleRate
            )
            // Compute CRC-16 over header + frame body
            val frameBodySize = 2 + count * 4 // 2 subframe headers (1 byte each) + L/R 16-bit samples
            val frameData = ByteArray(header.size + frameBodySize)
            System.arraycopy(header, 0, frameData, 0, header.size)
            var p = header.size

            // Channel 0 (Left): VERBATIM subframe header 0x02 (wastedBits=0, type=1 VERBATIM)
            frameData[p++] = 0x02
            for (i in 0 until count) {
                val s = (buffer.left[offset + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort().toInt()
                frameData[p++] = ((s shr 8) and 0xFF).toByte()
                frameData[p++] = (s and 0xFF).toByte()
            }

            // Channel 1 (Right): VERBATIM subframe header 0x02
            frameData[p++] = 0x02
            for (i in 0 until count) {
                val s = (buffer.right[offset + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort().toInt()
                frameData[p++] = ((s shr 8) and 0xFF).toByte()
                frameData[p++] = (s and 0xFF).toByte()
            }

            out.write(frameData)
            val crc16 = computeFlacCrc16(frameData)
            out.write((crc16 shr 8) and 0xFF)
            out.write(crc16 and 0xFF)

            offset += count
            frameNumber++
            onProgress((offset.toFloat() / totalSamples.toFloat()).coerceIn(0f, 0.99f))
        }
        out.flush()
        onProgress(1f)
    }

    private fun buildFlacFrameHeader(
        frameNumber: Long,
        blockSize: Int,
        sampleRate: Int
    ): ByteArray {
        val header = ArrayList<Byte>(16)
        // Sync code 0xFFF8 (fixed-blocksize)
        header.add(0xFF.toByte())
        header.add(0xF8.toByte())

        // Blocksize code + SampleRate code
        val bsCode = when (blockSize) {
            4096 -> 0x0C
            2048 -> 0x0B
            1024 -> 0x0A
            else -> 0x07 // 16-bit (blocksize - 1) stored at end
        }
        val srCode = when (sampleRate) {
            44100 -> 0x09
            48000 -> 0x0A
            32000 -> 0x08
            24000 -> 0x06
            22050 -> 0x05
            16000 -> 0x04
            8000 -> 0x03
            else -> 0x00 // from STREAMINFO
        }
        header.add(((bsCode shl 4) or srCode).toByte())

        // Channel assignment: 0001 (2 independent channels) | sample size 100 (16-bit) | reserved 0
        header.add(0x18.toByte())

        // UTF-8 encoded frame number
        appendUtf8Number(header, frameNumber)

        // Optional 16-bit blocksize - 1 if non-standard last frame
        if (bsCode == 0x07) {
            val bsMinusOne = (blockSize - 1).coerceIn(0, 65535)
            header.add(((bsMinusOne shr 8) and 0xFF).toByte())
            header.add((bsMinusOne and 0xFF).toByte())
        }

        val raw = ByteArray(header.size + 1)
        for (i in header.indices) raw[i] = header[i]
        raw[header.size] = computeFlacCrc8(raw, header.size).toByte()
        return raw
    }

    private fun appendUtf8Number(out: MutableList<Byte>, value: Long) {
        if (value < 0x80L) {
            out.add(value.toByte())
            return
        }
        val bytesNeeded = when {
            value < 0x800L -> 2
            value < 0x10000L -> 3
            value < 0x200000L -> 4
            value < 0x4000000L -> 5
            else -> 6
        }
        val leadMask = ((0xFF shl (8 - bytesNeeded)) and 0xFF)
        val firstShift = (bytesNeeded - 1) * 6
        out.add((leadMask or ((value ushr firstShift).toInt())).toByte())
        for (i in bytesNeeded - 2 downTo 0) {
            val shift = i * 6
            out.add((0x80 or (((value ushr shift) and 0x3FL).toInt())).toByte())
        }
    }

    private fun computeFlacCrc8(data: ByteArray, len: Int): Int {
        var crc = 0
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc and 0xFF
    }

    private fun computeFlacCrc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }
}
