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
        if (!file.exists() || file.length() <= 0L) {
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
    ): Boolean = startInternal(
        tag = tag,
        tempFile = null,
        configureDataSource = { mp -> mp.setDataSource(context, uri) },
        onPrepared = onPrepared,
        onCompletion = onCompletion,
        onError = onError,
    )

    fun playUrl(
        url: String,
        tag: String,
        onPrepared: (() -> Unit)? = null,
        onCompletion: () -> Unit = {},
        onError: (String) -> Unit = {},
    ): Boolean = startInternal(
        tag = tag,
        tempFile = null,
        configureDataSource = { mp -> mp.setDataSource(url) },
        onPrepared = onPrepared,
        onCompletion = onCompletion,
        onError = onError,
    )

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
        val mp = MediaPlayer()
        synchronized(lock) {
            priorCleanup = releaseLocked()
            myGen = generation.incrementAndGet()
            activePlayer = mp
            activeTag = tag
            activeTempFile = tempFile
            activeCleanupCallback = null
        }
        priorCleanup?.invoke()

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
                    runCatching { prepared.release() }
                    return@setOnPreparedListener
                }
                val started = runCatching {
                    prepared.start()
                    true
                }.getOrDefault(false)
                if (started) {
                    onPrepared?.invoke()
                } else {
                    stop(tag)
                    onError("MediaPlayer failed to start playback")
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
                    onCompletion()
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
                    onError("MediaPlayerError: what=$what extra=$extra")
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
                    runCatching { mp.release() }
                }
            }
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
            runCatching {
                if (old.isPlaying) old.stop()
            }
            runCatching { old.reset() }
            runCatching { old.release() }
        }
        if (oldTemp != null) {
            runCatching { oldTemp.delete() }
        }
        return oldCleanup
    }
}
