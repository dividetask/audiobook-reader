package com.dividetask.audiobookreader.ui.main

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.dividetask.audiobookreader.audible.download.DownloadProgress
import com.dividetask.audiobookreader.ui.common.PauseIcon
import com.dividetask.audiobookreader.ui.remote.ServerLibraryTab
import com.dividetask.audiobookreader.audible.download.DownloadState
import com.dividetask.audiobookreader.player.PlaybackState as PState
import com.dividetask.audiobookreader.data.db.entity.AudibleBookEntity
import com.dividetask.audiobookreader.data.db.entity.BookEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onBookClick: (Long) -> Unit,
    onAudibleLogin: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val localBooks by viewModel.localBooks.collectAsState()
    val audibleBooks by viewModel.audibleBooks.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val importError by viewModel.importError.collectAsState()
    val aaxImport by viewModel.aaxImportStatus.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val queuedAsins by viewModel.queuedAsins.collectAsState()
    val convertingBookId by viewModel.convertingBookId.collectAsState()
    val playbackState by viewModel.playbackController.playbackState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var bookToDelete by remember { mutableStateOf<BookEntity?>(null) }
    var audibleBookToDelete by remember { mutableStateOf<AudibleBookEntity?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importLocalBook(it) }
    }

    LaunchedEffect(importError) {
        importError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(aaxImport) {
        val status = aaxImport
        if (status?.finished == true) {
            snackbarHostState.showSnackbar(status.step)
            viewModel.clearAaxImportStatus()
        }
    }

    // Download failures (e.g. Audible throttling) are otherwise silent on the Audible tab
    LaunchedEffect(downloadProgress?.state, downloadProgress?.message) {
        val progress = downloadProgress
        if (progress?.state == DownloadState.ERROR && progress.message != null) {
            snackbarHostState.showSnackbar(progress.message)
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 || selectedTab == 3) {
            viewModel.syncAudibleLibrary()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Audiobook Reader") },
                    actions = {
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                        text = { Text("Library") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                        text = { Text("Audible") })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                        text = { Text("Server") })
                    Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 },
                        text = { Text("All") })
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(onClick = {
                    filePickerLauncher.launch(arrayOf("audio/*", "application/octet-stream", "audio/vnd.audible.aax", "audio/aax"))
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Book")
                }
            }
        },
        bottomBar = {
            if (playbackState.title.isNotBlank()) {
                MiniPlayerBar(
                    playbackState = playbackState,
                    onTap = { onBookClick(playbackState.bookId) },
                    onPlayPause = { viewModel.playbackController.togglePlayPause() },
                    onSkipForward = { viewModel.playbackController.skipForward() }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isSyncing && (selectedTab == 1 || selectedTab == 3)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            aaxImport?.takeIf { !it.finished }?.let { status ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val percent = status.progress?.let { " ${(it * 100).toInt()}%" } ?: "…"
                    Text(
                        "Importing ${status.title} — ${status.step}$percent",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val fraction = status.progress
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }

            val tabContent: @Composable () -> Unit = {
                when (selectedTab) {
                    0 -> LocalBooksList(
                        books = localBooks,
                        onBookClick = onBookClick,
                        onDelete = { bookToDelete = it },
                        onDeleteConvertedFile = { viewModel.deleteConvertedFile(it) },
                        onDeleteAaxFile = { viewModel.deleteAaxFile(it) },
                        onConvert = { viewModel.retryConversion(it.id) },
                        onDownloadAax = { viewModel.redownloadAax(it) },
                        fileChecker = { viewModel.checkFiles(it) },
                        onCancelDownload = { viewModel.cancelDownload() },
                        convertingBookId = convertingBookId,
                        downloadProgress = downloadProgress,
                        queuedAsins = queuedAsins,
                        queuedTitle = { viewModel.getQueuedTitle(it) }
                    )
                    1 -> {
                        if (!viewModel.isAudibleAuthenticated) {
                            NotSignedInView(onLogin = onAudibleLogin)
                        } else {
                            AudibleBooksList(
                                books = audibleBooks,
                                downloadProgress = downloadProgress,
                                queuedAsins = queuedAsins,
                                isDownloaded = { viewModel.isBookDownloaded(it) },
                                onDownload = { viewModel.downloadAudibleBook(it) },
                                onDelete = { audibleBookToDelete = it }
                            )
                        }
                    }
                    2 -> ServerLibraryTab(onOpenSettings = onSettingsClick)
                    3 -> {
                        AllBooksList(
                            localBooks = localBooks,
                            audibleBooks = if (viewModel.isAudibleAuthenticated) audibleBooks else emptyList(),
                            onLocalBookClick = onBookClick,
                            onLocalBookDelete = { bookToDelete = it },
                            downloadProgress = downloadProgress,
                            isDownloaded = { viewModel.isBookDownloaded(it) },
                            onDownload = { viewModel.downloadAudibleBook(it) },
                            onLogin = onAudibleLogin,
                            isAuthenticated = viewModel.isAudibleAuthenticated
                        )
                    }
                }
            }

            // Pull down on the Audible lists to re-sync the library with Amazon
            if (selectedTab == 1 || selectedTab == 3) {
                PullToRefreshBox(
                    isRefreshing = isSyncing,
                    onRefresh = { viewModel.syncAudibleLibrary() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    tabContent()
                }
            } else {
                tabContent()
            }
        }
    }

    bookToDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("Remove Book") },
            text = { Text("Remove \"${book.title}\" from your library?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteLocalBook(book.id)
                    bookToDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) { Text("Cancel") }
            }
        )
    }

    audibleBookToDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { audibleBookToDelete = null },
            title = { Text("Delete Download") },
            text = { Text("Delete the downloaded file for \"${book.title}\"? You can re-download it later from Audible.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDownload(book.asin)
                    audibleBookToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { audibleBookToDelete = null }) { Text("Cancel") }
            }
        )
    }
}


