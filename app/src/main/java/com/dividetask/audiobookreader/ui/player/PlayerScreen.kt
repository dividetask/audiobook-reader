package com.dividetask.audiobookreader.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.dividetask.audiobookreader.data.db.entity.ChapterEntity
import com.dividetask.audiobookreader.data.db.entity.ListenSessionEntity
import com.dividetask.audiobookreader.data.db.entity.PositionJumpEntity
import com.dividetask.audiobookreader.ui.common.ChapterSkipIcon
import com.dividetask.audiobookreader.ui.common.PauseIcon
import com.dividetask.audiobookreader.ui.common.SkipSecondsIcon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    bookId: Long,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val book by viewModel.book.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val chapterIndex by viewModel.currentChapterIndex.collectAsState()
    val skipBackSeconds by viewModel.skipBackSeconds.collectAsState()
    val skipForwardSeconds by viewModel.skipForwardSeconds.collectAsState()
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }
    var showChapters by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now Playing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { showChapters = true }, enabled = chapters.isNotEmpty()) {
                        Text("Chapters")
                    }
                    TextButton(onClick = { showHistory = true }) {
                        Text("History")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // File info banner
            book?.let { b ->
                val ext = b.fileUri.substringAfterLast(".")
                val filePath = android.net.Uri.parse(b.fileUri).path ?: ""
                val fileSize = try { java.io.File(filePath).length() / 1_048_576 } catch (_: Exception) { 0L }
                if (ext == "aax") {
                    Text(
                        text = "Awaiting conversion — this book will be playable once converted",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800)
                    )
                } else {
                    Text(
                        text = "$ext · ${fileSize}MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Cover art
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (book?.coverUri != null) {
                    AsyncImage(
                        model = book?.coverUri,
                        contentDescription = "Book cover",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Title and author
            Text(
                text = book?.title ?: "",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = book?.author ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Current chapter
            val chapter = chapters.getOrNull(chapterIndex)
            if (chapter != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { showChapters = true }
                )
                Text(
                    text = "Chapter ${chapterIndex + 1} of ${chapters.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Seek bar — covers the current chapter, or the whole book if there are no chapters
            val rangeStart = chapter?.startPositionMs ?: 0L
            val rangeEnd = (if (chapter != null) chapterEndMs(chapters, chapterIndex, playbackState.durationMs)
                else playbackState.durationMs).coerceAtLeast(rangeStart + 1)
            val shownPosition = if (isSeeking) seekPosition.toLong() else playbackState.currentPositionMs

            Slider(
                value = shownPosition.coerceIn(rangeStart, rangeEnd).toFloat(),
                onValueChange = {
                    isSeeking = true
                    seekPosition = it
                },
                onValueChangeFinished = {
                    viewModel.seekTo(seekPosition.toLong())
                    isSeeking = false
                },
                valueRange = rangeStart.toFloat()..rangeEnd.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration((shownPosition - rangeStart).coerceAtLeast(0)),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "-" + formatDuration((rangeEnd - shownPosition).coerceAtLeast(0)),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (chapter != null) {
                Text(
                    text = "Book ${formatDuration(shownPosition)} / ${formatDuration(playbackState.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Playback controls: previous chapter, skip back, play/pause, skip forward, next chapter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.previousChapter() },
                    enabled = chapters.isNotEmpty(),
                    modifier = Modifier.size(48.dp)
                ) {
                    ChapterSkipIcon(forward = false, modifier = Modifier.size(32.dp))
                }

                IconButton(
                    onClick = { viewModel.skipBackward() },
                    modifier = Modifier.size(56.dp)
                ) {
                    SkipSecondsIcon(
                        seconds = skipBackSeconds,
                        forward = false,
                        modifier = Modifier.size(44.dp)
                    )
                }

                IconButton(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier.size(72.dp)
                ) {
                    if (playbackState.isPlaying) {
                        PauseIcon(modifier = Modifier.size(56.dp))
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play",
                            modifier = Modifier.size(56.dp))
                    }
                }

                IconButton(
                    onClick = { viewModel.skipForward() },
                    modifier = Modifier.size(56.dp)
                ) {
                    SkipSecondsIcon(
                        seconds = skipForwardSeconds,
                        forward = true,
                        modifier = Modifier.size(44.dp)
                    )
                }

                IconButton(
                    onClick = { viewModel.nextChapter() },
                    enabled = chapters.isNotEmpty() && chapterIndex < chapters.lastIndex,
                    modifier = Modifier.size(48.dp)
                ) {
                    ChapterSkipIcon(forward = true, modifier = Modifier.size(32.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Speed control
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Speed:", style = MaterialTheme.typography.bodySmall)
                val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                speeds.forEach { speed ->
                    FilledTonalButton(
                        onClick = { viewModel.setPlaybackSpeed(speed) },
                        modifier = Modifier.height(32.dp),
                        enabled = playbackState.playbackSpeed != speed
                    ) {
                        Text(
                            "${speed}x",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showChapters) {
        ModalBottomSheet(
            onDismissRequest = { showChapters = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            ChapterList(
                chapters = chapters,
                currentIndex = chapterIndex,
                bookDurationMs = playbackState.durationMs,
                onSelect = { index ->
                    viewModel.jumpToChapter(index)
                    showChapters = false
                }
            )
        }
    }

    if (showHistory) {
        val sessions by viewModel.sessions.collectAsState()
        val jumps by viewModel.jumps.collectAsState()
        ModalBottomSheet(
            onDismissRequest = { showHistory = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            HistoryPanel(
                sessions = sessions,
                jumps = jumps,
                chapters = chapters,
                onJump = { positionMs ->
                    viewModel.jumpToHistory(positionMs)
                    showHistory = false
                }
            )
        }
    }
}

@Composable
private fun ChapterList(
    chapters: List<ChapterEntity>,
    currentIndex: Int,
    bookDurationMs: Long,
    onSelect: (Int) -> Unit
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
    Text(
        "Chapters",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    LazyColumn(state = listState) {
        itemsIndexed(chapters) { index, chapter ->
            val isCurrent = index == currentIndex
            val length = chapterEndMs(chapters, index, bookDurationMs) - chapter.startPositionMs
            ListItem(
                headlineContent = {
                    Text(
                        chapter.title,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Text("${formatDuration(chapter.startPositionMs)} · ${formatDuration(length.coerceAtLeast(0))}")
                },
                leadingContent = { Text("${index + 1}") },
                colors = if (isCurrent) {
                    ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    ListItemDefaults.colors()
                },
                modifier = Modifier.clickable { onSelect(index) }
            )
        }
    }
}

@Composable
private fun HistoryPanel(
    sessions: List<ListenSessionEntity>,
    jumps: List<PositionJumpEntity>,
    chapters: List<ChapterEntity>,
    onJump: (Long) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    fun chapterTitle(positionMs: Long): String? =
        chapters.getOrNull(chapterIndexAt(chapters, positionMs))?.title

    TabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sessions") })
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Jumps") })
    }

    if (tab == 0) {
        if (sessions.isEmpty()) {
            EmptyHistory("No listening sessions yet")
        }
        LazyColumn {
            items(sessions, key = { it.id }) { session ->
                val listened = (session.endedAt - session.startedAt).coerceAtLeast(0)
                ListItem(
                    headlineContent = {
                        Text("${formatDuration(session.startPositionMs)} → ${formatDuration(session.endPositionMs)}")
                    },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                "${dateFormat.format(Date(session.startedAt))} · ${formatDuration(listened)}",
                                chapterTitle(session.endPositionMs)
                            ).joinToString("\n"),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { onJump(session.startPositionMs) }) { Text("Start") }
                    },
                    // Tapping the row goes to where this session stopped
                    modifier = Modifier.clickable { onJump(session.endPositionMs) }
                )
            }
        }
    } else {
        if (jumps.isEmpty()) {
            EmptyHistory("No jumps yet")
        }
        LazyColumn {
            items(jumps, key = { it.id }) { jump ->
                ListItem(
                    headlineContent = {
                        Text("${formatDuration(jump.fromPositionMs)} → ${formatDuration(jump.toPositionMs)}")
                    },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                "${jump.reason} · ${dateFormat.format(Date(jump.timestamp))}",
                                chapterTitle(jump.fromPositionMs)?.let { "From: $it" }
                            ).joinToString("\n"),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    // Tapping goes back to where you were before the jump
                    modifier = Modifier.clickable { onJump(jump.fromPositionMs) }
                )
            }
        }
    }
}

@Composable
private fun EmptyHistory(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp)
    )
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
