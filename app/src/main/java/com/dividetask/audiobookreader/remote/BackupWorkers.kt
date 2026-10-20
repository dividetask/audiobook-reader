package com.dividetask.audiobookreader.remote

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Backs up Audible books. Input [KEY_SINGLE] = true does just one book ("Back up now"). */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: BackupEngine
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val single = inputData.getBoolean(KEY_SINGLE, false)
        try {
            setForeground(engine.foregroundInfo())
        } catch (e: Exception) {
            // Android 12+ may refuse a foreground service when started from the background.
            // The work still runs; if stopped, it resumes from partial files next time.
            Log.w("BackupWorker", "Could not run in foreground: ${e.message}")
        }
        return when (val outcome = engine.run(if (single) 1 else Int.MAX_VALUE)) {
            BackupOutcome.AllDone, BackupOutcome.MoreRemaining -> Result.success()
            is BackupOutcome.Failed -> if (outcome.retry && !single) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = engine.foregroundInfo()

    companion object {
        const val KEY_SINGLE = "single"
    }
}

/** Runs on the daily/weekly/monthly schedule and queues a full backup pass. */
@HiltWorker
class BackupCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: BackupScheduler
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        scheduler.enqueueFullBackup()
        return Result.success()
    }
}
