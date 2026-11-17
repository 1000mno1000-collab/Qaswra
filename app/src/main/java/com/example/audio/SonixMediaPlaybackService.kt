package com.example.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import java.util.concurrent.atomic.AtomicBoolean

object StudioPlaybackBridge {
    @Volatile
    var onTogglePlayPause: (() -> Unit)? = null

    @Volatile
    var onStopPlayback: (() -> Unit)? = null
}

class SonixMediaPlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning.set(true)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                ensureForeground(
                    title = lastTitle,
                    subtitle = lastSubtitle,
                    isPlaying = lastIsPlaying,
                )
                StudioPlaybackBridge.onTogglePlayPause?.invoke()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                StudioPlaybackBridge.onStopPlayback?.invoke()
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Sonix ✦"
                val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: ""
                val isPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, false) ?: false
                lastTitle = title
                lastSubtitle = subtitle
                lastIsPlaying = isPlaying

                if (isPlaying) {
                    acquireWakeLock()
                } else {
                    releaseWakeLock()
                }

                ensureForeground(title = title, subtitle = subtitle, isPlaying = isPlaying)
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        isServiceRunning.set(false)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun stopSelfCleanly() {
        isServiceRunning.set(false)
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sonix:AudioPlaybackLock").apply {
            setReferenceCounted(false)
            runCatching { acquire(4L * 60L * 60L * 1000L) }
        }
    }

    private fun releaseWakeLock() {
        val wl = wakeLock
        wakeLock = null
        if (wl != null && wl.isHeld) {
            runCatching { wl.release() }
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Sonix Studio Playback",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Background audio playback & sleep timer controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun ensureForeground(title: String, subtitle: String, isPlaying: Boolean) {
        val notification = buildNotification(title, subtitle, isPlaying)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(title: String, subtitle: String, isPlaying: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val playPauseIntent = Intent(this, SonixMediaPlaybackService::class.java).apply {
            action = ACTION_PLAY_PAUSE
        }
        val playPausePi = PendingIntent.getService(
            this,
            102,
            playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, SonixMediaPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this,
            103,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val playPauseLabel = if (isPlaying) "Pause" else "Play"
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_splash_vector)
            .setContentTitle(title)
            .setContentText(subtitle.ifBlank { "Sonix ✦ Studio" })
            .setContentIntent(contentPi)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(playPauseIcon, playPauseLabel, playPausePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sonix_media_playback"
        private const val NOTIFICATION_ID = 4401

        private const val ACTION_UPDATE = "com.example.audio.action.UPDATE"
        private const val ACTION_PLAY_PAUSE = "com.example.audio.action.PLAY_PAUSE"
        private const val ACTION_STOP = "com.example.audio.action.STOP"

        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SUBTITLE = "extra_subtitle"
        private const val EXTRA_IS_PLAYING = "extra_is_playing"

        private val isServiceRunning = AtomicBoolean(false)
        @Volatile private var lastTitle: String = "Sonix ✦"
        @Volatile private var lastSubtitle: String = ""
        @Volatile private var lastIsPlaying: Boolean = false

        fun syncPlaybackState(
            context: Context,
            isPlaying: Boolean,
            title: String,
            subtitle: String,
        ) {
            if (!isPlaying && !isServiceRunning.get()) return
            val intent = Intent(context, SonixMediaPlaybackService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            runCatching {
                if (isPlaying && !isServiceRunning.get()) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stopService(context: Context) {
            if (!isServiceRunning.get()) return
            val intent = Intent(context, SonixMediaPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
