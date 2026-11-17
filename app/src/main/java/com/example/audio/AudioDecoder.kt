package com.example.audio

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.min

object AudioDecoder {

    suspend fun decodeUri(context: Context, uri: Uri): StereoAudioBuffer? {
        return decodeUriWithProgress(context, uri) {}
    }

    /**
     * Copies any `content://` or `file://` URI reliably into `context.cacheDir` on [Dispatchers.IO]
     * while emitting real byte-level & decode-level progress (`0.0f` .. `1.0f`), then decodes
     * cleanly via WAV fast-path or FileDescriptor-backed [MediaExtractor].
     * Works reliably for both tiny KB files and large MB files without UI freezes or SAF permission loss.
     */
    suspend fun decodeUriWithProgress(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit,
    ): StereoAudioBuffer? = withContext(Dispatchers.IO) {
        onProgress(0.02f)

        // Best-effort persistable permission for OpenDocument URIs
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        // Direct filesystem path optimization if scheme == "file"
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val directFile = uri.path?.let { File(it) }
            if (directFile != null && directFile.exists() && directFile.canRead()) {
                onProgress(0.35f)
                val decoded = decodeFileWithProgress(directFile) { decP ->
                    onProgress(0.35f + decP * 0.65f)
                }
                if (decoded != null) onProgress(1.0f)
                return@withContext decoded
            }
        }

        val ext = resolveExtension(context, uri)
        val importDir = File(context.cacheDir, "import_cache").apply { mkdirs() }
        val tempFile = File(importDir, "import_${UUID.randomUUID()}.$ext")

        try {
            val totalBytes = queryContentSize(context, uri)
            val copied = copyUriToTempFile(context, uri, tempFile, totalBytes) { copyFrac ->
                onProgress((0.02f + copyFrac * 0.48f).coerceIn(0.02f, 0.50f))
            }
            if (!copied || !tempFile.exists() || tempFile.length() <= 0L) {
                return@withContext null
            }

            onProgress(0.52f)
            val decoded = decodeFileWithProgress(tempFile) { decFrac ->
                onProgress((0.52f + decFrac * 0.46f).coerceIn(0.52f, 0.98f))
            }
            if (decoded != null) {
                onProgress(1.0f)
            }
            decoded
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    suspend fun decodeFile(file: File): StereoAudioBuffer? {
        return decodeFileWithProgress(file) {}
    }

    suspend fun decodeFileWithProgress(
        file: File,
        onProgress: (Float) -> Unit,
    ): StereoAudioBuffer? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) return@withContext null
        coroutineContext.ensureActive()

        // Fast header check for WAV files (only read first 12 bytes before full parse)
        val isRiffWave = runCatching {
            FileInputStream(file).use { fis ->
                val head = ByteArray(12)
                val read = fis.read(head)
                read == 12 &&
                    head[0] == 'R'.code.toByte() &&
                    head[1] == 'I'.code.toByte() &&
                    head[2] == 'F'.code.toByte() &&
                    head[3] == 'F'.code.toByte() &&
                    head[8] == 'W'.code.toByte() &&
                    head[9] == 'A'.code.toByte() &&
                    head[10] == 'V'.code.toByte() &&
                    head[11] == 'E'.code.toByte()
            }
        }.getOrDefault(false)

        if (isRiffWave) {
            onProgress(0.25f)
            val wavBuf = readStereoWavFile(file)
            if (wavBuf != null) {
                onProgress(1.0f)
                return@withContext wavBuf
            }
        }

