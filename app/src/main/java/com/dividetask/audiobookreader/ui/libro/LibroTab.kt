package com.dividetask.audiobookreader.ui.libro

import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.dividetask.audiobookreader.audible.download.AaxImporter
import com.dividetask.audiobookreader.data.db.entity.LibroBookEntity
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.data.series.SeriesIndex
import com.dividetask.audiobookreader.data.series.SeriesRef
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.dividetask.audiobookreader.libro.LibroAccount
import com.dividetask.audiobookreader.libro.LibroRepository
import com.dividetask.audiobookreader.ui.common.SeriesMember
import com.dividetask.audiobookreader.ui.common.SortMenu
import com.dividetask.audiobookreader.ui.common.SortOrder
import com.dividetask.audiobookreader.ui.common.SortTab
import com.dividetask.audiobookreader.ui.common.bookListEntries
import com.dividetask.audiobookreader.ui.common.groupBySeries
import com.dividetask.audiobookreader.ui.common.matchesSearch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibroViewModel @Inject constructor(
    private val repository: LibroRepository,
    account: LibroAccount,
    private val importer: AaxImporter,
    private val bookRepository: BookRepository,
    private val debugLog: AppDebugLog
) : ViewModel() {
    val signedIn = account.signedIn
    val books: StateFlow<List<LibroBookEntity>> =
        repository.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val syncing = repository.syncing
    val queue = importer.libroQueue
    val current = importer.libroIsbn
    val status = importer.status

    /** ISBNs of the Libro.fm books in the library (their files are named libro_<isbn>.m4b). */
    val downloaded: StateFlow<Set<String>> = bookRepository.getAllBooks().map { books ->
        books.mapNotNull { FILE.find(it.fileUri)?.groupValues?.get(1) }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    var error by mutableStateOf<String?>(null)
        private set

    fun sync(force: Boolean = false) {
        viewModelScope.launch { repository.sync(force)?.let { error = it } }
    }

    fun clearError() { error = null }

    fun download(book: LibroBookEntity) = importer.downloadLibro(book)

    /** Removes the downloaded copy from the phone and the library. */
    fun delete(book: LibroBookEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = importer.libroFile(book.isbn)
            bookRepository.getBookByFileUri(Uri.fromFile(file).toString())?.let { bookRepository.deleteBook(it.id) }
            if (file.delete()) debugLog.log("LIBRO", "Deleted file: ${file.name}")
        }
    }

    companion object {
        private val FILE = Regex("/libro_([0-9A-Za-z]+)\\.m4b$")
    }
}

/** Where a Libro.fm book sits in a series: the Audible series it matches, else Libro.fm's own. */
fun libroSeries(index: SeriesIndex, book: LibroBookEntity): SeriesRef? =
    index.forTitle(book.title, book.author)
        ?: book.seriesTitle?.let { SeriesRef("libro:" + SeriesIndex.normalize(it), it, null, book.seriesNum) }

/** The "Libro.fm" tab: books bought on Libro.fm, downloaded into the library like Audible books. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibroTab(
    onOpenSettings: () -> Unit,
    searchQuery: String,
    seriesIndex: SeriesIndex,
    expandedSeries: Set<String>,
    onToggleSeries: (String) -> Unit,
    missingSeriesBook: @Composable (SeriesMember<*>) -> Unit,
    sortOrder: SortOrder,
    onSortChange: (SortOrder) -> Unit,
    viewModel: LibroViewModel = hiltViewModel()
) {
    val signedIn by viewModel.signedIn.collectAsState()
    if (!signedIn) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Not signed in to Libro.fm", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onOpenSettings) { Text("Sign in in Settings") }
            }
        }
        return
    }
    LaunchedEffect(Unit) { viewModel.sync() }

    val allBooks by viewModel.books.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val current by viewModel.current.collectAsState()
    val status by viewModel.status.collectAsState()
    val downloaded by viewModel.downloaded.collectAsState()
    var toDelete by remember { mutableStateOf<LibroBookEntity?>(null) }

    val books = allBooks.filter { matchesSearch(searchQuery, it.title, it.author, it.narrator, it.seriesTitle) }
    val entries = remember(books, seriesIndex, sortOrder) {
        // Added dates are ISO timestamps, so they sort as text
        val order = if (sortOrder == SortOrder.RECENTLY_ADDED) compareByDescending<LibroBookEntity> { it.addedAt.orEmpty() } else null
        groupBySeries(books, seriesIndex, { libroSeries(seriesIndex, it) }, { it.title }, { it.coverUrl }, order)
    }

    PullToRefreshBox(isRefreshing = syncing, onRefresh = { viewModel.sync(force = true) }, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            viewModel.error?.let {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearError() }) { Text("OK") }
                }
            }
            if (allBooks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (syncing) "Loading Libro.fm library…" else "No books in Libro.fm library",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(16.dp)) {
                    item(key = "sort") { SortMenu(SortTab.LIBRO, sortOrder, onSortChange) }
                    bookListEntries(
                        entries = entries,
                        expandedSeries = expandedSeries,
                        onToggleSeries = onToggleSeries,
                        keyOf = { "libro_${it.isbn}" },
                        book = { book ->
                            val fraction = if (current == book.isbn) status?.takeIf { !it.finished }?.progress else null
                            LibroBookItem(
                                book = book,
                                isDownloaded = book.isbn in downloaded,
                                isDownloading = current == book.isbn,
                                isQueued = book.isbn in queue,
                                progress = fraction,
                                step = if (current == book.isbn) status?.takeIf { !it.finished }?.step else null,
                                onDownload = { viewModel.download(book) },
                                onDelete = { toDelete = book }
                            )
                        },
                        missing = { missingSeriesBook(it) }
                    )
                }
            }
        }
    }

    toDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete download") },
            text = { Text("Delete the downloaded copy of \"${book.title}\" from this phone? It stays in your Libro.fm library.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(book); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun LibroBookItem(
    book: LibroBookEntity,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    isQueued: Boolean,
    progress: Float?,
    step: String?,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (book.coverUrl != null) {
                AsyncImage(model = book.coverUrl, contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
            } else {
                Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                book.durationSec?.let {
                    Text("${it / 3600}h ${it % 3600 / 60}m", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isDownloading && step != null) {
                    Text(step + (progress?.let { " — ${(it * 100).toInt()}%" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            when {
                isDownloaded -> IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete download",
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                }
                isDownloading -> Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    if (progress != null) CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                    else CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                }
                isQueued -> Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Refresh, contentDescription = "Queued",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                }
                else -> IconButton(onClick = onDownload) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Download", modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}
