package com.example.audio.unity

import com.example.audio.AudioDecoder
import com.example.audio.clamp
import com.example.audio.resampleStereo
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

data class UnityExportBundle(
    val wavFile: File,
    val csharpScriptFile: File,
    val zipBundleFile: File? = null,
)

object UnityExporter {

    private const val UNITY_SAMPLE_RATE = 48000

    /**
     * يصدّر الصوت من ملف إدخال بصيغة WAV 48kHz Mono 16-bit PCM بدون بيانات وصفية زائدة
     * (مثالية لمحرك Unity بدون تأخير) ويولّد معه سكربت C# جاهز للسحب والإفلات داخل مجلد Assets في Unity.
     */
    suspend fun exportForUnity(
        inputAudioPath: String,
        outputDir: File,
        assetName: String,
    ): UnityExportBundle? = withContext(Dispatchers.IO) {
        val sourceFile = File(inputAudioPath)
        if (!sourceFile.exists() || sourceFile.length() == 0L) return@withContext null
        val decoded = AudioDecoder.decodeFile(sourceFile) ?: return@withContext null
        exportBufferForUnity(
            buffer = decoded,
            outputDir = outputDir,
            assetName = assetName,
        )
    }

    /**
     * يصدّر [StereoAudioBuffer] مباشرة بصيغة Unity-Ready (48kHz, Mono, pcm_s16le, Zero Metadata)
     * مع توليد ملف C# ([MonoBehaviour]) وحزمة ZIP اختيارية.
     */
    suspend fun exportBufferForUnity(
        buffer: StereoAudioBuffer,
        outputDir: File,
        assetName: String,
    ): UnityExportBundle? = withContext(Dispatchers.IO) {
        if (buffer.frameCount <= 0) return@withContext null
        if (!outputDir.exists()) outputDir.mkdirs()

        var cleanName = assetName.replace(Regex("[^a-zA-Z0-9_]"), "_")
            .trim('_')
            .ifBlank { "SonixGameAudio" }
        if (cleanName.first().isDigit()) {
            cleanName = "Sonix_$cleanName"
        }

        val wavOut = File(outputDir, "$cleanName.wav")
        val csOut = File(outputDir, "${cleanName}Player.cs")
        val zipOut = File(outputDir, "${cleanName}_UnityAssets.zip")

        val resampled = if (buffer.sampleRate != UNITY_SAMPLE_RATE) {
            resampleStereo(buffer, UNITY_SAMPLE_RATE)
        } else {
            buffer
        }
        val wavOk = runCatching {
            writeUnityMono48kWav(wavOut, resampled)
            wavOut.exists() && wavOut.length() > 44L
        }.getOrDefault(false)
        if (!wavOk) {
            wavOut.delete()
            return@withContext null
        }

        // توليد سكربت C# جاهز للعمل مباشرة داخل Unity Engine
        val csharpCode = """
            using UnityEngine;

            // تم توليد هذا السكربت تلقائياً بواسطة تطبيق Sonix ✦
            // ضع هذا الملف مع ملف الصوت ($cleanName.wav) داخل مجلد Assets في Unity
            [RequireComponent(typeof(AudioSource))]
            public class ${cleanName}Player : MonoBehaviour
            {
                public AudioClip sonixClip;
                public bool playOnStart = true;
                public bool loopAudio = false;
                private AudioSource audioSource;

                void Start()
                {
                    audioSource = GetComponent<AudioSource>();
                    audioSource.clip = sonixClip;
                    audioSource.loop = loopAudio;
                    audioSource.playOnAwake = false;

                    if (playOnStart && sonixClip != null)
                    {
                        PlaySonixAudio();
                    }
                }

                public void PlaySonixAudio()
                {
                    if (audioSource != null && sonixClip != null)
                    {
                        audioSource.Play();
                    }
                }
            }
        """.trimIndent()

        csOut.writeText(csharpCode, Charsets.UTF_8)

        val zipped = runCatching {
            ZipOutputStream(FileOutputStream(zipOut)).use { zos ->
                listOf(wavOut, csOut).forEach { file ->
                    zos.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { inp -> inp.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            zipOut.exists() && zipOut.length() > 0L
        }.getOrDefault(false)

        return@withContext UnityExportBundle(
            wavFile = wavOut,
            csharpScriptFile = csOut,
            zipBundleFile = if (zipped) zipOut else null,
        )
    }

    /**
     * Writes canonical 48kHz Mono 16-bit PCM WAV (`pcm_s16le`) stripped of all metadata chunks
     * so Unity loads the AudioClip instantaneously with minimal memory overhead.
     */
    private fun writeUnityMono48kWav(outputFile: File, buffer: StereoAudioBuffer) {
        val numFrames = buffer.frameCount
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = UNITY_SAMPLE_RATE * numChannels * (bitsPerSample / 8)
        val blockAlign = numChannels * (bitsPerSample / 8)
        val dataSize = numFrames * blockAlign
        val totalBytes = 44 + dataSize

        val out = ByteArray(totalBytes)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataSize)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1) // PCM s16le
        bb.putShort(numChannels.toShort())
        bb.putInt(UNITY_SAMPLE_RATE)
        bb.putInt(byteRate)
        bb.putShort(blockAlign.toShort())
        bb.putShort(bitsPerSample.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataSize)

        for (i in 0 until numFrames) {
            val mono = (buffer.left[i] + buffer.right[i]) * 0.5f
            val sample = (clamp(mono, -1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
            bb.putShort(sample.toShort())
        }
        outputFile.writeBytes(out)
    }
}
