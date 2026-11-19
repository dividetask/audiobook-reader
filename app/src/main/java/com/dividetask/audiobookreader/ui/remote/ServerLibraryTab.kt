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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.dividetask.audiobookreader.data.db.entity.AudibleBookEntity
import com.dividetask.audiobookreader.data.series.SeriesIndex
import com.dividetask.audiobookreader.remote.RemoteDownload
import com.dividetask.audiobookreader.ui.common.SeriesPlaceholderItem
import com.dividetask.audiobookreader.ui.common.SortMenu
import com.dividetask.audiobookreader.ui.common.SortOrder
import com.dividetask.audiobookreader.ui.common.SortTab
import com.dividetask.audiobookreader.ui.common.bookListEntries
import com.dividetask.audiobookreader.ui.common.groupBySeries
import com.dividetask.audiobookreader.ui.common.matchesSearch
import com.dividetask.audiobookreader.remote.RemoteFile
import com.dividetask.audiobookreader.remote.RemoteLibraryManager
import androidx.lifecycle.viewModelScope
import com.dividetask.audiobookreader.remote.BackupEngine
import com.dividetask.audiobookreader.remote.BackupPhase
import com.dividetask.audiobookreader.remote.BackupStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerLibraryViewModel @Inject constructor(
    val manager: RemoteLibraryManager,
    backupEngine: BackupEngine
) : ViewModel() {
    val backupStatus: StateFlow<BackupStatus> = backupEngine.status
    val uploadQueue: StateFlow<List<Pair<String, String>>> = backupEngine.queue

    init {
        // An upload ended (finished or stopped): list the server again so a finished book shows up
        viewModelScope.launch {
            var last = ""
            backupEngine.status.collect { status ->
                if (last.isNotEmpty() && status.path != last) manager.refresh()
                last = status.path
            }
        }
    }
}

/**
 * The "Server" tab: audio files in the sync folder (SFTP or S3), each downloadable into the library.
 * Files are grouped by series; series titles not on the server can be downloaded from
 * Audible when owned.
 */
@Composable
fun ServerLibraryTab(
    onOpenSettings: () -> Unit,
    searchQuery: String = "",
    seriesIndex: SeriesIndex,
    expandedSeries: Set<String>,
    onToggleSeries: (String) -> Unit,
    isAudibleDownloaded: (String) -> Boolean,
    onAudibleDownload: (AudibleBookEntity) -> Unit,
    sortOrder: SortOrder,
    onSortChange: (SortOrder) -> Unit,
    viewModel: ServerLibraryViewModel = hiltViewModel()
) {
    val manager = viewModel.manager
    val allFiles by manager.files.collectAsState()
    val files = allFiles.filter {
        matchesSearch(searchQuery, it.title, it.folder, seriesIndex.forTitle(it.title, it.folder)?.seriesTitle)
    }
    val entries = remember(files, seriesIndex, sortOrder) {
        val order = if (sortOrder == SortOrder.RECENTLY_ADDED) compareByDescending<RemoteFile> { it.modified } else null
        groupBySeries(files, seriesIndex, { seriesIndex.forTitle(it.title, it.folder) }, { it.title }, order = order)
    }
    val isLoading by manager.isLoading.collectAsState()
    val error by manager.error.collectAsState()
    val downloads by manager.downloads.collectAsState()
    val backup by viewModel.backupStatus.collectAsState()
    val queue by viewModel.uploadQueue.collectAsState()
    val unfinished by manager.unfinished.collectAsState()
    val uploads = remember(backup, queue, unfinished, searchQuery) {
        uploadRows(backup, queue, unfinished).filter { matchesSearch(searchQuery, it.title, it.folder, null) }
    }

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
            if (uploads.isNotEmpty()) {
                item(key = "uploads_header") {
                    Text("Uploading to server", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                uploads.forEach { row -> item(key = "upload_${row.key}") { UploadItem(row) } }
            }
            if (files.isNotEmpty()) item(key = "sort") { SortMenu(SortTab.SERVER, sortOrder, onSortChange) }
            bookListEntries(
                entries = entries,
                expandedSeries = expandedSeries,
                onToggleSeries = onToggleSeries,
                keyOf = { it.relPath },
                book = { file ->
                    ServerBookItem(
                        file = file,
                        download = downloads[file.relPath],
                        onDevice = manager.isOnDevice(file),
                        onDownload = { manager.download(file) },
                        onCancel = { manager.cancel(file) }
                    )
                },
                missing = { member ->
                    val owned = member.asin?.let { seriesIndex.libraryByAsin[it] }
                    when {
                        owned == null -> SeriesPlaceholderItem(member, "Not purchased")
                        isAudibleDownloaded(owned.asin) -> SeriesPlaceholderItem(member, "Not on server · on device") {
                            Icon(Icons.Default.CheckCircle, contentDescription = "On device",
                                modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        else -> SeriesPlaceholderItem(member, "Not on server · owned on Audible") {
                            IconButton(onClick = { onAudibleDownload(owned) }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Download from Audible")
                            }
                        }
                    }
                }
            )
        }
    }
}

/** A book on its way to the server: being uploaded, waiting, or partly uploaded by an earlier run. */
private data class UploadRow(
    val key: String,
    val title: String,
    val folder: String,
    val status: String,
    /** null = no progress bar; -1 = indeterminate. */
    val fraction: Float?
)

private fun uploadRows(
    backup: BackupStatus,
    queue: List<Pair<String, String>>,
    unfinished: List<com.dividetask.audiobookreader.remote.UnfinishedUpload>
): List<UploadRow> {
    val rows = mutableListOf<UploadRow>()
    val mb = { b: Long -> b / 1_048_576 }
    when (backup.phase) {
        BackupPhase.UPLOADING -> rows.add(UploadRow("current", backup.title, folderOf(backup.path),
            "Uploading · ${mb(backup.bytes)} of ${mb(backup.total)} MB",
            if (backup.total > 0) backup.bytes.toFloat() / backup.total else -1f))
        BackupPhase.DOWNLOADING -> rows.add(UploadRow("current", backup.title, "",
            "Downloading from Audible, then uploading" +
                if (backup.total > 0) " · ${mb(backup.bytes)} of ${mb(backup.total)} MB" else "",
            if (backup.total > 0) backup.bytes.toFloat() / backup.total else -1f))
        BackupPhase.CONVERTING -> rows.add(UploadRow("current", backup.title, "", "Converting, then uploading", -1f))
        else -> {}
    }
    val queued = queue.map { it.second }.toSet()
    val byPath = unfinished.groupBy { it.relPath }
    queue.forEach { (title, path) ->
        val partial = byPath[path]?.maxOfOrNull { it.size } ?: 0
        rows.add(UploadRow("queued_$path", title, folderOf(path),
            "Waiting to upload" + if (partial > 0) " · ${mb(partial)} MB already on server" else "", null))
    }
    byPath.filterKeys { it != backup.path && it !in queued }.forEach { (path, list) ->
        val size = list.maxOf { it.size }
        rows.add(UploadRow("partial_$path", path.substringAfterLast('/').substringBeforeLast('.'), folderOf(path),
            "Upload not finished · ${mb(size)} MB on server", null))
    }
    return rows
}

private fun folderOf(path: String) = path.substringBeforeLast('/', "").substringAfterLast('/')

@Composable
private fun UploadItem(row: UploadRow) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(row.folder, row.status).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            row.fraction?.let { f ->
                Spacer(modifier = Modifier.height(4.dp))
                if (f < 0f) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
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
