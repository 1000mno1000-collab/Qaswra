package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioDecoder {

    suspend fun decodeUri(context: Context, uri: Uri): StereoAudioBuffer? = withContext(Dispatchers.IO) {
        // Fast path if the URI is a plain WAV stream
        val wavFast = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(12)
                val read = input.read(head)
                if (read == 12 &&
                    head.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
                    head.copyOfRange(8, 12).contentEquals("WAVE".toByteArray(Charsets.US_ASCII))
                ) {
                    val rest = input.readBytes()
                    decodeWavBytes(head + rest)
                } else {
                    null
                }
            }
        }.getOrNull()
        if (wavFast != null) return@withContext wavFast

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            decodeWithExtractor(extractor)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    suspend fun decodeFile(file: File): StereoAudioBuffer? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val wavFast = readStereoWavFile(file)
        if (wavFast != null) return@withContext wavFast

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            decodeWithExtractor(extractor)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decodeWithExtractor(extractor: MediaExtractor): StereoAudioBuffer? {
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
        extractor.selectTrack(audioTrackIdx)

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT

        val codec = MediaCodec.createDecoderByType(mime)
        val pcmStream = ByteArrayOutputStream()
        try {
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val timeoutUs = 10_000L

            while (!outputDone) {
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
                            codec.queueInputBuffer(
                                inIdx,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0,
                            )
                            extractor.advance()
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
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.get(chunk)
                            pcmStream.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        val rawBytes = pcmStream.toByteArray()
        if (rawBytes.isEmpty() || channelCount <= 0) return null

        val isFloat = pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameCount = rawBytes.size / (bytesPerSample * channelCount)
        if (frameCount <= 0) return null

        val bb = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val left = FloatArray(frameCount)
        val right = FloatArray(frameCount)

        for (i in 0 until frameCount) {
            var l = 0f
            var r = 0f
            for (ch in 0 until channelCount) {
                val sample = if (isFloat) {
                    clamp(bb.float, -1f, 1f)
                } else {
                    clamp(bb.short / 32768f, -1f, 1f)
                }
                if (ch == 0) {
                    l = sample
                    r = sample
                } else if (ch == 1) {
                    r = sample
                }
            }
            left[i] = l
            right[i] = r
        }

        val raw = StereoAudioBuffer(left, right, sampleRate)
        return if (sampleRate != TARGET_SAMPLE_RATE) resampleStereo(raw, TARGET_SAMPLE_RATE) else raw
    }
}
