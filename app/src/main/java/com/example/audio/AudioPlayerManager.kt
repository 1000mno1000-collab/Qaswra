package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralized exclusive audio preview player manager.
 * Guarantees at most ONE preview [MediaPlayer] instance is active at any time across all tools,
 * immediately stopping and releasing prior instances to prevent audio overlap or memory leaks,
 * and always prepares asynchronously so the Main Thread is never blocked.
 */
object AudioPlayerManager {
    private val lock = Any()
    private val generation = AtomicLong(0L)

    @Volatile private var activePlayer: MediaPlayer? = null
    @Volatile private var activeTag: String? = null
    @Volatile private var activeTempFile: File? = null
    @Volatile private var activeCleanupCallback: (() -> Unit)? = null

    /**
     * Hook invoked right before any preview starts (e.g., to pause the main StudioAudioPlayer)
     * so two audio tracks never play over each other.
     */
    @Volatile var onBeforeStartExclusive: (() -> Unit)? = null

    fun isPlaying(tag: String? = null): Boolean = synchronized(lock) {
        if (tag != null && activeTag != tag) return@synchronized false
        runCatching { activePlayer?.isPlaying == true }.getOrDefault(false)
    }

    fun currentTag(): String? = activeTag

    fun playFile(
        file: File,
        tag: String,
        deleteFileOnRelease: Boolean = false,
        onPrepared: (() -> Unit)? = null,
        onCompletion: () -> Unit = {},
        onError: (String) -> Unit = {},
    ): Boolean {
        if (file.absolutePath.isBlank() || !file.exists() || file.length() <= 0L) {
            onError("Preview audio file is missing or empty")
            return false
        }
        return startInternal(
            tag = tag,
            tempFile = if (deleteFileOnRelease) file else null,
            configureDataSource = { mp -> mp.setDataSource(file.absolutePath) },
            onPrepared = onPrepared,
            onCompletion = onCompletion,
            onError = onError,
        )
    }

    fun playUri(
        context: Context,
        uri: Uri,
        tag: String,
        onPrepared: (() -> Unit)? = null,
        onCompletion: () -> Unit = {},
        onError: (String) -> Unit = {},
    ): Boolean {
        if (uri.toString().isBlank()) {
            onError("Preview audio URI is blank")
            return false
        }
        return startInternal(
            tag = tag,
            tempFile = null,
            configureDataSource = { mp -> mp.setDataSource(context, uri) },
            onPrepared = onPrepared,
            onCompletion = onCompletion,
            onError = onError,
        )
    }

    fun playUrl(
        url: String,
        tag: String,
        onPrepared: (() -> Unit)? = null,
        onCompletion: () -> Unit = {},
        onError: (String) -> Unit = {},
    ): Boolean {
        if (url.isBlank()) {
            onError("Preview audio URL is blank")
            return false
        }
        return startInternal(
            tag = tag,
            tempFile = null,
            configureDataSource = { mp -> mp.setDataSource(url) },
            onPrepared = onPrepared,
            onCompletion = onCompletion,
            onError = onError,
        )
    }

    fun stop(tag: String? = null) {
        val cleanup: (() -> Unit)?
        synchronized(lock) {
            if (tag != null && activeTag != tag) return
            cleanup = releaseLocked()
        }
        cleanup?.invoke()
    }

    fun releaseAll() {
        stop(tag = null)
    }

    private fun startInternal(
        tag: String,
        tempFile: File?,
        configureDataSource: (MediaPlayer) -> Unit,
        onPrepared: (() -> Unit)?,
        onCompletion: () -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        runCatching { onBeforeStartExclusive?.invoke() }

        val priorCleanup: (() -> Unit)?
        val myGen: Long
        val mp: MediaPlayer = try {
            MediaPlayer()
        } catch (t: Throwable) {
            onError(t.localizedMessage ?: "MediaPlayer initialization failed")
            return false
        }
        synchronized(lock) {
            priorCleanup = releaseLocked()
            myGen = generation.incrementAndGet()
            activePlayer = mp
            activeTag = tag
            activeTempFile = tempFile
            activeCleanupCallback = null
        }
        try {
            priorCleanup?.invoke()
        } catch (_: Throwable) {}

        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            configureDataSource(mp)
            mp.setOnPreparedListener { prepared ->
                val valid = synchronized(lock) { activePlayer === prepared && generation.get() == myGen }
                if (!valid) {
                    try { prepared.release() } catch (_: Throwable) {}
                    return@setOnPreparedListener
                }
                val started = try {
                    prepared.start()
                    true
                } catch (_: Throwable) {
                    false
                }
                if (started) {
                    try { onPrepared?.invoke() } catch (_: Throwable) {}
                } else {
                    stop(tag)
                    try { onError("MediaPlayer failed to start playback") } catch (_: Throwable) {}
                }
            }
            mp.setOnCompletionListener { completed ->
                val wasCurrent = synchronized(lock) {
                    if (activePlayer === completed && generation.get() == myGen) {
                        releaseLocked()
                        true
                    } else {
                        false
                    }
                }
                if (wasCurrent) {
                    try { onCompletion() } catch (_: Throwable) {}
                }
            }
            mp.setOnErrorListener { errored, what, extra ->
                val wasCurrent = synchronized(lock) {
                    if (activePlayer === errored && generation.get() == myGen) {
                        releaseLocked()
                        true
                    } else {
                        false
                    }
                }
                if (wasCurrent) {
                    try { onError("MediaPlayerError: what=$what extra=$extra") } catch (_: Throwable) {}
                }
                true
            }
            mp.prepareAsync()
            true
        } catch (t: Throwable) {
            synchronized(lock) {
                if (activePlayer === mp && generation.get() == myGen) {
                    releaseLocked()
                } else {
                    try { mp.release() } catch (_: Throwable) {}
                }
            }
            onError(t.localizedMessage ?: "MediaPlayer failed to prepare")
            false
        }
    }

    private fun releaseLocked(): (() -> Unit)? {
        generation.incrementAndGet()
        val old = activePlayer
        val oldTemp = activeTempFile
        val oldCleanup = activeCleanupCallback
        activePlayer = null
        activeTag = null
        activeTempFile = null
        activeCleanupCallback = null
        if (old != null) {
            try {
                if (old.isPlaying) old.stop()
            } catch (_: Throwable) {}
            try { old.reset() } catch (_: Throwable) {}
            try { old.release() } catch (_: Throwable) {}
        }
        if (oldTemp != null) {
            try { oldTemp.delete() } catch (_: Throwable) {}
        }
        return oldCleanup
    }
}
