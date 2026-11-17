package com.example.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Serializable
enum class DiagnosticSeverity {
    RECOVERABLE,
    CRITICAL,
}

@Serializable
data class AppDiagnosticEntry(
    val id: String,
    val severity: DiagnosticSeverity,
    val titleAr: String,
    val titleEn: String,
    val causeAr: String,
    val causeEn: String,
    val toolName: String,
    val errorCode: String,
    val stackTrace: String,
    val timestampMs: Long,
    val occurrenceCount: Int = 1,
)

/**
 * Central crash & technical diagnostics registry for Sonix.
 * - Captures recoverable tool/FFmpeg/network/coroutine errors without killing the app.
 * - Captures critical errors and presents a user-consent restart dialog instead of force closing.
 * - Persists logs cleanly in app filesDir so users can inspect & copy stack traces anytime.
 */
object AppDiagnosticLog {
    private const val FILE_NAME = "sonix_diagnostics.json"
    private const val MAX_ENTRIES = 120

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    @Volatile
    private var storageFile: File? = null

    private val lock = Any()

    private val _entries = MutableStateFlow<List<AppDiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<AppDiagnosticEntry>> = _entries.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _criticalRestartPrompt = MutableStateFlow<AppDiagnosticEntry?>(null)
    val criticalRestartPrompt: StateFlow<AppDiagnosticEntry?> = _criticalRestartPrompt.asStateFlow()

    fun init(context: Context) {
        val appCtx = context.applicationContext ?: context
        synchronized(lock) {
            if (storageFile != null) return
            val dir = appCtx.filesDir ?: appCtx.cacheDir
            storageFile = File(dir, FILE_NAME)
            val loaded = readFromDisk()
            _entries.value = loaded
        }
    }

    fun logRecoverable(
        toolName: String,
        titleAr: String,
        titleEn: String,
        causeAr: String,
        causeEn: String,
        errorCode: String = "ERR_RECOVERABLE",
        throwable: Throwable? = null,
        extraTrace: String? = null,
    ): AppDiagnosticEntry {
        val trace = buildTrace(throwable, extraTrace)
        val entry = AppDiagnosticEntry(
            id = UUID.randomUUID().toString(),
            severity = DiagnosticSeverity.RECOVERABLE,
            titleAr = titleAr,
            titleEn = titleEn,
            causeAr = causeAr,
            causeEn = causeEn,
            toolName = toolName.ifBlank { "Studio" },
            errorCode = errorCode.ifBlank { throwable?.javaClass?.simpleName ?: "RECOVERABLE" },
            stackTrace = trace,
            timestampMs = System.currentTimeMillis(),
        )
        appendEntry(entry, incrementUnread = true)
        return entry
    }

    fun logCritical(
        toolName: String,
        titleAr: String,
        titleEn: String,
        causeAr: String,
        causeEn: String,
        errorCode: String = "ERR_CRITICAL",
        throwable: Throwable? = null,
        extraTrace: String? = null,
        requestUserRestartConsent: Boolean = true,
    ): AppDiagnosticEntry {
        val trace = buildTrace(throwable, extraTrace)
        val entry = AppDiagnosticEntry(
            id = UUID.randomUUID().toString(),
            severity = DiagnosticSeverity.CRITICAL,
            titleAr = titleAr,
            titleEn = titleEn,
            causeAr = causeAr,
            causeEn = causeEn,
            toolName = toolName.ifBlank { "System" },
            errorCode = errorCode.ifBlank { throwable?.javaClass?.simpleName ?: "CRITICAL" },
            stackTrace = trace,
            timestampMs = System.currentTimeMillis(),
        )
        appendEntry(entry, incrementUnread = true)
        if (requestUserRestartConsent) {
            _criticalRestartPrompt.value = entry
        }
        return entry
    }

    fun showPromptForEntryId(entryId: String) {
        val found = _entries.value.firstOrNull { it.id == entryId }
        if (found != null) {
            _criticalRestartPrompt.value = found
        }
    }

    fun showPrompt(entry: AppDiagnosticEntry) {
        _criticalRestartPrompt.value = entry
    }

