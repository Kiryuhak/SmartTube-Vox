package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.vox.external.VoxLog

/**
 * Фоновый сервис (Foreground Service) для обеспечения надежного скачивания видео
 * и мультиплексирования MKV в фоновом режиме на Android TV.
 */
class VoxDownloadService : Service(), VoxDownloadListener {

    private lateinit var coordinator: VoxDownloadCoordinator
    private var notificationManager: NotificationManager? = null
    private var currentDownloadId: String? = null

    companion object {
        private const val TAG = "VoxDownloadService"

        const val CHANNEL_ID = "smarttube_vox_downloads_channel"
        const val NOTIFICATION_ID_PROGRESS = 55001
        const val NOTIFICATION_ID_COMPLETED = 55002
        const val NOTIFICATION_ID_FAILED = 55003

        const val ACTION_START_DOWNLOAD = "com.liskovsoft.smartyoutubetv2.action.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.liskovsoft.smartyoutubetv2.action.CANCEL_DOWNLOAD"
        const val ACTION_STOP_SERVICE = "com.liskovsoft.smartyoutubetv2.action.STOP_DOWNLOAD_SERVICE"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"

        @JvmStatic
        fun start(context: Context, downloadId: String) {
            val intent = Intent(context, VoxDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                VoxLog.e(TAG, "Failed to start VoxDownloadService: ${e.message}")
            }
        }

        @JvmStatic
        fun cancel(context: Context, downloadId: String) {
            val intent = Intent(context, VoxDownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                VoxLog.e(TAG, "Failed to send cancel to VoxDownloadService: ${e.message}")
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            val intent = Intent(context, VoxDownloadService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                VoxLog.e(TAG, "Failed to stop VoxDownloadService: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        coordinator = VoxDownloadCoordinator.instance(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createNotificationChannel()
        coordinator.addGlobalListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator.removeGlobalListener(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val downloadId = intent?.getStringExtra(EXTRA_DOWNLOAD_ID)

        VoxLog.d(TAG, "onStartCommand action=$action, downloadId=$downloadId")

        when (action) {
            ACTION_START_DOWNLOAD -> {
                if (!downloadId.isNullOrBlank() && isValidDownloadId(downloadId)) {
                    currentDownloadId = downloadId
                    val job = coordinator.getJob(downloadId)
                    if (job != null) {
                        val initialNotification = buildProgressNotification(job.getSnapshot())
                        startForegroundCompat(NOTIFICATION_ID_PROGRESS, initialNotification)
                        if (!coordinator.isJobActive(downloadId) && job.state != VoxDownloadState.COMPLETED) {
                            coordinator.resumeDownload(downloadId)
                        }
                    } else {
                        // Показываем базовое уведомление перед проверкой
                        val fallback = buildFallbackNotification()
                        startForegroundCompat(NOTIFICATION_ID_PROGRESS, fallback)
                    }
                } else {
                    checkActiveWorkOrStop()
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                if (!downloadId.isNullOrBlank() && isValidDownloadId(downloadId)) {
                    coordinator.cancelDownload(downloadId)
                }
                checkActiveWorkOrStop()
            }
            ACTION_STOP_SERVICE -> {
                stopForegroundSafely()
                stopSelf()
            }
            else -> {
                checkActiveWorkOrStop()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    private fun checkActiveWorkOrStop() {
        val jobs = coordinator.getAllJobs()
        val active = jobs.firstOrNull {
            it.state != VoxDownloadState.COMPLETED &&
            it.state != VoxDownloadState.FAILED &&
            it.state != VoxDownloadState.CANCELLED &&
            it.state != VoxDownloadState.PAUSED &&
            it.state != VoxDownloadState.IDLE
        }

        if (active != null) {
            currentDownloadId = active.downloadId
            val notification = buildProgressNotification(active.getSnapshot())
            startForegroundCompat(NOTIFICATION_ID_PROGRESS, notification)
        } else {
            stopForegroundSafely()
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.vox_download_channel_name)
            val desc = getString(R.string.vox_download_channel_desc)
            val channel = NotificationChannel(
                CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = desc
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(progress: VoxDownloadProgress): Notification {
        val job = coordinator.getJob(progress.downloadId)
        val title = job?.request?.videoTitle?.ifBlank { getString(R.string.vox_download_notification_title) }
            ?: getString(R.string.vox_download_notification_title)
        val stageText = getStageDescription(progress)

        val cancelIntent = Intent(this, VoxDownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_ID, progress.downloadId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            progress.downloadId.hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(stageText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.vox_download_notification_cancel),
                cancelPendingIntent
            )

        val pct = progress.overallPercent
        if (pct != null && pct in 0..100) {
            builder.setProgress(100, pct, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun buildFallbackNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.vox_download_notification_title))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun getStageDescription(progress: VoxDownloadProgress): String {
        val pct = progress.overallPercent
        val pctSuffix = if (pct != null && pct in 0..100) " ($pct%)" else ""
        return when (progress.state) {
            VoxDownloadState.PREPARING_TRANSLATION -> getString(R.string.vox_download_stage_prep_trans)
            VoxDownloadState.RESOLVING_STREAMS -> getString(R.string.vox_download_stage_resolving)
            VoxDownloadState.DOWNLOADING_VIDEO -> "${getString(R.string.vox_download_stage_video)}$pctSuffix"
            VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO -> "${getString(R.string.vox_download_stage_orig_audio)}$pctSuffix"
            VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO -> "${getString(R.string.vox_download_stage_trans_audio)}$pctSuffix"
            VoxDownloadState.MUXING -> "${getString(R.string.vox_download_stage_muxing)}$pctSuffix"
            VoxDownloadState.PUBLISHING -> "${getString(R.string.vox_download_stage_publishing)}$pctSuffix"
            VoxDownloadState.READY_FOR_MUX -> getString(R.string.vox_download_stage_muxing)
            VoxDownloadState.MUXED -> getString(R.string.vox_download_stage_publishing)
            VoxDownloadState.COMPLETED -> getString(R.string.vox_download_stage_completed)
            VoxDownloadState.FAILED -> getString(R.string.vox_download_stage_failed)
            VoxDownloadState.CANCELLED -> getString(R.string.vox_download_stage_cancelled)
            VoxDownloadState.PAUSED -> getString(R.string.vox_download_stage_prep_trans)
            VoxDownloadState.IDLE -> getString(R.string.vox_download_stage_prep_trans)
        }
    }

    private fun showCompletedNotification(progress: VoxDownloadProgress) {
        val job = coordinator.getJob(progress.downloadId)
        val title = job?.request?.videoTitle?.ifBlank { getString(R.string.vox_download_notification_title) }
            ?: getString(R.string.vox_download_notification_title)
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(getString(R.string.vox_download_notification_completed))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        notificationManager?.notify(NOTIFICATION_ID_COMPLETED, notification)
    }

    private fun showFailedNotification(progress: VoxDownloadProgress) {
        val job = coordinator.getJob(progress.downloadId)
        val title = job?.request?.videoTitle?.ifBlank { getString(R.string.vox_download_notification_title) }
            ?: getString(R.string.vox_download_notification_title)
        val message = progress.errorMessage ?: getString(R.string.vox_download_notification_failed)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        notificationManager?.notify(NOTIFICATION_ID_FAILED, notification)
    }

    private fun stopForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun isValidDownloadId(downloadId: String): Boolean {
        return downloadId.matches(Regex("^[a-zA-Z0-9_-]{1,64}$"))
    }

    override fun onStateChanged(progress: VoxDownloadProgress) {
        when (progress.state) {
            VoxDownloadState.COMPLETED -> {
                stopForegroundSafely()
                showCompletedNotification(progress)
                stopSelf()
            }
            VoxDownloadState.FAILED -> {
                stopForegroundSafely()
                showFailedNotification(progress)
                stopSelf()
            }
            VoxDownloadState.CANCELLED -> {
                stopForegroundSafely()
                stopSelf()
            }
            VoxDownloadState.PAUSED -> {
                stopForegroundSafely()
                stopSelf()
            }
            else -> {
                val notification = buildProgressNotification(progress)
                notificationManager?.notify(NOTIFICATION_ID_PROGRESS, notification)
            }
        }
    }

    override fun onProgressUpdated(progress: VoxDownloadProgress) {
        if (progress.state != VoxDownloadState.COMPLETED &&
            progress.state != VoxDownloadState.FAILED &&
            progress.state != VoxDownloadState.CANCELLED &&
            progress.state != VoxDownloadState.PAUSED &&
            progress.state != VoxDownloadState.IDLE
        ) {
            val notification = buildProgressNotification(progress)
            notificationManager?.notify(NOTIFICATION_ID_PROGRESS, notification)
        }
    }

    override fun onError(downloadId: String, errorCode: VoxDownloadErrorCode, message: String) {
        val job = coordinator.getJob(downloadId)
        if (job != null) {
            showFailedNotification(job.getSnapshot())
        }
        stopForegroundSafely()
        stopSelf()
    }
}
