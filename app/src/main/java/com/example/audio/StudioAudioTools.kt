package com.example.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.example.model.CompressorParams
import com.example.model.EqualizerParams
import com.example.model.GlobalProPreset
import com.example.model.GlobalProPresetId
import com.example.model.PitchShiftParams
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Adds ElevenLabs-grade studio vocal mastering warmth, compression, 8kHz air, and gentle loudness normalization.
 */
fun applyStudioWarmth(
    buffer: StereoAudioBuffer,
    warmthDb: Float = 2.5f,
    presenceDb: Float = 2.2f,
): StereoAudioBuffer = com.example.audio.fx.AudioSkillsPresets.applyStudioVocalMastering(
    buffer = buffer,
    extraWarmthDb = (warmthDb - 2.5f).coerceAtLeast(-2f),
    extraPresenceDb = (presenceDb - 1.5f).coerceAtLeast(-2f),
)

/**
 * Applies a Global Pro Preset with dedicated post-DSP shaping (8D autopan, walkie 8-bit bitcrusher,
 * vibrato synth, or crystal voice purification).
 */
suspend fun renderGlobalProPreset(
    buffer: StereoAudioBuffer,
    preset: GlobalProPreset,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val base = renderOfflineDsp(buffer, preset.dspConfig)
    if (base.frameCount <= 0) return@withContext base
    if (preset.id == GlobalProPresetId.BROADCAST_RADIO || preset.id == GlobalProPresetId.CRYSTAL_VOCAL) {
        normalizeAudio(base, -1.0f)
    } else {
        normalizeAudio(base, -0.8f)
    }
}

fun reverseAudio(buffer: StereoAudioBuffer): StereoAudioBuffer {
    val n = buffer.frameCount
    if (n <= 1) return buffer
    val outL = FloatArray(n)
    val outR = FloatArray(n)
    for (i in 0 until n) {
        outL[i] = buffer.left[n - 1 - i]
        outR[i] = buffer.right[n - 1 - i]
    }
    return StereoAudioBuffer(outL, outR, buffer.sampleRate)
}

fun generateSilence(
    durationSeconds: Float,
    sampleRate: Int = TARGET_SAMPLE_RATE,
): StereoAudioBuffer {
    val frames = max(sampleRate / 10, (clamp(durationSeconds, 0.5f, 120f) * sampleRate).roundToInt())
    return StereoAudioBuffer(
        left = FloatArray(frames),
        right = FloatArray(frames),
        sampleRate = sampleRate,
    )
}

fun convertToSurroundStereo(
    buffer: StereoAudioBuffer,
    midLevel: Float = 1.0f,
    sideLevel: Float = 1.35f,
    stereoWidth: Float = 0.78f,
): StereoAudioBuffer {
    val n = buffer.frameCount
    if (n == 0) return buffer
    val haasSamples = max(1, (0.011f * buffer.sampleRate * clamp(stereoWidth, 0f, 1f)).roundToInt())
    val outL = FloatArray(n)
    val outR = FloatArray(n)

    for (i in 0 until n) {
        val rawL = buffer.left[i]
        val delayedR = buffer.right[max(0, i - haasSamples)]
        val mid = 0.5f * (rawL + buffer.right[i]) * midLevel
        val naturalSide = 0.5f * (rawL - buffer.right[i])
        val haasSide = 0.5f * (rawL - delayedR) * clamp(stereoWidth, 0f, 1f)
        val side = (naturalSide * 0.65f + haasSide * 0.55f) * sideLevel
        outL[i] = clamp(mid + side, -1f, 1f)
        outR[i] = clamp(mid - side, -1f, 1f)
    }
    return normalizeAudio(StereoAudioBuffer(outL, outR, buffer.sampleRate), -1.0f)
}

fun splitAudioByTime(
    buffer: StereoAudioBuffer,
    splitFraction: Float,
): Pair<StereoAudioBuffer, StereoAudioBuffer> {
    val n = buffer.frameCount
    if (n < 4) return buffer to buffer
    val splitIdx = clamp(floor(splitFraction * n).toInt(), 1, n - 1)
    val first = StereoAudioBuffer(
        left = buffer.left.copyOfRange(0, splitIdx),
        right = buffer.right.copyOfRange(0, splitIdx),
        sampleRate = buffer.sampleRate,
    )
    val second = StereoAudioBuffer(
        left = buffer.left.copyOfRange(splitIdx, n),
        right = buffer.right.copyOfRange(splitIdx, n),
        sampleRate = buffer.sampleRate,
    )
    return first to second
}

