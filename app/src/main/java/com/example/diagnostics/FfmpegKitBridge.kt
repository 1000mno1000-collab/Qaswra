package com.example.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Safe reflection bridge around com.arthenica.ffmpegkit.FFmpegKit.
 * Verifies native `.so` availability safely (catching UnsatisfiedLinkError), serializes execution,
 * and logs non-zero return codes to AppDiagnosticLog without crashing.
 */
object FfmpegKitBridge {

    private val executeMutex = Mutex()
    private val activeExecutions = AtomicInteger(0)

    @Volatile
    private var nativeCheckCache: Boolean? = null

    fun isAvailable(): Boolean {
        nativeCheckCache?.let { return it }
        val ok = try {
            Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
            val cfgClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKitConfig")
            val version = cfgClass.getMethod("getVersion").invoke(null) as? String
            version != null && version.isNotBlank()
        } catch (_: Throwable) {
            false
        }
        nativeCheckCache = ok
        return ok
    }

    fun cancelActiveSessions() {
        if (nativeCheckCache == false || !isAvailable()) return
        runCatching {
            val ffmpegClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
            ffmpegClass.getMethod("cancel").invoke(null)
        }
    }

    fun optimizeCommandWithThreads(command: String): String {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return trimmed
        if (THREADS_REGEX.containsMatchIn(trimmed)) return trimmed
        return if (trimmed.startsWith("-y ")) {
            "-y -threads 0 ${trimmed.removePrefix("-y ").trimStart()}"
        } else {
            "-threads 0 $trimmed"
        }
    }

    private val THREADS_REGEX = Regex("""(?:^|\s)-threads\s+""")

    suspend fun execute(
        command: String,
        toolName: String,
        logNonZeroFailure: Boolean = true,
        cancelPrevious: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext false
        val finalCommand = optimizeCommandWithThreads(command)
        if (cancelPrevious) {
            cancelActiveSessions()
        }
        executeMutex.withLock {
            activeExecutions.incrementAndGet()
            try {
                val ffmpegClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
                val returnCodeClass = Class.forName("com.arthenica.ffmpegkit.ReturnCode")
                val executeMethod = ffmpegClass.getMethod("execute", String::class.java)
                val session = executeMethod.invoke(null, finalCommand) ?: return@withContext false

                val getReturnCode = session.javaClass.getMethod("getReturnCode")
                val returnCode = getReturnCode.invoke(session) ?: return@withContext false
                val isSuccessMethod = returnCodeClass.getMethod("isSuccess", returnCodeClass)
                val ok = (isSuccessMethod.invoke(null, returnCode) as? Boolean) == true

                if (!ok && logNonZeroFailure) {
                    val logs = runCatching {
                        session.javaClass.getMethod("getAllLogsAsString").invoke(session) as? String
                    }.getOrNull().orEmpty()
                    AppDiagnosticLog.logRecoverable(
                        toolName = toolName,
                        titleAr = "تعذر تنفيذ أمر معالجة FFmpegKit",
                        titleEn = "FFmpegKit execution returned non-zero status",
                        causeAr = "فشل تنفيذ فلتر FFmpeg في $toolName وتم التحويل تلقائياً إلى معالج الاستوديو الاحتياطي.",
                        causeEn = "FFmpeg filter command failed in $toolName; automatically bypassed to safe Kotlin DSP.",
                        errorCode = "FFMPEG_RC_${returnCode}",
                        extraTrace = "Command: $finalCommand\nLogs:\n${logs.takeLast(3000)}",
                    )
                }
                ok
            } catch (_: ClassNotFoundException) {
                false
            } catch (t: Throwable) {
                AppDiagnosticLog.logRecoverable(
                    toolName = toolName,
                    titleAr = "خطأ أثناء تشغيل نواة FFmpegKit",
                    titleEn = "FFmpegKit execution exception",
                    causeAr = "حدث استثناء تقني أثناء تشغيل المعالجة السريعة وتم تفعيل المعالجة البديلة بأمان.",
                    causeEn = "An exception occurred in FFmpegKit; safely bypassed via fallback DSP.",
                    errorCode = t.javaClass.simpleName,
                    throwable = t,
                    extraTrace = "Command: $finalCommand",
                )
                false
            } finally {
                activeExecutions.decrementAndGet()
            }
        }
    }
}
