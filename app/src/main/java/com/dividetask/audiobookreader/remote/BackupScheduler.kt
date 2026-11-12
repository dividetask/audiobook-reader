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
import com.dividetask.audiobookreader.data.repository.BookRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two kinds of backup:
 * - Local: every book on the phone not yet on the server, whenever on WiFi. Queued at
 *   startup, when a book is added, and every few hours.
 * - Audible: one book per day/week/month (Audible's download limit), on WiFi while
 *   charging and idle. "Back up now" does one more straight away.
 */
@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: ServerSettings,
    private val tokenStore: AudibleTokenStore,
    private val bookRepository: BookRepository
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Call once at app start: applies the schedule and backs up books as they're added. */
    fun start() {
        applySchedule()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            bookRepository.getAllBooks()
                .map { books -> books.map { it.fileUri }.toSet() }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (isEnabled) enqueueLocalBackup() }
        }
    }

    private val isEnabled get() = settings.backupEnabled && settings.isConfigured

    /** Call after changing the backup checkbox, frequency or server settings. */
    fun applySchedule() {
        workManager.cancelUniqueWork(LEGACY_FULL)
        if (isEnabled) {
            val audible = PeriodicWorkRequestBuilder<BackupCheckWorker>(settings.frequency.days, TimeUnit.DAYS).build()
            workManager.enqueueUniquePeriodicWork(AUDIBLE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, audible)
            val local = PeriodicWorkRequestBuilder<BackupWorker>(LOCAL_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(wifi().build())
                .setInputData(workDataOf(BackupWorker.KEY_MODE to BackupWorker.MODE_LOCAL))
                .build()
            workManager.enqueueUniquePeriodicWork(LOCAL_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, local)
            enqueueLocalBackup()
        } else {
            listOf(AUDIBLE_PERIODIC, AUDIBLE, LOCAL_PERIODIC, LOCAL).forEach { workManager.cancelUniqueWork(it) }
        }
    }

    private fun wifi() = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)

    /** Every book on the phone that isn't on the server, as soon as there's WiFi. */
    fun enqueueLocalBackup() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(wifi().build())
            .setInputData(workDataOf(BackupWorker.KEY_MODE to BackupWorker.MODE_LOCAL))
            .build()
        workManager.enqueueUniqueWork(LOCAL, ExistingWorkPolicy.KEEP, request)
    }

    /** The next Audible book: only on WiFi, while charging, idle, battery and storage OK. */
    fun enqueueAudibleBackup() {
        val constraints = wifi()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(BackupWorker.KEY_MODE to BackupWorker.MODE_AUDIBLE))
            .build()
        workManager.enqueueUniqueWork(AUDIBLE, ExistingWorkPolicy.KEEP, request)
    }

    /** The next Audible book, as soon as there's a network (WiFi if "WiFi only" is on). */
    fun backupNow() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (tokenStore.wifiOnlyDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(BackupWorker.KEY_MODE to BackupWorker.MODE_AUDIBLE, BackupWorker.KEY_FORCE to true))
            .build()
        workManager.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelBackupNow() = workManager.cancelUniqueWork(NOW)

    /** True while "Back up now" is queued or running. */
    val backupNowPending: Flow<Boolean>
        get() = workManager.getWorkInfosForUniqueWorkFlow(NOW).map { infos -> infos.any { !it.state.isFinished } }

    /** State of the scheduled Audible book, e.g. ENQUEUED = waiting for WiFi/charging/idle. */
    val audibleBackupState: Flow<WorkInfo.State?>
        get() = workManager.getWorkInfosForUniqueWorkFlow(AUDIBLE).map { infos -> infos.firstOrNull { !it.state.isFinished }?.state }

    /** State of the local-copies pass, e.g. ENQUEUED = waiting for WiFi. */
    val localBackupState: Flow<WorkInfo.State?>
        get() = workManager.getWorkInfosForUniqueWorkFlow(LOCAL).map { infos -> infos.firstOrNull { !it.state.isFinished }?.state }

    companion object {
        private const val AUDIBLE_PERIODIC = "server_backup_periodic"
        private const val AUDIBLE = "server_backup_audible"
        private const val LOCAL_PERIODIC = "server_backup_local_periodic"
        private const val LOCAL = "server_backup_local"
        private const val NOW = "server_backup_now"
        /** Queued by earlier versions; replaced by [AUDIBLE]. */
        private const val LEGACY_FULL = "server_backup_full"
        private const val LOCAL_INTERVAL_HOURS = 6L
    }
}
