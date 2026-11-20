package com.example.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

data class UploadProgressInfo(
    val fraction: Float,
    val percent: Int,
    val bytesCopied: Long,
    val totalBytes: Long,
    val fileName: String,
)

data class UploadedCacheFile(
    val file: File,
    val displayName: String,
    val bytesCopied: Long,
    val totalBytes: Long,
)

sealed interface AudioUploadOutcome {
    data class Success(val uploaded: UploadedCacheFile) : AudioUploadOutcome
    data class Failure(val errorMessage: String) : AudioUploadOutcome
}

/**
 * Streams URI data through a 64 KB buffer into the staged cache directory, without loading the
 * source file into memory. Prefers a file descriptor for content URIs and reports byte progress
 * from the provider's actual size when available.
 */
object AudioFileUploader {
    private const val COPY_BUFFER_SIZE = 65536

    suspend fun copyAudioUriToCache(
        context: Context,
        uri: Uri,
        onProgress: (UploadProgressInfo) -> Unit,
    ): UploadedCacheFile? {
        return when (val outcome = copyAudioUriToCacheDetailed(context, uri, onProgress)) {
            is AudioUploadOutcome.Success -> outcome.uploaded
            is AudioUploadOutcome.Failure -> null
        }
    }

    suspend fun copyAudioUriToCacheDetailed(
        context: Context,
        uri: Uri,
        onProgress: (UploadProgressInfo) -> Unit,
    ): AudioUploadOutcome = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        // Take persistable read permission immediately before opening any stream
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        val displayName = resolveDisplayName(context, uri)
        val totalBytes = queryContentSize(context, uri)
        val initialExt = resolveExtension(context, uri, displayName)

        onProgress(
            UploadProgressInfo(
                fraction = 0f,
                percent = 0,
                bytesCopied = 0L,
                totalBytes = totalBytes,
                fileName = displayName,
            ),
        )

        val uploadDir = File(context.cacheDir, "audio_upload_cache").apply { mkdirs() }
        var destFile = File(uploadDir, "upload_${UUID.randomUUID()}.$initialExt")

        var pfd: ParcelFileDescriptor? = null
        var openError: Throwable? = null
        val inputStream = if (uri.scheme.equals("file", ignoreCase = true)) {
            val direct = uri.path?.let { File(it) }
            if (direct != null && direct.exists() && direct.canRead()) {
                runCatching { direct.inputStream() }
                    .onFailure { openError = it }
                    .getOrNull()
            } else {
                openError = IllegalStateException(
                    "File path is missing or unreadable (${uri.path ?: uri.toString()})",
                )
                null
            }
        } else {
            val fdRes = runCatching { resolver.openFileDescriptor(uri, "r") }
            pfd = fdRes.getOrNull()
            if (pfd == null) openError = fdRes.exceptionOrNull()
            pfd?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
                ?: runCatching { resolver.openInputStream(uri) }
                    .onFailure { if (openError == null) openError = it }
                    .getOrNull()
        }

        if (inputStream == null) {
            runCatching { pfd?.close() }
            val reason = openError?.let { "${it.javaClass.simpleName}: ${it.message}" }
                ?: "SecurityException: contentResolver.openInputStream returned null for $uri"
            return@withContext AudioUploadOutcome.Failure(reason)
        }

        val effectiveTotal = if (totalBytes > 0L) {
            totalBytes
        } else if (uri.scheme.equals("file", ignoreCase = true)) {
            uri.path?.let { File(it).length() } ?: -1L
        } else {
            -1L
        }

