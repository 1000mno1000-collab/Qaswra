package com.example.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.example.model.DeviceAudioItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaStoreAudioRepository {

    suspend fun queryDeviceAudioFiles(context: Context): List<DeviceAudioItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DeviceAudioItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
            }
        }.toTypedArray()

        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val bucketCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                } else {
                    -1
                }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val displayName = (if (nameCol >= 0) cursor.getString(nameCol) else null)
                        ?.takeIf { it.isNotBlank() }
                        ?: (if (titleCol >= 0) cursor.getString(titleCol) else null)
                        ?: "Audio_$id"
                    val durationMs = if (durCol >= 0) cursor.getLong(durCol).coerceAtLeast(0L) else 0L
                    val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol).coerceAtLeast(0L) else 0L
                    val mimeType = (if (mimeCol >= 0) cursor.getString(mimeCol) else null) ?: "audio/*"
                    val dateAdded = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val rawPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val bucketName = (if (bucketCol >= 0) cursor.getString(bucketCol) else null)
                        ?.takeIf { it.isNotBlank() }
                        ?: rawPath?.let { File(it).parentFile?.name }?.takeIf { it.isNotBlank() }
                        ?: "Audio"

                    val extFromName = displayName.substringAfterLast('.', "").uppercase()
                    val ext = when {
                        extFromName.length in 2..5 -> extFromName
                        mimeType.contains("mpeg", true) || mimeType.contains("mp3", true) -> "MP3"
                        mimeType.contains("wav", true) -> "WAV"
                        mimeType.contains("flac", true) -> "FLAC"
                        mimeType.contains("aac", true) -> "AAC"
                        mimeType.contains("mp4", true) || mimeType.contains("m4a", true) -> "M4A"
                        mimeType.contains("ogg", true) -> "OGG"
                        else -> "AUDIO"
                    }

                    if (sizeBytes > 0L || durationMs > 0L) {
                        results.add(
                            DeviceAudioItem(
                                id = id,
                                uriString = contentUri.toString(),
                                displayName = displayName,
                                durationMs = durationMs,
                                sizeBytes = sizeBytes,
                                extension = ext,
                                mimeType = mimeType,
                                folderName = bucketName,
                                dateAddedSec = dateAdded,
                            ),
                        )
                    }
                }
            }
        }
        results
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 KB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        } else {
            "${kb.toLong().coerceAtLeast(1L)} KB"
        }
    }
}
