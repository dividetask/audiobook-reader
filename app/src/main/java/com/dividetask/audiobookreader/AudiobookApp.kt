package com.dividetask.audiobookreader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.dividetask.audiobookreader.audible.download.AudibleDownloadManager
import com.dividetask.audiobookreader.background.BackgroundWork
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AudiobookApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var backgroundWork: BackgroundWork
    @Inject lateinit var downloadManager: AudibleDownloadManager

    override fun onCreate() {
        super.onCreate()
        backgroundWork.startWatching()
        // Pending downloads (Audible's download limit) are retried by WorkManager
        downloadManager.scheduleRetry()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