@Composable
private fun NotSignedInView(onLogin: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Not signed in to Audible", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onLogin) {
                Text("Sign in to Audible")
            }
        }
    }
}

@Composable
private fun LocalBooksList(
    books: List<BookEntity>,
    onBookClick: (Long) -> Unit,
    onDelete: (BookEntity) -> Unit,
    onDeleteConvertedFile: (BookEntity) -> Unit = {},
    onDeleteAaxFile: (BookEntity) -> Unit = {},
    onConvert: (BookEntity) -> Unit = {},
    onDownloadAax: (BookEntity) -> Unit = {},
    fileChecker: (BookEntity) -> Pair<Boolean, Boolean> = { false to false },
    onCancelDownload: () -> Unit = {},
    convertingBookId: Long? = null,
    downloadProgress: DownloadProgress? = null,
    queuedAsins: Set<String> = emptySet(),
    queuedTitle: (String) -> String = { it }
) {
    val activeDownload = downloadProgress?.takeIf { it.state in listOf(DownloadState.DOWNLOADING, DownloadState.WAITING_FOR_WIFI, DownloadState.CONVERTING) }
    val hasDownloads = activeDownload != null || queuedAsins.isNotEmpty()

    if (books.isEmpty() && !hasDownloads) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PlayArrow, contentDescription = null,
                    modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Text("No audiobooks yet", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Tap + to add your first book", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            if (hasDownloads) {
                activeDownload?.let { progress ->
                    item(key = "dl_${progress.asin}") {
                        DownloadingBookItem(
                            title = progress.title,
                            state = progress.state,
                            progress = if (progress.totalBytes > 0) progress.bytesDownloaded.toFloat() / progress.totalBytes.toFloat() else null,
                            sizeMB = "${progress.bytesDownloaded / 1_048_576} / ${progress.totalBytes / 1_048_576} MB",
                            coverUrl = progress.coverUrl,
                            onCancel = onCancelDownload
                        )
                    }
                }
                items(queuedAsins.toList(), key = { "q_$it" }) { asin ->
                    DownloadingBookItem(
                        title = queuedTitle(asin),
                        state = DownloadState.QUEUED,
                        progress = null,
                        sizeMB = "Queued"
                    )
                }
            }
            items(books, key = { it.id }) { book ->
                val (hasAax, hasConverted) = fileChecker(book)
                LocalBookItem(
                    book = book,
                    onClick = { onBookClick(book.id) },
                    onDelete = { onDelete(book) },
                    onDeleteConvertedFile = { onDeleteConvertedFile(book) },
                    onDeleteAaxFile = { onDeleteAaxFile(book) },
                    onConvert = { onConvert(book) },
                    onDownloadAax = { onDownloadAax(book) },
                    isConverting = convertingBookId == book.id,
                    hasAaxFile = hasAax,
                    hasConvertedFile = hasConverted
                )
            }
        }
    }
}

