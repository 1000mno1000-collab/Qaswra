package com.example.audio.video

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import com.example.audio.AudioDecoder
import com.example.audio.clamp
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Hardware-accelerated vertical Reels (1080x1920 @ 60 FPS, 320kbps AAC) Audiogram Video Creator
 * built cleanly on Android MediaCodec + Canvas + MediaMuxer with zero external native binaries.
 */
object AudioToVideoCreator {

    private const val WIDTH = 1080
    private const val HEIGHT = 1920
    private const val FPS = 60
    private const val VIDEO_BITRATE = 8_500_000
    private const val AUDIO_BITRATE = 320_000

    suspend fun createAudiogramVideo(
        inputAudioPath: String,
        outputVideoPath: String,
        waveColor: String = "0x00E5FF",
        bgColor: String = "0x0D1117",
        titleText: String = "Sonix ✦ Studio",
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val audioFile = File(inputAudioPath)
        if (!audioFile.exists()) return@withContext false
        val buffer = AudioDecoder.decodeFile(audioFile) ?: return@withContext false
        createAudiogramVideoFromBuffer(
            buffer = buffer,
            outputFile = File(outputVideoPath),
            waveColorHex = waveColor,
            bgColorHex = bgColor,
            titleText = titleText,
            onProgress = onProgress,
        )
    }

    suspend fun createAudiogramVideoFromBuffer(
        buffer: StereoAudioBuffer,
        outputFile: File,
        waveColorHex: String = "0x00E5FF",
        bgColorHex: String = "0x0D1117",
        titleText: String = "Sonix ✦ Studio",
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val totalAudioFrames = buffer.frameCount
        if (totalAudioFrames <= 0) return@withContext false
        val sr = buffer.sampleRate.coerceAtLeast(8000)
        val durationSec = totalAudioFrames.toDouble() / sr.toDouble()
        val totalVideoFrames = max(FPS, (durationSec * FPS).roundToInt()).coerceAtMost(FPS * 180)

        outputFile.parentFile?.mkdirs()

        val waveInt = parseHexColor(waveColorHex, Color.parseColor("#00E5FF"))
        val bgInt = parseHexColor(bgColorHex, Color.parseColor("#0D1117"))

        var videoCodec: MediaCodec? = null
        var audioCodec: MediaCodec? = null
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            // 1. Pre-encode Studio 320kbps AAC packets so muxer interleaves cleanly
            val aacFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(aacFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            val encodedAudioPackets = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
            var resolvedAudioFormat: MediaFormat? = null
            val audioInfo = MediaCodec.BufferInfo()
            var frameOffset = 0
            var aInDone = false
            var aOutDone = false
            val maxEncodeAudioFrames = min(totalAudioFrames, (180L * sr).toInt())

            while (!aOutDone) {
                if (!aInDone) {
                    val inIdx = audioCodec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = audioCodec.getInputBuffer(inIdx)
                        if (frameOffset >= maxEncodeAudioFrames || inBuf == null) {
                            audioCodec.queueInputBuffer(
                                inIdx,
                                0,
                                0,
                                (frameOffset * 1_000_000L) / sr,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            aInDone = true
                        } else {
                            inBuf.clear()
                            val chunkFrames = minOf(1024, inBuf.remaining() / 4, maxEncodeAudioFrames - frameOffset)
                            val pcm = ByteArray(chunkFrames * 4)
                            val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until chunkFrames) {
                                bb.putShort((clamp(buffer.left[frameOffset + i], -1f, 1f) * 32767f).roundToInt().toShort())
                                bb.putShort((clamp(buffer.right[frameOffset + i], -1f, 1f) * 32767f).roundToInt().toShort())
                            }
                            inBuf.put(pcm)
                            audioCodec.queueInputBuffer(
                                inIdx,
                                0,
                                pcm.size,
                                (frameOffset * 1_000_000L) / sr,
                                0,
                            )
                            frameOffset += chunkFrames
                        }
                    }
                }

                val outIdx = audioCodec.dequeueOutputBuffer(audioInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        resolvedAudioFormat = audioCodec.outputFormat
                    }
                    outIdx >= 0 -> {
                        val outBuf = audioCodec.getOutputBuffer(outIdx)
                        if (outBuf != null && audioInfo.size > 0 &&
                            (audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            outBuf.position(audioInfo.offset)
                            outBuf.limit(audioInfo.offset + audioInfo.size)
                            val bytes = ByteArray(audioInfo.size)
                            outBuf.get(bytes)
                            val infoCopy = MediaCodec.BufferInfo().apply {
                                set(0, bytes.size, audioInfo.presentationTimeUs, audioInfo.flags)
                            }
                            encodedAudioPackets.add(bytes to infoCopy)
                        }
                        audioCodec.releaseOutputBuffer(outIdx, false)
                        if ((audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            aOutDone = true
                        }
                    }
                }
            }
            onProgress?.invoke(0.18f)

            // 2. Configure 1080x1920 @ 60 FPS H.264 encoder Surface
            val videoFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(videoFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = videoCodec.createInputSurface()
            videoCodec.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxVideoTrack = -1
            var muxAudioTrack = -1
            val vInfo = MediaCodec.BufferInfo()

            // Precompute Paints & Gradient for smooth 60 FPS drawing
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, HEIGHT.toFloat(),
                    intArrayOf(blendDark(bgInt, 0.85f), bgInt, blendAccent(bgInt, waveInt, 0.14f)),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(65, Color.red(waveInt), Color.green(waveInt), Color.blue(waveInt))
                style = Paint.Style.FILL
            }
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = waveInt
                style = Paint.Style.FILL
            }
            val waveLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(230, Color.red(waveInt), Color.green(waveInt), Color.blue(waveInt))
                style = Paint.Style.STROKE
                strokeWidth = 6f
                strokeCap = Paint.Cap.ROUND
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 54f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = waveInt
                textSize = 30f
                textAlign = Paint.Align.CENTER
            }
            val wavePath = Path()
            val numBars = 48
            val barLevels = FloatArray(numBars)

