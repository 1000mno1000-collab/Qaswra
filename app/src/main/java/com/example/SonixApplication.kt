package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun safeInitializeFirebase(context: Context) {
        val appCtx = context.applicationContext ?: context
        try {
            if (FirebaseApp.getApps(appCtx).isEmpty()) {
                FirebaseApp.initializeApp(appCtx)
            }
        } catch (t: Throwable) {
            Log.w("SonixStartup", "FirebaseApp.initializeApp failed safely (offline mode active): ${t.message}")
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
                FirebaseFirestore.getInstance(dbId)
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
        Log.e("SonixCrashHandler", "Uncaught exception on thread ${thread.name}", throwable)
        val msg = throwable.localizedMessage?.takeIf { it.isNotBlank() }
            ?: throwable::class.java.simpleName
        val trace = Log.getStackTraceString(throwable).takeIf { it.isNotBlank() }
            ?: throwable.toString()
        val report = StartupErrorReport(
            threadName = thread.name,
            message = msg,
            stackTrace = trace,
        )
        _uncaughtError.value = report

        // If the crash occurred on the main thread, the UI Looper has exited;
        // relaunch MainActivity cleanly with the error details so the user sees
        // a readable recovery screen instead of the system "has stopped" dialog.
        if (Looper.myLooper() == Looper.getMainLooper()) {
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
                        putExtra(EXTRA_CRASH_THREAD, report.threadName)
                        putExtra(EXTRA_CRASH_MESSAGE, report.message)
                        putExtra(EXTRA_CRASH_TRACE, report.stackTrace.take(8000))
                    }
                    context.startActivity(intent)
                    true
                }
            }.getOrDefault(false)

            if (relaunched) {
                Process.killProcess(Process.myPid())
                exitProcess(1)
            } else {
                previousHandler?.uncaughtException(thread, throwable)
            }
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
}
