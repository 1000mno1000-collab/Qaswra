package com.example.audio.io

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.min

/**
 * Pure Kotlin/JVM MP3 encoder powered by Jump3r's LAME engine without desktop javax.sound dependencies.
 */
object Mp3AudioEncoder {

    private const val CHUNK_FRAMES = 4096
    private const val MP3_BUFFER_BYTES = 16384

    fun encodeStereoBufferToMp3Bytes(
        buffer: StereoAudioBuffer,
        bitrateKbps: Int = 192,
        onProgress: (Float) -> Unit = {}
    ): ByteArray {
        val baos = ByteArrayOutputStream((buffer.frameCount / 4).coerceAtLeast(16384))
        encodeStereoBufferToMp3Stream(
            buffer = buffer,
            output = baos,
            bitrateKbps = bitrateKbps,
            onProgress = onProgress
        )
        return baos.toByteArray()
    }

    fun encodeStereoBufferToMp3Stream(
        buffer: StereoAudioBuffer,
        output: OutputStream,
        bitrateKbps: Int = 192,
        onProgress: (Float) -> Unit = {}
    ) {
        val totalFrames = buffer.frameCount
        if (totalFrames <= 0) {
            onProgress(1f)
            return
        }

        val lame = Lame()
        val gaud = GetAudio()
        val ga = GainAnalysis()
        val bs = BitStream()
        val presets = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()

        lame.setModules(ga, bs, presets, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        presets.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, presets)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        val gfp = lame.lame_init()
        gfp.num_channels = 2
        gfp.in_samplerate = buffer.sampleRate
        gfp.out_samplerate = buffer.sampleRate
        gfp.mode = MPEGMode.JOINT_STEREO
        gfp.brate = bitrateKbps
        gfp.quality = 2
        id3.id3tag_init(gfp)
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = false

        val initRc = lame.lame_init_params(gfp)
        check(initRc >= 0) { "Failed to initialize LAME MP3 parameters (code=$initRc)" }

        val bufferedOut = BufferedOutputStream(output, 32768)
        val leftInt = IntArray(CHUNK_FRAMES)
        val rightInt = IntArray(CHUNK_FRAMES)
        val mp3Out = ByteArray(MP3_BUFFER_BYTES)

        try {
            var offset = 0
            while (offset < totalFrames) {
                val count = min(CHUNK_FRAMES, totalFrames - offset)
                for (i in 0 until count) {
                    val idx = offset + i
                    val l16 = (buffer.left[idx].coerceIn(-1f, 1f) * 32767f).toInt()
                    val r16 = (buffer.right[idx].coerceIn(-1f, 1f) * 32767f).toInt()
                    leftInt[i] = l16 shl 16
                    rightInt[i] = r16 shl 16
                }
                val encodedBytes = lame.lame_encode_buffer_int(
                    gfp,
                    leftInt,
                    rightInt,
                    count,
                    mp3Out,
                    0,
                    mp3Out.size
                )
                if (encodedBytes > 0) {
                    bufferedOut.write(mp3Out, 0, encodedBytes)
                }
                offset += count
                onProgress((offset.toFloat() / totalFrames.toFloat()).coerceIn(0f, 0.99f))
            }

            val flushedBytes = lame.lame_encode_flush(gfp, mp3Out, 0, mp3Out.size)
            if (flushedBytes > 0) {
                bufferedOut.write(mp3Out, 0, flushedBytes)
            }
            bufferedOut.flush()
            onProgress(1f)
        } finally {
            runCatching { lame.lame_close(gfp) }
        }
    }
}
