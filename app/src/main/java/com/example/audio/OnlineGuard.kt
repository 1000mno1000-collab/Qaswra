package com.example.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.i18n.t
import com.example.model.AppLanguage

/**
 * Strict online connectivity guard for all 100% cloud features
 * (Neural TTS, ElevenLabs, Jina article reader, Pollinations AI, Gemini/Archive.org).
 * Requires BOTH [NetworkCapabilities.NET_CAPABILITY_INTERNET] and
 * [NetworkCapabilities.NET_CAPABILITY_VALIDATED] so dead/captive/offline networks
 * fail fast instead of hanging or falling back to offline robotic engines.
 */
object OnlineGuard {

    fun isOnline(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            hasInternet && isValidated
        }.getOrDefault(false)
    }

    fun offlineErrorMessage(language: AppLanguage): String {
        return t(language, "online_guard_offline_error")
    }

    /**
     * Returns `null` if validated internet is active, or a localized error message
     * instructing the user that this feature works 100% online only and does not work offline.
     */
    fun requireOnlineOrError(context: Context, language: AppLanguage): String? {
        return if (isOnline(context)) null else offlineErrorMessage(language)
    }
}