@Composable
private fun DownloadingBookItem(
    title: String,
    state: DownloadState,
    progress: Float?,
    sizeMB: String,
    coverUrl: String? = null,
    onCancel: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (coverUrl != null) {
                AsyncImage(model = coverUrl, contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop)
            } else {
                Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when (state) {
                        DownloadState.DOWNLOADING -> "Downloading — $sizeMB"
                        DownloadState.WAITING_FOR_WIFI -> "Waiting for WiFi"
                        DownloadState.QUEUED -> "Queued"
                        DownloadState.CONVERTING -> "Converting..."
                        DownloadState.ERROR -> "Error"
                        else -> sizeMB
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state) {
                        DownloadState.CONVERTING -> Color(0xFFFF9800)
                        DownloadState.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (state == DownloadState.DOWNLOADING && progress != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            when (state) {
                DownloadState.DOWNLOADING -> {
                    CircularProgressIndicator(
                        progress = { progress ?: 0f },
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp
                    )
                }
                DownloadState.CONVERTING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                        color = Color(0xFFFF9800)
                    )
                }
                DownloadState.WAITING_FOR_WIFI -> {
                    Icon(Icons.Default.Warning, contentDescription = "Waiting for WiFi",
                        modifier = Modifier.size(28.dp), tint = Color(0xFFFF9800))
                }
                DownloadState.QUEUED -> {
                    Icon(Icons.Default.Refresh, contentDescription = "Queued",
                        modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {}
            }
            if (state in listOf(DownloadState.DOWNLOADING, DownloadState.QUEUED, DownloadState.WAITING_FOR_WIFI, DownloadState.CONVERTING)) {
                IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel",
                        modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun LocalBookItem(
    book: BookEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onDeleteConvertedFile: () -> Unit = {},
    onDeleteAaxFile: () -> Unit = {},
    onConvert: () -> Unit = {},
    onDownloadAax: () -> Unit = {},
    isConverting: Boolean = false,
    hasAaxFile: Boolean = false,
    hasConvertedFile: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (book.coverUri != null) {
                AsyncImage(model = book.coverUri, contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop)
            } else {
                Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false))
                    if (book.fileUri.endsWith(".aax")) {
                        Spacer(modifier = Modifier.width(4.dp))
                        if (isConverting) {
                            Text("Converting...", style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFF9800))
                        } else {
                            Text("DRM", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (book.durationMs > 0 && !book.fileUri.endsWith(".aax")) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val progress = if (book.durationMs > 0) book.currentPositionMs.toFloat() / book.durationMs.toFloat() else 0f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(formatDuration(book.currentPositionMs) + " / " + formatDuration(book.durationMs),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (isConverting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = Color(0xFFFF9800)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (hasConvertedFile) {
                        DropdownMenuItem(
                            text = { Text("Delete converted file") },
                            onClick = { showMenu = false; onDeleteConvertedFile() }
                        )
                    }
                    if (hasAaxFile && !isConverting) {
                        DropdownMenuItem(
                            text = { Text("Convert to m4b") },
                            onClick = { showMenu = false; onConvert() }
                        )
                    }
                    if (hasAaxFile) {
                        DropdownMenuItem(
                            text = { Text("Delete aax file") },
                            onClick = { showMenu = false; onDeleteAaxFile() }
                        )
                    }
                    if (!hasAaxFile) {
                        DropdownMenuItem(
                            text = { Text("Download aax") },
                            onClick = { showMenu = false; onDownloadAax() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Remove from library") },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun AudibleBooksList(
    books: List<AudibleBookEntity>,
    downloadProgress: DownloadProgress?,
    queuedAsins: Set<String> = emptySet(),
    isDownloaded: (String) -> Boolean,
    onDownload: (AudibleBookEntity) -> Unit,
    onDelete: (AudibleBookEntity) -> Unit
) {
    if (books.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No books in Audible library", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            items(books, key = { it.asin }) { book ->
                val bookProgress = if (downloadProgress?.asin == book.asin) downloadProgress
                    else if (queuedAsins.contains(book.asin)) DownloadProgress(book.asin, book.title, 0, 0, DownloadState.QUEUED)
                    else null
                AudibleBookItem(
                    book = book,
                    isDownloaded = isDownloaded(book.asin),
                    downloadState = bookProgress,
                    onDownload = { onDownload(book) },
                    onDelete = { onDelete(book) }
                )
            }
        }
    }
}

@Composable
private fun AudibleBookItem(
    book: AudibleBookEntity,
    isDownloaded: Boolean,
    downloadState: DownloadProgress?,
    onDownload: () -> Unit,
    onDelete: () -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (book.coverUrl != null) {
                AsyncImage(model = book.coverUrl, contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop)
            } else {
                Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                book.runtimeMinutes?.let {
                    Text("${it / 60}h ${it % 60}m", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                isDownloaded -> IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete download",
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                }
                downloadState?.state == DownloadState.DOWNLOADING -> {
                    val progress = if (downloadState.totalBytes > 0)
                        downloadState.bytesDownloaded.toFloat() / downloadState.totalBytes.toFloat()
                    else 0f
                    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp
                        )
                    }
                }
                downloadState?.state == DownloadState.WAITING_FOR_WIFI -> {
                    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Warning, contentDescription = "Waiting for WiFi",
                            tint = Color(0xFFFF9800), modifier = Modifier.size(28.dp))
                    }
                }
                downloadState?.state == DownloadState.QUEUED -> {
                    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Refresh, contentDescription = "Queued",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                    }
                }
                else -> IconButton(onClick = onDownload) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Download", modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
private fun AllBooksList(
    localBooks: List<BookEntity>,
    audibleBooks: List<AudibleBookEntity>,
    onLocalBookClick: (Long) -> Unit,
    onLocalBookDelete: (BookEntity) -> Unit,
    downloadProgress: DownloadProgress?,
    isDownloaded: (String) -> Boolean,
    onDownload: (AudibleBookEntity) -> Unit,
    onLogin: () -> Unit,
    isAuthenticated: Boolean
) {
    val audibleOnly = audibleBooks.filter { ab -> localBooks.none { it.title == ab.title } }

    if (localBooks.isEmpty() && audibleOnly.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No books yet", style = MaterialTheme.typography.titleMedium)
                if (!isAuthenticated) {
                    TextButton(onClick = onLogin) { Text("Sign in to Audible") }
                }
            }
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            if (localBooks.isNotEmpty()) {
                item { Text("Local Library", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(localBooks, key = { "local_${it.id}" }) { book ->
                    LocalBookItem(book = book, onClick = { onLocalBookClick(book.id) }, onDelete = { onLocalBookDelete(book) })
                }
            }
            if (audibleOnly.isNotEmpty()) {
                item { Spacer(modifier = Modifier.height(8.dp)); Text("Audible (not downloaded)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(audibleOnly, key = { "audible_${it.asin}" }) { book ->
                    val bookProgress = if (downloadProgress?.asin == book.asin) downloadProgress else null
                    AudibleBookItem(
                        book = book,
                        isDownloaded = isDownloaded(book.asin),
                        downloadState = bookProgress,
                        onDownload = { onDownload(book) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(
    playbackState: PState,
    onTap: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column {
            if (playbackState.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { playbackState.currentPositionMs.toFloat() / playbackState.durationMs.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(playbackState.title, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(playbackState.author, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = onPlayPause) {
                    if (playbackState.isPlaying) {
                        PauseIcon(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                    }
                }
                IconButton(onClick = onSkipForward) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Skip forward")
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%d:%02d", minutes, seconds)
}
