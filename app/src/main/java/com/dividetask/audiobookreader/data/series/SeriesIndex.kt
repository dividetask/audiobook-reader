package com.dividetask.audiobookreader.data.series

import com.dividetask.audiobookreader.data.db.entity.AudibleBookEntity
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.db.entity.SeriesBookEntity
import com.dividetask.audiobookreader.data.db.entity.SeriesEntity
import com.dividetask.audiobookreader.data.db.entity.SeriesLookupEntity

/** Where a book sits in a series. [asin] is the book's Audible ASIN when known. */
data class SeriesRef(
    val seriesAsin: String,
    val seriesTitle: String,
    val asin: String?,
    val sequence: String?
)

/** Everything stored about series, indexed for building the book lists. */
class SeriesIndex(
    library: List<AudibleBookEntity> = emptyList(),
    seriesBooks: List<SeriesBookEntity> = emptyList(),
    lookups: List<SeriesLookupEntity> = emptyList(),
    series: List<SeriesEntity> = emptyList()
) {
    val libraryByAsin: Map<String, AudibleBookEntity> = library.associateBy { it.asin }
    private val libraryByTitle = library.groupBy { normalize(it.title) }
    private val lookupsByKey = lookups.associateBy { it.key }
    private val seriesTitles = series.associate { it.seriesAsin to it.title }

    /** Every title Audible lists for each series, owned or not. */
    val booksBySeries: Map<String, List<SeriesBookEntity>> = seriesBooks.groupBy { it.seriesAsin }
    private val seriesBooksByAsin = seriesBooks.associateBy { it.asin }
    private val seriesBooksByTitle = seriesBooks.groupBy { normalize(it.title) }

    fun forAudible(book: AudibleBookEntity): SeriesRef? {
        val seriesAsin = book.seriesAsin ?: return null
        val title = seriesTitles[seriesAsin] ?: book.seriesTitle ?: return null
        return SeriesRef(seriesAsin, title, book.asin, book.seriesSequence)
    }

    /**
     * Audible downloads are named after their ASIN; other files may carry one in their
     * tags. Anything else is matched by title.
     */
    fun forLocal(book: BookEntity): SeriesRef? {
        asinFromFileUri(book.fileUri)?.let { libraryByAsin[it] }?.let { return forAudible(it) }
        book.asin?.takeIf { it.isNotEmpty() }?.let { asin ->
            libraryByAsin[asin]?.let { return forAudible(it) }
            seriesBooksByAsin[asin]?.let { return fromSeriesBook(it) }
            lookupsByKey[asinKey(asin)]?.let(::fromLookup)?.let { return it }
        }
        return forTitle(book.title, book.author)
    }

    /**
     * A book known only by title and author: matched to the Audible library, else to a
     * title in a series already fetched, else to a catalog lookup.
     */
    fun forTitle(title: String, author: String?): SeriesRef? {
        matchLibrary(title, author)?.let { return forAudible(it) }
        matchSeriesList(title, author)?.let { return fromSeriesBook(it) }
        return lookupsByKey[lookupKey(title, author)]?.let(::fromLookup)
    }

    /**
     * A title in a fetched series list (e.g. a book bought elsewhere in a series the user
     * has on Audible). The author must match when both are known, since short titles repeat.
     */
    fun matchSeriesList(title: String, author: String?): SeriesBookEntity? {
        val wanted = author?.takeUnless { it.isBlank() || it.startsWith("Unknown") }?.let(::normalize)
        return seriesBooksByTitle[normalize(title)].orEmpty().firstOrNull { book ->
            wanted == null || book.author == null || normalize(book.author) == wanted
        }
    }

    fun inSeriesList(asin: String): Boolean = asin in seriesBooksByAsin

    private fun fromSeriesBook(book: SeriesBookEntity): SeriesRef? {
        val seriesTitle = seriesTitles[book.seriesAsin] ?: return null
        return SeriesRef(book.seriesAsin, seriesTitle, book.asin, book.sequence)
    }

    private fun fromLookup(lookup: SeriesLookupEntity): SeriesRef? {
        val seriesAsin = lookup.seriesAsin ?: return null
        val seriesTitle = seriesTitles[seriesAsin] ?: lookup.seriesTitle ?: return null
        return SeriesRef(seriesAsin, seriesTitle, lookup.asin, lookup.sequence)
    }

    fun matchLibrary(title: String, author: String?): AudibleBookEntity? {
        val matches = libraryByTitle[normalize(title)].orEmpty()
        if (matches.size <= 1) return matches.firstOrNull()
        val wanted = author?.let(::normalize)
        return matches.firstOrNull { normalize(it.author) == wanted } ?: matches.first()
    }

    companion object {
        private val FILE_ASIN = Regex("/audiobooks/([A-Za-z0-9]{10})\\.")
        private val NUMBER = Regex("\\d+(\\.\\d+)?")

        fun normalize(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

        fun lookupKey(title: String, author: String?): String =
            normalize(title) + "|" + normalize(author.orEmpty())

        /** Lookup key for a book found by the ASIN in its tags. */
        fun asinKey(asin: String): String = "asin:$asin"

        fun asinFromFileUri(fileUri: String): String? = FILE_ASIN.find(fileUri)?.groupValues?.get(1)

        /** "3", "3.5" or "Book 3" → 3.0; unnumbered entries sort last. */
        fun sequenceNumber(sequence: String?): Double? =
            sequence?.let { NUMBER.find(it)?.value?.toDoubleOrNull() }
    }
}
