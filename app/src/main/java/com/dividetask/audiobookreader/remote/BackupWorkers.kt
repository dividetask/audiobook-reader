package com.dividetask.audiobookreader.remote

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one backup pass. [KEY_MODE] = [MODE_LOCAL] uploads every book on the phone
 * that isn't on the server; [MODE_AUDIBLE] downloads, converts and uploads the next
 * Audible book (one at a time, to stay clear of Audible's download limit).
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: BackupEngine
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val local = inputData.getString(KEY_MODE) == MODE_LOCAL
        try {
            setForeground(engine.foregroundInfo())
        } catch (e: Exception) {
            // Android 12+ may refuse a foreground service when started from the background.
            // The work still runs; if stopped, it resumes from partial files next time.
            Log.w("BackupWorker", "Could not run in foreground: ${e.message}")
        }
        val outcome = if (local) engine.runLocal() else engine.runAudible(maxBooks = 1)
        return when (outcome) {
            BackupOutcome.AllDone, BackupOutcome.MoreRemaining -> Result.success()
            is BackupOutcome.Failed -> if (outcome.retry && local) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = engine.foregroundInfo()

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_LOCAL = "local"
        const val MODE_AUDIBLE = "audible"
    }
}

/** Runs on the day/week/month schedule and queues the next Audible book. */
@HiltWorker
class BackupCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: BackupScheduler
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        scheduler.enqueueAudibleBackup()
        return Result.success()
    }
}
