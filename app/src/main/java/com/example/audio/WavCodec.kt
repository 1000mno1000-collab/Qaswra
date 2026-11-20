package com.example.audio

import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val TARGET_SAMPLE_RATE = 44100
private const val WAV_STREAM_CHUNK_BYTES = 8192 // 8KB fixed streaming buffer
private const val WAV_FRAMES_PER_CHUNK = WAV_STREAM_CHUNK_BYTES / 4 // 16-bit stereo = 4 bytes/frame

fun buildWavHeaderBytes(
    dataSizeBytes: Int,
    sampleRate: Int = TARGET_SAMPLE_RATE,
    numChannels: Int = 2,
    bitsPerSample: Int = 16,
): ByteArray {
    val byteRate = sampleRate * numChannels * (bitsPerSample / 8)
    val blockAlign = numChannels * (bitsPerSample / 8)
    val header = ByteArray(44)
    val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    bb.put("RIFF".toByteArray(Charsets.US_ASCII))
    bb.putInt(36 + dataSizeBytes)
    bb.put("WAVE".toByteArray(Charsets.US_ASCII))
    bb.put("fmt ".toByteArray(Charsets.US_ASCII))
    bb.putInt(16)
    bb.putShort(1)
    bb.putShort(numChannels.toShort())
    bb.putInt(sampleRate)
    bb.putInt(byteRate)
    bb.putShort(blockAlign.toShort())
    bb.putShort(bitsPerSample.toShort())
    bb.put("data".toByteArray(Charsets.US_ASCII))
    bb.putInt(dataSizeBytes)
    return header
}

