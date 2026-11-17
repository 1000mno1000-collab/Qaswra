package com.example.audio.io

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
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
    private const val CODEC_TIMEOUT_US = 10_000L

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
        return try {
            val maxChunkBytes = 50 * 1024 * 1024 // 50MB safety limit
            val dis = DataInputStream(BufferedInputStream(input, 65536))
            val riff = ByteArray(12)
            if (dis.read(riff) < 12) return null
            if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(riff, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) {
                return null
            }

            var audioFormat = 1
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
                if (size < 0 || size > maxChunkBytes) return null

                when (id) {
                    "fmt " -> {
                        if (size < 16) return null
                        val fmtBytes = ByteArray(size)
                        dis.readFully(fmtBytes)
                        val bb = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                        audioFormat = bb.short.toInt() and 0xFFFF
                        if (audioFormat != 1 && audioFormat != 3 && audioFormat != 0xFFFE) return null
                        channels = (bb.short.toInt() and 0xFFFF).coerceIn(1, 2)
                        sampleRate = bb.int
                        if (sampleRate <= 0) return null
                        bb.int // byteRate
                        bb.short // blockAlign
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                    }
                    "data" -> {
                        if (size == 0) return null
                        val data = ByteArray(size)
                        dis.readFully(data)
                        rawPcm = data
                        break
                    }
                    else -> {
                        var toSkip = size.toLong()
                        while (toSkip > 0) {
                            val skipped = dis.skip(toSkip)
                            if (skipped > 0) {
                                toSkip -= skipped
                            } else {
                                if (dis.read() == -1) return null
                                toSkip -= 1
                            }
                        }
                    }
                }
                if (size % 2 == 1 && id != "data") {
                    if (dis.read() == -1) break
                }
            }

            val pcm = rawPcm ?: return null
            val pcmEncoding = when {
                audioFormat == 3 && bitsPerSample == 32 -> AudioFormat.ENCODING_PCM_FLOAT
                bitsPerSample == 8 -> AudioFormat.ENCODING_PCM_8BIT
                bitsPerSample == 16 -> AudioFormat.ENCODING_PCM_16BIT
                bitsPerSample == 24 -> 24
                bitsPerSample == 32 -> 32
                else -> return null
            }

            val buffer = pcmBytesToStereoBuffer(pcm, sampleRate, channels, pcmEncoding) ?: return null
            if (sampleRate != TARGET_SAMPLE_RATE) resample(buffer, TARGET_SAMPLE_RATE) else buffer
        } catch (_: Throwable) {
            null
        }
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
        val tempFile = File.createTempFile("audio_bytes_", ext, context.cacheDir)
        return try {
            FileOutputStream(tempFile).use { it.write(bytes) }
            decodeAudioUri(context, Uri.fromFile(tempFile))
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    fun decodeAudioUri(
        context: Context,
        uri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): StereoAudioBuffer? {
        var tempFile: File? = null
        return try {
            onProgress(0.01f, "جاري قراءة الملف الصوتي...")

            // Fast WAV header check from stream (avoids MediaCodec overhead for native WAV files)
            val headerProbe = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val probe = ByteArray(12)
                    if (stream.read(probe) == 12) probe else null
                }
            }.getOrNull()

            if (headerProbe != null &&
                String(headerProbe, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(headerProbe, 8, 4, Charsets.US_ASCII) == "WAVE"
            ) {
                onProgress(0.25f, "جاري معالجة الموجات الصوتية...")
                val wav = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { readWavStream(it) }
                }.getOrNull()
                if (wav != null) {
                    onProgress(1.0f, "جاري معالجة الموجات الصوتية...")
                    return wav
                }
            }

            // Try zero-copy MediaExtractor direct URI path first for local/seekable URIs
            val directAttempt = decodeWithMediaExtractor(
                configureDataSource = { extractor ->
                    extractor.setDataSource(context, uri, null)
                },
                startProgress = 0.05f,
                onProgress = onProgress
            )
            if (directAttempt != null) {
                onProgress(1.0f, "جاري معالجة الموجات الصوتية...")
                return directAttempt
            }

            // Fallback: copy content:// stream to temporary cache file and decode from local path
            tempFile = copyUriToTempCacheFile(context, uri) { copyFrac ->
                onProgress((copyFrac * 0.28f).coerceIn(0.01f, 0.28f), "جاري نسخ الملف الصوتي...")
            } ?: return null

            onProgress(0.28f, "جاري معالجة الموجات الصوتية...")

            // Try WAV container parser on copied file first (handles 16/24/32-bit/float WAV)
            val wavFallback = runCatching {
                FileInputStream(tempFile).use { readWavStream(it) }
            }.getOrNull()
            if (wavFallback != null) {
                onProgress(1.0f, "جاري معالجة الموجات الصوتية...")
                return wavFallback
            }

            val codecDecoded = decodeWithMediaExtractor(
                configureDataSource = { extractor ->
                    extractor.setDataSource(tempFile.absolutePath)
                },
                startProgress = 0.28f,
                onProgress = onProgress
            )
            if (codecDecoded != null) {
                onProgress(1.0f, "جاري معالجة الموجات الصوتية...")
            }
            codecDecoded
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { tempFile?.delete() }
        }
    }

    private fun copyUriToTempCacheFile(
        context: Context,
        uri: Uri,
        onCopyFraction: (Float) -> Unit
    ): File? {
        val tempFile = File.createTempFile("import_audio_", ".tmp", context.cacheDir)
        val totalBytes = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
            } ?: -1L
        }.getOrDefault(-1L).let { size ->
            if (size > 0L) size
            else runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
        }

        val inputStream = context.contentResolver.openInputStream(uri) ?: run {
            runCatching { tempFile.delete() }
            return null
        }

        var copiedBytes = 0L
        var lastReported = -1f
        val copyBuf = ByteArray(64 * 1024)
        inputStream.use { input ->
            FileOutputStream(tempFile).use { output ->
                while (true) {
                    val n = input.read(copyBuf)
                    if (n <= 0) break
                    output.write(copyBuf, 0, n)
                    copiedBytes += n
                    val frac = if (totalBytes > 0L) {
                        (copiedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        (copiedBytes.toFloat() / (copiedBytes.toFloat() + 1_048_576f)).coerceIn(0f, 0.95f)
                    }
                    if (frac - lastReported >= 0.01f) {
                        lastReported = frac
                        onCopyFraction(frac)
                    }
                }
                output.flush()
            }
        }
        if (copiedBytes <= 0L || !tempFile.exists() || tempFile.length() <= 0L) {
            runCatching { tempFile.delete() }
            return null
        }
        onCopyFraction(1f)
        return tempFile
    }

    private inline fun decodeWithMediaExtractor(
        configureDataSource: (MediaExtractor) -> Unit,
        startProgress: Float,
        noinline onProgress: (Float, String) -> Unit
    ): StereoAudioBuffer? {
        val extractor = MediaExtractor()
        return try {
            configureDataSource(extractor)
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

            val primaryResult = runMediaCodecLoop(
                extractor = extractor,
                format = format,
                useFallbackList = false,
                startProgress = startProgress,
                onProgress = onProgress
            )
            if (primaryResult != null) return primaryResult

            // Fallback codec pass via MediaCodecList.ALL_CODECS (e.g. software decoders on tricky containers)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            runMediaCodecLoop(
                extractor = extractor,
                format = format,
                useFallbackList = true,
                startProgress = startProgress,
                onProgress = onProgress
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun runMediaCodecLoop(
        extractor: MediaExtractor,
        format: MediaFormat,
        useFallbackList: Boolean,
        startProgress: Float,
        onProgress: (Float, String) -> Unit
    ): StereoAudioBuffer? {
        var codec: MediaCodec? = null
        return try {
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                TARGET_SAMPLE_RATE
            }
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            var pcmEncoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                format.containsKey(MediaFormat.KEY_PCM_ENCODING)
            ) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            codec = if (useFallbackList) {
                val codecName = MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format)
                    ?: return null
                MediaCodec.createByCodecName(codecName)
            } else {
                MediaCodec.createDecoderByType(mime)
            }
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream(256 * 1024)
            var transferBuf = ByteArray(16_384)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val maxSamplesBytes = 44100 * 4 * 360 // ~6 minutes stereo cap
            var lastReported = startProgress
            var stallCount = 0

            while (!outputDone && pcmOut.size() < maxSamplesBytes) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(inIdx, 0, sampleSize, sampleTimeUs, 0)
                                if (durationUs > 0L) {
                                    val frac = (sampleTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                                    val p = startProgress + frac * (0.98f - startProgress)
                                    if (p - lastReported >= 0.01f) {
                                        lastReported = p
                                        onProgress(p, "جاري معالجة الموجات الصوتية...")
                                    }
                                }
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) {
                            stallCount++
                            if (stallCount > 50) break
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        stallCount = 0
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                            newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ) {
                            pcmEncoding = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIdx >= 0 -> {
                        stallCount = 0
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            if (info.size > transferBuf.size) {
                                transferBuf = ByteArray(info.size)
                            }
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.get(transferBuf, 0, info.size)
                            pcmOut.write(transferBuf, 0, info.size)

                            if (durationUs <= 0L) {
                                val frac = (pcmOut.size().toFloat() / maxSamplesBytes.toFloat()).coerceIn(0f, 0.96f)
                                val p = startProgress + frac * (0.96f - startProgress)
                                if (p - lastReported >= 0.01f) {
                                    lastReported = p
                                    onProgress(p, "جاري معالجة الموجات الصوتية...")
                                }
                            }
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
            val buffer = pcmBytesToStereoBuffer(bytes, sampleRate, channels, pcmEncoding) ?: return null
            onProgress(1.0f, "جاري معالجة الموجات الصوتية...")
            if (sampleRate != TARGET_SAMPLE_RATE) resample(buffer, TARGET_SAMPLE_RATE) else buffer
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private fun pcmBytesToStereoBuffer(
        bytes: ByteArray,
        sampleRate: Int,
        channels: Int,
        pcmEncoding: Int
    ): StereoAudioBuffer? {
        if (bytes.isEmpty() || sampleRate <= 0) return null
        val ch = channels.coerceIn(1, 2)
        val bytesPerSample = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_FLOAT, 32 -> 4
            24 -> 3
            else -> 2
        }
        val frameCount = bytes.size / (ch * bytesPerSample)
        if (frameCount <= 0) return null

        val left = FloatArray(frameCount)
        val right = FloatArray(frameCount)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_8BIT -> {
                for (i in 0 until frameCount) {
                    val l = ((bb.get().toInt() and 0xFF) - 128) / 128f
                    val r = if (ch == 2) ((bb.get().toInt() and 0xFF) - 128) / 128f else l
                    left[i] = l.coerceIn(-1f, 1f)
                    right[i] = r.coerceIn(-1f, 1f)
                }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                for (i in 0 until frameCount) {
                    val l = bb.float.coerceIn(-1f, 1f)
                    val r = if (ch == 2) bb.float.coerceIn(-1f, 1f) else l
                    left[i] = l
                    right[i] = r
                }
            }
            24 -> {
                for (i in 0 until frameCount) {
                    val l = readInt24(bb) / 8388608f
                    val r = if (ch == 2) readInt24(bb) / 8388608f else l
                    left[i] = l.coerceIn(-1f, 1f)
                    right[i] = r.coerceIn(-1f, 1f)
                }
            }
            32 -> {
                for (i in 0 until frameCount) {
                    val l = (bb.int / 2147483648.0).toFloat()
                    val r = if (ch == 2) (bb.int / 2147483648.0).toFloat() else l
                    left[i] = l.coerceIn(-1f, 1f)
                    right[i] = r.coerceIn(-1f, 1f)
                }
            }
            else -> {
                for (i in 0 until frameCount) {
                    val l = bb.short / 32768f
                    val r = if (ch == 2) bb.short / 32768f else l
                    left[i] = l
                    right[i] = r
                }
            }
        }
        return StereoAudioBuffer(left, right, sampleRate)
    }

    private fun readInt24(bb: ByteBuffer): Int {
        val b0 = bb.get().toInt() and 0xFF
        val b1 = bb.get().toInt() and 0xFF
        val b2 = bb.get().toInt()
        return b0 or (b1 shl 8) or (b2 shl 16)
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