            for (frameIdx in 0 until totalVideoFrames) {
                if (frameIdx % 15 == 0) yield()
                val timeSec = frameIdx.toDouble() / FPS.toDouble()
                val centerSample = min(totalAudioFrames - 1, (timeSec * sr).roundToInt())

                // Compute bar amplitudes around current playback instant
                val windowSamples = max(64, sr / 30)
                val barSpan = max(8, windowSamples / numBars)
                var energySum = 0f
                for (b in 0 until numBars) {
                    val startS = (centerSample - windowSamples / 2 + b * barSpan).coerceIn(0, totalAudioFrames - 1)
                    val endS = (startS + barSpan).coerceAtMost(totalAudioFrames)
                    var peak = 0f
                    var s = startS
                    while (s < endS) {
                        val v = 0.5f * (abs(buffer.left[s]) + abs(buffer.right[s]))
                        if (v > peak) peak = v
                        s += 4
                    }
                    barLevels[b] = barLevels[b] * 0.58f + peak.coerceIn(0.03f, 1f) * 0.42f
                    energySum += barLevels[b]
                }
                val avgEnergy = (energySum / numBars).coerceIn(0.05f, 1f)

                val canvas = inputSurface.lockCanvas(null)
                try {
                    canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bgPaint)

                    // Subtle pulsing ambient aura behind waveform
                    val auraRadius = 260f + avgEnergy * 240f
                    glowPaint.alpha = (38 + (avgEnergy * 75f).toInt()).coerceIn(25, 130)
                    canvas.drawCircle(WIDTH / 2f, HEIGHT / 2f, auraRadius, glowPaint)

                    // Title & studio pill
                    canvas.drawText(titleText.take(28), WIDTH / 2f, HEIGHT * 0.27f, titlePaint)
                    canvas.drawText("SONIX ✦ REELS 60 FPS", WIDTH / 2f, HEIGHT * 0.31f, badgePaint)

                    // Symmetrical Neon Bars
                    val totalWaveW = WIDTH * 0.84f
                    val leftStart = (WIDTH - totalWaveW) / 2f
                    val stepW = totalWaveW / numBars
                    val barW = stepW * 0.58f
                    val cy = HEIGHT / 2f
                    val maxBarH = 420f

                    for (b in 0 until numBars) {
                        val h = max(18f, barLevels[b] * maxBarH)
                        val x = leftStart + b * stepW + (stepW - barW) / 2f
                        canvas.drawRoundRect(
                            x,
                            cy - h / 2f,
                            x + barW,
                            cy + h / 2f,
                            barW / 2f,
                            barW / 2f,
                            barPaint,
                        )
                    }

                    // Flowing Liquid Sine Crest overlay
                    wavePath.reset()
                    val points = 72
                    for (p in 0..points) {
                        val frac = p.toFloat() / points
                        val px = leftStart + frac * totalWaveW
                        val env = sin(frac * PI).toFloat()
                        val dy = sin(frac * 10.0 + timeSec * 8.0).toFloat() * (28f + avgEnergy * 110f) * env
                        val py = cy + 290f + dy
                        if (p == 0) wavePath.moveTo(px, py) else wavePath.lineTo(px, py)
                    }
                    canvas.drawPath(wavePath, waveLinePaint)

                    // Bottom elapsed timer
                    val curSec = timeSec.toInt()
                    val totSec = durationSec.toInt()
                    val timerStr = String.format("%02d:%02d / %02d:%02d", curSec / 60, curSec % 60, totSec / 60, totSec % 60)
                    canvas.drawText(timerStr, WIDTH / 2f, HEIGHT * 0.74f, badgePaint)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                // Drain available video packets
                drainVideoEncoder(
                    videoCodec = videoCodec,
                    muxer = muxer,
                    vInfo = vInfo,
                    resolvedAudioFormat = resolvedAudioFormat,
                    endOfStream = false,
                    onMuxTracksReady = { vTrack, aTrack ->
                        muxVideoTrack = vTrack
                        muxAudioTrack = aTrack
                        muxerStarted = true
                    },
                    getMuxVideoTrack = { muxVideoTrack },
                    isMuxerStarted = { muxerStarted },
                )

                if (frameIdx % 10 == 0) {
                    onProgress?.invoke(0.18f + (frameIdx.toFloat() / totalVideoFrames) * 0.74f)
                }
            }

