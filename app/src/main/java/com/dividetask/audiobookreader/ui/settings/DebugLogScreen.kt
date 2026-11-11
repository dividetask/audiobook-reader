package com.dividetask.audiobookreader.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.dividetask.audiobookreader.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.dividetask.audiobookreader.debug.AudibleRequestLog
import com.dividetask.audiobookreader.debug.saveToDownloads
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DebugLogViewModel @Inject constructor(val requestLog: AudibleRequestLog) : ViewModel()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(
    onBack: () -> Unit,
    debugLog: AppDebugLog,
    viewModel: DebugLogViewModel = hiltViewModel()
) {
    val entries by debugLog.entries.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Both logs as files, for logs too long to paste
                    TextButton(onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        val built = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(BuildConfig.BUILD_TIMESTAMP))
                        val debugText = "${BuildConfig.VERSION_NAME} · $built\n\n" + debugLog.getFullLog()
                        val requestText = viewModel.requestLog.recent(Int.MAX_VALUE)
                        scope.launch {
                            val message = try {
                                withContext(Dispatchers.IO) {
                                    val a = saveToDownloads(context, "audiobook-debug-$stamp.txt", debugText)
                                    val b = saveToDownloads(context, "audiobook-audible-$stamp.txt", requestText)
                                    "Saved $a and $b"
                                }
                            } catch (e: Exception) {
                                "Saving failed: ${e.message}"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }) {
                        Text("Save")
                    }
                    // Saved log of every Audible request, for working out its download limit
                    TextButton(onClick = {
                        val text = viewModel.requestLog.recent()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Audible request log", text))
                        Toast.makeText(context, "Audible request log copied (${text.lines().size - 1} lines)", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Audible log")
                    }
                    IconButton(onClick = {
                        val buildDate2 = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(BuildConfig.BUILD_TIMESTAMP))
                        val header = "${BuildConfig.VERSION_NAME} · $buildDate2\n\n"
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Debug Log", header + debugLog.getFullLog()))
                        Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Copy all")
                    }
                    IconButton(onClick = { debugLog.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear log")
                    }
                }
            )
        }
    ) { padding ->
        val buildDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(BuildConfig.BUILD_TIMESTAMP))
        val buildInfo = "${BuildConfig.VERSION_NAME} · $buildDate"

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                SelectionContainer {
                    Text(
                        text = buildInfo,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (entries.isEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    "No log entries yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Text(
                    "Try signing in or downloading a book",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    SelectionContainer {
                        Text(
                            text = entries.joinToString("\n"),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
