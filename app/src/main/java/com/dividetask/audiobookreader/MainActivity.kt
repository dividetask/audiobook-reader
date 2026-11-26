package com.dividetask.audiobookreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.dividetask.audiobookreader.remote.WebsiteBackupNotifier
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.dividetask.audiobookreader.navigation.AppNavigation
import com.dividetask.audiobookreader.ui.theme.AudiobookReaderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var debugLog: AppDebugLog

    /** Set when the website-backup notification was tapped. */
    private val openWebsiteBackup = mutableStateOf(false)
    /** Set when a "download stopped — sign in again" notification was tapped. */
    private val openWebsiteDownload = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AudiobookReaderTheme {
                AppNavigation(
                    debugLog = debugLog,
                    openWebsiteBackup = openWebsiteBackup.value,
                    onWebsiteBackupOpened = { openWebsiteBackup.value = false },
                    openWebsiteDownload = openWebsiteDownload.value,
                    onWebsiteDownloadOpened = { openWebsiteDownload.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(WebsiteBackupNotifier.EXTRA_ASIN)?.let {
            intent.removeExtra(WebsiteBackupNotifier.EXTRA_ASIN)
            openWebsiteDownload.value = it
        }
        if (intent?.getBooleanExtra(WebsiteBackupNotifier.EXTRA_OPEN, false) == true) {
            intent.removeExtra(WebsiteBackupNotifier.EXTRA_OPEN)
            openWebsiteBackup.value = true
        }
    }
}
