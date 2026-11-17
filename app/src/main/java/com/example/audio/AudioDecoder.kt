package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.CancellationException
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

sealed interface AudioDecodeOutcome {
    data class Success(val buffer: StereoAudioBuffer) : AudioDecodeOutcome
    data class Failure(val reason: String) : AudioDecodeOutcome
}

object AudioDecoder {
    private const val TAG = "AudioDecoder"

    suspend fun decodeUri(context: Context, uri: Uri): StereoAudioBuffer? {
        return decodeUriWithProgress(context, uri) {}
    }

    /**
     * Copies any `content://` or `file://` URI reliably into `context.cacheDir` via [AudioFileUploader]
     * on [Dispatchers.IO] (64KB buffer) before passing `file.absolutePath` to the decoder.
     */
    suspend fun decodeUriWithProgress(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit,
    ): StereoAudioBuffer? = withContext(Dispatchers.IO) {
        onProgress(0.0f)

        val uploaded = when (
            val uploadOutcome = AudioFileUploader.copyAudioUriToCacheDetailed(context, uri) { info ->
                onProgress((info.fraction * 0.50f).coerceIn(0.0f, 0.50f))
            }
        ) {
            is AudioUploadOutcome.Success -> uploadOutcome.uploaded
            is AudioUploadOutcome.Failure -> return@withContext null
        }

        try {
            onProgress(0.52f)
            val decoded = decodeFileWithProgress(uploaded.file) { decFrac ->
                onProgress((0.52f + decFrac * 0.48f).coerceIn(0.52f, 1.0f))
            }
            if (decoded != null) {
                onProgress(1.0f)
            }
            decoded
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        } finally {
            runCatching { uploaded.file.delete() }
        }
    }

    suspend fun decodeFile(file: File): StereoAudioBuffer? {
        return decodeFileWithProgress(file) {}
    }

    suspend fun decodeFileWithProgress(
        file: File,
        onProgress: (Float) -> Unit,
    ): StereoAudioBuffer? {
        return when (val res = decodeFileDetailedWithProgress(file, onProgress)) {
            is AudioDecodeOutcome.Success -> res.buffer
            is AudioDecodeOutcome.Failure -> null
        }
    }

