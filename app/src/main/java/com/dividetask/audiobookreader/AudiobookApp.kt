package com.dividetask.audiobookreader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.dividetask.audiobookreader.audible.download.AudibleDownloadManager
import com.dividetask.audiobookreader.background.BackgroundWork
import com.dividetask.audiobookreader.debug.AppDebugLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AudiobookApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var backgroundWork: BackgroundWork
    @Inject lateinit var downloadManager: AudibleDownloadManager
    @Inject lateinit var debugLog: AppDebugLog
    @Inject lateinit var aaxImporter: com.dividetask.audiobookreader.audible.download.AaxImporter

    override fun onCreate() {
        super.onCreate()
        // Keep the stack trace in the debug log, which survives to the next run
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                debugLog.crash(thread, e)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
        backgroundWork.startWatching()
        // Pending downloads (Audible's download limit) are retried by WorkManager
        downloadManager.scheduleRetry()
        // Books added before files were copied into the app
        aaxImporter.copyExternalBooks()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
