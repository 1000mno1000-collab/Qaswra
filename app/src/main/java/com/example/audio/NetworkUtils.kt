package com.example.audio

import android.content.Context

/**
 * Lightweight connectivity check used exclusively before invoking online cloud AI,
 * Neural TTS, web article extraction, and Archive.org sound search.
 * Core audio/video DSP tools never invoke this and run 100% offline.
 */
object NetworkUtils {

    fun isInternetAvailable(context: Context): Boolean {
        return OnlineGuard.isOnline(context)
    }
}
