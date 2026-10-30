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
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.OutlinedTextField
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
import com.dividetask.audiobookreader.data.series.SeriesIndex
import com.dividetask.audiobookreader.audible.download.PendingDownload
import com.dividetask.audiobookreader.ui.common.PauseIcon
import com.dividetask.audiobookreader.ui.common.SeriesMember
import com.dividetask.audiobookreader.ui.common.SeriesPlaceholderItem
import com.dividetask.audiobookreader.ui.common.SortMenu
import com.dividetask.audiobookreader.ui.common.SortOrder
import com.dividetask.audiobookreader.ui.common.SortTab
import com.dividetask.audiobookreader.ui.common.bookListEntries
import com.dividetask.audiobookreader.ui.common.groupBySeries
import com.dividetask.audiobookreader.ui.common.matchesSearch
import com.dividetask.audiobookreader.ui.remote.ServerLibraryTab
import com.dividetask.audiobookreader.audible.download.DownloadState
import com.dividetask.audiobookreader.data.db.entity.AudibleBookEntity
import com.dividetask.audiobookreader.data.db.entity.BookEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onBookClick: (Long) -> Unit,
    onAudibleLogin: () -> Unit,
    onSettingsClick: () -> Unit,
    onWebsiteDownload: (asin: String) -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val allLocalBooks by viewModel.localBooks.collectAsState()
    val allAudibleBooks by viewModel.audibleBooks.collectAsState()
    val seriesIndex by viewModel.seriesIndex.collectAsState()
    val sortOrders by viewModel.sortOrders.collectAsState()
    val sortOf: (SortTab) -> SortOrder = { sortOrders[it] ?: it.default }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val localBooks = allLocalBooks.filter {
        matchesSearch(searchQuery, it.title, it.author, seriesIndex.forLocal(it)?.seriesTitle)
    }
    val audibleBooks = allAudibleBooks.filter {
        matchesSearch(searchQuery, it.title, it.author, it.narrator, it.seriesTitle)
    }
    val isSyncing by viewModel.isSyncing.collectAsState()
    val importError by viewModel.importError.collectAsState()
    val aaxImport by viewModel.aaxImportStatus.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val queuedAsins by viewModel.queuedAsins.collectAsState()
    val pendingDownloads by viewModel.pendingDownloads.collectAsState()
    val websiteOffer by viewModel.websiteOffer.collectAsState()
    val convertingBookId by viewModel.convertingBookId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var bookToDelete by remember { mutableStateOf<BookEntity?>(null) }
    var audibleBookToDelete by remember { mutableStateOf<AudibleBookEntity?>(null) }
    // Series rows tapped open, shared by every tab
    var expandedSeries by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val toggleSeries: (String) -> Unit = {
        expandedSeries = if (it in expandedSeries) expandedSeries - it else expandedSeries + it
    }
    // A series title the tab doesn't have: owned on Audible gets the download button
    val missingSeriesBook: @Composable (SeriesMember<*>) -> Unit = { member ->
        val owned = member.asin?.let { seriesIndex.libraryByAsin[it] }
        if (owned != null) {
            AudibleBookItem(
                book = owned,
                isDownloaded = viewModel.isBookDownloaded(owned.asin),
                downloadState = audibleProgress(owned, downloadProgress, queuedAsins),
                pendingUntil = pendingDownloads[owned.asin]?.nextAttemptAt,
                onDownload = { viewModel.downloadAudibleBook(owned) },
                onDelete = { audibleBookToDelete = owned }
            )
        } else {
            SeriesPlaceholderItem(member, "Not purchased")
        }
    }

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

    // Download failures (e.g. Audible throttling) are otherwise silent on the Audible tab.
    // Shown once each, not again every time the screen is opened.
    var shownDownloadError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(downloadProgress?.state, downloadProgress?.message) {
        val progress = downloadProgress
        if (progress?.state == DownloadState.ERROR && progress.message != null && progress.message != shownDownloadError) {
            shownDownloadError = progress.message
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
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) searchQuery = ""
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
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
                // One search box filters whichever tab is showing
                if (showSearch) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search title, author, narrator, series") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
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
        // The mini player is shown app-wide by AppNavigation
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
                        queuedTitle = { viewModel.getQueuedTitle(it) },
                        seriesIndex = seriesIndex,
                        expandedSeries = expandedSeries,
                        onToggleSeries = toggleSeries,
                        missingSeriesBook = missingSeriesBook,
                        sortOrder = sortOf(SortTab.LIBRARY),
                        onSortChange = { viewModel.setSortOrder(SortTab.LIBRARY, it) },
                        pending = pendingDownloads.values
                            .filter { matchesSearch(searchQuery, it.title, it.author) }
                            .sortedBy { it.nextAttemptAt },
                        onPendingWebsite = onWebsiteDownload,
                        onPendingCancel = { viewModel.removePending(it) }
                    )
                    1 -> {
                        if (!viewModel.isAudibleAuthenticated) {
                            NotSignedInView(onLogin = onAudibleLogin)
                        } else {
                            AudibleBooksList(
                                books = audibleBooks,
                                downloadProgress = downloadProgress,
                                queuedAsins = queuedAsins,
                                pendingUntil = { pendingDownloads[it]?.nextAttemptAt },
                                isDownloaded = { viewModel.isBookDownloaded(it) },
                                onDownload = { viewModel.downloadAudibleBook(it) },
                                onDelete = { audibleBookToDelete = it },
                                seriesIndex = seriesIndex,
                                expandedSeries = expandedSeries,
                                onToggleSeries = toggleSeries,
                                missingSeriesBook = missingSeriesBook,
                                sortOrder = sortOf(SortTab.AUDIBLE),
                                onSortChange = { viewModel.setSortOrder(SortTab.AUDIBLE, it) }
                            )
                        }
                    }
                    2 -> ServerLibraryTab(
                        onOpenSettings = onSettingsClick,
                        searchQuery = searchQuery,
                        seriesIndex = seriesIndex,
                        expandedSeries = expandedSeries,
                        onToggleSeries = toggleSeries,
                        isAudibleDownloaded = { viewModel.isBookDownloaded(it) },
                        onAudibleDownload = { viewModel.downloadAudibleBook(it) },
                        sortOrder = sortOf(SortTab.SERVER),
                        onSortChange = { viewModel.setSortOrder(SortTab.SERVER, it) }
                    )
                    3 -> {
                        AllBooksList(
                            localBooks = localBooks,
                            audibleBooks = if (viewModel.isAudibleAuthenticated) audibleBooks else emptyList(),
                            onLocalBookClick = onBookClick,
                            onLocalBookDelete = { bookToDelete = it },
                            downloadProgress = downloadProgress,
                            pendingUntil = { pendingDownloads[it]?.nextAttemptAt },
                            isDownloaded = { viewModel.isBookDownloaded(it) },
                            onDownload = { viewModel.downloadAudibleBook(it) },
                            onLogin = onAudibleLogin,
                            isAuthenticated = viewModel.isAudibleAuthenticated,
                            seriesIndex = seriesIndex,
                            expandedSeries = expandedSeries,
                            onToggleSeries = toggleSeries,
                            missingSeriesBook = missingSeriesBook,
                            sortOrder = sortOf(SortTab.ALL),
                            onSortChange = { viewModel.setSortOrder(SortTab.ALL, it) }
                        )
                    }
                }
            }

            // Pull down on the Audible lists to re-sync the library with Amazon
            if (selectedTab == 1 || selectedTab == 3) {
                PullToRefreshBox(
                    isRefreshing = isSyncing,
                    onRefresh = { viewModel.syncAudibleLibrary(force = true) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    tabContent()
                }
            } else {
                tabContent()
            }
        }
    }

    // A download was blocked by Audible's download limit: offer the website instead
    websiteOffer?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissWebsiteOffer() },
            title = { Text("Download blocked") },
            text = {
                Text("Audible is limiting downloads for your account. Download \"${pending.title}\" from the Audible website instead? If not, it stays pending and is retried automatically.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissWebsiteOffer()
                    onWebsiteDownload(pending.asin)
                }) { Text("Download from website") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissWebsiteOffer() }) { Text("Keep pending") }
            }
        )
    }

    bookToDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("Remove Book") },
            text = {
                Text("Remove \"${book.title}\" from your library? Files the app downloaded or imported are deleted; files you added from your phone are kept.")
            },
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
    queuedTitle: (String) -> String = { it },
    seriesIndex: SeriesIndex,
    expandedSeries: Set<String>,
    onToggleSeries: (String) -> Unit,
    missingSeriesBook: @Composable (SeriesMember<*>) -> Unit,
    sortOrder: SortOrder,
    onSortChange: (SortOrder) -> Unit,
    /** Books Audible's download limit turned away, waiting for an automatic retry. */
    pending: List<PendingDownload> = emptyList(),
    onPendingWebsite: (asin: String) -> Unit = {},
    onPendingCancel: (asin: String) -> Unit = {}
) {
    val entries = remember(books, seriesIndex, sortOrder) {
        val order = when (sortOrder) {
            SortOrder.RECENTLY_PLAYED -> compareByDescending<BookEntity> { it.lastPlayed }.thenByDescending { it.dateAdded }
            SortOrder.RECENTLY_ADDED -> compareByDescending<BookEntity> { it.dateAdded }
            SortOrder.TITLE -> null
        }
        groupBySeries(books, seriesIndex, seriesIndex::forLocal, { it.title }, { it.coverUri }, order)
    }
    val activeDownload = downloadProgress?.takeIf { it.state in listOf(DownloadState.DOWNLOADING, DownloadState.WAITING_FOR_WIFI, DownloadState.CONVERTING) }
    val waiting = pending.filter { it.asin != activeDownload?.asin && it.asin !in queuedAsins }
    val hasDownloads = activeDownload != null || queuedAsins.isNotEmpty() || waiting.isNotEmpty()

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
            item(key = "sort") { SortMenu(SortTab.LIBRARY, sortOrder, onSortChange) }
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
                items(waiting, key = { "p_${it.asin}" }) { book ->
                    PendingBookItem(
                        book = book,
                        onWebsite = { onPendingWebsite(book.asin) },
                        onCancel = { onPendingCancel(book.asin) }
                    )
                }
            }
            bookListEntries(
                entries = entries,
                expandedSeries = expandedSeries,
                onToggleSeries = onToggleSeries,
                keyOf = { it.id },
                book = { book ->
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
                },
                missing = { missingSeriesBook(it) }
            )
        }
    }
}

