package com.dividetask.audiobookreader.ui.audible

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.dividetask.audiobookreader.audible.api.AudibleBook
import com.dividetask.audiobookreader.audible.download.DownloadState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudibleLibraryScreen(
    onBack: () -> Unit,
    viewModel: AudibleViewModel = hiltViewModel()
) {
    val library by viewModel.library.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val debugLog by viewModel.debugLog.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        if (viewModel.isAuthenticated && library.isEmpty()) {
            viewModel.loadLibrary()
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audible Library") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.signOut()
                        onBack()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Sign Out")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            downloadProgress?.let { progress ->
                if (progress.state != DownloadState.QUEUED) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = when (progress.state) {
                            DownloadState.ERROR -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            DownloadState.COMPLETE -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            else -> CardDefaults.cardColors()
                        }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                when (progress.state) {
                                    DownloadState.ERROR -> "Download failed: ${progress.title}"
                                    DownloadState.COMPLETE -> "Downloaded: ${progress.title} (${progress.bytesDownloaded / 1_048_576}MB)"
                                    else -> "Downloading: ${progress.title}"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (progress.state == DownloadState.DOWNLOADING) {
                                if (progress.totalBytes > 0) {
                                    LinearProgressIndicator(
                                        progress = { progress.bytesDownloaded.toFloat() / progress.totalBytes.toFloat() },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        "${progress.bytesDownloaded / 1_048_576}MB / ${progress.totalBytes / 1_048_576}MB",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                            if (progress.debugLog.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                SelectionContainer {
                                    Text(
                                        text = progress.debugLog.joinToString("\n"),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isLoading && library.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Loading your Audible library...")
                    }
                }
            } else if (library.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "No books found",
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (debugLog.isNotEmpty()) {
                        val context = LocalContext.current
                        val debugText = debugLog.joinToString("\n")

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Debug Log",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Debug Log", debugText))
                                Toast.makeText(context, "Debug log copied", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Copy debug log",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            SelectionContainer {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = debugText,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    items(library, key = { it.asin }) { book ->
                        AudibleBookItem(
                            book = book,
                            isDownloaded = viewModel.isDownloaded(book.asin),
                            isDownloading = downloadProgress?.asin == book.asin &&
                                    downloadProgress?.state == DownloadState.DOWNLOADING,
                            onDownload = { viewModel.downloadBook(book) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudibleBookItem(
    book: AudibleBook,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val coverUrl = book.productImages?.image500
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = "Cover for ${book.title}",
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                book.authors?.firstOrNull()?.let { author ->
                    Text(
                        text = author.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                book.runtimeLengthMin?.let { minutes ->
                    val hours = minutes / 60
                    val mins = minutes % 60
                    Text(
                        text = "${hours}h ${mins}m",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when {
                isDownloaded -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                isDownloading -> {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
                else -> {
                    IconButton(onClick = onDownload) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Download",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}
