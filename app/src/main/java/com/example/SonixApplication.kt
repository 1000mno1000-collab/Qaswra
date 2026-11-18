package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.util.Log
import com.example.audio.cache.SmartCacheManager
import com.example.data.RecentEditsCacheManager
import com.example.diagnostics.AppDiagnosticLog
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.persistentCacheSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

data class StartupErrorReport(
    val threadName: String,
    val message: String,
    val stackTrace: String,
)

object SonixCrashGuard {
    const val EXTRA_CRASH_THREAD = "sonix_crash_thread"
    const val EXTRA_CRASH_MESSAGE = "sonix_crash_message"
    const val EXTRA_CRASH_TRACE = "sonix_crash_trace"
    const val EXTRA_ASK_RESTART_CONSENT = "sonix_ask_restart_consent"
    const val EXTRA_DIAGNOSTIC_ID = "sonix_diagnostic_id"

    private const val PREFS_NAME = "sonix_crash_guard"
    private const val KEY_LAST_RELAUNCH_MS = "last_relaunch_ms"
    private const val RELAUNCH_COOLDOWN_MS = 3500L

    private val handlerInstalled = AtomicBoolean(false)
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val _uncaughtError = MutableStateFlow<StartupErrorReport?>(null)
    val uncaughtError: StateFlow<StartupErrorReport?> = _uncaughtError.asStateFlow()

    fun reportError(threadName: String, throwable: Throwable) {
        val msg = throwable.localizedMessage?.takeIf { it.isNotBlank() }
            ?: throwable::class.java.simpleName
        val trace = Log.getStackTraceString(throwable).takeIf { it.isNotBlank() }
            ?: throwable.toString()
        AppDiagnosticLog.logCritical(
            toolName = threadName,
            titleAr = "خطأ غير متوقع في الواجهة الرئيسية",
            titleEn = "Unexpected Main Thread Error",
            causeAr = msg,
            causeEn = msg,
            errorCode = throwable.javaClass.simpleName,
            throwable = throwable,
            requestUserRestartConsent = true,
        )
        _uncaughtError.value = StartupErrorReport(
            threadName = threadName,
            message = msg,
            stackTrace = trace,
        )
    }

    fun reportError(report: StartupErrorReport) {
        _uncaughtError.value = report
    }

    fun clearError() {
        _uncaughtError.value = null
    }

    private fun parseFirebaseOptionsFromAssetsOrNull(context: Context): FirebaseOptions? {
        return runCatching {
            val rawJson = context.assets.open("google-services.json").bufferedReader().use { it.readText() }
            val root = JSONObject(rawJson)
            val projectInfo = root.optJSONObject("project_info") ?: return@runCatching null
            val projectId = projectInfo.optString("project_id").takeIf { it.isNotBlank() } ?: return@runCatching null
            val projectNumber = projectInfo.optString("project_number").takeIf { it.isNotBlank() }
            val storageBucket = projectInfo.optString("storage_bucket").takeIf { it.isNotBlank() }

            val clients = root.optJSONArray("client") ?: return@runCatching null
            var matchedClient: JSONObject? = null
            for (i in 0 until clients.length()) {
                val c = clients.optJSONObject(i) ?: continue
                val pkg = c.optJSONObject("client_info")
                    ?.optJSONObject("android_client_info")
                    ?.optString("package_name")
                if (pkg == context.packageName) {
                    matchedClient = c
                    break
                }
            }
            val chosenClient = matchedClient ?: clients.optJSONObject(0) ?: return@runCatching null
            val appId = chosenClient.optJSONObject("client_info")
                ?.optString("mobilesdk_app_id")
                ?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val apiKey = chosenClient.optJSONArray("api_key")
                ?.optJSONObject(0)
                ?.optString("current_key")
                ?.takeIf { it.isNotBlank() } ?: return@runCatching null

            FirebaseOptions.Builder()
                .setApplicationId(appId)
                .setApiKey(apiKey)
                .setProjectId(projectId)
                .apply {
                    if (!projectNumber.isNullOrBlank()) setGcmSenderId(projectNumber)
                    if (!storageBucket.isNullOrBlank()) setStorageBucket(storageBucket)
                }
                .build()
        }.getOrNull()
    }

    fun buildFallbackFirebaseOptions(): FirebaseOptions? {
        val apiKey = BuildConfig.FIREBASE_API_KEY
            .trim()
            .takeIf { it.startsWith("AIza") }
            ?: BuildConfig.GEMINI_API_KEY.trim().takeIf { it.startsWith("AIza") }
            ?: "AIzaSyAewWAxeUSQFEtUoMGuVFCSa5qHbmcYGoo"
        return FirebaseOptions.Builder()
            .setApplicationId("1:71794555883:android:4c65036e94f7e36e3b37ae")
            .setApiKey(apiKey)
            .setProjectId("gen-lang-client-0021883952")
            .setGcmSenderId("71794555883")
            .setStorageBucket("gen-lang-client-0021883952.firebasestorage.app")
            .build()
    }