@Composable
private fun PendingBookItem(
    book: PendingDownload,
    onWebsite: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (book.coverUrl != null) {
                AsyncImage(model = book.coverUrl, contentDescription = null,
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
                val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date(book.nextAttemptAt))
                Text("Pending — Audible is limiting downloads. Next try $time",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9800))
                TextButton(onClick = onWebsite) { Text("Download from website") }
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Stop retrying")
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
    pendingUntil: (String) -> Long? = { null },
    isDownloaded: (String) -> Boolean,
    onDownload: (AudibleBookEntity) -> Unit,
    onDelete: (AudibleBookEntity) -> Unit,
    seriesIndex: SeriesIndex,
    expandedSeries: Set<String>,
    onToggleSeries: (String) -> Unit,
    missingSeriesBook: @Composable (SeriesMember<*>) -> Unit,
    sortOrder: SortOrder,
    onSortChange: (SortOrder) -> Unit
) {
    val entries = remember(books, seriesIndex, sortOrder) {
        // Purchase dates are ISO timestamps, so they sort as text
        val order = if (sortOrder == SortOrder.RECENTLY_ADDED) compareByDescending<AudibleBookEntity> { it.purchaseDate.orEmpty() } else null
        groupBySeries(books, seriesIndex, seriesIndex::forAudible, { it.title }, { it.coverUrl }, order)
    }
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
            item(key = "sort") { SortMenu(SortTab.AUDIBLE, sortOrder, onSortChange) }
            bookListEntries(
                entries = entries,
                expandedSeries = expandedSeries,
                onToggleSeries = onToggleSeries,
                keyOf = { it.asin },
                book = { book ->
                    AudibleBookItem(
                        book = book,
                        isDownloaded = isDownloaded(book.asin),
                        downloadState = audibleProgress(book, downloadProgress, queuedAsins),
                        pendingUntil = pendingUntil(book.asin),
                        onDownload = { onDownload(book) },
                        onDelete = { onDelete(book) }
                    )
                },
                missing = { missingSeriesBook(it) }
            )
        }
    }
}

