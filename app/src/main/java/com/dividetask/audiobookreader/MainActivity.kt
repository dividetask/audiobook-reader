package com.dividetask.audiobookreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.dividetask.audiobookreader.navigation.AppNavigation
import com.dividetask.audiobookreader.ui.theme.AudiobookReaderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var debugLog: AppDebugLog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudiobookReaderTheme {
                AppNavigation(debugLog = debugLog)
            }
        }
    }
}
