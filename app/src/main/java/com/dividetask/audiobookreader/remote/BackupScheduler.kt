package com.dividetask.audiobookreader.remote

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dividetask.audiobookreader.audible.auth.AudibleTokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: ServerSettings,
    private val tokenStore: AudibleTokenStore
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Call after changing the backup checkbox or frequency. */
    fun applySchedule() {
        if (settings.backupEnabled && settings.isConfigured) {
            val request = PeriodicWorkRequestBuilder<BackupCheckWorker>(settings.frequency.days, TimeUnit.DAYS).build()
            workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            workManager.cancelUniqueWork(PERIODIC)
            workManager.cancelUniqueWork(FULL)
        }
    }

    /** Full pass: only on Wi-Fi, while charging, idle, battery and storage OK. */
    fun enqueueFullBackup() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(BackupWorker.KEY_SINGLE to false))
            .build()
        workManager.enqueueUniqueWork(FULL, ExistingWorkPolicy.KEEP, request)
    }

    /** One book, starting as soon as there's a network (Wi-Fi if "WiFi only" is on). */
    fun backupNow() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (tokenStore.wifiOnlyDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(BackupWorker.KEY_SINGLE to true))
            .build()
        workManager.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelBackupNow() = workManager.cancelUniqueWork(NOW)

    /** True while "Back up now" is queued or running. */
    val backupNowPending: Flow<Boolean>
        get() = workManager.getWorkInfosForUniqueWorkFlow(NOW).map { infos -> infos.any { !it.state.isFinished } }

    /** State of the scheduled full pass, e.g. ENQUEUED = waiting for Wi-Fi/charging/idle. */
    val fullBackupState: Flow<WorkInfo.State?>
        get() = workManager.getWorkInfosForUniqueWorkFlow(FULL).map { infos -> infos.firstOrNull { !it.state.isFinished }?.state }

    companion object {
        private const val PERIODIC = "server_backup_periodic"
        private const val FULL = "server_backup_full"
        private const val NOW = "server_backup_now"
    }
}