fun splitStereoChannels(buffer: StereoAudioBuffer): Pair<StereoAudioBuffer, StereoAudioBuffer> {
    val leftOnly = StereoAudioBuffer(
        left = buffer.left.copyOf(),
        right = buffer.left.copyOf(),
        sampleRate = buffer.sampleRate,
    )
    val rightOnly = StereoAudioBuffer(
        left = buffer.right.copyOf(),
        right = buffer.right.copyOf(),
        sampleRate = buffer.sampleRate,
    )
    return leftOnly to rightOnly
}

suspend fun applyPitchAndSpeedOffline(
    buffer: StereoAudioBuffer,
    semitones: Float,
    tempoRatio: Float,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val base = com.example.model.DspConfig()
        .withSlotEnabled(com.example.model.EffectType.PITCH_SHIFT, abs(semitones) >= 0.05f)
        .withSlotEnabled(com.example.model.EffectType.TIME_STRETCH, abs(tempoRatio - 1f) >= 0.02f)
        .copy(
            pitchShift = PitchShiftParams(semitones = clamp(semitones, -12f, 12f), wetMix = 1f),
            timeStretch = com.example.model.TimeStretchParams(tempoRatio = clamp(tempoRatio, 0.5f, 2.0f)),
        )
    renderOfflineDsp(buffer, base)
}

/**
 * Smart Background Music Mixer with Sidechain Auto-Ducking:
 * Mirrors `[1:a][0:a]sidechaincompress=threshold=0.02:ratio=6:attack=200:release=1000[bg];[0:a][bg]amix=inputs=2:duration=first`.
 * Lowers background music cleanly whenever voice activity crosses threshold and swells smoothly during pauses.
 */
suspend fun mixWithSidechainAutoDucking(
    voice: StereoAudioBuffer,
    music: StereoAudioBuffer,
    musicBaseVolume: Float = 0.28f,
    voiceGain: Float = 1.0f,
    threshold: Float = 0.02f,
    ratio: Float = 6.0f,
    attackMs: Float = 200f,
    releaseMs: Float = 1000f,
): StereoAudioBuffer = withContext(Dispatchers.Default) {
    val n = voice.frameCount
    if (n <= 0) return@withContext voice
    val sr = voice.sampleRate.coerceAtLeast(8000)

    val alignedMusic = if (music.sampleRate == sr) music else resampleStereo(music, sr)
    val musicFrames = alignedMusic.frameCount
    if (musicFrames <= 0) return@withContext applyGainDb(voice, (20.0 * kotlin.math.log10(max(voiceGain, 0.01f))).toFloat())

    val outL = FloatArray(n)
    val outR = FloatArray(n)

    val attackCoeff = kotlin.math.exp(-1.0 / (max(1f, attackMs) * 0.001f * sr)).toFloat()
    val releaseCoeff = kotlin.math.exp(-1.0 / (max(1f, releaseMs) * 0.001f * sr)).toFloat()
    val safeThresh = max(0.002f, threshold)
    val safeRatio = max(1.0f, ratio)
    val baseBg = clamp(musicBaseVolume, 0f, 1.5f)
    val fadeLoopFrames = min(sr / 4, musicFrames / 8)

    var env = 0f
    var duckGain = 1.0f
    val gainAttackCoeff = kotlin.math.exp(-1.0 / (0.045f * sr)).toFloat()
    val gainReleaseCoeff = kotlin.math.exp(-1.0 / (0.350f * sr)).toFloat()

    for (i in 0 until n) {
        if (i % 16384 == 0) yield()
        val vL = voice.left[i] * voiceGain
        val vR = voice.right[i] * voiceGain
        val detector = max(abs(vL), abs(vR))

        env = if (detector > env) {
            attackCoeff * env + (1f - attackCoeff) * detector
        } else {
            releaseCoeff * env + (1f - releaseCoeff) * detector
        }

        val targetDuck = if (env > safeThresh) {
            val overRatio = env / safeThresh
            val compressed = overRatio.toDouble().pow(1.0 / safeRatio - 1.0).toFloat()
            clamp(compressed, 0.14f, 1.0f)
        } else {
            1.0f
        }

        duckGain = if (targetDuck < duckGain) {
            gainAttackCoeff * duckGain + (1f - gainAttackCoeff) * targetDuck
        } else {
            gainReleaseCoeff * duckGain + (1f - gainReleaseCoeff) * targetDuck
        }

        val mIdx = i % musicFrames
        val loopEnv = if (musicFrames > fadeLoopFrames * 2 && fadeLoopFrames > 0) {
            when {
                mIdx < fadeLoopFrames -> mIdx.toFloat() / fadeLoopFrames
                mIdx > musicFrames - fadeLoopFrames -> (musicFrames - mIdx).toFloat() / fadeLoopFrames
                else -> 1f
            }
        } else {
            1f
        }

        val bgScale = baseBg * duckGain * loopEnv
        val bgL = alignedMusic.left[mIdx] * bgScale
        val bgR = alignedMusic.right[mIdx] * bgScale

        outL[i] = tanh((vL + bgL).toDouble()).toFloat()
        outR[i] = tanh((vR + bgR).toDouble()).toFloat()
    }

    StereoAudioBuffer(outL, outR, sr)
}

