package com.example.audio

import com.example.diagnostics.FfmpegKitBridge
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Centralized single-player coordinator across the entire Sonix app.
 * Guarantees that at most ONE audio stream ([StudioAudioPlayer] or [AudioPlayerManager])
 * is active at any moment, preventing echo overlap, jitter, or competing AudioTrack/MediaPlayer instances.
 */
object AudioPlaybackController {

    private val lock = Any()

    @Volatile
    private var studioPlayer: StudioAudioPlayer? = null

    fun registerStudioPlayer(player: StudioAudioPlayer) {
        synchronized(lock) {
            studioPlayer = player
        }
    }

    fun unregisterStudioPlayer(player: StudioAudioPlayer) {
        synchronized(lock) {
            if (studioPlayer === player) {
                studioPlayer = null
            }
        }
    }

    fun onBeforeStudioPlayerPlay(player: StudioAudioPlayer) {
        synchronized(lock) {
            studioPlayer = player
        }
        FfmpegKitBridge.cancelActiveSessions()
        AudioPlayerManager.releaseAll()
    }

    fun onBeforePreviewManagerPlay(
        @Suppress("UNUSED_PARAMETER") tag: String,
    ) {
        val activeStudio = synchronized(lock) { studioPlayer }
        if (activeStudio != null) {
            runCatching {
                if (activeStudio.isCurrentlyPlaying()) {
                    activeStudio.pause()
                }
            }
        }
    }

    fun stopAllExclusive() {
        FfmpegKitBridge.cancelActiveSessions()
        AudioPlayerManager.releaseAll()
        val activeStudio = synchronized(lock) { studioPlayer }
        if (activeStudio != null) {
            runCatching {
                if (activeStudio.isCurrentlyPlaying()) {
                    activeStudio.pause()
                }
            }
        }
    }

    /**
     * Builds a safe non-clipping FFmpeg `aecho` filter stage guarded with `alimiter=limit=0.95`.
     */
    fun buildSafeEchoFilter(
        delayMs: Float = 55f,
        decay: Float = 0.30f,
    ): String {
        val safeDelay = delayMs.roundToInt().coerceIn(15, 600)
        val safeDecay = String.format(Locale.US, "%.2f", decay.coerceIn(0.10f, 0.45f))
        return "aecho=0.8:0.85:$safeDelay:$safeDecay,alimiter=limit=0.95"
    }
}
