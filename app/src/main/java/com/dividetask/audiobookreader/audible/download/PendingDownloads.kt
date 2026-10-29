package com.dividetask.audiobookreader.audible.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A book Audible wouldn't let us download yet (download limit); retried at [nextAttemptAt]. */
@Serializable
data class PendingDownload(
    val asin: String,
    val title: String,
    val author: String? = null,
    val coverUrl: String? = null,
    val runtimeMinutes: Int? = null,
    /** How many times Audible has turned it down so far. */
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0
)

/** Pending downloads, saved so they survive the app being closed. */
@Singleton
class PendingDownloadStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("pending_downloads", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _items = MutableStateFlow(load())
    val items: StateFlow<Map<String, PendingDownload>> = _items.asStateFlow()

    fun get(asin: String): PendingDownload? = _items.value[asin]

    @Synchronized
    fun put(item: PendingDownload) = save(_items.value + (item.asin to item))

    @Synchronized
    fun remove(asin: String) {
        if (asin in _items.value) save(_items.value - asin)
    }

    private fun load(): Map<String, PendingDownload> = try {
        prefs.getString(KEY, null)
            ?.let { json.decodeFromString<List<PendingDownload>>(it) }
            ?.associateBy { it.asin }
            ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private fun save(items: Map<String, PendingDownload>) {
        _items.value = items
        prefs.edit().putString(KEY, json.encodeToString(items.values.toList())).apply()
    }

    companion object {
        private const val KEY = "items"
    }
}

/** Scheduled by [AudibleDownloadManager.scheduleRetry]; tries the pending downloads that are due. */
@HiltWorker
class PendingDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloadManager: AudibleDownloadManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            // Android 12+ may refuse a foreground service when started from the background.
            // The download still runs; if it's stopped, the book stays pending and is retried.
            Log.w("PendingDownloadWorker", "Could not run in foreground: ${e.message}")
        }
        downloadManager.retryDuePending()
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Retrying Audible downloads")
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val WORK_NAME = "pending_audible_downloads"
        private const val NOTIFICATION_ID = 0x5F7D
    }
}