/**
 * Replaces or mixes the audio track of an MP4/MOV video with processed studio audio
 * while copying the compressed video track losslessly without video re-encoding (`-c:v copy -c:a aac -shortest`).
 */
suspend fun replaceOrMixVideoAudio(
    context: Context,
    videoUri: Uri,
    newAudio: StereoAudioBuffer,
    mixWithOriginal: Boolean,
    originalGain: Float,
    newGain: Float,
    outputMp4File: File,
    onProgress: ((Float) -> Unit)? = null,
): Boolean = withContext(Dispatchers.IO) {
    val preparedAudio = if (mixWithOriginal) {
        val originalAudio = AudioDecoder.decodeUri(context, videoUri)
        if (originalAudio != null && originalAudio.frameCount > 0) {
            mixTracks(
                main = newAudio,
                background = originalAudio,
                mainGain = newGain,
                bgGain = originalGain,
            )
        } else {
            applyGainDb(newAudio, (20.0 * kotlin.math.log10(max(newGain, 0.01f))).toFloat())
        }
    } else {
        applyGainDb(newAudio, (20.0 * kotlin.math.log10(max(newGain, 0.01f))).toFloat())
    }

    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var muxerStarted = false
    var aacCodec: MediaCodec? = null

    try {
        extractor.setDataSource(context, videoUri, null)
        var videoTrackIndex = -1
        var videoFormat: MediaFormat? = null
        var videoDurationUs = 0L

        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                videoFormat = fmt
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    videoDurationUs = fmt.getLong(MediaFormat.KEY_DURATION)
                }
                break
            }
        }
        if (videoTrackIndex < 0 || videoFormat == null) return@withContext false

        val audioDurationUs = (preparedAudio.frameCount * 1_000_000L) / preparedAudio.sampleRate.coerceAtLeast(8000)
        val targetMaxUs = if (videoDurationUs > 0L) min(videoDurationUs, audioDurationUs) else audioDurationUs

        val aacMime = MediaFormat.MIMETYPE_AUDIO_AAC
        val aacFormat = MediaFormat.createAudioFormat(aacMime, preparedAudio.sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 320_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        aacCodec = MediaCodec.createEncoderByType(aacMime).apply {
            configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        muxer = MediaMuxer(outputMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxVideoTrack = muxer.addTrack(videoFormat)
        var muxAudioTrack = -1

        val bufferInfo = MediaCodec.BufferInfo()
        val numFramesToEncode = if (targetMaxUs > 0L) {
            min(preparedAudio.frameCount, ((targetMaxUs * preparedAudio.sampleRate) / 1_000_000L).toInt())
        } else {
            preparedAudio.frameCount
        }.coerceAtLeast(1)

        var frameOffset = 0
        var inputDone = false
        var outputDone = false
        val encodedAudioPackets = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = aacCodec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val inBuf = aacCodec.getInputBuffer(inIdx)
                    if (frameOffset >= numFramesToEncode || inBuf == null) {
                        aacCodec.queueInputBuffer(
                            inIdx,
                            0,
                            0,
                            (frameOffset * 1_000_000L) / preparedAudio.sampleRate,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        inBuf.clear()
                        val maxFrames = inBuf.remaining() / 4
                        val chunkFrames = minOf(1024, maxFrames, numFramesToEncode - frameOffset)
                        val bytes = ByteArray(chunkFrames * 4)
                        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until chunkFrames) {
                            bb.putShort((clamp(preparedAudio.left[frameOffset + i], -1f, 1f) * 32767f).roundToInt().toShort())
                            bb.putShort((clamp(preparedAudio.right[frameOffset + i], -1f, 1f) * 32767f).roundToInt().toShort())
                        }
                        inBuf.put(bytes)
                        val ptsUs = (frameOffset * 1_000_000L) / preparedAudio.sampleRate
                        aacCodec.queueInputBuffer(inIdx, 0, bytes.size, ptsUs, 0)
                        frameOffset += chunkFrames
                        onProgress?.invoke(min(0.55f, (frameOffset.toFloat() / numFramesToEncode) * 0.55f))
                    }
                }
            }

            val outIdx = aacCodec.dequeueOutputBuffer(bufferInfo, 10_000L)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxAudioTrack == -1) {
                        muxAudioTrack = muxer.addTrack(aacCodec.outputFormat)
                    }
                }
                outIdx >= 0 -> {
                    val encoded = aacCodec.getOutputBuffer(outIdx)
                    if (encoded != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        val pktBytes = ByteArray(bufferInfo.size)
                        encoded.get(pktBytes)
                        val infoCopy = MediaCodec.BufferInfo().apply {
                            set(0, pktBytes.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        }
                        encodedAudioPackets.add(pktBytes to infoCopy)
                    }
                    aacCodec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }
        }

        if (muxAudioTrack == -1) return@withContext false
        muxer.start()
        muxerStarted = true

        // Copy video packets (-c:v copy) up to -shortest duration
        extractor.selectTrack(videoTrackIndex)
        val vidBuf = ByteBuffer.allocate(512 * 1024)
        val vidInfo = MediaCodec.BufferInfo()
        while (true) {
            vidInfo.offset = 0
            val sampleSize = extractor.readSampleData(vidBuf, 0)
            if (sampleSize < 0) break
            val ptsUs = extractor.sampleTime
            if (targetMaxUs > 0L && ptsUs > targetMaxUs) break
            vidInfo.size = sampleSize
            vidInfo.presentationTimeUs = ptsUs
            vidInfo.flags = extractor.sampleFlags
            vidBuf.position(0)
            vidBuf.limit(sampleSize)
            muxer.writeSampleData(muxVideoTrack, vidBuf, vidInfo)
            extractor.advance()
        }
        onProgress?.invoke(0.8f)

        // Write encoded AAC packets
        for ((idx, pair) in encodedAudioPackets.withIndex()) {
            val (pktBytes, pktInfo) = pair
            muxer.writeSampleData(muxAudioTrack, ByteBuffer.wrap(pktBytes), pktInfo)
            if (idx % 20 == 0) {
                onProgress?.invoke(0.8f + (idx.toFloat() / max(1, encodedAudioPackets.size)) * 0.2f)
            }
        }
        onProgress?.invoke(1f)
        true
    } catch (_: Throwable) {
        false
    } finally {
        runCatching { aacCodec?.stop() }
        runCatching { aacCodec?.release() }
        runCatching { extractor.release() }
        if (muxerStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
    }
}