        val extractor = MediaExtractor()
        try {
            FileInputStream(file).use { fis ->
                extractor.setDataSource(fis.fd)
            }
            decodeWithExtractor(extractor, onProgress)
        } catch (_: Throwable) {
            // Fallback path string if fd binding fails on unusual devices
            runCatching {
                val fallbackExtractor = MediaExtractor()
                try {
                    fallbackExtractor.setDataSource(file.absolutePath)
                    decodeWithExtractor(fallbackExtractor, onProgress)
                } finally {
                    runCatching { fallbackExtractor.release() }
                }
            }.getOrNull()
        } finally {
            runCatching { extractor.release() }
        }
    }

    private suspend fun copyUriToTempFile(
        context: Context,
        uri: Uri,
        dest: File,
        totalBytes: Long,
        onCopyFraction: (Float) -> Unit,
    ): Boolean {
        val resolver = context.contentResolver
        var pfd: ParcelFileDescriptor? = null
        val inputStream = resolver.openInputStream(uri) ?: run {
            pfd = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
            pfd?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        } ?: return false

        return try {
            inputStream.use { input ->
                dest.outputStream().buffered(32_768).use { out ->
                    val buf = ByteArray(32_768)
                    var copied = 0L
                    var lastEmittedMs = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        copied += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmittedMs >= 40L || (totalBytes > 0L && copied >= totalBytes)) {
                            lastEmittedMs = now
                            val frac = if (totalBytes > 0L) {
                                (copied.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0.05f, 1f)
                            } else {
                                // Smooth asymptotic progress when provider hides file size
                                (1f - 1f / (1f + copied / 524_288f)).coerceIn(0.05f, 0.96f)
                            }
                            onCopyFraction(frac)
                        }
                    }
                    out.flush()
                    if (copied > 0L) {
                        onCopyFraction(1f)
                        true
                    } else {
                        false
                    }
                }
            }
        } finally {
            runCatching { pfd?.close() }
        }
    }

    private fun queryContentSize(context: Context, uri: Uri): Long {
        val colSize = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
        if (colSize > 0L) return colSize

        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun resolveExtension(context: Context, uri: Uri): String {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val extFromMime = if (!mime.isNullOrBlank()) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        } else {
            null
        }
        val fromName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.substringAfterLast('.', "") else null
            }
        }.getOrNull()
        return (extFromMime?.takeIf { it.isNotBlank() }
            ?: fromName?.takeIf { it.length in 2..5 }
            ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }
            ?: "bin").lowercase()
    }

    private suspend fun decodeWithExtractor(
        extractor: MediaExtractor,
        onProgress: (Float) -> Unit,
    ): StereoAudioBuffer? {
        var audioTrackIdx = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIdx = i
                format = trackFormat
                break
            }
        }
        if (audioTrackIdx < 0 || format == null) return null

        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
        } else {
            0L
        }
        extractor.selectTrack(audioTrackIdx)

        var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            TARGET_SAMPLE_RATE
        }
        var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            2
        }
        var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT

        val codec = MediaCodec.createDecoderByType(mime)
        // Store chunks sequentially to avoid double-copying a huge ByteArrayOutputStream on large MB files
        val pcmChunks = ArrayList<ByteArray>(256)
        var totalPcmBytes = 0L

        try {
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleRetriesAfterEos = 0
            val timeoutUs = 10_000L
            var lastProgressMs = 0L

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()

                            if (durationUs > 0L) {
                                val now = System.currentTimeMillis()
                                if (now - lastProgressMs >= 45L) {
                                    lastProgressMs = now
                                    onProgress((pts.toDouble() / durationUs.toDouble()).toFloat().coerceIn(0.05f, 0.85f))
                                }
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                            outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ) {
                            pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIdx >= 0 -> {
                        idleRetriesAfterEos = 0
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.get(chunk)
                            pcmChunks.add(chunk)
                            totalPcmBytes += chunk.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) {
                            idleRetriesAfterEos++
                            // Guard against small/malformed audio clips that don't emit explicit output EOS
                            if (idleRetriesAfterEos > 180) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        if (totalPcmBytes <= 0L || channelCount <= 0) return null
        onProgress(0.88f)

        val isFloat = pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameBytes = bytesPerSample * channelCount
        val totalFramesLong = totalPcmBytes / frameBytes
        if (totalFramesLong <= 0L || totalFramesLong > Int.MAX_VALUE) return null

        val frameCount = totalFramesLong.toInt()
        val left = FloatArray(frameCount)
        val right = FloatArray(frameCount)

        var frameIndex = 0
        val carry = ByteArray(frameBytes)
        var carryLen = 0

        for (chunk in pcmChunks) {
            var offset = 0
            if (carryLen > 0) {
                val needed = frameBytes - carryLen
                val take = min(needed, chunk.size)
                System.arraycopy(chunk, 0, carry, carryLen, take)
                carryLen += take
                offset += take
                if (carryLen == frameBytes && frameIndex < frameCount) {
                    val bb = ByteBuffer.wrap(carry).order(ByteOrder.LITTLE_ENDIAN)
                    var l = 0f
                    var r = 0f
                    for (ch in 0 until channelCount) {
                        val s = if (isFloat) clamp(bb.float, -1f, 1f) else clamp(bb.short / 32768f, -1f, 1f)
                        if (ch == 0) { l = s; r = s } else if (ch == 1) { r = s }
                    }
                    left[frameIndex] = l
                    right[frameIndex] = r
                    frameIndex++
                    carryLen = 0
                }
            }

            val fullFramesInChunk = (chunk.size - offset) / frameBytes
            if (fullFramesInChunk > 0) {
                val bb = ByteBuffer.wrap(chunk, offset, fullFramesInChunk * frameBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                for (f in 0 until fullFramesInChunk) {
                    if (frameIndex >= frameCount) break
                    var l = 0f
                    var r = 0f
                    for (ch in 0 until channelCount) {
                        val s = if (isFloat) clamp(bb.float, -1f, 1f) else clamp(bb.short / 32768f, -1f, 1f)
                        if (ch == 0) { l = s; r = s } else if (ch == 1) { r = s }
                    }
                    left[frameIndex] = l
                    right[frameIndex] = r
                    frameIndex++
                }
                offset += fullFramesInChunk * frameBytes
            }

            val rem = chunk.size - offset
            if (rem > 0) {
                System.arraycopy(chunk, offset, carry, 0, rem)
                carryLen = rem
            }
        }
        pcmChunks.clear()

        val actualFrames = min(frameIndex, frameCount)
        if (actualFrames <= 0) return null
        onProgress(0.94f)

        val finalL = if (actualFrames == frameCount) left else left.copyOf(actualFrames)
        val finalR = if (actualFrames == frameCount) right else right.copyOf(actualFrames)
        val raw = StereoAudioBuffer(finalL, finalR, sampleRate)
        val resampled = if (sampleRate != TARGET_SAMPLE_RATE) resampleStereo(raw, TARGET_SAMPLE_RATE) else raw
        onProgress(1.0f)
        return resampled
    }
}
