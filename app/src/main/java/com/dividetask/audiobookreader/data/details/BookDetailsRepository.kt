package com.dividetask.audiobookreader.data.details

import android.util.Base64
import androidx.core.text.HtmlCompat
import com.dividetask.audiobookreader.audible.api.AudibleApiClient
import com.dividetask.audiobookreader.audible.auth.AudibleTokenStore
import com.dividetask.audiobookreader.audible.download.AudibleDownloadManager
import com.dividetask.audiobookreader.data.db.dao.AudibleBookDao
import com.dividetask.audiobookreader.debug.AppDebugLog
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the book details screen shows that isn't in the local database:
 * Audible catalog (details, reviews, series), store availability (Apple, Spotify)
 * and Hardcover community reviews.
 */
@Singleton
class BookDetailsRepository @Inject constructor(
    private val audibleApi: AudibleApiClient,
    private val httpClient: HttpClient,
    private val tokenStore: AudibleTokenStore,
    private val audibleBookDao: AudibleBookDao,
    private val downloadManager: AudibleDownloadManager,
    private val keys: ServiceKeys,
    private val debugLog: AppDebugLog
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val detailsCache = mutableMapOf<String, AudibleDetails>()

    // ---------------------------------------------------------------- Audible

    suspend fun audibleDetails(asin: String): Section<AudibleDetails> {
        detailsCache[asin]?.let { return Section.Loaded(it) }
        val base = "contributors,media,product_attrs,product_desc,product_extended_attrs,rating,series,category_ladders,relationships"
        val common = mapOf("image_sizes" to "500,1024")
        // Reviews are an older, undocumented group — fall back without them if Audible rejects it
        val withReviews = audibleApi.getCatalog(
            "products/$asin",
            common + mapOf(
                "response_groups" to "$base,review_attrs,reviews",
                "reviews_num_results" to "10",
                "reviews_sort_by" to "MostHelpful"
            )
        )
        val result = if (withReviews.data != null) withReviews else {
            debugLog.log("DETAILS", "Catalog with reviews failed: ${withReviews.error}")
            audibleApi.getCatalog("products/$asin", common + mapOf("response_groups" to base))
        }
        val root = result.data ?: return Section.Failed(result.error ?: "Couldn't load Audible details")
        val product = root["product"].obj() ?: return Section.Failed("Audible returned no product for $asin")

        val details = AudibleDetails(
            asin = asin,
            title = product["title"].str() ?: asin,
            subtitle = product["subtitle"].str(),
            authors = product["authors"].arr().mapNotNull { it.obj()?.get("name").str() },
            narrators = product["narrators"].arr().mapNotNull { it.obj()?.get("name").str() },
            publisher = product["publisher_name"].str(),
            releaseDate = product["release_date"].str() ?: product["issue_date"].str(),
            runtimeMinutes = product["runtime_length_min"].num()?.toInt(),
            language = product["language"].str(),
            coverUrl = product["product_images"].obj()?.let { it["1024"].str() ?: it["500"].str() },
            summary = listOf("publisher_summary", "extended_product_description", "merchandising_summary")
                .firstNotNullOfOrNull { product[it].str()?.takeIf(String::isNotBlank) }
                ?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() },
            rating = product["rating"].obj()?.let { r ->
                AudibleRating(
                    overall = r["overall_distribution"].obj()?.average(),
                    count = r["overall_distribution"].obj()?.get("num_ratings").num()?.toInt()
                        ?: r["num_reviews"].num()?.toInt(),
                    performance = r["performance_distribution"].obj()?.average(),
                    story = r["story_distribution"].obj()?.average()
                )
            },
            genres = product["category_ladders"].arr().mapNotNull { ladder ->
                ladder.obj()?.get("ladder").arr().mapNotNull { it.obj()?.get("name").str() }
                    .takeIf { it.isNotEmpty() }?.joinToString(" › ")
            }.distinct(),
            series = product["series"].arr().mapNotNull { s ->
                val o = s.obj() ?: return@mapNotNull null
                SeriesRef(o["asin"].str(), o["title"].str() ?: return@mapNotNull null, o["sequence"].str())
            },
            reviews = (product["customer_reviews"] ?: root["customer_reviews"] ?: product["reviews"])
                .arr().mapNotNull { parseAudibleReview(it.obj()) },
            raw = buildList { flatten(product, "", this) }
        )
        detailsCache[asin] = details
        return Section.Loaded(details)
    }

    private fun JsonObject.average(): Double? =
        this["display_average_rating"].num() ?: this["average_rating"].num()

    private fun parseAudibleReview(o: JsonObject?): AudibleReview? {
        o ?: return null
        val body = (o["body"] ?: o["review_text"] ?: o["text"]).str()?.takeIf(String::isNotBlank) ?: return null
        val ratings = o["ratings"].obj()
        return AudibleReview(
            author = (o["author_name"] ?: o["author"] ?: o["customer_name"]).str(),
            title = o["title"].str(),
            body = HtmlCompat.fromHtml(body, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim(),
            date = (o["submission_date"] ?: o["date"]).str()?.take(10),
            overall = (ratings?.get("overall_rating") ?: o["overall_rating"]).num(),
            performance = (ratings?.get("performance_rating") ?: o["performance_rating"]).num(),
            story = (ratings?.get("story_rating") ?: o["story_rating"]).num()
        )
    }

    suspend fun seriesBooks(seriesAsin: String): Section<List<SeriesBook>> {
        val series = audibleApi.getCatalog(
            "products/$seriesAsin",
            mapOf("response_groups" to "relationships,product_attrs")
        )
        val product = series.data?.get("product").obj()
            ?: return Section.Failed(series.error ?: "Couldn't load the series")
        val children = product["relationships"].arr().mapNotNull { it.obj() }
            .filter { it["relationship_to_product"].str() == "child" }
            .sortedBy { it["sort"].num() ?: it["sequence"].num() ?: Double.MAX_VALUE }
        if (children.isEmpty()) return Section.Failed("Audible listed no books for this series")

        val asins = children.mapNotNull { it["asin"].str() }
        val info = mutableMapOf<String, JsonObject>()
        for (chunk in asins.chunked(50)) {
            val result = audibleApi.getCatalog(
                "products",
                mapOf(
                    "asins" to chunk.joinToString(","),
                    "response_groups" to "media,product_attrs",
                    "image_sizes" to "500"
                )
            )
            result.data?.get("products").arr().forEach { p ->
                val o = p.obj() ?: return@forEach
                o["asin"].str()?.let { info[it] = o }
            }
        }
        val owned = audibleBookDao.getAllOnce().map { it.asin }.toSet()
        return Section.Loaded(children.mapNotNull { child ->
            val asin = child["asin"].str() ?: return@mapNotNull null
            val p = info[asin]
            SeriesBook(
                asin = asin,
                title = p?.get("title").str() ?: asin,
                sequence = child["sequence"].str(),
                coverUrl = p?.get("product_images").obj()?.get("500").str(),
                owned = asin in owned,
                downloaded = downloadManager.isBookDownloaded(asin)
            )
        })
    }

    // ---------------------------------------------------------------- Stores

    /** Which platforms sell the audiobook. Audible is known from the catalog. */
    suspend fun availability(title: String, author: String?): List<StoreAvailability> = listOf(
        StoreAvailability("Audible", true),
        appleBooks(title, author),
        spotify(title, author)
    )

    private suspend fun appleBooks(title: String, author: String?): StoreAvailability = try {
        val response = httpClient.get("https://itunes.apple.com/search") {
            parameter("term", listOfNotNull(title, author).joinToString(" "))
            parameter("media", "audiobook")
            parameter("entity", "audiobook")
            parameter("limit", "15")
            parameter("country", storeCountry())
        }
        val results = json.parseToJsonElement(response.bodyAsText()).obj()?.get("results").arr()
        val sold = results.any { r ->
            val o = r.obj() ?: return@any false
            matches(title, author, o["collectionName"].str(), listOfNotNull(o["artistName"].str()))
        }
        StoreAvailability("Apple Books", sold)
    } catch (e: Exception) {
        StoreAvailability("Apple Books", null, "Check failed: ${e.message}")
    }

    private var spotifyToken: String? = null
    private var spotifyTokenExpires = 0L

    private suspend fun spotify(title: String, author: String?): StoreAvailability {
        if (keys.spotifyClientId.isBlank() || keys.spotifyClientSecret.isBlank()) {
            return StoreAvailability("Spotify", null, "Add Spotify credentials in Settings to check")
        }
        return try {
            val token = spotifyAccessToken() ?: return StoreAvailability("Spotify", null, "Spotify rejected the credentials")
            val response = httpClient.get("https://api.spotify.com/v1/search") {
                header("Authorization", "Bearer $token")
                parameter("q", listOfNotNull(title, author).joinToString(" "))
                parameter("type", "audiobook")
                parameter("market", storeCountry())
                parameter("limit", "10")
            }
            if (response.status != HttpStatusCode.OK) {
                return StoreAvailability("Spotify", null, "Spotify: ${response.status}")
            }
            val items = json.parseToJsonElement(response.bodyAsText()).obj()
                ?.get("audiobooks").obj()?.get("items").arr()
            val sold = items.any { item ->
                val o = item.obj() ?: return@any false
                matches(title, author, o["name"].str(), o["authors"].arr().mapNotNull { it.obj()?.get("name").str() })
            }
            StoreAvailability("Spotify", sold)
        } catch (e: Exception) {
            StoreAvailability("Spotify", null, "Check failed: ${e.message}")
        }
    }

    private suspend fun spotifyAccessToken(): String? {
        spotifyToken?.let { if (System.currentTimeMillis() < spotifyTokenExpires) return it }
        val credentials = Base64.encodeToString(
            "${keys.spotifyClientId}:${keys.spotifyClientSecret}".toByteArray(), Base64.NO_WRAP
        )
        val response = httpClient.submitForm(
            url = "https://accounts.spotify.com/api/token",
            formParameters = parameters { append("grant_type", "client_credentials") }
        ) {
            header("Authorization", "Basic $credentials")
        }
        if (response.status != HttpStatusCode.OK) {
            debugLog.log("DETAILS", "Spotify token: ${response.status} ${response.bodyAsText().take(200)}")
            return null
        }
        val o = json.parseToJsonElement(response.bodyAsText()).obj() ?: return null
        val token = o["access_token"].str() ?: return null
        spotifyToken = token
        spotifyTokenExpires = System.currentTimeMillis() + ((o["expires_in"].num() ?: 3600.0) - 60).toLong() * 1000
        return token
    }

    private fun storeCountry(): String = when (val l = tokenStore.locale.lowercase()) {
        "uk" -> "GB"
        else -> l.uppercase()
    }

    /** Title must match (ignoring punctuation and "(Unabridged)"), and the author's surname appear. */
    private fun matches(title: String, author: String?, candidateTitle: String?, candidateAuthors: List<String>): Boolean {
        val t = normalize(title.replace(Regex("\\(.*?\\)"), ""))
        val c = normalize((candidateTitle ?: return false).replace(Regex("\\(.*?\\)"), ""))
        if (t.isEmpty() || !(c.startsWith(t) || t.startsWith(c))) return false
        val surname = author?.trim()?.split(" ")?.lastOrNull()?.let(::normalize)
        return surname.isNullOrEmpty() || candidateAuthors.any { normalize(it).contains(surname) }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    // ---------------------------------------------------------------- Hardcover

    val hardcoverConfigured: Boolean get() = keys.hardcoverToken.isNotBlank()

    suspend fun hardcover(title: String, author: String?): Section<HardcoverBook> {
        if (!hardcoverConfigured) return Section.Unavailable("Add a Hardcover API token in Settings to see reviews")
        val id = findHardcoverBook(title, author) ?: return Section.Failed(
            lastHardcoverError ?: "\"$title\" wasn't found on Hardcover"
        )
        val data = hardcoverQuery(
            """
            query Book(${'$'}id: Int!) {
              books_by_pk(id: ${'$'}id) {
                id title rating ratings_count
                user_books(where: {review_raw: {_is_null: false}}, order_by: {reviewed_at: desc_nulls_last}, limit: 25) {
                  rating review_raw reviewed_at review_has_spoilers
                  user { id username }
                }
              }
            }
            """.trimIndent(),
            buildJsonObject { put("id", id) }
        ) ?: return Section.Failed(lastHardcoverError ?: "Hardcover request failed")
        val book = data["books_by_pk"].obj() ?: return Section.Failed("Hardcover returned no book")
        return Section.Loaded(
            HardcoverBook(
                id = id,
                title = book["title"].str() ?: title,
                rating = book["rating"].num(),
                ratingsCount = book["ratings_count"].num()?.toInt(),
                reviews = book["user_books"].arr().mapNotNull { ub ->
                    val o = ub.obj() ?: return@mapNotNull null
                    val text = o["review_raw"].str()?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                    val user = o["user"].obj()
                    HardcoverReview(
                        userId = user?.get("id").num()?.toInt(),
                        username = user?.get("username").str() ?: "Hardcover reader",
                        rating = o["rating"].num(),
                        text = text,
                        date = o["reviewed_at"].str()?.take(10),
                        spoilers = o["review_has_spoilers"].str() == "true"
                    )
                }
            )
        )
    }

    suspend fun hardcoverUserReviews(userId: Int): Section<List<HardcoverUserReview>> {
        val data = hardcoverQuery(
            """
            query UserReviews(${'$'}uid: Int!) {
              user_books(where: {user_id: {_eq: ${'$'}uid}, review_raw: {_is_null: false}}, order_by: {reviewed_at: desc_nulls_last}, limit: 30) {
                rating review_raw reviewed_at
                book { title }
              }
            }
            """.trimIndent(),
            buildJsonObject { put("uid", userId) }
        ) ?: return Section.Failed(lastHardcoverError ?: "Hardcover request failed")
        return Section.Loaded(data["user_books"].arr().mapNotNull { ub ->
            val o = ub.obj() ?: return@mapNotNull null
            val text = o["review_raw"].str()?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            HardcoverUserReview(
                bookTitle = o["book"].obj()?.get("title").str() ?: "Unknown book",
                rating = o["rating"].num(),
                text = text,
                date = o["reviewed_at"].str()?.take(10)
            )
        })
    }

    private suspend fun findHardcoverBook(title: String, author: String?): Int? {
        val search = hardcoverQuery(
            """
            query Find(${'$'}q: String!) {
              search(query: ${'$'}q, query_type: "Book", per_page: 5, page: 1) { results }
            }
            """.trimIndent(),
            buildJsonObject { put("q", listOfNotNull(title, author).joinToString(" ")) }
        )
        val hits = search?.get("search").obj()?.get("results").obj()?.get("hits").arr()
            .mapNotNull { it.obj()?.get("document").obj() }
        val best = hits.firstOrNull { matches(title, author, it["title"].str(), it["author_names"].arr().mapNotNull { a -> a.str() }) }
            ?: hits.firstOrNull()
        best?.get("id")?.num()?.toInt()?.let { return it }

        // Fall back to an exact title match if search isn't available
        val exact = hardcoverQuery(
            """
            query Exact(${'$'}t: String!) {
              books(where: {title: {_eq: ${'$'}t}}, order_by: {users_count: desc}, limit: 1) { id }
            }
            """.trimIndent(),
            buildJsonObject { put("t", title) }
        )
        return exact?.get("books").arr().firstOrNull()?.obj()?.get("id").num()?.toInt()
    }

    private var lastHardcoverError: String? = null

    /** Returns the "data" object, or null with [lastHardcoverError] set. */
    private suspend fun hardcoverQuery(query: String, variables: JsonObject): JsonObject? {
        lastHardcoverError = null
        return try {
            val token = keys.hardcoverToken.let { if (it.startsWith("Bearer ", ignoreCase = true)) it else "Bearer $it" }
            val response = httpClient.post("https://api.hardcover.app/v1/graphql") {
                header("authorization", token)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("query", query)
                    put("variables", variables)
                }.toString())
            }
            val text = response.bodyAsText()
            val root = json.parseToJsonElement(text).obj()
            val errors = root?.get("errors").arr()
            if (response.status != HttpStatusCode.OK || errors.isNotEmpty()) {
                lastHardcoverError = "Hardcover: " + (errors.firstOrNull()?.obj()?.get("message").str()
                    ?: "${response.status} ${text.take(200)}")
                debugLog.log("DETAILS", lastHardcoverError!!)
                return null
            }
            root?.get("data").obj()
        } catch (e: Exception) {
            lastHardcoverError = "Hardcover: ${e.message}"
            null
        }
    }

    // ---------------------------------------------------------------- JSON helpers

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    private fun JsonElement?.num(): Double? = str()?.toDoubleOrNull()

    private fun flatten(element: JsonElement?, path: String, out: MutableList<Pair<String, String>>) {
        if (out.size >= 400) return
        when (element) {
            is JsonObject -> element.forEach { (k, v) -> flatten(v, if (path.isEmpty()) k else "$path.$k", out) }
            is JsonArray -> element.forEachIndexed { i, v -> flatten(v, "$path[$i]", out) }
            is JsonPrimitive -> if (element !is JsonNull) out += path to element.content.take(300)
            else -> {}
        }
    }
}
