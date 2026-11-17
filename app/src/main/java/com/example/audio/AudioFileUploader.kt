package com.example.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
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

/**
 * Reliable background URI-to-cacheDir audio uploader on [Dispatchers.IO].
 * Supports both tiny KB voice notes and large multi-MB recordings with accurate byte-level
 * percentage calculation for live [androidx.compose.material3.LinearProgressIndicator] reporting.
 */
object AudioFileUploader {

    suspend fun copyAudioUriToCache(
        context: Context,
        uri: Uri,
        onProgress: (UploadProgressInfo) -> Unit,
    ): UploadedCacheFile? = withContext(Dispatchers.IO) {
        val displayName = resolveDisplayName(context, uri)
        val totalBytes = queryContentSize(context, uri)
        val ext = resolveExtension(context, uri, displayName)

        onProgress(
            UploadProgressInfo(
                fraction = 0.01f,
                percent = 1,
                bytesCopied = 0L,
                totalBytes = totalBytes,
                fileName = displayName,
            ),
        )

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        val uploadDir = File(context.cacheDir, "audio_upload_cache").apply { mkdirs() }
        val destFile = File(uploadDir, "upload_${UUID.randomUUID()}.$ext")

        val resolver = context.contentResolver
        var pfd: ParcelFileDescriptor? = null
        val inputStream = if (uri.scheme.equals("file", ignoreCase = true)) {
            val direct = uri.path?.let { File(it) }
            if (direct != null && direct.exists() && direct.canRead()) {
                direct.inputStream()
            } else {
                null
            }
        } else {
            resolver.openInputStream(uri) ?: run {
                pfd = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
                pfd?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
            }
        }

        if (inputStream == null) {
            runCatching { pfd?.close() }
            return@withContext null
        }

        // Smaller chunk for KB files so progress moves smoothly; larger chunk for MB files for speed
        val bufferSize = if (totalBytes in 1L..262_144L) 4096 else 32_768
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
                destFile.outputStream().buffered(bufferSize).use { out ->
                    val buf = ByteArray(bufferSize)
                    var lastEmittedMs = 0L
                    var lastEmittedPct = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        copiedBytes += read

                        val fraction = if (effectiveTotal > 0L) {
                            (copiedBytes.toDouble() / effectiveTotal.toDouble()).toFloat().coerceIn(0.01f, 1f)
                        } else {
                            (1f - 1f / (1f + copiedBytes / 524_288f)).coerceIn(0.05f, 0.96f)
                        }
                        val pct = (fraction * 100f).roundToInt().coerceIn(1, 100)
                        val now = System.currentTimeMillis()
                        if (pct != lastEmittedPct || now - lastEmittedMs >= 35L || (effectiveTotal > 0L && copiedBytes >= effectiveTotal)) {
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
                null
            } else {
                val finalTotal = if (effectiveTotal > 0L) effectiveTotal else copiedBytes
                onProgress(
                    UploadProgressInfo(
                        fraction = 1f,
                        percent = 100,
                        bytesCopied = copiedBytes,
                        totalBytes = finalTotal,
                        fileName = displayName,
                    ),
                )
                UploadedCacheFile(
                    file = destFile,
                    displayName = displayName,
                    bytesCopied = copiedBytes,
                    totalBytes = finalTotal,
                )
            }
        } catch (t: Throwable) {
            runCatching { destFile.delete() }
            if (t is kotlinx.coroutines.CancellationException) throw t
            null
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

    private fun resolveExtension(context: Context, uri: Uri, displayName: String): String {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val extFromMime = if (!mime.isNullOrBlank()) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        } else {
            null
        }
        val fromName = displayName.substringAfterLast('.', "")
        return (extFromMime?.takeIf { it.isNotBlank() }
            ?: fromName.takeIf { it.length in 2..5 }
            ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }
            ?: "bin").lowercase()
    }
}
