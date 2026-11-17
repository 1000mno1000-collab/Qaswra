package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val durationMs: Long,
    val sampleRate: Int,
    val configJson: String,
    val updatedAt: Long,
    val audioFileName: String?,
)

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val timestamp: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val sourceType: String,
    val audioFileName: String,
)

@Entity(tableName = "custom_presets")
data class CustomPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val configJson: String,
    val createdAt: Long,
)

@Entity(tableName = "local_accounts")
data class LocalAccountEntity(
    @PrimaryKey val uid: String,
    val displayName: String,
    val email: String,
    val passwordHash: String,
)