    fun dismissCriticalPrompt() {
        _criticalRestartPrompt.value = null
    }

    fun markAllRead() {
        _unreadCount.value = 0
    }

    fun clearAll() {
        synchronized(lock) {
            _entries.value = emptyList()
            _unreadCount.value = 0
            runCatching { storageFile?.delete() }
        }
    }

    fun findById(id: String): AppDiagnosticEntry? = _entries.value.firstOrNull { it.id == id }

    fun formatTimestamp(ts: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))
    }

    fun formatFullReport(entry: AppDiagnosticEntry): String {
        return buildString {
            appendLine("=== Sonix Technical Report ===")
            appendLine("Tool: ${entry.toolName}")
            appendLine("Severity: ${entry.severity.name}")
            appendLine("Time: ${formatTimestamp(entry.timestampMs)}")
            appendLine("Code: ${entry.errorCode}")
            appendLine("Occurrences: ${entry.occurrenceCount.coerceAtLeast(1)}")
            appendLine("Title (AR): ${entry.titleAr}")
            appendLine("Title (EN): ${entry.titleEn}")
            appendLine("Cause (AR): ${entry.causeAr}")
            appendLine("Cause (EN): ${entry.causeEn}")
            appendLine("--- StackTrace / Details ---")
            appendLine(entry.stackTrace)
        }
    }

    private fun buildTrace(throwable: Throwable?, extraTrace: String?): String {
        val parts = buildList {
            if (throwable != null) {
                add(Log.getStackTraceString(throwable).ifBlank { throwable.toString() })
            }
            if (!extraTrace.isNullOrBlank()) {
                add(extraTrace.trim())
            }
        }
        return parts.joinToString("\n\n").ifBlank { "No stack trace captured." }.take(12_000)
    }

    private fun appendEntry(entry: AppDiagnosticEntry, incrementUnread: Boolean) {
        synchronized(lock) {
            val normCode = entry.errorCode.trim()
            val normTool = entry.toolName.trim()
            val currentList = _entries.value
            val existingIdx = currentList.indexOfFirst {
                it.errorCode.trim().equals(normCode, ignoreCase = true) &&
                    it.toolName.trim().equals(normTool, ignoreCase = true)
            }
            val updated = if (existingIdx >= 0) {
                val existing = currentList[existingIdx]
                val mergedSeverity = if (
                    existing.severity == DiagnosticSeverity.CRITICAL ||
                    entry.severity == DiagnosticSeverity.CRITICAL
                ) {
                    DiagnosticSeverity.CRITICAL
                } else {
                    entry.severity
                }
                val merged = existing.copy(
                    severity = mergedSeverity,
                    titleAr = entry.titleAr.ifBlank { existing.titleAr },
                    titleEn = entry.titleEn.ifBlank { existing.titleEn },
                    causeAr = entry.causeAr.ifBlank { existing.causeAr },
                    causeEn = entry.causeEn.ifBlank { existing.causeEn },
                    stackTrace = entry.stackTrace.ifBlank { existing.stackTrace },
                    timestampMs = maxOf(existing.timestampMs, entry.timestampMs),
                    occurrenceCount = (existing.occurrenceCount.coerceAtLeast(1) + 1).coerceAtMost(9999),
                )
                val withoutOld = currentList.toMutableList().apply { removeAt(existingIdx) }
                (listOf(merged) + withoutOld).take(MAX_ENTRIES)
            } else {
                (listOf(entry.copy(occurrenceCount = entry.occurrenceCount.coerceAtLeast(1))) + currentList)
                    .take(MAX_ENTRIES)
            }
            _entries.value = updated
            if (incrementUnread) {
                _unreadCount.value = (_unreadCount.value + 1).coerceAtMost(999)
            }
            writeToDisk(updated)
        }
    }

    private fun readFromDisk(): List<AppDiagnosticEntry> {
        val file = storageFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) emptyList() else json.decodeFromString<List<AppDiagnosticEntry>>(text)
        }.getOrDefault(emptyList())
    }

    private fun writeToDisk(list: List<AppDiagnosticEntry>) {
        val file = storageFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(list))
        }
    }
}
