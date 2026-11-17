package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StudioDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity): Long

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: Long)

    @Query("SELECT * FROM presets ORDER BY createdAt DESC")
    fun observeCustomPresets(): Flow<List<PresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: PresetEntity): Long

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun deletePresetById(id: Long)

    @Query("SELECT * FROM audio_recordings ORDER BY timestamp DESC")
    fun observeRecordings(): Flow<List<AudioRecordingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecording(recording: AudioRecordingEntity): Long

    @Query("DELETE FROM audio_recordings WHERE id = :id")
    suspend fun deleteRecordingById(id: Long)
}