    fun safeInitializeFirebase(context: Context) {
        val appCtx = context.applicationContext ?: context
        try {
            if (FirebaseApp.getApps(appCtx).isEmpty()) {
                val autoInitialized = runCatching { FirebaseApp.initializeApp(appCtx) }.getOrNull()
                if (autoInitialized == null && FirebaseApp.getApps(appCtx).isEmpty()) {
                    val options = parseFirebaseOptionsFromAssetsOrNull(appCtx)
                        ?: buildFallbackFirebaseOptions()
                    if (options != null) {
                        FirebaseApp.initializeApp(appCtx, options)
                        Log.i("SonixStartup", "FirebaseApp initialized manually via FirebaseOptions.")
                    } else {
                        Log.i("SonixStartup", "Firebase config not provided; running local studio tools.")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("SonixStartup", "FirebaseApp.initializeApp failed safely: ${t.message}")
            AppDiagnosticLog.logRecoverable(
                toolName = "FirebaseInit",
                titleAr = "تجاوز تهيئة Firebase السحابية",
                titleEn = "Firebase Initialization Bypassed",
                causeAr = t.localizedMessage ?: "تعذر تهيئة خدمات Firebase حالياً؛ التطبيق يعمل محلياً بأمان.",
                causeEn = t.localizedMessage ?: "Firebase init skipped; local studio tools continue normally.",
                errorCode = t.javaClass.simpleName,
                throwable = t,
            )
        }
        try {
            if (FirebaseApp.getApps(appCtx).isNotEmpty()) {
                Firebase.auth.currentUser
            }
        } catch (t: Throwable) {
            Log.w("SonixStartup", "FirebaseAuth initialization skipped safely: ${t.message}")
        }
        try {
            if (FirebaseApp.getApps(appCtx).isNotEmpty()) {
                val dbId = appCtx.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(dbId)
                runCatching {
                    db.firestoreSettings = firestoreSettings {
                        setLocalCacheSettings(persistentCacheSettings {})
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("SonixStartup", "FirebaseFirestore initialization skipped safely: ${t.message}")
        }
    }

    fun installUncaughtExceptionHandler(context: Context) {
        if (!handlerInstalled.compareAndSet(false, true)) return
        val appCtx = context.applicationContext ?: context
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(appCtx, thread, throwable)
        }
    }

    fun handleUncaughtException(
        context: Context,
        thread: Thread,
        throwable: Throwable,
    ) {
        Log.e("SonixCrashHandler", "Intercepted exception on thread ${thread.name}", throwable)
        val msg = throwable.localizedMessage?.takeIf { it.isNotBlank() }
            ?: throwable::class.java.simpleName
        val trace = Log.getStackTraceString(throwable).takeIf { it.isNotBlank() }
            ?: throwable.toString()

        val isMainLooper = Looper.myLooper() == Looper.getMainLooper() ||
            thread == Looper.getMainLooper().thread

        if (!isMainLooper) {
            // Background thread error: intercept & log without closing the app or disrupting UI!
            AppDiagnosticLog.logRecoverable(
                toolName = "Background (${thread.name})",
                titleAr = "تم تجاوز خطأ في المعالجة الخلفية",
                titleEn = "Background Thread Error Intercepted",
                causeAr = msg,
                causeEn = msg,
                errorCode = throwable.javaClass.simpleName,
                throwable = throwable,
            )
            return
        }

        // Critical main-looper exception: record persistently and relaunch Activity ONLY
        // into the user-consent restart dialog (never silent termination).
        val entry = AppDiagnosticLog.logCritical(
            toolName = "UI Main Thread",
            titleAr = "خطأ حرج في واجهة التشغيل",
            titleEn = "Critical UI Thread Exception",
            causeAr = msg,
            causeEn = msg,
            errorCode = throwable.javaClass.simpleName,
            throwable = throwable,
            requestUserRestartConsent = true,
        )

        val relaunched = runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val last = prefs.getLong(KEY_LAST_RELAUNCH_MS, 0L)
            if (now - last < RELAUNCH_COOLDOWN_MS) {
                false
            } else {
                prefs.edit().putLong(KEY_LAST_RELAUNCH_MS, now).commit()
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION,
                    )
                    putExtra(EXTRA_ASK_RESTART_CONSENT, true)
                    putExtra(EXTRA_DIAGNOSTIC_ID, entry.id)
                    putExtra(EXTRA_CRASH_THREAD, thread.name)
                    putExtra(EXTRA_CRASH_MESSAGE, msg)
                    putExtra(EXTRA_CRASH_TRACE, trace.take(8000))
                }
                context.startActivity(intent)
                true
            }
        }.getOrDefault(false)

        if (relaunched) {
            Process.killProcess(Process.myPid())
            exitProcess(1)
        } else {
            _uncaughtError.value = StartupErrorReport(thread.name, msg, trace)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}

class SonixApplication : Application() {
    override fun onCreate() {
        try {
            super.onCreate()
        } catch (t: Throwable) {
            Log.e("SonixStartup", "Application super.onCreate error", t)
        }
        runCatching { AppDiagnosticLog.init(this) }
        runCatching { RecentEditsCacheManager.init(this) }
        runCatching { SmartCacheManager.pruneManagedBuckets(this) }
        try {
            SonixCrashGuard.installUncaughtExceptionHandler(this)
        } catch (t: Throwable) {
            Log.e("SonixStartup", "Failed to install crash handler", t)
        }
        try {
            SonixCrashGuard.safeInitializeFirebase(this)
        } catch (t: Throwable) {
            Log.w("SonixStartup", "Safe Firebase init caught error: ${t.message}")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            runCatching { SmartCacheManager.trimSessionCache(this, keepCount = 5) }
        }
    }
}
