package com.dividetask.audiobookreader.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.dividetask.audiobookreader.remote.RemoteDownload
import com.dividetask.audiobookreader.ui.common.matchesSearch
import com.dividetask.audiobookreader.remote.RemoteFile
import com.dividetask.audiobookreader.remote.RemoteLibraryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ServerLibraryViewModel @Inject constructor(
    val manager: RemoteLibraryManager
) : ViewModel()

/** The "Server" tab: audio files in the SFTP folder, each downloadable into the library. */
@Composable
fun ServerLibraryTab(
    onOpenSettings: () -> Unit,
    searchQuery: String = "",
    viewModel: ServerLibraryViewModel = hiltViewModel()
) {
    val manager = viewModel.manager
    val allFiles by manager.files.collectAsState()
    val files = allFiles.filter { matchesSearch(searchQuery, it.title, it.folder) }
    val isLoading by manager.isLoading.collectAsState()
    val error by manager.error.collectAsState()
    val downloads by manager.downloads.collectAsState()

    if (!manager.isConfigured) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No server set up", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onOpenSettings) { Text("Set up server in Settings") }
            }
        }
        return
    }

    LaunchedEffect(Unit) { manager.refresh() }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (isLoading && files.isEmpty()) "Connecting..." else if (files.size == allFiles.size) "${files.size} books on server" else "${files.size} of ${allFiles.size} books on server",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { manager.refresh() }, enabled = !isLoading) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }
        error?.let {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { manager.clearError() }) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss")
                }
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            items(files, key = { it.relPath }) { file ->
                ServerBookItem(
                    file = file,
                    download = downloads[file.relPath],
                    onDevice = manager.isOnDevice(file),
                    onDownload = { manager.download(file) },
                    onCancel = { manager.cancel(file) }
                )
            }
        }
    }
}

@Composable
private fun ServerBookItem(
    file: RemoteFile,
    download: RemoteDownload?,
    onDevice: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(file.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(file.folder, "${file.size / 1_048_576} MB", file.name.substringAfterLast('.').uppercase())
                        .filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (download != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val fraction = download.fraction
                    if (fraction == null) {
                        Text("Queued", style = MaterialTheme.typography.labelSmall)
                    } else {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            when {
                download != null -> IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
                }
                onDevice -> Icon(Icons.Default.CheckCircle, contentDescription = "On device",
                    modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                else -> IconButton(onClick = onDownload) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Download")
                }
            }
        }
    }
}