fun encodeStereoWavBytes(buffer: StereoAudioBuffer): ByteArray {
    val numFrames = buffer.frameCount
    val dataSize = numFrames * 4
    val out = ByteArray(44 + dataSize)
    val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    bb.put(buildWavHeaderBytes(dataSize, buffer.sampleRate))

    for (i in 0 until numFrames) {
        val l = (clamp(buffer.left[i], -1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
        val r = (clamp(buffer.right[i], -1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
        bb.putShort(l.toShort())
        bb.putShort(r.toShort())
    }
    return out
}

/**
 * Streams a StereoAudioBuffer cleanly to disk using a fixed 64KB chunk buffer,
 * preventing large temporary ByteArray allocations in Java heap.
 */
fun writeStereoWavFile(file: File, buffer: StereoAudioBuffer) {
    file.parentFile?.mkdirs()
    val numFrames = buffer.frameCount
    val dataSize = numFrames * 4
    val header = buildWavHeaderBytes(dataSize, buffer.sampleRate)
    val chunkBytes = ByteArray(WAV_STREAM_CHUNK_BYTES)
    val bb = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)

    BufferedOutputStream(FileOutputStream(file), WAV_STREAM_CHUNK_BYTES).use { out ->
        out.write(header)
        var offset = 0
        while (offset < numFrames) {
            val count = min(WAV_FRAMES_PER_CHUNK, numFrames - offset)
            bb.clear()
            for (i in 0 until count) {
                val idx = offset + i
                val l = (clamp(buffer.left[idx], -1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
                val r = (clamp(buffer.right[idx], -1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
                bb.putShort(l.toShort())
                bb.putShort(r.toShort())
            }
            out.write(chunkBytes, 0, count * 4)
            offset += count
        }
        out.flush()
    }
}

/**
 * Writes a pure silence WAV file directly on disk via 64KB streaming on Dispatchers.IO.
 */
suspend fun writeSilenceWavStreaming(
    outFile: File,
    durationSeconds: Float,
    sampleRate: Int = TARGET_SAMPLE_RATE,
    numChannels: Int = 2,
): Long = withContext(Dispatchers.IO) {
    outFile.parentFile?.mkdirs()
    val ch = numChannels.coerceIn(1, 2)
    val sr = sampleRate.coerceIn(8000, 96000)
    val bytesPerFrame = ch * 2L
    val totalFrames = max(1L, (clamp(durationSeconds, 0.1f, 600f) * sr).roundToInt().toLong())
    val totalDataBytes = (totalFrames * bytesPerFrame).coerceAtMost(Int.MAX_VALUE - 44L).toInt()
    val header = buildWavHeaderBytes(totalDataBytes, sr, numChannels = ch)
    val zeroChunk = ByteArray(WAV_STREAM_CHUNK_BYTES)

    BufferedOutputStream(FileOutputStream(outFile), WAV_STREAM_CHUNK_BYTES).use { out ->
        out.write(header)
        var remaining = totalDataBytes
        while (remaining > 0) {
            val toWrite = min(remaining, zeroChunk.size)
            out.write(zeroChunk, 0, toWrite)
            remaining -= toWrite
        }
        out.flush()
    }
    totalFrames
}

/**
 * Merges two 44-byte canonical PCM WAV files on disk with a smooth crossfade using a 64KB chunk buffer,
 * completely avoiding loading both full files as ByteArrays into heap.
 */
suspend fun mergeWavFilesStreaming(
    firstWav: File,
    secondWav: File,
    outWav: File,
    crossfadeMs: Int = 15,
    sampleRate: Int = TARGET_SAMPLE_RATE,
    maxCrossfadeFraction: Float = 0.85f,
): Boolean = withContext(Dispatchers.IO) {
    if (!firstWav.exists() || !secondWav.exists()) return@withContext false
    val firstDataBytes = (firstWav.length() - 44L).coerceAtLeast(0L)
    val secondDataBytes = (secondWav.length() - 44L).coerceAtLeast(0L)
    val n1 = (firstDataBytes / 4L).toInt()
    val n2 = (secondDataBytes / 4L).toInt()
    if (n1 <= 0 && n2 <= 0) return@withContext false

    val safeFrac = maxCrossfadeFraction.coerceIn(0.05f, 0.95f)
    val xfadeFrames = minOf(
        ((crossfadeMs / 1000.0) * sampleRate).toInt().coerceAtLeast(0),
        max(0, (n1 * safeFrac).toInt()),
        max(0, (n2 * safeFrac).toInt()),
    )
    val xfadeBytes = xfadeFrames * 4
    val firstCopyBytes = max(0L, firstDataBytes - xfadeBytes)
    val totalOutDataBytes = (firstCopyBytes + secondDataBytes).coerceAtMost(Int.MAX_VALUE - 44L).toInt()

    outWav.parentFile?.mkdirs()
    val chunk = ByteArray(WAV_STREAM_CHUNK_BYTES)

    BufferedOutputStream(FileOutputStream(outWav), WAV_STREAM_CHUNK_BYTES).use { out ->
        out.write(buildWavHeaderBytes(totalOutDataBytes, sampleRate))

        // 1. Copy first file up to overlap start in 64KB chunks
        FileInputStream(firstWav).use { in1 ->
            skipExactBytes(in1, 44L)
            copyExactBytesChunked(in1, out, firstCopyBytes, chunk)

            // 2. Crossfade region (or direct join if xfadeFrames == 0)
            if (xfadeFrames > 0) {
                val tail1 = ByteArray(xfadeBytes)
                readExactBytes(in1, tail1, xfadeBytes)

                FileInputStream(secondWav).use { in2 ->
                    skipExactBytes(in2, 44L)
                    val head2 = ByteArray(xfadeBytes)
                    readExactBytes(in2, head2, xfadeBytes)

                    val bb1 = ByteBuffer.wrap(tail1).order(ByteOrder.LITTLE_ENDIAN)
                    val bb2 = ByteBuffer.wrap(head2).order(ByteOrder.LITTLE_ENDIAN)
                    val mixOut = ByteArray(xfadeBytes)
                    val bbMix = ByteBuffer.wrap(mixOut).order(ByteOrder.LITTLE_ENDIAN)

                    for (i in 0 until xfadeFrames) {
                        val t = i.toFloat() / max(1, xfadeFrames)
                        val l1 = bb1.short.toInt()
                        val r1 = bb1.short.toInt()
                        val l2 = bb2.short.toInt()
                        val r2 = bb2.short.toInt()
                        val ml = (l1 * (1f - t) + l2 * t).roundToInt().coerceIn(-32768, 32767)
                        val mr = (r1 * (1f - t) + r2 * t).roundToInt().coerceIn(-32768, 32767)
                        bbMix.putShort(ml.toShort())
                        bbMix.putShort(mr.toShort())
                    }
                    out.write(mixOut)

                    // 3. Copy remainder of second file in 64KB chunks
                    val restSecond = (secondDataBytes - xfadeBytes).coerceAtLeast(0L)
                    copyExactBytesChunked(in2, out, restSecond, chunk)
                }
            } else {
                FileInputStream(secondWav).use { in2 ->
                    skipExactBytes(in2, 44L)
                    copyExactBytesChunked(in2, out, secondDataBytes, chunk)
                }
            }
        }
        out.flush()
    }
    true
}

fun patchWavHeaderSizes(wavFile: File, sampleRate: Int = TARGET_SAMPLE_RATE) {
    if (!wavFile.exists() || wavFile.length() < 44L) return
    val dataSize = (wavFile.length() - 44L).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE - 44L).toInt()
    RandomAccessFile(wavFile, "rw").use { raf ->
        raf.seek(0L)
        raf.write(buildWavHeaderBytes(dataSize, sampleRate))
    }
}

private fun skipExactBytes(stream: FileInputStream, bytesToSkip: Long) {
    var rem = bytesToSkip
    while (rem > 0L) {
        val skipped = stream.skip(rem)
        if (skipped <= 0L) {
            if (stream.read() == -1) break
            rem -= 1L
        } else {
            rem -= skipped
        }
    }
}

private fun readExactBytes(stream: FileInputStream, dest: ByteArray, length: Int) {
    var offset = 0
    while (offset < length) {
        val read = stream.read(dest, offset, length - offset)
        if (read <= 0) break
        offset += read
    }
}

private fun copyExactBytesChunked(
    input: FileInputStream,
    output: OutputStream,
    bytesToCopy: Long,
    chunkBuffer: ByteArray,
) {
    var remaining = bytesToCopy
    while (remaining > 0L) {
        val maxRead = min(chunkBuffer.size.toLong(), remaining).toInt()
        val read = input.read(chunkBuffer, 0, maxRead)
        if (read <= 0) break
        output.write(chunkBuffer, 0, read)
        remaining -= read
    }
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

fun readStereoWavFile(
    file: File,
    preserveSampleRate: Boolean = false,
    onProgress: (Float) -> Unit = {},
): StereoAudioBuffer? {
    if (!file.exists() || file.length() < 44L) return null
    return runCatching {
        FileInputStream(file).use { fis ->
            val headerProbe = ByteArray(12)
            readExactBytes(fis, headerProbe, 12)
            if (String(headerProbe, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(headerProbe, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) {
                return@use null
            }

            var audioFormat = 1
            var channels = 2
            var sampleRate = TARGET_SAMPLE_RATE
            var bitsPerSample = 16
            var dataSizeLong = 0L
            var foundData = false

            val chunkHdr = ByteArray(8)
            var fileOffset = 12L
            val fileLen = file.length()

            while (fileOffset + 8L <= fileLen) {
                var off = 0
                while (off < 8) {
                    val r = fis.read(chunkHdr, off, 8 - off)
                    if (r <= 0) break
                    off += r
                }
                if (off < 8) break
                fileOffset += 8L

                val hdrBb = ByteBuffer.wrap(chunkHdr).order(ByteOrder.LITTLE_ENDIAN)
                val idStr = String(chunkHdr, 0, 4, Charsets.US_ASCII)
                hdrBb.int // skip 4 ASCII chars
                val rawChunkSize = hdrBb.int
                val chunkSizeLong = rawChunkSize.toLong() and 0xFFFFFFFFL
                val paddedLong = chunkSizeLong + (chunkSizeLong and 1L)

                when (idStr) {
                    "fmt " -> {
                        val toRead = chunkSizeLong.coerceAtMost(256L).toInt()
                        val fmtBytes = ByteArray(toRead)
                        readExactBytes(fis, fmtBytes, toRead)
                        val fmtBb = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                        if (toRead >= 16) {
                            audioFormat = fmtBb.short.toInt() and 0xFFFF
                            channels = fmtBb.short.toInt() and 0xFFFF
                            sampleRate = fmtBb.int
                            fmtBb.int
                            fmtBb.short
                            bitsPerSample = fmtBb.short.toInt() and 0xFFFF
                            if (audioFormat == 0xFFFE && toRead >= 26) {
                                fmtBb.short
                                fmtBb.short
                                fmtBb.int
                                val subFmt = fmtBb.short.toInt() and 0xFFFF
                                if (subFmt == 1 || subFmt == 3) audioFormat = subFmt
                            }
                        }
                        val skipRest = (paddedLong - toRead).coerceAtLeast(0L)
                        if (skipRest > 0L) skipExactBytes(fis, skipRest)
                        fileOffset += paddedLong
                    }
                    "data" -> {
                        val avail = (fileLen - fileOffset).coerceAtLeast(0L)
                        dataSizeLong = if (chunkSizeLong == 0L || chunkSizeLong > avail) avail else chunkSizeLong
                        foundData = true
                        break
                    }
                    else -> {
                        if (paddedLong > (fileLen - fileOffset)) break
                        skipExactBytes(fis, paddedLong)
                        fileOffset += paddedLong
                    }
                }
            }

            if (!foundData || dataSizeLong <= 0L || channels <= 0) return@use null
            if (audioFormat != 1 && audioFormat != 3) return@use null
            val bytesPerSample = bitsPerSample / 8
            if (bytesPerSample <= 0) return@use null
            val frameBytes = channels * bytesPerSample
            if (frameBytes <= 0) return@use null

            val totalFramesLong = dataSizeLong / frameBytes
            val outputFrameMultiplier = if (preserveSampleRate || sampleRate >= TARGET_SAMPLE_RATE) {
                1.0
            } else {
                TARGET_SAMPLE_RATE.toDouble() / sampleRate.coerceAtLeast(1)
            }
            if (
                totalFramesLong <= 0L ||
                totalFramesLong.toDouble() * outputFrameMultiplier * 8.0 > 48.0 * 1024.0 * 1024.0
            ) {
                return@use null
            }
            val totalFrames = totalFramesLong.toInt()

            val left = FloatArray(totalFrames)
            val right = FloatArray(totalFrames)
            val framesPerChunk = (WAV_STREAM_CHUNK_BYTES / frameBytes).coerceAtLeast(1)
            val streamBuf = ByteArray(framesPerChunk * frameBytes)

            var frameIdx = 0
            var lastProgressMs = 0L
            while (frameIdx < totalFrames) {
                val wantFrames = min(framesPerChunk, totalFrames - frameIdx)
                val wantBytes = wantFrames * frameBytes
                var bytesRead = 0
                while (bytesRead < wantBytes) {
                    val n = fis.read(streamBuf, bytesRead, wantBytes - bytesRead)
                    if (n <= 0) break
                    bytesRead += n
                }
                val gotFrames = bytesRead / frameBytes
                if (gotFrames <= 0) break
                val bb = ByteBuffer.wrap(streamBuf, 0, gotFrames * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (f in 0 until gotFrames) {
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
                    left[frameIdx] = lSample
                    right[frameIdx] = rSample
                    frameIdx++
                }
                val now = System.currentTimeMillis()
                if (now - lastProgressMs >= 50L || frameIdx >= totalFrames) {
                    lastProgressMs = now
                    onProgress(frameIdx.toFloat() / totalFrames)
                }
            }

            val raw = StereoAudioBuffer(left, right, sampleRate)
            val result = if (!preserveSampleRate && sampleRate != TARGET_SAMPLE_RATE) {
                resampleStereo(raw, TARGET_SAMPLE_RATE)
            } else {
                raw
            }
            onProgress(1f)
            result
        }
    }.onFailure { if (it is OutOfMemoryError) throw it }.getOrNull()
}