sealed interface RingtoneSaveResult {
    data class Saved(val uri: Uri, val appliedAsDefault: Boolean, val needsWriteSettings: Boolean) : RingtoneSaveResult
    data class Error(val message: String) : RingtoneSaveResult
}

suspend fun saveAndSetRingtone(
    context: Context,
    buffer: StereoAudioBuffer,
    title: String,
    setAsDefault: Boolean,
): RingtoneSaveResult = withContext(Dispatchers.IO) {
    val cleanTitle = title.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Sonix_Ringtone" }
    val exported = exportRenderedAudioToFile(
        context = context,
        buffer = buffer,
        format = com.example.model.ExportAudioFormat.M4A,
        baseFileName = cleanTitle,
        bitrateKbps = 320,
    )

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "${cleanTitle}.${exported.extension}")
        put(MediaStore.MediaColumns.TITLE, cleanTitle)
        put(MediaStore.MediaColumns.MIME_TYPE, exported.mimeType)
        put(MediaStore.Audio.Media.IS_RINGTONE, 1)
        put(MediaStore.Audio.Media.IS_NOTIFICATION, 0)
        put(MediaStore.Audio.Media.IS_ALARM, 0)
        put(MediaStore.Audio.Media.IS_MUSIC, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val uri = runCatching {
        resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull() ?: return@withContext RingtoneSaveResult.Error("تعذر حفظ النغمة في مجلد الرنين")

    return@withContext try {
        resolver.openOutputStream(uri)?.use { out ->
            exported.file.inputStream().use { inp -> inp.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, finish, null, null)
        }
        var applied = false
        var needsPerm = false
        if (setAsDefault) {
            if (Settings.System.canWrite(context)) {
                runCatching {
                    RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, uri)
                    applied = true
                }
            } else {
                needsPerm = true
            }
        }
        RingtoneSaveResult.Saved(uri = uri, appliedAsDefault = applied, needsWriteSettings = needsPerm)
    } catch (t: Throwable) {
        RingtoneSaveResult.Error(t.message ?: "خطأ أثناء كتابة ملف النغمة")
    }
}
