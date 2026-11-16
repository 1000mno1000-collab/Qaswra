package com.example.audio.io

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

object WavAudioCodec {

    const val TARGET_SAMPLE_RATE = 44100

    fun writeWavFile(file: File, buffer: StereoAudioBuffer) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            writeWavStream(fos, buffer)
        }
    }

    fun writeWavStream(output: OutputStream, buffer: StereoAudioBuffer) {
        val buffered = BufferedOutputStream(output, 32768)
        val numFrames = buffer.frameCount
        val numChannels = 2
        val bitsPerSample = 16
        val byteRate = buffer.sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = numFrames * blockAlign
        val riffChunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(riffChunkSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM fmt chunk size
        header.putShort(1.toShort()) // AudioFormat = PCM
        header.putShort(numChannels.toShort())
        header.putInt(buffer.sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        buffered.write(header.array())

        val chunkFrames = 4096
        val byteBuf = ByteArray(chunkFrames * 4)
        var offset = 0
        while (offset < numFrames) {
            val count = min(chunkFrames, numFrames - offset)
            var b = 0
            for (i in 0 until count) {
                val l = (buffer.left[offset + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                val r = (buffer.right[offset + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                byteBuf[b++] = (l.toInt() and 0xFF).toByte()
                byteBuf[b++] = ((l.toInt() shr 8) and 0xFF).toByte()
                byteBuf[b++] = (r.toInt() and 0xFF).toByte()
                byteBuf[b++] = ((r.toInt() shr 8) and 0xFF).toByte()
            }
            buffered.write(byteBuf, 0, count * 4)
            offset += count
        }
        buffered.flush()
    }

    fun readWavFile(file: File): StereoAudioBuffer? {
        if (!file.exists()) return null
        return FileInputStream(file).use { readWavStream(it) }
    }

    fun readWavStream(input: InputStream): StereoAudioBuffer? {
        val dis = DataInputStream(BufferedInputStream(input, 32768))
        val riff = ByteArray(12)
        if (dis.read(riff) < 12) return null
        if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(riff, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) {
            return null
        }

        var channels = 1
        var sampleRate = TARGET_SAMPLE_RATE
        var bitsPerSample = 16
        var rawPcm: ByteArray? = null

        val chunkHeader = ByteArray(8)
        while (true) {
            val read = dis.read(chunkHeader)
            if (read < 8) break
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (size < 0) break

            when (id) {
                "fmt " -> {
                    val fmtBytes = ByteArray(size)
                    dis.readFully(fmtBytes)
                    val bb = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                    val audioFormat = bb.short.toInt() and 0xFFFF
                    if (audioFormat != 1 && audioFormat != 3) return null
                    channels = (bb.short.toInt() and 0xFFFF).coerceIn(1, 2)
                    sampleRate = bb.int
                    bb.int // byteRate
                    bb.short // blockAlign
                    bitsPerSample = bb.short.toInt() and 0xFFFF
                }
                "data" -> {
                    val data = ByteArray(size)
                    dis.readFully(data)
                    rawPcm = data
                    break
                }
                else -> {
                    var toSkip = size.toLong()
                    while (toSkip > 0) {
                        val skipped = dis.skip(toSkip)
                        if (skipped <= 0) break
                        toSkip -= skipped
                    }
                }
            }
            if (size % 2 == 1 && id != "data") {
                dis.read()
            }
        }

        val pcm = rawPcm ?: return null
        if (bitsPerSample != 16) return null
        val frameSize = channels * 2
        val totalFrames = pcm.size / frameSize
        if (totalFrames <= 0) return null

        val left = FloatArray(totalFrames)
        val right = FloatArray(totalFrames)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalFrames) {
            val l = bb.short / 32768f
            val r = if (channels == 2) bb.short / 32768f else l
            left[i] = l
            right[i] = r
        }

        val rawBuffer = StereoAudioBuffer(left, right, sampleRate)
        return if (sampleRate != TARGET_SAMPLE_RATE) resample(rawBuffer, TARGET_SAMPLE_RATE) else rawBuffer
    }

    fun encodeCompactMonoWavBytes(buffer: StereoAudioBuffer, targetSampleRate: Int = 16000): ByteArray {
        val resampled = if (buffer.sampleRate != targetSampleRate) {
            resample(buffer, targetSampleRate)
        } else {
            buffer
        }
        val numFrames = resampled.frameCount.coerceAtMost(targetSampleRate * 300) // cap 5 min (<10MB)
        val dataSize = numFrames * 2
        val baos = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort()) // Mono
        header.putInt(targetSampleRate)
        header.putInt(targetSampleRate * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        baos.write(header.array())

        val pcm = ByteArray(dataSize)
        var b = 0
        for (i in 0 until numFrames) {
            val mono = ((resampled.left[i] + resampled.right[i]) * 0.5f).coerceIn(-1f, 1f)
            val s = (mono * 32767f).toInt().toShort()
            pcm[b++] = (s.toInt() and 0xFF).toByte()
            pcm[b++] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        baos.write(pcm)
        return baos.toByteArray()
    }

    fun decodeAudioBytes(context: Context, bytes: ByteArray, mimeType: String): StereoAudioBuffer? {
        if (bytes.isEmpty()) return null
        val ext = when {
            mimeType.contains("wav", ignoreCase = true) -> ".wav"
            mimeType.contains("ogg", ignoreCase = true) -> ".ogg"
            mimeType.contains("aac", ignoreCase = true) || mimeType.contains("mp4", ignoreCase = true) -> ".m4a"
            else -> ".mp3"
        }
        val tempFile = File.createTempFile("lyria_gen_", ext, context.cacheDir)
        return try {
            FileOutputStream(tempFile).use { it.write(bytes) }
            decodeAudioUri(context, Uri.fromFile(tempFile))
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    fun decodeAudioUri(context: Context, uri: Uri): StereoAudioBuffer? {
        // First try fast WAV decode
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val wav = readWavStream(stream)
                if (wav != null) return wav
            }
        }
        // Fallback to MediaExtractor + MediaCodec for MP3 / AAC / M4A / OGG / FLAC
        return decodeWithMediaCodec(context, uri)
    }

    private fun decodeWithMediaCodec(context: Context, uri: Uri): StereoAudioBuffer? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream(256 * 1024)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val maxSamplesBytes = 44100 * 4 * 300 // cap ~5 minutes to guard memory

            while (!outputDone && pcmOut.size() < maxSamplesBytes) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            outBuf.get(chunk)
                            pcmOut.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val bytes = pcmOut.toByteArray()
            if (bytes.isEmpty()) return null
            val ch = channels.coerceIn(1, 2)
            val frameCount = bytes.size / (ch * 2)
            if (frameCount <= 0) return null

            val left = FloatArray(frameCount)
            val right = FloatArray(frameCount)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frameCount) {
                val l = bb.short / 32768f
                val r = if (ch == 2) bb.short / 32768f else l
                left[i] = l
                right[i] = r
            }
            val decoded = StereoAudioBuffer(left, right, sampleRate)
            if (sampleRate != TARGET_SAMPLE_RATE) resample(decoded, TARGET_SAMPLE_RATE) else decoded
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    fun resample(buffer: StereoAudioBuffer, targetRate: Int): StereoAudioBuffer {
        if (buffer.sampleRate == targetRate || buffer.frameCount == 0) return buffer
        val ratio = buffer.sampleRate.toDouble() / targetRate.toDouble()
        val outFrames = (buffer.frameCount / ratio).toInt().coerceAtLeast(1)
        val outL = FloatArray(outFrames)
        val outR = FloatArray(outFrames)
        val maxIdx = buffer.frameCount - 1
        for (i in 0 until outFrames) {
            val pos = i * ratio
            val idx0 = pos.toInt().coerceIn(0, maxIdx)
            val idx1 = (idx0 + 1).coerceIn(0, maxIdx)
            val frac = (pos - idx0).toFloat()
            outL[i] = buffer.left[idx0] + (buffer.left[idx1] - buffer.left[idx0]) * frac
            outR[i] = buffer.right[idx0] + (buffer.right[idx1] - buffer.right[idx0]) * frac
        }
        return StereoAudioBuffer(outL, outR, targetRate)
    }
}
