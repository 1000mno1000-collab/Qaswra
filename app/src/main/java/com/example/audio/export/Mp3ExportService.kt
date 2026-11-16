package com.example.audio.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.R
import com.example.audio.dsp.DspPipeline
import com.example.audio.io.Mp3AudioEncoder
import com.example.audio.io.StereoAudioBuffer
import com.example.model.DspConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

sealed class Mp3ExportState {
    data object Idle : Mp3ExportState()
    data class Exporting(val progress: Float, val stageLabel: String) : Mp3ExportState()
    data class Completed(
        val outputUri: Uri,
        val fileName: String,
        val isShare: Boolean
    ) : Mp3ExportState()
    data class Error(val message: String) : Mp3ExportState()
}

data class Mp3ExportRequest(
    val sourceBuffer: StereoAudioBuffer,
    val dspConfig: DspConfig,
    val destinationUri: Uri?,
    val fileName: String,
    val isShare: Boolean
)

object Mp3ExportCoordinator {

    private val _state = MutableStateFlow<Mp3ExportState>(Mp3ExportState.Idle)
    val state: StateFlow<Mp3ExportState> = _state.asStateFlow()

    private val pendingRequest = AtomicReference<Mp3ExportRequest?>(null)

    fun submitAndStart(context: Context, request: Mp3ExportRequest) {
        pendingRequest.set(request)
        _state.value = Mp3ExportState.Exporting(0.01f, "جارٍ تجهيز المعالج الصوتي…")
        val intent = Intent(context, Mp3ExportService::class.java)
        runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.onFailure {
            runCatching { context.startService(intent) }
        }
    }

    internal fun takePendingRequest(): Mp3ExportRequest? = pendingRequest.getAndSet(null)

    internal fun updateState(newState: Mp3ExportState) {
        _state.value = newState
    }

    fun acknowledgeTerminalState() {
        val current = _state.value
        if (current is Mp3ExportState.Completed || current is Mp3ExportState.Error) {
            _state.value = Mp3ExportState.Idle
        }
    }
}

/**
 * Background Service responsible for running offline DSP rendering and LAME MP3 encoding
 * off the main thread while displaying progress in a foreground notification.
 */
class Mp3ExportService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        val initialNotification = buildProgressNotification(
            progress = 0,
            detailText = getString(R.string.exporting_mp3_bg)
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        }

        val request = Mp3ExportCoordinator.takePendingRequest()
        if (request == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        activeJob?.cancel()
        activeJob = serviceScope.launch {
            executeExportJob(request)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }
            stopSelfResult(startId)
        }

        return START_NOT_STICKY
    }

    private fun executeExportJob(request: Mp3ExportRequest) {
        try {
            var lastNotifPct = -1

            // Phase 1: Render full DSP pipeline (0% .. 55%)
            Mp3ExportCoordinator.updateState(
                Mp3ExportState.Exporting(0.02f, "تطبيق المؤثرات الصوتية والماسترينغ…")
            )
            val rendered = DspPipeline(request.sourceBuffer.sampleRate).renderOffline(
                source = request.sourceBuffer,
                config = request.dspConfig
            ) { dspFrac ->
                val overall = (dspFrac * 0.55f).coerceIn(0.02f, 0.55f)
                Mp3ExportCoordinator.updateState(
                    Mp3ExportState.Exporting(overall, "تطبيق المؤثرات الصوتية…")
                )
                val pct = (overall * 100).toInt()
                if (pct - lastNotifPct >= 8) {
                    lastNotifPct = pct
                    updateNotification(pct, "تطبيق المؤثرات الصوتية ($pct%)")
                }
            }

            // Phase 2: Encode rendered PCM into MP3 (55% .. 98%)
            Mp3ExportCoordinator.updateState(
                Mp3ExportState.Exporting(0.56f, "ترميز الصوت إلى MP3 (192 kbps)…")
            )
            updateNotification(56, "ترميز الصوت إلى MP3…")

            val resultUri: Uri = if (request.isShare || request.destinationUri == null) {
                val exportsDir = File(cacheDir, "exports").apply { mkdirs() }
                val safeBase = request.fileName
                    .replace(Regex("[^a-zA-Z0-9\\u0600-\\u06FF_-]"), "_")
                    .ifBlank { "VoicePro_Export" }
                val mp3File = File(exportsDir, "${safeBase}_${System.currentTimeMillis()}.mp3")
                FileOutputStream(mp3File).use { fos ->
                    Mp3AudioEncoder.encodeStereoBufferToMp3Stream(
                        buffer = rendered,
                        output = fos,
                        bitrateKbps = 192
                    ) { encFrac ->
                        val overall = (0.56f + encFrac * 0.43f).coerceIn(0.56f, 0.99f)
                        Mp3ExportCoordinator.updateState(
                            Mp3ExportState.Exporting(overall, "ترميز الصوت إلى MP3…")
                        )
                        val pct = (overall * 100).toInt()
                        if (pct - lastNotifPct >= 8) {
                            lastNotifPct = pct
                            updateNotification(pct, "ترميز MP3 ($pct%)")
                        }
                    }
                }
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", mp3File)
            } else {
                val destUri = request.destinationUri
                val stream = contentResolver.openOutputStream(destUri)
                    ?: error("تعذر فتح وجهة الحفظ المحددة")
                stream.use { out ->
                    Mp3AudioEncoder.encodeStereoBufferToMp3Stream(
                        buffer = rendered,
                        output = out,
                        bitrateKbps = 192
                    ) { encFrac ->
                        val overall = (0.56f + encFrac * 0.43f).coerceIn(0.56f, 0.99f)
                        Mp3ExportCoordinator.updateState(
                            Mp3ExportState.Exporting(overall, "ترميز الصوت إلى MP3…")
                        )
                        val pct = (overall * 100).toInt()
                        if (pct - lastNotifPct >= 8) {
                            lastNotifPct = pct
                            updateNotification(pct, "ترميز MP3 ($pct%)")
                        }
                    }
                }
                destUri
            }

            Mp3ExportCoordinator.updateState(
                Mp3ExportState.Completed(
                    outputUri = resultUri,
                    fileName = request.fileName,
                    isShare = request.isShare
                )
            )
        } catch (t: Throwable) {
            Mp3ExportCoordinator.updateState(
                Mp3ExportState.Error(t.message ?: "فشل تصدير ملف MP3")
            )
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.export_mp3_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun updateNotification(progress: Int, detailText: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildProgressNotification(progress, detailText))
    }

    private fun buildProgressNotification(progress: Int, detailText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.export_mp3_notif_title))
            .setContentText(detailText)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .build()
    }

    override fun onDestroy() {
        activeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "voice_pro_mp3_export"
        private const val NOTIFICATION_ID = 4201
    }
}
