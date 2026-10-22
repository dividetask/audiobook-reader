package com.dividetask.audiobookreader.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dividetask.audiobookreader.audible.download.AaxImportStatus
import com.dividetask.audiobookreader.audible.download.AaxImporter
import com.dividetask.audiobookreader.audible.download.AudibleDownloadManager
import com.dividetask.audiobookreader.audible.download.DownloadProgress
import com.dividetask.audiobookreader.audible.download.DownloadState
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.dividetask.audiobookreader.youtube.YoutubeImporter
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One line describing whatever download/import is running, or null when idle. */
@Singleton
class BackgroundWork @Inject constructor(
    @ApplicationContext private val context: Context,
    downloadManager: AudibleDownloadManager,
    aaxImporter: AaxImporter,
    youtubeImporter: YoutubeImporter,
    private val debugLog: AppDebugLog
) {
    val activity: Flow<String?> = combine(
        downloadManager.downloadProgress,
        downloadManager.queuedAsins,
        aaxImporter.status,
        youtubeImporter.status
    ) { download, queued, aax, youtube ->
        describe(youtube) ?: describe(aax) ?: describe(download)
            ?: if (queued.isNotEmpty()) "${queued.size} Audible download(s) queued" else null
    }

    private fun describe(status: AaxImportStatus?): String? =
        status?.takeIf { !it.finished }?.let { s ->
            "${s.title} — ${s.step}" + (s.progress?.let { " ${(it * 100).toInt()}%" } ?: "")
        }

    private fun describe(progress: DownloadProgress?): String? = when (progress?.state) {
        DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.WAITING_FOR_WIFI ->
            "Downloading ${progress.title}"
        DownloadState.CONVERTING -> "Converting ${progress.title}"
        else -> null
    }

    /**
     * Starts [BackgroundWorkService] whenever work begins. Work always begins from a
     * tap in the app, so the app is in the foreground and may start the service.
     */
    fun startWatching() {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            activity.map { it != null }.distinctUntilChanged().collect { busy ->
                if (!busy) return@collect
                try {
                    ContextCompat.startForegroundService(context, Intent(context, BackgroundWorkService::class.java))
                } catch (e: Exception) {
                    debugLog.log("BG", "Could not start background service: ${e.message}")
                }
            }
        }
    }
}

/**
 * Keeps downloads and conversions running when the app is in the background or the
 * screen is off: a foreground service (so Android doesn't kill the process) plus a
 * partial wake lock (so the CPU keeps running). Stops itself once nothing is running.
 */
@AndroidEntryPoint
class BackgroundWorkService : Service() {

    @Inject lateinit var work: BackgroundWork

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground promptly after startForegroundService
        goForeground("Working…")
        if (!started) {
            started = true
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudiobookReader:background-work")
                .apply { acquire(6 * 60 * 60 * 1000L) }
            scope.launch {
                work.activity.collect { text ->
                    if (text == null) {
                        ServiceCompat.stopForeground(this@BackgroundWorkService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        notificationManager().notify(NOTIFICATION_ID, notification(text))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), type)
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notification(text: String) = run {
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads and imports", NotificationManager.IMPORTANCE_LOW)
        )
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Audiobook Reader")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
                }
            )
            .build()
    }

    companion object {
        const val CHANNEL = "background_work"
        const val NOTIFICATION_ID = 0x5F7C
    }
}