@Composable
private fun AudibleBookItem(
    book: AudibleBookEntity,
    isDownloaded: Boolean,
    downloadState: DownloadProgress?,
    /** Next automatic try when Audible's download limit turned this book away. */
    pendingUntil: Long? = null,
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
                if (pendingUntil != null && !isDownloaded) {
                    val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                        .format(java.util.Date(pendingUntil))
                    Text("Pending — next try $time", style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF9800))
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

/** An entry on the All tab: a book in the local library, or one only on Audible. */
private sealed interface AllBook {
    data class Local(val book: BookEntity) : AllBook
    data class Audible(val book: AudibleBookEntity) : AllBook
}

@Composable
private fun AllBooksList(
    localBooks: List<BookEntity>,
    audibleBooks: List<AudibleBookEntity>,
    onLocalBookClick: (Long) -> Unit,
    onLocalBookDelete: (BookEntity) -> Unit,
    downloadProgress: DownloadProgress?,
    pendingUntil: (String) -> Long? = { null },
    isDownloaded: (String) -> Boolean,
    onDownload: (AudibleBookEntity) -> Unit,
    onLogin: () -> Unit,
    isAuthenticated: Boolean,
    seriesIndex: SeriesIndex,
    expandedSeries: Set<String>,
    onToggleSeries: (String) -> Unit,
    missingSeriesBook: @Composable (SeriesMember<*>) -> Unit,
    sortOrder: SortOrder,
    onSortChange: (SortOrder) -> Unit
) {
    val audibleOnly = audibleBooks.filter { ab -> localBooks.none { it.title == ab.title } }
    // One list, so a series can hold both local and Audible books
    val entries = remember(localBooks, audibleOnly, seriesIndex, sortOrder) {
        // Recently played: local books by last played, then Audible-only books newest purchase first
        val order = if (sortOrder == SortOrder.RECENTLY_PLAYED) {
            compareBy<AllBook> { if (it is AllBook.Local) 0 else 1 }
                .thenByDescending { (it as? AllBook.Local)?.book?.lastPlayed ?: 0L }
                .thenByDescending { (it as? AllBook.Audible)?.book?.purchaseDate.orEmpty() }
        } else null
        groupBySeries<AllBook>(
            items = localBooks.map { AllBook.Local(it) } + audibleOnly.map { AllBook.Audible(it) },
            index = seriesIndex,
            seriesOf = {
                when (it) {
                    is AllBook.Local -> seriesIndex.forLocal(it.book)
                    is AllBook.Audible -> seriesIndex.forAudible(it.book)
                }
            },
            titleOf = {
                when (it) {
                    is AllBook.Local -> it.book.title
                    is AllBook.Audible -> it.book.title
                }
            },
            coverOf = {
                when (it) {
                    is AllBook.Local -> it.book.coverUri
                    is AllBook.Audible -> it.book.coverUrl
                }
            },
            order = order
        )
    }

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
            item(key = "sort") { SortMenu(SortTab.ALL, sortOrder, onSortChange) }
            bookListEntries(
                entries = entries,
                expandedSeries = expandedSeries,
                onToggleSeries = onToggleSeries,
                keyOf = {
                    when (it) {
                        is AllBook.Local -> "local_${it.book.id}"
                        is AllBook.Audible -> "audible_${it.book.asin}"
                    }
                },
                book = { entry ->
                    when (entry) {
                        is AllBook.Local -> LocalBookItem(
                            book = entry.book,
                            onClick = { onLocalBookClick(entry.book.id) },
                            onDelete = { onLocalBookDelete(entry.book) }
                        )
                        is AllBook.Audible -> AudibleBookItem(
                            book = entry.book,
                            isDownloaded = isDownloaded(entry.book.asin),
                            downloadState = if (downloadProgress?.asin == entry.book.asin) downloadProgress else null,
                            pendingUntil = pendingUntil(entry.book.asin),
                            onDownload = { onDownload(entry.book) }
                        )
                    }
                },
                missing = { missingSeriesBook(it) }
            )
        }
    }
}

/** The download state to show for [book]: its active download, or queued. */
private fun audibleProgress(
    book: AudibleBookEntity,
    downloadProgress: DownloadProgress?,
    queuedAsins: Set<String>
): DownloadProgress? = when {
    downloadProgress?.asin == book.asin -> downloadProgress
    book.asin in queuedAsins -> DownloadProgress(book.asin, book.title, 0, 0, DownloadState.QUEUED)
    else -> null
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%d:%02d", minutes, seconds)
}
