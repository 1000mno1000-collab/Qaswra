package com.example.audio.cache

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Smart CacheDir Cleaner & Path Sanitizer:
 * - Caps managed temp subdirectories to the newest [MAX_FILES_PER_BUCKET] (10) items so cache storage never bloats.
 * - Cleans old temp artifacts on app startup, after heavy operations, and when app memory/task closes.
 * - Stages files under clean ASCII UUID paths inside `cacheDir/staged_inputs` so Arabic characters, spaces,
 *   parentheses, or special symbols never break FFmpegKit command parsing.
 */
object SmartCacheManager {
    private const val TAG = "SmartCacheManager"
    const val MAX_FILES_PER_BUCKET = 10

    private val MANAGED_SUBDIRS = listOf(
        "stream_ops",
        "silence_stream",
        "reels_audio_fx",
        "free_emotional_tts",
        "edge_tts",
        "exports",
        "audio_upload_cache",
        "staged_inputs",
        "archive_sfx",
        "qa_bot",
        "undo_cache",
        "pro_fx",
    )

    fun pruneManagedBuckets(
        context: Context,
        keepLastCount: Int = MAX_FILES_PER_BUCKET,
    ): Int {
        val safeKeep = keepLastCount.coerceIn(1, 20)
        var deletedCount = 0
        for (bucket in MANAGED_SUBDIRS) {
            val dir = File(context.cacheDir, bucket)
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?: continue
            if (files.size > safeKeep) {
                for (i in safeKeep until files.size) {
                    if (runCatching { files[i].delete() }.getOrDefault(false)) {
                        deletedCount++
                    }
                }
            }
        }
        return deletedCount
    }

    /**
     * Aggressive session-close / low-memory purge keeping at most [keepCount] newest artifacts
     * across cache buckets (excluding persistent `recent_edits_cache`).
     */
    fun trimSessionCache(context: Context, keepCount: Int = 4): Int {
        val cleaned = pruneManagedBuckets(context, keepLastCount = keepCount)
        if (cleaned > 0) {
            Log.i(TAG, "Pruned $cleaned temp audio files from cacheDir.")
        }
        return cleaned
    }

    /**
     * Measures managed temporary cache usage in bytes.
     */
    fun calculateManagedCacheBytes(context: Context): Long {
        var total = 0L
        for (bucket in MANAGED_SUBDIRS) {
            val dir = File(context.cacheDir, bucket)
            if (!dir.exists() || !dir.isDirectory) continue
            dir.listFiles()?.forEach { f ->
                if (f.isFile) total += f.length()
            }
        }
        return total
    }

    /**
     * Sanitizes an arbitrary filename into pure ASCII letters, digits, underscores, and dots
     * to guarantee shell & FFmpegKit safety.
     */
    fun sanitizeAsciiFilename(rawName: String, fallbackExt: String = "wav"): String {
        val ext = rawName.substringAfterLast('.', fallbackExt)
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .take(8)
            .ifBlank { fallbackExt }
        val base = rawName.substringBeforeLast('.')
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(24)
            .ifBlank { "track" }
        return "${base}_${UUID.randomUUID().toString().take(8)}.$ext"
    }

    /**
     * Copies any local [File] whose path has Arabic characters, spaces, quotes, or brackets into a
     * pristine ASCII path in `cacheDir/staged_inputs/`. Returns the original file unchanged if already safe.
     */
    suspend fun ensureSafeFfmpegInputFile(
        context: Context,
        inputFile: File,
    ): File = withContext(Dispatchers.IO) {
        val path = inputFile.absolutePath
        val isAsciiSafe = path.all { ch ->
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                ch == '/' || ch == '_' || ch == '-' || ch == '.'
        }
        if (isAsciiSafe) return@withContext inputFile

        val stagedDir = File(context.cacheDir, "staged_inputs").apply { mkdirs() }
        val ext = inputFile.extension
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .take(8)
            .ifBlank { "audio" }
        val safeTarget = File(stagedDir, "in_${UUID.randomUUID()}.$ext")
        inputFile.inputStream().buffered(65_536).use { input ->
            safeTarget.outputStream().buffered(65_536).use { out ->
                input.copyTo(out, 65_536)
            }
        }
        pruneManagedBuckets(context)
        safeTarget
    }

    /**
     * Copies a content [Uri] safely to a clean ASCII path in `cacheDir/staged_inputs/` using a 64 KB buffer.
     */
    suspend fun stageUriToCleanCacheFile(
        context: Context,
        uri: Uri,
        ext: String = "tmp",
    ): File? = withContext(Dispatchers.IO) {
        val cleanExt = ext.lowercase().replace(Regex("[^a-z0-9]"), "").take(8).ifBlank { "tmp" }
        val stagedDir = File(context.cacheDir, "staged_inputs").apply { mkdirs() }
        val target = File(stagedDir, "uri_${UUID.randomUUID()}.$cleanExt")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered(65_536).use { out ->
                    input.copyTo(out, 65_536)
                }
                target.exists() && target.length() > 0L
            } ?: false
        }.getOrDefault(false)
        if (!copied) {
            runCatching { target.delete() }
            null
        } else {
            pruneManagedBuckets(context)
            target
        }
    }
}
