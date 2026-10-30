package com.dividetask.audiobookreader.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dividetask.audiobookreader.data.series.SeriesIndex
import com.dividetask.audiobookreader.data.series.SeriesRef

/** A row in a book list: one book, or a whole series collapsed into one row. */
sealed interface BookListEntry<out T> {
    data class Single<T>(val item: T) : BookListEntry<T>

    data class Series<T>(
        val seriesAsin: String,
        val title: String,
        val coverUrl: String?,
        val members: List<SeriesMember<T>>,
        /** Members in the Audible library or on this tab. */
        val ownedCount: Int
    ) : BookListEntry<T>
}

/** One title in a series. [item] is this tab's entry for it, or null when the tab doesn't have it. */
data class SeriesMember<out T>(
    val asin: String?,
    val title: String,
    val sequence: String?,
    val coverUrl: String?,
    val item: T?
)

/**
 * Groups [items] by series, filling each series with the titles Audible lists that the
 * tab doesn't have. Books with no known series stay single. Titles in a series sort by
 * sequence number. The list sorts by [order], a series by its first book on the tab;
 * with no [order], books sort by title and a series by its name.
 */
fun <T> groupBySeries(
    items: List<T>,
    index: SeriesIndex,
    seriesOf: (T) -> SeriesRef?,
    titleOf: (T) -> String,
    coverOf: (T) -> String? = { null },
    order: Comparator<T>? = null
): List<BookListEntry<T>> {
    val refs = items.map { it to seriesOf(it) }
    val singles: List<BookListEntry<T>> = refs.filter { it.second == null }.map { BookListEntry.Single(it.first) }
    val groups: List<BookListEntry<T>> = refs.mapNotNull { (item, ref) -> ref?.let { item to it } }
        .groupBy { it.second.seriesAsin }
        .map { (seriesAsin, onTab) ->
            val remaining = onTab.toMutableList()
            val listed = index.booksBySeries[seriesAsin].orEmpty().map { book ->
                val match = remaining.firstOrNull { it.second.asin == book.asin }?.also { remaining.remove(it) }
                SeriesMember(book.asin, book.title, book.sequence, book.coverUrl ?: match?.first?.let(coverOf), match?.first)
            }
            // Books on the tab that the catalog's list doesn't include
            val extra = remaining.map { (item, ref) -> SeriesMember(ref.asin, titleOf(item), ref.sequence, coverOf(item), item) }
            val members = dropRepeats(listed + extra, index).sortedWith(
                compareBy<SeriesMember<T>, Double?>(nullsLast<Double>()) { SeriesIndex.sequenceNumber(it.sequence) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            BookListEntry.Series(
                seriesAsin = seriesAsin,
                title = onTab.first().second.seriesTitle,
                coverUrl = members.firstNotNullOfOrNull { it.coverUrl },
                members = members,
                ownedCount = members.count { m -> m.item != null || m.asin?.let { it in index.libraryByAsin } == true }
            )
        }
    val byName = compareBy<BookListEntry<T>, String>(String.CASE_INSENSITIVE_ORDER) {
        when (it) {
            is BookListEntry.Single -> titleOf(it.item)
            is BookListEntry.Series -> it.title
        }
    }
    if (order == null) return (singles + groups).sortedWith(byName)
    val lead: (BookListEntry<T>) -> T = {
        when (it) {
            is BookListEntry.Single -> it.item
            is BookListEntry.Series -> it.members.mapNotNull { m -> m.item }.minWith(order)
        }
    }
    return (singles + groups).sortedWith(Comparator<BookListEntry<T>> { a, b -> order.compare(lead(a), lead(b)) }.then(byName))
}

/**
 * Audible lists most books in a series several times under different ASINs (other
 * editions, other marketplaces), all with the same title and number. Keeps one row per
 * book: the copies on this tab, else the one in the Audible library, else Audible's first.
 */
private fun <T> dropRepeats(members: List<SeriesMember<T>>, index: SeriesIndex): List<SeriesMember<T>> =
    members.groupBy { SeriesIndex.normalize(it.title) + "|" + it.sequence.orEmpty() }.values.flatMap { same ->
        same.filter { it.item != null }.ifEmpty {
            listOf(same.firstOrNull { m -> m.asin?.let { it in index.libraryByAsin } == true } ?: same.first())
        }
    }

/**
 * Adds [entries] to the list. A series is one row that expands, when tapped, into its
 * titles: [book] draws the ones the tab has, [missing] the rest.
 */
fun <T> LazyListScope.bookListEntries(
    entries: List<BookListEntry<T>>,
    expandedSeries: Set<String>,
    onToggleSeries: (String) -> Unit,
    keyOf: (T) -> Any,
    book: @Composable (T) -> Unit,
    missing: @Composable (SeriesMember<T>) -> Unit
) {
    entries.forEach { entry ->
        when (entry) {
            is BookListEntry.Single -> item(key = keyOf(entry.item)) { book(entry.item) }
            is BookListEntry.Series -> {
                val expanded = entry.seriesAsin in expandedSeries
                item(key = "series_${entry.seriesAsin}") {
                    SeriesHeaderItem(entry, expanded, onClick = { onToggleSeries(entry.seriesAsin) })
                }
                if (expanded) {
                    entry.members.forEach { member ->
                        val tabItem = member.item
                        item(key = tabItem?.let(keyOf) ?: "series_${entry.seriesAsin}_${member.asin}") {
                            SeriesMemberRow(member.sequence) {
                                if (tabItem != null) book(tabItem) else missing(member)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesHeaderItem(series: BookListEntry.Series<*>, expanded: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (series.coverUrl != null) {
                AsyncImage(model = series.coverUrl, contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop)
            } else {
                Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(series.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val count = series.members.size
                Text("Series · $count ${if (count == 1) "book" else "books"} · ${series.ownedCount} owned",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse series" else "Expand series"
            )
        }
    }
}

/** Indents a series title under its header, with its number in the series. */
@Composable
private fun SeriesMemberRow(sequence: String?, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            sequence?.let { "#$it" } ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(36.dp)
        )
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

/** A series title the tab doesn't have, e.g. one not purchased. [action] goes at the end of the row. */
@Composable
fun SeriesPlaceholderItem(
    member: SeriesMember<*>,
    status: String,
    action: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (member.coverUrl != null) {
                AsyncImage(model = member.coverUrl, contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).alpha(0.5f),
                    contentScale = ContentScale.Crop)
            } else {
                Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(member.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action?.invoke()
        }
    }
}
