package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val audioFilePath: String?,
    val durationMs: Long,
    val sampleRate: Int,
    val dspConfigJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val dspConfigJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "audio_recordings")
data class AudioRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val sampleRate: Int = 44100,
    val sourceType: String = "RECORDING"
)