        return@withContext try {
            var copiedBytes = 0L
            inputStream.use { input ->
                java.io.BufferedOutputStream(java.io.FileOutputStream(destFile), COPY_BUFFER_SIZE).use { out ->
                    val buf = ByteArray(COPY_BUFFER_SIZE)
                    var lastEmittedMs = 0L
                    var lastEmittedPct = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buf)
                        if (read < 0) break
                        if (read == 0) continue
                        out.write(buf, 0, read)
                        copiedBytes += read

                        val fraction = if (effectiveTotal > 0L) {
                            (copiedBytes.toDouble() / effectiveTotal.toDouble()).toFloat().coerceIn(0f, 0.99f)
                        } else {
                            0f
                        }
                        val pct = if (effectiveTotal > 0L) {
                            (fraction * 100f).roundToInt().coerceIn(0, 99)
                        } else {
                            -1
                        }
                        val now = System.currentTimeMillis()
                        if ((pct >= 0 && pct != lastEmittedPct && now - lastEmittedMs >= 35L) ||
                            now - lastEmittedMs >= 90L
                        ) {
                            lastEmittedPct = pct
                            lastEmittedMs = now
                            onProgress(
                                UploadProgressInfo(
                                    fraction = fraction,
                                    percent = pct,
                                    bytesCopied = copiedBytes,
                                    totalBytes = effectiveTotal,
                                    fileName = displayName,
                                ),
                            )
                        }
                    }
                    out.flush()
                }
            }

            if (copiedBytes <= 0L || !destFile.exists() || destFile.length() <= 0L) {
                runCatching { destFile.delete() }
                AudioUploadOutcome.Failure("EOFException: Copied 0 bytes from $displayName")
            } else {
                // If MIME/name gave a generic .bin extension, inspect audio magic header and rename cleanly
                if (initialExt == "bin") {
                    val sniffedExt = sniffAudioExtension(destFile)
                    if (sniffedExt != null) {
                        val renamed = File(uploadDir, "${destFile.nameWithoutExtension}.$sniffedExt")
                        if (destFile.renameTo(renamed)) {
                            destFile = renamed
                        }
                    }
                }

                val finalTotal = copiedBytes
                com.example.audio.cache.SmartCacheManager.pruneManagedBuckets(context)
                onProgress(
                    UploadProgressInfo(
                        fraction = 1f,
                        percent = 100,
                        bytesCopied = copiedBytes,
                        totalBytes = finalTotal,
                        fileName = displayName,
                    ),
                )
                AudioUploadOutcome.Success(
                    UploadedCacheFile(
                        file = destFile,
                        displayName = displayName,
                        bytesCopied = copiedBytes,
                        totalBytes = finalTotal,
                    ),
                )
            }
        } catch (t: Throwable) {
            runCatching { destFile.delete() }
            if (t is CancellationException) throw t
            AudioUploadOutcome.Failure("${t.javaClass.simpleName}: ${t.message ?: "Failed copying URI to cache"}")
        } finally {
            runCatching { pfd?.close() }
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return "${"%.1f".format(kb)} KB"
        val mb = kb / 1024.0
        return "${"%.2f".format(mb)} MB"
    }

    fun resolveDisplayName(context: Context, uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "audio_file"
    }

    fun queryContentSize(context: Context, uri: Uri): Long {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val len = uri.path?.let { File(it).length() } ?: -1L
            if (len > 0L) return len
        }
        val colSize = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
        if (colSize > 0L) return colSize

        val afdSize = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd -> afd.length } ?: -1L
        }.getOrDefault(-1L)
        if (afdSize > 0L) return afdSize

        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> pfd.statSize } ?: -1L
        }.getOrDefault(-1L)
    }

    private val KNOWN_MEDIA_EXTENSIONS = setOf(
        "mp3", "wav", "m4a", "aac", "ogg", "oga", "opus", "flac", "wma", "amr", "3gp", "mp4", "mkv", "webm", "aiff",
    )

    private fun resolveExtension(context: Context, uri: Uri, displayName: String): String {
        val fromName = displayName.substringAfterLast('.', "").lowercase()
        if (fromName in KNOWN_MEDIA_EXTENSIONS) return fromName

        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val extFromMime = if (!mime.isNullOrBlank()) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase()
        } else {
            null
        }
        if (extFromMime != null && extFromMime in KNOWN_MEDIA_EXTENSIONS) return extFromMime

        val fromPath = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (fromPath in KNOWN_MEDIA_EXTENSIONS) return fromPath

        return (extFromMime?.takeIf { it.isNotBlank() && it != "bin" }
            ?: fromName.takeIf { it.length in 2..5 }
            ?: "bin").lowercase()
    }

    private fun sniffAudioExtension(file: File): String? {
        return runCatching {
            val head = ByteArray(16)
            val n = FileInputStream(file).use { it.read(head) }
            if (n < 4) return@runCatching null
            val ascii4 = String(head, 0, 4, Charsets.US_ASCII)
            when {
                ascii4 == "RIFF" -> "wav"
                ascii4 == "OggS" -> "ogg"
                ascii4 == "fLaC" -> "flac"
                ascii4.startsWith("ID3") -> "mp3"
                (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 -> "mp3"
                n >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> "m4a"
                else -> null
            }
        }.getOrNull()
    }
}
