package com.dividetask.audiobookreader.ui.details

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.dividetask.audiobookreader.audible.download.DownloadState
import com.dividetask.audiobookreader.data.details.AudibleDetails
import com.dividetask.audiobookreader.data.details.AudibleReview
import com.dividetask.audiobookreader.data.details.HardcoverBook
import com.dividetask.audiobookreader.data.details.HardcoverReview
import com.dividetask.audiobookreader.data.details.Section
import com.dividetask.audiobookreader.data.details.SeriesBook
import com.dividetask.audiobookreader.data.details.StoreAvailability

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailsScreen(
    onBack: () -> Unit,
    onPlay: (bookId: Long) -> Unit,
    onOpenBook: (asin: String) -> Unit,
    viewModel: BookDetailsViewModel = hiltViewModel()
) {
    val audible by viewModel.audible.collectAsState()
    val series by viewModel.series.collectAsState()
    val stores by viewModel.stores.collectAsState()
    val hardcover by viewModel.hardcover.collectAsState()
    val reviewer by viewModel.reviewer.collectAsState()
    val localBook by viewModel.localBook.collectAsState()
    val owned by viewModel.owned.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val queuedAsins by viewModel.queuedAsins.collectAsState()
    var showChapters by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Book details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(horizontal = 16.dp)
        ) {
            when (val a = audible) {
                Section.Loading -> Loading("Loading from Audible…")
                is Section.Failed -> Message(a.message, error = true)
                is Section.Unavailable -> Message(a.message)
                is Section.Loaded -> Header(a.value)
            }

            // Actions
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val local = localBook
                if (local != null) {
                    Button(onClick = { onPlay(local.id) }) {
                        Text(if (local.currentPositionMs > 0) "Resume" else "Play")
                    }
                } else if (owned != null) {
                    val progress = downloadProgress?.takeIf { it.asin == viewModel.asin }
                    when {
                        viewModel.asin in queuedAsins -> OutlinedButton(onClick = {}, enabled = false) { Text("Queued") }
                        progress?.state == DownloadState.DOWNLOADING -> OutlinedButton(onClick = {}, enabled = false) {
                            val total = progress?.totalBytes ?: 0
                            val pct = if (total > 0) (progress?.bytesDownloaded ?: 0) * 100 / total else 0
                            Text("Downloading $pct%")
                        }
                        progress?.state == DownloadState.CONVERTING -> OutlinedButton(onClick = {}, enabled = false) { Text("Converting…") }
                        else -> Button(onClick = { viewModel.download() }) { Text("Download") }
                    }
                } else {
                    Text(
                        "Not in your Audible library",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
                if (local != null) {
                    OutlinedButton(onClick = { showChapters = true }, enabled = chapters.isNotEmpty()) {
                        Text(if (chapters.isEmpty()) "No chapters" else "Chapters (${chapters.size})")
                    }
                }
            }

            (audible as? Section.Loaded)?.value?.let { details ->
                details.rating?.let { RatingBlock(it.overall, it.count, it.performance, it.story) }
                details.summary?.let { SummaryBlock(it) }
                if (details.genres.isNotEmpty()) GenresBlock(details.genres)
            }

            // Series
            series?.let { s ->
                val ref = (audible as? Section.Loaded)?.value?.series?.firstOrNull()
                SectionTitle(listOfNotNull(ref?.title, ref?.sequence?.let { "Book $it" }).joinToString(", ").ifEmpty { "Series" })
                when (s) {
                    Section.Loading -> Loading("Loading series…")
                    is Section.Failed -> Message(s.message, error = true)
                    is Section.Unavailable -> Message(s.message)
                    is Section.Loaded -> s.value.forEach { book ->
                        SeriesRow(book, isCurrent = book.asin == viewModel.asin, onClick = { onOpenBook(book.asin) })
                    }
                }
            }

            // Where it's sold
            SectionTitle("Sold on")
            when (val st = stores) {
                Section.Loading -> Loading("Checking stores…")
                is Section.Failed -> Message(st.message, error = true)
                is Section.Unavailable -> Message(st.message)
                is Section.Loaded -> st.value.forEach { StoreRow(it) }
            }

            // Hardcover reviews
            SectionTitle("Hardcover reviews")
            when (val h = hardcover) {
                Section.Loading -> Loading("Loading Hardcover…")
                is Section.Failed -> Message(h.message, error = true)
                is Section.Unavailable -> Message(h.message)
                is Section.Loaded -> HardcoverBlock(h.value, onReviewer = { viewModel.showReviewer(it) })
            }

            // Audible reviews
            (audible as? Section.Loaded)?.value?.let { details ->
                SectionTitle("Audible reviews")
                if (details.reviews.isEmpty()) {
                    Message("Audible didn't return any reviews")
                } else {
                    details.reviews.forEach { AudibleReviewCard(it) }
                }
                RawDataBlock(details.raw)
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showChapters) {
        ModalBottomSheet(
            onDismissRequest = { showChapters = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            val canJump = viewModel.isPlayingThisBook
            Text(
                if (canJump) "Chapters" else "Chapters — start this book in the player to jump to one",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn {
                items(chapters, key = { it.id }) { chapter ->
                    ListItem(
                        headlineContent = { Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(formatMs(chapter.startPositionMs)) },
                        leadingContent = { Text("${chapter.index + 1}") },
                        modifier = if (canJump) Modifier.clickable {
                            viewModel.jumpToChapter(chapter)
                            showChapters = false
                        } else Modifier
                    )
                }
            }
        }
    }

    reviewer?.let { r ->
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeReviewer() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Text(
                "Reviews by ${r.username}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            when (val reviews = r.reviews) {
                Section.Loading -> Box(Modifier.padding(16.dp)) { Loading("Loading…") }
                is Section.Failed -> Box(Modifier.padding(16.dp)) { Message(reviews.message, error = true) }
                is Section.Unavailable -> Box(Modifier.padding(16.dp)) { Message(reviews.message) }
                is Section.Loaded -> LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
                    items(reviews.value) { review ->
                        ReviewCard(
                            heading = review.bookTitle,
                            meta = listOfNotNull(review.rating?.let { stars(it) }, review.date).joinToString(" · "),
                            text = review.text
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Header(details: AudibleDetails) {
    // Landscape: cover on the left, facts on the right, so the cover doesn't fill the screen
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            details.coverUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = "Cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(180.dp).clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(24.dp))
            }
            Column(Modifier.weight(1f)) { HeaderText(details, TextAlign.Start) }
        }
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        details.coverUrl?.let {
            AsyncImage(
                model = it,
                contentDescription = "Cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.height(16.dp))
        }
        HeaderText(details, TextAlign.Center)
    }
}

@Composable
private fun HeaderText(details: AudibleDetails, align: TextAlign) {
    Column(
        horizontalAlignment = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(details.title, style = MaterialTheme.typography.headlineSmall, textAlign = align)
        details.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = align,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        if (details.authors.isNotEmpty()) Text("By ${details.authors.joinToString()}", textAlign = align)
        if (details.narrators.isNotEmpty()) {
            Text("Narrated by ${details.narrators.joinToString()}", textAlign = align,
                style = MaterialTheme.typography.bodyMedium)
        }
        val facts = listOfNotNull(
            details.runtimeMinutes?.let { "${it / 60}h ${it % 60}m" },
            details.releaseDate?.let { "Released $it" },
            details.publisher,
            details.language?.replaceFirstChar { it.uppercase() }
        )
        if (facts.isNotEmpty()) {
            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, textAlign = align,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RatingBlock(overall: Double?, count: Int?, performance: Double?, story: Double?) {
    SectionTitle("Audible rating")
    Text(listOfNotNull(
        overall?.let { stars(it) },
        count?.let { "%,d ratings".format(it) }
    ).joinToString(" · "))
    val parts = listOfNotNull(
        performance?.let { "Performance ${"%.1f".format(it)}" },
        story?.let { "Story ${"%.1f".format(it)}" }
    )
    if (parts.isNotEmpty()) {
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SummaryBlock(summary: String) {
    var expanded by remember { mutableStateOf(false) }
    SectionTitle("Summary")
    Text(summary, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Read more") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenresBlock(genres: List<String>) {
    SectionTitle("Genres")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        genres.forEach { SuggestionChip(onClick = {}, label = { Text(it) }) }
    }
}

@Composable
private fun SeriesRow(book: SeriesBook, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isCurrent, onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Box(Modifier.size(48.dp)) {
            book.coverUrl?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(book.sequence?.let { "$it." }, book.title).joinToString(" "),
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            val status = when {
                isCurrent -> "This book"
                book.downloaded -> "Downloaded"
                book.owned -> "In your Audible library"
                else -> null
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StoreRow(store: StoreAvailability) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(store.store, modifier = Modifier.weight(1f))
        Text(
            when (store.sold) {
                true -> "✓ Sold"
                false -> "Not found"
                null -> "Unknown"
            },
            color = when (store.sold) {
                true -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
    store.note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HardcoverBlock(book: HardcoverBook, onReviewer: (HardcoverReview) -> Unit) {
    Text(listOfNotNull(
        book.rating?.let { stars(it) },
        book.ratingsCount?.let { "%,d ratings".format(it) }
    ).joinToString(" · ").ifEmpty { book.title })
    if (book.reviews.isEmpty()) {
        Message("No written reviews on Hardcover yet")
        return
    }
    Text(
        "Tap a reviewer to see their other reviews",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    book.reviews.forEach { review ->
        ReviewCard(
            heading = review.username,
            meta = listOfNotNull(review.rating?.let { stars(it) }, review.date).joinToString(" · "),
            text = review.text,
            spoilers = review.spoilers,
            onHeadingClick = if (review.userId != null) ({ onReviewer(review) }) else null
        )
    }
}

@Composable
private fun AudibleReviewCard(review: AudibleReview) {
    val ratings = listOfNotNull(
        review.overall?.let { stars(it) },
        review.performance?.let { "Performance ${"%.0f".format(it)}" },
        review.story?.let { "Story ${"%.0f".format(it)}" },
        review.date
    )
    ReviewCard(
        heading = listOfNotNull(review.title, review.author?.let { "— $it" }).joinToString(" ").ifEmpty { "Audible listener" },
        meta = ratings.joinToString(" · "),
        text = review.body
    )
}

@Composable
private fun ReviewCard(
    heading: String,
    meta: String,
    text: String,
    spoilers: Boolean = false,
    onHeadingClick: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(!spoilers) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                heading,
                style = MaterialTheme.typography.titleSmall,
                color = if (onHeadingClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = if (onHeadingClick != null) Modifier.clickable(onClick = onHeadingClick) else Modifier
            )
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            if (!revealed) {
                TextButton(onClick = { revealed = true }) { Text("Contains spoilers — tap to show") }
            } else {
                Text(
                    text,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            }
        }
    }
}

@Composable
private fun RawDataBlock(raw: List<Pair<String, String>>) {
    var expanded by remember { mutableStateOf(false) }
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide all Audible data" else "Show all Audible data (${raw.size} fields)")
    }
    if (expanded) {
        raw.forEach { (key, value) ->
            Text(
                "$key: $value",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Loading(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Message(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

private fun stars(rating: Double): String = "★ %.1f".format(rating)

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