            videoCodec.signalEndOfInputStream()
            drainVideoEncoder(
                videoCodec = videoCodec,
                muxer = muxer,
                vInfo = vInfo,
                resolvedAudioFormat = resolvedAudioFormat,
                endOfStream = true,
                onMuxTracksReady = { vTrack, aTrack ->
                    muxVideoTrack = vTrack
                    muxAudioTrack = aTrack
                    muxerStarted = true
                },
                getMuxVideoTrack = { muxVideoTrack },
                isMuxerStarted = { muxerStarted },
            )

            // 3. Write all AAC 320kbps audio packets into muxer
            if (muxerStarted && muxAudioTrack >= 0) {
                for ((idx, pair) in encodedAudioPackets.withIndex()) {
                    val (pkt, info) = pair
                    muxer.writeSampleData(muxAudioTrack, ByteBuffer.wrap(pkt), info)
                    if (idx % 30 == 0) {
                        onProgress?.invoke(0.92f + (idx.toFloat() / max(1, encodedAudioPackets.size)) * 0.08f)
                    }
                }
            }
            onProgress?.invoke(1f)
            muxerStarted && outputFile.exists() && outputFile.length() > 1024L
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { inputSurface?.release() }
            runCatching { videoCodec?.stop() }
            runCatching { videoCodec?.release() }
            runCatching { audioCodec?.stop() }
            runCatching { audioCodec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private inline fun drainVideoEncoder(
        videoCodec: MediaCodec,
        muxer: MediaMuxer,
        vInfo: MediaCodec.BufferInfo,
        resolvedAudioFormat: MediaFormat?,
        endOfStream: Boolean,
        onMuxTracksReady: (Int, Int) -> Unit,
        getMuxVideoTrack: () -> Int,
        isMuxerStarted: () -> Boolean,
    ) {
        val timeoutUs = if (endOfStream) 10_000L else 0L
        while (true) {
            val outIdx = videoCodec.dequeueOutputBuffer(vInfo, timeoutUs)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!isMuxerStarted()) {
                        val vTrack = muxer.addTrack(videoCodec.outputFormat)
                        val aTrack = if (resolvedAudioFormat != null) muxer.addTrack(resolvedAudioFormat) else -1
                        muxer.start()
                        onMuxTracksReady(vTrack, aTrack)
                    }
                }
                outIdx >= 0 -> {
                    val encoded = videoCodec.getOutputBuffer(outIdx)
                    if (encoded != null && vInfo.size > 0 &&
                        (vInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                        isMuxerStarted()
                    ) {
                        encoded.position(vInfo.offset)
                        encoded.limit(vInfo.offset + vInfo.size)
                        muxer.writeSampleData(getMuxVideoTrack(), encoded, vInfo)
                    }
                    videoCodec.releaseOutputBuffer(outIdx, false)
                    if ((vInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    private fun parseHexColor(hex: String, fallback: Int): Int {
        val cleaned = hex.trim().removePrefix("0x").removePrefix("0X").removePrefix("#")
        return runCatching {
            Color.parseColor("#$cleaned")
        }.getOrDefault(fallback)
    }

    private fun blendDark(c: Int, factor: Float): Int = Color.rgb(
        (Color.red(c) * factor).roundToInt().coerceIn(0, 255),
        (Color.green(c) * factor).roundToInt().coerceIn(0, 255),
        (Color.blue(c) * factor).roundToInt().coerceIn(0, 255),
    )

    private fun blendAccent(base: Int, accent: Int, amount: Float): Int = Color.rgb(
        (Color.red(base) * (1f - amount) + Color.red(accent) * amount).roundToInt().coerceIn(0, 255),
        (Color.green(base) * (1f - amount) + Color.green(accent) * amount).roundToInt().coerceIn(0, 255),
        (Color.blue(base) * (1f - amount) + Color.blue(accent) * amount).roundToInt().coerceIn(0, 255),
    )
}
