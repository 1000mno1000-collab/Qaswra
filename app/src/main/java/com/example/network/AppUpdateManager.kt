package com.example.network

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Immutable
import com.example.audio.network.NetworkFetchOutcome
import com.example.audio.network.NetworkImmunityManager
import com.example.util.SafeTextGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class AppUpdateCheckResult(
    val currentVersion: String,
    val latestVersion: String,
    val isUpdateAvailable: Boolean,
    val releaseNotes: String,
    val releasePageUrl: String,
)

sealed interface AppUpdateOutcome {
    data class Checked(val info: AppUpdateCheckResult) : AppUpdateOutcome
    data class Offline(val message: String) : AppUpdateOutcome
    data class Error(val message: String) : AppUpdateOutcome
}

/**
 * Checks release metadata and routes updates exclusively through Google Play Store
 * (without sideloading APK files, complying with Google Play Device & Network Abuse policy).
 */
object AppUpdateManager {
    private const val GITHUB_RELEASES_API =
        "https://api.github.com/repos/1000mno1000-collab/Qaswra/releases/latest"

    fun playStoreWebUrl(context: Context): String =
        "https://play.google.com/store/apps/details?id=${context.packageName}"

    fun getCurrentAppVersionName(context: Context): String {
        return runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.versionName?.takeIf { it.isNotBlank() } ?: "1.0"
        }.getOrDefault("1.0")
    }

    suspend fun checkLatestRelease(context: Context): AppUpdateOutcome = withContext(Dispatchers.IO) {
        val currentVersion = getCurrentAppVersionName(context)
        val storeUrl = playStoreWebUrl(context)
        if (!NetworkImmunityManager.pingReachable(context)) {
            return@withContext AppUpdateOutcome.Offline("No verified internet connection")
        }

        try {
            val outcome = NetworkImmunityManager.fetchTextWithFallback(
                urls = listOf(GITHUB_RELEASES_API),
                context = context,
                headers = mapOf("Accept" to "application/vnd.github+json"),
                timeoutMs = NetworkImmunityManager.DEFAULT_NETWORK_TIMEOUT_MS,
                providerLabel = "ReleaseNotesCheck",
            )
            when (outcome) {
                is NetworkFetchOutcome.Offline -> AppUpdateOutcome.Offline(outcome.reason)
                is NetworkFetchOutcome.Failure -> {
                    if (outcome.message.contains("404")) {
                        AppUpdateOutcome.Checked(
                            AppUpdateCheckResult(
                                currentVersion = currentVersion,
                                latestVersion = currentVersion,
                                isUpdateAvailable = false,
                                releaseNotes = "",
                                releasePageUrl = storeUrl,
                            ),
                        )
                    } else {
                        AppUpdateOutcome.Error(outcome.message)
                    }
                }
                is NetworkFetchOutcome.Success -> {
                    val json = SafeTextGuard.safeParseJsonObject(outcome.value.body)
                        ?: return@withContext AppUpdateOutcome.Error("Malformed update response payload")
                    val tagRaw = json.optString("tag_name").trim()
                    val latestClean = tagRaw.removePrefix("v").removePrefix("V").ifBlank { currentVersion }
                    val body = json.optString("body").trim()
                    val isNewer = compareSemanticVersions(latestClean, currentVersion) > 0
                    AppUpdateOutcome.Checked(
                        AppUpdateCheckResult(
                            currentVersion = currentVersion,
                            latestVersion = latestClean,
                            isUpdateAvailable = isNewer,
                            releaseNotes = body,
                            releasePageUrl = storeUrl,
                        ),
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AppUpdateOutcome.Error(t.localizedMessage ?: "Failed checking updates")
        }
    }

    fun openUpdateLinkInBrowser(context: Context, info: AppUpdateCheckResult) {
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(marketIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // Fallback to HTTPS Google Play Store web listing
        } catch (_: Throwable) {
            // Fallback to HTTPS Google Play Store web listing
        }
        val target = NetworkImmunityManager.enforceHttps(
            info.releasePageUrl.ifBlank { playStoreWebUrl(context) },
        )
        runCatching {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }

    internal fun compareSemanticVersions(a: String, b: String): Int {
        val pa = a.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        val pb = b.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