    /**
     * Decodes a real local file in `cacheDir` (always addressed via [File.getAbsolutePath])
     * with full technical error diagnostics when any codec/container fails.
     */
    suspend fun decodeFileDetailedWithProgress(
        file: File,
        onProgress: (Float) -> Unit,
    ): AudioDecodeOutcome = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) {
            return@withContext AudioDecodeOutcome.Failure(
                "FileNotFoundException: File missing or empty (${file.absolutePath})",
            )
        }
        coroutineContext.ensureActive()

        var lastError: String? = null

        // 1. Fast header check for standard PCM / Float WAV files
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
            try {
                val wavBuf = readStereoWavFile(file)
                if (wavBuf != null && wavBuf.frameCount > 0) {
                    onProgress(1.0f)
                    return@withContext AudioDecodeOutcome.Success(wavBuf)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = "WAV ${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, "WAV fast-path failed for ${file.name}: $lastError", t)
            }
        }

        // 2. Hardware MediaExtractor + MediaCodec via real local file.absolutePath
        val primaryOutcome = decodeViaMediaExtractor(
            file = file,
            useFdFallback = false,
            onProgress = onProgress,
        )
        if (primaryOutcome is AudioDecodeOutcome.Success) {
            return@withContext primaryOutcome
        } else if (primaryOutcome is AudioDecodeOutcome.Failure) {
            lastError = primaryOutcome.reason
        }

        // 3. Fallback FileDescriptor-backed MediaExtractor on the same cached file
        val fdOutcome = decodeViaMediaExtractor(
            file = file,
            useFdFallback = true,
            onProgress = onProgress,
        )
        if (fdOutcome is AudioDecodeOutcome.Success) {
            return@withContext fdOutcome
        } else if (fdOutcome is AudioDecodeOutcome.Failure && lastError == null) {
            lastError = fdOutcome.reason
        }

        // 4. Optional FFmpegKit fallback (strictly after file is in cacheDir, using file.absolutePath)
        val ffOutcome = tryDecodeViaFfmpegKitReflection(file)
        if (ffOutcome is AudioDecodeOutcome.Success) {
            onProgress(1.0f)
            return@withContext ffOutcome
        }

        AudioDecodeOutcome.Failure(lastError ?: "Unsupported or corrupt audio format (${file.name})")
    }

    private suspend fun decodeViaMediaExtractor(
        file: File,
        useFdFallback: Boolean,
        onProgress: (Float) -> Unit,
    ): AudioDecodeOutcome {
        val extractor = MediaExtractor()
        return try {
            if (useFdFallback) {
                FileInputStream(file).use { fis ->
                    extractor.setDataSource(fis.fd)
                }
            } else {
                extractor.setDataSource(file.absolutePath)
            }
            decodeWithExtractor(extractor, onProgress)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val detail = "${t.javaClass.simpleName}: ${t.message ?: "MediaExtractor failed"}"
            Log.w(TAG, "MediaExtractor(fd=$useFdFallback) failed on ${file.name}: $detail", t)
            AudioDecodeOutcome.Failure(detail)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private suspend fun decodeWithExtractor(
        extractor: MediaExtractor,
        onProgress: (Float) -> Unit,
    ): AudioDecodeOutcome {
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
        if (audioTrackIdx < 0 || format == null) {
            return AudioDecodeOutcome.Failure("IllegalArgumentException: No audio track found in file")
        }

        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: return AudioDecodeOutcome.Failure("IllegalArgumentException: Missing MIME type")
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
        } else {
            0L
        }
        extractor.selectTrack(audioTrackIdx)

        var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
        } else {
            TARGET_SAMPLE_RATE
        }
        var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        } else {
            2
        }
        var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT

        val codec = MediaCodec.createDecoderByType(mime)
        val pcmChunks = ArrayDeque<ByteArray>(256)
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
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
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
                            pcmChunks.addLast(chunk)
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

        if (totalPcmBytes <= 0L || channelCount <= 0) {
            return AudioDecodeOutcome.Failure("IllegalStateException: Decoder produced 0 PCM bytes (mime=$mime)")
        }
        onProgress(0.88f)

        val isFloat = pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameBytes = bytesPerSample * channelCount
        val totalFramesLong = totalPcmBytes / frameBytes
        if (totalFramesLong <= 0L || totalFramesLong > Int.MAX_VALUE) {
            return AudioDecodeOutcome.Failure("IllegalStateException: Invalid PCM frame count ($totalFramesLong)")
        }

        val frameCount = totalFramesLong.toInt()
        val left = FloatArray(frameCount)
        val right = FloatArray(frameCount)

        var frameIndex = 0
        val carry = ByteArray(frameBytes)
        var carryLen = 0

        // Consume and free each PCM byte chunk incrementally to halve peak heap allocation on long tracks
        while (pcmChunks.isNotEmpty()) {
            val chunk = pcmChunks.removeFirst()
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

        val actualFrames = min(frameIndex, frameCount)
        if (actualFrames <= 0) {
            return AudioDecodeOutcome.Failure("IllegalStateException: Decoded 0 audio frames (mime=$mime)")
        }
        onProgress(0.94f)

        val finalL = if (actualFrames == frameCount) left else left.copyOf(actualFrames)
        val finalR = if (actualFrames == frameCount) right else right.copyOf(actualFrames)
        val raw = StereoAudioBuffer(finalL, finalR, sampleRate)
        val resampled = if (sampleRate != TARGET_SAMPLE_RATE) resampleStereo(raw, TARGET_SAMPLE_RATE) else raw
        onProgress(1.0f)
        return AudioDecodeOutcome.Success(resampled)
    }

    private fun tryDecodeViaFfmpegKitReflection(sourceFile: File): AudioDecodeOutcome {
        val parent = sourceFile.parentFile ?: return AudioDecodeOutcome.Failure("No cache dir")
        val outWav = File(parent, "ff_dec_${UUID.randomUUID()}.wav")
        return try {
            val ffmpegClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
            val returnCodeClass = Class.forName("com.arthenica.ffmpegkit.ReturnCode")
            val executeMethod = ffmpegClass.getMethod("execute", String::class.java)
            val cmd = "-y -i \"${sourceFile.absolutePath}\" -vn -acodec pcm_s16le -ar $TARGET_SAMPLE_RATE -ac 2 \"${outWav.absolutePath}\""
            val session = executeMethod.invoke(null, cmd)
                ?: return AudioDecodeOutcome.Failure("FFmpegKit returned null session")
            val rc = session.javaClass.getMethod("getReturnCode").invoke(session)
                ?: return AudioDecodeOutcome.Failure("FFmpegKit missing ReturnCode")
            val ok = (returnCodeClass.getMethod("isSuccess", returnCodeClass).invoke(null, rc) as? Boolean) == true
            if (ok && outWav.exists() && outWav.length() > 44L) {
                val buf = readStereoWavFile(outWav)
                if (buf != null && buf.frameCount > 0) {
                    AudioDecodeOutcome.Success(buf)
                } else {
                    AudioDecodeOutcome.Failure("FFmpegKit produced unreadable WAV")
                }
            } else {
                AudioDecodeOutcome.Failure("FFmpegKit exited with code=$rc")
            }
        } catch (t: Throwable) {
            AudioDecodeOutcome.Failure("${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { outWav.delete() }
        }
    }
}
