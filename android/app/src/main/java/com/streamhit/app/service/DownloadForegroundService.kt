package com.streamhit.app.service

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
import com.streamhit.app.MainActivity
import com.streamhit.app.R
import com.streamhit.app.data.local.DownloadItem
import com.streamhit.app.data.local.DownloadStatus
import com.streamhit.app.data.local.MediaDownloadManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class DownloadForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "streamhit_downloads_channel"
        const val CHANNEL_NAME = "ডাউনলোড ম্যানেজার"
        const val NOTIFICATION_ID = 90210
        const val COMPLETE_NOTIFICATION_ID = 90211

        private const val WAKE_LOCK_TAG = "streamhit:DownloadWakeLock"

        fun startService(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager
    private var lastUpdateMs = 0L

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // Acquire WakeLock to prevent CPU sleep during background downloads
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // Safety cap of 4 hours max
            }
        } catch (_: Exception) {}

        // Launch initial foreground notification
        val initialNotification = buildNotification("ডাউনলোড শুরু হচ্ছে...", 0, "প্রস্তুত করা হচ্ছে...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        observeDownloads()
    }

    private fun observeDownloads() {
        serviceScope.launch {
            MediaDownloadManager.downloadsFlow.collectLatest { list ->
                val activeDownloads = list.filter { it.status == DownloadStatus.DOWNLOADING }
                val pendingDownloads = list.filter { it.status == DownloadStatus.PENDING }

                if (activeDownloads.isNotEmpty()) {
                    val current = activeDownloads.first()
                    val now = System.currentTimeMillis()
                    // Throttle notification updates to at most once every 500ms to avoid UI stutter
                    if (now - lastUpdateMs >= 500L || current.progressPercent == 100) {
                        lastUpdateMs = now
                        val title = if (activeDownloads.size > 1) {
                            "${current.title} (+${activeDownloads.size - 1} simultaneous)"
                        } else {
                            current.title
                        }
                        val progress = current.progressPercent
                        val queueInfo = if (pendingDownloads.isNotEmpty()) " • ${pendingDownloads.size} in queue" else ""
                        val infoText = "$progress% • ${DownloadItem.formatFileSize(current.downloadedBytes)} / ${DownloadItem.formatFileSize(current.totalBytes)}$queueInfo"
                        val updatedNotification = buildNotification(title, progress, infoText)
                        notificationManager.notify(NOTIFICATION_ID, updatedNotification)
                    }
                } else if (pendingDownloads.isNotEmpty()) {
                    // Pending downloads remain in queue! Promote next if queue stalled
                    MediaDownloadManager.processQueue(applicationContext)
                    val firstPending = pendingDownloads.first()
                    val infoText = "Waiting in queue (${pendingDownloads.size} remaining)"
                    val updatedNotification = buildNotification(firstPending.title, 0, infoText)
                    notificationManager.notify(NOTIFICATION_ID, updatedNotification)
                } else {
                    // Check if all downloads finished
                    val hasCompleted = list.any { it.status == DownloadStatus.COMPLETED }
                    if (hasCompleted) {
                        val lastCompleted = list.firstOrNull { it.status == DownloadStatus.COMPLETED }
                        lastCompleted?.let {
                            showCompletionNotification(it.title)
                        }
                    }
                    // Stop foreground service cleanly
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun buildNotification(title: String, progress: Int, contentText: String): android.app.Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_TAB", "DOWNLOADS")
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)

        if (progress in 0..100) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun showCompletionNotification(title: String) {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_TAB", "DOWNLOADS")
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ডাউনলোড সম্পন্ন! 🍿")
            .setContentText("$title অফলাইনে দেখার জন্য প্রস্তুত")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(COMPLETE_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "NXLY Stream ব্যাকগ্রাউন্ড ফাইল ডাউনলোডের লাইভ প্রোগ্রেস"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

