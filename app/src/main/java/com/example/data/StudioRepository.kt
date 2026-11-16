package com.example.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.audio.io.StereoAudioBuffer
import com.example.audio.io.WavAudioCodec
import com.example.model.BuiltInPresets
import com.example.model.DspConfig
import com.example.model.StudioPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

data class StudioProject(
    val id: Long = 0,
    val name: String,
    val audioFilePath: String?,
    val durationMs: Long,
    val sampleRate: Int,
    val config: DspConfig,
    val updatedAt: Long = System.currentTimeMillis()
)

class StudioRepository(
    private val context: Context,
    private val dao: StudioDao
) {
    private val projectsDir = File(context.filesDir, "projects").apply { mkdirs() }
    private val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }

    val projects: Flow<List<StudioProject>> = dao.observeProjects().map { list ->
        list.map { entity ->
            StudioProject(
                id = entity.id,
                name = entity.name,
                audioFilePath = entity.audioFilePath,
                durationMs = entity.durationMs,
                sampleRate = entity.sampleRate,
                config = DspConfig.fromJson(entity.dspConfigJson),
                updatedAt = entity.updatedAt
            )
        }
    }

    val allPresets: Flow<List<StudioPreset>> = dao.observeCustomPresets().map { customEntities ->
        val custom = customEntities.map { entity ->
            StudioPreset(
                id = entity.id,
                name = entity.name,
                description = entity.description,
                isBuiltIn = false,
                config = DspConfig.fromJson(entity.dspConfigJson)
            )
        }
        custom + BuiltInPresets.all
    }

    suspend fun saveProject(
        existingId: Long,
        name: String,
        buffer: StereoAudioBuffer?,
        config: DspConfig
    ): StudioProject = withContext(Dispatchers.IO) {
        val cleanName = name.ifBlank { "مشروع صوتي" }
        val filePath = if (buffer != null) {
            val fileName = if (existingId > 0) "project_$existingId.wav" else "project_${System.currentTimeMillis()}.wav"
            val file = File(projectsDir, fileName)
            WavAudioCodec.writeWavFile(file, buffer)
            file.absolutePath
        } else {
            null
        }

        val now = System.currentTimeMillis()
        val entity = ProjectEntity(
            id = existingId,
            name = cleanName,
            audioFilePath = filePath,
            durationMs = buffer?.durationMs ?: 0L,
            sampleRate = buffer?.sampleRate ?: WavAudioCodec.TARGET_SAMPLE_RATE,
            dspConfigJson = DspConfig.toJson(config),
            createdAt = now,
            updatedAt = now
        )
        val newId = dao.upsertProject(entity)
        val finalId = if (existingId > 0) existingId else newId
        StudioProject(
            id = finalId,
            name = cleanName,
            audioFilePath = filePath,
            durationMs = entity.durationMs,
            sampleRate = entity.sampleRate,
            config = config,
            updatedAt = now
        )
    }

    suspend fun loadProjectAudio(project: StudioProject): StereoAudioBuffer? = withContext(Dispatchers.IO) {
        val path = project.audioFilePath ?: return@withContext null
        WavAudioCodec.readWavFile(File(path))
    }

    suspend fun deleteProject(project: StudioProject) = withContext(Dispatchers.IO) {
        project.audioFilePath?.let { path ->
            runCatching { File(path).delete() }
        }
        dao.deleteProjectById(project.id)
    }

    suspend fun saveCustomPreset(name: String, description: String, config: DspConfig) {
        withContext(Dispatchers.IO) {
            dao.insertPreset(
                PresetEntity(
                    name = name.trim(),
                    description = description.ifBlank { "قالب صوتي مخصص" },
                    dspConfigJson = DspConfig.toJson(config)
                )
            )
        }
    }

    suspend fun deleteCustomPreset(presetId: Long) {
        withContext(Dispatchers.IO) {
            dao.deletePresetById(presetId)
        }
    }

    suspend fun importAudioFromUri(uri: Uri): StereoAudioBuffer? = withContext(Dispatchers.IO) {
        WavAudioCodec.decodeAudioUri(context, uri)
    }

    suspend fun exportWavToUri(uri: Uri, rendered: StereoAudioBuffer): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                WavAudioCodec.writeWavStream(out, rendered)
            } ?: return@withContext false
            true
        }.getOrDefault(false)
    }

    suspend fun createShareWavUri(fileName: String, rendered: StereoAudioBuffer): Uri = withContext(Dispatchers.IO) {
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9\\u0600-\\u06FF_-]"), "_")
        val file = File(exportsDir, "${safeName}_${System.currentTimeMillis()}.wav")
        WavAudioCodec.writeWavFile(file, rendered)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
