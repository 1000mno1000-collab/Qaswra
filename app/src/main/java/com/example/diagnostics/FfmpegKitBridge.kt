package com.example.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Safe reflection bridge around com.arthenica.ffmpegkit.FFmpegKit.
 * Logs execution errors or non-zero return codes to AppDiagnosticLog without crashing.
 */
object FfmpegKitBridge {

    fun isAvailable(): Boolean = runCatching {
        Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
        true
    }.getOrDefault(false)

    fun cancelActiveSessions() {
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
        val finalCommand = optimizeCommandWithThreads(command)
        try {
            if (cancelPrevious) {
                cancelActiveSessions()
            }
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
            // FFmpegKit native binary is optional in the classpath; cleanly fall back without noisy logs.
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
        }
    }
}
