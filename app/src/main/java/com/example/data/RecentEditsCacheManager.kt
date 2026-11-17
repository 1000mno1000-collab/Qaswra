package com.example.data

import android.content.Context
import com.example.audio.writeStereoWavFile
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class RecentEditEntry(
    val id: String,
    val toolId: String,
    val titleAr: String,
    val titleEn: String,
    val audioName: String,
    val summaryAr: String,
    val summaryEn: String,
    val timestampMs: Long,
    val wavPath: String,
    val floatParams: Map<String, Float> = emptyMap(),
    val stringParams: Map<String, String> = emptyMap(),
)

/**
 * Manages temporary cached "Continue Recent Edits" snapshots exclusively inside context.cacheDir.
 * Never writes anything to permanent user device storage without explicit export/save consent.
 */
object RecentEditsCacheManager {
    private const val DIR_NAME = "recent_edits"
    private const val META_FILE = "recent_edits.json"
    private const val MAX_ITEMS = 8

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cacheRoot: File? = null
    private val lock = Any()

    private val _entries = MutableStateFlow<List<RecentEditEntry>>(emptyList())
    val entries: StateFlow<List<RecentEditEntry>> = _entries.asStateFlow()

    fun init(context: Context) {
        val appCtx = context.applicationContext ?: context
        synchronized(lock) {
            if (cacheRoot != null) return
            val dir = File(appCtx.cacheDir, DIR_NAME).apply { mkdirs() }
            cacheRoot = dir
            val loaded = readMetadataLocked(dir)
                .filter { File(it.wavPath).exists() }
                .sortedByDescending { it.timestampMs }
                .take(MAX_ITEMS)
            _entries.value = loaded
        }
    }

    suspend fun saveSession(
        context: Context,
        toolId: String,
        titleAr: String,
        titleEn: String,
        audioName: String,
        summaryAr: String,
        summaryEn: String,
        buffer: StereoAudioBuffer,
        floatParams: Map<String, Float>,
        stringParams: Map<String, String> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        if (buffer.frameCount <= 0) return@withContext
        init(context)
        val dir = cacheRoot ?: File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        val cleanTool = toolId.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val entryId = "${cleanTool}_${UUID.randomUUID().toString().take(8)}"
        val wavFile = File(dir, "$entryId.wav")

        runCatching {
            writeStereoWavFile(wavFile, buffer)
        }.onFailure {
            runCatching { wavFile.delete() }
            return@withContext
        }

        val entry = RecentEditEntry(
            id = entryId,
            toolId = toolId,
            titleAr = titleAr,
            titleEn = titleEn,
            audioName = audioName.ifBlank { "Studio Audio" },
            summaryAr = summaryAr,
            summaryEn = summaryEn,
            timestampMs = System.currentTimeMillis(),
            wavPath = wavFile.absolutePath,
            floatParams = floatParams,
            stringParams = stringParams,
        )

        synchronized(lock) {
            // Keep one latest cache per (toolId + audioName) so the strip stays crisp and varied
            val existing = _entries.value.filterNot {
                val dup = it.toolId == toolId && it.audioName == entry.audioName
                if (dup) runCatching { File(it.wavPath).delete() }
                dup
            }
            val merged = (listOf(entry) + existing)
            val kept = merged.take(MAX_ITEMS)
            val dropped = merged.drop(MAX_ITEMS)
            dropped.forEach { old -> runCatching { File(old.wavPath).delete() } }
            _entries.value = kept
            writeMetadataLocked(dir, kept)
        }
    }

    fun removeEntry(entryId: String) {
        synchronized(lock) {
            val target = _entries.value.firstOrNull { it.id == entryId }
            if (target != null) runCatching { File(target.wavPath).delete() }
            val next = _entries.value.filterNot { it.id == entryId }
            _entries.value = next
            cacheRoot?.let { writeMetadataLocked(it, next) }
        }
    }

    private fun readMetadataLocked(dir: File): List<RecentEditEntry> {
        val meta = File(dir, META_FILE)
        if (!meta.exists()) return emptyList()
        return runCatching {
            val txt = meta.readText()
            if (txt.isBlank()) emptyList() else json.decodeFromString<List<RecentEditEntry>>(txt)
        }.getOrDefault(emptyList())
    }

    private fun writeMetadataLocked(dir: File, list: List<RecentEditEntry>) {
        runCatching {
            File(dir, META_FILE).writeText(json.encodeToString(list))
        }
    }
}
