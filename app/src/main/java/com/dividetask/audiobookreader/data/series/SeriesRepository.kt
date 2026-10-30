package com.dividetask.audiobookreader.data.series

import com.dividetask.audiobookreader.audible.api.AudibleApiClient
import com.dividetask.audiobookreader.audible.auth.AudibleTokenStore
import com.dividetask.audiobookreader.data.db.dao.AudibleBookDao
import com.dividetask.audiobookreader.data.db.dao.SeriesDao
import com.dividetask.audiobookreader.data.db.entity.AudibleBookEntity
import com.dividetask.audiobookreader.data.db.entity.SeriesBookEntity
import com.dividetask.audiobookreader.data.db.entity.SeriesEntity
import com.dividetask.audiobookreader.data.db.entity.SeriesLookupEntity
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.dividetask.audiobookreader.remote.RemoteLibraryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a local copy of every series the user's books belong to, including the titles
 * they don't own, so the lists can group books by series offline. Runs after each
 * Audible library sync.
 */
@Singleton
class SeriesRepository @Inject constructor(
    private val seriesDao: SeriesDao,
    private val audibleBookDao: AudibleBookDao,
    private val bookRepository: BookRepository,
    private val remoteLibrary: RemoteLibraryManager,
    private val apiClient: AudibleApiClient,
    private val tokenStore: AudibleTokenStore,
    private val debugLog: AppDebugLog
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    val index: Flow<SeriesIndex> = combine(
        audibleBookDao.getAll(),
        seriesDao.getAllBooks(),
        seriesDao.getAllLookups(),
        seriesDao.getAllSeries()
    ) { library, books, lookups, series -> SeriesIndex(library, books, lookups, series) }

    /** Fetches new series and refreshes stale ones in the background. */
    fun refresh() {
        if (!tokenStore.isAuthenticated || !running.compareAndSet(false, true)) return
        scope.launch {
            try {
                sync()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                debugLog.log("SERIES", "Sync failed: ${e.message}")
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun sync() {
        debugLog.section("Series Sync")
        val now = System.currentTimeMillis()
        val library = audibleBookDao.getAllOnce()
        val index = SeriesIndex(library)
        logDuplicates(library)

        // Books that aren't in the Audible library: find them in the catalog
        val known = seriesDao.getAllLookupsOnce().associateBy { it.key }
        val unmatched = buildList {
            bookRepository.getAllBooksOnce()
                .filter { book ->
                    SeriesIndex.asinFromFileUri(book.fileUri)?.let { it in index.libraryByAsin } != true &&
                        index.matchLibrary(book.title, book.author) == null
                }
                .forEach { add(it.title to it.author) }
            remoteLibrary.files.value
                .filter { index.matchLibrary(it.title, it.folder) == null }
                .forEach { add(it.title to it.folder.ifEmpty { null }) }
        }.distinctBy { (title, author) -> SeriesIndex.lookupKey(title, author) }

        var lookedUp = 0
        for ((title, author) in unmatched) {
            val key = SeriesIndex.lookupKey(title, author)
            val previous = known[key]
            // A match is kept; a miss is tried again after a week in case the catalog changed
            if (previous != null && (previous.asin != null || now - previous.checkedAt < MISS_RETRY_MS)) continue
            lookUp(key, title, author)?.let {
                seriesDao.insertLookup(it)
                lookedUp++
            }
        }

        val wanted = library.mapNotNull { book -> book.seriesAsin?.let { it to book.seriesTitle } } +
            seriesDao.getAllLookupsOnce().mapNotNull { l -> l.seriesAsin?.let { it to l.seriesTitle } }
        val titles = wanted.toMap()
        val synced = seriesDao.getAllSeriesOnce().associate { it.seriesAsin to it.lastSynced }
        val due = titles.keys.filter { now - (synced[it] ?: 0L) > SERIES_REFRESH_MS }
        var fetched = 0
        for (seriesAsin in due) {
            if (fetchSeries(seriesAsin, titles[seriesAsin])) fetched++
        }
        debugLog.log("SERIES", "Looked up $lookedUp of ${unmatched.size} non-Audible books; fetched $fetched of ${due.size} due series (${titles.size} total)")
    }

    /** Stores every title in the series. False when Audible couldn't be reached. */
    private suspend fun fetchSeries(seriesAsin: String, fallbackTitle: String?): Boolean {
        val result = apiClient.getCatalog(
            "products/$seriesAsin",
            mapOf("response_groups" to "relationships,product_attrs")
        )
        val product = result.data?.get("product").obj()
        if (product == null) {
            debugLog.log("SERIES", "$seriesAsin: ${result.error ?: "no product"}")
            return false
        }
        val sequences = product["relationships"].arr().mapNotNull { it.obj() }
            .filter { it["relationship_to_product"].str() == "child" }
            .mapNotNull { child -> child["asin"].str()?.let { it to child["sequence"].str() } }
            .distinctBy { it.first }

        val info = mutableMapOf<String, JsonObject>()
        for (chunk in sequences.map { it.first }.chunked(50)) {
            val page = apiClient.getCatalog(
                "products",
                mapOf(
                    "asins" to chunk.joinToString(","),
                    "response_groups" to "media,product_attrs,contributors",
                    "image_sizes" to "500"
                )
            )
            if (page.data == null) return false
            page.data["products"].arr().forEach { p ->
                val o = p.obj() ?: return@forEach
                o["asin"].str()?.let { info[it] = o }
            }
        }
        // Every relationship as Audible sent it, to find what marks the titles its app hides
        product["relationships"].arr().mapNotNull { it.obj() }.forEach { rel ->
            val details = rel["asin"].str()?.let { info[it] }?.let { JsonObject(it - LOG_SKIP_FIELDS) }
            debugLog.log("SERIES", "$seriesAsin relationship $rel -> ${details ?: "no details"}")
        }
        // Titles the catalog has no details for (not sold in this marketplace) are left out
        val books = sequences.mapNotNull { (asin, sequence) ->
            val p = info[asin] ?: return@mapNotNull null
            SeriesBookEntity(
                seriesAsin = seriesAsin,
                asin = asin,
                title = p["title"].str() ?: return@mapNotNull null,
                author = p["authors"].arr().firstOrNull().obj()?.get("name").str(),
                sequence = sequence,
                coverUrl = p["product_images"].obj()?.get("500").str()
            )
        }
        val title = product["title"].str() ?: fallbackTitle ?: seriesAsin
        seriesDao.replaceSeries(SeriesEntity(seriesAsin, title), books)
        return true
    }

    /**
     * Logs series whose stored list leaves out an owned copy or repeats a title,
     * the cases that show a book twice.
     */
    private suspend fun logDuplicates(library: List<AudibleBookEntity>) {
        val stored = seriesDao.getAllBooksOnce().groupBy { it.seriesAsin }
        library.filter { it.seriesAsin != null }.groupBy { it.seriesAsin!! }.forEach { (seriesAsin, owned) ->
            val listed = stored[seriesAsin] ?: return@forEach
            val listedAsins = listed.map { it.asin }.toSet()
            val notListed = owned.filter { it.asin !in listedAsins }
            val repeats = listed.groupBy { SeriesIndex.normalize(it.title) + "|" + it.sequence }.values.filter { it.size > 1 }
            if (notListed.isEmpty() && repeats.isEmpty()) return@forEach
            debugLog.log("SERIES", "$seriesAsin ${owned.first().seriesTitle}: ${listed.size} listed, owned ${owned.joinToString { "${it.asin} #${it.seriesSequence}" }}")
            notListed.forEach { book ->
                val sameTitle = listed.filter { SeriesIndex.normalize(it.title) == SeriesIndex.normalize(book.title) }
                debugLog.log("SERIES", "  owned ${book.asin} ${book.title} #${book.seriesSequence} not in list; listed with that title: ${sameTitle.joinToString { "${it.asin} #${it.sequence}" }.ifEmpty { "none" }}")
            }
            repeats.forEach { books ->
                debugLog.log("SERIES", "  listed twice: ${books.first().title} #${books.first().sequence} as ${books.joinToString { it.asin }}")
            }
        }
    }

    /** Searches the catalog for the book. Null when Audible couldn't be reached. */
    private suspend fun lookUp(key: String, title: String, author: String?): SeriesLookupEntity? {
        val params = mutableMapOf(
            "title" to title,
            "num_results" to "10",
            "products_sort_by" to "Relevance",
            "response_groups" to "series,contributors,product_attrs"
        )
        author?.takeUnless { it.isBlank() || it.startsWith("Unknown") }?.let { params["author"] = it }
        val result = apiClient.getCatalog("products", params)
        val data = result.data ?: run {
            debugLog.log("SERIES", "Lookup failed for $title: ${result.error}")
            return null
        }
        val products = data["products"].arr().mapNotNull { it.obj() }
        val wanted = SeriesIndex.normalize(title)
        val match = products.firstOrNull { SeriesIndex.normalize(it["title"].str().orEmpty()) == wanted }
            ?: products.firstOrNull {
                val t = SeriesIndex.normalize(it["title"].str().orEmpty())
                // Prefix matches only for longer titles, so "It" doesn't match "It Ends with Us"
                t.length >= 8 && wanted.length >= 8 && (t.startsWith(wanted) || wanted.startsWith(t))
            }
        val series = match?.get("series").arr().firstOrNull().obj()
        debugLog.log("SERIES", "Lookup $title: ${match?.let { "${it["asin"].str()} / ${series?.get("title").str() ?: "no series"}" } ?: "no match"}")
        return SeriesLookupEntity(
            key = key,
            asin = match?.get("asin").str(),
            seriesAsin = series?.get("asin").str(),
            seriesTitle = series?.get("title").str(),
            sequence = series?.get("sequence").str()
        )
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private companion object {
        const val SERIES_REFRESH_MS = 24 * 60 * 60 * 1000L
        // Long or bulky product fields left out of the relationship log
        val LOG_SKIP_FIELDS = setOf("product_images", "publisher_summary", "merchandising_summary")
        const val MISS_RETRY_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
