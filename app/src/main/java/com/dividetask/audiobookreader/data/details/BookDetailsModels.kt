package com.dividetask.audiobookreader.data.details

/** Result of one section of the details screen: each source loads and fails on its own. */
sealed interface Section<out T> {
    data object Loading : Section<Nothing>
    /** Not configured (e.g. no API key) — [message] says how to set it up. */
    data class Unavailable(val message: String) : Section<Nothing>
    data class Failed(val message: String) : Section<Nothing>
    data class Loaded<T>(val value: T) : Section<T>
}

data class AudibleDetails(
    val asin: String,
    val title: String,
    val subtitle: String?,
    val authors: List<String>,
    val narrators: List<String>,
    val publisher: String?,
    val releaseDate: String?,
    val runtimeMinutes: Int?,
    val language: String?,
    val coverUrl: String?,
    /** Plain text (HTML stripped). */
    val summary: String?,
    val rating: AudibleRating?,
    val genres: List<String>,
    val series: List<SeriesRef>,
    val reviews: List<AudibleReview>,
    /** Every scalar field in the response, flattened ("rating.overall_distribution.num_ratings: 4803"). */
    val raw: List<Pair<String, String>>
)

data class AudibleRating(
    val overall: Double?,
    val count: Int?,
    val performance: Double?,
    val story: Double?
)

data class SeriesRef(val asin: String?, val title: String, val sequence: String?)

data class AudibleReview(
    val author: String?,
    val title: String?,
    val body: String,
    val date: String?,
    val overall: Double?,
    val performance: Double?,
    val story: Double?
)

data class SeriesBook(
    val asin: String,
    val title: String,
    val sequence: String?,
    val coverUrl: String?,
    val owned: Boolean,
    val downloaded: Boolean
)

data class StoreAvailability(
    val store: String,
    /** null = couldn't check (see [note]). */
    val sold: Boolean?,
    val note: String? = null
)

data class HardcoverBook(
    val id: Int,
    val title: String,
    val rating: Double?,
    val ratingsCount: Int?,
    val reviews: List<HardcoverReview>
)

data class HardcoverReview(
    val userId: Int?,
    val username: String,
    val rating: Double?,
    val text: String,
    val date: String?,
    val spoilers: Boolean
)

data class HardcoverUserReview(
    val bookTitle: String,
    val rating: Double?,
    val text: String,
    val date: String?
)
